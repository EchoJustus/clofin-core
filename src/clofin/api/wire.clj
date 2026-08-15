(ns clofin.api.wire
  "Domain values to and from JSON, and the parsing vocabulary handlers share.

  Two rules shape everything here.

  **Names differ on purpose.** The domain speaks kebab-case Clojure keywords;
  the wire speaks camelCase JSON strings. Translating in one namespace means
  neither side has to accommodate the other, and renaming a domain key never
  silently changes the API contract.

  **Reading is validation.** Every `read-*` function either returns a value of
  the right type or throws a `:validation` error naming the field. A handler
  that has finished parsing is holding domain values, not strings it still has
  to be careful about.

  A malformed request is a `400` and comes from here. A request that is
  understood but cannot be carried out — an unbalanced entry, a frozen account
  — is a `422` and comes from the ledger. See
  docs/ADR/0012-repository-seam-and-posting-time-validation.md."
  (:require [clofin.error :as err]
            [clofin.ledger.account :as account]
            [clofin.money :as money]
            [clofin.payments.state :as payment-state]
            [clojure.string :as str])
  (:import [java.time Instant LocalDate]
           [java.time.format DateTimeParseException]))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn- missing!
  [field]
  (err/invalid! (str "Field '" field "' is required") {:field field}))

(defn read-object
  "The decoded JSON body, asserted to be an object.

  A body that is absent, or is an array or a scalar, is named as such rather
  than producing a null-pointer three functions later."
  [request]
  (let [body (:json-body request)]
    (when-not (map? body)
      (err/invalid! "Request body must be a JSON object"
                    {:content-type (get-in request [:headers "content-type"])}))
    body))

(defn read-string-field
  [obj field]
  (let [v (get obj field)]
    (when-not (and (string? v) (not (str/blank? v)))
      (missing! field))
    (str/trim v)))

(defn read-uuid
  "Parse a UUID, naming the field rather than leaking `IllegalArgumentException`."
  [value field]
  (when-not (string? value) (missing! field))
  (try
    (java.util.UUID/fromString value)
    (catch IllegalArgumentException _
      (err/invalid! (str "Field '" field "' must be a UUID") {:field field :value value}))))

(defn read-uuid-field
  [obj field]
  (read-uuid (get obj field) field))

(defn read-instant
  "Parse an RFC 3339 / ISO 8601 instant.

  An explicit offset is required — `Instant/parse` accepts `2026-08-02T10:15:00Z`
  and rejects a local time with no zone. That rejection is wanted: an occurrence
  time without a zone is ambiguous by exactly the amount that makes a statement
  period wrong at its boundaries."
  [value field]
  (when-not (string? value) (missing! field))
  (try
    (Instant/parse value)
    (catch DateTimeParseException _
      (err/invalid! (str "Field '" field "' must be an ISO 8601 instant with a zone, e.g. 2026-08-02T10:15:00Z")
                    {:field field :value value}))))

(defn read-instant-field
  [obj field]
  (read-instant (get obj field) field))

(defn read-local-date
  "Parse an ISO 8601 calendar date, `YYYY-MM-DD`.

  A date, not an instant: a value date is the same day in every zone that
  quotes it, so attaching a time and an offset to one would introduce a
  distinction the business does not make — and a day of drift at the
  boundaries, which is where value dates matter."
  [value field]
  (when-not (string? value) (missing! field))
  (try
    (LocalDate/parse value)
    (catch DateTimeParseException _
      (err/invalid! (str "Field '" field "' must be a date in YYYY-MM-DD form")
                    {:field field :value value}))))

(defn read-local-date-field
  [obj field]
  (read-local-date (get obj field) field))

(defn read-enum
  "Parse a string into one of `allowed`, a set of keywords."
  [value field allowed]
  (when-not (string? value) (missing! field))
  (let [candidate (keyword value)]
    (when-not (contains? allowed candidate)
      (err/invalid! (str "Field '" field "' must be one of: "
                         (str/join ", " (sort (map name allowed))))
                    {:field field :value value :known (vec (sort (map name allowed)))}))
    candidate))

(defn read-money
  "Parse `{\"currency\": \"SGD\", \"minorUnits\": 125000}` into an amount."
  [value field]
  (when-not (map? value)
    (err/invalid! (str "Field '" field "' must be an amount object with 'currency' and 'minorUnits'")
                  {:field field}))
  (money/wire-> value))

(defn read-query-param
  [request param]
  (let [v (get-in request [:query-params param])]
    (when (str/blank? v)
      (err/invalid! (str "Query parameter '" param "' is required") {:parameter param}))
    v))

