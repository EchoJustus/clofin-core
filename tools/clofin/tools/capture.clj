(ns clofin.tools.capture
  "`make capture-trace` — run the three scenarios against a stack built from a
  tagged commit and write one stamped bundle per scenario.

  This is the harness [ADR-0020](../../../docs/ADR/0020-two-repositories-and-the-generate-replay-rules.md)
  names as the reason `clofin-trace` can sit outside release-audit scope
  without that being a hole: the trace repository owns no truth, and
  everything it displays is captured output of an audited commit produced
  *here*, inside the audited repository. Audit the harness and the
  walkthrough's honesty is inherited.

  ## What one run does

  1. Resolves the source commit from a git ref, and refuses if it cannot
     (AC-2). See `clofin.tools.capture.provenance`.
  2. Reads the tag's recorded release-audit coverage, and refuses if there is
     none. `ref-1`'s audit was **partial**, and a walkthrough that showed the
     SHA without that qualifier would be standing lesson **L-14** in the
     project's most public artifact.
  3. Creates a detached worktree at the commit, migrates a scratch database
     with the commit's own migration runner, and starts the commit's own
     service. See `clofin.tools.capture.stack` for why the SHA is established
     rather than discovered.
  4. Captures `GET /` as a fixture — the scope statement, byte for byte,
     never transcribed.
  5. Runs each scenario, recording every request and response, then reads the
     journal, its lines and the audit trail straight out of the database.
  6. Writes each bundle through the one function that validates the stamp
     first (`clofin.tools.capture.bundle/write!`), plus a manifest.

  ## Running it

      make capture-trace                       # ref-1, into target/capture
      make capture-trace CAPTURE_REF=ref-2
      make capture-trace CAPTURE_OUT=/tmp/x

  A local `clojure` CLI and a reachable PostgreSQL are needed: the harness
  starts the captured commit's own service, and there is no way to do that
  through a container that does not also decide which commit's `deps.edn`
  built it. The capture database is scratch and is dropped and recreated on
  every run, which is why its name must end in `_capture`."
  (:require [clofin.tools.capture.bundle :as bundle]
            [clofin.tools.capture.provenance :as prov]
            [clofin.tools.capture.quotations :as quotations]
            [clofin.tools.capture.recorder :as rec]
            [clofin.tools.capture.scenarios :as scenarios]
            [clofin.tools.capture.stack :as stack]
            [clofin.tools.capture.store :as store]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Options
;; ---------------------------------------------------------------------------

(defn- env [k default] (or (not-empty (System/getenv k)) default))

(defn options
  "Command-line options, over environment defaults, over built-in defaults."
  [args]
  (let [pairs (into {} (for [[k v] (partition 2 args)] [k v]))]
    {:ref         (get pairs "--ref"  (env "CAPTURE_REF" "ref-1"))
     :tag         (get pairs "--tag"  (env "CAPTURE_TAG" nil))
     :out         (get pairs "--out"  (env "CAPTURE_OUT" "target/capture"))
     :port        (parse-long (get pairs "--port" (env "CAPTURE_PORT" "8099")))
     :clojure-bin (get pairs "--clojure" (env "CAPTURE_CLOJURE" "clojure"))
     :db          {:url      (get pairs "--db-url"
                                  (env "CAPTURE_DB_URL"
                                       "jdbc:postgresql://localhost:5432/clofin_capture"))
                   :user     (env "CAPTURE_DB_USER" "clofin")
                   :password (env "CAPTURE_DB_PASSWORD" "clofin_local_dev_only")}}))

;; ---------------------------------------------------------------------------
;; The scope-statement fixture
;; ---------------------------------------------------------------------------

(defn capture-service-info
  "`GET /` — the response whose disclaimer the walkthrough renders.

  Captured, not transcribed, and kept as raw bytes as well as parsed data:
  `clofin-trace`'s `disclaimer-verbatim` check compares bytes, and a byte
  comparison against a value that has been through somebody's paraphrase is a
  comparison against the paraphrase."
  [base-url]
  (let [r    (rec/recorder {:base-url base-url})
        step (rec/request! r {:id "service-info"
                              :title "GET / — what this service says it is"
                              :method "GET" :path "/"
                              :expect-status 200})
        res  (:response step)
        disclaimer (get-in res [:body "disclaimer"])]
    (when (str/blank? (str disclaimer))
      (throw (ex-info (str "capture refuses: GET / carries no disclaimer, so there is no scope "
                           "statement to render in-frame.")
                      {:body (:body-raw res)})))
    {:status      (:status res)
     :headers     (:headers res)
     :body        (:body res)
     :body-raw    (:body-raw res)
     :body-sha256 (:body-sha256 res)
     :disclaimer  disclaimer}))

;; ---------------------------------------------------------------------------
;; One scenario
;; ---------------------------------------------------------------------------

(defn run-scenario
  "Run one scenario against the live stack and assemble its bundle."
  [{:keys [scenario base-url conn provenance service-info]}]
  (let [recorder (rec/recorder {:base-url base-url})
        outcome  ((:run scenario) {:rec recorder :conn conn :base-url base-url
                                   :people (:people scenario)})
        steps    (rec/steps recorder)
        org-id   (:organisation-id outcome)]
    (bundle/assemble
     {:scenario     scenario
      :provenance   provenance
      :steps        steps
      :organisation org-id
      :accounts     (store/accounts conn org-id)
      :journal      (store/journal conn org-id)
      :audit-events (store/audit-events conn org-id)
      :sand-table   (when-let [spec (:sand-table outcome)]
                      (let [table    (bundle/sand-table steps spec)
                            journal  (store/journal conn org-id)
                            accounts (store/accounts conn org-id)]
                        ;; AC-6, before the bundle exists: the table and the
                        ;; journal in the same bundle must be the same story.
                        (bundle/verify-against-journal! table journal accounts)
                        table))
      :service-info service-info})))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn capture!
  "Everything, in order, cleaning up the stack whatever happens."
  [{:keys [ref tag out port clojure-bin db]}]
  (let [root        (System/getProperty "user.dir")
        run         (prov/git-runner root)
        captured-at (Instant/now)
        base-stamp  (prov/stamp {:run run :root root :ref ref :tag tag
                                 :captured-at captured-at})
        commit      (:source-commit base-stamp)
        worktree    (stack/worktree! root commit
                                     (io/file out (str "stack-" (subs commit 0 7))))
        log-file    (str (io/file out "stack.log"))]
    (println (format "capture: %s -> %s (tag %s, %s)"
                     ref commit (:tag base-stamp)
                     (get-in base-stamp [:release-audit :label])))
    (println (format "capture: coverage read from %s (%s)"
                     (get-in base-stamp [:release-audit :source-ref])
                     (get-in base-stamp [:release-audit :source])))
    (stack/assert-formatter-matches! root worktree)
    (store/reset-schema! db)
    (stack/migrate! {:worktree worktree :db db :clojure-bin clojure-bin :log-file log-file})
    (let [running (stack/start! {:worktree worktree :db db :port port
                                 :clojure-bin clojure-bin :log-file log-file})]
      (try
        (let [applied (stack/assert-schema-matches! (:readyz running) worktree)
              stamp   (assoc base-stamp :schema-version-applied applied)
              base    (:base-url running)
              info    (capture-service-info base)]
          (with-open [conn (store/connect db)]
            (let [written
                  (doall
                   (for [scenario scenarios/all]
                     (let [_ (println "capture: running" (:id scenario))
                           b (run-scenario {:scenario scenario :base-url base :conn conn
                                            :provenance stamp :service-info info})
                           ;; Paths in the manifest are relative to the output
                           ;; root, because that is what a consumer resolves
                           ;; them against — a bare file name would make the
                           ;; manifest depend on where the reader happens to
                           ;; be standing.
                           name* (str "bundles/" (:id scenario) ".json")
                           w (bundle/write! {:path (io/file out name*)
                                             :bundle b
                                             :service-info info})]
                       (assoc w :id (:id scenario) :title (:title scenario) :name name*))))
                  fixture (bundle/write-fixture!
                           {:path (io/file out "service-info.json")
                            :provenance stamp
                            :service-info info})
                  quotes  (bundle/write-quotations!
                           {:path (io/file out "quotations.json")
                            :provenance stamp
                            :quotations (quotations/extract worktree commit)})]
              (bundle/write-manifest! {:path (io/file out "manifest.json")
                                       :provenance stamp
                                       :fixture (assoc fixture :name "service-info.json")
                                       :quotations (assoc quotes :name "quotations.json")
                                       :bundles written})
              {:provenance stamp :bundles written :fixture fixture
               :quotations quotes :out out})))
        (finally (stack/stop! running))))))

(def usage
  (str "make capture-trace [CAPTURE_REF=<ref>] [CAPTURE_OUT=<dir>]\n"
       "clojure -M:capture [--ref <ref>] [--tag <tag>] [--out <dir>] [--port <n>]\n"
       "                   [--db-url <jdbc-url>] [--clojure <path>]\n\n"
       "Runs the three replay scenarios against a stack built from <ref> and writes\n"
       "one stamped bundle per scenario. Defaults: --ref ref-1, --out target/capture,\n"
       "--port 8099, --db-url jdbc:postgresql://localhost:5432/clofin_capture.\n\n"
       "The capture database is dropped and recreated on every run, so its name must\n"
       "end in `_capture`. Every bundle carries the source commit, the tag, that tag's\n"
       "recorded release-audit coverage, the capture instant and the schema version;\n"
       "a bundle that cannot carry all of them is not written at all.\n"))

(defn -main
  [& args]
  (when (some #{"--help" "-h"} args)
    (println usage)
    (System/exit 0))
  (try
    (let [{:keys [out] :as opts} (options args)
          {:keys [bundles provenance]} (capture! opts)]
      (println)
      (doseq [b bundles] (println (format "wrote %s  %s" (:path b) (subs (:sha256 b) 0 12))))
      (println (format "wrote %s" (str (io/file out "service-info.json"))))
      (println (format "wrote %s" (str (io/file out "quotations.json"))))
      (println (format "wrote %s" (str (io/file out "manifest.json"))))
      (println)
      (println (format "Captured from %s (%s) at %s — release audit: %s"
                       (:tag provenance) (:source-commit-short provenance)
                       (:captured-at provenance)
                       (get-in provenance [:release-audit :label])))
      (System/exit 0))
    (catch Exception e
      (binding [*out* *err*]
        (println (ex-message e))
        (when-let [data (ex-data e)]
          (when (seq (:problems data))
            (doseq [p (:problems data)] (println "  -" p)))))
      (System/exit 1))))
