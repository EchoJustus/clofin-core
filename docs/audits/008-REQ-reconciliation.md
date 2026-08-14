# 008-REQ — Reconciliation: synthetic statements, deterministic matching, breaks and adjustments

| Field | Value |
|---|---|
| **Brief** | `docs/briefs/008-TASK-reconciliation.md` — on **`origin/meta`**, which is the authoritative copy and is not synced to `main` yet, so it is named rather than linked |
| **Increment** | 6 |
| **Requirements** | PR-050…PR-054 (PRD §5.6) |
| **Controls** | **C-13 authored**; C-01, C-02, C-03, C-05, C-06 reused unchanged |
| **Base** | `main` at `501556e` |
| **Branch** | `claude/clofin-reconciliation-hjgb65` |
| **Model** | `claude-opus-5` |
| **Reasoning effort** | high |
| **Date** | 2026-08-14 |
| **Verification still in flight** | **None.** See [§8](#8-verification-l-9). |

---

## 1. What was built

A statement can be generated, ingested, matched and disagreed with; every
disagreement is a tracked, owned, ageing break; and a break is closed only by a
new, approved, balanced journal entry.

| Piece | Where |
|---|---|
| The statement format — CloFin's own, versioned, `SIM-` prefixed, with its canonical content digest | `clofin.recon.statement` |
| The matching sequence, the agreement checks and the six break kinds | `clofin.recon.matching` |
| The break lifecycle, as data | `clofin.recon.break-state` |
| What an adjustment is, how many approvals it needs, and the entry it posts | `clofin.recon.adjustment` |
| Persistence, and the read that makes reconciliation mean anything (`expectations-for`) | `clofin.recon.repository` |
| The units of work, each on the caller's transaction | `clofin.recon.service` |
| Nine endpoints | `clofin.api.reconciliation`, plus one in `clofin.api.settlement` |
| The **simulated scheme's** statement generator and its eight perturbation classes | `clofin.settlement.statement` |
| Schema | `resources/migrations/0012-reconciliation.sql` |
| Decisions | [ADR-0023](../ADR/0023-a-clofin-defined-synthetic-statement-format-and-an-ordered-matching-sequence.md) |
| Control | `COMPLIANCE.md` **C-13** |
| Walkthrough | [UAT-007](../uat/UAT-007-reconciliation-and-breaks.md) |
| Diagram | `docs/diagrams/reconciliation-break-lifecycle.md`, generated |

**Endpoints.** `GET /settlement-statements` (generate),
`POST /reconciliation-statements` (ingest and match),
`GET /reconciliation-statements/{id}`, `GET /reconciliation-breaks`,
`GET /reconciliation-breaks/{id}`,
`POST /reconciliation-breaks/{id}/assignment`,
`POST /reconciliation-breaks/{id}/adjustments`,
`POST /reconciliation-adjustments/{id}/approvals`,
`GET /reconciliation-status`.

**PR-054 was built, not cut.** The brief offered an objection proposing the cut
if the status endpoint threatened the increment. It did not: the counts are one
query each over rows the module already writes, and the endpoint is the only
place PR-054's "per account and period" is answerable without a client
assembling it from a capped list.

## 2. The three decisions that shaped everything else

**The format is CloFin's own and names no real standard.** Not camt.053, not
MT940, not BAI2. A synthetic-data project parsing a real bank format would be
fidelity theatre — the hard parts of a real format are optionality and national
variants a simulator cannot produce — and a file marked `camt.053` in the
fixtures would read, to somebody skimming, as evidence against every scope
statement in the repository. `assert-shape!` refuses a document in any other
format **by name**, and the UAT script has a reviewer try one.

**The two sides are produced by different things, and nothing they agree about
was derived from the other** (L-16). The generator reads
`settlement_batch_item` and `payment_instruction`; the matcher reads
`journal_entry` and `journal_line`. The amount, the value date and *the kind of
movement* are each derived twice, by different routes — the last of those from
the entry's **counter-account**, which is answerable from the accounting alone
because ADR-0018 gave the two finality templates different counter-accounts. A
test reads `clofin.settlement.statement`'s `ns` form and asserts it requires
nothing under `clofin.recon` but the format constants.

**Matching and agreement are different questions.** Four ordered rules decide
*which movement a line is about* and record the rule id; a separate pass decides
*whether the two records agree* and opens one break per disagreement. Collapsing
them would turn the single most valuable finding — same payment, different
amount — into two unrelated "not found" entries.

## 3. Objections

Four. The first two are gaps between what a ruled ADR assigned to increment 6
and what this brief scoped; the third is a specification that the named table
cannot express without a rule the brief did not state; the fourth is a factual
correction to a premise in the brief's own traps section.

### O-1 — ADR-0019 assigned linked-retry provenance to this increment; the brief neither scoped it in nor named it out. Not built.

[ADR-0019](../ADR/0019-a-returned-payment-is-terminal-and-retries-as-a-new-instruction.md)
says, twice and in ruled text: *"Linked-retry provenance — a `retries_id`-style
reference and the exception workflow around it — is ruled to be increment 6's
(reconciliation), where return-exception handling natively lives"*, and
*"Increment 6 inherits linked-retry provenance as scoped, named work rather than
as a discovery."* `DOMAIN_MODEL.md` §2.3 on `main` repeats it.

TASK-008's **Scope → In** list does not contain it, and its **Out** table does
not name it either. So it is in neither half of the contract.

**What I did instead.** I did not build it. Diverging from a brief's scope
without a ruling is a failed handover even when the divergence is right
(AGENT_HANDOFF §1b), and a provenance column plus the workflow that reads it is
product thinking, not a column — ADR-0019 says so itself. I recorded it as an
open gap in `COMPLIANCE.md` §4 and in `DOMAIN_MODEL.md` §2.4, both pointing
here.

**What this increment *did* deliver of it**, and it is the half that made the
gap dangerous inside reconciliation: a statement line's end-to-end reference is
the **payment instruction id**, so a line about a returned original and a line
about its retry can never be confused for one another. That is the brief's own
named trap, and it is asserted end to end in
`ac-4-a-returned-payments-line-matches-the-return-and-not-a-retry`.

**Asked of Master Control.** Rule whether linked-retry provenance is a
follow-up brief, or whether TASK-008's scope should be read as containing it.

### O-2 — the batch-status-change audit term was recorded as increment 6's work, and TASK-008 did not scope it. Not built; the pointer corrected.

`COMPLIANCE.md` §4 on `main` says of C-05's one disclosed exception: *"The fix
is a distinct vocabulary term, which is vocabulary design and belongs with
increment 6's reconciliation work."* Increment 6 is this brief, and its scope
item 9 names **the reconciliation vocabulary** — new actions and subject types
for what *this* increment writes. It says nothing about settlement's.

**What I did instead.** I did not extend settlement's audit vocabulary.
Extending a control-critical enum outside a brief is exactly the divergence
§1b forbids, and the term needs a name chosen against L-7's rule rather than
picked in passing. I corrected the gap row so it no longer points at an
increment that has closed, and pointed it here.

**Asked of Master Control.** Give the term a brief, or rule that the exception
stands as disclosed.

### O-3 — AC-5's "below the threshold a single actor may post, and the threshold table says so" is not expressible in `approval_threshold` as it stands. I defined the missing rule and recorded it in the ADR.

`approval_threshold` maps an amount band to a count, and
`threshold_approvals_positive` requires that count to be **≥ 1**. There is no
row that can mean "no approvals". `clofin.authz.approval/approvals-required`
returns `nil` when no band covers an amount, and for payments `nil` is a
**refusal** (`:no-threshold-configured`) — deliberately, because inventing a
requirement is how a control silently weakens.

So "below the threshold" has no representation in the table, and the two
readings available are opposite: `nil` means *refuse* (payments) or `nil` means
*nobody needed* (which would be the silent weakening).

**What I did instead**, and it is a decision rather than a reading, so it is in
[ADR-0023](../ADR/0023-a-clofin-defined-synthetic-statement-format-and-an-ordered-matching-sequence.md)
rather than in a docstring:

> **The lowest band the organisation has configured for the currency is the
> point at which approval starts.** Below it, zero approvals. At or above it —
> inclusive, as `band-for` is inclusive — that band's count. With **no** band
> configured in the currency, no adjustment can be proposed at all.

The last clause is the load-bearing one: it is what stops "unconfigured" from
reading as "unsupervised" in exactly the organisation that has thought least
about it. Two consequences are stated rather than implied: an organisation whose
lowest band starts at zero has **no** de-minimis, and every adjustment it makes
needs approval — which is the stricter setting, and is what the existing
payments fixtures configure.

Asserted at `boundary − 1`, `boundary` and `boundary + 1` in
`clofin.recon.adjustment-test` and again through the public path in
`ac-5-the-boundary-is-tested-at-the-boundary-value`.

**Asked of Master Control.** Ratify the rule, or name the one it should have
been.

### O-4 — "C-05 is unqualified on `main`" is not quite true, and the difference matters for the merge test the brief sets.

The brief's traps section says: *"`C-05` is unqualified on `main` and must still
be after you merge."* Enumerating every copy of that claim (L-16):

| Copy | What it says on `main` |
|---|---|
| `COMPLIANCE.md` C-05 **heading** | `### C-05 Complete and attributable audit trail ✅` — no qualifier, unlike `C-09 … ✅ (partial)` |
| `COMPLIANCE.md` C-05 **statement** | carries *"with one disclosed exception: a late `timeout-resolution` that moves an already-complete settlement batch's derived status emits no batch-subject event"* |
| `COMPLIANCE.md` §4 | the same exception as an open gap |
| `api/openapi.yaml`, Audit tag | the same exception, in the contract |
| `DOMAIN_MODEL.md` §2.6 coverage paragraph | the same |

So the **status glyph** is unqualified and the **statement** discloses one
exception, in four places consistently. The brief's sentence is true of the
glyph and not of the statement.

**What I did instead.** I treated the testable form as: *the glyph stays ✅ with
no qualifier, and no write this increment adds introduces a second exception.*
Both hold. Every new write leaves exactly one audit event in its transaction,
asserted per action in `ac-8-every-write-leaves-exactly-one-audit-event`, and
the rolled-back cases leave none. The C-05 heading is untouched.

**Asked of Master Control.** Confirm that reading — and, if the intent was that
C-05's *statement* is unqualified, note that it has not been since TASK-004's
remediation and that closing it is O-2.

## 4. Decisions taken, and the two that touch code outside reconciliation

**Both are flagged because they change control-adjacent code the brief did not
name.** Neither changes a behaviour that existed; both exist to avoid a second
copy of something.

**(a) `approval` gained a second kind of subject rather than a second table.**
`instruction_id` becomes nullable, `adjustment_id` is added, exactly one is
required (`approval_names_one_subject`), and a second partial unique index gives
adjustments the same one-live-decision-per-actor guarantee. The alternative — a
second approvals table — would have been a second maker–checker control, a
second no-delete guarantee and a second invalidation semantic to keep in step,
which is standing lesson **L-6** in a table. The brief's "no second approval
mechanism" is read as covering storage as well as the decision function.

**(b) `refusal-status` and the refusal prose moved from
`clofin.payments.approval-service` into `clofin.authz.approval`.** They lived
beside the payments service while payments were the only thing approvals decided
about; reconciliation adjustments now go through the same `evaluate`, and a
second copy in a second service is the same L-6 drift. The prose is templated on
the subject so a refused approver is told *"the actor who created this
reconciliation adjustment may not approve it"* rather than the payment wording.
**`clofin.payments.approval-service/refusal-status` is kept as a name** and the
A-016 assertions in `clofin.authz.approval-test` are **unchanged**, so the
audit-derived guard still binds exactly what it bound before.

**Other decisions**, each with its reasoning in the ADR or the namespace:

- Expectations are **credits** on the reconciled account whose entry references
  a payment instruction. Releases are debits — CloFin telling the scheme
  something, not the scheme reporting back — and adjustments are CloFin's record
  of a reconciliation decision, which no scheme reports. Including either would
  make every release, or every resolved break, reappear as an unmatched
  expectation.
- The reconciliation run is **bounded and refuses** rather than truncating. Every
  other capped read in CloFin returns a correct answer over what it returned; a
  reconciliation cannot, because a movement left out becomes a break against a
  movement that is right there in the journal. The statement is still *received*,
  with `too-many-ledger-movements`.
- **No `Idempotency-Key`** on ingestion: the document's own reference plus a
  digest of every effect-bearing field it carries is stronger than a
  caller-chosen header, because two callers delivering one document under
  different keys are still one delivery.
- Break ids are generated **in the service**, not supplied by the caller: how
  many breaks a statement opens is not knowable until it has been matched.

## 5. Observations

**N-1 — eight perturbation classes, not six.** The brief names six; I added
`flipped-line-type` and `reference-stripped`. Both exist because of standing
lesson **L-10**: without them, the `line-type-mismatch` break kind and the
`R4-amount-and-value-date` rule would be reachable only from a unit test
constructing values by hand, which is a schema path with no product path behind
it. A test asserts that **every** break kind is produced by some named class.

**N-2 — the matching-rule table is `DOMAIN_MODEL.md` §6, not §2.4.** AC-9 says
"the documented rule order in `DOMAIN_MODEL.md`" without naming a section. §2.4
now carries several tables (the four entities), so a guard reading "the first
table of §2.4" would break the day a table was added. §6 is a new top-level
section whose first table is the rule sequence, which the guard addresses
unambiguously.

**N-3 — a defect the tests caught in this increment's own code.**
`clofin.recon.repository/row->break` was written without the `(when row …)`
guard every other `row->` function in CloFin carries. `find-break` therefore
returned a map of nils for an unknown id — truthy, so the handler's `if-let`
passed it on and the caller got `400` where it should have had `404`. Found by
`another-tenants-statement-break-and-adjustment-are-not-visible`, fixed, and
written into the function's docstring so the next reader knows why the guard is
spelt out.

**N-4 — a value-date consequence, named rather than left to be met.** Each side
dates a movement by the UTC day of the instant *it* recorded: the scheme by the
item's `resolved_at`, CloFin by the entry's `occurred_at`. Those are two
instants written by two statements in one transaction, so they agree except when
that transaction spans UTC midnight — where the two records genuinely disagree
about the day and a `value-date-mismatch` break is the **correct** answer. It is
in ADR-0023's *Consequences* rather than discovered by whoever meets it.

**N-5 — no `rejected` adjustment.** An approver who disagrees simply does not
approve; the adjustment stays `proposed`, never posts, and a different one may
be raised. There is no way to record *that* somebody refused, or why — which is
exactly the evidence C-05 keeps for a rejected payment. It needs a third status,
an audit term and an endpoint, which is lifecycle design and belongs in a brief.
Recorded as a gap in `COMPLIANCE.md` §4.

**N-6 — ADR-0018's deferred question is answered, and the answer is "no
change".** ADR-0018 listed a per-scheme clearing account as "real, and
premature — it matters when reconciling against a scheme statement, which is
increment 6's problem". Reconciling against a scheme statement is now built and
the answer is still one account per currency: `settlement_batch.scheme` keeps
the split derivable without a migration, and a statement's scheme is recorded as
provenance rather than used to select an account. Stated in ADR-0023.

## 6. Acceptance criteria

| # | Covered by |
|---|---|
| AC-1 | `ac-1-an-unperturbed-statement-matches-every-line-and-opens-no-break`; `ac-1-a-payment-the-scheme-never-answered-about-is-absent-and-not-a-break`; `ac-1-a-settlement-and-a-return-are-told-apart-by-the-accounting` |
| AC-2 | `ac-2-the-same-statement-delivered-twice-replays-and-does-no-work`; `ac-2-a-different-document-under-the-same-reference-is-refused` |
| AC-3 | `ac-3-a-refused-statement-keeps-its-receipt-and-replays-the-same-refusal`; `ac-3-a-document-clofin-cannot-understand-earns-no-receipt` |
| AC-4 | `ac-4-each-perturbation-class-produces-the-break-it-names` (six classes); `ac-4-the-check-runs-in-both-directions`; `ac-4-a-line-with-no-reference-still-matches-and-records-the-weaker-rule`; `ac-4-a-returned-payments-line-matches-the-return-and-not-a-retry` |
| AC-5 | `ac-5-below-the-threshold-one-actor-posts-and-the-break-resolves`; **`ac-5-the-boundary-is-tested-at-the-boundary-value`**; `ac-5-above-the-threshold-a-second-different-actor-must-approve`; `ac-5-a-two-approval-band-needs-two-different-actors`; `ac-5-an-organisation-with-no-band-in-the-currency-cannot-adjust`; and `ac-5-the-boundary-is-inclusive-and-is-tested-at-the-boundary` in the pure layer |
| AC-6 | `every-state-event-pair-is-either-permitted-by-the-table-or-refused` (enumerated, not sampled); `the-terminal-set-is-derived-and-is-not-a-second-list`; `ac-6-assigning-an-open-break-moves-it-and-reassigning-does-not`; `ac-6-a-transition-the-table-does-not-contain-is-refused-not-applied` |
| AC-7 | `ac-7-status-agrees-with-the-records-underneath-it`; `ac-7-every-rule-and-every-kind-appears-with-a-zero-rather-than-absent`; `ac-7-a-resolved-break-moves-between-the-state-counts` |
| AC-8 | `ac-8-every-write-leaves-exactly-one-audit-event`; `ac-8-a-refused-arrival-still-leaves-its-event-and-a-replay-leaves-none`; `ac-8-work-that-rolled-back-leaves-no-event`; `ac-8-an-evidence-pack-can-be-extracted-for-every-new-subject`; both OpenAPI `subjectType` copies extended and asserted by `the-audit-vocabulary-in-the-contract-is-the-one-the-service-enforces` |
| AC-9 | `ac-9-the-documented-rule-order-and-the-code-agree-in-both-directions`; `ac-9-the-guard-is-not-vacuous`; `ac-9-a-rule-renamed-in-code-alone-fails-the-comparison` |
| AC-10 | `ac-10-the-break-lifecycle-diagram-and-its-table-agree-in-both-directions`; `ac-10-a-break-transition-added-without-regenerating-fails-the-check`; `ac-10-the-break-lifecycle-diagram-draws-no-arrow-for-a-reassignment`; the `ORPHAN` check covers removal |
| AC-11 | C-13's six numbered statements each name their set; every enforcement point named exists (twelve, each a file, constraint or test); `every-enforced-control-names-at-least-one-enforcement-point` and `make diagrams-check` both pass |

## 7. Standing lessons, and where each one bit

| Lesson | Where it applies here |
|---|---|
| **L-1** | Migration `0012`, ADR-**0023** and UAT-**007** are next-available against the live tree; this REQ is task-keyed `008-REQ` |
| **L-2 / L-12** | The content digest covers scheme, currency, period and **every field of every line**; each is mutated in turn in `every-field-that-decides-an-effect-moves-the-digest`, and line **order** is part of the message |
| **L-5** | Three new append-only tables, each with `UPDATE`/`DELETE` **and** `TRUNCATE` guards, all three verbs asserted |
| **L-6** | The rule order compared with `DOMAIN_MODEL.md` §6 in both directions **and in order**; nine new closed vocabularies each owned in `clofin.db.vocabulary-test` against the live catalogue; the approvals mechanism widened rather than copied |
| **L-7** | Six new audit terms, each emitted only where its fact commits. A replayed delivery emits nothing; a proposal emits nothing named `posted`; a break's resolution is emitted only in the transaction that resolves it |
| **L-8** | `lock-break!` and `lock-adjustment!` are `for update`, in a documented order extending settlement's |
| **L-9** | §8 below, stated plainly |
| **L-10** | Every rule and every break kind is reachable from a named perturbation class through the public path — which is why there are eight classes and not six (N-1) |
| **L-11** | A statement CloFin cannot **process** commits its receipt and the refusal renders afterwards; one it cannot **understand** is `400` and earns none. Both asserted, including that the refusal replays unchanged after the gap is closed |
| **L-13** | Four `clofin.recon.service` entry points added to `clofin.audit.unit-of-work-test`'s matrix and to `clofin.ledger.purity-test`'s service set — the two lists that assert each other |
| **L-14** | C-13's six statements each name their set and their boundary; two things it does **not** claim are named in the same breath |
| **L-16** | The generator and the matcher read different tables and derive the amount, the date and the kind of movement by different routes; a test reads the generator's `ns` form and asserts the separation |

## 8. Verification (L-9)

**No verification is in flight. Nothing is still running, and I am not expecting
a result that could change this report.**

Everything below was run to completion on this branch's final tree, against
PostgreSQL 16.13:

| Command | Result |
|---|---|
| `make verify` (`test` + `docs-check` + `diagrams-check` + `doc-consistency`) | **pass** — 424 tests, 2 634 assertions, 0 failures, 0 errors; 74 markdown files' links resolve; 6 generated artifacts match their sources; 13 controls consistent |
| `clojure -M:test:it` (full suite, unit + property + integration) | **pass** — **808 tests, 5 864 assertions, 0 failures, 0 errors** |
| `clojure -M -m clofin.db.migrate` | 12 migrations applied; `clofin.db.migrate-test` migrates from an empty schema |

The assertion total moves by a few dozen between runs and that is expected
rather than alarming: `clofin.api.settlement-api-test`'s property test generates
outcome mixes of varying size, so the number of assertions inside it varies with
the seed. The test and failure counts do not move.

Two limits on that, stated rather than implied:

1. **`make smoke` was not run.** This session has no Docker daemon; the compose
   stack could not be started. Nothing in this increment changes
   `docker-compose.yml`, the Dockerfile or start-up, and CI's `stack` job covers
   it.
2. **The suite ran against a PostgreSQL 16.13 instance started by hand** rather
   than by `make db-up`, for the same reason. The schema is the one migration
   `0012` produces, and `clofin.db.vocabulary-test` compares every vocabulary
   against that live catalogue.

## 9. What the next session should pick up

- Master Control's rulings on O-1…O-4.
- The `ref-2`/Milestone-3 audit inherits this increment. The assurance-chain
  decision of **2026-08-14** on `origin/meta` applies: TASK-006 and TASK-007 are
  merged and un-audited, and this increment **modifies enforcement code in the
  authorisation domain** (`approval`'s subject, and the refusal map's home) and
  adds migrations, so amendment **A1** puts its milestone at the **Sol** tier
  rather than Terra.
- Increment 7 (financial crime) is unblocked. Nothing here reserves a term or a
  table it needs.
