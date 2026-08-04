(ns clofin.settlement.repository
  "Persistence for settlement batches, their items, and scheme responses.

  A `repository` namespace is CloFin's persistence seam: it may require
  `clofin.db.*`, and the pure namespaces beside it — `clofin.settlement.batch`,
  `clofin.settlement.scheme` — may not (ADR-0012).

  ## Lock order — read this before adding a function

  Settlement introduces a third row type to the discipline
  `clofin.payments.repository` documents, and it goes **first**:

  1. `settlement_batch` — `lock-batch!`
  2. `payment_instruction` — `lock-instructions!`, and
     `clofin.payments.repository/lock-instruction!`
  3. `ledger_account` — `clofin.ledger.repository/assert-postable!`, which
     orders by id within itself

  Batch before instruction because every settlement operation starts by
  addressing a batch and only then reaches its members; taking them the other
  way round would mean two operations on overlapping batches could deadlock.
  Within step 2 the instructions are locked **`order by id`** for the same
  reason `assert-postable!` orders its accounts: two batches sharing members,
  locked in opposite orders, deadlock — and a batch is precisely a set of
  instructions, so overlapping membership is the normal case rather than the
  exotic one.

  ## Why the eligibility read is locked

  Standing lesson **L-8**: a validation that gates a write must lock what it
  validated. `assert-batchable!` reads each instruction's status to decide
  whether it may be released; under `READ COMMITTED` an `amend` or a `cancel`
  committing between that read and the `update` would produce a released
  instruction that was no longer approved, with every layer behaving exactly as
  written. `for update` makes the two serialise. This is the same defect audit
  finding **F-004** found in `assert-postable!`, and it is written down here
  because it is invisible at the call site."
  (:require [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.money :as money]
            [clofin.settlement.batch :as batch]))

(def row-cap
  "Maximum rows a list query returns. The same cap and the same reasoning as
  the ledger's and the payments list's. See ADR-0011."
  500)

;; ---------------------------------------------------------------------------
;; Rows to domain values
;; ---------------------------------------------------------------------------

(def ^:private batch-columns
  "select id, organisation_id, scheme, currency, value_date, status,
          created_by, created_at
     from settlement_batch ")

(defn- row->batch
  [row]
  (when row
    {:id              (:id row)
     :organisation-id (:organisation-id row)
     :scheme          (:scheme row)
     :currency        (:currency row)
     :value-date      (db/->local-date (:value-date row))
     :status          (:status row)
     :created-by      (:created-by row)
     :created-at      (db/->instant (:created-at row))}))

(defn- row->item
  [row]
  {:batch-id       (:batch-id row)
   :instruction-id (:instruction-id row)
   :outcome        (:outcome row)
   :outcome-reason (:outcome-reason row)
   :resolved-at    (db/->instant (:resolved-at row))})

(defn- row->response
  [row]
  {:id             (:id row)
   :batch-id       (:batch-id row)
   :instruction-id (:instruction-id row)
   :kind           (:kind row)
   :reference      (:reference row)
   :received-at    (db/->instant (:received-at row))})

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn find-batch
  "The batch with this id **within this organisation**, or nil.

  Scoped by organisation, like every other lookup in CloFin: an unscoped read is
  how one tenant sees another's settlement activity."
  [source organisation-id id]
  (row->batch
   (db/query-one source [(str batch-columns "where organisation_id = ? and id = ?")
                         organisation-id id])))

(defn items-for
  "Every item of a batch, ordered by instruction id.

  A total order, so the same query over unchanged data returns the same list
  every time — which matters because this list is what `derive-status` reads and
  what an evidence pack renders."
  [source batch-id]
  (mapv row->item
        (db/query source ["select batch_id, instruction_id, outcome, outcome_reason,
                                  resolved_at
                             from settlement_batch_item
                            where batch_id = ? order by instruction_id"
                          batch-id])))

(defn responses-for
  "Every scheme response recorded against a batch, oldest first.

  Kept and returned even where a response did no work: the row is the evidence
  that a duplicate arrived and was refused, which is the posture
  `idempotency_key` takes and the reason this table exists."
  [source batch-id]
  (mapv row->response
        (db/query source ["select id, batch_id, instruction_id, kind, reference, received_at
                             from scheme_response
                            where batch_id = ? order by received_at, id limit ?"
                          batch-id (inc row-cap)])))

