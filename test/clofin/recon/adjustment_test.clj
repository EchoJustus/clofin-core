(ns clofin.recon.adjustment-test
  "How many approvals an adjustment needs, and the entry it posts.

  The boundary is asserted **at the boundary** — `boundary − 1`, `boundary`,
  `boundary + 1` — because a rule that has to be guessed is a rule the next
  reader guesses differently, and because of the two possible readings of an
  inclusive bound only one asks for more scrutiny."
  (:require [clofin.ledger.entry :as entry]
            [clofin.money :as money]
            [clofin.recon.adjustment :as adjustment]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]))

(defn- sgd [minor] (money/of "SGD" minor))

(def ^:private bands
  "An organisation that wants a second pair of eyes from SGD 1,000.00, and two
  from SGD 10,000.00."
  [{:from-minor 100000 :approvals-required 1}
   {:from-minor 1000000 :approvals-required 2}])

;; ---------------------------------------------------------------------------
;; How many approvals
;; ---------------------------------------------------------------------------

(deftest the-de-minimis-is-the-organisations-own-lowest-band
  (is (= 100000 (adjustment/de-minimis-minor bands)))
  (is (= 100000 (adjustment/de-minimis-minor (reverse bands)))
      "read as the lowest, not as the first the caller happened to list")
  (is (nil? (adjustment/de-minimis-minor []))))

(deftest ac-5-the-boundary-is-inclusive-and-is-tested-at-the-boundary
  (testing "below it, the proposer alone may post"
    (is (= 0 (adjustment/approvals-required bands (sgd 99999))))
    (is (= 0 (adjustment/approvals-required bands (sgd 1))))
    (is (false? (adjustment/needs-approval? bands (sgd 99999)))))
  (testing "at it, approval is required — of the two readings of an inclusive
            bound, the one that asks for more scrutiny"
    (is (= 1 (adjustment/approvals-required bands (sgd 100000))))
    (is (true? (adjustment/needs-approval? bands (sgd 100000)))))
  (testing "and above it, the band that covers the amount decides"
    (is (= 1 (adjustment/approvals-required bands (sgd 100001))))
    (is (= 1 (adjustment/approvals-required bands (sgd 999999))))
    (is (= 2 (adjustment/approvals-required bands (sgd 1000000))))
    (is (= 2 (adjustment/approvals-required bands (sgd 50000000))))))

