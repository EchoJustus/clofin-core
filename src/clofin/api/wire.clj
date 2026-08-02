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
            [clojure.string :as str])
  (:import [java.time Instant]
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

(defn read-organisation-id
  "The organisation a request acts on.

  TODO(TASK-003): the organisation must come from the authenticated principal,
  not from the request. Until authorisation exists, a caller naming their own
  tenant is the honest state of affairs — and stating it here, rather than
  quietly trusting the field, is what stops it being forgotten. Every read of
  this value is a place TASK-003 has to change.

  Taken from the body on a write and from `?organisationId=` on a read, because
  a GET has no body."
  ([request] (read-organisation-id request nil))
  ([request body]
   (if body
     (read-uuid-field body "organisationId")
     (read-uuid (read-query-param request "organisationId") "organisationId"))))

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
