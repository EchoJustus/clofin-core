# 006-REQ — `ref-1` release-audit remediation (A-001…A-019)

| Field | Value |
|---|---|
| **Reviews** | `FEEDBACK-REL-ref-1.md` on `origin/meta` — the partial `ref-1` release audit, 19 findings, 2 blocking. Cited as a path rather than linked, as `004-REQ` and `005-REQ` cite theirs: `FEEDBACK` files live on the control plane and do not resolve from this branch |
| **Series number** | **006.** The `REQ` series is task-keyed (`NNN-REQ` reports on `TASK-NNN`), and this batch reports on **no brief** — it is a release-remediation batch dispatched against a `FEEDBACK` file. 006 is the next free integer in the series, verified against the live `origin/meta:docs/audits/` tree at `2ccbfd3` (001–005 present, 006 free) rather than assumed (**L-1**). Recorded explicitly because taking a number by ordinal is exactly what L-1 warns about, and a reader who applies the task-key rule to this file will look for a `TASK-006` that does not exist |
| **Branch** | `claude/clofin-ref-1-remediation-pizicn` — designated by the execution environment, substituted for the brief's `feat/ref-1-remediation`. No other divergence |
| **PR base** | `main` at `5d21334` — the `ref-1` RC itself |
| **Provenance** | `claude-opus-5` · reasoning effort **high** · 2026-08-05 |
| **Migrations** | **`0011-purpose-codes-and-line-currency.sql`** — next available, verified against the live tree and `resources/migrations/index.txt` (0001–0010 applied and checksummed), and validated against a live PostgreSQL 16 before this file was written (**L-3**) |
| **Controls touched** | C-05, C-06, C-08, C-09, C-10, C-11, C-12 — **statements narrowed, no control weakened**. C-08 **strengthened**: `GET /organisations/:id` is now inside the permission model |
| **Status** | Implemented. All 19 findings actioned. Two items raised for arbitration in §5; neither resolved unilaterally |
| **Verification** | `make verify` **284 tests / 1,743 assertions**, 0 failures, 0 errors, docs-check 46 files. `make test-it` **602 tests / 3,989 assertions**, 0 failures, 0 errors. Migrations replayed `0001`→`0011` from an empty schema: 11 applied, 0 pending. **No verification is in flight** (**L-9**) — see §7 |

---

## 1. The two blocking findings

### A-006 — `GET /organisations/:id` was outside the permission model entirely

`clofin.api.organisations` did not require `clofin.api.principal` at all. Anyone
who could reach the service and hold — or guess — a tenant UUID could read its
legal name, short name and status. C-08 claims enforcement "at the API boundary,
on every operation", and one business route made that false.

- **`:organisation/read`** added to `clofin.authz.model/permissions`, the closed
  15-value set. There is no `:organisation/create` beside it: creation is the
  documented unauthenticated bootstrap, and no actor exists to hold a permission
  before the organisation that holds actors (ADR-0017).
- **Granted to all five roles.** The permission reads *the actor's own*
  organisation — a foreign one never reaches the repository — so it discloses to
  an actor the tenant they already act for. A role that could raise a payment and
  not read the name of the organisation it belongs to is a role no interface can
  render. The boundary that matters here is the **tenant** one, and it is enforced
  on the organisation acted on rather than on the verb. §5 O-1 records the
  alternative I did not take.
- **The handler calls `principal/for-request`** like every other business handler,
  then `principal/assert-organisation!` on the **path** id — so the path is
  *verified, not trusted*, exactly as `organisationId` is in every body and query
  string.
- **A foreign organisation is `403`, not `404`.** Deliberate, and the brief is
  right to name it: scoping the lookup by the principal's organisation would have
  produced `404` as a side effect, and that turns an authorisation boundary into
  an existence oracle in reverse — a caller could distinguish "no such
  organisation" from "not yours" by whether its own id worked. The `404` survives
  only for the case it describes: the principal's own organisation row is missing.

