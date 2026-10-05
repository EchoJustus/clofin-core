(ns clofin.screening.decision-test
  "C-07's judgement (TASK-017 A-4), by enumerating its matrix: whose result,
  over which digest, against a retired list or not, with which outcome and
  which case."
  (:require [clofin.screening.decision :as decision]
            [clojure.test :refer [deftest is testing]]))

(def ^:private d "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90")
(def ^:private other-d (apply str (repeat 64 "b")))

(defn- result [& {:as overrides}]
  (merge {:origin :core :disposition :accepted :outcome :clear
          :instruction-digest d :list-version "synthetic-2026-10-v1" :list-retired? false}
         overrides))

(defn- a-case [& {:as overrides}]
  (merge {:status :open :disposition nil :instruction-digest d
          :list-version "synthetic-2026-10-v1"}
         overrides))

(deftest ac-17-11-the-decision-matrix
  (doseq [[label input expected]
          [["no result at all"                     {:digest d}                                              :refuse/no-decision]
           ["core's result for another digest"     {:digest d :latest-core-result (result :instruction-digest other-d)} :refuse/no-decision]
           ["a client's clear, accepted"           {:digest d :latest-core-result (result :origin :client)}  :refuse/no-decision]
           ["a client's result as stored strings"  {:digest d :latest-core-result (result :origin "client" :outcome "clear")} :refuse/no-decision]
           ["a refused result"                     {:digest d :latest-core-result (result :disposition :refused)} :refuse/no-decision]
           ["core clear, list retired"             {:digest d :latest-core-result (result :list-retired? true)} :refuse/list-retired]
           ["core hit, list retired"               {:digest d :latest-core-result (result :outcome :hit :list-retired? true)} :refuse/list-retired]
           ["core clear"                           {:digest d :latest-core-result (result)}                  :permit]
           ["core clear, as stored strings"        {:digest d :latest-core-result (result :origin "core" :disposition "accepted" :outcome "clear")} :permit]
           ["core hit, no case"                    {:digest d :latest-core-result (result :outcome :hit)}    :refuse/hit]
           ["core hit, open case"                  {:digest d :latest-core-result (result :outcome :hit) :case (a-case)} :refuse/hit]
           ["core hit, false-positive"             {:digest d :latest-core-result (result :outcome :hit)
                                                    :case (a-case :status :dispositioned :disposition :false-positive)} :permit]
           ["core hit, confirmed"                  {:digest d :latest-core-result (result :outcome :hit)
                                                    :case (a-case :status "dispositioned" :disposition "confirmed-hit")} :refuse/confirmed-hit]
           ["core hit, false-positive on other content" {:digest d :latest-core-result (result :outcome :hit)
                                                         :case (a-case :status :dispositioned :disposition :false-positive
                                                                       :instruction-digest other-d)} :refuse/hit]
           ["core hit, false-positive against another list" {:digest d :latest-core-result (result :outcome :hit)
                                                             :case (a-case :status :dispositioned :disposition :false-positive
                                                                           :list-version "synthetic-2026-09-v1")} :refuse/hit]]]
    (testing label
      (is (= expected (decision/decide input)))
      (is (= (= :permit expected) (decision/permits-submit? (decision/decide input))))))
  (testing "every answer the matrix gives is one `decisions` declares, and every
            declared answer is reached (non-vacuity both ways)"
    (is (= decision/decisions
           #{:permit :refuse/no-decision :refuse/hit :refuse/confirmed-hit :refuse/list-retired}))))

(deftest a-refused-decision-names-its-reason
  (is (nil? (decision/refusal-reason :permit)))
  (is (= "screening-required" (decision/refusal-reason :refuse/no-decision)))
  (is (= "screening-required" (decision/refusal-reason :refuse/list-retired)))
  (is (= "screening-hit" (decision/refusal-reason :refuse/hit)))
  (is (= "screening-hit" (decision/refusal-reason :refuse/confirmed-hit))))
