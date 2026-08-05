-- Release-audit remediation: the purpose-code vocabulary, and the line/account
-- currency relationship.
--
-- Three findings from the `ref-1` release audit
-- (docs/audits/FEEDBACK-REL-ref-1.md), triaged by Master Control and actioned
-- here. Each is stated in the terms the finding used, because a migration that
-- shows only its DDL leaves the next reader to guess what was wrong before.
--
-- Numbered 0011 against the live tree at `main` `5d21334`, where 0001..0010 are
-- applied and checksummed, and against every branch in the stack (lesson L-1 —
-- "next available" is verified against the live tree, never assumed). Append
-- only: an applied migration is immutable
-- (docs/ADR/0009-forward-only-sql-migrations.md).
--
-- Validated against a live PostgreSQL 16 with 0001..0010 applied before this
-- file was written (lesson L-3): every row shape described below inserts, and
-- every guard described below refuses.

-- ---------------------------------------------------------------------------
-- A-018 — `payment_instruction.purpose_code` was an unconstrained `text`
-- ---------------------------------------------------------------------------
--
-- `clofin.payments.instruction/purpose-codes` holds 15 codes,
-- `api/openapi.yaml`'s `PurposeCode` publishes the same 15, and the domain
-- constructor refuses anything else on every application path. The column,
-- however, was declared `purpose_code text not null` in migration 0003 with no
-- check at all, so a direct SQL write could persist any string — and
-- DOMAIN_MODEL described the field as a "constrained vocabulary" for the
-- system of record, which was false of the system of record itself.
--
-- This is the same defence-in-depth posture F-003 established for entry
-- completeness and F-002 for the append-only verbs: the application enforces
-- the rule and the schema makes the violating row impossible, because a rule
-- that lives only above the database is a rule the database will happily be
-- talked out of.
--
-- The codes are ISO 20022 external purpose codes, used here on synthetic data.
-- Their meanings are documented in `clofin.payments.instruction`; the list is
-- repeated here rather than referenced, because a check constraint cannot
-- reference application code — which is exactly why the two are compared by a
-- test that reads both (see `clofin.payments.instruction-test`).

alter table payment_instruction
  add constraint payment_purpose_code_known
    check (purpose_code in ('CASH','CHAR','DIVI','GDDS','INSU','INTC','LOAN',
                            'PENS','RENT','SALA','SCVE','SUPP','TAXS','TRAD',
                            'TREA'));

comment on constraint payment_purpose_code_known on payment_instruction is
  'The 15 purpose codes clofin.payments.instruction/purpose-codes declares and '
  'api/openapi.yaml publishes as PurposeCode. All three sets are compared for '
  'equality in both directions by clofin.payments.instruction-test — a code '
  'added to any one of them without the other two fails the build, which is '
  'what audit finding A-018 asked for and A-014 generalised.';

