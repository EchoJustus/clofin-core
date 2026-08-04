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
  arrives on a connection owned by `clofin.idempotency.repository`, because the
  state change and the idempotency key protecting it commit together or not at
  all."
  (:require [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.money :as money]
            [clofin.payments.instruction :as instruction]
            [clofin.payments.state :as state]))

(def row-cap
  "Maximum rows a list query returns.

  The same cap and the same reasoning as the ledger's: real pagination waits
  for a consumer that needs it, and until then a hard cap with an explicit
  `truncated` flag is the smallest thing that is not misleading. See ADR-0011."
  500)

;; ---------------------------------------------------------------------------
;; Rows to domain values
;; ---------------------------------------------------------------------------

(def ^:private instruction-columns
  "select id, organisation_id, debtor_account_id, creditor_name, creditor_account,
          amount_minor, currency, value_date, purpose_code, status,
          created_by, created_at, reverses_id
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
     :created-by        (:created-by row)
     :created-at        (db/->instant (:created-at row))
     :reverses-id       (:reverses-id row)}))

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

(defn- lock-instruction!
  "Read an instruction `for update`, or `404`.

  `for update` is what makes a read-then-write safe: the row is held until this
  transaction ends, so a concurrent caller blocks here rather than deciding
  against a status that is about to change underneath it. Outside a transaction
  the lock would be released at the next statement and would guarantee nothing,
  which is why every caller of this arrives on a connection that owns one."
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
                  purpose_code, status, created_by, reverses_id)
               values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               returning created_at"
              (:id instruction) (:organisation-id instruction)
              (:debtor-account-id instruction) (:creditor-name instruction)
              (:creditor-account instruction)
              (:minor-units (:amount instruction)) (:currency (:amount instruction))
              (:value-date instruction) (:purpose-code instruction)
              (name (:status instruction)) (:created-by instruction)
              (:reverses-id instruction)])]
    (assoc instruction :created-at (db/->instant (:created-at row)))))

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
  guess a UUID."
  [tx {:keys [organisation-id debtor-account-id amount]}]
  (let [row (db/query-one tx ["select id, code, currency, status from ledger_account
                                where organisation_id = ? and id = ?"
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

(defn create-instruction!
  "Persist a new instruction in `draft`. Returns it as stored.

  A caller cannot choose the status: an instruction that arrived already
  approved would be an approval nobody gave. `candidate` carries the id, the
  creating actor and — when this is a reversal — the settled instruction being
  reversed."
  [source candidate opts]
  (let [drafted (instruction/draft candidate opts)]
    (db/transactionally
     source
     (fn [tx]
       (assert-debtor-account! tx drafted)
       (when (:reverses-id drafted)
         (assert-reversal-target! tx drafted))
       (try
         (insert! tx drafted)
         (catch Exception t
           (let [{:keys [sql-state constraint]} (db/violation t)]
             (if (= sql-state (:foreign-key-violation db/sql-states))
               (err/fail! :unprocessable
                          "The instruction references a record that does not exist"
                          {:constraint constraint})
               (throw t)))))))))

(defn amend!
  "Apply `changes` to a **draft** instruction and return it as stored.

  Two rules, and neither is an `if` in this function. Whether the instruction's
  status permits amendment at all is `state/assert-mutable!` — held as data
  with every other rule about status. Which fields an amendment may touch, and
  whether the result is still valid, is `instruction/amend`.

  The row is locked for the duration, so an amendment and a concurrent
  submission cannot interleave into a submitted instruction carrying amended
  values that no approver will ever see."
  [source organisation-id id changes opts]
  (db/transactionally
   source
   (fn [tx]
     (let [existing (lock-instruction! tx organisation-id id)
           _        (state/assert-mutable! (:status existing))
           ;; TODO(TASK-003): PR-004 says a draft may be amended *by its
           ;; creator*. The creator is caller-asserted until there is an
           ;; authenticated principal, so enforcing it here would check that a
           ;; caller had copied a UUID correctly and look, from outside, like an
           ;; access control. The check belongs here once the principal is real.
           amended  (instruction/amend existing changes opts)]
       (assert-debtor-account! tx amended)
       (db/execute! tx
                    ["update payment_instruction
                         set debtor_account_id = ?, creditor_name = ?,
                             creditor_account = ?, amount_minor = ?, currency = ?,
                             value_date = ?, purpose_code = ?
                       where organisation_id = ? and id = ?"
                     (:debtor-account-id amended) (:creditor-name amended)
                     (:creditor-account amended)
                     (:minor-units (:amount amended)) (:currency (:amount amended))
                     (:value-date amended) (:purpose-code amended)
                     organisation-id id])
       amended))))

(defn transition!
  "Apply `event` to an instruction and return it in its new state.

  Read then written under `for update` inside one transaction, so two
  concurrent submissions cannot both succeed: the second blocks on the row,
  re-reads the status the first committed, and is refused by the state machine.
  Without the lock both would read `draft`, both would find `submit` permitted,
  and both would write — which is a payment submitted twice.

  The next state comes from `clofin.payments.state/transition` and from nowhere
  else. This function never asks what state the instruction is in."
  [source organisation-id id event]
  (db/transactionally
   source
   (fn [tx]
     (let [existing (lock-instruction! tx organisation-id id)
           next     (state/transition (:status existing) event)]
       ;; TODO(increment-7): screening gates submission here. `submit` requires
       ;; screening to have completed — a pending screening blocks submission
       ;; rather than queuing behind it (DOMAIN_MODEL §3 rule 1). Until
       ;; increment 7 there is no screening decision to consult, and inventing a
       ;; partial gate would look like a control that does not exist.
       (db/execute! tx ["update payment_instruction set status = ?
                          where organisation_id = ? and id = ?"
                        (name next) organisation-id id])
       (assoc existing :status next)))))
