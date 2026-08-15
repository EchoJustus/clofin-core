# 010-REQ — Reconciliation completion: the three disclosed gaps

| Field | Value |
|---|---|
| **Brief** | `docs/briefs/010-TASK-reconciliation-completion.md` — on **`origin/meta`**, which is the authoritative copy; the snapshot on `main` at `4171ade` is identical and is not edited here |
| **Increment** | 6c (completion) — closes the debt TASK-008 named; opens no new product surface |
| **Requirements** | ADR-0019 (ruled text); C-05; `008-REQ` O-1 / O-2 / N-5 and the TASK-008 changelog rulings |
| **Controls** | **C-05** — its one disclosed exception is **closed**; **C-13** gains a sixth statement and three enforcement points; C-01, C-02, C-03, C-08 reused unchanged |
| **Base** | `main` at `4171ade` |
| **Branch** | `claude/reconciliation-completion-gtcjnz` |
| **Model** | `claude-opus-5` |
| **Reasoning effort** | high |
| **Date** | 2026-08-15 |
| **Verification still in flight** | **No self-review, adversarial pass or long-running check of mine is outstanding.** See [§9](#9-verification-l-9), which also names the one thing that is running and is not mine to hide. |

---

## 1. What was built

Three items, exactly. Each was refused once by the TASK-008 Worker who was right
to refuse it unscoped, each had a ruling behind it, and each stops needing a
disclosure.

| Piece | Where |
|---|---|
| `payment_instruction.retries_id`, its index and its immutability trigger; the third adjustment status | `resources/migrations/0013-linked-retries-and-adjustment-rejection.sql` |
| What may be retried, as a rule about status beside every other one | `clofin.payments.state/retryable-states`, `assert-retryable!` |
| The link's validation, and the reverse side derived at read time | `clofin.payments.repository` (`assert-retry-target!`, `assert-one-linkage!`, `retried-by-column`) |
| The link on the audit digest | `clofin.audit/instruction-fields` |
| A break naming the payment it is about and that payment's retries, derived | `clofin.recon.repository` (`break-instruction-sql`, `break-columns`) |
| The batch-status term and where it is emitted | `clofin.audit/actions`, `clofin.settlement.service/batch-status-action` |
| The adjustment lifecycle, as data | `clofin.recon.adjustment/transitions` and the functions over it |
| Refusing an adjustment | `clofin.recon.service/decide-adjustment!`, `clofin.recon.repository/mark-rejected!` |
| Decisions | [ADR-0024](../ADR/0024-a-retry-names-the-returned-payment-it-replaces.md), [ADR-0025](../ADR/0025-two-audit-terms-for-changes-the-trail-did-not-carry.md) |
| Diagram | `docs/diagrams/reconciliation-adjustment-lifecycle.md`, generated |
| Walkthrough | [UAT-007](../uat/UAT-007-reconciliation-and-breaks.md) steps 12 and 13 |

**Contract.** `POST /payment-instructions` takes `retriesId`; `GET` renders it
and `retriedByIds`. A break renders `instructionId` and
`retriedByInstructionIds`. `POST /reconciliation-adjustments/{id}/approvals`
takes `decision` (`approved` | `rejected`, defaulting to `approved`) and returns
`rejected`. Two audit actions were added —
`settlement-batch.status-restated` and `reconciliation-adjustment.rejected` — and
the `AuditAction` enum carries both. **No new endpoint and no new subject type:**
both events are about subjects the vocabulary already names, and a refusal is a
decision on the endpoint decisions already go through.

## 2. The three decisions that shaped everything else

**A retry link that confers nothing.** `retries_id` relates two records and
grants no permission: no arrow out of `returned`, no second settlement
membership, no re-approval semantics, no auto-rebatching. ADR-0019 rejected all
four and none is reopened. The three questions ADR-0019 left open — chains,
cardinality, value rules — are answered in ADR-0024 with the reading stated, and
all three answers are "do not constrain": a retry of a retry is ordinary, an
original may be retried more than once over its life, and nothing compares the
retry's amount or beneficiary against the original, because *correcting one of
those is the reason a payment is retried.*

**Two terms, not one, for a status that can reach a terminal value twice.**
`settlement-batch.completed` names the transition *into* a complete batch;
`settlement-batch.status-restated` names a later correction of an outcome
already reached. Emitting a second `completed` would be audit finding F-005's
mislabelling with a new name, and emitting anything where the status did not move
would assert a transition that did not occur. Which term — or neither — is
decided from the two statuses rather than from the response `kind`, so a future
path that moves the status the same way is the same fact.

**A refusal is a move, so the adjustment's statuses became a lifecycle.** They
were a bare set of two names while `proposed → posted` was the only move, which
was honest. A second ending makes them a table — the shape
`clofin.payments.state` established and `clofin.recon.break-state` copied — with
`statuses` **derived** from it, the terminal set derived through `terminal?`, and
the diagram generated from the same value. Migration `0012` declined to declare
a `rejected` status precisely because nothing could reach it; ADR-0025 is the
record of it acquiring a driver.

## 3. Objections

Two. Both are about the brief's own text rather than about the work, and neither
blocked delivery — the work was done under the reading stated here.

### O-1 — "the break returns to its prior state" describes a move that increment 6 never made. Delivered as an invariant, and asserted; if a break state was intended, that is a redraw and needs a ruling.

The brief's item 3 says a rejection leaves *"the adjustment terminal, the break
returned to its prior state so a different adjustment can be raised"*, and AC-6
repeats it.

**Proposing an adjustment does not move the break.**
`clofin.recon.break-state/transitions` carries `:assign` and `:resolve` and
nothing else, and `clofin.recon.service/propose-adjustment!` drives neither: it
locks the break, refuses a terminal one, and inserts the adjustment. So there is
no state for a rejection to return the break *from*.

Two readings were available:

1. **The sentence states an invariant to preserve** — after a refusal the break
   holds the state it held before the proposal, and is still adjustable.
2. **The sentence assumes a break state a proposal moves into** — something like
   `pending-adjustment` — which a rejection would then reverse, and which would
   need the *prior* state remembered somewhere.

**What I did.** Reading 1, and it is asserted rather than assumed:
`ac-6-an-approver-can-reject-an-adjustment-with-a-reason` captures the break's
state before the proposal, asserts it is unchanged after the refusal, asserts
`resolvedAt` is still null, and then raises a *different* adjustment against the
same break and posts it.

**Why not reading 2.** It is a redraw of increment 6's lifecycle rather than a
completion of its edges. The brief's own framing is "this brief completes its
edges, it does not reopen its core", `L-4` and every brief since have said *drive
the arrows, do not redraw them*, and adding a state would need a stored or
derived "prior state" that nothing else in the model has. Diverging from a brief
without a ruling is a failed handover even when the divergence is right
(AGENT_HANDOFF §1b), so the smaller reading was taken and the larger one is
raised here.

**Asked of Master Control.** Confirm reading 1 — or, if a `pending-adjustment`
break state was intended, rule it and it becomes its own brief, because it
changes the break lifecycle, the generated diagram, `permittedTransitions` on
every break and the enumerated lifecycle test.

### O-2 — AC-4's opening clause describes a transition a `timeout-resolution` cannot cause. Built to its second clause, which is also what the Scope section says.

AC-4 reads: *"Given a late `timeout-resolution` that **completes** a batch, then
exactly one batch-subject event with the new term is emitted in that transaction
— and none when the resolution does not change the batch's derived status."*

A `timeout-resolution` resolves an item whose outcome is already `timed-out`, and
`clofin.settlement.batch/resolved?` counts `timed-out` as resolved — deliberately,
and its docstring says why. So the item it acts on was **already** resolved, the
batch's completeness cannot change, and a `timeout-resolution` can never move a
batch from incomplete to complete. The first clause is unsatisfiable.

The Scope section is right where the acceptance criterion is loose: *"A late
`timeout-resolution` that **moves an already-complete settlement batch's derived
status**"* — which is also the exception's own wording in C-05, in §4, in the
OpenAPI Audit tag and in DOMAIN_MODEL §2.6, all four of which say *moves*, none
of which says *completes*.

**What I did.** Built the Scope wording and AC-4's second clause, which agree
with each other and with all four copies of the exception, and covered both
directions:

- `ac-4-a-late-resolution-that-moves-a-complete-batchs-status-says-so` — a
  one-item batch swept to `failed`, then resolved `settled`: exactly one
  `status-restated`, exactly one `completed` (from the sweep, not a second one),
  audit count up by exactly two, and the two digests differ.
- `ac-4-a-late-resolution-that-moves-nothing-emits-nothing` — a two-item batch at
  `partially-settled`, a late resolution of the swept item to `returned`: the
  item moves, the batch does not, and the batch's action list is **identical**
  before and after.

**Asked of Master Control.** Ratify the Scope wording as the governing one, and
note that a criterion whose first clause cannot be satisfied would have passed
vacuously if it had been implemented literally.

## 4. Decisions taken, and the two that touch code outside the three items

**Both are flagged because they change code the brief did not name. Neither
changes a behaviour that existed.**

**(a) `clofin.recon.service/approve-adjustment!` is now `decide-adjustment!`, and
the two lists that assert each other were updated together.** A function that
records a rejection is not an "approve" function, and the name is the first thing
the next reader trusts. `clofin.audit.unit-of-work-test`'s matrix and
`clofin.ledger.purity-test`'s service set name each other by construction
(standing lesson **L-13**), so the rename touched both; the matrix gained a
second entry so the rejecting path is checked for the transaction precondition
too, not only the approving one.

**(b) `clofin.recon.repository/row->adjustment` now keywordises `:status`.** It
was a string while the "lifecycle" was a set of names; it is read against a table
keyed by keywords now, and a keyword table addressed with a string is a lookup
that silently returns nil. The wire rendering is unchanged (`(name …)`), and the
audit digest is unchanged too — `clofin.audit/normalise` renders a keyword and a
string identically, so no existing digest moves. Asserted by the enumerated
lifecycle test, which includes `(adjustment/terminal? "posted")` raising rather
than answering.

**Other decisions**, each with its reasoning in an ADR or in the namespace:

- **A break's `instructionId` is derived, not stored**, from the ledger entry's
  reference or — when it looks like a UUID at all — the statement line's
  end-to-end reference. The ledger side wins where both exist: it is CloFin's own
  record of what the movement was about. The text side is cast only behind a
  UUID-shaped regex, for the reason `clofin.recon.matching/reference-of` renders
  rather than parses: a garbled reference should fail to name anything, not fail
  the read.
- **`lock-break!` stopped spelling out its own column list** and now reads
  `break-columns` with `for update`. It carried a second copy of the projection,
  which is the shape L-6 names — and with two derived facts added, the two copies
  would have produced two different row shapes for the same row.
- **`retried-by-ids` is aggregated in SQL, not joined.** The ordinary case is one
  retry and a join would read identically; an original whose first retry was
  cancelled has two, and a scalar subquery meeting the second would fail the read
  rather than report it.
- **No `rejected_at`, `rejected_by` or `rejection_reason` column.** Who refused an
  adjustment, why and when are the `approval` row the same transaction wrote —
  the same place, and the only place, a rejected payment keeps them. A second
  copy would be a second thing to keep in step with it.
- **`db/->uuids` joins `->long`, `->instant` and `->local-date`** rather than a
  repository carrying a `java.sql.Array` into a domain value. An aggregate over
  no rows is `null` in SQL and an **empty vector** here, so no consumer tests for
  two shapes.

## 5. Observations

**N-1 — `reverses_id` has no immutability guard, and now the asymmetry is
visible.** `retries_id` is frozen by `payment_instruction_retry_link_immutable`
because the brief asked for an immutable reference and a docstring is not an
enforcement point (**L-6**). `reverses_id` — the same class of provenance, since
increment 2 — is protected only by `amendable-fields` and by no `UPDATE`
statement naming it. Not touched: it is a fourth item, and it belongs in a brief
that can decide whether the guard should be one trigger over both columns.

**N-2 — `docs/ROADMAP.md` on `main` still routes linked-retry provenance to
increment 6.** Line 240: *"The linkage (`retries_id`-style provenance and the
exception workflow around it) belongs to increment 6 (reconciliation)"*. That is
a control-plane document, maintained on `meta`, and a Worker does not edit it
(AGENT_HANDOFF §1). Reported so Master Control can correct it on `meta` in the
same pass that moves this brief to `IMPLEMENTED`. `make doc-consistency` does not
catch it, because it compares control *statuses* and brief statuses rather than
prose about deferred work.

