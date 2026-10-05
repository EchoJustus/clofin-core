(ns clofin.tools.capture-stack-test
  "A capture binds to the process it started.

  Release-audit finding **2C-006** (blocking) and standing lesson **L-19**. The
  auditor started an ordinary local HTTP responder reporting schema `0013` and
  asked the unmodified `start!` to spawn `/bin/false` onto that port. The child
  exited 1 and was never asked; the harness accepted the stranger's `200`, and
  `assert-schema-matches!` accepted `0013` because a schema version is a check
  and not an identity. Everything captured afterwards would have been stamped
  with the commit under capture.

  These tests reproduce that setup exactly, and they need **no CloFin service**:
  a `com.sun.net.httpserver.HttpServer` on an ephemeral port is the whole of the
  stranger, which is also what makes them unit tests rather than integration
  ones. The child is `/bin/false` — a process that starts, exits 1, and is
  therefore never the thing answering.

  What the harness establishes, and the wording matters (ADR-0027's amendment
  of 2026-09-06): both `instanceId` and `sourceCommit` are **self-reported**.
  Nothing here attests that the bytes running are the bytes at a commit. What
  is established is narrower and is what a stamp's consumers actually need —
  that the process which answered is the one this run spawned, from a worktree
  verified clean at that commit."
  (:require [clofin.tools.capture :as capture]
            [clofin.tools.capture.bundle :as bundle]
            [clofin.tools.capture.stack :as stack]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private commit "5c7b4badced5e807e1022fce44cbcad38c6d2095")
(def ^:private other-commit "0a1b2c3d4e5f60718293a4b5c6d7e8f901234567")

;; ---------------------------------------------------------------------------
;; A stranger on the port
;; ---------------------------------------------------------------------------

(defn- respond!
  [^HttpExchange exchange body]
  (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange 200 (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- responder
  "A local HTTP server answering `/readyz` with schema `0013` and `/` with
  whatever service info the test wants it to claim.

  This is the audit's own probe: an unrelated process that happens to have
  applied the same migrations. It is not a CloFin service and does not pretend
  to be one — it does not need to be, which is the finding."
  [service-info]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ready (json/write-str {"status" "ready" "checks" {"database" "ok"}
                               "schemaVersion" "0013"})]
    (.createContext server "/readyz"
                    (reify HttpHandler
                      (handle [_ exchange] (respond! exchange ready))))
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (respond! exchange (json/write-str service-info)))))
    (.start server)
    server))

(defn- port [^HttpServer server] (.getPort (.getAddress server)))

(defmacro ^:private with-responder
  [[binding service-info] & body]
  `(let [~binding (responder ~service-info)]
     (try ~@body
          (finally (.stop ~(vary-meta binding assoc :tag `HttpServer) 0)))))

(defn- info
  ([] (info {}))
  ([overrides]
   (merge {"service" "clofin-core"
           "description" "Open-source enterprise payments and reconciliation core"
           "environment" "dev"
           "disclaimer" "CloFin operates on synthetic data only."
           "sourceCommit" commit
           "documentation" "https://github.com/EchoJustus/clofin-core"}
          overrides)))

(defn- worktree!
  "A throwaway worktree whose `GET /` handler either renders `instanceId` or
  does not.

  `stack/self-identifies?` reads the **source**, so which gate applies is a
  property of the commit under capture rather than of the answer on the port.
  That is what makes it safe: a stranger can withhold a field, but it cannot
  reach into the worktree and remove the code that renders one."
  [self-identifies?]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "clofin-worktree"
                      (into-array java.nio.file.attribute.FileAttribute [])))
        handler (io/file dir "src" "clofin" "api" "health.clj")]
    (io/make-parents handler)
    (spit handler (if self-identifies?
                    "(resp/ok (cond-> {\"service\" \"clofin-core\"}\n  id (assoc \"instanceId\" id)))"
                    "(resp/ok {\"service\" \"clofin-core\" \"documentation\" \"…\"})"))
    dir))

