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
  file and not of the sentence. Paragraphs and list items stay apart, joined by
  a newline — a control that states seven numbered guarantees states seven
  things.

  ## The statement is the whole labelled block

  It was the first paragraph, and that is release-audit finding **2C-007**: the
  extractor stopped at the first blank line, so C-13's statement arrived as its
  introductory sentence — *\"Read each sentence with its named set, because each
  is bounded on purpose\"* — with all seven of the guarantees it introduces
  missing. Thirteen controls were found and thirteen were reported, which is
  what made it invisible: **complete along the id dimension and empty along the
  content one** (standing lesson **L-17**). A consumer restricted to the fixture
  could not display the guarantees it exists to quote.

  A labelled block now runs to the next bold label, heading or horizontal rule.
  See `label-line?` for why *starting a paragraph* — and not the closing `**` —
  is what makes a bold run a label."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- unwrap
  "Hard-wrapped source lines as the one paragraph they are."
  [lines]
  (-> (str/join " " (map str/trim lines))
      (str/replace #"\s+" " ")
      str/trim))

(defn- list-item?
  [line]
  (boolean (re-find #"^\s*(?:\d+\.|[-*+])\s" line)))

(defn- blocks
  "Source lines grouped into the blocks a reader sees.

  A block is one paragraph or one item of a list. Hard wrapping is undone
  *inside* a block; the blocks themselves stay apart, because a control that
  states seven numbered guarantees states seven things and a consumer has to be
  able to render them as seven rather than as one run-on sentence."
  [lines]
  (->> lines
       (reduce (fn [acc line]
                 (cond
                   (str/blank? line)   (conj acc [])
                   (list-item? line)   (conj acc [line])
                   :else               (if (seq acc)
                                         (update acc (dec (count acc)) conj line)
                                         [[line]])))
               [[]])
       (remove empty?)
       vec))

(defn- label-line?
  "Does this line begin a **bold-labelled** block?

  A bold run at the start of a line is not enough, and telling the two apart is
  the whole of release-audit finding **2C-007**. `docs/COMPLIANCE.md` contains
  seven lines that open with `**` in the middle of a hard-wrapped paragraph —
  `**F-003** reproduced it`, `**account 1 / event 0** and …` — and four labels
  whose own bold run wraps onto the next line before it closes. What separates
  them is not the closing `**`: it is that a label **starts a paragraph**. So
  the previous line must be blank, which is true of every one of the file's 76
  labels and of none of its 7 continuations."
  [lines i]
  (and (pos? i)
       (str/blank? (nth lines (dec i)))
       (str/starts-with? (nth lines i) "**")))

(defn- block-end
  "Where a labelled block stops.

  The next bold label at line start, the next heading, or the next horizontal
  rule — whichever comes first inside this control's own section. **Not the
  next blank line**, which is what it used to be: a statement made of numbered
  guarantees, or of more than one paragraph, was silently truncated to its
  introductory sentence, and C-13 lost all seven of its guarantees while the
  fixture reported complete control coverage (**2C-007**, standing lesson
  **L-17** — complete along the id dimension and empty along the content one)."
  [lines from to]
  (or (first (keep-indexed
              (fn [i l]
                (when (and (> i from) (< i to)
                           (or (label-line? lines i)
                               (re-find #"^#{1,6}\s" l)
                               (re-matches #"-{3,}\s*" l)))
                  i))
              lines))
      to))

(defn- labelled-paragraph
  "The block beginning `**Label.**`, with the label removed, and its line.

  Returns `[text line-number]` or nil. `from` and `to` bound the search to one
  control's section so a label found in the next control is not attributed to
  this one. The text runs to the end of the labelled block — every paragraph,
  every list item and every blank line between them — with each block unwrapped
  and the blocks joined by a newline."
  [lines from to label]
  (let [idx (first (keep-indexed
                    (fn [i l] (when (and (>= i from) (< i to)
                                         (str/starts-with? l (str "**" label ".**")))
                                i))
                    lines))]
    (when idx
      (let [body (subvec (vec lines) idx (block-end lines idx to))]
        [(-> (str/join "\n" (map unwrap (blocks body)))
             (str/replace (re-pattern (str "^\\*\\*" (java.util.regex.Pattern/quote label) "\\.\\*\\*\\s*"))
                          "")
             str/trim)
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
