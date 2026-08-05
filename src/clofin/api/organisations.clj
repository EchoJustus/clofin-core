(ns clofin.api.organisations
  "Organisation endpoints.

  Handlers are built by passing the components they need and returning a
  function of a request — so a test calls a function and asserts on a map,
  with no socket and no server (ADR-0010)."
  (:require [clofin.api.principal :as principal]
            [clofin.api.wire :as wire]
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
  or not at all (C-05, invariant I9). Wire parsing happens *before* the
  transaction, so a malformed body never opens one — but the value type's own
  rules run inside it, since `organisation/organisation` is called by the
  repository. A short name the pattern refuses is therefore a `400` raised
  within an open transaction, which rolls back with nothing written. Correct
  either way, and worth stating precisely rather than claiming no `400` reaches
  the transaction."
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
  "`GET /organisations/:id` — retrieve the actor's own organisation.

  **Authenticated and authorised like every other business route.** Until the
  `ref-1` release audit it was neither (finding **A-006**): this namespace did
  not require `clofin.api.principal` at all, so any caller holding — or
  guessing — a tenant UUID could read its legal name, short name and status.
  That falsified C-08's claim to be enforced *on every operation*, which is the
  reason a single unguarded route is a control finding rather than a missing
  feature. `POST /organisations` above remains the documented unauthenticated
  bootstrap; it is the exception, and it is the only one.

  **The organisation acted on comes from the actor.** The path names one and it
  is *verified, not trusted* — exactly as `organisationId` is in every body and
  query string (`principal/assert-organisation!`). A foreign organisation is
  therefore `403`, not `404`: the caller is told it may not act for the tenant
  it named, and learns nothing about whether that tenant exists, because the
  comparison never reads one. Answering `404` would have been the accidental
  outcome of scoping the lookup by the principal's organisation, and it would
  have turned an authorisation boundary into an existence oracle in reverse —
  a caller could distinguish \"no such organisation\" from \"not yours\" by
  whether its *own* id worked.

  The `404` below therefore survives only for the case it describes: the
  principal's own organisation row is missing, which is a deleted tenant with a
  live actor, not a caller reaching across a boundary."
  [pool]
  (fn [request]
    (let [[actor _] (principal/for-request pool request :organisation/read)
          id  (wire/read-uuid (get-in request [:path-params :id]) "id")
          organisation-id (principal/assert-organisation! actor id)
          org (organisations/find-organisation pool organisation-id)]
      (when-not org
        (err/not-found! "No such organisation" {:id (str organisation-id)}))
      (resp/ok (wire/organisation->wire org)))))
