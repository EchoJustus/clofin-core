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
  4. The port is refused if anything already answers on it, a fresh **instance
     id** is minted for the run and passed to the child, and the child's
     liveness and echoed identity are checked before any `200` is accepted and
     again before every file is written.
  5. The live service's `GET /readyz` reports its applied schema version, and
     that is compared with the last entry in the worktree's
     `resources/migrations/index.txt`.

  Steps 4 and 5 are the ones worth keeping. Steps 1–3 make the SHA correct;
  4 and 5 are the checks that fail when an assumption behind them is false —
  the shape standing lesson **L-13** asks for, where the precondition is
  enforced at runtime rather than documented and hoped for.

  ## What identity is, and what it is not

  Step 5 used to be the whole of it, and it was not enough: **a schema version
  is a check, not an identity**. The release audit started an unrelated local
  responder reporting schema `0013`, asked this namespace to spawn `/bin/false`
  onto that port, and watched the harness accept the stranger's `200` — the
  child had exited 1 and was never asked (finding **2C-006**, blocking;
  standing lesson **L-19**).

  So a capture binds to **the process it started**. The instance id is minted
  here, after the stranger would already have been listening, so no process the
  harness did not spawn can echo it. Both `instanceId` and `sourceCommit` are
  the service's own answers — self-reported, and `GET /` says so. The harness
  does not claim they attest anything about the bytes running; it claims
  something narrower and sufficient: *the process that answered is the one this
  run spawned from the worktree it verified clean at that commit.*

  `CLOFIN_SOURCE_COMMIT` is passed to the child for the same reason, and it
  closes a second hole. A child process inherits its parent's environment, and
  `make capture-trace` exports `CLOFIN_SOURCE_COMMIT` as the *harness's* own
  `HEAD` — so the tagged commit's service inherited it and reported `main`'s
  SHA under `sourceCommit`, which is the confidently-wrong value the paragraph
  above says the harness must never produce. It is now set explicitly from the
  commit under capture, and the check refuses if what comes back is anything
  else.

  ## What it deliberately will not do

  There is no `--base-url` that attaches to a stack someone else started.
  Nothing about such a stack can be resolved: not its commit, not its
  cleanliness, not whether it was rebuilt since it started. Offering the option
  would mean offering a bundle whose stamp is a guess, and every consumer
  downstream treats the stamp as fact."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
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

(defn env-for
  "The environment the child is started with.

  A child inherits its parent's environment, so every variable that decides
  what the child *says about itself* is set here rather than left to whatever
  the harness happened to be started with. `CLOFIN_SOURCE_COMMIT` is the case
  that bit: `make capture-trace` exports the harness's own `HEAD`, and an
  unset key here meant the captured commit's service inherited it and reported
  `main`'s SHA."
  [{:keys [url user password]} port {:keys [instance-id source-commit]}]
  (cond-> {"CLOFIN_DB_URL" url
           "CLOFIN_DB_USER" user
           "CLOFIN_DB_PASSWORD" password
           "CLOFIN_HTTP_HOST" "127.0.0.1"
           "CLOFIN_HTTP_PORT" (str port)
           "CLOFIN_MIGRATE_ON_START" "false"
           "CLOFIN_ENV" "dev"}
    instance-id   (assoc "CLOFIN_INSTANCE_ID" (str instance-id))
    source-commit (assoc "CLOFIN_SOURCE_COMMIT" (str source-commit))))

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
                    :env (env-for db 0 nil)
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

(defn assert-port-free!
  "Refuse to spawn onto a port something is already answering on.

  The first half of binding a capture to its own process, and the cheapest: a
  stranger that never gets the chance to answer cannot be captured. Both
  `/readyz` and `/` are asked, because a process that answers either one is a
  process this harness would go on to interrogate.

  Refusing rather than reserving. Reserving the port and handing it to the
  child is a window between the two, and the identity check below is what
  actually closes the hole — this makes the ordinary case fail immediately and
  legibly instead of ten minutes later (finding **2C-006**, lesson **L-19**)."
  [port]
  (doseq [path ["/readyz" "/"]]
    (when-let [res (http-get (str "http://127.0.0.1:" port path))]
      (throw (ex-info (format (str "capture refuses: something is already answering GET %s on "
                                   "port %s (status %s). A capture binds to the process it "
                                   "starts, so nothing may be listening before it does — a "
                                   "process reporting the same schema version would otherwise "
                                   "have its behaviour stamped with the commit under capture. "
                                   "Stop it, or run the capture on a free port (--port).")
                              path port (:status res))
                      {:port port :path path :status (:status res)}))))
  :free)

