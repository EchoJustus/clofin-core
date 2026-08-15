(ns clofin.build-info
  "What commit is this process running?

  `GET /` answers that question so a client — a person with `curl`, or the
  operator cockpit — can tell which source produced the behaviour in front of
  it. The answer is **self-reported**: the process states what it was told, or
  what it can read beside itself, and nothing here proves that the bytes
  running are the bytes at that commit. That sentence is in `api/openapi.yaml`
  as well, because a field called `sourceCommit` invites exactly the stronger
  reading it does not support.

  ## Resolved, or the literal string \"unknown\"

  There is no third answer, and in particular there is never a *plausible* one.
  011-REQ's objection O-1 is the reason this file is careful: the GitHub
  Releases API returns `target_commitish`, which for most releases is the
  string `\"main\"`, and a client that displayed it under the label \"commit\"
  would show a branch name that is stable, plausible and wrong. The same trap
  is on this side of the wire — `.git/HEAD` usually contains
  `ref: refs/heads/main`, and reporting that would be the identical defect,
  server-side. So:

  - a stamped value is used only if it is a 40-character lower-case hex commit
    id — `CLOFIN_SOURCE_COMMIT=main` is **ignored**, not reported;
  - a git checkout is read down to a commit id, never to the ref name that
    points at one;
  - anything else is `\"unknown\"`, which is a true statement and reads as one.

  ## Why the repository is read rather than `git` being run

  No subprocess. The container image carries no `git` binary, so shelling out
  would work in a checkout and fail silently in the deployment that matters;
  and a payments service that spawns a process at start-up to describe itself
  has acquired an ability it has no other use for. Reading three small files is
  smaller than the code that would handle the process failing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def unknown
  "What `GET /` reports when the commit could not be resolved. A literal, not
  an empty string: a blank field on a screen is indistinguishable from a field
  nobody filled in, and this one means something specific."
  "unknown")

(def env-variable
  "The environment variable `make up` and the container image stamp."
  "CLOFIN_SOURCE_COMMIT")

(def ^:private commit-pattern #"\A[0-9a-f]{40}\z")

(defn commit-id?
  "Is this a resolved commit id — 40 lower-case hex characters, and nothing
  else? Upper case is rejected rather than folded: git writes lower case, and
  something that wrote otherwise is something this function has not seen."
  [value]
  (boolean (and (string? value) (re-matches commit-pattern value))))

(defn- read-first-line
  "The first line of a file, trimmed, or nil if it cannot be read.

  Every failure — absent, a directory, unreadable, not text — is nil. Resolving
  a commit id is best-effort by construction: the caller's fallback is the
  string `\"unknown\"`, which is always available and always true."
  [file]
  (try
    (when (and file (.isFile (io/file file)))
      (with-open [reader (io/reader file)]
        (some-> (.readLine ^java.io.BufferedReader reader) str/trim not-empty)))
    (catch Exception _ nil)))

(defn- git-directories
  "The git directory for `root`, and its common directory if they differ.

  Three shapes are handled, because this project produces all three: an
  ordinary checkout (`.git` is a directory); a linked worktree (`.git` is a
  file containing `gitdir: <path>`, which is how `make capture-trace` runs a
  tagged commit); and a bare-ish layout where `root` *is* the git directory.
  A worktree's own directory holds `HEAD`, while `packed-refs` lives in the
  common directory it names, so both are searched."
  [root]
  ;; A blank root is *not* the working directory. `(io/file nil ".git")` reads
  ;; whatever the process happens to be started in, which would make this
  ;; function's answer depend on something no caller passed it — the ambient
  ;; dependency the rest of this namespace exists to avoid.
  (when-not (str/blank? (str root))
    (let [dot-git (io/file root ".git")
        git-dir (cond
                  (.isDirectory dot-git) dot-git
                  (.isFile dot-git) (when-let [line (read-first-line dot-git)]
                                      (when (str/starts-with? line "gitdir:")
                                        (let [path (str/trim (subs line (count "gitdir:")))
                                              candidate (io/file path)]
                                          (if (.isAbsolute candidate)
                                            candidate
                                            (io/file root path)))))
                  (.isFile (io/file root "HEAD")) (io/file root))
        common (when git-dir
                 (when-let [line (read-first-line (io/file git-dir "commondir"))]
                   (let [candidate (io/file line)]
                     (if (.isAbsolute candidate) candidate (io/file git-dir line)))))]
      (into [] (remove nil?) (distinct [git-dir common])))))

(defn- packed-ref
  "The commit a ref resolves to in `packed-refs`, or nil.

  Lines are `<sha> <refname>`; a line beginning `^` is a tag's dereferenced
  target and belongs to the line above, so it is skipped rather than matched —
  attributing it to this ref would report the wrong commit, confidently."
  [git-dir ref-name]
  (let [file (io/file git-dir "packed-refs")]
    (when (.isFile file)
      (try
        (with-open [reader (io/reader file)]
          (->> (line-seq reader)
               (keep (fn [line]
                       (when-not (or (str/starts-with? line "#") (str/starts-with? line "^"))
                         (let [[sha name] (str/split (str/trim line) #"\s+" 2)]
                           (when (and (= name ref-name) (commit-id? sha)) sha)))))
               first))
        (catch Exception _ nil)))))

(defn- resolve-ref
  "Follow a symbolic ref to a commit id, or nil.

  Bounded to five hops: a ref file may itself contain `ref: …`, and a cycle in
  a repository this process does not own must not become a start-up that never
  finishes."
  [git-dirs ref-name]
  (loop [name ref-name
         hops 0]
    (when (and name (< hops 5))
      (let [loose (some (fn [dir] (read-first-line (io/file dir name))) git-dirs)
            packed (when-not loose (some (fn [dir] (packed-ref dir name)) git-dirs))
            value (or loose packed)]
        (cond
          (commit-id? value) value
          (and value (str/starts-with? value "ref:"))
          (recur (str/trim (subs value (count "ref:"))) (inc hops))
          :else nil)))))

(defn resolve-from-checkout
  "The commit a git checkout at `root` is on, or nil.

  `HEAD` is either a commit id already (a detached checkout, which is what a
  tagged run produces) or `ref: <name>`, which is followed to the commit it
  names. **The ref name itself is never returned** — that is the whole point of
  this function existing rather than a line of `slurp`."
  [root]
  (let [git-dirs (git-directories root)]
    (when (seq git-dirs)
      (let [head (some (fn [dir] (read-first-line (io/file dir "HEAD"))) git-dirs)]
        (cond
          (commit-id? head) head
          (and head (str/starts-with? head "ref:"))
          (resolve-ref git-dirs (str/trim (subs head (count "ref:"))))
          :else nil)))))

(defn resolve-source-commit
  "The commit this process reports, given a stamped value and a checkout root.

  Order: the stamp, then the checkout, then `\"unknown\"`. The stamp wins
  because in a container it is the only truth available — the image carries no
  repository — and it is checked before it wins, so a stamp that is not a
  commit id falls through to the checkout rather than being published.

  Pure with respect to the environment: both inputs are arguments, so every
  branch is a unit test rather than a story about a deployment."
  [stamped root]
  (or (when (commit-id? stamped) stamped)
      (resolve-from-checkout root)
      unknown))
