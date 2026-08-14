(ns clofin.authz.approval
  "The approval decision, as a pure function.

  `evaluate` decides whether one actor may record one approval on one
  instruction. It takes values and returns a value: no database, no clock, no
  HTTP. That is what makes segregation of duties a **domain rule** rather than
  a UI restriction (PR-071, C-01) — the refusal happens with no transport layer
  involved, and `clofin.authz.approval-test` proves it by calling this function
  directly across the whole actor × instruction matrix.

  If the only thing stopping a maker approving their own payment is a hidden
  button, the control does not exist.

  A second consequence of purity is worth naming because it is what an auditor
  actually asks for: the same inputs replay to the same decision. A past
  approval can be re-evaluated against the values that were in front of it, and
  the answer is reproducible without restoring a database.

  ## The result

      {:decision :permitted   …}
      {:decision :refused :reason :self-approval      …}   ; C-01
      {:decision :refused :reason :not-an-approver    …}   ; C-08
      {:decision :refused :reason :above-actor-limit  …}   ; C-02
      {:decision :refused :reason :already-approved   …}
      {:decision :refused :reason :no-threshold-configured …}

  Every refusal reason is a named keyword, so a caller can branch on it and a
  test can enumerate the set rather than sampling it. `refusal-reasons` below
  is that set, and `refusal-status` beside it is how each is reported — a reason
  with no mapping is a build failure, not a `500` in production.

  ## Two kinds of subject, one control

  `evaluate` decides about a **payment instruction** and, since TASK-008, about
  a **reconciliation adjustment**. Both are \"a thing with an amount and a
  creator that somebody other than its creator must agree to\", and both go
  through this function unchanged: there is one maker–checker control in CloFin
  and a second implementation of it would be a second thing to keep in step
  (standing lesson **L-6**). What the caller supplies as `:instruction` is
  whichever subject it is deciding about; what differs between them — how many
  approvals an adjustment needs below an organisation's lowest band — belongs to
  `clofin.recon.adjustment` and never to this function.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.authz.model :as model]
            [clofin.error :as err]
            [clofin.money :as money]
            [clojure.string :as str]))

(def decisions
  "Every decision an actor may record on a payment instruction.

  Identical to the `approval_decision_known` check constraint in migration
  `0005`, and compared with the **live catalogue** by
  `clofin.db.vocabulary-test`. Held as a value because it was inlined at the
  one place that checked it — `#{:approved :rejected}` inside `evaluate` — so
  the schema and the code stated the same vocabulary twice with nothing
  relating them (audit finding **A-014**).

  Not to be confused with `evaluate`'s own `:decision` key, which is
  `:permitted` or `:refused`: that answers *may this actor decide*, while these
  two are *what they decided*. Two questions, deliberately different words, and
  only these reach the `approval.decision` column."
  (into (sorted-set) [:approved :rejected]))

(def refusal-reasons
  "Every reason `evaluate` may refuse for.

  Enumerable on purpose: a caller mapping reasons to responses can be checked
  against this set, so a reason added later cannot reach a caller that has no
  answer for it."
  (into (sorted-set)
        [:self-approval
         :not-an-approver
         :above-actor-limit
         :already-approved
         :no-threshold-configured]))

(def refusal-status
  "How each refusal reason is reported, as a `clofin.error` category.

  Held **here**, beside the reasons themselves, rather than in one of the
  services that renders them. It lived in `clofin.payments.approval-service`
  while payments were the only thing approvals decided about; reconciliation
  adjustments now go through this same `evaluate`, and a second copy of this map
  in a second service is precisely the drift standing lesson **L-6** names — the
  copy the author was looking at stays right and the other goes stale.

  A reason added to `refusal-reasons` without an answer here fails
  `clofin.authz.approval-test` rather than degrading silently to a generic
  `403`, which is a refusal a caller cannot branch on.

  The split is ADR-0012's: `403` is \"you may not\", `409` is \"not from
  here\", `422` is \"understood, and the organisation is not set up for it\"."
  {:self-approval           :forbidden
   :not-an-approver         :forbidden
   :above-actor-limit       :forbidden
   :already-approved        :conflict
   :no-threshold-configured :unprocessable})

