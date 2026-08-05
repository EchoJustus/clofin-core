-- Milestone 2 remediation: settlement membership, and the scheme-response
-- receipt.
--
-- Three verified findings from the Milestone 2 external audit
-- (docs/audits/FEEDBACK-M2-settlement-and-audit-coverage.md), ruled and
-- actioned in TASK-004's FEEDBACK-M2 changelog. Each is stated here in the
-- terms the ruling used, because a migration that only shows the DDL leaves the
-- next reader to guess why the previous rule was wrong.
--
-- Numbered 0010 against the live tree at `main` `cba31c5`, where 0001..0009 are
-- applied and checksummed. Append only — an applied migration is immutable
-- (docs/ADR/0009-forward-only-sql-migrations.md, lesson L-1).
--
-- Validated against a live PostgreSQL 16 with 0001..0009 applied before this
-- file was written (lesson L-3): every row shape described below inserts, and
-- every guard described below refuses.

-- ---------------------------------------------------------------------------
-- F-007 (blocking) — a returned instruction is terminal
-- ---------------------------------------------------------------------------
--
-- Migration 0009 declared:
--
--   create unique index settlement_item_live_key
--     on settlement_batch_item (instruction_id)
--     where outcome is distinct from 'returned';
--
-- and its comment advertised a retry: "only 'returned' frees it for
-- re-batching". TASK-004's AC-7 said the same in product terms. **No public
-- workflow could reach it.** Batch eligibility is `approved`-only
-- (`clofin.settlement.batch/eligible-status`), and `:returned` is a terminal
-- state in `clofin.payments.state` with no outgoing arrow — so a returned
-- instruction was refused `not-approved` by the application while the schema
-- said it was free. The auditor demonstrated the contradiction from both sides:
-- the public retry was `422`, and a raw membership insert for the same
-- instruction committed, leaving two memberships.
--
-- **Ruled (FEEDBACK-M2, F-007): `returned` is terminal; a retry is a NEW
-- instruction** — the doctrine `settled` already follows, where a correction is
-- a new reversing instruction rather than a mutation of the old one. So the
-- permission the index advertised is withdrawn rather than the lifecycle being
-- widened to honour it: the index becomes **full uniqueness over
-- `instruction_id`**, and the auditor's raw second membership now refuses.
--
-- What this costs, stated rather than glossed: an operator whose payment came
-- back must raise a new instruction, have it approved again, and batch that.
-- That is the point. A returned payment has had money move and come back; a
-- second attempt is a second payment decision, and a second payment decision
-- gets a second maker–checker cycle. The linked-retry provenance that would
-- relate the two records (a `retries_id`-style reference and the exception
-- workflow around it) is real product surface and is deferred to increment 6
-- (reconciliation), where return-exception handling natively lives.
--
-- The index is **renamed as well as redefined**. "live" named a distinction
-- that no longer exists — every membership blocks now, so there is no live/dead
-- split to encode — and a constraint whose name asserts a rule it no longer
-- implements is the shape of defect this remediation exists to remove.

drop index settlement_item_live_key;

create unique index settlement_item_instruction_key
  on settlement_batch_item (instruction_id);

comment on index settlement_item_instruction_key is
  'The no-double-settlement guard, in the schema rather than in application '
  'code, so it binds a fix-up script and a defect as well as a handler (AC-7). '
  'An instruction belongs to AT MOST ONE settlement membership, ever — '
  'pending, settled, timed-out and returned all block a second one. It '
  'replaces settlement_item_live_key (migration 0009), which excepted '
  '`returned` and so advertised a re-batching permission no public workflow '
  'could reach: batch eligibility is approved-only and `returned` is terminal '
  'in the payment lifecycle (audit finding F-007). A returned payment is '
  'retried as a NEW instruction, which is the doctrine `settled` already '
  'follows.';

-- ---------------------------------------------------------------------------
-- F-008 — a receipt and its processing disposition are separate facts
-- ---------------------------------------------------------------------------
--
-- `scheme_response` exists to prove that a message arrived. Migration 0009's
-- own table comment says so: "kept whether or not it caused work". It was not
-- true. `clofin.settlement.service/record-scheme-response!` inserted the row
-- first and then threw a conflict when the item was not in a state the response
-- could resolve; the API wrapped both in one transaction, so the conflict rolled
-- the receipt back with everything else. The auditor sent a timeout-resolution
-- before the item had timed out, got `409`, and found zero rows. After a sweep,
-- the *identical reference* was accepted, settled the payment and posted
-- finality — so the first delivery was neither evidence nor a replay barrier.
--
-- **Ruled (FEEDBACK-M2, F-008; standing lesson L-11): receipt and disposition
-- are separate facts.** Every arrival commits its immutable receipt together
-- with a machine-readable statement of what CloFin did about it, and the `409`
-- is rendered *after* that commit rather than by throwing the receipt away.
-- Replaying a refused receipt reproduces its original no-work answer instead of
-- re-evaluating it against whatever state has arrived since.
--
-- `disposition` is deliberately a small closed vocabulary rather than free
-- text: it is read by the replay path to reproduce an answer, so a value it
-- cannot interpret would silently become a different answer.

alter table scheme_response
  add column disposition        text null,
  add column disposition_reason text null;

update scheme_response set disposition = 'applied' where disposition is null;

