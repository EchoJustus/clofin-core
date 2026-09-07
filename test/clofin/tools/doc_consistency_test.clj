(ns clofin.tools.doc-consistency-test
  "`scripts/check-doc-consistency.sh`, exercised against documents built to
  contradict each other.

  A guard is only worth having if it has been watched failing. Every case here
  starts from a **consistent** fixture tree under
  `test-resources/doc-consistency/base`, applies one named edit, and asserts
  that the guard notices — so what each case proves is the edit, stated in one
  line, rather than a wall of fixture text.

  The headline case is AC-5's: the actual 2026-08-05 contradiction, in which
  `main`'s ROADMAP called four controls *designed, not built* while
  `COMPLIANCE.md` on the same branch showed them enforced. That is standing
  lesson **L-15**, and it survived two milestone audits and a release audit."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private fixture-root "test-resources/doc-consistency/base")
(def ^:private script "scripts/check-doc-consistency.sh")

(defn- copy-tree!
  [^java.io.File from ^java.io.File to]
  (doseq [^java.io.File f (file-seq from)
          :when (.isFile f)]
    (let [rel  (subs (.getPath f) (inc (count (.getPath from))))
          dest (io/file to rel)]
      (io/make-parents dest)
      (io/copy f dest))))

(defn- run
  "Run the guard over `root` and return `{:exit n :out s}`.

  Decoded as UTF-8 explicitly rather than in the platform default charset: the
  report is full of ✅, 🔨 and 📋, and a machine whose console encoding is
  ASCII would turn every assertion about a status glyph into a comparison
  against a question mark."
  [root]
  (let [{:keys [exit out err]} (shell/sh "sh" script (str root) :out-enc "UTF-8")]
    {:exit exit :out (str out err)}))

(defn- scenario
  "The fixture tree with `edits` applied, checked.

  Each edit is `[relative-path find replace]`. A `find` that is not present is
  a failed test rather than a silent no-op: an edit that did not apply would
  make the case assert nothing while still going green — the shape of the
  problem this whole increment exists to remove."
  [& edits]
  (let [tmp (.toFile (java.nio.file.Files/createTempDirectory
                      "clofin-doc-consistency"
                      (into-array java.nio.file.attribute.FileAttribute [])))]
    (copy-tree! (io/file fixture-root) tmp)
    (doseq [[path find replace] edits]
      (let [file    (io/file tmp path)
            content (slurp file)]
        (is (str/includes? content find)
            (str "fixture edit does not apply: " path " does not contain " (pr-str find)))
        (spit file (str/replace content find replace))))
    (run tmp)))

;; ---------------------------------------------------------------------------
;; The guard agrees with itself when the documents agree
;; ---------------------------------------------------------------------------

(deftest a-consistent-set-of-documents-passes
  (let [{:keys [exit out]} (scenario)]
    (is (zero? exit) out)
    (is (str/includes? out "Document consistency OK"))))

;; ---------------------------------------------------------------------------
;; AC-5 — a control enforced in one document and unenforced in the other
;; ---------------------------------------------------------------------------

(deftest ac-5-the-2026-08-05-contradiction-is-caught
  ;; The ROADMAP prose is replaced with the paragraph `main` actually carried
  ;; on 2026-08-05, verbatim in shape: controls listed as 📋 *designed, not
  ;; built* while COMPLIANCE marks them ✅.
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md"
                   "**Controls now enforced on `main`.** C-01 (segregation of duties) and C-02\n(dual authorisation) are enforced on `main`. C-07 (screening) remains 📋."
                   "**Controls still unenforced.** Two entries in\n[`COMPLIANCE.md`](COMPLIANCE.md) are 📋 *designed, not built* — C-01 and\nC-02. TASK-001 delivers them."])]
    (is (= 1 exit) "a control contradiction must fail the build")
    (testing "the failure names both documents"
      (is (str/includes? out "docs/ROADMAP.md"))
      (is (str/includes? out "docs/COMPLIANCE.md")))
    (testing "and both disagreeing values, for each control"
      (is (str/includes? out "says C-01 is 📋"))
      (is (str/includes? out "says C-01 is ✅"))
      (is (str/includes? out "says C-02 is 📋"))
      (is (str/includes? out "says C-02 is ✅")))
    (testing "and it reports rather than repairs"
      (is (str/includes? out "reported, never repaired")))))

