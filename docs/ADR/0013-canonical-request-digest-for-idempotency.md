# ADR-0013: Canonical request digest for idempotency keys

- **Status:** Accepted
- **Date:** 2026-08-03
- **Deciders:** Technical lead
- **Supersedes / Superseded by:** —

## Context

[C-06](../COMPLIANCE.md) requires that a retry cannot cause a second payment.
The mechanism is a caller-supplied `Idempotency-Key`, stored with the response
it produced, so that a replay returns the stored response instead of executing
again.

That mechanism needs one more thing to be safe: a way to tell a genuine retry
from a *different* request that happens to reuse a key. The stored record
therefore carries a digest of the request, and the two cases diverge sharply:

| Situation | Behaviour |
|---|---|
| Key seen, digest identical | Return the stored response. Perform no new work. |
| Key seen, digest different | `409 Conflict`. Do not execute. |

The consequence is that **what the digest is computed over decides whether a
correct retry is honoured or rejected**. Two facts make this awkward:

1. **JSON has no canonical form.** `{"a":1,"b":2}` and `{ "b": 2, "a": 1 }` are
   the same document. A digest over raw bytes makes those two a `409` — so a
   caller whose HTTP client reorders a map, or pretty-prints on retry, is told
   its payment conflicts when it is byte-for-byte the request it meant to send.
   The caller's only recourse is to mint a new key, which is precisely the
   behaviour C-06 exists to prevent.
2. **A digest that is too forgiving is worse.** If the digest ignored fields, a
   caller who changed the amount and reused the key would silently receive the
   *old* response, and believe an amount it never sent had been accepted.

So the digest must be stable under representational differences and sensitive
to every semantic one. Getting that boundary right is a decision, not an
implementation detail — and a future contributor who re-derives it will pick a
different line unless it is written down.

A related question: JSON Canonicalization Scheme ([RFC 8785]) already specifies
one such form. It is more than CloFin needs — its hardest requirement is
ECMAScript number serialisation for the full IEEE-754 double range, and CloFin
never puts a float on the wire, because money is never a float
([ADR-0003](0003-money-as-integer-minor-units.md)).

[RFC 8785]: https://www.rfc-editor.org/rfc/rfc8785

## Decision

**The digest is SHA-256 over a canonical serialisation of the decoded JSON
request body**, produced by `clofin.idempotency/canonical`. The canonical form
follows RFC 8785 for the value types CloFin's API accepts, and states its own
rules rather than deferring to a library:

| Element | Rule | Why |
|---|---|---|
| Object keys | Sorted by UTF-16 code unit, ascending | An object is unordered; key order carries no meaning. |
| Object | `{"k":v,...}` — no whitespace | Whitespace is insignificant in JSON. |
| Array | Order **preserved** | An array *is* ordered. Sorting one would make two different requests digest alike. |
| String | Minimal JSON escaping, `\uXXXX` for control characters | One representation per string value. |
| Integer | Decimal, no exponent, no leading `+` | `125000` is the same amount however it was written. |
| Non-integer number | `BigDecimal`, trailing zeros stripped, plain notation | `1.50` and `1.5` are the same number. Reaching this rule at all means a caller sent a fractional value, which no CloFin field accepts. |
| `true` / `false` / `null` | Literal | |
| Absent body | The empty object, `{}` | So a bodiless mutation still has a digest. |

**Two boundaries are drawn deliberately.**

**Included: every member of the body, at every depth.** There is no allow-list
of "significant" fields. A field CloFin does not recognise still changes the
digest, because the alternative is that an unrecognised field silently makes
two different requests look identical — and the field CloFin does not recognise
today is the one a later increment adds meaning to.

**Excluded: everything outside the body.** The digest covers the request body
and nothing else — not the HTTP method, not the path, not any header. This
follows the TASK-002 brief's specification of `request_digest` as "SHA-256 of
the canonical request body", and it is the boundary this increment implements.

