(ns clofin.http.cors-test
  "The allowlist, in both directions — and the two lists it answers with,
  discovered from the service rather than asserted about it.

  Two of the tests here are unusual and deliberate. `allow-methods-…` derives
  the allowed method set from `clofin.routes/routes`, and `allow-headers-…`
  derives the allowed header set by reading every `(get-in request [:headers
  …])` in `src/`. Both fail when the service changes and the CORS layer does
  not. That shape is the answer to a specific instruction — *discover the real
  header set the API uses, do not guess* — and to the specific way a guess
  fails: a missing entry in `Access-Control-Allow-Headers` is invisible to
  every test that is not a browser, because a browser is the only client that
  sends a preflight at all (011-REQ §7)."
  (:require [clofin.build-info :as build-info]
            [clofin.config :as config]
            [clofin.http.cors :as cors]
            [clofin.http.middleware :as middleware]
            [clofin.http.response :as resp]
            [clofin.routes :as routes]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]))

(def ^:private allowed "https://echojustus.github.io")
(def ^:private also-allowed "http://localhost:4173")

(defn- echo-handler
  "A handler that answers 200 and nothing else, so a test sees only what the
  middleware added."
  [_request]
  (resp/ok {"ok" true}))

(defn- cors-headers
  "Only the headers this middleware is responsible for."
  [response]
  (into (sorted-map)
        (filter (fn [[k _]] (or (str/starts-with? k "access-control") (= k "vary"))))
        (:headers response)))

(defn- request
  ([method uri] (request method uri {}))
  ([method uri headers]
   {:request-method method :uri uri :headers headers}))

;; ---------------------------------------------------------------------------
;; Default-closed
;; ---------------------------------------------------------------------------

(deftest unconfigured-returns-the-very-same-handler
  (testing "nothing configured is not a wrapper that adds nothing — it is no wrapper"
    (doseq [configured [nil "" "   " "," " , ,, " []]]
      (is (identical? echo-handler (cors/wrap-cors echo-handler configured))
          (str "wrap-cors should return the handler unchanged for "
               (pr-str configured))))))

(deftest unconfigured-answers-a-browser-exactly-as-it-answers-anyone
  (let [handler (cors/wrap-cors echo-handler nil)]
    (testing "an actual request carrying an Origin gets no CORS header and no Vary"
      (is (= {} (cors-headers (handler (request :get "/" {"origin" allowed}))))))
    (testing "a preflight is not answered here; it goes to the handler below"
      ;; Which, in the real chain, is the router — and the router answers 405
      ;; for OPTIONS on a routed path. No CORS header means the browser refuses
      ;; the request the preflight was asking about, which is the refusal.
      (let [response (handler (request :options "/"
                                       {"origin" allowed
                                        "access-control-request-method" "POST"}))]
        (is (= 200 (:status response)) "reached the handler below rather than being intercepted")
        (is (= {} (cors-headers response)))))))

