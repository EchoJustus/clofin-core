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

;; ---------------------------------------------------------------------------
;; The subjects TASK-005 added
;; ---------------------------------------------------------------------------

(defn- organisation []
  {:id (random-uuid) :legal-name "Meridian Freight Holdings Pte Ltd"
   :short-name "meridian" :status :active})

(defn- account []
  {:id (random-uuid) :organisation-id (random-uuid) :code "1100-CLIENT-FUNDS"
   :name "Client funds — pooled" :type :asset :currency "SGD" :status :active})

(defn- journal-entry [& {:keys [lines]}]
  (let [debit (random-uuid) credit (random-uuid)]
    {:id (random-uuid) :organisation-id (random-uuid)
     :occurred-at (Instant/parse "2026-08-04T09:00:00Z")
     :narrative "Client funds received"
     :reference {:type :opening-balance :id (random-uuid)}
     :lines (or lines
                [{:account-id debit  :direction :debit  :amount (money/of "SGD" 125000)}
                 {:account-id credit :direction :credit :amount (money/of "SGD" 125000)}])}))

(deftest a-changed-organisation-field-changes-the-digest
  (let [org (organisation)]
    (doseq [[field value] [[:legal-name "Someone Else Pte Ltd"]
                           [:short-name "elsewhere"]
                           [:status :suspended]
                           [:id (random-uuid)]]]
      (is (not= (audit/digest (audit/organisation-subject org))
                (audit/digest (audit/organisation-subject (assoc org field value))))
          (str "changing " field " must change the digest")))))

(deftest a-changed-account-field-changes-the-digest
  (let [acct (account)]
    (doseq [[field value] [[:code "1200-OTHER"]
                           [:name "Renamed"]
                           [:type :liability]
                           [:currency "JPY"]
                           ;; Freezing and closing are account state changes.
                           ;; When they gain events of their own, their before
                           ;; and after digests have to differ.
                           [:status :frozen]
                           [:organisation-id (random-uuid)]]]
      (is (not= (audit/digest (audit/account-subject acct))
                (audit/digest (audit/account-subject (assoc acct field value))))
          (str "changing " field " must change the digest")))))

(deftest a-journal-entry-digest-covers-its-lines
  (testing "an entry digest that covered only the header would be identical for two entries moving different money"
    (let [entry (journal-entry)
          moved (update-in entry [:lines 0 :amount] (constantly (money/of "SGD" 125001)))
          elsewhere (assoc-in entry [:lines 0 :account-id] (random-uuid))
          flipped (-> entry
                      (assoc-in [:lines 0 :direction] :credit)
                      (assoc-in [:lines 1 :direction] :debit))]
      (doseq [[label variant] [["amount" moved] ["account" elsewhere] ["direction" flipped]]]
        (is (not= (audit/digest (audit/journal-entry-subject entry))
                  (audit/digest (audit/journal-entry-subject variant)))
            (str "a changed line " label " must change the entry digest"))))))

(deftest a-journal-entry-digest-covers-its-header
  (let [entry (journal-entry)]
    (doseq [[field value] [[:narrative "Something else entirely"]
                           [:occurred-at (Instant/parse "2026-08-05T09:00:00Z")]
                           [:reference {:type :reversal :id (random-uuid)}]
                           [:organisation-id (random-uuid)]]]
      (is (not= (audit/digest (audit/journal-entry-subject entry))
                (audit/digest (audit/journal-entry-subject (assoc entry field value))))
          (str "changing " field " must change the digest")))))

(deftest a-journal-entry-digests-the-same-whether-it-was-posted-or-read-back
  (testing "`recorded_at` is assigned by the database, so it is outside the projection deliberately"
    (let [entry (journal-entry)]
      (is (= (audit/digest (audit/journal-entry-subject entry))
             (audit/digest (audit/journal-entry-subject
                            (assoc entry :recorded-at (Instant/now)))))))))

(deftest the-new-subjects-are-nil-safe-like-the-existing-ones
  (testing "nil in, nil out — the same shape `instruction-subject` has, so a creation's before is a null digest"
    (is (nil? (audit/organisation-subject nil)))
    (is (nil? (audit/account-subject nil)))
    (is (nil? (audit/journal-entry-subject nil)))))

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

