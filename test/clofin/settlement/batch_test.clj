(ns clofin.settlement.batch-test
  "Batching rules and status derivation, as pure functions.

  Two things are asserted here that a database test could not assert as well:
  that the vocabulary in code is the vocabulary in the schema (drift between
  them is a `500` the first time a caller hits it), and that status derivation
  is a *total* function over outcome mixes rather than three examples somebody
  thought of."
  (:require [clofin.error :as err]
            [clofin.settlement.batch :as batch]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import [java.time LocalDate]))

(def ^:private migration
  (slurp (io/file "resources/migrations/0009-settlement-batches-and-scheme-responses.sql")))

(defn- failure [f]
  (try (f) (is false "expected a domain error, but the call succeeded") nil
       (catch clojure.lang.ExceptionInfo e
         (is (err/domain-error? e))
         (ex-data e))))

;; ---------------------------------------------------------------------------
;; The vocabulary agrees with the schema
;; ---------------------------------------------------------------------------

(deftest the-schemes-in-code-are-the-schemes-in-the-migration
  (testing "a scheme one knows and the other does not is a 500 the first time a caller hits it —
            the same discipline clofin.authz.model/roles keeps with role_known"
    (doseq [scheme batch/schemes]
      (is (str/includes? migration (str "'" scheme "'"))
          (str scheme " is in the code vocabulary and not in settlement_scheme_known"))))
  (testing "and every scheme is simulated, which the SIM- prefix is what makes checkable"
    (is (every? #(str/starts-with? % "SIM-") batch/schemes))))

(deftest the-statuses-and-outcomes-in-code-are-the-ones-in-the-migration
  (doseq [status batch/statuses]
    (is (str/includes? migration (str "'" status "'"))
        (str status " is not in settlement_batch_status_known")))
  (doseq [outcome batch/item-outcomes]
    (is (str/includes? migration (str "'" outcome "'"))
        (str outcome " is not in settlement_outcome_known")))
  (testing "and `failed` is deliberately not an item outcome"
    (is (not (contains? batch/item-outcomes "failed"))
        "a scheme failure that returns the money is a return; an unknown outcome is timed-out")))

(deftest an-unknown-scheme-is-refused-by-name
  (testing "including a real one — the point of the SIM- prefix"
    (doseq [scheme ["SWIFT" "SEPA" "sim-rtgs" "" nil]]
      (is (some? (failure #(batch/assert-scheme! scheme)))
          (str (pr-str scheme) " must be refused")))))

;; ---------------------------------------------------------------------------
;; Eligibility
;; ---------------------------------------------------------------------------

(def ^:private org #uuid "00000000-0000-0000-0000-0000000000aa")
(def ^:private value-date (LocalDate/parse "2026-12-01"))

(defn- a-batch [& {:as overrides}]
  (merge {:organisation-id org :scheme "SIM-RTGS" :currency "SGD" :value-date value-date}
         overrides))

(defn- an-instruction [& {:as overrides}]
  (merge {:id (random-uuid) :organisation-id org :status :approved
          :amount {:currency "SGD" :minor-units 125000}
          :value-date value-date}
         overrides))

(deftest an-approved-instruction-matching-the-batch-is-eligible
  (is (batch/eligible? (a-batch) (an-instruction)))
  (is (nil? (batch/refusal (a-batch) (an-instruction)))))

(deftest ac-1-only-an-approved-instruction-may-be-batched
  (doseq [status [:draft :pending-approval :released :settled :rejected :cancelled
                  :failed :returned]]
    (is (= :not-approved (batch/refusal (a-batch) (an-instruction :status status)))
        (str "a " (name status) " instruction must be refused, by name"))))

(deftest ac-2-a-batch-is-one-currency-and-one-value-date
  (testing "currency"
    (is (= :currency-mismatch
           (batch/refusal (a-batch)
                          (an-instruction :amount {:currency "USD" :minor-units 125000})))))
  (testing "value date"
    (is (= :value-date-mismatch
           (batch/refusal (a-batch)
                          (an-instruction :value-date (LocalDate/parse "2026-12-02")))))))

(deftest another-tenants-instruction-is-refused-first
  (testing "before status, before currency — every later answer would confirm something
            about a record the caller may not see"
    (is (= :wrong-organisation
           (batch/refusal (a-batch)
                          (an-instruction :organisation-id (random-uuid)
                                          :status :draft
                                          :amount {:currency "USD" :minor-units 1}))))))

(deftest every-refusal-reason-can-be-explained-to-a-caller
  (testing "a reason with no explanation reaches an operator as a bare keyword"
    (doseq [reason (keys batch/refusal-reasons)]
      (is (string? (batch/refusal-reasons reason))))
    (doseq [reason [:not-approved :wrong-organisation :currency-mismatch :value-date-mismatch]]
      (is (contains? batch/refusal-reasons reason)))))

(deftest assert-eligible-names-every-refusal-not-only-the-first
  (testing "an operator batching forty payments fixes them in one pass"
    (let [bad-status   (an-instruction :status :draft)
          bad-currency (an-instruction :amount {:currency "USD" :minor-units 1})
          data (failure #(batch/assert-eligible! (a-batch)
                                                 [(an-instruction) bad-status bad-currency]))]
      (is (= :unprocessable (:clofin/error data)))
      (is (= 2 (count (:refused data))))
      (is (= #{"not-approved" "currency-mismatch"}
             (set (map :reason (:refused data)))))
      (is (every? :detail (:refused data)) "each carries prose an operator can act on"))))

(deftest an-eligible-set-passes-through-unchanged
  (let [instructions [(an-instruction) (an-instruction)]]
    (is (= instructions (batch/assert-eligible! (a-batch) instructions)))))

(deftest an-empty-batch-is-refused
  (is (= :validation (:clofin/error (failure #(batch/assert-non-empty! []))))))

;; ---------------------------------------------------------------------------
;; Grouping
;; ---------------------------------------------------------------------------

(deftest instructions-group-by-currency-and-value-date
  (let [a (an-instruction)
        b (an-instruction)
        c (an-instruction :amount {:currency "USD" :minor-units 1})
        d (an-instruction :value-date (LocalDate/parse "2026-12-02"))
        grouped (batch/group-by-key [a b c d])]
    (is (= 3 (count grouped)))
    (is (= 2 (count (get grouped ["SGD" value-date]))))
    (is (= 1 (count (get grouped ["USD" value-date]))))))

(deftest the-batch-key-is-the-triple-that-defines-a-batch
  (is (= ["SIM-RTGS" "SGD" value-date] (batch/batch-key (a-batch)))))

;; ---------------------------------------------------------------------------
;; Status derivation — AC-4's rule, exhaustively
;; ---------------------------------------------------------------------------

(defn- items [& outcomes]
  (mapv (fn [o] {:instruction-id (random-uuid) :outcome o}) outcomes))

(deftest a-batch-not-yet-submitted-is-open
  (is (= "open" (batch/derive-status {:submitted? false :items (items nil nil)})))
  (is (= "open" (batch/derive-status {:submitted? false :items []}))))

(deftest a-batch-with-an-unresolved-item-is-still-submitted
  (is (= "submitted" (batch/derive-status {:submitted? true :items (items "settled" nil)})))
  (is (= "submitted" (batch/derive-status {:submitted? true :items (items nil)}))))

(deftest a-fully-resolved-batch-derives-from-its-outcomes
  (is (= "settled"           (batch/derive-status {:submitted? true :items (items "settled" "settled")})))
  (is (= "partially-settled" (batch/derive-status {:submitted? true :items (items "settled" "returned")})))
  (is (= "failed"            (batch/derive-status {:submitted? true :items (items "returned" "returned")}))))

(deftest a-batch-whose-items-all-timed-out-derives-to-failed
  (testing "a statement about what CloFin knows, not about what the scheme did — the items
            keep the distinction, and settlement_item_instruction_key keeps them
            un-re-batchable"
    (is (= "failed" (batch/derive-status {:submitted? true :items (items "timed-out" "timed-out")})))
    (is (= "partially-settled"
           (batch/derive-status {:submitted? true :items (items "settled" "timed-out")})))))

(deftest completeness-is-every-item-having-an-answer
  (is (batch/complete? (items "settled" "returned" "timed-out")))
  (is (not (batch/complete? (items "settled" nil))))
  (is (not (batch/complete? []))
      "an empty batch is not 'complete' — there was never anything to complete"))

(defspec derive-status-is-total-over-every-outcome-mix 300
  (prop/for-all [outcomes (gen/vector (gen/elements ["settled" "returned" "timed-out" nil])
                                      1 8)]
    (let [is (mapv (fn [o] {:instruction-id (random-uuid) :outcome o}) outcomes)
          status (batch/derive-status {:submitted? true :items is})]
      (and (contains? batch/statuses status)
           ;; The rule, restated independently of the implementation.
           (if (some nil? outcomes)
             (= "submitted" status)
             (cond
               (every? #(= "settled" %) outcomes) (= "settled" status)
               (some   #(= "settled" %) outcomes) (= "partially-settled" status)
               :else                              (= "failed" status)))
           ;; A batch is complete exactly when nothing is outstanding.
           (= (not-any? nil? outcomes) (batch/complete? is))))))

(defspec a-derived-status-is-never-open-once-submitted 100
  (prop/for-all [outcomes (gen/vector (gen/elements ["settled" "returned" "timed-out" nil]) 1 6)]
    (not= "open" (batch/derive-status
                  {:submitted? true
                   :items (mapv (fn [o] {:outcome o}) outcomes)}))))
