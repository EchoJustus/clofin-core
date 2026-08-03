# CloFin — Control Design and Mapping

**Status:** living document · **Last reviewed:** 2026-08-02

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

### C-01 Segregation of duties 📋

**Statement.** The actor who creates or submits a payment instruction must not
be the actor who approves it.

**Design.** Maker–checker is a domain rule in `clofin.authz`, evaluated when an
approval is recorded — not a UI restriction and not a database convention. The
rule sees actor identity and instruction provenance and refuses the approval.

**Enforcement point.** Domain function; exhaustive unit tests over the actor
matrix. *(Increment 4.)*

**Evidence.** `audit_event` rows for `payment.submitted` and `payment.approved`
carry the actor; a query showing the same actor on both would be a control
failure and returns no rows.

---

### C-02 Dual authorisation proportionate to value 📋

**Statement.** The number of approvals required rises with the amount, and no
approver may approve beyond their own limit.

**Design.** A per-organisation threshold table maps amount bands to a required
approval count. Approver limits are a permission attribute. Both are evaluated
at approval time, not at submission, so a change to the amount re-evaluates the
requirement.

**Enforcement point.** Domain rule; table-driven tests over band boundaries.
*(Increment 4.)*

**Evidence.** Each `Approval` row records the actor, their limit at the time,
and the threshold applied.

**Open question.** Whether thresholds are per-currency or normalised to a base
currency (PRD Q1). Recorded rather than assumed.

---

### C-03 Immutable financial records ✅

**Statement.** A posted journal entry can never be altered or removed.

**Design.** Corrections are reversing entries referencing the original. Both the
error and the correction remain visible — which is the behaviour finance and
audit actually need, not merely a technical constraint.

**Enforcement point.**
- `journal_entry_append_only` and `journal_line_append_only` triggers reject
  `UPDATE` and `DELETE` at the database ✅
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

### C-05 Complete and attributable audit trail 📋

**Statement.** Every state change records who did what, to which subject, when,
and what changed — and cannot be altered afterwards.

**Design.** `audit_event` is append-only and written **in the same transaction**
as the change it describes, so an unaudited state change is not representable.
Before/after digests rather than full snapshots keep counterparty data out of
the audit table while still proving what changed.

**Enforcement point.** Append-only trigger; transactional write; a test that
asserts a rolled-back change leaves no audit event, and vice versa.
*(Increment 4.)*

**Evidence.** Evidence pack extraction for a nominated payment or period
(PR-074).

---

### C-06 Duplicate payment prevention ✅

**Statement.** A retry cannot cause a second payment.

**Design.** Every mutating operation requires an `Idempotency-Key`. The key, the
organisation and a digest of the request body are stored with the resulting
response, **in the same transaction as the effect they protect** — a key stored
separately from the effect leaves a window in which a crash makes a payment with
no record that it was made. A replay with the same body returns the stored
response and performs no new work; a replay with a *different* body is
`409 Conflict` rather than a silent second payment; a request with no key at all
is `400`.

The digest is taken over a *canonical* serialisation of the body, so a retry
that differs only in whitespace or key order is honoured rather than refused
([ADR-0013](ADR/0013-canonical-request-digest-for-idempotency.md)). This matters
as much as the storage does: a `409` on a genuine retry pushes the caller to
mint a new key, and a new key is a second payment.

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
receive byte-identical responses. `clofin.idempotency-test` covers the canonical
form the digest is taken over.

**Not covered by this control.** The digest is computed over the request body
alone, not the method or path, so one key reused across two *different*
instructions' sub-resources with identical bodies replays rather than acting.
Recorded in
[ADR-0013](ADR/0013-canonical-request-digest-for-idempotency.md) and in
[`audits/002-REQ-payment-instruction-lifecycle.md`](audits/002-REQ-payment-instruction-lifecycle.md),
and awaiting a ruling. Stated here rather than left out, because a control
described without its boundary is a control nobody can rely on.

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

### C-08 Least privilege 📋

**Statement.** A user can perform only what their role explicitly permits.

**Design.** Explicit permissions, default deny. An absent permission is a denied
permission; there is no implicit grant and no superuser role in the model.

**Enforcement point.** Authorisation check at the API boundary and again in
domain operations that move money. *(Increment 4.)*

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
| Field-level encryption at rest | Designed, not built |
| Key management and HSM integration | Out of scope ([ARCHITECTURE.md §10](../ARCHITECTURE.md)) |
| Rate limiting and abuse protection | Not built; local posture only |
| Authentication provider integration | Permission model built first; provider wiring later |
| Automated dependency vulnerability scanning | Candidate for CI |
| Retention and deletion policy | Not modelled; interacts with C-03 immutability and needs a decision |
