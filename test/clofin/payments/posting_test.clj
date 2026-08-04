(ns clofin.payments.posting-test
  "Posting templates against the worked example in `DOMAIN_MODEL.md` §4.

  | Step | Debit | Credit | Amount |
  |---|---|---|---|
  | Release | `1300-IN-TRANSIT` | `1100-CLIENT-FUNDS` | 1,250.00 |
  | Fee     | `2100-CLIENT-PAYABLE` | `4100-FEE-INCOME` | 5.00 |

  Each row is one entry, balancing on its own. A template that produced one
  combined entry would make the fee unreadable on a statement and unreversible
  on its own."
  (:require [clofin.ledger.entry :as entry]
            [clofin.money :as money]
            [clofin.payments.posting :as posting]
            [clojure.test :refer [deftest is testing]]))

(def ^:private accounts
  "The chart of accounts, resolved to ids by whoever looked them up."
  (into {} (map (fn [role] [role (random-uuid)])) (keys posting/account-roles)))

(def ^:private instruction
  {:id              (random-uuid)
   :organisation-id (random-uuid)
   :creditor-name   "Pacific Rim Logistics Pte Ltd"
   :amount          (money/of "SGD" 125000)})

(def ^:private occurred-at #inst "2026-08-03T10:15:00.000-00:00")

(defn- side
  "The account and amount on one side of a two-line movement."
  [lines direction]
  (let [line (first (filter #(= direction (:direction %)) lines))]
    [(:account-id line) (:amount line)]))

;; ---------------------------------------------------------------------------
;; The two movements
;; ---------------------------------------------------------------------------

(deftest a-release-debits-in-transit-and-credits-client-funds
  (let [lines (posting/release-lines accounts (money/of "SGD" 125000))]
    (is (= 2 (count lines)))
    (is (= [(accounts :in-transit) (money/of "SGD" 125000)] (side lines :debit)))
    (is (= [(accounts :client-funds) (money/of "SGD" 125000)] (side lines :credit)))
    (testing "and it balances on its own"
      (is (entry/balanced? lines)))))

(deftest a-fee-debits-client-payable-and-credits-fee-income
  (let [lines (posting/fee-lines accounts (money/of "SGD" 500))]
    (is (= [(accounts :client-payable) (money/of "SGD" 500)] (side lines :debit)))
    (is (= [(accounts :fee-income) (money/of "SGD" 500)] (side lines :credit)))
    (is (entry/balanced? lines))))

(deftest a-movement-must-be-for-a-positive-amount
  (is (thrown? Exception (posting/release-lines accounts (money/of "SGD" 0))))
  (is (thrown? Exception (posting/release-lines accounts (money/of "SGD" -1))))
  (is (thrown? Exception (posting/fee-lines accounts (money/of "SGD" 0)))))

(deftest a-missing-account-is-named-by-the-role-and-the-code-it-needs
  (let [t (try (posting/release-lines (dissoc accounts :in-transit) (money/of "SGD" 100))
               (catch Exception e e))]
    (is (= :validation (:clofin/error (ex-data t))))
    (is (= "in-transit" (:role (ex-data t))))
    (is (= "1300-IN-TRANSIT" (:code (ex-data t)))
        "the chart-of-accounts code, so the message is actionable without the source")))

;; ---------------------------------------------------------------------------
;; The worked example, end to end
;; ---------------------------------------------------------------------------

(deftest the-worked-example-produces-two-balanced-entries
  (let [ids [(random-uuid) (random-uuid)]
        entries (posting/release-entries instruction
                                         {:accounts accounts
                                          :fee (money/of "SGD" 500)
                                          :entry-ids ids
                                          :occurred-at occurred-at})]
    (is (= 2 (count entries)))

    (testing "each entry balances on its own — not merely in aggregate"
      (doseq [e entries]
        (is (entry/balanced? (:lines e)))))

    (testing "the release moves the full 1,250.00"
      (is (= (money/of "SGD" 125000) (entry/total (first entries) "SGD"))))

    (testing "the fee is a separate entry for 5.00"
      (is (= (money/of "SGD" 500) (entry/total (second entries) "SGD"))))

    (testing "every entry explains itself back to the instruction that caused it"
      (doseq [e entries]
        (is (= {:type :payment-instruction :id (:id instruction)} (:reference e)))
        (is (= (:organisation-id instruction) (:organisation-id e)))
        (is (= occurred-at (:occurred-at e)))))

    (testing "the narrative names the instruction and the counterparty"
      (is (= (str "Payment instruction " (:id instruction)
                  " released to Pacific Rim Logistics Pte Ltd")
             (:narrative (first entries))))
      (is (= (str "Fee on payment instruction " (:id instruction))
             (:narrative (second entries)))))

    (testing "the ids supplied are the ids used — a template generates none"
      (is (= ids (mapv :id entries))))))

(deftest a-release-with-no-fee-produces-one-entry
  (let [entries (posting/release-entries instruction
                                         {:accounts accounts
                                          :entry-ids [(random-uuid)]
                                          :occurred-at occurred-at})]
    (is (= 1 (count entries)))
    (is (entry/balanced? (:lines (first entries))))))

(deftest supplying-the-wrong-number-of-entry-ids-is-refused
  (testing "the count is a property of the template, so a mismatch is a defect
            in the caller rather than a silently short posting"
    (is (thrown? Exception
                 (posting/release-entries instruction
                                          {:accounts accounts
                                           :fee (money/of "SGD" 500)
                                           :entry-ids [(random-uuid)]
                                           :occurred-at occurred-at})))
    (is (thrown? Exception
                 (posting/release-entries instruction
                                          {:accounts accounts
                                           :entry-ids [(random-uuid) (random-uuid)]
                                           :occurred-at occurred-at})))))

(deftest the-template-refuses-an-entry-the-ledger-would-refuse
  (testing "entries are built through `entry/entry`, so the zero-sum invariant
            and every other rule are checked here too rather than at the database"
    (is (thrown? Exception
                 (posting/release-entries (assoc instruction :organisation-id "not-a-uuid")
                                          {:accounts accounts
                                           :entry-ids [(random-uuid)]
                                           :occurred-at occurred-at})))))

(deftest a-fee-in-another-currency-is-still-its-own-balanced-entry
  (testing "each entry balances per currency; two entries need not share one"
    (let [entries (posting/release-entries instruction
                                           {:accounts accounts
                                            :fee (money/of "USD" 400)
                                            :entry-ids [(random-uuid) (random-uuid)]
                                            :occurred-at occurred-at})]
      (is (= #{"SGD"} (set (entry/currencies (first entries)))))
      (is (= #{"USD"} (set (entry/currencies (second entries)))))
      (doseq [e entries] (is (entry/balanced? (:lines e)))))))

;; ---------------------------------------------------------------------------
;; The chart of accounts
;; ---------------------------------------------------------------------------

(deftest the-roles-name-the-codes-domain-model-4-defines
  (is (= {:client-funds   "1100-CLIENT-FUNDS"
          :nostro         "1200-NOSTRO"
          :in-transit     "1300-IN-TRANSIT"
          :client-payable "2100-CLIENT-PAYABLE"
          :fee-income     "4100-FEE-INCOME"
          :scheme-charges "5100-SCHEME-CHARGES"}
         posting/account-roles)))
