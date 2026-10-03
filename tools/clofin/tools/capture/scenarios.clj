(ns clofin.tools.capture.scenarios
  "The scenarios, written as the calls they make.

  Each one is a transcription of an acceptance-test script that a human runs
  by hand — `docs/uat/UAT-005-segregation-of-duties.md`,
  `docs/uat/UAT-006-settlement-simulation.md` and
  `docs/uat/UAT-007-reconciliation-and-breaks.md` — into calls a harness makes
  and records. That lineage is deliberate: the UAT scripts are the project's own
  statement of what is worth watching, they are reviewed, and they already say
  which steps are supposed to be refused.

  **Every step that is supposed to fail declares the status it expects.** A
  scenario narrating a refusal beside a captured `201` would be fiction with a
  commit SHA attached, so the capture stops instead
  (`clofin.tools.capture.recorder/request!`).

  **Narratives describe the picture, never the guarantee.** ADR-0020 RULE 3,
  worked in amendment A7 of the brief: *\"the highlighted row is the item that
  timed out\"* is this namespace's to write; *\"a settled or unknown
  instruction may never enter a second batch\"* is a claim about what the
  system guarantees and belongs to `COMPLIANCE.md`, quoted with attribution by
  the page that shows it. Where a step's meaning rests on a control, the step
  names the control id and the walkthrough quotes the document — it does not
  restate it here in better words."
  (:require [clofin.tools.capture.recorder :as rec]
            [clofin.tools.capture.store :as store]))

;; ---------------------------------------------------------------------------
;; Shared synthetic material
;; ---------------------------------------------------------------------------

(def value-date
  "The value date every captured instruction carries.

  Fixed rather than derived from today's date so that two captures of the same
  scenario differ only where the system made them differ. It is the same date
  UAT-006 uses."
  "2026-12-01")

(def statement-window
  "The half-open period every captured account statement is asked for.

  A fixed window for the same reason as `value-date`. `to` is far enough ahead
  to include everything the scenario posts; the period is `from` inclusive and
  `to` exclusive (ADR-0011)."
  {"from" "2020-01-01T00:00:00Z" "to" "2030-01-01T00:00:00Z"})

(defn- seed!
  "Run seed SQL and record it, refusing to continue if it did not apply.

  Seeding is in SQL because CloFin has no endpoint that creates an actor,
  grants a role or sets a limit, and that absence is itself a control decision
  (UAT-005 §2).

  `expect-error`, a pattern, names **which** refusal a step is about. Without
  it any failure passes as the refusal — including a statement that never
  reached the guard it was written to show, refused instead for a malformed
  identifier. UAT-007's statements carry placeholders a replay substitutes, and
  a substitution gone wrong is exactly that failure (016-REQ)."
  [{:keys [rec conn]} {:keys [id title narrative statement expect-refusal expect-error]}]
  (let [result (store/execute! conn statement)]
    (when (and (not expect-refusal) (not (:ok result)))
      (throw (ex-info (format "capture refuses: seed step %s failed: %s" id (:error result))
                      {:step id :statement statement :result result})))
    (when (and expect-refusal (:ok result))
      (throw (ex-info (format (str "capture refuses: step %s expected the database to refuse this "
                                   "statement and it succeeded.") id)
                      {:step id :statement statement})))
    (when (and expect-refusal expect-error
               (not (re-find expect-error (str (:error result)))))
      (throw (ex-info (format (str "capture refuses: step %s expected the database to refuse this "
                                   "statement with %s, and it refused it with: %s")
                              id (pr-str (str expect-error)) (:error result))
                      {:step id :statement statement :result result
                       :expected (str expect-error)})))
    (rec/sql! rec {:id id :title title :narrative narrative
                   :statement statement :result result})))

(defn- organisation!
  [{:keys [rec]} {:keys [short-name legal-name narrative]}]
  (rec/request! rec {:id "organisation-created"
                     :title "An organisation to act in"
                     :narrative narrative
                     :method "POST" :path "/organisations"
                     :body {"legalName" legal-name "shortName" short-name}
                     :expect-status 201}))

(defn- account!
  [{:keys [rec]} {:keys [id actor code name type currency narrative]}]
  (rec/request! rec {:id id
                     :title (str "Open " code)
                     :narrative narrative
                     :method "POST" :path "/accounts"
                     :headers {"x-actor-id" actor}
                     :body {"code" code "name" name "type" type "currency" currency}
                     :expect-status 201}))

(defn- balance!
  "Capture one account's statement — a cell of the sand table."
  [{:keys [rec]} {:keys [id actor account-id code narrative]}]
  (rec/request! rec {:id id
                     :kind "balance-snapshot"
                     :account code
                     :title (str "Closing balance — " code)
                     :narrative narrative
                     :method "GET"
                     :path (str "/accounts/" account-id "/statement")
                     :query statement-window
                     :headers {"x-actor-id" actor}
                     :expect-status 200}))

(defn- balances!
  "Capture every sand-table account at one moment.

  Returns `{:snapshots {code → step-id} :entries [journal-entry-id …]}`.

  The journal entry ids are read at the same moment as the balances, and they
  are what makes the sand table checkable rather than merely captured: with
  them, `clofin.tools.capture.bundle/verify-against-journal!` can put the
  ledger's own `clofin.ledger.account/balance` over exactly the entries that
  existed when the row was taken, and refuse if the number the API returned
  and the number its own journal implies are not the same. They travel in the
  bundle too, so a reader can redo the sum."
  [{:keys [conn] :as ctx} {:keys [at actor accounts narrative organisation-id]}]
  {:snapshots (into {}
                    (for [{:keys [code id]} accounts]
                      [code (:id (balance! ctx {:id (str "balance-" at "-" code)
                                                :actor actor
                                                :account-id id
                                                :code code
                                                :narrative narrative}))]))
   :entries (mapv #(get % "id")
                  (store/query conn
                               (str "select id from journal_entry "
                                    " where organisation_id = ?::uuid order by id")
                               organisation-id))})

(defn- body [step k] (get-in step [:response :body k]))

(defn- closing
  "The captured closing balance, in minor units, of the recorded step `id`."
  [rec id]
  (some #(when (= id (:id %)) (get-in % [:response :body "closingBalance" "minorUnits"]))
        (rec/steps rec)))

;; ---------------------------------------------------------------------------
;; Scenario 1 — segregation of duties, attempted and refused
;; ---------------------------------------------------------------------------

(def ^:private priya "11111111-1111-1111-1111-111111111111")
(def ^:private wei   "22222222-2222-2222-2222-222222222222")
(def ^:private sam   "44444444-4444-4444-4444-444444444444")
(def ^:private rae   "55555555-5555-5555-5555-555555555555")
(def ^:private tom   "66666666-6666-6666-6666-666666666666")
(def ^:private nadia "33333333-3333-3333-3333-333333333333")

(def ^:private uat005-actors
  "Every actor the segregation scenario seeds. It names them directly rather
  than through `people`, so the roster's distinctness test reads them here."
  [priya wei nadia sam rae tom])

