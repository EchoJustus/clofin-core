# UAT-007 — Reconciliation: the disagreements, and what happens to them

**Covers:** statement ingestion, deterministic matching that records its rule,
breaks with an owner and an age, resolution by an approved adjustment — and, in
steps 12 and 13, the two edges TASK-010 closed: an adjustment an approver
**refuses**, and a retry that names the returned payment it replaces
**Requirements:** PR-050, PR-051, PR-052, PR-053, PR-054 (PRD §5.6)
**Controls:** [C-01](../COMPLIANCE.md), C-02, C-03, C-05, C-06, C-08, C-13
**Related:** [ADR-0023](../ADR/0023-a-clofin-defined-synthetic-statement-format-and-an-ordered-matching-sequence.md)
— the format, the matching order, and how a break is resolved;
[ADR-0024](../ADR/0024-a-retry-names-the-returned-payment-it-replaces.md) — the
retry link; [ADR-0025](../ADR/0025-two-audit-terms-for-changes-the-trail-did-not-carry.md)
— the refusal, and the adjustment lifecycle

---

## What this script is for

Everything CloFin has done until now records what **CloFin** believes happened.
This script is where that belief meets a second, independent account of the same
events, and where every disagreement becomes something a person owns.

The PRD's own framing is the reason: *a break found in March may have originated
in January.* You will produce breaks deliberately, watch each one open with an
owner and an age, and close one by moving the books — through an approval given
by somebody other than the person who proposed it.

**Two things to watch for, because they are the point rather than the
decoration:**

1. **A payment the scheme never answered about produces no statement line at
   all — and no break.** Its money is still sitting in `1300-IN-TRANSIT`, which
   is exactly where value of unknown fate belongs. A reconciliation that
   reported it would be reporting CloFin's own decision to keep waiting.
2. **Nothing you do here edits a journal entry.** The only way the books move is
   a *new* entry, and above a threshold it needs a second pair of eyes. You will
   try to approve your own adjustment in step 9 and be refused.

> **Everything here is simulated.** CloFin is not connected to any bank, payment
> scheme or central bank and holds no regulatory authorisation. The statement
> format is CloFin's **own** — `SIM-CLOFIN-RECON-STATEMENT` — and is
> deliberately **not** camt.053, MT940, BAI2 or any real scheme's schema. The
> only producer of a statement is CloFin's own simulator. You will send a
> `camt.053` document in step 5 and be refused by name.

---

## Before you start

| | |
|---|---|
| Prerequisite | [UAT-006](UAT-006-settlement-simulation.md) completed, or its steps 1–7 repeated |
| Tools | `curl`, `jq`, `psql` (via `make db-shell`) |
| Time | About 40 minutes |
| Data | Synthetic only |

```sh
export BASE=http://localhost:8080
```

You need, from UAT-006 or from a fresh run of its setup:

- `$ORG` — the organisation id
- `$CONTROLLER` — an actor holding `controller` (it holds
  `reconciliation/execute`)
- `$CHECKER` — an actor holding `approver`, with an SGD limit above SGD 100.00
- `$AUDITOR` — an actor holding `auditor`
- a settled batch, a returned payment, and — importantly — **one payment nobody
  answered about**

You also need two accounts beyond settlement's three:

```sh
curl -sS -X POST $BASE/accounts -H "X-Actor-Id: $CONTROLLER" \
  -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"code\":\"2200-UNAPPLIED\",
       \"name\":\"Unapplied receipts\",\"type\":\"liability\",\"currency\":\"SGD\"}" | jq .
```

`1300-IN-TRANSIT` you already have — it is the account this whole script is
about.

**Approval bands matter here and are worth setting deliberately.** The lowest
band an organisation configures for a currency is the point at which an
adjustment starts needing approval; below it one actor may post. Set the floor
at SGD 1,000.00 so both cases are reachable:

```sh
make db-shell
```
```sql
insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
values ('<ORG>', 'SGD', 100000, 1)
on conflict (organisation_id, currency, from_minor)
  do update set approvals_required = excluded.approvals_required;
```

