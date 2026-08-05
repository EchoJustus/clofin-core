# UAT-006 — Settlement, and the four ways a scheme misbehaves

**Covers:** batch construction, release, terminal outcomes, and the failure
modes that distinguish a settlement module from a status field
**Requirements:** PR-030, PR-031, PR-032 (PRD §5.3); NFR-003
**Controls:** [C-03](../COMPLIANCE.md), C-04, C-05, C-08
**Related:** [ADR-0018](../ADR/0018-release-posts-to-settlement-in-transit.md) —
what a release posts, and what an auditor sees mid-flight

---

## What this script is for

The happy path of settlement is uninteresting: money goes out, the scheme says
yes, everyone is pleased. **This script is about the other four cases**, because
they are the ones that cost money in the world CloFin simulates:

1. a batch where some payments settle and others come back;
2. a scheme that answers the **same thing twice**;
3. a scheme that answers **out of order**;
4. a scheme that **never answers at all**, so nobody knows whether the money
   arrived.

Case 4 is the one to watch. The temptation everywhere in this industry is to
treat "no answer" as "failed" and try again. You will attempt exactly that in
step 8, by hand, and watch the database refuse you.

If a step *succeeds* where this script says it should fail, stop and raise a
defect. That is what this document exists to detect.

> **Everything here is simulated.** CloFin is not connected to any bank, payment
> scheme or central bank and holds no regulatory authorisation. The only scheme
> names the database accepts are `SIM-RTGS` and `SIM-ACH`; you will try a real
> one in step 2 and be refused.

---

## Before you start

| | |
|---|---|
| Prerequisite | `make up` has completed and `make ready` answers |
| Tools | `curl`, `jq`, `psql` (via `make db-shell`) |
| Time | About 30 minutes |
| Data | Synthetic only |

```sh
export BASE=http://localhost:8080
```

### How the simulated scheme decides

You do not have to guess what will happen — **you choose it**, by choosing the
creditor account. The simulated scheme reads the last digit:

| Last digit of `creditorAccount` | What the scheme does |
|---|---|
| `0`–`6` | settles |
| `7`, `8` | returns the payment |
| `9` | **never answers** |

That rule is in `clofin.settlement.scheme`, in the OpenAPI description of
`recordSchemeResponse`, and here. Three copies on purpose: a simulation whose
behaviour you can only discover by reading source is a simulation you cannot
review.

---

## Step 1 — An organisation that can settle

Settlement touches three accounts. An organisation missing any of them cannot
settle, and you will see it say so.

```sh
ORG=$(curl -sS -X POST $BASE/organisations -H 'content-type: application/json' \
  -d '{"legalName":"Meridian Freight Holdings Pte Ltd","shortName":"uat006"}' | jq -r .id)

# Seed the actors. There is no endpoint that creates one, deliberately —
# an actor that could grant itself a role would make maker–checker unenforceable.
make db-shell
```

```sql
-- inside psql; substitute your ORG
insert into actor (id, organisation_id, display_name)
values ('11111111-0000-0000-0000-000000000001', '<ORG>', 'Maker'),
       ('11111111-0000-0000-0000-000000000002', '<ORG>', 'Checker'),
       ('11111111-0000-0000-0000-000000000003', '<ORG>', 'Controller');
insert into actor_role (actor_id, role) values
  ('11111111-0000-0000-0000-000000000001','operator'),
  ('11111111-0000-0000-0000-000000000002','approver'),
  ('11111111-0000-0000-0000-000000000003','controller');
insert into approver_limit (actor_id, currency, limit_minor)
values ('11111111-0000-0000-0000-000000000002','SGD',100000000);
insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
values ('<ORG>','SGD',0,1);
\q
```

```sh
export MAKER=11111111-0000-0000-0000-000000000001
export CHECKER=11111111-0000-0000-0000-000000000002
export CTRL=11111111-0000-0000-0000-000000000003

for a in '1100-CLIENT-FUNDS asset' '1300-IN-TRANSIT asset' '2100-CLIENT-PAYABLE liability'; do
  set -- $a
  curl -sS -X POST $BASE/accounts -H "x-actor-id: $CTRL" -H 'content-type: application/json' \
    -d "{\"organisationId\":\"$ORG\",\"code\":\"$1\",\"name\":\"$1\",\"type\":\"$2\",\"currency\":\"SGD\"}" \
    | jq -r '.code + " " + .id'
done
```

