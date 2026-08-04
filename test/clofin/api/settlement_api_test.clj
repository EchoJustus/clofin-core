(ns clofin.api.settlement-api-test
  "Settlement end to end, without a socket.

  These call the fully-wrapped handler — router, middleware, authorisation,
  error translation, JSON codec — with a request map and assert on the response
  and on the database. That is the whole stack a caller meets, minus Jetty
  (ADR-0010).

  The database is real, because every claim this increment makes is a claim
  about what is persisted, what is refused, and what commits together.

  Acceptance criteria from docs/briefs/004-TASK-settlement-simulation.md are
  named in the tests that cover them. **AC-5 and AC-7** — no double settlement —
  are the two that must not be compromised; their repository halves are in
  `clofin.settlement.repository-test`, asserted with raw SQL against the schema
  rather than through this stack."
  (:require [clofin.audit.repository :as audit-store]
            [clofin.db.core :as db]
            [clofin.settlement.scheme :as scheme]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time LocalDate ZoneOffset]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private value-date (str (.plusDays (LocalDate/now ZoneOffset/UTC) 7)))

;; ---------------------------------------------------------------------------
;; Calling the API
;; ---------------------------------------------------------------------------

(defn- handler [] (system/handler {:config {:environment :test} :pool tdb/*pool*}))

(defn- call
  [method uri & {:keys [body query actor idempotency-key]}]
  (let [[path inline-query] (str/split uri #"\?" 2)
        response ((handler)
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

(defn- ok!
  [uri & {:as opts}]
  (let [{:keys [status json] :as r} (call :post uri opts)]
    (is (= 200 status) (str "expected 200 from " uri ", body was " (:body r)))
    json))

(defn- uuid [s] (java.util.UUID/fromString s))

;; ---------------------------------------------------------------------------
;; Fixtures — built through the API wherever the API can build them
;; ---------------------------------------------------------------------------

(def ^:private settlement-accounts
  "The three roles a settlement touches. An organisation without all three
  cannot be settled in that currency, and the API says so by name."
  [["1100-CLIENT-FUNDS" "asset"]
   ["1300-IN-TRANSIT" "asset"]
   ["2100-CLIENT-PAYABLE" "liability"]])

(defn- setup
  "An organisation with settlement accounts, and the four actors these tests act
  as. Every right is granted explicitly — there is no `insert-superuser!`."
  [& {:keys [accounts] :or {accounts settlement-accounts}}]
  (let [{:keys [status json]} (call :post "/organisations"
                                    :body {"legalName" "Meridian Freight Holdings Pte Ltd"
                                           "shortName" (str "meridian-" (rand-int 100000000))})
        _ (is (= 201 status))
        org (uuid (get json "id"))
        seed (fn [roles limits]
               (tdb/insert-actor! tdb/*pool* {:organisation-id org
                                              :display-name (str/join "+" (map name roles))
                                              :roles roles :limits limits}))
        controller (seed [:controller] {})
        maker      (seed [:operator] {})
        checker    (seed [:approver] {"SGD" 100000000})
        auditor    (seed [:auditor] {})]
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "SGD"
                                       :from-minor 0 :approvals-required 1})
    (doseq [[code type] accounts]
      (is (= 201 (:status (call :post "/accounts"
                                :actor controller
                                :body {"organisationId" (str org) "code" code
                                       "name" code "type" type "currency" "SGD"})))))
    {:org org :controller controller :maker maker :checker checker :auditor auditor}))

(defn- debtor-account-id
  [{:keys [org controller]}]
  (->> (get (:json (call :get "/accounts" :actor controller
                         :query (str "organisationId=" org)))
            "accounts")
       (filter #(= "1100-CLIENT-FUNDS" (get % "code")))
       first
       (#(get % "id"))))

(defn- raise!
  "An approved instruction, with the debtor account resolved properly."
  [{:keys [org maker checker] :as f} & {:keys [creditor-account amount]
                                        :or {creditor-account "SG-SYNTH-88012340"
                                             amount 125000}}]
  (let [{:keys [status json]}
        (call :post "/payment-instructions"
              :actor maker :idempotency-key (str (random-uuid))
              :body {"organisationId" (str org)
                     "debtorAccountId" (debtor-account-id f)
                     "creditorName" "Pacific Rim Logistics Pte Ltd"
                     "creditorAccount" creditor-account
                     "amount" {"currency" "SGD" "minorUnits" amount}
                     "valueDate" value-date
                     "purposeCode" "SUPP"})
        _ (is (= 201 status) (str "instruction not created: " json))
        id (get json "id")]
    (is (= 200 (:status (call :post (str "/payment-instructions/" id "/submission")
                              :actor maker :idempotency-key (str (random-uuid))
                              :body {"organisationId" (str org)}))))
    (is (= 201 (:status (call :post (str "/payment-instructions/" id "/approvals")
                              :actor checker :idempotency-key (str (random-uuid))
                              :body {"organisationId" (str org) "decision" "approved"}))))
    id))

(defn- create-batch!
  [{:keys [org controller]} ids & {:keys [scheme currency date actor]
                                   :or {scheme "SIM-RTGS" currency "SGD"}}]
  (call :post "/settlement-batches"
        :actor (or actor controller)
        :body {"organisationId" (str org) "scheme" scheme "currency" currency
               "valueDate" (or date value-date) "instructionIds" (vec ids)}))

(defn- submit-batch!
  [{:keys [org controller]} batch-id & {:keys [actor]}]
  (call :post (str "/settlement-batches/" batch-id "/submit")
        :actor (or actor controller) :body {"organisationId" (str org)}))

(defn- respond!
  [{:keys [org controller]} batch-id body & {:keys [actor]}]
  (call :post (str "/settlement-batches/" batch-id "/scheme-responses")
        :actor (or actor controller)
        :body (merge {"organisationId" (str org)} body)))

(defn- status-of [{:keys [org maker]} instruction-id]
  (get (:json (call :get (str "/payment-instructions/" instruction-id)
                    :actor maker :query (str "organisationId=" org)))
       "status"))

(defn- actions-for [org subject-id]
  (mapv :action (audit-store/events-for-subject tdb/*pool* org subject-id)))

(defn- audit-count [] (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event"])))
(defn- entry-count [] (:count (db/query-one tdb/*pool* ["select count(*) as count from journal_entry"])))

(defn- unbalanced-entries
  "Every journal entry whose lines do not net to zero per currency.

  The ledger's own invariant, asserted from outside the ledger: if settlement
  postings could unbalance an entry the deferred trigger would have refused the
  commit, but asserting it here proves the *whole flow* left the journal sound
  rather than that one insert did."
  []
  (db/query tdb/*pool*
            ["select e.id
                from journal_entry e
                join journal_line l on l.entry_id = e.id
               group by e.id, l.currency
              having sum(case when l.direction = 'debit' then l.amount_minor else 0 end)
                  <> sum(case when l.direction = 'credit' then l.amount_minor else 0 end)"]))

;; ---------------------------------------------------------------------------
;; AC-1 / AC-2 — construction
;; ---------------------------------------------------------------------------

(deftest ac-1-a-batch-contains-exactly-the-approved-instructions-named
  (let [f (setup)
        a (raise! f) b (raise! f)
        {:keys [status json]} (create-batch! f [a b])]
    (is (= 201 status))
    (is (= "open" (get json "status")))
    (is (= "SIM-RTGS" (get json "scheme")))
    (is (true? (get json "simulated")) "the payload says it is a simulation, not only the prose")
    (is (= #{a b} (set (map #(get % "instructionId") (get json "items")))))
    (is (= 2 (get json "itemCount")))
    (is (every? nil? (map #(get % "outcome") (get json "items")))
        "membership is not an outcome")))

(deftest ac-1-an-instruction-that-is-not-approved-is-refused-by-name
  (let [f (setup)
        approved (raise! f)
        ;; A draft: raised but never submitted or approved.
        draft (get (:json (call :post "/payment-instructions"
                                :actor (:maker f) :idempotency-key (str (random-uuid))
                                :body {"organisationId" (str (:org f))
                                       "debtorAccountId" (debtor-account-id f)
                                       "creditorName" "Pacific Rim Logistics Pte Ltd"
                                       "creditorAccount" "SG-SYNTH-88012340"
                                       "amount" {"currency" "SGD" "minorUnits" 100}
                                       "valueDate" value-date
                                       "purposeCode" "SUPP"}))
                   "id")
        {:keys [status json]} (create-batch! f [approved draft])]
    (is (= 422 status))
    (is (= [draft] (mapv #(get % "instruction-id") (get-in json ["errors" "refused"]))))
    (is (= "not-approved" (get-in json ["errors" "refused" 0 "reason"])))
    (is (str/includes? (get-in json ["errors" "refused" 0 "detail"]) "approved"))
    (testing "and nothing was created on the way to being refused"
      (is (zero? (:count (db/query-one tdb/*pool*
                                       ["select count(*) as count from settlement_batch"])))))))

(deftest ac-1-a-released-instruction-cannot-be-batched-again
  (let [f (setup)
        a (raise! f)
        batch (get (:json (create-batch! f [a])) "id")]
    (is (= 200 (:status (submit-batch! f batch))))
    (is (= "released" (status-of f a)))
    (let [b (raise! f)
          {:keys [status json]} (create-batch! f [a b])]
      (is (= 422 status)
          "the eligibility check reaches it first and answers with a reason an operator
           can act on; the live-membership index behind it is asserted with raw SQL in
           clofin.settlement.repository-test")
      (is (= "not-approved" (get-in json ["errors" "refused" 0 "reason"])))
      (is (= [a] (mapv #(get % "instruction-id") (get-in json ["errors" "refused"])))))))

(deftest an-instruction-in-an-open-batch-cannot-be-put-in-a-second-one
  (testing "still approved, so eligibility passes — this is the path where the schema's
            live-membership index is what refuses, translated into a named 409"
    (let [f (setup)
          a (raise! f)]
      (is (= 201 (:status (create-batch! f [a]))))
      (let [{:keys [status json]} (create-batch! f [a] :scheme "SIM-ACH")]
        (is (= 409 status))
        (is (str/includes? (str (get json "detail")) "already in a settlement batch"))))))

(deftest ac-2-a-batch-is-one-scheme-one-currency-one-value-date
  (let [f (setup)
        a (raise! f)]
    (testing "a value date that differs from the batch's"
      (let [{:keys [status json]} (create-batch! f [a] :date (str (LocalDate/parse value-date)
                                                                 ))]
        (is (= 201 status) "the matching date is fine")
        (is (some? json))))
    (let [b (raise! f)
          {:keys [status json]} (create-batch! f [b] :date "2027-01-01")]
      (is (= 422 status))
      (is (= "value-date-mismatch" (get-in json ["errors" "refused" 0 "reason"]))))
    (testing "a currency that differs"
      (let [c (raise! f)
            {:keys [status json]} (create-batch! f [c] :currency "USD")]
        (is (= 422 status))
        (is (= "currency-mismatch" (get-in json ["errors" "refused" 0 "reason"])))))
    (testing "and an unknown — or real — scheme is refused outright"
      (doseq [s ["SWIFT" "SEPA" "sim-rtgs"]]
        (is (= 400 (:status (create-batch! f [(raise! f)] :scheme s)))
            (str s " must be refused: CloFin settles against simulated schemes only"))))))

;; ---------------------------------------------------------------------------
;; AC-3 — submission releases every member, in one transaction
;; ---------------------------------------------------------------------------

(deftest ac-3-submission-releases-every-member-with-one-audit-event-each
  (let [f (setup)
        a (raise! f) b (raise! f)
        batch (get (:json (create-batch! f [a b])) "id")
        before (audit-count)
        {:keys [status json]} (submit-batch! f batch)]
    (is (= 200 status))
    (is (= "submitted" (get json "status")))

    (testing "every member is released"
      (is (= "released" (status-of f a)))
      (is (= "released" (status-of f b))))

    (testing "one payment.released per instruction, and one settlement-batch.submitted"
      (is (= ["payment.created" "payment.submitted" "payment.approved" "payment.released"]
             (actions-for (:org f) (uuid a))))
      (is (= ["settlement-batch.created" "settlement-batch.submitted"]
             (actions-for (:org f) (uuid batch)))))

    (testing "and a release posts — value sits in settlement-in-transit mid-flight (ADR-0018)"
      (is (= 2 (entry-count)) "one release entry per instruction")
      (is (empty? (unbalanced-entries))))

    (testing "the batch's ack is recorded like any other response"
      (is (= ["ack"] (mapv #(get % "kind") (get json "schemeResponses")))))

    (is (= (+ before 3) (audit-count))
        "exactly two payment.released and one settlement-batch.submitted — nothing else")))

(deftest ac-3-a-batch-cannot-be-submitted-twice
  (let [f (setup)
        a (raise! f)
        batch (get (:json (create-batch! f [a])) "id")]
    (is (= 200 (:status (submit-batch! f batch))))
    (let [{:keys [status]} (submit-batch! f batch)]
      (is (= 409 status) "the second submission finds the batch `submitted` and is refused"))
    (is (= 1 (entry-count)) "and posts nothing a second time")))

(deftest submission-refuses-an-organisation-with-no-settlement-accounts
  (testing "named by code, so an operator opens two accounts rather than reading a stack trace"
    (let [f (setup :accounts [["1100-CLIENT-FUNDS" "asset"]])
          a (raise! f)
          batch (get (:json (create-batch! f [a])) "id")
          {:keys [status json]} (submit-batch! f batch)]
      (is (= 422 status))
      (is (= #{"1300-IN-TRANSIT" "2100-CLIENT-PAYABLE"}
             (set (map #(get % "code") (get-in json ["errors" "missing"])))))
      (is (= "approved" (status-of f a)) "and nothing moved"))))

;; ---------------------------------------------------------------------------
;; AC-4 / AC-8 — outcomes, exceptions, and the ledger staying sound
;; ---------------------------------------------------------------------------

(defn- settle! [f batch instruction ref]
  (respond! f batch {"kind" "settled" "instructionId" instruction "reference" ref}))

(defn- return! [f batch instruction ref]
  (respond! f batch {"kind" "returned" "instructionId" instruction "reference" ref
                     "reason" "SIM-RETURN: simulated scheme return"}))

(deftest ac-4-a-partly-settled-batch-derives-its-status-and-leaves-the-ledger-sound
  (let [f (setup)
        a (raise! f) b (raise! f)
        batch (get (:json (create-batch! f [a b])) "id")]
    (submit-batch! f batch)
    (settle! f batch a "SIM-STL-A")
    (let [{:keys [status json]} (return! f batch b "SIM-RTN-B")]
      (is (= 200 status))
      (is (= "partially-settled" (get json "status")))
      (testing "AC-8: the returned item is an exception case, with its reason"
        (is (= [b] (mapv #(get % "instructionId") (get json "exceptions"))))
        (is (str/includes? (get-in json ["exceptions" 0 "outcomeReason"]) "SIM-RETURN"))))
    (is (= "settled" (status-of f a)))
    (is (= "returned" (status-of f b)))
    (testing "finality posted for both, and every entry balances"
      (is (= 4 (entry-count)) "two releases, one settlement, one return")
      (is (empty? (unbalanced-entries))))))

(deftest ac-8-a-returned-response-must-carry-a-reason
  (let [f (setup)
        a (raise! f)
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (let [{:keys [status]} (respond! f batch {"kind" "returned" "instructionId" a
                                              "reference" "SIM-RTN-1"})]
      (is (contains? #{400 422 500} status)
          "the schema refuses a returned item with no reason; the API must not smuggle one past it")
      (is (= "released" (status-of f a)) "and the instruction did not move"))))

(deftest a-fully-settled-batch-derives-to-settled-and-completes-once
  (let [f (setup)
        a (raise! f) b (raise! f)
        batch (get (:json (create-batch! f [a b])) "id")]
    (submit-batch! f batch)
    (settle! f batch a "SIM-STL-A")
    (is (= "submitted" (get (:json (call :get (str "/settlement-batches/" batch)
                                         :actor (:controller f)
                                         :query (str "organisationId=" (:org f))))
                            "status"))
        "one of two resolved completes nothing")
    (is (= ["settlement-batch.created" "settlement-batch.submitted"]
           (actions-for (:org f) (uuid batch)))
        "L-7: settlement-batch.completed is not emitted while an item is unresolved")

    (let [{:keys [json]} (settle! f batch b "SIM-STL-B")]
      (is (= "settled" (get json "status"))))
    (is (= ["settlement-batch.created" "settlement-batch.submitted" "settlement-batch.completed"]
           (actions-for (:org f) (uuid batch)))
        "and exactly once, when the last item resolved")))

(deftest a-batch-where-nothing-settled-derives-to-failed
  (let [f (setup)
        a (raise! f) b (raise! f)
        batch (get (:json (create-batch! f [a b])) "id")]
    (submit-batch! f batch)
    (return! f batch a "SIM-RTN-A")
    (is (= "failed" (get (:json (return! f batch b "SIM-RTN-B")) "status")))))

;; ---------------------------------------------------------------------------
;; AC-5 — a duplicate response does no work. **Must not be compromised.**
;; ---------------------------------------------------------------------------

(deftest ac-5-an-identical-scheme-response-delivered-twice-does-no-work
  (let [f (setup)
        a (raise! f)
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (let [first-answer (:json (settle! f batch a "SIM-STL-1"))
          entries-after-first (entry-count)
          audits-after-first (audit-count)
          {:keys [status json]} (settle! f batch a "SIM-STL-1")]
      (is (= 200 status) "a scheme answering twice is normal, not an error")
      (is (true? (get json "replayed")))
      (is (= (get first-answer "status") (get json "status")) "the same answer")

      (testing "no second posting"
        (is (= entries-after-first (entry-count))))
      (testing "no second audit event"
        (is (= audits-after-first (audit-count)))
        (is (= 1 (count (filter #(= "payment.settled" %) (actions-for (:org f) (uuid a)))))))
      (testing "and the duplicate is not stored twice — the first row is the evidence"
        (is (= 2 (count (get json "schemeResponses"))) "the ack, and one settled response")))))

(deftest ac-5-an-out-of-order-response-is-a-conflict-not-a-silent-overwrite
  (let [f (setup)
        a (raise! f)
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (settle! f batch a "SIM-STL-1")
    (let [{:keys [status]} (return! f batch a "SIM-RTN-LATE")]
      (is (= 409 status)
          "a genuinely new response for an item that already has an outcome is out of order"))
    (is (= "settled" (status-of f a)) "and the settled outcome stands")
    (is (= 2 (entry-count)) "release and settlement — no third entry")))

;; ---------------------------------------------------------------------------
;; AC-6 — timeouts mean unknown
;; ---------------------------------------------------------------------------

(deftest ac-6-the-sweep-marks-unanswered-items-timed-out-and-leaves-them-unknown
  (let [f (setup)
        a (raise! f :creditor-account "SG-SYNTH-88012349")   ; the scheme never answers
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (testing "before the horizon, nothing is swept and nothing is recorded"
      (let [{:keys [json]} (ok! (str "/settlement-batches/" batch "/timeout-sweep")
                                :actor (:controller f)
                                :body {"organisationId" (str (:org f)) "timeoutSeconds" 3600})]
        (is (empty? (get json "timedOut"))))
      (is (not (some #{"settlement-batch.timeout-swept"} (actions-for (:org f) (uuid batch))))
          "a sweep that swept nothing is not a state change and must leave no event"))

    (let [json (ok! (str "/settlement-batches/" batch "/timeout-sweep")
                    :actor (:controller f)
                    :body {"organisationId" (str (:org f)) "timeoutSeconds" 0})]
      (is (= [a] (get json "timedOut")))
      (is (= "timed-out" (get-in json ["items" 0 "outcome"]))))

    (testing "the instruction stays released: CloFin does not know what happened to it"
      (is (= "released" (status-of f a))
          "driving :fail here would claim the payment did not happen, which nobody can support"))
    (testing "and no payment-level event was written, because no payment changed state (L-7)"
      (is (= ["payment.created" "payment.submitted" "payment.approved" "payment.released"]
             (actions-for (:org f) (uuid a)))))
    (testing "the batch records the sweep once"
      (is (= 1 (count (filter #{"settlement-batch.timeout-swept"}
                              (actions-for (:org f) (uuid batch)))))))))

(deftest ac-6-a-late-timeout-resolution-resolves-exactly-once
  (let [f (setup)
        a (raise! f :creditor-account "SG-SYNTH-88012349")
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (ok! (str "/settlement-batches/" batch "/timeout-sweep")
         :actor (:controller f) :body {"organisationId" (str (:org f)) "timeoutSeconds" 0})

    (let [{:keys [status json]} (respond! f batch {"kind" "timeout-resolution"
                                                   "instructionId" a
                                                   "reference" "SIM-TMO-1"
                                                   "outcome" "settled"})]
      (is (= 200 status))
      (is (= "settled" (get-in json ["items" 0 "outcome"])))
      (is (= "settled" (get json "status"))))
    (is (= "settled" (status-of f a)))
    (is (= 2 (entry-count)) "the finality posting happens on resolution, not at the sweep")

    (testing "a second resolution attempt is refused"
      (let [{:keys [status]} (respond! f batch {"kind" "timeout-resolution" "instructionId" a
                                                "reference" "SIM-TMO-2" "outcome" "returned"
                                                "reason" "later still"})]
        (is (= 409 status)))
      (is (= "settled" (status-of f a)))
      (is (= 2 (entry-count)))
      (is (= 1 (count (filter #{"payment.settled"} (actions-for (:org f) (uuid a)))))))))

(deftest ac-6-a-timeout-resolution-must-name-the-outcome-it-resolves-to
  (let [f (setup)
        a (raise! f :creditor-account "SG-SYNTH-88012349")
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (ok! (str "/settlement-batches/" batch "/timeout-sweep")
         :actor (:controller f) :body {"organisationId" (str (:org f)) "timeoutSeconds" 0})
    (is (= 400 (:status (respond! f batch {"kind" "timeout-resolution" "instructionId" a
                                           "reference" "SIM-TMO-X"}))))))

(deftest ac-7-a-timed-out-instruction-cannot-be-re-batched-through-the-api
  (testing "the schema half is asserted with raw SQL in clofin.settlement.repository-test"
    (let [f (setup)
          a (raise! f :creditor-account "SG-SYNTH-88012349")
          batch (get (:json (create-batch! f [a])) "id")]
      (submit-batch! f batch)
      (ok! (str "/settlement-batches/" batch "/timeout-sweep")
           :actor (:controller f) :body {"organisationId" (str (:org f)) "timeoutSeconds" 0})
      (let [{:keys [status json]} (create-batch! f [a])]
        (is (= 422 status)
            "an item whose outcome is unknown must never be settled a second time — the
             instruction is still `released`, so eligibility refuses it first")
        (is (= "not-approved" (get-in json ["errors" "refused" 0 "reason"]))))
      (testing "and the schema refuses it too, whoever is writing"
        (is (thrown-with-msg?
             Exception #"settlement_item_live_key"
             (db/execute! tdb/*pool*
                          ["insert into settlement_batch_item (batch_id, instruction_id)
                            values (?, ?)"
                           (random-uuid) (uuid a)])))))))

;; ---------------------------------------------------------------------------
;; AC-9 — the I9 pair, extended to settlement
;; ---------------------------------------------------------------------------

(deftest ac-9-a-rolled-back-outcome-leaves-no-posting-no-outcome-and-no-audit-event
  (let [f (setup)
        a (raise! f)
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (let [entries (entry-count)
          audits  (audit-count)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (require 'clofin.settlement.service)
                ((resolve 'clofin.settlement.service/record-scheme-response!)
                 tx {:organisation-id (:org f)
                     :batch-id        (uuid batch)
                     :instruction-id  (uuid a)
                     :kind            "settled"
                     :reference       "SIM-STL-ROLLBACK"
                     :actor           {:id (:controller f)}
                     :correlation-id  "corr-rollback"
                     :entry-id        (random-uuid)
                     :occurred-at     (java.time.Instant/now)})
                ;; Whatever goes wrong after the posting goes wrong. The
                ;; transaction is the unit, so all of it goes.
                (throw (ex-info "deliberate rollback" {})))
              nil (catch Exception e e))]
      (is (some? t))
      (is (= entries (entry-count)) "no finality posting survives")
      (is (= audits (audit-count)) "no audit event survives")
      (is (= "released" (status-of f a)) "and the instruction did not move")
      (is (nil? (get-in (:json (call :get (str "/settlement-batches/" batch)
                                     :actor (:controller f)
                                     :query (str "organisationId=" (:org f))))
                        ["items" 0 "outcome"]))
          "nor did the item"))))

;; ---------------------------------------------------------------------------
;; AC-10 — authorisation
;; ---------------------------------------------------------------------------

(deftest ac-10-settlement-requires-the-settlement-permission
  (let [f (setup)
        a (raise! f)]
    (testing "an operator, an approver and an auditor are all refused, by name"
      (doseq [actor [(:maker f) (:checker f) (:auditor f)]]
        (let [{:keys [status json]} (create-batch! f [a] :actor actor)]
          (is (= 403 status))
          (is (str/includes? (str (get-in json ["errors" "permission"])) "settlement/execute")))))
    (testing "an unauthenticated caller is 401"
      (is (= 401 (:status (call :post "/settlement-batches"
                                :body {"organisationId" (str (:org f)) "scheme" "SIM-RTGS"
                                       "currency" "SGD" "valueDate" value-date
                                       "instructionIds" [a]})))))
    (testing "the controller succeeds"
      (is (= 201 (:status (create-batch! f [a])))))))

(deftest ac-10-every-settlement-mutation-is-gated
  (let [f (setup)
        a (raise! f)
        batch (get (:json (create-batch! f [a])) "id")]
    (doseq [[label response]
            [["submit" (submit-batch! f batch :actor (:maker f))]
             ["scheme-response" (respond! f batch {"kind" "ack" "reference" "X"}
                                          :actor (:maker f))]
             ["timeout-sweep" (call :post (str "/settlement-batches/" batch "/timeout-sweep")
                                    :actor (:maker f)
                                    :body {"organisationId" (str (:org f))})]]]
      (is (= 403 (:status response)) (str label " must require settlement/execute")))))

(deftest reading-a-batch-needs-only-payment-read
  (testing "an auditor who may read the payments may read how they settled"
    (let [f (setup)
          a (raise! f)
          batch (get (:json (create-batch! f [a])) "id")]
      (is (= 200 (:status (call :get (str "/settlement-batches/" batch)
                                :actor (:auditor f)
                                :query (str "organisationId=" (:org f))))))
      (is (= 200 (:status (call :get "/settlement-batches"
                                :actor (:auditor f)
                                :query (str "organisationId=" (:org f)))))))))

;; ---------------------------------------------------------------------------
;; AC-11 — the evidence pack
;; ---------------------------------------------------------------------------

(deftest ac-11-an-instructions-evidence-pack-shows-release-and-outcome-in-order
  (let [f (setup)
        a (raise! f)
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (settle! f batch a "SIM-STL-1")
    (let [{:keys [status json]} (call :get (str "/audit/evidence/" a)
                                      :actor (:auditor f)
                                      :query (str "organisationId=" (:org f)))]
      (is (= 200 status))
      (is (= "payment-instruction" (get json "subjectType")))
      (let [actions (mapv #(get % "action") (get json "events"))
            at (fn [action] (.indexOf ^java.util.List actions action))]
        (testing "every state change is there, exactly once"
          (is (= {"payment.created" 1 "payment.submitted" 1 "approval.recorded" 1
                  "payment.approved" 1 "payment.released" 1 "payment.settled" 1}
                 (frequencies actions))))
        (testing "and in causal order"
          ;; Asserted pairwise rather than as one ordered vector, and that is not
          ;; laziness. `approval.recorded` and `payment.approved` are written in
          ;; the SAME transaction, so they share `occurred_at` to the microsecond
          ;; and are ordered by `id`, which is random —
          ;; `clofin.audit.repository/ordered` says so explicitly. Their relative
          ;; order is stable for a given set of rows but is not causal, and
          ;; asserting one would be asserting the outcome of a UUID comparison.
          ;; Every pair below crosses a transaction boundary, so every one of
          ;; them is a real fact about time.
          (doseq [[before after] [["payment.created" "payment.submitted"]
                                  ["payment.submitted" "approval.recorded"]
                                  ["payment.submitted" "payment.approved"]
                                  ["payment.approved" "payment.released"]
                                  ["approval.recorded" "payment.released"]
                                  ["payment.released" "payment.settled"]]]
            (is (< (at before) (at after))
                (str before " must precede " after)))))
      (testing "each carrying the actor who caused it"
        (is (every? #(get % "actorId") (get json "events")))))

    (testing "and the batch has an evidence pack of its own"
      (let [{:keys [status json]} (call :get (str "/audit/evidence/" batch)
                                        :actor (:auditor f)
                                        :query (str "organisationId=" (:org f)))]
        (is (= 200 status))
        (is (= "settlement-batch" (get json "subjectType"))
            "the subject type the contract now declares — it did not before this increment")
        (is (= ["settlement-batch.created" "settlement-batch.submitted"
                "settlement-batch.completed"]
               (mapv #(get % "action") (get json "events"))))))))

;; ---------------------------------------------------------------------------
;; AC-4 — a property over generated outcome mixes
;; ---------------------------------------------------------------------------

(defspec ac-4-the-ledger-stays-balanced-across-every-outcome-mix 12
  (prop/for-all [outcomes (gen/vector (gen/elements [:settled :returned]) 1 4)]
    (tdb/clean-business-data! tdb/*pool*)
    (let [f (setup)
          ids (mapv (fn [_] (raise! f)) outcomes)
          batch (get (:json (create-batch! f ids)) "id")]
      (submit-batch! f batch)
      (doseq [[id outcome i] (map vector ids outcomes (range))]
        (if (= :settled outcome)
          (settle! f batch id (str "SIM-STL-" i))
          (return! f batch id (str "SIM-RTN-" i))))
      (let [json (:json (call :get (str "/settlement-batches/" batch)
                              :actor (:controller f)
                              :query (str "organisationId=" (:org f))))
            settled  (count (filter #{:settled} outcomes))
            expected (cond (= settled (count outcomes)) "settled"
                           (pos? settled)               "partially-settled"
                           :else                        "failed")]
        (and
         ;; The ledger is sound whatever the mix — the invariant this whole
         ;; product rests on (I1, C-04).
         (empty? (unbalanced-entries))
         ;; One release entry per instruction, plus one finality entry each.
         (= (* 2 (count ids)) (entry-count))
         ;; The batch status is derived, not asserted by the caller.
         (= expected (get json "status"))
         ;; Every item resolved, and every returned one carries its reason.
         (every? some? (map #(get % "outcome") (get json "items")))
         (every? #(seq (str (get % "outcomeReason"))) (get json "exceptions")))))))

;; ---------------------------------------------------------------------------
;; AC-12 — the contract says it is a simulation
;; ---------------------------------------------------------------------------

(deftest ac-12-the-simulation-rule-is-the-one-the-adapter-implements
  (testing "the documented rule and the code cannot drift: a reviewer predicts an outcome
            from the creditor account and the API must agree"
    (let [f (setup)
          settles (raise! f :creditor-account "SG-SYNTH-88012343")
          returns (raise! f :creditor-account "SG-SYNTH-88012348")
          silent  (raise! f :creditor-account "SG-SYNTH-88012349")
          batch (get (:json (create-batch! f [settles returns silent])) "id")]
      (is (= :settled (scheme/outcome-for {:creditor-account "SG-SYNTH-88012343"})))
      (is (= :returned (scheme/outcome-for {:creditor-account "SG-SYNTH-88012348"})))
      (is (= :no-response (scheme/outcome-for {:creditor-account "SG-SYNTH-88012349"})))
      (submit-batch! f batch)
      (settle! f batch settles "SIM-STL-1")
      (return! f batch returns "SIM-RTN-1")
      (let [json (:json (call :get (str "/settlement-batches/" batch)
                              :actor (:controller f)
                              :query (str "organisationId=" (:org f))))]
        (is (= "submitted" (get json "status"))
            "the silent one keeps the batch unresolved — which is what a timeout is")
        (is (true? (get json "simulated")))))))
