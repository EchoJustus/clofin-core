(ns clofin.api.audit-coverage-test
  "Every API write leaves exactly one audit event — end to end, without a socket.

  These call the fully-wrapped handler — router, middleware, error translation,
  JSON codec — with a request map and assert on the database. That is the whole
  stack a caller meets, minus Jetty (ADR-0010), and it is the level at which
  TASK-005's claim is actually made: not \"the service records an event\" but
  \"a request that changed something left a record of who changed it\".

  The transaction halves of the I9 pairs — a rolled-back change leaving no
  event, including when the rollback is the database refusing — live in
  `clofin.ledger.service-test` and `clofin.organisations.service-test`, because
  a handler owns its transaction and a test calling one cannot fail it midway.
  What is asserted here is the other half, plus the payoff: the new events reach
  `GET /audit/events` and the evidence pack with **no query changes** (AC-5).

  Acceptance criteria from docs/briefs/005-TASK-audit-coverage-completion.md are
  named in the tests that cover them."
  (:require [clofin.audit.repository :as audit-store]
            [clofin.db.core :as db]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time LocalDate ZoneOffset]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private today (LocalDate/now ZoneOffset/UTC))

;; ---------------------------------------------------------------------------
;; Calling the API
;; ---------------------------------------------------------------------------

(defn- handler [] (system/handler {:config {:environment :test} :pool tdb/*pool*}))

(defn- call
  "Issue a request through the whole stack and decode the response body.

  `:actor` names the actor to authenticate as; omitting it sends no actor
  header, which is how the unauthenticated bootstrap is exercised."
  [method uri & {:keys [body query actor idempotency-key correlation-id]}]
  (let [response ((handler)
                  (cond-> {:request-method method :uri uri :headers {}}
                    query (assoc :query-string query)
                    actor (assoc-in [:headers "x-actor-id"] (str actor))
                    correlation-id (assoc-in [:headers "x-correlation-id"] correlation-id)
                    idempotency-key (assoc-in [:headers "idempotency-key"] idempotency-key)
                    body (-> (assoc-in [:headers "content-type"] "application/json")
                             (assoc :body (ByteArrayInputStream.
                                           (.getBytes (json/write-str body)
                                                      StandardCharsets/UTF_8))))))]
    (assoc response :json (when-not (str/blank? (:body response))
                            (json/read-str (:body response))))))

(defn- created!
  [uri body & {:as opts}]
  (let [{:keys [status json] :as response} (call :post uri (assoc opts :body body))]
    (is (= 201 status) (str "expected 201 from " uri ", body was " (:body response)))
    json))

(defn- uuid [document] (java.util.UUID/fromString (get document "id")))

;; ---------------------------------------------------------------------------
;; Fixtures — built through the API wherever the API can build them
;; ---------------------------------------------------------------------------

(defn- seed-actor!
  "Seed an actor with exactly the roles named — no more.

  There is no `insert-superuser!` to reach for, deliberately (C-08). Actors are
  seeded rather than created through the API because no endpoint creates one:
  an actor that could grant itself a role would make C-01 unenforceable."
  [org roles]
  (tdb/insert-actor! tdb/*pool* {:organisation-id (uuid org)
                                 :display-name (str/join "+" (map name roles))
                                 :roles roles}))

(defn- new-organisation!
  ([] (new-organisation! "meridian"))
  ([short-name & {:as opts}]
   (created! "/organisations" {"legalName" "Meridian Freight Holdings Pte Ltd"
                               "shortName" short-name}
             opts)))

(defn- new-account!
  [org actor code type & {:as opts}]
  (created! "/accounts" {"organisationId" (get org "id")
                         "code" code
                         "name" (str "Account " code)
                         "type" type
                         "currency" "SGD"}
            (assoc opts :actor actor)))

(defn- new-entry!
  [org actor {:keys [from to]} & {:as opts}]
  (created! "/journal-entries"
            {"organisationId" (get org "id")
             "occurredAt" "2026-08-04T09:00:00Z"
             "narrative" "Client funds received"
             "reference" {"type" "opening-balance" "id" (str (random-uuid))}
             "lines" [{"accountId" (get to "id") "direction" "debit"
                       "amount" {"currency" "SGD" "minorUnits" 125000}}
                      {"accountId" (get from "id") "direction" "credit"
                       "amount" {"currency" "SGD" "minorUnits" 125000}}]}
            (assoc opts :actor actor)))

(defn- events-for
  "Every audit event about one subject, oldest first, read straight from storage."
  [org subject-id]
  (audit-store/events-for-subject tdb/*pool* (uuid org) subject-id))

(defn- audit-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from audit_event"])))

(defn- actions-in-order
  "The organisation's audit actions, oldest first, as `GET /audit/events` renders
  them.

  The endpoint answers most-recent-first; reversing here reads as history. The
  order is total rather than incidental: each call below is its own transaction,
  and `occurred_at` is the transaction's start time, so no two of these share
  one."
  [org auditor]
  (let [{:keys [status json]} (call :get "/audit/events"
                                    :actor auditor
                                    :query (str "organisationId=" (get org "id")))]
    (is (= 200 status))
    (into [] (map #(get % "action")) (reverse (get json "auditEvents")))))

;; ---------------------------------------------------------------------------
;; AC-1 — organisation creation, and the bootstrap identity
;; ---------------------------------------------------------------------------

(deftest ac-1-creating-an-organisation-leaves-exactly-one-event
  (let [org (new-organisation! "meridian" :correlation-id "corr-bootstrap")
        events (events-for org (uuid org))]
    (is (= 1 (count events)) "exactly one, not zero and not two")
    (is (= 1 (audit-count)) "and it is the first thing in the trail")
    (let [ev (first events)]
      (is (= "organisation.created" (:action ev)))
      (is (= "organisation" (:subject-type ev)))
      (is (= (uuid org) (:subject-id ev)))
      (is (= "corr-bootstrap" (:correlation-id ev))
          "so the event joins to the log line and the response the caller received")
      (is (some? (:after-digest ev)))
      (is (nil? (:before-digest ev))))))

(deftest ac-1-the-bootstrap-event-carries-no-actor-and-invents-none
  (testing "`POST /organisations` is unauthenticated by design — no actor can exist before the
            organisation that holds one — so its event records null rather than a manufactured
            identity (ADR-0017)"
    (let [org (new-organisation!)
          ev (first (events-for org (uuid org)))]
      (is (nil? (:actor-id ev)))
      (is (zero? (:count (db/query-one tdb/*pool* ["select count(*) as count from actor"])))
          "no `system` actor row was seeded to fill the column — an actor row is a thing that
           can hold roles and limits, and one nobody administers reads as attribution")
      (is (zero? (:count (db/query-one tdb/*pool*
                                       ["select count(*) as count from audit_event
                                          where actor_id is null and action <> 'organisation.created'"])))
          "and the null is the bootstrap's alone"))))

(deftest a-refused-organisation-registration-leaves-no-event
  (let [_ (new-organisation! "meridian")
        before (audit-count)]
    (testing "a duplicate short name is a 409 and records nothing"
      (is (= 409 (:status (call :post "/organisations"
                                :body {"legalName" "Someone Else Pte Ltd"
                                       "shortName" "meridian"}))))
      (is (= before (audit-count))))
    (testing "a malformed body is a 400 and records nothing"
      (is (= 400 (:status (call :post "/organisations" :body {"legalName" "  "}))))
      (is (= before (audit-count))))))

;; ---------------------------------------------------------------------------
;; AC-2 — opening an account
;; ---------------------------------------------------------------------------

(deftest ac-2-opening-an-account-leaves-exactly-one-event
  (let [org (new-organisation!)
        controller (seed-actor! org [:controller])
        acct (new-account! org controller "1100-CLIENT-FUNDS" "asset"
                           :correlation-id "corr-account")
        events (events-for org (uuid acct))]
    (is (= 1 (count events)) "exactly one, not zero and not two")
    (let [ev (first events)]
      (is (= "account.created" (:action ev)))
      (is (= "account" (:subject-type ev)))
      (is (= (uuid acct) (:subject-id ev)))
      (is (= controller (:actor-id ev))
          "the acting principal, taken from the authenticated actor rather than the request")
      (is (= "corr-account" (:correlation-id ev)))
      (is (nil? (:before-digest ev)))
      (is (some? (:after-digest ev))))))

(deftest a-refused-account-opening-leaves-no-event
  (let [org (new-organisation!)
        controller (seed-actor! org [:controller])
        operator (seed-actor! org [:operator])
        _ (new-account! org controller "1100-CLIENT-FUNDS" "asset")
        before (audit-count)]
    (testing "a duplicate code is a 409 and records nothing"
      (is (= 409 (:status (call :post "/accounts"
                                :actor controller
                                :body {"organisationId" (get org "id")
                                       "code" "1100-CLIENT-FUNDS" "name" "Again"
                                       "type" "asset" "currency" "SGD"}))))
      (is (= before (audit-count))))
    (testing "an actor without `:account/create` is a 403 and records nothing (C-08)"
      (is (= 403 (:status (call :post "/accounts"
                                :actor operator
                                :body {"organisationId" (get org "id")
                                       "code" "1200-OPERATING" "name" "Operating"
                                       "type" "asset" "currency" "SGD"}))))
      (is (= before (audit-count))))
    (testing "an unauthenticated caller is a 401 and records nothing"
      (is (= 401 (:status (call :post "/accounts"
                                :body {"organisationId" (get org "id")
                                       "code" "1300-OTHER" "name" "Other"
                                       "type" "asset" "currency" "SGD"}))))
      (is (= before (audit-count))))))

;; ---------------------------------------------------------------------------
;; AC-3 — posting a journal entry
;; ---------------------------------------------------------------------------

(deftest ac-3-posting-a-journal-entry-leaves-exactly-one-event
  (let [org (new-organisation!)
        controller (seed-actor! org [:controller])
        cash (new-account! org controller "1100-CLIENT-FUNDS" "asset")
        payable (new-account! org controller "2100-CLIENT-PAYABLE" "liability")
        entry (new-entry! org controller {:from payable :to cash} :correlation-id "corr-entry")
        events (events-for org (uuid entry))]
    (is (= 1 (count events)) "one per entry, not one per line")
    (let [ev (first events)]
      (is (= "journal-entry.posted" (:action ev)))
      (is (= "journal-entry" (:subject-type ev)))
      (is (= (uuid entry) (:subject-id ev)))
      (is (= controller (:actor-id ev)))
      (is (= "corr-entry" (:correlation-id ev)))
      (is (nil? (:before-digest ev))
          "a posted entry is never amended (C-03) — a correction is a reversing entry
           with its own id and its own event")
      (is (some? (:after-digest ev))))))

(deftest a-refused-posting-leaves-no-event
  (let [org (new-organisation!)
        controller (seed-actor! org [:controller])
        cash (new-account! org controller "1100-CLIENT-FUNDS" "asset")
        payable (new-account! org controller "2100-CLIENT-PAYABLE" "liability")
        before (audit-count)
        unbalanced {"organisationId" (get org "id")
                    "occurredAt" "2026-08-04T09:00:00Z"
                    "narrative" "Does not balance"
                    "reference" {"type" "opening-balance" "id" (str (random-uuid))}
                    "lines" [{"accountId" (get cash "id") "direction" "debit"
                              "amount" {"currency" "SGD" "minorUnits" 125000}}
                             {"accountId" (get payable "id") "direction" "credit"
                              "amount" {"currency" "SGD" "minorUnits" 124999}}]}]
    (testing "an entry that does not balance is a 422 and records nothing"
      (is (= 422 (:status (call :post "/journal-entries" :actor controller :body unbalanced))))
      (is (= before (audit-count))))
    (testing "an entry referencing an unknown account is a 422 and records nothing"
      (is (= 422 (:status (call :post "/journal-entries" :actor controller
                                :body (assoc-in unbalanced ["lines" 0 "accountId"]
                                                (str (random-uuid)))))))
      (is (= before (audit-count))))))

;; ---------------------------------------------------------------------------
;; AC-4 — digests, not payloads
;; ---------------------------------------------------------------------------

(deftest ac-4-no-payload-field-reaches-the-audit-table
  (testing "ADR-0016, C-09: an append-only table holding a counterparty name is a second copy
            of the data C-09 minimises, and one that can never be cleaned"
    (let [org (new-organisation!)
          controller (seed-actor! org [:controller])
          cash (new-account! org controller "1100-CLIENT-FUNDS" "asset")
          payable (new-account! org controller "2100-CLIENT-PAYABLE" "liability")
          _ (new-entry! org controller {:from payable :to cash})
          rendered (str/lower-case
                    (pr-str (db/query tdb/*pool*
                                      ["select id::text, organisation_id::text, actor_id::text,
                                               action, subject_type, subject_id::text,
                                               before_digest, after_digest, correlation_id
                                          from audit_event"])))]
      (doseq [payload ["meridian" "freight holdings" "client funds received"
                       "1100-client-funds" "125000"]]
        (is (not (str/includes? rendered payload))
            (str (pr-str payload) " must not appear in audit_event")))
      (testing "every digest that is present is version-tagged"
        (doseq [row (db/query tdb/*pool* ["select after_digest from audit_event"])]
          (is (re-matches #"v1:[0-9a-f]{64}" (:after-digest row))))))))

;; ---------------------------------------------------------------------------
;; AC-5 — the payoff: the same queries, now complete
;; ---------------------------------------------------------------------------

(deftest ac-5-the-trail-mixes-ledger-and-payment-events-with-no-query-change
  (testing "PR-074, C-05: `GET /audit/events` and the evidence pack surface the new events
            beside the payment ones, in order, without a single change to either query"
    (let [org (new-organisation!)
          controller (seed-actor! org [:controller])
          maker (seed-actor! org [:operator])
          auditor (seed-actor! org [:auditor])
          cash (new-account! org controller "1100-CLIENT-FUNDS" "asset")
          payable (new-account! org controller "2100-CLIENT-PAYABLE" "liability")
          entry (new-entry! org controller {:from payable :to cash})
          instruction (created! "/payment-instructions"
                                {"organisationId"  (get org "id")
                                 "debtorAccountId" (get cash "id")
                                 "creditorName"    "Pacific Rim Logistics Pte Ltd"
                                 "creditorAccount" "SG-SYNTH-88012345"
                                 "amount"          {"currency" "SGD" "minorUnits" 125000}
                                 "valueDate"       (str (.plusDays today 7))
                                 "purposeCode"     "SUPP"}
                                :actor maker :idempotency-key (str (random-uuid)))]

      (testing "every write is in the trail, in the order it happened"
        (is (= ["organisation.created"
                "account.created"
                "account.created"
                "journal-entry.posted"
                "payment.created"]
               (actions-in-order org auditor))))

      (testing "and each one can still be narrowed by action, with no new parameter"
        (let [{:keys [status json]} (call :get "/audit/events"
                                          :actor auditor
                                          :query (str "organisationId=" (get org "id")
                                                      "&action=journal-entry.posted"))]
          (is (= 200 status))
          (is (= 1 (get json "count")))
          (is (= (get entry "id") (get-in json ["auditEvents" 0 "subjectId"])))))

      (testing "an evidence pack for an account is a complete pack, not a 404"
        (let [{:keys [status json]} (call :get (str "/audit/evidence/" (get cash "id"))
                                          :actor auditor
                                          :query (str "organisationId=" (get org "id")))]
          (is (= 200 status))
          (is (= "account" (get json "subjectType")))
          (is (= ["account.created"] (mapv #(get % "action") (get json "events"))))))

      (testing "and so is one for a journal entry"
        (let [{:keys [status json]} (call :get (str "/audit/evidence/" (get entry "id"))
                                          :actor auditor
                                          :query (str "organisationId=" (get org "id")))]
          (is (= 200 status))
          (is (= "journal-entry" (get json "subjectType")))
          (is (= ["journal-entry.posted"] (mapv #(get % "action") (get json "events"))))))

      (testing "and one for the organisation itself, which is where the trail begins"
        (let [{:keys [status json]} (call :get (str "/audit/evidence/" (get org "id"))
                                          :actor auditor
                                          :query (str "organisationId=" (get org "id")))]
          (is (= 200 status))
          (is (= "organisation" (get json "subjectType")))
          (is (nil? (get-in json ["events" 0 "actorId"]))
              "the bootstrap event renders without an actor rather than with a placeholder")))

      (testing "the payment's own pack is unchanged by any of this"
        (let [{:keys [status json]} (call :get (str "/audit/evidence/" (get instruction "id"))
                                          :actor auditor
                                          :query (str "organisationId=" (get org "id")))]
          (is (= 200 status))
          (is (= "payment-instruction" (get json "subjectType")))
          (is (= ["payment.created"] (mapv #(get % "action") (get json "events")))))))))

(deftest ac-5-the-trail-stays-scoped-to-its-own-organisation
  (testing "a second tenant's creation events are not visible from the first — the new events
            are scoped by the same `organisation_id` filter as every existing one"
    (let [org (new-organisation! "meridian")
          controller (seed-actor! org [:controller])
          auditor (seed-actor! org [:auditor])
          _ (new-account! org controller "1100-CLIENT-FUNDS" "asset")
          other (new-organisation! "harbourline")
          other-controller (seed-actor! other [:controller])
          _ (new-account! other other-controller "1100-CLIENT-FUNDS" "asset")]
      (is (= ["organisation.created" "account.created"] (actions-in-order org auditor)))
      (is (= 4 (audit-count)) "both tenants' events exist; only one tenant's are visible"))))