(defn self-identifies?
  "Whether the commit in `worktree` can tell a caller which process it is.

  Asked of the **source**, not of the answer, and that is the whole point: a
  stranger on the port can withhold a field, but it cannot make the worktree's
  `GET /` handler stop rendering one. So this decides which of the two gates
  below applies, and the decision cannot be influenced by the thing being
  gated.

  `instanceId` reached `GET /` in `ref-2` (ADR-0027). Every earlier commit —
  `ref-1` among them, and it is the documented default of `make capture-trace`
  — renders service, description, environment, disclaimer and documentation and
  nothing else. ADR-0022 already settled the general form of this problem in so
  many words: `ref-1` predates any build stamp, so provenance is established
  from git rather than by asking the service, and *changing the source state to
  make it capturable captures a different source state*. Demanding an echo such
  a commit has no way to produce would not make the capture safer; it would end
  the capture, with a refusal that says the process answering is not the one
  this run started when it is exactly that process."
  [worktree]
  (let [handler (io/file worktree "src" "clofin" "api" "health.clj")]
    (and (.exists handler)
         (str/includes? (slurp handler) "\"instanceId\""))))

(defn- reported-identity
  "What the stack answering on `base-url` says it is.

  `nil` when `GET /` does not answer `200` at all; otherwise a map whose values
  may themselves be `nil`, because a service that reports neither field is a
  service that fails the comparison rather than one that skips it."
  [base-url]
  (when-let [res (http-get (str base-url "/"))]
    (when (= 200 (:status res))
      (let [body (try (json/read-str (str (:body res))) (catch Exception _ nil))]
        {:instance-id   (get body "instanceId")
         :source-commit (get body "sourceCommit")}))))

(defn assert-same-process!
  "The stack being captured must still be the process this run started.

  The child is asked first, of its `Process` handle rather than of the network:
  a dead child makes every answer on the port someone else's, whatever it says.
  Then `GET /` must answer `200`, because a harness that cannot see the service
  at all has established nothing.

  What happens next depends on whether the **commit under capture** can
  identify itself, which `self-identifies?` reads from the worktree:

  - **It can** — every commit from `ref-2` on. `GET /` must echo this run's
    instance id, minted after any process already listening had started, and
    the commit under capture, passed to the child explicitly rather than left
    to an inherited environment variable. This is the gate finding **2C-006**
    asked for: the previous version compared schema versions, which two
    unrelated processes share, and accepted a stranger's `200`.

  - **It cannot** — `ref-1` and earlier. There is no answer such a service can
    give that would bind it, so the harness does not pretend to have asked. The
    binding it does have is stated rather than implied: the port was proved free
    before the child was spawned (`assert-port-free!`), the child is alive, and
    the service is answering there. That is exclusion, not self-report, and it
    is weaker — a process that took the port in the window between those two
    moments would pass it. The run says so on stdout rather than leaving the
    operator to infer which of the two gates ran.

  Both echoed values are **self-reported**, and this function does not pretend
  otherwise: it establishes that the process answering is the one spawned from
  the worktree verified clean at that commit, which is what every downstream
  consumer of a stamp actually needs. It is called before each artifact is
  written as well as at start-up, because a child that dies halfway through a
  capture leaves a port a stranger can take."
  [{:keys [^Process process base-url instance-id source-commit self-identifies?]}]
  (when (and process (not (.isAlive process)))
    (throw (ex-info (str "capture refuses: the stack it started is no longer running, so nothing "
                         "answering on " base-url " can be attributed to it.")
                    {:base-url base-url :instance-id instance-id})))
  (let [reported (reported-identity base-url)]
    (when-not reported
      (throw (ex-info (str "capture refuses: GET / on " base-url " did not answer 200, so the "
                           "harness cannot establish which process is there.")
                      {:base-url base-url})))
    (if-not self-identifies?
      (do (println (format (str "capture: %s predates instanceId in GET / — identity is by port "
                                "exclusion (the port was free before this run spawned its child, "
                                "and that child is alive), not by self-report")
                           source-commit))
          (assoc reported :identity-established-by :port-exclusion))
      (do
        (when-not (= instance-id (:instance-id reported))
          (throw (ex-info (format (str "capture refuses: the stack answering on %s reports instance id "
                                       "%s, and this run started one with %s. A schema version is a "
                                       "check, not an identity: the process answering is not the "
                                       "process this capture started.")
                                  base-url (pr-str (:instance-id reported)) (pr-str instance-id))
                          {:base-url base-url :reported (:instance-id reported)
                           :expected instance-id})))
        (when-not (= source-commit (:source-commit reported))
          (throw (ex-info (format (str "capture refuses: the stack answering on %s reports source "
                                       "commit %s, and the commit under capture is %s.")
                                  base-url (pr-str (:source-commit reported)) (pr-str source-commit))
                          {:base-url base-url :reported (:source-commit reported)
                           :expected source-commit})))
        (assoc reported :identity-established-by :instance-id)))))

