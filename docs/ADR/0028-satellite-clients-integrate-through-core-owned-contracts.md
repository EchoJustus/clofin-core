# ADR-0028: Satellite clients integrate through core-owned JSON contracts; screening, idempotent creation and simulated chain confirmations are core transitions

- **Status:** Proposed — eight rulings requested of the operator (D3–D10 below); becomes *Accepted* when they are made, and the briefs it names are written after that, not before
- **Date:** 2026-10-03
- **Deciders:** Master Control (proposal); the operator (rulings); the Principal Architect seat at the next release audit (`ref-3`, Sol tier — this ADR and every increment it produces change enforcement code in the authorisation, settlement and financial-crime domains)
- **Supersedes / Superseded by:** — (extends ADR-0026's role boundary to further client repositories; amends nothing)

## Context

Four satellite modules exist outside this repository — `clofin-agent`,
`clofin-fc`, `clopay-gateway` and `cloagent-kit` — three proprietary, one
EPL-2.0. None has modified `clofin-core`, `clofin-trace` or `clofin-cockpit`;
each marks its core-dependent seams `TODO: clofin-core integration`. Their
owner has handed over a set of **requested** contracts: EDN over HTTP with
`application/edn`; payment states named `:payment/awaiting-screening` and
`:payment/ready-for-processing`; money as `BigDecimal` with the `M` suffix; a
party model (originator and beneficiary, each with id, name and country); a
core-owned RPC `apply-screening-result!` that would inject a screening result
and propose a transition; an authenticated lookup by idempotency key; an ingress
for ERC-20 transfer confirmations carrying a canonical-digest scheme (SHA-256
over a specified binary encoding, with golden vectors); and a table of
acceptance scenarios that only core can satisfy. The handover says, correctly,
that these are requests to be reconciled against the authoritative model, not
claims about what the core exposes.

What the core is, as of `ref-2` (`32dfcc9`):

- **JSON over HTTP, one representation.** `api/openapi.yaml` is the interface
  specification; `clofin.contract-test` compares it with the route table and
  the live handlers; the cockpit, the capture harness and `clofin-trace`'s
  fixtures all speak and record JSON. Errors are RFC 9457 problem documents.
- **Money is integer minor units with a per-currency scale** (ADR-0003):
  `{"currency":"SGD","minorUnits":125000}`. There is no decimal on the wire and
  no rounding anywhere.
- **The lifecycle is data** (ADR-0014): nine states and nine events in one
  table — `draft → submit → pending-approval → approve → approved → release →
  released → settle | return | fail`, with `amend` back to `draft`, `reject`
  and `cancel` to their terminals. `settled` and `returned` are terminal; a
  retry is a new instruction that names the one it replaces (ADR-0019,
  ADR-0024). There is no state between creation and submission other than
  `draft`.
- **Idempotency** (ADR-0013, C-06): six payment and approval operations
  require `Idempotency-Key`; the key is bound per organisation to a canonical
  digest of method, path and body and to the original response; an exact
  replay returns that response with `Idempotent-Replayed: true`; a different
  body under the same key is `409`. Keys are retained; no purge exists
  (retention is named debt, COMPLIANCE §4).
- **C-07, screening, is designed and not built.** Its statement: *no
  instruction can be released without a completed screening decision, and a
  hit blocks release pending disposition*; its enforcement point is a
  precondition of `submit`, at the line in `clofin.payments.repository` that
  carries `TODO(increment-7)`. Screening is core's own control with its own
  versioned synthetic list; it was never designed as something a client
  performs on core's behalf.
- **Settlement outcomes enter through one door**:
  `POST /settlement-batches/{id}/scheme-responses`, documented as *the
  simulation injection point* — there is no listener and no file drop. The
  known schemes are `SIM-RTGS` and `SIM-ACH`, enforced by a check constraint.
  Replay identity is `(batch, instruction, kind, reference)`; a refused arrival
  keeps its receipt (L-11); a same-identity different-content arrival is a
  conflict, never a replay (L-12, and 2C-002's remediation).
- **Authentication is scaffolding.** `X-Actor-Id` names a seeded actor; there
  is no identity provider, token or signature, and every document says so.
  Authorisation behind it is real (C-08).
- **The audit trail is written in the writing transaction** (C-05, L-13) by
  every service that composes a change.
- **The constraint that governs everything:** CloFin operates on synthetic
  data only, is connected to no bank, payment scheme or central bank, holds no
  regulatory authorisation and never processes real funds — the sentence
  `GET /` serves and every surface restates. ADR-0026 adds that a client
  repository owns no truth and may never claim.

The forces, then. A second wire representation is a second copy of every
example and every error shape, which is the class this project's audits exist
to find (L-6, L-14, L-16). New state names that mean what existing states
already mean are the same class in the lifecycle table. A client-performed
screening injected into core would invert C-07. `eip155:1` is Ethereum
mainnet: a ledger entry posted on the strength of a real on-chain transfer
would make the disclaimer false in the one place it must never be. And a
transport the core calls "authenticated" would overstate what COMPLIANCE §4
says is scaffolding.

## Decision

Core integrates the satellites **as clients of the real API, in core's own
language, through transitions core already defines or owns**. Each requested
contract is reconciled below; where reconciliation is a choice rather than a
fact, it is numbered as a ruling the operator makes, with Master Control's
recommendation stated. The satellites' EDN shapes are satisfied by the
satellites' own transport adapters (`CoreTransport`, `CorePosting`, the fc
interceptor's future adapter), which translate to and from the JSON contracts
below using the mapping tables in this ADR. Core publishes the contracts, the
mapping tables, and the tests that prove the atomic behaviour the handover's
§6 asks for; it does not publish, reference or depend on the satellites'
internals, three of which are proprietary.

### D3 — Wire format: JSON at core, EDN at the satellites (recommended: JSON only)

Core keeps one representation. The satellite transports serialise their EDN
to the JSON shapes `api/openapi.yaml` declares and decode core's JSON
responses into their EDN envelopes. Keyword-valued fields on the satellite
side (`:payment/awaiting-screening`, `:erc20/transfer`, `:finalized`) are
string enums at core and the mapping is in the tables below. The satellites'
`X-Clofin-Contract-Version` header is accepted and ignored: core's contract is
versioned by the OpenAPI document and the release tag, and a header that
disagrees with a URL would be a second copy.

*The alternative the operator may rule instead:* an `application/edn` codec in
core's ingress middleware, decoding with `clojure.edn/read-string` under
`{:readers {} :default (fn [tag _] (refuse tag))}` into the same string-keyed
map the JSON codec produces, with responses encoded per `Accept`. One handler,
two codecs; the idempotency digest is computed over the decoded map today, so
it is representation-independent already. Master Control recommends against
it for `ref-3`: the contract test, the capture harness, the cockpit's recorded
responses and the trace fixtures all speak JSON, and every example the
contract carries would need an EDN twin maintained by hand.

### D4 — State names: no new states; a mapping table

| Satellite name | Core state or event | Note |
|---|---|---|
| `:payment/awaiting-screening` (creation acknowledgment) | `draft` | created, not yet submitted; screening has not run |
| `:payment/release-for-processing` (the proposed transition) | the `submit` event, `draft → pending-approval`, gated by C-07 | performed by the maker, not by the screening client — see D5 |
| `:payment/ready-for-processing` | `pending-approval` | cleared screening; awaits maker–checker approval, then release into a batch |
| `:expected-payment-state :payment/ready-for-processing` on a chain confirmation | **`released`** | a confirmation applies to an instruction in a submitted batch; the satellite flow omits approval and release, which core does not skip |
| creation ack `202` / replay `200` with `:replayed?` | `201` on both; `Idempotent-Replayed: true` on the replay | the adapter derives `:replayed?` from the header |

Core answers with core's names. An adapter that needs the satellite's names
maps them; core never emits a state it does not have.

### D5 — Screening (C-07, increment 7): core screens; a client's result is evidence, never authorisation

Core performs screening itself at `submit`, against a **versioned synthetic
list it holds**, with deterministic rules — the C-07 design. The fc
satellite's `apply-screening-result!` is reconciled as:

```
POST /payment-instructions/{id}/screening-results          (Idempotency-Key required)
```

which **records an externally produced screening result as evidence** bound
to the instruction and to a list version core holds, and which **does not
transition** anything. Core then performs its own screening against the same
list version and compares; `submit` consults core's decision, not the
client's. The seven preconditions the satellite enumerates map onto core
checks that already exist or that increment 7 builds:

| Satellite precondition | Core check | Refusal |
|---|---|---|
| `:instruction-exists` | the id resolves within the caller's organisation | `404` |
| `:instruction-content-matches` | the submitted `instructionDigest` equals core's canonical digest of the stored instruction | `422 instruction-digest-mismatch` |
| `:expected-prior-state-matches` | status is `draft` at the time of the write, read `FOR UPDATE` (L-8) | `409` with the lifecycle's own refusal |
| `:screening-result-is-clear` | `outcome` is `clear` and `matchedEntries` is exactly `[]` | `422` |
| `:risk-list-version-accepted` | `listVersion` names a list core holds and has not retired | `422 list-version-not-accepted` |
| `:screening-result-verified` | core recomputes the result against that list and it equals the submitted one, entries included | `422 screening-result-mismatch` |
| `:core-authorizes-transition` | the caller holds `screening/record`; C-07's own decision at `submit` is core's, and a hit found by core blocks `submit` regardless of what was recorded | `403` / `409 screening-hit` |

A hit — recorded or found — leaves the instruction in `draft`, opens a case
(increment 7's case management), and `submit` answers `409 screening-hit`
until the case is dispositioned. The response to a successful record is `201`
with a `ScreeningResult` resource carrying `listVersion`, `outcome`,
`matchedEntries` (whole entries, in list order), `coreOutcome` (core's own
recomputation), `instructionDigest` and `recordedBy`. The satellite's three
response envelopes (`:applied`, `:rejected`, `:error`) are the adapter's
rendering of `201`, `4xx` problem documents and transport failure; the
"transition id" the satellite asks for is the audit event id core returns in
`Location`-adjacent fields on every write. A clear result against an empty
list is accepted only if core holds that empty list under that version, which
core will not: list versions are loaded by seed, not by clients.

### D6 — Payment creation and lookup: the existing endpoint, with three small additions

`POST /payment-instructions` is the endpoint; there is no `/v1/payments`. The
satellite's `PaymentInstruction` maps as follows, and the adapter owns the
mapping:

| Satellite field | Core field | Rule |
|---|---|---|
| `:instruction-id` (client-chosen) | **`clientReference`** — new, optional, string ≤ 128, unique per organisation | a second instruction with the same reference and different content is `409 client-reference-conflict`; the same reference under the same idempotency key is the replay. Core still issues the instruction's `id`. |
| `:amount 123.4500M` + `:currency` | `amount: {currency, minorUnits}` | converted at the currency's registry scale; a decimal with more scale than the currency carries is **refused by the adapter before sending**, and by core (`422`) if sent as minor units that do not match — core never rounds |
| `:originator {:party-id …}` | `debtorAccountId` | core's debtor is a ledger account the organisation owns; the adapter holds the party → account mapping as configuration. `:name` and `:country` of the originator are the organisation's and are not per-instruction fields |
| `:beneficiary {:party-id :name :country}` | `creditorAccount`, `creditorName`, **`creditorCountry`** — new, optional, ISO 3166-1 alpha-2 | `creditorCountry` exists so a screening rule can name it (D5); it is the only new instruction field |
| — | `valueDate`, `purposeCode` | required by core and absent from the satellite shape: **the kit's `create-payment` action gains both as explicit arguments**; the adapter does not default them |
| `Idempotency-Key` UUID v4 | `Idempotency-Key` | identical semantics (ADR-0013) |

Core's answers, as today: `201` + `PaymentInstruction` (`status: draft`);
replay `201` + the same body + `Idempotent-Replayed: true`; `400` validation,
`401`/`403` principal, `409` key bound to a different digest, `422` refusals.
The satellite's status-code table is the adapter's; `429`, `500`, `503` and
`504` are transport-level and never carry a core problem document.

**The lookup**, new:

```
GET /payment-instructions/by-idempotency-key/{key}       (permission payment/read)
```

answers `200` with the stored original response (status and body, as replay
would return it) plus the instruction's current `status`, scoped to the
caller's organisation; `404` when no key is bound there. It reads the same
table the write path binds in its transaction, so a `404` is strongly
consistent: there is no cache and no eventual anything. It resolves a timed-out
`POST` the way the handover asks; retention is the existing named debt.

### D7 — Chain confirmations (increment 9, simulation only): a third simulated scheme behind the one settlement door

A chain confirmation is a scheme response. Core gains a simulated scheme
**`SIM-EVM`** beside `SIM-RTGS` and `SIM-ACH` (the check constraint and
`clofin.settlement.batch/known-schemes` widen together; the partial-set sweep
covers both), a synthetic token registry (`tokenContract → currency, decimals`)
loaded by seed, and one new operation:

```
POST /settlement-batches/{id}/chain-confirmations        (Idempotency-Key required)
```

that validates the event exactly as the gateway's schema states it (closed
maps; lowercase hex; `eip155:` chain ids as canonical decimals; `uint64` and
`uint256` bounds; `amountBaseUnits` as a canonical decimal string), **recomputes
canonical digest v1** from the event and refuses a client digest that differs
(`422 digest-mismatch`), resolves the instruction by `instructionId` within
the batch, converts base units to minor units through the token registry
without rounding, and then **delegates to the same posting path
`recordSchemeResponse` uses** — kind `settled` for a finalized transfer, kind
`returned` with reason `reorged` for a reversal — so there is one producer of
settlement postings (L-21), one replay identity, one receipt rule (L-11) and
one conflict rule (L-12). The reference is the event identity rendered as
`eip155:<chain>/<txhash>/<logIndex>`.

Mapping of the gateway's result vocabulary:

| Gateway `:status` | Core answer |
|---|---|
| `:posted` | `200` + the scheme-response resource with `journalEntryId` (the posting id), `eventIdentity`, `canonicalDigest`, `instructionId` echoed |
| `:duplicate` | the stored response of the first arrival, `replayed: true` |
| `:conflict :identity-digest-conflict` | `409`, with `existingCanonicalDigest` in the problem document; nothing posted |
| `:pending :awaiting-*` | **not a core state**: an event whose `finality.status` is not `finalized` is refused `422 not-finalized` and retried later by the gateway; core holds no pending confirmations |
| `:rejected` reasons | `404 payment-not-found`; `409` lifecycle (`payment-state-not-ready`, i.e. not `released`); `422 payment-binding-mismatch`, `unsupported-chain`, `unsupported-token`, `transfer-mismatch`; `403 authorization-denied`; `invalid-chain-proof` — see finality below |
| `:unavailable` | transport-level `503` from the server, never a problem document core composed |

**Finality policy.** Core verifies nothing on any chain: it has no RPC, no
connectivity, and the constraint forbids both. A confirmation is trusted
exactly as a `SIM-RTGS` response is trusted today — it is what the caller
sent, and the caller is a seeded actor holding `settlement/confirm`. The
gateway's verifier output (`sourceId`, `attestationId`) is **recorded** on the
scheme response as provenance and rendered in the evidence pack; it is not
re-verified by core, and the documents say so. **Supported chain ids are
synthetic or local only** — `eip155:31337` and a `sim:` prefix are the first
two; `eip155:1` and every other public network id answer `422
unsupported-chain` by policy, and a test pins the refusal, so that no ledger
entry can ever be posted on the strength of a real-network transfer. The
handover's request that core "independently verify chain ID, receipt, topic,
block hash, finality and reorg policy" is therefore declined as written: the
only policy core can honestly hold is that it does not look, and the only
chains it accepts are ones on which no real funds move.

**Canonical digest v1** is implemented in core as `clofin.canonical.edn-v1`:
the 37-byte domain prefix, the type bytes, the key ordering by encoded key
bytes, depth 16, 65,536-byte ceiling, SHA-256; the event's keyword-valued
fields are reconstructed from JSON by the schema (only `event/type` and
`finality.status` are keywords). The three golden vectors the gateway
publishes are the namespace's tests; a property test asserts that map
insertion order and integer spelling do not change the digest and that any
semantic change does.

### D8 — Authentication and roles: service actors, honestly described

Two roles join the five: **`screening-service`** holding `screening/record`
and `payment/read`; **`settlement-feed`** holding `settlement/confirm`,
`settlement/read` and `payment/read`. The agent acts as an `operator`. All
three authenticate as every caller does today — `X-Actor-Id` naming a seeded
actor — and the adapters hold that id as configuration, not as a credential,
because it is not one. Core's documents, the satellites' READMEs and this ADR
say in the same words that the transport is **not authenticated in a sense
that resists an adversary**; the identity-provider integration remains the
deferred item (COMPLIANCE §4) that would make it so. The ingress keeps its
existing body bound (`max-body-bytes` in `clofin.http.middleware`) and safe
JSON decoding; the new endpoints inherit both.

### D9 — The kit: no core interface, and the word "confidence" never reaches core

`cloagent-kit` dispatches application-owned executors; its `:confidence` and
`:action-type` are the application's concern. Core has no endpoint for it and
gains none; an executor that creates a payment is an `operator` calling
`POST /payment-instructions` through the agent's transport, and nothing it
sends carries model confidence. The handover already states that schema
validity and confidence confer no authorisation; core's answer is that it
cannot see either.

### D10 — Sequencing, scope and audit tier

| Brief | Increment | Delivers |
|---|---|---|
| TASK-017 | 7 (financial crime) | C-07: screening lists (versioned, seeded), rules, the gate at `submit`, cases and disposition, `creditorCountry`, `POST …/screening-results`, the `screening-service` role, audit vocabulary (`screening.recorded`, `screening.hit`, `case.opened`, `case.dispositioned`) |
| TASK-018 | 3 (completion) | `clientReference`, `GET …/by-idempotency-key/{key}`, the lookup's permission and tests; small enough to precede TASK-017 |
| TASK-019 | 9 (first slice) | `SIM-EVM`, the token registry, `POST …/chain-confirmations`, `clofin.canonical.edn-v1` with golden vectors, the `settlement-feed` role, the synthetic-chain policy and its pinned refusal of public ids |
| TASK-020 | — | UAT-007 corrections (already routed from TASK-016) |

All three code briefs change enforcement code in the authorisation, settlement
or financial-crime domains: the release that carries them, `ref-3`, is audited
at the **Sol** tier, full whole-repo (the 2026-08-05 rule). `clofin-trace`
and `clofin-cockpit` are not changed by any of this; a capture at `ref-3` and
a cockpit flow for screening are their own briefs under their own owner's
authorisation.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| `application/edn` as a second representation at core | Every contract example, error shape and recorded fixture would need an EDN twin maintained by hand — the L-6/L-14/L-16 class. Left to the operator as the alternative under D3. |
| New states `awaiting-screening` and `ready-for-processing` in the lifecycle table | They name what `draft` and `pending-approval` already mean; a second name for one state is a copy that drifts, and the diagrams, the contract enum, the check constraint and the state×event walk would all gain members for no new behaviour. |
| Core trusts a client's `:clear` and transitions on it | Inverts C-07 — *no instruction can be released without a completed screening decision* means core's decision. A client result is evidence; core re-screens against the same list version. |
| A chain-confirmation path separate from scheme responses | A second producer of settlement postings, a second replay identity and a second receipt rule — the 2C-009 shape (L-21). One door, one posting path. |
| Accepting `eip155:1` and other public chain ids | A posting on the strength of a real-network transfer would make the disclaimer false. Refused by policy and pinned by a test. |
| Core verifying chain data itself | Requires connectivity the constraint forbids, and a trust decision about a chain nobody simulated. Core records the gateway's provenance and does not look. |
| Satellites simulating ledger entries locally | ADR-0026: a client owns no truth. The handover forbids it too. |
| Calling the transport "authenticated" because the satellite adds mTLS or signatures on its side | Core's authentication is scaffolding; a claim on one side of a boundary the other side cannot honour is an overstatement (L-14). |

## Consequences

**Positive**

- Four clients integrate without a single new state, a second wire format, a
  second posting path or a trusted client decision; what they need is three
  small additions to existing contracts and two increments the roadmap already
  planned (7 and 9).
- C-07 is built as designed, and the fc satellite becomes a pre-screening
  client that can refuse early rather than a component core must trust.
- Chain confirmations reuse the receipt, replay and conflict rules three
  audits have already tested, and the synthetic-chain policy keeps the
  disclaimer true by construction.

**Negative / accepted cost**

- The satellites' adapters carry the EDN↔JSON mapping and the party → account
  configuration; the kit's `create-payment` action grows two arguments.
- A "pending" confirmation does not exist in core; the gateway must retry a
  not-yet-finalized event rather than park it.
- `ref-3` is a Sol-tier whole-repo audit, after three control-bearing
  increments.

**Risks and how they are mitigated**

- *The mapping tables drift from the contract.* Each table in this ADR names
  core fields that the contract test and the conformance test exercise; a
  renamed field fails those before it fails a satellite.
- *A satellite describes the integration as more than it is.* Core's documents
  carry the authentication sentence and the synthetic-chain policy; the
  satellites' READMEs are asked to quote, not paraphrase (ADR-0020 rule 3
  applied across the boundary).
- *Two digests of one event disagree.* Core recomputes v1 and refuses a
  mismatch before it reads anything else from the event.

## Verification

Each row of the handover's §6 table has a test in core, named here so the
briefs inherit them:

| Scenario | Test, in core |
|---|---|
| Invalid or unauthenticated caller | existing principal tests; the two new roles in `authz/model-test`'s both-directions permission walk |
| Same logical payment retried after a timeout | `ac-…-the-lookup-returns-the-stored-response-and-current-status` in `api/payments-api-test`; a `404` only when no key is bound |
| Concurrent equivalent submissions | the existing two-connection latch test over `execute-once!` (one effect, one replay) — extended to assert one `clientReference` row |
| Same key, different instruction data | existing `409` test; plus `clientReference` reuse with different content → `409`, no new row |
| Screening hit | `submit` answers `409 screening-hit`; no `payment.submitted` event; a case row exists |
| Clear with stale list or mismatched instruction | `422 list-version-not-accepted` / `422 instruction-digest-mismatch`; the instruction unchanged |
| Screening race against another transition | two-connection latch test: `amend` and `screening-result` on one instruction serialise on the `FOR UPDATE` read; at most one wins (L-8) |
| Identical chain event delivered repeatedly, and after restart | the replay-key unique constraint; the stored response returned on the second pool after the first is closed (`recon/concurrency-test`'s shape) |
| Same identity, inconsistent content | `409` with `existingCanonicalDigest`; journal entry count unchanged |
| Finality, binding or amount not verifiable | `422 not-finalized` / `payment-binding-mismatch` / `transfer-mismatch`; nothing posted |
| Chain reorganisation | kind `returned`, reason `reorged`: `released → returned`, a reversing entry, no row updated (the append-only triggers' raw-SQL tests) |
| Wrong response contract | the conformance test over every new operation (status declared, required members present, enum values declared) |
| Core failure or ambiguous outcome | the lookup endpoint; and `unit-of-work-test`'s matrix extended to the screening and confirmation services (L-13) |
| Public chain ids refused | `unsupported-chain` for `eip155:1`, pinned; a negative control that widening the allowed set fails the policy test |
| Canonical digest v1 | the three golden vectors; a property test over ordering and spelling invariance |

Mechanically, every guard above lands in `make verify` or `make test-it`; the
partial-set sweep's discovered sets (schemes, roles, permissions, audit
actions, enum copies) gain the new members in both directions or the
existing tests fail.
