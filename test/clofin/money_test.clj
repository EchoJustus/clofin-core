(ns clofin.money-test
  "Property and example tests for the money value type.

  Properties carry most of the weight here. An example demonstrates that one
  case works; a property demonstrates that a class of cases works, and for
  arithmetic on money the second is the claim worth making."
  (:require [clofin.money :as money]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private currency-codes (vec (sort (keys money/currencies))))

(def gen-currency (gen/elements currency-codes))

(def gen-minor-units
  "Bounded well inside long range so that generated sums cannot overflow and
  turn a property failure into an arithmetic artefact."
  (gen/large-integer* {:min -1000000000000 :max 1000000000000}))

(def gen-money
  (gen/let [currency gen-currency
            units gen-minor-units]
    (money/of currency units)))

(defn gen-money-in [currency]
  (gen/fmap #(money/of currency %) gen-minor-units))

(def gen-same-currency-pair
  (gen/let [currency gen-currency
            a (gen-money-in currency)
            b (gen-money-in currency)]
    [a b]))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(deftest constructing-amounts
  (testing "a well-formed amount round-trips"
    (is (= {:currency "SGD" :minor-units 125000} (money/of "SGD" 125000))))

  (testing "an unknown currency is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (money/of "XYZ" 1))))

  (testing "money is never a float"
    (is (thrown? clojure.lang.ExceptionInfo (money/of "SGD" 12.5)))
    (is (thrown? clojure.lang.ExceptionInfo (money/of "SGD" 1250M))))

  (testing "an amount beyond long range is rejected as a domain error, not a cast failure"
    (let [t (try (money/of "SGD" (*' Long/MAX_VALUE 2)) (catch Exception e e))]
      (is (= :validation (:clofin/error (ex-data t))))))

  (testing "scale comes from the currency, not from an assumption of two places"
    (is (= 2 (money/scale "SGD")))
    (is (= 0 (money/scale "JPY")))
    (is (= 3 (money/scale "KWD")))))

;; ---------------------------------------------------------------------------
;; Arithmetic laws
;; ---------------------------------------------------------------------------

(defspec addition-is-commutative 200
  (prop/for-all [[a b] gen-same-currency-pair]
    (= (money/+ a b) (money/+ b a))))

(defspec addition-is-associative 200
  (prop/for-all [currency gen-currency]
    (let [g (gen-money-in currency)]
      (every? true?
              (map (fn [[a b c]] (= (money/+ (money/+ a b) c)
                                    (money/+ a (money/+ b c))))
                   (gen/sample (gen/tuple g g g) 25))))))

(defspec zero-is-the-additive-identity 200
  (prop/for-all [m gen-money]
    (= m (money/+ m (money/zero (:currency m))))))

(defspec subtracting-a-value-from-itself-is-zero 200
  (prop/for-all [m gen-money]
    (money/zero? (money/- m m))))

(defspec negation-is-an-involution 200
  (prop/for-all [m gen-money]
    (= m (money/negate (money/negate m)))))

(deftest cross-currency-arithmetic-is-an-error
  (testing "differing currencies never coerce — they raise"
    (is (thrown? clojure.lang.ExceptionInfo
                 (money/+ (money/of "SGD" 100) (money/of "USD" 100))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (money/- (money/of "SGD" 100) (money/of "JPY" 100))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (money/compare (money/of "SGD" 100) (money/of "AUD" 100)))))

  (testing "summing nothing is an error because there is no currency to return"
    (is (thrown? clojure.lang.ExceptionInfo (money/+)))
    (is (= (money/zero "SGD") (money/sum "SGD" [])))))

(deftest multiplication-requires-an-integer-factor
  (is (= (money/of "SGD" 300) (money/* (money/of "SGD" 100) 3)))
  (is (thrown? clojure.lang.ExceptionInfo (money/* (money/of "SGD" 100) 1.5))))

;; ---------------------------------------------------------------------------
;; Allocation
;; ---------------------------------------------------------------------------

(def gen-weights
  (gen/such-that #(pos? (reduce + %))
                 (gen/vector (gen/large-integer* {:min 0 :max 1000}) 1 10)
                 100))

(defspec allocation-preserves-the-total 300
  (prop/for-all [m gen-money
                 weights gen-weights]
    (let [parts (money/allocate m weights)]
      (and (= (count parts) (count weights))
           (= m (money/sum (:currency m) parts))))))

(defspec allocation-is-deterministic 100
  (prop/for-all [m gen-money
                 weights gen-weights]
    (= (money/allocate m weights) (money/allocate m weights))))

(deftest allocation-distributes-the-remainder
  (testing "SGD 100.00 split three ways loses nothing"
    (is (= [(money/of "SGD" 3334) (money/of "SGD" 3333) (money/of "SGD" 3333)]
           (money/allocate (money/of "SGD" 10000) [1 1 1]))))

  (testing "weights are respected"
    (is (= [(money/of "SGD" 7500) (money/of "SGD" 2500)]
           (money/allocate (money/of "SGD" 10000) [3 1]))))

  (testing "a negative amount allocates by magnitude and stays negative"
    (let [parts (money/allocate (money/of "SGD" -10000) [1 1 1])]
      (is (every? money/neg? parts))
      (is (= (money/of "SGD" -10000) (money/sum "SGD" parts)))))

  (testing "degenerate weights are rejected"
    (is (thrown? clojure.lang.ExceptionInfo (money/allocate (money/of "SGD" 100) [])))
    (is (thrown? clojure.lang.ExceptionInfo (money/allocate (money/of "SGD" 100) [0 0])))
    (is (thrown? clojure.lang.ExceptionInfo (money/allocate (money/of "SGD" 100) [-1 2])))))

;; ---------------------------------------------------------------------------
;; Parsing and formatting
;; ---------------------------------------------------------------------------

(defspec parse-and-format-round-trip 300
  (prop/for-all [m gen-money]
    (= m (money/parse (:currency m) (money/format-amount m)))))

(deftest formatting-respects-currency-scale
  (is (= "1250.00" (money/format-amount (money/of "SGD" 125000))))
  (is (= "125000"  (money/format-amount (money/of "JPY" 125000))))
  (is (= "125.000" (money/format-amount (money/of "KWD" 125000))))
  (is (= "-3.50"   (money/format-amount (money/of "SGD" -350)))))

(deftest parsing-rejects-excess-precision
  (testing "silently rounding a caller's amount is how money goes missing"
    (is (thrown? clojure.lang.ExceptionInfo (money/parse "SGD" "10.005")))
    (is (thrown? clojure.lang.ExceptionInfo (money/parse "JPY" "10.5"))))

  (testing "a fractional amount parses to its minor-unit count"
    (is (= (money/of "SGD" 1250)   (money/parse "SGD" "12.50")))
    (is (= (money/of "SGD" 125000) (money/parse "SGD" "1250.00")))
    (is (= (money/of "KWD" 125000) (money/parse "KWD" "125.000")))
    (is (= (money/of "SGD" -350)   (money/parse "SGD" "-3.50"))))

  (testing "fewer decimal places than the scale is fine"
    (is (= (money/of "SGD" 1000) (money/parse "SGD" "10")))
    (is (= (money/of "SGD" 1000) (money/parse "SGD" "10.0")))
    (is (= (money/of "JPY" 10)   (money/parse "JPY" "10"))))

  (testing "nonsense is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (money/parse "SGD" "ten dollars")))
    (is (thrown? clojure.lang.ExceptionInfo (money/parse "SGD" "")))))

;; ---------------------------------------------------------------------------
;; Wire representation
;; ---------------------------------------------------------------------------

(defspec wire-representation-round-trips 200
  (prop/for-all [m gen-money]
    (= m (money/wire-> (money/->wire m)))))

(deftest wire-representation-keeps-integers-integral
  (testing "minor units stay an integer so no consumer must parse a decimal"
    (is (= {"currency" "SGD" "minorUnits" 125000} (money/->wire (money/of "SGD" 125000)))))

  (testing "an incomplete wire amount is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (money/wire-> {"currency" "SGD"})))
    (is (thrown? clojure.lang.ExceptionInfo (money/wire-> {"minorUnits" 100})))))

;; ---------------------------------------------------------------------------
;; Comparison
;; ---------------------------------------------------------------------------

(deftest comparison
  (let [small (money/of "SGD" 100)
        large (money/of "SGD" 200)]
    (is (money/lt? small large))
    (is (money/gt? large small))
    (is (money/lte? small small))
    (is (money/gte? small small))
    (is (money/eq? small (money/of "SGD" 100)))))
