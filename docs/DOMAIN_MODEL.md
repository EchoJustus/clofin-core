# CloFin — Domain Model

**Status:** living document · **Applies to:** all contexts

This is the shared vocabulary. Terms defined here mean the same thing in the
code, the API contract, the tests and every conversation about the product.
Where a word is commonly used loosely in payments, the definition below says
which sense CloFin means — and, where it matters, which sense it does not.

> Synthetic data only. No entity, account or counterparty described here
> corresponds to anything real.

Legend: **✅ built** · **🔨 in progress** · **📋 specified, not built**

---

## 1. Ubiquitous language

| Term | Definition | Not to be confused with |
|---|---|---|
| **Organisation** | A tenant. Every business record belongs to exactly one. | The legal entity being paid — that is a Counterparty. |
| **Ledger Account** | An internal account in the double-entry ledger, holding one currency. | A bank account. CloFin's ledger accounts are internal; a bank account is an External Account. |
| **External Account** | An account at a financial institution, identified by scheme-specific details. | A Ledger Account. |
| **Counterparty** | A party CloFin pays or is paid by. | A user of the system. |
| **Payment Instruction** | A request to move money, with a lifecycle. Mutable while `draft`; immutable in substance thereafter. | A Journal Entry. The instruction is the intent; the entry is the accounting fact. |
| **Journal Entry** | One economic event, recorded as two or more balancing lines. Immutable once posted. | A Payment Instruction. One instruction typically produces several entries over its life. |
| **Journal Line** | One account's participation in an entry: a direction and a positive amount. | A "transaction". |
| **Posting** | The act of writing a journal entry. | Sending money. Posting is an accounting act; release is a payment act. |
| **Balance** | Derived by aggregating an account's journal lines. Never stored as authority. | A stored figure. There isn't one. |
| **Maker** | The actor who creates and submits an instruction. | The beneficiary. |
| **Checker** | The actor who approves. Must not be the maker. | A reviewer with no system authority. |
| **Release** | The act of handing an approved instruction to settlement. | Approval. Approval permits release; it is not release. |
| **Settlement** | Irrevocable transfer of value through a scheme. | Clearing, which is the exchange of instructions preceding it. |
| **Reversal** | A new entry mirroring an original, leaving both visible. | Deletion, amendment, or "cancellation" of a posted entry — none of which exist. |
| **Return** | A payment sent back by the receiving institution after settlement. | A Refund, which CloFin's own user initiates. |
| **Break** | A reconciliation item that did not match, with an age and an owner. | A discrepancy that someone will look at later. A break is a tracked object. |
| **Idempotency Key** | A caller-supplied identifier making a retry safe. | A payment reference. |
| **Audit Event** | An append-only record of a state change: who, what, when, before/after. | An application log line. |

---

## 2. Entities

### 2.1 Ledger context ✅

```
Organisation 1───* LedgerAccount
                        │
                        *
                        │
JournalEntry 1───* JournalLine
     │
     └── reverses ──▶ JournalEntry (0..1, at most once)
```

**LedgerAccount** ✅
| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `organisation-id` | UUID | |
| `code` | string | Uppercase alphanumeric and hyphens, unique per organisation. Appears in exports. |
| `name` | string | |
| `type` | `:asset` `:liability` `:equity` `:revenue` `:expense` | Determines the normal balance side. |
| `currency` | ISO 4217 | An account holds exactly one currency. |
| `status` | `:active` `:frozen` `:closed` | Only `:active` accepts postings; history stays readable in all states. |

**JournalEntry** ✅
| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `organisation-id` | UUID | |
| `occurred-at` | instant | When the economic event happened — supplied by the caller, not read from a clock. |
| `recorded-at` | instant | When CloFin learned of it. The two differ, and both matter. |
| `narrative` | string | Required. A movement without an explanation is not acceptable. |
| `reference` | `{:type … :id UUID}` | The business object that caused the movement. Required. |
| `reverses-id` | UUID? | Set on a reversal. An entry may be reversed at most once. |
| `lines` | JournalLine[] | At least two; debits equal credits per currency. |

**JournalLine** ✅
| Field | Type | Notes |
|---|---|---|
| `account-id` | UUID | |
| `direction` | `:debit` `:credit` | Explicit; the sign is never encoded in the amount. |
| `amount` | Money | Strictly positive. |

