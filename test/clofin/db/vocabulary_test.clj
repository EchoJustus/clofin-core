(ns clofin.db.vocabulary-test
  "Every closed vocabulary, compared with the **live catalogue**, in both
  directions.

  This file exists because of audit finding **A-014**, and the finding is worth
  restating because the tests it replaces all passed:

  - the role guard read migration `0005` with the regex
    `#\"'(operator|approver|controller|compliance|auditor)'\"` — which can only
    ever match the five roles it already knows, so a sixth SQL role was
    invisible to it;
  - the settlement guards asked, for each *code* value, whether that string
    appeared anywhere in migration `0009`'s text — an extra SQL value satisfies
    that question by never being asked about;
  - the payment-status guard inserted every code state successfully and probed
    one hand-picked invalid value, proving the schema accepts what the code
    knows rather than that it accepts nothing else;
  - account type/status, organisation status, journal reference type, approval
    decision, actor status and the three scheme-response sets had no
    code/schema comparison at all.

  Every one of those is the same shape: **a guard that can only see the values
  it already knows**. That is standing lesson **L-6** — a partial enforcement
  point is a false one — applied to enum drift, and the fix is the one the
  `subjectType` guard in `clofin.contract-test` already took: discover the
  whole set and compare it, rather than iterate the set you have and look for
  each member.

  So nothing here reads a migration file. It reads `pg_constraint` on a
  database migrated to head, extracts the complete value set from each
  vocabulary constraint, and asserts **set equality** with the code that owns
  it. An extra SQL value fails; a missing SQL value fails; a code value with no
  SQL literal fails. And the *set of vocabulary constraints itself* is
  discovered and compared with the table below, so a closed vocabulary added to
  the schema with no owner in code is loud rather than silently unguarded."
  (:require [clofin.authz.approval :as approval]
            [clofin.authz.model :as model]
            [clofin.db.core :as db]
            [clofin.ledger.account :as account]
            [clofin.ledger.entry :as entry]
            [clofin.organisations.organisation :as organisation]
            [clofin.payments.instruction :as instruction]
            [clofin.payments.state :as state]
            [clofin.settlement.batch :as batch]
            [clofin.settlement.response :as response]
            [clofin.test-db :as tdb]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)

;; ---------------------------------------------------------------------------
;; Discovery
;; ---------------------------------------------------------------------------

(def ^:private vocabulary-marker
  "How PostgreSQL renders `check (col in ('a','b'))` back to us.

  `pg_get_constraintdef` normalises the `IN` list to `= ANY (ARRAY[...])`, so
  this one marker finds every closed-vocabulary check whatever form its
  migration was written in — including the nullable ones, whose definition is
  `(col IS NULL) OR (col = ANY (ARRAY[...]))`.

  Matching on the *shape* rather than on a name suffix like `_known` is the
  point: a vocabulary constraint someone named differently is still found."
  "= ANY (ARRAY[")

(defn- literals
  "Every single-quoted literal inside a constraint definition's ARRAY."
  [definition]
  (let [array (second (re-find #"= ANY \(ARRAY\[(.*?)\]\)" definition))]
    (into (sorted-set) (map second) (re-seq #"'([^']*)'" (or array "")))))

(defn- catalogue-vocabularies
  "`{constraint-name #{value …}}` for every closed vocabulary the live database
  actually has.

  Queried without a name allowlist, so a constraint nobody told this test about
  still turns up."
  []
  (into {}
        (keep (fn [{:keys [conname definition]}]
                (when (str/includes? definition vocabulary-marker)
                  [conname (literals definition)])))
        (db/query tdb/*pool*
                  ["select c.conname as conname, pg_get_constraintdef(c.oid) as definition
                      from pg_constraint c
                      join pg_class t on t.oid = c.conrelid
                      join pg_namespace n on n.oid = t.relnamespace
                     where n.nspname = 'public'
                       and c.contype = 'c'"])))

;; ---------------------------------------------------------------------------
;; What owns each one
;; ---------------------------------------------------------------------------

(defn- names
  "A code vocabulary as the strings the schema stores.

  Keywords become their names and maps become their key sets, so a vocabulary
  held as `#{:debit :credit}`, as `{:asset {…}}` or as `#{\"ack\"}` all compare
  against SQL literals without each call site remembering which shape it has."
  [vocabulary]
  (into (sorted-set)
        (map #(if (keyword? %) (name %) (str %)))
        (if (map? vocabulary) (keys vocabulary) vocabulary)))

(def ^:private owners
  "Every closed vocabulary in the schema, and the code that owns it.

  A table of *pairs*, not of values: the values are read from the two sides at
  run time. Extending it is the deliberate act of saying \"this SQL constraint
  and this code set are the same vocabulary\", which is the only claim a test
  can make that a regex over migration text cannot."
  {"actor_status_known"                 #'model/actor-statuses
   "role_known"                         #'model/roles
   "approval_decision_known"            #'approval/decisions
   "journal_entry_reference_type_known" #'entry/reference-types
   "journal_line_direction_known"       #'account/directions
   "ledger_account_type_known"          #'account/account-types
   "ledger_account_status_known"        #'account/account-statuses
   "organisation_status_known"          #'organisation/statuses
   "payment_status_known"               #'state/states
   "payment_purpose_code_known"         #'instruction/purpose-codes
   "settlement_scheme_known"            #'batch/schemes
   "settlement_batch_status_known"      #'batch/statuses
   "settlement_outcome_known"           #'batch/item-outcomes
   "scheme_response_kind_known"         #'response/kinds
   "scheme_response_outcome_known"      #'response/response-outcomes
   "scheme_response_disposition_known"  #'response/dispositions})

;; ---------------------------------------------------------------------------
;; The comparison
;; ---------------------------------------------------------------------------

(deftest a-014-every-schema-vocabulary-has-an-owner-in-code
  (testing "a closed vocabulary the schema gained and no namespace declares is
            unguarded — and would stay unguarded silently, which is the whole
            finding"
    (let [discovered (set (keys (catalogue-vocabularies)))
          declared   (set (keys owners))]
      (is (empty? (set/difference discovered declared))
          (str "Check constraints in the live schema with no owning code vocabulary: "
               (pr-str (vec (sort (set/difference discovered declared))))))
      (is (empty? (set/difference declared discovered))
          (str "Code vocabularies claiming a constraint the live schema does not have: "
               (pr-str (vec (sort (set/difference declared discovered)))))))))

(deftest a-014-every-vocabulary-is-equal-in-both-directions
  (let [catalogue (catalogue-vocabularies)]
    (doseq [[constraint owner] owners]
      (testing constraint
        (let [in-sql  (get catalogue constraint)
              in-code (names @owner)]
          (is (some? in-sql)
              (str constraint " is not in the live catalogue at all"))
          (is (seq in-code)
              (str (symbol owner) " is empty — an equality test between two empty
                    sets passes and proves nothing"))
          (is (empty? (set/difference in-sql in-code))
              (str constraint " accepts " (pr-str (vec (set/difference in-sql in-code)))
                   ", which " (symbol owner) " does not know about — this is the "
                   "extra-SQL-value case every previous guard was blind to"))
          (is (empty? (set/difference in-code in-sql))
              (str (symbol owner) " declares "
                   (pr-str (vec (set/difference in-code in-sql)))
                   ", which " constraint " would refuse on insert")))))))

;; ---------------------------------------------------------------------------
;; A-018 — purpose codes across all three statements of the set
;; ---------------------------------------------------------------------------
;;
;; Code/SQL equality is covered by the table above, now that migration `0011`
;; gives the column a constraint to compare against. The third statement —
;; `PurposeCode` in `api/openapi.yaml` — is compared with the code in
;; `clofin.contract-test`, which is where every other published enum is
;; compared and where the comparison runs without a database. Named here so a
;; reader of either file finds both halves.

(deftest a-018-the-purpose-code-column-is-constrained-at-all
  (testing "before migration 0011 this column was unconstrained `text not null`,
            so DOMAIN_MODEL's \"constrained vocabulary\" was true of the
            application path and false of the system of record"
    (is (contains? (catalogue-vocabularies) "payment_purpose_code_known"))))
