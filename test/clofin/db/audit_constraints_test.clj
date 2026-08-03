(ns clofin.db.audit-constraints-test
  "The audit trail is append-only, and these tests prove it by **bypassing the
  application entirely** and issuing SQL directly — which is exactly what a
  defect, a migration script or a maintenance session would do.

  This is AC-11 from docs/briefs/003-TASK-authorisation-and-audit-trail.md, and
  it is written the same way `clofin.db.ledger-constraints-test` proves C-03:
  a control that only holds when you go through the front door is not a
  control. `audit_event_append_only` and `approval_no_delete` reuse the
  `reject_mutation()` function migration 0002 introduced, so an auditor reading
  the schema finds one pattern rather than three.

  See docs/ADR/0006-postgresql-as-system-of-record.md."
  (:require [clofin.db.core :as db]
            [clofin.test-db :as tdb]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(defn- fixture []
  (let [org (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)})
        actor (tdb/insert-actor! tdb/*pool* {:organisation-id org :roles [:approver]})]
    {:org org :actor actor}))

(defn- caught [f] (try (f) nil (catch Exception e e)))

;; ---------------------------------------------------------------------------
;; AC-11 — audit_event cannot be altered
;; ---------------------------------------------------------------------------

(deftest ac-11-a-committed-audit-event-cannot-be-updated
  (testing "the database refuses, even though the application layer was bypassed"
    (let [{:keys [org actor]} (fixture)
          id (tdb/insert-audit-event! tdb/*pool* {:organisation-id org :actor-id actor
                                                  :action "payment.approved"})
          t (caught #(db/execute! tdb/*pool*
                                  ["update audit_event set action = 'payment.created' where id = ?" id]))]
      (is (some? t) "an audit event must not be updatable")
      (is (re-find #"append-only" (.getMessage ^Exception t)))
      (is (= "payment.approved"
             (:action (db/query-one tdb/*pool* ["select action from audit_event where id = ?" id])))
          "the row is unchanged"))))

(deftest ac-11-a-committed-audit-event-cannot-be-deleted
  (let [{:keys [org actor]} (fixture)
        id (tdb/insert-audit-event! tdb/*pool* {:organisation-id org :actor-id actor})
        t (caught #(db/execute! tdb/*pool* ["delete from audit_event where id = ?" id]))]
    (is (some? t) "an audit event must not be deletable")
    (is (re-find #"append-only" (.getMessage ^Exception t)))
    (is (= 1 (:count (db/query-one tdb/*pool*
                                   ["select count(*) as count from audit_event where id = ?" id]))))))

(deftest ac-11-a-bulk-delete-of-the-whole-trail-is-refused
  (testing "the trigger is `for each row`, so 'delete everything' fails on the first row rather than succeeding at scale"
    (let [{:keys [org actor]} (fixture)]
      (dotimes [_ 3] (tdb/insert-audit-event! tdb/*pool* {:organisation-id org :actor-id actor}))
      (is (some? (caught #(db/execute! tdb/*pool* ["delete from audit_event"]))))
      (is (= 3 (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event"])))))))

(deftest ac-11-even-a-no-op-update-is-refused
  (testing "the trigger fires before the row is compared, so there is no 'harmless' update"
    (let [{:keys [org actor]} (fixture)
          id (tdb/insert-audit-event! tdb/*pool* {:organisation-id org :actor-id actor})]
      (is (some? (caught #(db/execute! tdb/*pool*
                                       ["update audit_event set action = action where id = ?" id])))))))

;; ---------------------------------------------------------------------------
;; approval — UPDATE permitted, DELETE not. The asymmetry is deliberate.
;; ---------------------------------------------------------------------------

(deftest an-approval-cannot-be-deleted
  (testing "a decision that can disappear is a decision nobody can prove was taken"
    (let [{:keys [org actor]} (fixture)
          account (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                   :code "1100-CLIENT-FUNDS"})
          instruction (random-uuid)]
      (db/execute! tdb/*pool*
                   ["insert into payment_instruction
                       (id, organisation_id, debtor_account_id, creditor_name, creditor_account,
                        amount_minor, currency, value_date, purpose_code, status, created_by)
                     values (?, ?, ?, 'Pacific Rim Logistics Pte Ltd', 'SG-SYNTH-88012345',
                             125000, 'SGD', '2026-12-01', 'SUPP', 'pending-approval', ?)"
                    instruction org account actor])
      (let [approval (tdb/insert-approval! tdb/*pool* {:instruction-id instruction :actor-id actor})
            t (caught #(db/execute! tdb/*pool* ["delete from approval where id = ?" approval]))]
        (is (some? t))
        (is (re-find #"append-only" (.getMessage ^Exception t)))

        (testing "but it CAN be invalidated — that asymmetry is what PR-014 needs"
          (is (= 1 (db/execute! tdb/*pool*
                                ["update approval set invalidated_at = now() where id = ?" approval])))
          (is (some? (:invalidated-at (db/query-one tdb/*pool*
                                                    ["select invalidated_at from approval where id = ?"
                                                     approval])))))))))

;; ---------------------------------------------------------------------------
;; The constraints the schema carries in its own right
;; ---------------------------------------------------------------------------

(deftest a-rejection-without-a-reason-is-refused-by-the-database
  (testing "PR-013 enforced twice: the domain produces the 422, the schema makes it unrepresentable"
    (let [{:keys [org actor]} (fixture)
          account (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                   :code "1100-CLIENT-FUNDS"})
          instruction (random-uuid)]
      (db/execute! tdb/*pool*
                   ["insert into payment_instruction
                       (id, organisation_id, debtor_account_id, creditor_name, creditor_account,
                        amount_minor, currency, value_date, purpose_code, status, created_by)
                     values (?, ?, ?, 'Pacific Rim Logistics Pte Ltd', 'SG-SYNTH-88012345',
                             125000, 'SGD', '2026-12-01', 'SUPP', 'pending-approval', ?)"
                    instruction org account actor])
      (doseq [blank [nil "" "   "]]
        (is (some? (caught #(tdb/insert-approval! tdb/*pool*
                                                  {:instruction-id instruction :actor-id actor
                                                   :decision "rejected" :reason blank})))
            (str "a rejection with reason " (pr-str blank) " must not commit"))))))

(deftest one-live-decision-per-actor-per-instruction
  (let [{:keys [org actor]} (fixture)
        account (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                 :code "1100-CLIENT-FUNDS"})
        instruction (random-uuid)]
    (db/execute! tdb/*pool*
                 ["insert into payment_instruction
                     (id, organisation_id, debtor_account_id, creditor_name, creditor_account,
                      amount_minor, currency, value_date, purpose_code, status, created_by)
                   values (?, ?, ?, 'Pacific Rim Logistics Pte Ltd', 'SG-SYNTH-88012345',
                           125000, 'SGD', '2026-12-01', 'SUPP', 'pending-approval', ?)"
                  instruction org account actor])
    (let [first-id (tdb/insert-approval! tdb/*pool* {:instruction-id instruction :actor-id actor})]
      (is (some? (caught #(tdb/insert-approval! tdb/*pool*
                                                {:instruction-id instruction :actor-id actor})))
          "a second live decision by the same actor must not commit")

      (testing "and after the first is invalidated, the actor may decide again"
        (db/execute! tdb/*pool* ["update approval set invalidated_at = now() where id = ?" first-id])
        (is (some? (tdb/insert-approval! tdb/*pool*
                                         {:instruction-id instruction :actor-id actor})))))))

(deftest an-actor-must-belong-to-an-organisation-that-exists
  (is (some? (caught #(tdb/insert-actor! tdb/*pool* {:organisation-id (random-uuid)})))))

(deftest an-unknown-role-cannot-be-granted
  (testing "there is no `superuser` role, and the schema refuses to invent one"
    (let [{:keys [org]} (fixture)
          actor (tdb/insert-actor! tdb/*pool* {:organisation-id org})]
      (doseq [role ["superuser" "admin" "root" ""]]
        (is (some? (caught #(db/execute! tdb/*pool*
                                         ["insert into actor_role (actor_id, role) values (?, ?)"
                                          actor role])))
            (str "role " (pr-str role) " must not be grantable"))))))

(deftest a-threshold-must-require-at-least-one-approval
  (let [{:keys [org]} (fixture)]
    (is (some? (caught #(tdb/insert-threshold! tdb/*pool*
                                               {:organisation-id org :approvals-required 0}))))))

(deftest an-approver-limit-must-be-positive
  (let [{:keys [org]} (fixture)
        actor (tdb/insert-actor! tdb/*pool* {:organisation-id org})]
    (is (some? (caught #(db/execute! tdb/*pool*
                                     ["insert into approver_limit (actor_id, currency, limit_minor)
                                       values (?, 'SGD', 0)" actor]))))))

(deftest objection-o-1-a-wildcard-currency-limit-cannot-be-stored
  (testing "the brief documents a null-currency row meaning 'every currency'; the primary key forbids it"
    (let [{:keys [org]} (fixture)
          actor (tdb/insert-actor! tdb/*pool* {:organisation-id org})
          t (caught #(db/execute! tdb/*pool*
                                  ["insert into approver_limit (actor_id, currency, limit_minor)
                                    values (?, null, 100000)" actor]))]
      (is (some? t)
          "PostgreSQL makes every primary key column NOT NULL, so approver_limit.currency
           cannot be null despite being declared so. This test records the defect rather
           than papering over it — see objection O-1 in the REQ. Delete it when the schema
           is corrected by a ruling, not before.")
      (is (re-find #"null value|not-null" (.getMessage ^Exception t))))))