(deftest ac-5-the-contradiction-in-the-other-direction-is-caught-too
  ;; L-6: a guard that only checks the direction its author was thinking about.
  ;; Here COMPLIANCE is the stale copy and the ROADMAP is right.
  (let [{:keys [exit out]}
        (scenario ["docs/COMPLIANCE.md" "### C-01 Segregation of duties ✅"
                   "### C-01 Segregation of duties 📋"])]
    (is (= 1 exit))
    (is (str/includes? out "says C-01 is ✅"))
    (is (str/includes? out "says C-01 is 📋"))))

(deftest a-control-the-roadmap-names-and-compliance-does-not-define-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md" "C-07 (screening) remains 📋."
                   "C-99 (invented) remains 📋."])]
    (is (= 1 exit))
    (is (str/includes? out "names control C-99"))
    (is (str/includes? out "does not define"))))

;; ---------------------------------------------------------------------------
;; AC-6 — a not-started increment whose brief is done
;; ---------------------------------------------------------------------------

(deftest ac-6-a-closed-brief-under-a-not-started-increment-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/briefs/002-TASK-diagrams.md" "| **Status** | `READY` |"
                   "| **Status** | `CLOSED` — merged in PR #9 |"])]
    (is (= 1 exit))
    (testing "the failure names the increment by its opaque id, not a number"
      (is (str/includes? out "increment 5v.1"))
      (is (not (str/includes? out "increment 5.1"))
          "`5v.1` is an opaque key: it is neither parsed as a number nor normalised"))
    (testing "and names both documents and both values"
      (is (str/includes? out "docs/ROADMAP.md"))
      (is (str/includes? out "docs/briefs/002-TASK-diagrams.md"))
      (is (str/includes? out "not started (📋)"))
      (is (str/includes? out "status is CLOSED")))))

(deftest ac-6-an-implemented-brief-under-a-not-started-increment-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/briefs/003-TASK-trace.md" "| **Status** | `READY` |"
                   "| **Status** | `IMPLEMENTED` — PR open |"])]
    (is (= 1 exit))
    (is (str/includes? out "increment 5v.2"))
    (is (str/includes? out "status is IMPLEMENTED"))))

(deftest a-later-increment-is-not-started-too
  ;; 💭 as well as 📋 — the legend defines both, and a guard that read only the
  ;; first would pass on the half of the 2026-08-05 incident that said
  ;; "not yet briefed" about five merged increments.
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md"
                   "| 6–9 | Reconciliation onwards | not yet briefed | 💭 later | — |"
                   "| 6–9 | Reconciliation onwards | [TASK-001](briefs/001-TASK-ledger.md) | 💭 later | — |"])]
    (is (= 1 exit))
    (is (str/includes? out "not started (💭)"))))

(deftest a-roadmap-that-restates-a-brief-status-wrongly-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/briefs/001-TASK-ledger.md"
                   "| **Status** | `CLOSED` — merged to `main` in PR #2 |"
                   "| **Status** | `IN PROGRESS` |"])]
    (is (= 1 exit))
    (is (str/includes? out "restates increment 2's status as CLOSED"))
    (is (str/includes? out "the brief itself says IN PROGRESS"))))

(deftest an-increment-that-is-genuinely-not-started-passes
  ;; The complement of AC-6: 📋 beside a `READY` brief is the correct state and
  ;; must not fail, or the guard gets disabled for crying wolf.
  (let [{:keys [exit out]} (scenario)]
    (is (zero? exit) out)
    (is (str/includes? out "9 increment status claim(s)")
        "the fixture's 📋 increments are read, not skipped")))

;; ---------------------------------------------------------------------------
;; The brief set: the ROADMAP, the backlog and the directory
;; ---------------------------------------------------------------------------

(deftest a-brief-the-roadmap-forgot-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md"
                   "| 5v.2 | Visual layer — walkthrough | [TASK-003](briefs/003-TASK-trace.md) | 📋 `READY`, gated on 5v.1 | — |\n"
                   ""])]
    (is (= 1 exit))
    (is (str/includes? out "the backlog lists docs/briefs/003-TASK-trace.md"))
    (is (str/includes? out "global-state table does not reference it"))
    (is (str/includes? out "2 increment(s) with a brief on the ROADMAP, 3 in the backlog")
        "the counts of the two documents are stated, which is the disagreeing value")))

