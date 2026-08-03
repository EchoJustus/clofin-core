(ns clofin.audit-test
  "The audit vocabulary and the digest.

  The digest is the load-bearing part: two different subjects must never
  produce one digest, and one subject must always produce the same digest
  however it was assembled. The first property is what makes an audit event
  evidence; the second is what stops a false positive — a digest that changed
  because a map was built in a different order would read exactly like a record
  that had been altered."
  (:require [clofin.audit :as audit]
            [clofin.money :as money]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import [java.time Instant LocalDate]))

(defn- instruction
  [& {:keys [amount status] :or {amount (money/of "SGD" 125000) status :draft}}]
  {:id (random-uuid) :organisation-id (random-uuid)
   :debtor-account-id (random-uuid)
   :creditor-name "Pacific Rim Logistics Pte Ltd"
   :creditor-account "SG-SYNTH-88012345"
   :amount amount
   :value-date (LocalDate/parse "2026-08-10")
   :purpose-code "SUPP" :status status
   :created-by (random-uuid) :created-at (Instant/now) :reverses-id nil})

;; ---------------------------------------------------------------------------
;; Digests
;; ---------------------------------------------------------------------------

(deftest a-digest-is-version-tagged
  (testing "so a later change to the canonical form cannot make two incomparable digests look comparable"
    (let [d (audit/digest {:a 1})]
      (is (str/starts-with? d (str audit/canonicalisation-version ":")))
      (is (re-matches #"v1:[0-9a-f]{64}" d)))))

(deftest nil-digests-to-nil
  (testing "a null before-digest is what distinguishes a creation from an update"
    (is (nil? (audit/digest nil)))))

(deftest the-same-value-always-digests-the-same
  (let [pi (instruction)]
    (is (= (audit/digest (audit/instruction-subject pi))
           (audit/digest (audit/instruction-subject pi))))))

(deftest map-construction-order-does-not-change-a-digest
  (testing "a digest that moved because a map was built differently would read as tampering"
    (is (= (audit/digest {:a 1 :b 2 :c 3})
           (audit/digest (into {} [[:c 3] [:a 1] [:b 2]]))
           (audit/digest (sorted-map :b 2 :c 3 :a 1))))))

(deftest a-changed-field-changes-the-digest
  (let [pi (instruction)]
    (doseq [[field value] [[:amount (money/of "SGD" 125001)]
                           [:creditor-name "Someone Else Pte Ltd"]
                           [:creditor-account "SG-SYNTH-99999999"]
                           [:status :pending-approval]
                           [:value-date (LocalDate/parse "2026-08-11")]
                           [:purpose-code "TRAD"]
                           [:debtor-account-id (random-uuid)]]]
      (is (not= (audit/digest (audit/instruction-subject pi))
                (audit/digest (audit/instruction-subject (assoc pi field value))))
          (str "changing " field " must change the digest")))))

(deftest a-currency-change-alone-changes-the-digest
  (testing "same minor units, different currency — a digest that missed this would prove nothing about money"
    (is (not= (audit/digest (audit/instruction-subject (instruction :amount (money/of "SGD" 125000))))
              (audit/digest (audit/instruction-subject (instruction :amount (money/of "JPY" 125000))))))))

(deftest fields-outside-the-projection-do-not-change-the-digest
  (testing "a value read from a row and one built in memory differ in incidental keys"
    (let [pi (instruction)]
      (is (= (audit/digest (audit/instruction-subject pi))
             (audit/digest (audit/instruction-subject (assoc pi :permitted-transitions [:submit]
                                                             :created-at (Instant/now)))))))))

(deftest normalise-renders-every-domain-type-the-canonicaliser-refuses
  (testing "the canonicaliser throws on an unknown type rather than falling back to str, so this must be total"
    (is (some? (audit/digest {:id (random-uuid)
                              :when (Instant/now)
                              :date (LocalDate/now)
                              :status :draft
                              :amount (money/of "SGD" 1)
                              :lines [{:direction :debit} {:direction :credit}]
                              :tags #{:a :b}
                              :absent nil})))))

(deftest a-set-digests-the-same-however-it-was-ordered
  (is (= (audit/digest {:roles #{:approver :operator}})
         (audit/digest {:roles #{:operator :approver}}))))

(defspec two-different-instructions-never-share-a-digest 200
  (prop/for-all [minor-a gen/nat
                 minor-b gen/nat]
    (let [a (audit/digest (audit/instruction-subject (instruction :amount (money/of "SGD" (inc minor-a)))))
          b (audit/digest (audit/instruction-subject (instruction :amount (money/of "SGD" (inc minor-b)))))]
      ;; The ids differ on every call, so equal digests would mean the
      ;; projection had stopped covering identity.
      (not= a b))))

;; ---------------------------------------------------------------------------
;; The event
;; ---------------------------------------------------------------------------

(defn- valid-event
  [& {:as overrides}]
  (merge {:organisation-id (random-uuid)
          :actor-id        (random-uuid)
          :action          "payment.approved"
          :subject-type    "payment-instruction"
          :subject-id      (random-uuid)
          :before          nil
          :after           {:status "approved"}
          :correlation-id  "corr-1"}
         overrides))

(deftest an-event-digests-its-before-and-after
  (let [ev (audit/event (valid-event :before {:status "pending-approval"}))]
    (is (some? (:before-digest ev)))
    (is (some? (:after-digest ev)))
    (is (not= (:before-digest ev) (:after-digest ev)))))

(deftest an-event-never-carries-the-payload
  (testing "C-09, ADR-0016: a counterparty name must not reach the audit table"
    (let [ev (audit/event (valid-event :after (audit/instruction-subject (instruction))))]
      (is (not (str/includes? (pr-str ev) "Pacific Rim"))
          "the creditor name is in the digest input and must not be in the event")
      (is (not (str/includes? (pr-str ev) "SG-SYNTH"))))))

(deftest an-unknown-action-is-refused
  (testing "default deny reaching the audit trail: an unconstrained vocabulary cannot be queried completely"
    (doseq [action ["payment.exploded" "" nil "PAYMENT.APPROVED"]]
      (is (thrown? Exception (audit/event (valid-event :action action)))
          (str "action " (pr-str action) " must be refused")))))

(deftest an-unknown-subject-type-is-refused
  (is (thrown? Exception (audit/event (valid-event :subject-type "invoice")))))

(deftest an-event-must-name-its-subject-and-organisation
  (is (thrown? Exception (audit/event (valid-event :subject-id nil))))
  (is (thrown? Exception (audit/event (valid-event :subject-id "not-a-uuid"))))
  (is (thrown? Exception (audit/event (valid-event :organisation-id nil)))))

(deftest an-event-may-have-no-actor
  (testing "the column is nullable for the bootstrap case alone, and the value must survive it"
    (is (nil? (:actor-id (audit/event (valid-event :actor-id nil)))))))

(deftest every-action-names-a-subject-type-that-exists
  (doseq [action audit/actions]
    (let [subject-type (if (str/starts-with? action "approval.")
                         "approval" "payment-instruction")]
      (is (some? (audit/event (valid-event :action action :subject-type subject-type)))
          (str action " must be recordable")))))
