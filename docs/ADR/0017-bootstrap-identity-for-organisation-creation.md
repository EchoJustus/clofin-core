# ADR-0017: The bootstrap write records no actor, and the null is enforced

- **Status:** Accepted
- **Date:** 2026-08-04
- **Deciders:** Worker session (TASK-005), Master Control
- **Supersedes / Superseded by:** —

## Context

C-05 claims that every state change records **who** did it. TASK-005 closes the
last gap in *what* is recorded — organisation creation, account opening and
journal posting emitted no audit event at all — and in doing so hits the one
write that has no *who* to record.

`POST /organisations` is the bootstrap. It is deliberately unauthenticated, and
that is not an oversight to be tidied up: an actor belongs to exactly one
organisation (`actor.organisation_id` is `NOT NULL` and references
`organisation`), so no actor can exist before the first organisation does.
Making the endpoint authenticated would require an actor who belongs to no
organisation — a principal outside the tenancy model, holding a right no role
grants, which is the superuser [ADR-0015's](0015-approval-thresholds-are-per-currency.md)
sibling decision and `clofin.authz.model` exist to avoid. TASK-003 recorded this
in 003-REQ §6 and the brief for TASK-005 rules it out explicitly.

So the bootstrap's audit event has no principal to carry, and something has to
be written in `audit_event.actor_id`. The column is already nullable, and
migration `0005` already says what a null there means:

> Null only where there is genuinely no authenticated actor. Today that is the
> bootstrap case alone; a null here on a payment action would be a defect.

That sentence is the whole problem. It is a **column comment**, and standing
lesson **L-6** was learned from exactly this shape: C-01 rested on an identity
invariant that was documented and enforced nowhere, and an operator could
therefore submit another's draft and approve it. An auditor reading
`actor_id is null` is being asked to trust a comment about what nulls mean, and
nothing stops the next handler writing one on a payment action and making the
comment false. An unattributed state change is precisely the half of C-05 that
says *who*.

## Decision

The bootstrap write records **no actor** — `actor_id` is null — and that null is
given a single, checked meaning:

1. `clofin.audit/bootstrap-actions` names the actions permitted to carry a null
   actor. It contains one term, `organisation.created`.
2. `clofin.audit/event` **refuses** a null actor for every action outside that
   set. Since `clofin.audit.repository/record!` builds every event through
   `event`, there is no path to the table that goes around it.

The rule is one-directional on purpose. A bootstrap action *may* be actorless;
it is not required to be. An administered organisation-creation path arriving
later records its principal without this set having to change, and if that path
ever makes the exemption unnecessary, deleting the term is the whole change.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Seed a `system` actor row and attribute the bootstrap to it | An `actor` row is a thing that can be granted roles and approval limits, and it authenticates by `X-Actor-Id` like any other. One that exists solely to sign the audit trail is an unadministered identity appearing in the column an auditor reads as attribution — worse than a null, because it *looks* like a person. It would also be the first actor belonging to an organisation it did not act for, or a second tenancy hole to reason about. The brief rules it out by name |
| Authenticate `POST /organisations` | Recreates the superuser problem: the authenticating actor would have to exist before any organisation, so it would belong to none, and every tenancy check downstream (`assert-organisation!`, the approval rules) is written against an actor that has one. Ruled out by brief 003 and restated by brief 005 |
| Leave the null unenforced, as a column comment | This is the status quo the decision replaces, and it is standing lesson **L-6** verbatim: a load-bearing sentence in a comment is not an enforcement point. It also makes C-05's own claim unfalsifiable — "every state change is attributable, except where it is not, and you cannot tell which from the table" |
| Record the correlation id in `actor_id` when there is no actor | Puts a value of one kind in a column of another, and defeats the foreign key to `actor`. The correlation id already has its own column, and the bootstrap event carries it |
| A `CHECK` constraint in the database instead of the application rule | Stronger against a defect that bypasses `clofin.audit`, but there is no such path — `record!` is the only writer and it always calls `event`. Deferred rather than rejected: it needs a migration, which brief 005 asks not to add without necessity, and it is recorded as a candidate in 005-REQ. The application rule is checked by a unit test *and* an integration test through `record!` |

## Consequences

**Positive**

- `actor_id is null` has exactly one meaning, and an auditor can rely on it
  without reading a migration.
- C-05 can be claimed without a scope qualification: every write leaves an event,
  and every event is attributable or is the one documented case that cannot be.
- A future handler that forgets to thread its principal through fails loudly at
  the audit write instead of silently producing an unattributed event.

**Negative / accepted cost**

- `clofin.audit/event` now refuses inputs it previously accepted. Two existing
  tests passed `:actor-id nil` on payment actions as fixture convenience and were
  updated to name a real actor — the rows they were writing were the shape
  migration `0005` already called a defect.
- The exemption is a list that has to be maintained. It is one term long, it is
  asserted to be exactly that in `clofin.audit-test`, and widening it is
  therefore a visible control decision rather than an edit.

**Risks and how they are mitigated**

- *Risk:* the exemption widens quietly as new bootstrap-like writes appear.
  *Mitigation:* `every-bootstrap-action-is-itself-a-known-action` asserts the set
  equals `#{"organisation.created"}`, so adding a term fails a test and has to be
  argued for.
- *Risk:* the rule is enforced in `event` but a future writer reaches the table
  directly. *Mitigation:* `record!` is the only writer, it cannot open a
  connection, and `clofin.authz.repository-test/a-payment-event-with-no-actor-is-refused`
  asserts the refusal through the storage path rather than only through the pure
  function.

## Verification

- `clofin.audit-test/the-bootstrap-action-may-have-no-actor` — the null survives
  the round trip rather than becoming a placeholder.
- `clofin.audit-test/an-actorless-event-outside-the-bootstrap-is-refused` — every
  action in the vocabulary except the bootstrap is refused a null actor. Table
  driven over `clofin.audit/actions`, so an action added later is covered without
  the test being extended.
- `clofin.audit-test/every-bootstrap-action-is-itself-a-known-action` — the
  exemption set is exactly one known term.
- `clofin.authz.repository-test/a-payment-event-with-no-actor-is-refused` — the
  refusal holds through `record!` and against a real database, and nothing
  reaches the table on the way.
- `clofin.api.audit-coverage-test/ac-1-the-bootstrap-event-carries-no-actor-and-invents-none`
  — end to end: no actor row is seeded, and
  `select count(*) from audit_event where actor_id is null and action <> 'organisation.created'`
  is zero.