(deftest a-brief-the-backlog-forgot-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/briefs/README.md"
                   "| [003 — Trace](003-TASK-trace.md) | 5v.2 | `READY` | 002 |\n"
                   ""])]
    (is (= 1 exit))
    (is (str/includes? out "backlog table does not list it"))
    (is (str/includes? out "listed in neither"))))

;; ---------------------------------------------------------------------------
;; Failing closed — a guard that checks nothing is not a guard that passed
;; ---------------------------------------------------------------------------

(deftest a-controls-paragraph-with-an-unrecognised-lead-in-fails-rather-than-skipping
  ;; The paragraph is still there and still makes control claims, but its
  ;; lead-in no longer says which way. Guessing would be worse than failing.
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md" "**Controls now enforced on `main`.**"
                   "**Controls, in general.**"])]
    (is (= 1 exit) "rewording the paragraph must not silently disable the control check")
    (is (str/includes? out "lead-in this guard does not"))))

(deftest a-roadmap-whose-controls-paragraph-is-renamed-away-fails-closed
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md" "**Controls now enforced on `main`.**"
                   "**Notes on the control set.**"])]
    (is (= 1 exit) "renaming the paragraph must not silently disable the control check")
    (is (str/includes? out "no controls paragraph"))))

(deftest a-roadmap-whose-controls-paragraph-is-deleted-fails-closed
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md"
                   "**Controls now enforced on `main`.** C-01 (segregation of duties) and C-02\n(dual authorisation) are enforced on `main`. C-07 (screening) remains 📋."
                   "Nothing to see here."])]
    (is (= 1 exit))
    (is (str/includes? out "no controls paragraph"))
    (is (str/includes? out "not the same as agreement"))))

(deftest a-controls-line-claiming-two-statuses-is-refused-rather-than-guessed
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md" "C-07 (screening) remains 📋."
                   "C-07 (screening) remains 📋 but is ✅ in places."])]
    (is (= 1 exit))
    (is (str/includes? out "names two different statuses"))
    (is (str/includes? out "is not decidable, so it is not guessed"))))

(deftest a-compliance-heading-without-a-status-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/COMPLIANCE.md" "### C-02 Dual authorisation ✅"
                   "### C-02 Dual authorisation"])]
    (is (= 1 exit))
    (is (str/includes? out "no single status glyph"))))

(deftest a-legend-that-loses-a-not-started-glyph-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md" "Legend: ✅ done · 🔨 in progress · 📋 next · 💭 later"
                   "Legend: ✅ done · 🔨 in progress · 📋 next"])]
    (is (= 1 exit))
    (is (str/includes? out "no longer defines both not-started glyphs"))))

(deftest a-missing-document-is-an-error-not-a-pass
  (let [tmp (.toFile (java.nio.file.Files/createTempDirectory
                      "clofin-doc-consistency-empty"
                      (into-array java.nio.file.attribute.FileAttribute [])))]
    (is (= 1 (:exit (run tmp))) "an empty tree must not report consistency")))

;; ---------------------------------------------------------------------------
;; The guard is deterministic
;; ---------------------------------------------------------------------------

