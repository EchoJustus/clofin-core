# 002-REQ — Payment instruction lifecycle and idempotency

| Field | Value |
|---|---|
| **Brief** | TASK-002 (increment 3), read from `origin/meta` |
| **Branch** | `feat/payment-instruction-lifecycle`, based on `origin/main` at `7024454` |
| **Requirements** | PR-001, PR-002, PR-003, PR-004, PR-040, PR-041, PR-042, PR-043 |
| **Controls** | C-06 — moved 📋 → ✅ |
| **Submitted** | 2026-08-03 |
| **Status** | Complete. Awaiting audit, and a ruling on the objections in §5. |

> **Meta copy.** Ingested by Master Control on 2026-08-03. References to
> ADR-0013, ADR-0014 and UAT-004 resolve on the submission branch
> `feat/payment-instruction-lifecycle` (PR #4), not on `meta` — those artefacts
> travel with the code and reach `main` when the PR merges.

This is the Worker's completion report and audit request for TASK-002, filed on
its own feature branch per
[`../AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §1b. Objections to the brief are in
§5 and were **not** resolved unilaterally.

---

## 1. Test results

Actual numbers, from the branch tip.

| Command | Before (base `7024454`) | After | Delta |
|---|---|---|---|
| `make test` | 90 tests / 385 assertions | **158 tests / 900 assertions** | +68 / +515 |
| `make test-it` | 158 tests / 757 assertions | **275 tests / 1547 assertions** | +117 / +790 |

**0 failures, 0 errors** in both. `make verify` (`test` + `docs-check`) passes.
The base was verified green before any code was written, as §2 of the working
agreement requires.

New test namespaces, all registered in `clofin.test-runner`:

| Namespace | Tests | Assertions | |
|---|---|---|---|
| `clofin.payments.state-test` | 17 | 285 | unit |
| `clofin.payments.instruction-test` | 22 | 104 | unit |
| `clofin.payments.posting-test` | 10 | 37 | unit |
| `clofin.idempotency-test` | 19 | 58 | unit, incl. 2 property tests |
| `clofin.payments.repository-test` | 23 | 59 | integration |
| `clofin.api.payments-api-test` | 26 | 214 | integration |

`clofin.contract-test` passes **unmodified** (AC-12).

### The two tests the brief singled out

**AC-10 is table-driven and exhaustive.** `state-test` walks all
9 states × 9 events = 81 pairs. The ten pairs in `transitions` must reach their
declared state; the other seventy-one must raise `:conflict`. A companion
assertion pins the shape of the table (9/9/10), so a table that had silently
lost entries could not make the enumeration pass by being empty. A further test
asserts every destination is itself a key of the table.

**AC-9 is a genuine concurrency test** — two threads, a `CountDownLatch`, one
shared key, both going through the fully-wrapped handler. It asserts exactly one
`payment_instruction` row afterwards, byte-identical bodies for both callers,
and that exactly one of the two responses carries `idempotent-replayed`.

I verified the race is real rather than incidentally passing, by instrumenting
the unique-violation branch and running the suite:

- With the fast-path read in `execute-once!` **disabled entirely**, all 26
  API tests still pass — so the primary key is the guarantee, exactly as the
  namespace docstring claims, and not the read.
- With the fast path restored, the unique-violation branch is entered **exactly
  once per run** — by the AC-9 test. Every sequential replay takes the fast
  path. The two threads genuinely contend.

The instrumentation was temporary and is not in the diff.

---

## 2. Acceptance criteria

| # | Covered by |
|---|---|
| AC-1 | `ac-1-a-valid-instruction-is-created-as-a-draft` — 201, `draft`, `Location` resolves |
| AC-2 | `ac-2-three-invalid-fields-are-all-named`; also `ac-2-...` in `instruction-test`, and `an-entirely-empty-candidate-names-every-required-field` |
| AC-3 | `ac-3-a-draft-can-be-amended`, `ac-3-a-submitted-instruction-cannot-be-amended` |
| AC-4 | `ac-4-submitting-a-draft-reaches-pending-approval` (incl. asserting no approve endpoint exists) |
| AC-5 | `ac-5-a-settled-instruction-refuses-every-transition-by-name`, `ac-5-a-refused-transition-names-what-was-attempted` |
| AC-6 | `ac-6-a-replayed-key-...`, `...-differs-only-in-representation-...`, `...-covers-every-mutating-operation-...` |
| AC-7 | `ac-7-the-same-key-with-a-different-body-is-a-conflict` |
| AC-8 | `ac-8-a-mutating-request-without-a-key-is-rejected` — all four mutating endpoints |
| AC-9 | `ac-9-two-concurrent-requests-with-one-key-produce-exactly-one-effect`; plus `ac-9-concurrent-submissions-...-under-different-keys` for the `FOR UPDATE` path |
| AC-10 | `ac-10-every-state-event-pair-behaves-as-the-table-declares` |
| AC-11 | `ac-11-a-settled-instruction-is-reversed-by-a-new-one`, `ac-11-only-a-settled-instruction-can-be-reversed` |
| AC-12 | `clofin.contract-test`, unmodified |

## 3. Definition of done

| | |
|---|---|
| ✅ | Every acceptance criterion has a named test |
| ✅ | AC-10 table-driven exhaustive |
| ✅ | AC-9 a genuine concurrency test with a latch |
| ✅ | `api/openapi.yaml` updated in the same commit as the handlers |
| ✅ | `make verify` and `make test-it` green |
| ✅ | New test namespaces in `clofin.test-runner` |
| ✅ | `DOMAIN_MODEL.md` I10 marked ✅ |
| ✅ | `COMPLIANCE.md` C-06 📋 → ✅, enforcement points named |
| ✅ | ADR for the canonical-digest decision — `ADR-0013` |
| ✅ | UAT script — `UAT-004`, incl. a manual `curl` double-submit *(numbered 004, not 003 — objection O-1)* |
| ✅ | This report filed; PR opened against `main` |
| ⚠️ | "Note PR-005 (batch) in ROADMAP" — **I cannot do this.** `ROADMAP.md` lives on `meta` and Workers never write `meta`. Recorded here for Master Control instead; see O-6. |

---

## 4. What was built

**Migration `0003-payment-instructions.sql`.** The brief's SQL **verbatim** —
every column, type, constraint and the composite primary key exactly as
specified. Added: a header comment block and `comment on` statements, matching
the style of `0001`/`0002`. `0001` and `0002` are untouched; `index.txt` gained
one line.

**Pure namespaces** (all four added to `clofin.ledger.purity-test`'s guard):

- `clofin.payments.state` — `transitions` plus `terminal?`, `permitted?`,
  `permitted-events`, `transition`, and the two non-transition rule sets
  `mutable-states` / `reversible-states`.
- `clofin.payments.instruction` — `field-errors` returns a map so PR-003's
  "every failed field" is structural rather than a promise; `instruction`,
  `draft` and `amend` are built on it, so validator and constructor cannot
  disagree.
- `clofin.payments.posting` — release and fee templates against the
  `DOMAIN_MODEL.md` §4 chart, tested with the worked SGD 1,250.00 + 5.00 fee
  example as the brief directed.
- `clofin.idempotency` — `canonical`, `digest`, `read-key`,
  `assert-same-request!`.

**Persistence** (the seam ADR-0012 names):

- `clofin.payments.repository` — `transition!` reads its row
  `SELECT … FOR UPDATE` inside a transaction, so two concurrent submissions
  cannot both succeed. `amend!` locks likewise.
- `clofin.idempotency.repository` — `execute-once!` claims the key **before**
  running the effect and completes the row in the same transaction, so a
  concurrent duplicate blocks on the primary key and never performs the work at
  all, rather than performing it and relying on rollback.

**API** — six routes and six OpenAPI operations, plus an `Idempotency-Key`
header parameter, `IdempotentReplay` / `IdempotencyConflict` responses, an
`Idempotent-Replayed` response header and six schemas.

**Shared code touched** (small, and each has a reason):

| Change | Why |
|---|---|
| `clofin.error/error-types` gains `:field-validation` (422, `problem-type :validation`) | The brief's `422` example carries the `validation` problem type, which no existing category produces. See ADR-0014 and O-8. |
| `clofin.http.response/error->problem` honours `:problem-type` | Three lines, so a category can report under a type other than its own name. |
| `clofin.db.core` gains `->local-date` and `transactionally` | A `date` column arrives as `java.sql.Date`; converting at the seam stops a value date drifting a day under a non-UTC default zone. |
| `clofin.api.wire` gains `read-local-date`, `instruction->wire` | Wire naming stays in one namespace. |
| `clofin.test-db/clean-business-data!` names the two new tables | They were already reached by `cascade`; naming them means a test never depends on that implication. |

**Deliberately not built**, each with a marker in the code: approval and
maker–checker (TASK-003 — there is no approve endpoint, and a test asserts
`POST …/approval` is a 404); the audit trail (TASK-003 — none, rather than a
partial one that looks complete); settlement (increment 5); screening
(`TODO(increment-7)` at the `submit` precondition); authentication
(`TODO(TASK-003)` on every read of `createdBy`); batch submission, PR-005.

---

## 5. Objections and decisions requiring a ruling

Per AGENT_HANDOFF §1b these are recorded rather than resolved unilaterally. Two
are interpretations I had to act on to deliver at all; they are marked as such.

### O-1 — `blocking to the DoD as written` · UAT numbering collides

The brief's definition of done names `docs/uat/UAT-003-idempotent-submission.md`.
**UAT-003 already exists**: `UAT-003-account-statement-production.md`, delivered
by TASK-001 and merged in PR #2, and referenced from `ROADMAP.md`. UAT numbers
are sequential and never reused.

**Action taken:** filed as `UAT-004-idempotent-submission.md`, with the reason
stated in the document itself. Two documents numbered UAT-003 would have been
the only alternative.

**Ruling requested:** confirm UAT-004, or name a different number.

### O-2 — `interpretation, acted on` · AC-3 contradicts the `:amend` transition

AC-3 requires that an instruction "submitted then amended" returns `409`. The
transition table the brief specifies carries `:amend` on `pending-approval`,
leading back to `:draft`. If `PATCH /payment-instructions/{id}` drove the
`:amend` event, patching a submitted instruction would **succeed**, not `409`.
The two cannot both hold.

**Resolution taken:** `DOMAIN_MODEL.md` §3 rule 3 says the `:amend` transition
invalidates every approval given so far, traced to **PR-014** — which is
TASK-003's requirement range, not this brief's. So the `:amend` *event* belongs
to the approval workflow, and the `PATCH` endpoint delivered here is a different
operation: in-place editing of a draft, which changes no status and therefore
moves along no arrow. It is governed by `state/mutable-states` (`#{:draft}`),
derived from `DOMAIN_MODEL.md` §1's "mutable while `draft`, immutable in
substance thereafter".

This satisfies AC-3 in both halves and leaves the brief's transition table
**unmodified**, so AC-10 enumerates exactly what was specified. Recorded in
`ADR-0014`, which also records the
rejected alternatives.

**Ruling requested:** confirm the interpretation. If instead `PATCH` was meant to
drive `:amend`, then AC-3's second half needs rewording and TASK-003 inherits an
approval-invalidation gap.

### O-3 — `should-fix, held pending ruling` · The digest excludes method and path

The brief specifies `request_digest` as "SHA-256 of the canonical request body".
Implemented **exactly that**. The consequence:

> `POST /payment-instructions/{a}/submission` and
> `POST /payment-instructions/{b}/submission` carry identical bodies
> (`{"organisationId": "…"}`). One key replayed across both digests identically,
> so the second caller receives the first's stored `200` — and instruction `b`
> is never submitted, while the operator sees success.

This is a *silently unsubmitted* payment rather than a double payment, so it is
not the failure C-06 targets, and it requires the caller to misuse a key. It is
still wrong. The fix is one line in `clofin.api.payments/request-digest`: digest
the canonical document `{"method": …, "path": …, "body": …}` instead of the body
alone.

**Not applied**, because it diverges from an explicit interface specification and
"diverging from a brief without a ruling is a failed handover even when the
divergence was right". The limitation is disclosed in
`ADR-0013`, in
`COMPLIANCE.md` C-06 under *Not covered by this control*, and at the call site.

**Ruling requested:** apply the fix, or accept the boundary explicitly.

### O-4 — `consider` · The brief's schema contradicts `DOMAIN_MODEL.md` §2.2

`DOMAIN_MODEL.md` §2.2 listed `idempotency-key` as a **field on
PaymentInstruction** ("Unique per organisation"). The brief's SQL puts it in a
separate `idempotency_key` table with no such column on `payment_instruction`.

**The brief is right and the model was wrong**: a key on the instruction row
could make *creation* idempotent and nothing else, leaving submission
unprotected — the one operation whose timeout the control exists for.

**Action taken:** followed the brief, and updated `DOMAIN_MODEL.md` §2.2 to model
`IdempotencyKey` as a record of its own with the reasoning stated. Flagged
because it is a change to a shared model document, made on a feature branch.

### O-5 — `consider` · C-06's stated enforcement point named a constraint that does not exist

`COMPLIANCE.md` C-06 named "Unique constraint on
`(organisation_id, idempotency_key)`". The constraint actually built is
`idempotency_key_pkey` on `(organisation_id, key)`, on a different table.
Updated to name the real one, plus the three code-level enforcement points and
the tests. Same substance, accurate identifiers.

### O-6 — `informational` · PR-005 cannot be noted where the brief asks

The brief says batch submission is deferred and to "Note it in ROADMAP".
`ROADMAP.md` is control-plane, on `meta`, and Workers never write `meta`.
**Recorded here for Master Control to transcribe:** PR-005 (batch submission
with per-item outcomes, a *Should*) is not implemented; single-instruction
submission is.

### O-7 — `informational` · Interfaces the brief left unspecified

Decided rather than guessed, and each is cheap to change:

- **Reversal has no endpoint in the brief's HTTP table** despite AC-11 requiring
  one. Implemented as `reversesId` on `POST /payment-instructions` — a reversal
  *is* a new instruction (`DOMAIN_MODEL.md` §3 rule 4), so a separate endpoint
  would have been a second way to create one.
- **`submission` and `cancellation` take a JSON body** carrying
  `organisationId`. Required because the idempotency key is scoped to an
  organisation and there is no authenticated principal to derive one from.
- **`PATCH` rejects any member it cannot amend** (`422`, "cannot be amended")
  rather than ignoring it — a caller that sent `status` believes it changed
  something.
- **A replayed response carries `Idempotent-Replayed: true`.** Not in the brief;
  it makes a replay visible to a reviewer, and the UAT script uses it.
- **`valueDate` is bounded at 365 days ahead** as well as not-in-the-past. An
  instruction dated four centuries out would sit in `pending-approval`
  indistinguishable from work in progress.

### O-8 — `informational` · The `422` in the brief needed a new error category

The brief's validation example is `"type": ".../problems/validation"` with
`"status": 422`. `clofin.error` mapped `:validation` to **400**, and the only
422 category (`:unprocessable`) renders a different `type` and `title`. Added
`:field-validation` — 422, title `Request failed validation`, reporting under
the `validation` problem type — so the brief's example is produced verbatim
while `400` keeps its meaning for a request that could not be *understood*.
Reasoning and rejected alternatives in
`ADR-0014` §5.

---

## 6. Debt left knowingly

Named so the next session does not have to rediscover it.

| | |
|---|---|
| **No indexes on `payment_instruction`.** | The brief's SQL specifies none, and I implemented it verbatim. `list-instructions` filters on `organisation_id` unindexed, and the two foreign keys are unindexed as PostgreSQL does not create them. Consistent with the project's measure-before-optimising posture (ROADMAP, increment 2), but it is a real gap the moment a table has volume. |
| **An instruction can be reversed more than once.** | `journal_entry.reverses_id` carries a partial unique index (invariant I4); `payment_instruction.reverses_id` does not, because the brief's SQL does not specify one. **PR-044** (a partial refund may not exceed the *unreversed* amount) is a *Should* and is not implemented — there is no accumulation across reversals. A reversal is validated only as: target exists, in this organisation, `settled`, same currency. |
| **`PATCH` validates in two passes**, not one. | Unreadable members are reported first, then the rules over the amended whole — the stored instruction is only available under its lock, inside the transaction. PR-003's single-pass guarantee holds for creation, where every field arrives at once. `POST` is one pass. |
| **`transactionally` now exists twice.** | Added to `clofin.db.core` for the payments repository; `clofin.ledger.repository` keeps its identical private copy. Not consolidated because rewriting merged TASK-001 code is outside this brief. A two-line delegation would close it. |
| **A replayed response replays status and body only.** | The `Location` header on a replayed `201` is rebuilt from the stored document rather than stored, which is correct here because it is a pure function of the resource. Any future header that is *not* would need schema. |
| **No pagination.** | Same cap-and-flag approach as the ledger, same reasoning (ADR-0011). |

---

## 7. What the next session should pick up

TASK-003 (increment 4), which inherits directly from this:

- The `approve`, `reject` and `pending-approval → draft` `amend` transitions are
  in the table, tested, and driven by nothing. **Read
  `ADR-0014` before wiring `amend`** —
  `PATCH` deliberately does not drive it, and PR-014's approval-invalidation is
  the missing half.
- Every `TODO(TASK-003)` marks a place `createdBy` or `organisationId` becomes a
  principal: `clofin.api.wire/read-organisation-id`,
  `clofin.api.payments/create`, `clofin.payments.repository/amend!` (where
  PR-004's "by its creator" check belongs), and
  `clofin.payments.instruction/field-errors`.
- The audit trail (C-05, I9) must commit in the same transaction as the change
  it describes. `clofin.idempotency.repository/execute-once!` already
  establishes that transaction and hands the effect its connection, which is
  where an audit write belongs.
- `TODO(increment-7)` in `clofin.payments.repository/transition!` marks the
  screening gate.
