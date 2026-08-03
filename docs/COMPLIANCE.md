# CloFin — Control Design and Mapping

**Status:** living document · **Last reviewed:** 2026-08-03

> **This is a modelling exercise, not a compliance attestation.**
>
> CloFin holds no licence or authorisation, is not connected to any bank,
> payment scheme or central bank, and processes only synthetic data. Nothing in
> this document constitutes regulatory advice, a control assurance opinion, or a
> claim that CloFin satisfies any obligation. The references to regulatory
> themes below describe *why a control was designed the way it was* — they are
> not assertions of compliance with any specific instrument.

The purpose of this document is to show the discipline: that each control was
chosen for a reason, is enforced by something mechanical, and can be evidenced.

---

## 1. How to read this

Each control has:

- a **statement** of what must be true,
- the **design** — how CloFin makes it true,
- the **enforcement point** — the code, constraint or test that makes it hold,
- the **evidence** an auditor could extract.

A control with no mechanical enforcement point is marked as such. Being honest
about which controls are procedural is more useful than claiming they are all
automated.

Status: ✅ enforced · 🔨 partial · 📋 designed, not yet built

---

## 2. Controls

### C-01 Segregation of duties ✅

**Statement.** The actor who creates or submits a payment instruction must not
be the actor who approves it.

**Design.** Maker–checker is a domain rule in `clofin.authz.approval`, evaluated
when an approval is recorded — not a UI restriction and not a database
convention. `evaluate` takes the instruction, the actor, the approvals already
given and the threshold table, and returns a decision. It reads no database and
no clock, so the rule is provable without a request and a past decision replays
against the values it was decided on.

Self-approval is refused **first**, before every other reason. It is the only
refusal that can never be resolved: an actor may be granted a role or a larger
limit, but the maker never becomes a valid checker for their own payment. An
actor holding both `operator` and `approver` is therefore told the reason that
governs rather than an incidental one.

The permission model reinforces it before any instruction exists: no role holds
both `:payment/create` and `:payment/approve`, and a test asserts it.

**Enforcement points.**

| | |
|---|---|
| `clofin.authz.approval/evaluate` | The rule itself. Refuses with `:self-approval`, with no HTTP layer involved. |
| `clofin.payments.state/creator-only-events` + `clofin.payments.repository/transition!` | **Only an instruction's creator may submit it.** This is what makes the row above a complete test: `evaluate` compares an approver against `created-by` and nothing else, which is maker–checker only while the submitter and the creator are the same actor. Enforced under the row lock, in the repository rather than the handler, so a caller that reaches `transition!` by another route is refused too. |
| `clofin.authz.model/role-permissions` | No role is both maker and checker. |
| `clofin.api.approvals` | Reports the domain's decision; it does **not** make one. The boundary deliberately does not pre-empt `evaluate`'s ranking of the reasons. |

**Where this control had a hole, and for how long.** Until Milestone 1's
external audit (finding **F-001**), submission was gated by a permission and
not by provenance. An actor holding `operator` and `approver` could submit
somebody else's draft — becoming its maker in every sense that mattered — and
then approve it, because `created-by` still named the other person. The claim
that this could not happen was written in a docstring and enforced nowhere
(standing lesson **L-6**). Reproduced end to end before the fix: the control's
own evidence query below returned a row.

**Boundary of this control.** It holds against the application and against a
defect in it. It does **not** hold against an adversary who can choose the
`X-Actor-Id` header, because authentication is a seeded actor with no token and
no signature — see §4. C-01 is enforced; the identity it is enforced against is
asserted by the caller.

**Evidence.** `audit_event` rows for `payment.submitted` and `payment.approved`
carry the actor. The control-failure query returns no rows:

```sql
select s.subject_id
  from audit_event s
  join audit_event a
    on a.subject_id = s.subject_id and a.actor_id = s.actor_id
 where s.action = 'payment.submitted' and a.action = 'payment.approved';
```

**Tests.** `clofin.authz.approval-test` is table-driven across the full actor ×
instruction matrix — every role set, every limit, every amount — and calls
`evaluate` **directly**, with no HTTP anywhere in the file. That is the point:
if the control only held for callers who came through a handler, it would not
be a control. `clofin.api.approvals-api-test` asserts the boundary reports it as
`403` with a machine-readable `errors.reason`, including for an actor who holds
the approver role *and* made the payment.

