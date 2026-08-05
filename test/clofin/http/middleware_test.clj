(ns clofin.http.middleware-test
  (:require [clofin.error :as err]
            [clofin.http.middleware :as mw]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(def ^:private dev-config {:environment :dev})
(def ^:private prod-config {:environment :prod})

(defn- body-stream [^String s]
  (ByteArrayInputStream. (.getBytes s StandardCharsets/UTF_8)))

(defn- json-request [body]
  {:request-method :post
   :uri "/test"
   :headers {"content-type" "application/json"}
   :body (body-stream body)})

;; ---------------------------------------------------------------------------
;; Correlation
;; ---------------------------------------------------------------------------

(deftest correlation-id
  (testing "one is generated when the caller supplies none"
    (let [handler (mw/wrap-correlation-id (fn [r] {:status 200 :body (:correlation-id r)}))
          response (handler {:request-method :get :uri "/" :headers {}})]
      (is (string? (:body response)))
      (is (= (:body response) (get-in response [:headers mw/correlation-header])))))

  (testing "a caller-supplied id is honoured so a trace survives across systems"
    (let [handler (mw/wrap-correlation-id (fn [r] {:status 200 :body (:correlation-id r)}))
          response (handler {:request-method :get :uri "/"
                             :headers {mw/correlation-header "upstream-req-42"}})]
      (is (= "upstream-req-42" (:body response)))))

  (testing "a caller-supplied id is untrusted input: sanitised and length-limited"
    (let [handler (mw/wrap-correlation-id (fn [r] {:status 200 :body (:correlation-id r)}))
          injected (handler {:request-method :get :uri "/"
                             :headers {mw/correlation-header "abc\r\nSet-Cookie: x=1"}})
          overlong (handler {:request-method :get :uri "/"
                             :headers {mw/correlation-header (apply str (repeat 500 "a"))}})]
      (is (not (str/includes? (:body injected) "\r")))
      (is (not (str/includes? (:body injected) "\n")))
      (is (not (str/includes? (:body injected) " ")))
      (is (<= (count (:body overlong)) 128))))

  (testing "an empty supplied id falls back to a generated one"
    (let [handler (mw/wrap-correlation-id (fn [r] {:status 200 :body (:correlation-id r)}))
          response (handler {:request-method :get :uri "/" :headers {mw/correlation-header "   "}})]
      (is (not (str/blank? (:body response)))))))

;; ---------------------------------------------------------------------------
;; JSON
;; ---------------------------------------------------------------------------

(deftest json-request-parsing
  (testing "a JSON body is parsed with string keys"
    (let [handler (mw/wrap-json-request (fn [r] {:status 200 :body (:json-body r)}))
          response (handler (json-request "{\"currency\":\"SGD\",\"minorUnits\":125000}"))]
      (is (= {"currency" "SGD" "minorUnits" 125000} (:body response)))))

  (testing "caller-supplied keys stay strings — keywordising untrusted input is unbounded"
    (let [handler (mw/wrap-json-request (fn [r] {:status 200 :body (keys (:json-body r))}))
          response (handler (json-request "{\"whatever\":1}"))]
      (is (every? string? (:body response)))))

  (testing "malformed JSON is a domain error, not a stack trace"
    (let [handler (mw/wrap-json-request (fn [_] {:status 200}))
          t (try (handler (json-request "{not json")) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :validation (:clofin/error (ex-data t))))))

  (testing "a non-JSON request is passed through untouched"
    (let [handler (mw/wrap-json-request (fn [r] {:status 200 :body (contains? r :json-body)}))
          response (handler {:request-method :get :uri "/" :headers {} :body nil})]
      (is (false? (:body response)))))

  (testing "an empty body parses to nil rather than failing"
    (let [handler (mw/wrap-json-request (fn [r] {:status 200 :body {:parsed (:json-body r)}}))
          response (handler (json-request ""))]
      (is (nil? (get-in response [:body :parsed]))))))

(deftest json-response-encoding
  (testing "data bodies are encoded and typed"
    (let [handler (mw/wrap-json-response (fn [_] {:status 200 :headers {} :body {"status" "ok"}}))
          response (handler {:request-method :get :uri "/"})]
      (is (= "application/json" (get-in response [:headers "content-type"])))
      (is (= {"status" "ok"} (json/read-str (:body response))))))

  (testing "an existing content-type is preserved, so problem+json survives"
    (let [handler (mw/wrap-json-response
                   (fn [_] {:status 400
                            :headers {"content-type" "application/problem+json"}
                            :body {"title" "nope"}}))
          response (handler {:request-method :get :uri "/"})]
      (is (= "application/problem+json" (get-in response [:headers "content-type"])))))

  (testing "instants and uuids are encoded rather than throwing"
    (let [id (random-uuid)
          handler (mw/wrap-json-response
                   (fn [_] {:status 200 :headers {}
                            :body {"id" id "at" (java.time.Instant/parse "2026-08-02T10:15:00Z")}}))
          decoded (json/read-str (:body (handler {:request-method :get :uri "/"})))]
      (is (= (str id) (get decoded "id")))
      (is (= "2026-08-02T10:15:00Z" (get decoded "at")))))

  (testing "a string body is left alone"
    (let [handler (mw/wrap-json-response (fn [_] {:status 200 :headers {} :body "raw"}))]
      (is (= "raw" (:body (handler {:request-method :get :uri "/"})))))))

;; ---------------------------------------------------------------------------
;; Error boundary
;; ---------------------------------------------------------------------------

(deftest domain-errors-become-problem-documents
  (let [handler (mw/wrap-errors (fn [_] (err/invalid! "Currency is not supported" {:currency "XYZ"}))
                                dev-config)
        response (handler {:request-method :post :uri "/accounts" :correlation-id "corr-1"})]
    (is (= 400 (:status response)))
    (is (= "application/problem+json" (get-in response [:headers "content-type"])))
    (is (= "https://clofin.dev/problems/validation" (get-in response [:body "type"])))
    (is (= "Currency is not supported" (get-in response [:body "detail"])))
    (is (= "corr-1" (get-in response [:body "instance"])))
    (is (= {:currency "XYZ"} (get-in response [:body "errors"])))))

(deftest each-error-category-maps-to-its-status
  (doseq [[type expected] {:validation 400 :unauthorised 401 :forbidden 403
                           :not-found 404 :conflict 409 :unprocessable 422
                           :unavailable 503}]
    (let [handler (mw/wrap-errors (fn [_] (err/fail! type "nope")) dev-config)]
      (is (= expected (:status (handler {:request-method :get :uri "/" :correlation-id "c"})))
          (str type " should map to " expected)))))

(deftest a-009-a-domain-error-does-not-publish-schema-identifiers
  (testing "C-11: repositories attach the constraint that refused a statement so a
            defect can be diagnosed; `errors.constraint` published it to anyone who
            could provoke a conflict, in every profile"
    (let [handler (mw/wrap-errors
                   (fn [_] (err/conflict! "An account with this code already exists"
                                          (merge {:code "1100-CLIENT-FUNDS"}
                                                 (err/internal
                                                  {:constraint "ledger_account_code_key"
                                                   :sql-state "23505"}))))
                   ;; The development profile, deliberately: the exposure was not
                   ;; profile-dependent, so neither is the fix.
                   dev-config)
          response (handler {:request-method :post :uri "/accounts" :correlation-id "c"})]
      (is (= 409 (:status response)))
      (is (= {:code "1100-CLIENT-FUNDS"} (get-in response [:body "errors"]))
          "the public half reaches the caller and nothing else does")
      (is (not (str/includes? (pr-str (:body response)) "ledger_account_code_key"))
          "the constraint name must not appear anywhere in the rendered document")
      (is (not (str/includes? (pr-str (:body response)) "23505"))))))

(deftest a-009-internal-data-is-kept-for-the-log-not-discarded
  (testing "removing it from the response must not remove it from the investigation"
    (let [data (merge {:code "1100"} (err/internal {:constraint "x_key" :sql-state "23505"}))]
      (is (= {:code "1100"} (err/public-data data)))
      (is (= {:constraint "x_key" :sql-state "23505"} (err/internal-data data))))))

(deftest defects-do-not-leak-internals
  (let [boom (fn [_] (throw (RuntimeException. "connection string: user=admin password=hunter2")))]
    (testing "in production the caller gets a correlation id and nothing else"
      (let [response ((mw/wrap-errors boom prod-config)
                      {:request-method :get :uri "/" :correlation-id "corr-9"})]
        (is (= 500 (:status response)))
        (is (= "corr-9" (get-in response [:body "instance"])))
        (is (not (str/includes? (get-in response [:body "detail"]) "hunter2")))))

    (testing "in development the detail is available, because that is the point of development"
      (let [response ((mw/wrap-errors boom dev-config)
                      {:request-method :get :uri "/" :correlation-id "corr-9"})]
        (is (= 500 (:status response)))
        (is (str/includes? (get-in response [:body "detail"]) "hunter2"))))))

;; ---------------------------------------------------------------------------
;; Query parameters
;; ---------------------------------------------------------------------------

(defn- query-params
  "The `:query-params` a request ends up with."
  [query-string]
  (let [captured (atom nil)
        handler ((mw/wrap-query-params (fn [r] (reset! captured (:query-params r)) {:status 200}))
                 {:request-method :get :uri "/accounts" :query-string query-string :headers {}})]
    (is (= 200 (:status handler)))
    @captured))

(deftest query-parameters-are-parsed
  (is (= {"organisationId" "abc" "from" "2026-02-01T00:00:00Z"}
         (query-params "organisationId=abc&from=2026-02-01T00:00:00Z")))
  (testing "a request with no query string still gets a map, not nil"
    (is (= {} (query-params nil)))
    (is (= {} (query-params "")))))

(deftest query-parameters-are-percent-decoded
  (testing "an instant's colons and a name's spaces survive the round trip"
    (is (= {"from" "2026-02-01T00:00:00Z"} (query-params "from=2026-02-01T00%3A00%3A00Z")))
    (is (= {"name" "Client funds"} (query-params "name=Client+funds")))
    (is (= {"name" "Client funds"} (query-params "name=Client%20funds")))))

(deftest a-parameter-without-a-value-is-empty-rather-than-missing
  (is (= {"organisationId" ""} (query-params "organisationId=")))
  (is (= {"flag" ""} (query-params "flag"))))

(deftest a-repeated-parameter-keeps-its-first-value
  (testing "silently using the last one is how a caller's typo becomes a different query"
    (is (= {"organisationId" "first"} (query-params "organisationId=first&organisationId=second")))))

(deftest a-malformed-query-string-is-a-domain-error-not-a-defect
  (testing "so the error boundary renders it as a 400 rather than a 500"
    (let [handler (mw/wrap (fn [_] {:status 200 :headers {} :body {}}) prod-config)
          response (handler {:request-method :get :uri "/accounts"
                             :query-string "organisationId=%ZZ" :headers {}})]
      (is (= 400 (:status response)))
      (is (= "https://clofin.dev/problems/validation"
             (get (json/read-str (:body response)) "type"))))))

;; ---------------------------------------------------------------------------
;; The assembled chain
;; ---------------------------------------------------------------------------

(deftest the-chain-encodes-problems-raised-below-it
  (testing "a domain error thrown by a handler comes back as encoded JSON, with a correlation id"
    (let [handler (mw/wrap (fn [_] (err/not-found! "No such account")) dev-config)
          response (handler {:request-method :get :uri "/accounts/missing" :headers {}})
          decoded (json/read-str (:body response))]
      (is (= 404 (:status response)))
      (is (string? (:body response)) "the body must be encoded, not a raw Clojure map")
      (is (= "https://clofin.dev/problems/not-found" (get decoded "type")))
      (is (= (get-in response [:headers mw/correlation-header]) (get decoded "instance"))))))

(deftest the-chain-parses-a-request-and-encodes-a-response
  (let [handler (mw/wrap (fn [r] {:status 200 :headers {} :body {"echo" (:json-body r)}}) dev-config)
        response (handler (assoc (json-request "{\"a\":1}") :headers {"content-type" "application/json"}))]
    (is (= 200 (:status response)))
    (is (= {"echo" {"a" 1}} (json/read-str (:body response))))))
