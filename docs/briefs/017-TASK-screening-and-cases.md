# TASK-017: Sanctions screening and cases — C-07 built as designed, with a client's result as evidence and core's decision as the gate

| Field | Value |
|---|---|
| **Increment** | 7 — financial crime controls (screening and cases; fraud scoring stays designed, PR-062) |
| **Status** | `READY` |
| **Depends on** | TASK-018 ✅ merged (a screening rule names `creditor-country`); [ADR-0028](../ADR/0028-satellite-clients-integrate-through-core-owned-contracts.md) D5 and D8 |
| **Blocks** | TASK-019 (both widen `role_known` and the vocabulary owners; sequential by ruling D10); the `ref-3` release |
| **Requirements** | PR-060, PR-061, PR-063 (Must); PR-015 (the queue shows the screening outcome); PRD Q2 resolved here; C-07 |
| **Controls touched** | **C-07** (📋 → ✅); C-05 (three audit terms, two subject types); C-08 (one role, three permissions); C-01 (a disposition is a decision the maker may not take) |
| **Scope** | Large — one migration, seven operations, one pure rules engine, one seed tool, one UAT script |
| **Base branch** | `clofin-core` `main` after TASK-018's merge |
| **Audit** | `docs/audits/017-REQ-screening-and-cases.md`, task-keyed, filed on the PR branch |

## Objective

After this brief **no instruction can be submitted without a completed
screening decision that core itself made**, against a versioned synthetic
list core holds, and a hit opens a case that a compliance actor — never the
maker — dispositions with a retained rationale. A client's screening result
is recorded as **evidence**: core recomputes it against the same list version,
stores both outcomes and whether they agree, refuses one it cannot reproduce,
and transitions nothing on any of it. The approval queue shows the checker
the screening outcome beside the amount. Every decision is reproducible from
what was retained: the list version, the instruction digest it was taken
over, the matched entries, the actor and the time. The list is synthetic, the
matching is exact, and this brief makes no claim about real-world screening
quality — it builds the control's shape. Nothing here changes what CloFin
claims to be: a synthetic-data reference implementation, connected to
nothing, approved by no one, processing no real funds.

## Context you need

- **The ruling.** ADR-0028 D5, the D5 amendment under *Rulings*, and D8. The
  precondition table in D5 maps every satellite precondition to the moment
  core checks it and the refusal it gives; this brief implements that table
  as written. "**A `201` means evidence recorded and nothing else**" is the
  operator's sentence, and it is the one a reviewer will test first.