---

### C-02 Dual authorisation proportionate to value ✅

**Statement.** The number of approvals required rises with the amount, and no
approver may approve beyond their own limit.

**Design.** A per-organisation, per-currency threshold table maps amount bands
to a required approval count. Approver limits are a per-currency attribute of
the actor. Both are evaluated at approval time, not at submission, so a change
to the amount re-evaluates the requirement — and an amendment invalidates every
approval given so far, so the re-evaluation cannot be skipped by amending after
approval.

Three properties are load-bearing:

- **`from_minor` is inclusive**, so an amount exactly on a boundary falls in the
  higher band — the side that asks for more scrutiny.
- **Absent means zero, never unlimited.** An approver with no limit row for a
  currency has no authority in it.
- **An unconfigured currency denies.** No band covering an amount means the
  payment cannot be approved at all, rather than defaulting to one approval.
  The gap is loud instead of silent.

**Open question — resolved.** PRD Q1 is answered by
[ADR-0015](ADR/0015-approval-thresholds-are-per-currency.md): thresholds and
limits are per currency, never normalised. Normalising would make the control's
strength depend on an exchange rate and the instant it was read, and would make
a past approval unreproducible without also reproducing that rate.

**Enforcement points.**

| | |
|---|---|
| `clofin.authz.approval/evaluate` | Refuses with `:above-actor-limit`, `:already-approved` or `:no-threshold-configured`, and returns `:completes?` — the caller does not decide when enough is enough. |
| `approval_actor_live_key` | Partial unique index on `(instruction_id, actor_id) where invalidated_at is null`. **This**, not the `:already-approved` check, is the guarantee: two concurrent approvals by one actor contend on the index, and a read-then-write in application code would be a race that counts one approval twice. |
| `approval_threshold` / `approver_limit` check constraints | A band requiring zero approvals, or a non-positive limit, cannot be stored. |

**Evidence.** Each `approval` row records the actor, the decision, the reason
and `decided_at`, and whether it still stands. The threshold that applied is
readable from `approval_threshold` for the instruction's organisation and
currency, and the audit event pins the amount by digest.

**Known limit of the evidence.** The approver's limit *at the time* is **not**
retained. `approver_limit` is mutable and not versioned, so a limit raised after
an approval cannot be distinguished from one that was always that high. Stated
rather than glossed over; recorded as objection **O-4** in
[`003-REQ`](audits/003-REQ-authorisation-and-audit-trail.md) §Objections —
accepted by ruling, with the capture columns carried forward as debt for a
future brief — and listed in §4 below.

**Tests.** `clofin.authz.approval-test` asserts the boundary rule at
boundary − 1, boundary and boundary + 1 across a three-band table, and the
limit rule at, below and above the ceiling. `clofin.api.approvals-api-test`
asserts end to end that one approval on a two-approval band leaves the
instruction `pending-approval` and the second moves it to `approved`.

---

### C-03 Immutable financial records ✅

**Statement.** A posted journal entry can never be altered or removed.

**Design.** Corrections are reversing entries referencing the original. Both the
error and the correction remain visible — which is the behaviour finance and
audit actually need, not merely a technical constraint.

**Enforcement point.**
- `journal_entry_append_only` and `journal_line_append_only` triggers reject
  `UPDATE`, `DELETE` **and** `TRUNCATE` at the database ✅ — the third verb
  added by migration `0007` after audit finding F-002 found it uncovered ✅
- `clofin.ledger.entry/reverse-entry` refuses to reuse the original's id ✅
- Integration tests attempt both mutations directly in SQL and assert failure ✅

**Evidence.** `select * from journal_entry where reverses_id = ?` returns the
correction; the original row is unchanged and still present.

---

### C-04 Ledger integrity ✅

**Statement.** Total debits equal total credits, per currency, for every entry.

**Design.** Enforced twice, deliberately: once in the domain constructor and
once in the database. A defect in application code must not be able to commit an
unbalanced entry. See [ADR-0008](ADR/0008-double-entry-journal-as-source-of-truth.md).

