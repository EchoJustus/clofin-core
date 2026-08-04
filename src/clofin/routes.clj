(ns clofin.routes
  "The route table.

  Routes are data. Keeping them in one value — rather than scattered across
  handler namespaces via macros — means the API surface can be read at a
  glance, diffed in review, and compared against `api/openapi.yaml` by a test.

  `operation-id` matches the corresponding OpenAPI `operationId`; that is the
  join key the contract test uses."
  (:require [clofin.api.accounts :as accounts]
            [clofin.api.approvals :as approvals]
            [clofin.api.audit :as audit]
            [clofin.api.entries :as entries]
            [clofin.api.health :as health]
            [clofin.api.organisations :as organisations]
            [clofin.api.payments :as payments]
            [clofin.api.settlement :as settlement]))

(defn routes
  "Build the route table for a running system."
  [{:keys [config pool]}]
  [{:method :get :path "/healthz" :operation-id "getHealth"
    :handler (health/healthz config)
    :summary "Liveness probe"}

   {:method :get :path "/readyz" :operation-id "getReadiness"
    :handler (health/readyz config pool)
    :summary "Readiness probe including database reachability"}

   {:method :get :path "/" :operation-id "getServiceInfo"
    :handler (health/info config)
    :summary "Service description and scope disclaimer"}

   ;; -------------------------------------------------------------------------
   ;; Organisations
   ;; -------------------------------------------------------------------------

   {:method :post :path "/organisations" :operation-id "createOrganisation"
    :handler (organisations/create pool)
    :summary "Register a synthetic organisation"}

   {:method :get :path "/organisations/:id" :operation-id "getOrganisation"
    :handler (organisations/show pool)
    :summary "Retrieve an organisation"}

   ;; -------------------------------------------------------------------------
   ;; Ledger accounts
   ;; -------------------------------------------------------------------------

   {:method :post :path "/accounts" :operation-id "createAccount"
    :handler (accounts/create pool)
    :summary "Open a ledger account"}

   {:method :get :path "/accounts" :operation-id "listAccounts"
    :handler (accounts/index pool)
    :summary "List an organisation's chart of accounts"}

   {:method :get :path "/accounts/:id" :operation-id "getAccount"
    :handler (accounts/show pool)
    :summary "Retrieve a ledger account"}

   {:method :get :path "/accounts/:id/statement" :operation-id "getAccountStatement"
    :handler (accounts/statement pool)
    :summary "Produce an account statement for a period"}

   ;; -------------------------------------------------------------------------
   ;; Journal
   ;; -------------------------------------------------------------------------

   {:method :post :path "/journal-entries" :operation-id "postJournalEntry"
    :handler (entries/post-entry pool)
    :summary "Post a balanced journal entry"}

   {:method :get :path "/journal-entries/:id" :operation-id "getJournalEntry"
    :handler (entries/show pool)
    :summary "Retrieve a posted journal entry"}

   ;; -------------------------------------------------------------------------
   ;; Payment instructions
   ;;
   ;; A lifecycle event is a sub-resource (`/submission`, `/cancellation`)
   ;; rather than a `status` field a caller writes. Naming the event leaves
   ;; `clofin.payments.state/transitions` the only thing that decides where an
   ;; instruction goes next; naming the state would put a second copy of the
   ;; state machine in every client. See ADR-0014.
   ;; -------------------------------------------------------------------------

   {:method :post :path "/payment-instructions" :operation-id "createPaymentInstruction"
    :handler (payments/create pool)
    :summary "Capture a payment instruction"}

   {:method :get :path "/payment-instructions" :operation-id "listPaymentInstructions"
    :handler (payments/index pool)
    :summary "List an organisation's payment instructions"}

   {:method :get :path "/payment-instructions/:id" :operation-id "getPaymentInstruction"
    :handler (payments/show pool)
    :summary "Retrieve a payment instruction"}

   {:method :patch :path "/payment-instructions/:id" :operation-id "amendPaymentInstruction"
    :handler (payments/amend pool)
    :summary "Amend a draft payment instruction"}

   {:method :post :path "/payment-instructions/:id/submission"
    :operation-id "submitPaymentInstruction"
    :handler (payments/submit pool)
    :summary "Submit a payment instruction for approval"}

   {:method :post :path "/payment-instructions/:id/cancellation"
    :operation-id "cancelPaymentInstruction"
    :handler (payments/cancel pool)
    :summary "Cancel a payment instruction"}

   ;; -------------------------------------------------------------------------
   ;; Approvals
   ;;
   ;; An approval is a sub-resource of the instruction it decides, because that
   ;; is what it is: a decision by one actor about one payment, which can be
   ;; addressed, withdrawn and evidenced on its own. A `status` field a checker
   ;; wrote would lose the identity of the decision and with it the ability to
   ;; say who agreed to what (C-01, C-02).
   ;; -------------------------------------------------------------------------

   {:method :post :path "/payment-instructions/:id/approvals"
    :operation-id "approvePaymentInstruction"
    :handler (approvals/decide pool)
    :summary "Approve or reject a payment instruction"}

   {:method :delete :path "/payment-instructions/:id/approvals/:approvalId"
    :operation-id "withdrawApproval"
    :handler (approvals/withdraw pool)
    :summary "Withdraw an approval already given"}

   {:method :get :path "/approvals/queue"
    :operation-id "getApprovalQueue"
    :handler (approvals/queue pool)
    :summary "List instructions awaiting approval with the context to decide them"}

   ;; -------------------------------------------------------------------------
   ;; Settlement
   ;;
   ;; Batches are constructed, submitted and then *answered*. The answer arrives
   ;; through `scheme-responses` — the simulation injection point — because
   ;; CloFin connects to no scheme and there is nothing to listen to. The sweep
   ;; is an explicit operator call rather than a daemon: a timeout that fires
   ;; itself is one nobody can point at afterwards.
   ;; -------------------------------------------------------------------------

   {:method :post :path "/settlement-batches" :operation-id "createSettlementBatch"
    :handler (settlement/create pool)
    :summary "Group approved payment instructions into a settlement batch"}

   {:method :get :path "/settlement-batches" :operation-id "listSettlementBatches"
    :handler (settlement/index pool)
    :summary "List an organisation's settlement batches"}

   {:method :get :path "/settlement-batches/:id" :operation-id "getSettlementBatch"
    :handler (settlement/show pool)
    :summary "Retrieve a settlement batch with its items and scheme responses"}

   {:method :post :path "/settlement-batches/:id/submit"
    :operation-id "submitSettlementBatch"
    :handler (settlement/submit pool)
    :summary "Release a settlement batch to its simulated scheme"}

   {:method :post :path "/settlement-batches/:id/scheme-responses"
    :operation-id "recordSchemeResponse"
    :handler (settlement/record-response pool)
    :summary "Record a simulated scheme response against a settlement batch"}

   {:method :post :path "/settlement-batches/:id/timeout-sweep"
    :operation-id "sweepSettlementTimeouts"
    :handler (settlement/sweep-timeouts pool)
    :summary "Mark unanswered settlement items as timed out"}

   ;; -------------------------------------------------------------------------
   ;; Audit
   ;; -------------------------------------------------------------------------

   {:method :get :path "/audit/events"
    :operation-id "listAuditEvents"
    :handler (audit/index pool)
    :summary "List an organisation's audit events"}

   {:method :get :path "/audit/evidence/:subjectId"
    :operation-id "getEvidencePack"
    :handler (audit/evidence pool)
    :summary "Extract a complete evidence pack for one subject"}])
