(ns clofin.tools.capture.provenance
  "Where a captured bundle's stamp comes from, and why it cannot be absent.

  [ADR-0020](../../../../docs/ADR/0020-two-repositories-and-the-generate-replay-rules.md)
  RULE 2 — *replay, never fake* — is only worth anything if the stamp is
  reliable. `clofin-trace` renders whatever it is given; if a bundle could
  reach it carrying a plausible-looking but unresolved commit, the walkthrough
  would attribute captured behaviour to a source state nobody can check, and
  the boundary that keeps the trace repository out of release-audit scope
  would be a hole rather than a definition.

  So every value in the stamp is **resolved from an artifact, never supplied
  by whoever runs the capture**, and every resolution refuses rather than
  guesses. Standing lesson **L-13**: a load-bearing precondition that depends
  on remembering to satisfy it is documentation, not enforcement. The
  enforcement point is `validate!`, and `clofin.tools.capture.bundle/write!`
  is the only path to disk.

  ## The four things resolved here

  | Value | Resolved from | Refuses when |
  |---|---|---|
  | Source commit | `git rev-parse <ref>^{commit}` in this repository | the ref does not resolve to a 40-hex commit |
  | Tag | `git tag --points-at <commit>` | no tag points at the commit, or several do and none was named |
  | Release-audit coverage | the tag's annotation, else its committed release-annotation mirror | neither carries a `RELEASE AUDIT:` paragraph |
  | Applied schema version | the running stack's own `GET /readyz`, cross-checked against the commit's migration index | the two disagree |

  ## Why coverage is captured rather than written

  `ref-1`'s release audit was partial — charter items 1–4 of 8. A walkthrough
  that says \"audited\" of that state is standing lesson **L-14** in the
  project's most public artifact. A coverage sentence *typed* into a page is a
  claim somebody must remember to update when `ref-2` lands; a coverage
  statement *read from the tag* updates itself. The parsing here is therefore
  deliberately thin — locate the paragraph, quote it whole — because anything
  that summarised it would be minting a claim rather than inheriting one.

  ## Where `ref-1`'s annotation actually lives

  `docs/audits/README.md` says `ref-<n>` tags are annotated \"with the date and
  RC SHA in the tag message\", and `docs/ROADMAP.md` says of the partial audit
  that \"the tag annotation says so\". Neither is true of the artifact:
  `refs/tags/ref-1` is a **lightweight** tag — `git cat-file -t ref-1` answers
  `commit`, and `git ls-remote origin refs/tags/*` shows no peeled `^{}` line.
  The text both documents describe exists, in full, as the body of the GitHub
  **release** published on that tag.

  Rather than refuse to capture anything (which would fail closed, correctly,
  and deliver nothing), this namespace accepts two sources and records which
  one it used in the stamp:

  1. **`git-tag-annotation`** — the mechanism the protocol describes. Preferred
     whenever the tag is annotated, so nothing here needs changing if `ref-1`
     is re-tagged or `ref-2` is tagged as the protocol says.
  2. **`release-annotation-file`** — `docs/releases/<tag>.annotation.txt`, a
     committed byte-for-byte mirror of the release body, reviewed in the pull
     request that added it and re-checkable at any time against the live
     release with `make check-release-annotation`.

  Both are artifacts under review. Neither is a value typed at capture time,
  which is the property that matters. See `007-REQ` objection **O-1**."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Digests
;; ---------------------------------------------------------------------------

(defn sha256
  "Lower-case hex SHA-256 of a string's UTF-8 bytes.

  Used for the annotation text and for every captured body, so that a bundle,
  a fixture and a rendered page can be compared with each other by value
  rather than by trust."
  [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

;; ---------------------------------------------------------------------------
;; Talking to git
;; ---------------------------------------------------------------------------

(defn git-runner
  "A function of git arguments returning `clojure.java.shell/sh`'s map.

  Injected rather than called directly so that the refusal paths — an
  unresolvable ref, a git that is not there at all — are unit-testable without
  a repository to break. `clofin.tools.capture-test` supplies a fake."
  [root]
  (fn [& args]
    (apply shell/sh (concat ["git" "-C" (str root)] args))))

(defn- git
  "Run git, returning trimmed stdout, or nil when the command failed."
  [run & args]
  (let [{:keys [exit out]} (apply run args)]
    (when (zero? exit) (str/trim (or out "")))))

(defn- git!
  "Run git, or throw with what was run and what it said.

  The message names the command because the operator reading it is usually
  standing in the wrong directory or has no tags fetched, and both are one
  command away from fixed."
  [run & args]
  (let [{:keys [exit out err]} (apply run args)]
    (when-not (zero? exit)
      (throw (ex-info (format "capture refuses: `git %s` failed (exit %s): %s"
                              (str/join " " args) exit (str/trim (str err)))
                      {:command (vec args) :exit exit :stderr (str/trim (str err))})))
    (str/trim (or out ""))))

;; ---------------------------------------------------------------------------
;; The source commit
;; ---------------------------------------------------------------------------

(def ^:private full-sha #"^[0-9a-f]{40}$")

(defn resolve-commit
  "The 40-hex commit `ref` names, or a refusal (AC-2).

  `^{commit}` peels an annotated tag to the commit it points at, so the same
  call works for a branch, a tag of either kind and a raw SHA. A partial or
  abbreviated answer is refused rather than padded: an eleven-character SHA on
  a page is a SHA a reader cannot paste into a URL."
  [run ref]
  (when (str/blank? (str ref))
    (throw (ex-info "capture refuses: no source ref was given, so no source commit can be resolved."
                    {:ref ref})))
  (let [commit (git! run "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))]
    (when-not (re-matches full-sha commit)
      (throw (ex-info (format "capture refuses: `%s` did not resolve to a full commit SHA (got %s)."
                              ref (pr-str commit))
                      {:ref ref :resolved commit})))
    commit))

(defn tags-at
  "Every tag pointing at `commit`, sorted."
  [run commit]
  (->> (or (git run "tag" "--points-at" commit) "")
       str/split-lines
       (map str/trim)
       (remove str/blank?)
       sort
       vec))

(defn resolve-tag
  "The one tag this capture is attributed to.

  A bundle without a tag is refused. The walkthrough shows the tag, the SHA
  and the tag's audit coverage **together** (AC-8) precisely so that a reader
  cannot supply the missing word themselves, and the missing word they would
  supply is \"audited\". A capture of an untagged commit has no coverage to
  show, so it is not a capture this harness will stamp."
  [run commit requested]
  (let [available (tags-at run commit)]
    (cond
      (seq (str requested))
      (do (when-not (some #{requested} available)
            (throw (ex-info (format (str "capture refuses: tag %s does not point at %s. "
                                         "Tags at that commit: %s")
                                    requested commit
                                    (if (seq available) (str/join ", " available) "none"))
                            {:requested requested :commit commit :available available})))
          requested)

      (= 1 (count available)) (first available)

      (empty? available)
      (throw (ex-info (format (str "capture refuses: no tag points at %s. A bundle is stamped with "
                                   "its tag's release-audit coverage; an untagged commit has none.")
                              commit)
                      {:commit commit}))

      :else
      (throw (ex-info (format (str "capture refuses: %d tags point at %s (%s). "
                                   "Name the one to attribute this capture to with --tag.")
                              (count available) commit (str/join ", " available))
                      {:commit commit :available available})))))

(defn tag-kind
  "`\"annotated\"` when the tag is a tag object, `\"lightweight\"` when the ref
  points straight at a commit.

  Recorded in the stamp rather than hidden, because it is the difference
  between coverage read from the tag itself and coverage read from a committed
  mirror of the release body, and a reader is entitled to know which."
  [run tag]
  (case (git run "cat-file" "-t" tag)
    "tag"    "annotated"
    "commit" "lightweight"
    "unknown"))

(defn tag-annotation
  "The annotated tag's message, or nil for a lightweight tag."
  [run tag]
  (when (= "annotated" (tag-kind run tag))
    (let [contents (git run "for-each-ref" (str "refs/tags/" tag) "--format=%(contents)")]
      (when-not (str/blank? contents) contents))))

;; ---------------------------------------------------------------------------
;; Release-audit coverage
;; ---------------------------------------------------------------------------

(def coverage-heading
  "The label that opens the coverage paragraph in a release annotation.

  A convention, stated in `docs/releases/README.md` and enforced here by
  refusal: an annotation without this paragraph produces no stamp and
  therefore no bundle."
  "RELEASE AUDIT:")

(defn coverage-paragraph
  "The verbatim paragraph of `annotation` that states the audit's coverage.

  A paragraph, not a summary. Quoting it whole is the only reading of RULE 3
  available to a machine: any narrowing — first sentence, or the word after
  the colon on its own — would be this harness deciding which part of an audit
  finding matters, in a repository whose dominant defect class is a claim
  narrower than the set it quantifies over (**L-14**).

  Returns nil when the annotation has no such paragraph; refusing is the
  caller's job, so that the message can name the file it looked in."
  [annotation]
  (when annotation
    (let [normalised (str/replace annotation "\r\n" "\n")
          paragraphs (str/split normalised #"\n[ \t]*\n")]
      (some (fn [p]
              (let [joined (str/trim (str/replace p #"\s*\n\s*" " "))]
                (when (str/starts-with? joined coverage-heading) joined)))
            paragraphs))))

(defn coverage-label
  "The short verdict the coverage paragraph opens with — `PARTIAL`, `COMPLETE`.

  Read as \"whatever stands between the heading and the first full stop\",
  which is the only part of the paragraph short enough for a provenance chip.
  The chip never appears without the paragraph beside it (AC-8), so the
  shortening cannot be what a reader takes away."
  [paragraph]
  (some-> paragraph
          (subs (count coverage-heading))
          (str/split #"\.")
          first
          str/trim
          not-empty))

(defn release-annotation-path
  "Where a tag's committed release-annotation mirror lives."
  [root tag]
  (io/file (str root) "docs/releases" (str tag ".annotation.txt")))

(defn resolve-coverage
  "The tag's recorded release-audit coverage, and where it was read from.

  Order is deliberate: the tag's own annotation first, so that re-tagging
  `ref-1` annotated — or tagging `ref-2` as the protocol already describes —
  silently upgrades the source with nothing here to change. The committed
  mirror is the fallback, and the stamp says so.

  Refuses when neither carries a coverage paragraph. That refusal is the whole
  point: a bundle with no coverage would render a tag and a SHA with a blank
  where the qualifier goes, and AC-11's check would then be guarding a page
  that had already lost the thing it guards."
  [run root tag]
  (let [annotation (tag-annotation run tag)
        from-tag   (coverage-paragraph annotation)]
    (if from-tag
      {:label       (coverage-label from-tag)
       :statement   from-tag
       :source      "git-tag-annotation"
       :source-ref  (str "refs/tags/" tag)
       :source-sha256 (sha256 annotation)}
      (let [file (release-annotation-path root tag)]
        (when-not (.isFile file)
          (throw (ex-info (format (str "capture refuses: tag %s is %s and carries no coverage "
                                       "paragraph, and there is no committed mirror at %s. "
                                       "A bundle is stamped with the tag's release-audit coverage; "
                                       "this harness will not emit one without it.")
                                  tag (tag-kind run tag)
                                  (str "docs/releases/" tag ".annotation.txt"))
                          {:tag tag :expected-file (str file)})))
        (let [text      (str/replace (slurp file) "\r\n" "\n")
              paragraph (coverage-paragraph text)]
          (when-not paragraph
            (throw (ex-info (format (str "capture refuses: %s contains no paragraph beginning "
                                         "\"%s\", so the tag's release-audit coverage cannot be read.")
                                    (str file) coverage-heading)
                            {:tag tag :file (str file)})))
          {:label         (coverage-label paragraph)
           :statement     paragraph
           :source        "release-annotation-file"
           :source-ref    (str "docs/releases/" tag ".annotation.txt")
           :source-sha256 (sha256 text)})))))

;; ---------------------------------------------------------------------------
;; The stamp
;; ---------------------------------------------------------------------------

(def schema-version
  "The bundle schema this harness emits.

  Changed when a consumer would have to change with it. `clofin-trace`'s
  `provenance-present` check refuses a bundle whose schema version it does not
  know, so a silently reshaped bundle fails there rather than rendering as
  blanks."
  "clofin.capture/1")

(defn harness-provenance
  "Which commit of the harness produced this bundle, and whether it was clean.

  Separate from the captured source commit and never conflated with it: the
  harness runs from `main` while the stack runs from the tag, and a reader who
  saw one SHA would reasonably assume it was the other. A dirty harness tree
  is recorded rather than refused — the capture is still of a clean tagged
  source — but it is recorded, because an unreproducible harness is worth
  knowing about."
  [run]
  (let [commit (or (git run "rev-parse" "HEAD") "unknown")
        status (or (git run "status" "--porcelain") "")]
    {:commit commit
     :dirty? (not (str/blank? status))}))

(defn source-url
  "The captured commit's tree on GitHub — the link every page carries."
  [commit]
  (str "https://github.com/EchoJustus/clofin-core/tree/" commit))

(defn stamp
  "Everything a bundle is stamped with, resolved from artifacts.

  `captured-at` is passed in rather than read here so that one capture run
  stamps every bundle with one instant, and so that the value is visible at
  the call site rather than hidden in a function that reads a clock."
  [{:keys [run root ref tag captured-at]}]
  (let [commit   (resolve-commit run ref)
        resolved (resolve-tag run commit tag)
        coverage (resolve-coverage run root resolved)]
    {:source-commit       commit
     :source-commit-short (subs commit 0 7)
     :source-ref          (str ref)
     :source-url          (source-url commit)
     :tag                 resolved
     :tag-kind            (tag-kind run resolved)
     :release-audit       coverage
     :captured-at         (str (or captured-at (Instant/now)))
     :harness             (harness-provenance run)}))

;; ---------------------------------------------------------------------------
;; The enforcement point
;; ---------------------------------------------------------------------------

(def ^:private required
  "Every field a stamp must carry, as `[path predicate description]`.

  A list rather than a chain of `when-not`s so that the test which asserts the
  harness cannot emit an unstamped bundle can walk it: a field added here
  without a test is caught by that walk, rather than by nobody."
  [[[:source-commit]                 #(and (string? %) (re-matches full-sha %))
    "a 40-character source commit SHA"]
   [[:source-commit-short]           #(and (string? %) (= 7 (count %)))
    "the short form of the source commit"]
   [[:source-ref]                    #(not (str/blank? (str %)))
    "the ref the capture was asked for"]
   [[:source-url]                    #(str/starts-with? (str %) "https://github.com/")
    "a link to the source commit"]
   [[:tag]                           #(not (str/blank? (str %)))
    "the tag the captured commit carries"]
   [[:tag-kind]                      #{"annotated" "lightweight"}
    "whether that tag is annotated or lightweight"]
   [[:release-audit :label]          #(not (str/blank? (str %)))
    "the tag's release-audit verdict"]
   [[:release-audit :statement]      #(str/starts-with? (str %) coverage-heading)
    "the tag's verbatim release-audit coverage paragraph"]
   [[:release-audit :source]         #{"git-tag-annotation" "release-annotation-file"}
    "where the coverage was read from"]
   [[:release-audit :source-ref]     #(not (str/blank? (str %)))
    "the artifact the coverage was read from"]
   [[:release-audit :source-sha256]  #(re-matches #"^[0-9a-f]{64}$" (str %))
    "the digest of that artifact"]
   [[:captured-at]                   #(try (Instant/parse (str %)) (catch Exception _ false))
    "the capture instant"]
   [[:schema-version-applied]        #(re-matches #"^\d{4}$" (str %))
    "the schema version the captured stack reported"]
   [[:harness :commit]               #(not (str/blank? (str %)))
    "the harness commit"]])

(defn problems
  "Every reason `provenance` is not a stamp, as sentences. Empty means valid."
  [provenance]
  (vec
   (for [[path pred description] required
         :let [value (get-in provenance path)]
         :when (not (try (boolean (pred value)) (catch Exception _ false)))]
     (format "%s is missing or invalid (%s): %s"
             (str/join "." (map name path)) description (pr-str value)))))

(defn validate!
  "Throw unless `provenance` is a complete stamp.

  The single gate. `clofin.tools.capture.bundle/write!` calls this **before**
  it opens a file, so a bundle that fails here leaves nothing on disk to be
  mistaken for output — the fail-closed half of AC-2 and standing lesson
  **L-13**. `context` names what was being written, because the operator
  reading this needs to know which of three scenarios stopped."
  [provenance context]
  (let [found (problems provenance)]
    (when (seq found)
      (throw (ex-info (str "capture refuses to write " context
                           ": the provenance stamp is incomplete.\n  - "
                           (str/join "\n  - " found))
                      {:context context :problems found})))
    provenance))
