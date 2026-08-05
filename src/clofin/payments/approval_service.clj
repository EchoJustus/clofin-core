(ns clofin.payments.approval-service
  "Recording an approval: the unit of work that binds the decision, the row and
  the audit event together.

  Every function here takes a `tx` — a connection inside a transaction the
  caller already owns, in practice the one
  `clofin.idempotency.repository/execute-once!` hands its effect. That is not a
  convenience. It is what makes [C-05] true: the approval, the resulting state
  change and the audit event describing them commit together or not at all, so
  an unaudited state change is not representable. A rolled-back approval leaves
  no audit event, which `clofin.api.approvals-api-test` asserts directly.

  **This namespace decides nothing.** Whether an actor may approve is
  `clofin.authz.approval/evaluate`, which is pure; where an instruction goes
  next is `clofin.payments.state/transition`, which reads the lifecycle table.
  What is left here is sequencing and effects — and, deliberately, no
  `clofin.db.*` requirement, so the persistence seam ADR-0012 names stays where
  it says it is. Everything reaches the database through a `repository`.

  **The precondition is checked, not described** (audit finding **F-011**,
  standing lesson **L-13**). Every function here opens with
  `clofin.audit.repository/assert-unit-of-work!`, which refuses a pool or an
  autocommit connection before the first write. A parameter named `tx` and a
  docstring make misuse visible in review; only the runtime check makes it fail
  closed for a REPL task, a script or a new adapter.

  The order of operations matters and is stated once, here:

  0. **Assert the unit of work**, before anything is written.
  1. **Lock the instruction.** An approval decided against a status that
     changed underneath it is an approval given to a payment nobody submitted.
  2. **Ask the lifecycle** whether the event is permitted at all, before asking
     who the actor is. An `approve` on a settled payment is a `409` regardless
     of who sent it, and answering `403` first would tell a caller that fixing
     their permissions would help.
  3. **Evaluate**, purely, against values read inside the lock.
  4. **Write** the decision, then the state change if the decision completes
     the requirement, then the audit event — all on `tx`.

  [C-05]: docs/COMPLIANCE.md"
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.authz.approval :as approval]
            [clofin.authz.repository :as authz]
            [clofin.error :as err]
            [clofin.payments.repository :as payments]
            [clofin.payments.state :as state]))

;; ---------------------------------------------------------------------------
;; Refusals
;; ---------------------------------------------------------------------------

(def refusal-status
  "How each refusal reason is reported over HTTP.

  Held as data beside the reasons themselves so that a reason added to
  `clofin.authz.approval/refusal-reasons` without an answer here fails
  `clofin.authz.approval-test` rather than becoming a `500` the first time a
  caller hits it.

  The split is ADR-0012's: `403` is \"you may not\", `409` is \"not from here\",
  `422` is \"understood, and the organisation is not set up for it\"."
  {:self-approval           :forbidden
   :not-an-approver         :forbidden
   :above-actor-limit       :forbidden
   :already-approved        :conflict
   :no-threshold-configured :unprocessable})

(def ^:private refusal-detail
  "What a refused caller is told. Each names the control it comes from, because
  a refusal an operator cannot explain to their own auditor is a refusal that
  gets escalated into a request to disable it."
  {:self-approval
   "The actor who created this payment instruction may not approve it (segregation of duties)"
   :not-an-approver
   "This actor does not hold the permission required to decide on this payment instruction"
   :above-actor-limit
   "This payment instruction's amount is above this actor's approval limit"
   :already-approved
   "This actor has already recorded a decision on this payment instruction"
   :no-threshold-configured
   "No approval threshold is configured for this organisation and currency, so no approval can be evaluated"})

(defn- refuse!
  "Turn a refused decision into the domain error the HTTP layer renders.

  The reason keyword travels in `errors.reason` so a caller branches on the
  control rather than on the prose, which may be reworded."
  [{:keys [reason] :as decision}]
  (err/fail! (or (refusal-status reason)
                 ;; Unreachable while the test above passes; if it ever is
                 ;; reached, a named 403 is a safer answer than a 500.
                 :forbidden)
             (or (refusal-detail reason) "This approval was refused")
             (-> decision
                 (dissoc :decision)
                 (assoc :reason (name reason)))))

;; ---------------------------------------------------------------------------
;; Deciding
;; ---------------------------------------------------------------------------

