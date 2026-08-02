-- Ledger accounts and the double-entry journal.
--
-- The journal is the source of truth for money; balances are derived from it
-- and are never stored as an authoritative value. See
-- docs/ADR/0008-double-entry-journal-as-source-of-truth.md.
--
-- The zero-sum invariant is enforced twice on purpose: once in the domain
-- constructor (clofin.ledger.entry/entry) and once here. A defect in the
-- application must not be able to commit an unbalanced entry.

create table ledger_account (
  id              uuid        primary key,
  organisation_id uuid        not null references organisation (id),
  code            text        not null,
  name            text        not null,
  type            text        not null,
  currency        char(3)     not null references currency (code),
  status          text        not null default 'active',
  created_at      timestamptz not null default now(),

  constraint ledger_account_type_known
    check (type in ('asset', 'liability', 'equity', 'revenue', 'expense')),
  constraint ledger_account_status_known
    check (status in ('active', 'frozen', 'closed')),
  constraint ledger_account_code_format
    check (code ~ '^[A-Z0-9][A-Z0-9-]{1,63}$'),
  constraint ledger_account_name_present
    check (length(btrim(name)) > 0)
);

create unique index ledger_account_org_code_key
  on ledger_account (organisation_id, code);

comment on column ledger_account.type is
  'Determines the normal balance side, and therefore how debits and credits '
  'combine into a balance a finance team would recognise.';

-- ---------------------------------------------------------------------------
-- Journal
-- ---------------------------------------------------------------------------

create table journal_entry (
  id              uuid        primary key,
  organisation_id uuid        not null references organisation (id),
  occurred_at     timestamptz not null,
  recorded_at     timestamptz not null default now(),
  narrative       text        not null,
  reference_type  text        not null,
  reference_id    uuid        not null,
  reverses_id     uuid        null references journal_entry (id),

  constraint journal_entry_reference_type_known
    check (reference_type in ('payment-instruction', 'settlement-item',
                              'reconciliation-adjustment', 'fee-assessment',
                              'fx-conversion', 'reversal', 'opening-balance')),
  constraint journal_entry_narrative_present
    check (length(btrim(narrative)) > 0),
  constraint journal_entry_not_self_reversing
    check (reverses_id is null or reverses_id <> id)
);

create index journal_entry_org_occurred_idx
  on journal_entry (organisation_id, occurred_at desc);

create index journal_entry_reference_idx
  on journal_entry (reference_type, reference_id);

create unique index journal_entry_reverses_key
  on journal_entry (reverses_id)
  where reverses_id is not null;

comment on index journal_entry_reverses_key is
  'An entry may be reversed at most once. A second reversal would silently '
  'reapply the original movement.';

create table journal_line (
  id           uuid     primary key,
  entry_id     uuid     not null references journal_entry (id),
  line_no      integer  not null,
  account_id   uuid     not null references ledger_account (id),
  direction    text     not null,
  amount_minor bigint   not null,
  currency     char(3)  not null references currency (code),

  constraint journal_line_direction_known
    check (direction in ('debit', 'credit')),
  constraint journal_line_amount_positive
    check (amount_minor > 0),
  constraint journal_line_no_positive
    check (line_no >= 1)
);

create unique index journal_line_entry_line_key on journal_line (entry_id, line_no);
create index journal_line_account_idx on journal_line (account_id);
create index journal_line_entry_idx on journal_line (entry_id);

comment on constraint journal_line_amount_positive on journal_line is
  'Direction carries the sign. A negative line amount would be a second way to '
  'express the same movement, and two representations of one concept is how '
  'sign errors survive review.';

-- ---------------------------------------------------------------------------
-- Zero-sum invariant
-- ---------------------------------------------------------------------------

create function assert_journal_entry_balanced() returns trigger as $$
declare
  offending record;
begin
  select l.currency,
         sum(case when l.direction = 'debit'  then l.amount_minor else 0 end) as debits,
         sum(case when l.direction = 'credit' then l.amount_minor else 0 end) as credits
    into offending
    from journal_line l
   where l.entry_id = new.entry_id
   group by l.currency
  having sum(case when l.direction = 'debit'  then l.amount_minor else 0 end)
       <> sum(case when l.direction = 'credit' then l.amount_minor else 0 end)
   limit 1;

  if found then
    raise exception
      'Journal entry % does not balance in %: debits %, credits %',
      new.entry_id, offending.currency, offending.debits, offending.credits
      using errcode = 'integrity_constraint_violation';
  end if;

  return null;
end;
$$ language plpgsql;

comment on function assert_journal_entry_balanced() is
  'Deferred to commit so that the lines of an entry may be inserted one at a '
  'time, while an entry that does not balance can never be committed.';

create constraint trigger journal_entry_must_balance
  after insert on journal_line
  deferrable initially deferred
  for each row
  execute function assert_journal_entry_balanced();

-- ---------------------------------------------------------------------------
-- Append-only enforcement
-- ---------------------------------------------------------------------------
--
-- Enforced by trigger rather than by REVOKE because the owning role's
-- privileges cannot be revoked from itself. In a deployment with a separate
-- application role, `revoke update, delete on journal_entry, journal_line from
-- <app_role>` should be applied as well — defence in depth, not a substitute.

create function reject_mutation() returns trigger as $$
begin
  raise exception
    'Table % is append-only: correct a posted entry with a reversing entry, never by % ',
    tg_table_name, lower(tg_op)
    using errcode = 'integrity_constraint_violation';
end;
$$ language plpgsql;

create trigger journal_entry_append_only
  before update or delete on journal_entry
  for each row execute function reject_mutation();

create trigger journal_line_append_only
  before update or delete on journal_line
  for each row execute function reject_mutation();
