# TASK-019: Chain confirmations — a third simulated scheme behind the one settlement door, a durable event identity, and a digest core recomputes

| Field | Value |
|---|---|
| **Increment** | 9 (first slice) — programmable settlement exploration, **as a simulation on a local chain and nothing else** |
| **Status** | `READY` |
| **Depends on** | TASK-018 ✅ merged (`clientReference` is what a chain event binds to); TASK-017 ✅ merged (both widen `role_known` and the vocabulary owners; sequential by ruling D10); [ADR-0028](../ADR/0028-satellite-clients-integrate-through-core-owned-contracts.md) D4, D7, D8 |
| **Blocks** | the `ref-3` release (Sol tier, whole repository) |
| **Requirements** | ROADMAP increment 9 ("conditional release against simulated tokenised-deposit or CBDC-style rails; explicitly a simulation, and labelled as such wherever it appears"); PR-030…PR-032 (settlement finality and its evidence); ADR-0028 D7; ADR-0019 (`settled` and `returned` are terminal); the canonical disclaimer |
| **Controls touched** | C-05 (one audit term, one subject type); C-08 (one role, two permissions); the settlement receipt, replay and conflict rules (L-11, L-12, L-21) extended to a third producer **through the same path**; the disclaimer (`resources/disclaimer.txt`) kept true by construction — a chain allow-list in code and in the schema |
| **Scope** | Large — one migration, three operations, one canonical encoder with golden vectors, one UAT script |
| **Base branch** | `clofin-core` `main` after TASK-017's merge |
| **Audit** | `docs/audits/019-REQ-chain-confirmations-sim-evm.md`, task-keyed, filed on the PR branch |

## Objective

After this brief a settlement batch may name a third simulated scheme,
`SIM-EVM`, whose outcomes arrive as **chain confirmations**: a normalised
ERC-20 transfer event on a local development chain, posted by a seeded
`settlement-feed` actor, which core validates, re-digests, binds to the
instruction by its `clientReference`, converts through a synthetic token
registry without rounding, and then settles through **the same posting path
every scheme response takes** — one producer of settlement postings, one
receipt rule, one replay identity. The event's `(chain id, transaction hash,
log index)` is made durably unique per organisation in the transaction that
posts it, so the same on-chain event can never settle two instructions or two
batches. Core accepts exactly one chain id, `eip155:31337`, refuses every
public network by policy, verifies nothing on any chain, and records the
feed's provenance without calling it verified. A reversal before settlement
returns the instruction; one after settlement is refused, because `settled`
is terminal and nothing here reverses a posting. Nothing here changes what
CloFin claims to be: a synthetic-data reference implementation, connected to
nothing, approved by no one, processing no real funds — and no sentence this
brief adds, in code, contract, test or document, may describe a confirmation
as verified real-network settlement.

## Context you need

- **The ruling.** ADR-0028 D7 and its amendment under *Rulings*, D4's row for
  the chain confirmation's prior state (**`released`**, in the batch core
  resolves), D8 for the role, and *Contracts, published* for the JSON
  shapes, the digest-reconstruction table and the identity mapping. D7's
  amendment is a list of musts: durable atomic uniqueness with explicit
  organisation scope; cross-instruction **and** cross-batch conflict tests;
  one posting path; post-settlement reorg refused; the allow-list pinned and
  its widening shown to fail; provenance recorded, never verified.
- **The one door.** `clofin.settlement.service/record-scheme-response!`
  (src/clofin/settlement/service.clj from line 414): lock the batch, validate
  shape, digest the whole message, look for the receipt under the replay key
  *before* any work, replay or refuse or act, commit the receipt whatever
  happened. Read its docstring and the order it gives, then read
  `clofin.settlement.repository/record-response!` (the savepoint, the replay
  key, nulls-not-distinct). A chain confirmation is a caller of this function
  with kind `settled` or `returned` — or `timeout-resolution` when the item
  the sweep gave up on is answered late — and reference
  `eip155:<chain>/<txhash>/<logIndex>`. It is **not** a second
  implementation of it (2C-009, L-21).
- **The schemes.** `clofin.settlement.scheme/schemes` is `#{"SIM-RTGS"
  "SIM-ACH"}` with the `SIM-` prefix load-bearing (checked:
  `sed -n 35,46p src/clofin/settlement/batch.clj` — the set lives in
  `clofin.settlement.batch`, compared with `settlement_scheme_known` by the
  vocabulary test). The contract carries the enum **twice**:
  `SettlementBatch.scheme` and `generateSimulatedStatement`'s `scheme` query
  parameter (checked: `api/openapi.yaml` line ~1725 `enum: [SIM-RTGS,
  SIM-ACH]`); `clofin.contract-test` compares copies.
- **Money.** `clofin.money/scale` is the currency's registry scale (SGD 2);
  amounts are integer minor units and `parse` refuses excess scale rather
  than rounding. A token's base units convert to minor units exactly or not
  at all: `amountBaseUnits × 10^scale` must be divisible by `10^decimals`.
- **The gateway's digest, published verbatim** under *Interfaces*: the
  37-byte prefix, the type bytes, the key ordering by **encoded** key bytes,
  depth 16, the 65,536-byte ceiling, SHA-256, and three golden vectors with
  byte counts. The complete-event vector names `eip155:1` and `0N` integers:
  it is a **digest test**, never a confirmation — `eip155:1` is refused at
  the policy a step later, and the EDN `N` suffix is a spelling the digest
  must ignore.
- **Identity.** `clofin.payments.repository`'s live-membership rule
  (`settlement_item_live_key`: an instruction has at most one membership that
  is not `returned`) is how core resolves "the batch it was released in"
  from an instruction id alone.
- **Guards that discover their sets:** the conformance walk and route table;
  `clofin.contract-test`'s enum copies and the evidence-pack subject enum
  (two copies); `clofin.db.vocabulary-test/owners` (four constraints join:
  `settlement_scheme_known` already has one; `role_known` already has one;
  `token_registry_chain_known`, `chain_confirmation_chain_known`,
  `chain_confirmation_kind_known` are new — the pre-flight counted the
  vocabulary constraints on the four tables at 4, of which two already have
  owners); `clofin.ledger.purity-test`'s lists; the unit-of-work matrix
  (one new entry point on an existing service);
  `ac-15-…-eight-operations…` becomes nine.
- **What is off limits:** the control-plane documents; `docs/releases/`;
  screening (TASK-017 — merged before you branch; do not touch its
  namespaces); any RPC, HTTP client or connectivity toward a chain (the
  constraint forbids it, and `deps.edn` gains no dependency without an
  ADR — D7 of `ARCHITECTURE.md`); a `sim:` chain-id prefix (a negotiated
  schema change, refused by the operator); a compensation operation for a
  settled posting; `clofin-trace`, `clofin-cockpit`, the gateway's own code.

## Scope

### In

- **A-1 — Migration `0016-sim-evm-token-registry-and-chain-confirmations.sql`**,
  exactly the DDL under *SQL pre-flight*: `settlement_scheme_known` and
  `role_known` dropped and recreated by name with `SIM-EVM` and
  `settlement-feed`; `token_registry` with one seeded row (the one synthetic
  token — reference data in the register of `0001`'s currencies, append-only
  by trigger; a second token is a later migration); `chain_confirmation`
  with the identity key, the response key, the membership foreign key, the
  shape and bound checks, and append-only triggers. Comments on every table
  and every non-obvious column, the `chain_id` checks saying in words why
  only a local chain is representable.
- **A-2 — `clofin.canonical.edn-v1`** (pure; joins `pure-namespaces`):
  `(encode value) → bytes` and `(digest value) → lowercase hex`, exactly the
  gateway's specification in *Interfaces*: supported types only, refusing
  (as `:validation` errors naming the offending type or limit) everything the
  specification rejects — lists, sets, symbols, characters, records, tagged
  values, floats, ratios, `BigDecimal`, non-keyword map keys, depth beyond
  16, output beyond 65,536 bytes, strings with lone surrogates. Tests: the
  three golden vectors (byte count **and** hex); the 86-byte vector's exact
  bytes; insertion order and `N` spelling do not change a digest; every
  semantic change does (a property over the event: flipping any leaf changes
  the hex); each refusal.
- **A-3 — `clofin.settlement.chain-policy`** (pure):
  `allowed-chains` = `#{"eip155:31337"}`, `assert-allowed!` (a `:validation`
  error, `unsupported-chain`, naming the chain and the allowed set), and the
  owner the vocabulary test names for both `*_chain_known` constraints.
  `clofin.settlement.chain-policy-test/widening-the-allow-list-fails-this-test`
  asserts the set is **exactly** that one member and that `eip155:1`,
  `eip155:11155111`, `eip155:137` and `eip155:31338` are refused; the REQ
  shows it failing with `eip155:1` added, and the vocabulary test failing
  the same way (the schema is the second copy, compared both directions).