**Enforcement point.**
- `clofin.ledger.entry/entry` raises with the per-currency shortfall ✅
- Deferred constraint trigger `journal_entry_must_balance` ✅
- Property test over generated many-to-many postings ✅
- Integration test bypassing the domain layer entirely ✅

**Evidence.** The property test's seed and case count; a direct SQL attempt that
fails with the imbalance named.

---

### C-05 Complete and attributable audit trail ✅

**Statement.** Every state change records who did what, to which subject, when,
and what changed — and cannot be altered afterwards.

**Design.** `audit_event` is append-only and written **in the same transaction**
as the change it describes, so an unaudited state change is not representable.
Before/after digests rather than full snapshots keep counterparty data out of
the audit table while still proving what changed
([ADR-0016](ADR/0016-audit-events-store-digests-not-payloads.md)).

The transactional property is made structural rather than remembered.
`clofin.audit.repository/record!` takes a `tx` and never opens one, so the only
connection available to a caller *is* the transaction carrying the change.
`clofin.payments.approval-service` likewise takes the caller's transaction and
requires no `clofin.db.*` namespace at all — a service that could open its own
connection is a service that could write an audit event outside the change it
describes, and `clofin.ledger.purity-test` fails the build if it acquires one.

**Enforcement points.**

| | |
|---|---|
| `audit_event_append_only` | Rejects `UPDATE` and `DELETE` row by row, reusing `reject_mutation()` from migration `0002`. |
| `audit_event_no_truncate` | Rejects `TRUNCATE`, statement by statement, reusing the same function (migration `0007`). A separate trigger because `TRUNCATE` is a separate event: it visits no rows, so a `for each row` guard never sees it. Until F-002 this was uncovered, and the audit trail could be emptied in one statement past a guard that had just refused an `UPDATE` and a `DELETE` on the same row. |
| `approval_no_delete` | An approval may be *invalidated* (`UPDATE`) and never removed. The asymmetry is deliberate and is commented in the migration, because the next reader will want to "fix" it. |
| `clofin.audit.repository/record!` | Writes on the caller's transaction. Cannot open one. |
| `clofin.audit/event` | Refuses an action outside the vocabulary — default deny reaching the audit trail, so "show me every approval in August" has a complete answer. |

**Evidence.** Evidence pack extraction for a nominated payment or period
(PR-074): `GET /audit/evidence/{subjectId}` returns every state change in order
with its actor, and states the period it spans and whether it hit the row cap.
A pack that is silently empty is `404` instead, because an empty pack reads as
proof that nothing happened.

**Tests.** The pair that matters is `clofin.authz.repository-test`:
`ac-9-a-committed-change-leaves-exactly-one-audit-event` and
`ac-10-a-rolled-back-change-leaves-no-audit-event`, plus a third where the
failure is the *database* refusing rather than a thrown exception.
`clofin.db.audit-constraints-test` attempts `UPDATE`, `DELETE`, a bulk delete
and a no-op update directly in SQL, bypassing the application entirely, and
asserts each is refused.

**What a trigger cannot do, stated because the previous wording claimed
otherwise.** This table used to say the guard was "not revoked privileges — a
trigger, so it holds for the owning role too." That is **false**, and it was
disproved directly. A trigger is enforced by the table, and the table's *owner*
decides what the table is. As the owner, all of these succeed:

| Attempt | Result as the owning role |
|---|---|
| `alter table audit_event disable trigger …` | permitted |
| `drop trigger audit_event_no_truncate on audit_event` | permitted |
| `set session_replication_role = 'replica'` then `delete from audit_event` | permitted — and this defeats the **pre-existing** `UPDATE`/`DELETE` guards too, not only the new one |

CloFin connects as that owner, and in the shipped stack that role is also a
superuser (`POSTGRES_USER` in `docker-compose.yml`). So the append-only
guarantee binds the application and any defect in it — which is what the
control is for — and has never bound an operator with the deployment's own
credentials.

The fix is a **runtime role split**: the application connects as a role that is
neither the owner nor a superuser, with `TRUNCATE` and DDL revoked. Migration
`0002` already foreshadowed it. It is not built, and is named debt in §4 rather
than left implicit. Under a non-owner role every attempt above is refused —
verified, not assumed:

```
ERROR:  permission denied for table audit_event          -- truncate
ERROR:  must be owner of table audit_event               -- disable trigger
ERROR:  must be owner of relation audit_event            -- drop trigger
ERROR:  permission denied to set parameter "session_replication_role"
```

`clofin.db.audit-constraints-test/f-002-the-residue-a-trigger-cannot-close`
demonstrates the residue in a rolled-back transaction, so the boundary is a
passing test rather than a paragraph.

**Scope of this control.** It covers **payment instructions and approvals** —
every state change either can undergo emits exactly one event. It does **not**
yet cover organisation creation, account opening or journal posting. Those are
TASK-001's endpoints and outside this increment's scope; the journal is
separately immutable under C-03 and carries its own `recorded_at`, but a
literal reading of PR-072 includes them and they are not done. Named here rather
than left for a reader to discover, and carried in §4 below.

---

### C-06 Duplicate payment prevention ✅

**Statement.** A retry cannot cause a second payment.

**Design.** Every mutating operation requires an `Idempotency-Key`. The key, the
organisation and a digest of the request are stored with the resulting response,
**in the same transaction as the effect they protect** — a key stored separately
from the effect leaves a window in which a crash makes a payment with no record
that it was made. A replay of the same request returns the stored response and
performs no new work; the same key carrying a *different* request is
`409 Conflict` rather than a silent second payment; a request with no key at all
is `400`.

The digest is taken over a *canonical* serialisation of the request — its
method, its path and its body
([ADR-0013](ADR/0013-canonical-request-digest-for-idempotency.md)). Both halves
of that scope are load-bearing:

- **Canonical**, so a retry that differs only in whitespace or key order is
  honoured rather than refused. A `409` on a genuine retry pushes the caller to
  mint a new key, and a new key is a second payment.
- **Method and path, not the body alone.** Two instructions' submissions carry
  byte-identical bodies and differ only in their path; a body-only digest made
  the second a replay of the first, so its instruction was never submitted while
  the operator saw success. Amended by ruling (ADR-0013 §Amendment 1) after the
  gap was found and disclosed during increment 3.

**Enforcement points.**

| | |
|---|---|
| Primary key `idempotency_key_pkey` on `(organisation_id, key)` | `resources/migrations/0003-payment-instructions.sql`. Two concurrent retries contend on this key: the second blocks until the first commits, fails on it, and returns what the first stored. Replay protection is a database guarantee, **not** a read-then-write in application code — that is a race, and under concurrent retries a race here pays twice. |
| `clofin.idempotency.repository/execute-once!` | Runs the effect inside the transaction that writes the key, and translates the unique violation into a replay. |
| `clofin.idempotency/canonical` and `/digest` | Decide whether a replay is the same request. |
| `SELECT … FOR UPDATE` in `clofin.payments.repository/transition!` | The lifecycle's own defence, for two concurrent state changes carrying *different* keys — where idempotency does not apply and the row lock is what stops both succeeding. |

**Evidence.** The `idempotency_key` row: the digest, the stored response status
and body, and `created_at` as the first-seen timestamp.

**Tests.** `clofin.api.payments-api-test` covers the externally visible
contract, including a genuine concurrency test — two threads, a latch, one key —
asserting that exactly one instruction row exists afterwards and both callers
receive byte-identical responses. It also asserts that one key cannot replay
across two instructions' submissions, or across a submission and a cancellation:
reverting the digest to body-only makes those fail, which is what makes them
regression tests rather than documentation. `clofin.idempotency-test` covers the
canonical form the digest is taken over.

**Scope of this control.** It prevents a *retry* from acting twice. It does not
prevent a caller from deliberately submitting two distinct instructions for the
same underlying invoice — that is a duplicate-payment detection problem, needs
matching on payment attributes rather than on a key, and is not designed here.
Stated because a control described without its boundary is a control nobody can
rely on.

---

### C-07 Sanctions screening before release 📋

**Statement.** No instruction can be released without a completed screening
decision, and a hit blocks release pending disposition.

