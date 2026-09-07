(ns clofin.contract-test
  "The API contract and the service must not drift apart.

  `api/openapi.yaml` is the interface specification, not generated
  documentation. This test asserts the two agree in both directions: every
  declared operation is routable, and every route is declared. A route added
  without a contract change fails here, which is the point."
  (:require [clofin.api.health :as health]
            [clofin.audit :as audit]
            [clofin.build-info :as build-info]
            [clofin.db.core :as db]
            [clofin.db.migrate :as migrate]
            [clofin.money :as money]
            [clofin.payments.instruction :as instruction]
            [clofin.payments.state :as state]
            [clofin.routes :as routes]
            [clofin.settlement.response :as response]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [org.yaml.snakeyaml Yaml]))

(def ^:private http-methods #{"get" "post" "put" "patch" "delete" "head" "options"})

(defn clojurise
  "SnakeYAML's `java.util.Map` / `java.util.List` as Clojure maps and vectors.

  Converted **once at load**, and it is not tidying. SnakeYAML returns
  `java.util.LinkedHashMap`, on which `get`, `get-in`, `keys` and `contains?`
  all work — so a guard reads correctly right up to the point where it asks
  `map?`, which is `false`, and then quietly checks nothing. That is exactly
  what happened to the A-019 currency guard: eight schemas declare a `currency`
  property, its `:when (map? currency)` admitted none of them, and the test
  passed with one assertion while the defect it exists to catch could return
  (release-audit finding **2C-003**).

  So the data is made to be what it looks like, once, here — rather than every
  predicate in this namespace having to remember."
  [x]
  (cond
    (instance? java.util.Map x)  (into {} (map (fn [[k v]] [k (clojurise v)])) x)
    (instance? java.util.List x) (mapv clojurise x)
    :else x))

(defn load-spec
  "The contract, parsed and made into Clojure data.

  Public because `clofin.api.conformance-test` validates live responses against
  the same parse: two loaders would be two answers to \"what does the contract
  say\", which is the shape of every finding in this file (**L-16**)."
  []
  (let [file (io/file "api/openapi.yaml")]
    (assert (.exists file) "api/openapi.yaml must exist — it is the interface specification")
    (clojurise (with-open [r (io/reader file)]
                 (.load (Yaml.) r)))))

(def ^:private spec-text
  "The raw file, for discoveries that must not go through the parser.

  A guard over parsed data and a guard over the bytes are two methods; two
  implementations of one method agreeing proves nothing (standing lesson
  **L-16**)."
  (delay (slurp (io/file "api/openapi.yaml"))))

(defn- schemas-with-a-currency-property
  "Every `components.schemas.*` that declares a `currency` property, read from
  the raw YAML by indentation.

  Deliberately not a second call to the parser: the point of comparing two
  discoveries is that they are reached by different means, and two runs of the
  same parse can only agree (standing lesson **L-16**). Indentation is the
  file's own structure — a top-level key at column 0, a component group at 2,
  a schema at 4, a schema section at 6, a property at 8."
  [text]
  (let [result (reduce
                (fn [{:keys [top group schema section] :as st} line]
                  (condp (fn [re l] (re-find re l)) line
                    #"^(\w[\w-]*):" :>> (fn [[_ k]] (assoc st :top k :group nil
                                                              :schema nil :section nil))
                    #"^  (\w[\w-]*):" :>> (fn [[_ k]] (assoc st :group k :schema nil :section nil))
                    #"^    (\w[\w-]*):" :>> (fn [[_ k]] (assoc st :schema k :section nil))
                    #"^      (\w[\w-]*):" :>> (fn [[_ k]] (assoc st :section k))
                    #"^        currency:" (if (and (= "components" top)
                                                   (= "schemas" group)
                                                   (= "properties" section)
                                                   schema)
                                            (update st :found conj schema)
                                            st)
                    st))
                {:found #{}}
                (str/split-lines text))]
    (:found result)))

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
        required (set (get schema "required"))
        ;; Both configurations, because one of the fields is optional and a
        ;; comparison against a single response would check only the half of
        ;; the declaration that happened to be exercised (L-17).
        without (set (keys (:body ((health/info {:environment :test}) {}))))
        with (set (keys (:body ((health/info {:environment :test
                                              :instance-id "run-7"}) {}))))]
    (is (= declared with)
        (str "ServiceInfo declares " (pr-str (vec (sort declared)))
             " and GET / returns " (pr-str (vec (sort with)))
             " when every optional field has a value"))
    (is (every? without required)
        (str "ServiceInfo requires " (pr-str (vec (sort required)))
             " and GET / returns only " (pr-str (vec (sort without)))
             " when no optional field has a value — a required field must be"
             " answered whatever the configuration"))
    (is (= without (disj with "instanceId"))
        (str "the only field whose presence depends on the configuration is"
             " instanceId; GET / returned " (pr-str (vec (sort without)))
             " without one and " (pr-str (vec (sort with))) " with one"))
    (testing "sourceCommit is required, because it is always answered"
      (is (contains? required "sourceCommit")))
    (testing "instanceId is not, because absence is its answer for a caller
              that passed nothing (ADR-0027 amendment 3a)"
      (is (contains? declared "instanceId"))
      (is (not (contains? required "instanceId"))))))

