(ns clofin.tools.screening-list
  "Load and retire versions of the synthetic screening list.

      clojure -M:screening-list load <edn-file> [--replacing <version>]
      clojure -M:screening-list retire <version>
      make load-screening-list LIST=<edn-file>        (LIST defaults to the shipped list)

  **A list is loaded by this tool, never by a client and never by a
  migration** (docs/briefs/017-TASK-screening-and-cases.md, A-5). A client that
  could load the list it is screened against would make C-07 unenforceable, so
  there is no route that writes one; and a migration is immutable history while
  a list is reference data that is loaded, retired and replaced. This
  namespace lives on `tools/`, which is not on the service's classpath — the
  running service cannot load a list because the code that does is not there.

  **Exactly one list is accepted at a time.** `load` of a second version while
  one is accepted is refused unless `--replacing` names the accepted version,
  in which case the old is retired and the new loaded **in one transaction** —
  never a moment with two accepted lists, never a moment with none. Loaders are
  serialised by a table lock, so two concurrent loads cannot both find the
  table empty of accepted versions. (The schema itself does not forbid two
  accepted rows; `clofin.screening.repository/accepted-list` treats two as a
  defect and refuses to screen against either.)

  **Loading is a deployment act, recorded by the list tables, not by the
  tenant audit trail.** `screening_list.loaded_at`, `.source` and
  `.retired_at`, on append-only rows, are the record; `audit_event` requires an
  organisation and a list belongs to none (docs/COMPLIANCE.md C-07, evidence).

  **Synthetic.** Every list this tool loads is CloFin-defined synthetic data;
  matching is exact; no claim about real-world screening quality is made."
  (:require [clofin.config :as config]
            [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.screening.list :as screening-list]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def refusal-reasons
  "Why the tool refuses an act on the list table, beside the list-shape
  refusals of `clofin.screening.list/refusal-reasons`."
  (sorted-set "a-list-is-already-accepted" "replacing-is-not-the-accepted-list"
              "version-already-loaded" "unknown-version" "already-retired"
              "unreadable-list-file"))

(defn- refuse!
  [reason message data]
  (err/fail! :unprocessable message (assoc data :reason (refusal-reasons reason))))

(defn read-list-file
  "Read the EDN list at `path`. `clojure.edn` reads data only — no evaluation,
  no reader tags — so a list file cannot run code in the loader.

  A path that names no readable file, or a file that is not EDN, is refused
  naming the path — never a stack trace an operator has to read."
  [path]
  (let [text (try (slurp path)
                  (catch java.io.IOException e
                    (err/fail! :unprocessable (str "No readable screening list file at " path
                                                   " (" (ex-message e) ")")
                               {:reason "unreadable-list-file" :path path})))]
    (try (edn/read-string text)
         (catch RuntimeException e
           (err/fail! :unprocessable (str "The file at " path " is not an EDN screening list: "
                                          (ex-message e))
                      {:reason "unreadable-list-file" :path path})))))

(defn- accepted-versions
  [tx]
  (mapv :version (db/query tx ["select version from screening_list
                                 where retired_at is null order by version"])))

(defn- lock-lists!
  "Serialise loaders. `share row exclusive` conflicts with itself and with every
  write to the table, and not with the plain reads a submission makes, so
  screening is never blocked by a load — it sees the old list or the new one."
  [tx]
  (db/execute! tx ["lock table screening_list in share row exclusive mode"]))

(defn retire-version!
  "Retire `version`, which must be accepted. Under the caller's transaction."
  [tx version]
  (let [row (db/query-one tx ["select version, retired_at from screening_list where version = ?"
                              version])]
    (when-not row
      (refuse! "unknown-version" (str "No screening list version " version " is loaded")
               {:version version}))
    (when (:retired-at row)
      (refuse! "already-retired" (str "Screening list " version " is already retired")
               {:version version}))
    (db/execute! tx ["update screening_list set retired_at = now()
                       where version = ? and retired_at is null" version])
    version))

(defn insert-version!
  "Write a validated list — the version row, its entries and their rules —
  under the caller's transaction."
  [tx {:keys [version entries]} source]
  (db/execute! tx ["insert into screening_list (version, source, entry_count) values (?, ?, ?)"
                   version source (count entries)])
  (doseq [{:keys [id rules]} entries]
    (db/execute! tx ["insert into screening_entry (list_version, id) values (?, ?)" version id])
    (doseq [[position {:keys [field operator value]}] (map-indexed vector rules)]
      (db/execute! tx ["insert into screening_rule
                          (list_version, entry_id, position, field, operator, value)
                        values (?, ?, ?, ?, ?, ?)"
                       version id position (name field) (name operator) value])))
  version)

(defn load-list!
  "Validate `list` and make it the accepted version. Returns
  `{:version … :entry-count … :retired …}`.

  Refused before anything is written: a list `clofin.screening.list/validate`
  refuses; a version already loaded (versions are immutable — a changed list is
  a new version); a second list while one is accepted, unless `replacing` names
  it; a `replacing` that is not the accepted version.

  With `replacing`, the retirement and the load are one transaction: if the
  load fails after the retirement, neither survives."
  [pool list {:keys [source replacing]}]
  (let [validated (screening-list/validate list)
        version (:version validated)]
    (when (str/blank? source)
      (err/invalid! "A list is loaded from a source, which is recorded with it" {}))
    (db/with-transaction [tx pool]
      (lock-lists! tx)
      (when (db/query-one tx ["select 1 as present from screening_list where version = ?" version])
        (refuse! "version-already-loaded"
                 (str "Screening list " version " is already loaded; a changed list is a new version")
                 {:version version}))
      (let [accepted (accepted-versions tx)]
        (cond
          (and (seq accepted) (nil? replacing))
          (refuse! "a-list-is-already-accepted"
                   (str "Screening list " (str/join ", " accepted) " is accepted; exactly one list is "
                        "accepted at a time — load with --replacing " (first accepted)
                        " to retire it and load " version " in one transaction")
                   {:accepted accepted :version version})

          (and replacing (not= [replacing] accepted))
          (refuse! "replacing-is-not-the-accepted-list"
                   (str "--replacing " replacing " does not name the accepted list ("
                        (if (seq accepted) (str/join ", " accepted) "none is accepted") ")")
                   {:replacing replacing :accepted accepted}))
        (when replacing (retire-version! tx replacing))
        (insert-version! tx validated source)
        {:version version :entry-count (count (:entries validated)) :retired replacing}))))

(defn retire!
  "Retire the accepted `version`, leaving no list accepted. Every submission is
  then refused `422 no-screening-list-accepted` until a list is loaded: an
  unconfigured screen is not an unsupervised one."
  [pool version]
  (db/with-transaction [tx pool]
    (lock-lists! tx)
    (retire-version! tx version)))

(defn- usage []
  (str "usage: clojure -M:screening-list load <edn-file> [--replacing <version>]\n"
       "       clojure -M:screening-list retire <version>"))

(defn- parse-args
  [args]
  (let [[command target & more] args
        opts (apply hash-map more)]
    (cond
      (and (= "load" command) target (every? #{"--replacing"} (keys opts)))
      {:command :load :path target :replacing (get opts "--replacing")}

      (and (= "retire" command) target (empty? more))
      {:command :retire :version target}

      :else nil)))

(defn -main
  [& args]
  (let [parsed (parse-args args)]
    (when-not parsed
      (binding [*out* *err*] (println (usage)))
      (System/exit 2))
    (let [pool (db/open-pool (assoc (:db (config/load-config)) :pool-size 2))
          code (try
                 (case (:command parsed)
                   :load
                   (let [{:keys [version entry-count retired]}
                         (load-list! pool (read-list-file (:path parsed))
                                     {:source (:path parsed) :replacing (:replacing parsed)})]
                     (println (str "Loaded screening list " version " (" entry-count " entries)"
                                   (when retired (str "; retired " retired))
                                   ". Synthetic list, exact matching."))
                     0)
                   :retire
                   (let [version (retire! pool (:version parsed))]
                     (println (str "Retired screening list " version
                                   ". No list is accepted: every submission is refused until one is loaded."))
                     0))
                 (catch clojure.lang.ExceptionInfo t
                   (binding [*out* *err*]
                     (println (str "Refused: " (ex-message t)))
                     (when-let [reason (:reason (ex-data t))] (println (str "reason: " reason))))
                   1)
                 (finally (db/close-pool! pool)))]
      (shutdown-agents)
      (System/exit code))))
