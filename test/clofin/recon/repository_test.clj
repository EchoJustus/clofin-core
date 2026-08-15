(ns clofin.recon.repository-test
  "Reconciliation's persistence, against a real PostgreSQL — because everything
  worth asserting here is a claim about what the schema permits.

  The centre of it is `expectations-for`, which is where reconciliation's
  independence lives: it reads the **journal** and nothing else, and what it
  narrows to is a set of decisions rather than a convenience filter. Each of
  those decisions is asserted by a row it must *not* return."
  (:require [clofin.db.core :as db]
            [clofin.money :as money]
            [clofin.recon.repository :as recon]
            [clofin.recon.statement :as statement]
            [clofin.test-db :as tdb]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time Instant]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private from (Instant/parse "2026-08-01T00:00:00Z"))
(def ^:private to   (Instant/parse "2026-09-01T00:00:00Z"))
(def ^:private mid  (Instant/parse "2026-08-12T10:00:00Z"))

(defn- setup
  "An organisation with the four accounts reconciliation and settlement touch."
  []
  (let [org (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)
                                                  :short-name (str "meridian-" (rand-int 100000000))})
        account (fn [code type]
                  (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                   :code code :type type :currency "SGD"}))
        actor (tdb/insert-actor! tdb/*pool* {:organisation-id org :roles [:controller]})]
    {:org org
     :actor actor
     :in-transit (account "1300-IN-TRANSIT" "asset")
     :funds      (account "1100-CLIENT-FUNDS" "asset")
     :payable    (account "2100-CLIENT-PAYABLE" "liability")
     :unapplied  (account "2200-UNAPPLIED" "liability")}))

(defn- reconciled-account
  [{:keys [org]}]
  (recon/account-by-code tdb/*pool* org "SGD" "1300-IN-TRANSIT"))

(defn- expectations
  [f & {:keys [period-from period-to] :or {period-from from period-to to}}]
  (recon/expectations-for tdb/*pool* (:org f) (reconciled-account f)
                          {:from period-from :to period-to}))

(defn- settlement-entry!
  "A finality entry as `clofin.payments.posting/settlement-entry` produces one:
  debit the client's payable, credit the clearing account."
  [{:keys [org in-transit payable]} instruction-id & {:keys [amount at]
                                                      :or {amount 125000 at mid}}]
  (tdb/insert-balanced-entry! tdb/*pool* {:organisation-id org
                                          :debit-account-id payable
                                          :credit-account-id in-transit
                                          :amount-minor amount
                                          :occurred-at at
                                          :reference-type "payment-instruction"
                                          :reference-id instruction-id}))

(defn- return-entry!
  "The other finality template: debit client funds, credit the clearing account."
  [{:keys [org in-transit funds]} instruction-id & {:keys [amount at]
                                                    :or {amount 90000 at mid}}]
  (tdb/insert-balanced-entry! tdb/*pool* {:organisation-id org
                                          :debit-account-id funds
                                          :credit-account-id in-transit
                                          :amount-minor amount
                                          :occurred-at at
                                          :reference-type "payment-instruction"
                                          :reference-id instruction-id}))

;; ---------------------------------------------------------------------------
;; What CloFin's ledger says happened
;; ---------------------------------------------------------------------------

(deftest an-expectation-is-a-credit-on-the-reconciled-account
  (let [f (setup)
        instruction (random-uuid)]
    (settlement-entry! f instruction)
    (let [{:keys [expectations truncated?]} (expectations f)]
      (is (false? truncated?))
      (is (= 1 (count expectations)))
      (let [e (first expectations)]
        (is (= instruction (:payment-reference e))
            "the end-to-end reference is the instruction the entry names")
        (is (= (money/of "SGD" 125000) (:amount e))
            "the amount comes from the posted journal line, not from the instruction")
        (is (= "2026-08-12" (str (:value-date e)))
            "and the date from the entry's occurrence, at UTC")
        (is (= "settlement" (:line-type e)))))))

(deftest the-kind-of-movement-is-derived-from-the-counter-account
  (testing "CloFin's independent answer to the question the statement's lineType
            also answers — readable from the accounting alone because ADR-0018
            gave the two finality templates different counter-accounts"
    (let [f (setup)]
      (settlement-entry! f (random-uuid))
      (return-entry! f (random-uuid))
      (let [by-type (group-by :line-type (:expectations (expectations f)))]
        (is (= 1 (count (get by-type "settlement"))))
        (is (= 1 (count (get by-type "return"))))
        (is (= "2100-CLIENT-PAYABLE"
               (:counter-account-code (first (get by-type "settlement")))))
        (is (= "1100-CLIENT-FUNDS"
               (:counter-account-code (first (get by-type "return")))))))))

(deftest a-movement-whose-counter-account-is-neither-has-no-derivable-kind
  (testing "and nil is the honest answer: reporting a mismatch out of an absence
            is the overstatement L-14 names"
    (let [f (setup)]
      (tdb/insert-balanced-entry! tdb/*pool* {:organisation-id (:org f)
                                              :debit-account-id (:unapplied f)
                                              :credit-account-id (:in-transit f)
                                              :occurred-at mid
                                              :reference-type "payment-instruction"
                                              :reference-id (random-uuid)})
      (let [e (first (:expectations (expectations f)))]
        (is (= "2200-UNAPPLIED" (:counter-account-code e)))
        (is (nil? (:line-type e)))))))

(deftest a-release-is-not-an-expectation
  (testing "a release DEBITS the clearing account — that is CloFin handing money
            to the scheme, not the scheme reporting back — and a statement that
            listed releases would be the scheme telling CloFin what CloFin told
            it"
    (let [f (setup)]
      ;; The release template: debit in-transit, credit client funds.
      (tdb/insert-balanced-entry! tdb/*pool* {:organisation-id (:org f)
                                              :debit-account-id (:in-transit f)
                                              :credit-account-id (:funds f)
                                              :occurred-at mid
                                              :reference-type "payment-instruction"
                                              :reference-id (random-uuid)})
      (is (empty? (:expectations (expectations f)))))))

(deftest an-adjustment-is-not-an-expectation
  (testing "an adjustment is CloFin's record of a reconciliation decision, not a
            movement any scheme reports. Including it would make every resolved
            break reappear as an unmatched expectation the next time the period
            was reconciled"
    (let [f (setup)]
      (tdb/insert-balanced-entry! tdb/*pool* {:organisation-id (:org f)
                                              :debit-account-id (:unapplied f)
                                              :credit-account-id (:in-transit f)
                                              :occurred-at mid
                                              :reference-type "reconciliation-adjustment"
                                              :reference-id (random-uuid)})
      (is (empty? (:expectations (expectations f)))))))

(deftest another-accounts-movements-and-another-tenants-are-not-expectations
  (let [f (setup)
        other (setup)]
    ;; A movement on a different account of the same organisation.
    (tdb/insert-balanced-entry! tdb/*pool* {:organisation-id (:org f)
                                            :debit-account-id (:in-transit f)
                                            :credit-account-id (:payable f)
                                            :occurred-at mid
                                            :reference-type "payment-instruction"
                                            :reference-id (random-uuid)})
    ;; And a real expectation belonging to somebody else.
    (settlement-entry! other (random-uuid))
    (is (empty? (:expectations (expectations f))))
    (is (= 1 (count (:expectations (expectations other)))))))

(deftest the-period-is-half-open
  (let [f (setup)]
    (settlement-entry! f (random-uuid) :at from)
    (settlement-entry! f (random-uuid) :at to)
    (is (= 1 (count (:expectations (expectations f))))
        "from is included and to is excluded, so consecutive periods chain
         exactly rather than double-counting the boundary")))

(deftest a-period-with-more-movements-than-the-cap-reports-truncation
  (testing "and the service refuses rather than reconciling part of a period —
            a movement left out becomes a break against a movement that is right
            there in the journal"
    (let [f (setup)]
      (dotimes [_ (inc recon/expectation-cap)]
        (settlement-entry! f (random-uuid)))
      (let [{:keys [expectations truncated?]} (expectations f)]
        (is (true? truncated?))
        (is (= recon/expectation-cap (count expectations)))))))

;; ---------------------------------------------------------------------------
;; Statement receipts
;; ---------------------------------------------------------------------------

(defn- receipt
  [f & {:keys [reference digest disposition reason]
        :or {reference "SIM-STMT-1" digest "v1:abc" disposition "applied"}}]
  {:id (random-uuid) :organisation-id (:org f) :scheme "SIM-RTGS" :currency "SGD"
   :statement-reference reference
   :format statement/format-name :format-version statement/format-version
   :period-start from :period-end to :content-digest digest
   :disposition disposition :disposition-reason reason
   :reconciled-account-id (:id (reconciled-account f))
   :received-by (:actor f)})

(deftest a-receipt-round-trips-and-is-found-by-its-reference
  (let [f (setup)]
    (db/with-transaction [tx tdb/*pool*]
      (let [stored (recon/insert-statement! tx (receipt f))]
        (is (some? stored))
        (is (= "applied" (:disposition stored)))
        (is (= statement/format-version (:format-version stored)))
        (is (= (:id stored) (:id (recon/find-statement-by-reference
                                  tx (:org f) "SIM-STMT-1"))))))))

(deftest a-second-receipt-under-one-reference-is-refused-without-aborting-the-work
  (testing "inside a savepoint, because PostgreSQL aborts the whole transaction
            on a constraint violation — merely catching the duplicate would
            leave the caller in a transaction whose next read fails for a reason
            unrelated to what it asked"
    (let [f (setup)]
      (db/with-transaction [tx tdb/*pool*]
        (is (some? (recon/insert-statement! tx (receipt f))))
        (is (nil? (recon/insert-statement! tx (receipt f :digest "v1:different"))))
        (is (some? (recon/find-statement-by-reference tx (:org f) "SIM-STMT-1"))
            "and the transaction is still usable afterwards, which is the whole
             point of the savepoint")))))

(deftest two-tenants-may-receive-the-same-statement-reference
  (let [f (setup)
        other (setup)]
    (db/with-transaction [tx tdb/*pool*]
      (is (some? (recon/insert-statement! tx (receipt f))))
      (is (some? (recon/insert-statement! tx (receipt other)))
          "the replay key is scoped to the organisation: a statement is
           addressed to a tenant"))))

(deftest a-refused-receipt-carries-a-reason-and-an-applied-one-carries-none
  (let [f (setup)]
    (db/with-transaction [tx tdb/*pool*]
      (is (some? (recon/insert-statement! tx (receipt f :reference "r1"
                                                      :disposition "refused"
                                                      :reason "no-reconciled-account")))))
    (testing "a refusal with no reason is unactionable, and an applied row with
              one is a contradiction; the schema refuses both"
      (doseq [[label candidate]
              [["refused, no reason" (receipt f :reference "r2" :disposition "refused")]
               ["applied, with reason" (receipt f :reference "r3"
                                                :reason "no-reconciled-account")]]]
        (is (thrown? Exception
                     (db/with-transaction [tx tdb/*pool*]
                       (recon/insert-statement! tx candidate)))
            label)))))

(deftest a-disposition-or-reason-the-vocabulary-does-not-know-is-refused-by-name
  (let [f (setup)]
    (db/with-transaction [tx tdb/*pool*]
      (doseq [candidate [(receipt f :disposition "maybe")
                         (receipt f :disposition "refused" :reason "because")]]
        (let [t (try (recon/insert-statement! tx candidate) nil (catch Exception e e))]
          (is (some? t))
          (is (= :validation (:clofin/error (ex-data t)))
              "refused in the application with the vocabulary named, rather than
               as a raw constraint failure rendered 500 — the shape A-017 found"))))))

;; ---------------------------------------------------------------------------
;; Breaks
;; ---------------------------------------------------------------------------

(defn- open-break!
  "A statement carrying one line, and a break about that line.

  A break must name at least one side — `recon_break_has_a_side` — so the
  fixture builds the line the break is about rather than a break about nothing."
  [f & {:keys [kind detail] :or {kind "statement-line-unmatched"
                                 detail "The scheme reports money CloFin does not"}}]
  (db/with-transaction [tx tdb/*pool*]
    (let [stmt (:id (recon/insert-statement! tx (receipt f :reference (str (random-uuid)))))]
      (recon/insert-lines! tx stmt
                           [{:line-no 1 :scheme-reference "SIM-STMT-LN-1"
                             :payment-reference "abc" :line-type "settlement"
                             :amount (money/of "SGD" 125000)
                             :value-date (java.time.LocalDate/parse "2026-08-12")}])
      (recon/insert-break! tx {:id (random-uuid)
                               :organisation-id (:org f)
                               :statement-id stmt
                               :account-id (:id (reconciled-account f))
                               :kind kind
                               :line-no 1
                               :entry-id nil
                               :currency "SGD"
                               :statement-amount (money/of "SGD" 125000)
                               :ledger-amount nil
                               :detail detail
                               :assignee-id (:actor f)}))))

(deftest a-break-opens-open-assigned-and-with-a-derived-age
  (let [f (setup)
        brk (open-break! f)]
    (is (= :open (:state brk)))
    (is (= (:actor f) (:assignee-id brk)))
    (is (some? (:age-seconds brk)))
    (is (<= 0 (:age-seconds brk) 60)
        "derived from opened-at at read time, not stored")
    (is (nil? (:resolved-at brk)))))

(deftest the-age-is-derived-and-so-it-grows-between-reads
  (let [f (setup)
        brk (open-break! f)]
    (db/execute! tdb/*pool* ["update reconciliation_break
                               set opened_at = opened_at - interval '2 hours'
                             where id = ?" (:id brk)])
    (let [older (recon/find-break tdb/*pool* (:org f) (:id brk))]
      (is (<= 7200 (:age-seconds older))
          "there is no age column to have gone stale: the value is now() minus
           opened_at, computed by the statement that read the row"))))

(deftest a-break-kind-the-vocabulary-does-not-know-is-refused-by-name
  (let [f (setup)]
    (db/with-transaction [tx tdb/*pool*]
      (let [stmt (:id (recon/insert-statement! tx (receipt f)))
            t (try (recon/insert-break! tx {:id (random-uuid) :organisation-id (:org f)
                                            :statement-id stmt
                                            :account-id (:id (reconciled-account f))
                                            :kind "vibes-mismatch" :currency "SGD"
                                            :entry-id (settlement-entry! f (random-uuid))
                                            :detail "?" :assignee-id (:actor f)})
                   nil (catch Exception e e))]
        (is (some? t))
        (is (= :validation (:clofin/error (ex-data t))))))))

(deftest a-break-with-neither-side-cannot-be-written
  (testing "a disagreement about nothing is not a disagreement — and the guard is
            a check constraint, so it binds a fix-up script too"
    (let [f (setup)]
      (is (thrown? Exception
                   (db/with-transaction [tx tdb/*pool*]
                     (let [stmt (:id (recon/insert-statement! tx (receipt f)))]
                       (db/execute! tx ["insert into reconciliation_break
                                           (id, organisation_id, statement_id, account_id,
                                            kind, currency, detail, assignee_id)
                                         values (?, ?, ?, ?, 'amount-mismatch', 'SGD', '?', ?)"
                                        (random-uuid) (:org f) stmt
                                        (:id (reconciled-account f)) (:actor f)]))))))))

(deftest a-resolved-break-must-have-a-resolution-time-and-only-a-resolved-one
  (let [f (setup)
        brk (open-break! f)]
    (is (thrown? Exception
                 (db/execute! tdb/*pool* ["update reconciliation_break set state = 'resolved'
                                          where id = ?" (:id brk)]))
        "the pair is a check constraint, stated as an equivalence in both
         directions so neither half can drift")
    (is (thrown? Exception
                 (db/execute! tdb/*pool* ["update reconciliation_break set resolved_at = now()
                                          where id = ?" (:id brk)])))))

(deftest set-break-state-writes-the-resolution-time-with-the-terminal-state
  (let [f (setup)
        brk (open-break! f)]
    (db/with-transaction [tx tdb/*pool*]
      (let [resolved (recon/set-break-state! tx (:org f) (:id brk)
                                             {:state :resolved :assignee-id (:actor f)})]
        (is (= :resolved (:state resolved)))
        (is (some? (:resolved-at resolved)))))))

(deftest a-break-must-name-the-actor-it-is-assigned-to
  (let [f (setup)
        brk (open-break! f)]
    (db/with-transaction [tx tdb/*pool*]
      (let [t (try (recon/set-break-state! tx (:org f) (:id brk)
                                           {:state :investigating :assignee-id nil})
                   nil (catch Exception e e))]
        (is (some? t) "a null assignee would blank the column PR-052 requires")
        (is (= :validation (:clofin/error (ex-data t))))))))

(deftest breaks-are-listed-oldest-first-and-narrowed-by-state
  (let [f (setup)
        older (open-break! f)
        _     (db/execute! tdb/*pool* ["update reconciliation_break
                                         set opened_at = opened_at - interval '1 day'
                                       where id = ?" (:id older)])
        newer (open-break! f :kind "expectation-unmatched")]
    (is (= [(:id older) (:id newer)]
           (mapv :id (:breaks (recon/list-breaks tdb/*pool* (:org f) {}))))
        "the only list in CloFin ordered oldest first, and it is the product
         point: a break found in March may have originated in January")
    (is (= [(:id newer)]
           (mapv :id (:breaks (recon/list-breaks tdb/*pool* (:org f)
                                                 {:kind "expectation-unmatched"})))))
    (is (empty? (:breaks (recon/list-breaks tdb/*pool* (:org f) {:state "resolved"}))))
    (is (= 2 (count (:breaks (recon/list-breaks tdb/*pool* (:org f)
                                                {:assignee-id (:actor f)})))))
    (is (thrown? Exception (recon/list-breaks tdb/*pool* (:org f) {:state "haunted"})))))

;; ---------------------------------------------------------------------------
;; Adjustments
;; ---------------------------------------------------------------------------

(defn- propose!
  [f brk & {:keys [required] :or {required 0}}]
  (db/with-transaction [tx tdb/*pool*]
    (recon/insert-adjustment! tx {:id (random-uuid) :organisation-id (:org f)
                                  :break-id (:id brk)
                                  :amount (money/of "SGD" 125000)
                                  :direction :credit
                                  :narrative "Agreeing with the scheme"
                                  :approvals-required required
                                  :created-by (:actor f)})))

(deftest an-adjustment-posts-exactly-once
  (let [f (setup)
        brk (open-break! f)
        adj (propose! f brk)
        entry (settlement-entry! f (random-uuid))]
    (db/with-transaction [tx tdb/*pool*]
      (is (some? (recon/mark-posted! tx (:org f) (:id adj) entry)))
      (is (nil? (recon/mark-posted! tx (:org f) (:id adj) entry))
          "`where status = 'proposed'` is the whole guarantee, and it is in the
           statement rather than in a preceding read"))))

(deftest a-second-posted-adjustment-for-one-break-is-refused-by-the-schema
  (let [f (setup)
        brk (open-break! f)
        first-adj (propose! f brk)
        second-adj (propose! f brk)
        entry-a (settlement-entry! f (random-uuid))
        entry-b (settlement-entry! f (random-uuid))]
    (db/with-transaction [tx tdb/*pool*]
      (is (some? (recon/mark-posted! tx (:org f) (:id first-adj) entry-a))))
    (let [t (try (db/with-transaction [tx tdb/*pool*]
                   (recon/mark-posted! tx (:org f) (:id second-adj) entry-b))
                 nil (catch Exception e e))]
      (is (some? t) "a break is corrected once")
      (is (= :conflict (:clofin/error (ex-data t)))
          "translated into a named 409 rather than surfacing as a 500")
      (is (str/includes? (ex-message t) "already has a posted adjustment")))))

(deftest a-posted-adjustment-must-name-its-entry-and-a-proposed-one-must-not
  (let [f (setup)
        brk (open-break! f)
        adj (propose! f brk)]
    (is (thrown? Exception
                 (db/execute! tdb/*pool* ["update reconciliation_adjustment
                                            set status = 'posted' where id = ?" (:id adj)])))
    (is (thrown? Exception
                 (db/execute! tdb/*pool* ["update reconciliation_adjustment
                                            set entry_id = ? where id = ?"
                                          (settlement-entry! f (random-uuid)) (:id adj)])))))

;; ---------------------------------------------------------------------------
;; Append-only enforcement — the full destructive verb set (lesson L-5)
;; ---------------------------------------------------------------------------

(deftest what-arrived-and-what-it-matched-cannot-be-edited-afterwards
  (let [f (setup)]
    (db/with-transaction [tx tdb/*pool*]
      (let [stmt (recon/insert-statement! tx (receipt f))]
        (recon/insert-lines! tx (:id stmt)
                             [{:line-no 1 :scheme-reference "SIM-STMT-LN-1"
                               :payment-reference "abc" :line-type "settlement"
                               :amount (money/of "SGD" 125000)
                               :value-date (java.time.LocalDate/parse "2026-08-12")}])))
    (let [stmt-id (:id (recon/find-statement-by-reference tdb/*pool* (:org f) "SIM-STMT-1"))]
      (testing "every destructive verb, each with its own trigger event and its
                own privilege — TRUNCATE visits no rows, which is exactly how
                audit finding F-002 emptied audit_event past a row guard"
        (doseq [[table sql]
                [["reconciliation_statement"
                  ["update reconciliation_statement set disposition = 'refused' where id = ?" stmt-id]]
                 ["reconciliation_statement"
                  ["delete from reconciliation_statement where id = ?" stmt-id]]
                 ["reconciliation_statement" ["truncate reconciliation_statement cascade"]]
                 ["reconciliation_statement_line"
                  ["update reconciliation_statement_line set amount_minor = 1 where statement_id = ?" stmt-id]]
                 ["reconciliation_statement_line"
                  ["delete from reconciliation_statement_line where statement_id = ?" stmt-id]]
                 ["reconciliation_statement_line" ["truncate reconciliation_statement_line cascade"]]]]
          (is (thrown? Exception (db/execute! tdb/*pool* sql))
              (str table ": " (first sql))))))))

(deftest a-match-cannot-be-rewritten-to-name-a-different-rule
  (let [f (setup)
        entry (settlement-entry! f (random-uuid))]
    (db/with-transaction [tx tdb/*pool*]
      (let [stmt (recon/insert-statement! tx (receipt f))]
        (recon/insert-lines! tx (:id stmt)
                             [{:line-no 1 :scheme-reference "x" :payment-reference "abc"
                               :line-type "settlement" :amount (money/of "SGD" 125000)
                               :value-date (java.time.LocalDate/parse "2026-08-12")}])
        (recon/insert-matches! tx (:id stmt)
                               [{:line-no 1 :entry-id entry
                                 :rule-id "R1-reference-amount-and-value-date"}])))
    (testing "which rule matched is the explanation PR-051 asks for; an editable
              explanation is not one"
      (is (thrown? Exception
                   (db/execute! tdb/*pool* ["update reconciliation_match
                                              set rule_id = 'R3-reference-only'"])))
      (is (thrown? Exception (db/execute! tdb/*pool* ["delete from reconciliation_match"])))
      (is (thrown? Exception (db/execute! tdb/*pool* ["truncate reconciliation_match"]))))))

(deftest one-ledger-movement-is-claimed-by-at-most-one-line-of-a-statement
  (testing "the index is why a second claim becomes a break rather than a second
            match, and it is in the schema so it binds a fix-up script too"
    (let [f (setup)
          entry (settlement-entry! f (random-uuid))]
      (is (thrown? Exception
                   (db/with-transaction [tx tdb/*pool*]
                     (let [stmt (recon/insert-statement! tx (receipt f))]
                       (recon/insert-lines!
                        tx (:id stmt)
                        [{:line-no 1 :scheme-reference "a" :payment-reference "abc"
                          :line-type "settlement" :amount (money/of "SGD" 1)
                          :value-date (java.time.LocalDate/parse "2026-08-12")}
                         {:line-no 2 :scheme-reference "b" :payment-reference "abc"
                          :line-type "settlement" :amount (money/of "SGD" 1)
                          :value-date (java.time.LocalDate/parse "2026-08-12")}])
                       (recon/insert-matches!
                        tx (:id stmt)
                        [{:line-no 1 :entry-id entry :rule-id "R1-reference-amount-and-value-date"}
                         {:line-no 2 :entry-id entry :rule-id "R1-reference-amount-and-value-date"}]))))))))

(deftest a-rule-id-the-code-does-not-know-is-refused-before-the-constraint-sees-it
  (let [f (setup)
        entry (settlement-entry! f (random-uuid))]
    (db/with-transaction [tx tdb/*pool*]
      (let [stmt (recon/insert-statement! tx (receipt f))]
        (recon/insert-lines! tx (:id stmt)
                             [{:line-no 1 :scheme-reference "a" :payment-reference "abc"
                               :line-type "settlement" :amount (money/of "SGD" 1)
                               :value-date (java.time.LocalDate/parse "2026-08-12")}])
        (let [t (try (recon/insert-matches! tx (:id stmt)
                                            [{:line-no 1 :entry-id entry :rule-id "R9-vibes"}])
                     nil (catch Exception e e))]
          (is (some? t))
          (is (= :validation (:clofin/error (ex-data t)))))))))

;; ---------------------------------------------------------------------------
;; Status (PR-054)
;; ---------------------------------------------------------------------------

(deftest status-counts-statements-whose-own-period-lies-inside-the-requested-one
  (testing "a statement covering the last week of July and the first of August
            is not an August statement, and counting half of it would produce a
            figure that agrees with no document"
    (let [f (setup)
          account (:id (reconciled-account f))]
      (db/with-transaction [tx tdb/*pool*]
        (recon/insert-statement! tx (receipt f :reference "inside"))
        (recon/insert-statement! tx (assoc (receipt f :reference "straddling")
                                           :period-start (Instant/parse "2026-07-25T00:00:00Z"))))
      (let [status (recon/status-for tdb/*pool* (:org f) account {:from from :to to})]
        (is (= 1 (get-in status [:statements :received])))
        (is (= 1 (get-in status [:statements :applied])))
        (is (= 0 (get-in status [:statements :refused])))))))

(deftest status-reports-matched-unmatched-and-breaks-by-state-and-kind
  (let [f (setup)
        account (:id (reconciled-account f))
        entry (settlement-entry! f (random-uuid))]
    (db/with-transaction [tx tdb/*pool*]
      (let [stmt (recon/insert-statement! tx (receipt f))]
        (recon/insert-lines! tx (:id stmt)
                             [{:line-no 1 :scheme-reference "a" :payment-reference "abc"
                               :line-type "settlement" :amount (money/of "SGD" 1)
                               :value-date (java.time.LocalDate/parse "2026-08-12")}
                              {:line-no 2 :scheme-reference "b" :payment-reference "def"
                               :line-type "settlement" :amount (money/of "SGD" 2)
                               :value-date (java.time.LocalDate/parse "2026-08-12")}])
        (recon/insert-matches! tx (:id stmt)
                               [{:line-no 1 :entry-id entry
                                 :rule-id "R2-reference-and-amount"}])
        (recon/insert-break! tx {:id (random-uuid) :organisation-id (:org f)
                                 :statement-id (:id stmt) :account-id account
                                 :kind "statement-line-unmatched" :line-no 2
                                 :currency "SGD" :detail "?" :assignee-id (:actor f)})))
    (let [status (recon/status-for tdb/*pool* (:org f) account {:from from :to to})]
      (is (= {:total 2 :matched 1 :unmatched 1} (:lines status)))
      (is (= {"R2-reference-and-amount" 1} (:matches-by-rule status)))
      (is (= {"open" 1} (:breaks-by-state status)))
      (is (= {"statement-line-unmatched" 1} (:breaks-by-kind status)))
      (is (some? (:oldest-unresolved-age-seconds status))))))

(deftest status-over-a-period-with-nothing-in-it-is-zeroes-and-a-null-age
  (testing "null is a different statement from zero: nothing is outstanding
            rather than something outstanding for no time"
    (let [f (setup)
          status (recon/status-for tdb/*pool* (:org f) (:id (reconciled-account f))
                                   {:from from :to to})]
      (is (= 0 (get-in status [:statements :received])))
      (is (= {:total 0 :matched 0 :unmatched 0} (:lines status)))
      (is (nil? (:oldest-unresolved-age-seconds status))))))

;; ---------------------------------------------------------------------------
;; Account lookup
;; ---------------------------------------------------------------------------

(deftest an-account-is-matched-on-code-and-currency-together
  (let [f (setup)]
    (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id (:org f)
                                     :code "1300-IN-TRANSIT-USD" :type "asset"
                                     :currency "USD"})
    (is (some? (recon/account-by-code tdb/*pool* (:org f) "SGD" "1300-IN-TRANSIT")))
    (is (nil? (recon/account-by-code tdb/*pool* (:org f) "USD" "1300-IN-TRANSIT"))
        "an account holds exactly one currency (I6), so a SGD clearing account
         cannot carry a USD statement")
    (is (nil? (recon/account-by-code tdb/*pool* (random-uuid) "SGD" "1300-IN-TRANSIT"))
        "and every lookup is scoped by organisation")))
