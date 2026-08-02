(ns clofin.db.migrate-test
  "Migration behaviour against a real database.

  What matters here is not that the SQL runs, but that the *release
  discipline* holds: applying is idempotent, an edited migration is refused,
  and the applied version is reportable. See
  docs/ADR/0009-forward-only-sql-migrations.md."
  (:require [clofin.db.core :as db]
            [clofin.db.migrate :as migrate]
            [clofin.test-db :as tdb]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once tdb/with-pool)

(defn- table-exists? [pool table]
  (pos? (:count (db/query-one pool ["select count(*) as count from information_schema.tables
                                     where table_schema = 'public' and table_name = ?" table]))))

(deftest migrations-apply-to-an-empty-database
  (tdb/drop-everything! tdb/*pool*)
  (let [applied (migrate/migrate! tdb/*pool*)]
    (testing "every migration in the index runs"
      (is (= (mapv :version (migrate/available)) applied)))

    (testing "the schema is actually there"
      (is (table-exists? tdb/*pool* "organisation"))
      (is (table-exists? tdb/*pool* "currency"))
      (is (table-exists? tdb/*pool* "ledger_account"))
      (is (table-exists? tdb/*pool* "journal_entry"))
      (is (table-exists? tdb/*pool* "journal_line")))

    (testing "reference data is loaded"
      (is (= 21 (:count (db/query-one tdb/*pool* ["select count(*) as count from currency"])))))))

(deftest re-running-is-a-no-op
  (migrate/migrate! tdb/*pool*)
  (let [before (migrate/current-version tdb/*pool*)
        applied (migrate/migrate! tdb/*pool*)]
    (is (empty? applied) "a second run must apply nothing")
    (is (= before (migrate/current-version tdb/*pool*)))))

(deftest an-edited-migration-is-refused
  (migrate/migrate! tdb/*pool*)
  (testing "a migration that has been applied is immutable; tampering aborts start-up"
    (db/execute! tdb/*pool* ["update schema_migration set checksum = 'tampered' where version = ?"
                             "0001"])
    (let [t (try (migrate/migrate! tdb/*pool*) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? t) "a changed checksum must stop the service")
      (is (= :conflict (:clofin/error (ex-data t))))
      (is (str/includes? (ex-message t) "0001")))
    ;; Restore so later tests in the suite see a consistent registry.
    (db/execute! tdb/*pool* ["delete from schema_migration where version = ?" "0001"])
    (db/execute! tdb/*pool*
                 ["insert into schema_migration (version, description, checksum) values (?, ?, ?)"
                  "0001"
                  (:description (first (migrate/available)))
                  (:checksum (first (migrate/available)))])))

(deftest status-reports-applied-and-pending
  (migrate/migrate! tdb/*pool*)
  (let [{:keys [applied pending current-version]} (migrate/status tdb/*pool*)]
    (is (seq applied))
    (is (empty? pending))
    (is (= (:version (last (migrate/available))) current-version))
    (testing "every applied migration reports when it was applied"
      (is (every? :applied-at applied)))))

(deftest the-migration-index-is-the-source-of-truth
  (let [listed (mapv :filename (migrate/available))]
    (testing "versions are unique, ordered and four digits"
      (is (= listed (sort listed)))
      (is (apply distinct? (map :version (migrate/available))))
      (is (every? #(re-matches #"\d{4}" %) (map :version (migrate/available)))))

    (testing "every migration is non-empty and checksummed"
      (doseq [m (migrate/available)]
        (is (not (str/blank? (:sql m))))
        (is (= 64 (count (:checksum m))) "SHA-256 hex digest")))))