- **A-4 — `clofin.settlement.chain-event`** (pure): from the decoded JSON
  `ChainTransferEvent`, `(validate event)` → the event as a closed map or a
  `:validation` error naming the member (closed objects — an unknown member
  is refused; lowercase hex; `eip155:` chain ids as canonical decimals;
  `uint64` bounds on `logIndex`, `block.number`, `confirmations`;
  `amountBaseUnits` a canonical positive decimal string; `eventVersion` 1;
  `eventType` `erc20/transfer`; `finality.status` `finalized`);
  `(->edn event)` the reconstruction in the ADR's table (keywords only for
  `:event/type` and `:finality :status`; integers as exact integers; `N`
  never written); `(identity event)` → `{:chain-id :transaction-hash
  :log-index}`; `(reference identity)` → `eip155:<chain>/<txhash>/<logIndex>`
  (the chain id's decimal, the hash as given, the index in decimal);
  `(minor-units event token)` → the exact conversion or a `:validation`
  error `transfer-mismatch` with `errors.receivedBaseUnits` and the reason
  (not representable).
- **A-5 — `POST /chain-confirmations`** (`recordChainConfirmation`,
  `Idempotency-Key`, permission `:settlement/confirm`, `200`
  `ChainConfirmation`; replay `200` with `replayed: true` and
  `Idempotent-Replayed`). In `clofin.settlement.service/record-chain-confirmation!`,
  inside the handler's transaction, in this order — each step's refusal is
  rendered **after** the transaction commits when a receipt exists, and
  immediately when the request could not be understood (the `assert-shape!`
  distinction the one-door function already draws):
  1. `assert-unit-of-work!`.
  2. Pure validation: the event (A-4); `provenance.authentication` must be
     `verified` (`422 provenance-unverified` — the gateway's `unverified`
     shape "may return only integration-required"; core does not integrate
     it); `canonicalDigest` recomputed from `->edn` and compared
     (`422 digest-mismatch`, with `errors.recomputedDigest`); the chain
     against the allow-list (`422 unsupported-chain`); `kind` ∈ {`settled`,
     `returned`}.
  3. The instruction, in the caller's organisation (`404`); its status:
     `released` → continue; `settled` with kind `returned` → `409
     settled-is-terminal`; anything else → `409 instruction-not-released`
     with `errors.instruction-status`. Its live membership → the batch;
     the batch's scheme must be `SIM-EVM` (`422 scheme-mismatch`, naming
     the scheme). Lock the batch (lock-order step 1), then the instruction
     (step 2) — the same order the door takes.
  4. Binding: `event.instructionId` equals the instruction's
     `clientReference` (`422 payment-binding-mismatch`; an instruction with
     no `clientReference` cannot be bound and answers the same).
  5. Token: `token_registry` by `(chainId, tokenContract)` (`422
     unsupported-token`); the token's currency must be the instruction's and
     the exact conversion must equal the instruction's amount (`422
     transfer-mismatch`, with `errors.expectedMinorUnits`).
  6. Identity: `chain_confirmation` by `(organisation, chain id, hash, log
     index)`: found with the same digest, instruction and batch → **replay**
     (the stored scheme response's answer, `replayed: true`, no work); found
     with a different digest → `409 identity-digest-conflict` carrying
     `errors.existingCanonicalDigest`; found with the same digest and another
     instruction or batch → `409 event-bound-elsewhere` carrying
     `errors.instructionId` (the existing binding). Nothing is posted on any
     `409`, and no row is written — the receipt that stands is the one the
     identity already has.
  7. Delegate to `record-scheme-response!` with kind `settled` or `returned`
     (reason **`reorged`**, a constant — the feed sends no free text) for a
     pending item, or `timeout-resolution` with that outcome for an item the
     sweep marked timed out; reference `(reference identity)`; the actor,
     correlation id, a fresh entry id, `occurred-at`. Its result decides:
     `applied` → insert the `chain_confirmation` row (identity, instruction,
     batch, the scheme response's id, the finality entry's id, kind, the
     digest, the canonical JSON of the event, provenance, actor) **in this
     transaction**, and emit `chain-confirmation.recorded` (subject
     `chain-confirmation`, after digest over the projection); `refused`
     (e.g. `item-already-resolved`: a settled instruction receiving a
     *new* identity) → no `chain_confirmation` row; the refused receipt is
     the scheme response's (L-11), and the handler renders `409` with that
     `dispositionReason` after commit; `replayed` under the replay key with
     no identity row cannot happen (the identity row and the receipt commit
     together) — assert it as an invariant and fail closed if seen.
- **A-6 — The reads.** `GET /chain-confirmations/{id}`
  (`getChainConfirmation`) and `GET /chain-confirmations?organisationId=&instructionId=`
  (`listChainConfirmations`, capped at `row-cap` with `truncated`), both
  `:settlement/read`, both organisation-scoped (`404` across tenants).
- **A-7 — `SIM-EVM` everywhere the schemes are named.** `clofin.settlement.batch/schemes`;
  the contract's two enum copies; `clofin.settlement.scheme`'s simulated
  adapter: for `SIM-EVM`, `responses-for` returns **no entries** (the chain
  feed answers; the adapter never invents an outcome) and `submit-reference`
  is derived as today; `POST /settlement-batches/{id}/scheme-responses`
  against a `SIM-EVM` batch accepts `ack` and refuses `settled`, `returned`
  and `timeout-resolution` with `422 scheme-mismatch` — outcomes for a chain
  batch enter through `/chain-confirmations` and nowhere else, so the
  one-door rule holds in both directions; the timeout sweep applies to
  `SIM-EVM` items unchanged; `generateSimulatedStatement` for `scheme=SIM-EVM`
  reports settled and returned items from their outcomes exactly as for the
  other two (a test: a chain-settled payment appears on the simulated
  statement and reconciles under UAT-007's rules without change).
- **A-8 — `settlementBatchId` on `PaymentInstruction`** (ADR-0028's fragment):
  the live membership's batch, present from `released` onward (also on
  `settled` and `returned`); absent before. Read with the instruction (a
  scalar subquery beside `retried_by_ids`), rendered when present.
- **A-9 — The role and permissions.** `clofin.authz.model`: permissions
  `:settlement/confirm` and `:settlement/read`; role `:settlement-feed`
  holding `#{:settlement/confirm :settlement/read :payment/read
  :organisation/read}`; `:controller`, `:auditor` and `:compliance` gain
  `:settlement/read` (batch reads stay on `:payment/read`, as today). Model
  tests: **no role holds `:settlement/confirm` with `:payment/approve`** and
  **no role holds `:settlement/confirm` with `:settlement/execute`** — the
  actor who pushes a batch out is not the actor who says the chain answered;
  the feed is a system actor with one job. Roles ↔ `role_known`.
- **A-10 — Audit vocabulary.** Action `chain-confirmation.recorded`; subject
  type `chain-confirmation` (the evidence-pack enum copies: eleven → twelve,
  prose and both copies); projection `clofin.audit/chain-confirmation-subject`
  (id, organisation, instruction, batch, scheme response, journal entry,
  chain id, hash, log index, kind, digest, source id, attestation id).
  `clofin.api.audit-coverage-test`: a confirmation leaves exactly the
  events the door leaves (`payment.settled`, `journal-entry.posted`) plus
  one `chain-confirmation.recorded`; a `409` leaves none of the three.
- **A-11 — The contract and the documents.** `api/openapi.yaml`: the three
  operations; ADR-0028's `ChainTransferEvent`, `CanonicalDigest`,
  `ChainConfirmationRequest` (**plus `kind`, required, enum
  `[settled, returned]`** — the ADR's fragment lacks it and a reversal has
  no other way to say what it is; see *Notes*), `ChainConfirmation` (with
  `simulated: true` required, enum `[true]`, and `provenance` as recorded),
  `ChainConfirmationList`, `ChainConfirmationRefusalReason`; `SIM-EVM` in
  both scheme enums; `settlementBatchId` on `PaymentInstruction`;
  `IdempotencyKey`'s "eight" → "nine"; every description of this operation
  carrying the sentence *core verifies nothing on any chain; a confirmation
  is simulation provenance on a local development chain, recorded and not
  verified, and is never verified real-network settlement*. `README.md`
  line 23's list of simulated interfaces gains "a local-chain confirmation
  feed"; `ARCHITECTURE.md` §2's adapter diagram gains the box, labelled
  simulated; `docs/DOMAIN_MODEL.md`'s settlement context gains
  `ChainConfirmation` and `TokenRegistryEntry`; `docs/COMPLIANCE.md` C-06's
  scope paragraph or the settlement control's *Scope* gains the sentence
  that the chain allow-list is what keeps the disclaimer true and is pinned
  in code and schema. ADR-0028 gains **Amendment 2 (TASK-019)**: `kind` on
  the request, the `timeout-resolution` delegation, the `SIM-EVM`
  scheme-response refusal, and the refusal reasons beyond D7's list —
  dated, appended. `make diagrams` regenerates `context-topology.md`
  (the new namespaces) and `control-map.md` if COMPLIANCE changed.
- **A-12 — `docs/uat/UAT-009-chain-confirmations.md`**, in the register of
  UAT-006: seed a feed actor; raise, submit, approve and batch two
  instructions (with `clientReference`s) on `SIM-EVM`; submit the batch;
  post a `settled` confirmation for the first (`200`, the instruction
  `settled`, the entry posted, `simulated: true` read back and quoted);
  post the identical request again (`replayed: true`); the same identity
  with a changed `amountBaseUnits` (`409 identity-digest-conflict`); the
  same identity against the second instruction (`409 event-bound-elsewhere`);
  `eip155:1` (`422 unsupported-chain`); `finality.status` not `finalized`
  (`422 not-finalized`); an `amountBaseUnits` one base unit off (`422
  transfer-mismatch`); a `returned` for the settled one (`409
  settled-is-terminal`); a `returned` for the second, still released
  (`200`, `returned`, the mirror entry); a `settled` scheme response posted
  by hand against the `SIM-EVM` batch (`422 scheme-mismatch`); the simulated
  statement for `SIM-EVM` showing the settled line; the raw `UPDATE` of a
  confirmation refused; the trail. Every step traced to a requirement;
  **run against a fresh stack before the REQ is filed** (L-20);
  `docs/uat/README.md` row added.

### Out — and why

- **Any connectivity to any chain.** No RPC, no client, no dependency. Core
  records what the feed sent and looks at nothing (D7; the constraint).
- **Public chain ids, and a `sim:` prefix.** Refused by policy and by schema;
  a new prefix is a schema change both sides negotiate (the operator's
  ruling).
- **A compensation operation for a settled posting.** `settled` is terminal;
  a reversing journal entry with its own audit event is a separate ruling,
  not a side effect of this one.
- **Conditional release** (the ROADMAP's phrase for the rest of increment 9).
  This slice is confirmation only; release stays the controller's `submit`
  of a batch.
- **A second token, a second chain, or a per-organisation registry.** One
  synthetic token on one local chain; the registry is append-only and a
  later migration adds to it.
- **Pending confirmations.** An event that is not finalized is refused and
  retried by the feed; core holds nothing in flight (D7).
- **`clofin-trace`, `clofin-cockpit`, the gateway's own repository** — the
  gateway's EDN envelope and `:core/version` are the adapter's; core
  publishes JSON.

## Interfaces

**The gateway's canonical digest v1, verbatim** (from the handover; core
implements it as `clofin.canonical.edn-v1` and reconstructs the EDN from the
JSON by the ADR's table):

> Hash only the normalized event. Prefix is the 37 ASCII bytes
> `clopay.gateway/chain-confirmation`, NUL, `v1`, NUL — hex
> `636c6f7061792e676174657761792f636861696e2d636f6e6669726d6174696f6e00763100`.
> All lengths and counts are unsigned 32-bit big-endian. Strings use strict
> UTF-8 without Unicode normalization; lone surrogates are rejected.
>
> | Value | Encoding after the domain prefix |
> |---|---|
> | `nil` | byte `00` |
> | `false` | byte `01` |
> | `true` | byte `02` |
> | exact integer | byte `10`, byte length, minimal signed base-10 UTF-8 digits — zero is `0`; no plus sign, leading zero, or `N` suffix |
> | string | byte `11`, byte length, exact UTF-8 bytes |
> | keyword | byte `12`; namespace marker `00` if absent, otherwise `01` followed by namespace byte length and bytes; then name byte length and bytes |
> | vector | byte `20`, element count, recursively encoded elements in original order |
> | keyword-keyed map | byte `21`, entry count, then key/value pairs — each key encoded with the keyword rule, pairs sorted by unsigned lexicographic comparison of the **entire encoded key bytes** (type, namespace and length framing included: `:z` precedes `:aa`), key bytes then the recursively encoded value |
>
> Supported: non-record keyword-keyed maps, vectors, keywords, valid-Unicode
> strings, exact integers (every JVM integer representation is one type),
> booleans, nil. Metadata ignored. Rejected: lists/sequences, sets,
> characters, symbols, records, tagged objects, dates/UUIDs, all floating
> point, nonfinite numbers, `BigDecimal`, ratios. Maximum depth 16 with the
> root at depth zero. Maximum encoded output, prefix included, 65,536 bytes.
> SHA-256 over prefix plus value encoding; lowercase 64-hex output.

Golden vectors:

| Input | Canonical byte count | SHA-256 |
|---|---|---|
| `nil` | 38 | `70f053f38f05bbe44945ae306d3c25ceeef5d6bbb658e95f69d82a4aa0acc417` |
| `{:b [1 "1"] :a {:x true}}` | 86 | `25483a30075e471ed511062bd3e1cb75677800aaf4904f3a6a411ecb732ea507` |
| the complete event below | 747 | `e438c689af8f2226b113fda70d809b49fc10a901f247cb0e2c2cf100e070d036` |

The 86-byte vector's bytes, in full:

```text
636c6f7061792e676174657761792f636861696e2d636f6e6669726d6174696f6e0076310021000000021200000000016121000000011200000000017802120000000001622000000002100000000131110000000131
```

The complete event (the gateway's EDN; core receives its JSON and rebuilds
this exactly):

```clojure
{:event/version 1
 :event/type :erc20/transfer
 :instruction-id "payment-demo-001"
 :chain-id "eip155:1"
 :transaction {:hash "0x1111111111111111111111111111111111111111111111111111111111111111"
               :log-index 0N}
 :block {:hash "0x2222222222222222222222222222222222222222222222222222222222222222"
         :number 19000000N}
 :finality {:status :finalized :confirmations 64N}
 :transfer {:token-contract "0x3333333333333333333333333333333333333333"
            :from-address "0x4444444444444444444444444444444444444444"
            :to-address "0x5555555555555555555555555555555555555555"
            :amount-base-units "1234500"}}
