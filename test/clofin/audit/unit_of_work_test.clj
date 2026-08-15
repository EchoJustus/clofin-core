(ns clofin.audit.unit-of-work-test
  "The transaction precondition, for **every** audit-composing service.

  Audit finding **F-011**, standing lesson **L-13**. Each of these services
  composes an aggregate write with the audit event describing it, and each
  claims — in `COMPLIANCE.md` C-05, in `ARCHITECTURE.md` §4, in its own
  docstring — that the two commit together or not at all. That claim rests
  entirely on the caller having supplied a transaction, and until this file
  existed nothing checked that they had:

  - the argument was *named* `tx`;
  - the docstring *said* it was a transaction;
  - `clofin.ledger.purity-test` proved the service could not *open* one.

  None of those is a runtime check. The auditor called `create-account!` and
  `post-entry!` with the **pool** and a null actor, and in both cases the audit
  construction failed exactly as designed — after the aggregate row had already
  committed on its own connection. Account `1`, event `0`. Journal entry `1`,
  event `0`. That is the state C-05 calls unrepresentable, reached through the
  service API exactly as Clojure permits it to be used.

  ## Why this is a matrix rather than two tests

  Standing lesson **L-6**: a guarantee stated over an enumerable set is checked
  across **every** instance, not the one its author was looking at. The set here
  is *services that compose a change with its audit event*, and it is
  enumerated below beside the same set in
  `clofin.ledger.purity-test/service-namespaces`. A service added to one and not
  the other is the partial enforcement L-6 exists to catch — so
  `every-audit-composing-service-is-covered-here` asserts the two agree.

  ## What each case proves

  Two negative arrivals per entry point, because they fail differently:

  - **the pool** — no transaction at all; every statement commits on its own;
  - **an autocommit connection** — a connection, which is what makes it the
    convincing near-miss: it satisfies `instance? Connection` and every type
    check a reviewer would think to write, and still commits statement by
    statement.

  And, for the entry points that would otherwise have written something, that
  **nothing was written** — which is the half that distinguishes a precondition
  from an error message. `clofin.ledger.repository-test` keeps the F-004 half of
  this story, where the guard sits at the repository; this file is about the
  guard sitting at the service *entry*, before the first write, because
  `create-account!` reached its repository only after the row was already
  durable."
  (:require [clofin.audit.repository :as audit-store]
            [clofin.db.core :as db]
            [clofin.ledger.purity-test :as purity]
            [clofin.ledger.service :as ledger-service]
            [clofin.money :as money]
            [clofin.organisations.service :as organisations-service]
            [clofin.payments.approval-service :as approval-service]
            [clofin.recon.service :as recon-service]
            [clofin.recon.statement :as statement]
            [clofin.settlement.service :as settlement-service]
            [clofin.test-db :as tdb]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time Instant LocalDate]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private value-date (LocalDate/parse "2026-12-01"))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- setup
  "Everything the services below need to be called *plausibly*.

  The calls must be ones that would really have written something, or the test
  proves only that a guard fires on a call that was going to fail anyway."
  []
  (let [org (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)})
        actor (tdb/insert-actor! tdb/*pool* {:organisation-id org
                                             :display-name "Controller"
                                             :roles [:controller :approver]
                                             :limits {"SGD" 100000000}})
        cash (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                              :code "1100-CLIENT-FUNDS" :type "asset"})
        payable (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                 :code "2100-CLIENT-PAYABLE" :type "liability"})
        instruction (random-uuid)
        approved (random-uuid)
        batch (random-uuid)]
    (tdb/insert-threshold! tdb/*pool* {:organisation-id org :currency "SGD"
                                       :from-minor 0 :approvals-required 1})
    (db/execute! tdb/*pool*
                 ["insert into payment_instruction
                     (id, organisation_id, debtor_account_id, creditor_name, creditor_account,
                      amount_minor, currency, value_date, purpose_code, status, created_by)
                   values (?, ?, ?, 'Pacific Rim Logistics Pte Ltd', 'SG-SYNTH-88012340',
                           125000, 'SGD', ?, 'SUPP', 'pending-approval', ?)"
                  instruction org cash value-date actor])
    ;; An approved one too, so the positive case below reaches real work rather
    ;; than being refused on eligibility — which would prove only that the
    ;; precondition is not the *first* thing to fail.
    (db/execute! tdb/*pool*
                 ["insert into payment_instruction
                     (id, organisation_id, debtor_account_id, creditor_name, creditor_account,
                      amount_minor, currency, value_date, purpose_code, status, created_by)
                   values (?, ?, ?, 'Pacific Rim Logistics Pte Ltd', 'SG-SYNTH-88012341',
                           125000, 'SGD', ?, 'SUPP', 'approved', ?)"
                  approved org cash value-date actor])
    (db/execute! tdb/*pool*
                 ["insert into settlement_batch
                     (id, organisation_id, scheme, currency, value_date, created_by)
                   values (?, ?, 'SIM-RTGS', 'SGD', ?, ?)"
                  batch org value-date actor])
    {:org org :actor actor :cash cash :payable payable
     :instruction instruction :approved approved :batch batch}))

(defn- counts
  "Every table an audit-composing service can write, in one map.

  Compared before and after, so a case does not have to know which table its
  service would have touched — and so a service that writes somewhere nobody
  expected is caught too."
  []
  (into {}
        (map (fn [t] [t (:count (db/query-one
                                 tdb/*pool*
                                 [(str "select count(*) as count from " t)]))]))
        ["organisation" "ledger_account" "journal_entry" "journal_line"
         "payment_instruction" "approval" "settlement_batch" "settlement_batch_item"
         "scheme_response" "audit_event"
         "reconciliation_statement" "reconciliation_statement_line"
         "reconciliation_match" "reconciliation_break" "reconciliation_adjustment"]))

;; ---------------------------------------------------------------------------
;; The set
;; ---------------------------------------------------------------------------

(def ^:private audit-composing-calls
  "Every entry point that composes an aggregate write with its audit event.

  Keyed by the namespace it belongs to, so the coverage assertion below can
  compare this set against `clofin.ledger.purity-test/service-namespaces` —
  the other place the same set is written down."
  [{:ns 'clofin.organisations.service
    :label "create-organisation!"
    :call (fn [source _]
            (organisations-service/create-organisation!
             source {:organisation {:id (random-uuid)
                                   :legal-name "Meridian Freight Holdings Pte Ltd"
                                   :short-name (str "meridian-" (rand-int 100000000))
                                   :status :active}
                     :correlation-id "corr-f-011"}))}

   {:ns 'clofin.ledger.service
    :label "create-account!"
    ;; The auditor's own call: pool plus a null actor. The account committed and
    ;; the audit write then failed on the actor, leaving account 1 / event 0.
    :call (fn [source {:keys [org]}]
            (ledger-service/create-account!
             source {:account {:id (random-uuid) :organisation-id org
                               :code "1200-OPERATING" :name "Operating account"
                               :type :asset :currency "SGD" :status :active}
                     :actor-id nil
                     :correlation-id "corr-f-011"}))}

   {:ns 'clofin.ledger.service
    :label "post-entry!"
    :call (fn [source {:keys [org cash payable]}]
            (ledger-service/post-entry!
             source {:entry {:id (random-uuid)
                             :organisation-id org
                             :occurred-at (Instant/parse "2026-08-04T09:00:00Z")
                             :narrative "Client funds received"
                             :reference {:type :opening-balance :id (random-uuid)}
                             :lines [{:account-id cash :direction :debit
                                      :amount (money/of "SGD" 125000)}
                                     {:account-id payable :direction :credit
                                      :amount (money/of "SGD" 125000)}]}
                     :actor-id nil
                     :correlation-id "corr-f-011"}))}

   {:ns 'clofin.payments.approval-service
    :label "decide!"
    :call (fn [source {:keys [org actor instruction]}]
            (approval-service/decide! source {:organisation-id org
                                              :instruction-id instruction
                                              :actor {:id actor}
                                              :decision :approved
                                              :correlation-id "corr-f-011"}))}

   {:ns 'clofin.payments.approval-service
    :label "withdraw!"
    :call (fn [source {:keys [org actor instruction]}]
            (approval-service/withdraw! source {:organisation-id org
                                                :instruction-id instruction
                                                :approval-id (random-uuid)
                                                :actor {:id actor}
                                                :correlation-id "corr-f-011"}))}

   {:ns 'clofin.settlement.service
    :label "create-batch!"
    :call (fn [source {:keys [org actor instruction]}]
            (settlement-service/create-batch!
             source {:batch-id (random-uuid) :organisation-id org
                     :scheme "SIM-RTGS" :currency "SGD" :value-date value-date
                     :instruction-ids [instruction] :actor {:id actor}
                     :correlation-id "corr-f-011"}))}

   {:ns 'clofin.settlement.service
    :label "submit-batch!"
    :call (fn [source {:keys [org actor batch]}]
            (settlement-service/submit-batch!
             source {:organisation-id org :batch-id batch :actor {:id actor}
                     :correlation-id "corr-f-011" :entry-ids [(random-uuid)]
                     :occurred-at (Instant/parse "2026-08-04T09:00:00Z")}))}

   {:ns 'clofin.settlement.service
    :label "record-scheme-response!"
    ;; Settlement's is the one where a lost transaction does the most damage: a
    ;; finality posting committed apart from the outcome that caused it is a
    ;; payment the ledger says settled and the batch says did not. It is also
    ;; the entry point whose receipt must commit with its refusal (F-008) — a
    ;; guarantee that means nothing without a transaction to commit it in.
    :call (fn [source {:keys [org actor batch instruction]}]
            (settlement-service/record-scheme-response!
             source {:organisation-id org :batch-id batch :instruction-id instruction
                     :kind "settled" :reference "SIM-STL-F011"
                     :actor {:id actor} :correlation-id "corr-f-011"
                     :entry-id (random-uuid)
                     :occurred-at (Instant/parse "2026-08-04T09:00:00Z")}))}

   {:ns 'clofin.settlement.service
    :label "sweep-timeouts!"
    :call (fn [source {:keys [org actor batch]}]
            (settlement-service/sweep-timeouts!
             source {:organisation-id org :batch-id batch :actor {:id actor}
                     :correlation-id "corr-f-011" :horizon-seconds 0}))}

   ;; Reconciliation (TASK-008). The statement receipt, the matches, the breaks
   ;; and every audit event describing them are one unit of work; so are an
   ;; adjustment's posting, its break's resolution and the journal entry
   ;; between them. A lost transaction here is a break opened against a receipt
   ;; that did not commit, or an entry in the books with no adjustment behind
   ;; it — both states C-05 calls unrepresentable.
   {:ns 'clofin.recon.service
    :label "ingest-statement!"
    :call (fn [source {:keys [org actor]}]
            (recon-service/ingest-statement!
             source {:organisation-id org
                     :statement-id (random-uuid)
                     :actor {:id actor}
                     :correlation-id "corr-f-011"
                     :statement {:format statement/format-name
                                 :format-version statement/format-version
                                 :scheme "SIM-RTGS"
                                 :currency "SGD"
                                 :statement-reference "SIM-STMT-F011"
                                 :period-start (Instant/parse "2026-08-01T00:00:00Z")
                                 :period-end   (Instant/parse "2026-09-01T00:00:00Z")
                                 :lines []}}))}

   {:ns 'clofin.recon.service
    :label "assign-break!"
    :call (fn [source {:keys [org actor]}]
            (recon-service/assign-break!
             source {:organisation-id org :break-id (random-uuid)
                     :assignee-id actor :actor {:id actor}
                     :correlation-id "corr-f-011"}))}

   {:ns 'clofin.recon.service
    :label "propose-adjustment!"
    :call (fn [source {:keys [org actor]}]
            (recon-service/propose-adjustment!
             source {:organisation-id org :break-id (random-uuid)
                     :adjustment-id (random-uuid)
                     :amount (money/of "SGD" 12500)
                     :direction :credit
                     :narrative "Adjusting a break the scheme reported"
                     :actor {:id actor} :correlation-id "corr-f-011"
                     :entry-id (random-uuid)
                     :occurred-at (Instant/parse "2026-08-04T09:00:00Z")}))}

   {:ns 'clofin.recon.service
    :label "decide-adjustment!"
    :call (fn [source {:keys [org actor]}]
            (recon-service/decide-adjustment!
             source {:organisation-id org :adjustment-id (random-uuid)
                     :approval-id (random-uuid) :actor {:id actor}
                     :decision :approved
                     :correlation-id "corr-f-011"
                     :entry-id (random-uuid)
                     :occurred-at (Instant/parse "2026-08-04T09:00:00Z")}))}

   {:ns 'clofin.recon.service
    :label "decide-adjustment! (rejecting)"
    :call (fn [source {:keys [org actor]}]
            (recon-service/decide-adjustment!
             source {:organisation-id org :adjustment-id (random-uuid)
                     :approval-id (random-uuid) :actor {:id actor}
                     :decision :rejected :reason "the scheme was right"
                     :correlation-id "corr-f-011"
                     :entry-id (random-uuid)
                     :occurred-at (Instant/parse "2026-08-04T09:00:00Z")}))}])

(deftest every-audit-composing-service-is-covered-here
  (testing "the same set is written down in two places, so they are compared —
            a service in the purity guard but not in this matrix is a service
            free to be called with a pool, which is the whole finding"
    (is (= (set (keys purity/service-namespaces))
           (set (map :ns audit-composing-calls)))
        (str "the transaction precondition must be asserted for every service "
             "`clofin.ledger.purity-test` guards, and only for those"))))

;; ---------------------------------------------------------------------------
;; The negative arrivals
;; ---------------------------------------------------------------------------

(defn- refused?
  "True when `f` was refused for want of a transaction, rather than reaching its
  work and failing there.

  Matched on the message rather than on the error class because `:validation` is
  a broad category: several of these calls would *also* fail validation deeper
  in, and a test that accepted any `:validation` error would pass on a service
  with no precondition at all."
  [f]
  (let [t (try (f) nil (catch Exception e e))]
    (boolean (and t (re-find #"must run inside a transaction" (str (ex-message t)))))))

(deftest f-011-no-audit-composing-service-accepts-a-pool
  (testing "the auditor's reproduction, over the whole set"
    (doseq [{:keys [label call]} audit-composing-calls]
      (let [f (setup)
            before (counts)]
        (is (refused? #(call tdb/*pool* f))
            (str label " must refuse a connection pool: it has no transaction, so its "
                 "aggregate write would commit on its own and a later audit failure "
                 "would leave the change unaudited"))
        (is (= before (counts))
            (str label " wrote something before refusing — a precondition that fires "
                 "after the first write is not a precondition (F-011)"))
        (tdb/clean-business-data! tdb/*pool*)))))

(deftest f-011-no-audit-composing-service-accepts-an-autocommit-connection
  (testing "the convincing near-miss: a real java.sql.Connection, which satisfies
            every type check a reviewer would think to write, and still commits
            statement by statement"
    (doseq [{:keys [label call]} audit-composing-calls]
      (let [f (setup)
            before (counts)]
        (with-open [conn (.getConnection ^javax.sql.DataSource tdb/*pool*)]
          (is (.getAutoCommit conn)
              "the pool hands out autocommit connections — if that ever changes, this
               test is asserting nothing and must be rewritten rather than deleted")
          (is (refused? #(call conn f))
              (str label " must refuse an autocommit connection")))
        (is (= before (counts))
            (str label " wrote something before refusing (F-011)"))
        (tdb/clean-business-data! tdb/*pool*)))))

(deftest f-011-a-real-transaction-is-accepted
  (testing "the guard refuses the wrong thing and only the wrong thing — a
            precondition nothing satisfies is an outage, not a control"
    (let [f (setup)]
      (db/with-transaction [tx tdb/*pool*]
        (is (= tx (audit-store/assert-unit-of-work! tx)) "and returns the transaction")
        (is (some? (settlement-service/create-batch!
                    tx {:batch-id (random-uuid) :organisation-id (:org f)
                        :scheme "SIM-RTGS" :currency "SGD" :value-date value-date
                        :instruction-ids [(:approved f)]
                        :actor {:id (:actor f)}
                        :correlation-id "corr-f-011-positive"}))
            "the same call that is refused above succeeds on a transaction")))))

(deftest the-assertion-names-what-was-wrong-with-what-it-was-given
  (testing "a pool and an autocommit connection are different mistakes and get
            different sentences; a caller told only `bad connection` has to read
            the source to find out which"
    (let [pool-message (try (audit-store/assert-unit-of-work! tdb/*pool*) nil
                            (catch Exception e (ex-message e)))]
      (is (re-find #"connection pool" pool-message)))
    (with-open [conn (.getConnection ^javax.sql.DataSource tdb/*pool*)]
      (let [conn-message (try (audit-store/assert-unit-of-work! conn) nil
                              (catch Exception e (ex-message e)))]
        (is (re-find #"autocommit" conn-message))))))
