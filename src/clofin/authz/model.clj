(ns clofin.authz.model
  "Actors, roles and permissions. **Default deny.**

  Three properties hold here, and each one is a control rather than a
  preference (`docs/COMPLIANCE.md` C-08, PR-070):

  1. **An absent permission is a denied permission.** `permitted?` answers a
     question about a set; there is no fallback branch, no wildcard and no
     `:else true`.
  2. **There is no superuser.** No role holds every permission, and
     `clofin.authz.model-test` asserts it — so a role added later that quietly
     accumulated the whole set would fail the build rather than the next audit.
     If a test needs broad rights, its fixture grants them role by role; that
     fixture then doubles as documentation of what a role can do.
  3. **A suspended actor holds nothing.** Not \"holds their permissions but is
     flagged\" — nothing. Suspension that the caller has to remember to check is
     suspension that one caller forgets.

  Roles are named identically to the `role_known` check constraint in migration
  `0005`, and a test asserts the two lists agree. What each role *may do* lives
  here rather than in a table, because a permission set stored as rows is a
  permission set editable by anyone who can write those rows — and least
  privilege would then be a matter of what the data happened to say that day.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.error :as err]))

;; ---------------------------------------------------------------------------
;; The vocabulary
;; ---------------------------------------------------------------------------

(def permissions
  "Every permission CloFin recognises.

  Named per operation rather than per resource, because \"may read a payment\"
  and \"may approve a payment\" are different questions with different answers
  and combining them into `:payment/write` is how an operator acquires an
  approver's authority by accident.

  Sorted so that error detail and generated documentation are stable between
  runs."
  (into (sorted-set)
        [;; Reading the organisation an actor belongs to. Added by the `ref-1`
         ;; release audit (**A-006**): `GET /organisations/:id` was the one
         ;; business route that neither authenticated nor authorised, so C-08's
         ;; "on every operation" was false and anyone holding a tenant UUID
         ;; could read its legal name. There is no `:organisation/create`
         ;; beside it, because creation is the documented unauthenticated
         ;; bootstrap — no actor exists to hold a permission before the
         ;; organisation that holds actors.
         :organisation/read
         :account/create
         :account/read
         :entry/post
         :entry/read
         :payment/create
         :payment/read
         :payment/amend
         :payment/cancel
         :payment/submit
         :payment/approve
         :payment/reject
         :approval/read
         :audit/read
         ;; Settlement (TASK-004). One permission for every mutating settlement
         ;; operation — creating a batch, submitting it, recording a scheme
         ;; response, sweeping timeouts — because they are one job done by one
         ;; person, and splitting them would suggest an organisation could grant
         ;; the power to release money without the power to batch it.
         ;;
         ;; **No role holds this and `:payment/approve`.** Asserted in
         ;; `clofin.authz.model-test` beside the existing separation
         ;; assertions: an actor who could approve a payment and then settle it
         ;; is a maker-checker boundary with one person on both sides of the
         ;; last step (C-01, C-08).
         :settlement/execute
         ;; Reconciliation (TASK-008). Split into a read and an execute for the
         ;; reason `:payment/read` and `:payment/approve` are split: reading a
         ;; break queue and *correcting the books* are different questions with
         ;; different answers, and an approver who must read an adjustment in
         ;; order to decide on it should not thereby acquire the right to
         ;; propose one.
         ;;
         ;; **No role holds `:reconciliation/execute` and `:payment/approve`.**
         ;; Asserted in `clofin.authz.model-test` beside the existing separation
         ;; assertions: an actor who could propose an adjustment and then
         ;; approve it would be a maker–checker boundary with one person on both
         ;; sides — which is the control C-01 exists for, and which
         ;; `clofin.authz.approval/evaluate` refuses per adjustment as well.
         ;; The permission split is the belt to that brace.
         :reconciliation/execute
         :reconciliation/read]))

(def actor-statuses
  "Every status an actor row may carry.

  Identical to the `actor_status_known` check constraint in migration `0005`,
  and compared with the **live catalogue** — not with the migration text — by
  `clofin.db.vocabulary-test`. Declared here because it was the one closed
  vocabulary in this namespace with no owner at all: the schema constrained the
  column, the code compared against `:active` in one place, and nothing related
  the two (audit finding **A-014**).

  `:active` is the only status that grants anything. That is not a property of
  this set but of `granted`, which returns the empty set for every other value
  — including a status the database allows and this list does not know about.
  The vocabulary fails closed in the direction that matters, and
  `clofin.authz.model-test` asserts it does so for every member here rather
  than for the one member someone sampled."
  (into (sorted-set) [:active :suspended]))

(def roles
  "Every role an actor may hold.

  Identical to the `role_known` check constraint in migration `0005`; a role
  present in one and not the other is caught by `clofin.authz.model-test`
  rather than by an insert failing in production."
  (into (sorted-set) [:operator :approver :controller :compliance :auditor]))

