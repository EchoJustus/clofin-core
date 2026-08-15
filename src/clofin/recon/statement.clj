(ns clofin.recon.statement
  "The synthetic statement format: what a statement *is*, its identity, and the
  digest that says whether two deliveries under one identity are the same
  message.

  > **The format is CloFin's own and is deliberately not any real one.** It is
  > not camt.053, not MT940, not BAI2, and not any scheme's or bank's schema.
  > CloFin connects to nothing and every statement it reads was produced by its
  > own simulator; a synthetic-data project parsing a real bank format would be
  > fidelity theatre, and naming one would invite exactly the misreading the
  > scope statements exist to prevent. See
  > [ADR-0023](../../../docs/ADR/0023-a-clofin-defined-synthetic-statement-format-and-an-ordered-matching-sequence.md).

  The format identifier and every scheme name it may carry are `SIM-` prefixed,
  for the reason `clofin.settlement.batch/schemes` gives: a synthetic record
  that reads as a real one is the failure the prefix makes unrepresentable.

  ## Receipt and disposition are separate facts (standing lesson **L-11**)

  A statement that arrives is recorded as having arrived, whether or not CloFin
  could process it. `dispositions` says what CloFin did about the arrival, and
  the caller's `422` is rendered **after** the receipt commits — the posture
  `clofin.settlement.response` established for scheme responses, applied here
  unchanged rather than reinvented.

  A message that could not be *understood* is a different case and earns no
  receipt: `assert-shape!` throws, exactly as
  `clofin.settlement.response/assert-shape!` does, and for the same reason —
  the receipt table is a record of deliveries, not of typos.

  ## Replay identity covers every effect-bearing field (**L-2**, **L-12**)

  `statement-reference` names a delivery's *identity* within an organisation.
  It does not say whether two deliveries under that identity are the same
  **message**, so every effect-bearing field — the scheme, the currency, the
  period, and every line — travels into `digest`. `clofin.idempotency/canonical`
  is reused unchanged through `clofin.audit/digest`: one canonical form, one
  place where \"the same request\" is defined, and one version tag so digests
  taken before and after a canonicalisation change cannot silently compare
  equal.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.audit :as audit]
            [clofin.error :as err]
            [clofin.money :as money]
            [clojure.string :as str])
  (:import [java.time Instant LocalDate]
           [java.time.format DateTimeParseException]))

;; ---------------------------------------------------------------------------
;; The format
;; ---------------------------------------------------------------------------

(def format-name
  "The one format CloFin ingests, and it is CloFin's own.

  Carried in every document and checked on arrival, so a file produced against
  some other convention is refused by name rather than mis-parsed into plausible
  nonsense. `SIM-` prefixed like the scheme names beside it."
  "SIM-CLOFIN-RECON-STATEMENT")

(def format-version
  "The version of `format-name` this build reads and writes.

  A number rather than a date, and stated in the document rather than inferred:
  a format change that left old documents parseable-but-different is the class
  of silent drift the canonicalisation version exists to make loud."
  1)

(def simulated-scheme-prefix
  "Every scheme name a statement may carry begins with this.

  **Reconciliation does not own the scheme vocabulary — settlement does**
  (`clofin.settlement.batch/schemes`), and this namespace deliberately does not
  require it. The dependency runs the other way: `clofin.settlement.statement`,
  the simulator that *writes* these documents, requires this namespace for the
  format it must emit. An adapter depending on the format its consumer reads is
  a direction; the two depending on each other would be a cycle between bounded
  contexts, which ARCHITECTURE §3's layering exists to prevent.

  So the membership check has two enforcement points and neither is this one:
  `clofin.api.reconciliation` asserts the settlement vocabulary at the boundary,
  where the refusal a caller can act on belongs, and
  `recon_statement_scheme_known` asserts it in the schema — a constraint
  `clofin.db.vocabulary-test` compares with `clofin.settlement.batch/schemes`
  against the live catalogue, in both directions. What is checked *here* is the
  property that does not depend on the vocabulary at all: a statement CloFin
  reads names a simulated scheme, so a document naming a real network is refused
  even if some future vocabulary drift admitted it."
  "SIM-")