(defn start!
  "Start the service from the worktree and wait until it is ready.

  Returns `{:process :base-url :readyz :instance-id :source-commit}`. A stack
  that never becomes ready is a stopped run with the log named, not a capture
  against a half-started service.

  **Liveness is asked before any `200` is looked at.** That order is the
  finding: the previous version accepted a success on `/readyz` and only asked
  about the child if none arrived, so a `/bin/false` that had already exited 1
  was captured as a running stack because something else was on the port
  (**2C-006**). Once a `200` does arrive it is not enough on its own either —
  `assert-same-process!` has to agree that the answer came from this run's
  child."
  [{:keys [worktree db port clojure-bin log-file timeout-seconds
           instance-id source-commit]
    :or   {timeout-seconds 120}}]
  (when (str/blank? (str instance-id))
    (throw (ex-info "capture refuses: a capture run must carry an instance id."
                    {:worktree worktree})))
  (let [base-url (str "http://127.0.0.1:" port)
        p (process {:dir worktree
                    :command [clojure-bin "-M:run"]
                    :env (env-for db port {:instance-id instance-id
                                           :source-commit source-commit})
                    :log-file log-file})
        deadline (+ (System/currentTimeMillis) (* 1000 timeout-seconds))]
    (loop []
      (let [alive? (.isAlive p)
            res (http-get (str base-url "/readyz"))]
        (cond
          ;; Sampled *before* the request, so an answer that arrived while the
          ;; child was already gone can never be the one that is accepted.
          (not alive?)
          (throw (ex-info (format (str "capture refuses: the stack from %s exited before becoming "
                                       "ready%s. See %s")
                                  worktree
                                  (if (and res (= 200 (:status res)))
                                    (str ", and something else is answering on port " port
                                         " — that answer is not this capture's to record")
                                    "")
                                  log-file)
                          {:worktree worktree :log log-file :port port
                           :foreign-response (when res (:status res))}))

          (and res (= 200 (:status res)))
          (let [running {:process p :base-url base-url :readyz (:body res)
                         :instance-id instance-id :source-commit source-commit
                         ;; Decided once, from the worktree, and carried — so
                         ;; every later call gates the same way as this one and
                         ;; the answer can never come from the thing being
                         ;; gated.
                         :self-identifies? (self-identifies? worktree)}]
            (try
              (assert-same-process! running)
              (catch Exception e
                (.destroy p)
                (throw e)))
            running)

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

  **A schema version is a check, not an identity**, and saying otherwise is the
  correction this docstring carries: any process that has applied the same
  migrations reports the same string, so agreement here narrows what could be
  answering and never names it. What names it is `assert-same-process!`. This
  check keeps its own value beside that one — it is the thing that fails when
  the right process is connected to the wrong database.

  `readyz` is the service's own answer about the database it is connected to;
  the index is the commit's own list of migrations. They are produced by
  different things, which is the only reason comparing them is worth anything
  (standing lesson **L-16**: when two copies of a claim can only agree,
  agreement proves nothing)."
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
