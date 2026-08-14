(ns clofin.tools.capture.stack
  "Bringing up the stack the capture runs against, from a resolved commit.

  ## How the harness knows what it captured

  This is the design decision the brief left to the harness, so it is stated
  here rather than inferred from the code.

  The obvious approaches do not work. Asking the operator is a value typed by
  a human, which is the thing provenance exists to eliminate. Reading
  `git rev-parse HEAD` in the working directory answers a different question —
  it names the *harness's* checkout, and the whole point is that the harness
  runs from `main` while the stack runs from a tag, so that value would be
  confidently wrong. Asking the service is impossible: `ref-1` predates any
  notion of a build stamp and `GET /` reports no commit, and the source state
  being captured cannot be changed to make capture easier without capturing a
  different state.

  So the harness does not discover the SHA — it **establishes** it:

  1. `git rev-parse <ref>^{commit}` resolves the commit, or the run stops
     (AC-2). Nothing later is attempted with an unresolved ref.
  2. A **detached git worktree** is created at exactly that commit. A worktree
     checked out from a commit object is clean by construction; it is verified
     clean anyway, because \"by construction\" is how a stale reused directory
     goes unnoticed.
  3. Migrations and the service are run **from inside that worktree**, by
     path. The running process is a child of this one, started from a
     directory whose `HEAD` was just verified.
  4. The live service's `GET /readyz` reports its applied schema version, and
     that is compared with the last entry in the worktree's
     `resources/migrations/index.txt`. If a stack somehow answers on the port
     that is not the one just started, the two disagree and the run stops.

  Step 4 is the one worth keeping. Steps 1–3 make the SHA correct; step 4 is
  the check that fails when an assumption behind them is false — the shape
  standing lesson **L-13** asks for, where the precondition is enforced at
  runtime rather than documented and hoped for.

  ## What it deliberately will not do

  There is no `--base-url` that attaches to a stack someone else started.
  Nothing about such a stack can be resolved: not its commit, not its
  cleanliness, not whether it was rebuilt since it started. Offering the option
  would mean offering a bundle whose stamp is a guess, and every consumer
  downstream treats the stamp as fact."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn- sh!
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (format "capture refuses: `%s` failed (exit %s): %s"
                              (str/join " " (remove map? args)) exit (str/trim (str err)))
                      {:command (vec (remove map? args)) :exit exit
                       :out (str/trim (str out)) :stderr (str/trim (str err))})))
    (str/trim (str out))))

;; ---------------------------------------------------------------------------
;; The worktree
;; ---------------------------------------------------------------------------

(defn worktree!
  "A detached worktree of `commit` under `dir`, created or reused.

  Reuse is allowed because a capture is often run several times while a
  scenario is being written and a fresh checkout each time is slow; it is
  verified rather than assumed, both that `HEAD` is the commit asked for and
  that nothing in the tree has been edited. An edited worktree is refused
  outright: the bundle would say `ref-1` and the behaviour would be whatever
  somebody was trying out."
  [root commit dir]
  (let [dir (io/file dir)]
    ;; `.git` in a linked worktree is a *file* pointing back at the main
    ;; repository's admin directory, not a directory of its own.
    (if (.exists (io/file dir ".git"))
      (let [head (sh! "git" "-C" (str dir) "rev-parse" "HEAD")]
        (when-not (= head commit)
          (throw (ex-info (format (str "capture refuses: the worktree at %s is checked out at %s, "
                                       "not at %s. Remove it (`git worktree remove --force %s`) "
                                       "and run the capture again.")
                                  dir head commit dir)
                          {:worktree (str dir) :head head :expected commit}))))
      (do (io/make-parents (io/file dir "x"))
          (sh! "git" "-C" (str root) "worktree" "add" "--detach" (str dir) commit)))
    (let [dirty (sh! "git" "-C" (str dir) "status" "--porcelain")]
      (when-not (str/blank? dirty)
        (throw (ex-info (format (str "capture refuses: the worktree at %s has uncommitted changes, "
                                     "so what would run is not %s:\n%s")
                                dir commit dirty)
                        {:worktree (str dir) :commit commit :status dirty}))))
    (str dir)))

