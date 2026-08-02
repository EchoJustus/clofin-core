# ADR-0012: Repository namespaces as the persistence seam, and where posting-time validation lives

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead
- **Supersedes / Superseded by:** —

## Context

`ARCHITECTURE.md` §4 states the layering rule that everything else in CloFin
depends on: **the domain layer is pure.** `clofin.money` and `clofin.ledger.*`
take values and return values; they never open a connection, read a clock, or
generate an identifier.

Connecting the ledger to PostgreSQL puts pressure on that rule from two
directions at once.

**First, naming.** The persistence code for accounts and entries is *about* the
ledger, and the obvious home for it is `clofin.ledger.something`. But the
existing `clofin.ledger.account` and `clofin.ledger.entry` are pure, and a
reader who sees a `clofin.db` require anywhere under `clofin.ledger` reasonably
concludes the rule has been abandoned.

**Second, and more substantially: some rules cannot be checked purely.** The
zero-sum invariant is a property of the entry value alone, so
`clofin.ledger.entry/entry` checks it. But three rules that must hold before an
entry is posted are properties of the *database*, not of the value:

- the accounts a line references must exist **within the entry's organisation**;
- each must be `active` — a `frozen` or `closed` account accepts no postings;
- each line's currency must match its account's currency, or the resulting
  balance is not computable (`clofin.ledger.account/balance` rejects a posting
  whose currency differs from the account's).

None of these can move into the pure layer, because the pure layer cannot see an
account it was not handed. Nor can they be left to the schema: the database has
no constraint tying a line's currency to its account's, and a foreign-key
violation surfaces as an opaque error rather than as a statement about which
account was wrong.

A third question falls out of the same work. `clofin.ledger.entry/entry` signals
an unbalanced entry with `err/invalid!` — a `:validation` error, rendered as
**400**. The API contract for `POST /journal-entries` requires **422** with the
per-currency shortfall. Left unreconciled, a future contributor finds two
plausible truths in the repository and changes one of them.

## Decision

**1. `clofin.<context>.repository` is the persistence seam.** A namespace named
`repository` may require `clofin.db.*`; every other domain namespace may not.
The rule is therefore mechanical and reviewable by name rather than by
judgement, and it is asserted by a test that reads the `ns` forms of the pure
namespaces and fails if a `clofin.db.*` or `clofin.http.*` require appears.

`clofin.ledger.repository` and `clofin.organisations.repository` are persistence.
`clofin.ledger.account`, `clofin.ledger.entry`, `clofin.money` and
`clofin.organisations.organisation` are pure and stay that way.

**2. Posting-time validation that needs database state lives in `post-entry!`,
inside the transaction.** The referenced accounts are loaded once, scoped to the
entry's organisation, and checked for existence, postability and currency
agreement before any row is written. Doing it inside the transaction is what
makes the check meaningful: an account frozen concurrently is either visible to
this transaction or it is not, and either way the outcome is consistent.

The pure constructor is still called first, and the deferred database trigger
still fires at commit. Three layers check the zero-sum invariant and that is
deliberate — see ADR-0008. **None of them is weakened to avoid duplication.**

**3. A well-formed request that violates a ledger rule is `422`, not `400`.**
`400` means CloFin could not understand the request — a malformed UUID, a
missing field, an amount that is not an integer. `422` means it was understood
completely and cannot be carried out: the entry does not balance, an account is
frozen, an account belongs to someone else. Payment clients branch on this
distinction, because the first class is a bug in the caller and the second is a
business outcome the caller must surface to a human.

Concretely: the HTTP layer computes `entry/imbalance` on the parsed lines and
raises `:unprocessable` carrying the per-currency shortfall **before** calling
`entry/entry`. The pure constructor's own `:validation` error is then
unreachable from the API and remains what it should be — the defence against a
defect in a non-HTTP caller.

**4. An account in another organisation is reported as unknown, not as
forbidden.** The account lookup is scoped by organisation, so a line referencing
another tenant's account is indistinguishable from a line referencing an account
that does not exist, and both are `422` naming the offending id. Answering
"forbidden" would confirm that the id exists and belongs to someone else, which
is a tenancy disclosure obtained for free by anyone able to guess a UUID.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Put persistence in `clofin.db.ledger`** | Keeps `clofin.ledger.*` unambiguously pure, which is the honest reading of the layering rule. Rejected because it groups code by mechanism rather than by domain: `clofin.db` would accumulate every context's SQL and become the one namespace every feature touches. The `repository` suffix gets the same guarantee from a naming convention that a test can enforce. |
| **Pass loaded accounts into a pure `entry/postable?` and keep all rules in the domain** | Attractive, and partly what happens — the currency and status *rules* are expressed in the domain vocabulary. Rejected as the whole answer because the caller would still have to know which accounts to load and to scope the query by organisation, so the security-relevant part of the check would live at the call site rather than in one place. |
| **Rely on the schema alone: foreign keys and a status check constraint** | Defence in depth is already the position (ADR-0006), so this is not either/or. Rejected as the primary mechanism because the database cannot express "the line's currency equals its account's currency" without a trigger or a denormalised column, and because a constraint violation names a constraint rather than the account a caller must go and unfreeze. |
| **Validate before opening the transaction** | One fewer statement inside the transaction. Rejected: the account status read and the insert must be in the same transaction, or an account frozen between the two is posted to anyway. The window is small and would be hit rarely, which is exactly what makes it a defect nobody reproduces. |
| **Return `400` for an unbalanced entry, matching the domain constructor** | Internally consistent and cheaper. Rejected because it merges two failures a payment client must handle differently, and because `422` with a per-currency shortfall is what makes the response actionable rather than merely correct. |
| **Return `403` for a cross-organisation account reference** | More precise in the abstract. Rejected: precision here is the disclosure. See decision 4. |

## Consequences

**Positive**
- The purity rule survives contact with the database, and is checked by a test rather than by reviewer memory.
- Everything that must be true before money moves is enforced in one function, inside one transaction, in the order a reviewer reads it.
- Callers can distinguish "I sent nonsense" from "the ledger will not do that", which is the distinction their own error handling is built on.
- Cross-tenant probing returns the same answer as a typo.

**Negative / accepted cost**
- `post-entry!` performs a read before its writes, so posting an entry is two round trips rather than one.
- The zero-sum invariant is expressed three times — pure constructor, HTTP layer, database trigger. This is intentional duplication and must not be "cleaned up"; the comment in each place says so.
- A caller who posts to a frozen account learns which account only for the accounts in their own organisation. For another tenant's account the message is deliberately less helpful.

**Risks and how they are mitigated**
- *Risk:* a future context adds persistence to a pure namespace under a name other than `repository`. *Mitigation:* the purity test enumerates the namespaces it protects, and adding a domain namespace without adding it to that list is visible in review as a missing line in a test that exists to be extended.
- *Risk:* the HTTP layer's `422` imbalance check drifts from `entry/imbalance`. *Mitigation:* it calls `entry/imbalance` — the same function the constructor uses — rather than reimplementing the sum.

## Verification

- `test/clofin/ledger/purity_test.clj` reads the `ns` form of each pure domain
  namespace and fails on a `clofin.db.*` or `clofin.http.*` dependency.
- `test/clofin/ledger/repository_test.clj` asserts that posting to a frozen
  account, a closed account, an account in another organisation, and an account
  whose currency differs from the line's each fail, and that nothing is
  persisted when they do.
- `test/clofin/api/ledger_api_test.clj` asserts the status codes this ADR
  distinguishes: `400` for a malformed request, `422` for an unbalanced entry
  with the shortfall named per currency.
