(ns clofin.organisations.service-test
  "Organisation registration and the audit event bound to it (TASK-005, AC-1).

  This is the I9 pair for `organisation.created`, in the shape
  `clofin.authz.repository-test` established for payments: a committed
  registration leaves **exactly one** event, and a rolled-back one leaves
  **none**. Together they say that an unaudited organisation is not
  representable — not that the code currently remembers to write an event, but
  that it cannot write one without the organisation surviving too.

  PostgreSQL rather than a substitute, because a substitute has no transaction
  to roll back and would be asserting that CloFin's code agrees with itself.

  The endpoint this covers is the **bootstrap**: it is deliberately
  unauthenticated, so its event carries no actor. That null is the subject of
  AC-1's trap and of ADR-0017, and it is asserted here as a value rather than
  assumed from a column comment."
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.db.core :as db]
            [clofin.organisations.repository :as organisations]
            [clofin.organisations.service :as service]
            [clofin.test-db :as tdb]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time Instant]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private legal-name "Meridian Freight Holdings Pte Ltd")

(defn- candidate
  [& {:keys [short-name] :or {short-name "meridian"}}]
  {:id (random-uuid) :legal-name legal-name :short-name short-name :status :active})

(defn- audit-count
  ([] (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event"])))
  ([subject-id] (:count (db/query-one tdb/*pool*
                                      ["select count(*) as count from audit_event where subject_id = ?"
                                       subject-id]))))

(defn- organisation-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from organisation"])))

;; ---------------------------------------------------------------------------
;; AC-1 — the pair
;; ---------------------------------------------------------------------------

(deftest ac-1-a-committed-registration-leaves-exactly-one-event
  (let [org (db/with-transaction [tx tdb/*pool*]
              (service/create-organisation! tx {:organisation (candidate)
                                                :correlation-id "corr-ac-1"}))]
    (is (= 1 (audit-count (:id org))) "exactly one, not zero and not two")
    (is (= 1 (audit-count)) "and nothing else was recorded on the way")

    (let [ev (first (audit-store/events-for-subject tdb/*pool* (:id org) (:id org)))]
      (testing "carrying action, subject and correlation id (PR-072)"
        (is (= "organisation.created" (:action ev)))
        (is (= "organisation" (:subject-type ev)))
        (is (= (:id org) (:subject-id ev)))
        (is (= (:id org) (:organisation-id ev))
            "the event belongs to the organisation it created, so a tenant-scoped
             query shows that tenant coming into existence")
        (is (= "corr-ac-1" (:correlation-id ev)))
        (is (instance? Instant (:occurred-at ev))))

      (testing "with the bootstrap identity: no actor, and no manufactured one (AC-1, ADR-0017)"
        (is (nil? (:actor-id ev)))
        (is (zero? (:count (db/query-one tdb/*pool* ["select count(*) as count from actor"])))
            "no actor row was invented to fill the column"))

      (testing "and digests computed by the existing canonicalisation (AC-4)"
        (is (nil? (:before-digest ev))
            "a creation has no before — that null is what distinguishes it from an update")
        (is (= (audit/digest (audit/organisation-subject org)) (:after-digest ev)))
        (is (str/starts-with? (:after-digest ev) (str audit/canonicalisation-version ":")))))

    (testing "and the organisation committed with it"
      (is (= org (organisations/find-organisation tdb/*pool* (:id org)))))))

(deftest ac-1-a-rolled-back-registration-leaves-no-event
  (testing "C-05, PR-075: an unaudited state change is not representable, and neither is an unchanged audit event"
    (let [before (audit-count)
          id (atom nil)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (let [org (service/create-organisation! tx {:organisation (candidate)
                                                            :correlation-id "corr-rollback"})]
                  (reset! id (:id org)))
                ;; Whatever goes wrong after the audit write goes wrong — a
                ;; constraint, a crash, a deliberate abort. The transaction is
                ;; the unit, so the event goes with it.
                (throw (ex-info "deliberate rollback" {})))
              nil (catch Exception e e))]
      (is (some? t))
      (is (= before (audit-count)) "no audit event survives a rolled-back registration")
      (is (zero? (audit-count @id)))
      (is (nil? (organisations/find-organisation tdb/*pool* @id))
          "and neither does the organisation"))))

(deftest a-refused-registration-records-nothing
  (testing "the repository raises before the audit write is reached, so a 409 leaves no trace"
    (db/with-transaction [tx tdb/*pool*]
      (service/create-organisation! tx {:organisation (candidate) :correlation-id "corr-first"}))
    (let [before (audit-count)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (service/create-organisation! tx {:organisation (candidate :short-name "MERIDIAN")
                                                  :correlation-id "corr-refused"}))
              nil (catch Exception e e))]
      (is (some? t) "a short name the value type refuses")
      (is (= before (audit-count))))

    (let [before (audit-count)
          t (try
              (db/with-transaction [tx tdb/*pool*]
                (service/create-organisation! tx {:organisation (candidate)
                                                  :correlation-id "corr-duplicate"}))
              nil (catch Exception e e))]
      (is (some? t) "a duplicate short name")
      (is (= before (audit-count)))
      (is (= 1 (organisation-count))))))

;; ---------------------------------------------------------------------------
;; AC-4 — digests, not payloads
;; ---------------------------------------------------------------------------

(deftest ac-4-the-audit-row-carries-no-payload
  (testing "ADR-0016, C-09: the legal name is in the digest input and must not be in the table"
    (let [org (db/with-transaction [tx tdb/*pool*]
                (service/create-organisation! tx {:organisation (candidate)
                                                  :correlation-id "corr-payload"}))
          row (db/query-one tdb/*pool*
                            ["select id::text, organisation_id::text, actor_id::text, action,
                                     subject_type, subject_id::text, before_digest, after_digest,
                                     correlation_id
                                from audit_event where subject_id = ?"
                             (:id org)])
          rendered (str/lower-case (pr-str row))]
      (is (not (str/includes? rendered (str/lower-case legal-name))))
      (is (not (str/includes? rendered "meridian"))))))