(def line-types
  "Every kind of movement a statement line may report. Identical to
  `recon_statement_line_type_known` in migration `0012`, and compared with the
  live catalogue by `clofin.db.vocabulary-test`.

  Two, and deliberately only two: these are the *outcomes* a scheme reports
  about money it was given. A release is CloFin telling the scheme something,
  not the scheme telling CloFin — so it is not a statement line, and the
  released-but-unanswered payment that is correctly absent from the statement is
  the case ADR-0018's clearing account exists to make visible."
  (into (sorted-set) ["settlement" "return"]))

(def dispositions
  "What CloFin did about a statement that arrived. Identical to
  `recon_statement_disposition_known` in migration `0012`.

  - `applied` — the statement was matched against the ledger and its breaks
    were opened.
  - `refused` — the statement arrived, is kept, and could not be processed. No
    matching, no breaks; the caller received its refusal **after** this receipt
    committed (**L-11**)."
  (into (sorted-set) ["applied" "refused"]))

(def refusal-reasons
  "Why a refused arrival was refused, as stable codes with the prose a caller is
  told. **Every code a caller may receive is in here.**

  Codes rather than free text because a replay reproduces the original answer
  from the stored row: a reason that were prose would be prose this code had to
  parse in order to answer a replay the same way twice. The `ref-1` audit's
  finding **A-016** is the record of what happens when a code the service can
  emit is absent from the vocabulary an integrator can enumerate."
  {"no-reconciled-account"
   (str "This organisation has no 1300-IN-TRANSIT account in the statement's "
        "currency, so there is nothing for the statement to be reconciled against")
   "too-many-ledger-movements"
   (str "This statement's period covers more ledger movements on the reconciled "
        "account than one reconciliation run may cover. Ingest a shorter period: "
        "a run that silently stopped at a cap would report matches over part of "
        "the ledger and breaks over the rest")
   "replay-key-conflict"
   (str "This organisation and statement reference already name a different "
        "statement. A reference identifies one document; two documents that say "
        "different things cannot share it")})

(def stored-refusal-reasons
  "The subset of `refusal-reasons` that can reach
  `reconciliation_statement.disposition_reason`. Identical to
  `recon_statement_refusal_reason_known` in migration `0012`.

  `replay-key-conflict` is deliberately outside it, and the asymmetry is a fact
  rather than an omission: that refusal happens **because** a receipt for that
  identity already exists, and writing a second row would defeat the replay key
  that produced the refusal. The first receipt is the evidence; the conflict is
  an answer to the caller and nothing else. The same split
  `clofin.settlement.response/stored-refusal-reasons` makes, for the same
  reason, and published as its own enum so neither copy can quietly become
  false."
  (into (sorted-set) ["no-reconciled-account" "too-many-ledger-movements"]))

(defn refusal-detail
  "The prose for a refusal code, or a neutral fallback.

  A fallback rather than a throw: this is read while *reproducing* a stored
  answer, and a receipt written by an earlier version carrying a code this one
  does not know must still replay as the refusal it was rather than becoming a
  `500` on the evidence path."
  [code]
  (or (refusal-reasons code)
      "This statement arrived and was recorded, and CloFin could not process it"))

(defn refused? [disposition] (= "refused" disposition))
(defn applied? [disposition] (= "applied" disposition))

;; ---------------------------------------------------------------------------
;; Shape
;; ---------------------------------------------------------------------------

(def max-lines
  "How many lines one statement may carry.

  A bound rather than an opinion: every line is matched inside one transaction,
  and an unbounded document is a caller's decision about how long CloFin holds a
  lock. The same reasoning as `clofin.idempotency/max-key-length`, and the same
  posture as ADR-0011's row caps — a limit stated in the contract beats a limit
  discovered under load."
  500)

(defn- read-instant!
  [value field]
  (when-not (string? value)
    (err/invalid! (str "Field '" field "' is required and must be an ISO 8601 instant")
                  {:field field}))
  (try
    (Instant/parse value)
    (catch DateTimeParseException _
      (err/invalid! (str "Field '" field "' must be an ISO 8601 instant with a zone, "
                         "e.g. 2026-08-02T10:15:00Z")
                    {:field field :value value}))))