- **The control as designed.** `docs/COMPLIANCE.md` C-07 (📋): statement,
  design, enforcement point ("state machine precondition; case creation on a
  hit"), evidence. Its design sentence says "the `submitted → pending_approval`
  transition" — a state that does not exist; the transition is `submit`,
  `draft → pending-approval` (`clofin.payments.state/transitions`, checked).
  Correct the sentence when you mark the control ✅. `docs/DOMAIN_MODEL.md`
  §2.5 (ScreeningResult, Case, FraudAssessment — the last stays 📋) and §3
  rule 1 ("`submit` requires screening to have completed … there is a
  `TODO(increment-7)` at the precondition it will gate"); `docs/PRD.md` §5.7
  and open question Q2.
- **The gate's place.** `clofin.payments.repository/transition!` reads the
  row `for update`, checks provenance, asks the lifecycle for the next state,
  and carries the `TODO(increment-7)` comment (checked: `grep -n
  "TODO(increment-7)" src/clofin/payments/repository.clj` → line 545). The
  gate goes **there**, under the lock, as a read of the latest decision and a
  pure judgement on it — so a direct call of `transition!` with `:submit` and
  no decision is refused by the repository itself, not only by the service
  that normally screens first. That is C-07's "state machine precondition"
  made enforceable (L-6, L-13).
- **Dependency direction.** `ARCHITECTURE.md` §3 names the Compliance context
  as `clofin.compliance`; this brief builds it as **`clofin.screening`**
  (ADR-0028's test identifiers already use that root) and updates the table
  row. The pure namespaces of screening (`rules`, `subject`, `decision`,
  `list`) require nothing from payments, so `clofin.payments.repository` may
  require `clofin.screening.subject` and `clofin.screening.decision` without
  a cycle; `clofin.screening.service` requires `clofin.payments.repository`.
  Update the dependency-rule paragraph in §3 ("Payments depends on ledger and
  authz" gains "and on compliance's pure decision"); the topology diagram
  regenerates from the `ns` forms (`make diagrams`).
- **How services are guarded.** `clofin.ledger.purity-test/pure-namespaces`
  and `/service-namespaces`, and `clofin.audit.unit-of-work-test/audit-composing-calls`
  — three lists a new namespace must join or their tests fail by design;
  `clofin.audit.repository/assert-unit-of-work!` is called at the entry of
  every audit-composing service. `clofin.settlement.service/record-scheme-response!`
  is the shape for a service that **returns a refusal as a value** so the
  handler renders the `409`/`422` after the receipt has committed (L-11).
- **Vocabularies.** `clofin.db.vocabulary-test/owners` must gain an owner for
  every `= ANY (ARRAY[…])` constraint the migration adds — eight (the
  pre-flight counted them); `clofin.authz.model/roles` ↔ `role_known`;
  `clofin.audit/actions` and `/subject-types`; the contract's enum copies
  (`clofin.contract-test` discovers them, and the evidence-pack subject enum
  appears **twice** in `api/openapi.yaml`, lines ~4484 and ~4535 — checked).
- **Idempotency.** Two new operations take `Idempotency-Key`; the contract
  parameter description says "the six payment and approval mutations" and
  `clofin.api.conformance-test/ac-15-the-key-is-required-by-exactly-the-six-operations-that-say-so`
  counts them — both become eight, in the same commit; TASK-018's
  `every-bound-key-names-the-operation-that-bound-it` grows with them.
- **Seeding.** No endpoint creates an actor; `clofin.test-db/insert-actor!`
  and the capture scenarios' `seed!` are the two seams. A screening list is
  reference data of the same kind: **loaded by a tool, never by a client**.
- **What is off limits:** the control-plane documents; `docs/releases/`;
  chain confirmations (TASK-019); fraud scoring (PR-062 — out, below);
  `clofin-trace`, `clofin-cockpit`; the satellites' adapters.

## Scope

### In

- **A-1 — Migration `0015-screening-lists-results-and-cases.sql`**, exactly
  the DDL under *SQL pre-flight*: the role constraint widened to
  `screening-service` (drop and recreate by name, as `0013` does); five
  tables (`screening_list`, `screening_entry`, `screening_rule`,
  `screening_result`, `screening_result_match`, `screening_case`); the
  partial unique index `screening_case_open_key`; append-only triggers on
  lists (except retirement, once), entries, rules, results and matches;
  delete/truncate refused on cases and a dispositioned case frozen by
  trigger. Comments on every table and every non-obvious column.
- **A-2 — The rules engine**, `clofin.screening.rules` (pure):
  `(evaluate instruction entries) → {:outcome :clear|:hit :matched [entry-id …]}`.
  An entry matches when **every** rule in it matches (rules AND within an
  entry; entries OR across the list); `exact` is string equality with no
  case folding, trimming or normalisation — a normalising matcher is a
  different operator and is not built; `creditor-country` compares against
  `:creditor-country`, and an instruction without one matches no country
  rule. Fields: `creditor-name` → `:creditor-name`, `creditor-account` →
  `:creditor-account`, `creditor-country` → `:creditor-country`. Tested by
  enumeration over the field × presence × equality matrix, and with a
  property: insertion order of entries and rules never changes the outcome
  or the matched set.
- **A-3 — The screening subject**, `clofin.screening.subject` (pure):
  `(digest instruction)` = lowercase SHA-256 hex over
  `clofin.idempotency/canonical` of `clofin.audit/normalise` applied to the
  projection `{:projection "screening-subject/1" :id :organisation-id
  :debtor-account-id :creditor-name :creditor-account :creditor-country
  :amount :value-date :purpose-code}` — identity and the screened content,
  **not** status, provenance or timestamps (a submission must not change
  it; an amendment must). The `:projection` member is what versions it: a
  changed projection changes every digest. Rendered on `PaymentInstruction`
  as `screeningDigest` (required, read-only) so a client can echo what core
  holds rather than reimplement the canonical form. Golden test: one fixed
  instruction, one fixed hex.
- **A-4 — The decision**, `clofin.screening.decision` (pure):
  `(decide {:digest d :latest-core-result r :case c})` →
  `:permit`, `:refuse/no-decision`, `:refuse/hit`, `:refuse/confirmed-hit`,
  `:refuse/list-retired`. Rules, in order: no core result for this digest →
  no-decision; the result's list is retired → list-retired; outcome `clear`
  → permit; outcome `hit` and a case for (instruction, digest, list) that is
  dispositioned `false-positive` → permit; dispositioned `confirmed-hit` →
  confirmed-hit; otherwise hit. **`permits-submit?`** is `(= :permit …)`.
  Tested by enumerating the matrix.
- **A-5 — The list**, `clofin.screening.list` (pure) and
  `clofin.tools.screening-list` (the tool). The EDN shape:

  ```clojure
  {:version "synthetic-2026-10-v1"
   :entries [{:id "SYN-0001" :rules [{:field :creditor-name :operator :exact :value "Blocked Counterparty Ltd"}]}
             {:id "SYN-0002" :rules [{:field :creditor-account :operator :exact :value "SG-SYNTH-99999999"}]}
             {:id "SYN-0003" :rules [{:field :creditor-country :operator :exact :value "ZZ"}]}
             {:id "SYN-0004" :rules [{:field :creditor-name :operator :exact :value "Dual Rule Trading Co"}
                                     {:field :creditor-country :operator :exact :value "ZZ"}]}]}
  ```

  `list/validate` refuses a field outside the three (`unsupported-rule-field`,
  naming the entry and the field — the `originator-*` refusal ADR-0028
  promises "at load"), an operator other than `exact`, a blank value, a
  duplicate entry id, an empty entries vector, a version outside
  `^[\x21-\x7E]{1,128}$`. Ship `resources/screening-lists/synthetic-2026-10-v1.edn`
  with those four entries — names that collide with nothing any test or UAT
  script uses today (checked: `grep -rn "Blocked Counterparty\|Dual Rule" test
  docs tools` matches nothing); `ZZ` is a user-assigned code that names no
  country. The tool: `clojure -M:screening-list load <edn-file>` and
  `… retire <version>`, plus `make load-screening-list LIST=…`. **Exactly one
  list is accepted at a time**: `load` of a second version while one is
  accepted refuses unless `--replacing <version>`, which retires the old and
  loads the new in one transaction. Loading is a deployment act recorded by
  the append-only list tables (`loaded_at`, `source`), **not** by the tenant
  audit trail — a list belongs to no organisation and `audit_event` requires
  one; say so in COMPLIANCE's C-07 evidence.
- **A-6 — Recording a client's result**,
  `POST /payment-instructions/{id}/screening-results`
  (`recordScreeningResult`, `Idempotency-Key`, permission `:screening/record`,
  `201` `ScreeningResult`). In `clofin.screening.service/record-result!`,
  inside the handler's transaction: lock the instruction (`payments/lock-instruction!`,
  L-8); the checks of D5's table in this order — `404` (id not in the
  caller's organisation); `409` lifecycle (status not `draft`, with
  `errors.instruction-status`); `422 list-version-not-accepted` (`listVersion`
  is not the accepted list — unknown or retired); `422
  outcome-entries-inconsistent` (`hit` with no entries, `clear` with some);
  `422 matched-entries-unknown` (an entry id not in that list version, or
  whose rules differ from the list's); `422 instruction-digest-mismatch`
  (`instructionDigest` ≠ `subject/digest` of the locked row); then
  recompute with `rules/evaluate` against that list version. Equal outcome
  and equal entry-id set → stored `accepted`, `agrees: true`. Different →
  stored **`refused`, `screening-result-mismatch`, `agrees: false`**, the
  service returns the refusal as a value, and the handler renders `422` with
  `errors.reason`, `errors.coreOutcome`, `errors.coreMatchedEntries` **after
  the transaction commits** (L-11: the disagreement is evidence). An
  accepted `hit` opens a case if none is open for this instruction (the
  partial unique index is the arbiter), bound to (instruction, digest, list
  version), and the response carries `caseId`. Audit: `screening-result.recorded`
  for every stored row, refused included (subject `screening-result`);
  `screening-case.opened` when a case opens. **No payment transition, no
  `payment.*` event**, whatever the outcome.
- **A-7 — Core screens at submit.** `clofin.screening.service/submit-screened!`
  replaces the direct `transition!` call in `clofin.api.payments/submit`'s
  effect, inside the same transaction: lock the instruction; read the accepted
  list (none → `422 no-screening-list-accepted`, the `no-threshold-configured`
  posture: unconfigured is not unsupervised); compute the digest; `evaluate`;
  store core's result (`origin: core`, `accepted`); then `decision/decide`
  with the case for (instruction, digest, list): `:permit` → `payments/transition!
  :submit` (whose own gate re-reads and re-decides under the lock) and the
  handler's `payment.submitted` event as today; `:refuse/hit` → open a case
  if none is open (`screening-case.opened`) and return the refusal; the
  handler renders `409`, `errors.reason: screening-hit`, `errors.caseId`,
  `errors.listVersion`, **after commit**; `:refuse/confirmed-hit` → `409`
  `screening-hit` with `errors.disposition: confirmed-hit` (the maker's path
  is `cancel`). A refused submission emits **no** `payment.*` event (the
  refused-attempts policy, ROADMAP *Deliberately deferred*); the result and
  the case it opened are the evidence.
- **A-8 — The gate in the repository.** `transition!` for `:submit`, after
  the lifecycle check and under the lock: read the latest `origin = 'core'`
  result for this instruction whose `instruction_digest` equals
  `subject/digest` of the locked row, with its list's `retired_at`, and the
  latest case for (instruction, digest, list); `decision/decide`; anything
  but `:permit` is a `:conflict` naming the decision (`screening-required`
  for no-decision or list-retired, `screening-hit` otherwise). The SQL lives
  in `clofin.payments.repository` (it is the lifecycle's precondition, and a
  repository may run SQL); the judgement is screening's pure function. The
  `TODO(increment-7)` comment is replaced by one naming this brief and C-07.
- **A-9 — Cases.** `GET /screening-cases?organisationId=&status=`
  (`listScreeningCases`, `:screening/read`, capped at `row-cap` with
  `truncated`), `GET /screening-cases/{id}` (`getScreeningCase`),
  `POST /screening-cases/{id}/disposition` (`dispositionScreeningCase`,
  `Idempotency-Key`, `:screening/disposition`, `200` `ScreeningCase`). A
  disposition carries `disposition` ∈ {`false-positive`, `confirmed-hit`} and
  `rationale` (1–1000 characters, non-blank); the actor must not be the
  instruction's creator (`403`, `errors.reason: self-disposition` — C-01's
  shape: the maker never clears their own hit); a dispositioned case answers
  `409` to a second disposition (the trigger is the second enforcement);
  audit `screening-case.dispositioned` with before and after digests. A
  disposition is bound to the digest and list version the case names: an
  amendment or a new list makes it moot, and the next submit opens a new case
  if the hit stands. `GET /payment-instructions/{id}/screening-results`
  (`listScreeningResults`, `:screening/read`) and `GET /screening-lists`,
  `GET /screening-lists/{version}` (`listScreeningLists`, `getScreeningList`,
  `:screening/read`; the full list with entries and rules on the second).
- **A-10 — The role and permissions.** `clofin.authz.model`: permissions
  `:screening/record`, `:screening/read`, `:screening/disposition`; role
  `:screening-service` holding `#{:screening/record :screening/read
  :payment/read :organisation/read}`; `:compliance` gains `:screening/read`
  and `:screening/disposition`; `:approver` and `:auditor` gain
  `:screening/read` (the queue, and the trail). `:operator` gains nothing: the
  `409` on submit tells the maker there is a case and its id, and reading the
  list is not a maker's business. Model tests: **no role holds
  `:screening/disposition` with `:payment/create`, `:payment/submit` or
  `:payment/approve`** (beside the existing separation assertions);
  `screening-service` holds no write but `:screening/record`; every new
  permission reachable; roles ↔ `role_known`.
- **A-11 — The queue.** `ApprovalQueueRow` gains an optional `screening`
  member `{resultId, outcome, listVersion, recordedAt}` — the latest accepted
  core result for the instruction's current digest — rendered by
  `clofin.api.wire/approval-queue-row->wire` from a value the handler reads
  in the same request. Optional because an instruction submitted before this
  migration carries none; every instruction submitted after it carries one,
  and a test asserts that.
- **A-12 — Audit vocabulary.** Actions `screening-result.recorded`,
  `screening-case.opened`, `screening-case.dispositioned`; subject types
  `screening-result`, `screening-case`; projections
  `clofin.audit/screening-result-subject` (id, organisation, instruction,
  list version, origin, outcome, core outcome, agrees, digest, disposition,
  reason, the two entry-id vectors) and `screening-case-subject` (id,
  organisation, instruction, result, digest, list version, status,
  disposition, rationale, dispositioned-by); the evidence pack serves both
  (`getEvidencePack`'s "nine kinds" becomes eleven in prose and in both enum
  copies); `clofin.api.audit-coverage-test` asserts one event per write for
  each new write, and that a refused submission leaves none.
- **A-13 — The contract and the documents.** `api/openapi.yaml`: the seven
  operations, the schemas ADR-0028 publishes (`ScreeningRule`,
  `ScreeningEntry`, `ScreeningList`, `ScreeningResultRequest`,
  `ScreeningResult`) plus `ScreeningResultList`, `ScreeningListSummary`,
  `ScreeningCase`, `ScreeningCaseList`, `ScreeningDispositionRequest`,
  `ScreeningRefusalReason` (every `errors.reason` above, one enum) and
  `screeningDigest` on `PaymentInstruction`; `IdempotencyKey`'s description
  "six" → "eight". `docs/COMPLIANCE.md` C-07 → ✅ with enforcement points
  (`transition!`'s gate, `screening_case_open_key`, the append-only
  triggers, `clofin.screening.service`, the named tests) and evidence (the
  three reads, the evidence pack, the list tables), **and the scope
  sentence**: synthetic list, exact matching, no claim of screening quality.
  `docs/DOMAIN_MODEL.md` §2.5 ScreeningResult and Case ✅, §3 rule 1 ✅ naming
  the gate, §1's `screening-outcome` row → "derived on the queue row, not
  stored on the approval". `docs/PRD.md` Q2 → resolved: screening runs at
  **every** submission; an amendment returns the instruction to `draft`, so
  the next submission re-screens; a disposition is bound to the digest it
  was made on (ADR-0028 D5; this brief). `ARCHITECTURE.md` §3 row and
  dependency paragraph. `README.md` line 64 ("Sanctions screening hooks …")
  stays true. ADR-0028 gains **Amendment 1 (TASK-017)**: `screeningDigest`
  on `PaymentInstruction`, `caseId` on `ScreeningResult`, and the refusal
  reasons this brief adds beyond D5's table — dated, appended, nothing
  rewritten. `make diagrams` regenerates `control-map.md` and
  `context-topology.md`.
- **A-14 — `docs/uat/UAT-008-screening-and-cases.md`**, in the register of
  UAT-005: load the shipped list; raise an instruction that hits `SYN-0001`
  and watch `submit` answer `409 screening-hit` with a case; read the case;
  try to disposition it as the maker (`403`); disposition it as compliance
  (`false-positive`, rationale); submit again (`pending-approval`); read the
  queue row's `screening`; amend the instruction (back to `draft`) and
  submit: a fresh decision; record a client result that core cannot
  reproduce (`422`, and the refused row in the results list); record one it
  can (`201`, status unchanged — **read it back and say so**); retire the list
  and submit (`422 no-screening-list-accepted`); the trail. Every step
  traced to PR-060/061/063 or PR-015; **run against a fresh stack before the
  REQ is filed** (L-20). `docs/uat/README.md` row added.

### Out — and why

- **Fraud scoring (PR-062, Should).** A rule set with velocity and pattern
  features is a second engine with its own versioning; a later brief under
  this increment.
- **Fuzzy or normalised matching.** `exact` only. A matcher that folds case
  or transliterates is a different operator with a different false-positive
  profile, and this brief builds the control's shape, not its quality.
- **Lists loaded through the API.** A client that could load the list it is
  screened against would make C-07 unenforceable. Tool only.
- **Re-screening on approval, release or settlement.** The gate is at
  `submit` (C-07's design); later stages rely on the retained decision, and
  an amendment forces a new one.
- **Organisation-scoped lists.** One synthetic list for every tenant;
  per-tenant lists are a product decision nobody has asked for.
- **Chain confirmations** (TASK-019), `clofin-trace`, `clofin-cockpit`, the
  fc satellite's adapter.

## Interfaces

**A-1** — the migration is reproduced in full under *SQL pre-flight*.

**A-2 / A-3 / A-4** — signatures as given in scope. All three namespaces and
`clofin.screening.list` join `pure-namespaces`.

**A-6 / A-7 / A-9** — `clofin.screening.service` (joins `service-namespaces`
and the unit-of-work matrix with all three entry points):

```clojure
(defn record-result!
  "Returns {:result … :case … :refused? bool :detail …}; never throws for a
   mismatch — the handler renders 422 after commit (L-11)."
  [tx {:keys [organisation-id instruction-id actor list-version outcome
              matched-entry-ids instruction-digest screened-at correlation-id]}])
(defn submit-screened!
  "Returns {:instruction … :result …} on permit, or
   {:refused? true :reason :screening-hit :case … :disposition …}."
  [tx {:keys [organisation-id instruction-id actor correlation-id]}])
(defn disposition!
  [tx {:keys [organisation-id case-id actor disposition rationale correlation-id]}])
```

Wire shapes (the ADR's fragments, plus this brief's additions):

```yaml
ScreeningResult:
  required: [id, organisationId, instructionId, listVersion, origin, outcome, matchedEntries,
             coreOutcome, coreMatchedEntries, agrees, instructionDigest, disposition,
             recordedBy, recordedAt, auditEventId]
  properties:
    origin:         { type: string, enum: [core, client] }
    disposition:    { type: string, enum: [accepted, refused] }
    dispositionReason: { type: string, enum: [screening-result-mismatch] }
    matchedEntries: { type: array, items: { type: string } }   # entry ids
    coreMatchedEntries: { type: array, items: { type: string } }
    caseId:         { type: string, format: uuid }             # present when a case was opened by, or is open for, this hit
    screenedAt:     { type: string, format: date-time }
ScreeningCase:
  required: [id, organisationId, instructionId, resultId, instructionDigest, listVersion,
             status, openedAt, permittedTransitions]
  properties:
    status:        { type: string, enum: [open, dispositioned] }
    disposition:   { type: string, enum: [false-positive, confirmed-hit] }
    rationale:     { type: string, maxLength: 1000 }
    dispositionedBy: { type: string, format: uuid }
    dispositionedAt: { type: string, format: date-time }
    permittedTransitions: { type: array, items: { type: string, enum: [disposition] } }
ScreeningDispositionRequest:
  required: [disposition, rationale]
  additionalProperties: false
  properties:
    organisationId: { type: string, format: uuid }
    disposition:    { type: string, enum: [false-positive, confirmed-hit] }
    rationale:      { type: string, minLength: 1, maxLength: 1000 }
ScreeningRefusalReason:
  type: string
  enum: [list-version-not-accepted, outcome-entries-inconsistent, matched-entries-unknown,
         instruction-digest-mismatch, screening-result-mismatch, screening-hit,
         screening-required, no-screening-list-accepted, self-disposition]
```

`ScreeningResultRequest.matchedEntries` is the ADR's array of
`ScreeningEntry` (id and rules) — core compares the rules to the list's and
refuses `matched-entries-unknown` on a difference, so a client cannot claim a
match on rules the list does not have. The approval queue's member:

```yaml
screening:
  required: [resultId, outcome, listVersion, recordedAt]
```

Problem documents carry `errors.reason` from `ScreeningRefusalReason`; the
`409` on submit adds `caseId`, `listVersion` and, when dispositioned,
`disposition`; the `422 screening-result-mismatch` adds `coreOutcome` and
`coreMatchedEntries`; the lifecycle `409` on recording adds
`instruction-status` in the payments API's existing shape.

## Dependency matrix

| Item | Transition or state it depends on | Interface or contract | DDL | DoD clause |
|---|---|---|---|---|
| A-1 | none | `index.txt`; `role_known` recreated by name | the migration | AC-17-0 |
| A-2 | `creditor-country` on the instruction (TASK-018) | `ScreeningRule.field` enum | `screening_rule_field_known` | AC-17-8 |
| A-3 | the instruction's content fields | `screeningDigest`; `ScreeningResultRequest.instructionDigest` | `screening_result_digest_shape` | AC-17-4 |
| A-4 | a core result; a case; a list's retirement | — (pure) | — | AC-17-6, AC-17-11 |
| A-5 | one accepted list at a time | the EDN shape; `make load-screening-list` | lists, entries, rules; `screening_list_retire_only` | AC-17-8, AC-17-12 |
| A-6 | `draft`; the row lock; the accepted list | `ScreeningResultRequest/Result`; `:screening/record` | results, matches, `screening_case_open_key` | AC-17-3, AC-17-4, AC-17-5, AC-17-6, AC-17-7 |
| A-7 | `draft → pending-approval`; A-4; A-8 | `submitPaymentInstruction`'s new `409`/`422` | results, cases | AC-17-1, AC-17-2 |
| A-8 | A-3, A-4 | `transition!`'s `:conflict` vocabulary | results, cases, lists (read) | AC-17-11 |
| A-9 | an open case; C-01's self-check | the case operations; `:screening/disposition` | `screening_case_disposition_final` | AC-17-9 |
| A-10 | — | `clofin.authz.model` | `role_known` | AC-17-12 |
| A-11 | `pending-approval` with a core result | `ApprovalQueueRow.screening` | — | AC-17-10 |
| A-12 | every write above | `AuditSubjectType` (two copies) | — | AC-17-13 |
| A-13 | all of the above | contract and conformance tests; diagrams | — | AC-17-14 |
| A-14 | a fresh stack with the list loaded | the public API | — | AC-17-15 |

Every cell is filled. A cell that turns out wrong is an objection.

## Acceptance criteria

Each names its negative control (L-17). The `ac-17-1` … `ac-17-7` names are
ADR-0028's Verification identifiers; keep them verbatim.

- **AC-17-0 (A-1).** `0015` applies on `0014`; the pre-flight's twelve
  refusals are re-run through `clofin.screening.repository-test` on the
  migrated test database, application bypassed; `clofin.db.vocabulary-test`
  passes with eight new owners (negative control: remove one owner and
  `a-014-every-schema-vocabulary-has-an-owner-in-code` fails naming the
  constraint).
- **AC-17-1 (A-7)** `clofin.api.screening-api-test/ac-17-1-submit-answers-409-screening-hit-and-emits-no-payment-submitted-event`:
  an instruction naming `Blocked Counterparty Ltd` submitted → `409`,
  `errors.reason: screening-hit`, `errors.caseId`; status still `draft`; the
  trail holds `screening-result.recorded` and `screening-case.opened` and no
  `payment.submitted`. Negative control: the same instruction with a name the
  list does not hold → `200`, `pending-approval`, one core result `clear`.
- **AC-17-2 (A-7)** `…/ac-17-2-a-hit-opens-a-case-in-the-same-transaction`:
  the case's `openedAt` and the result's `recordedAt` are in one
  transaction (assert via the audit events' correlation id, or by breaking
  the handler after the result write in a test double and seeing neither
  row). A second submit while the case is open opens no second case
  (`screening_case_open_key`) and answers `409` naming the same `caseId`.
- **AC-17-3 (A-6)** `…/ac-17-3-an-unaccepted-list-version-is-422`: a retired
  version and an unknown version are both `422 list-version-not-accepted`;
  nothing stored (results count unchanged).
- **AC-17-4 (A-6)** `…/ac-17-4-an-instruction-digest-mismatch-is-422-and-changes-nothing`:
  a digest one character off → `422 instruction-digest-mismatch`; no result
  row, no case, no event. Negative control: the digest read from
  `screeningDigest` on the resource → accepted.
- **AC-17-5 (A-6)** `…/ac-17-5-a-result-core-cannot-reproduce-is-422-screening-result-mismatch-and-is-recorded-as-refused`:
  a `clear` for an instruction core finds a hit → `422` with
  `coreOutcome: hit` and `coreMatchedEntries`; the results list shows the row
  `disposition: refused`, `agrees: false`; an audit event exists; status
  `draft`. Negative control: render the `422` *inside* the transaction (a
  test double of the handler) and the row vanishes — the L-11 shape, shown
  once.
- **AC-17-6 (A-6)** `…/ac-17-6-a-201-screening-result-leaves-status-draft-and-emits-no-transition-event`:
  an accepted `clear` and an accepted `hit`: both `201`, both leave `draft`,
  neither emits any `payment.*` event; the `hit` opens a case and the `201`
  carries `caseId`; `permittedTransitions` on the instruction unchanged.
- **AC-17-7 (A-6, A-7)** `clofin.screening.concurrency-test/ac-17-7-amend-and-screening-result-serialise-on-the-instruction-row`:
  two connections, one latch — an amendment that changes the creditor name
  and a client result for the pre-amendment digest, in flight together;
  whichever commits second sees the first: either the result is refused
  `instruction-digest-mismatch`, or it is accepted against the digest the
  row held under the lock and the instruction's digest has since moved.
  **Never** an accepted result whose digest the instruction never held.
  Negative control: remove `for update` from the recording path and the
  test observes the forbidden state (shown once, with the diff).
- **AC-17-8 (A-2, A-5)** `clofin.screening.rules-test` matrix and property;
  `clofin.tools.screening-list-test/a-list-with-an-originator-rule-is-refused-at-load`
  naming `unsupported-rule-field`, plus the other refusals in A-5; loading a
  second version while one is accepted refused without `--replacing`; with
  it, the old is retired and the new accepted in one transaction (break the
  tool after the retire in a test double: neither change survives).
- **AC-17-9 (A-9)** `clofin.api.screening-api-test/ac-17-9-a-disposition-is-never-the-makers-and-is-final`:
  the maker → `403 self-disposition`; compliance → `200`,
  `screening-case.dispositioned`; a second disposition → `409`; the raw
  `UPDATE` of a dispositioned case refused by the trigger (repository test);
  `false-positive` then submit → `pending-approval`; `confirmed-hit` then
  submit → `409` with `disposition: confirmed-hit`, and `cancel` works.
- **AC-17-10 (A-11)** `clofin.api.approvals-api-test/ac-17-10-the-queue-carries-the-screening-outcome`:
  every row for an instruction submitted after `0015` has `screening` with
  `outcome: clear` and the accepted list version; the conformance walk sees
  the member.
- **AC-17-11 (A-8)** `clofin.payments.repository-test/ac-17-11-submit-without-a-screening-decision-is-refused-by-the-repository-itself`:
  `transition!` with `:submit` on a draft that has no core result →
  `:conflict screening-required`; with a `clear` result for the current
  digest → permitted; with a `clear` result for a **stale** digest (amend
  first) → refused; with the result's list retired → refused
  `screening-required`. Negative control: comment out the gate and the first
  case passes — the whole finding, shown once.
- **AC-17-12 (A-10)** `clofin.authz.model-test`: the new separation
  assertion; `every-role-in-the-model-is-a-role-the-migration-text-mentions`
  and the vocabulary test green with `screening-service`; the screening-service
  actor cannot submit, approve, create or disposition (api test, four
  `403`s).
- **AC-17-13 (A-12)** `clofin.api.audit-coverage-test`: one event per write
  for record, open, disposition; none for a refused submission; the evidence
  pack for a case lists `opened` then `dispositioned`; `clofin.contract-test/the-evidence-pack-description-names-every-subject-type`
  green with eleven.
- **AC-17-14 (A-13)** `clofin.contract-test` and `clofin.api.conformance-test`
  green with every new operation walked to its `2xx` and to at least one
  modelled refusal; `ac-15-…-exactly-the-six-…` renamed to eight and green;
  `make diagrams-check` green after `make diagrams`; `make doc-consistency`
  green (C-07's status appears in COMPLIANCE and the control map — not in the
  ROADMAP, which Master Control updates on `meta`).
- **AC-17-15 (A-14)** UAT-008 executed against a fresh stack, every step
  recorded in the REQ.

## SQL pre-flight

Executed by Master Control on 2026-10-03 against a local PostgreSQL 16 at
schema `0013` holding a UAT-007 run (the capture database), in a transaction
rolled back at the end, with one row of every documented shape inserted and
twelve negative controls each in its own savepoint (L-3, widened). The DDL
below is the migration, verbatim but for comments:

```sql
alter table actor_role drop constraint role_known;
alter table actor_role add constraint role_known
  check (role in ('operator','approver','controller','compliance','auditor','screening-service'));

create table screening_list (
  version     text        primary key,
  source      text        not null,
  loaded_at   timestamptz not null default now(),
  retired_at  timestamptz null,
  entry_count integer     not null,
  constraint screening_list_version_shape      check (version ~ '^[\x21-\x7E]{1,128}$'),
  constraint screening_list_source_present     check (length(btrim(source)) > 0),
  constraint screening_list_entry_count_nonneg check (entry_count >= 0)
);

create table screening_entry (
  list_version text not null references screening_list (version),
  id           text not null,
  primary key (list_version, id),
  constraint screening_entry_id_shape check (id ~ '^[\x21-\x7E]{1,128}$')
);

create table screening_rule (
  list_version text    not null,
  entry_id     text    not null,
  position     integer not null,
  field        text    not null,
  operator     text    not null,
  value        text    not null,
  primary key (list_version, entry_id, position),
  foreign key (list_version, entry_id) references screening_entry (list_version, id),
  constraint screening_rule_field_known     check (field in ('creditor-name','creditor-account','creditor-country')),
  constraint screening_rule_operator_known  check (operator in ('exact')),
  constraint screening_rule_value_present   check (length(btrim(value)) > 0),
  constraint screening_rule_position_nonneg check (position >= 0)
);

create table screening_result (
  id                 uuid        primary key,
  organisation_id    uuid        not null references organisation (id),
  instruction_id     uuid        not null references payment_instruction (id),
  list_version       text        not null references screening_list (version),
  origin             text        not null,
  outcome            text        not null,
  core_outcome       text        not null,
  agrees             boolean     not null,
  instruction_digest text        not null,
  disposition        text        not null,
  disposition_reason text        null,
  recorded_by        uuid        not null references actor (id),
  recorded_at        timestamptz not null default now(),
  screened_at        timestamptz null,
  constraint screening_result_origin_known        check (origin in ('core','client')),
  constraint screening_result_outcome_known       check (outcome in ('clear','hit')),
  constraint screening_result_core_outcome_known  check (core_outcome in ('clear','hit')),
  constraint screening_result_disposition_known   check (disposition in ('accepted','refused')),
  constraint screening_result_refusal_reason_known
    check (disposition_reason is null or disposition_reason in ('screening-result-mismatch')),
  constraint screening_result_refusal_needs_reason
    check ((disposition = 'refused') = (disposition_reason is not null)),
  constraint screening_result_digest_shape        check (instruction_digest ~ '^[0-9a-f]{64}$'),
  constraint screening_result_core_agrees_with_itself
    check (origin <> 'core' or (outcome = core_outcome and agrees and disposition = 'accepted')),
  constraint screening_result_agreement_is_derived
    check (agrees = (outcome = core_outcome) or origin = 'client')
);
create index screening_result_instruction_idx on screening_result (instruction_id, recorded_at desc);

create table screening_result_match (
  result_id    uuid not null references screening_result (id),
  side         text not null,
  list_version text not null,
  entry_id     text not null,
  primary key (result_id, side, entry_id),
  foreign key (list_version, entry_id) references screening_entry (list_version, id),
  constraint screening_result_match_side_known check (side in ('submitted','core'))
);

create table screening_case (
  id                 uuid        primary key,
  organisation_id    uuid        not null references organisation (id),
  instruction_id     uuid        not null references payment_instruction (id),
  result_id          uuid        not null references screening_result (id),
  instruction_digest text        not null,
  list_version       text        not null references screening_list (version),
  status             text        not null default 'open',
  disposition        text        null,
  rationale          text        null,
  dispositioned_by   uuid        null references actor (id),
  opened_at          timestamptz not null default now(),
  dispositioned_at   timestamptz null,
  constraint screening_case_status_known      check (status in ('open','dispositioned')),
  constraint screening_case_disposition_known check (disposition is null or disposition in ('false-positive','confirmed-hit')),
  constraint screening_case_digest_shape      check (instruction_digest ~ '^[0-9a-f]{64}$'),
  constraint screening_case_disposition_complete check (
       (status = 'open' and disposition is null and rationale is null
        and dispositioned_by is null and dispositioned_at is null)
    or (status = 'dispositioned' and disposition is not null
        and length(btrim(coalesce(rationale,''))) > 0
        and dispositioned_by is not null and dispositioned_at is not null))
);
create unique index screening_case_open_key on screening_case (instruction_id) where status = 'open';

create function reject_unless_retiring_list() returns trigger as $$
begin
  if old.retired_at is null and new.retired_at is not null
     and new.version = old.version and new.source = old.source
     and new.loaded_at = old.loaded_at and new.entry_count = old.entry_count then
    return new;
  end if;
  raise exception
    'screening_list %: a list version is immutable once loaded; the only change it admits is retirement, once',
    old.version using errcode = 'integrity_constraint_violation';
end;
$$ language plpgsql;
create trigger screening_list_retire_only before update on screening_list
  for each row execute function reject_unless_retiring_list();
create trigger screening_list_append_only before delete on screening_list
  for each row execute function reject_mutation();
create trigger screening_list_no_truncate before truncate on screening_list
  for each statement execute function reject_mutation();
create trigger screening_entry_append_only before update or delete on screening_entry
  for each row execute function reject_mutation();
create trigger screening_entry_no_truncate before truncate on screening_entry
  for each statement execute function reject_mutation();
create trigger screening_rule_append_only before update or delete on screening_rule
  for each row execute function reject_mutation();
create trigger screening_rule_no_truncate before truncate on screening_rule
  for each statement execute function reject_mutation();
create trigger screening_result_append_only before update or delete on screening_result
  for each row execute function reject_mutation();
create trigger screening_result_no_truncate before truncate on screening_result
  for each statement execute function reject_mutation();
create trigger screening_result_match_append_only before update or delete on screening_result_match
  for each row execute function reject_mutation();
create trigger screening_result_match_no_truncate before truncate on screening_result_match
  for each statement execute function reject_mutation();
create function reject_redisposition() returns trigger as $$
begin
  raise exception
    'screening_case % is dispositioned and a disposition is final: a second one is a new case',
    old.id using errcode = 'integrity_constraint_violation';
end;
$$ language plpgsql;
create trigger screening_case_disposition_final before update on screening_case
  for each row when (old.status = 'dispositioned') execute function reject_redisposition();
create trigger screening_case_append_only before delete on screening_case
  for each row execute function reject_mutation();
create trigger screening_case_no_truncate before truncate on screening_case
  for each statement execute function reject_mutation();
```

Rows inserted: one list (`synthetic-2026-10-v1`), two entries, three rules
(one entry with two), one `screening-service` role grant, a core `clear`
result, a client `hit` core agrees with (two match rows, `submitted` and
`core`), a client `clear` core refused (`refused`, `screening-result-mismatch`,
`agrees false`), a case on the hit opened then dispositioned `false-positive`
with a rationale, and the list retired once. Every insert `INSERT 0 n`; the
disposition `UPDATE 1`; `select status, disposition from screening_case` →
`dispositioned | false-positive`; the retirement `UPDATE 1`. The catalogue
query counted **8** vocabulary constraints on the new tables (the owners
`vocabulary-test` must gain). Negative controls, each refused:

```
-- an originator-* rule field
ERROR:  new row for relation "screening_rule" violates check constraint "screening_rule_field_known"
-- a second open case on one instruction
ERROR:  duplicate key value violates unique constraint "screening_case_open_key"
-- re-dispositioning
ERROR:  screening_case 0000cccc-… is dispositioned and a disposition is final: a second one is a new case
-- a disposition without a rationale
ERROR:  new row for relation "screening_case" violates check constraint "screening_case_disposition_complete"
-- rewriting a result
ERROR:  Table screening_result is append-only: … never by update
-- a core result that disagrees with itself (outcome clear, core_outcome hit)
ERROR:  new row for relation "screening_result" violates check constraint "screening_result_core_agrees_with_itself"
-- a second retirement, and an edit to a retired list's entry_count
ERROR:  screening_list synthetic-2026-10-v1: a list version is immutable once loaded; the only change it admits is retirement, once
ERROR:  screening_list synthetic-2026-10-v1: a list version is immutable once loaded; …
-- deleting an entry; truncating results
ERROR:  Table screening_entry is append-only: … never by delete
ERROR:  Table screening_result is append-only: … never by truncate
-- a role the constraint does not know
ERROR:  new row for relation "actor_role" violates check constraint "role_known"
-- a refused result with no reason
ERROR:  new row for relation "screening_result" violates check constraint "screening_result_refusal_needs_reason"
```

The script is [`preflight/0015-task-017.sql`](preflight/0015-task-017.sql);
the REQ re-runs every refusal through `clofin.screening.repository-test`.

## Definition of done

- [ ] Every AC above has a named test or recorded run; every negative control
      seen failing and quoted in the REQ
- [ ] `make verify` and `make test-it` green with counts; `make diagrams`
      run and `make diagrams-check` green; `make doc-consistency` and
      `make docs-check` green
- [ ] The shipped list loads with `make load-screening-list`; the smoke
      stack (`make smoke`) still passes without a list loaded (it checks
      health only — say so)
- [ ] `api/openapi.yaml`: seven operations, the schemas named, the enum
      copies, "eight" where "six" was
- [ ] `docs/COMPLIANCE.md` C-07 ✅ with the scope sentence; `docs/DOMAIN_MODEL.md`,
      `docs/PRD.md` Q2, `ARCHITECTURE.md` §3 updated as A-13 says; ADR-0028
      Amendment 1 appended, dated
- [ ] UAT-008 written, listed in `docs/uat/README.md`, and executed against a
      fresh stack with every step recorded in the REQ
- [ ] `017-REQ-screening-and-cases.md` on the PR branch: provenance header
      (model may be a deliberate, stated absence; effort and date recorded);
      the finding-by-criterion table; the matrix of refusal order for
      recording (which check answers first and why); objections; the L-9
      statement in plain words
- [ ] One PR, `TASK-017: …`, against `main` after TASK-018; merged by Master
      Control after the objections are ruled and CI is green on the branch

## Notes for whoever picks this up

- **Evidence is not a transition.** The temptation is to let an accepted
  client `clear` satisfy the gate. It does not: `decision/decide` reads
  **core's** latest result only. A client result is a row the trail keeps
  and the submit path ignores.
- **Render refusals after commit.** `screening-result-mismatch` and
  `screening-hit` are values the service returns; the handler commits, then
  throws. Throwing inside the transaction destroys the receipt — F-008's
  shape, L-11.
- **Read under the lock.** The digest the recording path compares, the list
  it reads and the decision the gate makes are all taken after
  `lock-instruction!`. A digest computed before the lock is a digest of a row
  another transaction may be amending (L-8).
- **Two enforcement points, deliberately.** The service screens; the
  repository's gate re-decides. If you find yourself making the gate trust a
  flag the service set, you have made it documentation.
- **One accepted list.** No list → no submit, `422`. Do not default to an
  empty list: "nothing to screen against" would make every instruction
  clear, which is the control's absence wearing its name.
- **Do not seed the list from a migration.** A migration is immutable
  history; a list is versioned reference data that is loaded, retired and
  replaced. The tool is the seam.
- **Names you must not use in the shipped list:** `Pacific Rim Logistics
  Pte Ltd` and the `SG-SYNTH-…` accounts the UAT scripts and tests use; a
  list that hits them turns every existing script into a screening hit.
- **L-16 copies:** "six" in the contract's `IdempotencyKey` description and
  in `ac-15`'s name; "nine kinds" in `getEvidencePack`; the two
  `AuditSubjectType` enum copies; C-07's status in COMPLIANCE and the control
  map; `ARCHITECTURE.md`'s context table and dependency paragraph; the
  README's increment line. Grep for each before you call the docs done.
- **The ROADMAP is not yours.** Increment 7's heading and the global-state
  row are updated by Master Control on `meta` when this brief closes;
  `check-doc-consistency.sh` rule 2 tolerates a brief `IN PROGRESS` beside a
  💭 heading on `main`'s snapshot.
- **L-9.** If a self-review is still running when you write the REQ, say so
  in the REQ and do not call the work complete until it is not.
