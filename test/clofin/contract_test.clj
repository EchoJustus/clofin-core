(ns clofin.contract-test
  "The API contract and the service must not drift apart.

  `api/openapi.yaml` is the interface specification, not generated
  documentation. This test asserts the two agree in both directions: every
  declared operation is routable, and every route is declared. A route added
  without a contract change fails here, which is the point."
  (:require [clofin.audit :as audit]
            [clofin.routes :as routes]
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
