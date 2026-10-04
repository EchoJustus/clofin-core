(ns clofin.api.payments-api-test
  "The payment instruction API end to end, without a socket.

  These call the fully-wrapped handler — router, middleware, error translation,
  JSON codec — with a request map and assert on the response. That is the whole
  stack a caller meets, minus Jetty, which `clofin.system-test` covers
  separately (ADR-0010).

  The database is real, because every acceptance criterion here is a statement
  about what is persisted and what is not — and `AC-9` in particular is a claim
  about two transactions contending for one row, which no substitute has.

  Acceptance criteria from docs/briefs/002-TASK-payment-instruction-lifecycle.md
  are named in the tests that cover them."
  (:require [clofin.api.payments :as payments-api]
            [clofin.db.core :as db]
            [clofin.idempotency.repository :as idem-store]
            [clofin.payments.repository :as payments]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time LocalDate ZoneOffset]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private today (LocalDate/now ZoneOffset/UTC))

;; ---------------------------------------------------------------------------
;; Calling the API
;; ---------------------------------------------------------------------------

(defn- handler [] (system/handler {:config {:environment :test} :pool tdb/*pool*}))

(def ^:private current-actor
  "The actor every call authenticates as, unless one is named per request.

  Set by `setup`, which seeds it. There is deliberately no default and no
  fallback: a request with no actor is a `401`, which is what an unauthenticated
  caller should get now that TASK-003 has replaced the caller-asserted
  `organisationId` with a real principal."
  (atom nil))

(defn- call
  "Issue a request through the whole stack and decode the response body.

  `:actor` names the actor to authenticate as; `false` sends no actor header at
  all, which is how the unauthenticated cases are exercised."
  ([method uri] (call (handler) method uri {}))
  ([method uri opts] (call (handler) method uri opts))
  ([h method uri {:keys [body query idempotency-key actor]}]
   ;; A `Location` header carries its query string inline, and the HTTP adapter
   ;; splits the two before a handler ever sees them. Splitting here is what
   ;; lets a test follow a Location the way a client does.
   (let [[path inline-query] (str/split uri #"\?" 2)
         query (or query inline-query)
         ;; nil means "the fixture's actor"; false means "send no actor header".
         actor (if (nil? actor) @current-actor actor)
         response (h (cond-> {:request-method method :uri path :headers {}}
                       query (assoc :query-string query)
                       actor (assoc-in [:headers "x-actor-id"] (str actor))
                       idempotency-key (assoc-in [:headers "idempotency-key"] idempotency-key)
                       body (-> (assoc-in [:headers "content-type"] "application/json")
                                (assoc :body (ByteArrayInputStream.
                                              (.getBytes (json/write-str body)
                                                         StandardCharsets/UTF_8))))))]
     (assoc response :json (when-not (str/blank? (:body response))
                             (json/read-str (:body response)))))))

(defn- key! [] (str (random-uuid)))

(defn- created!
  [uri body]
  (let [{:keys [status json] :as response} (call :post uri {:body body :idempotency-key (key!)})]
    (is (= 201 status) (str "expected 201 from " uri ", body was " (:body response)))
    json))

;; ---------------------------------------------------------------------------
;; Fixtures, built through the API itself
;; ---------------------------------------------------------------------------

(defn- new-organisation!
  ([] (new-organisation! (str "meridian-" (rand-int 1000000))))
  ([short-name]
   (let [{:keys [status json]} (call :post "/organisations"
                                     {:body {"legalName" "Meridian Freight Holdings Pte Ltd"
                                             "shortName" short-name}})]
     (is (= 201 status))
     json)))

(defn- new-account!
  [org & {:keys [code currency] :or {currency "SGD"}}]
  (let [{:keys [status json]}
        (call :post "/accounts" {:body {"organisationId" (get org "id")
                                        "code" (or code (str "1100-CLIENT-FUNDS-" (rand-int 1000000)))
                                        "name" "Client funds — pooled"
                                        "type" "asset"
                                        "currency" currency}})]
    (is (= 201 status))
    json))

(defn- seed-actor!
  "Seed an actor with exactly the roles named — no more.

  There is no `insert-superuser!` to reach for here, deliberately (C-08). Each
  fixture states the rights it needs, which makes it readable as documentation
  of what a role can do."
  [org roles & {:keys [limits status] :or {limits {} status "active"}}]
  (tdb/insert-actor! tdb/*pool*
                     {:organisation-id (java.util.UUID/fromString (get org "id"))
                      :display-name (str/join "+" (map name roles))
                      :roles roles :limits limits :status status}))

(defn- setup
  "An organisation, an account, and the actors these tests act as.

  `POST /organisations` is the bootstrap operation and is unauthenticated —
  there is no actor until an organisation exists to hold one. Everything after
  it authenticates."
  []
  (let [org (new-organisation!)
        controller (seed-actor! org [:controller])
        maker (seed-actor! org [:operator])
        _ (reset! current-actor controller)
        account (new-account! org)]
    (reset! current-actor maker)
    {:org org :account account :maker maker :controller controller}))

(defn- instruction-body
  [{:keys [org account]} & {:as overrides}]
  (merge {"organisationId"  (get org "id")
          "debtorAccountId" (get account "id")
          "creditorName"    "Pacific Rim Logistics Pte Ltd"
          "creditorAccount" "SG-SYNTH-88012345"
          "amount"          {"currency" "SGD" "minorUnits" 125000}
          "valueDate"       (str (.plusDays today 7))
          "purposeCode"     "SUPP"}
         overrides))

(defn- new-instruction! [f & {:as overrides}]
  (created! "/payment-instructions" (instruction-body f overrides)))

(defn- action-body [{:keys [org]}] {"organisationId" (get org "id")})

(defn- submit!
  [f instruction & {:keys [idempotency-key] :or {idempotency-key nil}}]
  (call :post (str "/payment-instructions/" (get instruction "id") "/submission")
        {:body (action-body f) :idempotency-key (or idempotency-key (key!))}))

(defn- instruction-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from payment_instruction"])))

(defn- key-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from idempotency_key"])))

(defn- status-of [f instruction]
  (get (:json (call :get (str "/payment-instructions/" (get instruction "id"))
                    {:query (str "organisationId=" (get-in f [:org "id"]))}))
       "status"))

;; ---------------------------------------------------------------------------
;; AC-1 — creation
;; ---------------------------------------------------------------------------

(deftest ac-1-a-valid-instruction-is-created-as-a-draft
  (let [f (setup)
        {:keys [status json headers]}
        (call :post "/payment-instructions"
              {:body (instruction-body f) :idempotency-key (key!)})]
    (is (= 201 status))
    (is (= "draft" (get json "status")))
    (is (some? (get headers "location")) "a 201 must say where the resource is")
    (is (= {"currency" "SGD" "minorUnits" 125000} (get json "amount"))
        "money stays an integer count of minor units on the wire")
    (is (= (str (.plusDays today 7)) (get json "valueDate")))
    (is (some? (get json "createdAt")))
    (is (= ["cancel" "submit"] (get json "permittedTransitions"))
        "derived from the lifecycle table, so it cannot advertise a refused operation")
    (is (not (contains? json "reversesId")) "absent on an instruction that is not a reversal")

    (testing "and the Location header addresses a readable resource"
      (let [{:keys [status json]} (call :get (get headers "location"))]
        (is (= 200 status))
        (is (= "draft" (get json "status")))))))

(deftest an-instruction-in-another-organisation-is-not-found
  (let [f (setup)
        pi (new-instruction! f)
        other (setup)]
    (testing "an actor in another organisation cannot read it, even knowing its id"
      (is (= 404 (:status (call :get (str "/payment-instructions/" (get pi "id")))))
          "the same answer a non-existent id receives — anything else is a tenancy disclosure"))

    (testing "and naming someone else's organisation in the query is refused outright"
      (let [{:keys [status json]}
            (call :get (str "/payment-instructions/" (get pi "id"))
                  {:query (str "organisationId=" (get-in f [:org "id"]))})]
        (is (= 403 status)
            "organisationId is verified against the principal now, not trusted (TASK-003)")
        (is (= "https://clofin.dev/problems/forbidden" (get json "type")))))

    (testing "and an unauthenticated caller gets no further than 401"
      (is (= 401 (:status (call :get (str "/payment-instructions/" (get pi "id"))
                                {:actor false})))))

    (testing "while its own organisation's actor reads it"
      (reset! current-actor (:maker f))
      (is (= 200 (:status (call :get (str "/payment-instructions/" (get pi "id")))))))
    (identity other)))

(deftest instructions-can-be-listed-and-filtered
  (let [f (setup)
        a (new-instruction! f)
        _ (new-instruction! f)
        _ (submit! f a)
        org-id (get-in f [:org "id"])
        {:keys [status json]} (call :get "/payment-instructions"
                                    {:query (str "organisationId=" org-id)})]
    (is (= 200 status))
    (is (= 2 (get json "count")))
    (is (= 500 (get json "limit")))
    (is (false? (get json "truncated")) "stated on every response, not only when true")

    (testing "filtered to one lifecycle state"
      (let [filtered (:json (call :get "/payment-instructions"
                                  {:query (str "organisationId=" org-id "&status=pending-approval")}))]
        (is (= 1 (get filtered "count")))
        (is (= (get a "id") (get-in filtered ["paymentInstructions" 0 "id"])))))

    (testing "a status outside the lifecycle is a 400, not an empty list"
      (is (= 400 (:status (call :get "/payment-instructions"
                                {:query (str "organisationId=" org-id "&status=in-flight")})))))))

;; ---------------------------------------------------------------------------
;; AC-2 — every failed field
;; ---------------------------------------------------------------------------

(deftest ac-2-three-invalid-fields-are-all-named
  (let [f (setup)
        {:keys [status json]}
        (call :post "/payment-instructions"
              {:idempotency-key (key!)
               :body (instruction-body f
                                       "amount" {"currency" "SGD" "minorUnits" 0}
                                       "valueDate" (str (.minusDays today 1))
                                       "purposeCode" "XXXX")})]
    (is (= 422 status))
    (is (= "https://clofin.dev/problems/validation" (get json "type"))
        "the same problem type a 400 validation failure carries — status separates them")
    (is (= "Request failed validation" (get json "title")))
    (is (= {"amount"      "must be greater than zero"
            "valueDate"   "must not be in the past"
            "purposeCode" "unknown purpose code: XXXX"}
           (get json "errors"))
        "all three, under the names the caller sent — not the first one")
    (is (= 0 (instruction-count)) "and nothing was persisted")))

(deftest a-field-that-could-not-be-parsed-is-named-alongside-one-that-was
  (let [f (setup)
        {:keys [status json]}
        (call :post "/payment-instructions"
              {:idempotency-key (key!)
               :body (instruction-body f
                                       "debtorAccountId" "not-a-uuid"
                                       "purposeCode" "XXXX")})]
    (is (= 422 status))
    (is (= "must be a UUID" (get-in json ["errors" "debtorAccountId"]))
        "the parse failure wins over the domain's \"is required\"")
    (is (= "unknown purpose code: XXXX" (get-in json ["errors" "purposeCode"])))))

(deftest an-omitted-field-is-reported-as-required
  (let [f (setup)
        {:keys [status json]}
        (call :post "/payment-instructions"
              {:idempotency-key (key!)
               :body (dissoc (instruction-body f) "creditorName" "amount")})]
    (is (= 422 status))
    (is (= "is required" (get-in json ["errors" "creditorName"])))
    (is (= "is required" (get-in json ["errors" "amount"])))))

(deftest a-rejected-request-does-not-consume-its-key
  (testing "so a caller that fixes its body and retries under the same key is
            executed rather than told the body changed"
    (let [f (setup)
          k (key!)
          bad (call :post "/payment-instructions"
                    {:idempotency-key k
                     :body (instruction-body f "purposeCode" "XXXX")})]
      (is (= 422 (:status bad)))
      ;; Asserted here, between the two calls: the key must be gone *before*
      ;; the retry, or the retry would be a 409 rather than an execution.
      (is (= 0 (key-count)) "the key row rolled back with the effect")

      (let [good (call :post "/payment-instructions"
                       {:idempotency-key k :body (instruction-body f)})]
        (is (= 201 (:status good))
            "a corrected retry under the same key executes, and is not told the body changed")
        (is (= 1 (instruction-count)))
        (is (= 1 (key-count)))))))

(deftest a-debtor-account-problem-is-422-not-a-field-error
  (let [other (setup)
        f (setup)                       ; created second, so its maker is current
        {:keys [status json]}
        (call :post "/payment-instructions"
              {:idempotency-key (key!)
               :body (instruction-body f "debtorAccountId" (get-in other [:account "id"]))})]
    (is (= 422 status))
    (is (= "https://clofin.dev/problems/unprocessable" (get json "type")))
    (is (= 0 (instruction-count)))))

;; ---------------------------------------------------------------------------
;; AC-3 / AC-4 — amend and submit
;; ---------------------------------------------------------------------------

(deftest ac-3-a-draft-can-be-amended
  (let [f (setup)
        pi (new-instruction! f)
        {:keys [status json]}
        (call :patch (str "/payment-instructions/" (get pi "id"))
              {:idempotency-key (key!)
               :body {"organisationId" (get-in f [:org "id"])
                      "amount" {"currency" "SGD" "minorUnits" 99900}
                      "creditorName" "Andaman Shipping Sdn Bhd"}})]
    (is (= 200 status))
    (is (= {"currency" "SGD" "minorUnits" 99900} (get json "amount")))
    (is (= "Andaman Shipping Sdn Bhd" (get json "creditorName")))
    (is (= "draft" (get json "status")) "an amendment is not a transition")
    (is (= (get pi "id") (get json "id")) "and not a new instruction either")))

(deftest ac-7-amending-a-submitted-instruction-returns-it-to-draft
  (testing "PR-014. TASK-002 refused this because the approval-invalidation
            behind it did not exist; TASK-003 built it, and ADR-0014 amendment 1
            records the change."
    (let [f (setup)
          pi (new-instruction! f)]
      (is (= 200 (:status (submit! f pi))))
      (let [{:keys [status json]}
            (call :patch (str "/payment-instructions/" (get pi "id"))
                  {:idempotency-key (key!)
                   :body {"organisationId" (get-in f [:org "id"])
                          "amount" {"currency" "SGD" "minorUnits" 1}}})]
        (is (= 200 status))
        (is (= "draft" (get json "status"))
            "a submitted instruction that is amended goes back to draft, not forward")
        (is (= 1 (get-in json ["amount" "minorUnits"]))))
      (is (= "draft" (status-of f pi))))))

(deftest pr-004-only-the-creator-may-amend
  (testing "a real access control now that createdBy is an authenticated principal"
    (let [f (setup)
          pi (new-instruction! f)
          someone-else (seed-actor! (:org f) [:operator])
          {:keys [status json]}
          (call :patch (str "/payment-instructions/" (get pi "id"))
                {:idempotency-key (key!)
                 :actor someone-else
                 :body {"organisationId" (get-in f [:org "id"]) "purposeCode" "TRAD"}})]
      (is (= 403 status))
      (is (= "https://clofin.dev/problems/forbidden" (get json "type")))
      (is (= "SUPP" (get (:json (call :get (str "/payment-instructions/" (get pi "id"))))
                         "purposeCode"))))))

(deftest an-operator-cannot-approve-and-an-approver-cannot-create
  (testing "C-08 at the API boundary: the permission is checked before anything is read"
    (let [f (setup)
          approver (seed-actor! (:org f) [:approver] :limits {"SGD" 10000000})
          pi (new-instruction! f)]
      (is (= 403 (:status (call :post "/payment-instructions"
                                {:idempotency-key (key!) :actor approver
                                 :body (instruction-body f)})))
          "an approver may not raise a payment")
      (submit! f pi)
      (is (= 403 (:status (call :post (str "/payment-instructions/" (get pi "id") "/approvals")
                                {:idempotency-key (key!) :actor (:maker f)
                                 :body {"decision" "approved"}})))
          "and an operator may not approve one — here the maker, so the reason
           that governs is self-approval rather than the missing permission"))))

(deftest createdBy-is-the-authenticated-actor-and-cannot-be-set-by-the-caller
  (let [f (setup)
        pi (new-instruction! f)]
    (is (= (str (:maker f)) (get pi "createdBy"))
        "no longer a caller assertion — this is who the request authenticated as")
    (let [{:keys [status json]}
          (call :post "/payment-instructions"
                {:idempotency-key (key!)
                 :body (instruction-body f "createdBy" (str (random-uuid)))})]
      (is (= 422 status))
      (is (some? (get-in json ["errors" "createdBy"]))
          "rejected rather than ignored: silently overriding it would leave the caller
           believing it had recorded who raised the payment"))))

(deftest amending-something-that-is-not-amendable-is-rejected-not-ignored
  (let [f (setup)
        pi (new-instruction! f)]
    (doseq [member ["status" "createdBy" "id" "reversesId" "createdAt" "retriesId"]]
      (let [{:keys [status json]}
            (call :patch (str "/payment-instructions/" (get pi "id"))
                  {:idempotency-key (key!)
                   :body {"organisationId" (get-in f [:org "id"]) member "anything"}})]
        (is (= 422 status) (str member " must be refused"))
        (is (= "cannot be amended" (get-in json ["errors" member]))
            "silently dropping it would leave the caller believing it changed something")))))

(deftest ac-12-a-matching-organisation-id-asserts-the-tenant-and-amends-nothing
  (testing "residual C-R10a. `organisationId` is not substance: sending it
            scopes the request, exactly as it does on every other body in this
            contract, and it is verified rather than trusted. The contract
            listed it among the members a PATCH refuses `422`, and the release
            audit's single authorised probe supplied a matching one with a
            changed creditorName and was answered 200"
    (let [f (setup)
          pi (new-instruction! f)
          {:keys [status json]}
          (call :patch (str "/payment-instructions/" (get pi "id"))
                {:idempotency-key (key!)
                 :body {"organisationId" (get-in f [:org "id"])
                        "creditorName" "Pacific Rim Logistics Ltd"}})]
      (is (= 200 status) (str "a matching organisationId must not be refused — " json))
      (is (= "Pacific Rim Logistics Ltd" (get json "creditorName")))
      (is (= (get-in f [:org "id"]) (get json "organisationId"))
          "and the tenant is the one it always was: the field asserted scope,
           it did not amend identity")
      (is (= "draft" (get json "status"))))))

(deftest ac-4-submitting-a-draft-reaches-pending-approval
  (let [f (setup)
        pi (new-instruction! f)
        {:keys [status json]} (submit! f pi)]
    (is (= 200 status))
    (is (= "pending-approval" (get json "status")))
    (is (= ["amend" "approve" "reject"] (get json "permittedTransitions")))
    (is (= "pending-approval" (status-of f pi)) "and the stored row moved"))

  (testing "there is no approve operation — approval is TASK-003"
    (let [f (setup)
          pi (new-instruction! f)]
      (submit! f pi)
      (is (= 404 (:status (call :post (str "/payment-instructions/" (get pi "id") "/approval")
                                {:body {} :idempotency-key (key!)})))))))

(deftest a-draft-can-be-cancelled-and-a-cancelled-one-can-do-nothing-more
  (let [f (setup)
        pi (new-instruction! f)
        {:keys [status json]}
        (call :post (str "/payment-instructions/" (get pi "id") "/cancellation")
              {:body (action-body f) :idempotency-key (key!)})]
    (is (= 200 status))
    (is (= "cancelled" (get json "status")))
    (is (= [] (get json "permittedTransitions")))
    (is (= 409 (:status (submit! f pi))))))

;; ---------------------------------------------------------------------------
;; AC-5 — a terminal instruction refuses everything, and says what it refused
;; ---------------------------------------------------------------------------

(defn- settle!
  "Walk an instruction to `settled`, one lifecycle event at a time.

  There is no endpoint for the later events in this increment — approval is
  TASK-003 and settlement is increment 5 — so the walk goes through the
  repository. It still goes through `transition!`, so the fixture cannot reach
  a state the state machine would not have permitted."
  [f pi]
  (doseq [event [:submit :approve :release :settle]]
    (payments/transition! tdb/*pool*
                          (java.util.UUID/fromString (get-in f [:org "id"]))
                          (java.util.UUID/fromString (get pi "id"))
                          event
                          ;; `:submit` is creator-only (F-001). `new-instruction!`
                          ;; creates as the fixture's maker, so this walk is one
                          ;; that actor could really have performed.
                          {:actor {:id (:maker f)}}))
  pi)

(deftest amending-a-terminal-instruction-is-still-refused
  (testing "the lifecycle table decides: `settled` has no `amend` arrow, so 409 naming the state"
    (let [f (setup)
          pi (settle! f (new-instruction! f))
          {:keys [status json]}
          (call :patch (str "/payment-instructions/" (get pi "id"))
                {:idempotency-key (key!)
                 :body {"organisationId" (get-in f [:org "id"])
                        "amount" {"currency" "SGD" "minorUnits" 1}}})]
      (is (= 409 status))
      (is (= "settled" (get-in json ["errors" "instruction-status"])))
      (is (= "amend" (get-in json ["errors" "attempted"])))
      (is (= 125000 (get-in (:json (call :get (str "/payment-instructions/" (get pi "id"))))
                            ["amount" "minorUnits"]))
          "and nothing changed"))))

(deftest ac-5-a-settled-instruction-refuses-every-transition-by-name
  (let [f (setup)
        pi (settle! f (new-instruction! f))]
    (is (= "settled" (status-of f pi)))
    (doseq [[sub-resource event] {"submission" "submit" "cancellation" "cancel"}]
      (let [{:keys [status json]}
            (call :post (str "/payment-instructions/" (get pi "id") "/" sub-resource)
                  {:body (action-body f) :idempotency-key (key!)})]
        (is (= 409 status))
        (is (= "https://clofin.dev/problems/conflict" (get json "type")))
        (is (= (str "Cannot " event " a payment instruction that is settled")
               (get json "detail")))
        (is (= "settled" (get-in json ["errors" "instruction-status"])))
        (is (= event (get-in json ["errors" "attempted"])))
        (is (= [] (get-in json ["errors" "permitted"])))))))

;; ---------------------------------------------------------------------------
;; AC-6, AC-7, AC-8 — idempotency
;; ---------------------------------------------------------------------------

(deftest ac-6-a-replayed-key-with-an-identical-body-returns-the-stored-response
  (let [f (setup)
        k (key!)
        body (instruction-body f)
        first-call (call :post "/payment-instructions" {:body body :idempotency-key k})
        replay (call :post "/payment-instructions" {:body body :idempotency-key k})]
    (is (= 201 (:status first-call)))
    (is (= 201 (:status replay)) "the stored status, not a fresh 201")
    (is (= (:body first-call) (:body replay)) "byte-identical, including the id")
    (is (= "true" (get-in replay [:headers "idempotent-replayed"])))
    (is (nil? (get-in first-call [:headers "idempotent-replayed"])))
    (is (= (get-in first-call [:headers "location"]) (get-in replay [:headers "location"]))
        "a replayed 201 points at the resource the original created")
    (is (= 1 (instruction-count)) "**no second row was written**")
    (is (= 1 (key-count)))))

(deftest ac-6-a-replay-that-differs-only-in-representation-is-still-a-replay
  (testing "a caller's HTTP client reordering keys on a retry must not become a
            409 — a 409 would push it to mint a new key, which is a second payment"
    (let [f (setup)
          k (key!)
          body (instruction-body f)
          first-call (call :post "/payment-instructions" {:body body :idempotency-key k})
          ;; Same members, different order. `json/write-str` over a sorted map
          ;; emits them in a different sequence than the map above.
          replay (call :post "/payment-instructions"
                       {:body (into (sorted-map-by #(compare %2 %1)) body)
                        :idempotency-key k})]
      (is (= 201 (:status first-call)))
      (is (= 201 (:status replay)))
      (is (= (:body first-call) (:body replay)))
      (is (= 1 (instruction-count))))))

(deftest ac-6-replay-covers-every-mutating-operation-not-only-creation
  (let [f (setup)
        pi (new-instruction! f)
        k (key!)
        first-call (submit! f pi :idempotency-key k)
        replay (submit! f pi :idempotency-key k)]
    (is (= 200 (:status first-call)))
    (is (= (:body first-call) (:body replay)))
    (is (= "true" (get-in replay [:headers "idempotent-replayed"])))
    (is (= "pending-approval" (status-of f pi))
        "and the replay did not attempt a second transition")))

(deftest ac-7-the-same-key-with-a-different-body-is-a-conflict
  (let [f (setup)
        k (key!)
        first-call (call :post "/payment-instructions"
                         {:body (instruction-body f) :idempotency-key k})
        conflict (call :post "/payment-instructions"
                       {:body (instruction-body f "amount" {"currency" "SGD" "minorUnits" 999})
                        :idempotency-key k})]
    (is (= 201 (:status first-call)))
    (is (= 409 (:status conflict)))
    (is (= "https://clofin.dev/problems/conflict" (get-in conflict [:json "type"])))
    (is (= 1 (instruction-count)) "and nothing was executed")))

(deftest ac-8-a-mutating-request-without-a-key-is-rejected
  (let [f (setup)
        pi (new-instruction! f)
        org-id (get-in f [:org "id"])]
    (testing "PR-040, as far as it is built — every mutating operation *in this
              namespace* requires an Idempotency-Key. The whole seventeen-route
              sweep is `clofin.api.conformance-test`'s (2B-009)"
      (doseq [[method uri body]
              [[:post "/payment-instructions" (instruction-body f)]
               [:patch (str "/payment-instructions/" (get pi "id"))
                {"organisationId" org-id "purposeCode" "TRAD"}]
               [:post (str "/payment-instructions/" (get pi "id") "/submission")
                (action-body f)]
               [:post (str "/payment-instructions/" (get pi "id") "/cancellation")
                (action-body f)]]]
        (let [{:keys [status json]} (call method uri {:body body})]
          (is (= 400 status) (str method " " uri " must require a key"))
          (is (= "https://clofin.dev/problems/validation" (get json "type")))
          (is (str/includes? (get json "detail") "Idempotency-Key")))))

    (testing "and a blank one does not count as one"
      (is (= 400 (:status (call :post "/payment-instructions"
                                {:body (instruction-body f) :idempotency-key "   "})))))

    (is (= 1 (instruction-count)) "no rejected request executed anything")))

(deftest one-key-cannot-replay-across-two-instructions-submissions
  (testing "the failure that ruling O-3 closed. Two submissions carry byte-identical
            bodies — `{\"organisationId\": …}` — and differ only in their path. Under
            a body-only digest the second was a replay of the first: it returned
            `200`, and its instruction was never submitted while the operator saw
            success. The digest covers method, path and body, so it is a `409`."
    (let [f (setup)
          a (new-instruction! f)
          b (new-instruction! f)
          k (key!)
          body (action-body f)
          first-call (call :post (str "/payment-instructions/" (get a "id") "/submission")
                           {:body body :idempotency-key k})
          second-call (call :post (str "/payment-instructions/" (get b "id") "/submission")
                            {:body body :idempotency-key k})]
      (is (= 200 (:status first-call)))
      (is (= "pending-approval" (get-in first-call [:json "status"])))

      (is (= 409 (:status second-call))
          "not a silent 200 replaying the first instruction's response")
      (is (= "https://clofin.dev/problems/conflict" (get-in second-call [:json "type"])))
      (is (nil? (get-in second-call [:headers "idempotent-replayed"]))
          "nothing was replayed, because nothing matched")

      (testing "and the second instruction is untouched, which is the point"
        (is (= "draft" (status-of f b))))

      (testing "the caller is told plainly enough to act on"
        (is (str/includes? (get-in second-call [:json "detail"]) "Idempotency-Key"))))))

(deftest the-same-request-under-one-key-still-replays-after-the-o-3-fix
  (testing "narrowing what counts as the same request must not break what does —
            same method, same path, same body is still one request"
    (let [f (setup)
          pi (new-instruction! f)
          k (key!)
          first-call (submit! f pi :idempotency-key k)
          replay (submit! f pi :idempotency-key k)]
      (is (= 200 (:status first-call)))
      (is (= (:body first-call) (:body replay)))
      (is (= "true" (get-in replay [:headers "idempotent-replayed"]))))))

(deftest a-key-cannot-replay-across-two-different-operations-on-one-instruction
  (testing "submission and cancellation of the same instruction carry identical
            bodies too — the method and path are what tell them apart"
    (let [f (setup)
          pi (new-instruction! f)
          k (key!)
          body (action-body f)
          submitted (call :post (str "/payment-instructions/" (get pi "id") "/submission")
                          {:body body :idempotency-key k})
          cancelled (call :post (str "/payment-instructions/" (get pi "id") "/cancellation")
                          {:body body :idempotency-key k})]
      (is (= 200 (:status submitted)))
      (is (= 409 (:status cancelled))
          "a cancellation is not a replay of a submission")
      (is (= "pending-approval" (status-of f pi))))))

(deftest a-key-is-scoped-to-one-organisation
  (testing "two tenants choosing the same key must not collide"
    (let [a (setup)
          b (setup)
          k "shared-key"
          first-call (call :post "/payment-instructions"
                           {:body (instruction-body a) :idempotency-key k
                            :actor (:maker a)})
          second-call (call :post "/payment-instructions"
                            {:body (instruction-body b) :idempotency-key k
                             :actor (:maker b)})]
      (is (= 201 (:status first-call)))
      (is (= 201 (:status second-call)))
      (is (not= (get-in first-call [:json "id"]) (get-in second-call [:json "id"])))
      (is (= 2 (instruction-count))))))

;; ---------------------------------------------------------------------------
;; AC-9 — the one that matters most
;; ---------------------------------------------------------------------------

(deftest ac-9-two-concurrent-requests-with-one-key-produce-exactly-one-effect
  (testing "a real race: two threads, a latch, one key. Called sequentially this
            would prove nothing — the second call would simply find a committed
            row. The point is that both are in flight at once, and the primary
            key on (organisation_id, key) is what decides."
    (let [f (setup)
          ;; One handler, built before the threads start, so both callers go
          ;; through the same routes and the same pool — as two HTTP requests
          ;; arriving at one process do.
          h (handler)
          k (key!)
          ;; TASK-018 AC-18-10: the same race, carrying a client reference. The
          ;; key arbitrates first, so the reference changes nothing about the
          ;; outcome — and exactly one row ends up carrying it.
          body (instruction-body f "clientReference" "agent-ref-ac-9")
          start (CountDownLatch. 1)
          done (CountDownLatch. 2)
          responses (atom [])
          run (fn []
                (future
                  (.await start)
                  (let [response (try
                                   (call h :post "/payment-instructions"
                                         {:body body :idempotency-key k})
                                   (catch Throwable t {:status :threw :error t}))]
                    (swap! responses conj response))
                  (.countDown done)))]
      (run) (run)
      (.countDown start)
      (is (.await done 60 TimeUnit/SECONDS) "both threads must finish")

      (let [[a b] @responses]
        (testing "both callers receive the same response"
          (is (= 201 (:status a) (:status b))
              (str "statuses were " (pr-str (mapv :status @responses))))
          (is (= (:body a) (:body b))
              "byte-identical — the loser replays what the winner stored"))

        (testing "exactly one effect occurred"
          (is (= 1 (instruction-count))
              "two payment instructions from one key is the failure C-06 exists to prevent")
          (is (= 1 (key-count)))
          (is (= 1 (:count (db/query-one tdb/*pool*
                                         ["select count(*) as count from payment_instruction
                                            where client_reference = 'agent-ref-ac-9'"])))
              "and exactly one row carries the reference (AC-18-10)"))

        (testing "and exactly one of them did the work"
          (is (= 1 (count (filter #(= "true" (get-in % [:headers "idempotent-replayed"]))
                                  @responses)))
              "one fresh execution and one replay"))))))

(deftest ac-9-concurrent-submissions-of-one-instruction-under-different-keys
  (testing "different keys means idempotency does not apply, so the lifecycle
            has to hold the line on its own — `for update` is what does it"
    (let [f (setup)
          pi (new-instruction! f)
          h (handler)
          start (CountDownLatch. 1)
          done (CountDownLatch. 2)
          responses (atom [])
          run (fn []
                (future
                  (.await start)
                  (swap! responses conj
                         (call h :post (str "/payment-instructions/" (get pi "id") "/submission")
                               {:body (action-body f) :idempotency-key (key!)}))
                  (.countDown done)))]
      (run) (run)
      (.countDown start)
      (is (.await done 60 TimeUnit/SECONDS))
      (is (= [200 409] (sort (map :status @responses)))
          (str "exactly one submission, got " (pr-str (map :status @responses))))
      (is (= "pending-approval" (status-of f pi))))))

;; ---------------------------------------------------------------------------
;; AC-11 — reversal
;; ---------------------------------------------------------------------------

(deftest ac-11-a-settled-instruction-is-reversed-by-a-new-one
  (let [f (setup)
        original (settle! f (new-instruction! f))
        reversal (created! "/payment-instructions"
                           (instruction-body f "reversesId" (get original "id")))
        original-now (:json (call :get (str "/payment-instructions/" (get original "id"))
                                  {:query (str "organisationId=" (get-in f [:org "id"]))}))]
    (testing "the reversal is a new instruction pointing back at the original"
      (is (not= (get original "id") (get reversal "id")))
      (is (= (get original "id") (get reversal "reversesId")))
      (is (= "draft" (get reversal "status"))))

    (testing "the original is unchanged"
      (is (= "settled" (get original-now "status")))
      (is (not (contains? original-now "reversesId")))
      (is (= (get original "amount") (get original-now "amount")))
      (is (= (get original "createdAt") (get original-now "createdAt"))))))

(deftest ac-11-only-a-settled-instruction-can-be-reversed
  (let [f (setup)
        draft (new-instruction! f)
        {:keys [status json]}
        (call :post "/payment-instructions"
              {:idempotency-key (key!)
               :body (instruction-body f "reversesId" (get draft "id"))})]
    (is (= 409 status))
    (is (= "draft" (get-in json ["errors" "instruction-status"])))
    (is (= "reverse" (get-in json ["errors" "attempted"])))))

(defn- return!
  "Walk an instruction to `returned`, one lifecycle event at a time.

  The same walk `settle!` makes, taking the other terminal arrow out of
  `released`. It still goes through `transition!`, so the fixture cannot reach a
  state the state machine would not have permitted."
  [f pi]
  (doseq [event [:submit :approve :release :return]]
    (payments/transition! tdb/*pool*
                          (java.util.UUID/fromString (get-in f [:org "id"]))
                          (java.util.UUID/fromString (get pi "id"))
                          event
                          {:actor {:id (:maker f)}}))
  pi)

(defn- audit-rows
  [subject-id]
  (db/query tdb/*pool*
            ["select action, before_digest, after_digest from audit_event
               where subject_id = ? order by occurred_at, id"
             (java.util.UUID/fromString subject-id)]))

;; ---------------------------------------------------------------------------
;; TASK-010 AC-1 / AC-2 — linked-retry provenance (ADR-0019, ADR-0024)
;; ---------------------------------------------------------------------------

(deftest ac-1-a-retry-names-the-returned-instruction-it-replaces
  (let [f        (setup)
        original (return! f (new-instruction! f))
        retry    (created! "/payment-instructions"
                           (instruction-body f "retriesId" (get original "id")))
        original-now (:json (call :get (str "/payment-instructions/" (get original "id"))))]
    (testing "the retry is a NEW instruction pointing back at the original"
      (is (not= (get original "id") (get retry "id")))
      (is (= (get original "id") (get retry "retriesId")))
      (is (= "draft" (get retry "status"))
          "raised, submitted and approved on its own merits — ADR-0019"))

    (testing "the original is untouched, and terminal still"
      (is (= "returned" (get original-now "status")))
      (is (empty? (get original-now "permittedTransitions"))))

    (testing "AC-1 — the linkage is visible from BOTH sides"
      (is (= [(get retry "id")] (get original-now "retriedByIds"))
          "the original names its retry, derived from the retry rather than
           stored twice")
      (is (not (contains? retry "retriedByIds"))
          "and a retry nobody has retried carries no empty array"))

    (testing "AC-1 — and it is carried on the retry's own audit event"
      (let [rows (audit-rows (get retry "id"))]
        (is (= ["payment.created"] (mapv :action rows))
            "exactly one event for the creation (AC-8)")
        (is (nil? (:before-digest (first rows))))
        (is (some? (:after-digest (first rows)))))
      (testing "the digest actually covers the link: the same instruction
                without it digests differently"
        (let [unlinked (created! "/payment-instructions" (instruction-body f))
              digest-of (fn [id] (:after-digest (first (audit-rows id))))]
          (is (not= (digest-of (get retry "id")) (digest-of (get unlinked "id")))
              "a projection that omitted `retries-id` would give one digest to a
               retry and to an unlinked payment with the same values"))))

    (testing "AC-1 — the reference is immutable: a PATCH naming it is refused"
      (let [{:keys [status json]}
            (call :patch (str "/payment-instructions/" (get retry "id"))
                  {:idempotency-key (key!)
                   :body {"organisationId" (get-in f [:org "id"])
                          "retriesId" (get original "id")}})]
        (is (= 422 status))
        (is (= "cannot be amended" (get-in json ["errors" "retriesId"])))))

    (testing "AC-1 — and the database refuses the change to any writer, because
              provenance an operator can rewrite is provenance an investigation
              cannot rely on (L-6)"
      (let [t (try (db/execute! tdb/*pool*
                                ["update payment_instruction set retries_id = null where id = ?"
                                 (java.util.UUID/fromString (get retry "id"))])
                   nil
                   (catch Exception e e))]
        (is (some? t) "a raw UPDATE clearing the link must be refused")
        (is (str/includes? (str (ex-message t)) "never changes"))))))

(deftest ac-2-a-retry-target-that-is-not-returned-is-refused-by-name
  (let [f (setup)]
    (doseq [[label target-status build] [["a draft" "draft" (fn [pi] pi)]
                                         ["a settled instruction" "settled" #(settle! f %)]]]
      (let [target (build (new-instruction! f))
            before-instructions (instruction-count)
            before-audit (:count (db/query-one tdb/*pool*
                                               ["select count(*) as count from audit_event"]))
            {:keys [status json]}
            (call :post "/payment-instructions"
                  {:idempotency-key (key!)
                   :body (instruction-body f "retriesId" (get target "id"))})]
        (is (= 409 status) label)
        (is (= target-status (get-in json ["errors" "instruction-status"])) label)
        (is (= "retry" (get-in json ["errors" "attempted"])) label)
        (is (= ["returned"] (get-in json ["errors" "retryable-in"])) label)
        (is (str/includes? (str (get json "detail")) "only a returned instruction")
            "the refusal names the correction rather than only the rule")
        (testing "AC-8 — a refused creation rolls back, so it leaves no row and
                  no event"
          (is (= before-instructions (instruction-count)) label)
          (is (= before-audit
                 (:count (db/query-one tdb/*pool*
                                       ["select count(*) as count from audit_event"])))
              label))))))

(deftest ac-2-a-retry-target-in-another-organisation-reveals-nothing-about-it
  (testing "C-08 — saying `another organisation's` would confirm that a guessed
            UUID names a real payment somewhere else"
    (let [ours   (setup)
          theirs (setup)
          foreign (return! theirs (new-instruction! theirs))
          _ (reset! current-actor (:maker ours))
          {:keys [status json]}
          (call :post "/payment-instructions"
                {:idempotency-key (key!)
                 :body (instruction-body ours "retriesId" (get foreign "id"))})]
      (is (= 422 status)
          "not 409 — a foreign instruction is reported as not existing, so the
           answer is the same one a made-up id gets")
      (is (str/includes? (str (get json "detail")) "does not exist in this organisation"))
      (is (nil? (get-in json ["errors" "instruction-status"]))
          "and nothing about the foreign payment's state leaks")
      (is (= 422 (:status (call :post "/payment-instructions"
                                {:idempotency-key (key!)
                                 :body (instruction-body ours "retriesId"
                                                         (str (random-uuid)))})))
          "an id that names nothing at all gets the identical answer"))))

(deftest ac-2-an-instruction-cannot-be-both-a-reversal-and-a-retry
  (testing "opposite statements about opposite terminal outcomes — a record
            claiming both means nothing"
    (let [f (setup)
          settled  (settle! f (new-instruction! f))
          returned (return! f (new-instruction! f))
          {:keys [status json]}
          (call :post "/payment-instructions"
                {:idempotency-key (key!)
                 :body (instruction-body f
                                         "reversesId" (get settled "id")
                                         "retriesId" (get returned "id"))})]
      (is (= 422 status))
      (is (= "cannot be set on a reversal" (get-in json ["errors" "retriesId"])))
      (is (= "cannot be set on a retry" (get-in json ["errors" "reversesId"]))
          "both members are named, because a caller that sent both has a bug and
           needs to be told which half to remove"))))

(deftest ac-2-a-malformed-retries-id-is-reported-with-every-other-failed-field
  (testing "PR-003 — the parse failure joins the pass rather than aborting it"
    (let [f (setup)
          {:keys [status json]}
          (call :post "/payment-instructions"
                {:idempotency-key (key!)
                 :body (instruction-body f "retriesId" "not-a-uuid"
                                         "purposeCode" "XXXX")})]
      (is (= 422 status))
      (is (= "must be a UUID" (get-in json ["errors" "retriesId"])))
      (is (= "unknown purpose code: XXXX" (get-in json ["errors" "purposeCode"]))))))

(deftest ac-1-a-retry-of-a-retry-is-permitted-and-the-chain-is-not-collapsed
  (testing "ADR-0019's text says nothing about chains, and refusing one would
            leave an operator raising an unlinked instruction — losing exactly
            the provenance the link exists to create (ADR-0024 reading 1)"
    (let [f        (setup)
          original (return! f (new-instruction! f))
          first-retry (return! f (created! "/payment-instructions"
                                           (instruction-body f "retriesId"
                                                             (get original "id"))))
          second-retry (created! "/payment-instructions"
                                 (instruction-body f "retriesId" (get first-retry "id")))]
      (is (= (get first-retry "id") (get second-retry "retriesId"))
          "a retry names the payment it DIRECTLY replaces, not the head of the
           chain — walking a chain is a read, and storing the head would be a
           second copy of the relation")
      (let [original-now (:json (call :get (str "/payment-instructions/"
                                                (get original "id"))))]
        (is (= [(get first-retry "id")] (get original-now "retriedByIds"))
            "and the original still names only what directly retries it")))))

(deftest ac-1-an-original-may-be-retried-more-than-once-over-its-life
  (testing "the link carries no uniqueness rule: every candidate predicate would
            encode a second copy of the payment lifecycle in an index, which is
            the shape F-007 removed from this table (ADR-0024 reading 2)"
    (let [f        (setup)
          original (return! f (new-instruction! f))
          first-retry (created! "/payment-instructions"
                                (instruction-body f "retriesId" (get original "id")))
          _ (is (= 200 (:status (call :post (str "/payment-instructions/"
                                                 (get first-retry "id") "/cancellation")
                                      {:idempotency-key (key!) :body (action-body f)}))))
          second-retry (created! "/payment-instructions"
                                 (instruction-body f "retriesId" (get original "id")))
          original-now (:json (call :get (str "/payment-instructions/" (get original "id"))))]
      (is (= #{(get first-retry "id") (get second-retry "id")}
             (set (get original-now "retriedByIds")))
          "an operator whose first retry was cancelled is not stranded")
      (is (= 2 (count (get original-now "retriedByIds")))
          "and both are named — a scalar column could not have said so"))))

(deftest ac-1-an-ordinary-instruction-carries-neither-member
  (let [f (setup)
        pi (new-instruction! f)]
    (is (not (contains? pi "retriesId")))
    (is (not (contains? pi "retriedByIds")))
    (is (not (contains? pi "reversesId"))
        "an absent link is absent rather than null — the same courtesy
         `reversesId` has always had")))

;; ---------------------------------------------------------------------------
;; AC-12 is `clofin.contract-test`, which passes unmodified.
;; ---------------------------------------------------------------------------

(deftest the-stored-response-is-what-a-replay-serves
  (testing "not a re-execution that happens to agree — the row carries it"
    (let [f (setup)
          k (key!)
          body (instruction-body f)
          first-call (call :post "/payment-instructions" {:body body :idempotency-key k})
          stored (db/query-one tdb/*pool*
                               ["select response_status, response_body, request_digest
                                   from idempotency_key where key = ?" k])]
      (is (= 201 (int (:response-status stored))))
      (is (= (:body first-call) (:response-body stored)))
      (is (re-matches #"[0-9a-f]{64}" (:request-digest stored))))))

(deftest a-replayed-response-is-served-as-json
  (testing "the body is a stored string, so the content type must be set explicitly"
    (let [f (setup)
          k (key!)
          body (instruction-body f)]
      (call :post "/payment-instructions" {:body body :idempotency-key k})
      (let [replay (call :post "/payment-instructions" {:body body :idempotency-key k})]
        (is (= "application/json" (get-in replay [:headers "content-type"])))
        (is (map? (:json replay)) "and it still parses as JSON")))))

;; ===========================================================================
;; TASK-018 — `clientReference`, `creditorCountry` and the idempotency-key
;; lookup (ADR-0028 D6). Test names are the executable identifiers ADR-0028's
;; Verification table commits to.
;; ===========================================================================

(defn- lookup
  ([k] (lookup k {}))
  ([k opts] (call :get (str "/payment-instructions/by-idempotency-key/" k) opts)))

(defn- org-uuid [f] (java.util.UUID/fromString (get-in f [:org "id"])))

(defn- await-latch [^CountDownLatch latch]
  (is (.await latch 30 TimeUnit/SECONDS) "a latch the test depends on was never reached"))

(defn- backend-state
  "What PostgreSQL says the backend `pid` is doing right now."
  [pid]
  (:state (db/query-one tdb/*pool* ["select state from pg_stat_activity where pid = ?" (int pid)])))

(defn- await-lock-wait!
  "Block until a backend in this database is waiting on a lock **while running
  a statement matching `statement`** (an `ilike` pattern over
  `pg_stat_activity.query`), and return how many are. Returns 0 after ten
  seconds, which the caller asserts against.

  The statement is named, not just the wait: the adversarial review found that
  \"some backend is waiting on some lock\" was satisfied by a loser parked on
  the debtor account's row lock while the test said it was parked on the
  reference index. Which lock a loser waits on is the interleaving a race test
  claims, so the test now says which one and checks it."
  [statement]
  (loop [n 0]
    (let [waiting (:count (db/query-one tdb/*pool*
                                        ["select count(*) as count from pg_stat_activity
                                           where datname = current_database()
                                             and wait_event_type = 'Lock'
                                             and query ilike ?"
                                         statement]))]
      (if (or (pos? waiting) (> n 500))
        waiting
        (do (Thread/sleep 20) (recur (inc n)))))))

(defn- hold-a-creation-open!
  "Thread A of the race tests. Runs **the real creation effect**
  (`clofin.api.payments/creation-effect`) under
  `clofin.idempotency.repository/execute-once!` for `key` and `body`, and parks
  inside the effect after the instruction row is inserted and before the effect
  returns — so the key row is claimed, the instruction row is written, and
  neither is committed. `:release` lets it finish.

  The digest is the one the handler computes for the same request, so a later
  HTTP creation under the same key and body is the same request."
  [f key body]
  (let [inserted (CountDownLatch. 1)
        release  (CountDownLatch. 1)
        pid      (promise)
        request  {:request-method :post :uri "/payment-instructions"
                  :headers {"idempotency-key" key} :json-body body}
        effect   (payments-api/creation-effect request {:id @current-actor} (org-uuid f) body)
        outcome  (future
                   (idem-store/execute-once!
                    tdb/*pool*
                    {:organisation-id (org-uuid f)
                     :key             key
                     :digest          (#'payments-api/request-digest request)
                     :operation-id    "createPaymentInstruction"}
                    (fn [tx]
                      (let [result (effect tx)]
                        (deliver pid (:pid (db/query-one tx ["select pg_backend_pid() as pid"])))
                        (.countDown inserted)
                        (.await release 60 TimeUnit/SECONDS)
                        result))))]
    {:outcome outcome :inserted inserted :release release :pid pid}))

;; ---------------------------------------------------------------------------
;; AC-18-1 / AC-18-2 / AC-18-6 — the lookup
;; ---------------------------------------------------------------------------

(deftest ac-18-1-the-lookup-returns-the-stored-response-and-current-status
  (let [f (setup)
        k (key!)
        creation (call :post "/payment-instructions" {:body (instruction-body f) :idempotency-key k})
        pi (:json creation)
        _ (is (= 200 (:status (submit! f pi))))
        {:keys [status json]} (lookup k)]
    (is (= 201 (:status creation)))
    (is (= 200 status) (pr-str json))
    (is (= k (get json "idempotencyKey")))
    (is (= (get pi "id") (get json "instructionId")))
    (is (= 201 (get json "originalStatus")))
    (is (= (json/read-str (:body creation)) (get json "originalBody"))
        "the stored body, in content exactly what a replay serves")
    (is (= "draft" (get-in json ["originalBody" "status"]))
        "as it was stored at creation — not refreshed")
    (is (= "pending-approval" (get json "currentStatus"))
        "and the instruction's status now, read in the same request")
    (is (some? (get json "boundAt")))
    (testing "the negative control: the same key from another organisation's actor
              is not this organisation's binding — the scope is the key table's
              primary key, so the answer is 404, not the other tenant's payment"
      (let [other (setup)
            {:keys [status json]} (lookup k {:actor (:maker other)})]
        (is (= 404 status))
        (is (= "no-binding" (get-in json ["errors" "reason"])))))))

(deftest ac-18-1-a-key-with-reserved-characters-is-looked-up-percent-encoded
  (testing "a key containing a space or a plus — both of which the real transport
            carries in a path — is sent percent-encoded and compared decoded.
            (An encoded `/` or `%` is refused by the transport before any handler
            runs; `clofin.system-test` asserts that against the real server, and
            the contract says so.)"
    (let [f (setup)
          k "client key 2026+01"
          creation (call :post "/payment-instructions" {:body (instruction-body f) :idempotency-key k})
          {:keys [status json]} (lookup "client%20key%202026+01")]
      (is (= 201 (:status creation)))
      (is (= 200 status) (pr-str json))
      (is (= k (get json "idempotencyKey")))
      (is (= (get-in creation [:json "id"]) (get json "instructionId")))))
  (testing "and a key the header could never have bound is a 400, not a 404"
    (let [f (setup)]
      (is (= 400 (:status (lookup (apply str (repeat 256 "k"))))))
      (is (= 400 (:status (lookup "a%00b"))) "a control character")
      (is (= 400 (:status (lookup "a%zzb"))) "malformed percent-encoding")
      (is (some? f))))
  (testing "and a non-ASCII key is a 400 even when the header bound it — the
            transport read the header's octets as ISO-8859-1, so `café-1` was
            bound as `cafÃ©-1`, and a UTF-8 decoding of the path would have
            answered a false 404 (review finding 1)"
    (let [f (setup)
          _ (db/execute! tdb/*pool* ["insert into idempotency_key
                                        (organisation_id, key, request_digest, response_status,
                                         response_body, operation_id)
                                      values (?, ?, ?, ?, ?, ?)"
                                     (org-uuid f) "caf\u00c3\u00a9-1" "d" 201 "{}"
                                     "createPaymentInstruction"])
          {:keys [status json]} (lookup "caf%C3%A9-1")]
      (is (= 400 status) (pr-str json))
      (is (str/includes? (get json "detail") "printable ASCII")))))

(deftest ac-18-2-the-lookup-answers-404-only-when-no-key-is-bound
  (let [f (setup)]
    (testing "an unused key: 404 no-binding, carrying the contract sentence"
      (let [{:keys [status json]} (lookup (key!))]
        (is (= 404 status))
        (is (= "no-binding" (get-in json ["errors" "reason"])))
        (is (= payments-api/no-binding-detail (get json "detail")))
        (is (str/includes? (get json "detail") "a creation still in flight may still commit"))
        (is (str/includes? (get json "detail") "under the same key"))))

    (testing "a key bound by a submission: 409, naming the operation"
      (let [pi (new-instruction! f)
            k (key!)
            _ (is (= 200 (:status (submit! f pi :idempotency-key k))))
            {:keys [status json]} (lookup k)]
        (is (= 409 status))
        (is (= "key-bound-to-another-operation" (get-in json ["errors" "reason"])))
        (is (= "submitPaymentInstruction" (get-in json ["errors" "operationId"])))
        (is (not (contains? json "instructionId"))
            "a submission's stored body is a PaymentInstruction too, and must never be
             described as a creation")))

    (testing "a key row with no operation — the shape every row written before 0014
              has — is 409 with operationId null, never 404: a 404 would tell the
              client to resubmit under a key that is taken"
      (db/execute! tdb/*pool* ["insert into idempotency_key
                                  (organisation_id, key, request_digest, response_status, response_body)
                                values (?, ?, ?, ?, ?)"
                               (org-uuid f) "pre-0014-key" "d" 201 "{\"id\":\"x\"}"])
      (let [{:keys [status json]} (lookup "pre-0014-key")]
        (is (= 409 status))
        (is (= "key-bound-to-another-operation" (get-in json ["errors" "reason"])))
        (is (contains? (get json "errors") "operationId"))
        (is (nil? (get-in json ["errors" "operationId"])))))))

(deftest ac-18-6-the-lookup-requires-payment-read-and-is-scoped-to-the-callers-organisation
  (let [f (setup)
        k (key!)
        _ (call :post "/payment-instructions" {:body (instruction-body f) :idempotency-key k})
        other (setup)]
    (reset! current-actor (:maker f))
    (is (= 200 (:status (lookup k))) "the positive control: the maker may read it")
    (is (= 401 (:status (lookup k {:actor false}))) "no actor")
    (is (= 403 (:status (lookup k {:actor (seed-actor! (:org f) [])})))
        "an actor holding no role, so no payment/read")
    (let [{:keys [status]} (lookup k {:query (str "organisationId=" (get-in other [:org "id"]))})]
      (is (= 403 status) "organisationId naming another organisation"))
    (is (= 200 (:status (lookup k {:query (str "organisationId=" (get-in f [:org "id"]))})))
        "and naming its own is the same as naming none")))

;; ---------------------------------------------------------------------------
;; AC-18-3 — the lookup raced against an in-flight creation
;; ---------------------------------------------------------------------------

(deftest ac-18-3-a-404-during-an-in-flight-creation-becomes-200-on-the-same-key-with-one-instruction
  (let [f (setup)
        h (handler)
        k (key!)
        body (instruction-body f)
        {:keys [outcome inserted release pid]} (hold-a-creation-open! f k body)]
    (await-latch inserted)

    (testing "while A's transaction is open — key row claimed, instruction row
              written, nothing committed — the lookup on a second connection
              answers 404"
      (is (= "idle in transaction" (backend-state @pid))
          "non-vacuity: A's transaction is open when the lookup runs")
      (is (= 1 (.getCount ^CountDownLatch release)) "and A has not been released")
      (let [{:keys [status json]} (lookup k)]
        (is (= 404 status) (pr-str json))
        (is (= "no-binding" (get-in json ["errors" "reason"]))))
      (is (= "idle in transaction" (backend-state @pid))
          "and it was still open after the lookup returned"))

    (testing "the client keeps its key and resubmits under it while A is in
              flight: the resubmission waits on A's key row rather than creating"
      (let [resubmitted (future (call h :post "/payment-instructions"
                                      {:body body :idempotency-key k}))]
        (is (pos? (await-lock-wait! "%insert into idempotency_key%"))
            "the resubmission is parked on A's key row — its claiming insert waits")
        (is (not (realized? resubmitted)))

        (.countDown ^CountDownLatch release)
        (let [a @outcome
              b (deref resubmitted 30000 :timed-out)]
          (is (= 201 (:status a)))
          (is (false? (:replayed? a)))
          (testing "and the late commit turned it into a replay, not a second instruction"
            (is (= 201 (:status b)))
            (is (= "true" (get-in b [:headers "idempotent-replayed"])))
            (is (= (:body a) (:body b)))))))

    (testing "after A commits the same lookup is 200, naming the one instruction"
      (let [{:keys [status json]} (lookup k)]
        (is (= 200 status))
        (is (= (get (:data @outcome) "id") (get json "instructionId")))
        (is (= 201 (get json "originalStatus")))
        (is (= "draft" (get json "currentStatus")))))

    (is (= 1 (instruction-count)) "exactly one payment_instruction")
    (is (= 1 (key-count)) "and exactly one idempotency_key")

    (testing "and a resubmission after the commit is a replay too"
      (let [again (call :post "/payment-instructions" {:body body :idempotency-key k})]
        (is (= 201 (:status again)))
        (is (= "true" (get-in again [:headers "idempotent-replayed"])))
        (is (= 1 (instruction-count)))))))

;; ---------------------------------------------------------------------------
;; AC-18-4 / AC-18-5 — the three reference rules
;; ---------------------------------------------------------------------------

(deftest ac-18-2-rule-1-the-same-reference-under-the-same-key-is-the-ordinary-replay
  (let [f (setup)
        k (key!)
        body (instruction-body f "clientReference" "agent-ref-0001" "creditorCountry" "SG")
        first-call (call :post "/payment-instructions" {:body body :idempotency-key k})
        replay (call :post "/payment-instructions" {:body body :idempotency-key k})]
    (is (= 201 (:status first-call) (:status replay)))
    (is (= "agent-ref-0001" (get-in first-call [:json "clientReference"])))
    (is (= "SG" (get-in first-call [:json "creditorCountry"])))
    (is (= "true" (get-in replay [:headers "idempotent-replayed"])))
    (is (= (:body first-call) (:body replay)))
    (is (= 1 (instruction-count)))
    (testing "and the reference is rendered on the resource when read back"
      (is (= "agent-ref-0001"
             (get (:json (call :get (get-in first-call [:headers "location"]))) "clientReference"))))))

(deftest ac-18-4-a-reused-client-reference-with-different-content-is-409-and-creates-nothing
  (let [f (setup)
        k (key!)
        body (instruction-body f "clientReference" "agent-ref-0001")
        original (call :post "/payment-instructions" {:body body :idempotency-key k})
        different (assoc body "amount" {"currency" "SGD" "minorUnits" 999})]
    (is (= 201 (:status original)))

    (testing "rule 2 under a new key"
      (let [k2 (key!)
            {:keys [status json]} (call :post "/payment-instructions"
                                        {:body different :idempotency-key k2})]
        (is (= 409 status))
        (is (= "https://clofin.dev/problems/conflict" (get json "type")))
        (is (= {"reason" "client-reference-conflict"
                "instructionId" (get-in original [:json "id"])}
               (get json "errors")))
        (is (= 1 (instruction-count)) "nothing persisted")
        (is (= 1 (key-count)) "and the new key was not consumed")
        (testing "— so a corrected request under that same new key succeeds"
          (let [corrected (call :post "/payment-instructions"
                                {:body (assoc different "clientReference" "agent-ref-0002")
                                 :idempotency-key k2})]
            (is (= 201 (:status corrected)))
            (is (nil? (get-in corrected [:headers "idempotent-replayed"])))))))

    (testing "rule 2 under the original key: the key's own 409 wins, because the
              key row is claimed before anything else runs — the published order"
      (let [{:keys [status json]} (call :post "/payment-instructions"
                                        {:body different :idempotency-key k})]
        (is (= 409 status))
        (is (= {"header" "Idempotency-Key"} (get json "errors"))
            "the key conflict, not a reference conflict")
        (is (str/includes? (get json "detail") "Idempotency-Key"))))

    (testing "an existing instruction amended after creation answers rule 2 to its
              own original content: the reference names content the client no
              longer holds"
      (is (= 200 (:status (call :patch (str "/payment-instructions/" (get-in original [:json "id"]))
                                {:idempotency-key (key!)
                                 :body {"organisationId" (get-in f [:org "id"])
                                        "purposeCode" "TRAD"}}))))
      (let [{:keys [status json]} (call :post "/payment-instructions"
                                        {:body body :idempotency-key (key!)})]
        (is (= 409 status))
        (is (= "client-reference-conflict" (get-in json ["errors" "reason"])))))
    (is (= 2 (instruction-count)) "the original and the corrected request, nothing else")))

(deftest ac-18-4-a-race-loser-with-different-content-answers-client-reference-conflict
  (testing "standing lesson L-18: the loser of the race on the unique index re-enters
            the serial path's decision — so different content is a conflict even
            when the reference was arbitrated by the index rather than the pre-check.
            B draws on a **second** debtor account, so it contends with A on
            nothing but the reference: its pre-check misses A's uncommitted row,
            its own account lock is free, and its insert parks on A's
            uncommitted entry in payment_instruction_client_reference_key"
    (let [f (setup)
          h (handler)
          other-account (tdb/insert-account! tdb/*pool*
                                             {:id (random-uuid) :organisation-id (org-uuid f)
                                              :code (str "1110-CLIENT-FUNDS-" (rand-int 1000000))})
          body (instruction-body f "clientReference" "agent-ref-race-2")
          {:keys [outcome inserted release]} (hold-a-creation-open! f (key!) body)]
      (await-latch inserted)
      (let [loser (future (call h :post "/payment-instructions"
                                {:body (assoc body "debtorAccountId" (str other-account))
                                 :idempotency-key (key!)}))]
        (is (pos? (await-lock-wait! "%insert into payment_instruction%"))
            "the loser passed the pre-check and its insert is parked on the index")
        (is (not (realized? loser)))
        (.countDown ^CountDownLatch release)
        (let [winner @outcome
              {:keys [status json]} (deref loser 30000 :timed-out)]
          (is (= 201 (:status winner)))
          (is (= 409 status) (pr-str json))
          (is (= {"reason" "client-reference-conflict"
                  "instructionId" (get (:data winner) "id")}
                 (get json "errors")))
          (is (= 1 (instruction-count)))
          (is (= 1 (key-count)) "the loser's key rolled back with its insert"))))))

(deftest ac-18-5-a-lost-race-with-no-winning-row-is-a-defect-not-a-refusal
  (testing "the violation says a winner committed and instructions are never
            deleted, so finding no row is a 500 with a correlation id — not a 409
            whose errors carry no reason the contract declares (review finding 6)"
    (let [f (setup)
          marker (ex-info "lost" {:clofin/error :conflict :clofin/client-reference-race "nobody"})
          t (try (#'payments-api/resolve-client-reference-race!
                  tdb/*pool* (org-uuid f) (instruction-body f "clientReference" "nobody")
                  "nobody" marker)
                 nil
                 (catch Exception t t))]
      (is (some? t))
      (is (nil? (:clofin/error (ex-data t))) "not a domain error, so the boundary renders a 500")
      (is (identical? marker (ex-cause t)) "and the original is kept as the cause, for the log"))))

(deftest ac-18-5-a-reused-client-reference-under-a-new-key-answers-409-naming-the-existing-instruction
  (testing "rule 3, sequentially"
    (let [f (setup)
          body (instruction-body f "clientReference" "agent-ref-0001")
          original (call :post "/payment-instructions" {:body body :idempotency-key (key!)})
          k2 (key!)
          {:keys [status json]} (call :post "/payment-instructions" {:body body :idempotency-key k2})]
      (is (= 201 (:status original)))
      (is (= 409 status))
      (is (= {"reason" "client-reference-exists"
              "instructionId" (get-in original [:json "id"])}
             (get json "errors")))
      (is (str/includes? (get json "detail") (get-in original [:json "id"])))
      (is (= 1 (instruction-count)))
      (is (= 1 (key-count)) "the second key was not consumed")
      (testing "a lost key is resolved by the reference: the 409 names the id to read"
        (is (= 200 (:status (call :get (str "/payment-instructions/" (get-in json ["errors" "instructionId"])))))))))

  (testing "rule 3 with two threads and two keys racing on one reference. Thread A
            holds its creation open after its insert. B's pre-check runs while A
            is uncommitted and misses A's row; identical content means the same
            debtor account, so B then waits on the account row lock A holds —
            observed below, and taken only *after* the pre-check, which is what
            proves the pre-check missed. When A commits, B's insert fails on the
            unique index against A's committed row — the arbitration path, not
            the pre-check — and B is decided after its own rollback. (The
            different-content race above is the one whose insert waits on the
            index itself.)"
    (let [f (setup)
          h (handler)
          ;; The sequential half above left its own rows; this half counts from here.
          instructions-before (instruction-count)
          keys-before (key-count)
          body (instruction-body f "clientReference" "agent-ref-race")
          {:keys [outcome inserted release]} (hold-a-creation-open! f (key!) body)]
      (await-latch inserted)
      (let [loser (future (call h :post "/payment-instructions"
                                {:body body :idempotency-key (key!)}))]
        (is (pos? (await-lock-wait! "%from ledger_account%for update%"))
            "B passed its pre-check and is parked on the debtor account A holds")
        (is (not (realized? loser)))
        (.countDown ^CountDownLatch release)
        (let [winner @outcome
              {:keys [status json]} (deref loser 30000 :timed-out)]
          (is (= [201 409] [(:status winner) status]) (pr-str json))
          (is (= {"reason" "client-reference-exists"
                  "instructionId" (get (:data winner) "id")}
                 (get json "errors")))
          (is (= 1 (- (instruction-count) instructions-before)) "exactly one instruction")
          (is (= 1 (- (key-count) keys-before)) "and one key row"))))))

;; ---------------------------------------------------------------------------
;; AC-18-7 / AC-18-8 — immutability, and the country
;; ---------------------------------------------------------------------------

(deftest ac-18-7-a-client-reference-is-immutable-through-the-api-and-behind-it
  (let [f (setup)
        pi (new-instruction! f "clientReference" "agent-ref-0001")]
    (testing "PATCH naming it is 422 naming the member"
      (let [{:keys [status json]}
            (call :patch (str "/payment-instructions/" (get pi "id"))
                  {:idempotency-key (key!)
                   :body {"organisationId" (get-in f [:org "id"]) "clientReference" "agent-ref-0002"}})]
        (is (= 422 status))
        (is (= {"clientReference" "is set when the instruction is created and cannot be amended"}
               (get json "errors")))))
    (testing "nor can one be added to an instruction created without one"
      (let [plain (new-instruction! f)
            {:keys [status]} (call :patch (str "/payment-instructions/" (get plain "id"))
                                   {:idempotency-key (key!)
                                    :body {"organisationId" (get-in f [:org "id"])
                                           "clientReference" "agent-ref-late"}})]
        (is (= 422 status))))
    (testing "behind the API the trigger refuses the raw UPDATE — asserted in
              `clofin.payments.repository-test`, application bypassed"
      (is (thrown? Exception
                   (db/execute! tdb/*pool* ["update payment_instruction set client_reference = 'x' where id = ?"
                                            (java.util.UUID/fromString (get pi "id"))]))))
    (testing "the same reference in a second organisation creates a second instruction"
      (let [other (setup)
            theirs (call :post "/payment-instructions"
                         {:body (instruction-body other "clientReference" "agent-ref-0001")
                          :idempotency-key (key!) :actor (:maker other)})]
        (is (= 201 (:status theirs)))
        (is (not= (get pi "id") (get-in theirs [:json "id"])))))))

(deftest ac-18-8-creditor-country-is-syntax-only-and-amendable
  (let [f (setup)]
    (testing "SG accepted and rendered"
      (is (= "SG" (get (new-instruction! f "creditorCountry" "SG") "creditorCountry"))))
    (testing "sg, SGP and S1 refused, each named with every other failed field"
      (doseq [bad ["sg" "SGP" "S1"]]
        (let [{:keys [status json]}
              (call :post "/payment-instructions"
                    {:idempotency-key (key!)
                     :body (instruction-body f "creditorCountry" bad
                                             "clientReference" "has space"
                                             "purposeCode" "XXXX")})]
          (is (= 422 status))
          (is (= {"clientReference" "must be 1–128 printable ASCII characters with no spaces"
                  "creditorCountry" "must be an ISO 3166-1 alpha-2 shape: two uppercase letters"
                  "purposeCode"     "unknown purpose code: XXXX"}
                 (get json "errors"))
              bad))))
    (testing "an amend changes it, and the audit after-digest changes with it"
      (let [pi (new-instruction! f "creditorCountry" "SG")
            amended (call :patch (str "/payment-instructions/" (get pi "id"))
                          {:idempotency-key (key!)
                           :body {"organisationId" (get-in f [:org "id"]) "creditorCountry" "GB"}})
            event (last (filter #(= "payment.amended" (:action %)) (audit-rows (get pi "id"))))]
        (is (= 200 (:status amended)))
        (is (= "GB" (get-in amended [:json "creditorCountry"])))
        (is (some? event))
        (is (not= (:before-digest event) (:after-digest event))
            "the projection includes the country: a change to it alone moves the digest")))
    (is (= 2 (instruction-count)))))
