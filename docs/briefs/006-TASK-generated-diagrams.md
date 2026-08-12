# TASK-006: Generated diagrams, and two CI guards for document truth

| Field | Value |
|---|---|
| **Increment** | 5v.1 (visual layer, tier 0) — inside `clofin-core` |
| **Status** | `IN PROGRESS` — dispatched 2026-08-05. Amendment A1's gate is satisfied: ADR-0020 merged to `main` in PR #11 (`cbbd669`) |
| **Depends on** | **ADR-0020** ✅ merged (`cbbd669`) and the `meta` → `main` sync ✅ merged (PR #10, `64ceef0`) — both satisfied; the ordering trap below is therefore **released**, see the note there |
| **Base branch** | `main` at `cbbd669` or later. Both preconditions verified by Master Control at dispatch; verify them yourself anyway |
| **Blocks** | TASK-007 |
| **Requirements** | Driver D5; lessons L-4 (drawing half), L-15 |
| **Controls touched** | None move. This is document machinery, not enforcement |
| **Scope** | Medium |
| **Audit** | Not yet submitted |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

Two documents currently claim things a human has to check by eye. This brief
makes both machine-checkable.

**The diagrams.** `DOMAIN_MODEL.md` §3 carries a hand-maintained ASCII lifecycle
diagram. It has already drifted once from the transition table it depicts — that
is standing lesson **L-4**, and the mitigation today is a note asking a reader to
compare the drawing with the table. A drawing produced *from* the table cannot
disagree with it.

**The status claims.** On 2026-08-05 `main` said four controls were "designed,
not built" while `COMPLIANCE.md` on the same branch showed all four enforced,
and the ROADMAP listed merged increments as not started. That is standing lesson
**L-15**, and it survived two milestone audits and a release audit.

## Governing decision

**`ADR-0020` — `docs/ADR/0020-two-repositories-and-the-generate-replay-rules.md`
on `main`, which must already be merged when you read this.** Its **RULE 1 — generate, never draw** is this brief's
whole premise. Read it before you start.

## Scope

### In

1. **A diagram generator, in Clojure** — `clofin.tools.diagrams`, run through a
   `deps.edn` alias. **No Node, no npm, no new runtime dependency**; ADR-0004
   and NFR-007 stand. If you believe a diagram cannot be produced without one,
   that is an objection for your REQ, not a decision to take.
2. **Three generated diagrams**, each from its source of truth:
   - the **payment lifecycle** from `clofin.payments.state/transitions` — every
     state, every event, the terminal set derived rather than listed;
   - the **context topology** from `ARCHITECTURE.md` §3's table;
   - the **control map** from `COMPLIANCE.md` §2 — control id, statement
     status (✅/🔨/📋), and enforcement point.
3. **Committed output** — Mermaid and/or SVG under `docs/diagrams/`. Output must
   be **deterministic**: the same input produces byte-identical output, or the
   check below is a coin toss. State how you achieved that (sorted iteration,
   fixed ids, no timestamps) in your REQ.
4. **`make diagrams-check`** — regenerates into a temporary location and fails
   on any difference from the committed artifacts, printing the diff. Added to
   `verify` beside `docs-check`.
5. **`scripts/check-doc-consistency.sh`** — same shape as
   `scripts/check-doc-links.sh`. It asserts:
   - every control's status in `COMPLIANCE.md` §2 agrees with the ROADMAP's
     "still unenforced" / "controls now enforced" prose;
   - no increment the ROADMAP shows as not-started (`📋`/`💭`) has a brief whose
     status is `CLOSED` or `IMPLEMENTED`;
   - the ROADMAP's stated increment count agrees with the briefs backlog.
   Failure messages name the two documents and the disagreeing values.

   **Which copies it reads:** the ones in the working tree it is run from — i.e.
   `main`'s. `docs/briefs/` and `docs/audits/` on `main` are Master-Control-synced
   snapshots of `meta`; the script compares `main` against itself and never
   reaches across branches. A cross-branch check would fail in a shallow CI
   checkout and would make the build depend on a branch the PR does not contain.
   Consequence you must not "fix": a brief may read `IN PROGRESS` on `meta` and
   `READY` on `main` — including **this** brief, right now. That is expected
   drift between a live copy and a snapshot, not a finding.
6. **`DOMAIN_MODEL.md` §3's ASCII diagram is deleted** and replaced by the
   generated artifact, with a line stating it is generated and by what.

### Out — and why

| Out of scope | Reason |
|---|---|
| Any hand-drawn or hand-adjusted diagram | RULE 1. If the generator's output is ugly, improve the generator |
| The `clofin-trace` repository, fixtures, the capture harness | TASK-007 |
| The PR-015 approval-queue wireframe | Tier 1.5, `docs/design/`, separate |
| Styling beyond legibility | A diagram nobody can read fails its purpose; a beautiful one is not this brief's job |
| Diagrams of anything without a machine-readable source | RULE 1 has no exception. A diagram of something only prose describes would be a hand-drawn diagram with extra steps |

## ⚠️ The ordering trap — read this before you touch `verify` (amendment A2)

> **Released at dispatch (2026-08-05).** The sync landed in PR #10 (`64ceef0`);
> `main`'s ROADMAP shows increments 3, 4, 4c and 5 as `CLOSED` and its controls
> prose agrees with `COMPLIANCE.md`. You may wire the script into `verify` in the
> same PR. **Confirm it yourself before you do** — the paragraph below is the
> check to run, kept because a released trap is still a trap if the release was
> asserted rather than verified (L-14). If what you find disagrees with this
> note, the tree wins and the note is the defect: stop and report.

**`check-doc-consistency.sh` must not enter `verify` until the `meta` → `main`
sync has landed.** Added before it, its first run fails on exactly the staleness
it exists to catch, and you will have given yourself a red build on work that is
correct.

Verify the sync has merged (`main`'s ROADMAP shows increments 3–5 as `CLOSED`,
and its "controls" paragraph agrees with `COMPLIANCE.md`) **before** wiring the
script into `verify`. If it has not, build the script, commit it, run it
manually, and say plainly in your REQ that the `verify` wiring is deferred —
do not reorder around it, and do not "fix" the ROADMAP yourself: `main`'s
governance copies are synced from `meta` by Master Control, never edited in
place (AGENT_HANDOFF §1).

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given the committed lifecycle diagram, when `clofin.payments.state/transitions` gains or loses a pair and the diagram is not regenerated, then `make diagrams-check` fails naming the difference. | RULE 1, L-4 |
| AC-2 | Given no source change, when the generator runs twice, then both outputs are byte-identical. | determinism |
| AC-3 | Given the generated lifecycle diagram, then every state, every event and every permitted pair in `transitions` appears, and nothing that is not in `transitions` appears — asserted by comparing the parsed diagram with the table, **in both directions** (L-6). | RULE 1, L-6 |
| AC-4 | Given the control map, then every control in `COMPLIANCE.md` §2 appears with its actual status; a control added to COMPLIANCE without regenerating fails the check. | L-15 |
| AC-5 | Given `COMPLIANCE.md` marking a control ✅ while the ROADMAP lists it unenforced, when `check-doc-consistency.sh` runs, then it fails naming both documents and the control. **Verify by injecting the 2026-08-05 contradiction and watching it fail**, then reverting. | L-15 |
| AC-6 | Given a brief with status `CLOSED` whose increment the ROADMAP shows as not-started, then the check fails. **Increment ids are unique strings, not integers** — `5v.1` and `5v.2` exist deliberately, so parse the column as an opaque key and do not assume it is numeric or that it sorts. Ids were made distinct rather than the parser made tolerant: an ambiguous key is a defect in the data, not a case for the reader to handle. | L-15 |
| AC-7 | Given `make verify`, then it runs `docs-check`, `diagrams-check` and `check-doc-consistency` — subject to the ordering trap above. | — |
| AC-8 | `DOMAIN_MODEL.md` §3 contains no hand-maintained diagram, and its generated replacement states its source. | RULE 1 |

## Definition of done

- [ ] Every acceptance criterion has a named test or a named check
- [ ] AC-5's negative control was actually run — injected contradiction, observed failure, reverted. Say so in the REQ
- [ ] `make verify` and `make test-it` green
- [ ] No new runtime dependency; no Node anywhere
- [ ] Generated artifacts committed and reproducible from a clean checkout
- [ ] Completion reported — PR against `main`, REQ filed as `006-REQ-…`, provenance header (model, effort, date), and **a plain statement of whether any verification is still in flight (L-9)**

## Notes for whoever picks this up

**This closes half a lesson, not a whole one.** L-4's incident was three-way:
an acceptance criterion (prose) contradicted the lifecycle table, which
contradicted the drawing. You are closing **diagram-vs-table**. Prose-vs-table
remains a human check, and L-4 is narrowed rather than retired. Do not write
anything claiming the lesson is closed.

**Determinism is the whole value.** A check that fails intermittently gets
disabled within a month, and then the drawing drifts again with a green build
next to it. Sorted iteration, stable identifiers, no timestamps, no map-order
dependence.

**The consistency check is a guard, not a formatter.** It reports disagreement;
it never edits either document. A script that "fixes" the ROADMAP would make the
ROADMAP agree with COMPLIANCE while both were wrong.
