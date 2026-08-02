(ns clofin.db.migrate
  "Forward-only SQL migration runner.

  Migrations are numbered `.sql` files in `resources/migrations/`, applied in
  lexicographic order, each in its own transaction. Applied versions are
  recorded with a SHA-256 checksum; if a recorded checksum no longer matches
  the file on disk, start-up fails rather than allowing environments to diverge
  silently.

  There are no `down` migrations. See
  docs/ADR/0009-forward-only-sql-migrations.md.

      clojure -M -m clofin.db.migrate          apply pending migrations
      clojure -M -m clofin.db.migrate status   report applied and pending"
  (:require [clofin.config :as config]
            [clofin.db.core :as db]
            [clofin.error :as err]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.security MessageDigest]
           [java.sql Connection]
           [java.util HexFormat]))

(set! *warn-on-reflection* true)

(def ^:private migrations-index
  "Migrations are listed explicitly rather than discovered by scanning the
  classpath: a directory listing is not portable across a filesystem, a jar and
  a container image, and an accidentally-included file must never become a
  migration."
  "migrations/index.txt")

(def ^:private advisory-lock-key
  "Arbitrary but stable key for the PostgreSQL advisory lock that serialises
  migration across concurrently starting instances."
  8027461195360211003)

(defn- sha256 [^String s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s "UTF-8"))
      (->> (.formatHex (HexFormat/of)))))