(defn migration-head
  "The last migration listed in the worktree's index — `\"0011\"` for `ref-1`.

  Read from the index file rather than from a directory listing, for the same
  reason `clofin.db.migrate` reads it: a listing is not the order, and the
  order is the thing."
  [worktree]
  (let [index (io/file worktree "resources/migrations/index.txt")]
    (when-not (.isFile index)
      (throw (ex-info (str "capture refuses: no migration index at " index)
                      {:path (str index)})))
    (or (->> (str/split-lines (slurp index))
             (map str/trim)
             (remove #(or (str/blank? %) (str/starts-with? % "#")))
             (keep #(second (re-matches #"(\d{4})-.*\.sql" %)))
             last)
        (throw (ex-info (str "capture refuses: " index " lists no migrations.")
                        {:path (str index)})))))

;; ---------------------------------------------------------------------------
;; Processes
;; ---------------------------------------------------------------------------

(defn- env-for
  [{:keys [url user password]} port]
  {"CLOFIN_DB_URL" url
   "CLOFIN_DB_USER" user
   "CLOFIN_DB_PASSWORD" password
   "CLOFIN_HTTP_HOST" "127.0.0.1"
   "CLOFIN_HTTP_PORT" (str port)
   "CLOFIN_MIGRATE_ON_START" "false"
   "CLOFIN_ENV" "dev"})

(defn- process
  ^Process [{:keys [dir command env log-file]}]
  (let [pb (ProcessBuilder. ^java.util.List (vec command))]
    (.directory pb (io/file dir))
    (doto (.environment pb) (.putAll (java.util.HashMap. ^java.util.Map env)))
    (.redirectErrorStream pb true)
    (when log-file
      (io/make-parents (io/file log-file))
      (.redirectOutput pb (ProcessBuilder$Redirect/appendTo (io/file log-file))))
    (.start pb)))

(defn migrate!
  "Apply the captured commit's migrations to the capture database.

  Run as the commit's own code — `clojure -M -m clofin.db.migrate` inside the
  worktree — rather than by this harness reading the SQL files. The migration
  runner is part of what `ref-1` is, checksums included, and a capture that
  applied the schema some other way would be capturing a state the tag never
  produces."
  [{:keys [worktree db clojure-bin log-file]}]
  (let [p (process {:dir worktree
                    :command [clojure-bin "-M" "-m" "clofin.db.migrate"]
                    :env (env-for db 0)
                    :log-file log-file})
        exit (.waitFor p)]
    (when-not (zero? exit)
      (throw (ex-info (format (str "capture refuses: migrating the capture database from %s "
                                   "failed (exit %s). See %s")
                              worktree exit log-file)
                      {:worktree worktree :exit exit :log log-file})))
    :migrated))

(defn- http-get
  [url]
  (try
    (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 2)) (.build))
          req    (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofSeconds 5))
                     (.GET)
                     (.build))
          res    (.send client req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode res) :body (.body res)})
    (catch Exception _ nil)))

(defn start!
  "Start the service from the worktree and wait until it is ready.

  Returns `{:process :base-url :readyz}`. A stack that never becomes ready is
  a stopped run with the log named, not a capture against a half-started
  service."
  [{:keys [worktree db port clojure-bin log-file timeout-seconds]
    :or   {timeout-seconds 120}}]
  (let [base-url (str "http://127.0.0.1:" port)
        p (process {:dir worktree
                    :command [clojure-bin "-M:run"]
                    :env (env-for db port)
                    :log-file log-file})
        deadline (+ (System/currentTimeMillis) (* 1000 timeout-seconds))]
    (loop []
      (let [res (http-get (str base-url "/readyz"))]
        (cond
          (and res (= 200 (:status res)))
          {:process p :base-url base-url :readyz (:body res)}

          (not (.isAlive p))
          (throw (ex-info (format "capture refuses: the stack from %s exited before becoming ready. See %s"
                                  worktree log-file)
                          {:worktree worktree :log log-file}))

          (> (System/currentTimeMillis) deadline)
          (do (.destroy p)
              (throw (ex-info (format (str "capture refuses: the stack from %s did not become ready "
                                           "within %ss. See %s")
                                      worktree timeout-seconds log-file)
                              {:worktree worktree :log log-file})))

          :else (do (Thread/sleep 1000) (recur)))))))

(defn stop!
  "Stop the stack, politely and then not."
  [{:keys [^Process process]}]
  (when (and process (.isAlive process))
    (.destroy process)
    (when-not (.waitFor process 15 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly process)))
  :stopped)

(defn assert-formatter-matches!
  "The harness's money formatter must be the captured commit's money formatter.

  A bundle carries `display` strings — `\"SGD 3750.00\"` — because
  `clofin-trace` computes nothing and somebody has to put the decimal point
  in. They are produced by `clofin.money/format-amount` **as the harness has
  it**, which is `main`'s copy, while the values being formatted came from the
  tag's. Today those files are identical. The day they are not, a bundle would
  render a captured amount through a formatter that captured commit never had,
  and nothing on the page would say so.

  So it is checked rather than assumed, and the check is a refusal: if the two
  copies differ, the operator decides what to do about it, and the decision is
  made before the artifact exists rather than after it is published."
  [root worktree]
  (let [relative "src/clofin/money.clj"
        theirs (io/file worktree relative)
        ours   (io/file root relative)]
    (when-not (and (.isFile theirs) (.isFile ours))
      (throw (ex-info (str "capture refuses: cannot compare " relative
                           " between the harness and the captured commit.")
                      {:harness (str ours) :captured (str theirs)})))
    (let [a (slurp ours) b (slurp theirs)]
      (when-not (= a b)
        (throw (ex-info (str "capture refuses: " relative " differs between the harness and the "
                             "captured commit, so the harness cannot render that commit's amounts "
                             "with that commit's formatter.")
                        {:harness (str ours) :captured (str theirs)})))
      :same)))

(defn assert-schema-matches!
  "The running stack's applied schema version must be the captured commit's.

  The independent check described in this namespace's docstring. `readyz` is
  the service's own answer about the database it is connected to; the index is
  the commit's own list of migrations. They are produced by different things,
  which is the only reason comparing them is worth anything (standing lesson
  **L-16**: when two copies of a claim can only agree, agreement proves
  nothing)."
  [readyz-body worktree]
  (let [reported (second (re-find #"\"schemaVersion\"\s*:\s*\"([^\"]+)\"" (str readyz-body)))
        expected (migration-head worktree)]
    (when-not (= reported expected)
      (throw (ex-info (format (str "capture refuses: the stack answering on this port reports schema "
                                   "version %s, and the captured commit's migration index ends at %s. "
                                   "The service being captured is not the one that was started.")
                              (pr-str reported) (pr-str expected))
                      {:reported reported :expected expected})))
    reported))
