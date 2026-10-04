-- TASK-019 pre-flight: SIM-EVM, the token registry, chain confirmations, the settlement-feed role
begin;

alter table settlement_batch drop constraint settlement_scheme_known;
alter table settlement_batch add constraint settlement_scheme_known
  check (scheme in ('SIM-RTGS','SIM-ACH','SIM-EVM'));

alter table actor_role drop constraint role_known;
alter table actor_role add constraint role_known
  check (role in ('operator','approver','controller','compliance','auditor','screening-service','settlement-feed'));

create table token_registry (
  chain_id       text    not null,
  token_contract text    not null,
  symbol         text    not null,
  currency       char(3) not null references currency (code),
  decimals       integer not null,
  primary key (chain_id, token_contract),
  constraint token_registry_chain_known     check (chain_id in ('eip155:31337')),
  constraint token_registry_contract_shape  check (token_contract ~ '^0x[0-9a-f]{40}$'),
  constraint token_registry_decimals_range  check (decimals between 0 and 36),
  constraint token_registry_symbol_synthetic check (symbol ~ '^SIM-[A-Z0-9]{2,10}$')
);
insert into token_registry (chain_id, token_contract, symbol, currency, decimals)
  values ('eip155:31337', '0x5f1d4a0c0e8b2f9a7c3d6e1b4a8c2d9e0f1a2b3c', 'SIM-SGD', 'SGD', 6);

create table chain_confirmation (
  id                  uuid          primary key,
  organisation_id     uuid          not null references organisation (id),
  instruction_id      uuid          not null references payment_instruction (id),
  settlement_batch_id uuid          not null references settlement_batch (id),
  scheme_response_id  uuid          not null references scheme_response (id),
  journal_entry_id    uuid          not null references journal_entry (id),
  chain_id            text          not null,
  transaction_hash    text          not null,
  log_index           numeric(20,0) not null,
  kind                text          not null,
  canonical_digest    text          not null,
  event_json          text          not null,
  source_id           text          not null,
  attestation_id      text          not null,
  recorded_by         uuid          not null references actor (id),
  recorded_at         timestamptz   not null default now(),
  constraint chain_confirmation_identity_key unique (organisation_id, chain_id, transaction_hash, log_index),
  constraint chain_confirmation_response_key unique (scheme_response_id),
  constraint chain_confirmation_membership_fk
    foreign key (settlement_batch_id, instruction_id) references settlement_batch_item (batch_id, instruction_id),
  constraint chain_confirmation_kind_known       check (kind in ('settled','returned')),
  constraint chain_confirmation_chain_known      check (chain_id in ('eip155:31337')),
  constraint chain_confirmation_tx_hash_shape    check (transaction_hash ~ '^0x[0-9a-f]{64}$'),
  constraint chain_confirmation_log_index_range  check (log_index >= 0 and log_index <= 18446744073709551615),
  constraint chain_confirmation_digest_shape     check (canonical_digest ~ '^[0-9a-f]{64}$'),
  constraint chain_confirmation_event_present    check (length(event_json) > 0),
  constraint chain_confirmation_source_shape     check (length(source_id) between 1 and 128),
  constraint chain_confirmation_attestation_shape check (length(attestation_id) between 1 and 256)
);
create index chain_confirmation_instruction_idx on chain_confirmation (instruction_id, recorded_at);
create trigger chain_confirmation_append_only before update or delete on chain_confirmation
  for each row execute function reject_mutation();
create trigger chain_confirmation_no_truncate before truncate on chain_confirmation
  for each statement execute function reject_mutation();
create trigger token_registry_append_only before update or delete on token_registry
  for each row execute function reject_mutation();
create trigger token_registry_no_truncate before truncate on token_registry
  for each statement execute function reject_mutation();

-- ---- one row of every documented shape, bound to an applied settlement the capture database holds ----
create temporary table pf as
  select sr.id as response_id, sr.batch_id, sr.instruction_id, b.organisation_id, b.created_by,
         (select id from journal_entry je where je.organisation_id = b.organisation_id limit 1) as entry_id
    from scheme_response sr join settlement_batch b on b.id = sr.batch_id
   where sr.disposition = 'applied' and sr.kind = 'settled' limit 1;
select count(*) as fixture_rows from pf;
insert into actor_role (actor_id, role) select created_by, 'settlement-feed' from pf;
insert into settlement_batch (id, organisation_id, scheme, currency, value_date, created_by)
  select '0000dddd-0000-4000-8000-000000000001', organisation_id, 'SIM-EVM', 'SGD', date '2026-12-01', created_by from pf;
insert into chain_confirmation (id, organisation_id, instruction_id, settlement_batch_id, scheme_response_id, journal_entry_id,
  chain_id, transaction_hash, log_index, kind, canonical_digest, event_json, source_id, attestation_id, recorded_by)
select '0000eeee-0000-4000-8000-000000000001', organisation_id, instruction_id, batch_id, response_id, entry_id,
  'eip155:31337', '0x' || repeat('1', 64), 0, 'settled',
  'e438c689af8f2226b113fda70d809b49fc10a901f247cb0e2c2cf100e070d036', '{"eventVersion":1}', 'test-chain-feed', 'test-attestation-001', created_by
  from pf;
select chain_id, transaction_hash, log_index, kind from chain_confirmation;

