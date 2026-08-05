# FEEDBACK-M2: Settlement and Audit Coverage

| Field | Value |
|---|---|
| **Date** | 2026-08-04 |
| **Status** | Final external Principal Architect handoff for Master Control ingestion |
| **Audited code ref** | `main` `cba31c5082d73ec3de268f0eb21ea56e9a945247` |
| **Authoritative control-plane ref** | `origin/meta` `d68d912cff37bd7d3808cf9ef36202794bde1c6a` |
| **PR #6 range** | `5ff00eb7eaf4ead59857f1c0dfa9fff0178f0166..2ba977e1ab279b2028d1cc733b0140e8c906e379` — TASK-005, 21 files, 1,933 insertions, 86 deletions |
| **PR #7 range** | `2ba977e1ab279b2028d1cc733b0140e8c906e379..cba31c5082d73ec3de268f0eb21ea56e9a945247` — `b21d4c1` TASK-005 tail then `55566ad` TASK-004, 38 files, 5,247 insertions, 81 deletions |
| **Method** | Three sessions: evidence capture → structured analysis and candidate register → independent verification and report |
| **Audit posture** | Repository read-only. All audit artifacts live under `~/m2-audit/`; every database probe used synthetic data in an isolated PostgreSQL 16 project. No push, branch, commit, or tracked-file edit |
| **Result** | **1 blocking / 4 should-fix / 0 consider** verified findings; 1 candidate refuted and dropped |
| **Verification record** | `m2-verification-log.md` (bridge workpapers, `audit/workpapers/`) — command, output, and verdict for every Session 2 candidate |

