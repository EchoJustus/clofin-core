# TASK-014: Cockpit phase 4 — the Actions scenario runner, without a token

| Field | Value |
|---|---|
| **Increment** | 8.4 (cockpit, phase 4) — **`clofin-cockpit` only; `clofin-core` remains frozen** |
| **Status** | `CLOSED` — cockpit PR #4 merged (`9283dbf`), REQ-only core PR #27 merged (`ea428a3`), and the hosted dispatch against `ref-1` run and verified (run #1, success) — the merge-then-verify ruling completed, see Changelog |
| **Depends on** | TASK-013 ✅ closed — the flow documents and the runner this reuses |
| **Base branch** | `clofin-cockpit` `main` at `7ee7e28` or later; `clofin-core` at `f533897` is **cloned and driven inside the workflow, never edited** — its only change is the `014-REQ` file |
| **Requirements** | Driver D5; the cockpit plan (Phase 4, in the PAT-free form ruled at dispatch) |
| **Controls touched** | None |
| **Scope** | Medium–Large |
| **Audit** | `014-REQ` filed 2026-08-15; its O-1 joins the post-audit backlog beside 013's O-1/O-2 |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

The interactive walk needs a human, a local stack and twenty minutes. After
this task the same walk runs as a **batch**: a `workflow_dispatch` in
`clofin-cockpit` checks out `clofin-core` at a named ref, boots it against a
real PostgreSQL, executes a declared scenario end to end, and writes the
evidence — every step, every raw status, every balance in verbatim minor units
— into the **publicly readable job summary** of a public repository. One-click
scenario evidence against any tag, forever reproducible, and the tool that
will re-verify `ref-2` the day it exists.

**The PAT-free rule, decided at dispatch:** the cockpit site holds no token in
this phase either. Dispatching the workflow is done on github.com (the site
links to the workflow page and says exactly that); reading results uses only
what a public repository serves anonymously. The README's rule 3 stays
forward-tensed. The phase that introduces a token remains its own, later
decision.

## Scope — all in `clofin-cockpit`

1. **`scenario-run.yml`** — `workflow_dispatch` with inputs: `ref` (a
   `clofin-core` tag or SHA; default `ref-1`), `scenario` (one of the shipped
   flow documents), `seed` (a shipped seed profile). The job checks out
   `clofin-core` at exactly that ref (full SHA resolved and echoed), boots
   PostgreSQL and the service — `make up` if the runner supports it, the
   012/013 host-run fallback if not, stated either way in the summary —
   migrates, seeds, and executes the scenario.
2. **The same runner, headless.** A Node entry point drives the *same* profile
   reader and runner modules the browser uses — `role`, the four-state
   vocabulary, the actor gate, `figures.ts` — with two declared differences,
   recorded in a cockpit ADR (`docs/ADR/0004-…`):
   - **manual steps**: the workflow is the operator here, so it runs the
     generated SQL itself via `psql` — and still confirms through the API,
     marking the step *performed by the workflow, confirmed by the instance*;
   - **choice steps**: answered from a **playbook** — a versioned JSON document
     declaring every choice in order. The interactive page's one-click-per-
     response rule is about a human demo; a batch run's honesty is different:
     every choice is **declared before the run in a reviewable file**, and the
     summary renders each as *declared → performed → instance answered*.
     A scenario with no playbook for a choice halts `waiting for you`, exactly
     as the browser does — the batch never invents an answer. Ship one
     playbook: the full 013 scheme-play sequence (settle, duplicate, return,
     contradiction, silence, sweep, late answer).
3. **The evidence summary** (`GITHUB_STEP_SUMMARY`): opens with the verbatim
   scope statement and the resolved ref + full SHA (and its release-audit
   coverage when the ref is a tag with one — matched from the release body,
   the 011 rule); then every step with its actor, raw status, and figures
   **verbatim in minor units** — no formatter, no arithmetic, the same
   `figures` discipline, enforced by reusing the module rather than by a
   parallel rule. Refusals render as the expected outcomes they are.
4. **The site's "batch runs" page**: what the runner is, the honesty rules it
   follows, a link to the workflow page with "run it on github.com" wording,
   and — **only if** the anonymous API serves it for public repositories,
   verified rather than assumed — a list of recent runs with their
   conclusions. If anonymous listing is not served, render the link and say
   why the list is absent. Never a token prompt.
5. **Two CI checks stand**, extended over the new page and documents. The
   build guard's properties unchanged; the workflow file is exempt from the
   site's origin policy (it is not site code) but its own steps pin actions
   by SHA and fetch nothing beyond GitHub and the toolchain registries.

### Out — and why

