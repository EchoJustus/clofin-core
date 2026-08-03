# CloFin — Architecture

**Status:** living document · **Applies to:** `clofin-core`

This document explains how CloFin is put together and, more importantly, *why*.
Individual reversible decisions live in [`docs/ADR/`](docs/ADR); this document is the
map that ties them together.

> CloFin processes synthetic data only. It is not connected to any bank, payment
> scheme or central bank, and holds no regulatory authorisation. See the scope
> table in [`README.md`](README.md).

---

## 1. Architectural drivers

The design is driven by the qualities a regulated payments platform is actually
judged on — not by feature count.

| # | Driver | Consequence in the design |
|---|---|---|
| D1 | **Financial correctness is non-negotiable.** A ledger that can lose a cent is worthless. | Integer minor units, never floating point. Double-entry with a zero-sum invariant enforced in code *and* in the database. Property-based tests over generated postings. |
| D2 | **Every state change must be explainable after the fact.** | Append-only journal and audit trail. No destructive updates on financial records; corrections are compensating entries. |
| D3 | **Money movement must be authorised by more than one person.** | Maker–checker as a first-class domain concept, with threshold-driven dual authorisation and segregation-of-duties checks. |
| D4 | **Networks fail; clients retry.** | Idempotency keys on every mutating payment operation. Exactly-once *effect*, at-least-once *delivery*. |
| D5 | **The system must be inspectable by non-engineers.** | The API contract, domain model and acceptance criteria are versioned artefacts, reviewed alongside the code. |
| D6 | **It must run anywhere, including offline.** | One `docker compose` stack, no cloud-specific services, no hard-coded paths. |
| D7 | **Supply-chain surface is a control concern.** | Small, justified dependency set; new runtime dependencies require an ADR. |

---

## 2. System context

```
      ┌──────────────────┐          ┌───────────────────────┐
      │ Corporate user   │          │ Client ERP / TMS      │
      │ (maker/checker)  │          │ (batch file, API)     │
      └────────┬─────────┘          └──────────┬────────────┘
               │  HTTPS                        │  HTTPS
               ▼                               ▼
      ╔══════════════════════════════════════════════════════╗
      ║                    CloFin core                       ║
      ║                                                      ║
      ║  Payments  ──▶  Authorisation  ──▶  Ledger           ║
      ║      │                                  │            ║
      ║      ▼                                  ▼            ║
      ║  Compliance                        Settlement        ║
      ║      │                                  │            ║
      ║      └──────────▶ Reconciliation ◀──────┘            ║
      ║                                                      ║
      ║              Audit trail (append-only)               ║
      ╚═══════════════╤═══════════════════╤══════════════════╝
                      │                   │
             simulated adapters    PostgreSQL (system of record)
                      │
     ┌────────────────┼────────────────┬──────────────────┐
     ▼                ▼                ▼                  ▼
 Clearing scheme  Sanctions/PEP   Bank statement    FX reference
 (simulated)      screening       feed (simulated)  rates (static)
                  (simulated)
```

Every external box is a **simulated adapter** behind a Clojure protocol. The
protocol is the contract; the simulator is one implementation. This keeps the
door open for a real adapter without ever implying one exists.

---

## 3. Bounded contexts

CloFin is a modular monolith. Contexts are enforced by namespace boundaries and
explicit interfaces, not by network hops — a distributed architecture would add
failure modes without adding product insight at this stage
([ADR-0007](docs/ADR/0007-modular-monolith-over-microservices.md)).

| Context | Namespace root | Owns |
|---|---|---|
| **Organisations** | `clofin.organisations` | Tenants; every business record belongs to one |
| **Ledger** | `clofin.ledger` | Accounts, journal entries, postings, balances |
| **Payments** | `clofin.payments` | Payment instructions, lifecycle, idempotency |
| **Authorisation** | `clofin.authz` | Roles, permissions, maker–checker, SoD |
| **Settlement** | `clofin.settlement` | Batches, scheme adapter, settlement finality |
| **Reconciliation** | `clofin.recon` | Statement ingestion, matching, breaks |
| **Compliance** | `clofin.compliance` | Screening, fraud rules, cases |
| **Audit** | `clofin.audit` | Append-only event capture and evidence extraction |

Dependency rule: **the ledger depends on nothing.** Payments depends on ledger
and authz. Settlement and reconciliation depend on ledger. Nothing depends on
HTTP. This is what makes the domain testable without a server or a database.

---

## 4. Layering

```
 clofin.main / clofin.system     lifecycle: start, stop, signal handling
 ─────────────────────────────
 clofin.http.*                   transport: Jetty, routing, middleware
 clofin.api.*                    resource handlers: parse, dispatch, serialise
 ─────────────────────────────
 clofin.<context>.*              domain: pure functions over immutable values
 ─────────────────────────────
 clofin.db.*                     persistence: connection pool, SQL, migrations
```

The domain layer is **pure**: functions take values and return values. It never
opens a connection, reads a clock, or generates an identifier. Effects are
supplied by the caller. Two consequences that matter in a regulated context:

1. Domain rules are tested exhaustively without infrastructure.
2. The same rule can be replayed against historical inputs to reproduce a past
   decision — which is what an auditor actually asks for.

**The persistence seam is named, not implied.** One namespace per context may
require `clofin.db.*`, and it is the one called `repository` —
`clofin.ledger.repository`, `clofin.organisations.repository`,
`clofin.payments.repository`, `clofin.idempotency.repository`,
`clofin.authz.repository`, `clofin.audit.repository`. Every other domain
namespace beside it stays pure. The rule is checked by
`test/clofin/ledger/purity_test.clj`, which reads the `ns` forms rather than
trusting review to remember
([ADR-0012](docs/ADR/0012-repository-seam-and-posting-time-validation.md)).

**A `service` namespace owns no connection either.** `clofin.payments.approval-service`
sequences repositories inside a transaction the *caller* owns, and requires no
`clofin.db.*` namespace at all. That is not tidiness: a service able to open its
own transaction is a service able to write an audit event outside the change it
describes, which is the one failure C-05 exists to prevent. The same purity test
enforces it.

A repository is also where rules that **cannot** be checked purely belong —
those that are properties of stored state rather than of a value, such as
whether an account exists in the caller's organisation and still accepts
postings. Those run inside the same transaction as the write they guard.

---

## 5. Core design choices

### 5.1 Money

Amounts are integer **minor units** plus an ISO 4217 currency code
(`{:currency "SGD" :minor-units 125000}` is SGD 1,250.00). Floating point is
never used for money anywhere in the system. Arithmetic across differing
currencies is a hard error, not a coercion. See
[ADR-0003](docs/ADR/0003-money-as-integer-minor-units.md).

### 5.2 Double-entry ledger

The journal is the source of truth; balances are **derived**, never stored as an
authoritative value. Each journal entry carries two or more lines, each with an
explicit `:debit` or `:credit` direction and a positive amount. The invariant —
total debits equal total credits, per currency, per entry — is checked when the
entry is constructed, and again by a database constraint when it is persisted.
Entries are immutable once posted; a mistake is corrected by a reversing entry
that references the original. See
[ADR-0008](docs/ADR/0008-double-entry-journal-as-source-of-truth.md).

### 5.3 Payment lifecycle

```
                 ┌──────────── amend ────────────┐
                 ▼                               │
  draft ──submit──▶ pending-approval ──approve──▶ approved ──release──▶ released
    │                     │                          │                     │
    │ cancel              │ reject                   │ cancel              ├─settle──▶ settled
    ▼                     ▼                          ▼                     ├─fail────▶ failed
 cancelled             rejected                  cancelled                 └─return──▶ returned
```

Transitions are data (`clofin.payments.state/transitions`), not scattered
conditionals, so the permitted state machine can be rendered into documentation
and tested by enumerating every (state, event) pair rather than by sampling the
ones someone thought of. Terminal states are terminal: a settled payment is
never mutated; it is followed by a *reversal* instruction.

Two rules about status are **not** transitions — amending a draft in place, and
raising a reversal against a settled instruction — because neither changes the
status. They are held as named sets beside the table rather than as conditionals
in a handler, so that "what does status control?" has one answer and one file
([ADR-0014](docs/ADR/0014-payment-lifecycle-as-data.md)).

Built so far: `submit` and `cancel` have endpoints. The rest are in the table,
tested, and driven by nothing until approval (TASK-003) and settlement
(increment 5) arrive.

### 5.4 Idempotency

Every mutating payment endpoint requires an `Idempotency-Key`. The key, the
organisation and a digest of the request are stored with the resulting response,
**in the same transaction as the effect they protect**. Replaying the same
request returns the stored response; reusing the key for a different request is
a `409 Conflict`.

Two design points carry the weight, and both are decisions rather than details:

**The guarantee is the primary key**, `(organisation_id, key)`, not a check in
application code. A read-then-write is a race, and the window between the read
and the write is exactly long enough for two concurrent retries to both execute.
The second inserter blocks on the key, fails on it, and returns what the first
stored.

**The digest is over a canonical form of the request** — its method, its path
and its body, with sorted keys and no insignificant whitespace. Canonical, so a
retry that differs only in representation is honoured: a `409` on a genuine
retry would push the caller to mint a new key, and a new key is a second
payment. Method and path rather than the body alone, because two instructions'
submissions carry byte-identical bodies — a body-only digest made the second a
replay of the first, so its instruction was never submitted while the operator
saw success
([ADR-0013](docs/ADR/0013-canonical-request-digest-for-idempotency.md)).

### 5.5 Audit trail

Every payment instruction and approval state change is appended to
`audit_event` with actor, action, subject, before/after **digest**, correlation
id and timestamp. The table is append-only: `UPDATE` and `DELETE` are rejected
by a row-level trigger, so the constraint holds for the owning role too rather
than depending on which role happens to be connected.

Audit writes participate in the same transaction as the change they describe, so
an un-audited state change is not representable. That is made structural rather
than remembered: `clofin.audit.repository/record!` takes a connection and never
opens one, so the only connection a caller can hand it is the transaction
carrying the change.