> **Ingested by Master Control 2026-08-04** (CodeSpace path; transcript in the
> bridge at `audit/chats/20260804-01-M2-audit.json`). All five findings
> independently verified in source by Master Control before triage; the refuted
> candidate C-06 is recorded and not actioned. **Triage: all five actioned,
> none disputed, none deferred.** Rulings — including the F-007 retry-model
> ruling (returned is terminal; a retry is a new instruction; the index
> tightens) — in [TASK-004's changelog](../briefs/004-TASK-settlement-simulation.md).
> Brief 004 reopened `IN PROGRESS`; brief 005 stays `CLOSED` with F-011 as a
> recorded condition. Lessons L-10…L-13 adopted. `ref-1` is gated on this
> remediation. Register row: [`README.md`](README.md).

## 1. Verdicts

### TASK-004 — REMEDIATION-REQUIRED

The settlement implementation has strong foundations: ADR-0018's accounting
entries agree with `DOMAIN_MODEL.md` §4 and the posting templates; the
no-double-settlement index works; all five forced concurrency schedules
serialized under the documented batch → ordered instruction → ordered account
lock discipline; finality, status derivation, and L-7 event counts held under
those schedules.

It nevertheless does not meet an explicit acceptance criterion. A returned
membership is freed by `settlement_item_live_key`, but the returned instruction
is terminal and cannot enter the public batch workflow again (**F-007,
blocking**). Two response-evidence defects also remain: a rejected response is
rolled back and can later perform work under the same reference (**F-008**), and
the replay key/body omit effect-bearing outcome and reason (**F-009**). Finally,
the new append-only response table repeats L-5's test-coverage shape: its guards
work, but removing the UPDATE/DELETE trigger leaves the focused control suites
green (**F-010**).

TASK-004 should return to `IN PROGRESS` until F-007 is resolved and F-008 through
F-010 are triaged and actioned, deferred with an explicit control rationale, or
disputed with counter-evidence.

### TASK-005 including its tail — APPROVED-WITH-CONDITIONS

The TASK-005 product path is substantially complete. Organisation, account, and
journal-entry writes emit events through the HTTP routes; their committed and
rolled-back pairs pass, including deferred database rejection. Evidence packs
for organisation, account, journal entry, and payment are retrievable and
organisation-scoped. The bootstrap null is enforced. The action and subject
vocabularies match the OpenAPI contract.

The L-9 process failure's **technical consequences are fully closed at this
tip**. `b21d4c1` is an ancestor of `cba31c5`; both `AuditEvent.subjectType` and
`EvidencePack.subjectType` contain all six terms; the fixed contract test
discovers every schema-level `subjectType` copy; an external negative control
that restored the stale EvidencePack enum made that test fail; and the focused
contract and API evidence-pack suites pass. This closes the false contract, the
partial drift guard, and the carried documentation corrections identified by
the TASK-005 tail. It does not erase L-9 as a process lesson: merge must still
wait for declared verification to finish.

One architectural condition remains. The ledger service functions document a
caller-owned transaction but do not reject a pool. Direct invocation can commit
an account or journal entry before audit validation fails, leaving no event
(**F-011, should-fix**). Current HTTP handlers pass a transaction correctly, so
this is not a reproduced front-door loss; it does disprove the stronger claim
that the service shape makes an unaudited state change unrepresentable. TASK-005
is therefore approved with the condition that F-011 be made mechanically
fail-closed or the internal service contract and C-05 boundary be narrowed
explicitly.

### Consolidated Milestone 2 disposition — REMEDIATION-REQUIRED

Milestone 2 cannot receive an unqualified pass while TASK-004 has a blocking,
end-to-end acceptance failure. The release/accounting model, concurrency
control, TASK-005 tail closure, and all six M1 remediations are retained as
positive evidence. Master Control should return TASK-004 to `IN PROGRESS`, keep
TASK-005 `CLOSED` only with F-011 as an explicit condition, and triage all five
verified findings. No refuted candidate should be actioned as a finding.

## 2. Verified Findings

### F-007 — A returned payment cannot re-enter the public settlement workflow

**Severity:** `blocking`

**Finding.** TASK-004 AC-7 says a returned item's instruction re-batches
successfully (`origin/meta:docs/briefs/004-TASK-settlement-simulation.md:234`).
The database implements that permission with:

```sql
create unique index settlement_item_live_key
  on settlement_batch_item (instruction_id)
  where outcome is distinct from 'returned';
```

(`resources/migrations/0009-settlement-batches-and-scheme-responses.sql:108-110`).
The application cannot use it. `eligible-status` is only `:approved`
(`src/clofin/settlement/batch.clj:62-69`), `refusal` rejects every other state as
`:not-approved` (`src/clofin/settlement/batch.clj:87-100`), and `:returned` has
no outgoing lifecycle transition (`src/clofin/payments/state.clj:28-57`).

**Reproduction.** Verification log C-01 drove an approved synthetic instruction
through release and return. The return was `200` and payment status was
`returned`. A second public batch was `422` with reason `not-approved`. A raw
membership insert for the same instruction then committed, leaving membership
count `2`. This reproduced the cross-layer contradiction, not merely one side
of it.

**Why it matters.** A scheme return is an operational exception that the brief
explicitly says may be retried. The shipped product strands the instruction in a
terminal state. The schema advertises a safety-preserving retry permission that
no public workflow can reach, so AC-7 is false end to end.

**Suggested direction.** Decide the retry aggregate before changing code. If the
same instruction is meant to retry, define the lifecycle, re-approval semantics,
audit actions, and second release posting explicitly; simply allowing
`:returned` through construction would still fail the `:release` transition. If
a new linked instruction is the intended retry, add that operation and amend
AC-7, the index rationale, domain model, and UAT together. Retain the database
rule that pending, settled, and timed-out memberships can never be retried.

**Affects:** TASK-004 AC-7, payment lifecycle, settlement exception handling,
`settlement_item_live_key`, UAT-006, C-04.

### F-008 — A rejected scheme response is erased and can later perform work

**Severity:** `should-fix`

**Finding.** The design says responses are kept whether or not they caused work
(`resources/migrations/0009-settlement-batches-and-scheme-responses.sql:125-160`;
`origin/meta:docs/briefs/004-TASK-settlement-simulation.md:274-278`). In
`record-scheme-response!`, the response is inserted first
(`src/clofin/settlement/service.clj:298-330`). If the item is not in the state
that response kind can resolve, `when-not item` throws a conflict
(`src/clofin/settlement/service.clj:355-372`). The API wraps both in one outer
transaction (`src/clofin/api/settlement.clj:212-224`), and `with-transaction*`
rolls the whole transaction back on the throwable
(`src/clofin/db/core.clj:218-245`). The insert's duplicate savepoint does not
protect it from a later outer rollback.

**Reproduction.** Verification log C-02 sent a timeout-resolution before the
item timed out. The endpoint returned `409`; direct SQL found zero response rows
for its reference. After a timeout sweep, the identical reference returned
`200`, `replayed=false`, transitioned the payment to `settled`, wrote
`payment.settled`, and produced finality. Thus the first arrival was neither
evidence nor a replay barrier.

**Why it matters.** Arrival order is control evidence in settlement. Erasing a
rejected message makes the first delivery unprovable and allows the same message
to acquire a different effect when state changes later. That is the opposite of
the stated append-only receipt posture and complicates incident reconstruction.

**Suggested direction.** Separate durable receipt from processing disposition.
A rejected response should commit its immutable receipt and a machine-readable
processing result, then render `409` after commit rather than by throwing away
the unit. Replaying that receipt must reproduce its original no-work result,
not re-evaluate it against later state. Add premature timeout and late
contradiction tests that assert the row survives and remains effect-free.

**Affects:** TASK-004 AC-5/AC-6, migration `0009`, response evidence,
reconciliation input, C-05/C-06 posture.

### F-009 — The replay key and stored response omit effect-bearing fields

**Severity:** `should-fix`

**Finding.** `scheme_response` stores only batch, instruction, kind, reference,
and receipt time (`resources/migrations/0009-settlement-batches-and-scheme-responses.sql:130-143`).
For `timeout-resolution`, outcome and reason travel only in the request. The
replay key consequently treats two contradictory timeout outcomes as one
response. On duplicate, the service returns `:original` but no `:outcome`
(`src/clofin/settlement/service.clj:325-337`); the API renders `(:outcome result)`
(`src/clofin/api/settlement.clj:225-227`), producing null instead of the original
answer. `find-response` cannot recover fields the row never stored
(`src/clofin/settlement/repository.clj:413-425`).

**Reproduction.** Verification log C-03 observed `outcome="settled"` on first
delivery and `outcome=nil` on its duplicate; the bodies differed even after
excluding the intentional `replayed` flag. It then sent a
`timeout-resolution(settled)` followed by the same reference with
`timeout-resolution(returned, reason=...)`. The contradictory request received
`200 replayed=true` and `outcome=nil`. SQL confirmed the stored row has no
outcome or reason member.

**Why it matters.** Idempotency is identity plus replay. Here identity excludes
fields that decide the payment transition, and replay does not reproduce the
answer. A caller cannot distinguish an exact retry from contradictory input,
and the retained record cannot prove what the simulated scheme claimed.

**Suggested direction.** Apply the existing idempotency posture: retain a
canonical digest of the complete semantic request and the original response
status/body, or include normalized outcome and reason in an equivalent durable
model. Exact duplicates should return the original semantic body; the same
reference with a different digest should be `409` and must not be described as
an exact replay.

**Affects:** TASK-004 AC-5/AC-6, API contract `SettlementBatch.outcome`, migration
`0009`, reconciliation evidence, C-06.

### F-010 — Loss of the scheme-response UPDATE/DELETE guard is not test-detectable

**Severity:** `should-fix`

**Finding.** Migration `0009` correctly creates both
`scheme_response_append_only` and `scheme_response_no_truncate`
(`resources/migrations/0009-settlement-batches-and-scheme-responses.sql:195-201`).
The L-5 matrix in `clofin.db.audit-constraints-test` enumerates journal entries,
journal lines, audit events, and approvals, but not scheme responses
(`test/clofin/db/audit_constraints_test.clj:251-261`). The fixture's declared
append-only list includes `scheme_response` (`test/clofin/test_db.clj:39-47`),
but its schema discovery concerns TRUNCATE triggers used during cleanup
(`test/clofin/test_db.clj:49-118`), not the row-level UPDATE/DELETE trigger.

**Reproduction.** Verification log C-04 first proved all three direct SQL verbs
are currently refused and preserve the row. In a disposable migrated database,
the audit removed only `scheme_response_append_only`; the remaining trigger list
contained only `scheme_response_no_truncate`. Both
`clofin.db.audit-constraints-test` and
`clofin.settlement.repository-test` still passed: 33 tests, 120 assertions,
zero failures.

**Why it matters.** This does not allege a current mutation bypass. It proves a
future migration or fixture regression can remove two thirds of the append-only
control while the focused suites remain green. That is the partial-enforcement
failure L-5/L-6 exist to prevent, on the new evidence table those lessons should
have covered automatically.

**Suggested direction.** Add `scheme_response` to the full raw-SQL table × verb
matrix with a committed row, and run a negative control once to prove removal of
either trigger fails the suite. Keep the existing fixture drift check; it covers
a different risk.

**Affects:** TASK-004 migration `0009`, L-5, settlement evidence immutability,
UAT-006 step 12, C-05.

### F-011 — The audited ledger services do not enforce their transaction precondition

**Severity:** `should-fix`

**Finding.** TASK-005's service functions accept an argument named `tx`, compose
the aggregate write and audit write on it, and document that it is a transaction
(`src/clofin/ledger/service.clj:43-95`). Nothing rejects a pool. Account creation
uses the supplied source directly (`src/clofin/ledger/repository.clj:77-100`);
journal posting opens and commits its own transaction when given a pool
(`src/clofin/ledger/repository.clj:226-267`); then `audit.repository/record!`
uses the original supplied source (`src/clofin/audit/repository.clj:68-103`). The
purity guard proves that services do not require `clofin.db.*`; it does not
prove callers supplied an active transaction
(`test/clofin/ledger/purity_test.clj:97-137`).

**Reproduction.** Verification log C-05 called `create-account!` and
`post-entry!` with the pool and a null actor. In both cases audit construction
failed as intended. Nevertheless, SQL found one committed aggregate row and
zero audit rows: account `1/0`, journal entry `1/0`.

**Why it matters.** Current HTTP handlers pass a transaction correctly, so this
is not a reproduced front-door failure. It is an internal control-boundary
failure: a REPL task, script, new adapter, or future refactor can use the service
API exactly as Clojure permits and create the state C-05 says is
unrepresentable. A parameter name and dependency test make misuse visible in
review; they do not fail closed at runtime.

**Suggested direction.** Make transaction capability an enforced service
precondition before the first aggregate write. One compatible option is an
`audit.repository`/unit-of-work assertion, callable at service entry, that
accepts only a non-autocommit transaction connection; another is an opaque
transaction context that a pool cannot satisfy. Add direct pool and autocommit
negative tests for every audit-composing service, including settlement, while
retaining the no-`clofin.db.*` dependency rule.

**Affects:** TASK-005 AC-1/AC-2/AC-3, C-05, invariant I9, ARCHITECTURE §4,
ledger and organisation service contracts; analogous service entry points should
be reviewed.

## 3. Cross-Cutting Observations and Candidate Standing Lessons

### Concurrency control is stronger than its original evidence

C-06 was refuted by five forced schedules. In each, the competing backend was
observed in `pg_stat_activity` with `wait_event_type='Lock'` before the winner
committed. Overlapping batch construction left one membership and one event;
amend-first invalidated approval and prevented release; release-first preserved
the historical approval and refused amendment; concurrent distinct outcomes
produced the exact derived status and one completion event; sweep followed by a
late truth produced one sweep, one completion, one payment transition, and no
double posting. Future settlement briefs should preserve those tests, not only
the static lock-order prose.

### ADR-0018 is coherent

`clofin.payments.posting-test` and the full settlement property test pass. Release
debits `1300-IN-TRANSIT` and credits `1100-CLIENT-FUNDS`; settlement debits
`2100-CLIENT-PAYABLE` and credits in-transit; return is the exact release mirror;
timeout posts nothing. The domain model's corrected worked example agrees with
the implementation. No ADR-0018 finding is warranted.

### Recorded debt is real and mostly adequate

The timeout-horizon debt reproduced: a batch aged while open was swept
immediately after submission because the SQL uses `created_at`. The late-status
debt also reproduced: a late truth moved the stored batch from `failed` to
`settled`, added one `payment.settled`, and added no batch-subject event. Both
are honestly recorded in `004-REQ` and are not re-reported as discoveries. The
late-status exception should also be visible wherever COMPLIANCE C-05 is read as
“every state change,” so that the central control record does not appear broader
than its accepted boundary.

The runtime role split is likewise accurately bounded. The shipped `clofin` role
is owner and superuser; a rolled-back probe set
`session_replication_role='replica'` and deleted an audit event past the trigger.
Refused-attempt logging remains deliberately separate security-event debt. These
are not new findings.

`payment.failed` remains reserved and has no emission point. That matches the
O-1 ruling. The OpenAPI enum does not state that it is reserved, however, so an
API-only consumer cannot distinguish “no failed payments” from “this action has
no producer.” Correct that description when the contract next changes.

### Milestone 1 remains closed

The focused M1 suite passed: creator-only submission (F-001), full destructive
verb protection and owner residue boundary (F-002), entry completeness (F-003),
posting/account lock discipline (F-004), decision-versus-transition audit
vocabulary (F-005), and per-approval invalidation evidence (F-006). None is
reopened by this report.

### Candidate L-10 — A schema path is not a product path

When a control or acceptance criterion promises an operation, test from the
public command through lifecycle, persistence, posting, and audit. A raw SQL
constraint test proves the database permits or refuses one write; it does not
prove the application can reach that write coherently. F-007 is the motivating
case.

### Candidate L-11 — Durable receipt and processing failure are separate facts

If a message table is evidence that something arrived, a processing conflict
must not roll that receipt back. Model receipt and disposition separately, then
replay the original disposition. Throwing a domain error from the transaction
that appended the receipt destroys the evidence the table exists to retain.
F-008 is the motivating case.

### Candidate L-12 — Replay identity covers every effect-bearing field

A replay key that excludes a field selecting the state transition is incomplete.
Canonicalize the complete semantic request, retain its digest and response, and
reject same-key/different-digest input. F-009 is the motivating case.

### Candidate L-13 — A load-bearing transaction precondition fails closed

A parameter named `tx`, a docstring, and a dependency purity test do not make a
pool unrepresentable. Any service whose control claim depends on joining an
existing transaction must reject a pool or autocommit connection before its
first write. F-011 is the motivating case.

F-010 reinforces existing L-5/L-6 rather than requiring another lesson: discover
all guarded tables and exercise the full destructive verb set, with a negative
control proving the test detects removal.

## 4. Verification Summary

| Candidate | Result | Evidence | Report disposition |
|---|---|---|---|
| **C-01** — returned instruction re-batching | **Confirmed** | Public retry `422 not-approved`; direct returned-membership insert committed; count `2` | **F-007 blocking** |
| **C-02** — rejected response retention | **Confirmed** | Premature response `409`, stored count `0`; same reference later `200`, `replayed=false`, posted finality | **F-008 should-fix** |
| **C-03** — replay body/identity completeness | **Confirmed** | First outcome `settled`, duplicate outcome null; contradictory same-key timeout payload returned `200 replayed=true`; row stores no outcome/reason | **F-009 should-fix** |
| **C-04** — scheme-response L-5 matrix | **Confirmed, bounded** | All guards work; after dropping UPDATE/DELETE trigger, 33 focused tests/120 assertions still passed | **F-010 should-fix** |
| **C-05** — service transaction precondition | **Confirmed** | Pool call plus audit refusal left account `1/event 0` and journal entry `1/event 0` | **F-011 should-fix** |
| **C-06** — settlement concurrency defects | **Refuted** | Five forced schedules visibly waited on DB locks and satisfied final state, ledger, approval, and event invariants | Dropped; no finding |

## 5. Verification Totals and Close

- Unchanged unit/property/contract baseline: **272 tests / 1,502 assertions / 0 failures / 0 errors**.
- Unchanged full integration baseline: **567 tests / 3,459 assertions / 0 failures / 0 errors**. The generated settlement property changes assertion count with vector length, as `004-REQ` records.
- Focused verdict gates: **63 tests / 366 assertions** unit and **163 tests / 1,170 assertions** integration, all green.
- Candidate probes: **5 confirmed, 1 refuted**.
- Synthetic-only PostgreSQL probes were rolled back or executed in disposable audit databases.
- Final repository status was checked clean; the audit made no repository modification.

Master Control owns ingestion and triage. Each finding should be marked actioned,
deferred with a stated product/control reason, or disputed with evidence. The
operator may ferry this file unchanged to `origin/meta`; the auditor does not
push it.