(defn- start-against
  "`start!` pointed at an occupied port, spawning a child that cannot serve.

  `/bin/false` is the child: it starts, exits 1 immediately, and so is never
  the process answering. Whatever comes back on the port therefore came from
  somewhere else, which is exactly the case the finding is about."
  [server & {:keys [instance-id source-commit]
             :or {instance-id "run-under-test" source-commit commit}}]
  (stack/start! {:worktree (worktree! true)
                 :db {:url "jdbc:postgresql://127.0.0.1:1/nothing"
                      :user "nobody" :password "nothing"}
                 :port (port server)
                 :clojure-bin "/bin/false"
                 :log-file nil
                 :timeout-seconds 5
                 :instance-id instance-id
                 :source-commit source-commit}))

;; ---------------------------------------------------------------------------
;; (a) the port is refused before anything is spawned
;; ---------------------------------------------------------------------------

(deftest ac-2-a-port-something-is-already-answering-on-is-refused-before-spawning
  (with-responder [server (info)]
    (testing "the cheapest half of the fix: a stranger that never gets asked
              cannot be captured"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"already answering"
           (stack/assert-port-free! (port server))))))

  (testing "and a free port is free — the check must not refuse every run"
    (let [server (responder (info))
          p (port server)]
      (.stop server 0)
      (is (= :free (stack/assert-port-free! p))))))

;; ---------------------------------------------------------------------------
;; (b) a child that is not alive is refused whatever the port answers
;; ---------------------------------------------------------------------------

(deftest ac-2-a-dead-child-is-refused-however-healthy-the-port-looks
  (testing "the finding itself, reproduced: `/bin/false` exits 1, an unrelated
            responder reports schema 0013 on the port, and the RC's start!
            returned that responder as the captured stack"
    (with-responder [server (info)]
      ;; First, that the setup really is the audit's: something IS answering
      ;; 200 on this port. Without this the refusal below could be a refusal to
      ;; talk to nothing, which would prove nothing at all.
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already answering"
                            (stack/assert-port-free! (port server)))
          "the port must be occupied for this test to mean anything")

      (let [failure (try (start-against server) nil
                         (catch clojure.lang.ExceptionInfo e e))]
        (is (some? failure)
            "a stranger's 200 must never be accepted as the captured stack")
        ;; Which of the two refusals fires is a matter of whether the child has
        ;; finished exiting when the loop first samples it, and both are the
        ;; same guarantee: the answer on the port did not come from this run's
        ;; process. Neither is the RC's behaviour, which was to return it.
        (is (re-find #"exited before becoming ready|no longer running"
                     (ex-message failure))
            (ex-message failure))))))

;; ---------------------------------------------------------------------------
;; (c) and (d) identity, checked after the 200
;; ---------------------------------------------------------------------------

(deftest ac-2-a-responder-that-cannot-echo-the-run-s-instance-id-is-refused
  (testing "a process already on the port started before the id was minted, so
            it cannot produce it — whether it reports the wrong one"
    (with-responder [server (info {"instanceId" "some-other-run"})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"reports instance id"
           (stack/assert-same-process! {:process nil
                                        :base-url (str "http://127.0.0.1:" (port server))
                                        :instance-id "run-under-test"
                                        :source-commit commit
                                        :self-identifies? true})))))

  (testing "or none at all"
    (with-responder [server (info)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"reports instance id"
           (stack/assert-same-process! {:process nil
                                        :base-url (str "http://127.0.0.1:" (port server))
                                        :instance-id "run-under-test"
                                        :source-commit commit
                                        :self-identifies? true}))))))

(deftest ac-2-the-right-instance-with-the-wrong-commit-is-refused
  (testing "the second hole, and the one that was live: a child inherits its
            parent's environment, and `make capture-trace` exports the
            *harness's* HEAD as CLOFIN_SOURCE_COMMIT — so the tagged commit's
            service reported main's SHA. The harness now passes the commit
            under capture explicitly and refuses anything else"
    (with-responder [server (info {"instanceId" "run-under-test"
                                   "sourceCommit" other-commit})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"reports source commit"
           (stack/assert-same-process! {:process nil
                                        :base-url (str "http://127.0.0.1:" (port server))
                                        :instance-id "run-under-test"
                                        :source-commit commit
                                        :self-identifies? true}))))))

