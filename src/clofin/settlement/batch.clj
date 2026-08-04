(ns clofin.settlement.batch
  "Settlement batching rules: who may be batched together, and what a batch's
  status is once its members have answers.

  Two rules live here and nowhere else.

  **Eligibility.** A batch member is an `approved` instruction in the batch's
  organisation whose currency and value date match the batch's. Anything else is
  refused *by name* — a caller told only \"ineligible\" cannot tell a
  wrong-status instruction from a wrong-currency one, and those need different
  corrections.

  **Status derivation.** A batch's status is derived from its items' outcomes,
  never set independently — the same doctrine that makes a balance an
  aggregation over journal lines rather than a column (ADR-0008). A stored
  status that could be written directly is a status that can disagree with the
  items it claims to summarise, and the disagreement is invisible until someone
  reconciles them by hand.

  Pure: no database, no clock, no identifier generation. Every function takes
  values the caller read inside its own transaction and returns values; the
  locking those reads require is `clofin.settlement.repository`'s problem, and
  the transaction is the caller's.

  Note what is *not* here: the scheme. An instruction carries no scheme
  attribute, so which network settles it is a routing decision the operator
  makes when constructing the batch, not a property of the payment that could
  be grouped on. See objection O-2 in `docs/audits/004-REQ-settlement-simulation.md`."
  (:require [clofin.error :as err]))

;; ---------------------------------------------------------------------------
;; Schemes
;; ---------------------------------------------------------------------------

(def schemes
  "Every settlement scheme CloFin recognises. **All simulated.**

  Identical to the `settlement_scheme_known` check constraint in migration
  `0009`, and `clofin.settlement.batch-test` asserts the two agree — the same
  discipline `clofin.authz.model/roles` keeps with `role_known`.

  The `SIM-` prefix is not decoration. CloFin connects to no scheme, and a
  batch recorded against a name an auditor could mistake for a real network
  would be a synthetic record that reads as a real one. The constraint makes
  that unrepresentable rather than discouraged."
  (into (sorted-set) ["SIM-RTGS" "SIM-ACH"]))

(defn assert-scheme!
  "Return `scheme`, or throw naming what is permitted."
  [scheme]
  (when-not (contains? schemes scheme)
    (err/invalid! (str "Unknown settlement scheme: " scheme)
                  {:scheme (str scheme)
                   :known (vec schemes)
                   :note "CloFin settles against simulated schemes only"}))
  scheme)

;; ---------------------------------------------------------------------------
;; Eligibility
;; ---------------------------------------------------------------------------

(def eligible-status
  "The only status from which an instruction may be batched.

  `approved` and nothing else. A `draft` or `pending-approval` instruction has
  not been agreed by anyone; a `released` one is already in a batch; a terminal
  one is finished. Held as a value rather than written into a condition so that
  the answer to \"what may be settled?\" has one place."
  :approved)

(def refusal-reasons
  "Why an instruction may not join a batch, and what a caller is told.

  Held as data beside the checks so a reason added without an explanation fails
  `clofin.settlement.batch-test` rather than reaching a caller as a bare
  keyword. Each names the correction available, because a refusal an operator
  cannot act on becomes a request to disable the check."
  {:not-approved
   "Only an approved payment instruction may be settled"
   :wrong-organisation
   "This payment instruction belongs to a different organisation"
   :currency-mismatch
   "This payment instruction's currency differs from the batch's"
   :value-date-mismatch
   "This payment instruction's value date differs from the batch's"})

(defn refusal
  "The reason `instruction` may not join `batch`, or nil when it may.

  One reason, not a list, and the order is deliberate: organisation before
  status before currency before value date. A caller acting on another tenant's
  instruction is told that first, because every later answer would confirm
  something about a record they may not see."
  [{:keys [organisation-id currency value-date]} instruction]
  (cond
    (not= organisation-id (:organisation-id instruction)) :wrong-organisation
    (not= eligible-status (:status instruction))          :not-approved
    (not= currency (:currency (:amount instruction)))     :currency-mismatch
    (not= value-date (:value-date instruction))           :value-date-mismatch
    :else nil))

(defn eligible?
  "True when `instruction` may join `batch`."
  [batch instruction]
  (nil? (refusal batch instruction)))

