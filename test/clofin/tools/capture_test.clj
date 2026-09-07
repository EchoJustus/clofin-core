(ns clofin.tools.capture-test
  "The capture harness cannot emit an unstamped bundle.

  That sentence is acceptance criterion **AC-2** and standing lesson **L-13**,
  and this namespace is where it stops being a sentence. L-13's shape is a
  precondition that is documented rather than enforced — a parameter named
  `tx`, a docstring promising a transaction — where the runtime check is the
  only thing that makes misuse fail rather than merely look wrong in review.
  A provenance stamp added by convention is the same defect one layer up:
  every bundle would carry one until the day one did not, and the bundle that
  did not would render as a walkthrough with a blank where the commit goes.

  So the tests below do two things. They walk **every** field the stamp
  requires, remove it, and assert that `write!` refuses *and leaves nothing on
  disk* — a refusal that still wrote the file would be worse than no check,
  because the next step in the pipeline copies files. And they assert that the
  walk is exhaustive: `every-required-field-is-exercised` compares the fields
  this namespace removed against `provenance/required`, so a field added to
  the stamp without a test here fails rather than passing unnoticed
  (**L-6** — a guard over the copy the author was looking at is the defect it
  exists to catch)."
  (:require [clofin.tools.capture.bundle :as bundle]
            [clofin.tools.capture.provenance :as prov]
            [clofin.tools.capture.quotations :as quotations]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; A git that answers whatever the test needs it to
;; ---------------------------------------------------------------------------

(def ^:private commit "5c7b4badced5e807e1022fce44cbcad38c6d2095")

(defn fake-git
  "A `provenance/git-runner` substitute driven by a map of answers.

  Keys are the joined argument list; a missing key is a failed command, which
  is what git does when asked about a ref that is not there. Injecting this is
  what makes the refusal paths testable without a repository to damage."
  [answers]
  (fn [& args]
    (let [k (str/join " " args)]
      (if (contains? answers k)
        {:exit 0 :out (get answers k) :err ""}
        {:exit 128 :out "" :err (str "fatal: no answer for `git " k "`")}))))

(def ^:private annotated-message
  (str "ref-1\n\nDate:        2026-08-05\n\n"
       "RELEASE AUDIT: PARTIAL. Charter items 1-4 of 8 were performed.\n"
       "Items 5-7 were NOT performed.\n\n"
       "FINDINGS. 19 raised.\n"))

(defn- answers
  [& {:keys [tag-type tag-message tags]
      :or   {tag-type "commit" tags "ref-1"}}]
  (cond-> {(str "rev-parse --verify --quiet ref-1^{commit}") commit
           (str "tag --points-at " commit)                   tags
           "cat-file -t ref-1"                               tag-type
           "rev-parse HEAD"                                  "00c148d00c148d00c148d00c148d00c148d00c1"
           "status --porcelain"                              ""}
    tag-message (assoc "for-each-ref refs/tags/ref-1 --format=%(contents)" tag-message)))

(def ^:private root (System/getProperty "user.dir"))

(defn- stamp
  [git]
  (prov/stamp {:run git :root root :ref "ref-1" :tag nil
               :captured-at (java.time.Instant/parse "2026-08-12T00:00:00Z")}))

;; ---------------------------------------------------------------------------
;; AC-2 — no resolvable source commit, no bundle
;; ---------------------------------------------------------------------------

(deftest ac-2-an-unresolvable-ref-is-refused
  (testing "a ref git cannot resolve stops the run before anything else happens"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"capture refuses"
         (prov/resolve-commit (fake-git {}) "ref-1"))))

  (testing "a blank ref is refused rather than defaulted"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no source ref"
         (prov/resolve-commit (fake-git {}) "")))))

(deftest ac-2-an-abbreviated-sha-is-not-a-source-commit
  (testing "an answer that is not 40 hex characters is refused, not padded"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"did not resolve to a full commit SHA"
         (prov/resolve-commit
          (fake-git {"rev-parse --verify --quiet ref-1^{commit}" "5c7b4ba"})
          "ref-1")))))

