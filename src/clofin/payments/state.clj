(ns clofin.payments.state
  "The payment instruction lifecycle, held as data.

  `transitions` is the lifecycle — not a description of it. Every rule about
  which event may follow which state is in that one map, and every function here
  reads it rather than restating it. A transition rule written as an `if` inside
  a handler is the failure this namespace exists to prevent: the table stops
  being the truth, `DOMAIN_MODEL.md` §3 starts describing a system that no
  longer exists, and nobody finds out until an audit.

  Because the lifecycle is a value, it is tested by enumeration rather than by
  sampling — `clofin.payments.state-test` walks every (state, event) pair, so a
  pair nobody thought to write a case for is still covered.

  Two rules about status are **not** transitions and are stated separately
  below, as named sets. See docs/ADR/0014-payment-lifecycle-as-data.md.

  Pure: no database, no clock, no identifier generation."
  ;; `clojure.core/reversible?` asks whether a collection can be walked
  ;; backwards. Here the word means what it means in payments.
  (:refer-clojure :exclude [reversible?])
  (:require [clofin.error :as err]))

;; ---------------------------------------------------------------------------
;; The lifecycle
;; ---------------------------------------------------------------------------

(def transitions
  "State to `{event → next state}`. `DOMAIN_MODEL.md` §3 draws this; the
  diagram is generated from here, never the other way around.

  Several events have no endpoint driving them yet, and that is deliberate:
  `approve`, `reject` and the `pending-approval` `amend` belong to TASK-003's
  approval workflow, and `settle`, `fail` and `return` to settlement in
  increment 5. A transition with no caller is still part of the model — an
  increment that adds the endpoint gets to drive the transition, not to decide
  where it leads.

  Note `amend` on `pending-approval` **and on `approved`**. It returns an
  instruction to `draft` and invalidates every approval given so far
  (`DOMAIN_MODEL.md` §3 rule 3, **PR-014**). It is *not* an in-place edit of a
  draft: that leaves the status where it was and is governed by
  `mutable-states` below.

  `PATCH /payment-instructions/{id}` now drives both, choosing between them by
  reading these two values rather than by testing a status. ADR-0014 originally
  refused to let `PATCH` drive `:amend` because the approval-invalidation
  PR-014 requires did not exist yet; TASK-003 built it, and ADR-0014's
  amendment 1 records the change. The arrow from `approved` is the one
  `DOMAIN_MODEL.md` §3 draws — an approved-but-unreleased payment whose amount
  is corrected must lose its approvals, which is the case PR-014 exists for."
  {:draft            {:submit :pending-approval, :cancel :cancelled}
   :pending-approval {:approve :approved, :reject :rejected, :amend :draft}
   :approved         {:release :released, :cancel :cancelled, :amend :draft}
   :released         {:settle :settled, :fail :failed, :return :returned}
   :settled          {}          ; terminal — reverse with a NEW instruction
   :rejected         {} :cancelled {} :failed {} :returned {}})

(def initial-state
  "Where an instruction begins. A caller cannot create one in any other state:
  an instruction that arrives already approved is an approval nobody gave."
  :draft)

(def states
  "Every status an instruction may hold. Sorted so that error detail and
  generated documentation are stable between runs."
  (into (sorted-set) (keys transitions)))

(def events
  "Every event the lifecycle recognises, across all states."
  (into (sorted-set) (mapcat keys) (vals transitions)))

(defn- outgoing
  "The events permitted from `state`, as `{event → next state}`.

  An unrecognised state is a defect rather than a caller error — the column
  carries a check constraint listing exactly these nine — so it is reported as
  such rather than being quietly treated as terminal. Treating an unknown
  status as terminal would refuse every operation on the row and look, from
  outside, exactly like a correctly settled payment."
  [state]
  (or (get transitions state)
      (err/invalid! (str "Unknown payment instruction status: " state)
                    {:status (str state) :known (mapv name states)})))

(defn known?
  "True when `state` is a status the lifecycle recognises."
  [state]
  (contains? transitions state))

(defn terminal?
  "True when no event leaves `state`.

  Derived from the table rather than declared, so that adding an arrow out of a
  state cannot leave a list somewhere still calling it terminal."
  [state]
  (empty? (outgoing state)))

(defn permitted-events
  "The events `state` allows, sorted."
  [state]
  (into (sorted-set) (keys (outgoing state))))

(defn permitted?
  "True when `event` may be applied to `state`."
  [state event]
  (contains? (outgoing state) event))

