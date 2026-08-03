# TASK-004: Settlement simulation

| Field | Value |
|---|---|
| **Increment** | 5 |
| **Status** | `READY` |
| **Depends on** | TASK-003 — settlement drives the `release` arrow the authorisation increment left in the table |
| **Base branch** | `claude/authorisation-audit-trail-r5fzw3` at `6f58857` — TASK-003 is implemented but unmerged (PR #5, itself stacked on PR #4), so **stack on its tip and open the PR against that branch**, not `main`, per AGENT_HANDOFF §1b. If PR #5 retargets or rebases after PR #4 merges, rebase promptly and follow it |
| **Blocks** | Increment 6 (reconciliation) |
| **Requirements** | PRD §5.3 (settlement); NFR-003 |
| **Controls touched** | C-03, C-04 exercised; **no control moves 📋 → ✅ here** — C-07 (screening before release) stays 📋 and is increment 7's |
| **Scope** | Large — split into (a) batch construction + release, (b) outcomes + finality posting |
| **Audit** | Not yet submitted |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

Approved payments currently stop at `approved`. After this task an instruction
can be batched by scheme, currency and value date, released, and carried to a
terminal outcome — settled, failed or returned — with finality postings on the
ledger, an audit event for every state change, and a scheme adapter that is
**explicitly and only a simulation**.

The interesting failure modes are the point of the increment: partial batch
failure, duplicate scheme responses, out-of-order responses, and timeouts whose
true outcome is unknown. Handling those correctly — not the happy path — is
what distinguishes a settlement module from a status field.

**Standing constraint, non-negotiable:** CloFin never connects to a real
scheme, bank or central bank. Scheme names are `SIM-` prefixed in the schema's
check constraint on purpose. Every doc, endpoint description and test fixture
this task writes says *simulated* where it applies.

## Context you need

| Source | What it gives you |
|---|---|
| `src/clofin/payments/state.clj` on the base branch | The lifecycle **already carries settlement's arrows**: `approved →release→ released`, `released →settle/fail/return→` terminals. Its docstring: an increment "gets to drive the transition, not to decide where it leads." You drive; you do not redraw |
| `src/clofin/payments/posting.clj` | Posting templates from TASK-002 — extend, do not fork |
| `src/clofin/audit.clj` | The **closed** action vocabulary; `record!` refuses unknown actions. You must add settlement's terms — that is deliberate (003-REQ §7) |
| `src/clofin/payments/approval_service.clj` | The unit-of-work shape to copy: the service takes the caller's transaction and composes repositories on it, never opening its own |
| [ADR-0008](../ADR/0008-double-entry-journal-as-source-of-truth.md), [ADR-0012](../ADR/0012-repository-seam-and-posting-time-validation.md) | Where postings and validations live |
| ADR-0014 (+ amendment 1), ADR-0016 — on the base branch | Lifecycle-as-data rules; digests-not-payloads for the audit events you emit |

**Open question you must resolve, not assume — write the ADR:** what does
`release` post, if anything? Two defensible designs: (a) release posts debtor →
settlement-suspense, finality moves suspense → cleared, a return reverses the
suspense leg; (b) nothing posts until finality, and `released` is a purely
informational state. (a) mirrors how clearing exposure is usually represented;
(b) is simpler and keeps the journal free of in-flight states. Pick one, state
what an auditor sees mid-flight under your choice, and record it. Do not split
the difference.

## Scope

### In

1. **`clofin.settlement.batch`** — pure batch construction rules: eligibility
   (`approved` only, organisation-scoped), grouping by `(scheme, currency,
   value_date)`, and batch status derivation from item outcomes.
2. **`clofin.settlement.scheme`** — the adapter **protocol**, plus the one
   simulated implementation. Deterministic: outcomes derive from a seed or from
   instruction attributes (document which), so a test and a UAT reviewer can
   predict them. Partial failure must be producible on demand.
3. **`clofin.settlement.repository`**, **`clofin.settlement.service`** — the
   seam and the unit of work, in the `approval-service` shape: takes the
   caller's transaction, composes repositories, never opens a connection.
4. **Release**: batch submission transitions every member `approved → released`
   under `SELECT … FOR UPDATE`, one audit event per instruction, in one
   transaction with the batch's own transition.
5. **Outcomes**: scheme responses drive `released → settled | failed |
   returned` per item; finality postings via the posting templates; a `returned`
   item raises an exception case (its posting consequence depends on your
   release ADR). Batch status derives: all settled → `settled`; a mix →
   `partially-settled`; none → `failed`.
6. **Timeouts**: an explicit sweep operation marks overdue unresolved items
   `timed-out`. A timed-out item **blocks re-batching** (schema-enforced) until
   a late `timeout-resolution` response resolves it exactly once. No background
   scheduler — the sweep is an endpoint an operator (or a test) calls.
7. **Duplicate and out-of-order responses**: every scheme response is stored
   verbatim; the replay key makes a duplicate detectable and idempotent — same
   answer, no second posting, no second audit event.
8. **Migration** — *next available number against the tree you build on; at
   authoring time that is `0007` — verify before you write it (L-1)*. The DDL
   below is **validated against a live PostgreSQL 16 with migrations 0001–0006
   applied** (L-3): every documented row shape inserts; every guard refuses.
9. **Audit vocabulary**: add `payment.released`, `payment.settled`,
   `payment.failed`, `payment.returned`, `settlement-batch.created`,
   `settlement-batch.submitted`, `settlement-batch.completed`,
   `settlement-batch.timeout-swept` to `clofin.audit/actions`, and
   `settlement-batch` to `subject-types`. *(TASK-005 adds terms to the same
   sorted-set literal — whichever lands second rebases; a one-line conflict.)*
10. **Authorisation**: settlement operations require a `:settlement/execute`
    permission granted to the `controller` role (the role exists in `actor_role`'s
    check constraint; the permission set lives in code, default deny). No role
    may hold both `:payment/approve` and `:settlement/execute` — assert it in
    `authz.model-test` beside the existing separation assertions.
11. **HTTP + OpenAPI**, same commit as the handlers:

| Method | Path | Operation id |
|---|---|---|
| `POST` | `/settlement-batches` | `createSettlementBatch` |
| `POST` | `/settlement-batches/:id/submit` | `submitSettlementBatch` |
| `POST` | `/settlement-batches/:id/scheme-responses` | `recordSchemeResponse` — **simulation injection point; say so in its OpenAPI description** |
| `POST` | `/settlement-batches/:id/timeout-sweep` | `sweepSettlementTimeouts` |
| `GET` | `/settlement-batches/:id` | `getSettlementBatch` |
| `GET` | `/settlement-batches` | `listSettlementBatches` |

### Out — and why

| Out of scope | Reason |
|---|---|
| Real scheme connectivity, real scheme names, real cut-off calendars | Never in scope for this product. Synthetic only, stated everywhere |
| Sanctions screening before release | Increment 7 owns C-07. **Leave `TODO(increment-7)` exactly where it is** |
| Reconciliation of settled items against statements | Increment 6 — it consumes what you build |
| A background scheduler / timeout daemon | The sweep is an explicit call. A daemon is operational machinery with no driver yet |
| Batch-level approval workflow | Approval attaches to instructions (TASK-003). A second approval layer on batches needs product thinking, not incidental scope |
| Retry of failed batches | `failed` is terminal for the batch; members are re-batchable per the rules below. Automated retry is policy, and policy needs a brief |

## Interfaces

### Migration — settlement tables *(validated per L-3; see §8 above)*

```sql
create table settlement_batch (
  id              uuid        primary key,
  organisation_id uuid        not null references organisation (id),
  scheme          text        not null,
  currency        char(3)     not null references currency (code),
  value_date      date        not null,
  status          text        not null default 'open',
  created_by      uuid        not null references actor (id),
  created_at      timestamptz not null default now(),
  constraint settlement_batch_status_known
    check (status in ('open','submitted','settled','partially-settled','failed')),
  -- Simulated schemes only; the SIM- prefix is deliberate and load-bearing.
  constraint settlement_scheme_known
    check (scheme in ('SIM-RTGS','SIM-ACH'))
);

create table settlement_batch_item (
  batch_id       uuid        not null references settlement_batch (id),
  instruction_id uuid        not null references payment_instruction (id),
  outcome        text        null,
  outcome_reason text        null,
  resolved_at    timestamptz null,
  primary key (batch_id, instruction_id),
  constraint settlement_outcome_known
    check (outcome is null or outcome in ('settled','returned','timed-out')),
  constraint settlement_return_needs_reason
    check (outcome is distinct from 'returned'
           or length(btrim(coalesce(outcome_reason,''))) > 0)
);

-- An instruction may be in at most one membership that is pending, settled or
-- timed out. Only 'returned' frees it for re-batching: a timed-out item's true
-- outcome is unknown, and re-submitting it risks exactly the duplicate
-- settlement this module exists to prevent.
create unique index settlement_item_live_key
  on settlement_batch_item (instruction_id)
  where outcome is distinct from 'returned';

create index settlement_batch_org_idx
  on settlement_batch (organisation_id, value_date);

-- Scheme responses are recorded verbatim so a duplicate or out-of-order
-- delivery is detectable and provable — the posture idempotency_key set.
create table scheme_response (
  id             uuid        not null primary key,
  batch_id       uuid        not null references settlement_batch (id),
  instruction_id uuid        null references payment_instruction (id),
  kind           text        not null,
  reference      text        not null,
  received_at    timestamptz not null default now(),
  constraint scheme_response_kind_known
    check (kind in ('ack','settled','returned','timeout-resolution')),
  -- NULLS NOT DISTINCT: a batch-level ack carries a null instruction_id, and
  -- two identical acks must collide, not coexist (lesson from ruling O-1 —
  -- a plain unique constraint treats nulls as distinct and would admit both).
  constraint scheme_response_replay_key
    unique nulls not distinct (batch_id, instruction_id, kind, reference)
);

create index scheme_response_batch_idx on scheme_response (batch_id, received_at);
```

Note `settlement_batch_item` permits `UPDATE` (outcome resolution) and
`scheme_response` is effectively append-only by usage; whether to enforce
`reject_mutation()` on `scheme_response` is yours to decide — if you do, reuse
the function from migration 0002, never redefine it, and say why in a comment.

### Batch status derivation

Batch status is **derived from item outcomes**, never set independently —
same doctrine as balances deriving from the journal. `open → submitted` at
submission; a batch where every item has resolved becomes `settled` (all
settled), `failed` (none settled), or `partially-settled` (a mix). State the
derivation once, in `clofin.settlement.batch`, and have the repository read it.

## Acceptance criteria

Every arrow named below already exists in `clofin.payments.state/transitions`
on the base branch — verified at brief-authoring time (L-4). None of these ACs
requires a lifecycle change; if you believe one does, that is an objection for
your REQ, not an edit.

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given approved instructions in one organisation sharing `(scheme, currency, value_date)`, when a batch is constructed, then it contains exactly those; a `draft`, `pending-approval` or `released` instruction is refused with a named reason. | PRD §5.3 |
| AC-2 | Given instructions differing in scheme, currency **or** value date, when construction is attempted across the difference, then it is refused — one batch, one `(scheme, currency, value_date)`. | PRD §5.3 |
| AC-3 | Given an open batch, when submitted, then every member transitions `approved → released` and the batch `open → submitted` **in one transaction**, with one audit event per instruction and one for the batch. | I9, C-05 |
| AC-4 | Given a submitted batch where the simulated scheme settles some members and returns others, then settled items post finality entries, returned items carry their reason, batch status derives to `partially-settled`, and the ledger's zero-sum invariant holds — asserted by property test across generated outcome mixes. | I1, C-04 |
| AC-5 | Given a `settled` outcome recorded for an item, when the identical scheme response is delivered again, then the answer is the same, **no second posting exists and no second audit event exists** — proven at the repository level and through the API. | I10's posture, C-06 |
| AC-6 | Given an item with no response past the timeout horizon, when the sweep runs, then it is `timed-out`; when a late `timeout-resolution` response arrives, then it resolves to its true outcome **exactly once**, and a second resolution attempt is refused. | PRD §5.3 |
| AC-7 | Given a `timed-out` (or pending, or settled) item, when the instruction is added to a new batch, then the database refuses — asserted with raw SQL like `db.audit-constraints-test`, application bypassed. A `returned` item's instruction re-batches successfully. | C-04 |
| AC-8 | Given a `returned` item, then the instruction is `returned`, an exception case is visible via the API with the return reason, and the posting consequence matches your release ADR. | PRD §5.3 |
| AC-9 | Given a rolled-back outcome transaction (deliberate throw after the posting), then no posting, no outcome, no audit event — the I9 pair, extended to settlement. | I9, C-05 |
| AC-10 | Given an actor without `:settlement/execute`, when any settlement operation is attempted, then it is refused with a named reason; the `controller` role succeeds; **no role holds both `:payment/approve` and `:settlement/execute`** — asserted in the model tests. | C-01, C-08 |
| AC-11 | Given a batch's full history, when the evidence pack for an instruction in it is requested, then release, outcome and posting events appear in order with their actors. | C-05 |
| AC-12 | Every new route has a matching OpenAPI operation, and `recordSchemeResponse`'s description states it is a simulation injection point. | NFR-003 |

**AC-5 and AC-7 are the two that must not be compromised.** Both guard the same
failure — money moving twice — which is this product's cardinal sin. If either
is hard, the design is wrong; raise it in your REQ rather than weakening the
test.

## Definition of done

- [ ] Every acceptance criterion has a named test
- [ ] AC-4 is a property test over generated outcome mixes, not three examples
- [ ] AC-7 is an integration test issuing raw SQL
- [ ] The release-posting ADR is written — *ADR numbers are next-available
      against the tree you build on; verify, do not assume (L-1)*
- [ ] `api/openapi.yaml` updated in the same commit as the handlers
- [ ] `make verify` and `make test-it` both green
- [ ] `COMPLIANCE.md`: C-04's evidence extended with settlement postings;
      **C-07 untouched and still 📋**
- [ ] `DOMAIN_MODEL.md` §3: no lifecycle edits expected; the settlement states
      now have drivers — update the prose that says they are driven by nothing
- [ ] UAT script — *next available number; at authoring time `UAT-006` — verify
      (L-1)* — a reviewer batches, settles, returns and times out payments and
      watches the guards hold
- [ ] Completion reported — PR opened against the base branch above, REQ filed
      (next available number in the audits series) — so Master Control can set
      this brief `IMPLEMENTED` on `meta`

## Notes for whoever picks this up

**Drive the arrows; do not redraw them.** The lifecycle table on the base
branch already contains every transition you need. If an AC seems to demand a
new arrow, re-read it, then object in your REQ (L-4 exists because a brief got
this wrong once).

**The duplicate-response guard is the increment.** A scheme that answers twice,
late, or out of order is the *normal* case in the real world this simulates.
The `scheme_response` table plus its replay key is your evidence that a
duplicate arrived and was refused work — keep the verbatim row even when
no-oping, exactly as `idempotency_key` keeps the stored response.

**Timeout means unknown, not failed.** The temptation is to treat `timed-out`
as `failed` and move on; the schema deliberately blocks that. An item whose
outcome is unknown must stay un-re-batchable until resolved, or the simulation
teaches the wrong lesson about the one failure mode that costs real money.

**The audit vocabulary is closed on purpose.** Add your terms to
`clofin.audit/actions` in the same commit as the code that records them.
TASK-005 touches the same literal; the merge conflict is one line and whoever
lands second resolves it.

**Environment note.** CI has no Docker-in-Docker and workers before you built
against a local PostgreSQL 16 (003-REQ environment note). The compose smoke
test in CI applies the full migration stack from empty — your migration will be
exercised there; number it against the live tree (L-1).
