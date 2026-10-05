(ns clofin.payments.repository
  "Persistence for payment instructions.

  A `repository` namespace is CloFin's persistence seam: it may require
  `clofin.db.*`, and the pure namespaces beside it — `clofin.payments.state`,
  `clofin.payments.instruction`, `clofin.payments.posting` — may not
  (ADR-0012).

  The function that matters here is `transition!`. Two operators submitting the
  same instruction at the same moment must not both succeed: a lifecycle read
  in one statement and written in another is a race, and the window between the
  two is exactly long enough to submit a payment twice. Every state change
  therefore reads its row `for update` inside a transaction, so the second
  caller waits, re-reads the status the first left behind, and is refused by the
  state machine rather than by luck.

  Every function takes a `source` — a pool, or a connection already inside a
  caller's transaction — so the same function composes into a larger unit of
  work without knowing which it was given. In practice every mutating call
  **in this namespace** arrives on a connection owned by
  `clofin.idempotency.repository`, because the state change and the idempotency
  key protecting it commit together or not at all. Payments, approvals and
  screening are the eight operations that take a caller key; the sentence said
  *every mutating call* and was read as covering every one of the route table's
  mutations
  (**2B-009**, standing lesson **L-14**).

  ## Lock order

  Two row types are locked here, and **always in this sequence**:

  1. `payment_instruction` — by `lock-instruction!`, `assert-reversal-target!`
     and `assert-retry-target!`
  2. `ledger_account` — by `assert-debtor-account!`, and by
     `clofin.ledger.repository/assert-postable!`, which orders by id within
     itself

  The two link targets are the same row type, so a creation naming both would
  take two locks of one type in an order this namespace does not fix. It cannot:
  `create-instruction!` refuses an instruction that claims to be a reversal
  *and* a retry, which is a rule about meaning first — a payment succeeds a
  settled one or replaces a returned one, never both — and removes the ordering
  question as a consequence rather than as its reason.

  A single order across every caller is what stops two transactions that touch
  the same rows from deadlocking. It is written down because it is invisible at
  each call site individually: `create-instruction!` used to check the debtor
  account before the reversal target, which was harmless only while neither
  took a lock. Audit finding **F-004** added the locks; this ordering is the
  half of that fix that is easy to miss and expensive to rediscover."
  (:require [clofin.authz.repository :as authz]
            [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.money :as money]
            [clofin.payments.instruction :as instruction]
            [clofin.payments.state :as state]
            ;; Pure, both: the screening subject's digest and C-07's judgement.
            ;; Neither requires anything from payments, so this is no cycle;
            ;; `clofin.screening.service` requires this namespace, not the
            ;; reverse (ARCHITECTURE.md §3).
            [clofin.screening.decision :as decision]
            [clofin.screening.subject :as subject]))

(def row-cap
  "Maximum rows a list query returns.

  The same cap and the same reasoning as the ledger's: real pagination waits
  for a consumer that needs it, and until then a hard cap with an explicit
  `truncated` flag is the smallest thing that is not misleading. See ADR-0011."
  500)

;; ---------------------------------------------------------------------------
;; Rows to domain values
;; ---------------------------------------------------------------------------

(def ^:private retried-by-column
  "The other end of the retry link, read at the same time as the row itself.

  A retry names its original in a column; the original names its retries in
  **no** column, because the relation is stored once (ADR-0024). Deriving the
  reverse side here is what makes the linkage visible from both ends without a
  second copy of it that could disagree with the first — the same reasoning that
  keeps a balance an aggregation over journal lines rather than a column
  (ADR-0008).

  Ordered by creation so the list is stable between reads, and scoped to the
  organisation as well as to the id: the foreign key already confines a retry to
  a real instruction, and the scope confines the *answer* to one tenant even if
  a future writer forgets.

  Aggregated rather than joined. The normal case is one retry and the join would
  read identically — but an original whose first retry was cancelled and
  replaced has two, and a scalar subquery that met the second one would fail the
  read rather than report it (ADR-0024 explains why that cardinality is not
  constrained)."
  "(select coalesce(array_agg(r.id order by r.created_at, r.id), '{}'::uuid[])
      from payment_instruction r
     where r.retries_id = payment_instruction.id
       and r.organisation_id = payment_instruction.organisation_id) as retried_by_ids")

