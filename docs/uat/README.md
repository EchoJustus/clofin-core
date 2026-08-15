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
| [UAT-004 — A retried submission cannot pay twice](UAT-004-idempotent-submission.md) | A double submission performed by hand, and the evidence that it acted once | PR-001, PR-003, PR-004, PR-040, PR-041, PR-042 |
| [UAT-005 — Segregation of duties, attempted and refused](UAT-005-segregation-of-duties.md) | Self-approval attempted by hand and refused; approval thresholds and limits; an amendment killing its approvals; the audit trail resisting alteration | PR-010, PR-011, PR-012, PR-013, PR-014, PR-015, PR-070, PR-072, PR-073, PR-074, PR-075 |
| [UAT-006 — Settlement, and the four ways a scheme misbehaves](UAT-006-settlement-simulation.md) | Partial batch failure, a duplicate response, a late contradiction, and a timeout whose true outcome is unknown — with the re-batch attempted by hand in SQL and refused | PR-030, PR-031, PR-032, NFR-003 |
| [UAT-007 — Reconciliation: the disagreements, and what happens to them](UAT-007-reconciliation-and-breaks.md) | A statement ingested and matched rule by rule, a payment nobody answered about that is correctly absent, breaks with an owner and a derived age, and a correction posted only after somebody other than its author agreed | PR-050, PR-051, PR-052, PR-053, PR-054 |

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
