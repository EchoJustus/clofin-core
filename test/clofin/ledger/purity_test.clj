(ns clofin.ledger.purity-test
  "The domain layer is pure, and that is checked rather than remembered.

  `ARCHITECTURE.md` §4 states it as a rule and
  docs/ADR/0012-repository-seam-and-posting-time-validation.md makes it
  mechanical: a namespace named `repository` may require `clofin.db.*`; a
  domain namespace may not. A rule enforced only by review survives exactly as
  long as the reviewers who remember it.

  This test reads the `ns` form rather than the loaded namespace, because a
  transitive require through some other namespace would make a runtime check
  pass while the source still contains the dependency this rule forbids."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def pure-namespaces
  "Domain namespaces that must not reach for infrastructure.

  Extend this list when a domain namespace is added. A new context arriving
  without a line here is visible in review as a test that was not extended."
  {'clofin.money                          "src/clofin/money.clj"
   'clofin.ledger.account                 "src/clofin/ledger/account.clj"
   'clofin.ledger.entry                   "src/clofin/ledger/entry.clj"
   'clofin.organisations.organisation     "src/clofin/organisations/organisation.clj"})

(def forbidden-prefixes
  ["clofin.db." "clofin.http." "clofin.api."])

(defn- ns-form
  "The `ns` declaration at the top of a source file, as data."
  [path]
  (let [file (io/file path)]
    (assert (.exists file) (str path " must exist"))
    (with-open [r (java.io.PushbackReader. (io/reader file))]
      (read {:read-cond :allow} r))))

(defn- required-namespaces
  "Every namespace symbol named in the `ns` form's `:require` clauses."
  [form]
  (->> form
       (drop 2)                                    ; ns name and docstring
       (filter (fn [clause] (and (seq? clause) (= :require (first clause)))))
       (mapcat rest)
       (map (fn [spec] (if (sequential? spec) (first spec) spec)))
       (map str)))

(deftest the-domain-layer-does-not-depend-on-infrastructure
  (doseq [[namespace-sym path] pure-namespaces]
    (testing (str namespace-sym " is pure")
      (let [requires (required-namespaces (ns-form path))
            offending (filter (fn [required]
                                (some #(str/starts-with? required %) forbidden-prefixes))
                              requires)]
        (is (empty? offending)
            (str namespace-sym " requires " (pr-str offending)
                 " — persistence and transport belong in a repository or handler "
                 "namespace, never in the domain (ADR-0012)."))))))

(deftest the-persistence-seam-is-where-it-says-it-is
  (testing "the repository namespaces are the ones that touch the database"
    (doseq [path ["src/clofin/ledger/repository.clj"
                  "src/clofin/organisations/repository.clj"]]
      (is (some #(str/starts-with? % "clofin.db.")
                (required-namespaces (ns-form path)))
          (str path " is named `repository` but requires no persistence — "
               "either it is misnamed, or the seam has moved.")))))

(deftest a-domain-namespace-cannot-be-quietly-dropped-from-the-guard
  (testing "every pure namespace named here still exists at the path claimed"
    (doseq [[namespace-sym path] pure-namespaces]
      (is (.exists (io/file path))
          (str namespace-sym " no longer exists at " path
               " — update or remove its entry rather than leaving the guard stale.")))))