---

## Step 1 — Ask the simulated scheme for its statement

The scheme sends a statement covering a period. Ask for the last two days:

```sh
export FROM=$(date -u -d 'yesterday 00:00' +%Y-%m-%dT%H:%M:%SZ)
export TO=$(date -u -d 'tomorrow 00:00' +%Y-%m-%dT%H:%M:%SZ)

curl -sS "$BASE/settlement-statements?organisationId=$ORG&scheme=SIM-RTGS&currency=SGD&from=$FROM&to=$TO" \
  -H "X-Actor-Id: $CONTROLLER" | jq . | tee /tmp/statement.json
```

**Expected**

- `format` is `SIM-CLOFIN-RECON-STATEMENT` and `simulated` is `true`.
- One line per payment the scheme **answered about** — settlements and returns.
- **No line for the payment nobody answered about.** Count the lines against
  your batch: they will be one short, and that is correct.

> Record the line count and the number of payments in your batch. The difference
> is the unanswered one.

---

## Step 2 — Ingest it, and watch it match

```sh
jq --arg org "$ORG" '. + {organisationId: $org}' /tmp/statement.json \
  | curl -sS -X POST $BASE/reconciliation-statements \
      -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
      --data-binary @- | jq .
```

**Expected**

- `200`, `disposition: "applied"`, `replayed: false`.
- `matches` has one entry per line, each carrying **`rule`** —
  `R1-reference-amount-and-value-date` for every line of an unperturbed
  statement.
- `breaks` is **empty**.

> The `rule` field is the whole of PR-051. A match that could not say how it was
> made is a match nobody can defend. Record one match's `rule` and `entryId`.

**And the unanswered payment is still where it should be.** Check the clearing
account:

```sh
curl -sS "$BASE/accounts?organisationId=$ORG" -H "X-Actor-Id: $CONTROLLER" \
  | jq '.accounts[] | select(.code=="1300-IN-TRANSIT") | .id'
```

Produce a statement for that account over the same period
(`/accounts/{id}/statement`). Its closing balance is the value of the payment
the scheme never answered about — visible as money, not merely as a row in a
table.

---

## Step 3 — Deliver the same statement again

```sh
jq --arg org "$ORG" '. + {organisationId: $org}' /tmp/statement.json \
  | curl -sS -X POST $BASE/reconciliation-statements \
      -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
      --data-binary @- | jq '{replayed, disposition, id}'
```

**Expected** — `200`, and `replayed: true`. The `id` is the **same** receipt.

Confirm that nothing was done twice:

```sql
select count(*) from reconciliation_match;
select count(*) from reconciliation_break;
select count(*) from journal_entry;
```

**Expected** — every count unchanged from step 2. A duplicate delivery is the
normal case in the world this simulates, not an error.

---

## Step 4 — Deliver a *different* document under the same reference

Change one amount and send it again under the same `statementReference`:

```sh
jq --arg org "$ORG" '. + {organisationId: $org}
   | .lines[0].amount.minorUnits += 1' /tmp/statement.json \
  | curl -sS -X POST $BASE/reconciliation-statements \
      -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
      --data-binary @- | jq .
```

**Expected** — `409`, `errors.dispositionReason` is `replay-key-conflict`, and
`errors.replayed` is **false**.

> A reference identifies one document. Two documents that say different things
> cannot share it — and this is **not** called a replay, because telling a
> caller CloFin had already seen a document nobody sent is the failure this
> distinction exists to prevent.

Confirm no second receipt was written:

```sql
select statement_reference, count(*) from reconciliation_statement group by 1;
```

---

## Step 5 — Try a real bank format, and a real scheme name

```sh
curl -sS -o /dev/null -w '%{http_code}\n' -X POST $BASE/reconciliation-statements \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"format\":\"camt.053.001.08\",\"formatVersion\":1,
       \"scheme\":\"SIM-RTGS\",\"currency\":\"SGD\",\"statementReference\":\"X\",
       \"periodStart\":\"$FROM\",\"periodEnd\":\"$TO\",\"lines\":[]}"
```

