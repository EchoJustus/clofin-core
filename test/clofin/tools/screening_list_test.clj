(ns clofin.tools.screening-list-test
  "The screening-list loader (TASK-017 A-5, AC-17-8): what it refuses at load,
  that exactly one list is accepted at a time, and that a replacement retires
  the old and loads the new in one transaction.

  Every list here is synthetic. The fixture starts each test with the shipped
  list accepted, as a deployment does after `make load-screening-list`."
  (:require [clofin.db.core :as db]
            [clofin.screening.repository :as screening]
            [clofin.test-db :as tdb]
            [clofin.tools.screening-list :as tool]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private shipped "synthetic-2026-10-v1")

(defn- a-list
  ([version] (a-list version [{:id "SYN-9001" :rules [{:field :creditor-name :operator :exact
                                                       :value "Synthetic Test Entry Ltd"}]}]))
  ([version entries] {:version version :entries entries}))

(defn- refusal
  "The `:reason` a load or retirement was refused under, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo t (or (:reason (ex-data t)) (ex-message t)))))

(defn- list-rows [] (db/query tdb/*pool* ["select version, retired_at from screening_list order by version"]))

(defn- accepted [] (mapv :version (filter (comp nil? :retired-at) (list-rows))))

(deftest a-list-with-an-originator-rule-is-refused-at-load
  (testing "ADR-0028 D5: an originator-* rule has no per-instruction value in
            core, so a list carrying one is refused at load, naming the entry and
            the field — and nothing reaches the database"
    (let [before (list-rows)
          t (try (tool/load-list! tdb/*pool*
                                  (a-list "synthetic-2026-10-v2"
                                          [{:id "SYN-0001" :rules [{:field :creditor-name :operator :exact :value "A"}]}
                                           {:id "SYN-0002" :rules [{:field :originator-name :operator :exact :value "B"}]}])
                                  {:source "test" :replacing shipped})
                 nil
                 (catch clojure.lang.ExceptionInfo t t))]
      (is (some? t))
      (is (= "unsupported-rule-field" (:reason (ex-data t))))
      (is (= {:entry "SYN-0002" :field "originator-name"} (select-keys (ex-data t) [:entry :field])))
      (is (= before (list-rows)) "nothing was written, the old list was not retired"))))

(deftest ac-17-8-every-other-refusal-in-a-5
  (doseq [[label expected list*]
          [["an operator other than exact" "unsupported-operator"
            (a-list "v-op" [{:id "E" :rules [{:field :creditor-name :operator :fuzzy :value "A"}]}])]
           ["a blank value" "blank-rule-value"
            (a-list "v-blank" [{:id "E" :rules [{:field :creditor-name :operator :exact :value "  "}]}])]
           ["a duplicate entry id" "duplicate-entry-id"
            (a-list "v-dup" [{:id "E" :rules [{:field :creditor-name :operator :exact :value "A"}]}
                             {:id "E" :rules [{:field :creditor-name :operator :exact :value "B"}]}])]
           ["an empty entries vector" "empty-entries" (a-list "v-empty" [])]
           ["an entry with no rules" "empty-rules" (a-list "v-norules" [{:id "E" :rules []}])]
           ["a version with a space" "invalid-version" (a-list "synthetic 2026")]
           ["a version of 129 characters" "invalid-version" (a-list (apply str (repeat 129 "v")))]
           ["a non-ASCII version" "invalid-version" (a-list "synthétique")]]]
    (testing label
      (let [before (list-rows)]
        (is (= expected (refusal #(tool/load-list! tdb/*pool* list* {:source "test" :replacing shipped}))))
        (is (= before (list-rows)) "and nothing was written")))))

(deftest ac-17-8-exactly-one-list-is-accepted-at-a-time
  (testing "a second version while one is accepted is refused without --replacing"
    (is (= "a-list-is-already-accepted"
           (refusal #(tool/load-list! tdb/*pool* (a-list "synthetic-2026-10-v2") {:source "test"}))))
    (is (= [shipped] (accepted))))
  (testing "--replacing must name the accepted version"
    (is (= "replacing-is-not-the-accepted-list"
           (refusal #(tool/load-list! tdb/*pool* (a-list "synthetic-2026-10-v2")
                                      {:source "test" :replacing "synthetic-2026-09-v1"}))))
    (is (= [shipped] (accepted))))
  (testing "with it, the old is retired and the new accepted"
    (is (= {:version "synthetic-2026-10-v2" :entry-count 1 :retired shipped}
           (tool/load-list! tdb/*pool* (a-list "synthetic-2026-10-v2")
                            {:source "test" :replacing shipped})))
    (is (= ["synthetic-2026-10-v2"] (accepted)))
    (is (= "synthetic-2026-10-v2" (:version (screening/accepted-list tdb/*pool*)))))
  (testing "a version already loaded is refused — a changed list is a new version"
    (is (= "version-already-loaded"
           (refusal #(tool/load-list! tdb/*pool* (a-list shipped)
                                      {:source "test" :replacing "synthetic-2026-10-v2"})))))
  (testing "retiring leaves none accepted; retiring again, or an unknown version, is refused"
    (is (= "synthetic-2026-10-v2" (tool/retire! tdb/*pool* "synthetic-2026-10-v2")))
    (is (= [] (accepted)))
    (is (nil? (screening/accepted-list tdb/*pool*)))
    (is (= "already-retired" (refusal #(tool/retire! tdb/*pool* "synthetic-2026-10-v2"))))
    (is (= "unknown-version" (refusal #(tool/retire! tdb/*pool* "no-such-version")))))
  (testing "and with none accepted, a load needs no --replacing"
    (is (= "synthetic-2026-10-v3"
           (:version (tool/load-list! tdb/*pool* (a-list "synthetic-2026-10-v3") {:source "test"}))))
    (is (= ["synthetic-2026-10-v3"] (accepted)))))

(deftest ac-17-8-a-replacement-is-one-transaction
  (testing "break the tool after the retirement, in a test double: neither the
            retirement nor any of the new version survives"
    (let [before (list-rows)]
      (with-redefs [tool/insert-version! (fn [& _] (throw (ex-info "injected after the retirement" {})))]
        (is (= "injected after the retirement"
               (refusal #(tool/load-list! tdb/*pool* (a-list "synthetic-2026-10-v2")
                                          {:source "test" :replacing shipped})))))
      (is (= before (list-rows)))
      (is (= [shipped] (accepted)) "the old list is still the accepted one")
      (is (zero? (:count (db/query-one tdb/*pool* ["select count(*) as count from screening_entry
                                                    where list_version = 'synthetic-2026-10-v2'"])))))))

(deftest two-accepted-lists-are-a-defect-screening-refuses-to-choose-between
  (testing "the schema does not forbid two accepted rows — the tool's lock does —
            so the reader refuses to pick one rather than screening against either"
    (db/execute! tdb/*pool* ["insert into screening_list (version, source, entry_count)
                              values ('raw-second', 'raw sql', 0)"])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"More than one screening list is accepted"
                          (screening/accepted-list tdb/*pool*)))))

(deftest a-path-that-is-not-a-list-is-refused-naming-the-path
  (testing "found running UAT-008: an exported shell `LIST` holding a version was
            taken by make for the file path, and the loader answered with a stack
            trace. It now refuses, naming what it was given"
    (doseq [path ["synthetic-2026-10-v1" "deps.edn-that-does-not-exist"]]
      (let [t (try (tool/read-list-file path) nil (catch clojure.lang.ExceptionInfo t t))]
        (is (= "unreadable-list-file" (:reason (ex-data t))) path)
        (is (= path (:path (ex-data t))))))
    (is (= "unreadable-list-file"
           (:reason (ex-data (try (tool/read-list-file "Makefile") nil
                                  (catch clojure.lang.ExceptionInfo t t)))))
        "a file that is not EDN")))
