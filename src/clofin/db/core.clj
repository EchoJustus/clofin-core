(ns clofin.db.core
  "Connection pooling and a small set of SQL helpers over `java.sql`.

  Deliberately thin: parameterised statements, explicit transactions, and
  result rows as Clojure maps. There is no query DSL and no ORM — the SQL in
  this repository is the SQL that runs, which is what makes the schema
  constraints reviewable (docs/ADR/0006-postgresql-as-system-of-record.md).

  String interpolation into SQL is never used anywhere in CloFin. Every value
  travels as a bound parameter."
  (:require [clofin.error :as err]
            [clojure.string :as str])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.sql Connection PreparedStatement ResultSet Statement Timestamp Types]
           [java.time Instant LocalDate]
           [javax.sql DataSource]
           [org.postgresql.util PSQLException ServerErrorMessage]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Connection pool
;; ---------------------------------------------------------------------------

(defn open-pool
  "Create a connection pool. The caller owns it and must `close-pool!` it."
  ^HikariDataSource [{:keys [url user password pool-size connect-timeout-ms]}]
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl url)
              (.setUsername user)
              (.setPassword password)
              (.setMaximumPoolSize (int (or pool-size 8)))
              (.setMinimumIdle (int 1))
              (.setConnectionTimeout (long (or connect-timeout-ms 10000)))
              (.setPoolName "clofin")
              ;; Fail fast on a bad statement rather than leaving a half-applied
              ;; transaction to a later caller.
              (.setAutoCommit true))]
    (HikariDataSource. cfg)))

(defn close-pool! [^HikariDataSource pool]
  (when pool (.close pool)))

;; ---------------------------------------------------------------------------
;; Parameter and result conversion
;; ---------------------------------------------------------------------------

(defn- set-param!
  [^PreparedStatement stmt ^long idx value]
  (let [i (int idx)]
    (cond
      (nil? value)            (.setNull stmt i Types/NULL)
      (instance? Instant value) (.setTimestamp stmt i (Timestamp/from ^Instant value))
      (instance? java.util.Date value) (.setTimestamp stmt i (Timestamp. (.getTime ^java.util.Date value)))
      (keyword? value)        (.setString stmt i (name value))
      (uuid? value)           (.setObject stmt i value)
      :else                   (.setObject stmt i value))))

(defn- column-key
  "PostgreSQL lower-cases unquoted identifiers, and CloFin names columns with
  underscores. Rows come back as kebab-case keywords so that domain code never
  sees SQL naming."
  [^String label]
  (keyword (str/replace (str/lower-case label) \_ \-)))

(defn- row->map
  [^ResultSet rs column-keys]
  (persistent!
   (reduce-kv (fn [m idx k] (assoc! m k (.getObject rs (int (inc idx)))))
              (transient {})
              column-keys)))

(defn- result-seq
  [^ResultSet rs]
  (let [meta        (.getMetaData rs)
        column-keys (mapv #(column-key (.getColumnLabel meta (int (inc %))))
                          (range (.getColumnCount meta)))]
    (loop [acc (transient [])]
      (if (.next rs)
        (recur (conj! acc (row->map rs column-keys)))
        (persistent! acc)))))

;; ---------------------------------------------------------------------------
;; Reading values back out of a row
;; ---------------------------------------------------------------------------
;;
;; JDBC returns whatever the driver considers natural for a column type, which
;; is not always what the domain uses: `sum(bigint)` is `numeric` and arrives as
;; a `BigDecimal`, and `timestamptz` arrives as a `java.sql.Timestamp`. These
;; two functions are the only place that conversion happens, so a repository
;; never carries a driver type into a domain value.

(defn ->long
  "Coerce a numeric column value to a `long`, exactly.

  `longValueExact` rather than `longValue`: an aggregate that has overflowed a
  long is a fact worth failing on, not one worth truncating. Money is involved."
  ^long [value]
  (cond
    (nil? value)                     0
    (instance? Long value)           (long value)
    (instance? java.math.BigDecimal value) (.longValueExact ^java.math.BigDecimal value)
    (instance? java.math.BigInteger value) (.longValueExact ^java.math.BigInteger value)
    (instance? Number value)         (long value)
    :else (err/invalid! (str "Not a numeric column value: " (class value)) {:value value})))