```

**Request and response** (ADR-0028's fragments, with this brief's `kind`):

```yaml
ChainConfirmationRequest:
  required: [organisationId, instructionId, kind, event, canonicalDigest, provenance]
  additionalProperties: false
  properties:
    organisationId:  { type: string, format: uuid }
    instructionId:   { type: string, format: uuid }      # core's id
    kind:            { type: string, enum: [settled, returned] }
    event:           { $ref: '#/components/schemas/ChainTransferEvent' }
    canonicalDigest: { $ref: '#/components/schemas/CanonicalDigest' }
    provenance:
      required: [authentication, sourceId, attestationId]
      additionalProperties: false
      properties:
        authentication: { type: string, enum: [verified] }
        sourceId:       { type: string, minLength: 1, maxLength: 128 }
        attestationId:  { type: string, minLength: 1, maxLength: 256 }
ChainConfirmation:
  required: [id, organisationId, instructionId, settlementBatchId, schemeResponseId, journalEntryId,
             kind, eventIdentity, canonicalDigest, provenance, replayed, recordedAt, simulated]
  properties:
    kind:          { type: string, enum: [settled, returned] }
    eventIdentity:
      required: [chainId, transactionHash, logIndex]
      properties:
        chainId:         { type: string, enum: [eip155:31337] }   # the allow-list, published
        transactionHash: { type: string, pattern: '^0x[0-9a-f]{64}$' }
        logIndex:        { type: integer, minimum: 0 }
    simulated:     { type: boolean, enum: [true] }
