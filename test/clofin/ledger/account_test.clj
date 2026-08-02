(ns clofin.ledger.account-test
  (:require [clofin.ledger.account :as account]
            [clofin.money :as money]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private org-id (random-uuid))

(defn- account-of [type]
  {:id (random-uuid)
   :organisation-id org-id
   :code "1100-CLIENT-FUNDS"
   :name "Client funds — pooled"
   :type type
   :currency "SGD"
   :status :active})

(deftest validation
  (testing "a well-formed account is accepted unchanged"
    (let [candidate (account-of :asset)]
      (is (= candidate (account/account candidate)))))

  (testing "an account code is constrained because it appears in exports and statements"
    (is (thrown? clojure.lang.ExceptionInfo
                 (account/account (assoc (account-of :asset) :code "lower case"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (account/account (assoc (account-of :asset) :code "A"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (account/account (assoc (account-of :asset) :code "HAS SPACES")))))

  (testing "type, currency and status must all be known"
    (is (thrown? clojure.lang.ExceptionInfo
                 (account/account (assoc (account-of :asset) :type :suspense))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (account/account (assoc (account-of :asset) :currency "XYZ"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (account/account (assoc (account-of :asset) :status :dormant)))))

  (testing "unknown keys are dropped rather than persisted"
    (is (nil? (:injected (account/account (assoc (account-of :asset) :injected "value")))))))

(deftest postability
  (testing "only an active account accepts new postings; history stays readable"
    (is (account/postable? (account-of :asset)))
    (is (not (account/postable? (assoc (account-of :asset) :status :frozen))))
    (is (not (account/postable? (assoc (account-of :asset) :status :closed))))))

(deftest normal-balance-conventions
  (testing "assets and expenses are debit-normal; the rest are credit-normal"
    (is (= :debit  (account/normal-balance :asset)))
    (is (= :debit  (account/normal-balance :expense)))
    (is (= :credit (account/normal-balance :liability)))
    (is (= :credit (account/normal-balance :equity)))
    (is (= :credit (account/normal-balance :revenue))))

  (testing "an unknown type is an error rather than a silent default"
    (is (thrown? clojure.lang.ExceptionInfo (account/normal-balance :suspense)))))

;; ---------------------------------------------------------------------------
;; Balances
;; ---------------------------------------------------------------------------

(deftest balance-is-expressed-in-the-account-s-own-terms
  (let [postings [{:direction :debit  :amount (money/of "SGD" 100000)}
                  {:direction :credit :amount (money/of "SGD" 30000)}]]
    (testing "a debit-normal account rises on a debit"
      (is (= (money/of "SGD" 70000) (account/balance (account-of :asset) postings))))

    (testing "a credit-normal account falls on a debit"
      (is (= (money/of "SGD" -70000) (account/balance (account-of :liability) postings))))

    (testing "a positive liability balance means money is owed"
      (is (= (money/of "SGD" 30000)
             (account/balance (account-of :liability)
                              [{:direction :credit :amount (money/of "SGD" 30000)}]))))))

(deftest balance-of-nothing-is-zero
  (is (= (money/of "SGD" 0) (account/balance (account-of :asset) []))))

(deftest a-posting-in-another-currency-is-rejected
  (testing "an account holds one currency; a mismatch is a bug, not a conversion"
    (is (thrown? clojure.lang.ExceptionInfo
                 (account/balance (account-of :asset)
                                  [{:direction :debit :amount (money/of "USD" 100)}])))))

;; ---------------------------------------------------------------------------
;; Properties
;; ---------------------------------------------------------------------------

(def gen-posting
  (gen/let [direction (gen/elements [:debit :credit])
            units (gen/large-integer* {:min 1 :max 1000000000})]
    {:direction direction :amount (money/of "SGD" units)}))

(defspec debit-normal-and-credit-normal-balances-are-mirror-images 200
  (prop/for-all [postings (gen/vector gen-posting 0 20)]
    (= (money/negate (account/balance (account-of :asset) postings))
       (account/balance (account-of :liability) postings))))

(defspec balance-is-independent-of-posting-order 200
  (prop/for-all [postings (gen/vector gen-posting 0 20)]
    (= (account/balance (account-of :asset) postings)
       (account/balance (account-of :asset) (reverse postings)))))

(defspec a-posting-and-its-opposite-cancel 200
  (prop/for-all [posting gen-posting]
    (let [opposite (update posting :direction {:debit :credit :credit :debit})]
      (money/zero? (account/balance (account-of :asset) [posting opposite])))))
