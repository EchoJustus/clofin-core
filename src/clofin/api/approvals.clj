(ns clofin.api.approvals
  "Approval endpoints: the maker–checker boundary, over HTTP.

  Nothing in this namespace decides who may approve. The decision is
  `clofin.authz.approval/evaluate`, a pure function with no database and no
  HTTP; this layer reads a request, hands values to
  `clofin.payments.approval-service`, and renders whatever comes back. That
  split is the point of C-01, not an accident of layering: **if the only thing
  stopping self-approval were a check in this file, the control would not exist
  for any caller that did not come through this file.**

  What each status code means here (ADR-0012):

  - `400` — the request could not be understood: no `X-Actor-Id`, a malformed
    id, a missing `Idempotency-Key`. `401` when the actor header is absent or
    names nobody.
  - `403` — the actor may not do this: they submitted the instruction
    (`self-approval`), they hold no approval permission (`not-an-approver`), or
    the amount is above their limit (`above-actor-limit`). Every one of these
    carries `errors.reason`, so a client branches on the control rather than on
    the prose.
  - `409` — the lifecycle refuses the event, the actor has already decided, or
    an `Idempotency-Key` has been reused for a different request.
  - `422` — understood, and the organisation is not configured for it: a
    rejection with no reason, or no approval threshold for the currency.

  Both mutating operations are idempotent (PR-040), and the approval, the
  resulting state change and the audit event share one transaction (C-05)."
  (:require [clofin.api.principal :as principal]
            [clofin.api.wire :as wire]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clofin.idempotency :as idem]
            [clofin.idempotency.repository :as idem-store]
            [clofin.payments.approval-service :as approvals]
            [clojure.string :as str]))

(def ^:private idempotency-header "idempotency-key")

