-- Reconciliation: statements received, lines matched, breaks owned, adjustments
-- posted.
--
-- CloFin never connects to a real scheme, bank or central bank, and it does not
-- read any real statement format. The documents these tables hold are produced
-- by CloFin's own simulator in CloFin's own format —
-- `SIM-CLOFIN-RECON-STATEMENT`, deliberately not camt.053, MT940, BAI2 or any
-- scheme's schema. The `SIM-` prefix in `recon_statement_scheme_known` is
-- load-bearing for the same reason it is in `settlement_scheme_known`: it makes
-- a synthetic record that reads as a real one unrepresentable rather than
-- discouraged.
--
-- Numbered 0012 against the live tree at build time (lesson L-1): 0011 is
-- applied and checksummed, and 0001..0011 are immutable
-- (docs/ADR/0009-forward-only-sql-migrations.md).
--
-- Three decisions in this file are recorded in
-- docs/ADR/0023-a-clofin-defined-synthetic-statement-format-and-an-ordered-matching-sequence.md
-- rather than left to be re-derived:
--
--   * The statement format is CloFin's own and versioned, and the version is
--     stored on every receipt so a document read back years later says which
--     rules produced it.
--   * Matching is deterministic, ordered, and records **which rule** matched
--     (PR-051) — hence `reconciliation_match.rule_id` and its vocabulary
--     constraint, which `clofin.recon.matching/rule-ids` owns.
--   * A break is resolved only by a **new** approved balanced entry
--     (C-03: nothing here edits a journal entry, ever), and the approvals it
--     needs come from the existing `approval_threshold` table and the existing
--     `approval` table — extended below rather than duplicated, because a
--     second approvals table would be a second maker-checker control to keep
--     in step.

-- ---------------------------------------------------------------------------
-- Statements received
-- ---------------------------------------------------------------------------
--
-- Receipt and disposition are separate facts (standing lesson L-11, audit
-- finding F-008). A statement CloFin cannot process is still recorded as having
-- arrived, with a machine-readable reason, and the caller's refusal is rendered
-- after this row commits. A receipt destroyed by its own processing failure is
-- not a receipt.

create table reconciliation_statement (
  id                  uuid        primary key,
  organisation_id     uuid        not null references organisation (id),
  scheme              text        not null,
  currency            char(3)     not null references currency (code),
  statement_reference text        not null,
  format              text        not null,
  format_version      integer     not null,
  period_start        timestamptz not null,
  period_end          timestamptz not null,
  content_digest      text        not null,
  disposition         text        not null,
  disposition_reason  text        null,
  -- Null exactly when the statement was refused for want of one. A refused
  -- statement has no account to have been reconciled against, and inventing one
  -- would put a claim in the column an investigation reads as fact.
  reconciled_account_id uuid      null references ledger_account (id),
  received_at         timestamptz not null default now(),
  received_by         uuid        not null references actor (id),

  constraint recon_statement_replay_key
    unique (organisation_id, statement_reference),
  constraint recon_statement_disposition_known
    check (disposition = any (array['applied','refused'])),
  constraint recon_statement_refusal_reason_known
    check (disposition_reason is null
           or disposition_reason = any (array['no-reconciled-account',
                                              'too-many-ledger-movements'])),
  -- A refusal carries its reason and an application does not. Both directions,
  -- because a refused row with no reason is unactionable and an applied row
  -- with one is a contradiction.
  constraint recon_statement_refusal_states_why
    check ((disposition = 'refused') = (disposition_reason is not null)),
  constraint recon_statement_scheme_known
    check (scheme = any (array['SIM-RTGS','SIM-ACH'])),
  constraint recon_statement_period_ordered
    check (period_start < period_end)
);

comment on table reconciliation_statement is
  'One arrival of one synthetic statement, kept whether or not CloFin could '
  'process it. The format is CloFin''s own — SIM-CLOFIN-RECON-STATEMENT — and '
  'is deliberately not camt.053, MT940, BAI2 or any real scheme''s schema: a '
  'synthetic-data project parsing a real bank format would be fidelity theatre.';

comment on column reconciliation_statement.statement_reference is
  'The delivery''s IDENTITY within an organisation. Two deliveries carrying '
  'this reference are two deliveries of one document; whether they are the '
  'same MESSAGE is what content_digest answers.';

comment on column reconciliation_statement.content_digest is
  'Version-tagged canonical digest of the complete semantic content — scheme, '
  'currency, period, and every line. Standing lessons L-2 and L-12: a replay '
  'key that excludes a field which decides an effect lets two contradictory '
  'documents collapse into one. An exact re-delivery replays the original '
  'outcome; a different body under the same reference is 409.';

