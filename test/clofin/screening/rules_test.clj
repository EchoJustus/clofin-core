(ns clofin.screening.rules-test
  "The screening rules engine (TASK-017 A-2, AC-17-8): exact matching, rules AND
  within an entry, entries OR across a list — by enumeration over field ×
  presence × equality, and a property that the order of entries and rules never
  changes the answer.

  Synthetic names throughout; the list is synthetic and the matching exact."
  (:require [clofin.screening.list :as screening-list]
            [clofin.screening.rules :as rules]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private instruction
  {:creditor-name    "Blocked Counterparty Ltd"
   :creditor-account "SG-SYNTH-99999999"
   :creditor-country "ZZ"})

(def ^:private values
  "For each field: the value the instruction carries, and one it does not."
  {:creditor-name    ["Blocked Counterparty Ltd" "Pacific Rim Logistics Pte Ltd"]
   :creditor-account ["SG-SYNTH-99999999" "SG-SYNTH-88012345"]
   :creditor-country ["ZZ" "SG"]})

(defn- entry [id & rules]
  {:id id :rules (vec (for [[field value] rules]
                        {:field field :operator :exact :value value}))})

(deftest ac-17-8-the-field-presence-equality-matrix
  (testing "every field, present or absent on the instruction, against an equal
            and an unequal rule value — 3 × 2 × 2 cases, each answered"
    (doseq [field (keys rules/fields)
            present? [true false]
            equal? [true false]]
      (let [[same other] (get values field)
            subject (if present? instruction (dissoc instruction field))
            rule-value (if equal? same other)
            expected (if (and present? equal?) :hit :clear)
            answer (rules/evaluate subject [(entry "E-1" [field rule-value])])]
        (is (= expected (:outcome answer))
            (str field " present=" present? " equal=" equal? " → " (pr-str answer)))
        (is (= (if (= :hit expected) ["E-1"] []) (:matched answer)))))))

(deftest ac-17-8-exact-means-exact
  (testing "no case folding, no trimming, no normalisation: a normalising matcher
            is a different operator and is not built"
    (doseq [variant ["blocked counterparty ltd" "BLOCKED COUNTERPARTY LTD"
                     " Blocked Counterparty Ltd" "Blocked Counterparty Ltd "
                     "Blocked  Counterparty Ltd" "Blocked Counterparty Ltd."]]
      (is (= :clear (:outcome (rules/evaluate {:creditor-name variant}
                                              [(entry "E-1" [:creditor-name "Blocked Counterparty Ltd"])])))
          (pr-str variant)))))

(deftest ac-17-8-rules-and-within-an-entry-entries-or-across-a-list
  (let [dual (entry "SYN-0004" [:creditor-name "Dual Rule Trading Co"] [:creditor-country "ZZ"])]
    (testing "an entry matches only when every rule does"
      (is (= :hit (:outcome (rules/evaluate {:creditor-name "Dual Rule Trading Co" :creditor-country "ZZ"} [dual]))))
      (is (= :clear (:outcome (rules/evaluate {:creditor-name "Dual Rule Trading Co" :creditor-country "SG"} [dual]))))
      (is (= :clear (:outcome (rules/evaluate {:creditor-name "Dual Rule Trading Co"} [dual])))
          "an instruction without a country matches no country rule"))
    (testing "a list is a hit when any entry matches, and names every one that did"
      (let [answer (rules/evaluate {:creditor-name "Dual Rule Trading Co" :creditor-country "ZZ"}
                                   [(entry "SYN-0003" [:creditor-country "ZZ"]) dual
                                    (entry "SYN-0001" [:creditor-name "Blocked Counterparty Ltd"])])]
        (is (= {:outcome :hit :matched ["SYN-0003" "SYN-0004"]} answer))))))

(deftest a-malformed-entry-is-a-defect-not-a-clear
  (testing "an entry with no rules would match everything (`every?` over
            nothing); one naming a field the engine does not read could never
            match. Either read as a decision would be a decision nobody took"
    (is (thrown? clojure.lang.ExceptionInfo (rules/evaluate instruction [{:id "E" :rules []}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rules/evaluate instruction [(entry "E" [:originator-name "x"])])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rules/evaluate instruction [{:id "E" :rules [{:field :creditor-name :operator :fuzzy
                                                                :value "x"}]}])))))

(deftest the-shipped-list-is-valid-and-screens-as-its-entries-say
  (let [shipped (screening-list/validate
                 (edn/read-string (slurp "resources/screening-lists/synthetic-2026-10-v1.edn")))]
    (is (= "synthetic-2026-10-v1" (:version shipped)))
    (is (= ["SYN-0001" "SYN-0002" "SYN-0003" "SYN-0004"] (mapv :id (:entries shipped))))
    (testing "the names the existing tests and UAT scripts use are clear against it"
      (is (= :clear (:outcome (rules/evaluate {:creditor-name "Pacific Rim Logistics Pte Ltd"
                                               :creditor-account "SG-SYNTH-88012345"
                                               :creditor-country "SG"}
                                              (:entries shipped))))))
    (is (= {:outcome :hit :matched ["SYN-0001"]}
           (rules/evaluate {:creditor-name "Blocked Counterparty Ltd"
                            :creditor-account "SG-SYNTH-88012345"} (:entries shipped))))
    (is (= {:outcome :hit :matched ["SYN-0003" "SYN-0004"]}
           (rules/evaluate {:creditor-name "Dual Rule Trading Co" :creditor-country "ZZ"
                            :creditor-account "SG-SYNTH-88012345"} (:entries shipped))))))

;; ---------------------------------------------------------------------------
;; The property: order never changes the answer
;; ---------------------------------------------------------------------------

(def ^:private gen-rule
  (gen/let [field (gen/elements (keys values))
            value (gen/elements (get values field))]
    {:field field :operator :exact :value value}))

(def ^:private gen-entries
  (gen/let [n (gen/choose 1 6)
            rule-sets (gen/vector (gen/vector gen-rule 1 3) n)]
    (vec (map-indexed (fn [i rs] {:id (format "E-%02d" i) :rules rs}) rule-sets))))

(def ^:private gen-instruction
  (gen/let [present (gen/vector gen/boolean 3)
            picks (gen/vector (gen/elements [0 1]) 3)]
    (into {} (keep (fn [[field p? i]] (when p? [field (nth (get values field) i)])))
          (map vector (keys values) present picks))))

(defspec ac-17-8-insertion-order-of-entries-and-rules-never-changes-the-answer 200
  (prop/for-all [subject gen-instruction
                 entries gen-entries
                 seed gen/nat]
    (let [rnd (java.util.Random. seed)
          shuffle* (fn [xs] (let [l (java.util.ArrayList. ^java.util.Collection xs)]
                              (java.util.Collections/shuffle l rnd)
                              (vec l)))
          reordered (shuffle* (map #(update % :rules shuffle*) entries))]
      (= (rules/evaluate subject entries) (rules/evaluate subject reordered)))))
