(ns clofin.screening.list
  "What a screening list *is*, and the refusals that keep a malformed one from
  ever being loaded.

  A list is versioned, synthetic reference data:

      {:version \"synthetic-2026-10-v1\"
       :entries [{:id \"SYN-0001\"
                  :rules [{:field :creditor-name :operator :exact
                           :value \"Blocked Counterparty Ltd\"}]}
                 …]}

  It is loaded by a tool (`clofin.tools.screening-list`), never by a client and
  never by a migration, and once loaded a version is immutable but for its
  retirement. `validate` is what the tool asks before it writes anything, so
  every refusal here is a list that never reached the database.

  **Synthetic.** The shipped list's names, accounts and the `ZZ` country code
  name nothing real — `ZZ` is a user-assigned ISO 3166-1 code that names no
  country — and nothing about this format claims to model a real list.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.error :as err]
            [clofin.screening.rules :as rules]
            [clojure.string :as str]))

(def lock-key
  "The key of the PostgreSQL transaction-scoped advisory lock that serialises
  the accepted list against the decisions taken on it (017-REQ R-7).

  Every screening decision — core's at `submit`, a client's result being
  recorded, and the repository's own gate — holds it **shared** until its
  transaction ends (`pg_advisory_xact_lock_shared`); the loading tool holds it
  **exclusive** (`pg_advisory_xact_lock`) for the whole of a load, a
  replacement or a retirement. So a list change waits for every decision in
  flight, a decision that starts meanwhile waits for the change to commit and
  then reads the list in a statement whose snapshot follows it, and a waiting
  change is not overtaken by later decisions: PostgreSQL grants a queued
  exclusive request before shared requests that arrive after it.

  An advisory lock rather than a row or table lock because it needs no
  privilege on `screening_list`, which the service only ever reads. Arbitrary
  but stable; distinct from the migrator's."
  1701701701701701701)

(def ^:private printable-ascii
  "The shape a list version and an entry id share with the schema
  (`screening_list_version_shape`, `screening_entry_id_shape`): printable
  ASCII, no spaces, 1–128 characters."
  #"^[\x21-\x7E]{1,128}$")

(def refusal-reasons
  "Every reason `validate` refuses a list under. Each is a `422`-class
  refusal with the reason named, so a loader's operator reads which rule the
  file broke rather than a stack trace.

  `unsupported-rule-field` is the one ADR-0028 D5 promises \"at load\": an
  `originator-*` rule has no per-instruction value in core."
  (sorted-set "invalid-version" "empty-entries" "invalid-entry"
              "duplicate-entry-id" "empty-rules" "unsupported-rule-field"
              "unsupported-operator" "blank-rule-value"))

(defn- refuse!
  [reason message data]
  (err/fail! :unprocessable message (assoc data :reason (refusal-reasons reason))))

(defn- validate-rule
  [entry-id rule]
  (when-not (map? rule)
    (refuse! "invalid-entry" (str "Entry " entry-id " has a rule that is not a map")
             {:entry entry-id}))
  (let [{:keys [field operator value]} rule]
    (when-not (contains? rules/fields field)
      (refuse! "unsupported-rule-field"
               (str "Entry " entry-id " names rule field " (pr-str field)
                    ", which core does not screen: the fields are "
                    (str/join ", " (map name (sort (keys rules/fields)))))
               {:entry entry-id :field (if (keyword? field) (name field) (str field))}))
    (when-not (contains? rules/operators operator)
      (refuse! "unsupported-operator"
               (str "Entry " entry-id " names operator " (pr-str operator)
                    "; the only operator is exact")
               {:entry entry-id :operator (if (keyword? operator) (name operator) (str operator))}))
    (when-not (and (string? value) (not (str/blank? value)))
      (refuse! "blank-rule-value" (str "Entry " entry-id " has a rule with a blank value")
               {:entry entry-id :field (name field)}))
    {:field field :operator operator :value value}))

(defn- validate-entry
  [entry]
  (when-not (and (map? entry) (string? (:id entry)) (re-matches printable-ascii (:id entry)))
    (refuse! "invalid-entry"
             "Every entry needs an id of 1-128 printable ASCII characters without spaces"
             {:entry (pr-str (when (map? entry) (:id entry)))}))
  (let [{:keys [id rules]} entry]
    (when-not (and (sequential? rules) (seq rules))
      (refuse! "empty-rules" (str "Entry " id " has no rules: an entry matches when every rule does, "
                                  "so an entry with none would match everything")
               {:entry id}))
    {:id id :rules (mapv #(validate-rule id %) rules)}))

(defn validate
  "Return `list` normalised — the fields and operators as keywords, the entries
  as vectors — or refuse it naming the first rule it breaks.

  Refused, each with its reason (`refusal-reasons`):

  - a version outside `^[\\x21-\\x7E]{1,128}$`, or beginning with `-` —
    `invalid-version`: the loading tool's command line takes a version as the
    value of `--replacing` and of `retire`, where a leading `-` reads as a
    flag, so such a list could be loaded and then never replaced or retired
  - no entries at all — `empty-entries`: an empty list would make every
    instruction `clear`, which is the control's absence wearing its name
  - an entry without a well-formed id — `invalid-entry`; with no rules —
    `empty-rules`; an id twice — `duplicate-entry-id`
  - a rule on a field outside the three — `unsupported-rule-field`, naming the
    entry and the field
  - an operator other than `exact` — `unsupported-operator`
  - a blank value — `blank-rule-value`"
  [list]
  (when-not (map? list)
    (refuse! "invalid-version" "A screening list is a map with :version and :entries" {}))
  (let [{:keys [version entries]} list]
    (when-not (and (string? version) (re-matches printable-ascii version)
                   (not (str/starts-with? version "-")))
      (refuse! "invalid-version"
               "A list version is 1-128 printable ASCII characters without spaces, not beginning with -"
               {:version (pr-str version)}))
    (when-not (and (sequential? entries) (seq entries))
      (refuse! "empty-entries"
               (str "List " version " has no entries; an empty list would clear every instruction")
               {:version version}))
    (let [validated (mapv validate-entry entries)
          dupes (->> (frequencies (map :id validated))
                     (keep (fn [[id n]] (when (> n 1) id)))
                     sort)]
      (when (seq dupes)
        (refuse! "duplicate-entry-id" (str "List " version " names entry " (first dupes) " more than once")
                 {:version version :entry (first dupes)}))
      {:version version :entries validated})))

;; ---------------------------------------------------------------------------
;; Rules as comparable values
;; ---------------------------------------------------------------------------

(defn rule-key
  "A rule as a comparable triple of strings."
  [{:keys [field operator value]}]
  [(name field) (name operator) value])

(defn same-rules?
  "True when two entries' rules are the same rules.

  Order-insensitive — rules AND within an entry, so their order carries no
  meaning — and **duplicate-sensitive**: a client claiming a rule twice is not
  quoting the list. Used by recording to refuse a client's claim to have matched
  an entry on rules the list does not have (`matched-entries-unknown`)."
  [rules-a rules-b]
  (= (sort (map rule-key rules-a)) (sort (map rule-key rules-b))))
