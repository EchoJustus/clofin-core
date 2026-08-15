(ns clofin.build-info-test
  "Every path that could put something other than a commit id on `GET /`.

  The tests are grouped by the thing they refuse, because refusing is what this
  namespace is for. `resolve-source-commit` has exactly three outcomes and two
  of them are easy; the whole design is about never producing a fourth."
  (:require [clofin.build-info :as build-info]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private a-commit "f10974c7762eb9e095694fcfb3aaa72c0bee4bdf")
(def ^:private another-commit "5c7b4badced5e807e1022fce44cbcad38c6d2095")

(defn- temp-dir ^java.io.File []
  (.toFile (Files/createTempDirectory "clofin-build-info" (into-array FileAttribute []))))

(defn- write! [dir path content]
  (let [file (io/file dir path)]
    (io/make-parents file)
    (spit file content)
    file))

(defn- delete-tree! [^java.io.File file]
  (when (.isDirectory file)
    (run! delete-tree! (.listFiles file)))
  (.delete file))

(defmacro with-dir [[binding] & body]
  `(let [dir# (temp-dir)
         ~binding dir#]
     (try ~@body (finally (delete-tree! dir#)))))

;; ---------------------------------------------------------------------------
;; What counts as a commit id
;; ---------------------------------------------------------------------------

(deftest a-commit-id-is-forty-lower-case-hex-characters-and-nothing-else
  (is (build-info/commit-id? a-commit))
  (doseq [not-one [nil "" "main" "HEAD" "ref: refs/heads/main" "v1.0.0" "ref-1"
                   (str/upper-case a-commit)
                   (subs a-commit 0 7)
                   (str a-commit "0")
                   (str " " a-commit)
                   (str a-commit "\n")
                   (str/replace a-commit #"f" "g")
                   :keyword
                   12345]]
    (testing (pr-str not-one)
      (is (not (build-info/commit-id? not-one))))))

;; ---------------------------------------------------------------------------
;; The stamp
;; ---------------------------------------------------------------------------

(deftest a-stamped-commit-id-is-what-is-reported
  (with-dir [dir]
    (is (= a-commit (build-info/resolve-source-commit a-commit (.getPath dir))))))

(deftest a-stamp-that-is-not-a-commit-id-is-never-reported
  (testing "the 011-REQ O-1 rule, applied to the value this process was handed"
    (with-dir [dir]
      (doseq [stamp ["main" "HEAD" "ref-1" "refs/heads/main" (str/upper-case a-commit) "  " ""]]
        (testing (pr-str stamp)
          (is (= build-info/unknown (build-info/resolve-source-commit stamp (.getPath dir)))
              "a branch name under the label \"commit\" is the defect this refuses"))))))

(deftest a-bad-stamp-falls-through-to-the-checkout-rather-than-to-unknown
  (with-dir [dir]
    (write! dir ".git/HEAD" (str a-commit "\n"))
    (is (= a-commit (build-info/resolve-source-commit "main" (.getPath dir))))))

;; ---------------------------------------------------------------------------
;; Reading a checkout
;; ---------------------------------------------------------------------------

(deftest a-detached-head-is-read-directly
  (testing "which is what a tagged run produces — `make capture-trace`'s shape"
    (with-dir [dir]
      (write! dir ".git/HEAD" (str a-commit "\n"))
      (is (= a-commit (build-info/resolve-source-commit nil (.getPath dir)))))))

(deftest a-branch-is-followed-to-its-commit-and-the-branch-name-never-escapes
  (with-dir [dir]
    (write! dir ".git/HEAD" "ref: refs/heads/main\n")
    (write! dir ".git/refs/heads/main" (str a-commit "\n"))
    (let [resolved (build-info/resolve-source-commit nil (.getPath dir))]
      (is (= a-commit resolved))
      (is (not (str/includes? resolved "main"))))))

(deftest a-branch-with-a-slash-in-its-name-resolves
  (with-dir [dir]
    (write! dir ".git/HEAD" "ref: refs/heads/claude/cockpit-connect-bootstrap-zye3rg\n")
    (write! dir ".git/refs/heads/claude/cockpit-connect-bootstrap-zye3rg" a-commit)
    (is (= a-commit (build-info/resolve-source-commit nil (.getPath dir))))))

(deftest a-packed-ref-resolves-when-there-is-no-loose-one
  (with-dir [dir]
    (write! dir ".git/HEAD" "ref: refs/heads/main\n")
    (write! dir ".git/packed-refs"
            (str "# pack-refs with: peeled fully-peeled sorted \n"
                 another-commit " refs/heads/other\n"
                 a-commit " refs/heads/main\n"
                 another-commit " refs/tags/ref-1\n"
                 "^" another-commit "\n"))
    (is (= a-commit (build-info/resolve-source-commit nil (.getPath dir))))))

(deftest a-peeled-tag-line-is-not-mistaken-for-the-ref-above-it
  (with-dir [dir]
    (write! dir ".git/HEAD" "ref: refs/heads/main\n")
    (write! dir ".git/packed-refs" (str a-commit " refs/heads/main\n^" another-commit "\n"))
    (is (= a-commit (build-info/resolve-source-commit nil (.getPath dir))))))

(deftest a-linked-worktree-follows-its-gitdir-pointer
  (testing "`.git` as a file is how `make capture-trace` checks out a tagged commit"
    (with-dir [dir]
      (let [common (io/file dir "repo" ".git")
            worktree (io/file common "worktrees" "capture")]
        (write! dir "repo/.git/packed-refs" (str a-commit " refs/heads/main\n"))
        (write! worktree "HEAD" "ref: refs/heads/main\n")
        (write! worktree "commondir" "../..\n")
        (write! dir "checkout/.git" (str "gitdir: " (.getPath worktree) "\n"))
        (is (= a-commit (build-info/resolve-source-commit
                         nil (.getPath (io/file dir "checkout")))))))))

;; ---------------------------------------------------------------------------
;; Everything else is "unknown"
;; ---------------------------------------------------------------------------

(deftest no-repository-is-unknown
  (testing "the container case: the image carries no repository, and says so"
    (with-dir [dir]
      (is (= "unknown" (build-info/resolve-source-commit nil (.getPath dir)))))))

(deftest a-repository-that-cannot-be-resolved-is-unknown-not-a-guess
  (doseq [[label files] {"HEAD names a ref that does not exist"
                         {".git/HEAD" "ref: refs/heads/nowhere\n"}

                         "HEAD is a branch name rather than a ref line"
                         {".git/HEAD" "main\n"}

                         "the ref file holds a short sha"
                         {".git/HEAD" "ref: refs/heads/main\n"
                          ".git/refs/heads/main" "f10974c\n"}

                         "the ref file holds another branch name"
                         {".git/HEAD" "ref: refs/heads/main\n"
                          ".git/refs/heads/main" "develop\n"}

                         "HEAD is empty"
                         {".git/HEAD" "\n"}

                         "packed-refs names other branches only"
                         {".git/HEAD" "ref: refs/heads/main\n"
                          ".git/packed-refs" (str another-commit " refs/heads/other\n")}}]
    (testing label
      (with-dir [dir]
        (run! (fn [[path content]] (write! dir path content)) files)
        (is (= "unknown" (build-info/resolve-source-commit nil (.getPath dir))))))))

(deftest a-cycle-of-symbolic-refs-terminates
  (with-dir [dir]
    (write! dir ".git/HEAD" "ref: refs/heads/a\n")
    (write! dir ".git/refs/heads/a" "ref: refs/heads/b\n")
    (write! dir ".git/refs/heads/b" "ref: refs/heads/a\n")
    (is (= "unknown" (build-info/resolve-source-commit nil (.getPath dir)))
        "a repository this process does not own must not be able to hang start-up")))

(deftest a-missing-root-is-unknown
  (is (= "unknown" (build-info/resolve-source-commit nil "/no/such/path/anywhere")))
  (is (= "unknown" (build-info/resolve-source-commit nil nil))))

(deftest this-very-checkout-resolves-to-a-commit-or-says-unknown
  (testing "run against the repository the tests are running in — no third answer"
    (let [resolved (build-info/resolve-source-commit nil ".")]
      (is (or (build-info/commit-id? resolved) (= "unknown" resolved))
          (str "resolved to " (pr-str resolved))))))