comment on column reconciliation_statement.format_version is
  'The version of the format this document declared and this build read. '
  'Stored rather than assumed, so a receipt read back after the format moves '
  'says which rules produced its matches.';

comment on column reconciliation_statement.disposition is
  'applied | refused — what CloFin did about the arrival, machine-readable. A '
  'refusal commits WITH the receipt and the caller''s error is rendered after '
  'the transaction (F-008, L-11).';

create index recon_statement_org_period_idx
  on reconciliation_statement (organisation_id, period_start, period_end);

create index recon_statement_account_idx
  on reconciliation_statement (reconciled_account_id, period_start);

-- ---------------------------------------------------------------------------
-- The lines of a statement
-- ---------------------------------------------------------------------------

create table reconciliation_statement_line (
  statement_id      uuid    not null references reconciliation_statement (id),
  line_no           integer not null,
  scheme_reference  text    not null,
  -- The end-to-end reference the scheme echoed back, when it echoed one. Null
  -- is a real case rather than missing data: a line the scheme did not tag is
  -- matched on its attributes instead, by rule R4.
  payment_reference text    null,
  line_type         text    not null,
  amount_minor      bigint  not null,
  currency          char(3) not null references currency (code),
  value_date        date    not null,

  primary key (statement_id, line_no),
  constraint recon_statement_line_type_known
    check (line_type = any (array['settlement','return'])),
  constraint recon_statement_line_amount_positive
    check (amount_minor > 0)
);

comment on table reconciliation_statement_line is
  'One movement the simulated scheme reports. Two line types only: settlement '
  'and return — the outcomes a scheme reaches. A release is CloFin telling the '
  'scheme something, not the scheme telling CloFin, so it is not a line here; '
  'and a payment that timed out is one the scheme never answered about, so it '
  'is correctly absent from every statement while its value sits in '
  '1300-IN-TRANSIT (ADR-0018).';

comment on column reconciliation_statement_line.line_no is
  'Position in the document, assigned by CloFin from 1 on arrival rather than '
  'read from it: a line''s position is how a break addresses it, and two lines '
  'claiming one number would be two breaks claiming one identity.';

-- ---------------------------------------------------------------------------
-- Matches
-- ---------------------------------------------------------------------------

create table reconciliation_match (
  statement_id uuid        not null,
  line_no      integer     not null,
  -- The ledger movement the line was matched to. The journal entry, because
  -- exactly one of its lines touches the reconciled account and the entry is
  -- what an investigation reads.
  entry_id     uuid        not null references journal_entry (id),
  rule_id      text        not null,
  matched_at   timestamptz not null default now(),

  primary key (statement_id, line_no),
  foreign key (statement_id, line_no)
    references reconciliation_statement_line (statement_id, line_no),
  constraint recon_match_rule_known
    check (rule_id = any (array['R1-reference-amount-and-value-date',
                                'R2-reference-and-amount',
                                'R3-reference-only',
                                'R4-amount-and-value-date']))
);

comment on table reconciliation_match is
  'One statement line bound to one ledger movement, recording WHICH RULE bound '
  'them (PR-051). The rule id is the explanation: a match nobody can re-derive '
  'is a match nobody can defend to an auditor. The vocabulary is owned by '
  'clofin.recon.matching/rule-ids and compared with this constraint against the '
  'live catalogue by clofin.db.vocabulary-test.';

-- One movement is matched by at most one line, which is what makes a second
-- claim on it a break rather than a second match. In the schema rather than in
-- application code, so it binds a fix-up script too.
create unique index recon_match_expectation_key
  on reconciliation_match (statement_id, entry_id);

comment on index recon_match_expectation_key is
  'A ledger movement is claimed by at most one line of a statement. The '
  'duplicate-statement-line break exists because of this index: the second '
  'claim cannot become a match, so it becomes a tracked disagreement instead.';

-- ---------------------------------------------------------------------------
-- Breaks
-- ---------------------------------------------------------------------------

create table reconciliation_break (
  id              uuid        primary key,
  organisation_id uuid        not null references organisation (id),
  statement_id    uuid        not null references reconciliation_statement (id),
  account_id      uuid        not null references ledger_account (id),
  kind            text        not null,
  state           text        not null default 'open',
  -- Exactly one side is always present, and both are present when the two
  -- records were matched and disagree. A break with neither would be a
  -- disagreement about nothing.
  line_no         integer     null,
  entry_id        uuid        null references journal_entry (id),
  currency        char(3)     not null references currency (code),
  statement_amount_minor bigint null,
  ledger_amount_minor    bigint null,
  detail          text        not null,
  assignee_id     uuid        not null references actor (id),
  opened_at       timestamptz not null default now(),
  resolved_at     timestamptz null,

  foreign key (statement_id, line_no)
    references reconciliation_statement_line (statement_id, line_no),
  constraint recon_break_kind_known
    check (kind = any (array['statement-line-unmatched',
                             'expectation-unmatched',
                             'duplicate-statement-line',
                             'amount-mismatch',
                             'value-date-mismatch',
                             'line-type-mismatch'])),
  constraint recon_break_state_known
    check (state = any (array['open','investigating','resolved'])),
  constraint recon_break_has_a_side
    check (line_no is not null or entry_id is not null),
  constraint recon_break_resolution_paired
    check ((state = 'resolved') = (resolved_at is not null))
);

