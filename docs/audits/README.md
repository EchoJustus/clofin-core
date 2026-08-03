# Architecture audits and feedback

> **Control plane.** This directory is maintained on the **`meta` branch** by
> Master Control. `FEEDBACK` files land here on `meta`; `REQ` files are
> *authored by Workers on their own feature branch* (that is their reporting
> channel) and copied to `meta` by Master Control at ingestion. Read the
> current state without checking out:
> `git fetch origin meta && git show origin/meta:docs/audits/<file>`.

CloFin is developed asynchronously: worker sessions implement briefs
continuously on stacked feature branches, while architecture review happens
**offline, at milestones, over batches of one to five PRs** — rather than
blocking each increment. This directory is where that review lands and becomes
durable.

**Naming:** `REQ` files are `NNN-REQ-<subject>.md` (a Worker's completion
report and audit request for `TASK-NNN`, including any objections to the brief
itself). `FEEDBACK` files are `FEEDBACK-NNN-<subject>.md`, matching the brief
they review, or a milestone name for a broader batch audit
(`FEEDBACK-M1-foundation.md`).

**Worker objections to a brief** travel in the `REQ`, never as edits to the
brief. Master Control arbitrates: correct the brief on `meta`, or answer the
objection with evidence and let the brief stand. Either way the ruling is
recorded, and if the brief changes, the owning Worker receives a specific fix
instruction naming the branch, the change, and the acceptance criteria that now
apply ([`../AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §1b).

A worker session submits its own increment for review as
`NNN-REQ-<subject>.md` — what it built, the decisions it took, the edge cases it
found, and the debt it knowingly left. The `REQ` is the *submission*; the
`FEEDBACK` is the reply. Both are kept.

> A review that exists only in a conversation does not exist. If an audit
> finding is worth acting on, it is worth committing here — otherwise the next
> session re-introduces the same anti-pattern, and the one after that re-argues
> whether it was a problem.

---

## What an audit produces

An audit is expected to state, for each finding:

| Field | |
|---|---|
| **Severity** | `blocking` · `should-fix` · `consider` |
| **Finding** | What is wrong, specifically, with a file and line where possible |
| **Why it matters** | The consequence — ideally in product or control terms, not only engineering ones |
| **Suggested direction** | Not necessarily a patch; a direction is enough |
| **Affects** | Which briefs or namespaces |

## How feedback is ingested

When a `FEEDBACK` file lands, the orchestrating session:

1. **Reads it in full** before touching any code.
2. **Triages each finding.** A finding is actioned, deferred with a stated
   reason, or disputed with evidence. All three are legitimate; silence is not.
3. **Applies blocking findings** to the code, and updates the affected brief's
   `Status` back to `IN PROGRESS` if it had been `IMPLEMENTED`.
4. **Propagates the lesson forward.** This is the part with lasting value: a
   flagged anti-pattern is added to the *Notes for whoever picks this up*
   section of every brief where it could recur, so it is prevented rather than
   re-corrected.
5. **Writes an ADR** if a finding changes a decision. An accepted ADR is never
   edited — it is superseded by a new one that links back
   ([ADR-0001](../ADR/0001-record-architecture-decisions.md)).
6. **Records the disposition** in the Register below, and sets the brief's
   `Audit` field to reference the feedback file.

A finding that is disputed must be answered with evidence — a test, a
constraint, a benchmark — not with an assertion. The project's whole claim is
that its statements are checkable; that applies to its replies to reviewers too.

## Register

| Feedback | Reviews | Received | Findings (B/S/C) | Disposition |
|---|---|---|---|---|
| *(waived)* | Increment 1 / PR #1 | 2026-08-02 | — | Deep architectural audit **explicitly bypassed** by the reviewer to unblock the pipeline, with rigorous review to be enforced from PR #2 onward. Recorded so the assurance history is honest: increment 1 was consciously not audited. |
| *(pending)* | Increment 2 / PR #2 — `001-REQ` filed by the Worker | requested 2026-08-02 | — | Awaiting `FEEDBACK-001` from the Principal Architect. PR #2 merged to `main` (`f7018a1`) ahead of the audit — reviews do not gate execution; any findings will be dispatched as fix instructions against `main`. |
| *(pending)* | Increment 3 / PR #4 — [`002-REQ`](002-REQ-payment-instruction-lifecycle.md) | requested 2026-08-03 | — | Eight Worker objections triaged same day; rulings in the brief's changelog. O-3 fix ordered **and applied** (`f529663`). **Milestone batch audit deferred until after TASK-003** by decision of 2026-08-03 — FEEDBACK-001/002/003 expected as one batch review. |
| *(pending)* | Increment 4 / PR #5 — [`003-REQ`](003-REQ-authorisation-and-audit-trail.md) | requested 2026-08-03 | — | Four Worker objections triaged same day; rulings in [TASK-003's changelog](../briefs/003-TASK-authorisation-and-audit-trail.md). O-1 fix ordered **and applied** (`6f58857`, migration `0006`); O-2 resolution ratified; lessons L-1 widened, L-3 and L-4 added. **TASK-003 is implemented, so the deferred milestone batch audit is now unblocked** — FEEDBACK-001/002/003 awaited as one batch. |
| `FEEDBACK-M1-foundation` *(received — ingestion in progress)* | Milestone 1 batch — 001-REQ, 002-REQ, 003-REQ, plus the never-audited increment-1 substrate | commissioned 2026-08-03 · received 2026-08-03 | 2 B / 4 S / 0 C reported | Executed via the **CodeSpace path** (external agent, read-only clone; transcript archived in the bridge at `audit/chats/20260803-01-first-audit.json`). Deliverable in the bridge inbox; full text pending transfer to Master Control — the file itself is copied here at completion of ingestion. **Both blocking findings independently verified by Master Control on 2026-08-03: F-001** (maker–checker bypass: `submit` is not creator-restricted, so an actor holding operator+approver can submit another's draft and then approve it — `api/payments.clj` `transition-handler` has no provenance check, and `evaluate` compares `created-by` only, whose "creator = submitter" premise was documented but unenforced) **and F-002** (`TRUNCATE` bypasses the row-level `reject_mutation()` triggers — reproduced empirically: `truncate audit_event` succeeds as the app role; also affects `journal_entry`/`journal_line` on `main` and `approval`'s no-delete guard; a `BEFORE TRUNCATE FOR EACH STATEMENT` trigger on the same function was verified to refuse). **Decision: PR #4 and PR #5 merges are BLOCKED** pending remediation on PR #5's branch; brief 003 returned to `IN PROGRESS`; remediation dispatched to the TASK-003 Worker. Lessons L-5 and L-6 added. The four should-fix findings are triaged when the full file is transferred. |

### Submissions awaiting review

| Submission | Covers | Submitted | Status |
|---|---|---|---|
| [001-REQ](001-REQ-ledger-persistence-and-account-api.md) | [TASK-001](../briefs/001-TASK-ledger-persistence-and-account-api.md) | 2026-08-02 | Awaiting audit |
| [002-REQ](002-REQ-payment-instruction-lifecycle.md) | [TASK-002](../briefs/002-TASK-payment-instruction-lifecycle.md) | 2026-08-03 | Awaiting audit — objections ruled, O-3 fix applied (`f529663`) |
| [003-REQ](003-REQ-authorisation-and-audit-trail.md) | [TASK-003](../briefs/003-TASK-authorisation-and-audit-trail.md) | 2026-08-03 | Awaiting audit — objections ruled, O-1 fix applied (`6f58857`) |

## Standing lessons

Anti-patterns confirmed by audit, to be avoided in all future work. Each entry
names the brief section that now guards against it, so the guard can be checked.

| # | Anti-pattern | Guarded by |
|---|---|---|
| L-1 | A brief pre-assigns a sequence number owned by another artifact series without checking the live sequence (TASK-002's DoD named UAT-003, which TASK-001 had already consumed; TASK-003 renumbered its migration for this very reason and *still* hard-coded UAT-004, which TASK-002 owned — surfaced by 003-REQ O-3). | Brief authoring: reference **every** sequentially-numbered series — migrations, UAT scripts, ADRs, briefs, audits — by *next available number* or by name, never by a hard-coded number, and verify each one against the tree the Worker will actually build on, including unmerged branches in the stack. Widened from migrations-only 2026-08-03. |
| L-2 | Specifying replay protection as a digest of "the request body" alone scopes the guarantee too narrowly: identical bodies on different endpoints or resources collide, and a replayed response silently substitutes for work never done. Canonical digests include method and path. | Brief 002's idempotency section, as amended by ruling O-3. Any future brief specifying idempotency copies that wording. |
| L-3 | A brief ships DDL that its target engine cannot honour as written: TASK-003 declared a nullable column inside a primary key, which PostgreSQL silently forces `NOT NULL`, making the brief's own documented null-currency row uninsertable (003-REQ O-1; corrected by ruling — `unique nulls not distinct`, migration `0006`). | Brief authoring: **execute every specified migration against a live PostgreSQL of the target version before dispatch**, and insert one row of every documented shape — a comment describing data the schema cannot hold is a defect the Worker inherits. |
| L-4 | A brief's acceptance criterion demands behaviour unreachable under an interface the same author specified elsewhere: TASK-003's AC-7 required amending an `approved` instruction while TASK-002's lifecycle table carried no such arrow, and `DOMAIN_MODEL.md`'s rule 3 contradicted its own diagram (003-REQ O-2; ruled in AC-7's favour, ADR-0014 amendment 1 ratified). | Brief authoring: cross-check every AC against the state tables, diagrams and interfaces it exercises — in this brief, its dependency stack, and the domain model — before dispatch. A Worker who finds such a contradiction files it as an objection for arbitration; it is never resolved silently in either direction. |
| L-5 | Append-only enforcement was specified and tested against `UPDATE` and `DELETE` only; `TRUNCATE` — a distinct verb with its own trigger event and privilege — physically emptied the audit table past every guard (FEEDBACK-M1 F-002, reproduced empirically by Master Control). | Any brief claiming a table is append-only enumerates the engine's **full** destructive verb set — `UPDATE`, `DELETE`, `TRUNCATE` — with a trigger per verb and a raw-SQL test per verb; and states plainly that triggers do not bind a schema-owner adversary, so the runtime role split (app role ≠ owner, `TRUNCATE`/DDL revoked — foreshadowed in migration 0002's own comment) is named debt until built. |
| L-6 | A control rested on an identity invariant that was documented but enforced nowhere: C-01's `evaluate` compares only `created-by`, justified by "creator and submitter cannot be different actors" — a docstring claim with no code, schema or test behind it, so any operator could submit another's draft and, holding both roles, approve what they submitted (FEEDBACK-M1 F-001, verified by Master Control). | Brief authoring and review: every premise a control's enforcement point *relies on* is traced to its own enforcement point — code, constraint, or test. A load-bearing sentence in a docstring is not an enforcement point. |

Until an audit populates this table, the standing constraints are the ones in
[`../AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §3 — the rules that must not be
broken.
