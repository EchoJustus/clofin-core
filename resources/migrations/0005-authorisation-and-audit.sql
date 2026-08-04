-- Actors, roles, approval limits, thresholds, approvals and the audit trail.
--
-- This is the increment an auditor asks about first. Four controls become
-- enforced here (docs/COMPLIANCE.md): C-01 segregation of duties, C-02 dual
-- authorisation proportionate to value, C-05 a complete and attributable audit
-- trail, and C-08 least privilege.
--
-- Two design decisions in this file are recorded as ADRs rather than left to be
-- re-derived:
--
--   * Thresholds and approver limits are **per currency**, with no implicit FX
--     normalisation (docs/ADR/0015-approval-thresholds-are-per-currency.md,
--     resolving PRD Q1). That is why `approval_threshold.currency` is part of
--     the primary key rather than a base-currency amount sitting on its own.
--   * `audit_event` stores **digests, not payloads**
--     (docs/ADR/0016-audit-events-store-digests-not-payloads.md). An
--     append-only table holding counterparty names is a second copy of the data
--     C-09 exists to minimise, and one that can never be removed.
--
-- Numbered 0005 rather than 0004: the branch this stacks on consumed 0004 for
-- the idempotency digest-scope correction. Migrations in a stack are numbered
-- against the whole stack, never against `main` alone
-- (docs/AGENT_HANDOFF.md §1b). Append only — 0001..0004 are immutable and the
-- runner verifies their checksums
-- (docs/ADR/0009-forward-only-sql-migrations.md).

-- ---------------------------------------------------------------------------
-- Actors and roles
-- ---------------------------------------------------------------------------
--
-- An actor is seeded, not registered through the API: identity-provider
-- integration is deliberately out of scope for this increment, and a
-- self-service "create yourself an approver account" endpoint would be a
-- control failure dressed as convenience. See the Authorisation tag in
-- api/openapi.yaml, which says so to a caller as well.

create table actor (
  id uuid primary key,
  organisation_id uuid not null references organisation (id),
  display_name text not null,
  status text not null default 'active',
  constraint actor_status_known check (status in ('active','suspended'))
);

comment on table actor is
  'A person or system able to act within one organisation. Synthetic: no actor '
  'here corresponds to a real individual. Seeded rather than self-registered — '
  'there is no endpoint that creates one, because an actor that could grant '
  'itself the approver role would make C-01 unenforceable.';

comment on column actor.status is
  'A suspended actor holds no permissions at all, whatever roles are recorded '
  'against them. Suspension is therefore a complete stop, not a hint the '
  'application may weigh against the role table.';

create table actor_role (
  actor_id uuid not null references actor (id),
  role     text not null,
  primary key (actor_id, role),
  constraint role_known
    check (role in ('operator','approver','controller','compliance','auditor'))
);

comment on table actor_role is
  'Roles held by an actor. The permissions each role carries are held in '
  'clofin.authz.model, not here: a permission set expressed as rows would be '
  'editable at runtime by anyone able to write this table, and least privilege '
  '(C-08) would then be a matter of what the data happened to say. There is no '
  'superuser role in the list above, and none may be added — an absent '
  'permission is a denied permission.';

-- An approver's own ceiling. Null currency = applies to every currency;
-- see the ADR you write for PRD Q1.
create table approver_limit (
  actor_id     uuid    not null references actor (id),
  currency     char(3) null references currency (code),
  limit_minor  bigint  not null,
  primary key (actor_id, currency),
  constraint approver_limit_positive check (limit_minor > 0)
);

comment on table approver_limit is
  'The largest amount an actor may approve, per currency (C-02). Absent means '
  'zero, not unlimited: an approver with no row for a currency cannot approve '
  'anything in it. Limits are integer minor units against the currency''s own '
  'scale and are never converted — see ADR-0015 for why a normalised limit '
  'would make the control''s strength depend on an exchange rate.';

