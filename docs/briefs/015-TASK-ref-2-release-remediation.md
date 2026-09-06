# TASK-015: `ref-2` release remediation — the audit's findings, actioned in the subject

| Field | Value |
|---|---|
| **Increment** | ref-2 (release remediation; the tag waits on this brief) |
| **Status** | `IN PROGRESS` — dispatched 2026-09-06 |
| **Depends on** | `FEEDBACK-REL-ref-2` ingested ✅ (`docs/audits/FEEDBACK-REL-ref-2.md` on `origin/meta`, in the commit that carries this brief) |
| **Blocks** | the `ref-2` tag; the `clofin-trace` refresh at `ref-2`; increment 7 |
| **Requirements** | PR-040 (its scope stated honestly), PR-050…054; ADR-0020 rules 2–3, ADR-0022, ADR-0023, ADR-0027 |
| **Controls touched** | C-05, C-06, C-13 — **statements narrowed or made true, no control weakened**; the capture harness (ADR-0022) strengthened |
| **Scope** | Large — one PR, committed in the groups below |
| **Base branch** | `main` at or after the sync that carries this brief (`chore/meta-sync-2026-09-06`). The RC `c97a4f2` differs from that tip by documents only |
| **Audit** | `docs/audits/015-REQ-ref-2-release-remediation.md`, task-keyed, filed on the PR branch |

## Objective

After this brief, `main` carries no finding of `FEEDBACK-REL-ref-2` that Master
Control dispositioned as *actioned in the subject*: the two blocking findings
are closed with regression tests that **fail on the RC and pass on the branch**;
seventeen should-fix findings, one consider and one unnumbered residual are
closed the same way; every guard this brief adds or changes carries a negative
control that is shown failing; and the resulting commit is the remediation
descendant `ref-2` is tagged on. Nothing here changes what CloFin claims to be:
a synthetic-data reference implementation, connected to nothing, approved by no
one, processing no real funds.

## Context you need

- **The audit.** `git show origin/meta:docs/audits/FEEDBACK-REL-ref-2.md` —
  read it in full first. Every finding below is cited by its id there, with the
  auditor's own quotes at the RC, its reproduction and its suggested direction.
  This brief turns those directions into interfaces; where the two differ, this
  brief governs and says why. The auditor's workpapers and probe scripts are in
  the operator's bridge, not the repository; what you need from them is
  restated here.
- **Dispositions and rulings** are in the register's decision block of
  2026-09-06 (`git show origin/meta:docs/audits/README.md`). Findings actioned
  on `meta` (2B-001, 2B-002, 2B-006, 2B-011, 2C-008) and the one deferred
  (2B-008, the `ref-1` release body) are **not yours**; do not touch the
  control-plane documents they live in.
- **Lessons L-17…L-21** were adopted from this audit; read them in the
  register's *Standing lessons* table. They shape every acceptance criterion
  below: enumerate a guard's dimensions, assert non-vacuity, show a mutation
  failing (L-17); a collision loser re-enters the serial decision (L-18); a
  capture binds to the process it started (L-19); acceptance scripts are
  executable contracts (L-20); audit coverage follows every producer (L-21).
- **Namespaces you will touch:** `clofin.recon.service`,
  `clofin.recon.repository`, `clofin.api.reconciliation`, `clofin.routes`,
  `clofin.audit.repository`, `clofin.settlement.service`,
  `clofin.ledger.service`, `clofin.http.cors`, `clofin.idempotency`,
  `clofin.config`, `clofin.api.health`, `clofin.api.settlement`,
  `clofin.tools.capture.stack`, `clofin.tools.capture.bundle`,
  `clofin.tools.capture.quotations`; `api/openapi.yaml`; `docs/COMPLIANCE.md`;
  `docs/ADR/0022`, `docs/ADR/0027` (dated amendments only); `docs/uat/UAT-006`,
  `docs/uat/UAT-007`; `docs/releases/README.md`; `Makefile`; `scripts/`;
  `resources/`.
