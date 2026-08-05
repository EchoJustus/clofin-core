(ns clofin.settlement.repository-test
  "Settlement persistence against real PostgreSQL, and the two guards that must
  not be compromised.

  **AC-7** is asserted with raw SQL, application bypassed — the same posture
  `clofin.db.audit-constraints-test` takes, and for the same reason: a guard
  that only holds when the application is the one writing is a guard a fix-up
  script, a defect or a maintenance session walks straight past. The claim is
  that an instruction is **never** in two settlement memberships, and the thing
  that makes it true is a unique index, not this code.

  That claim used to be narrower, and audit finding **F-007** is why it is not.
  Migration `0009`'s partial index excepted `returned`, advertising a
  re-batching permission no public workflow could reach; the auditor's raw
  second membership for a returned instruction **committed**. That exact insert
  is now a refusal test — which is the whole of standing lesson **L-10**: a
  schema path is not a product path, and the two must agree.

  **AC-5**'s repository half is here too: a duplicate scheme response is refused
  by the replay key, and an item resolves exactly once however many callers try.
  The API half is in `clofin.api.settlement-api-test`; both are asserted,
  because a duplicate that the handler happens to filter and the storage would
  have accepted is a guarantee that lasts until the next handler."
  (:require [clofin.db.core :as db]
            [clofin.settlement.repository :as settlement]
            [clofin.test-db :as tdb]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time LocalDate]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private value-date (LocalDate/parse "2026-12-01"))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- insert-instruction!
  [{:keys [org account actor status]} & {:keys [creditor-account]
                                         :or {creditor-account "SG-SYNTH-88012340"}}]
  (let [id (random-uuid)]
    (db/execute! tdb/*pool*
                 ["insert into payment_instruction
                     (id, organisation_id, debtor_account_id, creditor_name, creditor_account,
                      amount_minor, currency, value_date, purpose_code, status, created_by)
                   values (?, ?, ?, 'Pacific Rim Logistics Pte Ltd', ?,
                           125000, 'SGD', ?, 'SUPP', ?, ?)"
                  id org account creditor-account value-date (or status "approved") actor])
    id))

(defn- setup []
  (let [org (tdb/insert-organisation! tdb/*pool* {:id (random-uuid)})
        actor (tdb/insert-actor! tdb/*pool* {:organisation-id org :display-name "Controller"
                                             :roles [:controller]})
        account (tdb/insert-account! tdb/*pool* {:id (random-uuid) :organisation-id org
                                                 :code "1100-CLIENT-FUNDS"})]
    {:org org :actor actor :account account}))

(defn- a-batch!
  [{:keys [org actor]} & {:keys [scheme] :or {scheme "SIM-RTGS"}}]
  (db/with-transaction [tx tdb/*pool*]
    (settlement/insert-batch! tx {:id (random-uuid) :organisation-id org
                                  :scheme scheme :currency "SGD"
                                  :value-date value-date :created-by actor})))

(defn- item-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from settlement_batch_item"])))

(defn- response-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from scheme_response"])))

;; ---------------------------------------------------------------------------
;; AC-7 — no instruction is ever in two live batches. Raw SQL.
;; ---------------------------------------------------------------------------

(deftest ac-7-the-database-itself-refuses-a-second-membership-whatever-the-outcome
  (testing "asserted with raw SQL, application bypassed: the guard is a unique index,
            so it binds a fix-up script and a defect as well as a handler"
    (let [f (setup)
          batch-a (a-batch! f)
          batch-b (a-batch! f :scheme "SIM-ACH")
          instruction (insert-instruction! f)
          insert! (fn [batch]
                    (db/execute! tdb/*pool*
                                 ["insert into settlement_batch_item (batch_id, instruction_id)
                                   values (?, ?)"
                                  (:id batch) instruction]))
          resolve-to! (fn [outcome reason]
                        (db/execute! tdb/*pool*
                                     ["update settlement_batch_item
                                          set outcome = ?, outcome_reason = ?, resolved_at = now()
                                        where batch_id = ?"
                                      outcome reason (:id batch-a)]))]
      (insert! batch-a)

      (testing "pending — the scheme has not answered, so the money's fate is open"
        (is (thrown-with-msg? Exception #"settlement_item_instruction_key" (insert! batch-b))))

      (testing "settled — the money is gone; batching it again would send it twice"
        (resolve-to! "settled" nil)
        (is (thrown-with-msg? Exception #"settlement_item_instruction_key" (insert! batch-b))))

      (testing "**timed out** — the outcome is UNKNOWN, and this is the case the module exists for"
        (resolve-to! "timed-out" nil)
        (is (thrown-with-msg? Exception #"settlement_item_instruction_key" (insert! batch-b))
            "treating unknown as failed and re-batching is how a payment is made twice"))

      (testing "**returned** — the audit's own reproduction, now a refusal (F-007)"
        ;; Verification log C-01 ran exactly this insert against migration
        ;; 0009's partial index and it COMMITTED, leaving membership count 2
        ;; while the public workflow answered 422 — the schema advertising a
        ;; retry the product could not perform. The ruling withdrew the
        ;; permission: a returned payment is terminal, and the retry is a new
        ;; instruction.
        (resolve-to! "returned" "SIM-RETURN")
        (is (thrown-with-msg? Exception #"settlement_item_instruction_key" (insert! batch-b))
            "a returned instruction is terminal; a retry is a NEW instruction")
        (is (= 1 (item-count)) "and nothing was written on the way to being refused"))

      (testing "a different instruction is of course unaffected"
        (is (= 1 (db/execute! tdb/*pool*
                              ["insert into settlement_batch_item (batch_id, instruction_id)
                                values (?, ?)"
                               (:id batch-b) (insert-instruction! f)])))
        (is (= 2 (item-count)))))))

(deftest ac-7-the-retry-for-a-returned-payment-is-a-new-instruction
  (testing "the doctrine `settled` already follows, now stated for `returned` too:
            the *instruction* is finished, and a fresh one batches normally"
    (let [f (setup)
          returned (insert-instruction! f)
          batch-a (a-batch! f)]
      (db/with-transaction [tx tdb/*pool*]
        (settlement/add-items! tx (:id batch-a) [returned]))
      (db/execute! tdb/*pool* ["update settlement_batch_item
                                  set outcome = 'returned', outcome_reason = 'SIM-RETURN',
                                      resolved_at = now()
                                where batch_id = ?" (:id batch-a)])
      (let [batch-b (a-batch! f :scheme "SIM-ACH")
            retry   (insert-instruction! f :creditor-account "SG-SYNTH-88012341")]
        (db/with-transaction [tx tdb/*pool*]
          (settlement/add-items! tx (:id batch-b) [retry]))
        (is (= 2 (item-count)) "the new instruction batches; the returned one stays finished")))))

(deftest a-returned-item-must-carry-a-reason-in-the-schema
  (testing "an exception queue whose entries do not say why is an exception queue nobody can work"
    (let [f (setup)
          batch (a-batch! f)
          instruction (insert-instruction! f)]
      (db/execute! tdb/*pool* ["insert into settlement_batch_item (batch_id, instruction_id)
                                values (?, ?)" (:id batch) instruction])
      (is (thrown-with-msg?
           Exception #"settlement_return_needs_reason"
           (db/execute! tdb/*pool* ["update settlement_batch_item set outcome = 'returned'
                                     where batch_id = ?" (:id batch)]))))))

(deftest the-scheme-name-cannot-be-a-real-one
  (testing "the SIM- prefix is enforced by the database, not by a convention in a handler"
    (let [{:keys [org actor]} (setup)]
      (is (thrown-with-msg?
           Exception #"settlement_scheme_known"
           (db/execute! tdb/*pool*
                        ["insert into settlement_batch
                            (id, organisation_id, scheme, currency, value_date, created_by)
                          values (?, ?, 'SWIFT', 'SGD', ?, ?)"
                         (random-uuid) org value-date actor]))))))

;; ---------------------------------------------------------------------------
;; AC-5 — a duplicate response does no work
;; ---------------------------------------------------------------------------

(defn- receipt!
  "One receipt, with the fields migration `0010` requires. Defaults to the
  ordinary case so a test naming only what it cares about still writes a
  well-formed row."
  [tx batch instruction & {:keys [kind reference disposition disposition-reason
                                 request-digest outcome reason]
                           :or {kind "settled" reference "SIM-STL-1"
                                disposition "applied" request-digest "v1:aaa"}}]
  (settlement/record-response!
   tx {:id (random-uuid) :batch-id (:id batch) :instruction-id instruction
       :kind kind :reference reference
       :disposition disposition :disposition-reason disposition-reason
       :request-digest request-digest :outcome outcome :reason reason}))

(deftest ac-5-a-duplicate-scheme-response-is-refused-by-the-replay-key
  (let [f (setup)
        batch (a-batch! f)
        instruction (insert-instruction! f)
        deliver! (fn [] (db/with-transaction [tx tdb/*pool*]
                          (receipt! tx batch instruction :outcome "settled")))]
    (is (some? (deliver!)) "the first delivery is recorded")
    (is (nil? (deliver!)) "the second is recognised as a repeat and returns nil, not an error")
    (is (= 1 (response-count)) "the first row stays; the duplicate is discarded")

    (testing "and the original can still be produced as evidence of when it arrived"
      (let [original (settlement/find-response tdb/*pool*
                                               {:batch-id (:id batch)
                                                :instruction-id instruction
                                                :kind "settled" :reference "SIM-STL-1"})]
        (is (some? original))
        (is (some? (:received-at original)))
        (testing "carrying everything a replay has to reproduce (F-009)"
          (is (= "applied" (:disposition original)))
          (is (= "settled" (:outcome original)))
          (is (= "v1:aaa" (:request-digest original))))))))

(deftest f-008-a-refused-arrival-is-stored-as-a-receipt-of-its-own
  (testing "the row the audit found missing: a response CloFin could not act on,
            kept with a machine-readable statement that it did no work"
    (let [f (setup)
          batch (a-batch! f)
          instruction (insert-instruction! f)]
      (db/with-transaction [tx tdb/*pool*]
        (receipt! tx batch instruction
                  :kind "timeout-resolution" :reference "SIM-TMO-1"
                  :disposition "refused" :disposition-reason "item-not-timed-out"
                  :request-digest "v1:bbb"))
      (let [stored (first (settlement/responses-for tdb/*pool* (:id batch)))]
        (is (= "refused" (:disposition stored)))
        (is (= "item-not-timed-out" (:disposition-reason stored)))
        (is (nil? (:outcome stored))
            "a refused arrival resolved nothing, so it claims no outcome in the column
             an investigation reads as fact")))))

(deftest a-receipt-must-name-a-disposition-the-vocabulary-knows
  (testing "default deny reaching the receipt table, the same posture
            `clofin.audit/event` takes with its action vocabulary — a disposition
            the replay path cannot interpret would silently become a different answer"
    (let [f (setup)
          batch (a-batch! f)
          instruction (insert-instruction! f)]
      (doseq [bad [nil "" "ignored" "applied "]]
        (is (thrown? Exception
                     (db/with-transaction [tx tdb/*pool*]
                       (receipt! tx batch instruction :disposition bad)))
            (str "disposition " (pr-str bad) " must not be recordable"))))))

(defn- violated-constraint
  "The constraint a receipt insert tripped, as the repository reports it.

  `record-response!` runs inside a savepoint and translates a violation that is
  not the replay key into a domain error carrying the constraint name in its
  data rather than in its message — so the assertion reads the data."
  [f]
  (:constraint (ex-data (try (f) nil (catch clojure.lang.ExceptionInfo e e)))))

(deftest a-refusal-without-a-reason-is-refused-by-the-schema
  (testing "and a non-refusal carrying one is too — two columns that can disagree"
    (let [f (setup)
          batch (a-batch! f)
          instruction (insert-instruction! f)]
      (is (= "scheme_response_refusal_needs_reason"
             (violated-constraint #(db/with-transaction [tx tdb/*pool*]
                                     (receipt! tx batch instruction :disposition "refused")))))
      (is (= "scheme_response_refusal_needs_reason"
             (violated-constraint
              #(db/with-transaction [tx tdb/*pool*]
                 (receipt! tx batch instruction
                           :disposition-reason "item-not-timed-out"))))))))

(deftest two-identical-batch-level-acks-collide-rather-than-coexisting
  (testing "nulls not distinct: without it a null instruction_id makes every ack unique,
            and a resubmission would look like a new acknowledgement"
    (let [f (setup)
          batch (a-batch! f)
          ack! (fn [] (db/with-transaction [tx tdb/*pool*]
                        (receipt! tx batch nil :kind "ack" :reference "SIM-ACK-1"
                                  :disposition "acknowledged")))]
      (is (some? (ack!)))
      (is (nil? (ack!)))
      (is (= 1 (response-count))))))

(deftest a-different-reference-is-a-different-response
  (testing "the replay key is (batch, instruction, kind, reference) — two genuinely different
            answers must both be recorded, or evidence is lost"
    (let [f (setup)
          batch (a-batch! f)
          instruction (insert-instruction! f)]
      (db/with-transaction [tx tdb/*pool*]
        (is (some? (receipt! tx batch instruction :reference "A" :outcome "settled")))
        (is (some? (receipt! tx batch instruction :reference "B" :outcome "settled"))))
      (is (= 2 (response-count))))))

(deftest f-009-the-replay-key-alone-cannot-tell-two-messages-apart
  (testing "which is the finding, stated at the level it lives at: the key admits
            one row for two contradictory timeout resolutions, so the digest — not
            the key — is what decides whether an arrival is the same message"
    (let [f (setup)
          batch (a-batch! f)
          instruction (insert-instruction! f)]
      (db/with-transaction [tx tdb/*pool*]
        (is (some? (receipt! tx batch instruction
                             :kind "timeout-resolution" :reference "SIM-TMO-1"
                             :outcome "settled" :request-digest "v1:settled-digest")))
        (is (nil? (receipt! tx batch instruction
                            :kind "timeout-resolution" :reference "SIM-TMO-1"
                            :outcome "returned" :reason "changed my mind"
                            :request-digest "v1:returned-digest"))
            "the key is taken, whatever this message says"))
      (let [stored (settlement/find-response tdb/*pool*
                                             {:batch-id (:id batch) :instruction-id instruction
                                              :kind "timeout-resolution"
                                              :reference "SIM-TMO-1"})]
        (is (= "v1:settled-digest" (:request-digest stored))
            "so the stored digest is what the service compares against, and a
             mismatch is a 409 rather than a replay — asserted end to end in
             clofin.api.settlement-api-test")
        (is (= "settled" (:outcome stored)))))))

(deftest a-response-cannot-claim-an-outcome-no-scheme-can-report
  (testing "`timed-out` is a fact about CloFin having stopped waiting; a scheme
            reporting it back would be the system quoting itself as evidence"
    (let [f (setup)
          batch (a-batch! f)
          instruction (insert-instruction! f)]
      (is (= "scheme_response_outcome_known"
             (violated-constraint #(db/with-transaction [tx tdb/*pool*]
                                     (receipt! tx batch instruction :outcome "timed-out"))))))))

;; ---------------------------------------------------------------------------
;; Resolving exactly once
;; ---------------------------------------------------------------------------

(defn- with-item! [f]
  (let [batch (a-batch! f)
        instruction (insert-instruction! f)]
    (db/with-transaction [tx tdb/*pool*]
      (settlement/add-items! tx (:id batch) [instruction]))
    {:batch batch :instruction instruction}))

(deftest an-item-resolves-exactly-once
  (let [f (setup)
        {:keys [batch instruction]} (with-item! f)]
    (db/with-transaction [tx tdb/*pool*]
      (is (some? (settlement/resolve-item! tx (:id batch) instruction "settled" nil))
          "the first caller resolves it")
      (is (nil? (settlement/resolve-item! tx (:id batch) instruction "returned" "late"))
          "the second gets nil — `where outcome is null` is in the statement, not in a
           preceding read that a concurrent caller could race"))
    (is (= "settled" (:outcome (first (settlement/items-for tdb/*pool* (:id batch))))))))

(deftest only-a-timed-out-item-can-be-resolved-by-a-timeout-resolution
  (let [f (setup)
        {:keys [batch instruction]} (with-item! f)]
    (db/with-transaction [tx tdb/*pool*]
      (is (nil? (settlement/resolve-timed-out-item! tx (:id batch) instruction "settled" nil))
          "a pending item is not timed out; there is nothing to resolve")
      (settlement/sweep-timeouts! tx (:id batch) 0)
      (is (some? (settlement/resolve-timed-out-item! tx (:id batch) instruction "settled" nil)))
      (is (nil? (settlement/resolve-timed-out-item! tx (:id batch) instruction "returned" "again"))
          "and a second resolution is refused (AC-6)"))
    (is (= "settled" (:outcome (first (settlement/items-for tdb/*pool* (:id batch))))))))

(deftest a-timeout-resolution-must-resolve-to-a-real-outcome
  (let [f (setup)
        {:keys [batch instruction]} (with-item! f)]
    (db/with-transaction [tx tdb/*pool*]
      (is (thrown? Exception
                   (settlement/resolve-timed-out-item! tx (:id batch) instruction "timed-out" nil))
          "resolving a timeout to another timeout is not a resolution"))))

;; ---------------------------------------------------------------------------
;; The sweep
;; ---------------------------------------------------------------------------

(deftest the-sweep-honours-its-horizon
  (let [f (setup)
        {:keys [batch]} (with-item! f)]
    (db/with-transaction [tx tdb/*pool*]
      (is (empty? (settlement/sweep-timeouts! tx (:id batch) 3600))
          "a batch created a moment ago is not overdue")
      (is (= 1 (count (settlement/sweep-timeouts! tx (:id batch) 0)))
          "with a zero horizon it is")
      (is (empty? (settlement/sweep-timeouts! tx (:id batch) 0))
          "and a sweep run twice marks nothing the second time — operators run things twice"))))

(deftest the-sweep-leaves-resolved-items-alone
  (let [f (setup)
        {:keys [batch instruction]} (with-item! f)]
    (db/with-transaction [tx tdb/*pool*]
      (settlement/resolve-item! tx (:id batch) instruction "settled" nil)
      (is (empty? (settlement/sweep-timeouts! tx (:id batch) 0))
          "a settled item is not something CloFin is still waiting for"))
    (is (= "settled" (:outcome (first (settlement/items-for tdb/*pool* (:id batch))))))))

;; ---------------------------------------------------------------------------
;; Membership
;; ---------------------------------------------------------------------------

(deftest adding-an-instruction-already-in-a-batch-is-a-named-conflict
  (testing "the index violation is translated, because `this payment is already in a batch`
            is something a caller can act on and a 500 is not"
    (let [f (setup)
          {:keys [batch instruction]} (with-item! f)
          other (a-batch! f :scheme "SIM-ACH")
          refused (fn [] (try (db/with-transaction [tx tdb/*pool*]
                                (settlement/add-items! tx (:id other) [instruction]))
                              nil
                              (catch clojure.lang.ExceptionInfo e e)))]
      (is (= :conflict (:clofin/error (ex-data (refused)))))
      (is (= 1 (item-count)) "and nothing was written on the way to being refused")

      (testing "and the reason names the terminal state and the correction (F-007)"
        ;; A refusal an operator cannot act on becomes a request to disable the
        ;; check. "Already in a batch" leaves them looking for the batch; the
        ;; answer they need is that the payment is finished and the retry is a
        ;; new instruction.
        (db/execute! tdb/*pool* ["update settlement_batch_item
                                    set outcome = 'returned', outcome_reason = 'SIM-RETURN',
                                        resolved_at = now()
                                  where batch_id = ?" (:id batch)])
        (let [t (refused)]
          (is (re-find #"returned payment is terminal" (ex-message t)))
          (is (re-find #"new payment instruction" (ex-message t)))
          (is (= "raise-a-new-instruction" (:retry (ex-data t))))
          (is (= settlement/membership-index (:constraint (ex-data t)))))))))

(deftest locking-instructions-names-the-ones-that-do-not-exist
  (let [{:keys [org] :as f} (setup)
        known (insert-instruction! f)
        unknown (random-uuid)]
    (db/with-transaction [tx tdb/*pool*]
      (is (= 1 (count (settlement/lock-instructions! tx org [known]))))
      (let [data (try (settlement/lock-instructions! tx org [known unknown]) nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :unprocessable (:clofin/error data)))
        (is (= [(str unknown)] (:instruction-ids data)))))))

(deftest a-batch-is-scoped-to-its-organisation
  (let [f (setup)
        batch (a-batch! f)
        other (tdb/insert-organisation! tdb/*pool* {:id (random-uuid) :short-name "other"})]
    (is (some? (settlement/find-batch tdb/*pool* (:org f) (:id batch))))
    (is (nil? (settlement/find-batch tdb/*pool* other (:id batch)))
        "an unscoped read is how one tenant sees another's settlement activity")))