(deftest ac-2-a-stack-that-echoes-both-is-accepted
  (testing "the positive case, so the refusals above cannot pass by refusing
            everything"
    (with-responder [server (info {"instanceId" "run-under-test"})]
      (is (= {:instance-id "run-under-test" :source-commit commit
              :identity-established-by :instance-id}
             (stack/assert-same-process! {:process nil
                                          :base-url (str "http://127.0.0.1:" (port server))
                                          :instance-id "run-under-test"
                                          :source-commit commit
                                          :self-identifies? true}))))))

(deftest a-commit-that-predates-instance-ids-is-still-capturable
  (testing "`ref-1` renders neither `instanceId` nor `sourceCommit` from
            `GET /` — it predates both — and it is the documented default of
            `make capture-trace` (`CAPTURE_REF ?= ref-1`) and of
            `clojure -M:capture`, and the only tag that exists. A gate that
            demanded an echo such a service has no way to produce would refuse
            every capture of it, with a message saying the process answering is
            not the one this run started when it is exactly that process — and
            it would contradict ADR-0022, which established that `ref-1`
            predates any build stamp and that changing the source to make it
            capturable captures a different source state"
    (with-responder [server (dissoc (info) "sourceCommit")]
      (let [child (.start (ProcessBuilder. ["sleep" "30"]))
            running {:process child
                     :base-url (str "http://127.0.0.1:" (port server))
                     :instance-id "run-under-test"
                     :source-commit commit
                     :self-identifies? false}]
        (try
          (is (= :port-exclusion
                 (:identity-established-by (stack/assert-same-process! running)))
              "a pre-stamp commit binds by exclusion, and the run says which")
          (testing "and the weakening is *named*, not silent: the same responder
                    against a commit whose source does render the field is
                    refused, so the two gates are genuinely different"
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo #"reports instance id"
                 (stack/assert-same-process! (assoc running :self-identifies? true)))))
          (finally (.destroy child)))))))

(deftest which-gate-applies-is-read-from-the-source-not-from-the-answer
  (testing "the discriminator must be one the thing being gated cannot reach.
            A stranger on the port can withhold `instanceId`; it cannot stop
            the worktree's handler from rendering one"
    (is (false? (boolean (stack/self-identifies? (worktree! false)))))
    (is (true? (boolean (stack/self-identifies? (worktree! true)))))
    (testing "and a worktree the harness cannot read is refused rather than
              answered. `false` there would be a silent downgrade to the weaker
              gate in the one case where the harness understands least — a
              guard that fails **open** on a surprise (L-6)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"is not readable"
           (stack/self-identifies? (System/getProperty "java.io.tmpdir"))))))

  (testing "and against the two real commits: `ref-1`, and this working tree"
    (let [ref-1 (java.io.File/createTempFile "ref1" "")
          dir (io/file (str ref-1 ".d"))
          handler (io/file dir "src" "clofin" "api" "health.clj")]
      (io/make-parents handler)
      (spit handler (:out (shell/sh
                           "git" "show" (str commit ":src/clofin/api/health.clj"))))
      (is (false? (boolean (stack/self-identifies? dir)))
          "ref-1's GET / renders service/description/environment/disclaimer/documentation")
      (testing "and the whole of src/ is searched, so moving the handler does
                not silently answer *no*"
        (let [moved (io/file (str ref-1 ".moved"))]
          (io/make-parents (io/file moved "src" "clofin" "elsewhere.clj"))
          (spit (io/file moved "src" "clofin" "elsewhere.clj")
                "(resp/ok {\"instanceId\" id})")
          (is (true? (boolean (stack/self-identifies? moved))))))
      (is (true? (boolean (stack/self-identifies? ".")))
          "this tree renders instanceId (ADR-0027)"))))

;; ---------------------------------------------------------------------------
;; What start! established is carried, so that it can be stamped (TASK-016)
;; ---------------------------------------------------------------------------

(defn- sleeping-child
  "An executable standing in for the `clojure` CLI: it ignores `-M:run` and
  stays alive, so `start!` gets past the liveness check and reaches the
  identity gate — which is the part under test. The responder on the port is
  what answers; this child only has to be the process the run started."
  []
  (let [f (java.io.File/createTempFile "clofin-child" ".sh")]
    (spit f "#!/bin/sh\nexec sleep 30\n")
    (.setExecutable f true)
    (.deleteOnExit f)
    (str f)))

