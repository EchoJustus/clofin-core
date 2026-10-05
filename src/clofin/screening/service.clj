(ns clofin.screening.service
  "Screening as units of work: core screens at every submission, a client's
  result is recorded as evidence, and a compliance actor dispositions a case
  (C-07; ADR-0028 D5; docs/briefs/017-TASK-screening-and-cases.md).

  Every function here takes a `tx` — a connection inside a transaction the
  *caller* owns, in practice the one `clofin.idempotency.repository/execute-once!`
  hands its effect — and requires no `clofin.db.*` namespace, exactly as
  `clofin.payments.approval-service` does. Each opens with
  `clofin.audit.repository/assert-unit-of-work!`, before its first write, so a
  pool or an autocommit connection is refused rather than trusted (F-011, L-13):
  a result, the case it opened and the events describing both commit together
  or not at all.

  ## Two refusals are values

  `record-result!`'s `screening-result-mismatch` and `submit-screened!`'s
  `screening-hit` are **returned**, never thrown. The result row and the case
  it opened are the evidence of the refusal, and throwing inside the
  transaction would roll them back with it — F-008's shape, lesson **L-11**.
  The handler lets the transaction commit, then renders the `422` or `409`.
  Every other refusal here stores nothing and is thrown as usual.

  ## Evidence is not a transition

  Nothing here moves a payment except `submit-screened!` on a `:permit`, and
  that through `clofin.payments.repository/transition!`, whose own gate re-reads
  and re-decides under the lock. A client's result — `clear` or `hit`, accepted
  or refused — transitions nothing and emits no `payment.*` event: a `201` from
  recording means *evidence recorded, state unchanged*, and nothing else
  (ADR-0028 D5, the operator's ruling).

  **The list is synthetic and the matching exact.** Nothing here measures or
  claims screening quality; it builds the control's shape.

  [C-07]: docs/COMPLIANCE.md"
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.error :as err]
            [clofin.payments.repository :as payments]
            [clofin.payments.state :as state]
            [clofin.screening.decision :as decision]
            [clofin.screening.list :as screening-list]
            [clofin.screening.repository :as screening]
            [clofin.screening.rules :as rules]
            [clofin.screening.subject :as subject]
            [clojure.string :as str]))

(def refusal-reasons
  "Every `errors.reason` the screening operations answer with, published as
  `ScreeningRefusalReason` (`clofin.contract-test` compares the two in both
  directions).

  | Reason | Status | Where |
  |---|---|---|
  | `list-version-not-accepted` | `422` | recording: the version is unknown or retired |
  | `outcome-entries-inconsistent` | `422` | recording: `hit` with no entries, `clear` with some |
  | `matched-entries-unknown` | `422` | recording: an entry the list version does not hold, or holds with other rules |
  | `instruction-digest-mismatch` | `422` | recording: not the digest of the instruction as it stands |
  | `screening-result-mismatch` | `422` | recording: core's recomputation disagrees — **stored**, refused |
  | `screening-hit` | `409` | submit: core's decision is a hit that stands |
  | `screening-required` | `409` | `transition!`'s gate: no core decision over the current content |
  | `no-screening-list-accepted` | `422` | submit: no list is accepted |
  | `self-disposition` | `403` | disposition: the actor created the instruction |"
  (sorted-set "list-version-not-accepted" "outcome-entries-inconsistent"
              "matched-entries-unknown" "instruction-digest-mismatch"
              "screening-result-mismatch" "screening-hit" "screening-required"
              "no-screening-list-accepted" "self-disposition"))

(defn- reason [r] (or (refusal-reasons r) (throw (ex-info "Undeclared refusal reason" {:reason r}))))

(defn- unprocessable!
  [r message data]
  (err/fail! :unprocessable message (assoc data :reason (reason r))))

(defn- record-event!
  [tx {:keys [organisation-id actor action subject-type subject before after correlation-id]}]
  (audit-store/record! tx {:organisation-id organisation-id
                           :actor-id        (:id actor)
                           :action          action
                           :subject-type    subject-type
                           :subject-id      (:id subject)
                           :before          before
                           :after           after
                           :correlation-id  correlation-id}))

(defn- record-result-event!
  "`screening-result.recorded` for a row just stored; returns the result with
  the event's id, which the `ScreeningResult` resource carries."
  [tx organisation-id actor result correlation-id]
  (let [event (record-event! tx {:organisation-id organisation-id :actor actor
                                 :action "screening-result.recorded"
                                 :subject-type "screening-result" :subject result
                                 :before nil :after (audit/screening-result-subject result)
                                 :correlation-id correlation-id})]
    (assoc result :audit-event-id (:id event))))