(def refusal-detail-templates
  "What a refused caller is told, with `%s` standing for the kind of thing being
  approved.

  Each names the control it comes from, because a refusal an operator cannot
  explain to their own auditor is a refusal that gets escalated into a request
  to disable it.

  Templated rather than fixed because there are now two kinds of subject — a
  payment instruction and a reconciliation adjustment — and one maker–checker
  control over both. Writing the prose twice would be two statements of C-01,
  and the second statement is the one that drifts."
  {:self-approval
   "The actor who created this %s may not approve it (segregation of duties)"
   :not-an-approver
   "This actor does not hold the permission required to decide on this %s"
   :above-actor-limit
   "This %s's amount is above this actor's approval limit"
   :already-approved
   "This actor has already recorded a decision on this %s"
   :no-threshold-configured
   "No approval threshold is configured for this organisation and currency, so no approval can be evaluated"})

(defn refusal-detail
  "The prose for a refusal, naming the kind of subject the decision was about."
  [reason subject]
  (when-let [template (refusal-detail-templates reason)]
    (if (str/includes? template "%s") (format template subject) template)))

;; ---------------------------------------------------------------------------
;; Thresholds
;; ---------------------------------------------------------------------------

(def wildcard-currency
  "The key an approver limit uses when it applies to every currency.

  `nil`, matching `approver_limit.currency` being nullable. Migration `0005`
  declared a primary key over that column, which PostgreSQL forces `NOT NULL`,
  so the row was uninsertable until migration `0006` replaced the key with
  `unique nulls not distinct (actor_id, currency)` — which also holds an actor
  to at most one wildcard row. Raised as objection O-1 and confirmed as a defect
  in the brief; see `docs/audits/003-REQ-authorisation-and-audit-trail.md`.

  The rule was implemented and tested here throughout, which is why the fix was
  a migration and no change to this namespace: the domain answer should not be
  shaped by a schema defect. `clofin.authz.repository-test` now asserts the same
  rule survives the round trip, because a function honouring a row nobody can
  store is a rule that does not exist."
  nil)

(defn band-for
  "The threshold band that applies to `minor-units`, or nil when none does.

  `thresholds` is a collection of `{:from-minor n :approvals-required k}` for
  **one currency** — the caller has already selected by currency, because
  thresholds are per currency and are never converted (ADR-0015).

  `from-minor` is **inclusive**: an amount exactly on a boundary falls into the
  higher band. Of the two possible readings that is the one that asks for more
  scrutiny rather than less, and a boundary rule that has to be guessed is a
  boundary rule that gets guessed differently by the next reader. Asserted at
  boundary − 1, boundary and boundary + 1 in the tests.

  Nil rather than a default when nothing matches. An organisation with no band
  covering an amount has not said how many approvals it wants, and inventing a
  number is how a control silently weakens — so the caller refuses instead."
  [thresholds minor-units]
  (->> thresholds
       (filter (fn [{:keys [from-minor]}] (<= from-minor minor-units)))
       (sort-by :from-minor)
       last))

(defn approvals-required
  "How many approvals `amount` needs under `thresholds`, or nil when no band
  covers it."
  [thresholds amount]
  (:approvals-required (band-for thresholds (:minor-units amount))))

;; ---------------------------------------------------------------------------
;; Limits
;; ---------------------------------------------------------------------------

(defn limit-for
  "The actor's ceiling for `currency`, in minor units, or nil when they have
  none.

  A currency-specific limit wins over the wildcard one; an actor with neither
  has **no** limit rather than an unlimited one. Absent means zero here, as it
  does everywhere else in this namespace — an approver who has not been given a
  limit in a currency cannot approve anything in it."
  [actor currency]
  (let [limits (:limits actor)]
    (if (contains? limits currency)
      (get limits currency)
      (get limits wildcard-currency))))