- **What is off limits:** `docs/ROADMAP.md`, `docs/briefs/**`,
  `docs/audits/README.md`, `docs/audits/RELEASE-AUDIT-CHARTER.md`,
  `docs/AGENT_HANDOFF.md` (control plane, written on `meta` only), and
  `docs/releases/ref-1.annotation.txt` (a byte-for-byte mirror of a published
  release body; `make check-release-annotation` fails if it changes). A change
  you believe is needed in any of these is an objection in your REQ.

## Scope

### In

**Group A — the two blockers (each: fix, regression test that fails on the RC, negative control).**

- **A-1 · 2C-002 — concurrent contradictory statements reported as replays.**
  Both receipt-collision branches in `clofin.recon.service` (the ingestion
  function whose docstring lists five ordered steps; at the RC the `refuse!`
  helper's nil-`stored` branch at lines 270–275 and the applied path's
  `if-not stored` at 307–309) re-enter **the same decision the serial path
  makes** (lines 222–236): read the winner via `replay-of`; if
  `statement/same-message?` holds for the two digests, replay; otherwise
  answer exactly as step 1 answers a different document under a taken identity
  — `replay-key-conflict`, rendered `409`, `replayed: false`, no second
  receipt, no lines, no matches, no breaks, no audit event for the loser.
  Factor that decision into one function both the pre-check and both recovery
  branches call, so the three copies cannot drift (L-16, L-18).
- **A-2 · 2C-006 — capture attributes another process's answers to the
  requested commit.** `clofin.tools.capture.stack` binds a capture to **the
  process it started** (L-19): (i) a **pre-flight** before spawning — if
  anything answers `GET /readyz` or `GET /` on the port, refuse with a message
  naming the port and the rule; (ii) a **per-run identity** — a fresh UUID
  passed to the child as `CLOFIN_INSTANCE_ID` (in `env-for`) and echoed by the
  service as `instanceId` on `GET /`; (iii) `start!` checks `(.isAlive p)`
  **before** it accepts any `200`, and after acceptance reads `GET /` and
  refuses unless `instanceId` equals the run's UUID and `sourceCommit` equals
  the commit under capture (pass `CLOFIN_SOURCE_COMMIT` in `env-for` too, from
  the worktree's resolved commit — today the child resolves nothing and reports
  `unknown`); (iv) liveness and identity are re-checked **before every writer
  call** and once more before the manifest; a failed check refuses and writes
  nothing. `assert-schema-matches!` stays, with its docstring corrected: a
  schema version is a check, not an identity.

**Group B — the capture harness's other two findings.**

- **B-1 · 2C-005 — two writers emit unstamped artifacts.** One provenance gate
  (`assert-provenance!` or the existing validation `write!` uses) runs
  **before any file is opened** in all four writers — `write!`,
  `write-fixture!`, `write-quotations!`, `write-manifest!`. ADR-0022's
  sentence "`write!` … is the only path to a bundle on disk" (lines 96–100)
  gains a dated correction: every writer validates the complete stamp before
  it opens a file, and there is no `spit` outside the four.
- **B-2 · 2C-007 — C-13's statement is quoted by its first sentence only.**
  `clofin.tools.capture.quotations` extracts a control's statement from
  `**Statement.**` to the **next bold label at line start** (`**Something.**`)
  or the next heading, including blank lines, numbered items and continuation
  paragraphs — not to the first blank line (`quotations.clj:53`). Every
  control's extracted statement equals the COMPLIANCE text between those
  markers, whitespace-normalised.

**Group C — reconciliation.**

- **C-1 · 2C-001 — nested evidence silently stops at 501 rows.**
  `breaks-for-statement` and `adjustments-for-break` follow the pattern the
  organisation-level `breaks` read already uses (`repository.clj:567–599`):
  fetch `row-cap + 1`, return **at most `row-cap`**, report truncation. The
  statement representation (ingestion response and `GET
  /reconciliation-statements/{id}`) gains `breaksLimit` (integer, `500`) and
  `breaksTruncated` (boolean); the break representation gains
  `adjustmentsLimit` and `adjustmentsTruncated`. Both pairs are `required` in
  their OpenAPI schemas. COMPLIANCE C-13's evidence sentence (lines 872–873)
  is narrowed to say the set is bounded by the row cap and that the response
  says when it was.
