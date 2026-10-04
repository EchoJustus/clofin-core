# TASK-016: `clofin-trace` at `ref-2` — the capture stamps how it bound, the walkthrough reads it, and the reconciliation walk joins the three

| Field | Value |
|---|---|
| **Increment** | 5v.4 (the visual layer, fourth increment) |
| **Status** | `CLOSED` — `clofin-trace` PR #4 (`0752637`) + `clofin-core` PR #38 (`fa5e790`), merged 2026-10-03 in the brief's order; eleven objections ruled below; the walkthrough replays `ref-2` |
| **Depends on** | `ref-2` released ✅ (tag object `420722f` → `32dfcc9`, pre-release 401516365); TASK-015 ✅ (the harness that binds a capture to its own process) |
| **Blocks** | — (increment 7 does not wait on this; the cockpit does not either) |
| **Requirements** | D5; ADR-0020 rules 1–3; ADR-0022; ADR-0027 §3a; the condition carried from 015-REQ O-5 |
| **Controls touched** | none — nothing under `src/clofin/` changes. Part A is harness, tests, one ADR amendment and one README line, all inside the next release audit's scope; part B is outside audit scope by definition (ADR-0020) |
| **Scope** | Medium–Large — two repositories, two PRs, one REQ |
| **Base branch** | `clofin-core` `main` at `d5a3735` or later; `clofin-trace` `main` at `bc0017c` or later |
| **Audit** | `docs/audits/016-REQ-trace-refresh-at-ref-2.md`, task-keyed, filed on the `clofin-core` PR branch; it reports on both halves |

## Objective

After this brief, https://echojustus.github.io/clofin-trace/ replays `ref-2`
— the first release cut on a complete audit — and the provenance block every
page carries says, from captured values, four things together: the tag, the
commit, the audit coverage (`COMPLETE`), and **how the capture established
that the process it interrogated was the one it started**. The last is the
condition Master Control attached when it accepted 015-REQ O-5: the run
*printed* which binding it used; from here it *stamps* it, so a reader of a
fixture can tell without the run's console. The walkthrough gains a fourth
scenario, reconciliation, replayed from UAT-007 the way the three existing
ones replay UAT-005 and UAT-006. Nothing here changes what CloFin claims to
be: a synthetic-data reference implementation, connected to nothing, approved
by no one, processing no real funds.

## Context you need

- **The rules.** ADR-0020: generate never draw; replay never fake; quote never
  paraphrase. ADR-0022: the harness establishes its own provenance and refuses
  to write an unstamped artifact. ADR-0027 §3a (added by TASK-015): what an
  echoed `instanceId` does and does not establish, and the two bindings a
  capture can have — **self-report** for a commit whose `GET /` renders the
  field (`ref-2` and after), **port exclusion** for one whose source does not
  (`ref-1`). Read §3a before touching the harness; it is the text this brief
  turns into a stamped field.
- **The condition this brief discharges.** TASK-015's changelog, ruling O-5:
  *"the bundle manifest must stamp which binding a capture used, not only
  print it, so a consumer of a fixture can tell."* At `main` today the binding
  is decided in `clofin.tools.capture.stack/assert-same-process!` (it returns
  `:identity-established-by` as `:instance-id` or `:port-exclusion`) and
  reaches nothing on disk — `grep identity-established-by tools/` finds only
  that namespace.
- **The harness** (`tools/clofin/tools/capture.clj` and `capture/*.clj`):
  `make capture-trace CAPTURE_REF=ref-2 CAPTURE_OUT=<dir>` creates a detached
  worktree at the commit, refuses an occupied port, mints an instance id,
  migrates a scratch database with the commit's own runner, starts the commit's
  own service, captures `GET /`, runs every scenario in
  `clofin.tools.capture.scenarios/all` through a recorder that **stops on an
  unexpected status**, reads the journal and the audit trail from the
  database, and writes one stamped bundle per scenario plus a manifest through
  four writers that each validate the stamp before opening a file. The stamp's
  required fields are the list `clofin.tools.capture.provenance/required`; a
  test walks that list, so a field added there without a test fails.
