(ns clofin.api.principal
  "Who is making this request, and what they are allowed to do.

  This namespace is what retires the authentication markers TASK-001 and
  TASK-002 left behind. Until now the organisation a request acted on and the
  actor it claimed to be came from the request itself — stated plainly in the
  API contract as *not* an access control, because a caller naming its own
  tenant is not one. Both now come from here.

  ## How authentication works, stated plainly

  The actor is named by an **`X-Actor-Id` header** and resolved against the
  seeded `actor` table. There is no identity provider, no token and no
  signature: OIDC integration is deliberately out of scope
  (`docs/briefs/003-TASK-authorisation-and-audit-trail.md`), because the
  permission *model* is the interesting part and provider wiring is plumbing.

  **This is not authentication in a sense that resists an adversary.** Anyone
  who can reach the service can claim to be any seeded actor. It is said here,
  in `api/openapi.yaml`, and in `docs/COMPLIANCE.md` §4, rather than being left
  for a reader to discover. What it *does* deliver is the thing the increment
  is about: a real principal with a real organisation and a real permission
  set, so that segregation of duties and least privilege are decided against an
  identity the application holds rather than one the request asserts.

  ## What it gives the rest of the system

  - **The organisation.** Taken from the actor's row, never from the request.
    A body or query string that names a *different* organisation is refused
    rather than ignored — see `assert-organisation!`.
  - **The permission check at the API boundary** (C-08). Every handler names
    the permission it needs; there is no handler that authenticates without
    authorising, because \"authenticated\" is not a permission.
  - **The actor id an audit event records** (C-05) and the maker–checker
    comparison uses (C-01)."
  (:require [clofin.api.wire :as wire]
            [clofin.authz.model :as model]
            [clofin.authz.repository :as authz]
            [clofin.error :as err]
            [clojure.string :as str]))

(def actor-header
  "Lower-cased, because the HTTP adapter lower-cases header names — HTTP header
  names are case-insensitive but Clojure map keys are not."
  "x-actor-id")

(defn- read-actor-id
  [request]
  (let [raw (get-in request [:headers actor-header])]
    (when-not (and (string? raw) (not (str/blank? raw)))
      (err/fail! :unauthorised
                 "Header 'X-Actor-Id' is required: this operation acts as an actor"
                 {:header "X-Actor-Id"}))
    (wire/read-uuid raw "X-Actor-Id")))

(defn authenticate
  "The actor this request acts as, with roles and limits. Throws `401` if there
  is not one.

  A suspended actor authenticates and holds no permissions, so it is refused by
  `authorise!` with the permission named rather than by a second status code
  here. That keeps one answer to \"why was I refused?\" — the permission — and
  avoids leaking whether a given actor id is suspended or simply unprivileged.

  An unknown actor id is `401`, not `404`: the caller failed to identify
  themselves, and confirming which UUIDs name real actors would let anyone able
  to guess one enumerate the actor table."
  [pool request]
  (let [id (read-actor-id request)]
    (or (authz/find-actor pool id)
        (err/fail! :unauthorised "No such actor" {:header "X-Actor-Id"}))))

(defn assert-organisation!
  "Refuse a request whose stated organisation is not the principal's.

  `organisationId` remains in the contract — it scopes the idempotency key and
  it appears in every `Location` header — but it is now **verified, not
  trusted**. Refusing a mismatch rather than ignoring it matters: silently
  reinterpreting the request as being about the caller's own organisation would
  let a caller believe it had acted on another tenant's payment and been told
  it succeeded.

  `403` rather than `404`: the caller named an organisation and is being told
  it may not act for it. It learns nothing about whether that organisation
  exists, because this comparison never reads one."
  [actor organisation-id]
  (when (and organisation-id (not= organisation-id (:organisation-id actor)))
    (err/forbidden! "This actor may not act for the organisation named in the request"
                    {:organisation-id (str organisation-id)}))
  (:organisation-id actor))

(defn authorise!
  "Authenticate, check `permission`, and return the actor.

  The single call a handler makes. Combining the two steps is deliberate: a
  handler that authenticated and forgot to authorise would look correct in
  review and would be C-08's failure mode exactly."
  [pool request permission]
  (model/authorise! (authenticate pool request) permission))

(defn for-request
  "`[actor organisation-id]` for a handler that also needs the organisation.

  `body` is the decoded request body when there is one; the organisation it
  names — or the `?organisationId=` query parameter on a read — is verified
  against the actor's own and then discarded in favour of it."
  ([pool request permission] (for-request pool request permission nil))
  ([pool request permission body]
   (let [actor (authorise! pool request permission)
         stated (wire/read-stated-organisation-id request body)]
     [actor (assert-organisation! actor stated)])))

(defn authenticated-for
  "`[actor organisation-id]` where the **domain** decides the permission.

  Used only by the approval endpoints. Everywhere else the boundary checks a
  permission before reading anything, because the domain beneath it does not
  look at the actor at all. `clofin.authz.approval/evaluate` does: it checks
  `:payment/approve` (or `:payment/reject`) *and* the maker, the limit and the
  count, and it ranks the reasons deliberately — segregation of duties first,
  because it is the only refusal that can never be resolved.

  A boundary check here would pre-empt that ranking, and an operator who
  submitted a payment and then tried to approve it would be told
  `not-an-approver` when the reason that actually governs is `self-approval`.
  The permission is not skipped; it is checked by the function whose answer
  reaches the caller, which is also the function an auditor can replay."
  ([pool request] (authenticated-for pool request nil))
  ([pool request body]
   (let [actor (authenticate pool request)
         stated (wire/read-stated-organisation-id request body)]
     [actor (assert-organisation! actor stated)])))
