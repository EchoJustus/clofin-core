(ns clofin.authz.repository
  "Persistence for actors, roles, limits, thresholds and approvals.

  The persistence seam for the authorisation context (ADR-0012): this namespace
  may require `clofin.db.*`, and `clofin.authz.model` and
  `clofin.authz.approval` beside it may not. Everything here reads or writes
  rows; nothing here decides anything. The decision is
  `clofin.authz.approval/evaluate`, which is pure precisely so that the rule an
  auditor asks about can be replayed without a database.

  Two notes about how approvals are stored, because both are load-bearing.

  **An approval is never deleted.** `approval_no_delete` refuses `DELETE` at the
  database. Withdrawing an approval and invalidating one on amendment both set
  `invalidated_at`; the row stays, and an approval that was given and then
  withdrawn is exactly the history an investigation needs.

  **Every write takes the caller's transaction.** These functions are called
  from inside the unit of work that carries the state change and its audit
  event, so they take a `source` and join whatever they are given — a pool when
  standing alone, a connection when composing."
  (:require [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.money :as money]))

;; ---------------------------------------------------------------------------
;; Actors
;; ---------------------------------------------------------------------------

(defn- limits-for
  "An actor's approval ceilings, keyed by currency.

  A row whose currency is null keys under `nil`, which
  `clofin.authz.approval/limit-for` reads as \"every currency\". Migration `0005`
  made such a row uninsertable by declaring a primary key over a nullable
  column; `0006` corrected it with `unique nulls not distinct`, which also holds
  an actor to at most one wildcard row. This map handled the case throughout,
  which is why the fix needed no change here — see objection O-1 in the REQ."
  [source actor-id]
  (into {}
        (map (fn [row] [(:currency row) (db/->long (:limit-minor row))]))
        (db/query source ["select currency, limit_minor from approver_limit where actor_id = ?"
                          actor-id])))

(defn- roles-for
  [source actor-id]
  (into #{}
        (map (comp keyword :role))
        (db/query source ["select role from actor_role where actor_id = ? order by role"
                          actor-id])))

(defn find-actor
  "The actor with this id, with their roles and limits, or nil.

  Assembled in three queries rather than one join because the shape wanted is a
  value with a set and a map inside it, and reassembling that from a product of
  rows is where an actor with two roles and three limits quietly becomes six of
  something. Actors are looked up once per request, so the cost is a
  non-question.

  **Unscoped by organisation on purpose** — this is the lookup that *establishes*
  which organisation the caller is acting for, so scoping it by the answer would
  be circular. Every lookup downstream of it is scoped."
  [source id]
  (when-let [row (db/query-one source
                               ["select id, organisation_id, display_name, status
                                   from actor where id = ?"
                                id])]
    {:id              (:id row)
     :organisation-id (:organisation-id row)
     :display-name    (:display-name row)
     :status          (keyword (:status row))
     :roles           (roles-for source id)
     :limits          (limits-for source id)}))

;; ---------------------------------------------------------------------------
;; Thresholds
;; ---------------------------------------------------------------------------

(defn thresholds-for
  "An organisation's approval bands for one currency, ascending.

  Per currency, never converted — see
  docs/ADR/0015-approval-thresholds-are-per-currency.md, which resolves PRD Q1.
  An empty result is a real answer and not an error: the organisation has not
  configured this currency, and `clofin.authz.approval/evaluate` refuses with
  `:no-threshold-configured` rather than inventing a requirement."
  [source organisation-id currency]
  (mapv (fn [row] {:from-minor         (db/->long (:from-minor row))
                   :approvals-required (int (:approvals-required row))})
        (db/query source
                  ["select from_minor, approvals_required
                      from approval_threshold
                     where organisation_id = ? and currency = ?
                     order by from_minor"
                   organisation-id currency])))

;; ---------------------------------------------------------------------------
;; Approvals
;; ---------------------------------------------------------------------------

