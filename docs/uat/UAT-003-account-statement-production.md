# UAT-003 — An account statement can be produced and explains itself

**Requirements:** PR-020, PR-021, PR-023, PR-024 · **Controls:** C-03, C-04
**Prerequisites:** UAT-001 passed; the stack is running (`make up`)
**Estimated duration:** 25 minutes

## Purpose

[UAT-002](UAT-002-ledger-integrity.md) proves the ledger cannot be corrupted by
someone with database access. This script proves the opposite thing: that the
ledger, used *correctly and only through the API*, produces a statement a
finance reviewer would accept.

The question being answered is the one a customer asks when they dispute a
figure: **"why is the balance this number?"** A passing run of this script
answers it with a list of movements that add up, and shows that the balance was
never stored anywhere to begin with.

Every step is an HTTP call. The database is not touched directly at any point —
that is the difference between this script and UAT-002.

Set a base URL first:

```bash
export CLOFIN=http://localhost:8080
```

Each step shows the command and what to look for. Record the actual output as
evidence.

---

## Setup — an organisation and a chart of accounts

```bash
curl -sS -X POST $CLOFIN/organisations \
  -H 'content-type: application/json' \
  -d '{"legalName":"Meridian Freight Holdings Pte Ltd","shortName":"meridian-uat3"}'
```

**Expected:** `201`, and a body carrying an `id`. Keep it:

```bash
export ORG=<the id from the response>
```

Open two accounts — a pooled client-money asset account, and the liability owed
against it:

```bash
curl -sS -X POST $CLOFIN/accounts -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","code":"1100-CLIENT-FUNDS",
       "name":"Client funds - pooled","type":"asset","currency":"SGD"}'

curl -sS -X POST $CLOFIN/accounts -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","code":"2100-CLIENT-PAYABLE",
       "name":"Client payable","type":"liability","currency":"SGD"}'
```

**Expected:** `201` each.

```bash
export CASH=<id of 1100-CLIENT-FUNDS>
export PAYABLE=<id of 2100-CLIENT-PAYABLE>
```

**Pass criterion:** both accounts report `"status":"active"`, and the asset
account reports `"normalBalance":"debit"` while the liability reports
`"credit"`. That field is what makes the sign of every later figure readable.

---

## Steps

### Step 1 — A balanced entry is accepted

An opening balance of SGD 1,250.00 — note that the amount travels as **125000
minor units**, not as `1250.00`. Money is never a decimal on the wire.

```bash
curl -sS -i -X POST $CLOFIN/journal-entries -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","occurredAt":"2026-02-05T09:00:00Z",
       "narrative":"Opening balance",
       "reference":{"type":"opening-balance","id":"55555555-5555-5555-5555-555555555555"},
       "lines":[{"accountId":"'$CASH'","direction":"debit","amount":{"currency":"SGD","minorUnits":125000}},
                {"accountId":"'$PAYABLE'","direction":"credit","amount":{"currency":"SGD","minorUnits":125000}}]}'
```

**Expected:** `201`, with a `location` header pointing at the created entry.

**Pass criterion:** following that `location` with a `GET` returns the entry
with both of its lines (PR-020).

---

### Step 2 — An unbalanced entry is refused, and says what is missing

```bash
curl -sS -X POST $CLOFIN/journal-entries -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","occurredAt":"2026-02-06T09:00:00Z",
       "narrative":"Deliberately unbalanced",
       "reference":{"type":"opening-balance","id":"55555555-5555-5555-5555-555555555555"},
       "lines":[{"accountId":"'$CASH'","direction":"debit","amount":{"currency":"SGD","minorUnits":125000}},
                {"accountId":"'$PAYABLE'","direction":"credit","amount":{"currency":"SGD","minorUnits":100000}}]}'
```

**Expected:** `422`, with a body containing:

```json
"errors": { "imbalance": { "SGD": "250.00" } }
```

**Pass criterion:** the refusal names **which currency** is out and **by how
much**, in major units a finance reviewer can read. A reviewer should be able to
act on this response without an engineer translating it (PR-020, C-04).

---

### Step 3 — Nothing from the refused entry was written

```bash
curl -sS "$CLOFIN/accounts/$CASH/statement?organisationId=$ORG&from=2026-01-01T00:00:00Z&to=2027-01-01T00:00:00Z"
```

**Expected:** exactly **one** movement — the opening balance from Step 1. The
rejected entry appears nowhere.

**Pass criterion:** a refused entry leaves no trace at all. There is no partial
entry and no orphaned line (C-04).

---

### Step 4 — Post the rest of a month's activity

