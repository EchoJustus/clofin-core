(ns clofin.api.approvals-api-test
  "Approval and audit, end to end, without a socket.

  These call the fully-wrapped handler — router, middleware, error translation,
  JSON codec — and assert on the response, which is the whole stack a caller
  meets minus Jetty (ADR-0010).

  `clofin.authz.approval-test` proves the same controls *without* HTTP, and
  that is the important half: if segregation of duties only held for callers
  who came through this file, it would not be a control. These tests assert the
  other thing — that the boundary reports each refusal as the right status code
  with the reason a client can branch on, and that the approval, the state
  change and the audit event commit as one.

  Acceptance criteria from docs/briefs/003-TASK-authorisation-and-audit-trail.md
  are named in the tests that cover them."
  (:require [clofin.db.core :as db]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time LocalDate ZoneOffset]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private today (LocalDate/now ZoneOffset/UTC))

;; ---------------------------------------------------------------------------
;; Calling the API
;; ---------------------------------------------------------------------------

(defn- handler [] (system/handler {:config {:environment :test} :pool tdb/*pool*}))

(defn- call
  ([method uri] (call method uri {}))
  ([method uri {:keys [body query idempotency-key actor correlation-id]}]
   (let [[path inline-query] (str/split uri #"\?" 2)
         query (or query inline-query)
         response ((handler)
                   (cond-> {:request-method method :uri path :headers {}}
                     query (assoc :query-string query)
                     actor (assoc-in [:headers "x-actor-id"] (str actor))
                     correlation-id (assoc-in [:headers "x-correlation-id"] correlation-id)
                     idempotency-key (assoc-in [:headers "idempotency-key"] idempotency-key)
                     body (-> (assoc-in [:headers "content-type"] "application/json")
                              (assoc :body (ByteArrayInputStream.
                                            (.getBytes (json/write-str body)
                                                       StandardCharsets/UTF_8))))))]
     (assoc response :json (when-not (str/blank? (:body response))
                             (json/read-str (:body response)))))))

(defn- key! [] (str (random-uuid)))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- setup
  "An organisation with a maker, two approvers, an auditor and a band table.

  Every right is granted explicitly, role by role. There is no superuser to
  reach for, and this fixture doubles as a statement of what each role can do
  (C-08) — which is the second reason the rule exists, after the obvious one."
  [& {:keys [bands] :or {bands [[0 1]]}}]
  (let [org (random-uuid)
        _ (tdb/insert-organisation! tdb/*pool* {:id org :short-name (str "meridian-" (rand-int 100000000))})
        account (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                 :code (str "1100-CLIENT-FUNDS-" (rand-int 1000000))})
        maker (tdb/insert-actor! tdb/*pool* {:organisation-id org :display-name "Priya (maker)"
                                             :roles [:operator]})
        checker-a (tdb/insert-actor! tdb/*pool* {:organisation-id org :display-name "Wei (checker)"
                                                 :roles [:approver] :limits {"SGD" 10000000}})
        checker-b (tdb/insert-actor! tdb/*pool* {:organisation-id org :display-name "Nadia (checker)"
                                                 :roles [:approver] :limits {"SGD" 10000000}})
        auditor (tdb/insert-actor! tdb/*pool* {:organisation-id org :display-name "Auditor"
                                               :roles [:auditor]})]
    (doseq [[from required] bands]
      (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "SGD"
                                         :from-minor from :approvals-required required}))
    {:org org :account account :maker maker :checker-a checker-a :checker-b checker-b
     :auditor auditor}))

(defn- create!
  [{:keys [org account maker]} & {:keys [minor-units] :or {minor-units 125000}}]
  (let [{:keys [status json]}
        (call :post "/payment-instructions"
              {:actor maker :idempotency-key (key!)
               :body {"organisationId"  (str org)
                      "debtorAccountId" (str account)
                      "creditorName"    "Pacific Rim Logistics Pte Ltd"
                      "creditorAccount" "SG-SYNTH-88012345"
                      "amount"          {"currency" "SGD" "minorUnits" minor-units}
                      "valueDate"       (str (.plusDays today 7))
                      "purposeCode"     "SUPP"}})]
    (is (= 201 status) (str "creation failed: " json))
    json))

(defn- submit!
  [{:keys [maker]} pi]
  (let [{:keys [status]} (call :post (str "/payment-instructions/" (get pi "id") "/submission")
                               {:actor maker :idempotency-key (key!) :body {}})]
    (is (= 200 status))
    pi))

(defn- pending!
  [f & {:as opts}]
  (submit! f (apply create! f (mapcat identity opts))))

(defn- approve!
  [f pi actor & {:keys [decision reason idempotency-key correlation-id]
                 :or {decision "approved"}}]
  (call :post (str "/payment-instructions/" (get pi "id") "/approvals")
        {:actor actor
         :idempotency-key (or idempotency-key (key!))
         :correlation-id correlation-id
         :body (cond-> {"decision" decision} reason (assoc "reason" reason))}))

(defn- status-of [f pi]
  (get (:json (call :get (str "/payment-instructions/" (get pi "id")) {:actor (:maker f)}))
       "status"))

(defn- audit-rows
  ([] (db/query tdb/*pool* ["select action, actor_id, subject_id, correlation_id
                               from audit_event order by occurred_at, id"]))
  ([subject-id] (db/query tdb/*pool* ["select action, actor_id, subject_id, correlation_id
                                         from audit_event where subject_id = ?
                                        order by occurred_at, id" subject-id])))

;; ---------------------------------------------------------------------------
;; AC-1 / AC-2 — segregation of duties over HTTP
;; ---------------------------------------------------------------------------

(deftest ac-1-the-maker-cannot-approve-their-own-instruction
  (testing "C-01. Refused by the domain function; this asserts the boundary reports it"
    (let [f (setup)
          pi (pending! f)
          {:keys [status json]} (approve! f pi (:maker f))]
      (is (= 403 status))
      (is (= "self-approval" (get-in json ["errors" "reason"]))
          "the reason is a keyword a client can branch on, not prose it must parse")
      (is (= "https://clofin.dev/problems/forbidden" (get json "type")))
      (is (= "pending-approval" (status-of f pi)) "and nothing moved")
      (is (zero? (:count (db/query-one tdb/*pool* ["select count(*) as count from approval"])))))))

(deftest ac-1-a-maker-who-also-holds-the-approver-role-is-still-refused
  (testing "the control is about provenance, not about which roles happen to be granted"
    (let [f (setup)
          both (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                              :display-name "Maker and checker"
                                              :roles [:operator :approver]
                                              :limits {"SGD" 10000000}})
          pi (submit! (assoc f :maker both) (create! (assoc f :maker both)))
          {:keys [status json]} (approve! f pi both)]
      (is (= 403 status))
      (is (= "self-approval" (get-in json ["errors" "reason"]))))))

(deftest ac-2-an-approver-other-than-the-maker-succeeds
  (let [f (setup)
        pi (pending! f)
        {:keys [status json headers]} (approve! f pi (:checker-a f))]
    (is (= 201 status))
    (is (= "approved" (get-in json ["approval" "decision"])))
    (is (true? (get-in json ["approval" "live"])))
    (is (= 1 (get json "approvalsRequired")))
    (is (= 1 (get json "approvalsHeld")))
    (is (true? (get json "satisfied")))
    (is (= "approved" (get-in json ["paymentInstruction" "status"])))
    (is (some? (get headers "location")))
    (is (= "approved" (status-of f pi)))))

;; ---------------------------------------------------------------------------
;; F-001 — the maker–checker bypass, asserted dead
;; ---------------------------------------------------------------------------
;;
;; Milestone 1's external audit found that `submit` applied a permission and no
;; provenance check, while `evaluate` refused approval by `created-by` alone on
;; the strength of a docstring claim that creator and submitter could not
;; differ. Nothing enforced that claim, so an actor holding `operator` and
;; `approver` could submit somebody else's draft — becoming its maker in every
;; sense that mattered — and then approve it, because `created-by` still named
;; the other person.
;;
;; The chain below is the exploit as reported. It succeeded end to end; it now
;; stops at step 2.

(deftest f-001-a-second-actor-cannot-submit-someone-elses-draft
  (testing "provenance, not permission: B holds :payment/submit and is still refused"
    (let [f (setup)
          other-operator (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                                        :display-name "Second operator"
                                                        :roles [:operator]})
          pi (create! f)
          {:keys [status json]}
          (call :post (str "/payment-instructions/" (get pi "id") "/submission")
                {:actor other-operator :idempotency-key (key!) :body {}})]
      (is (= 403 status))
      (is (= "https://clofin.dev/problems/forbidden" (get json "type")))
      (is (= "creator-only" (get-in json ["errors" "rule"]))
          "named so a caller can tell this from a missing permission: the answer
           is not 'ask for a role', it is 'this is not your instruction'")
      (is (= "submit" (get-in json ["errors" "attempted"])))
      (is (= "draft" (status-of f pi)) "and the instruction did not move"))))

(deftest f-001-the-full-exploit-chain-is-dead
  (testing "A creates; B — holding operator AND approver, with a limit — tries to
            submit and then approve. One human, two roles, an approved payment."
    (let [f (setup)
          both (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                              :display-name "B (operator+approver)"
                                              :roles [:operator :approver]
                                              :limits {"SGD" 99999999}})
          pi (create! f)]                                   ; created by A (:maker f)
      (is (= (str (:maker f)) (get pi "createdBy")))

      (testing "step 2 — B submits A's draft"
        (let [{:keys [status]} (call :post (str "/payment-instructions/" (get pi "id") "/submission")
                                     {:actor both :idempotency-key (key!) :body {}})]
          (is (= 403 status) "this is where the chain now breaks")))

      (testing "step 3 — and with the instruction still a draft, B cannot approve either"
        (let [{:keys [status]} (approve! f pi both)]
          (is (= 409 status) "the lifecycle refuses `approve` on a draft")))

      (is (= "draft" (status-of f pi)))
      (is (zero? (:count (db/query-one tdb/*pool* ["select count(*) as count from approval"]))))

      (testing "and C-01's own documented evidence query returns no rows"
        ;; COMPLIANCE C-01 publishes this query and states that it returns
        ;; nothing. Before the fix it returned a row for this very chain.
        (is (empty? (db/query tdb/*pool*
                              ["select s.subject_id
                                  from audit_event s
                                  join audit_event a
                                    on a.subject_id = s.subject_id and a.actor_id = s.actor_id
                                 where s.action = 'payment.submitted'
                                   and a.action = 'payment.approved'"])))))))

(deftest f-001-provenance-is-refused-before-the-lifecycle-is-consulted
  (testing "a non-creator gets 403, not a 409 that hands them the instruction's
            state and the list of events that would have been permitted"
    (let [f (setup)
          other-operator (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                                        :display-name "Second operator"
                                                        :roles [:operator]})
          pi (pending! f)]                                  ; already submitted
      (let [{:keys [status json]}
            (call :post (str "/payment-instructions/" (get pi "id") "/submission")
                  {:actor other-operator :idempotency-key (key!) :body {}})]
        (is (= 403 status))
        (is (nil? (get-in json ["errors" "permitted"]))
            "the lifecycle's `permitted` list is not disclosed to an actor the
             operation is closed to")))))

(deftest f-001-cancel-remains-open-to-a-controller
  (testing "the guard is on the event, not the handler. A controller holds
            :payment/cancel and can never hold :payment/create, so gating cancel
            on the creator would make that grant unexercisable — and cancelling
            can never produce an approval."
    (let [f (setup)
          controller (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                                    :display-name "Controller"
                                                    :roles [:controller]})
          pi (create! f)
          {:keys [status]} (call :post (str "/payment-instructions/" (get pi "id") "/cancellation")
                                 {:actor controller :idempotency-key (key!) :body {}})]
      (is (= 200 status) "a controller stops a payment it did not raise")
      (is (= "cancelled" (status-of f pi))))))

(deftest f-001-the-creator-can-still-submit
  (testing "the obvious half, so a fix that refused everyone would be caught"
    (let [f (setup)
          pi (create! f)
          {:keys [status json]} (call :post (str "/payment-instructions/" (get pi "id") "/submission")
                                      {:actor (:maker f) :idempotency-key (key!) :body {}})]
      (is (= 200 status))
      (is (= "pending-approval" (get json "status"))))))

;; ---------------------------------------------------------------------------
;; AC-3 — the approver's own limit
;; ---------------------------------------------------------------------------

(deftest ac-3-an-amount-above-the-approvers-limit-is-refused
  (testing "C-02, PR-012"
    (let [f (setup)
          small-fry (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                                   :display-name "Junior checker"
                                                   :roles [:approver] :limits {"SGD" 100000}})
          pi (pending! f :minor-units 125000)
          {:keys [status json]} (approve! f pi small-fry)]
      (is (= 403 status))
      (is (= "above-actor-limit" (get-in json ["errors" "reason"])))
      (is (= 100000 (get-in json ["errors" "actor-limit-minor"]))
          "the caller is told the ceiling that applied, which is what makes the refusal actionable")
      (is (= "pending-approval" (status-of f pi))))))

(deftest ac-3-an-approver-with-no-limit-in-the-currency-is-refused
  (let [f (setup)
        no-limit (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                                :display-name "Unlimited by omission"
                                                :roles [:approver]})
        pi (pending! f)]
    (is (= "above-actor-limit" (get-in (:json (approve! f pi no-limit)) ["errors" "reason"]))
        "absent means zero, not unlimited")))

;; ---------------------------------------------------------------------------
;; AC-4 / AC-5 — thresholds and their boundaries
;; ---------------------------------------------------------------------------

(deftest ac-4-two-approvals-are-required-above-the-band-and-the-first-does-not-suffice
  (let [f (setup :bands [[0 1] [100000 2]])
        pi (pending! f :minor-units 500000)
        first-decision (approve! f pi (:checker-a f))]
    (is (= 201 (:status first-decision)))
    (is (= 2 (get-in first-decision [:json "approvalsRequired"])))
    (is (= 1 (get-in first-decision [:json "approvalsHeld"])))
    (is (false? (get-in first-decision [:json "satisfied"])))
    (is (= "pending-approval" (status-of f pi))
        "one approval on a two-approval band leaves the instruction pending")

    (let [second-decision (approve! f pi (:checker-b f))]
      (is (= 201 (:status second-decision)))
      (is (= 2 (get-in second-decision [:json "approvalsHeld"])))
      (is (true? (get-in second-decision [:json "satisfied"])))
      (is (= "approved" (status-of f pi))))))

(deftest ac-5-an-amount-exactly-on-a-boundary-falls-in-the-higher-band
  (testing "asserted at boundary − 1, boundary and boundary + 1 (PR-011)"
    (doseq [[amount expected] [[99999 1] [100000 2] [100001 2]]]
      (let [f (setup :bands [[0 1] [100000 2]])
            pi (pending! f :minor-units amount)
            {:keys [json]} (approve! f pi (:checker-a f))]
        (is (= expected (get json "approvalsRequired"))
            (str amount " minor units must require " expected " approval(s)"))
        (is (= (if (= 1 expected) "approved" "pending-approval") (status-of f pi)))))))

(deftest an-organisation-with-no-band-for-the-amount-cannot-approve-it
  (testing "default deny reaching configuration rather than identity"
    (let [f (setup :bands [[100000 2]])
          pi (pending! f :minor-units 500)
          {:keys [status json]} (approve! f pi (:checker-a f))]
      (is (= 422 status))
      (is (= "no-threshold-configured" (get-in json ["errors" "reason"])))
      (is (= "pending-approval" (status-of f pi))))))

;; ---------------------------------------------------------------------------
;; AC-6 — a rejection needs a reason
;; ---------------------------------------------------------------------------

(deftest ac-6-a-rejection-without-a-reason-is-422
  (let [f (setup)
        pi (pending! f)]
    (doseq [body [{"decision" "rejected"}
                  {"decision" "rejected" "reason" ""}
                  {"decision" "rejected" "reason" "   "}]]
      (let [{:keys [status json]}
            (call :post (str "/payment-instructions/" (get pi "id") "/approvals")
                  {:actor (:checker-a f) :idempotency-key (key!) :body body})]
        (is (= 422 status) (str "body " body " must be refused"))
        (is (= "https://clofin.dev/problems/validation" (get json "type")))
        (is (some? (get-in json ["errors" "reason"])))))
    (is (= "pending-approval" (status-of f pi)))))

(deftest a-rejection-with-a-reason-is-recorded-and-the-reason-retained
  (testing "PR-013: the reason is what makes a refused payment explicable afterwards"
    (let [f (setup)
          pi (pending! f)
          {:keys [status json]} (approve! f pi (:checker-a f)
                                          :decision "rejected"
                                          :reason "Counterparty not verified for this corridor")]
      (is (= 201 status))
      (is (= "rejected" (get-in json ["approval" "decision"])))
      (is (= "Counterparty not verified for this corridor" (get-in json ["approval" "reason"])))
      (is (= "rejected" (status-of f pi)) "one refusal ends the instruction")
      (is (= "Counterparty not verified for this corridor"
             (:reason (db/query-one tdb/*pool* ["select reason from approval"])))
          "and it is the stored row that carries it"))))

;; ---------------------------------------------------------------------------
;; AC-7 — an amendment invalidates approvals
;; ---------------------------------------------------------------------------

(deftest ac-7-amending-an-approved-instruction-invalidates-every-approval-and-returns-it-to-draft
  (testing "PR-014. An approver agreed to the values in front of them; after an
            amendment those are not the instruction's values any more."
    (let [f (setup :bands [[0 1]])
          pi (pending! f)]
      (is (= 201 (:status (approve! f pi (:checker-a f)))))
      (is (= "approved" (status-of f pi)))

      (let [{:keys [status json]}
            (call :patch (str "/payment-instructions/" (get pi "id"))
                  {:actor (:maker f) :idempotency-key (key!)
                   :body {"amount" {"currency" "SGD" "minorUnits" 999}}})]
        (is (= 200 status))
        (is (= "draft" (get json "status")))
        (is (= 999 (get-in json ["amount" "minorUnits"]))))

      (let [approvals (db/query tdb/*pool* ["select decision, invalidated_at from approval"])]
        (is (= 1 (count approvals)) "the decision is invalidated, never deleted")
        (is (every? :invalidated-at approvals)))

      (testing "and the same approver may approve the amended instruction again"
        (submit! f pi)
        (is (= 201 (:status (approve! f pi (:checker-a f))))
            "which is the whole reason an amendment invalidates rather than deletes")))))

(deftest ac-7-an-amendment-of-a-partly-approved-instruction-resets-the-count
  (let [f (setup :bands [[0 1] [100000 2]])
        pi (pending! f :minor-units 500000)]
    (approve! f pi (:checker-a f))
    (call :patch (str "/payment-instructions/" (get pi "id"))
          {:actor (:maker f) :idempotency-key (key!)
           :body {"creditorName" "Andaman Shipping Sdn Bhd"}})
    (submit! f pi)
    (let [{:keys [json]} (approve! f pi (:checker-a f))]
      (is (= 1 (get json "approvalsHeld"))
          "the earlier approval no longer counts, so this is the first again")
      (is (false? (get json "satisfied"))))))

;; ---------------------------------------------------------------------------
;; AC-8 — role
;; ---------------------------------------------------------------------------

(deftest ac-8-an-actor-without-the-approver-role-cannot-approve
  (testing "C-08, PR-070"
    (let [f (setup)
          pi (pending! f)]
      (doseq [[role expected-status]
              [[:operator 403] [:controller 403] [:compliance 403] [:auditor 403]]]
        (let [actor (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                                   :display-name (name role)
                                                   :roles [role] :limits {"SGD" 10000000}})
              {:keys [status json]} (approve! f pi actor)]
          (is (= expected-status status) (str "role " role " must not approve"))
          (is (= "https://clofin.dev/problems/forbidden" (get json "type")))))
      (testing "and an actor with no roles at all"
        (let [nobody (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                                    :display-name "Nobody"})]
          (is (= 403 (:status (approve! f pi nobody))))))
      (is (= "pending-approval" (status-of f pi))))))

(deftest ac-8-a-suspended-approver-cannot-approve
  (let [f (setup)
        suspended (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                                 :display-name "Suspended checker"
                                                 :roles [:approver] :limits {"SGD" 10000000}
                                                 :status "suspended"})
        pi (pending! f)]
    (is (= 403 (:status (approve! f pi suspended)))
        "the role table still says approver; the actor is stopped anyway")))

(deftest an-unauthenticated-or-unknown-caller-cannot-approve
  (let [f (setup)
        pi (pending! f)]
    (is (= 401 (:status (call :post (str "/payment-instructions/" (get pi "id") "/approvals")
                              {:idempotency-key (key!) :body {"decision" "approved"}})))
        "no actor header at all")
    (is (= 401 (:status (approve! f pi (random-uuid))))
        "an unknown actor is 401, not 404 — otherwise the actor table is enumerable")))

(deftest an-approver-in-another-organisation-cannot-see-the-instruction
  (let [f (setup)
        other (setup)
        pi (pending! f)
        {:keys [status]} (approve! f pi (:checker-a other))]
    (is (= 404 status)
        "scoped by the principal's organisation, so it is not found rather than forbidden")))

;; ---------------------------------------------------------------------------
;; The lifecycle still governs
;; ---------------------------------------------------------------------------

(deftest approving-a-draft-is-a-conflict-before-it-is-anything-else
  (testing "the lifecycle is asked before the actor is, so fixing permissions would not help"
    (let [f (setup)
          pi (create! f)
          {:keys [status json]} (approve! f pi (:checker-a f))]
      (is (= 409 status))
      (is (= "draft" (get-in json ["errors" "instruction-status"])))
      (is (= "approve" (get-in json ["errors" "attempted"]))))))

(deftest an-actor-cannot-approve-the-same-instruction-twice
  (let [f (setup :bands [[0 2]])
        pi (pending! f)]
    (is (= 201 (:status (approve! f pi (:checker-a f)))))
    (let [{:keys [status json]} (approve! f pi (:checker-a f))]
      (is (= 409 status))
      (is (= "already-approved" (get-in json ["errors" "reason"]))))))

;; ---------------------------------------------------------------------------
;; Withdrawal
;; ---------------------------------------------------------------------------

(deftest an-approver-can-withdraw-their-own-approval-and-the-row-survives
  (let [f (setup :bands [[0 2]])
        pi (pending! f)
        approval-id (get-in (:json (approve! f pi (:checker-a f))) ["approval" "id"])
        {:keys [status json]}
        (call :delete (str "/payment-instructions/" (get pi "id") "/approvals/" approval-id)
              {:actor (:checker-a f) :idempotency-key (key!)})]
    (is (= 200 status))
    (is (false? (get-in json ["approval" "live"])))
    (is (some? (get-in json ["approval" "invalidatedAt"])))
    (is (= 1 (:count (db/query-one tdb/*pool* ["select count(*) as count from approval"])))
        "withdrawn, not deleted — the decision that was taken is still evidence")
    (testing "and the approver may decide again afterwards"
      (is (= 201 (:status (approve! f pi (:checker-a f))))))))

(deftest an-approver-cannot-withdraw-someone-elses-approval
  (let [f (setup :bands [[0 2]])
        pi (pending! f)
        approval-id (get-in (:json (approve! f pi (:checker-a f))) ["approval" "id"])
        {:keys [status]}
        (call :delete (str "/payment-instructions/" (get pi "id") "/approvals/" approval-id)
              {:actor (:checker-b f) :idempotency-key (key!)})]
    (is (= 403 status)
        "otherwise the count would say nothing about who agreed to what")))

(deftest an-approval-cannot-be-withdrawn-once-the-instruction-is-approved
  (testing "unwinding one approval at a time would leave it approved with too few"
    (let [f (setup)
          pi (pending! f)
          approval-id (get-in (:json (approve! f pi (:checker-a f))) ["approval" "id"])
          {:keys [status json]}
          (call :delete (str "/payment-instructions/" (get pi "id") "/approvals/" approval-id)
                {:actor (:checker-a f) :idempotency-key (key!)})]
      (is (= 409 status))
      (is (= "approved" (get-in json ["errors" "instruction-status"])))
      (is (str/includes? (get json "detail") "amend")
          "and the caller is told what to do instead"))))

;; ---------------------------------------------------------------------------
;; AC-13 — the approval queue
;; ---------------------------------------------------------------------------

(deftest ac-13-the-queue-carries-what-an-approver-needs-to-decide
  (testing "PR-015: an approval given without context is a rubber stamp"
    (let [f (setup :bands [[0 1] [100000 2]])
          pi (pending! f :minor-units 500000)
          _ (approve! f pi (:checker-a f))
          {:keys [status json]} (call :get "/approvals/queue" {:actor (:checker-b f)})
          row (first (get json "approvalQueue"))]
      (is (= 200 status))
      (is (= 1 (get json "count")))
      (is (= {"currency" "SGD" "minorUnits" 500000} (get-in row ["paymentInstruction" "amount"])))
      (is (= "Pacific Rim Logistics Pte Ltd" (get-in row ["paymentInstruction" "creditorName"])))
      (is (= "SG-SYNTH-88012345" (get-in row ["paymentInstruction" "creditorAccount"])))
      (is (= "SUPP" (get-in row ["paymentInstruction" "purposeCode"])))
      (is (= 1 (get row "approvalsHeld")))
      (is (= 2 (get row "approvalsRequired")))
      (is (= 1 (get row "approvalsRemaining")))
      (is (= 1 (count (get row "priorApprovals"))))
      (is (= (str (:checker-a f)) (get-in row ["priorApprovals" 0 "actorId"]))
          "who approved already, so the second approver is not deciding blind")
      (is (true? (get row "canApprove"))))))

(deftest ac-13-a-row-the-actor-may-not-approve-is-shown-with-the-reason
  (testing "hiding it would be a control implemented in a list query, and would
            leave a maker unable to see that their own payment is waiting"
    (let [f (setup)
          _ (pending! f)
          maker-view (:json (call :get "/approvals/queue" {:actor (:maker f)}))]
      ;; The maker holds `:operator`, which does not carry `:approval/read`.
      (is (= 403 (:status (call :get "/approvals/queue" {:actor (:maker f)})))
          "and an operator cannot read the queue at all")
      (identity maker-view))

    (let [f (setup)
          also-approver (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f)
                                                       :display-name "Maker who also checks"
                                                       :roles [:operator :approver]
                                                       :limits {"SGD" 10000000}})
          _ (pending! (assoc f :maker also-approver))
          row (first (get (:json (call :get "/approvals/queue" {:actor also-approver}))
                          "approvalQueue"))]
      (is (false? (get row "canApprove")))
      (is (= "self-approval" (get row "refusalReason"))
          "produced by the same function that would refuse the approval itself"))))

(deftest the-queue-holds-only-instructions-awaiting-approval
  (let [f (setup)
        pending (pending! f)
        _ (create! f)                                   ; still a draft
        approved (pending! f)]
    (approve! f approved (:checker-a f))
    (let [json (:json (call :get "/approvals/queue" {:actor (:checker-a f)}))]
      (is (= 1 (get json "count")))
      (is (= (get pending "id")
             (get-in json ["approvalQueue" 0 "paymentInstruction" "id"]))))))

;; ---------------------------------------------------------------------------
;; AC-9 / AC-10 — the audit trail, over the API
;; ---------------------------------------------------------------------------

(deftest ac-9-every-state-change-leaves-exactly-one-audit-event
  (testing "PR-072, PR-075"
    (let [f (setup)
          pi (create! f)
          subject (java.util.UUID/fromString (get pi "id"))]
      (submit! f pi)
      (approve! f pi (:checker-a f) :correlation-id "corr-approve")
      (let [rows (audit-rows subject)]
        (is (= ["payment.created" "payment.submitted" "payment.approved"]
               (mapv :action rows))
            "one event per state change, in the order they happened")
        (is (= [(:maker f) (:maker f) (:checker-a f)] (mapv :actor-id rows))
            "each carrying the actor who caused it (PR-072)")
        (is (= "corr-approve" (:correlation-id (last rows)))
            "and the correlation id that joins it to the log line and the response")))))

(deftest ac-9-a-refused-approval-leaves-no-audit-event
  (testing "an event for something that did not happen is worse than no event"
    (let [f (setup)
          pi (pending! f)
          subject (java.util.UUID/fromString (get pi "id"))
          before (count (audit-rows subject))]
      (is (= 403 (:status (approve! f pi (:maker f)))))
      (is (= before (count (audit-rows subject)))))))

(deftest ac-10-a-rolled-back-approval-leaves-no-audit-event-and-no-approval
  (testing "C-05, PR-075. The rollback is caused the way a real one would be: a
            second approval by the same actor, which the partial unique index
            refuses *after* the audit write has already been issued on the same
            transaction."
    (let [f (setup :bands [[0 2]])
          pi (pending! f)
          subject (java.util.UUID/fromString (get pi "id"))]
      (is (= 201 (:status (approve! f pi (:checker-a f)))))
      (let [events-before (count (audit-rows subject))
            approvals-before (:count (db/query-one tdb/*pool*
                                                   ["select count(*) as count from approval"]))]
        (is (= 409 (:status (approve! f pi (:checker-a f)))))
        (is (= events-before (count (audit-rows subject)))
            "the refused attempt left no audit event behind")
        (is (= approvals-before (:count (db/query-one tdb/*pool*
                                                      ["select count(*) as count from approval"])))
            "and no approval either — the transaction went down whole")))))

(deftest ac-10-a-failed-transition-leaves-neither-the-change-nor-the-event
  (let [f (setup)
        pi (pending! f)
        subject (java.util.UUID/fromString (get pi "id"))
        before (count (audit-rows subject))]
    (is (= 409 (:status (call :post (str "/payment-instructions/" (get pi "id") "/submission")
                              {:actor (:maker f) :idempotency-key (key!) :body {}})))
        "already submitted")
    (is (= before (count (audit-rows subject))))
    (is (= "pending-approval" (status-of f pi)))))

(deftest an-audit-event-carries-digests-and-never-the-payload
  (testing "C-09, ADR-0016: an append-only table holding counterparty names can never be cleaned"
    (let [f (setup)
          pi (pending! f)]
      (approve! f pi (:checker-a f))
      (let [dump (pr-str (db/query tdb/*pool* ["select * from audit_event"]))]
        (is (not (str/includes? dump "Pacific Rim"))
            "the creditor name is in the digest input and must not be in the table")
        (is (not (str/includes? dump "SG-SYNTH")))
        (is (str/includes? dump "v1:")
            "digests are version-tagged, so a later change to the canonical form is visible")))))

;; ---------------------------------------------------------------------------
;; AC-12 — evidence
;; ---------------------------------------------------------------------------

(deftest ac-12-an-evidence-pack-contains-every-state-change-in-order-with-its-actor
  (testing "PR-074"
    (let [f (setup :bands [[0 1] [100000 2]])
          pi (pending! f :minor-units 500000)]
      (approve! f pi (:checker-a f))
      (approve! f pi (:checker-b f))
      (let [{:keys [status json]}
            (call :get (str "/audit/evidence/" (get pi "id")) {:actor (:auditor f)})
            events  (get json "events")
            actions (mapv #(get % "action") events)]
        (is (= 200 status))
        (is (= 5 (get json "count")))

        (testing "the pack now carries the approval decisions as well as the
                  payment's own transitions (F-006): approval events are keyed
                  on the approval, and extraction relates them to their payment"
          (is (= {"payment.created" 1 "payment.submitted" 1
                  "approval.recorded" 2 "payment.approved" 1}
                 (frequencies actions))))

        (testing "exactly ONE payment.approved for two approvals (F-005) —
                  before the fix this said two, and the first described a
                  payment that was still pending-approval"
          (is (= 1 (count (filter #{"payment.approved"} actions)))))

        (testing "the payment's own events come first and in order"
          (is (= ["payment.created" "payment.submitted"] (subvec actions 0 2)))
          (is (= [(str (:maker f)) (str (:maker f))]
                 (mapv #(get % "actorId") (subvec events 0 2)))))

        (testing "each decision names the approver who made it"
          (is (= #{(str (:checker-a f)) (str (:checker-b f))}
                 (set (keep #(when (= "approval.recorded" (get % "action"))
                               (get % "actorId"))
                            events)))))

        (testing "and the transition names the approver whose decision completed it"
          (is (= (str (:checker-b f))
                 (some #(when (= "payment.approved" (get % "action")) (get % "actorId"))
                       events))))

        ;; The completing `approval.recorded` and `payment.approved` are written
        ;; in one transaction and therefore share `occurred_at` exactly, so
        ;; their relative order is stable but not causal — asserting a strict
        ;; sequence across them would be asserting an id comparison.
        (testing "and every approval event precedes or accompanies the transition"
          (let [approved-at (some #(when (= "payment.approved" (get % "action"))
                                     (get % "occurredAt"))
                                  events)]
            (is (every? (fn [e] (<= (compare (get e "occurredAt") approved-at) 0))
                        (filter #(= "approval.recorded" (get % "action")) events)))))

        (is (false? (get json "truncated")) "the pack states its own completeness")
        (is (some? (get json "from")))
        (is (some? (get json "to")))))))

(deftest an-evidence-pack-for-an-unknown-subject-is-404
  (testing "an empty pack reads as proof that nothing happened, which is worse than none"
    (let [f (setup)]
      (is (= 404 (:status (call :get (str "/audit/evidence/" (random-uuid))
                                {:actor (:auditor f)})))))))

(deftest audit-events-can-be-listed-and-narrowed
  (let [f (setup)
        a (pending! f)
        _ (pending! f)]
    (approve! f a (:checker-a f))
    (let [all (:json (call :get "/audit/events" {:actor (:auditor f)}))]
      ;; created + submitted for each of two instructions, plus one
      ;; approval.recorded and one payment.approved for the approved one.
      (is (= 6 (get all "count")))
      (is (= 500 (get all "limit")))
      (is (false? (get all "truncated"))))
    (is (= 1 (get (:json (call :get "/audit/events"
                               {:actor (:auditor f)
                                :query "action=payment.approved"}))
                  "count")))
    (is (= 3 (get (:json (call :get "/audit/events"
                               {:actor (:auditor f)
                                :query (str "subjectId=" (get a "id"))}))
                  "count")))
    (testing "an unknown action is refused rather than silently matching nothing"
      (is (= 400 (:status (call :get "/audit/events"
                                {:actor (:auditor f) :query "action=payment.exploded"})))))))

(deftest reading-the-audit-trail-needs-the-audit-permission
  (testing "an operator who could read the whole trail could see which approvers
            act on what and when, which is reconnaissance rather than transparency"
    (let [f (setup)
          pi (pending! f)]
      (doseq [actor [(:maker f) (:checker-a f)]]
        (is (= 403 (:status (call :get "/audit/events" {:actor actor}))))
        (is (= 403 (:status (call :get (str "/audit/evidence/" (get pi "id")) {:actor actor})))))
      (is (= 200 (:status (call :get "/audit/events" {:actor (:auditor f)})))))))

(deftest an-audit-trail-does-not-cross-organisations
  (let [f (setup)
        other (setup)
        pi (pending! f)]
    (is (zero? (get (:json (call :get "/audit/events" {:actor (:auditor other)})) "count")))
    (is (= 404 (:status (call :get (str "/audit/evidence/" (get pi "id"))
                              {:actor (:auditor other)}))))))

;; ---------------------------------------------------------------------------
;; Idempotency reaches the new endpoints too
;; ---------------------------------------------------------------------------

(deftest an-approval-is-idempotent-under-its-key
  (let [f (setup)
        pi (pending! f)
        k (key!)
        first-call (approve! f pi (:checker-a f) :idempotency-key k)
        replay (approve! f pi (:checker-a f) :idempotency-key k)]
    (is (= 201 (:status first-call)))
    (is (= 201 (:status replay)))
    (is (= (:body first-call) (:body replay)) "byte-identical, from the stored response")
    (is (= "true" (get-in replay [:headers "idempotent-replayed"])))
    (is (= 1 (:count (db/query-one tdb/*pool* ["select count(*) as count from approval"])))
        "and exactly one approval exists")
    (is (= 1 (count (filter #(= "payment.approved" (:action %))
                            (audit-rows (java.util.UUID/fromString (get pi "id"))))))
        "and exactly one audit event — a replay records nothing new")))

(deftest an-approval-without-an-idempotency-key-is-refused
  (let [f (setup)
        pi (pending! f)]
    (is (= 400 (:status (call :post (str "/payment-instructions/" (get pi "id") "/approvals")
                              {:actor (:checker-a f) :body {"decision" "approved"}}))))
    (is (= 400 (:status (call :delete (str "/payment-instructions/" (get pi "id")
                                           "/approvals/" (random-uuid))
                              {:actor (:checker-a f)}))))))

(deftest an-unknown-decision-is-refused-rather-than-defaulted
  (let [f (setup)
        pi (pending! f)]
    (doseq [decision ["maybe" "APPROVED" "" nil]]
      (is (= 400 (:status (call :post (str "/payment-instructions/" (get pi "id") "/approvals")
                                {:actor (:checker-a f) :idempotency-key (key!)
                                 :body {"decision" decision}})))
          (str "decision " (pr-str decision) " must be refused")))))

;; ---------------------------------------------------------------------------
;; F-005 — an action named after a transition is emitted only when it commits
;; ---------------------------------------------------------------------------

(deftest f-005-a-partial-approval-records-a-decision-and-no-transition
  (testing "the first of two required approvals leaves the payment
            pending-approval. Before the fix it wrote `payment.approved`
            anyway, with before and after digests that were identical because
            nothing had changed — an event asserting a transition that had not
            happened."
    (let [f (setup :bands [[0 1] [100000 2]])
          pi (pending! f :minor-units 500000)
          subject (java.util.UUID/fromString (get pi "id"))]
      (approve! f pi (:checker-a f))
      (is (= "pending-approval" (status-of f pi)))

      (let [rows (db/query tdb/*pool*
                           ["select action, subject_type from audit_event
                              where action in ('payment.approved','approval.recorded')"])]
        (is (= [{:action "approval.recorded" :subject-type "approval"}]
               (mapv #(select-keys % [:action :subject-type]) rows))
            "one decision recorded, no transition claimed"))

      (testing "and the second approval adds the transition, once"
        (approve! f pi (:checker-b f))
        (is (= "approved" (status-of f pi)))
        (is (= {"approval.recorded" 2 "payment.approved" 1}
               (frequencies (map :action
                                 (db/query tdb/*pool*
                                           ["select action from audit_event
                                              where action in ('payment.approved','approval.recorded')"])))))
        (is (= 3 (count (audit-rows subject)))
            "the payment's own subject carries created, submitted and approved — not the decisions")))))

(deftest f-005-the-recorded-decision-describes-the-approval-not-the-payment
  (let [f (setup)
        pi (pending! f)
        approval-id (get-in (:json (approve! f pi (:checker-a f))) ["approval" "id"])
        row (db/query-one tdb/*pool*
                          ["select subject_id, subject_type, before_digest, after_digest, actor_id
                              from audit_event where action = 'approval.recorded'"])]
    (is (= (java.util.UUID/fromString approval-id) (:subject-id row))
        "the subject is the decision that came into existence")
    (is (= "approval" (:subject-type row)))
    (is (nil? (:before-digest row)) "an approval has no before — it did not exist")
    (is (some? (:after-digest row)))
    (is (= (:checker-a f) (:actor-id row)))))

(deftest f-005-a-rejection-records-a-decision-and-a-transition
  (testing "a rejection DOES move the payment, so under L-7 it is two events:
            the decision, and the transition it caused"
    (let [f (setup)
          pi (pending! f)]
      (approve! f pi (:checker-a f) :decision "rejected" :reason "Counterparty unverified")
      (is (= "rejected" (status-of f pi)))
      (is (= {"payment.created" 1 "payment.submitted" 1
              "approval.recorded" 1 "payment.rejected" 1}
             (frequencies (map :action (audit-rows))))))))

(deftest f-005-c-01-evidence-query-still-detects-a-same-actor-submit-and-decide
  (testing "COMPLIANCE publishes a query proving no actor both submitted and
            approved. F-005 moved approvals out of `payment.approved`, so the
            query now joins through the approval record — and it must still
            return nothing, and must still be *capable* of returning something."
    (let [f (setup)
          pi (pending! f)
          _  (approve! f pi (:checker-a f))
          ;; The query as COMPLIANCE now publishes it.
          offending (db/query tdb/*pool*
                              ["select s.subject_id
                                  from audit_event s
                                  join approval ap on ap.instruction_id = s.subject_id
                                  join audit_event d on d.subject_id = ap.id
                                                    and d.action = 'approval.recorded'
                                 where s.action = 'payment.submitted'
                                   and d.actor_id = s.actor_id"])]
      (is (empty? offending) "the control holds")

      (testing "and the query is not vacuous — it finds a planted violation"
        ;; Plant the row the control exists to prevent, by writing an approval
        ;; for the maker directly. The API refuses this; the query must see it.
        (let [instruction (java.util.UUID/fromString (get pi "id"))
              planted (tdb/insert-approval! tdb/*pool*
                                            {:instruction-id instruction
                                             :actor-id (:maker f)})]
          (db/with-transaction [tx tdb/*pool*]
            (db/execute! tx ["insert into audit_event
                                (id, organisation_id, actor_id, action, subject_type, subject_id)
                              values (?, ?, ?, 'approval.recorded', 'approval', ?)"
                             (random-uuid) (:org f) (:maker f) planted]))
          (is (seq (db/query tdb/*pool*
                             ["select s.subject_id
                                 from audit_event s
                                 join approval ap on ap.instruction_id = s.subject_id
                                 join audit_event d on d.subject_id = ap.id
                                                   and d.action = 'approval.recorded'
                                where s.action = 'payment.submitted'
                                  and d.actor_id = s.actor_id"]))
              "a query that cannot detect the violation is not evidence of anything"))))))

;; ---------------------------------------------------------------------------
;; F-006 — invalidation is a state change and gets its own events
;; ---------------------------------------------------------------------------

(deftest f-006-an-amendment-emits-one-event-per-invalidated-approval
  (testing "PR-014 invalidates approvals; before the fix the trail said only
            that the payment had been amended, leaving a reader to infer from a
            column that somebody's approval had been revoked — without who,
            when, or under which correlation id"
    (let [f (setup :bands [[0 1] [100000 2]])
          pi (pending! f :minor-units 500000)
          a1 (get-in (:json (approve! f pi (:checker-a f))) ["approval" "id"])
          a2 (get-in (:json (approve! f pi (:checker-b f))) ["approval" "id"])
          subject (java.util.UUID/fromString (get pi "id"))]
      (is (= "approved" (status-of f pi)))

      (let [{:keys [status]}
            (call :patch (str "/payment-instructions/" (get pi "id"))
                  {:actor (:maker f) :idempotency-key (key!)
                   :correlation-id "corr-amend"
                   :body {"amount" {"currency" "SGD" "minorUnits" 999}}})]
        (is (= 200 status)))

      (let [invalidations (db/query tdb/*pool*
                                    ["select subject_id, subject_type, actor_id, correlation_id,
                                             before_digest, after_digest
                                        from audit_event where action = 'approval.invalidated'
                                       order by subject_id"])]
        (is (= 2 (count invalidations)) "one event per approval, not one for the amendment")
        (is (= #{(java.util.UUID/fromString a1) (java.util.UUID/fromString a2)}
               (set (map :subject-id invalidations)))
            "each names the approval it invalidated")
        (is (every? #(= "approval" (:subject-type %)) invalidations))
        (is (every? #(= (:maker f) (:actor-id %)) invalidations)
            "the amending actor caused it — that is who an investigation asks about")
        (is (every? #(= "corr-amend" (:correlation-id %)) invalidations)
            "and the correlation id joins them to the amendment request")
        (is (every? #(and (some? (:before-digest %)) (some? (:after-digest %)))
                    invalidations))
        (is (every? #(not= (:before-digest %) (:after-digest %)) invalidations)
            "the digests differ: the approval genuinely changed state"))

      (testing "and the payment's own amendment event is there too"
        (is (= 1 (count (filter #(= "payment.amended" (:action %)) (audit-rows subject))))))

      (testing "the evidence pack relates all of them to the payment"
        (let [json (:json (call :get (str "/audit/evidence/" (get pi "id"))
                                {:actor (:auditor f)}))]
          (is (= {"payment.created" 1 "payment.submitted" 1 "approval.recorded" 2
                  "payment.approved" 1 "payment.amended" 1 "approval.invalidated" 2}
                 (frequencies (map #(get % "action") (get json "events"))))))))))

(deftest f-006-the-amendment-and-its-invalidation-events-roll-back-together
  (testing "C-05, PR-075. The amendment, the payment event and every
            invalidation event share one transaction — so a failure leaves the
            approvals standing and the trail silent, rather than a trail that
            reports revocations that did not happen."
    (let [f (setup)
          pi (pending! f)
          _  (approve! f pi (:checker-a f))
          before-events (count (audit-rows))
          before-live (:count (db/query-one tdb/*pool*
                                            ["select count(*) as count from approval
                                               where invalidated_at is null"]))]
      (is (= "approved" (status-of f pi)))

      ;; An amendment that fails *after* the invalidation: a purpose code the
      ;; domain refuses. The invalidation has already run inside the
      ;; transaction when validation rejects the amended whole.
      (let [{:keys [status]}
            (call :patch (str "/payment-instructions/" (get pi "id"))
                  {:actor (:maker f) :idempotency-key (key!)
                   :body {"purposeCode" "NOT-A-REAL-PURPOSE-CODE"}})]
        (is (= 422 status) "the amendment is refused"))

      (is (= before-events (count (audit-rows)))
          "no payment.amended, and no approval.invalidated — the trail records nothing")
      (is (= before-live (:count (db/query-one tdb/*pool*
                                               ["select count(*) as count from approval
                                                  where invalidated_at is null"])))
          "and the approval still stands")
      (is (= "approved" (status-of f pi))))))

(deftest f-006-withdrawal-also-appears-in-the-payment-evidence-pack
  (testing "`approval.withdrawn` already used the approval as its subject, so
            extraction relating approval events to their payment picks it up
            too — the whole approval lifecycle, in one pack"
    (let [f (setup :bands [[0 2]])
          pi (pending! f)
          approval-id (get-in (:json (approve! f pi (:checker-a f))) ["approval" "id"])]
      (call :delete (str "/payment-instructions/" (get pi "id") "/approvals/" approval-id)
            {:actor (:checker-a f) :idempotency-key (key!)})
      (let [json (:json (call :get (str "/audit/evidence/" (get pi "id"))
                              {:actor (:auditor f)}))]
        (is (= {"payment.created" 1 "payment.submitted" 1
                "approval.recorded" 1 "approval.withdrawn" 1}
               (frequencies (map #(get % "action") (get json "events")))))))))
