(ns clofin.api.health
  "Liveness and readiness.

  The two are deliberately different questions. Liveness asks whether the
  process should be restarted; readiness asks whether it should receive
  traffic. Conflating them causes an orchestrator to restart a healthy service
  because its database is briefly unavailable — turning a recoverable
  dependency blip into an outage."
  (:require [clofin.db.core :as db]
            [clofin.db.migrate :as migrate]
            [clofin.http.response :as resp]))

(def ^:private started-at (System/currentTimeMillis))

(defn healthz
  "Liveness. Answers as long as the process can serve a request; it must not
  depend on anything external, or a dependency failure becomes a restart loop."
  [_config]
  (fn [_request]
    (resp/ok {"status"    "ok"
              "service"   "clofin-core"
              "uptimeSeconds" (quot (- (System/currentTimeMillis) started-at) 1000)})))

(defn readyz
  "Readiness. Reports 503 when the database is unreachable, so traffic is
  routed away without the process being restarted. Also reports the applied
  schema version, which is the first thing anyone asks during an incident."
  [_config pool]
  (fn [_request]
    (let [db-ok? (db/reachable? pool)
          version (when db-ok? (migrate/current-version pool))]
      (if db-ok?
        (resp/ok {"status" "ready"
                  "checks" {"database" "ok"}
                  "schemaVersion" (or version "none")})
        (resp/problem {:status 503
                       :type :unavailable
                       :title "Service not ready"
                       :detail "The database is not reachable."})))))

(defn info
  "Static description of the service. Exists so that anyone who reaches the API
  without context immediately learns what it is — and what it is not."
  [config]
  (fn [_request]
    (resp/ok {"service" "clofin-core"
              "description" "Open-source enterprise payments and reconciliation core"
              "environment" (name (:environment config))
              "disclaimer" (str "CloFin operates on synthetic data only. It is not connected "
                                "to any bank, payment scheme or central bank, holds no "
                                "regulatory authorisation, and never processes real funds.")
              "documentation" "https://github.com/EchoJustus/clofin-core"})))
