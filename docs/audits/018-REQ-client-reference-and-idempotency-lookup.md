# 018-REQ — `clientReference`, `creditorCountry` and the idempotency-key lookup

| Field | Value |
|---|---|
| **Brief** | `018-TASK-client-reference-and-idempotency-lookup.md` on `origin/meta` (identical to the copy on `main` at `59c6fde`), dispatched 2026-10-04. Cited as a path: briefs live on the control plane |
| **Series number** | **018.** Task-keyed, reporting on `TASK-018` (**L-1**) |
| **Pull request** | `clofin-core` — [EchoJustus/clofin-core#42](https://github.com/EchoJustus/clofin-core/pull/42). Not merged; the merge is Master Control's, after the objections in §6 are ruled and CI is green on the branch |
| **Branch** | `task/018-client-reference`, as dispatched |
| **PR base** | `main` at `59c6fde` (the merge of PR #41, the sync carrying this brief). No stack |
| **Model** | An Anthropic Claude model. **The identifier is deliberately not written into this file, any commit or the PR**: this session runs under a rule that keeps model identifiers out of repository artifacts. Recorded here as a stated absence rather than omitted, as `014-REQ`–`016-REQ` did |
| **Reasoning effort** | Extended thinking throughout, with a scripted mutation harness for the negative controls (§4) and an independent adversarial review pass (§7). The harness exposes no numeric effort setting to the session, so this records the mode, not a measured value |
| **Date** | 2026-10-04 |
| **Migrations** | **One**: `0014-client-reference-and-idempotency-lookup.sql`, the fourteenth entry in `index.txt`, exactly the brief's DDL plus a comment on every new column |
| **Controls touched** | **C-06** — the key row records the operation that bound it, and a read of the key table is published with a stated proof. **C-05** — `client-reference` and `creditor-country` join the audited instruction projection |
| **Status** | Implemented. Objections in §6, none resolved by diverging silently |
| **Verification** | `make verify` **559 tests / 3,548 assertions**, 0 failures, 0 errors; `make test-it` (run as `make -o db-up test-it`, O-8) **1,010 tests / 7,866 assertions**, 0 failures, 0 errors — both on `255a0a6`, the last code commit. 19 mutation runs (18 mutations, M1–M17) each seen failing (§4). The independent adversarial review has reported: six findings, none blocking, all six acted on in `255a0a6` (§7). **No verification is in flight (L-9): verification complete, clean.** |
---

## 1. What this file is for

TASK-018 implements ADR-0028 D6: a client that keeps its own identifier for a
payment sends it as `clientReference` and core holds at most one instruction
for it per organisation; `creditorCountry` exists so a screening rule can name
it; and a client that lost the answer to a creation can ask
`GET /payment-instructions/by-idempotency-key/{key}` what its key is bound to,
with the contract saying exactly what a `404` proves. This file maps every
acceptance criterion to its test, quotes every negative control failing, states
the `409` ordering decision AC-18-4 asks for, and lists the objections.

Nothing here changes what CloFin claims to be: a synthetic-data reference
implementation, connected to nothing, approved by no one, processing no real
funds.

---

## 2. What changed

| Commit | Items | What |
|---|---|---|
| `72a1dd1` | A-1…A-7 (code and contract) | Migration `0014`; `clofin.payments.instruction` gains the two patterns, two `field-errors` rules, `creation-members` and `same-content?`, `:creditor-country` in `amendable-fields` and **not** `:client-reference`; `clofin.payments.repository` carries both columns through `instruction-columns`, `insert!`, `row->instruction` and `amend!`'s `UPDATE`, gains `find-by-client-reference`, `client-reference-refusal` and the unique-violation catch that marks a lost race; `clofin.idempotency.repository/execute-once!` requires `:operation-id` and writes it on the claiming insert, and gains `find-binding`; the six callers pass their `operationId` literal; `clofin.api.payments` gains `creation-effect` (extracted from `create`, unchanged in behaviour), the race resolution after rollback, `lookup-by-key`, `lookup-refusal-reasons` and `no-binding-detail`; `clofin.idempotency/read-path-key` shares `read-key`'s rules; `clofin.api.wire` renders both members only when present and gains `read-path-segment`; `clofin.audit/instruction-fields` gains both, left out of the projection when nil; the route; `api/openapi.yaml`; every test in §3 |
| `ea059e9` | A-7 (documents) | `docs/DOMAIN_MODEL.md` §2.2 (see O-4) gains the two rows, ✅, and its `IdempotencyKey` paragraph says the row now carries its operation; `docs/COMPLIANCE.md` C-06 gains one paragraph on the lookup; ADR-0028's Verification row and its `idempotencyKey` format corrected with dated parentheses — no other ADR text changed |
| `603fab9` | A-5, A-7 | The lookup's contract stopped promising keys the transport will not carry (O-2), with a real-server test guarding the narrowed statement |
| `bca4929` | — | this file, filed with the adversarial review still in flight and saying so |
| `255a0a6` | A-2, A-5, A-6, A-7 | The review's six findings, acted on (§7): non-ASCII path keys refused rather than answered a false `404`; the transport's full refusal list in the contract and the system test; the reference race tests force and assert the interleavings they claim; the create `409` lists the link-target refusals; the ordering wording made literal; a lost race with no readable winner is a `500` |
| *(this commit)* | — | this file, brought up to date with the review and its fixes |

---

## 3. Finding by criterion

Every acceptance criterion, its named test (the executable identifiers
ADR-0028's Verification table commits to, plus the names this branch adds
where a criterion needed more than one), what the test asserts, and the
negative control seen failing (§4 quotes each).

| AC | Test(s) | Asserts | Negative control (§4) |
|---|---|---|---|
| **AC-18-0** | `clofin.db.migrate-test/ac-18-0-the-index-reaches-0014`; `clofin.payments.repository-test/ac-18-0-the-schema-refuses-every-bad-shape-with-the-application-bypassed`; `clofin.db.vocabulary-test/a-014-every-schema-vocabulary-has-an-owner-in-code` (unchanged, passes) | `0014` is the fourteenth entry, after `0013`, and applied; both documented shapes insert (both members; neither), and the 128-character bound; the pre-flight's refusals re-run on the migrated test database with the application bypassed — a reference with a space, of 129 characters, empty, non-ASCII; a lowercase country, a digit in the country; the same reference twice in one organisation; the same reference in another organisation accepted | **M1** — the shape check dropped: four refusals stop |
| **AC-18-1** | `clofin.api.payments-api-test/ac-18-1-the-lookup-returns-the-stored-response-and-current-status`; `…/ac-18-1-a-key-with-reserved-characters-is-looked-up-percent-encoded`; `clofin.system-test/ac-18-1-the-transport-carries-some-encoded-keys-to-the-lookup-and-refuses-others`; `clofin.idempotency-test/ac-18-1-a-key-named-in-a-path-obeys-the-headers-rules`; `clofin.api.wire-test/ac-18-1-a-path-segment-is-percent-decoded-as-a-path-and-not-as-a-form` | create, submit, look the creation key up: `originalStatus` 201, `originalBody` equal to the creation response, `status` inside it still `draft`, `currentStatus` `pending-approval`, `instructionId`, `idempotencyKey`, `boundAt`; the same key from another organisation's actor is `404 no-binding`; a key with a space and `+` round-trips percent-encoded; a non-ASCII key is `400` even when the header bound it, never a false `404`; an encoded `/`, `%` or `\`, or a segment of `%2E` / `%2E%2E`, is refused by the real transport before the service (O-2); a key the header could never bind is `400` | **M2** — the binding read ignores the organisation; **M15** — Jetty's URI compliance relaxed; **M16** — the non-ASCII refusal removed |
| **AC-18-2** | `…/ac-18-2-the-lookup-answers-404-only-when-no-key-is-bound`; `clofin.idempotency.repository-test/ac-18-2-a-read-of-an-absent-key-is-nil-and-a-legacy-row-says-it-has-no-operation` | an unused key is `404`, `errors.reason` `no-binding`, `detail` the contract sentence; a key bound by a submission is `409 key-bound-to-another-operation`, `errors.operationId` `submitPaymentInstruction`, no `instructionId`; a raw-inserted row with null `operation_id` is `409` with `errors.operationId` present and null | **M3** — the handler answers `404` for the null case: the third assertion fails, as the brief predicts |
| **AC-18-3** | `…/ac-18-3-a-404-during-an-in-flight-creation-becomes-200-on-the-same-key-with-one-instruction` | thread A runs **the real creation effect** (`clofin.api.payments/creation-effect`) under `execute-once!` and parks on a latch inside it after the insert; the lookup on a second connection is `404 no-binding`, with A's backend `idle in transaction` in `pg_stat_activity` before and after the lookup and A's release latch still at 1 (non-vacuity); a resubmission under the **same** key while A is open parks on A's key row — asserted as a lock wait on its claiming `insert into idempotency_key` — and after release becomes the replay — `201`, `Idempotent-Replayed: true`, A's body byte for byte; the lookup is then `200` naming the one instruction; one `payment_instruction`, one `idempotency_key`; a resubmission after the commit is a replay too | **M4** — the latch's `await` removed: six assertions fail, the first being A's backend reported `active`, not `idle in transaction` |
| **AC-18-4** | `…/ac-18-4-a-reused-client-reference-with-different-content-is-409-and-creates-nothing`; `…/ac-18-4-a-race-loser-with-different-content-answers-client-reference-conflict`; `clofin.payments.repository-test/ac-18-4-the-reference-rules-hold-at-the-repository-seam`; `clofin.payments.instruction-test/ac-18-4-same-content-compares-every-creation-member-and-nothing-else` | rule 2 under a new key is `409`, `errors` exactly `{reason: client-reference-conflict, instructionId}`, nothing persisted, the new key not consumed — a corrected request under that same key succeeds; rule 2 under the **original** key is the key's own `409` (`errors` = `{header: Idempotency-Key}`) — the published order (§5); an instruction amended after creation answers rule 2 to its own original content; the race loser with different content — drawn on a second debtor account, so its insert parks on A's uncommitted index entry (asserted as the waiting statement in `pg_stat_activity`) — answers `client-reference-conflict` (L-18); `same-content?` compares exactly the nine creation members, ignores identity, provenance, status and the reference, treats absent and nil alike, and normalises trimming as `instruction` does | **M5** — `same-content?` always true; **M14** — the race loser decided without comparing; **M17** — the loser put back on A's debtor account: it no longer waits on the index, and the interleaving assertion fails |
| **AC-18-5** | `…/ac-18-5-a-reused-client-reference-under-a-new-key-answers-409-naming-the-existing-instruction` | rule 3 sequentially — `409`, `errors` exactly `{reason: client-reference-exists, instructionId}`, the id in `detail`, the id readable, nothing persisted, the key not consumed; **and** two threads, two keys, one reference: A held open after its insert; B's pre-check runs while A is uncommitted and misses A's row; identical content means the same debtor account, so B then parks on the account row lock A holds — asserted as the waiting statement in `pg_stat_activity`, and taken only *after* the pre-check, which is what proves the pre-check missed; when A commits B's insert fails on the index against A's committed row and B is decided on the rolled-back side — exactly one `201`, one `409 client-reference-exists` naming it, one instruction, one key row. (Corrected after review finding 3: the first version said B parked on the index itself, which only the different-content race can show.) `…/ac-18-5-a-lost-race-with-no-winning-row-is-a-defect-not-a-refusal` covers finding 6 | **M6** — the partial unique index dropped: B answers `201`, and "one instruction" fails |
| **AC-18-6** | `…/ac-18-6-the-lookup-requires-payment-read-and-is-scoped-to-the-callers-organisation` | no actor `401`; an actor seeded with no role `403`; `organisationId` naming another organisation `403`; the maker `200` with or without its own `organisationId` | **M7** — the lookup authenticates without checking `payment/read`: the no-role actor gets `200` |
| **AC-18-7** | `…/ac-18-7-a-client-reference-is-immutable-through-the-api-and-behind-it`; `clofin.payments.repository-test/ac-18-7-the-raw-update-of-a-reference-is-refused-by-the-trigger`; `clofin.payments.instruction-test/ac-18-7-a-client-reference-is-not-amendable-and-a-country-is` | `PATCH` naming it is `422`, `errors` exactly `{clientReference: "is set when the instruction is created and cannot be amended"}` (O-6); adding one to an instruction created without one is `422`; the raw `UPDATE` value→value, value→null and null→value each refused by the trigger, application bypassed; an `UPDATE` that leaves the reference alone is not; the same reference in a second organisation creates a second instruction | **M8a** — the trigger dropped: five assertions fail; **M8b** — `:client-reference` added to `amendable-fields`: the `PATCH` answers `200` |
| **AC-18-8** | `…/ac-18-8-creditor-country-is-syntax-only-and-amendable`; `clofin.audit-test/ac-18-8-the-two-new-members-move-no-existing-digest`; `clofin.audit-test/ac-18-8-the-projection-includes-the-two-members-when-they-are-present`; `clofin.payments.instruction-test/ac-18-8-creditor-country-is-a-shape-and-not-a-list` | `SG` accepted and rendered; `sg`, `SGP`, `S1` each refused and named beside a bad `clientReference` and a bad `purposeCode` in one `422`; an amend changes it and the `payment.amended` event's before and after digests differ; a fixed instruction without either member digests to the golden value computed on `main` before this change, whether its map omits the keys or carries them nil | **M9** — the country left out of the projection: the digests are equal; **M12** — the projection digests absent members as null: the golden digest moves |
| **AC-18-9** | `clofin.api.conformance-test/every-bound-key-names-the-operation-that-bound-it`; `clofin.idempotency.repository-test/ac-18-9-a-key-is-never-claimed-without-its-operation`; `…/ac-18-9-the-claiming-insert-writes-the-operation` | after the walk every key row has a non-null `operation_id` that is an `operationId` in `clofin.routes/routes`, **equal to the operation the walk sent that key to** (a check per row, stronger than the set equality the brief asks for, which a swap of two literals would pass), and the distinct set equals `operations-the-contract-marks`; `execute-once!` refuses nil, `""`, blank and a non-string before any read or write, and no effect runs | **M10a** — the refusal removed: six assertions fail; **M10b** — cancel binds under `submitPaymentInstruction`: the per-row check names the key |
| **AC-18-10** | `clofin.contract-test/ac-18-10-the-contract-publishes-both-refusal-vocabularies-the-service-emits`; `…/ac-18-10-the-two-members-are-declared-where-the-service-accepts-and-renders-them`; `clofin.api.conformance-test/ac-18-10-the-walk-drives-the-lookup-to-every-answer-it-declares`; the extended `clofin.api.payments-api-test/ac-9-two-concurrent-requests-with-one-key-produce-exactly-one-effect`; `clofin.contract-test` and `clofin.api.conformance-test` as a whole | both enums equal the code's sets; both members' patterns and bounds on the create request and the resource, the country on the amend request and the reference not; the published patterns admit exactly what the domain's do over a sample set; `IdempotencyKeyLookup` is the fragment with the key as the service accepts it; the walk drives the lookup to `200`, `404` and `409` and the create to `201` and its reference `409`, all three dimensions checked; the AC-9 race carries a `clientReference` and exactly one row carries it | **M11** — one reason removed from the published enum |
| *(router)* | `clofin.http.router-test/ac-18-no-two-served-routes-with-one-method-can-match-one-path`; `…/ac-18-the-lookup-and-the-instruction-read-dispatch-the-same-in-either-order`; `…/first-match-wins-is-the-routers-actual-rule` | see O-1 | **M13** — a `GET /payment-instructions/:id/:sub` added to the table |
| *(rule 1)* | `…/ac-18-2-rule-1-the-same-reference-under-the-same-key-is-the-ordinary-replay` | the brief's "nothing to build, one test to add" | — (no code; the replay is `execute-once!`'s, already guarded by AC-6/AC-9) |

Further unit tests named in §2's commit: `clofin.payments.instruction-test/ac-18-2-a-client-reference-is-optional-and-printable-ascii-without-spaces`,
`…/ac-18-2-a-bad-client-reference-is-named-with-every-other-failed-field`,
`…/ac-18-2-construction-keeps-the-reference-exactly-as-sent`;
`clofin.api.wire-test/ac-18-2-the-two-new-members-are-rendered-only-when-present`;
`clofin.payments.repository-test/ac-18-2-both-members-round-trip-through-the-repository`.
`every-amendable-field-can-actually-be-amended` failed until it covered
`:creditor-country` — the guard doing its job.

**The two shape constraints are not vocabularies.** `clofin.db.vocabulary-test`
discovers `= ANY (ARRAY[…])` check constraints, and `payment_client_reference_shape`
and `payment_creditor_country_shape` are regular-expression checks; no owner is
added for them, as the brief directs. The discovered set is unchanged, shown by
the same query at `0013` and at `0014`:

```sql
select count(*) as vocabulary_constraints,
       md5(string_agg(conrelid::regclass || '.' || conname, ',' order by conrelid::regclass::text, conname)) as set_digest
  from pg_constraint
 where contype = 'c' and pg_get_constraintdef(oid) ~ '= ANY \(ARRAY\[';
```

```
-- schema 0013                                      -- schema 0014
 vocabulary_constraints |            set_digest      vocabulary_constraints |            set_digest
------------------------+---------------------------  ------------------------+---------------------------
                     25 | b7373ab5c097ac06d5ce0829e45a80cf               25 | b7373ab5c097ac06d5ce0829e45a80cf
```

**Why `canonicalisation-version` is not bumped.** The canonical form did not
change, and no digest of any existing instruction moved: `instruction-subject`
leaves the two new members **out of the projection when they are nil**, so an
instruction carrying neither digests exactly as before — whether its map omits
the keys or carries them as nil (O-3 explains why that second clause needed
code, not just `select-keys`). `clofin.audit-test/ac-18-8-the-two-new-members-move-no-existing-digest`
pins it with a fixed instruction and its digest computed on `main` at `59c6fde`
before the change: `v1:ae49022d7ca4f09b6a1152eed67f3a84087003e45351ac16190746de813006f3`.

**AC-18-0's `clojure -M -m clofin.db.migrate` on a database at `0013`,** as run
for this report against PostgreSQL 16:

```
INFO  [main] clofin.db.migrate - Applying migration 0014-client-reference-and-idempotency-lookup.sql
INFO  [main] clofin.db.migrate - Applied migration 0014-client-reference-and-idempotency-lookup.sql
Applied 1 migration(s): 0014
```

and `clojure -M -m clofin.db.migrate status` then ended `0014 client reference
and idempotency lookup` / `Pending: 0`. The CI job's own `status` print is on
the PR's integration run for Master Control to read (DoD line 3).

**The worker's own pre-flight** of the migration — not required (the brief's
was Master Control's), run because the DDL is the brief's verbatim and L-3 asks
that every row of SQL be executed — at schema `0013` in a transaction rolled
back afterwards: every shape inserted (both members; neither; the 128-character
bound; the same reference in a second organisation; an `idempotency_key` row
with and without `operation_id`), an `UPDATE` touching only the country passed
the trigger, and all twelve refusals fired — duplicate reference, value→value,
null→value, value→null, a space, 129 characters, empty, non-ASCII, `sg`, `S`
(padded to `S ` by `char(2)` and refused by the check), `S1`, and `SGP`
(`value too long for type character(2)`, refused by the type before the
check). Schema still `0013` afterwards.

---

## 4. Negative controls, seen failing

Each mutation was applied by a script, the named test run on its own against
the migrated test database, the failure captured, and the mutation reverted
(`git checkout` for code; the dropped object recreated with the migration's own
DDL for schema, after deleting the rows the mutated run had been allowed to
write). Quoted from the run on `255a0a6`; `git status` was clean and the index,
trigger and constraint all present afterwards. Abridged to the first failing
assertion of each.

*An incident in producing this section, stated because it touches what was
verified.* The first run after the review fixes was made **before they were
committed**, and the harness's `git checkout` reverted the uncommitted edits in
every file it mutated — four files lost their fixes mid-run, which is why that
run's M16 passed (its test had been reverted out from under it). The edits
were re-applied from the scripts that made them, the affected namespaces re-run
to identical counts (113 tests / 1,206 assertions), committed as `255a0a6`, and
the harness given a guard that refuses to run on a dirty tree. Every quotation
below is from the clean re-run on the committed tree; nothing from the
interrupted run is quoted.

**M1 (AC-18-0)** — `alter table payment_instruction drop constraint payment_client_reference_shape`. 7 pass, **4 fail**:

```
FAIL in (ac-18-0-the-schema-refuses-every-bad-shape-with-the-application-bypassed) (repository_test.clj:531)
expected: (= "payment_client_reference_shape" (refused-by (fn* [] (raw-insert! f :client-reference "has space"))))
  actual: (not (= "payment_client_reference_shape" nil))
```

**M2 (AC-18-1)** — `find-binding` reads `where (organisation_id = ? or true) and key = ?`. 14 pass, **2 fail**:

```
FAIL in (ac-18-1-the-lookup-returns-the-stored-response-and-current-status) (payments_api_test.clj:1129)
expected: (= 404 status)
  actual: (not (= 404 500))
```

The other tenant's payment is still not disclosed under this mutation — the
instruction read is scoped to the caller's organisation, finds nothing, and the
handler refuses to answer from a binding whose instruction it cannot read — but
the `404` contract is broken, and the test says so.

**M3 (AC-18-2)** — the first `cond` arm becomes `(or (nil? found) (nil? (:operation-id found)))`. 14 pass, **3 fail** — the third assertion, as the brief predicted:

```
FAIL in (ac-18-2-the-lookup-answers-404-only-when-no-key-is-bound) (payments_api_test.clj:1197)
expected: (= 409 status)
  actual: (not (= 409 404))
```

**M4 (AC-18-3)** — `(.await release 60 TimeUnit/SECONDS)` removed from the held-open effect. 18 pass, **6 fail**:

```
FAIL in (ac-18-3-a-404-during-an-in-flight-creation-becomes-200-on-the-same-key-with-one-instruction) (payments_api_test.clj:1231)
non-vacuity: A's transaction is open when the lookup runs
expected: (= "idle in transaction" (backend-state (clojure.core/deref pid)))
  actual: (not (= "idle in transaction" "idle"))
```

This is the demonstration the brief asks for: without the latch the `404`
assertion races, and the test does not rely on winning that race — it asserts
from `pg_stat_activity` that the lookup ran while A's transaction was open
(the backend has already committed and gone `idle` here; on the earlier run it
was still `active`).

**M5 (AC-18-4)** — `same-content?` answers `(or true …)`. 15 pass, **2 fail**:

```
FAIL in (ac-18-4-a-reused-client-reference-with-different-content-is-409-and-creates-nothing) (payments_api_test.clj:1309)
expected: (= {"reason" "client-reference-conflict", "instructionId" (get-in original [:json "id"])} (get json "errors"))
  actual: (not (= {"reason" "client-reference-conflict", …} {"reason" "client-reference-exists", …}))
```

**M6 (AC-18-5)** — `drop index payment_instruction_client_reference_key` (O-5 on why not "in a scratch transaction"). 14 pass, **4 fail**, the first at `payments_api_test.clj:1435`:

```
(is (= [201 409] [(:status winner) status]) (pr-str json))      ; observed [201 201]
```

followed by the "exactly one instruction" assertion. One duplicate row deleted
before the index was recreated.

**M7 (AC-18-6)** — `(principal/for-request pool request :payment/read)` replaced by `(principal/authenticated-for pool request)` in the lookup. 8 pass, **1 fail**:

```
FAIL in (ac-18-6-the-lookup-requires-payment-read-and-is-scoped-to-the-callers-organisation) (payments_api_test.clj:1210)
expected: (= 403 (:status (lookup k {:actor (seed-actor! (:org f) [])})))
  actual: (not (= 403 200))
```

**M8a (AC-18-7)** — `drop trigger payment_instruction_client_reference_immutable`. 1 pass, **5 fail**:

```
FAIL in (ac-18-7-the-raw-update-of-a-reference-is-refused-by-the-trigger) (repository_test.clj:558)
value -> value must be refused by the trigger, got nil
```

**M8b (AC-18-7)** — `:client-reference` added to `amendable-fields`. 9 pass, **3 fail**:

```
FAIL in (ac-18-7-a-client-reference-is-immutable-through-the-api-and-behind-it) (payments_api_test.clj:1454)
expected: (= 422 status)
  actual: (not (= 422 200))
```

**M9 (AC-18-8)** — `:creditor-country` removed from `instruction-fields`. 15 pass, **1 fail**:

```
FAIL in (ac-18-8-creditor-country-is-syntax-only-and-amendable) (payments_api_test.clj:1504)
expected: (not= (:before-digest event) (:after-digest event))
  actual: (not (not= "v1:0906e794…05d6" "v1:0906e794…05d6"))
```

**M10a (AC-18-9)** — `(assert-operation-id! operation-id)` removed from `execute-once!`. 0 pass, **6 fail**:

```
FAIL in (ac-18-9-a-key-is-never-claimed-without-its-operation) (repository_test.clj:37)
expected: (= :validation (:clofin/error (ex-data t)))
  actual: (not (= :validation nil))
```

**M10b (AC-18-9)** — `cancel` passes `"submitPaymentInstruction"`. 39 pass, **2 fail**:

```
FAIL in (every-bound-key-names-the-operation-that-bound-it) (conformance_test.clj:699)
key 2fe68374-… was sent to cancelPaymentInstruction and records "submitPaymentInstruction"
```

**M11 (AC-18-10)** — `client-reference-exists` removed from `ClientReferenceRefusalReason`. 3 pass, **1 fail**:

```
FAIL in (ac-18-10-the-contract-publishes-both-refusal-vocabularies-the-service-emits) (contract_test.clj:579)
  actual: (not (= #{"client-reference-exists" "client-reference-conflict"} #{"client-reference-conflict"}))
```

**M12 (no digest moves)** — `instruction-subject` keeps nil members. 2 pass, **1 fail**:

```
FAIL in (ac-18-8-the-two-new-members-move-no-existing-digest) (audit_test.clj:89)
  actual: (not (= "v1:ae49022d…06f3" "v1:ac0d3d17…73f2"))
```

**M13 (router)** — `{:method :get :path "/payment-instructions/:id/:sub" …}` added before the instruction read. 2 pass, **1 fail**:

```
FAIL in (ac-18-no-two-served-routes-with-one-method-can-match-one-path) (router_test.clj:113)
these same-method routes can match one path, so table order decides between them:
[[:get "/payment-instructions/by-idempotency-key/:key" "/payment-instructions/:id/:sub"]]
```

**M14 (AC-18-4, the race, L-18)** — the race resolution calls `(client-reference-refusal existing existing)`. 9 pass, **1 fail**, at `payments_api_test.clj:1370`:

```
(is (= {"reason" "client-reference-conflict" "instructionId" (get (:data winner) "id")} (get json "errors")))
                                                                ; observed reason client-reference-exists
```

**M15 (AC-18-1, the transport)** — `(.setUriCompliance org.eclipse.jetty.http.UriCompliance/LEGACY)` on the server's `HttpConfiguration`. 4 pass, **10 fail** — two for each of the five refused encodings:

```
FAIL in (ac-18-1-the-transport-carries-some-encoded-keys-to-the-lookup-and-refuses-others) (system_test.clj:105)
/payment-instructions/by-idempotency-key/a%2Fb
expected: (= 400 status)
```

**M16 (AC-18-1, non-ASCII — review finding 1)** — the printable-ASCII check in `read-path-key` disabled. 14 pass, **2 fail**:

```
FAIL in (ac-18-1-a-key-with-reserved-characters-is-looked-up-percent-encoded) (payments_api_test.clj:1164)
expected: (= 400 status)
  actual: (not (= 400 404))
```

— the false `404 no-binding` the review predicted, for a key that is bound.

**M17 (AC-18-4, the interleaving — review finding 3)** — the rule-2 loser drawn on A's debtor account again (`(assoc body "amount" …)` in place of the second account). 9 pass, **1 fail**, at `payments_api_test.clj:1362`:

```
(is (pos? (await-lock-wait! "%insert into payment_instruction%"))
    "the loser passed the pre-check and its insert is parked on the index")      ; 0 after ten seconds
```

— the loser waits on the account lock instead, and the test now notices.

**The race tests are not flaky in the direction that matters.** The three
forced-interleaving tests and the extended AC-9 race were run fifteen times
each in one JVM after the review fixes: 60 runs, 915 assertions, 0 failures.

---

## 5. The `409` ordering decision (AC-18-4)

**After authentication, the body's parse and the refusal of members a caller
may not set** (`createdBy`), **the `Idempotency-Key` is decided first.**
`execute-once!` claims the key row before the effect runs, so:

1. same key, same body → the stored response, replayed (rule 1);
2. same key, different body → the key's `409`, `errors` = `{header:
   Idempotency-Key}`, **whatever the reference says** — asserted by AC-18-4's
   "under the original key" case;
3. then every field at once — `422` naming every failure, a bad
   `clientReference` among them;
4. then the reference — `409 client-reference-exists` / `client-reference-conflict`,
   before any lock is taken, so a repeated reference is answered even when the
   debtor account has since been frozen (asserted at the repository seam);
5. then the rules that need the database — the link target's lifecycle
   (`409`: `reversesId` not settled, `retriesId` not returned) and the debtor
   account (`422`).

Published in the create operation's description and in its `409`, which lists
every conflict in that order (review findings 4 and 5 made both statements
complete and literal). The `IdempotencyConflict` response component, used only
by this operation, is folded into that description and removed from the
contract.

---

## 6. Objections

**O-1 — The router has no literal-over-parameter precedence; the brief says
it does.** *Interfaces* says "the router matches literals before parameters";
*Notes* asks for "the router test that proves order is not what decides it, if
`clofin.http.router-test` does not already assert literal-over-param". It does
not, because the router does not do it: `clofin.http.router/match` takes the
first same-method route in **table order** whose segments match
(`first-match-wins-is-the-routers-actual-rule` now documents that).
`/payment-instructions/by-idempotency-key/:key` and `/payment-instructions/:id`
never compete — they differ in segment count — so the lookup is safe in either
position, and it sits first as the brief asks. I did **not** add a precedence
rule to the router (not in scope, and a dispatch change for every route).
Instead `ac-18-no-two-served-routes-with-one-method-can-match-one-path` asserts
over the whole served table that no two same-method routes can match one path,
which makes order irrelevant for every route, this one and any later one, and
`ac-18-the-lookup-and-the-instruction-read-dispatch-the-same-in-either-order`
dispatches both paths through the table forward and reversed. *Ruling asked:*
accept the table-wide guard, or order a literal-precedence rule in the router.

**O-2 — The lookup cannot name every key the header can bind.** A-5 says
"`read-key`'s rules apply (any non-blank string without control characters,
≤ 255)", implying any key the header binds can be looked up. Against the real
server it cannot, in three ways, each probed directly:

- Jetty 12's default URI compliance refuses an encoded `/` (`%2F`), `%`
  (`%25`) or `\` (`%5C`), and a segment that is wholly `%2E` or `%2E%2E`, with
  its own HTML `400` before a handler runs; and a client that removes dot
  segments rewrites an unencoded `.` or `..` into another path.
- The transport reads a header's octets beyond ASCII one character per octet
  (`é` sent as UTF-8 is bound as `Ã©`), so no decoding of a path can be relied
  on to name a non-ASCII key; decoding it as UTF-8, as the first version did,
  answered a **false `404 no-binding`** for a key that was bound (review
  finding 1, M16).

My first contract text claimed every such key was looked up "as the header
carried it". Self-review caught `/` and `%` (`603fab9`); the adversarial review
caught the rest (`255a0a6`). The lookup now refuses a non-ASCII key with a
`400` naming the limit, and the contract lists exactly which keys the path
carries, that the transport's refusals are not problem documents, and that a
UUID key (ADR-0028 D6 names UUID v4 for the satellite) qualifies.
`clofin.system-test/ac-18-1-…` asserts all five transport refusals against the
real server; M15 fails ten of its assertions when compliance is relaxed.
*Options for the ruling:* (a) accept the narrowed lookup as published (my
recommendation — the satellite sends UUIDs); (b) narrow the header's key
alphabet to what a path carries — a breaking change for any current caller
using those characters; (c) relax the transport's URI compliance — a
server-wide posture change with its own security argument, not something to
make inside this brief; (d) a query-parameter or body form of the lookup.

**O-3 — "`select-keys` omits an absent field" is only half the premise.** The
brief reasons that adding the two fields to `instruction-fields` leaves
existing digests unchanged because `select-keys` omits absent keys — and, in
the same brief, specifies that "the domain map gains `:client-reference` and
`:creditor-country` (both may be nil)". Under that shape `select-keys` keeps a
nil key, the canonical form gains `"client-reference":null`, and **every**
instruction digest would move — including the before-digest of every amendment
of an instruction created before `0014`. Implemented to the brief's stated
intent, not its mechanism: `instruction-subject` drops the two keys when nil
(`omitted-when-absent`), the golden digest from `main` pins it, and M12 shows
the test failing without it. Not a divergence in outcome; recorded because the
reasoning in *Context* and *Notes* would have produced a digest change if
followed literally, which is the kind of thing a future brief inherits.

**O-4 — Two names in the brief do not match the tree.** (i) "`clofin.api.payments/readers`"
is `field-readers`; the two members were added there. (ii) "`docs/DOMAIN_MODEL.md` §1's
`PaymentInstruction` table" — §1 is the ubiquitous-language table, with one
row for the term; the field table with status glyphs is in §2.2, and the two
rows were added there, ✅. I also updated §2.2's `IdempotencyKey` paragraph,
which said what the key row stores and would otherwise be a stale copy of that
claim (L-16) — one paragraph beyond "two rows"; *ruling asked* only if that is
unwelcome.

**O-5 — AC-18-5's negative control cannot run "in a scratch transaction".**
The race spans at least three connections; a `drop index` inside an
uncommitted transaction is invisible to the others and holds an `ACCESS
EXCLUSIVE` lock that blocks them, so the test would hang rather than fail. Done
instead (M6, and likewise M1 and M8a): the object dropped on the test database,
the test run and seen failing, the rows the mutated run wrote deleted, and the
object recreated with the migration's own DDL; verified present afterwards.
The template's phrase is worth correcting for schema guards under concurrency.

**O-6 — The `PATCH` refusal's wording: "exactly as `retriesId`", or the
table?** A-2 says a `PATCH` naming `clientReference` is "`422` naming the
member, exactly as `retriesId`" — which answers `cannot be amended` — while
*Problem documents* gives `{"clientReference": "is set when the instruction is
created and cannot be amended"}`. I followed the table (the more specific
statement, and the one an AC can assert against); the mechanism is exactly
`retriesId`'s (absent from `amendable-fields`, refused by the trigger). *Ruling
asked:* confirm the table's wording.

