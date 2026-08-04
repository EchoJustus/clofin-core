(ns clofin.test-db
  "Shared fixtures for tests that need a real PostgreSQL instance.

  These tests run against PostgreSQL rather than an in-memory substitute
  because most of what they assert — deferred constraint triggers, append-only
  enforcement, transactional isolation — does not exist in a substitute. A test
  double would assert that CloFin's own code agrees with itself, which is the
  one thing already covered by the unit tests."
  (:require [clofin.config :as config]
            [clofin.db.core :as db]
            [clofin.db.migrate :as migrate]))

(def ^:dynamic *pool* nil)

(defn db-config []
  (:db (config/load-config)))

(defn with-pool
  "Fixture supplying a connection pool for the whole namespace."
  [f]
  (let [pool (db/open-pool (assoc (db-config) :pool-size 4))]
    (try
      (binding [*pool* pool] (f))
      (finally (db/close-pool! pool)))))

(defn drop-everything!
  "Return the database to an empty state. Used by the migration tests, which
  need to observe migration from nothing."
  [pool]
  (db/execute! pool ["drop schema public cascade"])
  (db/execute! pool ["create schema public"]))

(defn with-migrated-schema
  "Fixture guaranteeing the schema is present and current."
  [f]
  (migrate/migrate! *pool*)
  (f))

(def append-only-tables
  "Tables whose destructive verbs are refused by `reject_mutation()` triggers.

  Named here as a statement of what this file expects the schema to guard.
  `clean-business-data!` does not read it to decide what to disarm — it
  discovers that from `pg_trigger` — but it does assert the two agree, so a
  migration that adds or removes a guard fails here loudly rather than leaving
  a cleanup that silently stops resetting a table."
  ["journal_entry" "journal_line" "audit_event" "approval" "scheme_response"])

