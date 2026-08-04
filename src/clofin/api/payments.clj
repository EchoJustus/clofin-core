(ns clofin.api.payments
  "Payment instruction endpoints.

  This is where intent to pay is captured, so it is worth being explicit about
  what each status code means here:

  - `400` — the request could not be understood: an absent body, a malformed
    `organisationId`, a missing `Idempotency-Key`.
  - `422` — the request was understood and cannot be carried out: a rejected
    field, a debtor account that is frozen or in another organisation. The
    `errors` object names **every** failed field (PR-003), not the first.
  - `409` — the lifecycle refuses the operation, or an `Idempotency-Key` has
    already been used for a different request.

  **Every mutating operation is idempotent** (PR-040). The key is read from the
  `Idempotency-Key` header, the request is digested — method, path and body —
  and the effect runs inside the transaction that stores both, so a retry either
  replays the stored response or does nothing at all. Digesting the path as well
  as the body is what stops one key replaying across two *different*
  instructions' submissions, which carry identical bodies.

  Idempotency is resolved *before* validation. A replay must return what the
  first call returned even if the same request would no longer validate today,
  which a value date passing into the past makes concrete: an operator retrying
  a week-old submission must be told what happened, not told its date is
  invalid.

  A rejected request does not consume its key. The effect and the key row share
  a transaction, so a throw takes both down and a caller that fixes its body and
  retries under the same key gets a fresh execution rather than a `409`."
  (:require [clofin.api.wire :as wire]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clofin.idempotency :as idem]
            [clofin.idempotency.repository :as idem-store]
            [clofin.money :as money]
            [clofin.payments.instruction :as instruction]
            [clofin.payments.repository :as payments]
            [clofin.payments.state :as state]
            [clojure.string :as str])
  (:import [java.time LocalDate ZoneOffset]))

;; ---------------------------------------------------------------------------
;; Reading an instruction from the wire
;; ---------------------------------------------------------------------------

