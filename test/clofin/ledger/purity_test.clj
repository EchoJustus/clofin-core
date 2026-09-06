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
            [clojure.test :refer [deftest is testing]]
            [clojure.walk]))

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
   'clofin.audit                          "src/clofin/audit.clj"
   ;; Settlement's batching rules and its scheme adapter. The adapter is pure
   ;; for a reason worth stating: it decides what a *simulated* scheme would
   ;; say, and a simulator that could reach a socket is one nobody can prove
   ;; never did.
   'clofin.settlement.batch               "src/clofin/settlement/batch.clj"
   'clofin.settlement.scheme              "src/clofin/settlement/scheme.clj"
   ;; A scheme response's semantic identity, its digest and its disposition
   ;; vocabulary. The same split as `clofin.idempotency` beside
   ;; `clofin.idempotency.repository`, and for the same reason: the digest that
   ;; decides whether two deliveries are one message stays a pure function of
   ;; one argument, testable without a database (F-009, lesson L-12).
   'clofin.settlement.response            "src/clofin/settlement/response.clj"
   ;; The simulated scheme's statement generator. Pure for the same reason the
   ;; adapter beside it is: it decides what a *simulated* scheme would say, and
   ;; a simulator that could reach a socket is one nobody can prove never did.
   'clofin.settlement.statement           "src/clofin/settlement/statement.clj"
   ;; Reconciliation's four domain namespaces (TASK-008). The statement format
   ;; and its digest, the matching rules, the break lifecycle and what an
   ;; adjustment is — none of which may reach a database, because each is a rule
   ;; an auditor must be able to replay against the values it was decided on.
   'clofin.recon.statement                "src/clofin/recon/statement.clj"
   'clofin.recon.matching                 "src/clofin/recon/matching.clj"
   'clofin.recon.break-state              "src/clofin/recon/break_state.clj"
   'clofin.recon.adjustment               "src/clofin/recon/adjustment.clj"})

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
                  "src/clofin/audit/repository.clj"
                  "src/clofin/settlement/repository.clj"
                  "src/clofin/recon/repository.clj"]]
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
   'clofin.organisations.service     "src/clofin/organisations/service.clj"
   ;; TASK-004. Settlement moves money, so this is the service where a
   ;; connection of its own would do the most damage: a finality posting
   ;; committed apart from the outcome that caused it is a payment the ledger
   ;; says settled and the batch says did not.
   'clofin.settlement.service        "src/clofin/settlement/service.clj"
   ;; TASK-008. Reconciliation composes a receipt, its matches, its breaks and
   ;; every audit event describing them into one unit of work — and, when an
   ;; adjustment posts, a journal entry too. A connection of its own here would
   ;; be a break opened against a statement whose receipt did not commit.
   'clofin.recon.service             "src/clofin/recon/service.clj"})

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

;; ---------------------------------------------------------------------------
;; 2C-009 — the producer census
;;
;; `clofin.ledger.service/post-entry!` posts an entry **and** emits
;; `journal-entry.posted` in the same transaction. Settlement posted through
;; `clofin.ledger.repository/post-entry!` instead, so its release and finality
;; entries had no event of their own and their evidence packs answered `404` —
;; while the identical entry raised through the ledger API had both. Journal
;; evidence depended on which producer created the entry.
;;
;; The list of who may post is now a fact this test asserts rather than a
;; convention. Standing lesson **L-21**: the audited set is every committing
;; producer, discovered from the code, not the first service that implemented
;; one.
;; ---------------------------------------------------------------------------

