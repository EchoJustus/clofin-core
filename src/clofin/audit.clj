(ns clofin.audit
  "The audit event: its vocabulary, and the digests that stand in for payloads.

  `audit_event` records **digests, not payloads**
  (docs/ADR/0016-audit-events-store-digests-not-payloads.md). A payments audit
  table that holds counterparty names is a second copy of the data C-09 exists
  to minimise, and it is append-only — so it can never be removed, corrected or
  subjected to a retention policy. A digest proves *that* something changed,
  and proves *what it changed to* when compared against a value the auditor
  already holds, which is what an evidence request actually turns on.

  What that costs an auditor is real and is stated in the ADR rather than
  glossed over: a digest cannot be read. An investigation that wants to know
  the amount an instruction carried at 14:02 reads the instruction, and uses
  the digest to prove the row has not moved since.

  Storage lives in `clofin.audit.repository`, the same split
  `clofin.idempotency` and `clofin.idempotency.repository` already use: the
  seam ADR-0012 names is the namespace called `repository`, and keeping the
  digest on this side is what makes it a pure function of one argument,
  testable without a database.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.error :as err]
            [clofin.idempotency :as idem]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.time Instant LocalDate]
           [java.util HexFormat]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; The action vocabulary
;; ---------------------------------------------------------------------------