(defn read-optional-query-param
  [request param]
  (let [v (get-in request [:query-params param])]
    (when-not (str/blank? v) v)))

(defn read-stated-organisation-id
  "The organisation the *request* names, or nil when it names none.

  Read from the body on a write and from `?organisationId=` on a read, because
  a GET has no body. **It is not the organisation the request acts on.** That
  comes from the authenticated principal — see `clofin.api.principal`, which
  compares the two and refuses a mismatch rather than ignoring it.

  Optional, and that is the change TASK-003 makes: the field used to be the
  only thing saying which tenant a request was about, which is why it was
  documented as not being an access control. It now scopes the idempotency key
  and appears in `Location` headers, and it is verified rather than trusted. A
  caller that omits it gets the principal's own organisation, which is the only
  one it could ever have acted on."
  ([request] (read-stated-organisation-id request nil))
  ([request body]
   (if-let [raw (if body (get body "organisationId") (read-optional-query-param request "organisationId"))]
     (read-uuid raw "organisationId")
     nil)))

;; ---------------------------------------------------------------------------
;; Writing
;; ---------------------------------------------------------------------------
;;
;; These produce JSON-ready values — strings, numbers, booleans, maps with
;; string keys — rather than relying on the encoder to coerce a UUID or a
;; keyword. The encoder does coerce them (`clofin.http.middleware`), but a
;; handler test asserting on the response body should be reading the same
;; document the caller receives, not one that still needs converting.

(defn organisation->wire
  [org]
  {"id"         (str (:id org))
   "legalName"  (:legal-name org)
   "shortName"  (:short-name org)
   "status"     (name (:status org))})

(defn account->wire
  [acct]
  {"id"             (str (:id acct))
   "organisationId" (str (:organisation-id acct))
   "code"           (:code acct)
   "name"           (:name acct)
   "type"           (name (:type acct))
   "currency"       (:currency acct)
   "status"         (name (:status acct))
   ;; Derived, and included because it is what makes the sign of a balance
   ;; readable: a positive figure on a credit-normal account means money owed.
   "normalBalance"  (name (account/normal-balance (:type acct)))})

(defn- line->wire
  [line]
  {"accountId" (str (:account-id line))
   "direction" (name (:direction line))
   "amount"    (money/->wire (:amount line))})

(defn entry->wire
  [entry]
  (cond-> {"id"             (str (:id entry))
           "organisationId" (str (:organisation-id entry))
           "occurredAt"     (str (:occurred-at entry))
           "narrative"      (:narrative entry)
           "reference"      {"type" (name (get-in entry [:reference :type]))
                             "id"   (str (get-in entry [:reference :id]))}
           "lines"          (mapv line->wire (:lines entry))}
    ;; Present when the entry was read back from the journal; absent on the
    ;; value returned straight from a post, which has not been re-read.
    (:recorded-at entry) (assoc "recordedAt" (str (:recorded-at entry)))))

(defn instruction->wire
  [pi]
  (cond-> {"id"               (str (:id pi))
           "organisationId"   (str (:organisation-id pi))
           "debtorAccountId"  (str (:debtor-account-id pi))
           "creditorName"     (:creditor-name pi)
           "creditorAccount"  (:creditor-account pi)
           "amount"           (money/->wire (:amount pi))
           ;; A calendar date, rendered as one. `LocalDate/toString` is
           ;; ISO 8601 `YYYY-MM-DD` and carries no time and no offset, which is
           ;; the whole point of the column's type.
           "valueDate"        (str (:value-date pi))
           "purposeCode"      (:purpose-code pi)
           "status"           (name (:status pi))
           "createdBy"        (str (:created-by pi))
           ;; Derived from the lifecycle table, so the API cannot advertise an
           ;; operation the state machine would refuse. A caller reading this
           ;; does not have to hold a copy of the state machine to know what it
           ;; may do next (ADR-0014).
           "permittedTransitions" (mapv name (payment-state/permitted-events (:status pi)))}
    (:created-at pi)  (assoc "createdAt" (str (:created-at pi)))
    ;; Present only on a reversal, where it names the settled instruction this
    ;; one was raised against.
    (:reverses-id pi) (assoc "reversesId" (str (:reverses-id pi)))
    ;; Present only on a retry, where it names the returned instruction this one
    ;; was raised to replace (ADR-0019, ADR-0024).
    (:retries-id pi)  (assoc "retriesId" (str (:retries-id pi)))
    ;; The other end of the same link, derived rather than stored. Rendered only
    ;; when there is one, so an ordinary instruction carries no empty array — and
    ;; a returned instruction that *has* been retried says so from its own
    ;; resource, which is what makes the provenance answerable without knowing
    ;; the retry's id first.
    (seq (:retried-by-ids pi))
    (assoc "retriedByIds" (mapv str (:retried-by-ids pi)))))

