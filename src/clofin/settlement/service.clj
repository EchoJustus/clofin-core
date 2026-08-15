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

  **That precondition is now checked rather than documented.** Every function
  here opens with `clofin.audit.repository/assert-unit-of-work!`, before its
  first write, and a pool or an autocommit connection is refused there. Audit
  finding **F-011** (standing lesson **L-13**) showed why the parameter name and
  the purity test were not enough: they make misuse visible in review, and a
  REPL task, a script or a new adapter can still call the function exactly as
  Clojure permits and commit an aggregate write whose audit event then fails —
  producing the unaudited state change C-05 calls unrepresentable.

  ## Errors, and the one that is a value

  `record-scheme-response!` returns a refusal rather than throwing one. Every
  arrival commits its receipt, and the caller renders the `409` afterwards
  (audit finding **F-008**, standing lesson **L-11**). See that function.

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
  | A late answer moves an **already-complete** batch's derived status | `settlement-batch.status-restated` | the batch |

  A scheme response *recorded* is not a payment *settled*: a duplicate delivery
  records nothing new and emits nothing, and an `ack` moves no payment at all.
  A batch with unresolved items is not *completed* however many responses have
  arrived — `settlement-batch.completed` is gated on the batch not having been
  complete before this transaction and being complete after it, which is the
  only reading of \"the transition commits here\" that survives a late response
  changing a status that was already terminal.

  That late change is the fifth row, and until this increment it was the one
  state change in CloFin with no event naming its subject — C-05's single
  disclosed exception, found by the `ref-1` release audit as **A-004**. It is
  now `settlement-batch.status-restated`, emitted only where the derived status
  actually moves; see `batch-status-action` for why it is a second term rather
  than a second `completed`.

  [C-05]: docs/COMPLIANCE.md"
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.error :as err]
            [clofin.ledger.repository :as ledger]
            [clofin.payments.posting :as posting]
            [clofin.payments.repository :as payments]
            [clofin.settlement.batch :as batch]
            [clofin.settlement.repository :as settlement]
            [clofin.settlement.response :as response]
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
  instruction that has **ever** been in a settlement membership is refused by
  `settlement_item_instruction_key`, a schema constraint rather than a check in
  this function, so it binds a fix-up script too (AC-7). Migration `0010`
  tightened that index to cover `returned` as well, on the F-007 ruling that a
  returned payment is terminal and a retry is a new instruction — so the
  eligibility rules below and the index now agree instead of contradicting each
  other."
  [tx {:keys [batch-id organisation-id scheme currency value-date
              instruction-ids actor correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
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
  (audit-store/assert-unit-of-work! tx)
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
      ;;
      ;; It carries the same receipt fields an injected response does — the
      ;; disposition it self-evidently has, and a digest over its own semantic
      ;; content — because a receipt written by one path and not the other is a
      ;; table whose columns mean "except for acks" (F-008, F-009).
      (let [ack {:batch-id       batch-id
                 :instruction-id nil
                 :kind           "ack"
                 :reference      (scheme/submit-reference
                                  (scheme/simulated (:scheme batch-row)) batch-row)}]
        (settlement/record-response!
         tx (assoc ack :id             (random-uuid)
                       :disposition    "acknowledged"
                       :request-digest (response/digest ack))))
      {:batch        submitted
       :instructions (mapv :after released)})))

;; ---------------------------------------------------------------------------
;; Outcomes
;; ---------------------------------------------------------------------------

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