(def actions
  "Every action an audit event may record.

  A constrained vocabulary rather than free text, for the same reason
  `journal_entry.reference_type` is constrained: an audit trail that an auditor
  has to `like '%approv%'` their way through is an audit trail nobody can
  produce a complete answer from. `record!` refuses an action absent from this
  set — default deny reaching the audit trail as well.

  Named `<subject>.<past-tense-verb>`: the event is a statement about something
  that has already happened, and phrasing it as an imperative would invite
  reading the table as a queue of work.

  **An action named after a state transition is emitted only in the transaction
  where that transition commits** (standing lesson **L-7**). That rule is what
  separates `approval.recorded` from `payment.approved`: recording an approval
  and approving a payment are different events, and for a two-approval
  threshold the first happens twice and the second once. Until audit finding
  **F-005** they were the same term, so an auditor filtering
  `action = 'payment.approved'` saw two events for a payment approved once, the
  earlier of them describing a payment that was still `pending-approval` —
  with before and after digests that were identical, because nothing had
  changed.

  A decision, a partial step and a state change each get their own term. When
  adding one, ask which of the three it is before naming it."
  (into (sorted-set)
        ["payment.created"
         "payment.submitted"
         "payment.amended"
         "payment.cancelled"
         "payment.approved"
         "payment.rejected"
         ;; The decision itself, one per approver, whether or not it moves the
         ;; payment. Subject is the approval, not the payment.
         "approval.recorded"
         ;; An approval that stopped standing because the instruction was
         ;; amended (PR-014). A real state change on a real record, and until
         ;; finding F-006 it emitted nothing at all.
         "approval.invalidated"
         "approval.withdrawn"
         ;; The three writes that were silent until TASK-005. Each is a
         ;; creation, so each is emitted once, in the transaction where the row
         ;; it names first exists — which is what L-7 asks of a term named
         ;; `<subject>.<transition>`. None of the three has a decision or a
         ;; partial step to distinguish it from, so none needs a second term
         ;; the way `approval.recorded` needed one beside `payment.approved`.
         "organisation.created"
         "account.created"
         ;; `posted` rather than `created`: a journal entry is not drafted and
         ;; then posted, and it is never amended afterwards (C-03) — posting is
         ;; the only transition it has, and naming the event after the act
         ;; keeps the vocabulary a description of what happened rather than of
         ;; how a row arrived.
         "journal-entry.posted"

         ;; Settlement (TASK-004). The payment's own remaining transitions,
         ;; each emitted in the transaction where that transition commits and
         ;; nowhere else — L-7, which this vocabulary is the first extension to
         ;; be written under rather than corrected by.
         "payment.released"
         "payment.settled"
         "payment.returned"
         ;; Reserved: the lifecycle carries a `fail` arrow out of `released`,
         ;; but no item outcome drives it — `settlement_batch_item.outcome` is
         ;; settled | returned | timed-out, and a scheme failure that sends the
         ;; money back IS a return. Declared because brief 004 §9 names it and
         ;; because increment 6 or 7 may drive it; recorded as objection O-1 in
         ;; 004-REQ rather than left as an unexplained term nothing emits.
         "payment.failed"

         ;; The batch's own lifecycle. Deliberately distinct from the payments'
         ;; terms: a batch being submitted is not a payment being released, and
         ;; counting one as the other is exactly the mislabelling F-005 found.
         "settlement-batch.created"
         "settlement-batch.submitted"
         ;; **Only** where the batch reaches a terminal derived status, i.e.
         ;; when the last unresolved item resolves. A response that resolves one
         ;; item of ten completes nothing, and a batch with unresolved items is
         ;; not completed however many responses have arrived.
         "settlement-batch.completed"
         ;; The **second** move of a derived status that had already reached a
         ;; terminal value: a late `timeout-resolution` says what really happened
         ;; to an item the sweep had given up on, and a batch that derived to
         ;; `failed` becomes `settled` or `partially-settled`. That is a real
         ;; change to a real column with a real actor behind it, and until this
         ;; term it left no event whose subject was the batch — the one disclosed
         ;; exception on C-05, found by the `ref-1` release audit as **A-004**
         ;; and carried as named debt through two increments.
         ;;
         ;; `restated` rather than a second `completed`, and the distinction is
         ;; L-7's: `completed` names the transition *into* a complete batch, and
         ;; that transition happened earlier, under a different actor and a
         ;; different correlation id. Two `completed` events for one batch would
         ;; be F-005's mislabelling with a new name. "Restated" is what a set of
         ;; books calls a figure that later information corrected, which is
         ;; exactly what a late answer does to a batch's outcome.
         ;;
         ;; Emitted only where the derived status actually moves. A late
         ;; resolution that leaves `partially-settled` where it was changes no
         ;; batch-level fact and must leave no event saying it did.
         "settlement-batch.status-restated"
         ;; The sweep is a state change to the items it marks, caused by the
         ;; passage of time rather than by a scheme. Its own term, because
         ;; "we stopped waiting" is not "the scheme answered".
         "settlement-batch.timeout-swept"

         ;; Reconciliation (TASK-008).
         ;;
         ;; A statement **arriving** is the fact, and it is one fact whether or
         ;; not CloFin could process it: receipt and disposition are separate
         ;; things, and the disposition travels in the subject digest rather
         ;; than in a second term (standing lesson **L-11**). A *replayed*
         ;; delivery creates no row and emits nothing — the trail records
         ;; arrivals, not requests.
         "reconciliation-statement.received"
         ;; One per break the matching opened. A break is a record coming into
         ;; existence, so it is emitted once, in the transaction where the row
         ;; first exists — L-7's requirement of a `<subject>.<transition>` term.
         "reconciliation-break.opened"
         ;; **Assignment is the transition.** A break becomes investigated by
         ;; somebody taking it on, so this is one fact and one event rather than
         ;; an ownership change plus a state change that always accompany each
         ;; other. Re-assigning an already-investigated break emits the same
         ;; term with a state that did not move, which is honest: the before and
         ;; after digests differ in the assignee and in nothing else.
         "reconciliation-break.assigned"
         ;; Emitted **only** in the transaction where the break reaches its
         ;; terminal state, which is the transaction in which its adjustment
         ;; posts. A proposed adjustment resolves nothing.
         "reconciliation-break.resolved"
         ;; The adjustment is proposed, and — separately, possibly under a
         ;; different actor and certainly under a different correlation id —
         ;; posted. Two terms because they are two decisions: one operator
         ;; proposes a correction, and it becomes a movement in the books only
         ;; when the approvals it needs exist. Collapsing them would make a
         ;; count of postings a count of proposals, which is F-005's shape.
         "reconciliation-adjustment.proposed"
         "reconciliation-adjustment.posted"
         ;; The third thing that can happen to a proposal, and the one that left
         ;; no evidence at all until TASK-010: an approver refused it, with a
         ;; reason. Its own term beside `posted` because they are two different
         ;; endings of one lifecycle and counting either as the other would tell
         ;; an auditor a correction was made when one was declined.
         ;;
         ;; Emitted **only** in the transaction where the adjustment reaches
         ;; `rejected`, which is L-7's requirement of a term named after a
         ;; transition. The decision itself is `approval.recorded`, as it is for
         ;; every other decision in CloFin — one refusal, two events, because a
         ;; decision being taken and a subject becoming terminal are two facts.
         "reconciliation-adjustment.rejected"]))