**N-3 — `api/openapi.yaml`'s `getEvidencePack` prose is stale in two ways**, and
both predate this increment. It says *"A subject is a payment instruction, an
approval, an organisation, a ledger account, a journal entry or a settlement
batch — every record CloFin can write"*, which omits the three reconciliation
subjects TASK-008 added and then claims completeness; and it names
`AuditSubjectType` as "the authoritative list", which is not a schema in the file
— `clofin.contract-test` discovers the enum on `AuditEvent` and `EvidencePack`
instead and pins those two names. A fourth item, so it is here rather than in the
PR. It is an **L-14** instance: a universal quantifier whose set is smaller than
the sentence claims.

**N-4 — `clofin.authz.repository/record-approval!` inlines `#{:approved
:rejected}`** rather than reading `clofin.authz.approval/decisions`. That is the
same second-statement-of-one-vocabulary shape audit finding **A-014** named, and
`approval.clj`'s own docstring cites A-014 as the reason `decisions` exists.
Noticed while extending the decision path; not changed, because it is a control-
adjacent refactor outside three named items and would be better done with the
`approval_decision_known` comparison in view.

**N-5 — nothing invalidates an adjustment's approvals.**
`clofin.authz.repository/invalidate-approvals-for!` exists for instructions and
has no adjustment equivalent. It is not a gap today: an adjustment's substance
cannot be amended, so there is no event that should invalidate a decision about
it. Recorded because "there is no equivalent" and "no equivalent is needed" look
identical from the code.