(defn list-batches
  "An organisation's batches, most recent first, capped at `row-cap`.

  `status` narrows to one derived status. Returns `{:batches […] :truncated? bool}`;
  one row beyond the cap is read purely to learn whether there were more."
  [source organisation-id {:keys [status]}]
  (when (and status (not (contains? batch/statuses status)))
    (err/invalid! (str "Unknown settlement batch status: " status)
                  {:status (str status) :known (vec batch/statuses)}))
  (let [rows (db/query source
                       (if status
                         [(str batch-columns
                               "where organisation_id = ? and status = ?
                                 order by created_at desc, id limit ?")
                          organisation-id status (inc row-cap)]
                         [(str batch-columns
                               "where organisation_id = ?
                                 order by created_at desc, id limit ?")
                          organisation-id (inc row-cap)]))]
    {:batches    (mapv row->batch (take row-cap rows))
     :truncated? (> (count rows) row-cap)}))

;; ---------------------------------------------------------------------------
;; Locking
;; ---------------------------------------------------------------------------

(defn lock-batch!
  "Read a batch `for update`, or `404`. **Lock order step 1.**

  Held until the caller's transaction ends, so two operators submitting or
  sweeping the same batch serialise rather than both deciding against a status
  that is about to change. Outside a transaction the lock is released at the
  next statement and guarantees nothing, which is why every caller arrives on a
  connection that owns one."
  [tx organisation-id id]
  (or (row->batch
       (db/query-one tx [(str batch-columns
                              "where organisation_id = ? and id = ? for update")
                         organisation-id id]))
      (err/not-found! "No such settlement batch in this organisation" {:id (str id)})))

(def ^:private instruction-columns
  "select id, organisation_id, debtor_account_id, creditor_name, creditor_account,
          amount_minor, currency, value_date, purpose_code, status, created_by
     from payment_instruction ")

(defn- row->instruction
  [row]
  (when row
    {:id                (:id row)
     :organisation-id   (:organisation-id row)
     :debtor-account-id (:debtor-account-id row)
     :creditor-name     (:creditor-name row)
     :creditor-account  (:creditor-account row)
     :amount            (money/of (:currency row) (db/->long (:amount-minor row)))
     :value-date        (db/->local-date (:value-date row))
     :purpose-code      (:purpose-code row)
     :status            (keyword (:status row))
     :created-by        (:created-by row)}))

(defn lock-instructions!
  "Read every named instruction `for update`, **ordered by id**, or `404` naming
  the ones that do not exist here. **Lock order step 2.**

  `order by id` is not cosmetic: two batches sharing members and locking them in
  opposite orders deadlock, and overlapping membership is the normal case for a
  settlement module. One stable order across every caller means the second waits
  instead — the same discipline `clofin.ledger.repository/assert-postable!`
  keeps for accounts.

  The lock is what makes the eligibility check downstream mean anything (L-8);
  see the namespace docstring."
  [tx organisation-id ids]
  (when (empty? ids)
    (err/invalid! "A settlement batch must name at least one payment instruction" {:count 0}))
  (let [distinct-ids (vec (distinct ids))
        rows (db/query tx (into [(str instruction-columns
                                      "where organisation_id = ? and id in ("
                                      (db/placeholders (count distinct-ids)) ")"
                                      " order by id for update")
                                 organisation-id]
                                distinct-ids))
        found (mapv row->instruction rows)
        by-id (into {} (map (juxt :id identity)) found)]
    (when-let [missing (seq (remove by-id distinct-ids))]
      (err/fail! :unprocessable
                 "Some payment instructions do not exist in this organisation"
                 {:instruction-ids (mapv str missing)}))
    found))

;; ---------------------------------------------------------------------------
;; Writing
;; ---------------------------------------------------------------------------

(defn insert-batch!
  "Write a batch row in `open`. Returns it carrying the creation instant.

  `created_at` comes back from the database rather than from a clock read in
  the application: the domain layer reads no clock, and the row's own timestamp
  is the one an investigation will be looking at."
  [tx candidate]
  (batch/assert-scheme! (:scheme candidate))
  (let [row (db/insert-returning!
             tx
             ["insert into settlement_batch
                 (id, organisation_id, scheme, currency, value_date, status, created_by)
               values (?, ?, ?, ?, ?, 'open', ?)
               returning created_at"
              (:id candidate) (:organisation-id candidate) (:scheme candidate)
              (:currency candidate) (:value-date candidate) (:created-by candidate)])]
    (assoc candidate :status "open" :created-at (db/->instant (:created-at row)))))

(defn add-items!
  "Add memberships, or refuse the whole batch.

  The refusal an operator will actually hit is `settlement_item_live_key` — the
  partial unique index that lets an instruction belong to at most one membership
  that is pending, settled or timed out. It is translated here into a named
  `409` rather than surfacing as a `500`, because \"this payment is already in a
  batch\" is something the caller can act on, and because that index **is**
  AC-7: the guard against settling one payment twice lives in the schema, so it
  binds a fix-up script and a defect as well as this function."
  [tx batch-id instruction-ids]
  (try
    (doseq [id (sort-by str instruction-ids)]
      (db/execute! tx ["insert into settlement_batch_item (batch_id, instruction_id)
                        values (?, ?)"
                       batch-id id]))
    (catch Exception t
      (let [{:keys [sql-state constraint]} (db/violation t)]
        (if (and (= sql-state (:unique-violation db/sql-states))
                 (= "settlement_item_live_key" constraint))
          (err/conflict!
           (str "A payment instruction named here is already in a settlement batch that is "
                "pending, settled or timed out; only a returned item frees its instruction "
                "for re-batching")
           {:constraint constraint
            :batch-id   (str batch-id)})
          (throw t))))))

(defn set-batch-status!
  "Write the batch's derived status. Returns the batch as stored.

  Takes a status the caller obtained from `clofin.settlement.batch/derive-status`
  — this function does not decide, it records. Keeping the decision in the pure
  namespace is what stops a second, subtly different derivation appearing here
  the first time someone needs a status in a hurry."
  [tx organisation-id id status]
  (when-not (contains? batch/statuses status)
    (err/invalid! (str "Unknown settlement batch status: " status)
                  {:status (str status) :known (vec batch/statuses)}))
  (db/execute! tx ["update settlement_batch set status = ?
                     where organisation_id = ? and id = ?"
                   status organisation-id id])
  (find-batch tx organisation-id id))

(defn resolve-item!
  "Record an item's outcome, exactly once. Returns the item as stored, or nil
  when it was already resolved.

  `where outcome is null` is the whole guarantee, and it is in the statement
  rather than in a preceding read: a check-then-update is a race, and the window
  between them is exactly long enough for a duplicate scheme response on another
  connection to resolve the same item. Returning nil for zero rows updated lets
  the caller distinguish \"I resolved it\" from \"someone already had\" without
  asking again — which is what makes the duplicate path emit no second posting
  and no second audit event (AC-5).

  The caller holds the batch lock, so in practice the two deliveries serialise
  at step 1 of the lock order; this is the belt to that braces, and it is the
  half that keeps working if a later caller forgets the lock."
  [tx batch-id instruction-id outcome reason]
  (when-not (contains? batch/item-outcomes outcome)
    (err/invalid! (str "Unknown settlement outcome: " outcome)
                  {:outcome (str outcome) :known (vec batch/item-outcomes)}))
  (let [updated (db/execute! tx ["update settlement_batch_item
                                     set outcome = ?, outcome_reason = ?, resolved_at = now()
                                   where batch_id = ? and instruction_id = ?
                                     and outcome is null"
                                 outcome reason batch-id instruction-id])]
    (when (pos? updated)
      (first (filter #(= instruction-id (:instruction-id %)) (items-for tx batch-id))))))

(defn resolve-timed-out-item!
  "Resolve a **timed-out** item to its true outcome, exactly once.

  The only way an item leaves `timed-out`, and the only `UPDATE` in this module
  that overwrites an outcome. `where outcome = 'timed-out'` is what makes it
  exactly once: a second resolution updates zero rows and gets nil, whatever
  order the deliveries arrive in.

  Note it does **not** clear `resolved_at` back to the original moment — the
  timestamp becomes the moment the truth was learned, which is the fact an
  investigation wants. When CloFin stopped waiting is in the audit trail, under
  `settlement-batch.timeout-swept`."
  [tx batch-id instruction-id outcome reason]
  (when-not (contains? #{"settled" "returned"} outcome)
    (err/invalid! (str "A timeout resolution must resolve to settled or returned, not " outcome)
                  {:outcome (str outcome) :known ["settled" "returned"]}))
  (let [updated (db/execute! tx ["update settlement_batch_item
                                     set outcome = ?, outcome_reason = ?, resolved_at = now()
                                   where batch_id = ? and instruction_id = ?
                                     and outcome = 'timed-out'"
                                 outcome reason batch-id instruction-id])]
    (when (pos? updated)
      (first (filter #(= instruction-id (:instruction-id %)) (items-for tx batch-id))))))

(defn sweep-timeouts!
  "Mark every unresolved item of a batch `timed-out`. Returns the instruction
  ids marked.

  Only items with **no** outcome are touched, so a sweep run twice marks nothing
  the second time — the sweep is an operator action and operators run things
  twice.

  Timing out is not failing. The instruction stays `released`, because CloFin
  does not know what happened to it, and the item stays un-re-batchable because
  `settlement_item_live_key` counts `timed-out` as live. Treating unknown as
  failed and re-batching is how a payment gets made twice, which is the failure
  this whole module exists to prevent.

  **The horizon is measured from `settlement_batch.created_at`**, and it is
  applied in the statement rather than by reading a clock in the application —
  the domain layer reads no clock, and a horizon evaluated against a time the
  application chose is a horizon two concurrent sweeps could disagree about.
  Created-at rather than a submission time because the schema records no
  `submitted_at`; the two differ by however long a batch sat open, which
  overstates the wait. Named as observation N-2 in 004-REQ rather than papered
  over: the precise fix is a column, and a column is a migration."
  [tx batch-id horizon-seconds]
  (let [rows (db/query tx ["update settlement_batch_item i
                              set outcome = 'timed-out', resolved_at = now()
                             from settlement_batch b
                            where b.id = i.batch_id
                              and i.batch_id = ?
                              and i.outcome is null
                              and b.created_at <= now() - make_interval(secs => ?)
                            returning i.instruction_id"
                           batch-id (double (max 0 (or horizon-seconds 0)))])]
    (mapv :instruction-id rows)))

;; ---------------------------------------------------------------------------
;; Scheme responses
;; ---------------------------------------------------------------------------

(defn record-response!
  "Store a scheme response verbatim. Returns the row, or **nil when it is a
  duplicate**.

  The unique key `(batch_id, instruction_id, kind, reference)` — declared
  `nulls not distinct`, so two batch-level acks collide rather than coexisting —
  is what makes a duplicate delivery detectable. Catching the violation and
  returning nil, rather than letting it propagate, is deliberate: a scheme
  answering twice is the *normal* case in the world this simulates, not an
  error, and the caller's correct response is to do no work and return the same
  answer as the first time (AC-5).

  The first row stays and the second is discarded. That is the same posture
  `idempotency_key` takes, and for the same reason: what is worth keeping is the
  evidence that a duplicate arrived and was refused work.

  **Inside a savepoint**, and that is load-bearing rather than defensive.
  PostgreSQL aborts the whole transaction on a constraint violation, so merely
  catching the duplicate-key error would leave the caller in a transaction where
  the very next read fails — which is what happened before
  `clofin.db.core/tolerating-violation` existed, and it surfaced as a `500` on
  the duplicate path rather than as the `200` a repeated scheme response
  deserves."
  [tx {:keys [id batch-id instruction-id kind reference]}]
  (db/tolerating-violation
   tx
   (fn [conn]
     (row->response
      (db/insert-returning!
       conn
       ["insert into scheme_response (id, batch_id, instruction_id, kind, reference)
         values (?, ?, ?, ?, ?)
         returning id, batch_id, instruction_id, kind, reference, received_at"
        id batch-id instruction-id kind reference])))
   (fn [{:keys [sql-state constraint]}]
     (if (and (= sql-state (:unique-violation db/sql-states))
              (= "scheme_response_replay_key" constraint))
       nil
       (err/fail! :conflict "This scheme response could not be recorded"
                  {:constraint constraint :sql-state sql-state})))))

(defn find-response
  "The stored response matching a replay key, or nil.

  Read after a duplicate is detected, so the caller can report *when* the
  original arrived rather than only that this one was a repeat."
  [source {:keys [batch-id instruction-id kind reference]}]
  (row->response
   (db/query-one source
                 ["select id, batch_id, instruction_id, kind, reference, received_at
                     from scheme_response
                    where batch_id = ? and instruction_id is not distinct from ?
                      and kind = ? and reference = ?"
                  batch-id instruction-id kind reference])))