**Design.** Screening is a precondition of the `submitted → pending_approval`
transition, so it cannot be skipped by ordering. The result records the **list
version**, without which a past decision cannot be reproduced — the question an
investigation actually asks.

**Enforcement point.** State machine precondition; case creation on a hit.
*(Increment 7.)*

**Evidence.** `ScreeningResult` with list version, matched entries and
disposition rationale.

---

### C-08 Least privilege ✅

**Statement.** A user can perform only what their role explicitly permits.

**Design.** Explicit permissions, default deny. An absent permission is a denied
permission; there is no implicit grant and **no superuser role in the model**.

Four properties, each mechanical rather than remembered:

- **`permitted?` answers a question about a set.** There is no fallback branch,
  no wildcard and no `:else true`. An unknown permission is denied like any
  other absent one.
- **No role holds every permission**, and no role holds both `:payment/create`
  and `:payment/approve`. Both are asserted by tests, so a role that quietly
  accumulated the whole set would fail the build rather than the next audit.
- **A suspended actor holds nothing**, whatever roles are recorded against
  them — not "holds their permissions but is flagged", which is a check one
  caller eventually forgets.
- **The permission sets live in code, not in rows.** A permission set stored as
  data is editable by anyone able to write those rows, and least privilege would
  then be a matter of what the data happened to say that day. The *role
  assignments* are data; what a role means is not.

Which role can do what is stated once, in `clofin.authz.model/role-permissions`.
`operator` is the maker and cannot approve; `approver` is the checker and cannot
raise a payment; `controller` opens accounts and posts entries and deliberately
cannot approve; `auditor` holds reads only.

**Enforcement points.**

| | |
|---|---|
| `clofin.api.principal/authorise!` | At the API boundary, on every operation. There is no handler that authenticates without authorising — "authenticated" is not a permission. |
| `clofin.authz.approval/evaluate` | Again in the domain, for the operation that moves a payment forward. This is the one that can see the *instruction*, so it is the one that reports the reason. |
| `actor_role.role_known` | The database refuses a role outside the five. There is no `superuser` to grant. |
| `clofin.authz.model/authorise!` | An unknown *permission* raises rather than denying, so a typo in a handler is a failure instead of unreachable code that looks like a refusal. |

**Evidence.** `actor`, `actor_role` and `approver_limit` state what each actor
holds; `role-permissions` states what each role means. A `403` names the missing
permission and never the held ones — telling a refused caller what it *can* do
turns a refusal into a capability listing.

**Boundary of this control.** Authentication is a seeded actor named by an
`X-Actor-Id` header. It carries no token and no signature, so it does not resist
an adversary and is not presented as doing so — see §4 and the `X-Actor-Id`
description in `api/openapi.yaml`. What it delivers is a real principal with a
real organisation and a real permission set, which is what the *authorisation*
model needs to be enforceable at all.

---

### C-09 Data minimisation ✅ (partial)

**Statement.** CloFin holds no more counterparty or identity data than the
function requires, and sensitive values never reach a log.

**Design.**
- KYC is modelled as state and evidence *references*; identity documents are
  never stored, even synthetically, because storing them would model a bad
  practice.
- Request logging records method, path, status, duration and correlation id —
  never query strings or bodies, which in a payments system routinely carry
  account identifiers and counterparty names.
- Configuration is redacted before it is logged.

**Enforcement point.** `clofin.http.middleware/wrap-request-logging` ✅;
`clofin.config/redacted` with a test asserting the credential does not appear in
the printed form ✅.

**Gap.** Field-level encryption at rest for counterparty details is designed but
not built. Stated rather than glossed over.

---

### C-10 Change control over the schema ✅

**Statement.** The schema of any environment is identifiable, comparable and
tamper-evident.

**Design.** Forward-only migrations with recorded SHA-256 checksums. An edited
migration that has already been applied aborts start-up. No `down` migrations,
because a rollback that has never run against real data is untested code with a
dangerous name. See [ADR-0009](ADR/0009-forward-only-sql-migrations.md).

**Enforcement point.** Migration runner; integration test tampering with a
checksum and asserting start-up fails ✅.

