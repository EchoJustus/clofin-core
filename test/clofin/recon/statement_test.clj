(ns clofin.recon.statement-test
  "The statement format: what it refuses, and what its digest covers.

  Two things are worth testing about a format nobody else defines. That it
  **refuses** documents in some other convention rather than mis-parsing them
  into plausible nonsense — a synthetic reference implementation reading a real
  bank format is the misreading the whole scope statement exists to prevent. And
  that its digest covers **every effect-bearing field**, which is standing
  lessons **L-2** and **L-12**: a replay key that excludes a field deciding an
  effect lets two contradictory documents collapse into one."
  (:require [clofin.money :as money]
            [clofin.recon.statement :as statement]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant LocalDate]))

(defn- document
  [& {:as overrides}]
  (merge {"format"             statement/format-name
          "formatVersion"      statement/format-version
          "scheme"             "SIM-RTGS"
          "currency"           "SGD"
          "statementReference" "SIM-STMT-TEST-1"
          "periodStart"        "2026-08-01T00:00:00Z"
          "periodEnd"          "2026-09-01T00:00:00Z"
          "lines"
          [{"lineNo"           1
            "schemeReference"  "SIM-STMT-LN-1"
            "paymentReference" "5e2f6f5c-0e4a-4c0e-9f1e-2f0d4a7c1e33"
            "lineType"         "settlement"
            "amount"           {"currency" "SGD" "minorUnits" 125000}
            "valueDate"        "2026-08-12"}]}
         overrides))

(defn- refused?
  [doc]
  (let [t (try (statement/assert-shape! doc) nil (catch Exception e e))]
    (boolean (and t (= :validation (:clofin/error (ex-data t)))))))

;; ---------------------------------------------------------------------------
;; The format is CloFin's own, and says so
;; ---------------------------------------------------------------------------

(deftest the-format-identifier-is-sim-prefixed-and-names-no-real-standard
  (is (str/starts-with? statement/format-name "SIM-"))
  (doseq [real ["camt" "mt940" "bai2" "iso20022" "pain" "mt103" "edifact" "swift"]]
    (is (not (str/includes? (str/lower-case statement/format-name) real))
        (str "the format identifier must not read as " real
             " — CloFin connects to nothing and reads no real scheme's schema"))))

(deftest a-document-in-any-other-format-is-refused-rather-than-guessed-at
  (is (some? (statement/assert-shape! (document))))
  (is (refused? (document "format" "camt.053.001.08")))
  (is (refused? (document "format" "SIM-SOMETHING-ELSE")))
  (is (refused? (document "format" nil)))
  (testing "and the refusal names what CloFin does read, plus why"
    (let [t (try (statement/assert-shape! (document "format" "camt.053.001.08"))
                 nil (catch Exception e e))]
      (is (= [statement/format-name] (:known (ex-data t))))
      (is (str/includes? (:note (ex-data t)) "camt.053")))))

(deftest a-version-this-build-does-not-read-is-refused
  (testing "a format change that left old documents parseable-but-different is
            the silent drift a version exists to make loud"
    (is (refused? (document "formatVersion" 2)))
    (is (refused? (document "formatVersion" nil)))
    (is (refused? (document "formatVersion" "1")))))

(deftest the-scheme-must-be-a-simulated-one
  (testing "reconciliation does not own the scheme vocabulary — settlement does,
            and the API boundary checks membership. What is checked here is the
            property that does not depend on the vocabulary: a statement CloFin
            reads names a SIMULATED scheme"
    (is (refused? (document "scheme" "TARGET2")))
    (is (refused? (document "scheme" "FEDWIRE")))
    (is (refused? (document "scheme" nil)))
    (is (some? (statement/assert-shape! (document "scheme" "SIM-ACH"))))))

;; ---------------------------------------------------------------------------
;; Shape
;; ---------------------------------------------------------------------------

(deftest a-period-that-ends-before-it-begins-is-refused
  (is (refused? (document "periodStart" "2026-09-01T00:00:00Z"
                          "periodEnd"   "2026-08-01T00:00:00Z")))
  (is (refused? (document "periodStart" "2026-08-01T00:00:00Z"
                          "periodEnd"   "2026-08-01T00:00:00Z"))
      "half-open, so an empty period is not a period"))

(deftest a-line-in-a-currency-other-than-the-statement-s-is-refused
  (is (refused? (document "lines" [{"lineNo" 1 "schemeReference" "x"
                                    "lineType" "settlement"
                                    "amount" {"currency" "USD" "minorUnits" 100}
                                    "valueDate" "2026-08-12"}]))))

