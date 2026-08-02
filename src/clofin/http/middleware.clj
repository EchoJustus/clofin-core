(ns clofin.http.middleware
  "Middleware: correlation, logging, JSON codec and the error boundary.

  Each middleware is a function from handler to handler, as in the Ring
  specification, so the chain is ordinary function composition and every layer
  can be tested in isolation."
  (:require [clofin.config :as config]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io InputStream]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; Correlation
;; ---------------------------------------------------------------------------

(def correlation-header "x-correlation-id")

(defn wrap-correlation-id
  "Attach a correlation id to every request, honouring one supplied by the
  caller so that a trace survives across system boundaries.

  A caller-supplied value is length-limited and stripped of anything outside a
  conservative character set before it is echoed back or written to a log —
  an identifier that arrives from outside is untrusted input like any other."
  [handler]
  (fn [request]
    (let [supplied (get-in request [:headers correlation-header])
          sanitised (some-> supplied
                            (str/replace #"[^A-Za-z0-9._:-]" "")
                            (as-> s (when-not (str/blank? s) (subs s 0 (min 128 (count s))))))
          correlation-id (or sanitised (str (random-uuid)))
          response (handler (assoc request :correlation-id correlation-id))]
      (assoc-in response [:headers correlation-header] correlation-id))))

;; ---------------------------------------------------------------------------
;; Request logging
;; ---------------------------------------------------------------------------

(defn wrap-request-logging
  "Log one structured line per request. Query strings and bodies are not
  logged: in a payments system they routinely carry account identifiers and
  counterparty names."
  [handler]
  (fn [request]
    (let [started (System/nanoTime)
          response (handler request)
          elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
      (log/infof "%s %s -> %s (%dms) correlation=%s"
                 (str/upper-case (name (:request-method request)))
                 (:uri request)
                 (:status response)
                 elapsed-ms
                 (:correlation-id request))
      response)))

;; ---------------------------------------------------------------------------
;; JSON
;; ---------------------------------------------------------------------------

(def ^:private max-body-bytes
  "Cap on request body size. A payments API accepts small documents; anything
  larger is either a mistake or an attempt to exhaust memory."
  (* 1024 1024))

(defn- read-body ^String [^InputStream stream]
  (when stream
    (let [buffer (java.io.ByteArrayOutputStream.)
          chunk  (byte-array 8192)]
      (loop [total 0]
        (let [n (.read stream chunk)]
          (cond
            (neg? n) (String. (.toByteArray buffer) StandardCharsets/UTF_8)
            (> (+ total n) max-body-bytes)
            (err/invalid! "Request body is too large" {:max-bytes max-body-bytes})
            :else (do (.write buffer chunk 0 n) (recur (+ total n)))))))))

(defn- json-request? [request]
  (some-> (get-in request [:headers "content-type"])
          str/lower-case
          (str/starts-with? "application/json")))

(defn wrap-json-request
  "Parse a JSON request body into `:json-body`.

  Keys are left as strings. Converting arbitrary caller-supplied keys to
  keywords would let a caller grow the JVM keyword table without bound;
  handlers name the keys they expect."
  [handler]
  (fn [request]
    (if (json-request? request)
      (let [raw (read-body (:body request))
            parsed (when-not (str/blank? raw)
                     (try
                       (json/read-str raw)
                       (catch Exception _
                         (err/invalid! "Request body is not valid JSON"))))]
        (handler (assoc request :json-body parsed :raw-body raw)))
      (handler request))))

(defn- json-value-fn [_ v]
  (cond
    (instance? java.time.Instant v) (str v)
    (instance? java.util.Date v)    (str (.toInstant ^java.util.Date v))
    (uuid? v)                       (str v)
    (keyword? v)                    (name v)
    :else v))

(defn wrap-json-response
  "Encode a data response body as JSON, unless the handler already produced a
  string or a stream."
  [handler]
  (fn [request]
    (let [response (handler request)
          body (:body response)]
      (if (or (nil? body) (string? body) (instance? InputStream body))
        response
        (-> response
            (update :headers (fn [headers]
                               (if (contains? headers "content-type")
                                 headers
                                 (assoc headers "content-type" "application/json"))))
            (assoc :body (json/write-str body :value-fn json-value-fn)))))))

;; ---------------------------------------------------------------------------
;; Error boundary
;; ---------------------------------------------------------------------------

(defn wrap-errors
  "The single place where a throwable becomes a response.

  A domain error is rendered as a problem document with its own message. Any
  other throwable is a defect: it is logged in full with the correlation id,
  and the caller receives a 500 carrying that id and nothing else. Leaking a
  stack trace or a SQL fragment to a caller is a finding in every security
  review, so detail is exposed only in the development profile."
  [handler config]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (let [correlation-id (:correlation-id request)]
          (if (err/domain-error? t)
            (do (log/debugf "Domain error on %s: %s (correlation=%s)"
                            (:uri request) (ex-message t) correlation-id)
                (resp/error->problem t {:correlation-id correlation-id}))
            (do (log/error t (format "Unhandled error on %s (correlation=%s)"
                                     (:uri request) correlation-id))
                (resp/problem
                 {:status 500
                  :type :internal-error
                  :title "Internal server error"
                  :detail (if (config/expose-error-detail? config)
                            (str (.getName (class t)) ": " (ex-message t))
                            "The request could not be completed. Quote the instance id when reporting this.")
                  :instance correlation-id}))))))))

;; ---------------------------------------------------------------------------
;; Chain
;; ---------------------------------------------------------------------------

(defn wrap
  "Compose the standard middleware chain around `handler`.

  Order matters and is stated here once. Outermost first:

  1. correlation id  — so every later layer, including the error boundary, has one
  2. request logging — so a request that fails is still logged, with its status
  3. JSON response   — sits *outside* the error boundary so that the problem
                       documents it produces are encoded like any other body
  4. error boundary  — so a throwable from anything below becomes a response
  5. JSON request    — parsing failures are raised inside the error boundary"
  [handler config]
  (-> handler
      wrap-json-request
      (wrap-errors config)
      wrap-json-response
      wrap-request-logging
      wrap-correlation-id))
