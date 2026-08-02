# CloFin — Roadmap

**Status:** living document · **Last reviewed:** 2026-08-02

Increments are sequenced by **product relevance and regulatory risk**, not by
implementation convenience. Each increment leaves `main` runnable, migrated,
documented and tested — a half-finished increment is not merged.

Legend: ✅ done · 🔨 in progress · 📋 next · 💭 later

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

## Increment 2 — Ledger persistence and account API 📋

*Why next: the domain model exists but cannot yet be exercised through the API,
so nothing above it can be built.*

- Ledger repository: persist and load entries and accounts
- Balance and statement queries — opening balance, movements, closing balance
- `POST /organisations`, `POST /accounts`, `GET /accounts/:id`
- `POST /journal-entries`, `GET /accounts/:id/statement`
- Synthetic chart of accounts as a seedable fixture
- Acceptance criteria and UAT script for statement production
- **Risk addressed:** derived balances must be correct and performant before
  anything relies on them.

## Increment 3 — Payment instruction lifecycle 📋

*Why next: the lifecycle is the spine every control attaches to.*

- Instruction capture, validation and structured multi-field rejection
- State machine as data, with exhaustive transition tests
- `Idempotency-Key` handling: stored response, `409` on body mismatch
- Posting templates per payment type
- **Risk addressed:** duplicate payments from retries — the failure with the
  most direct financial consequence.

## Increment 4 — Authorisation, maker–checker and audit 📋

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

Increment 2 is next. Its work is specified as briefs in
[`briefs/`](briefs) — each is self-contained, with scope, schemas, acceptance
criteria and out-of-scope notes, so an independent session can execute one
without access to the conversation that produced it. See
[`AGENT_HANDOFF.md`](AGENT_HANDOFF.md).