(defn ->instant
  "Coerce a timestamp column value to a `java.time.Instant`."
  ^Instant [value]
  (cond
    (nil? value)                  nil
    (instance? Instant value)     value
    (instance? Timestamp value)   (.toInstant ^Timestamp value)
    (instance? java.util.Date value) (.toInstant ^java.util.Date value)
    :else (err/invalid! (str "Not a timestamp column value: " (class value)) {:value value})))

(defn ->uuids
  "Coerce a `uuid[]` column value to a vector of `java.util.UUID`, in the order
  the query produced them.

  An aggregate over no rows is `null` in SQL and an **empty vector** here: a
  projection whose \"nothing references this\" case were nil and whose \"one
  thing does\" case were a vector would make every consumer test for two shapes,
  and one of them would eventually be forgotten. `array_agg` is ordered in the
  query rather than here, so the order is the database's and is stable."
  [value]
  (cond
    (nil? value)                      []
    (vector? value)                   value
    (instance? java.sql.Array value)  (vec (.getArray ^java.sql.Array value))
    :else (err/invalid! (str "Not an array column value: " (class value)) {:value value})))

(defn ->local-date
  "Coerce a `date` column value to a `java.time.LocalDate`.

  A `date` is a calendar date, not an instant, and the driver hands one back as
  a `java.sql.Date` — which is a `java.util.Date` and therefore carries a time
  and a zone it has no business carrying. Converting here is what stops a value
  date shifting by a day when the JVM's default zone is not UTC."
  ^LocalDate [value]
  (cond
    (nil? value)                   nil
    (instance? LocalDate value)    value
    (instance? java.sql.Date value) (.toLocalDate ^java.sql.Date value)
    :else (err/invalid! (str "Not a date column value: " (class value)) {:value value})))

;; ---------------------------------------------------------------------------
;; Query and execute
;; ---------------------------------------------------------------------------

(defn- prepare
  ^PreparedStatement [^Connection conn [sql & params] return-keys?]
  (let [stmt (if return-keys?
               (.prepareStatement conn ^String sql Statement/RETURN_GENERATED_KEYS)
               (.prepareStatement conn ^String sql))]
    (dorun (map-indexed (fn [idx p] (set-param! stmt (inc idx) p)) params))
    stmt))

(defprotocol Connectable
  "Anything a query can run against: a pool, or a connection already inside a
  transaction. This is what lets a repository function be called either
  standalone or as part of a larger unit of work without knowing which."
  (-connection [this])
  (-close-after? [this]))

(extend-protocol Connectable
  HikariDataSource
  (-connection [this] (.getConnection this))
  (-close-after? [_] true)

  Connection
  (-connection [this] this)
  (-close-after? [_] false))

