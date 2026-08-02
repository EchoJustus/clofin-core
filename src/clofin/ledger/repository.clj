(ns clofin.ledger.repository
  "Persistence for ledger accounts and the double-entry journal.

  This is the namespace that connects the pure ledger model to PostgreSQL.
  `clofin.ledger.account` and `clofin.ledger.entry` stay pure and know nothing
  about a database; a `repository` namespace is the seam where that changes.
  See docs/ADR/0012-repository-seam-and-posting-time-validation.md.

  Two things this namespace deliberately does not do:

  - It never stores a balance. Every balance here is an aggregation over
    journal lines, computed at read time (ADR-0008). There is no `balance`
    column to read, and adding one would be the single most damaging change
    anyone could make to this codebase.
  - It never mutates a posted entry. `journal_entry` and `journal_line` are
    append-only, enforced by trigger. A correction is a reversing entry.

  Every function takes a `source`: a pool, or a connection already inside a
  caller's transaction. The same function therefore composes into a larger unit
  of work without knowing which it was given."
  (:require [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.ledger.account :as account]
            [clofin.ledger.entry :as entry]
            [clofin.money :as money])
  (:import [java.sql Connection]
           [java.time Instant]))

(def row-cap
  "Maximum rows any list or statement query returns.

  Real pagination waits for a consumer that needs it; until then a hard cap
  with an explicit `:truncated?` flag is the smallest thing that is not
  misleading. A capped statement still reports a correct closing balance,
  because the closing balance is aggregated separately rather than summed from
  the rows returned. See ADR-0011."
  500)

;; ---------------------------------------------------------------------------
;; Rows to domain values
;; ---------------------------------------------------------------------------

(defn- row->account
  [row]
  (when row
    {:id              (:id row)
     :organisation-id (:organisation-id row)
     :code            (:code row)
     :name            (:name row)
     :type            (keyword (:type row))
     :currency        (:currency row)
     :status          (keyword (:status row))}))

(defn- row->line
  [row]
  {:account-id (:account-id row)
   :direction  (keyword (:direction row))
   :amount     (money/of (:currency row) (db/->long (:amount-minor row)))})

(defn- row->entry
  [row lines]
  (when row
    {:id              (:id row)
     :organisation-id (:organisation-id row)
     :occurred-at     (db/->instant (:occurred-at row))
     :recorded-at     (db/->instant (:recorded-at row))
     :narrative       (:narrative row)
     :reference       {:type (keyword (:reference-type row)) :id (:reference-id row)}
     :lines           lines}))

;; ---------------------------------------------------------------------------
;; Accounts
;; ---------------------------------------------------------------------------

(def ^:private account-columns
  "select id, organisation_id, code, name, type, currency, status from ledger_account ")

(defn create-account!
  "Persist a new ledger account. Returns it as stored.

  Defaults to `:active`: an account created in any other state would be one
  nobody asked for and nobody could post to."
  [source candidate]
  (let [acct (account/account (merge {:status :active} candidate))]
    (try
      (db/execute! source
                   ["insert into ledger_account (id, organisation_id, code, name, type, currency, status)
                     values (?, ?, ?, ?, ?, ?, ?)"
                    (:id acct) (:organisation-id acct) (:code acct) (:name acct)
                    (name (:type acct)) (:currency acct) (name (:status acct))])
      acct
      (catch Exception t
        (let [{:keys [sql-state constraint]} (db/violation t)]
          (condp = sql-state
            (:unique-violation db/sql-states)
            (err/conflict! "An account with this code already exists in this organisation"
                           {:code (:code acct) :constraint constraint})

            (:foreign-key-violation db/sql-states)
            (err/fail! :unprocessable "Unknown organisation"
                       {:organisation-id (:organisation-id acct)})

            (throw t)))))))

(defn find-account
  "The account with this id **within this organisation**, or nil.

  Scoping by organisation is not a convenience: an unscoped lookup is how one
  tenant reads another's chart of accounts."
  [source organisation-id id]
  (row->account
   (db/query-one source [(str account-columns "where organisation_id = ? and id = ?")
                         organisation-id id])))

(defn list-accounts
  "Every account in an organisation, by code, capped at `row-cap`."
  [source organisation-id]
  (mapv row->account
        (db/query source [(str account-columns "where organisation_id = ? order by code limit ?")
                          organisation-id row-cap])))