(def ^:private approval-columns
  "select id, instruction_id, actor_id, decision, reason, decided_at, invalidated_at
     from approval ")

(defn- row->approval
  [row]
  (when row
    {:id             (:id row)
     :instruction-id (:instruction-id row)
     :actor-id       (:actor-id row)
     :decision       (keyword (:decision row))
     :reason         (:reason row)
     :decided-at     (db/->instant (:decided-at row))
     :invalidated-at (db/->instant (:invalidated-at row))}))

(defn approvals-for
  "Every approval recorded against an instruction, oldest first — **including
  invalidated ones**.

  The caller filters. `clofin.authz.approval/live-approvals` decides what still
  counts, and a repository that pre-filtered would be a second copy of that
  rule: the queue needs the live ones, the evidence pack needs all of them, and
  a query that only ever returned the live ones would make the second question
  unanswerable."
  [source instruction-id]
  (mapv row->approval
        (db/query source [(str approval-columns
                               "where instruction_id = ? order by decided_at, id")
                          instruction-id])))

(defn approvals-for-instructions
  "Approvals for many instructions at once, keyed by instruction id.

  The approval queue reads a page of instructions and needs each one's prior
  approvals (PR-015, AC-13). One query rather than one per row: a queue that
  degrades with its own length is a queue an approver stops using, and the
  approver who stops reading context is the control failure the PRD opens with."
  [source instruction-ids]
  (if (empty? instruction-ids)
    {}
    (->> (db/query source
                   (into [(str approval-columns
                               "where instruction_id in (" (db/placeholders (count instruction-ids)) ")"
                               " order by decided_at, id")]
                         instruction-ids))
         (map row->approval)
         (group-by :instruction-id))))

(defn find-approval
  "One approval by id, or nil."
  [source id]
  (row->approval (db/query-one source [(str approval-columns "where id = ?") id])))

(defn record-approval!
  "Write one approval decision. Returns it as stored.

  The partial unique index `approval_actor_live_key` is the guarantee that an
  actor cannot hold two live approvals on one instruction, not the
  `:already-approved` check in `evaluate`. That check is an optimisation and a
  better error message; delete it and the guarantee is unchanged. Two concurrent
  approvals by one actor contend on the index, and the loser is refused by the
  database rather than by luck — a read-then-write in application code is a
  race, and here a race is an approval counted twice toward a threshold."
  [source {:keys [id instruction-id actor-id decision reason]}]
  (when-not (#{:approved :rejected} decision)
    (err/invalid! (str "Unknown approval decision: " decision)
                  {:decision (str decision) :known ["approved" "rejected"]}))
  (try
    (let [row (db/insert-returning!
               source
               ["insert into approval (id, instruction_id, actor_id, decision, reason)
                 values (?, ?, ?, ?, ?)
                 returning decided_at"
                id instruction-id actor-id (name decision) reason])]
      {:id id :instruction-id instruction-id :actor-id actor-id
       :decision decision :reason reason
       :decided-at (db/->instant (:decided-at row)) :invalidated-at nil})
    (catch Exception t
      (let [{:keys [sql-state constraint]} (db/violation t)]
        (if (= sql-state (:unique-violation db/sql-states))
          (err/conflict! "This actor already holds a live decision on this instruction"
                         {:instruction-id (str instruction-id)
                          :constraint constraint})
          (throw t))))))

(defn invalidate-approval!
  "Invalidate one approval. Returns the number of rows affected.

  `where invalidated_at is null` so that invalidating twice is a no-op rather
  than a rewrite of when it happened — the first invalidation is the one that
  is true."
  [source id]
  (db/execute! source
               ["update approval set invalidated_at = now()
                  where id = ? and invalidated_at is null"
                id]))

(defn invalidate-approvals-for!
  "Invalidate every live approval on an instruction (PR-014). Returns the count.

  Called when an instruction is amended. The approvals stay in the table and
  stop counting: an approver agreed to *those* values, and after an amendment
  those are not the values on the instruction any more."
  [source instruction-id]
  (db/execute! source
               ["update approval set invalidated_at = now()
                  where instruction_id = ? and invalidated_at is null"
                instruction-id]))

;; ---------------------------------------------------------------------------
;; Seeding
;; ---------------------------------------------------------------------------
;;
;; There is deliberately no endpoint that creates an actor, grants a role or
;; sets a limit. Identity-provider integration is out of scope for this
;; increment, and a self-service "make me an approver" API would make C-01
;; unenforceable regardless of how carefully `evaluate` is written. These
;; functions exist so that a fixture, a seed script or a UAT script can set an
;; organisation up explicitly — which is also what makes the fixture readable
;; as documentation of what a role can do.

(defn create-actor!
  "Seed an actor. Returns it as stored, with no roles and no limits.

  Default deny starts here: a newly seeded actor can do nothing at all until a
  role is granted, and there is no role that grants everything."
  [source {:keys [id organisation-id display-name status] :or {status :active}}]
  (db/execute! source
               ["insert into actor (id, organisation_id, display_name, status)
                 values (?, ?, ?, ?)"
                id organisation-id display-name (name status)])
  {:id id :organisation-id organisation-id :display-name display-name
   :status status :roles #{} :limits {}})

(defn grant-role!
  "Grant one role to an actor. Idempotent."
  [source actor-id role]
  (db/execute! source
               ["insert into actor_role (actor_id, role) values (?, ?)
                 on conflict do nothing"
                actor-id (name role)]))

(defn set-limit!
  "Set an actor's approval ceiling for one currency, in minor units."
  [source actor-id currency limit-minor]
  (db/execute! source
               ["insert into approver_limit (actor_id, currency, limit_minor)
                 values (?, ?, ?)
                 on conflict (actor_id, currency) do update set limit_minor = excluded.limit_minor"
                actor-id currency limit-minor]))

(defn set-threshold!
  "Set one approval band for an organisation and currency."
  [source organisation-id currency from-minor approvals-required]
  (db/execute! source
               ["insert into approval_threshold
                   (organisation_id, currency, from_minor, approvals_required)
                 values (?, ?, ?, ?)
                 on conflict (organisation_id, currency, from_minor)
                   do update set approvals_required = excluded.approvals_required"
                organisation-id currency from-minor (int approvals-required)]))

(defn currencies-with-thresholds
  "Every currency this organisation has configured bands for.

  Used to tell an operator *which* currencies are configured when an approval
  is refused for want of a band — the useful half of that refusal is what to
  configure, not merely that something is missing."
  [source organisation-id]
  (mapv :currency
        (db/query source
                  ["select distinct currency from approval_threshold
                     where organisation_id = ? order by currency"
                   organisation-id])))

(defn amount-of
  "The instruction's amount as a domain value. Convenience for callers holding
  a row rather than an instruction."
  [row]
  (money/of (:currency row) (db/->long (:amount-minor row))))