(def subject-types
  "Every kind of thing an audit event may be about.

  One term per kind of row an event can name. `payment-instruction` is spelt
  out rather than shortened to `payment` because the subject is the
  *instruction* record, which is what `subject_id` addresses."
  (into (sorted-set)
        ["payment-instruction" "approval"
         "organisation" "account" "journal-entry"
         ;; The batch, not its items. An item has no identity of its own — its
         ;; key is (batch, instruction) — so an event about an item names the
         ;; instruction it is about, and an event about the batch names the
         ;; batch. Inventing a synthetic item id purely to have something to put
         ;; in `subject_id` would put a surrogate in the column an auditor joins
         ;; on.
         "settlement-batch"
         ;; Reconciliation (TASK-008). Three kinds of row, three subjects.
         ;; A statement's *lines* and *matches* have no identity of their own —
         ;; a line is addressed by (statement, position) and a match by the line
         ;; it sits on — so an event about either names the statement, and the
         ;; statement's own projection carries the content digest and the match
         ;; list that prove what those rows say. The same reasoning that keeps a
         ;; settlement item's events on the instruction and on the batch.
         "reconciliation-statement"
         "reconciliation-break"
         "reconciliation-adjustment"]))

(def payment-action-prefix
  "The one action prefix whose subject type is not spelt the same way.

  `payment.*` addresses the **instruction** record, so its subject type is
  `payment-instruction`. Held as a constant rather than inlined because
  `api/openapi.yaml` publishes the same exception in prose and
  `clofin.contract-test` compares the two."
  "payment")

