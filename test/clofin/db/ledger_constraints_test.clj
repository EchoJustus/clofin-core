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

(defn- reversal-of!
  "Insert a reversal entry pointing at `original`, with its two lines.

  Entries need at least two lines since migration `0008`, so a reversal — like
  any other entry — has to be written whole, in one transaction."
  [{:keys [org debit credit]} original]
  (let [id (random-uuid)]
    (db/with-transaction [tx tdb/*pool*]
      (db/execute! tx
                   ["insert into journal_entry (id, organisation_id, occurred_at, narrative,
                                                reference_type, reference_id, reverses_id)
                     values (?, ?, now(), 'Reversal', 'reversal', ?, ?)"
                    id org original original])
      ;; Mirrored directions: a reversal undoes the original's movement.
      (tdb/insert-line! tx {:entry-id id :line-no 1 :account-id credit
                            :direction "debit" :amount-minor 125000})
      (tdb/insert-line! tx {:entry-id id :line-no 2 :account-id debit
                            :direction "credit" :amount-minor 125000}))
    id))

(deftest an-entry-may-be-reversed-only-once
  (testing "a second reversal would silently reapply the original movement"
    (let [{:keys [org debit credit] :as accounts} (fixture-accounts)
          original (tdb/insert-balanced-entry! tdb/*pool*
                                               {:organisation-id org
                                                :debit-account-id debit
                                                :credit-account-id credit})]
      (reversal-of! accounts original)
      (is (thrown-with-msg?
           Exception #"journal_entry_reverses_key"
           (reversal-of! accounts original))
          "the partial unique index refuses the second reversal at INSERT, before
           any deferred check runs — so this still tests the index, not 0008"))))

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

;; ---------------------------------------------------------------------------
;; F-002 — TRUNCATE, the verb C-03 had never enumerated
;; ---------------------------------------------------------------------------
;;
;; `journal_entry_append_only` and `journal_line_append_only` have refused
;; `UPDATE` and `DELETE` since migration 0002, and the tests above prove it.
;; Neither covered `TRUNCATE`, which is a separate trigger event with a
;; separate privilege — so the ledger's immutability could be undone in one
;; statement (audit finding F-002, standing lesson L-5). Migration `0007`
;; closes it; these assert it stays closed.
;;
;; The exhaustive table × verb matrix lives in
;; `clofin.db.audit-constraints-test`. These are here because C-03 is the
;; ledger's control and its own test file should demonstrate it.

(deftest f-002-a-posted-entry-cannot-be-truncated-away
  (let [{:keys [org debit credit]} (fixture-accounts)
        entry-id (random-uuid)]
    (db/with-transaction [tx tdb/*pool*]
      (tdb/insert-entry! tx {:id entry-id :organisation-id org})
      (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                            :direction "debit" :amount-minor 125000})
      (tdb/insert-line! tx {:entry-id entry-id :line-no 2 :account-id credit
                            :direction "credit" :amount-minor 125000}))
    (doseq [table ["journal_entry" "journal_line"]]
      (let [t (try (db/execute! tdb/*pool* [(str "truncate " table " cascade")]) nil
                   (catch Exception e e))]
        (is (some? t) (str "truncate " table " must be refused"))
        (is (re-find #"append-only" (.getMessage ^Exception t)))
        (is (re-find #"never by truncate" (.getMessage ^Exception t)))))
    (is (= 2 (:count (db/query-one tdb/*pool*
                                   ["select count(*) as count from journal_line where entry_id = ?"
                                    entry-id])))
        "and the entry is still there")))

;; ---------------------------------------------------------------------------
;; F-003 — an entry with too few lines, which nothing used to refuse
;; ---------------------------------------------------------------------------
;;
;; ADR-0008 requires two or more lines and `clofin.ledger.entry/entry` enforces
;; it, but the database backstop did not: the zero-sum trigger from migration
;; `0002` is declared `after insert on journal_line`, so an entry with no lines
;; inserted no lines, queued no deferred check, and committed. The guard was
;; attached to the rows whose absence was the defect.
;;
;; Migration `0008` moves the check onto the entry. These bypass the domain
;; layer entirely — which is exactly what a migration, a maintenance action or
;; an application defect would do, and the only route by which the finding was
;; reachable.

(deftest f-003-an-entry-with-no-lines-cannot-be-committed
  (testing "the case the audit reproduced: one entry, zero lines, committed clean"
    (let [{:keys [org]} (fixture-accounts)
          entry-id (random-uuid)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (tdb/insert-entry! tx {:id entry-id :organisation-id org}))
              nil
              (catch Exception e e))]
      (is (some? t) "a zero-line entry must not commit")
      (is (re-find #"needs at least two" (.getMessage ^Exception t)))
      (is (zero? (:count (db/query-one tdb/*pool*
                                       ["select count(*) as count from journal_entry where id = ?"
                                        entry-id])))
          "and the whole transaction rolls back, leaving no orphan entry"))))

(deftest f-003-an-entry-with-one-line-cannot-be-committed
  (testing "a single line balances against nothing — it is half a double entry"
    (let [{:keys [org debit]} (fixture-accounts)
          entry-id (random-uuid)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (tdb/insert-entry! tx {:id entry-id :organisation-id org})
                (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                                      :direction "debit" :amount-minor 125000}))
              nil
              (catch Exception e e))]
      (is (some? t))
      ;; The message assertion is the load-bearing one here, and it is worth
      ;; saying why rather than leaving it to look cosmetic. A single line can
      ;; never balance — `amount_minor > 0` rules out a zero-amount line — so
      ;; the balance check would refuse this case anyway. Lower the cardinality
      ;; threshold from 2 to 1 and the entry is *still* refused, just for the
      ;; wrong reason and with a message that sends a reader looking for a
      ;; missing counter-line that was never going to be there. Verified by
      ;; mutating the live function: this assertion is the only one in the
      ;; suite that fails. Cardinality and balance are distinguishable in the
      ;; diagnosis long before they are distinguishable in the outcome.
      (is (re-find #"has 1 line\(s\)" (.getMessage ^Exception t))
          "the message names the count, so the defect is diagnosable from the log alone")
      (is (zero? (:count (db/query-one tdb/*pool*
                                       ["select count(*) as count from journal_entry where id = ?"
                                        entry-id])))))))

(deftest f-003-the-entry-level-guard-still-permits-a-transiently-incomplete-entry
  (testing "deferred, so an entry may be built up line by line inside one
            transaction — which `post-entry!` relies on. A non-deferred guard
            would have made every entry a single statement."
    (let [{:keys [org debit credit]} (fixture-accounts)
          entry-id (random-uuid)]
      (db/with-transaction [tx tdb/*pool*]
        (tdb/insert-entry! tx {:id entry-id :organisation-id org})
        ;; Zero lines here, and one line a moment later. Both illegal states,
        ;; both legal mid-transaction.
        (tdb/insert-line! tx {:entry-id entry-id :line-no 1 :account-id debit
                              :direction "debit" :amount-minor 125000})
        (tdb/insert-line! tx {:entry-id entry-id :line-no 2 :account-id credit
                              :direction "credit" :amount-minor 125000}))
      (is (= 2 (:count (db/query-one tdb/*pool*
                                     ["select count(*) as count from journal_line where entry_id = ?"
                                      entry-id])))))))

(deftest f-003-the-entry-level-guard-also-catches-an-imbalance
  (testing "it re-checks balance as well as cardinality — two guards over one
            invariant from two directions, and the message is deliberately
            identical to the line-level guard's so a caller cannot tell which
            one caught it"
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
      (is (some? t))
      (is (re-find #"does not balance" (.getMessage ^Exception t))
          "the wording the ledger has always used, whichever trigger reports it"))))

(deftest f-003-a-complete-entry-still-commits
  (testing "so a guard that refused everything would be caught"
    (let [{:keys [org debit credit]} (fixture-accounts)
          entry-id (tdb/insert-balanced-entry! tdb/*pool*
                                               {:organisation-id org
                                                :debit-account-id debit
                                                :credit-account-id credit})]
      (is (= 2 (:count (db/query-one tdb/*pool*
                                     ["select count(*) as count from journal_line where entry_id = ?"
                                      entry-id])))))))
