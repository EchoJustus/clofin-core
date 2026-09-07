(ns clofin.tools.disclaimer-test
  "`scripts/check-disclaimer.sh`, watched failing.

  The canonical scope sentence has three readers — `GET /`, `make help` and
  every release annotation — and the `ref-2` release audit found them saying
  three different things: four negations from the service, three from `make
  help` (finding **2B-007**), and four-with-a-different-omission from the
  `ref-1` release body (**2B-008**). None was untrue; together they meant the
  boundary a reader learned depended on which surface they opened.

  A check nobody has watched fail is a check nobody knows the shape of, so
  every case here starts from a **passing** fixture tree under
  `test-resources/disclaimer/base`, applies one named edit, and asserts the
  guard notices. The fixture carries its own tiny `Makefile` whose `help`
  target `cat`s the sentence file — the same arrangement the repository's does,
  so what is under test is the *check*, not a restatement of the sentence.

  Standing lesson **L-17**: one mutation that must fail, beside every positive
  assertion."
  (:require [clofin.api.health :as health]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private fixture-root "test-resources/disclaimer/base")
(def ^:private script "scripts/check-disclaimer.sh")

(def ^:private sentence
  (str/trim (slurp (io/file "resources/disclaimer.txt"))))

(defn- copy-tree!
  [^java.io.File from ^java.io.File to]
  (doseq [^java.io.File f (file-seq from)
          :when (.isFile f)]
    (let [rel (subs (.getPath f) (inc (count (.getPath from))))
          dest (io/file to rel)]
      (io/make-parents dest)
      (io/copy f dest))))

(defn- run
  [root]
  (let [{:keys [exit out err]} (shell/sh "sh" script (str root) :out-enc "UTF-8")]
    {:exit exit :out (str out err)}))

(defn- tree!
  "The fixture tree, with `edits` applied. Each edit is `[relative-path f]`,
  where `f` receives the tree root — so an edit may write, delete or add.

  Returns the root."
  [& edits]
  (let [tmp (.toFile (java.nio.file.Files/createTempDirectory
                      "clofin-disclaimer"
                      (into-array java.nio.file.attribute.FileAttribute [])))]
    (copy-tree! (io/file fixture-root) tmp)
    (doseq [edit edits] (edit tmp))
    tmp))

(defn- write!
  [root path content]
  (let [f (io/file root path)]
    (io/make-parents f)
    (spit f content)))

;; ---------------------------------------------------------------------------
;; The check passes on a tree where every surface agrees
;; ---------------------------------------------------------------------------

(deftest the-guard-passes-when-every-surface-carries-the-sentence
  (let [{:keys [exit out]} (run (tree!))]
    (is (zero? exit) out)
    (is (str/includes? out "Disclaimer OK") out)))

(deftest the-guard-passes-with-no-annotation-file-at-all
  (testing "`ref-2`'s annotation is written at tag time, by Master Control,
            after this branch merges — so the check must be green before it
            exists. A guard that required one would block the tag it exists to
            protect"
    (let [{:keys [exit out]}
          (run (tree! (fn [root]
                        (doseq [f (.listFiles (io/file root "docs/releases"))
                                :when (str/ends-with? (.getName ^java.io.File f)
                                                      ".annotation.txt")]
                          (.delete ^java.io.File f)))))]
      (is (zero? exit) out)
      (is (str/includes? out "0 release annotation(s) checked") out))))

;; ---------------------------------------------------------------------------
;; The negative controls (L-17)
;; ---------------------------------------------------------------------------

(deftest an-annotation-without-the-sentence-fails
  (testing "the mutation the rule exists for: a release published from ref-2
            onward whose body omits the canonical sentence"
    (let [{:keys [exit out]}
          (run (tree! (fn [root]
                        (write! root "docs/releases/ref-9.annotation.txt"
                                (str "Date: 2026-09-06\n\n"
                                     "WHAT THIS IS. A release body that says the system is\n"
                                     "synthetic and stops there.\n")))))]
      (is (= 1 exit) out)
      (is (str/includes? out "does not carry the canonical sentence") out)
      (is (str/includes? out "ref-9") out))))

(deftest an-almost-right-sentence-fails
  (testing "verbatim means verbatim. The `ref-1` body denies production
            deployment, attestation, connectivity and real funds and omits the
            regulatory clause — stronger language on four axes does not supply
            the fifth (2B-008), and a check comparing meaning rather than bytes
            would have accepted it"
    (let [without-regulatory
          (str/replace sentence ", holds no regulatory authorisation," ",")]
      (is (not= without-regulatory sentence) "the edit must actually change the sentence")
      (let [{:keys [exit out]}
            (run (tree! (fn [root]
                          (write! root "docs/releases/ref-9.annotation.txt"
                                  (str "Date: 2026-09-06\n\n" without-regulatory "\n")))))]
        (is (= 1 exit) out)
        (is (str/includes? out "does not carry the canonical sentence") out)))))

(deftest a-historical-tag-removed-from-the-list-fails
  (testing "the exemption is what makes `ref-1` pass, and it is recorded in
            `docs/releases/README.md` where a reader sees it. Take the row away
            and the file that has always been short of the sentence fails —
            which is what proves the list is load-bearing rather than
            decorative"
    (let [{:keys [exit out]}
          (run (tree! (fn [root]
                        (write! root "docs/releases/README.md"
                                "# Release annotations (fixture)\n\n### Historical exemptions\n\n| Tag | Reason |\n|---|---|\n"))))]
      (is (= 1 exit) out)
      (is (str/includes? out "ref-0") out)
      (is (str/includes? out "does not carry the canonical sentence") out))))

(deftest a-historical-tag-with-no-reason-fails
  (testing "an exemption nobody has to justify is not an exemption, it is a
            hole — and a hole in a document is how a claim stops being checked"
    (let [{:keys [exit out]}
          (run (tree! (fn [root]
                        (write! root "docs/releases/README.md"
                                "# Release annotations (fixture)\n\n### Historical exemptions\n\n| Tag | Reason |\n|---|---|\n| `ref-0` |  |\n"))))]
      (is (= 1 exit) out)
      (is (str/includes? out "carries no reason") out))))

(deftest a-help-target-that-restates-the-sentence-more-weakly-fails
  (testing "finding 2B-007 exactly: `make help` said synthetic data, no
            institutional connection and no regulatory approval, and omitted
            the never-processes-real-funds clause"
    (let [{:keys [exit out]}
          (run (tree! (fn [root]
                        (write! root "Makefile"
                                (str ".PHONY: help\nhelp:\n"
                                     "\t@echo \"CloFin uses synthetic data only. It is not connected to any bank,\"\n"
                                     "\t@echo \"payment scheme or central bank, and holds no regulatory approval.\"\n")))))]
      (is (= 1 exit) out)
      (is (str/includes? out "does not print the canonical sentence") out))))

(deftest a-missing-or-empty-sentence-file-is-an-error-not-a-skip
  (testing "L-6: a guard that silently checks nothing is indistinguishable, on
            a green build, from a guard that holds"
    (let [gone (run (tree! (fn [root] (.delete (io/file root "resources/disclaimer.txt")))))
          empty* (run (tree! (fn [root] (write! root "resources/disclaimer.txt" "\n"))))
          two (run (tree! (fn [root]
                            (write! root "resources/disclaimer.txt"
                                    (str sentence "\nAnd a second sentence.\n")))))]
      (is (= 1 (:exit gone)) (:out gone))
      (is (str/includes? (:out gone) "is missing") (:out gone))
      (is (= 1 (:exit empty*)) (:out empty*))
      (is (str/includes? (:out empty*) "is empty") (:out empty*))
      (is (= 1 (:exit two)) (:out two))
      (is (str/includes? (:out two) "exactly one non-blank line") (:out two)))))

;; ---------------------------------------------------------------------------
;; The repository's own surfaces
;; ---------------------------------------------------------------------------

(deftest the-service-and-the-file-carry-the-same-bytes
  (testing "`GET /` serves the resource rather than a copy of it, so the two
            cannot drift. This is the assertion that would have caught 2B-007
            had it existed: the sentence has one home"
    (is (= sentence health/disclaimer))
    (is (= sentence
           (get-in ((health/info {:environment :test}) {}) [:body "disclaimer"])))))

(deftest the-real-tree-passes
  (testing "the guard, against the repository it guards — including `ref-1`,
            which passes only because it is recorded as historical with a
            reason"
    (let [{:keys [exit out]} (run ".")]
      (is (zero? exit) out)
      (is (str/includes? out "1 historical")
          (str "ref-1 must be the one recorded exemption — " out)))))
