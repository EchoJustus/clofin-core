(ns clofin.organisations.organisation-test
  "The organisation value type. Pure, so these need nothing but a JVM."
  (:require [clofin.error :as err]
            [clofin.organisations.organisation :as org]
            [clojure.test :refer [deftest is testing]]))

(def ^:private valid
  {:id (random-uuid)
   :legal-name "Meridian Freight Holdings Pte Ltd"
   :short-name "meridian"
   :status :active})

(defn- rejected? [candidate]
  (try
    (org/organisation candidate)
    false
    (catch clojure.lang.ExceptionInfo e
      (err/domain-error? e))))

(deftest a-valid-organisation-is-normalised-to-its-known-keys
  (is (= valid (org/organisation valid)))
  (testing "anything else a caller sends is dropped rather than carried along"
    (is (= valid (org/organisation (assoc valid :balance 1000000 :status :active))))))

(deftest an-organisation-needs-an-identity-supplied-by-its-caller
  (testing "the domain layer never generates identifiers"
    (is (rejected? (dissoc valid :id)))
    (is (rejected? (assoc valid :id "not-a-uuid")))))

(deftest a-legal-name-is-required
  (doseq [name [nil "" "   " 42]]
    (is (rejected? (assoc valid :legal-name name)) (str "should reject " (pr-str name)))))

(deftest a-short-name-is-constrained-because-it-appears-in-filenames
  (testing "accepted"
    (doseq [short-name ["meridian" "kestrel-2" "a1" "0x"]]
      (is (= short-name (:short-name (org/organisation (assoc valid :short-name short-name)))))))
  (testing "rejected"
    (doseq [short-name [nil "" "M" "Meridian" "meridian holdings" "-leading" "with_underscore"
                        (apply str (repeat 65 "a"))]]
      (is (rejected? (assoc valid :short-name short-name))
          (str "should reject " (pr-str short-name))))))

(deftest a-status-must-be-one-the-domain-knows
  (doseq [status [:active :suspended :closed]]
    (is (= status (:status (org/organisation (assoc valid :status status))))))
  (doseq [status [nil :dormant "active"]]
    (is (rejected? (assoc valid :status status)) (str "should reject " (pr-str status)))))

(deftest a-closed-organisation-is-representable
  (testing "records referencing it are retained forever, so it is never deleted"
    (is (= :closed (:status (org/organisation (assoc valid :status :closed)))))))