(defn- canonical-path
  "The request path, normalised the way the router normalises it.

  Identical to `clofin.api.payments`'s, and for the identical reason: the
  router discards empty segments, so a client that added a trailing slash on a
  retry must not be told its approval conflicts."
  [uri]
  (str "/" (str/join "/" (remove str/blank? (str/split (str uri) #"/")))))

(defn- request-digest
  [request]
  (idem/digest {"method" (str/upper-case (name (:request-method request)))
                "path"   (canonical-path (:uri request))
                "body"   (or (:json-body request) {})}))

(defn- idempotently
  [pool request organisation-id effect]
  (idem-store/execute-once!
   pool
   {:organisation-id organisation-id
    :key             (idem/read-key (get-in request [:headers idempotency-header]))
    :digest          (request-digest request)}
   effect))

(defn- respond
  ([outcome] (respond outcome {}))
  ([{:keys [status body replayed?]} headers]
   {:status  status
    :headers (cond-> (assoc headers "content-type" "application/json")
               replayed? (assoc "idempotent-replayed" "true"))
    :body    body}))

(defn- read-decision
  "The decision a caller is recording.

  `approved` or `rejected`, and nothing else — a decision CloFin does not
  recognise is refused rather than defaulted, because the safe default here
  would have to be one of the two and either choice is a decision nobody made."
  [body]
  (wire/read-enum (get body "decision") "decision" #{:approved :rejected}))

(defn- read-reason
  [body]
  (let [raw (get body "reason")]
    (cond
      (nil? raw) nil
      (string? raw) (let [trimmed (str/trim raw)] (when-not (str/blank? trimmed) trimmed))
      :else (err/invalid! "Field 'reason' must be a string" {:field "reason"}))))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn decide
  "`POST /payment-instructions/:id/approvals` — approve or reject.

  Returns `201` with the approval, the instruction in whatever state it now
  holds, and the counting context: how many approvals the amount requires and
  how many are now held. A first approval on a two-approval band leaves the
  instruction `pendingApproval` and says so in the same document, so an
  approver is never left guessing whether their decision was the last one
  needed.

  `:payment/approve` — or `:payment/reject` — is checked by
  `clofin.authz.approval/evaluate` rather than at the boundary, and that is
  deliberate. `evaluate` sees the instruction as well as the actor, so it can
  rank the reasons: segregation of duties first, because a maker never becomes
  a valid checker for their own payment whereas a missing role can be granted.
  Checking the permission out here would answer `not-an-approver` to an
  operator who had just tried to approve their own submission — true, and not
  the reason that governs. See `clofin.api.principal/authenticated-for`."
  [pool]
  (fn [request]
    (let [body     (wire/read-object request)
          decision (read-decision body)
          reason   (read-reason body)
          ;; Authenticated here; the permission is checked by `evaluate`, which
          ;; ranks it against the maker rule and the limit — see
          ;; `clofin.api.principal/authenticated-for` for why the boundary must
          ;; not pre-empt that ranking.
          [actor organisation-id] (principal/authenticated-for pool request body)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")
          outcome
          (idempotently
           pool request organisation-id
           (fn [tx]
             (let [{:keys [approval instruction decision]}
                   (approvals/decide! tx {:organisation-id organisation-id
                                          :instruction-id  id
                                          :actor           actor
                                          :decision        decision
                                          :reason          reason
                                          :correlation-id  (:correlation-id request)})]
               {:status 201
                :body {"approval"           (wire/approval->wire approval)
                       "paymentInstruction" (wire/instruction->wire instruction)
                       "approvalsRequired"  (:approvals-required decision)
                       "approvalsHeld"      (inc (:approvals-held decision))
                       "satisfied"          (boolean (:completes? decision))}})))]
      (respond outcome
               {"location" (str "/payment-instructions/" id
                                "/approvals/"
                                (get-in (:data outcome) ["approval" "id"]))}))))

(defn withdraw
  "`DELETE /payment-instructions/:id/approvals/:approvalId` — withdraw an
  approval.

  The row is **not** deleted. `invalidated_at` is set and the decision stays
  visible, because an approval that was given and then withdrawn is exactly the
  history an investigation needs — and `approval_no_delete` refuses a `DELETE`
  at the database in any case.

  Only the actor who gave an approval may withdraw it, and only while the
  instruction is still `pendingApproval`. Once the threshold is met the way
  back is to amend, which invalidates every approval and returns the
  instruction to `draft` (PR-014)."
  [pool]
  (fn [request]
    (let [[actor organisation-id] (principal/for-request pool request :payment/approve)
          id          (wire/read-uuid (get-in request [:path-params :id]) "id")
          approval-id (wire/read-uuid (get-in request [:path-params :approvalId]) "approvalId")
          outcome
          (idempotently
           pool request organisation-id
           (fn [tx]
             (let [{:keys [approval instruction]}
                   (approvals/withdraw! tx {:organisation-id organisation-id
                                            :instruction-id  id
                                            :approval-id     approval-id
                                            :actor           actor
                                            :correlation-id  (:correlation-id request)})]
               {:status 200
                :body {"approval"           (wire/approval->wire approval)
                       "paymentInstruction" (wire/instruction->wire instruction)}})))]
      (respond outcome))))

(defn queue
  "`GET /approvals/queue` — what is waiting, with the context to decide it.

  Each row carries amount, counterparty, purpose, the approvals already given
  and how many more are required (PR-015, AC-13). Rows this actor may not
  approve are shown with `canApprove: false` and the reason, rather than being
  filtered out: hiding them would be a control implemented in a list query, and
  it would leave a maker unable to see that their own payment is waiting.

  JSON, not a UI. The approval queue screen is increment 8."
  [pool]
  (fn [request]
    (let [[actor organisation-id] (principal/for-request pool request :approval/read)
          {:keys [items truncated?]} (approvals/queue pool organisation-id actor)]
      (resp/ok {"approvalQueue" (mapv #(wire/approval-queue-row->wire % wire/instruction->wire)
                                      items)
                "count"     (count items)
                "actor"     (wire/actor->wire actor)
                "truncated" (boolean truncated?)}))))
