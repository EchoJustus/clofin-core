# Architecture audits and feedback

CloFin is developed asynchronously: worker sessions implement briefs
continuously, while architecture review happens **offline, at milestones**,
rather than blocking each increment. This directory is where that review lands
and becomes durable.

**Naming:** `FEEDBACK-NNN-<subject>.md`, matching the brief number it reviews
where one applies (`FEEDBACK-001-…` reviews `TASK-001`), or a milestone name for
a broader audit (`FEEDBACK-M1-foundation.md`).

> A review that exists only in a conversation does not exist. If an audit
> finding is worth acting on, it is worth committing here — otherwise the next
> session re-introduces the same anti-pattern, and the one after that re-argues
> whether it was a problem.

---

## What an audit produces

An audit is expected to state, for each finding:

| Field | |
|---|---|
| **Severity** | `blocking` · `should-fix` · `consider` |
| **Finding** | What is wrong, specifically, with a file and line where possible |
| **Why it matters** | The consequence — ideally in product or control terms, not only engineering ones |
| **Suggested direction** | Not necessarily a patch; a direction is enough |
| **Affects** | Which briefs or namespaces |

## How feedback is ingested

When a `FEEDBACK` file lands, the orchestrating session:

1. **Reads it in full** before touching any code.
2. **Triages each finding.** A finding is actioned, deferred with a stated
   reason, or disputed with evidence. All three are legitimate; silence is not.
3. **Applies blocking findings** to the code, and updates the affected brief's
   `Status` back to `IN PROGRESS` if it had been `IMPLEMENTED`.
4. **Propagates the lesson forward.** This is the part with lasting value: a
   flagged anti-pattern is added to the *Notes for whoever picks this up*
   section of every brief where it could recur, so it is prevented rather than
   re-corrected.
5. **Writes an ADR** if a finding changes a decision. An accepted ADR is never
   edited — it is superseded by a new one that links back
   ([ADR-0001](../ADR/0001-record-architecture-decisions.md)).
6. **Records the disposition** in the Register below, and sets the brief's
   `Audit` field to reference the feedback file.

A finding that is disputed must be answered with evidence — a test, a
constraint, a benchmark — not with an assertion. The project's whole claim is
that its statements are checkable; that applies to its replies to reviewers too.

## Register

| Feedback | Reviews | Received | Findings (B/S/C) | Disposition |
|---|---|---|---|---|
| _(none yet)_ | | | | |

## Standing lessons

Anti-patterns confirmed by audit, to be avoided in all future work. Each entry
names the brief section that now guards against it, so the guard can be checked.

| # | Anti-pattern | Guarded by |
|---|---|---|
| _(none yet — populated as audits arrive)_ | | |

Until an audit populates this table, the standing constraints are the ones in
[`../AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §3 — the rules that must not be
broken.