- **C-2 · 2C-011 — an adjustment's `Location` is not retrievable.** Add
  `GET /reconciliation-adjustments/{id}` (`getReconciliationAdjustment`,
  permission `:reconciliation/read`), returning the same representation the
  break embeds. Tenant-boundary and malformed-id behaviour: **copy what
  `GET /reconciliation-breaks/{id}` does**, do not invent. The `Location`
  headers on proposal and decision stay as they are — they now resolve.
- **C-3 · 2C-012 (consider) — adjustment evidence omits its approval
  decisions.** `clofin.audit.repository`'s evidence extraction traverses the
  approval relationship for subject type `reconciliation-adjustment` through
  the approval row's adjustment link (the column migration `0012` added — take
  its name from the DDL), as it does for a payment through `instruction_id`.
  Actioned here because the fix shares C-2's files; the register records its
  severity as the auditor gave it.
- **C-4 · 2B-004 — reconciliation locking has no concurrency proof.** Two-
  connection, latch-based tests in the shape of
  `test/clofin/ledger/repository_test.clj:590–652`: (a) assignment versus
  resolution on one break — the second transaction blocks until the first
  commits, exactly one transition wins, the loser is refused with the recorded
  reason, one audit event each; (b) two concurrent decisions on one
  adjustment — exactly one posts or rejects, the other is refused, at most one
  journal entry. Plus A-1's matrix. These are committed regression tests, not
  probes.
- **C-5 · 2C-004 — UAT-007 inherits a threshold that invalidates its
  example.** UAT-007's approval-band step replaces the fixture tenant's SGD
  bands rather than adding one: delete the tenant's SGD bands, insert
  `(0, 0)` and `(100000, 1)`, and state the floor in words; its prerequisite
  row lists every variable and actor alias it reuses from UAT-006 (including
  the auditor C-… below introduces). Run UAT-006 steps 1–7 and UAT-007 against
  a fresh stack before filing the REQ and quote the responses (L-20).

**Group D — the contract.**

- **D-1 · 2C-010 — live responses disagree with their contracts.** Every
  `SettlementBatch` representation carries `simulated: true` — list items
  included. Declare `400` (the validation problem shape) on the five
  operations the audit found emitting it undeclared (`getReconciliationBreak`,
  `getReconciliationStatement`, `getSettlementBatch`, `listSettlementBatches`,
  `submitSettlementBatch`) and `422` on `recordSchemeResponse` for a return
  without a reason. The `Readiness.checks` enum stops declaring the `failed`
  value the code never emits — or the code emits it; state which and why. Then
  the **bounded conformance test** (D-4).
- **D-2 · 2C-003 — the currency-property guard checks nothing.**
  `test/clofin/contract_test.clj` converts the SnakeYAML result to Clojure
  maps and vectors once at load (walk `java.util.Map` / `java.util.List`), so
  `map?` and friends mean what they say. Every predicate over the parsed spec
  in that namespace is audited and listed in the REQ with whether it was
  vacuous at the RC. The currency check asserts **non-vacuity**: the discovered
  population is non-empty **and** equals the population found by an
  independent method (a scan of the raw YAML text for `currency:` under
  `properties:`), both directions (L-16, L-17).
- **D-3 · C-R10a and 010-REQ N-3 — stale contract prose.** `api/openapi.yaml`:
  the `PATCH /payment-instructions/{id}` description (868–871) says
  `organisationId` is accepted as a tenant assertion that must match, and
  names exactly the members whose presence is `422`; the `PaymentEvent`
  description (2859–2862) names which events have an operation today (submit,
  cancel, amend, approve and reject through approvals, the settlement events
  through settlement) and which have none; `getEvidencePack`'s description
  (2292–2300) names all nine subject types, reconciliation's three included.
