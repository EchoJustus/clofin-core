(ns clofin.payments.posting
  "Posting templates: the journal entries a payment instruction produces.

  An instruction is *intent to pay* and is not itself an accounting fact. One
  instruction produces several entries over its life — a release, a fee, a
  settlement — and each is a separate entry that balances on its own
  (`DOMAIN_MODEL.md` §4, ADR-0008). Templates live here rather than inside the
  ledger because which accounts a movement touches is a *payments* decision;
  the ledger's job is to refuse anything that does not balance, whoever built
  it.

  Templates are **per payment type, not global**. The pair of accounts a
  settlement touches depends on the scheme, which is why `DOMAIN_MODEL.md` §4
  stops at naming the release and fee movements and leaves settlement to the
  increment that has a scheme adapter to be specific about.

  Pure: no database, no clock, no identifier generation. Entry ids and the
  occurrence instant come from the caller, so a posting is reproducible in a
  test and replayable in an investigation."
  (:require [clofin.error :as err]
            [clofin.ledger.entry :as entry]
            [clofin.money :as money]))

(def account-roles
  "The chart-of-accounts codes these templates need, by the role each plays.

  Templates are written against roles rather than codes so that the mapping
  from role to account is resolved once, by the caller who looked the accounts
  up. `DOMAIN_MODEL.md` §4 is the reference chart."
  {:client-funds    "1100-CLIENT-FUNDS"
   :nostro          "1200-NOSTRO"
   :in-transit      "1300-IN-TRANSIT"
   :client-payable  "2100-CLIENT-PAYABLE"
   :fee-income      "4100-FEE-INCOME"
   :scheme-charges  "5100-SCHEME-CHARGES"})

(defn- account!
  [accounts role]
  (or (get accounts role)
      (err/invalid! (str "Posting template requires the " (name role) " account ("
                         (get account-roles role) ")")
                    {:role (name role)
                     :code (get account-roles role)
                     :supplied (mapv name (sort (keys accounts)))})))

(defn release-lines
  "The value movement of a release: debit settlement-in-transit, credit client
  funds.

  Read in accounting terms rather than cash terms. Releasing a client's money
  moves it out of the pooled asset account and into an account that says it has
  left but has not yet settled — so the destination is debited and the source
  credited. Whether cash has physically moved is a different question, answered
  by settlement.

  `accounts` maps the roles in `account-roles` to ledger account ids."
  [accounts amount]
  (when-not (money/pos? amount)
    (err/invalid! "A release must be for a positive amount" {:amount amount}))
  (entry/transfer-lines {:from-account-id (account! accounts :client-funds)
                         :to-account-id   (account! accounts :in-transit)
                         :amount          amount}))

(defn fee-lines
  "The fee movement: debit client payable, credit fee income.

  A separate entry from the release, not extra lines on it. The two are
  different economic events — value leaving, and a charge being earned — and
  merging them would make the fee unreadable on a statement and unreversible on
  its own."
  [accounts fee]
  (when-not (money/pos? fee)
    (err/invalid! "A fee must be for a positive amount" {:amount fee}))
  (entry/transfer-lines {:from-account-id (account! accounts :fee-income)
                         :to-account-id   (account! accounts :client-payable)
                         :amount          fee}))

(defn release-entries
  "Every entry a release produces: the value movement, and the fee when one
  applies.

  Returns entry candidates ready for `clofin.ledger.repository/post-entry!` —
  each referencing the instruction that caused it, which is what makes the
  journal explainable back to an intent (`DOMAIN_MODEL.md` I7).

  The caller supplies one id per entry and the occurrence instant; a template
  that generated either would not be pure and could not be replayed. Supplying
  too few ids is a defect, not a caller error — the count is a property of the
  template, and `fee` is the only thing that varies it."
  [instruction {:keys [accounts fee entry-ids occurred-at]}]
  (let [movements (cond-> [{:narrative (str "Payment instruction " (:id instruction)
                                            " released to " (:creditor-name instruction))
                            :lines     (release-lines accounts (:amount instruction))}]
                    (some? fee)
                    (conj {:narrative (str "Fee on payment instruction " (:id instruction))
                           :lines     (fee-lines accounts fee)}))]
    (when-not (= (count entry-ids) (count movements))
      (err/invalid! (str "A release of this instruction produces " (count movements)
                         " entries and needs that many ids")
                    {:required (count movements) :supplied (count entry-ids)}))
    (mapv (fn [id {:keys [narrative lines]}]
            (entry/entry {:id              id
                          :organisation-id (:organisation-id instruction)
                          :occurred-at     occurred-at
                          :narrative       narrative
                          :reference       {:type :payment-instruction
                                            :id   (:id instruction)}
                          :lines           lines}))
          entry-ids
          movements)))
