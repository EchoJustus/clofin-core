(ns clofin.api.reconciliation
  "Reconciliation endpoints: receiving a synthetic statement and matching it,
  working the breaks that come out, and correcting the books by adjustment.

  > CloFin is not connected to any bank, payment scheme or central bank, and it
  > reads no real statement format. `POST /reconciliation-statements` accepts
  > CloFin's **own** versioned format, `SIM-CLOFIN-RECON-STATEMENT`, and the
  > only producer of one is CloFin's own simulator at
  > `GET /settlement-statements`. There is no listener, no file drop and no
  > correspondent. The API contract says so to a caller as well.

  Nothing here decides anything. Which line is which movement is
  `clofin.recon.matching`; where a break may go is `clofin.recon.break-state`;
  how many approvals an adjustment needs is `clofin.recon.adjustment`; whether
  an actor may give one is `clofin.authz.approval/evaluate`. This layer reads a
  request, opens the transaction the unit of work needs, and renders what comes
  back.

  What each status code means here (ADR-0012):

  - `400` — the request could not be understood: an unknown format, a malformed
    UUID, a period that ends before it begins, a scheme that is not a simulated
    one.
  - `401` / `403` — no actor, or an actor without the permission. Reads need
    `:reconciliation/read`; ingesting, assigning and proposing need
    `:reconciliation/execute`. Deciding on an adjustment names **no** permission
    at the boundary, for the reason `clofin.api.principal/authenticated-for`
    gives: `evaluate` ranks its refusals deliberately — and checks
    `:payment/approve` or `:payment/reject` according to the decision — and a
    boundary check would tell a proposer that fixing their permissions would
    help when the reason that governs is segregation of duties.
  - `409` — a statement reference already names a different document, a break is
    not in a state that permits this, or an adjustment has already reached a
    terminal status. On `ingestReconciliationStatement` this status is rendered
    **after** the transaction commits, so the refused statement's receipt
    survives its own refusal (audit finding **F-008**, standing lesson **L-11**).
  - `422` — understood, and the organisation is not set up for it: no
    `1300-IN-TRANSIT` in the statement's currency, no `2200-UNAPPLIED` to post
    an adjustment to, no approval band configured — or a rejection sent with no
    reason.

  **No `Idempotency-Key` header**, and that is a decision rather than an
  omission. Replay protection here is the statement's own identity — its
  reference — plus a canonical digest of every effect-bearing field it carries,
  which is a *stronger* guarantee than a caller-chosen header: two callers
  delivering the same document under different keys are still one delivery.
  The settlement endpoints made the same choice for scheme responses, and
  standing lesson **L-14** is the record of PR-040's \"every mutating
  operation\" being read as covering handler families that never took one.

  The transaction is opened here because something must and a service may not
  (`ARCHITECTURE.md` §4). Every one of these operations is a state change, a
  posting or a receipt, and an audit event, and they commit together or not at
  all (C-05, I9)."
  (:require [clofin.api.principal :as principal]
            [clofin.api.wire :as wire]
            [clofin.authz.approval :as approval]
            [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clofin.money :as money]
            [clofin.recon.adjustment :as adjustment]
            [clofin.recon.break-state :as break-state]
            [clofin.recon.matching :as matching]
            [clofin.recon.repository :as recon]
            [clofin.recon.service :as service]
            [clofin.recon.statement :as statement]
            [clofin.settlement.batch :as batch]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Wire
;; ---------------------------------------------------------------------------

(defn- line->wire
  [line]
  (cond-> {"lineNo"          (:line-no line)
           "schemeReference" (:scheme-reference line)
           "lineType"        (:line-type line)
           "amount"          (money/->wire (:amount line))
           "valueDate"       (str (:value-date line))}
    (:payment-reference line) (assoc "paymentReference" (:payment-reference line))))

(defn- match->wire
  "One match, carrying the rule that produced it.

  `rule` is rendered on every match rather than only where it is interesting:
  PR-051 asks that matching record *which* rule matched, and a consumer that had
  to infer the rule from the absence of a break would be inferring the very
  thing the record exists to state."
  [match]
  {"lineNo"    (:line-no match)
   "entryId"   (str (:entry-id match))
   "rule"      (:rule-id match)
   "matchedAt" (str (:matched-at match))})

(defn- break->wire
  "One break, with its **derived** age.

  `ageSeconds` is computed when the row is read and is stored nowhere — a stored
  age is wrong the moment it is written. It is rendered on every break because
  age is half of what makes a break workable: a queue that shows what is wrong
  and not how long it has been wrong is a queue that ages quietly, which is the
  failure PR-052 exists to prevent.

  `instructionId` and `retriedByInstructionIds` are derived the same way and
  answer the question an investigator holding a break about a **returned**
  payment asks first: which payment is this, and has it been raised again?
  Before ADR-0024 the answer was reachable only by matching counterparty and
  amount by eye. `retriedByInstructionIds` is a list because the linkage
  deliberately carries no uniqueness rule — see ADR-0024 — and is rendered only
  when there is something in it, so an ordinary break carries no empty array."
  [brk]
  (cond-> {"id"          (str (:id brk))
           "statementId" (str (:statement-id brk))
           "accountId"   (str (:account-id brk))
           "kind"        (:kind brk)
           "state"       (name (:state brk))
           "detail"      (:detail brk)
           "assigneeId"  (str (:assignee-id brk))
           "openedAt"    (str (:opened-at brk))
           "ageSeconds"  (:age-seconds brk)
           ;; Derived from the lifecycle table, so the API cannot advertise an
           ;; operation the state machine would refuse — the same courtesy
           ;; `instruction->wire` extends (ADR-0014).
           "permittedTransitions" (mapv name (break-state/permitted-events (:state brk)))}
    (:line-no brk)          (assoc "lineNo" (:line-no brk))
    (:entry-id brk)         (assoc "entryId" (str (:entry-id brk)))
    (:statement-amount brk) (assoc "statementAmount" (money/->wire (:statement-amount brk)))
    (:ledger-amount brk)    (assoc "ledgerAmount" (money/->wire (:ledger-amount brk)))
    (:resolved-at brk)      (assoc "resolvedAt" (str (:resolved-at brk)))
    (:instruction-id brk)   (assoc "instructionId" (str (:instruction-id brk)))
    (seq (:retried-by-ids brk))
    (assoc "retriedByInstructionIds" (mapv str (:retried-by-ids brk)))))

(defn- adjustment->wire
  "One adjustment, with the moves its status still allows.

  `permittedTransitions` is derived from `clofin.recon.adjustment/transitions`,
  so the API cannot advertise a decision the lifecycle would refuse — the same
  courtesy `instruction->wire` and `break->wire` extend (ADR-0014). It is what
  tells a caller holding a `proposed` adjustment that both `post` and `reject`
  are open to it, and a caller holding a `rejected` one that nothing is."
  [adj]
  (cond-> {"id"                 (str (:id adj))
           "breakId"            (str (:break-id adj))
           "amount"             (money/->wire (:amount adj))
           "direction"          (name (:direction adj))
           "narrative"          (:narrative adj)
           "status"             (name (:status adj))
           "approvalsRequired"  (:approvals-required adj)
           "createdBy"          (str (:created-by adj))
           "createdAt"          (str (:created-at adj))
           "permittedTransitions" (mapv name (adjustment/permitted-events (:status adj)))}
    (:entry-id adj)  (assoc "entryId" (str (:entry-id adj)))
    (:posted-at adj) (assoc "postedAt" (str (:posted-at adj)))))

(defn- statement->wire
  [stmt]
  (cond-> {"id"                  (str (:id stmt))
           "organisationId"      (str (:organisation-id stmt))
           "scheme"              (:scheme stmt)
           "currency"            (:currency stmt)
           "statementReference"  (:statement-reference stmt)
           "format"              (:format stmt)
           "formatVersion"       (:format-version stmt)
           "periodStart"         (str (:period-start stmt))
           "periodEnd"           (str (:period-end stmt))
           "disposition"         (:disposition stmt)
           "receivedAt"          (str (:received-at stmt))
           "receivedBy"          (str (:received-by stmt))
           "simulated"           true}
    (:disposition-reason stmt)    (assoc "dispositionReason" (:disposition-reason stmt))
    (:reconciled-account-id stmt) (assoc "reconciledAccountId"
                                         (str (:reconciled-account-id stmt)))))

(defn- statement-document
  "A received statement, its lines, its matches and the breaks it opened.

  The breaks are surfaced as their own list rather than left for a caller to
  assemble: a break is the thing an operator has to act on, and an exception
  queue that has to be built client-side is one each client builds
  differently."
  [source stmt]
  (assoc (statement->wire stmt)
         "lines"      (mapv line->wire (recon/lines-for source (:id stmt)))
         "matches"    (mapv match->wire (recon/matches-for source (:id stmt)))
         "breaks"     (mapv break->wire (recon/breaks-for-statement source (:id stmt)))))

;; ---------------------------------------------------------------------------
;; Reading a request
;; ---------------------------------------------------------------------------

(defn- optional-object
  "The decoded body, or `{}` when there is none."
  [request]
  (let [body (:json-body request)]
    (cond
      (nil? body) {}
      (map? body) body
      :else (err/invalid! "Request body must be a JSON object when one is sent"
                          {:content-type (get-in request [:headers "content-type"])}))))

(defn- read-statement!
  "The statement document, validated into a domain value.

  Two checks, in two places, deliberately. `assert-shape!` is the format's own —
  it knows nothing about which schemes exist and refuses anything that is not
  `SIM-` prefixed — and `batch/assert-scheme!` is the settlement vocabulary's,
  applied here at the boundary because that is where a refusal a caller can act
  on belongs. Reconciliation does not require the settlement context (that would
  make two bounded contexts depend on each other); the API layer, which is
  outside the context roster, is exactly where the two meet."
  [body]
  (let [parsed (statement/assert-shape! body)]
    (batch/assert-scheme! (:scheme parsed))
    parsed))

(defn- read-amount!
  [body]
  (let [raw (get body "amount")]
    (when-not (map? raw)
      (err/invalid! "Field 'amount' must be an amount object with 'currency' and 'minorUnits'"
                    {:field "amount"}))
    (money/wire-> raw)))

(defn- read-optional-uuid
  [request param]
  (some-> (wire/read-optional-query-param request param) (wire/read-uuid param)))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn ingest
  "`POST /reconciliation-statements` — receive a synthetic statement, match it
  against the ledger, and open a break for every disagreement (PR-050, PR-051,
  PR-052).

  A duplicate delivery — the same reference carrying the same document — is
  answered `200` with `replayed: true` and does no work: no second match, no
  second break, no second audit event. That is the normal case in the world this
  simulates, not an error, so it is not reported as one, and the body it returns
  is the *original* answer.

  **The refusal is rendered here, after the transaction has committed**, and
  that ordering is standing lesson **L-11**. The service returns a refusal as a
  value; the receipt for the refused arrival is inside the transaction this
  handler commits; only then does the error throw. Doing it the other way round
  would roll the receipt back with the rejection, so the first delivery would be
  unprovable — which is precisely what audit finding **F-008** found in
  settlement."
  [pool]
  (fn [request]
    (let [body (wire/read-object request)
          [actor organisation-id]
          (principal/for-request pool request :reconciliation/execute body)
          parsed (read-statement! body)
          result (db/with-transaction [tx pool]
                   (service/ingest-statement! tx {:organisation-id organisation-id
                                                  :statement       parsed
                                                  :statement-id    (random-uuid)
                                                  :actor           actor
                                                  :correlation-id  (:correlation-id request)}))]
      ;; Committed. The receipt exists whichever branch follows.
      (when (statement/refused? (:disposition result))
        (let [reason (:disposition-reason result)
              detail {:statementReference (:statement-reference parsed)
                      :disposition        (:disposition result)
                      :dispositionReason  reason
                      ;; True when this exact document has been refused before:
                      ;; the caller is being told the same answer as last time,
                      ;; not a fresh evaluation against state that has moved.
                      :replayed           (boolean (:replayed? result))
                      :receiptId          (some-> (:statement result) :id str)}]
          (err/fail! (if (= "replay-key-conflict" reason) :conflict :unprocessable)
                     (:detail result)
                     detail)))
      (resp/ok (assoc (statement-document pool (:statement result))
                      "replayed" (boolean (:replayed? result)))))))

(defn show-statement
  "`GET /reconciliation-statements/:id` — the statement, its lines, its matches
  and the breaks it opened."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :reconciliation/read)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")]
      (if-let [found (recon/find-statement pool organisation-id id)]
        (resp/ok (statement-document pool found))
        (err/not-found! "No such reconciliation statement in this organisation"
                        {:id (str id)})))))