(defn- parse-name
  "`0003-add-idempotency-keys.sql` -> {:version \"0003\" :description \"add idempotency keys\"}"
  [filename]
  (if-let [[_ version description] (re-matches #"(\d{4})-(.+)\.sql" filename)]
    {:version version
     :description (str/replace description \- \space)
     :filename filename}
    (err/invalid! (str "Migration filename must match NNNN-description.sql: " filename)
                  {:filename filename})))

(defn available
  "Every migration in the repository, in application order."
  []
  (let [index (or (io/resource migrations-index)
                  (err/invalid! (str "Missing " migrations-index
                                     " — every migration must be listed there")))]
    (->> (str/split-lines (slurp index))
         (map str/trim)
         (remove str/blank?)
         (remove #(str/starts-with? % "#"))
         (map (fn [filename]
                (let [resource (or (io/resource (str "migrations/" filename))
                                   (err/invalid! (str "Migration listed in index but not found: " filename)
                                                 {:filename filename}))
                      sql (slurp resource)]
                  (assoc (parse-name filename)
                         :sql sql
                         :checksum (sha256 sql)))))
         (sort-by :version)
         vec)))

(defn- ensure-registry!
  "Create the migration registry itself. This is the one piece of schema that
  cannot be created by a migration."
  [conn]
  (db/execute! conn
    ["create table if not exists schema_migration (
        version      text        primary key,
        description  text        not null,
        checksum     text        not null,
        applied_at   timestamptz not null default now()
      )"]))

(defn applied
  "Migrations already recorded as applied, keyed by version."
  [source]
  (into {}
        (map (juxt :version identity))
        (db/query source ["select version, description, checksum, applied_at
                           from schema_migration order by version"])))

(defn- verify-checksums!
  "Fail if a migration that has already been applied has since been edited."
  [applied-by-version migrations]
  (doseq [{:keys [version checksum filename]} migrations]
    (when-let [record (get applied-by-version version)]
      (when-not (= checksum (:checksum record))
        (err/conflict!
         (str "Migration " filename " has changed since it was applied. "
              "Applied migrations are immutable — add a new migration instead.")
         {:version version
          :applied-checksum (:checksum record)
          :current-checksum checksum})))))

(defn- split-statements
  "Split a migration into individual statements on semicolons at end of line.

  Deliberately simple, and sufficient because CloFin's migrations are DDL. A
  statement containing a semicolon inside a string literal or a function body
  must be wrapped in `$$ ... $$` and kept on one logical block, which the
  `$$`-awareness below handles."
  [sql]
  (loop [lines (str/split-lines sql)
         current []
         out []
         in-dollar? false]
    (if-let [line (first lines)]
      (let [dollar-count (count (re-seq #"\$\$" line))
            now-in-dollar? (if (odd? dollar-count) (not in-dollar?) in-dollar?)
            current' (conj current line)]
        (if (and (not now-in-dollar?) (str/ends-with? (str/trimr line) ";"))
          (recur (rest lines) [] (conj out (str/join "\n" current')) now-in-dollar?)
          (recur (rest lines) current' out now-in-dollar?)))
      (let [tail (str/trim (str/join "\n" current))]
        (cond-> out (not (str/blank? tail)) (conj tail))))))

(defn- apply-migration!
  [pool {:keys [version description sql checksum filename]}]
  (log/info "Applying migration" filename)
  (db/with-transaction [tx pool]
    (doseq [statement (split-statements sql)
            :when (not (str/blank? (str/replace statement #"--[^\n]*" "")))]
      (db/execute! tx [statement]))
    (db/execute! tx ["insert into schema_migration (version, description, checksum)
                      values (?, ?, ?)"
                     version description checksum]))
  (log/info "Applied migration" filename))

(defn migrate!
  "Apply every pending migration. Returns the versions applied by this call.

  A session-scoped advisory lock is held for the duration, so two instances
  starting at the same moment cannot both apply the same migration. Each
  migration then commits in its own transaction: a failure part-way through
  leaves the schema at the last good version rather than discarding the
  migrations that already succeeded."
  [pool]
  (db/with-transaction [tx pool]
    (ensure-registry! tx))
  (db/with-connection [^Connection lock-conn pool]
    (db/query lock-conn ["select pg_advisory_lock(?)" advisory-lock-key])
    (try
      (let [migrations (available)
            already    (applied lock-conn)]
        (verify-checksums! already migrations)
        (let [pending (remove #(contains? already (:version %)) migrations)]
          (if (seq pending)
            (do (doseq [m pending] (apply-migration! pool m))
                (mapv :version pending))
            (do (log/info "Database schema is up to date")
                []))))
      (finally
        (db/query lock-conn ["select pg_advisory_unlock(?)" advisory-lock-key])))))

(defn status
  "Applied and pending migrations, for reporting."
  [pool]
  (db/with-transaction [tx pool]
    (ensure-registry! tx))
  (let [migrations (available)
        already    (applied pool)]
    {:applied (->> migrations
                   (filter #(contains? already (:version %)))
                   (mapv (fn [m] (assoc (select-keys m [:version :description])
                                        :applied-at (get-in already [(:version m) :applied-at])))))
     :pending (->> migrations
                   (remove #(contains? already (:version %)))
                   (mapv #(select-keys % [:version :description])))
     :current-version (->> migrations
                           (filter #(contains? already (:version %)))
                           (map :version)
                           sort
                           last)}))

(defn current-version
  "Highest applied migration version, or nil when the database is empty."
  [source]
  (try
    (:version (db/query-one source ["select max(version) as version from schema_migration"]))
    (catch Exception _ nil)))

(defn -main
  "Command-line entrypoint used by `make migrate` and `make migrate-status`."
  [& args]
  (let [cfg  (config/load-config)
        pool (db/open-pool (:db cfg))]
    (try
      (if (= "status" (first args))
        (let [{:keys [applied pending current-version]} (status pool)]
          (println "Schema version:" (or current-version "<empty>"))
          (println "Applied:" (count applied))
          (doseq [m applied] (println "  ✓" (:version m) (:description m)))
          (println "Pending:" (count pending))
          (doseq [m pending] (println "  ·" (:version m) (:description m))))
        (let [versions (migrate! pool)]
          (if (seq versions)
            (println "Applied" (count versions) "migration(s):" (str/join ", " versions))
            (println "Database schema is up to date."))))
      (finally
        (db/close-pool! pool)
        (shutdown-agents)))))
