(ns clofin.system
  "System lifecycle: build it, start it, stop it.

  The system is a plain map of running components, threaded explicitly rather
  than held in global state. A test can therefore start a second system on a
  different port without disturbing the first, and stopping one releases
  exactly what it created.

  No dependency-injection framework is involved — the graph is three
  components deep, and a `let` expresses it more clearly than a library would."
  (:require [clofin.config :as config]
            [clofin.db.core :as db]
            [clofin.db.migrate :as migrate]
            [clofin.http.middleware :as middleware]
            [clofin.http.router :as router]
            [clofin.http.server :as server]
            [clofin.routes :as routes]
            [clojure.tools.logging :as log]))

(defn handler
  "Build the fully-wrapped Ring handler for a system.

  Exposed separately from `start!` so that tests can exercise the entire HTTP
  stack — routing, middleware, error translation — by calling a function."
  [{:keys [config] :as system}]
  (-> (routes/routes system)
      router/compile-routes
      router/router
      (middleware/wrap config)))

(defn start!
  "Start the system. Order matters: the database must be reachable and migrated
  before the listener binds, so that a request never arrives at a service whose
  schema is not yet in place."
  ([] (start! (config/load-config)))
  ([config]
   (log/info "Starting CloFin" (pr-str (config/redacted config)))
   (let [pool (db/open-pool (:db config))]
     (try
       (when (:migrate-on-start? config)
         (migrate/migrate! pool))
       (let [system (with-meta {:config config :pool pool} {:type ::system})
             ring-handler (handler system)
             http (server/start-server ring-handler (:http config))]
         (assoc system :http http :handler ring-handler))
       (catch Throwable t
         (db/close-pool! pool)
         (throw t))))))

(defn stop!
  "Stop the system, releasing components in reverse order of creation. Each
  step is independent so that a failure to stop one does not leak the others."
  [{:keys [http pool]}]
  (try (server/stop-server! http)
       (catch Throwable t (log/warn t "Failed to stop the HTTP listener")))
  (try (db/close-pool! pool)
       (catch Throwable t (log/warn t "Failed to close the connection pool")))
  (log/info "CloFin stopped")
  nil)