alter table scheme_response
  alter column disposition set not null,
  add constraint scheme_response_disposition_known
    check (disposition in ('applied','acknowledged','refused')),
  -- A refusal that does not say why is a refusal nobody can reconstruct, and a
  -- non-refusal carrying a refusal reason is two columns disagreeing.
  add constraint scheme_response_refusal_needs_reason
    check ((disposition = 'refused') = (disposition_reason is not null));

comment on column scheme_response.disposition is
  'What CloFin did about this arrival, machine-readable. applied — it resolved '
  'an item, transitioned the instruction and posted finality. acknowledged — a '
  'batch-level ack: recorded by design, moves nothing. refused — the message '
  'arrived and was kept, and the item was not in a state this kind could '
  'resolve, so no work was done and the caller received 409 AFTER this row '
  'committed. Before audit finding F-008 the third case did not exist: the '
  'conflict rolled its own receipt back, so the first delivery was unprovable '
  'and the same reference could perform work later against changed state '
  '(standing lesson L-11). Replaying a refused receipt reproduces THIS answer, '
  'never a fresh evaluation.';

comment on column scheme_response.disposition_reason is
  'Why a refused arrival was refused, as a stable code rather than prose: '
  'item-already-resolved | item-not-timed-out | item-not-in-batch. Null unless '
  'disposition is refused, and required when it is.';

-- ---------------------------------------------------------------------------
-- F-009 — replay identity covers every effect-bearing field
-- ---------------------------------------------------------------------------
--
-- The row stored batch, instruction, kind, reference and receipt time. For a
-- `timeout-resolution`, the fields that decide the payment's transition —
-- `outcome` and `reason` — travelled only in the request and were never
-- retained. Two consequences, both reproduced by the auditor:
--
--   * `timeout-resolution(settled)` and `timeout-resolution(returned, reason)`
--     under one reference were *one* response. The contradictory second arrival
--     was answered `200 replayed=true`, which is a claim that CloFin had seen
--     that request before. It had not.
--   * The duplicate's body carried `outcome: null` where the original carried
--     `outcome: "settled"` — the replay did not reproduce the answer it was
--     replaying, which is the misreporting L-7 exists to prevent.
--
-- **Ruled (FEEDBACK-M2, F-009; standing lesson L-12, extending L-2): apply the
-- idempotency posture.** `idempotency_key` already solves exactly this shape —
-- a caller-chosen key plus a canonical digest of the complete request, so an
-- exact retry replays and a different request under the same key is `409`
-- (docs/ADR/0013-canonical-request-digest-for-idempotency.md). The replay key
-- keeps its job of naming the *identity* of a delivery; the digest decides
-- whether two deliveries under that identity are the same message.
--
-- The digest is version-tagged, exactly as `audit_event`'s digests are, so a
-- later change to the canonical form is visible instead of silently
-- incomparable (`clofin.audit/canonicalisation-version`).

alter table scheme_response
  add column request_digest text null,
  add column outcome        text null,
  add column reason         text null,
  add constraint scheme_response_outcome_known
    check (outcome is null or outcome in ('settled','returned'));

comment on column scheme_response.request_digest is
  'Version-tagged SHA-256 over the canonical form of the COMPLETE semantic '
  'request — batch, instruction, kind, reference, outcome and reason — taken '
  'with clofin.idempotency/canonical, the same canonicaliser and the same '
  'posture as idempotency_key (ADR-0013). The replay key names a delivery''s '
  'identity; this digest says whether two deliveries under that identity are '
  'the same message. An exact duplicate replays the stored disposition and '
  'outcome; the same reference carrying a different digest is 409 and is never '
  'described as a replay (audit finding F-009, standing lesson L-12). Nullable '
  'only because migration 0010 is additive over rows written before it '
  'existed; every row written since carries one.';

comment on column scheme_response.outcome is
  'The outcome THIS RESPONSE claimed, normalised: settled | returned, or null '
  'for an ack and for a refused arrival that resolved nothing. Migration 0009 '
  'deliberately omitted it, reasoning that settlement_batch_item.outcome was '
  'its system of record and two columns holding one fact can disagree. Audit '
  'finding F-009 overturned that: they are not one fact. The item''s outcome is '
  'what CloFin RECORDED; this is what the simulated scheme SAID — and the two '
  'differ in exactly the case that matters, a message kept as evidence that did '
  'no work. Without it the retained record cannot prove what was claimed, and '
  'the replay cannot reproduce the answer it is replaying.';

comment on column scheme_response.reason is
  'The reason this response carried, normalised (blank becomes null). Part of '
  'the digest, because a return reason is an effect-bearing field: it is '
  'written to settlement_batch_item.outcome_reason and a returned item without '
  'one is refused by settlement_return_needs_reason.';

comment on table scheme_response is
  'Every response a simulated scheme delivered, kept whether or not it caused '
  'work — and since migration 0010 that claim is true rather than intended: a '
  'processing conflict commits its receipt with disposition = refused and the '
  'error is rendered afterwards, instead of the rejection destroying the '
  'evidence the table exists to retain (audit finding F-008, lesson L-11). A '
  'duplicate delivery is refused by scheme_response_replay_key and the FIRST '
  'row stays. Responses arrive late and out of order in the world this '
  'simulates; that is the normal case, not the exception.';

comment on column scheme_response.kind is
  'ack (batch acknowledged, instruction_id null) | settled | returned | '
  'timeout-resolution (a late answer for an item the sweep already marked '
  'timed-out). Part of the replay key and of request_digest. The outcome a '
  'timeout-resolution resolves TO is now stored here as well as on the item — '
  'see the outcome column comment for why migration 0009''s reasoning about '
  'that was wrong.';
