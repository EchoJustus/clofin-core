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
            [clofin.screening.repository :as screening-repo]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
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