(defn segregation-of-duties
  "UAT-005, replayed: the violations attempted, and the refusals that answer.

  Two refusals matter more than the rest and both are here. Tom holds
  `operator`, which carries `payment/submit`, and is still refused Priya's
  draft — the answer is not \"ask for a permission\" but \"this is not your
  instruction\" (finding F-001). And Priya, granted the approver role
  mid-scenario, is still refused her own payment."
  [{:keys [rec conn] :as ctx}]
  (rec/note! rec
             {:id "scenario-note"
              :title "What you are about to watch"
              :narrative (str "Five actors are seeded with roles, then three things are "
                              "attempted that the model does not permit: an operator opening "
                              "a ledger account, one operator submitting another's draft, and "
                              "the maker approving her own payment. Each is refused by the "
                              "running system; the refusals are the captured responses below.")})
  (let [org (organisation! ctx {:short-name "capture-uat005"
                                :legal-name "Meridian Freight Holdings Pte Ltd"
                                :narrative (str "Organisation creation is the one unauthenticated "
                                                "operation: there is no actor until an "
                                                "organisation exists to hold one.")})
        org-id (body org "id")]

    (seed! ctx {:id "seed-actors"
                :title "Seed the actors and the organisation's approval policy"
                :narrative (str "There is deliberately no endpoint that creates an actor, grants "
                                "a role or sets a limit. Priya raises payments; Wei and Nadia "
                                "approve, to different ceilings; Sam opens accounts; Rae reads "
                                "the trail; Tom is a second operator.")
                :statement (format
                            (str "insert into actor (id, organisation_id, display_name) values "
                                 "('%s','%s','Priya (maker)'),"
                                 "('%s','%s','Wei (checker)'),"
                                 "('%s','%s','Nadia (checker)'),"
                                 "('%s','%s','Sam (controller)'),"
                                 "('%s','%s','Rae (auditor)'),"
                                 "('%s','%s','Tom (second operator)'); "
                                 "insert into actor_role (actor_id, role) values "
                                 "('%s','operator'),('%s','approver'),"
                                 "('%s','approver'),"
                                 "('%s','controller'),('%s','auditor'),('%s','operator'); "
                                 "insert into approver_limit (actor_id, currency, limit_minor) values "
                                 "('%s','SGD',500000),"
                                 "('%s','SGD',5000000); "
                                 "insert into approval_threshold "
                                 "(organisation_id, currency, from_minor, approvals_required) "
                                 "values ('%s','SGD',0,1),('%s','SGD',100000,2);")
                            priya org-id wei org-id nadia org-id sam org-id rae org-id tom org-id
                            priya wei nadia sam rae tom
                            wei nadia
                            org-id org-id)})

    (seed! ctx {:id "superuser-refused"
                :title "Grant yourself a stronger role, and watch the database refuse"
                :narrative (str "There is no superuser role in the model, and one cannot be "
                                "added by writing a row. The refusal below is the check "
                                "constraint's own message.")
                :expect-refusal true
                :statement (format "insert into actor_role (actor_id, role) values ('%s','superuser');"
                                   priya)})

    (rec/request! rec {:id "account-create-refused"
                       :title "An operator cannot open a ledger account"
                       :narrative (str "Priya holds `operator`. The response names the permission "
                                       "she lacks and does not list what she can do — a refusal "
                                       "that enumerated capabilities would be a capability "
                                       "listing.")
                       :method "POST" :path "/accounts"
                       :headers {"x-actor-id" priya}
                       :body {"code" "1100-CLIENT-FUNDS" "name" "Client funds — pooled"
                              "type" "asset" "currency" "SGD"}
                       :expect-status 403})

    (let [acct (account! ctx {:id "account-created"
                              :actor sam
                              :code "1100-CLIENT-FUNDS"
                              :name "Client funds — pooled"
                              :type "asset" :currency "SGD"
                              :narrative (str "The same request as the one just refused, from Sam, "
                                              "who holds `controller`. Same request, different "
                                              "actor, opposite outcome.")})
          acct-id (body acct "id")
          pi (rec/request! rec
                           {:id "payment-raised"
                            :title "Priya raises a payment"
                            :narrative (str "SGD 500.00. `createdBy` is not a field the request "
                                            "sent — it is who the request authenticated as.")
                            :method "POST" :path "/payment-instructions"
                            :headers {"x-actor-id" priya
                                      "idempotency-key" "capture-uat005-raise-1"}
                            :body {"debtorAccountId" acct-id
                                   "creditorName" "Pacific Rim Logistics Pte Ltd"
                                   "creditorAccount" "SG-SYNTH-88012345"
                                   "amount" {"currency" "SGD" "minorUnits" 50000}
                                   "valueDate" value-date
                                   "purposeCode" "SUPP"}
                            :expect-status 201})
          pi-id (body pi "id")]

      (rec/request! rec {:id "created-by-refused"
                         :title "A request that tries to name its own maker is refused"
                         :narrative (str "Sending `createdBy` is refused rather than quietly "
                                         "ignored: a caller that believed it had set the maker "
                                         "and was overridden would be reading a different "
                                         "payment from the one that exists.")
                         :method "POST" :path "/payment-instructions"
                         :headers {"x-actor-id" priya
                                   "idempotency-key" "capture-uat005-raise-createdby"}
                         :body {"debtorAccountId" acct-id
                                "createdBy" wei
                                "creditorName" "Pacific Rim Logistics Pte Ltd"
                                "creditorAccount" "SG-SYNTH-88012345"
                                "amount" {"currency" "SGD" "minorUnits" 50000}
                                "valueDate" value-date
                                "purposeCode" "SUPP"}
                         :expect-status 422})

      (rec/request! rec {:id "payment-submitted"
                         :title "Priya submits her own draft"
                         :narrative "The instruction moves to `pendingApproval`."
                         :method "POST" :path (str "/payment-instructions/" pi-id "/submission")
                         :headers {"x-actor-id" priya
                                   "idempotency-key" "capture-uat005-submit-1"}
                         :body {}
                         :expect-status 200})

      (let [pi3 (rec/request! rec
                              {:id "second-draft-raised"
                               :title "A second draft, also Priya's"
                               :narrative "Raised so that somebody else can try to submit it."
                               :method "POST" :path "/payment-instructions"
                               :headers {"x-actor-id" priya
                                         "idempotency-key" "capture-uat005-raise-3"}
                               :body {"debtorAccountId" acct-id
                                      "creditorName" "Pacific Rim Logistics Pte Ltd"
                                      "creditorAccount" "SG-SYNTH-88012345"
                                      "amount" {"currency" "SGD" "minorUnits" 50000}
                                      "valueDate" value-date
                                      "purposeCode" "SUPP"}
                               :expect-status 201})
            pi3-id (body pi3 "id")]

        (rec/request! rec {:id "foreign-submit-refused"
                           :title "Tom tries to submit Priya's draft"
                           :narrative (str "Tom holds `operator`, which carries `payment/submit`. "
                                           "He is refused anyway, and the rule named in the "
                                           "response is `creator-only`. This step did not exist "
                                           "when UAT-005 was first written, and its absence is "
                                           "why audit finding F-001 reached production code.")
                           :method "POST" :path (str "/payment-instructions/" pi3-id "/submission")
                           :headers {"x-actor-id" tom
                                     "idempotency-key" "capture-uat005-submit-tom"}
                           :body {}
                           :expect-status 403})

        (rec/request! rec {:id "second-draft-unmoved"
                           :title "The draft has not moved"
                           :narrative "Still `draft`. The refusal changed nothing."
                           :method "GET" :path (str "/payment-instructions/" pi3-id)
                           :headers {"x-actor-id" priya}
                           :expect-status 200}))

      (rec/request! rec {:id "self-approval-refused"
                         :title "Priya tries to approve her own payment"
                         :narrative (str "The reason is machine-readable — `self-approval` — "
                                         "rather than prose a client would have to parse.")
                         :method "POST" :path (str "/payment-instructions/" pi-id "/approvals")
                         :headers {"x-actor-id" priya
                                   "idempotency-key" "capture-uat005-approve-self"}
                         :body {"decision" "approved"}
                         :expect-status 403})

      (seed! ctx {:id "grant-priya-approver"
                  :title "Grant Priya the approver role as well"
                  :narrative "So that the next attempt fails for a reason that is not a missing role."
                  :statement (format (str "insert into actor_role (actor_id, role) values ('%s','approver'); "
                                          "insert into approver_limit (actor_id, currency, limit_minor) "
                                          "values ('%s','SGD',99999999);")
                                     priya priya)})

      (rec/request! rec {:id "self-approval-refused-again"
                         :title "Priya, now an approver, tries again"
                         :narrative (str "Still refused, and for the same reason. Which roles an "
                                         "actor happens to hold is not what this refusal is "
                                         "about.")
                         :method "POST" :path (str "/payment-instructions/" pi-id "/approvals")
                         :headers {"x-actor-id" priya
                                   "idempotency-key" "capture-uat005-approve-self-2"}
                         :body {"decision" "approved"}
                         :expect-status 403})

      (seed! ctx {:id "revoke-priya-approver"
                  :title "Remove the extra grant again"
                  :narrative "Leaving the organisation as it was before the demonstration."
                  :statement (format (str "delete from approver_limit where actor_id = '%s'; "
                                          "delete from actor_role where actor_id = '%s' "
                                          "and role = 'approver';")
                                     priya priya)})

      (rec/request! rec {:id "approved-by-wei"
                         :title "A different approver succeeds"
                         :narrative (str "SGD 500.00 is below the SGD 1,000.00 band, so one "
                                         "approval is enough; the response says how many were "
                                         "required and how many are held.")
                         :method "POST" :path (str "/payment-instructions/" pi-id "/approvals")
                         :headers {"x-actor-id" wei
                                   "idempotency-key" "capture-uat005-approve-wei"}
                         :body {"decision" "approved"}
                         :expect-status 201})

      (rec/request! rec {:id "evidence-pack"
                         :title "The trail Rae reads"
                         :narrative (str "Every state change of the instruction, in order, each "
                                         "carrying the actor who caused it. No creditor name and "
                                         "no account identifier — only digests.")
                         :method "GET" :path (str "/audit/evidence/" pi-id)
                         :headers {"x-actor-id" rae}
                         :expect-status 200})

      (rec/request! rec {:id "audit-read-refused"
                         :title "An operator cannot read the trail"
                         :narrative (str "Priya holds no `audit/read`. An operator able to read "
                                         "the whole organisation's trail could see which "
                                         "approvers act on what and when.")
                         :method "GET" :path "/audit/events"
                         :headers {"x-actor-id" priya}
                         :expect-status 403})

      {:organisation-id org-id
       :subjects {:payment-instruction pi-id}
       :sand-table nil})))

;; ---------------------------------------------------------------------------
;; Scenario 2 — the settlement batch that misbehaves
;; ---------------------------------------------------------------------------

(defn people
  "The maker, checker and controller of one scenario.

  Parameterised by a prefix because several scenarios seed the same roles in
  the same capture database, and an actor id is a primary key: reusing one
  would make the second scenario's seed fail, and quietly sharing an actor
  between two scenarios would make their audit trails each other's."
  [prefix]
  {:maker   (str prefix "-0000-0000-0000-000000000001")
   :checker (str prefix "-0000-0000-0000-000000000002")
   :ctrl    (str prefix "-0000-0000-0000-000000000003")
   :auditor (str prefix "-0000-0000-0000-000000000004")})

(defn- settlement-actors!
  [{{:keys [maker checker ctrl]} :people :as ctx} org-id]
  (seed! ctx {:id "seed-actors"
              :title "Seed a maker, a checker and a controller"
              :narrative (str "Three actors, one role each: the maker holds `operator`, the "
                              "checker `approver` with an SGD limit, and the controller "
                              "`controller`; and one SGD approval band.")
              :statement (format
                          (str "insert into actor (id, organisation_id, display_name) values "
                               "('%s','%s','Maker'),('%s','%s','Checker'),('%s','%s','Controller'); "
                               "insert into actor_role (actor_id, role) values "
                               "('%s','operator'),('%s','approver'),('%s','controller'); "
                               "insert into approver_limit (actor_id, currency, limit_minor) "
                               "values ('%s','SGD',100000000); "
                               "insert into approval_threshold "
                               "(organisation_id, currency, from_minor, approvals_required) "
                               "values ('%s','SGD',0,1);")
                          maker org-id checker org-id ctrl org-id
                          maker checker ctrl checker org-id)}))

(defn- settlement-accounts!
  "The three accounts the sand table follows."
  [{:keys [rec] {:keys [ctrl]} :people :as ctx}]
  (let [specs [{:id "account-funds"   :code "1100-CLIENT-FUNDS"
                :name "Client funds — pooled" :type "asset"}
               {:id "account-transit" :code "1300-IN-TRANSIT"
                :name "Settlement in transit" :type "asset"}
               {:id "account-payable" :code "2100-CLIENT-PAYABLE"
                :name "Client payable" :type "liability"}]
        opened (vec (for [{:keys [id code name type]} specs]
                      (let [step (account! ctx {:id id :actor ctrl :code code :name name
                                                :type type :currency "SGD"
                                                :narrative (str "Settlement touches three "
                                                                "accounts; this is " code ".")})]
                        {:code code :id (body step "id")})))]
    (rec/request! rec {:id "chart-of-accounts"
                       :title "The chart of accounts"
                       :narrative (str "Which way each account normally balances is not a stored "
                                       "column — it follows from the account's type — so it is "
                                       "read here, from the API, rather than looked up later.")
                       :method "GET" :path "/accounts"
                       :headers {"x-actor-id" ctrl}
                       :expect-status 200})
    opened))

