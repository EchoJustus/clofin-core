# ADR-0023: A CloFin-defined synthetic statement format, and an ordered matching sequence

- **Status:** Accepted
- **Date:** 2026-08-14
- **Deciders:** Worker session (TASK-008), Master Control
- **Supersedes / Superseded by:** — (extends ADR-0018 and ADR-0013; answers a
  question ADR-0018 deferred, see *Consequences*)

## Context

Increment 6 asks CloFin to meet a **second, independent account of the same
events** — the scheme's statement — and turn every disagreement into a tracked,
owned, ageing fact. Four decisions had to be taken before a line of matching
code could be written, and each is one a future session would otherwise
re-derive differently.

1. **What format is a statement in?**
2. **What is a statement compared against, and where does that comparison read
   from?**
3. **How is a line matched to a movement, and how is that explained
   afterwards?**
4. **How does a disagreement change the books?**

## Decision 1 — the format is CloFin's own, versioned, and `SIM-` prefixed

`SIM-CLOFIN-RECON-STATEMENT`, version 1. JSON, with a scheme, a currency, a
half-open period, a statement reference and a list of lines carrying a scheme
reference, an optional end-to-end payment reference, a line type, an amount in
integer minor units and a value date.

**It is deliberately not camt.053, MT940, BAI2, or any real scheme's or bank's
schema.** Two reasons, and the second is the stronger.

A synthetic-data reference implementation that parsed a real bank format would
be *fidelity theatre*: the hard parts of a real format are its optionality, its
national variants and its counterparties' habits, none of which a simulator can
produce. What would be demonstrated is a parser for messages nobody sent.

And naming one would invite exactly the misreading every scope statement in this
repository exists to prevent. `README.md`, `COMPLIANCE.md` and the API contract
all say CloFin is connected to no bank, scheme or central bank; a file marked
`camt.053` in the fixtures would be read, by somebody skimming, as evidence to
the contrary. The `SIM-` prefix on the format identifier is the same device
migration `0009` applies to scheme names, and it is load-bearing for the same
reason: it makes a synthetic record that reads as a real one unrepresentable
rather than discouraged.

The **version** is in the document and stored on every receipt. A format change
that left old documents parseable-but-different is the silent drift the
canonicalisation version in `clofin.audit` exists to make loud, and a receipt
read back after such a change must say which rules produced its matches.

### Replay is the document's identity plus a digest of its content

`statement_reference` is the delivery's identity within an organisation;
`content_digest` is a version-tagged canonical digest of the **complete semantic
content** — scheme, currency, period and every line. An exact re-delivery
reproduces the stored answer and does no work; a *different* document under the
same reference is refused and is never called a replay.

This is [ADR-0013](0013-canonical-request-digest-for-idempotency.md)'s
canonicaliser, unchanged, applied through `clofin.audit/digest` — one canonical
form and one place where "the same request" is defined. Standing lessons **L-2**
and **L-12** are why the digest covers every effect-bearing field rather than
the reference alone: audit finding **F-009** found two contradictory settlement
messages sharing one identity, and the second reported as an exact replay of a
request nobody had sent.

There is deliberately **no `Idempotency-Key` header** on ingestion. The
document's own identity is a stronger guarantee than a caller-chosen key,
because two callers delivering the same document under different keys are still
one delivery. Standing lesson **L-14** is the record of PR-040's "every mutating
operation" being read as a claim about handler families that never took the
header; this ADR states the position rather than leaving the parameter list to
imply it.

## Decision 2 — the statement is reconciled against `1300-IN-TRANSIT`, read from the journal

[ADR-0018](0018-release-posts-to-settlement-in-transit.md) made
`1300-IN-TRANSIT` CloFin's own view of value it has released and does not yet
know the fate of. That is precisely the thing a scheme's statement is a second
opinion about, so it is what a statement is reconciled against — and the
**expectations** are read from `journal_entry` and `journal_line` and from
nothing else.