**Expected** — `400`. Read the message: it names the one format CloFin reads and
says why it is not a real one.

Now a real network name:

```sh
curl -sS -X POST $BASE/reconciliation-statements \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"format\":\"SIM-CLOFIN-RECON-STATEMENT\",\"formatVersion\":1,
       \"scheme\":\"TARGET2\",\"currency\":\"SGD\",\"statementReference\":\"Y\",
       \"periodStart\":\"$FROM\",\"periodEnd\":\"$TO\",\"lines\":[]}" | jq '.detail'
```

**Expected** — `400`. A synthetic system that could record a real network's name
is a synthetic record that reads as a real one.

**Neither attempt leaves a receipt.** Check:

```sql
select count(*) from reconciliation_statement where statement_reference in ('X','Y');
```

**Expected** — `0`. A document CloFin cannot *understand* is a request that could
not be read, not a delivery. Contrast step 6, where a delivery CloFin cannot
*process* is kept.

---

## Step 6 — Make the scheme wrong, one way at a time

The generator will misbehave on request. Each class is named, so you can predict
the break before you run it.

| Ask for | Expect the break |
|---|---|
| `perturbation=missing-line` | `expectation-unmatched` — CloFin's books record a movement the statement does not report |
| `perturbation=amount-mismatch` | `amount-mismatch` — same payment, different amount |
| `perturbation=unknown-line` | `statement-line-unmatched` — money the scheme says it moved and CloFin never saw settle |
| `perturbation=duplicate-line` | `duplicate-statement-line` |
| `perturbation=shifted-value-date` | `value-date-mismatch` |
| `perturbation=flipped-line-type` | `line-type-mismatch` — a return reported as a settlement |

Run at least **three**, including `missing-line` and `unknown-line` — those two
are the reconciliation running in *both directions*, and a reconciliation that
only checked statement lines would miss the first entirely:

```sh
for CLASS in missing-line unknown-line amount-mismatch; do
  echo "== $CLASS"
  curl -sS "$BASE/settlement-statements?organisationId=$ORG&scheme=SIM-RTGS&currency=SGD&from=$FROM&to=$TO&perturbation=$CLASS" \
    -H "X-Actor-Id: $CONTROLLER" \
  | jq --arg org "$ORG" '. + {organisationId: $org}' \
  | curl -sS -X POST $BASE/reconciliation-statements \
      -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' --data-binary @- \
  | jq '[.breaks[] | {kind, detail, state, assigneeId, ageSeconds}]'
done
```

**Expected, for each break**

- the `kind` the table above predicts;
- a `detail` that names **what** disagreed — the two amounts, the two dates, the
  movement one side does not have — rather than merely that something did;
- `state: "open"`;
- an `assigneeId`. **A break is never unowned**; it opens assigned to whoever
  ingested the statement that found it;
- an `ageSeconds`, which grows every time you read it because it is **derived**
  from when the break opened and stored nowhere.

> Record one break's `id` as `$BREAK` — you will work it in the next steps.

---

## Step 7 — Look at the queue, oldest first

```sh
curl -sS "$BASE/reconciliation-breaks?organisationId=$ORG" -H "X-Actor-Id: $AUDITOR" \
  | jq '{count, truncated, breaks: [.reconciliationBreaks[] | {kind, state, ageSeconds}]}'
```

**Expected** — **oldest first**. This is the only list in CloFin ordered that
way, and it is deliberate: a queue that buries the oldest item on page four is
the spreadsheet this module replaces.

Try a filter, and then an invalid one:

```sh
curl -sS "$BASE/reconciliation-breaks?organisationId=$ORG&state=open" -H "X-Actor-Id: $AUDITOR" | jq '.count'
curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/reconciliation-breaks?organisationId=$ORG&state=haunted" \
  -H "X-Actor-Id: $AUDITOR"
```

