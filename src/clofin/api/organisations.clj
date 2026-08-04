(ns clofin.api.organisations
  "Organisation endpoints.

  Handlers are built by passing the components they need and returning a
  function of a request — so a test calls a function and asserts on a map,
  with no socket and no server (ADR-0010)."
  (:require [clofin.api.wire :as wire]
            [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clofin.organisations.repository :as organisations]
            [clofin.organisations.service :as organisation-service]))

(defn create
  "`POST /organisations` — register a synthetic tenant.

  The id is generated here rather than accepted from the caller. The domain
  layer never generates identifiers (ARCHITECTURE.md §4), and accepting one
  from a caller would be an idempotency mechanism built by accident — that is
  TASK-002's job, done deliberately.

  **This endpoint is the bootstrap and is deliberately unauthenticated.** No
  actor can exist before the organisation that holds one, so its audit event
  records no actor at all rather than a manufactured one — see
  `clofin.organisations.service` and ADR-0017 for why a `system` actor row was
  rejected. It is the only write in CloFin with a null `actor_id`, and
  `clofin.audit/bootstrap-actions` is what keeps it the only one.

  The transaction is opened here because something must open it and a service
  may not (`ARCHITECTURE.md` §4): the row and its audit event commit together
  or not at all (C-05, invariant I9). The request is parsed *before* the
  transaction, so a `400` never opens one."
  [pool]
  (fn [request]
    (let [body      (wire/read-object request)
          candidate {:id         (random-uuid)
                     :legal-name (wire/read-string-field body "legalName")
                     :short-name (wire/read-string-field body "shortName")
                     :status     :active}
          org (db/with-transaction [tx pool]
                (organisation-service/create-organisation!
                 tx {:organisation   candidate
                     :correlation-id (:correlation-id request)}))]
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