(defn- start-alive
  [server worktree]
  (stack/start! {:worktree (str worktree)
                 :db {:url "jdbc:postgresql://127.0.0.1:1/nothing"
                      :user "nobody" :password "nothing"}
                 :port (port server)
                 :clojure-bin (sleeping-child)
                 :log-file nil
                 :timeout-seconds 10
                 :instance-id "run-under-test"
                 :source-commit commit}))

(defn- stamped
  "What a capture run started this way would stamp: `capture!`'s own stamp
  completion, read back off the wire shape every artifact is written in."
  [running]
  (get (bundle/provenance->wire (capture/run-stamp {} "0013" running)) "identityBinding"))

(deftest ac-1-start-carries-the-binding-it-established
  (testing "one responder for both cases, echoing the run's instance id and the
            commit: which binding applies is read from the worktree, never from
            the answer, so only the worktree differs below"
    (with-responder [server (info {"instanceId" "run-under-test"})]
      (testing "a worktree whose GET / renders instanceId binds by instance id"
        (doseq [worktree [(worktree! true)
                          ;; This tree's own source — src/ is identical to
                          ;; ref-2's, the first tag whose GET / renders it.
                          (System/getProperty "user.dir")]]
          (let [running (start-alive server worktree)]
            (try
              (is (= :instance-id (:identity-binding running)) (str worktree))
              (is (= "instance-id" (stamped running))
                  "and it reaches the stamp every artifact carries")
              (finally (stack/stop! running))))))
      (testing "a worktree whose source does not render it binds by port
                exclusion — even though the responder offers the right id"
        (let [running (start-alive server (worktree! false))]
          (try
            (is (= :port-exclusion (:identity-binding running)))
            (is (= "port-exclusion" (stamped running)))
            (finally (stack/stop! running)))))))

  (testing "and a start that cannot establish either binding returns nothing to
            stamp: it throws, and destroys the child it started"
    (with-responder [server (info {"instanceId" "some-other-run"})]
      (let [spawned (atom nil)
            real    @#'stack/process]
        (with-redefs [stack/process (fn [opts] (reset! spawned (real opts)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reports instance id"
                                (start-alive server (worktree! true)))))
        (is (some? @spawned) "non-vacuity: a child was spawned")
        (is (.waitFor ^Process @spawned 5 java.util.concurrent.TimeUnit/SECONDS)
            "start! refused and left the child it spawned running")))))

(deftest a-worktree-the-harness-cannot-read-refuses-before-anything-is-spawned
  (testing "which gate applies is decided before the child exists, so a refusal
            there has no child to leave behind holding the port"
    (with-responder [server (info {"instanceId" "run-under-test"})]
      (let [spawned (atom 0)
            no-src  (.toFile (java.nio.file.Files/createTempDirectory
                              "clofin-no-src"
                              (into-array java.nio.file.attribute.FileAttribute [])))]
        (with-redefs [stack/process (fn [_] (swap! spawned inc)
                                      (.start (ProcessBuilder. ["sleep" "30"])))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not readable"
                                (start-alive server no-src))))
        (is (zero? @spawned) "start! spawned a child before deciding which gate applies")))))

;; ---------------------------------------------------------------------------
;; (e) liveness is re-checked, not checked once
;; ---------------------------------------------------------------------------

(deftest ac-2-a-destroyed-child-fails-the-check-before-the-next-file-is-written
  (testing "a child that dies halfway through a capture leaves a port a
            stranger can take, so every writer call re-asks"
    (with-responder [server (info {"instanceId" "run-under-test"})]
      (let [child (.start (ProcessBuilder. ["sleep" "30"]))
            running {:process child
                     :base-url (str "http://127.0.0.1:" (port server))
                     :instance-id "run-under-test"
                     :source-commit commit
                     :self-identifies? true}]
        (is (map? (stack/assert-same-process! running))
            "alive and echoing: the capture may continue")
        (.destroy child)
        (.waitFor child)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"no longer running"
             (stack/assert-same-process! running)))))))

;; ---------------------------------------------------------------------------
;; The environment the child is handed
;; ---------------------------------------------------------------------------

