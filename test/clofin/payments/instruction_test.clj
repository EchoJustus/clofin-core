(ns clofin.payments.instruction-test
  "Validation, and specifically the property PR-003 asks for: a rejection names
  **every** failed field, not the first.

  That is why `field-errors` returns a map rather than throwing. A validator
  that throws can only ever report one failure, and an operator correcting a
  rejected instruction would need one round trip per mistake."
  (:require [clofin.money :as money]
            [clofin.payments.instruction :as instruction]
            [clofin.payments.state :as state]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time LocalDate]))

(def today (LocalDate/parse "2026-08-03"))
(def opts {:today today})

(defn- valid-candidate []
  {:organisation-id   (random-uuid)
   :debtor-account-id (random-uuid)
   :creditor-name     "Pacific Rim Logistics Pte Ltd"
   :creditor-account  "SG-SYNTH-88012345"
   :amount            (money/of "SGD" 125000)
   :value-date        (.plusDays today 7)
   :purpose-code      "SUPP"
   :status            :draft
   :created-by        (random-uuid)})

(defn- errors [overrides]
  (instruction/field-errors (merge (valid-candidate) overrides) opts))

(defn- caught [f] (try (f) nil (catch Exception t t)))

;; ---------------------------------------------------------------------------
;; AC-2 — every failed field, not the first
;; ---------------------------------------------------------------------------

