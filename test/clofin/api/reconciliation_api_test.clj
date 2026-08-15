(ns clofin.api.reconciliation-api-test
  "Reconciliation end to end, without a socket.

  These call the fully-wrapped handler — router, middleware, authorisation,
  error translation, JSON codec — with a request map and assert on the response,
  on the database and on the audit trail. That is the whole stack a caller
  meets, minus Jetty (ADR-0010).

  **Every walk starts from a real settlement.** Payments are raised, approved,
  batched, submitted and answered through the public API, and the statement is
  then produced by `GET /settlement-statements` from what the simulation
  actually did. Nothing here hand-writes a statement to match what the matcher
  expects: a fixture built to agree would be standing lesson **L-16**'s failure
  wearing a test's clothes.

  Acceptance criteria from `docs/briefs/008-TASK-reconciliation.md` are named in
  the tests that cover them."
  (:require [clofin.db.core :as db]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time Instant LocalDate ZoneOffset]))

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

(defn- uuid [s] (java.util.UUID/fromString s))

;; ---------------------------------------------------------------------------
;; Fixtures — built through the API wherever the API can build them
;; ---------------------------------------------------------------------------

(def ^:private accounts
  [["1100-CLIENT-FUNDS" "asset"]
   ["1300-IN-TRANSIT" "asset"]
   ["2100-CLIENT-PAYABLE" "liability"]
   ["2200-UNAPPLIED" "liability"]])

(defn- setup
  "An organisation, its accounts, and the actors these tests act as.

  The threshold bands are the interesting part. The floor is **not** zero:
  SGD 1,000.00 is where a second pair of eyes starts, which is what makes the
  de-minimis case reachable at all — an organisation whose lowest band starts at
  zero has no de-minimis, and every adjustment it makes needs approval. Payments
  of SGD 1,250.00 still need one approval, because the same band covers them."
  [& {:keys [open-accounts] :or {open-accounts accounts}}]
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
        checker-2  (seed [:approver] {"SGD" 100000000})
        small-checker (seed [:approver] {"SGD" 50000})
        auditor    (seed [:auditor] {})]
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "SGD"
                                       :from-minor 100000 :approvals-required 1})
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "SGD"
                                       :from-minor 1000000 :approvals-required 2})
    (doseq [[code type] open-accounts]
      (is (= 201 (:status (call :post "/accounts"
                                :actor controller
                                :body {"organisationId" (str org) "code" code
                                       "name" code "type" type "currency" "SGD"})))))
    {:org org :controller controller :maker maker :checker checker
     :checker-2 checker-2 :small-checker small-checker :auditor auditor}))

