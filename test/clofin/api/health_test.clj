(ns clofin.api.health-test
  "Handlers are plain functions, so the whole API can be tested without binding
  a port. The database is represented by a stub because these tests are about
  the handlers' contract, not about PostgreSQL."
  (:require [clofin.api.health :as health]
            [clofin.build-info :as build-info]
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

(deftest service-info-reports-the-commit-it-was-given-and-nothing-else
  (testing "a resolved commit is reported as it is"
    (let [commit "f10974c7762eb9e095694fcfb3aaa72c0bee4bdf"]
      (is (= commit (get-in ((health/info (assoc config :source-commit commit)) {})
                            [:body "sourceCommit"])))))

  (testing "an unresolved commit is the literal string, never blank and never absent"
    (doseq [c [(assoc config :source-commit build-info/unknown)
               ;; A configuration built without going through `load-config` —
               ;; every test in this repository does that. A missing key must
               ;; still produce the honest answer rather than a null that would
               ;; render as an empty commit on a client.
               config
               (assoc config :source-commit nil)]]
      (let [body (:body ((health/info c) {}))]
        (is (contains? body "sourceCommit"))
        (is (= "unknown" (get body "sourceCommit"))))))

  (testing "the two forms the contract allows are the only two that can occur"
    (doseq [c [config
               (assoc config :source-commit "f10974c7762eb9e095694fcfb3aaa72c0bee4bdf")]]
      (let [reported (get-in ((health/info c) {}) [:body "sourceCommit"])]
        (is (or (build-info/commit-id? reported) (= build-info/unknown reported)))))))
