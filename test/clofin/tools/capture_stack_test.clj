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
  (:require [clofin.tools.capture.stack :as stack]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
    (testing "a worktree with no handler at all is not evidence of capability"
      (is (false? (boolean (stack/self-identifies?
                            (System/getProperty "java.io.tmpdir")))))))

  (testing "and against the two real commits: `ref-1`, and this working tree"
    (let [ref-1 (java.io.File/createTempFile "ref1" "")
          dir (io/file (str ref-1 ".d"))
          handler (io/file dir "src" "clofin" "api" "health.clj")]
      (io/make-parents handler)
      (spit handler (:out (shell/sh
                           "git" "show" (str commit ":src/clofin/api/health.clj"))))
      (is (false? (boolean (stack/self-identifies? dir)))
          "ref-1's GET / renders service/description/environment/disclaimer/documentation")
      (is (true? (boolean (stack/self-identifies? ".")))
          "this tree renders instanceId (ADR-0027)"))))

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
