(ns clofin.api.screening
  "Screening endpoints: recording a client's result as evidence, reading
  results, cases and lists, and dispositioning a case (C-07, ADR-0028 D5).

  **A `201` from recording means evidence recorded and nothing else.** No
  payment state changes and no `payment.*` event is written, whatever the
  result says; the only acknowledgment of a transition is the
  `PaymentInstruction` that `submit` returns with `status: pending-approval`.
  A satellite must not translate the `201`, or its `auditEventId`, into a
  transition (the operator's ruling on D5).

  **Two refusals are rendered after their evidence commits** (lesson L-11):
  a result core cannot reproduce is stored `refused` and answered `422
  screening-result-mismatch`; a hit at submission stores core's result and the
  case it opened and is answered `409 screening-hit` (`clofin.api.payments`).
  Both operations take an `Idempotency-Key`, and in both the refusal is the
  response the key is bound to — the effect returns it as a value, the key and
  the evidence commit together, and a retry under the same key replays the
  same refusal rather than recording the same evidence twice. Every other
  refusal stores nothing, is thrown inside the transaction, and leaves the key
  unconsumed, as everywhere else.

  **There is no route that writes a list.** Lists are loaded by the operator's
  tool (`make load-screening-list`); these endpoints read them.

  The list is synthetic and the matching exact; nothing here claims screening
  quality."
  (:require [clofin.api.principal :as principal]
            [clofin.api.wire :as wire]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clofin.idempotency :as idem]
            [clofin.idempotency.repository :as idem-store]
            [clofin.payments.repository :as payments]
            [clofin.screening.decision :as decision]
            [clofin.screening.repository :as screening]
            [clofin.screening.rules :as rules]
            [clofin.screening.service :as service]
            [clojure.set :as set]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Idempotency, as `clofin.api.payments` and `clofin.api.approvals` do it
;; ---------------------------------------------------------------------------

(def ^:private idempotency-header "idempotency-key")

(defn- canonical-path
  "The request path, normalised the way the router normalises it — identical to
  `clofin.api.payments`'s, and for the identical reason."
  [uri]
  (str "/" (str/join "/" (remove str/blank? (str/split (str uri) #"/")))))

(defn- request-digest
  [request]
  (idem/digest {"method" (str/upper-case (name (:request-method request)))
                "path"   (canonical-path (:uri request))
                "body"   (or (:json-body request) {})}))

(defn- idempotently
  [pool request organisation-id operation-id effect]
  (idem-store/execute-once!
   pool
   {:organisation-id organisation-id
    :key             (idem/read-key (get-in request [:headers idempotency-header]))
    :digest          (request-digest request)
    :operation-id    operation-id}
   effect))

(defn respond
  "Render an idempotent outcome. A stored refusal is a problem document, and is
  labelled as one whether it is answered now or replayed."
  ([outcome] (respond outcome {}))
  ([{:keys [status body replayed?]} headers]
   {:status  status
    :headers (cond-> (assoc headers "content-type" (if (>= status 400)
                                                     "application/problem+json"
                                                     "application/json"))
               replayed? (assoc "idempotent-replayed" "true"))
    :body    body}))

(defn refusal-outcome
  "An effect's answer for a refusal whose evidence commits: `{:status :body}`
  carrying the RFC 9457 document the error boundary would have rendered — the
  same `type`, `title`, `detail` and `instance` — with `errors` as given.

  Returned from the effect rather than thrown, so `execute-once!` commits the
  evidence and binds the key to this answer; `respond` then renders it."
  [request kind detail errors]
  (let [{:keys [status title]} (get err/error-types kind)]
    {:status status
     :body   (:body (resp/problem {:status   status
                                   :type     kind
                                   :title    title
                                   :detail   detail
                                   :instance (:correlation-id request)
                                   :errors   errors}))}))

;; ---------------------------------------------------------------------------
;; Reading a client's result
;; ---------------------------------------------------------------------------

(defn- closed!
  "Refuse a member the object's schema does not declare. Every screening request
  object is closed (`additionalProperties: false`): evidence is exactly what it
  states, and a member core does not read — a `coreOutcome`, say — must not
  look as though it had been accepted."
  [obj allowed where]
  (let [extra (sort (set/difference (set (keys obj)) allowed))]
    (when (seq extra)
      (err/invalid! (str where " has members the contract does not declare: " (str/join ", " extra))
                    {:unexpected (vec extra)}))))

(defn- read-text
  "A required string of 1 to `max` characters, **not trimmed**: a list version
  or a rule value is compared exactly, and trimming would make two different
  values equal."
  [obj field max]
  (let [v (get obj field)]
    (when-not (and (string? v) (<= 1 (count v) max))
      (err/invalid! (str "Field '" field "' must be a string of 1-" max " characters")
                    {:field field}))
    v))

(defn- read-rule
  [rule]
  (when-not (map? rule) (err/invalid! "Each rule must be an object" {:field "matchedEntries"}))
  (closed! rule #{"field" "operator" "value"} "A rule")
  {:field    (wire/read-enum (get rule "field") "field" (set (keys rules/fields)))
   :operator (wire/read-enum (get rule "operator") "operator" rules/operators)
   :value    (read-text rule "value" 10000)})

(defn- read-entry
  [entry]
  (when-not (map? entry) (err/invalid! "Each matched entry must be an object" {:field "matchedEntries"}))
  (closed! entry #{"id" "rules"} "A matched entry")
  (let [rule-values (get entry "rules")]
    (when-not (and (sequential? rule-values) (seq rule-values))
      (err/invalid! "A matched entry carries at least one rule" {:field "matchedEntries"}))
    {:id    (read-text entry "id" 128)
     :rules (mapv read-rule rule-values)}))

(defn- read-matched-entries
  [body]
  (let [v (get body "matchedEntries")]
    (when-not (sequential? v)
      (err/invalid! "Field 'matchedEntries' is required: an array, empty when the outcome is clear"
                    {:field "matchedEntries"}))
    (let [entries (mapv read-entry v)
          ids (map :id entries)]
      (when-not (= (count ids) (count (distinct ids)))
        (err/invalid! "Field 'matchedEntries' names an entry more than once" {:field "matchedEntries"}))
      entries)))

(defn- read-digest
  [body]
  (let [v (get body "instructionDigest")]
    (when-not (and (string? v) (re-matches #"[0-9a-f]{64}" v))
      (err/invalid! "Field 'instructionDigest' must be 64 lowercase hex characters: the instruction's screeningDigest"
                    {:field "instructionDigest"}))
    v))

(defn- read-result-request
  "`ScreeningResultRequest`, read whole before anything is locked. A request
  that cannot be understood is `400` and stores nothing."
  [body]
  (closed! body #{"organisationId" "listVersion" "outcome" "matchedEntries"
                  "instructionDigest" "screenedAt"} "The request")
  (when-not (contains? body "organisationId")
    (err/invalid! "Field 'organisationId' is required" {:field "organisationId"}))
  {:list-version       (read-text body "listVersion" 128)
   :outcome            (wire/read-enum (get body "outcome") "outcome" decision/outcomes)
   :matched-entries    (read-matched-entries body)
   :instruction-digest (read-digest body)
   :screened-at        (when (some? (get body "screenedAt"))
                         (wire/read-instant (get body "screenedAt") "screenedAt"))})

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn record-result
  "`POST /payment-instructions/:id/screening-results` — record a client's
  screening result as **evidence**.

  `:screening/record`. Core recomputes the result against the same list version
  and stores both outcomes and whether they agree (`clofin.screening.service/record-result!`
  gives the order of the refusals). A `201` is a `ScreeningResult`: evidence
  recorded, **status unchanged**. A result core cannot reproduce is stored
  `refused` and answered `422 screening-result-mismatch` with `coreOutcome` and
  `coreMatchedEntries`, after the transaction commits."
  [pool]
  (fn [request]
    (let [body (wire/read-object request)
          [actor organisation-id] (principal/for-request pool request :screening/record body)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")
          outcome
          (idempotently
           pool request organisation-id "recordScreeningResult"
           (fn [tx]
             (let [parsed (read-result-request body)
                   {:keys [result refused? reason detail]}
                   (service/record-result! tx (assoc parsed
                                                     :organisation-id organisation-id
                                                     :instruction-id id
                                                     :actor actor
                                                     :correlation-id (:correlation-id request)))]
               (if refused?
                 (refusal-outcome request :unprocessable detail
                                  {"reason"             reason
                                   "resultId"           (str (:id result))
                                   "coreOutcome"        (name (:core-outcome result))
                                   "coreMatchedEntries" (vec (:core-matched-entries result))})
                 {:status 201 :body (wire/screening-result->wire result)}))))]
      (respond outcome (if (= 201 (:status outcome))
                         {"location" (str "/screening-results?instructionId=" id)}
                         {})))))

(defn index-results
  "`GET /screening-results?instructionId=` — an instruction's screening
  results, core's and clients', accepted and refused, newest first.

  `:screening/read`. The instruction must be one of the caller's
  organisation's: another tenant's is `404`, as every read answers it."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :screening/read)
          instruction-id (wire/read-uuid (wire/read-query-param request "instructionId")
                                         "instructionId")]
      (when-not (payments/find-instruction pool organisation-id instruction-id)
        (err/not-found! "No such payment instruction in this organisation"
                        {:id (str instruction-id)}))
      (let [{:keys [results truncated?]} (screening/list-results pool organisation-id instruction-id)]
        (resp/ok {"screeningResults" (mapv wire/screening-result->wire results)
                  "count"            (count results)
                  "limit"            screening/row-cap
                  "truncated"        (boolean truncated?)})))))

(defn index-cases
  "`GET /screening-cases?status=` — an organisation's screening cases, newest
  first, optionally one status. `:screening/read`."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :screening/read)
          status (when-let [raw (wire/read-optional-query-param request "status")]
                   (wire/read-enum raw "status" decision/case-statuses))
          {:keys [cases truncated?]} (screening/list-cases pool organisation-id {:status status})]
      (resp/ok {"screeningCases" (mapv wire/screening-case->wire cases)
                "count"          (count cases)
                "limit"          screening/row-cap
                "truncated"      (boolean truncated?)}))))

(defn show-case
  "`GET /screening-cases/:id`. `:screening/read`; another organisation's case
  is `404`."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :screening/read)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")]
      (if-let [found (screening/find-case pool organisation-id id)]
        (resp/ok (wire/screening-case->wire found))
        (err/not-found! "No such screening case in this organisation" {:id (str id)})))))