(def ^:private field-readers
  "How each field of an instruction is read from a JSON object.

  `:wire` is the member name, `:read` turns it into a domain value, and
  `:unreadable` is the message for a member that was **present and could not be
  read** — which `clofin.payments.instruction/field-errors` cannot report,
  because it never sees the value. A member that is simply absent is not
  reported here at all: required-ness belongs to the domain, so exactly one
  place decides which fields an instruction needs.

  Text fields have no `:read`. They arrive as whatever JSON carried and the
  domain judges their type along with everything else about them."
  [{:key :organisation-id   :wire "organisationId"
    :read #(java.util.UUID/fromString %) :unreadable "must be a UUID"}
   {:key :debtor-account-id :wire "debtorAccountId"
    :read #(java.util.UUID/fromString %) :unreadable "must be a UUID"}
   {:key :created-by        :wire "createdBy"
    :read #(java.util.UUID/fromString %) :unreadable "must be a UUID"}
   {:key :reverses-id       :wire "reversesId"
    :read #(java.util.UUID/fromString %) :unreadable "must be a UUID"}
   {:key :creditor-name     :wire "creditorName"}
   {:key :creditor-account  :wire "creditorAccount"}
   {:key :purpose-code      :wire "purposeCode"}
   {:key :amount            :wire "amount" :read money/wire->
    :unreadable "must be an integer count of minor units in a supported currency"}
   {:key :value-date        :wire "valueDate" :read #(LocalDate/parse %)
    :unreadable "must be a date in YYYY-MM-DD form"}])

(def ^:private wire-name
  "Domain key to the JSON member it is reported under.

  Names differ on purpose — the domain speaks kebab-case keywords and the wire
  speaks camelCase — and translating in one place means renaming a domain key
  never silently changes what a caller sees in an `errors` object."
  (into {} (map (juxt :key :wire)) field-readers))

(defn- read-members
  "Parse every member of `body` that `field-readers` knows about.

  Returns `[values unreadable]`. A parser that throws yields no value and a
  message rather than aborting the pass: PR-003 requires a rejection to name
  every failed field, and a reader that throws can only ever name the first."
  [body]
  (reduce (fn [[values unreadable] {:keys [key wire read] :as reader}]
            (let [raw (get body wire)]
              (cond
                (nil? raw)  [values unreadable]
                (nil? read) [(assoc values key raw) unreadable]
                :else       (try
                              [(assoc values key (read raw)) unreadable]
                              (catch Exception _
                                [values (assoc unreadable key (:unreadable reader))])))))
          [{} {}]
          field-readers))

(defn- ->member
  "The JSON member name a failed field is reported under."
  [field]
  (or (wire-name field)
      (if (keyword? field) (name field) (str field))))

(defn- by-member
  "Failed fields keyed by the JSON member each is reported under, in a stable
  order.

  An `array-map` rather than a `sorted-map`: this map becomes the error's
  `ex-data`, and the error boundary `dissoc`es two *keyword* keys from it. A
  sorted map of strings has a comparator that cannot compare a keyword to a
  string, so the dissoc would throw — turning every validation failure into a
  `500`. Insertion order gives the same determinism with no comparator."
  [errors]
  (into (array-map)
        (map (fn [[field message]] [(->member field) message]))
        (sort-by (comp ->member key) errors)))

(defn- invalid-fields!
  "Reject a request naming every failed field, under its wire name.

  `422` rather than `400`: these are values CloFin understood perfectly well
  and cannot act on, which is a business outcome to show a human rather than a
  bug in the caller (ADR-0012, ADR-0014)."
  [errors]
  (err/fail! :field-validation "Request failed validation" (by-member errors)))

(defn- wire-named
  "Run `f`, restating any field-validation failure under wire member names.

  The domain reports a failed field by its domain key, which is right — it does
  not know the wire, and ADR-0012 keeps it that way. A failure that reaches a
  *caller* must nonetheless name the member the caller sent, so the translation
  happens here, at the boundary where the two vocabularies meet."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo t
      (let [data (ex-data t)]
        (if (= :field-validation (:clofin/error data))
          (invalid-fields! (dissoc data :clofin/error :clofin/message))
          (throw t))))))

(defn- today
  "The calendar date a value date is judged against.

  UTC, so that whether a value date is \"in the past\" does not depend on the
  zone the JVM happens to be running in. Read here, at the edge, because the
  domain reads no clock."
  []
  {:today (LocalDate/now ZoneOffset/UTC)})

;; ---------------------------------------------------------------------------
;; Idempotency
;; ---------------------------------------------------------------------------

(def ^:private idempotency-header
  "Lower-cased, because the HTTP adapter lower-cases header names — HTTP header
  names are case-insensitive but Clojure map keys are not."
  "idempotency-key")

(defn- canonical-path
  "The request path, normalised the way the router normalises it.

  The router discards empty segments, so `/a/b` and `/a/b/` reach the same
  handler. If the digest saw them as different, a client that added a trailing
  slash on a retry would be told its payment conflicts — which is the same class
  of false conflict the body canonicalisation exists to prevent, one component
  along."
  [uri]
  (str "/" (str/join "/" (remove str/blank? (str/split (str uri) #"/")))))

(defn- request-digest
  "The digest that identifies this request for idempotency purposes.

  Covers the canonical document `{\"method\", \"path\", \"body\"}` — **not the
  body alone**. The path is what distinguishes one instruction's submission from
  another's: those two requests carry identical bodies, so a body-only digest
  would make the second a replay of the first, and its instruction would never
  be submitted while the operator saw success.

  Amended by the ruling on objection O-3; see
  docs/ADR/0013-canonical-request-digest-for-idempotency.md."
  [request]
  (idem/digest {"method" (str/upper-case (name (:request-method request)))
                "path"   (canonical-path (:uri request))
                ;; Normalised here rather than relied on downstream: `{"body":
                ;; null}` and `{"body": {}}` are the same request, and a
                ;; bodiless mutation must still digest to something stable.
                "body"   (or (:json-body request) {})}))

(defn- idempotently
  "Run `effect` at most once for this request's `Idempotency-Key`.

  The key is scoped to the organisation, so `organisationId` is read before
  anything else happens — which is also why a request whose `organisationId` is
  unreadable is `400` rather than one field on a `422`: without it there is no
  key to be idempotent under, and executing anyway is the failure this whole
  mechanism exists to prevent."
  [pool request organisation-id effect]
  (idem-store/execute-once!
   pool
   {:organisation-id organisation-id
    :key             (idem/read-key (get-in request [:headers idempotency-header]))
    :digest          (request-digest request)}
   effect))

(defn- respond
  "Render an idempotent outcome as a response.

  The body is the stored JSON **string**, so a replay is byte-identical to the
  response the first call produced. That is also why the content type is set
  here: the JSON middleware encodes data and leaves a string alone."
  ([outcome] (respond outcome {}))
  ([{:keys [status body replayed?]} headers]
   {:status  status
    :headers (cond-> (assoc headers "content-type" "application/json")
               ;; Stated rather than left to be inferred. Someone reconciling a
               ;; retry should be able to see that CloFin replayed rather than
               ;; acted.
               replayed? (assoc "idempotent-replayed" "true"))
    :body    body}))

(defn- location
  "Where a created instruction lives, including the query parameter needed to
  read it back.

  Derived from the response document rather than from the request, so a
  replayed `201` points at the same resource the original did."
  [data]
  (str "/payment-instructions/" (get data "id")
       "?organisationId=" (get data "organisationId")))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn create
  "`POST /payment-instructions` — capture intent to pay.

  The instruction is created `draft`. A caller cannot choose the status: an
  instruction arriving already approved would be an approval nobody gave.

  A reversal is an ordinary creation carrying `reversesId` (PR-043). It is a
  **new** instruction — the settled one it names is not touched, because a
  settled payment is never mutated (`DOMAIN_MODEL.md` §3 rule 4)."
  [pool]
  (fn [request]
    (let [body            (wire/read-object request)
          organisation-id (wire/read-organisation-id request body)
          outcome
          (idempotently
           pool request organisation-id
           (fn [tx]
             (let [opts (today)
                   [values unreadable] (read-members body)
                   ;; One pass over every rule, so a caller with three bad
                   ;; fields learns about all three (PR-003, AC-2). A member
                   ;; that could not be parsed reaches the domain as absent, so
                   ;; its parse message takes precedence: "must be a UUID" is
                   ;; the useful answer for `"debtorAccountId": "nope"`, and
                   ;; "is required" is not.
                   candidate (assoc values
                                    :organisation-id organisation-id
                                    :status state/initial-state)
                   errors (merge (instruction/field-errors candidate opts) unreadable)
                   _ (when (seq errors) (invalid-fields! errors))
                   created (wire-named
                            #(payments/create-instruction!
                              tx
                              ;; TODO(TASK-003): `createdBy` came from the body,
                              ;; because there is no authenticated principal to
                              ;; take it from. It is not evidence of who did
                              ;; anything, and every read of it is a place
                              ;; TASK-003 has to change.
                              (assoc candidate :id (random-uuid))
                              opts))]
               {:status 201 :body (wire/instruction->wire created)})))]
      (respond outcome {"location" (location (:data outcome))}))))

(defn show
  "`GET /payment-instructions/:id` — the resource a `201` points at.

  Scoped by organisation. An instruction belonging to another organisation is
  `404`, the same answer a non-existent id receives: confirming that an id
  exists and belongs to someone else would be a tenancy disclosure available to
  anyone able to guess a UUID."
  [pool]
  (fn [request]
    (let [organisation-id (wire/read-organisation-id request)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")]
      (if-let [found (payments/find-instruction pool organisation-id id)]
        (resp/ok (wire/instruction->wire found))
        (err/not-found! "No such payment instruction in this organisation"
                        {:id (str id)})))))

(defn index
  "`GET /payment-instructions` — an organisation's instructions, newest first.

  Optionally filtered to one lifecycle state with `?status=`. Capped rather
  than paginated; the cap is reported so a caller can tell a full answer from a
  partial one (ADR-0011)."
  [pool]
  (fn [request]
    (let [organisation-id (wire/read-organisation-id request)
          status (when-let [raw (get-in request [:query-params "status"])]
                   (wire/read-enum raw "status" state/states))
          {:keys [instructions truncated?]}
          (payments/list-instructions pool organisation-id {:status status})]
      (resp/ok {"paymentInstructions" (mapv wire/instruction->wire instructions)
                "count"     (count instructions)
                "limit"     payments/row-cap
                "truncated" (boolean truncated?)}))))

(defn amend
  "`PATCH /payment-instructions/:id` — edit a draft in place.

  Only a draft may be amended, and only its substance: identity, provenance and
  lifecycle are not editable. Amending anything that is not a draft is `409`
  (PR-004).

  This does **not** drive the `amend` event in the lifecycle table. That event
  returns a `pending-approval` instruction to `draft` and invalidates the
  approvals already given (PR-014); it belongs to TASK-003's approval workflow,
  and wiring it here would pull a submitted payment back to draft with no
  approval-invalidation behind it. See ADR-0014.

  A member the caller may not change is rejected rather than ignored. Silently
  dropping it would leave the caller believing it had amended something.

  Failures are reported in two passes rather than one: members that could not
  be read at all, then — once the stored instruction has been loaded under its
  lock — the rules over the amended whole. The single-pass guarantee PR-003
  asks for is a property of creation, where every field arrives at once."
  [pool]
  (fn [request]
    (let [body            (wire/read-object request)
          organisation-id (wire/read-organisation-id request body)
          id              (wire/read-uuid (get-in request [:path-params :id]) "id")
          outcome
          (idempotently
           pool request organisation-id
           (fn [tx]
             (let [opts (today)
                   [values unreadable] (read-members body)
                   ;; `organisationId` scopes the request rather than amending
                   ;; anything; every other member must name a field an
                   ;; amendment may touch.
                   permitted (into #{"organisationId"}
                                   (map ->member)
                                   instruction/amendable-fields)
                   rejected  (remove permitted (keys body))]
               (when (seq rejected)
                 (err/fail! :field-validation "Request failed validation"
                            (by-member (zipmap rejected (repeat "cannot be amended")))))
               (when (seq unreadable) (invalid-fields! unreadable))
               {:status 200
                :body (wire/instruction->wire
                       (wire-named
                        #(payments/amend! tx organisation-id id
                                          (select-keys values instruction/amendable-fields)
                                          opts)))})))]
      (respond outcome))))

(defn- transition-handler
  "`POST /payment-instructions/:id/<sub-resource>` — apply one lifecycle event.

  Each event has its own sub-resource rather than a `status` field a caller
  writes. A caller naming the state it wants would be a caller holding a second
  copy of the state machine; naming the *event* leaves the lifecycle table the
  only thing that decides where an instruction goes next (ADR-0014)."
  [pool event]
  (fn [request]
    (let [body            (wire/read-object request)
          organisation-id (wire/read-organisation-id request body)
          id              (wire/read-uuid (get-in request [:path-params :id]) "id")
          outcome
          (idempotently
           pool request organisation-id
           (fn [tx]
             {:status 200
              :body (wire/instruction->wire
                     (payments/transition! tx organisation-id id event))}))]
      (respond outcome))))

(defn submit
  "`POST /payment-instructions/:id/submission` — submit a draft for approval.

  A submitted instruction stops at `pending-approval`. Approval is TASK-003 and
  there is deliberately no endpoint here that moves it further: a payment that
  could be approved by whoever submitted it is not a control."
  [pool]
  (transition-handler pool :submit))

(defn cancel
  "`POST /payment-instructions/:id/cancellation` — cancel an instruction.

  Permitted from `draft` and from `approved`, per the lifecycle table: an
  approved instruction that has not been released is still stoppable. Once
  released it is not, and the answer is `409`."
  [pool]
  (transition-handler pool :cancel))