;; ---------------------------------------------------------------------------
;; Posting
;; ---------------------------------------------------------------------------

(defn- transactionally
  "Run `(f conn)` in a transaction.

  When `source` is already a connection the caller owns a transaction and this
  work simply joins it — atomicity is then the caller's to guarantee, which is
  what lets a payment instruction post its entry as part of a larger unit of
  work in a later increment."
  [source f]
  (if (instance? Connection source)
    (f source)
    (db/with-transaction* source f)))

(defn- assert-postable!
  "Load every account an entry references and assert the entry may be posted.

  Runs inside the posting transaction, so an account frozen concurrently is
  either visible here or is not, and either way the outcome is consistent.
  Three rules, none of which the pure layer can check because none is a
  property of the entry value alone:

  1. the account exists **in this organisation**;
  2. the account accepts postings — `frozen` and `closed` do not;
  3. the line's currency matches the account's, or the resulting balance is not
     computable at all.

  An account belonging to another organisation is reported as unknown rather
  than as forbidden. Saying \"forbidden\" would confirm that the id exists and
  belongs to someone else — a tenancy disclosure available to anyone able to
  guess a UUID (ADR-0012)."
  [tx {:keys [organisation-id lines]}]
  (let [ids   (into [] (comp (map :account-id) (distinct)) lines)
        rows  (db/query tx (into [(str account-columns
                                       "where organisation_id = ? and id in ("
                                       (db/placeholders (count ids)) ")")
                                  organisation-id]
                                 ids))
        by-id (into {} (map (juxt :id row->account)) rows)]

    (when-let [unknown (seq (remove by-id ids))]
      (err/fail! :unprocessable
                 "Journal entry references accounts that do not exist in this organisation"
                 {:account-ids (mapv str unknown)}))

    (when-let [blocked (seq (remove account/postable? (vals by-id)))]
      (err/fail! :unprocessable
                 "Journal entry references accounts that do not accept postings"
                 {:accounts (mapv (fn [a] {:id (str (:id a))
                                           :code (:code a)
                                           :status (name (:status a))})
                                  blocked)}))

    (doseq [{:keys [account-id amount]} lines]
      (let [acct (by-id account-id)]
        (when-not (= (:currency acct) (:currency amount))
          (err/fail! :unprocessable
                     "Journal line currency does not match the account it posts to"
                     {:account-id (str account-id)
                      :code (:code acct)
                      :account-currency (:currency acct)
                      :line-currency (:currency amount)}))))
    by-id))

(defn- posting-failure!
  "Translate a constraint violation raised while posting into a domain outcome.

  Anything not named here is a defect rather than a caller error and is
  rethrown, so it surfaces as a 500 with a correlation id and nothing else.
  In particular the deferred zero-sum trigger is *not* translated: if it fires,
  the domain constructor and the HTTP layer both missed an unbalanced entry,
  and that is a bug in CloFin, not a mistake by the caller."
  [t posted]
  (let [{:keys [sql-state constraint]} (db/violation t)]
    (cond
      (and (= sql-state (:unique-violation db/sql-states))
           (= "journal_entry_reverses_key" constraint))
      (err/conflict! "That entry has already been reversed; a second reversal would reapply the original movement"
                     {:reverses (str (get-in posted [:reference :id]))})

      (= sql-state (:unique-violation db/sql-states))
      (err/conflict! "A journal entry with this id has already been posted"
                     {:id (str (:id posted)) :constraint constraint})

      (= sql-state (:foreign-key-violation db/sql-states))
      (err/fail! :unprocessable
                 "Journal entry references a record that does not exist"
                 {:constraint constraint})

      :else (throw t))))