(defn disposition-case
  "`POST /screening-cases/:id/disposition` — decide a hit: `false-positive` or
  `confirmed-hit`, with a rationale of 1-1000 characters.

  `:screening/disposition`, held by `compliance` alone. The instruction's maker
  is refused `403 self-disposition` whatever roles they hold — the maker never
  clears their own hit (C-01's shape). A dispositioned case answers `409`; a
  disposition is final. `200` with the `ScreeningCase`."
  [pool]
  (fn [request]
    (let [body (wire/read-object request)
          [actor organisation-id] (principal/for-request pool request :screening/disposition body)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")
          outcome
          (idempotently
           pool request organisation-id "dispositionScreeningCase"
           (fn [tx]
             (closed! body #{"organisationId" "disposition" "rationale"} "The request")
             (let [disposition (wire/read-enum (get body "disposition") "disposition"
                                               decision/case-dispositions)
                   {dispositioned :case} (service/disposition!
                                   tx {:organisation-id organisation-id
                                       :case-id         id
                                       :actor           actor
                                       :disposition     disposition
                                       :rationale       (get body "rationale")
                                       :correlation-id  (:correlation-id request)})]
               {:status 200 :body (wire/screening-case->wire dispositioned)})))]
      (respond outcome))))

(defn index-lists
  "`GET /screening-lists` — every loaded list version, newest first, with
  which one is accepted. `:screening/read`. Read-only: a list is loaded by the
  operator's tool, never through the API."
  [pool]
  (fn [request]
    (principal/for-request pool request :screening/read)
    (let [lists (screening/list-lists pool)]
      (resp/ok {"screeningLists" (mapv wire/screening-list-summary->wire lists)
                "count"          (count lists)}))))

(defn show-list
  "`GET /screening-lists/:version` — one version with every entry and rule: the
  list a past decision can be reproduced against. `:screening/read`."
  [pool]
  (fn [request]
    (principal/for-request pool request :screening/read)
    (let [version (wire/read-path-segment (get-in request [:path-params :version]) "version")]
      (if-let [found (screening/find-list pool version)]
        (resp/ok (wire/screening-list->wire found))
        (err/not-found! "No such screening list version" {:version version})))))