(defn- read-date!
  [value field]
  (when-not (string? value)
    (err/invalid! (str "Field '" field "' is required and must be a date in YYYY-MM-DD form")
                  {:field field}))
  (try
    (LocalDate/parse value)
    (catch DateTimeParseException _
      (err/invalid! (str "Field '" field "' must be a date in YYYY-MM-DD form")
                    {:field field :value value}))))

(defn- read-line!
  [currency index raw]
  (let [at (fn [field] (str "lines[" index "]." field))]
    (when-not (map? raw)
      (err/invalid! (str "Field '" (at "") "' must be an object") {:field (at "")}))
    (let [line-type (get raw "lineType")
          reference (get raw "schemeReference")
          payment   (get raw "paymentReference")
          amount    (get raw "amount")]
      (when-not (contains? line-types line-type)
        (err/invalid! (str "Field '" (at "lineType") "' must be one of: "
                           (str/join ", " line-types))
                      {:field (at "lineType") :known (vec line-types)}))
      (when-not (and (string? reference) (not (str/blank? reference)))
        (err/invalid! (str "Field '" (at "schemeReference") "' is required")
                      {:field (at "schemeReference")}))
      (when-not (map? amount)
        (err/invalid! (str "Field '" (at "amount")
                           "' must be an amount object with 'currency' and 'minorUnits'")
                      {:field (at "amount")}))
      (let [money (money/wire-> amount)]
        (when-not (money/pos? money)
          (err/invalid! (str "Field '" (at "amount") "' must be a positive amount")
                        {:field (at "amount")}))
        (when-not (= currency (:currency money))
          (err/invalid! (str "Field '" (at "amount")
                             "' is denominated in " (:currency money)
                             " and the statement is in " currency)
                        {:field (at "amount")
                         :statement-currency currency
                         :line-currency (:currency money)}))
        ;; A blank payment reference and an absent one are the same claim — the
        ;; scheme echoed no end-to-end reference back — and letting them differ
        ;; would make two identical statements digest differently.
        {:scheme-reference  (str/trim reference)
         :payment-reference (when (and (string? payment) (not (str/blank? payment)))
                              (str/trim payment))
         :line-type         line-type
         :amount            money
         :value-date        (read-date! (get raw "valueDate") (at "valueDate"))}))))

(defn assert-shape!
  "Validate a statement document and return it as a domain value.

  Runs **before** anything is written, and that ordering is the point. A
  document CloFin cannot understand — an unknown format, a period that ends
  before it begins, a line in the wrong currency — is not a statement that
  arrived and could not be processed; it is a request that could not be read,
  and it is `400`. Only a document well-formed enough to *be* a statement earns
  a receipt (standing lesson **L-11** as `clofin.settlement.response` applies
  it: F-008 is about a **processing** refusal, which always commits its
  receipt).

  Returns

      {:format … :format-version … :scheme … :currency …
       :statement-reference … :period-start inst :period-end inst
       :lines [{:scheme-reference … :payment-reference … :line-type …
                :amount money :value-date date} …]}

  with `:line-no` assigned by `with-line-numbers` rather than read from the
  document: a line's position is CloFin's way of addressing it, and accepting a
  caller-chosen number would let two lines claim one address."
  [document]
  (when-not (map? document)
    (err/invalid! "A reconciliation statement must be a JSON object" {}))
  (let [format*  (get document "format")
        version  (get document "formatVersion")
        scheme   (get document "scheme")
        currency (get document "currency")
        reference (get document "statementReference")
        raw-lines (get document "lines")]
    (when-not (= format-name format*)
      (err/invalid! (str "CloFin reads one statement format, and it is its own: " format-name)
                    {:field "format" :value (str format*) :known [format-name]
                     :note (str "Deliberately not camt.053, MT940, BAI2 or any real "
                                "scheme's schema — CloFin is a synthetic-data reference "
                                "implementation and connects to nothing")}))
    (when-not (= format-version version)
      (err/invalid! (str "This build reads version " format-version " of " format-name)
                    {:field "formatVersion" :value (str version) :known [format-version]}))
    (when-not (and (string? scheme) (str/starts-with? scheme simulated-scheme-prefix))
      (err/invalid! (str "A statement's scheme must name a simulated scheme, prefixed "
                         simulated-scheme-prefix)
                    {:field "scheme" :value (str scheme)
                     :note "CloFin reconciles against simulated schemes only"}))
    (when-not (money/supported? currency)
      (err/invalid! (str "Unsupported statement currency: " currency)
                    {:field "currency" :value (str currency)}))
    (when-not (and (string? reference) (not (str/blank? reference)))
      (err/invalid! "Field 'statementReference' is required" {:field "statementReference"}))
    (when-not (sequential? raw-lines)
      (err/invalid! "Field 'lines' must be an array of statement lines" {:field "lines"}))
    (when (> (count raw-lines) max-lines)
      (err/invalid! (str "A statement may carry at most " max-lines " lines")
                    {:field "lines" :max-lines max-lines :supplied (count raw-lines)}))
    (let [period-start (read-instant! (get document "periodStart") "periodStart")
          period-end   (read-instant! (get document "periodEnd") "periodEnd")]
      (when-not (.isBefore period-start period-end)
        (err/invalid! "A statement period ends before it begins"
                      {:field "periodEnd"
                       :period-start (str period-start)
                       :period-end   (str period-end)}))
      {:format              format-name
       :format-version      format-version
       :scheme              scheme
       :currency            currency
       :statement-reference (str/trim reference)
       :period-start        period-start
       :period-end          period-end
       :lines               (into [] (map-indexed (partial read-line! currency)) raw-lines)})))

