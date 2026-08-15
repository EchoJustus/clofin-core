# ADR-0025: Two audit terms for changes the trail did not carry — a restated batch status, and a rejected adjustment

- **Status:** Accepted
- **Date:** 2026-08-15
- **Deciders:** Master Control (TASK-010, ruling on `008-REQ` objection O-2 and
  observation N-5), Worker session
- **Supersedes / Superseded by:** — (closes C-05's one disclosed exception; the
  disclosure itself dates from TASK-004's remediation)

## Context

Two facts could change in CloFin and leave nothing in the audit trail that named
the thing that changed. They arrived by different routes and are recorded
together because they are the same kind of defect and were closed by the same
kind of fix: **a state change with no term is a state change an auditor cannot
find.**

**The first was disclosed.** A settlement batch's `status` is derived from its
items' outcomes. `settlement-batch.completed` is written once, when the last
unresolved item resolves. A **late** `timeout-resolution` then says what really
happened to an item the sweep had given up on, and a batch that derived to
`failed` becomes `settled` or `partially-settled`. That transaction wrote the
payment's own event, posted its finality entry and updated the stored batch
status — and wrote nothing whose subject was the batch. The `ref-1` release
audit reproduced it as finding **A-004**, and it has been the single disclosed
exception on **C-05**'s statement since, stated in four places at once.

**The second was not disclosed, because nobody could ask for it.** A proposed
reconciliation adjustment had two possible fates in the schema and one in
practice: an approver who disagreed simply did not approve. The adjustment stayed
`proposed` for ever, never posted, and a different one could be raised — but
nothing recorded *that somebody had refused it, or why*, which is exactly the
evidence C-05 keeps for a rejected payment. Migration `0012` declined to declare
a `rejected` status and said why in the file: naming the refusal would need a
second arrow, a second audit term and an endpoint that TASK-008's brief did not
ask for, and **a status nothing can reach is worse than an absent one**. It was
recorded as observation **N-5** in `008-REQ`.

Both were ruled to TASK-010.

## Decision

**Two new audit terms, each emitted only in the transaction where its own fact
commits — and, for the second, the lifecycle change that gives it a driver.**

### 1. `settlement-batch.status-restated`

A batch whose derived status was already terminal and moves again emits
`settlement-batch.status-restated`, whose subject is the batch.

**A second term rather than a second `completed`.** `completed` names the
transition *into* a complete batch, and that transition happened earlier, under
a different actor and a different correlation id. Two `completed` events for one
batch would be audit finding **F-005**'s mislabelling with a new name — an
auditor counting `completed` to learn how many batches finished would count some
twice — and standing lesson **L-7** exists to stop exactly that.

**"Restated" is the accounting word for it.** A restatement is a previously
reported figure corrected by later information, which is precisely what a late
answer does to a batch's outcome. It cannot be confused with `completed`, and it
does not claim more than it knows: nothing about the batch's *membership*
changed, only what its items add up to.

**Emitted only where the status actually moves.** The decision is
`clofin.settlement.service/batch-status-action`, and it is taken from the two
statuses rather than from the response `kind`:

| Before | After | Term |
|---|---|---|
| not complete | complete | `settlement-batch.completed` |
| complete | complete, different status | `settlement-batch.status-restated` |
| complete | complete, same status | none |
| not complete | not complete | none |

The last two rows are load-bearing. A response that resolves one item of ten
completes nothing; a late resolution that leaves `partially-settled` where it was
changes no batch-level fact. An event in either case would assert a transition
that did not occur, with before and after digests that are identical — F-005's
shape again. Deciding from the statuses rather than from the response kind means
a future path that moves the status the same way is the same fact and needs no
new term.

### 2. `reconciliation-adjustment.rejected`, and an adjustment lifecycle held as data

An approver other than the proposer may **reject** an adjustment, with a
mandatory reason. The adjustment becomes terminal, the break it named is left
exactly where it was, and a different adjustment may be raised against it.

**The lifecycle becomes data.** `clofin.recon.adjustment/transitions` replaces a
bare set of status names:

```clojure
{:proposed {:post :posted, :reject :rejected}
 :posted   {}
 :rejected {}}
```

This is the shape `clofin.payments.state` established and
`clofin.recon.break-state` copied, and the reason is theirs: a transition rule
written as an `if` inside a service is the failure both namespaces exist to
prevent. A set of two names was honest while `proposed → posted` was the only
move; a second move makes it a lifecycle, and a lifecycle that is not a table is
a lifecycle that gets restated differently in the next handler. `statuses` is now
**derived** from that table, so the vocabulary and the lifecycle cannot disagree,
and `clofin.db.vocabulary-test` compares the derived set with
`recon_adjustment_status_known` in the live catalogue in both directions. The
terminal set is derived through `terminal?` and is never a second list, and the
diagram at `docs/diagrams/reconciliation-adjustment-lifecycle.md` is generated
from the same value (ADR-0020 RULE 1, ADR-0021).

**Both arrows have drivers**, which is what migration `0012` was waiting for:
`:post` is driven by the transaction in which the approvals an adjustment needs
first exist, and `:reject` by a `rejected` decision on
`POST /reconciliation-adjustments/{id}/approvals`. Standing lesson **L-10** is
why the status could not simply have been declared in advance.

**The decision goes through the existing control, unchanged.**
`clofin.authz.approval/evaluate` already understood a `:rejected` decision: it
checks `:payment/reject` instead of `:payment/approve`, skips the approver's
ceiling and the organisation's bands — refusing a payment is not an exercise of
spending authority — and returns `:completes? true`, because one refusal ends the
subject. Self-approval is refused first and never waivably, so **the rejector
cannot be the proposer**, by the same comparison and the same ranking that
governs an approval. `assert-reason!` makes the reason mandatory, and
`approval_rejection_needs_reason` in migration `0005` makes a reasonless refusal
unrepresentable at the database — for both kinds of subject, because there is
one approvals table.

**The evidence lives where a rejected payment's lives, and nowhere else.** There
is no `rejected_at`, no `rejected_by` and no `rejection_reason` column on
`reconciliation_adjustment`. Who refused it, why and when are the `approval` row
the same transaction wrote. A second copy on the adjustment would be a second
thing to keep in step with it — standing lesson **L-6** in a column — and the
brief's own wording is *"the same class of evidence C-05 keeps for a rejected
payment"*.

**A rejection blocks nothing.** `recon_adjustment_posted_key` is partial on
`status = 'posted'`, so a rejected row is outside it and the break can be
corrected by a different adjustment. `recon_adjustment_posting_paired` already
required an entry id and a posting instant of anything that *is* posted and of
nothing else, so it needed no widening: a `rejected` row with neither satisfies
it as written.

**The break is not moved, and that is a statement rather than an omission.**
Proposing an adjustment never moved the break — the lifecycle in
`clofin.recon.break-state` has only `:assign` and `:resolve`, and a proposal
drives neither — so "the break returns to its prior state" is a property this
increment *preserves* rather than one it implements. A test asserts it: after a
rejection the break holds the state it held before the proposal, and a new
adjustment can be raised and posted against it. Giving a proposal its own break
state would be a redraw of increment 6's lifecycle rather than a completion of
its edges, and is recorded as an objection in `010-REQ` rather than taken
unilaterally.

## Alternatives considered

**Emit a second `settlement-batch.completed`.** Rejected on L-7 and F-005: the
transition into completion happened once, and a term that names it must be
emitted once.

**Emit `settlement-batch.status-restated` on every recomputation.** Rejected. An
event whose before and after digests are identical asserts a change that did not
happen, which is the defect this project has corrected twice already.

**Key the restatement off the response `kind` (`timeout-resolution`).** Rejected:
the fact the term names is *the status moving*, not the message that moved it. A
rule written against the kind would go quietly wrong the first time another path
moved a complete batch's status.

**A `cancelled` adjustment status instead of `rejected`, withdrawn by its
proposer.** Rejected: that is a different control with a different actor rule —
the proposer acting on their own proposal — and nothing in the brief or in
`008-REQ` asks for it. It is recorded as an observation in `010-REQ`.

**A separate `POST /reconciliation-adjustments/{id}/rejection` sub-resource**, in
the style of `POST /payment-instructions/{id}/cancellation`. Rejected: the
payment path records both decisions through one approvals endpoint with a
`decision` member, and there is one maker–checker control. An approver deciding
about an adjustment should send what they would send about a payment. The member
defaults to `approved`, so every caller written before this ADR keeps its
meaning.

**Storing the rejection reason on the adjustment as well.** Rejected, L-6: two
copies of one fact, and the `approval` row is the copy the evidence pack already
reaches.

## Consequences

- `clofin.audit/actions` gains `settlement-batch.status-restated` and
  `reconciliation-adjustment.rejected`; `api/openapi.yaml`'s `AuditAction` enum
  gains both. No new subject type: both events are about subjects the vocabulary
  already names.
- Migration `0013` widens `recon_adjustment_status_known` to three values by
  dropping and recreating it, and restates the column comment. No column is
  added and no other constraint moves.
- `clofin.recon.service/approve-adjustment!` becomes `decide-adjustment!` and
  takes a `:decision`. The two lists that assert each other —
  `clofin.audit.unit-of-work-test`'s matrix and `clofin.ledger.purity-test`'s
  service set — name the new function.
- `docs/diagrams/reconciliation-adjustment-lifecycle.md` joins the generated
  artifacts, with a both-directions test and the `ORPHAN` check covering its
  removal (the TASK-006 pattern).
- **C-05's statement carries no exception clause.** The disclosure is closed on
  every copy of it: the statement itself, `COMPLIANCE.md` §4's gap row,
  `api/openapi.yaml`'s Audit tag, and `DOMAIN_MODEL.md` §2.6's coverage
  paragraph. The *history* is kept in the first and last of those, because a
  control that was never qualified and one that was qualified and then closed are
  different facts and an auditor holding the `ref-1` report needs to find the
  second.
- **C-13 gains a sixth numbered statement** for the refusal evidence, with its
  set and its boundary named (**L-14**), and three enforcement points to match.
