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
| [0014](0014-payment-lifecycle-as-data.md) | The payment instruction lifecycle is data, and rules that are not transitions say so | Accepted (amended 1) |
| [0015](0015-approval-thresholds-are-per-currency.md) | Approval thresholds and approver limits are per currency, never normalised | Accepted |
| [0016](0016-audit-events-store-digests-not-payloads.md) | The audit trail stores digests, not payloads | Accepted |
| [0017](0017-bootstrap-identity-for-organisation-creation.md) | The bootstrap write records no actor, and the null is enforced | Accepted |
| [0018](0018-release-posts-to-settlement-in-transit.md) | A release posts to settlement-in-transit; finality moves that leg | Accepted |
| [0019](0019-a-returned-payment-is-terminal-and-retries-as-a-new-instruction.md) | A returned payment is terminal; the retry is a new instruction | Accepted |
| [0020](0020-two-repositories-and-the-generate-replay-rules.md) | Two repositories, and the rules that govern anything visual | Accepted (amended 1) |
| [0021](0021-diagrams-are-mermaid-generated-from-code-and-tables.md) | Diagrams are Mermaid, generated from code and tables, on a tools path | Accepted |
| [0022](0022-the-capture-harness-establishes-its-own-provenance.md) | The capture harness establishes its own provenance, and fails closed | Accepted |
| [0023](0023-a-clofin-defined-synthetic-statement-format-and-an-ordered-matching-sequence.md) | A CloFin-defined synthetic statement format, and an ordered matching sequence | Accepted |
| [0024](0024-a-retry-names-the-returned-payment-it-replaces.md) | A retry names the returned payment it replaces | Accepted |
| [0025](0025-two-audit-terms-for-changes-the-trail-did-not-carry.md) | Two audit terms for changes the trail did not carry — a restated batch status, and a rejected adjustment | Accepted |
| [0026](0026-three-repositories-and-the-cockpits-role-boundary.md) | Three repositories, and the cockpit's role boundary | Accepted |

## Conventions

- Files are numbered sequentially and never renumbered.
- Status is one of `Proposed`, `Accepted`, `Superseded by ADR-nnnn`, `Deprecated`.
- An accepted ADR is **never edited to change its decision**. It is superseded by
  a new ADR that links back to it. The record of what was believed at the time is
  the point.
- An ADR whose decision was explicitly conditional — "not until X exists" — may
  gain an **amendment** section when X arrives, stating what changed and why the
  original reasoning no longer applies. The original decision text stays intact
  above it. ADR-0013 and ADR-0014 both carry one.
- Start from [`0000-adr-template.md`](0000-adr-template.md).