- **The scenario pattern.** `settlement-batch` in `scenarios.clj` is the shape
  to copy: a second organisation created through the API, actors seeded by
  `seed!` (SQL, recorded, with the database's refusal recorded when a step is
  meant to be refused — see `superuser-refused`), accounts opened by
  `account!`, requests by `rec/request!` with `:expect-status`, balance
  snapshots by `balances!` that the sand table is built from and
  `bundle/verify-against-journal!` checks against the captured journal.
- **The consumer** (`clofin-trace`): Python, no JavaScript, two CI checks and
  exactly two (`provenance-present`, `disclaimer-verbatim`, plus the seam
  TASK-009 closed inside the first). `build/fixtures.py` refuses any manifest
  whose `schemaVersion` is not the one it knows; `build/build.py` iterates the
  manifest's bundles for pages but hard-codes "The three scenarios" in the
  index; the provenance block (`build.py` ~lines 170–220) renders tag, SHA,
  coverage, schema version and harness commit from captured pointers.
- **What the `ref-1` capture looks like** (`fixtures/manifest.json` at trace
  `bc0017c`): `tagKind: lightweight`, `releaseAudit.source:
  release-annotation-file`, `label: PARTIAL`, `harness.dirty: true`. All four
  change at `ref-2`: annotated, `git-tag-annotation`, `COMPLETE`, and — this
  brief requires — `false`.
- **What is off limits:** `src/clofin/**` (no control changes; a needed one
  is an objection); the control-plane documents (`docs/ROADMAP.md`,
  `docs/briefs/**`, `docs/audits/README.md`, `docs/AGENT_HANDOFF.md`,
  `docs/audits/RELEASE-AUDIT-CHARTER.md`); `docs/releases/*.annotation.txt`;
  `docs/uat/UAT-007-reconciliation-and-breaks.md` (the scenario replays the
  script as merged — a defect found in it is an objection, not an edit);
  `clofin-cockpit`.

## Scope

### In — part A, `clofin-core`

- **A-1 · The binding is stamped.** `clofin.tools.capture.stack/start!`
  returns `running` carrying `:identity-binding`, one of `:instance-id` /
  `:port-exclusion`, taken from what `assert-same-process!` established at
  start-up (it keeps re-checking before every writer as today). The stamp
  (`clofin.tools.capture.provenance/stamp`'s result, completed in
  `capture!` the way `:schema-version-applied` is) gains `:identity-binding`;
  `required` gains the row; `provenance->wire` / `wire->internal` carry it as
  `"identityBinding"` with wire values `"instance-id"` / `"port-exclusion"`,
  placed after `"schemaVersionApplied"` and before `"harness"` (key order is
  part of the contract — the block is rendered in that order).
  **`schema-version` becomes `"clofin.capture/2"`**: its own docstring says it
  changes "when a consumer would have to change with it", and this consumer
  must. ADR-0022 gains a dated amendment naming the field, its two values,
  the schema bump and the rule that produced it; for what each value
  establishes it **cites ADR-0027 §3a** rather than restating it.