(defn actor->wire
  "An actor, as much of one as any caller needs to see.

  Roles and limits are **not** included. A caller that can read another actor's
  limit knows exactly how large a payment to split an amount into, and an
  endpoint that lists an organisation's approvers and their ceilings is a
  reconnaissance tool. The display name is here because an approval queue that
  showed only UUIDs would be unreadable to the person meant to act on it."
  [actor]
  {"id"          (str (:id actor))
   "displayName" (:display-name actor)
   "status"      (name (:status actor))})

(defn approval->wire
  "One approval decision, whichever kind of subject it is about.

  Exactly one of `instructionId` and `adjustmentId` is present, mirroring the
  `approval_names_one_subject` constraint. Rendering the absent one as the
  string `\"null\"` — which `(str nil)` produces — would put a value in a field a
  consumer would then have to know to ignore."
  [approval]
  (cond-> {"id"            (str (:id approval))
           "actorId"       (str (:actor-id approval))
           "decision"      (name (:decision approval))
           "decidedAt"     (str (:decided-at approval))
           ;; Stated on every approval rather than only when set: a consumer
           ;; should not have to infer from an absent field that a decision
           ;; still stands.
           "live"          (nil? (:invalidated-at approval))}
    (:instruction-id approval) (assoc "instructionId" (str (:instruction-id approval)))
    (:adjustment-id approval)  (assoc "adjustmentId" (str (:adjustment-id approval)))
    (:reason approval)         (assoc "reason" (:reason approval))
    (:invalidated-at approval) (assoc "invalidatedAt" (str (:invalidated-at approval)))))

(defn approval-queue-row->wire
  "One row of the approval queue.

  Carries what PR-015 says an approver needs in order to decide — amount,
  counterparty, purpose, prior approvals and how many more are required —
  rather than an id the approver would have to resolve in another system. An
  approval given without context is a rubber stamp."
  [{:keys [instruction approvals approvals-held approvals-required
           approvals-remaining can-approve? refusal-reason]}
   instruction->wire-fn]
  (cond-> {"paymentInstruction"  (instruction->wire-fn instruction)
           "priorApprovals"      (mapv approval->wire approvals)
           "approvalsHeld"       approvals-held
           "approvalsRequired"   approvals-required
           "approvalsRemaining"  approvals-remaining
           ;; Shown, not filtered. Hiding a row this actor may not approve
           ;; would be a control implemented in a list query, and it would
           ;; leave a maker unable to see that their own payment is waiting.
           "canApprove"          (boolean can-approve?)}
    refusal-reason (assoc "refusalReason" (name refusal-reason))))

(defn audit-event->wire
  [event]
  (cond-> {"id"            (str (:id event))
           "organisationId" (str (:organisation-id event))
           "action"        (:action event)
           "subjectType"   (:subject-type event)
           "subjectId"     (str (:subject-id event))
           "occurredAt"    (str (:occurred-at event))}
    (:actor-id event)       (assoc "actorId" (str (:actor-id event)))
    (:before-digest event)  (assoc "beforeDigest" (:before-digest event))
    (:after-digest event)   (assoc "afterDigest" (:after-digest event))
    (:correlation-id event) (assoc "correlationId" (:correlation-id event))))

(defn- movement->wire
  [movement]
  {"entryId"        (str (:entry-id movement))
   "lineNo"         (:line-no movement)
   "occurredAt"     (str (:occurred-at movement))
   "narrative"      (:narrative movement)
   "direction"      (name (:direction movement))
   "amount"         (money/->wire (:amount movement))
   "runningBalance" (money/->wire (:running-balance movement))})

(defn statement->wire
  [statement cap]
  {"account"        (account->wire (:account statement))
   "from"           (str (:from statement))
   "to"             (str (:to statement))
   "openingBalance" (money/->wire (:opening-balance statement))
   "closingBalance" (money/->wire (:closing-balance statement))
   "movements"      (mapv movement->wire (:movements statement))
   ;; Stated on every statement, not only when true: a consumer should not have
   ;; to infer from the absence of a field that nothing was left out.
   "truncated"      (boolean (:truncated? statement))
   "movementCap"    cap})