(deftest a-line-must-carry-a-known-type-a-reference-and-a-positive-amount
  (doseq [[label line]
          [["unknown type"    {"lineNo" 1 "schemeReference" "x" "lineType" "release"
                               "amount" {"currency" "SGD" "minorUnits" 100}
                               "valueDate" "2026-08-12"}]
           ["no reference"    {"lineNo" 1 "lineType" "settlement"
                               "amount" {"currency" "SGD" "minorUnits" 100}
                               "valueDate" "2026-08-12"}]
           ["zero amount"     {"lineNo" 1 "schemeReference" "x" "lineType" "settlement"
                               "amount" {"currency" "SGD" "minorUnits" 0}
                               "valueDate" "2026-08-12"}]
           ["negative amount" {"lineNo" 1 "schemeReference" "x" "lineType" "settlement"
                               "amount" {"currency" "SGD" "minorUnits" -100}
                               "valueDate" "2026-08-12"}]
           ["no value date"   {"lineNo" 1 "schemeReference" "x" "lineType" "settlement"
                               "amount" {"currency" "SGD" "minorUnits" 100}}]]]
    (is (refused? (document "lines" [line])) label)))

(deftest a-line-may-carry-no-payment-reference-and-that-is-a-real-case
  (testing "an untagged line is matched on its attributes instead, by rule R4 —
            it is not missing data"
    (let [parsed (statement/assert-shape!
                  (document "lines" [{"lineNo" 1 "schemeReference" "x"
                                      "lineType" "return"
                                      "amount" {"currency" "SGD" "minorUnits" 100}
                                      "valueDate" "2026-08-12"}]))]
      (is (nil? (:payment-reference (first (:lines parsed)))))))
  (testing "and a blank one is the same claim as an absent one, so it must not
            digest differently"
    (let [blank (statement/assert-shape!
                 (document "lines" [{"lineNo" 1 "schemeReference" "x"
                                     "lineType" "return" "paymentReference" "   "
                                     "amount" {"currency" "SGD" "minorUnits" 100}
                                     "valueDate" "2026-08-12"}]))
          absent (statement/assert-shape!
                  (document "lines" [{"lineNo" 1 "schemeReference" "x"
                                      "lineType" "return"
                                      "amount" {"currency" "SGD" "minorUnits" 100}
                                      "valueDate" "2026-08-12"}]))]
      (is (= (statement/digest blank) (statement/digest absent))))))

(deftest a-statement-with-more-lines-than-the-cap-is-refused-rather-than-trimmed
  (let [line {"lineNo" 1 "schemeReference" "x" "lineType" "settlement"
              "amount" {"currency" "SGD" "minorUnits" 100} "valueDate" "2026-08-12"}]
    (is (some? (statement/assert-shape!
                (document "lines" (vec (repeat statement/max-lines line))))))
    (is (refused? (document "lines" (vec (repeat (inc statement/max-lines) line)))))))

(deftest line-numbers-are-assigned-by-clofin-and-not-read-from-the-document
  (testing "two lines claiming one lineNo would be two breaks claiming one
            identity, so a document is not a place to accept an identifier from"
    (let [numbered (statement/with-line-numbers
                     (statement/assert-shape!
                      (document "lines" [{"lineNo" 99 "schemeReference" "b"
                                          "lineType" "settlement"
                                          "amount" {"currency" "SGD" "minorUnits" 100}
                                          "valueDate" "2026-08-12"}
                                         {"lineNo" 99 "schemeReference" "a"
                                          "lineType" "return"
                                          "amount" {"currency" "SGD" "minorUnits" 200}
                                          "valueDate" "2026-08-12"}])))]
      (is (= [1 2] (mapv :line-no (:lines numbered))))
      (is (= ["b" "a"] (mapv :scheme-reference (:lines numbered)))
          "and the order is the document's, because an array is ordered"))))

(deftest a-parsed-statement-carries-domain-values-rather-than-strings
  (let [parsed (statement/assert-shape! (document))]
    (is (instance? Instant (:period-start parsed)))
    (is (instance? LocalDate (:value-date (first (:lines parsed)))))
    (is (money/money? (:amount (first (:lines parsed)))))))

;; ---------------------------------------------------------------------------
;; The digest covers every effect-bearing field
;; ---------------------------------------------------------------------------

