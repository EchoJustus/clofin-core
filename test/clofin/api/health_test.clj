(ns clofin.api.health-test
  "Handlers are plain functions, so the whole API can be tested without binding
  a port. The database is represented by a stub because these tests are about
  the handlers' contract, not about PostgreSQL."
  (:require [clofin.api.health :as health]
            [clofin.db.core :as db]
            [clofin.db.migrate :as migrate]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private config {:environment :test})

(deftest liveness-does-not-depend-on-anything-external
  (testing "healthz answers whenever the process can serve a request"
    (let [response ((health/healthz config) {})]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body "status"])))
      (is (= "clofin-core" (get-in response [:body "service"])))
      (is (nat-int? (get-in response [:body "uptimeSeconds"]))))))

(deftest readiness-reflects-the-database
  (testing "an unreachable database means 503, so traffic is routed away"
    (with-redefs [db/reachable? (constantly false)]
      (let [response ((health/readyz config ::pool) {})]
        (is (= 503 (:status response)))
        (is (= "application/problem+json" (get-in response [:headers "content-type"]))))))

  (testing "a reachable database means ready, and reports the schema version"
    (with-redefs [db/reachable? (constantly true)
                  migrate/current-version (constantly "0002")]
      (let [response ((health/readyz config ::pool) {})]
        (is (= 200 (:status response)))
        (is (= "ready" (get-in response [:body "status"])))
        (is (= "ok" (get-in response [:body "checks" "database"])))
        (is (= "0002" (get-in response [:body "schemaVersion"]))))))

  (testing "an empty schema is reported rather than omitted"
    (with-redefs [db/reachable? (constantly true)
                  migrate/current-version (constantly nil)]
      (is (= "none" (get-in ((health/readyz config ::pool) {}) [:body "schemaVersion"]))))))

(deftest service-info-states-the-scope-plainly
  (testing "anyone who reaches the API without context learns what it is not"
    (let [body (:body ((health/info config) {}))
          disclaimer (str/lower-case (get body "disclaimer"))]
      (is (= "clofin-core" (get body "service")))
      (is (= "test" (get body "environment")))
      (is (str/includes? disclaimer "synthetic"))
      (is (str/includes? disclaimer "central bank"))
      (is (str/includes? disclaimer "no regulatory")))))
