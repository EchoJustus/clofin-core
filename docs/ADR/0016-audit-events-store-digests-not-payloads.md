# ADR-0016: The audit trail stores digests, not payloads

- **Status:** Accepted
- **Date:** 2026-08-03
- **Deciders:** Technical lead
- **Supersedes / Superseded by:** —

## Context

[C-05](../COMPLIANCE.md) requires that every state change record who did what,
to which subject, when, **and what changed** — and that it cannot be altered
afterwards. The obvious implementation of "what changed" is a before-and-after
snapshot of the record.

Two other commitments collide with that.

**[C-09](../COMPLIANCE.md) data minimisation.** A payment instruction carries a
counterparty name and an external account identifier. An audit table holding
before-and-after snapshots of instructions is a second copy of exactly the data
CloFin has undertaken to hold as little of as possible — and it holds one copy
per state change, so a payment that is created, submitted, amended, amended
again and approved leaves five.

**[C-03](../COMPLIANCE.md)/append-only.** The audit table cannot be corrected,
redacted or subjected to a retention policy, because those are `UPDATE` and
`DELETE` and the table refuses both. Every one of those five copies is
permanent. A data-minimisation mistake in a mutable table is a cleanup task; the
same mistake here is unfixable by design.

There is a third force, less obvious and more decisive: **what does an auditor
actually do with the payload?** The question an evidence request turns on is
almost never "what was the counterparty name in the audit table?" — the
instruction itself answers that. It is "has this record been altered since it
was approved?", and "is the value I am looking at now the value that was
approved?". Those are comparison questions.

## Decision

**1. `audit_event` stores `before_digest` and `after_digest`, never the values.**
A digest is SHA-256 over the canonical serialisation of a fixed projection of
the subject.

**2. The projection is explicit, not "the whole map."** `clofin.audit/instruction-fields`
names the fields a digest covers. A value read back from a row and one built in
memory differ in incidental keys, and a digest that changed depending on which
one it was handed would prove nothing — it would report tampering every time a
caller happened to hold a slightly different map.

**3. A null `before_digest` means the subject did not exist.** Nil digests to
nil rather than to a fixed hash of nothing, because the absence is meaningful:
it is what distinguishes a creation from an update in the trail.

**4. Every digest carries the canonicalisation version that produced it**, as a
`v1:` prefix. Without it, a later amendment to the canonical form would produce
digests that *look* comparable to older ones and are not — and an auditor
comparing across the change would conclude a record had been altered when only
the algorithm had. The prefix makes that case visible rather than silent, which
is the entire job of an audit column.

**5. Canonicalisation is `clofin.idempotency/canonical`**, the RFC 8785-shaped
serialisation ADR-0013 already specifies and property-tests. Two mechanisms
share it deliberately: one canonical form in the codebase is one thing to
review. `clofin.audit/normalise` renders domain types — UUIDs, instants, dates,
keywords, sets — into what that function accepts, because it throws on an
unknown type rather than falling back to `str`, and a silent `str` fallback is
how two different values come to share a digest.

## What this costs an auditor

Stated plainly, because a trade-off recorded without its cost is a sales pitch.

**A digest cannot be read.** An investigation that wants to know the amount an
instruction carried at 14:02 cannot get it from the audit trail. It reads the
instruction, and uses the digest to establish that the row has not moved since —
or that it has. The audit trail proves *integrity and attribution*; the record
proves *content*.

**A destroyed record cannot be reconstructed from its digests.** If a
`payment_instruction` row were deleted, the audit trail would prove that a
payment existed, who acted on it and when, and would not recover the amount.
This is a genuine limitation. The mitigation is that payment instructions are
not deleted; but the limitation is real and is not hidden behind the word
"immutable".

**Verifying a digest requires the verifier to reproduce the projection.** An
auditor checking `after_digest` against a value must digest the same fields, in
the same canonical form, under the same version. That is why the fields are a
named vector in one namespace and the version is stored on every row, and why
`clofin.authz.repository-test` asserts that a digest written through the
repository equals one computed purely from the same value — the auditor's
procedure, exercised.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Full before/after JSON snapshots | Duplicates counterparty data into a table that is append-only and therefore cannot be cleaned, once per state change (C-09). The convenience is real and the permanence is the problem. |
| Snapshots with sensitive fields redacted | Redaction lists rot. The field added next increment is not on the list, and nobody notices until it is in an unfixable table. The failure is silent and one-directional. |
| Encrypted payloads in the audit table | Field-level encryption at rest is an open gap in COMPLIANCE §4 and needs key management CloFin has deliberately scoped out (ARCHITECTURE §10). It would also make the trail unreadable to exactly the auditor it exists for, unless they hold the key — at which point the minimisation argument returns. |
| A digest with no version prefix | Cheaper today, and it makes a future change to the canonical form indistinguishable from tampering. The prefix costs three bytes. |
| Digest the whole subject map rather than a named projection | A value from a row and a value from memory differ in incidental keys, so the digest would depend on which one the caller happened to hold — producing false tampering reports, which are worse than none because they train the reader to ignore them. |
| A separate canonicaliser for audit, independent of idempotency's | Two canonical forms to review and keep aligned, for no benefit. The coupling is instead made explicit by the version prefix: if ADR-0013's form changes, `canonicalisation-version` is bumped in the same commit. |

## Consequences

**Positive**

- The audit table holds no counterparty data, so its permanence is an asset
  rather than a liability.
- Integrity is provable: any change to a covered field changes the digest.
- A change to the canonical form is detectable rather than silently corrupting
  every comparison across it.

**Negative / accepted cost**

- The trail cannot answer content questions on its own. Named above.
- A field outside `instruction-fields` can change without changing any digest.
  The projection is chosen to cover everything an amendment can touch plus
  identity, provenance and status; a field added later must be added there too,
  and `clofin.audit-test` asserts that every field currently in it changes the
  digest, so an addition that forgets is at least a visible omission rather than
  a silent one.

**Risks and how they are mitigated**

- *`clofin.idempotency/canonical` changes and the version is not bumped.*
  Mitigated by `canonicalisation-version` sitting in `clofin.audit` with the
  instruction to bump it in the docstring, and by ADR-0013 being the only
  document that governs the canonical form.
- *A later increment adds a payload column "just for debugging".* Mitigated by
  this ADR being named in migration `0005`'s column comment, which is where a
  reader adding a column will be looking.

## Verification

- `clofin.audit-test` asserts a digest is version-tagged; that nil digests to
  nil; that map construction order does not change a digest; that every field
  in the projection changes it; that a currency change alone changes it; and
  that fields outside the projection do not. A property test asserts two
  different instructions never share a digest.
- The same suite asserts that an event built from an instruction carries
  neither the creditor name nor the creditor account anywhere in it.
- `clofin.authz.repository-test` asserts that a digest written through the
  repository equals one computed purely from the same value.
- `clofin.api.approvals-api-test` dumps the whole `audit_event` table after a
  full approval flow and asserts the counterparty name does not appear in it.
