(ns clofin.system-test
  "End-to-end: start the real system, bind a real port, speak real HTTP.

  Every other HTTP test calls handlers as functions, which is faster and
  covers more. This one exists to prove the part that cannot be tested that
  way — the Jetty adapter, the lifecycle, and the fact that `make up` will
  actually work."
  (:require [clofin.config :as config]
            [clofin.http.server :as server]
            [clofin.system :as system]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private client
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) .build))

(defn- GET [port path & {:keys [headers]}]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://127.0.0.1:" port path)))
                    (.timeout (Duration/ofSeconds 10))
                    (.GET))]
    (doseq [[k v] headers] (.header builder k v))
    (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)]))
                      (.map (.headers response)))
       :body (.body response)})))

(defn- with-system [f]
  (let [config (-> (config/load-config)
                   (assoc :environment :test)
                   ;; Port 0 asks the operating system for a free port, so the
                   ;; test never collides with a running development stack.
                   (assoc-in [:http :port] 0)
                   (assoc-in [:http :host] "127.0.0.1"))
        started (system/start! config)]
    (try
      (f started (server/bound-port (:http started)))
      (finally (system/stop! started)))))

(deftest the-service-starts-and-answers
  (with-system
    (fn [_system port]
      (testing "liveness"
        (let [{:keys [status body headers]} (GET port "/healthz")]
          (is (= 200 status))
          (is (str/starts-with? (get headers "content-type") "application/json"))
          (is (= "ok" (get (json/read-str body) "status")))))

      (testing "readiness reports the applied schema version"
        (let [{:keys [status body]} (GET port "/readyz")
              decoded (json/read-str body)]
          (is (= 200 status))
          (is (= "ready" (get decoded "status")))
          (is (re-matches #"\d{4}" (get decoded "schemaVersion")))))

      (testing "the service states its scope to anyone who reaches it"
        (let [decoded (json/read-str (:body (GET port "/")))]
          (is (str/includes? (str/lower-case (get decoded "disclaimer")) "synthetic"))))

      (testing "an unknown path is a problem document, not a stack trace"
        (let [{:keys [status body headers]} (GET port "/no-such-thing")]
          (is (= 404 status))
          (is (= "application/problem+json" (get headers "content-type")))
          (is (= "https://clofin.dev/problems/not-found" (get (json/read-str body) "type")))))

      (testing "every response carries a correlation id"
        (let [{:keys [headers]} (GET port "/healthz")]
          (is (not (str/blank? (get headers "x-correlation-id"))))))

      (testing "a caller-supplied correlation id is echoed back"
        (let [{:keys [headers]} (GET port "/healthz" :headers {"x-correlation-id" "trace-abc-1"})]
          (is (= "trace-abc-1" (get headers "x-correlation-id")))))

      (testing "the server does not advertise its implementation"
        (let [{:keys [headers]} (GET port "/healthz")]
          (is (nil? (get headers "server")))
          (is (nil? (get headers "x-powered-by"))))))))

(deftest the-service-migrates-before-it-listens
  (testing "a request never arrives at a service whose schema is not yet in place"
    (with-system
      (fn [_system port]
        (let [decoded (json/read-str (:body (GET port "/readyz")))]
          (is (= "ready" (get decoded "status"))))))))

(deftest stopping-releases-everything
  (let [config (-> (config/load-config)
                   (assoc :environment :test)
                   (assoc-in [:http :port] 0)
                   (assoc-in [:http :host] "127.0.0.1"))
        started (system/start! config)
        port (server/bound-port (:http started))]
    (is (= 200 (:status (GET port "/healthz"))))
    (system/stop! started)
    (testing "the port is no longer served once the system is stopped"
      (is (thrown? Exception (GET port "/healthz"))))))
