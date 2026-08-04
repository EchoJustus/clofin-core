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
| [`FEEDBACK-M1-foundation`](FEEDBACK-M1-foundation.md) — **ingested** | Milestone 1 batch — 001-REQ, 002-REQ, 003-REQ, plus the never-audited increment-1 substrate | commissioned 2026-08-03 · received & ingested 2026-08-03 | **2 B / 4 S / 0 C** | Executed via the **CodeSpace path** (external agent, read-only clone; transcript in the bridge at `audit/chats/20260803-01-first-audit.json`). Both blockers **independently verified by Master Control before the file arrived**: F-001 in source, F-002 reproduced empirically (with the `BEFORE TRUNCATE` guard verified effective). **Triage — all six actioned, none disputed, none deferred:** F-001 → creator-only submit (satisfies C-01's "creates *or submits*" by collapsing the two; stated explicitly in the OpenAPI contract per the auditor's caveat); F-002 → `BEFORE TRUNCATE` statement triggers on all four append-only tables **plus** the test-cleanup consequence the auditor caught — `test_db.clj` deliberately truncates past the triggers, so cleanup switches to an explicit, commented disable/re-enable bypass — and the runtime role split recorded as named debt; F-003 → deferred entry-level constraint (line cardinality + balance at commit); F-004 → `FOR UPDATE` on referenced accounts in stable order + latch-based race test; F-005 → `approval.recorded` action, `payment.approved` only on the completing transition; F-006 → `approval.invalidated` events per approval, same transaction, evidence pack extended. All six land as **one consolidated remediation on PR #5's branch** by the TASK-003 Worker (findings against TASK-001 ride the stack; briefs 001/002 stay `IMPLEMENTED` — their findings are should-fix). Lessons L-5…L-8. **Remediation log below.** |

### FEEDBACK-M1 remediation log

Tracks the consolidated remediation on PR #5's branch
(`claude/authorisation-audit-trail-r5fzw3`) against each finding.

| Finding | State | Evidence |
|---|---|---|
| **F-001** maker–checker bypass | ✅ **remediated & verified** (`971d0d1`) | `:submit` is a `creator-only-events` set enforced in `payments.repository/transition!` **under the row lock, before the lifecycle** (mirroring `amend!`), so a non-creator gets `403` by any route into the repository, not just the handler. C-01's own published evidence query — the one COMPLIANCE tells an auditor to run — is now a regression assertion. Master Control re-read the enforcement path and re-ran the exploit reasoning: killed. |
| **F-002** TRUNCATE bypass | ✅ **remediated & verified** (`971d0d1`, migration `0007`) | `BEFORE TRUNCATE FOR EACH STATEMENT` on all four append-only tables reusing `reject_mutation()`. Master Control re-probed from an empty schema (0001–0007): `audit_event`, `journal_line`, `approval` refuse `TRUNCATE`; `journal_entry` refuses (FK first, trigger on cascade); `truncate … cascade` and truncate-laundered-through-`organisation` both refused. Test harness reworked to disarm only the named TRUNCATE triggers, discovered from `pg_trigger`, restoring each `tgenabled`, in one transaction — the cleanup **is** the schema-owner-adversary demonstration COMPLIANCE §4 names. |
| **F-003** zero-line journal entry | ✅ **remediated & verified** (`2053fee`, migration `0008`) | Entry-level `deferrable initially deferred` constraint trigger checking cardinality ≥ 2 **and** per-currency balance; 0002's line-level trigger untouched — the two catch different absences. Master Control re-probed from an empty 0001–0008 schema: zero-line entry refused at commit by `assert_journal_entry_complete()`, one-line refused, two balanced lines commit. |
| **F-004** freeze/post TOCTOU race | ✅ **remediated & verified** (`6d2c0a9`) | `assert-postable!` locks referenced account rows `for update` **`order by id`** inside the posting transaction; lock-order discipline documented repository-wide (instructions before accounts); a lock-order inversion and a private duplicate of `transactionally` found and removed in the process. The race test **forces** the interleaving with a latch (a freeze holding its lock across the posting) rather than hoping for it, and was verified failing on unfixed code. ADR-0012 corrected. |
| **F-005** `payment.approved` on non-final approval | ✅ **remediated & verified** (`be3289e`) | `approval.recorded` emitted for every decision; `payment.approved`/`payment.rejected` emitted **only inside the branch where the payment transition commits** — verified in `approval-service` source. C-01's published evidence query, broken by the vocabulary change, was caught, rewritten to join through `approval`, and is itself now a tested assertion. |
| **F-006** amendment invalidates approvals with no approval event | ✅ **remediated & verified** (`be3289e`) | `approval.invalidated` in the vocabulary and emitted per invalidated approval in the amendment's transaction; the pre-existing sibling gap (`approval.withdrawn` was invisible) closed with it. |