(defmacro with-connection
  "Bind a connection from `source` for the duration of `body`.

  When `source` is a pool the connection is borrowed and returned; when it is
  already a connection — because the caller is inside a transaction — it is
  used as-is and left open. Callers therefore compose without knowing which
  they were given."
  [[binding source] & body]
  (let [conn-sym (with-meta binding {:tag 'java.sql.Connection})]
    `(let [src#      ~source
           ~conn-sym (-connection src#)
           close?#   (-close-after? src#)]
       (try
         ~@body
         (finally
           (when close?# (.close ~conn-sym)))))))

(defn query
  "Run a `SELECT` and return a vector of row maps.

  `sql-params` is a vector of the SQL string followed by its bound parameters:

      (query pool [\"select * from account where id = ?\" account-id])"
  [source sql-params]
  (with-connection [conn source]
    (with-open [stmt (prepare conn sql-params false)
                rs   (.executeQuery stmt)]
      (result-seq rs))))

(defn query-one
  "Run a `SELECT` expected to match at most one row."
  [source sql-params]
  (first (query source sql-params)))

(defn execute!
  "Run an `INSERT`, `UPDATE`, `DELETE` or DDL statement. Returns the affected
  row count."
  [source sql-params]
  (with-connection [conn source]
    (with-open [stmt (prepare conn sql-params false)]
      (.executeUpdate stmt))))

(defn insert-returning!
  "Run an `INSERT ... RETURNING ...` and return the resulting row map."
  [source sql-params]
  (with-connection [conn source]
    (with-open [stmt (prepare conn sql-params false)
                rs   (.executeQuery stmt)]
      (first (result-seq rs)))))

;; ---------------------------------------------------------------------------
;; Transactions
;; ---------------------------------------------------------------------------

(def isolation-levels
  {:read-committed  Connection/TRANSACTION_READ_COMMITTED
   :repeatable-read Connection/TRANSACTION_REPEATABLE_READ
   :serializable    Connection/TRANSACTION_SERIALIZABLE})

(defn with-transaction*
  "Run `(f conn)` inside a transaction, committing on success and rolling back
  on any throwable.

  Isolation is chosen explicitly per unit of work rather than globally: a read
  that leads to a decision about money — approving, releasing, reversing —
  states its requirement at the call site so a reviewer can see it."
  ([source f] (with-transaction* source {} f))
  ([source {:keys [isolation]} f]
   (let [^HikariDataSource pool source
         ^Connection conn (.getConnection pool)
         previous-auto (.getAutoCommit conn)
         previous-iso  (.getTransactionIsolation conn)]
     (try
       (.setAutoCommit conn false)
       (when isolation
         (.setTransactionIsolation
          conn (int (or (isolation-levels isolation)
                        (err/invalid! (str "Unknown isolation level: " isolation)
                                      {:isolation isolation
                                       :known (vec (sort (keys isolation-levels)))})))))
       (let [result (f conn)]
         (.commit conn)
         result)
       (catch Throwable t
         (try (.rollback conn) (catch Exception _))
         (throw t))
       (finally
         (try
           (.setAutoCommit conn previous-auto)
           (.setTransactionIsolation conn previous-iso)
           (catch Exception _))
         (.close conn))))))

(defmacro with-transaction
  "    (with-transaction [tx pool {:isolation :serializable}]
          (execute! tx [...])
          (execute! tx [...]))"
  [[binding source opts] & body]
  `(with-transaction* ~source ~(or opts {}) (fn [~binding] ~@body)))

(defn assert-transaction!
  "Return `source` if it is a connection inside an open transaction; throw
  otherwise.

  **The one runtime check behind a precondition that used to be prose.** \"This
  is a connection\" and \"this is a transaction\" are different claims, and until
  audit finding **F-011** only services' *documentation* asserted the second.
  The pool is configured `autoCommit true`, so a caller who passed a raw pooled
  connection got each statement committed on its own — silently: every write
  still succeeds, and only atomicity is gone. For a service that composes an
  aggregate write with the audit event describing it, losing atomicity means the
  aggregate can commit and the event fail, which is the unaudited state change
  C-05 calls unrepresentable (standing lesson **L-13**).

  Two callers, deliberately at two different depths. `transactionally` uses it
  to protect the `select … for update` in a repository, whose locks are released
  per statement under autocommit and so guarantee nothing (finding **F-004**).
  `clofin.audit.repository/assert-unit-of-work!` re-exports it for services,
  which must fail *before their first write* rather than at whichever repository
  happens to check.

  Reported as a `:validation` error, which is what the guard has raised since
  F-004; the message is asserted by `clofin.ledger.repository-test`."
  [source]
  (when-not (instance? Connection source)
    (err/invalid! "This work must run inside a transaction, and it was given a connection pool"
                  {:hint "Wrap the call in `with-transaction` and pass the transaction, not the pool."}))
  (when (.getAutoCommit ^Connection source)
    (err/invalid! "This work must run inside a transaction, and the connection it was given is in autocommit"
                  {:hint "Wrap the call in `with-transaction`, or pass the pool and let it open one."}))
  source)

(defn transactionally
  "Run `(f conn)` in a transaction, joining the caller's if there is one.

  When `source` is already a connection the caller owns a transaction and this
  work simply joins it — atomicity is then the caller's to guarantee. That is
  what lets a repository function stand alone *and* compose into a larger unit
  of work, such as a payment instruction whose state change and idempotency key
  must commit together, without either caller knowing which it is.

  **The connection is checked, not trusted.** \"Already a connection\" and \"already
  in a transaction\" are different claims, and until this guard existed only the
  first was tested. The pool is configured `autoCommit true`, so a caller who
  handed a raw pooled connection straight to a repository function would get
  each statement committed on its own — and would get it *silently*: every
  write still succeeds, and only atomicity is gone.

  That became load-bearing with the F-004 fix. A `select … for update` releases
  its locks at the end of its transaction, so under autoCommit the lock is gone
  before the insert it was taken for, and the validate-then-write race is back
  with the lock still visible in the SQL. A guarantee that a reader can see in
  the code and cannot rely on at runtime is worse than no guarantee. One
  `getAutoCommit` call is the whole cost of making it real."
  [source f]
  (if (instance? Connection source)
    (f (assert-transaction! source))
    (with-transaction* source f)))

;; ---------------------------------------------------------------------------
;; Constraint violations
;; ---------------------------------------------------------------------------

(defn placeholders
  "`\"?, ?, ?\"` for an `IN` list of `n` values.

  Only the *number* of placeholders is derived from the collection — every
  value still travels as a bound parameter. This is not an exception to the
  no-interpolation rule; it is what makes an `IN` list obey it."
  [n]
  (str/join ", " (repeat n "?")))

(defn violation
  "Structured detail about a constraint violation, or nil if `t` is not one.

  The cause chain is walked because a deferred constraint fires at `commit`,
  and by then the driver's exception is wrapped by whatever was unwinding.
  Returning the constraint name — rather than a pre-baked domain error — keeps
  the decision about what a violation *means* with the repository that issued
  the statement, which is the only place that knows."
  [t]
  (loop [^Throwable cause t]
    (when cause
      (if-let [server (and (instance? PSQLException cause)
                           (.getServerErrorMessage ^PSQLException cause))]
        {:sql-state  (.getSQLState ^PSQLException cause)
         :constraint (.getConstraint ^ServerErrorMessage server)
         :table      (.getTable ^ServerErrorMessage server)}
        (recur (.getCause cause))))))

(def sql-states
  "The SQLSTATE codes CloFin translates into domain outcomes. Anything else is
  a defect and is left to surface as an internal error with a correlation id."
  {:unique-violation      "23505"
   :foreign-key-violation "23503"
   :check-violation       "23514"
   :not-null-violation    "23502"})

(defn tolerating-violation
  "Run `(f tx)` inside a **savepoint**; on a constraint violation, roll back to
  the savepoint and return `(on-violation v)` with the structured detail.

  This exists because catching the exception is **not enough**. PostgreSQL
  aborts the entire transaction on a constraint violation: every statement after
  one fails with `current transaction is aborted, commands ignored until end of
  transaction block`, so code that catches a duplicate-key error and carries on
  reading is code whose next query fails for a reason that has nothing to do
  with what it was asked. A savepoint is the only correct way to express
  *\"insert, and if it collides, carry on in the same transaction\"*.

  The distinction matters wherever a violation is an *expected outcome* rather
  than a failure — a duplicate scheme response is the normal case in the world
  settlement simulates. Where a violation should abort the unit of work, catch
  it and throw a domain error instead; the transaction is going away regardless
  and a savepoint would only add ceremony.

  Anything that is not a constraint violation is rethrown after the savepoint is
  released, so a defect still surfaces as one."
  [tx f on-violation]
  (let [^Connection conn tx
        savepoint (.setSavepoint conn)]
    (try
      (let [result (f conn)]
        (.releaseSavepoint conn savepoint)
        result)
      (catch Exception t
        (if-let [v (violation t)]
          (do (.rollback conn savepoint)
              (on-violation v))
          (do (try (.rollback conn savepoint) (catch Exception _))
              (throw t)))))))

;; ---------------------------------------------------------------------------
;; Health
;; ---------------------------------------------------------------------------

(defn reachable?
  "True when the database answers a trivial query. Used by the readiness probe."
  [source]
  (try
    (= 1 (:ok (query-one source ["select 1 as ok"])))
    (catch Exception _ false)))
