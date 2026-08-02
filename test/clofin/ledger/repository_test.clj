(ns clofin.ledger.repository-test
  "The ledger repository against real PostgreSQL.

  These run against a real database rather than a substitute because most of
  what they assert — the deferred zero-sum trigger, transactional rollback,
  the ordering PostgreSQL actually returns — does not exist in a substitute.

  Acceptance criteria from docs/briefs/001-TASK-ledger-persistence-and-account-api.md
  are named in the tests that cover them."
  (:require [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.ledger.account :as account]
            [clofin.ledger.entry :as entry]
            [clofin.ledger.repository :as repo]
            [clofin.money :as money]
            [clofin.organisations.repository :as organisations]
            [clofin.test-db :as tdb]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time Instant]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- t [iso] (Instant/parse iso))

(def ^:private jan (t "2026-01-01T00:00:00Z"))

(defn- sgd [minor] (money/of "SGD" minor))

(defn- new-organisation!
  ([] (new-organisation! "meridian"))
  ([short-name]
   (organisations/create-organisation! tdb/*pool*
                                       {:id (random-uuid)
                                        :legal-name "Meridian Freight Holdings Pte Ltd"
                                        :short-name short-name})))

(defn- new-account!
  [org code type & {:keys [currency status] :or {currency "SGD" status :active}}]
  (repo/create-account! tdb/*pool*
                        {:id (random-uuid)
                         :organisation-id (:id org)
                         :code code
                         :name (str "Account " code)
                         :type type
                         :currency currency
                         :status status}))

(defn- ledger-fixture
  "An organisation with the two accounts every test below needs: a pooled
  client-money asset account and the liability it is owed against."
  []
  (let [org (new-organisation!)]
    {:org     org
     :cash    (new-account! org "1100-CLIENT-FUNDS" :asset)
     :payable (new-account! org "2100-CLIENT-PAYABLE" :liability)}))

(defn- post!
  "Post a two-line transfer: debit `to`, credit `from`."
  [{:keys [org]} {:keys [from to amount occurred-at narrative]}]
  (repo/post-entry! tdb/*pool*
                    {:id (random-uuid)
                     :organisation-id (:id org)
                     :occurred-at occurred-at
                     :narrative (or narrative "Test movement")
                     :reference {:type :opening-balance :id (random-uuid)}
                     :lines (entry/transfer-lines {:from-account-id (:id from)
                                                   :to-account-id (:id to)
                                                   :amount amount})}))

(defn- unprocessable
  "Run `f`, returning the domain error data of the failure it is expected to
  raise. Fails the test if it does not raise one."
  [f]
  (try
    (f)
    (is false "expected a domain error, but the call succeeded")
    nil
    (catch clojure.lang.ExceptionInfo e
      (is (err/domain-error? e) "expected a domain error rather than a defect")
      (assoc (ex-data e) :message (ex-message e)))))

(defn- entry-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from journal_entry"])))

(defn- line-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from journal_line"])))

;; ---------------------------------------------------------------------------
;; Accounts
;; ---------------------------------------------------------------------------

(deftest an-account-round-trips
  (let [org  (new-organisation!)
        made (new-account! org "1100-CLIENT-FUNDS" :asset)]
    (is (= made (repo/find-account tdb/*pool* (:id org) (:id made)))
        "what comes back out is what went in")
    (is (= :active (:status made)) "accounts are created active")))

(deftest an-account-is-invisible-to-another-organisation
  (testing "an unscoped lookup is how one tenant reads another's chart of accounts"
    (let [mine   (new-organisation! "meridian")
          theirs (new-organisation! "kestrel")
          acct   (new-account! mine "1100-CLIENT-FUNDS" :asset)]
      (is (some? (repo/find-account tdb/*pool* (:id mine) (:id acct))))
      (is (nil? (repo/find-account tdb/*pool* (:id theirs) (:id acct)))))))

(deftest account-codes-are-unique-within-an-organisation
  (let [org (new-organisation!)]
    (new-account! org "1100-CLIENT-FUNDS" :asset)
    (is (= :conflict
           (:clofin/error (unprocessable #(new-account! org "1100-CLIENT-FUNDS" :asset))))
        "a duplicate code is a conflict the caller can act on, not a 500")))

(deftest accounts-are-listed-by-code
  (let [org (new-organisation!)]
    (new-account! org "2100-CLIENT-PAYABLE" :liability)
    (new-account! org "1100-CLIENT-FUNDS" :asset)
    (new-account! org "4100-FEE-INCOME" :revenue)
    (is (= ["1100-CLIENT-FUNDS" "2100-CLIENT-PAYABLE" "4100-FEE-INCOME"]
           (mapv :code (repo/list-accounts tdb/*pool* (:id org))))
        "a chart of accounts is read in code order, so the order is not incidental")))

(deftest an-account-cannot-be-opened-for-an-unknown-organisation
  (is (= :unprocessable
         (:clofin/error
          (unprocessable #(repo/create-account! tdb/*pool*
                                                {:id (random-uuid)
                                                 :organisation-id (random-uuid)
                                                 :code "1100-CLIENT-FUNDS"
                                                 :name "Orphan"
                                                 :type :asset
                                                 :currency "SGD"
                                                 :status :active}))))))

;; ---------------------------------------------------------------------------
;; Posting — AC-1, AC-2
;; ---------------------------------------------------------------------------

(deftest ac-1-a-balanced-entry-persists-with-all-its-lines
  (let [{:keys [org cash payable] :as fixture} (ledger-fixture)
        posted (post! fixture {:from payable :to cash :amount (sgd 125000)
                               :occurred-at jan :narrative "Opening balance"})
        found  (repo/find-entry tdb/*pool* (:id org) (:id posted))]
    (is (= 1 (entry-count)))
    (is (= 2 (line-count)) "both lines are persisted, not just the first")
    (is (= (:id posted) (:id found)))
    (is (= "Opening balance" (:narrative found)))
    (is (= jan (:occurred-at found)))
    (is (= #{[(:id cash) :debit] [(:id payable) :credit]}
           (into #{} (map (juxt :account-id :direction)) (:lines found))))
    (is (some? (:recorded-at found))
        "the journal records when it was told, as well as when it happened")))

(deftest ac-2-an-unbalanced-entry-persists-nothing
  (testing "the domain constructor refuses it before a row is written"
    (let [{:keys [org cash payable]} (ledger-fixture)
          failure (unprocessable
                   #(repo/post-entry! tdb/*pool*
                                      {:id (random-uuid)
                                       :organisation-id (:id org)
                                       :occurred-at jan
                                       :narrative "Deliberately unbalanced"
                                       :reference {:type :opening-balance :id (random-uuid)}
                                       :lines [{:account-id (:id cash) :direction :debit
                                                :amount (sgd 125000)}
                                               {:account-id (:id payable) :direction :credit
                                                :amount (sgd 100000)}]}))]
      (is (= {"SGD" "250.00"} (:imbalance failure))
          "the shortfall is named per currency, not merely refused")
      (is (zero? (entry-count)))
      (is (zero? (line-count))))))

(deftest an-entry-cannot-be-posted-twice-under-the-same-id
  (let [{:keys [org cash payable]} (ledger-fixture)
        candidate {:id (random-uuid)
                   :organisation-id (:id org)
                   :occurred-at jan
                   :narrative "Once"
                   :reference {:type :opening-balance :id (random-uuid)}
                   :lines (entry/transfer-lines {:from-account-id (:id payable)
                                                 :to-account-id (:id cash)
                                                 :amount (sgd 1000)})}]
    (repo/post-entry! tdb/*pool* candidate)
    (is (= :conflict (:clofin/error (unprocessable #(repo/post-entry! tdb/*pool* candidate)))))
    (is (= 1 (entry-count)) "the second attempt leaves the first entry alone")))

;; ---------------------------------------------------------------------------
;; Posting rules that need database state — AC-4, AC-7
;; ---------------------------------------------------------------------------

(deftest ac-4-an-entry-referencing-a-frozen-or-closed-account-is-refused
  (doseq [status [:frozen :closed]]
    (testing (str "posting to a " (name status) " account")
      (tdb/clean-business-data! tdb/*pool*)
      (let [org     (new-organisation!)
            blocked (new-account! org "1100-CLIENT-FUNDS" :asset :status status)
            open    (new-account! org "2100-CLIENT-PAYABLE" :liability)
            failure (unprocessable
                     #(post! {:org org} {:from open :to blocked :amount (sgd 500)
                                         :occurred-at jan}))]
        (is (= :unprocessable (:clofin/error failure)))
        (is (= [{:id (str (:id blocked)) :code "1100-CLIENT-FUNDS" :status (name status)}]
               (:accounts failure))
            "the response names the account a caller must go and unfreeze")
        (is (zero? (entry-count)) "nothing is persisted")))))

(deftest ac-7-an-entry-referencing-another-organisations-account-is-refused
  (let [mine    (new-organisation! "meridian")
        theirs  (new-organisation! "kestrel")
        mine-a  (new-account! mine "1100-CLIENT-FUNDS" :asset)
        mine-b  (new-account! mine "2100-CLIENT-PAYABLE" :liability)
        outside (new-account! theirs "1100-CLIENT-FUNDS" :asset)
        failure (unprocessable
                 #(repo/post-entry! tdb/*pool*
                                    {:id (random-uuid)
                                     :organisation-id (:id mine)
                                     :occurred-at jan
                                     :narrative "Cross-tenant"
                                     :reference {:type :opening-balance :id (random-uuid)}
                                     :lines (entry/transfer-lines
                                             {:from-account-id (:id mine-b)
                                              :to-account-id (:id outside)
                                              :amount (sgd 500)})}))]
    (is (= :unprocessable (:clofin/error failure)))
    (is (= [(str (:id outside))] (:account-ids failure)))
    (testing "the message does not confirm that the id belongs to someone else"
      (is (re-find #"do not exist in this organisation" (:message failure))))
    (is (zero? (entry-count)))
    (is (some? (repo/find-account tdb/*pool* (:id mine) (:id mine-a)))
        "the caller's own accounts are untouched")))

(deftest a-line-must-be-in-its-accounts-currency
  (testing "otherwise the resulting balance is not computable at all"
    (let [org  (new-organisation!)
          sgd-account (new-account! org "1100-CLIENT-FUNDS" :asset :currency "SGD")
          usd-account (new-account! org "1200-USD-FUNDS" :asset :currency "USD")
          failure (unprocessable
                   #(repo/post-entry! tdb/*pool*
                                      {:id (random-uuid)
                                       :organisation-id (:id org)
                                       :occurred-at jan
                                       :narrative "Wrong currency"
                                       :reference {:type :opening-balance :id (random-uuid)}
                                       :lines (entry/transfer-lines
                                               {:from-account-id (:id usd-account)
                                                :to-account-id (:id sgd-account)
                                                :amount (money/of "USD" 500)})}))]
      (is (= :unprocessable (:clofin/error failure)))
      (is (= "SGD" (:account-currency failure)))
      (is (= "USD" (:line-currency failure)))
      (is (zero? (entry-count))))))

;; ---------------------------------------------------------------------------
;; Derived balances — AC-3
;; ---------------------------------------------------------------------------

(deftest ac-3-a-balance-is-derived-from-the-journal
  (let [{:keys [cash payable] :as fixture} (ledger-fixture)
        as-of (t "2026-12-31T00:00:00Z")]
    (is (= (sgd 0) (repo/balance-at tdb/*pool* cash as-of))
        "an account with no postings has a zero balance, not a missing one")

    (post! fixture {:from payable :to cash :amount (sgd 125000) :occurred-at jan})
    (is (= (sgd 125000) (repo/balance-at tdb/*pool* cash as-of))
        "adding an entry moves the balance — there is nothing else it could come from")

    (post! fixture {:from payable :to cash :amount (sgd 25000)
                    :occurred-at (t "2026-02-01T00:00:00Z")})
    (is (= (sgd 150000) (repo/balance-at tdb/*pool* cash as-of)))

    (testing "the sign is expressed in the account's own terms"
      (is (= (sgd 150000) (repo/balance-at tdb/*pool* payable as-of))
          "a positive liability balance means money is owed, not that credits are positive")
      (is (= :debit (account/normal-balance (:type cash))))
      (is (= :credit (account/normal-balance (:type payable)))))))

(deftest a-balance-is-a-point-in-time-question
  (let [{:keys [cash payable] :as fixture} (ledger-fixture)
        moment (t "2026-03-01T12:00:00Z")]
    (post! fixture {:from payable :to cash :amount (sgd 1000) :occurred-at moment})

    (testing "balance-at includes a movement occurring exactly at the instant asked about"
      (is (= (sgd 1000) (repo/balance-at tdb/*pool* cash moment))))

    (testing "balance-strictly-before does not — which is what a period boundary needs"
      (is (= (sgd 0) (repo/balance-strictly-before tdb/*pool* cash moment))))))

;; ---------------------------------------------------------------------------
;; Reversal — AC-6
;; ---------------------------------------------------------------------------

(deftest ac-6-a-reversal-returns-the-balance-and-leaves-both-entries-visible
  (let [{:keys [org cash payable] :as fixture} (ledger-fixture)
        as-of    (t "2026-12-31T00:00:00Z")
        original (post! fixture {:from payable :to cash :amount (sgd 125000)
                                 :occurred-at jan :narrative "Mistaken posting"})
        before   (repo/balance-at tdb/*pool* cash as-of)
        reversal (repo/post-entry! tdb/*pool*
                                   (entry/reverse-entry original
                                                        {:id (random-uuid)
                                                         :occurred-at (t "2026-01-02T00:00:00Z")}))]
    (is (= (sgd 125000) before))
    (is (= (sgd 0) (repo/balance-at tdb/*pool* cash as-of))
        "the account is back where it started")
    (is (= (sgd 0) (repo/balance-at tdb/*pool* payable as-of)))

    (testing "both the error and the correction remain visible — that is the point"
      (is (some? (repo/find-entry tdb/*pool* (:id org) (:id original))))
      (is (some? (repo/find-entry tdb/*pool* (:id org) (:id reversal))))
      (is (= 2 (entry-count)))
      (is (= 4 (line-count))))

    (testing "an entry can be reversed only once"
      (is (= :conflict
             (:clofin/error
              (unprocessable #(repo/post-entry!
                               tdb/*pool*
                               (entry/reverse-entry original
                                                    {:id (random-uuid)
                                                     :occurred-at (t "2026-01-03T00:00:00Z")})))))
          "a second reversal would silently reapply the original movement"))))

;; ---------------------------------------------------------------------------
;; Multiple currencies — AC-8
;; ---------------------------------------------------------------------------

(deftest ac-8-an-entry-may-span-currencies-that-balance-within-each
  (let [org      (new-organisation!)
        sgd-cash (new-account! org "1100-SGD-FUNDS" :asset :currency "SGD")
        sgd-owed (new-account! org "2100-SGD-PAYABLE" :liability :currency "SGD")
        jpy-cash (new-account! org "1200-JPY-FUNDS" :asset :currency "JPY")
        jpy-owed (new-account! org "2200-JPY-PAYABLE" :liability :currency "JPY")
        posted   (repo/post-entry!
                  tdb/*pool*
                  {:id (random-uuid)
                   :organisation-id (:id org)
                   :occurred-at jan
                   :narrative "Two currencies, balanced within each"
                   :reference {:type :opening-balance :id (random-uuid)}
                   :lines [{:account-id (:id sgd-cash) :direction :debit  :amount (sgd 125000)}
                           {:account-id (:id sgd-owed) :direction :credit :amount (sgd 125000)}
                           ;; JPY has no minor unit — 125000 yen, not 1,250.00.
                           {:account-id (:id jpy-cash) :direction :debit  :amount (money/of "JPY" 125000)}
                           {:account-id (:id jpy-owed) :direction :credit :amount (money/of "JPY" 125000)}]})
        as-of    (t "2026-12-31T00:00:00Z")]
    (is (= 4 (count (:lines posted))))
    (is (= (sgd 125000) (repo/balance-at tdb/*pool* sgd-cash as-of)))
    (is (= (money/of "JPY" 125000) (repo/balance-at tdb/*pool* jpy-cash as-of)))
    (testing "each currency is balanced separately, so neither subsidises the other"
      (is (= #{"JPY" "SGD"} (entry/currencies posted))))))

;; ---------------------------------------------------------------------------
;; Statements — AC-5
;; ---------------------------------------------------------------------------

(deftest ac-5-a-statement-adds-up
  (let [{:keys [cash payable] :as fixture} (ledger-fixture)]
    ;; One movement before the period, three inside it, one after.
    (post! fixture {:from payable :to cash :amount (sgd 100000)
                    :occurred-at (t "2026-01-15T00:00:00Z") :narrative "Before the period"})
    (post! fixture {:from payable :to cash :amount (sgd 25000)
                    :occurred-at (t "2026-02-05T00:00:00Z") :narrative "Deposit"})
    (post! fixture {:from cash :to payable :amount (sgd 5000)
                    :occurred-at (t "2026-02-10T00:00:00Z") :narrative "Withdrawal"})
    (post! fixture {:from payable :to cash :amount (sgd 1000)
                    :occurred-at (t "2026-02-20T00:00:00Z") :narrative "Interest"})
    (post! fixture {:from payable :to cash :amount (sgd 999999)
                    :occurred-at (t "2026-03-05T00:00:00Z") :narrative "After the period"})

    (let [{:keys [opening-balance closing-balance movements truncated?]}
          (repo/statement tdb/*pool* cash {:from (t "2026-02-01T00:00:00Z")
                                           :to   (t "2026-03-01T00:00:00Z")})
          movement-total (money/sum "SGD" (map (fn [m]
                                                 (account/signed-amount
                                                  (:type cash) (:direction m) (:amount m)))
                                               movements))]
      (is (false? truncated?))
      (is (= 3 (count movements)) "only the movements inside the period")
      (is (= (sgd 100000) opening-balance) "everything strictly before `from`")
      (is (= (sgd 121000) closing-balance))

      (testing "opening + sum(movements) = closing — the point of the endpoint"
        (is (= closing-balance (money/+ opening-balance movement-total))))

      (testing "the last running balance reaches the closing balance"
        (is (= closing-balance (:running-balance (last movements)))))

      (testing "each running balance is the balance after that movement"
        (is (= [(sgd 125000) (sgd 120000) (sgd 121000)]
               (mapv :running-balance movements))))

      (testing "movements carry what a reader needs to recognise them"
        (is (= ["Deposit" "Withdrawal" "Interest"] (mapv :narrative movements)))
        (is (= [:debit :credit :debit] (mapv :direction movements)))))))

(deftest a-statement-period-is-half-open-so-consecutive-periods-chain
  (let [{:keys [cash payable] :as fixture} (ledger-fixture)
        boundary (t "2026-02-01T00:00:00Z")]
    (post! fixture {:from payable :to cash :amount (sgd 10000)
                    :occurred-at (t "2026-01-20T00:00:00Z")})
    ;; Posted exactly on the boundary: the case an inclusive end double-counts.
    (post! fixture {:from payable :to cash :amount (sgd 7000) :occurred-at boundary})
    (post! fixture {:from payable :to cash :amount (sgd 3000)
                    :occurred-at (t "2026-02-14T00:00:00Z")})

    (let [january  (repo/statement tdb/*pool* cash {:from (t "2026-01-01T00:00:00Z")
                                                    :to   boundary})
          february (repo/statement tdb/*pool* cash {:from boundary
                                                    :to   (t "2026-03-01T00:00:00Z")})]
      (is (= (:closing-balance january) (:opening-balance february))
          "the closing balance of one period is the opening balance of the next")
      (is (= (sgd 10000) (:closing-balance january)))
      (is (= 1 (count (:movements january))) "the boundary movement is not in January")
      (is (= 2 (count (:movements february))) "it is in February, exactly once")
      (is (= (sgd 20000) (:closing-balance february))))))

(deftest a-statement-is-the-same-document-every-time-it-is-produced
  (testing "movements sharing an occurrence instant still have a total order"
    (let [{:keys [cash payable] :as fixture} (ledger-fixture)
          simultaneous (t "2026-02-10T09:00:00Z")]
      (dotimes [n 5]
        (post! fixture {:from payable :to cash :amount (sgd (* 100 (inc n)))
                        :occurred-at simultaneous :narrative (str "Movement " n)}))
      (let [period {:from (t "2026-02-01T00:00:00Z") :to (t "2026-03-01T00:00:00Z")}
            once  (repo/statement tdb/*pool* cash period)
            twice (repo/statement tdb/*pool* cash period)]
        (is (= 5 (count (:movements once))))
        (is (= (:movements once) (:movements twice))
            "a statement that reorders between runs cannot be used as evidence")))))

(deftest a-statement-past-the-row-cap-says-so-and-still-closes-correctly
  (let [{:keys [org cash]} (ledger-fixture)
        ;; Enough lines to exceed the cap: 251 debits followed by 251 credits,
        ;; all on the same account. The entry balances, so it is legal, and it
        ;; produces more movements on that account than a statement returns.
        ;; Ordering by line number puts every debit before every credit, so the
        ;; capped view is *not* representative of the whole — which is exactly
        ;; the situation `truncated` exists to disclose.
        per-side (inc (quot repo/row-cap 2))
        line     (fn [direction] {:account-id (:id cash) :direction direction :amount (sgd 300)})]
    (repo/post-entry! tdb/*pool*
                      {:id (random-uuid)
                       :organisation-id (:id org)
                       :occurred-at (t "2026-02-10T00:00:00Z")
                       :narrative "Many movements"
                       :reference {:type :opening-balance :id (random-uuid)}
                       :lines (into (vec (repeat per-side (line :debit)))
                                    (repeat per-side (line :credit)))})
    (let [{:keys [movements truncated? closing-balance opening-balance]}
          (repo/statement tdb/*pool* cash {:from (t "2026-02-01T00:00:00Z")
                                           :to   (t "2026-03-01T00:00:00Z")})
          shown (money/sum "SGD" (map (fn [m] (account/signed-amount
                                               (:type cash) (:direction m) (:amount m)))
                                      movements))]
      (is (true? truncated?))
      (is (= repo/row-cap (count movements)) "capped, not unbounded")
      (is (= (sgd 0) opening-balance))

      (testing "the closing balance is aggregated over the journal, not summed from the rows"
        (is (= (sgd 0) closing-balance)
            "251 debits and 251 credits of the same amount net to zero")
        (is (= (sgd 600) shown)
            "but the 500 movements returned do not — they are 251 debits and 249 credits")
        (is (not= closing-balance (:running-balance (last movements)))
            "so the last running balance is not a closing figure, which is why `truncated` exists"))

      (testing "the movements returned are the earliest, in line order"
        (is (= (range 1 (inc repo/row-cap)) (map :line-no movements)))))))

(deftest a-statement-period-must-not-end-before-it-begins
  (let [{:keys [cash]} (ledger-fixture)]
    (is (= :validation
           (:clofin/error
            (unprocessable #(repo/statement tdb/*pool* cash
                                            {:from (t "2026-03-01T00:00:00Z")
                                             :to   (t "2026-02-01T00:00:00Z")})))))))

(deftest an-empty-period-is-legal-and-reports-no-movement
  (let [{:keys [cash payable] :as fixture} (ledger-fixture)
        moment (t "2026-02-01T00:00:00Z")]
    (post! fixture {:from payable :to cash :amount (sgd 5000)
                    :occurred-at (t "2026-01-01T00:00:00Z")})
    (let [{:keys [movements opening-balance closing-balance]}
          (repo/statement tdb/*pool* cash {:from moment :to moment})]
      (is (empty? movements))
      (is (= opening-balance closing-balance))
      (is (= (sgd 5000) closing-balance)))))

;; ---------------------------------------------------------------------------
;; Reading entries
;; ---------------------------------------------------------------------------

(deftest entries-for-an-account-come-back-most-recent-first
  (let [{:keys [org cash payable] :as fixture} (ledger-fixture)
        other (new-account! org "4100-FEE-INCOME" :revenue)]
    (post! fixture {:from payable :to cash :amount (sgd 100)
                    :occurred-at (t "2026-01-01T00:00:00Z") :narrative "First"})
    (post! fixture {:from payable :to cash :amount (sgd 200)
                    :occurred-at (t "2026-02-01T00:00:00Z") :narrative "Second"})
    (post! fixture {:from other :to payable :amount (sgd 300)
                    :occurred-at (t "2026-03-01T00:00:00Z") :narrative "Does not touch cash"})

    (let [entries (repo/list-entries-for-account tdb/*pool* (:id org) (:id cash))]
      (is (= ["Second" "First"] (mapv :narrative entries)))
      (is (every? #(= 2 (count (:lines %))) entries)
          "each entry carries its lines, not just its header"))))

(deftest an-entry-is-invisible-to-another-organisation
  (let [{:keys [org] :as fixture} (ledger-fixture)
        theirs (new-organisation! "kestrel")
        posted (post! fixture {:from (:payable fixture) :to (:cash fixture)
                               :amount (sgd 100) :occurred-at jan})]
    (is (some? (repo/find-entry tdb/*pool* (:id org) (:id posted))))
    (is (nil? (repo/find-entry tdb/*pool* (:id theirs) (:id posted))))))

(deftest a-missing-entry-is-nil-rather-than-an-error
  (let [{:keys [org]} (ledger-fixture)]
    (is (nil? (repo/find-entry tdb/*pool* (:id org) (random-uuid))))))

;; ---------------------------------------------------------------------------
;; Transactionality
;; ---------------------------------------------------------------------------

(deftest posting-joins-a-transaction-the-caller-already-owns
  (testing "so a later increment can post an entry as part of a larger unit of work"
    (let [{:keys [org cash payable]} (ledger-fixture)
          entry-id (random-uuid)]
      (is (thrown? Exception
                   (db/with-transaction [tx tdb/*pool*]
                     (repo/post-entry! tx
                                       {:id entry-id
                                        :organisation-id (:id org)
                                        :occurred-at jan
                                        :narrative "Rolled back with its caller"
                                        :reference {:type :opening-balance :id (random-uuid)}
                                        :lines (entry/transfer-lines
                                                {:from-account-id (:id payable)
                                                 :to-account-id (:id cash)
                                                 :amount (sgd 500)})})
                     ;; The caller fails after the entry is written. The entry
                     ;; must go with it.
                     (throw (ex-info "caller failed after posting" {})))))
      (is (zero? (entry-count))
          "the entry rolled back with the transaction it joined")
      (is (zero? (line-count))))))
