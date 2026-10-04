begin;
-- Before you start, as UAT-007 states it (an organisation the capture database holds)
create temporary table pf as select organisation_id as org from reconciliation_adjustment where status = 'posted' limit 1;
delete from approval_threshold where organisation_id = (select org from pf) and currency = 'SGD';
insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
values ((select org from pf), 'SGD', 100000, 1);
select from_minor, approvals_required from approval_threshold where organisation_id = (select org from pf) and currency = 'SGD' order by from_minor;
-- Step 10: remove the bands, then restore them with the same statement
delete from approval_threshold where organisation_id = (select org from pf);
insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
values ((select org from pf), 'SGD', 100000, 1);
-- Step 11: the raw insert with every placeholder substituted (a posted adjustment's break, an existing entry, its proposer)
savepoint s11;
insert into reconciliation_adjustment
  (id, organisation_id, break_id, amount_minor, currency, direction, narrative,
   status, approvals_required, entry_id, posted_at, created_by)
select gen_random_uuid(), organisation_id, break_id, 100, 'SGD', 'credit', 'by hand',
       'posted', 0, entry_id, now(), created_by
  from reconciliation_adjustment where status = 'posted' limit 1;
rollback to savepoint s11;
-- Step 13: the raw update of a retry link (the capture holds one retry)
savepoint s13;
update payment_instruction set retries_id = null where retries_id is not null;
rollback to savepoint s13;
-- Step 15, each in its own savepoint
savepoint s15a; update reconciliation_match set rule_id = 'R3-reference-only'; rollback to savepoint s15a;
savepoint s15b; delete from reconciliation_statement_line; rollback to savepoint s15b;
savepoint s15c; truncate reconciliation_statement; rollback to savepoint s15c;
savepoint s15d; truncate reconciliation_statement cascade; rollback to savepoint s15d;
rollback;
