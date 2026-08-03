# ADR-0013: Canonical request digest for idempotency keys

- **Status:** Accepted
- **Date:** 2026-08-03 · **Amended:** 2026-08-03 (§Amendment 1)
- **Deciders:** Technical lead; amended by Master Control ruling on objection O-3
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

**The digest is SHA-256 over a canonical serialisation of the request**,
produced by `clofin.idempotency/canonical`. The document digested is

```json
{"method": "POST", "path": "/payment-instructions/{id}/submission", "body": {…}}
```

— see §Amendment 1 for why the method and path are in it. The canonical form
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

**Excluded: request headers.** No header is digested. A header is transport —
a caller's `User-Agent` or `Accept-Encoding` changing between a call and its
retry says nothing about whether the two are the same request, and digesting one
would turn a proxy's rewrite into a `409`.

> **Superseded in part by Amendment 1 below.** As originally accepted, this
> decision also excluded the HTTP method and path, digesting the request *body*
> alone. That boundary had a defect, which is recorded rather than erased —
> see §Amendment 1.

## Amendment 1 — the method and path are inside the digest (2026-08-03)

**As originally accepted, this ADR digested the request body alone**, following
the TASK-002 brief's specification of `request_digest` as "SHA-256 of the
canonical request body". That boundary had a defect, which was found during
implementation, disclosed rather than quietly fixed, and closed by ruling.

**The defect.** Two submissions carry byte-identical bodies:

```
POST /payment-instructions/{a}/submission   {"organisationId": "…"}
POST /payment-instructions/{b}/submission   {"organisationId": "…"}
```

They differ only in their path. With the path outside the digest, one key used
across both digested identically — so the second was treated as a *replay* of
the first. It returned the first instruction's stored `200`, instruction `b` was
never submitted, and the operator saw success. A payment silently not made,
which is a different failure from paying twice and no less serious.

The same held for two different *operations* on one instruction: submission and
cancellation also carry identical bodies.

**Why it was not fixed unilaterally.** The exclusion was an explicit interface
specification in the brief, not an oversight in this ADR. Diverging from a brief
without a ruling is a failed handover even when the divergence is right
([`AGENT_HANDOFF.md`](../AGENT_HANDOFF.md) §1b), so the limitation was recorded
in the `REQ` as objection O-3, disclosed in `COMPLIANCE.md` under C-06, and left
in place pending arbitration.

**The ruling.** Master Control amended the brief's idempotency section and
ordered the fix. The digest now covers `{"method", "path", "body"}`.

**What "path" means.** The path is normalised the way the router normalises it —
empty segments discarded, so `/a/b` and `/a/b/` digest alike. The router already
treats those as one route, and if the digest did not, a client that added a
trailing slash on a retry would be told its payment conflicts. That is the same
class of false conflict the body canonicalisation exists to prevent, one
component along.

The path is the *concrete* request path, including the instruction id — that id
is precisely what distinguishes instruction `a`'s submission from `b`'s, so a
route template would reintroduce the defect.

**Consequence.** Idempotency keys are now scoped by
`(organisation, method, path, body)` rather than `(organisation, body)`. Nothing
that was a replay before has stopped being one: a genuine retry repeats the same
method and path by construction. What changed is that requests which were never
the same request stopped being treated as one.

**Verification.** `clofin.api.payments-api-test` carries three tests for this:
one key across two instructions' submissions is `409`; one key across submission
and cancellation of a single instruction is `409`; and an unchanged retry still
replays, so narrowing what counts as the same request did not break what does.
Reverting `request-digest` to body-only makes them fail, which is what makes
them regression tests rather than documentation.

---

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Digest the request body alone | **Originally accepted; superseded by Amendment 1.** Two different instructions' submissions carry identical bodies, so one key replayed across them silently left the second unsubmitted. |
| Digest the route *template* rather than the concrete path | `/payment-instructions/{id}/submission` is the same string for every instruction, which reintroduces exactly the defect Amendment 1 closes. |
| Digest headers as well | A `User-Agent` or `Accept-Encoding` that changed between a call and its retry says nothing about whether they are the same request. A proxy rewriting one would become a `409`. |
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
  be had again from scratch — including the body-only form this ADR itself
  started with, which is listed as rejected rather than deleted.
- *A new endpoint is added whose requests differ only outside the digested
  document.* Amendment 1 fixed the two such cases that existed; the general
  shape of the mistake is that two requests a caller means differently must
  never digest alike. Any new mutating endpoint should be checked against that
  sentence before it ships.

## Verification

- `clofin.idempotency-test` covers the canonical form directly: key ordering,
  whitespace insensitivity, array order preservation, nesting, numeric forms,
  escaping, and the absent body. It includes a property test asserting that two
  independently-shuffled encodings of the same document produce one digest, and
  that changing any single leaf changes it.
- `clofin.api.payments-api-test` asserts the externally visible contract: same
  key and same request replays the stored response and writes no second row;
  same key and a different body is `409`; no key at all is `400`. Since
  Amendment 1 it also asserts that one key across two instructions' submissions
  is `409`, that one key across a submission and a cancellation is `409`, and
  that an unchanged retry still replays.
- `clofin.ledger.purity-test` lists `clofin.idempotency` among the pure
  namespaces, so the canonicaliser cannot acquire a database or transport
  dependency without failing a test.
