(ns clofin.api.conformance-test
  "Every operation in the route table, exercised through the public handler and
  checked against the contract it names.

  ## What this closes, and what it does not

  `clofin.contract-test` proves route identity and published-vocabulary
  equality without invoking a handler, so request bodies, required members and
  response schemas were maintained by review. The `ref-2` release audit found
  what that leaves open (finding **2C-010**): every `SettlementBatch` in a list
  response was missing `simulated`, which the schema **requires**; five
  operations answered `400` and one answered `422` without declaring it; and
  the readiness `checks` enum declared a value nothing could emit. Each was
  ordinary behaviour violating the published interface while every route-level
  check stayed green.

  So this exercises each operation at least once and validates the response on
  **three dimensions only**:

  1. the status is declared for that operation;
  2. every `required` member of the schema the response names is present;
  3. every member whose schema is an `enum` holds a declared value.

  Nothing deeper. Types are not checked, formats are not checked,
  `additionalProperties` is not enforced, and a request body is not validated
  against its schema at all. This **narrows** the A-011 debt recorded in
  `docs/COMPLIANCE.md` §4; it does not close it, and §4 still says so.

  Dimensions 2 and 3 descend through objects and arrays, because that is where
  the finding was: `simulated` was present on the batch a caller fetched by id
  and absent from every item of the list, and a check that stopped at the
  top-level schema would have called the list conformant.

  ## Coverage is asserted, not assumed

  `every-operation-in-the-route-table-is-exercised` compares the operations
  this namespace drove with `clofin.routes/routes`, in both directions. An
  operation added without a walk here fails rather than passing unnoticed —
  which is the whole of standing lesson **L-17**, applied to this file itself."
  (:require [clofin.contract-test :as contract]
            [clofin.idempotency :as idempotency]
            [clofin.routes :as routes]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time LocalDate ZoneOffset]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

;; ---------------------------------------------------------------------------
;; Reading the contract
;; ---------------------------------------------------------------------------

(def ^:private spec (delay (contract/load-spec)))

(defn- resolve-ref
  "`#/components/schemas/Money` → the schema it names."
  [ref]
  (get-in @spec (vec (rest (str/split ref #"/")))))

(defn- deref-schema
  [schema]
  (if-let [ref (get schema "$ref")] (deref-schema (resolve-ref ref)) schema))

(defn- operations
  "`{operation-id {status response-object}}`, from the whole contract."
  []
  (into {}
        (for [[_ methods] (get @spec "paths")
              [method operation] methods
              :when (contains? #{"get" "post" "put" "patch" "delete"}
                               (str/lower-case (str method)))]
          [(get operation "operationId") (get operation "responses")])))

(defn- response-schema
  "The JSON schema an operation's `status` response names, or nil when it
  declares no body."
  [responses status]
  (let [response (deref-schema (get responses (str status)))
        content (get response "content")]
    (some (fn [media] (get-in content [media "schema"]))
          ["application/json" "application/problem+json"])))

;; ---------------------------------------------------------------------------
;; The three dimensions
;; ---------------------------------------------------------------------------

(defn- problems
  "Every way `value` disagrees with `schema`, as sentences. Empty means it does
  not — on these three dimensions and no others."
  [schema value path]
  (let [schema (deref-schema schema)
        at (fn [& more] (str/join "." (concat path more)))]
    (cond
      (nil? schema) []

      ;; `allOf` **and** whatever the schema says beside it. This branch used to
      ;; end the `cond`, so a schema carrying `allOf` next to its own `required`
      ;; or `properties` had those siblings silently ignored — the composed
      ;; schema was checked and the sibling constraints were not. Today's
      ;; contract has no such site; a checker whose subject is a contract that
      ;; will grow should not depend on that staying true.
      (seq (get schema "allOf"))
      (concat (mapcat #(problems % value path) (get schema "allOf"))
              (problems (dissoc schema "allOf") value path))

      ;; `oneOf` / `anyOf` reduced to "no problems", which is the vacuous answer
      ;; rather than the absent one: a response validated against a combinator
      ;; this does not implement passed dimensions 2 and 3 by default. There is
      ;; none in the contract, and the day one appears the guard says so rather
      ;; than going quiet (L-6).
      (or (seq (get schema "oneOf")) (seq (get schema "anyOf")))
      [(format (str "%s is declared with a combinator this checker does not "
                    "implement (%s). Implement it or the response is validated "
                    "by nothing on dimensions 2 and 3.")
               (at) (pr-str (vec (sort (filter #{"oneOf" "anyOf"} (keys schema))))))]

      ;; A declared enum, and a value outside it. Skipped for nil: a nullable
      ;; member that is absent is dimension 2's business, not this one.
      (and (seq (get schema "enum")) (some? value))
      (when-not (contains? (set (get schema "enum")) value)
        [(format "%s is %s, which the contract does not declare (%s)"
                 (at) (pr-str value) (pr-str (vec (get schema "enum"))))])

      (map? value)
      (concat
       (for [required (get schema "required")
             :when (not (contains? value required))]
         (format "%s is missing required member %s (present: %s)"
                 (at) (pr-str required) (pr-str (vec (sort (keys value))))))
       (mapcat (fn [[member sub]]
                 (when (contains? value member)
                   (problems sub (get value member) (conj (vec path) member))))
               (get schema "properties"))
       (when-let [extra (get schema "additionalProperties")]
         (when (map? extra)
           (mapcat (fn [[member v]]
                     (when-not (contains? (get schema "properties") member)
                       (problems extra v (conj (vec path) member))))
                   value))))

      (vector? value)
      (when-let [items (get schema "items")]
        (mapcat (fn [i v] (problems items v (conj (vec path) (str "[" i "]"))))
                (range) value))

      :else [])))

;; ---------------------------------------------------------------------------
;; Driving the public handler, recording what each operation answered
;; ---------------------------------------------------------------------------

(def ^:private recorded (atom []))

(defn- request!
  "One request through the fully-wrapped handler. Records nothing."
  [method uri & {:keys [body query actor idempotency-key]}]
  (let [handler (system/handler {:config {:environment :test} :pool tdb/*pool*})
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

(defn- call
  "One request, recorded against the operation it exercises."
  [operation-id method uri & opts]
  (let [response (apply request! method uri opts)]
    (swap! recorded conj {:operation-id operation-id
                          :status (:status response)
                          :body (:json response)
                          :where (str (name method) " " uri)})
    response))

(defn- uuid [s] (java.util.UUID/fromString s))
(def ^:private value-date (str (.plusDays (LocalDate/now ZoneOffset/UTC) 7)))

(defn- period []
  (let [midnight (.toInstant (.atStartOfDay (java.time.LocalDate/now ZoneOffset/UTC)
                                            ZoneOffset/UTC))]
    {:from (str (.minusSeconds midnight 86400))
     :to (str (.plusSeconds midnight 86400))}))

;; ---------------------------------------------------------------------------
;; One walk that reaches every operation
;; ---------------------------------------------------------------------------

(defn- walk!
  "Drive every operation in the route table at least once, plus the modelled
  refusals the audit found undeclared. Returns nothing: what matters is the
  corpus in `recorded`."
  []
  ;; --- health and info ----------------------------------------------------
  (call "getHealth" :get "/healthz")
  (call "getReadiness" :get "/readyz")
  (call "getServiceInfo" :get "/")

  ;; --- organisation and actors -------------------------------------------
  (let [org (uuid (get-in (call "createOrganisation" :post "/organisations"
                                :body {"legalName" "Meridian Freight Holdings Pte Ltd"
                                       "shortName" (str "meridian-" (random-uuid))})
                          [:json "id"]))
        seed (fn [roles limits]
               (tdb/insert-actor! tdb/*pool* {:organisation-id org
                                              :display-name (str/join "+" (map name roles))
                                              :roles roles :limits limits}))
        controller (seed [:controller] {})
        maker (seed [:operator] {})
        checker (seed [:approver] {"SGD" 100000000})
        checker-2 (seed [:approver] {"SGD" 100000000})
        auditor (seed [:auditor] {})
        q (str "organisationId=" org)]
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "SGD"
                                       :from-minor 100000 :approvals-required 1})
    ;; A second band, so that one instruction is still `pendingApproval` after a
    ;; single approval. `withdrawApproval` is only lawful while it is: with one
    ;; band the sole approval meets the threshold and the way back is to amend,
    ;; so the operation could not have reached its `200` at all.
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "SGD"
                                       :from-minor 135000 :approvals-required 2})
    (call "getOrganisation" :get (str "/organisations/" org) :actor controller)

    ;; --- accounts and the journal ----------------------------------------
    (doseq [[code type] [["1100-CLIENT-FUNDS" "asset"]
                         ["1300-IN-TRANSIT" "asset"]
                         ["2100-CLIENT-PAYABLE" "liability"]
                         ["2200-UNAPPLIED" "liability"]]]
      (call "createAccount" :post "/accounts" :actor controller
            :body {"organisationId" (str org) "code" code "name" code
                   "type" type "currency" "SGD"}))
    (let [accounts (get-in (call "listAccounts" :get "/accounts" :actor controller :query q)
                           [:json "accounts"])
          by-code (into {} (map (juxt #(get % "code") #(get % "id"))) accounts)
          funds (get by-code "1100-CLIENT-FUNDS")
          payable (get by-code "2100-CLIENT-PAYABLE")]
      (call "getAccount" :get (str "/accounts/" funds) :actor controller :query q)
      (let [entry (call "postJournalEntry" :post "/journal-entries" :actor controller
                        :idempotency-key (str (random-uuid))
                        :body {"organisationId" (str org)
                               "occurredAt" (:from (period))
                               "narrative" "Opening client funds"
                               "reference" {"type" "opening-balance"
                                            "id" (str (random-uuid))}
                               "lines" [{"accountId" payable "direction" "credit"
                                         "amount" {"currency" "SGD" "minorUnits" 5000000}}
                                        {"accountId" funds "direction" "debit"
                                         "amount" {"currency" "SGD" "minorUnits" 5000000}}]})]
        (call "getJournalEntry" :get (str "/journal-entries/" (get-in entry [:json "id"]))
              :actor controller :query q)
        ;; After the entry, and with the period the contract marks **required**.
        ;; Called without `from`/`to` this answers `400`, and `Statement` — the
        ;; only `$ref` to it in the whole contract is this operation's `200` —
        ;; goes unvalidated along with its eight required members,
        ;; `MovementLine`, and three of the contract's four `allOf` sites. An
        ;; operation driven only to a refusal is an operation whose success
        ;; representation nothing checks.
        (call "getAccountStatement" :get (str "/accounts/" funds "/statement")
              :actor controller
              :query (str q "&from=" (:from (period)) "&to=" (:to (period)))))

      ;; --- payments -------------------------------------------------------
      (let [raise (fn [amount]
                    (get-in (call "createPaymentInstruction" :post "/payment-instructions"
                                  :actor maker :idempotency-key (str (random-uuid))
                                  :body {"organisationId" (str org)
                                         "debtorAccountId" funds
                                         "creditorName" "Pacific Rim Logistics Pte Ltd"
                                         "creditorAccount" "SG-SYNTH-88012340"
                                         "amount" {"currency" "SGD" "minorUnits" amount}
                                         "valueDate" value-date
                                         "purposeCode" "SUPP"})
                            [:json "id"]))
            settled (raise 125000)
            returned (raise 110000)
            cancelled (raise 130000)
            withdrawn (raise 140000)]
        (call "listPaymentInstructions" :get "/payment-instructions" :actor maker :query q)
        (call "getPaymentInstruction" :get (str "/payment-instructions/" settled)
              :actor maker :query q)
        (call "amendPaymentInstruction" :patch (str "/payment-instructions/" settled)
              :actor maker :idempotency-key (str (random-uuid))
              :body {"organisationId" (str org) "creditorName" "Pacific Rim Logistics Ltd"})
        (call "cancelPaymentInstruction" :post (str "/payment-instructions/" cancelled "/cancellation")
              :actor maker :idempotency-key (str (random-uuid))
              :body {"organisationId" (str org) "reason" "Raised against the wrong invoice"})

        (doseq [id [settled returned withdrawn]]
          (call "submitPaymentInstruction" :post (str "/payment-instructions/" id "/submission")
                :actor maker :idempotency-key (str (random-uuid))
                :body {"organisationId" (str org)}))
        (call "getApprovalQueue" :get "/approvals/queue" :actor checker :query q)
        (doseq [id [settled returned]]
          (call "approvePaymentInstruction" :post (str "/payment-instructions/" id "/approvals")
                :actor checker :idempotency-key (str (random-uuid))
                :body {"organisationId" (str org) "decision" "approved"}))
        (let [approval (get-in (call "approvePaymentInstruction" :post
                                     (str "/payment-instructions/" withdrawn "/approvals")
                                     :actor checker-2 :idempotency-key (str (random-uuid))
                                     :body {"organisationId" (str org) "decision" "approved"})
                               ;; The approval id is nested: the 201 body is
                               ;; `{"approval" {…} "paymentInstruction" {…}}`.
                               ;; Read from the top level this is `nil`, the URI
                               ;; becomes `/approvals/`, and the router's own
                               ;; generic 404 gets recorded as `withdrawApproval`
                               ;; having been exercised — the operation counted
                               ;; as covered while its 200 schema, which requires
                               ;; `approval` and `paymentInstruction`, was
                               ;; checked by nothing.
                               [:json "approval" "id"])]
          ;; `DELETE /payment-instructions/:id/approvals/:approvalId`. The walk
          ;; built `/approvals/<id>` — a path no route matches — so the router's
          ;; own `404` was recorded and the operation counted as exercised.
          (call "withdrawApproval" :delete
                (str "/payment-instructions/" withdrawn "/approvals/" approval)
                :actor checker-2 :idempotency-key (str (random-uuid))
                :query q))

        ;; --- settlement ---------------------------------------------------
        (let [batch (get-in (call "createSettlementBatch" :post "/settlement-batches"
                                  :actor controller
                                  :body {"organisationId" (str org) "scheme" "SIM-RTGS"
                                         "currency" "SGD" "valueDate" value-date
                                         "instructionIds" [settled returned]})
                            [:json "id"])]
          (call "listSettlementBatches" :get "/settlement-batches" :actor controller :query q)
          (call "submitSettlementBatch" :post (str "/settlement-batches/" batch "/submit")
                :actor controller :body {"organisationId" (str org)})
          (call "recordSchemeResponse" :post (str "/settlement-batches/" batch "/scheme-responses")
                :actor controller
                :body {"organisationId" (str org) "kind" "settled" "instructionId" settled
                       "reference" (str "SIM-STL-" batch)})
          (call "recordSchemeResponse" :post (str "/settlement-batches/" batch "/scheme-responses")
                :actor controller
                :body {"organisationId" (str org) "kind" "returned" "instructionId" returned
                       "reason" "SIM-RETURN: simulated scheme return"
                       "reference" (str "SIM-RTN-" batch)})
          (call "getSettlementBatch" :get (str "/settlement-batches/" batch)
                :actor controller :query q)
          (call "sweepSettlementTimeouts" :post
                (str "/settlement-batches/" batch "/timeout-sweep")
                :actor controller :body {"organisationId" (str org)})

          ;; --- reconciliation ---------------------------------------------
          (let [{:keys [from to]} (period)
                document (:json (call "generateSimulatedStatement" :get "/settlement-statements"
                                      :actor controller
                                      :query (str q "&scheme=SIM-RTGS&currency=SGD"
                                                  "&from=" from "&to=" to
                                                  "&perturbation=unknown-line")))
                ingested (call "ingestReconciliationStatement" :post "/reconciliation-statements"
                               :actor controller
                               :body (assoc document "organisationId" (str org)))
                statement-id (get-in ingested [:json "id"])
                brk (first (get-in ingested [:json "breaks"]))]
            (call "getReconciliationStatement" :get (str "/reconciliation-statements/" statement-id)
                  :actor controller :query q)
            (call "listReconciliationBreaks" :get "/reconciliation-breaks" :actor controller :query q)
            (call "getReconciliationBreak" :get (str "/reconciliation-breaks/" (get brk "id"))
                  :actor controller :query q)
            (call "assignReconciliationBreak" :post
                  (str "/reconciliation-breaks/" (get brk "id") "/assignment")
                  :actor controller
                  :body {"organisationId" (str org) "assigneeId" (str maker)})
            (let [adjustment (get-in (call "proposeReconciliationAdjustment" :post
                                           (str "/reconciliation-breaks/" (get brk "id")
                                                "/adjustments")
                                           :actor controller
                                           :body {"organisationId" (str org)
                                                  "amount" {"currency" "SGD" "minorUnits" 100000}
                                                  "direction" "credit"
                                                  "narrative" "Agreeing with the scheme"})
                                     [:json "id"])]
              (call "getReconciliationAdjustment" :get
                    (str "/reconciliation-adjustments/" adjustment)
                    :actor controller :query q)
              (call "approveReconciliationAdjustment" :post
                    (str "/reconciliation-adjustments/" adjustment "/approvals")
                    :actor checker :body {"organisationId" (str org)}))
            (call "getReconciliationStatus" :get "/reconciliation-status" :actor controller
                  :query (str q "&accountId=" (get by-code "1300-IN-TRANSIT")
                              "&from=" from "&to=" to))

            ;; --- audit ----------------------------------------------------
            (call "listAuditEvents" :get "/audit/events" :actor auditor :query q)
            (call "getEvidencePack" :get (str "/audit/evidence/" settled)
                  :actor auditor :query q)

            ;; --- the modelled refusals the audit found undeclared ----------
            (call "getReconciliationBreak" :get "/reconciliation-breaks/not-a-uuid"
                  :actor controller :query q)
            (call "getReconciliationStatement" :get "/reconciliation-statements/not-a-uuid"
                  :actor controller :query q)
            (call "getSettlementBatch" :get "/settlement-batches/not-a-uuid"
                  :actor controller :query q)
            (call "listSettlementBatches" :get "/settlement-batches" :actor controller
                  :query (str q "&status=nonsense"))
            (call "submitSettlementBatch" :post "/settlement-batches/not-a-uuid/submit"
                  :actor controller :body {"organisationId" (str org)})
            (call "recordSchemeResponse" :post (str "/settlement-batches/" batch "/scheme-responses")
                  :actor controller
                  :body {"organisationId" (str org) "kind" "returned" "instructionId" settled
                         "reference" (str "SIM-RTN-NO-REASON-" batch)}))))))
  nil)

(def ^:private walked
  "The walk, run exactly once however many tests deref it.

  A `delay` rather than \"run it if `recorded` is empty\": the sweep in
  `ac-15-…` builds an organisation of its own, and test order is not defined,
  so an emptiness check let that setup masquerade as the corpus and every
  operation read as unexercised."
  (delay (reset! recorded []) (walk!) @recorded))

(defn- corpus [] @walked)

;; ---------------------------------------------------------------------------
;; The assertions
;; ---------------------------------------------------------------------------

(deftest every-operation-in-the-route-table-is-exercised
  (let [driven (set (map :operation-id (corpus)))
        declared (set (map :operation-id (routes/routes {:config {:environment :test}
                                                         :pool ::stub})))]
    (is (seq driven) "the walk recorded nothing at all")
    (is (empty? (set/difference declared driven))
        (str "operations in the route table that this namespace never exercises: "
             (pr-str (vec (sort (set/difference declared driven))))
             ". An operation added without a walk here is an operation whose "
             "responses nothing validates (L-17)."))
    (is (empty? (set/difference driven declared))
        (str "operations exercised here that the route table does not have: "
             (pr-str (vec (sort (set/difference driven declared))))))))

(deftest every-response-status-is-declared-for-its-operation
  (testing "dimension 1. Five operations answered 400 and one answered 422
            without declaring it (2C-010): a modelled refusal absent from the
            contract is a refusal a generated client cannot handle"
    (let [ops (operations)]
      (doseq [{:keys [operation-id status where]} (corpus)]
        (let [declared (set (keys (get ops operation-id)))]
          (is (contains? declared (str status))
              (str where " (" operation-id ") answered " status
                   ", and the contract declares " (pr-str (vec (sort declared))))))))))

(deftest every-response-satisfies-the-schema-it-names
  (testing "dimensions 2 and 3, descending through objects and arrays. Every
            `SettlementBatch` in a list response was missing `simulated`, which
            the schema requires — a check that stopped at the top-level schema
            would have called that list conformant (2C-010)"
    (let [ops (operations)]
      (doseq [{:keys [operation-id status body where]} (corpus)
              :let [schema (response-schema (get ops operation-id) status)]
              :when (and schema (some? body))]
        (let [found (remove nil? (problems schema body [operation-id]))]
          (is (empty? found)
              (str where " (" operation-id " → " status ") disagrees with its "
                   "contract:\n  - " (str/join "\n  - " found))))))))

(deftest the-walk-reaches-both-the-happy-path-and-the-modelled-refusals
  (testing "a conformance suite that only ever saw 2xx would declare an
            undeclared 400 conformant by never provoking one"
    (let [statuses (group-by :operation-id (corpus))]
      (doseq [[operation-id expected]
              {"getReconciliationBreak" 400
               "getReconciliationStatement" 400
               "getSettlementBatch" 400
               "listSettlementBatches" 400
               "submitSettlementBatch" 400
               "recordSchemeResponse" 422}]
        (is (some #(= expected (:status %)) (get statuses operation-id))
            (str operation-id " must be driven to " expected
                 " — the audit found it emitting one undeclared; observed "
                 (pr-str (mapv :status (get statuses operation-id)))))))))

(deftest every-operation-the-contract-gives-a-success-reaches-one
  (testing "the other half of the coverage claim. `every-operation-in-the-route-
            table-is-exercised` counts an operation as driven the moment *any*
            response is recorded for it — so an operation the walk only ever
            provokes into a refusal, or one whose URI the walk builds out of a
            nil id and which the router therefore answers `404` without ever
            reaching the handler, passes it while its success representation is
            validated by nothing. That is the L-17 shape — coverage complete
            along the operation-id dimension and absent along the status
            dimension — inside the guard written to close it, and it hid two:
            `withdrawApproval` (never reached its handler) and
            `getAccountStatement` (only ever a `400`, and the sole `$ref` to
            `Statement` in the contract)"
    (let [declared (operations)
          by-id (group-by :operation-id (corpus))
          success? (fn [status] (<= 200 status 299))
          ;; From the contract, not from a list here: an operation that gains a
          ;; success response is covered by this the day it does.
          expected (into (sorted-set)
                         (for [[operation-id responses] declared
                               :when (contains? by-id operation-id)
                               :when (some #(re-matches #"2\d\d" (str %)) (keys responses))]
                           operation-id))
          missing (into (sorted-set)
                        (remove (fn [operation-id]
                                  (some (comp success? :status) (by-id operation-id)))
                                expected))]
      (is (seq expected) "no operation was matched against the contract at all")
      (is (empty? missing)
          (str "the contract declares a 2xx for these operations and the walk "
               "never drove one: "
               (pr-str (into {} (for [operation-id missing]
                                  [operation-id (mapv :status (by-id operation-id))])))
               ". An operation seen only refusing is an operation whose success "
               "schema this namespace does not check.")))))

(deftest the-schema-checker-has-no-silent-gaps
  (testing "the three dimensions are only as wide as `problems` and
            `response-schema` reach, and both used to widen a green build
            rather than narrow it. Asserted directly, because a hole that no
            schema in today's contract exercises is a hole the contract's next
            author walks into"
    (testing "`allOf` beside its own `required` — the siblings used to be
              dropped with the composed schema checked and nothing else"
      (is (seq (#'problems {"allOf" [{"type" "object"}]
                            "required" ["thisWasIgnored"]}
                           {"currency" "SGD"} ["m"]))
          "a sibling `required` next to `allOf` must still be checked"))

    (testing "a combinator this checker does not implement is named, not
              silently passed"
      (doseq [combinator ["oneOf" "anyOf"]]
        (let [found (#'problems {combinator [{"required" ["k"]}]} {} ["o"])]
          (is (seq found) (str combinator " must not reduce to no problems"))
          (is (str/includes? (str (first found)) "does not implement") (str found)))))

    (testing "and every response body the contract declares is in a media type
              `response-schema` reads — otherwise it returns nil and dimensions
              2 and 3 are skipped for that response while dimension 1 passes"
      (let [known #{"application/json" "application/problem+json"}
            unread (into (sorted-set)
                         (for [[_ methods] (get @spec "paths")
                               [_ operation] methods
                               :when (map? operation)
                               [status response] (get operation "responses")
                               :let [content (get (deref-schema response) "content")]
                               :when (seq content)
                               :when (empty? (filter known (keys content)))]
                           (str (get operation "operationId") " " status
                                " " (pr-str (vec (keys content))))))]
        (is (empty? unread)
            (str "these responses declare a body this checker cannot read: "
                 (pr-str (vec unread))))))))

(deftest the-check-is-bounded-and-says-so
  (testing "this narrows the A-011 debt; it does not close it, and COMPLIANCE
            §4 must record what is still open.

            What stood here asserted that the heading string appeared somewhere
            in the file — which it did before this namespace existed and would
            go on doing however stale the row beneath it became. That is the
            same false-protection shape as **2C-003**, in a guard this batch
            added: a check whose subject is a row, testing only that a heading
            exists. It did not notice that the row still said `clofin.contract-
            test` *does not invoke a handler, so required members and response
            schemas are maintained by review*, which this namespace had just
            made false — COMPLIANCE understating what exists is the same L-15
            failure as overstating it"
    (let [row (->> (str/split-lines (slurp "docs/COMPLIANCE.md"))
                   (filter #(str/includes? % "Deep OpenAPI/handler contract validation"))
                   first)]
      (is (some? row) "the debt row must still be in COMPLIANCE §4")
      (testing "and it must name what now exists"
        (is (str/includes? row "clofin.api.conformance-test")
            (str "the row must name the namespace that narrowed the debt — " row)))
      (testing "and what is still open, or a reader learns a closed debt from a
                row that stopped mentioning its remainder (L-14)"
        (doseq [remainder ["request bodies" "oneOf"]]
          (is (str/includes? row remainder)
              (str "the row must still record " (pr-str remainder) " — " row)))))))

;; ---------------------------------------------------------------------------
;; AC-15 (2B-009) — the six that require a key, and the eleven that do not
;;
;; `clofin.idempotency/read-key`'s docstring and its own `400` said the header
;; was mandatory on every mutating endpoint. Independent route and contract
;; inventories contain seventeen mutations and only six read the header, so a
;; maintainer could infer fail-closed retry protection for eleven writes that
;; offer no caller-key contract at all.
;;
;; The sweep below calls every mutating route with a valid body and **no**
;; `Idempotency-Key`, and compares what answers the key-required `400` with
;; `clofin.idempotency/protected-operations` and with the operations the
;; contract marks — three sources, all three compared, rather than a sentence.
;; ---------------------------------------------------------------------------

(defn- mutating-routes
  []
  (filter (comp #{:post :patch :delete :put} :method)
          (routes/routes {:config {:environment :test} :pool ::stub})))

(defn- operations-the-contract-marks
  "Every operation whose parameters reference `#/components/parameters/IdempotencyKey`."
  []
  (into (sorted-set)
        (for [[_ methods] (get @spec "paths")
              [_ operation] methods
              :when (map? operation)
              :when (some #(= "#/components/parameters/IdempotencyKey" (get % "$ref"))
                          (get operation "parameters"))]
          (get operation "operationId"))))

(deftest ac-15-the-key-is-required-by-exactly-the-six-operations-that-say-so
  (let [declared (operations-the-contract-marks)
        in-code (into (sorted-set) idempotency/protected-operations)]
    (testing "the contract and the code name the same six, both directions"
      (is (seq declared) "the contract marks no operation at all")
      (is (= declared in-code)
          (str "api/openapi.yaml marks " (pr-str (vec declared))
               " and clofin.idempotency/protected-operations holds "
               (pr-str (vec in-code)))))

    (testing "and there really are seventeen mutations, so \"six of seventeen\"
              is a counted claim rather than a remembered one"
      (is (= 17 (count (mutating-routes)))
          (str "the route table has " (count (mutating-routes)) " mutating routes; "
               "if that is right, every sentence saying seventeen needs revisiting")))))

(def ^:private sweep-bodies
  "The body each mutation is swept with.

  Good enough to reach the idempotency check, which is not the same as good
  enough to succeed. `approvePaymentInstruction` validates `decision` before it
  reads the key, so a body without one answers a `400` about the decision and
  the sweep would have read that as \"does not require a key\" — a false
  negative in the guard, which is the class of thing this whole remediation is
  about."
  {"approvePaymentInstruction" {"decision" "approved"}
   "recordSchemeResponse" {"kind" "ack"}})

(deftest ac-15-a-mutation-with-no-key-is-refused-by-the-six-and-by-no-others
  (testing "the sweep. What answers the key-required 400 with no
            Idempotency-Key must be exactly the protected set — and for every
            other mutation the header must make no difference at all, which is
            what \"does not take one\" means (2B-009)"
    (let [org (uuid (get-in (request! :post "/organisations"
                                      :body {"legalName" "Meridian Freight Holdings Pte Ltd"
                                             "shortName" (str "meridian-" (random-uuid))})
                            [:json "id"]))
          actor (tdb/insert-actor! tdb/*pool* {:organisation-id org
                                               :display-name "controller"
                                               :roles [:controller :operator :approver]
                                               :limits {"SGD" 100000000}})
          some-uuid (str (random-uuid))
          sweep (fn [{:keys [method path operation-id]} key]
                  (let [uri (-> path
                                (str/replace ":approvalId" some-uuid)
                                (str/replace ":id" some-uuid))
                        response (apply request! method
                                        (str uri "?organisationId=" org)
                                        (cond-> [:actor actor
                                                 :body (merge {"organisationId" (str org)}
                                                              (get sweep-bodies operation-id))]
                                          key (conj :idempotency-key key)))]
                    {:status (:status response)
                     :key-required? (boolean
                                     (and (= 400 (:status response))
                                          (str/includes?
                                           (str (get-in response [:json "detail"]))
                                           "Idempotency-Key")))}))
          swept (into {} (for [route (mutating-routes)]
                           [(:operation-id route)
                            {:without (sweep route nil)
                             :with (sweep route (str (random-uuid)))}]))
          refused (into (sorted-set)
                        (keep (fn [[op {:keys [without]}]]
                                (when (:key-required? without) op)))
                        swept)
          protected (into (sorted-set) idempotency/protected-operations)]

      (is (= protected refused)
          (str "with no Idempotency-Key these answered the key-required 400: "
               (pr-str (vec refused)) "; protected-operations holds "
               (pr-str (vec protected)) ". Observed: " (pr-str swept)))

      (is (= 11 (count (remove (comp :key-required? :without val) swept)))
          (str "eleven of the seventeen mutations must not require the header; "
               (count (remove (comp :key-required? :without val) swept))
               " did not: " (pr-str (vec (sort (map key (remove (comp :key-required? :without val)
                                                                swept)))))))

      (testing "and for those eleven the header changes nothing — which is what
                \"does not take one\" has to mean, rather than \"was not
                reached in this fixture\""
        (doseq [[op {:keys [without with]}] swept
                :when (not (contains? protected op))]
          (is (= (:status without) (:status with))
              (str op " answered " (:status without) " without an Idempotency-Key and "
                   (:status with) " with one — a mutation that is not protected must "
                   "be indifferent to the header"))
          (is (not (:key-required? with)) op)))

      (testing "and for the six it is the header that is missing, not the body"
        (doseq [op protected]
          (is (:key-required? (:without (get swept op)))
              (str op " must answer the key-required 400 with no header"))
          (is (not (:key-required? (:with (get swept op))))
              (str op " must stop answering it once a header is supplied — if it "
                   "does not, this sweep is reading some other 400"))))

      (testing "and the refusal names the six rather than claiming every mutation"
        (let [detail (-> (request! :post "/payment-instructions"
                                   :actor actor
                                   :body {"organisationId" (str org)})
                         (get-in [:json "detail"]))]
          (is (str/includes? (str detail) "six payment and approval mutations")
              (str "the 400 must not say the header is required on every mutating "
                   "request — " (pr-str detail)))
          (is (not (str/includes? (str detail) "every mutating"))
              (str "the superseded claim must not survive — " (pr-str detail))))))))
