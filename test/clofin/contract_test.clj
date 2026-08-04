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

(deftest the-audit-vocabulary-in-the-contract-is-the-one-the-service-enforces
  (testing "`clofin.audit/actions` is a closed vocabulary and the contract publishes it —
            two copies of one list, so the copies are asserted equal rather than trusted"
    (let [spec (load-spec)]
      (is (= (set audit/actions)
             (set (get-in spec ["components" "schemas" "AuditAction" "enum"])))
          "an action the service can write and the contract does not declare is an event a
           caller cannot filter for; one the contract declares and the service refuses is a
           400 the caller was invited to make")
      (is (= (set audit/subject-types)
             (set (get-in spec ["components" "schemas" "AuditEvent"
                                "properties" "subjectType" "enum"])))))))

(deftest money-is-specified-as-integer-minor-units
  (let [money (get-in (load-spec) ["components" "schemas" "Money"])]
    (testing "the wire contract cannot be read as accepting a floating-point amount"
      (is (= "integer" (get-in money ["properties" "minorUnits" "type"])))
      (is (= "int64" (get-in money ["properties" "minorUnits" "format"])))
      (is (= #{"currency" "minorUnits"} (set (get money "required")))))))