**N-6 — a proposer cannot withdraw their own adjustment.** A payment's approver
may withdraw a decision (`DELETE …/approvals/{id}`); an adjustment's proposer has
no way to retract a proposal, and after this increment the only way one ends is
by somebody else deciding it. That is arguably right — a proposal nobody has
decided is harmless, and self-withdrawal is a different control with a different
actor rule — but it is now the asymmetry between the two subjects, so it is
written down. Named as a rejected alternative in ADR-0025 too.

**N-7 — the assertion count moves by a few dozen between runs**, and that is
expected rather than alarming, for the reason `008-REQ` gives:
`clofin.api.settlement-api-test`'s property test generates outcome mixes of
varying size. The test and failure counts do not move.

## 6. Acceptance criteria

| # | Covered by |
|---|---|
| AC-1 | `ac-1-a-retry-names-the-returned-instruction-it-replaces` (stored, immutable at the API **and** at the database, visible both ways, carried on the audit event — including that the digest *moves* when the link is present, so the projection is proved to cover it); `ac-1-a-retry-of-a-retry-is-permitted-and-the-chain-is-not-collapsed`; `ac-1-an-original-may-be-retried-more-than-once-over-its-life`; `ac-1-an-ordinary-instruction-carries-neither-member`; `ac-1-retries-id-is-optional-but-must-be-a-uuid-when-present`; `ac-1-a-built-instruction-and-a-loaded-row-have-the-same-shape`; `ac-1-neither-link-may-be-amended` |
| AC-2 | `ac-2-a-retry-target-that-is-not-returned-is-refused-by-name` (a draft and a settled instruction, each with `instruction-status`, `attempted` and `retryable-in` in the refusal — **and** the rollback half: no row and no event); `ac-2-a-retry-target-in-another-organisation-reveals-nothing-about-it` (`422` identical to a made-up id, and no status leaks); `ac-2-an-instruction-cannot-be-both-a-reversal-and-a-retry`; `ac-2-a-malformed-retries-id-is-reported-with-every-other-failed-field`; `ac-2-only-a-returned-instruction-is-retryable` and `ac-2-assert-retryable-refuses-with-the-state-it-found` in the pure layer, enumerated over every state |
| AC-3 | `ac-3-a-break-on-a-returned-original-names-the-retry` — the break names the instruction before any retry exists and carries **no** `retriedByInstructionIds`; after the retry is raised, the same break names it, from the detail read and from the list, with nothing written to the break; `ac-3-neither-derived-fact-is-a-column-on-the-break` asserts the absence in the live catalogue |
| AC-4 | `ac-4-a-late-resolution-that-moves-a-complete-batchs-status-says-so`; `ac-4-a-late-resolution-that-moves-nothing-emits-nothing`; `ac-4-a-refused-late-resolution-restates-nothing`. See objection **O-2** on the criterion's first clause |
| AC-5 | §7 below — the enumeration, with the grep output, before and after |
| AC-6 | `ac-6-an-approver-can-reject-an-adjustment-with-a-reason` (terminal, reason recorded on the decision, break unchanged, a different adjustment then posts); `ac-6-the-rejector-must-differ-from-the-creator`; `ac-6-a-rejection-with-no-reason-is-refused`; `ac-6-a-rejected-adjustment-is-terminal-for-every-decision`; `ac-6-an-approval-still-works-and-the-default-decision-is-unchanged`; and in the pure layer `ac-6-every-status-event-pair-is-either-permitted-by-the-table-or-refused` (enumerated, not sampled) and `ac-6-the-terminal-set-is-derived-and-is-not-a-second-list` |
| AC-7 | `ac-7-the-adjustment-lifecycle-diagram-and-its-table-agree-in-both-directions`; `ac-7-an-adjustment-transition-added-without-regenerating-fails-the-check` (the negative control, run rather than asserted); `ac-7-the-generated-index-names-every-lifecycle-that-is-drawn`; `clofin.db.vocabulary-test`'s `recon_adjustment_status_known` ↔ `clofin.recon.adjustment/statuses` pair, compared against the live catalogue in both directions; `the-audit-vocabulary-in-the-contract-is-the-one-the-service-enforces` for both OpenAPI enum copies; `make diagrams-check` |
| AC-8 | `ac-6-and-ac-8-a-rejection-leaves-exactly-two-events-and-a-rollback-leaves-none`; the rollback half of `ac-2-a-retry-target-that-is-not-returned-is-refused-by-name`; `ac-4-a-refused-late-resolution-restates-nothing`; `ac-1-a-retry-names-…`'s single `payment.created`; and the pre-existing `ac-8-*` family, which still passes unchanged |

