-- Sanctions screening: versioned synthetic lists, screening results, cases,
-- and the screening-service role (C-07).
--
-- docs/briefs/017-TASK-screening-and-cases.md; ADR-0028 D5 and D8. Core screens
-- every submission itself, against the one list it has accepted; a client's
-- screening result is recorded as evidence beside core's recomputation and
-- authorises nothing. A hit opens a case that a compliance actor — never the
-- instruction's maker — dispositions with a retained rationale.
--
-- Numbered 0015 against the live tree at build time (lesson L-1): 0001..0014
-- are applied and checksummed, and every one of them is immutable
-- (docs/ADR/0009-forward-only-sql-migrations.md).
--
-- The DDL is the brief's, verbatim: Master Control executed it against a live
-- PostgreSQL 16 before dispatch with one row of every documented shape and
-- twelve refusals (lesson L-3, widened); the refusals are re-run on the
-- migrated test database by `clofin.screening.repository-test`. What this file
-- adds to the brief's text is comments.
--
-- **Synthetic data only.** A screening list here is a CloFin-defined synthetic
-- list with exact-match rules; it is not, and is not derived from, any real
-- sanctions list, and nothing in this schema makes a claim about real-world
-- screening quality. Lists are loaded by a tool (`clojure -M:screening-list`,
-- `make load-screening-list`), never through the API and never by a migration:
-- a migration is immutable history, and a list is reference data that is
-- loaded, retired and replaced.

-- ---------------------------------------------------------------------------
-- The role (ADR-0028 D8)
-- ---------------------------------------------------------------------------
--
-- Dropped and recreated by name, the way 0013 widened
-- `recon_adjustment_status_known`. `clofin.authz.model/roles` is the code's copy
-- and `clofin.db.vocabulary-test` compares the two in both directions.

alter table actor_role drop constraint role_known;
alter table actor_role add constraint role_known
  check (role in ('operator','approver','controller','compliance','auditor','screening-service'));

-- ---------------------------------------------------------------------------
-- Lists, entries and rules — reference data, loaded by the tool
-- ---------------------------------------------------------------------------

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

comment on table screening_list is
  'One version of the synthetic screening list core screens against. A version '
  'is immutable once loaded; the only change it admits is retirement, once '
  '(screening_list_retire_only). At most one version is accepted — not retired '
  '— at a time, which the loading tool enforces under a table lock: a submit '
  'with no accepted list is refused, never screened against nothing. '
  'Belongs to no organisation, so loading one is recorded here (loaded_at, '
  'source) and not in the tenant audit trail, whose events each name an '
  'organisation. Synthetic: not derived from any real list.';
comment on column screening_list.version is
  'The version a decision names. A past decision is reproducible only because '
  'the list it was taken against is retained under this version, unchanged.';
comment on column screening_list.source is
  'Where the tool loaded the list from (a path), recorded as provenance.';
comment on column screening_list.retired_at is
  'Null while the version is the accepted list. Set once, by the tool, when the '
  'version is retired or replaced. A core decision against a retired list '
  'permits nothing: the next submission screens again against the accepted one.';
comment on column screening_list.entry_count is
  'The number of entries the tool loaded, recorded with the version so a '
  'truncated load is visible against the entries actually present.';

create table screening_entry (
  list_version text not null references screening_list (version),
  id           text not null,
  primary key (list_version, id),
  constraint screening_entry_id_shape check (id ~ '^[\x21-\x7E]{1,128}$')
);

comment on table screening_entry is
  'One entry of a list version. An entry matches an instruction when every one '
  'of its rules matches (rules AND within an entry; entries OR across the '
  'list). Append-only: a changed entry is a new list version.';

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

comment on table screening_rule is
  'One rule of an entry. Append-only, like the entry it belongs to.';
comment on column screening_rule.position is
  'The rule''s place in the entry as loaded. Order does not change an entry''s '
  'meaning (every rule must match); it is kept so a list read back is the list '
  'that was loaded.';
comment on column screening_rule.field is
  'The instruction field the rule reads. Three, and only three: an '
  'originator-* field has no per-instruction value in core, and a list naming '
  'one is refused at load (unsupported-rule-field, ADR-0028 D5).';
comment on column screening_rule.operator is
  'exact: string equality with no case folding, trimming or normalisation. A '
  'normalising matcher would be a different operator with a different '
  'false-positive profile, and is not built.';

