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
;; This repository, right now
;; ---------------------------------------------------------------------------

(def ^:private known-staleness
  "The disagreements this guard finds on `main` today, which this Worker is not
  permitted to repair.

  `docs/ROADMAP.md` is a governance document, synced from the `meta` branch by
  Master Control and never edited in place (AGENT_HANDOFF §1). Its global-state
  table was brought up to date by the `meta` → `main` sync in PR #10; its
  per-increment sections were not, and still show merged, audited, closed
  increments as `📋 next` and `READY`. That is the *same* understatement as the
  2026-08-05 incident, in the *same* file, on `main` — see objection O-1 in
  `docs/audits/006-REQ-generated-diagrams.md`.

  This expectation is why `check-doc-consistency` is not yet in `make verify`,
  and it is deliberately exact. It fails if a **new** disagreement appears, and
  it fails when the ROADMAP is corrected — at which point the deferral is over:
  delete this test and wire the script into `verify` (brief AC-7)."
  [["docs/ROADMAP.md:132" "increment 3" "not started (📋)"]
   ["docs/ROADMAP.md:134" "increment 3" "IMPLEMENTED"]
   ["docs/ROADMAP.md:157" "increment 4" "IMPLEMENTED"]
   ["docs/ROADMAP.md:184" "increment 5" "not started (📋)"]
   ["docs/ROADMAP.md:186" "increment 5" "READY"]])

(deftest the-known-roadmap-staleness-has-not-grown
  (let [{:keys [exit out]} (run ".")
        reported (count (re-seq #"(?m)^DISAGREE" out))]
    (testing "every known disagreement is still reported, and named the same way"
      (doseq [[location increment value] known-staleness]
        (is (some (fn [block] (and (str/includes? block location)
                                   (str/includes? block increment)
                                   (str/includes? block value)))
                  (str/split out #"\n\n"))
            (str "expected a disagreement at " location " about " increment " (" value ")"))))
    (testing "and nothing else has appeared"
      (is (= (count known-staleness) reported)
          (str "the guard reports " reported " disagreement(s) on this branch; "
               (count known-staleness) " are known and ruled on in 006-REQ objection O-1.\n"
               "If the ROADMAP has been corrected on `meta` and re-synced, that is good "
               "news: delete this test and add check-doc-consistency to `make verify`.\n"
               "If something new has drifted, that is what this guard is for.\n\n"
               out)))
    (is (= 1 exit))))
