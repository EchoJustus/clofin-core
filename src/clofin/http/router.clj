(ns clofin.http.router
  "A small data-driven router.

  Routes are a vector of maps, so the route table is a value that can be
  inspected, printed and compared against `api/openapi.yaml` in a test:

      [{:method :get  :path \"/accounts/:id\" :handler #'show   :operation-id \"getAccount\"}
       {:method :post :path \"/accounts\"     :handler #'create :operation-id \"createAccount\"}]

  Path segments beginning with `:` are parameters and are bound into
  `:path-params` on the request. Nothing else is supported — no wildcards, no
  regex segments, no reverse routing. See
  docs/ADR/0010-thin-ring-compatible-http-adapter.md."
  (:require [clofin.error :as err]
            [clofin.http.response :as resp]
            [clojure.string :as str]))

(defn- segments [path]
  (into [] (remove str/blank?) (str/split path #"/")))

(defn- compile-route
  [{:keys [method path handler] :as route}]
  (when-not (#{:get :post :put :patch :delete :head :options} method)
    (err/invalid! (str "Unsupported route method: " method) {:route route}))
  (when-not (str/starts-with? (str path) "/")
    (err/invalid! (str "Route path must start with '/': " path) {:route route}))
  (when-not (ifn? handler)
    (err/invalid! (str "Route handler must be callable: " path) {:route route}))
  (assoc route :segments (mapv (fn [s]
                                 (if (str/starts-with? s ":")
                                   {:param (keyword (subs s 1))}
                                   {:literal s}))
                               (segments path))))

(defn compile-routes
  "Validate the route table once, at start-up, so a malformed route fails fast
  rather than on the first request that reaches it."
  [routes]
  (let [compiled (mapv compile-route routes)
        duplicates (->> compiled
                        (group-by (juxt :method :path))
                        (filter (fn [[_ v]] (> (count v) 1)))
                        (map first))]
    (when (seq duplicates)
      (err/invalid! "Duplicate routes" {:routes (vec duplicates)}))
    compiled))

(defn- match-segments
  "Bind path parameters, or nil when the route does not match."
  [route-segments request-segments]
  (when (= (count route-segments) (count request-segments))
    (reduce (fn [params [rs value]]
              (if-let [param (:param rs)]
                (assoc params param value)
                (if (= (:literal rs) value) params (reduced nil))))
            {}
            (map vector route-segments request-segments))))

(defn match
  "Find the route matching `method` and `uri`.

  Returns `{:route … :path-params …}`, or `{:status :method-not-allowed
  :allowed #{…}}` when the path exists under other methods — the distinction
  between 404 and 405 is part of an API contract, not an implementation
  detail."
  [compiled-routes method uri]
  (let [request-segments (segments uri)
        path-matches (keep (fn [route]
                             (when-let [params (match-segments (:segments route) request-segments)]
                               {:route route :path-params params}))
                           compiled-routes)]
    (or (first (filter #(= method (:method (:route %))) path-matches))
        (when (seq path-matches)
          {:status :method-not-allowed
           :allowed (into (sorted-set) (map (comp :method :route)) path-matches)}))))

(defn router
  "Build a handler from a compiled route table."
  [compiled-routes]
  (fn [request]
    (let [result (match compiled-routes (:request-method request) (:uri request))]
      (cond
        (:route result)
        ((:handler (:route result))
         (assoc request
                :path-params (:path-params result)
                :route (dissoc (:route result) :segments :handler)))

        (= :method-not-allowed (:status result))
        (-> (resp/problem {:status 405
                           :type :method-not-allowed
                           :title "Method not allowed"
                           :detail (str (str/upper-case (name (:request-method request)))
                                        " is not supported for " (:uri request))})
            (resp/with-header "allow"
              (->> (:allowed result) (map (comp str/upper-case name)) (str/join ", "))))

        :else
        (resp/problem {:status 404
                       :type :not-found
                       :title "Resource not found"
                       :detail (str "No route for " (:uri request))})))))
