# UAT-004 — A retried submission cannot pay twice

**Requirements:** PR-001, PR-003, PR-004, PR-040, PR-041, PR-042 · **Controls:** C-06
**Prerequisites:** UAT-001 passed; the stack is running (`make up`)
**Estimated duration:** 25 minutes

> **Numbering.** The TASK-002 brief asked for this script as `UAT-003`. That
> number belongs to [UAT-003 — Account statement production](UAT-003-account-statement-production.md),
> delivered by TASK-001 and merged in PR #2. UAT numbers are sequential and
> never reused, so this is `UAT-004`. Recorded as an objection in
> [`../audits/002-REQ-payment-instruction-lifecycle.md`](../audits/002-REQ-payment-instruction-lifecycle.md).

## Purpose

This script exists for one scenario, and it is the scenario the whole increment
was built for:

> An operator submits a payment. The connection times out. They do not know
> whether the payment went out. They press submit again.

Without an idempotency key, that second press is a second payment. This script
performs that double submission **by hand**, and shows that CloFin answers the
operator's real question — *did it go out?* — without acting twice.

A reviewer executing this needs `curl` and a shell. Every step's expected
outcome is stated before you run it, so a surprising result is visible rather
than rationalised afterwards.

> Synthetic data only. No organisation, counterparty or account identifier
> below corresponds to anything real, and CloFin is connected to no bank or
> payment scheme.

---

## Setup

```bash
export BASE=http://localhost:8080
```

Check the service is up and tell it apart from anything else you may be running:

```bash
curl -fsS $BASE/readyz
```

**Expected:** `"status": "ready"` and `"schemaVersion": "0004"` or higher. Below
`0003` the payment tables are not present — run `make migrate`.

### Create a synthetic organisation and a debtor account

```bash
ORG=$(curl -fsS -X POST $BASE/organisations \
  -H 'content-type: application/json' \
  -d '{"legalName":"Meridian Freight Holdings Pte Ltd","shortName":"meridian-uat4"}' \
  | sed 's/.*"id":"\([^"]*\)".*/\1/')
echo "organisation: $ORG"

ACCOUNT=$(curl -fsS -X POST $BASE/accounts \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"code\":\"1100-CLIENT-FUNDS\",
       \"name\":\"Client funds - pooled\",\"type\":\"asset\",\"currency\":\"SGD\"}" \
  | sed 's/.*"id":"\([^"]*\)".*/\1/')
echo "debtor account: $ACCOUNT"
```

**Expected:** two UUIDs printed. If `shortName` is already taken from a previous
run, change the suffix.

A value date must not be in the past, so derive one:

```bash
VALUE_DATE=$(date -u -d '+7 days' +%F 2>/dev/null || date -u -v+7d +%F)
echo "value date: $VALUE_DATE"
```

---

## Steps

### Step 1 — A mutating request with no `Idempotency-Key` is refused

```bash
curl -s -o /dev/stderr -w '%{http_code}\n' -X POST $BASE/payment-instructions \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"debtorAccountId\":\"$ACCOUNT\",
       \"creditorName\":\"Pacific Rim Logistics Pte Ltd\",
       \"creditorAccount\":\"SG-SYNTH-88012345\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":125000},
       \"valueDate\":\"$VALUE_DATE\",\"purposeCode\":\"SUPP\",
       \"createdBy\":\"99999999-9999-9999-9999-999999999999\"}"
```

**Expected:** `400`, and a problem document naming `Idempotency-Key`.

**Why it matters (PR-040).** A caller that has not thought about retries is
exactly the caller a retry will hurt. CloFin refuses rather than executing
quietly and leaving the caller to discover the problem during a reconciliation.

**Record:** status code, and the `detail` string.

---

### Step 2 — Three bad fields are all reported, not just the first

```bash
curl -s -X POST $BASE/payment-instructions \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: uat4-invalid-$(date +%s)" \
  -d "{\"organisationId\":\"$ORG\",\"debtorAccountId\":\"$ACCOUNT\",
       \"creditorName\":\"Pacific Rim Logistics Pte Ltd\",
       \"creditorAccount\":\"SG-SYNTH-88012345\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":0},
       \"valueDate\":\"2020-01-01\",\"purposeCode\":\"XXXX\",
       \"createdBy\":\"99999999-9999-9999-9999-999999999999\"}"
```

**Expected:** `422`, and an `errors` object with **all three** entries:

```json
"errors": {
  "amount": "must be greater than zero",
  "purposeCode": "unknown purpose code: XXXX",
  "valueDate": "must not be in the past"
}
```

**Why it matters (PR-003).** An operator fixing a rejected instruction should
need one round trip, not one per mistake. Count the entries: three, not one.

**Record:** the full `errors` object.

---

