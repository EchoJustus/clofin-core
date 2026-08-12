# TASK-006: Generated diagrams, and two CI guards for document truth

| Field | Value |
|---|---|
| **Increment** | 5v.1 (visual layer, tier 0) — inside `clofin-core` |
| **Status** | `CLOSED` — merged to `main` in PR #12 (`2237a39`) 2026-08-12; all five objections ruled in the Worker's favour, see Changelog. AC-7's deferred `verify` wiring landed with the O-1 fix sync |
| **Depends on** | **ADR-0020** ✅ merged (`cbbd669`) and the `meta` → `main` sync ✅ merged (PR #10, `64ceef0`) — both satisfied; the ordering trap below is therefore **released**, see the note there |
| **Base branch** | `main` at `cbbd669` or later. Both preconditions verified by Master Control at dispatch; verify them yourself anyway |
| **Blocks** | TASK-007 |
| **Requirements** | Driver D5; lessons L-4 (drawing half), L-15 |
| **Controls touched** | None move. This is document machinery, not enforcement |
| **Scope** | Medium |
| **Audit** | `006-REQ` filed 2026-08-12 (`docs/audits/006-REQ-generated-diagrams.md` on `main`); awaiting the next milestone batch audit |

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

---

## Changelog — rulings on the `006-REQ` objections (2026-08-12)

*(The REQ is `docs/audits/006-REQ-generated-diagrams.md` on `main`, landed by
PR #12 — not on `meta`, hence no link here.)*

All five ruled the day the REQ was filed; **all in the Worker's favour**. Three
are brief-authoring or control-plane defects of Master Control's, corrected
forward. Every O-1 finding was independently reproduced by running the Worker's
guard from its branch before any ruling was made.

| # | Objection | Ruling |
|---|---|---|
| O-1 | The ordering trap was *not* released: the sync (PR #10) repaired the ROADMAP's global-state table, but the same file's per-increment sections still showed increments 3 and 5 as `📋 next` and restated 3/4/5 as `IMPLEMENTED`/`IMPLEMENTED`/`READY` while all three briefs were `CLOSED` — L-15's own incident, still live. `verify` wiring deferred per the brief's fallback; deferral pinned by a test. | **Confirmed — two Master Control defects, one incident.** (1) The staleness itself: the sync was scoped to what the 2026-08-05 repair had looked at, and nobody enumerated the second copy of the claim in the same file. (2) The released-trap note: Master Control declared the trap released after verifying the global-state table and controls prose only — a release verified against one copy of a claim, which is the partial-guard shape (L-6) applied to verification itself. Recorded as **lesson L-16**. The Worker's handling was exactly right on all four counts: took the brief's own fallback rather than stopping; did **not** narrow the check to the copy that would have made it pass; bounded the deferral with a pinning test rather than a comment; did not touch `main`'s ROADMAP. Fix executed by Master Control on `meta` (this commit) and re-synced: the five findings plus increment 4's understated `🔨` heading (L-15 — understatement is as false as overstatement) plus a sixth staleness the guard structurally cannot see — brief 001's own status field and the ROADMAP's increment-2 lines were stale *together*, so assertion 3 matched two wrong copies against each other. The pinning test is deleted and `doc-consistency` enters `verify` in the sync PR, whose green CI is the proof the repair is complete. |
| O-2 | The context topology's arrows cannot come from `ARCHITECTURE.md` §3's table — the table is a roster with no dependency direction; the direction lives in prose, and drawing from prose is forbidden by the brief's own out-of-scope table. | **Confirmed — brief defect; the Worker's resolution is ratified and is stronger than what was asked.** Nodes from the table, arrows from the `:require` clauses of `ns` forms read as data (the `purity-test` technique). The arrows are now *evidence* rather than a transcription, the caption states the consequence (reading the diagram against §3's paragraph is a check on the paragraph), and the diagram already verified something nothing else did — `clofin.audit` has no outgoing context dependency, which is what "audit is a sink" means. Recorded in ADR-0021. |
| O-3 | "The ROADMAP's stated increment count" does not exist — the ROADMAP states no count anywhere, so there is nothing to compare a backlog count to. | **Confirmed — brief defect.** The set comparison implemented (ROADMAP table ↔ backlog table ↔ brief files on disk, every direction, counts printed on failure) is the check the brief meant: L-15's incident was a set mismatch, not a count mismatch. Flagging rather than silently reinterpreting was the correct move and is what makes the substitution auditable. |
| O-4 | Assertion 2 taken literally checks one of two copies of the same claim in the same file; the Worker read every status claim and added assertion 3 (a restated brief status must match the brief), a strengthening beyond the brief's literal list. | **Confirmed — assertion 3 is in scope.** The literal reading would have shipped a guard over the copy its author was looking at — the L-6 shape, inside the increment built to close L-6's diagram half. The strengthening proved itself on first contact: three of O-1's five findings exist only because of it. |
| O-5 | `check-doc-consistency` is two files (`sh` entry point + `awk` program), not the single file the brief's "same shape as `check-doc-links.sh`" implies. | **Confirmed — two files stand.** "Same shape" meant POSIX `sh` and `awk`, zero dependencies, one `make` target — all of which hold. ~230 lines of awk inside a shell heredoc means every `$1`, `$0` and quote is escaped against the shell, which is precisely where this class of script acquires its defects. The separation is sounder engineering, not a divergence. |

**The A2 released-trap note in this brief is left as written** — it is the
defect L-16 records, and rewriting it would erase the evidence. This changelog
is the correction.
