(ns clofin.screening.subject-test
  "The screening subject digest (TASK-017 A-3, AC-17-4's foundation): one fixed
  instruction, one fixed hex — and what moves it and what does not."
  (:require [clofin.money :as money]
            [clofin.screening.subject :as subject]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant LocalDate]))

(def ^:private fixed
  "A synthetic instruction, fixed field by field."
  {:id                #uuid "00000000-0000-4000-8000-000000000017"
   :organisation-id   #uuid "00000000-0000-4000-8000-0000000000a1"
   :debtor-account-id #uuid "00000000-0000-4000-8000-0000000000b2"
   :creditor-name     "Blocked Counterparty Ltd"
   :creditor-account  "SG-SYNTH-88012345"
   :creditor-country  "SG"
   :amount            (money/of "SGD" 125000)
   :value-date        (LocalDate/parse "2026-10-12")
   :purpose-code      "SUPP"
   :status            :draft
   :created-by        #uuid "00000000-0000-4000-8000-0000000000c3"
   :created-at        (Instant/parse "2026-10-05T09:00:00Z")})

(def ^:private golden
  "Computed once from `fixed` with the projection `screening-subject/1`, and
  pinned. A change to the projection or the canonical form moves it — which is
  the point: a decision bound to one is not a decision about the other.

  Recomputed outside Clojure when it was pinned (TASK-017), as SHA-256 over

      {\"amount\":{\"currency\":\"SGD\",\"minor-units\":125000},\"creditor-account\":\"SG-SYNTH-88012345\",
       \"creditor-country\":\"SG\",\"creditor-name\":\"Blocked Counterparty Ltd\",
       \"debtor-account-id\":\"…-0000000000b2\",\"id\":\"…-000000000017\",
       \"organisation-id\":\"…-0000000000a1\",\"projection\":\"screening-subject/1\",
       \"purpose-code\":\"SUPP\",\"value-date\":\"2026-10-12\"}

  (keys sorted, no whitespace) with Python's `hashlib` — two methods agreeing,
  rather than one implementation agreeing with itself (L-16)."
  "b557727148059e3eb9919d9673c6542c9f036b3eff270089e427431ac35b3e94")

(deftest ac-17-4-the-golden-digest
  (is (re-matches #"[0-9a-f]{64}" (subject/digest fixed)))
  (is (= golden (subject/digest fixed))))

(deftest a-submission-does-not-move-the-digest-and-an-amendment-does
  (testing "status, provenance and timestamps are outside the projection"
    (doseq [[k v] [[:status :pending-approval] [:status :settled]
                   [:created-by (random-uuid)] [:created-at (Instant/parse "2027-01-01T00:00:00Z")]
                   [:retried-by-ids [(random-uuid)]] [:client-reference "agent-ref-1"]]]
      (is (= (subject/digest fixed) (subject/digest (assoc fixed k v))) (str k))))
  (testing "every screened field and identity moves it"
    (doseq [[k v] [[:creditor-name "Pacific Rim Logistics Pte Ltd"]
                   [:creditor-account "SG-SYNTH-88012340"]
                   [:creditor-country "ZZ"]
                   [:amount (money/of "SGD" 125001)]
                   [:value-date (LocalDate/parse "2026-10-13")]
                   [:purpose-code "TRAD"]
                   [:debtor-account-id (random-uuid)]
                   [:id (random-uuid)]
                   [:organisation-id (random-uuid)]]]
      (is (not= (subject/digest fixed) (subject/digest (assoc fixed k v))) (str k)))))

(deftest an-absent-country-and-a-nil-one-are-one-fact
  (is (= (subject/digest (dissoc fixed :creditor-country))
         (subject/digest (assoc fixed :creditor-country nil)))))

(deftest the-projection-names-its-version
  (is (= "screening-subject/1" (:projection (subject/projection fixed))))
  (is (not= (subject/digest fixed)
            (with-redefs [subject/projection-version "screening-subject/2"]
              (subject/digest fixed)))
      "a changed projection changes every digest"))
