(ns clofin.recon.concurrency-test
  "Reconciliation under two connections, where the outcome depends on timing.

  Ordinary functional tests stay green after a lock is removed or an
  arbitration branch takes a shortcut, because nothing in them ever loses a
  race. These force the interleaving instead — one transaction holds a row or a
  replay key while the other runs into it — which is the only shape that can
  fail when the serialisation is wrong (standing lesson **L-8**; the harness is
  `clofin.ledger.repository-test`'s, reused rather than reinvented).

  Three findings of the `ref-2` release audit live here:

  - **2C-002** (blocking, lesson **L-18**) — both receipt-collision branches
    replayed the winner's receipt without comparing digests, so a document
    nobody processed was acknowledged as an exact replay of a different one.
    `ac-1-*` is the four-cell matrix {identical, different} x {applied,
    refused}.
  - **2B-004** (lesson **L-8**) — reconciliation's locking had no two-session
    proof at all. `ac-8-*` are the two the finding names: assignment against
    resolution on one break, and two concurrent decisions on one adjustment.

  Every walk here calls the fully-wrapped public handler, so what is under test
  is the behaviour a caller meets rather than a service function called
  directly."
  (:require [clofin.db.core :as db]
            [clofin.recon.repository :as recon]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time Instant LocalDate ZoneOffset]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private value-date (str (.plusDays (LocalDate/now ZoneOffset/UTC) 7)))

;; ---------------------------------------------------------------------------
;; Calling the API — from a thread, so the pool is passed rather than bound
;; ---------------------------------------------------------------------------

(defn- call
  "One public request. `pool` is explicit because `tdb/*pool*` is a dynamic
  binding and dynamic bindings do not cross threads."
  [pool method uri & {:keys [body query actor idempotency-key]}]
  (let [handler (system/handler {:config {:environment :test} :pool pool})
        [path inline-query] (str/split uri #"\?" 2)
        response (handler
                  (cond-> {:request-method method :uri path :headers {}}
                    (or query inline-query) (assoc :query-string (or query inline-query))
                    actor (assoc-in [:headers "x-actor-id"] (str actor))
                    idempotency-key (assoc-in [:headers "idempotency-key"] idempotency-key)
                    body (-> (assoc-in [:headers "content-type"] "application/json")
                             (assoc :body (ByteArrayInputStream.
                                           (.getBytes (json/write-str body)
                                                      StandardCharsets/UTF_8))))))]
    (assoc response :json (when-not (str/blank? (:body response))
                            (json/read-str (:body response))))))

(defn- uuid [s] (java.util.UUID/fromString s))

;; ---------------------------------------------------------------------------
;; Forcing the interleaving
;; ---------------------------------------------------------------------------

(defn- both-at-once
  "Run `f` and `g` on two threads and return `[result-of-f result-of-g]`.

  A thrown exception is returned rather than lost, so a test asserting on an
  outcome never sees a silently missing one."
  [f g]
  (let [results (object-array 2)
        done (CountDownLatch. 2)
        run (fn [i h]
              (.start (Thread. (fn []
                                 (try (aset results i (h))
                                      (catch Throwable t (aset results i t))
                                      (finally (.countDown done)))))))]
    (run 0 f)
    (run 1 g)
    (is (.await done 90 TimeUnit/SECONDS) "both deliveries must finish")
    [(aget results 0) (aget results 1)]))

(defn- with-both-lookups-empty
  "Run `f` with the receipt lookup held open until two threads have used it.

  The first lookup **on each thread** returns only once both have looked, so
  both see no receipt under the reference and both go on to insert one. That is
  the state the collision branches exist for, and it is the only thing this
  fixture controls: the insert, the savepoint rollback, the commit and the
  rendering are all real.

  Later lookups pass straight through — `replay-of` uses the same function to
  read the winner's row after losing the key, and gating that one would
  deadlock the very branch under test."
  [f]
  (let [original recon/find-statement-by-reference
        looked (CountDownLatch. 2)
        seen (atom #{})]
    (with-redefs [recon/find-statement-by-reference
                  (fn [source organisation-id reference]
                    (let [result (original source organisation-id reference)
                          thread (Thread/currentThread)
                          [before _] (swap-vals! seen conj thread)]
                      (when-not (contains? before thread)
                        (.countDown looked)
                        (.await looked 60 TimeUnit/SECONDS))
                      result))]
      (f))))

;; ---------------------------------------------------------------------------
;; Fixtures — built through the API wherever the API can build them
;; ---------------------------------------------------------------------------

(def ^:private accounts
  [["1100-CLIENT-FUNDS" "asset"]
   ["1300-IN-TRANSIT" "asset"]
   ["2100-CLIENT-PAYABLE" "liability"]
   ["2200-UNAPPLIED" "liability"]])

(defn- setup
  [& {:keys [open-accounts] :or {open-accounts accounts}}]
  (let [pool tdb/*pool*
        {:keys [status json]} (call pool :post "/organisations"
                                    :body {"legalName" "Meridian Freight Holdings Pte Ltd"
                                           "shortName" (str "meridian-" (rand-int 100000000))})
        _ (is (= 201 status))
        org (uuid (get json "id"))
        seed (fn [roles limits]
               (tdb/insert-actor! pool {:organisation-id org
                                        :display-name (str/join "+" (map name roles))
                                        :roles roles :limits limits}))
        controller (seed [:controller] {})
        maker (seed [:operator] {})
        checker (seed [:approver] {"SGD" 100000000})
        checker-2 (seed [:approver] {"SGD" 100000000})
        auditor (seed [:auditor] {})]
    (tdb/insert-threshold! pool {:organisation-id org :currency "SGD"
                                 :from-minor 100000 :approvals-required 1})
    (tdb/insert-threshold! pool {:organisation-id org :currency "SGD"
                                 :from-minor 1000000 :approvals-required 2})
    (doseq [[code type] open-accounts]
      (is (= 201 (:status (call pool :post "/accounts"
                                :actor controller
                                :body {"organisationId" (str org) "code" code
                                       "name" code "type" type "currency" "SGD"})))))
    {:pool pool :org org :controller controller :maker maker :checker checker
     :checker-2 checker-2 :auditor auditor}))

(defn- account-id
  [{:keys [pool org controller]} code]
  (->> (get (:json (call pool :get "/accounts" :actor controller
                         :query (str "organisationId=" org)))
            "accounts")
       (filter #(= code (get % "code")))
       first
       (#(get % "id"))))

(defn- raise!
  [{:keys [pool org maker checker] :as f} amount]
  (let [{:keys [status json]}
        (call pool :post "/payment-instructions"
              :actor maker :idempotency-key (str (random-uuid))
              :body {"organisationId" (str org)
                     "debtorAccountId" (account-id f "1100-CLIENT-FUNDS")
                     "creditorName" "Pacific Rim Logistics Pte Ltd"
                     "creditorAccount" "SG-SYNTH-88012340"
                     "amount" {"currency" "SGD" "minorUnits" amount}
                     "valueDate" value-date
                     "purposeCode" "SUPP"})
        _ (is (= 201 status) (str "instruction not created: " json))
        id (get json "id")]
    (is (= 200 (:status (call pool :post (str "/payment-instructions/" id "/submission")
                              :actor maker :idempotency-key (str (random-uuid))
                              :body {"organisationId" (str org)}))))
    (is (= 201 (:status (call pool :post (str "/payment-instructions/" id "/approvals")
                              :actor checker :idempotency-key (str (random-uuid))
                              :body {"organisationId" (str org) "decision" "approved"}))))
    id))

(defn- settle!
  "Raise, batch, submit and answer — the settlement the reconciliation is about."
  [{:keys [pool org controller] :as f}]
  (let [settled (raise! f 125000)
        returned (raise! f 110000)
        batch (get (:json (call pool :post "/settlement-batches"
                                :actor controller
                                :body {"organisationId" (str org) "scheme" "SIM-RTGS"
                                       "currency" "SGD" "valueDate" value-date
                                       "instructionIds" [settled returned]}))
                   "id")]
    (is (= 200 (:status (call pool :post (str "/settlement-batches/" batch "/submit")
                              :actor controller :body {"organisationId" (str org)}))))
    (is (= 200 (:status (call pool :post (str "/settlement-batches/" batch "/scheme-responses")
                              :actor controller
                              :body {"organisationId" (str org) "kind" "settled"
                                     "instructionId" settled
                                     "reference" (str "SIM-STL-" batch "-" settled)}))))
    (is (= 200 (:status (call pool :post (str "/settlement-batches/" batch "/scheme-responses")
                              :actor controller
                              :body {"organisationId" (str org) "kind" "returned"
                                     "instructionId" returned
                                     "reason" "SIM-RETURN: simulated scheme return"
                                     "reference" (str "SIM-RTN-" batch "-" returned)}))))
    {:settled settled :returned returned :batch batch}))

(defn- period []
  (let [midnight (.toInstant (.atStartOfDay (LocalDate/now ZoneOffset/UTC) ZoneOffset/UTC))]
    {:from (str (.minusSeconds midnight 86400))
     :to (str (.plusSeconds midnight 86400))}))

(defn- generate!
  [{:keys [pool org controller]}]
  (let [{:keys [from to]} (period)
        {:keys [status json]}
        (call pool :get "/settlement-statements"
              :actor controller
              :query (str "organisationId=" org "&scheme=SIM-RTGS&currency=SGD"
                          "&from=" from "&to=" to "&perturbation=none"))]
    (is (= 200 status) (str "statement not generated: " json))
    json))

(defn- ingest-fn
  "A thunk that delivers `document` through the public handler."
  [{:keys [pool org controller]} document]
  (fn [] (call pool :post "/reconciliation-statements"
               :actor controller
               :body (assoc document "organisationId" (str org)))))

;; ---------------------------------------------------------------------------
;; Counting what the losing delivery must not have done
;; ---------------------------------------------------------------------------

(defn- counts
  [pool org]
  (let [n (fn [sql] (:count (db/query-one pool (into [sql] [org]))))]
    {:receipts (n "select count(*) as count from reconciliation_statement
                    where organisation_id = ?")
     :lines (n "select count(*) as count from reconciliation_statement_line l
                 join reconciliation_statement s on s.id = l.statement_id
                where s.organisation_id = ?")
     :matches (n "select count(*) as count from reconciliation_match m
                   join reconciliation_statement s on s.id = m.statement_id
                  where s.organisation_id = ?")
     :breaks (n "select count(*) as count from reconciliation_break
                  where organisation_id = ?")
     :arrivals (n "select count(*) as count from audit_event
                    where organisation_id = ?
                      and action = 'reconciliation-statement.received'")}))

(defn- outcome
  "What one delivery answered: its status, whether it was called a replay, and
  the refusal reason when it was refused.

  `contains?` rather than `or`, because the value under test is a boolean and
  `false` is the answer that matters most here — an `or` would report it as
  missing."
  [response]
  (if (instance? Throwable response)
    {:status :threw :error (str response)}
    (let [body (:json response)]
      {:status (:status response)
       :replayed (if (contains? body "replayed")
                   (get body "replayed")
                   (get-in body ["errors" "replayed"]))
       :reason (get-in body ["errors" "dispositionReason"])})))

;; ---------------------------------------------------------------------------
;; AC-1 (2C-002) — the four cells
;;
;; The pre-check compares digests; before this fix, neither collision branch
;; did. A loser therefore replayed the winner's receipt on the strength of the
;; key alone, and answered `replayed: true` for a document CloFin had never
;; seen. The serial path answers 409 for exactly that arrival, which is what
;; makes the disagreement a defect rather than a design choice (ADR-0023:
;; "a *different* document under the same reference is refused and is never
;; called a replay").
;; ---------------------------------------------------------------------------

(defn- applied-documents
  "Two documents under one reference: the statement the simulation really sent,
  and the same statement with one amount changed by a minor unit."
  [f]
  (let [document (generate! f)
        tampered (update document "lines"
                         (fn [lines]
                           (update-in (vec lines) [0 "amount" "minorUnits"] + 1)))]
    [document tampered]))

(defn- refused-document
  "A statement for an organisation with no `1300-IN-TRANSIT`: refused with a
  receipt, which is the second stored outcome a collision can find."
  [period-end]
  {"format" "SIM-CLOFIN-RECON-STATEMENT"
   "formatVersion" 1
   "scheme" "SIM-RTGS"
   "currency" "SGD"
   "statementReference" "SIM-STMT-RACE"
   "periodStart" (:from (period))
   "periodEnd" period-end
   "lines" []})

(deftest ac-1-identical-documents-racing-produce-one-receipt-and-one-replay
  (testing "the applied path: whichever transaction loses the key replays the
            winner's receipt, because it really is the same document"
    (let [f (setup)
          _ (settle! f)
          [document _] (applied-documents f)
          [a b] (with-both-lookups-empty
                  #(both-at-once (ingest-fn f document) (ingest-fn f document)))
          answers (map outcome [a b])]
      (is (= [200 200] (mapv :status answers)) (pr-str answers))
      (is (= 1 (count (filter :replayed answers)))
          (str "exactly one of the two is a replay — " (pr-str answers)))
      (let [c (counts (:pool f) (:org f))]
        (is (= 1 (:receipts c)) "one receipt row, not two")
        (is (= 1 (:arrivals c)) "one arrival event, not two")))))

(deftest ac-1-different-documents-racing-refuse-the-loser-rather-than-replaying-it
  (testing "the applied path, and the finding itself: a document nobody
            processed must never be acknowledged as an exact replay of a
            different one (2C-002, L-18)"
    (let [f (setup)
          _ (settle! f)
          [document tampered] (applied-documents f)
          [a b] (with-both-lookups-empty
                  #(both-at-once (ingest-fn f document) (ingest-fn f tampered)))
          answers (map outcome [a b])
          ;; Which of the two documents wins the key is genuinely up to the
          ;; scheduler, so nothing here assumes it. What is asserted is that
          ;; the database holds the winner's work and nothing besides.
          won (first (filter #(and (map? %) (= 200 (:status %))) [a b]))
          winner (first (filter #(= 200 (:status %)) answers))
          loser (first (filter #(= 409 (:status %)) answers))]
      (is (= #{200 409} (set (map :status answers)))
          (str "one applies and one is refused the reference — " (pr-str answers)))
      (is (false? (:replayed winner))
          "the winner did the work, so it is not a replay")
      (is (some? loser) (str "no 409 among " (pr-str answers)))
      (is (= "replay-key-conflict" (:reason loser))
          (str "the loser must re-enter the decision the serial path makes — "
               (pr-str loser)))
      (is (false? (:replayed loser))
          (str "and it is never called a replay — " (pr-str loser)))
      (let [c (counts (:pool f) (:org f))]
        (is (= 1 (:receipts c)) "one receipt row: the loser wrote none")
        (is (= 1 (:arrivals c)) "one arrival event: the loser recorded none")
        (is (= 2 (:lines c)) "one document's worth of lines, not two documents'")
        (is (= (count (get-in won [:json "matches"])) (:matches c))
            "exactly the matches the winner reported, and no others")
        (is (= (count (get-in won [:json "breaks"])) (:breaks c))
            "exactly the breaks the winner reported: the loser opened none")))))

(deftest ac-1-identical-documents-racing-on-the-refused-path-replay-the-refusal
  (testing "the refused path: a receipt exists for a statement CloFin could not
            process, and the loser reproduces that same refusal"
    (let [f (setup :open-accounts [["1100-CLIENT-FUNDS" "asset"]
                                   ["2200-UNAPPLIED" "liability"]])
          document (refused-document (:to (period)))
          [a b] (with-both-lookups-empty
                  #(both-at-once (ingest-fn f document) (ingest-fn f document)))
          answers (map outcome [a b])]
      (is (= [422 422] (mapv :status answers)) (pr-str answers))
      (is (every? #(= "no-reconciled-account" (:reason %)) answers)
          (str "both are told why the statement could not be processed — "
               (pr-str answers)))
      (is (= 1 (count (filter :replayed answers)))
          (str "exactly one of the two is a replay — " (pr-str answers)))
      (let [c (counts (:pool f) (:org f))]
        (is (= 1 (:receipts c)) "one receipt row, not two")
        (is (= 1 (:arrivals c)) "one arrival event, not two")))))

(deftest ac-1-different-documents-racing-on-the-refused-path-refuse-the-loser
  (testing "the fourth cell: the stored outcome is a refusal and the documents
            differ, so the loser is answered 409 rather than handed somebody
            else's refusal as its own"
    (let [f (setup :open-accounts [["1100-CLIENT-FUNDS" "asset"]
                                   ["2200-UNAPPLIED" "liability"]])
          later (str (.plusSeconds (Instant/parse (:to (period))) 3600))
          [a b] (with-both-lookups-empty
                  #(both-at-once (ingest-fn f (refused-document (:to (period))))
                                 (ingest-fn f (refused-document later))))
          answers (map outcome [a b])
          loser (first (filter #(= 409 (:status %)) answers))]
      (is (= #{422 409} (set (map :status answers)))
          (str "one receipt is written and refused, the other reference is
                refused outright — " (pr-str answers)))
      (is (some? loser) (str "no 409 among " (pr-str answers)))
      (is (= "replay-key-conflict" (:reason loser)) (pr-str loser))
      (is (false? (boolean (:replayed loser))) (pr-str loser))
      (let [c (counts (:pool f) (:org f))]
        (is (= 1 (:receipts c)) "one receipt row: the loser wrote none")
        (is (= 1 (:arrivals c)) "one arrival event: the loser recorded none")))))

(deftest ac-1-the-serial-answer-and-the-raced-answer-are-the-same
  (testing "the decision is one decision (L-18): the same contradiction sent
            one after the other is answered exactly as it is when the two
            arrive together"
    (let [f (setup)
          _ (settle! f)
          [document tampered] (applied-documents f)
          _ (is (= 200 (:status ((ingest-fn f document)))))
          serial (outcome ((ingest-fn f tampered)))]
      (is (= 409 (:status serial)) (pr-str serial))
      (is (= "replay-key-conflict" (:reason serial)) (pr-str serial))
      (is (false? (boolean (:replayed serial))) (pr-str serial)))))
