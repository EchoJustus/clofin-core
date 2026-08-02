(ns clofin.api.ledger-api-test
  "The ledger API end to end, without a socket.

  These call the fully-wrapped handler — router, middleware, error translation,
  JSON codec — with a request map and assert on the response. That is the whole
  stack a caller meets, minus Jetty, which `clofin.system-test` covers
  separately (ADR-0010).

  The database is real, because the acceptance criteria are statements about
  what is persisted and what is not.

  Acceptance criteria from docs/briefs/001-TASK-ledger-persistence-and-account-api.md
  are named in the tests that cover them."
  (:require [clofin.db.core :as db]
            [clofin.system :as system]
            [clofin.test-db :as tdb]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(use-fixtures :once tdb/with-pool tdb/with-migrated-schema)
(use-fixtures :each tdb/with-clean-data)

;; ---------------------------------------------------------------------------
;; Calling the API
;; ---------------------------------------------------------------------------

(defn- handler []
  (system/handler {:config {:environment :test} :pool tdb/*pool*}))

(defn- call
  "Issue a request and decode the response body."
  [method uri & {:keys [body query]}]
  (let [response ((handler)
                  (cond-> {:request-method method
                           :uri uri
                           :headers {}}
                    query (assoc :query-string query)
                    body  (-> (assoc-in [:headers "content-type"] "application/json")
                              (assoc :body (ByteArrayInputStream.
                                            (.getBytes (json/write-str body)
                                                       StandardCharsets/UTF_8))))))]
    (assoc response :json (when-not (str/blank? (:body response))
                            (json/read-str (:body response))))))

(defn- created!
  "Issue a POST expected to succeed, and return the created document."
  [uri body]
  (let [{:keys [status json] :as response} (call :post uri :body body)]
    (is (= 201 status) (str "expected 201 from " uri ", body was " (:body response)))
    (is (some? (get-in response [:headers "location"])) "a 201 must say where the resource is")
    json))

;; ---------------------------------------------------------------------------
;; Fixtures, built through the API itself
;; ---------------------------------------------------------------------------

(defn- new-organisation!
  ([] (new-organisation! "meridian"))
  ([short-name]
   (created! "/organisations" {"legalName" "Meridian Freight Holdings Pte Ltd"
                               "shortName" short-name})))

(defn- new-account!
  [org code type & {:keys [currency] :or {currency "SGD"}}]
  (created! "/accounts" {"organisationId" (get org "id")
                         "code" code
                         "name" (str "Account " code)
                         "type" type
                         "currency" currency}))

(defn- money [currency minor] {"currency" currency "minorUnits" minor})

(defn- transfer
  "A two-line entry body: debit `to`, credit `from`."
  [org {:keys [from to amount occurred-at narrative reference]}]
  {"organisationId" (get org "id")
   "occurredAt" occurred-at
   "narrative" (or narrative "Test movement")
   "reference" (or reference {"type" "opening-balance" "id" (str (random-uuid))})
   "lines" [{"accountId" (get to "id")   "direction" "debit"  "amount" amount}
            {"accountId" (get from "id") "direction" "credit" "amount" amount}]})

(defn- ledger-fixture []
  (let [org (new-organisation!)]
    {:org org
     :cash (new-account! org "1100-CLIENT-FUNDS" "asset")
     :payable (new-account! org "2100-CLIENT-PAYABLE" "liability")}))

(defn- entry-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from journal_entry"])))

(defn- line-count []
  (:count (db/query-one tdb/*pool* ["select count(*) as count from journal_line"])))

;; ---------------------------------------------------------------------------
;; Organisations and accounts
;; ---------------------------------------------------------------------------

(deftest an-organisation-can-be-created-and-read-back
  (let [org (new-organisation!)
        {:keys [status json]} (call :get (str "/organisations/" (get org "id")))]
    (is (= 200 status))
    (is (= "Meridian Freight Holdings Pte Ltd" (get json "legalName")))
    (is (= "active" (get json "status")))))

(deftest a-duplicate-short-name-is-a-conflict
  (new-organisation! "meridian")
  (let [{:keys [status json]} (call :post "/organisations"
                                    :body {"legalName" "Another Entity Pte Ltd"
                                           "shortName" "meridian"})]
    (is (= 409 status))
    (is (= "https://clofin.dev/problems/conflict" (get json "type")))))

(deftest an-account-is-created-active-and-reports-its-normal-balance
  (let [org (new-organisation!)
        acct (new-account! org "1100-CLIENT-FUNDS" "asset")]
    (is (= "active" (get acct "status")))
    (is (= "debit" (get acct "normalBalance"))
        "the normal balance is what makes the sign of a later figure readable")
    (testing "a liability account carries the other convention"
      (is (= "credit" (get (new-account! org "2100-CLIENT-PAYABLE" "liability")
                           "normalBalance"))))))

(deftest the-location-header-of-a-created-account-actually-resolves
  (testing "a 201 that points nowhere is a broken contract"
    (let [org (new-organisation!)
          {:keys [status headers]} (call :post "/accounts"
                                         :body {"organisationId" (get org "id")
                                                "code" "1100-CLIENT-FUNDS"
                                                "name" "Client funds"
                                                "type" "asset"
                                                "currency" "SGD"})
          location (get headers "location")
          [path query] (str/split location #"\?")]
      (is (= 201 status))
      (is (= 200 (:status (call :get path :query query)))))))

(deftest an-account-is-listed-in-its-organisations-chart
  (let [{:keys [org]} (ledger-fixture)
        {:keys [status json]} (call :get "/accounts"
                                    :query (str "organisationId=" (get org "id")))]
    (is (= 200 status))
    (is (= 2 (get json "count")))
    (is (= ["1100-CLIENT-FUNDS" "2100-CLIENT-PAYABLE"]
           (mapv #(get % "code") (get json "accounts"))))
    (is (= 500 (get json "limit")) "the cap is stated rather than left to be discovered")))

(deftest another-organisations-account-is-not-found
  (let [{:keys [cash]} (ledger-fixture)
        theirs (new-organisation! "kestrel")
        {:keys [status json]} (call :get (str "/accounts/" (get cash "id"))
                                    :query (str "organisationId=" (get theirs "id")))]
    (is (= 404 status) "the same answer a non-existent id receives")
    (is (= "https://clofin.dev/problems/not-found" (get json "type")))))

(deftest a-request-without-an-organisation-is-rejected
  (testing "the organisation is not optional just because it is not yet authenticated"
    (is (= 400 (:status (call :get "/accounts"))))))

;; ---------------------------------------------------------------------------
;; Posting — AC-1, AC-2
;; ---------------------------------------------------------------------------

(deftest ac-1-a-balanced-entry-is-created-and-both-lines-are-persisted
  (let [{:keys [org cash payable]} (ledger-fixture)
        {:keys [status headers json]}
        (call :post "/journal-entries"
              :body (transfer org {:from payable :to cash
                                   :amount (money "SGD" 125000)
                                   :occurred-at "2026-08-02T10:15:00Z"
                                   :narrative "Opening balance"}))]
    (is (= 201 status))
    (is (str/starts-with? (get headers "location") "/journal-entries/"))
    (is (= "Opening balance" (get json "narrative")))
    (is (= 2 (count (get json "lines"))))
    (is (= 1 (entry-count)))
    (is (= 2 (line-count)) "both lines, not just the first")

    (testing "the posted entry is retrievable at the location it reported"
      (let [[path query] (str/split (get headers "location") #"\?")
            {:keys [status json]} (call :get path :query query)]
        (is (= 200 status))
        (is (= "Opening balance" (get json "narrative")))
        (is (some? (get json "recordedAt"))
            "read back from the journal, so it carries when CloFin was told")))))

(deftest ac-2-an-unbalanced-entry-is-422-and-persists-nothing
  (let [{:keys [org cash payable]} (ledger-fixture)
        {:keys [status json]}
        (call :post "/journal-entries"
              :body {"organisationId" (get org "id")
                     "occurredAt" "2026-08-02T10:15:00Z"
                     "narrative" "Deliberately unbalanced"
                     "reference" {"type" "opening-balance" "id" (str (random-uuid))}
                     "lines" [{"accountId" (get cash "id") "direction" "debit"
                               "amount" (money "SGD" 125000)}
                              {"accountId" (get payable "id") "direction" "credit"
                               "amount" (money "SGD" 100000)}]})]
    (is (= 422 status) "understood, and cannot be carried out")
    (is (= "https://clofin.dev/problems/unprocessable" (get json "type")))
    (testing "the body names the shortfall per currency"
      (is (= {"SGD" "250.00"} (get-in json ["errors" "imbalance"]))))
    (testing "nothing is persisted"
      (is (zero? (entry-count)))
      (is (zero? (line-count))))))

(deftest ac-2-an-entry-unbalanced-in-only-one-of-two-currencies-names-that-currency
  (let [org (new-organisation!)
        sgd-a (new-account! org "1100-SGD-FUNDS" "asset" :currency "SGD")
        sgd-b (new-account! org "2100-SGD-PAYABLE" "liability" :currency "SGD")
        jpy-a (new-account! org "1200-JPY-FUNDS" "asset" :currency "JPY")
        jpy-b (new-account! org "2200-JPY-PAYABLE" "liability" :currency "JPY")
        {:keys [status json]}
        (call :post "/journal-entries"
              :body {"organisationId" (get org "id")
                     "occurredAt" "2026-08-02T10:15:00Z"
                     "narrative" "SGD balances, JPY does not"
                     "reference" {"type" "opening-balance" "id" (str (random-uuid))}
                     "lines" [{"accountId" (get sgd-a "id") "direction" "debit"
                               "amount" (money "SGD" 100)}
                              {"accountId" (get sgd-b "id") "direction" "credit"
                               "amount" (money "SGD" 100)}
                              {"accountId" (get jpy-a "id") "direction" "debit"
                               "amount" (money "JPY" 500)}
                              {"accountId" (get jpy-b "id") "direction" "credit"
                               "amount" (money "JPY" 400)}]})]
    (is (= 422 status))
    (is (= {"JPY" "100"} (get-in json ["errors" "imbalance"]))
        "only the currency that does not balance, and at JPY's own scale")))

;; ---------------------------------------------------------------------------
;; Posting rules needing database state — AC-4, AC-7
;; ---------------------------------------------------------------------------

(deftest ac-4-an-entry-referencing-a-frozen-account-is-422-naming-the-account
  (let [{:keys [org cash payable]} (ledger-fixture)]
    ;; Freezing is not an API operation yet — TASK-003 owns account lifecycle.
    (db/execute! tdb/*pool* ["update ledger_account set status = 'frozen' where id = ?"
                             (java.util.UUID/fromString (get cash "id"))])
    (let [{:keys [status json]}
          (call :post "/journal-entries"
                :body (transfer org {:from payable :to cash
                                     :amount (money "SGD" 5000)
                                     :occurred-at "2026-08-02T10:15:00Z"}))]
      (is (= 422 status))
      (is (= [{"id" (get cash "id") "code" "1100-CLIENT-FUNDS" "status" "frozen"}]
             (get-in json ["errors" "accounts"]))
          "the caller is told which account to go and unfreeze")
      (is (zero? (entry-count))))))

(deftest ac-7-an-entry-referencing-another-organisations-account-is-422
  (let [{:keys [org payable]} (ledger-fixture)
        theirs  (new-organisation! "kestrel")
        outside (new-account! theirs "9100-THEIR-FUNDS" "asset")
        {:keys [status json]}
        (call :post "/journal-entries"
              :body (transfer org {:from payable :to outside
                                   :amount (money "SGD" 5000)
                                   :occurred-at "2026-08-02T10:15:00Z"}))]
    (is (= 422 status))
    (is (= [(get outside "id")] (get-in json ["errors" "account-ids"])))
    (testing "the reply does not confirm the id belongs to another tenant"
      (is (re-find #"do not exist in this organisation" (get json "detail"))))
    (is (zero? (entry-count)))))

;; ---------------------------------------------------------------------------
;; Multiple currencies — AC-8
;; ---------------------------------------------------------------------------

(deftest ac-8-an-entry-spanning-currencies-that-balance-within-each-succeeds
  (let [org (new-organisation!)
        sgd-a (new-account! org "1100-SGD-FUNDS" "asset" :currency "SGD")
        sgd-b (new-account! org "2100-SGD-PAYABLE" "liability" :currency "SGD")
        jpy-a (new-account! org "1200-JPY-FUNDS" "asset" :currency "JPY")
        jpy-b (new-account! org "2200-JPY-PAYABLE" "liability" :currency "JPY")
        {:keys [status json]}
        (call :post "/journal-entries"
              :body {"organisationId" (get org "id")
                     "occurredAt" "2026-08-02T10:15:00Z"
                     "narrative" "Two currencies, balanced within each"
                     "reference" {"type" "opening-balance" "id" (str (random-uuid))}
                     "lines" [{"accountId" (get sgd-a "id") "direction" "debit"
                               "amount" (money "SGD" 125000)}
                              {"accountId" (get sgd-b "id") "direction" "credit"
                               "amount" (money "SGD" 125000)}
                              {"accountId" (get jpy-a "id") "direction" "debit"
                               "amount" (money "JPY" 125000)}
                              {"accountId" (get jpy-b "id") "direction" "credit"
                               "amount" (money "JPY" 125000)}]})]
    (is (= 201 status))
    (is (= 4 (count (get json "lines"))))
    (is (= 4 (line-count)))
    (testing "JPY has no minor unit, so the same integer means a different amount"
      (is (= 125000 (get-in json ["lines" 2 "amount" "minorUnits"]))))))

;; ---------------------------------------------------------------------------
;; Statements — AC-5
;; ---------------------------------------------------------------------------

(deftest ac-5-a-statement-adds-up-over-http
  (let [{:keys [org cash payable]} (ledger-fixture)
        post! (fn [amount occurred-at narrative from to]
                (created! "/journal-entries"
                          (transfer org {:from from :to to
                                         :amount (money "SGD" amount)
                                         :occurred-at occurred-at
                                         :narrative narrative})))]
    (post! 100000 "2026-01-15T00:00:00Z" "Before the period" payable cash)
    (post! 25000  "2026-02-05T00:00:00Z" "Deposit"           payable cash)
    (post! 5000   "2026-02-10T00:00:00Z" "Withdrawal"        cash    payable)
    (post! 1000   "2026-02-20T00:00:00Z" "Interest"          payable cash)
    (post! 999999 "2026-03-05T00:00:00Z" "After the period"  payable cash)

    (let [{:keys [status json]}
          (call :get (str "/accounts/" (get cash "id") "/statement")
                :query (str "organisationId=" (get org "id")
                            "&from=2026-02-01T00:00:00Z"
                            "&to=2026-03-01T00:00:00Z"))
          movements (get json "movements")
          opening (get-in json ["openingBalance" "minorUnits"])
          closing (get-in json ["closingBalance" "minorUnits"])
          net (reduce + 0 (map (fn [m] (if (= "debit" (get m "direction"))
                                         (get-in m ["amount" "minorUnits"])
                                         (- (get-in m ["amount" "minorUnits"]))))
                               movements))]
      (is (= 200 status))
      (is (= 3 (count movements)) "only the movements inside the period")
      (is (= 100000 opening))
      (is (= 121000 closing))

      (testing "opening + sum(movements) = closing"
        (is (= closing (+ opening net))))

      (testing "the last running balance equals the closing balance"
        (is (= closing (get-in (last movements) ["runningBalance" "minorUnits"]))))

      (testing "the period is reported back, so a reader knows what was asked"
        (is (= "2026-02-01T00:00:00Z" (get json "from")))
        (is (= "2026-03-01T00:00:00Z" (get json "to")))
        (is (false? (get json "truncated")))
        (is (= 500 (get json "movementCap"))))

      (testing "movements are self-describing"
        (is (= ["Deposit" "Withdrawal" "Interest"] (mapv #(get % "narrative") movements)))
        (is (= ["debit" "credit" "debit"] (mapv #(get % "direction") movements)))
        (testing "lineNo locates the movement within its own entry"
          ;; The withdrawal debits the payable account and credits cash, so the
          ;; line this statement is about is the second of that entry, not the
          ;; first. Without lineNo a reader could not tell which line of a
          ;; multi-line entry a movement refers to.
          (is (= [1 2 1] (mapv #(get % "lineNo") movements))))))))

(deftest a-statement-requires-an-explicit-period
  (let [{:keys [org cash]} (ledger-fixture)]
    (testing "a statement whose period the caller did not choose is not a statement"
      (is (= 400 (:status (call :get (str "/accounts/" (get cash "id") "/statement")
                                :query (str "organisationId=" (get org "id"))))))
      (is (= 400 (:status (call :get (str "/accounts/" (get cash "id") "/statement")
                                :query (str "organisationId=" (get org "id")
                                            "&from=2026-02-01T00:00:00Z"))))))))

(deftest a-statement-rejects-a-time-without-a-zone
  (testing "an occurrence time without an offset is ambiguous by exactly the amount
            that makes a period wrong at its boundaries"
    (let [{:keys [org cash]} (ledger-fixture)
          {:keys [status json]}
          (call :get (str "/accounts/" (get cash "id") "/statement")
                :query (str "organisationId=" (get org "id")
                            "&from=2026-02-01"
                            "&to=2026-03-01T00:00:00Z"))]
      (is (= 400 status))
      (is (= "from" (get-in json ["errors" "field"]))))))

;; ---------------------------------------------------------------------------
;; Reversal — AC-6
;; ---------------------------------------------------------------------------

(deftest ac-6-a-reversal-posted-over-http-returns-the-balance
  (let [{:keys [org cash payable]} (ledger-fixture)
        original (created! "/journal-entries"
                           (transfer org {:from payable :to cash
                                          :amount (money "SGD" 125000)
                                          :occurred-at "2026-08-02T10:15:00Z"
                                          :narrative "Mistaken posting"}))
        balance (fn []
                  (get-in (:json (call :get (str "/accounts/" (get cash "id") "/statement")
                                       :query (str "organisationId=" (get org "id")
                                                   "&from=2026-01-01T00:00:00Z"
                                                   "&to=2027-01-01T00:00:00Z")))
                          ["closingBalance" "minorUnits"]))
        reversal-body (transfer org {;; Every direction flipped, amounts unchanged.
                                     :from cash :to payable
                                     :amount (money "SGD" 125000)
                                     :occurred-at "2026-08-03T10:15:00Z"
                                     :narrative "Reversal of the mistaken posting"
                                     :reference {"type" "reversal" "id" (get original "id")}})]
    (is (= 125000 (balance)))
    (let [reversal (created! "/journal-entries" reversal-body)]
      (is (= 0 (balance)) "the account is back where it started")

      (testing "both the error and the correction remain visible"
        (is (= 200 (:status (call :get (str "/journal-entries/" (get original "id"))
                                  :query (str "organisationId=" (get org "id"))))))
        (is (= 200 (:status (call :get (str "/journal-entries/" (get reversal "id"))
                                  :query (str "organisationId=" (get org "id"))))))
        (is (= 2 (entry-count))))

      (testing "an entry can be reversed only once"
        (is (= 409 (:status (call :post "/journal-entries" :body reversal-body)))
            "a second reversal would silently reapply the original movement")))))

;; ---------------------------------------------------------------------------
;; Malformed requests are 400, not 422 and not 500
;; ---------------------------------------------------------------------------

(deftest a-malformed-request-is-a-400-naming-the-field
  (let [{:keys [org cash payable]} (ledger-fixture)
        valid (transfer org {:from payable :to cash
                             :amount (money "SGD" 100)
                             :occurred-at "2026-08-02T10:15:00Z"})]
    (testing "a missing field"
      (let [{:keys [status json]} (call :post "/journal-entries"
                                        :body (dissoc valid "narrative"))]
        (is (= 400 status))
        (is (= "narrative" (get-in json ["errors" "field"])))))

    (testing "an identifier that is not a UUID"
      (is (= 400 (:status (call :post "/journal-entries"
                                :body (assoc valid "organisationId" "not-a-uuid"))))))

    (testing "an occurrence time that is not an instant"
      (is (= 400 (:status (call :post "/journal-entries"
                                :body (assoc valid "occurredAt" "yesterday"))))))

    (testing "an unknown reference type"
      (is (= 400 (:status (call :post "/journal-entries"
                                :body (assoc valid "reference"
                                             {"type" "vibes" "id" (str (random-uuid))}))))))

    (testing "a single-line entry"
      (is (= 400 (:status (call :post "/journal-entries"
                                :body (update valid "lines" (comp vector first)))))))

    (testing "an amount that is not an integer — money is never a float"
      (is (= 400 (:status (call :post "/journal-entries"
                                :body (assoc-in valid ["lines" 0 "amount" "minorUnits"] 12.5))))))

    (testing "a negative line amount, because direction carries the sign"
      (is (= 400 (:status (call :post "/journal-entries"
                                :body (assoc-in valid ["lines" 0 "amount" "minorUnits"] -100))))))

    (testing "a body that is not an object at all"
      (is (= 400 (:status (call :post "/journal-entries" :body ["not" "an" "object"])))))

    (is (zero? (entry-count)) "no malformed request reached the journal")))

(deftest an-unknown-route-and-method-still-behave
  (let [{:keys [org cash]} (ledger-fixture)]
    (is (= 404 (:status (call :get "/accounts/not-a-uuid/nope"
                              :query (str "organisationId=" (get org "id"))))))
    (testing "a known path under an unsupported method is 405, not 404"
      (let [{:keys [status headers]} (call :delete (str "/accounts/" (get cash "id")))]
        (is (= 405 status))
        (is (str/includes? (get headers "allow") "GET"))))))