(defn- open-case-on-hit!
  "Open a case on `result`'s hit unless one is open on the instruction, and
  audit an opening. Returns `{:case … :opened? bool}`."
  [tx organisation-id actor result correlation-id]
  (let [{opened-case :case opened? :opened? :as answer}
        (screening/open-case! tx {:id                 (random-uuid)
                                  :organisation-id    organisation-id
                                  :instruction-id     (:instruction-id result)
                                  :result-id          (:id result)
                                  :instruction-digest (:instruction-digest result)
                                  :list-version       (:list-version result)})]
    (when opened?
      (record-event! tx {:organisation-id organisation-id :actor actor
                         :action "screening-case.opened"
                         :subject-type "screening-case" :subject opened-case
                         :before nil :after (audit/screening-case-subject opened-case)
                         :correlation-id correlation-id}))
    answer))

;; ---------------------------------------------------------------------------
;; Recording a client's result
;; ---------------------------------------------------------------------------

(defn- assert-entries-known!
  "Every submitted entry names an entry of `listed` **with the same rules**, so
  a client cannot claim a match on rules the list does not have."
  [listed submitted]
  (let [by-id (into {} (map (juxt :id identity)) (:entries listed))]
    (doseq [{:keys [id rules]} submitted]
      (let [known (get by-id id)]
        (when-not (and known (screening-list/same-rules? rules (:rules known)))
          (unprocessable! "matched-entries-unknown"
                          (if known
                            (str "Entry " id " is in list " (:version listed)
                                 " with different rules than the ones submitted")
                            (str "List " (:version listed) " holds no entry " id))
                          {:entry id :listVersion (:version listed)}))))))