```bash
# A deposit
curl -sS -X POST $CLOFIN/journal-entries -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","occurredAt":"2026-02-10T09:00:00Z",
       "narrative":"Client deposit",
       "reference":{"type":"payment-instruction","id":"66666666-6666-6666-6666-666666666666"},
       "lines":[{"accountId":"'$CASH'","direction":"debit","amount":{"currency":"SGD","minorUnits":25000}},
                {"accountId":"'$PAYABLE'","direction":"credit","amount":{"currency":"SGD","minorUnits":25000}}]}'

# A withdrawal — note the directions are the other way round
curl -sS -X POST $CLOFIN/journal-entries -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","occurredAt":"2026-02-20T09:00:00Z",
       "narrative":"Client withdrawal",
       "reference":{"type":"payment-instruction","id":"77777777-7777-7777-7777-777777777777"},
       "lines":[{"accountId":"'$PAYABLE'","direction":"debit","amount":{"currency":"SGD","minorUnits":5000}},
                {"accountId":"'$CASH'","direction":"credit","amount":{"currency":"SGD","minorUnits":5000}}]}'
```

**Expected:** `201` each.

---

### Step 5 — The statement adds up

This is the step the endpoint exists for.

```bash
curl -sS "$CLOFIN/accounts/$CASH/statement?organisationId=$ORG&from=2026-02-01T00:00:00Z&to=2026-03-01T00:00:00Z"
```

**Expected:** three movements, and:

| Field | Expected |
|---|---|
| `openingBalance.minorUnits` | `0` |
| movements | `+125000`, `+25000`, `−5000` |
| `closingBalance.minorUnits` | `145000` |
| last `runningBalance.minorUnits` | `145000` |
| `truncated` | `false` |

**Pass criteria — check all three:**

1. **Opening plus the movements equals closing.** Add them up by hand. This is
   the arithmetic a reviewer will do, and it must come out.
2. **The last `runningBalance` equals `closingBalance`.** The running column is
   what lets a reader find *where* a disputed figure came from, rather than
   only *what* it is.
3. Every movement carries a `narrative` and an `occurredAt`, so each line is
   explainable without another lookup (PR-023).

---

### Step 6 — The period boundary belongs to exactly one period

Ask for January, which ends exactly where February begins:

```bash
curl -sS "$CLOFIN/accounts/$CASH/statement?organisationId=$ORG&from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z"
```

**Expected:** no movements, and `closingBalance` of `0`.

**Pass criterion:** January's `closingBalance` equals February's
`openingBalance` (both `0`). The period is half-open — `from` is included, `to`
is not — so a movement on the boundary is counted once, in one period, never in
both and never in neither. See
[ADR-0011](../ADR/0011-statement-periods-ordering-and-row-caps.md).

> If this step is run against a period boundary that *does* have a movement on
> it, the same rule applies: the movement belongs to the period that begins at
> that instant.

---

### Step 7 — The balance was never stored

```bash
curl -sS "$CLOFIN/accounts/$CASH?organisationId=$ORG"
```

**Expected:** the account document has **no balance field at all** — only `id`,
`organisationId`, `code`, `name`, `type`, `currency`, `status` and
`normalBalance`.

**Pass criterion:** there is nowhere in this system for a balance to be stored
and go stale. Every figure in Step 5 was derived from the movements listed
beside it (PR-021,
[ADR-0008](../ADR/0008-double-entry-journal-as-source-of-truth.md)).

---

### Step 8 — A correction is a reversal, and both remain visible

Reverse the withdrawal from Step 4. Every direction is flipped; the amount is
unchanged.

```bash
export WITHDRAWAL=<id of the withdrawal entry from Step 4>

curl -sS -X POST $CLOFIN/journal-entries -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","occurredAt":"2026-02-21T09:00:00Z",
       "narrative":"Reversal of client withdrawal - posted in error",
       "reference":{"type":"reversal","id":"'$WITHDRAWAL'"},
       "lines":[{"accountId":"'$CASH'","direction":"debit","amount":{"currency":"SGD","minorUnits":5000}},
                {"accountId":"'$PAYABLE'","direction":"credit","amount":{"currency":"SGD","minorUnits":5000}}]}'
```

**Expected:** `201`. Re-run the Step 5 statement.

**Pass criteria:**

1. `closingBalance` is back to `150000` — the withdrawal has been undone.
2. The statement now shows **four** movements: the withdrawal **and** its
   reversal are both listed. Neither the original entry nor its narrative has
   changed.

That second point is the control. An amendment that erased the mistake would
leave a correct balance and no way to discover the error had happened (PR-022,
C-03).

---

### Step 9 — An entry can be reversed only once

Repeat the Step 8 request exactly.

**Expected:** `409`.

**Pass criterion:** a second reversal would silently reapply the original
movement — a withdrawal reversed twice becomes a deposit. It is refused.

---

### Step 10 — Multiple currencies balance within each

Open two JPY accounts and post an entry touching both currencies. Note that JPY
has **no minor unit**: `125000` here means ¥125,000, where the same integer in
SGD meant $1,250.00.

