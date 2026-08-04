# 003-REQ — Authorisation, maker–checker and audit trail

| Field | Value |
|---|---|
| **Brief** | `003-TASK-authorisation-and-audit-trail.md` (on `origin/meta`) |
| **Increment** | 4 |
| **Branch** | `feat/authorisation-and-audit-trail`, stacked on `feat/payment-instruction-lifecycle` at `f529663` |
| **PR base** | `feat/payment-instruction-lifecycle` — TASK-002 is implemented but unmerged (PR #4), per AGENT_HANDOFF §1b |
| **Controls** | C-01, C-02, C-05, C-08 → ✅ |
| **Requirements** | PR-010…PR-015, PR-070…PR-075 |
| **Status** | Implemented. Four Worker objections ruled 2026-08-03 (O-1 fixed in `0006`, O-2 ratified, O-3/O-4 accepted). Returned to `IN PROGRESS` by the Milestone 1 batch audit; **both blocking findings remediated — see §8** |

---

## 1. What was built

Four controls that were `📋 designed, not yet built` are now enforced, each by
something mechanical rather than by a convention.

**`clofin.authz.model`** — actors, roles, permissions. Default deny: `permitted?`
answers a question about a set, with no fallback branch and no wildcard. No role
holds every permission and no role holds both `:payment/create` and
`:payment/approve`; both are asserted by tests, so a role that quietly
accumulated the whole set fails the build rather than the next audit. A suspended
actor holds nothing at all, whatever roles are recorded against them. Permission
*sets* live in code; role *assignments* live in rows — a permission set stored as
data is editable by anyone able to write those rows.

**`clofin.authz.approval`** — the pure decision function. `evaluate` takes the
instruction, the actor, the approvals already given and the threshold table, and
returns a decision value. No database, no clock, no HTTP. Refusals are named
keywords, and the set is closed and enumerable.

The refusal *order* is the part worth reviewing. Self-approval is checked
**first**, before the role, the limit or the count, because it is the only
refusal that can never be resolved — an actor may be granted a role or a larger
limit, but the maker never becomes a valid checker for their own payment. An
actor holding both `operator` and `approver` is therefore told the reason that
governs rather than an incidental one.

**`clofin.authz.repository`**, **`clofin.payments.approval-service`** — the
persistence seam and the unit of work. The service requires no `clofin.db.*`
namespace at all: it takes the caller's transaction and composes repositories on
it. That is enforced by a new test, because a service able to open its own
connection is a service able to write an audit event outside the change it
describes.

**`clofin.audit` + `clofin.audit.repository`** — append-only event capture and
evidence extraction, split pure/persistent the way `clofin.idempotency` already
is. `record!` takes a `tx` and never opens one, so the only connection a caller
can hand it is the transaction carrying the change.

**Migration `0005-authorisation-and-audit.sql`** — the brief's SQL, verbatim,
plus comments. Numbered against the whole stack.

**`clofin.api.principal`, `clofin.api.approvals`, `clofin.api.audit`** — five new
operations, and an authenticated principal threaded through every pre-existing
one.

### The `TODO(TASK-003)` markers are gone

```
$ grep -rn "TODO(TASK-003)" src/
$ echo $?
1
```

All four are replaced with the real thing, not with a comment saying they were:

| Marker | Was | Is now |
|---|---|---|
| `api/wire.clj` `read-organisation-id` | The organisation came from the request and was documented as *not* an access control | Renamed `read-stated-organisation-id`. The organisation acted on comes from the actor's row; the stated value is **verified** against it and a mismatch is `403`, not ignored |
| `api/payments.clj` `createdBy` | Taken from the request body | The authenticated actor. A caller that sends it is refused rather than quietly overridden |
| `payments/repository.clj` `amend!` | PR-004's "by its creator" check deferred, because it would have compared two caller-asserted values | A real check against the principal |
| `payments/instruction.clj` `created-by` | "caller-asserted" | Documented as the authenticated actor, with the organisation-membership question sent to the seam where it belongs (ADR-0012) |

`TODO(increment-7)` — screening — is untouched, as the brief instructs.

### The PR-014 path, which had no driver

The brief flagged this as inherited work: the `pending-approval → draft` `amend`
event was in the lifecycle table driven by nothing, and ADR-0014 had explicitly
refused to let `PATCH` drive it. The reason ADR-0014 gave was **conditional** —
"with no approval-invalidation logic behind it, because that logic is TASK-003's
and does not exist yet."

That logic now exists, so the restriction is lifted rather than carried forward
as a rule nobody can explain. Recorded as **ADR-0014 amendment 1**, which states
what changed, what did not, and why it weakens nothing (`:amend` only ever
*destroys* approvals; acquiring them is unreachable from it).

This required adding `:amend :draft` to `approved` in the lifecycle table — see
objection **O-2**.

---

## 2. Acceptance criteria

Every criterion has a named test. Where a test's name differs from the AC label
it is given below.

| # | Covered by | Result |
|---|---|---|
| AC-1 | `authz.approval-test/ac-1-the-maker-can-never-approve-their-own-instruction` — table-driven over 4 role sets × 3 limits × 3 amounts; `.../ac-1-self-approval-is-refused-before-every-other-reason`; `api.approvals-api-test/ac-1-…` (×2, incl. an actor holding both roles) | ✅ |
| AC-2 | `authz.approval-test/ac-2-an-approver-other-than-the-maker-is-permitted`; `api.approvals-api-test/ac-2-…` | ✅ |
| AC-3 | `authz.approval-test/ac-3-an-amount-above-the-actor-limit-is-refused` (at, below, above); `.../ac-3-an-approver-with-no-limit-in-the-currency-cannot-approve`; `api.approvals-api-test/ac-3-…` (×2) | ✅ |
| AC-4 | `authz.approval-test/ac-4-the-first-of-two-approvals-does-not-complete-the-requirement`; `api.approvals-api-test/ac-4-two-approvals-are-required-above-the-band-and-the-first-does-not-suffice` | ✅ |
| AC-5 | `authz.approval-test/ac-5-a-band-boundary-is-inclusive-of-its-own-lower-bound` and `.../ac-5-the-boundary-rule-holds-across-three-bands`; `api.approvals-api-test/ac-5-an-amount-exactly-on-a-boundary-falls-in-the-higher-band` — all assert boundary − 1, boundary, boundary + 1 | ✅ |
| AC-6 | `authz.approval-test/ac-6-a-rejection-without-a-reason-is-refused` (nil, `""`, spaces, tab); `api.approvals-api-test/ac-6-a-rejection-without-a-reason-is-422`; `db.audit-constraints-test/a-rejection-without-a-reason-is-refused-by-the-database` | ✅ |
| AC-7 | `api.approvals-api-test/ac-7-amending-an-approved-instruction-invalidates-every-approval-and-returns-it-to-draft` and `.../ac-7-an-amendment-of-a-partly-approved-instruction-resets-the-count`; `payments.repository-test/amending-an-approved-instruction-invalidates-every-approval` | ✅ |
| AC-8 | `authz.approval-test/ac-8-an-actor-without-the-approver-role-cannot-approve` — every non-approver role × 2 amounts, plus no roles at all; `.../ac-8-a-suspended-approver-cannot-approve`; `api.approvals-api-test/ac-8-…` (×2) | ✅ |
| AC-9 | `authz.repository-test/ac-9-a-committed-change-leaves-exactly-one-audit-event`; `api.approvals-api-test/ac-9-every-state-change-leaves-exactly-one-audit-event` and `.../ac-9-a-refused-approval-leaves-no-audit-event` | ✅ |
| AC-10 | `authz.repository-test/ac-10-a-rolled-back-change-leaves-no-audit-event` and `.../ac-10-the-pair-holds-when-the-failure-is-the-database-refusing`; `api.approvals-api-test/ac-10-a-rolled-back-approval-leaves-no-audit-event-and-no-approval` and `.../ac-10-a-failed-transition-leaves-neither-the-change-nor-the-event` | ✅ |
| AC-11 | `db.audit-constraints-test` — raw SQL, application bypassed: `UPDATE`, `DELETE`, bulk `DELETE`, and a no-op `UPDATE`, each refused | ✅ |
| AC-12 | `authz.repository-test/ac-12-an-evidence-pack-carries-every-state-change-in-order-with-its-actor`; `api.approvals-api-test/ac-12-…` | ✅ |
| AC-13 | `api.approvals-api-test/ac-13-the-queue-carries-what-an-approver-needs-to-decide` and `.../ac-13-a-row-the-actor-may-not-approve-is-shown-with-the-reason` | ✅ |
| AC-14 | `contract-test` — every new route has a matching OpenAPI operation, in both directions | ✅ |

**AC-1 and AC-10, the two the brief says must not be compromised.** Neither was
difficult, and both are asserted twice.

AC-1 is proved by calling `evaluate` **directly, with no HTTP anywhere in the
file** — that is the assertion, not an implementation detail. `clofin.authz.approval-test`
requires no database and no handler. The API test asserts the separate thing:
that the boundary reports the domain's decision faithfully.

AC-10 is proved three ways: a deliberate `throw` after the audit write, a
constraint violation after it, and — through the API — a real duplicate approval
that the partial unique index refuses *after* the audit write has been issued on
the same transaction. All three leave no event and no change.

---

## 3. Test results

Real numbers, from a clean run on this branch.

```
$ clojure -M:test        # unit and property only — what `make test` runs
Ran 219 tests containing 1252 assertions.
0 failures, 0 errors.

$ clojure -M:test:it     # including PostgreSQL integration
Ran 422 tests containing 2333 assertions.
0 failures, 0 errors.

$ sh scripts/check-doc-links.sh
Documentation links OK (40 markdown files checked).
```

Against the base branch at `f529663`, which was verified green before any work
started — 158 tests / 900 assertions unit, 278 tests / 1572 assertions with
integration:

| | Base | This branch | Added |
|---|---|---|---|
| Unit tests / assertions | 158 / 900 | 219 / 1252 | **+61 / +352** |
| With integration | 278 / 1572 | 422 / 2333 | **+144 / +761** |

The integration figures moved after the O-1 fix: the pinning test (1 test, 2
assertions) was deleted and seven storage-level tests (21 assertions) replaced
it. Unit counts are unchanged, which is the expected shape — `0006` corrected a
schema, and the pure rule it made reachable was already implemented and tested.

Migration `0006` was also verified from an **empty schema**, applying all six in
order, rather than only as an increment on an already-migrated database — the
case a fresh `make up` hits.

`make verify` = `test` + `docs-check`, both green. `make test-it` green.

> **Environment note.** `make test` and `make test-it` shell out to
> `docker compose` when no local Clojure CLI is present, and this session had
> neither Docker nor a Clojure CLI at start. Both were installed (Clojure CLI
> 1.12.1.1550; PostgreSQL 16 as a local cluster rather than a container) and the
> underlying `clojure -M:test` / `-M:test:it` commands the Makefile invokes were
> run directly. No Makefile or `deps.edn` change was needed or made.

---

## 4. Objections

Per AGENT_HANDOFF §1b, these were recorded rather than resolved unilaterally.
None of them was worked around silently.

**All four were ruled on the day this file was filed.** Each objection below
keeps what was originally reported — so the record of what was believed at the
time survives — followed by the ruling and, where one was ordered, the fix.
O-1's fix is in this branch as migration `0006`; the others needed no code
beyond the O-2 residual (the DOMAIN_MODEL diagram, now corrected).

### O-1 — `approver_limit`'s primary key makes its documented null-currency row impossible ✅ ruled: confirmed, fixed in migration `0006`

The brief's SQL for `approver_limit` is:

```sql
create table approver_limit (
  actor_id     uuid    not null references actor (id),
  currency     char(3) null references currency (code),   -- "Null currency = applies to every currency"
  limit_minor  bigint  not null,
  primary key (actor_id, currency),
  ...
);
```

**PostgreSQL makes every primary key column `NOT NULL`.** The `null` declaration
is silently overridden, so the "applies to every currency" row the comment
describes cannot be inserted. Confirmed empirically against the applied
migration:

```
$ psql -c "insert into approver_limit (actor_id, currency, limit_minor) values ('…', null, 100);"
ERROR:  null value in column "currency" of relation "approver_limit"
        violates not-null constraint
```

**What I did when filing.** Shipped the brief's DDL **verbatim**, so that a
ruling would land against the exact thing specified rather than against my edit
of it. The domain function implemented the wildcard rule regardless and was
tested on it, so correcting the schema needed no code change. The defect was
pinned by a test asserting the *current* failure, with a comment saying to
delete it when a ruling corrected the schema.

### Ruling and fix — **confirmed as a brief defect; resolved**

Master Control confirmed the defect and ordered the fix, choosing
`unique nulls not distinct` over my suggested `coalesce(currency, '***')`
expression index. The ruling is right and my suggestion was worse on three
counts: a sentinel is a magic value every future query has to remember to
exclude; an expression index hides a business rule in an implementation detail
rather than declaring it; and — the one I had missed — a plain unique index over
a nullable column would have accepted **two** wildcard rows for one actor, since
SQL nulls are distinct by default. That would have left two contradictory
"applies to every currency" ceilings and no rule for which wins.
`NULLS NOT DISTINCT` refuses the second.

Delivered as **migration `0006-approver-limit-wildcard-currency.sql`** —
`0005` is applied and checksummed, so it is immutable and the correction is a
new migration, not an edit.

**One thing the ruling's DDL did not cover, found by verifying rather than
assuming.** Dropping the primary key does **not** drop the `NOT NULL` it
implied — PostgreSQL leaves those column marks behind. My first draft of `0006`
did exactly what the ruling specified and the wildcard insert *still* failed
with `null value in column "currency" … violates not-null constraint`. `0006`
therefore carries an explicit `alter column currency drop not null` between the
two statements, with a comment saying why. Verified against a live server, from
an empty schema, applying all six migrations in order:

```
$ psql \d approver_limit
 currency    | character(3) |           |          |          ← nullable
Indexes:
    "approver_limit_key" UNIQUE CONSTRAINT, btree (actor_id, currency) NULLS NOT DISTINCT
```

**Tests.** The pinning test `objection-o-1-a-wildcard-currency-limit-cannot-be-stored`
is deleted, as its own comment instructed. In its place:

| Test | Asserts |
|---|---|
| `db.audit-constraints-test/a-wildcard-currency-limit-can-be-stored` | The row inserts and reads back as null, not as a sentinel |
| `.../an-actor-cannot-hold-two-wildcard-limits` | The second wildcard row is refused, naming `approver_limit_key` |
| `.../an-actor-cannot-hold-two-limits-in-one-currency` | The constraint still does the per-currency job the primary key did |
| `.../a-wildcard-and-a-currency-specific-limit-coexist` | Both rows live, and two actors may each hold their own wildcard |
| `authz.repository-test/a-wildcard-limit-round-trips-and-applies-to-every-currency` | Stored → `find-actor` keys it under nil → `limit-for` honours it for every currency |
| `.../a-currency-specific-limit-beats-the-wildcard-through-the-repository` | The specific row wins **through the repository path**, and `evaluate` reading those limits reports the specific ceiling in its refusal |
| `.../set-limit!-updates-a-wildcard-row-rather-than-duplicating-it` | `on conflict` infers the new constraint, so raising a wildcard ceiling updates rather than violating |

The pure tests in `authz.approval-test` are unchanged and still pass — which is
the point worth keeping: they passed throughout the defect, because a schema
that cannot store a row can make a rule *unreachable* but not *wrong*. That is
the argument for the decision living in a pure function, and also the reason a
pure test alone was not enough. The storage tests are the half that was missing.

Documentation updated to record the ruling rather than the defect: `0006`'s
column comment supersedes `0005`'s, and `clofin.authz.approval/wildcard-currency`,
`clofin.authz.repository/limits-for`, ADR-0015 (decision 2, Consequences,
Verification) and this file all now describe a working wildcard row.

### O-2 — AC-7 requires an `amend` arrow the lifecycle table does not have ✅ ruled: resolution ratified, no revert

AC-7 reads: *"Given an **approved** instruction, when any field is amended, then
every prior approval is invalidated and status returns to `draft`."*

The lifecycle table carries `:amend` only on `pending-approval`. `approved` has
`{:release, :cancel}`. So AC-7 as written is **unreachable** under the table
TASK-002 shipped.

The two sources disagree with each other, not only with me:
`DOMAIN_MODEL.md` §3 rule 3 says `amend` applies to `pending_approval`, while
the ASCII diagram immediately above it draws the `amend` arrow from **`approved`**
back to `draft`. The code followed the rule; AC-7 followed the diagram.

**What I did.** Satisfied both: `:amend :draft` is added to `approved`, so the
event is permitted from `pending-approval` *and* `approved`. That makes AC-7
literally testable, matches the diagram, keeps rule 3 true, and gives PR-014 —
"a change to **any** instruction field invalidates approvals already given" — its
clearest case. Recorded as ADR-0014 amendment 1 with the reasoning, including
why it weakens nothing: `:amend` only ever destroys approvals, and an instruction
pulled back to `draft` must be resubmitted and reapproved from zero.

**This is a divergence from the lifecycle as TASK-002 left it, and I am flagging
it as one.** ADR-0014 decision 2 says a later increment "gets to drive the
transition, not to decide where it leads" — I have added an arrow rather than
redirected one, which I read as within that rule, but Master Control may read it
otherwise. If the ruling is that AC-7 meant only `pending-approval`, the fix is a
one-line revert of the table entry plus the two tests naming `approved`; nothing
else depends on it.

**Suggested follow-up regardless of the ruling:** `DOMAIN_MODEL.md`'s diagram and
rule 3 should be made to agree. I have updated rule 3 and the surrounding prose
on this branch; the ASCII diagram itself I left alone, because redrawing it is a
change to a document the next increment may also be editing.

### Ruling — **resolution ratified; no revert. Residual closed.**

AC-7 means what it says: an approved instruction is amendable, every approval is
invalidated, and the status returns to `draft` — PR-014's clearest case, and
without it the only path off `approved` is cancellation. The added arrow and
ADR-0014 amendment 1 stand.

Master Control ruled the contradiction a **cross-brief authoring defect** rather
than a Worker error, since both contradicting documents were authored on `meta`,
and recorded it as standing **lesson L-4**.

**Residual, now closed.** The ruling folded the diagram redraw into this fix
instruction, so my reason for leaving it alone no longer applies. `DOMAIN_MODEL.md`
§3 now draws `amend` leaving **both** `pending-approval` and `approved`, with the
two arrows converging on `draft`; the connector columns line up with the
`cancel`/`reject`/`release` drops already below the boxes, which the *original*
diagram did not. A line under it states the arrow count and points at
`clofin.payments.state/transitions` as the thing to check the drawing against —
because a diagram nobody can check against the table is how this defect happened.

### O-3 — `UAT-004` was already taken ✅ ruled: accepted; lesson L-1 widened

The brief's definition of done asks for
`docs/uat/UAT-004-segregation-of-duties.md`. `UAT-004-idempotent-submission.md`
already exists, from TASK-002.

Filed as **`UAT-005-segregation-of-duties.md`** and added to the UAT index, with
a note at the top of the script explaining the number. Same class of collision as
the migration renumbering the brief itself already corrected (`0004` → `0005`,
lesson L-1) — the standing lesson is about migrations; it may be worth widening
it to *every* sequentially-numbered artefact, since briefs, audits, ADRs, UAT
scripts and migrations are all numbered and never renumbered.

**Ruled accepted.** `UAT-005` stands and the brief's definition of done is
amended on `meta`. The suggestion was taken: **lesson L-1 is widened** from
migrations to every sequentially-numbered artefact series. Master Control noted
this as the second L-1 recurrence inside one brief — the brief renumbered its own
migration and still hard-coded a stale UAT number.

### O-4 — C-02's stated evidence is not producible from the brief's schema ✅ ruled: accepted; capture columns carried forward as debt

`COMPLIANCE.md` C-02 previously said:

> **Evidence.** Each `Approval` row records the actor, their limit at the time,
> and the threshold applied.

The brief's `approval` table has no column for either. `approver_limit` and
`approval_threshold` are both mutable and unversioned, so *the limit at the
time* is genuinely not reconstructible after the fact: a limit raised the day
after an approval is indistinguishable from one that was always that high.

**What I did.** I did not add columns — the brief's SQL is exact and O-1 already
shows what happens when the DDL and the intent diverge. Instead I rewrote C-02's
evidence paragraph to name what is *actually* extractable (the approval row, the
current band table, the amount pinned by digest in the audit event), and added a
**Known limit of the evidence** paragraph stating the gap plainly, plus a line in
COMPLIANCE §4. The brief explicitly asks for each control's "extractable evidence
named", so naming the real evidence is the instruction, not a divergence — but
the change of wording is mine and is flagged here.

**Suggested fix, for a later increment:** two columns on `approval`
(`actor_limit_minor`, `approvals_required`) captured at decision time. That is a
schema change and belongs in a brief, not in this one.

**Ruled accepted.** Rewriting C-02 to name only what is extractable *is* the
definition-of-done instruction ("extractable evidence named"), not a divergence.
The capture columns are recorded as carried-forward debt on the ROADMAP for a
future brief — a schema change belongs in a brief, exactly as judged here. No
change to this branch beyond the cross-reference in COMPLIANCE §4.

---

## 5. Decisions taken, and where the ADRs are

| Decision | ADR |
|---|---|
| Approval thresholds and approver limits are **per currency**, never normalised — resolving PRD **Q1** | [ADR-0015](../ADR/0015-approval-thresholds-are-per-currency.md) |
| The audit trail stores **digests, not payloads**, and what that costs an auditor | [ADR-0016](../ADR/0016-audit-events-store-digests-not-payloads.md) |
| `PATCH` may now drive `:amend`; `:amend` added to `approved` | [ADR-0014 amendment 1](../ADR/0014-payment-lifecycle-as-data.md) |

**On Q1 specifically**, since the brief asked for the multi-currency consequence
to be stated: per-currency thresholds mean a multi-currency organisation must
configure every currency it pays in, and that is real, unautomated work. The
alternative — normalising through an exchange rate — would make the control's
strength depend on a rate source and the instant it was read, so the same payment
would need one approval or two depending on a market move, and reproducing a past
approval would mean reproducing a past rate. The inconsistency the PRD worried
about is answered not by normalisation but by **denying** an unconfigured
currency: an organisation that forgets EUR does not get weak control over EUR, it
gets no EUR payments. The gap is loud instead of silent.

Two smaller decisions taken without an ADR, **both ratified by ruling** so they
are not re-argued:

- **A fifth refusal reason, `:no-threshold-configured`**, beyond the four the
  brief lists. The brief's four have no answer for "the organisation has not said
  how many approvals this amount needs", and the alternatives are to invent a
  number (which is how a control silently weakens) or to throw a defect (which an
  unconfigured tenant is not). It strengthens default deny rather than weakening
  any AC. Flagged because the brief's list may have been intended as exhaustive.
  **Ruled accepted:** the list was illustrative, and denying loudly is ADR-0015's
  posture applied consistently.
- **`evaluate` takes an optional `:decision`**, defaulting to `:approved`, so a
  rejection runs through the *same* function — checking `:payment/reject` and the
  maker rule, and skipping the limit and the threshold, because a ceiling is
  authority to permit a payment of a size, not to refuse one. One function rather
  than two, because C-01 is the rule that must not be stated twice and a separate
  `evaluate-rejection` is exactly where the second statement would drift. The
  brief's call shape is unchanged. **Ruled accepted**, on that reasoning.

---

## 6. Debt knowingly left

Named here and in `COMPLIANCE.md` §4, not left for a reader to discover.

| Debt | Why, and what it would take |
|---|---|
| **Ledger and organisation writes emit no audit events.** Payment instructions and approvals emit one per state change; account opening, journal posting and organisation creation do not | Those are TASK-001's endpoints and outside this brief's In-scope list, which names `clofin.audit` and the payment/approval interfaces only. A literal reading of PR-072 covers them, so **C-05 is marked ✅ with an explicit scope paragraph** rather than unqualified. The work is small — each handler wrapping its repository call in a transaction and recording on it — but it changes three of TASK-002's and TASK-001's files and belongs in its own brief |
| **The approver's limit at the time of an approval is not retained** | O-4, **accepted by ruling**: the capture columns (`actor_limit_minor`, `approvals_required` on `approval`, written at decision time) are carried forward on the ROADMAP for a future brief, because a schema change belongs in a brief |
| **Authentication does not resist an adversary** | Out of scope by the brief. `X-Actor-Id` names a seeded actor with no token and no signature; anyone who can reach the service can claim to be any actor. Said plainly in `api/openapi.yaml`, `clofin.api.principal`'s docstring and COMPLIANCE §4 — the authorisation model is real, the authentication in front of it is not |
| **No actor administration API** | Deliberate: an actor able to grant itself the approver role would make C-01 unenforceable however carefully `evaluate` is written. A real deployment needs an administered path with its own controls, and that is a brief of its own |
| **`POST /organisations` is unauthenticated** | It is the bootstrap: there is no actor until an organisation exists to hold one. The alternative was a superuser, which the brief forbids and which would be worse. Stated in the OpenAPI `X-Actor-Id` description |
| ~~The wildcard approver limit is unbuildable~~ | **Closed.** O-1 ruled a brief defect; fixed by migration `0006` and covered by seven storage-level tests |
| ~~`DOMAIN_MODEL.md` §3's ASCII diagram disagrees with itself~~ | **Closed.** O-2's residual; the diagram now draws `amend` from both `pending-approval` and `approved` |

---

## 7. Notes for the next session

- **The base is still unmerged.** This branch is stacked on
  `feat/payment-instruction-lifecycle` (PR #4). If PR #4 merges, retarget this
  PR to `main` and rebase. If PR #4 moves, rebase promptly.
- **`evaluate` is the enforcement point for two controls at once.** C-01 and
  C-02 both live in it, and it is pure so that a past decision replays. Adding a
  database read to it — for a screening outcome, say — would end that property.
  Increment 7's screening gate belongs at the `submit` precondition, where
  `TODO(increment-7)` already sits, not inside `evaluate`.
- **Increment 5 (settlement) inherits a working `approved` state.** `release`
  is in the lifecycle table, driven by nothing, exactly as `approve` was. The
  audit action vocabulary in `clofin.audit/actions` is closed and `record!`
  refuses an unknown action, so settlement will need to add its terms there —
  that is deliberate, so that "show me every release in August" has a complete
  answer.
- **Do not add a superuser.** Every fixture in the suite grants rights role by
  role, and `authz.model-test/no-role-holds-every-permission` will fail the build
  if one appears. The fixtures are readable as documentation of what a role can
  do, which is the second reason for the rule.
- **The audit digest is version-tagged.** If `clofin.idempotency/canonical` ever
  changes, bump `clofin.audit/canonicalisation-version` in the same commit, or
  every digest written after the change becomes silently incomparable to every
  one written before it.


---

## 8. Remediation addendum — Milestone 1 audit findings F-001 and F-002

Filed 2026-08-03, after `FEEDBACK-M1-foundation` returned two blocking findings,
both independently verified by Master Control, with PR #4 and PR #5 held pending
this work.

Both findings are the same shape, and it is worth naming before the detail: **a
guarantee stated over a partial set.** F-001 rested on a premise about identity
that nothing enforced; F-002 enumerated two of PostgreSQL's three destructive
verbs. Neither was a coding error. Both were claims that read as true and were
tested as true, over a domain narrower than the claim.

I reproduced both before changing anything, rather than working from the report.

### F-001 — maker–checker bypass

**Reproduced.** Actor A (operator) creates a draft; actor B, holding `operator`
*and* `approver` with a limit, submits it and then approves it:

```
1. A creates draft      -> 201 createdBy=<A>
2. B submits A's draft  -> 200 pending-approval
3. B approves it        -> 201 approved
```

One human, an approved payment, every individual check passing. `evaluate`
permitted step 3 correctly on its own terms: B is not `created-by`.

The sharpest evidence is that **C-01's own published evidence query returned a
row** — the query this document tells an auditor to run to prove the control
holds:

```sql
select s.subject_id, s.actor_id
  from audit_event s
  join audit_event a on a.subject_id = s.subject_id and a.actor_id = s.actor_id
 where s.action = 'payment.submitted' and a.action = 'payment.approved';
-- 1 row
```

**Fixed.** `:submit` is now creator-only.

| | |
|---|---|
| `clofin.payments.state/creator-only-events` | The rule, as a named set beside the lifecycle table — the same shape as `mutable-states` and `reversible-states`, per ADR-0014 decision 3. A provenance rule written into a handler is one the next handler restates differently, or omits. |
| `clofin.payments.repository/transition!` | Enforces it, under the row lock, in the transaction that carries the state change — **not** at the HTTP boundary. `transition!` is called directly by `approval-service` and by fixtures; a rule enforced only in a handler stops existing for every other caller. |
| `assert-creator!` | Generalised from amend-only to take the verb, so C-01's submit rule and PR-004's amend rule are one statement, not two that can drift. |

Ordering: provenance is checked **before** the lifecycle, mirroring `amend!`. A
non-creator gets `403` rather than a `409` carrying the instruction's status and
the list of events that would have been permitted. The opposite order is right
in `approval-service` and stays — an `approve` on a settled payment is a `409`
whoever sent it, and answering `403` first would suggest that fixing permissions
would help. Here it would not: no grant makes a non-creator the creator.

Absent actor fails **closed** (`401`), so a caller that reaches `transition!`
without a principal cannot submit.

**`:cancel` is deliberately not creator-only**, and this is the one judgement in
the fix that could reasonably have gone the other way. PR-004 names cancellation
alongside amendment as a creator's act — but only for a *draft*, and the
lifecycle also permits `cancel` from `approved`, which PR-004 never contemplates.
`controller` holds `:payment/cancel` and can never hold `:payment/create`, so
gating cancel on the creator would make that grant unexercisable. Cancellation
also destroys no control: it reaches a terminal state and can never yield an
approval. **Open question for Master Control**, recorded rather than settled:
*should cancellation of a `draft` be creator-restricted, and how does that
reconcile with `controller`'s `:payment/cancel`?* Widening it is a product
decision about who may stop a payment.
`state_test/cancel-is-deliberately-not-creator-only` exists so the decision is
reversed on purpose rather than by someone tidying the set.

The alternative the ruling named — recording a separate submitter and refusing
both — was not taken, as instructed. Noted as future work: it is the design that
would allow draft handoff between operators, which the creator-only rule
forecloses. If an organisation needs one operator to prepare and another to
submit, that is the shape to build, and it needs `submitted_by` on the row.

**Docstrings now cite the enforcement point instead of asserting the invariant**
(the L-6 instruction): `evaluate`'s comment, `evaluate`'s docstring, and
`DOMAIN_MODEL.md` §1's Maker and Checker rows.

### F-002 — TRUNCATE bypasses append-only

**Reproduced.** `UPDATE` refused, `DELETE` refused, then:

```
clofin=> truncate audit_event;
TRUNCATE TABLE
clofin=> select count(*) from audit_event;  -- 0
```

**Fixed** by migration `0007`: `before truncate … for each statement execute
function reject_mutation()` on `journal_entry`, `journal_line`, `audit_event`
and `approval`. The function is reused unchanged — it reads only
`tg_table_name` and `lower(tg_op)` and touches neither NEW nor OLD, so it is
already safe at statement level and renders "never by truncate".

Verified against a live PostgreSQL 16 **from an empty schema**, applying all
seven migrations in order (lesson L-3), before the migration was written and
again after: `TRUNCATE` and `TRUNCATE … CASCADE` both refuse, and a `CASCADE`
from an *unguarded* parent (`truncate organisation cascade`) fires the guarded
children's triggers — so the guard cannot be sidestepped by aiming one level up.

### The residue, named rather than implied

The ruling required stating plainly that triggers do not bind a schema-owner
adversary. They do not, and I verified exactly what that means rather than
describing it in the abstract. As the owning role — which CloFin connects as,
and which is also a superuser in the shipped `docker-compose.yml` — all of these
succeed:

| Attempt | As owner | As a non-owner, non-superuser role |
|---|---|---|
| `truncate audit_event` | permitted after disabling the trigger | `permission denied for table audit_event` |
| `alter table … disable trigger …` | permitted | `must be owner of table audit_event` |
| `drop trigger …` | permitted | `must be owner of relation audit_event` |
| `set session_replication_role = 'replica'` | permitted | `permission denied to set parameter` |

**One of these deserves particular attention, and I want it on the record rather
than buried.** `session_replication_role = 'replica'` disables the triggers
wholesale, which defeats the **pre-existing** `UPDATE` and `DELETE` guards as
well as the new `TRUNCATE` one — verified: `delete from audit_event` removed
every row under replica mode. So the append-only guarantee has never held
against a superuser connection, since migration `0002`. That predates F-002
rather than being introduced by it.

**I considered raising this as a separate finding (F-003) and concluded it is
not one.** `session_replication_role` is `context = superuser`, so it is
unavailable to any role that is not already able to `DROP TRIGGER` outright. It
is one more instance of the residue the ruling ordered me to name, not a new
class. Recorded here so that Master Control can overrule that reading if it
prefers a separate finding.

**I also considered and rejected `ENABLE ALWAYS` on the guards**, which would
make them fire under replica mode. Rejected because it diverges from the DDL the
ruling specified, and because it closes one superuser action while `DISABLE
TRIGGER` and `DROP TRIGGER` remain open to the same actor — raising the bar
without closing the class. It belongs in the role-split brief, where it is a
sensible belt-and-braces addition, not here.

Named debt is now in `COMPLIANCE.md` §4 with the verified evidence, C-05's
enforcement table, `ARCHITECTURE.md` §5.5, migration `0007`'s header, and
`clofin.db.audit-constraints-test/f-002-the-residue-a-trigger-cannot-close`,
which demonstrates it in a rolled-back transaction so the boundary is a passing
test rather than a paragraph.

**A false claim was removed, not just extended.** C-05's enforcement table said
the guard was "not revoked privileges — a trigger, so it holds for the owning
role too". That was wrong, I wrote it, and it is now replaced with the table
above. `ARCHITECTURE.md` §5.5 carried the same sentence.

### The riskiest part of this change was the test harness

`clofin.test-db/clean-business-data!` reset state between tests by TRUNCATEing
the very tables `0007` now guards — and its docstring said, in as many words,
that it relied on TRUNCATE bypassing the triggers. **Adding the guards broke
every integration test**, which is how a fix like this goes wrong quietly: the
tempting repairs all weaken the control.

Rejected: a session flag or GUC escape hatch in `reject_mutation()` (a guard
with a documented bypass is a guard whose bypass appears in an incident);
`session_replication_role` (superuser-only, and it would silently stop working
if the guards were ever strengthened to `ALWAYS`); `DELETE` (also refused, by
design).

Taken: disable only the **named TRUNCATE triggers**, discovered from
`pg_trigger`, inside one transaction, restoring each to the state it was found
in. Four properties, each verified:

1. **Narrow.** `disable trigger user` — my first attempt — would also have
   disarmed `journal_entry_must_balance`, the deferred trigger behind C-03.
   Verified that this matters: with it down, an unbalanced entry commits,
   because a deferred trigger disabled at INSERT queues no event to fire at
   commit. Nothing is inserted inside the window today; the narrow form cannot
   break if that stops being true.
2. **Atomic.** Disable, truncate and re-enable share one transaction, so a
   failure rolls the disable back with everything else. Verified by simulating a
   mid-cleanup failure: the guards were armed afterwards and TRUNCATE was still
   refused.
3. **State-preserving.** It restores each trigger to the `tgenabled` it found,
   not to `ENABLE`. Today every guard is `'O'` so this is identical in effect —
   but a fixture that hard-coded `enable` would downgrade an `ALWAYS` guard the
   day one is introduced, leaving the suite green and the control quietly
   weaker. That is F-002's own shape, and it is not worth re-creating to save a
   word.
4. **Drift-detecting, and not compilable away.** The declared table list is
   cross-checked against what `pg_trigger` reports, and the check `throw`s
   rather than `assert`s — `clojure.core/assert` compiles to nothing when
   `*assert*` is false, and a guard that can be compiled away is not a guard.

The docstring now says plainly that this function *is* the schema-owner
adversary COMPLIANCE §4 names, and that under the role-split it would stop
working — which is the intended outcome, not a regression.

### Tests added

18 tests, 90 assertions.

| Test | Finding |
|---|---|
| `api.approvals-api-test/f-001-the-full-exploit-chain-is-dead` | The reported chain, step by step, including C-01's evidence query returning no rows |
| `.../f-001-a-second-actor-cannot-submit-someone-elses-draft` | 403 with `errors.rule = creator-only` |
| `.../f-001-provenance-is-refused-before-the-lifecycle-is-consulted` | 403 beats 409; `permitted` is not disclosed |
| `.../f-001-cancel-remains-open-to-a-controller` | The regression guard for the cancel decision |
| `.../f-001-the-creator-can-still-submit` | So a fix that refused everyone would be caught |
| `payments.repository-test/f-001-*` (4) | The same rules below HTTP, where they are actually enforced |
| `payments.state-test/creator-only-events-is-exactly-submit` and 3 more | The set itself, including that no event `approval-service` drives is creator-only — which would invert C-01 |
| `db.audit-constraints-test/f-002-every-append-only-table-refuses-every-destructive-verb` | The full table × verb matrix, enumerated rather than sampled, asserting the message names the verb |
| `.../f-002-truncate-cannot-be-laundered-through-an-unguarded-parent` | `CASCADE` from `organisation` |
| `.../f-002-every-guard-is-armed-after-the-test-fixture-has-run` | Makes the fixture's restore non-regressable |
| `.../f-002-the-residue-a-trigger-cannot-close` | The owner bypass, demonstrated and rolled back |
| `db.ledger-constraints-test/f-002-a-posted-entry-cannot-be-truncated-away` | C-03's own file demonstrates its own control |

### Documentation

`COMPLIANCE.md` C-01 (new enforcement row, the hole and its duration, the
`X-Actor-Id` boundary), C-03 and C-05 (verb sets; the false owner clause
replaced), §4 (role-split debt with verified evidence); `DOMAIN_MODEL.md` §1;
`ARCHITECTURE.md` §5.5; `api/openapi.yaml` (submit description, `401`/`403` on
submit **and** on amend — the latter has returned 403 since TASK-003 and was
never declared; the stale amend `409` text corrected); migration `0007`.

**`UAT-002` needs singling out.** Its teardown ran `truncate journal_line,
journal_entry … cascade` under a note reading: *"Note that `truncate` succeeds
where `delete` failed … **This is deliberate** — a schema-level reset for test
environments must remain possible without weakening the row-level control."*
F-002 was written down as an intended design choice, in the acceptance evidence,
signed off. It is now a step that **asserts the refusal**, with the old sentence
quoted in a callout rather than deleted — a UAT script that once blessed a hole
is itself worth remembering. `UAT-003`'s teardown had the same statement.
`UAT-005`, the acceptance script *for C-01*, had no step in which a second actor
submits somebody else's draft — the hole sat between two passing steps — and no
TRUNCATE probe. Both added, plus a step demonstrating the owner residue.

### Verification

```
$ clojure -M:test        # what `make test` runs
Ran 223 tests containing 1257 assertions.   0 failures, 0 errors.

$ clojure -M:test:it
Ran 440 tests containing 2425 assertions.   0 failures, 0 errors.

$ sh scripts/check-doc-links.sh
Documentation links OK (40 markdown files checked).

$ grep -rn "TODO(TASK-003)" src/    # still empty
```

| | Before remediation | After | Added |
|---|---|---|---|
| Unit | 219 / 1252 | **223 / 1257** | +4 / +5 |
| With integration | 422 / 2335 | **440 / 2425** | +18 / +90 |

Migrations re-applied from an empty schema, all seven in order, before and after.

### Left open

- **Cancellation provenance** — the open question above. Needs a ruling; the
  test that pins the current decision is named.
- **`session_replication_role`** — read as part of F-002's residue rather than a
  new finding. Master Control may prefer it recorded as F-003.
- **`ENABLE ALWAYS`** — considered, rejected as out of scope, recommended for
  the role-split brief.
- **A refused submission leaves no audit event.** Audit writes happen inside the
  idempotent effect, so a rejected attempt produces no trace. Whether an
  *attempted* control violation should be recorded is a C-05 question this
  remediation did not decide, and it is not currently tested either way. Worth a
  brief: an audit trail that records only successes cannot answer "did anyone
  try?".
- **`clofin.http.response/error->problem`** puts all remaining `ex-data` on the
  wire, so `errors.rule` and `errors.attempted` are now part of the 403 contract
  (declared in OpenAPI). Its docstring still claims only "explicitly-declared
  public data" reaches the caller, which has been inaccurate since TASK-001.
  Not fixed here — it is TASK-001 code and outside this remediation — but it
  should be corrected or made true.

---

## 9. Remediation addendum — Milestone 1 audit findings F-003 to F-006

Four should-fix findings, remediated as one batch on the same branch. All four
are in this increment's code; none required a change to the brief's scope.

Two of them share a shape worth naming before the detail, because it is the
same mistake twice: **a guard that cannot see the case it is meant to judge.**
F-003's balance trigger fired on `journal_line` and so never fired for an entry
with no lines. F-004's status check read a row it did not hold and so never saw
the write that invalidated it. In both, the enforcement existed, was tested, and
passed — against every case except the one it was blind to.

---

### F-003 — a journal entry with no lines commits

**The defect.** `journal_entry_must_balance` is a deferred constraint trigger on
`journal_line`. An entry with no lines fires it **zero times**. So this
committed, cleanly, and appeared in the journal as a posted record:

```sql
begin;
insert into journal_entry (id, organisation_id, occurred_at, narrative,
                           reference_type, reference_id)
values (…, …, now(), 'No lines', 'payment_instruction', …);
commit;   -- succeeded
```

Reproduced before the fix and re-run after it. Not merely an unbalanced entry —
an entry that is not double-entry at all, and one the balance check can never
reach, because zero debits do equal zero credits and nothing runs to say so.
ADR-0008 has said "two or more lines" since TASK-001; it was prose.

**The fix — migration `0008`.** The guard had to move to the row that exists in
the failing case, so it is on `journal_entry`:

```sql
create constraint trigger journal_entry_must_be_complete
  after insert on journal_entry
  deferrable initially deferred
  for each row
  execute function assert_journal_entry_complete();
```

`assert_journal_entry_complete()` checks **line cardinality ≥ 2 first**, then
per-currency balance. Both, in one trigger, at commit.

Three decisions in that, each of which could have gone the other way:

- **Deferred, not immediate.** The foreign key forces the entry to be written
  before its lines, so *every* legal transaction passes through a state this
  trigger would refuse. Only the commit is judged. A test asserts that a
  transiently incomplete entry still commits, because that is the property a
  future reader is most likely to break while "tightening" this.
- **Cardinality before balance.** A zero-line entry is refused as
  `has 0 line(s): a double-entry record needs at least two`, not as an
  imbalance of zero against zero, which would be true and useless.
- **The balance message is byte-identical to `0002`'s**, deliberately. Two
  guards checking one invariant must not be able to disagree about what it
  means, and the first thing a reader compares is the wording. The migration
  header says so, because the duplication otherwise reads as an oversight.

Prototyped on scratch tables across six cases before the migration was written
(lesson **L-3**): zero-line refused, one-line refused, balanced two-line
commits, unbalanced gives `0002`'s wording, multi-currency balanced commits,
transiently unbalanced commits.

---

### F-004 — account status is read without a lock

**The defect.** `assert-postable!` and the debtor-account check read an
account's status and then wrote on the strength of it. CloFin runs at
`READ COMMITTED`, where every statement takes a fresh snapshot: a freeze
committing between the read and the insert is invisible to the first and fully
in effect by the second. The posting lands on a frozen account.

ADR-0012 had positively asserted the opposite — "an account frozen concurrently
is either visible to this transaction or it is not, and either way the outcome
is consistent." That is a property of a snapshot, and `READ COMMITTED` does not
give one. The ADR is corrected rather than quietly reworded.

**The fix.** Lock the rows the validation is about, in a stable order, inside
the writing transaction:

```sql
select … from ledger_account where id in (…) order by id for update
```

Applied at both sites. `order by id` is the lock order for accounts; where a
transaction locks more than one kind of row the order between kinds is fixed
too — **`payment_instruction` first, `ledger_account` second** — and is stated
in `clofin.payments.repository`'s namespace docstring rather than in a commit
message.

**Adding the lock exposed an ordering inversion that had been harmless without
it.** `create-instruction!` took its account lock before its instruction lock;
`amend!` took them the other way round. Unlocked, that is nothing. Locked, it is
a deadlock between an amendment and a creation touching the same account.
Fixed by reordering `create-instruction!` so `assert-reversal-target!` runs
before `assert-debtor-account!`.

**The riskiest part of this was the test, again.** The first version of the race
test passed against the **unfixed** code, three runs in a row — the window
between the status read and the insert is microseconds, so an ordinary two-thread
test almost never lands in it. A green test there would have certified the
defect as fixed. The rewrite makes the interleaving deterministic: the freezing
thread holds its transaction open across a latch while the posting thread runs,
so the posting must either block on the lock or race past it. Verified to fail
without the fix (`:posted`, one entry written) and pass with it.

Two smaller traps inside that: `tdb/*pool*` is a dynamic binding and dynamic
bindings do not cross thread boundaries, so the pool is captured into a local
before the threads start; and the ledger fixture uses a fixed organisation short
name, so a retry loop needs a per-attempt name.

---

### F-005 — `payment.approved` was emitted per decision

**The defect.** Every approval wrote `payment.approved`. A payment on a
two-approval band therefore had two `payment.approved` events and had been
approved once. The first of them described a payment that was still
`pending-approval`, with `beforeDigest` equal to `afterDigest` because nothing
had changed.

The trail was not merely noisy, it was **wrong in the reading an auditor would
take**: `count(*) where action = 'payment.approved'` counted decisions and
looked like it counted approved payments, and an evidence pack read literally
said the payment reached `approved` twice.

**The fix.** Standing lesson **L-7**, applied: *an action named
`<subject>.<transition>` is emitted only in the transaction where that
transition commits.*

- **`approval.recorded`** is new vocabulary, emitted for every decision, approve
  or reject, with the **approval** as its subject and the approval's own before
  and after projections. It lands in the same commit as its first emitter.
- **`payment.approved`** is now emitted only inside `(when moved …)` — the
  branch where the instruction actually transitioned — using that transition's
  own before and after.

`clofin.audit/actions` carries the rule in its docstring, so the next person
adding a term is asked which of the three it is: a decision, a partial step, or
a state change.

**C-01's published evidence query had to change with it**, and this is the part
that would have been easy to miss. The query joined two `audit_event` rows on
one subject and matched `payment.approved`. Run unchanged after this fix it
returns no rows — because it no longer looks anywhere, not because the control
holds. It now joins through the `approval` table:

```sql
select s.subject_id
  from audit_event s
  join approval ap on ap.instruction_id = s.subject_id
  join audit_event d on d.subject_id = ap.id
                    and d.action = 'approval.recorded'
 where s.action = 'payment.submitted'
   and d.actor_id = s.actor_id;
```

A test runs it, asserts no rows, then **plants the violation** by writing an
approval and an `approval.recorded` for the maker directly, and asserts the
query finds it. A control query that cannot fail is not evidence of anything —
the same lesson F-001 taught about docstrings, applied to SQL.

---

### F-006 — invalidating an approval left no trace

**The defect.** Amending an instruction invalidates every approval standing
against it (PR-014). That is a state change on real records, and it emitted
nothing. The trail said `payment.amended`; who lost their approval, when, and
under which correlation id had to be inferred from an `invalidated_at` column an
evidence pack does not show.

**The fix.** `invalidate-approvals-for!` now selects the live approvals
**`for update` before updating them** and returns `[{:before … :after …} …]`,
one pair per approval. The amend handler writes one **`approval.invalidated`**
event per pair, on the same `tx` as the amendment and its `payment.amended`
event, carrying the amending actor and the request's correlation id.

The select-then-update is not incidental. The `before` value has to be read to
be digested, and reading it unlocked is the same race F-004 is about (**L-8**).
`now()` is the transaction timestamp, so every row invalidated here shares one
`invalidated_at`; selecting first means the events describe exactly the rows
that changed rather than whatever a second scan would match.

**The evidence pack had to learn the relation.** `approval.invalidated` names
the *approval* as its subject — keying it on the payment would be F-005's
mislabelling in the other direction — so a pack built from `subject_id = ?`
could not see it. `events-for-payment` relates them through
`approval.instruction_id`:

```sql
where organisation_id = ?
  and (subject_id = ?
       or subject_id in (select id from approval where instruction_id = ?))
```

`evidence-pack` now derives `:subject-type` from the event matching the
requested subject rather than from the first event in the pack, since the first
event is whichever happened earliest and the pack now mixes two subject types.
Harmless when the requested subject is itself an approval: no approval names an
approval as its instruction, so the sub-select adds nothing.

A test asserts the amendment event and **every** invalidation event survive
together, and roll back together, since a partial write here is exactly the
failure C-05 exists to prevent.

---

### One thing found while fixing F-004, and fixed with it

F-004's lock is `select … for update`, and `for update` holds its locks until
**its transaction** ends. That makes the fix depend on a claim nothing was
checking: that a repository handed a `Connection` is a repository inside a
transaction.

`clofin.db.core/transactionally` branched on `(instance? Connection source)` and
took that as proof. It is not. The pool is configured `autoCommit true`, so a
caller passing a raw pooled connection would get every statement committed on
its own — and would get it silently, because every write still succeeds and only
atomicity is missing. Under that, `for update` releases before the insert it was
taken for and F-004 is back, with the lock still plainly visible in the SQL.

A guarantee a reader can see in the code and cannot rely on at runtime is worse
than none, so the connection is now checked rather than trusted: one
`getAutoCommit` call, refusing with "must run inside a transaction".

**And that exposed a second copy of the rule.** `clofin.ledger.repository` had
its own private `transactionally`, body-identical to `db/core`'s. Guarding
`db/core`'s therefore left unguarded the one path that actually takes the F-004
lock — the ledger's posting path. Found by the guard's own test failing for the
wrong reason. The private copy is deleted and the call site points at
`db/transactionally`; a comment stands where it was, because "there used to be
two of these" is the useful thing for the next reader to know.

Worth noting how the failure surfaced: under autocommit the entry insert
committed alone, with no lines, and **F-003's new entry-level trigger caught
it** — the wrong error for this test, but the right refusal, from a guard added
in the same batch for an unrelated reason. That is what the second enforcement
point is for.

`assert-postable!`'s docstring still opened with the sentence F-004 disproved —
"an account frozen concurrently is either visible here or is not, and either way
the outcome is consistent" — directly above the corrected paragraph. Fixed.

### An observation the tests forced, recorded rather than smuggled in

Events written in one transaction share `occurred_at` to the microsecond —
`now()` is the transaction's start time — so `id` orders them, and `id` is
random. Within a transaction the order is therefore **stable** (the same query
returns the same order every time, which is what stops an evidence pack looking
tampered with) but **not causal**.

Ordering two events that happened atomically is a question with no answer. The
tests assert action *frequencies* and cross-transaction ordering rather than a
strict sequence, and `clofin.audit.repository` says why in a comment. A
monotonic sequence column would give a causal order; it is recorded as a
candidate rather than added here, because it is a schema change and schema
changes belong in a brief.

---

### Tests added

16 tests, 90 assertions.

| Namespace | What was added |
|---|---|
| `clofin.db.ledger-constraints-test` | 5 for F-003: zero-line refused at commit, one-line refused, a transiently incomplete entry still commits, the imbalance message is unchanged, a complete entry commits. Also rewrote `an-entry-may-be-reversed-only-once`, whose fixture had been creating zero-line entries to probe a different constraint — legal before `0008`, refused after. |
| `clofin.ledger.repository-test` | 4 for F-004: the latch-based freeze-versus-post race, a posting to an already-frozen account, two postings over the same pair of accounts from opposite directions completing without deadlock, and a repository write refusing a connection that is in autocommit. |
| `clofin.api.approvals-api-test` | 7 for F-005 and F-006: a partial approval records a decision and no transition; the recorded decision describes the approval, not the payment; a rejection records both; C-01's evidence query still detects a planted violation; an amendment emits one event per invalidated approval; the amendment and its invalidations roll back together; a withdrawal appears in the payment's pack. |
| `clofin.db.audit-constraints-test` | Guard count updated for `0008` and scoped to the `public` schema. |
| `clofin.authz.repository-test` | `invalidating-approvals-leaves-them-visible` updated to the pair-returning shape. |
| `clofin.test-db` | `insert-balanced-entry!` — entry plus two mirrored lines in one transaction, which is now the only way a fixture can write an entry. |

The F-003 pair earns a note. A one-line entry can never balance —
`amount_minor > 0` rules out a zero-amount line — so the balance check refuses
it anyway, and lowering the cardinality threshold from 2 to 1 leaves the
*outcome* unchanged. Only the message differs, and it differs in a way that
sends a reader hunting for a counter-line that was never going to be there.
Verified by mutating the live function: the message assertion in
`f-003-an-entry-with-one-line-cannot-be-committed` is the only thing in the
whole suite that fails. Cardinality and balance are distinguishable in the
diagnosis long before they are distinguishable in the refusal, which is why that
assertion is load-bearing rather than cosmetic — and the test says so. The
guard-count assertion in `clofin.db.audit-constraints-test` proves the trigger is
*present*, never that it still means what it meant.

Existing tests changed rather than added: `ac-12-*` now asserts an action
multiset and ordering properties instead of a literal sequence, for the reason
in the section above; and `audit-events-can-be-listed-and-narrowed` expects 6
events rather than 5, since a decision and a transition are now two events.

### Documentation

| File | Change |
|---|---|
| `COMPLIANCE.md` C-01 | Evidence query rewritten to join through `approval`, with why the old one would have silently stopped looking. |
| `COMPLIANCE.md` C-04 | F-003's trigger added as a second enforcement point, with why two triggers and not one. |
| `COMPLIANCE.md` C-05 | The two vocabulary rules, both stated as corrections rather than as design. |
| `DOMAIN_MODEL.md` §2.6 | The audit vocabulary as a table, split by whose lifecycle each term describes. |
| `DOMAIN_MODEL.md` §5 | **I11** (≥ 2 lines) and **I12** (status read under lock). |
| `ADR-0008` | The "two or more lines" invariant made mechanical; a risk entry for vacuously-satisfied guards. |
| `ADR-0012` | Decision 2 corrected — inside the transaction is necessary and not sufficient — with the lock, the lock order, the autocommit guard, and both `FOR SHARE` and `SERIALIZABLE` recorded as considered-and-rejected alternatives with their reasons. |
| `ARCHITECTURE.md` §5.2, §5.5, §6 | Entry completeness, the locking discipline, the emission rule. |
| `api/openapi.yaml` | `AuditAction` gains `approval.recorded` and `approval.invalidated`; the enum and the evidence endpoint both document that approval events carry the approval as subject and appear in the payment's pack anyway. |
| `UAT-002` | A zero-line and one-line probe as step 11, plus a deferral check. Also renumbered the TRUNCATE step, which F-002's edit had added as a duplicate "Step 6" at the wrong heading level. |
| `UAT-005` | The evidence query updated; the expected evidence pack now names `approval.recorded` and `approval.invalidated` and says what each is; step 12's query widened to both actions. |

**`session_replication_role` in COMPLIANCE §4** — ruled as an addition to make.
On checking, it was already named there beside `DISABLE TRIGGER` and
`DROP TRIGGER` in commit `971d0d1`, in the runtime-role-split row, together with
the note that it defeats the row-level guards that have existed since `0002` and
so predates F-002. No change was needed; recorded here so the ruling is visibly
discharged rather than silently skipped.

### Verification

```
$ clojure -M:test        # what `make test` runs
Ran 223 tests containing 1259 assertions.   0 failures, 0 errors.

$ clojure -M:test:it
Ran 456 tests containing 2515 assertions.   0 failures, 0 errors.

$ sh scripts/check-doc-links.sh
Documentation links OK (40 markdown files checked).
```

| | After F-001/F-002 | After F-003–F-006 | Added |
|---|---|---|---|
| Unit | 223 / 1257 | **223 / 1259** | +0 / +2 |
| With integration | 440 / 2425 | **456 / 2515** | +16 / +90 |

Migration `0008` applied from an empty schema, all eight in order.

### Left open

- **A refused submission leaves no audit event** — deferred by ruling to its own
  brief. Untouched here, and still untested either way.
- **A monotonic audit sequence column**, so that two events written in one
  transaction have a causal order and not merely a stable one. A schema change;
  belongs in a brief.
- **`SERIALIZABLE` instead of per-site locks** — recorded in ADR-0012 as
  considered and rejected for now. It closes F-004's whole class rather than one
  instance, at the cost of a retry policy CloFin does not have. Worth revisiting
  when the number of locking sites grows.
- **`FOR SHARE` rather than `FOR UPDATE`** at F-004's two sites. It gives the
  identical freeze-versus-post guarantee without serialising concurrent postings
  to a shared account — and `1100-CLIENT-FUNDS` is on every payment, so that
  serialisation is not hypothetical. `FOR UPDATE` was the ruled fix and is what
  shipped; the weaker lock is recorded here rather than substituted, because
  lock strength is not a decision to take quietly. Note for whoever revisits it:
  a future freeze operation must not `select … for share` and then `update` the
  same row — that self-upgrade is the classic `FOR SHARE` deadlock.
- **Approver limit at the time of an approval** (O-4) and the **runtime role
  split** (F-002's residue) are unchanged and still carried in COMPLIANCE §4.