(deftest a-capture-must-be-attributable-to-exactly-one-tag
  (testing "no tag at the captured commit is refused: there is no coverage to show"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no tag points at"
         (prov/resolve-tag (fake-git (answers :tags "")) commit nil))))

  (testing "several tags and no choice is refused rather than guessed"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Name the one to attribute"
         (prov/resolve-tag (fake-git (answers :tags "ref-1\nref-1-rc")) commit nil))))

  (testing "a named tag that points somewhere else is refused"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"does not point at"
         (prov/resolve-tag (fake-git (answers)) commit "ref-2"))))

  (testing "one tag is the tag"
    (is (= "ref-1" (prov/resolve-tag (fake-git (answers)) commit nil)))))

;; ---------------------------------------------------------------------------
;; Release-audit coverage
;; ---------------------------------------------------------------------------

(deftest coverage-comes-from-the-tag-annotation-when-there-is-one
  (let [git (fake-git (answers :tag-type "tag" :tag-message annotated-message))
        coverage (prov/resolve-coverage git root "ref-1")]
    (is (= "git-tag-annotation" (:source coverage))
        "an annotated tag is preferred over the committed mirror")
    (is (= "PARTIAL" (:label coverage)))
    (is (str/starts-with? (:statement coverage) "RELEASE AUDIT: PARTIAL."))
    (is (str/includes? (:statement coverage) "Items 5-7 were NOT performed")
        "the paragraph is quoted whole, not cut at the first sentence")
    (is (not (str/includes? (:statement coverage) "FINDINGS"))
        "and it stops at the end of the paragraph")))