(defn- raise-approved!
  "Raise, submit and approve one instruction. Returns its id."
  [{:keys [rec] {:keys [maker checker]} :people} {:keys [key suffix funds-id narrative]}]
  (let [created (rec/request! rec {:id (str "raise-" key)
                                   :title (str "Raise a payment ending " suffix)
                                   :narrative narrative
                                   :method "POST" :path "/payment-instructions"
                                   :headers {"x-actor-id" maker
                                             "idempotency-key" (str "capture-uat006-raise-" key)}
                                   :body {"debtorAccountId" funds-id
                                          "creditorName" "Pacific Rim Logistics Pte Ltd"
                                          "creditorAccount" (str "SG-SYNTH-8801234" suffix)
                                          "amount" {"currency" "SGD" "minorUnits" 125000}
                                          "valueDate" value-date
                                          "purposeCode" "SUPP"}
                                   :expect-status 201})
        id (body created "id")]
    (rec/request! rec {:id (str "submit-" key)
                       :title (str "Submit the payment ending " suffix)
                       :narrative "The maker submits their own draft."
                       :method "POST" :path (str "/payment-instructions/" id "/submission")
                       :headers {"x-actor-id" maker
                                 "idempotency-key" (str "capture-uat006-submit-" key)}
                       :body {}
                       :expect-status 200})
    (rec/request! rec {:id (str "approve-" key)
                       :title (str "Approve the payment ending " suffix)
                       :narrative "A different actor, holding `approver`, agrees it."
                       :method "POST" :path (str "/payment-instructions/" id "/approvals")
                       :headers {"x-actor-id" checker
                                 "idempotency-key" (str "capture-uat006-approve-" key)}
                       :body {"decision" "approved"}
                       :expect-status 201})
    id))

;; ---------------------------------------------------------------------------
;; Settlement steps more than one scenario makes
;; ---------------------------------------------------------------------------
;;
;; The settlement scenario, the evidence pack and the reconciliation scenario
;; all need ledger movements produced the way UAT-006 produces them. Each call
;; below is written once and made by every scenario that needs it, so the
;; movements a later scenario inherits are the movements the settlement
;; scenario shows — not a second transcription of them that could drift.

(defn- opening-balance!
  "The client's money arriving: one entry, debiting `1100-CLIENT-FUNDS` and
  crediting `2100-CLIENT-PAYABLE`, so the payments that follow have something
  to move."
  [{:keys [rec] {:keys [ctrl]} :people} {:keys [org-id by-code minor-units narrative]}]
  (rec/request! rec {:id "opening-balance"
                     :title "The client's money arrives"
                     :narrative narrative
                     :method "POST" :path "/journal-entries"
                     :headers {"x-actor-id" ctrl}
                     :body {"occurredAt" "2026-11-01T09:00:00Z"
                            "narrative" "Opening client balance"
                            "reference" {"type" "opening-balance" "id" org-id}
                            "lines" [{"accountId" (get by-code "1100-CLIENT-FUNDS")
                                      "direction" "debit"
                                      "amount" {"currency" "SGD" "minorUnits" minor-units}}
                                     {"accountId" (get by-code "2100-CLIENT-PAYABLE")
                                      "direction" "credit"
                                      "amount" {"currency" "SGD" "minorUnits" minor-units}}]}
                     :expect-status 201}))

(defn- raise-three!
  "UAT-006 step 3: three approved payments whose outcomes the creditor
  account's last digit chooses. Returns `{:settles :returns :silent}`."
  [ctx funds-id]
  (let [settles (raise-approved! ctx {:key "settles" :suffix "0" :funds-id funds-id
                                      :narrative (str "The simulated scheme reads the last digit "
                                                      "of the creditor account: 0–6 settles.")})
        returns (raise-approved! ctx {:key "returns" :suffix "7" :funds-id funds-id
                                      :narrative "7 and 8 come back."})
        silent  (raise-approved! ctx {:key "silent" :suffix "9" :funds-id funds-id
                                      :narrative "9 is never answered at all."})]
    {:settles settles :returns returns :silent silent}))

(defn- release-batch!
  "Batch approved instructions on `SIM-RTGS` and release the batch to the
  simulated scheme. Returns the batch id."
  [{:keys [rec] {:keys [ctrl]} :people} {:keys [instruction-ids created submitted]}]
  (let [batch (rec/request! rec {:id "batch-created"
                                 :title (:title created)
                                 :narrative (:narrative created)
                                 :method "POST" :path "/settlement-batches"
                                 :headers {"x-actor-id" ctrl}
                                 :body {"scheme" "SIM-RTGS" "currency" "SGD"
                                        "valueDate" value-date
                                        "instructionIds" (vec instruction-ids)}
                                 :expect-status 201})
        batch-id (body batch "id")]
    (rec/request! rec {:id "batch-submitted"
                       :title (:title submitted)
                       :narrative (:narrative submitted)
                       :method "POST" :path (str "/settlement-batches/" batch-id "/submit")
                       :headers {"x-actor-id" ctrl}
                       :body {}
                       :expect-status 200})
    batch-id))

(defn- scheme-response!
  "Deliver one simulated scheme response for a released batch."
  [{:keys [rec] {:keys [ctrl]} :people} batch-id {:keys [id title narrative body expect-status]}]
  (rec/request! rec {:id id
                     :title title
                     :narrative narrative
                     :method "POST" :path (str "/settlement-batches/" batch-id "/scheme-responses")
                     :headers {"x-actor-id" ctrl}
                     :body body
                     :expect-status expect-status}))

(defn- answer-settled!
  "UAT-006 step 7a: the scheme settles the first of three items."
  [ctx batch-id instruction-id]
  (scheme-response! ctx batch-id
                    {:id "response-settled"
                     :title "One settles"
                     :narrative "The batch stays `submitted`: two items are still outstanding."
                     :body {"kind" "settled" "instructionId" instruction-id
                            "reference" "SIM-STL-1"}
                     :expect-status 200}))

(defn- answer-returned!
  "UAT-006 step 7d: the scheme returns another, with its reason."
  [ctx batch-id instruction-id]
  (scheme-response! ctx batch-id
                    {:id "response-returned"
                     :title "One comes back"
                     :narrative (str "The returned item appears under `exceptions` with its "
                                     "reason — the queue an operator actually works.")
                     :body {"kind" "returned" "instructionId" instruction-id
                            "reference" "SIM-RTN-1"
                            "reason" "SIM-RETURN: beneficiary account closed"}
                     :expect-status 200}))

(defn- auditor!
  "Seed the scenario's auditor, who reads and does nothing else here."
  [{{:keys [auditor]} :people :as ctx} org-id narrative]
  (seed! ctx {:id "seed-auditor"
              :title "Seed an auditor"
              :narrative narrative
              :statement (format
                          (str "insert into actor (id, organisation_id, display_name) "
                               "values ('%s','%s','Rae (auditor)'); "
                               "insert into actor_role (actor_id, role) values ('%s','auditor');")
                          auditor org-id auditor)}))