**Expected** — a count, then `400`.

---

## Step 8 — Take ownership, and try to skip a step

```sh
curl -sS -X POST $BASE/reconciliation-breaks/$BREAK/assignment \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"assigneeId\":\"$CHECKER\"}" \
  | jq '{state, assigneeId, permittedTransitions}'
```

**Expected** — `state` moves from `open` to `investigating`. **Assigning a break
is how it becomes investigated**: taking it on is one fact, not two.

Assign it again, to somebody else:

```sh
curl -sS -X POST $BASE/reconciliation-breaks/$BREAK/assignment \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"assigneeId\":\"$CONTROLLER\"}" \
  | jq '{state, assigneeId}'
```

**Expected** — the owner changes and the state does **not** move. Re-assignment
is not a transition.

Now try to assign it to somebody in another organisation (any UUID that is not
an actor here):

```sh
curl -sS -o /dev/null -w '%{http_code}\n' -X POST $BASE/reconciliation-breaks/$BREAK/assignment \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"assigneeId\":\"00000000-0000-4000-8000-000000000000\"}"
```

**Expected** — `422`, naming no such actor. It does not tell you whether that id
names a real actor somewhere else.

---

## Step 9 — Move the books, and try to approve your own correction

Propose an adjustment **above** the SGD 1,000.00 band:

```sh
curl -sS -X POST $BASE/reconciliation-breaks/$BREAK/adjustments \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":100000},
       \"direction\":\"credit\",
       \"narrative\":\"Scheme reports a movement CloFin did not post; parked in suspense pending investigation\"}" \
  | jq '{id, status, posted, approvalsRequired, break: .break.state}'
```

**Expected** — `201`, `status: "proposed"`, `posted: false`,
`approvalsRequired: 1`, and the break still `investigating`. **A proposed
adjustment resolves nothing.**

Record the adjustment id as `$ADJ`, and now try to approve it yourself:

```sh
curl -sS -X POST $BASE/reconciliation-adjustments/$ADJ/approvals \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\"}" | jq '{status: .status, reason: .errors.reason, detail}'
```

**Expected** — `403`, `errors.reason` is `self-approval`.

> This is the *same* control that refuses a maker approving their own payment —
> the same function, the same table, the same refusal vocabulary. There is no
> second approval mechanism in CloFin, and the prose names the subject: *the
> actor who created this reconciliation adjustment may not approve it*.

Now let the checker approve it:

```sh
curl -sS -X POST $BASE/reconciliation-adjustments/$ADJ/approvals \
  -H "X-Actor-Id: $CHECKER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\"}" \
  | jq '{posted, approvalsHeld, approvalsRequired, adjustment: .adjustment.status,
         entryId: .adjustment.entryId, break: .break.state}'
```

**Expected** — `201`, `posted: true`, the adjustment `posted`, and the break
**`resolved`** — in the same transaction. An approved-but-unposted adjustment is
not a state that exists.

**Read the entry it posted.** It is an ordinary journal entry:

```sh
curl -sS "$BASE/journal-entries/<entryId>?organisationId=$ORG" -H "X-Actor-Id: $CONTROLLER" \
  | jq '{reference, narrative, lines: [.lines[] | {accountId, direction, amount}]}'
```

**Expected** — two lines, one on `1300-IN-TRANSIT` and one on `2200-UNAPPLIED`,
balancing, referencing `reconciliation-adjustment`. **Nothing was edited**: the
original entries are untouched, and this is a new fact about a new moment.

---

## Step 10 — Below the threshold, one actor suffices

Pick another break (`$BREAK2`) and propose an adjustment of **SGD 999.99** —
one minor unit below the band:

```sh
curl -sS -X POST $BASE/reconciliation-breaks/$BREAK2/adjustments \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":99999},
       \"direction\":\"credit\",
       \"narrative\":\"De-minimis difference, parked in suspense\"}" \
  | jq '{posted, approvalsRequired, status, break: .break.state}'
```

