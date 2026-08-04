(ns clofin.ledger.service-test
  "The ledger's audited writes and the transactions they live in
  (TASK-005, AC-2 and AC-3).

  These are the I9 pairs for `account.created` and `journal-entry.posted`, in
  the shape `clofin.authz.repository-test` established for payments: a committed
  change leaves **exactly one** event, a rolled-back one leaves **none**.
  Together they say that an unaudited account opening or journal posting is not
  representable — not that the code currently remembers to write an event, but
  that it cannot write one without the change surviving too.

  Posting carries a third case that the other two do not, and it is the one
  worth the file: **the rollback where the database refuses.** The zero-sum and
  completeness guards on a journal entry are `deferrable initially deferred`, so
  they fire at `commit` — after the service has returned and after the audit
  event has been written. Nothing in the application code sees that failure, and
  the event still must not survive it. That is precisely why
  `clofin.audit.repository/record!` cannot open a connection of its own.

  PostgreSQL rather than a substitute: a substitute has no deferred constraint
  and no transaction to roll back, and would be asserting that CloFin's code
  agrees with itself."
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.db.core :as db]
            [clofin.ledger.repository :as ledger]
            [clofin.ledger.service :as service]
            [clofin.money :as money]
            [clofin.test-db :as tdb]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time Instant]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private narrative "Client funds received from Pacific Rim Logistics")

