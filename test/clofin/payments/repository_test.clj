(ns clofin.payments.repository-test
  "Persistence for payment instructions, against a real PostgreSQL instance.

  The properties asserted here — `for update` serialising two concurrent state
  changes, a foreign key refusing an account from another organisation, a check
  constraint refusing a status the application does not know — do not exist in
  an in-memory substitute. A double would assert that CloFin's own code agrees
  with itself, which the unit tests already cover."
  (:require [clofin.authz.repository :as authz]
            [clofin.db.core :as db]
            [clofin.money :as money]
            [clofin.payments.repository :as payments]
            [clofin.payments.state :as state]
            [clofin.test-db :as tdb]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time LocalDate]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private today (LocalDate/now java.time.ZoneOffset/UTC))
(def ^:private opts {:today today})

(defn- caught [f] (try (f) nil (catch Exception t t)))
(defn- error-type [f] (some-> (caught f) ex-data :clofin/error))

(defn- fixture
  "An organisation with a client-funds account, both synthetic."
  ([] (fixture {}))
  ([{:keys [currency status] :or {currency "SGD" status "active"}}]
   (let [org-id (tdb/insert-organisation!
                 tdb/*pool*
                 ;; Unique per fixture: several tests here need two
                 ;; organisations, and short names are unique case-insensitively.
                 {:id (random-uuid) :short-name (str "meridian-" (rand-int 100000000))})
         account-id (tdb/insert-account! tdb/*pool*
                                         {:id (random-uuid)
                                          :organisation-id org-id
                                          :code (str "1100-CLIENT-FUNDS-" (rand-int 1000000))
                                          :currency currency
                                          :status status})
         ;; The maker. `amend!` now enforces PR-004 — a draft may be amended by
         ;; its creator — against a real principal rather than a caller-asserted
         ;; UUID, so the fixture has to seed one.
         maker (tdb/insert-actor! tdb/*pool* {:organisation-id org-id
                                              :display-name "Maker"
                                              :roles [:operator]})]
     {:organisation-id org-id :account-id account-id :maker maker
      :actor {:id maker}})))

(defn- candidate
  [{:keys [organisation-id account-id maker] :as fixture} & {:as overrides}]
  (merge {:id                (random-uuid)
          :organisation-id   organisation-id
          :debtor-account-id account-id
          :creditor-name     "Pacific Rim Logistics Pte Ltd"
          :creditor-account  "SG-SYNTH-88012345"
          :amount            (money/of "SGD" 125000)
          :value-date        (.plusDays today 7)
          :purpose-code      "SUPP"
          :created-by        (:maker fixture)}
         overrides))

;; ---------------------------------------------------------------------------
;; Round trip
;; ---------------------------------------------------------------------------

(deftest an-instruction-round-trips-through-the-database-unchanged
  (let [f (fixture)
        created (payments/create-instruction! tdb/*pool* (candidate f) opts)
        found (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))]
    (is (= :draft (:status created)) "a caller cannot choose the status")
    (is (some? (:created-at created)) "the database's own timestamp, not a clock read here")
    (is (= (dissoc created :created-at) (dissoc found :created-at)))
    (testing "money survives as integer minor units, in the currency it was sent"
      (is (= (money/of "SGD" 125000) (:amount found))))
    (testing "the value date is a calendar date and does not drift by a day"
      (is (= (.plusDays today 7) (:value-date found)))
      (is (instance? LocalDate (:value-date found))))))

(deftest an-instruction-in-another-organisation-is-invisible
  (let [a (fixture)
        b (fixture)
        created (payments/create-instruction! tdb/*pool* (candidate a) opts)]
    (is (some? (payments/find-instruction tdb/*pool* (:organisation-id a) (:id created))))
    (is (nil? (payments/find-instruction tdb/*pool* (:organisation-id b) (:id created)))
        "an unscoped lookup is how one tenant reads another's payments")))

(deftest a-zero-decimal-currency-persists-without-a-scale-assumption
  (let [f (fixture {:currency "JPY"})
        created (payments/create-instruction! tdb/*pool*
                                              (candidate f :amount (money/of "JPY" 125000))
                                              opts)]
    (is (= (money/of "JPY" 125000) (:amount (payments/find-instruction
                                             tdb/*pool* (:organisation-id f) (:id created)))))))

;; ---------------------------------------------------------------------------
;; Posting-time rules that need database state
;; ---------------------------------------------------------------------------

(deftest the-debtor-account-must-exist-in-this-organisation
  (let [a (fixture)
        b (fixture)]
    (is (= :unprocessable
           (error-type #(payments/create-instruction!
                         tdb/*pool*
                         (candidate a :debtor-account-id (:account-id b))
                         opts)))
        "an account belonging to another organisation is unknown, not forbidden")
    (is (= :unprocessable
           (error-type #(payments/create-instruction!
                         tdb/*pool* (candidate a :debtor-account-id (random-uuid)) opts))))))

(deftest a-frozen-or-closed-debtor-account-cannot-be-drawn-on
  (doseq [status ["frozen" "closed"]]
    (let [f (fixture {:status status})]
      (is (= :unprocessable (error-type #(payments/create-instruction!
                                          tdb/*pool* (candidate f) opts)))
          (str "a " status " account accepts nothing")))))

(deftest the-instruction-currency-must-match-the-debtor-account
  (let [f (fixture {:currency "SGD"})]
    (is (= :unprocessable
           (error-type #(payments/create-instruction!
                         tdb/*pool* (candidate f :amount (money/of "USD" 100)) opts))))))

(deftest nothing-is-persisted-when-a-rule-refuses
  (let [f (fixture)]
    (caught #(payments/create-instruction! tdb/*pool* (candidate f :amount (money/of "USD" 100)) opts))
    (is (= 0 (:count (db/query-one tdb/*pool* ["select count(*) as count from payment_instruction"]))))))

;; ---------------------------------------------------------------------------
;; The database's own backstops
;; ---------------------------------------------------------------------------

(deftest the-schema-refuses-a-status-the-application-does-not-know
  (let [f (fixture)]
    (is (thrown? Exception
                 (db/execute! tdb/*pool*
                              ["insert into payment_instruction
                                  (id, organisation_id, debtor_account_id, creditor_name,
                                   creditor_account, amount_minor, currency, value_date,
                                   purpose_code, status, created_by)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               (random-uuid) (:organisation-id f) (:account-id f)
                               "X" "SG-SYNTH-1" 100 "SGD" today "SUPP" "in-flight"
                               (random-uuid)]))
        "payment_status_known is a backstop against a row the state machine
         could never produce — not a second copy of the state machine")))

(deftest the-schema-refuses-a-non-positive-amount
  (let [f (fixture)]
    (doseq [amount [0 -1]]
      (is (thrown? Exception
                   (db/execute! tdb/*pool*
                                ["insert into payment_instruction
                                    (id, organisation_id, debtor_account_id, creditor_name,
                                     creditor_account, amount_minor, currency, value_date,
                                     purpose_code, status, created_by)
                                  values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                 (random-uuid) (:organisation-id f) (:account-id f)
                                 "X" "SG-SYNTH-1" amount "SGD" today "SUPP" "draft"
                                 (random-uuid)]))))))

;; ---------------------------------------------------------------------------
;; Transitions
;; ---------------------------------------------------------------------------

(deftest submitting-a-draft-persists-the-new-state
  (let [f (fixture)
        created (payments/create-instruction! tdb/*pool* (candidate f) opts)
        moved (:after (payments/transition! tdb/*pool* (:organisation-id f) (:id created) :submit))]
    (is (= :pending-approval (:status moved)))
    (is (= :pending-approval (:status (payments/find-instruction
                                       tdb/*pool* (:organisation-id f) (:id created))))
        "and it is the stored row that changed, not just the value returned")))

(deftest a-transition-the-lifecycle-refuses-changes-nothing
  (let [f (fixture)
        created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
    (payments/transition! tdb/*pool* (:organisation-id f) (:id created) :submit)
    (is (= :conflict (error-type #(payments/transition! tdb/*pool* (:organisation-id f)
                                                        (:id created) :submit))))
    (is (= :pending-approval (:status (payments/find-instruction
                                       tdb/*pool* (:organisation-id f) (:id created)))))))

(deftest transitioning-an-instruction-in-another-organisation-is-not-found
  (let [a (fixture)
        b (fixture)
        created (payments/create-instruction! tdb/*pool* (candidate a) opts)]
    (is (= :not-found (error-type #(payments/transition! tdb/*pool* (:organisation-id b)
                                                         (:id created) :submit))))))

(deftest two-concurrent-submissions-cannot-both-succeed
  (testing "the row is read `for update`, so the second caller waits, re-reads
            what the first committed, and is refused by the state machine —
            without the lock both would read `draft` and both would write"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)
          start (CountDownLatch. 1)
          done (CountDownLatch. 2)
          outcomes (atom [])
          run (fn []
                (future
                  (.await start)
                  (swap! outcomes conj
                         (try
                           (payments/transition! tdb/*pool* (:organisation-id f)
                                                 (:id created) :submit)
                           :submitted
                           (catch Exception t
                             (or (:clofin/error (ex-data t)) :defect))))
                  (.countDown done)))]
      (run) (run)
      (.countDown start)
      (is (.await done 30 TimeUnit/SECONDS) "both threads must finish")
      (is (= [:conflict :submitted] (vec (sort-by str @outcomes)))
          (str "exactly one submission and one conflict, got " (pr-str @outcomes)))
      (is (= :pending-approval (:status (payments/find-instruction
                                         tdb/*pool* (:organisation-id f) (:id created))))))))

;; ---------------------------------------------------------------------------
;; Amendment
;; ---------------------------------------------------------------------------

(deftest a-draft-can-be-amended-in-place
  (let [f (fixture)
        created (payments/create-instruction! tdb/*pool* (candidate f) opts)
        amended (:after (payments/amend! tdb/*pool* (:organisation-id f) (:id created)
                                         {:amount (money/of "SGD" 999)
                                          :creditor-name "Andaman Shipping Sdn Bhd"}
                                         (assoc opts :actor (:actor f))))
        found (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))]
    (is (= (money/of "SGD" 999) (:amount amended)))
    (is (= (money/of "SGD" 999) (:amount found)))
    (is (= "Andaman Shipping Sdn Bhd" (:creditor-name found)))
    (is (= :draft (:status found)) "an amendment is not a transition")
    (is (= (:id created) (:id found)) "and not a new instruction either")))

(deftest amending-a-submitted-instruction-returns-it-to-draft
  (testing "PR-014, AC-7. TASK-002 refused this because the approval-invalidation
            behind it did not exist; it does now (ADR-0014 amendment 1)."
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
      (payments/transition! tdb/*pool* (:organisation-id f) (:id created) :submit)
      (let [{:keys [before after]}
            (payments/amend! tdb/*pool* (:organisation-id f) (:id created)
                             {:amount (money/of "SGD" 1)} (assoc opts :actor (:actor f)))]
        (is (= :pending-approval (:status before)))
        (is (= :draft (:status after))))
      (let [found (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))]
        (is (= :draft (:status found)))
        (is (= (money/of "SGD" 1) (:amount found)))))))

(deftest amending-an-approved-instruction-invalidates-every-approval
  (testing "PR-014: an approver agreed to values that are no longer the instruction's"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)
          checker (tdb/insert-actor! tdb/*pool* {:organisation-id (:organisation-id f)
                                                 :display-name "Checker"
                                                 :roles [:approver] :limits {"SGD" 10000000}})]
      (payments/transition! tdb/*pool* (:organisation-id f) (:id created) :submit)
      (tdb/insert-approval! tdb/*pool* {:instruction-id (:id created) :actor-id checker})
      (payments/transition! tdb/*pool* (:organisation-id f) (:id created) :approve)

      (let [{:keys [before after approvals-invalidated]}
            (payments/amend! tdb/*pool* (:organisation-id f) (:id created)
                             {:amount (money/of "SGD" 7)} (assoc opts :actor (:actor f)))]
        (is (= :approved (:status before)))
        (is (= :draft (:status after)))
        (is (= 1 approvals-invalidated)))

      (let [approvals (authz/approvals-for tdb/*pool* (:id created))]
        (is (= 1 (count approvals)) "the decision is invalidated, never deleted")
        (is (some? (:invalidated-at (first approvals))))))))

(deftest amending-a-terminal-instruction-is-refused
  (testing "the lifecycle table decides: `settled` has no `amend` arrow"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
      (doseq [event [:submit :approve :release :settle]]
        (payments/transition! tdb/*pool* (:organisation-id f) (:id created) event))
      (is (= :conflict (error-type #(payments/amend! tdb/*pool* (:organisation-id f)
                                                     (:id created)
                                                     {:amount (money/of "SGD" 1)}
                                                     (assoc opts :actor (:actor f))))))
      (is (= (money/of "SGD" 125000)
             (:amount (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))))
          "and nothing changed"))))

(deftest pr-004-only-the-creator-may-amend
  (let [f (fixture)
        created (payments/create-instruction! tdb/*pool* (candidate f) opts)
        someone-else (tdb/insert-actor! tdb/*pool* {:organisation-id (:organisation-id f)
                                                    :display-name "Someone else"
                                                    :roles [:operator]})]
    (is (= :forbidden (error-type #(payments/amend! tdb/*pool* (:organisation-id f)
                                                    (:id created)
                                                    {:amount (money/of "SGD" 1)}
                                                    (assoc opts :actor {:id someone-else})))))
    (testing "and an amendment with no actor at all is refused rather than allowed"
      (is (= :unauthorised (error-type #(payments/amend! tdb/*pool* (:organisation-id f)
                                                         (:id created)
                                                         {:amount (money/of "SGD" 1)} opts)))))
    (is (= (money/of "SGD" 125000)
           (:amount (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))))
        "and nothing changed")))

(deftest an-amendment-is-checked-against-the-database-too
  (let [a (fixture)
        b (fixture)
        created (payments/create-instruction! tdb/*pool* (candidate a) opts)]
    (is (= :unprocessable
           (error-type #(payments/amend! tdb/*pool* (:organisation-id a) (:id created)
                                         {:debtor-account-id (:account-id b)}
                                         (assoc opts :actor (:actor a)))))
        "amending onto another organisation's account must fail like creating onto one")))

;; ---------------------------------------------------------------------------
;; Reversal
;; ---------------------------------------------------------------------------

(defn- settle!
  "Walk an instruction to `settled` through the lifecycle, one event at a time.

  Written as a walk rather than an `update ... set status = 'settled'` so that
  the fixture cannot reach a state the state machine would not have permitted."
  [f id]
  (doseq [event [:submit :approve :release :settle]]
    (payments/transition! tdb/*pool* (:organisation-id f) id event))
  id)

(deftest a-settled-instruction-can-be-reversed-by-a-new-instruction
  (let [f (fixture)
        original (payments/create-instruction! tdb/*pool* (candidate f) opts)
        _ (settle! f (:id original))
        reversal (payments/create-instruction!
                  tdb/*pool* (candidate f :reverses-id (:id original)) opts)
        found-original (payments/find-instruction tdb/*pool* (:organisation-id f) (:id original))]
    (testing "the reversal is a new instruction pointing back at the original"
      (is (not= (:id original) (:id reversal)))
      (is (= (:id original) (:reverses-id reversal)))
      (is (= :draft (:status reversal))))
    (testing "and the original is untouched — a settled payment is never mutated"
      (is (= :settled (:status found-original)))
      (is (nil? (:reverses-id found-original)))
      (is (= (money/of "SGD" 125000) (:amount found-original))))))

(deftest only-a-settled-instruction-can-be-reversed
  (let [f (fixture)
        draft (payments/create-instruction! tdb/*pool* (candidate f) opts)]
    (is (= :conflict (error-type #(payments/create-instruction!
                                   tdb/*pool* (candidate f :reverses-id (:id draft)) opts))))))

(deftest a-reversal-must-name-an-instruction-in-this-organisation
  (let [a (fixture)
        b (fixture)
        original (payments/create-instruction! tdb/*pool* (candidate a) opts)]
    (settle! a (:id original))
    (is (= :unprocessable (error-type #(payments/create-instruction!
                                        tdb/*pool* (candidate b :reverses-id (:id original))
                                        opts))))
    (is (= :unprocessable (error-type #(payments/create-instruction!
                                        tdb/*pool* (candidate a :reverses-id (random-uuid))
                                        opts))))))

(deftest a-reversal-must-be-in-the-currency-it-reverses
  (let [f (fixture)
        original (payments/create-instruction! tdb/*pool* (candidate f) opts)]
    (settle! f (:id original))
    (is (= :unprocessable
           (error-type #(payments/create-instruction!
                         tdb/*pool*
                         (candidate f :reverses-id (:id original)
                                    :amount (money/of "USD" 100))
                         opts))))))

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(deftest listing-is-scoped-ordered-and-filterable
  (let [a (fixture)
        b (fixture)
        first-id (:id (payments/create-instruction! tdb/*pool* (candidate a) opts))
        second-id (:id (payments/create-instruction! tdb/*pool* (candidate a) opts))
        _ (payments/create-instruction! tdb/*pool* (candidate b) opts)]
    (payments/transition! tdb/*pool* (:organisation-id a) first-id :submit)

    (let [{:keys [instructions truncated?]}
          (payments/list-instructions tdb/*pool* (:organisation-id a) {})]
      (is (= 2 (count instructions)) "another organisation's instructions are not listed")
      (is (= #{first-id second-id} (set (map :id instructions))))
      (is (false? truncated?)))

    (testing "filtered to one lifecycle state"
      (is (= [first-id] (mapv :id (:instructions (payments/list-instructions
                                                  tdb/*pool* (:organisation-id a)
                                                  {:status :pending-approval}))))))

    (testing "a status outside the lifecycle is refused rather than returning nothing"
      (is (= :validation (error-type #(payments/list-instructions
                                       tdb/*pool* (:organisation-id a)
                                       {:status :in-flight})))))))

(deftest listing-reports-the-cap-rather-than-hiding-behind-it
  (is (= 500 payments/row-cap)
      "the same cap and the same reasoning as the ledger's — see ADR-0011"))

(deftest every-lifecycle-state-can-be-stored
  (testing "the check constraint and the state machine agree on the set of statuses"
    (let [f (fixture)]
      (doseq [status state/states]
        (is (= 1 (db/execute! tdb/*pool*
                              ["insert into payment_instruction
                                  (id, organisation_id, debtor_account_id, creditor_name,
                                   creditor_account, amount_minor, currency, value_date,
                                   purpose_code, status, created_by)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               (random-uuid) (:organisation-id f) (:account-id f)
                               "X" "SG-SYNTH-1" 100 "SGD" today "SUPP" (name status)
                               (random-uuid)]))
            (str (name status) " must be storable"))))))