**Expected** — `201`, `approvalsRequired: 0`, `posted: true`, break `resolved`.

> The boundary is **inclusive**: at exactly SGD 1,000.00 approval is required,
> as you saw in step 9. Of the two readings of an inclusive bound, that is the
> one that asks for more scrutiny.

**Now remove the organisation's bands and try again** on a third break:

```sql
delete from approval_threshold where organisation_id = '<ORG>';
```
```sh
curl -sS -X POST $BASE/reconciliation-breaks/$BREAK3/adjustments \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"amount\":{\"currency\":\"SGD\",\"minorUnits\":100},
       \"direction\":\"credit\",\"narrative\":\"Should be refused\"}" \
  | jq '{status: .status, reason: .errors.reason}'
```

**Expected** — `422`, `no-threshold-configured`. An organisation that has not
said how many approvals it wants gets **none** of them, not zero of them: an
unconfigured currency is not an unsupervised one. Restore the bands before
continuing.

---

## Step 11 — Try to resolve a resolved break

```sh
curl -sS -o /dev/null -w '%{http_code}\n' -X POST $BASE/reconciliation-breaks/$BREAK/assignment \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"assigneeId\":\"$CHECKER\"}"

curl -sS -X POST $BASE/reconciliation-breaks/$BREAK/adjustments \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"amount\":{\"currency\":\"SGD\",\"minorUnits\":100},
       \"direction\":\"credit\",\"narrative\":\"A second correction\"}" | jq '.detail'
```

**Expected** — `409` both times. Who resolved what is history, and history is
not reassigned or re-corrected. The refusal names the state the break is in and
what would have been permitted instead.

Now try it in raw SQL, the way a fix-up script would:

```sql
insert into reconciliation_adjustment
  (id, organisation_id, break_id, amount_minor, currency, direction, narrative,
   status, approvals_required, entry_id, posted_at, created_by)
values (gen_random_uuid(), '<ORG>', '<BREAK>', 100, 'SGD', 'credit', 'by hand',
        'posted', 0, '<any existing journal_entry id>', now(), '<CONTROLLER>');
```

**Expected** — refused by `recon_adjustment_posted_key`. **One posted adjustment
per break, ever**, and the guard is in the schema so it binds a script and a
defect as well as a handler.

---

## Step 12 — Refuse a correction, and raise a different one

Not every proposal deserves to post. Pick a break that is still open — call it
`$BREAK3` — and propose an adjustment **above** the band, so it needs somebody
else's agreement:

```sh
curl -sS -X POST $BASE/reconciliation-breaks/$BREAK3/adjustments \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":100000},
       \"direction\":\"credit\",
       \"narrative\":\"Provisional: the scheme's figure looks wrong\"}" \
  | jq '{id, status, permittedTransitions, posted}'
```

**Expected** — `201`, `status: "proposed"`, and
`permittedTransitions: ["post","reject"]`. Record the id as `$ADJ3`.

Try to refuse **your own** proposal first:

```sh
curl -sS -X POST $BASE/reconciliation-adjustments/$ADJ3/approvals \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"decision\":\"rejected\",\"reason\":\"changed my mind\"}" \
  | jq '{reason: .errors.reason, detail}'
```

**Expected** — `403`, `errors.reason` is `self-approval`.

> The maker never becomes a valid checker for their own correction, and that is
> as true of a refusal as of an approval. It is the same comparison, ranked
> first and never waivable.

Now try a refusal with no reason:

```sh
curl -sS -X POST $BASE/reconciliation-adjustments/$ADJ3/approvals \
  -H "X-Actor-Id: $CHECKER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"decision\":\"rejected\"}" | jq '.errors'
```

**Expected** — `422`, naming `reason`. A refusal whose reason is retained is the
difference between a trail that explains a declined correction and one that
merely records that somebody declined it — the same rule PR-013 sets for a
rejected payment, enforced in the domain **and** by
`approval_rejection_needs_reason` at the database.