Three facts are derived independently on the two sides, and the independence is
the control rather than an accident (standing lesson **L-16**):

| | The scheme's side | CloFin's side |
|---|---|---|
| Reads | `settlement_batch_item`, `payment_instruction` | `journal_entry`, `journal_line` |
| Amount from | the instruction | the posted journal line |
| Dates the movement by | when the item resolved | when the entry occurred |
| Says what kind of movement it was from | the item's outcome | the entry's **counter-account** |

The last row is the one worth pausing on. ADR-0018 gave the two finality
templates different counter-accounts — settlement extinguishes the client's
payable, a return puts the money back in the pooled client-funds asset — so
"was this a settlement or a return?" is answerable **from the accounting
alone**. That is what makes a `line-type-mismatch` a disagreement between two
records rather than a comparison of one record with itself.

A statement generated from the matcher's own keys would prove only that the
matcher agrees with itself. `clofin.settlement.statement` requires nothing under
`clofin.recon`; `clofin.recon.matching` has never seen it. The one dependency
between the contexts runs generator → format, because an adapter writing
documents in its consumer's format is a direction and mutual dependency would be
a cycle.

**Only credits, and only entries raised against a payment instruction.** A
release *debits* the clearing account — that is CloFin handing money to the
scheme, not the scheme reporting back — and an adjustment posts to the same
account as CloFin's record of a reconciliation decision, which no scheme
reports. Including either would make every release, or every resolved break,
reappear as an unmatched expectation.

## Decision 3 — four rules, in a documented order, and the rule is recorded

PR-051 asks for matching that is deterministic **and** explainable. Fuzzy,
probabilistic and learned matching are out (they are also out of TASK-008's
scope, and PRD Q4 keeps pluggability open as a *question*). What is in:

| Order | Rule | Matches when |
|---|---|---|
| 1 | `R1-reference-amount-and-value-date` | reference, amount and value date all agree |
| 2 | `R2-reference-and-amount` | reference and amount agree; dates differ |
| 3 | `R3-reference-only` | the reference agrees and the amount does not |
| 4 | `R4-amount-and-value-date` | the line carries no reference, and exactly one unmatched movement has its amount and date |

Four properties make this a specification rather than an implementation:

- **First match wins**, and the rule id is written to `reconciliation_match`.
  A match nobody can re-derive is a match nobody can defend to an auditor.
- **Rule-major** application: every line is offered rule 1 before any line is
  offered rule 2, so the strongest available evidence claims a movement first.
  Line-major would make the outcome depend on the order the scheme happened to
  list its lines, which is not a fact about the money.
- **Exactly one candidate, or no match.** A guessed match is worse than a
  break: a break is visible, and a wrong match is not.
- **The order is published** in `DOMAIN_MODEL.md` §6 and compared with the code
  in both directions and in order by a guard. That is the vocabulary-drift shape
  this repository has now used sixteen times (standing lesson **L-6**).

**Matching and agreement are separate questions**, and separating them is what
lets a break say *what* is wrong. A pair matched on reference that disagrees on
amount is the most valuable thing a reconciliation finds; calling it "unmatched"
would throw away the identification that makes it actionable. So every matched
pair is compared on amount, value date and line type, and each disagreement
becomes its own break — six break kinds, covering both directions and the three
ways a matched pair can still disagree.

### The value-date consequence, stated rather than discovered

Each side dates a movement by the **UTC calendar day of the instant it
recorded**: the scheme by the item's `resolved_at`, CloFin by the entry's
`occurred_at`. Those are two instants written by two statements in one
transaction, so they agree except when that transaction spans UTC midnight —
where the two sides genuinely disagree about the day and a `value-date-mismatch`
break is the **correct** answer rather than a defect. It is named here because a
consequence discovered by a reader is the failure this repository spends the
most effort avoiding.

## Decision 4 — a break is resolved by a new, approved, balanced entry, through the existing paths