(defn index-breaks
  "`GET /reconciliation-breaks` — an organisation's breaks, **oldest first**.

  Oldest first is the only list in CloFin ordered that way, and it is the
  product point: a break found in March may have originated in January (PRD
  §2), so the queue that buries the oldest item is the spreadsheet this module
  replaces.

  Narrowed by `?state=`, `?kind=`, `?accountId=` and `?assigneeId=`. Capped
  rather than paginated, with the cap and a `truncated` flag on every response
  (ADR-0011)."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :reconciliation/read)
          {:keys [breaks truncated?]}
          (recon/list-breaks pool organisation-id
                             {:state       (wire/read-optional-query-param request "state")
                              :kind        (wire/read-optional-query-param request "kind")
                              :account-id  (read-optional-uuid request "accountId")
                              :assignee-id (read-optional-uuid request "assigneeId")})]
      (resp/ok {"reconciliationBreaks" (mapv break->wire breaks)
                "count"     (count breaks)
                "limit"     recon/row-cap
                "truncated" (boolean truncated?)
                "kinds"     (vec matching/break-kinds)
                "states"    (mapv name break-state/states)}))))

(defn show-break
  "`GET /reconciliation-breaks/:id` — one break and every adjustment raised
  against it."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :reconciliation/read)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")]
      (if-let [found (recon/find-break pool organisation-id id)]
        (resp/ok (assoc (break->wire found)
                        "adjustments" (mapv adjustment->wire
                                            (recon/adjustments-for-break pool id))))
        (err/not-found! "No such reconciliation break in this organisation" {:id (str id)})))))