Three tests in `clofin.api.ledger-api-test`, named for the finding:
unauthenticated `401`; wrong-organisation `403` with the refused organisation's
name absent from the body; a roleless actor `403` naming `organisation/read`. The
existing `an-organisation-can-be-created-and-read-back` covers the permitted
`200` and passed unchanged, because its fixture already authenticates.

`the-auditor-role-is-read-only` had to change and is worth noting: it held a
hard-coded allowlist of the four reads that existed when it was written.
`:organisation/read` was the first addition, and the diff to admit it is exactly
where a *write* would be waved through, because it would look like the four
before it. It is now a rule over the permission name — `(= "read" (name
permission))` — which is what "read-only" actually means.

### A-012 — the published contract had no satisfiable instance

`CreatePaymentInstructionRequest` **required** `createdBy` while
`clofin.api.payments/caller-may-not-set` **refuses** it. No conformant request
could succeed. Alongside that, 12 actor-protected operations declared no way to
supply a principal.

- `createdBy` removed from `required` **and** from `properties`. Removing it from
  `required` alone would have left it declared under
  `additionalProperties: false`, which reads as an invitation to send it. The
  schema description now says it is derived from the principal and refused if
  sent.
- `ActorId` added to all 12 operations (4 account, 2 journal, 6 payment) with
  their reachable `401` and `403`. `getOrganisation` makes 13, from A-006 — so
  every route in the table now declares the parameter except the three
  health/info routes and the organisation bootstrap.
- `organisationId` made optional in `CreateAccountRequest`,
  `PostJournalEntryRequest`, `AmendPaymentInstructionRequest` and
  `CreatePaymentInstructionRequest`, each with a description saying the principal
  supplies it and a stated mismatch is `403` — it is still accepted, because it is
  verified rather than ignored.

Two contract tests guard the class rather than the instances.
`a-012-a-conformant-create-payment-request-can-be-satisfied` pins the specific
defect; `a-012-every-actor-protected-operation-declares-the-actor-header`
**discovers** which operations authenticate by reading whether their handler
namespace requires `clofin.api.principal`, and asserts each declares the
parameter and both statuses. That discovery is crude on purpose: a list would
have to be kept in step by hand, and `GET /organisations/:id` sat outside every
hand-kept list of "the authenticated routes" for two audits. It carries a
non-vacuity assertion — `(- (count route-table) 4)` — because a discovery that
quietly found nothing would pass every assertion under it.

---

## 2. Code and contract findings

| Finding | What changed |
|---|---|
| **A-013** | Six OpenAPI statements corrected against the handlers: settlement **exists** and drives release/finality (with `failed` named as the one transition nothing drives); PATCH is **not** draft-only and creator authentication **is** built, under the row lock; `settlement-batch` added to the evidence-subject prose in both places it appears; the scheme-response outcome is the caller's `kind`/`outcome` and the last-digit table is **guidance for composing a request**, not a constraint this operation enforces; idempotency described as method + path + body |
| **A-015** | `clofin.audit/subject-type-for` derives the subject type from the action's prefix (with the `payment.*` → `payment-instruction` exception), and `event` refuses a pair that disagrees. `payment.approved` about an `account` — two individually valid values — was accepted and stored. Tested both as the named pair and across **every** wrong combination of the 20 actions and 6 subject types |
| **A-016** | `replay-key-conflict` added to `response/refusal-reasons`, and the service now reads its code *and its prose* from there instead of inlining both at the one call site. Published as `SchemeResponseRefusalReason`. Migration `0011` corrects `0010`'s column comment. Separately: `clofin.authz.approval-test` now requires `clofin.payments.approval-service` and compares the key sets of `refusal-status` and `refusal-detail` with `approval/refusal-reasons` — the build failure the docstring had been promising with nothing behind it (**L-6**) |
| **A-017** | `assert-shape!` refuses a `returned` outcome carrying no reason, on **both** routes to it, as `:field-validation` → `422` (ADR-0012: understood, one named field rejected on its merits). The test's `#{400 422 500}` is replaced by exact statuses plus assertions that the instruction did not move and that **no receipt was written** — a well-formed message earns a receipt, a typo does not |
| **A-018** | Migration `0011` constrains `payment_instruction.purpose_code` to the 15 codes. Code↔OpenAPI equality in `clofin.contract-test`; code↔SQL equality from the live catalogue in `clofin.db.vocabulary-test` |
| **A-019** | `CurrencyCode`, the 21-value enum, replaces `^[A-Z]{3}$` on `Money.currency` and every other currency field. The contract test compares it with `clofin.money/currencies` **and** asserts that no `properties.currency` anywhere in the spec is left describing a shape — discovered, not listed, the same way the `subjectType` guard works |
| **A-002** | Migration `0011` adds `ledger_account (id, currency)` as a unique key and `journal_line (account_id, currency)` as a composite foreign key to it. DOMAIN_MODEL I6 restated and its enforcement column rewritten. Validated live: a `USD` line against an `SGD` account refuses; matching lines commit |
| **A-009** | `clofin.error/internal` marks diagnostic data, `public-data`/`internal-data` split it, `error->problem` renders only the public half and `wrap-errors` logs the internal half. Every repository that attached a PostgreSQL constraint name now marks it internal. Tested in the **development** profile deliberately: the exposure was not profile-dependent, so neither is the fix |

