(ns clofin.tools.diagrams-test
  "The generated diagrams are checked against their sources **in both
  directions**, which is the only way a guard of this shape is worth having.

  Standing lesson **L-6**: a guard that asserts only the direction its author
  was thinking about passes green while the thing it guards is false. The
  enum-drift guard that Master Control praised as the remedy for exactly this
  checked one of two copies of `subjectType` and missed the stale one. So every
  comparison here runs both ways: nothing in the source may be missing from the
  drawing, and nothing in the drawing may be absent from the source."
  (:require [clofin.payments.state :as state]
            [clofin.recon.adjustment :as adjustment]
            [clofin.recon.break-state :as break-state]
            [clofin.tools.diagrams :as diagrams]
            [clofin.tools.markdown :as md]
            [clofin.tools.mermaid :as mmd]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def root ".")

;; ---------------------------------------------------------------------------
;; Reading a generated diagram back
;; ---------------------------------------------------------------------------
;;
;; The parsers below deliberately do not share code with the generator. A
;; round-trip through the generator's own emitter would prove the emitter is
;; self-consistent and nothing else.

(defn- mermaid-body
  "The lines inside the first ```mermaid fence of a generated artifact."
  [content]
  (let [lines (str/split content #"\n" -1)
        start (first (keep-indexed (fn [i l] (when (= "```mermaid" (str/trim l)) i)) lines))]
    (is (some? start) "the artifact contains a mermaid fence")
    (vec (take-while #(not= "```" (str/trim %)) (drop (inc start) lines)))))

(defn- state-aliases
  "`{node-id → status}` from the `state \"…\" as …` declarations."
  [body]
  (into {} (for [line body
                 :let [[_ label id] (re-find #"^\s*state\s+\"([^\"]+)\"\s+as\s+(\S+)\s*$" line)]
                 :when label]
             [id (keyword label)])))

(defn- lifecycle-edges
  "`#{[from event to]}` from the labelled arrows, mapped back through the aliases."
  [body]
  (let [alias (state-aliases body)]
    (into #{} (for [line body
                    :let [[_ from to event]
                          (re-find #"^\s*(\S+)\s+-->\s+(\S+)\s+:\s+(\S+)\s*$" line)]
                    :when from]
                [(get alias from) (keyword event) (get alias to)]))))

(defn- anchor-edges
  "`{:initial #{…} :terminal #{…}}` — the unlabelled `[*]` arrows."
  [body]
  (let [alias (state-aliases body)]
    {:initial  (into #{} (for [line body
                               :let [[_ to] (re-find #"^\s*\[\*\]\s+-->\s+(\S+)\s*$" line)]
                               :when to]
                           (get alias to)))
     :terminal (into #{} (for [line body
                               :let [[_ from] (re-find #"^\s*(\S+)\s+-->\s+\[\*\]\s*$" line)]
                               :when from]
                           (get alias from)))}))

(defn- flow-nodes
  "`{node-id → label}` for every `id[\"label\"]` declaration."
  [body]
  (into {} (for [line body
                 :let [[_ id label] (re-find #"^\s*(\w+)\[\"([^\"]*)\"\]\s*$" line)]
                 :when id]
             [id label])))

(defn- flow-edges
  [body]
  (into #{} (for [line body
                  :let [[_ from to] (re-find #"^\s*(\w+)\s+-->\s+(\w+)\s*$" line)]
                  :when from]
              [from to])))

(def generated (delay (diagrams/artifacts root)))

(defn- artifact [path]
  (let [content (get @generated path)]
    (is (some? content) (str path " is one of the generated artifacts"))
    content))

;; ---------------------------------------------------------------------------
;; AC-3 — the lifecycle diagram against the lifecycle table, both ways
;; ---------------------------------------------------------------------------

(deftest ac-3-the-lifecycle-diagram-and-the-transition-table-agree-in-both-directions
  (let [body     (mermaid-body (artifact "docs/diagrams/payment-lifecycle.md"))
        drawn    (lifecycle-edges body)
        declared (into #{} (for [[from events] state/transitions
                                 [event to]    events]
                             [from event to]))]
    (testing "every permitted pair in the table is drawn"
      (is (empty? (set/difference declared drawn))
          (str "transitions the table permits and the diagram omits: "
               (pr-str (set/difference declared drawn))
               " — run `make diagrams`.")))
    (testing "nothing is drawn that the table does not permit"
      (is (empty? (set/difference drawn declared))
          (str "transitions the diagram invents: " (pr-str (set/difference drawn declared)))))

    (testing "every state appears, and no other"
      (is (= (set (keys state/transitions))
             (set (vals (state-aliases body))))))

    (testing "every event appears, and no other"
      (is (= (set state/events) (into #{} (map second) drawn))))

    (testing "the start marker names the initial state, and only it"
      (is (= #{state/initial-state} (:initial (anchor-edges body)))))

    (testing "the terminal set is the derived one, and is not a second list"
      ;; A state with no outgoing arrow, and nothing else, ends at `[*]`.
      (is (= (into #{} (filter state/terminal?) (keys state/transitions))
             (:terminal (anchor-edges body)))))

    (testing "a state drawn as terminal has no outgoing transition in the diagram either"
      (doseq [s (:terminal (anchor-edges body))]
        (is (empty? (filter #(= s (first %)) drawn))
            (str s " is drawn as terminal and also has an outgoing arrow."))))))

(deftest the-lifecycle-diagram-carries-no-arrow-that-is-not-a-transition
  ;; The hand-drawn version carried a `reverse` arrow from `settled` to a "new
  ;; reversal instruction". It was never in `transitions` — `reversible-states`
  ;; is a rule about status, not an edge — and a generated drawing cannot
  ;; contain it. This asserts the specific thing that used to be wrong.
  (let [body (mermaid-body (artifact "docs/diagrams/payment-lifecycle.md"))]
    (is (not (str/includes? (str/join "\n" body) "reverse"))
        "`reverse` is not a transition and must not appear as an arrow")
    (is (empty? (filter #(= :settled (first %)) (lifecycle-edges body)))
        "`settled` is terminal: nothing leaves it")))

;; ---------------------------------------------------------------------------
;; TASK-008 AC-10 — the break lifecycle diagram against its table, both ways
;; ---------------------------------------------------------------------------
;;
;; The second lifecycle, and the second time this comparison is written. It is
;; written twice rather than abstracted over the two because the *point* of the
;; guard is that it names its source: a parameterised version would pass a table
;; in, and a caller passing the wrong table would still be green.

(deftest ac-10-the-break-lifecycle-diagram-and-its-table-agree-in-both-directions
  (let [body     (mermaid-body (artifact "docs/diagrams/reconciliation-break-lifecycle.md"))
        drawn    (lifecycle-edges body)
        declared (into #{} (for [[from events] break-state/transitions
                                 [event to]    events]
                             [from event to]))]
    (testing "every permitted pair in the table is drawn"
      (is (empty? (set/difference declared drawn))
          (str "transitions the table permits and the diagram omits: "
               (pr-str (set/difference declared drawn))
               " — run `make diagrams`.")))
    (testing "nothing is drawn that the table does not permit"
      (is (empty? (set/difference drawn declared))
          (str "transitions the diagram invents: " (pr-str (set/difference drawn declared)))))

    (testing "every state appears, and no other"
      (is (= (set (keys break-state/transitions))
             (set (vals (state-aliases body))))))

    (testing "every event appears, and no other"
      (is (= (set break-state/events) (into #{} (map second) drawn))))

    (testing "the start marker names the initial state, and only it"
      (is (= #{break-state/initial-state} (:initial (anchor-edges body)))))

    (testing "the terminal set is the derived one, and is not a second list"
      (is (= (into #{} (filter break-state/terminal?) (keys break-state/transitions))
             (:terminal (anchor-edges body)))))

    (testing "a state drawn as terminal has no outgoing arrow in the diagram either"
      (doseq [s (:terminal (anchor-edges body))]
        (is (empty? (filter #(= s (first %)) drawn))
            (str s " is drawn as terminal and also has an outgoing arrow."))))))

(deftest ac-10-the-break-lifecycle-diagram-draws-no-arrow-for-a-reassignment
  ;; Reassigning an investigating break leaves the state where it is, so it is a
  ;; rule about status rather than an edge — the distinction
  ;; `clofin.payments.state`'s `mutable-states` already makes, and the one a
  ;; hand-drawn diagram gets wrong first.
  (let [body (mermaid-body (artifact "docs/diagrams/reconciliation-break-lifecycle.md"))]
    (is (empty? (filter (fn [[from _ to]] (= from to)) (lifecycle-edges body)))
        "a self-arrow would draw reassignment as a transition")))

(deftest ac-10-a-break-transition-added-without-regenerating-fails-the-check
  ;; The negative control, run rather than asserted: the lifecycle gains a pair,
  ;; nothing is regenerated, and `check` must fail and say where.
  (let [tampered (update (diagrams/break-lifecycle) :edges conj [:resolved :reopen :open])]
    (with-redefs [diagrams/break-lifecycle (constantly tampered)]
      (let [{:keys [ok? report]} (diagrams/check root)]
        (is (not ok?) "a diagram that no longer matches its source must fail the check")
        (is (str/includes? report "docs/diagrams/reconciliation-break-lifecycle.md")
            "the failure names the artifact that drifted")
        (is (str/includes? report "reopen")
            "the failure names the difference, not merely that there is one"))))
  (testing "and the check passes again once nothing is tampered with"
    (is (:ok? (diagrams/check root)))))

;; ---------------------------------------------------------------------------
;; TASK-010 AC-7 — the adjustment lifecycle diagram against its table, both ways
;; ---------------------------------------------------------------------------
;;
;; The third lifecycle, and the third time this comparison is written out. Same
;; reason as the second: the guard's value is that it names its source, and a
;; parameterised version would pass a table in — a caller passing the wrong one
;; would still be green.

(deftest ac-7-the-adjustment-lifecycle-diagram-and-its-table-agree-in-both-directions
  (let [body     (mermaid-body
                  (artifact "docs/diagrams/reconciliation-adjustment-lifecycle.md"))
        drawn    (lifecycle-edges body)
        declared (into #{} (for [[from events] adjustment/transitions
                                 [event to]    events]
                             [from event to]))]
    (testing "every permitted pair in the table is drawn"
      (is (empty? (set/difference declared drawn))
          (str "transitions the table permits and the diagram omits: "
               (pr-str (set/difference declared drawn))
               " — run `make diagrams`.")))
    (testing "nothing is drawn that the table does not permit"
      (is (empty? (set/difference drawn declared))
          (str "transitions the diagram invents: " (pr-str (set/difference drawn declared)))))

    (testing "every status appears, and no other"
      (is (= (set (keys adjustment/transitions))
             (set (vals (state-aliases body))))))

    (testing "every event appears, and no other"
      (is (= (set adjustment/events) (into #{} (map second) drawn))))

    (testing "the start marker names the initial status, and only it"
      (is (= #{adjustment/initial-status} (:initial (anchor-edges body)))))

    (testing "the terminal set is the derived one, and is not a second list"
      (is (= (into #{} (filter adjustment/terminal?) (keys adjustment/transitions))
             (:terminal (anchor-edges body))))
      (is (= #{:posted :rejected} (:terminal (anchor-edges body)))
          "both endings are drawn as endings — an adjustment that was refused is
           as finished as one that posted"))

    (testing "a status drawn as terminal has no outgoing arrow in the diagram either"
      (doseq [s (:terminal (anchor-edges body))]
        (is (empty? (filter #(= s (first %)) drawn))
            (str s " is drawn as terminal and also has an outgoing arrow."))))))

(deftest ac-7-an-adjustment-transition-added-without-regenerating-fails-the-check
  ;; The negative control, run rather than asserted. `un-reject` is chosen
  ;; because it is the arrow somebody would reach for first and the one the
  ;; lifecycle must not have: a refusal that can be taken back is not a refusal.
  (let [tampered (update (diagrams/adjustment-lifecycle) :edges conj
                         [:rejected :un-reject :proposed])]
    (with-redefs [diagrams/adjustment-lifecycle (constantly tampered)]
      (let [{:keys [ok? report]} (diagrams/check root)]
        (is (not ok?) "a diagram that no longer matches its source must fail the check")
        (is (str/includes? report "docs/diagrams/reconciliation-adjustment-lifecycle.md")
            "the failure names the artifact that drifted")
        (is (str/includes? report "un-reject")
            "the failure names the difference, not merely that there is one"))))
  (testing "and the check passes again once nothing is tampered with"
    (is (:ok? (diagrams/check root)))))

(deftest ac-7-the-generated-index-names-every-lifecycle-that-is-drawn
  ;; `docs/diagrams/README.md` is the one artifact with no machine-readable
  ;; source: its table is written in the generator. A diagram added to the
  ;; registry and forgotten there would be a drawing nobody could find from the
  ;; index that exists to list them.
  (let [index (artifact "docs/diagrams/README.md")]
    (doseq [path (->> (keys (diagrams/artifacts root))
                      (filter #(str/starts-with? % "docs/diagrams/"))
                      (remove #(= % "docs/diagrams/README.md")))]
      (is (str/includes? index (str/replace path "docs/diagrams/" ""))
          (str path " is generated but is not in the generated index")))))

;; ---------------------------------------------------------------------------
;; AC-4 — the control map against COMPLIANCE §2, both ways
;; ---------------------------------------------------------------------------

(defn- compliance-headings
  "`{id → {:status glyph :title s}}`, read straight out of the document with a
  code path the generator does not share."
  []
  (into {}
        (for [line  (md/read-lines "docs/COMPLIANCE.md")
              :let  [[_ id rest*] (re-find #"^### (C-\d+)\s+(.*)$" line)]
              :when id]
          [id {:status (md/status-marker rest*)
               :title  (str/trim (md/strip-status rest*))}])))

(deftest ac-4-the-control-map-and-compliance-section-2-agree-in-both-directions
  (let [body     (mermaid-body (artifact "docs/diagrams/control-map.md"))
        nodes    (flow-nodes body)
        drawn    (into {} (for [[id label] nodes
                                :when (re-find #"^c_\d+$" id)]
                            [(first (str/split label #"<br/>")) label]))
        document (compliance-headings)]
    (testing "every control in COMPLIANCE §2 is on the map"
      (is (empty? (set/difference (set (keys document)) (set (keys drawn))))
          (str "controls the document states and the map omits: "
               (pr-str (set/difference (set (keys document)) (set (keys drawn))))
               " — run `make diagrams`.")))
    (testing "the map invents no control"
      (is (empty? (set/difference (set (keys drawn)) (set (keys document))))))

    (testing "each control is drawn with the status its heading states"
      (doseq [[id {:keys [status]}] (sort document)]
        (let [group (->> body
                         (reduce (fn [{:keys [current found]} line]
                                   (cond
                                     (re-find #"^\s*subgraph\s" line)
                                     {:current (second (re-find #"\[\"([^\"]*)\"\]" line))
                                      :found   found}

                                     (re-find (re-pattern (str "^\\s*" (mmd/identifier id) "\\[")) line)
                                     {:current current :found current}

                                     :else {:current current :found found}))
                                 {:current nil :found nil})
                         :found)]
          (is (and group (str/starts-with? group status))
              (str id " is drawn in the \"" group "\" group but COMPLIANCE says " status)))))

    (testing "every control's title is the one the heading gives"
      (doseq [[id {:keys [title]}] (sort document)]
        (is (str/includes? (get drawn id "") title)
            (str id "'s box does not carry its COMPLIANCE title"))))))

(deftest every-enforced-control-names-at-least-one-enforcement-point
  ;; COMPLIANCE §1: "A control with no mechanical enforcement point is marked as
  ;; such." A ✅ control with an empty enforcement-point list is either a
  ;; document that lost its enforcement or a parser that lost it — and both are
  ;; the kind of silent partial coverage L-6 exists to catch.
  (doseq [{:keys [id status points]} (diagrams/controls root)]
    (when (= "✅" status)
      (is (seq points) (str id " is marked ✅ but states no enforcement point.")))
    (doseq [p points]
      (is (not (str/blank? p)) (str id " has a blank enforcement point.")))))

(deftest enforcement-points-are-read-whole
  ;; A count taken by a second, dumber code path. This is the test that fails if
  ;; the table reader ever again mistakes an empty header row (`| | |`) for the
  ;; `|---|` delimiter and silently drops a control's first enforcement point.
  (let [by-id (into {} (map (juxt :id identity)) (diagrams/controls root))]
    (testing "the table form — every row of C-05's enforcement table"
      (let [section (md/section (md/read-lines "docs/COMPLIANCE.md") #"^### C-05")
            marker  (first (keep-indexed (fn [i l] (when (re-find #"^\*\*Enforcement" l) i))
                                         section))
            rows    (->> (drop (inc marker) section)
                         (take-while #(not (re-find #"^\*\*" %)))
                         (filter #(str/starts-with? % "|"))
                         (remove #(re-find #"^\|[-|]+\|$" %))
                         (remove #(re-matches #"\|\s*\|\s*\|" %)))]
        (is (= (count rows) (count (:points (by-id "C-05"))))
            "C-05's enforcement points do not match the rows of its table")
        (is (contains? (set (:points (by-id "C-05"))) "audit_event_append_only")
            "the first row of C-05's table is missing — the `| | |` header bug")))

    (testing "the bullet form"
      (is (= 5 (count (:points (by-id "C-04"))))
          "C-04 states five enforcement bullets")
      (is (some #(str/starts-with? % "clofin.ledger.entry/entry") (:points (by-id "C-04")))))

    (testing "the prose form keeps its qualifier rather than shortening to the identifier"
      (is (= 1 (count (:points (by-id "C-12")))))
      (is (str/includes? (first (:points (by-id "C-12"))) "Not mechanical")
          "C-12 is a procedural control and the map must not draw it as a mechanical one"))))

;; ---------------------------------------------------------------------------
;; The context topology
;; ---------------------------------------------------------------------------

(deftest the-topology-nodes-are-exactly-architecture-section-3s-contexts
  (let [body    (mermaid-body (artifact "docs/diagrams/context-topology.md"))
        drawn   (set (keys (flow-nodes body)))
        roster  (into #{} (map (comp mmd/identifier :root)) (diagrams/contexts root))]
    (is (= roster drawn)
        "the topology's nodes and ARCHITECTURE §3's table disagree — run `make diagrams`")
    (is (= 8 (count roster)) "ARCHITECTURE §3 names eight bounded contexts")))

(deftest every-topology-arrow-is-a-require-that-actually-exists-in-the-source
  (let [{:keys [edges]} (diagrams/topology root)
        body            (mermaid-body (artifact "docs/diagrams/context-topology.md"))]
    (testing "the drawn arrows are exactly the computed ones, both ways"
      (is (= (into #{} (map (fn [[f t]] [(mmd/identifier f) (mmd/identifier t)])) edges)
             (flow-edges body))))
    (testing "audit is a sink — ARCHITECTURE §3's strongest claim about the graph"
      (is (empty? (filter #(= "clofin.audit" (first %)) edges))
          "clofin.audit acquired an outgoing context dependency; §3 says it depends on nothing"))
    (testing "the arrows §3's prose names are present"
      (is (contains? (set edges) ["clofin.payments" "clofin.ledger"]))
      (is (contains? (set edges) ["clofin.payments" "clofin.authz"]))
      (is (contains? (set edges) ["clofin.settlement" "clofin.ledger"])))))

;; ---------------------------------------------------------------------------
;; AC-1 and AC-2 — drift is caught, and output does not move on its own
;; ---------------------------------------------------------------------------

(deftest ac-2-generating-twice-produces-identical-bytes
  ;; The in-process half. The cross-process half is `make diagrams-check`
  ;; itself, which every build runs in a fresh JVM against bytes a different
  ;; JVM produced — that is what proves the output does not depend on this
  ;; run's hash seeds.
  (is (= (diagrams/artifacts root) (diagrams/artifacts root))))

(deftest ac-2-every-emitted-sequence-is-sorted
  ;; Sorted iteration is *how* determinism was achieved, so it is asserted
  ;; directly rather than inferred from two equal runs inside one JVM.
  (let [body (mermaid-body (artifact "docs/diagrams/payment-lifecycle.md"))
        declarations (keep #(second (re-find #"^\s*state\s+\"([^\"]+)\"" %)) body)
        arrows       (keep #(let [[_ f t e] (re-find #"^\s*(\S+)\s+-->\s+(\S+)\s+:\s+(\S+)\s*$" %)]
                              (when f [f e])) body)]
    (is (= (sort declarations) (vec declarations)) "state declarations are sorted")
    (is (= (sort-by (juxt first second) arrows) (vec arrows)) "transitions are sorted"))
  (let [body (mermaid-body (artifact "docs/diagrams/context-topology.md"))
        ids  (keep #(second (re-find #"^\s*(\w+)\[" %)) body)]
    (is (= (sort ids) (vec ids)) "context nodes are sorted")))

(deftest ac-1-a-transition-added-without-regenerating-fails-the-check
  ;; The negative control for AC-1, run rather than asserted: the lifecycle
  ;; gains a pair, nothing is regenerated, and `check` must fail and say where.
  (let [tampered (update (diagrams/lifecycle) :edges conj [:settled :resurrect :draft])]
    (with-redefs [diagrams/lifecycle (constantly tampered)]
      (let [{:keys [ok? report]} (diagrams/check root)]
        (is (not ok?) "a diagram that no longer matches its source must fail the check")
        (is (str/includes? report "docs/diagrams/payment-lifecycle.md")
            "the failure names the artifact that drifted")
        (is (str/includes? report "resurrect")
            "the failure names the difference, not merely that there is one")
        (is (str/includes? report "docs/DOMAIN_MODEL.md")
            "the embedded copy in DOMAIN_MODEL.md drifts with it"))))
  (testing "and the check passes again once nothing is tampered with"
    (is (:ok? (diagrams/check root))
        "the committed diagrams match their sources — run `make diagrams`")))

(deftest ac-1-a-transition-removed-without-regenerating-fails-the-check
  (let [tampered (update (diagrams/lifecycle) :edges
                         (fn [edges] (vec (remove #(= [:draft :submit :pending-approval] %) edges))))]
    (with-redefs [diagrams/lifecycle (constantly tampered)]
      (let [{:keys [ok? report]} (diagrams/check root)]
        (is (not ok?))
        (is (str/includes? report "submit")
            "the failure names the pair that disappeared")))))

(deftest a-committed-diagram-the-generator-no-longer-produces-fails-the-check
  ;; The other direction: not "what was generated is missing" but "what is
  ;; committed is generated by nothing". A file left behind still renders and
  ;; is still believed.
  (let [orphan (io/file "docs/diagrams/no-longer-generated.md")]
    (try
      (spit orphan "# stale\n")
      (let [{:keys [ok? report]} (diagrams/check root)]
        (is (not ok?))
        (is (str/includes? report "ORPHAN"))
        (is (str/includes? report "no-longer-generated.md")))
      (finally (.delete orphan))))
  (is (:ok? (diagrams/check root)) "and the check is clean once it is gone"))

(deftest ac-4-a-control-added-to-compliance-without-regenerating-fails-the-check
  (let [tampered (conj (diagrams/controls root)
                       {:id "C-99" :title "Invented control" :status "✅"
                        :qualifier nil :points ["nothing at all"]})]
    (with-redefs [diagrams/controls (constantly tampered)]
      (let [{:keys [ok? report]} (diagrams/check root)]
        (is (not ok?))
        (is (str/includes? report "docs/diagrams/control-map.md"))
        (is (str/includes? report "C-99"))))))

;; ---------------------------------------------------------------------------
;; AC-8 — DOMAIN_MODEL §3 carries no hand-maintained drawing
;; ---------------------------------------------------------------------------

(deftest ac-8-domain-model-section-3-has-no-hand-maintained-diagram
  (let [section (md/section (md/read-lines "docs/DOMAIN_MODEL.md")
                            #"^## 3\. Payment instruction lifecycle")
        text    (str/join "\n" section)]
    (testing "the ASCII drawing is gone"
      (is (not (re-find #"[┌┐└┘─│▶▼┬┴├┤]" text))
          "§3 still contains box-drawing characters — a hand-maintained diagram."))
    (testing "the replacement states what generates it"
      (is (str/includes? text "clofin.payments.state/transitions"))
      (is (str/includes? text "clofin.tools.diagrams"))
      (is (str/includes? text "make diagrams-check")))
    (testing "and it is inside the managed block, so it cannot be edited in place"
      (is (str/includes? text "BEGIN GENERATED: payment-lifecycle"))
      (is (str/includes? text "END GENERATED: payment-lifecycle")))))

;; ---------------------------------------------------------------------------
;; The reading primitives the three diagrams rest on
;; ---------------------------------------------------------------------------

(deftest a-table-with-an-empty-header-row-keeps-all-of-its-data
  (is (= [["a" "1"] ["b" "2"]]
         (md/first-table ["| | |" "|---|---|" "| a | 1 |" "| b | 2 |"])))
  (is (= [["a" "1"]]
         (md/first-table ["| Head | Value |" "|---|---|" "| a | 1 |"])))
  (is (nil? (md/first-table ["not a table"])))
  (is (thrown? clojure.lang.ExceptionInfo
               (md/first-table ["| a | b |" "| c | d |"]))
      "a table with no delimiter row is a defect, not a shape to absorb"))

(deftest a-missing-or-ambiguous-section-is-an-error-rather-than-an-empty-diagram
  (is (thrown? clojure.lang.ExceptionInfo (md/section ["# One"] #"^## Nowhere")))
  (is (thrown? clojure.lang.ExceptionInfo
               (md/section ["## Twice" "## Twice"] #"^## Twice"))))

(deftest inline-markdown-is-reduced-to-the-text-a-reader-would-see
  (testing "emphasis loses both delimiters, not only the opening one"
    (is (= "designed, not built" (md/plain "*designed, not built*")))
    (is (= "an approval may be invalidated" (md/plain "an approval may be *invalidated*")))
    (is (= "bold and italic text" (md/plain "**bold** and *italic* text"))))
  (testing "links become their text and code spans lose their backticks"
    (is (= "ADR-0017" (md/plain "[ADR-0017](ADR/0017-bootstrap-identity.md)")))
    (is (= "clofin.audit/event" (md/plain "`clofin.audit/event`"))))
  (testing "a trailing status glyph and its qualifier are separable"
    (is (= "✅" (md/status-marker "C-09 Data minimisation ✅ (partial)")))
    (is (= "C-09 Data minimisation" (md/strip-status "C-09 Data minimisation ✅ (partial)")))
    (is (= "C-07 Sanctions screening" (md/strip-status "C-07 Sanctions screening 📋")))
    (is (nil? (md/status-marker "C-02 Dual authorisation")))))

(deftest labels-cannot-break-out-of-a-mermaid-node
  (is (= "\"a #quot;quoted#quot; b\"" (mmd/label "a \"quoted\" b")))
  (is (= "\"PR #35;6\"" (mmd/label "PR #6")))
  (is (= "\"#lt;tag#gt;\"" (mmd/label "<tag>")))
  (testing "an explicit newline is a hard break and survives wrapping"
    (is (= "\"one<br/>two\"" (mmd/label "one\ntwo"))))
  (testing "wrapping never truncates a word"
    (is (= ["supercalifragilistic"] (mmd/wrap "supercalifragilistic" 5)))))