*A limitation of that boundary is recorded in
[`../audits/002-REQ-payment-instruction-lifecycle.md`](../audits/002-REQ-payment-instruction-lifecycle.md)*
*and is not resolved here:* because the path is outside the digest, one key
replayed across two different instructions' sub-resources — `POST
/payment-instructions/{a}/submission` and `.../{b}/submission` — has an
identical digest when the two bodies agree, so the second caller receives the
first's stored response and instruction `b` is never submitted. Folding the
method and path into the canonical document is a one-line change to
`clofin.api.payments/request-digest`; it is held pending a ruling rather than
taken unilaterally, because diverging from a brief without one is a failed
handover even when the divergence is right
([`AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §1b).

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Digest the raw request bytes | Simplest, and wrong in the direction that hurts. A retry that differs only in whitespace or key order is the *normal* output of an HTTP client library, and it would be answered `409` — pushing the caller to mint a new key, which is a second payment. |
| Digest a subset of "significant" fields | Requires a decision, per endpoint, about which fields matter; every such list goes stale. A field omitted from the list makes two semantically different requests indistinguishable, and the failure is silent. |
| Depend on a JCS library | A runtime dependency needs an ADR of its own ([ADR-0004](0004-minimal-dependency-footprint.md)), for roughly sixty lines of code whose hardest case — arbitrary doubles — CloFin's wire contract does not admit. |
| Store the request body itself and compare | Storing every request body indefinitely is retention CloFin has not designed, on documents carrying counterparty names and account identifiers. A digest answers the only question asked of it. |
| Sort array elements as well as object keys | Would make `[debit, credit]` and `[credit, debit]` digest alike. Those are different instructions. |
| Use MD5 or SHA-1 for speed | A collision is a request substituted for another under a key that authorises payment. The cost difference is irrelevant at these sizes. |

## Consequences

**Positive**

- A retry that is semantically identical is honoured, whatever the caller's
  HTTP client did to its whitespace or key order. That is what makes the key
  usable, and an unusable key is not a control.
- A retry that differs in any way is a `409`, not a second payment and not a
  misleading `200`.
- The canonicalisation is a pure function of one argument, so it is tested
  directly — including the property that re-canonicalising a canonical document
  is a no-op, and that two encodings of one document agree.

**Negative / accepted cost**

- CloFin carries its own canonicaliser. It is exercised by every idempotent
  request, so a defect surfaces immediately rather than silently.
- The form is a *subset* of RFC 8785, not an implementation of it. A body
  carrying a number outside the exactly-representable integer range would be
  canonicalised by CloFin's `BigDecimal` rule rather than RFC 8785's
  ECMAScript rule. No CloFin field accepts such a value; the rule exists so the
  function is total, not because the case is reachable.
- Digesting a decoded document means a body that is not valid JSON has no
  digest. Such a request is rejected as `400` before idempotency is consulted,
  so it never reaches the store — and consumes no key.

**Risks and how they are mitigated**

- *The digest is computed but never checked.* Mitigated by an end-to-end test
  that replays a key with a changed body and asserts `409`, and by C-06 naming
  its enforcement point.
- *The canonical form drifts when a new value type appears on the wire.*
  `canonical` throws on a value it does not have a rule for, rather than
  falling back to `str`. A silent fallback would let two different documents
  digest alike, which is the one failure this ADR exists to prevent.
- *Someone "optimises" the digest to cover fewer fields.* The rejected options
  above are recorded with their failure modes so the argument does not have to
  be had again from scratch.

## Verification

- `clofin.idempotency-test` covers the canonical form directly: key ordering,
  whitespace insensitivity, array order preservation, nesting, numeric forms,
  escaping, and the absent body. It includes a property test asserting that two
  independently-shuffled encodings of the same document produce one digest, and
  that changing any single leaf changes it.
- `clofin.api.payments-api-test` asserts the externally visible contract: same
  key and same body replays the stored response and writes no second row; same
  key and a different body is `409`; no key at all is `400`.
- `clofin.ledger.purity-test` lists `clofin.idempotency` among the pure
  namespaces, so the canonicaliser cannot acquire a database or transport
  dependency without failing a test.