(defn assign-break
  "`POST /reconciliation-breaks/:id/assignment` — give a break an owner.

  Assigning an `open` break is the `:assign` transition — it is how a break
  becomes investigated — and assigning one that is already `investigating` is a
  reassignment that leaves the state where it is. One endpoint drives both by
  reading `clofin.recon.break-state`'s two values rather than by testing a
  status, which is the arrangement `PATCH /payment-instructions/{id}` already
  uses for amend-versus-`:amend` (ADR-0014). A `resolved` break is refused
  `409`."
  [pool]
  (fn [request]
    (let [body (wire/read-object request)
          [actor organisation-id]
          (principal/for-request pool request :reconciliation/execute body)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")
          assignee-id (wire/read-uuid-field body "assigneeId")
          {:keys [break]}
          (db/with-transaction [tx pool]
            (service/assign-break! tx {:organisation-id organisation-id
                                       :break-id        id
                                       :assignee-id     assignee-id
                                       :actor           actor
                                       :correlation-id  (:correlation-id request)}))]
      (resp/ok (break->wire break)))))

(defn propose-adjustment
  "`POST /reconciliation-breaks/:id/adjustments` — correct the books (PR-053).

  Below the lowest approval band the organisation configured for the currency,
  the proposer alone may post and the entry lands in this call. At or above it
  the adjustment is `proposed` and needs approvals from actors who are **not**
  its proposer; `201` either way, with `posted` saying which happened.

  Nothing here edits a journal entry (C-03). The correction is a new balanced
  entry posted through `clofin.ledger.service/post-entry!` — the same path a
  release takes."
  [pool]
  (fn [request]
    (let [body (wire/read-object request)
          [actor organisation-id]
          (principal/for-request pool request :reconciliation/execute body)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")
          amount    (read-amount! body)
          direction (adjustment/assert-direction!
                     (wire/read-enum (get body "direction") "direction"
                                     adjustment/directions))
          narrative (get body "narrative")
          result (db/with-transaction [tx pool]
                   (service/propose-adjustment!
                    tx {:organisation-id organisation-id
                        :break-id        id
                        :adjustment-id   (random-uuid)
                        :amount          amount
                        :direction       direction
                        :narrative       narrative
                        :actor           actor
                        :correlation-id  (:correlation-id request)
                        ;; One entry id and one instant, generated here: the
                        ;; domain generates no identifiers and reads no clock
                        ;; (ARCHITECTURE.md §4). Unused when the adjustment
                        ;; waits for approval.
                        :entry-id        (random-uuid)
                        :occurred-at     (java.time.Instant/now)}))]
      (resp/created (str "/reconciliation-breaks/" id)
                    (assoc (adjustment->wire (:adjustment result))
                           "posted" (boolean (:posted? result))
                           "break"  (break->wire (:break result)))))))

