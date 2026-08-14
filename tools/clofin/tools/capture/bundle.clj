(ns clofin.tools.capture.bundle
  "Assembling a capture into a bundle, and the one path that writes it out.

  A bundle is the whole record of one scenario: every request and response,
  every journal entry with its lines, every audit event, the chart of accounts
  those lines refer to, the sand-table rows, and the stamp that says which
  commit produced all of it.

  ## Fail closed, not fail tidy

  `write!` validates the stamp **before** it opens a file. That ordering is
  the acceptance criterion (AC-2) and standing lesson **L-13**: a harness that
  wrote the bundle and then noticed the stamp was missing would leave a file
  on disk that looks exactly like output, and the next step in the pipeline
  copies files. There is deliberately no second way to write a bundle — no
  `spit` in a scenario, no `--no-verify`, no environment variable that skips
  it. If a field cannot be resolved, the run produces nothing.

  ## Why the scope statement is carried in three places

  The captured `GET /` response is written once as its own fixture, and each
  bundle carries both the digest of that fixture's body and the disclaimer
  string itself. That is a copy, and copies drift — so the copies are
  compared here, at write time, and again by `clofin-trace`'s
  `disclaimer-verbatim` check over the built page. Three artifacts that must
  be byte-identical are three chances to notice; one artifact quoted from
  memory in a template is standing lesson **L-6**."
  (:require [clofin.ledger.account :as account]
            [clofin.money :as money]
            [clofin.tools.capture.provenance :as prov]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Amounts, rendered once, here
;; ---------------------------------------------------------------------------

(defn display-amount
  "`{\"currency\": \"SGD\", \"minorUnits\": 375000}` → `\"SGD 3750.00\"`.

  Money crosses the wire as an integer count of minor units and nothing in the
  API ever renders it as a decimal — so somebody has to, and it must not be
  the browser. `clofin-trace` computes nothing: a decimal point placed at
  render time is arithmetic performed on a financial figure in an unaudited
  repository, which is exactly the boundary ADR-0020 draws.

  It is done here, with the system's **own** `clofin.money/format-amount`,
  against a captured commit whose copy of that namespace is asserted identical
  to this one (`clofin.tools.capture.stack/assert-formatter-matches!`). The
  minor units travel in the bundle beside the rendering, so a reader who
  distrusts the decimal point can check it."
  [amount]
  (when (map? amount)
    (let [currency (get amount "currency")
          units    (get amount "minorUnits")]
      (when (and (string? currency) (integer? units))
        (str currency " " (money/format-amount (money/of currency units)))))))

(defn provenance->wire
  "The stamp as the JSON object every consumer reads.

  Key order is fixed and the tag, SHA and coverage sit together, because the
  object is also the thing `clofin-trace` renders in one block: a SHA shown
  without its coverage invites the reader to supply the missing word, and the
  word they supply is \"audited\"."
  [p]
  (array-map
   "schemaVersion"        prov/schema-version
   "sourceCommit"         (:source-commit p)
   "sourceCommitShort"    (:source-commit-short p)
   "sourceRef"            (:source-ref p)
   "sourceUrl"            (:source-url p)
   "tag"                  (:tag p)
   "tagKind"              (:tag-kind p)
   "releaseAudit"         (array-map
                           "label"        (get-in p [:release-audit :label])
                           "statement"    (get-in p [:release-audit :statement])
                           "source"       (get-in p [:release-audit :source])
                           "sourceRef"    (get-in p [:release-audit :source-ref])
                           "sourceSha256" (get-in p [:release-audit :source-sha256]))
   "capturedAt"           (:captured-at p)
   "schemaVersionApplied" (:schema-version-applied p)
   "harness"              (array-map
                           "commit" (get-in p [:harness :commit])
                           "dirty"  (boolean (get-in p [:harness :dirty?])))))

(defn- step->wire
  [step]
  (into (array-map)
        (remove (comp nil? val))
        (array-map
         "n"         (:n step)
         "id"        (:id step)
         "kind"      (:kind step)
         "title"     (:title step)
         "narrative" (:narrative step)
         "account"   (:account step)
         "statement" (:statement step)
         "result"    (when (:result step)
                       (into (array-map) (map (fn [[k v]] [(name k) v])) (:result step)))
         "request"   (when-let [r (:request step)]
                       (into (array-map)
                             (remove (comp nil? val))
                             (array-map "method"  (:method r)
                                        "path"    (:path r)
                                        "query"   (:query r)
                                        "headers" (:headers r)
                                        "body"    (:body r)
                                        "bodyRaw" (:body-raw r))))
         "response"  (when-let [r (:response step)]
                       (array-map "status"     (:status r)
                                  "headers"    (:headers r)
                                  "body"       (:body r)
                                  "bodyRaw"    (:body-raw r)
                                  "bodySha256" (:body-sha256 r)))
         "expected"  (when-let [e (:expected step)]
                       (array-map "status" (:status e))))))

;; ---------------------------------------------------------------------------
;; The sand table
;; ---------------------------------------------------------------------------

(defn sand-table
  "The ledger sand table, built by copying captured statement responses.

  Each cell names the step it came from and repeats that step's
  `closingBalance` object. Nothing is added up here and nothing is added up
  downstream: `clofin-trace` renders the cell, and its `provenance-present`
  check re-reads the named step and fails if the two ever differ. A balance
  computed at render time — even correctly — would be a figure on the page
  that no captured response contains, which RULE 2 does not allow however
  right the arithmetic is.

  `rows` is `[{:label … :after-step-id …}]`; the accounts are the codes whose
  movement the scenario is about."
  [steps {:keys [codes rows]}]
  (let [by-id (into {} (map (juxt :id identity)) steps)]
    (array-map
     "accounts" (vec codes)
     "rows"
     (vec
      (for [{:keys [label after-step-id snapshots entries]} rows]
        (array-map
         "label" label
         "afterStep" after-step-id
         "journalEntries" (vec entries)
         "cells"
         (vec
          (for [code codes
                :let [step-id (get snapshots code)
                      step    (get by-id step-id)]]
            (do
              (when-not step
                (throw (ex-info (format (str "capture refuses: sand-table row %s names step %s for "
                                             "account %s, and no such step was recorded.")
                                        (pr-str label) (pr-str step-id) code)
                                {:row label :step step-id :account code})))
              (when-not (= code (:account step))
                (throw (ex-info (format (str "capture refuses: sand-table row %s reads account %s "
                                             "from step %s, which captured account %s.")
                                        (pr-str label) code (pr-str step-id)
                                        (pr-str (:account step)))
                                {:row label :step step-id :expected code :actual (:account step)})))
              (let [balance (get-in step [:response :body "closingBalance"])]
                (when-not (map? balance)
                  (throw (ex-info (format (str "capture refuses: step %s has no closingBalance to "
                                               "put in the sand table.")
                                          (pr-str step-id))
                                  {:step step-id})))
                (array-map "account" code
                           "sourceStep" step-id
                           "closingBalance" balance
                           "display" (display-amount balance))))))))))))

(defn verify-against-journal!
  "Every sand-table cell must equal what the captured journal implies.

  The cells are captured `GET /accounts/:id/statement` responses, and the
  journal in the same bundle is the rows those responses were derived from —
  two answers from the same system by two different routes, which is the only
  kind of agreement worth checking (**L-16**). Each row records the journal
  entry ids that existed when it was taken, so the comparison is against the
  ledger's state at that moment rather than at the end.

  The arithmetic is `clofin.ledger.account/balance` — the system's own
  function, the only place the debit-normal convention is expressed — over the
  captured lines. Nothing is reimplemented here; a second implementation of a
  balance would be a second thing that can be wrong.

  Acceptance criterion **AC-6**. A mismatch is a refusal: a sand table that
  disagreed with its own journal is the single most misleading artifact this
  project could publish."
  [table journal accounts]
  (let [entries   (into {} (map (juxt #(get % "id") identity)) journal)
        by-code   (into {} (map (juxt #(get % "code") identity)) accounts)]
    (doseq [row  (get table "rows")
            cell (get row "cells")
            :let [code    (get cell "account")
                  account (get by-code code)
                  present (set (get row "journalEntries"))]]
      (when-not account
        (throw (ex-info (format "capture refuses: sand-table account %s is not in the captured chart of accounts."
                                (pr-str code))
                        {:account code})))
      (let [lines (for [id      (get row "journalEntries")
                        line    (get (get entries id) "lines")
                        :when   (= code (get line "account_code"))]
                    {:direction (keyword (get line "direction"))
                     :amount    (money/of (str/trim (str (get line "currency")))
                                          (get line "amount_minor"))})
            currency (get account "currency")
            derived  (account/balance {:type     (keyword (get account "type"))
                                       :currency (str/trim (str currency))}
                                      lines)
            captured (get-in cell ["closingBalance" "minorUnits"])]
        (when-not (= (:minor-units derived) captured)
          (throw (ex-info
                  (format (str "capture refuses: the sand table and the journal disagree. Row %s, "
                               "account %s: the captured statement says %s minor units and the "
                               "%d journal entr%s present at that point imply %s.")
                          (pr-str (get row "label")) code (pr-str captured)
                          (count present) (if (= 1 (count present)) "y" "ies")
                          (pr-str (:minor-units derived)))
                  {:row (get row "label") :account code
                   :captured captured :derived (:minor-units derived)})))))
    :verified))

;; ---------------------------------------------------------------------------
;; Assembly
;; ---------------------------------------------------------------------------

(defn- with-line-display
  "Each journal line gains the rendering of its own amount, and nothing else.

  The line already carries `amount_minor` and `currency` as the database holds
  them; `display` is those two values put through the system's own formatter,
  so the page has something to print and no arithmetic to do."
  [entry]
  (update entry "lines"
          (fn [lines]
            (mapv (fn [line]
                    (assoc line "display"
                           (display-amount {"currency" (str/trim (str (get line "currency")))
                                            "minorUnits" (get line "amount_minor")})))
                  lines))))

(defn assemble
  "One scenario's bundle, ready to write."
  [{:keys [scenario provenance steps journal audit-events accounts sand-table
           service-info organisation]}]
  (array-map
   "schemaVersion" prov/schema-version
   "scenario"      (array-map "id"      (:id scenario)
                              "title"   (:title scenario)
                              "summary" (:summary scenario)
                              "source"  (:source scenario))
   "provenance"    (provenance->wire provenance)
   "scopeStatement" (array-map
                     "fixture"     "service-info.json"
                     "bodySha256"  (:body-sha256 service-info)
                     "disclaimer"  (:disclaimer service-info))
   "organisation"  organisation
   "accounts"      (vec accounts)
   "rendering"     (array-map
                    "amounts"
                    (array-map
                     "function" "clofin.money/format-amount"
                     "note" (str "Money crosses the API as an integer count of minor units. "
                                 "Every `display` string in this bundle was produced from the "
                                 "adjacent minorUnits by the system's own formatter, in "
                                 "clofin-core, at capture time — never in a browser.")))
   "steps"         (mapv step->wire steps)
   "sandTable"     sand-table
   "journal"       (array-map "entries" (mapv with-line-display journal)
                              "entryCount" (count journal))
   "auditEvents"   (array-map "events" (vec audit-events)
                              "count"  (count audit-events))))

;; ---------------------------------------------------------------------------
;; Writing — the only path to disk
;; ---------------------------------------------------------------------------

(defn- json-text
  "Pretty JSON, so that a bundle can be read in a diff.

  These files are reviewed by people: a reviewer who cannot see which value
  changed cannot review a provenance artifact, and a one-line 200KB document
  is not reviewable."
  [value]
  (with-out-str (json/pprint value :escape-slash false)))

(defn problems
  "Every reason `bundle` is not writable, as sentences.

  The stamp's own problems, plus the bundle-level ones: a scenario with no
  steps is an empty tape, and a scope statement that disagrees with the
  captured fixture is the drift this arrangement exists to catch."
  [bundle service-info]
  (into
   (prov/problems
    ;; `problems` works on the internal shape; the wire shape is what a bundle
    ;; carries, so it is read back through the same names.
    (let [p (get bundle "provenance")]
      {:source-commit (get p "sourceCommit")
       :source-commit-short (get p "sourceCommitShort")
       :source-ref (get p "sourceRef")
       :source-url (get p "sourceUrl")
       :tag (get p "tag")
       :tag-kind (get p "tagKind")
       :release-audit {:label (get-in p ["releaseAudit" "label"])
                       :statement (get-in p ["releaseAudit" "statement"])
                       :source (get-in p ["releaseAudit" "source"])
                       :source-ref (get-in p ["releaseAudit" "sourceRef"])
                       :source-sha256 (get-in p ["releaseAudit" "sourceSha256"])}
       :captured-at (get p "capturedAt")
       :schema-version-applied (get p "schemaVersionApplied")
       :harness {:commit (get-in p ["harness" "commit"])}}))
   (remove
    nil?
    [(when (not= prov/schema-version (get bundle "schemaVersion"))
       (format "schemaVersion is %s, expected %s"
               (pr-str (get bundle "schemaVersion")) (pr-str prov/schema-version)))
     (when (str/blank? (str (get-in bundle ["scenario" "id"])))
       "scenario.id is missing")
     (when (empty? (get bundle "steps"))
       "steps is empty: a bundle with no captured calls is not a capture")
     (when (str/blank? (str (get-in bundle ["scopeStatement" "disclaimer"])))
       "scopeStatement.disclaimer is missing: the captured GET / disclaimer is what the page renders")
     (when (and service-info
                (not= (get-in bundle ["scopeStatement" "disclaimer"])
                      (:disclaimer service-info)))
       "scopeStatement.disclaimer differs from the captured GET / fixture")
     (when (and service-info
                (not= (get-in bundle ["scopeStatement" "bodySha256"])
                      (:body-sha256 service-info)))
       "scopeStatement.bodySha256 differs from the digest of the captured GET / body")])))

(defn write!
  "Validate, then write. There is no other way to produce a bundle file.

  Returns `{:path :sha256}`. Throws — leaving nothing behind — when the bundle
  is not fully stamped."
  [{:keys [path bundle service-info]}]
  (let [found (problems bundle service-info)]
    (when (seq found)
      (throw (ex-info (str "capture refuses to write " path
                           ": the bundle is not fully stamped.\n  - "
                           (str/join "\n  - " found))
                      {:path (str path) :problems found})))
    (let [text (json-text bundle)
          file (io/file path)]
      (io/make-parents file)
      (spit file text)
      {:path (str path) :sha256 (prov/sha256 text)})))

(defn write-fixture!
  "Write the captured `GET /` response as its own fixture.

  Its own file because it is the one artifact `clofin-trace` compares byte for
  byte, and a value that must be compared byte for byte is better held
  somewhere nothing else is being edited."
  [{:keys [path provenance service-info]}]
  (let [payload (array-map
                 "schemaVersion" prov/schema-version
                 "fixture"       "GET /"
                 "provenance"    (provenance->wire provenance)
                 "request"       (array-map "method" "GET" "path" "/")
                 "response"      (array-map
                                  "status"     (:status service-info)
                                  "headers"    (:headers service-info)
                                  "bodyRaw"    (:body-raw service-info)
                                  "body"       (:body service-info)
                                  "bodySha256" (:body-sha256 service-info))
                 "disclaimer"    (:disclaimer service-info))
        found   (prov/problems
                 (let [p (provenance->wire provenance)]
                   {:source-commit (get p "sourceCommit")
                    :source-commit-short (get p "sourceCommitShort")
                    :source-ref (get p "sourceRef")
                    :source-url (get p "sourceUrl")
                    :tag (get p "tag")
                    :tag-kind (get p "tagKind")
                    :release-audit {:label (get-in p ["releaseAudit" "label"])
                                    :statement (get-in p ["releaseAudit" "statement"])
                                    :source (get-in p ["releaseAudit" "source"])
                                    :source-ref (get-in p ["releaseAudit" "sourceRef"])
                                    :source-sha256 (get-in p ["releaseAudit" "sourceSha256"])}
                    :captured-at (get p "capturedAt")
                    :schema-version-applied (get p "schemaVersionApplied")
                    :harness {:commit (get-in p ["harness" "commit"])}}))]
    (when (seq found)
      (throw (ex-info (str "capture refuses to write " path
                           ": the fixture is not fully stamped.\n  - " (str/join "\n  - " found))
                      {:path (str path) :problems found})))
    (when (str/blank? (str (:disclaimer service-info)))
      (throw (ex-info (str "capture refuses to write " path
                           ": the captured GET / response carries no disclaimer.")
                      {:path (str path)})))
    (let [text (json-text payload)
          file (io/file path)]
      (io/make-parents file)
      (spit file text)
      {:path (str path) :sha256 (prov/sha256 text)})))

(defn write-quotations!
  "Write the control statements and invariants the walkthrough may quote.

  Stamped like everything else, because a quotation is only worth anything
  with the commit it was taken from attached — RULE 3 says *attributed and
  linked at the captured commit*, and the link is built from the stamp."
  [{:keys [path provenance quotations]}]
  (let [payload (array-map
                 "schemaVersion" prov/schema-version
                 "fixture"       "quotations"
                 "provenance"    (provenance->wire provenance)
                 "note"          (str "Extracted verbatim from the captured commit's own "
                                      "docs/COMPLIANCE.md and docs/DOMAIN_MODEL.md. The only "
                                      "transformation is unwrapping hard-wrapped lines into the "
                                      "paragraph they are. ADR-0020 RULE 3.")
                 "controls"      (get quotations "controls")
                 "invariants"    (get quotations "invariants"))
        text    (json-text payload)
        file    (io/file path)]
    (when (empty? (get quotations "controls"))
      (throw (ex-info (str "capture refuses to write " path ": no control statements were extracted.")
                      {:path (str path)})))
    (io/make-parents file)
    (spit file text)
    {:path (str path) :sha256 (prov/sha256 text)}))

(defn write-manifest!
  "One index of everything a capture run produced.

  `clofin-trace` reads this rather than a directory listing, for the reason
  `clofin.db.migrate` reads a migration index rather than a listing: a listing
  is whatever happens to be in the directory, and a fixture that was supposed
  to be there and is not should be a failure rather than a shorter page."
  [{:keys [path provenance fixture quotations bundles]}]
  (let [payload (array-map
                 "schemaVersion" prov/schema-version
                 "provenance"    (provenance->wire provenance)
                 "fixture"       (array-map "path" (:name fixture) "sha256" (:sha256 fixture))
                 "quotations"    (array-map "path" (:name quotations) "sha256" (:sha256 quotations))
                 "bundles"       (vec (for [b bundles]
                                        (array-map "id" (:id b)
                                                   "title" (:title b)
                                                   "path" (:name b)
                                                   "sha256" (:sha256 b)))))
        text    (json-text payload)
        file    (io/file path)]
    (io/make-parents file)
    (spit file text)
    {:path (str path) :sha256 (prov/sha256 text)}))