(defn settlement-batch
  "UAT-006, replayed: partial failure, a duplicate, a contradiction, a silence.

  The sand table is the point. Three accounts, watched at seven moments, every
  balance a captured `GET /accounts/:id/statement` response — the clearing
  exposure rising when the batch is released, falling as answers arrive, and
  refusing to drain for the item nobody answered for."
  [{:keys [rec] {:keys [maker checker ctrl]} :people :as ctx}]
  (rec/note! rec
             {:id "scenario-note"
              :title "What you are about to watch"
              :narrative (str "A batch of three payments is released to a simulated scheme. One "
                              "settles, one comes back, and one is never answered. Along the "
                              "way the scheme answers the same thing twice and then contradicts "
                              "itself. The three account balances are captured after each "
                              "event.")})
  (let [org (organisation! ctx {:short-name "capture-uat006"
                                :legal-name "Meridian Freight Holdings Pte Ltd"
                                :narrative "A second organisation, for the settlement scenario."})
        org-id (body org "id")
        _ (settlement-actors! ctx org-id)
        accounts (settlement-accounts! ctx)
        by-code (into {} (map (juxt :code :id)) accounts)
        funds-id (get by-code "1100-CLIENT-FUNDS")
        rows (atom [])
        snap! (fn [at label narrative]
                (let [{:keys [snapshots entries]}
                      (balances! ctx {:at at :actor ctrl :accounts accounts
                                      :narrative narrative :organisation-id org-id})]
                  (swap! rows conj {:label label
                                    :after-step-id at
                                    :snapshots snapshots
                                    :entries entries})))]

    (opening-balance! ctx {:org-id org-id :by-code by-code :minor-units 1000000
                           :narrative (str "An opening entry: SGD 10,000.00 debited to client funds "
                                           "and credited to what CloFin owes the client. Two lines, "
                                           "one entry, and it balances.")})

    (snap! "opening" "Opening balances"
           "Before anything is released.")

    (rec/request! rec {:id "real-scheme-refused"
                       :title "A real scheme name is refused"
                       :narrative (str "CloFin settles against simulated schemes only. The "
                                       "`SIM-` prefix is a database check constraint rather "
                                       "than a convention.")
                       :method "POST" :path "/settlement-batches"
                       :headers {"x-actor-id" ctrl}
                       :body {"scheme" "SWIFT" "currency" "SGD"
                              "valueDate" value-date "instructionIds" []}
                       :expect-status 400})

    (let [{:keys [settles returns silent]} (raise-three! ctx funds-id)
          draft   (rec/request! rec {:id "raise-draft"
                                     :title "A fourth payment, left in draft"
                                     :narrative "Raised so that it can be refused a place in the batch."
                                     :method "POST" :path "/payment-instructions"
                                     :headers {"x-actor-id" maker
                                               "idempotency-key" "capture-uat006-raise-draft"}
                                     :body {"debtorAccountId" funds-id
                                            "creditorName" "Pacific Rim Logistics Pte Ltd"
                                            "creditorAccount" "SG-SYNTH-88012340"
                                            "amount" {"currency" "SGD" "minorUnits" 100}
                                            "valueDate" value-date
                                            "purposeCode" "SUPP"}
                                     :expect-status 201})
          draft-id (body draft "id")]

      (rec/request! rec {:id "unapproved-refused"
                         :title "An unapproved payment cannot be batched"
                         :narrative (str "One refusal lists every ineligible instruction with its "
                                         "reason, so an operator batching forty payments fixes "
                                         "them in one pass. Nothing is created.")
                         :method "POST" :path "/settlement-batches"
                         :headers {"x-actor-id" ctrl}
                         :body {"scheme" "SIM-RTGS" "currency" "SGD" "valueDate" value-date
                                "instructionIds" [settles draft-id]}
                         :expect-status 422})

      (rec/request! rec {:id "operator-cannot-settle"
                         :title "An operator cannot settle"
                         :narrative "The response names the permission: `settlement/execute`."
                         :method "POST" :path "/settlement-batches"
                         :headers {"x-actor-id" maker}
                         :body {"scheme" "SIM-RTGS" "currency" "SGD" "valueDate" value-date
                                "instructionIds" [settles]}
                         :expect-status 403})

      (rec/request! rec {:id "approver-cannot-settle"
                         :title "Neither can the approver who agreed it"
                         :narrative "The same refusal, for the actor who approved the payment."
                         :method "POST" :path "/settlement-batches"
                         :headers {"x-actor-id" checker}
                         :body {"scheme" "SIM-RTGS" "currency" "SGD" "valueDate" value-date
                                "instructionIds" [settles]}
                         :expect-status 403})

      (let [batch-id (release-batch!
                      ctx {:instruction-ids [settles returns silent]
                           :created {:title "The controller batches the three approved payments"
                                     :narrative "One scheme, one currency, one value date."}
                           :submitted {:title "Release the batch"
                                       :narrative (str "Three items, and the simulated scheme "
                                                       "acknowledges receipt. Every instruction "
                                                       "is now `released`.")}})]

        (snap! "released" "The batch is released"
               (str "SGD 3,750.00 has left client funds and is sitting in "
                    "1300-IN-TRANSIT — three payments released and none yet settled."))

        (answer-settled! ctx batch-id settles)

        (snap! "settled" "One payment settles"
               "Its value leaves in-transit and reduces what CloFin owes the client.")

        (rec/request! rec {:id "response-duplicate"
                           :title "The scheme says the same thing again"
                           :narrative (str "`replayed` is true and the outcome is the original "
                                           "one, reproduced rather than re-derived. The response "
                                           "count does not move.")
                           :method "POST" :path (str "/settlement-batches/" batch-id "/scheme-responses")
                           :headers {"x-actor-id" ctrl}
                           :body {"kind" "settled" "instructionId" settles "reference" "SIM-STL-1"}
                           :expect-status 200})

        (rec/request! rec {:id "response-contradiction"
                           :title "A late contradiction"
                           :narrative (str "A new message — its replay key is free — claiming the "
                                           "settled payment came back. Refused.")
                           :method "POST" :path (str "/settlement-batches/" batch-id "/scheme-responses")
                           :headers {"x-actor-id" ctrl}
                           :body {"kind" "returned" "instructionId" settles
                                  "reference" "SIM-RTN-LATE" "reason" "too late"}
                           :expect-status 409})

        (rec/request! rec {:id "response-contradiction-replayed"
                           :title "The refusal is itself evidence, and replays"
                           :narrative (str "The same message again gets the same answer, with "
                                           "`replayed` true: the refusal was stored, not "
                                           "recomputed. Before audit finding F-008 the conflict "
                                           "rolled its own receipt back and the first delivery "
                                           "was unprovable.")
                           :method "POST" :path (str "/settlement-batches/" batch-id "/scheme-responses")
                           :headers {"x-actor-id" ctrl}
                           :body {"kind" "returned" "instructionId" settles
                                  "reference" "SIM-RTN-LATE" "reason" "too late"}
                           :expect-status 409})

        (answer-returned! ctx batch-id returns)

        (snap! "returned" "One payment comes back"
               "The release is unwound line for line; the client's money returns to client funds.")

        (rec/request! rec {:id "timeout-sweep"
                           :title "Nobody answered for the third"
                           :narrative (str "The sweep is an explicit operator call, not a daemon: "
                                           "a timeout that fires itself is one nobody can point "
                                           "at afterwards.")
                           :method "POST" :path (str "/settlement-batches/" batch-id "/timeout-sweep")
                           :headers {"x-actor-id" ctrl}
                           :body {"timeoutSeconds" 0}
                           :expect-status 200})

        (snap! "swept" "The silent item times out"
               (str "In-transit still holds SGD 1,250.00. The sweep changed what the batch item "
                    "says; it moved no money, because nothing is known to have happened."))

        (rec/request! rec {:id "silent-still-released"
                           :title "The payment nobody answered for is still `released`"
                           :narrative "Not `failed`. CloFin does not know what happened to this money."
                           :method "GET" :path (str "/payment-instructions/" silent)
                           :headers {"x-actor-id" maker}
                           :expect-status 200})

        (rec/request! rec {:id "rebatch-refused"
                           :title "Trying again is refused"
                           :narrative (str "The instruction is `released`, so it is not approved, "
                                           "so it cannot be batched.")
                           :method "POST" :path "/settlement-batches"
                           :headers {"x-actor-id" ctrl}
                           :body {"scheme" "SIM-ACH" "currency" "SGD" "valueDate" value-date
                                  "instructionIds" [silent]}
                           :expect-status 422})

        (seed! ctx {:id "rebatch-refused-in-sql"
                    :title "And refused again from SQL, where no handler is involved"
                    :narrative (str "A unique index, so it binds a fix-up script exactly as it "
                                    "binds the API. Until migration 0010 the equivalent insert "
                                    "for a returned payment succeeded while the API answered "
                                    "422 to the same retry — audit finding F-007.")
                    :expect-refusal true
                    :statement (format
                                (str "with b as (insert into settlement_batch "
                                     "(id, organisation_id, scheme, currency, value_date, created_by) "
                                     "values (gen_random_uuid(), '%s', 'SIM-ACH', 'SGD', '%s', '%s') "
                                     "returning id) "
                                     "insert into settlement_batch_item (batch_id, instruction_id) "
                                     "select id, '%s' from b;")
                                org-id value-date ctrl silent)})

        (rec/request! rec {:id "timeout-resolution"
                           :title "The scheme finally answers"
                           :narrative (str "The item resolves, the instruction settles, and its "
                                           "finality entry posts now rather than when the sweep "
                                           "ran.")
                           :method "POST" :path (str "/settlement-batches/" batch-id "/scheme-responses")
                           :headers {"x-actor-id" ctrl}
                           :body {"kind" "timeout-resolution" "instructionId" silent
                                  "reference" "SIM-TMO-1" "outcome" "settled"}
                           :expect-status 200})

        (snap! "resolved" "The late answer arrives"
               "In-transit drains to zero: two payments settled and one came back.")

        (rec/request! rec {:id "timeout-resolution-again"
                           :title "A timeout resolves exactly once"
                           :narrative "A second resolution, with a different outcome, is refused."
                           :method "POST" :path (str "/settlement-batches/" batch-id "/scheme-responses")
                           :headers {"x-actor-id" ctrl}
                           :body {"kind" "timeout-resolution" "instructionId" silent
                                  "reference" "SIM-TMO-2" "outcome" "returned"
                                  "reason" "changed my mind"}
                           :expect-status 409})

        (rec/request! rec {:id "batch-final"
                           :title "The batch, at the end"
                           :narrative "Two settled, one returned, and every item resolved."
                           :method "GET" :path (str "/settlement-batches/" batch-id)
                           :headers {"x-actor-id" ctrl}
                           :expect-status 200})

        {:organisation-id org-id
         :subjects {:settlement-batch batch-id
                    :settled-instruction settles
                    :returned-instruction returns
                    :silent-instruction silent}
         :sand-table {:codes ["1100-CLIENT-FUNDS" "1300-IN-TRANSIT" "2100-CLIENT-PAYABLE"]
                      :rows @rows}}))))

;; ---------------------------------------------------------------------------
;; Scenario 3 — the evidence pack
;; ---------------------------------------------------------------------------

(defn evidence-pack
  "One payment, start to finish, and the trail an auditor extracts afterwards.

  Deliberately the uneventful path: nothing is refused here, so what the pack
  contains is the whole life of a payment that went well. The pack states the
  period it spans and whether it hit the row cap, because an auditor should
  never have to infer completeness from the absence of a warning."
  [{:keys [rec] {:keys [maker ctrl auditor]} :people :as ctx}]
  (rec/note! rec
             {:id "scenario-note"
              :title "What you are about to watch"
              :narrative (str "A single payment is raised, submitted, approved, released in a "
                              "batch and settled. Then the evidence pack for it is extracted, "
                              "and the pack for the batch that carried it.")})
  (let [org (organisation! ctx {:short-name "capture-evidence"
                                :legal-name "Meridian Freight Holdings Pte Ltd"
                                :narrative "A third organisation, for the evidence-pack scenario."})
        org-id (body org "id")
        _ (settlement-actors! ctx org-id)
        _ (auditor! ctx org-id "Rae reads the trail and nothing else.")
        accounts (settlement-accounts! ctx)
        by-code (into {} (map (juxt :code :id)) accounts)
        funds-id (get by-code "1100-CLIENT-FUNDS")]

    (opening-balance! ctx {:org-id org-id :by-code by-code :minor-units 500000
                           :narrative "So the payment has something to move."})

    (let [pi (raise-approved! ctx {:key "settles" :suffix "0" :funds-id funds-id
                                   :narrative "One payment, raised by the maker."})
          batch-id (release-batch!
                    ctx {:instruction-ids [pi]
                         :created {:title "Batch it"
                                   :narrative "One approved instruction, one batch."}
                         :submitted {:title "Release it"
                                     :narrative (str "The instruction becomes `released` and the "
                                                     "value posts to in-transit.")}})]

      (rec/request! rec {:id "response-settled"
                         :title "The scheme settles it"
                         :narrative "Every item is resolved, so the batch completes."
                         :method "POST" :path (str "/settlement-batches/" batch-id "/scheme-responses")
                         :headers {"x-actor-id" ctrl}
                         :body {"kind" "settled" "instructionId" pi "reference" "SIM-STL-1"}
                         :expect-status 200})

      (rec/request! rec {:id "evidence-payment"
                         :title "The evidence pack for the payment"
                         :narrative (str "Six events, in order: created, submitted, the approval "
                                         "decision, the payment reaching approved, released, "
                                         "settled. `approval.recorded` and `payment.approved` are "
                                         "separate because they are separate facts — one actor "
                                         "decided, and the payment changed state.")
                         :method "GET" :path (str "/audit/evidence/" pi)
                         :headers {"x-actor-id" auditor}
                         :expect-status 200})

      (rec/request! rec {:id "evidence-batch"
                         :title "The evidence pack for the batch"
                         :narrative (str "The batch's own subject type, and exactly one "
                                         "`settlement-batch.completed` — written when the last "
                                         "item resolved, not once per response.")
                         :method "GET" :path (str "/audit/evidence/" batch-id)
                         :headers {"x-actor-id" auditor}
                         :expect-status 200})

      (rec/request! rec {:id "audit-events"
                         :title "The whole trail for the organisation"
                         :narrative (str "Capped rather than paginated, with the cap and a "
                                         "`truncated` flag on every response.")
                         :method "GET" :path "/audit/events"
                         :headers {"x-actor-id" auditor}
                         :expect-status 200})

      {:organisation-id org-id
       :subjects {:payment-instruction pi :settlement-batch batch-id}
       :sand-table nil})))

;; ---------------------------------------------------------------------------
;; Scenario 4 — reconciliation, its breaks, and the corrections that close them
;; ---------------------------------------------------------------------------