### Step 3 — Capture a valid instruction

```bash
IDEM_CREATE="uat4-create-$(date +%s)"

curl -s -D /dev/stderr -X POST $BASE/payment-instructions \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: $IDEM_CREATE" \
  -d "{\"organisationId\":\"$ORG\",\"debtorAccountId\":\"$ACCOUNT\",
       \"creditorName\":\"Pacific Rim Logistics Pte Ltd\",
       \"creditorAccount\":\"SG-SYNTH-88012345\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":125000},
       \"valueDate\":\"$VALUE_DATE\",\"purposeCode\":\"SUPP\",
       \"createdBy\":\"99999999-9999-9999-9999-999999999999\"}"
```

**Expected:** `201`, a `location` header, `"status": "draft"`, and
`"permittedTransitions": ["cancel","submit"]`.

Capture the id:

```bash
PI=$(curl -s "$BASE/payment-instructions?organisationId=$ORG" \
  | sed 's/.*"paymentInstructions":\[{"id":"\([^"]*\)".*/\1/')
echo "instruction: $PI"
```

**Why it matters (PR-001).** The instruction is created `draft`. A caller
cannot ask for it to arrive approved, because that would be an approval nobody
gave.

**Record:** the status code, the id, and `permittedTransitions`.

---

### Step 4 — **The double submission.** Submit once

This is the step the increment exists for. Choose one key and keep it — it
represents the key an operator's client would generate once per intended
action.

```bash
IDEM_SUBMIT="uat4-submit-$(date +%s)"

curl -s -D /dev/stderr -X POST "$BASE/payment-instructions/$PI/submission" \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: $IDEM_SUBMIT" \
  -d "{\"organisationId\":\"$ORG\"}"
```

**Expected:** `200`, `"status": "pending-approval"`, and **no**
`idempotent-replayed` header.

**Record:** the status code, the instruction status, and the response headers.

---

### Step 5 — Press submit again. Exactly the same request

Simulating the operator who saw a timeout and does not know whether it worked.

```bash
curl -s -D /dev/stderr -X POST "$BASE/payment-instructions/$PI/submission" \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: $IDEM_SUBMIT" \
  -d "{\"organisationId\":\"$ORG\"}"
```

**Expected:**

- `200` — **not** an error. The operator gets an answer, not a puzzle.
- A body **identical** to Step 4's, byte for byte.
- An `idempotent-replayed: true` header, which is how you can tell nothing new
  happened.

**Why it matters (PR-041, C-06).** This is the whole control. The second press
did not submit anything; it answered the operator's real question — *did it go
out?* — with the response the first press produced.

**Record:** the status, the `idempotent-replayed` header, and whether the two
bodies are identical. Diff them if you want the evidence:

```bash
diff <(curl -s -X POST "$BASE/payment-instructions/$PI/submission" \
        -H 'content-type: application/json' -H "Idempotency-Key: $IDEM_SUBMIT" \
        -d "{\"organisationId\":\"$ORG\"}") \
     <(curl -s -X POST "$BASE/payment-instructions/$PI/submission" \
        -H 'content-type: application/json' -H "Idempotency-Key: $IDEM_SUBMIT" \
        -d "{\"organisationId\":\"$ORG\"}") \
  && echo "IDENTICAL"
```

**Expected:** `IDENTICAL`.

---

### Step 6 — A retry that is reformatted is still a retry

An HTTP client that pretty-prints, or reorders a map, must not be told its
payment conflicts. Same key, same members, different bytes:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/payment-instructions/$PI/submission" \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: $IDEM_SUBMIT" \
  -d '{
        "organisationId" : "'"$ORG"'"
      }'
```

**Expected:** `200`, not `409`.

**Why it matters (ADR-0013).** A `409` here would push the caller to mint a new
key — and a new key is a second payment. The digest is taken over a canonical
form of the body precisely so that whitespace is not treated as substance.

---

### Step 7 — One key cannot be carried to a *different* payment

This is the step that closed a real gap. Create a second instruction, then try
to submit it with the key already used for the first:

```bash
curl -s -X POST $BASE/payment-instructions \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: uat4-create-second-$(date +%s)" \
  -d "{\"organisationId\":\"$ORG\",\"debtorAccountId\":\"$ACCOUNT\",
       \"creditorName\":\"Andaman Shipping Sdn Bhd\",
       \"creditorAccount\":\"MY-SYNTH-4471\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":50000},
       \"valueDate\":\"$VALUE_DATE\",\"purposeCode\":\"TRAD\",
       \"createdBy\":\"99999999-9999-9999-9999-999999999999\"}" > /dev/null

PI2=$(curl -s "$BASE/payment-instructions?organisationId=$ORG&status=draft" \
  | sed 's/.*"paymentInstructions":\[{"id":"\([^"]*\)".*/\1/')
