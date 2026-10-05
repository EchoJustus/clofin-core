(ns clofin.screening.repository-test
  "Migration `0015` against the live schema, application bypassed (TASK-017
  AC-17-0, and the repository half of AC-17-9).

  The brief's SQL pre-flight inserted one row of every documented shape and saw
  twelve refusals on a scratch database. Each is re-run here, on the migrated
  test database, by raw SQL — so what is asserted is the schema, not CloFin's
  code agreeing with itself. Synthetic rows only."
  (:require [clofin.db.core :as db]
            [clofin.screening.repository :as screening]
            [clofin.test-db :as tdb]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time LocalDate ZoneOffset]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private version "synthetic-2026-10-v1")

(defn- caught [f] (try (f) nil (catch Exception t t)))

(defn- refused-by
  "The constraint that refused `f`, or the trigger's message, or nil when
  nothing did."
  [f]
  (when-let [t (caught f)]
    (or (:constraint (db/violation t)) (ex-message t))))

(defn- fixture
  []
  (let [org (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)
                                                  :short-name (str "meridian-" (rand-int 100000000))})
        account (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                 :code (str "1100-CLIENT-FUNDS-" (rand-int 1000000))})
        maker (tdb/insert-actor! tdb/*pool* {:organisation-id org :display-name "Maker"
                                             :roles [:operator]})
        instruction (random-uuid)]
    (db/execute! tdb/*pool*
                 ["insert into payment_instruction
                     (id, organisation_id, debtor_account_id, creditor_name, creditor_account,
                      amount_minor, currency, value_date, purpose_code, status, created_by)
                   values (?, ?, ?, 'Blocked Counterparty Ltd', 'SG-SYNTH-88012345', 125000,
                           'SGD', ?, 'SUPP', 'draft', ?)"
                  instruction org account (.plusDays (LocalDate/now ZoneOffset/UTC) 7) maker])
    {:org org :maker maker :instruction instruction}))

(defn- insert-result!
  [{:keys [org maker instruction]} & {:keys [id origin outcome core-outcome agrees digest
                                             disposition reason]
                                      :or {origin "core" outcome "clear" core-outcome "clear"
                                           agrees true digest (apply str (repeat 64 "a"))
                                           disposition "accepted"}}]
  (let [id (or id (random-uuid))]
    (db/execute! tdb/*pool*
                 ["insert into screening_result
                     (id, organisation_id, instruction_id, list_version, origin, outcome,
                      core_outcome, agrees, instruction_digest, disposition, disposition_reason,
                      recorded_by)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                  id org instruction version origin outcome core-outcome agrees digest
                  disposition reason maker])
    id))

(defn- insert-case!
  [{:keys [org instruction]} result-id & {:keys [id] :or {id (random-uuid)}}]
  (db/execute! tdb/*pool*
               ["insert into screening_case
                   (id, organisation_id, instruction_id, result_id, instruction_digest, list_version)
                 select ?, organisation_id, instruction_id, id, instruction_digest, list_version
                   from screening_result where id = ?"
                id result-id])
  id)

(defn- disposition!
  [case-id actor]
  (db/execute! tdb/*pool*
               ["update screening_case
                    set status = 'dispositioned', disposition = 'false-positive',
                        rationale = 'Synthetic name collision; counterparty verified',
                        dispositioned_by = ?, dispositioned_at = now()
                  where id = ?" actor case-id]))

(deftest ac-17-0-every-documented-shape-inserts
  (let [f (fixture)]
    (testing "the shipped list, loaded by the tool, is in the tables the brief's DDL made"
      (is (= {:version version :entry-count 4}
             (select-keys (screening/find-list tdb/*pool* version) [:version :entry-count])))
      (is (= 5 (:count (db/query-one tdb/*pool* ["select count(*) as count from screening_rule
                                                  where list_version = ?" version])))
          "four entries, five rules — SYN-0004 has two"))
    (testing "a screening-service role grant"
      (is (uuid? (tdb/insert-actor! tdb/*pool* {:organisation-id (:org f) :display-name "Screener"
                                                :roles [:screening-service]}))))
    (testing "core's clear; a client's hit core agrees with, with both match rows; a
              client's clear core refused"
      (let [clear (insert-result! f)
            hit (insert-result! f :origin "client" :outcome "hit" :core-outcome "hit"
                                :digest (apply str (repeat 64 "b")))
            refused (insert-result! f :origin "client" :outcome "clear" :core-outcome "hit"
                                    :agrees false :disposition "refused"
                                    :reason "screening-result-mismatch")]
        (is (every? uuid? [clear hit refused]))
        (doseq [side ["submitted" "core"]]
          (is (= 1 (db/execute! tdb/*pool* ["insert into screening_result_match
                                               (result_id, side, list_version, entry_id)
                                             values (?, ?, ?, 'SYN-0001')" hit side version]))))
        (testing "a case on the hit, opened then dispositioned with a rationale"
          (let [c (insert-case! f hit)]
            (is (= 1 (disposition! c (:maker f))))
            (is (= {:status "dispositioned" :disposition "false-positive"}
                   (db/query-one tdb/*pool* ["select status, disposition from screening_case
                                              where id = ?" c])))))))
    (testing "the list retired, once"
      (is (= 1 (db/execute! tdb/*pool* ["update screening_list set retired_at = now()
                                          where version = ?" version]))))))

(deftest ac-17-0-the-pre-flight-s-twelve-refusals-with-the-application-bypassed
  (let [f (fixture)
        hit (insert-result! f :origin "client" :outcome "hit" :core-outcome "hit")
        open-case (insert-case! f hit)]
    (is (= "screening_rule_field_known"
           (refused-by #(db/execute! tdb/*pool* ["insert into screening_rule values (?, 'SYN-0001', 1,
                                                    'originator-name', 'exact', 'x')" version])))
        "1. an originator-* rule field")
    (is (= "screening_case_open_key" (refused-by #(insert-case! f hit)))
        "2. a second open case on one instruction")
    (is (= "screening_case_disposition_complete"
           (refused-by #(db/execute! tdb/*pool*
                                     ["insert into screening_case
                                         (id, organisation_id, instruction_id, result_id,
                                          instruction_digest, list_version, status, disposition,
                                          dispositioned_by, dispositioned_at)
                                       select ?, organisation_id, instruction_id, id,
                                              instruction_digest, list_version, 'dispositioned',
                                              'confirmed-hit', recorded_by, now()
                                         from screening_result where id = ?"
                                      (random-uuid) hit])))
        "4. a disposition without a rationale")
    (disposition! open-case (:maker f))
    (is (str/includes? (str (refused-by #(db/execute! tdb/*pool* ["update screening_case
                                                                     set rationale = 'changed my mind'
                                                                   where id = ?" open-case])))
                       "is dispositioned and a disposition is final")
        "3. re-dispositioning")
    (is (str/includes? (str (refused-by #(db/execute! tdb/*pool* ["update screening_result set outcome = 'clear'
                                                                   where id = ?" hit])))
                       "Table screening_result is append-only")
        "5. rewriting a result")
    (is (= "screening_result_core_agrees_with_itself"
           (refused-by #(insert-result! f :origin "core" :outcome "clear" :core-outcome "hit"
                                        :agrees false)))
        "6. a core result that disagrees with itself")
    (db/execute! tdb/*pool* ["update screening_list set retired_at = now() where version = ?" version])
    (is (str/includes? (str (refused-by #(db/execute! tdb/*pool* ["update screening_list set retired_at = now()
                                                                   where version = ?" version])))
                       "a list version is immutable once loaded")
        "7. a second retirement")
    (is (str/includes? (str (refused-by #(db/execute! tdb/*pool* ["update screening_list set entry_count = 3
                                                                   where version = ?" version])))
                       "a list version is immutable once loaded")
        "8. an edit to a retired list's entry_count")
    (is (str/includes? (str (refused-by #(db/execute! tdb/*pool* ["delete from screening_entry
                                                                   where id = 'SYN-0001'"])))
                       "Table screening_entry is append-only")
        "9. deleting an entry")
    (is (str/includes? (str (refused-by #(db/execute! tdb/*pool* ["truncate screening_result cascade"])))
                       "Table screening_result is append-only")
        "10. truncating results")
    (is (= "role_known"
           (refused-by #(db/execute! tdb/*pool* ["insert into actor_role (actor_id, role) values (?, 'superuser')"
                                                 (:maker f)])))
        "11. a role the constraint does not know")
    (is (= "screening_result_refusal_needs_reason"
           (refused-by #(insert-result! f :origin "client" :outcome "clear" :core-outcome "hit"
                                        :agrees false :disposition "refused")))
        "12. a refused result with no reason")))

(deftest ac-17-9-the-raw-update-of-a-dispositioned-case-is-refused-by-the-trigger
  (testing "the second enforcement behind the service's 409: any writer, application
            bypassed, every column"
    (let [f (fixture)
          c (insert-case! f (insert-result! f :origin "client" :outcome "hit" :core-outcome "hit"))]
      (disposition! c (:maker f))
      (doseq [[column value] [["disposition" "'confirmed-hit'"] ["status" "'open'"]
                              ["rationale" "'other words'"] ["instruction_digest" "repeat('c', 64)"]]]
        (is (str/includes? (str (refused-by #(db/execute! tdb/*pool*
                                                          [(str "update screening_case set " column " = "
                                                                value " where id = ?") c])))
                           "a disposition is final")
            column))
      (is (str/includes? (str (refused-by #(db/execute! tdb/*pool* ["delete from screening_case where id = ?" c])))
                         "append-only")
          "and it is never deleted"))))
