-- A journal entry must have at least two lines, checked at commit.
--
-- FINDING F-003, Milestone 1 external audit, reproduced before this file was
-- written:
--
--   clofin=> begin;
--   clofin=> insert into journal_entry (...) values (...);   -- and no lines
--   clofin=> commit;
--   COMMIT
--   entries | lines
--         1 |     0
--
-- `ADR-0008` requires two or more lines and `clofin.ledger.entry/entry`
-- enforces it, but the database backstop did not — and the reason is worth
-- understanding rather than patching around. The zero-sum trigger from 0002 is
-- declared `after insert on journal_line`. An entry with no lines inserts no
-- lines, so it queues no deferred check, so nothing fires. The guard was
-- attached to the rows whose absence was the defect.
--
-- That is C-04's stated design failing exactly where ADR-0008 says it must not:
-- "a defect in the application must not be able to commit an unbalanced entry."
-- A zero-line entry moves no money, but it is an immutable accounting record
-- that violates the documented model and cannot be corrected through the
-- reversing-entry path, because there is nothing to reverse.
--
-- ---------------------------------------------------------------------------
-- Why an ENTRY-level trigger, and why it checks balance too
-- ---------------------------------------------------------------------------
--
-- The check has to hang off the row that always exists: the entry. `for each
-- row` on `journal_entry`, `deferrable initially deferred`, so the lines may
-- still be inserted one at a time and the entry may be transiently unbalanced
-- mid-transaction — which `clofin.ledger.repository/post-entry!` relies on and
-- `the-constraint-is-deferred-so-lines-may-be-inserted-one-at-a-time` asserts.
--
-- It re-checks balance as well as cardinality, deliberately. The line-level
-- trigger from 0002 stays exactly as it is: two guards over the same invariant
-- from two directions is the same doubling ADR-0008 already argues for between
-- the domain constructor and the database, and here it costs one query on a
-- path that already does several. The line-level trigger catches an entry
-- whose lines do not balance; this one catches an entry whose lines are absent
-- — and neither can be reached by the other's route.
--
-- The balance message is **deliberately identical** to
-- `assert_journal_entry_balanced()`'s, down to the punctuation. Both triggers
-- are deferred, both fire at commit, and the entry's event is queued first, so
-- this one reports an unbalanced entry in practice. A caller must not be able
-- to tell which guard caught it — the fact being reported is the same fact,
-- and `clofin.db.ledger-constraints-test` matches on that wording.
--
-- Verified against a live PostgreSQL 16 on scratch tables before this file was
-- written (lesson L-3): zero lines refused, one line refused, two balanced
-- lines commit, an unbalanced pair is refused with the 0002 wording,
-- per-currency balancing across two currencies commits, and lines inserted one
-- at a time still commit.

create function assert_journal_entry_complete() returns trigger as $$
declare
  line_count integer;
  offending  record;
begin
  select count(*) into line_count
    from journal_line l
   where l.entry_id = new.id;

  if line_count < 2 then
    raise exception
      'Journal entry % has % line(s): a double-entry record needs at least two',
      new.id, line_count
      using errcode = 'integrity_constraint_violation';
  end if;

  select l.currency,
         sum(case when l.direction = 'debit'  then l.amount_minor else 0 end) as debits,
         sum(case when l.direction = 'credit' then l.amount_minor else 0 end) as credits
    into offending
    from journal_line l
   where l.entry_id = new.id
   group by l.currency
  having sum(case when l.direction = 'debit'  then l.amount_minor else 0 end)
       <> sum(case when l.direction = 'credit' then l.amount_minor else 0 end)
   limit 1;

  if found then
    raise exception
      'Journal entry % does not balance in %: debits %, credits %',
      new.id, offending.currency, offending.debits, offending.credits
      using errcode = 'integrity_constraint_violation';
  end if;

  return null;
end;
$$ language plpgsql;

comment on function assert_journal_entry_complete() is
  'Deferred to commit, and attached to the entry rather than to its lines: a '
  'guard on journal_line cannot see an entry that has none, which is how a '
  'zero-line entry committed past every check (audit finding F-003). Verifies '
  'line cardinality and, redundantly with assert_journal_entry_balanced(), '
  'per-currency balance — the two catch different absences and neither is '
  'reachable by the other''s route.';

create constraint trigger journal_entry_must_be_complete
  after insert on journal_entry
  deferrable initially deferred
  for each row
  execute function assert_journal_entry_complete();

comment on trigger journal_entry_must_be_complete on journal_entry is
  'At least two lines, balancing per currency, by commit time. Entries already '
  'committed are not revisited — a deferred constraint trigger fires on the '
  'statement that queues it — so this binds every entry written from now on '
  'and does not retroactively invalidate history.';
