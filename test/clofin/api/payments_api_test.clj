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
  (:require [clofin.db.core :as db]
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

(defn- call
  "Issue a request through the whole stack and decode the response body."
  ([method uri] (call (handler) method uri {}))
  ([method uri opts] (call (handler) method uri opts))
  ([h method uri {:keys [body query idempotency-key]}]
   ;; A `Location` header carries its query string inline, and the HTTP adapter
   ;; splits the two before a handler ever sees them. Splitting here is what
   ;; lets a test follow a Location the way a client does.
   (let [[path inline-query] (str/split uri #"\?" 2)
         query (or query inline-query)
         response (h (cond-> {:request-method method :uri path :headers {}}
                       query (assoc :query-string query)
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

(defn- setup []
  (let [org (new-organisation!)]
    {:org org :account (new-account! org)}))

(defn- instruction-body
  [{:keys [org account]} & {:as overrides}]
  (merge {"organisationId"  (get org "id")
          "debtorAccountId" (get account "id")
          "creditorName"    "Pacific Rim Logistics Pte Ltd"
          "creditorAccount" "SG-SYNTH-88012345"
          "amount"          {"currency" "SGD" "minorUnits" 125000}
          "valueDate"       (str (.plusDays today 7))
          "purposeCode"     "SUPP"
          "createdBy"       (str (random-uuid))}
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
        other (new-organisation!)
        pi (new-instruction! f)
        {:keys [status]} (call :get (str "/payment-instructions/" (get pi "id"))
                               {:query (str "organisationId=" (get other "id"))})]
    (is (= 404 status)
        "the same answer a non-existent id receives — anything else is a tenancy disclosure")))

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
  (let [f (setup)
        other (setup)
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

(deftest ac-3-a-submitted-instruction-cannot-be-amended
  (let [f (setup)
        pi (new-instruction! f)]
    (is (= 200 (:status (submit! f pi))))
    (let [{:keys [status json]}
          (call :patch (str "/payment-instructions/" (get pi "id"))
                {:idempotency-key (key!)
                 :body {"organisationId" (get-in f [:org "id"])
                        "amount" {"currency" "SGD" "minorUnits" 1}}})]
      (is (= 409 status))
      (is (= "pending-approval" (get-in json ["errors" "instruction-status"])))
      (is (= "amend" (get-in json ["errors" "attempted"]))))
    (is (= 125000 (get-in (:json (call :get (str "/payment-instructions/" (get pi "id"))
                                       {:query (str "organisationId=" (get-in f [:org "id"]))}))
                          ["amount" "minorUnits"]))
        "and nothing changed")))

(deftest amending-something-that-is-not-amendable-is-rejected-not-ignored
  (let [f (setup)
        pi (new-instruction! f)]
    (doseq [member ["status" "createdBy" "id" "reversesId"]]
      (let [{:keys [status json]}
            (call :patch (str "/payment-instructions/" (get pi "id"))
                  {:idempotency-key (key!)
                   :body {"organisationId" (get-in f [:org "id"]) member "anything"}})]
        (is (= 422 status) (str member " must be refused"))
        (is (= "cannot be amended" (get-in json ["errors" member]))
            "silently dropping it would leave the caller believing it changed something")))))

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
                          event))
  pi)

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
    (testing "PR-040 — every mutating operation requires an Idempotency-Key"
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
                           {:body (instruction-body a) :idempotency-key k})
          second-call (call :post "/payment-instructions"
                            {:body (instruction-body b) :idempotency-key k})]
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
          body (instruction-body f)
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
          (is (= 1 (key-count))))

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