-- ---------------------------------------------------------------------------
-- Results — every screening decision and every client's evidence
-- ---------------------------------------------------------------------------

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

comment on table screening_result is
  'A screening result: core''s own decision (origin core), taken at every '
  'submission, or a client''s result recorded as evidence beside core''s '
  'recomputation against the same list version (origin client). Append-only. '
  'Only core''s results gate submit; a client''s result — accepted or refused — '
  'transitions nothing (ADR-0028 D5). A refused client result is stored, not '
  'rolled back with its refusal: the disagreement is evidence (lesson L-11).';
comment on column screening_result.outcome is
  'What the result says: core''s own outcome for a core result; the outcome '
  'the client submitted for a client result.';
comment on column screening_result.core_outcome is
  'Core''s recomputation against list_version and the instruction as it stood '
  'under the row lock. Equal to outcome on every core result '
  '(screening_result_core_agrees_with_itself).';
comment on column screening_result.agrees is
  'For a client result: the submitted outcome and matched-entry set both equal '
  'core''s. The set comparison is the application''s (the match rows record both '
  'sides); the outcome half is derivable and is checked for core results only.';
comment on column screening_result.instruction_digest is
  'clofin.screening.subject/digest of the instruction the result was taken '
  'over: identity and the screened content, not status or timestamps. A '
  'decision is bound to this digest, so an amendment makes it moot.';
comment on column screening_result.disposition is
  'accepted: core reproduced the result (always, for a core result). refused: '
  'core''s recomputation disagreed, and the row is kept as evidence of that.';
comment on column screening_result.recorded_by is
  'The actor whose request recorded the row: the screening client for a client '
  'result; the submitting maker for core''s own decision, taken at their submit.';
comment on column screening_result.screened_at is
  'When the client says it screened, as it said it. Null for core results, '
  'whose decision time is recorded_at.';

create table screening_result_match (
  result_id    uuid not null references screening_result (id),
  side         text not null,
  list_version text not null,
  entry_id     text not null,
  primary key (result_id, side, entry_id),
  foreign key (list_version, entry_id) references screening_entry (list_version, id),
  constraint screening_result_match_side_known check (side in ('submitted','core'))
);

comment on table screening_result_match is
  'The entries a result matched, on each side: submitted (what the client '
  'claimed; client results only) and core (core''s recomputation). Each names a '
  'real entry of a loaded list version by foreign key, so a result cannot cite '
  'an entry no list held. Append-only.';

-- ---------------------------------------------------------------------------
-- Cases — a hit, and its disposition
-- ---------------------------------------------------------------------------

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

comment on table screening_case is
  'A hit awaiting, or carrying, a compliance disposition. Bound to the '
  'instruction, the digest and the list version of the result that opened it: '
  'a disposition decides that hit on that content against that list, so an '
  'amendment or a new list makes it moot and the next submission opens a new '
  'case if the hit stands. Never deleted; a dispositioned case is frozen '
  '(screening_case_disposition_final).';
comment on column screening_case.result_id is
  'The accepted hit — core''s, or a client''s core reproduced — that opened the case.';
comment on column screening_case.disposition is
  'false-positive: the hit does not stand, and a submission of this content '
  'against this list is permitted. confirmed-hit: it stands, and submission is '
  'refused; the maker''s path is cancellation.';
comment on column screening_case.rationale is
  'Why, in the dispositioning actor''s words: 1-1000 characters, non-blank. '
  'Required by screening_case_disposition_complete, so a disposition without a '
  'reason cannot be recorded.';
comment on column screening_case.dispositioned_by is
  'The compliance actor who decided. Never the instruction''s creator: the '
  'maker does not clear their own hit (C-01''s shape, enforced by the service).';

-- **One open case per instruction**, decided by the index rather than by a
-- read in the application: two submissions racing on one hit both find no open
-- case, and the second insert fails here.
create unique index screening_case_open_key on screening_case (instruction_id) where status = 'open';

-- ---------------------------------------------------------------------------
-- Immutability, enforced rather than described (L-6)
-- ---------------------------------------------------------------------------

-- A list version admits one change: retirement, once, touching nothing else.
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

-- A case moves once, from open to dispositioned, and is frozen from then on.
-- The service refuses a second disposition with a 409 first; this is the second
-- enforcement, for every writer that is not the service.
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