(deftest coverage-falls-back-to-the-committed-mirror-for-a-lightweight-tag
  (testing "ref-1 is lightweight, and its annotation text is the release body"
    (let [coverage (prov/resolve-coverage (fake-git (answers)) root "ref-1")]
      (is (= "release-annotation-file" (:source coverage)))
      (is (= "docs/releases/ref-1.annotation.txt" (:source-ref coverage)))
      (is (= "PARTIAL" (:label coverage))
          "ref-1's release audit was partial: charter items 1-4 of 8")
      (is (str/includes? (:statement coverage) "Charter items 1-4 of 8 were performed"))
      (is (re-matches #"[0-9a-f]{64}" (:source-sha256 coverage))))))

(deftest coverage-that-cannot-be-read-stops-the-capture
  (testing "no annotation and no mirror is a refusal, not a blank"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"carries no coverage paragraph"
         (prov/resolve-coverage
          (fake-git {"cat-file -t ref-9" "commit"}) root "ref-9"))))

  (testing "an annotation with no coverage paragraph is not coverage"
    (is (nil? (prov/coverage-paragraph "ref-9\n\nJust a message.\n")))))

(deftest the-committed-mirror-keeps-the-format-the-harness-reads
  (testing "the real docs/releases/ref-1.annotation.txt still parses"
    (let [text (slurp (io/file root "docs/releases/ref-1.annotation.txt"))
          paragraph (prov/coverage-paragraph text)]
      (is (some? paragraph)
          "a mirror that stopped carrying a RELEASE AUDIT paragraph would stop every capture")
      (is (= "PARTIAL" (prov/coverage-label paragraph))))))

;; ---------------------------------------------------------------------------
;; The fail-closed write
;; ---------------------------------------------------------------------------

(def ^:private service-info
  {:status 200
   :headers {"content-type" "application/json"}
   :body {"disclaimer" "CloFin operates on synthetic data only."}
   :body-raw "{\"disclaimer\":\"CloFin operates on synthetic data only.\"}"
   :body-sha256 (prov/sha256 "{\"disclaimer\":\"CloFin operates on synthetic data only.\"}")
   :disclaimer "CloFin operates on synthetic data only."})

(defn- bundle-with
  "A complete bundle carrying `p` as its stamp — including a `p` that is
  deliberately incomplete, which is what the writer matrix needs."
  [p]
  (bundle/assemble
     {:scenario     {:id "example" :title "Example" :summary "…" :source "docs/uat/UAT-005…"}
      :provenance   p
      :steps        [{:n 1 :id "s1" :kind "http" :title "A call"
                      :request {:method "GET" :path "/"}
                      :response {:status 200 :headers {} :body {} :body-raw "{}"
                                 :body-sha256 (prov/sha256 "{}")}}]
      :organisation "00000000-0000-0000-0000-000000000001"
      :accounts     []
      :journal      []
      :audit-events []
    :sand-table   nil
    :service-info service-info}))

(defn- complete-bundle
  []
  (bundle-with (assoc (stamp (fake-git (answers))) :schema-version-applied "0011")))

(defn- temp-path [name]
  (io/file (System/getProperty "java.io.tmpdir")
           (str "clofin-capture-test-" (random-uuid)) name))

(deftest ac-2-a-complete-bundle-is-written
  (let [path (temp-path "bundle.json")
        {:keys [sha256]} (bundle/write! {:path path :bundle (complete-bundle)
                                         :service-info service-info})
        written (json/read-str (slurp path))]
    (is (re-matches #"[0-9a-f]{64}" sha256))
    (is (= commit (get-in written ["provenance" "sourceCommit"])))
    (is (= "ref-1" (get-in written ["provenance" "tag"])))
    (is (= "PARTIAL" (get-in written ["provenance" "releaseAudit" "label"])))
    (is (= "0011" (get-in written ["provenance" "schemaVersionApplied"])))
    (is (= prov/schema-version (get written "schemaVersion")))
    (.delete (io/file path))))

(def ^:private stamp-fields
  "Every provenance field this namespace removes, one at a time.

  Compared with `provenance/required` by `every-required-field-is-exercised`,
  so that the two cannot drift apart."
  [["sourceCommit"]
   ["sourceCommitShort"]
   ["sourceRef"]
   ["sourceUrl"]
   ["tag"]
   ["tagKind"]
   ["releaseAudit" "label"]
   ["releaseAudit" "statement"]
   ["releaseAudit" "source"]
   ["releaseAudit" "sourceRef"]
   ["releaseAudit" "sourceSha256"]
   ["capturedAt"]
   ["schemaVersionApplied"]
   ["harness" "commit"]])

(deftest ac-2-the-harness-cannot-emit-an-unstamped-bundle
  (doseq [field stamp-fields]
    (testing (str "a bundle missing provenance." (str/join "." field))
      (let [path   (temp-path "bundle.json")
            broken (update-in (complete-bundle) (into ["provenance"] (butlast field))
                              dissoc (last field))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"not fully stamped"
             (bundle/write! {:path path :bundle broken :service-info service-info})))
        (is (not (.exists (io/file path)))
            (str "refusing must leave nothing on disk: a written-then-rejected bundle "
                 "is indistinguishable from output to whatever copies it next")))))

  (testing "a blank value is as absent as a missing key"
    (let [path   (temp-path "bundle.json")
          broken (assoc-in (complete-bundle) ["provenance" "tag"] "   ")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not fully stamped"
                            (bundle/write! {:path path :bundle broken
                                            :service-info service-info})))
      (is (not (.exists (io/file path))))))

  (testing "a plausible-looking but abbreviated commit is refused"
    (let [path   (temp-path "bundle.json")
          broken (assoc-in (complete-bundle) ["provenance" "sourceCommit"] "5c7b4ba")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not fully stamped"
                            (bundle/write! {:path path :bundle broken
                                            :service-info service-info})))
      (is (not (.exists (io/file path))))))

  (testing "coverage that is not the tag's own coverage paragraph is refused"
    (let [path   (temp-path "bundle.json")
          broken (assoc-in (complete-bundle) ["provenance" "releaseAudit" "statement"]
                           "The release audit was fine.")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not fully stamped"
                            (bundle/write! {:path path :bundle broken
                                            :service-info service-info})))
      (is (not (.exists (io/file path)))))))

(deftest every-required-field-is-exercised
  (testing "each field the stamp requires has a removal test above"
    (let [required (set (map (fn [[path _ _]]
                               (mapv (fn [k]
                                       ;; :source-commit -> "sourceCommit"
                                       (let [[head & tail] (str/split (name k) #"-")]
                                         (apply str head (map str/capitalize tail))))
                                     path))
                             @#'prov/required))
          exercised (set stamp-fields)]
      (is (= required exercised)
          (str "provenance/required and this namespace's stamp-fields disagree. "
               "A field added to the stamp without a removal test is a field that "
               "can go missing in production and pass here.")))))

(deftest a-bundle-with-no-steps-is-not-a-capture
  (let [path (temp-path "bundle.json")
        empty (assoc (complete-bundle) "steps" [])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"steps is empty"
                          (bundle/write! {:path path :bundle empty
                                          :service-info service-info})))
    (is (not (.exists (io/file path))))))

(deftest the-scope-statement-must-be-the-captured-one
  (testing "a disclaimer that has been softened is refused at write time"
    (let [path    (temp-path "bundle.json")
          altered (assoc-in (complete-bundle) ["scopeStatement" "disclaimer"]
                            "CloFin operates on synthetic data.")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"differs from the captured GET / fixture"
                            (bundle/write! {:path path :bundle altered
                                            :service-info service-info})))
      (is (not (.exists (io/file path))))))

  (testing "and so is one whose digest does not match the fixture body"
    (let [path    (temp-path "bundle.json")
          altered (assoc-in (complete-bundle) ["scopeStatement" "bodySha256"]
                            (prov/sha256 "something else"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"differs from the digest"
                            (bundle/write! {:path path :bundle altered
                                            :service-info service-info})))
      (is (not (.exists (io/file path)))))))

;; ---------------------------------------------------------------------------
;; The sand table
;; ---------------------------------------------------------------------------

(def ^:private balance-step
  {:n 1 :id "balance-released-1300-IN-TRANSIT" :kind "balance-snapshot"
   :account "1300-IN-TRANSIT"
   :request {:method "GET" :path "/accounts/x/statement"}
   :response {:status 200 :headers {}
              :body {"closingBalance" {"currency" "SGD" "minorUnits" 375000}}
              :body-raw "{}" :body-sha256 (prov/sha256 "{}")}})

(deftest a-sand-table-cell-is-a-captured-value-or-it-does-not-exist
  (testing "a cell repeats the closing balance of the step it names"
    (let [table (bundle/sand-table
                 [balance-step]
                 {:codes ["1300-IN-TRANSIT"]
                  :rows [{:label "released" :after-step-id "released"
                          :snapshots {"1300-IN-TRANSIT" "balance-released-1300-IN-TRANSIT"}}]})
          cell (first (get-in table ["rows" 0 "cells"]))]
      (is (= "balance-released-1300-IN-TRANSIT" (get cell "sourceStep")))
      (is (= {"currency" "SGD" "minorUnits" 375000} (get cell "closingBalance")))))

  (testing "a row naming a step that was never captured is refused"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no such step was recorded"
         (bundle/sand-table
          [balance-step]
          {:codes ["1300-IN-TRANSIT"]
           :rows [{:label "released" :after-step-id "released"
                   :snapshots {"1300-IN-TRANSIT" "balance-that-never-happened"}}]}))))

  (testing "a row reading one account's balance out of another account's step is refused"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"which captured account"
         (bundle/sand-table
          [balance-step]
          {:codes ["1100-CLIENT-FUNDS"]
           :rows [{:label "released" :after-step-id "released"
                   :snapshots {"1100-CLIENT-FUNDS" "balance-released-1300-IN-TRANSIT"}}]}))))

  (testing "a row carries the journal entries that existed when it was taken"
    (let [table (bundle/sand-table
                 [balance-step]
                 {:codes ["1300-IN-TRANSIT"]
                  :rows [{:label "released" :after-step-id "released"
                          :entries ["e1"]
                          :snapshots {"1300-IN-TRANSIT" "balance-released-1300-IN-TRANSIT"}}]})]
      (is (= ["e1"] (get-in table ["rows" 0 "journalEntries"]))
          "without them the cell cannot be checked against the ledger it came from")))

  (testing "a step with no closing balance in it cannot fill a cell"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no closingBalance"
         (bundle/sand-table
          [(assoc-in balance-step [:response :body] {})]
          {:codes ["1300-IN-TRANSIT"]
           :rows [{:label "released" :after-step-id "released"
                   :snapshots {"1300-IN-TRANSIT" "balance-released-1300-IN-TRANSIT"}}]})))))