### A-014 — the drift guards were one-directional, and now are not

The finding is the important one in this batch, because every test it names was
**passing**. `clofin.db.vocabulary-test` is new and does three things:

1. **Discovers** every closed vocabulary in the live catalogue by *shape* —
   `pg_get_constraintdef` normalises `check (col in (…))` to `= ANY (ARRAY[…])`,
   so one marker finds all of them, including the nullable ones, and including a
   constraint someone named without a `_known` suffix.
2. Asserts **set equality in both directions** against the code that owns it, for
   all 16 (the 15 the audit found, plus `payment_purpose_code_known` from
   `0011`).
3. Asserts the **set of vocabulary constraints itself** equals the owner table —
   so a closed vocabulary added to the schema with no owner in code is loud
   rather than silently unguarded.

Two vocabularies had **no owner at all** and were declared as part of the fix:
`clofin.authz.model/actor-statuses` and `clofin.authz.approval/decisions`. The
second replaced an inline `#{:approved :rejected}` inside `evaluate`, so it is
load-bearing rather than decorative; the first is asserted in
`clofin.authz.model-test` to grant nothing for every non-`:active` member, plus a
value neither side knows.

**I verified the guard fails, in both directions**, rather than trusting a green
run: adding `'zombie'` to `actor_status_known` on the live database produced the
extra-SQL-value failure — the exact case the audit says every previous guard was
blind to — and an unowned check constraint produced the unowned-vocabulary
failure. Both were then reverted and the suite re-run green.

The three superseded guards are **kept and renamed**, not deleted, because each
still proves something the catalogue comparison does not: that every code role
appears in migration `0005`'s text, that every settlement code value appears in
`0009`'s, and that every lifecycle state can actually be **stored** — a code
value the constraint would refuse is a `500` in production, and that test catches
it in the act. Each now carries a comment saying which half of the claim it is
and where the other half lives. Renaming them was the point: a test called
`…-are-the-ones-in-the-migration` asserting `…-appear-in-the-migration` is the
false-guard shape all over again.

---

## 3. Documentation truthfulness (L-14)

Eight statements narrowed to what is enforced, or with the exception named in the
same sentence. **No control was weakened; nothing was deleted to make a sentence
true.**