**Expected:** three accounts. Keep the `1100-CLIENT-FUNDS` id as `$FUNDS` and
the `1300-IN-TRANSIT` id as `$TRANSIT`.

---

## Step 2 — A real scheme name is refused

Before anything else, confirm the boundary this product will not cross.

```sh
curl -sS -o /dev/null -w '%{http_code}\n' -X POST $BASE/settlement-batches \
  -H "x-actor-id: $CTRL" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"scheme\":\"SWIFT\",\"currency\":\"SGD\",
       \"valueDate\":\"2026-12-01\",\"instructionIds\":[]}"
```

**Expected: `400`.** CloFin settles against simulated schemes only, and the
`SIM-` prefix is a database check constraint rather than a convention.

---

## Step 3 — Three approved payments, with outcomes you chose

Raise, submit and approve three payments. Note the creditor accounts — they are
how you are choosing what the scheme will do.

```sh
raise () {  # $1 = creditor account suffix
  ID=$(curl -sS -X POST $BASE/payment-instructions \
    -H "x-actor-id: $MAKER" -H "idempotency-key: $(uuidgen)" -H 'content-type: application/json' \
    -d "{\"organisationId\":\"$ORG\",\"debtorAccountId\":\"$FUNDS\",
         \"creditorName\":\"Pacific Rim Logistics Pte Ltd\",
         \"creditorAccount\":\"SG-SYNTH-8801234$1\",
         \"amount\":{\"currency\":\"SGD\",\"minorUnits\":125000},
         \"valueDate\":\"2026-12-01\",\"purposeCode\":\"SUPP\"}" | jq -r .id)
  curl -sS -o /dev/null -X POST $BASE/payment-instructions/$ID/submission \
    -H "x-actor-id: $MAKER" -H "idempotency-key: $(uuidgen)" -H 'content-type: application/json' \
    -d "{\"organisationId\":\"$ORG\"}"
  curl -sS -o /dev/null -X POST $BASE/payment-instructions/$ID/approvals \
    -H "x-actor-id: $CHECKER" -H "idempotency-key: $(uuidgen)" -H 'content-type: application/json' \
    -d "{\"organisationId\":\"$ORG\",\"decision\":\"approved\"}"
  echo $ID
}

SETTLES=$(raise 0)   # will settle
RETURNS=$(raise 7)   # will come back
SILENT=$(raise 9)    # will never be answered
```

---

## Step 4 — An unapproved payment cannot be batched

Raise one more and leave it in `draft`.

```sh
DRAFT=$(curl -sS -X POST $BASE/payment-instructions \
  -H "x-actor-id: $MAKER" -H "idempotency-key: $(uuidgen)" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"debtorAccountId\":\"$FUNDS\",
       \"creditorName\":\"Pacific Rim Logistics Pte Ltd\",\"creditorAccount\":\"SG-SYNTH-88012340\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":100},
       \"valueDate\":\"2026-12-01\",\"purposeCode\":\"SUPP\"}" | jq -r .id)

curl -sS -X POST $BASE/settlement-batches -H "x-actor-id: $CTRL" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"scheme\":\"SIM-RTGS\",\"currency\":\"SGD\",
       \"valueDate\":\"2026-12-01\",\"instructionIds\":[\"$SETTLES\",\"$DRAFT\"]}" | jq '.status, .errors'
```

**Expected: `422`,** with `errors.refused` naming `$DRAFT` and the reason
`not-approved` — and **nothing created**. One refusal lists every ineligible
instruction, so an operator batching forty payments fixes them in one pass.

---

## Step 5 — An operator cannot settle

```sh
curl -sS -o /dev/null -w '%{http_code}\n' -X POST $BASE/settlement-batches \
  -H "x-actor-id: $MAKER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"scheme\":\"SIM-RTGS\",\"currency\":\"SGD\",
       \"valueDate\":\"2026-12-01\",\"instructionIds\":[\"$SETTLES\"]}"
```

**Expected: `403`,** naming `settlement/execute`. Try the same with `$CHECKER`
— also `403`. **No role holds both `payment/approve` and `settlement/execute`:**
the actor who agreed a payment is never the actor who pushes it out of the door.

---

## Step 6 — Batch and release, and watch the money go somewhere visible

