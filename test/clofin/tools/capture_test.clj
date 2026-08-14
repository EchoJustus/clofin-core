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

(defn- complete-bundle
  []
  (let [p (assoc (stamp (fake-git (answers))) :schema-version-applied "0011")]
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
      :service-info service-info})))

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
