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
| **Maker** | The actor who creates an instruction **and the only actor who may submit it** — enforced by `clofin.payments.state/creator-only-events` and `clofin.payments.repository/transition!`, not merely stated here. Without that enforcement, "creates and submits" is two actors described as one, and the Checker rule below stops being a control. | The beneficiary. |
| **Checker** | The actor who approves. Must not be the maker — refused by `clofin.authz.approval/evaluate`, which compares the approver against `created-by` and relies on the Maker row above for that to be the whole comparison. | A reviewer with no system authority. |
| **Release** | The act of handing an approved instruction to settlement. | Approval. Approval permits release; it is not release. |
| **Settlement** | Irrevocable transfer of value through a scheme. | Clearing, which is the exchange of instructions preceding it. |
| **Reversal** | A new entry mirroring an original, leaving both visible. | Deletion, amendment, or "cancellation" of a posted entry — none of which exist. |
| **Return** | A payment sent back by the receiving institution after settlement. | A Refund, which CloFin's own user initiates. |
| **Break** | A reconciliation item that did not match, with an age and an owner. | A discrepancy that someone will look at later. A break is a tracked object. |
| **Idempotency Key** | A caller-supplied identifier making a retry safe. | A payment reference. |
| **Audit Event** | An append-only record of a state change: who, what, when, before/after **as digests**. | An application log line. Also not a copy of the record — a digest proves a value, it does not carry one. |
| **Actor** | A person or system able to act within one organisation, holding roles and per-currency approval limits. | An Organisation, which is the tenant the actor acts within. |
| **Role** | A named bundle of permissions. Five exist and none of them is a superuser. | A job title. A role here is exactly its permission set. |
| **Permission** | A single verb an actor may exercise. **Absent means denied**, always. | A preference or a UI affordance. |
| **Approval Threshold** | A band of amounts, in one currency, and how many approvals it requires. Lower bound inclusive. | A limit. A threshold is the organisation's rule; a limit is one approver's ceiling. |

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

**Approval** ✅
| Field | Notes |
|---|---|
| `id` | ✅ An approval is addressable: it can be withdrawn and it appears in an evidence pack on its own. |
| `instruction-id`, `actor-id` | ✅ The actor is the authenticated principal, not a caller assertion. |
| `decision` | ✅ `approved` or `rejected`. No default — the safe default would have to be one of the two, and either is a decision nobody made. |
| `reason` | ✅ Mandatory on a rejection and retained (PR-013). Enforced twice: the domain produces the `422` naming the field, and `approval_rejection_needs_reason` makes a reasonless rejection unrepresentable. |
| `decided-at` | ✅ |
| `invalidated-at` | ✅ Set when the instruction was amended (PR-014) or the actor withdrew the approval. **The row is never deleted** — the database refuses it. An approval that was given and then invalidated is exactly the history an investigation needs; a deleted one is a decision nobody can prove was taken. |

### 2.3 Settlement context ✅

```
SettlementBatch 1───* SettlementBatchItem *───1 PaymentInstruction
        │
        └──* SchemeResponse
```

**SettlementBatch** ✅
| Field | Notes |
|---|---|
| `id`, `organisation-id` | ✅ |
| `scheme` | ✅ `SIM-RTGS` or `SIM-ACH`. **Simulated only** — the `SIM-` prefix is a database check constraint, so a synthetic record can never name a real network. An operator's routing choice: an instruction carries no scheme attribute. |
| `currency`, `value-date` | ✅ With `scheme`, the triple that defines a batch. One batch, one triple. |
| `status` | ✅ `open` → `submitted` → `settled` / `partially-settled` / `failed`. **Derived from the items' outcomes**, never set directly — the same doctrine as a balance deriving from the journal. |
| `created-by`, `created-at` | ✅ |

**SettlementBatchItem** ✅ — one instruction within a batch, keyed
`(batch, instruction)` and having no identity of its own.
| Field | Notes |
|---|---|
| `outcome` | ✅ `settled`, `returned`, `timed-out`, or null while pending. There is deliberately **no `failed`** — see §3. |
| `outcome-reason` | ✅ Required on a `returned` item: an exception queue whose entries do not say why is one nobody can work. |
| `resolved-at` | ✅ Set exactly once. |

**A settlement membership is permanent.** ✅ An instruction is in **at most one**
membership, ever — pending, settled, timed out and returned all block a second
one — and that is a unique index rather than a check in application code, so it
binds a fix-up script and a defect too. **`timed-out` blocks precisely because
the outcome is unknown:** treating unknown as failed and re-batching is how a
payment is made twice, which is the single failure this context exists to
prevent.