```sh
BATCH=$(curl -sS -X POST $BASE/settlement-batches -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"scheme\":\"SIM-RTGS\",\"currency\":\"SGD\",
       \"valueDate\":\"2026-12-01\",\"instructionIds\":[\"$SETTLES\",\"$RETURNS\",\"$SILENT\"]}" \
  | jq -r .id)

curl -sS -X POST $BASE/settlement-batches/$BATCH/submit -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' -d "{\"organisationId\":\"$ORG\"}" \
  | jq '{status, itemCount, schemeResponses: [.schemeResponses[].kind]}'
```

**Expected:** `status` is `submitted`, three items, and one `ack`.

Now look at the ledger. Every instruction is `released`, and the money is
somewhere an auditor can point at:

```sh
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -sS "$BASE/accounts/$TRANSIT/statement?organisationId=$ORG&from=2020-01-01T00:00:00Z&to=$NOW" \
  -H "x-actor-id: $CTRL" | jq '.closingBalance'
```

**Expected: SGD 3,750.00 in `1300-IN-TRANSIT`** — three payments of 1,250.00
released and not yet settled. **This is the clearing exposure**, readable from
the ledger alone. Design (b) in ADR-0018 would have left this account empty and
`1100-CLIENT-FUNDS` overstating available funds by the same amount.

---

## Step 7 — Partial batch failure, a duplicate, and an out-of-order answer

**7a. One settles.**

```sh
curl -sS -X POST $BASE/settlement-batches/$BATCH/scheme-responses -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"kind\":\"settled\",\"instructionId\":\"$SETTLES\",
       \"reference\":\"SIM-STL-1\"}" | jq '{status, outcome, replayed}'
```

**Expected:** `status` still `submitted` (two items outstanding), `replayed`
false.

**7b. The scheme says the same thing again.** This is not an error — it is
Tuesday.

```sh
curl -sS -X POST $BASE/settlement-batches/$BATCH/scheme-responses -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"kind\":\"settled\",\"instructionId\":\"$SETTLES\",
       \"reference\":\"SIM-STL-1\"}" | jq '{replayed, outcome, responses: (.schemeResponses|length)}'
```

**Expected: `200` with `replayed: true` and `outcome: "settled"`,** and the
response count **unchanged**. The `outcome` matters: a replay reproduces the
*original answer*, and reporting `null` there was audit finding **F-009** —
a caller could not tell a replayed settlement from a replayed nothing.

Confirm no second posting was made:

```sh
make db-shell
```

```sql
select count(*) from journal_entry
 where reference_id = '<SETTLES>';   -- expect 2: one release, one settlement
select count(*) from audit_event
 where subject_id = '<SETTLES>' and action = 'payment.settled';   -- expect 1
```

**A duplicate must cost nothing.** Two entries and one event, or raise a defect.

**7c. An out-of-order answer.** The scheme now claims the settled payment came
back.

```sh
curl -sS -o /dev/null -w '%{http_code}\n' -X POST $BASE/settlement-batches/$BATCH/scheme-responses \
  -H "x-actor-id: $CTRL" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"kind\":\"returned\",\"instructionId\":\"$SETTLES\",
       \"reference\":\"SIM-RTN-LATE\",\"reason\":\"too late\"}"
```

**Expected: `409`.** This is a *new* response — its replay key is free — for an
item that already has an outcome. Silently overwriting it would be a settled
payment turning into a returned one on the say-so of a late message.

**7c(ii). And the refusal is still evidence.** A message that arrived is a fact
whether or not CloFin could act on it. Look for it:

```sql
select kind, reference, disposition, disposition_reason, outcome
  from scheme_response where reference = 'SIM-RTN-LATE';
```

**Expected: one row**, `disposition = 'refused'`,
`disposition_reason = 'item-already-resolved'`, `outcome` null. Before audit
finding **F-008** there was **no row**: the conflict rolled its own receipt back,
so the first delivery of a rejected message was unprovable and the identical
reference could perform work later, once state had moved. Send it again and you
get the same `409` — the stored answer reproduced, not re-derived:

```sh
curl -sS -X POST $BASE/settlement-batches/$BATCH/scheme-responses -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"kind\":\"returned\",\"instructionId\":\"$SETTLES\",
       \"reference\":\"SIM-RTN-LATE\",\"reason\":\"too late\"}" \
  | jq '{status, replayed: .errors.replayed, why: .errors.dispositionReason}'
```

**Expected: `409` again, with `replayed: true`** — and still exactly one row.