(deftest the-report-is-byte-identical-between-runs
  ;; awk's `for (k in array)` order is unspecified, so every report here is
  ;; emitted from an explicitly indexed list in document order. This is the
  ;; assertion that keeps it that way.
  (let [edits [["docs/COMPLIANCE.md" "### C-01 Segregation of duties ✅"
                "### C-01 Segregation of duties 📋"]
               ["docs/briefs/002-TASK-diagrams.md" "| **Status** | `READY` |"
                "| **Status** | `CLOSED` |"]]
        runs  (repeatedly 3 #(apply scenario edits))]
    (is (apply = (map :out runs)) "the report must not reorder between runs")
    (is (apply = (map :exit runs)))))

;; ---------------------------------------------------------------------------
;; AC-20 (2B-011, 2C-008) — rule 5: prose and headings that speak of a task
;; as work in hand
;;
;; Rules 1–4 read the global-state table and each section's `**Brief:**` line.
;; They do not read a task named *inside* a heading or a paragraph, and the
;; `ref-2` release audit walked through that gap twice in one document:
;;
;;   line 227  ## Increment 8 — Operator interface 💭 *(… phase 8.1 in flight
;;             as [TASK-011](briefs/011-TASK-cockpit-initialization.md))*
;;   line 254  dependencies met — currently **TASK-001**. Set its `Status` to
;;             `IN PROGRESS` in
;;
;; while the same file's table marked 8.1–8.4 `CLOSED` and every one of the
;; fourteen briefs was `CLOSED`. The guard passed both times.
;;
;; **The live ROADMAP is repaired and is never edited here.** These are fixture
;; trees carrying the RC's two lines — which is also what proves rule 5 is not
;; vacuous, since the repaired document contains no live prose claim at all and
;; the guard reports "0 prose task claim(s)".
;; ---------------------------------------------------------------------------

(def ^:private rc-heading
  "The RC's line 227, with the fixture's own closed task in place of TASK-011."
  (str "## Increment 2 — Ledger ✅ *(relocating to `clofin-cockpit` — D1 ruling"
       " 2026-08-15, ADR-0026; phase 8.1 in flight as"
       " [TASK-001](briefs/001-TASK-ledger.md))*"))

(def ^:private rc-pickup
  "The RC's lines 253–255, verbatim in shape."
  (str "Take the lowest-numbered brief in [`briefs/`](briefs) that is `READY` with its\n"
       "dependencies met — currently **TASK-001**. Set its `Status` to `IN PROGRESS` in\n"
       "your first commit; that commit is the lock other sessions check."))

(deftest ac-20-a-heading-calling-a-closed-task-in-flight-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md" "## Increment 2 — Ledger ✅" rc-heading])]
    (is (= 1 exit) (str "a heading naming a CLOSED task as in flight must fail — " out))
    (is (str/includes? out "speaks of TASK-001 as work in hand") out)
    (is (str/includes? out "its brief says CLOSED") out)
    (is (str/includes? out "docs/briefs/001-TASK-ledger.md") out)))

(deftest ac-20-a-pickup-paragraph-sending-a-worker-to-a-closed-task-is-caught
  (let [{:keys [exit out]}
        (scenario ["docs/ROADMAP.md"
                   "- Statement ingestion and matching"
                   (str "- Statement ingestion and matching\n\n" rc-pickup)])]
    (is (= 1 exit) (str "prose naming a CLOSED task as the current one must fail — " out))
    ;; Reported through the literal-status branch rather than the liveness one:
    ;; the sentence names `IN PROGRESS` outright, so the guard says which status
    ;; was claimed rather than only that the task was called live. Either branch
    ;; catches it; this is the sharper of the two.
    (is (str/includes? out "says TASK-001 is IN PROGRESS") out)
    (is (str/includes? out "the brief itself says CLOSED") out)
    (is (str/includes? out "docs/briefs/001-TASK-ledger.md") out)))