**Nothing edits a journal entry, ever** (C-03, ADR-0008). A disagreement changes
the books through one route: a new entry between the reconciled account and
`2200-UNAPPLIED` — which `DOMAIN_MODEL.md` §4 has described as "the account
where reconciliation breaks live" since before there was any reconciliation —
posted through `clofin.ledger.service/post-entry!`, with the same zero-sum
domain check, the same account lock, the same deferred database trigger and the
same `journal-entry.posted` audit event a release gets.

Posting the difference to a **suspense** account rather than to income or
expense is deliberate: reconciliation knows the two records disagree and does not
know why. Parking the difference where it is visible and unallocated states that
honestly; writing it off to income would be a judgement this increment has no
evidence for.

**There is one approval mechanism in CloFin and this is not a second one.**
`clofin.authz.approval/evaluate` decides, unchanged and un-forked, with the
adjustment as the subject: self-approval refused first and never waivably, the
approver's per-currency ceiling applied, the decision landing in the same
`approval` table under the same no-delete guarantee. The table gained a nullable
`adjustment_id` and a check that exactly one subject is named, rather than a
second approvals table — a second table would have been a second maker–checker
control, a second no-delete guarantee and a second invalidation semantic to keep
in step.

### How many approvals, and the one adjustment-specific rule

The count comes from the organisation's own `approval_threshold` bands, per
currency, never converted ([ADR-0015](0015-approval-thresholds-are-per-currency.md)):

> **Below the lowest band the organisation has configured for the currency an
> adjustment needs no approval; at or above it, it needs that band's count, from
> actors who are not its proposer.**

The boundary is inclusive, exactly as `band-for` is inclusive, because of the
two readings that is the one asking for more scrutiny. Two consequences are
stated rather than implied (**L-14**):

- **An organisation with no band at all in the currency cannot adjust.** Treating
  "unconfigured" as "needs nobody" would be a control that silently weakens in
  exactly the organisation that has thought least about it.
- **An organisation whose lowest band starts at zero has no de-minimis**, and
  every adjustment it makes needs approval. That is the stricter setting.

The count is computed at proposal and **stored on the adjustment**, so lowering
a band later cannot post an adjustment that never cleared the bar it was raised
under. `evaluate` answers *may this actor decide*; the row answers *how many*.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Parse camt.053 (or MT940, or BAI2) | Fidelity theatre, and it would invite the misreading every scope statement here exists to prevent. The hard parts of a real format are optionality and national variants a simulator cannot produce; what would be demonstrated is a parser for messages nobody sent |
| Generate the statement from the matcher's own keys | It would prove the matcher agrees with itself, which is not a fact about anything (**L-16**). The two sides now read different tables and derive the amount, the date and the movement kind by different routes |
| Reconcile the nostro (`1200-NOSTRO`) instead | The nostro moves when a *bank* statement says it moved. CloFin has no bank; ADR-0018 explicitly left the nostro alone for that reason, and reconciling an account nothing posts to would reconcile zero against zero |
| Fuzzy or scored matching, with a confidence threshold | PR-051 asks for deterministic **and** explainable. A score is neither: two runs over the same data can differ, and "0.83" is not an explanation an auditor can act on. A rule id is |
| One break per disagreeing pair, carrying every difference | An investigator working an amount discrepancy and one working a date discrepancy do different things; collapsing them makes the queue unfilterable and the break's own kind meaningless |
| Treat a matched-but-disagreeing pair as unmatched | It throws away the identification that makes the break actionable, and turns the single most valuable finding — same payment, different amount — into two unrelated "not found" entries |
| A second approvals table for adjustments | A second maker–checker control, a second no-delete guarantee and a second invalidation semantic to keep in step with the first. Standing lesson **L-6** is the record of what one stale copy costs |
| A separate adjustment-threshold table | It would let an organisation's reconciliation policy drift from its payment policy silently, and it would need its own configuration path. The existing bands already state where the organisation wants a second pair of eyes |
| An `Idempotency-Key` header on ingestion | Weaker than the document's own identity: two callers delivering the same statement under different keys would be two deliveries. The digest covers the content, which a header cannot |
| Background/asynchronous matching | This codebase has no job runner, and one increment must not introduce one as a side effect. Matching runs synchronously on ingestion, bounded by an explicit cap that **refuses** rather than truncates |
| Truncate a period with too many movements, like every other capped read | A movement left out of the expectations is not a missing row in a list — it becomes a break against a movement that is right there in the journal, indistinguishable from a real disagreement. So the statement is *received* with `too-many-ledger-movements` and the caller is told to narrow the period |

