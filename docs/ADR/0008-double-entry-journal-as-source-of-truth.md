# ADR-0008: Double-entry journal as source of truth

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead / product owner

## Context

The core accounting question for any payments platform is where the truth about
money lives. The naive answer — a `balance` column on an account, updated as
payments move — is the source of the most common and most damaging class of
defect in financial systems: a balance that no longer explains itself. When a
customer disputes a figure, "the column says 4,215.00" is not an answer. The
answer has to be a list of movements that add up to 4,215.00.

Regulators, auditors and finance teams all ask the same question in different
words: *show me every movement, in order, with who authorised it and why.*

## Decision

The **journal is the source of truth.** Balances are derived by aggregating
journal lines, never stored as an authoritative mutable value.

The model:

- A **journal entry** represents one economic event. It carries an occurrence
  time, a narrative, a reference to the originating business object (a payment
  instruction, a settlement item, a reconciliation adjustment), and two or more
  lines.
- A **journal line** references one account, a direction (`:debit` or `:credit`),
  and a **positive** amount. Direction is explicit rather than implied by sign,
  because that is how the domain is read, spoken and reviewed by finance.
- **Invariant:** within an entry, total debits equal total credits, per currency.
  Checked when the entry value is constructed, and again by a deferred database
  constraint at commit ([ADR-0006](0006-postgresql-as-system-of-record.md)).
- **Invariant:** an entry has **at least two lines**. "Two or more lines" above
  was prose until migration `0008`; the balance constraint fires on
  `journal_line`, so an entry with no lines at all satisfied it vacuously —
  zero debits equal zero credits, and nothing ever ran. A second deferred
  constraint, on `journal_entry` rather than on its lines, checks cardinality
  and balance together at commit. See the migration's header for the
  reproduction; the finding was **F-003** in Milestone 1's external audit.
- **Accounts** carry a type — `asset`, `liability`, `equity`, `revenue`,
  `expense` — which determines the normal balance side and therefore how a
  balance is computed from debits and credits.
- **Entries are immutable.** A posted entry is never updated or deleted. A
  mistake is corrected by a **reversing entry** that mirrors the original and
  references it. The error and the correction both remain visible, which is the
  point.
- A **period close** may snapshot balances for performance, but a snapshot is a
  cache: it is always reproducible from the journal, and it is never the
  authority.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Mutable balance column per account** | Fast and simple, and wrong. It cannot answer "why is the balance this number?", it corrupts irrecoverably under a partial failure, and it makes concurrent updates a lock-contention problem on the hottest row in the system. |
| **Single-entry transaction log** (one row: from, to, amount) | Adequate for simple wallet transfers, but cannot express fees, FX legs, tax, or any movement touching more than two accounts — all of which are ordinary in enterprise payments. It also has no natural place for suspense and clearing accounts, which is where reconciliation actually happens. |
| **Signed amounts instead of explicit direction** (negative = credit) | Compact, and defensible. Rejected because sign conventions differ across accounts of different types, so reviewers must hold the mapping in their heads, and an off-by-sign bug reads as valid data. Explicit `:debit`/`:credit` with positive amounts matches how finance professionals read a ledger and makes review possible for non-engineers — a stated driver. |
| **Full event sourcing with projections** | The journal *is* an event log for money; adding a second event layer above it duplicates the mechanism. See [ADR-0006](0006-postgresql-as-system-of-record.md) for why eventual consistency in balance reads was rejected. |
| **Allowing entry amendment before posting** | Considered for drafts. Rejected: a draft that can be amended is a payment instruction, not a journal entry. The distinction is kept sharp — instructions are mutable while in draft, journal entries never are. |

## Consequences

**Positive**
- Every balance is explainable by construction; there is no reconciliation between "the balance" and "the movements" because there is only one of them.
- A partial failure cannot corrupt a balance — an entry either commits whole or does not exist.
- Fees, FX legs, tax and multi-party splits are expressible without special cases.
- Corrections leave an audit trail that shows both the error and the fix.
- The zero-sum invariant is a single property that catches a large class of bugs, and is testable generatively.

**Negative / accepted cost**
- Balance reads are aggregations, so they cost more than reading a column. Mitigated by indexing on `(account_id, occurred_at)` and, if measurement justifies it, by period snapshots — explicitly as a cache.
- Callers must construct balanced entries, which is more work than "move X from A to B". Mitigated by posting templates per payment type.
- Storage grows with movement count rather than with account count.

**Risks and how they are mitigated**
- *Risk:* someone adds a `balance` column "for performance" and it silently becomes authoritative. *Mitigation:* no such column exists in the schema; any snapshot table is named `*_snapshot` and is documented as derived.
- *Risk:* an unbalanced entry reaches the database through a code path that skips the domain constructor. *Mitigation:* the deferred database constraint, tested directly in integration tests.
- *Risk:* a database guard is written against the wrong row and is therefore satisfied vacuously. *Mitigation:* stated, because it happened — see the second invariant above. The general form is that a `for each row` trigger cannot see a case with no rows, so any cardinality rule has to be enforced on the parent. The integration tests now assert the zero-line and one-line cases explicitly rather than only the unbalanced one.

## Verification

`test/clofin/ledger/entry_test.clj` contains a property test asserting that every
generated entry accepted by the constructor sums to zero per currency, and that
every unbalanced entry is rejected. Integration tests in
`test/clofin/db/ledger_constraints_test.clj` assert that the database rejects an
unbalanced entry, an entry with no lines and an entry with one line, each
inserted directly and bypassing the domain layer — and that an entry which is
merely *transiently* incomplete part-way through a transaction still commits.