## 7. AC-5 — the enumeration, not the memory (L-16)

The brief asks that the closure be **asserted by grep, not by memory**. Here is
the enumeration and its output, run from the repository root.

**The claim's copies, on `main` at `4171ade`:**

```
$ git show origin/main:docs/COMPLIANCE.md | grep -n "batch-subject event"
267:batch's derived status emits no batch-subject event.** That exception is
469:second batch-subject event**, because `settlement-batch.completed` names the
888:| Batch-status-change audit term | **Not built.** A late `timeout-resolution` moves an already-complete batch's derived status and writes no batch-subject event, …

$ git show origin/main:docs/DOMAIN_MODEL.md | grep -n "batch-subject event"
341:derived status, and that recomputation emits no batch-subject event, because

$ git show origin/main:api/openapi.yaml | grep -n "batch-subject event"
177:      no second batch-subject event, because `settlement-batch.completed` marks
```

Four live copies, in the three documents `008-REQ` O-4 enumerated: the C-05
**statement** (line 267), the C-05 *boundary* paragraph (469), the §4 **gap row**
(888), the `DOMAIN_MODEL.md` §2.6 **coverage paragraph** (341), and the OpenAPI
**Audit tag** (177).

**The same enumeration on this branch:**

```
$ grep -rn "batch-subject event" docs/COMPLIANCE.md docs/DOMAIN_MODEL.md api/openapi.yaml
$ echo $?
1
```

