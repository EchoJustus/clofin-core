-- TASK-017 pre-flight: screening lists, results, cases; the screening-service role
begin;

alter table actor_role drop constraint role_known;
alter table actor_role add constraint role_known
  check (role in ('operator','approver','controller','compliance','auditor','screening-service'));

create table screening_list (
  version     text        primary key,
  source      text        not null,
  loaded_at   timestamptz not null default now(),
  retired_at  timestamptz null,
  entry_count integer     not null,
  constraint screening_list_version_shape      check (version ~ '^[\x21-\x7E]{1,128}$'),
  constraint screening_list_source_present     check (length(btrim(source)) > 0),
  constraint screening_list_entry_count_nonneg check (entry_count >= 0)
);

create table screening_entry (
  list_version text not null references screening_list (version),
  id           text not null,
  primary key (list_version, id),
  constraint screening_entry_id_shape check (id ~ '^[\x21-\x7E]{1,128}$')
);

create table screening_rule (
  list_version text    not null,
  entry_id     text    not null,
  position     integer not null,
  field        text    not null,
  operator     text    not null,
  value        text    not null,
  primary key (list_version, entry_id, position),
  foreign key (list_version, entry_id) references screening_entry (list_version, id),
  constraint screening_rule_field_known     check (field in ('creditor-name','creditor-account','creditor-country')),
  constraint screening_rule_operator_known  check (operator in ('exact')),
  constraint screening_rule_value_present   check (length(btrim(value)) > 0),
  constraint screening_rule_position_nonneg check (position >= 0)
);

create table screening_result (
  id                 uuid        primary key,
  organisation_id    uuid        not null references organisation (id),
  instruction_id     uuid        not null references payment_instruction (id),
  list_version       text        not null references screening_list (version),
  origin             text        not null,
  outcome            text        not null,
  core_outcome       text        not null,
  agrees             boolean     not null,
  instruction_digest text        not null,
  disposition        text        not null,
  disposition_reason text        null,
  recorded_by        uuid        not null references actor (id),
  recorded_at        timestamptz not null default now(),
  screened_at        timestamptz null,
  constraint screening_result_origin_known        check (origin in ('core','client')),
  constraint screening_result_outcome_known       check (outcome in ('clear','hit')),
  constraint screening_result_core_outcome_known  check (core_outcome in ('clear','hit')),
  constraint screening_result_disposition_known   check (disposition in ('accepted','refused')),
  constraint screening_result_refusal_reason_known
    check (disposition_reason is null or disposition_reason in ('screening-result-mismatch')),
  constraint screening_result_refusal_needs_reason
    check ((disposition = 'refused') = (disposition_reason is not null)),
  constraint screening_result_digest_shape        check (instruction_digest ~ '^[0-9a-f]{64}$'),
  constraint screening_result_core_agrees_with_itself
    check (origin <> 'core' or (outcome = core_outcome and agrees and disposition = 'accepted')),
  constraint screening_result_agreement_is_derived
    check (agrees = (outcome = core_outcome) or origin = 'client')
);
create index screening_result_instruction_idx on screening_result (instruction_id, recorded_at desc);

create table screening_result_match (
  result_id    uuid not null references screening_result (id),
  side         text not null,
  list_version text not null,
  entry_id     text not null,
  primary key (result_id, side, entry_id),
  foreign key (list_version, entry_id) references screening_entry (list_version, id),
  constraint screening_result_match_side_known check (side in ('submitted','core'))
);

create table screening_case (
  id                 uuid        primary key,
  organisation_id    uuid        not null references organisation (id),
  instruction_id     uuid        not null references payment_instruction (id),
  result_id          uuid        not null references screening_result (id),
  instruction_digest text        not null,
  list_version       text        not null references screening_list (version),
  status             text        not null default 'open',
  disposition        text        null,
  rationale          text        null,
  dispositioned_by   uuid        null references actor (id),
  opened_at          timestamptz not null default now(),
  dispositioned_at   timestamptz null,
  constraint screening_case_status_known      check (status in ('open','dispositioned')),
  constraint screening_case_disposition_known check (disposition is null or disposition in ('false-positive','confirmed-hit')),
  constraint screening_case_digest_shape      check (instruction_digest ~ '^[0-9a-f]{64}$'),
  constraint screening_case_disposition_complete check (
       (status = 'open' and disposition is null and rationale is null
        and dispositioned_by is null and dispositioned_at is null)
    or (status = 'dispositioned' and disposition is not null
        and length(btrim(coalesce(rationale,''))) > 0
        and dispositioned_by is not null and dispositioned_at is not null))
);
create unique index screening_case_open_key on screening_case (instruction_id) where status = 'open';