**7d. One comes back.**

```sh
curl -sS -X POST $BASE/settlement-batches/$BATCH/scheme-responses -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"kind\":\"returned\",\"instructionId\":\"$RETURNS\",
       \"reference\":\"SIM-RTN-1\",\"reason\":\"SIM-RETURN: beneficiary account closed\"}" \
  | jq '{status, exceptions}'
```

**Expected:** the returned item appears under `exceptions` **with its reason** —
the queue an operator actually works — and the instruction is now `returned`.
The batch is still `submitted`: one item has not answered.

---

## Step 8 — The one that matters: nobody answered

`$SILENT` has had no response and never will.

```sh
curl -sS -X POST $BASE/settlement-batches/$BATCH/timeout-sweep -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' -d "{\"organisationId\":\"$ORG\",\"timeoutSeconds\":0}" \
  | jq '{status, timedOut}'
```

**Expected:** `timedOut` contains `$SILENT`, and the batch derives to
`partially-settled` — one settled, one returned, one unknown.

Now check what happened to the **payment**:

```sh
curl -sS "$BASE/payment-instructions/$SILENT?organisationId=$ORG" -H "x-actor-id: $MAKER" | jq -r .status
```

**Expected: `released`.** *Not* `failed`. CloFin does not know what happened to
this money and does not pretend to.

### 8b — Attempt the thing that costs real money

Try to put it in a new batch, as an operator convinced it must have failed:

```sh
curl -sS -X POST $BASE/settlement-batches -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"scheme\":\"SIM-ACH\",\"currency\":\"SGD\",
       \"valueDate\":\"2026-12-01\",\"instructionIds\":[\"$SILENT\"]}" | jq '.status, .errors'
```

**Expected: `422`** — the instruction is still `released`, so it is not
approved and cannot be batched.

Now bypass the application entirely, as a defect or a fix-up script would:

```sh
make db-shell
```

```sql
insert into settlement_batch (id, organisation_id, scheme, currency, value_date, created_by)
values (gen_random_uuid(), '<ORG>', 'SIM-ACH', 'SGD', '2026-12-01',
        '11111111-0000-0000-0000-000000000003')
returning id;

-- substitute the returned batch id and the SILENT instruction id
insert into settlement_batch_item (batch_id, instruction_id)
values ('<NEW BATCH>', '<SILENT>');
```

**Expected:**

```
ERROR:  duplicate key value violates unique constraint "settlement_item_instruction_key"
```

**This is the control.** Not a check in a handler — a unique index, so it binds
SQL run by hand exactly as it binds the API.

**8c — and the same is true of a payment that came *back*.** Run the identical
insert for `$RETURNS`, the payment the scheme returned in step 7d:

```sql
insert into settlement_batch_item (batch_id, instruction_id)
values ('<NEW BATCH>', '<RETURNS>');
```

**Expected: the same refusal.** This is the one worth doing by hand, because
until migration `0010` it **succeeded** — the index excepted `returned`, and the
Milestone 2 audit committed exactly this row while the API answered `422` to the
same retry. The schema was advertising a permission no workflow could reach
(finding **F-007**; a schema path is not a product path, standing lesson
**L-10**).

The retry a returned payment gets is a **new instruction**, approved on its own
merits — the doctrine a settled payment already follows, where a correction is a
new reversing instruction. Prove it works:

```sh
RETRY=$(raise 1)     # a NEW instruction, raised, submitted and approved by `raise`
curl -sS -X POST $BASE/settlement-batches -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"scheme\":\"SIM-ACH\",\"currency\":\"SGD\",
       \"valueDate\":\"2026-12-01\",\"instructionIds\":[\"$RETRY\"]}" | jq -r .status
```

**Expected: `open`.** A returned payment is finished; the money's second attempt
is a second payment decision, and it gets a second maker–checker cycle. See
[ADR-0019](../ADR/0019-a-returned-payment-is-terminal-and-retries-as-a-new-instruction.md).

---

## Step 9 — The late answer

The scheme finally says what happened.

```sh
curl -sS -X POST $BASE/settlement-batches/$BATCH/scheme-responses -H "x-actor-id: $CTRL" \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"kind\":\"timeout-resolution\",\"instructionId\":\"$SILENT\",
       \"reference\":\"SIM-TMO-1\",\"outcome\":\"settled\"}" | jq '{status, outcome}'
```