(defn- expect!
  "Stop the capture when the system and the script disagree about a fact that a
  later step depends on.

  The recorder stops on an unexpected status. Some of UAT-007's steps depend
  on more than a status — which break a perturbation opened, which payment it
  names — and a replay that carried on past a disagreement there would make
  its next request against the wrong subject and narrate it as the right one."
  [ok? message data]
  (when-not ok?
    (throw (ex-info (str "capture refuses: " message) data))))

(defn- confirm!
  "One of UAT-007's read-only SQL confirmations, made and asserted rather than
  recorded.

  The script tells its reader to check the database after several steps —
  counts unchanged, no receipt written, the status figures equal to the rows —
  and in step 14 says *if they differ, stop and raise a defect*. A replay that
  skipped them would narrate a fact nobody checked. So each runs here, scoped
  to this scenario's organisation (the script's queries are database-wide, and
  the capture database holds every scenario's), and a disagreement stops the
  capture, as `verify-against-journal!` stops it for the sand table. They are
  not steps on the tape: the tables they read are not in the bundle, and a row
  count printed beside a query would read as a statement the page cannot back.

  Returns the value read, so a later confirmation can compare with it."
  [conn {:keys [step what sql params expected]}]
  (let [actual (vec (apply store/query conn sql params))]
    (expect! (= expected actual)
             (format "UAT-007 step %s confirms %s: it expects %s, and the database holds %s."
                     step what (pr-str expected) (pr-str actual))
             {:step step :sql sql :expected expected :actual actual})
    actual))

(defn- org-counts
  "The rows a statement delivered twice must not add to — matches, breaks and
  journal entries, for one organisation."
  [conn org-id]
  (first (store/query conn
                      (str "select (select count(*) from reconciliation_match m"
                           "          join reconciliation_statement s on s.id = m.statement_id"
                           "         where s.organisation_id = ?::uuid) as matches,"
                           "       (select count(*) from reconciliation_break"
                           "         where organisation_id = ?::uuid) as breaks,"
                           "       (select count(*) from journal_entry"
                           "         where organisation_id = ?::uuid) as entries")
                      org-id org-id org-id)))

(def ^:private band-rows
  "What UAT-007's band check expects: exactly one row, `100000 | 1`."
  [{"from_minor" 100000 "approvals_required" 1}])

(def ^:private band-check-sql
  (str "select from_minor, approvals_required from approval_threshold"
       " where organisation_id = ?::uuid and currency = 'SGD' order by from_minor"))

(defn- statement-period
  "UAT-007 step 1's period: yesterday 00:00 to tomorrow 00:00, UTC, from the
  database's clock — `date -u -d 'yesterday 00:00'` and `'tomorrow 00:00'` in
  the script. Computed once, so every step that names the period names the same
  one."
  [conn]
  (let [today (.toLocalDate (.atZone ^java.time.Instant (store/now conn)
                                     java.time.ZoneOffset/UTC))
        at    (fn [^java.time.LocalDate d]
                (str (.toInstant (.atStartOfDay d java.time.ZoneOffset/UTC))))]
    {:from (at (.minusDays today 1)) :to (at (.plusDays today 1))}))

(defn- replace-bands!
  "UAT-007's *Before you start* SQL, as merged: the tenant's SGD bands
  replaced — not added to — by one band at SGD 1,000.00 needing one approval.

  Its two statements are run as two steps, so that each records its own row
  count: `store/execute!` reports the first statement's count for a string
  holding several, and a restore whose delete found nothing would otherwise
  read as a step that applied nothing."
  [ctx org-id {:keys [id title narrative]}]
  (seed! ctx {:id (str id "-delete")
              :title (str title " — the delete")
              :narrative narrative
              :statement (format (str "delete from approval_threshold where organisation_id = '%s' "
                                      "and currency = 'SGD';")
                                 org-id)})
  (seed! ctx {:id (str id "-insert")
              :title (str title " — the insert")
              :narrative "One band: from SGD 1,000.00, one approval."
              :statement (format (str "insert into approval_threshold (organisation_id, currency, "
                                      "from_minor, approvals_required)\n"
                                      "values ('%s', 'SGD', 100000, 1);")
                                 org-id)}))

(def ^:private unknown-actor
  "UAT-007 step 8's assignee: a UUID that is not an actor in this organisation."
  "00000000-0000-4000-8000-000000000000")

