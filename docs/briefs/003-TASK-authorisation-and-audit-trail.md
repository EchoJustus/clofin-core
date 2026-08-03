# TASK-003: Authorisation, maker–checker and audit trail

| Field | Value |
|---|---|
| **Increment** | 4 |
| **Status** | `IN PROGRESS` — returned from `IMPLEMENTED` 2026-08-03: FEEDBACK-M1 blocking findings F-001 (maker–checker bypass via unrestricted `submit`) and F-002 (`TRUNCATE` bypasses append-only triggers), both verified by Master Control; remediation dispatched to the owning Worker. Prior state: PR #5 green at `6f58857`, all four REQ rulings actioned |
| **Depends on** | TASK-002 — needs the instruction lifecycle to attach approval to |
| **Base branch** | `feat/payment-instruction-lifecycle` at `f529663` — TASK-002 is implemented but unmerged (PR #4), so **stack on its tip and open the PR against that branch**, not `main`, per AGENT_HANDOFF §1b. When PR #4 merges, retarget to `main` and rebase |
| **Blocks** | Increment 5 (settlement) |
| **Requirements** | PR-010…PR-015, PR-070…PR-075 |
| **Controls touched** | C-01, C-02, C-05, C-08 |
| **Scope** | Large — split into (a) authz + approval, (b) audit trail + evidence |
| **Audit** | [`003-REQ`](../audits/003-REQ-authorisation-and-audit-trail.md) filed and ingested 2026-08-03 · **audited in [`FEEDBACK-M1`](../audits/FEEDBACK-M1-foundation.md)** — F-001 (blocking), F-002 (blocking, shared with TASK-001), F-005, F-006; all actioned, remediation in flight |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

This is **the increment an auditor asks about first**, and the one that most
distinguishes a payments product from a database with an API.

After this task: no payment can be approved by the person who submitted it; the
number of approvals required rises with the amount; no approver can act beyond
their own limit; amending an instruction invalidates approvals already given;
and every state change leaves an immutable audit record that commits with the
change it describes.

Four controls that are currently 📋 in `COMPLIANCE.md` become ✅ here.

## Context you need

| Source | What it gives you |
|---|---|
| [COMPLIANCE C-01, C-02, C-05, C-08](../COMPLIANCE.md) | The control statements, verbatim — implement to these |
| [PRD §5.2, §5.8](../PRD.md) | PR-010…PR-015 and PR-070…PR-075 |
| [DOMAIN_MODEL §2.2, §3](../DOMAIN_MODEL.md) | `Approval` fields; lifecycle rules 2 and 3 |
| [ADR-0006](../ADR/0006-postgresql-as-system-of-record.md) | Append-only enforcement pattern — copy the existing trigger approach |
| `resources/migrations/0002-…sql` | `reject_mutation()` already exists; **reuse it**, do not redefine |
| TASK-002 output | `clofin.payments.state`, `clofin.payments.repository/transition!` |

**Open question you must resolve, not assume** — PRD Q1: are approval thresholds
per-currency, or normalised to a base currency? Pick one, **write the ADR**, and
say what it means for a multi-currency organisation. Getting this silently wrong
gives inconsistent control strength across currencies.

## Scope

### In

1. **`clofin.authz.model`** — actors, roles, permissions. Default deny: an
   absent permission is a denied permission, and there is no superuser.
2. **`clofin.authz.approval`** — the pure decision function:

   ```clojure
   (evaluate {:instruction … :actor … :existing-approvals [] :thresholds …})
   ;=> {:decision :permitted}
   ;   {:decision :refused :reason :self-approval}      ; C-01
   ;   {:decision :refused :reason :above-actor-limit}  ; C-02
   ;   {:decision :refused :reason :already-approved}
   ;   {:decision :refused :reason :not-an-approver}
   ```

   Pure — no database, no clock. Every refusal reason is a named keyword so a
   caller can branch and a test can enumerate.
3. **`clofin.authz.repository`**, **`clofin.payments.approval-service`**.
4. **`clofin.audit`** — append-only event capture and evidence extraction.
5. **Migration `0005-authorisation-and-audit.sql`** *(was `0004` — the base branch consumed `0004-idempotency-digest-scope.sql` in the O-3 fix; numbered against the whole stack per §1b and lesson L-1)*.
6. **`clofin.api.approvals`**, **`clofin.api.audit`** + routes + OpenAPI.
7. Replace every `TODO(TASK-003)` left by TASK-001 and TASK-002 with the real
   authenticated principal. **Grep for them; leaving one is a failed handover.**

### Out — and why

| Out of scope | Reason |
|---|---|
| OIDC / identity provider integration | The permission *model* is the interesting part; provider wiring is plumbing. Authenticate from a signed header or a seeded actor table, and say so plainly in the OpenAPI description. |
| Release and settlement | Increment 5. `approve` reaches `approved` and stops. |
| Screening as an approval precondition | Increment 7. Leave the `TODO(increment-7)`. |
| A UI approval queue | Increment 8. `GET /approvals/queue` returns JSON. |
| Field-level encryption of audit payloads | Known gap in COMPLIANCE §4. Store digests, not payloads — see below. |

## Interfaces

### Migration `0005-authorisation-and-audit.sql`

```sql
create table actor (
  id uuid primary key,
  organisation_id uuid not null references organisation (id),
  display_name text not null,
  status text not null default 'active',
  constraint actor_status_known check (status in ('active','suspended'))
);

create table actor_role (
  actor_id uuid not null references actor (id),
  role     text not null,
  primary key (actor_id, role),
  constraint role_known
    check (role in ('operator','approver','controller','compliance','auditor'))
);

-- An approver's own ceiling. Null currency = applies to every currency;
-- see the ADR you write for PRD Q1.
-- [Amended by ruling O-1, 2026-08-03] The original text declared
-- `primary key (actor_id, currency)`. PostgreSQL forces every primary-key
-- column NOT NULL, which makes the documented null-currency row uninsertable —
-- a defect the Worker proved empirically (003-REQ O-1). Uniqueness is now the
-- UNIQUE NULLS NOT DISTINCT constraint below (PostgreSQL 15+; this stack runs
-- 16), which also enforces at most one wildcard row per actor. Migration 0005
-- shipped the defective DDL verbatim and is checksummed and immutable, so the
-- correction lands as migration 0006, not as an edit to 0005.
create table approver_limit (
  actor_id     uuid    not null references actor (id),
  currency     char(3) null references currency (code),
  limit_minor  bigint  not null,
  constraint approver_limit_key unique nulls not distinct (actor_id, currency),
  constraint approver_limit_positive check (limit_minor > 0)
);

-- Amount bands -> how many approvals are required.
create table approval_threshold (
  organisation_id  uuid    not null references organisation (id),
  currency         char(3) not null references currency (code),
  from_minor       bigint  not null,
  approvals_required smallint not null,
  primary key (organisation_id, currency, from_minor),
  constraint threshold_approvals_positive check (approvals_required >= 1)
);

create table approval (
  id             uuid        primary key,
  instruction_id uuid        not null references payment_instruction (id),
  actor_id       uuid        not null references actor (id),
  decision       text        not null,
  reason         text        null,
  decided_at     timestamptz not null default now(),
  -- Invalidated when the instruction is amended (PR-014). Never deleted.
  invalidated_at timestamptz null,

  constraint approval_decision_known check (decision in ('approved','rejected')),
  constraint approval_rejection_needs_reason
    check (decision <> 'rejected' or length(btrim(coalesce(reason,''))) > 0)
);

-- One live approval per actor per instruction; an invalidated one may be re-given.
create unique index approval_actor_live_key
  on approval (instruction_id, actor_id) where invalidated_at is null;

create table audit_event (
  id              uuid        primary key,
  organisation_id uuid        not null references organisation (id),
  actor_id        uuid        null references actor (id),
  action          text        not null,
  subject_type    text        not null,
  subject_id      uuid        not null,
  before_digest   text        null,
  after_digest    text        null,
  correlation_id  text        null,
  occurred_at     timestamptz not null default now()
);

create index audit_event_subject_idx on audit_event (subject_type, subject_id, occurred_at);
create index audit_event_org_time_idx on audit_event (organisation_id, occurred_at desc);

-- Reuse the existing function from migration 0002. Do not redefine it.
create trigger audit_event_append_only
  before update or delete on audit_event
  for each row execute function reject_mutation();

create trigger approval_no_delete
  before delete on approval
  for each row execute function reject_mutation();
```

Note `approval` permits `UPDATE` (to set `invalidated_at`) but forbids `DELETE`.
That asymmetry is deliberate — say so in a comment, or the next reader will
"fix" it.

### Audit capture

`audit_event` stores **digests, not payloads**. A payments audit table that
holds counterparty names becomes a second copy of the data you are trying to
minimise (C-09), and it is append-only, so you can never remove it. Digests
prove *that* something changed and *what it changed to* when compared against a
known value — which is what an auditor actually needs.

The write must be in the **same transaction** as the change (PR-075). The
cleanest shape is a helper that takes the transaction:

```clojure
(audit/record! tx {:actor-id … :action "payment.approved"
                   :subject-type "payment-instruction" :subject-id …
                   :before before-value :after after-value
                   :correlation-id (:correlation-id request)})
```

A test must assert that a **rolled-back** change leaves **no** audit event, and
that a committed one always leaves exactly one. That pair is the proof that an
unaudited state change is not representable.

### HTTP

| Method | Path | Operation id |
|---|---|---|
| `POST` | `/payment-instructions/:id/approvals` | `approvePaymentInstruction` |
| `DELETE` | `/payment-instructions/:id/approvals/:approvalId` | `withdrawApproval` |
| `GET` | `/approvals/queue` | `getApprovalQueue` |
| `GET` | `/audit/events` | `listAuditEvents` |
| `GET` | `/audit/evidence/:subjectId` | `getEvidencePack` |

The queue must carry what an approver needs to *decide* (PR-015): amount,
counterparty, purpose, prior approvals, and how many more are required. A queue
that shows only an id forces the approver into another system, and an approval
given without context is a rubber stamp — which is the control failure the PRD
opens with.

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given an instruction submitted by actor A, when A attempts to approve it, then it is refused with `:self-approval`. | PR-010, C-01 |
| AC-2 | Given an instruction submitted by A, when approver B approves it, then it succeeds. | PR-010 |
| AC-3 | Given an amount above B's limit, when B approves, then it is refused with `:above-actor-limit`. | PR-012, C-02 |
| AC-4 | Given a threshold table requiring two approvals above a band, when only one is given, then status stays `pending-approval`; on the second it becomes `approved`. | PR-011, C-02 |
| AC-5 | Given band boundaries, when an amount falls exactly on a boundary, then the documented side wins — asserted at boundary − 1, boundary, boundary + 1. | PR-011 |
| AC-6 | Given a rejection with no reason, when submitted, then it returns `422`. | PR-013 |
| AC-7 | Given an approved instruction, when any field is amended, then every prior approval is invalidated and status returns to `draft`. | PR-014 |
| AC-8 | Given an actor without the `approver` role, when they approve, then it is refused with `:not-an-approver`. | PR-070, C-08 |
| AC-9 | Given any state change, when it commits, then exactly one `audit_event` exists carrying actor, action, subject and correlation id. | PR-072, PR-075 |
| AC-10 | Given a transaction that **rolls back**, then **no** audit event exists. | PR-075, C-05 |
| AC-11 | Given a committed audit event, when `UPDATE` or `DELETE` is attempted directly in SQL, then the database refuses. | PR-073, C-05 |
| AC-12 | Given an instruction with a full history, when an evidence pack is requested, then it contains every state change in order with its actor. | PR-074 |
| AC-13 | Given the approval queue, when an approver requests it, then each row carries amount, counterparty, purpose, prior approvals and approvals still required. | PR-015 |
| AC-14 | Every new route has a matching OpenAPI operation. | NFR-003 |

**AC-1 and AC-10 are the two that must not be compromised.** If either is
difficult, that is a signal the design is wrong — raise it rather than weakening
the test.

## Definition of done

- [ ] Every acceptance criterion has a named test
- [ ] AC-1, AC-3 and AC-8 are table-driven across the full actor × instruction matrix
- [ ] AC-11 is an integration test issuing raw SQL, like `clofin.db.ledger-constraints-test`
- [ ] **No `TODO(TASK-003)` remains anywhere** — `grep -rn "TODO(TASK-003)" src/` is empty
- [ ] `api/openapi.yaml` updated in the same commit as the handlers
- [ ] `make verify` and `make test-it` both green
- [ ] `COMPLIANCE.md`: C-01, C-02, C-05, C-08 moved 📋 → ✅, each with its
      enforcement point and extractable evidence named
- [ ] `DOMAIN_MODEL.md`: invariants I8 and I9 marked ✅
- [ ] Completion reported — PR opened against the base branch above, `003-REQ` filed — so Master Control can set this brief to `IMPLEMENTED` on `meta`
- [ ] UAT script `docs/uat/UAT-005-segregation-of-duties.md` *(was `UAT-004`,
      which TASK-002 had already consumed — ruling O-3; lesson L-1 widened)* — a
      reviewer must be able to *attempt* self-approval and watch it fail
- [ ] **Two ADRs**: (1) threshold currency handling, resolving PRD Q1;
      (2) digests-not-payloads in the audit trail, and what that costs an auditor

## Notes for whoever picks this up

Three traps, in order of how much damage they do.

**Segregation of duties is a domain rule, not a UI rule.** If the only thing
stopping self-approval is that the button is hidden, the control does not exist.
`evaluate` must refuse it with no HTTP layer involved, and the test must prove
that by calling the function directly.

**The audit write must share the transaction.** Writing it afterwards — even one
line later — means a crash in between produces a state change with no record.
That is precisely the failure C-05 exists to prevent, and it is invisible until
an incident.

**Do not add a superuser role to make testing easier.** Default deny means
default deny. If a test needs broad permissions, grant them explicitly in the
fixture; that fixture then doubles as documentation of what the role can do.

**Inherited from TASK-002 (ruling O-2 and 002-REQ §7) — read
`ADR-0014` (on `feat/payment-instruction-lifecycle` / PR #4 until it merges) before wiring `amend`.**
`PATCH` deliberately does not drive the `:amend` transition; the
`pending-approval → draft` event with approval invalidation (PR-014) is yours to
build, and it is currently in the transition table driven by nothing. The
`TODO(TASK-003)` markers name every point where `createdBy`/`organisationId`
becomes an authenticated principal — including `amend!`, where PR-004's "by its
creator" check belongs. And the audit write (C-05, I9) belongs inside the
transaction that `clofin.idempotency.repository/execute-once!` already
establishes — it hands the effect its connection; write the audit event on it.

---

## Changelog — rulings on the [`003-REQ`](../audits/003-REQ-authorisation-and-audit-trail.md) objections (2026-08-03)

All four objections were triaged the day the REQ was filed. Per protocol the
Worker diverged nowhere silently; every ruling below lands on `meta` and, where
it changes code, travels to the owning Worker as a fix instruction.

| # | Objection | Ruling |
|---|---|---|
| O-1 | The specified `approver_limit` primary key forces `currency NOT NULL`, making the brief's own documented null-currency ("every currency") row uninsertable. | **Confirmed — brief defect; fix ordered.** The Interfaces DDL above is amended: the primary key is replaced by `unique nulls not distinct (actor_id, currency)`. Chosen over the REQ's suggested `coalesce(currency, '***')` expression index because it needs no sentinel value, it is a declared table constraint rather than only an index, and the same declaration that enforces per-currency uniqueness enforces at-most-one wildcard row per actor. Migration `0005` is applied and checksummed, so the correction is **migration `0006`**; the Worker's pinning test (`objection-o-1-…`) is deleted by the fix and replaced with storage-level wildcard tests. **Fix applied** in `6f58857` as `0006-approver-limit-wildcard-currency.sql`, CI green, +6 tests / +19 assertions with integration. One addendum the Worker found by verifying from an empty schema rather than assuming: dropping a primary key does **not** drop the `NOT NULL` marks it implied, so the migration carries an explicit `alter column currency drop not null` between the constraint swap — without it the ruled DDL still refused the wildcard row. The brief's amended `create table` form above is unaffected (a fresh table with the declared constraint behaves correctly); the subtlety is specific to the ALTER path off 0005. |
| O-2 | AC-7 requires an `:amend` arrow from `approved` that TASK-002's lifecycle table did not carry; `DOMAIN_MODEL.md` rule 3 and its diagram disagreed with each other. | **Confirmed — the Worker's resolution stands; no revert.** AC-7 means what it says: an approved instruction is amendable, every approval is invalidated, status returns to `draft` — that is PR-014's clearest case, and without it the only path off `approved` is cancellation. The added arrow and ADR-0014 amendment 1 are ratified. Both contradicting documents were authored by Master Control, so this is a cross-brief authoring defect, now **lesson L-4**. Residual: the DOMAIN_MODEL §3 diagram must show `:amend` from *both* `pending-approval` and `approved` — folded into the O-1 fix instruction. |
| O-3 | `UAT-004` was already consumed by TASK-002. | **Accepted.** `UAT-005-segregation-of-duties.md` stands; the DoD above is amended. The Worker's suggestion is taken: lesson **L-1 is widened** from migrations to every sequentially-numbered artefact series (briefs, audits, ADRs, UAT scripts, migrations). This is the second L-1 recurrence inside one brief — the brief renumbered its migration and still hard-coded a stale UAT number. |
| O-4 | C-02's stated evidence ("the approver's limit at the time") is not producible from the specified schema — `approver_limit` is mutable and unversioned. | **Accepted.** Rewriting C-02 to name only what is extractable *is* the DoD instruction ("extractable evidence named"), not a divergence. The capture columns (`actor_limit_minor`, `approvals_required` on `approval`, written at decision time) are recorded as carried-forward debt on the ROADMAP for a future brief — a schema change belongs in a brief, exactly as the Worker judged. |

Two decisions the Worker took without an ADR, ruled here so they are not
re-argued:

- **`:no-threshold-configured` as a fifth refusal reason — accepted.** The
  brief's four-reason list was illustrative, not exhaustive; it had no answer
  for an unconfigured band table, and the alternatives (invent an approval
  count, or throw) both weaken or misreport the control. Denying loudly is the
  ADR-0015 posture applied consistently.
- **`evaluate` taking an optional `:decision` (default `:approved`) — accepted.**
  C-01 must be stated exactly once; a separate rejection-evaluation function is
  where a second, drifting statement would grow. The brief's call shape is
  unchanged.