**Expected:** the item resolves to `settled`, the instruction becomes `settled`,
its finality entry posts now, and the batch derives to `partially-settled`
(two settled, one returned).

Try to resolve it a second time:

```sh
curl -sS -o /dev/null -w '%{http_code}\n' -X POST $BASE/settlement-batches/$BATCH/scheme-responses \
  -H "x-actor-id: $CTRL" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"kind\":\"timeout-resolution\",\"instructionId\":\"$SILENT\",
       \"reference\":\"SIM-TMO-2\",\"outcome\":\"returned\",\"reason\":\"changed my mind\"}"
```

**Expected: `409`.** A timeout resolves exactly once.

---

## Step 10 — The ledger still adds up

```sh
make db-shell
```

```sql
-- Any entry whose lines do not net to zero, per currency. Expect no rows.
select e.id
  from journal_entry e join journal_line l on l.entry_id = e.id
 group by e.id, l.currency
having sum(case when l.direction='debit'  then l.amount_minor else 0 end)
    <> sum(case when l.direction='credit' then l.amount_minor else 0 end);
```

**Expected: no rows**, whatever mix of outcomes you produced.

And the clearing exposure has drained to just the returned payment's unwind:

```sh
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -sS "$BASE/accounts/$TRANSIT/statement?organisationId=$ORG&from=2020-01-01T00:00:00Z&to=$NOW" \
  -H "x-actor-id: $CTRL" | jq '.closingBalance'
```

**Expected: zero.** Three payments went out; two settled and one came back, so
nothing is in flight.

---

## Step 11 — The trail an auditor reads

```sh
curl -sS "$BASE/audit/evidence/$SETTLES?organisationId=$ORG" -H "x-actor-id: $CTRL" \
  | jq '[.events[].action]'
```

**Expected**, in order:

```json
["payment.created","payment.submitted","approval.recorded","payment.approved",
 "payment.released","payment.settled"]
```

And for the batch:

```sh
curl -sS "$BASE/audit/evidence/$BATCH?organisationId=$ORG" -H "x-actor-id: $CTRL" \
  | jq '{subjectType, actions: [.events[].action]}'
```

**Expected:** `subjectType` is `settlement-batch`, and the actions include
`settlement-batch.created`, `.submitted`, `.timeout-swept` and exactly **one**
`.completed`.

Two things to check deliberately:

- There is **one** `settlement-batch.completed`, written when the last item
  resolved — not one per response. A batch with items outstanding is not
  completed.
- There is **no** payment-level event from the sweep. Nothing about `$SILENT`'s
  *payment* changed when CloFin stopped waiting; only the item did.

---

## Step 12 — Scheme responses cannot be edited

```sql
update scheme_response set reference = 'edited';
update scheme_response set disposition = 'applied';   -- including the receipt's verdict
delete from scheme_response;
truncate scheme_response;
```

**Expected:** all four refused —

```
ERROR:  Table scheme_response is append-only: correct a posted entry with a
        reversing entry, never by update / delete / truncate
```

All three verbs, because `TRUNCATE` is a separate trigger event with its own
privilege and visits no rows — the gap audit finding **F-002** found in the audit
table.

> A trigger binds the application, not the table's **owner**, and CloFin
> currently connects as the owner. The runtime role split that would close that
> is named debt in [COMPLIANCE §4](../COMPLIANCE.md).

---

## What you have just verified

| | |
|---|---|
| A batch is one scheme, one currency, one value date | Step 4 |
| Real scheme names are impossible, not discouraged | Step 2 |
| Settlement is a controller's right, and never an approver's | Step 5 |
| A release posts, and clearing exposure is visible in the ledger | Step 6, 10 |
| Partial batch failure is ordinary | Step 7 |
| A duplicate response costs nothing, and replays its original answer | Step 7b |
| A late contradiction is refused, not applied — and is kept as evidence | Step 7c |
| A refused message replays its refusal rather than being re-evaluated | Step 7c(ii) |
| **Unknown is not failed, and cannot be re-batched — including from SQL** | **Step 8** |
| **A returned payment is terminal too; the retry is a new instruction** | **Step 8c** |
| A timeout resolves exactly once | Step 9 |
| The zero-sum invariant survives every outcome mix | Step 10 |
| The trail names one event per thing that happened | Step 11 |
| Scheme responses resist all three destructive verbs | Step 12 |
