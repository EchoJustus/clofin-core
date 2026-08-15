(ns clofin.recon.service
  "Reconciliation as units of work: receiving a statement and matching it,
  taking ownership of a break, proposing an adjustment, and posting one once the
  approvals it needs exist.

  Every function here takes a `tx` — a connection inside a transaction the
  *caller* owns — and requires no `clofin.db.*` namespace at all, exactly as
  `clofin.settlement.service` and `clofin.payments.approval-service` do. That is
  the control rather than a style (`ARCHITECTURE.md` §4, [C-05], invariant I9):
  the receipt, the matches, the breaks and the audit events describing them
  commit together or not at all, so an unaudited reconciliation is not
  representable. `clofin.ledger.purity-test` fails the build if this namespace
  acquires a connection, and every function opens with
  `clofin.audit.repository/assert-unit-of-work!`, before its first write, so a
  pool or an autocommit connection is refused there rather than after the first
  row is already durable (standing lesson **L-13**).

  **This namespace decides very little.** Whether a line matches a movement is
  `clofin.recon.matching`; where a break may go is
  `clofin.recon.break-state`; how many approvals an adjustment needs is
  `clofin.recon.adjustment`; whether *this* actor may give one is
  `clofin.authz.approval/evaluate`, unchanged and un-forked. What is left here
  is sequencing, effects, and the one judgement that is genuinely
  reconciliation's: which audit event a given moment deserves.

  ## Nothing here edits a journal entry

  C-03, and it is the reason the module is shaped this way. A disagreement
  between the scheme and CloFin's books changes those books through **one**
  route: a new, balanced, approved entry posted by
  `clofin.ledger.service/post-entry!` — the same function a release goes
  through, with the same domain zero-sum check, the same account lock, the same
  deferred database trigger and the same `journal-entry.posted` event.
  Reconciliation has no private door into the ledger.

  ## Errors, and the one that is a value

  `ingest-statement!` returns a refusal rather than throwing one. Every arrival
  commits its receipt, and the caller renders the error afterwards (audit
  finding **F-008**, standing lesson **L-11**). See that function.

  [C-05]: docs/COMPLIANCE.md"
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.authz.approval :as approval]
            [clofin.authz.repository :as authz]
            [clofin.error :as err]
            [clofin.ledger.service :as ledger-service]
            [clofin.recon.adjustment :as adjustment]
            [clofin.recon.break-state :as break-state]
            [clofin.recon.matching :as matching]
            [clofin.recon.repository :as recon]
            [clofin.recon.statement :as statement]))

(def subject-noun
  "What this service's approvals are about, as it appears in a refusal.
  See `clofin.authz.approval/refusal-detail-templates`."
  "reconciliation adjustment")

(defn- refuse-approval!
  "Turn a refused approval decision into the domain error the HTTP layer
  renders — through the **same** status map and the **same** prose templates
  `clofin.payments.approval-service` uses.

  One maker–checker control, one vocabulary of refusals, one set of answers to
  it. A second copy here would be the drift standing lesson **L-6** names, and
  it would drift in the direction that matters: a refusal an operator cannot
  explain becomes a request to disable the check."
  [{:keys [reason] :as decision}]
  (err/fail! (or (approval/refusal-status reason) :forbidden)
             (or (approval/refusal-detail reason subject-noun) "This approval was refused")
             (-> decision
                 (dissoc :decision)
                 (assoc :reason (name reason)))))

;; ---------------------------------------------------------------------------
;; Ingestion and matching
;; ---------------------------------------------------------------------------