-- append-only evidence: lists (except retirement), entries, rules, results, matches; cases are
-- dispositioned once and never deleted
create function reject_unless_retiring_list() returns trigger as $$
begin
  if old.retired_at is null and new.retired_at is not null
     and new.version = old.version and new.source = old.source
     and new.loaded_at = old.loaded_at and new.entry_count = old.entry_count then
    return new;
  end if;
  raise exception
    'screening_list %: a list version is immutable once loaded; the only change it admits is retirement, once',
    old.version using errcode = 'integrity_constraint_violation';
end;
$$ language plpgsql;
create trigger screening_list_retire_only before update on screening_list
  for each row execute function reject_unless_retiring_list();
create trigger screening_list_append_only before delete on screening_list
  for each row execute function reject_mutation();
create trigger screening_list_no_truncate before truncate on screening_list
  for each statement execute function reject_mutation();
create trigger screening_entry_append_only before update or delete on screening_entry
  for each row execute function reject_mutation();
create trigger screening_entry_no_truncate before truncate on screening_entry
  for each statement execute function reject_mutation();
create trigger screening_rule_append_only before update or delete on screening_rule
  for each row execute function reject_mutation();
create trigger screening_rule_no_truncate before truncate on screening_rule
  for each statement execute function reject_mutation();
create trigger screening_result_append_only before update or delete on screening_result
  for each row execute function reject_mutation();
create trigger screening_result_no_truncate before truncate on screening_result
  for each statement execute function reject_mutation();
create trigger screening_result_match_append_only before update or delete on screening_result_match
  for each row execute function reject_mutation();
create trigger screening_result_match_no_truncate before truncate on screening_result_match
  for each statement execute function reject_mutation();
create function reject_redisposition() returns trigger as $$
begin
  raise exception
    'screening_case % is dispositioned and a disposition is final: a second one is a new case',
    old.id using errcode = 'integrity_constraint_violation';
end;
$$ language plpgsql;
create trigger screening_case_disposition_final before update on screening_case
  for each row when (old.status = 'dispositioned') execute function reject_redisposition();
create trigger screening_case_append_only before delete on screening_case
  for each row execute function reject_mutation();
create trigger screening_case_no_truncate before truncate on screening_case
  for each statement execute function reject_mutation();

-- ---- one row of every documented shape ----
insert into screening_list (version, source, entry_count)
  values ('synthetic-2026-10-v1', 'resources/screening-lists/synthetic-2026-10-v1.edn', 2);
insert into screening_entry values ('synthetic-2026-10-v1', 'SYN-0001'), ('synthetic-2026-10-v1', 'SYN-0002');
insert into screening_rule values
  ('synthetic-2026-10-v1', 'SYN-0001', 0, 'creditor-name',    'exact', 'Blocked Counterparty Ltd'),
  ('synthetic-2026-10-v1', 'SYN-0002', 0, 'creditor-account', 'exact', 'SG-SYNTH-99999999'),
  ('synthetic-2026-10-v1', 'SYN-0002', 1, 'creditor-country', 'exact', 'ZZ');
insert into actor_role (actor_id, role) select id, 'screening-service' from actor limit 1;

-- core's own clear decision
insert into screening_result (id, organisation_id, instruction_id, list_version, origin, outcome, core_outcome,
  agrees, instruction_digest, disposition, recorded_by)
select '0000bbbb-0000-4000-8000-000000000001', organisation_id, id, 'synthetic-2026-10-v1', 'core', 'clear', 'clear',
  true, repeat('a', 64), 'accepted', created_by from payment_instruction limit 1;
-- a client's hit that core agrees with
insert into screening_result (id, organisation_id, instruction_id, list_version, origin, outcome, core_outcome,
  agrees, instruction_digest, disposition, recorded_by, screened_at)
select '0000bbbb-0000-4000-8000-000000000002', organisation_id, id, 'synthetic-2026-10-v1', 'client', 'hit', 'hit',
  true, repeat('b', 64), 'accepted', created_by, now() from payment_instruction limit 1;
insert into screening_result_match values
  ('0000bbbb-0000-4000-8000-000000000002', 'submitted', 'synthetic-2026-10-v1', 'SYN-0001'),
  ('0000bbbb-0000-4000-8000-000000000002', 'core',      'synthetic-2026-10-v1', 'SYN-0001');
-- a client's result core could not reproduce: recorded, refused
insert into screening_result (id, organisation_id, instruction_id, list_version, origin, outcome, core_outcome,
  agrees, instruction_digest, disposition, disposition_reason, recorded_by)
