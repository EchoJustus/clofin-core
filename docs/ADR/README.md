# Architecture Decision Records

An ADR captures a decision that a future contributor would otherwise have to
re-derive — including the options that were rejected and why. If a decision only
makes sense once you have read the code, it belongs here instead.

**Write the ADR before the code that depends on it.** An ADR written afterwards
is a justification, not a decision.

## Index

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-clojure-for-the-ledger-core.md) | Clojure for the ledger and rules core | Accepted |
| [0003](0003-money-as-integer-minor-units.md) | Money as integer minor units | Accepted |
| [0004](0004-minimal-dependency-footprint.md) | Minimal runtime dependency footprint | Accepted |
| [0005](0005-hybrid-local-and-cloud-execution.md) | Hybrid local and cloud execution | Accepted |
| [0006](0006-postgresql-as-system-of-record.md) | PostgreSQL as system of record | Accepted |
| [0007](0007-modular-monolith-over-microservices.md) | Modular monolith over microservices | Accepted |
| [0008](0008-double-entry-journal-as-source-of-truth.md) | Double-entry journal as source of truth | Accepted |
| [0009](0009-forward-only-sql-migrations.md) | Forward-only SQL migrations | Accepted |
| [0010](0010-thin-ring-compatible-http-adapter.md) | Thin Ring-compatible HTTP adapter | Accepted |
| [0011](0011-statement-periods-ordering-and-row-caps.md) | Statement periods, movement ordering and the row cap | Accepted |
| [0012](0012-repository-seam-and-posting-time-validation.md) | Repository namespaces as the persistence seam, and where posting-time validation lives | Accepted |
| [0013](0013-canonical-request-digest-for-idempotency.md) | Canonical request digest for idempotency keys | Accepted |
| [0014](0014-payment-lifecycle-as-data.md) | The payment instruction lifecycle is data, and rules that are not transitions say so | Accepted |

## Conventions

- Files are numbered sequentially and never renumbered.
- Status is one of `Proposed`, `Accepted`, `Superseded by ADR-nnnn`, `Deprecated`.
- An accepted ADR is **never edited to change its decision**. It is superseded by
  a new ADR that links back to it. The record of what was believed at the time is
  the point.
- Start from [`0000-adr-template.md`](0000-adr-template.md).
