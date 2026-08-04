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
            [clofin.idempotency :as idem])
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
         ;; The sweep is a state change to the items it marks, caused by the
         ;; passage of time rather than by a scheme. Its own term, because
         ;; "we stopped waiting" is not "the scheme answered".
         "settlement-batch.timeout-swept"]))

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
         "settlement-batch"]))

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

  Refuses an unknown action, an unknown subject type, a missing subject, or a
  missing actor outside the bootstrap. A `record!` that quietly accepted
  anything would make the vocabulary above a suggestion, and an audit trail
  whose vocabulary is a suggestion cannot answer \"show me every approval in
  August\" completely."
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
  digest that changed depending on which one it was handed would prove nothing."
  [:id :organisation-id :debtor-account-id :creditor-name :creditor-account
   :amount :value-date :purpose-code :status :created-by :reverses-id])

(defn instruction-subject
  "The projection of an instruction that its audit digests are taken over."
  [instruction]
  (when instruction
    (select-keys instruction instruction-fields)))

(def approval-fields
  "The fields of an approval that a digest covers."
  [:id :instruction-id :actor-id :decision :reason :invalidated-at])

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
