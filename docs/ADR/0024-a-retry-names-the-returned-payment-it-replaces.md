# ADR-0024: A retry names the returned payment it replaces

- **Status:** Accepted
- **Date:** 2026-08-15
- **Deciders:** Master Control (TASK-010, ruling on `008-REQ` objection O-1),
  Worker session
- **Supersedes / Superseded by:** — (discharges the deferral recorded in
  [ADR-0019](0019-a-returned-payment-is-terminal-and-retries-as-a-new-instruction.md)
  *What this costs, stated rather than glossed*)

## Context

[ADR-0019](0019-a-returned-payment-is-terminal-and-retries-as-a-new-instruction.md)
ruled that a returned payment is terminal and that a second attempt is a **new**
payment instruction, raised, submitted and approved entirely on its own merits.
It named the cost of that ruling in the same breath rather than glossing it:

> An operator whose payment came back must raise a new instruction, have it
> approved, and batch that. **Nothing in the record relates the retry to the
> payment it replaces**: an investigator matching them today does so by
> counterparty and amount, not by a reference.
>
> That is a real gap and it is **deferred, not denied**. Linked-retry
> provenance — a `retries_id`-style reference and the exception workflow around
> it — is ruled to be increment 6's (reconciliation), where return-exception
> handling natively lives.

Increment 6 delivered the half of it that made the gap *dangerous inside
reconciliation*: a statement line's end-to-end reference is the instruction id,
so a line about a returned original and a line about its retry can never be
confused for one another. It did not deliver the reference, and said so —
objection **O-1** in `008-REQ`, ruled by Master Control to be TASK-010's scoped,
named work. This ADR is the data-plane record of that work.

Three questions had to be answered that ADR-0019 did not answer, and each is
answered below with its reading stated, because the next reader will otherwise
re-derive them differently.

## Decision

**A retry carries `retries_id`, naming the `returned` instruction it replaces.
The reference is set at creation, immutable thereafter, refused unless its
target is a returned instruction of the same organisation, visible from both
ends, carried on the retry's own audit event, and named by any reconciliation
break on the original.**

Nothing else changes. There is no lifecycle arrow out of `returned`, no second
settlement membership, no re-approval semantics and no automatic re-batching —
all four were rejected by ADR-0019 and none of them is reopened here. The link
**relates two records and confers nothing**.

### 1. A retry of a retry is permitted, and a chain is not refused

The brief asked this explicitly: refuse a target that is itself a retry *only if
ADR-0019's text says so*. **It does not.** ADR-0019 speaks of "a `retries_id`-
style reference" and of the retry being a new instruction; it says nothing about
cardinality, chains or depth.

The reading is therefore that a chain is ordinary. It is also the only reading
that survives the obvious case: a retry that is itself returned is, by every
rule in `clofin.payments.state`, a returned instruction, and the operator's next
attempt is a retry of *it*. Refusing that would leave the operator raising an
unlinked instruction and losing exactly the provenance this ADR exists to
create — the failure mode would be worse than the gap.

A retry names the payment it *directly* replaces, not the head of a chain.
Walking a chain is a read, and a read is cheap; storing the head would be a
second copy of the relation that could disagree with the first.

### 2. The link carries no uniqueness rule

`retries_id` has no unique index, partial or otherwise, and a returned
instruction may be named by more than one retry over its life.

The alternative — at most one retry per original — reads well and fails in a
recoverable case: an operator raises a retry, cancels it (a public command from
`draft`), and can then never link a second one. Every predicate that would fix
that is worse than the disease. `where status <> 'cancelled'` encodes a second
copy of the payment lifecycle in an index predicate; adding `'rejected'` to it
encodes a third; and a partial index whose predicate must be revisited every
time the lifecycle gains a terminal status is the shape audit finding **F-007**
removed from this very table — a schema rule advertising something the
application's rules had moved past.

So the relation is many-to-one, both derived projections are **lists**, and the
ordinary case has one member. Where the API renders the reverse side —
`retriedByIds` on an instruction, `retriedByInstructionIds` on a break — it is an
array, present only when it has something in it.

### 3. The link carries no value rule

Nothing compares the retry's amount, currency, beneficiary or value date against
the original's.

This is the opposite of `assert-reversal-target!`, which requires a reversal to
match its target's currency, and the asymmetry is deliberate. ADR-0019's own
second reason says why:

> A return is **new information** — a closed account, a rejected beneficiary —
> and it is exactly the information a checker should see before the money is
> sent again.

Correcting a beneficiary account or an amount is therefore the *ordinary* reason
to retry, and a link that only accepted an identical payment would refuse
precisely the retries that matter. The maker–checker control is what judges the
retry's values, and it applies to the retry in full because the retry is a new
instruction.

