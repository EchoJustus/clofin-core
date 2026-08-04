(ns clofin.test-runner
  "Test entrypoint for `clojure -M:test`.

  Test namespaces are listed explicitly rather than discovered by scanning the
  filesystem. A scan is not portable across a source tree, a jar and a
  container image, and silently skipping a test suite because a scan missed it
  is a failure mode worth designing out.

  Integration tests that need PostgreSQL are included only when
  `-Dclofin.test.integration=true` is set, which `clojure -M:test:it` does."
  (:require [clojure.test :as test]
            [clojure.string :as str]))

(def unit-namespaces
  "Tests that need nothing but a JVM."
  '[clofin.money-test
    clofin.error-test
    clofin.ledger.account-test
    clofin.ledger.entry-test
    clofin.ledger.purity-test
    clofin.organisations.organisation-test
    clofin.payments.state-test
    clofin.payments.instruction-test
    clofin.payments.posting-test
    clofin.idempotency-test
    clofin.authz.model-test
    clofin.authz.approval-test
    clofin.audit-test
    clofin.config-test
    clofin.http.router-test
    clofin.http.middleware-test
    clofin.api.health-test
    clofin.api.wire-test
    clofin.contract-test])

(def integration-namespaces
  "Tests that need a reachable PostgreSQL instance."
  '[clofin.db.migrate-test
    clofin.db.ledger-constraints-test
    clofin.db.audit-constraints-test
    clofin.organisations.repository-test
    clofin.ledger.repository-test
    clofin.payments.repository-test
    clofin.api.ledger-api-test
    clofin.authz.repository-test
    clofin.api.payments-api-test
    clofin.api.approvals-api-test
    clofin.system-test])

(defn integration?
  "True when integration tests should run in this invocation."
  []
  (= "true" (System/getProperty "clofin.test.integration")))

(defn -main [& args]
  (let [selected (if (seq args)
                   (mapv symbol args)
                   (cond-> (vec unit-namespaces)
                     (integration?) (into integration-namespaces)))]
    (println "Running" (count selected) "test namespace(s)"
             (if (integration?) "including integration tests" "(unit and property only)"))
    (doseq [ns-sym selected] (require ns-sym))
    (let [summary (apply test/run-tests selected)]
      (println)
      (println (format "%d assertion(s), %d failure(s), %d error(s)"
                       (:pass summary) (:fail summary) (:error summary)))
      (when-not (integration?)
        (println (str "Integration tests were skipped. Run `make test-it` to include "
                      (str/join ", " (map name integration-namespaces)) ".")))
      (shutdown-agents)
      (System/exit (if (zero? (+ (:fail summary) (:error summary))) 0 1)))))
