(ns clofin.tools.capture.store
  "Direct database access for the capture harness: seeding, and reading the
  journal and the audit trail out whole.

  Plain JDBC rather than `clofin.db.core`. Two reasons, both about what this
  namespace is for. The harness must run against a stack built from a *tagged*
  commit while itself running from `main`, so anything it shared with the
  running service would be the wrong copy; and the harness is a witness, not a
  participant — it reads rows the way an auditor with a psql prompt would,
  through no abstraction the system under capture also uses.

  **Everything, not a selection.** `journal` and `audit-events` return every
  row for the scenario's organisation, in a stable order, with every column.
  A capture that returned the columns the walkthrough currently displays would
  quietly decide what the next reader is allowed to check, and the whole claim
  being made is that the reader can check."
  (:require [clojure.string :as str])
  (:import [java.sql Connection DriverManager ResultSet Timestamp]
           [java.time Instant]))

(defn connect
  "A JDBC connection to the capture database.

  The driver is loaded explicitly: the harness runs from an alias whose
  classpath it does not otherwise touch, and a `No suitable driver` at capture
  time is a confusing way to learn that."
  ^Connection [{:keys [url user password]}]
  (Class/forName "org.postgresql.Driver")
  (DriverManager/getConnection url user password))

(defn- value
  "One JDBC column as a value a JSON writer can take.

  Timestamps become ISO-8601 instants — the same rendering
  `clofin.http.middleware` gives them on the wire, so a value read from a row
  and the same value read from a response are comparable by string equality on
  the page."
  [^ResultSet rs idx]
  (let [raw (.getObject rs idx)]
    (cond
      (nil? raw) nil
      (instance? Timestamp raw) (str (.toInstant ^Timestamp raw))
      (instance? java.util.UUID raw) (str raw)
      (instance? java.math.BigDecimal raw) (str raw)
      (instance? java.sql.Array raw) (vec (.getArray ^java.sql.Array raw))
      (instance? Boolean raw) raw
      (number? raw) raw
      :else (str raw))))

(defn query
  "Run `sql` with `params` and return a vector of column-name → value maps."
  [^Connection conn sql & params]
  (with-open [ps (.prepareStatement conn sql)]
    (doseq [[i p] (map-indexed vector params)]
      (.setObject ps (inc i) p))
    (with-open [rs (.executeQuery ps)]
      (let [meta (.getMetaData rs)
            cols (mapv #(.getColumnLabel meta %) (range 1 (inc (.getColumnCount meta))))]
        (loop [acc []]
          (if (.next rs)
            (recur (conj acc (into (array-map)
                                   (map-indexed (fn [i col] [col (value rs (inc i))]) cols))))
            acc))))))

(defn execute!
  "Run a statement for effect, returning `{:ok true :rows n}`.

  A failure is returned rather than thrown, with the database's own message,
  because several scenario steps exist precisely to be refused — granting a
  role the check constraint does not know, re-batching an instruction the
  unique index will not have twice — and the refusal text is the evidence."
  [^Connection conn sql]
  (try
    (with-open [st (.createStatement conn)]
      {:ok true :rows (.executeUpdate st sql)})
    (catch java.sql.SQLException e
      {:ok false
       :error (str/trim (str (.getMessage e)))
       :sqlstate (.getSQLState e)})))

(defn reset-schema!
  "Drop and recreate `public`, so a capture always starts from nothing.

  The guard is not decoration. This wipes a database, and the harness's
  default is a URL an operator may well have pointed at their development
  instance by editing one line. Refusing anything whose database name does not
  end in `_capture` costs nothing and has exactly one failure mode, which is
  being told to rename a database."
  [{:keys [url] :as db}]
  (let [db-name (last (str/split (str/replace url #"\?.*$" "") #"/"))]
    (when-not (str/ends-with? (str db-name) "_capture")
      (throw (ex-info (format (str "capture refuses: %s is not a capture database. This step drops "
                                   "and recreates the `public` schema, so the harness will only "
                                   "point it at a database whose name ends in `_capture`.")
                              (pr-str db-name))
                      {:url url :database db-name})))
    (with-open [conn (connect db)
                st   (.createStatement conn)]
      (.execute st "drop schema if exists public cascade")
      (.execute st "create schema public"))
    db-name))

;; ---------------------------------------------------------------------------
;; Reading the ledger and the trail out
;; ---------------------------------------------------------------------------

(defn journal
  "Every journal entry for `organisation-id`, each with all of its lines.

  Ordered by occurrence then by insertion order of the lines, so two captures
  of the same scenario produce the same shape. The account's code and name are
  joined in: a line that names only an account id is unreadable on a page, and
  resolving the id at render time would be the trace repository looking
  something up."
  [conn organisation-id]
  (let [entries (query conn
                       (str "select id, organisation_id, occurred_at, recorded_at, narrative, "
                            "       reference_type, reference_id, reverses_id "
                            "  from journal_entry where organisation_id = ?::uuid "
                            " order by occurred_at, recorded_at, id")
                       organisation-id)
        ;; The account's code and type are joined in because a line that names
        ;; only an account id is unreadable, and resolving the id at render
        ;; time would be the trace repository looking something up. There is no
        ;; `normal_balance` column to join: which way an account type normally
        ;; balances is a domain fact (`clofin.ledger.account/account-types`),
        ;; not a stored one, and it reaches the bundle the way every other
        ;; derived value does — inside a captured API response.
        lines   (query conn
                       (str "select l.id, l.entry_id, l.line_no, l.account_id, a.code as account_code, "
                            "       a.name as account_name, a.type as account_type, "
                            "       l.direction, l.amount_minor, l.currency "
                            "  from journal_line l "
                            "  join journal_entry e on e.id = l.entry_id "
                            "  join ledger_account a on a.id = l.account_id "
                            " where e.organisation_id = ?::uuid "
                            " order by e.occurred_at, e.recorded_at, l.entry_id, l.line_no")
                       organisation-id)
        by-entry (group-by #(get % "entry_id") lines)]
    (mapv (fn [entry]
            (assoc entry "lines" (vec (get by-entry (get entry "id") []))))
          entries)))

(defn audit-events
  "Every audit event for `organisation-id`, oldest first.

  Includes the digests and omits nothing: an audit event stores digests rather
  than payloads by design (ADR-0016), so there is no sensitive column here to
  leave out, and leaving one out would misrepresent what the table holds."
  [conn organisation-id]
  (query conn
         (str "select id, organisation_id, actor_id, action, subject_type, subject_id, "
              "       before_digest, after_digest, correlation_id, occurred_at "
              "  from audit_event where organisation_id = ?::uuid "
              " order by occurred_at, id")
         organisation-id))

(defn accounts
  "The organisation's chart of accounts, by code."
  [conn organisation-id]
  (query conn
         (str "select id, code, name, type, currency, status "
              "  from ledger_account where organisation_id = ?::uuid order by code")
         organisation-id))

(defn now
  "The database's clock, as an instant.

  Used for the `to` bound of a statement request. Taking it from the database
  rather than from the harness's JVM removes a class of flake nobody enjoys
  diagnosing: a movement posted a few milliseconds ago and excluded from the
  statement that was supposed to show it."
  [conn]
  (Instant/parse (get (first (query conn "select now() as t")) "t")))