- **A-2 · The fourth scenario.** `reconciliation-breaks`, replaying
  `docs/uat/UAT-007-reconciliation-and-breaks.md` **as merged at the captured
  commit**, in `scenarios.clj`, appended to `all` as the fourth entry:
  - **self-contained**: its own organisation; actors with exactly the roles
    UAT-007's *inherits* table names (a controller, an approver whose SGD
    limit is above SGD 100.00, an auditor) seeded by `seed!`; settlement's
    three accounts plus `2200-UNAPPLIED`; the prerequisite ledger movements —
    a settled payment, a returned one, one never answered — produced through
    the public settlement API exactly as `settlement-batch` produces them
    (**factor the shared steps into helpers both scenarios call; do not copy
    them**);
  - the SGD approval bands **replaced, not added to**, by UAT-007's block-1
    SQL as a `seed!` step (pre-flighted below);
  - **UAT-007 steps 1–15 as recorded requests**, each with `:expect-status`
    equal to the status the script states for that step — replay, the
    contradiction under one reference, the real-format and real-scheme-name
    refusals, the queue, ownership and the skipped step, the above-threshold
    posting with the self-approval refused, the below-threshold posting,
    the resolved-break refusal, the rejection and the second proposal, the
    retry link and its immutability, the status read, the trail — every
    `/audit/` read as the auditor;
  - **step 15's three statements as `sql!` steps expected refused**, recorded
    with the database's own message, as `superuser-refused` records its;
  - a **sand table** over the accounts the script's adjustments move — the
    three settlement accounts and `2200-UNAPPLIED` — with rows taken by
    `balances!` before ingestion and after each posting step (9, 10, 12, 13),
    verified by `verify-against-journal!` as the settlement table is. The
    break counts the script reads (steps 7 and 14) are captured responses
    rendered as captured values; no new table type.
- **A-3 · The capture.** Performed by the Worker from a **clean checkout of
  the core PR branch's final commit** (`harness.dirty` must be `false`;
  `ref-1`'s was `true`), in a clone where `ref-2` exists as a **tag object**
  (`git fetch origin --tags`; `git cat-file -t ref-2` prints `tag`; without
  it the harness falls back to the mirror and the stamp says so, which is
  not the stamp this release gets). `make capture-trace CAPTURE_REF=ref-2
  CAPTURE_OUT=<outside the repo>`. The REQ quotes the manifest's provenance
  block verbatim.
- **A-4 · One README line.** `README.md`'s walkthrough link names `ref-2`
  instead of `ref-1`. It is merged **after** the site is live at `ref-2` (see
  *Notes*).

### In — part B, `clofin-trace`

- **B-1 · The fixtures.** `fixtures/` becomes the `ref-2` capture: manifest,
  `service-info.json`, `quotations.json`, four bundles. The `ref-1` capture
  leaves the tree — **one capture per site** — and the README names the
  commit where it lives (`bc0017c`).