(defn- setup
  "An organisation, an actor to act as, and two accounts to move money between."
  []
  (let [org (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)})
        actor (tdb/insert-actor! tdb/*pool* {:organisation-id org
                                             :display-name "Controller"
                                             :roles [:controller]})]
    {:org org
     :actor actor
     :cash (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                            :code "1100-CLIENT-FUNDS" :type "asset"})
     :payable (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                               :code "2100-CLIENT-PAYABLE" :type "liability"})}))

(defn- account-candidate
  [org & {:keys [code] :or {code "1200-OPERATING"}}]
  {:id (random-uuid) :organisation-id org :code code :name "Operating account"
   :type :asset :currency "SGD" :status :active})

(defn- entry-candidate
  [{:keys [org cash payable]} & {:keys [amount-minor] :or {amount-minor 125000}}]
  {:id (random-uuid)
   :organisation-id org
   :occurred-at (Instant/parse "2026-08-04T09:00:00Z")
   :narrative narrative
   :reference {:type :opening-balance :id (random-uuid)}
   :lines [{:account-id cash    :direction :debit  :amount (money/of "SGD" amount-minor)}
           {:account-id payable :direction :credit :amount (money/of "SGD" amount-minor)}]})

(defn- audit-count
  ([] (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event"])))
  ([subject-id] (:count (db/query-one tdb/*pool*
                                      ["select count(*) as count from audit_event where subject_id = ?"
                                       subject-id]))))

(defn- entry-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from journal_entry"])))

;; ---------------------------------------------------------------------------
;; AC-2 — opening an account
;; ---------------------------------------------------------------------------

(deftest ac-2-a-committed-account-opening-leaves-exactly-one-event
  (let [{:keys [org actor]} (setup)
        before (audit-count)
        acct (db/with-transaction [tx tdb/*pool*]
               (service/create-account! tx {:account (account-candidate org)
                                            :actor-id actor
                                            :correlation-id "corr-ac-2"}))]
    (is (= 1 (audit-count (:id acct))) "exactly one, not zero and not two")
    (is (= (inc before) (audit-count)) "and nothing else was recorded on the way")

    (let [ev (first (audit-store/events-for-subject tdb/*pool* org (:id acct)))]
      (testing "carrying actor, action, subject and correlation id (PR-072)"
        (is (= actor (:actor-id ev)))
        (is (= "account.created" (:action ev)))
        (is (= "account" (:subject-type ev)))
        (is (= (:id acct) (:subject-id ev)))
        (is (= org (:organisation-id ev)))
        (is (= "corr-ac-2" (:correlation-id ev)))
        (is (instance? Instant (:occurred-at ev))))

      (testing "and digests computed by the existing canonicalisation (AC-4)"
        (is (nil? (:before-digest ev))
            "a creation has no before — that null is what distinguishes it from an update")
        (is (= (audit/digest (audit/account-subject acct)) (:after-digest ev)))))

    (testing "and the account committed with it"
      (is (= acct (ledger/find-account tdb/*pool* org (:id acct)))))))

(deftest ac-2-a-rolled-back-account-opening-leaves-no-event
  (let [{:keys [org actor]} (setup)
        before (audit-count)
        id (atom nil)
        t (try
            (db/with-transaction [tx tdb/*pool*]
              (reset! id (:id (service/create-account!
                               tx {:account (account-candidate org)
                                   :actor-id actor
                                   :correlation-id "corr-rollback"})))
              (throw (ex-info "deliberate rollback" {})))
            nil (catch Exception e e))]
    (is (some? t))
    (is (= before (audit-count)) "no audit event survives a rolled-back opening")
    (is (zero? (audit-count @id)))
    (is (nil? (ledger/find-account tdb/*pool* org @id)) "and neither does the account")))

(deftest a-refused-account-opening-records-nothing
  (testing "the repository raises before the audit write is reached, so a 409 leaves no trace"
    (let [{:keys [org actor]} (setup)
          before (audit-count)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (service/create-account! tx {:account (account-candidate org :code "1100-CLIENT-FUNDS")
                                             :actor-id actor
                                             :correlation-id "corr-duplicate"}))
              nil (catch Exception e e))]
      (is (some? t) "a code already used in this organisation")
      (is (= before (audit-count))))))

;; ---------------------------------------------------------------------------
;; AC-3 — posting a journal entry
;; ---------------------------------------------------------------------------

(deftest ac-3-a-committed-posting-leaves-exactly-one-event
  (let [{:keys [org actor] :as f} (setup)
        candidate (entry-candidate f)
        before (audit-count)
        posted (db/with-transaction [tx tdb/*pool*]
                 (service/post-entry! tx {:entry candidate
                                          :actor-id actor
                                          :correlation-id "corr-ac-3"}))]
    (is (= 1 (audit-count (:id posted))) "exactly one, not one per line and not zero")
    (is (= (inc before) (audit-count)))

    (let [ev (first (audit-store/events-for-subject tdb/*pool* org (:id posted)))]
      (testing "carrying actor, action, subject and correlation id (PR-072)"
        (is (= actor (:actor-id ev)))
        (is (= "journal-entry.posted" (:action ev)))
        (is (= "journal-entry" (:subject-type ev)))
        (is (= (:id posted) (:subject-id ev)))
        (is (= org (:organisation-id ev)))
        (is (= "corr-ac-3" (:correlation-id ev))))

      (testing "and digests computed by the existing canonicalisation (AC-4)"
        (is (nil? (:before-digest ev))
            "a posted entry is never amended (C-03), so there is no before and never will be")
        (is (= (audit/digest (audit/journal-entry-subject posted)) (:after-digest ev)))
        (testing "over the entry as read back from the database, not only as posted"
          (is (= (:after-digest ev)
                 (audit/digest (audit/journal-entry-subject
                                (ledger/find-entry tdb/*pool* org (:id posted)))))
              "`recorded_at` is outside the projection precisely so these two agree"))))

    (testing "and the entry committed with it"
      (is (= 1 (entry-count))))))

(deftest ac-3-a-rolled-back-posting-leaves-no-event
  (let [{:keys [org actor] :as f} (setup)
        before (audit-count)
        id (atom nil)
        t (try
            (db/with-transaction [tx tdb/*pool*]
              (reset! id (:id (service/post-entry! tx {:entry (entry-candidate f)
                                                       :actor-id actor
                                                       :correlation-id "corr-rollback"})))
              (throw (ex-info "deliberate rollback" {})))
            nil (catch Exception e e))]
    (is (some? t))
    (is (= before (audit-count)) "no audit event survives a rolled-back posting")
    (is (zero? (audit-count @id)))
    (is (zero? (entry-count)) "and neither does the entry")
    (is (nil? (ledger/find-entry tdb/*pool* org @id)))))

(deftest ac-3-the-pair-holds-when-the-rollback-is-the-database-refusing
  (testing "the zero-sum guard is deferred, so it fires at commit — after the service returned
            and after the event was written. The event is inside that transaction, so it goes too."
    (let [{:keys [org actor cash] :as f} (setup)
          before (audit-count)
          id (atom nil)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (let [posted (service/post-entry! tx {:entry (entry-candidate f)
                                                      :actor-id actor
                                                      :correlation-id "corr-refused"})]
                  (reset! id (:id posted))
                  ;; A third line that unbalances the very entry the audit event
                  ;; describes. `journal_entry_must_balance` is deferred, so this
                  ;; insert succeeds and the *commit* is what fails — no
                  ;; application code raises, and nothing catches.
                  (tdb/insert-line! tx {:entry-id (:id posted) :line-no 3 :account-id cash
                                        :direction "debit" :amount-minor 1 :currency "SGD"})))
              nil (catch Exception e e))]
      (is (some? t) "the commit was refused")
      (is (re-find #"balance" (str/lower-case (str (ex-message t) (some-> t ex-cause ex-message))))
          "and refused by the zero-sum guard, not by something incidental")
      (is (= before (audit-count)) "no audit event survives a posting the database refused")
      (is (zero? (audit-count @id)))
      (is (zero? (entry-count))))))

(deftest ac-3-the-pair-holds-when-the-database-refuses-a-different-row
  (testing "the transaction is the unit: a deferred constraint firing on anything inside it
            takes the audit event with it, whether or not the event's own subject was at fault"
    (let [{:keys [org actor] :as f} (setup)
          before (audit-count)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (service/post-entry! tx {:entry (entry-candidate f)
                                         :actor-id actor
                                         :correlation-id "corr-incomplete"})
                ;; A second entry with no lines at all. `journal_entry_must_be_complete`
                ;; (migration `0008`, audit finding F-003) refuses it at commit.
                (tdb/insert-entry! tx {:id (random-uuid) :organisation-id org}))
              nil (catch Exception e e))]
      (is (some? t) "the commit was refused")
      (is (= before (audit-count)))
      (is (zero? (entry-count))))))

;; ---------------------------------------------------------------------------
;; AC-4 — digests, not payloads
;; ---------------------------------------------------------------------------

(deftest ac-4-the-audit-row-carries-no-payload
  (testing "ADR-0016, C-09: the narrative is in the digest input and must not reach the table"
    (let [{:keys [org actor] :as f} (setup)
          posted (db/with-transaction [tx tdb/*pool*]
                   (service/post-entry! tx {:entry (entry-candidate f)
                                            :actor-id actor
                                            :correlation-id "corr-payload"}))
          row (db/query-one tdb/*pool*
                            ["select id::text, organisation_id::text, actor_id::text, action,
                                     subject_type, subject_id::text, before_digest, after_digest,
                                     correlation_id
                                from audit_event where subject_id = ?"
                             (:id posted)])
          rendered (str/lower-case (pr-str row))]
      (is (not (str/includes? rendered (str/lower-case narrative))))
      (is (not (str/includes? rendered "pacific rim")))
      (is (not (str/includes? rendered "125000"))
          "nor the amount the entry moved — an audit row is a digest, not a second ledger"))))