comment on table reconciliation_break is
  'A reconciliation item that did not match, or matched and disagreed: a '
  'tracked object with an owner and an age, not a discrepancy somebody will '
  'look at later (DOMAIN_MODEL §1). The PRD''s framing is the reason it is a '
  'row: a break found in March may have originated in January, and this table '
  'is why that cannot happen quietly here.';

comment on column reconciliation_break.opened_at is
  'When the break was discovered. Its AGE is derived from this by whoever '
  'reads it and is never stored — a stored age is wrong the moment it is '
  'written, for the same reason a stored balance is (ADR-0008).';

comment on column reconciliation_break.assignee_id is
  'A break is never unowned. It opens assigned to the actor whose ingestion '
  'discovered it and may be reassigned while it is open or investigating; a '
  'resolved break keeps the owner of record, because who resolved what is the '
  'history an investigation reads.';

comment on column reconciliation_break.state is
  'open -> investigating -> resolved, enforced at the service boundary from '
  'clofin.recon.break-state/transitions. An illegal transition is refused with '
  'a 409 naming what would have been permitted, never applied silently.';

create index recon_break_org_state_idx
  on reconciliation_break (organisation_id, state, opened_at);

create index recon_break_account_idx
  on reconciliation_break (account_id, opened_at);

create index recon_break_assignee_idx
  on reconciliation_break (assignee_id, state);

-- ---------------------------------------------------------------------------
-- Adjustments
-- ---------------------------------------------------------------------------
--
-- The only way a disagreement changes the books. Nothing here edits a journal
-- entry (C-03): an adjustment is a NEW balanced entry, posted through the same
-- path a release uses — the domain zero-sum check, the account lock, the
-- deferred database trigger and the journal-entry.posted audit event all apply.

create table reconciliation_adjustment (
  id                 uuid        primary key,
  organisation_id    uuid        not null references organisation (id),
  break_id           uuid        not null references reconciliation_break (id),
  amount_minor       bigint      not null,
  currency           char(3)     not null references currency (code),
  direction          text        not null,
  narrative          text        not null,
  status             text        not null default 'proposed',
  approvals_required smallint    not null,
  entry_id           uuid        null references journal_entry (id),
  created_by         uuid        not null references actor (id),
  created_at         timestamptz not null default now(),
  posted_at          timestamptz null,

  constraint recon_adjustment_status_known
    check (status = any (array['proposed','posted'])),
  constraint recon_adjustment_direction_known
    check (direction = any (array['debit','credit'])),
  constraint recon_adjustment_amount_positive
    check (amount_minor > 0),
  constraint recon_adjustment_approvals_not_negative
    check (approvals_required >= 0),
  -- A posted adjustment has an entry and a moment; a proposed one has neither.
  -- Stated as an equivalence in both directions so neither half can drift.
  constraint recon_adjustment_posting_paired
    check ((status = 'posted') = (entry_id is not null)
           and (status = 'posted') = (posted_at is not null))
);

comment on table reconciliation_adjustment is
  'A proposed correction to the books, and the entry it posted once approved. '
  'approvals_required is stored as it was computed AT PROPOSAL from the '
  'organisation''s approval_threshold bands, so that changing the bands later '
  'cannot retrospectively lower the bar an adjustment already cleared — the '
  'same reasoning that keeps a screening decision beside the list version that '
  'produced it.';

comment on column reconciliation_adjustment.direction is
  'The direction applied to the RECONCILED account (1300-IN-TRANSIT). The '
  'suspense leg (2200-UNAPPLIED) is always the opposite, which is what makes '
  'the entry balance by construction rather than by the caller getting it '
  'right.';

comment on column reconciliation_adjustment.approvals_required is
  'How many approvals this adjustment needs, computed from the lowest band the '
  'organisation configured for the currency: below it, zero — the proposer '
  'alone may post; at or above it, the band''s count, from actors who are not '
  'the proposer (C-01). An organisation with NO band in the currency cannot '
  'adjust at all, because treating "unconfigured" as "needs nobody" is how a '
  'control silently weakens.';