No match in any of the three. Widening the pattern to every phrasing the claim
has ever used:

```
$ grep -rn "batch-subject event\|no second batch-subject\|one disclosed exception\|One qualification" \
      docs/COMPLIANCE.md docs/DOMAIN_MODEL.md api/openapi.yaml
docs/COMPLIANCE.md:267:**That statement carried one disclosed exception from TASK-004's remediation
```

That one hit is **the history, not the exception**: the sentence reads *"carried
one disclosed exception from TASK-004's remediation until TASK-010, and it no
longer does"*, and the paragraph goes on to name the release-audit finding and to
point at the closed edge. `DOMAIN_MODEL.md` §2.6 keeps the same three-step
history for the same reason.

**Why the history is kept rather than deleted.** A control that was never
qualified and one that was qualified and then closed are different facts. An
auditor holding `FEEDBACK-REL-ref-1.md` — which quotes the old COMPLIANCE lines
verbatim as finding **A-004** — must be able to find the closure from the
document, not conclude that the disclosure was quietly removed. Deleting the
trace would look, from outside, exactly like the over-statement L-14 exists to
prevent.

**Where the phrase still appears, and why each is correct:**

```
$ grep -rln "batch-subject event" . | sed 's|^\./||' | sort
docs/audits/004-REQ-settlement-simulation.md              # historical REQ — never edited (§1)
docs/audits/008-REQ-reconciliation.md                     # historical REQ — never edited
docs/audits/010-REQ-reconciliation-completion.md          # this file
docs/audits/FEEDBACK-M2-settlement-and-audit-coverage.md  # audit record — never edited
docs/audits/FEEDBACK-REL-ref-1.md                         # audit record — never edited
docs/briefs/010-TASK-reconciliation-completion.md         # the brief itself, a `meta` snapshot
src/clofin/settlement/service.clj                         # the docstring of the function that now emits one
test/clofin/api/settlement_api_test.clj                   # the tests that assert it
```

