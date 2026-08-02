# ADR-0009: Forward-only SQL migrations

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead

## Context

Schema change in a system holding financial records is a release-management
problem before it is an engineering one. The properties that matter:

- The schema in every environment must be identifiable and comparable.
- A migration that has already been applied must never change afterwards —
  otherwise environments silently diverge and nobody can say what the schema is.
- Rollback on a table holding posted journal entries is largely a fiction: you
  cannot un-drop a column that held data.
- An auditor should be able to read the schema history as plain SQL.

## Decision

Migrations are **forward-only**, numbered, plain SQL files in
`resources/migrations/`, named `NNNN-description.sql` and applied in
lexicographic order by a small in-repository runner.

- The runner records each applied migration in `schema_migration` with its
  version, description, SHA-256 checksum and applied timestamp.
- If a recorded checksum no longer matches the file on disk, start-up **fails**.
  A migration that has been applied is immutable; a change is a new migration.
- Each migration runs in its own transaction, so a failure leaves the schema at
  the last good version rather than half-applied.
- There are no `down` migrations. A reversal is a new forward migration that
  performs the reverse change, written and reviewed with the same care as any
  other.
- Destructive changes are staged over at least two releases: stop writing, then
  drop in a later migration once no deployed version depends on the column.
- Migrations contain **no** application logic and no seed data beyond static
  reference data such as the currency registry. Test fixtures live in
  `test-resources/`, never in a migration.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Flyway or Liquibase** | Mature and, in a commercial team, very likely the right answer — Flyway in particular implements almost exactly this policy. Rejected here because the runner needed is about a hundred lines, and adding a runtime dependency requires the justification in [ADR-0004](0004-minimal-dependency-footprint.md). Liquibase additionally expresses schema in XML or YAML, which hides the SQL an auditor wants to read. |
| **Up/down migration pairs** (Rails/ActiveRecord style) | Encourages the belief that a production rollback is available. It is not, for any migration that has dropped or transformed data. A `down` script that has never been executed against production data is untested code with a dangerous name. |
| **ORM-generated schema diffs** | The generator decides what "the same schema" means, and the SQL actually executed against the ledger becomes an implementation detail. Unacceptable when the constraints in the schema *are* the control. |
| **Manually applied SQL with a runbook** | No mechanical guarantee that an environment is at a known version, and no way to detect a hand-edited schema. |
| **Idempotent full-schema scripts** (`CREATE TABLE IF NOT EXISTS …`) | Cannot express a data-transforming change, and makes the current schema a function of execution history rather than of the repository. |

## Consequences

**Positive**
- The schema of any environment is one query away, and comparable to the repository.
- Checksum verification makes silent divergence impossible rather than merely unlikely.
- The migration files are readable SQL — reviewable by a DBA or an auditor without tooling.
- The runner has no dependencies and runs identically in tests, containers and CI.

**Negative / accepted cost**
- No automated rollback. Reverting a bad release means writing and reviewing a forward migration under time pressure — accepted, because the alternative is a false sense of safety.
- Destructive changes take two releases, which is slower and is the correct speed.
- CloFin maintains its own runner, including its edge cases (concurrent start-up, partial application).

**Risks and how they are mitigated**
- *Risk:* two service instances start simultaneously and both try to migrate. *Mitigation:* the runner takes a PostgreSQL advisory lock for the duration.
- *Risk:* someone edits an applied migration to fix a typo. *Mitigation:* the checksum check fails start-up with the offending version named.
- *Risk:* a long migration blocks start-up and fails a health check. *Mitigation:* migrations are applied before the HTTP listener binds, and readiness reports the applied schema version.

## Verification

Integration tests assert that: migrations apply cleanly to an empty database;
re-running is a no-op; a tampered checksum aborts start-up; and `GET /readyz`
reports the expected schema version.