One combination *is* refused: an instruction may not be both a reversal and a
retry. They are opposite statements about opposite terminal outcomes — one says
*that payment happened and is being undone*, the other *that payment did not
happen and is being attempted again* — so a record claiming both means nothing.
Refusing it also removes a lock-ordering question, because at most one
`payment_instruction` link target is then read per creation; that is a
consequence rather than the reason.

### 4. Immutability is enforced, not described

`payment_instruction.retries_id` cannot be changed by any writer.
`amendable-fields` excludes it, so a `PATCH` naming it is a `422`; and migration
`0013` adds `payment_instruction_retry_link_immutable`, a trigger that refuses
an `UPDATE` changing the column at all.

The trigger exists because "no code path does it" is a property of today's
callers, and provenance an operator can rewrite after the fact is provenance an
investigation cannot rely on. That is standing lesson **L-6**: a premise a
control rests on is traced to its own enforcement point. The guard is narrow —
`payment_instruction` is *not* an append-only table, its status moves along the
lifecycle and its substance is amendable while it is a draft — so only this one
column is frozen.

### 5. The linkage is an audited fact, not a convenience column

`:retries-id` is in `clofin.audit/instruction-fields`, so the retry's
`payment.created` event carries in its after digest which payment it replaces. A
link altered afterwards would no longer match the digest the creation left
behind — which, together with the trigger, is what makes the relation provable
from the trail and not only from the row.

`:retried-by-ids` is deliberately **outside** that projection. It is derived from
other rows, so a projection carrying it would give one instruction two different
digests before and after somebody else raised a retry against it: a before/after
pair differing for a reason that is not a change to this record. That is the same
reasoning that keeps `age-seconds` out of `reconciliation-break-fields` and a
balance out of `account-fields`.

### 6. The exception workflow is a break that names the retry

ADR-0019 deferred the reference *because* "a provenance link with no workflow
reading it is a column, and the workflow is the part that needs product
thinking". The workflow is reconciliation's, which is why the ruling put it here.

Every reconciliation break now carries two derived facts: the payment
instruction it is about, and the instructions raised to retry that payment. Both
are computed in the statement that reads the break, from whichever side of the
disagreement it has — the ledger entry's `reference_id`, or the statement line's
`payment_reference` when that text is a UUID at all. Neither is stored.

That is the question an investigator holding a break about a payment that came
back asks first, and before this the answer was reachable only by matching
counterparty and amount by eye.

## Alternatives considered

**A generic "linked instruction" table with a link type.** Rejected, and
TASK-010's brief named it out for the same reason: one link kind is scoped —
retry → returned original — and generality without a second use case is
speculation. `reverses_id` has existed since increment 2 as its own column and
has cost nothing by being one.

**Deriving the link instead of storing it** — matching a retry to an original by
counterparty, amount and adjacency in time. Rejected: that is exactly the manual
procedure ADR-0019 described as the cost of the gap, and automating a heuristic
would produce a *confident* wrong answer where the operator currently produces a
cautious one. The operator raising the retry knows which payment it replaces;
asking them is the only source that is right by construction.

**Copying the original's values onto the retry**, so an operator retries with one
click. Rejected: it is design (3) inverted, and it would make the retry's
approval a rubber stamp of a payment that has already failed once.

**A `retried` status on the original.** Rejected. `returned` is terminal by
ADR-0019 and giving it an outgoing arrow is the design that ADR rejected; and
"has been retried" is answerable from the retries themselves, which is where the
fact lives.

## Consequences

- Migration `0013` adds `payment_instruction.retries_id`, an index on it, and
  the immutability trigger. `payment_instruction` gains no other column.
- `clofin.payments.state` gains `retryable-states` (`#{:returned}`) and
  `assert-retryable!` beside `reversible-states`, so the answer to "what may be
  retried?" lives where every other rule about status lives (ADR-0014).
- `POST /payment-instructions` accepts `retriesId`; `GET` renders it, and renders
  `retriedByIds` on an instruction that has been retried.
  `GET /reconciliation-breaks/{id}` and the break lists render `instructionId`
  and `retriedByInstructionIds`.
- A cross-tenant target is reported as **not existing** rather than as
  forbidden, exactly as `assert-debtor-account!` reports a foreign account:
  saying "another organisation's" would confirm that a guessed UUID names a real
  payment somewhere else (C-08).
- `COMPLIANCE.md` §4's *Linked-retry provenance* gap row is deleted, and
  `DOMAIN_MODEL.md` §2.3, §2.4 and §3 rule 5 stop calling it deferred.