And now refuse it properly:

```sh
curl -sS -X POST $BASE/reconciliation-adjustments/$ADJ3/approvals \
  -H "X-Actor-Id: $CHECKER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",\"decision\":\"rejected\",
       \"reason\":\"The statement is right; our posting is the one to investigate\"}" \
  | jq '{rejected, posted, adjustment: {status: .adjustment.status,
         permittedTransitions: .adjustment.permittedTransitions,
         entryId: .adjustment.entryId},
         approval: {decision: .approval.decision, reason: .approval.reason},
         break: .break.state}'
```

**Expected**

- `201`, `rejected: true`, `posted: false`;
- the adjustment `rejected`, with **no** `entryId` and
  `permittedTransitions: []` — it is terminal;
- the approval carrying `decision: "rejected"` and your reason. **That is where
  the evidence lives**, and it is the same place a rejected payment keeps it;
- the break in the state it was already in. Proposing an adjustment never moved
  it, so refusing one returns it to nothing.

Confirm nothing moved in the books, and then raise a **different** adjustment
against the same break:

```sh
psql -c "select count(*) from journal_entry where reference_type = 'reconciliation-adjustment';"

curl -sS -X POST $BASE/reconciliation-breaks/$BREAK3/adjustments \
  -H "X-Actor-Id: $CONTROLLER" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":99999},
       \"direction\":\"credit\",
       \"narrative\":\"Agreed after investigation\"}" \
  | jq '{status, posted, break: .break.state}'
```

**Expected** — the entry count is unchanged by the refusal, and the second
adjustment posts and resolves the break. A refused correction blocks nothing:
`recon_adjustment_posted_key` is partial on `posted`, so a rejected row is
outside it.

Finally, try to decide the rejected adjustment again:

```sh
curl -sS -X POST $BASE/reconciliation-adjustments/$ADJ3/approvals \
  -H "X-Actor-Id: $CHECKER2" -H 'content-type: application/json' \
  -d "{\"organisationId\":\"$ORG\"}" | jq '.errors'
```

**Expected** — `409`, naming `adjustment-status: "rejected"` and
`permitted: []`. The lifecycle is
[`diagrams/reconciliation-adjustment-lifecycle.md`](../diagrams/reconciliation-adjustment-lifecycle.md),
generated from `clofin.recon.adjustment/transitions`; a refusal that could be
taken back would not be a refusal.

---

## Step 13 — Point a retry at the payment that came back

Reconciliation is where return-exception handling lives, which is why
[ADR-0019](../ADR/0019-a-returned-payment-is-terminal-and-retries-as-a-new-instruction.md)
ruled linked-retry provenance to this increment. Find a break about a **returned**
payment — the `missing-line` run in step 6 produced one — and read it:

```sh
curl -sS "$BASE/reconciliation-breaks/$BREAKR?organisationId=$ORG" -H "X-Actor-Id: $CONTROLLER" \
  | jq '{kind, instructionId, retriedByInstructionIds}'
```

**Expected** — `expectation-unmatched`, an `instructionId`, and **no**
`retriedByInstructionIds`: nothing has been retried yet, so the field is absent
rather than empty. Record the instruction as `$RETURNED` and confirm what it is:

```sh
curl -sS "$BASE/payment-instructions/$RETURNED?organisationId=$ORG" -H "X-Actor-Id: $CONTROLLER" \
  | jq '{status, permittedTransitions}'
```

**Expected** — `returned`, with an empty `permittedTransitions`. It is terminal:
a retry is a **new** instruction, not a transition on this one.

Try to retry something that is not returned first — any settled instruction:

```sh
curl -sS -X POST $BASE/payment-instructions \
  -H "X-Actor-Id: $MAKER" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"organisationId\":\"$ORG\",\"debtorAccountId\":\"$FUNDS\",
       \"creditorName\":\"Pacific Rim Logistics Pte Ltd\",
       \"creditorAccount\":\"SG-SYNTH-88012399\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":110000},
       \"valueDate\":\"$VALUEDATE\",\"purposeCode\":\"SUPP\",
       \"retriesId\":\"$SETTLED\"}" | jq '{detail, errors}'
```