-- At most one posted adjustment per break. A second posting would move the
-- books twice for one disagreement, and the guard belongs in the schema so it
-- binds a defect as well as a handler.
create unique index recon_adjustment_posted_key
  on reconciliation_adjustment (break_id) where status = 'posted';

comment on index recon_adjustment_posted_key is
  'One posted adjustment per break, ever. A break resolves when its adjustment '
  'posts, and a second posting would be a second correction for one '
  'disagreement.';

create index recon_adjustment_break_idx
  on reconciliation_adjustment (break_id, created_at);

-- ---------------------------------------------------------------------------
-- Approvals — the EXISTING mechanism, widened rather than duplicated
-- ---------------------------------------------------------------------------
--
-- An adjustment above the threshold needs approvals, and CloFin already has an
-- approvals table, an approvals index, a no-delete trigger, an invalidation
-- column and a pure decision function (clofin.authz.approval/evaluate). A
-- second table would be a second maker-checker control to keep in step, and
-- standing lesson L-6 is the record of what happens when one copy of a rule
-- goes stale while the other keeps passing its tests.
--
-- So `approval` gains a second possible subject. instruction_id becomes
-- nullable and exactly one of the two subjects is required; the existing live
-- index is unaffected, because an adjustment row's instruction_id is null and
-- nulls are distinct in a unique index.

alter table approval alter column instruction_id drop not null;

alter table approval
  add column adjustment_id uuid null references reconciliation_adjustment (id);

alter table approval
  add constraint approval_names_one_subject
  check (num_nonnulls(instruction_id, adjustment_id) = 1);

-- One live decision per actor per adjustment, the mirror of
-- approval_actor_live_key. Partial on invalidated_at for the same reason: an
-- invalidated decision may be given again.
create unique index approval_actor_live_adjustment_key
  on approval (adjustment_id, actor_id) where invalidated_at is null;

comment on column approval.instruction_id is
  'The payment instruction this decision is about, or null when the decision '
  'is about a reconciliation adjustment. Exactly one of instruction_id and '
  'adjustment_id is set — approval_names_one_subject — so every row has one '
  'subject and no row has two.';

comment on column approval.adjustment_id is
  'The reconciliation adjustment this decision is about, or null when the '
  'decision is about a payment instruction. Added rather than given its own '
  'table: there is ONE maker-checker control in CloFin (C-01, C-02), one '
  'no-delete guarantee and one invalidation semantic, and a second approvals '
  'table would be a second copy of all three.';

comment on index approval_actor_live_adjustment_key is
  'One live decision per actor per adjustment. The guarantee that an actor '
  'cannot be counted twice toward a threshold is this index, not the '
  ':already-approved check in clofin.authz.approval/evaluate — that check is a '
  'better error message, and deleting it would leave the guarantee unchanged.';

-- ---------------------------------------------------------------------------
-- Append-only enforcement
-- ---------------------------------------------------------------------------
--
-- Three of the five tables above are statements about things that happened at a
-- moment in time: a document arrived, it carried these lines, and this line was
-- matched to this movement under this rule. Editing any of them would be
-- editing history, so all three are append-only across the FULL destructive
-- verb set standing lesson **L-5** requires — UPDATE, DELETE and TRUNCATE, each
-- a distinct trigger event with its own privilege. TRUNCATE visits no rows, so
-- a `for each row` guard never sees it; that is exactly how audit finding F-002
-- emptied `audit_event` past a guard that had just refused an UPDATE and a
-- DELETE on the same row.
--
-- `reconciliation_break` and `reconciliation_adjustment` are deliberately NOT
-- append-only: a break's state and assignee move, and an adjustment becomes
-- posted. That is the whole mechanism of the module, the same reason
-- `settlement_batch_item` is not append-only, and it is why the audit trail
-- carries a before and an after digest for every one of those changes.
--
-- Reusing `reject_mutation()` from migration 0002 rather than redefining it:
-- one function, one message, one place to change. The limit stated for every
-- other guarded table applies here too — a trigger binds the application, not
-- the table's OWNER, and CloFin connects as the owner. The runtime role split
-- is named debt in COMPLIANCE.md §4.

create trigger recon_statement_append_only
  before update or delete on reconciliation_statement
  for each row execute function reject_mutation();

create trigger recon_statement_no_truncate
  before truncate on reconciliation_statement
  for each statement execute function reject_mutation();

create trigger recon_statement_line_append_only
  before update or delete on reconciliation_statement_line
  for each row execute function reject_mutation();

create trigger recon_statement_line_no_truncate
  before truncate on reconciliation_statement_line
  for each statement execute function reject_mutation();

create trigger recon_match_append_only
  before update or delete on reconciliation_match
  for each row execute function reject_mutation();

create trigger recon_match_no_truncate
  before truncate on reconciliation_match
  for each statement execute function reject_mutation();
