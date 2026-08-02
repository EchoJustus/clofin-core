(ns clofin.ledger.entry
  "Journal entries: the source of truth for money in CloFin.

  An entry represents one economic event and carries two or more lines. Its
  defining invariant is that, within the entry, total debits equal total credits
  for every currency involved. That invariant is checked here, and again by a
  deferred database constraint at commit — defence in depth on the one property
  that must never be violated.

      {:id           #uuid \"...\"
       :organisation-id #uuid \"...\"
       :occurred-at  #inst \"...\"
       :narrative    \"Supplier payment PI-000123 released\"
       :reference    {:type :payment-instruction :id #uuid \"...\"}
       :lines        [{:account-id #uuid \"...\" :direction :debit  :amount {...}}
                      {:account-id #uuid \"...\" :direction :credit :amount {...}}]}

  Entries are immutable once posted. A mistake is corrected by a reversing
  entry that references the original, so that both the error and the correction
  remain visible.

  Pure: no database, no clock, no identifier generation. The caller supplies
  the id and the occurrence time, which is what makes an entry reproducible in
  a test and replayable in an investigation.

  See docs/ADR/0008-double-entry-journal-as-source-of-truth.md."
  (:require [clofin.error :as err]
            [clofin.ledger.account :as account]
            [clofin.money :as money]
            [clojure.string :as str]))

(def reference-types
  "Business objects a journal entry may be raised against. An entry with no
  reference is not permitted: every movement of money must be explainable by
  something that caused it."
  #{:payment-instruction
    :settlement-item
    :reconciliation-adjustment
    :fee-assessment
    :fx-conversion
    :reversal
    :opening-balance})

;; ---------------------------------------------------------------------------
;; Lines
;; ---------------------------------------------------------------------------

(defn line
  "Validate a single journal line.

  Line amounts are strictly positive. Direction carries the sign, so a negative
  line amount would be a second, redundant way to express the same thing — and
  two representations of one concept is how sign bugs survive review."
  [{:keys [account-id direction amount] :as candidate}]
  (when-not (uuid? account-id)
    (err/invalid! "Journal line requires an account-id UUID" {:account-id account-id}))
  (when-not (contains? account/directions direction)
    (err/invalid! (str "Journal line direction must be :debit or :credit, got: " direction)
                  {:direction direction}))
  (when-not (money/money? amount)
    (err/invalid! "Journal line requires a valid amount" {:amount amount}))
  (when-not (money/pos? amount)
    (err/invalid! "Journal line amounts must be strictly positive; direction carries the sign"
                  {:amount amount :direction direction}))
  (select-keys candidate [:account-id :direction :amount]))

(defn- totals-by-currency
  "Debit and credit totals per currency across `lines`."
  [lines]
  (reduce (fn [acc {:keys [direction amount]}]
            (update-in acc [(:currency amount) direction]
                       (fnil #(money/+ % amount) (money/zero (:currency amount)))))
          {}
          lines))

(defn imbalance
  "Debit-minus-credit per currency, keeping only the currencies that do not
  balance. An empty map means the entry balances.

  Returned rather than thrown so that callers building an entry incrementally —
  a posting template, a settlement batch — can inspect the shortfall and report
  it usefully instead of catching an exception."
  [lines]
  (into {}
        (keep (fn [[currency {:keys [debit credit]}]]
                (let [d (or debit (money/zero currency))
                      c (or credit (money/zero currency))
                      diff (money/- d c)]
                  (when-not (money/zero? diff)
                    [currency diff]))))
        (totals-by-currency lines)))

(defn balanced?
  "True when total debits equal total credits for every currency in `lines`."
  [lines]
  (empty? (imbalance lines)))

;; ---------------------------------------------------------------------------
;; Entries
;; ---------------------------------------------------------------------------

(defn entry
  "Validate and normalise a journal entry, enforcing the zero-sum invariant.

  Throws a validation error carrying the per-currency imbalance when the entry
  does not balance, so the caller can report exactly what is missing."
  [{:keys [id organisation-id occurred-at narrative reference lines] :as candidate}]
  (when-not (uuid? id)
    (err/invalid! "Journal entry requires an id UUID" {:id id}))
  (when-not (uuid? organisation-id)
    (err/invalid! "Journal entry requires an organisation-id UUID"
                  {:organisation-id organisation-id}))
  (when-not (inst? occurred-at)
    (err/invalid! "Journal entry requires an occurred-at instant" {:occurred-at occurred-at}))
  (when-not (and (string? narrative) (not (str/blank? narrative)))
    (err/invalid! "Journal entry requires a narrative describing the economic event"
                  {:narrative narrative}))
  (when-not (contains? reference-types (:type reference))
    (err/invalid! "Journal entry requires a reference to the business object that caused it"
                  {:reference reference :known (vec (sort reference-types))}))
  (when-not (uuid? (:id reference))
    (err/invalid! "Journal entry reference requires an id UUID" {:reference reference}))
  (when-not (and (sequential? lines) (>= (count lines) 2))
    (err/invalid! "Journal entry requires at least two lines"
                  {:line-count (count lines)}))
  (let [validated (mapv line lines)
        gaps      (imbalance validated)]
    (when (seq gaps)
      (err/invalid! "Journal entry does not balance: total debits must equal total credits"
                    {:imbalance (into {} (map (fn [[c m]] [c (money/format-amount m)])) gaps)}))
    (-> (select-keys candidate [:id :organisation-id :occurred-at :narrative :reference])
        (assoc :lines validated))))

(defn currencies
  "Every currency appearing in an entry."
  [entry]
  (into (sorted-set) (map (comp :currency :amount)) (:lines entry)))

(defn total
  "Total value moved by an entry in `currency` — the debit side, which by the
  invariant equals the credit side. This is the figure a statement shows."
  [entry currency]
  (money/sum currency
             (into [] (comp (filter #(= :debit (:direction %)))
                            (filter #(= currency (:currency (:amount %))))
                            (map :amount))
                   (:lines entry))))

(defn reverse-entry
  "Build the entry that reverses `original`.

  Every line's direction is flipped and the amounts are unchanged, so posting
  both leaves every affected account exactly where it started. The reversal is a
  new entry referencing the original — the original is never modified, and both
  remain visible in the journal."
  [original {:keys [id occurred-at narrative]}]
  (when-not (uuid? id)
    (err/invalid! "Reversal requires a new entry id" {:id id}))
  (when (= id (:id original))
    (err/invalid! "A reversal must be a new entry, not a rewrite of the original"
                  {:id id}))
  (entry {:id              id
          :organisation-id (:organisation-id original)
          :occurred-at     occurred-at
          :narrative       (or narrative
                               (str "Reversal of " (:id original) ": " (:narrative original)))
          :reference       {:type :reversal :id (:id original)}
          :lines           (mapv (fn [l]
                                   (update l :direction {:debit :credit :credit :debit}))
                                 (:lines original))}))

;; ---------------------------------------------------------------------------
;; Posting templates
;; ---------------------------------------------------------------------------

(defn transfer-lines
  "The two lines of a simple value transfer: debit `to-account`, credit
  `from-account`.

  Read in accounting terms rather than in cash-movement terms. Moving client
  money out of a pooled asset account and into a settlement clearing account
  debits the destination and credits the source; the direction of the *cash* is
  a separate question answered by which accounts were chosen."
  [{:keys [from-account-id to-account-id amount]}]
  [(line {:account-id to-account-id   :direction :debit  :amount amount})
   (line {:account-id from-account-id :direction :credit :amount amount})])
