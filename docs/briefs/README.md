# Task briefs — the delegation backlog

A brief specifies one unit of work precisely enough that an **independent
session, with no access to the conversation that produced it**, can execute it.
Scope, interfaces, acceptance criteria and — critically — what is *out* of scope
and why.

Briefs are written **ahead of execution**, so the backlog stays healthy and no
worker session ever waits on planning. A brief blocked by a dependency is still
written; it simply names what it is blocked on.

**Naming:** `NNN-TASK-<short-feature>.md`, numbered sequentially, never renumbered.
Feedback from an architecture audit lands in [`../audits/`](../audits).

## Status lifecycle

| Status | Meaning |
|---|---|
| `READY` | Specified and executable. If `Depends on` is unmet, it is queued rather than startable. |
| `IN PROGRESS` | A session has claimed it. Set this in your first commit, so two sessions do not collide. |
| `IMPLEMENTED` | Code merged, acceptance criteria tested, CI green. Awaiting audit. |
| `AUDITED` | A `FEEDBACK` file has been ingested and any findings actioned or explicitly accepted. |
| `CLOSED` | Done. Kept, never deleted — the record of how the increment was specified. |

The status table at the top of each brief is updated **in the same commit** as
the work it describes. The repository is the only source of truth about what is
in flight (see [`../AGENT_HANDOFF.md`](../AGENT_HANDOFF.md)).

## Backlog

| Brief | Increment | Status | Depends on | Requirements | Scope |
|---|---|---|---|---|---|
| [001 — Ledger persistence and account API](001-TASK-ledger-persistence-and-account-api.md) | 2 | `IN PROGRESS` | — | PR-020…024 | Medium |
| [002 — Payment instruction lifecycle and idempotency](002-TASK-payment-instruction-lifecycle.md) | 3 | `READY` | 001 | PR-001…005, PR-040…044 | Large |
| [003 — Authorisation, maker–checker and audit trail](003-TASK-authorisation-and-audit-trail.md) | 4 | `READY` | 002 | PR-010…015, PR-070…075 | Large |

Sequencing follows **product relevance and regulatory risk**, not implementation
convenience:

- **001** first because nothing above the ledger is trustworthy until balances
  can be derived and read.
- **002** next because duplicate payments from retries are the failure with the
  most direct financial consequence.
- **003** next because segregation of duties and the audit trail are what an
  auditor asks about first, and they attach to the lifecycle 002 creates.

001 and 002 have a hard dependency (002 posts entries through the repository
001 builds). 002 and 003 do too. They can still be *planned* in parallel, and
002's state machine can be built against a stubbed repository if 001 is in
flight — but do not merge out of order.

## Writing a new brief

Template and rules: [`../AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §4.

A brief that cannot be executed without asking a question is not finished. The
test: hand it to someone who has read only the repository. If they would need to
ask "which one?" or "where does this go?", answer it in the brief first.

## Completed

Completed briefs are kept, not deleted. They are the record of how each
increment was specified, which is as useful as the code that resulted.

_(None yet. Increment 1 was the foundation and predates this protocol.)_