(deftest ac-2-three-bad-fields-produce-three-messages
  (let [found (errors {:amount       (money/of "SGD" 0)
                       :value-date   (.minusDays today 1)
                       :purpose-code "XXXX"})]
    (is (= #{:amount :value-date :purpose-code} (set (keys found)))
        "all three, and nothing else")
    (is (= "must be greater than zero" (:amount found)))
    (is (= "must not be in the past" (:value-date found)))
    (is (= "unknown purpose code: XXXX" (:purpose-code found)))))

(deftest ac-2-an-entirely-empty-candidate-names-every-required-field
  (testing "the count matters: a validator that stopped early would report one"
    (let [found (instruction/field-errors {:status :draft} opts)]
      (is (= #{:organisation-id :debtor-account-id :creditor-name :creditor-account
               :amount :value-date :purpose-code :created-by}
             (set (keys found))))
      (is (every? #(= "is required" %) (vals found))))))

(deftest a-valid-candidate-has-no-errors
  (is (= {} (errors {})))
  (is (instruction/valid? (valid-candidate) opts)))

(deftest an-absent-field-and-a-wrong-typed-one-get-different-answers
  (testing "a caller that omitted a field and one that sent the wrong kind of
            value have made different mistakes"
    (is (= "is required" (:creditor-name (errors {:creditor-name nil}))))
    (is (= "must be text" (:creditor-name (errors {:creditor-name 42}))))
    (is (= "is required" (:debtor-account-id (errors {:debtor-account-id nil}))))
    (is (= "must be a UUID" (:debtor-account-id (errors {:debtor-account-id "nope"}))))))

;; ---------------------------------------------------------------------------
;; Individual rules
;; ---------------------------------------------------------------------------

(deftest an-amount-must-be-positive-money
  (is (= "must be greater than zero" (:amount (errors {:amount (money/of "SGD" 0)}))))
  (is (= "must be greater than zero" (:amount (errors {:amount (money/of "SGD" -1)}))))
  (is (nil? (:amount (errors {:amount (money/of "SGD" 1)}))))
  (testing "an amount in a currency CloFin cannot represent is not an amount"
    (is (some? (:amount (errors {:amount {:currency "XYZ" :minor-units 100}}))))
    (is (some? (:amount (errors {:amount {:currency "SGD" :minor-units 1.5}}))))))

(deftest a-zero-decimal-currency-is-not-a-special-case
  (testing "JPY has no minor unit; 1250 minor units is JPY 1,250"
    (is (nil? (:amount (errors {:amount (money/of "JPY" 1250)}))))))

(deftest a-value-date-must-be-today-or-later-and-not-absurdly-far-out
  (is (nil? (:value-date (errors {:value-date today})))
      "today is not in the past")
  (is (= "must not be in the past" (:value-date (errors {:value-date (.minusDays today 1)}))))
  (is (nil? (:value-date (errors {:value-date (.plusDays today
                                                         instruction/max-value-date-horizon-days)}))))
  (is (= (str "must be within " instruction/max-value-date-horizon-days " days")
         (:value-date (errors {:value-date (.plusDays today
                                                      (inc instruction/max-value-date-horizon-days))})))
      "a warehoused payment is a product; one dated four centuries out is a typo"))

(deftest validating-a-value-date-requires-the-caller-to-supply-today
  (testing "the domain reads no clock, so the boundary that matters is testable"
    (is (thrown? Exception (instruction/field-errors (valid-candidate) {})))))

(deftest a-purpose-code-must-be-in-the-vocabulary
  (doseq [code (keys instruction/purpose-codes)]
    (is (nil? (:purpose-code (errors {:purpose-code code}))) (str code " must be accepted")))
  (is (= "unknown purpose code: SUPPLIER"
         (:purpose-code (errors {:purpose-code "SUPPLIER"}))))
  (testing "the vocabulary is case-sensitive — a scheme's code set is"
    (is (some? (:purpose-code (errors {:purpose-code "supp"}))))))

(deftest a-creditor-name-is-bounded-at-the-length-a-scheme-message-carries
  (is (nil? (:creditor-name (errors {:creditor-name (apply str (repeat 140 "a"))}))))
  (is (= "must be at most 140 characters"
         (:creditor-name (errors {:creditor-name (apply str (repeat 141 "a"))}))))
  (is (= "is required" (:creditor-name (errors {:creditor-name "   "})))))

(deftest a-creditor-account-is-a-constrained-synthetic-identifier
  (is (nil? (:creditor-account (errors {:creditor-account "SG-SYNTH-88012345"}))))
  (is (nil? (:creditor-account (errors {:creditor-account "ABCD"}))) "four is the minimum")
  (is (some? (:creditor-account (errors {:creditor-account "ABC"}))) "three is not")
  (is (some? (:creditor-account (errors {:creditor-account (apply str (repeat 35 "A"))}))))
  (testing "deliberately narrower than any real scheme's, so nothing real fits"
    (is (some? (:creditor-account (errors {:creditor-account "sg-synth-1"}))))
    (is (some? (:creditor-account (errors {:creditor-account "SG SYNTH 1"}))))
    (is (some? (:creditor-account (errors {:creditor-account "-LEADING-HYPHEN"}))))
    (is (some? (:creditor-account (errors {:creditor-account "TRAILING-HYPHEN-"}))))))

(deftest reverses-id-is-optional-but-must-be-a-uuid-when-present
  (is (nil? (:reverses-id (errors {:reverses-id nil}))))
  (is (nil? (:reverses-id (errors {:reverses-id (random-uuid)}))))
  (is (= "must be a UUID" (:reverses-id (errors {:reverses-id "not-a-uuid"})))))

(deftest ac-1-retries-id-is-optional-but-must-be-a-uuid-when-present
  (is (nil? (:retries-id (errors {:retries-id nil}))))
  (is (nil? (:retries-id (errors {:retries-id (random-uuid)}))))
  (is (= "must be a UUID" (:retries-id (errors {:retries-id "not-a-uuid"}))))
  (testing "whether the target is a returned instruction of this organisation is
            a question about stored state, so it is answered at the persistence
            seam and not here (ADR-0012)"
    (is (nil? (:retries-id (errors {:retries-id (random-uuid)}))))))

(deftest ac-1-a-built-instruction-and-a-loaded-row-have-the-same-shape
  (testing "both link fields are defaulted rather than merely selected: a key
            absent in one and nil in the other is a difference that only shows
            up in an equality check nobody expected to fail"
    (let [built (instruction/draft (valid-candidate) opts)]
      (is (contains? built :reverses-id))
      (is (contains? built :retries-id))
      (is (nil? (:retries-id built)))))
  (testing "and a retry keeps the link it was given"
    (let [original (random-uuid)
          retry    (instruction/draft (assoc (valid-candidate) :retries-id original) opts)]
      (is (= original (:retries-id retry))))))

(deftest ac-1-neither-link-may-be-amended
  (testing "a reversal does not stop being one, and nor does a retry stop
            replacing what it replaces"
    (doseq [field [:reverses-id :retries-id]]
      (is (not (contains? instruction/amendable-fields field)) (str field))
      (let [t (caught #(instruction/amend (instruction/draft (valid-candidate) opts)
                                          {field (random-uuid)} opts))]
        (is (some? t) (str field " must not be amendable"))
        (is (= :field-validation (:clofin/error (ex-data t))))
        (is (= "cannot be amended" (get (ex-data t) field)))))))

(deftest a-status-outside-the-lifecycle-is-refused
  (is (= "unknown status: :in-flight" (:status (errors {:status :in-flight})))))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(deftest the-constructor-throws-with-every-failed-field-attached
  (let [t (caught #(instruction/instruction
                    (merge (valid-candidate)
                           {:amount (money/of "SGD" 0) :purpose-code "XXXX"})
                    opts))
        data (ex-data t)]
    (is (= :field-validation (:clofin/error data))
        "422 under the validation problem type, not 400 — ADR-0014")
    (is (= "must be greater than zero" (:amount data)))
    (is (= "unknown purpose code: XXXX" (:purpose-code data)))))

(deftest the-constructor-and-field-errors-cannot-disagree
  (testing "the constructor is built on field-errors, so one cannot accept what
            the other rejects"
    (doseq [override [{:amount (money/of "SGD" 0)}
                      {:value-date (.minusDays today 1)}
                      {:purpose-code "XXXX"}
                      {:creditor-account "no"}
                      {:creditor-name ""}]]
      (is (seq (errors override)))
      (is (some? (caught #(instruction/instruction (merge (valid-candidate) override) opts)))))))

(deftest a-draft-begins-where-the-lifecycle-begins
  (let [drafted (instruction/draft (dissoc (valid-candidate) :status) opts)]
    (is (= state/initial-state (:status drafted)))
    (is (= :draft (:status drafted)))))

(deftest construction-trims-the-text-a-caller-sent
  (let [built (instruction/instruction
               (assoc (valid-candidate)
                      :creditor-name "  Pacific Rim Logistics Pte Ltd  "
                      :creditor-account "  SG-SYNTH-88012345  ")
               opts)]
    (is (= "Pacific Rim Logistics Pte Ltd" (:creditor-name built)))
    (is (= "SG-SYNTH-88012345" (:creditor-account built)))))

(deftest construction-keeps-only-the-fields-an-instruction-has
  (let [built (instruction/instruction (assoc (valid-candidate) :smuggled "value") opts)]
    (is (not (contains? built :smuggled)))))

;; ---------------------------------------------------------------------------
;; Amendment
;; ---------------------------------------------------------------------------

(deftest amending-changes-only-what-was-supplied
  (let [existing (instruction/instruction (valid-candidate) opts)
        amended  (instruction/amend existing {:amount (money/of "SGD" 999)} opts)]
    (is (= (money/of "SGD" 999) (:amount amended)))
    (is (= (:creditor-name existing) (:creditor-name amended)))
    (is (= (:id existing) (:id amended)))
    (is (= (:status existing) (:status amended))
        "an amendment is not a transition — the status is untouched")))

(deftest an-amendment-is-revalidated-as-a-whole
  (let [existing (instruction/instruction (valid-candidate) opts)]
    (is (some? (caught #(instruction/amend existing {:amount (money/of "SGD" 0)} opts))))
    (is (some? (caught #(instruction/amend existing {:purpose-code "XXXX"} opts))))))

(deftest identity-provenance-and-lifecycle-are-not-amendable
  (let [existing (instruction/instruction (valid-candidate) opts)]
    (doseq [field [:id :organisation-id :status :created-by :created-at :reverses-id]]
      (is (not (contains? instruction/amendable-fields field))
          (str (name field) " must not be amendable"))
      (let [data (ex-data (caught #(instruction/amend existing {field :anything} opts)))]
        (is (= :field-validation (:clofin/error data)))
        (is (= "cannot be amended" (get data field))
            (str (name field) " must be rejected rather than ignored"))))))

(deftest every-amendable-field-can-actually-be-amended
  (let [existing (instruction/instruction (valid-candidate) opts)
        changes  {:debtor-account-id (random-uuid)
                  :creditor-name     "Andaman Shipping Sdn Bhd"
                  :creditor-account  "MY-SYNTH-42"
                  :amount            (money/of "SGD" 7500)
                  :value-date        (.plusDays today 30)
                  :purpose-code      "TRAD"}]
    (is (= (set (keys changes)) instruction/amendable-fields)
        "this test covers the whole set, and fails if the set grows")
    (let [amended (instruction/amend existing changes opts)]
      (doseq [[field value] changes]
        (is (= value (get amended field)))))))
