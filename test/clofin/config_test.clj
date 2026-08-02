(ns clofin.config-test
  (:require [clofin.config :as config]
            [clojure.test :refer [deftest is testing]]))

(deftest defaults-let-a-fresh-clone-run
  (testing "every setting has a local-development default, so there is no configuration step"
    (let [c (config/load-config)]
      (is (contains? config/environments (:environment c)))
      (is (string? (get-in c [:http :host])))
      (is (integer? (get-in c [:http :port])))
      (is (string? (get-in c [:db :url])))
      (is (integer? (get-in c [:db :pool-size]))))))

(deftest error-detail-is-a-development-only-affordance
  (is (config/expose-error-detail? {:environment :dev}))
  (is (not (config/expose-error-detail? {:environment :test})))
  (is (not (config/expose-error-detail? {:environment :prod}))))

(deftest credentials-never-reach-a-log-line
  (let [c {:environment :prod :db {:url "jdbc:postgresql://db/clofin" :user "clofin" :password "s3cret"}}
        safe (config/redacted c)]
    (is (= "<redacted>" (get-in safe [:db :password])))
    (is (not (re-find #"s3cret" (pr-str safe))))
    (testing "everything else is still there, or redaction would defeat its purpose"
      (is (= "jdbc:postgresql://db/clofin" (get-in safe [:db :url])))
      (is (= "clofin" (get-in safe [:db :user]))))))