Digests rather than payloads
([ADR-0016](docs/ADR/0016-audit-events-store-digests-not-payloads.md)): an
append-only table holding counterparty names is a second copy of the data C-09
minimises, and one that can never be cleaned. What that costs an auditor — a
digest cannot be read back — is stated in the ADR rather than discovered.

Ledger and organisation writes do not yet emit audit events; the gap is named in
[COMPLIANCE §4](docs/COMPLIANCE.md).

### 5.6 Multi-tenancy and access

Every business record carries an organisation identifier, and the organisation a
request acts on comes from the **authenticated actor**, never from the request.
An `organisationId` in a body or query string is verified against it and a
mismatch is refused rather than ignored.

Authorisation is role-based with explicit permissions and **default deny**: an
absent permission is a denied permission, and there is no superuser role — a
test asserts that no role holds every permission. Permission sets live in code
(`clofin.authz.model`) rather than in rows, because a permission set stored as
data is editable by anyone able to write those rows.

Segregation of duties is enforced as a **domain rule**: the actor who submits a
payment cannot be the actor who approves it, refused by a pure function that
takes values and returns a decision. If the only thing stopping self-approval
were a check in a handler, the control would not exist for any caller that did
not go through that handler.

Authentication itself is deliberately minimal — a seeded actor named by an
`X-Actor-Id` header, with no token and no signature. It does not resist an
adversary and is not presented as doing so ([COMPLIANCE §4](docs/COMPLIANCE.md));
identity-provider integration is later work. The permission model is the part
that had to be built first, because it is what everything else is enforced
against.

---

## 6. Persistence

PostgreSQL is the system of record. Correctness constraints are expressed in the
schema where possible, so that a bug in the application cannot corrupt the
ledger:

- `CHECK` constraints on positive amounts and known currency codes
- A deferred constraint trigger asserting per-entry zero-sum on commit
- Unique constraints backing idempotency keys
- Revoked `UPDATE`/`DELETE` on journal and audit tables

Migrations are **forward-only**, numbered SQL files in
`resources/migrations/`, applied by a small runner that records applied
versions and their checksums in `schema_migration`. A changed checksum on an
already-applied migration is a hard failure — the same discipline a release
process needs. See [ADR-0009](docs/ADR/0009-forward-only-sql-migrations.md).

---

## 7. HTTP layer

Jetty 12 with a thin adapter that converts to and from plain Clojure maps in the
shape of the Ring specification. Handlers are ordinary functions of a request
map returning a response map, so the API is tested by calling functions — no
socket required. Middleware provides correlation ids, structured request
logging, JSON encoding, and a single error-translation boundary that maps domain
errors (`:clofin/error` in `ex-data`) to RFC 9457 problem responses. See
[ADR-0010](docs/ADR/0010-thin-ring-compatible-http-adapter.md).

The API contract is versioned at [`api/openapi.yaml`](api/openapi.yaml) and is
treated as the interface specification: it changes in the same commit as the
handler it describes.

---

## 8. Testing strategy

| Level | What it covers | Command |
|---|---|---|
| **Property** | Ledger invariants over generated postings; money arithmetic laws | `make test` |
| **Unit** | Pure domain rules, state machine transitions, middleware | `make test` |
| **Integration** | Migrations, constraints, transactional behaviour against real PostgreSQL | `make test-it` |
| **Acceptance** | Scenario scripts traceable to acceptance criteria in `docs/uat/` | `make test-it` |

Property-based tests carry the most weight in the ledger: hand-written examples
demonstrate that a case works, whereas a property demonstrates that a *class* of
cases works. For a balance invariant, the second is the claim worth making.

---

## 9. Operability

- `GET /healthz` — liveness; process is up
- `GET /readyz` — readiness; database reachable and schema at expected version
- Structured JSON logs with correlation id on every request
- Graceful shutdown: stop accepting connections, drain, close the pool

---

## 10. What is deliberately *not* here

Being explicit about omissions is part of the specification.

- **No real institutional connectivity.** Adapters are simulated by design.
- **No cryptographic key management.** Signing and HSM integration are out of scope; the seams exist, the implementation does not.
- **No production hardening** — rate limiting, WAF, secret rotation. Local-development posture only.
- **No microservices, event bus, or CQRS read models** until a driver demands one. See [ADR-0007](docs/ADR/0007-modular-monolith-over-microservices.md).
- **No real customer identity data.** KYC is modelled as state and evidence references, never as stored identity documents.

---

## 11. Working agreement for contributors and agents

CloFin is developed in small, independently reviewable increments, frequently by
separate working sessions. The rules that keep that coherent:

1. `main` is always runnable, migrated, and documented.
2. Work happens on `feat/*` or `fix/*` branches with Conventional Commits.
3. A decision that a future contributor would otherwise have to re-derive goes
   into an ADR **before** the code that depends on it.
4. Non-trivial implementation work is specified as a brief in
   [`docs/briefs/`](docs/briefs) — scope, schemas, acceptance criteria — so that an
   independent session can execute it without access to this conversation.
5. The API contract, the domain model and the code change together.

See [`docs/AGENT_HANDOFF.md`](docs/AGENT_HANDOFF.md) for the full protocol.