(deftest unconfigured-leaves-the-whole-chain-identical
  (testing "the composed chain is the same chain, with and without an Origin header"
    (let [config {:environment :test}
          handler (middleware/wrap echo-handler config)
          plain (handler (request :get "/"))
          with-origin (handler (request :get "/" {"origin" allowed}))
          without-correlation #(update % :headers dissoc "x-correlation-id")]
      (is (= (without-correlation plain) (without-correlation with-origin))
          "an Origin header changes nothing about the response")
      (is (empty? (cors-headers with-origin))))))

;; ---------------------------------------------------------------------------
;; Configured: preflight
;; ---------------------------------------------------------------------------

(deftest a-listed-origins-preflight-is-answered-completely
  (let [handler (cors/wrap-cors echo-handler (str allowed "," also-allowed))
        response (handler (request :options "/accounts"
                                   {"origin" allowed
                                    "access-control-request-method" "POST"
                                    "access-control-request-headers" "content-type, x-actor-id"}))]
    (is (= 204 (:status response)))
    (is (nil? (:body response)))
    (is (= {"access-control-allow-origin" allowed
            "access-control-allow-headers" "content-type, idempotency-key, x-actor-id, x-correlation-id"
            "access-control-allow-methods" "GET, POST, PATCH, DELETE"
            "access-control-max-age" "600"
            "vary" "origin"}
           (cors-headers response)))
    (testing "no credentials are ever allowed"
      (is (not (contains? (:headers response) "access-control-allow-credentials"))))))

(deftest a-preflight-answer-is-this-services-list-not-the-callers
  (testing "the requested headers are not echoed back"
    (let [handler (cors/wrap-cors echo-handler allowed)
          response (handler (request :options "/accounts"
                                     {"origin" allowed
                                      "access-control-request-method" "POST"
                                      "access-control-request-headers" "x-inject, x-actor-id"}))]
      (is (= "content-type, idempotency-key, x-actor-id, x-correlation-id"
             (get-in response [:headers "access-control-allow-headers"]))
          "a header the service does not read must not appear because a caller asked for it"))))

(deftest an-unlisted-origins-preflight-falls-through-uncorsed
  (let [handler (cors/wrap-cors echo-handler allowed)]
    (doseq [origin ["https://evil.example"
                    ;; The three near-misses a substring or suffix comparison
                    ;; would accept. Matching is equality against a set.
                    (str allowed ".evil.example")
                    "https://echojustus.github.io.evil.example"
                    "https://io"
                    "http://echojustus.github.io"]]
      (let [response (handler (request :options "/accounts"
                                       {"origin" origin
                                        "access-control-request-method" "POST"}))]
        (testing origin
          (is (= 200 (:status response)) "not intercepted — the handler below answers")
          (is (nil? (get-in response [:headers "access-control-allow-origin"]))))))))

;; ---------------------------------------------------------------------------
;; Configured: actual requests
;; ---------------------------------------------------------------------------

(deftest a-listed-origin-can-read-the-response
  (let [handler (cors/wrap-cors echo-handler (str allowed ", " also-allowed))]
    (doseq [origin [allowed also-allowed]]
      (let [response (handler (request :get "/" {"origin" origin}))]
        (testing origin
          (is (= origin (get-in response [:headers "access-control-allow-origin"])))
          (is (= "location, x-correlation-id, allow"
                 (get-in response [:headers "access-control-expose-headers"])))
          (is (= "origin" (get-in response [:headers "vary"])))
          (is (= 200 (:status response)) "the response itself is untouched"))))))

(deftest an-unlisted-origin-gets-a-response-it-cannot-read
  (let [handler (cors/wrap-cors echo-handler allowed)
        response (handler (request :get "/" {"origin" "https://evil.example"}))]
    (is (nil? (get-in response [:headers "access-control-allow-origin"])))
    (is (nil? (get-in response [:headers "access-control-expose-headers"])))
    (testing "Vary is still declared, so no cache serves this to a listed origin"
      (is (= "origin" (get-in response [:headers "vary"]))))))

(deftest a-request-with-no-origin-is-unaffected-apart-from-vary
  (let [handler (cors/wrap-cors echo-handler allowed)
        response (handler (request :get "/"))]
    (is (= {"vary" "origin"} (cors-headers response)))))

(deftest vary-is-appended-not-overwritten
  (let [handler (cors/wrap-cors (fn [_] (resp/with-header (resp/ok {}) "vary" "accept")) allowed)]
    (is (= "accept, origin" (get-in (handler (request :get "/" {"origin" allowed}))
                                    [:headers "vary"])))
    (testing "and not doubled when it is already declared"
      (let [already (cors/wrap-cors (fn [_] (resp/with-header (resp/ok {}) "vary" "Origin"))
                                    allowed)]
        (is (= "Origin" (get-in (already (request :get "/" {"origin" allowed}))
                                [:headers "vary"])))))))

(deftest the-emitted-origin-comes-from-configuration-not-from-the-request
  (testing "case differences resolve to the configured spelling"
    (let [handler (cors/wrap-cors echo-handler "HTTPS://Echojustus.GitHub.io")
          response (handler (request :get "/" {"origin" "https://echojustus.github.io"}))]
      (is (= "https://echojustus.github.io"
             (get-in response [:headers "access-control-allow-origin"]))
          "the allowlist is normalised once, at start-up, and that is what is emitted"))))

(deftest a-cors-marked-response-still-carries-its-own-status-and-body
  (let [handler (cors/wrap-cors (fn [_] (resp/problem {:status 403 :type :forbidden
                                                       :title "Forbidden" :detail "no"}))
                                allowed)
        response (handler (request :post "/accounts" {"origin" allowed}))]
    (is (= 403 (:status response)))
    (is (= allowed (get-in response [:headers "access-control-allow-origin"]))
        "a refusal is exactly the response a browser client most needs to read")))

;; ---------------------------------------------------------------------------
;; What the configuration refuses
;; ---------------------------------------------------------------------------

(defn- refusal
  "The message from refusing to start with this configuration, or nil if it
  was accepted."
  [configured]
  (try
    (cors/read-allowed-origins configured)
    nil
    (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest a-wildcard-is-refused-at-start-up-and-says-so
  (doseq [configured ["*" "https://*" "https://*.example.com" (str allowed ",*")]]
    (testing configured
      (let [message (refusal configured)]
        (is (some? message) "starting with a wildcard must not be possible")
        (is (str/includes? message cors/env-variable))
        (is (str/includes? message "There is no wildcard"))))))

(deftest a-value-that-is-not-an-origin-is-refused
  (doseq [configured ["example.com"
                      "https://example.com/"
                      "https://example.com/path"
                      "https://example.com?q=1"
                      "https://example.com#f"
                      "ftp://example.com"
                      "https://user:secret@example.com"
                      "https://example.com:0"
                      "https://example.com:99999"
                      "https://exa mple.com"
                      "//example.com"]]
    (testing configured
      (is (some? (refusal configured))
          "an entry a browser could never send is a configuration mistake, not a no-op"))))

(deftest one-bad-entry-refuses-the-whole-list
  (is (some? (refusal (str allowed ",not-an-origin," also-allowed)))
      "silently dropping it would leave an operator believing an origin was allowed"))

(deftest what-a-legal-allowlist-parses-to
  (is (= #{allowed also-allowed} (cors/read-allowed-origins (str allowed " , " also-allowed))))
  (is (= #{"http://localhost:8080"} (cors/read-allowed-origins ["http://localhost:8080"])))
  (is (= #{} (cors/read-allowed-origins nil)))
  (is (= #{"https://example.com"} (cors/read-allowed-origins "https://example.com"))
      "a default port is not inserted; an origin is compared as the browser sends it"))

;; ---------------------------------------------------------------------------
;; The two lists, discovered from the service
;; ---------------------------------------------------------------------------

(deftest allow-methods-are-the-methods-the-route-table-has
  (let [in-table (into (sorted-set)
                       (map (comp str/upper-case name :method))
                       (routes/routes {:config {:environment :test} :pool ::stub}))]
    (is (= in-table (into (sorted-set) cors/allowed-methods))
        (str "Access-Control-Allow-Methods must be exactly the methods the route table uses. "
             "Route table: " (pr-str in-table) ", CORS: " (pr-str cors/allowed-methods)))))

(def ^:private source-files
  (->> (file-seq (io/file "src"))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".clj")))
       (sort-by #(.getPath ^java.io.File %))))

(defn- forms-of
  "Every top-level form in a source file, as data."
  [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (let [eof (Object.)]
      (doall (take-while #(not (identical? eof %))
                         (repeatedly #(read {:read-cond :allow :eof eof} reader)))))))

(defn- header-keys-read-by
  "Every header name looked up as `(get-in … [:headers <x>] …)` in one file.

  A symbol is resolved in the file's own namespace, because the three headers
  that matter are named by a `def` rather than written inline — which is also
  why a regular expression over the text would find only `content-type` and
  report the other three as absent."
  [file]
  (let [forms (forms-of file)
        ns-sym (when (and (seq? (first forms)) (= 'ns (ffirst forms))) (second (first forms)))
        found (atom #{})]
    (when ns-sym
      (require ns-sym)
      (walk/postwalk
       (fn [node]
         (when (and (seq? node)
                    (= 'get-in (first node))
                    (vector? (nth node 2 nil))
                    (= :headers (first (nth node 2))))
           (let [key (second (nth node 2))]
             (swap! found conj (cond
                                 (string? key) key
                                 (symbol? key) (some-> (ns-resolve ns-sym key) deref)
                                 :else key))))
         node)
       forms))
    @found))

(deftest allow-headers-are-the-headers-the-service-reads
  (let [read-by-service (->> source-files
                             ;; `clofin.http.cors` itself reads `origin` and
                             ;; `access-control-request-method`. Those are set
                             ;; by the browser, are forbidden header names a
                             ;; page cannot set, and are part of the CORS
                             ;; protocol rather than of CloFin's API — so they
                             ;; are not what a page asks permission to send.
                             (remove #(str/ends-with? (str %) "cors.clj"))
                             (mapcat header-keys-read-by)
                             (filter string?)
                             (into (sorted-set)))]
    (is (seq read-by-service) "the scan found nothing, which means it is broken rather than clean")
    (is (= read-by-service (into (sorted-set) cors/allowed-request-headers))
        (str "Access-Control-Allow-Headers must be exactly the request headers this service "
             "reads — no guessed extras, and nothing missing. Read by src/: "
             (pr-str read-by-service) ", allowed: " (pr-str cors/allowed-request-headers)))))

(deftest exposed-headers-are-the-ones-a-browser-could-not-otherwise-read
  (testing "every response header CloFin sets that is not CORS-safelisted is exposed"
    ;; `content-type` is CORS-safelisted and readable without being named here;
    ;; the other three are not, and each is one a client displaying raw
    ;; responses would otherwise silently lose.
    (is (= #{"location" "x-correlation-id" "allow"} (set cors/exposed-response-headers)))
    (is (= middleware/correlation-header "x-correlation-id")
        "the correlation header's name is the one exposed")
    (is (not-any? #{"*"} cors/exposed-response-headers)
        "a wildcard would expose headers nobody decided to expose")))

;; ---------------------------------------------------------------------------
;; Configuration reaches the middleware
;; ---------------------------------------------------------------------------

(deftest the-configuration-key-and-the-variable-name-agree
  (is (= "CLOFIN_CORS_ALLOWED_ORIGINS" cors/env-variable)
      "clofin.config reads this name as a literal; the two must not drift")
  (is (= "CLOFIN_SOURCE_COMMIT" build-info/env-variable)))

(deftest the-chain-applies-the-configured-allowlist
  (let [handler (middleware/wrap echo-handler {:environment :test
                                               :cors {:allowed-origins allowed}})
        response (handler (request :get "/" {"origin" allowed}))]
    (is (= allowed (get-in response [:headers "access-control-allow-origin"])))
    (testing "and a preflight through the whole chain is answered 204 with a correlation id"
      (let [preflight (handler (request :options "/accounts"
                                        {"origin" allowed
                                         "access-control-request-method" "POST"}))]
        (is (= 204 (:status preflight)))
        (is (some? (get-in preflight [:headers "x-correlation-id"]))
            "a preflight is a request like any other and is traceable like one")))))

(deftest cors-allowed-origins-reads-the-configured-place
  (is (nil? (config/cors-allowed-origins {:environment :test})))
  (is (= allowed (config/cors-allowed-origins {:cors {:allowed-origins allowed}}))))
