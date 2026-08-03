# FEEDBACK-M1: Foundation, Ledger, Payments, Authorisation and Audit

| Field | Value |
|---|---|
| **Status** | Final external Principal Architect handoff for Main Control ingestion |
| **Date** | 2026-08-03 |
| **Naming basis** | `docs/audits/README.md` explicitly commissions `FEEDBACK-M1-foundation.md`; no filename fallback was used. |
| **Delivery** | Staged out-of-band for Main Control to ingest into `docs/audits/` on `meta`; this audit did not modify `meta`, source, migrations, tests, or PR branches. |
| **Audit posture** | Read-only review. All executed probes used only the isolated local PostgreSQL container and synthetic data, which was removed after each probe. |
| **Audited refs** | `main` `7024454`; PR #4 / TASK-002 `f529663`; PR #5 / TASK-003 `6f58857`; authoritative briefs and REQs from `origin/meta` `9451636`. |
| **Result** | 2 `blocking`, 4 `should-fix`, 0 `consider` findings. |

> **Ingested by Master Control 2026-08-03** (CodeSpace delivery path; transcript
> archived in the bridge at `audit/chats/20260803-01-first-audit.json`). Both
> blocking findings were independently verified before this file arrived —
> F-001 in source, F-002 reproduced empirically, including the guard's
> effectiveness. Triage disposition: **all six findings actioned**, none
> disputed, none deferred; consolidated remediation dispatched to the TASK-003
> Worker on PR #5's branch. PR #4 and PR #5 merges stay blocked until F-001 and
> F-002 land green. Register row and standing lessons L-5…L-8:
> [`README.md`](README.md).

## Scope and Method

This milestone audit covers the never-audited foundation substrate plus
REQ-001, REQ-002 and REQ-003. It reviewed the implementation stack, migrations,
API routes, OpenAPI contract, ADRs, REQ submissions, tests, PR commit ranges and
the authoritative `meta` briefs.

Static analysis was corroborated by the repository test suites and three
isolated synthetic probes:

1. A fully wrapped API scenario in which a different actor submitted a draft
   and then approved it.
2. A direct SQL insert of an empty journal entry.
3. A direct SQL `TRUNCATE` of a committed audit event.

## Finding Register

| ID | Severity | Finding | Affects |
|---|---|---|---|
| F-001 | `blocking` | A submitter can approve a payment they submitted when they did not create it. | C-01, PR-010, TASK-003, `clofin.api.payments`, `clofin.authz.approval` |
| F-002 | `blocking` | `TRUNCATE` bypasses append-only triggers for audit and journal tables under the runtime database role. | C-03, C-05, TASK-001, TASK-003, schema/deployment role model |
| F-003 | `should-fix` | PostgreSQL accepts and commits a journal entry with no lines. | ADR-0008, C-04, TASK-001, migration `0002` |
| F-004 | `should-fix` | Account postability validation is nonlocking, so a freeze can interleave after validation and before posting. | TASK-001 AC-4, `clofin.ledger.repository`, payment debtor validation |
| F-005 | `should-fix` | A partial approval is recorded as `payment.approved` even though the payment remains `pending-approval`. | C-05, PR-074/075, TASK-003 audit evidence |
| F-006 | `should-fix` | Amendment invalidates approval records without emitting an approval-subject audit event. | C-05, PR-014, PR-072/075, TASK-003 |

## Findings

### F-001 - Submitter self-approval is possible

**Severity:** `blocking`

**Finding.** C-01 states that the actor who creates **or submits** a payment
must not approve it (`docs/COMPLIANCE.md:38-41`). The implementation records
only `created_by` as maker provenance. `clofin.api.payments/transition-handler`
authenticates the submitter but passes no actor to `payments/transition!`
(`src/clofin/api/payments.clj:437-456`). `evaluate` consequently rejects only
an actor equal to `instruction.created_by` (`src/clofin/authz/approval.clj:235-240`).