**O-7 — AC-18-3 needed the creation effect to be callable.** "Thread A calls
`execute-once!` directly with the creation effect" — the effect was an
anonymous closure inside `create`. It is now `clofin.api.payments/creation-effect`,
public, with no change in behaviour (the whole API suite passes unchanged), so
the race test runs the real effect rather than a stand-in. The latch sits after
the effect's body — insert and audit event — and before it returns, which
satisfies "after the instruction row is inserted and before the effect
returns". Stated as a decision, not disputed.

**O-8 — Observations, no ruling needed.** (i) `make test-it`'s `db-up` step
needs Docker, which this execution environment does not have; the target was
run as `make -o db-up test-it` against a local PostgreSQL 16 — its own
`migrate` and `clojure -M:test:it` steps, unchanged. CI's integration job is
the canonical run. (ii) A `creditorCountry` once set cannot be cleared by
`PATCH`: a JSON `null` is treated as absent, as it is for every member — out of
scope here, mentioned because TASK-017's rules will read the field. (iii) The
lookup's `boundAt` is the key row's `created_at`, which is the creating
transaction's start (`now()`), not its commit.

---

## 7. Self-review, the adversarial review, and the L-9 statement

**Self-review.** Re-reading my own diff against the brief, and probing the real
server rather than the handler, found one real defect: the lookup's contract
claimed a key containing `/` or `%` is looked up as the header carried it, and
Jetty refuses both encodings in a path before any handler runs. Fixed in
`603fab9` and raised as O-2. The handler-level test that had "proved" it was
proving a path no client can reach — L-10's shape.

