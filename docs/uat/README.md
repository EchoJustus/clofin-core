# User acceptance test scripts

Each script is written so that a non-engineer — a finance reviewer, a business
analyst, an auditor — can execute it against a running instance and record a
result. Every step traces to a requirement in [`../PRD.md`](../PRD.md).

These are deliberately *manual* scripts. The automated suite proves the code
does what the code says; a UAT script proves the product does what a user was
promised, in the user's own terms. Both are needed, and they are not the same
document.

| Script | Covers | Requirements |
|---|---|---|
| [UAT-001 — Environment and service scope](UAT-001-environment-and-scope.md) | Stack starts; service states its scope honestly | NFR-001, NFR-005 |
| [UAT-002 — Ledger integrity cannot be bypassed](UAT-002-ledger-integrity.md) | Zero-sum invariant and immutability, tested adversarially | PR-020, PR-021, PR-022 |
| [UAT-003 — Account statement production](UAT-003-account-statement-production.md) | A statement that adds up, produced entirely through the API | PR-020, PR-021, PR-023, PR-024 |

## Recording a result

| Field | |
|---|---|
| Executed by | role, not name |
| Date | |
| Build | `git rev-parse --short HEAD` |
| Result | Pass / Fail / Blocked, per step |
| Evidence | command output, screenshot, or query result |
| Defects raised | issue references |

A step with no evidence is not a passed step.
