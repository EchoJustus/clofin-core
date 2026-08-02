(ns clofin.http.router-test
  (:require [clofin.http.router :as router]
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
