(ns clofin.screening.rules
  "The screening rules engine: does an instruction match a list's entries?

  C-07's matching, and deliberately the smallest one that can be stated
  exactly (docs/briefs/017-TASK-screening-and-cases.md, A-2):

  - **An entry matches when every one of its rules matches** — rules AND within
    an entry; **entries OR across the list**. An instruction is a `hit` when at
    least one entry matches, and `clear` otherwise.
  - **`exact` is string equality** — no case folding, no trimming, no
    normalisation of any kind. `\"Blocked Counterparty Ltd \"` does not match
    `\"Blocked Counterparty Ltd\"`. A matcher that folded case or transliterated
    would be a *different operator* with a different false-positive profile;
    it is not built, and nothing here may be described as one.
  - **A field the instruction does not carry matches no rule on it.** An
    instruction without a `creditor-country` is not a hit on a country rule,
    whatever the rule's value.

  **The list is synthetic and the matching is exact.** Nothing in this
  namespace — or in any sentence describing it — makes a claim about real-world
  screening quality. It builds the control's shape: a deterministic,
  reproducible decision against a versioned list.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.error :as err]))

(def fields
  "Every field a rule may read, and the instruction key it reads.

  Three, and only three — the instruction's beneficiary fields. ADR-0028 D5's
  satellite mapping lands here: `beneficiary-name → creditor-name`,
  `beneficiary-party-id → creditor-account`, `beneficiary-country →
  creditor-country`. An `originator-*` rule has no per-instruction value in
  core, so a list naming one is refused at load (`clofin.screening.list`).

  Identical to `screening_rule_field_known` in migration `0015`;
  `clofin.db.vocabulary-test` compares the two in both directions, and
  `clofin.contract-test` compares this set with `ScreeningRule.field`."
  {:creditor-name    :creditor-name
   :creditor-account :creditor-account
   :creditor-country :creditor-country})

(def operators
  "Every operator a rule may use. One.

  Identical to `screening_rule_operator_known`. PostgreSQL renders a one-value
  `IN` list as `=`, so `clofin.db.vocabulary-test`'s discovery of
  `= ANY (ARRAY[…])` constraints does not see this one; a test of its own
  compares the two."
  (sorted-set :exact))

(defn- rule-matches?
  "True when `instruction` satisfies `rule`.

  A field or operator outside the vocabulary is a **defect**, thrown rather
  than read as `false`: an entry that silently could never match is a screening
  list that silently screens for less than it says. A list reaches this function
  only after `clofin.screening.list/validate` and the schema's own constraints,
  so a throw here means one of those was bypassed."
  [instruction {:keys [field operator value]}]
  (let [k (or (get fields field)
              (throw (ex-info (str "A screening rule names a field the engine does not read: " field)
                              {:field field})))]
    (when-not (contains? operators operator)
      (throw (ex-info (str "A screening rule names an operator the engine does not have: " operator)
                      {:operator operator})))
    (let [actual (get instruction k)]
      ;; `exact`: equality of the two strings as stored, and nothing else. An
      ;; absent field is not equal to any value.
      (and (string? actual) (string? value) (= actual value)))))

(defn entry-matches?
  "True when every rule of `entry` matches `instruction`.

  An entry with **no rules** is refused rather than evaluated: `every?` over
  nothing is `true`, so such an entry would match every instruction — or, if
  the convention were reversed, none. Neither is a decision anyone took. The
  schema requires at least one rule per entry in the contract (`ScreeningEntry`
  `minItems: 1`) and `clofin.screening.list/validate` refuses one at load."
  [instruction {:keys [id rules]}]
  (when (empty? rules)
    (throw (ex-info (str "Screening entry " id " has no rules and cannot be evaluated")
                    {:entry id})))
  (every? #(rule-matches? instruction %) rules))

(defn evaluate
  "Screen `instruction` against `entries`.

  Returns `{:outcome :clear|:hit :matched [entry-id …]}`. `:matched` is the ids
  of every matching entry, **sorted** — so the answer is a function of the set
  of entries and never of the order they were loaded or read in, which
  `clofin.screening.rules-test` asserts as a property. A decision that changed
  with row order would not be reproducible from what was retained.

  `:outcome` is `:hit` exactly when `:matched` is non-empty."
  [instruction entries]
  (when-not (map? instruction)
    (err/invalid! "Screening needs an instruction" {}))
  (let [matched (into [] (comp (filter #(entry-matches? instruction %))
                               (map :id)
                               (distinct))
                      (sort-by :id entries))]
    {:outcome (if (seq matched) :hit :clear)
     :matched (vec (sort matched))}))
