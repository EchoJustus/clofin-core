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
  (db/execute! pool ["truncate idempotency_key, payment_instruction,
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
