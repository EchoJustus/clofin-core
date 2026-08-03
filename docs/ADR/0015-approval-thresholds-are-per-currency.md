# ADR-0015: Approval thresholds and approver limits are per currency, never normalised

- **Status:** Accepted
- **Date:** 2026-08-03
- **Deciders:** Technical lead
- **Supersedes / Superseded by:** —
- **Resolves:** [PRD](../PRD.md) open question **Q1**

## Context

[C-02](../COMPLIANCE.md) says the number of approvals required rises with the
amount, and that no approver may act beyond their own limit. Both halves need a
number to compare an amount against, and a payment instruction's amount carries
a currency.

[`PRD.md`](../PRD.md) §8 records this as **Q1**: *should approval thresholds be
per-currency, or normalised to a base currency?* — with the stated risk that
"multi-currency organisations get inconsistent control strength". It was
recorded rather than answered because answering it changes the product's shape.
It has to be answered now: `approval_threshold` is being created in this
increment, and a table whose key includes a currency has already answered it
implicitly.

Three forces apply.

**A normalised threshold needs an exchange rate, and a rate is a time-varying
input.** SGD 1,250.00 is above a base-currency band on Tuesday and below it on
Thursday, with nothing about the payment having changed. The control's strength
would then depend on a rate source and on the moment it was read — and CloFin
has no rate source, so introducing one to answer this question would add a
runtime dependency, a cache, a staleness policy and a failure mode to a control
whose whole value is that it is predictable.

**A past approval decision has to be reproducible.** That is the question an
investigation actually asks: *why did this payment need only one approval?*
Under normalisation the honest answer is "because of the rate at 14:02 on the
day it was approved", and reproducing the decision means reproducing the rate.
`clofin.authz.approval/evaluate` is pure specifically so that a past decision
can be replayed against the values it was decided on; a rate lookup inside it
would end that.

**Inconsistent control strength across currencies is a real cost.** The PRD's
concern is legitimate: an organisation that configures SGD bands carefully and
forgets EUR has weaker control over EUR payments. The question is what the
system does about a currency nobody configured.

## Decision

**1. Thresholds are per currency.** `approval_threshold` is keyed on
`(organisation_id, currency, from_minor)`. Amounts are compared in the
currency's own integer minor units (ADR-0003), and no conversion happens
anywhere in the approval path.

**2. Approver limits are per currency too**, for the same reason, with a
nullable `currency` intended to mean "every currency". *(That row cannot in
fact be stored — see Consequences.)*

**3. `from_minor` is inclusive**, so an amount exactly on a band boundary falls
into the **higher** band. Of the two readings this is the one that asks for more
scrutiny rather than less, and a boundary rule that has to be guessed is one the
next reader guesses differently. Asserted at boundary − 1, boundary and
boundary + 1.

**4. An unconfigured currency denies rather than defaults.** When no band covers
an amount, `evaluate` returns `{:decision :refused :reason
:no-threshold-configured}` and the payment cannot be approved at all. This is
the answer to the PRD's inconsistency concern: an organisation that forgets to
configure EUR does not get *weak* control over EUR, it gets *no* EUR payments.
The gap is loud instead of silent, which is the only version of this that is
safe.

The same rule applies to limits: an approver with no limit row for a currency
has **no** authority in it, not unlimited authority. Absent means zero
everywhere in this model.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Normalise to a base currency using a rate | Makes the control's strength depend on a rate source and the instant it was read; the same payment needs one approval or two depending on a market move. Adds a runtime dependency, a cache and a staleness policy (ADR-0004), and makes a past decision unreproducible without also reproducing the rate. |
| Normalise using a *fixed* rate table stored per organisation | Reproducible, but it is a per-currency threshold table wearing a disguise — the same configuration burden, expressed less directly, plus a second table to keep consistent with the first. |
| Per currency, defaulting an unconfigured currency to one approval | The failure is silent and in the dangerous direction: the currency nobody thought about is the one with the weakest control. This is exactly the PRD's stated risk, made real. |
| Per currency, defaulting an unconfigured currency to the strictest configured band | Arbitrary. "Strictest band from another currency" is not a policy anyone chose, and it produces requirements an operator cannot find in any table when asked to justify them. |
| Exclusive `from_minor`, so a boundary amount falls in the lower band | Defensible in isolation, but it means the amount exactly equal to the threshold is the one that escapes it — which is the amount a person structuring a payment would choose. |

## Consequences

**Positive**

- The decision is a pure function of stored values. `evaluate` reads no clock
  and no rate, so a past approval replays exactly.
- A band table is directly reviewable: an auditor reads
  `approval_threshold` and sees the policy, with no arithmetic in between.
- An unconfigured currency fails closed, and fails visibly — the refusal names
  the reason and the currency.

**Negative / accepted cost**

- **A multi-currency organisation must configure every currency it pays in.**
  This is real work and it is not automated. It is the cost of the control
  being predictable, and the mitigation is that forgetting produces a refusal
  rather than a weak approval.
- **Limits are not comparable across currencies.** A wildcard limit — one
  number applying to every currency — compares integer minor units directly, so
  100000 means SGD 1,000.00 and JPY 100,000. It is therefore only meaningful as
  a *conservative* ceiling, and an organisation with mixed currencies should
  prefer per-currency rows. Stated here because the alternative is a reader
  discovering it from behaviour.
- **The wildcard limit row cannot currently be stored.**
  `approver_limit`'s primary key is `(actor_id, currency)`, and PostgreSQL makes
  every primary key column `NOT NULL` — so the nullable `currency` the design
  calls for is silently `NOT NULL` in practice and the "every currency" row
  fails to insert. `evaluate` implements the rule regardless and is tested on
  it, so correcting the schema needs no code change. Recorded as objection
  **O-1** in [`003-REQ`](../audits/003-REQ-authorisation-and-audit-trail.md),
  awaiting a ruling; `clofin.db.audit-constraints-test` asserts the current
  behaviour so the defect cannot be forgotten.

**Risks and how they are mitigated**

- *An increment adds FX and quietly normalises thresholds.* Mitigated by this
  ADR being named in migration `0005`'s header comment and in
  `clofin.authz.approval`'s docstring, so the constraint is visible from both
  the schema and the code.
- *An operator reads `:no-threshold-configured` as a bug and "fixes" it with a
  default.* Mitigated by the refusal naming the currency and by the reason
  keyword being explicit about what is missing rather than saying "forbidden".

## Verification

- `clofin.authz.approval-test` asserts the boundary rule at boundary − 1,
  boundary and boundary + 1, across a three-band table, and asserts that an
  amount below every band has no requirement.
- The same suite asserts that a currency with no bands refuses with
  `:no-threshold-configured`, and that an approver with a limit in one currency
  has none in another.
- `clofin.authz.repository-test` asserts that bands are read per currency and
  do not leak between organisations.
- `clofin.api.approvals-api-test` asserts the boundary rule end to end, through
  the API, at all three points.
