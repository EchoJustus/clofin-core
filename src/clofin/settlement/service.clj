(ns clofin.settlement.service
  "Settlement as units of work: constructing a batch, submitting it, recording
  what the simulated scheme said, and stopping waiting.

  Every function here takes a `tx` — a connection inside a transaction the
  *caller* owns — and requires no `clofin.db.*` namespace at all, exactly as
  `clofin.payments.approval-service` does. That is the control rather than a
  style (`ARCHITECTURE.md` §4, [C-05], invariant I9): the state change, the
  finality posting and the audit event describing them commit together or not at
  all, so an unaudited settlement is not representable.
  `clofin.ledger.purity-test` fails the build if this namespace acquires a
  connection.

  **This namespace decides nothing about where a payment may go.** That is
  `clofin.payments.state`, whose table already carries `release`, `settle`,
  `fail` and `return`; this code drives those arrows and never redraws them
  (ADR-0014). What is left here is sequencing, effects, and the one judgement
  that is genuinely settlement's: which audit event a given moment deserves.

  ## The audit vocabulary, and why the terms are not interchangeable

  Standing lesson **L-7** — an action named after a transition is emitted only
  in the transaction where that transition commits. Four different things happen
  in this namespace and they are four different events:

  | Moment | Event | Subject |
  |---|---|---|
  | A batch is constructed | `settlement-batch.created` | the batch |
  | A batch is submitted, and its members leave `approved` | `settlement-batch.submitted` **and one** `payment.released` **per member** | batch, then each instruction |
  | The scheme answers about one item | `payment.settled` / `payment.returned` | that instruction |
  | The last unresolved item resolves | `settlement-batch.completed` | the batch |

  A scheme response *recorded* is not a payment *settled*: a duplicate delivery
  records nothing new and emits nothing, and an `ack` moves no payment at all.
  A batch with unresolved items is not *completed* however many responses have
  arrived — `settlement-batch.completed` is gated on the batch not having been
  complete before this transaction and being complete after it, which is the
  only reading of \"the transition commits here\" that survives a late response
  changing a status that was already terminal.

  [C-05]: docs/COMPLIANCE.md"
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.error :as err]
            [clofin.ledger.repository :as ledger]
            [clofin.payments.posting :as posting]
            [clofin.payments.repository :as payments]
            [clofin.settlement.batch :as batch]
            [clofin.settlement.repository :as settlement]
            [clofin.settlement.scheme :as scheme]))

(def default-timeout-seconds
  "How long CloFin waits for a scheme before an operator may sweep.

  A default rather than a policy: the sweep is an explicit operator action and
  the horizon travels with the request, so an organisation that settles same-day
  and one that settles overnight are not forced to share a number chosen here.
  One hour is a value that makes the endpoint usable without an argument, not a
  claim about any scheme's behaviour."
  3600)

;; ---------------------------------------------------------------------------
;; Accounts
;; ---------------------------------------------------------------------------

(def settlement-roles
  "The chart-of-accounts roles a settlement touches, across all three postings.

  Resolved up front so a batch whose organisation has not opened one of them is
  refused at submission with the missing codes named — rather than at the
  posting of the seventh instruction, half way through a batch."
  [:client-funds :in-transit :client-payable])