**Money** ✅ — `{:currency "SGD" :minor-units 125000}`. Integer minor units
against an ISO 4217 scale. Never floating point.
See [ADR-0003](ADR/0003-money-as-integer-minor-units.md).

### 2.2 Payments context 🔨

**PaymentInstruction** 🔨
| Field | Notes |
|---|---|
| `id`, `organisation-id` | ✅ |
| `debtor-account-id` | A LedgerAccount. ✅ Must be `active` and hold the instruction's currency. |
| `creditor-name`, `creditor-account` | ✅ Counterparty and External Account details, synthetic. |
| `amount` | Money. ✅ |
| `value-date` | Requested settlement date. ✅ A calendar date, not an instant. |
| `purpose-code` | Constrained vocabulary; several corridors require one. ✅ |
| `status` | See §3. ✅ |
| `created-by`, `created-at` | ✅ `created-by` is caller-asserted until TASK-003 delivers authentication. |
| `reverses-id` | ✅ Set on an instruction raised to reverse a settled one. |
| `screening-outcome` | 📋 Reference to the screening decision that permitted approval. Increment 7. |

**IdempotencyKey** ✅ — `(organisation-id, key)`, with the digest of the request
and the response it produced.

Modelled as a **record of its own** rather than as a field on the instruction,
because the key protects *every* mutating operation — a submission, an
amendment, a cancellation — and not only the creation of an instruction. A key
that lived on `payment_instruction` could make creation idempotent and nothing
else, which would leave submission unprotected: precisely the operation whose
timeout the control exists for. See
[C-06](COMPLIANCE.md) and
[ADR-0013](ADR/0013-canonical-request-digest-for-idempotency.md).

**Approval**
| Field | Notes |
|---|---|
| `instruction-id`, `actor-id`, `decision`, `reason`, `decided-at` | A rejection reason is mandatory. Approvals are invalidated by any amendment. |

### 2.3 Settlement context 📋

**SettlementBatch** — instructions grouped by scheme, currency and value date.
**SettlementItem** — one instruction within a batch, with its own outcome.
Partial batch failure is the normal case, not an edge case.

### 2.4 Reconciliation context 📋

**StatementLine** — an ingested synthetic bank statement line.
**Match** — a StatementLine bound to expected movements, recording *which rule*
matched, so a match can be explained.
**Break** — an unmatched line or movement, with an age, an owner and a
resolution. Resolution by adjustment posts an entry and may require approval.

### 2.5 Compliance context 📋

**ScreeningResult** — outcome, matched list entries, and the *list version*.
Without the version, a past decision cannot be reproduced.
**FraudAssessment** — score, contributing reasons, and the rule-set version.
**Case** — an alert requiring human disposition, with rationale retained.

### 2.6 Audit context 📋

**AuditEvent** — `actor`, `action`, `subject-type`, `subject-id`,
`before-digest`, `after-digest`, `correlation-id`, `occurred-at`. Append-only,
written in the same transaction as the change it describes.

---

## 3. Payment instruction lifecycle 🔨

```
                    ┌──────────────── amend ────────────────┐
                    ▼                                       │
   ┌───────┐    submit    ┌──────────────────┐   approve  ┌─┴────────┐
   │ draft │─────────────▶│ pending_approval │───────────▶│ approved │
   └───┬───┘              └────────┬─────────┘            └────┬─────┘
       │                           │                           │
       │ cancel                    │ reject                    │ release
       ▼                           ▼                           ▼
  ┌───────────┐              ┌──────────┐               ┌──────────┐
  │ cancelled │              │ rejected │               │ released │
  └───────────┘              └──────────┘               └────┬─────┘
                                                             │
                                            ┌────────────────┼────────────────┐
                                            ▼                ▼                ▼
                                      ┌─────────┐      ┌────────┐      ┌──────────┐
                                      │ settled │      │ failed │      │ returned │
                                      └────┬────┘      └────────┘      └──────────┘
                                           │ reverse
                                           ▼
                                    ┌──────────────┐
                                    │ new reversal │
                                    │  instruction │
                                    └──────────────┘
```

Rules that the diagram alone does not carry:

1. 📋 `submit` requires screening to have completed. A pending screening blocks
   submission rather than queuing behind it. *(Increment 7. There is a
   `TODO(increment-7)` at the precondition it will gate.)*
2. 📋 `approve` requires an actor other than the maker, within their limit, and
   enough approvals to satisfy the threshold for the amount. *(TASK-003.)*
