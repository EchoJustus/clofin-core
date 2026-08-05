(ns clofin.organisations.service
  "Registering an organisation: the row and the audit event that records it,
  as one unit of work.

  Like `clofin.payments.approval-service`, every function here takes a `tx` — a
  connection inside a transaction the *caller* owns — and requires no
  `clofin.db.*` namespace at all. That is the control, not a style
  (`ARCHITECTURE.md` §4, [C-05]): a service able to open its own connection is a
  service able to write an audit event outside the change it describes, and
  `clofin.ledger.purity-test/a-service-cannot-open-its-own-transaction` fails
  the build if this namespace acquires one.

  ## The bootstrap identity

  `POST /organisations` is the one endpoint with no authenticated principal, and
  deliberately so: no actor can exist before the organisation that holds it, and
  making the endpoint authenticated would need an actor who belongs to no
  organisation — the superuser the authorisation model exists to avoid
  (003-REQ §6, `clofin.api.principal`).

  Its event therefore records **no actor**: `actor_id` is null, and null means
  *this change had no authenticated principal because none could exist yet*.
  The alternative — seeding a fake `system` actor row so the column is never
  null — was rejected: an actor row is a thing that can be granted roles and
  limits, and one that exists solely to sign the audit trail is an identity
  nobody administers appearing in a table an auditor reads as attribution.
  Recorded as ADR-0017
  (docs/ADR/0017-bootstrap-identity-for-organisation-creation.md).

  The meaning of that null is enforced rather than described:
  `clofin.audit/bootstrap-actions` names the actions allowed to carry one and
  `clofin.audit/event` refuses every other action a null actor (standing lesson
  **L-6**).

  [C-05]: docs/COMPLIANCE.md"
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.organisations.repository :as organisations]))

(defn create-organisation!
  "Register an organisation and record it, on the caller's transaction.

  Returns the organisation as stored. A refused registration — a duplicate
  short name, a short name the value type rejects — throws before the audit
  write is reached and takes the whole transaction with it, so a `409` leaves
  no event behind.

  The transaction precondition is checked before the first write rather than
  documented (audit finding **F-011**, standing lesson **L-13**): given a pool,
  the insert would commit on its own connection and a later audit failure would
  leave an organisation with no `organisation.created` event."
  [tx {:keys [organisation correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
  (let [org (organisations/create-organisation! tx organisation)]
    ;; Same transaction as the insert above (C-05, PR-075, invariant I9). The
    ;; event's `organisation_id` is the organisation it just created: the row
    ;; and the foreign key referencing it commit together, and an auditor
    ;; scoped to a tenant can see that tenant coming into existence rather than
    ;; finding a trail that begins after the fact.
    (audit-store/record! tx {:organisation-id (:id org)
                             ;; No actor, and no fake one. See the namespace
                             ;; docstring and ADR-0017.
                             :actor-id        nil
                             :action          "organisation.created"
                             :subject-type    "organisation"
                             :subject-id      (:id org)
                             ;; The organisation did not exist a moment ago, so
                             ;; there is no before — the same nil that marks
                             ;; every creation in this trail.
                             :before          nil
                             :after           (audit/organisation-subject org)
                             :correlation-id  correlation-id})
    org))