(defn with-line-numbers
  "Number a statement's lines from 1, in the order the document listed them.

  Position is the address CloFin gives a line, and it is assigned here rather
  than read from the document: two lines claiming one `lineNo` would be two
  breaks claiming one identity, and a document is not a place to accept an
  identifier from."
  [statement]
  (update statement :lines
          (fn [lines] (into [] (map-indexed (fn [i l] (assoc l :line-no (inc i)))) lines))))

;; ---------------------------------------------------------------------------
;; Identity and digest
;; ---------------------------------------------------------------------------

(defn semantic-content
  "The complete semantic content of one statement, as a value.

  **Everything that decides an effect is in here, and nothing else is**
  (standing lessons **L-2** and **L-12**). The scheme, the currency and the
  period decide which ledger movements the statement is compared against; every
  line decides which matches and which breaks come out. Change any of them and
  the outcome changes, so a digest that omitted one would let two genuinely
  different statements replay as one.

  Deliberately absent: the organisation, the actor, the correlation id and the
  moment of arrival. The organisation is part of the replay *key* rather than of
  the message — a statement is addressed to a tenant, and two tenants receiving
  the same document must both be able to ingest it. The other three change
  nothing about what the statement says, and digesting them would make every
  delivery unique, which would disable the replay guard while looking like a
  stricter version of it."
  [{:keys [format format-version scheme currency statement-reference
           period-start period-end lines]}]
  {:format              format
   :format-version      format-version
   :scheme              scheme
   :currency            currency
   :statement-reference statement-reference
   :period-start        period-start
   :period-end          period-end
   ;; Ordered, not sorted: a statement's lines are a sequence, and two documents
   ;; listing the same movements in different orders address them differently —
   ;; `lines[3]` is a break's identity. `clofin.idempotency/canonical` preserves
   ;; array order for exactly this reason.
   :lines               (mapv (fn [l]
                                {:scheme-reference  (:scheme-reference l)
                                 :payment-reference (:payment-reference l)
                                 :line-type         (:line-type l)
                                 :currency          (:currency (:amount l))
                                 :minor-units       (:minor-units (:amount l))
                                 :value-date        (:value-date l)})
                              lines)})

(defn digest
  "The version-tagged canonical digest of a statement's semantic content.

  `clofin.audit/digest` does the work: it normalises domain values — instants,
  dates, nils — into something `clofin.idempotency/canonical` can serialise,
  hashes the canonical string, and prefixes the canonicalisation version. Same
  function, same guarantee, same one place to change (ADR-0013)."
  [statement]
  (audit/digest (semantic-content statement)))

(defn same-message?
  "True when a stored digest and an incoming one describe the same statement.

  Nil-safe on the stored side: a receipt whose digest cannot be compared must
  not be *assumed* identical, which is precisely the assumption audit finding
  **F-009** found. It replays as the answer it recorded, and a caller sending
  something different under that reference is told the reference is taken."
  [stored-digest incoming-digest]
  (and (some? stored-digest) (= stored-digest incoming-digest)))