ChainConfirmationRefusalReason:
  type: string
  enum: [unsupported-chain, unsupported-token, not-finalized, digest-mismatch,
         payment-binding-mismatch, transfer-mismatch, provenance-unverified, scheme-mismatch,
         instruction-not-released, identity-digest-conflict, event-bound-elsewhere,
         settled-is-terminal, item-already-resolved]
```

Problem documents carry `errors.reason` from that enum; `identity-digest-conflict`
adds `existingCanonicalDigest`; `event-bound-elsewhere` adds `instructionId`;
`digest-mismatch` adds `recomputedDigest`; `transfer-mismatch` adds
`expectedMinorUnits` and `receivedBaseUnits`; `instruction-not-released` adds
`instruction-status`.

**The service**, one new entry point on `clofin.settlement.service` (joins the
unit-of-work matrix):

```clojure
(defn record-chain-confirmation!
  "Returns {:confirmation … :replayed? bool} on an applied or replayed arrival,
   or {:refused? true :reason … :detail … :receipt …} for a refusal that
   committed a receipt through the door. Throws :validation for a request
   that could not be understood (nothing written), and :not-found."
  [tx {:keys [organisation-id instruction-id kind event canonical-digest provenance
              actor correlation-id entry-id occurred-at]}])
```

**The token row** seeded by the migration:

| chain_id | token_contract | symbol | currency | decimals |
|---|---|---|---|---|
| `eip155:31337` | `0x5f1d4a0c0e8b2f9a7c3d6e1b4a8c2d9e0f1a2b3c` | `SIM-SGD` | `SGD` | 6 |

Conversion, for the tests and the UAT script: SGD 1,250.00 is 125000 minor
units; at 6 decimals its base units are `1250000000`. `1250000001` is not
representable (`transfer-mismatch`); `1250010000` is SGD 1,250.01
(`transfer-mismatch`); `1250000000` matches.

## Dependency matrix

| Item | Transition or state it depends on | Interface or contract | DDL | DoD clause |
|---|---|---|---|---|
| A-1 | none | `index.txt`; both constraints recreated by name | the migration | AC-19-0 |
| A-2 | — (pure) | the gateway's specification above | — | AC-19-11 |
| A-3 | — (pure) | `unsupported-chain` | `*_chain_known` (two) | AC-19-10 |
| A-4 | — (pure) | `ChainTransferEvent`; the reconstruction table | — | AC-19-5, AC-19-6, AC-19-7 |
| A-5 | `released` with a live `SIM-EVM` membership; the door's lock order; the identity key | `recordChainConfirmation`; `:settlement/confirm` | `chain_confirmation`, `token_registry` | AC-19-1 … AC-19-9 |
| A-6 | an applied confirmation | the two reads; `:settlement/read` | — | AC-19-12 |
| A-7 | batches on `SIM-EVM`; the sweep; the statement generator | both scheme enums; `SchemeResponseRequest` | `settlement_scheme_known` | AC-19-13 |
| A-8 | the live membership | `PaymentInstruction.settlementBatchId` | — | AC-19-14 |
| A-9 | — | `clofin.authz.model` | `role_known` | AC-19-15 |
| A-10 | every applied confirmation | `AuditSubjectType` (two copies) | — | AC-19-16 |
| A-11 | all of the above | contract, conformance, diagrams | — | AC-19-17 |
| A-12 | a fresh stack | the public API | — | AC-19-18 |

Every cell is filled. A cell that turns out wrong is an objection.

## Acceptance criteria

Each names its negative control (L-17). `ac-19-1` … `ac-19-10` and the
chain-policy and canonical test names are ADR-0028's Verification identifiers;
keep them verbatim. The API tests live in
`clofin.api.chain-confirmations-api-test`.

- **AC-19-0 (A-1).** `0016` applies on `0015`; the pre-flight's refusals
  re-run through `clofin.settlement.repository-test`, application bypassed:
  a public chain at both tables, the same identity twice, a second
  confirmation on one scheme response, uppercase hex, a log index past
  `uint64`, a confirmation for an instruction not in the named batch, the
  `UPDATE`, a scheme the constraint does not know, a token symbol without
  the `SIM-` prefix. The vocabulary test green with the new owners.
- **AC-19-1 (A-5)** `ac-19-1-the-second-delivery-replays-the-first-response`:
  the identical request twice → the second is `200`, `replayed: true`,
  byte-identical body, no second posting, no second event, one
  `chain_confirmation` row. Negative control: the identical request under a
  **new** `Idempotency-Key` is still a replay — the identity row, not the
  key, is what answers (assert the key count is two and the row count one).
- **AC-19-2 (A-5)** `ac-19-2-the-replay-survives-a-new-connection-pool`:
  close the pool, open another, deliver again → replay. Negative control:
  the door's own `f-008` tests already show a receipt surviving; this one
  shows the identity row does.
- **AC-19-3 (A-5)** `ac-19-3-a-different-digest-under-one-identity-is-409-and-posts-nothing`:
  same identity, `amountBaseUnits` changed (and the digest recomputed to
  match it, so step 2 passes) → `409 identity-digest-conflict` with
  `existingCanonicalDigest`; journal entries and events unchanged; no second
  row.
- **AC-19-4 (A-5)** `ac-19-4-an-identity-already-bound-elsewhere-is-409-event-bound-elsewhere`:
  **two cases** — the same identity and digest against a second instruction
  in the same batch, and against an instruction in a second batch → `409`
  naming the bound `instructionId`; nothing posted. Negative control: drop
  `chain_confirmation_identity_key` in a scratch transaction and the second
  instruction settles — the whole finding, shown once.
- **AC-19-5 (A-4, A-5)** `ac-19-5-not-finalized-is-422`: `finality.status`
  anything but `finalized` (a plausible `safe`) → `422`; nothing written.
- **AC-19-6** `ac-19-6-a-client-reference-that-is-not-the-instructions-is-422`:
  `event.instructionId` ≠ `clientReference`, and an instruction created
  without one → `422 payment-binding-mismatch`.
- **AC-19-7** `ac-19-7-an-amount-that-is-not-the-instructions-is-422`: the
  three conversion cases above; a token contract not in the registry →
  `422 unsupported-token`; a token whose currency is not the instruction's
  (seed one in the test, in a scratch transaction) → `transfer-mismatch`.
- **AC-19-8 (A-5)** `ac-19-8-a-reversal-before-settlement-returns-the-instruction`:
  kind `returned` on a released, unanswered item → `200`, the instruction
  `returned`, the mirror entry posted, the item's `outcomeReason` `reorged`,
  `payment.returned` and `chain-confirmation.recorded` emitted; the batch
  derives as the door says.
- **AC-19-9 (A-5)** `ac-19-9-a-reversal-after-settlement-is-409-settled-is-terminal-and-mutates-nothing`:
  kind `returned` on a settled instruction → `409`; the instruction, the
  entries and the events unchanged; no row. Negative control: the same
  request against a still-released sibling → `200` (AC-19-8), so the test
  distinguishes the state from the request.
- **AC-19-10 (A-3)** `ac-19-10-eip155-1-is-422-unsupported-chain` (with the
  digest recomputed for `eip155:1`, so the policy — not the digest — is what
  answers), plus `clofin.settlement.chain-policy-test/widening-the-allow-list-fails-this-test`
  and its REQ-quoted failure with `eip155:1` added.
- **AC-19-11 (A-2)** `clofin.canonical.edn-v1-test/the-three-golden-vectors`
  (count and hex; the 86-byte vector's bytes), `…/insertion-order-and-integer-spelling-do-not-change-the-digest`
  (a map built in three insertion orders; `0N` vs `0`; `19000000N` vs
  `19000000`), `…/every-semantic-change-changes-the-digest` (property: each
  leaf of the event changed one at a time), and one test per refusal.
  Negative control: change the depth limit to 17 and the depth test fails.
- **AC-19-12 (A-6)** the two reads: `200` for the feed, the controller, the
  auditor; `403` for an operator; `404` across tenants; the list capped with
  `truncated` (reuse the pattern and its test shape from reconciliation).
- **AC-19-13 (A-7)** `clofin.api.settlement-api-test/ac-19-13-a-sim-evm-batch-takes-no-outcome-through-scheme-responses`:
  `ack` accepted; `settled`, `returned`, `timeout-resolution` → `422
  scheme-mismatch`; the sweep marks an unanswered `SIM-EVM` item timed out;
  a later chain confirmation for it delegates as `timeout-resolution` and
  resolves it once; `SIM-RTGS` behaviour unchanged (the existing tests);
  `generateSimulatedStatement` with `scheme=SIM-EVM` lists the chain-settled
  line and UAT-007's ingestion matches it (one test in
  `clofin.api.reconciliation-api-test` or the settlement statement test).
- **AC-19-14 (A-8)** `clofin.api.payments-api-test/ac-19-14-settlement-batch-id-appears-from-released-onward`:
  absent on `draft`/`pending-approval`/`approved`; present and equal to the
  batch on `released`, `settled`, `returned`.
- **AC-19-15 (A-9)** `clofin.authz.model-test`: the two new separation
  assertions; the feed actor cannot create a batch, submit one, record a
  scheme response or approve (api test, `403` each).
- **AC-19-16 (A-10)** audit coverage as A-10 says; the evidence pack for a
  confirmation id answers one `chain-confirmation.recorded`; the
  instruction's pack shows release, settlement and the confirmation in
  order.
- **AC-19-17 (A-11)** contract and conformance green with every new
  operation walked to its `200` and at least one modelled refusal;
  `ac-15-…-nine…` green; both scheme enum copies compared;
  `make diagrams-check`, `make doc-consistency`, `make docs-check`,
  `make disclaimer-check` green. Negative control for the honesty rule: a
  grep over the diff for `verified real-network`, `on-chain settlement`,
  `mainnet` and `real funds` that must match only the sentences denying
  them — quote the grep in the REQ.
- **AC-19-18 (A-12)** UAT-009 executed against a fresh stack, every step
  recorded in the REQ.

## SQL pre-flight

Executed by Master Control on 2026-10-03 against a local PostgreSQL 16 at
schema `0013` holding a settled `SIM-RTGS` batch (the capture database), in a
transaction rolled back at the end, with TASK-017's `role_known` form
assumed (the constraint text below is the final form), one row of every
documented shape inserted and eleven negative controls each in its own
savepoint (L-3, widened). The DDL below is the migration, verbatim but for
comments:

```sql
alter table settlement_batch drop constraint settlement_scheme_known;
alter table settlement_batch add constraint settlement_scheme_known
  check (scheme in ('SIM-RTGS','SIM-ACH','SIM-EVM'));