(defn reconciliation-breaks
  "UAT-007, replayed: a statement, its breaks, and the corrections that close them.

  The prerequisite is UAT-006's ledger state — one batch of three released
  payments, one settled, one returned, one never answered — produced by the
  same calls the settlement scenario makes (`raise-three!`, `release-batch!`,
  `answer-settled!`, `answer-returned!`), in an organisation of its own. Then
  UAT-007's fifteen steps, every request with the status the script states.

  UAT-007 accepts \"UAT-006 completed, or its steps 1–7 repeated\", and only
  the second works: UAT-006's step 9 answers for the unanswered payment, after
  which step 1 is no longer one line short and step 2's clearing balance is
  zero. Of steps 1–7 the scenario makes the calls that produce that state; the
  refusals, the duplicate and the draft among them (steps 2, 4, 5, 7b and 7c,
  with 7c(ii))
  change nothing this script reconciles, and the settlement scenario shows
  them.

  Where the script names something it does not define, the replay fills it
  from the run, and every filling is listed here:

  - `$CONTROLLER`, `$CHECKER`, `$AUDITOR`, `$MAKER` — the scenario's people;
    the maker is UAT-006's, whom step 13 uses and the inherits table omits.
  - `$FUNDS` — this organisation's `1100-CLIENT-FUNDS`; `$VALUEDATE` — the
    value date UAT-006 uses; `$SETTLED` — UAT-006's `$SETTLES`.
  - `$BREAK`, `$BREAK2`, `$BREAK3` — the breaks the `unknown-line`,
    `amount-mismatch` and `missing-line` runs open; `$BREAKR` — the
    `missing-line` break, which is about the returned payment in a run that
    passes the premise check below.
  - `<entryId>` (step 9) and `<the retry>` (step 13) — the ids those steps
    returned; step 11's placeholders — this organisation, `$BREAK`, the entry
    step 9 posted, the controller.
  - `$CHECKER2` (step 12), which no script defines — the checker; the step's
    narrative says so.
  - Step 10's \"restore the bands\", which gives no statement — the script's
    own *Before you start* SQL, run again.

  And where the script says something the system does not do, the replay
  shows what it does, and the narrative says which:

  - *Before you start* says \"two accounts beyond settlement's three\" and
    creates one, `2200-UNAPPLIED`.
  - Steps 9 and 12 say \"above\" the SGD 1,000.00 band and send exactly SGD
    1,000.00, which needs approval because the bound is inclusive.
  - Step 8's heading promises an attempt \"to skip a step\" that its body does
    not contain; the replay makes the three calls the body states and no
    fourth. The lifecycle permits `open` → `resolved`, and steps 10 and 12
    take breaks there without assignment.

  The statuses the script states are the ones expected; where a call states
  none, the success status the step describes is expected. The script's
  read-only SQL confirmations — the band check, and steps 3, 4, 5, 12 and 14 —
  run through `confirm!`: asserted, scoped to this organisation, not recorded.
  016-REQ records each of the above as an objection."
  [{:keys [rec conn] {:keys [maker checker ctrl auditor]} :people :as ctx}]
  (rec/note! rec
             {:id "scenario-note"
              :title "What you are about to watch"
              :narrative (str "A batch of three payments is released; one settles, one is "
                              "returned and one is never answered — the settlement scenario up to "
                              "its timeout. Then the simulated scheme's statement for it is "
                              "ingested, delivered again, contradicted, and requested three more "
                              "times with a deliberate fault. The breaks those faults open are "
                              "queued, assigned and corrected, and the sand table follows the "
                              "accounts the corrections move.")})
  (let [org (organisation! ctx {:short-name "capture-uat007"
                                :legal-name "Meridian Freight Holdings Pte Ltd"
                                :narrative "A fourth organisation, for the reconciliation scenario."})
        org-id (body org "id")
        _ (settlement-actors! ctx org-id)
        _ (auditor! ctx org-id (str "Rae holds `auditor`, and makes every `/audit/` read below, as "
                                    "UAT-007 requires."))
        accounts (settlement-accounts! ctx)
        unapplied (rec/request! rec {:id "account-unapplied"
                                     :title "Open 2200-UNAPPLIED"
                                     :narrative (str "The account UAT-007's *Before you start* "
                                                     "creates — it says \"two accounts beyond "
                                                     "settlement's three\" and creates this one. "
                                                     "An adjustment's second line posts here.")
                                     :method "POST" :path "/accounts"
                                     :headers {"x-actor-id" ctrl}
                                     :body (array-map "organisationId" org-id
                                                      "code" "2200-UNAPPLIED"
                                                      "name" "Unapplied receipts"
                                                      "type" "liability"
                                                      "currency" "SGD")
                                     :expect-status 201})
        accounts (conj accounts {:code "2200-UNAPPLIED" :id (body unapplied "id")})
        by-code (into {} (map (juxt :code :id)) accounts)
        funds-id (get by-code "1100-CLIENT-FUNDS")
        transit-id (get by-code "1300-IN-TRANSIT")
        rows (atom [])
        snap! (fn [at label narrative]
                (let [{:keys [snapshots entries]}
                      (balances! ctx {:at at :actor ctrl :accounts accounts
                                      :narrative narrative :organisation-id org-id})]
                  (swap! rows conj {:label label :after-step-id at
                                    :snapshots snapshots :entries entries})))
        _ (opening-balance! ctx {:org-id org-id :by-code by-code :minor-units 1000000
                                 :narrative (str "As in the settlement scenario: SGD 10,000.00 into "
                                                 "client funds, so the payments have something to "
                                                 "move.")})
        {:keys [settles returns silent]} (raise-three! ctx funds-id)
        batch-id (release-batch!
                  ctx {:instruction-ids [settles returns silent]
                       :created {:title "The controller batches the three approved payments"
                                 :narrative "One scheme, one currency, one value date."}
                       :submitted {:title "Release the batch"
                                   :narrative (str "Three items, and the simulated scheme "
                                                   "acknowledges receipt. Every instruction is now "
                                                   "`released`.")}})
        _ (answer-settled! ctx batch-id settles)
        _ (answer-returned! ctx batch-id returns)
        _ (replace-bands! ctx org-id
                          {:id "bands-replaced"
                           :title "Replace the organisation's SGD approval bands"
                           :narrative (str "UAT-007's own SQL, which deletes the band the "
                                           "settlement actors were seeded with and inserts one at "
                                           "SGD 1,000.00 needing one approval.")})
        _ (confirm! conn {:step "*Before you start*" :what "exactly one SGD band, 100000 | 1"
                          :sql band-check-sql :params [org-id] :expected band-rows})
        {:keys [from to]} (statement-period conn)
        statement-query (fn [perturbation]
                          (cond-> (array-map "organisationId" org-id "scheme" "SIM-RTGS"
                                             "currency" "SGD" "from" from "to" to)
                            perturbation (assoc "perturbation" perturbation)))
        ingest! (fn [{:keys [id title narrative document expect-status]}]
                  (rec/request! rec {:id id :title title :narrative narrative
                                     :method "POST" :path "/reconciliation-statements"
                                     :headers {"x-actor-id" ctrl}
                                     :body (assoc document "organisationId" org-id)
                                     :expect-status expect-status}))
        org-only {"organisationId" org-id}]

    (snap! "before-ingestion" "Before any statement is ingested"
           (str "After the settlement: the unanswered payment's SGD 1,250.00 is still in "
                "1300-IN-TRANSIT, and nothing has reached 2200-UNAPPLIED."))
    (expect! (= [125000 0] [(closing rec "balance-before-ingestion-1300-IN-TRANSIT")
                            (closing rec "balance-before-ingestion-2200-UNAPPLIED")])
             (str "the before-ingestion row's narrative names SGD 1,250.00 in 1300-IN-TRANSIT "
                  "and nothing in 2200-UNAPPLIED, and the captured statements say otherwise.")
             {:in-transit (closing rec "balance-before-ingestion-1300-IN-TRANSIT")
              :unapplied (closing rec "balance-before-ingestion-2200-UNAPPLIED")})

    ;; -- Step 1 ------------------------------------------------------------
    (let [s1 (rec/request! rec {:id "s01-statement"
                                :title "Step 1 · Ask the simulated scheme for its statement"
                                :narrative (str "Yesterday 00:00 to tomorrow 00:00, UTC. The "
                                                "document says it is `simulated`, in CloFin's own "
                                                "format, with one line per payment the scheme "
                                                "answered about.")
                                :method "GET" :path "/settlement-statements"
                                :query (statement-query nil)
                                :headers {"x-actor-id" ctrl}
                                :expect-status 200})
          statement (get-in s1 [:response :body])
          reported  (set (map #(get % "paymentReference") (get statement "lines")))]
      (expect! (= #{settles returns} reported)
               (str "UAT-007 step 1 expects one line for each payment the scheme answered about "
                    "and none for the one it did not; the statement reports "
                    (pr-str (vec reported)) ".")
               {:expected [settles returns] :reported reported})

      ;; -- Step 2 ----------------------------------------------------------
      (ingest! {:id "s02-ingested"
                :title "Step 2 · Ingest it, and watch it match"
                :narrative (str "`disposition` is `applied`, every match names the `rule` that made "
                                "it, and `breaks` is empty.")
                :document statement :expect-status 200})
      (rec/request! rec {:id "s02-accounts"
                         :title "Step 2 · Find the clearing account"
                         :narrative "The chart of accounts, to read `1300-IN-TRANSIT`'s id from."
                         :method "GET" :path "/accounts" :query org-only
                         :headers {"x-actor-id" ctrl}
                         :expect-status 200})
      (rec/request! rec {:id "s02-in-transit"
                         :kind "http"
                         :account "1300-IN-TRANSIT"
                         :title "Step 2 · The unanswered payment, as money"
                         :narrative (str "`1300-IN-TRANSIT` over the statement's own period. Its "
                                         "closing balance is the value of the payment the scheme "
                                         "never answered about, which no statement line reports.")
                         :method "GET" :path (str "/accounts/" transit-id "/statement")
                         :query (array-map "organisationId" org-id "from" from "to" to)
                         :headers {"x-actor-id" ctrl}
                         :expect-status 200})
      (expect! (= 125000 (closing rec "s02-in-transit"))
               (str "UAT-007 step 2 says the clearing account's closing balance is the "
                    "unanswered payment's value, SGD 1,250.00; the captured statement says "
                    (pr-str (closing rec "s02-in-transit")) " minor units.")
               {:closing (closing rec "s02-in-transit")})

      ;; -- Step 3 ----------------------------------------------------------
      (let [before (org-counts conn org-id)]
        (ingest! {:id "s03-delivered-again"
                  :title "Step 3 · Deliver the same statement again"
                  :narrative (str "`replayed` is true; the `id`, the matches and their `matchedAt` "
                                  "are the first delivery's.")
                  :document statement :expect-status 200})
        (let [after (org-counts conn org-id)]
          (expect! (= before after)
                   (format (str "UAT-007 step 3 confirms that a second delivery changes no count; "
                                "this organisation's matches, breaks and journal entries were %s "
                                "before it and %s after.")
                           (pr-str before) (pr-str after))
                   {:before before :after after})))

      ;; -- Step 4 ----------------------------------------------------------
      (ingest! {:id "s04-different-document"
                :title "Step 4 · A different document under the same reference"
                :narrative (str "The first line's amount is one minor unit higher, and the "
                                "`statementReference` is the same. Refused as "
                                "`replay-key-conflict`, with `replayed` false.")
                :document (update-in statement ["lines" 0 "amount" "minorUnits"] inc)
                :expect-status 409})
      (confirm! conn {:step "4" :what "one receipt for the reference, not two"
                      :sql (str "select statement_reference, count(*) as receipts"
                                "  from reconciliation_statement where organisation_id = ?::uuid"
                                " group by statement_reference")
                      :params [org-id]
                      :expected [{"statement_reference" (get statement "statementReference")
                                  "receipts" 1}]})

      ;; -- Step 5 ----------------------------------------------------------
      (rec/request! rec {:id "s05-real-format"
                         :title "Step 5 · A real bank statement format is refused"
                         :narrative (str "`camt.053.001.08`. The refusal names the one format "
                                         "CloFin reads.")
                         :method "POST" :path "/reconciliation-statements"
                         :headers {"x-actor-id" ctrl}
                         :body (array-map "organisationId" org-id
                                          "format" "camt.053.001.08" "formatVersion" 1
                                          "scheme" "SIM-RTGS" "currency" "SGD"
                                          "statementReference" "X"
                                          "periodStart" from "periodEnd" to "lines" [])
                         :expect-status 400})
      (rec/request! rec {:id "s05-real-scheme"
                         :title "Step 5 · So is a real network's name"
                         :narrative "`TARGET2`, in CloFin's own format. Refused too."
                         :method "POST" :path "/reconciliation-statements"
                         :headers {"x-actor-id" ctrl}
                         :body (array-map "organisationId" org-id
                                          "format" "SIM-CLOFIN-RECON-STATEMENT" "formatVersion" 1
                                          "scheme" "TARGET2" "currency" "SGD"
                                          "statementReference" "Y"
                                          "periodStart" from "periodEnd" to "lines" [])
                         :expect-status 400})
      (confirm! conn {:step "5" :what "that neither refused document left a receipt"
                      :sql (str "select count(*) as receipts from reconciliation_statement"
                                " where organisation_id = ?::uuid"
                                "   and statement_reference in ('X', 'Y')")
                      :params [org-id] :expected [{"receipts" 0}]})

      ;; -- Step 6 ----------------------------------------------------------
      (let [perturbed
            (into {}
                  (for [[class kind] [["missing-line" "expectation-unmatched"]
                                      ["unknown-line" "statement-line-unmatched"]
                                      ["amount-mismatch" "amount-mismatch"]]]
                    (let [fetched (rec/request! rec {:id (str "s06-" class "-statement")
                                                     :title (str "Step 6 · Ask for a statement "
                                                                 "with `" class "`")
                                                     :narrative (str "The generator misbehaves on "
                                                                     "request; the class names how.")
                                                     :method "GET" :path "/settlement-statements"
                                                     :query (statement-query class)
                                                     :headers {"x-actor-id" ctrl}
                                                     :expect-status 200})
                          ingested (ingest! {:id (str "s06-" class "-ingested")
                                             :title (str "Step 6 · Ingest it — `" class "`")
                                             :narrative (str "The break UAT-007's table predicts "
                                                             "for this class is `" kind "`. Each "
                                                             "break names what disagreed, its "
                                                             "state, its assignee and its age.")
                                             :document (get-in fetched [:response :body])
                                             :expect-status 200})
                          breaks (body ingested "breaks")]
                      (expect! (and (= 1 (count breaks)) (= kind (get (first breaks) "kind")))
                               (format (str "UAT-007 step 6 predicts one %s break for %s, and the "
                                            "ingestion opened %s.")
                                       kind class (pr-str (mapv #(get % "kind") breaks)))
                               {:class class :breaks breaks})
                      [class (first breaks)])))
            break-unseen   (get-in perturbed ["unknown-line" "id"])
            break-mismatch (get-in perturbed ["amount-mismatch" "id"])
            missing        (get perturbed "missing-line")
            break-missing  (get missing "id")
            ;; $BREAK, $BREAK2, $BREAK3 and $BREAKR, in the script's names.
            brk  break-unseen
            brk2 break-mismatch
            brk3 break-missing
            brkr break-missing
            break-path (fn [id & tail] (apply str "/reconciliation-breaks/" id tail))
            adjust! (fn [{:keys [id title narrative break-id minor-units text expect-status]}]
                      (rec/request! rec {:id id :title title :narrative narrative
                                         :method "POST" :path (break-path break-id "/adjustments")
                                         :headers {"x-actor-id" ctrl}
                                         :body (array-map "organisationId" org-id
                                                          "amount" {"currency" "SGD"
                                                                    "minorUnits" minor-units}
                                                          "direction" "credit"
                                                          "narrative" text)
                                         :expect-status expect-status}))
            decide! (fn [{:keys [id title narrative adjustment actor decision expect-status]}]
                      (rec/request! rec {:id id :title title :narrative narrative
                                         :method "POST"
                                         :path (str "/reconciliation-adjustments/" adjustment
                                                    "/approvals")
                                         :headers {"x-actor-id" actor}
                                         :body (into (array-map "organisationId" org-id) decision)
                                         :expect-status expect-status}))
            assign! (fn [{:keys [id title narrative assignee expect-status]}]
                      (rec/request! rec {:id id :title title :narrative narrative
                                         :method "POST" :path (break-path brk "/assignment")
                                         :headers {"x-actor-id" ctrl}
                                         :body (array-map "organisationId" org-id
                                                          "assigneeId" assignee)
                                         :expect-status expect-status}))]

        ;; UAT-007 step 13 needs "a break about a **returned** payment — the
        ;; missing-line run in step 6 produced one". The generator drops the
        ;; first line in scheme-reference order, which within one batch is
        ;; instruction-id order, and instruction ids are random: in a run where
        ;; the settled payment's id sorts first, no break names the returned
        ;; one, and step 13's "proper" retry is refused. Said here, before any
        ;; request is made against the wrong subject (016-REQ objection).
        (expect! (= returns (get missing "instructionId"))
                 (format (str "UAT-007 step 13 needs a break about the returned payment and says "
                              "the missing-line run produced one. In this run that break names %s, "
                              "the %s payment: the generator drops the first line in "
                              "scheme-reference order, which is instruction-id order, and %s sorts "
                              "before the returned payment %s. No break names the returned payment, "
                              "so step 13 cannot be replayed as written. Run the capture again.")
                         (get missing "instructionId")
                         (if (= settles (get missing "instructionId")) "settled" "unexpected")
                         (get missing "instructionId") returns)
                 {:missing-line-break missing :settles settles :returns returns})

        ;; -- Step 7 --------------------------------------------------------
        (rec/request! rec {:id "s07-queue"
                           :title "Step 7 · The queue, oldest first"
                           :narrative (str "Read as the auditor. `count` and `truncated` are part "
                                           "of the answer.")
                           :method "GET" :path "/reconciliation-breaks" :query org-only
                           :headers {"x-actor-id" auditor}
                           :expect-status 200})
        (rec/request! rec {:id "s07-queue-open"
                           :title "Step 7 · Only the open ones"
                           :narrative "The same queue, filtered by state."
                           :method "GET" :path "/reconciliation-breaks"
                           :query (array-map "organisationId" org-id "state" "open")
                           :headers {"x-actor-id" auditor}
                           :expect-status 200})
        (rec/request! rec {:id "s07-queue-haunted"
                           :title "Step 7 · A state that does not exist"
                           :narrative "`haunted` is refused, and the refusal lists the states there are."
                           :method "GET" :path "/reconciliation-breaks"
                           :query (array-map "organisationId" org-id "state" "haunted")
                           :headers {"x-actor-id" auditor}
                           :expect-status 400})

        ;; -- Step 8 --------------------------------------------------------
        (assign! {:id "s08-assigned"
                  :title "Step 8 · Take ownership"
                  :narrative (str "The break from the `unknown-line` run is assigned to the "
                                  "checker; its state moves from `open` to `investigating`.")
                  :assignee checker :expect-status 200})
        (assign! {:id "s08-reassigned"
                  :title "Step 8 · Hand it to someone else"
                  :narrative "The owner changes to the controller. The state stays `investigating`."
                  :assignee ctrl :expect-status 200})
        (assign! {:id "s08-unknown-assignee"
                  :title "Step 8 · Assign it to nobody this organisation knows"
                  :narrative (str "A UUID that names no actor here. Refused, and the refusal does "
                                  "not say whether it names one anywhere else.")
                  :assignee unknown-actor :expect-status 422})

        ;; -- Step 9 --------------------------------------------------------
        (let [proposed (adjust! {:id "s09-proposed"
                                 :title "Step 9 · Propose a correction at the SGD 1,000.00 band"
                                 :narrative (str "SGD 1,000.00, exactly at the band's lower bound "
                                                 "(UAT-007 says \"above\"). `status` is "
                                                 "`proposed`, `posted` is false, one approval is "
                                                 "required, and the break is still "
                                                 "`investigating`.")
                                 :break-id brk :minor-units 100000
                                 :text (str "Scheme reports a movement CloFin did not post; parked "
                                            "in suspense pending investigation")
                                 :expect-status 201})
              adj (body proposed "id")]
          (decide! {:id "s09-self-approval"
                    :title "Step 9 · The proposer tries to approve it"
                    :narrative "Refused, with `errors.reason` `self-approval`."
                    :adjustment adj :actor ctrl :decision {} :expect-status 403})
          (let [approved (decide! {:id "s09-approved"
                                   :title "Step 9 · The checker approves it"
                                   :narrative (str "`posted` is true, the adjustment is `posted` "
                                                   "with an `entryId`, and the break is "
                                                   "`resolved`.")
                                   :adjustment adj :actor checker :decision {}
                                   :expect-status 201})
                entry-id (get-in approved [:response :body "adjustment" "entryId"])]
            (expect! (string? entry-id)
                     "UAT-007 step 9 expects the approval to post an entry, and none is named."
                     {:response (body approved "adjustment")})
            (rec/request! rec {:id "s09-entry"
                               :title "Step 9 · The entry it posted"
                               :narrative (str "An ordinary journal entry: two lines, one on "
                                               "`1300-IN-TRANSIT` and one on `2200-UNAPPLIED`, "
                                               "referencing `reconciliation-adjustment`.")
                               :method "GET" :path (str "/journal-entries/" entry-id)
                               :query org-only
                               :headers {"x-actor-id" ctrl}
                               :expect-status 200})
            (snap! "s09" "After step 9 — the approved correction posts"
                   "The adjustment's two lines, on 1300-IN-TRANSIT and 2200-UNAPPLIED.")

            ;; -- Step 10 -----------------------------------------------------
            (adjust! {:id "s10-below-band"
                      :title "Step 10 · Below the band, one actor suffices"
                      :narrative (str "SGD 999.99 against the `amount-mismatch` break: "
                                      "`approvalsRequired` 0, `posted` true, the break "
                                      "`resolved`.")
                      :break-id brk2 :minor-units 99999
                      :text "De-minimis difference, parked in suspense"
                      :expect-status 201})
            (snap! "s10" "After step 10 — the de-minimis correction posts"
                   "Posted by the proposer alone.")
            (seed! ctx {:id "s10-bands-removed"
                        :title "Step 10 · Remove the organisation's bands"
                        :narrative "UAT-007's own statement: every band the organisation has."
                        :statement (format "delete from approval_threshold where organisation_id = '%s';"
                                           org-id)})
            (adjust! {:id "s10-no-threshold"
                      :title "Step 10 · No band, no adjustment"
                      :narrative (str "Against the `missing-line` break: refused with "
                                      "`no-threshold-configured`.")
                      :break-id brk3 :minor-units 100 :text "Should be refused"
                      :expect-status 422})
            (replace-bands! ctx org-id
                            {:id "s10-bands-restored"
                             :title "Step 10 · Restore the bands"
                             :narrative (str "UAT-007 says to restore the bands before continuing "
                                             "and gives no statement; this is its own *Before you "
                                             "start* SQL again, the band steps 9 and 12 depend "
                                             "on. The delete finds nothing: the step above "
                                             "removed every band.")})
            (confirm! conn {:step "10" :what "the band restored: exactly one, 100000 | 1"
                            :sql band-check-sql :params [org-id] :expected band-rows})

            ;; -- Step 11 -----------------------------------------------------
            (assign! {:id "s11-assign-resolved"
                      :title "Step 11 · Reassign a resolved break"
                      :narrative "Refused, naming the state the break is in."
                      :assignee checker :expect-status 409})
            (adjust! {:id "s11-adjust-resolved"
                      :title "Step 11 · Correct a resolved break again"
                      :narrative "Refused, naming the state the break is in."
                      :break-id brk :minor-units 100 :text "A second correction"
                      :expect-status 409})
            (seed! ctx {:id "s11-insert-refused"
                        :title "Step 11 · The same, in raw SQL"
                        :narrative (str "UAT-007's insert, with its placeholders filled: this "
                                        "organisation, the break resolved in step 9, the entry "
                                        "step 9 posted, and the controller. The database's own "
                                        "answer is below.")
                        :expect-refusal true
                        :expect-error #"recon_adjustment_posted_key"
                        :statement (format
                                    (str "insert into reconciliation_adjustment\n"
                                         "  (id, organisation_id, break_id, amount_minor, currency, direction, narrative,\n"
                                         "   status, approvals_required, entry_id, posted_at, created_by)\n"
                                         "values (gen_random_uuid(), '%s', '%s', 100, 'SGD', 'credit', 'by hand',\n"
                                         "        'posted', 0, '%s', now(), '%s');")
                                    org-id brk entry-id ctrl)})))

        ;; -- Step 12 -------------------------------------------------------
        (let [proposed (adjust! {:id "s12-proposed"
                                 :title "Step 12 · Propose a correction the checker will refuse"
                                 :narrative (str "Against the `missing-line` break, SGD 1,000.00 — "
                                                 "at the band (UAT-007 says \"above\"): `proposed`, "
                                                 "with `post` and `reject` permitted.")
                                 :break-id brk3 :minor-units 100000
                                 :text "Provisional: the scheme's figure looks wrong"
                                 :expect-status 201})
              adj3 (body proposed "id")]
          (decide! {:id "s12-self-rejection"
                    :title "Step 12 · The proposer tries to refuse it"
                    :narrative "Refused, with `errors.reason` `self-approval`."
                    :adjustment adj3 :actor ctrl
                    :decision {"decision" "rejected" "reason" "changed my mind"}
                    :expect-status 403})
          (decide! {:id "s12-rejection-without-reason"
                    :title "Step 12 · A refusal with no reason"
                    :narrative "Refused, naming `reason`."
                    :adjustment adj3 :actor checker :decision {"decision" "rejected"}
                    :expect-status 422})
          (let [adjustment-entries
                (fn [] (confirm! conn {:step "12" :what "the adjustment entries in the journal"
                                       :sql (str "select count(*) as entries from journal_entry"
                                                 " where organisation_id = ?::uuid"
                                                 "   and reference_type = 'reconciliation-adjustment'")
                                       :params [org-id]
                                       ;; Steps 9 and 10 posted one each.
                                       :expected [{"entries" 2}]}))]
            (adjustment-entries)
            (decide! {:id "s12-rejected"
                      :title "Step 12 · The checker refuses it"
                      :narrative (str "`rejected` true, `posted` false; the adjustment is `rejected` "
                                      "with no `entryId` and no permitted transitions; the approval "
                                      "carries the decision and the reason; the break is where it "
                                      "was.")
                      :adjustment adj3 :actor checker
                      :decision {"decision" "rejected"
                                 "reason" "The statement is right; our posting is the one to investigate"}
                      :expect-status 201})
            ;; "the entry count is unchanged by the refusal"
            (adjustment-entries))
          (adjust! {:id "s12-second-proposal"
                    :title "Step 12 · A different correction, against the same break"
                    :narrative "SGD 999.99, below the band: it posts, and the break is `resolved`."
                    :break-id brk3 :minor-units 99999 :text "Agreed after investigation"
                    :expect-status 201})
          (decide! {:id "s12-decide-again"
                    :title "Step 12 · Decide the refused adjustment again"
                    :narrative (str "UAT-007 writes this call as `$CHECKER2`, an actor neither it "
                                    "nor UAT-006 defines; it is made here as the checker who "
                                    "refused the adjustment. Refused, naming the adjustment's "
                                    "status `rejected` and an empty `permitted` list.")
                    :adjustment adj3 :actor checker :decision {}
                    :expect-status 409}))
        (snap! "s12" "After step 12 — the second proposal posts"
               "The refused proposal posted nothing; the second one did.")

        ;; -- Step 13 -------------------------------------------------------
        (let [instruction (fn [id title narrative]
                            (rec/request! rec {:id id :title title :narrative narrative
                                               :method "GET" :path (str "/payment-instructions/" returns)
                                               :query org-only
                                               :headers {"x-actor-id" ctrl}
                                               :expect-status 200}))
              read-break  (fn [id title narrative]
                            (rec/request! rec {:id id :title title :narrative narrative
                                               :method "GET" :path (break-path brkr)
                                               :query org-only
                                               :headers {"x-actor-id" ctrl}
                                               :expect-status 200}))
              retry-body  (fn [account target]
                            (array-map "organisationId" org-id
                                       "debtorAccountId" funds-id
                                       "creditorName" "Pacific Rim Logistics Pte Ltd"
                                       "creditorAccount" account
                                       "amount" {"currency" "SGD" "minorUnits" 110000}
                                       "valueDate" value-date
                                       "purposeCode" "SUPP"
                                       "retriesId" target))]
          (read-break "s13-break"
                      "Step 13 · The break about the returned payment"
                      (str "The `missing-line` break: `expectation-unmatched`, naming the returned "
                           "payment's `instructionId`, with no `retriedByInstructionIds` yet."))
          (instruction "s13-returned"
                       "Step 13 · The payment that came back"
                       "`returned`, with no permitted transitions.")
          (rec/request! rec {:id "s13-retry-settled"
                             :title "Step 13 · Retry a payment that settled"
                             :narrative (str "Refused, naming the instruction's status `settled`, "
                                             "the attempt `retry`, and the status a retry may "
                                             "name.")
                             :method "POST" :path "/payment-instructions"
                             :headers {"x-actor-id" maker
                                       "idempotency-key" "capture-uat007-retry-settled"}
                             :body (retry-body "SG-SYNTH-88012399" settles)
                             :expect-status 409})
          (let [retry (rec/request! rec {:id "s13-retry"
                                         :title "Step 13 · Retry the returned payment"
                                         :narrative (str "A new instruction, in `draft`, naming the "
                                                         "returned payment in `retriesId`, to a "
                                                         "corrected beneficiary account.")
                                         :method "POST" :path "/payment-instructions"
                                         :headers {"x-actor-id" maker
                                                   "idempotency-key" "capture-uat007-retry-returned"}
                                         :body (retry-body "SG-SYNTH-88012777" returns)
                                         :expect-status 201})
                retry-id (body retry "id")]
            (instruction "s13-returned-retried"
                         "Step 13 · The link, from the original"
                         "The returned payment now names the retry in `retriedByIds`.")
            (read-break "s13-break-retried"
                        "Step 13 · The link, from the break"
                        "The break names it too, in `retriedByInstructionIds`.")
            (rec/request! rec {:id "s13-relink-refused"
                               :title "Step 13 · Point the retry somewhere else"
                               :narrative "Refused, naming `retriesId`."
                               :method "PATCH" :path (str "/payment-instructions/" retry-id)
                               :headers {"x-actor-id" maker
                                         "idempotency-key" "capture-uat007-relink"}
                               :body (array-map "organisationId" org-id "retriesId" settles)
                               :expect-status 422})
            (seed! ctx {:id "s13-unlink-refused"
                        :title "Step 13 · And behind the API"
                        :narrative (str "UAT-007's update, naming the retry. The database's own "
                                        "answer is below.")
                        :expect-refusal true
                        ;; The trigger's message, not its name: the name is
                        ;; in the migration, and the error carries only what
                        ;; the trigger function raises.
                        :expect-error #"retries_id is set when the retry is raised and never changes"
                        :statement (format "update payment_instruction set retries_id = null where id = '%s';"
                                           retry-id)})
            (rec/request! rec {:id "s13-retry-evidence"
                               :title "Step 13 · The link, in the trail"
                               :narrative "The retry's evidence pack, read as the auditor."
                               :method "GET" :path (str "/audit/evidence/" retry-id)
                               :query org-only
                               :headers {"x-actor-id" auditor}
                               :expect-status 200})))
        (snap! "s13" "After step 13 — the retry is raised"
               "Unchanged from step 12: raising the retry posted no entry.")

        ;; -- Step 14 -------------------------------------------------------
        (rec/request! rec {:id "s14-accounts"
                           :title "Step 14 · Find the clearing account again"
                           :narrative "As the script does, to name the account the status is for."
                           :method "GET" :path "/accounts" :query org-only
                           :headers {"x-actor-id" ctrl}
                           :expect-status 200})
        (let [status (rec/request! rec {:id "s14-status"
                                        :title "Step 14 · Reconciliation status for the account and period"
                                        :narrative (str "Read as the auditor: statements received, "
                                                        "lines matched and unmatched, matches by "
                                                        "rule — every rule listed — breaks by "
                                                        "state, and the oldest unresolved age.")
                                        :method "GET" :path "/reconciliation-status"
                                        :query (array-map "organisationId" org-id
                                                          "accountId" transit-id
                                                          "from" from "to" to)
                                        :headers {"x-actor-id" auditor}
                                        :expect-status 200})
              ;; The API zero-fills every state and rule; the rows hold only
              ;; what exists. Compared on what exists.
              nonzero (fn [m] (into {} (remove (comp zero? val)) m))]
          ;; "Check the figures against the breaks themselves … If they differ,
          ;; stop and raise a defect."
          (confirm! conn {:step "14" :what "breaks by state, against the rows"
                          :sql (str "select state, count(*) as breaks from reconciliation_break"
                                    " where organisation_id = ?::uuid group by state order by state")
                          :params [org-id]
                          :expected (vec (for [[k v] (sort (nonzero (body status "breaksByState")))]
                                           {"state" k "breaks" v}))})
          (confirm! conn {:step "14" :what "matches by rule, against the rows"
                          :sql (str "select m.rule_id, count(*) as matches"
                                    "  from reconciliation_match m"
                                    "  join reconciliation_statement s on s.id = m.statement_id"
                                    " where s.organisation_id = ?::uuid"
                                    " group by m.rule_id order by m.rule_id")
                          :params [org-id]
                          :expected (vec (for [[k v] (sort (nonzero (body status "matchesByRule")))]
                                           {"rule_id" k "matches" v}))}))

        ;; -- Step 15 -------------------------------------------------------
        (rec/request! rec {:id "s15-trail"
                           :title "Step 15 · The trail"
                           :narrative "Every audit event for the organisation, read as the auditor."
                           :method "GET" :path "/audit/events" :query org-only
                           :headers {"x-actor-id" auditor}
                           :expect-status 200})
        (rec/request! rec {:id "s15-break-evidence"
                           :title "Step 15 · The evidence pack for the break resolved in step 9"
                           :narrative "Opened, assigned twice, resolved — each naming its actor."
                           :method "GET" :path (str "/audit/evidence/" brk)
                           :query org-only
                           :headers {"x-actor-id" auditor}
                           :expect-status 200})
        (seed! ctx {:id "s15-rewrite-match-refused"
                    :title "Step 15 · Rewrite which rule made a match"
                    :narrative "UAT-007's statement, as written. The database's own answer is below."
                    :expect-refusal true
                    :expect-error #"reconciliation_match is append-only"
                    :statement "update reconciliation_match set rule_id = 'R3-reference-only';"})
        (seed! ctx {:id "s15-delete-lines-refused"
                    :title "Step 15 · Delete what a statement carried"
                    :narrative "UAT-007's statement, as written. The database's own answer is below."
                    :expect-refusal true
                    :expect-error #"reconciliation_statement_line is append-only"
                    :statement "delete from reconciliation_statement_line;"})
        (seed! ctx {:id "s15-truncate-refused"
                    :title "Step 15 · Empty the statements table"
                    :narrative (str "UAT-007's statement, as written. The database's own answer is "
                                    "below; it names the foreign key from "
                                    "`reconciliation_statement_line`.")
                    :expect-refusal true
                    :expect-error #"cannot truncate a table referenced in a foreign key constraint"
                    :statement "truncate reconciliation_statement;"})

        {:organisation-id org-id
         :subjects {:settlement-batch batch-id
                    :settled-instruction settles
                    :returned-instruction returns
                    :silent-instruction silent
                    :breaks {:break brk :break2 brk2 :break3 brk3 :break-r brkr}}
         :sand-table {:codes ["1100-CLIENT-FUNDS" "1300-IN-TRANSIT"
                              "2100-CLIENT-PAYABLE" "2200-UNAPPLIED"]
                      :rows @rows}}))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def all
  "The scenarios, in the order the walkthrough presents them."
  [{:id "segregation-of-duties-refused"
    :title "Segregation of duties, attempted and refused"
    :summary (str "An operator tries to open a ledger account, a second operator tries to "
                  "submit somebody else's draft, and the maker tries to approve her own "
                  "payment. Every attempt is refused by the running system.")
    :source "docs/uat/UAT-005-segregation-of-duties.md"
    :run segregation-of-duties}
   {:id "settlement-batch-misbehaves"
    :title "A settlement batch, and the four ways a scheme misbehaves"
    :summary (str "Three payments are released to a simulated scheme. One settles, one is "
                  "returned, one is never answered; along the way the scheme repeats itself "
                  "and then contradicts itself. The ledger sand table follows the money.")
    :source "docs/uat/UAT-006-settlement-simulation.md"
    :people (people "11111111")
    :run settlement-batch}
   {:id "evidence-pack-timeline"
    :title "The evidence pack an auditor extracts"
    :summary (str "One payment from capture to settlement, then the complete trail for it and "
                  "for the batch that carried it, extracted through "
                  "GET /audit/evidence/{subjectId}.")
    :source "docs/uat/UAT-006-settlement-simulation.md"
    :people (people "22222222")
    :run evidence-pack}
   {:id "reconciliation-breaks"
    :title "Reconciliation: a statement, its breaks, and the corrections that close them"
    :summary (str "A simulated scheme's statement for a batch — one payment settled, one "
                  "returned, one never answered — is ingested, delivered again, contradicted "
                  "under the same reference, and then asked for three more times with a "
                  "deliberate fault. The breaks those faults open are queued, assigned and "
                  "corrected — one adjustment approved by a second actor, one refused by a "
                  "second actor, two below the band posted by their proposer — and a returned "
                  "payment is retried. The sand table follows the settlement accounts and "
                  "2200-UNAPPLIED as each correction posts.")
    :source "docs/uat/UAT-007-reconciliation-and-breaks.md"
    :people (people "33333333")
    :run reconciliation-breaks}])
