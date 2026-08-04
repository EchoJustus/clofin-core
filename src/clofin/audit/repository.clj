(ns clofin.audit.repository
  "Storage for audit events, and the evidence extraction built on it.

  This namespace is the enforcement point for [C-05] and for invariant I9.
  One property makes both true and it is not optional:

  **`record!` writes on the connection it is handed.** It takes a `tx` and
  never opens one. A caller therefore cannot write an audit event outside the
  transaction that carries the change it describes — the only connection
  available to it *is* that transaction. Writing the event afterwards, even one
  line later, means a crash in between produces a state change with no record;
  that is precisely the failure C-05 exists to prevent, and it is invisible
  until an incident.

  The database enforces the other half. `audit_event_append_only` rejects
  `UPDATE` and `DELETE` at the row level (migration `0005`, reusing the
  `reject_mutation()` function from `0002`), so an event that committed cannot
  be edited by the application role, by a maintenance session, or by a defect.
  `clofin.db.audit-constraints-test` attempts both directly in SQL.

  Evidence extraction (PR-074) is the other half of the control's value. An
  append-only table nobody can query completely is a table that satisfies an
  auditor's question with \"probably\".

  [C-05]: docs/COMPLIANCE.md"
  (:require [clofin.audit :as audit]
            [clofin.db.core :as db]
            [clofin.error :as err]
            [clojure.string :as str]))

(def row-cap
  "Maximum rows an audit query returns.

  The same cap and the same reasoning as the ledger's and the payments list's:
  real pagination waits for a consumer that needs it, and until then a hard cap
  with an explicit `truncated` flag is the smallest thing that is not
  misleading. An audit answer that silently stopped at a cap would be the worst
  possible place for that to happen. See ADR-0011."
  500)

;; ---------------------------------------------------------------------------
;; Rows to domain values
;; ---------------------------------------------------------------------------

(def ^:private event-columns
  "select id, organisation_id, actor_id, action, subject_type, subject_id,
          before_digest, after_digest, correlation_id, occurred_at
     from audit_event ")

(defn- row->event
  [row]
  (when row
    {:id              (:id row)
     :organisation-id (:organisation-id row)
     :actor-id        (:actor-id row)
     :action          (:action row)
     :subject-type    (:subject-type row)
     :subject-id      (:subject-id row)
     :before-digest   (:before-digest row)
     :after-digest    (:after-digest row)
     :correlation-id  (:correlation-id row)
     :occurred-at     (db/->instant (:occurred-at row))}))

;; ---------------------------------------------------------------------------
;; Writing
;; ---------------------------------------------------------------------------

(defn record!
  "Append one audit event **on the caller's transaction**. Returns it as stored.

      (record! tx {:actor-id … :action \"payment.approved\"
                   :subject-type \"payment-instruction\" :subject-id …
                   :before before-value :after after-value
                   :correlation-id (:correlation-id request)})

  `tx` is a connection inside the transaction that also carries the change this
  event describes — in practice the one `clofin.idempotency.repository/execute-once!`
  hands its effect. Pass a pool and the event commits on its own, which is the
  bug this function's whole shape exists to make obvious in review: a lone
  audit event is an event that survived a change that did not.

  `:before` and `:after` are **domain values**, not digests. `clofin.audit/event`
  digests them, so there is exactly one place that decides what an audit digest
  is taken over.

  The event id is generated here rather than accepted: an audit event whose id
  a caller chose is an audit event a caller can collide with."
  [tx {:keys [organisation-id actor-id action subject-type subject-id
              before after correlation-id]}]
  (let [ev (audit/event {:organisation-id organisation-id
                         :actor-id        actor-id
                         :action          action
                         :subject-type    subject-type
                         :subject-id      subject-id
                         :before          before
                         :after           after
                         :correlation-id  correlation-id})
        id (random-uuid)
        row (db/insert-returning!
             tx
             ["insert into audit_event
                 (id, organisation_id, actor_id, action, subject_type, subject_id,
                  before_digest, after_digest, correlation_id)
               values (?, ?, ?, ?, ?, ?, ?, ?, ?)
               returning occurred_at"
              id (:organisation-id ev) (:actor-id ev) (:action ev)
              (:subject-type ev) (:subject-id ev)
              (:before-digest ev) (:after-digest ev) (:correlation-id ev)])]
    (assoc ev :id id :occurred-at (db/->instant (:occurred-at row)))))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn- ordered
  "Audit events are ordered by occurrence and then by id.

  A total order, so the same query over unchanged data returns the same page
  every time. Two events written in one transaction share `occurred_at` to the
  microsecond — `now()` is the transaction's start time in PostgreSQL — so
  ordering on the timestamp alone would let a history reorder itself between
  reads, which in an evidence pack is indistinguishable from tampering."
  [direction]
  (str " order by occurred_at " direction ", id " direction " limit ?"))