The inline comment claims creation and submission cannot have different actors,
but the code does not enforce that. An actor may hold both `:operator` and
`:approver` roles; the existing tests already exercise that role combination.

**Reproduction.** In the isolated, fully wrapped API fixture, actor A created a
draft. Actor B, with `:operator` and `:approver`, submitted it and then approved
it. Submission returned `200`; B's approval returned `201`; the resulting
payment status was `approved`.

**Why it matters.** This is a direct bypass of the primary maker-checker rule.
The prescribed evidence query in C-01 would detect the event after the fact,
but the control must prevent it before approval, not merely make it observable.

**Suggested direction.** Persist immutable submission provenance when the
`submit` transition succeeds, and refuse approval/rejection where the actor is
either the creator or submitter. Alternatively, enforce that only the creator
can submit, but make that workflow restriction explicit in the contract and
tests. Add API and pure-domain tests for creator A, submitter B, approver B.

**Affects.** C-01, PR-010, TASK-003 AC-1/AC-2, `clofin.api.payments`,
`clofin.payments.repository`, `clofin.authz.approval`, audit evidence.

### F-002 - Append-only controls do not prevent `TRUNCATE`

**Severity:** `blocking`

**Finding.** The journal and audit protections are row triggers for only
`UPDATE` and `DELETE`:

- `resources/migrations/0002-ledger-accounts-and-journal.sql:160-166`
- `resources/migrations/0005-authorisation-and-audit.sql:210-216`

`TRUNCATE` does not fire these `FOR EACH ROW` triggers. The project test helper
states this explicitly and uses `TRUNCATE` to reset business data
(`test/clofin/test_db.clj:39-52`). No schema or deployment rule revokes
`TRUNCATE`; the Compose application and schema owner both use `clofin`
(`docker-compose.yml`).

**Reproduction.** A synthetic `audit_event` was inserted directly under the
runtime role. Its count was `1` before `TRUNCATE audit_event` and `0` after it.
All probe data was then removed.

**Why it matters.** C-05 claims audit events cannot be altered afterwards, and
C-03 makes the equivalent claim for journal records. A routine SQL statement
available to the runtime owner can erase the whole evidence set. This is not a
malicious-superuser proof problem: the stated design specifically claims the
trigger protects against an owning-role application defect.

**Suggested direction.** Separate migration/schema ownership from the runtime
application role and grant the application only the DML it needs, explicitly
excluding `TRUNCATE` and DDL. Add `BEFORE TRUNCATE` protection as defence in
depth, and move test cleanup to a privileged test-only role or reset database.
Document that a schema owner remains inherently privileged and outside the
application control boundary.

**Affects.** C-03, C-05, TASK-001 journal immutability, TASK-003 audit
immutability, deployment security model and raw-SQL constraint tests.

### F-003 - Database permits a zero-line journal entry

**Severity:** `should-fix`

**Finding.** ADR-0008 requires a journal entry to carry two or more lines. The
domain constructor enforces this, but the database backstop does not. The
deferred constraint trigger is declared only `AFTER INSERT ON journal_line`
(`resources/migrations/0002-ledger-accounts-and-journal.sql:106-140`), so an
entry inserted with no lines schedules no deferred check. The raw-SQL tests
cover balanced, unbalanced, and multi-currency entries, but not an empty entry
(`test/clofin/db/ledger_constraints_test.clj:28-88`).

**Reproduction.** A synthetic direct SQL transaction inserted one
`journal_entry` and zero `journal_line` rows. It committed successfully; the
post-commit counts were one entry and zero lines. The synthetic rows were then
truncated from the isolated test database.

**Why it matters.** The database permits an invalid, immutable accounting
record through any path that bypasses the Clojure constructor: a migration,
maintenance action, or application defect. It has no financial movement, but
it violates the documented double-entry model and cannot be corrected through
the normal reversing-entry path.