(defn- decision-context
  "Everything `evaluate` needs, read inside the caller's lock."
  [tx instruction actor]
  {:instruction        instruction
   :actor              actor
   :existing-approvals (authz/approvals-for tx (:id instruction))
   :thresholds         (authz/thresholds-for tx
                                             (:organisation-id instruction)
                                             (:currency (:amount instruction)))})

(defn evaluate-for
  "The decision `evaluate` would return for this actor and instruction, read
  from `tx`.

  Exposed so the approval queue can show an approver *why* a row is not theirs
  to approve using the same function that would refuse them (PR-015, AC-13). A
  queue that filtered rows with its own copy of the rule would be a second
  statement of C-01, and the second statement is the one that drifts."
  [tx instruction actor & {:keys [decision] :or {decision :approved}}]
  (approval/evaluate (assoc (decision-context tx instruction actor)
                            :decision decision)))

(defn decide!
  "Record one actor's decision on one instruction, on the caller's transaction.

  Returns `{:approval … :instruction … :decision …}`, where `:instruction` is
  the instruction in whatever state it now holds and `:decision` is the
  evaluation that permitted the write — carrying `:approvals-required`,
  `:approvals-held` and `:completes?`, so a caller reports the counting
  context without recomputing it.

  Throws rather than returning a refusal: a refusal here is an outcome the HTTP
  layer must render as a status code, and `clofin.error` is how this codebase
  says that. The pure function returns values; this one performs effects, and
  the two failure vocabularies are deliberately different."
  [tx {:keys [organisation-id instruction-id actor decision reason correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
  (let [event       (if (= :rejected decision) :reject :approve)
        instruction (payments/lock-instruction! tx organisation-id instruction-id)
        ;; The lifecycle first. `transition` raises `:conflict` naming the
        ;; state, the attempted event and what would have been permitted —
        ;; before anything is said about the actor.
        next-status (state/transition (:status instruction) event)
        _           (approval/assert-reason! decision reason)
        outcome     (approval/evaluate (assoc (decision-context tx instruction actor)
                                              :decision decision))
        _           (when (= :refused (:decision outcome)) (refuse! outcome))
        approval-id (random-uuid)
        recorded    (authz/record-approval! tx {:id             approval-id
                                                :instruction-id instruction-id
                                                :actor-id       (:id actor)
                                                :decision       decision
                                                :reason         reason})
        ;; The state change happens only when this decision completes the
        ;; requirement. A first approval on a two-approval band leaves the
        ;; instruction `pending-approval`, which is what AC-4 asserts.
        moved       (when (:completes? outcome)
                      (payments/transition! tx organisation-id instruction-id event))
        after       (if moved (:after moved) instruction)]
    ;; Same transaction as everything above (C-05, PR-075, invariant I9).
    ;;
    ;; **One event per thing that happened**, which is the whole of standing
    ;; lesson L-7 and the correction for audit finding F-005. Two different
    ;; things can happen here and they are not the same event:
    ;;
    ;;   1. A decision was recorded. Always. Its subject is the *approval* —
    ;;      the record that came into existence — not the payment, which may
    ;;      not have moved at all.
    ;;   2. The payment reached a new state. Only when this decision completed
    ;;      the requirement.
    ;;
    ;; Before F-005 there was one write, named `payment.approved`, emitted for
    ;; both. The first approval of a two-approval threshold therefore produced a
    ;; `payment.approved` event whose before and after digests were identical,
    ;; because the payment had not changed — an event asserting a transition
    ;; that had not occurred, in the table an auditor is told to trust.
    (audit-store/record! tx {:organisation-id organisation-id
                             :actor-id        (:id actor)
                             :action          "approval.recorded"
                             :subject-type    "approval"
                             :subject-id      approval-id
                             ;; The approval did not exist a moment ago, so
                             ;; there is no before — the same nil that marks
                             ;; every creation in this trail.
                             :before          nil
                             :after           (audit/approval-subject recorded)
                             :correlation-id  correlation-id})

    (when moved
      (audit-store/record! tx {:organisation-id organisation-id
                               :actor-id        (:id actor)
                               :action          (if (= :rejected decision)
                                                  "payment.rejected"
                                                  "payment.approved")
                               :subject-type    "payment-instruction"
                               :subject-id      instruction-id
                               :before          (audit/instruction-subject (:before moved))
                               :after           (audit/instruction-subject (:after moved))
                               :correlation-id  correlation-id}))

    {:approval    recorded
     :instruction after
     :decision    outcome
     :next-status next-status}))

;; ---------------------------------------------------------------------------
;; Withdrawal
;; ---------------------------------------------------------------------------

(defn withdraw!
  "Withdraw an actor's own approval, on the caller's transaction.

  Three rules, each of which would be a hole if it were left out:

  - **Only the actor who gave an approval may withdraw it.** Otherwise one
    approver could clear another's decision and the count would say nothing
    about who agreed to what.
  - **Only while the instruction is still `pending-approval`.** Once the
    threshold is met the decision is made, and the way back is to amend — which
    invalidates every approval and returns the instruction to `draft` (PR-014).
    Unwinding an `approved` instruction one approval at a time would leave it
    approved with fewer approvals than its band requires.
  - **The row is not deleted.** `invalidated_at` is set; `approval_no_delete`
    refuses a `DELETE` at the database anyway. An approval that was given and
    then withdrawn is exactly the history an investigation needs."
  [tx {:keys [organisation-id instruction-id approval-id actor correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
  (let [instruction (payments/lock-instruction! tx organisation-id instruction-id)
        existing    (or (authz/find-approval tx approval-id)
                        (err/not-found! "No such approval on this payment instruction"
                                        {:id (str approval-id)}))]
    (when-not (= instruction-id (:instruction-id existing))
      (err/not-found! "No such approval on this payment instruction"
                      {:id (str approval-id)}))
    (when-not (= (:id actor) (:actor-id existing))
      (err/forbidden! "Only the actor who gave an approval may withdraw it"
                      {:id (str approval-id)}))
    (when-not (= :pending-approval (:status instruction))
      (err/conflict!
       (str "Cannot withdraw an approval from a payment instruction that is "
            (name (:status instruction))
            "; amend it instead, which invalidates every approval")
       {:instruction-status (name (:status instruction))
        :attempted "withdraw-approval"
        :withdrawable-in ["pending-approval"]}))
    (when-not (approval/live? existing)
      (err/conflict! "This approval has already been invalidated"
                     {:id (str approval-id)}))
    (authz/invalidate-approval! tx approval-id)
    (let [withdrawn (authz/find-approval tx approval-id)]
      (audit-store/record! tx {:organisation-id organisation-id
                               :actor-id        (:id actor)
                               :action          "approval.withdrawn"
                               :subject-type    "approval"
                               :subject-id      approval-id
                               :before          (audit/approval-subject existing)
                               :after           (audit/approval-subject withdrawn)
                               :correlation-id  correlation-id})
      {:approval    withdrawn
       :instruction instruction})))

;; ---------------------------------------------------------------------------
;; The queue
;; ---------------------------------------------------------------------------

(defn queue
  "The instructions awaiting approval, with the context an approver needs to
  decide (PR-015, AC-13).

  Each row carries the amount, the counterparty, the purpose, the approvals
  already given and how many more are required — because an approval given
  without context is a rubber stamp, which is the control failure the PRD opens
  with. A queue that showed only an id would push the approver into another
  system to find out what they were agreeing to.

  Rows the actor may not approve are **shown, with the reason**, rather than
  filtered out. Hiding them would be a control implemented in a list query —
  the same mistake as hiding the button — and it would leave a maker unable to
  see that their own payment is waiting. The refusal shown is produced by
  `evaluate`, the same function that would refuse the approval itself."
  [source organisation-id actor]
  (let [{:keys [instructions truncated?]}
        (payments/list-instructions source organisation-id {:status :pending-approval})
        by-instruction (authz/approvals-for-instructions
                        source (mapv :id instructions))]
    {:truncated? truncated?
     :items
     (mapv (fn [instruction]
             (let [existing (get by-instruction (:id instruction) [])
                   thresholds (authz/thresholds-for source organisation-id
                                                    (:currency (:amount instruction)))
                   outcome (approval/evaluate {:instruction instruction
                                               :actor actor
                                               :existing-approvals existing
                                               :thresholds thresholds})
                   live (approval/live-approvals existing)]
               {:instruction         instruction
                :approvals           live
                :approvals-held      (count live)
                :approvals-required  (approval/approvals-required thresholds (:amount instruction))
                :approvals-remaining (some-> (approval/approvals-required thresholds (:amount instruction))
                                             (- (count live))
                                             (max 0))
                :can-approve?        (= :permitted (:decision outcome))
                :refusal-reason      (when (= :refused (:decision outcome))
                                       (:reason outcome))}))
           instructions)}))