- **B-2 · The build.** `SCHEMA_VERSION = "clofin.capture/2"` (a `/1` manifest
  is refused, naming the version — that is the point of the bump). The
  provenance block renders `identityBinding` **in-frame with the tag, the
  SHA and the coverage**, as a captured value (`cap` pointer
  `/provenance/identityBinding`), labelled by its field name and linked to
  `docs/ADR/0027-…` at the captured commit — no prose on the page saying what
  it proves (RULE 3: the ADR is the attributed source; a sentence of the
  site's own would be a paraphrase). The captured `GET /` renders as
  captured: `instanceId` is the harness's redaction marker and `sourceCommit`
  the self-reported value — shown as what they are, never dressed up. The
  index derives its scenario count from the manifest; the fourth page comes
  from the same `scenario_page`.
- **B-3 · The checks, inside the two.** `provenance-present` gains, as the
  same check: (i) `identityBinding` is a required stamp field with exactly
  those two values — the fixtures' required list mirrors the harness's
  `required`, and the REQ shows the two lists side by side; (ii) every page's
  provenance block shows it, in-frame, next to tag/SHA/coverage; (iii) when
  the captured `GET /` carries `sourceCommit`, it **equals
  `provenance.sourceCommit`** byte for byte — a fixture whose service reported
  one commit while its stamp names another fails (L-19 on the consumer's
  side); (iv) the schema refusal above. `disclaimer-verbatim` is unchanged in
  logic — the captured sentence at `ref-2` is the canonical one. The
  assurance-word rule from TASK-007 AC-11 keeps firing: with a `COMPLETE`
  label the qualifier is still the captured label beside the SHA, never a
  word of the site's own.
- **B-4 · The README.** *What this site shows* names `ref-2`, its coverage as
  the captured label, and the four scenarios; *Fixtures* says one capture per
  site and where the previous one lives; the scope statement stays verbatim
  (checked).
- **B-5 · The deploy.** On merge to `main`, `pages.yml` builds, checks and
  deploys. The REQ names the run; Master Control verifies the live page's
  provenance block reads `ref-2` / `32dfcc9` / `COMPLETE` / `annotated` /
  `instance-id` before merging A-4.

### Out — and why

| Out of scope | Reason |
|---|---|
| Re-capturing `ref-1` with the new harness | `ref-1`'s walkthrough is closed; a new capture would stamp `port-exclusion` and change a published replay for no reader's benefit. It lives at trace `bc0017c`. |
| A site that holds more than one capture | One tag per site build; a multi-tag site is a design decision nobody has asked for, and the build's refusal of a foreign schema version is part of how one capture stays one. |
| Any change under `src/clofin/` | The subject's controls are not this brief's; `tools/` is. |
| A reconciliation scenario in `clofin-cockpit` | The cockpit is interaction with the present; this is replay of the past (ADR-0026). The cockpit reads releases live and needs nothing here; if its tests pin `ref-1` text, that is its own brief. |
| Editing UAT-007 | The scenario replays the script as merged; a defect in it is an objection. |
| Pagination or a cap on the fourth scenario's collections | The script's volumes are far below the row cap; 2C-001's fields are captured and rendered as captured values if present. |

## Interfaces

**A-1.** In `clofin.tools.capture.provenance`:

```clojure
(def schema-version "clofin.capture/2")
;; `required` gains, after the harness rows:
[[:identity-binding] #{"instance-id" "port-exclusion"}
 "how the capture established that the answering process was the one it started"]
```

`provenance->wire` emits `"identityBinding"` after `"schemaVersionApplied"`;
`wire->internal` reads it back. `stack/start!` returns
`{… :identity-binding :instance-id | :port-exclusion}`; `capture!` does
`(assoc base-stamp :schema-version-applied applied :identity-binding (name (:identity-binding running)))`
— the wire value is the keyword's name.

**A-2.** The scenario map, fourth in `all`:

```clojure
{:id      "reconciliation-breaks"
 :title   "Reconciliation: a statement, its breaks, and the corrections that close them"
 :summary "…"   ; the Worker's words, three sentences at most, no claim about what a control guarantees
 :source  "docs/uat/UAT-007-reconciliation-and-breaks.md"
 :run     reconciliation-breaks}
```

`reconciliation-breaks` returns `{:organisation-id … :sand-table {:codes [the four account codes] :rows […]}}` in the shape `settlement-batch` returns.

**B-2 / B-3.** `build/fixtures.py`: `SCHEMA_VERSION = "clofin.capture/2"`; the
required list gains `(["identityBinding"], lambda v: v in ("instance-id", "port-exclusion"))`.
`build/build.py`: `r.cap(m, '/provenance/identityBinding', tag='code')` in the
provenance block. `build/checks/provenance_present.py`: the block assertion
names the new field; the `sourceCommit` equality assertion reads
`service-info.json`'s captured body.

## Dependency matrix

| Item | Transition or state it depends on | Interface or contract | DDL | DoD clause |
|---|---|---|---|---|
| A-1 | `assert-same-process!`'s two outcomes (`:instance-id`, `:port-exclusion`) | stamp schema `/2`; `required`; wire key order | none | AC-1 |
| A-2 | UAT-007's step statuses as merged at `32dfcc9`; the recon state tables; approval bands | the public reconciliation and settlement API as captured; `rec/request!` stop-on-mismatch | none new — the band SQL is the script's, pre-flighted below | AC-2 |
| A-3 | `ref-2` tag object fetchable; a clean harness tree; a free port; a reachable PostgreSQL | `make capture-trace` | none | AC-3 |
| A-4 | the site live at `ref-2` | one README line; `make docs-check` | none | AC-9 |
| B-1 | A-3's output | manifest `/2` with four bundles | none | AC-4 |
| B-2 | B-1 | `SCHEMA_VERSION`; provenance block pointers | none | AC-4 |
| B-3 | B-2 | the two checks' contracts | none | AC-5, AC-6, AC-7 |
| B-4 | B-1 | README text; `disclaimer-verbatim` reads it | none | AC-8 |
| B-5 | B-1…B-4 merged | `pages.yml` | none | AC-8 |

Every cell is filled. A cell that turns out wrong is an objection.

## Acceptance criteria

Each names its negative control (L-17).

- **AC-1 (A-1).** Every one of the four writers refuses a stamp whose
  `identityBinding` is absent or any value but the two, leaving no file —
  the writer × required-field matrix walks `required`, so the new row enters
  it or `every-required-field-is-exercised` fails: that is the negative
  control, and the REQ quotes it failing before the row's test existed. A
  capture started against a worktree whose `GET /` renders `instanceId`
  stamps `instance-id`; one against a worktree whose source does not stamps
  `port-exclusion` (both cases run with the local `HttpServer` pattern of
  `capture-stack-test`, no CloFin service needed).
- **AC-2 (A-2).** The fourth bundle holds every UAT-007 step with its expected
  status met, the three step-15 statements recorded as refused with the
  database's message, and a sand table `verify-against-journal!` accepts.
  Negative control: change one `:expect-status` and the capture stops naming
  the step (existing behaviour, shown once). `all` has four entries and the
  index-count test, if one exists, says so.
- **AC-3 (A-3).** The manifest's provenance at `ref-2` reads: `sourceCommit`
  `32dfcc99025fa339478f7ecf91b42ded71d725c2`, `tag` `ref-2`, `tagKind`
  `annotated`, `releaseAudit.label` `COMPLETE`, `releaseAudit.source`
  `git-tag-annotation`, `schemaVersionApplied` `0013`, `identityBinding`
  `instance-id`, `harness.dirty` `false`, and `harness.commit` the PR
  branch's final commit — which Master Control confirms is an ancestor of
  `main` after merge.
- **AC-4 (B-1, B-2).** The build renders four scenario pages and the
  provenance block with `identityBinding` in-frame; given the `ref-1`
  manifest from `bc0017c`, the build **refuses**, naming `clofin.capture/1`.
- **AC-5 (B-3).** `provenance-present` fails, naming the cause, on each of:
  `identityBinding` missing; `identityBinding: "self-report"` (a plausible
  wrong value); a page whose block lacks it; `service-info.json` whose
  captured `sourceCommit` differs from the stamp's by one character — each
  in a scratch copy of the fixtures; and passes on the real ones. Still
  exactly two checks in CI.
- **AC-6.** `disclaimer-verbatim` passes at `ref-2`; one softened word in a
  page fails it (existing control, shown once).
- **AC-7.** A page sentence calling the source state "audited" with no
  captured qualifier adjacent fails `provenance-present` — with the label now
  `COMPLETE`, the rule must still fire; shown once.
- **AC-8 (B-4, B-5).** The README names `ref-2`, the four scenarios and
  `bc0017c`; the Pages run is green and the live page's block reads `ref-2` /
  `32dfcc9` / `COMPLETE` / `annotated` / `instance-id`.
- **AC-9 (A-4).** `clofin-core`'s README names `ref-2`; `make docs-check`
  passes; merged after AC-8.

## SQL pre-flight

This brief specifies no SQL of its own. The scenario executes the statements
UAT-007 states, as merged at `32dfcc9`. Master Control ran them against a
local PostgreSQL 16 at schema `0013` on 2026-10-02 (L-3, widened):

```
begin;  -- a throwaway organisation, rolled back
insert into organisation (id, legal_name, short_name) values ('0000aaaa-…-00000000a007', …);
delete from approval_threshold where organisation_id = '…a007' and currency = 'SGD';   → DELETE 0
insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
values ('…a007', 'SGD', 100000, 1);                                                      → INSERT 0 1
select from_minor, approvals_required from approval_threshold where … order by from_minor;
   100000 | 1
rollback;
```

Step 15, each statement in its own rolled-back transaction, against a schema
holding rows:

```
update reconciliation_match set rule_id = 'R3-reference-only';
ERROR:  Table reconciliation_match is append-only: … never by update
delete from reconciliation_statement_line;
ERROR:  Table reconciliation_statement_line is append-only: … never by delete
truncate reconciliation_statement;
ERROR:  cannot truncate a table referenced in a foreign key constraint   (the FK answers first)
truncate reconciliation_statement cascade;
ERROR:  Table reconciliation_statement is append-only: … never by truncate
```

All three are refused as the script expects. Note for the scenario: the plain
`truncate` is stopped by the foreign key before the trigger speaks; record the
script's statement as written and the database's answer as given — both are
refusals, and `clofin.recon.repository-test` covers the trigger's own answer
with `cascade`.

## Definition of done

- [ ] Every AC above has a named test or recorded run; each negative control
      was seen failing and the REQ quotes it
- [ ] `clofin-core`: `make verify` and `make test-it` green with counts;
      `ADR-0022` amended with a dated section; nothing under `src/clofin/`
      changed (`git diff --stat origin/main -- src` is empty); control-plane
      files untouched
- [ ] `clofin-trace`: `python3 build/build.py` and both checks green locally
      on the new fixtures; the `ref-1` manifest refused; still exactly two
      checks in `.github/workflows/`
- [ ] The capture performed from a clean tree at the core PR's final commit
      with the tag object present; the manifest's provenance block quoted in
      the REQ
- [ ] `016-REQ-trace-refresh-at-ref-2.md` on the core branch: provenance
      header (model may be a deliberate, stated absence; effort and date
      recorded); both PR links; the finding-by-criterion table; the two
      required-field lists side by side; objections; the L-9 statement in
      plain words
- [ ] Two PRs: `clofin-core` `TASK-016: …` (A-1, A-2, A-4, the REQ) and
      `clofin-trace` `TASK-016: …` (B-1…B-4). Merge order is Master Control's
      and is stated in *Notes*

## Notes for whoever picks this up

- **Order of landing.** Core PR first in *review*, but the README line (A-4)
  must not say `ref-2` while the site still shows `ref-1`. The sequence:
  capture from the core branch's final commit → trace PR merged → Pages live
  and verified → core PR merged. Keep A-4 in its own commit at the end of the
  core branch so a reviewer can see it alone.
- **The harness commit in the stamp is your branch's commit.** It is not on
  `main` when you capture; it will be, once the core PR merges. Do not
  capture from a dirty tree to "fix" that — `ref-1`'s manifest says
  `dirty: true`, and this release's must not.
- **Fetch the tag object.** A clone that has `ref-2` only as a lightweight
  ref (or not at all) makes the harness read the mirror and stamp
  `release-annotation-file`. `git cat-file -t ref-2` must print `tag`.
- **The environment.** The harness starts the captured commit's own service:
  a local `clojure` CLI and a reachable PostgreSQL 16 are required, Docker is
  not. TASK-015's Worker ran PostgreSQL directly for the same reason.
- **`instanceId` is already redacted** from the fixture by the harness
  (`redact-instance-id`); the site renders the marker as captured. Do not
  strip it, do not explain it on the page.
- **"The three scenarios" is hard-coded** in `build.py`'s index. Derive the
  count; a number typed into a page is the class this site exists to avoid.
- **`self-identifies?` reads the worktree, not the answer.** For AC-1's
  `port-exclusion` case, give it a worktree whose `health.clj` does not
  render `instanceId` — `git show 5c7b4ba:src/clofin/api/health.clj` is one
  — not a responder that withholds the field.
- **Two repositories, one REQ.** The REQ lives in `clofin-core` and reports
  on both PRs; the trace PR's description links to it by path.
- **L-9.** If a self-review is still running when you write the REQ, say so
  in the REQ and do not call the work complete until it is not.

## Changelog — rulings on the `016-REQ` objections, and the close-out (2026-10-03)

Delivered as `clofin-trace` PR #4 (6 commits, merged at `0752637`) and
`clofin-core` PR #38 (7 commits, merged at `fa5e790`), in the brief's order:
trace first, Pages run 37081282071 green, the live page verified, then core.
The REQ is `docs/audits/016-REQ-trace-refresh-at-ref-2.md` on `main`.

**Master Control's reproduction, before any merge.** Core, in a detached
worktree at the PR tip `2681ff9`: `make verify` 543 tests / 3,404 assertions,
and the integration suite 971 tests / 7,434 assertions (the Worker's run:
7,449 — the property tests generate a varying number of cases), both 0
failures, on a local PostgreSQL 16. Trace, at `36eefda`: the build and both
checks pass on the committed fixtures; the `ref-1` manifest from `bc0017c` is
refused naming `clofin.capture/1`; exactly two checks in each workflow.
**Then the capture itself**: from a fresh detached worktree at the harness
commit `1ee8346`, clean, with `ref-2` as a tag object, `make capture-trace
CAPTURE_REF=ref-2` — the first attempt refused by the step-13 premise check
with the message the REQ quotes (O-2 reproduced on the first try), the second
captured. Against the Worker's fixtures: provenance identical in every field
but `capturedAt`; all four bundles identical in step ids, kinds, actual and
expected statuses; sand tables identical cell for cell (6 and 5 rows); journal
entry counts identical (7, 3, 9). The Worker's site built from Master
Control's capture passes both checks. **The live page** at
https://echojustus.github.io/clofin-trace/ after the Pages deploy: `ref-2`,
`32dfcc9`, `COMPLETE`, `annotated`, `instance-id`, the fourth page served, no
`ref-1` or `PARTIAL` text left; the published manifest's provenance equals the
committed one. After the core merge, `1ee8346` is an ancestor of `main`.

| # | Objection | Ruling |
|---|---|---|
| O-1 | AC-3's "the PR branch's final commit" cannot also be the commit that carries the REQ and A-4. | **Confirmed — brief defect, Master Control's.** AC-3 means the last commit that changes anything the capture runs; the REQ's `git diff --stat 1ee8346 HEAD -- tools test src resources deps.edn Makefile scripts .github` being empty is the proof the brief should have asked for, and future briefs with a capture step will. |
| O-2 | UAT-007 step 13's premise ("the `missing-line` run produced a break about the returned payment") holds in about half of all runs: the generator drops the first line in instruction-id order, and the ids are random. | **Confirmed — a defect in UAT-007, not in the replay; the scenario's refusal is ratified, and it is the right shape** (L-20: an acceptance script is an executable contract, and a premise that holds by coin-flip is not one). Master Control reproduced it on the first attempt. The published run is a run that happened, unedited. **Routed**: UAT-007 names the break by its `instructionId` equal to the returned payment, or the generator perturbs a line the caller names — to the UAT-007 corrections brief (below). |
| O-3 | `$CHECKER2` is defined nowhere; run literally the call answers `401`. | **Ratified** as `$CHECKER`, with the narrative saying so; the `409` is the lifecycle's, whoever asks. UAT-007 defect, routed. |
| O-4 | Step 10 says "restore the bands" and gives no statement. | **Ratified**: the *Before you start* SQL, run again. UAT-007 defect, routed. |
| O-5 | Step 11's raw insert carries placeholders; `seed!` accepted any refusal. | **Ratified, and commended**: `seed!`'s `:expect-error` makes every raw-SQL refusal name the refusal it shows — the L-17 shape closed before anyone found it. |
| O-6 | Step 13 uses variables the *inherits* table does not list, one defined nowhere. | **Ratified** as substituted. UAT-007 defect, routed. |
| O-7 | The inherits table's "SGD limit above SGD 100.00" is insufficient for step 9's SGD 1,000.00. | **Ratified**: UAT-006's seeded limit. UAT-007 defect (L-20), routed. |
| O-8 | The brief's "sand-table row after step 13" — step 13 posts nothing. | **Confirmed — brief defect, Master Control's.** The row is kept as taken: a row that equals the one before it is the captured evidence that raising a retry moves no money, which is worth a row. |
| O-9 | "UAT-006 completed, or steps 1–7" admits a state that contradicts UAT-007 (a completed UAT-006 resolves the unanswered payment). | **Ratified**: steps 1–7's state-producing calls only, through the shared helpers. UAT-007's prerequisite sentence is a defect, routed. |
| O-10 | Step 8's heading promises an attempt to "skip a step" that its body does not make; the brief repeated the heading. | **Ruled: the body is the intent.** The break lifecycle permits `open → resolved` and the capture shows it; there is no step to skip. The brief's phrase is withdrawn (Master Control copied a heading without reading the body — L-16 applied to a brief). The heading is a UAT-007 defect, routed. |
| O-11 | ADR-0027 §3a names port exclusion without its limit; the brief asked ADR-0022's amendment to cite §3a "for what each value establishes". | **Ruled: ADR-0022 may state the limit; ADR-0027 gains nothing.** The limit is a property of the harness, and the harness's ADR is where it belongs; §3a describes the service's field. The amendment as written — citing §3a for the service's part and `assert-same-process!`'s docstring for the harness's — is accepted; a later edit of ADR-0022 may lift that docstring's sentence into the amendment, which is not a restatement of §3a. |

**Routed, not changed here.** (1) **UAT-007 corrections** — the defects behind
O-2, O-3, O-4, O-6, O-7, O-9, O-10 and the eight mismatched statements the REQ
lists under §8: a doc-only brief in the subject, dispatched with the
increment-7 batch, whose acceptance criterion is that the fourth scenario
replays the corrected script *without* a substitution list in its docstring.
(2) **A vacuous test in CI**, found by the Worker outside its scope:
`capture-stack-test/which-gate-applies-is-read-from-the-source-not-from-the-answer`
reads `ref-1`'s handler with `git show 5c7b4ba:…`, which fails on CI's depth-1
checkout, and an empty extraction "does not self-identify" either — L-17's
*assert non-vacuity after every discovery step*, in a test TASK-015 added
under L-17. Same brief: assert the extracted source is non-blank, and give the
job the history it needs. (3) **A multi-statement seed step records the first
statement's row count** — the existing scenarios' "Applied — n row(s)" figures
understate; same brief: one statement per step, as the fourth scenario already
does. (4) **The proposal's `Location` names the break while the contract says
the adjustment** — a `src/` contract defect in 2C-011's territory; the
post-audit remediation backlog. (5) The harness's re-run message after a
deleted worktree does not say `git worktree prune`; same brief as (2).

**Recorded beside the rulings.** The Worker's adversarial review (34 findings,
33 already fixed by the time their verifiers ran, 1 confirmed and fixed) found
that the site's digit predicates used `\d`, which in Python admits any Unicode
digit — a gap the harness's `\d` (ASCII in Java) does not have. Two
implementations of one rule in two languages had already drifted at the
character class; the fix spells `[0-9]`. The REQ's §3 side-by-side list is the
first place the two required-field lists have been compared; a check that
compares them mechanically is not possible across repositories, and the
limitation is recorded as a limitation rather than papered over.
