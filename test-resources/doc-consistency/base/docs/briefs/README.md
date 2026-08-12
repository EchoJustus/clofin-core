# Fixture — Task briefs

Synthetic fixture for `clofin.tools.doc-consistency-test`.

## Status lifecycle

| Status | Meaning |
|---|---|
| `READY` | Specified and executable. |
| `IN PROGRESS` | Dispatched to a Worker. |
| `IMPLEMENTED` | Worker reported done; awaiting audit. |
| `AUDITED` | Feedback ingested and actioned. |
| `CLOSED` | Merged to `main`. |

## Backlog

| Brief | Increment | Status | Depends on |
|---|---|---|---|
| [001 — Ledger](001-TASK-ledger.md) | 2 | `CLOSED` — merged in PR #2 | — |
| [002 — Diagrams](002-TASK-diagrams.md) | 5v.1 | `READY` | 001 ✅ |
| [003 — Trace](003-TASK-trace.md) | 5v.2 | `READY` | 002 |

## Completed

| Brief | Increment | Merged | Audit |
|---|---|---|---|
| [001 — Ledger](001-TASK-ledger.md) | 2 | PR #2 | none |