(defn post-entry!
  "Post a journal entry: the entry row and all of its lines, in one transaction.

  The zero-sum invariant is checked three times on the way in — by
  `entry/entry` here, by the HTTP layer before that (which raises the 422
  carrying the per-currency shortfall), and by the deferred database trigger at
  commit. That is deliberate duplication and must not be reduced: an unbalanced
  entry is the one thing that must never be committed, and each layer fails for
  a different reason. See ADR-0008.

  Returns the entry as posted."
  [source candidate]
  (let [posted      (entry/entry candidate)
        ;; An entry correcting another records the link in `reverses_id`, which
        ;; carries a unique index — an entry can be reversed at most once,
        ;; because a second reversal would silently reapply the original.
        reverses-id (when (= :reversal (get-in posted [:reference :type]))
                      (get-in posted [:reference :id]))]
    (try
      (transactionally
       source
       (fn [tx]
         (assert-postable! tx posted)
         (db/execute! tx
                      ["insert into journal_entry
                          (id, organisation_id, occurred_at, narrative,
                           reference_type, reference_id, reverses_id)
                        values (?, ?, ?, ?, ?, ?, ?)"
                       (:id posted) (:organisation-id posted) (:occurred-at posted)
                       (:narrative posted) (name (get-in posted [:reference :type]))
                       (get-in posted [:reference :id]) reverses-id])
         (doseq [[idx line] (map-indexed vector (:lines posted))]
           (db/execute! tx
                        ["insert into journal_line
                            (id, entry_id, line_no, account_id, direction, amount_minor, currency)
                          values (?, ?, ?, ?, ?, ?, ?)"
                         ;; Line ids are surrogate: the domain model identifies a
                         ;; line by its position within its entry, not by an id
                         ;; a caller could supply or depend on.
                         (random-uuid) (:id posted) (inc idx) (:account-id line)
                         (name (:direction line)) (:minor-units (:amount line))
                         (:currency (:amount line))]))))
      posted
      (catch Exception t (posting-failure! t posted)))))

;; ---------------------------------------------------------------------------
;; Reading entries
;; ---------------------------------------------------------------------------