(deftest every-field-that-decides-an-effect-moves-the-digest
  (let [base (statement/digest (statement/assert-shape! (document)))
        moved (fn [& overrides]
                (not= base (statement/digest
                            (statement/assert-shape! (apply document overrides)))))]
    (testing "the header fields decide which movements the statement is compared against"
      (is (moved "scheme" "SIM-ACH"))
      (is (moved "statementReference" "SIM-STMT-TEST-2"))
      (is (moved "periodStart" "2026-08-02T00:00:00Z"))
      (is (moved "periodEnd" "2026-10-01T00:00:00Z")))
    (testing "and every part of every line decides which matches and breaks come out"
      (doseq [[label line]
              [["amount"    {"lineNo" 1 "schemeReference" "SIM-STMT-LN-1"
                             "paymentReference" "5e2f6f5c-0e4a-4c0e-9f1e-2f0d4a7c1e33"
                             "lineType" "settlement"
                             "amount" {"currency" "SGD" "minorUnits" 125100}
                             "valueDate" "2026-08-12"}]
               ["reference" {"lineNo" 1 "schemeReference" "SIM-STMT-LN-1"
                             "paymentReference" "22222222-0e4a-4c0e-9f1e-2f0d4a7c1e33"
                             "lineType" "settlement"
                             "amount" {"currency" "SGD" "minorUnits" 125000}
                             "valueDate" "2026-08-12"}]
               ["line type" {"lineNo" 1 "schemeReference" "SIM-STMT-LN-1"
                             "paymentReference" "5e2f6f5c-0e4a-4c0e-9f1e-2f0d4a7c1e33"
                             "lineType" "return"
                             "amount" {"currency" "SGD" "minorUnits" 125000}
                             "valueDate" "2026-08-12"}]
               ["value date" {"lineNo" 1 "schemeReference" "SIM-STMT-LN-1"
                              "paymentReference" "5e2f6f5c-0e4a-4c0e-9f1e-2f0d4a7c1e33"
                              "lineType" "settlement"
                              "amount" {"currency" "SGD" "minorUnits" 125000}
                              "valueDate" "2026-08-13"}]
               ["scheme reference" {"lineNo" 1 "schemeReference" "SIM-STMT-LN-2"
                                    "paymentReference" "5e2f6f5c-0e4a-4c0e-9f1e-2f0d4a7c1e33"
                                    "lineType" "settlement"
                                    "amount" {"currency" "SGD" "minorUnits" 125000}
                                    "valueDate" "2026-08-12"}]]]
        (is (moved "lines" [line]) (str "a changed " label " must move the digest"))))
    (testing "and so does dropping a line, or adding one"
      (is (moved "lines" []))
      (is (moved "lines" (into (get (document) "lines")
                               [{"lineNo" 2 "schemeReference" "SIM-STMT-LN-9"
                                 "lineType" "return"
                                 "amount" {"currency" "SGD" "minorUnits" 1}
                                 "valueDate" "2026-08-12"}]))))))

(deftest line-order-is-part-of-the-message
  (testing "an array is ordered and `lines[3]` is a break's identity, so two
            documents listing the same movements in different orders address
            them differently"
    (let [lines [{"lineNo" 1 "schemeReference" "a" "lineType" "settlement"
                  "amount" {"currency" "SGD" "minorUnits" 100} "valueDate" "2026-08-12"}
                 {"lineNo" 2 "schemeReference" "b" "lineType" "return"
                  "amount" {"currency" "SGD" "minorUnits" 200} "valueDate" "2026-08-12"}]]
      (is (not= (statement/digest (statement/assert-shape! (document "lines" lines)))
                (statement/digest (statement/assert-shape!
                                   (document "lines" (vec (reverse lines))))))))))

(deftest the-same-document-digests-the-same-however-it-was-serialised
  (testing "key order and whitespace are not the message; a caller whose
            serialiser emits members in a different order is retrying, not
            conflicting"
    (let [a (statement/assert-shape! (document))
          b (statement/assert-shape! (into (sorted-map) (document)))]
      (is (= (statement/digest a) (statement/digest b))))))

(deftest a-stored-digest-that-cannot-be-compared-is-never-assumed-identical
  (testing "which is exactly the assumption audit finding F-009 found"
    (is (false? (statement/same-message? nil "v1:abc")))
    (is (false? (statement/same-message? "v1:abc" "v1:def")))
    (is (true? (statement/same-message? "v1:abc" "v1:abc")))))

(deftest the-digest-carries-the-canonicalisation-version
  (is (str/starts-with? (statement/digest (statement/assert-shape! (document))) "v1:")))

;; ---------------------------------------------------------------------------
;; Dispositions and refusal reasons
;; ---------------------------------------------------------------------------

(deftest every-stored-refusal-reason-is-a-refusal-reason
  (is (empty? (set/difference (set statement/stored-refusal-reasons)
                              (set (keys statement/refusal-reasons))))
      "a code that can reach a receipt and is in no published vocabulary is
       audit finding A-016 again"))

(deftest the-conflict-code-is-deliberately-not-storable
  (testing "that refusal happens BECAUSE a receipt for the identity already
            exists, and writing a second row would defeat the replay key that
            produced it"
    (is (contains? (set (keys statement/refusal-reasons)) "replay-key-conflict"))
    (is (not (contains? statement/stored-refusal-reasons "replay-key-conflict")))))

(deftest every-refusal-reason-has-prose-an-operator-can-act-on
  (doseq [[code detail] statement/refusal-reasons]
    (is (not (str/blank? detail)) (str code " must say something"))
    (is (> (count detail) 40)
        (str code "'s prose must explain the correction, not restate the code"))))

(deftest an-unknown-refusal-code-replays-as-a-refusal-rather-than-a-500
  (testing "a receipt written by an earlier version carrying a code this one
            does not know must still replay as the refusal it was — the evidence
            path is the worst possible place for an internal error"
    (is (not (str/blank? (statement/refusal-detail "invented-later"))))))

(deftest the-two-dispositions-are-recognised-by-their-predicates
  (is (statement/applied? "applied"))
  (is (statement/refused? "refused"))
  (is (not (statement/applied? "refused")))
  (is (= #{"applied" "refused"} (set statement/dispositions))))