(defn- batch-status-action
  "Which batch-subject event this recomputation deserves, or nil for none.

  Two different things can happen to a derived status and they are two different
  terms — standing lesson **L-7**, and the reason this is a function rather than
  a `when` inside the writer:

  | Before | After | Term |
  |---|---|---|
  | not complete | complete | `settlement-batch.completed` — the transition *into* a complete batch |
  | complete | complete, different status | `settlement-batch.status-restated` — a late answer corrected the outcome |
  | complete | complete, same status | none |
  | not complete | not complete | none |

  The last two rows are the point. A response that resolves one item of ten
  completes nothing; a late `timeout-resolution` that leaves
  `partially-settled` where it was changes no batch-level fact. An event in
  either case would assert a transition that did not occur, with before and
  after digests that are identical — which is precisely the shape audit finding
  **F-005** found.

  The third row is the one that did not exist until now, and its absence was
  C-05's single disclosed exception (release-audit finding **A-004**): the
  payment's own `settled`/`returned` event was written, the stored batch status
  was updated, and **no event named the batch**. `status-restated` is that
  event. It is decided from the two statuses rather than from the response
  `kind`, because the fact the term is named after is the status moving — a
  future path that moves it the same way would be the same fact."
  [{:keys [was-complete? complete? before after]}]
  (cond
    (and (not was-complete?) complete?)              "settlement-batch.completed"
    (and was-complete? (not= before after))          "settlement-batch.status-restated"
    :else                                            nil))

(defn- complete-batch!
  "Recompute the batch's derived status and, when this transaction moved a
  batch-level fact, say so once.

  `was-complete?` is passed in from *before* the write, which is what makes both
  terms obey L-7: `settlement-batch.completed` marks the transition into a
  complete batch, and `settlement-batch.status-restated` marks a later
  correction of an outcome that had already been reached. Which of the two — or
  neither — is `batch-status-action`'s decision, taken from the statuses
  themselves."
  [tx {:keys [organisation-id batch-id batch-row was-complete? actor correlation-id]}]
  (let [items   (settlement/items-for tx batch-id)
        status  (batch/derive-status {:submitted? true :items items})
        updated (settlement/set-batch-status! tx organisation-id batch-id status)
        action  (batch-status-action {:was-complete? was-complete?
                                      :complete?     (batch/complete? items)
                                      :before        (:status batch-row)
                                      :after         (:status updated)})]
    (when action
      (audit-store/record! tx {:organisation-id organisation-id
                               :actor-id        (:id actor)
                               :action          action
                               :subject-type    "settlement-batch"
                               :subject-id      batch-id
                               :before          (audit/settlement-batch-subject batch-row)
                               :after           (audit/settlement-batch-subject updated)
                               :correlation-id  correlation-id}))
    updated))

(defn- replay
  "The answer a stored receipt gives when its exact message arrives again.

  Reproduced from the row, never re-derived. That is the whole of F-008's
  second half and of F-009's: a receipt whose disposition was `refused` answers
  `409` again however the world has moved on since — it is *not* re-evaluated
  against state that arrived in the meantime — and a receipt whose disposition
  was `applied` answers with the outcome it recorded, rather than with the
  `nil` the audit found in its place."
  [batch-row receipt]
  {:batch              batch-row
   :replayed?          true
   :receipt            receipt
   :disposition        (:disposition receipt)
   :disposition-reason (:disposition-reason receipt)
   :detail             (when (response/refused? (:disposition receipt))
                         (response/refusal-detail (:disposition-reason receipt)))
   :outcome            (:outcome receipt)})

(defn- refusal-code
  "Why this response could not be acted upon, as a stable code.

  Read off the item as it stood before the attempt, which is the fact the
  refusal is about: no membership at all, an item that has already answered,
  or — for a late answer — an item nobody had given up on."
  [kind item]
  (cond
    (nil? item)                   "item-not-in-batch"
    (= "timeout-resolution" kind) "item-not-timed-out"
    :else                         "item-already-resolved"))

