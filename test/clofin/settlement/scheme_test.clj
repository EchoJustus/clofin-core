(ns clofin.settlement.scheme-test
  "The simulated scheme adapter.

  The property that matters is **predictability**. A simulation whose outcomes a
  reviewer cannot predict is a simulation nobody can review, and a test that
  asserted a distribution rather than a value would pass while the rule changed
  underneath it. Every assertion here names the expected outcome for a stated
  input."
  (:require [clofin.error :as err]
            [clofin.settlement.scheme :as scheme]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defn- instruction [account]
  {:id (random-uuid) :creditor-account account :creditor-name "Pacific Rim Logistics Pte Ltd"})

(def ^:private batch {:id #uuid "00000000-0000-0000-0000-0000000000bb" :scheme "SIM-RTGS"})

;; ---------------------------------------------------------------------------
;; The rule
;; ---------------------------------------------------------------------------

(deftest the-outcome-is-the-last-digit-of-the-synthetic-creditor-account
  (testing "0-6 settle"
    (doseq [d "0123456"]
      (is (= :settled (scheme/outcome-for (instruction (str "SG-SYNTH-8801234" d))))
          (str "…" d " must settle"))))
  (testing "7 and 8 return"
    (doseq [d "78"]
      (is (= :returned (scheme/outcome-for (instruction (str "SG-SYNTH-8801234" d))))
          (str "…" d " must return"))))
  (testing "9 produces no response at all — which is what a timeout is"
    (is (= :no-response (scheme/outcome-for (instruction "SG-SYNTH-88012349"))))))

(deftest an-account-not-ending-in-a-digit-produces-no-response
  (testing "the safe answer for `this rule does not know` is that the money's fate is
            unknown, not that it arrived"
    (is (= :no-response (scheme/outcome-for (instruction "SG-SYNTH-UNKNOWN"))))))

(deftest an-instruction-with-no-creditor-account-is-a-defect-not-a-default
  (doseq [account [nil "" "   "]]
    (is (thrown? Exception (scheme/outcome-for (instruction account)))
        (str "creditor account " (pr-str account) " must be refused, not defaulted"))))

(deftest partial-failure-is-producible-on-demand
  (testing "the whole point of the rule: a reviewer picks accounts and gets a known mix"
    (let [instructions [(instruction "SG-SYNTH-88012340")   ; settles
                        (instruction "SG-SYNTH-88012347")   ; returns
                        (instruction "SG-SYNTH-88012349")]  ; never answers
          responses (scheme/responses-for (scheme/simulated "SIM-RTGS") batch instructions)]
      (is (= 2 (count responses)) "the unanswered instruction produces no response at all")
      (is (= ["settled" "returned"] (mapv :kind responses)))
      (is (= scheme/return-reason (:reason (second responses))))
      (is (nil? (:reason (first responses))) "a settlement carries no reason"))))

;; ---------------------------------------------------------------------------
;; Determinism
;; ---------------------------------------------------------------------------

(deftest the-ack-reference-is-derived-from-the-batch-not-generated
  (testing "so resubmitting the same batch produces the same reference, and the replay key
            sees the second delivery as a duplicate rather than a new ack"
    (let [adapter (scheme/simulated "SIM-RTGS")]
      (is (= (scheme/submit-reference adapter batch)
             (scheme/submit-reference adapter batch)))
      (is (str/starts-with? (scheme/submit-reference adapter batch) "SIM-")))))

(deftest response-references-are-stable-across-calls
  (let [adapter (scheme/simulated "SIM-RTGS")
        instructions [(instruction "SG-SYNTH-88012340") (instruction "SG-SYNTH-88012347")]]
    (is (= (scheme/responses-for adapter batch instructions)
           (scheme/responses-for adapter batch instructions))
        "an adapter that answered differently on a retry could not be replayed by an auditor")))

(deftest every-simulated-reference-says-it-is-simulated
  (let [adapter (scheme/simulated "SIM-ACH")
        refs (into [(scheme/submit-reference adapter batch)]
                   (map :reference)
                   (scheme/responses-for adapter batch
                                         [(instruction "SG-SYNTH-88012340")
                                          (instruction "SG-SYNTH-88012347")]))]
    (is (every? #(str/starts-with? % "SIM-") refs)
        "a reference that could be mistaken for a real scheme's is a synthetic record
         reading as a real one")
    (is (str/includes? scheme/return-reason "SIM-"))))

(deftest the-adapter-reports-the-scheme-it-settles-for
  (is (= "SIM-RTGS" (scheme/scheme-id (scheme/simulated "SIM-RTGS"))))
  (is (= "SIM-ACH" (scheme/scheme-id (scheme/simulated "SIM-ACH")))))

(defspec the-outcome-is-a-pure-function-of-the-account 200
  (prop/for-all [digits (gen/vector (gen/elements "0123456789") 1 12)]
    (let [account (str "SG-SYNTH-" (str/join digits))
          i (instruction account)]
      (= (scheme/outcome-for i) (scheme/outcome-for i)))))

(defspec every-account-maps-to-exactly-one-of-three-outcomes 200
  (prop/for-all [digits (gen/vector (gen/elements "0123456789") 1 12)]
    (contains? #{:settled :returned :no-response}
               (scheme/outcome-for (instruction (str "SG-SYNTH-" (str/join digits)))))))

(defspec only-an-answering-instruction-produces-a-response 100
  (prop/for-all [accounts (gen/vector (gen/fmap #(str "SG-SYNTH-" (str/join %))
                                                (gen/vector (gen/elements "0123456789") 1 6))
                                      1 6)]
    (let [instructions (mapv instruction accounts)
          responses (scheme/responses-for (scheme/simulated "SIM-RTGS") batch instructions)
          answering (count (remove #(= :no-response (scheme/outcome-for %)) instructions))]
      (and (= answering (count responses))
           ;; A returned response always carries a reason: the schema refuses a
           ;; returned item without one, so an adapter that omitted it would
           ;; produce a response that cannot be recorded.
           (every? #(or (not= "returned" (:kind %)) (seq (:reason %))) responses)))))
