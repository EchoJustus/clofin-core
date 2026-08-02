(ns clofin.config
  "Configuration read from the environment, with documented defaults.

  Every setting has a safe local-development default so that a fresh clone runs
  without a configuration step. Nothing here reads a file: configuration comes
  from the process environment, which is what makes the same image run
  unchanged on a laptop, in Compose and in CI
  (docs/ADR/0005-hybrid-local-and-cloud-execution.md)."
  (:require [clofin.error :as err]
            [clojure.string :as str]))

(defn- env
  ([k] (env k nil))
  ([k default]
   (let [v (System/getenv k)]
     (if (str/blank? v) default v))))

(defn- env-int [k default]
  (let [v (env k)]
    (if v
      (try (Long/parseLong v)
           (catch NumberFormatException _
             (err/invalid! (str k " must be an integer") {:variable k :value v})))
      default)))

(defn- env-bool [k default]
  (let [v (env k)]
    (if v (contains? #{"true" "1" "yes" "on"} (str/lower-case v)) default)))

(def environments
  "Deployment profiles. `:dev` includes exception detail in error responses;
  every other profile does not."
  #{:dev :test :prod})

(defn load-config
  "Read configuration from the environment. Called once at start-up; the result
  is passed explicitly to everything that needs it rather than read from a
  global, so tests can supply their own."
  []
  (let [profile (keyword (env "CLOFIN_ENV" "dev"))]
    (when-not (contains? environments profile)
      (err/invalid! (str "CLOFIN_ENV must be one of " (str/join ", " (map name (sort environments))))
                    {:value (name profile)}))
    {:environment profile
     :http {:host (env "CLOFIN_HTTP_HOST" "0.0.0.0")
            :port (env-int "CLOFIN_HTTP_PORT" 8080)}
     :db   {:url             (env "CLOFIN_DB_URL" "jdbc:postgresql://localhost:5432/clofin")
            :user            (env "CLOFIN_DB_USER" "clofin")
            :password        (env "CLOFIN_DB_PASSWORD" "clofin_local_dev_only")
            :pool-size       (env-int "CLOFIN_DB_POOL_SIZE" 8)
            :connect-timeout-ms (env-int "CLOFIN_DB_CONNECT_TIMEOUT_MS" 10000)}
     :migrate-on-start? (env-bool "CLOFIN_MIGRATE_ON_START" true)}))

(defn expose-error-detail?
  "Whether internal exception detail may appear in an HTTP response body.
  Only in development — in every other profile a caller gets a correlation id
  and nothing more."
  [config]
  (= :dev (:environment config)))

(defn redacted
  "Configuration safe to log. Credentials never reach a log line."
  [config]
  (update-in config [:db] #(-> % (dissoc :password) (assoc :password "<redacted>"))))