;; ---------------------------------------------------------------------------
;; AC-6 — the sand table and the journal are the same story
;; ---------------------------------------------------------------------------

(def ^:private transit-account
  {"id" "a1" "code" "1300-IN-TRANSIT" "name" "Settlement in transit"
   "type" "asset" "currency" "SGD" "status" "active"})

(defn- entry
  [id lines]
  {"id" id "narrative" "…" "lines" lines})

(defn- line
  [direction minor]
  {"account_code" "1300-IN-TRANSIT" "direction" direction
   "amount_minor" minor "currency" "SGD"})

(defn- table-with
  [minor-units entries]
  {"accounts" ["1300-IN-TRANSIT"]
   "rows" [{"label" "released" "afterStep" "released" "journalEntries" entries
            "cells" [{"account" "1300-IN-TRANSIT"
                      "sourceStep" "balance-released-1300-IN-TRANSIT"
                      "closingBalance" {"currency" "SGD" "minorUnits" minor-units}}]}]})

(deftest ac-6-the-sand-table-must-agree-with-the-captured-journal
  (let [journal [(entry "e1" [(line "debit" 375000)])
                 (entry "e2" [(line "credit" 125000)])]]

    (testing "a cell equal to the ledger's own balance over the entries present passes"
      (is (= :verified
             (bundle/verify-against-journal! (table-with 375000 ["e1"])
                                             journal [transit-account])))
      (is (= :verified
             (bundle/verify-against-journal! (table-with 250000 ["e1" "e2"])
                                             journal [transit-account]))
          "a debit-normal account's balance falls when it is credited"))

    (testing "a cell that does not is a refusal, naming the row and the account"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"sand table and the journal disagree"
           (bundle/verify-against-journal! (table-with 999999 ["e1"])
                                           journal [transit-account]))))

    (testing "a row that names the wrong set of entries is caught by the same comparison"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"sand table and the journal disagree"
           (bundle/verify-against-journal! (table-with 375000 ["e1" "e2"])
                                           journal [transit-account])))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"sand table and the journal disagree"
           (bundle/verify-against-journal! (table-with 375000 [])
                                           journal [transit-account]))))

    (testing "an account the captured chart does not contain is a refusal, not a zero"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"not in the captured chart of accounts"
           (bundle/verify-against-journal! (table-with 375000 ["e1"]) journal []))))))