Every remaining hit is either code describing the closure or a governance
document a Worker does not edit.

**The other two gap rows, likewise deleted from `COMPLIANCE.md` §4:**

```
$ git show origin/main:docs/COMPLIANCE.md | grep -c "^| Linked-retry provenance |\|^| A rejected adjustment |\|^| Batch-status-change audit term |"
3
$ grep -c "^| Linked-retry provenance |\|^| A rejected adjustment |\|^| Batch-status-change audit term |" docs/COMPLIANCE.md
0
```

## 8. Standing lessons, and where each one bit

| Lesson | Where it applies here |
|---|---|
| **L-1** | Migration `0013`, ADR-**0024** and ADR-**0025** are next-available against the live tree at build time; this REQ is task-keyed `010-REQ` |
| **L-4** | The adjustment lifecycle is **drawn from the table**, not beside it, and objection **O-1** refuses to redraw the break lifecycle without a ruling |
| **L-5** | `payment_instruction` and `reconciliation_adjustment` are deliberately **not** append-only — a status moves on both — so the new guard is one frozen *column*, not a table guard, and it says so in the migration |
| **L-6** | Immutability is a trigger rather than a docstring; `lock-break!`'s second copy of the break projection was removed; `adjustment/statuses` is derived from the lifecycle rather than declared beside it; the refusal reason is kept in one place rather than copied onto the adjustment |
| **L-7** | Both new terms are emitted **only** where their own fact commits: `status-restated` only where the derived status moves, `reconciliation-adjustment.rejected` only in the transaction the adjustment becomes terminal — and the decision itself stays `approval.recorded`, because a decision taken and a subject becoming terminal are two facts |
| **L-8** | `assert-retry-target!` reads its target `for update`, in the documented lock order; `mark-rejected!` claims the refusal in the statement rather than after a read |
| **L-9** | §9 below, stated plainly |
| **L-10** | The `:reject` arrow was added **with** its driver; `each-decision-drives-exactly-one-lifecycle-event` asserts that every arrow in the table has one, so a status nothing can reach cannot be declared |
| **L-13** | The rename touched both lists that assert each other, and the unit-of-work matrix gained the rejecting path as well as the approving one |
| **L-14** | C-13's new statement names its set and its boundary; C-05's closure names what it closed rather than deleting the trace; the two things `status-restated` does *not* claim are stated in the same breath |
| **L-16** | §7 — every copy enumerated by grep, before and after, rather than closed from memory. The same lesson governs the link's two ends: the reverse side is **derived** from the retries themselves, so there is no second copy that could disagree with the first |

