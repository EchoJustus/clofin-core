# ADR-0019: A returned payment is terminal; the retry is a new instruction

- **Status:** Accepted
- **Date:** 2026-08-05
- **Deciders:** Master Control (ruling on FEEDBACK-M2 F-007), Worker session
  (TASK-004 remediation)
- **Supersedes / Superseded by:** — (amends the index rationale recorded in
  migration `0009`; see *Consequences*)

## Context

TASK-004 shipped two rules about the same question — *may a payment that came
back be settled again?* — and they gave different answers.

**The schema said yes.** Migration `0009` declared a *partial* unique index:

```sql
create unique index settlement_item_live_key
  on settlement_batch_item (instruction_id)
  where outcome is distinct from 'returned';
```

with a comment stating that "only `returned` frees it for re-batching", and
TASK-004's AC-7 promised the same in product terms: *a returned item's
instruction re-batches successfully.*

**The application said no, and could not be persuaded otherwise.** Batch
eligibility is `approved` and nothing else (`clofin.settlement.batch/eligible-status`);
`:returned` is terminal in `clofin.payments.state` with no outgoing arrow. A
public retry was refused `not-approved`, and would have failed the `:release`
transition even if construction had let it through.

The Milestone 2 external audit reproduced both halves at once — finding
**F-007**, `blocking`, in `FEEDBACK-M2-settlement-and-audit-coverage.md` on the
`meta` branch: the public retry returned `422`, and a raw membership insert for
the same instruction **committed**, leaving membership count `2`. So the
acceptance criterion was false end to end, and the schema was advertising a
safety-preserving permission no workflow could reach.

The finding also produced standing lesson **L-10**: *a schema path is not a
product path.* AC-7 had been "proved" by a raw-SQL insert the database permitted.
That test was correct about the database and said nothing about the product.

Two designs could resolve the contradiction, and the brief was explicit that
splitting the difference was not available:

- **(a) Make the promise true.** Define a retry of the *same* instruction:
  a lifecycle arrow out of `:returned`, re-approval semantics, audit actions for
  the retry, and a second release posting.
- **(b) Withdraw the promise.** Treat `returned` as terminal, tighten the index
  to admit no second membership at all, and make the retry a *new* instruction.

## Decision

**Design (b). A returned payment is terminal, and a retry is a new payment
instruction.**

Migration `0010` replaces the partial index with full uniqueness over
`instruction_id`, renamed `settlement_item_instruction_key`. An instruction
belongs to at most one settlement membership, ever — pending, settled, timed out
and returned all block a second one.

Three reasons, in the order they weighed.

**1. It is the doctrine `settled` already follows.** `DOMAIN_MODEL.md` §3 rule 4
says a settled payment is never mutated; a correction is a *new* reversing
instruction. `returned` is the other terminal outcome of the same journey, and
money has moved and come back in both cases. Giving the two terminal outcomes
opposite doctrines would mean the model had two answers to "what do you do with a
finished payment?", which is how the contradiction arose in the first place.

**2. A second attempt is a second payment decision.** Under design (a) an
instruction approved once, released once, and returned by the scheme could be
released again on the strength of the original approval. Approval is a
maker–checker control over *this payment going out* (C-01, PR-071). A return is
new information — a closed account, a rejected beneficiary — and it is exactly
the information a checker should see before the money is sent again. Under (b)
the retry is raised, submitted and approved on its own merits, and the whole
control applies to it. Design (a) would have had to invent re-approval semantics
to avoid weakening the control, and would then have arrived at (b)'s behaviour
with an extra lifecycle arrow.

**3. The safe error is the recoverable one.** Under (b) the failure mode is an
operator having to raise a payment again — visible, ordinary, and correctable in
minutes. Under (a) the failure mode is a payment released twice on one approval.
The asymmetry is not close.

## What this costs, stated rather than glossed

An operator whose payment came back must raise a new instruction, have it
approved, and batch that. Nothing in the record relates the retry to the payment
it replaces: an investigator matching them today does so by counterparty and
amount, not by a reference.

That is a real gap and it is **deferred, not denied**. Linked-retry provenance —
a `retries_id`-style reference and the exception workflow around it — is ruled to
be increment 6's (reconciliation), where return-exception handling natively
lives. It is deferred rather than added here because a provenance link with no
workflow reading it is a column, and the workflow is the part that needs product
thinking. Recording it against increment 6 on the ROADMAP is Master Control's, on
the control plane; this ADR is the data-plane statement of why the gap exists.

The refusal an operator meets names the correction rather than only the rule.
`clofin.settlement.repository/add-items!` translates the index violation into a
`409` that says the payment is terminal and that the retry is a new instruction —
because a refusal an operator cannot act on becomes a request to disable the
check.

## Alternatives considered

**Widen the lifecycle to allow `returned → approved` (design (a)).** Rejected on
reasons 2 and 3 above. It also contradicts the brief's own standing instruction
for this increment — *drive the arrows, do not redraw them* — which is the rule
lesson **L-4** exists to enforce.

**Leave the index partial and simply amend AC-7 to say the retry is unbuilt.**
Rejected: the index would still admit a second membership that no application
path creates, so a fix-up script or a defect could still produce one. The guard
against settling one payment twice lives in the schema precisely so that it binds
writers who are not this application, and a guard with a hole nobody uses is a
hole.

**Keep the index name `settlement_item_live_key`.** Rejected. "Live" named a
distinction — live memberships versus dead ones — that no longer exists. A
constraint whose name asserts a rule it does not implement is the shape of defect
this remediation exists to remove, and the name appears in the error an operator
reads.

## Consequences

- Migration `0010` drops `settlement_item_live_key` and creates
  `settlement_item_instruction_key`. Migration `0009` is immutable and keeps its
  original comment (ADR-0009); the new index's comment records what changed and
  why, so the two read in sequence.
- `DOMAIN_MODEL.md` §2.3 and §3 rule 5, `ARCHITECTURE.md` §5.6, the OpenAPI
  descriptions for `createSettlementBatch` and `sweepSettlementTimeouts`, and
  UAT-006 step 8b all state the tightened rule.
- AC-7 is amended in TASK-004's brief by the same ruling, and is now asserted
  **from the public command** as well as in raw SQL — `L-10`'s requirement. The
  audit's own raw insert for a returned instruction is a refusal test in
  `clofin.settlement.repository-test`.
- A timed-out item's status is unchanged by this ADR: it was already
  un-re-batchable, and a late `timeout-resolution` still resolves it exactly
  once. What changes is that resolving it never frees the instruction either.
- Increment 6 inherits linked-retry provenance as scoped, named work rather than
  as a discovery.