;; ---------------------------------------------------------------------------
;; AC-3 (2C-005) — the gate is on every writer, not on the one that had it
;;
;; ADR-0022 promised that a refusal leaves nothing behind that looks like
;; output, because the next step in the pipeline copies files. `write!` and
;; `write-fixture!` kept that promise; `write-quotations!` and `write-manifest!`
;; opened their files with no provenance check at all. The exhaustive
;; required-field test above covered one writer of four while the precondition
;; applied to every artifact sink — standing lesson **L-17**, a guard complete
;; along one dimension of its set and absent along another.
;; ---------------------------------------------------------------------------

(defn- without
  "The stamp with one required field removed, at any depth."
  [stamp path]
  (if (= 1 (count path))
    (dissoc stamp (first path))
    (update-in stamp (vec (butlast path)) dissoc (last path))))

(def ^:private writers
  "Every function in the harness that opens a file, and how to call it.

  Discovered from `clofin.tools.capture.bundle` rather than remembered: the
  `every-writer-is-exercised` test below compares this list with the namespace's
  public `write*!` vars, so a fifth writer added without a row here fails."
  {"write!" (fn [stamp path]
              (bundle/write! {:path path
                              :bundle (bundle-with stamp)
                              :service-info service-info}))
   "write-fixture!" (fn [stamp path]
                      (bundle/write-fixture! {:path path :provenance stamp
                                              :service-info service-info}))
   "write-quotations!" (fn [stamp path]
                         (bundle/write-quotations!
                          {:path path :provenance stamp
                           :quotations {"controls" [{"id" "C-01" "statement" "…"}]
                                        "invariants" [{"id" "I1" "statement" "…"}]}}))
   "write-manifest!" (fn [stamp path]
                       (bundle/write-manifest!
                        {:path path :provenance stamp
                         :fixture {:name "service-info.json" :sha256 "a"}
                         :quotations {:name "quotations.json" :sha256 "b"}
                         :bundles [{:id "example" :title "Example"
                                    :name "bundles/example.json" :sha256 "c"}]}))})

