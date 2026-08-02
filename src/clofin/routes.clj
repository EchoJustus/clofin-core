(ns clofin.routes
  "The route table.

  Routes are data. Keeping them in one value — rather than scattered across
  handler namespaces via macros — means the API surface can be read at a
  glance, diffed in review, and compared against `api/openapi.yaml` by a test.

  `operation-id` matches the corresponding OpenAPI `operationId`; that is the
  join key the contract test uses."
  (:require [clofin.api.health :as health]))

(defn routes
  "Build the route table for a running system."
  [{:keys [config pool]}]
  [{:method :get :path "/healthz" :operation-id "getHealth"
    :handler (health/healthz config)
    :summary "Liveness probe"}

   {:method :get :path "/readyz" :operation-id "getReadiness"
    :handler (health/readyz config pool)
    :summary "Readiness probe including database reachability"}

   {:method :get :path "/" :operation-id "getServiceInfo"
    :handler (health/info config)
    :summary "Service description and scope disclaimer"}])