(def ^:private entry-columns
  "select id, organisation_id, occurred_at, recorded_at, narrative,
          reference_type, reference_id, reverses_id
     from journal_entry ")

(defn- lines-for
  "Lines for a set of entries, keyed by entry id and ordered within each."
  [source entry-ids]
  (if (empty? entry-ids)
    {}
    (->> (db/query source (into [(str "select entry_id, line_no, account_id, direction,
                                              amount_minor, currency
                                         from journal_line
                                        where entry_id in ("
                                      (db/placeholders (count entry-ids))
                                      ") order by entry_id, line_no")]
                                entry-ids))
         (group-by :entry-id)
         (reduce-kv (fn [acc entry-id rows] (assoc acc entry-id (mapv row->line rows))) {}))))

(defn find-entry
  "The entry with this id within this organisation, or nil."
  [source organisation-id id]
  (when-let [row (db/query-one source [(str entry-columns "where organisation_id = ? and id = ?")
                                       organisation-id id])]
    (row->entry row (get (lines-for source [id]) id []))))

(defn list-entries-for-account
  "Entries touching an account, most recent occurrence first, capped at
  `row-cap`.

  Ordered by occurrence rather than by recording time, because the question
  this answers is what happened to the account, not when CloFin was told."
  [source organisation-id account-id]
  (let [rows (db/query source
                       [(str entry-columns
                             "where organisation_id = ?
                                and exists (select 1 from journal_line l
                                             where l.entry_id = journal_entry.id
                                               and l.account_id = ?)
                              order by occurred_at desc, recorded_at desc, id
                              limit ?")
                        organisation-id account-id row-cap])
        by-entry (lines-for source (mapv :id rows))]
    (mapv (fn [row] (row->entry row (get by-entry (:id row) []))) rows)))

;; ---------------------------------------------------------------------------
;; Derived balances
;; ---------------------------------------------------------------------------
;;
;; Both statements below are constants assembled from SQL fragments defined in
;; this file. No caller-supplied value is ever concatenated into SQL — every
;; value travels as a bound parameter, here as everywhere else in CloFin. The
;; two exist separately because a comparison operator cannot be a parameter,
;; and because the difference between them is the difference between "as at"
;; and "up to but excluding" — which ADR-0011 exists to keep straight.

(def ^:private balance-select
  "select coalesce(sum(case when l.direction = 'debit'  then l.amount_minor else 0 end), 0) as debit_minor,
          coalesce(sum(case when l.direction = 'credit' then l.amount_minor else 0 end), 0) as credit_minor
     from journal_line l
     join journal_entry e on e.id = l.entry_id
    where l.account_id = ? and l.currency = ? and e.occurred_at ")

(def ^:private balance-inclusive-sql (str balance-select "<= ?"))
(def ^:private balance-exclusive-sql (str balance-select "< ?"))

(defn- balance-from
  "Aggregate debits and credits, then let the domain apply the sign convention.

  The totals are summed in PostgreSQL — the alternative is loading every line
  an account has ever had into memory to add them up — but the step that turns
  those totals into a *balance* goes through `account/balance`, so the rule
  that a debit increases an asset and decreases a liability stays expressed in
  exactly one place (`clofin.ledger.account/signed-amount`)."
  [source sql {:keys [currency] :as acct} instant]
  (let [row (db/query-one source [sql (:id acct) currency instant])]
    (account/balance acct
                     [{:direction :debit  :amount (money/of currency (db/->long (:debit-minor row)))}
                      {:direction :credit :amount (money/of currency (db/->long (:credit-minor row)))}])))

(defn balance-at
  "The account's balance **as at** `as-of`, counting every line up to and
  including that instant. Derived from the journal, never read from a column."
  [source account as-of]
  (balance-from source balance-inclusive-sql account as-of))

(defn balance-strictly-before
  "The account's balance considering only lines strictly before `instant`.

  This is the form a statement needs: it is both the opening balance at the
  start of a period and the closing balance at its end, which is precisely why
  consecutive periods chain exactly (ADR-0011)."
  [source account instant]
  (balance-from source balance-exclusive-sql account instant))

;; ---------------------------------------------------------------------------
;; Statements
;; ---------------------------------------------------------------------------

(def ^:private movements-sql
  "select e.id as entry_id, e.occurred_at, e.narrative,
          l.line_no, l.direction, l.amount_minor, l.currency
     from journal_line l
     join journal_entry e on e.id = l.entry_id
    where l.account_id = ? and l.currency = ?
      and e.occurred_at >= ? and e.occurred_at < ?
    order by e.occurred_at, e.recorded_at, e.id, l.line_no
    limit ?")

(defn- with-running-balance
  "Thread the opening balance through the movements in order.

  The running balance is the account's balance after each movement, so the last
  one equals the closing balance — provided the movements were not capped."
  [account opening rows]
  (:movements
   (reduce (fn [{:keys [balance movements]} row]
             (let [amount    (money/of (:currency row) (db/->long (:amount-minor row)))
                   direction (keyword (:direction row))
                   running   (money/+ balance (account/signed-amount (:type account) direction amount))]
               {:balance running
                :movements (conj movements
                                 {:entry-id        (:entry-id row)
                                  :line-no         (int (:line-no row))
                                  :occurred-at     (db/->instant (:occurred-at row))
                                  :narrative       (:narrative row)
                                  :direction       direction
                                  :amount          amount
                                  :running-balance running})}))
           {:balance opening :movements []}
           rows)))

(defn statement
  "Opening balance, movements and closing balance for an account over a period.

  The period is half-open, `[from, to)`: a movement belongs to it when
  `from <= occurred-at < to`. Consecutive periods therefore chain exactly —
  the closing balance of one is the opening balance of the next — with no
  movement counted twice and none lost. See ADR-0011 for why this rather than
  an inclusive end.

      {:account         account
       :from            inst
       :to              inst
       :opening-balance money
       :closing-balance money
       :movements       [{:entry-id … :line-no … :occurred-at … :narrative …
                          :direction :debit|:credit :amount money
                          :running-balance money}]
       :truncated?      boolean}

  `:closing-balance` is aggregated over the whole journal, not summed from
  `:movements`, so it stays correct when `:truncated?` is true."
  [source account {:keys [from to]}]
  (when-not (inst? from)
    (err/invalid! "Statement requires a 'from' instant" {:from from}))
  (when-not (inst? to)
    (err/invalid! "Statement requires a 'to' instant" {:to to}))
  (when (.isAfter ^Instant (db/->instant from) ^Instant (db/->instant to))
    (err/invalid! "Statement period ends before it begins" {:from from :to to}))
  (let [opening    (balance-strictly-before source account from)
        closing    (balance-strictly-before source account to)
        ;; One row beyond the cap, purely to learn whether there were more.
        rows       (db/query source [movements-sql (:id account) (:currency account)
                                     from to (inc row-cap)])
        truncated? (> (count rows) row-cap)]
    {:account         account
     :from            (db/->instant from)
     :to              (db/->instant to)
     :opening-balance opening
     :closing-balance closing
     :movements       (with-running-balance account opening (take row-cap rows))
     :truncated?      truncated?}))
