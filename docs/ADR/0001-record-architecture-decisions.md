# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead / product owner

## Context

CloFin is developed in short, independent increments, frequently by separate
working sessions that do not share memory of earlier discussion. Without a
durable record, each session re-derives — and often silently reverses — earlier
decisions.

The project also has a second audience. CloFin is a public demonstration of
product and analysis capability in regulated payments, where the *reasoning*
behind a control is at least as interesting as the control itself. A reviewer
should be able to see why dual authorisation is threshold-driven, or why a
correction is a reversing entry rather than an update, without reading the
implementation.

## Decision

We keep architecture decision records in `docs/ADR/`, one Markdown file per
decision, numbered sequentially and never renumbered. Any decision that a future
contributor would otherwise have to re-derive is recorded before the code that
depends on it. Accepted ADRs are immutable: a changed decision is a new ADR that
supersedes the old one.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Decisions in commit messages | Not discoverable. Nobody reviews a product's rationale by reading `git log`, and the reasoning is lost the moment the code is refactored. |
| A single long design document | Edits overwrite history, so the record of *what was believed at the time* — the part with audit value — disappears. |
| An external wiki | Falls out of step with the code, is not reviewable in a pull request, and is invisible to anyone reading the repository. |
| No formal record | The failure mode this project exists to demonstrate competence against. |

## Consequences

**Positive**
- A new session or contributor can reconstruct the design from the repository alone.
- Rejected alternatives are preserved, so old ground is not re-argued.
- ADRs are reviewed in pull requests alongside the code they govern.

**Negative / accepted cost**
- Writing an ADR costs time before implementation starts.
- The index needs maintaining.

**Risks and how they are mitigated**
- *Risk:* ADRs drift out of date. *Mitigation:* they are immutable by convention, so drift shows up as a missing superseding ADR rather than as a quietly wrong document.

## Verification

`docs/ADR/README.md` carries the index. Every ADR referenced from
`ARCHITECTURE.md` must exist; the documentation link check in CI fails the build
if a referenced ADR is missing.