- **D-4 · bounded contract conformance (closes D-1's class).** A test namespace
  that exercises **every operation in the route table** at least once through
  the public handler — the happy path and every modelled refusal the audit
  found undeclared — and validates each response against the contract on
  three dimensions only: the status is declared for the operation; every
  `required` member of the referenced schema is present; every member whose
  schema is an enum holds a declared value. Nothing deeper: this **narrows**
  the A-011 debt (`docs/COMPLIANCE.md` §4) and the REQ says so; it does not
  close it. Run it on the RC first and quote the failures (`simulated`, the
  five `400`s, the `422`) — that is the negative control.
- **D-5 · 2B-003 — CORS hides the published replay header.**
  `clofin.http.cors/exposed-response-headers` gains `idempotent-replayed`. The
  test stops restating a list: it **discovers** the response headers the
  contract declares (every `responses.*.headers` key across all operations,
  lower-cased) and the headers the response helpers and middleware set
  (`location`, `x-correlation-id`, `allow`, `idempotent-replayed` — enumerate
  them from `clofin.http.response`, the error boundary and the idempotency
  layer), subtracts the CORS-safelisted names, and asserts equality with the
  exposed set in both directions. Negative controls: one name removed from the
  list fails; one undeclared name added fails.
- **D-6 · 2B-009 — the idempotency docstring overstates its coverage.**
  `clofin.idempotency/read-key`'s docstring and its `400` message name the six
  payment and approval operations that require the key and say the other
  eleven mutations do not. Then the guard: a test that calls **every mutating
  route** in the route table with a valid body and no `Idempotency-Key` —
  the six answer the key-required `400`, the eleven do not answer that problem
  — and compares the six with the operations that reference
  `#/components/parameters/IdempotencyKey` in the contract, both directions.
  Every other copy of the "every mutating" sentence is enumerated and each is
  narrowed or left with a reason in the REQ (at the RC: `src/clofin/api/payments.clj:15`,
  `src/clofin/payments/repository.clj:19`, `docs/uat/UAT-005-segregation-of-duties.md:53`;
  `docs/PRD.md:56,122` state the requirement and stay; `docs/COMPLIANCE.md:524`
  already narrows).

**Group E — audit coverage.**

- **E-1 · 2C-009 — settlement's journal entries have no posting event.** Both
  settlement posting sites (`settlement/service.clj:228` release, `:565`
  finality and return) post through `clofin.ledger.service/post-entry!` with
  the acting actor and correlation id, so `journal-entry.posted` is emitted in
  the same transaction. Test: after a public settlement walk that releases and
  settles, and one that releases and returns, **every** journal entry in the
  tenant has exactly one `journal-entry.posted` event and its evidence pack
  answers `200`. Producer census (L-21): a test in the style of
  `clofin.ledger.purity-test` asserts that the only production namespace
  calling `clofin.ledger.repository/post-entry!` is `clofin.ledger.service`.
  COMPLIANCE lines 849–850 ("the same path a release takes") become true and
  say so.
- **E-2 · 2B-005 — C-05 names four audit-composing services; there are five.**
  `docs/COMPLIANCE.md:294–298` names `clofin.recon.service`. Guard: the
  unit-of-work test asserts that every namespace in
  `clofin.ledger.purity-test/service-namespaces` is named in COMPLIANCE's
  C-05 section text and that every `clofin.*.service` name in that section is
  in the set — both directions, an independently moving source (L-16).

**Group F — documents inside the subject.**

- **F-1 · 2B-007 — `make help` omits the real-funds clause.** The canonical
  sentence moves to **one place**, `resources/disclaimer.txt` (one line, no
  trailing newline dependence); `clofin.api.health/info` serves it from the
  resource; `make help` prints it from the same file; a unit test asserts
  `GET /` returns the resource verbatim.
- **F-2 · the mechanical check.** `scripts/check-disclaimer.sh` (POSIX sh,
  same shape as `check-doc-links.sh`) asserts the sentence appears verbatim in
  `make help`'s output and in every `docs/releases/*.annotation.txt` except
  those listed as historical in `docs/releases/README.md` with their reason
  (`ref-1`: published before the rule; its omission recorded, finding 2B-008).
  Wired into `make verify`. Negative control: a temporary annotation file
  without the sentence fails it.
- **F-3 · 2B-008's forward half.** `docs/releases/README.md` states the rule:
  from `ref-2` onward the annotation carries the canonical sentence verbatim,
  and the check above enforces it. The `ref-1` file is not touched.
- **F-4 · 2B-010 — UAT-006 §11 reads evidence as an actor who cannot.** Seed
  `$AUDITOR` (role `auditor`) in UAT-006's seeding step; both Step 11 reads
  use it, with one sentence saying why the controller cannot. Then enumerate
  every `x-actor-id` a UAT sends to an `/audit/` route across **all** UAT
  scripts and confirm each holds `audit/read` (L-16).

**Group G — the document guard.**

- **G-1 · 2B-011 and 2C-008 made mechanical.** `scripts/check-doc-consistency.sh`
  gains rule 5: any `TASK-NNN` named in the ROADMAP's prose or headings on a
  line that also says `in flight`, `currently`, `READY`, `IN PROGRESS` or
  `next` must have that status in `docs/briefs/NNN-*.md` in the same tree.
  Negative control in `clofin.tools.doc-consistency-test`: a fixture tree
  carrying the RC's line 227 ("phase 8.1 in flight as TASK-011") and line 254
  ("currently **TASK-001**") fails rule 5 with both lines named. The live
  ROADMAP is already repaired on `meta`; you test against fixtures, never by
  editing it.

### Out — and why

- **014-REQ O-1** (authenticated organisation lookup by short name),
  **010-REQ N-1 / N-4 / N-6**, and the operational-hardening mechanisms
  (A-007 log sanitiser, A-008 catalogue hashing, A-010 transitive SBOM, A-011
  beyond D-4's bounded form): recorded debt with named targets; a remediation
  batch that grows past its audit stops being one.
- **Pagination** for the nested collections C-1 bounds: deferred debt since
  increment 2 ("a cursor contract designed without a consumer would be
  guesswork"); C-1 makes the bound *visible*, which is the finding.
- **Rewriting `docs/releases/ref-1.annotation.txt` or the `ref-1` release
  body**: a published artifact, immutable by the register's rule; disposition
  *deferred with a target* (F-2/F-3 are the target).
- **ADR-0023's promise** ("a different document under the same reference is
  refused and is never called a replay"): it is correct; A-1 makes the code
  keep it. Do not soften it.
- **`clofin-trace` and `clofin-cockpit`**: not the audit's subject. The
  cockpit's connect step reads `GET /` and ignores members it does not know;
  `instanceId` needs nothing there. The `ref-1` fixtures in `clofin-trace`
  were captured before C-13 existed and are not re-captured by this brief;
  `ref-2`'s capture uses the fixed extractor.
- **A migration.** None is specified and none is expected; see *Migration
  pre-flight*. If one becomes necessary, stop and object.

## Interfaces

**A-1.** In `clofin.recon.service`:

```clojure
(defn- decide-against-existing
  "The one decision for a receipt that already exists under this reference —
   made by the pre-check and by both collision recoveries (L-18)."
  [tx existing content-digest]
  (if (statement/same-message? (:content-digest existing) content-digest)
    (replay tx existing)
    {:statement existing :replayed? false
     :disposition "refused" :disposition-reason "replay-key-conflict"
     :detail (statement/refusal-detail "replay-key-conflict")
     :matches [] :breaks []}))
```

Both `(if stored … (replay tx (replay-of …)))` sites become
`(if stored … (decide-against-existing tx (replay-of tx organisation-id reference) content-digest))`.
The handler already renders `replay-key-conflict` as `409`; no contract change.

**A-2.** `clofin.config` reads `CLOFIN_INSTANCE_ID` into `:instance-id`
(optional, default absent — never a generated default, never an empty
string). `clofin.api.health/info` adds `"instanceId"` **only when present**.
`api/openapi.yaml` `ServiceInfo` gains an optional `instanceId` (string):
"an opaque identifier the operator passed at start-up, echoed verbatim;
self-reported like `sourceCommit`; absent when none was passed". Not in
`required`; the contract test covers presence and absence. ADR-0027 gains a
dated amendment section (no new ADR). In `clofin.tools.capture.stack`:

```clojure
(defn assert-port-free! [port])          ; refuses if /readyz or / answers
(defn start! [{:keys [worktree db port clojure-bin log-file timeout-seconds
                      instance-id source-commit]}])
  ;; env-for gains CLOFIN_INSTANCE_ID and CLOFIN_SOURCE_COMMIT;
  ;; the loop checks (.isAlive p) before it looks at any response;
  ;; after 200: GET / must echo instance-id and source-commit, else refuse.
(defn assert-same-process! [running])    ; isAlive + GET / identity; called
                                         ; by capture.clj before every writer
```

**C-1.** Wire fields on the statement representation: `breaksLimit` (integer)
and `breaksTruncated` (boolean); on the break representation:
`adjustmentsLimit`, `adjustmentsTruncated`. Repository functions return
`{:breaks […] :truncated? bool}` / `{:adjustments […] :truncated? bool}` with
at most `row-cap` elements.

**C-2.** `{:method :get :path "/reconciliation-adjustments/:id"
:operation-id "getReconciliationAdjustment"}` → `200` with the embedded
adjustment schema; other statuses copied from `getReconciliationBreak`.

**D-6.** Docstring and message name the six operations by their
`:operation-id`s from `clofin.routes`.

**F-1.** `resources/disclaimer.txt` holds exactly:
`CloFin operates on synthetic data only. It is not connected to any bank, payment scheme or central bank, holds no regulatory authorisation, and never processes real funds.`

## Dependency matrix

| Item | Transition or state it depends on | Interface or contract | DDL | DoD clause |
|---|---|---|---|---|
| A-1 | receipt uniqueness on `(organisation_id, statement_reference)` — migration `0012` | `POST /reconciliation-statements` `200` / `422` / `409` as today | none — the unique key exists | AC-1, negative control shown |
| A-2 | none (harness process lifecycle) | `GET /` `ServiceInfo` + optional `instanceId`; `CLOFIN_INSTANCE_ID`, `CLOFIN_SOURCE_COMMIT` env | none | AC-2 |
| B-1 | none | four writer signatures unchanged | none | AC-3 |
| B-2 | none | quotation fixture format unchanged (statement body longer) | none | AC-4 |
| C-1 | none | four new `required` wire fields | none — `row-cap` is code | AC-5 |
| C-2 | none | new `GET` operation, `:reconciliation/read` | none | AC-6 |
| C-3 | approval subject kinds — migration `0012`'s adjustment link | evidence-pack schema unchanged (more events) | none | AC-7 |
| C-4 | break and adjustment state tables (`clofin.recon.break-state`, adjustment status) | none | none | AC-8 |
| C-5 | approval bands `(organisation, currency, from_minor)` | UAT text only | none | AC-9 |
| D-1 | none | six response-status declarations, `simulated` on list items, one enum value | none | AC-10 |
| D-2 | none | test only | none | AC-11 |
| D-3 | none | description text only | none | AC-12 |
| D-4 | none | test only — reads the contract | none | AC-13 |
| D-5 | none | `Access-Control-Expose-Headers` value | none | AC-14 |
| D-6 | none | docstring, `400` message text | none | AC-15 |
| E-1 | ledger posting path (`ledger.service/post-entry!` requires a unit of work) | none | none | AC-16 |
| E-2 | none | COMPLIANCE text + test | none | AC-17 |
| F-1…F-3 | none | `GET /` body unchanged in value; `make help`; `verify` | none | AC-18 |
| F-4 | role table (`clofin.authz.model`) | UAT text only | none | AC-19 |
| G-1 | brief status vocabulary | script + fixtures | none | AC-20 |

Every cell is filled; no question is left for you to answer by guessing. If a
cell turns out to be wrong, that is an objection.

## Acceptance criteria

Each is a named test (or script run) that **fails on the RC** where a defect
is being fixed, and each guard names its negative control (L-17).

- **AC-1 (2C-002).** Given two connections and two documents under one
  reference, synchronised so both initial lookups see no receipt, when the
  documents are identical then both answer `200` (applied) or `422` (refused
  path) with exactly one `replayed: true`, one receipt row, one arrival event;
  when the documents differ then the winner answers `replayed: false` and the
  loser answers `409` `replay-key-conflict`, one receipt row, no lines,
  matches, breaks or event from the loser. Four cells: {identical, different}
  × {applied, refused-no-account}. Synchronisation may use `with-redefs` on
  `recon/find-statement-by-reference` with a latch, as the auditor did, or a
  test hook; timing is the only thing controlled. Negative control: the test
  fails on the RC with `replayed: true` on the loser.
- **AC-2 (2C-006).** With a local `HttpServer` answering `200` on `/readyz`
  with schema `0013` and any `GET /`: (a) `assert-port-free!` refuses before
  spawning; (b) `start!` with `clojure-bin` `/bin/false` refuses because the
  child is not alive, whatever the port answers; (c) a responder that echoes
  the wrong `instanceId`, or none, is refused after `200`; (d) a responder
  that echoes the right `instanceId` but a different `sourceCommit` is
  refused; (e) `assert-same-process!` refuses once the child is destroyed.
  Negative control: (b) and (c) fail on the RC's `start!`.
- **AC-3 (2C-005).** A writer × required-field matrix: each of the four
  writers, each required provenance field absent → refuses **and no file
  exists** afterwards. Negative control: `write-quotations!` and
  `write-manifest!` fail this on the RC.
- **AC-4 (2C-007).** For every control the extractor discovers in
  `docs/COMPLIANCE.md`, the extracted statement equals the text between
  `**Statement.**` and the next bold label or heading, whitespace-normalised;
  C-13's contains its seven numbered guarantees. Negative control: the RC's
  extractor fails on C-13.
- **AC-5 (2C-001).** 504 breaks on one statement → the ingestion response and
  `GET` return 500 with `breaksTruncated: true`, `breaksLimit: 500`; 502
  adjustments on one break → 500 with `adjustmentsTruncated: true`; below the
  cap both flags are `false`. The contract test sees the four new required
  members. Negative control: the RC returns 501 and no flag.
- **AC-6 (2C-011).** Following the `Location` of a proposal and of a decision
  answers `200` with the same representation the break embeds; foreign tenant
  and malformed id answer exactly as `getReconciliationBreak` does.
- **AC-7 (2C-012).** The evidence pack of a decided adjustment contains its
  `approval.recorded` event(s); a payment's pack is unchanged.
- **AC-8 (2B-004).** The two latch tests above, plus AC-1, all green; each
  asserts the blocked transaction did not proceed until the first committed.
- **AC-9 (2C-004).** UAT-007's below-threshold example posts immediately
  (`approvalsRequired: 0`, `posted: true`) against a fresh stack after UAT-006
  steps 1–7; the REQ quotes the responses of every UAT-007 step.
- **AC-10 (2C-010).** Every settlement list item carries `simulated: true`;
  the six declarations exist; the readiness enum matches what the code emits.
- **AC-11 (2C-003).** The currency-property population is non-empty and equals
  the raw-text discovery; a test that mutates one `$ref` to a pattern in an
  in-memory copy fails the check. The REQ lists every predicate over the
  parsed spec and its vacuity at the RC.
- **AC-12 (C-R10a, N-3).** A `PATCH` naming a matching `organisationId`
  answers `200`; naming any listed non-amendable member answers `422`; the
  three descriptions read as specified.
- **AC-13 (D-4).** The conformance namespace exercises all 37 operations; on
  the RC it reports the `simulated`, five-`400` and `422` drifts (quoted in
  the REQ); on the branch it is green.
- **AC-14 (2B-003).** The discovered response-header set equals the exposed
  set both ways; a browser-shaped test reads `Idempotent-Replayed` through
  the middleware; the two negative controls fail.
- **AC-15 (2B-009).** The no-key sweep over all 17 mutating routes matches the
  contract's six `IdempotencyKey` references both ways; the docstring names
  the six.
- **AC-16 (2C-009).** After release+settle and release+return walks, every
  journal entry has exactly one `journal-entry.posted` event and a `200`
  evidence pack; the producer census names `clofin.ledger.service` as the
  only production caller of the repository's `post-entry!`. Negative control:
  both fail on the RC.
- **AC-17 (2B-005).** COMPLIANCE's C-05 service list equals
  `service-namespaces` both ways; removing a name from either side fails.
- **AC-18 (2B-007, 2B-008 forward).** `GET /`'s disclaimer equals
  `resources/disclaimer.txt`; `make help` prints it; `scripts/check-disclaimer.sh`
  passes in `verify`, and fails on a temporary annotation without the
  sentence and on the `ref-1` file when it is not on the historical list.
- **AC-19 (2B-010).** UAT-006 Step 11 as written answers `200` with
  `$AUDITOR`; the REQ's table of every UAT `x-actor-id` against `/audit/`
  routes shows each holds `audit/read`.
- **AC-20 (2B-011, 2C-008).** Rule 5 fails the fixture tree naming both RC
  lines and passes the repaired ROADMAP; `make doc-consistency` stays green on
  the branch.

## Migration pre-flight

**No migration.** Every item above is code, tests, contract or prose against
the schema at `0013`. If any item turns out to need DDL, stop and raise it as
an objection in the REQ rather than adding `0014`.

## Definition of done

- [ ] Every AC above has a named test or recorded run; every defect-fixing
      test was seen **failing on the RC** and the REQ quotes that failure
- [ ] `make verify` (now including `check-disclaimer`) and `make test-it`
      green; counts in the REQ
- [ ] `docs/COMPLIANCE.md`: C-05 and C-13 edited as specified, **no control
      weakened**; ADR-0022 and ADR-0027 amended with dated sections; every
      other copy of a changed claim enumerated in the REQ (L-16)
- [ ] The control-plane files listed as off limits are untouched — `git diff
      --stat origin/main -- docs/ROADMAP.md docs/briefs docs/audits/README.md
      docs/AGENT_HANDOFF.md docs/releases/ref-1.annotation.txt` is empty
- [ ] `015-REQ-ref-2-release-remediation.md` filed on the branch with: a
      provenance header (model, effort, date); a finding-by-finding table
      (id → what changed → test → RC failure quoted); objections, if any; the
      L-9 statement in plain words — nothing in flight, or exactly what is
- [ ] PR against `main`, titled `TASK-015: ref-2 release remediation — …`,
      described by group; CI green on all three checks

## Notes for whoever picks this up

- **`replayed: true` is a promise** that CloFin already processed *this*
  document. The collision branch broke it by assuming a matching key meant a
  matching document. The fix is one function, not two patched branches.
- **`(inc row-cap)` fetches the sentinel row; today it is returned.** The
  organisation-level read shows the correct shape; copy it.
- **SnakeYAML returns Java collections.** `get`/`get-in` work on them;
  `map?`/`vector?` do not. That is how a guard over "every schema with a
  currency property" passed while checking none. Convert once, then assert
  the population you found is not empty.
- **The child's `sourceCommit` and `instanceId` are both self-reported.** What
  the harness establishes is that *the process it started* answered — that is
  the identity. Say exactly that in ADR-0027's amendment and in the capture
  docstrings; do not write "attested".
- **The capture tests must not need a running service.** A local
  `com.sun.net.httpserver.HttpServer` on an ephemeral port is enough for AC-2,
  as the auditor's probe showed.
- **`ref-1.annotation.txt` is immutable** and `make check-release-annotation`
  proves it; the disclaimer check lists it as historical with the reason.
  `ref-2`'s annotation file is Master Control's to write at tag time, not
  yours — the check must pass with no `ref-2` file present.
- **Nothing in `docs/ROADMAP.md` is yours**, however wrong a line looks — it
  is repaired on `meta` and synced. Your rule-5 negative control lives in a
  fixture directory.
- **L-9.** If a self-review is still running when you write the REQ, say so
  in the REQ and do not call the work complete until it is not.
- **The scope is large on purpose** — a release remediation lands as one
  descendant. Commit by group (A…G) so a reviewer can read one finding at a
  time, and keep each commit's message naming the finding ids it closes.
