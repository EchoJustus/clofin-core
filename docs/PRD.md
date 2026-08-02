# CloFin — Product Requirements

**Status:** living document · **Owner:** product · **Last reviewed:** 2026-08-02

> CloFin operates on synthetic data only. It is not connected to any bank,
> payment scheme or central bank, holds no regulatory authorisation, and never
> processes real funds. Requirements below describe a modelled product, not a
> live service.

---

## 1. Problem

A mid-sized enterprise that pays hundreds of suppliers a month typically runs
payments through a combination of an ERP module, a bank portal, and a
spreadsheet. The result is a set of failures that finance and audit teams
recognise immediately:

| Observed failure | What actually goes wrong |
|---|---|
| **Approval is theatre.** | Dual authorisation is enforced by process, not by system. The same person can prepare and release, and nothing records that they did. |
| **The ledger and the bank disagree.** | Reconciliation is a monthly spreadsheet exercise. A break found in March may have originated in January. |
| **Retries duplicate payments.** | A timeout on submission leaves the operator unsure whether the payment went out. Resubmitting sometimes pays twice. |
| **Corrections destroy evidence.** | A wrong payment is fixed by editing the record. The error disappears, and with it the ability to explain what happened. |
| **Audit is archaeology.** | "Who approved this, when, and against which limit?" takes days to answer, because the answer is spread across an email thread, a portal log and a spreadsheet. |

Each of these is a control failure before it is a software failure. CloFin
exists to show what the controls look like when they are built into the product.

## 2. Product goal

> Give a regulated enterprise a payments core in which **every movement of money
> is authorised, recorded, explainable and reconcilable — by construction rather
> than by procedure.**

## 3. Users

| User | What they need | What they must be prevented from doing |
|---|---|---|
| **Payments Operator** (maker) | Capture and submit instructions quickly; see exactly why one was rejected. | Approving or releasing their own instruction. |
| **Finance Approver** (checker) | Review a queue with the context needed to decide; approve or reject with a reason. | Approving above their limit; approving an instruction they created. |
| **Treasury Controller** | See positions and settlement status; authorise exceptions and write-offs. | Editing or deleting a posted entry. |
| **Compliance Analyst** | Review screening alerts and fraud cases; record a disposition with rationale. | Releasing a payment directly. |
| **Internal / External Auditor** | Extract a complete, immutable evidence trail for any payment or period. | Any write access at all. |
| **Client System** (ERP/TMS) | Submit instructions over an API with safe retry semantics. | Causing a duplicate payment through a retry. |

## 4. Scope

### In scope

1. Payment instruction capture, validation and lifecycle
2. Maker–checker authorisation with threshold-driven dual approval
3. Double-entry ledger with derived balances
4. Simulated clearing and settlement, including batch construction
5. Refunds, reversals and returns as compensating entries
6. Idempotent API semantics for every mutating operation
7. Bank statement ingestion, matching, breaks and ageing
8. Sanctions screening and rule-based fraud monitoring hooks
9. RBAC, segregation of duties, and an append-only audit trail
10. Evidence extraction for a nominated payment or period

### Out of scope (and why)

| Excluded | Reason |
|---|---|
| Real institutional connectivity | CloFin is a modelling exercise; claiming otherwise would be dishonest. |
| Cryptographic signing / HSM integration | The seam is designed; the implementation needs hardware and key custody that cannot be demonstrated openly. |
| Storage of identity documents | KYC is modelled as state and evidence *references*. Holding synthetic identity documents would model a bad practice. |
| FX rate sourcing | Rates are static reference data. Live rate procurement is a commercial integration, not a product insight. |
| General ledger / statutory reporting | Downstream of CloFin. The journal is designed to export cleanly to one. |

## 5. Requirements

Requirements are identified `PR-nnn` and are traced to acceptance criteria in
[`uat/`](uat) and, where they carry a control implication, to
[`COMPLIANCE.md`](COMPLIANCE.md).

### 5.1 Payment initiation

| ID | Requirement | Priority |
|---|---|---|
| PR-001 | An operator can create a payment instruction specifying debtor account, creditor details, amount, currency, value date and purpose. | Must |
| PR-002 | An instruction is validated on submission: known currency, positive amount, active accounts, mandatory fields for the selected payment type. | Must |
| PR-003 | An instruction is rejected with a structured, machine-readable reason naming every failed field — not the first one. | Must |
| PR-004 | A draft instruction may be amended or cancelled by its creator. A submitted one may not. | Must |
| PR-005 | Instructions can be submitted individually or as a batch, with per-item outcomes. | Should |

### 5.2 Authorisation

| ID | Requirement | Priority |
|---|---|---|
| PR-010 | Every instruction requires approval by an actor other than its creator. | Must |
| PR-011 | Approvals required scale with amount, against a configurable per-organisation threshold table. | Must |
| PR-012 | An approver cannot approve an amount above their own limit. | Must |
| PR-013 | Rejection requires a reason, which is retained. | Must |
| PR-014 | A change to any instruction field invalidates approvals already given. | Must |
| PR-015 | The approval queue shows an approver the context needed to decide: amount, counterparty, purpose, screening outcome, prior approvals. | Should |