| Out of scope | Reason |
|---|---|
| **Any `clofin-core` change beyond the REQ file** | Frozen until the 2026-09-01 audit; a needed core change is a dispatch-blocked objection |
| Any PAT/token in the site, including for dispatch or artifact reads | The PAT-free rule above; the token phase is its own later decision |
| Scheduled/cron runs | A scenario run is a deliberate act with a person behind it; unattended runs against tags prove nothing new and burn minutes |
| Auto-play in the **browser** | Unchanged from 013 — the playbook is a batch-run construct only |
| A third CI check | Standing rule |

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given a dispatch against `ref-1` with the shipped seed and scheme-play playbook, then the run completes; the summary carries scope statement, resolved full SHA, `PARTIAL` coverage, and every step's actor, raw status and verbatim figures. | the runner |
| AC-2 | Given the summary's figures, then each appears verbatim in a response body captured in the run log — asserted by the run itself re-checking, not by review. | figures discipline |
| AC-3 | Given a scenario reaching a choice the playbook does not answer, then the run halts `waiting for you` and fails the job with the step named — never inventing an answer. | honesty |
| AC-4 | Given a manual step, then the summary shows the SQL that was run **and** the API confirmation the step's status rests on. | TASK-012 pattern |
| AC-5 | Given the workflow file, then `clofin-core` is checked out read-only at the resolved SHA and nothing in the job writes to any repository. | frozen core |
| AC-6 | Given the batch-runs page, then dispatch is described as a github.com action, no token is requested anywhere, and the recent-runs list either renders from anonymous reads or states why it is absent — verified, not assumed. | PAT-free rule |
| AC-7 | Both CI checks green and still exactly two; the only `clofin-core` diff is `docs/audits/014-REQ-…`. | standing rules |

## Definition of done

- [ ] Every AC has a named test, check, or a real dispatched run as evidence
- [ ] At least one full real dispatch against `ref-1` linked in the REQ, its summary quoted
- [ ] Cockpit ADR-0004 records the headless differences and their reasons
- [ ] Two PRs, cross-referenced; the core PR is the REQ alone
- [ ] REQ filed as `014-REQ-…` with provenance header, objections, and the L-9 statement

---

## Changelog — rulings and the hosted-run verification (2026-08-15)

*(The REQ is `docs/audits/014-REQ-cockpit-scenario-runner.md` on `main`, landed
alone in PR #27, `ea428a3` — one file, verified at ingestion.)*

**The dispatch chicken-and-egg, ruled at the Worker's stop-point:**
merge-then-verify, the Pages precedent. GitHub registers a `workflow_dispatch`
only on the default branch — checked by the Worker two ways (a parsed-valid
workflow absent from the registered list) rather than inferred from the `404`.
Master Control's own dispatch-note workaround was wrong on this point and the
record says so.

**O-1 (an organisation cannot be found by short name; a batch run therefore
requires a fresh database): confirmed, disposition ratified.** The remedy is an
**authenticated** lookup, not an enriched unauthenticated `409` — the id in a
`409` served to an anonymous caller is a disclosure decision, and the Worker
called it one. Routed to the post-audit backlog beside 013's O-1/O-2.

**The provenance row's recorded absence is accepted.** The Worker's session
rule keeps its model identifier out of repository artifacts; writing "known and
withheld, and why" beats both silent omission and breaking the session's rule.
The REQ series' provenance convention now admits that form.

**The hosted run — dispatched and verified by Master Control:**
run #1, <https://github.com/EchoJustus/clofin-cockpit/actions/runs/31903726901>,
`workflow_dispatch` with `ref-1` / `scheme-play` / `uat-standard`, conclusion
**success** in 51s. Verified from the run's own logs: the ref resolved to
`5c7b4badced5e807e1022fce44cbcad38c6d2095` in full; **all 27 steps `done` in
the expected order** — the creator's `403`, the contradiction's `409`, silence
sending nothing, the sweep, the late answer — ending `every document finished
and every figure is the instance's own`, which is the figures re-check passing
(the job fails otherwise); **`make up` ran the composed stack**, building
`infra/Dockerfile` on the hosted runner (§6c item 2, previously unexercised in
every sandbox); **`anonymous: releases 200, tags 200`** — the coverage line was
computed from the published release body (§6c item 4, the one criterion whose
evidence only the hosted run could produce); and **`anonymous read probe:
200 OK … no credential of any kind`** — the anonymous GitHub read path,
inferred-but-unverified since TASK-011, is now settled by the run itself.
One residual is the operator's single glance: the rendered summary page (the
literal `PARTIAL — charter items 1-4 of 8` line and the balance table) is
rendered HTML the API does not serve; `summary.test.ts` pins the rendering
against the recorded release body, and the live read succeeded, so the residual
is presentational, not evidentiary.