**The independent adversarial review** — a separate read-only pass over the
branch against the brief and ADR-0028 D6, which this file was first filed
waiting on (`bca4929`) — reported **six findings, none blocking**, and
confirmed as clean: the rule order; that no refusal consumes a key; that the
race's loser decides after its rollback through the serial path's own function;
`same-content?`'s members and normalisation; that every producer of an
instruction audit event goes through `instruction-subject`; `execute-once!`'s
check coming first and all six literals; the lookup's tenant scoping and error
shapes; and the migration against the brief's DDL. Every finding was verified
before it was acted on — 1 and 2 by probing Jetty, 3 by reading
`create-instruction!`'s lock order — and all six are fixed in `255a0a6`:

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | should-fix | A non-ASCII key gets a false `404`: Jetty decodes header octets as ISO-8859-1, the lookup decoded the path as UTF-8 | **Fixed.** `read-path-key` refuses any character outside printable ASCII, `400`; contract says so; API and unit tests; **M16** |
| 2 | should-fix | The transport also refuses `%5C`, `%2E`, `%2E%2E`; the contract named only `/` and `%` | **Fixed.** Contract, docstrings and `clofin.system-test` cover all five; dot segments named; O-2 widened |
| 3 | should-fix | The reference race tests did not force the interleaving they claimed: B waited on the shared debtor account's lock, not the index; "some lock" was all that was asserted | **Fixed.** The rule-2 race uses a second account, so its insert waits on the index; the rule-3 race (which must share the account) now claims what it shows; all three race tests assert the waiting statement; the migration's index comment corrected (0014 is unmerged); REQ §3 corrected; **M17** |
| 4 | should-fix | The create `409` said "one of three conflicts"; the link-target lifecycle refusals are `409` too | **Fixed.** Listed, in order |
| 5 | nit | "The key row is claimed before anything else runs" was not literal | **Fixed.** Authentication, parse and the caller-set-member refusal named as coming first, in the contract, the docstring and §5 |
| 6 | nit | A lost race with no readable winner rethrew a `409` with no declared reason | **Fixed.** A `500` with a correlation id; `ac-18-5-a-lost-race-with-no-winning-row-is-a-defect-not-a-refusal` |

