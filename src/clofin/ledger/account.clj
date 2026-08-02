(ns clofin.ledger.account
  "Ledger accounts and the balance conventions that follow from their type.

  An account is a plain map:

      {:id            #uuid \"...\"
       :organisation-id #uuid \"...\"
       :code          \"1100-CLIENT-FUNDS\"
       :name          \"Client funds — pooled\"
       :type          :asset
       :currency      \"SGD\"
       :status        :active}

  Pure. This namespace touches no database, clock or identifier generator.
  See docs/ADR/0008-double-entry-journal-as-source-of-truth.md."
  (:require [clofin.error :as err]
            [clofin.money :as money]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Account types
;; ---------------------------------------------------------------------------

(def account-types
  "The five classical account types and the side on which each normally carries
  a positive balance. This mapping is the whole reason account type exists in
  the model: it is what turns a pile of debits and credits into a balance with
  a sign that a finance team would recognise."
  {:asset     {:normal-balance :debit
               :description "Resources the institution controls — client money held, nostro balances, receivables."}
   :liability {:normal-balance :credit
               :description "Obligations owed — client payable balances, unsettled instructions, accrued fees."}
   :equity    {:normal-balance :credit
               :description "Residual interest — retained earnings, capital contributed."}
   :revenue   {:normal-balance :credit
               :description "Income earned — transaction fees, FX margin."}
   :expense   {:normal-balance :debit
               :description "Costs incurred — scheme charges, correspondent fees."}})

(def account-statuses
  "Lifecycle of an account. A closed account is retained forever because the
  journal entries referencing it are retained forever; it is never deleted."
  {:active   {:postable? true  :description "Accepts new postings."}
   :frozen   {:postable? false :description "Blocked pending investigation; history remains readable."}
   :closed   {:postable? false :description "Permanently closed; retained for audit."}})

(def directions
  "Posting directions. Explicit rather than encoded as a sign — see ADR-0008."
  #{:debit :credit})

(defn normal-balance
  "The side on which an account of `type` carries a positive balance."
  [type]
  (or (get-in account-types [type :normal-balance])
      (err/invalid! (str "Unknown account type: " type)
                    {:type type :known (vec (sort (keys account-types)))})))

(defn postable?
  "True when new journal lines may reference this account."
  [account]
  (boolean (get-in account-statuses [(:status account) :postable?])))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(def ^:private code-pattern
  "Account codes are uppercase alphanumerics and hyphens. They appear in
  statements, exports and reconciliation files, so the character set is
  constrained deliberately."
  #"[A-Z0-9][A-Z0-9-]{1,63}")

(defn account
  "Validate and normalise an account. Returns the account, or throws a
  validation error describing the first problem found."
  [{:keys [id organisation-id code name type currency status] :as candidate}]
  (when-not (uuid? id)
    (err/invalid! "Account id must be a UUID" {:id id}))
  (when-not (uuid? organisation-id)
    (err/invalid! "Account organisation-id must be a UUID" {:organisation-id organisation-id}))
  (when-not (and (string? code) (re-matches code-pattern code))
    (err/invalid! "Account code must be 2–64 uppercase alphanumeric characters or hyphens"
                  {:code code}))
  (when-not (and (string? name) (not (str/blank? name)))
    (err/invalid! "Account name is required" {:name name}))
  (normal-balance type) ; validates the type
  (money/scale currency) ; validates the currency
  (when-not (contains? account-statuses status)
    (err/invalid! (str "Unknown account status: " status)
                  {:status status :known (vec (sort (keys account-statuses)))}))
  (select-keys candidate [:id :organisation-id :code :name :type :currency :status]))

;; ---------------------------------------------------------------------------
;; Balances
;; ---------------------------------------------------------------------------

(defn signed-amount
  "The contribution a single posting makes to an account's balance.

  A debit increases a debit-normal account and decreases a credit-normal one.
  This function is the only place that convention is expressed."
  [account-type direction amount]
  (when-not (contains? directions direction)
    (err/invalid! (str "Unknown posting direction: " direction) {:direction direction}))
  (if (= direction (normal-balance account-type))
    amount
    (money/negate amount)))

(defn balance
  "Derive an account's balance from its postings.

  `postings` is a collection of `{:direction :debit|:credit :amount <money>}`.
  Balances are always derived, never stored as an authoritative value — see
  ADR-0008. The sign is expressed in the account's own terms: a positive
  liability balance means money is owed, not that the raw credit total is
  positive."
  [{:keys [type currency]} postings]
  (normal-balance type)
  (money/scale currency)
  (let [contributions (map (fn [{:keys [direction amount]}]
                             (when-not (= currency (:currency amount))
                               (err/invalid! "Posting currency does not match the account"
                                             {:account-currency currency
                                              :posting-currency (:currency amount)}))
                             (signed-amount type direction amount))
                           postings)]
    (money/sum currency contributions)))
