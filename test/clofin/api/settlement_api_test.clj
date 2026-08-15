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
           can act on; the membership index behind it is asserted with raw SQL in
           clofin.settlement.repository-test")
      (is (= "not-approved" (get-in json ["errors" "refused" 0 "reason"])))
      (is (= [a] (mapv #(get % "instruction-id") (get-in json ["errors" "refused"])))))))

(deftest an-instruction-in-an-open-batch-cannot-be-put-in-a-second-one
  (testing "still approved, so eligibility passes — this is the path where the schema's
            membership index is what refuses, translated into a named 409"
    (let [f (setup)
          a (raise! f)]
      (is (= 201 (:status (create-batch! f [a]))))
      (let [{:keys [status json]} (create-batch! f [a] :scheme "SIM-ACH")]
        (is (= 409 status))
        (is (str/includes? (str (get json "detail")) "has already been in a settlement batch"))))))

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
  ;; A-017. This test used to accept `#{400 422 500}`, and a 500 was what it got:
  ;; `assert-shape!` did not look at `:reason`, the service wrote the item, and
  ;; `settlement_return_needs_reason` raised a raw constraint failure. Accepting
  ;; a 500 is not an assertion — it is the absence of one written in the shape of
  ;; one, and it is why the gap survived two audits. The status is now exact.
  (let [f (setup)
        a (raise! f)
        b (raise! f)
        batch (get (:json (create-batch! f [a b])) "id")]
    (submit-batch! f batch)

    (testing "a direct return with no reason is a modelled refusal, not an internal error"
      (let [{:keys [status json]} (respond! f batch {"kind" "returned" "instructionId" a
                                                     "reference" "SIM-RTN-1"})]
        (is (= 422 status)
            "the request was understood and one named field is rejected on its merits (ADR-0012)")
        (is (= "https://clofin.dev/problems/validation" (get json "type")))
        (is (= "reason" (get-in json ["errors" "field"])))
        (is (= "released" (status-of f a)) "and the instruction did not move")))

    (testing "a blank reason is the same claim as an absent one and is refused alike"
      (is (= 422 (:status (respond! f batch {"kind" "returned" "instructionId" a
                                             "reference" "SIM-RTN-2"
                                             "reason" "   "})))))

    (testing "and so is the second route to `returned` — a timeout resolution"
      (ok! (str "/settlement-batches/" batch "/timeout-sweep")
           :actor (:controller f)
           :body {"organisationId" (str (:org f)) "timeoutSeconds" 0})
      (let [{:keys [status json]} (respond! f batch {"kind" "timeout-resolution"
                                                     "instructionId" b
                                                     "outcome" "returned"
                                                     "reference" "SIM-TRS-1"})]
        (is (= 422 status))
        (is (= "reason" (get-in json ["errors" "field"])))))

    (testing "no receipt is written for a message that could not be understood"
      (is (zero? (:count (db/query-one tdb/*pool*
                                       ["select count(*) as count from scheme_response
                                          where reference like 'SIM-RTN-%'
                                             or reference = 'SIM-TRS-1'"])))
          "assert-shape! runs before anything is written — only a well-formed
           message earns a receipt (F-008 is about processing conflicts, which do)"))))

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
;; F-007 — a returned payment is terminal, and the retry is a new instruction
;; ---------------------------------------------------------------------------
;;
;; Standing lesson **L-10**: a schema path is not a product path. The audit
;; found the two disagreeing — migration 0009's index freed a returned
;; instruction while the lifecycle and batch eligibility both refused it — and
;; the ruling settled the disagreement in the lifecycle's favour. These assert
;; the settled position **from the public command**, which is what AC-7 always
;; needed and never had.

(deftest f-007-a-returned-instruction-is-refused-a-second-batch-by-name
  (testing "and the reason names the terminal state and the route that does work,
            because a refusal an operator cannot act on becomes a request to
            disable the check"
    (let [f (setup)
          a (raise! f :creditor-account "SG-SYNTH-88012348")   ; the scheme returns it
          batch (get (:json (create-batch! f [a])) "id")]
      (submit-batch! f batch)
      (return! f batch a "SIM-RTN-1")
      (is (= "returned" (status-of f a)))

      (let [{:keys [status json]} (create-batch! f [a] :scheme "SIM-ACH")]
        (is (= 422 status)
            "eligibility answers first: a returned instruction is not approved")
        (is (= "not-approved" (get-in json ["errors" "refused" 0 "reason"]))))

      (testing "and the schema refuses it too, whoever is writing — the audit's own
                raw insert, which committed before migration 0010 (F-007)"
        (is (thrown-with-msg?
             Exception #"settlement_item_instruction_key"
             (db/execute! tdb/*pool*
                          ["insert into settlement_batch_item (batch_id, instruction_id)
                            values (?, ?)"
                           (random-uuid) (uuid a)]))))

      (testing "the retry is a new instruction, and it settles normally"
        (let [retry (raise! f :creditor-account "SG-SYNTH-88012340")
              retry-batch (get (:json (create-batch! f [retry] :scheme "SIM-ACH")) "id")]
          (is (= 200 (:status (submit-batch! f retry-batch))))
          (is (= 200 (:status (settle! f retry-batch retry "SIM-STL-RETRY"))))
          (is (= "settled" (status-of f retry)))
          (is (= "returned" (status-of f a))
              "and the original stays returned — a retry does not rewrite history"))))))

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

      (testing "F-009: the replayed body reproduces the ORIGINAL answer, outcome included"
        ;; The audit observed `outcome: "settled"` on the first delivery and
        ;; `outcome: null` on its duplicate. A replay that does not carry the
        ;; answer it is replaying is not a replay; it is an acknowledgement
        ;; dressed as one.
        (is (= "settled" (get first-answer "outcome")))
        (is (= "settled" (get json "outcome")))
        (is (= (dissoc first-answer "schemeResponses" "replayed")
               (dissoc json "schemeResponses" "replayed"))
            "and the two bodies are otherwise identical. The audit's C-03 probe
             compared them with `replayed` excluded — that flag is the one
             intentional difference — and they still differed. They no longer do"))

      (testing "no second posting"
        (is (= entries-after-first (entry-count))))
      (testing "no second audit event"
        (is (= audits-after-first (audit-count)))
        (is (= 1 (count (filter #(= "payment.settled" %) (actions-for (:org f) (uuid a)))))))
      (testing "and the duplicate is not stored twice — the first row is the evidence"
        (is (= 2 (count (get json "schemeResponses"))) "the ack, and one settled response")
        (is (= #{"acknowledged" "applied"}
               (set (map #(get % "disposition") (get json "schemeResponses")))))))))

(deftest ac-5-an-out-of-order-response-is-a-conflict-not-a-silent-overwrite
  (let [f (setup)
        a (raise! f)
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (settle! f batch a "SIM-STL-1")
    (let [{:keys [status json]} (return! f batch a "SIM-RTN-LATE")]
      (is (= 409 status)
          "a genuinely new response for an item that already has an outcome is out of order")
      (is (= "item-already-resolved" (get-in json ["errors" "dispositionReason"]))))
    (is (= "settled" (status-of f a)) "and the settled outcome stands")
    (is (= 2 (entry-count)) "release and settlement — no third entry")))

;; ---------------------------------------------------------------------------
;; F-008 — the receipt survives its own refusal
;; ---------------------------------------------------------------------------
;;
;; Standing lesson **L-11**. Both tests the ruling names are here: a premature
;; timeout-resolution, and a late contradiction. Each asserts the row survives
;; and remains effect-free, and each then re-delivers the identical message to
;; prove the stored answer is *reproduced* rather than re-evaluated against
;; state that has arrived since.

(defn- receipts-for
  "Every receipt on a batch, read straight from the table rather than through
  the API — the audit's own probe, which found zero rows where the design
  claimed one."
  [batch]
  (db/query tdb/*pool*
            ["select kind, reference, disposition, disposition_reason, outcome
                from scheme_response where batch_id = ? order by received_at, id"
             (uuid batch)]))

(deftest f-008-a-premature-timeout-resolution-is-kept-and-does-no-work
  (testing "the audit's C-02 reproduction: 409 with zero stored rows, and then the
            identical reference performing work after a sweep. Both halves are now false"
    (let [f (setup)
          a (raise! f :creditor-account "SG-SYNTH-88012349")   ; the scheme never answers
          batch (get (:json (create-batch! f [a])) "id")]
      (submit-batch! f batch)
      (let [entries (entry-count)
            audits  (audit-count)
            {:keys [status json]} (respond! f batch {"kind" "timeout-resolution"
                                                     "instructionId" a
                                                     "reference" "SIM-TMO-1"
                                                     "outcome" "settled"})]
        (is (= 409 status) "the item is not timed out; there is nothing to resolve")
        (is (= "item-not-timed-out" (get-in json ["errors" "dispositionReason"])))
        (is (false? (get-in json ["errors" "replayed"])) "this is its first arrival")

        (testing "and the receipt committed anyway — the row the audit found missing"
          (let [rows (receipts-for batch)
                receipt (first (filter #(= "SIM-TMO-1" (:reference %)) rows))]
            (is (some? receipt) "a rejected response must not be erased by its own rejection")
            (is (= "refused" (:disposition receipt)))
            (is (= "item-not-timed-out" (:disposition-reason receipt)))
            (is (nil? (:outcome receipt))
                "it resolved nothing, so it claims nothing")))

        (testing "effect-free"
          (is (= entries (entry-count)))
          (is (= audits (audit-count)))
          (is (= "released" (status-of f a)))))

      (testing "after the sweep, the SAME reference reproduces its original no-work answer"
        ;; This is the half that mattered: previously the identical message was
        ;; accepted, settled the payment, wrote `payment.settled` and posted
        ;; finality — because the first arrival had left no trace to replay.
        (ok! (str "/settlement-batches/" batch "/timeout-sweep")
             :actor (:controller f) :body {"organisationId" (str (:org f)) "timeoutSeconds" 0})
        (let [entries (entry-count)
              audits  (audit-count)
              {:keys [status json]} (respond! f batch {"kind" "timeout-resolution"
                                                       "instructionId" a
                                                       "reference" "SIM-TMO-1"
                                                       "outcome" "settled"})]
          (is (= 409 status) "the stored disposition is reproduced, not re-evaluated")
          (is (= "item-not-timed-out" (get-in json ["errors" "dispositionReason"])))
          (is (true? (get-in json ["errors" "replayed"]))
              "and it is honestly reported as the same message arriving again")
          (is (= entries (entry-count)) "no finality posting")
          (is (= audits (audit-count)) "no audit event")
          (is (= "released" (status-of f a)) "and the payment did not settle")
          (is (= 1 (count (filter #(= "SIM-TMO-1" (:reference %)) (receipts-for batch))))
              "one receipt, not two — the replay key still admits exactly one row"))

        (testing "a NEW reference still resolves it, so the item is not stranded"
          (let [{:keys [status json]} (respond! f batch {"kind" "timeout-resolution"
                                                         "instructionId" a
                                                         "reference" "SIM-TMO-2"
                                                         "outcome" "settled"})]
            (is (= 200 status))
            (is (= "settled" (get json "outcome")))
            (is (= "settled" (status-of f a)))))))))

(deftest f-008-a-refused-receipt-is-visible-on-the-batch-as-evidence
  (testing "an append-only evidence table nobody can read completely is a table
            that answers an auditor's question with `probably`"
    (let [f (setup)
          a (raise! f)
          batch (get (:json (create-batch! f [a])) "id")]
      (submit-batch! f batch)
      (settle! f batch a "SIM-STL-1")
      (is (= 409 (:status (return! f batch a "SIM-RTN-LATE"))))
      (let [json (:json (call :get (str "/settlement-batches/" batch)
                              :actor (:controller f)
                              :query (str "organisationId=" (:org f))))
            responses (get json "schemeResponses")]
        (is (= 3 (count responses)) "the ack, the settlement, and the refused late return")
        (let [refused (first (filter #(= "refused" (get % "disposition")) responses))]
          (is (some? refused))
          (is (= "SIM-RTN-LATE" (get refused "reference")))
          (is (= "item-already-resolved" (get refused "dispositionReason")))
          (is (nil? (get refused "outcome"))))))))

;; ---------------------------------------------------------------------------
;; F-009 — replay identity covers every effect-bearing field
;; ---------------------------------------------------------------------------

(deftest f-009-a-contradiction-under-one-reference-is-a-conflict-not-a-replay
  (testing "the audit's C-03 reproduction: timeout-resolution(settled) then the same
            reference carrying timeout-resolution(returned) was answered
            `200 replayed=true` — a claim that CloFin had seen a request nobody sent"
    (let [f (setup)
          a (raise! f :creditor-account "SG-SYNTH-88012349")
          batch (get (:json (create-batch! f [a])) "id")]
      (submit-batch! f batch)
      (ok! (str "/settlement-batches/" batch "/timeout-sweep")
           :actor (:controller f) :body {"organisationId" (str (:org f)) "timeoutSeconds" 0})
      (is (= 200 (:status (respond! f batch {"kind" "timeout-resolution" "instructionId" a
                                             "reference" "SIM-TMO-1" "outcome" "settled"}))))
      (is (= "settled" (status-of f a)))

      (let [entries (entry-count)
            audits  (audit-count)
            {:keys [status json]} (respond! f batch {"kind" "timeout-resolution"
                                                     "instructionId" a
                                                     "reference" "SIM-TMO-1"
                                                     "outcome" "returned"
                                                     "reason" "changed my mind"})]
        (is (= 409 status) "outcome and reason are inside the identity now")
        (is (= "replay-key-conflict" (get-in json ["errors" "dispositionReason"])))
        (is (false? (get-in json ["errors" "replayed"]))
            "and it is emphatically NOT described as an exact replay")
        (is (= entries (entry-count)) "effect-free")
        (is (= audits (audit-count)))
        (is (= "settled" (status-of f a)) "the payment keeps the answer it was given"))

      (testing "the first receipt stands alone, carrying what was actually claimed"
        (let [rows (filter #(= "SIM-TMO-1" (:reference %)) (receipts-for batch))]
          (is (= 1 (count rows)))
          (is (= "settled" (:outcome (first rows))))
          (is (= "applied" (:disposition (first rows)))))))))

(deftest f-009-a-return-reason-is-part-of-the-message
  (testing "the reason is written to the item and gates a schema constraint, so two
            returns under one reference saying different things are two messages"
    (let [f (setup)
          a (raise! f) b (raise! f)
          batch (get (:json (create-batch! f [a b])) "id")]
      (submit-batch! f batch)
      (is (= 200 (:status (return! f batch a "SIM-RTN-1"))))
      (is (= 409 (:status (respond! f batch {"kind" "returned" "instructionId" a
                                             "reference" "SIM-RTN-1"
                                             "reason" "a different reason entirely"})))
          "same reference, different reason — not a replay")
      (testing "while the identical message replays, blank and absent being one claim"
        (let [{:keys [status json]} (return! f batch a "SIM-RTN-1")]
          (is (= 200 status))
          (is (true? (get json "replayed")))
          (is (= "returned" (get json "outcome"))))))))

(deftest f-009-an-ack-replays-as-an-ack
  (testing "a resubmission produces the same deterministic ack reference; the receipt
            written by `submit-batch!` and one injected by hand are the same shape"
    (let [f (setup)
          a (raise! f)
          batch (get (:json (create-batch! f [a])) "id")
          ack-ref (str "SIM-ACK-" batch)]
      (submit-batch! f batch)
      (let [{:keys [status json]} (respond! f batch {"kind" "ack" "reference" ack-ref})]
        (is (= 200 status))
        (is (true? (get json "replayed")) "the submission already recorded this ack")
        (is (nil? (get json "outcome")) "an ack resolves nothing"))
      (is (= 1 (count (filter #(= ack-ref (:reference %)) (receipts-for batch))))))))

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
      (let [{:keys [status json]} (respond! f batch {"kind" "timeout-resolution" "instructionId" a
                                                     "reference" "SIM-TMO-2" "outcome" "returned"
                                                     "reason" "later still"})]
        (is (= 409 status))
        (is (= "item-not-timed-out" (get-in json ["errors" "dispositionReason"]))
            "the item is resolved, so there is no longer a timeout to resolve")
        (is (= "refused" (:disposition (first (filter #(= "SIM-TMO-2" (:reference %))
                                                      (receipts-for batch)))))
            "and the refused attempt is kept as evidence that it was made (F-008)"))
      (is (= "settled" (status-of f a)))
      (is (= 2 (entry-count)))
      (is (= 1 (count (filter #{"payment.settled"} (actions-for (:org f) (uuid a)))))))))

(deftest ac-4-a-late-resolution-that-moves-a-complete-batchs-status-says-so
  ;; TASK-010 AC-4, and the closing of C-05's one disclosed exception
  ;; (release-audit finding **A-004**). One item, swept: the batch is complete
  ;; and derives to `failed`. The late truth then makes it `settled` — a real
  ;; change to a real column, with a real actor behind it, that left no event
  ;; whose subject was the batch until `settlement-batch.status-restated`.
  (let [f (setup)
        a (raise! f :creditor-account "SG-SYNTH-88012349")
        batch (get (:json (create-batch! f [a])) "id")]
    (submit-batch! f batch)
    (ok! (str "/settlement-batches/" batch "/timeout-sweep")
         :actor (:controller f) :body {"organisationId" (str (:org f)) "timeoutSeconds" 0})
    (let [after-sweep (actions-for (:org f) (uuid batch))]
      (is (= 1 (count (filter #{"settlement-batch.completed"} after-sweep)))
          "the sweep is the transition INTO a complete batch")
      (is (empty? (filter #{"settlement-batch.status-restated"} after-sweep))
          "and nothing has been restated yet"))

    (let [before (audit-count)
          {:keys [status json]} (respond! f batch {"kind" "timeout-resolution"
                                                   "instructionId" a
                                                   "reference" "SIM-TMO-LATE"
                                                   "outcome" "settled"})
          actions (actions-for (:org f) (uuid batch))]
      (is (= 200 status))
      (is (= "settled" (get json "status")) "the derived status did move")
      (is (= 1 (count (filter #{"settlement-batch.status-restated"} actions)))
          "exactly one batch-subject event with the new term, in that transaction")
      (is (= 1 (count (filter #{"settlement-batch.completed"} actions)))
          "and NOT a second `completed` — that transition happened at the sweep,
           under a different actor and a different correlation id, and counting
           one as the other is F-005's mislabelling with a new name")
      (is (= (+ before 2) (audit-count))
          "two events for two facts: the payment settled, and the batch's
           outcome was restated")

      (testing "the event carries a real before and a real after"
        (let [row (db/query-one
                   tdb/*pool*
                   ["select before_digest, after_digest, actor_id from audit_event
                      where subject_id = ? and action = 'settlement-batch.status-restated'"
                    (uuid batch)])]
          (is (some? (:before-digest row)))
          (is (some? (:after-digest row)))
          (is (not= (:before-digest row) (:after-digest row))
              "identical digests would assert a change that did not happen")
          (is (some? (:actor-id row)) "and the actor that caused it"))))))

(deftest ac-4-a-late-resolution-that-moves-nothing-emits-nothing
  ;; The other half of AC-4, and the half a rule written against the response
  ;; `kind` rather than against the status would get wrong. Two items: one
  ;; settled, one swept. The batch is complete and derives to
  ;; `partially-settled`. Resolving the swept item to `returned` leaves it
  ;; `partially-settled` — the item moved, the batch did not.
  (let [f (setup)
        a (raise! f :creditor-account "SG-SYNTH-88012349")
        b (raise! f :creditor-account "SG-SYNTH-88012350")
        batch (get (:json (create-batch! f [a b])) "id")]
    (submit-batch! f batch)
    (settle! f batch a "SIM-STL-A")
    (ok! (str "/settlement-batches/" batch "/timeout-sweep")
         :actor (:controller f) :body {"organisationId" (str (:org f)) "timeoutSeconds" 0})
    (is (= "partially-settled"
           (get (:json (call :get (str "/settlement-batches/" batch)
                             :actor (:controller f)
                             :query (str "organisationId=" (:org f))))
                "status")))

    (let [before-actions (actions-for (:org f) (uuid batch))
          {:keys [status json]} (respond! f batch {"kind" "timeout-resolution"
                                                   "instructionId" b
                                                   "reference" "SIM-TMO-LATE-B"
                                                   "outcome" "returned"
                                                   "reason" "SIM-RETURN: late truth"})
          after-actions (actions-for (:org f) (uuid batch))]
      (is (= 200 status))
      (is (= "partially-settled" (get json "status"))
          "the item moved and the batch's derived status did not")
      (is (= "returned" (status-of f b)) "the payment's own transition is recorded")
      (is (= before-actions after-actions)
          "so no batch-subject event at all — an event whose before and after
           digests are identical asserts a transition that did not occur")
      (is (empty? (filter #{"settlement-batch.status-restated"} after-actions))))))

(deftest ac-4-a-refused-late-resolution-restates-nothing
  (testing "AC-8's rolled-back half for the new write: the receipt commits, the
            batch does not move, and no batch-subject event claims it did"
    (let [f (setup)
          a (raise! f :creditor-account "SG-SYNTH-88012349")
          batch (get (:json (create-batch! f [a])) "id")]
      (submit-batch! f batch)
      (settle! f batch a "SIM-STL-1")
      (let [before (actions-for (:org f) (uuid batch))
            {:keys [status json]} (respond! f batch {"kind" "timeout-resolution"
                                                     "instructionId" a
                                                     "reference" "SIM-TMO-NOPE"
                                                     "outcome" "returned"
                                                     "reason" "too late"})]
        (is (= 409 status))
        (is (= "item-not-timed-out" (get-in json ["errors" "dispositionReason"])))
        (is (= before (actions-for (:org f) (uuid batch)))
            "nothing about the batch changed, so nothing about the batch is
             recorded")))))

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
             Exception #"settlement_item_instruction_key"
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
