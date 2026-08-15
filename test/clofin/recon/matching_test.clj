(ns clofin.recon.matching-test
  "The matching sequence, the agreement checks, and **the guard that keeps the
  documented rule order and the code's the same list** (AC-9).

  The guard is the vocabulary-drift shape this repository has used sixteen times
  over (standing lesson **L-6**), applied to an ordered list rather than a set:
  it compares `DOMAIN_MODEL.md` §6's table with `clofin.recon.matching/rules` in
  **both directions and in order**, because a rule sequence documented in the
  wrong order is a document that reads as evidence and is false.

  The reading code deliberately shares nothing with the code that publishes the
  rules. `clofin.tools.markdown` parses the table; a round trip through the
  generator's own emitter would prove the emitter is self-consistent and nothing
  else."
  (:require [clofin.money :as money]
            [clofin.recon.matching :as matching]
            [clofin.tools.markdown :as md]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time LocalDate]))

(defn- sgd [minor] (money/of "SGD" minor))
(def ^:private day-1 (LocalDate/parse "2026-08-10"))
(def ^:private day-2 (LocalDate/parse "2026-08-13"))

(defn- line
  [line-no & {:keys [reference amount value-date line-type]
              :or {amount (sgd 125000) value-date day-1 line-type "settlement"}}]
  {:line-no line-no :payment-reference reference :amount amount
   :value-date value-date :line-type line-type})

(defn- expectation
  [entry-id & {:keys [reference amount value-date line-type]
               :or {amount (sgd 125000) value-date day-1 line-type "settlement"}}]
  {:entry-id entry-id :payment-reference reference :amount amount
   :value-date value-date :line-type line-type})

(defn- kinds [result] (mapv :kind (:breaks result)))

;; ---------------------------------------------------------------------------
;; AC-9 — the documented rule order and the code's, both directions and in order
;; ---------------------------------------------------------------------------

