-- Close TRUNCATE on every append-only table.
--
-- FINDING F-002, Milestone 1 external audit, reproduced by Master Control and
-- again here before this file was written:
--
--   clofin=> truncate audit_event;
--   TRUNCATE TABLE
--   clofin=> select count(*) from audit_event;
--    0
--
-- No error. The `audit_event_append_only` trigger refused the `UPDATE` and the
-- `DELETE` immediately before, exactly as designed, and then the whole table
-- was emptied past it.
--
-- The cause is not a bug in the trigger. It is that PostgreSQL treats TRUNCATE
-- as its own trigger event with its own privilege, and migrations 0002 and 0005
-- enumerated only `update or delete`. An append-only guarantee stated over a
-- partial verb set is not an append-only guarantee — it is a guarantee about
-- the two verbs somebody happened to think of. Recorded as standing lesson L-5.
--
-- TRUNCATE triggers are necessarily `for each statement`: TRUNCATE does not
-- visit rows, which is exactly why it outran a `for each row` guard.
-- `reject_mutation()` is reused unchanged — it reads `tg_table_name` and
-- `lower(tg_op)` and touches neither NEW nor OLD, so it is already safe at
-- statement level and renders "... never by truncate". Verified against a live
-- PostgreSQL 16 on a scratch table before this migration was written, per
-- lesson L-3: `TRUNCATE` and `TRUNCATE ... CASCADE` both refuse.
--
-- ---------------------------------------------------------------------------
-- What this does NOT close, stated here because a control described without
-- its boundary is a control nobody can rely on
-- ---------------------------------------------------------------------------
--
-- **Triggers do not bind the table's owner.** Whoever owns these tables can
-- `ALTER TABLE ... DISABLE TRIGGER`, `DROP TRIGGER` or `DROP TABLE`, and no
-- trigger can prevent it — a trigger is enforced by the table, and the owner
-- decides what the table is. CloFin currently connects as the owning role, so
-- today the guarantee holds against the *application*, against a defect in it,
-- and against a maintenance session that does not deliberately disarm the
-- guard. It does not hold against an adversary who has the owner's
-- credentials and means to use them.
--
-- The fix is a runtime role split — the application connects as a role that is
-- not the owner, with TRUNCATE and DDL revoked — which migration 0002 already
-- foreshadowed and which is not built. It is named debt in COMPLIANCE §4
-- rather than quietly absent, and `clofin.db.audit-constraints-test` asserts
-- the residue explicitly so that nobody reads the passing verb tests as
-- proving more than they do.

create trigger journal_entry_no_truncate
  before truncate on journal_entry
  for each statement execute function reject_mutation();

create trigger journal_line_no_truncate
  before truncate on journal_line
  for each statement execute function reject_mutation();

create trigger audit_event_no_truncate
  before truncate on audit_event
  for each statement execute function reject_mutation();

-- `approval` permits UPDATE — that is how PR-014 invalidates an approval — and
-- forbids DELETE. TRUNCATE belongs with DELETE: it destroys decisions, and a
-- decision that can disappear is a decision nobody can prove was taken.
create trigger approval_no_truncate
  before truncate on approval
  for each statement execute function reject_mutation();

comment on trigger journal_entry_no_truncate on journal_entry is
  'TRUNCATE is a distinct verb with its own trigger event; a `for each row` '
  'guard on UPDATE and DELETE does not see it (audit finding F-002). Statement '
  'level because TRUNCATE visits no rows.';

comment on trigger audit_event_no_truncate on audit_event is
  'See journal_entry_no_truncate. The audit trail is the table where this gap '
  'mattered most: it was emptied in one statement past a guard that had just '
  'refused an UPDATE and a DELETE on the same row.';