(defn decide-adjustment
  "`POST /reconciliation-adjustments/:id/approvals` — a second, different actor
  agrees, or refuses (PR-053, C-01, C-02, C-05).

  `decision` is `approved` or `rejected` and defaults to `approved`, which is
  the member and the default `POST /payment-instructions/{id}/approvals` already
  uses. One vocabulary for one maker–checker control: an approver deciding about
  an adjustment sends what they would send about a payment, and a `reason` is
  **required** when they refuse — a rejection whose reason is retained is the
  difference between a trail that explains a refused correction and one that
  merely records that somebody refused it.

  A rejection is terminal for the adjustment and leaves the break exactly where
  it was, so a different adjustment may be raised against it. Before ADR-0025
  there was no way to say no at all: an approver who disagreed simply did not
  answer, the proposal sat `proposed` for ever, and nothing recorded that
  anybody had considered it.

  No permission is checked at this boundary. `clofin.authz.approval/evaluate`
  checks `:payment/approve` — or `:payment/reject` — **and** the maker, the
  limit and the count, and it ranks those reasons deliberately — segregation of
  duties first, because it is the only refusal that can never be resolved. A
  boundary check would pre-empt that ranking and tell the adjustment's own
  proposer that fixing their permissions would help.

  The adjustment posts in the same transaction as the approval that completes
  its requirement, so an approved-but-unposted adjustment is not a state that
  exists."
  [pool]
  (fn [request]
    (let [body (optional-object request)
          [actor organisation-id] (principal/authenticated-for pool request body)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")
          ;; Absent means `approved`: this endpoint accepted no decision at all
          ;; before ADR-0025, and every caller written against it sends a body
          ;; with no `decision` member — or no body. A default that changed
          ;; their meaning would be a contract break dressed as a feature.
          decision (if (contains? body "decision")
                     (wire/read-enum (get body "decision") "decision" approval/decisions)
                     :approved)
          reason (let [r (get body "reason")]
                   (when (and (string? r) (not (str/blank? r))) (str/trim r)))
          result (db/with-transaction [tx pool]
                   (service/decide-adjustment!
                    tx {:organisation-id organisation-id
                        :adjustment-id   id
                        :approval-id     (random-uuid)
                        :actor           actor
                        :decision        decision
                        :reason          reason
                        :correlation-id  (:correlation-id request)
                        :entry-id        (random-uuid)
                        :occurred-at     (java.time.Instant/now)}))]
      (resp/created (str "/reconciliation-adjustments/" id)
                    {"approval"           (wire/approval->wire (:approval result))
                     "adjustment"         (adjustment->wire (:adjustment result))
                     "break"              (break->wire (:break result))
                     "posted"             (boolean (:posted? result))
                     "rejected"           (boolean (:rejected? result))
                     "approvalsHeld"      (:approvals-held result)
                     "approvalsRequired"  (:approvals-required result)}))))