(def ^:private instruction-columns
  (str "select id, organisation_id, debtor_account_id, creditor_name, creditor_account,
               amount_minor, currency, value_date, purpose_code, status,
               created_by, created_at, reverses_id, retries_id,
               client_reference, creditor_country, "
       retried-by-column
       " from payment_instruction "))

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
     :created-by        (:created-by row)
     :created-at        (db/->instant (:created-at row))
     :reverses-id       (:reverses-id row)
     ;; The link this instruction carries: the returned instruction it replaces.
     :retries-id        (:retries-id row)
     ;; The link others carry to it, derived. Empty for almost every
     ;; instruction, which is why it is a vector and never nil.
     :retried-by-ids    (db/->uuids (:retried-by-ids row))
     ;; Optional, both (ADR-0028 D6). The reference exactly as the client sent
     ;; it; the country as the two letters `payment_creditor_country_shape`
     ;; admits, so `char(2)` has no padding to strip.
     :client-reference  (:client-reference row)
     :creditor-country  (:creditor-country row)}))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn find-instruction
  "The instruction with this id **within this organisation**, or nil.

  Scoping by organisation is not a convenience: an unscoped lookup is how one
  tenant reads another's payments."
  [source organisation-id id]
  (row->instruction
   (db/query-one source [(str instruction-columns "where organisation_id = ? and id = ?")
                         organisation-id id])))

(defn find-by-client-reference
  "The instruction this organisation holds under `client-reference`, or nil.

  At most one, by `payment_instruction_client_reference_key`. Read **without a
  lock**: it decides the common case of a repeated reference cheaply, and the
  unique index — not this read — is what stops a second instruction when two
  creations race (`create-instruction!`). Scoped by organisation, because the
  same reference in another organisation is a different reference."
  [source organisation-id client-reference]
  (row->instruction
   (db/query-one source [(str instruction-columns
                              "where organisation_id = ? and client_reference = ?")
                         organisation-id client-reference])))