alter table actor_role drop constraint role_known;
alter table actor_role add constraint role_known
  check (role in ('operator','approver','controller','compliance','auditor','screening-service','settlement-feed'));

create table token_registry (
  chain_id       text    not null,
  token_contract text    not null,
  symbol         text    not null,
  currency       char(3) not null references currency (code),
  decimals       integer not null,
  primary key (chain_id, token_contract),
  constraint token_registry_chain_known      check (chain_id in ('eip155:31337')),
  constraint token_registry_contract_shape   check (token_contract ~ '^0x[0-9a-f]{40}$'),
  constraint token_registry_decimals_range   check (decimals between 0 and 36),
  constraint token_registry_symbol_synthetic check (symbol ~ '^SIM-[A-Z0-9]{2,10}$')
);
insert into token_registry (chain_id, token_contract, symbol, currency, decimals)
  values ('eip155:31337', '0x5f1d4a0c0e8b2f9a7c3d6e1b4a8c2d9e0f1a2b3c', 'SIM-SGD', 'SGD', 6);

create table chain_confirmation (
  id                  uuid          primary key,
  organisation_id     uuid          not null references organisation (id),
  instruction_id      uuid          not null references payment_instruction (id),
  settlement_batch_id uuid          not null references settlement_batch (id),
  scheme_response_id  uuid          not null references scheme_response (id),
  journal_entry_id    uuid          not null references journal_entry (id),
  chain_id            text          not null,
  transaction_hash    text          not null,
  log_index           numeric(20,0) not null,
  kind                text          not null,
  canonical_digest    text          not null,
  event_json          text          not null,
  source_id           text          not null,
  attestation_id      text          not null,
  recorded_by         uuid          not null references actor (id),
  recorded_at         timestamptz   not null default now(),
  constraint chain_confirmation_identity_key unique (organisation_id, chain_id, transaction_hash, log_index),
  constraint chain_confirmation_response_key unique (scheme_response_id),
  constraint chain_confirmation_membership_fk
    foreign key (settlement_batch_id, instruction_id) references settlement_batch_item (batch_id, instruction_id),
  constraint chain_confirmation_kind_known        check (kind in ('settled','returned')),
  constraint chain_confirmation_chain_known       check (chain_id in ('eip155:31337')),
  constraint chain_confirmation_tx_hash_shape     check (transaction_hash ~ '^0x[0-9a-f]{64}$'),
  constraint chain_confirmation_log_index_range   check (log_index >= 0 and log_index <= 18446744073709551615),
  constraint chain_confirmation_digest_shape      check (canonical_digest ~ '^[0-9a-f]{64}$'),
  constraint chain_confirmation_event_present     check (length(event_json) > 0),
  constraint chain_confirmation_source_shape      check (length(source_id) between 1 and 128),
  constraint chain_confirmation_attestation_shape check (length(attestation_id) between 1 and 256)
);
create index chain_confirmation_instruction_idx on chain_confirmation (instruction_id, recorded_at);
create trigger chain_confirmation_append_only before update or delete on chain_confirmation
  for each row execute function reject_mutation();
