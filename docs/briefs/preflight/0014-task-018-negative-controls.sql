begin;
alter table payment_instruction
  add column client_reference text    null,
  add column creditor_country char(2) null;
alter table payment_instruction
  add constraint payment_client_reference_shape
    check (client_reference is null or client_reference ~ '^[\x21-\x7E]{1,128}$'),
  add constraint payment_creditor_country_shape
    check (creditor_country is null or creditor_country ~ '^[A-Z]{2}$');
create unique index payment_instruction_client_reference_key
  on payment_instruction (organisation_id, client_reference)
  where client_reference is not null;
alter table idempotency_key add column operation_id text null;

insert into payment_instruction
  (id, organisation_id, debtor_account_id, creditor_name, creditor_account, amount_minor, currency,
   value_date, purpose_code, status, created_by, client_reference, creditor_country)
select '0000aaaa-0000-4000-8000-00000000a018', organisation_id, debtor_account_id,
       'Pre-flight creditor', 'SG-SYNTH-00000018', 1000, currency, value_date, purpose_code,
       'draft', created_by, 'agent-ref-0001', 'SG'
  from payment_instruction limit 1;

-- N-a: a reference with a space is refused at insert
savepoint na;
insert into payment_instruction
  (id, organisation_id, debtor_account_id, creditor_name, creditor_account, amount_minor, currency,
   value_date, purpose_code, status, created_by, client_reference)
select '0000aaaa-0000-4000-8000-00000000d018', organisation_id, debtor_account_id,
       'Pre-flight creditor', 'SG-SYNTH-00000021', 1000, currency, value_date, purpose_code,
       'draft', created_by, 'has space'
  from payment_instruction where id = '0000aaaa-0000-4000-8000-00000000a018';
rollback to savepoint na;
-- N-b: 129 characters refused
savepoint nb;
insert into payment_instruction
  (id, organisation_id, debtor_account_id, creditor_name, creditor_account, amount_minor, currency,
   value_date, purpose_code, status, created_by, client_reference)
select '0000aaaa-0000-4000-8000-00000000e018', organisation_id, debtor_account_id,
       'Pre-flight creditor', 'SG-SYNTH-00000022', 1000, currency, value_date, purpose_code,
       'draft', created_by, repeat('x', 129)
  from payment_instruction where id = '0000aaaa-0000-4000-8000-00000000a018';
rollback to savepoint nb;
-- P-c: the same reference in ANOTHER organisation is permitted (scope is the organisation)
insert into organisation (id, legal_name, short_name) values ('0000aaaa-0000-4000-8000-00000000f018', 'Pre-flight Org', 'PRE018');
select count(*) as other_org_ref_allowed from (
  select 1) s;
-- a ledger account in that org is needed for the FK; use a bare insert of the minimum columns
\d ledger_account
rollback;
