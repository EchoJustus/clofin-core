# TASK-018: `clientReference`, `creditorCountry` and the idempotency-key lookup — the three additions a client needs to bind its own record to core's

| Field | Value |
|---|---|
| **Increment** | 3 (completion) — the first brief of the ADR-0028 batch, dispatched before 017 and 019 by ruling D10 |
| **Status** | `CLOSED` — merged in PR #42 (`6536ef5`) on 2026-10-04, by the operator before the rulings (the second such merge; the register says so); verified on `main` by Master Control; eight objections ruled below |
| **Depends on** | [ADR-0028](../ADR/0028-satellite-clients-integrate-through-core-owned-contracts.md) ✅ accepted and merged (`c4e1689`); nothing else |
| **Blocks** | TASK-017 (screening rules name `creditor-country`), TASK-019 (a chain event is bound to the instruction's `clientReference`) |
| **Requirements** | PR-001…PR-005, PR-040…PR-044; ADR-0028 D6; ADR-0013 (the canonical request digest); ADR-0024 (a link set at creation never changes) |
| **Controls touched** | C-06 — the key row gains the operation it was bound by, and a read of the key table is published with a stated proof; C-05 — two fields join the audited instruction projection |
| **Scope** | Medium — one migration, one new operation, two new members, one race test |
| **Base branch** | `clofin-core` `main` at `c4e1689` or later |
| **Audit** | `docs/audits/018-REQ-client-reference-and-idempotency-lookup.md`, task-keyed, filed on the PR branch |

## Objective

After this brief a client that keeps its own identifier for a payment can send
it as `clientReference` and core will hold exactly one instruction for it per
organisation — the same reference with the same content under a new key
returns the existing instruction's identity rather than creating a second one,
and the same reference with different content is refused by name. A
screening rule can name the beneficiary's country because `creditorCountry`
exists on the instruction. And a client that lost the answer to a creation can
ask `GET /payment-instructions/by-idempotency-key/{key}` what its key is bound
to, with the contract saying precisely what a `404` proves: that no committed
binding existed at the instant of that read, and nothing about a creation
still in flight. Nothing here changes what CloFin claims to be: a
synthetic-data reference implementation, connected to nothing, approved by no
one, processing no real funds.

## Context you need

- **The ruling.** ADR-0028 D6 is the decision this brief implements, and its
  *Contracts, published* section carries the OpenAPI fragments to add. Read D6
  and the D6 amendment in *Rulings* before writing a line: the three
  `clientReference` rules, the proof a `404` carries, and "the client keeps its
  original key" are the operator's words, not suggestions.
- **Where creation lives today.** `clofin.api.payments/create` reads the body,
  runs `instruction/field-errors` once over every rule so every failed field is
  named, and performs the insert inside `clofin.idempotency.repository/execute-once!`
  — the key row is claimed *before* the effect and completed in the same
  transaction, and an effect that throws takes the key row down with it.
  `clofin.payments.repository/create-instruction!` takes the lock order the
  namespace docstring fixes (link target, then debtor account) and maps a
  foreign-key violation to `422`. `clofin.payments.instruction/amendable-fields`
  is what makes a `PATCH` naming `retriesId` a `422`, and migration `0013`'s
  `payment_instruction_retry_link_immutable` trigger is the second enforcement
  of the same rule, in the schema. Copy both halves for `clientReference`.
- **The audited projection.** `clofin.audit/instruction-fields` is the set of
  instruction fields a digest covers; `select-keys` omits an absent field, so
  adding `:client-reference` and `:creditor-country` leaves the digest of an
  instruction without them unchanged, and no canonicalisation-version bump is
  needed. A field left out of the projection is a field an alteration could
  move without the trail noticing (ADR-0024's reasoning for `:retries-id`).
- **What the key table knows today.** `idempotency_key` holds the digest, the
  status and the stored body, and nothing about *which operation* bound the
  key — checked: `grep -n "operation_id" resources/migrations/*.sql` matches
  nothing, and `clofin.idempotency.repository/execute-once!` inserts
  `(organisation_id, key, request_digest, response_status, response_body)`.
  The lookup needs that fact, because a key bound to a submission also stores
  a `PaymentInstruction` body and the lookup must never describe it as a
  creation.
- **The six callers of `execute-once!`**, each through a private
  `idempotently`: `clofin.api.payments` (`create`, `amend`, `submit`,
  `cancel` — the last two through `transition-handler`) and
  `clofin.api.approvals` (`decide`, `withdraw`). Checked:
  `grep -rn "execute-once!" src/clofin/api/` finds the two wrappers.
- **The existing race test** is
  `clofin.api.payments-api-test/ac-9-two-concurrent-requests-with-one-key-produce-exactly-one-effect`:
  two threads, one `CountDownLatch`, one key; it asserts one instruction, one
  key row, one `Idempotent-Replayed`. It is the shape to copy for AC-18-3, with
  the latch moved *inside* the winner's transaction. **ADR-0028's Verification
  table names this test as `one-key-two-concurrent-submissions-one-effect-one-replay`,
  which does not exist** (checked: `grep -rn "one-key-two-concurrent" test/`
  matches nothing) — correct that row of the ADR in your PR, with a dated
  parenthesis, rather than inventing a test to match the name.
- **Guards that discover their sets** and will fail until extended: the
  conformance walk (`clofin.api.conformance-test/every-operation-in-the-route-table-is-exercised`
  compares the operations walked with `clofin.routes/routes` in both
  directions), `clofin.contract-test` (route ↔ contract identity; enum copies),
  `clofin.db.vocabulary-test` (every `= ANY (ARRAY[…])` check constraint must
  have an owner in code — the two shape constraints below are regex checks,
  not vocabularies, and are not discovered by it; say so in the REQ rather
  than adding owners for them).
- **What is off limits:** the control-plane documents (`docs/ROADMAP.md`,
  `docs/briefs/**`, `docs/audits/README.md`, `docs/AGENT_HANDOFF.md`,
  `docs/audits/RELEASE-AUDIT-CHARTER.md`); `docs/releases/`; screening
  (TASK-017) and chain confirmations (TASK-019) — a need you find there is an
  objection, not a start; `clofin-trace` and `clofin-cockpit`.

## Scope

### In

- **A-1 — Migration `0014-client-reference-and-idempotency-lookup.sql`**, the
  next entry in `resources/migrations/index.txt` (checked: the last entry is
  `0013-linked-retries-and-adjustment-rejection.sql`). Exactly the DDL under
  *SQL pre-flight*: two nullable columns on `payment_instruction` with shape
  checks, a partial unique index on `(organisation_id, client_reference)`, an
  immutability trigger on `client_reference` in `0013`'s pattern, and a
  nullable `operation_id` on `idempotency_key` with the comment given there.
  Comments on every new column, in the register of the existing ones.
- **A-2 — `clientReference` on creation.** Optional member of
  `CreatePaymentInstructionRequest`; printable ASCII, 1–128, no spaces
  (`^[\x21-\x7E]+$`), validated in `clofin.payments.instruction/field-errors`
  beside the other rules so a bad reference is named with every other failed
  field; stored, rendered on `PaymentInstruction` when present, added to
  `clofin.audit/instruction-fields`. **Immutable**: absent from
  `amendable-fields` (a `PATCH` naming it is `422` naming the member, exactly
  as `retriesId`), and refused by the trigger from any writer — the negative
  control is the raw `UPDATE`, shown refused. The three rules, within one
  organisation:
  1. same reference, **same key**, identical content — the ordinary replay
     (`201` + `Idempotent-Replayed: true`), which `execute-once!` already
     gives; nothing to build, one test to add;
  2. same reference, **any** key, different content — `409`,
     `errors.reason: client-reference-conflict`, `errors.instructionId` the
     existing instruction; nothing persisted, the key not consumed;
  3. same reference, identical content, **different** key — `409`,
     `errors.reason: client-reference-exists`, `errors.instructionId` the
     existing instruction; nothing persisted, the key not consumed.

  "Identical content" is equality of the creation members — `debtorAccountId`,
  `creditorName`, `creditorAccount`, `creditorCountry`, `amount`, `valueDate`,
  `purposeCode`, `reversesId`, `retriesId` — between the request and the
  existing instruction **as it is now**, decided by a pure function
  `clofin.payments.instruction/same-content?`. An existing instruction that
  was amended after creation therefore answers rule 2; that is correct — the
  reference names content the client no longer holds, and the client is told
  which instruction to read.

  Under concurrency the partial unique index is the arbiter: two creations
  carrying one reference under two keys race on it, the loser's insert fails,
  its transaction — key row included — rolls back, and **the loser decides
  between rules 2 and 3 by reading the winner's committed row after its own
  rollback**, never inside the aborted transaction. Core never creates a
  second instruction for a reference it holds, and a test proves it with two
  threads (AC-18-5). The same reference in another organisation is a
  different reference (the index is organisation-scoped) — one test.
- **A-3 — `creditorCountry`.** Optional member of the create and amend
  requests and of the resource; **syntax only**, `^[A-Z]{2}$`, no membership
  check against any country list (ADR-0028: "ISO 3166-1 alpha-2, syntax
  only"); amendable while `draft` like the other beneficiary fields; in the
  audited projection; written by `create-instruction!` and `amend!`.
- **A-4 — The key row records its operation.** `execute-once!` gains a
  required `:operation-id` (a non-blank string) written on the claiming
  insert; the six callers pass the route's `operationId` literal
  (`createPaymentInstruction`, `amendPaymentInstruction`,
  `submitPaymentInstruction`, `cancelPaymentInstruction`,
  `approvePaymentInstruction`, `withdrawApproval`). Non-vacuity, in the
  conformance namespace: after the walk, every row of `idempotency_key` has a
  non-null `operation_id` that is an `operationId` in `clofin.routes/routes`,
  and the set of distinct values is exactly the six operations the contract
  marks as taking the header — the same six
  `ac-15-the-key-is-required-by-exactly-the-six-operations-that-say-so`
  already discovers.
- **A-5 — The lookup.** `GET /payment-instructions/by-idempotency-key/{key}`,
  `operationId: lookupPaymentInstructionByIdempotencyKey`, permission
  `:payment/read`, organisation from the principal (the optional
  `organisationId` query parameter is verified against it and refused `403`
  when it differs, as every read does). Reads `idempotency_key` by
  `(organisation_id, key)` — the row the write path binds, no cache — and:
  - no row → `404`, `errors.reason: no-binding`, with the contract sentence in
    the problem's `detail`: *no committed binding existed at the instant of
    this read; a creation still in flight may still commit — keep the original
    key, read again before deciding, and resubmit, if at all, under the same
    key*;
  - a row whose `operation_id` is `createPaymentInstruction` → `200`
    `IdempotencyKeyLookup` (ADR-0028's fragment): `idempotencyKey`,
    `instructionId` (from the stored body's `id`), `boundAt` (`created_at`),
    `originalStatus` (`response_status`), `originalBody` (the stored body,
    decoded, byte-identical in content to what a replay serves),
    `currentStatus` (the instruction's status now, read in the same request);
  - a row whose `operation_id` is any other value **or null** → `409`,
    `errors.reason: key-bound-to-another-operation`, `errors.operationId` the
    stored value or `null`. Null is a row written before `0014` and is treated
    as "bound to an operation this lookup does not serve", never as absent —
    a `404` here would tell a client to resubmit under a key that is taken.

  The key is a path segment: `clofin.idempotency/read-key`'s rules apply (any
  non-blank string without control characters, ≤ 255); a key the reader
  refuses is `400`.
- **A-6 — The race, as a named test**, plus the other names ADR-0028's
  Verification table commits this brief to (*Acceptance criteria*).
- **A-7 — The contract and the documents.** `api/openapi.yaml`: the two
  members on `CreatePaymentInstructionRequest`, `PaymentInstruction` and
  (`creditorCountry` only) `AmendPaymentInstructionRequest`; the new
  operation and `IdempotencyKeyLookup`; a `ClientReferenceRefusalReason` enum
  (`client-reference-conflict`, `client-reference-exists`) and an
  `IdempotencyKeyLookupRefusalReason` enum (`no-binding`,
  `key-bound-to-another-operation`) so the contract test discovers them; the
  create operation's `409` description naming both reference reasons beside
  the existing key conflict. `docs/DOMAIN_MODEL.md` §1's `PaymentInstruction`
  table gains the two rows. `docs/COMPLIANCE.md` C-06 gains one paragraph on
  the lookup: what it reads and what its `404` proves, in the ADR's words.
  ADR-0028's Verification row for "Concurrent equivalent submissions"
  corrected as described under *Context*.

### Out — and why

- **Defaults for `valueDate` and `purposeCode`, and decimal-to-minor-unit
  conversion.** The operator ruled both into `clofin-agent`'s action-specific
  contract (D6 amendment); core refuses `minorUnits` outside its schema and
  rounds nothing, as today.
- **A country list.** `creditorCountry` is syntax only. A membership check
  would make a synthetic field look like a real-world validation and would
  need a maintained list; neither is asked for.
- **Retention of idempotency keys.** The existing named debt; the lookup
  reads whatever rows exist.
- **Screening** (TASK-017) and **chain confirmations** (TASK-019). This brief
  gives them the two fields they bind to and nothing else.
- **A `clientReference` lookup endpoint.** The reference is resolved by the
  `409` problem documents naming `instructionId`; a client that holds a
  reference and no key is told the id on its next attempt, and the list
  endpoint is unchanged.
- **`clofin-trace`, `clofin-cockpit`** and the satellite adapters.

## Interfaces

**A-1 — the migration** (as pre-flighted below; comments elided here, not in
the file):

```sql
alter table payment_instruction
  add column client_reference text    null,
  add column creditor_country char(2) null;

alter table payment_instruction
  add constraint payment_client_reference_shape
    check (client_reference is null or client_reference ~ '^[\x21-\x7E]{1,128}$'),
  add constraint payment_creditor_country_shape
    check (creditor_country is null or creditor_country ~ '^[A-Z]{2}$');

create unique index payment_instruction_client_reference_key
  on payment_instruction (organisation_id, client_reference)
  where client_reference is not null;

create function reject_client_reference_change() returns trigger as $$
begin
  raise exception
    'payment_instruction.client_reference is set when the instruction is created and never changes: '
    'it is the identity a client binds its own record to (ADR-0028 D6), and an identity that can be '
    'rewritten is one an investigation cannot follow'
    using errcode = 'integrity_constraint_violation';
end;
$$ language plpgsql;

create trigger payment_instruction_client_reference_immutable
  before update of client_reference on payment_instruction
  for each row
  when (new.client_reference is distinct from old.client_reference)
  execute function reject_client_reference_change();

alter table idempotency_key
  add column operation_id text null;

comment on column idempotency_key.operation_id is
  'The OpenAPI operationId the key was bound by, written on the same insert as the key. '
  'Null only on a row written before migration 0014; the lookup treats null as an '
  'operation it does not serve, never as an absent binding (ADR-0028 D6).';
```

Note the trigger refuses null → value as well as value → value: a reference
cannot be *added* after creation either.

**A-2 / A-3 — the domain.** In `clofin.payments.instruction`:

```clojure
(def client-reference-pattern #"[\x21-\x7E]{1,128}")
(def creditor-country-pattern #"[A-Z]{2}")
;; field-errors gains two rules; nil is permitted for both (optional members)
;; amendable-fields gains :creditor-country and NOT :client-reference
(defn same-content?
  "True when `candidate` (a validated creation) and `existing` (an instruction as
   stored) agree on every creation member: debtor account, creditor name,
   account and country, amount, value date, purpose code, reverses-id and
   retries-id. Status, identity, provenance and timestamps are not compared."
  [candidate existing] …)
```

The domain map gains `:client-reference` and `:creditor-country` (both may be
nil); `clofin.api.payments/readers` gains `{:key :client-reference :wire
"clientReference"}` and `{:key :creditor-country :wire "creditorCountry"}`;
`clofin.api.wire/instruction->wire` renders each **only when present** (the
shape `reversesId` takes). In `clofin.payments.repository`: `instruction-columns`,
`insert!`, `row->instruction` and `amend!`'s `UPDATE` carry the columns;
`create-instruction!` resolves the reference rules — the pre-check by
`find-by-client-reference` (no lock; it decides the common case cheaply) and
the unique-violation catch on `payment_instruction_client_reference_key` for
the race, which throws a `:conflict` carrying `{:client-reference …}` that the
handler resolves to rule 2 or 3 **after** `execute-once!` has rolled back, by
reading the committed row.

**A-4 — the key store.** `execute-once!`'s options map gains `:operation-id`
(required; a nil or blank value is a `:validation` error raised before any
write — a key bound without its operation would be a row the lookup can only
refuse). The claiming insert becomes
`(organisation_id, key, request_digest, response_status, response_body, operation_id)`.

**A-5 — the lookup**, in `clofin.api.payments`:

```clojure
(defn lookup-by-key
  "`GET /payment-instructions/by-idempotency-key/:key`"
  [pool] (fn [request] …))
```

Route (in the payments section of `clofin.routes/routes`, before
`/payment-instructions/:id` so the literal segment is matched first — the
router matches literals before parameters; add a router test if you find it
does not):

```clojure
{:method :get :path "/payment-instructions/by-idempotency-key/:key"
 :operation-id "lookupPaymentInstructionByIdempotencyKey"
 :handler (payments/lookup-by-key pool)
 :summary "What an idempotency key is bound to, and the instruction's status now"}
```

Response (ADR-0028's fragment, verbatim):

```yaml
IdempotencyKeyLookup:
  required: [idempotencyKey, instructionId, boundAt, originalStatus, originalBody, currentStatus]
  properties:
    idempotencyKey: { type: string, minLength: 1, maxLength: 255 }
    instructionId:  { type: string, format: uuid }
    boundAt:        { type: string, format: date-time }
    originalStatus: { type: integer }
    originalBody:   { $ref: '#/components/schemas/PaymentInstruction' }
    currentStatus:  { $ref: '#/components/schemas/PaymentStatus' }
```

(ADR-0028's fragment gives `idempotencyKey` as `format: uuid`; the key the
service accepts is any non-blank string ≤ 255 — publish what the service
accepts and note the correction in the ADR row you already touch.)

Problem documents:

| Case | Status | `errors` |
|---|---|---|
| rule 2 | `409` | `{"reason": "client-reference-conflict", "instructionId": "<existing>"}` |
| rule 3 | `409` | `{"reason": "client-reference-exists", "instructionId": "<existing>"}` |
| lookup, no row | `404` | `{"reason": "no-binding"}` + the `detail` sentence above |
| lookup, other operation | `409` | `{"reason": "key-bound-to-another-operation", "operationId": "<value or null>"}` |
| `PATCH` naming `clientReference` | `422` | `{"clientReference": "is set when the instruction is created and cannot be amended"}` |

## Dependency matrix

| Item | Transition or state it depends on | Interface or contract | DDL | DoD clause |
|---|---|---|---|---|
| A-1 | none — additive columns; existing rows stay valid (both nullable) | `index.txt` order; `clofin.db.migrate` checksum rule | the migration above | AC-18-0 |
| A-2 | `draft` creation; the `execute-once!` rollback-on-throw contract; the partial unique index | `CreatePaymentInstructionRequest`, `PaymentInstruction`, `ClientReferenceRefusalReason`; `instruction-fields` | `payment_instruction_client_reference_key`, the trigger | AC-18-4, AC-18-5, AC-18-7 |
| A-3 | `draft` amend path (`mutable-states`) | the three schemas | `payment_creditor_country_shape` | AC-18-8 |
| A-4 | `execute-once!`'s claim-then-complete order | the six route `operationId`s | `idempotency_key.operation_id` | AC-18-9 |
| A-5 | A-4 (the row says which operation); the instruction's current row | `IdempotencyKeyLookup`, `IdempotencyKeyLookupRefusalReason`; `:payment/read` | none beyond A-1 | AC-18-1, AC-18-2, AC-18-6 |
| A-6 | A-5 and `execute-once!`'s transaction boundary | the test harness's two-connection pattern | none | AC-18-3 |
| A-7 | all of the above | `clofin.contract-test`, `clofin.api.conformance-test` | none | AC-18-10 |

Every cell is filled. A cell that turns out wrong is an objection.

## Acceptance criteria

Each names its negative control (L-17). Test names are the executable
identifiers ADR-0028's Verification table commits to; where this brief adds a
name the ADR does not have, the ADR row is unaffected.

- **AC-18-0 (A-1).** `clojure -M -m clofin.db.migrate` applies `0014` on a
  database at `0013`; `clofin.db.migrate-test` sees fourteen entries;
  `clofin.db.vocabulary-test/a-014-every-schema-vocabulary-has-an-owner-in-code`
  still passes (the two regex checks are not vocabularies — state this in the
  REQ with the query that shows the constraint set unchanged). Negative
  control: the pre-flight's refusals below, re-run on the migrated test
  database by `clofin.payments.repository-test` — a reference with a space,
  one of 129 characters, a lowercase country, and the raw `UPDATE` of a
  reference — each refused by the schema with the application bypassed.
- **AC-18-1 (A-5)** `clofin.api.payments-api-test/ac-18-1-the-lookup-returns-the-stored-response-and-current-status`:
  create, submit, then look the creation key up — `originalStatus` 201,
  `originalBody` equal to the creation response body, `currentStatus`
  `pending-approval`, `instructionId` the instruction. Negative control: the
  same key from another organisation's actor is `404` (`no-binding`) — the
  scope is the key table's primary key.
- **AC-18-2 (A-5)** `…/ac-18-2-the-lookup-answers-404-only-when-no-key-is-bound`:
  an unused key is `404` with `errors.reason: no-binding` and the `detail`
  sentence; a key bound by a submission is `409`
  `key-bound-to-another-operation` with `errors.operationId:
  "submitPaymentInstruction"`; a key row with null `operation_id` (inserted
  raw by the test) is `409` with `errors.operationId: null`. Negative control:
  change the handler to answer `404` for the null case and the third assertion
  fails.
- **AC-18-3 (A-6)** `…/ac-18-3-a-404-during-an-in-flight-creation-becomes-200-on-the-same-key-with-one-instruction`:
  thread A calls `execute-once!` directly with the creation effect and a
  `CountDownLatch` **inside the effect**, after the instruction row is
  inserted and before the effect returns — so the key row is claimed and the
  transaction is open; the main thread issues the HTTP lookup on a second
  connection and asserts `404 no-binding`; the latch is released; after A
  commits the same lookup is `200` naming one instruction; exactly one
  `payment_instruction` and one `idempotency_key` row exist; and a creation
  resubmitted under the **same** key after the `404` is a replay, not a second
  instruction. Negative control: remove the latch's `await` and the `404`
  assertion races — the REQ shows the test is not vacuous by asserting the
  lookup ran while the transaction was open (a `pg_stat_activity` read, or the
  latch's count).
- **AC-18-4 (A-2)** `…/ac-18-4-a-reused-client-reference-with-different-content-is-409-and-creates-nothing`:
  rule 2 under a new key and under the original key (the original key with a
  different body is the existing key conflict — assert which `409` wins, and
  publish the order: the key digest is compared first, because the key row is
  claimed first). Count of instructions unchanged; the new key is not
  consumed (a corrected request under it succeeds).
- **AC-18-5 (A-2)** `…/ac-18-5-a-reused-client-reference-under-a-new-key-answers-409-naming-the-existing-instruction`:
  rule 3 sequentially, **and** with two threads and two keys racing on one
  reference: exactly one instruction, one `201`, one `409
  client-reference-exists` naming it, and one key row. Negative control: drop
  the partial unique index in a scratch transaction and the race test's "one
  instruction" assertion fails.
- **AC-18-6 (A-5)** `…/ac-18-6-the-lookup-requires-payment-read-and-is-scoped-to-the-callers-organisation`:
  no actor `401`; an actor without `:payment/read` (seed one with no roles)
  `403`; `organisationId` naming another organisation `403`.
- **AC-18-7 (A-2)** `…/ac-18-7-a-client-reference-is-immutable-through-the-api-and-behind-it`:
  `PATCH` naming it is `422` naming the member; the raw `UPDATE` is refused by
  the trigger (`clofin.payments.repository-test`, application bypassed). The
  same reference in a second organisation creates a second instruction.
- **AC-18-8 (A-3)** `…/ac-18-8-creditor-country-is-syntax-only-and-amendable`:
  `SG` accepted, `sg`, `SGP`, `S1` refused and named with every other failed
  field (extend `ac-2-three-invalid-fields-are-all-named` or add a case); an
  amend changes it; the audit after-digest changes when it changes
  (`clofin.audit-test` or the api test — one assertion that the projection
  includes it).
- **AC-18-9 (A-4)** `clofin.api.conformance-test/every-bound-key-names-the-operation-that-bound-it`:
  after the walk, every `idempotency_key` row has a non-null `operation_id` in
  `clofin.routes/routes`, and the distinct set equals the six operations the
  contract marks (reuse `operations-the-contract-marks`). Negative control:
  pass `:operation-id nil` from one caller and `execute-once!` refuses before
  writing (unit test in `clofin.idempotency.repository`'s test namespace —
  add one if none exists; `clofin.idempotency-test` is the pure half).
- **AC-18-10 (A-7).** `clofin.contract-test` green with the new operation,
  the two enums and the two members; `clofin.api.conformance-test` walks
  `lookupPaymentInstructionByIdempotencyKey` to its `200` and its `404`/`409`
  (every status declared, every required member present); `make verify` and
  `make test-it` green with counts in the REQ. Extend
  `ac-9-two-concurrent-requests-with-one-key-produce-exactly-one-effect` to
  send a `clientReference` and assert one row carries it.

## SQL pre-flight

Executed by Master Control on 2026-10-03 against a local PostgreSQL 16 at
schema `0013`, in a transaction that was rolled back, with one row of every
documented shape inserted (L-3, widened by 015-REQ O-2). The DDL is the
*Interfaces* block above. Output, abridged to the lines that carry
information:

```
BEGIN
ALTER TABLE
ALTER TABLE
CREATE INDEX
CREATE FUNCTION
CREATE TRIGGER
ALTER TABLE
COMMENT
INSERT 0 1        -- client_reference 'agent-ref-0001', creditor_country 'SG'
INSERT 0 1        -- neither member: both columns null, row valid
                  id                  | client_reference | creditor_country
--------------------------------------+------------------+------------------
 0000aaaa-0000-4000-8000-00000000a018 | agent-ref-0001   | SG
 0000aaaa-0000-4000-8000-00000000b018 |                  |
```

Negative controls, each in its own savepoint, each refused:

```
-- the same reference again in the same organisation
ERROR:  duplicate key value violates unique constraint "payment_instruction_client_reference_key"
DETAIL:  Key (organisation_id, client_reference)=(f4fad1da-…, agent-ref-0001) already exists.
-- update payment_instruction set client_reference = 'agent-ref-0002' …
ERROR:  payment_instruction.client_reference is set when the instruction is created and never changes: …
-- update … set client_reference = 'has space' where client_reference is null   (null → value is a change too)
ERROR:  payment_instruction.client_reference is set when the instruction is created and never changes: …
-- insert … client_reference = 'has space'
ERROR:  new row for relation "payment_instruction" violates check constraint "payment_client_reference_shape"
-- insert … client_reference = repeat('x', 129)
ERROR:  new row for relation "payment_instruction" violates check constraint "payment_client_reference_shape"
-- update … set creditor_country = 'sg'
ERROR:  new row for relation "payment_instruction" violates check constraint "payment_creditor_country_shape"
```

And the positive controls that bound the rules: a second organisation inserted
in the same transaction accepted `agent-ref-0001` beside the first (the index
is organisation-scoped); `insert into idempotency_key (…, operation_id) values
(…, 'createPaymentInstruction')` → `INSERT 0 1`, read back as
`preflight-key-018 | createPaymentInstruction`. The scripts are
[`preflight/0014-task-018.sql`](preflight/0014-task-018.sql) and
[`preflight/0014-task-018-negative-controls.sql`](preflight/0014-task-018-negative-controls.sql);
the REQ re-runs
every refusal above through `clofin.payments.repository-test` on the migrated
test database.

## Definition of done

- [ ] Every AC above has a named test; every negative control was seen
      failing and the REQ quotes it
- [ ] `make verify` and `make test-it` green with counts; `make diagrams-check`
      green (no diagram source changes are expected — say so if none)
- [ ] `clojure -M -m clofin.db.migrate status` on the CI job reports `0014`
      applied (the job prints it)
- [ ] `api/openapi.yaml` carries the operation, the three member additions and
      the two enums; the contract and conformance namespaces discover them
- [ ] `docs/DOMAIN_MODEL.md` §1 and `docs/COMPLIANCE.md` C-06 updated as A-7
      says; ADR-0028's Verification row and the `idempotencyKey` format
      corrected with dated parentheses; no other ADR text changed
- [ ] `018-REQ-client-reference-and-idempotency-lookup.md` on the PR branch:
      provenance header (model may be a deliberate, stated absence; effort and
      date recorded); the finding-by-criterion table; the `409`-ordering
      decision from AC-18-4 stated; objections; the L-9 statement in plain
      words
- [ ] One PR, `TASK-018: …`, against `main`; merged by Master Control after
      the objections are ruled and CI is green on the branch

## Notes for whoever picks this up

- **Decide the race's answer outside the aborted transaction.** When the
  unique index refuses the loser's insert, PostgreSQL has aborted that
  transaction; a `SELECT` on the same connection fails. `execute-once!`
  already rolls the whole thing back for you when the effect throws — read
  the winner's row *after* that, on the pool, the way `execute-once!` itself
  re-reads the key row when it loses its own race.
- **Rule 1 is not yours to build.** Same key, same content, same reference is
  the replay the key store already serves; the reference pre-check must not
  run before the key check, or a replay would be refused as rule 3. Order:
  key (claimed by `execute-once!`), then reference, then the rest of
  validation that needs the database.
- **Null `operation_id` is not "no binding".** The lookup's `404` is a
  promise; the only way to keep it honest for rows written before `0014` is
  to refuse them by name.
- **Do not round, default or trim.** `clientReference` is stored exactly as
  sent after the shape check (no `trim` — a trailing space is refused, not
  removed, because the pattern excludes it); `creditorCountry` is not
  upper-cased for the caller.
- **`select-keys` and the projection.** Adding the two fields to
  `instruction-fields` changes no existing digest, because absent keys are
  omitted. Do not bump `canonicalisation-version` for this; do say in the REQ
  why not.
- **The literal path segment must win.** `/payment-instructions/by-idempotency-key/:key`
  and `/payment-instructions/:id` share a prefix. Put the lookup first in the
  route table *and* add the router test that proves order is not what decides
  it, if `clofin.http.router-test` does not already assert literal-over-param.
- **L-16 copies.** The contract's `IdempotencyKey` parameter description
  says "the six payment and approval mutations"; it stays true (the lookup is
  a `GET`). `docs/DOMAIN_MODEL.md` §1 has a status glyph per field — the two
  new rows are ✅ once merged, not 🔨.
- **L-9.** If a self-review is still running when you write the REQ, say so
  in the REQ and do not call the work complete until it is not.

## Changelog — rulings on the `018-REQ` objections, and the close-out (2026-10-04)

Delivered as `clofin-core` PR #42 (6 commits, merged at `6536ef5`). The REQ is
`docs/audits/018-REQ-client-reference-and-idempotency-lookup.md` on `main`,
with the independent adversarial review's six findings fixed in `255a0a6` and
the L-9 statement in plain words.

**The merge.** The operator merged PR #42 at 15:44Z, before these rulings and
without a written override. CI was green on the branch head `9141697`
(three checks) and is green on the merge commit; the integration job printed
`0014` applied. The 2026-09-07 rule — a Worker's PR is merged by Master
Control after its objections are ruled and CI is green on the branch — was set
after the same thing happened to PR #31; this is the second instance, and the
verification therefore moved onto `main`. The register records it; the rulings
below are made against the merged tree.

**Master Control's reproduction, on `main` at `6536ef5`** (a detached
worktree, local PostgreSQL 16): `make verify` 559 tests / 3,548 assertions,
0 failures — links OK (107 files), diagrams OK (7), consistency OK,
disclaimer OK; the integration suite migrated from nothing to `0014`:
`Applied: 14`, `Pending: 0`; 1,010 tests / 7,887 assertions, 0 failures, 0
errors (the Worker's run: 7,866 — the property tests and the conformance walk
generate their own populations). The merge touched no control-plane file
(`git diff --stat 59c6fde..6536ef5 -- docs/ROADMAP.md docs/briefs
docs/audits/README.md docs/AGENT_HANDOFF.md docs/audits/RELEASE-AUDIT-CHARTER.md
docs/releases` is empty).

| # | Objection | Ruling |
|---|---|---|
| O-1 | The brief says the router matches literals before parameters; it matches the first same-method route in table order. | **Confirmed — brief defect, Master Control's**, and of the kind the template was amended to forbid: a claim about current behaviour with no command behind it. The table-wide guard (no two same-method routes can match one path) is **accepted** and is the better invariant; no precedence rule is ordered. |
| O-2 | The lookup cannot name every key the header binds (encoded `/`, `%`, `\`, dot segments, non-ASCII). | **Ruled: (a)** — the narrowed lookup as published, with the contract naming exactly which keys the path carries and the real-server test guarding it. (b) breaks current callers; (c) is a server-wide posture change with its own security argument; (d) would be a second form of one operation, a copy of its semantics. The false `404` the first version answered was precisely the failure the `404`'s promise exists to prevent, found and fixed by the Worker's own review before anyone else read it — **commended**. |
| O-3 | "`select-keys` omits an absent field" is half the premise: the domain map carries the new fields as nil, so every digest would have moved. | **Confirmed — brief defect, Master Control's.** `omitted-when-absent` with the golden digest pinned from `main` is **accepted**; the pin is now a template expectation for any brief that adds to an audited projection (AGENT_HANDOFF §4, and the Notes of TASK-017 and TASK-019, amended today). |
| O-4 | `readers` is `field-readers`; DOMAIN_MODEL's field table is §2.2, not §1. | **Confirmed**, both. The `IdempotencyKey` paragraph update is the L-16 discipline applied unasked — welcome, not unwelcome. |
| O-5 | A race across connections cannot have its negative control "in a scratch transaction". | **Confirmed — brief defect.** The procedure the Worker used — drop on the test database, run, see failing, delete the mutated run's rows, recreate with the migration's own DDL, verify present — is adopted into AGENT_HANDOFF §4's template; TASK-019's AC-19-4 and AC-19-7 are corrected today. |
| O-6 | `PATCH` wording: "exactly as `retriesId`" or the table's sentence? | **Confirmed: the table's wording.** The mechanism is `retriesId`'s; the message is the table's. |
| O-7 | The creation effect made public as `creation-effect` so the race test runs the real code. | **Accepted** as decided. |
| O-8 | Observations. | (i) `make -o db-up test-it` against a local PostgreSQL 16 is the run this environment can make and is what Master Control ran too; CI's integration job is the canonical run and it reports `0014` applied. (ii) `creditorCountry` cannot be cleared through `PATCH`: carried into TASK-017's Notes. (iii) `boundAt` is the start of the binding transaction, not its commit: recorded; the next brief that touches the lookup's contract says which instant it is. |

**Carried forward.** TASK-017 reads `creditorCountry` and binds nothing to
the lookup; TASK-019 binds a chain event to `clientReference` and inherits
the race-test discipline (two connections, the interleaving forced and
asserted — the Worker's review finding 3 is the standard). The `IdempotencyConflict`
response component was folded into the create operation's `409` description
(REQ §5); the contract test discovers nothing by that name any more, which is
correct and is noted here so nobody restores it.
