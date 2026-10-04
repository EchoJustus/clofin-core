begin;
alter table settlement_batch drop constraint settlement_scheme_known;
alter table settlement_batch add constraint settlement_scheme_known check (scheme in ('SIM-RTGS','SIM-ACH','SIM-EVM'));
create table chain_confirmation (
  id uuid primary key, organisation_id uuid not null references organisation (id),
  instruction_id uuid not null references payment_instruction (id),
  settlement_batch_id uuid not null references settlement_batch (id),
  scheme_response_id uuid not null references scheme_response (id),
  journal_entry_id uuid not null references journal_entry (id),
  chain_id text not null, transaction_hash text not null, log_index numeric(20,0) not null, kind text not null,
  canonical_digest text not null, event_json text not null, source_id text not null, attestation_id text not null,
  recorded_by uuid not null references actor (id), recorded_at timestamptz not null default now(),
  constraint chain_confirmation_identity_key unique (organisation_id, chain_id, transaction_hash, log_index),
  constraint chain_confirmation_response_key unique (scheme_response_id),
  constraint chain_confirmation_membership_fk
    foreign key (settlement_batch_id, instruction_id) references settlement_batch_item (batch_id, instruction_id));
create temporary table pf as
  select sr.id as response_id, sr.batch_id, sr.instruction_id, b.organisation_id, b.created_by,
         (select id from journal_entry je where je.organisation_id = b.organisation_id limit 1) as entry_id
    from scheme_response sr join settlement_batch b on b.id = sr.batch_id
   where sr.disposition = 'applied' and sr.kind = 'settled' limit 1;
insert into settlement_batch (id, organisation_id, scheme, currency, value_date, created_by)
  select '0000dddd-0000-4000-8000-000000000001', organisation_id, 'SIM-EVM', 'SGD', date '2026-12-01', created_by from pf;
-- N-7, isolated: a fresh response id, so only the membership foreign key can answer
insert into chain_confirmation (id, organisation_id, instruction_id, settlement_batch_id, scheme_response_id, journal_entry_id,
  chain_id, transaction_hash, log_index, kind, canonical_digest, event_json, source_id, attestation_id, recorded_by)
select '0000eeee-0000-4000-8000-000000000007', organisation_id, instruction_id, '0000dddd-0000-4000-8000-000000000001', response_id, entry_id,
  'eip155:31337', '0x' || repeat('5', 64), 0, 'settled', repeat('0', 64), '{}', 's', 'a', created_by from pf;
rollback;