**Suggested direction.** Add a forward migration with a deferred entry-level
constraint trigger that verifies at commit both the minimum line cardinality and
per-currency balance. Add raw-SQL integration cases for zero and one line.

**Affects.** ADR-0008, C-04, TASK-001, migration `0002`,
`clofin.db.ledger-constraints-test`.

### F-004 - Frozen-account validation has a time-of-check/time-of-use race

**Severity:** `should-fix`

**Finding.** `assert-postable!` reads referenced accounts without `FOR UPDATE`
(`src/clofin/ledger/repository.clj:155-186`). Standalone posting enters
`db/with-transaction*` without an explicit isolation level
(`src/clofin/ledger/repository.clj:126-136`; `src/clofin/db/core.clj:213-240`),
therefore uses PostgreSQL `READ COMMITTED`.

A status update may commit after `assert-postable!` reads `active` but before
the journal entry and lines commit. No database constraint rechecks account
status on line insertion. The analogous debtor-account validation in
`clofin.payments.repository` also reads status without a row lock.

**Why it matters.** TASK-001 AC-4 says frozen and closed accounts refuse
postings. Under concurrent account lifecycle work, a journal entry can post to
an account that is frozen at commit time. The current tests freeze accounts
before posting; they do not exercise this interleaving.

**Suggested direction.** Lock all referenced account rows in a stable order as
part of postability validation, and make all account-state transitions use the
same rows/transactional lock discipline. Apply the same treatment to debtor
account validation. Add a latch-based integration test that races freeze against
posting and asserts the defined serialization outcome.

**Affects.** TASK-001 AC-4, ADR-0012, `clofin.ledger.repository`,
`clofin.payments.repository`, future account lifecycle work.

### F-005 - Audit action falsely reports a non-final approval as payment approval

**Severity:** `should-fix`

**Finding.** In `decide!`, the payment transitions only when
`:completes?` is true (`src/clofin/payments/approval_service.clj:147-152`), but
the audit write always uses `payment.approved` for an approved decision
(`src/clofin/payments/approval_service.clj:157-166`). Thus the first approval
on a two-approval threshold leaves the payment `pending-approval` while creating
a `payment.approved` event whose before and after digests describe the same
payment state.

The acceptance test codifies this semantic mismatch: its evidence pack expects
two `payment.approved` actions for two approvals, although only the second
changes the instruction to `approved`
(`test/clofin/api/approvals_api_test.clj:563-579`).

**Why it matters.** C-05 says each event states what changed. An auditor or
downstream extraction filtering `action=payment.approved` cannot distinguish a
recorded approval from a payment that was actually approved. The evidence pack
is labelled as state-change history, but includes a non-state change under a
state-transition action.

**Suggested direction.** Introduce an approval-decision action and subject
representation, such as `approval.recorded`, and emit `payment.approved` only
when the payment transition commits. Adjust evidence-pack semantics and tests
so a complete payment history can include approval decisions without assigning
them the wrong payment state.

**Affects.** C-05, PR-074, PR-075, TASK-003 AC-4/AC-9/AC-12,
`clofin.payments.approval-service`, `clofin.audit/actions`.

### F-006 - Amendment invalidates approvals without auditable approval events

**Severity:** `should-fix`

**Finding.** Amending a pending or approved instruction bulk-updates every live
approval's `invalidated_at` (`src/clofin/payments/repository.clj:296-325`; the
mutation is `src/clofin/authz/repository.clj:199-209`). The API then emits only
one `payment.amended` event whose before/after digests project the payment, not
the approvals (`src/clofin/api/payments.clj:407-420`). No
`approval.invalidated` action exists in the audit vocabulary.

**Why it matters.** C-05 explicitly scopes the control to payment instructions
and approvals, with every state change emitting an event
(`docs/COMPLIANCE.md:225-231`). The current rows show that an approval stopped
standing, but do not provide an immutable approval-subject event recording who
caused the invalidation, its correlation id, or its before/after state. This
also prevents a nominated payment evidence pack from representing the full
approval lifecycle faithfully.

