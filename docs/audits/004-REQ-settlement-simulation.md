# 004-REQ — Settlement simulation

| Field | Value |
|---|---|
| **Brief** | `004-TASK-settlement-simulation.md` (on `origin/meta`) |
| **Increment** | 5 |
| **Branch** | `claude/audit-coverage-completion-6iuffy` — **substituted for the brief's `feat/settlement-simulation`**; the execution environment designates the push branch and this session cannot create another. The name is TASK-005's and is now misleading; noted per instruction |
| **PR base** | `main` at `2ba977e` — TASK-003 and TASK-005 merged |
| **Migration** | `0009-settlement-batches-and-scheme-responses.sql` — next available against the live tree, verified (L-1) |
| **ADR** | `0018-release-posts-to-settlement-in-transit.md` — next available, verified |
| **UAT** | `UAT-006-settlement-simulation.md` — next available, verified |
| **Controls** | C-03, C-04 exercised. **C-07 untouched and still 📋**; `TODO(increment-7)` left exactly where it was |
| **Status** | Implemented. **Three objections** in §4, one of them a process finding about the previous increment. Seven observations in §5 |

> **Reopened 2026-08-05.** The Milestone 2 external audit returned
> REMEDIATION-REQUIRED against this increment. §1–§7 below are the original
> submission and are **left as written** — a report edited after its findings
> landed is not a record. The remediation is **[§8](#8-remediation-addendum--feedback-m2-findings-f-007--f-011)**,
> which carries its own provenance, verification and declarations.

---

## 1. What was built

Approved payments now go somewhere. A batch is constructed for one simulated
scheme, currency and value date; submitting it releases every member and posts;
a scheme response settles or returns one member; a sweep gives up on the ones
nobody answered.

| Namespace | Role |
|---|---|
| `clofin.settlement.batch` | Pure. Eligibility, grouping, and the **single statement** of status derivation |
| `clofin.settlement.scheme` | Pure. The adapter protocol, and the one simulated implementation |
| `clofin.settlement.repository` | The persistence seam, and the lock order |
| `clofin.settlement.service` | The units of work, on the caller's `tx`, requiring no `clofin.db.*` |
| `clofin.api.settlement` | Six endpoints; opens the transaction, decides nothing |

`clofin.payments.posting` gained `settlement-lines`, `return-lines`,
`settlement-entry` and `return-entry` — **extended, not forked**, as the brief
asked.

**Every arrow was driven, none redrawn.** `release`, `settle` and `return` were
already in `clofin.payments.state/transitions`; the diff does not touch that
file. `fail` is still driven by nothing — see O-1.

**The lock order gained a third row type, and it goes first:**
`settlement_batch` → `payment_instruction` (`order by id`) → `ledger_account`.
Batch first because every settlement operation addresses a batch and only then
reaches its members; instructions ordered by id because two batches sharing
members and locking them in opposite orders deadlock, and overlapping membership
is the *normal* case for a settlement module. Stated in
`clofin.settlement.repository`'s docstring, where the next person to add a
function will read it.

**L-8 discipline.** Eligibility is read under `for update` and re-checked at
submission, not merely trusted from construction: an instruction amended or
cancelled between the two is no longer approved, and releasing it would release
a payment nobody currently agrees to.

**L-7 discipline.** Four events, four different facts, and the two that could
have been sloppy are not:

- `settlement-batch.completed` is emitted **only** when the batch was not
  complete before the transaction and is after it — so a response resolving one
  item of ten completes nothing, and a *late* resolution that changes an
  already-complete batch's status updates the status and emits no second
  `completed`.
- The timeout sweep emits `settlement-batch.timeout-swept` **and no
  payment-level event at all**, because no payment changed state. Emitting
  `payment.failed` there would be F-005's mistake with a new name — and would
  also be a lie, since nobody knows the payment failed.
- A sweep that swept nothing writes no event. Nothing happened.

**L-5 discipline.** `scheme_response` is append-only against the **full**
destructive verb set — `UPDATE`, `DELETE` **and** `TRUNCATE`, the last a
separate trigger event that visits no rows. The brief left this table's
enforcement to me; I enforced it, reusing `reject_mutation()` from migration
`0002` rather than redefining it, and said why in the migration. The
owner-adversary limit is restated there and remains named debt in COMPLIANCE §4.
`settlement_batch_item` is deliberately **not** append-only: resolving an
outcome is an `UPDATE`, and it is the whole mechanism of the module.

### The migration was validated, not assumed

Applied against live PostgreSQL 16 on top of 0001–0008, then every documented
row shape and every guard probed by hand with savepoints (L-3's discipline
applied to my own DDL, not only to the brief's):

| Probe | Result |
|---|---|
| `scheme = 'SWIFT'` | refused — `settlement_scheme_known` |
| Re-batch a **pending** item | refused — `settlement_item_live_key` |
| Re-batch a **timed-out** item | refused — the guard that matters |
| Re-batch a **returned** item | permitted |
| `returned` with no reason | refused — `settlement_return_needs_reason` |
| Two identical batch-level acks (null `instruction_id`) | refused — `nulls not distinct` works as intended |
| `UPDATE` / `DELETE` / `TRUNCATE` on `scheme_response` | all three refused |

---

## 2. Acceptance criteria

| # | Covered by |
|---|---|
| AC-1 | `settlement.batch-test/ac-1-only-an-approved-instruction-may-be-batched`; `api.settlement-api-test/ac-1-a-batch-contains-exactly-the-approved-instructions-named`, `…/ac-1-an-instruction-that-is-not-approved-is-refused-by-name`, `…/ac-1-a-released-instruction-cannot-be-batched-again` |
| AC-2 | `settlement.batch-test/ac-2-a-batch-is-one-currency-and-one-value-date`; `api.settlement-api-test/ac-2-a-batch-is-one-scheme-one-currency-one-value-date` — see **O-2** for the scheme half |
| AC-3 | `api.settlement-api-test/ac-3-submission-releases-every-member-with-one-audit-event-each` (asserts the exact audit count, so a stray event fails), `…/ac-3-a-batch-cannot-be-submitted-twice` |
| AC-4 | **`api.settlement-api-test/ac-4-the-ledger-stays-balanced-across-every-outcome-mix`** — a `defspec` over generated settled/returned mixes, driving each through the whole API and then querying the journal directly for any entry that does not net to zero. Also asserts the entry count is exactly one release plus one finality entry per instruction, so a double posting fails even if it balanced |
| AC-5 | `settlement.repository-test/ac-5-a-duplicate-scheme-response-is-refused-by-the-replay-key`, `…/two-identical-batch-level-acks-collide-rather-than-coexisting`, `…/an-item-resolves-exactly-once`; `api.settlement-api-test/ac-5-an-identical-scheme-response-delivered-twice-does-no-work`, `…/ac-5-an-out-of-order-response-is-a-conflict-not-a-silent-overwrite` |
| AC-6 | `api.settlement-api-test/ac-6-the-sweep-marks-unanswered-items-timed-out-and-leaves-them-unknown`, `…/ac-6-a-late-timeout-resolution-resolves-exactly-once`, `…/ac-6-a-timeout-resolution-must-name-the-outcome-it-resolves-to`; `settlement.repository-test/only-a-timed-out-item-can-be-resolved-by-a-timeout-resolution`, `…/the-sweep-honours-its-horizon` |
| AC-7 | **`settlement.repository-test/ac-7-the-database-itself-refuses-a-second-live-membership`** — raw SQL, application bypassed, all four cases (pending / settled / timed-out block; returned frees). Plus `api.settlement-api-test/ac-7-a-timed-out-instruction-cannot-be-re-batched-through-the-api`, which asserts the API refusal *and* re-runs the raw-SQL attempt |
| AC-8 | `api.settlement-api-test/ac-4-a-partly-settled-batch-…` (the `exceptions` list with its reason), `…/ac-8-a-returned-response-must-carry-a-reason` |
| AC-9 | `api.settlement-api-test/ac-9-a-rolled-back-outcome-leaves-no-posting-no-outcome-and-no-audit-event` |
| AC-10 | `authz.model-test/no-single-role-both-approves-and-settles`, `…/settlement-is-a-controller-right-and-only-a-controller-right`; `api.settlement-api-test/ac-10-settlement-requires-the-settlement-permission`, `…/ac-10-every-settlement-mutation-is-gated`, `…/reading-a-batch-needs-only-payment-read` |
| AC-11 | `api.settlement-api-test/ac-11-an-instructions-evidence-pack-shows-release-and-outcome-in-order` — asserts the exact ordered action list for the instruction *and* for the batch |
| AC-12 | `clofin.contract-test` (unchanged, now covering six more operations); `api.settlement-api-test/ac-12-the-simulation-rule-is-the-one-the-adapter-implements`; `recordSchemeResponse`'s OpenAPI description states it is the simulation injection point and documents how to produce partial failure on demand |

### AC-5 and AC-7 — the two that must not be compromised

Neither was hard, and neither was weakened. Both rest on the schema rather than
on application code:

- **AC-7** is a partial unique index. The API refuses a re-batch attempt earlier
  and more helpfully (a `422` naming the reason, because a timed-out
  instruction is still `released` and so fails eligibility first), but the index
  is what makes the claim true for a fix-up script. Both are asserted.
- **AC-5** is the response replay key plus `where outcome is null` in the
  resolving `UPDATE`. The second is not redundant: the replay key catches the
  same delivery twice, the `where` clause catches two *different* deliveries
  racing for the same item.

One implementation fact worth recording, because it was a real defect caught by
the tests: **catching a constraint violation is not enough.** PostgreSQL aborts
the whole transaction on one, so the duplicate path's next read failed with
`current transaction is aborted` and surfaced as a `500` — on precisely the path
that is supposed to answer `200 replayed: true`. The fix is
`clofin.db.core/tolerating-violation`, which runs the insert inside a
**savepoint** so the rollback is local. That is the only correct way to express
"insert, and if it collides, carry on in the same transaction", and it is now
documented as such where the next person will need it.

---

## 3. Test results

| | Tests | Assertions |
|---|---|---|
| Baseline `2ba977e`, `make verify` | 233 | 1321 |
| **This branch, `make verify`** | **272** | **1502** |
| Baseline `2ba977e`, `make test-it` | 489 | 2747 |
| **This branch, `make test-it`** | **567** | **3435** |

Both green, 0 failures and 0 errors, against PostgreSQL 16 from
`docker-compose.yml`. The migration was also applied from an empty schema by
`clofin.db.migrate-test`, which is what CI's compose smoke test does.

Two notes on the numbers. Three of the unit assertions come from the
carried-forward TASK-005 remediation rather than from this increment (O-3). And
the **integration assertion count varies by a few between runs**, because AC-4's
`defspec` generates outcome vectors of varying length — the figure above is one
run; the test count is stable.

The four new settlement namespaces contribute **70 tests** on their own, and were
run five times over to confirm they are stable — one of them was not, and the
fix is worth recording: `ac-11`'s evidence-pack assertion originally compared the
whole ordered action list, but `approval.recorded` and `payment.approved` are
written in the **same transaction**, so they share `occurred_at` to the
microsecond and are ordered by a **random** `id`
(`clofin.audit.repository/ordered` says exactly this). The assertion passed in
the full suite and failed when the settlement namespaces ran alone. It now
asserts the multiset of actions plus six pairwise orderings, every one of which
crosses a transaction boundary and is therefore a real fact about time rather
than the outcome of a UUID comparison.

New namespaces: `clofin.settlement.batch-test`, `clofin.settlement.scheme-test`
(unit); `clofin.settlement.repository-test`, `clofin.api.settlement-api-test`
(integration). Extended: `clofin.payments.posting-test` (the two finality
templates), `clofin.authz.model-test` (the approve/settle separation),
`clofin.ledger.purity-test` (two new pure namespaces, a new seam, a new
service), `clofin.test-db` (the new append-only table and the truncate list).

---

## 4. Objections

Per AGENT_HANDOFF §1b, recorded rather than resolved unilaterally.

### O-1 — the brief's scope and vocabulary require an item outcome its own DDL forbids

Scope item 5 says scheme responses drive `released → settled | failed | returned`
**per item**, and item 9 requires `payment.failed` in the vocabulary. The brief's
own DDL says:

```sql
constraint settlement_outcome_known
  check (outcome is null or outcome in ('settled','returned','timed-out')),
```

There is no `failed`. Nor is there a `failed` response kind:
`scheme_response_kind_known` permits `ack | settled | returned |
timeout-resolution`. So no item outcome and no response kind can drive the
lifecycle's `fail` arrow. This is **L-4's shape** — an AC/scope statement
contradicted by an interface the same brief specifies.

**What I did.** Followed the DDL, which the brief states is validated against a
live PostgreSQL 16 (L-3) and which is internally coherent: a scheme failure that
sends the money back **is** a return, and an outcome nobody knows is
`timed-out`, which must *not* become `failed` — the brief itself is emphatic
about that. So settlement drives `settle` and `return`, and `fail` stays
undriven.

`payment.failed` **is** in the vocabulary, because scope item 9 names it
explicitly and the OpenAPI enum must match. It is emitted by nothing. I judged
publishing an unused term less bad than silently dropping one the brief listed —
a caller filtering by it gets an empty answer, which is true.

**Requested ruling**, one of:

1. Confirm the DDL governs; `payment.failed` stays as a reserved term (and,
   optionally, is dropped from the vocabulary until something drives it).
2. Add `failed` to `settlement_outcome_known` and to the response kinds in a
   follow-up migration, and I will drive the arrow.

I have **not** diverged from the DDL to make option 2 true, because that would
be changing pre-validated schema on my own authority. The gap is named in
`DOMAIN_MODEL.md` §3, in `ARCHITECTURE.md` §5.3, and in a column comment on
`settlement_batch_item.outcome`, each pointing here.

### O-2 — AC-2's "differing in scheme" is not testable, because an instruction has no scheme

AC-2 requires that instructions *"differing in scheme, currency **or** value
date"* be refused. A payment instruction carries no scheme attribute — not in
`payment_instruction`, not in `clofin.payments.instruction`, and this brief adds
none. Two instructions cannot differ in a field neither has.

**What I did.** Treated the scheme as what it actually is: an **operator's
routing choice**, made when the batch is constructed, and constrained to
`SIM-RTGS | SIM-ACH`. The batch is defined by the triple, so "one batch, one
scheme" holds trivially; the *testable* halves of AC-2 — currency and value date
— are asserted at both the pure and the API level, and an unknown or real scheme
name is refused (`400`) with a test naming SWIFT and SEPA.

`clofin.settlement.batch/group-by-key` therefore groups on `[currency
value-date]` and documents why the scheme is absent from the key.

**Requested ruling:** confirm the reading, or say where an instruction's scheme
should come from. If instructions are to carry one, that is a column, a wire
field and a routing rule — a brief, not a footnote.

### O-3 — **process finding: TASK-005's remediation never merged, and `main` carries a contract defect this task walks straight into**

PR #6 was merged at head `095440d` on 2026-08-04 at 14:04. The remediation
commit `a2e85cb` — which fixed the defects TASK-005's own self-review found —
was pushed at 14:14, **ten minutes after the merge**, and is not in `main`.
Verified: `git merge-base --is-ancestor a2e85cb origin/main` fails, and
`git show origin/main:api/openapi.yaml` still carries
`EvidencePack.subjectType: enum: [payment-instruction, approval]`.

So `main` today contains:

- a **false published contract** — `GET /audit/evidence/{id}` returns
  `subjectType` values of `organisation`, `account` and `journal-entry` that the
  `EvidencePack` schema declares impossible;
- a **drift guard that cannot catch it** — `clofin.contract-test` checked only
  `AuditEvent.subjectType`, the copy its author happened to look at;
- four documentation claims that were corrected and then lost (a miscited
  F-001, an over-broad "the service decides", a false "a `400` never opens a
  transaction", and C-05's statement claiming attribution the bootstrap has not).

This is **directly in TASK-004's path.** The brief instructs that every term
added to `subject-types` must also be added to *"`api/openapi.yaml`'s matching
enums"* — and `EvidencePack.subjectType` is one of the matching enums. Adding
`settlement-batch` to only the copy the guard checks would have shipped a
contract wrong about two increments' worth of subjects, with a test certifying
it.

**What I did.** Carried `a2e85cb` forward onto this branch as its first commit,
minus its edit to `005-REQ` (this task must not touch `docs/audits/` beyond its
own REQ, so the §8 self-review section stays absent — Master Control's copy on
`meta` also lacks it). The widened guard now discovers **every** `subjectType`
enum in the spec, so `settlement-batch` landed in both.

**Requested ruling / action:** none needed for this branch, but the process is
worth a look — a Worker's post-merge fix commit is invisible to everyone unless
someone re-reads the branch. TASK-005's §8 findings are also absent from the
copy of `005-REQ` on `meta` and would be worth ingesting from PR #6's comment
thread, where they were posted.

---

## 5. Observations, decided and recorded

**N-1 — a `timeout-resolution`'s claimed outcome is not stored on the response
row.** `scheme_response` has no `outcome` column and the brief's DDL is
validated, so I did not add one. The outcome travels in the request and is
written to `settlement_batch_item.outcome`, which is its system of record; the
response row proves *that* a resolution with that reference arrived, not *what*
it claimed. Two columns holding one fact are two columns that can disagree — but
"stored verbatim" is now slightly less than literal for this one kind. A nullable
column would close it.

**N-2 — the timeout horizon is measured from `settlement_batch.created_at`**,
because the schema records no `submitted_at`. A batch that sat open for a day
before submission looks a day more overdue than it is. Applied in SQL rather than
against a clock read in the application, so two concurrent sweeps cannot
disagree. The precise fix is a column.

**N-3 — reads are gated by `:payment/read`, not a new `:settlement/read`.** The
brief names only `:settlement/execute`. A settlement batch is a fact about
payments, so an auditor who may read the payments may read how they settled;
inventing a second permission the brief did not ask for seemed worse than
reusing one that already means the right thing. Mutations are all
`:settlement/execute`.

**N-4 — `DOMAIN_MODEL.md` §4's settlement row was the *return* posting.** It
showed debit `1100-CLIENT-FUNDS` / credit `1300-IN-TRANSIT`, which puts the money
back in the pool while leaving the client's claim intact — that is a return. It
carried an asterisk explicitly deferring the pair to "the increment that has a
scheme adapter", so being specific was mine to do. Corrected, with a Return row
added, and the reasoning is in ADR-0018.

**N-5 — `clofin.db.core/tolerating-violation` is new.** See §2. It is the
smallest correct expression of a pattern the duplicate-response path needs, and
it is in `db.core` rather than in the settlement repository because the next
caller who needs it will not be a settlement caller.

**N-6 — settlement endpoints do not require an `Idempotency-Key`.** The brief
does not ask for one, and each operation is already protected by something
stronger and more specific: creation by the live-membership index, submission by
the batch row lock plus the `open`-only check, responses by the replay key, and
the sweep by being naturally idempotent. Adding the header would be new contract
surface.

**N-7 — no `settlement-item` audit subject type.** An item is keyed
`(batch, instruction)` and has no identity of its own; inventing a surrogate id
purely to fill `subject_id` would put a synthetic key in the column an auditor
joins on. Item-level facts are recorded against the *instruction*
(`payment.settled`, `payment.returned`) and batch-level ones against the batch.

---

## 6. Debt knowingly left

| Debt | Why |
|---|---|
| **No `submitted_at` on `settlement_batch`** | N-2. A one-column migration; it changes what "overdue" means, which is a product question worth asking before answering |
| **No fee posting on release** | `release-entries` supports a fee and nothing supplies one. Fee assessment has no driver in any increment yet |
| **A batch cannot be cancelled or emptied** | An `open` batch is created and then submitted; there is no way to remove a member or abandon it, so a mis-constructed batch strands its instructions until it is submitted. Not in the brief; a real operator would want it |
| **Batch status can move off a terminal value** | A late `timeout-resolution` can take a `failed` batch to `partially-settled`. That is honest — new information changed the summary — but it means "failed" is not final, and no audit event marks the second change beyond the payment's own |
| **`payment.failed` is a published action nothing emits** | O-1 |
| **Runtime role split** | Unchanged, still named in COMPLIANCE §4. The new `scheme_response` triggers inherit exactly the same owner-adversary limit |

---

## 7. Notes for the next session

**Reconciliation (increment 6) consumes this.** The things it will want:
`settlement_batch.scheme` records which simulated network carried each payment;
`1300-IN-TRANSIT`'s balance is the clearing exposure at any instant; and
`scheme_response` holds every message verbatim, in arrival order, including the
duplicates. `1200-NOSTRO` is deliberately untouched by settlement — the nostro
moves when a bank statement says it moved, which is your increment.

**If you add a settlement operation, take the locks in the documented order**:
batch, then instructions `order by id`, then accounts. It is in
`clofin.settlement.repository`'s docstring and it is invisible at any single call
site.

**If you add an audit action, four things follow it now**: the `AuditAction` enum
in `api/openapi.yaml`; a subject type whose name is the action's prefix; an actor
unless it is a bootstrap action; and — if it is named after a transition — an
emission point in the transaction where that transition commits, and nowhere
else. `clofin.contract-test` will fail the build for the first two.

**The simulated scheme's rule is documented in three places** —
`clofin.settlement.scheme`, the OpenAPI description of `recordSchemeResponse`,
and UAT-006 — because a simulation whose behaviour is only discoverable from
source is one nobody can review. If you change the rule, change all three; there
is no test asserting they agree, which is itself a small piece of debt.

---

# 8. Remediation addendum — FEEDBACK-M2 findings F-007 … F-011

| Field | Value |
|---|---|
| **Provenance** | `claude-opus-5`, reasoning effort **high**, session date **2026-08-05** (per the provenance rule adopted 2026-08-04 in `docs/audits/README.md`) |
| **Occasion** | Milestone 2 external audit returned **REMEDIATION-REQUIRED**; brief 004 reopened `IN PROGRESS`. Rulings in TASK-004's FEEDBACK-M2 changelog on `origin/meta` |
| **Base** | `main` at `cba31c5`. **A fresh branch**, per the ruling — PR #7 is merged history and is never reused |
| **Branch** | `claude/task-004-settlement-remediation-oi8ke4` — **substituted for the brief's `feat/settlement-remediation`**; the execution environment designates the push branch and this session cannot create another. Noted per instruction; no other divergence |
| **Migration** | `0010-settlement-remediation.sql` — next available **verified against the live tree** at dispatch time, not assumed (L-1) |
| **ADR** | `0019-a-returned-payment-is-terminal-and-retries-as-a-new-instruction.md` — next available, verified |
| **Scope** | Five findings in one batch, plus the two doc-sweep items the ruling folded in |
| **Objections** | **None.** All five rulings are implemented as ruled. Two implementation choices that go slightly beyond the letter of a ruling are declared in §8.7 for arbitration rather than left to be discovered |
| **Status** | Implemented. `make verify` and `make test-it` green — counts in §8.8 |

## 8.1 F-007 (blocking) — a returned instruction is terminal

**Ruled:** `returned` is terminal; a retry is a new instruction. The index
tightens to full uniqueness over `instruction_id`; the audit's raw insert becomes
a refusal test; a public batch attempt naming a returned instruction is refused
with a reason naming the terminal state and the new-instruction path; DOMAIN_MODEL,
the index rationale and UAT-006 align. **No linked retry operation** — that is
increment 6's.

**Done.** Migration `0010` drops `settlement_item_live_key` and creates
`settlement_item_instruction_key` — full uniqueness, no `where` clause. Every
outcome now blocks a second membership.

- **The audit's own reproduction is now a refusal test.** Verification log C-01
  inserted a second membership for a returned instruction and it committed,
  leaving count `2`.
  `clofin.settlement.repository-test/ac-7-the-database-itself-refuses-a-second-membership-whatever-the-outcome`
  runs that exact insert for all four outcomes — pending, settled, timed-out and
  **returned** — and asserts the refusal and that count stays `1`.
- **And from the public command, which is what L-10 asks for.**
  `clofin.api.settlement-api-test/f-007-a-returned-instruction-is-refused-a-second-batch-by-name`
  drives an instruction through release and return via the API, asserts the
  retry is `422 not-approved`, asserts the raw insert is *also* refused — the
  two layers now agreeing rather than contradicting — and then raises a **new**
  instruction, approves it, batches it and settles it, proving the deferral has
  a working alternative rather than being a dead end.
- **The refusal names the correction.** `add-items!` translates the violation
  into a `409` saying the payment is terminal and that the retry is a new
  instruction, with `:retry "raise-a-new-instruction"` in the error data. A
  refusal an operator cannot act on becomes a request to disable the check.
- **Aligned:** `DOMAIN_MODEL.md` §2.3 and §3 rule 5 (which was still `📋
  (Increment 5)`), `ARCHITECTURE.md` §5.6, the index comment in `0010`, the
  OpenAPI descriptions for the Settlement tag, `createSettlementBatch`,
  `sweepSettlementTimeouts` and `SettlementBatchItem.outcome`, and UAT-006
  steps 8b and 8c.
- **ADR-0019** records the decision, the two designs, why (b) won — the deciding
  argument being that a second attempt is a second *payment decision* and
  therefore deserves a second maker–checker cycle — and what it costs.
- **No linked retry was built**, per the ruling.

## 8.2 F-008 — receipt and disposition are separate facts (L-11)

**Ruled:** every scheme response commits an immutable receipt plus a
machine-readable disposition; a processing conflict renders its `409` **after**
the receipt commits; replaying a rejected receipt reproduces its original no-work
answer, never a re-evaluation against later state. Tests: premature
timeout-resolution and late contradiction, both asserting the row survives,
effect-free.

**Done.** `scheme_response` gains `disposition` (`applied` | `acknowledged` |
`refused`) and `disposition_reason`, with a check constraint making the two agree
in both directions. `record-scheme-response!` **returns** a refusal instead of
throwing one; `clofin.api.settlement/record-response` renders the `409` after
`with-transaction` has returned, so the receipt is durable before the error
exists.

- The service's ordering changed to make this possible: it now reads the
  existing receipt **before** attempting work, rather than discovering a
  duplicate by colliding with it. That is also what makes replay *reproduce* an
  answer rather than re-derive one.
- `f-008-a-premature-timeout-resolution-is-kept-and-does-no-work` is C-02's
  reproduction: the premature response is `409`, the row is present with
  `disposition = refused`, `disposition_reason = item-not-timed-out`, no
  outcome, and nothing posted or audited. Then a sweep runs, and **the identical
  reference is delivered again** — the exact step that previously settled the
  payment. It is `409` again, with `replayed: true`, no posting, no event, and
  the payment still `released`. A *new* reference still resolves the item, so
  the item is not stranded.
- `f-009-a-contradiction-under-one-reference-is-a-conflict-not-a-replay` is the
  late-contradiction half; it asserts effect-freeness the same way.
- `f-008-a-refused-receipt-is-visible-on-the-batch-as-evidence` asserts the
  refused arrival is readable through `GET /settlement-batches/{id}` — an
  evidence table nobody can read completely answers an auditor with "probably".

**Where the line was drawn, and why.** A request too malformed to *be* a scheme
response — a `settled` naming no instruction, a `timeout-resolution` naming no
outcome — still throws, and earns no receipt. It is `400`, and the receipt table
stays a record of deliveries rather than of typos. The ruling's language is
"a **processing** conflict", and that is exactly the case that now commits.
`clofin.settlement.response/assert-shape!` holds the distinction and states it.

## 8.3 F-009 — replay identity covers every effect-bearing field (L-12)

**Ruled:** canonical digest over the complete semantic request (kind, reference,
outcome, reason), stored with the original response; exact duplicates return the
original body, `outcome` included; same reference with a different digest is
`409` and is never called a replay. Reuse the idempotency canonicalisation
posture (L-2 / ADR-0013), and mind the digest-version coupling note in 003-REQ §7.

**Done.** `scheme_response` gains `request_digest`, `outcome` and `reason`. The
new pure namespace `clofin.settlement.response` holds the semantic request, its
digest and the replay decision — the same split `clofin.idempotency` has beside
`clofin.idempotency.repository`, and it is registered in
`clofin.ledger.purity-test/pure-namespaces`.

- **The canonicaliser is reused, not reimplemented.** `response/digest` calls
  `clofin.audit/digest`, which normalises domain values and then calls
  `clofin.idempotency/canonical`. One canonical form, one place where "the same
  request" is defined, and the digest carries
  `clofin.audit/canonicalisation-version`.
- **On the digest-version coupling (003-REQ §7):** `clofin.idempotency/canonical`
  is **unchanged by this batch**, so `canonicalisation-version` is deliberately
  **not** bumped — bumping it would make every existing `audit_event` digest
  incomparable to every later one for no reason. The coupling now binds a third
  consumer, and that is stated in `response`'s docstring so the next person to
  change `canonical` finds it.
- **The digest covers the resource, not only the body.** It includes `batch-id`
  and `instruction-id` alongside kind, reference, outcome and reason. The ruling
  named the four fields that were missing; L-2's rule is that a canonical digest
  covers the resource too, and including them costs nothing since a shared replay
  key already implies a shared address. Declared in §8.7 rather than assumed.
- **`outcome: nil` on a replay is gone.** The duplicate now returns the original
  body; the test asserts the two bodies are byte-equal after excluding
  `schemeResponses` and the intentional `replayed` flag — the same comparison the
  auditor made when they found them differing.
- **A contradiction is `409` and is not called a replay.** `replayed` is `false`
  in that response's error data, and `dispositionReason` is
  `replay-key-conflict`.
- **Blank and absent reasons are one claim.** `normalise-reason` trims to nil, so
  `""` and `null` digest alike — otherwise a caller would get a `409` it could
  not act on.

## 8.4 F-010 — `scheme_response` joins the L-5 matrix

**Ruled:** full table × verb raw-SQL coverage with a committed row; run a
one-time negative control proving that removing either trigger fails the suite,
and say so in this REQ.

**Done.** `scheme_response` is in `guarded-tables` in
`clofin.db.audit-constraints-test` with `[:update :delete :truncate]`,
`seeded-row!` commits a real receipt for the row-level triggers to fire on, and
the armed-guard drift assertion now expects **12** triggers over five tables
rather than 10 over four.

**Negative control, run once, in disposable migrated databases:**

| Removed | Result |
|---|---|
| `scheme_response_append_only` | `clofin.db.audit-constraints-test` + `clofin.settlement.repository-test`: **3 failures, 4 errors** (was 33 tests / 120 assertions **green** for the auditor) |
| `scheme_response_no_truncate` | The fixture's own drift guard throws before any test runs: *"TRUNCATE guards have drifted from `append-only-tables`"* |
| `scheme_response_no_truncate`, with the fixture drift guard deliberately silenced | The matrix alone fails: **4 failures, 6 errors**, on `scheme_response must refuse truncate` |

The third row is there because the second is the *fixture* catching it, not the
matrix. The auditor's point was that the matrix should catch it, so the matrix
was tested in isolation. It does.

**One thing found while doing this, fixed in passing.** `refuses` was a **set**,
so verb order was unspecified. On a healthy schema that is invisible — every verb
is refused and the row survives all three. On the broken schema of the negative
control, `TRUNCATE` ran first, succeeded, emptied the table, and `UPDATE` and
`DELETE` then matched nothing: the suite would have reported one broken guard
where three were. It is now an ordered vector with `truncate` last.

## 8.5 F-011 — the transaction precondition fails closed (L-13)

**Ruled:** every audit-composing service asserts a non-autocommit transaction
before its first write; direct-pool and autocommit negative tests for each; the
no-`clofin.db.*` purity rule stays.

**Done.** `clofin.db.core/assert-transaction!` is the one runtime check;
`clofin.audit.repository/assert-unit-of-work!` re-exports it for services.

- **It lives in the audit repository** because a service may not require
  `clofin.db.*` (ADR-0012) and every audit-composing service already requires
  that namespace — so the check reaches all of them without any of them
  acquiring a connection. The purity rule is untouched and still passes.
- **Called at entry, before the first write**, in all nine entry points across
  `clofin.organisations.service`, `clofin.ledger.service`,
  `clofin.payments.approval-service` and `clofin.settlement.service`. Entry
  rather than repository is the whole point: `create-account!` reached its
  repository only *after* the row was durable, and a precondition that fires
  after the first write is not a precondition.
- `clofin.db.core/transactionally` now calls the same function, so the F-004
  guard and this one cannot drift. Its error class and message are unchanged, so
  `clofin.ledger.repository-test`'s existing assertion still holds.
- **`clofin.audit.unit-of-work-test` is a matrix, not two tests.** Every entry
  point × {pool, autocommit connection}, each asserting **both** the refusal and
  that **nothing was written** — the second is what distinguishes a precondition
  from an error message, and it is the audit's `account 1 / event 0` reproduction
  turned into an assertion. A positive case asserts the guard admits a real
  transaction, and a fourth test asserts the two failure messages differ, because
  a pool and an autocommit connection are different mistakes.
- **The set is compared against `clofin.ledger.purity-test/service-namespaces`**,
  so a service added to one and not the other fails the build. That is L-6's
  rule applied to this guard rather than trusted.

**Negative control on this one too:** removing the assertion from
`ledger.service/create-account!` produces 4 failures, including *"create-account!
wrote something before refusing"* — the auditor's exact finding, now detected.

**`clofin.payments.approval-service` was included** although the ruling's prose
names "organisations, ledger, settlement". The finding says *every*
audit-composing service, and the approval service is one. Declared in §8.7.

## 8.6 The doc sweep the ruling folded in

- **`payment.failed`** — the `AuditAction` enum description in
  `api/openapi.yaml` now states that it is **reserved and has no producer**, why
  (a scheme failure that returns funds *is* a return; an unknown outcome is a
  timeout), and why it is published rather than omitted. Stated explicitly so an
  API-only consumer can distinguish "no payments have failed" from "this action
  has no producer", which are very different answers to give an auditor.
- **COMPLIANCE C-05** gains *"The boundary of 'every state change'"*: a late
  `timeout-resolution` moves an already-terminal batch's derived status and
  writes no batch-subject event, because `settlement-batch.completed` names the
  transition *into* completeness and that happened earlier. Every item resolution
  that moved it has its own event, so the change is auditable through the items;
  what is absent is an event whose subject is the batch. Recorded debt, now
  visible where the control is read, with the fix (a distinct vocabulary term)
  named and placed in increment 6.
- C-05 also records the F-011 enforcement point and the `scheme_response`
  triggers; C-06 gains `scheme_response_replay_key` + `request_digest` and
  `settlement_item_instruction_key` as enforcement points, with their tests.

## 8.7 Declared beyond the letter of a ruling — for arbitration

No objection to any ruling. Two choices go slightly further than the ruling's
words, and are declared rather than left to be found:

1. **The index was renamed, not only redefined** —
   `settlement_item_live_key` → `settlement_item_instruction_key`. The ruling
   said "replaces … with full uniqueness over `instruction_id`". "Live" named a
   live/dead distinction that no longer exists, and the name appears in the error
   an operator reads and in every raw-SQL test. A constraint whose name asserts a
   rule it does not implement is the shape of defect this batch exists to remove.
   Reverting to the old name is a one-line change to `0010` if Master Control
   prefers minimal churn.
2. **The digest covers `batch_id` and `instruction_id`** as well as the four
   fields the ruling named. This is L-2's "canonical digests include method and
   path" applied to a resource address. It is strictly stronger and functionally
   inert — two deliveries sharing a replay key necessarily share both — so it
   changes no behaviour, only what the digest proves in isolation.

Also declared, as scope rather than divergence: `clofin.payments.approval-service`
received the F-011 assertion (§8.5), and the verb-order fix in the L-5 matrix
(§8.4) was not asked for.

## 8.8 Verification

Run against a local PostgreSQL 16 with migrations `0001`–`0010` applied, per the
environment note in 003-REQ.

| Suite | Baseline at `cba31c5` | After |
|---|---|---|
| `make verify` (unit, property, contract) | 272 tests / 1,502 assertions | **272 tests / 1,504 assertions**, 0 failures, 0 errors |
| `make test-it` (with integration) | 567 tests / 3,429 assertions | **584 tests / 3,638–3,659 assertions**, 0 failures, 0 errors |
| `make docs-check` | — | 46 markdown files, all links resolve |

The generated settlement property varies its assertion count with vector length,
as recorded in §5, so the integration assertion count is a **range across runs**
rather than a number: 3,638 and 3,659 were both observed after this batch, both
green. The same effect explains why the baseline this session measured on
unmodified `cba31c5` (567 tests / 3,429 assertions) differs from the audit's
(567 / 3,459) — same tests, same result, different generated vector lengths. The
**test count** is the stable figure: 567 → 584, the seventeen new tests being
F-011's five, F-007's two, F-008/F-009's six, and four new receipt-schema tests.

**Migration `0010` was validated against a live PostgreSQL 16 before it was
written to disk (L-3)**, applied from an **empty** schema through `0001`–`0010`
in a disposable database. Every documented row shape inserts: an `applied`
receipt with an outcome, a `refused` receipt with a reason, an `acknowledged`
ack, and a `timeout-resolution` carrying outcome and reason. Every guard refuses:
a refusal with no reason, a non-refusal carrying one, an unknown disposition, an
unknown response outcome, the replay key under `nulls not distinct`, all three
destructive verbs, and a second membership for each of the four item outcomes.

**Nothing is in flight (L-9).** Both suites were run to completion after the last
edit; the negative controls in §8.4 and §8.5 were run and their databases
destroyed; no verification is outstanding, and no fix is expected to follow this
report.

## 8.9 Notes for whoever picks this up

- **`clofin.settlement.response` is where a response's identity lives.** If you
  add a field that can change what a response *does*, add it to
  `semantic-request` in the same commit, or two different messages will share one
  digest — which is F-009 again with a new field.
- **`assert-unit-of-work!` goes first in a new audit-composing service**, and the
  service goes into **both** `clofin.ledger.purity-test/service-namespaces` and
  `clofin.audit.unit-of-work-test/audit-composing-calls`. The two are compared,
  so adding it to one alone fails the build rather than passing quietly.
- **A new append-only table goes into three places**: its triggers in the
  migration, `guarded-tables` in `clofin.db.audit-constraints-test`, and
  `append-only-tables` in `clofin.test-db`. F-010 was the second of those being
  missed.
- **Increment 6 inherits linked-retry provenance** as scoped work: a returned
  payment's retry is a new instruction with nothing in the record relating the
  two. ADR-0019 says why the gap exists and what closing it needs.
- **The late-status boundary in C-05 is still open debt.** The fix is a
  vocabulary term for "a completed batch's derived status changed", which is
  audit-vocabulary design and belongs with reconciliation rather than being
  invented in a remediation batch.
