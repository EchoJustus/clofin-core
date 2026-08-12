(ns clofin.tools.capture.quotations
  "Control statements and invariants, extracted verbatim from the captured
  commit's own documents.

  [ADR-0020](../../../../docs/ADR/0020-two-repositories-and-the-generate-replay-rules.md)
  RULE 3 — *quote, never paraphrase* — says any statement about what a control
  guarantees is a verbatim quotation from `COMPLIANCE.md` or
  `DOMAIN_MODEL.md`, attributed and linked at the captured commit. That leaves
  a practical question the rule does not answer: **where does the quotation
  come from?**

  If `clofin-trace` holds the sentences, it holds a second copy of every
  control claim it displays — in the one repository that is outside audit
  scope, edited by whoever is making the page look nicer, and drifting from
  the document the moment the document changes. That is standing lesson
  **L-4** with the stakes raised: the walkthrough is the artifact most people
  will read.

  So the quotations are captured, like everything else. This namespace reads
  the **captured worktree's** `docs/COMPLIANCE.md` and `docs/DOMAIN_MODEL.md`
  — the tag's copies, not `main`'s — and emits each statement with its source
  file, its line number and a permalink at the captured commit. The
  walkthrough picks quotations by id and can render nothing that is not in
  the fixture.

  **Nothing is summarised, shortened or re-punctuated.** The only
  transformation is unwrapping: a paragraph hard-wrapped across source lines
  becomes one line, because the wrap points are an artifact of an 80-column
  file and not of the sentence."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- unwrap
  "Hard-wrapped source lines as the one paragraph they are."
  [lines]
  (-> (str/join " " (map str/trim lines))
      (str/replace #"\s+" " ")
      str/trim))

(defn- labelled-paragraph
  "The paragraph beginning `**Label.**`, with the label removed, and its line.

  Returns `[text line-number]` or nil. `from` and `to` bound the search to one
  control's section so a label found in the next control is not attributed to
  this one."
  [lines from to label]
  (let [idx (first (keep-indexed
                    (fn [i l] (when (and (>= i from) (< i to)
                                         (str/starts-with? l (str "**" label ".**")))
                                i))
                    lines))]
    (when idx
      (let [body (take-while #(not (str/blank? %)) (drop idx lines))]
        [(-> (unwrap body)
             (str/replace (re-pattern (str "^\\*\\*" (java.util.regex.Pattern/quote label) "\\.\\*\\*\\s*"))
                          ""))
         (inc idx)]))))

(defn- permalink
  [commit file line]
  (format "https://github.com/EchoJustus/clofin-core/blob/%s/%s#L%d" commit file line))

(defn controls
  "Every control in the captured commit's `COMPLIANCE.md` §2.

  Every one, not a chosen few: the walkthrough decides which to show, and a
  fixture holding only the flattering ones would be a partial set produced by
  the harness (**L-6**)."
  [worktree commit]
  (let [file  "docs/COMPLIANCE.md"
        lines (str/split-lines (slurp (io/file worktree file)))
        heads (keep-indexed (fn [i l] (when (re-find #"^### C-\d+" l) i)) lines)
        bounds (partition 2 1 (concat heads [(count lines)]))]
    (when (empty? heads)
      (throw (ex-info (str "capture refuses: no control headings in " file " at " commit)
                      {:file file})))
    (vec
     (for [[from to] bounds
           :let [heading (nth lines from)
                 [_ id title] (re-find #"^### (C-\d+)\s+(.*)$" heading)
                 status (last (re-find #"(✅|🔨|📋)(\s*\([^)]*\))?\s*$" (str/trim heading)))
                 [statement s-line] (labelled-paragraph lines from to "Statement")
                 [boundary b-line]  (labelled-paragraph lines from to "Boundary of this control")]]
       (do
         (when-not statement
           (throw (ex-info (str "capture refuses: control " id " in " file
                                " has no **Statement.** paragraph to quote.")
                           {:control id :file file})))
         (cond-> {"id" id
                  "title" (str/trim (str/replace title #"\s*(✅|🔨|📋).*$" ""))
                  "heading" (str/trim heading)
                  "statement" statement
                  "file" file
                  "line" s-line
                  "url" (permalink commit file s-line)}
           boundary (assoc "boundary" boundary
                           "boundaryLine" b-line
                           "boundaryUrl" (permalink commit file b-line))))))))

(defn invariants
  "Every invariant in the captured commit's `DOMAIN_MODEL.md` §5 table."
  [worktree commit]
  (let [file  "docs/DOMAIN_MODEL.md"
        lines (str/split-lines (slurp (io/file worktree file)))
        rows  (keep-indexed
               (fn [i l]
                 (when-let [[_ id statement enforcement] (re-find #"^\|\s*(I\d+)\s*\|(.*?)\|(.*?)\|\s*$" l)]
                   {"id" id
                    "statement" (str/trim statement)
                    "enforcement" (str/trim enforcement)
                    "file" file
                    "line" (inc i)
                    "url" (permalink commit file (inc i))}))
               lines)]
    (when (empty? rows)
      (throw (ex-info (str "capture refuses: no invariant rows in " file " at " commit)
                      {:file file})))
    (vec rows)))

(defn extract
  "Everything RULE 3 allows the walkthrough to say, as data."
  [worktree commit]
  {"controls"   (controls worktree commit)
   "invariants" (invariants worktree commit)})