(defn assert-eligible!
  "Throw a `:unprocessable` naming every instruction that may not join `batch`,
  and why.

  All of them, not the first: an operator batching forty payments needs the
  whole list to fix in one pass, and returning one at a time turns a single
  correction into forty round trips. `:unprocessable` rather than
  `:validation` — the request was understood, and the reason is a fact about
  stored state rather than about the request's shape (ADR-0012)."
  [batch instructions]
  (let [refused (into []
                      (keep (fn [i]
                              (when-let [reason (refusal batch i)]
                                {:instruction-id (str (:id i))
                                 :reason         (name reason)
                                 :detail         (refusal-reasons reason)})))
                      instructions)]
    (when (seq refused)
      (err/fail! :unprocessable
                 "Some payment instructions cannot be settled in this batch"
                 {:refused refused
                  :batch {:scheme     (:scheme batch)
                          :currency   (:currency batch)
                          :value-date (str (:value-date batch))}}))
    instructions))

(defn assert-non-empty!
  "A batch with no members settles nothing and derives to `failed` the moment it
  is submitted, which is a confusing way to say a caller sent an empty list."
  [instructions]
  (when (empty? instructions)
    (err/invalid! "A settlement batch must contain at least one payment instruction"
                  {:count 0}))
  instructions)

;; ---------------------------------------------------------------------------
;; Grouping
;; ---------------------------------------------------------------------------

(defn batch-key
  "The `(scheme, currency, value-date)` a batch is defined by.

  One batch, one key — the whole of AC-2. Exposed as a function rather than
  inlined so that the key and the uniqueness rule are the same expression
  wherever either is used."
  [batch]
  [(:scheme batch) (:currency batch) (:value-date batch)])

(defn group-by-key
  "Group eligible instructions into `{[currency value-date] [instruction …]}`.

  The scheme is absent from the key on purpose: an instruction has no scheme
  attribute to group on (see the namespace docstring). This is the function an
  operator's tooling would use to see which batches *could* be built from a set
  of approved payments; the scheme is then their choice per group."
  [instructions]
  (reduce (fn [acc i]
            (update acc [(:currency (:amount i)) (:value-date i)] (fnil conj []) i))
          {}
          instructions))

;; ---------------------------------------------------------------------------
;; Status derivation
;; ---------------------------------------------------------------------------

(def statuses
  "Every status a batch may hold. Identical to `settlement_batch_status_known`
  in migration `0009`; a test asserts the two agree."
  (into (sorted-set) ["open" "submitted" "settled" "partially-settled" "failed"]))

(def item-outcomes
  "Every outcome an item may carry, or nil while pending. Identical to
  `settlement_outcome_known` in migration `0009`.

  There is deliberately no `failed`. A scheme failure that returns the money
  **is** a return, and an outcome nobody knows is `timed-out` — which is not a
  failure but an absence of information, and the difference is the whole point
  of this module (see `timed-out` below and objection O-1 in 004-REQ)."
  (into (sorted-set) ["settled" "returned" "timed-out"]))

(defn resolved?
  "True when an item has an outcome — including `timed-out`.

  `timed-out` is resolved in the sense that matters for deriving a batch status:
  CloFin has stopped waiting for it. It is emphatically *not* resolved in the
  sense that matters for the instruction, whose true fate is still unknown and
  which therefore stays un-re-batchable until a late response says otherwise."
  [item]
  (some? (:outcome item)))

(defn derive-status
  "The status a batch holds, given its items. The single statement of the rule.

      open       — not yet submitted
      submitted  — submitted, and at least one item is still unresolved
      settled    — every item resolved, and every one of them settled
      failed     — every item resolved, and none of them settled
      partially-settled — every item resolved, and some but not all settled

  `submitted?` is passed rather than read from the batch's own status column,
  because this function is what *decides* that column and reading it back would
  make the derivation self-referential.

  A batch with no items derives to `failed` once submitted: nothing settled, so
  `settled` would be a lie and `partially-settled` is not true either. The
  construction rules refuse an empty batch, so this is a statement about a shape
  that should not arise rather than a path a caller can reach."
  [{:keys [submitted? items]}]
  (cond
    (not submitted?)                "open"
    (not (every? resolved? items))  "submitted"
    (every? #(= "settled" (:outcome %)) items) "settled"
    (some   #(= "settled" (:outcome %)) items) "partially-settled"
    :else                           "failed"))

(defn complete?
  "True when every item has an outcome, so the batch has reached a terminal
  derived status.

  This is the predicate that gates the `settlement-batch.completed` audit event.
  Standing lesson **L-7**: an action named after a transition is emitted only
  where that transition commits, so a response that resolves one item of ten
  completes nothing and must emit nothing named `completed`."
  [items]
  (and (seq items) (every? resolved? items)))
