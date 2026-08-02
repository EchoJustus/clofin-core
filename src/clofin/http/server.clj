(ns clofin.http.server
  "Jetty 12 adapter producing and consuming Ring-shaped Clojure maps.

  This is the only namespace that knows Jetty exists. Everything above it — the
  router, the middleware chain, every handler — is a plain function from a map
  to a map, so the entire API can be tested without binding a port. Replacing
  Jetty, or adopting a maintained Ring adapter, is a change confined to this
  file (docs/ADR/0010-thin-ring-compatible-http-adapter.md)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io InputStream OutputStream]
           [java.nio.charset StandardCharsets]
           [org.eclipse.jetty.http HttpField HttpFields$Mutable]
           [org.eclipse.jetty.server ConnectionFactory Handler Handler$Abstract HttpConfiguration
            HttpConnectionFactory Request Response Server ServerConnector]
           [org.eclipse.jetty.util Callback]
           [org.eclipse.jetty.util.thread QueuedThreadPool]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Jetty -> Ring
;; ---------------------------------------------------------------------------

(defn- request-headers
  "Header names are lower-cased so that lookups are deterministic; HTTP header
  names are case-insensitive but Clojure map keys are not. Repeated headers are
  joined with a comma, as RFC 9110 permits."
  [^Request request]
  (persistent!
   (reduce (fn [acc ^HttpField field]
             (let [k (str/lower-case (.getName field))
                   v (.getValue field)]
               (assoc! acc k (if-let [existing (get acc k)] (str existing ", " v) v))))
           (transient {})
           (.getHeaders request))))

(defn ->ring-request
  "Convert a Jetty request into a Ring-shaped map."
  [^Request request]
  (let [uri (.getHttpURI request)]
    {:request-method (keyword (str/lower-case (.getMethod request)))
     :uri            (.getPath uri)
     :query-string   (.getQuery uri)
     :scheme         (keyword (.getScheme uri))
     :server-name    (Request/getServerName request)
     :server-port    (Request/getServerPort request)
     :remote-addr    (Request/getRemoteAddr request)
     :headers        (request-headers request)
     :body           (Request/asInputStream request)}))

;; ---------------------------------------------------------------------------
;; Ring -> Jetty
;; ---------------------------------------------------------------------------

(defn- write-response!
  [^Request request ^Response response ^Callback callback {:keys [status headers body]}]
  (.setStatus response (int (or status 200)))
  (let [fields ^HttpFields$Mutable (.getHeaders response)]
    (doseq [[k v] headers]
      (.put fields ^String (name k) ^String (str v)))
    (cond
      (nil? body)
      (.succeeded callback)

      (string? body)
      (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
        (.put fields "content-length" (str (alength bytes)))
        (with-open [^OutputStream out (Response/asBufferedOutputStream request response)]
          (.write out bytes))
        (.succeeded callback))

      (instance? InputStream body)
      (do (with-open [^InputStream in body
                      ^OutputStream out (Response/asBufferedOutputStream request response)]
            (.transferTo in out))
          (.succeeded callback))

      :else
      (throw (IllegalArgumentException.
              (str "Unsupported response body type: " (class body)))))))

(defn- ->jetty-handler
  "Wrap a Ring handler as a Jetty handler.

  The `catch` here is a backstop, not the error boundary: domain and
  application failures are handled by the middleware chain. This exists so that
  a defect in the middleware chain itself still produces a response rather than
  a dangling connection."
  [ring-handler]
  (proxy [Handler$Abstract] []
    (handle [^Request request ^Response response ^Callback callback]
      (try
        (write-response! request response callback (ring-handler (->ring-request request)))
        (catch Throwable t
          (log/error t "Failure below the middleware chain while handling a request")
          (try
            (.setStatus response 500)
            (.put ^HttpFields$Mutable (.getHeaders response) "content-type" "text/plain")
            (with-open [^OutputStream out (Response/asBufferedOutputStream request response)]
              (.write out (.getBytes "Internal server error" StandardCharsets/UTF_8)))
            (.succeeded callback)
            (catch Throwable t2
              (.failed callback t2)))))
      true)))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start-server
  "Start Jetty on `host`:`port` with `ring-handler`. Returns the server.

  Binding port 0 asks the operating system for a free port; `bound-port` then
  reports which one, which is how the smoke test avoids a fixed port."
  ^Server [ring-handler {:keys [host port]}]
  (let [pool        (doto (QueuedThreadPool.) (.setName "clofin-http"))
        server      (Server. pool)
        http-config (doto (HttpConfiguration.)
                      ;; The server should not disclose its implementation.
                      (.setSendServerVersion false)
                      (.setSendXPoweredBy false)
                      (.setRequestHeaderSize 16384))
        factories   ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
                    (into-array ConnectionFactory [(HttpConnectionFactory. http-config)])
        connector   (doto (ServerConnector. server factories)
                      (.setHost host)
                      (.setPort (int port)))]
    (.addConnector server connector)
    (.setHandler server ^Handler (->jetty-handler ring-handler))
    ;; Drain in-flight requests rather than severing them on shutdown; a
    ;; payment request cut in half is an operational incident.
    (.setStopTimeout server 10000)
    (.setStopAtShutdown server true)
    (.start server)
    (log/infof "HTTP listening on %s:%s" host (.getLocalPort connector))
    server))

(defn bound-port
  "Port the server actually bound."
  [^Server server]
  (when server
    (.getLocalPort ^ServerConnector (first (.getConnectors server)))))

(defn stop-server!
  [^Server server]
  (when server
    (log/info "Stopping HTTP listener")
    (.stop server)
    (.join server)))
