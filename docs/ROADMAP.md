# CloFin — Roadmap

**Status:** living document · **Last reviewed:** 2026-08-02

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
| 2 | Ledger persistence and account API | [TASK-001](briefs/001-TASK-ledger-persistence-and-account-api.md) | ✅ `IMPLEMENTED` — merged to `main` in PR #2 (`f7018a1`); `FEEDBACK-001` outstanding | green, 757 assertions |
| 3 | Payment lifecycle and idempotency | [TASK-002](briefs/002-TASK-payment-instruction-lifecycle.md) | 🔨 `IN PROGRESS` — dispatched; based on `main` | — |
| 4 | Authorisation, maker–checker, audit | [TASK-003](briefs/003-TASK-authorisation-and-audit-trail.md) | 📋 `READY`, blocked on 002 | — |
| 5–9 | Settlement onwards | not yet briefed | 💭 later | — |

**Controls still unenforced.** Four entries in
[`COMPLIANCE.md`](COMPLIANCE.md) are 📋 *designed, not built* — C-01, C-02,
C-05 and C-06. TASK-002 delivers C-06; TASK-003 delivers the other three. Until
then, the honest statement is that CloFin **specifies** maker–checker and
idempotency and **does not yet implement them**. Do not describe them otherwise
in a README, a PR description or a conversation.

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

**Brief:** [TASK-001](briefs/001-TASK-ledger-persistence-and-account-api.md) · **Status:** `IMPLEMENTED` — merged in PR #2, `FEEDBACK-001` outstanding

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

## Increment 3 — Payment instruction lifecycle 📋

**Brief:** [TASK-002](briefs/002-TASK-payment-instruction-lifecycle.md) · **Status:** `IN PROGRESS` — dispatched; based on `main` (dependency merged in PR #2)

*Why next: the lifecycle is the spine every control attaches to.*

- Instruction capture, validation and structured multi-field rejection
- State machine as data, with exhaustive transition tests
- `Idempotency-Key` handling: stored response, `409` on body mismatch
- Posting templates per payment type
- **Risk addressed:** duplicate payments from retries — the failure with the
  most direct financial consequence.

## Increment 4 — Authorisation, maker–checker and audit 📋

**Brief:** [TASK-003](briefs/003-TASK-authorisation-and-audit-trail.md) · **Status:** `READY`, blocked on TASK-002

*Why next: this is the control an auditor asks about first.*

- Roles, permissions, and per-organisation approval threshold tables
- Segregation of duties as a domain rule: maker ≠ checker, enforced in code
- Approval invalidation on amendment
- Append-only audit trail, written in the same transaction as the change
- Evidence extraction for a nominated payment
- **Risk addressed:** unauthorised or unattributable movement of money.

## Increment 5 — Settlement simulation 💭

- Batch construction by scheme, currency and value date
- Simulated scheme adapter behind a protocol, with partial-failure outcomes
- Settlement finality posting; returns raising exception cases
- Timeout, duplicate and out-of-order response handling

## Increment 6 — Reconciliation 💭

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

## How to pick up the next task

Take the lowest-numbered brief in [`briefs/`](briefs) that is `READY` with its
dependencies met — currently **TASK-001**. Set its `Status` to `IN PROGRESS` in
your first commit; that commit is the lock other sessions check.

Each brief is self-contained — scope, schemas, acceptance criteria, out-of-scope
notes and the traps to avoid — so a session with no access to the conversation
that produced it can execute it. Protocol:
[`AGENT_HANDOFF.md`](AGENT_HANDOFF.md). Audit feedback:
[`audits/`](audits).
