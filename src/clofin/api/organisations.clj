(ns clofin.api.organisations
  "Organisation endpoints.

  Handlers are built by passing the components they need and returning a
  function of a request — so a test calls a function and asserts on a map,
  with no socket and no server (ADR-0010)."
  (:require [clofin.api.wire :as wire]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clofin.organisations.repository :as organisations]))

(defn create
  "`POST /organisations` — register a synthetic tenant.

  The id is generated here rather than accepted from the caller. The domain
  layer never generates identifiers (ARCHITECTURE.md §4), and accepting one
  from a caller would be an idempotency mechanism built by accident — that is
  TASK-002's job, done deliberately."
  [pool]
  (fn [request]
    (let [body (wire/read-object request)
          org  (organisations/create-organisation!
                pool
                {:id         (random-uuid)
                 :legal-name (wire/read-string-field body "legalName")
                 :short-name (wire/read-string-field body "shortName")
                 :status     :active})]
      (resp/created (str "/organisations/" (:id org)) (wire/organisation->wire org)))))

(defn show
  "`GET /organisations/:id`."
  [pool]
  (fn [request]
    (let [id  (wire/read-uuid (get-in request [:path-params :id]) "id")
          org (organisations/find-organisation pool id)]
      (when-not org
        (err/not-found! "No such organisation" {:id (str id)}))
      (resp/ok (wire/organisation->wire org)))))
