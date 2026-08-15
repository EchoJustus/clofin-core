(ns clofin.recon.matching
  "Deterministic, ordered, explainable matching — and the disagreements it
  names.

  Two questions, deliberately separated, because conflating them is how a
  reconciliation engine ends up unable to say *what* is wrong:

  1. **Which ledger movement is this statement line about?** Answered by the
     rules in `rules`, applied in the order they are written, first match wins,
     and the id of the rule that matched is recorded against the match
     (**PR-051**). No fuzzy scoring, no probabilities, no learning: a match a
     human cannot re-derive is a match nobody can defend to an auditor, and
     PR-051 asks for deterministic *and* explainable.
  2. **Do the two agree?** Answered by `agreement-checks`, applied to every
     matched pair. Matching on identity and disagreeing on amount is the most
     valuable thing a reconciliation finds, and it is emphatically not the same
     as \"unmatched\" — a break that says *what* disagreed is a break somebody
     can work.

  The rule order is documented in [`DOMAIN_MODEL.md`](../../../docs/DOMAIN_MODEL.md)
  §6 and `clofin.recon.matching-test` compares the documented list with this one
  **in both directions and in order**. That guard is the vocabulary-drift shape
  this repository has now used sixteen times over (standing lesson **L-6**): a
  document describing a rule sequence the code no longer runs is a document that
  reads as evidence.

  ## What is compared against what

  A **statement line** is what the simulated scheme says it did. An
  **expectation** is a movement CloFin's own journal already records on the
  reconciled account — read from the ledger, by
  `clofin.recon.repository/expectations-for`, with no reference to the
  settlement tables at all. Nothing in this namespace can see how a statement
  was produced, and the generator that produces one
  (`clofin.settlement.statement`) cannot see this namespace: agreement between
  two values is worth something only when different things produced them
  (standing lesson **L-16**).

  ## Both directions

  The check runs both ways round and always has. A statement line with no
  ledger counterpart is a break; a ledger movement with no statement line is
  **also** a break, and it is the more interesting of the two — it is CloFin
  claiming a movement the scheme does not report.

  Pure: no database, no clock, no identifier generation. A break's *age* is
  derived from its `opened-at` by whoever reads it, and is never stored — a
  stored age is wrong the moment it is written.

  Both a statement line and an expectation carry:

      :payment-reference   the end-to-end reference — a statement line's as the
                           scheme echoed it (a string, possibly nil), an
                           expectation's as the journal entry references it
      :amount              money
      :value-date          the calendar date each side dates the movement to
      :line-type           settlement | return

  and each carries its own address — `:line-no` for a line, `:entry-id` for an
  expectation."
  (:require [clofin.money :as money]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reading a reference off either side
;; ---------------------------------------------------------------------------

(defn reference-of
  "The end-to-end reference a line or an expectation carries, as a string, or
  nil.

  Rendered rather than parsed. A statement line's reference is whatever the
  scheme echoed back and may be anything at all; an expectation's is the id of
  the business object the journal entry names. Comparing them as strings means a
  garbled reference simply fails to match, rather than raising on a value the
  matcher had no business trying to parse."
  [side]
  (let [raw (:payment-reference side)]
    (when (and (some? raw) (not (str/blank? (str raw))))
      (str/trim (str raw)))))

(defn- same-reference?
  [line expectation]
  (let [a (reference-of line)
        b (reference-of expectation)]
    (and (some? a) (some? b) (= a b))))

(defn- same-amount?
  "True when the two amounts are equal, currency included.

  The currency is compared first rather than left to `money/eq?`, which raises
  on a cross-currency comparison (invariant I5). Both sides are single-currency
  by construction — a statement's lines are checked against its own currency and
  an expectation is a line on an account that holds one — so this guard is
  reached only if that ever stops being true, and a break is the right answer
  there rather than a `500`."
  [line expectation]
  (and (= (:currency (:amount line)) (:currency (:amount expectation)))
       (money/eq? (:amount line) (:amount expectation))))

(defn- same-value-date?
  [line expectation]
  (= (:value-date line) (:value-date expectation)))

;; ---------------------------------------------------------------------------
;; The rules, in the order they are applied
;; ---------------------------------------------------------------------------

(def rules
  "The matching sequence. **Order is the specification**, not an implementation
  detail: the first rule that matches a line wins, and the rules are tried
  rule-major — every line is offered rule 1 before any line is offered rule 2.

  Rule-major rather than line-major so that the *strongest available evidence*
  claims a movement first. A line carrying an end-to-end reference should take
  its movement before a line carrying none can claim it on amount and date
  alone, whichever order the two happen to appear in the document. Line-major
  would make the outcome depend on the order the scheme listed its lines, which
  is not a fact about the money.

  A rule matches only when it identifies **exactly one** unmatched expectation.
  Two candidates is not a match: picking one would be a guess, and a guessed
  match is worse than a break, because a break is visible and a wrong match is
  not.

  `:id` is what is recorded against the match and what
  [`DOMAIN_MODEL.md`](../../../docs/DOMAIN_MODEL.md) §6 publishes; `:matches?`
  is the whole of the rule. Adding a rule means adding a row to that table in
  the same commit — `clofin.recon.matching-test` fails on either half."
  [{:id       "R1-reference-amount-and-value-date"
    :summary  "The end-to-end reference, the amount and the value date all agree."
    :matches? (fn [line expectation]
                (and (same-reference? line expectation)
                     (same-amount? line expectation)
                     (same-value-date? line expectation)))}

   {:id       "R2-reference-and-amount"
    :summary  (str "The reference and the amount agree and the value dates differ. "
                   "The movement is identified; the date disagreement is a break.")
    :matches? (fn [line expectation]
                (and (same-reference? line expectation)
                     (same-amount? line expectation)))}

   {:id       "R3-reference-only"
    :summary  (str "The reference agrees and the amount does not. The scheme and "
                   "CloFin are talking about the same payment and disagree about "
                   "how much moved, which is the disagreement reconciliation "
                   "exists to surface.")
    :matches? (fn [line expectation]
                (same-reference? line expectation))}

   {:id       "R4-amount-and-value-date"
    :summary  (str "The line carries no end-to-end reference, and exactly one "
                   "unmatched movement has its amount and value date. Last, "
                   "because it identifies a movement by its attributes rather "
                   "than by its name.")
    :matches? (fn [line expectation]
                (and (nil? (reference-of line))
                     (same-amount? line expectation)
                     (same-value-date? line expectation)))}])

(def rule-ids
  "Every rule id, in application order. Identical to
  `recon_match_rule_known` in migration `0012`."
  (mapv :id rules))

;; ---------------------------------------------------------------------------
;; Agreement
;; ---------------------------------------------------------------------------

(def break-kinds
  "Every kind of break this namespace can open. Identical to
  `recon_break_kind_known` in migration `0012`, and compared with the live
  catalogue by `clofin.db.vocabulary-test`.

  Six, covering both directions and the three ways a matched pair can still
  disagree. Every one of them is reachable from the public path — a generated
  statement, a named perturbation class, an ingestion — rather than only from a
  unit test constructing values by hand: a schema path is not a product path
  (standing lesson **L-10**)."
  (into (sorted-set)
        ["statement-line-unmatched"
         "expectation-unmatched"
         "duplicate-statement-line"
         "amount-mismatch"
         "value-date-mismatch"
         "line-type-mismatch"]))

(def agreement-checks
  "What a matched pair is compared on, in the order the breaks are reported.

  Applied to **every** match, whichever rule produced it, so the rule decides
  *which movement* and these decide *what disagrees*. A pair may fail more than
  one — a line with the wrong amount and the wrong date is two facts an
  investigator needs, not one — so each produces its own break."
  [{:kind    "amount-mismatch"
    :agrees? same-amount?
    :detail  (fn [line expectation]
               (str "The statement reports " (money/format-amount (:amount line))
                    " and the ledger movement is "
                    (money/format-amount (:amount expectation))))}

   {:kind    "value-date-mismatch"
    :agrees? same-value-date?
    :detail  (fn [line expectation]
               (str "The statement dates this movement " (:value-date line)
                    " and the ledger dates it " (:value-date expectation)))}

   {:kind    "line-type-mismatch"
    ;; An expectation whose type could not be derived — a movement on the
    ;; reconciled account whose counter-account is not one of the two the
    ;; finality templates use — agrees with everything, because CloFin does not
    ;; know what it was. Reporting a mismatch there would be asserting a
    ;; disagreement out of an absence, which is the overstatement L-14 names.
    :agrees? (fn [line expectation]
               (or (nil? (:line-type expectation))
                   (= (:line-type line) (:line-type expectation))))
    :detail  (fn [line expectation]
               (str "The statement reports this movement as a " (:line-type line)
                    " and the ledger records it as a " (:line-type expectation)))}])

;; ---------------------------------------------------------------------------
;; The reconciliation
;; ---------------------------------------------------------------------------

(defn- claim
  "Apply one rule across every still-unmatched line. Returns the updated state.

  Lines are offered in `:line-no` order and candidate expectations are
  considered in `:entry-id` order, so the outcome of a run depends on the values
  and on nothing else. Ambiguity — two candidate expectations — leaves the line
  unmatched for this rule and for every later one to try."
  [state rule]
  (reduce
   (fn [acc line]
     (if (contains? (:matched-lines acc) (:line-no line))
       acc
       (let [candidates (into []
                              (comp (remove #(contains? (:matched-expectations acc)
                                                        (:entry-id %)))
                                    (filter #((:matches? rule) line %)))
                              (sort-by (comp str :entry-id) (:expectations acc)))]
         (if (= 1 (count candidates))
           (let [expectation (first candidates)]
             (-> acc
                 (update :matched-lines conj (:line-no line))
                 (update :matched-expectations conj (:entry-id expectation))
                 (update :matches conj {:line-no  (:line-no line)
                                        :entry-id (:entry-id expectation)
                                        :rule-id  (:id rule)})))
           acc))))
   state
   (sort-by :line-no (:lines state))))

(defn- agreement-breaks
  [line expectation]
  (into []
        (comp (remove (fn [{:keys [agrees?]}] (agrees? line expectation)))
              (map (fn [{:keys [kind detail]}]
                     {:kind                 kind
                      :line-no              (:line-no line)
                      :entry-id             (:entry-id expectation)
                      :statement-amount     (:amount line)
                      :ledger-amount        (:amount expectation)
                      :statement-value-date (:value-date line)
                      :ledger-value-date    (:value-date expectation)
                      :detail               (detail line expectation)})))
        agreement-checks))

(defn reconcile
  "Match a statement's lines against the ledger's expectations, and name every
  disagreement.

      (reconcile {:lines [...] :expectations [...]})
      ;; => {:matches [{:line-no … :entry-id … :rule-id …} …]
      ;;     :breaks  [{:kind … :line-no … :entry-id … :detail …} …]}

  Deterministic in output as well as in decision: matches come out in
  `:line-no` order, and breaks come out agreement-first (in line order), then
  unmatched lines (in line order), then unmatched expectations (in `:entry-id`
  order). A function whose output order moved between runs would make every
  assertion about it a sample."
  [{:keys [lines expectations]}]
  (let [{:keys [matches matched-lines matched-expectations]}
        (reduce claim
                {:lines lines :expectations expectations
                 :matched-lines #{} :matched-expectations #{} :matches []}
                rules)
        by-line        (into {} (map (juxt :line-no identity)) lines)
        by-entry       (into {} (map (juxt :entry-id identity)) expectations)
        ordered        (sort-by :line-no matches)
        ;; The references a matched line claimed. An unmatched line naming one
        ;; of these is not merely unmatched — it is a second claim on a movement
        ;; that is already accounted for, which is a different fact and a
        ;; different piece of work.
        claimed        (into #{} (keep (comp reference-of by-line :line-no)) ordered)]
    {:matches ordered
     :breaks
     (vec
      (concat
       (mapcat (fn [{:keys [line-no entry-id]}]
                 (agreement-breaks (by-line line-no) (by-entry entry-id)))
               ordered)

       (for [line (sort-by :line-no lines)
             :when (not (contains? matched-lines (:line-no line)))
             :let  [reference  (reference-of line)
                    duplicate? (contains? claimed reference)]]
         {:kind                 (if duplicate?
                                  "duplicate-statement-line"
                                  "statement-line-unmatched")
          :line-no              (:line-no line)
          :entry-id             nil
          :statement-amount     (:amount line)
          :ledger-amount        nil
          :statement-value-date (:value-date line)
          :ledger-value-date    nil
          :detail               (if duplicate?
                                  (str "This line claims payment reference " reference
                                       ", which another line of this statement has "
                                       "already matched to a ledger movement")
                                  (str "The statement reports "
                                       (money/format-amount (:amount line))
                                       " that CloFin's ledger does not record on this "
                                       "account for this period"))})

       (for [expectation (sort-by (comp str :entry-id) expectations)
             :when (not (contains? matched-expectations (:entry-id expectation)))]
         {:kind                 "expectation-unmatched"
          :line-no              nil
          :entry-id             (:entry-id expectation)
          :statement-amount     nil
          :ledger-amount        (:amount expectation)
          :statement-value-date nil
          :ledger-value-date    (:value-date expectation)
          :detail               (str "CloFin's ledger records "
                                     (money/format-amount (:amount expectation))
                                     " on this account that the statement does not "
                                     "report")})))}))