### 5.3 Ledger

| ID | Requirement | Priority |
|---|---|---|
| PR-020 | Every movement of money is recorded as a journal entry whose debits equal its credits, per currency. | Must |
| PR-021 | Balances are derived from the journal; no authoritative balance is stored. | Must |
| PR-022 | Posted entries are immutable. Corrections are reversing entries referencing the original. | Must |
| PR-023 | An account statement can be produced for any account and period, showing opening balance, movements and closing balance. | Must |
| PR-024 | Entries may span multiple currencies, balancing within each. | Should |

### 5.4 Settlement

| ID | Requirement | Priority |
|---|---|---|
| PR-030 | Released instructions are grouped into settlement batches by scheme, currency and value date. | Must |
| PR-031 | A simulated scheme adapter accepts a batch and returns per-item outcomes, including partial failure. | Must |
| PR-032 | Settlement finality moves an instruction to `settled` and posts the corresponding entry. | Must |
| PR-033 | A returned item posts a reversing entry and raises an exception case. | Must |

### 5.5 Exceptions and idempotency

| ID | Requirement | Priority |
|---|---|---|
| PR-040 | Every mutating operation requires an `Idempotency-Key`. | Must |
| PR-041 | Replaying a key with an identical body returns the original response and performs no new work. | Must |
| PR-042 | Replaying a key with a different body returns `409 Conflict`. | Must |
| PR-043 | A settled payment can be reversed, producing a reversal referencing the original. | Must |
| PR-044 | A partial refund is supported and may not exceed the unreversed amount. | Should |

### 5.6 Reconciliation

| ID | Requirement | Priority |
|---|---|---|
| PR-050 | A synthetic bank statement can be ingested and each line matched against expected movements. | Must |
| PR-051 | Matching applies deterministic rules in a documented order, recording which rule matched. | Must |
| PR-052 | An unmatched line becomes a break with an age and an assignee. | Must |
| PR-053 | A break can be resolved by adjustment, which posts an entry and requires approval above a threshold. | Must |
| PR-054 | Reconciliation status is reportable per account and period. | Should |

### 5.7 Financial crime

| ID | Requirement | Priority |
|---|---|---|
| PR-060 | Every instruction is screened against a synthetic sanctions list before approval is possible. | Must |
| PR-061 | A screening hit blocks release and creates a case requiring disposition. | Must |
| PR-062 | Fraud rules evaluate velocity, unusual counterparty, and out-of-pattern amounts, producing a score and reasons. | Should |
| PR-063 | Every screening and fraud decision is retained with the rule version that produced it. | Must |

### 5.8 Governance

| ID | Requirement | Priority |
|---|---|---|
| PR-070 | Access is role-based with explicit permissions; a permission absent is a permission denied. | Must |
| PR-071 | Segregation of duties is enforced as a domain rule, not a UI restriction. | Must |
| PR-072 | Every state change appends an audit event carrying actor, action, subject, before/after digest, correlation id and timestamp. | Must |
| PR-073 | The audit trail is append-only and cannot be altered by the application role. | Must |
| PR-074 | An auditor can extract a complete evidence pack for a payment or a period. | Must |
| PR-075 | An audit event is written in the same transaction as the change it describes, so an unaudited change is not representable. | Must |

## 6. Non-functional requirements

| ID | Requirement | Target |
|---|---|---|
| NFR-001 | The whole stack starts from a clean clone with one command. | `make up`, under 5 minutes on a laptop |
| NFR-002 | Ledger invariants are verified by property-based tests. | Every release |
| NFR-003 | API contract and implementation cannot drift. | Contract test in CI |
| NFR-004 | No credential, account identifier or counterparty name appears in a log line. | Verified by review; asserted for correlation ids |
| NFR-005 | Error responses expose no internal detail outside development. | Asserted in tests |
| NFR-006 | Migrations are forward-only and checksum-verified. | Enforced by the runner |
| NFR-007 | Every runtime dependency is justified by an ADR. | Enforced at review |

## 7. Success criteria

CloFin is doing its job when a reviewer can, within thirty minutes of cloning:

1. Start the stack and see it answer.
2. Read one ADR and understand a trade-off that was genuinely made, including
   what was rejected.
3. Find the acceptance criteria for a control, and the test that proves it.
4. Attempt to violate the ledger invariant directly in SQL — and fail.

## 8. Open questions

| # | Question | Impact if deferred |
|---|---|---|
| Q1 | Should approval thresholds be per-currency, or normalised to a base currency? | Multi-currency organisations get inconsistent control strength. |
| Q2 | Should screening re-run on amendment, or only on submission? | A material amendment could bypass screening. |
| Q3 | Is a period-close snapshot needed before performance work, or after measurement? | Premature optimisation versus a slow statement endpoint. |
| Q4 | Should reconciliation matching be pluggable per organisation, or fixed? | Flexibility versus explainability of a match. |

These are recorded rather than answered because answering them changes the
product's shape, and the decision belongs in an ADR when it is made.