**Evidence.** `schema_migration` — version, description, checksum, applied
timestamp; also surfaced by `GET /readyz`.

---

### C-11 Error handling that does not leak 🔨

**Statement.** An error response reveals nothing about the system's internals.

**Design.** A single error boundary. Domain errors render as RFC 9457 problem
documents with their own message. Any other throwable is a defect: logged in
full internally, returned to the caller as a correlation id and nothing else.
Exception detail is available only in the development profile.

**Enforcement point.** `clofin.http.middleware/wrap-errors` ✅; a test asserting
a credential embedded in an exception message does not appear in a production
response ✅.

**Gap.** No rate limiting or request throttling. Local-development posture only.

---

### C-12 Supply chain control ✅

**Statement.** Every third-party component in the runtime path is known,
justified and has a named upstream with a security process.

**Design.** A deliberately small runtime dependency set; adding one requires an
ADR recording what was gained and what transitive graph came with it. See
[ADR-0004](ADR/0004-minimal-dependency-footprint.md).

**Enforcement point.** Review. *(Not mechanical — stated honestly. A dependency
review job in CI is a candidate improvement.)*

---

## 3. Regulatory themes considered

These themes informed the control design. They are listed to show what was
being reasoned about — **not** to claim conformance with any of them.

| Theme | Where it shows up |
|---|---|
| Payment services operational risk and outsourcing expectations | C-01, C-02, C-05, C-11 |
| AML/CFT: screening, record-keeping, reproducibility of decisions | C-07, C-05 |
| Technology risk management: change control, segregation, audit logging | C-01, C-05, C-10, C-12 |
| Accounting record-keeping expectations for financial records | C-03, C-04 |
| Data protection: minimisation, purpose limitation | C-09 |

## 4. Known gaps

Being explicit about gaps is part of the control design.

| Gap | Status |
|---|---|
| Field-level encryption at rest | Designed, not built. Mitigated for the audit trail specifically by storing digests rather than payloads ([ADR-0016](ADR/0016-audit-events-store-digests-not-payloads.md)) |
| Key management and HSM integration | Out of scope ([ARCHITECTURE.md §10](../ARCHITECTURE.md)) |
| Rate limiting and abuse protection | Not built; local posture only |
| Authentication provider integration | **Permission model built (C-08); provider wiring not.** The actor is named by an `X-Actor-Id` header against a seeded table — anyone who can reach the service can claim to be any seeded actor. The authorisation model is real; the authentication in front of it is not adversarial |
| Audit coverage of ledger and organisation writes | **Payment instructions and approvals emit audit events; account opening, journal posting and organisation creation do not yet.** A literal reading of PR-072 covers them. See C-05 §Scope |
| Approver limit at the time of an approval | Not retained. `approver_limit` is mutable and unversioned, so a limit raised after an approval is indistinguishable from one that was always that high (C-02 §Known limit). Raised as objection O-4 and **accepted by ruling**: the capture columns (`actor_limit_minor`, `approvals_required` on `approval`, written at decision time) are carried forward as debt for a future brief, because a schema change belongs in a brief |
| Actor administration | No endpoint creates an actor, grants a role or sets a limit. Deliberate for this increment — an actor that could grant itself the approver role would make C-01 unenforceable — but a real deployment needs an administered path with its own controls |
| Runtime role split for append-only enforcement | **Not built.** The append-only triggers on `journal_entry`, `journal_line`, `audit_event` and `approval` refuse `UPDATE`, `DELETE` and `TRUNCATE` — but a trigger cannot bind the table's *owner*, and CloFin connects as the owner (and, in the shipped stack, as a superuser). `DISABLE TRIGGER`, `DROP TRIGGER` and `session_replication_role = 'replica'` all succeed for that role; the last defeats the row-level guards that have existed since migration `0002`, so this residue predates audit finding F-002 rather than being introduced by it. The fix — application role ≠ owner, `TRUNCATE` and DDL revoked — is foreshadowed in `0002`'s own comment and verified to refuse all four attempts. Named here rather than left to be discovered |
| Automated dependency vulnerability scanning | Candidate for CI |
| Retention and deletion policy | Not modelled; interacts with C-03 and C-05 immutability and needs a decision |
