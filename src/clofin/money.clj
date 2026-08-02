(ns clofin.money
  "Monetary amounts as integer minor units against an ISO 4217 currency.

  An amount is a plain map:

      {:currency \"SGD\" :minor-units 125000}   ; SGD 1,250.00
      {:currency \"JPY\" :minor-units 125000}   ; JPY 125,000
      {:currency \"KWD\" :minor-units 125000}   ; KWD 125.000

  Floating point is never used for money. The scale of a currency comes from
  the registry below, not from an assumption that every currency has two
  decimal places — roughly a third of the world's currencies do not.

  Arithmetic between different currencies is an error, never a coercion.

  See docs/ADR/0003-money-as-integer-minor-units.md."
  (:refer-clojure :exclude [zero? pos? neg? abs + - * compare])
  (:require [clofin.error :as err])
  (:import [java.math BigDecimal RoundingMode]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Currency registry
;; ---------------------------------------------------------------------------

(def currencies
  "Currencies CloFin can represent, with the number of decimal places defined by
  ISO 4217. `scale` is the exponent: the number of minor units in one major unit
  is 10^scale."
  {"AUD" {:scale 2 :name "Australian Dollar"}
   "BHD" {:scale 3 :name "Bahraini Dinar"}
   "CAD" {:scale 2 :name "Canadian Dollar"}
   "CHF" {:scale 2 :name "Swiss Franc"}
   "CNY" {:scale 2 :name "Yuan Renminbi"}
   "EUR" {:scale 2 :name "Euro"}
   "GBP" {:scale 2 :name "Pound Sterling"}
   "HKD" {:scale 2 :name "Hong Kong Dollar"}
   "IDR" {:scale 2 :name "Rupiah"}
   "INR" {:scale 2 :name "Indian Rupee"}
   "JPY" {:scale 0 :name "Yen"}
   "KRW" {:scale 0 :name "Won"}
   "KWD" {:scale 3 :name "Kuwaiti Dinar"}
   "MYR" {:scale 2 :name "Malaysian Ringgit"}
   "NZD" {:scale 2 :name "New Zealand Dollar"}
   "PHP" {:scale 2 :name "Philippine Peso"}
   "SGD" {:scale 2 :name "Singapore Dollar"}
   "THB" {:scale 2 :name "Baht"}
   "TND" {:scale 3 :name "Tunisian Dinar"}
   "USD" {:scale 2 :name "US Dollar"}
   "VND" {:scale 0 :name "Dong"}})

(defn supported?
  "True when `code` is a currency CloFin knows how to represent."
  [code]
  (contains? currencies code))

(defn scale
  "Decimal places for `code`. Throws for an unknown currency."
  [code]
  (or (get-in currencies [code :scale])
      (err/invalid! (str "Unsupported currency: " code)
                    {:currency code :supported (vec (sort (keys currencies)))})))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn money?
  "True when `x` is a well-formed amount."
  [x]
  (boolean (and (map? x)
                (string? (:currency x))
                (integer? (:minor-units x))
                (supported? (:currency x)))))

(defn of
  "Build an amount from a currency code and a signed count of minor units.

  Negative amounts are representable — a reversal, a fee credit and a debit
  balance all need them. Individual ledger lines separately require a positive
  amount; that rule belongs to the ledger, not to the value type."
  [currency minor-units]
  (when-not (string? currency)
    (err/invalid! "Currency must be a string" {:currency currency}))
  (when-not (integer? minor-units)
    (err/invalid! "Minor units must be an integer — money is never a float"
                  {:currency currency :minor-units minor-units}))
  (scale currency) ; validates the currency
  ;; Compared before coercion: `(long x)` on an out-of-range BigInt throws a
  ;; bare IllegalArgumentException, which is a defect rather than a domain error.
  (when-not (clojure.core/<= Long/MIN_VALUE minor-units Long/MAX_VALUE)
    (err/invalid! "Amount out of representable range" {:minor-units minor-units}))
  {:currency currency :minor-units (long minor-units)})

(defn zero
  "The zero amount in `currency`."
  [currency]
  (of currency 0))

(defn- same-currency!
  "Assert that every amount shares one currency, and return it."
  [amounts]
  (let [codes (into #{} (map :currency) amounts)]
    (when (clojure.core/> (count codes) 1)
      (err/invalid! "Cannot combine amounts in different currencies"
                    {:currencies (vec (sort codes))}))
    (first codes)))

;; ---------------------------------------------------------------------------
;; Predicates and comparison
;; ---------------------------------------------------------------------------

(defn zero? [m] (clojure.core/zero? (:minor-units m)))
(defn pos?  [m] (clojure.core/pos? (:minor-units m)))
(defn neg?  [m] (clojure.core/neg? (:minor-units m)))

(defn compare
  "Compare two amounts of the same currency, as `clojure.core/compare`."
  [a b]
  (same-currency! [a b])
  (clojure.core/compare (:minor-units a) (:minor-units b)))

(defn eq? [a b] (clojure.core/zero? (compare a b)))
(defn lt? [a b] (clojure.core/neg? (compare a b)))
(defn gt? [a b] (clojure.core/pos? (compare a b)))
(defn lte? [a b] (clojure.core/<= (compare a b) 0))
(defn gte? [a b] (clojure.core/>= (compare a b) 0))

;; ---------------------------------------------------------------------------
;; Arithmetic
;; ---------------------------------------------------------------------------

(defn +
  "Sum amounts. All must share a currency; summing nothing is an error because
  there would be no currency to return."
  [& amounts]
  (when (empty? amounts)
    (err/invalid! "Cannot sum an empty set of amounts — no currency to return"))
  (let [currency (same-currency! amounts)]
    (of currency (reduce (fn [acc m] (Math/addExact (long acc) (long (:minor-units m))))
                         0 amounts))))

(defn sum
  "Sum a collection of amounts, or return zero in `currency` when empty."
  [currency amounts]
  (if (seq amounts)
    (let [total (apply + amounts)]
      (when-not (= currency (:currency total))
        (err/invalid! "Amounts are not in the expected currency"
                      {:expected currency :actual (:currency total)}))
      total)
    (zero currency)))

(defn negate [m] (of (:currency m) (Math/negateExact (long (:minor-units m)))))

(defn -
  "Subtract subsequent amounts from the first. With one argument, negate."
  ([m] (negate m))
  ([a & more] (apply + a (map negate more))))

(defn abs [m] (of (:currency m) (Math/abs (long (:minor-units m)))))

(defn *
  "Multiply an amount by an integer factor. Non-integer scaling is deliberately
  absent: proportional splits go through `allocate`, and FX goes through an
  explicit, audited conversion."
  [m factor]
  (when-not (integer? factor)
    (err/invalid! "Money may only be multiplied by an integer factor"
                  {:factor factor}))
  (of (:currency m) (Math/multiplyExact (long (:minor-units m)) (long factor))))

(defn allocate
  "Split `m` across `weights` so that the parts always sum back to `m`.

  Naive division loses minor units: splitting SGD 100.00 three ways gives three
  parts of 33.33 and loses a cent. `allocate` distributes the remainder one
  minor unit at a time, largest fractional share first, then by position — so
  the result is deterministic and the sum is exact.

      (allocate (of \"SGD\" 10000) [1 1 1])
      ;=> [3334 3333 3333] minor units

  Weights must be non-negative integers and must not all be zero."
  [m weights]
  (when (empty? weights)
    (err/invalid! "Allocation requires at least one weight"))
  (when-not (every? #(and (integer? %) (clojure.core/>= % 0)) weights)
    (err/invalid! "Allocation weights must be non-negative integers" {:weights weights}))
  (let [total-weight (reduce clojure.core/+ 0 weights)]
    (when (clojure.core/zero? total-weight)
      (err/invalid! "Allocation weights must not sum to zero" {:weights weights}))
    (let [units      (long (:minor-units m))
          negative?  (clojure.core/neg? units)
          magnitude  (clojure.core/abs units)
          ;; Floor share plus the remainder each bucket is owed, so that
          ;; distribution order is decided by actual entitlement.
          shares     (map-indexed
                      (fn [idx w]
                        (let [numerator (clojure.core/* magnitude (long w))]
                          {:idx   idx
                           :base  (quot numerator total-weight)
                           :rem   (rem numerator total-weight)}))
                      weights)
          allocated  (reduce clojure.core/+ 0 (map :base shares))
          leftover   (clojure.core/- magnitude allocated)
          ;; Largest remainder first; ties broken by position for determinism.
          ranked     (->> shares
                          (sort-by (juxt (comp clojure.core/- :rem) :idx))
                          (map :idx)
                          (take leftover)
                          set)
          magnitudes (mapv (fn [{:keys [idx base]}]
                             (if (contains? ranked idx) (inc base) base))
                           shares)]
      (mapv #(of (:currency m) (if negative? (clojure.core/- %) %)) magnitudes))))

;; ---------------------------------------------------------------------------
;; Parsing, formatting and wire representation
;; ---------------------------------------------------------------------------

(defn ->decimal
  "Exact `BigDecimal` in major units. For display and reporting only — never
  feed the result back into arithmetic."
  ^BigDecimal [m]
  (.movePointLeft (BigDecimal/valueOf (long (:minor-units m))) (int (scale (:currency m)))))

(defn format-amount
  "Render an amount as a plain decimal string at the currency's scale, with no
  grouping separators and no currency symbol: \"1250.00\", \"125000\", \"-3.500\"."
  [m]
  (.toPlainString (->decimal m)))

(defn parse
  "Parse a decimal string in major units into an amount.

  The string must not carry more decimal places than the currency defines —
  silently rounding a user's input is how money goes missing."
  [currency ^String s]
  (let [target (scale currency)
        ^BigDecimal decimal (try
                              (BigDecimal. (.trim (str s)))
                              (catch NumberFormatException _
                                (err/invalid! "Amount is not a valid decimal number"
                                              {:currency currency :value s})))]
    (when (clojure.core/> (.scale decimal) (int target))
      (err/invalid! (str "Amount has more decimal places than " currency " allows")
                    {:currency currency :value s :max-decimal-places target}))
    ;; Normalise to the currency's scale, then take the *unscaled* value: that
    ;; integer is precisely the minor-unit count. Calling `longValueExact` on
    ;; the BigDecimal itself would instead demand a zero fractional part and
    ;; reject every amount with cents.
    (of currency (-> decimal
                     (.setScale (int target) RoundingMode/UNNECESSARY)
                     .unscaledValue
                     .longValueExact))))

(defn ->wire
  "JSON representation. Minor units stay an integer on the wire so that no
  consumer has to parse a decimal to get an exact value."
  [m]
  {"currency" (:currency m) "minorUnits" (:minor-units m)})

(defn wire->
  "Read an amount from a decoded JSON object, validating as it goes."
  [obj]
  (let [currency (or (get obj "currency") (get obj :currency))
        units    (or (get obj "minorUnits") (get obj :minorUnits) (get obj :minor-units))]
    (when-not (and currency units)
      (err/invalid! "Amount requires 'currency' and 'minorUnits'" {:value obj}))
    (of currency units)))
