(ns clofin.ledger.entry-test
  "The zero-sum invariant is the single most important property in CloFin.
  It is asserted here as a universal claim over generated entries, not as a
  handful of worked examples."
  (:require [clofin.ledger.entry :as entry]
            [clofin.money :as money]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private org-id (random-uuid))
(def ^:private occurred-at #inst "2026-08-02T10:15:00.000-00:00")

(defn- account-id [] (random-uuid))

(def gen-currency (gen/elements ["SGD" "AUD" "JPY" "KWD" "USD"]))

(def gen-positive-units (gen/large-integer* {:min 1 :max 100000000}))

(def gen-balanced-lines
  "Generate a balanced entry directly: pick a set of debit amounts, then split
  the same total across a different number of credit lines. This exercises the
  case that matters — many-to-many postings, not just a simple two-line
  transfer."
  (gen/let [currency     gen-currency
            debit-units  (gen/vector gen-positive-units 1 5)
            credit-count (gen/choose 1 5)]
    (let [total        (money/of currency (reduce + debit-units))
          credit-parts (->> (money/allocate total (repeat credit-count 1))
                            (remove money/zero?))
          debits       (map #(hash-map :account-id (account-id)
                                       :direction :debit
                                       :amount (money/of currency %))
                            debit-units)
          credits      (map #(hash-map :account-id (account-id)
                                       :direction :credit
                                       :amount %)
                            credit-parts)]
      (vec (concat debits credits)))))

(defn- entry-of [lines]
  (entry/entry {:id (random-uuid)
                :organisation-id org-id
                :occurred-at occurred-at
                :narrative "Generated test entry"
                :reference {:type :payment-instruction :id (random-uuid)}
                :lines lines}))

;; ---------------------------------------------------------------------------
;; The invariant
;; ---------------------------------------------------------------------------

(defspec every-accepted-entry-balances 300
  (prop/for-all [lines gen-balanced-lines]
    (let [e (entry-of lines)]
      (and (entry/balanced? (:lines e))
           (empty? (entry/imbalance (:lines e)))))))

(defspec an-unbalanced-entry-is-always-rejected 300
  (prop/for-all [lines gen-balanced-lines
                 extra gen-positive-units]
    ;; Adding a lone debit to a balanced entry must always break it.
    (let [broken (conj lines {:account-id (account-id)
                              :direction :debit
                              :amount (money/of (:currency (:amount (first lines))) extra)})]
      (and (not (entry/balanced? broken))
           (try (entry-of broken) false
                (catch clojure.lang.ExceptionInfo e
                  (= :validation (:clofin/error (ex-data e)))))))))

(defspec reversing-an-entry-restores-every-account 200
  (prop/for-all [lines gen-balanced-lines]
    (let [original (entry-of lines)
          reversal (entry/reverse-entry original {:id (random-uuid)
                                                  :occurred-at occurred-at})
          ;; Net movement per account across both entries must be zero.
          net (reduce (fn [acc {:keys [account-id direction amount]}]
                        (update acc account-id (fnil + 0)
                                (if (= :debit direction)
                                  (:minor-units amount)
                                  (- (:minor-units amount)))))
                      {}
                      (concat (:lines original) (:lines reversal)))]
      (every? zero? (vals net)))))

;; ---------------------------------------------------------------------------
;; Worked examples
;; ---------------------------------------------------------------------------

(def ^:private client-funds (random-uuid))
(def ^:private clearing (random-uuid))
(def ^:private fee-income (random-uuid))

(deftest a-simple-transfer-balances
  (let [amount (money/of "SGD" 125000)
        e (entry-of (entry/transfer-lines {:from-account-id client-funds
                                           :to-account-id clearing
                                           :amount amount}))]
    (is (= 2 (count (:lines e))))
    (is (entry/balanced? (:lines e)))
    (is (= amount (entry/total e "SGD")))))

(deftest a-payment-with-a-fee-balances
  (testing "one debit against two credits — the ordinary shape once fees exist"
    (let [e (entry-of [{:account-id client-funds :direction :debit  :amount (money/of "SGD" 125500)}
                       {:account-id clearing     :direction :credit :amount (money/of "SGD" 125000)}
                       {:account-id fee-income   :direction :credit :amount (money/of "SGD" 500)}])]
      (is (entry/balanced? (:lines e)))
      (is (= (money/of "SGD" 125500) (entry/total e "SGD"))))))