(defn record-scheme-response!
  "Record what the simulated scheme said, act on it at most once, and keep the
  receipt whatever happened.

  Returns
  `{:batch … :replayed? bool :disposition … :disposition-reason … :outcome … :receipt …}`.

  **It does not throw for a processing conflict.** A refusal is a value, and the
  caller renders the `409` *after* committing — which is what makes the receipt
  survive it (audit finding **F-008**, standing lesson **L-11**). Before
  migration `0010` this function threw, the outer transaction rolled back, and
  the receipt went with the rejection: the first delivery was unprovable and the
  identical reference could perform work later against changed state.

  It still throws for a request that could not be *understood* —
  `clofin.settlement.response/assert-shape!` explains why those two cases are
  not the same and why only one of them earns a receipt.

  ## The order, and why it is this order

  1. **Lock the batch.** Lock order step 1, and what serialises two deliveries
     about one batch so the steps below cannot interleave.
  2. **Validate the shape**, before anything is written.
  3. **Digest the complete semantic request** — kind, reference, outcome and
     reason included (**F-009**, standing lesson **L-12**). The replay key names
     a delivery's identity; the digest says whether two deliveries under that
     identity are the same message.
  4. **Look for an existing receipt under this replay key**, before doing any
     work rather than after colliding with it.
     - Digest matches: the same message again. Reproduce the stored answer and
       do *no work at all* — no second posting, no second audit event, no second
       transition (AC-5).
     - Digest differs: two different messages claiming one identity. `409`, and
       **not** a replay — answering `replayed: true` there would tell a caller
       CloFin had already seen a request nobody had sent, which is exactly what
       F-009 found.
  5. **Otherwise attempt the work**, or discover that it cannot be done, and
  6. **commit the receipt carrying the disposition that describes either
     outcome.**

  Kinds:

  - `ack` — the batch was acknowledged. Receipt `acknowledged`; moves nothing.
  - `settled` / `returned` — resolves one item, transitions its instruction,
    posts finality, and emits one audit event named after the transition.
  - `timeout-resolution` — the late answer for an item the sweep already gave
    up on. Resolves it to the outcome the request names, exactly once."
  [tx {:keys [organisation-id batch-id instruction-id kind reference reason outcome
              actor correlation-id entry-id occurred-at]}]
  (audit-store/assert-unit-of-work! tx)
  (let [batch-row      (settlement/lock-batch! tx organisation-id batch-id)
        request        (response/assert-shape! {:batch-id       batch-id
                                                :instruction-id instruction-id
                                                :kind           kind
                                                :reference      reference
                                                :outcome        outcome
                                                :reason         reason})
        request-digest (response/digest request)
        existing       (settlement/find-response
                        tx (select-keys request [:batch-id :instruction-id :kind :reference]))]
    (cond
      (and existing (response/same-message? (:request-digest existing) request-digest))
      (replay batch-row existing)

      ;; A different message under a taken identity. No work, and no second row:
      ;; the first receipt already stands as the evidence of what arrived, and
      ;; the replay key exists precisely to stop a second one.
      ;;
      ;; The code and its prose both come from `response/refusal-reasons` rather
      ;; than being written here. They were written here until finding
      ;; **A-016**, which is how `replay-key-conflict` came to be a code the
      ;; service emitted and no vocabulary declared: a term defined at its only
      ;; call site is a term nothing can enumerate.
      existing
      {:batch              batch-row
       :replayed?          false
       :receipt            existing
       :disposition        "refused"
       :disposition-reason "replay-key-conflict"
       :outcome            nil
       :detail             (response/refusal-detail "replay-key-conflict")}

      :else
      (let [items-before  (settlement/items-for tx batch-id)
            was-complete? (batch/complete? items-before)
            resolved      (:outcome request)
            reason        (:reason request)
            receipt!      (fn [disposition disposition-reason]
                            (or
                             (settlement/record-response!
                              tx {:id                 (random-uuid)
                                  :batch-id           batch-id
                                  :instruction-id     instruction-id
                                  :kind               kind
                                  :reference          reference
                                  :disposition        disposition
                                  :disposition-reason disposition-reason
                                  :request-digest     request-digest
                                  ;; What the scheme CLAIMED, on the arrival
                                  ;; that acted on it. A refused arrival
                                  ;; resolved nothing, and recording an outcome
                                  ;; against it would put a claim CloFin
                                  ;; rejected in the column an investigation
                                  ;; reads as fact.
                                  :outcome            (when (= "applied" disposition) resolved)
                                  :reason             reason})
                             ;; Unreachable while the batch lock is held: this
                             ;; branch only runs when `find-response` found
                             ;; nothing *under that lock*, so nothing can have
                             ;; claimed the replay key since. If it ever does,
                             ;; the work above has happened and its receipt has
                             ;; not — which is the state F-008 exists to make
                             ;; impossible. Fail closed and take the whole
                             ;; transaction down rather than commit an effect
                             ;; with no evidence of what caused it.
                             (err/fail!
                              :conflict
                              (str "This scheme response was processed and its receipt could not "
                                   "be recorded; the work has been rolled back")
                              {:batch-id  (str batch-id)
                               :reference reference
                               :hint      (str "The batch lock makes this unreachable — reaching "
                                               "it means a caller wrote outside the documented "
                                               "lock order.")})))]
        (if (= "ack" kind)
          {:batch batch-row :replayed? false :outcome nil
           :disposition "acknowledged" :receipt (receipt! "acknowledged" nil)}

          (let [;; Lock order step 2. The instruction is read under the same
                ;; transaction that will transition it.
                instruction (first (settlement/lock-instructions! tx organisation-id
                                                                  [instruction-id]))
                item (if (= "timeout-resolution" kind)
                       (settlement/resolve-timed-out-item! tx batch-id instruction-id
                                                           resolved reason)
                       (settlement/resolve-item! tx batch-id instruction-id
                                                 resolved reason))]
            (if-not item
              ;; Out of order rather than duplicate: this message is new — its
              ;; replay key was free — and the item it is about is not in a
              ;; state this kind can resolve. The failed resolution wrote
              ;; nothing (`where outcome is null` matched no row), so the
              ;; receipt is the only thing this transaction commits — and it
              ;; commits, because what the scheme said is worth keeping whether
              ;; or not CloFin could act on it.
              (let [code (refusal-code kind
                                       (first (filter #(= instruction-id (:instruction-id %))
                                                      items-before)))]
                {:batch              batch-row
                 :replayed?          false
                 :receipt            (receipt! "refused" code)
                 :disposition        "refused"
                 :disposition-reason code
                 :outcome            nil
                 :detail             (response/refusal-detail code)})

              (let [moved (payments/transition! tx organisation-id instruction-id
                                                (lifecycle-event resolved))
                    entry (finality-entry resolved instruction
                                          {:accounts    (resolve-accounts
                                                         (ledger/list-accounts tx organisation-id)
                                                         (:currency batch-row))
                                           :entry-id    entry-id
                                           :occurred-at occurred-at})]
                (ledger/post-entry! tx entry)
                (audit-store/record! tx {:organisation-id organisation-id
                                         :actor-id        (:id actor)
                                         :action          (audit-action resolved)
                                         :subject-type    "payment-instruction"
                                         :subject-id      instruction-id
                                         :before          (audit/instruction-subject (:before moved))
                                         :after           (audit/instruction-subject (:after moved))
                                         :correlation-id  correlation-id})
                (let [receipt (receipt! "applied" nil)]
                  {:batch       (complete-batch! tx {:organisation-id organisation-id
                                                     :batch-id        batch-id
                                                     :batch-row       batch-row
                                                     :was-complete?   was-complete?
                                                     :actor           actor
                                                     :correlation-id  correlation-id})
                   :item        item
                   :outcome     resolved
                   :replayed?   false
                   :disposition "applied"
                   :receipt     receipt})))))))))

;; ---------------------------------------------------------------------------
;; Timeouts
;; ---------------------------------------------------------------------------

(defn sweep-timeouts!
  "Stop waiting for a batch's unanswered items, on the caller's transaction.

  Returns `{:batch … :timed-out [instruction-id …]}`.

  **Timing out is not failing, and this function is where that distinction is
  kept.** Every item it marks is one whose true outcome CloFin does not know;
  the instruction therefore stays `released` — no lifecycle event is driven —
  and the item stays un-re-batchable, because `settlement_item_instruction_key`
  admits no second membership at all. The temptation is to drive `:fail` here
  and be done;
  doing so would say the payment did not happen, which is a claim nobody can
  support, and would free the instruction to be batched again. That is the
  single failure mode this module exists to prevent.

  The event is `settlement-batch.timeout-swept` and is about the *batch*. There
  is deliberately no per-instruction audit event: no instruction changed state.
  Emitting `payment.failed` here would be F-005's mistake with a new name."
  [tx {:keys [organisation-id batch-id actor correlation-id horizon-seconds]}]
  (audit-store/assert-unit-of-work! tx)
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
