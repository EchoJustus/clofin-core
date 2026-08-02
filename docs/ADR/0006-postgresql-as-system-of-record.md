# ADR-0006: PostgreSQL as system of record

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead

## Context

The ledger, the payment instructions and the audit trail need durable storage
with strong transactional guarantees. Specific requirements:

- A journal entry and all of its lines commit atomically, or not at all.
- Concurrent approval of the same payment must not produce two approvals.
- Idempotency keys need a uniqueness guarantee that survives concurrent requests.
- Journal and audit rows must be effectively append-only.
- Correctness constraints should be enforceable *below* the application, so that
  an application bug cannot corrupt the ledger.
- An auditor must be able to query the data directly, without the application.

## Decision

PostgreSQL 16 is the single system of record for all CloFin state.

Correctness is pushed into the schema wherever the database can express it,
rather than relying only on application code:

- `CHECK` constraints for positive amounts, known currency codes, and known
  status values.
- A **deferred** constraint trigger asserting that each journal entry's lines sum
  to zero per currency at commit time — so a multi-statement insert is legal
  while in flight but a lopsided entry can never be committed.
- `UNIQUE` constraints backing idempotency keys, so replay protection is a
  database guarantee rather than a read-then-write race.
- `UPDATE` and `DELETE` revoked on `journal_entry`, `journal_line` and
  `audit_event` for the application role.
- Balances are derived by query from the journal, never stored as an
  authoritative mutable value.

Transactions default to `READ COMMITTED`; operations that read state and then
decide on it (approval, release, reversal) use explicit row locking or
`SERIALIZABLE`, chosen per use case and documented at the call site.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **MySQL / MariaDB** | Workable, but weaker constraint expressiveness (no deferred constraint triggers in the same form, historically laxer defaults) and a less capable type system for the modelling here. |
| **A document store** (MongoDB, DynamoDB) | Multi-document transactional integrity is either unavailable or expensive, and there is no place to express a cross-row ledger invariant. Fundamentally the wrong shape for double-entry accounting. |
| **Event store + projections** (EventStoreDB, Kafka as source of truth) | Genuinely attractive for an immutable ledger, and worth revisiting. Rejected for now because it introduces eventual consistency into balance reads — which turns "what is the available balance for this payment?" into a subtle question at exactly the moment it must be simple. The append-only journal already provides the audit properties that motivate event sourcing. Revisit if a driver appears. |
| **A ledger-specific database** (TigerBeetle) | Purpose-built and impressive for this exact problem. Rejected because it covers only the ledger, so PostgreSQL would still be needed for payments, audit and reconciliation — two systems of record, and a distributed-transaction problem between them, for a project at this stage. |
| **SQLite** | Excellent for local development, but the concurrency model does not represent the production characteristics CloFin is modelling, and it would make the local stack unrepresentative. |

## Consequences

**Positive**
- ACID guarantees under concurrent approval and release.
- Invariants enforced below the application: a bug in Clojure cannot commit an unbalanced entry.
- Auditors and analysts can query the ledger with SQL, no application access required.
- Runs identically in a container locally and in any managed PostgreSQL service.

**Negative / accepted cost**
- Constraint logic is expressed in two places — schema and domain code — and both must be maintained. Accepted deliberately: defence in depth on the ledger invariant is worth the duplication, and the integration tests assert that both agree.
- A single relational store will eventually become a scaling bottleneck. Not a driver at this stage; partitioning the journal by period is the first move when it is.
- Revoking `UPDATE`/`DELETE` means corrections are always compensating entries, which is more work at the application layer — and is the correct accounting behaviour anyway.

**Risks and how they are mitigated**
- *Risk:* migrations diverge between environments. *Mitigation:* forward-only migrations with recorded checksums ([ADR-0009](0009-forward-only-sql-migrations.md)) and a readiness probe that reports the applied schema version.
- *Risk:* the deferred trigger is expensive on large batches. *Mitigation:* measured in integration tests; batch posting groups lines per entry and commits per batch.

## Verification

Integration tests (`make test-it`) assert that an unbalanced entry is rejected by
the database even when the application-level check is bypassed, and that
`UPDATE` and `DELETE` on `journal_line` fail for the application role.