;; ---------------------------------------------------------------------------
;; The vocabulary and the subject each term is about
;; ---------------------------------------------------------------------------

(defn- subject-type-for
  "The subject type an action names, derived *here* rather than read from
  `clofin.audit`.

  Every action is `<subject>.<past-tense-verb>`, and — with one deliberate
  exception — the prefix *is* the subject type. The exception is `payment.*`,
  whose subject type is spelt `payment-instruction` because the row it
  addresses is the instruction record.

  Kept as an independent second implementation on purpose. Since finding
  **A-015** the rule is enforced by `clofin.audit/subject-type-for`, and a test
  that called that function would be asking the rule to confirm itself: every
  pair would agree by construction, including the pairs a mis-stated rule
  produces. Two implementations that must agree is the assertion."
  [action]
  (let [prefix (first (str/split action #"\." 2))]
    (if (= "payment" prefix) "payment-instruction" prefix)))

(deftest every-action-names-a-subject-type-that-exists
  (doseq [action audit/actions]
    (let [subject-type (subject-type-for action)]
      (is (= subject-type (audit/subject-type-for action))
          (str "the shipped derivation and this test must agree on " action))
      (is (contains? audit/subject-types subject-type)
          (str action " names subject type " (pr-str subject-type)
               ", which is not in the vocabulary"))
      (is (some? (audit/event (valid-event :action action
                                           :subject-type subject-type
                                           ;; Bootstrap actions may carry no
                                           ;; actor; every other action must,
                                           ;; and `valid-event` supplies one.
                                           :actor-id (random-uuid))))
          (str action " must be recordable")))))

(deftest a-015-an-action-with-the-wrong-subject-type-is-refused
  (testing "two individually valid values are not a valid pair"
    (let [t (try (audit/event (valid-event :action "payment.approved"
                                           :subject-type "account"))
                 nil (catch Exception e e))]
      (is (some? t)
          "`payment.approved` about an `account` was accepted until A-015: the two
           vocabularies were checked independently and never against each other")
      (is (= :validation (:clofin/error (ex-data t))))
      (is (= "payment-instruction" (:expected (ex-data t))))))

  (testing "and the rule holds across the whole vocabulary, not one sampled pair"
    (doseq [action      audit/actions
            subject-type audit/subject-types
            :when (not= subject-type (subject-type-for action))]
      (is (thrown? Exception (audit/event (valid-event :action action
                                                       :subject-type subject-type)))
          (str action " must not be recordable about a " subject-type)))))

(deftest the-writes-this-brief-covers-are-in-the-vocabulary
  (testing "TASK-005: organisation creation, account opening and journal posting each have a term"
    (doseq [action ["organisation.created" "account.created" "journal-entry.posted"]]
      (is (contains? audit/actions action)))
    (doseq [subject-type ["organisation" "account" "journal-entry"]]
      (is (contains? audit/subject-types subject-type)))))

;; ---------------------------------------------------------------------------
;; The bootstrap identity — AC-1
;; ---------------------------------------------------------------------------

(deftest the-bootstrap-action-may-have-no-actor
  (testing "`POST /organisations` is unauthenticated: no actor exists before the first organisation"
    (is (nil? (:actor-id (audit/event (valid-event :action "organisation.created"
                                                   :subject-type "organisation"
                                                   :actor-id nil))))
        "and the null must survive the round trip rather than becoming a placeholder")))

(deftest an-actorless-event-outside-the-bootstrap-is-refused
  (testing "standing lesson L-6: `a null actor_id means the bootstrap` is enforced, not merely commented"
    (doseq [action (remove audit/bootstrap-actions audit/actions)]
      (is (thrown? Exception
                   (audit/event (valid-event :action action
                                             :subject-type (subject-type-for action)
                                             :actor-id nil)))
          (str action " must name the actor that caused it — an unattributed "
               "state change is the half of C-05 that says who")))))

(deftest every-bootstrap-action-is-itself-a-known-action
  (testing "a term misspelt here would silently stop exempting anything, or exempt something unnamed"
    (is (every? audit/actions audit/bootstrap-actions))
    (is (= #{"organisation.created"} (set audit/bootstrap-actions))
        "the bootstrap is one endpoint; widening this set is a control decision, not a detail")))