## 9. Verification (L-9)

**No self-review, adversarial pass or long-running check of mine is
outstanding.** Everything I started has finished; nothing is pending that could
surface a fix and change this report.

**One thing is running and it is not mine to hide:** GitHub Actions on the pull
request, started by the push. It is the same jobs as the table below plus
`make smoke`, which this session could not run. A merge should wait for it, as it
would for any PR.

Everything below was run to completion on this branch's final tree, against
PostgreSQL 16.13:

| Command | Result |
|---|---|
| `make verify` (`test` + `docs-check` + `diagrams-check` + `doc-consistency`) | **pass** — 438 tests, 2 779 assertions, 0 failures, 0 errors; every internal documentation link resolves; **7** generated artifacts match their sources; 13 controls consistent |
| `clojure -M:test:it` (full suite, unit + property + integration) | **pass** — **841 tests, 6 324 assertions, 0 failures, 0 errors** |
| `clojure -M -m clofin.db.migrate` | 13 migrations applied; `clofin.db.migrate-test` migrates from an empty schema, so `0013` is exercised against a fresh database and not only against mine |

Two limits on that, stated rather than implied:

1. **`make smoke` was not run.** This session has no Docker daemon — the client
   is present and `/var/run/docker.sock` is not — so the compose stack could not
   be started. Nothing in this increment changes `docker-compose.yml`, the
   Dockerfile or start-up, and CI's `stack` job covers it.
2. **The suite ran against a PostgreSQL 16.13 instance started by hand** rather
   than by `make db-up`, for the same reason. The schema is the one migrations
   `0001`…`0013` produce, and `clofin.db.vocabulary-test` compares every closed
   vocabulary — including the widened `recon_adjustment_status_known` — against
   that live catalogue.

## 10. What the next session should pick up

- Master Control's rulings on **O-1** and **O-2**, and the `ROADMAP.md`
  correction in **N-2**, which is a `meta` edit rather than work.
- The four observations that are genuine debt and are not this brief's: **N-1**
  (`reverses_id` has no immutability guard), **N-3** (the `getEvidencePack`
  prose is smaller than its claim and names a schema that does not exist),
  **N-4** (`record-approval!` restates the decision vocabulary), **N-6** (a
  proposer cannot withdraw their own adjustment).
- The `ref-2` / Milestone-3 audit inherits this increment together with
  TASK-008. Amendment **A1** of the 2026-08-04 assurance-chain decision puts it
  at the **Sol** tier for the same reason TASK-008 was: this increment adds a
  migration and touches enforcement code in the authorisation domain — the
  approval path now records a refusal about a second kind of subject.
- Increment 7 (financial crime) is still unblocked. Nothing here reserves a term
  or a table it needs.
