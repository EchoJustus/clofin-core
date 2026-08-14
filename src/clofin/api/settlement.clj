(ns clofin.api.settlement
  "Settlement endpoints: batching approved payments, releasing them to a
  **simulated** scheme, and recording what that simulation says came back.

  > CloFin is not connected to any bank, payment scheme or central bank.
  > `POST /settlement-batches/{id}/scheme-responses` is the injection point for
  > a simulated response and is the only way an outcome enters the system —
  > there is no listener, no file drop and no correspondent. The API contract
  > says so to a caller as well.

  Nothing here decides anything. Where a payment may go is
  `clofin.payments.state`; which instructions may be batched together is
  `clofin.settlement.batch`; what a simulated scheme would say is
  `clofin.settlement.scheme`. This layer reads a request, opens the transaction
  the unit of work needs, and renders what comes back.

  What each status code means here (ADR-0012):

  - `400` — the request could not be understood: a malformed UUID, an unknown
    scheme, a missing outcome on a timeout resolution.
  - `401` / `403` — no actor, or an actor without `:settlement/execute`. Reads
    need only `:payment/read`: a settlement batch is a fact about payments, and
    an auditor who may read the payments may read how they settled.
  - `409` — the batch is not in a state that permits this, the instruction has
    already been in a settlement batch, or a response arrived out of order. On
    `recordSchemeResponse` this status is rendered **after** the transaction
    commits, so the refused response's receipt survives its own refusal
    (F-008).
  - `422` — understood, and the organisation is not set up for it: an
    ineligible instruction, or no settlement accounts in the batch's currency.

  The transaction is opened here because something must and a service may not
  (`ARCHITECTURE.md` §4). Every one of these operations is a state change, a
  posting and an audit event that commit together or not at all (C-05, I9)."
  (:require [clofin.api.principal :as principal]
            [clofin.api.wire :as wire]
            [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clofin.settlement.batch :as batch]
            [clofin.settlement.repository :as settlement]
            [clofin.settlement.response :as response]
            [clofin.settlement.service :as service]
            [clofin.settlement.statement :as sim-statement]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Wire
;; ---------------------------------------------------------------------------

(defn- batch->wire
  [batch]
  {"id"             (str (:id batch))
   "organisationId" (str (:organisation-id batch))
   "scheme"         (:scheme batch)
   "currency"       (:currency batch)
   "valueDate"      (str (:value-date batch))
   "status"         (:status batch)
   "createdBy"      (str (:created-by batch))
   "createdAt"      (str (:created-at batch))})

(defn- item->wire
  [item]
  (cond-> {"instructionId" (str (:instruction-id item))
           "outcome"       (:outcome item)}
    (:outcome-reason item) (assoc "outcomeReason" (:outcome-reason item))
    (:resolved-at item)    (assoc "resolvedAt" (str (:resolved-at item)))))

(defn- response->wire
  "One receipt, including what CloFin did about it.

  `disposition` is rendered rather than left implicit, because the point of
  keeping a refused arrival (audit finding **F-008**) is defeated if a reader of
  the batch document cannot tell which responses did work from which merely
  arrived. `outcome` is what *this response claimed*, which for a refused
  arrival is nothing — the item carries what was recorded."
  [response]
  (cond-> {"id"          (str (:id response))
           "kind"        (:kind response)
           "reference"   (:reference response)
           "receivedAt"  (str (:received-at response))
           "disposition" (:disposition response)}
    (:instruction-id response)     (assoc "instructionId" (str (:instruction-id response)))
    (:disposition-reason response) (assoc "dispositionReason" (:disposition-reason response))
    (:outcome response)            (assoc "outcome" (:outcome response))
    (:reason response)             (assoc "reason" (:reason response))))

(defn- batch-document
  "A batch, its items and every response recorded against it.

  The **exception cases** are surfaced as their own list rather than left for a
  caller to filter out of `items`: a returned payment is the thing an operator
  has to act on, and an exception queue that has to be assembled client-side is
  an exception queue each client assembles differently (AC-8)."
  [source batch]
  (let [items     (settlement/items-for source (:id batch))
        responses (settlement/responses-for source (:id batch))]
    (assoc (batch->wire batch)
           "items" (mapv item->wire items)
           "itemCount" (count items)
           "exceptions" (mapv item->wire (filter #(= "returned" (:outcome %)) items))
           "schemeResponses" (mapv response->wire responses)
           "simulated" true)))

(defn- optional-object
  "The decoded body, or `{}` when there is none.

  `submit` and `timeout-sweep` are actions on a resource named in the path;
  neither needs a body, and requiring an empty one would be ceremony a caller
  has to discover. A body that is present and is not an object is still named
  as such rather than producing a null three functions later."
  [request]
  (let [body (:json-body request)]
    (cond
      (nil? body)  {}
      (map? body)  body
      :else (err/invalid! "Request body must be a JSON object when one is sent"
                          {:content-type (get-in request [:headers "content-type"])}))))

(defn- read-instruction-ids
  [body]
  (let [raw (get body "instructionIds")]
    (when-not (sequential? raw)
      (err/invalid! "Field 'instructionIds' must be an array of payment instruction ids"
                    {:field "instructionIds"}))
    (into [] (map-indexed (fn [i v] (wire/read-uuid v (str "instructionIds[" i "]")))) raw)))

(defn- read-scheme
  [body]
  (let [raw (get body "scheme")]
    (when-not (string? raw)
      (err/invalid! "Field 'scheme' must name a simulated settlement scheme"
                    {:field "scheme" :known (vec batch/schemes)}))
    (batch/assert-scheme! raw)))

(defn- read-positive-int
  "An optional non-negative integer field, or `fallback`."
  [body field fallback]
  (let [raw (get body field)]
    (cond
      (nil? raw) fallback
      (and (integer? raw) (not (neg? raw))) (long raw)
      :else (err/invalid! (str "Field '" field "' must be a non-negative whole number of seconds")
                          {:field field}))))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn create
  "`POST /settlement-batches` — group approved instructions for one simulated
  scheme, currency and value date.

  Refuses the whole request when any named instruction is ineligible, naming
  every one and why (AC-1, AC-2). Nothing is created on a refusal — the
  eligibility check runs inside the transaction that would have written the
  batch, against rows it holds."
  [pool]
  (fn [request]
    (let [body (wire/read-object request)
          [actor organisation-id] (principal/for-request pool request :settlement/execute body)
          scheme     (read-scheme body)
          currency   (wire/read-string-field body "currency")
          value-date (wire/read-local-date-field body "valueDate")
          ids        (read-instruction-ids body)
          {:keys [batch]}
          (db/with-transaction [tx pool]
            (service/create-batch! tx {:batch-id        (random-uuid)
                                       :organisation-id organisation-id
                                       :scheme          scheme
                                       :currency        currency
                                       :value-date      value-date
                                       :instruction-ids ids
                                       :actor           actor
                                       :correlation-id  (:correlation-id request)}))]
      (resp/created (str "/settlement-batches/" (:id batch))
                    (batch-document pool batch)))))

(defn submit
  "`POST /settlement-batches/:id/submit` — release every member to the simulated
  scheme.

  One transaction: every member moves `approved → released`, each posts its
  release entry, the batch moves `open → submitted`, and the audit trail gains
  one event per instruction plus one for the batch (AC-3)."
  [pool]
  (fn [request]
    (let [body (optional-object request)
          [actor organisation-id] (principal/for-request pool request :settlement/execute body)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")
          {:keys [batch]}
          (db/with-transaction [tx pool]
            (let [items (settlement/items-for tx id)]
              (service/submit-batch! tx {:organisation-id organisation-id
                                         :batch-id        id
                                         :actor           actor
                                         :correlation-id  (:correlation-id request)
                                         ;; One entry id per member, generated
                                         ;; here: the domain generates no
                                         ;; identifiers (ARCHITECTURE.md §4).
                                         :entry-ids       (mapv (fn [_] (random-uuid)) items)
                                         :occurred-at     (java.time.Instant/now)})))]
      (resp/ok (batch-document pool batch)))))

(defn record-response
  "`POST /settlement-batches/:id/scheme-responses` — **the simulation injection
  point**.

  A duplicate delivery — the same `(instructionId, kind, reference)` carrying
  the same message — is answered `200` with `replayed: true` and does no work:
  no second posting, no second audit event, no second transition (AC-5). That is
  the normal case in the world this simulates, not an error, so it is not
  reported as one. The body it returns is the *original* answer, `outcome`
  included (audit finding **F-009**).

  **The `409` is rendered here, after the transaction has committed**, and that
  ordering is the fix for audit finding **F-008**. The service returns a
  refusal as a value; the receipt for the refused arrival is inside the
  transaction this handler commits; only then does `err/conflict!` throw. Doing
  it the other way round — throwing from inside the unit of work, as this
  endpoint used to — rolled the receipt back with the rejection, so the first
  delivery was unprovable and the identical reference could perform work later
  against changed state (standing lesson **L-11**).

  Nothing between the commit and the throw can fail in a way that loses the
  receipt: the transaction is already durable, and a failure while rendering the
  problem document costs the caller its answer, not its evidence."
  [pool]
  (fn [request]
    (let [body (wire/read-object request)
          [actor organisation-id] (principal/for-request pool request :settlement/execute body)
          id   (wire/read-uuid (get-in request [:path-params :id]) "id")
          kind (wire/read-enum (get body "kind") "kind"
                               #{:ack :settled :returned :timeout-resolution})
          instruction-id (some-> (get body "instructionId") (wire/read-uuid "instructionId"))
          reference (wire/read-string-field body "reference")
          reason    (let [r (get body "reason")]
                      (when (and (string? r) (not (str/blank? r))) (str/trim r)))
          outcome   (when (some? (get body "outcome"))
                      (name (wire/read-enum (get body "outcome") "outcome"
                                            #{:settled :returned})))
          result
          (db/with-transaction [tx pool]
            (service/record-scheme-response!
             tx {:organisation-id organisation-id
                 :batch-id        id
                 :instruction-id  instruction-id
                 :kind            (name kind)
                 :reference       reference
                 :reason          reason
                 :outcome         outcome
                 :actor           actor
                 :correlation-id  (:correlation-id request)
                 :entry-id        (random-uuid)
                 :occurred-at     (java.time.Instant/now)}))]
      ;; Committed. The receipt exists whichever branch follows.
      (when (response/refused? (:disposition result))
        (err/conflict!
         (:detail result)
         {:batch-id          (str id)
          :instruction-id    (some-> instruction-id str)
          :kind              (name kind)
          :disposition       (:disposition result)
          :dispositionReason (:disposition-reason result)
          ;; True when this exact message has been refused before. The caller is
          ;; being told the same answer as last time, not a fresh evaluation
          ;; against state that has moved since (F-008).
          :replayed          (boolean (:replayed? result))
          :receiptId         (some-> (:receipt result) :id str)}))
      (resp/ok (assoc (batch-document pool (:batch result))
                      "replayed" (boolean (:replayed? result))
                      "disposition" (:disposition result)
                      "outcome" (:outcome result))))))

(defn sweep-timeouts
  "`POST /settlement-batches/:id/timeout-sweep` — stop waiting.

  An explicit operator action, not a daemon: a background scheduler is
  operational machinery with no driver yet, and a timeout that fires itself is
  one nobody can point at afterwards. `timeoutSeconds` measures the horizon from
  the batch's creation and defaults to
  `clofin.settlement.service/default-timeout-seconds`.

  Every item swept becomes `timed-out`, which means **unknown** — the
  instruction stays `released` and the item stays un-re-batchable (AC-6, AC-7)."
  [pool]
  (fn [request]
    (let [body (optional-object request)
          [actor organisation-id] (principal/for-request pool request :settlement/execute body)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")
          horizon (read-positive-int body "timeoutSeconds" service/default-timeout-seconds)
          result (db/with-transaction [tx pool]
                   (service/sweep-timeouts! tx {:organisation-id organisation-id
                                                :batch-id        id
                                                :actor           actor
                                                :correlation-id  (:correlation-id request)
                                                :horizon-seconds horizon}))]
      (resp/ok (assoc (batch-document pool (:batch result))
                      "timedOut" (mapv str (:timed-out result))
                      "timeoutSeconds" (:horizon-seconds result))))))

(defn show
  "`GET /settlement-batches/:id` — the batch, its items, its exceptions and
  every response recorded against it."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :payment/read)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")]
      (if-let [found (settlement/find-batch pool organisation-id id)]
        (resp/ok (batch-document pool found))
        (err/not-found! "No such settlement batch in this organisation" {:id (str id)})))))

(defn simulated-statement
  "`GET /settlement-statements` — **the simulated scheme's own account
  statement** for a period.

  A read: it computes what the simulation would have sent and writes nothing.
  The document it returns is in CloFin's own format and can be posted straight
  back to `POST /reconciliation-statements`, which is the whole walk a reviewer
  needs — generate, ingest, look at the breaks.

  `perturbation` names one of `clofin.settlement.statement/perturbation-classes`
  and defaults to `none`. A generator that could only produce agreement would be
  a generator that could not test disagreement, and a *named* class is what lets
  a reviewer predict the break before running anything.

  It lives in the settlement API rather than the reconciliation one because the
  scheme is what sends a statement. That is also what keeps the two sides
  honest: this endpoint reads the settlement tables and the matcher reads the
  journal, so nothing they agree about was derived from the other (standing
  lesson **L-16**)."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :payment/read)
          scheme   (batch/assert-scheme! (wire/read-query-param request "scheme"))
          currency (wire/read-query-param request "currency")
          from     (wire/read-instant (wire/read-query-param request "from") "from")
          to       (wire/read-instant (wire/read-query-param request "to") "to")
          class    (sim-statement/assert-perturbation!
                    (wire/read-optional-query-param request "perturbation"))
          _ (when-not (.isBefore from to)
              (err/invalid! "A statement period ends before it begins"
                            {:from (str from) :to (str to)}))
          {:keys [items truncated?]}
          (settlement/resolved-items-for pool organisation-id
                                         {:scheme scheme :currency currency
                                          :from from :to to})
          _ (when truncated?
              ;; Refused rather than truncated, for the reason
              ;; `clofin.recon.repository/expectation-cap` gives at the other
              ;; end: a statement silently missing its tail would produce breaks
              ;; against movements that are right there in the settlement
              ;; record, and no caller could tell that from a real disagreement.
              (err/fail! :unprocessable
                         (str "This period covers more settled items than one statement "
                              "may report; generate a shorter period")
                         {:limit settlement/row-cap :from (str from) :to (str to)}))]
      (resp/ok (sim-statement/generate {:scheme scheme
                                        :currency currency
                                        :period-start from
                                        :period-end to
                                        :perturbation class
                                        :items items})))))

(defn index
  "`GET /settlement-batches` — an organisation's batches, most recent first.

  Capped rather than paginated, with the cap and a `truncated` flag on every
  response (ADR-0011)."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :payment/read)
          status (wire/read-optional-query-param request "status")
          {:keys [batches truncated?]}
          (settlement/list-batches pool organisation-id {:status status})]
      (resp/ok {"settlementBatches" (mapv batch->wire batches)
                "count"     (count batches)
                "limit"     settlement/row-cap
                "truncated" (boolean truncated?)
                "simulated" true}))))
