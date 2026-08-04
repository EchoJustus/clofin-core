-- Settlement: batches, their members, and the scheme responses that resolve them.
--
-- CloFin never connects to a real scheme, bank or central bank. The `SIM-`
-- prefix in `settlement_scheme_known` is load-bearing rather than cosmetic: it
-- makes it impossible to record a batch against a scheme name that could be
-- mistaken for a real one, in the table an auditor reads. Every doc, endpoint
-- description and fixture that touches this schema says *simulated*.
--
-- Numbered 0009 against the live tree: 0007 and 0008 were consumed by the
-- FEEDBACK-M1 remediation (TRUNCATE guards, entry completeness) and are applied
-- and checksummed. Append only — 0001..0008 are immutable
-- (docs/ADR/0009-forward-only-sql-migrations.md, lesson L-1).
--
-- Two decisions in this file are recorded as ADRs rather than left to be
-- re-derived:
--
--   * A release **posts** — debtor funds move to settlement-in-transit when a
--     batch is submitted, and finality moves the in-transit leg on
--     (docs/ADR/0018-release-posts-to-settlement-in-transit.md). That is why
--     nothing here stores an amount: the journal already holds it, and a second
--     copy is a second thing to disagree.
--   * Only a `returned` item frees its instruction for re-batching. See
--     `settlement_item_live_key` below — it is the schema half of the control
--     this whole module exists for.

-- ---------------------------------------------------------------------------
-- Batches
-- ---------------------------------------------------------------------------

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

comment on table settlement_batch is
  'A set of approved payment instructions submitted to a simulated scheme as a '
  'unit, sharing one (scheme, currency, value_date). No real scheme exists: the '
  'SIM- prefix on the scheme check constraint is what keeps a synthetic record '
  'from ever naming a real network.';

comment on column settlement_batch.status is
  'DERIVED from the outcomes of the batch''s items, never set independently — '
  'the same doctrine as balances deriving from the journal rather than being '
  'stored (ADR-0008). open -> submitted at submission; once every item has '
  'resolved the batch becomes settled (all settled), failed (none settled) or '
  'partially-settled (a mix). The derivation is stated once, in '
  'clofin.settlement.batch/derive-status, and this column is where the '
  'repository writes what that function returned.';

comment on column settlement_batch.currency is
  'One currency per batch. A batch is the unit a scheme settles, and a scheme '
  'settles in one currency — a mixed-currency batch would have no meaningful '
  'value and no meaningful finality.';

-- ---------------------------------------------------------------------------
-- Membership and outcomes
-- ---------------------------------------------------------------------------

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

comment on table settlement_batch_item is
  'One instruction''s membership of one batch, and what became of it. A null '
  'outcome means the scheme has not answered yet — which is NOT the same as '
  'timed-out: null is "no answer yet", timed-out is "no answer, and we have '
  'stopped waiting". Both are unresolved; only the second is a fact about the '
  'passage of time.';

comment on column settlement_batch_item.outcome is
  'settled | returned | timed-out, or null while pending. There is deliberately '
  'no "failed": a scheme failure that sends the money back IS a return, and an '
  'unknown outcome is timed-out. Recorded rather than silently assumed — see '
  'objection O-1 in docs/audits/004-REQ-settlement-simulation.md, which asks '
  'whether the payment lifecycle''s `fail` arrow should have a driver here.';

comment on column settlement_batch_item.resolved_at is
  'When the outcome was recorded. Null while pending. Set exactly once: a '
  'second resolution of the same item is refused by the application before it '
  'reaches this row, and the scheme_response replay key refuses the duplicate '
  'delivery that would have caused it.';

-- An instruction may be in at most one membership that is pending, settled or
-- timed out. Only 'returned' frees it for re-batching: a timed-out item's true
-- outcome is unknown, and re-submitting it risks exactly the duplicate
-- settlement this module exists to prevent.
create unique index settlement_item_live_key
  on settlement_batch_item (instruction_id)
  where outcome is distinct from 'returned';

comment on index settlement_item_live_key is
  'The no-double-settlement guard, in the schema rather than in application '
  'code, so it binds a fix-up script and a defect as well as a handler '
  '(AC-7). "Live" is every membership except a returned one: pending, settled '
  'and timed-out all block. Timed-out blocks precisely because the outcome is '
  'UNKNOWN — treating unknown as failed and re-batching is how a payment gets '
  'made twice.';

create index settlement_batch_org_idx
  on settlement_batch (organisation_id, value_date);

-- ---------------------------------------------------------------------------
-- Scheme responses
-- ---------------------------------------------------------------------------
--
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
  -- two identical acks must collide, not coexist (lesson from ruling O-1 on
  -- TASK-003 — a plain unique constraint treats nulls as distinct and would
  -- admit both).
  constraint scheme_response_replay_key
    unique nulls not distinct (batch_id, instruction_id, kind, reference)
);

create index scheme_response_batch_idx on scheme_response (batch_id, received_at);

comment on table scheme_response is
  'Every response a simulated scheme delivered, kept whether or not it caused '
  'work. A duplicate delivery is refused by scheme_response_replay_key and the '
  'FIRST row stays — the same posture idempotency_key takes, and for the same '
  'reason: the evidence that a duplicate arrived and was refused work is worth '
  'more than the storage. Responses arrive late and out of order in the world '
  'this simulates; that is the normal case, not the exception.';

comment on column scheme_response.reference is
  'The scheme''s own reference for this response. Part of the replay key, so '
  'two deliveries carrying the same reference for the same (batch, instruction, '
  'kind) are one response delivered twice, and the second does no work.';

comment on column scheme_response.kind is
  'ack (batch acknowledged, instruction_id null) | settled | returned | '
  'timeout-resolution (a late answer for an item the sweep already marked '
  'timed-out). The outcome a timeout-resolution resolves TO travels in the '
  'request and is written to settlement_batch_item.outcome, which is its system '
  'of record; it is not duplicated here, because two columns holding one fact '
  'are two columns that can disagree. Noted in 004-REQ.';

-- ---------------------------------------------------------------------------
-- Append-only enforcement
-- ---------------------------------------------------------------------------
--
-- `scheme_response` is append-only *by usage* and now by enforcement, on the
-- full destructive verb set that lesson **L-5** requires: UPDATE, DELETE and
-- TRUNCATE, each a distinct trigger event with its own privilege. TRUNCATE
-- visits no rows, so a `for each row` guard never sees it — that is exactly how
-- audit finding F-002 emptied `audit_event` past a guard that had just refused
-- an UPDATE and a DELETE on the same row.
--
-- Reusing `reject_mutation()` from migration 0002 rather than redefining it:
-- one function, one message, one place to change. Do not add a bypass flag —
-- a guard with a documented bypass is a guard whose bypass appears in an
-- incident.
--
-- Why this table and not `settlement_batch_item`: an item's `outcome` is
-- resolved by UPDATE, which is the whole mechanism of the module, so it cannot
-- be append-only. A response, by contrast, is a statement about something that
-- arrived at a moment in time. Editing one would be editing history.
--
-- The same limit stated for every other guarded table applies here: a trigger
-- binds the application, not the table's OWNER, and CloFin connects as the
-- owner. The runtime role split is named debt in COMPLIANCE.md §4.

create trigger scheme_response_append_only
  before update or delete on scheme_response
  for each row execute function reject_mutation();

create trigger scheme_response_no_truncate
  before truncate on scheme_response
  for each statement execute function reject_mutation();