3. 📋 `amend` on a `pending_approval` instruction returns it to `draft` and
   **invalidates every approval given so far** (PR-014). *(TASK-003. The
   transition is in the table; nothing drives it yet. It is **not** what
   `PATCH /payment-instructions/{id}` does — see below.)*
4. ✅ `settled` is terminal. A settled payment is never mutated; it is followed
   by a *new* reversal instruction.
5. 📋 `returned` posts a reversing entry automatically and opens an exception
   case. *(Increment 5.)*

Transitions are held as data in `clofin.payments.state`, so the table above can
be generated from the code and tested exhaustively rather than sampled — every
(state, event) pair is walked, not a chosen few.

**Two rules about status are not transitions**, and are held as named sets
beside the table rather than as conditionals in a handler
([ADR-0014](ADR/0014-payment-lifecycle-as-data.md)):

- ✅ `mutable-states` — an instruction is *mutable while `draft`, immutable in
  substance thereafter* (§1). Amending a draft leaves it in `draft`, so it moves
  along no arrow. This, not the `amend` event, is what governs
  `PATCH /payment-instructions/{id}`.
- ✅ `reversible-states` — a reversal may be raised only against a `settled`
  instruction, per rule 4. The original is untouched, so this is not a
  transition either.

**Built so far:** `submit` and `cancel` have endpoints. `approve`, `reject`,
`amend`, `release`, `settle`, `fail` and `return` are in the table, tested, and
driven by nothing — a transition with no caller is still part of the model, and
the increment that adds the endpoint gets to drive it, not to decide where it
leads.

---

## 4. Chart of accounts (synthetic reference model)

A minimal chart sufficient to express supplier payment, fees and settlement.

| Code | Name | Type | Purpose |
|---|---|---|---|
| `1100-CLIENT-FUNDS` | Client funds — pooled | asset | Money held on behalf of clients. |
| `1200-NOSTRO` | Nostro — settlement bank | asset | The institution's balance at its settlement bank. |
| `1300-IN-TRANSIT` | Settlement in transit | asset | Value released but not yet settled. |
| `2100-CLIENT-PAYABLE` | Client payable | liability | The obligation to each client for funds held. |
| `2200-UNAPPLIED` | Unapplied receipts | liability | Received but not yet allocated — the account where reconciliation breaks live. |
| `4100-FEE-INCOME` | Transaction fee income | revenue | |
| `5100-SCHEME-CHARGES` | Scheme and correspondent charges | expense | |

**Worked example — supplier payment of SGD 1,250.00 with a SGD 5.00 fee:**

| Step | Debit | Credit | Amount |
|---|---|---|---|
| Release | `1300-IN-TRANSIT` | `1100-CLIENT-FUNDS` | 1,250.00 |
| Fee | `2100-CLIENT-PAYABLE` | `4100-FEE-INCOME` | 5.00 |
| Settlement | `1100-CLIENT-FUNDS`* | `1300-IN-TRANSIT` | 1,250.00 |

\* On settlement the in-transit asset is released against the nostro movement;
the exact pair depends on the scheme, which is why posting templates are per
payment type rather than global.

Each row is one entry, balancing on its own. Every entry is reversible by
mirroring its directions.

---

## 5. Invariants

Properties that must hold at all times. Each is enforced mechanically, and the
enforcement point is named — an invariant with no enforcement is a wish.

| # | Invariant | Enforced by |
|---|---|---|
| I1 | Every journal entry's debits equal its credits, per currency. | `clofin.ledger.entry/entry` **and** a deferred database trigger ✅ |
| I2 | Journal line amounts are strictly positive. | Domain constructor **and** `CHECK` constraint ✅ |
| I3 | A posted entry is never updated or deleted. | Append-only database triggers ✅ |
| I4 | An entry may be reversed at most once. | Partial unique index ✅ |
| I5 | Money arithmetic never crosses currencies implicitly. | `clofin.money` raises ✅ |
| I6 | An account holds exactly one currency. | Schema and balance computation ✅ |
| I7 | Every entry references the business object that caused it. | `NOT NULL` plus a constrained vocabulary ✅ |
| I8 | An instruction's approver is never its maker. | Domain rule in `clofin.authz` 📋 |
| I9 | A state change and its audit event commit together. | Same transaction 📋 |
| I10 | A replayed idempotency key never performs work twice. | Primary key `(organisation_id, key)` plus the stored response, written in the same transaction as the effect ✅ |