comment on column approver_limit.currency is
  'Intended to allow a null row meaning "every currency". NOTE: this column is '
  'part of the primary key, and PostgreSQL forbids a null in a primary key '
  'column — so the null row cannot in fact be inserted, and every limit must '
  'today be per currency. Recorded rather than silently worked around; see '
  'objection O-1 in docs/audits/003-REQ-authorisation-and-audit-trail.md. The '
  'domain function clofin.authz.approval/evaluate already honours a wildcard '
  'limit, so only this constraint stands in the way.';

-- Amount bands -> how many approvals are required.
create table approval_threshold (
  organisation_id  uuid    not null references organisation (id),
  currency         char(3) not null references currency (code),
  from_minor       bigint  not null,
  approvals_required smallint not null,
  primary key (organisation_id, currency, from_minor),
  constraint threshold_approvals_positive check (approvals_required >= 1)
);

comment on table approval_threshold is
  'Amount bands mapping to the number of approvals required (C-02, PR-011). '
  'The applicable band is the one with the greatest from_minor not exceeding '
  'the amount, and from_minor is INCLUSIVE — an amount exactly on a boundary '
  'falls in the higher band, which is the side that asks for more scrutiny '
  'rather than less. Per currency, per organisation: an organisation with no '
  'band covering an amount cannot have it approved at all, because guessing a '
  'requirement is how a control silently weakens.';

comment on column approval_threshold.from_minor is
  'Inclusive lower bound of the band, in integer minor units. A band with '
  'from_minor 0 is the floor; without one, small amounts are unapprovable by '
  'design rather than by accident.';

-- ---------------------------------------------------------------------------
-- Approvals
-- ---------------------------------------------------------------------------

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

comment on table approval is
  'One actor''s decision on one instruction. UPDATE is permitted and DELETE is '
  'not, and that asymmetry is deliberate: an amendment must be able to set '
  'invalidated_at (PR-014), but nothing may make a decision disappear. An '
  'approval that was given and then invalidated is exactly the history an '
  'investigation needs — a deleted one is a decision nobody can prove was '
  'ever taken. Do not "fix" the missing DELETE trigger by removing the UPDATE '
  'asymmetry; add a column instead.';

comment on column approval.invalidated_at is
  'Set when the instruction was amended after this approval was given, or when '
  'the approving actor withdrew it. The row stays; the approval stops counting.';

comment on index approval_actor_live_key is
  'One live approval per actor per instruction. Partial on invalidated_at is '
  'null so that an actor whose approval was invalidated by an amendment may '
  'approve the amended instruction again — which is the whole point of '
  'invalidating rather than deleting.';

-- ---------------------------------------------------------------------------
-- Audit trail
-- ---------------------------------------------------------------------------
--
-- Written in the same transaction as the change it describes (PR-075, C-05).
-- A write "immediately afterwards" is a write that a crash in between omits,
-- which is precisely the failure this control exists to prevent — and it is
-- invisible until an incident.

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

comment on table audit_event is
  'Append-only record of a state change: who, what, to which subject, when, '
  'and — as digests — what it changed from and to. Written in the same '
  'transaction as the change itself, so a rolled-back change leaves no event '
  'and a committed one always leaves exactly one (C-05, PR-075, invariant I9).';

comment on column audit_event.actor_id is
  'Null only where there is genuinely no authenticated actor. Today that is '
  'the bootstrap case alone; a null here on a payment action would be a defect.';

comment on column audit_event.before_digest is
  'Digest of the subject before the change, or null when the subject did not '
  'exist. NOT the value itself: see '
  'docs/ADR/0016-audit-events-store-digests-not-payloads.md. Prefixed with the '
  'canonicalisation version that produced it, so a later change to the '
  'canonical form cannot make two incomparable digests look comparable.';

comment on column audit_event.correlation_id is
  'The request correlation id, so an audit event can be joined to the log line '
  'and the response the caller received.';

-- Reuse the existing function from migration 0002. Do not redefine it.
create trigger audit_event_append_only
  before update or delete on audit_event
  for each row execute function reject_mutation();

create trigger approval_no_delete
  before delete on approval
  for each row execute function reject_mutation();