-- ---- negative controls ----
savepoint n1;  -- a public network, at both tables
insert into token_registry values ('eip155:1', '0x' || repeat('a', 40), 'SIM-USDC', 'USD', 6);
rollback to savepoint n1;
savepoint n2;
insert into chain_confirmation (id, organisation_id, instruction_id, settlement_batch_id, scheme_response_id, journal_entry_id,
  chain_id, transaction_hash, log_index, kind, canonical_digest, event_json, source_id, attestation_id, recorded_by)
select '0000eeee-0000-4000-8000-000000000002', organisation_id, instruction_id, batch_id, response_id, entry_id,
  'eip155:1', '0x' || repeat('2', 64), 0, 'settled', repeat('0', 64), '{}', 's', 'a', created_by from pf;
rollback to savepoint n2;
savepoint n3;  -- the same identity twice in one organisation (a different response id, to isolate the identity key)
insert into chain_confirmation (id, organisation_id, instruction_id, settlement_batch_id, scheme_response_id, journal_entry_id,
  chain_id, transaction_hash, log_index, kind, canonical_digest, event_json, source_id, attestation_id, recorded_by)
select '0000eeee-0000-4000-8000-000000000003', organisation_id, instruction_id, batch_id,
  (select id from scheme_response where id <> (select response_id from pf) and batch_id = (select batch_id from pf) limit 1), entry_id,
  'eip155:31337', '0x' || repeat('1', 64), 0, 'settled', repeat('0', 64), '{}', 's', 'a', created_by from pf;
rollback to savepoint n3;
savepoint n4;  -- a second confirmation on one scheme response
insert into chain_confirmation (id, organisation_id, instruction_id, settlement_batch_id, scheme_response_id, journal_entry_id,
  chain_id, transaction_hash, log_index, kind, canonical_digest, event_json, source_id, attestation_id, recorded_by)
select '0000eeee-0000-4000-8000-000000000004', organisation_id, instruction_id, batch_id, response_id, entry_id,
  'eip155:31337', '0x' || repeat('3', 64), 1, 'settled', repeat('0', 64), '{}', 's', 'a', created_by from pf;
rollback to savepoint n4;
savepoint n5;  -- uppercase hex
insert into chain_confirmation (id, organisation_id, instruction_id, settlement_batch_id, scheme_response_id, journal_entry_id,
  chain_id, transaction_hash, log_index, kind, canonical_digest, event_json, source_id, attestation_id, recorded_by)
select '0000eeee-0000-4000-8000-000000000005', organisation_id, instruction_id, batch_id, response_id, entry_id,
  'eip155:31337', '0x' || repeat('A', 64), 2, 'settled', repeat('0', 64), '{}', 's', 'a', created_by from pf;
rollback to savepoint n5;
savepoint n6;  -- log index past uint64
insert into chain_confirmation (id, organisation_id, instruction_id, settlement_batch_id, scheme_response_id, journal_entry_id,
  chain_id, transaction_hash, log_index, kind, canonical_digest, event_json, source_id, attestation_id, recorded_by)
select '0000eeee-0000-4000-8000-000000000006', organisation_id, instruction_id, batch_id, response_id, entry_id,
  'eip155:31337', '0x' || repeat('4', 64), 18446744073709551616, 'settled', repeat('0', 64), '{}', 's', 'a', created_by from pf;
rollback to savepoint n6;
savepoint n7;  -- a confirmation for an instruction that is not in that batch
insert into chain_confirmation (id, organisation_id, instruction_id, settlement_batch_id, scheme_response_id, journal_entry_id,
  chain_id, transaction_hash, log_index, kind, canonical_digest, event_json, source_id, attestation_id, recorded_by)
select '0000eeee-0000-4000-8000-000000000007', organisation_id, instruction_id, '0000dddd-0000-4000-8000-000000000001', response_id, entry_id,
  'eip155:31337', '0x' || repeat('5', 64), 0, 'settled', repeat('0', 64), '{}', 's', 'a', created_by from pf;
rollback to savepoint n7;
savepoint n8;  -- rewriting a confirmation
update chain_confirmation set kind = 'returned' where id = '0000eeee-0000-4000-8000-000000000001';
rollback to savepoint n8;
savepoint n9;  -- a scheme the constraint does not know
insert into settlement_batch (id, organisation_id, scheme, currency, value_date, created_by)
  select '0000dddd-0000-4000-8000-000000000002', organisation_id, 'RTGS', 'SGD', date '2026-12-01', created_by from pf;
rollback to savepoint n9;
savepoint n10; -- a real-looking token symbol
insert into token_registry values ('eip155:31337', '0x' || repeat('b', 40), 'USDC', 'USD', 6);
rollback to savepoint n10;
savepoint n11; -- same identity in ANOTHER organisation is permitted: the organisation is the simulation scope
insert into organisation (id, legal_name, short_name) values ('0000ffff-0000-4000-8000-000000000001', 'Pre-flight Org', 'PRE019');
-- (needs an instruction, batch, item, response, entry in that organisation; the FK chain is shown to the depth the
--  identity key needs — the unique key includes organisation_id, so the catalogue proves the scope)
select conname, pg_get_constraintdef(c.oid) from pg_constraint c where conname = 'chain_confirmation_identity_key';
rollback to savepoint n11;

select 'vocabularies' as check, count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid
 where t.relname in ('chain_confirmation','token_registry','settlement_batch','actor_role')
   and pg_get_constraintdef(c.oid) like '%= ANY (ARRAY[%';
rollback;