(defn- replay-of
  "The receipt a concurrent identical delivery committed while this one was
  working, or a hard failure.

  Reached only when `insert-statement!` lost the replay key to another
  transaction, which means that transaction committed — so its row is visible
  now. If it somehow is not, this transaction has done work whose receipt does
  not exist, which is the state audit finding **F-008** exists to make
  impossible: fail closed and take the whole transaction down rather than answer
  from nothing."
  [tx organisation-id statement-reference]
  (or (recon/find-statement-by-reference tx organisation-id statement-reference)
      (err/fail!
       :conflict
       (str "This reconciliation statement was processed and its receipt could not "
            "be recorded; the work has been rolled back")
       {:statement-reference statement-reference
        :hint (str "The replay key was taken by a concurrent delivery that then "
                   "disappeared, which the transaction that took it makes "
                   "unreachable.")})))

(defn- replay
  "The answer a stored receipt gives when its exact document arrives again.

  Reproduced from the row, never re-derived. That is the whole of F-008's second
  half: a receipt whose disposition was `refused` answers the same way however
  the world has moved on since — the organisation may have opened the missing
  account in the meantime — and a receipt whose disposition was `applied`
  answers with the matching it recorded rather than with a fresh run against a
  ledger that has moved."
  [source stored]
  {:statement          stored
   :replayed?          true
   :disposition        (:disposition stored)
   :disposition-reason (:disposition-reason stored)
   :detail             (when (statement/refused? (:disposition stored))
                         (statement/refusal-detail (:disposition-reason stored)))
   :matches            (recon/matches-for source (:id stored))
   :breaks             (recon/breaks-for-statement source (:id stored))})

(defn- receipt!
  "Commit one statement receipt and the single audit event that says it arrived.

  Returns the stored row, or nil when the replay key was taken between the read
  at the top of `ingest-statement!` and this insert — which is the concurrent
  duplicate, and which the caller answers by replaying the winner's receipt."
  [tx {:keys [candidate matches actor correlation-id]}]
  (when-let [stored (recon/insert-statement! tx candidate)]
    ;; Same transaction as the rows above (C-05, PR-075, invariant I9).
    (audit-store/record! tx {:organisation-id (:organisation-id stored)
                             :actor-id        (:id actor)
                             :action          "reconciliation-statement.received"
                             :subject-type    "reconciliation-statement"
                             :subject-id      (:id stored)
                             ;; The statement did not exist a moment ago, so
                             ;; there is no before — the same nil that marks
                             ;; every creation in this trail.
                             :before          nil
                             :after           (audit/reconciliation-statement-subject
                                               (assoc stored :matches matches))
                             :correlation-id  correlation-id})
    stored))

(defn- open-breaks!
  "Open one break per disagreement, each with an owner and each with its own
  audit event.

  Assigned to the actor whose ingestion discovered them. A break with no owner
  is the thing PR-052 exists to prevent, and \"unassigned\" is how a break ages
  quietly — so the discovering actor holds it until somebody takes it on, which
  is a real answer rather than a placeholder.

  The ids are generated here rather than supplied by the caller, unlike the
  entry ids `clofin.settlement.service/submit-batch!` demands. Two reasons, and
  the first is decisive: **how many breaks a statement opens is not knowable
  until it has been matched**, so a caller could not supply the right number.
  The second is that a break id is not a ledger identifier a test needs to
  predict — the same judgement `record-scheme-response!` already makes for its
  receipt row."
  [tx {:keys [statement account breaks actor correlation-id]}]
  (mapv (fn [break]
          (let [stored (recon/insert-break!
                        tx {:id               (random-uuid)
                            :organisation-id  (:organisation-id statement)
                            :statement-id     (:id statement)
                            :account-id       (:id account)
                            :kind             (:kind break)
                            :line-no          (:line-no break)
                            :entry-id         (:entry-id break)
                            :currency         (:currency statement)
                            :statement-amount (:statement-amount break)
                            :ledger-amount    (:ledger-amount break)
                            :detail           (:detail break)
                            :assignee-id      (:id actor)})]
            (audit-store/record! tx {:organisation-id (:organisation-id statement)
                                     :actor-id        (:id actor)
                                     :action          "reconciliation-break.opened"
                                     :subject-type    "reconciliation-break"
                                     :subject-id      (:id stored)
                                     :before          nil
                                     :after           (audit/reconciliation-break-subject stored)
                                     :correlation-id  correlation-id})
            stored))
        breaks))

