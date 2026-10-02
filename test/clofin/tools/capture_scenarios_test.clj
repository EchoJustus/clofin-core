(ns clofin.tools.capture-scenarios-test
  "The scenario roster, and the two things the reconciliation scenario adds to
  how a scenario decides it is replaying what its script says.

  Needs no service and no database: the roster is data, and the guards are
  exercised against a stubbed `store/execute!` and a stubbed database clock.

  **Which refusal.** A scenario step that is supposed to be refused by the
  database used to accept *any* failure as that refusal. UAT-007's raw
  statements carry placeholders a replay has to fill — a break, an entry, an
  actor — and a statement whose substitution went wrong is refused too, for a
  malformed identifier, without ever reaching the guard the script is about.
  `:expect-error` names the refusal; the tests below show a wrong one failing
  the step and the right one recording it (TASK-016, lesson **L-17**)."
  (:require [clofin.tools.capture.recorder :as rec]
            [clofin.tools.capture.scenarios :as scenarios]
            [clofin.tools.capture.store :as store]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(deftest the-roster-replays-four-scripts-in-order
  (is (= ["segregation-of-duties-refused"
          "settlement-batch-misbehaves"
          "evidence-pack-timeline"
          "reconciliation-breaks"]
         (mapv :id scenarios/all))
      "the walkthrough presents the scenarios in this order, and its index counts them")
  (testing "the fourth replays UAT-007, under the title the brief gives it"
    (let [recon (nth scenarios/all 3)]
      (is (= "docs/uat/UAT-007-reconciliation-and-breaks.md" (:source recon)))
      (is (= "Reconciliation: a statement, its breaks, and the corrections that close them"
             (:title recon)))
      (is (fn? (:run recon)))))
  (testing "every scenario names a script that exists, by the path the page links to"
    (doseq [{:keys [id source]} scenarios/all]
      (is (.isFile (io/file source)) (str id " names " source))))
  (testing "no two scenarios share an actor id: the capture database is shared, and an
            actor seeded twice is a seed that fails or a trail that is two scenarios'"
    (let [ids (mapcat (comp vals :people) (filter :people scenarios/all))]
      (is (seq ids) "non-vacuity: the roster seeds people at all")
      (is (= (count ids) (count (set ids)))))))

;; ---------------------------------------------------------------------------
;; Which refusal
;; ---------------------------------------------------------------------------

(def ^:private seed! @#'scenarios/seed!)

(defn- ctx [] {:rec (rec/recorder {:base-url "http://127.0.0.1:1"}) :conn ::no-connection})

(def ^:private refused-by-index
  {:ok false
   :error (str "ERROR: duplicate key value violates unique constraint \"recon_adjustment_posted_key\"\n"
               "  Detail: Key (break_id)=(89cd9319-436d-415e-8624-0b7a1a4d2b39) already exists.")
   :sqlstate "23505"})

(def ^:private refused-for-a-placeholder
  {:ok false
   :error "ERROR: invalid input syntax for type uuid: \"<BREAK>\""
   :sqlstate "22P02"})

(deftest a-refusal-step-records-only-the-refusal-it-is-about
  (testing "the negative control: a statement refused for a reason other than the
            guard the step is about stops the capture, naming both"
    (with-redefs [store/execute! (fn [_ _] refused-for-a-placeholder)]
      (let [c (ctx)
            e (try (seed! c {:id "s11-insert-refused" :title "t" :narrative "n"
                             :statement "insert …" :expect-refusal true
                             :expect-error #"recon_adjustment_posted_key"})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a refusal for a malformed placeholder must not pass as the index's refusal")
        (is (re-find #"recon_adjustment_posted_key" (ex-message e)))
        (is (re-find #"invalid input syntax for type uuid" (ex-message e)))
        (is (empty? (rec/steps (:rec c))) "and nothing was recorded"))))

  (testing "the refusal the step is about is recorded, with the database's own message"
    (with-redefs [store/execute! (fn [_ _] refused-by-index)]
      (let [c (ctx)
            step (seed! c {:id "s11-insert-refused" :title "t" :narrative "n"
                           :statement "insert …" :expect-refusal true
                           :expect-error #"recon_adjustment_posted_key"})]
        (is (= "sql" (:kind step)))
        (is (= refused-by-index (:result step))))))

  (testing "a step that names no refusal keeps its old meaning — any refusal — so the
            scenarios recorded before this guard replay unchanged"
    (with-redefs [store/execute! (fn [_ _] refused-for-a-placeholder)]
      (is (map? (seed! (ctx) {:id "superuser-refused" :title "t" :narrative "n"
                              :statement "insert …" :expect-refusal true})))))

  (testing "and a refusal that did not happen is still a stopped capture"
    (with-redefs [store/execute! (fn [_ _] {:ok true :rows 1})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected the database to refuse"
                            (seed! (ctx) {:id "x" :title "t" :narrative "n" :statement "…"
                                          :expect-refusal true
                                          :expect-error #"append-only"}))))))

;; ---------------------------------------------------------------------------
;; The period UAT-007 asks for
;; ---------------------------------------------------------------------------

(deftest the-statement-period-is-yesterday-to-tomorrow-in-utc
  (let [period @#'scenarios/statement-period]
    (doseq [[now expected] [["2026-10-02T13:59:10Z" {:from "2026-10-01T00:00:00Z"
                                                     :to   "2026-10-03T00:00:00Z"}]
                            ["2026-10-02T00:00:00Z" {:from "2026-10-01T00:00:00Z"
                                                     :to   "2026-10-03T00:00:00Z"}]
                            ["2026-10-02T23:59:59.999Z" {:from "2026-10-01T00:00:00Z"
                                                         :to   "2026-10-03T00:00:00Z"}]
                            ["2027-01-01T08:00:00Z" {:from "2026-12-31T00:00:00Z"
                                                     :to   "2027-01-02T00:00:00Z"}]]]
      (with-redefs [store/now (fn [_] (java.time.Instant/parse now))]
        (is (= expected (period ::no-connection)) now)))))
