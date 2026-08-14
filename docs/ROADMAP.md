# CloFin — Roadmap

**Status:** living document · **Last reviewed:** 2026-08-14

Increments are sequenced by **product relevance and regulatory risk**, not by
implementation convenience. Each increment leaves `main` runnable, migrated,
documented and tested — a half-finished increment is not merged.

Legend: ✅ done · 🔨 in progress · 📋 next · 💭 later

## Global state

Single view of what is delivered, in flight, and queued. Kept in step with the
`Status` table in each brief — if the two disagree, the brief is authoritative
and this table is stale.

| Increment | Theme | Brief | Status | CI |
|---|---|---|---|---|
| 1 | Foundation and ledger core | — (predates the brief protocol) | ✅ done — merged to `main` at `3bde834` (PR #1; milestone audit waived, see audits register) | green, 303 assertions |
| 2 | Ledger persistence and account API | [TASK-001](briefs/001-TASK-ledger-persistence-and-account-api.md) | ✅ `CLOSED` — merged to `main` in PR #2 (`f7018a1`); audited in FEEDBACK-M1 (F-002/F-003/F-004, actioned via the increment-4 stack) | green, 757 assertions |
| 3 | Payment lifecycle and idempotency | [TASK-002](briefs/002-TASK-payment-instruction-lifecycle.md) | ✅ `CLOSED` — merged to `main` in PR #4 (`31306dd`); audited in FEEDBACK-M1, no new findings | green, 1547+ assertions |
| 4 | Authorisation, maker–checker, audit | [TASK-003](briefs/003-TASK-authorisation-and-audit-trail.md) | ✅ `CLOSED` — merged to `main` in PR #5 (`5ff00eb`); FEEDBACK-M1 fully remediated & verified (migrations `0007`/`0008`) | green, 2515 assertions |
| 4c | Audit coverage completion (C-05 unqualified) | [TASK-005](briefs/005-TASK-audit-coverage-completion.md) | ✅ `CLOSED` — merged to `main` in PR #6 (`2ba977e`) | green, 2747 assertions |
| 5 | Settlement simulation | [TASK-004](briefs/004-TASK-settlement-simulation.md) | ✅ `CLOSED` — PR #7 (`cba31c5`) + FEEDBACK-M2 remediation PR #8 (`5d21334`; migration `0010`, ADR-0019) | green, 584 tests / 3638 assertions |
| 5v.1 | Visual layer — generated diagrams | [TASK-006](briefs/006-TASK-generated-diagrams.md) | ✅ `CLOSED` — merged to `main` in PR #12 (`2237a39`); five objections ruled, O-1 actioned on `meta` | green, 322 tests / 1991 assertions (verify), 640 / 4222 (integration) |
| 5v.2 | Visual layer — `clofin-trace` replay walkthrough | [TASK-007](briefs/007-TASK-clofin-trace.md) | ✅ `CLOSED` — harness merged in PR #14 (`261c778`); walkthrough merged in `clofin-trace` PR #1 (`71cb13f`); **live at <https://echojustus.github.io/clofin-trace/>** | green, both repositories |
| 5v.3 | Visual layer — trace hardening and cross-links | [TASK-009](briefs/009-TASK-trace-hardening-and-cross-links.md) | 🔨 `IN PROGRESS` — dispatched 2026-08-14 | — |
| 6 | Reconciliation | [TASK-008](briefs/008-TASK-reconciliation.md) | 🔨 `IN PROGRESS` — dispatched 2026-08-14 | — |
| 7–9 | Financial crime onwards | not yet briefed | 💭 later | — |

**Controls now enforced on `main`.** As of 2026-08-04 the increment-3/4 stack is
merged (PR #4 `31306dd`, PR #5 `5ff00eb`): C-06 (idempotency), C-01 (segregation
of duties), C-02 (dual authorisation), C-05 (attributable audit trail) and C-08
(least privilege) are enforced on `main`, and the Milestone 1 audit's two
blocking and four should-fix findings are all remediated and verified there.
**C-05 is unqualified on `main`** as of PR #6 (`2ba977e`): every API write —
payments, approvals, organisations, accounts, journal entries — leaves exactly
one audit event in the transaction carrying the change, with the bootstrap
identity enforced per ADR-0017. **One honesty caveat stands:** the append-only
guarantee binds the application but **not** a schema-owner adversary — the
runtime role split is named debt in COMPLIANCE §4, earmarked for the
operational-hardening brief alongside tools.build. C-07 (screening) remains 📋.
Do not describe any of this more generously in a
README, a PR description or a conversation.

**Releases.** A release is a tagged, whole-repo-audited snapshot (`ref-<n>`) —
an internal quality milestone on a synthetic-data reference implementation,
never a production deployment or an external attestation. It follows a
milestone once that milestone's audit findings are remediated and closed, and
is gated by a whole-repo release audit
([AGENT_HANDOFF §1c](AGENT_HANDOFF.md); mechanics and charter in
[audits/](audits/README.md)).

**[`ref-1` is released](https://github.com/EchoJustus/clofin-core/releases/tag/ref-1)**
— tagged 2026-08-05 at `5c7b4ba`, the remediation descendant of the RC
(`5d21334`), as the release rules permit. Verified on the remote:
`refs/tags/ref-1` → `5c7b4badced5e807e1022fce44cbcad38c6d2095`. Published as a
**GitHub pre-release** — the accurate machine-readable signal for a
synthetic-data reference implementation, and now the convention for every
`ref-<n>`. **Its release audit was partial**: charter items 1–4 of 8 were
performed, 5–7 were not, and the release annotation says so — published as the
GitHub **release body** on the tag; the published tag object itself is
lightweight (007-REQ O-1), and `docs/releases/ref-1.annotation.txt` on `main`
mirrors the text byte for byte. All 19 findings were
remediated before the tag. **Uncovered audit scope (items 5–7) carries forward
as mandatory-first scope for `ref-2`.**

**Visual layer — and what it displaces.** `ADR-0020`
*(`docs/ADR/0020-two-repositories-and-the-generate-replay-rules.md`, merged to
`main` in PR #11, `cbbd669`, 2026-08-05, per amendment A1)*
adds generated diagrams ([TASK-006](briefs/006-TASK-generated-diagrams.md), in
`clofin-core`) and **`clofin-trace`**, a published replay walkthrough
([TASK-007](briefs/007-TASK-clofin-trace.md), a second repository). Driver D5 —
"the system must be inspectable by non-engineers" — has been satisfied by
versioned documents alone; documents make the system auditable, not visible.

**Cost, stated rather than absorbed: ~3–4 weeks that do not go to increment 6.**
Reconciliation is unchanged in content and position; it starts later by that
amount. A delay discovered afterwards is worse than one stated in advance.

**Increment 8 (operator console) does not move**, in `clofin-core`, for the
reason this roadmap already gives. When it arrives it brings an npm toolchain
into a repository with a stated minimal-dependency doctrine — that needs its own
ADR at increment 8, qualifying ADR-0004 and NFR-007. Recorded now so it is not
discovered late.

---

## Increment 1 — Foundation and ledger core ✅

*Why first: nothing downstream is trustworthy if the ledger is not.*

- ✅ Repository, licence, contribution and agent-handoff protocol
- ✅ `ARCHITECTURE.md` and ADRs 0001–0010
- ✅ Docker Compose stack and `Makefile` single entrypoint
- ✅ Clojure service skeleton: config, lifecycle, graceful shutdown
- ✅ Jetty 12 adapter with Ring-shaped maps; router; middleware chain
- ✅ RFC 9457 problem responses and a single error boundary
- ✅ Money value type — integer minor units, currency-aware scale, allocation
- ✅ Double-entry ledger core: accounts, entries, zero-sum invariant, reversal
- ✅ PostgreSQL schema with deferred zero-sum trigger and append-only enforcement
- ✅ Forward-only migration runner with checksum verification and advisory lock
- ✅ Health, readiness and service-info endpoints
- ✅ Property tests for ledger and money invariants; database constraint tests;
  end-to-end HTTP test; OpenAPI contract test

## Increment 2 — Ledger persistence and account API ✅

**Brief:** [TASK-001](briefs/001-TASK-ledger-persistence-and-account-api.md) · **Status:** `CLOSED` — merged in PR #2; audited in FEEDBACK-M1, findings actioned via the increment-4 stack

*Why it came next: the domain model existed but could not be exercised through
the API, so nothing above it could be built.*

- ✅ Ledger repository: persist and load entries and accounts, scoped by organisation
- ✅ Balance and statement queries — opening balance, movements, closing balance,
  every figure derived from the journal
- ✅ `POST /organisations`, `GET /organisations/:id`
- ✅ `POST /accounts`, `GET /accounts`, `GET /accounts/:id`
- ✅ `POST /journal-entries`, `GET /journal-entries/:id`, `GET /accounts/:id/statement`
- ✅ Posting-time rules that need database state: account exists in the
  organisation, accepts postings, and agrees on currency
- ✅ [UAT-003](uat/UAT-003-account-statement-production.md) for statement production
- ✅ [ADR-0011](ADR/0011-statement-periods-ordering-and-row-caps.md) and
  [ADR-0012](ADR/0012-repository-seam-and-posting-time-validation.md)
- **Risk addressed:** derived balances must be correct before anything relies on
  them. Performance is *not* yet addressed — see below.

**Carried forward, deliberately.** Named here so the next session does not have
to rediscover them:

- **No pagination.** List and statement responses cap at 500 rows and set
  `truncated`. A cursor contract designed without a consumer would be guesswork.
- **No performance measurement.** The statement's composite sort is unindexed
  beyond `journal_line (account_id)`. ADR-0008 permits a snapshot table as an
  explicitly derived cache — only after measurement, never before.
- **Account lifecycle is not an API operation.** Freezing and closing are done
  in SQL; the transitions belong with authorisation and audit in TASK-003.

## Increment 3 — Payment instruction lifecycle ✅

**Brief:** [TASK-002](briefs/002-TASK-payment-instruction-lifecycle.md) · **Status:** `CLOSED` — merged in PR #4 (`31306dd`); audited in FEEDBACK-M1, no new findings

**Carried forward, deliberately** (from [002-REQ](audits/002-REQ-payment-instruction-lifecycle.md) §6):

- **PR-005 batch submission** is deferred — single-instruction submission only (O-6).
- **PR-044 partial-reversal accumulation** is not implemented; an instruction can
  be reversed more than once, unlike a journal entry (I4 has no instruction-level
  counterpart yet).
- **No indexes on `payment_instruction`** — the measure-before-optimising posture,
  but a real gap at volume.
- `transactionally` exists in two namespaces; a two-line delegation closes it.

*Why next: the lifecycle is the spine every control attaches to.*

- Instruction capture, validation and structured multi-field rejection
- State machine as data, with exhaustive transition tests
- `Idempotency-Key` handling: stored response, `409` on body mismatch
- Posting templates per payment type
- **Risk addressed:** duplicate payments from retries — the failure with the
  most direct financial consequence.

## Increment 4 — Authorisation, maker–checker and audit ✅

**Brief:** [TASK-003](briefs/003-TASK-authorisation-and-audit-trail.md) · **Status:** `CLOSED` — merged in PR #5 (`5ff00eb`); FEEDBACK-M1 fully remediated and verified

*Why next: this is the control an auditor asks about first.*

- ✅ Roles, permissions, and per-organisation approval threshold tables — default
  deny, no superuser, both build-enforced
- ✅ Segregation of duties as a pure domain rule (`clofin.authz.approval/evaluate`),
  tested with no HTTP anywhere in the file
- ✅ Approval invalidation on amendment; approvals invalidated, never deleted
- ✅ Append-only audit trail written in the same transaction as the change —
  `record!` takes a transaction and cannot open one
- ✅ Evidence extraction for a nominated payment; digests, not payloads (ADR-0016)
- **Risk addressed:** unauthorised or unattributable movement of money.

**Carried forward, deliberately** (from [003-REQ](audits/003-REQ-authorisation-and-audit-trail.md) §6):

- **Ledger and organisation writes emit no audit events** — C-05's scope
  paragraph names the gap; **briefed as
  [TASK-005](briefs/005-TASK-audit-coverage-completion.md)**, dispatched 2026-08-04.
- **The approver's limit at decision time is not retained** (O-4): two capture
  columns on `approval` belong in a future brief.
- **Authentication does not resist an adversary** — `X-Actor-Id` names a seeded
  actor; the authorisation model is real, the authentication in front of it is
  scaffolding, and every relevant doc says so.
- **No actor administration API** — deliberate; self-granted roles would make
  C-01 unenforceable.

## Increment 5 — Settlement simulation ✅

**Brief:** [TASK-004](briefs/004-TASK-settlement-simulation.md) · **Status:** `CLOSED` — merged in PR #7 (`cba31c5`); FEEDBACK-M2 remediation merged in PR #8 (`5d21334`)

- Batch construction by scheme, currency and value date
- Simulated scheme adapter behind a protocol, with partial-failure outcomes
- Settlement finality posting; returns raising exception cases
- Timeout, duplicate and out-of-order response handling
- **Risk addressed:** money moving twice — duplicate scheme responses,
  re-batched unknowns, and out-of-order deliveries are the increment's core,
  not its edge cases.

## Increment 6 — Reconciliation 🔨

**Brief:** [TASK-008](briefs/008-TASK-reconciliation.md) · **Status:** `IN PROGRESS` — dispatched 2026-08-14

- Synthetic statement generation and ingestion
- Deterministic matching rules in a documented order, recording which matched
- Breaks with ageing, ownership and resolution workflow
- Adjustment posting with approval above a threshold

## Increment 7 — Financial crime controls 💭

- Sanctions screening against a synthetic list, with list versioning
- Rule-based fraud scoring with explainable contributing reasons
- Case management and disposition with retained rationale

## Increment 8 — Operator interface 💭

- React/TypeScript console: instruction capture, approval queue, break workbench
- Chosen deliberately late: the API contract and controls are the substance,
  and a UI built before them would encode the wrong model.

## Increment 9 — Programmable settlement exploration 💭

- Conditional release against simulated tokenised-deposit or CBDC-style rails
- Explicitly a **simulation**, and labelled as such wherever it appears.

---

## Deliberately deferred

| Item | Why it waits |
|---|---|
| Performance and scale work | No measurement yet. Optimising an unmeasured system is guesswork. |
| Event bus / service extraction | No driver. See [ADR-0007](ADR/0007-modular-monolith-over-microservices.md). |
| Authentication provider integration | The permission model is the interesting part; OIDC wiring is not. |
| Multi-region and DR | Meaningless without real institutional connectivity. |
| Linked-retry provenance for returned payments | FEEDBACK-M2 F-007 ruling: a returned instruction is terminal and a retry is a **new** instruction — already possible via capture today. The linkage (`retries_id`-style provenance and the exception workflow around it) belongs to increment 6 (reconciliation), where return-exception handling natively lives. |
| Audit logging of *refused* control attempts | FEEDBACK-M1 surfaced that a refused submission/approval leaves no audit event (audit writes sit inside the successful effect). C-05 scopes the trail to state *changes*, so this is new scope — a distinct security-event control with its own volume and retention, briefed separately when prioritised. Distinct from TASK-005, which covers *successful* writes that currently go unaudited. |

## How to pick up the next task

Take the lowest-numbered brief in [`briefs/`](briefs) that is `READY` with its
dependencies met — currently **TASK-001**. Set its `Status` to `IN PROGRESS` in
your first commit; that commit is the lock other sessions check.

Each brief is self-contained — scope, schemas, acceptance criteria, out-of-scope
notes and the traps to avoid — so a session with no access to the conversation
that produced it can execute it. Protocol:
[`AGENT_HANDOFF.md`](AGENT_HANDOFF.md). Audit feedback:
[`audits/`](audits).
