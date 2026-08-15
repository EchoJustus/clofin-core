# Task briefs — the delegation backlog

> **Control plane.** This directory is maintained on the **`meta` branch** by
> Master Control, in batches of one to five briefs. The copy on `origin/meta`
> is the current one; read it without checking out:
> `git fetch origin meta && git show origin/meta:docs/briefs/<file>`.
> Copies on `main` or feature branches are historical snapshots. Workers never
> commit to `meta` — see [`../AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §1b.

A brief specifies one unit of work precisely enough that an **independent
session, with no access to the conversation that produced it**, can execute it.
Scope, interfaces, acceptance criteria, the **base branch to build on** (which,
in a stacked batch, may be an unmerged feature branch rather than `main`) and —
critically — what is *out* of scope and why.

Briefs are written **ahead of execution**, so the backlog stays healthy and no
worker session ever waits on planning. A brief blocked by a dependency is still
written; it simply names what it is blocked on and where to stack.

**Naming:** `NNN-TASK-<short-feature>.md`, numbered sequentially, never renumbered.
Feedback from an architecture audit lands in [`../audits/`](../audits) on `meta`.

## Status lifecycle

| Status | Meaning |
|---|---|
| `READY` | Specified and executable. If `Depends on` is unmet, the brief names the branch to stack on. |
| `IN PROGRESS` | Dispatched to a Worker. Set by Master Control on `meta` at dispatch — being handed the brief **is** the claim. |
| `IMPLEMENTED` | Worker reported done: PR open, acceptance criteria tested, CI green, `REQ` filed. Awaiting audit. |
| `AUDITED` | A `FEEDBACK` file has been ingested and any findings actioned or explicitly accepted. |
| `CLOSED` | Merged to `main`. Kept, never deleted — the record of how the increment was specified. |

Only Master Control moves a brief between states, on `meta`. A Worker who thinks
a status is wrong says so in its `REQ`; it does not edit the brief. If this
table and a brief disagree, the brief on `origin/meta` wins.

## Backlog

| Brief | Increment | Status | Depends on | Requirements | Scope |
|---|---|---|---|---|---|
| [001 — Ledger persistence and account API](001-TASK-ledger-persistence-and-account-api.md) | 2 | `CLOSED` — merged in PR #2; **audited** (FEEDBACK-M1: F-002/F-003/F-004, actioned via the increment-4 stack) | — | PR-020…024 | Medium |
| [002 — Payment instruction lifecycle and idempotency](002-TASK-payment-instruction-lifecycle.md) | 3 | `CLOSED` — merged in PR #4 (`31306dd`); **audited** (FEEDBACK-M1: no new findings) | 001 ✅ | PR-001…005, PR-040…044 | Large |
| [003 — Authorisation, maker–checker and audit trail](003-TASK-authorisation-and-audit-trail.md) | 4 | `CLOSED` — merged in PR #5 (`5ff00eb`); FEEDBACK-M1 fully remediated & verified | 002 ✅ | PR-010…015, PR-070…075 | Large |
| [004 — Settlement simulation](004-TASK-settlement-simulation.md) | 5 | `CLOSED` — merged in PR #7 (`cba31c5`); FEEDBACK-M2 remediation merged in PR #8 (`5d21334`) | 003 ✅, 005 ✅ merged | PRD §5.3 | Large |
| [005 — Audit coverage completion](005-TASK-audit-coverage-completion.md) | 4 (completion) | `CLOSED` — merged in PR #6 (`2ba977e`); three objections ruled for the Worker | 003 ✅ merged | PR-072, C-05 | Small |
| [006 — Generated diagrams and CI doc guards](006-TASK-generated-diagrams.md) | 5v.1 | `CLOSED` — merged in PR #12 (`2237a39`); five objections ruled for the Worker, O-1 actioned on `meta` (L-16) | ADR-0020 ✅ | D5, L-4, L-15 | Medium |
| [007 — `clofin-trace` replay walkthrough](007-TASK-clofin-trace.md) | 5v.2 | `CLOSED` — part A merged in PR #14 (`261c778`), part B merged in `clofin-trace` PR #1 (`71cb13f`); five objections ruled for the Worker (O-1 → tag-form correction) | 006 ✅, ADR-0020 ✅, `ref-1` ✅ | D5, PR-015 | Large |
| [008 — Reconciliation](008-TASK-reconciliation.md) | 6 | `CLOSED` — merged in PR #16 (`a41e69f`); four objections ruled, O-1/O-2 routed to TASK-010 | 004 ✅, 006 ✅ | PR-050…054 | Large |
| [009 — Trace hardening and cross-links](009-TASK-trace-hardening-and-cross-links.md) | 5v.3 | `CLOSED` — merged in PR #17 (`ddf39c1`) + `clofin-trace` PR #2 (`bc0017c`); duplicate dispatch's PR #3 closed unmerged | 007 ✅ | D5 | Small |
| [010 — Reconciliation completion](010-TASK-reconciliation-completion.md) | 6c | `CLOSED` — merged in PR #19 (`37d2d02`); two objections ruled for the Worker | 008 ✅ | ADR-0019, C-05, 008-REQ O-1/O-2/N-5 | Medium |
| [011 — `clofin-cockpit` initialization](011-TASK-cockpit-initialization.md) | 8.1 | `CLOSED` — PR #21 (`eb3a561`) + `clofin-cockpit` PR #1 (`f20f4a6`); two objections ruled for the Worker | cockpit repo ✅ | D5, ADR-0026 | Medium |
| [012 — Cockpit connect and bootstrap](012-TASK-cockpit-connect-and-bootstrap.md) | 8.2 | `CLOSED` — PR #23 (`f174116`) + `clofin-cockpit` PR #2 (`90abb1d`); three objections ruled for the Worker; N-1 pre-declared to the Sol audit | 011 ✅ | D5, ADR-0027 | Large |

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

- **004** because settlement is the product function everything so far exists
  to control, and reconciliation (increment 6) consumes what it produces.
- **005** because C-05 carries a scope asterisk until every write is audited,
  and a compliance claim with an asterisk is a finding waiting to be written.

**004 and 005 both stack on TASK-003's branch and both touch the
`clofin.audit/actions` literal.** They may be planned in parallel but Master
Control sequences dispatch; whichever lands second rebases over a one-line
conflict. 005 is a single sitting — dispatching it first is the default.

**008 and 009 share no files and may run in parallel, in separate Worker
sessions.** 009's only `clofin-core` change is one README line; 008 does not
touch the README. Neither waits on the other.

## Writing a new brief

Template and rules: [`../AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §4.

A brief that cannot be executed without asking a question is not finished. The
test: hand it to someone who has read only the repository. If they would need to
ask "which one?" or "where does this go?", answer it in the brief first.

## Completed

Completed briefs are kept, not deleted. They are the record of how each
increment was specified, which is as useful as the code that resulted.

| Brief | Increment | Merged | Audit |
|---|---|---|---|
| [001 — Ledger persistence and account API](001-TASK-ledger-persistence-and-account-api.md) | 2 | PR #2 (`f7018a1`) | FEEDBACK-M1 — F-002/F-003/F-004, all remediated via the increment-4 stack |
| [002 — Payment instruction lifecycle and idempotency](002-TASK-payment-instruction-lifecycle.md) | 3 | PR #4 (`31306dd`) | FEEDBACK-M1 — no new findings; eight brief objections ruled at dispatch |
| [003 — Authorisation, maker–checker and audit trail](003-TASK-authorisation-and-audit-trail.md) | 4 | PR #5 (`5ff00eb`) | FEEDBACK-M1 — F-001/F-002 blocking + F-005/F-006, all remediated & verified; four brief objections ruled at dispatch |
| [005 — Audit coverage completion](005-TASK-audit-coverage-completion.md) | 4 (completion) | PR #6 (`2ba977e`); its post-merge contract fix landed via PR #7 (`b21d4c1`) | Awaiting Milestone 2 batch audit; three brief objections ruled at ingestion; the L-9 tail recorded |
| [004 — Settlement simulation](004-TASK-settlement-simulation.md) | 5 | PR #7 (`cba31c5`) + remediation PR #8 (`5d21334`) | FEEDBACK-M2 — F-007 blocking + F-008/F-009/F-010, all remediated & verified; three brief objections ruled at ingestion, four remediation divergences ruled accepted |
| [006 — Generated diagrams and CI doc guards](006-TASK-generated-diagrams.md) | 5v.1 | PR #12 (`2237a39`); the deferred `verify` wiring landed with the O-1 fix sync | Awaiting the next milestone batch audit; five objections ruled at ingestion, all for the Worker; O-1 produced lesson L-16 |
| [008 — Reconciliation](008-TASK-reconciliation.md) | 6 | PR #16 (`a41e69f`) | Awaiting the deferred batch audit at the **Sol** tier (A1: authz enforcement code + migration); four objections ruled at ingestion, O-1/O-2 → TASK-010, O-3 ratified, O-4 confirmed |
| [009 — Trace hardening and cross-links](009-TASK-trace-hardening-and-cross-links.md) | 5v.3 | PR #17 (`ddf39c1`) + `clofin-trace` PR #2 (`bc0017c`) | Awaiting the deferred batch audit; no objections |
| [010 — Reconciliation completion](010-TASK-reconciliation-completion.md) | 6c | PR #19 (`37d2d02`) | Awaiting the deferred batch audit at the **Sol** tier; two objections ruled at ingestion, both confirming the Worker against the brief's own text; N-1/N-3/N-4 routed to the operational-hardening brief |
| [011 — `clofin-cockpit` initialization](011-TASK-cockpit-initialization.md) | 8.1 | PR #21 (`eb3a561`) + `clofin-cockpit` PR #1 (`f20f4a6`) | ADR-0026 joins the deferred Sol audit's scope; two objections ruled at ingestion (O-1 → the Tags-API rule; O-2 → the README tense fix, executed by Master Control) |
| [007 — `clofin-trace` replay walkthrough](007-TASK-clofin-trace.md) | 5v.2 | `clofin-core` PR #14 (`261c778`) + `clofin-trace` PR #1 (`71cb13f`) | Awaiting the next milestone batch audit; five objections ruled at ingestion, all for the Worker; O-1 corrected the register's tag-form claims |