(defn resolve-accounts
  "Map each role in `settlement-roles` to an account id, or refuse naming the
  gap.

  Matched on **code and currency**: an account holds exactly one currency
  (invariant I6), and a batch settles in one, so a `1300-IN-TRANSIT` in SGD
  cannot carry a USD batch's in-transit leg. Refusing here with the codes named
  is the difference between an operator opening two accounts and an operator
  reading a stack trace."
  [accounts currency]
  (let [by-code (into {} (map (juxt :code identity))
                      (filter #(= currency (:currency %)) accounts))
        resolved (into {} (keep (fn [role]
                                  (when-let [a (by-code (get posting/account-roles role))]
                                    [role (:id a)])))
                       settlement-roles)]
    (when-not (= (count resolved) (count settlement-roles))
      (err/fail! :unprocessable
                 "This organisation has no settlement accounts for this currency"
                 {:currency currency
                  :missing (mapv (fn [role] {:role (name role)
                                             :code (get posting/account-roles role)})
                                 (remove resolved settlement-roles))}))
    resolved))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn create-batch!
  "Construct a batch from named instructions, on the caller's transaction.

  Returns `{:batch … :instructions […]}`. The order of operations is the
  interesting part and is stated once, here:

  1. **Lock the instructions** (`order by id`). Every eligibility answer below
     is read from rows this transaction holds, so an `amend` or a `cancel`
     committing concurrently either lost the race or is refused by it —
     validate-then-write is a race unless the validated rows are locked
     (standing lesson **L-8**).
  2. **Ask the pure rules**, which refuse *every* ineligible instruction at once
     with a named reason each, rather than the first one found.
  3. **Write** the batch, its memberships, then the audit event — all on `tx`.

  The membership insert is where the no-double-settlement guard bites: an
  instruction already in a pending, settled or timed-out membership is refused
  by `settlement_item_live_key`, a schema constraint rather than a check in this
  function, so it binds a fix-up script too (AC-7)."
  [tx {:keys [batch-id organisation-id scheme currency value-date
              instruction-ids actor correlation-id]}]
  (batch/assert-scheme! scheme)
  (batch/assert-non-empty! instruction-ids)
  (let [instructions (settlement/lock-instructions! tx organisation-id instruction-ids)
        candidate    {:id              batch-id
                      :organisation-id organisation-id
                      :scheme          scheme
                      :currency        currency
                      :value-date      value-date
                      :created-by      (:id actor)}
        _            (batch/assert-eligible! candidate instructions)
        stored       (settlement/insert-batch! tx candidate)]
    (settlement/add-items! tx batch-id (mapv :id instructions))
    ;; Same transaction as the rows above (C-05, PR-075, invariant I9).
    (audit-store/record! tx {:organisation-id organisation-id
                             :actor-id        (:id actor)
                             :action          "settlement-batch.created"
                             :subject-type    "settlement-batch"
                             :subject-id      batch-id
                             :before          nil
                             :after           (audit/settlement-batch-subject stored)
                             :correlation-id  correlation-id})
    {:batch stored :instructions instructions}))

;; ---------------------------------------------------------------------------
;; Submission — the release
;; ---------------------------------------------------------------------------

(defn submit-batch!
  "Submit a batch to its simulated scheme, on the caller's transaction.

  Everything in AC-3 happens here and happens together: every member
  transitions `approved → released`, each release posts its value movement, the
  batch moves `open → submitted`, and one audit event is written per instruction
  plus one for the batch. One transaction, so a failure anywhere — including the
  deferred zero-sum trigger firing at the caller's commit — leaves the batch
  open, the instructions approved and the journal untouched.

  `entry-ids` are supplied by the caller, one per member, because the domain
  layer generates no identifiers and a posting a test cannot predict is a
  posting a test cannot assert on. `occurred-at` likewise.

  The lock order is the one this module documents: batch, then instructions
  (`order by id`), then accounts (inside `post-entry!`). Taking the batch first
  is what stops two operators submitting the same batch concurrently — the
  second waits, re-reads `submitted`, and is refused by the lifecycle rather
  than by luck."
  [tx {:keys [organisation-id batch-id actor correlation-id entry-ids occurred-at]}]
  (let [batch-row (settlement/lock-batch! tx organisation-id batch-id)]
    (when-not (= "open" (:status batch-row))
      (err/conflict! (str "Cannot submit a settlement batch that is " (:status batch-row))
                     {:batch-status (:status batch-row)
                      :attempted    "submit"
                      :submittable-in ["open"]}))
    (let [items        (settlement/items-for tx batch-id)
          ids          (mapv :instruction-id items)
          instructions (settlement/lock-instructions! tx organisation-id ids)
          ;; Re-checked under the lock, not merely at construction. An
          ;; instruction amended or cancelled between constructing the batch and
          ;; submitting it is no longer approved, and releasing it would release
          ;; a payment nobody currently agrees to.
          _            (batch/assert-eligible! batch-row instructions)
          accounts     (resolve-accounts (ledger/list-accounts tx organisation-id)
                                         (:currency batch-row))
          _            (when-not (= (count entry-ids) (count instructions))
                         (err/invalid!
                          (str "Submitting this batch posts " (count instructions)
                               " release entries and needs that many ids")
                          {:required (count instructions) :supplied (count entry-ids)}))
          released     (mapv (fn [instruction entry-id]
                               (let [moved (payments/transition! tx organisation-id
                                                                 (:id instruction) :release)]
                                 ;; ADR-0018: a release posts. The value leaves
                                 ;; the pooled client-funds asset and sits in
                                 ;; settlement-in-transit until finality.
                                 (ledger/post-entry!
                                  tx (first (posting/release-entries
                                             instruction {:accounts    accounts
                                                          :entry-ids   [entry-id]
                                                          :occurred-at occurred-at})))
                                 moved))
                             instructions
                             entry-ids)
          submitted    (settlement/set-batch-status! tx organisation-id batch-id "submitted")]

      ;; The batch's own transition, and then one event per payment that moved.
      ;; Two terms rather than one: a batch being submitted and a payment being
      ;; released are different facts about different subjects, and counting
      ;; `settlement-batch.submitted` to learn how many payments left would be
      ;; the mislabelling F-005 found (L-7).
      (audit-store/record! tx {:organisation-id organisation-id
                               :actor-id        (:id actor)
                               :action          "settlement-batch.submitted"
                               :subject-type    "settlement-batch"
                               :subject-id      batch-id
                               :before          (audit/settlement-batch-subject batch-row)
                               :after           (audit/settlement-batch-subject submitted)
                               :correlation-id  correlation-id})
      (doseq [{:keys [before after]} released]
        (audit-store/record! tx {:organisation-id organisation-id
                                 :actor-id        (:id actor)
                                 :action          "payment.released"
                                 :subject-type    "payment-instruction"
                                 :subject-id      (:id after)
                                 :before          (audit/instruction-subject before)
                                 :after           (audit/instruction-subject after)
                                 :correlation-id  correlation-id}))

      ;; The scheme's acknowledgement, recorded like any other response so that
      ;; a resubmission is visible as a duplicate rather than as a new event.
      ;; Deterministic reference (see `clofin.settlement.scheme`), so it is.
      (settlement/record-response!
       tx {:id             (random-uuid)
           :batch-id       batch-id
           :instruction-id nil
           :kind           "ack"
           :reference      (scheme/submit-reference (scheme/simulated (:scheme batch-row))
                                                    batch-row)})
      {:batch        submitted
       :instructions (mapv :after released)})))

;; ---------------------------------------------------------------------------
;; Outcomes
;; ---------------------------------------------------------------------------

(def response-outcome
  "The item outcome each response kind resolves to. `ack` resolves nothing."
  {"settled"  "settled"
   "returned" "returned"})

(defn- finality-entry
  "The entry an outcome posts, or nil for an outcome that posts nothing."
  [outcome instruction opts]
  (case outcome
    "settled"  (posting/settlement-entry instruction opts)
    "returned" (posting/return-entry instruction opts)
    nil))

(defn- audit-action
  [outcome]
  (case outcome
    "settled"  "payment.settled"
    "returned" "payment.returned"
    nil))

(defn- lifecycle-event
  [outcome]
  (case outcome
    "settled"  :settle
    "returned" :return
    nil))

(defn- complete-batch!
  "Recompute the batch's derived status and, when this transaction is the one
  that made it complete, say so once.

  `was-complete?` is passed in from *before* the write, which is what makes the
  event obey L-7: `settlement-batch.completed` marks the transition into a
  complete batch, so a late resolution that changes an already-complete batch's
  status updates the status and emits nothing named `completed`."
  [tx {:keys [organisation-id batch-id batch-row was-complete? actor correlation-id]}]
  (let [items   (settlement/items-for tx batch-id)
        status  (batch/derive-status {:submitted? true :items items})
        updated (settlement/set-batch-status! tx organisation-id batch-id status)]
    (when (and (not was-complete?) (batch/complete? items))
      (audit-store/record! tx {:organisation-id organisation-id
                               :actor-id        (:id actor)
                               :action          "settlement-batch.completed"
                               :subject-type    "settlement-batch"
                               :subject-id      batch-id
                               :before          (audit/settlement-batch-subject batch-row)
                               :after           (audit/settlement-batch-subject updated)
                               :correlation-id  correlation-id}))
    updated))

(defn record-scheme-response!
  "Record what the simulated scheme said, and act on it exactly once.

  Returns `{:batch … :outcome … :replayed? bool}`.

  **The duplicate path is the point of this function.** A scheme that answers
  twice, late, or out of order is the normal case in the world this simulates,
  so the first thing that happens is the verbatim insert: if the replay key
  refuses it, this delivery is one CloFin has already seen, and the correct
  behaviour is to do *no work at all* — no second posting, no second audit
  event, no second transition — and report the same answer as the first time
  (AC-5). The original row stays; the duplicate is discarded rather than stored,
  because the evidence needed is that a duplicate arrived, and the first row
  plus this refusal is that evidence.

  Kinds:

  - `ack` — the batch was acknowledged. Records the row and moves nothing.
  - `settled` / `returned` — resolves one item, transitions its instruction,
    posts finality, and emits one audit event named after the transition.
  - `timeout-resolution` — the late answer for an item the sweep already gave
    up on. Resolves it to the outcome the request names, exactly once."
  [tx {:keys [organisation-id batch-id instruction-id kind reference reason outcome
              actor correlation-id entry-id occurred-at]}]
  (let [batch-row     (settlement/lock-batch! tx organisation-id batch-id)
        items-before  (settlement/items-for tx batch-id)
        was-complete? (batch/complete? items-before)
        stored        (settlement/record-response!
                       tx {:id             (random-uuid)
                           :batch-id       batch-id
                           :instruction-id instruction-id
                           :kind           kind
                           :reference      reference})]
    (if (nil? stored)
      ;; Already seen. No work, and the answer the first delivery produced.
      {:batch     batch-row
       :replayed? true
       :original  (settlement/find-response tx {:batch-id batch-id
                                                :instruction-id instruction-id
                                                :kind kind :reference reference})}

      (if (= "ack" kind)
        {:batch batch-row :replayed? false :outcome nil}

        (let [resolved-outcome (if (= "timeout-resolution" kind)
                                 (or outcome
                                     (err/invalid!
                                      "A timeout resolution must name the outcome it resolves to"
                                      {:known ["settled" "returned"]}))
                                 (response-outcome kind))
              _ (when-not instruction-id
                  (err/invalid! (str "A '" kind "' scheme response must name the instruction it is about")
                                {:kind kind}))
              ;; Lock order step 2. The instruction is read under the same
              ;; transaction that will transition it.
              instruction (first (settlement/lock-instructions! tx organisation-id
                                                                [instruction-id]))
              item (if (= "timeout-resolution" kind)
                     (settlement/resolve-timed-out-item! tx batch-id instruction-id
                                                         resolved-outcome reason)
                     (settlement/resolve-item! tx batch-id instruction-id
                                               resolved-outcome reason))]
          (when-not item
            ;; Out of order rather than duplicate: this response is new — its
            ;; replay key was free — but the item it is about is not in a state
            ;; this kind can resolve. The verbatim row stays, because the fact
            ;; that the scheme said this is worth keeping whether or not CloFin
            ;; could act on it.
            (err/conflict!
             (if (= "timeout-resolution" kind)
               "This settlement item is not timed out, so there is nothing for a timeout resolution to resolve"
               "This settlement item already has an outcome; a late answer for a timed-out item must arrive as a timeout-resolution")
             {:batch-id       (str batch-id)
              :instruction-id (str instruction-id)
              :kind           kind}))

          (let [moved (payments/transition! tx organisation-id instruction-id
                                            (lifecycle-event resolved-outcome))
                entry (finality-entry resolved-outcome instruction
                                      {:accounts    (resolve-accounts
                                                     (ledger/list-accounts tx organisation-id)
                                                     (:currency batch-row))
                                       :entry-id    entry-id
                                       :occurred-at occurred-at})]
            (ledger/post-entry! tx entry)
            (audit-store/record! tx {:organisation-id organisation-id
                                     :actor-id        (:id actor)
                                     :action          (audit-action resolved-outcome)
                                     :subject-type    "payment-instruction"
                                     :subject-id      instruction-id
                                     :before          (audit/instruction-subject (:before moved))
                                     :after           (audit/instruction-subject (:after moved))
                                     :correlation-id  correlation-id})
            {:batch     (complete-batch! tx {:organisation-id organisation-id
                                             :batch-id        batch-id
                                             :batch-row       batch-row
                                             :was-complete?   was-complete?
                                             :actor           actor
                                             :correlation-id  correlation-id})
             :item      item
             :outcome   resolved-outcome
             :replayed? false}))))))

;; ---------------------------------------------------------------------------
;; Timeouts
;; ---------------------------------------------------------------------------

(defn sweep-timeouts!
  "Stop waiting for a batch's unanswered items, on the caller's transaction.

  Returns `{:batch … :timed-out [instruction-id …]}`.

  **Timing out is not failing, and this function is where that distinction is
  kept.** Every item it marks is one whose true outcome CloFin does not know;
  the instruction therefore stays `released` — no lifecycle event is driven —
  and the item stays un-re-batchable, because `settlement_item_live_key` counts
  `timed-out` as live. The temptation is to drive `:fail` here and be done;
  doing so would say the payment did not happen, which is a claim nobody can
  support, and would free the instruction to be batched again. That is the
  single failure mode this module exists to prevent.

  The event is `settlement-batch.timeout-swept` and is about the *batch*. There
  is deliberately no per-instruction audit event: no instruction changed state.
  Emitting `payment.failed` here would be F-005's mistake with a new name."
  [tx {:keys [organisation-id batch-id actor correlation-id horizon-seconds]}]
  (let [batch-row     (settlement/lock-batch! tx organisation-id batch-id)
        items-before  (settlement/items-for tx batch-id)
        was-complete? (batch/complete? items-before)]
    (when-not (contains? #{"submitted" "settled" "partially-settled" "failed"} (:status batch-row))
      (err/conflict! (str "Cannot sweep a settlement batch that is " (:status batch-row)
                          "; only a submitted batch has anything to wait for")
                     {:batch-status (:status batch-row) :attempted "timeout-sweep"}))
    (let [horizon (or horizon-seconds default-timeout-seconds)
          swept   (settlement/sweep-timeouts! tx batch-id horizon)
          ;; The derived status is recomputed *before* the sweep event is
          ;; written, so that event carries a real before and after. An event
          ;; whose two digests are identical asserts a change that did not
          ;; happen — F-005's shape — and marking every remaining item resolved
          ;; always moves the batch out of `submitted`.
          updated (if (seq swept)
                    (complete-batch! tx {:organisation-id organisation-id
                                         :batch-id        batch-id
                                         :batch-row       batch-row
                                         :was-complete?   was-complete?
                                         :actor           actor
                                         :correlation-id  correlation-id})
                    batch-row)]
      ;; Nothing swept, nothing happened: a sweep an operator ran twice, or ran
      ;; before the horizon, is not a state change and must not leave an event
      ;; saying it was.
      (when (seq swept)
        (audit-store/record! tx {:organisation-id organisation-id
                                 :actor-id        (:id actor)
                                 :action          "settlement-batch.timeout-swept"
                                 :subject-type    "settlement-batch"
                                 :subject-id      batch-id
                                 :before          (audit/settlement-batch-subject batch-row)
                                 :after           (audit/settlement-batch-subject updated)
                                 :correlation-id  correlation-id}))
      {:batch           updated
       :horizon-seconds horizon
       :timed-out       swept})))