(defn subject-type-for
  "The subject type `action` is about, derived from the action itself.

  Every action is named `<subject>.<past-tense-verb>` and — with the single
  `payment.*` exception above — **the prefix is the subject type**. Deriving it
  rather than tabulating it is what makes the rule enforceable: a term added to
  `actions` whose prefix names no subject type has nowhere to go, instead of
  quietly acquiring whatever subject its first caller passed.

  Returns the derived name whether or not it is a known subject type; `event`
  decides what to do about that."
  [action]
  (when (string? action)
    (let [prefix (first (str/split action #"\." 2))]
      (if (= payment-action-prefix prefix) "payment-instruction" prefix))))

(def bootstrap-actions
  "The actions that may be recorded with no actor at all.

  `POST /organisations` is the bootstrap, and it is deliberately
  unauthenticated: no actor can exist before the organisation that holds one,
  so there is no principal for its event to carry (`clofin.api.principal`, and
  003-REQ §6). `audit_event.actor_id` is nullable for exactly this case and
  migration `0005` says so in a column comment — *\"null only where there is
  genuinely no authenticated actor. Today that is the bootstrap case alone; a
  null here on a payment action would be a defect.\"*

  **A column comment is not an enforcement point** (standing lesson **L-6**).
  Left at that, \"a null actor means the bootstrap\" is a sentence an auditor is
  asked to trust, while nothing stops a future caller writing a null actor on a
  payment action and making it false — and an unattributed state change is the
  half of C-05 that says *who*. So the exemption is named here as a set, and
  `event` refuses a null actor for every action outside it. The trail's null
  column then has one meaning, and that meaning is checked rather than
  described.

  The rule is one-directional on purpose. A bootstrap action *may* carry no
  actor; it is not required to be actorless. An administered
  organisation-creation path arriving later records its principal without this
  set having to change — and if it ever should stop being exempt, removing the
  term here is the whole change."
  (into (sorted-set) ["organisation.created"]))

;; ---------------------------------------------------------------------------
;; Digests
;; ---------------------------------------------------------------------------

(def canonicalisation-version
  "Names the canonical form a digest was taken over.

  Every stored digest carries this prefix. Without it, a later amendment to the
  canonical serialisation would produce digests that *look* comparable to older
  ones and are not — an auditor comparing across the change would conclude a
  record had been altered when only the algorithm had. The prefix makes that
  case visible instead of silent, which is the whole job of an audit column.

  Bump it in the same commit as any change to `clofin.idempotency/canonical`."
  "v1")

(defn- sha256 ^String [^String s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s "UTF-8"))
      (->> (.formatHex (HexFormat/of)))))

(defn normalise
  "Render a domain value as something the canonicaliser can serialise.

  UUIDs, instants, dates and keywords become strings; maps and vectors are
  walked. Necessary because `clofin.idempotency/canonical` deliberately throws
  on a type it has no rule for rather than falling back to `str` — a silent
  fallback is how two different values come to share a digest, and here that
  would mean two different instructions producing one audit record.

  Map keys are rendered too, so a domain map keyed by kebab-case keywords
  digests identically however it was built."
  [value]
  (cond
    (nil? value)     nil
    (map? value)     (into (sorted-map)
                           (map (fn [[k v]]
                                  [(if (keyword? k) (name k) (str k)) (normalise v)]))
                           value)
    (or (vector? value) (seq? value) (set? value))
    (mapv normalise (if (set? value) (sort-by str value) value))
    (keyword? value) (name value)
    (uuid? value)    (str value)
    (instance? Instant value)   (str value)
    (instance? LocalDate value) (str value)
    (or (string? value) (number? value) (boolean? value)) value
    :else (str value)))

(defn digest
  "A version-tagged digest of `value`, or nil for nil.

  Nil in, nil out: `before_digest` is null when the subject did not exist
  before the change, and that absence is meaningful — it is what distinguishes
  a creation from an update in the trail. Digesting nil into a fixed hash would
  make every creation indistinguishable from an update of an empty record."
  [value]
  (when (some? value)
    (str canonicalisation-version ":" (sha256 (idem/canonical (normalise value))))))

;; ---------------------------------------------------------------------------
;; The event
;; ---------------------------------------------------------------------------

(defn event
  "Validate and normalise an audit event, ready to be written.

  Takes `:before` and `:after` as domain values and digests them here; the
  caller never computes a digest itself, so there is one place that decides
  what an audit digest is taken over.

  Refuses an unknown action, an unknown subject type, a **subject type the
  action is not about**, a missing subject, or a missing actor outside the
  bootstrap. A `record!` that quietly accepted anything would make the
  vocabulary above a suggestion, and an audit trail whose vocabulary is a
  suggestion cannot answer \"show me every approval in August\" completely.

  The third of those was added by the `ref-1` release audit (**A-015**). Until
  then the two vocabularies were checked *independently*, so `payment.approved`
  with subject type `account` — two individually valid values, one impossible
  pair — was accepted and stored. Every call site happened to be right, which
  is exactly the condition under which a missing check goes unnoticed: the
  naming rule this vocabulary is documented and queried by was a convention the
  code did not hold anyone to. It holds them to it now (standing lesson
  **L-6**: a premise a control rests on is traced to its own enforcement
  point)."
  [{:keys [organisation-id actor-id action subject-type subject-id
           before after correlation-id]}]
  (when-not (contains? actions action)
    (err/invalid! (str "Unknown audit action: " action)
                  {:action (str action) :known (vec actions)}))
  ;; Checked after the action, because it is the action that decides whether an
  ;; absent actor is the documented bootstrap or a missing attribution. See
  ;; `bootstrap-actions` for why this is code rather than a column comment.
  (when (and (nil? actor-id) (not (contains? bootstrap-actions action)))
    (err/invalid! (str "An audit event for " action " must name the actor that caused it")
                  {:action action :bootstrap-actions (vec bootstrap-actions)}))
  (when-not (contains? subject-types subject-type)
    (err/invalid! (str "Unknown audit subject type: " subject-type)
                  {:subject-type (str subject-type) :known (vec subject-types)}))
  ;; Checked last of the three vocabulary rules, so a caller that got both
  ;; values wrong is told which one is unknown before being told the pair is
  ;; impossible.
  (when-not (= subject-type (subject-type-for action))
    (err/invalid! (str "An audit event for " action " is about a "
                       (subject-type-for action) ", not a " subject-type)
                  {:action       action
                   :subject-type (str subject-type)
                   :expected     (subject-type-for action)}))
  (when-not (uuid? subject-id)
    (err/invalid! "An audit event must name the subject it is about"
                  {:action action}))
  (when-not (uuid? organisation-id)
    (err/invalid! "An audit event must name the organisation it belongs to"
                  {:action action}))
  {:organisation-id organisation-id
   :actor-id        actor-id
   :action          action
   :subject-type    subject-type
   :subject-id      subject-id
   :before-digest   (digest before)
   :after-digest    (digest after)
   :correlation-id  correlation-id})

;; ---------------------------------------------------------------------------
;; What gets digested
;; ---------------------------------------------------------------------------

(def instruction-fields
  "The fields of a payment instruction that a digest covers.

  Everything an amendment could change, plus identity, provenance and status —
  so that a digest distinguishes a submission from an amendment from a
  cancellation. Deliberately explicit rather than \"the whole map\": a value
  read back from a row and one built in memory differ in incidental keys, and a
  digest that changed depending on which one it was handed would prove nothing.

  **`:retries-id` is here because the linkage is an audited fact, not a
  convenience column** (ADR-0024). A retry's `payment.created` event therefore
  carries, in its after digest, which returned payment this one replaces — so
  the relation is provable from the trail and not only from the row, and a
  linkage altered afterwards would no longer match the digest the creation left
  behind. `:reverses-id` has been in this projection for the same reason since
  the field existed.

  **`:retried-by-ids` is deliberately absent, and its absence is load-bearing.**
  It is derived at read time from other rows, so a projection carrying it would
  give one instruction two different digests before and after somebody else
  raised a retry against it — a before/after pair that differed for a reason
  that is not a change to this record. That is the same reasoning that keeps
  `age-seconds` out of `reconciliation-break-fields` and a balance out of
  `account-fields`. The retry's own creation event is where that fact is
  recorded."
  [:id :organisation-id :debtor-account-id :creditor-name :creditor-account
   :amount :value-date :purpose-code :status :created-by :reverses-id
   :retries-id])

(defn instruction-subject
  "The projection of an instruction that its audit digests are taken over."
  [instruction]
  (when instruction
    (select-keys instruction instruction-fields)))

(def approval-fields
  "The fields of an approval that a digest covers.

  `:adjustment-id` joined `:instruction-id` in TASK-008, when an approval became
  a decision about one of two kinds of subject. Both are in the projection, and
  both are in it *always*: a field left out is a field an alteration could move
  without the digest noticing, and \"which thing was this approval about?\" is
  the first question an evidence pack has to answer."
  [:id :instruction-id :adjustment-id :actor-id :decision :reason :invalidated-at])

(defn approval-subject
  "The projection of an approval that its audit digests are taken over."
  [approval]
  (when approval
    (select-keys approval approval-fields)))

(def organisation-fields
  "The fields of an organisation that a digest covers.

  Identity and everything a later change could alter. `status` is here even
  though nothing changes it today: the projection describes what the digest
  proves about the row, and a field left out is a field an alteration could
  move without the digest noticing."
  [:id :legal-name :short-name :status])

(defn organisation-subject
  "The projection of an organisation that its audit digests are taken over."
  [organisation]
  (when organisation
    (select-keys organisation organisation-fields)))

(def account-fields
  "The fields of a ledger account that a digest covers.

  `status` in particular: freezing and closing are account state changes, and
  when they gain audit events of their own their before and after digests have
  to differ, which they only do if the projection covers the column that moved.
  Balances are deliberately absent — an account has no balance column to
  digest, only an aggregation over journal lines (ADR-0008)."
  [:id :organisation-id :code :name :type :currency :status])

(defn account-subject
  "The projection of a ledger account that its audit digests are taken over."
  [account]
  (when account
    (select-keys account account-fields)))

(def journal-entry-fields
  "The fields of a journal entry that a digest covers, including its lines.

  The lines are the entry: an entry digest that covered only the header would
  be identical for two entries moving different amounts between different
  accounts, which is the one thing a ledger digest must never be. `normalise`
  walks the vector and the money values inside it, so each line's account,
  direction, amount and currency all reach the digest.

  `recorded-at` is deliberately outside the projection, for the same reason
  `instruction-fields` excludes `created-at`: it is assigned by the database
  and is therefore present on an entry read back from a row and absent from the
  one just posted. A digest that differed depending on which of the two it was
  handed would prove nothing (an entry is append-only in any case — C-03 — so
  there is no later value for it to be compared against)."
  [:id :organisation-id :occurred-at :narrative :reference :lines])

(defn journal-entry-subject
  "The projection of a journal entry that its audit digests are taken over."
  [entry]
  (when entry
    (select-keys entry journal-entry-fields)))

(def settlement-batch-fields
  "The fields of a settlement batch that a digest covers.

  Identity, the routing that defines the batch, and `status` — which is the
  only one that moves. A batch's *membership* is deliberately outside the
  projection: members are rows in another table, and a digest that changed
  every time an item resolved would make `settlement-batch.submitted`'s after
  digest incomparable with anything an auditor could recompute later. The
  membership has its own evidence — one `payment.released` event per instruction
  in the same transaction as the submission."
  [:id :organisation-id :scheme :currency :value-date :status])

(defn settlement-batch-subject
  "The projection of a settlement batch that its audit digests are taken over."
  [batch]
  (when batch
    (select-keys batch settlement-batch-fields)))

(def reconciliation-statement-fields
  "The fields of a received statement that a digest covers.

  Identity, what the document was a statement *of*, and the two things that say
  what CloFin did with it: the `content-digest` — which itself covers every line
  — and the disposition.

  `:matches` is here, and it is the field that makes this event worth having.
  A statement's matches have no subject of their own (a match is addressed by
  the line it sits on), so without them the trail would record that a document
  arrived and not *which rule bound which line to which movement* — which is
  exactly what PR-051 asks CloFin to be able to explain. `reconciliation_match`
  is append-only, so the digest proves those rows have not moved since.

  `received-at` is deliberately outside the projection, for the same reason
  `instruction-fields` excludes `created-at`: it is assigned by the database, so
  it is present on a row read back and absent from the value just built, and a
  digest that differed depending on which it was handed would prove nothing."
  [:id :organisation-id :scheme :currency :statement-reference :format
   :format-version :period-start :period-end :content-digest
   :disposition :disposition-reason :reconciled-account-id :matches])