(defn- account-id
  [{:keys [org controller]} code]
  (->> (get (:json (call :get "/accounts" :actor controller
                         :query (str "organisationId=" org)))
            "accounts")
       (filter #(= code (get % "code")))
       first
       (#(get % "id"))))

(defn- raise!
  "An approved instruction."
  [{:keys [org maker checker] :as f} & {:keys [creditor-account amount]
                                        :or {creditor-account "SG-SYNTH-88012340"
                                             amount 125000}}]
  (let [{:keys [status json]}
        (call :post "/payment-instructions"
              :actor maker :idempotency-key (str (random-uuid))
              :body {"organisationId" (str org)
                     "debtorAccountId" (account-id f "1100-CLIENT-FUNDS")
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

(defn- settle!
  "Raise, batch, submit and answer — the settlement the reconciliation is about.

  Returns `{:settled [id …] :returned [id …] :unanswered [id …] :batch id}`.
  The unanswered instruction is the case that matters most: it is correctly
  still in `1300-IN-TRANSIT` on CloFin's books and correctly absent from every
  statement."
  [{:keys [org controller] :as f} & {:keys [settled returned unanswered]
                                     :or {settled 1 returned 1 unanswered 0}}]
  ;; Every amount is at or above the organisation's lowest approval band, so a
  ;; payment can be approved at all. Below it a payment has no band and is
  ;; refused `no-threshold-configured` — which is the payments rule, and is
  ;; deliberately *not* the adjustments rule (see `clofin.recon.adjustment`).
  (let [ids (fn [n amount] (vec (repeatedly n #(raise! f :amount amount))))
        to-settle   (ids settled 125000)
        to-return   (ids returned 110000)
        to-ignore   (ids unanswered 105000)
        all (concat to-settle to-return to-ignore)
        batch (get (:json (call :post "/settlement-batches"
                                :actor controller
                                :body {"organisationId" (str org) "scheme" "SIM-RTGS"
                                       "currency" "SGD" "valueDate" value-date
                                       "instructionIds" (vec all)}))
                   "id")]
    (is (= 200 (:status (call :post (str "/settlement-batches/" batch "/submit")
                              :actor controller :body {"organisationId" (str org)}))))
    (doseq [id to-settle]
      (is (= 200 (:status (call :post (str "/settlement-batches/" batch "/scheme-responses")
                                :actor controller
                                :body {"organisationId" (str org) "kind" "settled"
                                       "instructionId" id
                                       "reference" (str "SIM-STL-" batch "-" id)})))))
    (doseq [id to-return]
      (is (= 200 (:status (call :post (str "/settlement-batches/" batch "/scheme-responses")
                                :actor controller
                                :body {"organisationId" (str org) "kind" "returned"
                                       "instructionId" id
                                       "reason" "SIM-RETURN: simulated scheme return"
                                       "reference" (str "SIM-RTN-" batch "-" id)})))))
    {:settled to-settle :returned to-return :unanswered to-ignore :batch batch}))

;; ---------------------------------------------------------------------------
;; The period, and the two endpoints under test
;; ---------------------------------------------------------------------------

(defn- period
  "A period wide enough to contain everything this test just did.

  Anchored to **midnight UTC** rather than to the calling instant, so two calls
  inside one test ask for the same period — which is what makes generating the
  same statement twice a replay rather than two documents."
  []
  (let [midnight (.toInstant (.atStartOfDay (LocalDate/now ZoneOffset/UTC) ZoneOffset/UTC))]
    {:from (str (.minusSeconds midnight 86400))
     :to   (str (.plusSeconds midnight 86400))}))

(defn- generate!
  [{:keys [org controller]} & {:keys [perturbation actor] :or {perturbation "none"}}]
  (let [{:keys [from to]} (period)
        {:keys [status json]}
        (call :get "/settlement-statements"
              :actor (or actor controller)
              :query (str "organisationId=" org "&scheme=SIM-RTGS&currency=SGD"
                          "&from=" from "&to=" to "&perturbation=" perturbation))]
    (is (= 200 status) (str "statement not generated: " json))
    json))

(defn- ingest!
  [{:keys [org controller]} document & {:keys [actor]}]
  (call :post "/reconciliation-statements"
        :actor (or actor controller)
        :body (assoc document "organisationId" (str org))))

(defn- reconcile!
  "The whole walk: settle, generate, ingest. Returns the ingestion response."
  [f & {:keys [perturbation settled returned unanswered]
        :or {perturbation "none" settled 1 returned 1 unanswered 0}}]
  (settle! f :settled settled :returned returned :unanswered unanswered)
  (ingest! f (generate! f :perturbation perturbation)))

(defn- breaks [response] (get-in response [:json "breaks"]))
(defn- break-kinds [response] (mapv #(get % "kind") (breaks response)))
(defn- matches [response] (get-in response [:json "matches"]))

(defn- audit-actions
  "Every audit action recorded for this organisation, with its count."
  [{:keys [org auditor]}]
  (->> (get (:json (call :get "/audit/events" :actor auditor
                         :query (str "organisationId=" org)))
            "auditEvents")
       (map #(get % "action"))
       frequencies))

;; ---------------------------------------------------------------------------
;; AC-1 — an unperturbed statement matches completely
;; ---------------------------------------------------------------------------

(deftest ac-1-an-unperturbed-statement-matches-every-line-and-opens-no-break
  (let [f (setup)
        response (reconcile! f)]
    (is (= 200 (:status response)) (str (:body response)))
    (is (= "applied" (get-in response [:json "disposition"])))
    (is (= 2 (count (get-in response [:json "lines"]))))
    (is (= 2 (count (matches response))))
    (is (empty? (breaks response))
        (str "an unperturbed statement is the statement the simulation would
              really send, and it must agree completely — breaks: "
             (pr-str (mapv #(get % "detail") (breaks response)))))
    (testing "and every match records WHICH rule matched (PR-051)"
      (is (every? #(= "R1-reference-amount-and-value-date" (get % "rule"))
                  (matches response)))
      (is (every? #(some? (get % "entryId")) (matches response))
          "bound to a journal entry, which is what an investigation reads"))))

(deftest ac-1-a-payment-the-scheme-never-answered-about-is-absent-and-not-a-break
  (testing "the case ADR-0018's clearing account exists to show: the money is
            still in 1300-IN-TRANSIT on CloFin's books AND correctly missing
            from the statement. A reconciliation that reported it would be
            reporting CloFin's own decision to keep waiting"
    (let [f (setup)
          response (reconcile! f :unanswered 1)]
      (is (= 2 (count (get-in response [:json "lines"])))
          "two answered payments, one line each")
      (is (empty? (breaks response))
          (str "breaks: " (pr-str (mapv #(get % "detail") (breaks response))))))))

(deftest ac-1-a-settlement-and-a-return-are-told-apart-by-the-accounting
  (testing "CloFin derives the kind of movement from the entry's counter-account
            and the scheme states it on the line; they are two records agreeing"
    (let [f (setup)
          response (reconcile! f)]
      (is (= #{"settlement" "return"}
             (set (mapv #(get % "lineType") (get-in response [:json "lines"])))))
      (is (empty? (breaks response))))))

;; ---------------------------------------------------------------------------
;; AC-2 — replay, and the same identity carrying a different document
;; ---------------------------------------------------------------------------

(defn- row-counts
  [org]
  (into {}
        (map (fn [t] [t (:count (db/query-one
                                 tdb/*pool*
                                 [(str "select count(*) as count from " t)]))]))
        ["reconciliation_statement" "reconciliation_statement_line"
         "reconciliation_match" "reconciliation_break" "journal_entry" "audit_event"]))

(deftest ac-2-the-same-statement-delivered-twice-replays-and-does-no-work
  (let [f (setup)
        _ (settle! f)
        document (generate! f)
        first-delivery (ingest! f document)
        after-first (row-counts (:org f))
        second-delivery (ingest! f document)]
    (is (= 200 (:status first-delivery)))
    (is (false? (get-in first-delivery [:json "replayed"])))
    (is (= 200 (:status second-delivery)))
    (is (true? (get-in second-delivery [:json "replayed"]))
        "a duplicate delivery is the normal case in the world this simulates,
         not an error")
    (is (= after-first (row-counts (:org f)))
        "no second match, no second break, no second entry and no second audit
         event")
    (is (= (get-in first-delivery [:json "id"]) (get-in second-delivery [:json "id"]))
        "and the answer is the original one, reproduced from the row")))

(deftest ac-2-a-different-document-under-the-same-reference-is-refused
  (let [f (setup)
        _ (settle! f)
        document (generate! f)
        _ (is (= 200 (:status (ingest! f document))))
        before (row-counts (:org f))
        tampered (update document "lines"
                         (fn [lines]
                           (update-in (vec lines) [0 "amount" "minorUnits"] + 1)))
        conflict (ingest! f tampered)]
    (is (= 409 (:status conflict))
        "two documents that say different things cannot share one reference")
    (is (= "replay-key-conflict" (get-in conflict [:json "errors" "dispositionReason"])))
    (is (false? (get-in conflict [:json "errors" "replayed"]))
        "and it is never called a replay — answering `replayed: true` there would
         tell a caller CloFin had already seen a document nobody had sent")
    (is (= before (row-counts (:org f)))
        "the first receipt already stands as the evidence; a second row would
         defeat the replay key that produced this refusal")))

;; ---------------------------------------------------------------------------
;; AC-3 — a refused statement is still recorded as having arrived
;; ---------------------------------------------------------------------------

(deftest ac-3-a-refused-statement-keeps-its-receipt-and-replays-the-same-refusal
  (testing "receipt and disposition are separate facts (L-11): a receipt
            destroyed by its own processing failure is not a receipt"
    (let [f (setup :open-accounts [["1100-CLIENT-FUNDS" "asset"]
                                   ["2200-UNAPPLIED" "liability"]])
          document {"format" "SIM-CLOFIN-RECON-STATEMENT"
                    "formatVersion" 1
                    "scheme" "SIM-RTGS"
                    "currency" "SGD"
                    "statementReference" "SIM-STMT-NO-ACCOUNT"
                    "periodStart" (:from (period))
                    "periodEnd" (:to (period))
                    "lines" []}
          refusal (ingest! f document)]
      (is (= 422 (:status refusal)))
      (is (= "no-reconciled-account" (get-in refusal [:json "errors" "dispositionReason"])))
      (is (some? (get-in refusal [:json "errors" "receiptId"]))
          "the caller is told which receipt records its delivery")

      (testing "and the receipt is durable — the refusal was rendered after it committed"
        (let [receipt-id (get-in refusal [:json "errors" "receiptId"])
              stored (call :get (str "/reconciliation-statements/" receipt-id)
                           :actor (:controller f)
                           :query (str "organisationId=" (:org f)))]
          (is (= 200 (:status stored)))
          (is (= "refused" (get-in stored [:json "disposition"])))
          (is (= "no-reconciled-account" (get-in stored [:json "dispositionReason"])))
          (is (nil? (get-in stored [:json "reconciledAccountId"]))
              "null exactly when the statement was refused for want of one")))

      (testing "the refusal is reproducible on replay, even after the gap is closed"
        (is (= 201 (:status (call :post "/accounts" :actor (:controller f)
                                  :body {"organisationId" (str (:org f))
                                         "code" "1300-IN-TRANSIT" "name" "in transit"
                                         "type" "asset" "currency" "SGD"}))))
        (let [replayed (ingest! f document)]
          (is (= 422 (:status replayed))
              "a receipt whose disposition was refused answers the same way
               however the world has moved on since — it is not re-evaluated")
          (is (true? (get-in replayed [:json "errors" "replayed"]))))))))

(deftest ac-3-a-document-clofin-cannot-understand-earns-no-receipt
  (testing "only a document well-formed enough to BE a statement earns one,
            which is what keeps the table a record of deliveries rather than of
            typos"
    (let [f (setup)
          before (row-counts (:org f))]
      (doseq [[label document]
              [["a real bank format" {"format" "camt.053.001.08" "formatVersion" 1
                                      "scheme" "SIM-RTGS" "currency" "SGD"
                                      "statementReference" "x"
                                      "periodStart" (:from (period))
                                      "periodEnd" (:to (period)) "lines" []}]
               ["a real scheme name" {"format" "SIM-CLOFIN-RECON-STATEMENT" "formatVersion" 1
                                      "scheme" "TARGET2" "currency" "SGD"
                                      "statementReference" "x"
                                      "periodStart" (:from (period))
                                      "periodEnd" (:to (period)) "lines" []}]
               ["an inverted period" {"format" "SIM-CLOFIN-RECON-STATEMENT" "formatVersion" 1
                                      "scheme" "SIM-RTGS" "currency" "SGD"
                                      "statementReference" "x"
                                      "periodStart" (:to (period))
                                      "periodEnd" (:from (period)) "lines" []}]]]
        (is (= 400 (:status (ingest! f document))) label))
      (is (= before (row-counts (:org f)))))))

;; ---------------------------------------------------------------------------
;; AC-4 — every perturbation class produces the break it names, both directions
;; ---------------------------------------------------------------------------

(deftest ac-4-each-perturbation-class-produces-the-break-it-names
  (doseq [[class expected]
          [["missing-line"       "expectation-unmatched"]
           ["amount-mismatch"    "amount-mismatch"]
           ["unknown-line"       "statement-line-unmatched"]
           ["duplicate-line"     "duplicate-statement-line"]
           ["shifted-value-date" "value-date-mismatch"]
           ["flipped-line-type"  "line-type-mismatch"]]]
    (testing class
      (let [f (setup)
            response (reconcile! f :perturbation class)]
        (is (= 200 (:status response)) (str (:body response)))
        (is (contains? (set (break-kinds response)) expected)
            (str class " should open a " expected " break; opened "
                 (pr-str (break-kinds response))))
        (testing "and the break names what disagreed rather than merely that
                  something did"
          (let [brk (first (filter #(= expected (get % "kind")) (breaks response)))]
            (is (not (str/blank? (get brk "detail"))))
            (is (some? (get brk "assigneeId")) "a break is never unowned")
            (is (= "open" (get brk "state")))
            (is (some? (get brk "ageSeconds")) "and carries a derived age")))))))

(deftest ac-4-the-check-runs-in-both-directions
  (testing "a statement line with no ledger counterpart, and a ledger movement
            with no statement line — the second being the one a
            statement-line-only reconciliation would miss entirely"
    (let [statement-side (reconcile! (setup) :perturbation "unknown-line")
          ledger-side    (reconcile! (setup) :perturbation "missing-line")]
      (is (= ["statement-line-unmatched"] (break-kinds statement-side)))
      (is (some? (get (first (breaks statement-side)) "lineNo")))
      (is (nil? (get (first (breaks statement-side)) "entryId")))

      (is (= ["expectation-unmatched"] (break-kinds ledger-side)))
      (is (nil? (get (first (breaks ledger-side)) "lineNo")))
      (is (some? (get (first (breaks ledger-side)) "entryId"))))))

(deftest ac-4-a-line-with-no-reference-still-matches-and-records-the-weaker-rule
  (let [f (setup)
        response (reconcile! f :perturbation "reference-stripped")]
    (is (empty? (breaks response)))
    (is (contains? (set (map #(get % "rule") (matches response)))
                   "R4-amount-and-value-date")
        "the ordered sequence is not decoration: a line the scheme did not tag is
         still matched, by a weaker rule, and the match says which")))

(deftest ac-4-a-returned-payments-line-matches-the-return-and-not-a-retry
  (testing "ADR-0019: a retry is a NEW instruction. Because the end-to-end
            reference is the instruction id, a line about the original and a
            line about its retry can never be confused for one another"
    (let [f (setup)
          {:keys [returned]} (settle! f :settled 0 :returned 1)
          retry (raise! f :amount 110000)         ; same amount, new instruction
          document (generate! f)
          response (ingest! f document)
          line (first (get-in response [:json "lines"]))]
      (is (= 1 (count (get-in response [:json "lines"]))))
      (is (= (first returned) (get line "paymentReference"))
          "the line names the original, which is the instruction that was returned")
      (is (not= retry (get line "paymentReference")))
      (is (empty? (breaks response))
          "and the retry, being un-batched and un-settled, is no part of this
           reconciliation at all"))))

;; ---------------------------------------------------------------------------
;; AC-6 — the break lifecycle is enforced at the boundary
;; ---------------------------------------------------------------------------

(defn- a-break!
  "One break to work with, and the ingestion that produced it."
  [f & {:keys [perturbation] :or {perturbation "unknown-line"}}]
  (let [response (reconcile! f :perturbation perturbation)]
    (is (= 200 (:status response)) (str (:body response)))
    (first (breaks response))))

(deftest ac-6-assigning-an-open-break-moves-it-and-reassigning-does-not
  (let [f (setup)
        brk (a-break! f)
        assign (fn [assignee]
                 (call :post (str "/reconciliation-breaks/" (get brk "id") "/assignment")
                       :actor (:controller f)
                       :body {"organisationId" (str (:org f))
                              "assigneeId" (str assignee)}))]
    (is (= "open" (get brk "state")))
    (is (= ["assign" "resolve"] (get brk "permittedTransitions"))
        "derived from the lifecycle table, so the API cannot advertise an
         operation the state machine would refuse")

    (let [taken (assign (:maker f))]
      (is (= 200 (:status taken)))
      (is (= "investigating" (get-in taken [:json "state"]))
          "assignment IS the transition: a break becomes investigated by
           somebody taking it on")
      (is (= (str (:maker f)) (get-in taken [:json "assigneeId"]))))

    (let [again (assign (:checker f))]
      (is (= 200 (:status again)))
      (is (= "investigating" (get-in again [:json "state"]))
          "reassigning leaves the state where it is — no self-arrow")
      (is (= (str (:checker f)) (get-in again [:json "assigneeId"]))))))

(deftest ac-6-a-transition-the-table-does-not-contain-is-refused-not-applied
  (let [f (setup)
        brk (a-break! f)]
    (testing "a break resolved by adjustment can be neither assigned nor
              re-resolved: who resolved what is history"
      (is (= 201 (:status (call :post (str "/reconciliation-breaks/" (get brk "id")
                                           "/adjustments")
                                :actor (:controller f)
                                :body {"organisationId" (str (:org f))
                                       "amount" {"currency" "SGD" "minorUnits" 99999}
                                       "direction" "credit"
                                       "narrative" "Agreeing with the scheme"}))))
      (let [refused (call :post (str "/reconciliation-breaks/" (get brk "id") "/assignment")
                          :actor (:controller f)
                          :body {"organisationId" (str (:org f))
                                 "assigneeId" (str (:maker f))})]
        (is (= 409 (:status refused)))
        (is (= "resolved" (get-in refused [:json "errors" "break-state"]))
            "the refusal names the state the break is actually in")
        (is (= ["investigating" "open"]
               (sort (get-in refused [:json "errors" "assignable-in"])))
            "and what would have been permitted instead"))
      (let [refused (call :post (str "/reconciliation-breaks/" (get brk "id") "/adjustments")
                          :actor (:controller f)
                          :body {"organisationId" (str (:org f))
                                 "amount" {"currency" "SGD" "minorUnits" 100}
                                 "direction" "credit"
                                 "narrative" "Twice"})]
        (is (= 409 (:status refused)))))))

(deftest ac-6-a-break-can-only-be-assigned-to-an-actor-of-its-own-organisation
  (let [f (setup)
        other (setup)
        brk (a-break! f)
        refused (call :post (str "/reconciliation-breaks/" (get brk "id") "/assignment")
                      :actor (:controller f)
                      :body {"organisationId" (str (:org f))
                             "assigneeId" (str (:controller other))})]
    (is (= 422 (:status refused)))
    (is (str/includes? (str/lower-case (get-in refused [:json "detail"])) "no such actor")
        "reported as unknown rather than as forbidden: saying `another
         organisation's` would confirm the id names a real actor somewhere else")))

;; ---------------------------------------------------------------------------
;; AC-5 — resolution by adjustment, and the threshold that governs it
;; ---------------------------------------------------------------------------

(defn- propose!
  [f brk & {:keys [minor actor direction narrative]
            :or {minor 99999 direction "credit" narrative "Agreeing with the scheme"}}]
  (call :post (str "/reconciliation-breaks/" (get brk "id") "/adjustments")
        :actor (or actor (:controller f))
        :body {"organisationId" (str (:org f))
               "amount" {"currency" "SGD" "minorUnits" minor}
               "direction" direction
               "narrative" narrative}))

(defn- approve!
  [f adjustment-id & {:keys [actor]}]
  (call :post (str "/reconciliation-adjustments/" adjustment-id "/approvals")
        :actor actor
        :body {"organisationId" (str (:org f))}))

(deftest ac-5-below-the-threshold-one-actor-posts-and-the-break-resolves
  (let [f (setup)
        brk (a-break! f)
        proposal (propose! f brk :minor 99999)]
    (is (= 201 (:status proposal)))
    (is (true? (get-in proposal [:json "posted"]))
        "below the lowest band the organisation configured, the proposer alone
         may post")
    (is (= 0 (get-in proposal [:json "approvalsRequired"])))
    (is (= "posted" (get-in proposal [:json "status"])))
    (is (= "resolved" (get-in proposal [:json "break" "state"])))
    (is (some? (get-in proposal [:json "entryId"])))

    (testing "and the entry is a real, balanced journal entry on the public path"
      (let [entry (call :get (str "/journal-entries/" (get-in proposal [:json "entryId"]))
                        :actor (:controller f)
                        :query (str "organisationId=" (:org f)))]
        (is (= 200 (:status entry)))
        (is (= "reconciliation-adjustment" (get-in entry [:json "reference" "type"])))
        (is (= 2 (count (get-in entry [:json "lines"]))))
        (is (= #{"debit" "credit"}
               (set (map #(get % "direction") (get-in entry [:json "lines"])))))
        (is (= #{(account-id f "1300-IN-TRANSIT") (account-id f "2200-UNAPPLIED")}
               (set (map #(get % "accountId") (get-in entry [:json "lines"])))))))))

(deftest ac-5-the-boundary-is-tested-at-the-boundary-value
  (testing "SGD 1,000.00 is the lowest band, and the bound is inclusive"
    (doseq [[minor expected-required expected-posted]
            [[99999  0 true]
             [100000 1 false]
             [100001 1 false]]]
      (let [f (setup)
            brk (a-break! f)
            proposal (propose! f brk :minor minor)]
        (is (= 201 (:status proposal)) (str minor))
        (is (= expected-required (get-in proposal [:json "approvalsRequired"]))
            (str minor " minor units"))
        (is (= expected-posted (get-in proposal [:json "posted"]))
            (str minor " minor units"))))))

(deftest ac-5-above-the-threshold-a-second-different-actor-must-approve
  (let [f (setup)
        brk (a-break! f)
        proposal (propose! f brk :minor 100000)
        adjustment-id (get-in proposal [:json "id"])]
    (is (false? (get-in proposal [:json "posted"])))
    (is (= "proposed" (get-in proposal [:json "status"])))
    (is (= "open" (get-in proposal [:json "break" "state"]))
        "a proposed adjustment resolves nothing")

    (testing "the actor who created the adjustment cannot approve it — C-01's
              own comparison, applied to a different subject and refused first"
      (let [refused (approve! f adjustment-id :actor (:controller f))]
        (is (= 403 (:status refused)))
        (is (= "self-approval" (get-in refused [:json "errors" "reason"])))
        (is (str/includes? (get-in refused [:json "detail"]) "reconciliation adjustment")
            "and the prose names the subject it is about rather than a payment")))

    (testing "an actor whose limit is below the amount is refused too (C-02)"
      (let [refused (approve! f adjustment-id :actor (:small-checker f))]
        (is (= 403 (:status refused)))
        (is (= "above-actor-limit" (get-in refused [:json "errors" "reason"])))))

    (testing "and a different, authorised actor posts it"
      (let [approved (approve! f adjustment-id :actor (:checker f))]
        (is (= 201 (:status approved)))
        (is (true? (get-in approved [:json "posted"])))
        (is (= 1 (get-in approved [:json "approvalsHeld"])))
        (is (= "posted" (get-in approved [:json "adjustment" "status"])))
        (is (= "resolved" (get-in approved [:json "break" "state"]))
            "the entry posts and the break resolves in the same transaction — an
             approved-but-unposted adjustment is not a state that exists")
        (is (= (str adjustment-id) (get-in approved [:json "approval" "adjustmentId"]))
            "the decision lands in the same approval table a payment's does")
        (is (nil? (get-in approved [:json "approval" "instructionId"]))
            "and names one subject, not two")))

    (testing "a second approval on a posted adjustment is refused"
      (let [refused (approve! f adjustment-id :actor (:checker-2 f))]
        (is (= 409 (:status refused)))))))

(deftest ac-5-a-two-approval-band-needs-two-different-actors
  (let [f (setup)
        brk (a-break! f)
        proposal (propose! f brk :minor 1000000)
        adjustment-id (get-in proposal [:json "id"])]
    (is (= 2 (get-in proposal [:json "approvalsRequired"])))
    (let [first-approval (approve! f adjustment-id :actor (:checker f))]
      (is (= 201 (:status first-approval)))
      (is (false? (get-in first-approval [:json "posted"]))
          "one of two is not enough, and the adjustment stays proposed")
      (is (= 1 (get-in first-approval [:json "approvalsHeld"]))))
    (testing "and the same actor cannot make up the difference alone"
      (is (= 409 (:status (approve! f adjustment-id :actor (:checker f))))))
    (let [second-approval (approve! f adjustment-id :actor (:checker-2 f))]
      (is (= 201 (:status second-approval)))
      (is (true? (get-in second-approval [:json "posted"])))
      (is (= 2 (get-in second-approval [:json "approvalsHeld"]))))))

(deftest ac-5-an-organisation-with-no-band-in-the-currency-cannot-adjust
  (testing "treating `unconfigured` as `needs nobody` is how a control silently
            weakens in exactly the organisation that has thought least about it"
    (let [f (setup)
          brk (a-break! f)]
      (db/execute! tdb/*pool* ["delete from approval_threshold where organisation_id = ?"
                               (:org f)])
      (let [refused (propose! f brk :minor 99999)]
        (is (= 422 (:status refused)))
        (is (= "no-threshold-configured" (get-in refused [:json "errors" "reason"])))))))

(deftest ac-5-an-adjustment-cannot-be-posted-without-the-suspense-account
  (let [f (setup :open-accounts [["1100-CLIENT-FUNDS" "asset"]
                                 ["1300-IN-TRANSIT" "asset"]
                                 ["2100-CLIENT-PAYABLE" "liability"]])
        brk (a-break! f)
        refused (propose! f brk :minor 99999)]
    (is (= 422 (:status refused)))
    (is (str/includes? (str (get-in refused [:json "errors" "missing"])) "2200-UNAPPLIED")
        "the refusal names the account an operator must open, which is the
         difference between opening one and reading a stack trace")))

(deftest ac-5-an-adjustment-must-say-why-the-books-are-moving
  (let [f (setup)
        brk (a-break! f)]
    (doseq [[label body]
            [["no narrative" {"amount" {"currency" "SGD" "minorUnits" 100}
                              "direction" "credit"}]
             ["blank narrative" {"amount" {"currency" "SGD" "minorUnits" 100}
                                 "direction" "credit" "narrative" "   "}]
             ["zero amount" {"amount" {"currency" "SGD" "minorUnits" 0}
                             "direction" "credit" "narrative" "why"}]]]
      (is (= 422 (:status (call :post (str "/reconciliation-breaks/" (get brk "id")
                                           "/adjustments")
                                :actor (:controller f)
                                :body (assoc body "organisationId" (str (:org f))))))
          label))
    (is (= 400 (:status (call :post (str "/reconciliation-breaks/" (get brk "id")
                                         "/adjustments")
                              :actor (:controller f)
                              :body {"organisationId" (str (:org f))
                                     "amount" {"currency" "SGD" "minorUnits" 100}
                                     "direction" "sideways" "narrative" "why"})))
        "an unknown direction is a request that could not be understood")))

;; ---------------------------------------------------------------------------
;; AC-7 — reconciliation status per account and period
;; ---------------------------------------------------------------------------

(deftest ac-7-status-agrees-with-the-records-underneath-it
  (let [f (setup)
        response (reconcile! f :perturbation "amount-mismatch")
        {:keys [from to]} (period)
        status (call :get "/reconciliation-status"
                     :actor (:controller f)
                     :query (str "organisationId=" (:org f)
                                 "&accountId=" (account-id f "1300-IN-TRANSIT")
                                 "&from=" from "&to=" to))]
    (is (= 200 (:status status)))
    (is (= 1 (get-in status [:json "statements" "received"])))
    (is (= 1 (get-in status [:json "statements" "applied"])))
    (is (= (count (get-in response [:json "lines"]))
           (get-in status [:json "lines" "total"])))
    (is (= (count (matches response)) (get-in status [:json "lines" "matched"])))
    (is (= (- (count (get-in response [:json "lines"])) (count (matches response)))
           (get-in status [:json "lines" "unmatched"])))
    (is (= (frequencies (break-kinds response))
           (into {} (remove (comp zero? val)) (get-in status [:json "breaksByKind"])))
        "the counts and the breaks the ingestion returned are the same facts")
    (is (= {"open" (count (breaks response))}
           (into {} (remove (comp zero? val)) (get-in status [:json "breaksByState"]))))
    (is (some? (get-in status [:json "oldestUnresolvedAgeSeconds"]))
        "and the oldest outstanding break's age, derived at read time")))

(deftest ac-7-every-rule-and-every-kind-appears-with-a-zero-rather-than-absent
  (testing "so a consumer reads `none of this kind` rather than having to
            distinguish that from `this build does not know this kind`"
    (let [f (setup)
          _ (reconcile! f)
          {:keys [from to]} (period)
          status (call :get "/reconciliation-status"
                       :actor (:auditor f)
                       :query (str "organisationId=" (:org f)
                                   "&accountId=" (account-id f "1300-IN-TRANSIT")
                                   "&from=" from "&to=" to))]
      (is (= 6 (count (get-in status [:json "breaksByKind"]))))
      (is (= 4 (count (get-in status [:json "matchesByRule"]))))
      (is (= 3 (count (get-in status [:json "breaksByState"]))))
      (is (nil? (get-in status [:json "oldestUnresolvedAgeSeconds"]))
          "null when nothing is outstanding, which is a different statement from
           zero"))))

(deftest ac-7-a-resolved-break-moves-between-the-state-counts
  (let [f (setup)
        brk (a-break! f)
        _ (is (= 201 (:status (propose! f brk :minor 99999))))
        {:keys [from to]} (period)
        status (call :get "/reconciliation-status"
                     :actor (:controller f)
                     :query (str "organisationId=" (:org f)
                                 "&accountId=" (account-id f "1300-IN-TRANSIT")
                                 "&from=" from "&to=" to))]
    (is (= 1 (get-in status [:json "breaksByState" "resolved"])))
    (is (= 0 (get-in status [:json "breaksByState" "open"])))
    (is (nil? (get-in status [:json "oldestUnresolvedAgeSeconds"])))))

;; ---------------------------------------------------------------------------
;; AC-8 — one audit event per write, and none for work that rolled back
;; ---------------------------------------------------------------------------

(deftest ac-8-every-write-leaves-exactly-one-audit-event
  (let [f (setup)
        response (reconcile! f :perturbation "unknown-line")
        brk (first (breaks response))
        _ (is (= 200 (:status (call :post (str "/reconciliation-breaks/" (get brk "id")
                                               "/assignment")
                                    :actor (:controller f)
                                    :body {"organisationId" (str (:org f))
                                           "assigneeId" (str (:maker f))}))))
        proposal (propose! f brk :minor 100000)
        _ (is (= 201 (:status proposal)))
        _ (is (= 201 (:status (approve! f (get-in proposal [:json "id"])
                                        :actor (:checker f)))))
        actions (audit-actions f)]
    (is (= 1 (get actions "reconciliation-statement.received"))
        "one statement, one arrival — and a replay would add none")
    (is (= 1 (get actions "reconciliation-break.opened")))
    (is (= 1 (get actions "reconciliation-break.assigned")))
    (is (= 1 (get actions "reconciliation-break.resolved"))
        "emitted only in the transaction where the break reaches its terminal
         state; the proposal resolved nothing")
    (is (= 1 (get actions "reconciliation-adjustment.proposed")))
    (is (= 1 (get actions "reconciliation-adjustment.posted"))
        "proposing and posting are two decisions and two terms; collapsing them
         would make a count of postings a count of proposals (F-005's shape)")
    (is (= 3 (get actions "approval.recorded" 0))
        "one per decision — two payment approvals from the settlement this walk
         started with, and the adjustment's. One vocabulary and one table,
         whatever the decision was about")))

(deftest ac-8-a-refused-arrival-still-leaves-its-event-and-a-replay-leaves-none
  (let [f (setup)
        _ (settle! f)
        document (generate! f)
        _ (ingest! f document)
        after-first (get (audit-actions f) "reconciliation-statement.received")
        _ (ingest! f document)]
    (is (= 1 after-first))
    (is (= 1 (get (audit-actions f) "reconciliation-statement.received"))
        "the trail records arrivals, not requests: a replayed delivery creates
         no row and emits nothing")))

(deftest ac-8-work-that-rolled-back-leaves-no-event
  (let [f (setup)
        brk (a-break! f)
        before (audit-actions f)]
    (testing "a refused proposal writes neither the adjustment nor its event"
      (is (= 422 (:status (propose! f brk :minor 99999 :narrative nil))))
      (is (= (get before "reconciliation-adjustment.proposed" 0)
             (get (audit-actions f) "reconciliation-adjustment.proposed" 0))))
    (testing "and a refused approval writes neither the approval nor its event"
      ;; Counted as a delta rather than as an absolute: the payments this walk
      ;; settled were themselves approved, and those are `approval.recorded`
      ;; events too — one vocabulary, one table, one term for a decision
      ;; whatever it was about.
      (let [proposal (propose! f brk :minor 100000)
            recorded-before (get (audit-actions f) "approval.recorded" 0)]
        (is (= 403 (:status (approve! f (get-in proposal [:json "id"])
                                      :actor (:controller f)))))
        (is (= recorded-before (get (audit-actions f) "approval.recorded" 0)))))))

(deftest ac-8-an-evidence-pack-can-be-extracted-for-every-new-subject
  (let [f (setup)
        response (reconcile! f :perturbation "unknown-line")
        brk (first (breaks response))
        proposal (propose! f brk :minor 99999)
        pack (fn [subject-id]
               (call :get (str "/audit/evidence/" subject-id)
                     :actor (:auditor f)
                     :query (str "organisationId=" (:org f))))]
    (doseq [[subject-type subject-id]
            [["reconciliation-statement" (get-in response [:json "id"])]
             ["reconciliation-break" (get brk "id")]
             ["reconciliation-adjustment" (get-in proposal [:json "id"])]]]
      (let [p (pack subject-id)]
        (is (= 200 (:status p)) subject-type)
        (is (= subject-type (get-in p [:json "subjectType"])))
        (is (seq (get-in p [:json "events"])))
        (is (every? #(some? (get % "actorId")) (get-in p [:json "events"]))
            "every event names the actor that caused it")))))

;; ---------------------------------------------------------------------------
;; Authorisation
;; ---------------------------------------------------------------------------

(deftest reconciliation-reads-and-writes-need-the-permissions-they-declare
  (let [f (setup)
        response (reconcile! f :perturbation "unknown-line")
        brk (first (breaks response))]
    (testing "an operator holds neither reconciliation permission"
      (is (= 403 (:status (call :get "/reconciliation-breaks" :actor (:maker f)
                                :query (str "organisationId=" (:org f))))))
      (is (= 403 (:status (ingest! f (generate! f) :actor (:maker f))))))
    (testing "an auditor may read and may not act"
      (is (= 200 (:status (call :get "/reconciliation-breaks" :actor (:auditor f)
                                :query (str "organisationId=" (:org f))))))
      (is (= 403 (:status (call :post (str "/reconciliation-breaks/" (get brk "id")
                                           "/assignment")
                                :actor (:auditor f)
                                :body {"organisationId" (str (:org f))
                                       "assigneeId" (str (:maker f))})))))
    (testing "an approver may read the break they are being asked to decide on"
      (is (= 200 (:status (call :get (str "/reconciliation-breaks/" (get brk "id"))
                                :actor (:checker f)
                                :query (str "organisationId=" (:org f)))))))
    (testing "and every one of them needs an actor at all"
      (is (= 401 (:status (call :get "/reconciliation-breaks"
                                :query (str "organisationId=" (:org f)))))))))

(deftest another-tenants-statement-break-and-adjustment-are-not-visible
  (let [f (setup)
        other (setup)
        response (reconcile! f :perturbation "unknown-line")
        brk (first (breaks response))]
    (is (= 404 (:status (call :get (str "/reconciliation-statements/"
                                        (get-in response [:json "id"]))
                              :actor (:controller other)
                              :query (str "organisationId=" (:org other))))))
    (let [r (call :get (str "/reconciliation-breaks/" (get brk "id"))
                  :actor (:controller other)
                  :query (str "organisationId=" (:org other)))]
      (is (= 404 (:status r)) (str "break id " (pr-str (get brk "id")) " body " (:body r))))
    (is (= 403 (:status (call :get "/reconciliation-breaks"
                              :actor (:controller other)
                              :query (str "organisationId=" (:org f)))))
        "naming another organisation is refused rather than ignored")))

;; ---------------------------------------------------------------------------
;; The break queue
;; ---------------------------------------------------------------------------

(deftest breaks-are-listed-oldest-first-with-their-ages-and-the-filters-work
  (let [f (setup)
        response (reconcile! f :perturbation "duplicate-line")
        listed (call :get "/reconciliation-breaks" :actor (:controller f)
                     :query (str "organisationId=" (:org f)))]
    (is (= 200 (:status listed)))
    (is (= (count (breaks response)) (get-in listed [:json "count"])))
    (is (every? #(some? (get % "ageSeconds")) (get-in listed [:json "reconciliationBreaks"])))
    (is (= (vec (sort (map #(get % "openedAt") (get-in listed [:json "reconciliationBreaks"]))))
           (mapv #(get % "openedAt") (get-in listed [:json "reconciliationBreaks"])))
        "oldest first — a break found in March may have originated in January")
    (is (set/subset? #{"kinds" "states"} (set (keys (:json listed))))
        "the vocabularies travel with the list, so a client renders a filter
         without hard-coding them")
    (is (= 1 (get-in (call :get "/reconciliation-breaks" :actor (:controller f)
                           :query (str "organisationId=" (:org f)
                                       "&kind=duplicate-statement-line"))
                     [:json "count"])))
    (is (= 400 (:status (call :get "/reconciliation-breaks" :actor (:controller f)
                              :query (str "organisationId=" (:org f) "&state=haunted")))))))

(deftest a-break-carries-its-adjustments
  (let [f (setup)
        brk (a-break! f)
        proposal (propose! f brk :minor 100000)
        detail (call :get (str "/reconciliation-breaks/" (get brk "id"))
                     :actor (:controller f)
                     :query (str "organisationId=" (:org f)))]
    (is (= 200 (:status detail)))
    (is (= [(get-in proposal [:json "id"])]
           (mapv #(get % "id") (get-in detail [:json "adjustments"]))))
    (is (= 1 (get-in detail [:json "adjustments" 0 "approvalsRequired"])))))

;; ---------------------------------------------------------------------------
;; The generator endpoint
;; ---------------------------------------------------------------------------

(deftest the-generated-statement-says-what-it-is
  (let [f (setup)
        _ (settle! f)
        document (generate! f)]
    (is (= "SIM-CLOFIN-RECON-STATEMENT" (get document "format")))
    (is (true? (get document "simulated")))
    (is (str/starts-with? (get document "scheme") "SIM-"))
    (is (str/starts-with? (get document "statementReference") "SIM-"))
    (testing "and generating it twice produces the same document, so ingesting
              the second is a replay rather than a second statement"
      (is (= document (generate! f))))))

(deftest the-generator-refuses-an-unknown-perturbation-and-a-real-scheme-name
  (let [f (setup)
        {:keys [from to]} (period)
        query (fn [extra]
                (call :get "/settlement-statements" :actor (:controller f)
                      :query (str "organisationId=" (:org f) "&currency=SGD"
                                  "&from=" from "&to=" to "&" extra)))]
    (is (= 400 (:status (query "scheme=SIM-RTGS&perturbation=make-it-worse"))))
    (is (= 400 (:status (query "scheme=TARGET2"))))
    (is (= 200 (:status (query "scheme=SIM-ACH"))))))

;; ---------------------------------------------------------------------------
;; TASK-010 AC-6 — an adjustment can be refused, and the refusal is evidence
;; ---------------------------------------------------------------------------
;;
;; The gap `008-REQ` recorded as observation N-5: an approver who disagreed
;; simply did not approve, the adjustment sat `proposed` for ever, and nothing
;; recorded that anybody had considered it. ADR-0025 gives the refusal a status,
;; a term and a driver.

(defn- decide!
  "One decision on an adjustment — the same endpoint, whichever it is."
  [f adjustment-id & {:keys [actor decision reason]}]
  (call :post (str "/reconciliation-adjustments/" adjustment-id "/approvals")
        :actor actor
        :body (cond-> {"organisationId" (str (:org f))}
                decision (assoc "decision" decision)
                reason   (assoc "reason" reason))))

(deftest ac-6-an-approver-can-reject-an-adjustment-with-a-reason
  (let [f          (setup)
        brk        (a-break! f)
        before     (get brk "state")
        proposal   (propose! f brk :minor 100000)
        adjustment (get-in proposal [:json "id"])]
    (is (= "proposed" (get-in proposal [:json "status"])))
    (is (= ["post" "reject"] (get-in proposal [:json "permittedTransitions"]))
        "derived from the lifecycle table, so the API cannot advertise a
         decision the state machine would refuse")

    (let [refused (decide! f adjustment :actor (:checker f) :decision "rejected"
                           :reason "The scheme's figure is the right one")]
      (is (= 201 (:status refused)) (str (:body refused)))
      (is (true? (get-in refused [:json "rejected"])))
      (is (false? (get-in refused [:json "posted"]))
          "a refusal moves no money — one refusal ends the adjustment")
      (is (= "rejected" (get-in refused [:json "adjustment" "status"])))
      (is (= [] (get-in refused [:json "adjustment" "permittedTransitions"]))
          "and it is terminal: nothing may follow it")
      (is (nil? (get-in refused [:json "adjustment" "entryId"]))
          "no entry, so recon_adjustment_posting_paired holds as written")

      (testing "the reason is retained on the decision, where a rejected
                payment's is — the same place and the only place"
        (is (= "rejected" (get-in refused [:json "approval" "decision"])))
        (is (= "The scheme's figure is the right one"
               (get-in refused [:json "approval" "reason"])))
        (is (= (str adjustment) (get-in refused [:json "approval" "adjustmentId"])))
        (is (nil? (get-in refused [:json "approval" "instructionId"]))))

      (testing "the break returns to the state it was in — proposing an
                adjustment never moved it, so a refusal returns it to nothing"
        (is (= before (get-in refused [:json "break" "state"])))
        (is (nil? (get-in refused [:json "break" "resolvedAt"])))))

    (testing "no journal entry was posted by any of it"
      (let [entries (call :get "/reconciliation-breaks" :actor (:controller f)
                          :query (str "organisationId=" (:org f) "&state=resolved"))]
        (is (= 0 (get-in entries [:json "count"])))))

    (testing "and the break can be corrected by a DIFFERENT adjustment, which is
              the whole point of recording the refusal rather than leaving the
              proposal to sit"
      (let [second-proposal (propose! f brk :minor 99999 :narrative "Second look")]
        (is (= 201 (:status second-proposal)))
        (is (true? (get-in second-proposal [:json "posted"])))
        (is (= "resolved" (get-in second-proposal [:json "break" "state"])))))))

(deftest ac-6-the-rejector-must-differ-from-the-creator
  (testing "C-01's own comparison, refused first and never waivably: the maker
            never becomes a valid checker for their own correction, and that is
            as true of a refusal as of an approval"
    (let [f          (setup)
          brk        (a-break! f)
          proposal   (propose! f brk :minor 100000)
          adjustment (get-in proposal [:json "id"])
          refused    (decide! f adjustment :actor (:controller f) :decision "rejected"
                              :reason "changed my mind")]
      (is (= 403 (:status refused)))
      (is (= "self-approval" (get-in refused [:json "errors" "reason"])))
      (is (str/includes? (get-in refused [:json "detail"]) "reconciliation adjustment"))
      (testing "and the adjustment is untouched"
        (let [detail (call :get (str "/reconciliation-breaks/" (get brk "id"))
                           :actor (:controller f)
                           :query (str "organisationId=" (:org f)))]
          (is (= "proposed" (get-in detail [:json "adjustments" 0 "status"]))))))))

(deftest ac-6-a-rejection-with-no-reason-is-refused
  (testing "PR-013's rule, applied to the other subject: a refusal whose reason
            is retained is the difference between a trail that explains a
            declined correction and one that merely records that somebody
            declined it. Enforced in the domain, and again by
            approval_rejection_needs_reason at the database"
    (let [f        (setup)
          brk      (a-break! f)
          proposal (propose! f brk :minor 100000)
          refused  (decide! f (get-in proposal [:json "id"])
                            :actor (:checker f) :decision "rejected")]
      (is (= 422 (:status refused)))
      (is (= "is required when rejecting an instruction"
             (get-in refused [:json "errors" "reason"]))))))

(deftest ac-6-a-rejected-adjustment-is-terminal-for-every-decision
  (let [f          (setup)
        brk        (a-break! f)
        proposal   (propose! f brk :minor 1000000)
        adjustment (get-in proposal [:json "id"])]
    (is (= 2 (get-in proposal [:json "approvalsRequired"]))
        "two approvals were needed, and one refusal is still enough to end it")
    (is (= 201 (:status (decide! f adjustment :actor (:checker f)
                                 :decision "rejected" :reason "no"))))
    (testing "a later approval, and a later rejection, are both 409 naming what
              would have been permitted"
      (doseq [[decision reason] [["approved" nil] ["rejected" "also no"]]]
        (let [late (decide! f adjustment :actor (:checker-2 f)
                            :decision decision :reason reason)]
          (is (= 409 (:status late)) decision)
          (is (= "rejected" (get-in late [:json "errors" "adjustment-status"]))
              "the refusal names the status the adjustment is actually in")
          (is (= [] (get-in late [:json "errors" "permitted"]))))))))

(deftest ac-6-an-approval-still-works-and-the-default-decision-is-unchanged
  (testing "the `decision` member defaults to `approved`: every caller written
            before a refusal could be recorded keeps its meaning"
    (let [f          (setup)
          brk        (a-break! f)
          proposal   (propose! f brk :minor 100000)
          adjustment (get-in proposal [:json "id"])
          approved   (decide! f adjustment :actor (:checker f))]
      (is (= 201 (:status approved)))
      (is (true? (get-in approved [:json "posted"])))
      (is (false? (get-in approved [:json "rejected"])))
      (is (= "posted" (get-in approved [:json "adjustment" "status"])))
      (is (= [] (get-in approved [:json "adjustment" "permittedTransitions"])))))
  (testing "and a decision CloFin does not recognise is refused rather than
            defaulted — the safe default would have to be one of the two"
    (let [f          (setup)
          brk        (a-break! f)
          proposal   (propose! f brk :minor 100000)
          bad        (decide! f (get-in proposal [:json "id"]) :actor (:checker f)
                              :decision "maybe")]
      (is (= 400 (:status bad))))))

(deftest ac-6-and-ac-8-a-rejection-leaves-exactly-two-events-and-a-rollback-leaves-none
  (let [f          (setup)
        brk        (a-break! f)
        proposal   (propose! f brk :minor 100000)
        adjustment (get-in proposal [:json "id"])
        before     (audit-actions f)
        _          (is (= 201 (:status (decide! f adjustment :actor (:checker f)
                                                :decision "rejected"
                                                :reason "the scheme was right"))))
        after      (audit-actions f)]
    (is (= 1 (get after "reconciliation-adjustment.rejected"))
        "one event in the transaction where the adjustment became terminal — L-7")
    (is (= (inc (get before "approval.recorded" 0)) (get after "approval.recorded"))
        "and one for the decision itself: a decision being taken and a subject
         becoming terminal are two facts about two subjects")
    (is (nil? (get after "reconciliation-adjustment.posted"))
        "nothing posted, so nothing says it did")
    (is (nil? (get after "reconciliation-break.resolved"))
        "and the break did not resolve")
    (is (nil? (get after "journal-entry.posted"))
        "no entry reached the journal at all")

    (testing "a refused decision writes nothing — the self-approval attempt below
              rolls its whole transaction back"
      (let [snapshot (audit-actions f)
            second-proposal (propose! f brk :minor 100000)]
        (is (= 403 (:status (decide! f (get-in second-proposal [:json "id"])
                                     :actor (:controller f) :decision "rejected"
                                     :reason "mine"))))
        (is (= (get snapshot "reconciliation-adjustment.rejected")
               (get (audit-actions f) "reconciliation-adjustment.rejected")))))))

;; ---------------------------------------------------------------------------
;; TASK-010 AC-3 — a break on a returned original names the retry
;; ---------------------------------------------------------------------------
;;
;; ADR-0019 deferred linked-retry provenance to reconciliation precisely because
;; the workflow that reads it is a break. ADR-0024 built it.

(defn- retry!
  "A retry of a returned instruction, raised through the public path."
  [{:keys [org maker] :as f} original & {:keys [actor amount]}]
  (call :post "/payment-instructions"
        :actor (or actor maker) :idempotency-key (str (random-uuid))
        :body {"organisationId" (str org)
               "debtorAccountId" (account-id f "1100-CLIENT-FUNDS")
               "creditorName" "Pacific Rim Logistics Pte Ltd"
               "creditorAccount" "SG-SYNTH-88012399"
               "amount" {"currency" "SGD" "minorUnits" (or amount 110000)}
               "valueDate" value-date
               "purposeCode" "SUPP"
               "retriesId" (str original)}))

(deftest ac-3-a-break-on-a-returned-original-names-the-retry
  ;; Two returned payments and no settled ones, so every statement line is a
  ;; return and the dropped line is certainly about a returned instruction —
  ;; which is the only kind a retry may be raised against.
  (let [f        (setup)
        settled  (settle! f :settled 0 :returned 2)
        response (ingest! f (generate! f :perturbation "missing-line"))
        found    (breaks response)
        brk      (first found)]
    (is (= 200 (:status response)) (str (:body response)))
    (is (= 1 (count found))
        (str "one dropped line, one break — " (pr-str (mapv #(get % "kind") found))))
    (is (= "expectation-unmatched" (get brk "kind"))
        "CloFin's books record a movement the statement does not report")

    (let [original (get brk "instructionId")]
      (is (some? original)
          "the break names the payment it is about, derived from the ledger
           entry it carries")
      (is (contains? (set (:returned settled)) original)
          "and that payment is one of the two the scheme sent back")
      (is (nil? (get brk "retriedByInstructionIds"))
          "nothing has been retried yet, so the field is absent rather than
           empty — evidence, not decoration")

      (let [retry (retry! f original)
            _     (is (= 201 (:status retry)) (str (:body retry)))
            retry-id (get-in retry [:json "id"])
            detail (call :get (str "/reconciliation-breaks/" (get brk "id"))
                         :actor (:controller f)
                         :query (str "organisationId=" (:org f)))]
        (is (= 200 (:status detail)))
        (is (= original (get-in detail [:json "instructionId"])))
        (is (= [retry-id] (get-in detail [:json "retriedByInstructionIds"]))
            "the break now names the retry — and nothing wrote to the break to
             make that true, which is what `derived` means here")

        (testing "the other break-bearing reads agree, because they share one
                  projection rather than each deriving their own"
          (let [listed (call :get "/reconciliation-breaks" :actor (:controller f)
                             :query (str "organisationId=" (:org f)))]
            (is (= [retry-id]
                   (get-in listed [:json "reconciliationBreaks" 0
                                   "retriedByInstructionIds"])))))
))))

(deftest ac-3-neither-derived-fact-is-a-column-on-the-break
  (testing "a stored answer would be wrong the moment somebody raised a retry,
            for the same reason a stored age is wrong the moment it is written"
    (is (= 0 (db/->long
              (:count (db/query-one
                       tdb/*pool*
                       ["select count(*) as count from information_schema.columns
                          where table_name = 'reconciliation_break'
                            and column_name in ('instruction_id', 'retried_by_ids',
                                                'retries_id')"]))))
        "reconciliation_break has no column for either fact")))
