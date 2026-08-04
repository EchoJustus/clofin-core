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

;; ---------------------------------------------------------------------------
;; The wildcard approver limit
;; ---------------------------------------------------------------------------
;;
;; These replace a test that pinned the *defect* — migration 0005 declared
;; `primary key (actor_id, currency)` while documenting the column as nullable,
;; and PostgreSQL forces primary-key columns NOT NULL, so the "applies to every
;; currency" row could not be stored at all. Raised as objection O-1, confirmed
;; as a brief defect, and corrected by migration 0006.
;;
;; The pure rule was always implemented and tested
;; (`clofin.authz.approval-test/a-wildcard-limit-applies-to-every-currency`).
;; What was missing, and what these assert, is that the rule survives *storage*:
;; a domain function that honours a row nobody can insert is a rule that does
;; not exist.

(deftest a-wildcard-currency-limit-can-be-stored
  (testing "O-1's fix: the row the column has always documented now inserts"
    (let [{:keys [org]} (fixture)
          actor (tdb/insert-actor! tdb/*pool* {:organisation-id org})]
      (is (= 1 (db/execute! tdb/*pool*
                            ["insert into approver_limit (actor_id, currency, limit_minor)
                              values (?, null, 100000)" actor])))
      (is (nil? (:currency (db/query-one tdb/*pool*
                                         ["select currency from approver_limit where actor_id = ?"
                                          actor])))
          "and it comes back as null rather than as a sentinel — see migration 0006
           for why `unique nulls not distinct` was chosen over a coalesce index"))))

(deftest an-actor-cannot-hold-two-wildcard-limits
  (testing "`nulls not distinct`: two contradictory 'every currency' ceilings would
            leave no rule for which wins, and a plain unique index would accept both"
    (let [{:keys [org]} (fixture)
          actor (tdb/insert-actor! tdb/*pool* {:organisation-id org})]
      (db/execute! tdb/*pool* ["insert into approver_limit (actor_id, currency, limit_minor)
                               values (?, null, 100000)" actor])
      (let [t (caught #(db/execute! tdb/*pool*
                                    ["insert into approver_limit (actor_id, currency, limit_minor)
                                      values (?, null, 999)" actor]))]
        (is (some? t) "a second wildcard row must be refused")
        (is (re-find #"approver_limit_key" (.getMessage ^Exception t)))))))

(deftest an-actor-cannot-hold-two-limits-in-one-currency
  (testing "the same constraint still does the per-currency job the primary key did"
    (let [{:keys [org]} (fixture)
          actor (tdb/insert-actor! tdb/*pool* {:organisation-id org})]
      (db/execute! tdb/*pool* ["insert into approver_limit (actor_id, currency, limit_minor)
                               values (?, 'SGD', 100000)" actor])
      (is (some? (caught #(db/execute! tdb/*pool*
                                       ["insert into approver_limit (actor_id, currency, limit_minor)
                                         values (?, 'SGD', 999)" actor])))))))

(deftest a-wildcard-and-a-currency-specific-limit-coexist
  (let [{:keys [org]} (fixture)
        actor (tdb/insert-actor! tdb/*pool* {:organisation-id org})]
    (db/execute! tdb/*pool* ["insert into approver_limit (actor_id, currency, limit_minor)
                             values (?, null, 100000)" actor])
    (db/execute! tdb/*pool* ["insert into approver_limit (actor_id, currency, limit_minor)
                             values (?, 'SGD', 500)" actor])
    (is (= 2 (:count (db/query-one tdb/*pool*
                                   ["select count(*) as count from approver_limit where actor_id = ?"
                                    actor]))))
    (testing "and two actors may each hold their own wildcard row"
      (let [other (tdb/insert-actor! tdb/*pool* {:organisation-id org})]
        (is (= 1 (db/execute! tdb/*pool*
                              ["insert into approver_limit (actor_id, currency, limit_minor)
                                values (?, null, 7)" other])))))))

;; ---------------------------------------------------------------------------
;; F-002 — the full destructive verb set, on every guarded table
;; ---------------------------------------------------------------------------
;;
;; Milestone 1's external audit found that append-only enforcement had been
;; specified and tested against `UPDATE` and `DELETE` only. `TRUNCATE` is a
;; distinct verb with its own trigger event and its own privilege, and it
;; emptied the audit table in one statement past a guard that had just refused
;; an `UPDATE` and a `DELETE` on the same row (standing lesson **L-5**).
;;
;; The lesson is not "add TRUNCATE". It is that a guarantee stated over a
;; partial verb set is a guarantee about the verbs somebody happened to think
;; of. So this is a matrix: every guarded table × every verb the engine offers,
;; enumerated rather than sampled — the same argument
;; `clofin.payments.state-test` makes for the lifecycle.

(def ^:private guarded-tables
  "Every table `reject_mutation()` protects, and which verbs it must refuse.

  `approval` permits `UPDATE` — that is how PR-014 invalidates an approval —
  and refuses the two verbs that destroy a decision. The asymmetry is
  deliberate and is commented in migration `0005`."
  [{:table "journal_entry" :refuses #{:update :delete :truncate}}
   {:table "journal_line"  :refuses #{:update :delete :truncate}}
   {:table "audit_event"   :refuses #{:update :delete :truncate}}
   {:table "approval"      :refuses #{:delete :truncate} :permits #{:update}}])

(defn- seeded-row!
  "One row in `table`, so that row-level triggers have something to fire on.

  A `DELETE` against an empty table succeeds trivially — nothing to refuse —
  so a verb probe with no row in place proves nothing."
  [table {:keys [org actor]}]
  (case table
    "audit_event"
    (tdb/insert-audit-event! tdb/*pool* {:organisation-id org :actor-id actor})

    ("journal_entry" "journal_line")
    (let [debit  (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                  :code (str "1100-A-" (rand-int 1000000))})
          credit (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                  :code (str "2100-B-" (rand-int 1000000))
                                                  :type "liability"})
          entry  (random-uuid)]
      (db/with-transaction [tx tdb/*pool*]
        (tdb/insert-entry! tx {:id entry :organisation-id org})
        (tdb/insert-line! tx {:entry-id entry :line-no 1 :account-id debit
                              :direction "debit" :amount-minor 1000})
        (tdb/insert-line! tx {:entry-id entry :line-no 2 :account-id credit
                              :direction "credit" :amount-minor 1000}))
      entry)

    "approval"
    (let [account (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                   :code (str "1100-C-" (rand-int 1000000))})
          instruction (random-uuid)]
      (db/execute! tdb/*pool*
                   ["insert into payment_instruction
                       (id, organisation_id, debtor_account_id, creditor_name, creditor_account,
                        amount_minor, currency, value_date, purpose_code, status, created_by)
                     values (?, ?, ?, 'Pacific Rim Logistics Pte Ltd', 'SG-SYNTH-88012345',
                             125000, 'SGD', '2026-12-01', 'SUPP', 'pending-approval', ?)"
                    instruction org account actor])
      (tdb/insert-approval! tdb/*pool* {:instruction-id instruction :actor-id actor}))))

(defn- attempt
  "Issue `verb` against `table` in raw SQL. Returns the exception, or nil."
  [table verb]
  (caught #(db/execute! tdb/*pool*
                        [(case verb
                           ;; `id` is the one column all four tables share. A
                           ;; no-op update still fires a BEFORE trigger — there
                           ;; is no "harmless" update.
                           :update   (str "update " table " set id = id")
                           :delete   (str "delete from " table)
                           :truncate (str "truncate " table " cascade"))])))

(deftest f-002-every-append-only-table-refuses-every-destructive-verb
  (testing "raw SQL, application bypassed — what a defect, a migration script or
            a maintenance session would actually do"
    ;; One organisation for the whole matrix: every probe below is expected to
    ;; be refused, so nothing it does can disturb a later row.
    (let [f (fixture)]
      (doseq [{:keys [table refuses permits]} guarded-tables]
        (seeded-row! table f)
        (doseq [verb refuses]
          (let [t (attempt table verb)]
            (is (some? t) (str table " must refuse " (name verb)))
            (is (re-find #"append-only" (.getMessage ^Exception t))
                (str table " + " (name verb) " refused for the wrong reason: "
                     (some-> t .getMessage)))
            (is (re-find (re-pattern (str "never by " (name verb))) (.getMessage ^Exception t))
                "the message names the verb that was attempted")))
        (doseq [verb permits]
          (is (nil? (attempt table verb))
              (str table " must still permit " (name verb)
                   " — `approval` is updated to set invalidated_at (PR-014)")))))))

(deftest f-002-truncate-cannot-be-laundered-through-an-unguarded-parent
  (testing "`TRUNCATE ... CASCADE` on a table with no guard of its own reaches
            the guarded children by foreign key, and fires their triggers —
            so the guard cannot be sidestepped by aiming one level up"
    (let [f (fixture)]
      (seeded-row! "audit_event" f)
      (let [t (caught #(db/execute! tdb/*pool* ["truncate organisation cascade"]))]
        (is (some? t))
        (is (re-find #"append-only" (.getMessage ^Exception t)))))))

(deftest f-002-every-guard-is-armed-after-the-test-fixture-has-run
  (testing "`clean-business-data!` disarms the TRUNCATE guards to reset between
            tests — it is the schema-owner adversary COMPLIANCE §4 names — and
            must restore every one of them, in the state it found them.

            This assertion is what makes that non-regressable: a fixture that
            silently downgraded a guard would leave the suite green and the
            control weaker, which is F-002's own shape."
      ;; `with-clean-data` has already run for this test, so this observes the
      ;; database exactly as the fixture left it.
    (let [guards (db/query tdb/*pool*
                           ;; Scoped to `public`. Without it this counts any
                           ;; same-named table in a scratch schema someone left
                           ;; behind, and reports a phantom drift.
                           ["select c.relname as table_name, t.tgname as trigger_name,
                                    t.tgenabled::text as enabled
                               from pg_trigger t
                               join pg_class c on c.oid = t.tgrelid
                               join pg_namespace n on n.oid = c.relnamespace
                              where n.nspname = 'public'
                                and not t.tgisinternal
                                and c.relname in ('journal_entry','journal_line','audit_event','approval')
                              order by 1, 2"])]
      (is (= 10 (count guards))
          (str "expected 8 append-only guards plus the two deferred completeness "
               "triggers (journal_entry_must_be_complete, journal_entry_must_balance), found "
               (pr-str (mapv (juxt :table-name :trigger-name) guards))))
      (doseq [{:keys [table-name trigger-name enabled]} guards]
        (is (contains? #{"O" "A"} enabled)
            (str table-name "." trigger-name " is not armed (tgenabled=" enabled ")"))))))

(deftest f-002-the-residue-a-trigger-cannot-close
  (testing "COMPLIANCE §4 names this, and here it is demonstrated rather than
            merely described: a trigger is enforced by the table, and the
            table's owner decides what the table is. CloFin connects as that
            owner, so these guards bind the application and any defect in it —
            not an adversary holding the owner's credentials. The fix is the
            runtime role split named as debt, under which this test would stop
            passing, which is the point."
    (let [f (fixture)]
      (seeded-row! "audit_event" f)
      (let [emptied?
            ;; Deliberately rolled back: the demonstration must leave no trace,
            ;; and no other test may observe a disarmed guard.
            (caught #(db/with-transaction [tx tdb/*pool*]
                       (db/execute! tx ["alter table audit_event disable trigger audit_event_no_truncate"])
                       (db/execute! tx ["truncate audit_event cascade"])
                       (throw (ex-info "rollback: the residue is demonstrated, not performed" {}))))]
        (is (= "rollback: the residue is demonstrated, not performed" (ex-message emptied?))
            "the owner reached TRUNCATE — it was the deliberate abort that stopped it, not the guard"))
      (testing "and after the rollback the guard is armed again"
        (let [t (caught #(db/execute! tdb/*pool* ["truncate audit_event cascade"]))]
          (is (some? t))
          (is (re-find #"append-only" (.getMessage ^Exception t))))))))