`returned` used to be the exception, and audit finding **F-007** removed it. The
schema freed a returned instruction for re-batching while the payment lifecycle
held `returned` terminal and batch eligibility was `approved`-only — so the
permission the index advertised was unreachable from any public command, and the
acceptance criterion promising it was false end to end (standing lesson
**L-10**: a schema path is not a product path). **Ruled: `returned` is terminal;
a retry is a NEW instruction** — the doctrine `settled` already follows in §3
rule 4, where a correction is a new reversing instruction rather than a mutation
of the old one. Migration `0010` tightened the index accordingly. Linked-retry
provenance — a reference relating the retry to the payment it replaces, and the
exception workflow around it — is increment 6's, where return-exception handling
natively lives.

**SchemeResponse** ✅ — what a simulated scheme said, stored **verbatim** as a
**receipt**, kept whether or not it caused work.

| Field | Notes |
|---|---|
| `kind`, `reference` | ✅ With batch and instruction, the **replay key** — `nulls not distinct`, so two identical batch-level acks collide rather than coexisting. It names a delivery's *identity*. |
| `request-digest` | ✅ Version-tagged canonical digest of the **complete semantic request** — batch, instruction, kind, reference, outcome and reason. It says whether two deliveries under one identity are the same *message*. Same canonicaliser and same posture as `idempotency_key` ([ADR-0013](ADR/0013-canonical-request-digest-for-idempotency.md)). |
| `disposition` | ✅ `applied`, `acknowledged` or `refused` — what CloFin did about the arrival, machine-readable. |
| `disposition-reason` | ✅ Required on a refusal, absent otherwise: `item-already-resolved`, `item-not-timed-out`, `item-not-in-batch`. |
| `outcome`, `reason` | ✅ What this response **claimed**. Distinct from `SettlementBatchItem.outcome`, which is what CloFin **recorded** — the two differ in exactly the case that matters, a message kept as evidence that did no work. |

Append-only against all three destructive verbs, and — since audit finding
**F-008** — genuinely kept whether or not it caused work. It was not before: a
response CloFin could not act on was rolled back *by the conflict that rejected
it*, so the first delivery was unprovable and the identical reference could
perform work later against changed state. Receipt and disposition are separate
facts (standing lesson **L-11**): the receipt commits with a machine-readable
statement of what was done, and the `409` is rendered afterwards.

Responses arriving **late, twice, or out of order** is the normal case in the
world this simulates, not an edge case; so is **partial batch failure**.

- An **exact duplicate** — same identity, same digest — does no work and
  reproduces the original answer, `outcome` included.
- The **same reference saying something different** is a conflict and is never
  called a replay. Audit finding **F-009** (standing lesson **L-12**, extending
  L-2) found two contradictory timeout resolutions sharing one identity, the
  second answered `200 replayed=true`; replay identity now covers every
  effect-bearing field.
- A genuinely **new response for an item that already has an outcome** is a
  conflict — and its receipt is kept.

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

### 2.6 Audit context ✅ (payments and approvals)

**AuditEvent** ✅ — `actor`, `action`, `subject-type`, `subject-id`,
`before-digest`, `after-digest`, `correlation-id`, `occurred-at`. Append-only,
written in the same transaction as the change it describes.

**Digests, not payloads.** An event proves *that* something changed, and *what*
it changed to when compared against a value the auditor already holds. It does
not carry the counterparty name: an append-only table holding one is a second
copy of the data C-09 minimises, and it can never be cleaned. Each digest is
prefixed with the canonicalisation version that produced it. See
[ADR-0016](ADR/0016-audit-events-store-digests-not-payloads.md), which also
states what this costs an auditor.

**Coverage.** Every state change the API can perform emits one event: payment
instructions, approvals, and — since TASK-005 — organisation creation, account
opening and journal posting. There is no qualification left on
[C-05](COMPLIANCE.md).

**Attribution, including where there is none.** `actor` is null on exactly one
action, `organisation.created`, because `POST /organisations` is the bootstrap
and no actor can exist before the organisation that holds one. That is enforced
rather than conventional: `clofin.audit/event` refuses a null actor for every
action outside `clofin.audit/bootstrap-actions`
([ADR-0017](ADR/0017-bootstrap-identity-for-organisation-creation.md)).

**Vocabulary.** The action is drawn from a closed set (`clofin.audit/actions`);
anything else is refused before it reaches the table, so a question like "show
me every approval in August" has a complete answer rather than a best-effort
one. Two naming rules hold, both of them corrections from Milestone 1's audit:

| | |
|---|---|
| `payment.created`, `payment.submitted`, `payment.approved`, `payment.rejected`, `payment.amended`, `payment.cancelled` | The **payment's** transitions. Each is emitted exactly once, in the transaction where that transition commits — finding **F-005** found `payment.approved` emitted per *decision*, so a two-approval payment appeared to have been approved twice. |
| `approval.recorded`, `approval.withdrawn`, `approval.invalidated` | The **approval's** own lifecycle, with the approval as subject. `approval.recorded` is written for every decision, approve or reject. `approval.invalidated` is written per approval when an amendment revokes it — finding **F-006**; before it, the trail said only that the payment had been amended. |
| `payment.released`, `payment.settled`, `payment.returned` | Settlement's payment transitions, each written where that transition commits. `payment.released` is one event **per instruction** in the batch's submission transaction — counting `settlement-batch.submitted` to learn how many payments left would be F-005's mistake with a new name. `payment.failed` is in the vocabulary and is emitted by nothing; see §3. |
| `settlement-batch.created`, `.submitted`, `.completed`, `.timeout-swept` | The **batch's** own lifecycle, with the batch as subject. `.completed` is written **only** in the transaction where the last unresolved item resolves — a batch with items outstanding is not completed however many responses have arrived. `.timeout-swept` is its own term because "we stopped waiting" is not "the scheme answered", and a sweep that swept nothing writes nothing. |
| `organisation.created`, `account.created`, `journal-entry.posted` | The three writes that emitted nothing until TASK-005. Each is a creation, so each is written once, in the transaction where the row it names first exists, with a null before-digest. None has a decision or a partial step to distinguish it from, so none needs a second term the way `approval.recorded` needed one beside `payment.approved`. `posted` rather than `created` for a journal entry: an entry is never drafted and never amended (C-03), so posting is the only transition it has. |

An approval's events name the approval, not the payment, because that is what
they are about. `clofin.audit.repository/events-for-payment` relates them back
through `approval.instruction_id`, so a payment's evidence pack still shows
them without the subject column having to misdescribe them.

### 2.7 Authorisation context ✅

**Actor** ✅ — `id`, `organisation-id`, `display-name`, `status`
(`active`/`suspended`), with roles and per-currency approval limits. Seeded, not
self-registered: an actor that could grant itself the approver role would make
segregation of duties unenforceable however carefully the rule is written.

**Role** ✅ — one of `operator`, `approver`, `controller`, `compliance`,
`auditor`. What each *means* is `clofin.authz.model/role-permissions`, in code
rather than in rows: a permission set stored as data is editable by anyone able
to write those rows. **There is no superuser**, and a test asserts that no role
holds every permission.

**ApproverLimit** ✅ — the largest amount an actor may approve, per currency.
Absent means zero, not unlimited.

**ApprovalThreshold** ✅ — amount bands mapping to a required approval count,
per organisation and per currency. `from-minor` is inclusive, so an amount
exactly on a boundary falls in the higher band. A currency with no bands cannot
have payments approved at all. See
[ADR-0015](ADR/0015-approval-thresholds-are-per-currency.md), which resolves
PRD Q1.

---

## 3. Payment instruction lifecycle 🔨

