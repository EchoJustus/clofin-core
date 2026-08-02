(ns user
  "REPL conveniences for local development. Loaded automatically by
  `clojure -M:dev` (and therefore by `make repl`).

  Nothing here is on the runtime classpath — the `dev` path belongs to the
  `:dev` alias only, so none of it can accidentally reach production."
  (:require [clofin.config :as config]
            [clofin.db.core :as db]
            [clofin.db.migrate :as migrate]
            [clofin.ledger.entry :as entry]
            [clofin.money :as money]
            [clofin.system :as system]))

(defonce ^{:doc "The running system, if one was started from this REPL."}
  running (atom nil))

(defn start!
  "Start CloFin in this REPL. Requires PostgreSQL — `make db-up`."
  []
  (when @running (throw (ex-info "A system is already running; call (stop!) first" {})))
  (reset! running (system/start!))
  (:http-port (:config @running))
  :started)

(defn stop! []
  (when-let [s @running] (system/stop! s) (reset! running nil))
  :stopped)

(defn restart! [] (stop!) (start!))

(defn pool
  "Connection pool of the running system."
  []
  (:pool @running))

(defn handler
  "The fully-wrapped Ring handler, for exercising the API without HTTP:

      (handler {:request-method :get :uri \"/healthz\" :headers {}})"
  []
  (:handler @running))

(comment
  ;; Bring the system up and poke at it.
  (start!)
  ((handler) {:request-method :get :uri "/readyz" :headers {}})

  ;; Migration state.
  (migrate/status (pool))

  ;; Money behaves like a value, not like a number with a unit stapled on.
  (money/+ (money/of "SGD" 125000) (money/of "SGD" 500))
  (money/allocate (money/of "SGD" 10000) [1 1 1])
  (money/format-amount (money/of "KWD" 125000))

  ;; The invariant, up close.
  (entry/imbalance [{:account-id (random-uuid) :direction :debit  :amount (money/of "SGD" 1000)}
                    {:account-id (random-uuid) :direction :credit :amount (money/of "SGD" 700)}])

  (stop!))
