(ns clofin.screening.concurrency-test
  "An amendment and a client's screening result, in flight together on one
  instruction (TASK-017 AC-17-7; ADR-0028's *Verification* row \"Screening race
  against another transition\").

  The recording path reads the instruction `for update` and compares the
  digest the row holds **under that lock** (lesson **L-8**), so the two
  serialise on the row and whichever commits second sees the first:

  - **the amendment first** — the result, sent with the pre-amendment digest,
    waits for the amendment to commit, then reads the amended row and is
    refused `instruction-digest-mismatch`. Nothing is stored.
  - **the result first** — it is accepted against the digest the row held
    under its lock; the amendment waits, then commits, and the instruction's
    digest has since moved. The decision stays bound to the content it was
    about.

  **Never** an accepted result that committed after the amendment while bound
  to the content the amendment replaced: a decision about content the row no
  longer held when the decision was taken.

  The interleaving is forced, not hoped for — one transaction is parked inside
  its unit of work with the row locked, and `pg_stat_activity` is read to show
  the other waiting on that lock — in the harness shape of
  `clofin.recon.concurrency-test` and `clofin.api.payments-api-test`'s AC-18-3.
  Synthetic data only."
  (:require [clofin.audit.repository :as audit-store]
            [clofin.db.core :as db]
            [clofin.payments.repository :as payments]
            [clofin.screening.repository :as screening-repo]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clofin.tools.screening-list :as screening-tool]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time LocalDate ZoneOffset]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(defn- call
  "One public request. `pool` is explicit: dynamic bindings do not cross threads."
  [pool method uri & {:keys [body actor idempotency-key]}]
  (let [handler (system/handler {:config {:environment :test} :pool pool})
        response (handler
                  (cond-> {:request-method method :uri uri :headers {}}
                    actor (assoc-in [:headers "x-actor-id"] (str actor))
                    idempotency-key (assoc-in [:headers "idempotency-key"] idempotency-key)
                    body (-> (assoc-in [:headers "content-type"] "application/json")
                             (assoc :body (ByteArrayInputStream.
                                           (.getBytes (json/write-str body) StandardCharsets/UTF_8))))))]
    (assoc response :json (when-not (str/blank? (:body response))
                            (json/read-str (:body response))))))

(defn- on-thread
  "Run `f` on a thread of its own; deref the promise for its value or throwable."
  [f]
  (let [p (promise)]
    (.start (Thread. (fn [] (deliver p (try (f) (catch Throwable t t))))))
    p))

(defn- await-lock-wait!
  "Block until some backend is waiting on a lock while running a statement
  matching `pattern`; the count, or 0 after ten seconds."
  [pattern]
  (loop [n 0]
    (let [waiting (:count (db/query-one tdb/*pool*
                                        ["select count(*) as count from pg_stat_activity
                                           where datname = current_database()
                                             and wait_event_type = 'Lock'
                                             and query ilike ?" pattern]))]
      (if (or (pos? waiting) (> n 500))
        waiting
        (do (Thread/sleep 20) (recur (inc n)))))))

(def ^:private instruction-lock "%from payment_instruction%for update%")

(defn- setup
  []
  (let [pool tdb/*pool*
        org (get (:json (call pool :post "/organisations"
                              :body {"legalName" "Meridian Freight Holdings Pte Ltd"
                                     "shortName" (str "meridian-" (random-uuid))}))
                 "id")
        org-id (java.util.UUID/fromString org)
        seed (fn [roles] (tdb/insert-actor! pool {:organisation-id org-id
                                                  :display-name (str/join "+" (map name roles))
                                                  :roles roles}))
        controller (seed [:controller])
        maker (seed [:operator])
        screener (seed [:screening-service])
        account (get (:json (call pool :post "/accounts" :actor controller
                                  :body {"organisationId" org "code" "1100-CLIENT-FUNDS"
                                         "name" "Client funds" "type" "asset" "currency" "SGD"}))
                     "id")
        pi (:json (call pool :post "/payment-instructions" :actor maker
                        :idempotency-key (str (random-uuid))
                        :body {"organisationId" org "debtorAccountId" account
                               "creditorName" "Pacific Rim Logistics Pte Ltd"
                               "creditorAccount" "SG-SYNTH-88012345"
                               "amount" {"currency" "SGD" "minorUnits" 125000}
                               "valueDate" (str (.plusDays (LocalDate/now ZoneOffset/UTC) 7))
                               "purposeCode" "SUPP"}))]
    {:pool pool :org org :maker maker :screener screener :pi pi
     :id (get pi "id") :d0 (get pi "screeningDigest")}))

(defn- amend [{:keys [pool org maker id]}]
  (call pool :patch (str "/payment-instructions/" id) :actor maker
        :idempotency-key (str (random-uuid))
        :body {"organisationId" org "creditorName" "Andaman Shipping Sdn Bhd"}))

(defn- record [{:keys [pool org screener id d0]}]
  (call pool :post (str "/payment-instructions/" id "/screening-results") :actor screener
        :idempotency-key (str (random-uuid))
        :body {"organisationId" org "listVersion" "synthetic-2026-10-v1" "outcome" "clear"
               "matchedEntries" [] "instructionDigest" d0}))

(defn- current-digest [{:keys [pool maker id]}]
  (get (:json (call pool :get (str "/payment-instructions/" id) :actor maker)) "screeningDigest"))

(defn- stored-results [{:keys [id]}]
  (db/query tdb/*pool* ["select disposition, instruction_digest from screening_result
                          where instruction_id = ?" (java.util.UUID/fromString id)]))

(deftest ac-17-7-amend-and-screening-result-serialise-on-the-instruction-row
  (testing "the amendment holds the row: the result waits, then sees the amended
            row, and is refused"
    (let [f (setup)
          parked (CountDownLatch. 1)
          release (CountDownLatch. 1)
          amended-committed (CountDownLatch. 1)
          original-record audit-store/record!
          original-insert screening-repo/insert-result!]
      (with-redefs [;; Park the amendment after its UPDATE, inside its transaction,
                    ;; with the row lock held.
                    audit-store/record! (fn [tx event]
                                          (when (= "payment.amended" (:action event))
                                            (.countDown parked)
                                            (.await release 60 TimeUnit/SECONDS))
                                          (original-record tx event))
                    ;; Should the result ever get past its digest check while the
                    ;; amendment is still open — which only a recording path
                    ;; without the lock can — it is held here until the
                    ;; amendment has committed, so it commits second.
                    screening-repo/insert-result! (fn [tx row]
                                                    (.await amended-committed 60 TimeUnit/SECONDS)
                                                    (original-insert tx row))]
        (let [a (on-thread #(let [r (amend f)] (.countDown amended-committed) r))]
          (is (.await parked 30 TimeUnit/SECONDS) "the amendment reached its park point")
          (let [b (on-thread #(record f))]
            (is (pos? (await-lock-wait! instruction-lock))
                "non-vacuity: the result is waiting on the instruction's row lock")
            (.countDown release)
            (let [a* (deref a 60000 ::timeout) b* (deref b 60000 ::timeout)]
              (is (= 200 (:status a*)) (pr-str (:json a*)))
              (is (= 422 (:status b*)) (pr-str (:json b*)))
              (is (= "instruction-digest-mismatch" (get-in b* [:json "errors" "reason"])))))))
      (let [d1 (current-digest f)]
        (is (not= (:d0 f) d1) "the amendment moved the digest")
        (is (empty? (stored-results f))
            "the forbidden state is absent: no result accepted after the amendment against the content it replaced"))))

  (testing "the result holds the row: it is accepted against the digest the row
            held under its lock, and the instruction's digest has since moved"
    (let [f (setup)
          parked (CountDownLatch. 1)
          release (CountDownLatch. 1)
          original-insert screening-repo/insert-result!]
      (with-redefs [screening-repo/insert-result! (fn [tx row]
                                                    (.countDown parked)
                                                    (.await release 60 TimeUnit/SECONDS)
                                                    (original-insert tx row))]
        (let [b (on-thread #(record f))]
          (is (.await parked 30 TimeUnit/SECONDS) "the result reached its park point")
          (let [a (on-thread #(amend f))]
            (is (pos? (await-lock-wait! instruction-lock))
                "non-vacuity: the amendment is waiting on the instruction's row lock")
            (.countDown release)
            (let [a* (deref a 60000 ::timeout) b* (deref b 60000 ::timeout)]
              (is (= 201 (:status b*)) (pr-str (:json b*)))
              (is (= 200 (:status a*)) (pr-str (:json a*)))))))
      (let [d1 (current-digest f)]
        (is (= [{:disposition "accepted" :instruction-digest (:d0 f)}] (stored-results f))
            "accepted, bound to the digest the row held when it was decided")
        (is (not= (:d0 f) d1) "and the instruction has since moved on"))))

  (testing "a submission and a result serialise the same way: evidence about a
            submitted instruction is refused in the lifecycle's own shape"
    (let [f (setup)
          parked (CountDownLatch. 1)
          release (CountDownLatch. 1)
          original-record audit-store/record!]
      (with-redefs [audit-store/record! (fn [tx event]
                                          (when (= "payment.submitted" (:action event))
                                            (.countDown parked)
                                            (.await release 60 TimeUnit/SECONDS))
                                          (original-record tx event))]
        (let [a (on-thread #(call (:pool f) :post (str "/payment-instructions/" (:id f) "/submission")
                                  :actor (:maker f) :idempotency-key (str (random-uuid))
                                  :body {"organisationId" (:org f)}))]
          (is (.await parked 30 TimeUnit/SECONDS))
          (let [b (on-thread #(record f))]
            (is (pos? (await-lock-wait! instruction-lock)) "non-vacuity")
            (.countDown release)
            (let [a* (deref a 60000 ::timeout) b* (deref b 60000 ::timeout)]
              (is (= 200 (:status a*)))
              (is (= 409 (:status b*)) (pr-str (:json b*)))
              (is (= "pending-approval" (get-in b* [:json "errors" "instruction-status"])))))))
      (is (= ["core"] (mapv :origin (db/query tdb/*pool* ["select origin from screening_result
                                                            where instruction_id = ?"
                                                           (java.util.UUID/fromString (:id f))])))
          "only core's own decision at the submission is stored"))))

;; ---------------------------------------------------------------------------
;; A list retirement and a screening decision (017-REQ R-7)
;; ---------------------------------------------------------------------------

(defn- submit [{:keys [pool org maker id]}]
  (call pool :post (str "/payment-instructions/" id "/submission") :actor maker
        :idempotency-key (str (random-uuid)) :body {"organisationId" org}))

(defn- status-of [{:keys [pool maker id]}]
  (get (:json (call pool :get (str "/payment-instructions/" id) :actor maker)) "status"))

(def ^:private list-lock
  "The statement of a transaction waiting on the list lock — shared or
  exclusive, `clofin.screening.list/lock-key` — which carries this comment."
  "%screening list lock%")

(defn- results-recorded-after-their-list-retired
  "Results whose `recorded_at` is not before their list's `retired_at` — a
  decision taken against a list after it was retired."
  []
  (db/query tdb/*pool* ["select r.id, r.recorded_at, l.retired_at
                           from screening_result r
                           join screening_list l on l.version = r.list_version
                          where l.retired_at is not null and r.recorded_at >= l.retired_at"]))

(deftest a-retirement-in-flight-holds-the-list-and-a-submission-waits-then-decides-nothing
  (testing "the retirement holds the list lock: a submission waits for it, then
            finds no list accepted and decides nothing"
    (let [f (setup)
          parked (CountDownLatch. 1)
          release (CountDownLatch. 1)
          original-retire screening-tool/retire-version!]
      (with-redefs [screening-tool/retire-version! (fn [tx version at]
                                                     (let [v (original-retire tx version at)]
                                                       (.countDown parked)
                                                       (.await release 60 TimeUnit/SECONDS)
                                                       v))]
        (let [t (on-thread #(screening-tool/retire! (:pool f) "synthetic-2026-10-v1"))]
          (is (.await parked 30 TimeUnit/SECONDS) "the retirement reached its park point, uncommitted")
          (let [s (on-thread #(submit f))]
            (is (pos? (await-lock-wait! list-lock))
                "non-vacuity: the submission is waiting on the list lock")
            (.countDown release)
            (let [t* (deref t 60000 ::timeout) s* (deref s 60000 ::timeout)]
              (is (= "synthetic-2026-10-v1" t*) (pr-str t*))
              (is (= 422 (:status s*)) (pr-str (:json s*)))
              (is (= "no-screening-list-accepted" (get-in s* [:json "errors" "reason"])))))))
      (is (= "draft" (status-of f)))
      (is (empty? (stored-results f)) "nothing decided against the retired list"))))

(deftest a-replacement-in-flight-holds-the-list-and-a-submission-screens-against-the-replacement
  (testing "a replacement holds the list lock: the submission waits, then
            screens against the replacement the same transaction committed —
            its read is a statement whose snapshot follows that commit"
    (let [f (setup)
          parked (CountDownLatch. 1)
          release (CountDownLatch. 1)
          original-retire screening-tool/retire-version!]
      (with-redefs [screening-tool/retire-version! (fn [tx version at]
                                                     (let [v (original-retire tx version at)]
                                                       (.countDown parked)
                                                       (.await release 60 TimeUnit/SECONDS)
                                                       v))]
        (let [t (on-thread #(screening-tool/load-list!
                             (:pool f)
                             {:version "synthetic-2026-10-v2"
                              :entries [{:id "SYN-0001"
                                         :rules [{:field :creditor-name :operator :exact
                                                  :value "Blocked Counterparty Ltd"}]}]}
                             {:source "test" :replacing "synthetic-2026-10-v1"}))]
          (is (.await parked 30 TimeUnit/SECONDS))
          (let [s (on-thread #(submit f))]
            (is (pos? (await-lock-wait! list-lock)) "non-vacuity")
            (.countDown release)
            (let [t* (deref t 60000 ::timeout) s* (deref s 60000 ::timeout)]
              (is (= "synthetic-2026-10-v2" (:version t*)) (pr-str t*))
              (is (= 200 (:status s*)) (pr-str (:json s*)))))))
      (is (= ["synthetic-2026-10-v2"]
             (mapv :list-version (db/query tdb/*pool* ["select list_version from screening_result
                                                         where instruction_id = ?"
                                                        (java.util.UUID/fromString (:id f))])))
          "screened against the replacement, never the retired list"))))

(deftest a-decision-in-flight-holds-the-list-and-the-retirement-is-stamped-after-it
  (testing "the decision holds the list lock: the retirement waits for it,
            and is stamped after it — no result is ever recorded after its
            list's retired_at"
    (let [f (setup)
          parked (CountDownLatch. 1)
          release (CountDownLatch. 1)
          original-insert screening-repo/insert-result!]
      (with-redefs [screening-repo/insert-result! (fn [tx row]
                                                    (.countDown parked)
                                                    (.await release 60 TimeUnit/SECONDS)
                                                    (original-insert tx row))]
        (let [s (on-thread #(submit f))]
          (is (.await parked 30 TimeUnit/SECONDS)
              "the submission holds the list's row and has not yet recorded its result")
          (let [t (on-thread #(screening-tool/retire! (:pool f) "synthetic-2026-10-v1"))]
            (is (pos? (await-lock-wait! list-lock))
                "non-vacuity: the retirement is waiting on the decision's hold of the list lock")
            (.countDown release)
            (let [s* (deref s 60000 ::timeout) t* (deref t 60000 ::timeout)]
              (is (= 200 (:status s*)) (pr-str (:json s*)))
              (is (= "synthetic-2026-10-v1" t*) (pr-str t*))))))
      (is (= 1 (count (stored-results f))))
      (is (empty? (results-recorded-after-their-list-retired))
          "the forbidden state is absent: a result recorded at or after its list's retirement"))))

(deftest a-retirement-in-flight-and-a-direct-transition-serialise-at-the-gate
  (testing "the repository's own gate takes the list lock too: a caller of
            `transition!` that bypasses the service, racing a retirement,
            waits for it and is refused `screening-required` — the retired list
            permits nothing (017-REQ R-7; the gate is the one that cannot be
            skipped)"
    (let [f (setup)
          org-id (java.util.UUID/fromString (:org f))
          pi-id (java.util.UUID/fromString (:id f))
          _ (tdb/record-core-screening! tdb/*pool* org-id pi-id (:maker f))
          parked (CountDownLatch. 1)
          release (CountDownLatch. 1)
          original-retire screening-tool/retire-version!]
      (with-redefs [screening-tool/retire-version! (fn [tx version at]
                                                     (let [v (original-retire tx version at)]
                                                       (.countDown parked)
                                                       (.await release 60 TimeUnit/SECONDS)
                                                       v))]
        (let [t (on-thread #(screening-tool/retire! (:pool f) "synthetic-2026-10-v1"))]
          (is (.await parked 30 TimeUnit/SECONDS) "the retirement reached its park point, uncommitted")
          (let [s (on-thread #(payments/transition! (:pool f) org-id pi-id :submit
                                                    {:actor {:id (:maker f)}}))]
            (is (pos? (await-lock-wait! list-lock))
                "non-vacuity: the direct transition is waiting on the list lock")
            (.countDown release)
            (let [t* (deref t 60000 ::timeout) s* (deref s 60000 ::timeout)]
              (is (= "synthetic-2026-10-v1" t*) (pr-str t*))
              (is (instance? clojure.lang.ExceptionInfo s*) (pr-str s*))
              (is (= "screening-required" (:reason (ex-data s*))) (pr-str (ex-data s*)))))))
      (is (= "draft" (status-of f)) "nothing moved"))))