(defn ingest-statement!
  "Receive one synthetic statement, match it against the ledger, and open a
  break for every disagreement — on the caller's transaction.

  Returns
  `{:statement … :replayed? bool :disposition … :disposition-reason … :detail …
    :matches […] :breaks […]}`.

  **It does not throw for a processing refusal.** A refusal is a value, and the
  caller renders the error *after* committing — which is what makes the receipt
  survive it (audit finding **F-008**, standing lesson **L-11**). A statement
  CloFin could not *understand* is a different case and never reaches here:
  `clofin.recon.statement/assert-shape!` throws at the boundary, so the receipt
  table stays a record of deliveries rather than of typos.

  ## The order, and why it is this order

  1. **Look for an existing receipt under this reference**, before doing any
     work rather than after colliding with it.
     - Digest matches: the same document again. Reproduce the stored answer and
       do *no work at all* — no second match, no second break, no second event.
     - Digest differs: two different documents claiming one identity. Refused,
       and **not** a replay — answering `replayed: true` there would tell a
       caller CloFin had already seen a document nobody had sent (**F-009**).
  2. **Resolve the account this statement reconciles.** An organisation with no
     `1300-IN-TRANSIT` in the currency is refused *with a receipt*, because the
     statement did arrive.
  3. **Read the ledger's own account of the period.** More movements than one
     run may cover is likewise a refusal with a receipt: a run that stopped at a
     cap would report breaks for the tail of a period whose movements are right
     there in the journal.
  4. **Match**, purely, and
  5. **write** the receipt, the lines, the matches and the breaks — all on `tx`,
     with one audit event for the arrival and one per break."
  [tx {:keys [organisation-id statement statement-id actor correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
  (let [content-digest (statement/digest statement)
        existing (recon/find-statement-by-reference tx organisation-id
                                                    (:statement-reference statement))]
    (cond
      (and existing (statement/same-message? (:content-digest existing) content-digest))
      (replay tx existing)

      ;; A different document under a taken identity. No work, and no second
      ;; row: the first receipt already stands as the evidence of what arrived,
      ;; and the replay key exists precisely to stop a second one. The code and
      ;; its prose come from `clofin.recon.statement/refusal-reasons` rather than
      ;; being written here — a term defined at its only call site is a term
      ;; nothing can enumerate (audit finding **A-016**).
      existing
      {:statement          existing
       :replayed?          false
       :disposition        "refused"
       :disposition-reason "replay-key-conflict"
       :detail             (statement/refusal-detail "replay-key-conflict")
       :matches            []
       :breaks             []}

      :else
      (let [account (recon/account-by-code tx organisation-id (:currency statement)
                                          (:reconciled adjustment/account-roles))
            refuse! (fn [code]
                      (let [stored (receipt!
                                    tx {:candidate {:id statement-id
                                                    :organisation-id organisation-id
                                                    :scheme (:scheme statement)
                                                    :currency (:currency statement)
                                                    :statement-reference (:statement-reference statement)
                                                    :format (:format statement)
                                                    :format-version (:format-version statement)
                                                    :period-start (:period-start statement)
                                                    :period-end (:period-end statement)
                                                    :content-digest content-digest
                                                    :disposition "refused"
                                                    :disposition-reason code
                                                    :reconciled-account-id (:id account)
                                                    :received-by (:id actor)}
                                        :matches []
                                        :actor actor
                                        :correlation-id correlation-id})]
                        (if stored
                          {:statement stored :replayed? false
                           :disposition "refused" :disposition-reason code
                           :detail (statement/refusal-detail code)
                           :matches [] :breaks []}
                          ;; Lost the race to a concurrent identical delivery.
                          ;; The winner committed before this insert could take
                          ;; the key, so its receipt is visible now and is the
                          ;; answer both callers get.
                          (replay tx (replay-of tx organisation-id
                                                (:statement-reference statement))))))]
        (if-not account
          (refuse! "no-reconciled-account")
          (let [{:keys [expectations truncated?]}
                (recon/expectations-for tx organisation-id account
                                        {:from (:period-start statement)
                                         :to   (:period-end statement)})]
            (if truncated?
              (refuse! "too-many-ledger-movements")
              (let [numbered  (statement/with-line-numbers statement)
                    outcome   (matching/reconcile {:lines (:lines numbered)
                                                   :expectations expectations})
                    matches   (:matches outcome)
                    breaks    (:breaks outcome)
                    stored    (receipt!
                               tx {:candidate {:id statement-id
                                               :organisation-id organisation-id
                                               :scheme (:scheme numbered)
                                               :currency (:currency numbered)
                                               :statement-reference (:statement-reference numbered)
                                               :format (:format numbered)
                                               :format-version (:format-version numbered)
                                               :period-start (:period-start numbered)
                                               :period-end (:period-end numbered)
                                               :content-digest content-digest
                                               :disposition "applied"
                                               :disposition-reason nil
                                               :reconciled-account-id (:id account)
                                               :received-by (:id actor)}
                                   :matches matches
                                   :actor actor
                                   :correlation-id correlation-id})]
                (if-not stored
                  (replay tx (replay-of tx organisation-id
                                        (:statement-reference numbered)))
                  (do
                    (recon/insert-lines! tx (:id stored) (:lines numbered))
                    (recon/insert-matches! tx (:id stored) matches)
                    (let [opened (open-breaks! tx {:statement stored
                                                   :account account
                                                   :breaks breaks
                                                   :actor actor
                                                   :correlation-id correlation-id})]
                      {:statement stored :replayed? false
                       :disposition "applied" :disposition-reason nil
                       :detail nil
                       :matches matches
                       :breaks opened})))))))))))

;; ---------------------------------------------------------------------------
;; Ownership
;; ---------------------------------------------------------------------------

(defn assign-break!
  "Give a break an owner, on the caller's transaction.

  Assigning an `open` break **is** the `:assign` transition — a break becomes
  investigated by somebody taking it on — so one call does one thing and leaves
  one event. Assigning one that is already `investigating` is a reassignment:
  the state does not move, which is why `investigating` is in
  `clofin.recon.break-state/reassignable-states` rather than carrying a
  self-arrow. A `resolved` break is refused by both halves of that rule, which
  is `409` naming what would have been permitted (AC-6).

  The break is read `for update` first: a state decided against a value that
  changed underneath it is validate-then-write, and that is a race (standing
  lesson **L-8**)."
  [tx {:keys [organisation-id break-id assignee-id actor correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
  (let [before   (recon/lock-break! tx organisation-id break-id)
        _        (break-state/assert-assignable! (:state before))
        assignee (or (authz/find-actor tx assignee-id)
                     (err/fail! :unprocessable
                                "No such actor to assign this break to"
                                {:assignee-id (str assignee-id)}))
        _        (when-not (= organisation-id (:organisation-id assignee))
                   ;; Reported as unknown rather than as forbidden, for the
                   ;; reason `clofin.ledger.repository/assert-postable!` gives:
                   ;; saying "another organisation's" would confirm that the id
                   ;; names a real actor somewhere else.
                   (err/fail! :unprocessable
                              "No such actor to assign this break to"
                              {:assignee-id (str assignee-id)}))
        next-state (if (break-state/permitted? (:state before) :assign)
                     (break-state/transition (:state before) :assign)
                     (:state before))
        after    (recon/set-break-state! tx organisation-id break-id
                                         {:state next-state :assignee-id assignee-id})]
    (audit-store/record! tx {:organisation-id organisation-id
                             :actor-id        (:id actor)
                             :action          "reconciliation-break.assigned"
                             :subject-type    "reconciliation-break"
                             :subject-id      break-id
                             :before          (audit/reconciliation-break-subject before)
                             :after           (audit/reconciliation-break-subject after)
                             :correlation-id  correlation-id})
    {:break after :assignee assignee}))

;; ---------------------------------------------------------------------------
;; Adjustments
;; ---------------------------------------------------------------------------

(defn- resolve-adjustment-accounts
  "Map each role in `clofin.recon.adjustment/account-roles` to an account id, or
  refuse naming the gap.

  Matched on **code and currency**, and refusing with the missing codes named,
  for the reason `clofin.settlement.service/resolve-accounts` gives: it is the
  difference between an operator opening an account and an operator reading a
  stack trace."
  [tx organisation-id currency]
  (let [resolved (into {}
                       (keep (fn [[role code]]
                               (when-let [account (recon/account-by-code
                                                   tx organisation-id currency code)]
                                 [role (:id account)])))
                       adjustment/account-roles)]
    (when-not (= (count resolved) (count adjustment/account-roles))
      (err/fail! :unprocessable
                 "This organisation has no accounts to post a reconciliation adjustment to"
                 {:currency currency
                  :missing (mapv (fn [[role code]] {:role (name role) :code code})
                                 (remove (comp resolved key) adjustment/account-roles))}))
    resolved))

(defn- post-adjustment!
  "Post an adjustment's entry, resolve its break, and say so — all in one
  transaction.

  The order is the interesting part and is stated once, here:

  1. **Post the entry** through `clofin.ledger.service/post-entry!` — the
     existing path, which locks the accounts, checks the zero sum, arms the
     deferred trigger and writes `journal-entry.posted`.
  2. **Claim the posting** (`mark-posted!`, `where status = 'proposed'`). A
     check-then-post is a race, and the window between them is exactly long
     enough for a concurrent approval to post the same adjustment twice.
  3. **Resolve the break**, through the lifecycle table rather than by writing a
     status.
  4. **Say what happened**: one event for the adjustment, one for the break.

  **The entry is posted before the claim, and the claim is still what makes it
  exactly once.** `reconciliation_adjustment.entry_id` is a foreign key to
  `journal_entry`, so a claim naming an entry that does not exist yet is a row
  the schema refuses — and it is right to: an adjustment pointing at nothing
  would be a correction nobody could read. The ordering costs nothing, because
  all of this is one transaction: a claim that finds the adjustment already
  posted throws, and the entry rolls back with everything else. Two callers
  cannot even reach here concurrently — both hold the break's row lock (lock
  order step 3) before either reads the adjustment."
  [tx {:keys [organisation-id adjustment break actor correlation-id entry-id occurred-at]}]
  (let [accounts (resolve-adjustment-accounts tx organisation-id (:currency (:amount adjustment)))
        entry    (adjustment/adjustment-entry adjustment {:accounts    accounts
                                                          :entry-id    entry-id
                                                          :occurred-at occurred-at})
        posted   (ledger-service/post-entry! tx {:entry          entry
                                                 :actor-id       (:id actor)
                                                 :correlation-id correlation-id})
        claimed  (or (recon/mark-posted! tx organisation-id (:id adjustment) (:id posted))
                     (err/conflict!
                      "This reconciliation adjustment has already been posted"
                      {:adjustment-id (str (:id adjustment))}))
        resolved (recon/set-break-state! tx organisation-id (:id break)
                                         {:state (break-state/transition (:state break) :resolve)
                                          :assignee-id (:assignee-id break)})]
    (audit-store/record! tx {:organisation-id organisation-id
                             :actor-id        (:id actor)
                             :action          "reconciliation-adjustment.posted"
                             :subject-type    "reconciliation-adjustment"
                             :subject-id      (:id adjustment)
                             :before          (audit/reconciliation-adjustment-subject adjustment)
                             :after           (audit/reconciliation-adjustment-subject claimed)
                             :correlation-id  correlation-id})
    (audit-store/record! tx {:organisation-id organisation-id
                             :actor-id        (:id actor)
                             :action          "reconciliation-break.resolved"
                             :subject-type    "reconciliation-break"
                             :subject-id      (:id break)
                             :before          (audit/reconciliation-break-subject break)
                             :after           (audit/reconciliation-break-subject resolved)
                             :correlation-id  correlation-id})
    {:adjustment claimed :break resolved :entry posted :posted? true :rejected? false}))

(defn propose-adjustment!
  "Raise an adjustment against a break, and post it immediately when the
  organisation's own bands say it needs nobody else — on the caller's
  transaction.

  Returns `{:adjustment … :break … :entry … :posted? bool
            :approvals-required n}`.

  Below the lowest band the organisation configured for the currency, one actor
  suffices and the adjustment posts here. At or above it, the adjustment is
  `proposed` and waits for approvals from actors who are **not** its proposer.
  An organisation with no band in the currency cannot adjust at all — see
  `clofin.recon.adjustment/approvals-required` for why \"unconfigured\" must not
  read as \"needs nobody\".

  The count is stored on the adjustment at proposal, so a later change to the
  bands cannot retrospectively lower the bar an adjustment has already cleared."
  [tx {:keys [organisation-id break-id adjustment-id amount direction narrative
              actor correlation-id entry-id occurred-at]}]
  (audit-store/assert-unit-of-work! tx)
  (let [break (recon/lock-break! tx organisation-id break-id)
        _     (when (break-state/terminal? (:state break))
                (err/conflict!
                 (str "Cannot adjust a reconciliation break that is " (name (:state break)))
                 {:break-state (name (:state break)) :attempted "adjust"}))
        _     (when-not (= (:currency break) (:currency amount))
                (err/fail! :unprocessable
                           "An adjustment is denominated in the currency of the break it resolves"
                           {:break-currency (:currency break)
                            :adjustment-currency (:currency amount)}))
        thresholds (authz/thresholds-for tx organisation-id (:currency amount))
        required   (or (adjustment/approvals-required thresholds amount)
                       (err/fail! :unprocessable
                                  (str "No approval threshold is configured for this "
                                       "organisation and currency, so no adjustment can be "
                                       "posted — an unconfigured currency is not an "
                                       "unsupervised one")
                                  {:currency (:currency amount)
                                   :reason (name :no-threshold-configured)
                                   :configured (authz/currencies-with-thresholds
                                                tx organisation-id)}))
        stored (recon/insert-adjustment! tx {:id                 adjustment-id
                                             :organisation-id    organisation-id
                                             :break-id           break-id
                                             :amount             (adjustment/assert-amount! amount)
                                             :direction          (adjustment/assert-direction! direction)
                                             :narrative          (adjustment/assert-narrative! narrative)
                                             :approvals-required required
                                             :created-by         (:id actor)})]
    (audit-store/record! tx {:organisation-id organisation-id
                             :actor-id        (:id actor)
                             :action          "reconciliation-adjustment.proposed"
                             :subject-type    "reconciliation-adjustment"
                             :subject-id      adjustment-id
                             :before          nil
                             :after           (audit/reconciliation-adjustment-subject stored)
                             :correlation-id  correlation-id})
    (if (zero? required)
      (assoc (post-adjustment! tx {:organisation-id organisation-id
                                   :adjustment      stored
                                   :break           break
                                   :actor           actor
                                   :correlation-id  correlation-id
                                   :entry-id        entry-id
                                   :occurred-at     occurred-at})
             :approvals-required required)
      {:adjustment stored :break break :entry nil :posted? false :rejected? false
       :approvals-required required})))

(defn- reject-adjustment!
  "Refuse an adjustment, and say so — in the transaction that refuses it.

  Three facts, and the order is the interesting part:

  1. **Claim the refusal** (`mark-rejected!`, `where status = 'proposed'`), for
     the same reason `post-adjustment!` claims the posting in a statement rather
     than after a read: a check-then-write is a race, and the window is exactly
     long enough for a concurrent approval to post the adjustment being refused.
  2. **Say what happened** — one event, whose subject is the adjustment, in the
     transaction where it reached a terminal status (**L-7**). The
     `approval.recorded` event for the decision itself is written by the caller
     and is a different fact about a different subject.
  3. **Leave the break alone.** Deliberately, and it is the half worth stating:
     proposing an adjustment never moved the break, so a refused adjustment
     returns it to nothing — it is in the state it was in, and a different
     adjustment may be raised against it because `recon_adjustment_posted_key`
     is partial on `posted` and a rejected row is outside it. The break is
     returned as it was read, under its lock, so a caller renders the state it
     actually has rather than the state a rejection might have implied.

  Nothing posts. There is no entry, and `recon_adjustment_posting_paired`
  already required that of anything that is not `posted`."
  [tx {:keys [organisation-id adjustment break actor correlation-id]}]
  (let [rejected (or (recon/mark-rejected! tx organisation-id (:id adjustment))
                     (err/conflict!
                      "This reconciliation adjustment is no longer awaiting a decision"
                      {:adjustment-id (str (:id adjustment))}))]
    (audit-store/record! tx {:organisation-id organisation-id
                             :actor-id        (:id actor)
                             :action          "reconciliation-adjustment.rejected"
                             :subject-type    "reconciliation-adjustment"
                             :subject-id      (:id adjustment)
                             :before          (audit/reconciliation-adjustment-subject adjustment)
                             :after           (audit/reconciliation-adjustment-subject rejected)
                             :correlation-id  correlation-id})
    {:adjustment rejected :break break :entry nil :posted? false :rejected? true}))

(defn decide-adjustment!
  "Record one actor's decision on an adjustment and act on it — post it when the
  approvals it needs now exist, refuse it when the decision is a rejection — on
  the caller's transaction.

  Returns `{:approval … :adjustment … :break … :entry … :posted? bool
            :rejected? bool :approvals-held n :approvals-required n}`.

  ## What is reused, and the two things that are not

  **The decision is `clofin.authz.approval/evaluate`, unchanged.** Self-approval
  is refused first and never waivably — the actor who proposed the adjustment
  may not approve *or reject* it, which is C-01's own comparison against
  `created-by` applied to a different subject. The approver's per-currency
  ceiling (C-02) and their `:payment/approve` permission (C-08) apply exactly as
  they do to a payment; a rejection is judged against `:payment/reject` and
  needs neither a ceiling nor a band, because refusing a payment is not an
  exercise of spending authority. The row lands in the same `approval` table
  under the same no-delete guarantee, and `assert-reason!` — the same function,
  and `approval_rejection_needs_reason` behind it — makes the reason mandatory
  on a rejection here exactly as it is on a payment's.

  **The count comes from the adjustment, not from `evaluate`.** `evaluate`
  answers *may this actor decide*, and its `:completes?` is computed against the
  bands as they stand **now**; an adjustment carries the requirement computed
  when it was proposed. Reading the stored number is what stops an organisation
  lowering a band and thereby posting an adjustment that never cleared the bar
  it was raised under. A **rejection** is exempt from the count and always
  terminal: one refusal ends the adjustment, which is the same rule a rejected
  payment follows and is `evaluate`'s own `:completes?` for a rejection.

  **Where it may go next is the lifecycle table**, asked before anything is said
  about the actor. `clofin.recon.adjustment/transition` refuses a decision on an
  adjustment that has already posted or already been refused, with a `409`
  naming what would have been permitted — the same ordering
  `clofin.payments.approval-service/decide!` uses, and for the same reason: a
  decision on a finished adjustment is a conflict whoever sent it, and answering
  `403` first would suggest that fixing permissions would help.

  ## Lock order

  The adjustment is *addressed* by an unlocked read, then the **break** is
  locked, then the adjustment is re-read under its own lock —
  `clofin.recon.repository`'s documented order, steps 3 then 4. Locking the
  adjustment first to find out which break it belongs to would take the two row
  types in the opposite order from `propose-adjustment!`, and two operations on
  overlapping rows locked in opposite orders deadlock."
  [tx {:keys [organisation-id adjustment-id approval-id actor decision reason
              correlation-id entry-id occurred-at]}]
  (audit-store/assert-unit-of-work! tx)
  (let [decision   (or decision :approved)
        event      (or (adjustment/decision-events decision)
                       (err/invalid! (str "Unknown approval decision: " decision)
                                     {:decision (str decision)
                                      :known (mapv name (sort (keys adjustment/decision-events)))}))
        addressed  (or (recon/find-adjustment tx organisation-id adjustment-id)
                       (err/not-found! "No such reconciliation adjustment in this organisation"
                                       {:id (str adjustment-id)}))
        break      (recon/lock-break! tx organisation-id (:break-id addressed))
        proposal   (recon/lock-adjustment! tx organisation-id adjustment-id)
        ;; The lifecycle first, from the table rather than from a status test
        ;; written here. Its return value is deliberately discarded: what is
        ;; wanted is the refusal, and where the adjustment actually goes is
        ;; decided below by whether this decision completes the requirement.
        _          (adjustment/transition (:status proposal) event)
        _          (approval/assert-reason! decision reason)
        existing   (authz/approvals-for-adjustment tx adjustment-id)
        thresholds (authz/thresholds-for tx organisation-id (:currency (:amount proposal)))
        outcome    (approval/evaluate {:instruction        proposal
                                       :actor              actor
                                       :existing-approvals existing
                                       :thresholds         thresholds
                                       :decision           decision})
        _          (when (= :refused (:decision outcome)) (refuse-approval! outcome))
        recorded   (authz/record-approval! tx {:id            approval-id
                                               :adjustment-id adjustment-id
                                               :actor-id      (:id actor)
                                               :decision      decision
                                               :reason        reason})
        held       (count (approval/live-approvals (conj (vec existing) recorded)))
        outstanding {:approval recorded
                     :approvals-held held
                     :approvals-required (:approvals-required proposal)}]
    ;; One event per thing that happened, which is the whole of standing lesson
    ;; **L-7**. A decision was recorded — always, and its subject is the
    ;; *approval*, the record that came into existence. The adjustment posting,
    ;; the adjustment's refusal and the break resolving are separate facts about
    ;; separate subjects and get their own events below, only when they happen.
    (audit-store/record! tx {:organisation-id organisation-id
                             :actor-id        (:id actor)
                             :action          "approval.recorded"
                             :subject-type    "approval"
                             :subject-id      approval-id
                             :before          nil
                             :after           (audit/approval-subject recorded)
                             :correlation-id  correlation-id})
    (cond
      (= :rejected decision)
      (merge (reject-adjustment! tx {:organisation-id organisation-id
                                     :adjustment      proposal
                                     :break           break
                                     :actor           actor
                                     :correlation-id  correlation-id})
             outstanding)

      (>= held (:approvals-required proposal))
      (merge (post-adjustment! tx {:organisation-id organisation-id
                                   :adjustment      proposal
                                   :break           break
                                   :actor           actor
                                   :correlation-id  correlation-id
                                   :entry-id        entry-id
                                   :occurred-at     occurred-at})
             outstanding)

      :else
      (merge {:adjustment proposal :break break :entry nil :posted? false :rejected? false}
             outstanding))))
