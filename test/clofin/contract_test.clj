(ns clofin.contract-test
  "The API contract and the service must not drift apart.

  `api/openapi.yaml` is the interface specification, not generated
  documentation. This test asserts the two agree in both directions: every
  declared operation is routable, and every route is declared. A route added
  without a contract change fails here, which is the point."
  (:require [clofin.api.health :as health]
            [clofin.audit :as audit]
            [clofin.build-info :as build-info]
            [clofin.money :as money]
            [clofin.payments.instruction :as instruction]
            [clofin.routes :as routes]
            [clofin.settlement.response :as response]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [org.yaml.snakeyaml Yaml]))

(def ^:private http-methods #{"get" "post" "put" "patch" "delete" "head" "options"})

(defn- load-spec []
  (let [file (io/file "api/openapi.yaml")]
    (assert (.exists file) "api/openapi.yaml must exist — it is the interface specification")
    (with-open [r (io/reader file)]
      (.load (Yaml.) r))))

(defn- spec-operations
  "`{[:get \"/healthz\"] {:operation-id \"getHealth\" :summary \"...\"}}`"
  [spec]
  (into {}
        (for [[path methods] (get spec "paths")
              [method operation] methods
              :when (contains? http-methods (str/lower-case method))]
          [[(keyword (str/lower-case method)) path]
           {:operation-id (get operation "operationId")
            :summary (get operation "summary")}])))

(defn- ->spec-path
  "Route paths use `:id`; OpenAPI uses `{id}`."
  [path]
  (str/replace path #":([^/]+)" "{$1}"))

(def ^:private route-table
  ;; Handlers are never invoked here, so a stub system is enough to build the
  ;; table. That the table can be built without a database is itself part of
  ;; the design being asserted.
  (routes/routes {:config {:environment :test} :pool ::stub}))

(deftest every-route-is-declared-in-the-contract
  (let [declared (set (keys (spec-operations (load-spec))))
        actual   (set (map (juxt :method (comp ->spec-path :path)) route-table))]
    (testing "no undocumented endpoint reaches production"
      (is (empty? (set/difference actual declared))
          (str "Routes missing from api/openapi.yaml: " (pr-str (set/difference actual declared)))))

    (testing "no documented endpoint is unimplemented"
      (is (empty? (set/difference declared actual))
          (str "Operations declared in api/openapi.yaml with no route: "
               (pr-str (set/difference declared actual)))))))

(deftest operation-ids-match
  (let [declared (spec-operations (load-spec))]
    (doseq [{:keys [method path operation-id]} route-table]
      (testing (str method " " path)
        (is (= operation-id (:operation-id (get declared [method (->spec-path path)])))
            "operationId is the join key between the contract and the route table")))))

(deftest every-route-carries-an-operation-id-and-a-summary
  (doseq [{:keys [method path operation-id summary]} route-table]
    (testing (str method " " path)
      (is (not (str/blank? operation-id)))
      (is (not (str/blank? summary))))))

(deftest the-contract-states-its-scope
  (let [description (get-in (load-spec) ["info" "description"])
        lowered (str/lower-case description)]
    (testing "a reader of the API contract alone still learns what CloFin is not"
      (is (str/includes? lowered "synthetic"))
      (is (str/includes? lowered "central bank"))
      (is (str/includes? lowered "regulatory")))))

;; ---------------------------------------------------------------------------
;; Self-identification (ADR-0027)
;; ---------------------------------------------------------------------------

(deftest service-info-declares-exactly-the-fields-it-returns
  (let [schema (get-in (load-spec) ["components" "schemas" "ServiceInfo"])
        declared (set (keys (get schema "properties")))
        returned (set (keys (:body ((health/info {:environment :test}) {}))))]
    (is (= declared returned)
        (str "ServiceInfo declares " (pr-str (vec (sort declared)))
             " and GET / returns " (pr-str (vec (sort returned)))))
    (testing "sourceCommit is required, because it is always answered"
      (is (contains? (set (get schema "required")) "sourceCommit")))))

(deftest the-contract-says-source-commit-is-self-reported-rather-than-attested
  (testing "L-14: the sentence beside the field may not claim more than the field is"
    (let [description (str/lower-case
                       (get-in (load-spec)
                               ["components" "schemas" "ServiceInfo"
                                "properties" "sourceCommit" "description"]))]
      (is (str/includes? description "self-reported"))
      (is (str/includes? description "not attested"))
      (is (not (str/includes? description "proves"))
          "nothing about this field proves anything, so the word must not appear as a claim"))))

(deftest the-declared-pattern-admits-a-commit-and-unknown-and-nothing-in-between
  (let [pattern (re-pattern
                 (get-in (load-spec)
                         ["components" "schemas" "ServiceInfo" "properties" "sourceCommit"
                          "pattern"]))
        matches? #(boolean (re-find pattern %))]
    (testing "the two forms the service can produce"
      (is (matches? "f10974c7762eb9e095694fcfb3aaa72c0bee4bdf"))
      (is (matches? build-info/unknown))
      (is (matches? (get-in ((health/info {:environment :test}) {}) [:body "sourceCommit"]))))
    (testing "and the form 011-REQ's objection O-1 is about"
      (doseq [wrong ["main" "HEAD" "ref-1" "refs/heads/main" "f10974c" ""]]
        (is (not (matches? wrong))
            (str (pr-str wrong) " is contract-valid, which would let a branch name be "
                 "published under the label \"commit\""))))))

(deftest the-contract-describes-browser-access-as-closed-and-not-as-a-control
  ;; Flattened before matching: the description is hard-wrapped YAML, so a
  ;; sentence is split across lines at whatever column it reached, and matching
  ;; raw text would assert about the wrapping rather than the words.
  (let [description (-> (get-in (load-spec) ["info" "description"])
                        str/lower-case
                        (str/replace #"\s+" " "))]
    (testing "a reader of the contract alone learns the default and the limit"
      (is (str/includes? description "clofin_cors_allowed_origins"))
      (is (str/includes? description "sends no cors header of any kind"))
      (is (str/includes? description "there is no wildcard setting"))
      (is (str/includes? description "cors is not an access control")))))

(defn- subject-type-enums
  "Every `subjectType` enum anywhere in the spec's schemas, as `{schema-name enum}`.

  **Discovered, not listed.** The first version of this test named
  `AuditEvent.properties.subjectType` and asserted that one copy — and passed
  green while `EvidencePack.properties.subjectType`, a second copy in the same
  file, still declared the two subject types that existed before TASK-005. The
  endpoint returned `account` and the contract said `account` was impossible.

  A drift guard that checks the copy its author happened to look at is the same
  defect it exists to catch, so this finds them all: a third copy added later is
  covered without anyone remembering to extend this list."
  [spec]
  (into {}
        (keep (fn [[schema-name schema]]
                (when-let [enum (get-in schema ["properties" "subjectType" "enum"])]
                  [schema-name enum])))
        (get-in spec ["components" "schemas"])))

(deftest the-audit-vocabulary-in-the-contract-is-the-one-the-service-enforces
  (testing "`clofin.audit` holds a closed vocabulary and the contract publishes it —
            several copies of two lists, so the copies are asserted equal rather than trusted"
    (let [spec (load-spec)
          by-schema (subject-type-enums spec)]
      (is (= (set audit/actions)
             (set (get-in spec ["components" "schemas" "AuditAction" "enum"])))
          "an action the service can write and the contract does not declare is an event a
           caller cannot filter for; one the contract declares and the service refuses is a
           400 the caller was invited to make")

      (is (seq by-schema) "the contract must declare the subject vocabulary somewhere")
      (is (= #{"AuditEvent" "EvidencePack"} (set (keys by-schema)))
          "a schema gained or lost a `subjectType` — check it is covered below rather than
           letting this test quietly stop guarding it")

      (doseq [[schema-name enum] by-schema]
        (is (= (set audit/subject-types) (set enum))
            (str "components.schemas." schema-name ".properties.subjectType declares "
                 (pr-str (vec (sort enum))) " — the service can emit "
                 (pr-str (vec audit/subject-types))))))))

(deftest money-is-specified-as-integer-minor-units
  (let [money (get-in (load-spec) ["components" "schemas" "Money"])]
    (testing "the wire contract cannot be read as accepting a floating-point amount"
      (is (= "integer" (get-in money ["properties" "minorUnits" "type"])))
      (is (= "int64" (get-in money ["properties" "minorUnits" "format"])))
      (is (= #{"currency" "minorUnits"} (set (get money "required")))))))

;; ---------------------------------------------------------------------------
;; A-019 — the supported currencies, not a three-letter shape
;; ---------------------------------------------------------------------------

(deftest a-019-the-contract-publishes-the-currencies-the-service-supports
  (let [spec (load-spec)
        declared (set (get-in spec ["components" "schemas" "CurrencyCode" "enum"]))]
    (testing "`^[A-Z]{3}$` described a system more permissive than the one that answers:
              `XYZ` was contract-valid and rejected by `money/of` or a currency foreign key"
      (is (= (set (keys money/currencies)) declared)
          (str "CurrencyCode declares " (pr-str (vec (sort declared)))
               "; clofin.money supports " (pr-str (vec (sort (keys money/currencies)))))))

    (testing "and no currency field is left describing a shape instead"
      ;; Discovered, not listed — the `subjectType` lesson (L-6) applied to a
      ;; second enum with several copies. A currency property added later that
      ;; reintroduces the pattern fails here without anyone extending a list.
      (doseq [[schema-name schema] (get-in spec ["components" "schemas"])
              :let [currency (get-in schema ["properties" "currency"])]
              :when (map? currency)]
        (is (= "#/components/schemas/CurrencyCode" (get currency "$ref"))
            (str "components.schemas." schema-name ".properties.currency is "
                 (pr-str currency) " rather than a CurrencyCode reference"))))))

;; ---------------------------------------------------------------------------
;; A-018 — purpose codes, in all three places that state the set
;; ---------------------------------------------------------------------------

(deftest a-018-the-contract-publishes-the-purpose-codes-the-service-accepts
  (let [declared (set (get-in (load-spec) ["components" "schemas" "PurposeCode" "enum"]))]
    (is (= (set (keys instruction/purpose-codes)) declared)
        "a code the contract offers and the domain refuses is a 422 the caller was
         invited to make; one the domain accepts and the contract omits is
         unreachable through any generated client")
    (testing "the third statement of the set is the check constraint, compared with
              the live catalogue in `clofin.db.vocabulary-test`"
      (is (seq declared)))))

;; ---------------------------------------------------------------------------
;; A-016 — the refusal-reason vocabulary
;; ---------------------------------------------------------------------------

(deftest a-016-the-contract-publishes-every-scheme-response-refusal-reason
  (let [spec (load-spec)
        caller-facing (set (get-in spec ["components" "schemas"
                                         "SchemeResponseRefusalReason" "enum"]))
        stored        (set (get-in spec ["components" "schemas"
                                         "StoredSchemeResponseRefusalReason" "enum"]))]
    (is (= (set (keys response/refusal-reasons)) caller-facing)
        "`replay-key-conflict` reached callers under errors.dispositionReason while
         appearing in no published enum at all — a code an integrator could receive
         and could not have known to handle")
    (is (= (set response/stored-refusal-reasons) stored)
        "and the narrower stored set is published as its own schema rather than
         being conflated with the one above")
    (is (= "#/components/schemas/StoredSchemeResponseRefusalReason"
           (get-in spec ["components" "schemas" "SchemeResponseRecord"
                         "properties" "dispositionReason" "$ref"]))
        "SchemeResponseRecord.dispositionReason must reference the stored set rather
         than repeating it — a second copy is the drift L-6 names")))

;; ---------------------------------------------------------------------------
;; A-012 — the actor boundary is declared where it is enforced
;; ---------------------------------------------------------------------------

(defn- actor-protected-operations
  "Every route whose handler resolves a principal, discovered from the source
  rather than listed.

  A handler namespace that requires `clofin.api.principal` authenticates and
  authorises; one that does not is a public route and must be one deliberately.
  Reading the source is crude and is the point — it cannot be kept in step by
  hand, so it cannot fall behind the way a list would. `GET /organisations/:id`
  was outside every list of \"the authenticated routes\" for two audits."
  []
  (let [protected? (fn [handler-ns]
                     (let [file (io/file (str "src/clofin/api/" handler-ns ".clj"))]
                       (and (.exists file)
                            (str/includes? (slurp file) "clofin.api.principal"))))]
    (into #{}
          (comp (remove #(contains? #{"getHealth" "getReadiness" "getServiceInfo"
                                      ;; The documented unauthenticated bootstrap:
                                      ;; no actor can exist before the organisation
                                      ;; that holds one (ADR-0017).
                                      "createOrganisation"}
                                    (:operation-id %)))
                (map :operation-id))
          (filter (fn [{:keys [path]}]
                    (protected? (cond
                                  (str/starts-with? path "/organisations") "organisations"
                                  (str/starts-with? path "/accounts")      "accounts"
                                  (str/starts-with? path "/journal-entries") "entries"
                                  (str/starts-with? path "/payment-instructions/")
                                  (if (str/includes? path "approvals") "approvals" "payments")
                                  (str/starts-with? path "/payment-instructions") "payments"
                                  (str/starts-with? path "/approvals")     "approvals"
                                  (str/starts-with? path "/settlement-")   "settlement"
                                  (str/starts-with? path "/reconciliation-") "reconciliation"
                                  (str/starts-with? path "/audit")         "audit"
                                  :else "health")))
                  route-table))))

(deftest a-012-every-actor-protected-operation-declares-the-actor-header
  (testing "12 operations authenticated and authorised before doing any work, and
            declared with no way for a client to supply a principal (A-012)"
    (let [spec (load-spec)
          protected (actor-protected-operations)
          by-op (into {} (for [[path methods] (get spec "paths")
                               [method operation] methods
                               :when (contains? http-methods (str/lower-case method))]
                           [(get operation "operationId") operation]))]
      ;; Non-vacuity. A discovery that quietly found nothing would pass every
      ;; assertion below and prove exactly nothing, which is the failure mode
      ;; this whole file exists to guard against.
      (is (= (- (count route-table) 4) (count protected))
          (str "every route but the three health/info routes and the "
               "unauthenticated organisation bootstrap should be actor-protected; "
               "discovered " (pr-str (vec (sort protected)))))

      (doseq [operation-id protected]
        (let [operation (get by-op operation-id)
              parameters (get operation "parameters")
              responses  (set (keys (get operation "responses")))]
          (is (some #(= "#/components/parameters/ActorId" (get % "$ref")) parameters)
              (str operation-id " authenticates and does not declare ActorId"))
          (is (contains? responses "401")
              (str operation-id " can answer 401 and does not declare it"))
          (is (contains? responses "403")
              (str operation-id " can answer 403 and does not declare it")))))))

(deftest a-012-a-conformant-create-payment-request-can-be-satisfied
  (let [schema (get-in (load-spec) ["components" "schemas" "CreatePaymentInstructionRequest"])]
    (testing "`createdBy` was required here and refused by the handler — no request
              could satisfy both, which is a published contract with no valid instance"
      (is (not (contains? (set (get schema "required")) "createdBy")))
      (is (not (contains? (set (keys (get schema "properties"))) "createdBy"))
          "and it is gone from the properties too: `additionalProperties: false`
           plus a declared member reads as an invitation to send it"))))

(deftest a-012-the-principal-supplies-the-organisation-where-the-handler-says-it-does
  (testing "a member the handler derives from the actor must not be `required`"
    (doseq [schema-name ["CreateAccountRequest" "PostJournalEntryRequest"
                         "AmendPaymentInstructionRequest" "CreatePaymentInstructionRequest"]]
      (let [schema (get-in (load-spec) ["components" "schemas" schema-name])]
        (is (not (contains? (set (get schema "required")) "organisationId"))
            (str schema-name " requires organisationId, which the principal supplies"))
        (is (contains? (set (keys (get schema "properties"))) "organisationId")
            (str schema-name " must still accept it — it is verified, not ignored"))))))
