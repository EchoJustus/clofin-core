(ns clofin.authz.repository-test
  "Actors, thresholds, approvals and the audit write, against a real database.

  Two of these tests are the ones that must not be compromised.

  **AC-10** asserts that a rolled-back change leaves **no** audit event, and its
  twin asserts that a committed one leaves exactly one. Together they are the
  proof that an unaudited state change is not representable (C-05, PR-075,
  invariant I9) — not that the code currently remembers to write an event, but
  that it *cannot* write one without the change surviving too, and cannot let
  the change survive without the event.

  PostgreSQL rather than a substitute, because a substitute has no transaction
  to roll back and would be asserting that CloFin's code agrees with itself."
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.authz.approval :as approval]
            [clofin.authz.repository :as authz]
            [clofin.db.core :as db]
            [clofin.money :as money]
            [clofin.test-db :as tdb]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time Instant]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- setup
  "An organisation with a maker, two approvers and a one-approval band.

  Every right is granted explicitly. There is no `insert-superuser!` to reach
  for, deliberately — so this fixture doubles as a statement of what each role
  can do (C-08)."
  []
  (let [org (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)})
        account (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                 :code "1100-CLIENT-FUNDS"})
        maker (tdb/insert-actor! tdb/*pool* {:organisation-id org :display-name "Maker"
                                             :roles [:operator]})
        approver-a (tdb/insert-actor! tdb/*pool* {:organisation-id org :display-name "Checker A"
                                                  :roles [:approver] :limits {"SGD" 10000000}})
        approver-b (tdb/insert-actor! tdb/*pool* {:organisation-id org :display-name "Checker B"
                                                  :roles [:approver] :limits {"SGD" 10000000}})]
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "SGD"
                                       :from-minor 0 :approvals-required 1})
    {:org org :account account :maker maker :approver-a approver-a :approver-b approver-b}))

(defn- insert-instruction!
  [source {:keys [org account maker status amount-minor]
           :or {status "pending-approval" amount-minor 125000}}]
  (let [id (random-uuid)]
    (db/execute! source
                 ["insert into payment_instruction
                     (id, organisation_id, debtor_account_id, creditor_name, creditor_account,
                      amount_minor, currency, value_date, purpose_code, status, created_by)
                   values (?, ?, ?, 'Pacific Rim Logistics Pte Ltd', 'SG-SYNTH-88012345',
                           ?, 'SGD', '2026-12-01', 'SUPP', ?, ?)"
                  id org account amount-minor status maker])
    id))

(defn- audit-count
  ([] (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event"])))
  ([subject-id] (:count (db/query-one tdb/*pool*
                                      ["select count(*) as count from audit_event where subject_id = ?"
                                       subject-id]))))

;; ---------------------------------------------------------------------------
;; Actors
;; ---------------------------------------------------------------------------

(deftest an-actor-is-assembled-with-its-roles-and-limits
  (let [{:keys [org approver-a]} (setup)
        actor (authz/find-actor tdb/*pool* approver-a)]
    (is (= approver-a (:id actor)))
    (is (= org (:organisation-id actor)))
    (is (= :active (:status actor)))
    (is (= #{:approver} (:roles actor)))
    (is (= {"SGD" 10000000} (:limits actor)))))

(deftest an-unknown-actor-is-nil
  (is (nil? (authz/find-actor tdb/*pool* (random-uuid)))))

(deftest a-seeded-actor-starts-with-nothing
  (testing "default deny begins at creation, not at the first check"
    (let [{:keys [org]} (setup)
          id (:id (authz/create-actor! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                   :display-name "Nobody"}))
          actor (authz/find-actor tdb/*pool* id)]
      (is (empty? (:roles actor)))
      (is (empty? (:limits actor))))))

(deftest granting-a-role-twice-is-a-no-op
  (let [{:keys [org]} (setup)
        id (:id (authz/create-actor! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                 :display-name "Twice"}))]
    (authz/grant-role! tdb/*pool* id :approver)
    (authz/grant-role! tdb/*pool* id :approver)
    (is (= #{:approver} (:roles (authz/find-actor tdb/*pool* id))))))

;; ---------------------------------------------------------------------------
;; Thresholds
;; ---------------------------------------------------------------------------

(deftest thresholds-are-read-per-currency
  (let [{:keys [org]} (setup)]
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "SGD"
                                       :from-minor 100000 :approvals-required 2})
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "EUR"
                                       :from-minor 0 :approvals-required 3})
    (is (= [{:from-minor 0 :approvals-required 1}
            {:from-minor 100000 :approvals-required 2}]
           (authz/thresholds-for tdb/*pool* org "SGD")))
    (is (= [{:from-minor 0 :approvals-required 3}]
           (authz/thresholds-for tdb/*pool* org "EUR")))
    (testing "a currency with no bands returns nothing rather than a default"
      (is (empty? (authz/thresholds-for tdb/*pool* org "JPY"))))))

(deftest thresholds-do-not-leak-between-organisations
  (let [{:keys [org]} (setup)
        other (tdb/insert-organisation! tdb/*pool* {:id (random-uuid) :short-name "other"})]
    (is (empty? (authz/thresholds-for tdb/*pool* other "SGD")))
    (is (seq (authz/thresholds-for tdb/*pool* org "SGD")))))

;; ---------------------------------------------------------------------------
;; Approvals
;; ---------------------------------------------------------------------------

(deftest an-approval-is-stored-and-read-back
  (let [{:keys [org account maker approver-a] :as f} (setup)
        instruction (insert-instruction! tdb/*pool* (assoc f :org org :account account :maker maker))
        id (random-uuid)
        stored (authz/record-approval! tdb/*pool* {:id id :instruction-id instruction
                                                   :actor-id approver-a :decision :approved})]
    (is (= id (:id stored)))
    (is (instance? Instant (:decided-at stored)))
    (is (nil? (:invalidated-at stored)))
    (is (= [stored] (authz/approvals-for tdb/*pool* instruction)))))

(deftest invalidating-approvals-leaves-them-visible
  (testing "PR-014: an approval that was given and then invalidated is the history an investigation needs"
    (let [{:keys [org account maker approver-a approver-b] :as f} (setup)
          instruction (insert-instruction! tdb/*pool* f)]
      (authz/record-approval! tdb/*pool* {:id (random-uuid) :instruction-id instruction
                                          :actor-id approver-a :decision :approved})
      (authz/record-approval! tdb/*pool* {:id (random-uuid) :instruction-id instruction
                                          :actor-id approver-b :decision :approved})
      (is (= 2 (authz/invalidate-approvals-for! tdb/*pool* instruction)))
      (let [all (authz/approvals-for tdb/*pool* instruction)]
        (is (= 2 (count all)) "the rows are still there")
        (is (every? :invalidated-at all))
        (is (empty? (approval/live-approvals all)) "and none of them counts any more"))
      (testing "invalidating again changes nothing"
        (is (zero? (authz/invalidate-approvals-for! tdb/*pool* instruction)))))))

(deftest an-actor-cannot-hold-two-live-decisions
  (testing "the partial unique index is the guarantee, not the check in `evaluate`"
    (let [{:keys [approver-a] :as f} (setup)
          instruction (insert-instruction! tdb/*pool* f)]
      (authz/record-approval! tdb/*pool* {:id (random-uuid) :instruction-id instruction
                                          :actor-id approver-a :decision :approved})
      (let [t (try (authz/record-approval! tdb/*pool* {:id (random-uuid) :instruction-id instruction
                                                       :actor-id approver-a :decision :approved})
                   nil (catch Exception e e))]
        (is (some? t))
        (is (= :conflict (:clofin/error (ex-data t))))))))

(deftest approvals-for-many-instructions-come-back-keyed
  (let [{:keys [approver-a] :as f} (setup)
        a (insert-instruction! tdb/*pool* f)
        b (insert-instruction! tdb/*pool* f)]
    (authz/record-approval! tdb/*pool* {:id (random-uuid) :instruction-id a
                                        :actor-id approver-a :decision :approved})
    (let [by-id (authz/approvals-for-instructions tdb/*pool* [a b])]
      (is (= 1 (count (get by-id a))))
      (is (nil? (get by-id b))))
    (testing "and an empty list does not become a query with no filter"
      (is (= {} (authz/approvals-for-instructions tdb/*pool* []))))))

;; ---------------------------------------------------------------------------
;; AC-9 / AC-10 — the audit write and the transaction it lives in
;; ---------------------------------------------------------------------------

(deftest ac-9-a-committed-change-leaves-exactly-one-audit-event
  (let [{:keys [org approver-a] :as f} (setup)
        instruction (insert-instruction! tdb/*pool* f)]
    (db/with-transaction [tx tdb/*pool*]
      (db/execute! tx ["update payment_instruction set status = 'approved' where id = ?" instruction])
      (audit-store/record! tx {:organisation-id org
                               :actor-id        approver-a
                               :action          "payment.approved"
                               :subject-type    "payment-instruction"
                               :subject-id      instruction
                               :before          {:status "pending-approval"}
                               :after           {:status "approved"}
                               :correlation-id  "corr-ac-9"}))
    (is (= 1 (audit-count instruction)) "exactly one, not zero and not two")
    (let [ev (first (audit-store/events-for-subject tdb/*pool* org instruction))]
      (testing "carrying actor, action, subject and correlation id (PR-072)"
        (is (= approver-a (:actor-id ev)))
        (is (= "payment.approved" (:action ev)))
        (is (= "payment-instruction" (:subject-type ev)))
        (is (= instruction (:subject-id ev)))
        (is (= "corr-ac-9" (:correlation-id ev)))
        (is (instance? Instant (:occurred-at ev)))
        (is (not= (:before-digest ev) (:after-digest ev))))
      (testing "and the status change committed with it"
        (is (= "approved" (:status (db/query-one tdb/*pool*
                                                 ["select status from payment_instruction where id = ?"
                                                  instruction]))))))))

(deftest ac-10-a-rolled-back-change-leaves-no-audit-event
  (testing "C-05, PR-075: an unaudited state change is not representable, and neither is an unchanged audit event"
    (let [{:keys [org approver-a] :as f} (setup)
          instruction (insert-instruction! tdb/*pool* f)
          before (audit-count)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (db/execute! tx ["update payment_instruction set status = 'approved' where id = ?"
                                 instruction])
                (audit-store/record! tx {:organisation-id org
                                         :actor-id        approver-a
                                         :action          "payment.approved"
                                         :subject-type    "payment-instruction"
                                         :subject-id      instruction
                                         :before          {:status "pending-approval"}
                                         :after           {:status "approved"}
                                         :correlation-id  "corr-ac-10"})
                ;; Whatever goes wrong after the audit write goes wrong — a
                ;; constraint, a crash, a deliberate abort. The transaction is
                ;; the unit, so the event goes with it.
                (throw (ex-info "deliberate rollback" {})))
              nil (catch Exception e e))]
      (is (some? t))
      (is (= before (audit-count)) "no audit event survives a rolled-back change")
      (is (zero? (audit-count instruction)))
      (is (= "pending-approval"
             (:status (db/query-one tdb/*pool*
                                    ["select status from payment_instruction where id = ?" instruction])))
          "and neither does the change"))))

(deftest ac-10-the-pair-holds-when-the-failure-is-the-database-refusing
  (testing "not only a thrown exception: a constraint violation after the audit write takes it down too"
    (let [{:keys [org approver-a] :as f} (setup)
          instruction (insert-instruction! tdb/*pool* f)
          before (audit-count)]
      (try
        (db/with-transaction [tx tdb/*pool*]
          (audit-store/record! tx {:organisation-id org :actor-id approver-a
                                   :action "payment.approved"
                                   :subject-type "payment-instruction"
                                   :subject-id instruction
                                   :before nil :after {:status "approved"}
                                   :correlation-id "corr"})
          ;; A status the check constraint does not allow.
          (db/execute! tx ["update payment_instruction set status = 'teleported' where id = ?"
                           instruction]))
        (catch Exception _))
      (is (= before (audit-count))))))

;; ---------------------------------------------------------------------------
;; AC-12 — evidence extraction
;; ---------------------------------------------------------------------------

(deftest ac-12-an-evidence-pack-carries-every-state-change-in-order-with-its-actor
  (let [{:keys [org maker approver-a] :as f} (setup)
        instruction (insert-instruction! tdb/*pool* f)]
    (doseq [[actor action] [[maker "payment.created"]
                            [maker "payment.submitted"]
                            [approver-a "payment.approved"]]]
      (db/with-transaction [tx tdb/*pool*]
        (audit-store/record! tx {:organisation-id org :actor-id actor :action action
                                 :subject-type "payment-instruction" :subject-id instruction
                                 :before nil :after {:action action}
                                 :correlation-id (str "corr-" action)})))
    (let [pack (audit-store/evidence-pack tdb/*pool* org instruction)]
      (is (= 3 (count (:events pack))))
      (is (= ["payment.created" "payment.submitted" "payment.approved"]
             (mapv :action (:events pack)))
          "in the order they happened")
      (is (= [maker maker approver-a] (mapv :actor-id (:events pack)))
          "each carrying the actor who caused it")
      (is (false? (boolean (:truncated? pack))))
      (is (= "payment-instruction" (:subject-type pack))))))

(deftest an-evidence-pack-for-an-unknown-subject-is-nil-not-empty
  (testing "an empty pack reads as proof that nothing happened, which is worse than no pack"
    (let [{:keys [org]} (setup)]
      (is (nil? (audit-store/evidence-pack tdb/*pool* org (random-uuid)))))))

(deftest an-evidence-pack-does-not-cross-organisations
  (let [{:keys [org] :as f} (setup)
        other (tdb/insert-organisation! tdb/*pool* {:id (random-uuid) :short-name "other-org"})
        instruction (insert-instruction! tdb/*pool* f)]
    (db/with-transaction [tx tdb/*pool*]
      (audit-store/record! tx {:organisation-id org :actor-id nil :action "payment.created"
                               :subject-type "payment-instruction" :subject-id instruction
                               :before nil :after {} :correlation-id nil}))
    (is (nil? (audit-store/evidence-pack tdb/*pool* other instruction))
        "an unscoped evidence query is how one tenant reads another's payment history")))

;; ---------------------------------------------------------------------------
;; Listing and filtering
;; ---------------------------------------------------------------------------

(deftest audit-events-can-be-narrowed-by-action-subject-and-period
  (let [{:keys [org maker] :as f} (setup)
        a (insert-instruction! tdb/*pool* f)
        b (insert-instruction! tdb/*pool* f)]
    (doseq [[subject action] [[a "payment.created"] [a "payment.submitted"] [b "payment.created"]]]
      (db/with-transaction [tx tdb/*pool*]
        (audit-store/record! tx {:organisation-id org :actor-id maker :action action
                                 :subject-type "payment-instruction" :subject-id subject
                                 :before nil :after {} :correlation-id nil})))
    (is (= 3 (count (:events (audit-store/list-events tdb/*pool* org {})))))
    (is (= 2 (count (:events (audit-store/list-events tdb/*pool* org {:subject-id a})))))
    (is (= 2 (count (:events (audit-store/list-events tdb/*pool* org {:action "payment.created"})))))
    (is (= 1 (count (:events (audit-store/list-events tdb/*pool* org
                                                      {:subject-id a :action "payment.created"})))))
    (testing "the period is half-open, so consecutive extractions chain exactly"
      (is (= 3 (count (:events (audit-store/list-events
                                tdb/*pool* org {:from (Instant/parse "2000-01-01T00:00:00Z")})))))
      (is (zero? (count (:events (audit-store/list-events
                                  tdb/*pool* org {:to (Instant/parse "2000-01-01T00:00:00Z")}))))))
    (testing "an unknown action is refused rather than silently matching nothing"
      (is (thrown? Exception (audit-store/list-events tdb/*pool* org {:action "payment.exploded"}))))
    (testing "a period whose start is after its end is refused"
      (is (thrown? Exception (audit-store/list-events
                              tdb/*pool* org {:from (Instant/parse "2030-01-01T00:00:00Z")
                                              :to   (Instant/parse "2020-01-01T00:00:00Z")}))))))

(deftest audit-events-do-not-leak-between-organisations
  (let [{:keys [org maker] :as f} (setup)
        other (tdb/insert-organisation! tdb/*pool* {:id (random-uuid) :short-name "other-org-2"})
        instruction (insert-instruction! tdb/*pool* f)]
    (db/with-transaction [tx tdb/*pool*]
      (audit-store/record! tx {:organisation-id org :actor-id maker :action "payment.created"
                               :subject-type "payment-instruction" :subject-id instruction
                               :before nil :after {} :correlation-id nil}))
    (is (empty? (:events (audit-store/list-events tdb/*pool* other {}))))))

(deftest two-events-in-one-transaction-keep-a-stable-order
  (testing "`now()` is the transaction's start time, so ordering on the timestamp alone would let a history reorder itself"
    (let [{:keys [org maker] :as f} (setup)
          instruction (insert-instruction! tdb/*pool* f)]
      (db/with-transaction [tx tdb/*pool*]
        (audit-store/record! tx {:organisation-id org :actor-id maker :action "payment.created"
                                 :subject-type "payment-instruction" :subject-id instruction
                                 :before nil :after {} :correlation-id nil})
        (audit-store/record! tx {:organisation-id org :actor-id maker :action "payment.submitted"
                                 :subject-type "payment-instruction" :subject-id instruction
                                 :before nil :after {} :correlation-id nil}))
      (let [first-read (mapv :id (audit-store/events-for-subject tdb/*pool* org instruction))]
        (is (= 2 (count first-read)))
        (dotimes [_ 5]
          (is (= first-read (mapv :id (audit-store/events-for-subject tdb/*pool* org instruction)))
              "the same query over unchanged data must return the same order every time"))))))

(deftest record!-refuses-an-action-outside-the-vocabulary
  (let [{:keys [org] :as f} (setup)
        instruction (insert-instruction! tdb/*pool* f)]
    (is (thrown? Exception
                 (db/with-transaction [tx tdb/*pool*]
                   (audit-store/record! tx {:organisation-id org :actor-id nil
                                            :action "payment.quietly-adjusted"
                                            :subject-type "payment-instruction"
                                            :subject-id instruction
                                            :before nil :after {} :correlation-id nil}))))
    (is (zero? (audit-count instruction)))))

(deftest a-digest-written-here-matches-one-computed-purely
  (testing "so an auditor can verify a stored digest against a value they hold"
    (let [{:keys [org] :as f} (setup)
          instruction (insert-instruction! tdb/*pool* f)
          value {:id instruction :status :approved :amount (money/of "SGD" 125000)}]
      (db/with-transaction [tx tdb/*pool*]
        (audit-store/record! tx {:organisation-id org :actor-id nil :action "payment.approved"
                                 :subject-type "payment-instruction" :subject-id instruction
                                 :before nil :after value :correlation-id nil}))
      (is (= (audit/digest value)
             (:after-digest (first (audit-store/events-for-subject tdb/*pool* org instruction))))))))