**Merge posture — MERGED & CLOSED 2026-08-04.** All six findings remediated and
independently verified; CI green on all three checks at `900ddee`. Executed:
**PR #4 merged (`31306dd`, merge commit), PR #5 retargeted to `main` and merged
(`5ff00eb`)** — SHAs preserved so the retarget stayed conflict-free. `main` folded
into `meta`; briefs 002 and 003 `CLOSED`. Increment 1 (never separately audited,
register row 1) is retroactively covered here as the substrate — its findings
(F-002/F-003/F-004 touch increment-1 code) were remediated on the increment-4
stack, so it no longer carries an outstanding-audit note.

**Rulings on the Worker's remediation open questions (2026-08-03):**

- **`:cancel` provenance — ratified as left.** Cancellation stays
  permission-gated (`:payment/cancel`), **not** creator-gated. It destroys no
  control — it reaches a terminal state and can never produce an approval — and
  `controller` holds `:payment/cancel` precisely so a non-maker can *halt* a
  payment; creator-restricting it would remove that safety valve. PR-004 names
  cancellation as a creator's act for a *draft*, which the permission model
  already serves (the creator, an operator, holds the permission). Settled, not
  open.
- **`session_replication_role = 'replica'` — ratified as F-002 residue, not a
  new finding.** Setting it requires superuser, and a superuser can already
  `DROP`/`DISABLE TRIGGER` as owner — the same adversary class L-5 names. The
  Worker's rejection of `ENABLE ALWAYS` is upheld: it diverges from the
  specified DDL and closes one superuser door in a room with no walls. The real
  fix is the runtime role split (named debt). The next batch adds
  `session_replication_role` to the COMPLIANCE §4 residue text so the debt is
  named completely.
- **A refused submission/approval leaves no audit event — deferred to its own
  brief.** C-05 scopes the trail to state *changes*; a refused attempt is not
  one, and recording attempted control violations is a distinct control
  (security-event logging: different volume, retention, likely a different
  table) — genuinely separate from TASK-005's *successful-write* coverage gap.
  Recorded as a **future-brief candidate**; not folded into this remediation.

### Submissions awaiting review

| Submission | Covers | Submitted | Status |
|---|---|---|---|
| [001-REQ](001-REQ-ledger-persistence-and-account-api.md) | [TASK-001](../briefs/001-TASK-ledger-persistence-and-account-api.md) | 2026-08-02 | **Audited** — [FEEDBACK-M1](FEEDBACK-M1-foundation.md): F-002/F-003/F-004 prevent an unqualified pass; all actioned via the stack remediation |
| [002-REQ](002-REQ-payment-instruction-lifecycle.md) | [TASK-002](../briefs/002-TASK-payment-instruction-lifecycle.md) | 2026-08-03 | **Audited** — [FEEDBACK-M1](FEEDBACK-M1-foundation.md): no new findings |
| [003-REQ](003-REQ-authorisation-and-audit-trail.md) | [TASK-003](../briefs/003-TASK-authorisation-and-audit-trail.md) | 2026-08-03 | **Audited** — [FEEDBACK-M1](FEEDBACK-M1-foundation.md): F-001/F-002/F-005/F-006; all remediated, merged in PR #5 |
| [005-REQ](005-REQ-audit-coverage-completion.md) | [TASK-005](../briefs/005-TASK-audit-coverage-completion.md) | 2026-08-04 | **Ingested** — PR #6 open & green; three objections all ruled in the Worker's favour (rulings in [TASK-005's changelog](../briefs/005-TASK-audit-coverage-completion.md)); no fix issued. Candidate for the next milestone batch audit alongside TASK-004 |

## Standing lessons

Anti-patterns confirmed by audit, to be avoided in all future work. Each entry
names the brief section that now guards against it, so the guard can be checked.

