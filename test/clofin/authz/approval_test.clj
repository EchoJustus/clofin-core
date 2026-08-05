(ns clofin.authz.approval-test
  "The approval decision, called directly.

  Every test here calls `evaluate` with values and asserts on the value it
  returns. **There is no HTTP layer and no database anywhere in this file**,
  and that is the assertion the file exists to make: segregation of duties is a
  domain rule (PR-071, C-01), so it must be provable without a request. A test
  that could only demonstrate the control by issuing a `POST` would be
  demonstrating the handler.

  AC-1, AC-3 and AC-8 from
  docs/briefs/003-TASK-authorisation-and-audit-trail.md are table-driven across
  the actor × instruction matrix rather than sampled, so a combination nobody
  thought to write a case for is still covered."
  (:require [clofin.authz.approval :as approval]
            [clofin.authz.model :as model]
            [clofin.money :as money]
            ;; Required for the A-016 guard at the foot of this file, and for
            ;; no other reason. `approval-service` names *this* namespace's test
            ;; as the thing that fails when its refusal maps fall behind
            ;; `refusal-reasons`; until A-016 that sentence was a promise
            ;; nothing kept, because the namespace it named did not load the
            ;; namespace it was about.
            [clofin.payments.approval-service :as approval-service]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Fixtures — values, not rows
;; ---------------------------------------------------------------------------

(def maker-id     (random-uuid))
(def approver-id  (random-uuid))
(def approver-2-id (random-uuid))

(defn- actor
  [id & {:keys [roles status limits]
         :or {roles #{:approver} status :active limits {"SGD" 10000000}}}]
  {:id id :organisation-id (random-uuid) :display-name (str id)
   :status status :roles roles :limits limits})

(defn- instruction
  [& {:keys [amount created-by status]
      :or {amount (money/of "SGD" 125000) created-by maker-id status :pending-approval}}]
  {:id (random-uuid) :organisation-id (random-uuid)
   :amount amount :created-by created-by :status status
   :creditor-name "Pacific Rim Logistics Pte Ltd"})

(def one-approval-band  [{:from-minor 0 :approvals-required 1}])
(def two-band
  "Below 1,000.00 one approval; at and above it, two."
  [{:from-minor 0 :approvals-required 1}
   {:from-minor 100000 :approvals-required 2}])

(defn- decide
  [& {:keys [actor instruction existing thresholds decision]
      :or {existing [] thresholds one-approval-band decision :approved}}]
  (approval/evaluate {:actor actor :instruction instruction
                      :existing-approvals existing :thresholds thresholds
                      :decision decision}))

(defn- reason-of [result] (:reason result))

;; ---------------------------------------------------------------------------
;; AC-1 — segregation of duties, over the whole actor matrix
;; ---------------------------------------------------------------------------

(deftest ac-1-the-maker-can-never-approve-their-own-instruction
  (testing "C-01, and it does not depend on what else the maker holds"
    (doseq [roles [#{:operator}
                   #{:approver}
                   #{:operator :approver}
                   #{:operator :approver :controller :compliance :auditor}]
            limits [{} {"SGD" 1} {"SGD" 10000000}]
            amount [(money/of "SGD" 1) (money/of "SGD" 125000) (money/of "SGD" 99999999)]]
      (let [result (decide :actor (actor maker-id :roles roles :limits limits)
                           :instruction (instruction :amount amount))]
        (is (= :refused (:decision result))
            (str "maker with roles " roles " and limits " limits
                 " must not approve their own instruction"))
        (is (= :self-approval (reason-of result))
            (str "the reason must be :self-approval even when another refusal "
                 "would also apply — roles " roles ", limits " limits))))))

(deftest ac-1-self-approval-is-refused-before-every-other-reason
  (testing "an actor who is the maker AND unqualified AND over limit is told the one that cannot be fixed"
    (is (= :self-approval
           (reason-of (decide :actor (actor maker-id :roles #{} :limits {})
                              :instruction (instruction)
                              :thresholds []))))))

;; ---------------------------------------------------------------------------
;; AC-2 — a different approver succeeds
;; ---------------------------------------------------------------------------

(deftest ac-2-an-approver-other-than-the-maker-is-permitted
  (let [result (decide :actor (actor approver-id) :instruction (instruction))]
    (is (= :permitted (:decision result)))
    (is (= 1 (:approvals-required result)))
    (is (= 0 (:approvals-held result)))
    (is (true? (:completes? result)))))

;; ---------------------------------------------------------------------------
;; AC-3 — the approver's own limit, over the matrix
;; ---------------------------------------------------------------------------

(deftest ac-3-an-amount-above-the-actor-limit-is-refused
  (testing "C-02, PR-012: at, below and above the actor's ceiling"
    (doseq [[ceiling amount expected]
            [[100000 99999  :permitted]
             [100000 100000 :permitted]                 ; the limit is inclusive
             [100000 100001 :refused]
             [1      2      :refused]
             [1      1      :permitted]]]
      (let [result (decide :actor (actor approver-id :limits {"SGD" ceiling})
                           :instruction (instruction :amount (money/of "SGD" amount)))]
        (is (= expected (:decision result))
            (str "ceiling " ceiling " against amount " amount))
        (when (= :refused expected)
          (is (= :above-actor-limit (reason-of result))))))))

(deftest ac-3-an-approver-with-no-limit-in-the-currency-cannot-approve
  (testing "absent means zero, not unlimited — the direction that fails safe"
    (is (= :above-actor-limit
           (reason-of (decide :actor (actor approver-id :limits {})
                              :instruction (instruction)))))
    (is (= :above-actor-limit
           (reason-of (decide :actor (actor approver-id :limits {"EUR" 99999999})
                              :instruction (instruction :amount (money/of "SGD" 1))))))))

(deftest a-wildcard-limit-applies-to-every-currency
  (testing "the pure rule. That it survives *storage* is
            `clofin.authz.repository-test` — migration 0005 made the row
            uninsertable and 0006 fixed it (objection O-1), and this test passed
            throughout, which is exactly why a pure test alone was not enough"
    (let [everywhere (actor approver-id :limits {approval/wildcard-currency 500000})]
      (is (= :permitted (:decision (decide :actor everywhere
                                           :instruction (instruction :amount (money/of "SGD" 500000))))))
      (is (= :permitted (:decision (decide :actor everywhere
                                           :instruction (instruction :amount (money/of "JPY" 400000))
                                           :thresholds one-approval-band))))
      (is (= :above-actor-limit
             (reason-of (decide :actor everywhere
                                :instruction (instruction :amount (money/of "SGD" 500001)))))))))

(deftest a-currency-specific-limit-beats-the-wildcard
  (let [mixed (actor approver-id :limits {approval/wildcard-currency 999999999 "SGD" 100})]
    (is (= :above-actor-limit
           (reason-of (decide :actor mixed :instruction (instruction :amount (money/of "SGD" 101))))))
    (is (= :permitted
           (:decision (decide :actor mixed :instruction (instruction :amount (money/of "EUR" 5000))))))))

;; ---------------------------------------------------------------------------
;; AC-5 — band boundaries
;; ---------------------------------------------------------------------------

(deftest ac-5-a-band-boundary-is-inclusive-of-its-own-lower-bound
  (testing "asserted at boundary − 1, boundary and boundary + 1 (PR-011)"
    (is (= 1 (approval/approvals-required two-band (money/of "SGD" 99999))))
    (is (= 2 (approval/approvals-required two-band (money/of "SGD" 100000)))
        "an amount exactly on a boundary falls in the HIGHER band — the side that asks for more scrutiny")
    (is (= 2 (approval/approvals-required two-band (money/of "SGD" 100001))))))

(deftest ac-5-the-boundary-rule-holds-across-three-bands
  (let [bands [{:from-minor 0 :approvals-required 1}
               {:from-minor 100000 :approvals-required 2}
               {:from-minor 10000000 :approvals-required 3}]]
    (doseq [[amount expected] [[0 1] [1 1] [99999 1]
                               [100000 2] [100001 2] [9999999 2]
                               [10000000 3] [10000001 3]]]
      (is (= expected (approval/approvals-required bands (money/of "SGD" amount)))
          (str amount " minor units must require " expected " approval(s)")))))

(deftest an-amount-below-every-band-has-no-requirement
  (testing "a band table with no floor leaves small amounts unapprovable, by design"
    (is (nil? (approval/approvals-required [{:from-minor 100000 :approvals-required 2}]
                                           (money/of "SGD" 99999))))))

;; ---------------------------------------------------------------------------
;; AC-4 — counting toward the threshold
;; ---------------------------------------------------------------------------

(deftest ac-4-the-first-of-two-approvals-does-not-complete-the-requirement
  (let [big (instruction :amount (money/of "SGD" 500000))
        first-decision (decide :actor (actor approver-id) :instruction big :thresholds two-band)]
    (is (= :permitted (:decision first-decision)))
    (is (= 2 (:approvals-required first-decision)))
    (is (false? (:completes? first-decision))
        "one approval on a two-approval band leaves the instruction pending")
    (let [second-decision (decide :actor (actor approver-2-id)
                                  :instruction big
                                  :thresholds two-band
                                  :existing [{:actor-id approver-id :decision :approved
                                              :invalidated-at nil}])]
      (is (= 1 (:approvals-held second-decision)))
      (is (true? (:completes? second-decision))))))

(deftest an-invalidated-approval-does-not-count
  (testing "PR-014: an approval invalidated by an amendment stops counting but stays visible"
    (let [big (instruction :amount (money/of "SGD" 500000))
          result (decide :actor (actor approver-2-id) :instruction big :thresholds two-band
                         :existing [{:actor-id approver-id :decision :approved
                                     :invalidated-at (java.time.Instant/now)}])]
      (is (= 0 (:approvals-held result)))
      (is (false? (:completes? result))))))

(deftest a-rejection-does-not-count-toward-the-approval-threshold
  (let [result (decide :actor (actor approver-2-id) :instruction (instruction) :thresholds two-band
                       :existing [{:actor-id approver-id :decision :rejected
                                   :reason "Counterparty unverified" :invalidated-at nil}])]
    (is (= 0 (:approvals-held result)))))

;; ---------------------------------------------------------------------------
;; AC-8 — role, over the whole matrix
;; ---------------------------------------------------------------------------

(deftest ac-8-an-actor-without-the-approver-role-cannot-approve
  (testing "C-08, PR-070: every role that is not `approver`, against every amount"
    (doseq [role (remove #{:approver} model/roles)
            amount [(money/of "SGD" 1) (money/of "SGD" 125000)]]
      (let [result (decide :actor (actor approver-id :roles #{role})
                           :instruction (instruction :amount amount))]
        (is (= :refused (:decision result)) (str "role " role " must not approve"))
        (is (= :not-an-approver (reason-of result)))))
    (testing "and no role at all"
      (is (= :not-an-approver
             (reason-of (decide :actor (actor approver-id :roles #{})
                                :instruction (instruction))))))))

(deftest ac-8-a-suspended-approver-cannot-approve
  (testing "the role table still says `approver`; the actor is stopped anyway"
    (is (= :not-an-approver
           (reason-of (decide :actor (actor approver-id :status :suspended)
                              :instruction (instruction)))))))

(deftest the-full-actor-times-instruction-matrix
  (testing "AC-1, AC-3 and AC-8 together: every (role set × limit × maker) combination"
    (doseq [role-set [#{} #{:operator} #{:approver} #{:controller} #{:auditor} #{:operator :approver}]
            ceiling  [nil 1000 1000000]
            self?    [true false]]
      (let [id (if self? maker-id approver-id)
            result (decide :actor (actor id :roles role-set
                                         :limits (if ceiling {"SGD" ceiling} {}))
                           :instruction (instruction :amount (money/of "SGD" 125000)))
            expected (cond
                       self?                                   :self-approval
                       (not (contains? role-set :approver))     :not-an-approver
                       (or (nil? ceiling) (< ceiling 125000))   :above-actor-limit
                       :else                                    nil)]
        (if expected
          (is (= expected (reason-of result))
              (str "roles " role-set ", ceiling " ceiling ", self? " self?))
          (is (= :permitted (:decision result))
              (str "roles " role-set ", ceiling " ceiling ", self? " self?)))))))

;; ---------------------------------------------------------------------------
;; Already decided
;; ---------------------------------------------------------------------------

(deftest an-actor-cannot-decide-twice-on-one-instruction
  (let [existing [{:actor-id approver-id :decision :approved :invalidated-at nil}]]
    (is (= :already-approved
           (reason-of (decide :actor (actor approver-id) :instruction (instruction)
                              :existing existing))))))

(deftest an-actor-whose-approval-was-invalidated-may-approve-again
  (testing "which is the whole reason an amendment invalidates rather than deletes"
    (let [existing [{:actor-id approver-id :decision :approved
                     :invalidated-at (java.time.Instant/now)}]]
      (is (= :permitted
             (:decision (decide :actor (actor approver-id) :instruction (instruction)
                                :existing existing)))))))

(deftest an-actor-who-rejected-cannot-then-approve
  (let [existing [{:actor-id approver-id :decision :rejected :reason "no"
                   :invalidated-at nil}]]
    (is (= :already-approved
           (reason-of (decide :actor (actor approver-id) :instruction (instruction)
                              :existing existing))))))

;; ---------------------------------------------------------------------------
;; Unconfigured thresholds
;; ---------------------------------------------------------------------------

(deftest an-organisation-with-no-band-for-the-currency-cannot-have-it-approved
  (testing "default deny reaching configuration: guessing a requirement weakens the control"
    (is (= :no-threshold-configured
           (reason-of (decide :actor (actor approver-id) :instruction (instruction)
                              :thresholds []))))
    (is (= :no-threshold-configured
           (reason-of (decide :actor (actor approver-id)
                              :instruction (instruction :amount (money/of "SGD" 50))
                              :thresholds [{:from-minor 100000 :approvals-required 2}]))))))

;; ---------------------------------------------------------------------------
;; Rejection
;; ---------------------------------------------------------------------------

(deftest a-rejection-needs-the-reject-permission-not-the-approve-one
  (is (= :not-an-approver
         (reason-of (decide :actor (actor approver-id :roles #{:operator})
                            :instruction (instruction) :decision :rejected))))
  (is (= :permitted
         (:decision (decide :actor (actor approver-id :roles #{:approver})
                            :instruction (instruction) :decision :rejected)))))

(deftest a-rejection-is-not-bounded-by-the-actor-limit
  (testing "an approver's ceiling is authority to permit a payment, not to refuse one"
    (is (= :permitted
           (:decision (decide :actor (actor approver-id :limits {})
                              :instruction (instruction :amount (money/of "SGD" 99999999))
                              :decision :rejected))))))

(deftest a-rejection-does-not-need-a-configured-threshold
  (is (= :permitted
         (:decision (decide :actor (actor approver-id) :instruction (instruction)
                            :thresholds [] :decision :rejected)))))

(deftest the-maker-cannot-reject-their-own-instruction-either
  (testing "the checker's verdict is the checker's, in both directions; a maker who wants to stop their own payment amends it back to draft"
    (is (= :self-approval
           (reason-of (decide :actor (actor maker-id) :instruction (instruction)
                              :decision :rejected))))))

(deftest a-rejection-always-completes
  (is (true? (:completes? (decide :actor (actor approver-id) :instruction (instruction)
                                  :thresholds two-band :decision :rejected)))))

;; ---------------------------------------------------------------------------
;; PR-013 — a rejection reason is mandatory
;; ---------------------------------------------------------------------------

(deftest ac-6-a-rejection-without-a-reason-is-refused
  (doseq [blank [nil "" "   " "\t"]]
    (let [t (try (approval/assert-reason! :rejected blank) nil (catch Exception e e))]
      (is (some? t) (str "a rejection reason of " (pr-str blank) " must be refused"))
      (is (= :field-validation (:clofin/error (ex-data t)))
          "422 under the validation problem type, naming the field (ADR-0014)")
      (is (contains? (ex-data t) "reason")))))

(deftest an-approval-needs-no-reason
  (is (nil? (approval/assert-reason! :approved nil))))

(deftest a-rejection-with-a-reason-passes
  (is (= "Counterparty unverified" (approval/assert-reason! :rejected "Counterparty unverified"))))

;; ---------------------------------------------------------------------------
;; The refusal vocabulary is closed
;; ---------------------------------------------------------------------------

(deftest every-refusal-reason-is-declared
  (testing "a reason a caller has no answer for would be a 500 the first time it fired"
    (doseq [[inputs expected]
            [[{:actor (actor maker-id)} :self-approval]
             [{:actor (actor approver-id :roles #{})} :not-an-approver]
             [{:actor (actor approver-id :limits {})} :above-actor-limit]
             [{:actor (actor approver-id)
               :existing [{:actor-id approver-id :decision :approved :invalidated-at nil}]}
              :already-approved]
             [{:actor (actor approver-id) :thresholds []} :no-threshold-configured]]]
      (let [result (apply decide (into [:instruction (instruction)] (mapcat identity inputs)))]
        (is (= expected (reason-of result)))
        (is (contains? approval/refusal-reasons (reason-of result))
            (str (reason-of result) " is not in `refusal-reasons`"))))))

(deftest evaluate-refuses-inputs-it-cannot-decide-on
  (testing "a defect must not read as a denial"
    (is (thrown? Exception (approval/evaluate {:instruction (instruction) :actor {}})))
    (is (thrown? Exception (approval/evaluate {:instruction {} :actor (actor approver-id)})))
    (is (thrown? Exception (approval/evaluate {:instruction (instruction)
                                               :actor (actor approver-id)
                                               :decision :maybe})))))

;; ---------------------------------------------------------------------------
;; A-016 — the reason vocabulary and the answers to it are the same set
;; ---------------------------------------------------------------------------
;;
;; `clofin.payments.approval-service` says, verbatim, that "a reason added to
;; `clofin.authz.approval/refusal-reasons` without an answer here fails
;; `clofin.authz.approval-test`". Nothing compared the keys, and `refuse!` has
;; `(or (refusal-status reason) :forbidden)` and `(or (refusal-detail reason)
;; "This approval was refused")` fallbacks — so an unmapped reason would have
;; degraded silently to a generic `403` with generic prose, which is the
;; behaviour a caller cannot branch on and an auditor cannot explain.
;;
;; The docstring was the enforcement point, which is standing lesson **L-6**
;; exactly. These two assertions are the enforcement point now, and the
;; docstring is true.

(deftest a-016-every-refusal-reason-has-an-http-status
  (is (= (set approval/refusal-reasons)
         (set (keys approval-service/refusal-status)))
      "a reason with no status falls back to a generic 403; a status for a reason
       `evaluate` cannot return is an answer to a question nobody asks"))

(deftest a-016-every-refusal-reason-has-prose-that-names-its-control
  (is (= (set approval/refusal-reasons)
         (set (keys @#'approval-service/refusal-detail)))
      "a reason with no detail falls back to \"This approval was refused\", which
       tells an operator nothing they can act on or escalate")
  (doseq [[reason detail] @#'approval-service/refusal-detail]
    (is (not (str/blank? detail))
        (str reason " must say something"))))
