-- Correct `approver_limit`'s uniqueness so the wildcard-currency row it has
-- always documented can actually be stored.
--
-- Migration 0005 declared `primary key (actor_id, currency)` while the column
-- was declared `null` and commented as "null currency = applies to every
-- currency". PostgreSQL forces every primary-key column NOT NULL, so the
-- `null` was silently overridden and the wildcard row failed on insert:
--
--   ERROR:  null value in column "currency" of relation "approver_limit"
--           violates not-null constraint
--
-- The DDL and its own comment therefore contradicted each other, and the
-- comment was the one telling the truth about the intent. Found while
-- implementing TASK-003 and raised as objection O-1 rather than worked around;
-- confirmed by Master Control as a defect in the brief, with this fix ordered.
-- See docs/audits/003-REQ-authorisation-and-audit-trail.md §4 O-1.
--
-- `unique nulls not distinct` rather than a `coalesce(currency, '***')`
-- expression index — the alternative the REQ suggested. Three reasons, in the
-- order they matter:
--
--   1. **No sentinel.** A magic `'***'` currency code is a value that has to be
--      excluded from every future query, report and foreign key by whoever
--      remembers it exists. The wildcard row's currency is genuinely unknown,
--      and NULL is what SQL has for that.
--   2. **A declared constraint, not only an index.** It appears in `\d`, in
--      information_schema, and in any schema diff an auditor runs. An
--      expression index enforcing a business rule is a rule hidden in an
--      implementation detail.
--   3. **It enforces at-most-one wildcard row per actor.** Under the default
--      NULLS DISTINCT — and under a plain unique index — two null-currency rows
--      for one actor would both be accepted, leaving two contradictory
--      "applies to every currency" ceilings and no rule for which wins.
--      `NULLS NOT DISTINCT` treats them as equal and refuses the second.
--
-- `NULLS NOT DISTINCT` requires PostgreSQL 15 or later. This stack runs 16
-- (docker-compose.yml), and the migration runner will fail loudly on an older
-- server rather than silently applying weaker uniqueness — which is the right
-- failure for a constraint that is part of a control (C-02).
--
-- Migration 0005 is applied and its checksum is recorded, so it is immutable:
-- editing it would abort start-up rather than fix anything
-- (docs/ADR/0009-forward-only-sql-migrations.md). The correction lands here.

alter table approver_limit
  drop constraint approver_limit_pkey;

-- Dropping the primary key does **not** drop the NOT NULL it implied.
-- PostgreSQL leaves those marks behind, so without this line the column stays
-- NOT NULL and the wildcard row still fails to insert — the constraint swap
-- alone fixes nothing. Verified against a live server rather than assumed;
-- the first draft of this migration omitted it and the wildcard insert still
-- raised `null value in column "currency" ... violates not-null constraint`.
alter table approver_limit
  alter column currency drop not null;

alter table approver_limit
  add constraint approver_limit_key unique nulls not distinct (actor_id, currency);

comment on constraint approver_limit_key on approver_limit is
  'At most one ceiling per (actor, currency), and — because nulls are treated '
  'as equal here — at most one wildcard row per actor. Two contradictory '
  '"applies to every currency" ceilings would leave no rule for which wins.';

-- Replaces the comment 0005 carried, which described the column as unbuildable.
-- That was accurate when it was written and is not any more; a stale comment
-- describing a *control* is worth a migration to correct, exactly as 0004 did
-- for the idempotency digest's scope.
comment on column approver_limit.currency is
  'The currency this ceiling applies to. NULL means every currency — a '
  'conservative fallback, since it compares integer minor units across '
  'currencies without conversion, so an organisation paying in several should '
  'prefer per-currency rows. A currency-specific row always wins over the '
  'wildcard for that currency; see clofin.authz.approval/limit-for. Absent '
  'means zero, never unlimited: an approver with no row for a currency has no '
  'authority in it. Thresholds and limits are never normalised through an '
  'exchange rate — see '
  'docs/ADR/0015-approval-thresholds-are-per-currency.md.';
