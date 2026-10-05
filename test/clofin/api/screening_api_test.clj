(ns clofin.api.screening-api-test
  "Screening end to end through the public handler (TASK-017; ADR-0028 D5 and
  its *Verification* table, whose identifiers `ac-17-1` … `ac-17-7` are kept
  verbatim here and in `clofin.screening.concurrency-test`).

  Core screens every submission against the accepted synthetic list; a client's
  result is evidence and transitions nothing; a hit opens a case a compliance
  actor — never the maker — dispositions. The database is real, because every
  criterion is a statement about what was stored, what was not, and in which
  transaction.

  Synthetic data only. The shipped list is loaded by the fixture, as a
  deployment loads it; its first entry is the creditor name
  `Blocked Counterparty Ltd`, and nothing else any test uses collides with it."
  (:require [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.screening.repository :as screening-repo]
            [clofin.screening.service :as service]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clofin.tools.screening-list :as screening-tool]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time LocalDate ZoneOffset]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private list-version "synthetic-2026-10-v1")
(def ^:private blocked "Blocked Counterparty Ltd")
(def ^:private ordinary "Pacific Rim Logistics Pte Ltd")
(def ^:private syn-0001 {"id" "SYN-0001"
                         "rules" [{"field" "creditor-name" "operator" "exact" "value" blocked}]})

;; ---------------------------------------------------------------------------
;; Calling the API
;; ---------------------------------------------------------------------------

