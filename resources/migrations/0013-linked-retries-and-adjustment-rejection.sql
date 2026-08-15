-- Linked-retry provenance, and a rejected reconciliation adjustment.
--
-- Two of the three gaps increment 6 disclosed rather than closed
-- (docs/audits/008-REQ-reconciliation.md, objections O-1 and observation N-5).
-- The third — a batch-status-change audit term — needs no schema.
--
-- Numbered 0013 against the live tree at build time (lesson L-1): 0001..0012
-- are applied and checksummed, and every one of them is immutable
-- (docs/ADR/0009-forward-only-sql-migrations.md). Nothing here edits an applied
-- file; the one existing constraint that has to change is dropped and recreated
-- by name, which is a new statement in a new file rather than an edit to an old
-- one.
--
-- Validated against a live PostgreSQL 16 with 0001..0012 applied before this
-- file was written (lesson L-3): every row shape described below inserts, and
-- every guard described below refuses.
--
-- The decisions are in
-- docs/ADR/0024-a-retry-names-the-returned-payment-it-replaces.md and
-- docs/ADR/0025-two-audit-terms-for-changes-the-trail-did-not-carry.md rather
-- than left to be re-derived from the DDL.

-- ---------------------------------------------------------------------------
-- Linked-retry provenance (ADR-0019, ruled to this increment; ADR-0024)
-- ---------------------------------------------------------------------------
--
-- ADR-0019 ruled that a returned payment is terminal and that a retry is a NEW
-- instruction, and named the cost it was accepting in the same breath:
--
--   "Nothing in the record relates the retry to the payment it replaces: an
--    investigator matching them today does so by counterparty and amount, not
--    by a reference. That is a real gap and it is deferred, not denied."
--
-- This column is that reference. It is deliberately NOT a second membership, a
-- second lifecycle arrow or a re-approval path — ADR-0019 rejected all three,
-- and the retry is still raised, submitted and approved entirely on its own
-- merits. What changes is that the record now says which payment it replaces.

alter table payment_instruction
  add column retries_id uuid null references payment_instruction (id);

comment on column payment_instruction.retries_id is
  'The RETURNED instruction this one was raised to replace, or null. Set when '
  'the retry is created and never afterwards — payment_instruction_retry_link_'
  'immutable refuses a change. A retry is a new payment decision (ADR-0019): '
  'this column relates the two records and confers nothing. It carries no '
  'value rule — a return is new information, and correcting the beneficiary or '
  'the amount is the ordinary reason to retry.';

-- The reverse lookup — "what retries this?" — is the half of the linkage that
-- an exception workflow reads, and it is the half with no primary key behind
-- it. Indexed rather than left to a sequential scan, because both the
-- instruction projection and every reconciliation break read it.
create index payment_instruction_retries_idx
  on payment_instruction (retries_id) where retries_id is not null;

-- **Immutable, enforced rather than described** (standing lesson L-6). The
-- application never names this column in an UPDATE — `amendable-fields` does
-- not contain it and `transition!` writes only `status` — but "no code path
-- does it" is a property of today's callers, and provenance an operator can
-- rewrite after the fact is provenance an investigation cannot rely on. The
-- guard is here so that it binds a fix-up script and a defect too, which is the
-- same reasoning migration 0002 gives for putting the append-only triggers in
-- the schema rather than in a repository.
--
-- Narrower than `reject_mutation()` on purpose: `payment_instruction` is not an
-- append-only table — its status moves along the lifecycle, and its substance
-- is amendable while it is a draft. Only this one column is frozen, so only a
-- change to this one column is refused.
create function reject_retry_link_change() returns trigger as $$
begin
  raise exception
    'payment_instruction.retries_id is set when the retry is raised and never changes: '
    'a retry is a new payment instruction (ADR-0019), and which payment it replaces '
    'is provenance rather than a field'
    using errcode = 'integrity_constraint_violation';
end;
$$ language plpgsql;

create trigger payment_instruction_retry_link_immutable
  before update of retries_id on payment_instruction
  for each row
  when (new.retries_id is distinct from old.retries_id)
  execute function reject_retry_link_change();

-- ---------------------------------------------------------------------------
-- A rejected adjustment (008-REQ observation N-5; ADR-0025)
-- ---------------------------------------------------------------------------
--
-- Migration 0012 admitted two statuses and said why there was no third:
--
--   "There is deliberately no `rejected`: an approver who disagrees simply does
--    not approve ... Naming the refusal would need a second arrow, a second
--    audit term and an endpoint nothing in this brief asks for — and a status
--    nothing can reach is worse than an absent one."
--
-- All three now exist, so the status does too. What it buys is the evidence
-- C-05 already keeps for a REJECTED PAYMENT: a decision row naming the actor
-- and the reason, and a subject that is terminal so nobody is left reading a
-- `proposed` adjustment that will never post.
--
-- Dropped and recreated by name rather than added to: a CHECK constraint has no
-- ALTER form, and `clofin.db.vocabulary-test` compares the constraint's own
-- definition in the live catalogue with `clofin.recon.adjustment/statuses` in
-- both directions, so the two move together or the build fails.

alter table reconciliation_adjustment
  drop constraint recon_adjustment_status_known;

alter table reconciliation_adjustment
  add constraint recon_adjustment_status_known
  check (status = any (array['proposed','posted','rejected']));

comment on column reconciliation_adjustment.status is
  'proposed -> posted, or proposed -> rejected. Both endpoints are terminal '
  'and are derived from clofin.recon.adjustment/transitions rather than listed '
  'twice. A rejected adjustment keeps no entry_id and no posted_at — '
  'recon_adjustment_posting_paired already required that of anything that is '
  'not posted — and it does not block a further adjustment against the same '
  'break, because recon_adjustment_posted_key is partial on the posted status.';

-- `recon_adjustment_posting_paired` is deliberately untouched. It states
-- `(status = 'posted') = (entry_id is not null)` in both directions, so a
-- `rejected` row with neither an entry nor a posting instant already satisfies
-- it, and widening a constraint that is already right is how a guard loses its
-- edge. The same is true of `recon_adjustment_posted_key`: a rejected
-- adjustment is outside its partial predicate, which is exactly why a break
-- whose adjustment was rejected can be corrected by a different one.
--
-- There is likewise no `rejected_at` column. The rejection's who, why and when
-- are the `approval` row the decision wrote — the same place, and the only
-- place, a rejected payment keeps them (`approval_rejection_needs_reason` in
-- migration 0005 makes the reason mandatory at the database for both). A second
-- copy on this table would be a second thing to keep in step with it, which is
-- standing lesson L-6 in a column.