(deftest the-child-is-told-its-instance-and-its-commit
  (testing "both are set explicitly, because a child inherits everything not
            set — which is how CLOFIN_SOURCE_COMMIT came to be main's"
    (let [env (stack/env-for {:url "jdbc:x" :user "u" :password "p"} 8099
                             {:instance-id "run-under-test" :source-commit commit})]
      (is (= "run-under-test" (get env "CLOFIN_INSTANCE_ID")))
      (is (= commit (get env "CLOFIN_SOURCE_COMMIT")))
      (is (= "8099" (get env "CLOFIN_HTTP_PORT")))))

  (testing "and the migration run, which reports nothing about itself, is given
            neither"
    (let [env (stack/env-for {:url "jdbc:x" :user "u" :password "p"} 0 nil)]
      (is (not (contains? env "CLOFIN_INSTANCE_ID")))
      (is (not (contains? env "CLOFIN_SOURCE_COMMIT"))))))

(deftest a-run-without-an-instance-id-is-refused
  (testing "the identity is not optional: a run that could not be bound to its
            own process must not start one"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"must carry an instance id"
         (stack/start! {:worktree (worktree! true)
                        :db {:url "jdbc:x" :user "u" :password "p"}
                        :port 1 :clojure-bin "/bin/false" :log-file nil
                        :source-commit commit})))))

;; ---------------------------------------------------------------------------
;; The captured commit's screening list (017-REQ R-4)
;; ---------------------------------------------------------------------------

(defn- list-worktree!
  "A throwaway worktree shipping the named screening-list files (empty files:
  the loader is a stand-in, below)."
  [& names]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "capture-lists" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [n names]
      (let [f (io/file dir "resources" "screening-lists" n)]
        (io/make-parents f)
        (spit f "")))
    (str dir)))

(def ^:private no-db {:url "jdbc:postgresql://127.0.0.1:1/none" :user "none" :password "none"})

(deftest a-capture-loads-the-one-screening-list-the-commit-ships
  (testing "TASK-017's gate refuses every submission with no list accepted, so a
            capture of a commit that carries it loads the commit's own list with
            the commit's own loader, **run inside the captured worktree** — a
            loader run anywhere else would load another commit's list. A script
            stands in for `clojure` and prints where it ran and what it was
            asked"
    (let [wt     (list-worktree! "synthetic-2026-10-v1.edn")
          log    (str wt "/capture.log")
          script (doto (java.io.File/createTempFile "fake-clojure" ".sh")
                   (spit "#!/bin/sh\necho \"cwd=$(pwd -P) db=$CLOFIN_DB_URL args=$*\"\n")
                   (.setExecutable true))]
      (is (= :loaded (stack/load-screening-list! {:worktree wt :db no-db
                                                  :clojure-bin (str script) :log-file log})))
      (is (str/includes? (slurp log) (str "cwd=" (.getCanonicalPath (io/file wt)) " "))
          "the loader ran in the captured worktree")
      (is (str/includes? (slurp log) (str "db=" (:url no-db) " "))
          "and was given the capture database, not whatever the harness's environment holds")
      (is (str/includes? (slurp log)
                         "args=-M:screening-list load resources/screening-lists/synthetic-2026-10-v1.edn"))))
  (testing "a commit from before TASK-017 ships no list and needs none"
    (is (= :no-list (stack/load-screening-list! {:worktree (list-worktree!) :db no-db
                                                 :clojure-bin "false" :log-file nil}))))
  (testing "a loader that fails stops the capture, naming the list"
    (let [wt (list-worktree! "synthetic-2026-10-v1.edn")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"capture refuses: loading the screening list resources/screening-lists/synthetic-2026-10-v1.edn"
                            (stack/load-screening-list! {:worktree wt :db no-db :clojure-bin "false"
                                                         :log-file (str wt "/capture.log")})))))
  (testing "two shipped lists are refused rather than chosen between"
    (let [wt (list-worktree! "a.edn" "b.edn")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ships 2 screening lists \(a.edn, b.edn\)"
                            (stack/load-screening-list! {:worktree wt :db no-db :clojure-bin "echo"
                                                         :log-file nil})))))
  (testing "and this commit ships exactly one, so a capture of it loads that list"
    (is (= ["synthetic-2026-10-v1.edn"]
           (mapv #(.getName ^java.io.File %) (#'stack/shipped-screening-lists "."))))))
