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
           [java.time Instant]
           [javax.sql DataSource]))

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

;; ---------------------------------------------------------------------------
;; Health
;; ---------------------------------------------------------------------------

(defn reachable?
  "True when the database answers a trivial query. Used by the readiness probe."
  [source]
  (try
    (= 1 (:ok (query-one source ["select 1 as ok"])))
    (catch Exception _ false)))
