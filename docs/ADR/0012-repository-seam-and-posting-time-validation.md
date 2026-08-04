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
inside the transaction, and takes a lock on every row it validates.** The
referenced accounts are loaded once, scoped to the entry's organisation, and
checked for existence, postability and currency agreement before any row is
written.

Being inside the transaction is necessary and **not sufficient**. This ADR
originally said an account frozen concurrently "is either visible to this
transaction or it is not, and either way the outcome is consistent." That is
true of a *snapshot*, and CloFin runs at `READ COMMITTED`, where every statement
takes a fresh one: a freeze that commits between the status read and the line
insert is invisible to the read and fully in effect by the insert, and the
posting lands on a frozen account anyway. Milestone 1's external audit found
this as **F-004**. The correction is a lock, not a stricter isolation level:

> `select … from ledger_account where id in (…) order by id for update`

The freeze and the posting now serialise on the account row. Whichever commits
first wins and the loser sees the outcome, rather than both proceeding on
readings taken before the other existed. `order by id` is the lock order,
applied at every site that takes these locks, so two concurrent postings over an
overlapping set of accounts cannot deadlock by approaching them from opposite
ends. Where a transaction locks more than one *kind* of row, the order between
kinds is fixed too and stated at the top of the repository namespace:
`payment_instruction` before `ledger_account`.

The general form is standing lesson **L-8**: validate-then-write is a race
unless the validated rows are held. A `select` that decides whether a write may
happen is part of that write.

A lock is only worth what its transaction is worth, so
`clofin.db.core/transactionally` now **checks** the connection it is given
rather than inferring a transaction from its type. The pool runs `autoCommit
true`; a caller handing over a raw pooled connection would get each statement
committed separately, releasing the lock before the insert it was taken for,
with every write still succeeding and only atomicity gone. One `getAutoCommit`
call turns that from a convention into a refusal.

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
| **Validate before opening the transaction** | One fewer statement inside the transaction. Rejected: the account status read and the insert must be in the same transaction, or an account frozen between the two is posted to anyway. The window is small and would be hit rarely, which is exactly what makes it a defect nobody reproduces. Worth noting that the same transaction was not enough on its own either — see decision 2 and finding F-004. |
| **`FOR SHARE` rather than `FOR UPDATE`** | Gives the identical freeze-versus-post guarantee — a share lock still conflicts with the exclusive lock an `UPDATE` to `status` takes — without serialising concurrent postings against each other. That matters because a pooled client-money account is on every payment, so `FOR UPDATE` makes every posting in an organisation queue on one row. `FOR UPDATE` shipped because it is what was ruled, and the serialisation is recorded as an accepted cost below. If it is revisited: use one strength at both sites, never a mix, and note that a future freeze operation must not `select … for share` and then `update` the same row — that self-upgrade is the classic `FOR SHARE` deadlock. |
| **Raise the isolation level to `REPEATABLE READ` or `SERIALIZABLE` instead of locking** | Would close F-004, and closes the whole class rather than one instance of it. Rejected for now because it converts the failure into a serialisation error the caller must retry, and CloFin has no retry policy: every write path would need one, and a payment endpoint that silently retries is a different conversation from one that locks. Explicit `for update` at the two sites that need it is smaller, is visible where it matters, and does not change the contract of every other endpoint. Revisit if the number of sites grows. |
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
- Posting takes a row lock on every account it touches, so postings over a shared account — a settlement or fee account, typically — serialise against each other rather than running concurrently. Accepted: the alternative is a posting whose validation was true when it was read and false when it committed.

**Risks and how they are mitigated**
- *Risk:* a future context adds persistence to a pure namespace under a name other than `repository`. *Mitigation:* the purity test enumerates the namespaces it protects, and adding a domain namespace without adding it to that list is visible in review as a missing line in a test that exists to be extended.
- *Risk:* a new site takes these locks in a different order and deadlocks against an existing one. *Mitigation:* the order is `payment_instruction` then `ledger_account`, and within a kind by `id` ascending; it is stated in the namespace docstring rather than in a commit message, and a test posts two entries over the same pair of accounts from opposite directions and asserts both complete.
- *Risk:* a caller passes a pooled connection rather than a transaction, and the locks are released per statement. *Mitigation:* `transactionally` refuses a connection in autocommit, with a test. There used to be a second, private copy of `transactionally` in `clofin.ledger.repository` — guarding one and not the other left the only path that takes these locks unguarded — and it is deleted rather than also-fixed.
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
- `test/clofin/ledger/repository_test.clj` also runs decision 2's lock as a
  two-session race: one thread freezes an account and **holds its transaction
  open**, a second attempts to post, and the assertion is that the posting is
  refused and nothing is written. Holding the transaction open is what makes the
  test worth having — the same test without it passed against the unfixed code
  three times in a row, because the unlocked window is microseconds wide. A race
  test that cannot be made to fail is not evidence of a fix.
