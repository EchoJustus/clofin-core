(ns clofin.recon.adjustment
  "The adjustment: what resolving a break by posting is, how many approvals it
  needs, and the entry it produces.

  **Nothing in reconciliation edits a journal entry, ever** (C-03). A posted
  entry is immutable, and the only way a disagreement between the scheme and
  CloFin's books changes those books is a *new*, approved, balanced entry
  raised against the break — which is what this namespace describes. If you find
  yourself wanting an update statement, you are holding the wrong tool.

  ## The approvals an adjustment needs, and where that number comes from

  There is **one** approval mechanism in CloFin and this is not a second one.
  `clofin.authz.approval/evaluate` decides whether a given actor may record a
  given approval, unchanged and un-forked, and the bands in
  `approval_threshold` — the same table, per currency, never converted
  (ADR-0015) — decide how many are needed. What this namespace adds is one
  rule the payments path does not need:

  > **Below the lowest band an organisation has configured for the currency, an
  > adjustment needs no approval; at or above it, it needs the band's count.**

  The boundary is inclusive, exactly as `clofin.authz.approval/band-for` is
  inclusive, because of the two possible readings that is the one asking for
  more scrutiny rather than less.

  Two things about that rule are load-bearing and are stated rather than
  implied (**L-14**):

  - **An organisation with no band at all in the currency gets no adjustment.**
    `approvals-required` returns nil and the caller refuses with
    `:no-threshold-configured`. Treating \"unconfigured\" as \"needs nobody\"
    would be a control that silently weakens in exactly the organisation that
    has thought least about it.
  - **An organisation whose lowest band starts at zero has no de-minimis at
    all**, and every adjustment it makes needs approval. That is the stricter
    setting, and it is what the fixtures in the payments tests already
    configure.

  Segregation of duties applies with no exception: the actor who proposed an
  adjustment may not approve it. That is C-01's own comparison —
  `evaluate` refuses `:self-approval` first and never waivably — reached by
  handing it the adjustment as the subject rather than a payment instruction.

  ## The entry an adjustment posts

  Two lines, always: the **reconciled account** — `1300-IN-TRANSIT`, CloFin's
  clearing account (ADR-0018) — and the **suspense account**,
  `2200-UNAPPLIED`, which `DOMAIN_MODEL.md` §4 already describes as *\"the
  account where reconciliation breaks live\"*. The adjustment names the
  direction applied to the reconciled account and the other leg follows, so the
  entry balances by construction and an operator chooses between \"the money
  left and our books do not show it\" and \"our books show a movement the
  scheme does not\" rather than composing a journal entry by hand.

  Posting to a suspense account rather than to a revenue or expense account is
  deliberate: reconciliation knows that the two records disagree and does not
  know *why*. Parking the difference where it is visible and unallocated is an
  honest statement of that; writing it off to income would be a judgement this
  increment has no evidence for.

  Pure: no database, no clock, no identifier generation. Entry ids and the
  occurrence instant come from the caller, so a posting is reproducible in a
  test and replayable in an investigation."
  (:require [clofin.authz.approval :as approval]
            [clofin.error :as err]
            [clofin.ledger.account :as account]
            [clofin.ledger.entry :as entry]
            [clofin.money :as money]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Vocabulary
;; ---------------------------------------------------------------------------

(def statuses
  "Every status an adjustment may hold. Identical to
  `recon_adjustment_status_known` in migration `0012`.

  Two, and the second is terminal. A `proposed` adjustment is a request for a
  posting; a `posted` one has an entry id and its break is resolved. There is
  deliberately no `rejected`: an approver who disagrees simply does not approve,
  the adjustment stays `proposed` and never posts, and a different adjustment
  may be raised against the same break. Naming the refusal would need a second
  arrow, a second audit term and an endpoint nothing in this brief asks for —
  and a status nothing can reach is worse than an absent one. Recorded as debt
  in `docs/audits/008-REQ-reconciliation.md`."
  (into (sorted-set) ["proposed" "posted"]))

(def account-roles
  "The chart-of-accounts codes an adjustment needs, by the role each plays.

  Written against roles rather than codes for the reason
  `clofin.payments.posting/account-roles` gives: the mapping from role to
  account is resolved once, by the caller that looked the accounts up.
  `DOMAIN_MODEL.md` §4 is the reference chart, and both codes are already in
  it — `2200-UNAPPLIED` has described itself as \"the account where
  reconciliation breaks live\" since before there was any reconciliation."
  {:reconciled "1300-IN-TRANSIT"
   :suspense   "2200-UNAPPLIED"})

(def directions
  "The direction an adjustment applies **to the reconciled account**.

  `clofin.ledger.account/directions` itself, not a copy of it: the value written
  to `reconciliation_adjustment.direction` is a posting direction and there is
  one vocabulary for those (audit finding **A-014** is the record of what two
  copies cost). The suspense leg is always the opposite, which is what makes the
  entry balance by construction rather than by the caller getting it right."
  account/directions)

(defn assert-direction!
  "Return `direction` as a keyword, or throw naming what is permitted."
  [direction]
  (let [d (if (keyword? direction) direction (keyword (str direction)))]
    (when-not (contains? directions d)
      (err/invalid! (str "An adjustment's direction must be debit or credit, got: " direction)
                    {:direction (str direction)
                     :known (mapv name (sort directions))}))
    d))

;; ---------------------------------------------------------------------------
;; How many approvals
;; ---------------------------------------------------------------------------

(defn de-minimis-minor
  "The amount, in minor units, at which approval starts being required — the
  lowest band the organisation has configured for this currency — or nil when it
  has configured none.

  `thresholds` is the band list for one currency, as
  `clofin.authz.repository/thresholds-for` returns it. Reading the *lowest* band
  rather than inventing a separate adjustment threshold is what makes \"the
  threshold table says so\" true: there is one statement of where an
  organisation wants a second pair of eyes, and it is the table it already
  maintains."
  [thresholds]
  (:from-minor (first (sort-by :from-minor thresholds))))

(defn approvals-required
  "How many approvals an adjustment of `amount` needs, or **nil** when the
  organisation has configured no band in its currency.

  Nil is a refusal, not a zero: see the namespace docstring. Zero is a real
  answer and means the proposer alone may post."
  [thresholds amount]
  (when-let [floor (de-minimis-minor thresholds)]
    (if (< (:minor-units amount) floor)
      0
      ;; At or above the floor a band always covers the amount, because the
      ;; floor *is* the lowest band's lower bound and bands are inclusive. The
      ;; `or` is therefore unreachable rather than a default being chosen here —
      ;; and it fails closed if that ever stops being true.
      (or (approval/approvals-required thresholds amount) 1))))

(defn needs-approval?
  "True when an adjustment of `amount` may not be posted by its proposer alone."
  [thresholds amount]
  (let [required (approvals-required thresholds amount)]
    (and (some? required) (pos? required))))

;; ---------------------------------------------------------------------------
;; The adjustment
;; ---------------------------------------------------------------------------

(def max-narrative-length
  "An adjustment's narrative reaches the journal, where it is immutable forever.
  Bounded for the reason every caller-supplied string in CloFin is."
  500)

(defn assert-narrative!
  "Return the narrative, trimmed, or throw.

  Required rather than optional, and it is the one field this increment insists
  on: an adjustment is a judgement about a disagreement, and a journal entry
  that moves money between a clearing account and a suspense account without
  saying why is the entry an investigation will be least able to explain."
  [narrative]
  (when-not (and (string? narrative) (not (str/blank? narrative)))
    (err/fail! :field-validation "Request failed validation"
               {"narrative" "is required: an adjustment must say why the books are moving"}))
  (let [trimmed (str/trim narrative)]
    (when (> (count trimmed) max-narrative-length)
      (err/fail! :field-validation "Request failed validation"
                 {"narrative" (str "must be at most " max-narrative-length " characters")}))
    trimmed))

(defn assert-amount!
  "Return `amount`, or throw. An adjustment moves a positive amount in one
  direction; a negative one would be the same movement written the other way
  round, and two representations of one concept is how sign bugs survive
  review (the reasoning `clofin.ledger.entry/line` already applies to lines)."
  [amount]
  (when-not (money/money? amount)
    (err/invalid! "An adjustment requires a valid amount" {:amount amount}))
  (when-not (money/pos? amount)
    (err/fail! :field-validation "Request failed validation"
               {"amount" "must be a positive amount"}))
  amount)

(defn adjustment-entry
  "The single balanced entry an adjustment posts.

  `accounts` maps the roles in `account-roles` to ledger account ids. The
  reference is `:reconciliation-adjustment` — a reference type
  `clofin.ledger.entry/reference-types` has carried since the ledger was
  written, and this is the increment that finally raises one — so the journal
  stays explainable back to the thing that caused it (`DOMAIN_MODEL.md` I7).

  Returns an entry candidate ready for `clofin.ledger.repository/post-entry!`
  via `clofin.ledger.service/post-entry!`, which is the **existing** posting
  path: the zero-sum domain check, the account lock, the deferred database
  trigger and the `journal-entry.posted` audit event all apply to an adjustment
  exactly as they apply to a release. Reconciliation does not get a private door
  into the ledger."
  [{:keys [id organisation-id amount direction narrative]}
   {:keys [accounts entry-id occurred-at]}]
  (let [reconciled (or (get accounts :reconciled)
                       (err/invalid! (str "An adjustment needs the reconciled account ("
                                          (:reconciled account-roles) ")")
                                     {:role "reconciled" :code (:reconciled account-roles)}))
        suspense   (or (get accounts :suspense)
                       (err/invalid! (str "An adjustment needs the suspense account ("
                                          (:suspense account-roles) ")")
                                     {:role "suspense" :code (:suspense account-roles)}))
        d          (assert-direction! direction)]
    (entry/entry
     {:id              entry-id
      :organisation-id organisation-id
      :occurred-at     occurred-at
      :narrative       narrative
      :reference       {:type :reconciliation-adjustment :id id}
      :lines           (entry/transfer-lines
                        (if (= :debit d)
                          {:from-account-id suspense   :to-account-id reconciled
                           :amount (assert-amount! amount)}
                          {:from-account-id reconciled :to-account-id suspense
                           :amount (assert-amount! amount)}))})))
