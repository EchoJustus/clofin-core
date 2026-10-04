# TASK-020: UAT-007 corrections, and the five defects the `ref-2` walkthrough found beside its scope

| Field | Value |
|---|---|
| **Increment** | 6 (its acceptance script), 5v (the capture harness) |
| **Status** | `IN PROGRESS` — dispatched 2026-10-04 |
| **Depends on** | nothing — may run in parallel with TASK-018 (the two share `api/openapi.yaml` in different regions and nothing else; whichever lands second rebases) |
| **Blocks** | the next trace capture (at `ref-3`), which replays the corrected script |
| **Requirements** | PR-050…PR-054 (UAT-007's); standing lessons L-17, L-20; the rulings on 016-REQ O-2, O-3, O-4, O-6, O-7, O-9, O-10 and the four findings routed from its §8 |
| **Controls touched** | none. `src/clofin/` changes are one `Location` header (a contract defect, 2C-011's territory) and one query parameter on the simulated-statement generator; neither is an enforcement point |
| **Scope** | Small–Medium — docs, harness, tests, two small handler changes |
| **Base branch** | `clofin-core` `main` at `c4e1689` or later |
| **Audit** | `docs/audits/020-REQ-uat-007-corrections-and-walkthrough-findings.md`, task-keyed, filed on the PR branch |

## Objective

After this brief UAT-007 is an executable contract again (L-20): every
variable it uses is defined, every statement it gives can be run as written,
every premise it states holds on every run, and the fourth capture scenario
replays it **without a substitution list in its docstring**. Beside that, four
things the TASK-016 Worker found outside its scope and one Master Control
routed are closed: a gate test that passes vacuously in CI, seed steps whose
recorded row count understates what they applied, a harness refusal that does
not say how to recover, and a proposal whose `Location` names the wrong
resource. Nothing here changes what CloFin claims to be: a synthetic-data
reference implementation, connected to nothing, approved by no one,
processing no real funds.

## Context you need

- **The rulings.** TASK-016's changelog (this file's sibling,
  `016-TASK-trace-refresh-at-ref-2.md`, read it on `meta`:
  `git show origin/meta:docs/briefs/016-TASK-trace-refresh-at-ref-2.md`)
  records eleven rulings; seven name a UAT-007 defect and say what the
  corrected script must do. `docs/audits/016-REQ-trace-refresh-at-ref-2.md`
  §8 lists eight further statements in the script that do not match the
  captured system, and four findings outside that brief's scope. This brief is
  those lists, turned into edits with acceptance criteria.
- **Why the step-13 premise fails by coin flip.** `clofin.settlement.statement/perturb`
  edits or drops **the first line** of the statement in every class
  (checked: `sed -n 207,245p src/clofin/settlement/statement.clj` — `head`
  is `(first lines)` throughout); lines are ordered by scheme reference,
  which within one batch is instruction-id order, and ids are random. So the
  `missing-line` run drops the returned payment's line in about half of all
  runs, and step 13 — which needs a break *about the returned payment* — has
  no subject in the other half. TASK-016's scenario refuses such a run before
  acting on the wrong subject (ruled the right shape, O-2); the fix belongs to
  the script and the generator, and this brief makes it: the generator
  perturbs **a line the caller names**.
- **The harness.** `clofin.tools.capture.scenarios/seed!` runs one SQL string
  through `clofin.tools.capture.store/execute!`, which returns
  `executeUpdate`'s count — for a string of several statements, the first
  statement's count (checked: `sed -n 65,79p tools/clofin/tools/capture/store.clj`).
  Three steps carry several statements in one string: the two `seed-actors`
  steps (`grep -n '"seed-actors"' tools/clofin/tools/capture/scenarios.clj`
  — lines 186 and 414) and `seed-auditor` (line 597, two statements).
  The fourth scenario already runs its band SQL as one step per statement.
  **The bundle schema does not change** (`clofin.capture/2`): one statement
  per step keeps the step shape, so `clofin-trace` is untouched and the
  committed `ref-2` fixtures stay what they are.
- **The vacuous test.** `clofin.tools.capture-stack-test/which-gate-applies-is-read-from-the-source-not-from-the-answer`
  (test/clofin/tools/capture_stack_test.clj, from line 257) writes `ref-1`'s
  handler with `git show 5c7b4ba:src/clofin/api/health.clj` and asserts the
  result does not self-identify. CI checks out at depth 1
  (`.github/workflows/ci.yml`: `actions/checkout@v4` with no `fetch-depth`
  in either job — checked), so the `git show` fails there, `:out` is empty,
  and an empty file does not self-identify either: the assertion passes
  without reading `ref-1`. L-17: assert non-vacuity after every discovery
  step.
- **The worktree message.** `clofin.tools.capture.stack/worktree!` creates or
  reuses a detached worktree; when the output directory's worktree was
  deleted by hand, `git worktree add` refuses with *"… is a missing but
  already registered worktree"* and the harness surfaces that text with no
  recovery (`git worktree prune`).
- **The `Location`.** `clofin.api.reconciliation`'s propose handler answers
  `resp/created (str "/reconciliation-breaks/" id)` (checked: line 439),
  while `getReconciliationAdjustment`'s contract description says *"This is
  where the `Location` of a proposal and of a decision points"* and the
  `show-adjustment` docstring says both answer `/reconciliation-adjustments/{id}`.
  The propose operation's own `Location` description says "URI of the break"
  — the contract disagrees with itself. The adjustment is the created
  resource; the `Location` names it.
- **What is off limits:** the control-plane documents; `docs/releases/`;
  `src/clofin/` beyond the two changes named; `clofin-trace`'s repository
  (its fixtures are a capture of `ref-2`, and a capture of the corrected
  script is the `ref-3` trace brief under its owner's authorisation);
  `clofin-cockpit`.

## Scope

### In

- **A-1 — UAT-007, corrected.** `docs/uat/UAT-007-reconciliation-and-breaks.md`,
  edited exactly as follows and nowhere else:
  1. *Before you start* — prerequisite: "UAT-006's **steps 1–7** (not its
     step 9: a completed UAT-006 resolves the unanswered payment, and this
     script needs it unanswered)" (O-9). "two accounts beyond settlement's
     three" → "one account". The *inherits* table gains `$MAKER` (UAT-006's,
     `operator`), `$FUNDS` (the id of `1100-CLIENT-FUNDS`), `$SETTLES` and
     `$RETURNS` (UAT-006's settled and returned payments), and states the
     checker's limit as "at least SGD 1,000.00 — UAT-006 seeds SGD
     1,000,000.00" (O-6, O-7). `$VALUEDATE` is defined once: `2026-12-01`,
     the value date UAT-006 uses throughout (checked: `grep -c 2026-12-01
     docs/uat/UAT-006-settlement-simulation.md` is 9).
  2. Step 6 — the `missing-line` run names the line:
     `&perturbation=missing-line&line=$RETURNS`, with one sentence saying the
     generator perturbs the first line unless told which (A-6); the expected
     break is therefore about `$RETURNS`, always.
  3. Step 8 — heading "Take ownership, and re-assign it" (O-10: the body is
     the intent); the third call's prose "somebody in another organisation"
     → "an id that names no actor here — the answer is the same whether or
     not it names one elsewhere".
  4. Steps 9 and 12 — "above the SGD 1,000.00 band" → "at the band — the
     bound is inclusive, as step 10 says".
  5. Step 10 — "Restore the bands before continuing" → the *Before you start*
     `insert` repeated verbatim (O-4).
  6. Step 11 — "both refusals name what would have been permitted" → only
     the assignment's does (`assignable-in`); the raw insert's placeholders
     become the script's own variables (`$ORG`, `$BREAK`, `$ENTRY` — "step 9's
     `entryId`" — and `$CONTROLLER`), with the one-line note that `psql`
     does not expand shell variables and the reader substitutes them (O-5).
  7. Step 12 — the blockquote "ranked first" → "ranked first among the
     approval rules; the adjustment's lifecycle (`409`) and a missing reason
     (`422`) are checked before any approval rule"; the last call's
     `$CHECKER2` → `$CHECKER`, with the sentence that the `409` is the
     lifecycle's whoever asks (O-3).
  8. Step 13 — "Find a break about a returned payment — the `missing-line`
     run in step 6 produced one" → "the break whose `instructionId` is
     `$RETURNS`", found with a `jq` `select`; `$RETURNED` → `$RETURNS`
     throughout; `$SETTLED` → `$SETTLES` (O-2, O-6).
  9. Step 15 — the evidence pack for `$BREAK` is "opened, assigned, assigned,
     resolved — step 8 assigned it twice"; the action list is qualified
     "beside the inherited state's events and `account.created` for
     `2200-UNAPPLIED` — the trail is organisation-wide"; the plain `truncate`
     is answered by the foreign key from `reconciliation_statement_line`,
     and `truncate … cascade` reaches the trigger — say which answered.
- **A-2 — The fourth scenario replays the corrected script.**
  `reconciliation-breaks` in `clofin.tools.capture.scenarios`: pass `line`
  on the `missing-line` statement; remove the step-13 premise refusal (its
  premise now holds by construction); narratives follow the corrected text;
  the docstring carries **no substitution list** — the acceptance criterion
  Master Control attached when routing. `clofin.tools.capture-scenarios-test`
  updated; the scenario's recorded step ids unchanged except where a step was
  added or removed by the corrections.
- **A-3 — The gate test asserts what it read.** In
  `which-gate-applies-is-read-from-the-source-not-from-the-answer`, after the
  `git show`, assert the extracted source is non-blank, with a message naming
  the commit and the `git` exit status; and `.github/workflows/ci.yml` gives
  `actions/checkout@v4` `fetch-depth: 0` in **both** jobs that run the test
  suite (`verify` and the integration job), with a comment naming this test
  as the reason.
- **A-4 — One statement per seed step.** `seed!` refuses a `:statement`
  carrying more than one statement — detection: strip `--` comments and
  single-quoted literals, then any `;` followed by non-whitespace — with a
  message naming the step; the three multi-statement steps become one step
  per statement (`seed-actors`, `seed-roles`, `seed-limits`, `seed-bands`,
  and the auditor's equivalents), each with its own title and narrative, so
  "Applied — n row(s)" is true of every step. `clofin.tools.capture-scenarios-test`
  gains the refusal.
- **A-5 — The worktree refusal says how to recover.** `worktree!` catches the
  "missing but already registered worktree" failure of `git worktree add`
  and throws `capture refuses: … run `git -C <root> worktree prune` and run
  the capture again`. The harness does **not** prune on its own: pruning
  edits the operator's main repository, and a refusal that names the command
  is the honest shape.
- **A-6 — The generator perturbs a line the caller names.**
  `GET /settlement-statements` gains an optional `line` query parameter
  (`format: uuid`, the instruction whose line the class edits or drops).
  Absent, the first line as today — no existing capture or test changes
  behaviour. Rules: refused `400` with `perturbation=none` or `unknown-line`
  (the class touches no existing line, and a parameter that does nothing is
  a lie); `422`, `errors.reason: line-not-on-statement`, when the named
  instruction has no line in the period; and **the statement reference
  includes the named instruction** — a perturbed statement is a different
  document and the class is already part of the reference (`statement.clj`
  line ~264); a targeted perturbation is a different document again, and
  two different documents must not share a reference (L-2, 2C-002's shape).
  `clofin.settlement.statement-test` covers each class with a named line and
  the reference rule.
- **A-7 — The proposal's `Location`.** `POST /reconciliation-breaks/{id}/adjustments`
  answers `Location: /reconciliation-adjustments/{adjustmentId}?organisationId=…`;
  the operation's `Location` description says so; `clofin.api.reconciliation-api-test`
  gains a test that follows the proposal's and the decision's `Location` with
  `GET` and receives `200` with the representation the break embeds.

### Out — and why

- **A capture at `ref-3`, and `clofin-trace`'s fixtures.** The walkthrough
  replays a tagged release; the corrected script is replayed when `ref-3` is
  captured, under the trace owner's authorisation (ADR-0028 D10).
- **Editing UAT-006.** Only UAT-007's own text is in scope; a UAT-006 defect
  you find is an objection.
- **The statement generator's other classes.** `line` is the one addition;
  no new perturbation class.
- **Any change to what the capture stamps** (the provenance schema stays
  `clofin.capture/2`).

## Interfaces

**A-4 — `seed!`.** Signature unchanged; the refusal:

```clojure
;; capture refuses: seed step seed-actors carries 4 statements; one statement per step,
;; so the recorded row count is the count of what that step applied (TASK-020).
```

**A-6 — the generator.** `clofin.settlement.statement/perturb` takes the
target: `[class currency from lines {:keys [line]}]` where `line` is an
instruction id or nil; `head` becomes the line whose `:instruction-id` (or
the scheme reference it is derived from) names it, else the first line;
`edit` replaces that line in place, order preserved; `missing-line` removes
it. The reference suffix for a targeted perturbation is
`-<CLASS>-<instruction-id without hyphens, upper case>`. Contract:

```yaml
- name: line
  in: query
  required: false
  description: |
    The instruction whose statement line the perturbation edits or drops.
    Absent, the first line in scheme-reference order. Refused with
    `perturbation=none` or `unknown-line`, which touch no existing line;
    `422 line-not-on-statement` when the instruction has no line in the
    period. Part of the statement's reference: a perturbation aimed at a
    named line is a different document from one aimed at the first.
  schema: { type: string, format: uuid }
```

**A-7.** `(resp/created (str "/reconciliation-adjustments/" adjustment-id "?organisationId=" organisation-id) body)`
in the propose handler; the decision handler already names the adjustment
(verify, and add it to the same test).

## Dependency matrix

| Item | Transition or state it depends on | Interface or contract | DDL | DoD clause |
|---|---|---|---|---|
| A-1 | UAT-006 steps 1–7's state; the recon lifecycles as merged | the public API as at `c4e1689`; A-6's parameter | none | AC-20-1 |
| A-2 | A-1 merged in the same PR; A-6 | `rec/request!` stop-on-mismatch; `seed!` | none | AC-20-2 |
| A-3 | `ref-1`'s commit `5c7b4ba` reachable in CI | `actions/checkout` `fetch-depth` | none | AC-20-3 |
| A-4 | none | `store/execute!`'s count; the bundle step shape (unchanged) | none | AC-20-4 |
| A-5 | `git worktree add`'s message text | — | none | AC-20-5 |
| A-6 | statement lines ordered by scheme reference | `generateSimulatedStatement` contract; the reference rule | none | AC-20-6 |
| A-7 | an adjustment proposed and decided | `getReconciliationAdjustment` | none | AC-20-7 |

Every cell is filled. A cell that turns out wrong is an objection.

## Acceptance criteria

Each names its negative control (L-17).

- **AC-20-1 (A-1).** Every statement UAT-007 gives runs as written once its
  variables are substituted; every expected status is the one the system
  answers. **Run it against a fresh stack before filing the REQ** (L-20), and
  record each step's result in the script's own *Recording the result* table
  in the REQ. Negative control: the uncorrected step 13 on the half of runs
  where the first line is the settled payment's — shown once, from the REQ
  of TASK-016 (§6) or your own run.
- **AC-20-2 (A-2).** `make capture-trace CAPTURE_REF=<the PR's last harness
  commit> CAPTURE_OUT=<dir>` from a clean worktree completes **twice in a
  row** with no premise refusal; the fourth bundle's steps match the
  corrected script; the docstring has no substitution list (grep it). Negative
  control: revert A-6's `line` in the scenario and the capture is back to
  refusing some runs — shown once, or argued from the generator's `head`.
- **AC-20-3 (A-3).** On a depth-1 clone (`git clone --depth 1 … && clojure
  -M:test` for that namespace) the test **fails** naming the blank extraction
  and `5c7b4ba`; on the full clone it passes; CI's `verify` job at the PR head
  shows `fetch-depth: 0` and the test green.
- **AC-20-4 (A-4).** A scenario step carrying two statements is refused by
  `seed!` naming the step (new test in `capture-scenarios-test`); every
  `seed!` call in `scenarios.clj` carries one statement (a test walks `all`
  with a recorder that counts statements per step — reuse the detector);
  a capture's "Applied — n row(s)" for the former `seed-actors` steps now
  reads the true count per step.
- **AC-20-5 (A-5).** Delete a capture output directory's worktree by hand,
  re-run, and the refusal names `git -C <root> worktree prune`; after
  pruning, the capture proceeds (shown in the REQ). Negative control: the
  message's `root` is the main repository, not the output directory.
- **AC-20-6 (A-6).** `clofin.settlement.statement-test`: for each class that
  touches an existing line, `line` names the second line and that line — not
  the first — is edited or dropped; `none` and `unknown-line` with `line` are
  `400`; an instruction with no line in the period is `422
  line-not-on-statement`; the reference differs between a targeted and an
  untargeted perturbation of one statement (negative control: remove the
  suffix and the two references collide, and the second ingestion of the
  pair is reported as a replay of the first — the L-2 failure, shown once).
  `clofin.api.conformance-test` walks the operation with the parameter at
  least once.
- **AC-20-7 (A-7).** `clofin.api.reconciliation-api-test/the-location-of-a-proposal-and-of-a-decision-resolves-to-the-adjustment`:
  `GET` on each `Location` is `200` and equals the embedded representation.
  Negative control: the old header — `GET` on it answers the **break**, which
  the test distinguishes by `kind`/`state` members the adjustment lacks.

## SQL pre-flight

This brief specifies no migration. Every SQL statement UAT-007 states was
executed by Master Control on 2026-10-03 against the local capture database
(PostgreSQL 16, schema `0013`, holding a UAT-007 run) with every placeholder
substituted, in a rolled-back transaction (L-3, widened):

```
-- Before you start, and step 10's restore (the same statement)
delete from approval_threshold where organisation_id = '<ORG>' and currency = 'SGD';     DELETE 1
insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
values ('<ORG>', 'SGD', 100000, 1);                                                    INSERT 0 1
select from_minor, approvals_required … order by from_minor;                           100000 | 1
delete from approval_threshold where organisation_id = '<ORG>';                        DELETE 1
insert … values ('<ORG>', 'SGD', 100000, 1);                                           INSERT 0 1
-- step 11, substituted: gen_random_uuid(), '<ORG>', '<BREAK of a posted adjustment>', …, '<its entry>', now(), '<CONTROLLER>'
ERROR:  duplicate key value violates unique constraint "recon_adjustment_posted_key"
-- step 13: update payment_instruction set retries_id = null where …
ERROR:  payment_instruction.retries_id is set when the retry is raised and never changes: …
-- step 15
update reconciliation_match set rule_id = 'R3-reference-only';
ERROR:  Table reconciliation_match is append-only: … never by update
delete from reconciliation_statement_line;
ERROR:  Table reconciliation_statement_line is append-only: … never by delete
truncate reconciliation_statement;
ERROR:  cannot truncate a table referenced in a foreign key constraint
truncate reconciliation_statement cascade;
ERROR:  Table reconciliation_statement is append-only: … never by truncate
```

Every refusal is the one the corrected script will state. `psql` substitutes
nothing: the script's note (A-1 item 6) exists because of this. The script is
[`preflight/uat-007-task-020.sql`](preflight/uat-007-task-020.sql).

## Definition of done

- [ ] Every AC above has a named test or recorded run; each negative control
      seen failing and quoted in the REQ
- [ ] `make verify` and `make test-it` green with counts; `make diagrams-check`
      green; `make docs-check` green (the script's internal links)
- [ ] UAT-007 executed against a fresh stack, every step recorded, before the
      REQ is filed (L-20)
- [ ] The capture run twice from a clean worktree with no refusal; the
      fourth bundle's step list in the REQ; `clofin-trace` untouched
- [ ] `ci.yml` carries `fetch-depth: 0` on both suite jobs with the comment
- [ ] `020-REQ-uat-007-corrections-and-walkthrough-findings.md` on the PR
      branch: provenance header (model may be a deliberate, stated absence;
      effort and date recorded); the finding-by-criterion table; the
      step-by-step UAT-007 record; objections; the L-9 statement in plain
      words
- [ ] One PR, `TASK-020: …`, against `main`; merged by Master Control after
      the objections are ruled and CI is green on the branch

## Notes for whoever picks this up

- **Edit the script, not the system, except where this brief says.** A step
  whose expected status the system does not give is corrected to what the
  system does — unless the system is wrong, which is an objection, not a
  `src/` change.
- **The reference rule in A-6 is not optional.** Two different documents
  with one reference is the exact shape of blocking finding 2C-002; the
  class is already in the reference for this reason, and the target joins it
  for the same reason.
- **Do not prune for the operator.** A harness that edits the main
  repository's worktree list to make its own life easier has started doing
  things a capture should not do.
- **Keep the bundle schema.** One statement per step is a scenario change,
  not a stamp change. If you find yourself adding a field to the step
  shape, stop: that is `clofin.capture/3` and a trace brief.
- **`fetch-depth: 0` costs a few seconds and buys the test its meaning.** Do
  not narrow it to `fetch-depth: 50` on a guess about where `5c7b4ba` sits.
- **L-16 copies.** UAT-007's *Recording the result* table lists steps and
  requirements; keep it in step with the corrected steps. The README under
  `docs/uat/` describes UAT-007 in one line; it stays true.
- **L-9.** If a self-review is still running when you write the REQ, say so
  in the REQ and do not call the work complete until it is not.