**Expected** — `409`, naming `instruction-status: "settled"`,
`attempted: "retry"` and `retryable-in: ["returned"]`. The refusal names the
correction rather than only the rule: what that operator wants is a reversal.

Now raise the retry properly, against `$RETURNED`, changing the beneficiary
account — which is the ordinary reason a payment comes back:

```sh
curl -sS -X POST $BASE/payment-instructions \
  -H "X-Actor-Id: $MAKER" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"organisationId\":\"$ORG\",\"debtorAccountId\":\"$FUNDS\",
       \"creditorName\":\"Pacific Rim Logistics Pte Ltd\",
       \"creditorAccount\":\"SG-SYNTH-88012777\",
       \"amount\":{\"currency\":\"SGD\",\"minorUnits\":110000},
       \"valueDate\":\"$VALUEDATE\",\"purposeCode\":\"SUPP\",
       \"retriesId\":\"$RETURNED\"}" | jq '{id, status, retriesId}'
```

**Expected** — `201`, `status: "draft"`, `retriesId: "$RETURNED"`. Record it as
`$RETRY`.

> Nothing about the amount, the currency or the beneficiary is compared against
> the original, and that is deliberate: a return is **new information**, and
> correcting the account it bounced off is exactly why you are here. The retry
> is submitted and approved on its own merits, with the whole maker–checker
> control applying to it.

Read the linkage from **both** ends, and from the break:

```sh
curl -sS "$BASE/payment-instructions/$RETURNED?organisationId=$ORG" -H "X-Actor-Id: $CONTROLLER" \
  | jq '{status, retriedByIds}'

curl -sS "$BASE/reconciliation-breaks/$BREAKR?organisationId=$ORG" -H "X-Actor-Id: $CONTROLLER" \
  | jq '{instructionId, retriedByInstructionIds}'
```

**Expected** — the original names `$RETRY` in `retriedByIds`, and the break now
names it too. **Nothing was written to either the original or the break to make
that true**: both are derived when they are read, for the same reason a break's
age is.

Try to rewrite the provenance, through the API and then behind it:

```sh
curl -sS -X PATCH "$BASE/payment-instructions/$RETRY" \
  -H "X-Actor-Id: $MAKER" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"organisationId\":\"$ORG\",\"retriesId\":\"$SETTLED\"}" | jq '.errors'
```

```sql
update payment_instruction set retries_id = null where id = '<the retry>';
```

**Expected** — `422` naming `retriesId`, and the raw `UPDATE` **refused** by
`payment_instruction_retry_link_immutable`. Provenance an operator can rewrite
after the fact is provenance an investigation cannot rely on, so the guard is in
the schema and binds a fix-up script too.

Finally, confirm the link is in the trail and not only in the row:

```sh
curl -sS "$BASE/audit/evidence/$RETRY?organisationId=$ORG" -H "X-Actor-Id: $AUDITOR" \
  | jq '{subjectType, events: [.events[] | {action, actorId}]}'
```

**Expected** — one `payment.created` event. Its after digest covers
`retriesId`, so a link altered afterwards would no longer match what the
creation left behind.

---

## Step 14 — Reconciliation status for the account and period

```sh
export INTRANSIT=$(curl -sS "$BASE/accounts?organisationId=$ORG" -H "X-Actor-Id: $CONTROLLER" \
  | jq -r '.accounts[] | select(.code=="1300-IN-TRANSIT") | .id')

curl -sS "$BASE/reconciliation-status?organisationId=$ORG&accountId=$INTRANSIT&from=$FROM&to=$TO" \
  -H "X-Actor-Id: $AUDITOR" | jq .
```

**Expected**

- `statements.received` equals the number of statements you ingested that were
  *understood* (steps 2, 4's refusal is not one, step 6's runs are);
