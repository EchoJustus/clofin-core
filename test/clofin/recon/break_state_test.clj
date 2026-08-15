(ns clofin.recon.break-state-test
  "The break lifecycle, tested **by enumeration rather than by sampling**.

  The same discipline `clofin.payments.state-test` keeps, and for the same
  reason: the lifecycle is a value, so every (state, event) pair can be walked
  and a pair nobody thought to write a case for is still covered. A test that
  sampled three transitions would pass on a table with a fourth nobody meant to
  add."
  (:require [clofin.recon.break-state :as break-state]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; The whole matrix
;; ---------------------------------------------------------------------------

(deftest every-state-event-pair-is-either-permitted-by-the-table-or-refused
  (doseq [state break-state/states
          event break-state/events]
    (testing (str state " / " event)
      (if (contains? (get break-state/transitions state) event)
        (is (= (get-in break-state/transitions [state event])
               (break-state/transition state event))
            "a permitted pair goes exactly where the table says")
        (let [t (try (break-state/transition state event) nil (catch Exception e e))]
          (is (some? t) (str state " must not permit " event))
          (is (= :conflict (:clofin/error (ex-data t)))
              "a refused transition is a conflict, not a validation error")
          (is (= (name state) (:break-state (ex-data t)))
              "the refusal names the state the break is actually in")
          (is (= (mapv name (break-state/permitted-events state))
                 (:permitted (ex-data t)))
              "and what would have been permitted instead — a caller refused with
               409 alone is left guessing between `somebody already resolved this`
               and `this was never assignable`"))))))

(deftest an-unknown-state-is-a-defect-rather-than-a-terminal-one
  (testing "treating an unrecognised state as terminal would refuse every
            operation on the row and look, from outside, exactly like a
            correctly resolved break"
    (doseq [f [#(break-state/terminal? :vanished)
               #(break-state/permitted-events :vanished)
               #(break-state/transition :vanished :assign)
               #(break-state/reassignable? :vanished)]]
      (let [t (try (f) nil (catch Exception e e))]
        (is (some? t))
        (is (= :validation (:clofin/error (ex-data t))))))))

;; ---------------------------------------------------------------------------
;; The shape of the table itself
;; ---------------------------------------------------------------------------

(deftest the-terminal-set-is-derived-and-is-not-a-second-list
  (is (= (into #{} (filter break-state/terminal?) break-state/states)
         (set break-state/terminal-states))
      "a hand-kept terminal list is the thing that goes stale while every test
       that reads it keeps passing")
  (is (= #{:resolved} (set break-state/terminal-states))
      "and today exactly one state is terminal"))

(deftest a-break-begins-open-and-nothing-leads-back-to-it
  (is (= :open break-state/initial-state))
  (is (empty? (for [[_ events] break-state/transitions
                    [_ to] events
                    :when (= :open to)]
                to))
      "no arrow returns a break to open: a break that has been picked up has
       been picked up"))

(deftest every-state-is-reachable-from-the-initial-one
  ;; A state nothing can reach is a schema path with no product path behind it
  ;; (standing lesson **L-10**), and it would still be drawn on the generated
  ;; diagram as though an operator could get there.
  (let [reachable (loop [seen #{break-state/initial-state}
                         queue [break-state/initial-state]]
                    (if-let [state (first queue)]
                      (let [next-states (set (vals (get break-state/transitions state)))
                            fresh (remove seen next-states)]
                        (recur (into seen fresh) (into (vec (rest queue)) fresh)))
                      seen))]
    (is (= (set break-state/states) reachable)
        (str "unreachable states: "
             (pr-str (remove reachable break-state/states))))))

(deftest every-event-leaves-some-state
  (is (= (set break-state/events)
         (into #{} (mapcat keys) (vals break-state/transitions)))))

;; ---------------------------------------------------------------------------
;; Assignment: an arrow from one state, a rule about status from another
;; ---------------------------------------------------------------------------

(deftest assigning-an-open-break-is-the-transition-and-reassigning-is-not
  (testing "one endpoint drives both by reading these two values rather than by
            testing a status — the arrangement ADR-0014 already applies to
            amend-versus-:amend"
    (is (break-state/permitted? :open :assign)
        "assigning an open break moves it to investigating")
    (is (= :investigating (break-state/transition :open :assign)))
    (is (not (break-state/reassignable? :open))
        "so `open` is not *also* reassignable in place; it would be two rules for
         one act")
    (is (not (break-state/permitted? :investigating :assign))
        "there is no self-arrow: reassigning is not a transition")
    (is (break-state/reassignable? :investigating))))

(deftest a-resolved-break-can-be-neither-assigned-nor-reassigned
  (testing "who resolved what is history, and history is not reassigned"
    (is (not (break-state/permitted? :resolved :assign)))
    (is (not (break-state/reassignable? :resolved)))
    (let [t (try (break-state/assert-assignable! :resolved) nil (catch Exception e e))]
      (is (some? t))
      (is (= :conflict (:clofin/error (ex-data t))))
      (is (= ["investigating" "open"] (sort (:assignable-in (ex-data t))))
          "the refusal names both halves of the rule, derived rather than listed"))))

(deftest assert-assignable-permits-exactly-the-states-either-half-covers
  (doseq [state break-state/states]
    (let [expected (or (break-state/permitted? state :assign)
                       (break-state/reassignable? state))
          ok? (try (break-state/assert-assignable! state) true (catch Exception _ false))]
      (is (= expected ok?)
          (str state ": assert-assignable! must agree with the two values it reads")))))

(deftest reassignable-states-are-states-the-lifecycle-knows
  (is (every? break-state/known? break-state/reassignable-states)
      "a rule about a state the table does not contain is a rule about nothing")
  (is (not-any? break-state/terminal? break-state/reassignable-states)
      "a terminal break's owner of record is not rewritten"))