(deftest an-organisation-with-no-band-in-the-currency-cannot-adjust-at-all
  (testing "treating `unconfigured` as `needs nobody` is how a control silently
            weakens in exactly the organisation that has thought least about it"
    (is (nil? (adjustment/approvals-required [] (sgd 125000))))
    (is (false? (adjustment/needs-approval? [] (sgd 125000)))
        "and the predicate answers false rather than true, so a caller that
         forgot to check for nil cannot post — the service refuses on the nil")))

(deftest an-organisation-whose-floor-is-zero-has-no-de-minimis
  (testing "which is the stricter setting, and is what the payments fixtures
            already configure"
    (let [floor-at-zero [{:from-minor 0 :approvals-required 1}]]
      (is (= 0 (adjustment/de-minimis-minor floor-at-zero)))
      (is (= 1 (adjustment/approvals-required floor-at-zero (sgd 1))))
      (is (true? (adjustment/needs-approval? floor-at-zero (sgd 1)))))))

;; ---------------------------------------------------------------------------
;; The entry
;; ---------------------------------------------------------------------------

(def ^:private in-transit (random-uuid))
(def ^:private unapplied  (random-uuid))
(def ^:private accounts {:reconciled in-transit :suspense unapplied})

(defn- adjustment
  [& {:keys [direction amount] :or {direction :credit amount (sgd 125000)}}]
  {:id (random-uuid) :organisation-id (random-uuid)
   :amount amount :direction direction
   :narrative "The scheme reports a settlement CloFin never posted"})

(defn- posted
  [adj]
  (adjustment/adjustment-entry adj {:accounts accounts
                                    :entry-id (random-uuid)
                                    :occurred-at (Instant/parse "2026-08-14T09:00:00Z")}))

(deftest the-direction-applies-to-the-reconciled-account-and-the-other-leg-follows
  (testing "credit the clearing account"
    (let [lines (:lines (posted (adjustment :direction :credit)))
          by-account (into {} (map (juxt :account-id :direction)) lines)]
      (is (= :credit (by-account in-transit)))
      (is (= :debit (by-account unapplied)))))
  (testing "debit it"
    (let [lines (:lines (posted (adjustment :direction :debit)))
          by-account (into {} (map (juxt :account-id :direction)) lines)]
      (is (= :debit (by-account in-transit)))
      (is (= :credit (by-account unapplied))))))

(deftest the-entry-balances-by-construction-rather-than-by-the-caller
  (doseq [direction [:debit :credit]]
    (let [posted-entry (posted (adjustment :direction direction))]
      (is (= 2 (count (:lines posted-entry))))
      (is (empty? (entry/imbalance (:lines posted-entry)))
          "the suspense leg is always the opposite of the reconciled one")
      (is (= [(sgd 125000) (sgd 125000)] (mapv :amount (:lines posted-entry)))))))

(deftest the-entry-names-the-adjustment-that-caused-it
  (let [adj (adjustment)
        posted-entry (posted adj)]
    (is (= :reconciliation-adjustment (get-in posted-entry [:reference :type]))
        "a reference type the ledger has carried since it was written, and this
         is the increment that finally raises one")
    (is (= (:id adj) (get-in posted-entry [:reference :id])))
    (is (= (:narrative adj) (:narrative posted-entry)))))

(deftest an-adjustment-cannot-be-posted-without-both-accounts
  (doseq [[label partial-accounts expected-code]
          [["no suspense"   {:reconciled in-transit} "2200-UNAPPLIED"]
           ["no reconciled" {:suspense unapplied}    "1300-IN-TRANSIT"]
           ["neither"       {}                       "1300-IN-TRANSIT"]]]
    (let [t (try (adjustment/adjustment-entry
                  (adjustment) {:accounts partial-accounts
                                :entry-id (random-uuid)
                                :occurred-at (Instant/now)})
                 nil (catch Exception e e))]
      (is (some? t) label)
      (is (str/includes? (ex-message t) expected-code)
          (str label ": the refusal names the account code an operator must open")))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest an-adjustment-moves-a-positive-amount
  (testing "a negative one would be the same movement written the other way
            round, and two representations of one concept is how sign bugs
            survive review"
    (is (= (sgd 1) (adjustment/assert-amount! (sgd 1))))
    (doseq [amount [(sgd 0) (sgd -1)]]
      (let [t (try (adjustment/assert-amount! amount) nil (catch Exception e e))]
        (is (some? t))
        (is (= :field-validation (:clofin/error (ex-data t))))))))

(deftest a-narrative-is-required-and-bounded
  (is (= "Because the scheme said so"
         (adjustment/assert-narrative! "  Because the scheme said so  ")))
  (doseq [bad [nil "" "   " 42]]
    (let [t (try (adjustment/assert-narrative! bad) nil (catch Exception e e))]
      (is (some? t) (pr-str bad))
      (is (= :field-validation (:clofin/error (ex-data t))))
      (is (contains? (ex-data t) "narrative")
          "reported under the member name the caller sent")))
  (let [t (try (adjustment/assert-narrative!
                (str/join (repeat (inc adjustment/max-narrative-length) "x")))
               nil (catch Exception e e))]
    (is (some? t) "an adjustment's narrative reaches the journal, where it is
                   immutable forever")))

(deftest the-direction-vocabulary-is-the-ledgers-own
  (is (= #{:debit :credit} (set adjustment/directions))
      "not a copy of it — audit finding A-014 is the record of what two copies
       of one vocabulary cost")
  (is (= :debit (adjustment/assert-direction! :debit)))
  (is (= :credit (adjustment/assert-direction! "credit")))
  (doseq [bad [:sideways "up" nil]]
    (is (thrown? Exception (adjustment/assert-direction! bad)) (pr-str bad))))

(deftest the-status-vocabulary-has-two-terms-and-the-second-is-terminal
  (is (= #{"proposed" "posted"} (set adjustment/statuses))))

(deftest both-account-roles-are-named-with-the-codes-domain-model-4-defines
  (is (= "1300-IN-TRANSIT" (:reconciled adjustment/account-roles)))
  (is (= "2200-UNAPPLIED" (:suspense adjustment/account-roles))
      "the chart of accounts has described 2200-UNAPPLIED as `the account where
       reconciliation breaks live` since before there was any reconciliation"))