(def role-permissions
  "What each role may do. This map **is** the access control model.

  Read it as the answer to \"what can this role do?\" — the whole answer, with
  nothing implied. Four things are worth stating about the shape:

  - **`:operator` is the maker.** It creates, amends, cancels and submits, and
    it cannot approve. That is not a UI choice; it is C-01 expressed as a
    permission set, and it holds even for an instruction the operator did not
    create.
  - **`:approver` is the checker.** Approving and rejecting are separate
    permissions because refusing a payment and permitting one are different
    acts with different consequences, and an organisation may reasonably want
    an actor who can do the first and not the second.
  - **`:controller` supervises; it does not approve.** It posts journal entries
    and opens accounts — acts that move money in the ledger — cancels
    instructions, and **executes settlement**. Giving it `:payment/approve` as
    well would produce a role that is both maker-adjacent and checker, which is
    the exact shape C-01 exists to prevent — and it is why `:settlement/execute`
    lands here rather than on `:approver`: the actor who agreed a payment should
    not also be the actor who pushes it out of the door.
  - **`:auditor` is read-only.** Every permission it holds is a read. An
    auditor who can change the thing being audited is not an auditor.

  **`:organisation/read` is held by every role**, and that is a grant rather
  than an oversight. The permission reads *the actor's own* organisation — the
  handler takes the organisation from the principal and refuses any other with
  `403` — so it discloses to an actor the tenant they already act for. A role
  that could raise a payment but not read the name of the organisation it
  belongs to would be a role no interface could render, and narrowing it
  further would make least privilege a slogan rather than a boundary: the
  boundary that matters here is the *tenant* one, and that is enforced on the
  organisation acted on, not on the verb.

  No role holds every permission. See the namespace docstring."
  {:operator   #{:payment/create :payment/read :payment/amend :payment/cancel
                 :payment/submit :account/read :entry/read :organisation/read}
   :approver   #{:payment/approve :payment/reject :payment/read
                 :approval/read :account/read :entry/read :organisation/read
                 ;; A checker who cannot read the adjustment they are being
                 ;; asked to approve is a rubber stamp, which is the control
                 ;; failure the PRD opens with. It is a **read**: proposing one
                 ;; is `:reconciliation/execute`, which this role does not hold.
                 :reconciliation/read}
   :controller #{:account/create :account/read :entry/post :entry/read
                 :payment/read :payment/cancel :approval/read
                 :settlement/execute :organisation/read
                 ;; The operational reconciliation role. It lands beside
                 ;; `:settlement/execute` because it is the same job — the actor
                 ;; who pushed the money out is the one who reconciles what came
                 ;; back — and emphatically not beside `:payment/approve`.
                 :reconciliation/execute :reconciliation/read}
   :compliance #{:payment/read :account/read :entry/read :audit/read
                 :organisation/read :reconciliation/read}
   :auditor    #{:audit/read :payment/read :account/read :entry/read
                 :organisation/read :reconciliation/read}})

;; ---------------------------------------------------------------------------
;; Actors
;; ---------------------------------------------------------------------------
;;
;; An actor value is `{:id :organisation-id :display-name :status :roles
;; :limits}`, as `clofin.authz.repository` assembles it from `actor`,
;; `actor_role` and `approver_limit`. Nothing here reads a row; these are
;; functions over that value.

(defn active?
  "True when the actor may act at all."
  [actor]
  (= :active (:status actor)))

(defn granted
  "Every permission this actor holds, as a set.

  The union over their roles — and **empty** for a suspended actor, whatever
  roles are recorded against them. An unknown role contributes nothing rather
  than raising: a role the database allows and this model does not know about
  is a deployment mid-migration, and the safe reading of an unrecognised role
  is that it grants nothing."
  [actor]
  (if (active? actor)
    (into #{} (mapcat role-permissions) (:roles actor))
    #{}))

(defn permitted?
  "True when `actor` holds `permission`.

  Deliberately total and deliberately dull. Every interesting decision — who
  may approve *this* instruction — belongs to `clofin.authz.approval`, which
  needs the instruction to answer. This function only ever answers \"is this
  verb in this actor's set?\", and an unknown permission is denied like any
  other absent one."
  [actor permission]
  (contains? (granted actor) permission))

(defn authorise!
  "Return `actor`, or throw `:forbidden` naming the permission that was missing.

  The permission is named in the error and the actor's own permissions are not.
  A caller that lacks a right should be told which right; telling it which
  rights it *does* hold turns a refusal into a capability listing."
  [actor permission]
  (when-not (contains? permissions permission)
    ;; A typo in a handler must not read as a denial — it would look exactly
    ;; like a correctly refused request while actually being unreachable code.
    (err/invalid! (str "Unknown permission: " permission)
                  {:permission (str permission)
                   :known (mapv str permissions)}))
  (when-not (permitted? actor permission)
    (err/forbidden! (str "This actor may not " (name permission))
                    {:permission (str (symbol permission))
                     :actor-status (name (:status actor :unknown))}))
  actor)

(defn approver?
  "True when the actor may record an approval at all.

  Separate from `(permitted? actor :payment/approve)` only in name: it exists
  so `clofin.authz.approval` can express the `:not-an-approver` refusal in the
  vocabulary of the control (C-08) rather than in the vocabulary of the
  permission set."
  [actor]
  (permitted? actor :payment/approve))
