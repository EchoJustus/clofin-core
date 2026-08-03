# 003-REQ — Authorisation, maker–checker and audit trail

| Field | Value |
|---|---|
| **Brief** | `003-TASK-authorisation-and-audit-trail.md` (on `origin/meta`) |
| **Increment** | 4 |
| **Branch** | `feat/authorisation-and-audit-trail`, stacked on `feat/payment-instruction-lifecycle` at `f529663` |
| **PR base** | `feat/payment-instruction-lifecycle` — TASK-002 is implemented but unmerged (PR #4), per AGENT_HANDOFF §1b |
| **Controls** | C-01, C-02, C-05, C-08 → ✅ |
| **Requirements** | PR-010…PR-015, PR-070…PR-075 |
| **Status** | Implemented. All four objections ruled on 2026-08-03; O-1's fix delivered as migration `0006`, O-2 ratified, O-3 and O-4 accepted as filed |

> **Meta copy.** Ingested 2026-08-03 from the submission branch
> `claude/authorisation-audit-trail-r5fzw3` (PR #5, stacked on PR #4);
> refreshed same day after the O-1 fix landed (`6f58857`, CI green). Rulings
> live in the brief's changelog
> ([TASK-003](../briefs/003-TASK-authorisation-and-audit-trail.md)). References
> to ADR-0014/0015/0016, UAT-005 and migration `0006` resolve on the submission
> branch, not on `meta`.

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
| Approval thresholds and approver limits are **per currency**, never normalised — resolving PRD **Q1** | `ADR-0015` (on the submission branch) |
| The audit trail stores **digests, not payloads**, and what that costs an auditor | `ADR-0016` (on the submission branch) |
| `PATCH` may now drive `:amend`; `:amend` added to `approved` | `ADR-0014` amendment 1 (on the submission branch) |

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