| # | Anti-pattern | Guarded by |
|---|---|---|
| L-1 | A brief pre-assigns a sequence number owned by another artifact series without checking the live sequence (TASK-002's DoD named UAT-003, which TASK-001 had already consumed; TASK-003 renumbered its migration and *still* hard-coded UAT-004; TASK-005's DoD said the REQ takes the "next available" number, 004, which belongs to the in-flight TASK-004 — 005-REQ O-2). | Brief authoring: never hard-code a number from any sequentially-numbered series, and **name the series' numbering discipline explicitly, because it differs** — the **audits `REQ` series is task-keyed** (`NNN-REQ` reports on `TASK-NNN`), while migrations, UAT scripts and ADRs are **next-available** against the live tree (including unmerged branches in the stack). Saying "next available" for the task-keyed REQ series is itself the bug. |
| L-2 | Specifying replay protection as a digest of "the request body" alone scopes the guarantee too narrowly: identical bodies on different endpoints or resources collide, and a replayed response silently substitutes for work never done. Canonical digests include method and path. | Brief 002's idempotency section, as amended by ruling O-3. Any future brief specifying idempotency copies that wording. |
| L-3 | A brief ships DDL that its target engine cannot honour as written: TASK-003 declared a nullable column inside a primary key, which PostgreSQL silently forces `NOT NULL`, making the brief's own documented null-currency row uninsertable (003-REQ O-1; corrected by ruling — `unique nulls not distinct`, migration `0006`). | Brief authoring: **execute every specified migration against a live PostgreSQL of the target version before dispatch**, and insert one row of every documented shape — a comment describing data the schema cannot hold is a defect the Worker inherits. |
| L-4 | A brief's acceptance criterion demands behaviour unreachable under an interface — or forbidden by a constraint — the same author specified elsewhere in the brief: TASK-003's AC-7 required amending an `approved` instruction the lifecycle table did not allow (003-REQ O-2); TASK-005's AC-1 required an OpenAPI description change its own DoD's "no OpenAPI changes" line forbade (005-REQ O-1). | Brief authoring: cross-check every AC against the state tables, diagrams, interfaces **and the brief's own Definition-of-Done checklist** — a DoD item is an interface an AC can contradict. Do this before dispatch. A Worker who finds such a contradiction files it as an objection for arbitration; it is never resolved silently in either direction. |
| L-5 | Append-only enforcement was specified and tested against `UPDATE` and `DELETE` only; `TRUNCATE` — a distinct verb with its own trigger event and privilege — physically emptied the audit table past every guard (FEEDBACK-M1 F-002, reproduced empirically by Master Control). | Any brief claiming a table is append-only enumerates the engine's **full** destructive verb set — `UPDATE`, `DELETE`, `TRUNCATE` — with a trigger per verb and a raw-SQL test per verb; and states plainly that triggers do not bind a schema-owner adversary, so the runtime role split (app role ≠ owner, `TRUNCATE`/DDL revoked — foreshadowed in migration 0002's own comment) is named debt until built. |
| L-6 | A control rested on an identity invariant that was documented but enforced nowhere: C-01's `evaluate` compares only `created-by`, justified by "creator and submitter cannot be different actors" — a docstring claim with no code, schema or test behind it, so any operator could submit another's draft and, holding both roles, approve what they submitted (FEEDBACK-M1 F-001, verified by Master Control). | Brief authoring and review: every premise a control's enforcement point *relies on* is traced to its own enforcement point — code, constraint, or test. A load-bearing sentence in a docstring is not an enforcement point. |
| L-7 | An audit action named after a state transition was emitted for a decision that did not cause one: the first of two required approvals wrote `payment.approved` while the payment stayed `pending-approval`, so evidence filtered by action misstates when the state was reached (FEEDBACK-M1 F-005; F-006 is the mirror — a real approval state change, invalidation, emitted no approval-subject event at all). | Audit vocabulary design: an action named `<subject>.<transition>` is emitted **only** in the transaction where that transition commits; decisions, partial progress and side effects get their own terms. Applies immediately to TASK-004's settlement vocabulary (batch outcomes vs item outcomes) and every vocabulary extension after it. |
| L-8 | A validation that gates a write read its facts without locking them: `assert-postable!` checked account status without `FOR UPDATE` under `READ COMMITTED`, so a freeze could commit between the check and the posting — validate-then-write is a race unless the validated rows are locked (FEEDBACK-M1 F-004). | Any brief specifying a precondition that database state must satisfy at write time also specifies the lock: referenced rows locked `FOR UPDATE` in a stable order inside the writing transaction, and a race test (latch-based, two sessions) asserting the serialisation outcome. |

Until an audit populates this table, the standing constraints are the ones in
[`../AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §3 — the rules that must not be
broken.