```
       ┌────────── amend ──────────┬────────── amend ──────────┐
       ▼                           │                           │
   ┌───────┐    submit    ┌────────┴─────────┐   approve  ┌────┴─────┐
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

`amend` leaves **two** states — `pending_approval` and `approved` — and both
land back in `draft`, invalidating every approval given so far. The diagram is
checked against `clofin.payments.state/transitions`, which carries eleven
permitted pairs; a drawing that disagreed with the table would be the failure
[ADR-0014](ADR/0014-payment-lifecycle-as-data.md) exists to prevent, and it is
the failure this diagram *was* until ruling O-2 (lesson L-4).

Rules that the diagram alone does not carry:

1. 📋 `submit` requires screening to have completed. A pending screening blocks
   submission rather than queuing behind it. *(Increment 7. There is a
   `TODO(increment-7)` at the precondition it will gate.)*
2. ✅ `approve` requires an actor other than the maker, within their limit, and
   enough approvals to satisfy the threshold for the amount. Decided by
   `clofin.authz.approval/evaluate`, a pure function: the rule holds with no
   HTTP layer involved, and a past decision replays against the values it was
   decided on.
3. ✅ `amend` on a `pending_approval` **or `approved`** instruction returns it
   to `draft` and **invalidates every approval given so far** (PR-014). This
   *is* what `PATCH /payment-instructions/{id}` does when the instruction is not
   a draft — see [ADR-0014 amendment 1](ADR/0014-payment-lifecycle-as-data.md),
   which lifted the restriction now that the invalidation behind it exists.
   Approvals are invalidated, never deleted, so the same approver may approve
   the amended instruction again.
4. ✅ `settled` is terminal. A settled payment is never mutated; it is followed
   by a *new* reversal instruction.
5. ✅ `returned` posts the exact mirror of the release entry and appears as an
   exception case with its reason (increment 5). It is **terminal, exactly as
   `settled` is** — rule 4's doctrine, applied to the other terminal outcome:
   the instruction is finished, its settlement membership is permanent, and a
   retry is a *new* instruction approved on its own merits. Audit finding
   **F-007** settled this; the schema had advertised a re-batching permission
   the lifecycle forbade. A structured exception workflow — relating a retry
   back to the payment it replaces — is increment 6's. 📋

Transitions are held as data in `clofin.payments.state`, so the table above can
be generated from the code and tested exhaustively rather than sampled — every
(state, event) pair is walked, not a chosen few.

**Two rules about status are not transitions**, and are held as named sets
beside the table rather than as conditionals in a handler
([ADR-0014](ADR/0014-payment-lifecycle-as-data.md)):

- ✅ `mutable-states` — an instruction is *mutable while `draft`, immutable in
  substance thereafter* (§1). Amending a draft leaves it in `draft`, so it moves
  along no arrow. `PATCH /payment-instructions/{id}` chooses between this and
  the `amend` *event* by reading the two values rather than by testing a status:
  a draft is edited in place; anything the lifecycle permits `amend` from goes
  back to `draft` with its approvals invalidated.
- ✅ `reversible-states` — a reversal may be raised only against a `settled`
  instruction, per rule 4. The original is untouched, so this is not a
  transition either.

**Built so far:** `submit`, `cancel`, `approve`, `reject` and `amend` have
endpoints. `release`, `settle` and `return` are driven by settlement
(increment 5): a batch's submission releases every member, and a simulated
scheme response settles or returns one. TASK-004 drove those arrows without
changing any of them — the table already said where they led.

**`fail` is still driven by nothing**, and that is now a deliberate gap rather
than an unbuilt one. A settlement item's outcome is `settled`, `returned` or
`timed-out`: a scheme failure that sends the money back **is** a return, and an
outcome nobody knows is a timeout, which leaves the instruction `released`
because CloFin cannot claim the payment did not happen. Nothing in the
settlement model produces "the payment definitively failed and no money came
back". The arrow stays in the table — a transition with no caller is still part
of the model — and whether it should acquire one is recorded as objection **O-1**
in `004-REQ`.

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
| Settlement | `2100-CLIENT-PAYABLE` | `1300-IN-TRANSIT` | 1,250.00 |
| Return *(instead of settlement)* | `1100-CLIENT-FUNDS` | `1300-IN-TRANSIT` | 1,250.00 |

**Release moves value between two assets and leaves the liability alone** —
until the scheme settles, CloFin still owes the client the money it is holding.
**Settlement extinguishes both sides:** the in-transit asset is credited away and
the obligation to the client is debited down. **A return touches no liability**
and is the exact mirror of the release: the money is back in the pool and the
client's claim on it never moved.

The balance of `1300-IN-TRANSIT` is therefore, at any instant, the value CloFin
has released and does not yet know the fate of — its clearing exposure, readable
from the ledger alone. A **timed-out** payment posts nothing and stays in that
balance, which is the visibility a stuck payment deserves.

This table previously showed settlement as debit `1100-CLIENT-FUNDS`, under an
asterisk deferring the pair to "the increment that has a scheme adapter". That
pair is the *return*. Corrected by
[ADR-0018](ADR/0018-release-posts-to-settlement-in-transit.md), which records
what was chosen and what was rejected.

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
| I8 | An instruction's approver is never its maker. | `clofin.authz.approval/evaluate`, a pure function — refused with no HTTP layer involved, and table-driven over the whole actor × instruction matrix ✅ |
| I9 | A state change and its audit event commit together. | Same transaction. `clofin.audit.repository/record!` takes the caller's connection and cannot open one, so an audit write outside the change it describes is not expressible ✅ |
| I10 | A replayed idempotency key never performs work twice. | Primary key `(organisation_id, key)` plus the stored response, written in the same transaction as the effect ✅ |
| I11 | A committed journal entry has at least two lines. | Deferred constraint trigger `journal_entry_must_be_complete` on `journal_entry` (migration `0008`). I1's trigger fires on `journal_line`, so an entry with no lines never fired it — audit finding **F-003** ✅ |
| I12 | An account's status is read under a lock by the transaction that writes against it. | `select … for update` in `clofin.ledger.repository/assert-postable!` and `clofin.payments.repository/assert-debtor-account!`. Reading a status and then writing on it is a race under `READ COMMITTED` (standing lesson **L-8**) — audit finding **F-004** ✅ |