(defn within-limit?
  "True when `amount` does not exceed the actor's ceiling for its currency."
  [actor amount]
  (when-not (:currency amount)
    (err/invalid! "An approval decision needs an amount with a currency" {}))
  (let [ceiling (limit-for actor (:currency amount))]
    (and (some? ceiling) (<= (:minor-units amount) ceiling))))

;; ---------------------------------------------------------------------------
;; Existing approvals
;; ---------------------------------------------------------------------------

(defn live?
  "True when a decision still stands.

  A decision invalidated by an amendment (PR-014) or withdrawn by its actor
  stays in the table — decisions are never deleted — and stops standing from
  that moment. Covers rejections as well as approvals: an actor who has already
  said no has spoken, and the partial unique index in migration `0005` agrees."
  [approval]
  (nil? (:invalidated-at approval)))

(defn counts-toward-threshold?
  "True when a decision contributes to the required approval count.

  A live *approval* does; a live rejection does not, and neither does an
  invalidated approval. Separate from `live?` because the two questions have
  different answers for a rejection, and answering them with one predicate is
  how a rejection comes to count as an approval."
  [approval]
  (and (live? approval) (= :approved (:decision approval))))

(defn live-approvals
  "Only the decisions that count toward the threshold, in the order given."
  [existing-approvals]
  (filterv counts-toward-threshold? existing-approvals))

(defn decided-by?
  "True when `actor` already holds a live decision — of either kind — on this
  instruction."
  [existing-approvals actor]
  (boolean (some (fn [a] (and (live? a) (= (:actor-id a) (:id actor))))
                 existing-approvals)))

;; ---------------------------------------------------------------------------
;; The decision
;; ---------------------------------------------------------------------------

(defn- refused
  [reason detail]
  (assoc detail :decision :refused :reason reason))