```bash
curl -sS -X POST $CLOFIN/accounts -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","code":"1200-JPY-FUNDS","name":"JPY funds","type":"asset","currency":"JPY"}'
curl -sS -X POST $CLOFIN/accounts -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","code":"2200-JPY-PAYABLE","name":"JPY payable","type":"liability","currency":"JPY"}'

export JPY_CASH=<id of 1200-JPY-FUNDS>
export JPY_OWED=<id of 2200-JPY-PAYABLE>

curl -sS -X POST $CLOFIN/journal-entries -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","occurredAt":"2026-02-25T09:00:00Z",
       "narrative":"Two currencies, balanced within each",
       "reference":{"type":"fx-conversion","id":"88888888-8888-8888-8888-888888888888"},
       "lines":[{"accountId":"'$CASH'","direction":"debit","amount":{"currency":"SGD","minorUnits":10000}},
                {"accountId":"'$PAYABLE'","direction":"credit","amount":{"currency":"SGD","minorUnits":10000}},
                {"accountId":"'$JPY_CASH'","direction":"debit","amount":{"currency":"JPY","minorUnits":125000}},
                {"accountId":"'$JPY_OWED'","direction":"credit","amount":{"currency":"JPY","minorUnits":125000}}]}'
```

**Expected:** `201`.

Now try one that balances *overall* but not within each currency:

```bash
curl -sS -X POST $CLOFIN/journal-entries -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","occurredAt":"2026-02-26T09:00:00Z",
       "narrative":"Nets out across currencies - not the same thing",
       "reference":{"type":"fx-conversion","id":"99999999-9999-9999-9999-999999999999"},
       "lines":[{"accountId":"'$CASH'","direction":"debit","amount":{"currency":"SGD","minorUnits":1000}},
                {"accountId":"'$JPY_OWED'","direction":"credit","amount":{"currency":"JPY","minorUnits":1000}}]}'
```

**Expected:** `422`, naming **both** SGD and JPY in `errors.imbalance`.

**Pass criterion:** currencies do not subsidise one another. A debit in SGD is
not settled by a credit in JPY, whatever the numbers look like (PR-024).

---

### Step 11 — An account that cannot accept postings says so

Freeze the JPY account. Account lifecycle is not yet an API operation — that is
TASK-003 — so this one step uses the database:

```bash
make db-shell
```

```sql
update ledger_account set status = 'frozen' where code = '1200-JPY-FUNDS';
```

Then attempt to post to it:

```bash
curl -sS -X POST $CLOFIN/journal-entries -H 'content-type: application/json' \
  -d '{"organisationId":"'$ORG'","occurredAt":"2026-02-27T09:00:00Z",
       "narrative":"Posting to a frozen account",
       "reference":{"type":"payment-instruction","id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"},
       "lines":[{"accountId":"'$JPY_CASH'","direction":"debit","amount":{"currency":"JPY","minorUnits":100}},
                {"accountId":"'$JPY_OWED'","direction":"credit","amount":{"currency":"JPY","minorUnits":100}}]}'
```

**Expected:** `422`, with `errors.accounts` naming the account by **id, code and
status**.

**Pass criterion:** the response tells the operator which account to go and
unfreeze, by the code they know it as — not merely that something was refused.

---

### Step 12 — One organisation cannot reach another's accounts

Create a second organisation and try to read the first one's account with it:

```bash
curl -sS -X POST $CLOFIN/organisations -H 'content-type: application/json' \
  -d '{"legalName":"Kestrel Logistics Pte Ltd","shortName":"kestrel-uat3"}'

export OTHER=<the new organisation id>

curl -sS -i "$CLOFIN/accounts/$CASH?organisationId=$OTHER"
```

**Expected:** `404` — the same answer an id that does not exist would receive.

**Pass criterion:** the response does **not** distinguish "belongs to someone
else" from "does not exist". Confirming the former would disclose that an id is
in use by another tenant to anyone able to guess a UUID.

> **Record this limitation on the result sheet.** This is tenancy scoping, not
> access control. The caller states which organisation they are acting as and
> is believed, because authentication does not exist yet — it is TASK-003.
> Nothing in this step should be reported as an access control being tested.

---

## Teardown

The journal is append-only against `UPDATE`, `DELETE` **and** `TRUNCATE`
(migration `0007`), so the tables written by this script cannot be emptied in
place. Reset the whole environment instead:

```bash
make db-reset
```

---

## Result

| Step | Result | Evidence | Notes |
|---|---|---|---|
| Setup — organisation and accounts | | | |
| 1 Balanced entry accepted | | | |
| 2 Unbalanced entry refused, shortfall named | | | |
| 3 Refused entry left no trace | | | |
| 4 Month's activity posted | | | |
| 5 Statement adds up; running reaches closing | | | |
| 6 Boundary belongs to exactly one period | | | |
| 7 No balance is stored anywhere | | | |
| 8 Reversal corrects; both entries visible | | | |
| 9 Second reversal refused | | | |
| 10 Currencies balance within each | | | |
| 11 Frozen account named in the refusal | | | |
| 12 Cross-organisation read is 404 | | | |

**Overall:** Pass / Fail
**Executed by:** ____________ **Date:** ____________ **Build:** ____________

**Known limitations to record alongside the result:**

- There is **no authentication or authorisation**. Step 12 tests tenancy
  scoping only. Do not report it as access control (TASK-003).
- There is **no idempotency**. Re-sending a `POST /journal-entries` posts a
  second entry; it does not return the first (TASK-002).
- Statements return at most 500 movements and set `truncated` when there were
  more. `closingBalance` remains correct in that case, but the running balance
  column does not reach it.
