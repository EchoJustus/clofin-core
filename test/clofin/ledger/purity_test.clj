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
   'clofin.organisations.organisation     "src/clofin/organisations/organisation.clj"
   'clofin.payments.state                 "src/clofin/payments/state.clj"
   'clofin.payments.instruction           "src/clofin/payments/instruction.clj"
   'clofin.payments.posting               "src/clofin/payments/posting.clj"
   ;; The canonicaliser and the replay decision. Storage lives in
   ;; `clofin.idempotency.repository`, which is the seam ADR-0012 names —
   ;; splitting them is what lets the digest stay a pure function of one
   ;; argument, testable without a database.
   'clofin.idempotency                    "src/clofin/idempotency.clj"
   ;; The authorisation model and the approval decision. `evaluate` being pure
   ;; is not a stylistic preference: it is what makes segregation of duties a
   ;; domain rule rather than a UI restriction (PR-071, C-01), and what lets a
   ;; past approval be replayed against the values it was decided on.
   'clofin.authz.model                    "src/clofin/authz/model.clj"
   'clofin.authz.approval                 "src/clofin/authz/approval.clj"
   ;; The audit vocabulary and the digest. Storage is
   ;; `clofin.audit.repository`, the same split as idempotency above.
   'clofin.audit                          "src/clofin/audit.clj"})

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
                  "src/clofin/organisations/repository.clj"
                  "src/clofin/payments/repository.clj"
                  "src/clofin/idempotency/repository.clj"
                  "src/clofin/authz/repository.clj"
                  "src/clofin/audit/repository.clj"]]
      (is (some #(str/starts-with? % "clofin.db.")
                (required-namespaces (ns-form path)))
          (str path " is named `repository` but requires no persistence — "
               "either it is misnamed, or the seam has moved.")))))

(def service-namespaces
  "Namespaces that orchestrate effects without owning any.

  A service composes repositories inside a transaction the *caller* owns. It
  must not reach for `clofin.db.*` itself, because a service that can open its
  own transaction is a service that can write an audit event outside the
  transaction carrying the change it describes — which is the one failure C-05
  exists to prevent, and the one this rule makes unavailable rather than
  merely discouraged.

  Extend this list when a service is added — the same discipline as
  `pure-namespaces` above, and for a stronger reason: a service missing from
  here is a service free to open its own connection, which is the failure the
  rule exists to make unavailable."
  {'clofin.payments.approval-service "src/clofin/payments/approval_service.clj"
   ;; TASK-005: the three writes that emitted no audit event until this brief.
   ;; Their handlers open the transaction; these compose the change and its
   ;; event onto it.
   'clofin.ledger.service            "src/clofin/ledger/service.clj"
   'clofin.organisations.service     "src/clofin/organisations/service.clj"})

(deftest a-service-cannot-open-its-own-transaction
  (doseq [[namespace-sym path] service-namespaces]
    (testing (str namespace-sym " reaches the database only through a repository")
      (let [offending (filter #(str/starts-with? % "clofin.db.")
                              (required-namespaces (ns-form path)))]
        (is (empty? offending)
            (str namespace-sym " requires " (pr-str offending)
                 " — a service takes the caller's transaction and composes "
                 "repositories on it (C-05, PR-075). Owning a connection here "
                 "is how an audit write ends up outside the change it describes."))))))

(deftest a-domain-namespace-cannot-be-quietly-dropped-from-the-guard
  (testing "every guarded namespace named here still exists at the path claimed"
    ;; `ns-form` asserts the file exists too, but `clojure.core/assert` compiles
    ;; to nothing when `*assert*` is false — a guard that can be compiled away
    ;; is not a guard. This one cannot be, so a namespace renamed without its
    ;; entry being updated fails here rather than passing vacuously.
    (doseq [[namespace-sym path] (merge pure-namespaces service-namespaces)]
      (is (.exists (io/file path))
          (str namespace-sym " no longer exists at " path
               " — update or remove its entry rather than leaving the guard stale.")))))