(defn clean-business-data!
  "Reset business tables between tests, leaving reference data and the
  migration registry alone.

  **This function is the schema-owner adversary that `COMPLIANCE.md` §4 names,
  and it is worth understanding rather than copying.**

  Every destructive verb on the append-only tables is refused —
  `UPDATE`, `DELETE` and, since migration `0007`, `TRUNCATE`. That is the point
  of the control (C-03, C-05), and it means a test cannot reset those tables by
  any ordinary means. What is left is what a table's *owner* can always do:
  disarm the triggers. Tests run as the owning role, so this works.

  Two things follow, and both are deliberate:

  1. **The production control is not weakened.** There is no escape hatch in
     `reject_mutation()` and no session flag it consults — proposals to add one
     were rejected, because a guard with a documented bypass is a guard whose
     bypass eventually appears in an incident. The application cannot do this
     by writing SQL; it can only do it by being the owner.
  2. **It is a live demonstration of the residual risk.** Audit finding F-002
     closed the verb gap; it did not — and a trigger cannot — bind an adversary
     holding the owner's credentials. The fix for that is the runtime role
     split named as debt in `COMPLIANCE.md` §4, under which *this very
     function* would stop working. That is the intended outcome, not a
     regression. No test asserts that today — there is no non-owner role in the
     test schema to assert it with — so this paragraph is a statement of intent,
     not of covered behaviour.

  Disable, truncate and re-enable happen in **one transaction**, so a failure
  anywhere rolls the disable back with everything else and cannot leave the
  guards down for the next test.

  Only the `BEFORE TRUNCATE` guards are disarmed, and each is named
  individually. `disable trigger user` would be shorter and is wrong: it also
  disarms `journal_entry_must_balance`, the deferred constraint trigger behind
  C-03. Verified — with the row guards down, an unbalanced journal entry
  commits, because a deferred trigger that was disabled at INSERT queues no
  event to fire at commit, so re-enabling before COMMIT does not save it.
  Nothing is inserted inside the window today, so nothing is broken today; the
  point is that the narrow form cannot break if that ever stops being true."
  [pool]
  (db/with-transaction [tx pool]
    (let [guards (db/query tx ["select c.relname as table_name, t.tgname as trigger_name,
                                      t.tgenabled::text as enabled
                                 from pg_trigger t
                                 join pg_class c on c.oid = t.tgrelid
                                 join pg_namespace n on n.oid = c.relnamespace
                                where n.nspname = 'public'
                                  and not t.tgisinternal
                                  and (t.tgtype & 32) <> 0"])]
      ;; Discovered rather than listed, so a migration that guards a new table
      ;; is disarmed here without anyone remembering to edit this function —
      ;; and cross-checked against the declared list, so one that guards a table
      ;; nobody expected is loud rather than silent.
      ;;
      ;; `throw`, not `assert`: `clojure.core/assert` compiles to nothing when
      ;; `*assert*` is false, and a drift guard that can be compiled away is not
      ;; a guard. That is the same shape as the finding this file exists to
      ;; accommodate.
      (when-not (= (set append-only-tables) (set (map :table-name guards)))
        (throw (ex-info "TRUNCATE guards have drifted from `append-only-tables`"
                        {:declared (vec (sort append-only-tables))
                         :found    (vec (sort (map :table-name guards)))})))
      (doseq [{:keys [table-name trigger-name]} guards]
        (db/execute! tx [(str "alter table " table-name " disable trigger " trigger-name)]))
      ;; `cascade` would reach the payment tables through their foreign keys, but
      ;; they are named anyway: a test that leaves rows behind because a table was
      ;; only ever truncated by implication is a test that fails somewhere else.
      (db/execute! tx ["truncate audit_event, approval, approver_limit,
                                 approval_threshold, actor_role, actor,
                                 scheme_response, settlement_batch_item,
                                 settlement_batch,
                                 idempotency_key, payment_instruction,
                                 journal_line, journal_entry, ledger_account,
                                 organisation cascade"])
      ;; Restore each trigger to the state it was found in, rather than to
      ;; `ENABLE`. The two differ: `enable trigger` sets `tgenabled` to `'O'`
      ;; (origin), while `enable always` sets `'A'`, and only `'A'` survives a
      ;; superuser setting `session_replication_role = 'replica'`. Today every
      ;; guard is `'O'`, so this is identical in effect — but a fixture that
      ;; hard-coded `enable` would silently downgrade a guard the day a
      ;; migration strengthens one, leaving the suite green and the control
      ;; quietly weaker. That is exactly F-002's shape, and it is not worth
      ;; re-creating here to save a word.
      (doseq [{:keys [table-name trigger-name enabled]} guards]
        (db/execute! tx [(str "alter table " table-name
                              (case enabled
                                "A" " enable always trigger "
                                "R" " enable replica trigger "
                                "D" " disable trigger "
                                " enable trigger ")
                              trigger-name)])))))

(defn with-clean-data
  [f]
  (clean-business-data! *pool*)
  (f))

;; ---------------------------------------------------------------------------
;; Synthetic fixtures
;; ---------------------------------------------------------------------------

(defn insert-organisation!
  [pool {:keys [id legal-name short-name] :or {legal-name "Meridian Freight Holdings Pte Ltd"
                                               short-name "meridian"}}]
  (db/execute! pool ["insert into organisation (id, legal_name, short_name) values (?, ?, ?)"
                     id legal-name short-name])
  id)

(defn insert-account!
  [pool {:keys [id organisation-id code name type currency status]
         :or {name "Test account" type "asset" currency "SGD" status "active"}}]
  (db/execute! pool ["insert into ledger_account (id, organisation_id, code, name, type, currency, status)
                     values (?, ?, ?, ?, ?, ?, ?)"
                     id organisation-id code name type currency status])
  id)

(defn insert-entry!
  [pool {:keys [id organisation-id occurred-at narrative reference-type reference-id]
         :or {narrative "Test entry" reference-type "payment-instruction"}}]
  (db/execute! pool ["insert into journal_entry
                      (id, organisation_id, occurred_at, narrative, reference_type, reference_id)
                     values (?, ?, ?, ?, ?, ?)"
                     id organisation-id (or occurred-at (java.time.Instant/now))
                     narrative reference-type (or reference-id (random-uuid))])
  id)

(defn insert-line!
  [source {:keys [id entry-id line-no account-id direction amount-minor currency]
           :or {currency "SGD"}}]
  (db/execute! source ["insert into journal_line
                        (id, entry_id, line_no, account_id, direction, amount_minor, currency)
                       values (?, ?, ?, ?, ?, ?, ?)"
                       (or id (random-uuid)) entry-id line-no account-id
                       direction amount-minor currency]))

(defn insert-balanced-entry!
  "Insert a journal entry with two balancing lines, in one transaction.

  Migration `0008` requires an entry to carry at least two lines, balancing per
  currency, by commit time — so an entry and its lines can no longer be written
  by separate autocommitted statements, and a fixture that wants a *valid*
  entry has to build a whole one.

  That is the point of audit finding F-003 rather than an inconvenience: before
  `0008`, a fixture could leave a zero-line entry in the ledger and nothing
  objected. Tests that deliberately probe an *incomplete* entry still do so
  directly, so the constraint they exercise is visible at the call site."
  [pool {:keys [id organisation-id debit-account-id credit-account-id
                amount-minor currency narrative reference-type reference-id]
         :or {amount-minor 125000 currency "SGD" narrative "Test entry"
              reference-type "payment-instruction"}}]
  (let [id (or id (random-uuid))]
    (db/with-transaction [tx pool]
      (insert-entry! tx {:id id :organisation-id organisation-id
                         :narrative narrative :reference-type reference-type
                         :reference-id reference-id})
      (insert-line! tx {:entry-id id :line-no 1 :account-id debit-account-id
                        :direction "debit" :amount-minor amount-minor :currency currency})
      (insert-line! tx {:entry-id id :line-no 2 :account-id credit-account-id
                        :direction "credit" :amount-minor amount-minor :currency currency}))
    id))

;; ---------------------------------------------------------------------------
;; Actors, roles and limits
;; ---------------------------------------------------------------------------
;;
;; **Default deny applies to fixtures too.** There is no `insert-superuser!`
;; here and there must not be one: an actor is seeded with no roles, and a test
;; that needs a right grants it explicitly. That makes each fixture a readable
;; statement of what a role can do — which is the second reason for the rule,
;; after the obvious one that a superuser in a test is a superuser someone
;; eventually reaches for in production.

(defn insert-actor!
  "Seed an actor with the roles and per-currency limits named.

  `roles` is a collection of role keywords or strings; `limits` is
  `{\"SGD\" 100000}`. Both default to nothing at all, which is what an actor
  with no grants can do."
  [pool {:keys [id organisation-id display-name status roles limits]
         :or {display-name "Test actor" status "active" roles [] limits {}}}]
  (let [id (or id (random-uuid))]
    (db/execute! pool ["insert into actor (id, organisation_id, display_name, status)
                       values (?, ?, ?, ?)"
                       id organisation-id display-name (name status)])
    (doseq [role roles]
      (db/execute! pool ["insert into actor_role (actor_id, role) values (?, ?)"
                         id (name role)]))
    (doseq [[currency limit-minor] limits]
      (db/execute! pool ["insert into approver_limit (actor_id, currency, limit_minor)
                         values (?, ?, ?)"
                         id currency limit-minor]))
    id))

(defn insert-threshold!
  "Seed one approval band: `from-minor` and above requires `approvals-required`."
  [pool {:keys [organisation-id currency from-minor approvals-required]
         :or {currency "SGD" from-minor 0 approvals-required 1}}]
  (db/execute! pool ["insert into approval_threshold
                       (organisation_id, currency, from_minor, approvals_required)
                     values (?, ?, ?, ?)"
                     organisation-id currency from-minor (int approvals-required)]))

(defn insert-approval!
  [pool {:keys [id instruction-id actor-id decision reason]
         :or {decision "approved"}}]
  (let [id (or id (random-uuid))]
    (db/execute! pool ["insert into approval (id, instruction_id, actor_id, decision, reason)
                       values (?, ?, ?, ?, ?)"
                       id instruction-id actor-id (name decision) reason])
    id))

(defn insert-audit-event!
  [pool {:keys [id organisation-id actor-id action subject-type subject-id
                before-digest after-digest correlation-id]
         :or {action "payment.created" subject-type "payment-instruction"}}]
  (let [id (or id (random-uuid))]
    (db/execute! pool ["insert into audit_event
                         (id, organisation_id, actor_id, action, subject_type,
                          subject_id, before_digest, after_digest, correlation_id)
                       values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                       id organisation-id actor-id action subject-type
                       (or subject-id (random-uuid))
                       before-digest after-digest correlation-id])
    id))