(def ^:private documented-rule-ids
  "The rule ids `DOMAIN_MODEL.md` §6.1's table publishes, in document order.

  Read from the document by a code path the matcher does not share. §6's first
  table is the rule sequence, which is why the section is its own top-level
  heading rather than a paragraph inside §2.4 — a guard that had to pick the
  third table out of a section would be a guard that broke when a table was
  added."
  (delay
    (->> (md/first-table (md/section (md/read-lines "docs/DOMAIN_MODEL.md")
                                     #"^## 6\. Reconciliation matching"))
         (mapv (fn [[_order id _when]] (md/plain id))))))

(deftest ac-9-the-documented-rule-order-and-the-code-agree-in-both-directions
  (let [documented @documented-rule-ids
        in-code    matching/rule-ids]
    (testing "the document names a rule the code does not run"
      (is (empty? (set/difference (set documented) (set in-code)))
          (str "rules DOMAIN_MODEL §6 publishes and the matcher does not have: "
               (pr-str (set/difference (set documented) (set in-code))))))
    (testing "the code runs a rule the document does not name"
      (is (empty? (set/difference (set in-code) (set documented)))
          (str "rules the matcher applies and DOMAIN_MODEL §6 does not publish: "
               (pr-str (set/difference (set in-code) (set documented))))))
    (testing "and they are in the same order, because the order IS the specification"
      (is (= documented in-code)
          (str "documented: " (pr-str documented) "\ncode:       " (pr-str in-code))))))

(deftest ac-9-the-guard-is-not-vacuous
  (testing "a table this reader found nothing in would pass every assertion above"
    (is (= 4 (count @documented-rule-ids)))
    (is (every? #(str/starts-with? % "R") @documented-rule-ids))))

(deftest ac-9-a-rule-renamed-in-code-alone-fails-the-comparison
  ;; The negative control, run rather than asserted. Standing lesson L-6's
  ;; incident was a guard that passed while the thing it guarded was false, so
  ;; this proves the guard can fail.
  (with-redefs [matching/rule-ids (assoc (vec matching/rule-ids) 0 "R1-renamed-in-code-only")]
    (is (not= @documented-rule-ids matching/rule-ids)
        "the comparison must notice a rule renamed on one side")))

(deftest ac-9-every-documented-rule-has-a-predicate-and-a-summary
  (doseq [{:keys [id summary matches?]} matching/rules]
    (is (contains? (set @documented-rule-ids) id))
    (is (not (str/blank? summary)) (str id " must say what it matches on"))
    (is (ifn? matches?) (str id " must be a rule and not a description"))))

;; ---------------------------------------------------------------------------
;; The rules themselves
;; ---------------------------------------------------------------------------

(deftest r1-matches-when-reference-amount-and-value-date-all-agree
  (let [result (matching/reconcile
                {:lines [(line 1 :reference "abc")]
                 :expectations [(expectation :e1 :reference "abc")]})]
    (is (= [{:line-no 1 :entry-id :e1 :rule-id "R1-reference-amount-and-value-date"}]
           (:matches result)))
    (is (empty? (:breaks result)) "everything agreed, so there is nothing to work")))

(deftest r2-matches-a-shifted-value-date-and-the-disagreement-is-still-a-break
  (let [result (matching/reconcile
                {:lines [(line 1 :reference "abc" :value-date day-2)]
                 :expectations [(expectation :e1 :reference "abc")]})]
    (is (= "R2-reference-and-amount" (:rule-id (first (:matches result))))
        "the movement is identified — matching and agreement are different questions")
    (is (= ["value-date-mismatch"] (kinds result)))
    (is (str/includes? (:detail (first (:breaks result))) (str day-2))
        "the break names both dates rather than merely saying they differ")
    (is (str/includes? (:detail (first (:breaks result))) (str day-1)))))

(deftest r3-matches-on-reference-alone-and-names-the-amounts
  (let [result (matching/reconcile
                {:lines [(line 1 :reference "abc" :amount (sgd 125100))]
                 :expectations [(expectation :e1 :reference "abc")]})]
    (is (= "R3-reference-only" (:rule-id (first (:matches result)))))
    (is (= ["amount-mismatch"] (kinds result)))
    (testing "the single most valuable finding — same payment, different amount —
              is one actionable break rather than two unrelated `not found`s"
      (is (str/includes? (:detail (first (:breaks result))) "1251.00"))
      (is (str/includes? (:detail (first (:breaks result))) "1250.00")))))

(deftest r4-matches-a-line-the-scheme-did-not-tag
  (let [result (matching/reconcile
                {:lines [(line 1)]
                 :expectations [(expectation :e1 :reference "abc")]})]
    (is (= "R4-amount-and-value-date" (:rule-id (first (:matches result))))
        "a line with no end-to-end reference is matched on its attributes, and
         the match records that it was the weaker rule that found it")
    (is (empty? (:breaks result)))))

(deftest r4-refuses-to-guess-between-two-candidates
  (testing "a guessed match is worse than a break: a break is visible and a
            wrong match is not"
    (let [result (matching/reconcile
                  {:lines [(line 1)]
                   :expectations [(expectation :e1 :reference "abc")
                                  (expectation :e2 :reference "def")]})]
      (is (empty? (:matches result)))
      (is (= ["statement-line-unmatched" "expectation-unmatched" "expectation-unmatched"]
             (kinds result))))))

(deftest a-line-with-a-reference-nothing-answers-is-never-matched-by-attributes
  (testing "R4 applies only to a line carrying no reference. A line whose
            reference names nothing is the scheme claiming a specific payment
            CloFin has no record of, and quietly attaching it to some other
            movement of the same size would be the wrong match this design
            refuses to make"
    (let [result (matching/reconcile
                  {:lines [(line 1 :reference "never-issued")]
                   :expectations [(expectation :e1 :reference "abc")]})]
      (is (empty? (:matches result)))
      (is (= ["statement-line-unmatched" "expectation-unmatched"] (kinds result))))))

(deftest rules-are-applied-rule-major-so-the-strongest-evidence-claims-first
  (testing "a referenced line takes its movement before an unreferenced one can
            claim it on amount and date — whichever order the scheme listed
            them in"
    (doseq [[label lines] [["untagged first" [(line 1) (line 2 :reference "abc")]]
                           ["tagged first"   [(line 1 :reference "abc") (line 2)]]]]
      (let [result (matching/reconcile
                    {:lines lines
                     :expectations [(expectation :e1 :reference "abc")]})
            by-rule (into {} (map (juxt :rule-id :line-no)) (:matches result))]
        (is (= 1 (count (:matches result))) label)
        (is (contains? by-rule "R1-reference-amount-and-value-date")
            (str label ": the referenced line must win the movement"))
        (is (= ["statement-line-unmatched"] (kinds result)) label)))))

;; ---------------------------------------------------------------------------
;; Both directions, and the six kinds
;; ---------------------------------------------------------------------------

(deftest a-ledger-movement-no-line-reports-is-a-break-too
  (testing "the direction a statement-line-only reconciliation would miss
            entirely — CloFin claiming a movement the scheme does not report"
    (let [result (matching/reconcile {:lines [] :expectations [(expectation :e1)]})]
      (is (= ["expectation-unmatched"] (kinds result)))
      (is (= :e1 (:entry-id (first (:breaks result)))))
      (is (nil? (:line-no (first (:breaks result))))))))

(deftest a-second-claim-on-a-matched-movement-is-a-duplicate-not-merely-unmatched
  (let [result (matching/reconcile
                {:lines [(line 1 :reference "abc") (line 2 :reference "abc")]
                 :expectations [(expectation :e1 :reference "abc")]})]
    (is (= 1 (count (:matches result))))
    (is (= ["duplicate-statement-line"] (kinds result))
        "naming it a duplicate is different work from naming it unmatched")
    (is (str/includes? (:detail (first (:breaks result))) "abc"))))

(deftest a-matched-pair-that-disagrees-about-direction-of-travel-is-a-break
  (let [result (matching/reconcile
                {:lines [(line 1 :reference "abc" :line-type "return")]
                 :expectations [(expectation :e1 :reference "abc" :line-type "settlement")]})]
    (is (= 1 (count (:matches result))))
    (is (= ["line-type-mismatch"] (kinds result)))))

(deftest an-expectation-whose-kind-cannot-be-derived-agrees-with-anything
  (testing "asserting a disagreement out of an absence is the overstatement L-14
            names: a movement whose counter-account is neither finality account
            is one CloFin cannot classify, not one that disagrees"
    (let [result (matching/reconcile
                  {:lines [(line 1 :reference "abc" :line-type "return")]
                   :expectations [(expectation :e1 :reference "abc" :line-type nil)]})]
      (is (empty? (:breaks result))))))

(deftest one-pair-can-disagree-in-more-than-one-way-and-each-is-its-own-break
  (testing "a line with the wrong amount AND the wrong date is two facts an
            investigator needs, not one"
    (let [result (matching/reconcile
                  {:lines [(line 1 :reference "abc" :amount (sgd 999) :value-date day-2
                            :line-type "return")]
                   :expectations [(expectation :e1 :reference "abc")]})]
      (is (= 1 (count (:matches result))))
      (is (= ["amount-mismatch" "value-date-mismatch" "line-type-mismatch"]
             (kinds result))
          "and they come out in the documented order"))))

(deftest every-break-kind-the-vocabulary-declares-is-produced-by-a-case-here
  ;; The other direction of standing lesson L-6: a kind nothing can emit is a
  ;; term nothing enumerates, and a kind emitted under no name is worse.
  (let [produced (into #{}
                       (mapcat kinds)
                       [(matching/reconcile {:lines [(line 1 :reference "x")] :expectations []})
                        (matching/reconcile {:lines [] :expectations [(expectation :e1)]})
                        (matching/reconcile {:lines [(line 1 :reference "abc")
                                                     (line 2 :reference "abc")]
                                             :expectations [(expectation :e1 :reference "abc")]})
                        (matching/reconcile {:lines [(line 1 :reference "abc"
                                                      :amount (sgd 1) :value-date day-2
                                                      :line-type "return")]
                                             :expectations [(expectation :e1 :reference "abc")]})])]
    (is (= (set matching/break-kinds) produced)
        (str "break kinds declared and not produced by any case here: "
             (pr-str (set/difference (set matching/break-kinds) produced))))))

;; ---------------------------------------------------------------------------
;; Determinism
;; ---------------------------------------------------------------------------

(deftest the-outcome-does-not-depend-on-the-order-the-inputs-arrived-in
  (let [lines [(line 3 :reference "c" :amount (sgd 300))
               (line 1 :reference "a" :amount (sgd 100))
               (line 2 :reference "b" :amount (sgd 200))]
        exps  [(expectation :e2 :reference "b" :amount (sgd 200))
               (expectation :e3 :reference "zz" :amount (sgd 999))
               (expectation :e1 :reference "a" :amount (sgd 100))]
        a (matching/reconcile {:lines lines :expectations exps})
        b (matching/reconcile {:lines (reverse lines) :expectations (reverse exps)})]
    (is (= a b) "a function whose output order moved between runs would make
                 every assertion about it a sample")
    (is (= [1 2] (mapv :line-no (:matches a)))
        "matches come out in line order; line 3 names a payment no expectation
         carries, and e3 is a movement no line reports")
    (is (= ["statement-line-unmatched" "expectation-unmatched"] (kinds a)))))

(deftest matching-consumes-each-side-at-most-once
  (let [result (matching/reconcile
                {:lines [(line 1 :reference "a") (line 2 :reference "b")]
                 :expectations [(expectation :e1 :reference "a")
                                (expectation :e2 :reference "b")]})]
    (is (= 2 (count (:matches result))))
    (is (= 2 (count (distinct (map :entry-id (:matches result))))))
    (is (= 2 (count (distinct (map :line-no (:matches result))))))))

(deftest nothing-matches-when-there-is-nothing-to-match
  (let [result (matching/reconcile {:lines [] :expectations []})]
    (is (empty? (:matches result)))
    (is (empty? (:breaks result)))))

;; ---------------------------------------------------------------------------
;; Reading a reference off either side
;; ---------------------------------------------------------------------------

(deftest a-reference-is-compared-as-text-and-never-parsed
  (testing "a statement line's reference is whatever the scheme echoed back and
            may be anything at all; raising on it would be the matcher trying to
            parse a value it has no business parsing"
    (let [id (random-uuid)]
      (is (= (str id) (matching/reference-of {:payment-reference id})))
      (is (= "abc" (matching/reference-of {:payment-reference "  abc  "})))
      (is (nil? (matching/reference-of {:payment-reference "   "})))
      (is (nil? (matching/reference-of {:payment-reference nil})))
      (is (nil? (matching/reference-of {}))))))

(deftest a-uuid-reference-matches-the-string-form-of-the-same-uuid
  (let [id (random-uuid)
        result (matching/reconcile
                {:lines [(line 1 :reference (str id))]
                 :expectations [(expectation :e1 :reference id)]})]
    (is (= 1 (count (:matches result)))
        "the statement carries a string and the journal an id; they are the same
         reference and must match")))

(deftest amounts-in-different-currencies-never-match-and-never-raise
  (testing "both sides are single-currency by construction, so this is the guard
            for when that stops being true — and a break is the right answer
            there rather than a 500"
    (let [result (matching/reconcile
                  {:lines [(line 1 :reference "abc" :amount (money/of "USD" 125000))]
                   :expectations [(expectation :e1 :reference "abc")]})]
      (is (= "R3-reference-only" (:rule-id (first (:matches result))))
          "the reference still identifies the movement")
      (is (= ["amount-mismatch"] (kinds result))))))
