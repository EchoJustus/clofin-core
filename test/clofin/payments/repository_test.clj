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
            [clofin.screening.subject :as screening-subject]
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

(defn- screen!
  "Core's screening decision over the instruction as it stands, so a direct
  `transition!` with `:submit` finds one — since TASK-017 the repository's own
  gate refuses `:submit` without it (C-07). Called **only** where a test means
  to submit a draft; `ac-17-11-…` below is what submitting without one does."
  [f id]
  (tdb/record-core-screening! tdb/*pool* (:organisation-id f) id (:id (:actor f))))

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
        _ (screen! f (:id created))
        moved (:after (payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                            :submit {:actor (:actor f)}))]
    (is (= :pending-approval (:status moved)))
    (is (= :pending-approval (:status (payments/find-instruction
                                       tdb/*pool* (:organisation-id f) (:id created))))
        "and it is the stored row that changed, not just the value returned")))

(deftest a-transition-the-lifecycle-refuses-changes-nothing
  (let [f (fixture)
        created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
    (screen! f (:id created))
    (payments/transition! tdb/*pool* (:organisation-id f) (:id created) :submit {:actor (:actor f)})
    (is (= :conflict (error-type #(payments/transition! tdb/*pool* (:organisation-id f)
                                                        (:id created) :submit
                                                        {:actor (:actor f)}))))
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
          _ (screen! f (:id created))
          start (CountDownLatch. 1)
          done (CountDownLatch. 2)
          outcomes (atom [])
          run (fn []
                (future
                  (.await start)
                  (swap! outcomes conj
                         (try
                           (payments/transition! tdb/*pool* (:organisation-id f)
                                                 (:id created) :submit {:actor (:actor f)})
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

(deftest f-001-only-the-creator-may-submit
  (testing "audit finding F-001. Enforced here rather than in the handler: a
            provenance rule that lives at the HTTP boundary stops existing for
            every caller that does not come through it — and this function is
            called directly by `approval-service` and by test fixtures."
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)
          someone-else (tdb/insert-actor! tdb/*pool* {:organisation-id (:organisation-id f)
                                                      :display-name "Second operator"
                                                      :roles [:operator]})]
      (is (= :forbidden
             (error-type #(payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                :submit {:actor {:id someone-else}}))))
      (is (= :draft (:status (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))))
          "and the instruction did not move"))))

(deftest f-001-submitting-with-no-actor-fails-closed
  (testing "an operation restricted to the creator, with nobody to compare
            against, refuses rather than permitting"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
      (is (= :unauthorised
             (error-type #(payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                :submit))))
      (is (= :draft (:status (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))))))))

(deftest f-001-provenance-is-checked-before-the-lifecycle
  (testing "mirroring `amend!`: a non-creator is told it is not their instruction
            rather than being handed its state and permitted events"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)
          someone-else (tdb/insert-actor! tdb/*pool* {:organisation-id (:organisation-id f)
                                                      :display-name "Second operator"
                                                      :roles [:operator]})]
      (screen! f (:id created))
      (payments/transition! tdb/*pool* (:organisation-id f) (:id created) :submit {:actor (:actor f)})
      ;; Already `pending-approval`, so the lifecycle would also refuse.
      (is (= :forbidden
             (error-type #(payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                :submit {:actor {:id someone-else}})))
          "403 wins over 409 — no grant makes a non-creator the creator"))))

(deftest f-001-a-non-creator-may-still-cancel
  (testing "the rule is on the event, not on the caller: `:cancel` is not
            creator-only, so a controller can stop a payment it did not raise"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)
          controller (tdb/insert-actor! tdb/*pool* {:organisation-id (:organisation-id f)
                                                    :display-name "Controller"
                                                    :roles [:controller]})]
      (is (= :cancelled
             (:status (:after (payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                    :cancel {:actor {:id controller}}))))))))

(deftest amending-a-submitted-instruction-returns-it-to-draft
  (testing "PR-014, AC-7. TASK-002 refused this because the approval-invalidation
            behind it did not exist; it does now (ADR-0014 amendment 1)."
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
      (screen! f (:id created))
      (payments/transition! tdb/*pool* (:organisation-id f) (:id created) :submit {:actor (:actor f)})
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
      (screen! f (:id created))
      (payments/transition! tdb/*pool* (:organisation-id f) (:id created) :submit {:actor (:actor f)})
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
      (screen! f (:id created))
      (doseq [event [:submit :approve :release :settle]]
        (payments/transition! tdb/*pool* (:organisation-id f) (:id created) event {:actor (:actor f)}))
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
  (screen! f id)
  (doseq [event [:submit :approve :release :settle]]
    ;; `:actor` matters only for `:submit`, which is creator-only (F-001); the
    ;; fixture's actor is the creator, so the walk is one an operator could
    ;; actually have performed rather than one only a fixture can reach.
    (payments/transition! tdb/*pool* (:organisation-id f) id event {:actor (:actor f)}))
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
    (screen! a first-id)
    (payments/transition! tdb/*pool* (:organisation-id a) first-id :submit {:actor (:actor a)})

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
  ;; This proves the schema accepts every state the code knows. It does **not**
  ;; prove the schema accepts nothing else — inserting nine known values says
  ;; nothing about a tenth literal in the constraint — and it was described as
  ;; the agreement guard until audit finding **A-014**. `payment_status_known`
  ;; is compared with `state/states` for set equality, in both directions, from
  ;; the live catalogue in `clofin.db.vocabulary-test`. Both facts are worth
  ;; having: a value the constraint would refuse on insert is a `500` in
  ;; production, and this is the test that would catch it in the act.
  (testing "every status the state machine can reach is one the column accepts"
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

;; ---------------------------------------------------------------------------
;; TASK-018 — `client_reference` and `creditor_country` (migration `0014`)
;; ---------------------------------------------------------------------------

(defn- raw-insert!
  "Insert an instruction row with the application bypassed entirely."
  [f & {:keys [client-reference creditor-country]}]
  (let [id (random-uuid)]
    (db/execute! tdb/*pool*
                 ["insert into payment_instruction
                     (id, organisation_id, debtor_account_id, creditor_name,
                      creditor_account, amount_minor, currency, value_date,
                      purpose_code, status, created_by, client_reference, creditor_country)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                  id (:organisation-id f) (:account-id f)
                  "X" "SG-SYNTH-1" 100 "SGD" today "SUPP" "draft" (:maker f)
                  client-reference creditor-country])
    id))

(defn- refused-by
  "The constraint or trigger message that refused `f`, or nil if nothing did."
  [f]
  (when-let [t (caught f)]
    (or (:constraint (db/violation t)) (ex-message t))))

(deftest ac-18-0-the-schema-refuses-every-bad-shape-with-the-application-bypassed
  (let [f (fixture)
        with-ref (raw-insert! f :client-reference "agent-ref-0001" :creditor-country "SG")
        without (raw-insert! f)]
    (testing "both documented shapes insert: both members, and neither"
      (is (= {:client-reference "agent-ref-0001" :creditor-country "SG"}
             (select-keys (payments/find-instruction tdb/*pool* (:organisation-id f) with-ref)
                          [:client-reference :creditor-country])))
      (is (= {:client-reference nil :creditor-country nil}
             (select-keys (payments/find-instruction tdb/*pool* (:organisation-id f) without)
                          [:client-reference :creditor-country]))))
    (testing "the 128-character upper bound inserts"
      (is (uuid? (raw-insert! f :client-reference (apply str "!~" (repeat 126 "x"))))))
    (testing "the pre-flight's refusals, re-run on the migrated test database"
      (is (= "payment_client_reference_shape"
             (refused-by #(raw-insert! f :client-reference "has space"))))
      (is (= "payment_client_reference_shape"
             (refused-by #(raw-insert! f :client-reference (apply str (repeat 129 "x"))))))
      (is (= "payment_client_reference_shape"
             (refused-by #(raw-insert! f :client-reference ""))))
      (is (= "payment_client_reference_shape"
             (refused-by #(raw-insert! f :client-reference "réf-1"))))
      (is (= "payment_creditor_country_shape"
             (refused-by #(raw-insert! f :creditor-country "sg"))))
      (is (= "payment_creditor_country_shape"
             (refused-by #(db/execute! tdb/*pool* ["update payment_instruction set creditor_country = 'S1' where id = ?" without]))))
      (is (= "payment_instruction_client_reference_key"
             (refused-by #(raw-insert! f :client-reference "agent-ref-0001")))
          "the same reference twice in one organisation"))
    (testing "the same reference in another organisation is a different reference"
      (is (uuid? (raw-insert! (fixture) :client-reference "agent-ref-0001"))))))

(deftest ac-18-7-the-raw-update-of-a-reference-is-refused-by-the-trigger
  (let [f (fixture)
        with-ref (raw-insert! f :client-reference "agent-ref-0001")
        without (raw-insert! f)
        refusal "payment_instruction.client_reference is set when the instruction is created and never changes"]
    (doseq [[label sql id] [["value -> value" "update payment_instruction set client_reference = 'agent-ref-0002' where id = ?" with-ref]
                            ["value -> null" "update payment_instruction set client_reference = null where id = ?" with-ref]
                            ["null -> value" "update payment_instruction set client_reference = 'agent-ref-0003' where id = ?" without]]]
      (let [message (refused-by #(db/execute! tdb/*pool* [sql id]))]
        (is (and message (re-find (re-pattern (java.util.regex.Pattern/quote refusal)) message))
            (str label " must be refused by the trigger, got " (pr-str message)))))
    (testing "and nothing moved"
      (is (= "agent-ref-0001" (:client-reference (payments/find-instruction tdb/*pool* (:organisation-id f) with-ref))))
      (is (nil? (:client-reference (payments/find-instruction tdb/*pool* (:organisation-id f) without)))))
    (testing "an update that leaves the reference alone is not refused — the trigger
              is narrower than the row, as 0013's is"
      (db/execute! tdb/*pool* ["update payment_instruction set creditor_country = 'GB' where id = ?" with-ref])
      (is (= "GB" (:creditor-country (payments/find-instruction tdb/*pool* (:organisation-id f) with-ref)))))))

(deftest ac-18-2-both-members-round-trip-through-the-repository
  (let [f (fixture)
        created (payments/create-instruction! tdb/*pool*
                                              (candidate f :client-reference "agent-ref-0001"
                                                         :creditor-country "SG")
                                              opts)
        found (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))]
    (is (= (dissoc created :created-at) (dissoc found :created-at)))
    (is (= "agent-ref-0001" (:client-reference found)))
    (is (= "SG" (:creditor-country found)))
    (is (= (:id created) (:id (payments/find-by-client-reference tdb/*pool* (:organisation-id f) "agent-ref-0001"))))
    (is (nil? (payments/find-by-client-reference tdb/*pool* (:organisation-id (fixture)) "agent-ref-0001"))
        "scoped to the organisation")
    (testing "amend! writes the country and leaves the reference where it was"
      (let [{:keys [after]} (payments/amend! tdb/*pool* (:organisation-id f) (:id created)
                                             {:creditor-country "GB"} {:today today :actor (:actor f)})
            reread (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))]
        (is (= "GB" (:creditor-country after) (:creditor-country reread)))
        (is (= "agent-ref-0001" (:client-reference reread)))))))

(deftest ac-18-4-the-reference-rules-hold-at-the-repository-seam
  (let [f (fixture)
        original (payments/create-instruction! tdb/*pool* (candidate f :client-reference "agent-ref-0001") opts)
        count-rows #(:count (db/query-one tdb/*pool* ["select count(*) as count from payment_instruction"]))]
    (testing "identical content: client-reference-exists, naming the instruction"
      (let [data (ex-data (caught #(payments/create-instruction!
                                    tdb/*pool* (candidate f :client-reference "agent-ref-0001") opts)))]
        (is (= :conflict (:clofin/error data)))
        (is (= "client-reference-exists" (:reason data)))
        (is (= (str (:id original)) (:instructionId data)))))
    (testing "different content: client-reference-conflict"
      (let [data (ex-data (caught #(payments/create-instruction!
                                    tdb/*pool* (candidate f :client-reference "agent-ref-0001"
                                                          :amount (money/of "SGD" 999))
                                    opts)))]
        (is (= "client-reference-conflict" (:reason data)))
        (is (= (str (:id original)) (:instructionId data)))))
    (is (= 1 (count-rows)) "nothing was created by either")
    (testing "the reference is checked before the debtor account — a frozen account
              does not turn a repeated reference into a 422"
      (db/execute! tdb/*pool* ["update ledger_account set status = 'frozen' where id = ?" (:account-id f)])
      (is (= "client-reference-exists"
             (:reason (ex-data (caught #(payments/create-instruction!
                                         tdb/*pool* (candidate f :client-reference "agent-ref-0001") opts)))))))))

;; ---------------------------------------------------------------------------
;; TASK-017 — C-07's gate is the repository's own (AC-17-11)
;; ---------------------------------------------------------------------------

(defn- conflict-reason
  "The `[error-type reason]` `f` was refused with, or nil."
  [f]
  (when-let [t (caught f)]
    [(:clofin/error (ex-data t)) (:reason (ex-data t))]))

(deftest ac-17-11-submit-without-a-screening-decision-is-refused-by-the-repository-itself
  (testing "a direct call of `transition!` with `:submit` — no service, no
            handler — on a draft core has not screened is refused by the gate
            under the row lock: `screening-required`"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
      (is (= [:conflict "screening-required"]
             (conflict-reason #(payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                     :submit {:actor (:actor f)}))))
      (is (= :draft (:status (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))))
          "and nothing moved")))
  (testing "with core's clear result over the current digest → permitted"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
      (screen! f (:id created))
      (is (= :pending-approval
             (:status (:after (payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                    :submit {:actor (:actor f)})))))))
  (testing "with core's clear result over a **stale** digest — screened, then
            amended — → refused: the decision was about other content"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
      (screen! f (:id created))
      (payments/amend! tdb/*pool* (:organisation-id f) (:id created)
                       {:creditor-name "Andaman Shipping Sdn Bhd"} (assoc opts :actor (:actor f)))
      (is (= [:conflict "screening-required"]
             (conflict-reason #(payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                     :submit {:actor (:actor f)}))))))
  (testing "with core's hit, no disposition → refused `screening-hit`"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool*
                                                (candidate f :creditor-name "Blocked Counterparty Ltd") opts)]
      (screen! f (:id created))
      (is (= [:conflict "screening-hit"]
             (conflict-reason #(payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                     :submit {:actor (:actor f)}))))))
  (testing "a client's accepted clear, alone, does not open the gate — the gate
            reads core's results only"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)
          found (payments/find-instruction tdb/*pool* (:organisation-id f) (:id created))]
      (db/execute! tdb/*pool*
                   ["insert into screening_result
                       (id, organisation_id, instruction_id, list_version, origin, outcome,
                        core_outcome, agrees, instruction_digest, disposition, recorded_by)
                     values (?, ?, ?, 'synthetic-2026-10-v1', 'client', 'clear', 'clear', true, ?,
                             'accepted', ?)"
                    (random-uuid) (:organisation-id f) (:id created)
                    (screening-subject/digest found) (:id (:actor f))])
      (is (= [:conflict "screening-required"]
             (conflict-reason #(payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                     :submit {:actor (:actor f)}))))))
  ;; Last, because it leaves no list accepted for anything after it.
  (testing "with the result's list retired since → refused `screening-required`"
    (let [f (fixture)
          created (payments/create-instruction! tdb/*pool* (candidate f) opts)]
      (screen! f (:id created))
      (db/execute! tdb/*pool* ["update screening_list set retired_at = now() where retired_at is null"])
      (is (= [:conflict "screening-required"]
             (conflict-reason #(payments/transition! tdb/*pool* (:organisation-id f) (:id created)
                                                     :submit {:actor (:actor f)})))))))