(defn- call
  [method uri & {:keys [body query idempotency-key actor]}]
  (let [handler (system/handler {:config {:environment :test} :pool tdb/*pool*})
        [path inline-query] (str/split uri #"\?" 2)
        response (handler
                  (cond-> {:request-method method :uri path :headers {}}
                    (or query inline-query) (assoc :query-string (or query inline-query))
                    actor (assoc-in [:headers "x-actor-id"] (str actor))
                    idempotency-key (assoc-in [:headers "idempotency-key"] idempotency-key)
                    body (-> (assoc-in [:headers "content-type"] "application/json")
                             (assoc :body (ByteArrayInputStream.
                                           (.getBytes (json/write-str body) StandardCharsets/UTF_8))))))]
    (assoc response :json (when-not (str/blank? (:body response))
                            (json/read-str (:body response))))))

(defn- key! [] (str (random-uuid)))

;; ---------------------------------------------------------------------------
;; Fixtures, built through the API itself
;; ---------------------------------------------------------------------------

(defn- setup
  "An organisation with an account, and the actors screening needs: the maker,
  a screening client, a compliance actor, a checker and an auditor — each with
  exactly the role it acts in (C-08: no superuser, here or anywhere)."
  []
  (let [org (get (:json (call :post "/organisations"
                              :body {"legalName" "Meridian Freight Holdings Pte Ltd"
                                     "shortName" (str "meridian-" (random-uuid))}))
                 "id")
        org-id (java.util.UUID/fromString org)
        seed (fn [roles & {:keys [limits] :or {limits {}}}]
               (tdb/insert-actor! tdb/*pool* {:organisation-id org-id
                                              :display-name (str/join "+" (map name roles))
                                              :roles roles :limits limits}))
        controller (seed [:controller])
        account (get (:json (call :post "/accounts" :actor controller
                                  :body {"organisationId" org "code" "1100-CLIENT-FUNDS"
                                         "name" "Client funds — pooled" "type" "asset"
                                         "currency" "SGD"}))
                     "id")]
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org-id :currency "SGD"
                                       :from-minor 0 :approvals-required 1})
    {:org org :org-id org-id :account account :controller controller
     :maker (seed [:operator])
     :screener (seed [:screening-service])
     :compliance (seed [:compliance])
     :checker (seed [:approver] :limits {"SGD" 100000000})
     :auditor (seed [:auditor])}))

(defn- raise!
  "A draft, raised by the fixture's maker (or `as`)."
  [{:keys [org account maker]} & {:keys [creditor-name creditor-account creditor-country as]
                                  :or {creditor-name ordinary creditor-account "SG-SYNTH-88012345"}}]
  (let [{:keys [status json]}
        (call :post "/payment-instructions" :actor (or as maker) :idempotency-key (key!)
              :body (cond-> {"organisationId" org "debtorAccountId" account
                             "creditorName" creditor-name "creditorAccount" creditor-account
                             "amount" {"currency" "SGD" "minorUnits" 125000}
                             "valueDate" (str (.plusDays (LocalDate/now ZoneOffset/UTC) 7))
                             "purposeCode" "SUPP"}
                      creditor-country (assoc "creditorCountry" creditor-country)))]
    (is (= 201 status) (pr-str json))
    json))

(defn- submit! [f pi & {:keys [as key]}]
  (call :post (str "/payment-instructions/" (get pi "id") "/submission")
        :actor (or as (:maker f)) :idempotency-key (or key (key!))
        :body {"organisationId" (:org f)}))

(defn- read-instruction [f pi]
  (:json (call :get (str "/payment-instructions/" (get pi "id")) :actor (:maker f))))

(defn- record! [f pi body & {:keys [as key]}]
  (call :post (str "/payment-instructions/" (get pi "id") "/screening-results")
        :actor (or as (:screener f)) :idempotency-key (or key (key!))
        :body (merge {"organisationId" (:org f)
                      "listVersion" list-version
                      "instructionDigest" (get (read-instruction f pi) "screeningDigest")}
                     body)))

(defn- results [f pi]
  (:json (call :get "/screening-results" :actor (:compliance f)
               :query (str "instructionId=" (get pi "id")))))

(defn- disposition! [f case-id disposition & {:keys [as key rationale]}]
  (call :post (str "/screening-cases/" case-id "/disposition")
        :actor (or as (:compliance f)) :idempotency-key (or key (key!))
        :body {"organisationId" (:org f) "disposition" disposition
               "rationale" (or rationale "Synthetic name collision; counterparty verified as not the listed entry")}))

(defn- actions-for-instruction
  "The actions the trail holds about an instruction and the screening rows
  about it."
  [pi]
  (let [id (java.util.UUID/fromString (get pi "id"))]
    (mapv :action
          (db/query tdb/*pool*
                    ["select action from audit_event
                       where subject_id = ?
                          or subject_id in (select id from screening_result where instruction_id = ?)
                          or subject_id in (select id from screening_case where instruction_id = ?)
                       order by occurred_at, id" id id id]))))

(defn- count-of [table pi]
  (:count (db/query-one tdb/*pool* [(str "select count(*) as count from " table
                                         " where instruction_id = ?")
                                    (java.util.UUID/fromString (get pi "id"))])))

;; ---------------------------------------------------------------------------
;; AC-17-1 / AC-17-2 — core screens at submit
;; ---------------------------------------------------------------------------

(deftest ac-17-1-submit-answers-409-screening-hit-and-emits-no-payment-submitted-event
  (let [f (setup)
        pi (raise! f :creditor-name blocked)
        {:keys [status json headers]} (submit! f pi)]
    (is (= 409 status) (pr-str json))
    (is (= "application/problem+json" (get headers "content-type")))
    (is (= "screening-hit" (get-in json ["errors" "reason"])))
    (is (some? (get-in json ["errors" "caseId"])))
    (is (= list-version (get-in json ["errors" "listVersion"])))
    (is (= "draft" (get (read-instruction f pi) "status")) "the instruction did not move")
    (let [actions (actions-for-instruction pi)]
      (is (some #{"screening-result.recorded"} actions) (pr-str actions))
      (is (some #{"screening-case.opened"} actions) (pr-str actions))
      (is (not-any? #{"payment.submitted"} actions) (pr-str actions))
      (is (not-any? #(str/starts-with? % "payment.") (remove #{"payment.created"} actions))
          "no payment.* event beyond the creation"))
    (testing "core's own result is stored: a hit on SYN-0001"
      (let [[r] (get (results f pi) "screeningResults")]
        (is (= {"origin" "core" "outcome" "hit" "matchedEntries" ["SYN-0001"]
                "disposition" "accepted" "agrees" true}
               (select-keys r ["origin" "outcome" "matchedEntries" "disposition" "agrees"]))))))
  (testing "negative control: the same instruction with a name the list does not hold"
    (let [f (setup)
          pi (raise! f)
          {:keys [status json]} (submit! f pi)]
      (is (= 200 status) (pr-str json))
      (is (= "pending-approval" (get json "status")))
      (let [rs (get (results f pi) "screeningResults")]
        (is (= 1 (count rs)) "one core result")
        (is (= {"origin" "core" "outcome" "clear" "matchedEntries" []}
               (select-keys (first rs) ["origin" "outcome" "matchedEntries"]))))
      (is (zero? (count-of "screening_case" pi))))))

(deftest ac-17-2-a-hit-opens-a-case-in-the-same-transaction
  (let [f (setup)
        pi (raise! f :creditor-name blocked)
        first-409 (:json (submit! f pi))
        case-id (get-in first-409 ["errors" "caseId"])]
    (testing "the result and the case were written by one request, in one transaction:
              their events share the request's correlation id"
      (let [rows (db/query tdb/*pool* ["select action, correlation_id from audit_event
                                         where action in ('screening-result.recorded', 'screening-case.opened')"])]
        (is (= #{"screening-result.recorded" "screening-case.opened"} (set (map :action rows))))
        (is (= 1 (count (set (map :correlation-id rows)))) (pr-str rows))
        (is (= (get first-409 "instance") (:correlation-id (first rows))))))
    (testing "a second submission while the case is open opens no second case and
              names the same one"
      (let [{:keys [status json]} (submit! f pi)]
        (is (= 409 status))
        (is (= case-id (get-in json ["errors" "caseId"])))
        (is (= 1 (count-of "screening_case" pi)))
        (is (= 2 (count-of "screening_result" pi)) "each submission is screened, and stored")))
    (testing "and a retry under the first request's key replays its 409 — the
              evidence is not recorded twice"
      (let [k (key!)
            first-answer (submit! f pi :key k)
            results-before (count-of "screening_result" pi)
            replay (submit! f pi :key k)]
        (is (= 409 (:status replay)))
        (is (= "true" (get-in replay [:headers "idempotent-replayed"])))
        (is (= (:body first-answer) (:body replay)) "byte for byte")
        (is (= results-before (count-of "screening_result" pi))))))
  (testing "negative control: break the unit of work after the result is written
            and before the case opens, in a test double — neither row survives"
    (let [f (setup)
          pi (raise! f :creditor-name blocked)]
      (with-redefs [screening-repo/open-case! (fn [& _] (throw (ex-info "injected after the result" {})))]
        (is (= 500 (:status (submit! f pi)))))
      (is (zero? (count-of "screening_result" pi)))
      (is (zero? (count-of "screening_case" pi)))
      (is (= ["payment.created"] (actions-for-instruction pi))))))

(deftest a-submission-with-no-list-accepted-is-422-and-consumes-nothing
  (let [f (setup)
        pi (raise! f)
        k (key!)]
    (screening-tool/retire! tdb/*pool* list-version)
    (let [{:keys [status json]} (submit! f pi :key k)]
      (is (= 422 status))
      (is (= "no-screening-list-accepted" (get-in json ["errors" "reason"]))))
    (is (zero? (count-of "screening_result" pi)) "nothing was screened against nothing")
    (is (= "draft" (get (read-instruction f pi) "status")))
    (testing "the key is not consumed: once a list is loaded, the same key submits"
      (screening-tool/load-list! tdb/*pool* {:version "synthetic-2026-10-v2"
                                             :entries [{:id "SYN-0001"
                                                        :rules [{:field :creditor-name :operator :exact
                                                                 :value blocked}]}]}
                                 {:source "test"})
      (let [{:keys [status json headers]} (submit! f pi :key k)]
        (is (= 200 status) (pr-str json))
        (is (nil? (get headers "idempotent-replayed")) "fresh work, not a replay")))))

;; ---------------------------------------------------------------------------
;; AC-17-3 … AC-17-6 — a client's result is evidence
;; ---------------------------------------------------------------------------

(deftest ac-17-3-an-unaccepted-list-version-is-422
  (let [f (setup)
        pi (raise! f :creditor-name blocked)]
    (screening-tool/load-list! tdb/*pool* {:version "synthetic-2026-10-v2"
                                           :entries [{:id "SYN-0001"
                                                      :rules [{:field :creditor-name :operator :exact
                                                               :value blocked}]}]}
                               {:source "test" :replacing list-version})
    (doseq [[label version] [["a retired version" list-version]
                             ["an unknown version" "synthetic-2099-01-v1"]]]
      (testing label
        (let [{:keys [status json]} (record! f pi {"listVersion" version "outcome" "hit"
                                                    "matchedEntries" [syn-0001]})]
          (is (= 422 status) (pr-str json))
          (is (= "list-version-not-accepted" (get-in json ["errors" "reason"])))
          (is (zero? (count-of "screening_result" pi)) "nothing stored"))))
    (testing "negative control: the accepted version is recorded"
      (is (= 201 (:status (record! f pi {"listVersion" "synthetic-2026-10-v2" "outcome" "hit"
                                         "matchedEntries" [syn-0001]})))))))

(deftest ac-17-4-an-instruction-digest-mismatch-is-422-and-changes-nothing
  (let [f (setup)
        pi (raise! f)
        digest (get (read-instruction f pi) "screeningDigest")
        off-by-one (str (subs digest 0 63) (if (= \0 (last digest)) "1" "0"))
        events-before (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event"]))
        {:keys [status json]} (record! f pi {"instructionDigest" off-by-one "outcome" "clear"
                                             "matchedEntries" []})]
    (is (re-matches #"[0-9a-f]{64}" digest))
    (is (= 422 status) (pr-str json))
    (is (= "instruction-digest-mismatch" (get-in json ["errors" "reason"])))
    (is (zero? (count-of "screening_result" pi)))
    (is (zero? (count-of "screening_case" pi)))
    (is (= events-before (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event"])))
        "no event")
    (testing "negative control: the digest read from `screeningDigest` is accepted"
      (let [{:keys [status json]} (record! f pi {"instructionDigest" digest "outcome" "clear"
                                                 "matchedEntries" []})]
        (is (= 201 status) (pr-str json))
        (is (= digest (get json "instructionDigest")))))))

(deftest ac-17-5-a-result-core-cannot-reproduce-is-422-screening-result-mismatch-and-is-recorded-as-refused
  (let [f (setup)
        pi (raise! f :creditor-name blocked)
        {:keys [status json]} (record! f pi {"outcome" "clear" "matchedEntries" []})]
    (is (= 422 status) (pr-str json))
    (is (= "screening-result-mismatch" (get-in json ["errors" "reason"])))
    (is (= "hit" (get-in json ["errors" "coreOutcome"])))
    (is (= ["SYN-0001"] (get-in json ["errors" "coreMatchedEntries"])))
    (let [[r :as rs] (get (results f pi) "screeningResults")]
      (is (= 1 (count rs)) "the refused result is stored")
      (is (= {"origin" "client" "outcome" "clear" "coreOutcome" "hit" "agrees" false
              "disposition" "refused" "dispositionReason" "screening-result-mismatch"
              "matchedEntries" [] "coreMatchedEntries" ["SYN-0001"]}
             (select-keys r ["origin" "outcome" "coreOutcome" "agrees" "disposition"
                             "dispositionReason" "matchedEntries" "coreMatchedEntries"])))
      (is (= (get-in json ["errors" "resultId"]) (get r "id")))
      (testing "with its event"
        (is (= 1 (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event
                                                     where action = 'screening-result.recorded'
                                                       and subject_id = ?"
                                                    (java.util.UUID/fromString (get r "id"))])))))
      (is (= (get r "auditEventId")
             (str (:id (db/query-one tdb/*pool* ["select id from audit_event where subject_id = ?"
                                                 (java.util.UUID/fromString (get r "id"))]))))))
    (is (= "draft" (get (read-instruction f pi) "status")))
    (is (zero? (count-of "screening_case" pi)) "a refused result opens no case"))
  (testing "a mismatch on the entry set alone is a mismatch: a hit naming SYN-0001
            for an instruction that matches SYN-0003 as well"
    (let [f (setup)
          pi (raise! f :creditor-name blocked :creditor-country "ZZ")
          {:keys [status json]} (record! f pi {"outcome" "hit" "matchedEntries" [syn-0001]})]
      (is (= 422 status))
      (is (= ["SYN-0001" "SYN-0003"] (get-in json ["errors" "coreMatchedEntries"])))))
  (testing "negative control: render the 422 *inside* the transaction, in a test
            double of the handler's order — and the evidence vanishes with it.
            The L-11 shape, shown once"
    (let [f (setup)
          pi (raise! f :creditor-name blocked)
          real service/record-result!]
      (with-redefs [service/record-result! (fn [tx args]
                                             (let [r (real tx args)]
                                               (when (:refused? r)
                                                 (err/fail! :unprocessable (:detail r)
                                                            {:reason (:reason r)}))
                                               r))]
        (is (= 422 (:status (record! f pi {"outcome" "clear" "matchedEntries" []})))))
      (is (zero? (count-of "screening_result" pi))
          "thrown inside the transaction, the refused result is rolled back: no evidence"))))

(deftest ac-17-6-a-201-screening-result-leaves-status-draft-and-emits-no-transition-event
  (let [f (setup)
        clear-pi (raise! f)
        hit-pi (raise! f :creditor-name blocked)
        before-clear (read-instruction f clear-pi)
        before-hit (read-instruction f hit-pi)
        clear (record! f clear-pi {"outcome" "clear" "matchedEntries" []})
        hit (record! f hit-pi {"outcome" "hit" "matchedEntries" [syn-0001]
                               "screenedAt" "2026-10-05T09:00:00Z"})]
    (doseq [[label {:keys [status json headers]} pi before] [["clear" clear clear-pi before-clear]
                                                             ["hit" hit hit-pi before-hit]]]
      (testing label
        (is (= 201 status) (pr-str json))
        (is (= (str "/screening-results?instructionId=" (get pi "id")) (get headers "location")))
        (is (= {"origin" "client" "disposition" "accepted" "agrees" true}
               (select-keys json ["origin" "disposition" "agrees"])))
        (is (some? (get json "auditEventId")))
        (let [after (read-instruction f pi)]
          (is (= "draft" (get after "status")) "evidence recorded, state unchanged — read back")
          (is (= (get before "permittedTransitions") (get after "permittedTransitions"))))
        (is (not-any? #(str/starts-with? % "payment.")
                      (remove #{"payment.created"} (actions-for-instruction pi)))
            "no payment.* event")))
    (testing "the accepted hit opened a case, and the 201 names it"
      (let [case-id (get-in hit [:json "caseId"])]
        (is (some? case-id))
        (is (= "open" (get (:json (call :get (str "/screening-cases/" case-id)
                                        :actor (:compliance f))) "status")))
        (is (= "2026-10-05T09:00:00Z" (get-in hit [:json "screenedAt"])))))
    (testing "the clear opened nothing and names nothing"
      (is (nil? (get-in clear [:json "caseId"])))
      (is (zero? (count-of "screening_case" clear-pi))))
    (testing "and a client's accepted clear does not satisfy the gate: core screens
              the hit instruction at submit regardless"
      (is (= 409 (:status (submit! f hit-pi)))))))

(deftest recording-refusals-in-the-published-order
  (let [f (setup)
        pi (raise! f :creditor-name blocked)]
    (testing "404 — another organisation's instruction"
      (let [g (setup)]
        (is (= 404 (:status (call :post (str "/payment-instructions/" (get pi "id") "/screening-results")
                                  :actor (:screener g) :idempotency-key (key!)
                                  :body {"organisationId" (:org g) "listVersion" list-version
                                         "outcome" "clear" "matchedEntries" []
                                         "instructionDigest" (apply str (repeat 64 "0"))}))))))
    (testing "outcome-entries-inconsistent — a hit with no entries, a clear with some"
      (doseq [body [{"outcome" "hit" "matchedEntries" []}
                    {"outcome" "clear" "matchedEntries" [syn-0001]}]]
        (is (= "outcome-entries-inconsistent"
               (get-in (:json (record! f pi body)) ["errors" "reason"])))))
    (testing "matched-entries-unknown — an id the version does not hold, and the
              right id with rules the list does not have"
      (doseq [entry [{"id" "SYN-9999" "rules" (get syn-0001 "rules")}
                     {"id" "SYN-0001" "rules" [{"field" "creditor-name" "operator" "exact"
                                                "value" "Blocked Counterparty"}]}]]
        (is (= "matched-entries-unknown"
               (get-in (:json (record! f pi {"outcome" "hit" "matchedEntries" [entry]}))
                       ["errors" "reason"])))))
    (testing "the list version is asked before the entries"
      (is (= "list-version-not-accepted"
             (get-in (:json (record! f pi {"listVersion" "nope" "outcome" "hit" "matchedEntries" []}))
                     ["errors" "reason"]))))
    (testing "a body the contract does not describe is 400"
      (is (= 400 (:status (record! f pi {"outcome" "clear" "matchedEntries" [] "coreOutcome" "clear"}))))
      (is (= 400 (:status (record! f pi {"outcome" "maybe" "matchedEntries" []}))))
      (is (= 400 (:status (record! f pi {"outcome" "hit"
                                         "matchedEntries" [{"id" "SYN-0001"
                                                            "rules" [{"field" "originator-name"
                                                                      "operator" "exact" "value" "x"}]}]})))))
    (is (zero? (count-of "screening_result" pi)) "none of those stored anything")
    (testing "409 — a submitted instruction, in the lifecycle's own shape"
      (let [clear-pi (raise! f)]
        (is (= 200 (:status (submit! f clear-pi))))
        (let [{:keys [status json]} (record! f clear-pi {"outcome" "clear" "matchedEntries" []})]
          (is (= 409 status))
          (is (= "pending-approval" (get-in json ["errors" "instruction-status"]))))))))

;; ---------------------------------------------------------------------------
;; AC-17-9 — the disposition
;; ---------------------------------------------------------------------------

(deftest ac-17-9-a-disposition-is-never-the-makers-and-is-final
  (let [f (setup)
        ;; A maker who also holds compliance: the one actor for whom the
        ;; permission check passes and the provenance rule must refuse.
        dual (tdb/insert-actor! tdb/*pool* {:organisation-id (:org-id f) :display-name "operator+compliance"
                                            :roles [:operator :compliance]})
        pi (raise! f :creditor-name blocked :as dual)
        case-id (get-in (:json (submit! f pi :as dual)) ["errors" "caseId"])]
    (testing "the maker → 403 self-disposition, whatever roles they hold"
      (let [{:keys [status json]} (disposition! f case-id "false-positive" :as dual)]
        (is (= 403 status))
        (is (= "self-disposition" (get-in json ["errors" "reason"])))))
    (testing "compliance → 200, and the event"
      (let [{:keys [status json]} (disposition! f case-id "false-positive")]
        (is (= 200 status) (pr-str json))
        (is (= {"status" "dispositioned" "disposition" "false-positive" "permittedTransitions" []}
               (select-keys json ["status" "disposition" "permittedTransitions"])))
        (is (= (str (:compliance f)) (get json "dispositionedBy")))
        (is (= 1 (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event
                                                     where action = 'screening-case.dispositioned'"]))))))
    (testing "a second disposition → 409"
      (is (= 409 (:status (disposition! f case-id "confirmed-hit")))))
    (testing "false-positive, then submit → pending-approval"
      (let [{:keys [status json]} (submit! f pi :as dual)]
        (is (= 200 status) (pr-str json))
        (is (= "pending-approval" (get json "status"))))))
  (testing "confirmed-hit, then submit → 409 naming the disposition, and cancel works"
    (let [f (setup)
          pi (raise! f :creditor-name blocked)
          case-id (get-in (:json (submit! f pi)) ["errors" "caseId"])]
      (is (= 200 (:status (disposition! f case-id "confirmed-hit"))))
      (let [{:keys [status json]} (submit! f pi)]
        (is (= 409 status))
        (is (= {"reason" "screening-hit" "disposition" "confirmed-hit" "caseId" case-id}
               (select-keys (get json "errors") ["reason" "disposition" "caseId"]))))
      (is (= "cancelled" (get-in (call :post (str "/payment-instructions/" (get pi "id") "/cancellation")
                                       :actor (:maker f) :idempotency-key (key!)
                                       :body {"organisationId" (:org f)})
                                 [:json "status"])))))
  (testing "a rationale is required, 1-1000 characters and not blank"
    (let [f (setup)
          pi (raise! f :creditor-name blocked)
          case-id (get-in (:json (submit! f pi)) ["errors" "caseId"])]
      (doseq [rationale ["" "   " (apply str (repeat 1001 "x"))]]
        (is (= 422 (:status (disposition! f case-id "false-positive" :rationale rationale)))
            (pr-str (count rationale))))
      (is (= 200 (:status (disposition! f case-id "false-positive"
                                        :rationale (apply str (repeat 1000 "x")))))))))

(deftest a-disposition-is-bound-to-the-content-it-was-made-on
  (testing "PRD Q2: an amendment makes a disposition moot — the next submission
            screens the amended content and opens a new case if the hit stands"
    (let [f (setup)
          pi (raise! f :creditor-name blocked)
          first-case (get-in (:json (submit! f pi)) ["errors" "caseId"])]
      (is (= 200 (:status (disposition! f first-case "false-positive"))))
      (is (= 200 (:status (call :patch (str "/payment-instructions/" (get pi "id"))
                                :actor (:maker f) :idempotency-key (key!)
                                :body {"organisationId" (:org f)
                                       "creditorAccount" "SG-SYNTH-99999999"}))))
      (let [{:keys [status json]} (submit! f pi)]
        (is (= 409 status) "the false-positive was given on other content")
        (is (not= first-case (get-in json ["errors" "caseId"])) "a new case")
        (is (= 2 (count-of "screening_case" pi))))))
  (testing "and an amendment that clears the hit submits on a fresh decision"
    (let [f (setup)
          pi (raise! f :creditor-name blocked)]
      (is (= 409 (:status (submit! f pi))))
      (call :patch (str "/payment-instructions/" (get pi "id"))
            :actor (:maker f) :idempotency-key (key!)
            :body {"organisationId" (:org f) "creditorName" ordinary})
      (is (= 200 (:status (submit! f pi))))
      (is (= ["clear" "hit"] (mapv #(get % "outcome") (get (results f pi) "screeningResults")))
          "newest first: core's fresh clear, then the earlier hit"))))

(deftest ac-17-9-a-client-hit-never-supersedes-a-disposition
  ;; 017-REQ R-1. `decide` reads the latest case for (instruction, digest, list),
  ;; so a second case for the same triple would let a client's evidence overturn
  ;; a final disposition with no amendment at all.
  (doseq [[disposition submit-status] [["confirmed-hit" 409] ["false-positive" 200]]]
    (testing (str "after " disposition ", a client's accepted hit on the same content and list")
      (let [f (setup)
            pi (raise! f :creditor-name blocked)
            case-id (get-in (:json (submit! f pi)) ["errors" "caseId"])]
        (is (= 200 (:status (disposition! f case-id disposition))))
        (let [{:keys [status json]} (record! f pi {"outcome" "hit" "matchedEntries" [syn-0001]})]
          (is (= 201 status) (pr-str json))
          (is (= case-id (get json "caseId")) "names the case that already covers this content")
          (is (= 1 (count-of "screening_case" pi)) "and opens no second case"))
        (is (= {"status" "dispositioned" "disposition" disposition}
               (select-keys (:json (call :get (str "/screening-cases/" case-id) :actor (:compliance f)))
                            ["status" "disposition"]))
            "the disposition stands")
        (let [{:keys [status json]} (submit! f pi)]
          (is (= submit-status status) (str "the gate still answers by the disposition: " (pr-str json)))
          (when (= 409 submit-status)
            (is (= {"disposition" "confirmed-hit" "caseId" case-id}
                   (select-keys (get json "errors") ["disposition" "caseId"]))))))))
  (testing "negative control: with no case on this content, a client's accepted hit opens one"
    (let [f (setup)
          pi (raise! f :creditor-name blocked)
          {:keys [status json]} (record! f pi {"outcome" "hit" "matchedEntries" [syn-0001]})]
      (is (= 201 status))
      (is (some? (get json "caseId")))
      (is (= 1 (count-of "screening_case" pi))))))

(deftest ac-17-2-a-case-open-on-earlier-content-is-named-as-blocking-never-as-this-contents
  ;; 017-REQ R-2. One open case per instruction (`screening_case_open_key`): a
  ;; case left open across an amendment blocks the amended content's case, and
  ;; the 409 must say so rather than present it as this content's case.
  (let [f (setup)
        pi (raise! f :creditor-name blocked)
        first-case (get-in (:json (submit! f pi)) ["errors" "caseId"])
        _ (is (some? first-case))
        _ (is (= 200 (:status (call :patch (str "/payment-instructions/" (get pi "id"))
                                    :actor (:maker f) :idempotency-key (key!)
                                    :body {"organisationId" (:org f)
                                           "creditorAccount" "SG-SYNTH-99999999"}))))
        {:keys [status json]} (submit! f pi)]
    (testing "the amended content still hits; the first case is still open"
      (is (= 409 status) (pr-str json))
      (is (= "screening-hit" (get-in json ["errors" "reason"]))))
    (testing "the open case is named as what blocks, and not as this content's case"
      (is (= first-case (get-in json ["errors" "blockingCaseId"])))
      (is (not (contains? (get json "errors") "caseId")))
      (is (str/includes? (get json "detail") "earlier content"))
      (is (= list-version (get-in json ["errors" "listVersion"])))
      (is (= 1 (count-of "screening_case" pi)) "no case could open"))
    (testing "once compliance dispositions the stale case, the next submission opens this content's"
      (is (= 200 (:status (disposition! f first-case "false-positive"))))
      (let [{:keys [status json]} (submit! f pi)
            second-case (get-in json ["errors" "caseId"])]
        (is (= 409 status))
        (is (some? second-case))
        (is (not= first-case second-case))
        (is (not (contains? (get json "errors") "blockingCaseId")))
        (is (= (get (read-instruction f pi) "screeningDigest")
               (get (:json (call :get (str "/screening-cases/" second-case) :actor (:compliance f)))
                    "instructionDigest"))
            "bound to the content as it stands")
        (is (= 200 (:status (disposition! f second-case "false-positive"))))
        (is (= 200 (:status (submit! f pi))))))
    (testing "and after a list replacement, the old list's open case is named as blocking too"
      (let [f (setup)
            pi (raise! f :creditor-name blocked)
            old-case (get-in (:json (submit! f pi)) ["errors" "caseId"])]
        (screening-tool/load-list! tdb/*pool* {:version "synthetic-2026-10-v2"
                                               :entries [{:id "SYN-0001"
                                                          :rules [{:field :creditor-name :operator :exact
                                                                   :value blocked}]}]}
                                   {:source "test" :replacing list-version})
        (let [{:keys [status json]} (submit! f pi)]
          (is (= 409 status))
          (is (= old-case (get-in json ["errors" "blockingCaseId"])))
          (is (not (contains? (get json "errors") "caseId")))
          (is (= "synthetic-2026-10-v2" (get-in json ["errors" "listVersion"])))
          (is (str/includes? (get json "detail") (str "against list " list-version))))))))

;; ---------------------------------------------------------------------------
;; AC-17-12 — the screening-service actor
;; ---------------------------------------------------------------------------

(deftest ac-17-12-the-screening-service-cannot-submit-approve-create-or-disposition
  (let [f (setup)
        pi (raise! f :creditor-name blocked)
        case-id (get-in (:json (submit! f pi)) ["errors" "caseId"])
        clear-pi (raise! f)
        s (:screener f)]
    (is (= 403 (:status (submit! f clear-pi :as s))) "submit")
    ;; Approval ranks the lifecycle before the permission (an approve on a draft
    ;; is 409 whoever sends it), so the checker's question is put to a
    ;; `pending-approval` instruction.
    (is (= 200 (:status (submit! f clear-pi))))
    (is (= 403 (:status (call :post (str "/payment-instructions/" (get clear-pi "id") "/approvals")
                              :actor s :idempotency-key (key!)
                              :body {"organisationId" (:org f) "decision" "approved"})))
        "approve")
    (is (= 403 (:status (call :post "/payment-instructions" :actor s :idempotency-key (key!)
                              :body {"organisationId" (:org f) "debtorAccountId" (:account f)
                                     "creditorName" ordinary "creditorAccount" "SG-SYNTH-88012345"
                                     "amount" {"currency" "SGD" "minorUnits" 1}
                                     "valueDate" (str (.plusDays (LocalDate/now ZoneOffset/UTC) 7))
                                     "purposeCode" "SUPP"})))
        "create")
    (is (= 403 (:status (disposition! f case-id "false-positive" :as s))) "disposition")
    (testing "and what it can do: read the instruction's digest and the list, and record"
      (is (= 200 (:status (call :get (str "/payment-instructions/" (get pi "id")) :actor s))))
      (is (= 200 (:status (call :get (str "/screening-lists/" list-version) :actor s))))
      (is (= 201 (:status (record! f (raise! f) {"outcome" "clear" "matchedEntries" []})))))))

(deftest the-reads
  (let [f (setup)
        pi (raise! f :creditor-name blocked)
        case-id (get-in (:json (submit! f pi)) ["errors" "caseId"])]
    (testing "the list, entry by entry, as loaded"
      (let [{:keys [status json]} (call :get (str "/screening-lists/" list-version) :actor (:auditor f))]
        (is (= 200 status))
        (is (= ["SYN-0001" "SYN-0002" "SYN-0003" "SYN-0004"] (mapv #(get % "id") (get json "entries"))))
        (is (true? (get json "accepted")))
        (is (= 2 (count (get-in json ["entries" 3 "rules"]))))))
    (is (= [{"version" list-version "accepted" true "entryCount" 4}]
           (mapv #(select-keys % ["version" "accepted" "entryCount"])
                 (get-in (call :get "/screening-lists" :actor (:checker f)) [:json "screeningLists"]))))
    (is (= 404 (:status (call :get "/screening-lists/no-such-version" :actor (:auditor f)))))
    (is (= [case-id] (mapv #(get % "id") (get-in (call :get "/screening-cases" :actor (:auditor f)
                                                     :query "status=open")
                                               [:json "screeningCases"]))))
    (is (= [] (get-in (call :get "/screening-cases" :actor (:auditor f) :query "status=dispositioned")
                      [:json "screeningCases"])))
    (testing "screening reads are not the maker's business"
      (is (= 403 (:status (call :get "/screening-cases" :actor (:maker f)))))
      (is (= 403 (:status (call :get (str "/screening-lists/" list-version) :actor (:maker f))))))
    (testing "another organisation's case and instruction are 404"
      (let [g (setup)]
        (is (= 404 (:status (call :get (str "/screening-cases/" case-id) :actor (:compliance g)))))
        (is (= 404 (:status (call :get "/screening-results" :actor (:compliance g)
                                  :query (str "instructionId=" (get pi "id"))))))))))