(deftest imbalance-reports-what-is-missing
  (testing "the shortfall is returned per currency so a caller can report it"
    (let [gaps (entry/imbalance [{:account-id client-funds :direction :debit  :amount (money/of "SGD" 1000)}
                                 {:account-id clearing     :direction :credit :amount (money/of "SGD" 700)}])]
      (is (= {"SGD" (money/of "SGD" 300)} gaps)))))

(deftest a-multi-currency-entry-must-balance-in-each-currency
  (testing "an FX entry balancing overall but not per currency is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (entry-of [{:account-id client-funds :direction :debit  :amount (money/of "SGD" 1000)}
                            {:account-id clearing     :direction :credit :amount (money/of "USD" 1000)}]))))

  (testing "an entry balancing in each currency separately is accepted"
    (let [e (entry-of [{:account-id client-funds :direction :debit  :amount (money/of "SGD" 1000)}
                       {:account-id clearing     :direction :credit :amount (money/of "SGD" 1000)}
                       {:account-id client-funds :direction :debit  :amount (money/of "USD" 800)}
                       {:account-id clearing     :direction :credit :amount (money/of "USD" 800)}])]
      (is (entry/balanced? (:lines e)))
      (is (= #{"SGD" "USD"} (set (entry/currencies e)))))))

;; ---------------------------------------------------------------------------
;; Structural rules
;; ---------------------------------------------------------------------------

(deftest line-rules
  (testing "line amounts are strictly positive; direction carries the sign"
    (is (thrown? clojure.lang.ExceptionInfo
                 (entry/line {:account-id client-funds :direction :debit :amount (money/of "SGD" -100)})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (entry/line {:account-id client-funds :direction :debit :amount (money/of "SGD" 0)}))))

  (testing "direction must be a known direction"
    (is (thrown? clojure.lang.ExceptionInfo
                 (entry/line {:account-id client-funds :direction :in :amount (money/of "SGD" 100)}))))

  (testing "an account id is required"
    (is (thrown? clojure.lang.ExceptionInfo
                 (entry/line {:account-id "not-a-uuid" :direction :debit :amount (money/of "SGD" 100)})))))

(deftest entry-rules
  (let [valid {:id (random-uuid)
               :organisation-id org-id
               :occurred-at occurred-at
               :narrative "Supplier payment released"
               :reference {:type :payment-instruction :id (random-uuid)}
               :lines (entry/transfer-lines {:from-account-id client-funds
                                             :to-account-id clearing
                                             :amount (money/of "SGD" 100)})}]
    (testing "the happy path is accepted"
      (is (entry/entry valid)))

    (testing "an entry needs at least two lines"
      (is (thrown? clojure.lang.ExceptionInfo
                   (entry/entry (assoc valid :lines [(first (:lines valid))])))))

    (testing "an entry needs a narrative"
      (is (thrown? clojure.lang.ExceptionInfo (entry/entry (assoc valid :narrative "  ")))))

    (testing "every movement must be explainable by what caused it"
      (is (thrown? clojure.lang.ExceptionInfo (entry/entry (dissoc valid :reference))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (entry/entry (assoc valid :reference {:type :something-else :id (random-uuid)})))))

    (testing "an entry needs an occurrence time supplied by the caller"
      (is (thrown? clojure.lang.ExceptionInfo (entry/entry (dissoc valid :occurred-at)))))

    (testing "unknown keys are dropped rather than persisted"
      (is (nil? (:injected (entry/entry (assoc valid :injected "value"))))))))

(deftest reversal-rules
  (let [original (entry-of (entry/transfer-lines {:from-account-id client-funds
                                                  :to-account-id clearing
                                                  :amount (money/of "SGD" 100)}))]
    (testing "a reversal is a new entry referencing the original"
      (let [reversal (entry/reverse-entry original {:id (random-uuid) :occurred-at occurred-at})]
        (is (not= (:id original) (:id reversal)))
        (is (= {:type :reversal :id (:id original)} (:reference reversal)))
        (is (entry/balanced? (:lines reversal)))
        (is (= [:credit :debit] (mapv :direction (:lines reversal))))))

    (testing "a reversal may not reuse the original's id — history is never rewritten"
      (is (thrown? clojure.lang.ExceptionInfo
                   (entry/reverse-entry original {:id (:id original) :occurred-at occurred-at}))))))
