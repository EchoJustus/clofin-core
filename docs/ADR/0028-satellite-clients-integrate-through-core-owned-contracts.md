# ADR-0028: Satellite clients integrate through core-owned JSON contracts; screening, idempotent creation and simulated chain confirmations are core transitions

- **Status:** Accepted — proposed 2026-10-03 with eight rulings requested; ruled by the operator the same day: D3, D4, D8, D9, D10 accepted as proposed; D5, D6, D7 accepted with the amendments recorded in each section (the *Rulings* section carries the operator's text)
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
contract is reconciled below. The satellites' EDN shapes are satisfied by the
satellites' own transport adapters (`CoreTransport`, `CorePosting`, the fc
interceptor's future adapter), which translate to and from the JSON contracts
published in *Contracts, published* using the mapping tables there. Core
publishes the contracts, the mapping tables, and the tests that prove the
atomic behaviour the handover's §6 asks for; it does not publish, reference or
depend on the satellites' internals, three of which are proprietary.

### D3 — Wire format: JSON at core, EDN at the satellites (accepted)

Core keeps one representation. The satellite transports serialise their EDN
to the JSON shapes `api/openapi.yaml` declares and decode core's JSON
responses into their EDN envelopes; those adapters are explicit and tested on
the satellites' side. Keyword-valued fields on the satellite side
(`:payment/awaiting-screening`, `:erc20/transfer`, `:finalized`) are string
enums at core and the mapping is in the tables below. The satellites'
`X-Clofin-Contract-Version` header is accepted and ignored; **that is not
version negotiation and must not be described as such on either side** —
core's contract is versioned by the OpenAPI document and the release tag, and
a header that disagrees with a URL would be a second copy.

*The alternative not taken:* an `application/edn` codec in core's ingress
middleware. Rejected for `ref-3` because the contract test, the capture
harness, the cockpit's recorded responses and the trace fixtures all speak
JSON, and every example the contract carries would need an EDN twin maintained
by hand.

### D4 — State names: no new states; a mapping table (accepted)

| Satellite name | Core state or event | Note |
|---|---|---|
| `:payment/awaiting-screening` (creation acknowledgment) | `draft` | created, not yet submitted; screening has not run |
| `:payment/release-for-processing` (the proposed transition) | the `submit` event, `draft → pending-approval`, gated by C-07 | performed by the maker, not by the screening client — see D5 |
| `:payment/ready-for-processing` | `pending-approval` | cleared screening; awaits maker–checker approval, then release into a batch |
| `:expected-payment-state :payment/ready-for-processing` on a chain confirmation | **`released`**, in the settlement batch core resolves for the instruction | a confirmation applies to an instruction in a submitted batch; the satellite flow omits approval and release, which core does not skip |
| creation ack `202` / replay `200` with `:replayed?` | `201` on both; `Idempotent-Replayed: true` on the replay | the adapter derives `:replayed?` from the header |

Core answers with core's names. An adapter that needs the satellite's names
maps them; core never emits a state it does not have.

### D5 — Screening (C-07, increment 7): core screens; a client's result is evidence, never authorisation (accepted, amended)

Core performs screening itself at `submit`, against a **versioned synthetic
list it holds**, with deterministic rules — the C-07 design. The fc
satellite's `apply-screening-result!` is reconciled as:

```
POST /payment-instructions/{id}/screening-results          (Idempotency-Key required)
```

which **records an externally produced screening result as evidence** — with
**either valid outcome, `clear` or `hit`** — bound to the instruction's
content digest and to a list version core holds; core **recomputes** the
result against that list version and stores both the submitted and the
recomputed outcome with whether they agree. The call **transitions nothing**.
`submit` consults core's own decision, made at `submit` time against core's
accepted list, and not the client's; a hit — recorded by a client or found by
core — leaves the instruction in `draft`, opens a case, and `submit` answers
`409 screening-hit` until the case is dispositioned.

The satellite's seven preconditions map onto two different moments, and the
table says which:

| Satellite precondition | Where core checks it | Core check | Refusal |
|---|---|---|---|
| `:instruction-exists` | recording | the id resolves within the caller's organisation | `404` |
| `:instruction-content-matches` | recording | `instructionDigest` equals core's canonical digest of the stored instruction's immutable fields | `422 instruction-digest-mismatch` |
| `:expected-prior-state-matches` | recording | status is `draft` when the row is read `FOR UPDATE` (L-8); evidence against a submitted instruction is refused | `409` with the lifecycle's own refusal |
| `:risk-list-version-accepted` | recording | `listVersion` names a list core holds and has not retired | `422 list-version-not-accepted` |
| `:screening-result-verified` | recording | the submitted outcome and entries equal core's recomputation against that list — a disagreement is **recorded and refused**, not silently overwritten | `422 screening-result-mismatch` |
| `:screening-result-is-clear` | **`submit`** | core's own decision for this instruction, against the list core accepts at that moment, is `clear` | `409 screening-hit` |
| `:core-authorizes-transition` | recording and `submit` | the recorder holds `screening/record`; the submitter is the creator (C-01) and C-07's gate is core's | `403` / `409` |

**What a `201` means, exactly.** The response is a `ScreeningResult` resource
(*Contracts, published*) with an `auditEventId`. It means *evidence recorded*
and nothing else: **no payment-state change has occurred, and neither the
`201` nor the audit event id may be translated into the satellite's `:applied`
transition acknowledgment**. The satellite's response contract is revised
accordingly: its success envelope means *evidence recorded, state unchanged*;
the only acknowledgment of a transition is the `PaymentInstruction` returned
by `submit`, with `status: pending-approval`. The satellite's `:rejected` and
`:error` envelopes are its rendering of core's `4xx` problem documents and of
transport failure. Lists are loaded by seed, never by clients, so a clear
result against an empty list is accepted only if core holds that empty list
under that version, which it will not.

### D6 — Payment creation and lookup: the existing endpoint, with three small additions (accepted, amended)

`POST /payment-instructions` is the endpoint; there is no `/v1/payments`. The
satellite's `PaymentInstruction` maps as follows, and the adapter owns the
mapping:

| Satellite field | Core field | Rule |
|---|---|---|
| `:instruction-id` (client-chosen) | **`clientReference`** — new, optional, string 1–128, unique per organisation | see the reference rules below; core still issues the instruction's `id` |
| `:amount 123.4500M` + `:currency` | `amount: {currency, minorUnits}` | **exact conversion in the adapter, before submission**: `amount × 10^scale` must be an integer in `int64` — excess scale (`123.4500` for a scale-2 currency when the trailing digits are not zero) and overflow are **refused before sending**; core cannot recover precision an adapter discarded. Core refuses `minorUnits` outside its schema and never rounds |
| `:originator {:party-id …}` | `debtorAccountId` | core's debtor is a ledger account the organisation owns; the adapter holds the party → account mapping as configuration. `:name` and `:country` of the originator are the organisation's and are not per-instruction fields |
| `:beneficiary {:party-id :name :country}` | `creditorAccount`, `creditorName`, **`creditorCountry`** — new, optional, ISO 3166-1 alpha-2 | `creditorCountry` exists so a screening rule can name it (D5); it is the only new instruction field besides `clientReference` |
| — | `valueDate`, `purposeCode` | required by core and absent from the satellite shape: **they are arguments of `clofin-agent`'s `create-payment` action contract**, not of the generic kit, and the adapter does not default them |
| `Idempotency-Key` UUID v4 | `Idempotency-Key` | identical semantics (ADR-0013) |

**`clientReference` rules.** Within an organisation: the same reference under
the **same** idempotency key with identical content is the replay; the same
reference under **any** key with different content is `409
client-reference-conflict`; the same reference with identical content under a
**different** key is `409 client-reference-exists`, whose problem document
names the existing `instructionId` — **core never creates a second
instruction for a reference it holds**. A lost key is therefore resolved by
the reference, and a replaced key cannot create another instruction.

Core's answers, as today: `201` + `PaymentInstruction` (`status: draft`);
replay `201` + the same body + `Idempotent-Replayed: true`; `400` validation,
`401`/`403` principal, `409` key bound to a different digest or the two
reference conflicts, `422` refusals. The satellite's status-code table is the
adapter's; `429`, `500`, `503` and `504` are transport-level and never carry a
core problem document.

**The lookup**, new:

```
GET /payment-instructions/by-idempotency-key/{key}       (permission payment/read)
```

answers `200` with the stored original response (status and body, as replay
would return it) plus the instruction's current `status`, scoped to the
caller's organisation; `404` when no key is bound there. It reads the same
table the write path binds in its transaction, with no cache, so a `404` is
strongly consistent **as of that read: it means no committed binding existed
at that instant, and it does not prove that a `POST` still in flight cannot
commit afterwards.** The client keeps its original key after a `404`,
re-reads before any decision to resubmit, and resubmits — if at all — under
the **same** key, which turns a late commit into a replay rather than a second
instruction. A test races the lookup against an in-flight creation and
asserts `404` then `200` on one key with one instruction (*Verification*).
Retention is the existing named debt.

### D7 — Chain confirmations (increment 9, simulation only): a third simulated scheme behind the one settlement door (accepted, amended)

A chain confirmation is a scheme response. Core gains a simulated scheme
**`SIM-EVM`** beside `SIM-RTGS` and `SIM-ACH` (the check constraint and
`clofin.settlement.batch/known-schemes` widen together; the partial-set sweep
covers both), a synthetic token registry (`tokenContract → currency, decimals`)
loaded by seed, and one new operation:

```
POST /chain-confirmations        (Idempotency-Key required; permission settlement/confirm)
```

Organisation-scoped and keyed by core's `instructionId`, so the gateway needs
no batch id: core resolves the instruction, requires `released`, resolves the
batch it was released in, validates the event exactly as the gateway's schema
states it (closed objects; lowercase hex; `eip155:` chain ids as canonical
decimals; `uint64` and `uint256` bounds; `amountBaseUnits` as a canonical
decimal string), **recomputes canonical digest v1** from the event and refuses
a client digest that differs (`422 digest-mismatch`), checks that the event's
`instructionId` equals the instruction's `clientReference` (`422
payment-binding-mismatch`), converts base units to minor units through the
token registry without rounding (`422 transfer-mismatch` when the amount is not
the instruction's), and then **delegates to the same posting path
`recordSchemeResponse` uses** — kind `settled` for a finalized transfer — so
there is one producer of settlement postings (L-21), one receipt rule (L-11)
and one conflict rule (L-12). The reference is the event identity rendered as
`eip155:<chain>/<txhash>/<logIndex>`.

**Event uniqueness, durably and atomically.** The scheme-response replay tuple
`(batch, instruction, kind, reference)` does not by itself stop the same
on-chain event from being posted against a second instruction or a second
batch. A table `chain_confirmation` binds, under a **unique constraint over
`(organisation_id, chain_id, transaction_hash, log_index)`**, the event
identity to its `instruction_id`, `settlement_batch_id`, `scheme_response_id`,
`canonical_digest` and the full canonical event, **inserted in the same
transaction as the scheme response and its posting**. Arrivals: same identity,
same digest, same instruction → the stored response, `replayed: true`; same
identity, different digest → `409 identity-digest-conflict` carrying
`existingCanonicalDigest`; same identity, same digest, **different
instruction or batch** → `409 event-bound-elsewhere` naming the existing
`instructionId`. The organisation is the simulation scope: an event identity
is unique within an organisation, and the chain id is part of the identity.
Nothing is posted on any `409`.

**Reorg semantics, v1.** Core's `return` transition applies to a `released`
instruction only: a reversal event arriving **before** settlement is kind
`returned` with reason `reorged` and moves `released → returned`, which is
terminal (ADR-0019). **A reversal arriving after `settled` is refused, `409
settled-is-terminal`**: `settled` is terminal, the existing return transition
does not and cannot reverse a settled posting, and this ADR defines no
compensation operation. If one is ever needed it is an append-only operation
ruled separately — a reversing journal entry with its own audit event, never a
mutation of the posted one.

**Chain schema and allow-list.** The gateway's v1 schema accepts `eip155:`
identifiers only, and so does core: the pinned allow-list is **`eip155:31337`**
(a local development chain) and nothing else; a `sim:` prefix is not
introduced by this ADR and would be a negotiated schema change on both sides.
`eip155:1` and every other public network id answer `422 unsupported-chain`
by policy; a test pins the refusal and a negative control proves that widening
the allow-list fails the policy test.

**Finality and provenance.** Core verifies nothing on any chain: it has no
RPC, no connectivity, and the constraint forbids both. A confirmation is
trusted exactly as a `SIM-RTGS` response is trusted today — it is what the
caller sent, and the caller is a seeded actor holding `settlement/confirm`.
The gateway's verifier output (`sourceId`, `attestationId`) is **recorded** on
the confirmation as provenance and rendered in the evidence pack; it is not
re-verified by core. **Neither side may describe any of this as verified
real-network settlement**: it is simulation provenance on a local chain, and
the documents on both sides say so in those words. An event whose
`finality.status` is not `finalized` is refused `422 not-finalized` and
retried by the gateway later; core holds no pending confirmations.

**Canonical digest v1** is implemented in core as `clofin.canonical.edn-v1`:
the 37-byte domain prefix, the type bytes, the key ordering by encoded key
bytes, depth 16, the 65,536-byte ceiling, SHA-256; the event's EDN form is
reconstructed from JSON by the schema (*Contracts, published* gives the key
and keyword mapping). The three golden vectors the gateway publishes are the
namespace's tests; a property test asserts that map insertion order and
integer spelling do not change the digest and that any semantic change does.

### D8 — Authentication and roles: service actors, honestly described (accepted)

Two roles join the five: **`screening-service`** holding `screening/record`
and `payment/read`; **`settlement-feed`** holding `settlement/confirm`,
`settlement/read` and `payment/read`. The agent acts as an `operator`. All
three authenticate as every caller does today — `X-Actor-Id` naming a seeded
actor — and the adapters hold that id as **configured identity, not a
credential**, because it is not one. Core's documents, the satellites' READMEs
and this ADR say in the same words that the transport is **not authenticated
in a sense that resists an adversary**; the identity-provider integration
remains the deferred item (COMPLIANCE §4) that would make it so. The ingress
keeps its existing body bound (`max-body-bytes` in `clofin.http.middleware`)
and safe JSON decoding; the new endpoints inherit both.

### D9 — The kit: no core interface, and the word "confidence" never reaches core (accepted)

`cloagent-kit` dispatches application-owned executors; its `:confidence` and
`:action-type` are the application's concern. Core has no endpoint for it and
gains none; an executor that creates a payment is an `operator` calling
`POST /payment-instructions` through the agent's transport, and nothing it
sends carries model confidence. Confidence is never a core authorisation
input.

### D10 — Sequencing, scope and audit tier (accepted)

| Brief | Increment | Delivers |
|---|---|---|
| TASK-018 | 3 (completion) — **first** | `clientReference` and its three rules, `creditorCountry`, `GET …/by-idempotency-key/{key}`, the lookup race test |
| TASK-017 | 7 (financial crime) | C-07: screening lists (versioned, seeded), rules, the gate at `submit`, cases and disposition, `POST …/screening-results`, the `screening-service` role, audit vocabulary |
| TASK-019 | 9 (first slice) | `SIM-EVM`, the token registry, `chain_confirmation`, `POST /chain-confirmations`, `clofin.canonical.edn-v1` with golden vectors, the `settlement-feed` role, the `eip155:31337` allow-list and its pinned refusals, `settlementBatchId` on the instruction resource |
| TASK-020 | — | UAT-007 corrections (already routed from TASK-016) |

All three code briefs change enforcement code in the authorisation, settlement
or financial-crime domains: the release that carries them, `ref-3`, is audited
at the **Sol** tier, full whole-repo (the 2026-08-05 rule). The test
identifiers in *Verification* are executable names, and **the release waits
on their actual results**, in the REQs and in Master Control's reproduction,
not on their existence. `clofin-trace` and `clofin-cockpit` are not changed by
any of this; a capture at `ref-3` and a cockpit flow for screening are their
own briefs under their own owner's authorisation.

## Contracts, published

OpenAPI fragments as the briefs will add them to `api/openapi.yaml`. Every
object is closed (`additionalProperties: false`); every enum is a copy the
contract test discovers.

**Instruction additions** (`CreatePaymentInstructionRequest` and
`PaymentInstruction`):

```yaml
clientReference:    { type: string, minLength: 1, maxLength: 128, pattern: '^[\x21-\x7E]+$' }  # printable ASCII, no spaces
creditorCountry:    { type: string, pattern: '^[A-Z]{2}$' }                                      # ISO 3166-1 alpha-2, syntax only
settlementBatchId:  { type: string, format: uuid }   # PaymentInstruction only; present from `released` onward
```

**Screening** (TASK-017):

```yaml
ScreeningRule:
  required: [field, operator, value]
  properties:
    field:    { type: string, enum: [creditor-name, creditor-account, creditor-country] }
    operator: { type: string, enum: [exact] }
    value:    { type: string, minLength: 1 }
ScreeningEntry:
  required: [id, rules]
  properties:
    id:    { type: string, minLength: 1 }
    rules: { type: array, minItems: 1, items: { $ref: '#/components/schemas/ScreeningRule' } }
ScreeningList:                       # read-only resource; loaded by seed, never by clients
  required: [version, entries, loadedAt]
  properties:
    version:  { type: string, minLength: 1, maxLength: 128 }
    entries:  { type: array, items: { $ref: '#/components/schemas/ScreeningEntry' } }
    loadedAt: { type: string, format: date-time }
ScreeningResultRequest:              # POST /payment-instructions/{id}/screening-results
  required: [organisationId, listVersion, outcome, matchedEntries, instructionDigest]
  properties:
    organisationId:    { type: string, format: uuid }
    listVersion:       { type: string, minLength: 1, maxLength: 128 }
    outcome:           { type: string, enum: [clear, hit] }
    matchedEntries:    { type: array, items: { $ref: '#/components/schemas/ScreeningEntry' } }  # empty iff outcome is clear
    instructionDigest: { type: string, pattern: '^[0-9a-f]{64}$' }   # core's canonical digest of the stored instruction's immutable fields
    screenedAt:        { type: string, format: date-time }
ScreeningResult:                     # 201
  required: [id, organisationId, instructionId, listVersion, outcome, matchedEntries,
             coreOutcome, coreMatchedEntries, agrees, instructionDigest, recordedBy, recordedAt, auditEventId]
  properties:
    coreOutcome: { type: string, enum: [clear, hit] }   # core's recomputation; agrees = (outcome, matchedEntries) equal
    # … the remaining properties as named, with the types of their request counterparts
```

Satellite rule-field mapping: `beneficiary-name → creditor-name`,
`beneficiary-party-id → creditor-account`, `beneficiary-country →
creditor-country`. `originator-*` rules have no per-instruction field in core;
a list carrying one is refused `422 unsupported-rule-field` at load, so the
adapter refuses it before sending.

**Idempotency lookup** (TASK-018):

```yaml
IdempotencyKeyLookup:                # GET /payment-instructions/by-idempotency-key/{key}
  required: [idempotencyKey, instructionId, boundAt, originalStatus, originalBody, currentStatus]
  properties:
    idempotencyKey: { type: string, format: uuid }                    # corrected 2026-10-04 (TASK-018): published as { type: string, minLength: 1, maxLength: 255 } — the key the service accepts is any non-blank string without control characters, not only a UUID
    instructionId:  { type: string, format: uuid }
    boundAt:        { type: string, format: date-time }
    originalStatus: { type: integer }                                  # the stored HTTP status, 201
    originalBody:   { $ref: '#/components/schemas/PaymentInstruction' } # as stored at creation
    currentStatus:  { $ref: '#/components/schemas/PaymentStatus' }
```

**Chain confirmations** (TASK-019):

```yaml
ChainTransferEvent:                  # the gateway's normalized event, in JSON
  required: [eventVersion, eventType, instructionId, chainId, transaction, block, finality, transfer]
  properties:
    eventVersion:  { type: integer, enum: [1] }
    eventType:     { type: string, enum: [erc20/transfer] }
    instructionId: { type: string, minLength: 1, maxLength: 128 }     # the satellite reference = clientReference
    chainId:       { type: string, pattern: '^eip155:(0|[1-9][0-9]{0,77})$' }
    transaction:   { required: [hash, logIndex], properties: { hash: { pattern: '^0x[0-9a-f]{64}$' }, logIndex: { type: integer, minimum: 0 } } }
    block:         { required: [hash, number],   properties: { hash: { pattern: '^0x[0-9a-f]{64}$' }, number:   { type: integer, minimum: 0 } } }
    finality:      { required: [status, confirmations], properties: { status: { enum: [finalized] }, confirmations: { type: integer, minimum: 1 } } }
    transfer:
      required: [tokenContract, fromAddress, toAddress, amountBaseUnits]
      properties:
        tokenContract:   { pattern: '^0x[0-9a-f]{40}$' }
        fromAddress:     { pattern: '^0x[0-9a-f]{40}$' }
        toAddress:       { pattern: '^0x[0-9a-f]{40}$' }
        amountBaseUnits: { type: string, pattern: '^(0|[1-9][0-9]*)$' }
CanonicalDigest:
  required: [algorithm, version, value]
  properties:
    algorithm: { enum: [sha-256] }
    version:   { type: integer, enum: [1] }
    value:     { type: string, pattern: '^[0-9a-f]{64}$' }
ChainConfirmationRequest:            # POST /chain-confirmations
  required: [organisationId, instructionId, event, canonicalDigest, provenance]
  properties:
    organisationId:  { type: string, format: uuid }
    instructionId:   { type: string, format: uuid }                   # core's id
    event:           { $ref: '#/components/schemas/ChainTransferEvent' }
    canonicalDigest: { $ref: '#/components/schemas/CanonicalDigest' }
    provenance:
      required: [authentication, sourceId, attestationId]
      properties:
        authentication: { enum: [verified] }                          # `unverified` is refused: 422 provenance-unverified
        sourceId:       { type: string, minLength: 1, maxLength: 128 }
        attestationId:  { type: string, minLength: 1, maxLength: 256 }
ChainConfirmation:                   # 200 (replay: the same body, replayed: true)
  required: [id, organisationId, instructionId, settlementBatchId, schemeResponseId, journalEntryId,
             kind, eventIdentity, canonicalDigest, provenance, replayed, recordedAt, simulated]
  properties:
    kind:          { enum: [settled, returned] }
    eventIdentity: { required: [chainId, transactionHash, logIndex] }
    simulated:     { type: boolean, enum: [true] }
```

**Digest reconstruction** (how core rebuilds the gateway's EDN event from the
JSON to recompute v1; the only keywords are the three named):

| JSON key | EDN key | Value |
|---|---|---|
| `eventVersion` | `:event/version` | integer |
| `eventType` | `:event/type` | keyword `:erc20/transfer` |
| `instructionId`, `chainId` | `:instruction-id`, `:chain-id` | string |
| `transaction.hash`, `transaction.logIndex` | `:transaction {:hash :log-index}` | string, integer |
| `block.hash`, `block.number` | `:block {:hash :number}` | string, integer |
| `finality.status`, `finality.confirmations` | `:finality {:status :confirmations}` | keyword `:finalized`, integer |
| `transfer.tokenContract` … `amountBaseUnits` | `:transfer {:token-contract :from-address :to-address :amount-base-units}` | strings |

**Identity mapping** across the boundary:

| Satellite holds | Core field | Where it is learned |
|---|---|---|
| its `instruction-id` | `clientReference` | sent on creation; echoed on the instruction |
| — | `id` (the instruction's uuid) | the creation response, kept with the prepared request |
| — | `settlementBatchId` | on the instruction resource from `released`; the gateway never needs it |
| the gateway event's `instruction-id` | must equal the instruction's `clientReference` | checked at `POST /chain-confirmations` |

## Rulings (the operator, 2026-10-03)

Recorded verbatim in substance. D3, D4, D8, D9 and D10 accepted as proposed,
with: an ignored version header is not version negotiation (D3); creation maps
to `draft`, screening does not bypass approval or release, and a chain
confirmation requires `released` and the appropriate batch (D4); `X-Actor-Id`
is configured identity, not a credential, on both sides (D8); the kit has no
core interface and confidence never becomes an authorisation input (D9);
TASK-018 first, the control-bearing release under a Sol-tier whole-repository
audit, executable test identifiers and actual results before release (D10).
D5 amended: both evidence outcomes supported, recomputed and compared; the
transition exclusively core's; a `201` receipt or audit-event id is not
`:applied`; the satellite's success envelope means evidence recorded with no
state change. D6 amended: `valueDate` and `purposeCode` in `clofin-agent`'s
action-specific contract; exact conversion with excess scale and overflow
refused before submission; same reference and identical content under a
different key returns the existing instruction or an explicit conflict, never
a second instruction; a strongly consistent `404` proves only that no
committed binding existed at that read, the original key is preserved, and
the race is tested. D7 amended: durable, atomic uniqueness of
`(chain-id, transaction-hash, log-index)` bound to instruction and canonical
data with an explicit organisation scope, cross-instruction and cross-batch
conflict tests, one posting path; post-settlement reorg refused in v1 with no
claim that `return` reverses a settled posting; the allow-list is
`eip155:31337`, no `sim:` prefix without a negotiated schema change, public
networks refused with a mandatory negative control; simulation provenance
recorded without chain verification and never described as verified
real-network settlement. Core, trace and cockpit unchanged by the review.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| `application/edn` as a second representation at core | Every contract example, error shape and recorded fixture would need an EDN twin maintained by hand — the L-6/L-14/L-16 class. |
| New states `awaiting-screening` and `ready-for-processing` in the lifecycle table | They name what `draft` and `pending-approval` already mean; a second name for one state is a copy that drifts, and the diagrams, the contract enum, the check constraint and the state×event walk would all gain members for no new behaviour. |
| Core trusts a client's `:clear` and transitions on it | Inverts C-07 — *no instruction can be released without a completed screening decision* means core's decision. A client result is evidence; core re-screens against the same list version. |
| A chain-confirmation path separate from scheme responses | A second producer of settlement postings, a second receipt rule — the 2C-009 shape (L-21). One door, one posting path; the event-identity table adds uniqueness, not a second path. |
| Keying chain confirmations by batch id | The gateway's event carries no batch; core resolves the batch from the released instruction, which it alone knows. |
| Accepting `eip155:1` and other public chain ids, or a `sim:` prefix now | A posting on the strength of a real-network transfer would make the disclaimer false; refused by policy and pinned by a test. A new prefix is a schema change both sides must negotiate. |
| Core verifying chain data itself | Requires connectivity the constraint forbids, and a trust decision about a chain nobody simulated. Core records the gateway's provenance and does not look. |
| A compensation operation for post-settlement reorgs in v1 | `settled` is terminal and `return` does not reverse a posting; an append-only compensation is a separate ruling, not a side effect of this one. |
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
  audits have already tested, gain a durable event-identity binding, and the
  local-chain-only policy keeps the disclaimer true by construction.

**Negative / accepted cost**

- The satellites' adapters carry the EDN↔JSON mapping, the exact decimal
  conversion and the party → account configuration; `clofin-agent`'s
  `create-payment` action grows two arguments.
- A "pending" confirmation does not exist in core; the gateway must retry a
  not-yet-finalized event rather than park it. A post-settlement reorg is
  refused in v1.
- `ref-3` is a Sol-tier whole-repo audit, after three control-bearing
  increments.

**Risks and how they are mitigated**

- *The mapping tables drift from the contract.* Each table names core fields
  that the contract test and the conformance test exercise; a renamed field
  fails those before it fails a satellite.
- *A satellite describes the integration as more than it is.* Core's documents
  carry the authentication sentence and the local-chain policy; the
  satellites' READMEs are asked to quote, not paraphrase (ADR-0020 rule 3
  applied across the boundary).
- *Two digests of one event disagree.* Core recomputes v1 and refuses a
  mismatch before it reads anything else from the event.
- *A `404` is read as proof of absence.* The lookup's contract says what it
  proves, the client keeps its key, and the race test exists.

## Verification

Each row of the handover's §6 table has an executable test in core, named as
`namespace/deftest`; the briefs inherit these names, and the release waits on
their results.

| Scenario | Test |
|---|---|
| Invalid or unauthenticated caller | `clofin.authz.model-test/every-permission-the-router-requires-is-granted-and-every-grant-is-required` (both directions, the two new roles included); `clofin.api.payments-api-test/a-006-…` family for principal refusals |
| Same logical payment retried after a timeout | `clofin.api.payments-api-test/ac-18-1-the-lookup-returns-the-stored-response-and-current-status`; `…/ac-18-2-the-lookup-answers-404-only-when-no-key-is-bound` |
| The lookup raced against an in-flight creation | `clofin.api.payments-api-test/ac-18-3-a-404-during-an-in-flight-creation-becomes-200-on-the-same-key-with-one-instruction` (two connections, a latch between the key binding and the commit) |
| Concurrent equivalent submissions | the existing `clofin.api.payments-api-test/ac-9-two-concurrent-requests-with-one-key-produce-exactly-one-effect`, extended to assert one `clientReference` row *(corrected 2026-10-04, TASK-018: this row named `one-key-two-concurrent-submissions-one-effect-one-replay`, which never existed; the test it meant is the one named here)* |
| Same key, different instruction data | the existing `409` test; `…/ac-18-4-a-reused-client-reference-with-different-content-is-409-and-creates-nothing` |
| Same reference, identical content, different key | `…/ac-18-5-a-reused-client-reference-under-a-new-key-answers-409-naming-the-existing-instruction` |
| Screening hit | `clofin.api.screening-api-test/ac-17-1-submit-answers-409-screening-hit-and-emits-no-payment-submitted-event`; `…/ac-17-2-a-hit-opens-a-case-in-the-same-transaction` |
| Clear with stale list or mismatched instruction | `…/ac-17-3-an-unaccepted-list-version-is-422`; `…/ac-17-4-an-instruction-digest-mismatch-is-422-and-changes-nothing` |
| Client result disagrees with core's recomputation | `…/ac-17-5-a-result-core-cannot-reproduce-is-422-screening-result-mismatch-and-is-recorded-as-refused` |
| Evidence is not a transition | `…/ac-17-6-a-201-screening-result-leaves-status-draft-and-emits-no-transition-event` |
| Screening race against another transition | `clofin.recon.concurrency-test`'s shape, new namespace `clofin.screening.concurrency-test/ac-17-7-amend-and-screening-result-serialise-on-the-instruction-row` |
| Identical chain event delivered repeatedly, and after restart | `clofin.api.chain-confirmations-api-test/ac-19-1-the-second-delivery-replays-the-first-response`; `…/ac-19-2-the-replay-survives-a-new-connection-pool` |
| Same identity, inconsistent content | `…/ac-19-3-a-different-digest-under-one-identity-is-409-and-posts-nothing` |
| Same identity bound to another instruction or batch | `…/ac-19-4-an-identity-already-bound-elsewhere-is-409-event-bound-elsewhere` (cross-instruction and cross-batch cases) |
| Finality, binding or amount not verifiable | `…/ac-19-5-not-finalized-is-422`; `…/ac-19-6-a-client-reference-that-is-not-the-instructions-is-422`; `…/ac-19-7-an-amount-that-is-not-the-instructions-is-422` |
| Chain reorganisation | `…/ac-19-8-a-reversal-before-settlement-returns-the-instruction`; `…/ac-19-9-a-reversal-after-settlement-is-409-settled-is-terminal-and-mutates-nothing` |
| Public chain ids refused | `…/ac-19-10-eip155-1-is-422-unsupported-chain`; negative control `clofin.settlement.chain-policy-test/widening-the-allow-list-fails-this-test` |
| Wrong response contract | `clofin.api.conformance-test` over every new operation (status declared, required members present, enum values declared) |
| Core failure or ambiguous outcome | the lookup; `clofin.audit.unit-of-work-test/every-audit-composing-service-is-covered-here` extended to the screening and confirmation services (L-13) |
| Canonical digest v1 | `clofin.canonical.edn-v1-test/the-three-golden-vectors`; `…/insertion-order-and-integer-spelling-do-not-change-the-digest`; `…/every-semantic-change-changes-the-digest` |

Mechanically, every guard above lands in `make verify` or `make test-it`; the
partial-set sweep's discovered sets (schemes, roles, permissions, audit
actions, enum copies) gain the new members in both directions or the
existing tests fail.

## Amendment 1 — what TASK-017 added to the screening contract (2026-10-05)

*Appended by the TASK-017 Worker as the brief directs; nothing above is
rewritten. Every item here is published in `api/openapi.yaml`. The enums, and
`screeningDigest`'s shape, are compared with the code by `clofin.contract-test`
(and every response by the conformance walk); the other members (`caseId`,
`blockingCaseId`, `matchedEntries`, `coreMatchedEntries`, `screenedAt`) are
asserted by `clofin.api.screening-api-test`. The objections this amendment raises are in
`docs/audits/017-REQ-screening-and-cases.md`, for Master Control's ruling.*

**`screeningDigest` on `PaymentInstruction`** (required, read-only). D5 asks a
client to send `instructionDigest`, "core's canonical digest of the stored
instruction's immutable fields", without publishing how a client obtains it. A
client now reads it from the instruction and echoes it, rather than
reimplementing the canonical form. It is `clofin.screening.subject/digest`:
lowercase SHA-256 hex over `clofin.idempotency/canonical` of
`clofin.audit/normalise` applied to the projection
`{projection: "screening-subject/1", id, organisationId, debtorAccountId,
creditorName, creditorAccount, creditorCountry, amount, valueDate,
purposeCode}` — identity and the screened content, **not** status, provenance
or timestamps, so a submission does not move it and an amendment does. The
projection's name is inside it, so a changed projection changes every digest.
D5's "immutable fields" is read as *the fields a decision is about*: they are
amendable while `draft`, and an amendment is exactly what must make a decision
moot.

**`ScreeningResult`, completed.** The fragment above listed its required members
and left "the remaining properties as named, with the types of their request
counterparts". As published:

- `disposition` (`accepted` | `refused`) is **required**, and
  `dispositionReason` (`screening-result-mismatch`) is present exactly when it
  is `refused` — D5's "a disagreement is recorded and refused" made a member,
  because a refused result is stored and listed.
- `matchedEntries` and `coreMatchedEntries` are **arrays of entry ids**, sorted —
  not the request's `ScreeningEntry` objects. The rules are the list's, readable
  at `GET /screening-lists/{version}`; the request carries them only so core can
  refuse a claim on rules the list does not have.
- `caseId` is present when a case was opened by the hit, or — on the `201` that
  recorded it — when a case already covers the same content against the same
  list, open or dispositioned. A client's accepted hit never opens a second
  case for content and a list a case already covers, so a disposition is never
  superseded by later evidence (017-REQ R-1, O-7: narrower than A-6's "opens a
  case if none is open", ruling asked).
- On `submit`'s `409 screening-hit`, `errors.blockingCaseId` replaces
  `errors.caseId` when the instruction's one open case is about earlier content
  or another list, and so is not the case for this content (017-REQ R-2). In
  that one case the `409` departs from A-7's and AC-17-1's "with
  `errors.caseId`" — 017-REQ O-6, ruling asked.
- `screenedAt` is the client's own, recorded as sent.

**Refusal reasons beyond D5's table**, one published enum
(`ScreeningRefusalReason`) with every `errors.reason` screening answers:
`outcome-entries-inconsistent` (`422`: a `hit` with no entries, a `clear` with
some), `matched-entries-unknown` (`422`: an entry the version does not hold, or
holds with other rules), `screening-required` (`409`: the repository's own gate
— no core decision over the instruction's current content, or one against a
retired list), `no-screening-list-accepted` (`422`: no list accepted, so nothing
is screened and nothing submitted) and `self-disposition` (`403`: the
instruction's maker may not disposition its case). D5's own four —
`list-version-not-accepted`, `instruction-digest-mismatch`,
`screening-result-mismatch`, `screening-hit` — are unchanged.

**Two refusals bind the `Idempotency-Key`.** A `422 screening-result-mismatch`
and a `409 screening-hit` are answers whose evidence commits — the refused
result; core's result and the case — and a retry under the same key replays the
refusal rather than recording the evidence twice. Every other refusal stores
nothing and leaves the key unconsumed, as before.

**The role, as built** (D8). `screening-service` holds `screening/record` and
`payment/read`, as D8 says, **and** `screening/read` and `organisation/read`,
as the brief's A-10 specifies: the client must read the accepted list it
screens against and the instruction's `screeningDigest`, and every role reads
its own organisation. It still writes nothing but evidence, and a test says so.

**Where results are read.** `GET /screening-results?instructionId=` — not
`GET /payment-instructions/{id}/screening-results` as the brief named it,
because that path can match the same request as the idempotency-key lookup and
the route table is held to having no such pair (TASK-018's accepted invariant).
Objection O-1 in the REQ asks for the ruling; the recording operation is at the
path D5 names.
