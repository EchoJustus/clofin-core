(ns clofin.tools.diagrams
  "Generate every committed diagram from its source of truth.

  [ADR-0020](../../../docs/ADR/0020-two-repositories-and-the-generate-replay-rules.md)
  RULE 1 — *generate, never draw* — is what this namespace exists to make
  mechanical. A hand-drawn diagram is a second copy of the truth, and second
  copies drift: standing lesson **L-4** is the record of an acceptance
  criterion, a transition table and an ASCII drawing in `DOMAIN_MODEL.md` §3
  disagreeing three ways, mitigated by a note asking a human to compare them.

  Four diagrams, four sources:

  | Diagram | Source of truth |
  |---|---|
  | Payment instruction lifecycle | `clofin.payments.state/transitions` |
  | Reconciliation break lifecycle | `clofin.recon.break-state/transitions` |
  | Bounded-context topology | `ARCHITECTURE.md` §3's table, plus the `ns` forms under `src/` |
  | Control map | `COMPLIANCE.md` §2 |

  **Determinism is the whole value.** Every sequence emitted here is sorted by
  an explicit comparator over strings, every node id is derived from its
  content, and nothing reads a clock, a commit, a hostname or an environment
  variable. `make diagrams-check` regenerates and compares byte for byte; a
  generator whose output moved between runs would make that check a coin toss,
  and a check that fails intermittently is a check that gets deleted.

  **This namespace is not on the runtime classpath.** `deps.edn`'s `:paths` is
  `[\"src\" \"resources\"]`; `tools` arrives only through the `:diagrams`,
  `:test` and `:dev` aliases, so documentation machinery cannot reach a running
  service.

  Run it: `make diagrams` to write, `make diagrams-check` to verify."
  (:require [clofin.payments.state :as state]
            [clofin.recon.break-state :as break-state]
            [clofin.tools.markdown :as md]
            [clofin.tools.mermaid :as mmd]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Where the sources live
;; ---------------------------------------------------------------------------

(def architecture-path "ARCHITECTURE.md")
(def compliance-path   "docs/COMPLIANCE.md")
(def domain-model-path "docs/DOMAIN_MODEL.md")
(def source-root       "src")

(defn- at [root relative] (str (io/file root relative)))

;; ---------------------------------------------------------------------------
;; Source 1 — the payment lifecycle, from `clofin.payments.state/transitions`
;; ---------------------------------------------------------------------------

(defn lifecycle
  "The lifecycle as diagram-shaped data, read from the transition table.

  Nothing here is listed by hand — the terminal set is derived through
  `state/terminal?` rather than enumerated, so a state that acquires an
  outgoing arrow stops being drawn as terminal in the same commit that gives it
  one. That is the arrangement ADR-0014 already applies to the code and RULE 1
  extends to the drawing."
  []
  {:states   (vec (sort-by name (keys state/transitions)))
   :initial  state/initial-state
   :terminal (vec (sort-by name (filter state/terminal? (keys state/transitions))))
   :edges    (vec (sort-by (juxt (comp name first) (comp name second))
                           (for [[from events] state/transitions
                                 [event to]    events]
                             [from event to])))})

(defn break-lifecycle
  "The reconciliation break lifecycle as diagram-shaped data, read from
  `clofin.recon.break-state/transitions`.

  The **same** function shape as `lifecycle` above, and rendered by the **same**
  emitter, because the two lifecycles are the same kind of thing: a table of
  arrows with a derived terminal set. Writing a second emitter would be a second
  place for a drawing to disagree with its table, which is what RULE 1 exists to
  prevent — and the terminal set here is derived through
  `break-state/terminal?` for the same reason it is there.

  Not spliced into any prose document. `DOMAIN_MODEL.md` §2.4 links to the
  standalone artifact instead: the payment lifecycle earned an embedded block
  because §3 is *about* it, and a link is enough where the drawing is not the
  section's subject."
  []
  {:states   (vec (sort-by name (keys break-state/transitions)))
   :initial  break-state/initial-state
   :terminal (vec (sort-by name (filter break-state/terminal?
                                        (keys break-state/transitions))))
   :edges    (vec (sort-by (juxt (comp name first) (comp name second))
                           (for [[from events] break-state/transitions
                                 [event to]    events]
                             [from event to])))})

;; ---------------------------------------------------------------------------
;; Source 2 — the bounded contexts, from ARCHITECTURE.md §3 and the `ns` forms
;; ---------------------------------------------------------------------------

(defn contexts
  "The bounded contexts named by `ARCHITECTURE.md` §3's table.

  The table is the roster: context name, namespace root, and what it owns.
  Nothing else in the repository lists the eight contexts, so nothing else can
  be the source for the nodes."
  [root]
  (let [rows (md/first-table (md/section (md/read-lines (at root architecture-path))
                                         #"^## 3\. Bounded contexts"))]
    (when (empty? rows)
      (throw (ex-info "ARCHITECTURE.md §3 no longer contains a table of bounded contexts."
                      {:path architecture-path})))
    (vec (sort-by :root
                  (for [[context ns-root owns] rows]
                    {:context (md/plain context)
                     :root    (md/plain ns-root)
                     :owns    (md/plain owns)})))))

(defn- ns-form
  "The `ns` declaration at the top of a Clojure source file, as data.

  Read as data rather than loaded, for the reason `clofin.ledger.purity-test`
  gives: a transitive require through some other namespace would make a runtime
  check see a dependency the source does not declare, and the arrow this
  diagram draws is the declared one."
  [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (read {:read-cond :allow} r)))

(defn- declared-requires
  "Every namespace named in the `ns` form's `:require` clauses, as strings."
  [form]
  (->> form
       (drop 2)
       (filter (fn [clause] (and (seq? clause) (= :require (first clause)))))
       (mapcat rest)
       (map (fn [spec] (if (sequential? spec) (first spec) spec)))
       (map str)))

(defn- owning-context
  "The namespace root in `roots` that owns `ns-name`, or nil.

  Longest root wins, so a future `clofin.ledger.recon` would belong to
  `clofin.ledger.recon` rather than to `clofin.ledger`."
  [roots ns-name]
  (->> roots
       (filter (fn [r] (or (= ns-name r) (str/starts-with? ns-name (str r ".")))))
       (sort-by count)
       last))

(defn topology
  "The context roster with each context's declared dependencies on the others.

  **Nodes come from the table; edges come from the code.** `ARCHITECTURE.md`
  §3 states its dependency rule in prose — *\"the ledger's domain depends on
  nothing. Payments depends on ledger and authz\"* — and RULE 1 has no
  exception for prose: a diagram drawn from a sentence is a hand-drawn diagram
  with extra steps. The `ns` forms under `src/` are the machine-readable
  statement of the same fact, so they are what the arrows are drawn from. The
  consequence is deliberate: this diagram shows what the code *does*, and a
  reader comparing it with §3's paragraph is checking the paragraph.

  A context whose namespace root has no source file yet is marked as not built
  — again derived, from the absence of the files, not from a list."
  [root]
  (let [roster  (contexts root)
        roots   (mapv :root roster)
        sources (->> (file-seq (io/file (at root source-root)))
                     (filter #(and (.isFile ^java.io.File %)
                                   (str/ends-with? (.getName ^java.io.File %) ".clj")))
                     (sort-by #(.getPath ^java.io.File %)))
        ;; [owning-root, required-root] for every declared require that crosses
        ;; a context boundary, plus the set of roots that have any source at all.
        parsed  (for [file sources
                      :let [form (ns-form file)
                            self (owning-context roots (str (second form)))]
                      :when self]
                  {:self self :deps (keep #(owning-context roots %) (declared-requires form))})
        built   (into #{} (map :self) parsed)
        edges   (into (sorted-set)
                      (for [{:keys [self deps]} parsed
                            dep deps
                            :when (not= self dep)]
                        [self dep]))]
    {:contexts (mapv #(assoc % :built? (contains? built (:root %))) roster)
     :edges    (vec edges)}))

;; ---------------------------------------------------------------------------
;; Source 3 — the controls, from COMPLIANCE.md §2
;; ---------------------------------------------------------------------------

(defn status-legend
  "`COMPLIANCE.md` §1's status vocabulary, in document order.

  Returns `[{:glyph \"✅\" :meaning \"enforced\"} …]`. Read rather than
  restated, for the reason RULE 1 gives one level up: a group heading reading
  \"enforced\" while §1 had been reworded would be a second copy of the
  vocabulary. **Document order is the grouping order too** — sorting by glyph
  would order the control map by Unicode codepoint, which is deterministic and
  meaningless."
  [root]
  (let [section (md/section (md/read-lines (at root compliance-path))
                            #"^## 1\. How to read this")
        line    (first (filter #(str/starts-with? (str/trim %) "Status:") section))]
    (when-not line
      (throw (ex-info "COMPLIANCE.md §1 no longer states its status vocabulary."
                      {:path compliance-path})))
    (let [entries (vec (for [part  (str/split (str/replace (str/trim line) #"^Status:\s*" "") #"·")
                             :let  [[_ glyph meaning] (re-find #"^(✅|🔨|📋)\s+(.*)$"
                                                               (md/plain part))]
                             :when glyph]
                         {:glyph glyph :meaning (str/trim meaning)}))]
      (when (empty? entries)
        (throw (ex-info "COMPLIANCE.md §1's status line no longer names any status."
                        {:path compliance-path :line line})))
      entries)))

(defn- enforcement-entries
  "The enforcement points a control section states, in document order.

  Three shapes appear in `COMPLIANCE.md` and all three are read verbatim:

  - a table, whose first column names each point;
  - a bullet list, one point per bullet;
  - a prose sentence, whose **semicolons** separate the points — which is how
    they were written.

  Verbatim, and split no further. A control map that shortened
  `\"Review of `:deps` additions. *(Not mechanical — …)*\"` to its first clause
  would draw C-12 as mechanically enforced, which is the overstatement this
  repository spends the most effort hunting (standing lesson **L-14**)."
  [section-lines]
  (let [marker (first (keep-indexed
                       (fn [i l] (when (re-find #"^\*\*Enforcement points?\.\*\*" l) i))
                       section-lines))]
    (if-not marker
      []
      (let [head   (str/replace (nth section-lines marker) #"^\*\*Enforcement points?\.\*\*" "")
            rest*  (take-while (fn [l] (not (or (re-find #"^\*\*" l)
                                                (re-find #"^---\s*$" l)
                                                (re-find #"^#" l))))
                               (drop (inc marker) section-lines))
            region (into [head] rest*)]
        (cond
          ;; A table: the first column is the point, the second is commentary.
          (some #(str/starts-with? (str/triml %) "|") region)
          (mapv #(md/plain (first %)) (md/first-table region))

          ;; A bullet list: one point per bullet, continuation lines folded in.
          (some #(re-find #"^- " %) region)
          (->> region
               (reduce (fn [acc line]
                         (cond
                           (re-find #"^- " line)     (conj acc [line])
                           (str/blank? line)         acc
                           (seq acc)                 (conj (pop acc) (conj (peek acc) line))
                           :else                     acc))
                       [])
               (mapv (fn [bullet] (md/plain (str/replace (str/join " " bullet) #"^- " "")))))

          ;; Prose: the semicolons are the separators.
          :else
          (->> (str/split (str/join " " region) #";")
               (map md/plain)
               (remove str/blank?)
               vec))))))

(defn controls
  "Every control in `COMPLIANCE.md` §2 — id, title, status and enforcement points.

  Both directions matter here (standing lesson **L-6**): a control the document
  states and the diagram omits is a control map that quietly under-reports, and
  a control the diagram carries and the document has dropped is one that
  over-reports. `clofin.tools.diagrams-test` asserts the set equality; this
  function's job is only to read every `### C-nn` heading there is."
  [root]
  (let [lines   (md/read-lines (at root compliance-path))
        section (md/section lines #"^## 2\. Controls")
        starts  (keep-indexed (fn [i l] (when (re-find #"^### C-\d+" l) i)) section)
        bounds  (partition 2 1 (concat starts [(count section)]))]
    (when (empty? starts)
      (throw (ex-info "COMPLIANCE.md §2 no longer contains any control headings."
                      {:path compliance-path})))
    (vec
     (for [[from to] bounds
           :let [body    (subvec section from to)
                 heading (str/replace (first body) #"^###\s+" "")
                 id      (re-find #"^C-\d+" heading)
                 status  (md/status-marker heading)]]
       (do
         (when-not status
           (throw (ex-info (str "Control " id " has no status marker in its heading. "
                                "COMPLIANCE.md §1 requires one of ✅ 🔨 📋.")
                           {:control id :heading heading})))
         {:id        id
          :title     (str/trim (md/plain (md/strip-status (str/replace heading #"^C-\d+\s*" ""))))
          :status    status
          :qualifier (some-> (re-find #"(?:✅|🔨|📋)\s+\(([^)]*)\)\s*$" (str/trim heading))
                             second)
          :points    (enforcement-entries body)})))))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- state-id [s] (mmd/identifier (name s)))

(defn lifecycle-mermaid
  "The lifecycle as a Mermaid state diagram.

  Every status is declared with `state \"…\" as …` so the box carries the value
  the database and the API actually use — `pending-approval`, hyphen and all —
  while the node id stays inside Mermaid's identifier grammar."
  [{:keys [states initial terminal edges]}]
  (mmd/block
   (concat
    ["stateDiagram-v2"
     "    direction LR"
     ""]
    (for [s states] (format "    state \"%s\" as %s" (name s) (state-id s)))
    [""
     (format "    [*] --> %s" (state-id initial))
     ""]
    (for [[from event to] edges]
      (format "    %s --> %s : %s" (state-id from) (state-id to) (name event)))
    [""]
    (for [s terminal] (format "    %s --> [*]" (state-id s))))))

(defn topology-mermaid
  "The context roster and its declared cross-context dependencies."
  [{:keys [contexts edges]}]
  (mmd/block
   (concat
    ["flowchart LR"]
    (for [{:keys [context root built?]} contexts]
      (format "    %s[%s]"
              (mmd/identifier root)
              (mmd/label (str context "\n" root (when-not built? "\n(not yet built)")))))
    [""]
    (for [[from to] edges]
      (format "    %s --> %s" (mmd/identifier from) (mmd/identifier to))))))

(defn control-map-mermaid
  "Controls grouped by status, each linked to the enforcement points it states.

  An enforcement point named by more than one control becomes one node with
  several arrows into it, which is the thing a table cannot show: how much of
  the control set rests on `clofin.authz.approval/evaluate`.

  Point node ids are assigned by **sorted position** rather than derived from
  the text, because the text is arbitrary prose and two different clauses can
  reduce to the same identifier. Sorted position is stable across runs, which
  is all determinism requires."
  [{:keys [controls legend]}]
  (let [points    (vec (sort (into #{} (mapcat :points) controls)))
        width     (count (str (count points)))
        point-id  (into {} (map-indexed (fn [i p] [p (format (str "ep%0" width "d") (inc i))])
                                        points))
        by-status (group-by :status controls)
        ;; Groups appear in COMPLIANCE §1's order, and a status the legend does
        ;; not define is a defect in the document rather than a case to absorb.
        _         (let [unknown (remove (set (map :glyph legend)) (keys by-status))]
                    (when (seq unknown)
                      (throw (ex-info (str "Control status " (pr-str unknown)
                                           " is not in COMPLIANCE.md §1's legend.")
                                      {:unknown unknown}))))]
    (mmd/block
     (concat
      ["flowchart LR"]
      (mapcat (fn [{:keys [glyph meaning]}]
                (when-let [members (seq (get by-status glyph))]
                  (concat
                   [(format "    subgraph %s[%s]"
                            (mmd/identifier (str "status_" meaning))
                            (mmd/label (str glyph " " meaning)))
                    "        direction TB"]
                   (for [{:keys [id title qualifier]} (sort-by :id members)]
                     (format "        %s[%s]"
                             (mmd/identifier id)
                             (mmd/label (str id "\n" title (when qualifier (str "\n(" qualifier ")"))))))
                   ["    end"])))
              legend)
      [""]
      (for [p points] (format "    %s[%s]" (point-id p) (mmd/label p)))
      [""]
      (for [control (sort-by :id controls)
            p       (sort (:points control))]
        (format "    %s --> %s" (mmd/identifier (:id control)) (point-id p)))))))

;; ---------------------------------------------------------------------------
;; The committed artifacts
;; ---------------------------------------------------------------------------

(def ^:private banner
  (str "<!-- GENERATED FILE — do not edit by hand.\n"
       "     Regenerate with `make diagrams`. `make diagrams-check` fails the build on drift.\n"
       "     Generator: clofin.tools.diagrams, per ADR-0020 RULE 1 (generate, never draw). -->"))

(defn- document
  [{:keys [title source-prose body]}]
  (str banner "\n\n"
       "# " title "\n\n"
       "> Generated from " source-prose "\n"
       "> by `clofin.tools.diagrams`, per\n"
       "> [ADR-0020](../ADR/0020-two-repositories-and-the-generate-replay-rules.md)\n"
       "> RULE 1 — *generate, never draw*.\n\n"
       body "\n"))

(def ^:private begin-marker "<!-- BEGIN GENERATED: payment-lifecycle -->")
(def ^:private end-marker   "<!-- END GENERATED: payment-lifecycle -->")

(defn- domain-model-block
  [lifecycle-block]
  (str begin-marker "\n\n"
       "> **Generated.** This diagram is produced from\n"
       "> `clofin.payments.state/transitions` by `clofin.tools.diagrams` and checked\n"
       "> by `make diagrams-check`, per\n"
       "> [ADR-0020](ADR/0020-two-repositories-and-the-generate-replay-rules.md) RULE 1.\n"
       "> Editing it here is pointless: the next `make diagrams` overwrites it, and\n"
       "> the build fails in between. The standalone artifact is\n"
       "> [`diagrams/payment-lifecycle.md`](diagrams/payment-lifecycle.md).\n\n"
       lifecycle-block "\n\n"
       end-marker))

(defn- splice-domain-model
  "`DOMAIN_MODEL.md` with its managed lifecycle block replaced.

  The rest of the file is left exactly as it was — §3's numbered rules are
  prose about the lifecycle, not a drawing of it, and RULE 1 does not reach
  them. That boundary is the half of lesson L-4 this brief does **not** close."
  [root lifecycle-block]
  (let [lines (md/read-lines (at root domain-model-path))
        start (first (keep-indexed (fn [i l] (when (= begin-marker (str/trim l)) i)) lines))
        end   (first (keep-indexed (fn [i l] (when (= end-marker (str/trim l)) i)) lines))]
    (when-not (and start end (< start end))
      (throw (ex-info (str "DOMAIN_MODEL.md is missing its generated-lifecycle markers. "
                           "Expected " begin-marker " … " end-marker ".")
                      {:path domain-model-path :begin start :end end})))
    (str/join "\n" (concat (subvec lines 0 start)
                           (str/split (domain-model-block lifecycle-block) #"\n" -1)
                           (subvec lines (inc end))))))

(defn artifacts
  "Every generated file, as `{relative-path → content}`.

  The single place that knows what is committed. `make diagrams` writes this
  map; `make diagrams-check` compares it with what is on disk."
  [root]
  (let [life       (lifecycle)
        life-block (lifecycle-mermaid life)
        break-life (break-lifecycle)
        topo       (topology root)
        ctrls      (controls root)
        legend     (status-legend root)]
    (array-map
     "docs/diagrams/README.md"
     (str banner "\n\n"
          "# Generated diagrams\n\n"
          "Every diagram in this directory is produced from a machine-readable source\n"
          "by `clofin.tools.diagrams` and verified by `make diagrams-check`, which runs\n"
          "inside `make verify`. None of them is drawn or adjusted by hand — see\n"
          "[ADR-0020](../ADR/0020-two-repositories-and-the-generate-replay-rules.md)\n"
          "RULE 1, and standing lesson **L-4** for what a hand-maintained drawing cost.\n\n"
          "| Diagram | Source of truth |\n"
          "|---|---|\n"
          "| [Payment instruction lifecycle](payment-lifecycle.md) | `clofin.payments.state/transitions` |\n"
          "| [Reconciliation break lifecycle](reconciliation-break-lifecycle.md) | `clofin.recon.break-state/transitions` |\n"
          "| [Bounded-context topology](context-topology.md) | [`ARCHITECTURE.md` §3](../../ARCHITECTURE.md) and the `ns` forms under `src/` |\n"
          "| [Control map](control-map.md) | [`COMPLIANCE.md` §2](../COMPLIANCE.md) |\n\n"
          "To change a diagram, change its source and run `make diagrams`.\n")

     "docs/diagrams/payment-lifecycle.md"
     (document
      {:title "Payment instruction lifecycle"
       :source-prose "`clofin.payments.state/transitions`"
       :body (str life-block "\n\n"
                  "Every state, every event and every permitted pair above is read from that\n"
                  "table. The terminal states — the ones with an arrow to `[*]` — are\n"
                  "**derived** through `clofin.payments.state/terminal?` rather than listed, so a\n"
                  "state that gains an outgoing transition stops being drawn as terminal in the\n"
                  "same commit that gives it one.\n\n"
                  "The rules that are *not* transitions — `mutable-states`, `reversible-states`\n"
                  "and `creator-only-events` — govern operations that leave the status where it\n"
                  "was, so they are no arrow on any diagram. They are stated in\n"
                  "[`DOMAIN_MODEL.md` §3](../DOMAIN_MODEL.md) beside this drawing.\n")})

     "docs/diagrams/reconciliation-break-lifecycle.md"
     (document
      {:title "Reconciliation break lifecycle"
       :source-prose "`clofin.recon.break-state/transitions`"
       :body (str (lifecycle-mermaid break-life) "\n\n"
                  "Every state, every event and every permitted pair above is read from that\n"
                  "table. The terminal state — the one with an arrow to `[*]` — is **derived**\n"
                  "through `clofin.recon.break-state/terminal?` rather than listed, so a state\n"
                  "that gains an outgoing transition stops being drawn as terminal in the same\n"
                  "commit that gives it one.\n\n"
                  "**Assignment is the transition.** A break becomes `investigating` by somebody\n"
                  "taking it on, so `assign` is one arrow rather than an ownership change beside a\n"
                  "state change. *Re*-assigning an already-investigating break leaves the state\n"
                  "where it is and is therefore no arrow at all: it is governed by\n"
                  "`reconciliation_break`'s `reassignable-states`, the same way `mutable-states`\n"
                  "governs amending a draft payment.\n\n"
                  "**`resolve` is driven by a posted adjustment and by nothing else.** There is no\n"
                  "written-off state, because nothing in this increment could drive one — and a\n"
                  "state with no driver is a promise the product does not keep.\n\n"
                  "A break's **age** is derived from its `openedAt` whenever it is read and is\n"
                  "stored nowhere, so it is not a state and appears on no diagram.\n")})

     "docs/diagrams/context-topology.md"
     (document
      {:title "Bounded-context topology"
       :source-prose (str "[`ARCHITECTURE.md` §3](../../ARCHITECTURE.md)'s table of contexts,\n"
                          "> with the arrows read from the `:require` clauses of the `ns` forms under `src/`,")
       :body (str (topology-mermaid topo) "\n\n"
                  "**Nodes come from the table; arrows come from the code.** `ARCHITECTURE.md` §3\n"
                  "states its dependency rule in prose, and RULE 1 has no exception for prose —\n"
                  "a diagram drawn from a sentence is a hand-drawn diagram with extra steps. The\n"
                  "`ns` forms are the machine-readable statement of the same fact, so they are\n"
                  "what is drawn. Reading this diagram against §3's paragraph is therefore a\n"
                  "check *on the paragraph*, and a useful one.\n\n"
                  "An arrow is a **declared** require that crosses a context boundary, read from\n"
                  "the source rather than from a loaded namespace: a transitive require through\n"
                  "some other namespace would draw an arrow the source does not contain. A\n"
                  "context marked *not yet built* has no source file under its namespace root.\n")})

     "docs/diagrams/control-map.md"
     (document
      {:title "Control map"
       :source-prose "[`COMPLIANCE.md` §2](../COMPLIANCE.md)"
       :body (str (control-map-mermaid {:controls ctrls :legend legend}) "\n\n"
                  "Each control carries the status its `COMPLIANCE.md` heading states, grouped by\n"
                  "the status vocabulary [§1](../COMPLIANCE.md) defines. The boxes on the right\n"
                  "are that control's **Enforcement point** entries, quoted as the document\n"
                  "writes them — a table's first column, a bullet, or a semicolon-separated\n"
                  "clause of a sentence. They are not shortened to the identifier inside them:\n"
                  "shortening *\"Review of `:deps` additions. (Not mechanical — …)\"* to its first\n"
                  "clause would draw a procedural control as a mechanical one, which is standing\n"
                  "lesson **L-14** exactly.\n\n"
                  "An enforcement point named by more than one control is one box with several\n"
                  "arrows into it. That is the thing the table in `COMPLIANCE.md` cannot show:\n"
                  "how much of the control set rests on a single function.\n\n"
                  "This is a map of what the document **claims**. It is not evidence that a\n"
                  "control holds; the enforcement points themselves are, and\n"
                  "[`COMPLIANCE.md`](../COMPLIANCE.md) names the test or constraint for each.\n")})

     "docs/DOMAIN_MODEL.md"
     (splice-domain-model root life-block))))

;; ---------------------------------------------------------------------------
;; Writing and checking
;; ---------------------------------------------------------------------------

(defn- differing-lines
  "A printable hunk showing where `expected` and `actual` first diverge.

  Deliberately not a full diff algorithm: common prefix and suffix are trimmed
  and what is left is printed from both sides with line numbers. That names the
  difference — which is what AC-1 asks for — with no dependency on an external
  `diff` and no ambiguity about which of several equally short edit scripts was
  chosen."
  [expected actual]
  (let [e (str/split expected #"\n" -1)
        a (str/split actual #"\n" -1)
        prefix (count (take-while true? (map = e a)))
        suffix (count (take-while true? (map = (reverse (drop prefix e))
                                            (reverse (drop prefix a)))))
        e-mid  (subvec (vec e) prefix (- (count e) suffix))
        a-mid  (subvec (vec a) prefix (- (count a) suffix))
        cap    12]
    (str/join
     "\n"
     (concat [(format "    @@ line %d @@" (inc prefix))]
             (map-indexed (fn [i l] (format "    - %4d  %s" (+ prefix i 1) l))
                          (take cap a-mid))
             (when (> (count a-mid) cap)
               [(format "    - … %d further committed line(s)" (- (count a-mid) cap))])
             (map-indexed (fn [i l] (format "    + %4d  %s" (+ prefix i 1) l))
                          (take cap e-mid))
             (when (> (count e-mid) cap)
               [(format "    + … %d further generated line(s)" (- (count e-mid) cap))])))))

(defn write!
  "Write every artifact, creating directories as needed. Returns the paths."
  [root]
  (let [generated (artifacts root)]
    (doseq [[relative content] generated
            :let [file (io/file root relative)]]
      (io/make-parents file)
      (spit file content))
    (vec (keys generated))))

(defn- orphans
  "Committed files under `docs/diagrams/` that the generator no longer produces.

  The other direction of the same comparison, and the one a check written in a
  hurry leaves out: a diagram dropped from the generator leaves its artifact
  sitting in the repository, still rendering, still wrong, and still passing a
  check that only ever looks at what it was about to write (lesson **L-6**)."
  [root generated]
  (let [dir (io/file root "docs/diagrams")]
    (->> (when (.isDirectory dir) (.listFiles dir))
         (filter #(.isFile ^java.io.File %))
         (map #(str "docs/diagrams/" (.getName ^java.io.File %)))
         (remove (set (keys generated)))
         sort
         vec)))

(defn check
  "Compare every artifact with what is committed, in both directions.

  Returns `{:ok? bool :report string}`. The regenerated files are also written
  to a temporary directory, whose path the report names, so that a reader who
  wants a full diff can run one."
  [root]
  (let [generated (artifacts root)
        tmp       (java.nio.file.Files/createTempDirectory
                   "clofin-diagrams" (into-array java.nio.file.attribute.FileAttribute []))
        drift     (into
                   (vec
                    (for [[relative content] generated
                          :let [file      (io/file root relative)
                                committed (when (.exists file)
                                            (str/replace (slurp file) "\r\n" "\n"))
                                out       (io/file (.toFile tmp) relative)]
                          :let [_ (do (io/make-parents out) (spit out content))]
                          :when (not= content committed)]
                      (if committed
                        (format "DRIFT   %s — the committed diagram no longer matches its source.\n%s"
                                relative (differing-lines content committed))
                        (format "MISSING %s — generated from its source, but not committed."
                                relative))))
                   (for [orphan (orphans root generated)]
                     (format (str "ORPHAN  %s — committed under docs/diagrams/ but produced by no\n"
                                  "        source. `make diagrams` will not remove it; delete it, or\n"
                                  "        restore whatever stopped generating it.")
                             orphan)))]
    {:ok?    (empty? drift)
     :report (if (empty? drift)
               (format "Diagrams OK (%d generated artifact(s) match their sources)."
                       (count generated))
               (str (str/join "\n\n" drift)
                    "\n\n"
                    (format "%d artifact(s) disagree with their source of truth.\n" (count drift))
                    "Run `make diagrams` and commit the result. Do not edit the diagram by hand —\n"
                    "ADR-0020 RULE 1: the source is the truth, the drawing is generated from it.\n"
                    (format "Freshly generated files for comparison: %s" (str tmp))))}))

(defn -main
  "`clojure -M:diagrams` writes; `clojure -M:diagrams --check` verifies."
  [& args]
  (let [root (System/getProperty "user.dir")]
    (if (some #{"--check"} args)
      (let [{:keys [ok? report]} (check root)]
        (println report)
        (System/exit (if ok? 0 1)))
      (do (doseq [p (write! root)] (println "wrote" p))
          (System/exit 0)))))
