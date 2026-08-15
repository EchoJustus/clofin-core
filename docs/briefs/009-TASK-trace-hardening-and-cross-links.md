# TASK-009: Trace hardening, and the cross-links that make the walkthrough findable

| Field | Value |
|---|---|
| **Increment** | 5v.3 (visual layer, follow-up) — spans both repositories, small |
| **Status** | `CLOSED` — merged 2026-08-15: `clofin-core` PR #17 (`ddf39c1`), `clofin-trace` PR #2 (`bc0017c`). No objections; negative controls independently re-run by Master Control before merge. **Note:** the task was accidentally dispatched twice; the second session's duplicate PR (`clofin-trace` #3, branch `…9grdy8`, no REQ, no companion PR) was closed unmerged with the reason commented on it |
| **Depends on** | TASK-007 ✅ closed; the walkthrough live at `https://echojustus.github.io/clofin-trace/` |
| **Base branch** | `clofin-core` `main` at `501556e` or later; `clofin-trace` `main` at `71cb13f` or later |
| **Requirements** | Driver D5; the seam observation from TASK-007's ingestion (below) |
| **Controls touched** | None |
| **Scope** | Small — one sitting |
| **Audit** | Not yet submitted |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

Two loose ends from the visual layer, both small, neither optional.

**The seam.** At TASK-007's ingestion, Master Control tampered the *published*
fixture copy under `_site/fixtures/` after a build and both checks stayed
green: `provenance-present` validates the committed `fixtures/` and the pages
against them, and the published copy is only ever produced and deployed inside
one CI job — so the seam is theoretical today. It stops being theoretical the
first time anyone changes how the site is built or deployed, and a guard that
exists only as an argument about workflow topology is a convention, not an
enforcement point (L-13's shape, at low stakes).

**The links.** The walkthrough exists to be seen and is currently findable only
by someone who already knows the second repository exists. `clofin-core`'s
README — the most-read document in the project — does not mention it.

## Scope

### In — `clofin-trace`

1. **Close the seam inside `provenance-present`** — after the existing
   assertions, byte-compare every file under `_site/fixtures/` with its
   counterpart in `fixtures/`: a missing counterpart, an extra file or a single
   differing byte fails, naming the file. This extends check 1 of 2; **it is
   not a third check** (the O-3 ruling's boundary: it is provenance).
2. **The live URL in `README.md`** — one line, stating where the built
   walkthrough is served.

### In — `clofin-core`

3. **Link the walkthrough from `README.md`** — in the section that lists what
   the project demonstrates. The link text states what it is: a **replay
   walkthrough of captured output** at a named tag. Constraints, not
   suggestions: no "demo", no "live system", no "try it"; the sentence must
   survive being read with the link followed and the site's own scope banner in
   view without the reader feeling oversold.

### Out — and why

| Out of scope | Reason |
|---|---|
| Any change to what the pages render or how the site looks | Delivered and verified in TASK-007; this brief hardens, it does not revisit |
| A third CI check in `clofin-trace` | Scope item 7 of TASK-007 stands; the seam closes inside check 1 |
| Re-capturing fixtures | The walkthrough replays `ref-1`; nothing here changes that |
| Any code in `clofin-core` beyond the README line | Nothing else is in scope |

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given a built site with one byte changed in any file under `_site/fixtures/`, when `provenance-present` runs, then it fails naming the file; unmodified, it passes. | the seam |
| AC-2 | Given a file present in `_site/fixtures/` with no counterpart in `fixtures/` (or the reverse), then the check fails naming it. | L-6 |
| AC-3 | Given `clofin-core`'s README, then it links the live walkthrough with link text meeting the constraints in scope item 3, and `make docs-check` passes. | D5 |
| AC-4 | Given `clofin-trace`'s README, then it names the live URL. | D5 |
| AC-5 | Exactly two automated checks still exist in `clofin-trace` CI. | O-3 ruling |

## Definition of done

- [ ] AC-1's negative control actually run — tampered byte, observed failure, reverted; say so in the REQ
- [ ] Both repositories' CI green; `clofin-core`'s `make verify` untouched and green
- [ ] Two PRs, cross-referenced
- [ ] REQ filed as `009-REQ-…` with provenance header and the L-9 statement
