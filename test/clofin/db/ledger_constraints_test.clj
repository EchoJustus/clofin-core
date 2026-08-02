(ns clofin.db.ledger-constraints-test
  "The ledger invariant is enforced in two places on purpose: in the domain
  constructor and in the database. These tests assert the *database* half, by
  bypassing the domain layer entirely and writing SQL directly — which is
  exactly what a defect, a migration script or a maintenance session would do.

  See docs/ADR/0006-postgresql-as-system-of-record.md."
  (:require [clofin.db.core :as db]
            [clofin.money :as money]
            [clofin.test-db :as tdb]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(defn- fixture-accounts []
  (let [org (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)})
        debit-account  (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                        :code "1100-CLIENT-FUNDS" :type "asset"})
        credit-account (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                        :code "2100-CLIENT-PAYABLE" :type "liability"})]
    {:org org :debit debit-account :credit credit-account}))

;; ---------------------------------------------------------------------------
;; The zero-sum invariant
;; ---------------------------------------------------------------------------

(deftest a-balanced-entry-commits
  (let [{:keys [org debit credit]} (fixture-accounts)
        entry-id (random-uuid)]
    (db/with-transaction [tx tdb/*pool*]
      (tdb/insert-entry! tx {:id entry-id :organisation-id org})
      (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                            :direction "debit" :amount-minor 125000})
      (tdb/insert-line! tx {:entry-id entry-id :line-no 2 :account-id credit
                            :direction "credit" :amount-minor 125000}))
    (is (= 2 (:count (db/query-one tdb/*pool*
                                   ["select count(*) as count from journal_line where entry_id = ?"
                                    entry-id]))))))

(deftest an-unbalanced-entry-cannot-be-committed
  (testing "the database rejects it even though the domain layer was bypassed"
    (let [{:keys [org debit credit]} (fixture-accounts)
          entry-id (random-uuid)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (tdb/insert-entry! tx {:id entry-id :organisation-id org})
                (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                                      :direction "debit" :amount-minor 125000})
                (tdb/insert-line! tx {:entry-id entry-id :line-no 2 :account-id credit
                                      :direction "credit" :amount-minor 100000}))
              nil
              (catch Exception e e))]
      (is (some? t) "an unbalanced entry must not commit")
      (is (re-find #"does not balance" (.getMessage ^Exception t)))
      (is (zero? (:count (db/query-one tdb/*pool*
                                       ["select count(*) as count from journal_entry where id = ?"
                                        entry-id])))
          "the whole transaction rolls back, leaving no partial entry"))))

(deftest the-constraint-is-deferred-so-lines-may-be-inserted-one-at-a-time
  (testing "an entry is transiently unbalanced mid-transaction and that is legal"
    (let [{:keys [org debit credit]} (fixture-accounts)
          entry-id (random-uuid)]
      (db/with-transaction [tx tdb/*pool*]
        (tdb/insert-entry! tx {:id entry-id :organisation-id org})
        ;; After this single insert the entry does not balance. A non-deferred
        ;; constraint would fail here, forcing every entry to be written as one
        ;; statement.
        (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                              :direction "debit" :amount-minor 500})
        (tdb/insert-line! tx {:entry-id entry-id :line-no 2 :account-id credit
                              :direction "credit" :amount-minor 500}))
      (is (= 2 (:count (db/query-one tdb/*pool*
                                     ["select count(*) as count from journal_line where entry_id = ?"
                                      entry-id])))))))

(deftest an-entry-must-balance-in-every-currency
  (testing "totals that net out across currencies do not balance"
    (let [{:keys [org debit credit]} (fixture-accounts)
          entry-id (random-uuid)]
      (is (thrown? Exception
                   (db/with-transaction [tx tdb/*pool*]
                     (tdb/insert-entry! tx {:id entry-id :organisation-id org})
                     (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                                           :direction "debit" :amount-minor 1000 :currency "SGD"})
                     (tdb/insert-line! tx {:entry-id entry-id :line-no 2 :account-id credit
                                           :direction "credit" :amount-minor 1000 :currency "USD"})))))))

;; ---------------------------------------------------------------------------
;; Append-only enforcement
;; ---------------------------------------------------------------------------

(deftest posted-entries-cannot-be-rewritten
  (let [{:keys [org debit credit]} (fixture-accounts)
        entry-id (random-uuid)]
    (db/with-transaction [tx tdb/*pool*]
      (tdb/insert-entry! tx {:id entry-id :organisation-id org :narrative "Original"})
      (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                            :direction "debit" :amount-minor 100})
      (tdb/insert-line! tx {:entry-id entry-id :line-no 2 :account-id credit
                            :direction "credit" :amount-minor 100}))

    (testing "a posted entry cannot be updated"
      (is (thrown-with-msg?
           Exception #"append-only"
           (db/execute! tdb/*pool* ["update journal_entry set narrative = ? where id = ?"
                                    "Rewritten" entry-id]))))

    (testing "a posted entry cannot be deleted"
      (is (thrown-with-msg?
           Exception #"append-only"
           (db/execute! tdb/*pool* ["delete from journal_entry where id = ?" entry-id]))))

    (testing "a posted line cannot be updated"
      (is (thrown-with-msg?
           Exception #"append-only"
           (db/execute! tdb/*pool* ["update journal_line set amount_minor = 1 where entry_id = ?"
                                    entry-id]))))

    (testing "the original survives every attempt"
      (is (= "Original" (:narrative (db/query-one tdb/*pool*
                                                  ["select narrative from journal_entry where id = ?"
                                                   entry-id])))))))

;; ---------------------------------------------------------------------------
;; Column-level constraints
;; ---------------------------------------------------------------------------

(deftest line-amounts-must-be-positive
  (testing "direction carries the sign, so a negative amount is a defect"
    (let [{:keys [org debit credit]} (fixture-accounts)
          entry-id (random-uuid)]
      (is (thrown-with-msg?
           Exception #"journal_line_amount_positive"
           (db/with-transaction [tx tdb/*pool*]
             (tdb/insert-entry! tx {:id entry-id :organisation-id org})
             (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                                   :direction "debit" :amount-minor -100})))))))

(deftest an-unknown-currency-cannot-be-persisted
  (let [{:keys [org debit credit]} (fixture-accounts)
        entry-id (random-uuid)]
    (is (thrown? Exception
                 (db/with-transaction [tx tdb/*pool*]
                   (tdb/insert-entry! tx {:id entry-id :organisation-id org})
                   (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                                         :direction "debit" :amount-minor 100 :currency "XYZ"}))))))

(deftest an-unknown-direction-cannot-be-persisted
  (let [{:keys [org debit]} (fixture-accounts)
        entry-id (random-uuid)]
    (is (thrown-with-msg?
         Exception #"journal_line_direction_known"
         (db/with-transaction [tx tdb/*pool*]
           (tdb/insert-entry! tx {:id entry-id :organisation-id org})
           (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                                 :direction "sideways" :amount-minor 100}))))))

(deftest an-entry-may-be-reversed-only-once
  (testing "a second reversal would silently reapply the original movement"
    (let [{:keys [org]} (fixture-accounts)
          original (random-uuid)]
      (tdb/insert-entry! tdb/*pool* {:id original :organisation-id org})
      (db/execute! tdb/*pool*
                   ["insert into journal_entry (id, organisation_id, occurred_at, narrative,
                                                reference_type, reference_id, reverses_id)
                     values (?, ?, now(), 'Reversal', 'reversal', ?, ?)"
                    (random-uuid) org original original])
      (is (thrown-with-msg?
           Exception #"journal_entry_reverses_key"
           (db/execute! tdb/*pool*
                        ["insert into journal_entry (id, organisation_id, occurred_at, narrative,
                                                     reference_type, reference_id, reverses_id)
                          values (?, ?, now(), 'Second reversal', 'reversal', ?, ?)"
                         (random-uuid) org original original]))))))

(deftest account-codes-are-unique-within-an-organisation
  (let [org (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)})]
    (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org :code "1100-CLIENT-FUNDS"})
    (is (thrown-with-msg?
         Exception #"ledger_account_org_code_key"
         (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                          :code "1100-CLIENT-FUNDS"})))))

(deftest the-currency-registry-matches-the-domain
  (testing "the database and clofin.money agree on every currency and its scale"
    (let [rows (db/query tdb/*pool* ["select code, scale from currency"])
          from-db (into {} (map (juxt :code #(int (:scale %)))) rows)
          from-domain (update-vals money/currencies :scale)]
      (is (= from-domain from-db)
          "a currency present in one and not the other is a latent production defect"))))