(defn list-instructions
  "An organisation's instructions, most recently created first, capped at
  `row-cap`.

  Ordered by creation and then by id — a total order, so the same query over
  unchanged data returns the same page every time. `status`, when given, filters
  to one lifecycle state.

  Returns `{:instructions [...] :truncated? bool}`; one row beyond the cap is
  read purely to learn whether there were more."
  [source organisation-id {:keys [status]}]
  (when (and status (not (state/known? status)))
    (err/invalid! (str "Unknown payment instruction status: " (name status))
                  {:status (name status) :known (mapv name state/states)}))
  (let [rows (db/query source
                       (if status
                         [(str instruction-columns
                               "where organisation_id = ? and status = ?
                                 order by created_at desc, id limit ?")
                          organisation-id (name status) (inc row-cap)]
                         [(str instruction-columns
                               "where organisation_id = ?
                                 order by created_at desc, id limit ?")
                          organisation-id (inc row-cap)]))]
    {:instructions (mapv row->instruction (take row-cap rows))
     :truncated?   (> (count rows) row-cap)}))

(defn lock-instruction!
  "Read an instruction `for update`, or `404`.

  `for update` is what makes a read-then-write safe: the row is held until this
  transaction ends, so a concurrent caller blocks here rather than deciding
  against a status that is about to change underneath it. Outside a transaction
  the lock would be released at the next statement and would guarantee nothing,
  which is why every caller of this arrives on a connection that owns one.

  Public because `clofin.payments.approval-service` needs the same lock for the
  same reason: an approval decided against a status that changed underneath it
  is an approval given to a payment nobody submitted."
  [tx organisation-id id]
  (or (row->instruction
       (db/query-one tx [(str instruction-columns
                              "where organisation_id = ? and id = ? for update")
                         organisation-id id]))
      (err/not-found! "No such payment instruction in this organisation"
                      {:id (str id)})))

;; ---------------------------------------------------------------------------
;; Writing
;; ---------------------------------------------------------------------------

(defn- insert!
  "Write the instruction and return it carrying the creation instant.

  `created_at` comes back from the database rather than from a clock read in
  the application: the domain layer reads no clock, and the row's own timestamp
  is the one an investigation will be looking at."
  [tx instruction]
  (let [row (db/insert-returning!
             tx
             ["insert into payment_instruction
                 (id, organisation_id, debtor_account_id, creditor_name,
                  creditor_account, amount_minor, currency, value_date,
                  purpose_code, status, created_by, reverses_id, retries_id,
                  client_reference, creditor_country)
               values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               returning created_at"
              (:id instruction) (:organisation-id instruction)
              (:debtor-account-id instruction) (:creditor-name instruction)
              (:creditor-account instruction)
              (:minor-units (:amount instruction)) (:currency (:amount instruction))
              (:value-date instruction) (:purpose-code instruction)
              (name (:status instruction)) (:created-by instruction)
              (:reverses-id instruction) (:retries-id instruction)
              (:client-reference instruction) (:creditor-country instruction)])]
    ;; A brand new instruction is retried by nothing, and says so as an empty
    ;; vector rather than by omitting the key — the shape a row read back has.
    (assoc instruction
           :created-at     (db/->instant (:created-at row))
           :retried-by-ids [])))

(defn- assert-debtor-account!
  "The debtor account must exist in this organisation, accept postings, and
  hold the instruction's currency.

  None of these is a property of the instruction value alone, so none can be
  checked in the pure layer — it cannot see an account it was not handed
  (ADR-0012). Checking them inside the transaction is what makes the check
  mean anything: an account frozen concurrently is either visible here or is
  not, and either way the outcome is consistent.

  An account belonging to another organisation is reported as unknown rather
  than forbidden. Saying \"forbidden\" would confirm that the id exists and
  belongs to someone else — a tenancy disclosure available to anyone able to
  guess a UUID.

  **Locked `for update`**, for the reason
  `clofin.ledger.repository/assert-postable!` sets out at length: a status read
  without a lock is a check that can stop being true before the write it gates
  (audit finding **F-004**, lesson **L-8**). One row, so no ordering is needed
  *within* this call — but the order *between* row types is, and it is stated
  in this namespace's docstring: payment instructions first, accounts second."
  [tx {:keys [organisation-id debtor-account-id amount]}]
  (let [row (db/query-one tx ["select id, code, currency, status from ledger_account
                                where organisation_id = ? and id = ?
                                for update"
                              organisation-id debtor-account-id])]
    (when-not row
      (err/fail! :unprocessable
                 "The debtor account does not exist in this organisation"
                 {:debtor-account-id (str debtor-account-id)}))
    (when-not (= "active" (:status row))
      (err/fail! :unprocessable
                 "The debtor account does not accept postings"
                 {:debtor-account-id (str debtor-account-id)
                  :code (:code row)
                  :status (:status row)}))
    (when-not (= (:currency row) (:currency amount))
      (err/fail! :unprocessable
                 "The instruction's currency does not match the debtor account"
                 {:debtor-account-id (str debtor-account-id)
                  :code (:code row)
                  :account-currency (:currency row)
                  :instruction-currency (:currency amount)}))
    row))

(defn- assert-reversal-target!
  "A reversal must name a settled instruction in the same organisation and the
  same currency.

  `DOMAIN_MODEL.md` §3 rule 4: a settled payment is never mutated; it is
  followed by a *new* instruction that points back at it. That the original may
  be reversed at all is `clofin.payments.state/assert-reversible!`'s rule, held
  as data with every other rule about status (ADR-0014).

  Read `for update` so the original cannot leave `settled` between this check
  and the insert — it cannot today, `settled` being terminal, but a reversal
  raised against a state that changed underneath it is the kind of thing a
  later increment introduces silently."
  [tx {:keys [organisation-id amount reverses-id]}]
  (let [original (or (row->instruction
                      (db/query-one tx [(str instruction-columns
                                             "where organisation_id = ? and id = ? for update")
                                        organisation-id reverses-id]))
                     (err/fail! :unprocessable
                                "The instruction being reversed does not exist in this organisation"
                                {:reverses-id (str reverses-id)}))]
    (state/assert-reversible! (:status original))
    (when-not (= (:currency (:amount original)) (:currency amount))
      (err/fail! :unprocessable
                 "A reversal must be in the same currency as the instruction it reverses"
                 {:reverses-id (str reverses-id)
                  :original-currency (:currency (:amount original))
                  :reversal-currency (:currency amount)}))
    original))

(defn- assert-retry-target!
  "A retry must name a **returned** instruction in the same organisation.

  [ADR-0019](../../../docs/ADR/0019-a-returned-payment-is-terminal-and-retries-as-a-new-instruction.md)
  ruled that `returned` is terminal and that a second attempt is a *new*
  instruction, raised, submitted and approved on its own merits — and named the
  cost it accepted: nothing in the record related the retry to the payment it
  replaced. This is the check behind that reference.

  Two rules and no more, which is the whole of ADR-0024's decision:

  - **The target is `returned`.** Not `settled` (that is a reversal), not
    `failed`, not still in flight. Read from
    `clofin.payments.state/retryable-states`, so the answer lives beside every
    other rule about status rather than as an `=` written here.
  - **The target is in this organisation.** An instruction in another
    organisation is reported as *not existing*, exactly as
    `assert-debtor-account!` reports a foreign account: saying \"another
    organisation's\" would confirm that a guessed UUID names a real payment
    somewhere else, which is a tenancy disclosure (C-08).

  Deliberately no rule about the amount, the currency, the beneficiary or the
  value date. A return is **new information** — a closed account, a rejected
  beneficiary — and correcting one of those is the ordinary reason to retry; a
  link that only accepted an identical payment would refuse exactly the retries
  that matter. ADR-0024 states that rather than leaving it to be inferred from
  the absence of a check.

  Read `for update` for the reason `assert-reversal-target!` gives: a link
  raised against a state that changed underneath it is validate-then-write, and
  that is a race (standing lesson **L-8**). `returned` is terminal today, so the
  status cannot move; the lock is what keeps that true if a later increment
  gives it an arrow."
  [tx {:keys [organisation-id retries-id]}]
  (let [original (or (row->instruction
                      (db/query-one tx [(str instruction-columns
                                             "where organisation_id = ? and id = ? for update")
                                        organisation-id retries-id]))
                     (err/fail! :unprocessable
                                "The instruction being retried does not exist in this organisation"
                                {:retries-id (str retries-id)}))]
    (state/assert-retryable! (:status original))
    original))

(defn- assert-one-linkage!
  "An instruction succeeds a settled payment or replaces a returned one, never
  both.

  A reversal and a retry are opposite statements about opposite terminal
  outcomes — one says *that payment happened and is being undone*, the other
  says *that payment did not happen and is being attempted again* — so a record
  claiming both is a record that means nothing. Refused as a field failure
  naming both members rather than silently preferring one, because a caller that
  sent both has a bug and needs to be told which half to remove."
  [{:keys [reverses-id retries-id]}]
  (when (and reverses-id retries-id)
    (err/fail! :field-validation "Request failed validation"
               (array-map :retries-id  "cannot be set on a reversal"
                          :reverses-id "cannot be set on a retry"))))

(def ^:private client-reference-constraint
  "The partial unique index that makes a client reference name at most one
  instruction per organisation (migration `0014`). Named, as
  `clofin.idempotency.repository` names its own key, so that a unique violation
  raised by anything else is rethrown as the defect it is rather than being
  answered as a reference conflict."
  "payment_instruction_client_reference_key")

(def client-reference-refusal-reasons
  "The `errors.reason` codes a creation may be refused under for its
  `clientReference`, published as `ClientReferenceRefusalReason`
  (`clofin.contract-test` compares the two in both directions)."
  #{"client-reference-conflict" "client-reference-exists"})

(defn client-reference-refusal
  "The `409` for a `clientReference` this organisation already holds, decided
  against `existing` **as it is now** by `clofin.payments.instruction/same-content?`
  — the one function the serial path and a race's loser both ask (L-18).

  Two reasons, and nothing is persisted under either (ADR-0028 D6):

  - `client-reference-exists` — identical content under a different key. The
    request is the instruction the client already has; it is told which one.
  - `client-reference-conflict` — different content. The reference names a
    payment that is not this request, so core will not create a second one and
    will not pretend the request was accepted.

  `errors.instructionId` names the existing instruction in both: a client that
  lost its key resolves it by the reference, which is the point of having one.
  Public because `clofin.api.payments` decides the race's loser with it after
  the losing transaction has rolled back."
  [candidate existing]
  (let [same? (instruction/same-content? candidate existing)]
    (err/conflict!
     (if same?
       (str "This clientReference already names payment instruction " (:id existing)
            ", created with identical content under a different Idempotency-Key. "
            "Nothing was created; read that instruction.")
       (str "This clientReference already names payment instruction " (:id existing)
            ", whose content differs from this request. Nothing was created; "
            "read that instruction, or send this payment under a reference of its own."))
     {:reason        (client-reference-refusal-reasons
                      (if same? "client-reference-exists" "client-reference-conflict"))
      :instructionId (str (:id existing))})))

(defn create-instruction!
  "Persist a new instruction in `draft`. Returns it as stored.

  A caller cannot choose the status: an instruction that arrived already
  approved would be an approval nobody gave. `candidate` carries the id, the
  creating actor and — when this is a reversal — the settled instruction being
  reversed, or — when it is a retry — the returned instruction it replaces.

  **A `client-reference` names at most one instruction per organisation**
  (ADR-0028 D6). Checked first inside the transaction — after the idempotency
  key, which the caller has already claimed, and before every other database
  rule — by `find-by-client-reference`, which answers the common case with
  `client-reference-refusal`. Two creations racing on one reference both pass
  that read; the second insert then fails on
  `payment_instruction_client_reference_key`, its transaction is aborted, and
  this throws a `:conflict` marked `:clofin/client-reference-race` instead of
  deciding anything: PostgreSQL refuses every statement on an aborted
  transaction, so the loser's answer is decided by its caller **after** the
  rollback, by reading the winner's committed row."
  [source candidate opts]
  (let [drafted (instruction/draft candidate opts)]
    (assert-one-linkage! drafted)
    (db/transactionally
     source
     (fn [tx]
       ;; The reference before any lock. No row is locked by this read, so the
       ;; lock order below is untouched; a repeated reference is refused without
       ;; taking the link target's or the debtor account's lock for nothing.
       (when-let [reference (:client-reference drafted)]
         (when-let [existing (find-by-client-reference tx (:organisation-id drafted) reference)]
           (client-reference-refusal drafted existing)))
       ;; Link target first, debtor account second — the lock order this
       ;; namespace's docstring fixes. `assert-reversal-target!` and
       ;; `assert-retry-target!` lock a `payment_instruction` row and
       ;; `assert-debtor-account!` a `ledger_account` row; `amend!` takes the
       ;; same two in the same sequence. Taken in opposite orders by two
       ;; concurrent callers, these deadlock — which is why F-004's `for update`
       ;; could not simply be added without also fixing the order here.
       ;;
       ;; At most one of the two runs: `assert-one-linkage!` above has already
       ;; refused an instruction claiming to be both, so this never takes two
       ;; locks of one row type in an unfixed order.
       (when (:reverses-id drafted)
         (assert-reversal-target! tx drafted))
       (when (:retries-id drafted)
         (assert-retry-target! tx drafted))
       (assert-debtor-account! tx drafted)
       (try
         (insert! tx drafted)
         (catch Exception t
           (let [{:keys [sql-state constraint]} (db/violation t)]
             (cond
               (= sql-state (:foreign-key-violation db/sql-states))
               (err/fail! :unprocessable
                          "The instruction references a record that does not exist"
                          (err/internal {:constraint constraint}))

               ;; Lost a race on the reference. Nothing can be read on this
               ;; connection any more, so nothing is decided here: the marker
               ;; travels up through the rollback to a caller that can read the
               ;; winner's committed row (`clofin.api.payments/create`). The
               ;; message stands on its own in case no caller resolves it.
               (and (= sql-state (:unique-violation db/sql-states))
                    (= client-reference-constraint constraint))
               (err/conflict!
                "This clientReference was taken by a concurrent creation; nothing was created"
                (err/internal {:client-reference-race (:client-reference drafted)}))

               :else (throw t)))))))))

(defn- assert-creator!
  "Only an instruction's creator may perform `verb` on it.

  A real access control rather than a check that a caller copied a UUID
  correctly: `actor` is the authenticated principal, and `created-by` is the
  principal that created the instruction. Before there was a principal this
  comparison would have compared two caller-asserted values and looked, from
  outside, exactly like an access control while being none — which is why
  TASK-002 left it undone and said so.

  Used for two operations, for two different reasons:

  - **amend** (PR-004) — a draft belongs to whoever raised it.
  - **submit** ([C-01], audit finding **F-001**) — this one is load-bearing for
    a *control*. `clofin.authz.approval/evaluate` refuses an approval by the
    instruction's `created-by` actor and compares nothing else, so maker–checker
    holds only while the submitter and the creator are the same actor. Until
    this check existed, they need not have been.

  `403` rather than `404`: the caller has been told this instruction exists —
  they are inside the organisation that owns it and addressed it by id — so
  hiding behind a `404` would only obscure the reason without concealing
  anything. `401` when there is no actor at all, and it is deliberately not
  possible to reach this with an absent actor and be permitted: an operation
  restricted to the creator with nobody to compare against fails closed.

  [C-01]: docs/COMPLIANCE.md"
  [existing actor verb]
  (when-not (:id actor)
    (err/fail! :unauthorised
               (str "Performing '" verb "' on a payment instruction requires an actor")
               {:attempted verb}))
  (when-not (= (:id actor) (:created-by existing))
    (err/forbidden!
     (str "Only the actor who created a payment instruction may " verb " it")
     {:instruction-id (str (:id existing))
      :attempted      verb
      ;; Named so a caller can tell this apart from a missing permission: the
      ;; answer is not "ask for a role", it is "this is not your instruction".
      :rule           "creator-only"}))
  existing)

(defn amend!
  "Apply `changes` to an instruction. Returns `{:before … :after … :approvals-invalidated n}`.

  Two paths, and which one applies is decided by the lifecycle table rather
  than by an `if` about status written here (ADR-0014):

  - **The instruction is in a `mutable-state`** — `draft`. The substance is
    edited in place and the status does not move. This is what
    `DOMAIN_MODEL.md` §1 means by \"mutable while `draft`\".
  - **The lifecycle permits `:amend` from its status** — `pending-approval` or
    `approved`. Every approval given so far is invalidated and the instruction
    returns to `draft` (`DOMAIN_MODEL.md` §3 rule 3, **PR-014**) *before* the
    changes are applied. An approver agreed to the values that were in front of
    them; after an amendment those are not the instruction's values any more,
    so their approval cannot survive it.

  Anything else is a `:conflict` from `state/assert-mutable!`, naming the state.

  Both paths return `:before` and `:after`, because an audited change needs
  both and re-reading the row afterwards to reconstruct the before is a read of
  a value that has already changed.

  The row is locked for the duration, so an amendment and a concurrent
  submission or approval cannot interleave into a submitted instruction
  carrying amended values that no approver will ever see."
  [source organisation-id id changes {:keys [actor] :as opts}]
  (db/transactionally
   source
   (fn [tx]
     (let [existing  (lock-instruction! tx organisation-id id)
           _         (assert-creator! existing actor "amend")
           ;; PR-014. Read from the table, so an amendable state added later is
           ;; covered without this function being told about it.
           reverting? (and (not (state/mutable? (:status existing)))
                           (state/permitted? (:status existing) :amend))
           _         (when-not reverting? (state/assert-mutable! (:status existing)))
           ;; `[{:before … :after …} …]`, one per approval, so the caller can
           ;; write an `approval.invalidated` event for each (F-006). A count
           ;; was all this used to return, and a count cannot be audited.
           invalidated (if reverting?
                         (authz/invalidate-approvals-for! tx id)
                         [])
           reverted  (if reverting?
                       (assoc existing :status (state/transition (:status existing) :amend))
                       existing)
           amended   (instruction/amend reverted changes opts)]
       (assert-debtor-account! tx amended)
       (db/execute! tx
                    ;; `client_reference` is deliberately not in this list: it
                    ;; is not amendable, and the trigger in migration `0014`
                    ;; would refuse it if it were.
                    ["update payment_instruction
                         set debtor_account_id = ?, creditor_name = ?,
                             creditor_account = ?, creditor_country = ?,
                             amount_minor = ?, currency = ?,
                             value_date = ?, purpose_code = ?, status = ?
                       where organisation_id = ? and id = ?"
                     (:debtor-account-id amended) (:creditor-name amended)
                     (:creditor-account amended) (:creditor-country amended)
                     (:minor-units (:amount amended)) (:currency (:amount amended))
                     (:value-date amended) (:purpose-code amended)
                     (name (:status amended))
                     organisation-id id])
       {:before existing
        :after  amended
        ;; The pairs themselves, not just how many. The handler audits each one
        ;; on this same transaction, so an amendment and every invalidation it
        ;; caused commit together or not at all (C-05, PR-075).
        :invalidated-approvals invalidated
        :approvals-invalidated (count invalidated)}))))

(defn assert-may-apply!
  "Refuse `event` on `existing` unless `actor` may cause it and the lifecycle
  permits it. Returns the next state.

  Provenance before the lifecycle, mirroring `amend!` — which asserts the
  creator and only then asks whether the status permits the change. An actor
  with no business touching this instruction is told that, rather than being
  handed its current state and the list of events that would have been
  permitted.

  The opposite order is right in `approval-service`, and deliberately so: an
  `approve` on a settled payment is a `409` whoever sent it, and answering
  `403` first would suggest that fixing permissions would help. Here it would
  not — no grant makes a non-creator the creator.

  Public because `clofin.screening.service/submit-screened!` asks the same two
  questions, under the same lock, **before** it screens: a submission that the
  lifecycle or provenance refuses must not leave a screening decision — or open
  a case — on the strength of a request that was never going to be honoured.
  `transition!` asks them again; the second asking is the one that cannot be
  skipped."
  [existing event actor]
  (when (state/creator-only? event)
    (assert-creator! existing actor (name event)))
  (state/transition (:status existing) event))

(defn- screening-decision-inputs
  "What the gate needs, read under the caller's row lock: the latest **core**
  result over `digest`, with whether its list has since been retired, and the
  latest case for (instruction, digest, that list).

  The SQL is here rather than in `clofin.screening.repository` because it is
  the lifecycle's own precondition, read on the lifecycle's own connection; the
  judgement is not — it is `clofin.screening.decision/decide`'s. Ordered by
  `recorded_at`, which a result is written with as its insert instant under
  this same lock (`clofin.screening.repository/insert-result!`), so \"latest\"
  is the latest decided."
  [tx instruction-id digest]
  (let [r (db/query-one tx ["select r.id, r.origin, r.outcome, r.disposition, r.instruction_digest,
                                    r.list_version, (l.retired_at is not null) as list_retired
                               from screening_result r
                               join screening_list l on l.version = r.list_version
                              where r.instruction_id = ? and r.origin = 'core'
                                and r.instruction_digest = ?
                              order by r.recorded_at desc, r.id desc
                              limit 1"
                            instruction-id digest])
        c (when r
            (db/query-one tx ["select id, status, disposition, instruction_digest, list_version
                                 from screening_case
                                where instruction_id = ? and instruction_digest = ?
                                  and list_version = ?
                                order by opened_at desc, id desc
                                limit 1"
                              instruction-id digest (:list-version r)]))]
    {:digest             digest
     :latest-core-result (when r (assoc r :list-retired? (boolean (:list-retired r))))
     :case               c}))

(defn- assert-screened!
  "C-07's gate: refuse unless core's own decision over the locked row's current
  content permits the event.

  Re-read and re-decided here, under the lock, even when the caller has just
  screened — `clofin.screening.service/submit-screened!` stores core's result
  and then calls this function, which reads that result back rather than
  trusting a flag the service set. A gate that trusted its caller would be
  documentation (docs/briefs/017, *Notes*). So `transition!` with `:submit`
  and no decision is refused by the repository itself: `screening-required`
  when core holds no decision over this content, or holds one against a
  retired list; `screening-hit` when the hit stands."
  [tx existing]
  (let [inputs (screening-decision-inputs tx (:id existing) (subject/digest existing))
        verdict (decision/decide inputs)]
    (when-not (decision/permits-submit? verdict)
      (err/conflict!
       (if (= "screening-hit" (decision/refusal-reason verdict))
         "Core's screening of this instruction found a hit that has not been dispositioned false-positive"
         "Submission requires core's screening decision over this instruction's current content")
       {:reason   (decision/refusal-reason verdict)
        :decision (name verdict)}))
    verdict))

(defn transition!
  "Apply `event` to an instruction. Returns `{:before … :after …}`.

  Read then written under `for update` inside one transaction, so two
  concurrent submissions cannot both succeed: the second blocks on the row,
  re-reads the status the first committed, and is refused by the state machine.
  Without the lock both would read `draft`, both would find `submit` permitted,
  and both would write — which is a payment submitted twice.

  The next state comes from `clofin.payments.state/transition` and from nowhere
  else. This function never asks what state the instruction is in.

  Both the previous and the next value are returned, because every caller of
  this writes an audit event describing the change and an audit event needs
  both ends of it.

  `opts` carries `:actor`, the authenticated principal. For an event in
  `clofin.payments.state/creator-only-events` — today `:submit` — the actor
  must be the instruction's creator, checked **here**, under the row lock, in
  the same transaction as the state change. Not at the HTTP boundary: a
  provenance rule enforced only in a handler is a rule that stops existing the
  moment anything else calls this function, which is how audit finding F-001
  reached an approved payment moved by one human.

  The check is inside the lock for the same reason the lifecycle check is: an
  instruction whose `created-by` was read outside the lock is provenance read
  from a row that another transaction may be changing.

  **For an event in `clofin.payments.state/screened-events` — `:submit` — core's
  screening decision gates the transition** (C-07,
  docs/briefs/017-TASK-screening-and-cases.md, A-8), checked after provenance
  and the lifecycle and under the same lock: see `assert-screened!`."
  ([source organisation-id id event] (transition! source organisation-id id event {}))
  ([source organisation-id id event {:keys [actor]}]
   (db/transactionally
    source
    (fn [tx]
      (let [existing (lock-instruction! tx organisation-id id)
            next     (assert-may-apply! existing event actor)]
        ;; C-07 (TASK-017): `submit` requires core's completed screening decision
        ;; over this row's current content — a missing decision blocks
        ;; submission rather than queuing behind it (DOMAIN_MODEL §3 rule 1).
        (when (state/screened? event)
          (assert-screened! tx existing))
        (db/execute! tx ["update payment_instruction set status = ?
                           where organisation_id = ? and id = ?"
                         (name next) organisation-id id])
        {:before existing
         :after  (assoc existing :status next)})))))
