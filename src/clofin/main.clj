(ns clofin.main
  "Process entrypoint."
  (:require [clofin.system :as system]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main
  [& _args]
  (let [started (system/start!)
        latch   (java.util.concurrent.CountDownLatch. 1)]
    ;; Stop on SIGTERM so that a container stop drains in-flight requests
    ;; instead of severing them.
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                               (fn []
                                 (log/info "Shutdown signal received")
                                 (system/stop! started)
                                 (.countDown latch))
                               "clofin-shutdown"))
    (try
      (.await latch)
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))))
    (shutdown-agents)))