(deftest ac-3-every-writer-refuses-an-incomplete-stamp-and-leaves-nothing-behind
  (let [complete (assoc (stamp (fake-git (answers))) :schema-version-applied "0011")]
    (testing "the positive case first, so the matrix below cannot pass by
              refusing everything"
      (doseq [[name* write!] writers]
        (let [path (temp-path "artifact.json")]
          (is (map? (write! complete path)) (str name* " must write a complete stamp"))
          (is (.exists (io/file path)) name*)
          (.delete (io/file path)))))

    (doseq [[name* write!] writers
            path* @#'prov/required
            :let [field (first path*)]]
      (testing (str name* " with provenance." (str/join "." (map clojure.core/name field)) " absent")
        (let [path (temp-path "artifact.json")]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"not fully stamped"
               (write! (without complete field) path))
              (str name* " wrote an artifact whose stamp was missing "
                   (str/join "." (map clojure.core/name field))))
          (is (not (.exists (io/file path)))
              (str name* " refused and left a file behind: a written-then-rejected "
                   "artifact is indistinguishable from output to whatever copies it next")))))))

(deftest every-writer-is-exercised
  (testing "a fifth writer added to the harness without a row above is a writer
            nothing checks (L-17: enumerate the output sinks, not just the
            fields)"
    (let [discovered (set (keep (fn [[sym var*]]
                                  (let [n (clojure.core/name sym)]
                                    (when (and (re-find #"^write.*!$" n)
                                               (fn? @var*))
                                      n)))
                                (ns-publics 'clofin.tools.capture.bundle)))]
      (is (= discovered (set (keys writers)))
          (str "clofin.tools.capture.bundle exposes " (pr-str (vec (sort discovered)))
               " and this namespace exercises " (pr-str (vec (sort (keys writers)))))))))

(def ^:private sink-primitives
  "Every way a JVM Clojure namespace can put bytes on disk, as the literal text
  that would appear in the source.

  A list rather than `(spit `, because the claim this backstop makes is *the
  four stamped writers are the only door to a file* — and a claim about every
  sink that walks one primitive is a claim about one primitive. `io/writer`
  opens a file as surely as `spit` does, and neither the writer matrix nor the
  `^write.*!$` discovery below would notice a sink named `emit-index!` that
  used it (**L-17**, and **2C-005**'s failure mode with a different spelling)."
  [#"\(spit " #"io/writer" #"io/output-stream" #"io/copy"
   #"FileOutputStream" #"FileWriter" #"PrintWriter"
   #"Files/write" #"Files/newBufferedWriter" #"Files/newOutputStream" #"Files/copy"])

(defn- harness-sources
  "Every source file of the capture harness.

  Both the package directory **and** `capture.clj` beside it. The entrypoint —
  which is what `make capture-trace` runs, and which orchestrates the writers —
  sits outside the directory, so a `file-seq` of the directory alone walks the
  harness minus its front door."
  []
  (conj (->> (file-seq (io/file root "tools/clofin/tools/capture"))
             (filter #(.isFile ^java.io.File %))
             (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
             vec)
        (io/file root "tools/clofin/tools/capture.clj")))

(deftest the-harness-writes-through-those-writers-and-nowhere-else
  (testing "ADR-0022: there is no file sink anywhere else in the capture
            harness, so the gate above is the only door to a file"
    (let [sinks (into {}
                      (for [f (harness-sources)
                            :let [text (slurp f)
                                  n (reduce + (map #(count (re-seq % text)) sink-primitives))]
                            :when (pos? n)]
                        [(.getName ^java.io.File f) n]))]
      (is (= {"bundle.clj" 4} sinks)
          (str "every file the harness opens must go through a stamped writer; found "
               (pr-str sinks)))))

  (testing "and the scan is looking at the whole harness, entrypoint included —
            a set this asserts about must be a set it walks (L-14)"
    (let [names (set (map #(.getName ^java.io.File %) (harness-sources)))]
      (is (contains? names "capture.clj")
          (str "the entrypoint must be scanned; walked " (pr-str (sort names))))
      (is (contains? names "bundle.clj"))
      (is (< 5 (count names))
          (str "the harness is more than a file or two; walked " (pr-str (sort names))))))

  (testing "and the primitive list is not vacuous: a sink written any of these
            ways is seen"
    (doseq [line ["(spit f x)" "(io/writer f)" "(io/copy a f)"
                  "(FileOutputStream. f)" "(java.nio.file.Files/write p b)"]]
      (is (some #(re-find % line) sink-primitives)
          (str "a sink spelled " (pr-str line) " would pass the backstop unseen")))))
;; ---------------------------------------------------------------------------
;; AC-4 (2C-007) — the statement is the whole labelled block
;;
;; Thirteen controls were found and thirteen were reported, and C-13's
;; statement arrived as its introductory sentence with all seven of the
;; guarantees it introduces missing. Complete ID coverage masked empty content
;; coverage (standing lesson **L-17**).
;;
;; The expectation below is sliced out of the raw file by a **different method**
;; — a regex over the file's text, rather than the extractor's line-index scan —
;; because two implementations of one rule that can only agree prove nothing
;; (standing lesson **L-16**).
;; ---------------------------------------------------------------------------

(defn- statement-by-regex
  "Every control's statement, sliced from the raw file text.

  Independent of `clofin.tools.capture.quotations`: this splits the file on
  `### C-nn` headings, then takes from `**Statement.**` to the next
  paragraph-leading `**`, heading or rule, by pattern rather than by index."
  [text]
  (into {}
        (for [section (rest (str/split text #"(?m)^### (?=C-\d+)"))
              :let [id (second (re-find #"^(C-\d+)" section))
                    body (second (re-find #"(?s)\*\*Statement\.\*\*(.*?)(?:\n\n\*\*|\n#{1,6} |\n-{3,}\n|\z)"
                                          section))]
              :when (and id body)]
          [id (-> body (str/replace #"\s+" " ") str/trim)])))

(defn- normalise [s] (-> (str s) (str/replace #"\s+" " ") str/trim))

(deftest ac-4-every-control-statement-is-quoted-whole
  (let [text (slurp (io/file root "docs/COMPLIANCE.md"))
        extracted (into {} (for [c (quotations/controls root commit)]
                             [(get c "id") (get c "statement")]))
        expected (statement-by-regex text)]
    (testing "the discovered population is not empty, and the two methods found
              the same controls (L-17: assert non-vacuity after discovery)"
      (is (seq extracted))
      (is (= (set (keys extracted)) (set (keys expected)))
          (str "the extractor found " (pr-str (vec (sort (keys extracted))))
               " and the raw-text slice found " (pr-str (vec (sort (keys expected)))))))

    (doseq [[id statement] (sort extracted)]
      (testing (str id "'s statement is the text between its markers")
        (is (= (normalise (get expected id)) (normalise statement))
            (str id " differs between the extractor and an independent slice of "
                 "docs/COMPLIANCE.md"))))))

(deftest ac-4-c-13-carries-its-seven-numbered-guarantees
  (testing "the finding itself: at the RC this statement was 74 characters —
            its introductory sentence — and a consumer restricted to the
            fixture could not display the guarantees it exists to quote"
    (let [c13 (->> (quotations/controls root commit)
                   (filter #(= "C-13" (get % "id")))
                   first
                   (#(get % "statement")))]
      (is (some? c13))
      (is (= 7 (count (re-seq #"(?m)^\d+\. " c13)))
          (str "C-13 states seven numbered guarantees and the fixture must carry "
               "all seven; it carries " (pr-str (vec (re-seq #"(?m)^\d+\. " c13)))))
      (doseq [phrase ["is either matched to exactly one"
                      "that no line matched is a break"
                      "has an owner and a derived age"
                      "No reconciliation writes to the journal"
                      "posts only after that band's number of approvals"
                      "is refused says so, and says who and why"
                      "is recorded as having arrived"]]
        (is (str/includes? c13 phrase)
            (str "C-13's fixture is missing: " (pr-str phrase))))
      (testing "and the two things it explicitly does not claim, which L-14
                requires to travel with the claims"
        (is (str/includes? c13 "does **not** claim"))))))

(deftest ac-4-a-bold-run-is-a-label-only-when-it-starts-a-paragraph
  (testing "the negative control for the block rule. `docs/COMPLIANCE.md` holds
            both shapes: labels whose bold run wraps onto the next line before
            closing, and mid-paragraph lines that open with `**`. A rule keyed
            on the closing `**` truncates at the second; a rule keyed on the
            blank line before does not"
    (let [fixture (str "### C-99 A fixture control ✅\n"
                       "\n"
                       "**Statement.** The first sentence, hard wrapped across\n"
                       "**this** line, which opens with a bold run and is not a label.\n"
                       "\n"
                       "1. **A numbered guarantee.** With its own qualifying sentence.\n"
                       "\n"
                       "A closing paragraph that is still the statement.\n"
                       "\n"
                       "**Design.** This is where the statement stops.\n"
                       "\n"
                       "**Evidence.** And this is well past it.\n")
          dir (io/file (System/getProperty "java.io.tmpdir") (str "clofin-quot-" (random-uuid)))
          file (io/file dir "docs/COMPLIANCE.md")]
      (io/make-parents file)
      (spit file fixture)
      (try
        (let [statement (get (first (quotations/controls (str dir) commit)) "statement")]
          (is (str/includes? statement "**this** line")
              (str "a line opening with a bold run mid-paragraph is not a label, so "
                   "the statement must not stop there — got " (pr-str statement)))
          (is (str/includes? statement "A numbered guarantee")
              "a numbered item is part of the statement, not the end of it")
          (is (str/includes? statement "A closing paragraph that is still the statement")
              "a second paragraph is part of the statement")
          (is (not (str/includes? statement "This is where the statement stops"))
              "and the next label ends it")
          (is (not (str/includes? statement "well past it"))
              "as does everything after that label")
          (testing "each block is one line, so seven guarantees render as seven"
            (is (= 3 (count (str/split-lines statement))) (pr-str statement))))
        (finally
          (.delete file)
          (.delete (io/file dir "docs"))
          (.delete dir))))))