;; A consequence worth stating rather than discovering: events written in one
;; transaction share `occurred_at` exactly, so `id` is what orders them — and
;; `id` is random. Within a single transaction the order is therefore *stable*
;; (the same query returns the same order every time, which is what matters for
;; an evidence pack not to look tampered with) but not *causal*. Ordering two
;; events that happened atomically is a question with no answer; a monotonic
;; sequence column would give one, and is recorded as a candidate rather than
;; smuggled in here.

(defn events-for-subject
  "Every audit event about one subject, oldest first.

  This is the shape an evidence pack is built from (PR-074, AC-12): the
  complete history of one payment, in the order it happened, each entry
  carrying the actor who caused it."
  [source organisation-id subject-id]
  (mapv row->event
        (db/query source [(str event-columns
                               "where organisation_id = ? and subject_id = ?"
                               (ordered "asc"))
                          organisation-id subject-id (inc row-cap)])))

(defn list-events
  "An organisation's audit events, most recent first, capped at `row-cap`.

  `action`, `subject-id`, `from` and `to` narrow the answer; `from` is
  inclusive and `to` is exclusive, the same half-open period the account
  statement uses (ADR-0011), so consecutive extractions chain exactly rather
  than double-counting whatever landed on the boundary.

  Returns `{:events [...] :truncated? bool}`; one row beyond the cap is read
  purely to learn whether there were more."
  [source organisation-id {:keys [action subject-id from to]}]
  (when (and action (not (contains? audit/actions action)))
    (err/invalid! (str "Unknown audit action: " action)
                  {:action action :known (vec audit/actions)}))
  (when (and from to (.isAfter ^java.time.Instant from ^java.time.Instant to))
    (err/invalid! "The period's start must not be after its end"
                  {:from (str from) :to (str to)}))
  (let [clauses (cond-> ["organisation_id = ?"]
                  action     (conj "action = ?")
                  subject-id (conj "subject_id = ?")
                  from       (conj "occurred_at >= ?")
                  to         (conj "occurred_at < ?"))
        params  (cond-> [organisation-id]
                  action     (conj action)
                  subject-id (conj subject-id)
                  from       (conj from)
                  to         (conj to))
        rows (db/query source
                       (into [(str event-columns "where "
                                   (str/join " and " clauses)
                                   (ordered "desc"))]
                             (conj params (inc row-cap))))]
    {:events     (mapv row->event (take row-cap rows))
     :truncated? (> (count rows) row-cap)}))

(defn events-for-payment
  "Every audit event about an instruction **and about its approvals**, oldest
  first.

  An approval's events — `approval.recorded`, `approval.invalidated`,
  `approval.withdrawn` — carry the *approval* as their subject, because that is
  what they are about: a decision came into existence, or stopped standing.
  Keying them on the payment would be the mislabelling audit finding F-005
  corrected in the other direction.

  But an evidence pack for a payment has to show them, or it cannot answer
  \"who approved this, and what happened to their approval?\" — which is most
  of what an approval trail is for. So the relation is made here, in the query,
  rather than by flattening it into the subject column: an approval belongs to
  exactly one instruction, and `approval.instruction_id` already says which.
  Audit finding **F-006** required this extension.

  Harmless when `subject-id` is itself an approval: no approval names an
  approval as its instruction, so the sub-select adds nothing and the pack is
  the subject's own events."
  [source organisation-id subject-id]
  (mapv row->event
        (db/query source [(str event-columns
                               "where organisation_id = ?
                                  and (subject_id = ?
                                       or subject_id in (select id from approval
                                                          where instruction_id = ?))"
                               (ordered "asc"))
                          organisation-id subject-id subject-id (inc row-cap)])))

(defn evidence-pack
  "Every state change of one subject, in order, with its actor (PR-074, AC-12).

  Returns nil when the subject has no events in this organisation, so a caller
  can answer `404` rather than presenting an empty pack as a complete one — an
  evidence pack that is silently empty is worse than no evidence pack, because
  it reads as proof that nothing happened.

  The pack states its own boundaries: the period it spans and whether it hit
  the row cap. An auditor should never have to infer completeness."
  [source organisation-id subject-id]
  (let [rows (events-for-payment source organisation-id subject-id)
        events (vec (take row-cap rows))]
    (when (seq events)
      {:subject-id  subject-id
       ;; The subject the pack is *about*, not the type of its first event —
       ;; the pack now mixes payment-instruction and approval events, and the
       ;; first one is whichever happened earliest.
       :subject-type (or (some (fn [e] (when (= subject-id (:subject-id e))
                                         (:subject-type e)))
                               events)
                         (:subject-type (first events)))
       :events      events
       :from        (:occurred-at (first events))
       :to          (:occurred-at (last events))
       :truncated?  (> (count rows) row-cap)})))