(defn status
  "`GET /reconciliation-status` — matched, unmatched and breaks by state, for an
  account and a period (PR-054).

  The period is half-open, `[from, to)`, the convention every other CloFin
  period uses (ADR-0011). A statement belongs to it when **its own period lies
  inside** the requested one: a statement covering the last week of July and the
  first of August is not an August statement, and counting half of it would
  produce a figure that agrees with no document.

  Every figure is counted by the database over the rows themselves rather than
  assembled from a capped list, so the answer stays correct for a period whose
  breaks would not fit in one page."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :reconciliation/read)
          account-id (wire/read-uuid (wire/read-query-param request "accountId") "accountId")
          from (wire/read-instant (wire/read-query-param request "from") "from")
          to   (wire/read-instant (wire/read-query-param request "to") "to")
          _ (when-not (.isBefore from to)
              (err/invalid! "The period's start must be before its end"
                            {:from (str from) :to (str to)}))
          result (recon/status-for pool organisation-id account-id {:from from :to to})]
      (resp/ok {"accountId"  (str account-id)
                "from"       (str from)
                "to"         (str to)
                "statements" {"received" (get-in result [:statements :received])
                              "applied"  (get-in result [:statements :applied])
                              "refused"  (get-in result [:statements :refused])}
                "lines"      {"total"     (get-in result [:lines :total])
                              "matched"   (get-in result [:lines :matched])
                              "unmatched" (get-in result [:lines :unmatched])}
                ;; Every rule and every kind is present with a zero rather than
                ;; absent, so a consumer reads "no breaks of this kind" rather
                ;; than having to distinguish that from "this build does not
                ;; know this kind".
                "matchesByRule"  (reduce (fn [acc rule] (update acc rule (fnil identity 0)))
                                         (:matches-by-rule result)
                                         matching/rule-ids)
                "breaksByState"  (reduce (fn [acc s] (update acc (name s) (fnil identity 0)))
                                         (:breaks-by-state result)
                                         break-state/states)
                "breaksByKind"   (reduce (fn [acc k] (update acc k (fnil identity 0)))
                                         (:breaks-by-kind result)
                                         matching/break-kinds)
                ;; Derived, never stored — and null when nothing is outstanding,
                ;; which is a different statement from zero.
                "oldestUnresolvedAgeSeconds" (:oldest-unresolved-age-seconds result)}))))
