# TASK-002: Payment instruction lifecycle and idempotency

| Field | Value |
|---|---|
| **Increment** | 3 |
| **Status** | `READY` |
| **Depends on** | TASK-001 — needs `clofin.ledger.repository/post-entry!` |
| **Base branch** | `claude/ledger-persistence-account-api-p5oi05` — TASK-001 is implemented but unmerged (PR #2), so stack on its tip and open the PR against it, per AGENT_HANDOFF §1b |
| **Blocks** | TASK-003 |
| **Requirements** | PR-001…PR-005, PR-040…PR-044 |
| **Controls touched** | C-06 (duplicate payment prevention) |
| **Scope** | Large — split into two commits if it helps: (a) state machine + persistence, (b) API + idempotency |
| **Audit** | Not yet submitted |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

CloFin can record accounting facts but has no concept of *intent to pay*. After
this task, a caller can capture a payment instruction, submit it, cancel it, and
retry any of those calls safely — with the state machine expressed as data and
every mutating operation protected by an idempotency key.

**This is the increment that addresses the highest-consequence failure in the
problem statement:** a timeout on submission leaves an operator unsure whether
the payment went out, and resubmitting pays twice.

## Context you need

| Source | What it gives you |
|---|---|
| [PRD §5.1, §5.5](../PRD.md) | The requirements this implements, verbatim |
| [DOMAIN_MODEL §2.2, §3](../DOMAIN_MODEL.md) | `PaymentInstruction` fields and the lifecycle diagram, including the four rules the diagram does not carry |
| [COMPLIANCE C-06](../COMPLIANCE.md) | Why idempotency is a control, not a convenience |
| [ADR-0009](../ADR/0009-forward-only-sql-migrations.md) | Migration rules — append only, never edit `0001`–`0002` |
| [ADR-0008](../ADR/0008-double-entry-journal-as-source-of-truth.md) | An instruction is *not* a journal entry; one instruction produces several entries over its life |
| `src/clofin/error.clj` | `conflict!`, `invalid!`, `not-found!` and how they map to status codes |
| `src/clofin/ledger/entry.clj` | `transfer-lines` — the shape a posting template returns |
| TASK-001 output | `clofin.ledger.repository` — you post entries through it |

## Scope

### In

1. **`clofin.payments.state`** — the state machine **as data**, not conditionals.

   ```clojure
   (def transitions
     {:draft            {:submit :pending-approval, :cancel :cancelled}
      :pending-approval {:approve :approved, :reject :rejected, :amend :draft}
      :approved         {:release :released, :cancel :cancelled}
      :released         {:settle :settled, :fail :failed, :return :returned}
      :settled          {}          ; terminal — reverse with a NEW instruction
      :rejected         {} :cancelled {} :failed {} :returned {}})
   ```

   Plus `terminal?`, `permitted?`, `transition` (returns the next state or
   throws a `:conflict` domain error naming the attempted transition).

2. **`clofin.payments.instruction`** — pure validation and construction. Applies
   PR-002 and PR-003: **collect every failed field, not the first one.**

3. **`clofin.payments.repository`** — persistence, plus `transition!` which
   reads-then-writes under `SELECT … FOR UPDATE` so two concurrent submissions
   cannot both succeed.

4. **`clofin.idempotency`** — key storage and replay resolution.

5. **Migration `0003-payment-instructions.sql`** — see below.

6. **`clofin.api.payments`** + routes + OpenAPI operations.

7. **Posting templates** — `clofin.payments.posting`, producing the journal lines
   for a release. Use the chart of accounts in
   [DOMAIN_MODEL §4](../DOMAIN_MODEL.md); the worked SGD 1,250.00 + 5.00 fee
   example is your test fixture.

### Out — and why

| Out of scope | Reason |
|---|---|
| Approval, maker–checker, thresholds | TASK-003. `submit` moves to `pending-approval` and **stops there**. Do not add an approve endpoint. |
| Audit trail | TASK-003. Do not invent a partial one; a half-built audit trail is worse than none because it looks complete. |
| Settlement, scheme adapter | Increment 5. `released → settled` exists in the state machine but no endpoint drives it yet. |
| Screening | Increment 7. Leave a `TODO(increment-7)` at the `submit` precondition where screening will gate. |
| Batch submission (PR-005) | Deferred — single instruction first. Note it in ROADMAP. |
| Authentication | TASK-003. `createdBy` comes from the request body with a `TODO(TASK-003)`. |

## Interfaces

### Migration `0003-payment-instructions.sql`

Append to `resources/migrations/index.txt`. **Never edit `0001` or `0002`** —
the checksum check will refuse to start.

```sql
create table payment_instruction (
  id                 uuid        primary key,
  organisation_id    uuid        not null references organisation (id),
  debtor_account_id  uuid        not null references ledger_account (id),
  creditor_name      text        not null,
  creditor_account   text        not null,   -- synthetic external identifier
  amount_minor       bigint      not null,
  currency           char(3)     not null references currency (code),
  value_date         date        not null,
  purpose_code       text        not null,
  status             text        not null,
  created_by         uuid        not null,
  created_at         timestamptz not null default now(),
  reverses_id        uuid        null references payment_instruction (id),

  constraint payment_amount_positive check (amount_minor > 0),
  constraint payment_status_known
    check (status in ('draft','pending-approval','approved','released',
                      'settled','rejected','cancelled','failed','returned'))
);

create table idempotency_key (
  organisation_id uuid        not null references organisation (id),
  key             text        not null,
  request_digest  text        not null,   -- SHA-256 of the canonical request body
  response_status integer     not null,
  response_body   text        not null,
  created_at      timestamptz not null default now(),
  primary key (organisation_id, key)
);
```

The composite primary key **is** the replay guarantee (C-06). Do not implement
replay protection as a read-then-write in application code — that is a race, and
under concurrent retries it pays twice, which is the exact failure this task
exists to prevent.

### Idempotency semantics — get these exactly right

| Situation | Behaviour |
|---|---|
| Key absent from the request | `400` — the header is mandatory on every mutating endpoint |
| Key unseen | Execute; store `(key, digest, status, body)` in the **same transaction** as the effect |
| Key seen, digest identical | Return the stored status and body. Perform **no** new work |
| Key seen, digest different | `409 Conflict`. Do not execute |
| Concurrent identical keys | One wins on the primary key; the loser catches the unique violation and returns the stored response |

The digest is over a **canonical** serialisation — sorted keys, no insignificant
whitespace — so that a semantically identical retry is not treated as a
conflict. Write that canonicalisation as its own tested function.

### HTTP

| Method | Path | Operation id | Idempotent |
|---|---|---|---|
| `POST` | `/payment-instructions` | `createPaymentInstruction` | yes |
| `GET` | `/payment-instructions/:id` | `getPaymentInstruction` | — |
| `GET` | `/payment-instructions` | `listPaymentInstructions` | — |
| `PATCH` | `/payment-instructions/:id` | `amendPaymentInstruction` | yes |
| `POST` | `/payment-instructions/:id/submission` | `submitPaymentInstruction` | yes |
| `POST` | `/payment-instructions/:id/cancellation` | `cancelPaymentInstruction` | yes |

Validation failure returns `422` with **every** failed field:

```json
{
  "type": "https://clofin.dev/problems/validation",
  "title": "Request failed validation",
  "status": 422,
  "errors": {
    "amount":     "must be greater than zero",
    "valueDate":  "must not be in the past",
    "purposeCode":"unknown purpose code: XXXX"
  }
}
```

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given valid details, when an instruction is created, then it returns `201` in status `draft`. | PR-001 |
| AC-2 | Given three invalid fields, when creation is attempted, then the response names **all three**, not the first. | PR-003 |
| AC-3 | Given a `draft`, when amended by its creator, then it succeeds; when submitted then amended, then it returns `409`. | PR-004 |
| AC-4 | Given a `draft`, when submitted, then status becomes `pending-approval`. | PR-001 |
| AC-5 | Given a `settled` instruction, when any transition is attempted, then it returns `409` naming the attempted transition. | PR-004 |
| AC-6 | Given a request with an `Idempotency-Key`, when replayed with an identical body, then the stored response is returned and **no second row is written**. | PR-041 |
| AC-7 | Given the same key with a different body, when replayed, then it returns `409`. | PR-042 |
| AC-8 | Given a mutating request with **no** `Idempotency-Key`, then it returns `400`. | PR-040 |
| AC-9 | Given two concurrent requests with the same key, when both execute, then exactly one effect occurs and both callers receive the same response. | PR-041, C-06 |
| AC-10 | Given the transition table, when every (state, event) pair is enumerated, then permitted pairs succeed and every other pair raises `:conflict`. | PR-004 |
| AC-11 | Given a settled instruction, when a reversal is created, then it is a **new** instruction with `reverses_id` set; the original is unchanged. | PR-043 |
| AC-12 | Every new route has a matching OpenAPI operation — `clofin.contract-test` passes without modification. | NFR-003 |

**AC-9 is the one that matters most.** Write it as a real concurrency test: two
threads, a latch, one shared key. A test that calls sequentially proves nothing.

## Definition of done

- [ ] Every acceptance criterion has a named test
- [ ] AC-10 is a **table-driven exhaustive** test over the full transition matrix
- [ ] AC-9 is a genuine concurrency test with a latch
- [ ] `api/openapi.yaml` updated in the same commit as the handlers
- [ ] `make verify` and `make test-it` both green
- [ ] New test namespaces added to `clofin.test-runner`
- [ ] `DOMAIN_MODEL.md` invariants I10 marked ✅
- [ ] `COMPLIANCE.md` C-06 moved from 📋 to ✅ with its enforcement point named
- [ ] Completion reported — PR opened against the base branch above, `002-REQ` filed — so Master Control can set this brief to `IMPLEMENTED` on `meta`
- [ ] UAT script `docs/uat/UAT-003-idempotent-submission.md` — including a
      manual double-submit that a reviewer can perform with `curl`
- [ ] ADR for the canonical-digest decision (what is included, what is ignored,
      and why) — a future contributor will otherwise re-derive it wrongly

## Notes for whoever picks this up

Two traps.

**The state machine must stay data.** The moment a transition rule appears as an
`if` inside a handler, the table stops being the truth and the documentation
starts lying. Every rule goes in `transitions`; handlers only call `transition`.

**Idempotency is not caching.** A cache may miss and re-execute; an idempotency
key may not. Store the key in the *same transaction* as the effect it protects,
or a crash between the two leaves a payment made with no record that it was.
