(ns clofin.payments.state-test
  "The lifecycle is a value, so it is tested by enumeration rather than by
  sampling.

  `AC-10` from docs/briefs/002-TASK-payment-instruction-lifecycle.md asks for
  exactly this: every (state, event) pair walked, permitted pairs succeeding
  and every other pair raising `:conflict`. A test that picked interesting
  cases would cover the transitions someone thought of, which is the same set
  the implementation already handles."
  (:require [clofin.error :as err]
            [clofin.payments.state :as state]
            [clojure.test :refer [deftest is testing]]))

(defn- caught
  "The exception `f` throws, or nil."
  [f]
  (try (f) nil (catch Exception t t)))

(defn- error-type
  [f]
  (some-> (caught f) ex-data :clofin/error))

;; ---------------------------------------------------------------------------
;; AC-10 — the whole transition matrix
;; ---------------------------------------------------------------------------

(deftest ac-10-every-state-event-pair-behaves-as-the-table-declares
  (testing "permitted pairs reach the declared state; every other pair is a conflict"
    (doseq [state state/states
            event state/events]
      (let [declared (get-in state/transitions [state event])]
        (if declared
          (is (= declared (state/transition state event))
              (str (name state) " + " (name event) " must reach " (name declared)))
          (is (= :conflict (error-type #(state/transition state event)))
              (str (name state) " + " (name event)
                   " is not in the table and must raise :conflict")))))))

(deftest ac-10-the-matrix-is-not-trivially-empty
  (testing "a table that had lost its entries would pass the enumeration above"
    (is (= 9 (count state/states)))
    (is (= 9 (count state/events)))
    (is (= 10 (reduce + (map count (vals state/transitions))))
        "ten permitted pairs out of eighty-one — the other seventy-one conflict")))

(deftest permitted?-and-transition-cannot-disagree
  (doseq [state state/states
          event state/events]
    (is (= (state/permitted? state event)
           (nil? (error-type #(state/transition state event))))
        (str "permitted? and transition disagree about "
             (name state) " + " (name event)))))

(deftest every-destination-is-itself-a-state
  (testing "no transition points at a state the table does not define"
    (doseq [[state moves] state/transitions
            [event destination] moves]
      (is (contains? state/transitions destination)
          (str (name state) " + " (name event) " leads to " (name destination)
               ", which is not a state")))))

;; ---------------------------------------------------------------------------
;; Terminality
;; ---------------------------------------------------------------------------

(deftest terminal-states-are-exactly-those-with-no-way-out
  (testing "terminality is derived from the table, never declared beside it"
    (is (= #{:settled :rejected :cancelled :failed :returned}
           (into #{} (filter state/terminal?) state/states))))

  (testing "a terminal state permits nothing at all"
    (doseq [state (filter state/terminal? state/states)]
      (is (empty? (state/permitted-events state)))
      (doseq [event state/events]
        (is (= :conflict (error-type #(state/transition state event)))))))

  (testing "every non-terminal state permits something, or it is a dead end nobody meant"
    (doseq [state (remove state/terminal? state/states)]
      (is (seq (state/permitted-events state))))))

(deftest settled-is-terminal-because-a-settled-payment-is-followed-not-changed
  (testing "DOMAIN_MODEL §3 rule 4 — reversal is a new instruction, not a transition"
    (is (state/terminal? :settled))
    (is (state/reversible? :settled))))

;; ---------------------------------------------------------------------------
;; AC-5 — a refusal says what was attempted
;; ---------------------------------------------------------------------------

(deftest ac-5-a-refused-transition-names-what-was-attempted
  (let [t (caught #(state/transition :settled :submit))
        data (ex-data t)]
    (is (= :conflict (:clofin/error data)))
    (is (= "settled" (:instruction-status data)))
    (is (= "submit" (:attempted data)))
    (is (= [] (:permitted data)))
    (testing "the message alone is enough for a human reading a log"
      (is (= "Cannot submit a payment instruction that is settled" (ex-message t))))))

(deftest a-refusal-from-a-non-terminal-state-lists-what-would-have-worked
  (let [data (ex-data (caught #(state/transition :draft :release)))]
    (is (= "draft" (:instruction-status data)))
    (is (= "release" (:attempted data)))
    (is (= ["cancel" "submit"] (:permitted data))
        "sorted, so the same refusal reads the same way every time")))

;; ---------------------------------------------------------------------------
;; The happy path the increment delivers
;; ---------------------------------------------------------------------------

(deftest ac-4-submitting-a-draft-reaches-pending-approval-and-stops
  (is (= :pending-approval (state/transition :draft :submit)))
  (testing "approval is TASK-003 — the transition exists, and nothing here drives it"
    (is (state/permitted? :pending-approval :approve))))

(deftest a-draft-and-an-approved-instruction-can-both-be-cancelled
  (is (= :cancelled (state/transition :draft :cancel)))
  (is (= :cancelled (state/transition :approved :cancel)))
  (testing "a released instruction cannot: value has left, and the correction is a reversal"
    (is (= :conflict (error-type #(state/transition :released :cancel))))))

;; ---------------------------------------------------------------------------
;; Unknown states
;; ---------------------------------------------------------------------------

(deftest an-unknown-status-is-a-defect-not-a-quiet-terminal-state
  (testing "treating one as terminal would refuse every operation on the row and
            look, from outside, exactly like a correctly settled payment"
    (is (= :validation (error-type #(state/terminal? :in-flight))))
    (is (= :validation (error-type #(state/permitted? :in-flight :submit))))
    (is (= :validation (error-type #(state/transition :in-flight :submit))))
    (is (= :validation (error-type #(state/mutable? :in-flight))))
    (is (= :validation (error-type #(state/reversible? :in-flight)))))

  (testing "known? answers without raising, which is what a filter needs"
    (is (false? (state/known? :in-flight)))
    (is (true? (state/known? :draft)))))

;; ---------------------------------------------------------------------------
;; Rules about status that are not transitions (ADR-0014)
;; ---------------------------------------------------------------------------

(deftest only-a-draft-is-mutable
  (testing "DOMAIN_MODEL §1 — mutable while draft, immutable in substance thereafter"
    (is (state/mutable? :draft))
    (doseq [state (disj (set state/states) :draft)]
      (is (not (state/mutable? state)) (str (name state) " must not be amendable")))))

(deftest assert-mutable-refuses-in-the-same-shape-as-a-refused-transition
  (is (= :draft (state/assert-mutable! :draft)))
  (let [t (caught #(state/assert-mutable! :pending-approval))
        data (ex-data t)]
    (is (= :conflict (:clofin/error data)))
    (is (= "pending-approval" (:instruction-status data)))
    (is (= "amend" (:attempted data)))
    (is (= ["draft"] (:mutable-in data)))))

(deftest only-a-settled-instruction-is-reversible
  (testing "PR-043 — a settled payment can be reversed"
    (is (state/reversible? :settled))
    (doseq [state (disj (set state/states) :settled)]
      (is (not (state/reversible? state))
          (str (name state) " must not be reversible")))))

(deftest assert-reversible-refuses-with-the-state-it-found
  (is (= :settled (state/assert-reversible! :settled)))
  (let [data (ex-data (caught #(state/assert-reversible! :draft)))]
    (is (= :conflict (:clofin/error data)))
    (is (= "draft" (:instruction-status data)))
    (is (= "reverse" (:attempted data)))
    (is (= ["settled"] (:reversible-in data)))))

(deftest amend-in-place-and-the-amend-event-are-different-things
  (testing "ADR-0014 — PATCH is governed by mutable-states, not by the amend event"
    (is (state/mutable? :draft))
    (is (not (state/permitted? :draft :amend))
        "a draft amendment is not a transition; it leaves the instruction in draft")
    (is (state/permitted? :pending-approval :amend)
        "the amend *event* belongs to TASK-003's approval workflow (PR-014)")
    (is (not (state/mutable? :pending-approval))
        "and PATCH must still refuse a submitted instruction")))

;; ---------------------------------------------------------------------------
;; The error vocabulary this namespace relies on
;; ---------------------------------------------------------------------------

(deftest a-refused-transition-is-rendered-as-409
  (testing "the state machine's refusal must reach a caller as a conflict"
    (is (= 409 (:status (get err/error-types :conflict))))))
