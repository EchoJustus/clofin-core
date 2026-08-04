(ns clofin.payments.instruction
  "Payment instructions: what one is, and what makes one invalid.

      {:id                #uuid \"...\"
       :organisation-id   #uuid \"...\"
       :debtor-account-id #uuid \"...\"
       :creditor-name     \"Pacific Rim Logistics Pte Ltd\"
       :creditor-account  \"SG-SYNTH-88012345\"
       :amount            {:currency \"SGD\" :minor-units 125000}
       :value-date        #object[java.time.LocalDate \"2026-08-10\"]
       :purpose-code      \"SUPP\"
       :status            :draft
       :created-by        #uuid \"...\"
       :created-at        #inst \"...\"
       :reverses-id       nil}

  **Every failed field is reported, not the first one** (PR-003). That is the
  whole reason validation is a function returning a map rather than a chain of
  early throws: an operator fixing a rejected instruction should need one round
  trip, not one per mistake. `field-errors` is therefore the primitive, and
  `instruction` is the constructor built on it — so the two cannot disagree
  about what is valid.

  Pure: no database, no clock, no identifier generation. `value-date` is
  checked against a `today` the caller supplies, because a domain namespace
  that reads a clock cannot be tested for the boundary that matters.

  See docs/ADR/0014-payment-lifecycle-as-data.md for why a rejected field is
  `422` rather than `400`."
  (:require [clofin.error :as err]
            [clofin.money :as money]
            [clofin.payments.state :as state]
            [clojure.string :as str])
  (:import [java.time LocalDate]))

;; ---------------------------------------------------------------------------
;; Constrained vocabularies
;; ---------------------------------------------------------------------------

(def purpose-codes
  "Purpose of payment, as a constrained vocabulary. Several corridors require
  one, and a free-text purpose is a field that is never usable for anything.

  A synthetic subset of ISO 20022 `ExternalPurpose1Code`, chosen to cover the
  payment types CloFin models. It is **not** a corridor-accurate list: which
  codes a real scheme accepts, and which it mandates, differ by scheme and by
  jurisdiction, and CloFin is connected to neither."
  {"CASH" "Cash management transfer"
   "CHAR" "Charity payment"
   "DIVI" "Dividend"
   "GDDS" "Purchase or sale of goods"
   "INSU" "Insurance premium"
   "INTC" "Intra-company payment"
   "LOAN" "Loan"
   "PENS" "Pension payment"
   "RENT" "Rent"
   "SALA" "Salary payment"
   "SCVE" "Purchase or sale of services"
   "SUPP" "Supplier payment"
   "TAXS" "Tax payment"
   "TRAD" "Trade services"
   "TREA" "Treasury payment"})

(def max-creditor-name-length
  "Matches the ISO 20022 `Max140Text` used for a party name, so that an
  instruction CloFin accepts is one a scheme message could carry."
  140)

(def creditor-account-pattern
  "A synthetic external account identifier: uppercase alphanumerics and
  hyphens, 4–34 characters. The upper bound is the IBAN maximum; the character
  set is deliberately narrower than any real scheme's, because these
  identifiers address nothing and a permissive field invites someone to paste
  something real into it."
  #"[A-Z0-9][A-Z0-9-]{2,32}[A-Z0-9]")

(def max-value-date-horizon-days
  "How far ahead a value date may be requested. A warehoused payment is a real
  product; one dated four centuries out is a typo that would otherwise sit in
  `pending-approval` forever, indistinguishable from work in progress."
  365)

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------
;;
;; Each rule returns a message or nil. Messages are field-relative and lower
;; case — they read as the predicate the field failed ("must be greater than
;; zero"), because the API renders them against the field's own name and a
;; sentence repeating that name reads twice.

;; `nil` is reported as "is required" rather than as a type failure, because a
;; caller that omitted a field and one that sent the wrong kind of value have
;; made different mistakes and need different answers.

(defn- uuid-error
  [value]
  (cond
    (nil? value)        "is required"
    (not (uuid? value)) "must be a UUID"))

(defn- creditor-name-error
  [value]
  (cond
    (nil? value)          "is required"
    (not (string? value)) "must be text"
    (str/blank? value)    "is required"
    (> (count (str/trim value)) max-creditor-name-length)
    (str "must be at most " max-creditor-name-length " characters")))

(defn- creditor-account-error
  [value]
  (cond
    (nil? value)          "is required"
    (not (string? value)) "must be text"
    (str/blank? value)    "is required"
    (not (re-matches creditor-account-pattern (str/trim value)))
    "must be 4–34 uppercase alphanumeric characters or hyphens"))

(defn- amount-error
  [value]
  (cond
    (nil? value)               "is required"
    (not (money/money? value)) "must be an integer count of minor units in a supported currency"
    (not (money/pos? value))   "must be greater than zero"))

(defn- value-date-error
  [value today]
  (cond
    (nil? value)                      "is required"
    (not (instance? LocalDate value)) "must be a date in YYYY-MM-DD form"
    (.isBefore ^LocalDate value ^LocalDate today) "must not be in the past"
    (.isAfter ^LocalDate value (.plusDays ^LocalDate today max-value-date-horizon-days))
    (str "must be within " max-value-date-horizon-days " days")))

(defn- purpose-code-error
  [value]
  (cond
    (nil? value)                          "is required"
    (not (string? value))                 "must be text"
    (not (contains? purpose-codes value)) (str "unknown purpose code: " value)))

(defn- status-error
  [value]
  (when-not (state/known? value)
    (str "unknown status: " value)))

(defn field-errors
  "Every field of `candidate` that fails validation, as `{field → message}`.

  An empty map means the instruction is valid. This returns rather than throws
  precisely so that all of the failures are available at once: PR-003 requires
  a rejection to name **every** failed field, and a validator that throws can
  only ever name the first.

  `today` is supplied by the caller — see the namespace docstring. Fields the
  caller could not parse at all are its own to report; this sees only values
  that arrived as some Clojure value, and judges those."
  [{:keys [organisation-id debtor-account-id creditor-name creditor-account
           amount value-date purpose-code status created-by reverses-id]}
   {:keys [today]}]
  (when-not (instance? LocalDate today)
    (err/invalid! "Validating a value date requires today's date from the caller"
                  {:today (str today)}))
  (into (sorted-map)
        (remove (comp nil? val))
        {:organisation-id   (uuid-error organisation-id)
         :debtor-account-id (uuid-error debtor-account-id)
         :creditor-name     (creditor-name-error creditor-name)
         :creditor-account  (creditor-account-error creditor-account)
         :amount            (amount-error amount)
         :value-date        (value-date-error value-date today)
         :purpose-code      (purpose-code-error purpose-code)
         :status            (status-error status)
         ;; `created-by` is the authenticated actor, supplied by the handler
         ;; from `clofin.api.principal` — a caller that sends it is refused
         ;; rather than quietly overridden. This layer still checks it is a
         ;; UUID and present, because the domain does not get to assume its
         ;; caller is an HTTP handler: `instruction` is the guarantee that
         ;; holds when something else builds an instruction. *Whether that
         ;; actor may act for the organisation* is a question about stored
         ;; state, so it belongs at the seam (ADR-0012) and is answered by
         ;; `clofin.api.principal/assert-organisation!`.
         :created-by        (uuid-error created-by)
         ;; Optional. Present only on a reversal, where it names the settled
         ;; instruction being reversed; that the target exists, is in this
         ;; organisation and is settled is a database question, answered in
         ;; `clofin.payments.repository`.
         :reverses-id       (when (some? reverses-id) (uuid-error reverses-id))}))

(defn valid?
  "True when `candidate` has no failed fields."
  [candidate opts]
  (empty? (field-errors candidate opts)))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn instruction
  "Validate and normalise a payment instruction.

  Throws `:field-validation` — rendered as `422` under the `validation` problem
  type — carrying every failed field. The HTTP layer normally validates first,
  so that it can report failures under their wire names and alongside fields
  that could not be parsed at all; this is the guarantee that holds when
  something other than an HTTP handler builds an instruction."
  [candidate opts]
  (let [errors (field-errors candidate opts)]
    (when (seq errors)
      (err/fail! :field-validation "Request failed validation" errors))
    (-> ;; `reverses-id` is defaulted rather than merely selected, so that an
        ;; instruction built here and one loaded from a row have the same shape.
        ;; A key that is absent in one and nil in the other is a difference that
        ;; only ever shows up in an equality check nobody expected to fail.
        (merge {:reverses-id nil}
               (select-keys candidate [:id :organisation-id :debtor-account-id
                                       :creditor-name :creditor-account
                                       :amount :value-date :purpose-code :status
                                       :created-by :created-at :reverses-id]))
        (update :creditor-name str/trim)
        (update :creditor-account str/trim))))

(defn draft
  "A new instruction in the state a new instruction begins in.

  The id, the creation instant and — until TASK-003 — the creating actor all
  come from the caller. The domain layer generates none of them, which is what
  makes an instruction reproducible in a test and replayable in an
  investigation."
  [candidate opts]
  (instruction (assoc candidate :status state/initial-state) opts))

;; ---------------------------------------------------------------------------
;; Amendment
;; ---------------------------------------------------------------------------

(def amendable-fields
  "Fields a `PATCH` may change on a draft.

  Everything else is either identity (`id`, `organisation-id`), provenance
  (`created-by`, `created-at`), lifecycle (`status` — that moves by transition,
  never by edit) or linkage (`reverses-id` — a reversal does not stop being one)."
  #{:debtor-account-id :creditor-name :creditor-account
    :amount :value-date :purpose-code})

(defn amend
  "Apply `changes` to `existing` and re-validate the whole instruction.

  The result is validated as a whole rather than field by field, because an
  amendment can break an invariant that spans fields — a currency that no
  longer matches the debtor account, say. Whether the instruction is in a state
  that permits amendment at all is `clofin.payments.state/assert-mutable!`'s
  question, asked before this one."
  [existing changes opts]
  (let [rejected (remove amendable-fields (keys changes))]
    (when (seq rejected)
      (err/fail! :field-validation "Request failed validation"
                 (into (sorted-map)
                       (map (fn [field] [field "cannot be amended"]))
                       rejected))))
  (instruction (merge existing (select-keys changes amendable-fields)) opts))