## Consequences

**Positive**

- A reconciliation's conclusion is explainable: every match names the rule that
  produced it, and every break names what disagreed and by how much.
- The two sides are independent by construction, so the reconciliation can find
  a defect in the settlement posting itself rather than only in the simulation.
- Breaks are ageing, owned rows, so the PRD's opening framing — a break found in
  March may have originated in January — is answerable by a query.
- Every correction to the books is a new entry with an approval trail, so C-03
  and C-01 hold over reconciliation without either being restated.

**Negative / accepted cost**

- One reconciliation run is bounded at 500 ledger movements and 500 statement
  lines, and a period exceeding either is refused rather than partially
  processed. That is the honest behaviour and it is a real limit.
- A settlement transaction spanning UTC midnight produces a
  `value-date-mismatch` break. Correct, and it will look like noise to whoever
  meets it first.
- `approval` now carries a nullable `instruction_id`, which every future reader
  of that table has to notice. The check constraint and the column comments say
  why.

**Risks and how they are mitigated**

- *Risk:* the documented rule order and the code's diverge.
  *Mitigation:* `clofin.recon.matching-test` compares `DOMAIN_MODEL.md` §6's
  table with `clofin.recon.matching/rules` in both directions **and in order**.
- *Risk:* a break kind, a rule or a disposition exists in code and no product
  path reaches it (standing lesson **L-10**).
  *Mitigation:* every rule and every break kind is reachable from
  `GET /settlement-statements` through a named perturbation class and
  `POST /reconciliation-statements`, asserted end to end.
- *Risk:* an adjustment posts without the approvals it needed.
  *Mitigation:* the requirement is stored on the adjustment at proposal and read
  from the row; the posting claim is a conditional `UPDATE`; and one posted
  adjustment per break is a partial unique index.

**A question ADR-0018 deferred, and the answer.** ADR-0018 listed a per-scheme
suspense account (`1300-IN-TRANSIT-RTGS`, …) as "real, and premature — it
matters when reconciling against a scheme statement, which is increment 6's
problem". Reconciling against a scheme statement is now built, and the answer is
**still one account per currency**. `settlement_batch.scheme` records which
scheme carried each payment, so the split remains derivable without a migration;
and a statement's scheme is recorded on its receipt as *provenance* rather than
used to select an account. Splitting the account would multiply the chart of
accounts by the number of schemes to answer a question the existing columns
already answer.

## Verification

- `clofin.recon.matching-test` — the rule sequence against `DOMAIN_MODEL.md` §6
  in both directions and in order; every break kind produced by a named case;
  rule-major ordering asserted directly; ambiguity refused rather than guessed.
- `clofin.recon.statement-test` — the format identifier and version refused when
  wrong, the `SIM-` prefix required, and the digest covering every
  effect-bearing field (each field mutated in turn, and the digest asserted to
  move).
- `clofin.settlement.statement-test` — each perturbation class applied
  deterministically, and the generator asserted to require nothing under
  `clofin.recon` beyond the format constants.
- `clofin.recon.adjustment-test` — the de-minimis boundary at
  `boundary − 1`, `boundary` and `boundary + 1`, and the unconfigured-currency
  refusal.
- `clofin.api.reconciliation-api-test` — the whole walk from a generated
  statement through ingestion, breaks, adjustment and approval, including the
  replay and `409` paths and one audit event per write.
