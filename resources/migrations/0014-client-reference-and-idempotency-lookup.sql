-- A client's own reference, the beneficiary's country, and the operation an
-- idempotency key was bound by.
--
-- The three additions ADR-0028 D6 makes so a satellite client can bind its own
-- record to core's (docs/briefs/018-TASK-client-reference-and-idempotency-
-- lookup.md). Every column is nullable: rows written before this migration stay
-- valid as they are, and nothing here rewrites one.
--
-- Numbered 0014 against the live tree at build time (lesson L-1): 0001..0013
-- are applied and checksummed, and every one of them is immutable
-- (docs/ADR/0009-forward-only-sql-migrations.md).
--
-- Executed against a live PostgreSQL 16 with 0001..0013 applied before this
-- file was committed (lesson L-3), with one row of every documented shape
-- inserted and every guard below seen refusing; the refusals are re-run on the
-- migrated test database by `clofin.payments.repository-test`.
--
-- Synthetic data only. A client reference is an identifier a caller chooses
-- for its own record; it addresses nothing outside this reference
-- implementation, and the country is a two-letter shape, checked as a shape.

-- ---------------------------------------------------------------------------
-- The instruction's two new members (ADR-0028 D6)
-- ---------------------------------------------------------------------------

alter table payment_instruction
  add column client_reference text    null,
  add column creditor_country char(2) null;

comment on column payment_instruction.client_reference is
  'The identifier the creating client keeps for this payment, or null. '
  'Printable ASCII without spaces, 1-128 characters, stored exactly as sent. '
  'Unique per organisation (payment_instruction_client_reference_key), so core '
  'holds at most one instruction for a reference: the same reference under a '
  'new key answers 409 naming this instruction rather than creating a second '
  'one. Set when the instruction is created and never afterwards — '
  'payment_instruction_client_reference_immutable refuses a change, including '
  'null to a value (ADR-0028 D6).';

comment on column payment_instruction.creditor_country is
  'The beneficiary''s country as an ISO 3166-1 alpha-2 shape — two uppercase '
  'letters — or null. Syntax only: no list of countries is consulted, because '
  'the field is synthetic and a membership check would make it look like a '
  'real-world validation. Amendable while the instruction is a draft, like the '
  'other beneficiary fields. It exists so a screening rule can name it '
  '(ADR-0028 D5, D6).';

-- Shape checks, the schema's own copy of the rules
-- `clofin.payments.instruction/field-errors` applies first. These are regular
-- expressions, not vocabularies: `clofin.db.vocabulary-test` discovers
-- `= ANY (ARRAY[...])` constraints and owns none of these, by design.
alter table payment_instruction
  add constraint payment_client_reference_shape
    check (client_reference is null or client_reference ~ '^[\x21-\x7E]{1,128}$'),
  add constraint payment_creditor_country_shape
    check (creditor_country is null or creditor_country ~ '^[A-Z]{2}$');

-- **The arbiter under concurrency.** Two creations carrying one reference under
-- two keys can both pass the application's pre-check. Whichever inserts second
-- fails on this index — after waiting for the first to commit, if it has not
-- yet — and its whole transaction, idempotency key row included, rolls back.
-- (Two creations with identical content name the same debtor account, so the
-- second usually waits on that account's row lock first and meets this index
-- only once the first has committed; it is refused here all the same.)
-- Organisation-scoped: the same reference in another organisation is a
-- different reference. Partial, so the many instructions with no reference do
-- not contend.
create unique index payment_instruction_client_reference_key
  on payment_instruction (organisation_id, client_reference)
  where client_reference is not null;

-- **Immutable, enforced rather than described** (standing lesson L-6), in the
-- pattern migration 0013 set for retries_id. The application never names this
-- column in an UPDATE — `amendable-fields` does not contain it — but "no code
-- path does it" is a property of today's callers, and an identity a client
-- binds its own record to is useless if it can be rewritten underneath it.
-- `is distinct from` makes null -> value a change too: a reference cannot be
-- added after creation, only given at it.
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

-- ---------------------------------------------------------------------------
-- Which operation bound a key (ADR-0028 D6, the lookup)
-- ---------------------------------------------------------------------------
--
-- `GET /payment-instructions/by-idempotency-key/{key}` answers for a key bound
-- by a creation and refuses every other binding by name. A key bound by a
-- submission stores a PaymentInstruction body too, so the body alone cannot
-- tell the two apart; this column can. Nullable only because rows written
-- before this migration have no value to give it — every insert from here on
-- writes one (`clofin.idempotency.repository/execute-once!` refuses to claim a
-- key without it).

alter table idempotency_key
  add column operation_id text null;

comment on column idempotency_key.operation_id is
  'The OpenAPI operationId the key was bound by, written on the same insert as the key. '
  'Null only on a row written before migration 0014; the lookup treats null as an '
  'operation it does not serve, never as an absent binding (ADR-0028 D6).';
