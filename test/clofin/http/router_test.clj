(ns clofin.http.router-test
  (:require [clofin.http.router :as router]
            [clofin.routes :as routes]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- echo [name] (fn [request] {:status 200 :body {:handler name :params (:path-params request)}}))

(def ^:private table
  [{:method :get    :path "/accounts"                    :handler (echo "list")}
   {:method :post   :path "/accounts"                    :handler (echo "create")}
   {:method :get    :path "/accounts/:id"                :handler (echo "show")}
   {:method :get    :path "/accounts/:id/entries"        :handler (echo "entries")}
   {:method :post   :path "/payments/:id/approvals"      :handler (echo "approve")}])

(def ^:private handler (router/router (router/compile-routes table)))

(deftest static-and-parameterised-routing
  (testing "a static path dispatches to its handler"
    (is (= "list" (get-in (handler {:request-method :get :uri "/accounts"}) [:body :handler]))))

  (testing "the same path under a different method dispatches elsewhere"
    (is (= "create" (get-in (handler {:request-method :post :uri "/accounts"}) [:body :handler]))))

  (testing "path parameters are bound"
    (let [response (handler {:request-method :get :uri "/accounts/abc-123"})]
      (is (= "show" (get-in response [:body :handler])))
      (is (= {:id "abc-123"} (get-in response [:body :params])))))

  (testing "a parameter followed by a literal segment still matches"
    (let [response (handler {:request-method :get :uri "/accounts/abc-123/entries"})]
      (is (= "entries" (get-in response [:body :handler])))
      (is (= {:id "abc-123"} (get-in response [:body :params])))))

  (testing "trailing and duplicated slashes are tolerated"
    (is (= 200 (:status (handler {:request-method :get :uri "/accounts/"}))))
    (is (= 200 (:status (handler {:request-method :get :uri "//accounts"}))))))

(deftest unmatched-requests
  (testing "an unknown path is 404"
    (let [response (handler {:request-method :get :uri "/nope"})]
      (is (= 404 (:status response)))
      (is (= "https://clofin.dev/problems/not-found" (get-in response [:body "type"])))))

  (testing "a known path under an unsupported method is 405, not 404"
    (let [response (handler {:request-method :delete :uri "/accounts"})]
      (is (= 405 (:status response)))
      (is (= "GET, POST" (get-in response [:headers "allow"])))))

  (testing "a path with the wrong number of segments does not match a parameterised route"
    (is (= 404 (:status (handler {:request-method :get :uri "/accounts/abc/entries/extra"}))))))

(deftest route-table-is-validated-at-startup
  (testing "an unusable route fails fast rather than on first request"
    (is (thrown? clojure.lang.ExceptionInfo
                 (router/compile-routes [{:method :fetch :path "/x" :handler identity}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (router/compile-routes [{:method :get :path "no-leading-slash" :handler identity}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (router/compile-routes [{:method :get :path "/x" :handler "not callable"}]))))

  (testing "duplicate routes are rejected — the second would be unreachable"
    (is (thrown? clojure.lang.ExceptionInfo
                 (router/compile-routes [{:method :get :path "/x" :handler identity}
                                         {:method :get :path "/x" :handler identity}])))))

(deftest route-metadata-reaches-the-handler
  (testing "a handler can see which route matched, without the compiled internals"
    (let [captured (atom nil)
          h (router/router (router/compile-routes
                            [{:method :get :path "/accounts/:id" :operation-id "getAccount"
                              :handler (fn [r] (reset! captured (:route r)) {:status 204})}]))]
      (h {:request-method :get :uri "/accounts/1"})
      (is (= "getAccount" (:operation-id @captured)))
      (is (nil? (:segments @captured)))
      (is (nil? (:handler @captured))))))

;; ---------------------------------------------------------------------------
;; TASK-018 — table order never decides a dispatch
;;
;; The router has **no** literal-over-parameter precedence: of the routes whose
;; method and path match, the first in table order wins. TASK-018's brief
;; assumed the opposite. Rather than add a precedence rule, this asserts the
;; property that makes order irrelevant for the table CloFin actually serves:
;; no two of its routes with one method can match one path. Then where
;; `/payment-instructions/by-idempotency-key/:key` sits in the table cannot
;; matter, and neither can the position of any route added after it.
;; ---------------------------------------------------------------------------

(defn- segments [path] (vec (remove str/blank? (str/split path #"/"))))

(defn- can-both-match?
  "True when some request path matches both route paths: same segment count,
  and at every position a parameter on either side or equal literals."
  [a b]
  (let [sa (segments a) sb (segments b)]
    (and (= (count sa) (count sb))
         (every? (fn [[x y]] (or (str/starts-with? x ":") (str/starts-with? y ":") (= x y)))
                 (map vector sa sb)))))

(defn- ambiguous-pairs [table]
  (for [[i a] (map-indexed vector table)
        [j b] (map-indexed vector table)
        :when (< i j)
        :when (= (:method a) (:method b))
        :when (can-both-match? (:path a) (:path b))]
    [(:method a) (:path a) (:path b)]))

(def ^:private served (routes/routes {:config {:environment :test} :pool ::stub}))

(deftest ac-18-no-two-served-routes-with-one-method-can-match-one-path
  (is (< 30 (count served)) "the discovery found the route table (non-vacuity)")
  (is (empty? (ambiguous-pairs served))
      (str "these same-method routes can match one path, so table order decides "
           "between them: " (pr-str (vec (ambiguous-pairs served)))))
  (testing "the checker sees an ambiguity when there is one (the negative control)"
    (is (= [[:get "/payment-instructions/by-idempotency-key/:key"
             "/payment-instructions/:id/:anything"]]
           (vec (ambiguous-pairs
                 (conj (vec (filter #(= "/payment-instructions/by-idempotency-key/:key" (:path %))
                                    served))
                       {:method :get :path "/payment-instructions/:id/:anything"})))))))

(deftest ac-18-the-lookup-and-the-instruction-read-dispatch-the-same-in-either-order
  (let [dispatch (fn [table uri]
                   (get-in (router/match (router/compile-routes table) :get uri)
                           [:route :operation-id]))]
    (doseq [table [served (vec (reverse served))]]
      (is (= "lookupPaymentInstructionByIdempotencyKey"
             (dispatch table "/payment-instructions/by-idempotency-key/k-1")))
      (is (= "getPaymentInstruction"
             (dispatch table "/payment-instructions/00000000-0000-4000-8000-000000000018")))
      (testing "a key that spells a sub-resource is still a key on GET"
        (is (= "lookupPaymentInstructionByIdempotencyKey"
               (dispatch table "/payment-instructions/by-idempotency-key/submission")))))))

(deftest first-match-wins-is-the-routers-actual-rule
  (testing "documented by a test rather than assumed: with two same-method routes
            that can both match, the earlier one is chosen — which is why the
            served table is held to having none"
    (let [h (fn [table] (get-in (router/match (router/compile-routes table) :get "/x/literal")
                                [:route :path]))]
      (is (= "/x/:p" (h [{:method :get :path "/x/:p" :handler identity}
                         {:method :get :path "/x/literal" :handler identity}])))
      (is (= "/x/literal" (h [{:method :get :path "/x/literal" :handler identity}
                              {:method :get :path "/x/:p" :handler identity}]))))))