- `lines.matched + lines.unmatched = lines.total`;
- `matchesByRule` lists **every** rule, including those that matched nothing —
  so you read "none under this rule" rather than guessing whether the build
  knows it;
- `breaksByState` shows your resolved breaks under `resolved`;
- `oldestUnresolvedAgeSeconds` is the age of the oldest break still open, or
  `null` when nothing is outstanding — which is a different statement from
  zero.

Check the figures against the breaks themselves:

```sql
select state, count(*) from reconciliation_break group by 1;
select rule_id, count(*) from reconciliation_match group by 1;
```

**Expected** — the same numbers. If they differ, stop and raise a defect.

---

## Step 15 — The trail, and the attempt to alter it

Every step you performed left exactly one audit event per write:

```sh
curl -sS "$BASE/audit/events?organisationId=$ORG" -H "X-Actor-Id: $AUDITOR" \
  | jq '[.auditEvents[].action] | group_by(.) | map({action: .[0], count: length})'
```

**Expected** — `reconciliation-statement.received`, `reconciliation-break.opened`,
`reconciliation-break.assigned`, `reconciliation-break.resolved`,
`reconciliation-adjustment.proposed`, `reconciliation-adjustment.posted`,
`reconciliation-adjustment.rejected`, `approval.recorded`, `payment.created`
and `journal-entry.posted`.

`reconciliation-adjustment.rejected` appears **once**, for the refusal in step
12, and `approval.recorded` appears once for *every* decision — the refusal
included. A refusal is two events, because a decision being taken and a subject
becoming terminal are two facts about two subjects.

**Note what is *not* there:** the statement you delivered twice appears once.
The trail records arrivals, not requests.

Pull an evidence pack for the break you resolved:

```sh
curl -sS "$BASE/audit/evidence/$BREAK?organisationId=$ORG" -H "X-Actor-Id: $AUDITOR" \
  | jq '{subjectType, count, events: [.events[] | {action, actorId, occurredAt}]}'
```

**Expected** — opened, assigned, resolved, each naming the actor that caused it.

Now try to alter what arrived:

```sql
update reconciliation_match set rule_id = 'R3-reference-only';
delete from reconciliation_statement_line;
truncate reconciliation_statement;
```

**Expected** — all three refused. What arrived, what it carried, and which rule
bound which line to which movement cannot be edited afterwards. An editable
explanation is not one.

> As everywhere else in CloFin, those triggers bind the **application role** and
> not the table's owner, and the shipped stack connects as the owner. That
> residual risk is named in [`COMPLIANCE.md` §4](../COMPLIANCE.md) rather than
> left for you to discover here.

---

## Recording the result

| Step | Requirement | Result | Evidence |
|---|---|---|---|
| 1 | PR-050 | | line count, and the unanswered payment absent |
| 2 | PR-050, PR-051 | | every line matched, each with its `rule` |
| 3 | C-06 | | `replayed: true`, counts unchanged |
| 4 | C-06 | | `409`, `replay-key-conflict`, one receipt |
| 5 | scope | | `400` twice, and **no** receipt |
| 6 | PR-052 | | the predicted break kind, with detail, owner and age |
| 7 | PR-052 | | oldest first |
| 8 | PR-052 | | assign moves the state; re-assign does not |
| 9 | PR-053, C-01, C-02 | | self-approval refused; second actor posts |
| 10 | PR-053 | | de-minimis posts; unconfigured currency refuses |
| 11 | C-03 | | `409`, and the raw insert refused |
| 12 | C-01, C-05, C-13 | | self-rejection refused; reasonless refusal refused; the adjustment terminal with no entry; the break unmoved; a different adjustment posts |
| 13 | ADR-0019, ADR-0024, C-08 | | a non-returned target refused by name; the link visible from both ends and from the break; `PATCH` and the raw `UPDATE` both refused |
| 14 | PR-054 | | figures agree with the rows |
| 15 | C-05, C-13 | | one event per write; alterations refused |

A step with no evidence is not a passed step.
