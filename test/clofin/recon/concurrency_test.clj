(ns clofin.recon.concurrency-test
  "Reconciliation under two connections, where the outcome depends on timing.

  Ordinary functional tests stay green after a lock is removed or an
  arbitration branch takes a shortcut, because nothing in them ever loses a
  race. These force the interleaving instead — one transaction holds a row or a
  replay key while the other runs into it — which is the only shape that can
  fail when the serialisation is wrong (standing lesson **L-8**; the harness is
  `clofin.ledger.repository-test`'s, reused rather than reinvented).

  Three findings of the `ref-2` release audit live here:

  - **2C-002** (blocking, lesson **L-18**) — both receipt-collision branches
    replayed the winner's receipt without comparing digests, so a document
    nobody processed was acknowledged as an exact replay of a different one.
    `ac-1-*` is the four-cell matrix {identical, different} x {applied,
    refused}.
  - **2B-004** (lesson **L-8**) — reconciliation's locking had no two-session
    proof at all. `ac-8-*` are the two the finding names: assignment against
    resolution on one break, and two concurrent decisions on one adjustment.

  Every walk here calls the fully-wrapped public handler, so what is under test
  is the behaviour a caller meets rather than a service function called
  directly."
  (:require [clofin.db.core :as db]
            [clofin.money :as money]
            [clofin.recon.repository :as recon]
            [clofin.recon.service :as service]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time Instant LocalDate ZoneOffset]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

(def ^:private value-date (str (.plusDays (LocalDate/now ZoneOffset/UTC) 7)))

;; ---------------------------------------------------------------------------
;; Calling the API — from a thread, so the pool is passed rather than bound
;; ---------------------------------------------------------------------------

(defn- call
  "One public request. `pool` is explicit because `tdb/*pool*` is a dynamic
  binding and dynamic bindings do not cross threads."
  [pool method uri & {:keys [body query actor idempotency-key]}]
  (let [handler (system/handler {:config {:environment :test} :pool pool})
        [path inline-query] (str/split uri #"\?" 2)
        response (handler
                  (cond-> {:request-method method :uri path :headers {}}
                    (or query inline-query) (assoc :query-string (or query inline-query))
                    actor (assoc-in [:headers "x-actor-id"] (str actor))
                    idempotency-key (assoc-in [:headers "idempotency-key"] idempotency-key)
                    body (-> (assoc-in [:headers "content-type"] "application/json")
                             (assoc :body (ByteArrayInputStream.
                                           (.getBytes (json/write-str body)
                                                      StandardCharsets/UTF_8))))))]
    (assoc response :json (when-not (str/blank? (:body response))
                            (json/read-str (:body response))))))

(defn- uuid [s] (java.util.UUID/fromString s))

;; ---------------------------------------------------------------------------
;; Forcing the interleaving
;; ---------------------------------------------------------------------------

(defn- both-at-once
  "Run `f` and `g` on two threads and return `[result-of-f result-of-g]`.

  A thrown exception is returned rather than lost, so a test asserting on an
  outcome never sees a silently missing one."
  [f g]
  (let [results (object-array 2)
        done (CountDownLatch. 2)
        run (fn [i h]
              (.start (Thread. (fn []
                                 (try (aset results i (h))
                                      (catch Throwable t (aset results i t))
                                      (finally (.countDown done)))))))]
    (run 0 f)
    (run 1 g)
    (is (.await done 90 TimeUnit/SECONDS) "both deliveries must finish")
    [(aget results 0) (aget results 1)]))

(defn- with-both-lookups-empty
  "Run `f` with the receipt lookup held open until two threads have used it.

  The first lookup **on each thread** returns only once both have looked, so
  both see no receipt under the reference and both go on to insert one. That is
  the state the collision branches exist for, and it is the only thing this
  fixture controls: the insert, the savepoint rollback, the commit and the
  rendering are all real.

  Later lookups pass straight through — `replay-of` uses the same function to
  read the winner's row after losing the key, and gating that one would
  deadlock the very branch under test."
  [f]
  (let [original recon/find-statement-by-reference
        looked (CountDownLatch. 2)
        seen (atom #{})]
    (with-redefs [recon/find-statement-by-reference
                  (fn [source organisation-id reference]
                    (let [result (original source organisation-id reference)
                          thread (Thread/currentThread)
                          [before _] (swap-vals! seen conj thread)]
                      (when-not (contains? before thread)
                        (.countDown looked)
                        (.await looked 60 TimeUnit/SECONDS))
                      result))]
      (f))))

;; ---------------------------------------------------------------------------
;; Fixtures — built through the API wherever the API can build them
;; ---------------------------------------------------------------------------

(def ^:private accounts
  [["1100-CLIENT-FUNDS" "asset"]
   ["1300-IN-TRANSIT" "asset"]
   ["2100-CLIENT-PAYABLE" "liability"]
   ["2200-UNAPPLIED" "liability"]])

(defn- setup
  [& {:keys [open-accounts] :or {open-accounts accounts}}]
  (let [pool tdb/*pool*
        {:keys [status json]} (call pool :post "/organisations"
                                    :body {"legalName" "Meridian Freight Holdings Pte Ltd"
                                           "shortName" (str "meridian-" (random-uuid))})
        _ (is (= 201 status))
        org (uuid (get json "id"))
        seed (fn [roles limits]
               (tdb/insert-actor! pool {:organisation-id org
                                        :display-name (str/join "+" (map name roles))
                                        :roles roles :limits limits}))
        controller (seed [:controller] {})
        maker (seed [:operator] {})
        checker (seed [:approver] {"SGD" 100000000})
        checker-2 (seed [:approver] {"SGD" 100000000})
        auditor (seed [:auditor] {})]
    (tdb/insert-threshold! pool {:organisation-id org :currency "SGD"
                                 :from-minor 100000 :approvals-required 1})
    (tdb/insert-threshold! pool {:organisation-id org :currency "SGD"
                                 :from-minor 1000000 :approvals-required 2})
    (doseq [[code type] open-accounts]
      (is (= 201 (:status (call pool :post "/accounts"
                                :actor controller
                                :body {"organisationId" (str org) "code" code
                                       "name" code "type" type "currency" "SGD"})))))
    {:pool pool :org org :controller controller :maker maker :checker checker
     :checker-2 checker-2 :auditor auditor}))

(defn- account-id
  [{:keys [pool org controller]} code]
  (->> (get (:json (call pool :get "/accounts" :actor controller
                         :query (str "organisationId=" org)))
            "accounts")
       (filter #(= code (get % "code")))
       first
       (#(get % "id"))))

(defn- raise!
  [{:keys [pool org maker checker] :as f} amount]
  (let [{:keys [status json]}
        (call pool :post "/payment-instructions"
              :actor maker :idempotency-key (str (random-uuid))
              :body {"organisationId" (str org)
                     "debtorAccountId" (account-id f "1100-CLIENT-FUNDS")
                     "creditorName" "Pacific Rim Logistics Pte Ltd"
                     "creditorAccount" "SG-SYNTH-88012340"
                     "amount" {"currency" "SGD" "minorUnits" amount}
                     "valueDate" value-date
                     "purposeCode" "SUPP"})
        _ (is (= 201 status) (str "instruction not created: " json))
        id (get json "id")]
    (is (= 200 (:status (call pool :post (str "/payment-instructions/" id "/submission")
                              :actor maker :idempotency-key (str (random-uuid))
                              :body {"organisationId" (str org)}))))
    (is (= 201 (:status (call pool :post (str "/payment-instructions/" id "/approvals")
                              :actor checker :idempotency-key (str (random-uuid))
                              :body {"organisationId" (str org) "decision" "approved"}))))
    id))

(defn- settle!
  "Raise, batch, submit and answer — the settlement the reconciliation is about."
  [{:keys [pool org controller] :as f}]
  (let [settled (raise! f 125000)
        returned (raise! f 110000)
        batch (get (:json (call pool :post "/settlement-batches"
                                :actor controller
                                :body {"organisationId" (str org) "scheme" "SIM-RTGS"
                                       "currency" "SGD" "valueDate" value-date
                                       "instructionIds" [settled returned]}))
                   "id")]
    (is (= 200 (:status (call pool :post (str "/settlement-batches/" batch "/submit")
                              :actor controller :body {"organisationId" (str org)}))))
    (is (= 200 (:status (call pool :post (str "/settlement-batches/" batch "/scheme-responses")
                              :actor controller
                              :body {"organisationId" (str org) "kind" "settled"
                                     "instructionId" settled
                                     "reference" (str "SIM-STL-" batch "-" settled)}))))
    (is (= 200 (:status (call pool :post (str "/settlement-batches/" batch "/scheme-responses")
                              :actor controller
                              :body {"organisationId" (str org) "kind" "returned"
                                     "instructionId" returned
                                     "reason" "SIM-RETURN: simulated scheme return"
                                     "reference" (str "SIM-RTN-" batch "-" returned)}))))
    {:settled settled :returned returned :batch batch}))

(defn- period []
  (let [midnight (.toInstant (.atStartOfDay (LocalDate/now ZoneOffset/UTC) ZoneOffset/UTC))]
    {:from (str (.minusSeconds midnight 86400))
     :to (str (.plusSeconds midnight 86400))}))

(defn- generate!
  [{:keys [pool org controller]}]
  (let [{:keys [from to]} (period)
        {:keys [status json]}
        (call pool :get "/settlement-statements"
              :actor controller
              :query (str "organisationId=" org "&scheme=SIM-RTGS&currency=SGD"
                          "&from=" from "&to=" to "&perturbation=none"))]
    (is (= 200 status) (str "statement not generated: " json))
    json))

(defn- ingest-fn
  "A thunk that delivers `document` through the public handler."
  [{:keys [pool org controller]} document]
  (fn [] (call pool :post "/reconciliation-statements"
               :actor controller
               :body (assoc document "organisationId" (str org)))))

;; ---------------------------------------------------------------------------
;; Counting what the losing delivery must not have done
;; ---------------------------------------------------------------------------

(defn- counts
  [pool org]
  (let [n (fn [sql] (:count (db/query-one pool (into [sql] [org]))))]
    {:receipts (n "select count(*) as count from reconciliation_statement
                    where organisation_id = ?")
     :lines (n "select count(*) as count from reconciliation_statement_line l
                 join reconciliation_statement s on s.id = l.statement_id
                where s.organisation_id = ?")
     :matches (n "select count(*) as count from reconciliation_match m
                   join reconciliation_statement s on s.id = m.statement_id
                  where s.organisation_id = ?")
     :breaks (n "select count(*) as count from reconciliation_break
                  where organisation_id = ?")
     :arrivals (n "select count(*) as count from audit_event
                    where organisation_id = ?
                      and action = 'reconciliation-statement.received'")}))

(defn- outcome
  "What one delivery answered: its status, whether it was called a replay, and
  the refusal reason when it was refused.

  `contains?` rather than `or`, because the value under test is a boolean and
  `false` is the answer that matters most here — an `or` would report it as
  missing."
  [response]
  (if (instance? Throwable response)
    {:status :threw :error (str response)}
    (let [body (:json response)]
      {:status (:status response)
       :replayed (if (contains? body "replayed")
                   (get body "replayed")
                   (get-in body ["errors" "replayed"]))
       :reason (get-in body ["errors" "dispositionReason"])})))

;; ---------------------------------------------------------------------------
;; AC-1 (2C-002) — the four cells
;;
;; The pre-check compares digests; before this fix, neither collision branch
;; did. A loser therefore replayed the winner's receipt on the strength of the
;; key alone, and answered `replayed: true` for a document CloFin had never
;; seen. The serial path answers 409 for exactly that arrival, which is what
;; makes the disagreement a defect rather than a design choice (ADR-0023:
;; "a *different* document under the same reference is refused and is never
;; called a replay").
;; ---------------------------------------------------------------------------

(defn- applied-documents
  "Two documents under one reference: the statement the simulation really sent,
  and the same statement with one amount changed by a minor unit."
  [f]
  (let [document (generate! f)
        tampered (update document "lines"
                         (fn [lines]
                           (update-in (vec lines) [0 "amount" "minorUnits"] + 1)))]
    [document tampered]))

(defn- refused-document
  "A statement for an organisation with no `1300-IN-TRANSIT`: refused with a
  receipt, which is the second stored outcome a collision can find."
  [period-end]
  {"format" "SIM-CLOFIN-RECON-STATEMENT"
   "formatVersion" 1
   "scheme" "SIM-RTGS"
   "currency" "SGD"
   "statementReference" "SIM-STMT-RACE"
   "periodStart" (:from (period))
   "periodEnd" period-end
   "lines" []})

(deftest ac-1-identical-documents-racing-produce-one-receipt-and-one-replay
  (testing "the applied path: whichever transaction loses the key replays the
            winner's receipt, because it really is the same document"
    (let [f (setup)
          _ (settle! f)
          [document _] (applied-documents f)
          [a b] (with-both-lookups-empty
                  #(both-at-once (ingest-fn f document) (ingest-fn f document)))
          answers (map outcome [a b])]
      (is (= [200 200] (mapv :status answers)) (pr-str answers))
      (is (= 1 (count (filter :replayed answers)))
          (str "exactly one of the two is a replay — " (pr-str answers)))
      (let [c (counts (:pool f) (:org f))]
        (is (= 1 (:receipts c)) "one receipt row, not two")
        (is (= 1 (:arrivals c)) "one arrival event, not two")))))

(deftest ac-1-different-documents-racing-refuse-the-loser-rather-than-replaying-it
  (testing "the applied path, and the finding itself: a document nobody
            processed must never be acknowledged as an exact replay of a
            different one (2C-002, L-18)"
    (let [f (setup)
          _ (settle! f)
          [document tampered] (applied-documents f)
          [a b] (with-both-lookups-empty
                  #(both-at-once (ingest-fn f document) (ingest-fn f tampered)))
          answers (map outcome [a b])
          ;; Which of the two documents wins the key is genuinely up to the
          ;; scheduler, so nothing here assumes it. What is asserted is that
          ;; the database holds the winner's work and nothing besides.
          won (first (filter #(and (map? %) (= 200 (:status %))) [a b]))
          winner (first (filter #(= 200 (:status %)) answers))
          loser (first (filter #(= 409 (:status %)) answers))]
      (is (= #{200 409} (set (map :status answers)))
          (str "one applies and one is refused the reference — " (pr-str answers)))
      (is (false? (:replayed winner))
          "the winner did the work, so it is not a replay")
      (is (some? loser) (str "no 409 among " (pr-str answers)))
      (is (= "replay-key-conflict" (:reason loser))
          (str "the loser must re-enter the decision the serial path makes — "
               (pr-str loser)))
      (is (false? (:replayed loser))
          (str "and it is never called a replay — " (pr-str loser)))
      (let [c (counts (:pool f) (:org f))]
        (is (= 1 (:receipts c)) "one receipt row: the loser wrote none")
        (is (= 1 (:arrivals c)) "one arrival event: the loser recorded none")
        (is (= 2 (:lines c)) "one document's worth of lines, not two documents'")
        (is (= (count (get-in won [:json "matches"])) (:matches c))
            "exactly the matches the winner reported, and no others")
        (is (= (count (get-in won [:json "breaks"])) (:breaks c))
            "exactly the breaks the winner reported: the loser opened none")))))

(deftest ac-1-identical-documents-racing-on-the-refused-path-replay-the-refusal
  (testing "the refused path: a receipt exists for a statement CloFin could not
            process, and the loser reproduces that same refusal"
    (let [f (setup :open-accounts [["1100-CLIENT-FUNDS" "asset"]
                                   ["2200-UNAPPLIED" "liability"]])
          document (refused-document (:to (period)))
          [a b] (with-both-lookups-empty
                  #(both-at-once (ingest-fn f document) (ingest-fn f document)))
          answers (map outcome [a b])]
      (is (= [422 422] (mapv :status answers)) (pr-str answers))
      (is (every? #(= "no-reconciled-account" (:reason %)) answers)
          (str "both are told why the statement could not be processed — "
               (pr-str answers)))
      (is (= 1 (count (filter :replayed answers)))
          (str "exactly one of the two is a replay — " (pr-str answers)))
      (let [c (counts (:pool f) (:org f))]
        (is (= 1 (:receipts c)) "one receipt row, not two")
        (is (= 1 (:arrivals c)) "one arrival event, not two")))))

(deftest ac-1-different-documents-racing-on-the-refused-path-refuse-the-loser
  (testing "the fourth cell: the stored outcome is a refusal and the documents
            differ, so the loser is answered 409 rather than handed somebody
            else's refusal as its own"
    (let [f (setup :open-accounts [["1100-CLIENT-FUNDS" "asset"]
                                   ["2200-UNAPPLIED" "liability"]])
          later (str (.plusSeconds (Instant/parse (:to (period))) 3600))
          [a b] (with-both-lookups-empty
                  #(both-at-once (ingest-fn f (refused-document (:to (period))))
                                 (ingest-fn f (refused-document later))))
          answers (map outcome [a b])
          loser (first (filter #(= 409 (:status %)) answers))]
      (is (= #{422 409} (set (map :status answers)))
          (str "one receipt is written and refused, the other reference is
                refused outright — " (pr-str answers)))
      (is (some? loser) (str "no 409 among " (pr-str answers)))
      (is (= "replay-key-conflict" (:reason loser)) (pr-str loser))
      (is (false? (boolean (:replayed loser))) (pr-str loser))
      (let [c (counts (:pool f) (:org f))]
        (is (= 1 (:receipts c)) "one receipt row: the loser wrote none")
        (is (= 1 (:arrivals c)) "one arrival event: the loser recorded none")))))

(deftest ac-1-the-serial-answer-and-the-raced-answer-are-the-same
  (testing "the decision is one decision (L-18): the same contradiction sent
            one after the other is answered exactly as it is when the two
            arrive together"
    (let [f (setup)
          _ (settle! f)
          [document tampered] (applied-documents f)
          _ (is (= 200 (:status ((ingest-fn f document)))))
          serial (outcome ((ingest-fn f tampered)))]
      (is (= 409 (:status serial)) (pr-str serial))
      (is (= "replay-key-conflict" (:reason serial)) (pr-str serial))
      (is (false? (boolean (:replayed serial))) (pr-str serial)))))

;; ---------------------------------------------------------------------------
;; AC-8 (2B-004) — the locks, proved with two connections
;;
;; `clofin.recon.repository`'s docstring states the lock order and
;; `clofin.recon.service` reads every gated row `for update`, and until now
;; nothing failed if either stopped being true. Ordinary functional tests stay
;; green after a lock is removed, because nothing in them ever loses a race —
;; which is the whole of the finding: the recorded design was not the
;; adversarial proof standing lesson **L-8** asks for.
;;
;; Both tests below force the interleaving rather than hoping for it. The
;; window between a validating read and the write it gates is microseconds
;; wide, so two threads simply started at once reproduce nothing; the first
;; transaction therefore holds its lock open across a sleep and the second runs
;; into it. This is the harness `clofin.ledger.repository-test` built for
;; F-004, reused rather than reinvented.
;; ---------------------------------------------------------------------------

(defn- a-break!
  "One break to contend over, opened by a real ingestion."
  [{:keys [pool org controller] :as f}]
  (settle! f)
  (let [{:keys [from to]} (period)
        document (:json (call pool :get "/settlement-statements"
                              :actor controller
                              :query (str "organisationId=" org "&scheme=SIM-RTGS&currency=SGD"
                                          "&from=" from "&to=" to
                                          "&perturbation=unknown-line")))
        response ((ingest-fn f document))]
    (is (= 200 (:status response)) (str (:body response)))
    (let [brk (first (get-in response [:json "breaks"]))]
      (is (some? brk) "the perturbed statement must open a break to contend over")
      brk)))

(defn- break-state
  [pool id]
  (:state (db/query-one pool ["select state from reconciliation_break where id = ?"
                              (uuid id)])))

(defn- events-about
  [pool org subject-id]
  (mapv :action (db/query pool ["select action from audit_event
                                  where organisation_id = ? and subject_id = ?
                                  order by occurred_at, id"
                                org (uuid subject-id)])))

(deftest ac-8-an-assignment-serialises-behind-an-in-flight-resolution
  (testing "the resolving transaction holds the break's row lock; the
            assignment must wait for it to commit and then be refused against
            the state it actually finds, not the one it read on the way in"
    (let [f (setup)
          brk (a-break! f)
          pool (:pool f)
          break-id (get brk "id")
          locked (CountDownLatch. 1)
          resolved-at (atom nil)
          assigned-at (atom nil)]

      ;; The resolving transaction: take the lock, hold it while the assignment
      ;; arrives, then post an adjustment below the organisation's lowest band
      ;; — which resolves the break — and commit.
      (.start (Thread.
               (fn []
                 (db/with-transaction [tx pool]
                   (recon/lock-break! tx (:org f) (uuid break-id))
                   (.countDown locked)
                   (Thread/sleep 1500)
                   (service/propose-adjustment!
                    tx {:organisation-id (:org f)
                        :break-id        (uuid break-id)
                        :adjustment-id   (random-uuid)
                        :amount          (money/of "SGD" 99999)
                        :direction       :credit
                        :narrative       "Agreeing with the scheme"
                        :actor           {:id (:controller f)}
                        :correlation-id  (str (random-uuid))
                        :entry-id        (random-uuid)
                        :occurred-at     (java.time.Instant/now)}))
                 (reset! resolved-at (System/nanoTime)))))

      (is (.await locked 10 TimeUnit/SECONDS)
          "the resolving transaction must hold the lock before the assignment starts")

      (let [assignment (call pool :post (str "/reconciliation-breaks/" break-id "/assignment")
                             :actor (:controller f)
                             :body {"organisationId" (str (:org f))
                                    "assigneeId" (str (:maker f))})]
        (reset! assigned-at (System/nanoTime))

        (is (some? @resolved-at)
            "the resolution must have committed before the assignment answered")
        (is (< @resolved-at @assigned-at)
            "the assignment must have blocked on the lock rather than reading
             around it: an answer that arrived first read a state that was
             about to change underneath it")

        (is (= 409 (:status assignment))
            (str "exactly one transition wins, and it is the one that committed "
                 "first — " (:body assignment)))
        (is (= "Cannot assign a reconciliation break that is resolved"
               (get-in assignment [:json "detail"]))
            (str "refused with the reason the lifecycle table records, not a "
                 "generic conflict — " (:body assignment)))
        (is (= "resolved" (get-in assignment [:json "errors" "break-state"]))
            (str "and it names the state it actually found — " (:body assignment))))

      (is (= "resolved" (break-state pool break-id))
          "and the state the winner wrote is the state that stands")

      (testing "one audit event for the transition that happened, and none for
                the one that did not"
        (let [actions (events-about pool (:org f) break-id)]
          (is (= 1 (count (filter #{"reconciliation-break.resolved"} actions)))
              (str "exactly one resolution event — " (pr-str actions)))
          (is (empty? (filter #{"reconciliation-break.assigned"} actions))
              (str "the refused assignment recorded nothing — " (pr-str actions))))))))

(deftest ac-8-two-decisions-on-one-adjustment-serialise-and-only-one-lands
  (testing "both approvers see a `proposed` adjustment, both proceed, and the
            second is refused against what it finds under its own lock — at
            most one journal entry, whatever the scheduling"
    (let [f (setup)
          brk (a-break! f)
          pool (:pool f)
          ;; At or above the organisation's lowest band, so the adjustment is
          ;; proposed and waits for an approver other than its proposer.
          proposal (call pool :post (str "/reconciliation-breaks/" (get brk "id") "/adjustments")
                         :actor (:controller f)
                         :body {"organisationId" (str (:org f))
                                "amount" {"currency" "SGD" "minorUnits" 100000}
                                "direction" "credit"
                                "narrative" "Agreeing with the scheme"})
          _ (is (= 201 (:status proposal)) (str (:body proposal)))
          adjustment-id (get-in proposal [:json "id"])
          _ (is (= "proposed" (get-in proposal [:json "status"])))
          entries-before (:count (db/query-one pool ["select count(*) as count from journal_entry"]))
          decide (fn [actor]
                   (fn [] (call pool :post
                                (str "/reconciliation-adjustments/" adjustment-id "/approvals")
                                :actor actor
                                :body {"organisationId" (str (:org f))})))
          ;; The same gate the receipt-collision matrix uses, on the unlocked
          ;; read `decide-adjustment!` opens with: both transactions address a
          ;; `proposed` adjustment before either takes the break's lock.
          original recon/find-adjustment
          original-lock recon/lock-break!
          both (CountDownLatch. 2)
          seen (atom #{})
          ;; How long each transaction spent inside the break's `for update`.
          ;; AC-8 asks each latch test to show the blocked transaction *waited*,
          ;; and every assertion below is about the outcome — which a loser
          ;; refused on a read it merely took second would satisfy just as well.
          ;;
          ;; The winner holds the lock for `hold-ms` before returning, so the
          ;; wait is observable rather than a matter of microseconds. That
          ;; widens the window; it does not create it. Without the lock the
          ;; second transaction would sail through in about the time the first
          ;; one took.
          hold-ms 1000
          first-lock (atom nil)
          waits (atom {})
          [a b] (with-redefs [recon/find-adjustment
                              (fn [source organisation-id id]
                                (let [result (original source organisation-id id)
                                      thread (Thread/currentThread)
                                      [before _] (swap-vals! seen conj thread)]
                                  (when-not (contains? before thread)
                                    (.countDown both)
                                    (.await both 60 TimeUnit/SECONDS))
                                  result))
                              recon/lock-break!
                              (fn [tx organisation-id id]
                                (let [entered (System/nanoTime)
                                      result (original-lock tx organisation-id id)
                                      waited (quot (- (System/nanoTime) entered) 1000000)
                                      thread (Thread/currentThread)]
                                  (swap! waits assoc thread waited)
                                  ;; Whoever got here first keeps the lock for a
                                  ;; moment. The other is now blocked in the
                                  ;; database, not merely later in a queue.
                                  (when (compare-and-set! first-lock nil thread)
                                    (Thread/sleep hold-ms))
                                  result))]
                  (both-at-once (decide (:checker f)) (decide (:checker-2 f))))
          answers (map outcome [a b])]

      (is (= #{201 409} (set (map :status answers)))
          (str "one decision lands and the other is refused against the state it "
               "finds under its own lock — " (pr-str answers)))

      (is (= "posted" (:status (db/query-one
                                pool ["select status from reconciliation_adjustment where id = ?"
                                      (uuid adjustment-id)])))
          "the winner posted it")

      (is (= 1 (- (:count (db/query-one pool ["select count(*) as count from journal_entry"]))
                  entries-before))
          "at most one journal entry: two postings of one adjustment would be
           the same money moved twice")

      (is (= 1 (:count (db/query-one
                        pool ["select count(*) as count from approval
                                where adjustment_id = ? and invalidated_at is null"
                              (uuid adjustment-id)])))
          "and one live decision, because the loser's never happened")

      (is (= "resolved" (break-state pool (get brk "id")))
          "the break the adjustment addressed is resolved exactly once")

      (testing "and the loser *blocked* — AC-8 asks each latch test to show
                that, and every assertion above is about the outcome, which a
                loser refused on a read it happened to take second would
                satisfy just as well"
        (let [measured (sort (vals @waits))]
          (is (= 2 (count measured))
              (str "both transactions must reach the break's `for update` — "
                   (pr-str @waits)))
          (is (< (first measured) (quot hold-ms 2))
              (str "the winner took the lock without waiting — " (pr-str measured)))
          (is (>= (second measured) (quot hold-ms 2))
              (str "the second transaction sat inside `for update` while the "
                   "first held it, which is the serialisation; a loser that "
                   "read around the lock would have come back as fast as the "
                   "winner — waits in ms: " (pr-str measured))))))))