-- ---------------------------------------------------------------------------
-- A-002 (DOMAIN_MODEL I6) — nothing related a line's currency to its account's
-- ---------------------------------------------------------------------------
--
-- I6 says an account holds exactly one currency, and named "schema and balance
-- computation" as its enforcement. Both `ledger_account.currency` and
-- `journal_line.currency` referenced the currency registry, but **no
-- constraint related the two**: a balanced entry whose lines were denominated
-- differently from the accounts they moved could be committed by raw SQL. The
-- application blocked it (`clofin.ledger.repository/assert-postable!` compares
-- each line's currency with the loaded account), and the balance query then
-- filtered by account currency — which computes a correct balance *over an
-- incorrect row* rather than making the row impossible.
--
-- The fix is a composite foreign key, which PostgreSQL needs a matching unique
-- key to point at. `ledger_account (id, currency)` is unique for free — `id` is
-- already the primary key — so the extra key costs an index and buys the
-- reference. Nothing about the account's identity changes.
--
-- Why not a trigger: a foreign key is declarative, is enforced on both INSERT
-- and UPDATE without anyone writing the UPDATE half, and cannot be disabled by
-- `session_replication_role` in a way that a `BEFORE` trigger can. It also
-- states the rule as a *relationship*, which is what I6 is.
--
-- What this does NOT do, stated rather than left to be discovered: it does not
-- stop an account's currency being changed out from under its lines. Nothing
-- changes `ledger_account.currency` today and no operation exists to; were one
-- added, this key would refuse the change while lines existed, which is the
-- correct answer and not an accident of ordering.

alter table ledger_account
  add constraint ledger_account_id_currency_key unique (id, currency);

comment on constraint ledger_account_id_currency_key on ledger_account is
  'Not an identity constraint — `id` alone is already the primary key. It '
  'exists so journal_line can reference (account_id, currency) as a pair, '
  'which is what makes invariant I6 a schema relationship rather than an '
  'application convention (audit finding A-002).';

alter table journal_line
  add constraint journal_line_account_currency_fk
    foreign key (account_id, currency)
    references ledger_account (id, currency);

comment on constraint journal_line_account_currency_fk on journal_line is
  'A line is denominated in its account''s currency. Invariant I6, enforced at '
  'the database rather than only in clofin.ledger.repository — a raw-SQL '
  'insert of a mismatched line now refuses instead of committing a row whose '
  'balance is only correct because the balance query filters it out.';

-- The single-column foreign key from 0002 is now implied by the composite one
-- and is left in place deliberately: dropping it would remove the `ON DELETE`
-- semantics reviewers read off that line, and a redundant constraint costs a
-- catalogue row while a missing one costs an invariant.

-- ---------------------------------------------------------------------------
-- A-016 — `replay-key-conflict` was a refusal code no vocabulary declared
-- ---------------------------------------------------------------------------
--
-- Migration 0010's comment on `scheme_response.disposition_reason` documented
-- the vocabulary as `item-already-resolved | item-not-timed-out |
-- item-not-in-batch`. The service also emits `replay-key-conflict` — a caller
-- receives it under `errors.dispositionReason` on a `409`, and
-- `clofin.api.settlement-api-test` asserts it end to end — while it appeared in
-- neither `clofin.settlement.response/refusal-reasons`, this comment, nor any
-- published enum.
--
-- 0010 is applied and immutable, so the correction is made here rather than
-- there. The correction is *not* to widen the stored vocabulary: the three
-- values above remain the complete set this column can hold, and
-- `replay-key-conflict` is precisely the refusal that writes no receipt,
-- because it fires when a receipt for that identity already exists. Widening
-- the column's documented set to include it would publish a value the system
-- cannot produce, which is the same class of untrue statement in the other
-- direction (lesson L-14).
--
-- `clofin.settlement.response` now declares both sets — `refusal-reasons`,
-- everything a caller may be told, and `stored-refusal-reasons`, what may reach
-- this column — and `api/openapi.yaml` publishes both as
-- `SchemeResponseRefusalReason` and `StoredSchemeResponseRefusalReason`.

comment on column scheme_response.disposition_reason is
  'Why a refused arrival was refused, as a stable machine code. Complete set: '
  'item-already-resolved | item-not-timed-out | item-not-in-batch — mirrored by '
  'clofin.settlement.response/stored-refusal-reasons and by '
  'StoredSchemeResponseRefusalReason in api/openapi.yaml. This is a strict '
  'subset of the codes a caller may receive: replay-key-conflict is refused '
  'because a receipt for that identity already exists, so it never writes one '
  'of its own and never appears here. Supersedes the comment migration 0010 '
  'set, which named this set without noting that a fourth code reached callers '
  '(audit finding A-016).';

comment on column payment_instruction.purpose_code is
  'ISO 20022 external purpose code, on synthetic data. Constrained by '
  'payment_purpose_code_known since migration 0011; before that the column was '
  'unconstrained text and the "constrained vocabulary" DOMAIN_MODEL described '
  'was true only of the application path (audit finding A-018).';