| Finding | Was | Is |
|---|---|---|
| **A-001** | "every role set, every limit, every amount" | six role sets, three ceilings, three amounts — with the note that the maker is refused in **every** cell, which *is* exhaustive and is the property the control turns on |
| **A-003** | I7 "every entry references the business object that caused it" | "names a reference type from a closed vocabulary and a reference id, both required" — **shape only**, nothing resolves the reference; resolution named debt |
| **A-004** | C-05 unqualified; DOMAIN_MODEL "there is no qualification left on C-05" | both name the late-batch-status exception in the headline, agreeing with the disclosure that was already 200 lines below it |
| **A-005** | C-06 "every mutating operation requires an `Idempotency-Key`" | scoped to the six `execute-once!` operations, with the four handler families outside it named **and** what guards them instead |
| **A-007** | C-09 "sensitive values never reach a log" | scoped to request and configuration logging, stating plainly that C-11 logs defects in full and that both sentences could not be true |
| **A-008** | C-10 "the schema of any environment is identifiable, comparable and tamper-evident" | scoped to the **migration history**, with what a direct `ALTER TABLE` leaves untouched spelled out |
| **A-010** | C-12 "every third-party component in the runtime path" | scoped to the seven **direct** dependencies, noting ADR-0004 claims an SBOM the repository does not contain |
| **A-011** | the contract test read as proving OpenAPI vs the handlers | ARCHITECTURE §7 and the OpenAPI `info` block both now say what it proves and what it does not, and cite A-012 as the drift that passed green |

`api/openapi.yaml`'s `IdempotencyKey` parameter carried A-005's wording too and
is narrowed the same way. `docs/PRD.md` PR-040 is **left alone**: it is a
*requirement*, and a requirement not yet met is not a false claim — §4 now
records the shortfall.

Four **new §4 rows**, each naming the deferred mechanism, what it would do, and
the operational-hardening brief as its target: log sanitiser (A-007), live-schema
catalogue hashing (A-008), transitive SBOM (A-010), deep OpenAPI/handler
validation (A-011). A fifth row records the batch-status-change audit term, which
A-004's narrowing now points at from three documents. Every one of the five has
its claim narrowed **now**, so no document keeps an unsupported statement while
the debt is open.

---

## 4. Verification

- `make verify` — 284 tests / 1,743 assertions, 0 failures, 0 errors; docs-check
  46 files. Baseline at `5d21334` was 272 / 1,504.
- `make test-it` — 602 tests / 3,989 assertions, 0 failures, 0 errors. Baseline
  was 584 / 3,644 (the audit) and 584 / 3,659 on this machine; the assertion
  count varies with a generated property, which is why the test count is the
  figure worth comparing.
- Migrations replayed `0001`→`0011` against a schema dropped to empty: 11
  applied, 0 pending.
- Migration `0011`'s constraints validated **by hand against a live PostgreSQL
  16** before the file was committed (**L-3**): a cross-currency journal line
  refuses, matching lines commit, `purpose_code = 'NOPE'` refuses, `'SUPP'`
  commits.
- The A-014 guard verified to **fail** on injected drift in both directions, then
  reverted.

---

## 5. Objections and items for arbitration

Per AGENT_HANDOFF §1b, recorded rather than resolved unilaterally.

### O-1 — `:organisation/read` is granted to every role, and the brief did not say which roles should hold it

The brief says "grant it to the roles that should hold it" and leaves the set to
me. I granted it to all five, reasoning that the permission reads only the
actor's *own* organisation and that the tenant boundary is enforced separately,
on the organisation acted on.

The alternative I considered and rejected was restricting it to `compliance` and
`auditor`, on the grounds that an operator has no operational need for the legal
name. I rejected it because it makes the model *look* tighter while protecting
nothing: an operator already reads the organisation's accounts, payments and
journal entries, all of which are more sensitive than its registered name, and a
grant that excludes only the least sensitive read is theatre. It would also break
any client that renders the tenant it is acting for.

**Requested ruling:** confirm the five-role grant, or name the subset. If a
subset is ruled, `a-006-an-actor-without-the-permission-is-refused` already
provides the shape for a per-role assertion.

### O-2 — the brief's A-016 instruction, read literally, would publish a value the system cannot produce

The brief says to add `replay-key-conflict` to "`response/refusal-reasons`,
migration `0010`'s documented vocabulary and the OpenAPI enum". Two of those are
unambiguous. The third is not, and the literal reading is wrong.