echo "second instruction: $PI2"

curl -s -X POST "$BASE/payment-instructions/$PI2/submission" \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: $IDEM_SUBMIT" \
  -d "{\"organisationId\":\"$ORG\"}"
```

**Expected:** `409`.

**Why it matters.** Note that the body here is **byte-identical** to Step 4's —
a submission body is just `{"organisationId": …}`. Only the path differs. When
the digest covered the body alone, this returned `200` replaying the *first*
payment's response, and this second payment was never submitted while the
operator saw success. That gap was found during increment 3, disclosed rather
than quietly patched, and closed by ruling; the digest now covers the request's
method and path as well as its body
(ADR-0013 amendment 1).

Confirm the second instruction really is still a draft:

```bash
curl -s "$BASE/payment-instructions/$PI2?organisationId=$ORG"
```

**Expected:** `"status": "draft"` — and, crucially, the `409` above is how the
operator finds that out rather than being told it succeeded.

**Record:** the status code of the submission, and the second instruction's
status.

---

### Step 8 — The same key with a *different* body is refused

```bash
curl -s -X POST $BASE/payment-instructions \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: $IDEM_CREATE" \
  -d "{\"organisationId\":\"$ORG\",\"debtorAccountId\":\"$ACCOUNT\",
       \"creditorName\":\"Someone Else Pte Ltd\",
       \"creditorAccount\":\"SG-SYNTH-00000001\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":999999},
       \"valueDate\":\"$VALUE_DATE\",\"purposeCode\":\"SUPP\",
       \"createdBy\":\"99999999-9999-9999-9999-999999999999\"}"
```

**Expected:** `409`, with a problem document naming `Idempotency-Key`.

**Why it matters (PR-042).** Returning the stored response would tell the
caller that an amount it never sent had been accepted. Executing would be the
second payment. Neither is acceptable, so nothing runs.

**Record:** the status code and the `detail`.

---

### Step 9 — A submitted instruction can no longer be amended

```bash
curl -s -X PATCH "$BASE/payment-instructions/$PI" \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: uat4-amend-$(date +%s)" \
  -d "{\"organisationId\":\"$ORG\",\"amount\":{\"currency\":\"SGD\",\"minorUnits\":1}}"
```

**Expected:** `409`, and an `errors` object naming the state it is actually in:

```json
"errors": { "instruction-status": "pending-approval", "attempted": "amend", "mutable-in": ["draft"] }
```

Confirm the amount is unchanged:

```bash
curl -s "$BASE/payment-instructions/$PI?organisationId=$ORG"
```

**Expected:** `"minorUnits": 125000` — the original amount.

**Why it matters (PR-004).** A submitted instruction is under consideration by
someone else. Changing what they are considering, underneath them, is the
failure segregation of duties exists to prevent.

---

### Step 10 — Confirm exactly one effect, in the database

```bash
make db-shell
```

```sql
select count(*) as instructions from payment_instruction;
select status, count(*) from payment_instruction group by status;
select key, response_status, left(request_digest, 12) as digest, created_at
  from idempotency_key order by created_at;
```

**Expected:**

- **One** instruction, in status `pending-approval`. Steps 4, 5 and 6 pressed
  submit three times between them and moved it once.
- One `idempotency_key` row per *distinct* key used — the repeated submissions
  share one row, and its `created_at` is the time of the **first** press.
- No key row for the request rejected in Step 2: a failed request does not
  consume its key.

**Why it matters.** This is the evidence an auditor would ask for: the stored
response and its first-seen timestamp, showing that the retries were answered
rather than acted on.

---

## What this script does **not** demonstrate

Stated so that a reviewer does not read more into a pass than it earns.

| Not shown | Why |
|---|---|
| Approval, or that a payment cannot be approved by whoever submitted it | TASK-003. `submit` stops at `pending-approval` and no operation moves it further. |
| That an audit trail records who did any of this | TASK-003. There is no audit trail yet; `createdBy` is what a caller claimed. |
| Screening before submission | Increment 7. Submission is gated by the lifecycle alone. |
| Settlement, or any money actually moving | Increment 5. Nothing in this script posts a journal entry. |
| Authentication of any kind | TASK-003. `organisationId` is taken from the request, and is not an access control. |
| That two *simultaneous* retries produce one effect | Not reproducible by hand at a keyboard. Covered by `ac-9-two-concurrent-requests-with-one-key-produce-exactly-one-effect` in `clofin.api.payments-api-test`, which uses two threads and a latch. |

---

## Recording a result

Use the table in [`README.md`](README.md). A step with no evidence is not a
passed step — for this script, the evidence is the response headers and the
final SQL counts, not a recollection that it looked right.