(def ^:private undriven-payment-events
  "Lifecycle events with no operation driving them.

  One entry, and it is a decision rather than a gap: nothing marks an
  instruction failed, because a settlement item whose outcome CloFin does not
  know is swept as timed out and the instruction stays `released`. See
  `clofin.settlement.service/sweep-timeouts!`, which says so at length."
  #{"fail"})

(deftest the-payment-event-description-names-the-drivers-it-has
  (testing "C-R10a: the description said only `submit` and `cancel` had an
            operation, which stopped being true when approval and settlement
            were built. Derived from the code rather than restated, so a tenth
            event or a driver removed fails here (L-6)"
    (let [spec (load-spec)
          declared (set (get-in spec ["components" "schemas" "PaymentEvent" "enum"]))
          description (get-in spec ["components" "schemas" "PaymentEvent" "description"])
          ;; The events the lifecycle table recognises, from the table itself.
          modelled (set (map name state/events))
          driven (set/difference modelled undriven-payment-events)]
      (is (= modelled declared)
          (str "PaymentEvent declares " (pr-str (vec (sort declared)))
               " and clofin.payments.state recognises " (pr-str (vec (sort modelled)))))

      (testing "every driven event is named in the description beside an operation"
        (doseq [event driven]
          (is (str/includes? description (str "`" event "`"))
              (str "`" event "` has a driver and the description does not name it"))))

      (testing "and the undriven one is named as undriven, not quietly omitted —
                naming what a contract does not do is L-14's whole discipline"
        (doseq [event undriven-payment-events]
          (is (str/includes? description (str "`" event "` has no driver"))
              (str "`" event "` is driven by nothing and the description must "
                   "say so in those words"))))

      (testing "the sentence the residual was about is gone"
        (is (not (str/includes? description "Only `submit` and `cancel` have an"))
            "the superseded claim must not survive beside its correction")))))

(deftest the-evidence-pack-description-names-every-subject-type
  (testing "010-REQ N-3: the prose named six of the nine subjects and omitted
            reconciliation's three"
    (let [description (get-in (load-spec) ["paths" "/audit/evidence/{subjectId}"
                                           "get" "description"])
          ;; The prose spells subjects in words; the vocabulary spells them with
          ;; hyphens. Compare on the words, which is what a reader reads.
          in-words (fn [t] (str/replace t #"-" " "))]
      (is (some? description))
      (doseq [subject audit/subject-types]
        (is (str/includes? (str/lower-case description) (in-words subject))
            (str "the evidence-pack description does not name the subject type "
                 (pr-str subject) ", which clofin.audit/subject-types has")))
      (is (str/includes? description "nine")
          "and it states how many there are, so a tenth cannot be added
           without this sentence being read"))))

(deftest the-patch-description-tells-a-tenant-assertion-from-an-amendment
  (testing "C-R10a: the description listed `organisationId` among the members a
            PATCH refuses `422`, and the audit's probe supplied a matching one
            and was answered 200"
    (let [description (get-in (load-spec) ["paths" "/payment-instructions/{id}"
                                           "patch" "description"])]
      (is (str/includes? description "asserts the tenant"))
      (is (not (re-find #"`id`, `organisationId`, `status`" description))
          "organisationId must not be listed among the members that are 422")
      (testing "and the members that really are refused are all named"
        (doseq [member ["id" "status" "createdBy" "createdAt" "reversesId" "retriesId"]]
          (is (str/includes? description (str "`" member "`"))
              (str "the description does not name " (pr-str member)
                   " among the members a PATCH refuses")))))))

(deftest the-readiness-check-enum-is-what-the-code-can-emit
  (testing "a contract that admits a value the system cannot produce is L-14
            with the sign reversed. `failed` was declared and unreachable: the
            only path that would report a failing dependency answers 503 with a
            problem document, not a Readiness with a failed check inside it
            (release-audit finding 2C-010)"
    (let [declared (set (get-in (load-spec)
                                ["components" "schemas" "Readiness" "properties"
                                 "checks" "additionalProperties" "enum"]))
          ;; Derived by running the handler down both of its branches, rather
          ;; than by writing out what it emits — the second copy of a claim is
          ;; the one that goes stale (L-6).
          emitted (set (mapcat (fn [reachable?]
                                 (with-redefs [db/reachable? (constantly reachable?)
                                               migrate/current-version (constantly "0013")]
                                   (vals (get-in ((health/readyz {:environment :test} ::pool) {})
                                                 [:body "checks"]))))
                               [true false]))]
      (is (seq emitted) "the readiness handler emitted no check at all")
      (is (= emitted declared)
          (str "Readiness.checks declares " (pr-str (vec (sort declared)))
               " and clofin.api.health/readyz can emit " (pr-str (vec (sort emitted)))
               " across both of its branches")))))

(deftest the-contract-says-instance-id-is-self-reported-too
  (testing "L-14 and L-19: what an echoed identifier establishes is that the
            process answering is the one the caller started, and the sentence
            beside the field may not claim more than that"
    (let [description (str/lower-case
                       (get-in (load-spec)
                               ["components" "schemas" "ServiceInfo"
                                "properties" "instanceId" "description"]))]
      (is (str/includes? description "self-reported"))
      (is (not (str/includes? description "proves")))
      (is (not (str/includes? description "attest"))
          "nothing here attests anything, so the word must not appear at all"))))

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
      (let [found (into (sorted-map)
                        (for [[schema-name schema] (get-in spec ["components" "schemas"])
                              :let [currency (get-in schema ["properties" "currency"])]
                              :when (map? currency)]
                          [schema-name currency]))
            ;; The same population, discovered from the bytes rather than from
            ;; the parse. If the two disagree, one of them is looking at
            ;; nothing — which is the whole of 2C-003 (**L-16**, **L-17**).
            by-text (schemas-with-a-currency-property @spec-text)]
        (testing "the population is not empty, and both methods found the same one"
          (is (seq found)
              (str "no schema with a `currency` property was discovered, so every "
                   "assertion below is vacuous. At the RC this was the state: "
                   "SnakeYAML returned java.util.LinkedHashMap, `map?` was false "
                   "for all eight of them, and the guard checked none (2C-003)."))
          (is (= by-text (set (keys found)))
              (str "the parse found " (pr-str (vec (keys found)))
                   " and a scan of the raw YAML found " (pr-str (vec (sort by-text)))
                   " — a guard that discovers a different population from the one "
                   "in the file is guarding something else")))

        (doseq [[schema-name currency] found]
          (is (= "#/components/schemas/CurrencyCode" (get currency "$ref"))
              (str "components.schemas." schema-name ".properties.currency is "
                   (pr-str currency) " rather than a CurrencyCode reference")))))))

(deftest a-019-the-currency-guard-fails-when-a-currency-property-is-a-pattern
  (testing "the negative control (L-17). The precise old contract defect — a
            three-uppercase-letter pattern where a CurrencyCode reference
            belongs — reintroduced in an in-memory copy of the spec, which the
            guard must reject. At the RC the same mutation passed"
    (let [spec (assoc-in (load-spec)
                         ["components" "schemas" "Money" "properties" "currency"]
                         {"type" "string" "pattern" "^[A-Z]{3}$"})
          checked (for [[schema-name schema] (get-in spec ["components" "schemas"])
                        :let [currency (get-in schema ["properties" "currency"])]
                        :when (map? currency)]
                    [schema-name (= "#/components/schemas/CurrencyCode" (get currency "$ref"))])]
      (is (seq checked) "the mutant must still present a non-empty population")
      (is (some (comp false? second) checked)
          (str "the mutated Money.currency must fail the reference check — "
               (pr-str (vec checked))))
      (is (= ["Money"] (mapv first (remove second checked)))
          "and only the mutated one fails, so the guard is not simply failing
           everything"))))

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
