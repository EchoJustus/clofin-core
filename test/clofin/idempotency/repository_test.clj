(ns clofin.idempotency.repository-test
  "The key store against a real PostgreSQL: what a key row records, and what a
  read of one can and cannot prove.

  `clofin.idempotency-test` is the pure half — canonicalisation, the digest, the
  replay decision. This is the half that writes, which is where TASK-018 made
  the operation that bound a key part of the row (ADR-0028 D6, migration
  `0014`)."
  (:require [clofin.db.core :as db]
            [clofin.idempotency.repository :as idem-store]
            [clofin.test-db :as tdb]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(defn- organisation! []
  (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)
                                        :short-name (str "meridian-" (rand-int 100000000))}))

(defn- key-rows []
  (db/query tdb/*pool* ["select key, operation_id from idempotency_key order by key"]))

(defn- caught [f] (try (f) nil (catch Exception t t)))

(deftest ac-18-9-a-key-is-never-claimed-without-its-operation
  (testing "the negative control: a caller passing a nil or blank operation-id is
            refused before anything is read or written, and its effect never runs"
    (let [org (organisation!)
          ran (atom 0)]
      (doseq [operation-id [nil "" "   " :createPaymentInstruction]]
        (let [t (caught #(idem-store/execute-once!
                          tdb/*pool*
                          {:organisation-id org :key (str (random-uuid)) :digest "d"
                           :operation-id operation-id}
                          (fn [_] (swap! ran inc) {:status 201 :body {"id" "x"}})))]
          (is (= :validation (:clofin/error (ex-data t))) (pr-str operation-id))))
      (is (= 0 @ran) "no effect ran")
      (is (empty? (key-rows)) "and no key was claimed"))))

(deftest ac-18-9-the-claiming-insert-writes-the-operation
  (let [org (organisation!)
        outcome (idem-store/execute-once!
                 tdb/*pool*
                 {:organisation-id org :key "k-1" :digest "d"
                  :operation-id "createPaymentInstruction"}
                 (fn [_] {:status 201 :body {"id" "00000000-0000-4000-8000-000000000018"}}))]
    (is (= 201 (:status outcome)))
    (is (= [{:key "k-1" :operation-id "createPaymentInstruction"}] (key-rows)))
    (testing "and the binding reads back with what a replay serves, byte for byte"
      (let [found (idem-store/find-binding tdb/*pool* org "k-1")]
        (is (= "createPaymentInstruction" (:operation-id found)))
        (is (= 201 (:status found)))
        (is (= (:body outcome) (:body found)))
        (is (= (:body outcome) (:body (idem-store/find-response tdb/*pool* org "k-1"))))
        (is (inst? (:bound-at found)))))))

(deftest ac-18-2-a-read-of-an-absent-key-is-nil-and-a-legacy-row-says-it-has-no-operation
  (let [org (organisation!)]
    (is (nil? (idem-store/find-binding tdb/*pool* org "never-bound")))
    (db/execute! tdb/*pool* ["insert into idempotency_key
                                (organisation_id, key, request_digest, response_status, response_body)
                              values (?, ?, ?, ?, ?)"
                             org "pre-0014" "d" 200 "{}"])
    (let [found (idem-store/find-binding tdb/*pool* org "pre-0014")]
      (is (some? found) "a row with no operation is a binding, not an absence")
      (is (nil? (:operation-id found))))
    (testing "scoped by organisation: another organisation's key is not this one's"
      (is (nil? (idem-store/find-binding tdb/*pool* (organisation!) "pre-0014"))))))