(defn evaluate
  "May `actor` approve `instruction`? Returns a decision value.

      (evaluate {:instruction … :actor … :existing-approvals [] :thresholds …})

  `thresholds` is the band list for the instruction's currency; `actor` carries
  `:id`, `:status`, `:roles` and `:limits` as `clofin.authz.repository`
  assembles it; `existing-approvals` is every approval already recorded against
  the instruction, live or not.

  ## Order of the checks, and why it is this order

  The checks narrow, most fundamental first, so that a refused caller is told
  the reason that will still be true after everything else is fixed:

  1. `:self-approval` — the actor created this instruction, and therefore also
     submitted it: submission is restricted to the creator by
     `clofin.payments.state/creator-only-events` and
     `clofin.payments.repository/transition!`, which is what makes a comparison
     against `created-by` alone a complete maker–checker test. Checked
     **first** because it is the only refusal that can never be resolved: an
     actor may be granted a role or a larger limit, but the maker never becomes
     a valid checker for their own payment. An actor who is both the maker and
     an approver is told the reason that actually governs (C-01, PR-010).
  2. `:not-an-approver` — the actor has no approval authority at all (C-08).
  3. `:above-actor-limit` — they have authority, but not this much (C-02,
     PR-012).
  4. `:already-approved` — they have authority and enough of it, and have
     already used it here.
  5. `:no-threshold-configured` — the organisation has not said how many
     approvals this amount needs, so nobody can approve it. Default deny
     reaching the one input that is configuration rather than identity.

  A permitted decision carries the counting context the caller needs and must
  not recompute: how many approvals the amount requires, how many live ones
  already exist, and whether this one completes the requirement. The caller
  transitions the instruction to `approved` when `:completes?` is true — it
  does not decide for itself when enough is enough.

  ## Rejections

  `:decision` is optional and defaults to `:approved`. Passing `:rejected`
  evaluates the same maker–checker rule against the `:payment/reject`
  permission, and **skips the limit and the threshold**: an approver's ceiling
  is authority to *permit* a payment of a size, and saying no needs no such
  authority. Keeping it in one function rather than two is deliberate — C-01 is
  the rule that must not be stated twice, and a separate `evaluate-rejection`
  is exactly where the second statement would drift."
  [{:keys [instruction actor existing-approvals thresholds decision]
    :or {decision :approved}}]
  (when-not (:id actor)
    (err/invalid! "An approval decision needs an actor" {}))
  (when-not (:amount instruction)
    (err/invalid! "An approval decision needs an instruction with an amount" {}))
  (when-not (contains? decisions decision)
    (err/invalid! (str "Unknown approval decision: " decision)
                  {:decision (str decision) :known (mapv name decisions)}))
  (let [amount     (:amount instruction)
        currency   (:currency amount)
        rejecting? (= :rejected decision)
        live       (live-approvals existing-approvals)
        required   (approvals-required thresholds amount)
        ceiling    (limit-for actor currency)
        context    {:approvals-required required
                    :approvals-held     (count live)
                    :actor-limit-minor  ceiling
                    :currency           currency}]
    (cond
      ;; C-01. First, and never waivable.
      ;;
      ;; This compares the actor to the instruction's `created-by` and to
      ;; nothing else, which is sufficient **only because**
      ;; `clofin.payments.state/creator-only-events` puts `:submit` beyond any
      ;; other actor, enforced in `clofin.payments.repository/transition!`.
      ;; `DOMAIN_MODEL.md` §1 defines the maker as the actor who creates *and*
      ;; submits; that sentence is a description of what those two functions
      ;; make true, not an assumption this one may rest on.
      ;;
      ;; It rested on the assumption until audit finding **F-001**: submission
      ;; was gated by a permission and not by provenance, so an actor holding
      ;; `operator` and `approver` could submit someone else's draft — becoming
      ;; its maker in every sense that matters — and then approve it here,
      ;; because `created-by` still named the other person. The premise was
      ;; documented in this comment and enforced nowhere (standing lesson
      ;; **L-6**). Do not widen this comparison without first checking that
      ;; enforcement point still exists.
      (= (:id actor) (:created-by instruction))
      (refused :self-approval (assoc context :created-by (:created-by instruction)))

      ;; C-08. Covers a suspended actor too: `granted` is empty for one, so an
      ;; approver whose access was withdrawn this morning is refused here
      ;; rather than at some later layer that might not have been told.
      (not (model/permitted? actor (if rejecting? :payment/reject :payment/approve)))
      (refused :not-an-approver context)

      ;; C-02, the approver's own ceiling. Absent limit means no authority in
      ;; this currency, not unlimited authority. Not applied to a rejection:
      ;; refusing a payment is not an exercise of spending authority.
      (and (not rejecting?) (not (within-limit? actor amount)))
      (refused :above-actor-limit context)

      (decided-by? existing-approvals actor)
      (refused :already-approved context)

      ;; C-02, the organisation's band table. Last of the refusals because it
      ;; is the only one that is not about this actor: an operator reading it
      ;; should go and configure a threshold, not go and ask for a role. Not
      ;; applied to a rejection, which needs no count to be met.
      (and (not rejecting?) (nil? required))
      (refused :no-threshold-configured
               (assoc context :amount (money/->wire amount)))

      :else
      (assoc context
             :decision   :permitted
             ;; The caller writes the decision and then transitions if this is
             ;; true. Computed here so that "is one more enough?" is answered
             ;; by the same function that answered "how many are needed?". A
             ;; rejection always completes: one refusal ends the instruction.
             :completes? (or rejecting? (>= (inc (count live)) required))))))

(defn permitted?
  "True when `evaluate` permits the decision. For call sites that only branch."
  [request]
  (= :permitted (:decision (evaluate request))))

(defn assert-reason!
  "Throw `:field-validation` unless a rejection carries a reason (PR-013, AC-6).

  A domain rule, not a form validation: a rejection whose reason is retained is
  the difference between an audit trail that explains a refused payment and one
  that merely records that somebody refused it. The database enforces it again
  in `approval_rejection_needs_reason`, deliberately — this layer exists to
  produce the `422` naming the field, which a constraint violation cannot."
  [decision reason]
  (when (and (= :rejected decision)
             (or (nil? reason) (str/blank? reason)))
    (err/fail! :field-validation "Request failed validation"
               {"reason" "is required when rejecting an instruction"}))
  reason)