select '0000bbbb-0000-4000-8000-000000000003', organisation_id, id, 'synthetic-2026-10-v1', 'client', 'clear', 'hit',
  false, repeat('b', 64), 'refused', 'screening-result-mismatch', created_by from payment_instruction limit 1;
-- an open case on the hit, then dispositioned
insert into screening_case (id, organisation_id, instruction_id, result_id, instruction_digest, list_version)
select '0000cccc-0000-4000-8000-000000000001', organisation_id, instruction_id, id, instruction_digest, list_version
  from screening_result where id = '0000bbbb-0000-4000-8000-000000000002';
update screening_case set status = 'dispositioned', disposition = 'false-positive',
  rationale = 'Name collision with a synthetic entry; counterparty verified', dispositioned_by = recorded_by_x.actor,
  dispositioned_at = now()
  from (select recorded_by as actor from screening_result where id = '0000bbbb-0000-4000-8000-000000000002') recorded_by_x
  where id = '0000cccc-0000-4000-8000-000000000001';
select status, disposition from screening_case;
-- retirement, once
update screening_list set retired_at = now() where version = 'synthetic-2026-10-v1';
select version, retired_at is not null as retired from screening_list;

-- ---- negative controls ----
savepoint n1;  -- originator-* rule field
insert into screening_rule values ('synthetic-2026-10-v1', 'SYN-0001', 1, 'originator-name', 'exact', 'x');
rollback to savepoint n1;
savepoint n2;  -- a second open case on one instruction
insert into screening_case (id, organisation_id, instruction_id, result_id, instruction_digest, list_version)
select '0000cccc-0000-4000-8000-000000000002', organisation_id, instruction_id, id, instruction_digest, list_version
  from screening_result where id = '0000bbbb-0000-4000-8000-000000000002';
insert into screening_case (id, organisation_id, instruction_id, result_id, instruction_digest, list_version)
select '0000cccc-0000-4000-8000-000000000003', organisation_id, instruction_id, id, instruction_digest, list_version
  from screening_result where id = '0000bbbb-0000-4000-8000-000000000002';
rollback to savepoint n2;
savepoint n3;  -- re-disposition
update screening_case set rationale = 'changed my mind' where id = '0000cccc-0000-4000-8000-000000000001';
rollback to savepoint n3;
savepoint n4;  -- a disposition without a rationale
insert into screening_case (id, organisation_id, instruction_id, result_id, instruction_digest, list_version, status, disposition, dispositioned_by, dispositioned_at)
select '0000cccc-0000-4000-8000-000000000004', organisation_id, instruction_id, id, instruction_digest, list_version, 'dispositioned', 'confirmed-hit', recorded_by, now()
  from screening_result where id = '0000bbbb-0000-4000-8000-000000000002';
rollback to savepoint n4;
savepoint n5;  -- rewriting a result
update screening_result set outcome = 'clear' where id = '0000bbbb-0000-4000-8000-000000000002';
rollback to savepoint n5;
savepoint n6;  -- a core result that disagrees with itself
insert into screening_result (id, organisation_id, instruction_id, list_version, origin, outcome, core_outcome,
  agrees, instruction_digest, disposition, recorded_by)
select '0000bbbb-0000-4000-8000-000000000009', organisation_id, id, 'synthetic-2026-10-v1', 'core', 'clear', 'hit',
  false, repeat('c', 64), 'accepted', created_by from payment_instruction limit 1;
rollback to savepoint n6;
savepoint n7;  -- a second retirement, and an edit to a retired list
update screening_list set retired_at = now() where version = 'synthetic-2026-10-v1';
rollback to savepoint n7;
savepoint n8;
update screening_list set entry_count = 3 where version = 'synthetic-2026-10-v1';
rollback to savepoint n8;
savepoint n9;  -- deleting evidence
delete from screening_entry where id = 'SYN-0001';
rollback to savepoint n9;
savepoint n10; -- truncate
truncate screening_result cascade;
rollback to savepoint n10;
savepoint n11; -- a role the constraint does not know
insert into actor_role (actor_id, role) select id, 'superuser' from actor limit 1;
rollback to savepoint n11;
savepoint n12; -- a refused result with no reason
insert into screening_result (id, organisation_id, instruction_id, list_version, origin, outcome, core_outcome,
  agrees, instruction_digest, disposition, recorded_by)
select '0000bbbb-0000-4000-8000-000000000008', organisation_id, id, 'synthetic-2026-10-v1', 'client', 'clear', 'hit',
  false, repeat('d', 64), 'refused', created_by from payment_instruction limit 1;
rollback to savepoint n12;

select 'vocabularies' as check, count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid
 where t.relname like 'screening%' and pg_get_constraintdef(c.oid) like '%= ANY (ARRAY[%';
rollback;