(def audited-write-primitives
  "Repository writes that a service must wrap, and the service that wraps each.

  A production namespace calling one of these directly commits the change
  without the audit event that describes it — the state C-05 calls
  unrepresentable."
  {"clofin.ledger.repository/post-entry!" 'clofin.ledger.service})

(defn- production-sources
  []
  (->> (file-seq (io/file "src"))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".clj")))
       (sort-by #(.getPath ^java.io.File %))))

(defn- namespace-of
  [file]
  (second (ns-form (.getPath ^java.io.File file))))

(defn- alias->namespace
  "`{ledger clofin.ledger.repository}` for one file's `:require` clauses."
  [form]
  (into {}
        (for [clause (drop 2 form)
              :when (and (seq? clause) (= :require (first clause)))
              spec (rest clause)
              :when (sequential? spec)
              :let [[nsym & opts] spec
                    alias* (:as (apply hash-map opts))]
              :when alias*]
          [(str alias*) (str nsym)])))

(defn- calls-to
  "Every `alias/name` symbol used in a file, resolved through its `:require`
  aliases to a fully-qualified `namespace/name`."
  [file]
  (let [path (.getPath ^java.io.File file)
        aliases (alias->namespace (ns-form path))
        found (atom #{})]
    (with-open [r (java.io.PushbackReader. (io/reader path))]
      (let [eof (Object.)]
        (doseq [form (take-while #(not (identical? eof %))
                                 (repeatedly #(read {:read-cond :allow :eof eof} r)))]
          (clojure.walk/postwalk
           (fn [x]
             (when (and (symbol? x) (namespace x))
               (when-let [full (get aliases (namespace x))]
                 (swap! found conj (str full "/" (name x)))))
             x)
           form))))
    @found))

(deftest ac-16-only-the-audited-service-calls-an-audited-write-primitive
  (testing "every committing producer, discovered from the code (L-21). A
            namespace that posts a journal entry without going through
            `clofin.ledger.service` writes one with no `journal-entry.posted`
            and an evidence pack that answers 404 (2C-009)"
    (doseq [[primitive owner] audited-write-primitives]
      (let [callers (into (sorted-set)
                          (comp (filter #(contains? (calls-to %) primitive))
                                (map namespace-of))
                          (production-sources))
            ;; The primitive's own namespace defines it rather than calling it.
            defining (symbol (namespace (symbol primitive)))
            callers (disj callers defining)]
        (is (seq callers)
            (str "no production namespace calls " primitive
                 " — either the scan is broken or the primitive is dead"))
        (is (= #{owner} callers)
            (str primitive " must be called by " owner " and by nothing else in "
                 "src/; it is called by " (pr-str (vec callers))
                 ". A second caller posts a journal entry whose evidence pack "
                 "answers 404 (2C-009, L-21)."))))))

;; ---------------------------------------------------------------------------
;; 2B-005 — C-05's service list, compared with this one
;; ---------------------------------------------------------------------------

(defn- compliance-section
  "The text of one `### C-nn` section of `docs/COMPLIANCE.md`."
  [id]
  (let [text (slurp (io/file "docs/COMPLIANCE.md"))
        from (str/index-of text (str "### " id " "))]
    (assert from (str id " has no section in docs/COMPLIANCE.md"))
    (let [rest* (subs text from)
          to (str/index-of rest* "\n### ")]
      (if to (subs rest* 0 to) rest*))))

(deftest ac-17-c-05-names-every-audit-composing-service
  (testing "C-05 named four services and there are five: `clofin.recon.service`
            composes ingestion, assignment, proposal and decision with their
            events, and the document understated a built control-bearing
            service while contradicting its own later reconciliation section by
            omission (2B-005). Both directions, against a source that moves
            independently of the prose (L-16)"
    (let [section (compliance-section "C-05")
          in-code (into (sorted-set) (map str) (keys service-namespaces))
          named (into (sorted-set) (re-seq #"clofin\.[a-z-]+\.[a-z-]*service" section))]
      (is (seq named) "C-05 names no service at all, so this guard checks nothing")
      (is (= in-code named)
          (str "C-05's section names " (pr-str (vec named))
               " and clofin.ledger.purity-test/service-namespaces holds "
               (pr-str (vec in-code))
               ". A service missing from the document understates the control; "
               "a name in the document that is not a service overstates it.")))))