After the fixes: `make verify` and `make test-it` re-run green (§8), every
mutation re-run on the committed tree and seen failing (§4), the race tests
re-stressed.

**L-9, in plain words: no verification is in flight. Verification complete,
clean.** Nothing I declared is still running, and nothing I know of is waiting
to be fixed. What remains is Master Control's: the rulings in §6 and CI on the
branch.

---

## 8. Verification

| Suite | Command | Result |
|---|---|---|
| Baseline, before any change | `clojure -M -m clofin.db.migrate && clojure -M:test:it` on `59c6fde` | 971 tests / 7,443 assertions, 0 failures, 0 errors |
| `make verify` | unit and property tests, docs links, diagrams, document consistency, disclaimer | 559 tests / 3,548 assertions, 0 failures, 0 errors; `Documentation links OK (107 markdown files checked)`; `Diagrams OK (7 generated artifact(s) match their sources)`; `Document consistency OK (13 control(s), 35 increment status claim(s), 0 prose task claim(s), 20 brief(s))`; `Disclaimer OK` — on `255a0a6` |
| `make test-it` | `make -o db-up test-it` (O-8) | 1,010 tests / 7,866 assertions, 0 failures, 0 errors — on `255a0a6`. (Assertion counts vary run to run by a few: the property tests and the conformance walk generate their own populations.) |
| `make diagrams-check` | inside `verify` | no diagram source changed, none expected |
| Race stress | the three forced-interleaving tests and AC-9, 15 runs each, after the review fixes | 60 runs, 915 assertions, 0 failures |
| Negative controls | §4 | 18 mutations in 19 runs (M1–M17, M8 and M10 in two parts), each seen failing its test on the committed tree; tree and schema restored |
