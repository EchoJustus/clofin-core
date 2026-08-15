(ns clofin.recon.repository
  "Persistence for statements received, matches, breaks and adjustments — and
  the one read that makes reconciliation mean anything: what CloFin's **own
  ledger** says happened.

  A `repository` namespace is CloFin's persistence seam: it may require
  `clofin.db.*`, and the pure namespaces beside it — `clofin.recon.statement`,
  `clofin.recon.matching`, `clofin.recon.break-state`,
  `clofin.recon.adjustment` — may not (ADR-0012).

  ## The expectations come from the journal, and from nowhere else

  `expectations-for` reads `journal_entry` and `journal_line`. It does not read
  `settlement_batch_item`, `scheme_response` or `payment_instruction`, and that
  is the point rather than an accident: the statement CloFin is comparing
  against was produced from those tables by `clofin.settlement.statement`, and
  two records that agree because one was derived from the other agree about
  nothing (standing lesson **L-16**).

  So the two sides reach the same facts by different routes — the amount from a
  posted journal line rather than from the instruction, the date from the
  entry's `occurred_at` rather than from the item's `resolved_at`, and the kind
  of movement from the entry's **counter-account** rather than from the item's
  outcome. A settlement that posted the wrong amount is exactly the defect this
  arrangement can find and a shared derivation could not.

  ## Lock order — read this before adding a function

  Reconciliation adds two row types to the discipline
  `clofin.settlement.repository` documents, and they go **after** it:

  1. `settlement_batch`
  2. `payment_instruction`
  3. `reconciliation_break` — `lock-break!`
  4. `reconciliation_adjustment` — `lock-adjustment!`
  5. `ledger_account` — `clofin.ledger.repository/assert-postable!`, which
     orders by id within itself

  Break before adjustment because every adjustment addresses a break and is
  reached through it; accounts last because posting is the last thing an
  adjustment does. Standing lesson **L-8**: a validation that gates a write
  locks what it validated, so a break's state and an adjustment's status are
  read `for update` by the transaction that changes them."
  (:require [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.money :as money]
            [clofin.recon.adjustment :as adjustment]
            [clofin.recon.break-state :as break-state]
            [clofin.recon.matching :as matching]
            [clofin.recon.statement :as statement]
            [clojure.string :as str])
  (:import [java.time Instant ZoneOffset]))

(def row-cap
  "Maximum rows a list query returns. The same cap and the same reasoning as the
  ledger's, the payments list's and settlement's. See ADR-0011."
  500)

(def expectation-cap
  "Maximum ledger movements one reconciliation run may cover.

  A **refusal** above this, not a truncation. Every other capped read in CloFin
  returns a `truncated` flag and a correct answer over what it returned; a
  reconciliation cannot, because a movement left out of the expectations is not
  a missing row in a list — it becomes a `statement-line-unmatched` break
  against a movement that is right there in the journal. Silently reporting
  breaks for the tail of a period is worse than refusing the period, so the
  statement is received with `too-many-ledger-movements` and the caller is told
  to narrow it."
  500)

;; ---------------------------------------------------------------------------
;; Rows to domain values
;; ---------------------------------------------------------------------------

