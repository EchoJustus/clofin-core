(ns clofin.tools.markdown
  "Just enough Markdown reading to treat a document as a source of truth.

  This is not a Markdown parser and must not become one. It reads the three
  shapes CloFin's documents actually use — headings, pipe tables and bullet
  lists — because [ADR-0020](../../../docs/ADR/0020-two-repositories-and-the-generate-replay-rules.md)
  RULE 1 says a diagram is generated from its source, and two of the three
  sources are Markdown sections rather than Clojure data.

  Every function here is **total and deterministic**: same file bytes in, same
  value out, no map-iteration order and no clock. A generator whose output
  moves between runs turns `make diagrams-check` into a coin toss, and a check
  that fails intermittently gets deleted within a month (the brief's own
  warning, and the reason L-4 is only half closed).

  Pure: no diagram knowledge, no writing, no I/O beyond reading a named file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn read-lines
  "Every line of `path`, as a vector, with line endings normalised to none.

  Reading through `slurp` rather than `line-seq` so the result does not depend
  on a lazy sequence outliving the reader."
  [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info (str "Cannot generate from a document that does not exist: " path)
                      {:path (str path)})))
    ;; `split` with -1 keeps a trailing empty line, so round-tripping a file
    ;; through this function and `join` is byte-identical.
    (vec (str/split (str/replace (slurp file) "\r\n" "\n") #"\n" -1))))

;; ---------------------------------------------------------------------------
;; Sections
;; ---------------------------------------------------------------------------

(defn- heading-level
  "The ATX heading level of `line`, or nil when it is not a heading."
  [line]
  (when-let [hashes (re-find #"^#{1,6}(?= )" line)]
    (count hashes)))

(defn section
  "The lines of the section whose heading matches `heading-re`, heading included.

  A section runs from its heading to the next heading at the same level or
  above. Throws when the heading is absent or ambiguous: a generator that
  silently produced an empty diagram because a section was renamed would be the
  partial-guard shape standing lesson **L-6** exists to catch."
  [lines heading-re]
  (let [starts (keep-indexed (fn [i line]
                               (when (and (heading-level line) (re-find heading-re line))
                                 i))
                             lines)]
    (when-not (= 1 (count starts))
      (throw (ex-info (str "Expected exactly one heading matching " heading-re
                           ", found " (count starts)
                           ". The document moved and the generator did not.")
                      {:pattern (str heading-re) :matches (count starts)})))
    (let [start (first starts)
          level (heading-level (nth lines start))
          end   (or (first (keep-indexed
                            (fn [i line]
                              (when-let [l (heading-level line)]
                                (when (and (> i start) (<= l level)) i)))
                            lines))
                    (count lines))]
      (subvec lines start end))))

;; ---------------------------------------------------------------------------
;; Tables
;; ---------------------------------------------------------------------------

(defn- table-row?
  [line]
  (str/starts-with? (str/triml line) "|"))

(defn- separator-row?
  [line]
  (re-matches #"\s*\|[\s:|-]+\|\s*" line))

(defn- cells
  "The cells of a pipe-table row, trimmed, without the leading and trailing bar."
  [line]
  (let [trimmed (str/trim line)
        inner   (subs trimmed 1 (max 1 (dec (count trimmed))))]
    (mapv str/trim (str/split inner #"\|" -1))))

(defn first-table
  "The data rows of the first pipe table in `lines`, each as a vector of cells.

  The header row and the `|---|` delimiter beneath it are dropped **by
  position**, never by pattern. Several of CloFin's tables have an empty header
  — `COMPLIANCE.md`'s enforcement-point tables are written `| | |` — and an
  empty header row matches every reasonable delimiter pattern there is. Reading
  by position rather than by shape is what stops the first enforcement point of
  C-05 from silently disappearing off a control map that still looks complete,
  which is standing lesson **L-6** in one line of code.

  Returns nil when `lines` contains no table — callers decide whether that is
  an error, because for some of them it is and for others it is a legitimate
  shape."
  [lines]
  (when-let [start (first (keep-indexed (fn [i l] (when (table-row? l) i)) lines))]
    (let [block (vec (take-while table-row? (drop start lines)))]
      (when-not (and (>= (count block) 2) (separator-row? (nth block 1)))
        (throw (ex-info (str "A pipe table has no delimiter row beneath its header: "
                             (first block))
                        {:row (first block)})))
      (mapv cells (subvec block 2)))))

;; ---------------------------------------------------------------------------
;; Inline formatting
;; ---------------------------------------------------------------------------

(def ^:private status-markers
  "The three status glyphs CloFin's documents use, longest first so that a
  qualified status is recognised before its bare glyph."
  ["✅" "🔨" "📋"])

(defn status-marker
  "The status glyph at the end of `s`, or nil.

  `COMPLIANCE.md` §1 defines the vocabulary — ✅ enforced, 🔨 partial,
  📋 designed, not yet built — and every control heading ends with one,
  optionally followed by a parenthetical qualifier such as `(partial)`."
  [s]
  (let [trimmed (str/trim s)]
    (first (filter (fn [m]
                     (or (str/ends-with? trimmed m)
                         (re-find (re-pattern (str (java.util.regex.Pattern/quote m)
                                                   "\\s+\\([^)]*\\)$"))
                                  trimmed)))
                   status-markers))))

(defn strip-status
  "`s` without a trailing status glyph and its optional qualifier."
  [s]
  (let [trimmed (str/trim s)]
    (str/trim
     (reduce (fn [acc m]
               (-> acc
                   (str/replace (re-pattern (str (java.util.regex.Pattern/quote m)
                                                 "\\s+\\([^)]*\\)$"))
                                "")
                   (str/replace (re-pattern (str (java.util.regex.Pattern/quote m) "$"))
                                "")))
             trimmed
             status-markers))))

(defn plain
  "`s` with inline Markdown reduced to the text a reader would see.

  Links become their text, code spans and emphasis lose their delimiters, and
  runs of whitespace collapse. Applied to a diagram label, because a box
  reading `` `clofin.audit/event` `` with the backticks still in it is a
  generator leaking its source format into its output."
  [s]
  (-> s
      (str/replace #"\[([^\]]*)\]\([^)]*\)" "$1")    ; [text](target) -> text
      (str/replace #"`+" "")
      ;; Every asterisk, not just the ones a lookbehind recognises as opening
      ;; emphasis. A rule that removed `*` only when it was not preceded by a
      ;; word character stripped the opening marker of *emphasis* and left the
      ;; closing one, putting a stray asterisk in a diagram label. No label in
      ;; this repository wants a literal asterisk; a leaked delimiter is the
      ;; only outcome the clever version produces.
      (str/replace #"\*+" "")
      (str/replace #"\s+" " ")
      str/trim))
