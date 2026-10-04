-- TASK-018 pre-flight: client reference, creditor country, idempotency operation id
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

create function reject_client_reference_change() returns trigger as $$
begin
  raise exception
    'payment_instruction.client_reference is set when the instruction is created and never changes: '
    'it is the identity a client binds its own record to (ADR-0028 D6), and an identity that can be '
    'rewritten is one an investigation cannot follow'
    using errcode = 'integrity_constraint_violation';
end;
$$ language plpgsql;

create trigger payment_instruction_client_reference_immutable
  before update of client_reference on payment_instruction
  for each row
  when (new.client_reference is distinct from old.client_reference)
  execute function reject_client_reference_change();

alter table idempotency_key
  add column operation_id text null;

comment on column idempotency_key.operation_id is
  'The OpenAPI operationId the key was bound by, written on the same insert as the key. '
  'Null only on a row written before migration 0014; the lookup treats null as an '
  'operation it does not serve, never as an absent binding (ADR-0028 D6).';

-- one row of every documented shape
insert into payment_instruction
  (id, organisation_id, debtor_account_id, creditor_name, creditor_account, amount_minor, currency,
   value_date, purpose_code, status, created_by, client_reference, creditor_country)
select '0000aaaa-0000-4000-8000-00000000a018', organisation_id, debtor_account_id,
       'Pre-flight creditor', 'SG-SYNTH-00000018', 1000, currency, value_date, purpose_code,
       'draft', created_by, 'agent-ref-0001', 'SG'
  from payment_instruction limit 1;
-- an instruction with neither member is still valid
insert into payment_instruction
  (id, organisation_id, debtor_account_id, creditor_name, creditor_account, amount_minor, currency,
   value_date, purpose_code, status, created_by)
select '0000aaaa-0000-4000-8000-00000000b018', organisation_id, debtor_account_id,
       'Pre-flight creditor', 'SG-SYNTH-00000019', 1000, currency, value_date, purpose_code,
       'draft', created_by
  from payment_instruction limit 1;
select id, client_reference, creditor_country from payment_instruction where id::text like '0000aaaa-%';

-- negative controls
savepoint n1;
insert into payment_instruction
  (id, organisation_id, debtor_account_id, creditor_name, creditor_account, amount_minor, currency,
   value_date, purpose_code, status, created_by, client_reference)
select '0000aaaa-0000-4000-8000-00000000c018', organisation_id, debtor_account_id,
       'Pre-flight creditor', 'SG-SYNTH-00000020', 1000, currency, value_date, purpose_code,
       'draft', created_by, 'agent-ref-0001'
  from payment_instruction where id = '0000aaaa-0000-4000-8000-00000000a018';
rollback to savepoint n1;
savepoint n2;
update payment_instruction set client_reference = 'agent-ref-0002' where id = '0000aaaa-0000-4000-8000-00000000a018';
rollback to savepoint n2;
savepoint n3;
update payment_instruction set creditor_country = 'sg' where id = '0000aaaa-0000-4000-8000-00000000a018';
rollback to savepoint n3;
savepoint n4;
update payment_instruction set client_reference = 'has space' where id = '0000aaaa-0000-4000-8000-00000000b018';
rollback to savepoint n4;
savepoint n5;
insert into idempotency_key (organisation_id, key, request_digest, response_status, response_body, operation_id)
select organisation_id, 'preflight-key-018', 'v1:deadbeef', 201, '{}', 'createPaymentInstruction' from organisation limit 1;
select key, operation_id from idempotency_key where key = 'preflight-key-018';
rollback to savepoint n5;

rollback;