(def ^:private statement-columns
  "select id, organisation_id, scheme, currency, statement_reference, format,
          format_version, period_start, period_end, content_digest,
          disposition, disposition_reason, reconciled_account_id,
          received_at, received_by
     from reconciliation_statement ")

(defn- row->statement
  [row]
  (when row
    {:id                    (:id row)
     :organisation-id       (:organisation-id row)
     :scheme                (:scheme row)
     :currency              (:currency row)
     :statement-reference   (:statement-reference row)
     :format                (:format row)
     :format-version        (int (:format-version row))
     :period-start          (db/->instant (:period-start row))
     :period-end            (db/->instant (:period-end row))
     :content-digest        (:content-digest row)
     :disposition           (:disposition row)
     :disposition-reason    (:disposition-reason row)
     :reconciled-account-id (:reconciled-account-id row)
     :received-at           (db/->instant (:received-at row))
     :received-by           (:received-by row)}))

(defn- row->line
  [row]
  {:line-no           (int (:line-no row))
   :scheme-reference  (:scheme-reference row)
   :payment-reference (:payment-reference row)
   :line-type         (:line-type row)
   :amount            (money/of (:currency row) (db/->long (:amount-minor row)))
   :value-date        (db/->local-date (:value-date row))})

(defn- row->match
  [row]
  {:line-no    (int (:line-no row))
   :entry-id   (:entry-id row)
   :rule-id    (:rule-id row)
   :matched-at (db/->instant (:matched-at row))})

(defn- amount-or-nil
  [currency minor]
  (when (some? minor) (money/of currency (db/->long minor))))

(defn- row->break
  "A break row as a domain value, or **nil for no row**.

  The nil guard is not decoration. Without it `find-break` returned a map of
  nils for an id that does not exist — truthy, so an `if-let` in a handler
  passed it straight to `clofin.recon.break-state/permitted-events`, which
  refused a nil state and answered `400` where the caller should have had a
  `404`. Every other `row->` function in CloFin's repositories carries the same
  guard; this one is written out because a test caught it missing."
  [row]
  (when row
    {:id               (:id row)
     :organisation-id  (:organisation-id row)
     :statement-id     (:statement-id row)
     :account-id       (:account-id row)
     :kind             (:kind row)
     :state            (keyword (:state row))
     :line-no          (when (some? (:line-no row)) (int (:line-no row)))
     :entry-id         (:entry-id row)
     :currency         (:currency row)
     :statement-amount (amount-or-nil (:currency row) (:statement-amount-minor row))
     :ledger-amount    (amount-or-nil (:currency row) (:ledger-amount-minor row))
     :detail           (:detail row)
     :assignee-id      (:assignee-id row)
     :opened-at        (db/->instant (:opened-at row))
     :resolved-at      (db/->instant (:resolved-at row))
     ;; **Derived, never stored** (see migration `0012`'s column comment). The
     ;; age is computed in the same statement that reads the row, from `now()`
     ;; and `opened_at`, so a break's age is a fact about when it is read rather
     ;; than a column that was true once.
     :age-seconds      (when (some? (:age-seconds row)) (db/->long (:age-seconds row)))
     ;; Derived too, and for the same reason: which payment this break is about
     ;; is answerable from the sides the break already carries, and storing it
     ;; would be a fourth copy that could disagree with the other three.
     :instruction-id   (:instruction-id row)
     ;; The retries of that payment, when it has any. A break on a returned
     ;; original names the retry (ADR-0019, ADR-0024) — the linkage is stored
     ;; once, on the retry, and read from here.
     :retried-by-ids   (db/->uuids (:retried-by-ids row))}))

(defn- row->adjustment
  "An adjustment row as a domain value, or nil for no row — see `row->break`."
  [row]
  (when row
    {:id                 (:id row)
     :organisation-id    (:organisation-id row)
     :break-id           (:break-id row)
     :amount             (money/of (:currency row) (db/->long (:amount-minor row)))
     :direction          (keyword (:direction row))
     :narrative          (:narrative row)
     ;; A keyword, as every other lifecycle value in CloFin is. It was a string
     ;; while the adjustment's \"lifecycle\" was a set of two names; it is read
     ;; against `clofin.recon.adjustment/transitions` now, and a table keyed by
     ;; keywords addressed with strings is a lookup that silently returns nil.
     :status             (keyword (:status row))
     :approvals-required (int (:approvals-required row))
     :entry-id           (:entry-id row)
     :created-by         (:created-by row)
     :created-at         (db/->instant (:created-at row))
     :posted-at          (db/->instant (:posted-at row))}))

;; ---------------------------------------------------------------------------
;; What CloFin's ledger says happened
;; ---------------------------------------------------------------------------

(def finality-counter-line-types
  "Which movement a credit on the reconciled account was, read from the
  **counter-account** of the entry that posted it.

  This is CloFin's independent answer to the question the statement's
  `lineType` also answers, and it is readable from the journal alone because
  ADR-0018 gave the two finality templates different counter-accounts:
  settlement extinguishes the client's payable, a return puts the money back in
  the pooled client-funds asset. Deriving the kind of movement from the
  accounting rather than from the settlement tables is what makes a
  `line-type-mismatch` a real disagreement between two records instead of a
  comparison of one record with itself.

  A counter-account outside this map yields nil — CloFin does not know what the
  movement was — and `clofin.recon.matching` treats nil as agreeing with
  anything rather than asserting a mismatch out of an absence (**L-14**)."
  {"2100-CLIENT-PAYABLE" "settlement"
   "1100-CLIENT-FUNDS"   "return"})

(def ^:private expectations-sql
  "The movements CloFin's own books record on the reconciled account.

  Credits only, and only entries raised against a payment instruction. Both
  narrowings are decisions rather than filters of convenience:

  - **Credits** are the finality leg. A release *debits* the clearing account —
    that is CloFin handing money to the scheme, not the scheme reporting back —
    and a statement that listed releases would be the scheme telling CloFin what
    CloFin had told it.
  - **`payment-instruction` references only.** An adjustment posts to this same
    account and is CloFin's record of a reconciliation decision, not a movement
    any scheme reports; including it would make every resolved break reappear as
    an unmatched expectation the next time the period was reconciled.

  Amounts are summed per entry so an entry touching the account more than once
  yields one expectation rather than two competing for the same match — the
  templates never do, and a query that quietly depended on that would be a
  guarantee resting on a coincidence."
  "select e.id as entry_id,
          e.occurred_at as occurred_at,
          e.reference_id as reference_id,
          sum(l.amount_minor) as amount_minor,
          l.currency as currency,
          (select min(a.code)
             from journal_line cl
             join ledger_account a on a.id = cl.account_id
            where cl.entry_id = e.id and cl.account_id <> l.account_id) as counter_code
     from journal_line l
     join journal_entry e on e.id = l.entry_id
    where e.organisation_id = ?
      and l.account_id = ?
      and l.currency = ?
      and l.direction = 'credit'
      and e.reference_type = 'payment-instruction'
      and e.occurred_at >= ?
      and e.occurred_at < ?
    group by e.id, e.occurred_at, e.reference_id, l.currency, l.account_id
    order by e.id
    limit ?")

(defn expectations-for
  "Every movement CloFin's ledger records on `account` in `[from, to)`, as
  values `clofin.recon.matching` can compare against statement lines.

  Returns `{:expectations [...] :truncated? bool}`. One row beyond
  `expectation-cap` is read purely to learn whether there were more — and unlike
  every other capped read in CloFin the caller **refuses** rather than reporting
  a partial answer; see `expectation-cap`.

  Each expectation carries the same four facts a statement line does, so the
  matcher compares like with like and neither side had to know how the other was
  produced:

      {:entry-id … :payment-reference … :amount … :value-date … :line-type …}

  The value date is the **UTC calendar day** of the entry's `occurred_at`. The
  scheme dates the same movement by the UTC day of the instant *it* recorded, so
  the two agree except for a settlement transaction that spanned UTC midnight —
  where a `value-date-mismatch` break is the correct answer rather than a
  defect. ADR-0023 states that consequence rather than leaving it to be found."
  [source organisation-id account {:keys [from to]}]
  (let [rows (db/query source [expectations-sql
                               organisation-id (:id account) (:currency account)
                               from to (inc expectation-cap)])]
    {:truncated? (> (count rows) expectation-cap)
     :expectations
     (mapv (fn [row]
             {:entry-id          (:entry-id row)
              :payment-reference (:reference-id row)
              :amount            (money/of (:currency row) (db/->long (:amount-minor row)))
              :value-date        (.toLocalDate
                                  (.atZone ^Instant (db/->instant (:occurred-at row))
                                           ZoneOffset/UTC))
              :counter-account-code (:counter-code row)
              :line-type         (get finality-counter-line-types (:counter-code row))})
           (take expectation-cap rows))}))

(defn account-by-code
  "The account with this code in this currency, or nil.

  Matched on **code and currency** for the reason
  `clofin.settlement.service/resolve-accounts` gives: an account holds exactly
  one currency (invariant I6), so a `1300-IN-TRANSIT` in SGD cannot carry a USD
  statement.

  The account a statement is *reconciled against* is always
  `1300-IN-TRANSIT` in the statement's currency, and nothing else. ADR-0018
  makes that account CloFin's own view of value it has released and does not yet
  know the fate of, which is precisely what a scheme's statement is a second
  opinion about; it also rejected per-scheme clearing accounts as premature, and
  this increment does not revisit that — one account per currency, whichever
  simulated scheme sent the statement. The code itself comes from
  `clofin.recon.adjustment/account-roles`, which is where both codes an
  adjustment touches are named."
  [source organisation-id currency code]
  (when-let [row (db/query-one
                  source
                  ["select id, organisation_id, code, name, type, currency, status
                      from ledger_account
                     where organisation_id = ? and code = ? and currency = ?"
                   organisation-id code currency])]
    {:id              (:id row)
     :organisation-id (:organisation-id row)
     :code            (:code row)
     :name            (:name row)
     :type            (keyword (:type row))
     :currency        (:currency row)
     :status          (keyword (:status row))}))

;; ---------------------------------------------------------------------------
;; Statements
;; ---------------------------------------------------------------------------

(def replay-key
  "The unique constraint that makes a second statement under one reference
  unrepresentable. Named as a value because this namespace and its tests both
  reason about it, and a constraint name spelled out twice is one that can be
  renamed in one of them."
  "recon_statement_replay_key")

(defn find-statement
  "The statement with this id **within this organisation**, or nil."
  [source organisation-id id]
  (row->statement
   (db/query-one source [(str statement-columns "where organisation_id = ? and id = ?")
                         organisation-id id])))

(defn find-statement-by-reference
  "The stored receipt for a statement reference, or nil.

  Read **before** any work is attempted, not only after a collision. That order
  is what makes a replay reproduce the original answer rather than re-derive
  one: a receipt whose disposition was `refused` must answer the same way
  however the world has moved on since — the organisation may have opened the
  missing account in the meantime, and re-evaluating would answer a question
  nobody asked twice (audit findings **F-008**, **F-009**)."
  [source organisation-id statement-reference]
  (row->statement
   (db/query-one source [(str statement-columns
                              "where organisation_id = ? and statement_reference = ?")
                         organisation-id statement-reference])))

(defn insert-statement!
  "Commit the receipt for one statement, carrying what CloFin did about it.
  Returns the row, or **nil when the replay key was already taken**.

  The row is a receipt, not a record of successful work (standing lesson
  **L-11**): `disposition` is written in the same statement as the arrival, so
  the two commit together, and nothing here renders an error — the caller's
  refusal is rendered after its transaction commits.

  **Inside a savepoint**, and that is load-bearing rather than defensive.
  PostgreSQL aborts the whole transaction on a constraint violation, so merely
  catching the duplicate key would leave the caller in a transaction whose next
  read fails for a reason unrelated to what it asked. A concurrent identical
  ingestion blocks here until the first commits; the loser then rolls back to
  the savepoint and replays the winner's stored answer."
  [tx {:keys [id organisation-id scheme currency statement-reference format
              format-version period-start period-end content-digest
              disposition disposition-reason reconciled-account-id received-by]}]
  (when-not (contains? statement/dispositions disposition)
    (err/invalid! (str "Unknown statement disposition: " disposition)
                  {:disposition (str disposition) :known (vec statement/dispositions)}))
  (when (and disposition-reason
             (not (contains? statement/stored-refusal-reasons disposition-reason)))
    (err/invalid! (str "Unknown statement refusal reason: " disposition-reason)
                  {:disposition-reason (str disposition-reason)
                   :known (vec statement/stored-refusal-reasons)}))
  (db/tolerating-violation
   tx
   (fn [conn]
     (row->statement
      (db/insert-returning!
       conn
       ["insert into reconciliation_statement
           (id, organisation_id, scheme, currency, statement_reference, format,
            format_version, period_start, period_end, content_digest,
            disposition, disposition_reason, reconciled_account_id, received_by)
         values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         returning id, organisation_id, scheme, currency, statement_reference, format,
                   format_version, period_start, period_end, content_digest,
                   disposition, disposition_reason, reconciled_account_id,
                   received_at, received_by"
        id organisation-id scheme currency statement-reference format
        (int format-version) period-start period-end content-digest
        disposition disposition-reason reconciled-account-id received-by])))
   (fn [{:keys [sql-state constraint]}]
     (if (and (= sql-state (:unique-violation db/sql-states))
              (= replay-key constraint))
       nil
       (err/fail! :conflict "This reconciliation statement could not be recorded"
                  (err/internal {:constraint constraint :sql-state sql-state}))))))

(defn insert-lines!
  "Write a statement's lines. Returns the number written."
  [tx statement-id lines]
  (doseq [line lines]
    (db/execute! tx ["insert into reconciliation_statement_line
                        (statement_id, line_no, scheme_reference, payment_reference,
                         line_type, amount_minor, currency, value_date)
                      values (?, ?, ?, ?, ?, ?, ?, ?)"
                     statement-id (int (:line-no line)) (:scheme-reference line)
                     (:payment-reference line) (:line-type line)
                     (:minor-units (:amount line)) (:currency (:amount line))
                     (:value-date line)]))
  (count lines))

(defn lines-for
  "Every line of a statement, in document order."
  [source statement-id]
  (mapv row->line
        (db/query source ["select line_no, scheme_reference, payment_reference,
                                  line_type, amount_minor, currency, value_date
                             from reconciliation_statement_line
                            where statement_id = ? order by line_no"
                          statement-id])))

;; ---------------------------------------------------------------------------
;; Matches
;; ---------------------------------------------------------------------------

(defn insert-matches!
  "Write the matches a reconciliation produced, each carrying the rule that
  produced it.

  The rule id is validated here as well as by `recon_match_rule_known`, so a
  rule renamed in code without a migration is refused with the vocabulary named
  rather than as a raw constraint failure rendered `500` — the shape audit
  finding **A-017** found in settlement."
  [tx statement-id matches]
  (doseq [{:keys [line-no entry-id rule-id]} matches]
    (when-not (contains? (set matching/rule-ids) rule-id)
      (err/invalid! (str "Unknown matching rule: " rule-id)
                    {:rule-id (str rule-id) :known (vec matching/rule-ids)}))
    (db/execute! tx ["insert into reconciliation_match (statement_id, line_no, entry_id, rule_id)
                      values (?, ?, ?, ?)"
                     statement-id (int line-no) entry-id rule-id]))
  (count matches))

(defn matches-for
  "Every match recorded against a statement, in line order."
  [source statement-id]
  (mapv row->match
        (db/query source ["select line_no, entry_id, rule_id, matched_at
                             from reconciliation_match
                            where statement_id = ? order by line_no"
                          statement-id])))

;; ---------------------------------------------------------------------------
;; Breaks
;; ---------------------------------------------------------------------------

(def ^:private break-instruction-sql
  "Which payment instruction a break is about, derived from whichever side of the
  disagreement it has.

  A break carries a ledger side (`entry_id`), a statement side (`line_no`), or
  both — `recon_break_has_a_side` requires at least one. Each side names the
  instruction differently, and each is read where it actually lives:

  - the **ledger** side from `journal_entry.reference_id`, which is a `uuid`
    column and is the instruction by definition when `reference_type` says so;
  - the **statement** side from `reconciliation_statement_line.payment_reference`,
    which is `text` because it is whatever the document carried. It is cast only
    when it looks like a UUID, for exactly the reason
    `clofin.recon.matching/reference-of` renders rather than parses: a garbled
    reference should fail to name anything, not fail the read.

  The ledger side is preferred where both exist. It is CloFin's own record of
  what the movement was about, and a matched-but-disagreeing break has a
  statement line the matcher already bound to that entry."
  "coalesce(
     (select e.reference_id
        from journal_entry e
       where e.id = b.entry_id and e.reference_type = 'payment-instruction'),
     (select case
               when sl.payment_reference ~
                    '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
               then sl.payment_reference::uuid
             end
        from reconciliation_statement_line sl
       where sl.statement_id = b.statement_id and sl.line_no = b.line_no))")

(def ^:private break-columns
  "Every break column, plus the two facts a break derives rather than stores.

  `age_seconds` has been derived since increment 6. `instruction_id` and
  `retried_by_ids` join it in this one: a break on a **returned** payment is the
  exception an investigator is holding, and the first question they ask about it
  is whether the payment was raised again (ADR-0024). Both are computed in the
  same statement that reads the row, so neither is a column that was true once."
  (str "select b.id, b.organisation_id, b.statement_id, b.account_id, b.kind, b.state,
               b.line_no, b.entry_id, b.currency, b.statement_amount_minor,
               b.ledger_amount_minor, b.detail, b.assignee_id, b.opened_at,
               b.resolved_at,
               extract(epoch from (now() - b.opened_at))::bigint as age_seconds, "
       break-instruction-sql " as instruction_id,
               (select coalesce(array_agg(r.id order by r.created_at, r.id), '{}'::uuid[])
                  from payment_instruction r
                 where r.organisation_id = b.organisation_id
                   and r.retries_id = " break-instruction-sql ") as retried_by_ids
          from reconciliation_break b "))

(defn insert-break!
  "Open one break. Returns it as stored, with its derived age.

  Every break opens `open` and **assigned**: a break with no owner is the thing
  PR-052 exists to prevent, and `assignee_id` is `not null` so an unowned one
  cannot be written by any route."
  [tx {:keys [id organisation-id statement-id account-id kind line-no entry-id
              currency statement-amount ledger-amount detail assignee-id]}]
  (when-not (contains? matching/break-kinds kind)
    (err/invalid! (str "Unknown reconciliation break kind: " kind)
                  {:kind (str kind) :known (vec matching/break-kinds)}))
  (db/execute! tx ["insert into reconciliation_break
                      (id, organisation_id, statement_id, account_id, kind, state,
                       line_no, entry_id, currency, statement_amount_minor,
                       ledger_amount_minor, detail, assignee_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                   id organisation-id statement-id account-id kind
                   (name break-state/initial-state)
                   (when line-no (int line-no)) entry-id currency
                   (:minor-units statement-amount) (:minor-units ledger-amount)
                   detail assignee-id])
  (row->break (db/query-one tx [(str break-columns "where id = ?") id])))

(defn find-break
  "The break with this id within this organisation, or nil."
  [source organisation-id id]
  (row->break
   (db/query-one source [(str break-columns "where organisation_id = ? and id = ?")
                         organisation-id id])))

(defn lock-break!
  "Read a break `for update`, or `404`. **Lock order step 3.**

  Held until the caller's transaction ends, so two operators assigning or
  resolving the same break serialise rather than both deciding against a state
  that is about to change (standing lesson **L-8**). The derived facts are read
  under the lock too, which costs nothing and — more to the point — keeps **one**
  row shape: a locked break and a listed break are the same value, so a caller
  that renders one can render the other. This function used to spell its own
  column list out beside `break-columns`, which is a second copy of the
  projection and the shape standing lesson **L-6** names."
  [tx organisation-id id]
  (or (row->break
       (db/query-one tx [(str break-columns
                              "where b.organisation_id = ? and b.id = ? for update")
                         organisation-id id]))
      (err/not-found! "No such reconciliation break in this organisation" {:id (str id)})))

(defn breaks-for-statement
  "Every break a statement opened, oldest first."
  [source statement-id]
  (mapv row->break
        (db/query source [(str break-columns
                               "where statement_id = ? order by opened_at, id limit ?")
                          statement-id (inc row-cap)])))

(defn list-breaks
  "An organisation's breaks, oldest first, capped at `row-cap`.

  Oldest first, deliberately, and it is the only list in CloFin ordered that
  way: every other list answers \"what happened recently?\" and this one answers
  \"what has been waiting longest?\". A break found in March may have originated
  in January (PRD §2), and a queue that buried the oldest item on page four
  would be the spreadsheet this module replaces.

  Returns `{:breaks […] :truncated? bool}`."
  [source organisation-id {:keys [state kind account-id assignee-id]}]
  (when (and state (not (contains? break-state/states (keyword state))))
    (err/invalid! (str "Unknown reconciliation break state: " state)
                  {:state (str state) :known (mapv name break-state/states)}))
  (when (and kind (not (contains? matching/break-kinds kind)))
    (err/invalid! (str "Unknown reconciliation break kind: " kind)
                  {:kind (str kind) :known (vec matching/break-kinds)}))
  (let [clauses (cond-> ["organisation_id = ?"]
                  state       (conj "state = ?")
                  kind        (conj "kind = ?")
                  account-id  (conj "account_id = ?")
                  assignee-id (conj "assignee_id = ?"))
        params  (cond-> [organisation-id]
                  state       (conj (name state))
                  kind        (conj kind)
                  account-id  (conj account-id)
                  assignee-id (conj assignee-id))
        rows (db/query source
                       (into [(str break-columns "where "
                                   (str/join " and " clauses)
                                   " order by opened_at, id limit ?")]
                             (conj params (inc row-cap))))]
    {:breaks     (mapv row->break (take row-cap rows))
     :truncated? (> (count rows) row-cap)}))

(defn set-break-state!
  "Write a break's state, and its assignee where the caller is setting one.
  Returns the break as stored.

  Takes the state the caller obtained from
  `clofin.recon.break-state/transition` — this function does not decide, it
  records. Keeping the decision in the pure namespace is what stops a second,
  subtly different lifecycle appearing here the first time somebody needs a
  state in a hurry.

  `resolved_at` is set by the same statement that writes the terminal state,
  because `recon_break_resolution_paired` requires the two to agree and a
  two-statement update would leave the row, however briefly, in a shape the
  schema refuses.

  `assignee-id` is **required** rather than optional. A caller that is not
  changing the owner passes the one it read under the lock, which is one line at
  each call site and removes the branch where a null could quietly blank the
  column that PR-052 requires to be populated."
  [tx organisation-id id {:keys [state assignee-id]}]
  (when-not (contains? break-state/states state)
    (err/invalid! (str "Unknown reconciliation break state: " state)
                  {:state (str state) :known (mapv name break-state/states)}))
  (when-not (uuid? assignee-id)
    (err/invalid! "A reconciliation break must name the actor it is assigned to" {:id (str id)}))
  (db/execute! tx [(if (break-state/terminal? state)
                     "update reconciliation_break
                         set state = ?, assignee_id = ?, resolved_at = now()
                       where organisation_id = ? and id = ?"
                     "update reconciliation_break
                         set state = ?, assignee_id = ?
                       where organisation_id = ? and id = ?")
                   (name state) assignee-id organisation-id id])
  (find-break tx organisation-id id))

;; ---------------------------------------------------------------------------
;; Adjustments
;; ---------------------------------------------------------------------------

(def ^:private adjustment-columns
  "select id, organisation_id, break_id, amount_minor, currency, direction,
          narrative, status, approvals_required, entry_id, created_by,
          created_at, posted_at
     from reconciliation_adjustment ")

(def posted-index
  "The partial unique index that makes a second posted adjustment for one break
  unrepresentable. Named as a value for the same reason
  `clofin.settlement.repository/membership-index` is."
  "recon_adjustment_posted_key")

(defn insert-adjustment!
  "Write an adjustment in the status an adjustment begins in. Returns it as
  stored.

  The status is bound from `clofin.recon.adjustment/initial-status` rather than
  written as a literal, so that the one place that decides where an adjustment
  starts is the lifecycle table."
  [tx {:keys [id organisation-id break-id amount direction narrative
              approvals-required created-by]}]
  (db/execute! tx ["insert into reconciliation_adjustment
                      (id, organisation_id, break_id, amount_minor, currency, direction,
                       narrative, status, approvals_required, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                   id organisation-id break-id (:minor-units amount) (:currency amount)
                   (name direction) narrative (name adjustment/initial-status)
                   (int approvals-required) created-by])
  (row->adjustment (db/query-one tx [(str adjustment-columns "where id = ?") id])))

(defn find-adjustment
  "The adjustment with this id within this organisation, or nil."
  [source organisation-id id]
  (row->adjustment
   (db/query-one source [(str adjustment-columns "where organisation_id = ? and id = ?")
                         organisation-id id])))

(defn lock-adjustment!
  "Read an adjustment `for update`, or `404`. **Lock order step 4.**"
  [tx organisation-id id]
  (or (row->adjustment
       (db/query-one tx [(str adjustment-columns
                              "where organisation_id = ? and id = ? for update")
                         organisation-id id]))
      (err/not-found! "No such reconciliation adjustment in this organisation"
                      {:id (str id)})))

(defn adjustments-for-break
  "Every adjustment raised against a break, oldest first."
  [source break-id]
  (mapv row->adjustment
        (db/query source [(str adjustment-columns
                               "where break_id = ? order by created_at, id limit ?")
                          break-id (inc row-cap)])))

(defn mark-posted!
  "Record that an adjustment posted, exactly once. Returns it as stored, or nil
  when it was already posted.

  `where status = 'proposed'` is the whole guarantee, and it is in the statement
  rather than in a preceding read: a check-then-update is a race, and the window
  between them is exactly long enough for a concurrent approval to post the same
  adjustment. Returning nil for zero rows updated lets the caller distinguish
  \"I posted it\" from \"someone already had\" without asking again — the same
  shape `clofin.settlement.repository/resolve-item!` uses, and the half that
  keeps working if a later caller forgets the lock.

  A second **posted** adjustment for the same break is refused by
  `recon_adjustment_posted_key`, which is a schema guarantee rather than a check
  in this function — so it binds a fix-up script and a defect too. It is
  translated into a named `409` here rather than surfacing as a `500`, because
  \"this break has already been corrected\" is something the caller can act on
  (the shape audit finding **A-017** found missing in settlement)."
  [tx organisation-id id entry-id]
  (let [updated (try
                  (db/execute! tx ["update reconciliation_adjustment
                                      set status = ?, entry_id = ?, posted_at = now()
                                    where organisation_id = ? and id = ? and status = ?"
                                   (name (adjustment/transition
                                          adjustment/initial-status :post))
                                   entry-id organisation-id id
                                   (name adjustment/initial-status)])
                  (catch Exception t
                    (let [{:keys [sql-state constraint]} (db/violation t)]
                      (if (and (= sql-state (:unique-violation db/sql-states))
                               (= posted-index constraint))
                        (err/conflict!
                         (str "This reconciliation break already has a posted adjustment. "
                              "A break is corrected once; raise a new break, or a reversing "
                              "entry against the one that was posted")
                         {:clofin/constraint constraint
                          :adjustment-id     (str id)})
                        (throw t)))))]
    (when (pos? updated)
      (find-adjustment tx organisation-id id))))

(defn mark-rejected!
  "Record that an adjustment was refused, exactly once. Returns it as stored, or
  nil when it was no longer `proposed`.

  The same `where status = <initial>` guarantee `mark-posted!` relies on, and in
  the statement rather than in a preceding read for the same reason: a
  check-then-update is a race, and the window between them is exactly long
  enough for a concurrent approval to post the adjustment this one is refusing.
  Returning nil for zero rows updated lets the caller tell \"I rejected it\"
  from \"someone had already decided\" without asking again.

  **Nothing else moves.** No entry id, no posted instant — the row stays outside
  `recon_adjustment_posted_key`'s partial predicate, so the break it names can
  still be corrected by a different adjustment, which is the whole point of
  recording the refusal rather than leaving the proposal to sit. Who rejected
  it, why and when are the `approval` row the same transaction wrote: the same
  place, and the only place, a rejected payment keeps them."
  [tx organisation-id id]
  (let [updated (db/execute! tx ["update reconciliation_adjustment
                                    set status = ?
                                  where organisation_id = ? and id = ? and status = ?"
                                 (name (adjustment/transition
                                        adjustment/initial-status :reject))
                                 organisation-id id
                                 (name adjustment/initial-status)])]
    (when (pos? updated)
      (find-adjustment tx organisation-id id))))

;; ---------------------------------------------------------------------------
;; Status (PR-054)
;; ---------------------------------------------------------------------------

(defn status-for
  "Reconciliation status for one account over one period.

  A statement belongs to the period when **its own period lies inside**
  `[from, to)`. That is the containment a reader means by \"reconciliation
  status for August\": a statement covering the last week of July and the first
  of August is not an August statement, and counting half of it would produce a
  figure that agrees with no document.

  Returns counts, never a sample, and every figure is computed by the database
  over the rows themselves rather than assembled from a capped list — so the
  answer stays correct for a period whose breaks would not fit in one page
  (AC-7). The oldest open break's **age** is derived here, in the same
  statement, and is never stored."
  [source organisation-id account-id {:keys [from to]}]
  (let [scoped ["select id from reconciliation_statement
                  where organisation_id = ? and reconciled_account_id = ?
                    and period_start >= ? and period_end <= ?"
                organisation-id account-id from to]
        [scoped-sql & scoped-params] scoped
        one        (fn [sql] (db/query-one source (into [sql] scoped-params)))]
    {:statements
     (let [row (one (str "select count(*) as received,
                                 count(*) filter (where disposition = 'applied') as applied,
                                 count(*) filter (where disposition = 'refused') as refused
                            from reconciliation_statement
                           where id in (" scoped-sql ")"))]
       {:received (db/->long (:received row))
        :applied  (db/->long (:applied row))
        :refused  (db/->long (:refused row))})
     :lines
     (let [row (one (str "select count(*) as total,
                                 count(m.line_no) as matched
                            from reconciliation_statement_line l
                            left join reconciliation_match m
                              on m.statement_id = l.statement_id and m.line_no = l.line_no
                           where l.statement_id in (" scoped-sql ")"))
           total (db/->long (:total row))
           matched (db/->long (:matched row))]
       {:total total :matched matched :unmatched (- total matched)})
     :matches-by-rule
     (into (sorted-map)
           (map (juxt :rule-id (comp db/->long :count)))
           (db/query source
                     (into [(str "select rule_id, count(*) as count
                                    from reconciliation_match
                                   where statement_id in (" scoped-sql ")
                                   group by rule_id order by rule_id")]
                           scoped-params)))
     :breaks-by-state
     (into (sorted-map)
           (map (juxt :state (comp db/->long :count)))
           (db/query source
                     (into [(str "select state, count(*) as count
                                    from reconciliation_break
                                   where statement_id in (" scoped-sql ")
                                   group by state order by state")]
                           scoped-params)))
     :breaks-by-kind
     (into (sorted-map)
           (map (juxt :kind (comp db/->long :count)))
           (db/query source
                     (into [(str "select kind, count(*) as count
                                    from reconciliation_break
                                   where statement_id in (" scoped-sql ")
                                   group by kind order by kind")]
                           scoped-params)))
     :oldest-unresolved-age-seconds
     (let [row (one (str "select max(extract(epoch from (now() - opened_at)))::bigint as age
                            from reconciliation_break
                           where statement_id in (" scoped-sql ")
                             and resolved_at is null"))]
       (when (some? (:age row)) (db/->long (:age row))))}))