**Suggested direction.** Select and lock the live approvals before invalidating
them, write an `approval.invalidated` event with each approval's before/after
projection in the same transaction, and extend evidence extraction to relate
approval events to their payment. Add an amendment test that asserts both the
payment event and each approval invalidation event survive together.

**Affects.** C-05, PR-014, PR-072, PR-075, TASK-003 AC-7/AC-9/AC-12,
`clofin.payments.repository`, `clofin.authz.repository`, audit evidence.

## Traceability Summary

| Submission | Outcome |
|---|---|
| REQ-001 - ledger persistence and account API | Integer minor-unit handling, derived balances, statement ordering, reversal uniqueness, tenant-scoped reads, and normal update/delete immutability are well evidenced. F-002, F-003 and F-004 prevent an unqualified pass. |
| REQ-002 - payment lifecycle and idempotency | No new finding. The canonical method/path/body digest, composite-key replay serialization, transactionally paired effect/key, positive-amount validation, exhaustive transition matrix, and locked state changes are implemented and tested. Settlement, timeout and failure execution paths remain later-increment scope. |
| REQ-003 - authorisation and audit trail | Threshold, limit, default-deny, normal transaction rollback, and raw update/delete protections are well structured. F-001, F-002, F-005 and F-006 prevent C-01/C-05 from being described as fully enforced. |

## Positive Evidence Retained

- Money remains integer minor units in the application and `bigint` in the
  schema; no floating-point money path was found.
- The journal's per-currency zero-sum check rejects unbalanced line sets at
  commit, and posted journal rows reject ordinary `UPDATE`/`DELETE`.
- Idempotency uses `(organisation_id, key)` as the serialization point; the
  canonical digest includes method, normalized path and body. The concurrent
  duplicate-request test is genuine and the full integration suite passes.
- Payment state transitions are data-driven and row-locked for concurrent
  different-key transitions.
- Approval threshold and limit decisions are pure, default-deny, per currency,
  and use the corrected nullable wildcard constraint.
- Audit writes share the transaction of their current mutation paths; rollback
  tests correctly show neither the change nor its event surviving.

## Verification Performed

| Check | Result |
|---|---|
| `make test-it` | Passed: 422 tests, 2,333 assertions, 0 failures, 0 errors. |
| `make verify` | Passed: 219 tests, 1,252 assertions, 0 failures, 0 errors; documentation links passed for 40 Markdown files. |
| Focused `clofin.ledger.repository-test` | Passed: 25 tests, 99 assertions, 0 failures, 0 errors. |
| Synthetic submitter-approval probe | Reproduced F-001 through the fully wrapped API. |
| Synthetic empty-entry SQL probe | Reproduced F-003: one committed entry, zero lines. |
| Synthetic audit-truncate SQL probe | Reproduced F-002: one event before `TRUNCATE`, zero after. |
| Git state after audit checks | Clean tracked worktree; no source or control-plane changes made. |

## Audit Limits and Non-Findings

- This is a synthetic-data-only reference-product review. It makes no claim
  about real funds, institutional connectivity, production deployment, or
  regulatory approval.
- Authentication by `X-Actor-Id` is intentionally non-adversarial and already
  documented as such. It was not refiled as a new finding.
- Settlement, release, screening, timeout and failed-state execution are not
  implemented in the audited increments; their absence is documented deferred
  scope, not a defect attributed to REQ-002.
- The acknowledged lack of audit coverage for organisation, account and journal
  writes remains carried-forward work. F-002 is distinct: it concerns deletion
  of the audit and journal evidence that does exist.

## Required Main Control Triage

1. Treat F-001 and F-002 as blocking before C-01 and C-05 are represented as
   fully enforced.
2. Dispatch owners for F-003 through F-006 with focused regression tests and
   forward-only migrations where schema changes are required.
3. Update the affected briefs, control mapping, standing lessons and audit
   register during feedback ingestion, following `docs/audits/README.md`.