create trigger chain_confirmation_no_truncate before truncate on chain_confirmation
  for each statement execute function reject_mutation();
create trigger token_registry_append_only before update or delete on token_registry
  for each row execute function reject_mutation();
create trigger token_registry_no_truncate before truncate on token_registry
  for each statement execute function reject_mutation();
```

Rows: the token (`INSERT 0 1`); a `settlement-feed` grant on an existing
actor; a `SIM-EVM` batch (`INSERT 0 1` — the widened constraint admits it);
one confirmation bound to an applied `settled` scheme response, its batch,
its instruction and a journal entry of the same organisation, read back as
`eip155:31337 | 0x1111…1111 | 0 | settled`. Negative controls, each
refused:

```
-- a public network, at each table
ERROR:  new row for relation "token_registry" violates check constraint "token_registry_chain_known"
ERROR:  new row for relation "chain_confirmation" violates check constraint "chain_confirmation_chain_known"
-- the same identity twice in one organisation (a different scheme response)
ERROR:  duplicate key value violates unique constraint "chain_confirmation_identity_key"
DETAIL:  Key (organisation_id, chain_id, transaction_hash, log_index)=(…, eip155:31337, 0x1111…, 0) already exists.
-- a second confirmation on one scheme response
ERROR:  duplicate key value violates unique constraint "chain_confirmation_response_key"
-- uppercase hex
ERROR:  … violates check constraint "chain_confirmation_tx_hash_shape"
-- a log index of 18446744073709551616
ERROR:  … violates check constraint "chain_confirmation_log_index_range"
-- a confirmation naming a batch the instruction is not a member of (run in isolation, with a fresh response id)
ERROR:  insert or update on table "chain_confirmation" violates foreign key constraint "chain_confirmation_membership_fk"
DETAIL:  Key (settlement_batch_id, instruction_id)=(0000dddd-…, 95ffaa1c-…) is not present in table "settlement_batch_item".
-- rewriting a confirmation
ERROR:  Table chain_confirmation is append-only: … never by update
-- a scheme the constraint does not know ('RTGS')
ERROR:  … violates check constraint "settlement_scheme_known"
-- a token symbol that could be mistaken for a real one ('USDC')
ERROR:  … violates check constraint "token_registry_symbol_synthetic"
```

And the scope proof: the identity key's definition read back from the
catalogue is `UNIQUE (organisation_id, chain_id, transaction_hash,
log_index)` — the organisation is part of it, as D7's amendment requires.
The scripts are [`preflight/0016-task-019.sql`](preflight/0016-task-019.sql) and
[`preflight/0016-task-019-membership-control.sql`](preflight/0016-task-019-membership-control.sql)
(the membership control was first masked by the response
key and re-run in isolation, which is why it is listed with that note); the
REQ re-runs every refusal through `clofin.settlement.repository-test`.

## Definition of done

- [ ] Every AC above has a named test or recorded run; every negative control
      seen failing and quoted in the REQ
- [ ] `make verify` and `make test-it` green with counts; `make diagrams`
      run and `make diagrams-check` green; `make doc-consistency`,
      `make docs-check` and `make disclaimer-check` green
- [ ] `deps.edn` unchanged (no new dependency; `git diff --stat
      origin/main -- deps.edn` empty — quote it)
- [ ] The honesty grep from AC-19-17 quoted in the REQ
- [ ] `api/openapi.yaml`: three operations, the schemas named, `kind`,
      `SIM-EVM` in both copies, `settlementBatchId`, "nine" where "eight"
      was, the denial sentence on the operation
- [ ] `README.md`, `ARCHITECTURE.md` §2, `docs/DOMAIN_MODEL.md`,
      `docs/COMPLIANCE.md` updated as A-11 says; ADR-0028 Amendment 2
      appended, dated
- [ ] UAT-009 written, listed in `docs/uat/README.md`, executed against a
      fresh stack with every step recorded in the REQ
- [ ] `019-REQ-chain-confirmations-sim-evm.md` on the PR branch: provenance
      header (model may be a deliberate, stated absence; effort and date
      recorded); the finding-by-criterion table; the refusal order of A-5
      stated as implemented; objections; the L-9 statement in plain words
- [ ] One PR, `TASK-019: …`, against `main` after TASK-017; merged by Master
      Control after the objections are ruled and CI is green on the branch

## Notes for whoever picks this up

- **One door.** If you find yourself writing a second function that posts a
  finality entry, stop. The chain confirmation is a caller of
  `record-scheme-response!`; the identity row is what it adds, in the same
  transaction, after the door has answered `applied`. 2C-009 was exactly a
  second producer.
- **Recompute, never trust.** The client's `canonicalDigest` is compared
  with core's own encoding of the reconstructed EDN. A test that passes the
  client's hex through unchecked is the finding in waiting.
- **The `N` is a spelling.** `0N` and `0` are one integer to the digest;
  JSON carries neither suffix. Do not write `N` into `event_json` or into
  any canonical form.
- **`kind` is the brief's addition, and the ADR says why.** D7 names a
  reversal as kind `returned` with reason `reorged` but publishes a request
  shape without a discriminator; the gateway's event schema has no reversal
  type. The required `kind` member is the smallest honest fix; record it as
  Amendment 2, dated, and do not invent a second event type.
- **Reason `reorged` is core's constant.** The feed sends no free text; a
  return reason that a system actor could phrase would be evidence written
  by the thing being evidenced.
- **The organisation is the simulation scope.** The identity key includes
  it on purpose; two tenants on one local chain may see one event each.
  Do not "fix" that into a global key.
- **The feed holds one permission that writes.** If a test needs the feed
  to create a batch, it is using the wrong actor; seed a controller for that
  and a feed actor for confirmations, and keep the model test that says
  they are not the same role.
- **Public-network refusal is two copies, compared.** The code set and the
  schema constraint are both `eip155:31337`; the vocabulary test is what
  keeps them equal. Widening one without the other fails the build, which is
  the point.
- **L-16 copies:** the two scheme enums; the two `AuditSubjectType` copies;
  "eight" in the `IdempotencyKey` description and in `ac-15`'s name;
  `README.md` line 23's list; `ARCHITECTURE.md` §2's diagram and §3's
  dependency paragraph if a namespace crosses a context; the UAT README row.
- **The ROADMAP is not yours.** Increment 9's heading is updated by Master
  Control on `meta` when this brief closes.
- **L-9.** If a self-review is still running when you write the REQ, say so
  in the REQ and do not call the work complete until it is not.