`replay-key-conflict` is **never stored**. It is refused *because* a receipt for
that identity already exists, and writing a second row would defeat the replay
key that produced the refusal — I re-read `record-scheme-response!` to confirm
the branch returns before any write. Adding it to
`SchemeResponseRecord.dispositionReason`'s enum, which describes the **stored**
column, would publish a value no receipt can carry: the same class of untrue
statement as A-014's, in the other direction, and precisely what L-14 asks me not
to do.

So I published **two** enums: `SchemeResponseRefusalReason`, the complete set a
caller may receive under `errors.dispositionReason` (four values), and
`StoredSchemeResponseRefusalReason`, what may reach the column (three).
`SchemeResponseRecord.dispositionReason` `$ref`s the second. `response.clj`
declares both sets, the contract test compares each with its own enum, and
migration `0011`'s comment on `disposition_reason` states the asymmetry and why
it exists. Migration `0010` is applied and immutable, so the correction is made
in `0011` rather than by editing it (ADR-0009).

**Requested ruling:** confirm the two-enum split, or direct that the stored enum
be widened. I have not widened it, because I would be shipping a contract I know
to be untrue.

---

## 6. Debt this batch knowingly leaves

- **The four deferred mechanisms** — log sanitiser, catalogue hashing, transitive
  SBOM, deep contract validation — are named in COMPLIANCE §4 with the
  operational-hardening brief as their target, as the brief directs. Each
  statement that depended on them is narrowed now.
- **The batch-status-change audit term** (A-004) is vocabulary design and belongs
  with increment 6's reconciliation work; it is now named in §4 as well as
  disclosed in C-05's prose.
- **`clofin.contract-test` still does not invoke a handler.** A-011's narrowing
  says so in two documents; the mechanism is §4's fourth new row.
- **`docs/PRD.md` PR-040 remains unmet**, deliberately. It is a requirement, not
  a claim, and the shortfall is recorded in COMPLIANCE §4 rather than by editing
  the requirement to match what was built — which would be the L-14 failure with
  the sign flipped.

---

## 7. Verification status at completion (L-9)

**Nothing is in flight.** `make verify` and `make test-it` were both run to
completion on the final tree, green, with the counts in §4. The migration replay
from empty and the by-hand constraint validation were run on the final
`0011`. The A-014 negative checks were run and reverted before the final suite.
No review, test run or adversarial pass of mine is still executing, and I have no
pending fix I expect to push after this report.

The one thing I did **not** do is re-run the audit's own items 5–7, which it never
performed — those carry forward to `ref-2` as mandatory-first scope by the
resource-interruption fallback, and reproducing them here would be a Worker
grading its own remediation.

---

## 8. Files touched

**Source.** `authz/model.clj` (`:organisation/read`, `actor-statuses`),
`authz/approval.clj` (`decisions`), `api/organisations.clj` (A-006),
`audit.clj` (A-015), `settlement/response.clj` (A-016, A-017),
`settlement/service.clj` (A-016), `error.clj`, `http/response.clj`,
`http/middleware.clj` and five repositories (A-009).

**Migrations.** `0011-purpose-codes-and-line-currency.sql`, `index.txt`.

**Contract.** `api/openapi.yaml` — A-012, A-013, A-016, A-019, and the A-005 and
A-011 narrowings.

**Tests.** New: `clofin.db.vocabulary-test`. Extended: `contract-test`,
`audit-test`, `authz/model-test`, `authz/approval-test`,
`api/ledger-api-test`, `api/settlement-api-test`, `http/middleware-test`.
Renamed with narrowed claims: `settlement/batch-test`,
`payments/repository-test`. Registered: `test_runner.clj`.

**Documentation.** `COMPLIANCE.md` (C-05, C-06, C-09, C-10, C-11, C-12, §4),
`DOMAIN_MODEL.md` (I6, I7, audit coverage), `ARCHITECTURE.md` §7.

**Untouched.** Every control-plane file: `docs/briefs/`, `docs/audits/` apart
from this REQ, `docs/ROADMAP.md`, `docs/AGENT_HANDOFF.md`, `docs/PRD.md`.
