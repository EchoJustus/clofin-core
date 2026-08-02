(ns clofin.organisations.repository-test
  "Organisation persistence against real PostgreSQL."
  (:require [clofin.error :as err]
            [clofin.organisations.repository :as organisations]
            [clofin.test-db :as tdb]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(defn- create!
  [short-name & {:keys [legal-name] :or {legal-name "Meridian Freight Holdings Pte Ltd"}}]
  (organisations/create-organisation! tdb/*pool*
                                      {:id (random-uuid)
                                       :legal-name legal-name
                                       :short-name short-name}))

(defn- failure [f]
  (try
    (f)
    (is false "expected a domain error, but the call succeeded")
    nil
    (catch clojure.lang.ExceptionInfo e
      (is (err/domain-error? e))
      (ex-data e))))

(deftest an-organisation-round-trips
  (let [org (create! "meridian")]
    (is (= org (organisations/find-organisation tdb/*pool* (:id org))))
    (is (= :active (:status org)) "organisations are created active")))

(deftest a-missing-organisation-is-nil-rather-than-an-error
  (is (nil? (organisations/find-organisation tdb/*pool* (random-uuid)))))

(deftest a-duplicate-short-name-is-a-conflict
  (create! "meridian")
  (is (= :conflict (:clofin/error (failure #(create! "meridian"))))
      "a collision is something the caller can act on, not a 500"))

(deftest capitalisation-cannot-be-used-to-create-a-second-tenant
  (testing "two tenants differing only by capitalisation would be indistinguishable
            in every export they appear in"
    (create! "meridian")
    (testing "the value type refuses an uppercase short name outright"
      (is (= :validation (:clofin/error (failure #(create! "MERIDIAN"))))))

    (testing "and the schema's unique index on lower(short_name) refuses it again,
              for anything that bypasses the domain — a migration, a fix-up script"
      (is (thrown-with-msg?
           Exception #"organisation_short_name_key"
           (tdb/insert-organisation! tdb/*pool* {:id (random-uuid) :short-name "MERIDIAN"}))))))

(deftest an-invalid-organisation-never-reaches-the-database
  (testing "the value type refuses it first, so the schema constraint is a second line"
    (is (= :validation (:clofin/error (failure #(create! "Not A Short Name")))))
    (is (= :validation (:clofin/error (failure #(create! "meridian" :legal-name "  ")))))
    (is (nil? (organisations/find-organisation tdb/*pool* (random-uuid)))
        "and nothing was written on the way to being refused")))
