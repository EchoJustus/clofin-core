# 005-REQ — Audit coverage completion: ledger and organisation writes

| Field | Value |
|---|---|
| **Brief** | `005-TASK-audit-coverage-completion.md` (on `origin/meta`) |
| **Increment** | 4 (completion) |
| **Branch** | `claude/audit-coverage-completion-6iuffy` — **substituted for the brief's `feat/audit-coverage-completion`**; the execution environment designates the push branch and the brief's field names a branch this session cannot create. Noted per instruction; no other divergence |
| **PR base** | `main` at `5ff00eb` — TASK-003 merged, so this is an ordinary branch off `main` |
| **Controls** | C-05 → **unqualified ✅** (was ✅ with a scope paragraph) |
| **Requirements** | PR-072, PR-075, C-05, invariants I9 and I1 |
| **Migrations** | **None.** `audit_event` is generic over subjects and needed no change |
| **Status** | Implemented. Three items raised for arbitration in §4 — none resolved unilaterally |

> **Meta copy.** Ingested 2026-08-04 from the submission branch
> `claude/audit-coverage-completion-6iuffy` (PR #6, base `main` `5ff00eb`). All
> three objections ruled the same day in the Worker's favour — rulings in the
> brief's changelog
> ([TASK-005](../briefs/005-TASK-audit-coverage-completion.md)). ADR-0017 and the
> new service namespaces resolve on the submission branch until PR #6 merges.

---

## 1. What was built

Three API writes emitted no audit event at all. They now each emit exactly one,
in the transaction that carries the change.

| Write | Action | Subject type | Composed by |
|---|---|---|---|
| `POST /organisations` | `organisation.created` | `organisation` | `clofin.organisations.service` |
| `POST /accounts` | `account.created` | `account` | `clofin.ledger.service` |
| `POST /journal-entries` | `journal-entry.posted` | `journal-entry` | `clofin.ledger.service` |

**Two new service namespaces, in the `approval-service` shape.** Each takes the
caller's `tx` and requires no `clofin.db.*` namespace at all. Both are registered
in `clofin.ledger.purity-test/service-namespaces`, so the build fails if either
acquires a connection — the property is enforced for them the same way it is for
`clofin.payments.approval-service`, rather than being a shape someone copied.

**The handler opens the transaction.** Something has to, and a service may not.
`clofin.api.{organisations,accounts,entries}` now require `clofin.db.core` and
wrap their write in `db/with-transaction`. In each, the request is parsed and the
principal resolved *before* the transaction opens, so a `400`, `401`, `403` or a
validation `422` never opens one.

**The audit write is not in the handler**, and that is the point of the split.
An event emitted by `clofin.api.accounts` would be a control that exists only for
callers arriving through `clofin.api.accounts` — the shape audit finding **F-001**
found segregation of duties in. The handler parses, opens and renders; the
service decides what is written.

**Vocabulary.** Three actions and three subject types added to `clofin.audit`, in
the same commit as the code recording them. Each of the three is a creation, so
each is written once, where the row it names first exists (L-7), with a null
before-digest. `posted` rather than `created` for a journal entry: an entry is
never drafted and never amended (C-03), so posting is the only transition it has.

**Three new subject projections** — `organisation-subject`, `account-subject`,
`journal-entry-subject` — beside the two that existed. The entry projection
covers its **lines**: an entry digest over the header alone would be identical
for two entries moving different amounts between different accounts, which is the
one thing a ledger digest must never be. `recorded_at` is deliberately outside
the projection, for the same reason `instruction-fields` excludes `created-at`:
it is database-assigned, so it is present on an entry read back and absent from
the one just posted, and a digest that differed between the two would prove
nothing. `ac-3-a-committed-posting-leaves-exactly-one-event` asserts the two
agree.

**The bootstrap identity, enforced rather than described** — see §5 and ADR-0017.

### What was *not* built

Nothing from the out-of-scope table. No account lifecycle endpoint, no read
auditing, no capture columns, no new route, no new migration. No query in
`clofin.audit.repository` or `clofin.api.audit` was touched — AC-5's payoff is
that the existing ones already answer, and that is asserted rather than assumed.

---

## 2. Acceptance criteria

| # | Covered by | Notes |
|---|---|---|
| **AC-1** | `clofin.organisations.service-test/ac-1-a-committed-registration-leaves-exactly-one-event`, `…/ac-1-a-rolled-back-registration-leaves-no-event`, `clofin.api.audit-coverage-test/ac-1-creating-an-organisation-leaves-exactly-one-event`, `…/ac-1-the-bootstrap-event-carries-no-actor-and-invents-none` | The trap is answered with a **documented null actor**, enforced by `clofin.audit/bootstrap-actions`. No `system` actor row. The endpoint stays unauthenticated |
| **AC-2** | `clofin.ledger.service-test/ac-2-a-committed-account-opening-leaves-exactly-one-event`, `…/ac-2-a-rolled-back-account-opening-leaves-no-event`, `clofin.api.audit-coverage-test/ac-2-opening-an-account-leaves-exactly-one-event` | Plus `a-refused-account-opening-leaves-no-event` for `409`, `403` and `401` through the whole stack |
| **AC-3** | `clofin.ledger.service-test/ac-3-a-committed-posting-leaves-exactly-one-event`, `…/ac-3-a-rolled-back-posting-leaves-no-event`, **`…/ac-3-the-pair-holds-when-the-rollback-is-the-database-refusing`**, `…/ac-3-the-pair-holds-when-the-database-refuses-a-different-row` | The database-refusal case is the one the AC singles out; see below |
| **AC-4** | `clofin.ledger.service-test/ac-4-the-audit-row-carries-no-payload`, `clofin.organisations.service-test/ac-4-…`, `clofin.api.audit-coverage-test/ac-4-no-payload-field-reaches-the-audit-table`, and the digest-equality assertions inside the AC-1/2/3 tests | Each stored digest is asserted **equal to the one `clofin.audit/digest` computes purely**, so an auditor holding the value can reproduce it |
| **AC-5** | `clofin.api.audit-coverage-test/ac-5-the-trail-mixes-ledger-and-payment-events-with-no-query-change`, `…/ac-5-the-trail-stays-scoped-to-its-own-organisation` | Asserts the exact ordered action list `["organisation.created" "account.created" "account.created" "journal-entry.posted" "payment.created"]` from `GET /audit/events`, plus an evidence pack for an account, an entry, the organisation and the payment |
| **AC-6** | `docs/COMPLIANCE.md` — C-05's *Scope of this control* paragraph deleted, §4's gap row removed | `ARCHITECTURE.md` §5.5 and `docs/DOMAIN_MODEL.md` carried the same claim and were corrected with it; a grep for the gap wording returns nothing |

### AC-3's database-refusal case, since it is the one that matters

`ac-3-the-pair-holds-when-the-rollback-is-the-database-refusing` posts a valid
entry through the service — entry, lines and audit event all written — and then
inserts a **third, unbalancing line into that same entry** before the transaction
commits. `journal_entry_must_balance` is `deferrable initially deferred`, so the
insert succeeds and the **commit** is what fails. No application code raises and
nothing catches: the failure arrives after the service has returned. The test
asserts the audit count is unchanged, the entry count is zero, and — so the test
cannot pass for an incidental reason — that the refusal message names the
balance.

This is the case the transaction shape exists for. An event written on its own
connection would have survived a posting the database then refused, and no test
of an application-level throw would have caught it.

---

## 3. Test results

| | Tests | Assertions |
|---|---|---|
| Baseline at `5ff00eb`, `make verify` | 223 | 1259 |
| **This branch, `make verify`** | **233** | **1321** |
| Baseline at `5ff00eb`, `make test-it` | 456 | 2515 |
| **This branch, `make test-it`** | **489** | **2747** |

Both green, 0 failures and 0 errors. Run against PostgreSQL 16 from
`docker-compose.yml` via the repository's own targets.

New test namespaces, registered in `clofin.test-runner`:

- `clofin.organisations.service-test` — the I9 pair for `organisation.created`,
  the bootstrap null, and that a refused registration records nothing.
- `clofin.ledger.service-test` — the I9 pairs for `account.created` and
  `journal-entry.posted`, including both database-refusal cases.
- `clofin.api.audit-coverage-test` — the whole claim end to end through router,
  middleware, authorisation and error boundary: every write leaves one event,
  every refusal leaves none, no payload reaches the table, and the existing audit
  queries surface the new events unchanged.

Extended: `clofin.audit-test` (the new vocabulary, the three projections, the
bootstrap rule), `clofin.contract-test` (the published `AuditAction` and
`subjectType` enums must equal `clofin.audit/actions` and
`clofin.audit/subject-types`), `clofin.ledger.purity-test` (both new services),
`clofin.authz.repository-test` (see §4, O-3).

---

## 4. Objections and items for arbitration

Per AGENT_HANDOFF §1b, recorded rather than resolved unilaterally.

### O-1 — the Definition of Done forbids OpenAPI changes that AC-1 requires, and that correctness requires

The DoD says:

> No lifecycle, route or OpenAPI changes — assert the contract test still passes
> unchanged

AC-1 says, of the bootstrap identity:

> say it in the OpenAPI description and the test

These cannot both be honoured literally. I read the DoD as meaning *no change to
the API surface* — no new path, no new operation, no changed schema shape — and
`clofin.contract-test`'s route-and-operation assertions pass **unchanged**, which
is the mechanical form of that claim.

Under that reading I changed `api/openapi.yaml` in two ways:

1. **Descriptions.** `createOrganisation` states the bootstrap identity and the
   null `actorId` (required by AC-1); `createAccount` and `postJournalEntry` state
   that a successful call appends one event in the same transaction, and that
   posting emits one event per entry rather than one per line. `AuditEvent.actorId`'s
   description now says the null is *enforced*, not merely conventional.

2. **Two enums, which I believe the change makes mandatory.**
   `AuditAction` gained the three new terms and `AuditEvent.subjectType` gained
   `organisation`, `account`, `journal-entry`. Without this the published contract
   would be **false**: `GET /audit/events` returns actions the schema does not
   declare, and its `?action=` filter — which `$ref`s `AuditAction` — would tell a
   caller that `journal-entry.posted` is not a legal value while the service
   accepts it and returns rows for it. A contract that misdescribes its own
   responses is worse than one that lags.

   I added `clofin.contract-test/the-audit-vocabulary-in-the-contract-is-the-one-the-service-enforces`
   so the two copies of that list can never diverge again. This is L-6's shape:
   the contract *relied* on the enum matching the code, and nothing checked it.

**Requested ruling:** confirm the reading, or name which of the two changes should
be reverted. I have not reverted either, because reverting (2) would ship a
contract I know to be untrue.

### O-2 — "next available number in the audits series" collides with the series' own naming rule

The DoD says the REQ takes the *next available number in the audits series*.
Verified against the live tree (L-1): `origin/meta:docs/audits/` holds `001-REQ`,
`002-REQ`, `003-REQ` and `FEEDBACK-M1`, so the next free integer is **004**.

But `docs/audits/README.md` defines the series by identity, not by order:

> `REQ` files are `NNN-REQ-<subject>.md` (a Worker's completion report and audit
> request for `TASK-NNN` …)

004 therefore belongs to TASK-004, which is in flight on a sibling branch. Taking
it would guarantee a collision with the settlement Worker's own REQ and would
break the join between a REQ and the brief it reports on.

**Filed as `005-REQ-audit-coverage-completion.md`**, matching TASK-005. I believe
this is what the DoD meant — "verify, do not hard-code" (L-1) — but it is a
literal divergence from the wording, so it is recorded rather than assumed. If
briefs are to keep saying "next available", the phrase should say *"the number
matching your task"* for this series, since the two rules disagree whenever a
lower-numbered task has not yet filed.

### O-3 — the null-actor rule tightens `clofin.audit/event` for pre-existing actions, and two existing tests had to change

Enforcing the bootstrap null (L-6, and see §5) means `clofin.audit/event` now
**refuses** a null `actor-id` for any action outside `bootstrap-actions`. That is
a contract change for actions that existed before this brief.

Two tests in `clofin.authz.repository-test` were passing `:actor-id nil` on
payment actions as fixture convenience:
`a-digest-written-here-matches-one-computed-purely` and
`an-evidence-pack-does-not-cross-organisations`. Neither asserted anything about
the actor; both now name one from the same fixture, and what they assert is
unchanged.

I judged this in scope rather than a divergence: the rows those fixtures were
writing are the exact shape migration `0005`'s own column comment calls a defect
("a null here on a payment action would be a defect"), and a rule that exempted
existing actions would leave C-05's attribution claim unfalsifiable from the
table. I added `a-payment-event-with-no-actor-is-refused` in the same file so the
refusal is asserted through `record!` against a real database, not only through
the pure function.

**Requested ruling:** confirm the tightening is wanted. Narrowing it (enforcing
only for the new actions) is a two-line change if Master Control prefers the
smaller blast radius, but it would make `actor_id is null` ambiguous again.

### Not an objection — an architectural statement I amended, flagged for visibility

`ARCHITECTURE.md` §3 said *"the ledger depends on nothing."* Composing a ledger
write with its audit event makes the ledger context depend on `clofin.audit` —
as the payments context already does. I amended the sentence to
*"the ledger's **domain** depends on nothing"* and added the general rule:
**audit is the one context every writing context depends on, and it depends on
none of them.** That direction is acyclic — `clofin.audit` holds a vocabulary and
a digest and knows nothing about accounts, entries or payments; each context
supplies its own projection.

The alternative was to keep the arrow out of the ledger by composing in the
handler, which is F-001's shape and is argued against in §1. `ARCHITECTURE.md`
travels with the code and is the Worker's to update, so this is recorded for
visibility rather than raised as an objection — but it is the largest
architectural statement this small brief touches, and it is the one an auditor
should look at first.

---

## 5. Decisions taken, and where the ADR is

**ADR-0017 — the bootstrap write records no actor, and the null is enforced.**
Numbered against the live tree (L-1): `origin/meta:docs/ADR/` ends at `0016`.

The decision has two halves and the second is the one that matters:

1. `POST /organisations` records `actor_id = null`. A seeded `system` actor row
   was rejected — an `actor` row is a thing that can hold roles and approval
   limits and authenticates by `X-Actor-Id` like any other, so one that exists
   solely to sign the audit trail is an unadministered identity appearing in the
   column an auditor reads as attribution. Worse than a null, because it looks
   like a person. Making the endpoint authenticated was ruled out by brief 003
   and restated by this brief.

2. **That null is enforced, not documented.** Migration `0005` already carried the
   sentence *"a null here on a payment action would be a defect"* — and that is
   standing lesson **L-6** verbatim: a load-bearing claim in a comment with no
   enforcement point. `clofin.audit/bootstrap-actions` now names the one action
   permitted to carry a null actor, and `clofin.audit/event` refuses every other
   action one. Since `record!` builds every event through `event`, there is no
   path to the table around it.

The rule is one-directional on purpose: a bootstrap action *may* be actorless, it
is not required to be, so an administered organisation-creation path arriving
later records its principal without the set changing.
`every-bootstrap-action-is-itself-a-known-action` asserts the set is exactly
`#{"organisation.created"}`, so widening it is a visible control decision rather
than an edit.

---

## 6. Debt knowingly left

| Debt | Why, and what it would take |
|---|---|
| **No database `CHECK` constraint behind the null-actor rule** | The rule is enforced in `clofin.audit/event`, which every write reaches through `record!` — there is no application path around it. A `check (actor_id is not null or action = 'organisation.created')` would also bind a defect that bypassed `clofin.audit` entirely, and a fix-up script run by hand. It needs a migration, which this brief asks not to add without necessity, and I could not demonstrate necessity. Recorded as a candidate rather than deferred silently; it is a one-statement migration whenever the next schema brief lands |
| **Account freeze and close still emit nothing** | Because they are still SQL, not API operations (ROADMAP increment-2 debt), and the brief names an *absent* operation as a backlog item rather than this brief's problem. `account-fields` already covers `status`, so when those operations arrive their before and after digests will differ without the projection changing — the groundwork is done, the events are not |
| **A refused write still leaves no security event** | Unchanged and deliberate: C-05 scopes the trail to state *changes*, and recording attempted control violations is a distinct control with different volume and retention. Already ruled a future-brief candidate by Master Control on 2026-08-03 |
| **`occurred_at` cannot order two events written in one transaction** | Pre-existing and unchanged. None of the three new writes emits more than one event, so this brief adds no new instance of it |

---

## 7. Notes for the next session

**The transaction boundary moved, and one consequence is worth knowing.**
`clofin.ledger.repository/post-entry!` used to be handed the pool, so its
`db/transactionally` opened *and committed* inside its own `try`, and
`posting-failure!` saw every violation. It is now handed the handler's `tx`, so
the **commit happens outside that catch**. I checked what this changes: only the
two `deferrable initially deferred` constraint triggers fire at commit
(`journal_entry_must_balance`, `journal_entry_must_be_complete`); every unique and
foreign-key constraint in the schema is immediate and still fires inside the
insert. Both deferred triggers were already in `posting-failure!`'s `:else`
branch — deliberately rethrown untranslated, because if they fire the domain
constructor and the HTTP layer both missed an unbalanced entry, and that is a
CloFin bug rather than a caller error. So no response code changed. **If a future
migration makes a constraint deferrable, check this again**: it would newly escape
translation.

**The `for update` locks from F-004 are unaffected and slightly safer.**
`assert-postable!` takes them inside the posting transaction, which is now the
handler's — so they are held until the handler's commit rather than the
repository's, which is *longer*, and covers the audit write. Lock order is
unchanged (`order by id`), so no new deadlock ordering exists.

**If you add an action to `clofin.audit/actions`, three things now follow it.**
The `AuditAction` enum in `api/openapi.yaml` (asserted equal by
`clofin.contract-test`), a subject type whose name is the action's prefix
(asserted by `clofin.audit-test/every-action-names-a-subject-type-that-exists`),
and an actor — unless you deliberately add the term to `bootstrap-actions`, which
is itself asserted to be exactly one term long.

**TASK-004 shares two sorted-set literals with this branch.** `clofin.audit/actions`
and `clofin.audit/subject-types`, as the brief anticipated. Whichever lands second
rebases; the conflict is the literal plus, now, the matching `AuditAction` enum in
`api/openapi.yaml` and possibly the ADR number if the settlement brief also files
one. I did not read that branch.