(defn record-result!
  "Record a client's screening result as evidence. Returns
  `{:result … :case … :refused? bool :detail …}`; never throws for a mismatch —
  the handler renders `422` after commit (L-11).

  The instruction is locked first (`lock-instruction!`, L-8), and every check
  below reads what the lock holds — the status, the digest — in the order of
  ADR-0028 D5's table, each answering before the next is asked:

  1. `404` — the id is not an instruction of this organisation.
  2. `409` — the instruction is not a `draft`: evidence about a submitted
     instruction is evidence about a decision already taken. The lifecycle's
     own refusal shape, with `instruction-status`.
  3. `422 list-version-not-accepted` — the version is not the accepted list:
     unknown, or retired.
  4. `422 outcome-entries-inconsistent` — a `hit` naming no entries, or a
     `clear` naming some.
  5. `422 matched-entries-unknown` — an entry that version does not hold, or
     holds with other rules.
  6. `422 instruction-digest-mismatch` — `instructionDigest` is not
     `clofin.screening.subject/digest` of the locked row.

  None of those stores anything. Then core recomputes with
  `clofin.screening.rules/evaluate` against the same list version. Equal
  outcome and equal entry-id set → stored `accepted`, `agrees: true`; an
  accepted `hit` opens a case unless one is open on the instruction (the
  partial unique index decides), bound to (instruction, digest, list version).
  Anything else → stored **`refused`**, `screening-result-mismatch`,
  `agrees: false`, returned as a refusal. One `screening-result.recorded` event
  for every row stored, refused included; `screening-case.opened` when a case
  opens. **No payment transition and no `payment.*` event, whatever the
  outcome.**"
  [tx {:keys [organisation-id instruction-id actor list-version outcome
              matched-entries instruction-digest screened-at correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
  (let [instruction (payments/lock-instruction! tx organisation-id instruction-id)
        _ (state/assert-screenable! (:status instruction))
        listed (screening/accepted-list tx)
        _ (when-not (and listed (= list-version (:version listed)))
            (unprocessable! "list-version-not-accepted"
                            (str "List version " list-version " is not the accepted screening list"
                                 (if listed (str "; the accepted list is " (:version listed))
                                     "; no list is accepted"))
                            {:listVersion list-version}))
        _ (when (not= (= :hit outcome) (boolean (seq matched-entries)))
            (unprocessable! "outcome-entries-inconsistent"
                            (if (= :hit outcome)
                              "A hit must name the entries it matched"
                              "A clear result cannot name matched entries")
                            {:outcome (name outcome)}))
        _ (assert-entries-known! listed matched-entries)
        digest (subject/digest instruction)
        _ (when-not (= instruction-digest digest)
            (unprocessable! "instruction-digest-mismatch"
                            (str "instructionDigest is not the digest of this instruction as it "
                                 "stands; read screeningDigest from the instruction and screen again")
                            {}))
        recomputed (rules/evaluate instruction (:entries listed))
        submitted-ids (vec (sort (distinct (map :id matched-entries))))
        agrees? (and (= outcome (:outcome recomputed))
                     (= (set submitted-ids) (set (:matched recomputed))))
        stored (screening/insert-result!
                tx {:id                 (random-uuid)
                    :organisation-id    organisation-id
                    :instruction-id     instruction-id
                    :list-version       (:version listed)
                    :origin             :client
                    :outcome            outcome
                    :core-outcome       (:outcome recomputed)
                    :agrees             agrees?
                    :instruction-digest digest
                    :disposition        (if agrees? :accepted :refused)
                    :disposition-reason (when-not agrees? (reason "screening-result-mismatch"))
                    :recorded-by        (:id actor)
                    :screened-at        screened-at
                    :submitted-entries  submitted-ids
                    :core-entries       (:matched recomputed)})
        result (record-result-event! tx organisation-id actor stored correlation-id)]
    (if-not agrees?
      {:result   result
       :refused? true
       :reason   (reason "screening-result-mismatch")
       :detail   (str "Core's screening of this instruction against " (:version listed)
                      " is " (name (:outcome recomputed))
                      (when (seq (:matched recomputed))
                        (str " (" (str/join ", " (:matched recomputed)) ")"))
                      ", which this result does not reproduce. The result is recorded as refused "
                      "evidence; nothing else changed.")}
      (let [{open-case :case} (when (= :hit outcome)
                                (open-case-on-hit! tx organisation-id actor result correlation-id))
            ;; `caseId` names the case for *this* hit — opened by it, or already
            ;; open on the same content against the same list. An open case on
            ;; other content is not this hit's.
            this-hits (when (and open-case
                                 (= (:instruction-digest open-case) digest)
                                 (= (:list-version open-case) (:version listed)))
                        open-case)]
        {:result   (cond-> result this-hits (assoc :case-id (:id this-hits)))
         :case     this-hits
         :refused? false}))))

;; ---------------------------------------------------------------------------
;; Core screens at submit
;; ---------------------------------------------------------------------------

(defn submit-screened!
  "Screen, decide, and submit if core's decision permits. Returns
  `{:instruction … :before … :result …}` on permit, or
  `{:refused? true :reason \"screening-hit\" :case … :disposition … :result …}`.

  In the caller's transaction, in this order:

  1. **Lock the instruction** and ask provenance and the lifecycle first
     (`clofin.payments.repository/assert-may-apply!`): a submission they refuse
     must not leave a decision behind or open a case on the strength of a
     request that was never going to be honoured.
  2. **Read the accepted list.** None is `422 no-screening-list-accepted` —
     unconfigured is not unsupervised, the `no-threshold-configured` posture.
     Never an empty list standing in for one: \"nothing to screen against\"
     would make every instruction clear, which is the control's absence
     wearing its name.
  3. **Screen** the locked row with `clofin.screening.rules/evaluate` and store
     core's result (`origin core`, `accepted`), with its event.
  4. **Decide** with `clofin.screening.decision/decide` and the latest case for
     (instruction, digest, list):
     - `:permit` → `transition! :submit`, whose own gate re-reads the result
       just stored and decides again under the lock;
     - `:refuse/hit` → open a case unless one is open, and return the refusal;
     - `:refuse/confirmed-hit` → return the refusal with the disposition; the
       maker's path is `cancel`.

  A refused submission emits **no** `payment.*` event — the refused-attempts
  policy — and the result and the case it opened are the evidence."
  [tx {:keys [organisation-id instruction-id actor correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
  (let [existing (payments/lock-instruction! tx organisation-id instruction-id)
        _ (payments/assert-may-apply! existing :submit actor)
        listed (or (screening/accepted-list tx)
                   (unprocessable! "no-screening-list-accepted"
                                   (str "No screening list is accepted, so this instruction cannot be "
                                        "screened and is not submitted; a list is loaded by the operator's "
                                        "tool (make load-screening-list), never through the API")
                                   {}))
        digest (subject/digest existing)
        {:keys [outcome matched]} (rules/evaluate existing (:entries listed))
        stored (screening/insert-result!
                tx {:id                 (random-uuid)
                    :organisation-id    organisation-id
                    :instruction-id     instruction-id
                    :list-version       (:version listed)
                    :origin             :core
                    :outcome            outcome
                    :core-outcome       outcome
                    :agrees             true
                    :instruction-digest digest
                    :disposition        :accepted
                    :recorded-by        (:id actor)
                    :core-entries       matched})
        result (record-result-event! tx organisation-id actor stored correlation-id)
        latest-case (screening/latest-case-for tx instruction-id digest (:version listed))
        verdict (decision/decide {:digest             digest
                                  :latest-core-result (assoc result :list-retired? false)
                                  :case               latest-case})]
    (case verdict
      :permit
      (let [{:keys [before after]} (payments/transition! tx organisation-id instruction-id :submit
                                                         {:actor actor})]
        {:before before :instruction after :result result})

      :refuse/confirmed-hit
      {:refused?     true
       :reason       (reason "screening-hit")
       :case         latest-case
       :disposition  :confirmed-hit
       :list-version (:version listed)
       :result       result}

      :refuse/hit
      (let [{open-case :case} (open-case-on-hit! tx organisation-id actor result correlation-id)]
        {:refused?     true
         :reason       (reason "screening-hit")
         :case         open-case
         :list-version (:version listed)
         :result       result})

      ;; Unreachable: the result decided on was written above, over this
      ;; digest, against the accepted list. Reaching here is a defect.
      (throw (ex-info "Core's screening decision did not hold for the result it just stored"
                      {:decision verdict})))))

;; ---------------------------------------------------------------------------
;; Disposition
;; ---------------------------------------------------------------------------

(def max-rationale-length
  "A rationale is 1-1000 characters, counted as Unicode code points — what
  JSON Schema's `maxLength` counts."
  1000)

(defn- assert-rationale!
  [rationale]
  (let [ok? (and (string? rationale)
                 (not (str/blank? rationale))
                 (<= (.codePointCount ^String rationale 0 (count rationale)) max-rationale-length))]
    (when-not ok?
      (err/fail! :field-validation "Request failed validation"
                 {"rationale" (str "must be 1-" max-rationale-length
                                   " characters and not blank: a disposition carries its reason")}))
    rationale))

(defn disposition!
  "Disposition an open case, as a compliance actor who is not the
  instruction's maker. Returns `{:case … :before …}`.

  1. `404` — no such case in this organisation.
  2. The instruction is locked, **then** the case — the lock order
     `clofin.screening.repository` states — so a disposition and a submission
     of the same instruction serialise rather than interleave.
  3. `403 self-disposition` — the actor created the instruction. C-01's shape:
     the maker never clears their own hit. Asked before the case's status, as
     `transition!` asks provenance before the lifecycle: no answer about the
     case helps an actor who may not decide it.
  4. `409` — the case is already dispositioned; a disposition is final (the
     trigger `screening_case_disposition_final` is the second enforcement).
  5. The rationale: 1-1000 characters, not blank.

  A disposition is bound to the digest and list version the case names: an
  amendment or a new list makes it moot, and the next submission opens a new
  case if the hit stands. `screening-case.dispositioned`, with before and after
  digests, in the same transaction."
  [tx {:keys [organisation-id case-id actor disposition rationale correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
  (let [found (or (screening/find-case tx organisation-id case-id)
                  (err/not-found! "No such screening case in this organisation" {:id (str case-id)}))
        instruction (payments/lock-instruction! tx organisation-id (:instruction-id found))
        before (screening/lock-case! tx organisation-id case-id)]
    (when (= (:id actor) (:created-by instruction))
      (err/forbidden! (str "The actor who created a payment instruction may not disposition its "
                           "screening case: the maker never clears their own hit")
                      {:reason (reason "self-disposition")
                       :instructionId (str (:id instruction))}))
    (when-not (= :open (:status before))
      (err/conflict! (str "Screening case " case-id " is already dispositioned "
                          (some-> (:disposition before) name)
                          "; a disposition is final")
                     {:case-status (name (:status before))
                      :attempted   "disposition"
                      :permitted   []}))
    (when-not (contains? decision/case-dispositions disposition)
      (err/invalid! "disposition must be false-positive or confirmed-hit" {:field "disposition"}))
    (assert-rationale! rationale)
    (let [after (screening/disposition-case! tx {:id               case-id
                                                 :disposition      disposition
                                                 :rationale        rationale
                                                 :dispositioned-by (:id actor)})]
      (record-event! tx {:organisation-id organisation-id :actor actor
                         :action "screening-case.dispositioned"
                         :subject-type "screening-case" :subject after
                         :before (audit/screening-case-subject before)
                         :after (audit/screening-case-subject after)
                         :correlation-id correlation-id})
      {:case after :before before})))