(deftest ac-20-both-of-the-rc-lines-are-reported-together
  (testing "the RC carried both at once, and a guard that reported only the
            first would leave the second live for another cycle"
    (let [{:keys [exit out]}
          (scenario ["docs/ROADMAP.md" "## Increment 2 — Ledger ✅" rc-heading]
                    ["docs/ROADMAP.md"
                     "- Statement ingestion and matching"
                     (str "- Statement ingestion and matching\n\n" rc-pickup)])]
      (is (= 1 exit) out)
      (is (str/includes? out "2 document disagreement(s)")
          (str "both lines must be reported, not just the first — " out))
      (testing "each by its own line number, and each naming the brief that
                contradicts it"
        (is (str/includes? out "speaks of TASK-001 as work in hand") out)
        (is (str/includes? out "says TASK-001 is IN PROGRESS") out)
        (is (= 2 (count (re-seq #"docs/briefs/001-TASK-ledger.md:6" out))) out)))))

(deftest ac-20-a-literal-status-word-must-match-the-brief
  (testing "a line that names a lifecycle status is checked against it rather
            than merely against liveness"
    (let [{:keys [exit out]}
          (scenario ["docs/ROADMAP.md"
                     "- Statement ingestion and matching"
                     "- Statement ingestion and matching\n\nTASK-002 is `IN PROGRESS` this week."])]
      (is (= 1 exit) out)
      (is (str/includes? out "says TASK-002 is IN PROGRESS") out)
      (is (str/includes? out "the brief itself says READY") out))))

(deftest ac-20-a-live-task-named-as-live-passes
  (testing "the positive case, so the four above cannot pass by failing
            everything. TASK-002's brief is READY and the prose says so"
    (let [{:keys [exit out]}
          (scenario ["docs/ROADMAP.md"
                     "- Statement ingestion and matching"
                     "- Statement ingestion and matching\n\nTASK-002 is `READY` and is next up."])]
      (is (zero? exit) out)
      (is (str/includes? out "prose task claim(s)") out))))

(deftest ac-20-a-quotation-of-a-superseded-claim-is-not-a-claim
  (testing "the repaired heading records what it used to say. A guard that read
            a quotation as live would fail the very repair it exists to
            enforce, and would push the next author into deleting the history
            rather than keeping it — `scripts/check-doc-links.sh` learned the
            same thing about fenced blocks when FEEDBACK-REL-ref-2 landed"
    (let [{:keys [exit out]}
          (scenario ["docs/ROADMAP.md"
                     "## Increment 2 — Ledger ✅"
                     (str "## Increment 2 — Ledger ✅ *(phases delivered as"
                          " [TASK-001](briefs/001-TASK-ledger.md); this heading said"
                          " \"phase 8.1 in flight\" until 2026-09-05, three closed phases"
                          " after it stopped being true)*")])]
      (is (zero? exit) (str "a quoted superseded claim must not be read as live — " out)))))

(deftest ac-20-scare-quotes-around-a-status-word-are-still-a-claim
  (testing "the exemption above is for one sentence shape — a repair that keeps
            what a heading used to say. An ungated version silenced far more
            than that: it removed every quoted span from every line before the
            claim words were looked for, so an author who wrote `TASK-001 is
            \"IN PROGRESS\" this week` disabled the rule for that line. Nothing
            asserted that a *quoted* claim still fails, so the guard could be
            walked past by adding two characters"
    (doseq [line ["TASK-001 is \"IN PROGRESS\" this week."
                  "Phase 8.1 is \"in flight\" as TASK-001."]]
      (let [{:keys [exit out]}
            (scenario ["docs/ROADMAP.md"
                       "- Statement ingestion and matching"
                       (str "- Statement ingestion and matching\n\n" line)])]
        (is (= 1 exit) (str line " — " out))
        (is (str/includes? out "TASK-001") out)))))

(deftest ac-20-an-unpaired-quote-does-not-swallow-the-claim-after-it
  (testing "the pairing was naive left-to-right, so a single stray `\"` — a 24\"
            wallboard — paired with the opening quote of a later, real
            quotation and deleted everything between them, claim and task
            identifier included. An odd count means one of them is not a
            quotation mark, and the rule keeps the words rather than guessing"
    (let [{:keys [exit out]}
          (scenario ["docs/ROADMAP.md"
                     "- Statement ingestion and matching"
                     (str "- Statement ingestion and matching\n\n"
                          "The 24\" wallboard shows it: TASK-001 is currently in"
                          " hand — see the \"handoff\" note.")])]
      (is (= 1 exit) out)
      (is (str/includes? out "TASK-001") out))))

(deftest ac-20-a-task-named-as-live-with-no-brief-is-caught
  (testing "fail closed: prose naming a task nothing describes is a claim
            nothing can check (L-6)"
    (let [{:keys [exit out]}
          (scenario ["docs/ROADMAP.md"
                     "- Statement ingestion and matching"
                     "- Statement ingestion and matching\n\nTASK-099 is currently in hand."])]
      (is (= 1 exit) out)
      (is (str/includes? out "docs/briefs/ holds no brief for it") out))))

(deftest ac-20-the-live-roadmap-passes-rule-5
  (testing "the repaired document, checked. Its prose claims about tasks are
            the empty set, which is a legitimate state and is why the fixtures
            above are what prove the rule is not vacuous"
    (let [{:keys [exit out]} (run ".")]
      (is (zero? exit) out)
      (is (str/includes? out "0 prose task claim(s)")
          (str "the live ROADMAP names no task as work in hand — " out)))))
