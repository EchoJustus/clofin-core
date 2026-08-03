-- Payment instructions and idempotency keys.
--
-- An instruction is *intent to pay*. It is not a journal entry: one instruction
-- produces several entries over its life — a release, a fee, a settlement — and
-- the entries are the accounting fact while the instruction is the request that
-- caused them. See docs/ADR/0008-double-entry-journal-as-source-of-truth.md.
--
-- The lifecycle itself is held as data in clofin.payments.state, not encoded
-- here. The check constraint below is a backstop against a row arriving in a
-- status the application does not know, not a second copy of the state machine:
-- it constrains the set of statuses, and says nothing about which may follow
-- which. See docs/ADR/0014-payment-lifecycle-as-data.md.
--
-- Append only. 0001 and 0002 are immutable — the migration runner verifies
-- checksums and refuses to start if an applied file has changed.
-- See docs/ADR/0009-forward-only-sql-migrations.md.

create table payment_instruction (
  id                 uuid        primary key,
  organisation_id    uuid        not null references organisation (id),
  debtor_account_id  uuid        not null references ledger_account (id),
  creditor_name      text        not null,
  creditor_account   text        not null,   -- synthetic external identifier
  amount_minor       bigint      not null,
  currency           char(3)     not null references currency (code),
  value_date         date        not null,
  purpose_code       text        not null,
  status             text        not null,
  created_by         uuid        not null,
  created_at         timestamptz not null default now(),
  reverses_id        uuid        null references payment_instruction (id),

  constraint payment_amount_positive check (amount_minor > 0),
  constraint payment_status_known
    check (status in ('draft','pending-approval','approved','released',
                      'settled','rejected','cancelled','failed','returned'))
);

comment on table payment_instruction is
  'A request to move money, with a lifecycle. Mutable while draft; immutable '
  'in substance thereafter. Distinct from a journal entry, which is the '
  'accounting fact an instruction eventually produces.';

comment on column payment_instruction.amount_minor is
  'Integer minor units against the currency''s ISO 4217 scale. Money is never '
  'a float, here or anywhere else (docs/ADR/0003-money-as-integer-minor-units.md).';

comment on column payment_instruction.creditor_account is
  'A synthetic external account identifier. CloFin is not connected to any '
  'bank or payment scheme and no value in this column addresses a real account.';

comment on column payment_instruction.value_date is
  'The date settlement is requested for. A calendar date, not an instant: a '
  'value date is the same day in every zone that quotes it.';

comment on column payment_instruction.created_by is
  'The actor who created the instruction. Caller-asserted until TASK-003 '
  'delivers authentication — it is not yet evidence of who did anything.';

comment on column payment_instruction.reverses_id is
  'Set on an instruction raised to reverse a settled one. The original is '
  'never mutated; the reversal is a new instruction that points back at it.';

-- ---------------------------------------------------------------------------
-- Idempotency
-- ---------------------------------------------------------------------------
--
-- The composite primary key IS the replay guarantee (COMPLIANCE C-06). Two
-- concurrent retries carrying one key contend on this key: the second blocks
-- until the first commits and then fails on it, at which point it reads the
-- winner's stored response and returns that. Replay protection implemented as
-- a read-then-write in application code would be a race, and under concurrent
-- retries a race here pays twice.

create table idempotency_key (
  organisation_id uuid        not null references organisation (id),
  key             text        not null,
  request_digest  text        not null,   -- SHA-256 of the canonical request body
  response_status integer     not null,
  response_body   text        not null,
  created_at      timestamptz not null default now(),
  primary key (organisation_id, key)
);

comment on table idempotency_key is
  'One row per (organisation, idempotency key). The row is written in the same '
  'transaction as the effect it protects: a crash between the two would leave '
  'a payment made with no record that it was.';

comment on column idempotency_key.request_digest is
  'SHA-256 of the canonical serialisation of the request body — sorted keys, '
  'no insignificant whitespace — so that a semantically identical retry is not '
  'mistaken for a conflicting one. See '
  'docs/ADR/0013-canonical-request-digest-for-idempotency.md.';

comment on column idempotency_key.response_body is
  'The response the first execution produced, replayed verbatim to any retry '
  'carrying the same key and the same digest.';
