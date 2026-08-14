(ns clofin.tools.capture.scenarios
  "The three scenarios, written as the calls they make.

  Each one is a transcription of an acceptance-test script that a human runs
  by hand — `docs/uat/UAT-005-segregation-of-duties.md` and
  `docs/uat/UAT-006-settlement-simulation.md` — into calls a harness makes and
  records. That lineage is deliberate: the UAT scripts are the project's own
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
  (UAT-005 §2)."
  [{:keys [rec conn]} {:keys [id title narrative statement expect-refusal]}]
  (let [result (store/execute! conn statement)]
    (when (and (not expect-refusal) (not (:ok result)))
      (throw (ex-info (format "capture refuses: seed step %s failed: %s" id (:error result))
                      {:step id :statement statement :result result})))
    (when (and expect-refusal (:ok result))
      (throw (ex-info (format (str "capture refuses: step %s expected the database to refuse this "
                                   "statement and it succeeded.") id)
                      {:step id :statement statement})))
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
  "Capture all three sand-table accounts at one moment.

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

;; ---------------------------------------------------------------------------
;; Scenario 1 — segregation of duties, attempted and refused
;; ---------------------------------------------------------------------------

(def ^:private priya "11111111-1111-1111-1111-111111111111")
(def ^:private wei   "22222222-2222-2222-2222-222222222222")
(def ^:private sam   "44444444-4444-4444-4444-444444444444")
(def ^:private rae   "55555555-5555-5555-5555-555555555555")
(def ^:private tom   "66666666-6666-6666-6666-666666666666")

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
                                 "('33333333-3333-3333-3333-333333333333','%s','Nadia (checker)'),"
                                 "('%s','%s','Sam (controller)'),"
                                 "('%s','%s','Rae (auditor)'),"
                                 "('%s','%s','Tom (second operator)'); "
                                 "insert into actor_role (actor_id, role) values "
                                 "('%s','operator'),('%s','approver'),"
                                 "('33333333-3333-3333-3333-333333333333','approver'),"
                                 "('%s','controller'),('%s','auditor'),('%s','operator'); "
                                 "insert into approver_limit (actor_id, currency, limit_minor) values "
                                 "('%s','SGD',500000),"
                                 "('33333333-3333-3333-3333-333333333333','SGD',5000000); "
                                 "insert into approval_threshold "
                                 "(organisation_id, currency, from_minor, approvals_required) "
                                 "values ('%s','SGD',0,1),('%s','SGD',100000,2);")
                            priya org-id wei org-id org-id sam org-id rae org-id tom org-id
                            priya wei sam rae tom
                            wei
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

  Parameterised by a prefix because two scenarios seed the same three roles in
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
              :narrative (str "No role holds both `payment/approve` and `settlement/execute`: "
                              "the actor who agreed a payment is never the actor who pushes it "
                              "out of the door.")
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

    (rec/request! rec {:id "opening-balance"
                       :title "The client's money arrives"
                       :narrative (str "An opening entry: SGD 10,000.00 debited to client funds "
                                       "and credited to what CloFin owes the client. Two lines, "
                                       "one entry, and it balances.")
                       :method "POST" :path "/journal-entries"
                       :headers {"x-actor-id" ctrl}
                       :body {"occurredAt" "2026-11-01T09:00:00Z"
                              "narrative" "Opening client balance"
                              "reference" {"type" "opening-balance" "id" org-id}
                              "lines" [{"accountId" funds-id
                                        "direction" "debit"
                                        "amount" {"currency" "SGD" "minorUnits" 1000000}}
                                       {"accountId" (get by-code "2100-CLIENT-PAYABLE")
                                        "direction" "credit"
                                        "amount" {"currency" "SGD" "minorUnits" 1000000}}]}
                       :expect-status 201})

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

    (let [settles (raise-approved! ctx {:key "settles" :suffix "0" :funds-id funds-id
                                        :narrative (str "The simulated scheme reads the last digit "
                                                        "of the creditor account: 0–6 settles.")})
          returns (raise-approved! ctx {:key "returns" :suffix "7" :funds-id funds-id
                                        :narrative "7 and 8 come back."})
          silent  (raise-approved! ctx {:key "silent" :suffix "9" :funds-id funds-id
                                        :narrative "9 is never answered at all."})
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

      (let [batch (rec/request! rec {:id "batch-created"
                                     :title "The controller batches the three approved payments"
                                     :narrative "One scheme, one currency, one value date."
                                     :method "POST" :path "/settlement-batches"
                                     :headers {"x-actor-id" ctrl}
                                     :body {"scheme" "SIM-RTGS" "currency" "SGD"
                                            "valueDate" value-date
                                            "instructionIds" [settles returns silent]}
                                     :expect-status 201})
            batch-id (body batch "id")]

        (rec/request! rec {:id "batch-submitted"
                           :title "Release the batch"
                           :narrative (str "Three items, and the simulated scheme acknowledges "
                                           "receipt. Every instruction is now `released`.")
                           :method "POST" :path (str "/settlement-batches/" batch-id "/submit")
                           :headers {"x-actor-id" ctrl}
                           :body {}
                           :expect-status 200})

        (snap! "released" "The batch is released"
               (str "SGD 3,750.00 has left client funds and is sitting in "
                    "1300-IN-TRANSIT — three payments released and none yet settled."))

        (rec/request! rec {:id "response-settled"
                           :title "One settles"
                           :narrative "The batch stays `submitted`: two items are still outstanding."
                           :method "POST" :path (str "/settlement-batches/" batch-id "/scheme-responses")
                           :headers {"x-actor-id" ctrl}
                           :body {"kind" "settled" "instructionId" settles "reference" "SIM-STL-1"}
                           :expect-status 200})

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

        (rec/request! rec {:id "response-returned"
                           :title "One comes back"
                           :narrative (str "The returned item appears under `exceptions` with its "
                                           "reason — the queue an operator actually works.")
                           :method "POST" :path (str "/settlement-batches/" batch-id "/scheme-responses")
                           :headers {"x-actor-id" ctrl}
                           :body {"kind" "returned" "instructionId" returns
                                  "reference" "SIM-RTN-1"
                                  "reason" "SIM-RETURN: beneficiary account closed"}
                           :expect-status 200})

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
        _ (seed! ctx {:id "seed-auditor"
                      :title "Seed an auditor"
                      :narrative "Rae reads the trail and nothing else."
                      :statement (format
                                  (str "insert into actor (id, organisation_id, display_name) "
                                       "values ('%s','%s','Rae (auditor)'); "
                                       "insert into actor_role (actor_id, role) values ('%s','auditor');")
                                  auditor org-id auditor)})
        accounts (settlement-accounts! ctx)
        by-code (into {} (map (juxt :code :id)) accounts)
        funds-id (get by-code "1100-CLIENT-FUNDS")]

    (rec/request! rec {:id "opening-balance"
                       :title "The client's money arrives"
                       :narrative "So the payment has something to move."
                       :method "POST" :path "/journal-entries"
                       :headers {"x-actor-id" ctrl}
                       :body {"occurredAt" "2026-11-01T09:00:00Z"
                              "narrative" "Opening client balance"
                              "reference" {"type" "opening-balance" "id" org-id}
                              "lines" [{"accountId" funds-id
                                        "direction" "debit"
                                        "amount" {"currency" "SGD" "minorUnits" 500000}}
                                       {"accountId" (get by-code "2100-CLIENT-PAYABLE")
                                        "direction" "credit"
                                        "amount" {"currency" "SGD" "minorUnits" 500000}}]}
                       :expect-status 201})

    (let [pi (raise-approved! ctx {:key "settles" :suffix "0" :funds-id funds-id
                                   :narrative "One payment, raised by the maker."})
          batch (rec/request! rec {:id "batch-created"
                                   :title "Batch it"
                                   :narrative "One approved instruction, one batch."
                                   :method "POST" :path "/settlement-batches"
                                   :headers {"x-actor-id" ctrl}
                                   :body {"scheme" "SIM-RTGS" "currency" "SGD"
                                          "valueDate" value-date "instructionIds" [pi]}
                                   :expect-status 201})
          batch-id (body batch "id")]

      (rec/request! rec {:id "batch-submitted"
                         :title "Release it"
                         :narrative "The instruction becomes `released` and the value posts to in-transit."
                         :method "POST" :path (str "/settlement-batches/" batch-id "/submit")
                         :headers {"x-actor-id" ctrl}
                         :body {}
                         :expect-status 200})

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
;; The roster
;; ---------------------------------------------------------------------------

(def all
  "The three scenarios, in the order the walkthrough presents them."
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
    :run evidence-pack}])
