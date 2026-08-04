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
         "approval.withdrawn"]))

(def subject-types
  "Every kind of thing an audit event may be about."
  (into (sorted-set) ["payment-instruction" "approval"]))

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

  Refuses an unknown action, an unknown subject type, or a missing subject.
  A `record!` that quietly accepted anything would make the vocabulary above a
  suggestion, and an audit trail whose vocabulary is a suggestion cannot answer
  \"show me every approval in August\" completely."
  [{:keys [organisation-id actor-id action subject-type subject-id
           before after correlation-id]}]
  (when-not (contains? actions action)
    (err/invalid! (str "Unknown audit action: " action)
                  {:action (str action) :known (vec actions)}))
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