(defn transition
  "The state reached by applying `event` to `state`.

  Throws a `:conflict` naming the attempted transition and what would have been
  permitted instead. A caller that has been refused needs to know which of its
  assumptions was wrong — `409` alone leaves it to guess between \"already
  submitted\" and \"already cancelled\", which are very different situations for
  whoever is looking at the payment."
  [state event]
  (or (get (outgoing state) event)
      (err/conflict!
       (str "Cannot " (name event) " a payment instruction that is " (name state))
       {:instruction-status (name state)
        :attempted          (name event)
        :permitted          (mapv name (permitted-events state))})))

;; ---------------------------------------------------------------------------
;; Rules about status that are not transitions
;; ---------------------------------------------------------------------------
;;
;; Both of these govern an operation that leaves the status where it was, so
;; neither is an arrow on the diagram. They live here rather than in a handler
;; so that "what does status control?" has one answer and one file (ADR-0014).

(def mutable-states
  "States in which an instruction's substance may still be edited in place.

  `DOMAIN_MODEL.md` §1: a payment instruction is \"mutable while `draft`;
  immutable in substance thereafter\". Amending a draft leaves it in `draft`, so
  this is not a transition — but it is a rule about status, and a rule about
  status written into a handler is a rule that gets restated differently in the
  next handler."
  #{:draft})

(def reversible-states
  "States from which a reversal instruction may be raised against an
  instruction.

  `DOMAIN_MODEL.md` §3 rule 4: `settled` is terminal, and a settled payment is
  never mutated — it is followed by a *new* reversal instruction. The original
  is untouched, which is why this is not a transition either."
  #{:settled})

(def creator-only-events
  "Events only an instruction's **creator** may cause.

  A rule about *provenance* rather than about status, so it is a named set
  beside the table for the same reason `mutable-states` is: a provenance rule
  written into a handler is a provenance rule the next handler restates
  differently — or, as audit finding **F-001** showed, omits entirely.

  `:submit` is here because [C-01] rests on it. `clofin.authz.approval/evaluate`
  refuses an approval by the instruction's `created-by` actor, and that is the
  whole of the maker–checker comparison. It is sufficient **only if the actor
  who submits is the actor who created** — otherwise an actor holding both
  `operator` and `approver` submits someone else's draft, is not `created-by`,
  and approves what they themselves put forward. That was reachable end to end
  until this set existed, and the claim that it was not lived in a docstring
  with nothing behind it (standing lesson **L-6**).

  `:cancel` is deliberately **not** here. PR-004 names cancellation alongside
  amendment as a creator's act, but `cancel` is also permitted from `approved`,
  where it is a controller's stop rather than a maker's retraction — and
  `controller` holds `:payment/cancel` precisely so that someone other than the
  maker can halt a payment. Restricting it to the creator would remove that.
  Cancellation also destroys no control: it moves an instruction to a terminal
  state and can never produce an approval. Recorded as an open question in
  `docs/audits/003-REQ-authorisation-and-audit-trail.md` rather than settled
  here, because widening it is a product decision about who may stop a payment.

  Enforced by `clofin.payments.repository/transition!`, under the row lock, in
  the same transaction as the state change — not at the HTTP boundary, so a
  caller that reaches the repository by another route is refused too.

  [C-01]: docs/COMPLIANCE.md"
  #{:submit})

(defn creator-only?
  "True when `event` may be caused only by the instruction's creator."
  [event]
  (contains? creator-only-events event))

(defn mutable?
  "True when an instruction in `state` may be amended in place."
  [state]
  (outgoing state)                      ; an unrecognised status is a defect, not a `false`
  (contains? mutable-states state))

(defn reversible?
  "True when a reversal may be raised against an instruction in `state`."
  [state]
  (outgoing state)
  (contains? reversible-states state))

(defn assert-mutable!
  "Throw a `:conflict` unless an instruction in `state` may be amended.

  Same shape as a refused transition, because from a caller's side it is the
  same kind of answer: the instruction is not in a state where this makes
  sense, and here is the state it is in."
  [state]
  (when-not (mutable? state)
    (err/conflict!
     (str "Cannot amend a payment instruction that is " (name state)
          "; only a draft may be amended")
     {:instruction-status (name state)
      :attempted          "amend"
      :mutable-in         (mapv name (sort mutable-states))}))
  state)

(defn assert-reversible!
  "Throw a `:conflict` unless a reversal may be raised against `state`."
  [state]
  (when-not (reversible? state)
    (err/conflict!
     (str "Cannot reverse a payment instruction that is " (name state)
          "; only a settled instruction can be reversed")
     {:instruction-status (name state)
      :attempted          "reverse"
      :reversible-in      (mapv name (sort reversible-states))}))
  state)
