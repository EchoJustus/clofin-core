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

(defn clean-business-data!
  "Truncate business tables between tests, leaving reference data and the
  migration registry alone. `truncate` bypasses the append-only triggers, which
  are `for each row` on update and delete — deliberately, so that a test can
  reset without weakening the production constraint."
  [pool]
  ;; `cascade` would reach the payment tables through their foreign keys, but
  ;; they are named anyway: a test that leaves rows behind because a table was
  ;; only ever truncated by implication is a test that fails somewhere else.
  (db/execute! pool ["truncate audit_event, approval, approver_limit,
                              approval_threshold, actor_role, actor,
                              idempotency_key, payment_instruction,
                              journal_line, journal_entry, ledger_account,
                              organisation cascade"]))

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