(defn reconciliation-statement-subject
  "The projection of a received statement that its audit digests are taken over."
  [statement]
  (when statement
    (select-keys statement reconciliation-statement-fields)))

(def reconciliation-break-fields
  "The fields of a break that a digest covers.

  Everything a later change could alter — `state` and `assignee-id` are the two
  that move — plus what the break *is*: which side or sides disagreed, by how
  much, and the detail that names it.

  **`age-seconds` is deliberately absent, and its absence is load-bearing.** A
  break's age is derived at read time from `opened-at`, so a projection that
  carried it would produce a different digest every second and make every
  before/after pair differ for reasons that are not changes. That is the same
  reasoning that keeps a balance out of `account-fields`."
  [:id :organisation-id :statement-id :account-id :kind :state :line-no
   :entry-id :currency :statement-amount :ledger-amount :detail :assignee-id])

(defn reconciliation-break-subject
  "The projection of a break that its audit digests are taken over."
  [break]
  (when break
    (select-keys break reconciliation-break-fields)))

(def reconciliation-adjustment-fields
  "The fields of an adjustment that a digest covers.

  `approvals-required` is in the projection because it is the control's own
  number: an adjustment that posted after one approval when its row said two
  would be the failure C-01 and C-02 exist to prevent, and a digest that omitted
  the requirement could not prove it did not happen."
  [:id :organisation-id :break-id :amount :direction :narrative :status
   :approvals-required :entry-id :created-by])

(defn reconciliation-adjustment-subject
  "The projection of an adjustment that its audit digests are taken over."
  [adjustment]
  (when adjustment
    (select-keys adjustment reconciliation-adjustment-fields)))
