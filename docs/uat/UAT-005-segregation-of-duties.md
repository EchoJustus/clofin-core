# UAT-005 — Segregation of duties, attempted and refused

**Covers:** maker–checker, approval thresholds, approver limits, least
privilege, and the audit trail behind them
**Requirements:** PR-010, PR-011, PR-012, PR-013, PR-014, PR-015, PR-070,
PR-072, PR-073, PR-074, PR-075
**Controls:** [C-01](../COMPLIANCE.md), C-02, C-03, C-05, C-08
**Audit findings covered:** F-001 (step 4b), F-002 (step 11)

> **Numbered 005, not 004.** The brief for this work asked for
> `UAT-004-segregation-of-duties.md`; `UAT-004` was already taken by the
> idempotent-submission script from the previous increment. Numbers are never
> reused. Recorded as objection **O-3** in
> [`003-REQ`](../audits/003-REQ-authorisation-and-audit-trail.md).

---

## What this script is for

A control you have only read about is a control you are trusting. The point of
this script is that **you attempt the violation yourself and watch it fail** —
twice, in two different ways, because the two failures prove different things:

- Through the API, which is what an attacker or a mistaken operator would use.
- Directly in SQL against the audit trail, which is what a defect, a migration
  script or a maintenance session would do.

If a step *succeeds* where this script says it should fail, stop and raise a
defect. That is the outcome this document exists to detect.

Everything below is synthetic. CloFin is not connected to any bank, payment
scheme or central bank and holds no regulatory authorisation.

---

## Before you start

| | |
|---|---|
| Prerequisite | `make up` has completed and `make ready` answers |
| Tools | `curl`, `psql` (via `make db-shell`), and a terminal |
| Time | About 25 minutes |
| Data | Synthetic only. Nothing here names a real person or counterparty |

Set a base URL once:

```sh
export BASE=http://localhost:8080
```

Two conventions used throughout:

- Every mutating request needs an `Idempotency-Key`. Use a fresh UUID each
  time — `uuidgen` or any random string will do.
- Every request except organisation creation needs an `X-Actor-Id`. **This is
  not authentication that resists an adversary**, and the API contract says so:
  there is no token and no signature. It names which seeded actor you are
  acting as, which is what makes the authorisation model testable.

---

## Step 1 — Create an organisation and an account

`POST /organisations` is the one unauthenticated operation, because there is no
actor until an organisation exists to hold one.

```sh
ORG=$(curl -sS -X POST $BASE/organisations \
  -H 'content-type: application/json' \
  -d '{"legalName":"Meridian Freight Holdings Pte Ltd","shortName":"meridian-uat5"}' \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "organisation: $ORG"
```

**Expected:** a UUID.

---

## Step 2 — Seed the actors, and read what you have granted

There is deliberately **no endpoint that creates an actor, grants a role or sets
a limit**. An actor able to grant itself the approver role would make
segregation of duties unenforceable however carefully the rule is written. Seed
them in SQL — and read what you are granting as you go, because this is the
control's configuration.

Open a shell with `make db-shell`, then:

```sql
\set org 'PASTE-THE-ORG-UUID-HERE'

-- Priya raises payments. She is the MAKER. Note what she is not granted.
insert into actor (id, organisation_id, display_name)
  values ('11111111-1111-1111-1111-111111111111', :'org', 'Priya (maker)');
insert into actor_role (actor_id, role)
  values ('11111111-1111-1111-1111-111111111111', 'operator');

-- Wei approves, up to SGD 5,000.00.
insert into actor (id, organisation_id, display_name)
  values ('22222222-2222-2222-2222-222222222222', :'org', 'Wei (checker)');
insert into actor_role (actor_id, role)
  values ('22222222-2222-2222-2222-222222222222', 'approver');
insert into approver_limit (actor_id, currency, limit_minor)
  values ('22222222-2222-2222-2222-222222222222', 'SGD', 500000);

-- Nadia also approves, up to SGD 50,000.00.
insert into actor (id, organisation_id, display_name)
  values ('33333333-3333-3333-3333-333333333333', :'org', 'Nadia (checker)');
insert into actor_role (actor_id, role)
  values ('33333333-3333-3333-3333-333333333333', 'approver');
insert into approver_limit (actor_id, currency, limit_minor)
  values ('33333333-3333-3333-3333-333333333333', 'SGD', 5000000);

-- Sam opens accounts and posts entries. Sam cannot approve.
insert into actor (id, organisation_id, display_name)
  values ('44444444-4444-4444-4444-444444444444', :'org', 'Sam (controller)');
insert into actor_role (actor_id, role)
  values ('44444444-4444-4444-4444-444444444444', 'controller');

-- Rae reads the audit trail and nothing else.
insert into actor (id, organisation_id, display_name)
  values ('55555555-5555-5555-5555-555555555555', :'org', 'Rae (auditor)');
insert into actor_role (actor_id, role)
  values ('55555555-5555-5555-5555-555555555555', 'auditor');

-- The organisation's policy: up to SGD 1,000.00 needs one approval;
-- at and above it, two.
insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
  values (:'org', 'SGD', 0, 1), (:'org', 'SGD', 100000, 2);
```

Now try to grant yourself something stronger:

```sql
insert into actor_role (actor_id, role)
  values ('11111111-1111-1111-1111-111111111111', 'superuser');
```

**Expected:** the database refuses — `violates check constraint "role_known"`.
There is no superuser role in the model and one cannot be added by writing a
row. Record the error text as evidence.

Leave the SQL shell (`\q`) and set shell variables:

```sh
export PRIYA=11111111-1111-1111-1111-111111111111
export WEI=22222222-2222-2222-2222-222222222222
export NADIA=33333333-3333-3333-3333-333333333333
export SAM=44444444-4444-4444-4444-444444444444
export RAE=55555555-5555-5555-5555-555555555555
```

---

## Step 3 — Open an account, and see least privilege refuse first

Try it as Priya, who is an operator:

```sh
curl -sS -i -X POST $BASE/accounts \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -d "{\"code\":\"1100-CLIENT-FUNDS\",\"name\":\"Client funds — pooled\",\"type\":\"asset\",\"currency\":\"SGD\"}"
```

**Expected:** `403 Not permitted`, naming `account/create` as the missing
permission. Note that the response does **not** list what Priya *can* do — that
would turn a refusal into a capability listing.

Now as Sam, the controller:

```sh
ACCT=$(curl -sS -X POST $BASE/accounts \
  -H "X-Actor-Id: $SAM" -H 'content-type: application/json' \
  -d "{\"code\":\"1100-CLIENT-FUNDS\",\"name\":\"Client funds — pooled\",\"type\":\"asset\",\"currency\":\"SGD\"}" \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "account: $ACCT"
```

**Expected:** a UUID. Same request, different actor, opposite outcome — which
is the whole of C-08 in one pair of commands.

---

## Step 4 — Raise a payment as Priya

```sh
PI=$(curl -sS -X POST $BASE/payment-instructions \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"debtorAccountId\":\"$ACCT\",\"creditorName\":\"Pacific Rim Logistics Pte Ltd\",\"creditorAccount\":\"SG-SYNTH-88012345\",\"amount\":{\"currency\":\"SGD\",\"minorUnits\":50000},\"valueDate\":\"$(date -d '+7 days' +%F 2>/dev/null || date -v+7d +%F)\",\"purposeCode\":\"SUPP\"}" \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "instruction: $PI"

curl -sS $BASE/payment-instructions/$PI -H "X-Actor-Id: $PRIYA"
```

**Expected:** `"status":"draft"` and `"createdBy"` equal to `$PRIYA`.

**Look at `createdBy`.** It is not a field the request sent — try adding
`"createdBy": "..."` to the body above and the request is refused, rather than
the value being quietly ignored. It is who the request authenticated as, which
is what makes it usable as the maker side of the next step.

Submit it:

```sh
curl -sS -X POST $BASE/payment-instructions/$PI/submission \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{}'
```

**Expected:** `"status":"pendingApproval"`.

---

## Step 4b — **Attempt to submit someone else's draft, and watch it fail**

This step did not exist when this script was first written, and its absence is
why audit finding **F-001** reached production code. The script tested that a
maker could not *approve* their own payment, and never tested who could
*submit* one — so the hole sat between two passing steps.

Seed a second operator:

```sql
insert into actor (id, organisation_id, display_name)
  values ('66666666-6666-6666-6666-666666666666', :'org', 'Tom (second operator)');
insert into actor_role (actor_id, role)
  values ('66666666-6666-6666-6666-666666666666', 'operator');
```

```sh
export TOM=66666666-6666-6666-6666-666666666666
```

Raise a fresh draft as Priya (repeat step 4, keeping the id in `$PI3`), then
have Tom try to submit it:

```sh
curl -sS -i -X POST $BASE/payment-instructions/$PI3/submission \
  -H "X-Actor-Id: $TOM" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{}'
```

**Expected:** `403`, with `"rule": "creator-only"` in the `errors` object.

Tom holds `operator`, which carries `payment/submit`. **He is refused anyway.**
That is the distinction this step exists to show: the answer is not "ask for a
permission", it is "this is not your instruction". Confirm the draft has not
moved:

```sh
curl -sS $BASE/payment-instructions/$PI3 -H "X-Actor-Id: $PRIYA"
```

**Expected:** still `"status": "draft"`.

### Why this matters more than it looks

Grant Tom the approver role as well, and run the original exploit:

```sql
insert into actor_role (actor_id, role)
  values ('66666666-6666-6666-6666-666666666666', 'approver');
insert into approver_limit (actor_id, currency, limit_minor)
  values ('66666666-6666-6666-6666-666666666666', 'SGD', 99999999);
```

Tom now holds both roles. Before the fix, he could submit Priya's draft — making
himself its maker in every sense that mattered — and then approve it, because
`createdBy` still said Priya. One human, an approved payment, and every
individual check passing.

```sh
curl -sS -i -X POST $BASE/payment-instructions/$PI3/submission \
  -H "X-Actor-Id: $TOM" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{}'
```

**Expected:** still `403`. The chain breaks at its first step.

Then check the query C-01 publishes as its evidence:

```sql
select s.subject_id
  from audit_event s
  join approval ap on ap.instruction_id = s.subject_id
  join audit_event d on d.subject_id = ap.id
                    and d.action = 'approval.recorded'
 where s.action = 'payment.submitted'
   and d.actor_id = s.actor_id;
```

**Expected:** no rows. Before the fix this returned one for the chain above —
the control's own evidence reported the control failing.

> The query joins through the `approval` table because an approval decision is
> `approval.recorded` against the approval, not `payment.approved` against the
> payment (finding **F-005**). The earlier version matched two `audit_event`
> rows on one subject; run unchanged after F-005 it would return no rows
> because it no longer looks anywhere, which on this page is indistinguishable
> from the control holding.

Remove the extra grants before continuing:

```sql
delete from approver_limit where actor_id = '66666666-6666-6666-6666-666666666666';
delete from actor_role
  where actor_id = '66666666-6666-6666-6666-666666666666' and role = 'approver';
```

---

## Step 5 — **Attempt self-approval, and watch it fail**

This is the step the script exists for. Priya raised this payment. She now tries
to approve it.

```sh
curl -sS -i -X POST $BASE/payment-instructions/$PI/approvals \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"decision":"approved"}'
```

**Expected:** `403`, with `"reason": "self-approval"` in the `errors` object and
a detail naming segregation of duties.

Three things to check, because they are what distinguish a control from a
convenience:

1. **The reason is machine-readable.** `errors.reason` is `self-approval`, not
   prose a client would have to parse.
2. **Nothing moved.** Re-read the instruction; it is still `pendingApproval`,
   and `select count(*) from approval` is still zero.
3. **It is not the UI stopping her.** There is no UI. The refusal came from a
   function that took values and returned a decision — the same function the
   test suite calls directly with no HTTP involved at all.

Now grant Priya the approver role as well, and try again:

```sql
insert into actor_role (actor_id, role) values
  ('11111111-1111-1111-1111-111111111111', 'approver');
insert into approver_limit (actor_id, currency, limit_minor) values
  ('11111111-1111-1111-1111-111111111111', 'SGD', 99999999);
```

```sh
curl -sS -i -X POST $BASE/payment-instructions/$PI/approvals \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"decision":"approved"}'
```

**Expected:** still `403 self-approval`. The control is about *provenance*, not
about which roles happen to be granted. Remove the grant again before
continuing:

```sql
delete from approver_limit where actor_id = '11111111-1111-1111-1111-111111111111';
delete from actor_role
  where actor_id = '11111111-1111-1111-1111-111111111111' and role = 'approver';
```

---

## Step 6 — A different approver succeeds

```sh
curl -sS -X POST $BASE/payment-instructions/$PI/approvals \
  -H "X-Actor-Id: $WEI" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"decision":"approved"}'
```

**Expected:** `201`, `"satisfied": true`, `"approvalsRequired": 1`, and the
instruction now `"approved"`. SGD 500.00 is below the SGD 1,000.00 band, so one
approval is enough.

---

## Step 7 — An amount above an approver's limit

Raise a second payment for SGD 20,000.00 and submit it (repeat step 4 with
`"minorUnits": 2000000`, keeping the id in `$PI2`). Then:

```sh
curl -sS -i -X POST $BASE/payment-instructions/$PI2/approvals \
  -H "X-Actor-Id: $WEI" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"decision":"approved"}'
```

**Expected:** `403`, `"reason": "above-actor-limit"`, and
`"actor-limit-minor": 500000` — the caller is told the ceiling that applied,
which is what makes the refusal actionable rather than merely final.

---

## Step 8 — Two approvals above the band

SGD 20,000.00 is above the SGD 1,000.00 band, so it needs two.

```sh
curl -sS -X POST $BASE/payment-instructions/$PI2/approvals \
  -H "X-Actor-Id: $NADIA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"decision":"approved"}'
```

**Expected:** `201`, `"approvalsRequired": 2`, `"approvalsHeld": 1`,
`"satisfied": false` — and the instruction still `"pendingApproval"`. One
approval on a two-approval band is not enough, and the response says so rather
than leaving the approver to work it out.

Now look at the queue as Wei:

```sh
curl -sS "$BASE/approvals/queue" -H "X-Actor-Id: $WEI"
```

**Expected:** one row carrying the amount, the counterparty name, the account,
the purpose code, `priorApprovals` naming Nadia, `approvalsRemaining: 1`, and
`canApprove: false` with `"refusalReason": "above-actor-limit"` — because SGD
20,000.00 is still over Wei's ceiling.

**This is the point of the queue.** An approver can see what they are being
asked to agree to without opening another system, *and* can see why a row is not
theirs to act on. A queue that showed only an id would make every approval a
rubber stamp.

Try the queue as Priya:

```sh
curl -sS -i "$BASE/approvals/queue" -H "X-Actor-Id: $PRIYA"
```

**Expected:** `403`. An operator does not hold `approval/read`.

---

## Step 9 — A rejection needs a reason

```sh
curl -sS -i -X POST $BASE/payment-instructions/$PI2/approvals \
  -H "X-Actor-Id: $NADIA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"decision":"rejected"}'
```

**Expected:** `422`, naming `reason` as the failed field. (Nadia has already
decided on this instruction, so if you want to see the rejection path succeed,
use a fresh instruction and a fresh approver.)

---

## Step 10 — **Amend an approved payment, and watch its approvals die**

Return to the first instruction, `$PI`, which Wei approved in step 6. Priya
corrects the amount:

```sh
curl -sS -X PATCH $BASE/payment-instructions/$PI \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount":{"currency":"SGD","minorUnits":75000}}'
```

**Expected:** `200`, and `"status": "draft"`. The approved payment has been
pulled back to draft.

```sql
select actor_id, decision, invalidated_at from approval;
```

**Expected:** Wei's approval is still there, with `invalidated_at` set. It was
**not deleted** — an approval that was given and then invalidated is exactly the
history an investigation needs, whereas a deleted one is a decision nobody can
prove was taken.

Try to delete it:

```sql
delete from approval where actor_id = '22222222-2222-2222-2222-222222222222';
```

**Expected:** refused — `is append-only`.

Now check that only Priya can amend it. As Sam:

```sh
curl -sS -i -X PATCH $BASE/payment-instructions/$PI \
  -H "X-Actor-Id: $SAM" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"purposeCode":"TRAD"}'
```

**Expected:** `403`. A draft may be amended by its creator (PR-004) — a check
that means something now that `createdBy` is an authenticated principal.

---

## Step 11 — **Attempt to alter the audit trail, and watch it fail**

Read the trail as Rae, the auditor:

```sh
curl -sS "$BASE/audit/evidence/$PI" -H "X-Actor-Id: $RAE"
```

**Expected:** every state change of that instruction, in order, each carrying
the actor who caused it: `payment.created` and `payment.submitted` by Priya,
`approval.recorded` by Wei, `payment.approved` for the instruction,
`payment.amended` by Priya, and one `approval.invalidated` for the approval the
amendment revoked. The pack states the period it spans and
`"truncated": false`.

**Two of those are worth pausing on.**

`approval.recorded` and `payment.approved` are separate events because they are
separate facts: Wei made a decision, and the payment reached `approved`. On a
band requiring two approvals there would be two of the first and still one of
the second. Until finding **F-005** both were called `payment.approved`, so a
payment approved once appeared in the trail as approved twice — the earlier of
the two describing a payment that was still `pending-approval`, with identical
before and after digests because nothing had changed.

`approval.invalidated` names the **approval** as its subject, not the payment,
and it appears in this pack anyway because the pack relates a payment to its
approvals. Until finding **F-006** it did not exist: an amendment revoked
standing approvals and the trail said only that the payment had been amended,
leaving who lost their approval — and under which correlation id — to be
inferred from a column.

**Look at what is *not* there.** No creditor name, no account identifier — only
`beforeDigest` and `afterDigest`, each prefixed `v1:`. An audit table is
append-only, so anything in it is permanent; a digest proves the record has not
moved without becoming a second copy of the data.

Try to read it as Priya:

```sh
curl -sS -i "$BASE/audit/events" -H "X-Actor-Id: $PRIYA"
```

**Expected:** `403`. An operator able to read the whole trail could see which
approvers act on what and when.

Now attempt the alteration directly in SQL, which is what a defect or a
maintenance session would do:

```sql
update audit_event set actor_id = '22222222-2222-2222-2222-222222222222'
  where action = 'payment.approved';
```

**Expected:** refused — `Table audit_event is append-only: correct a posted
entry with a reversing entry, never by update`.

```sql
delete from audit_event;
```

**Expected:** refused, for every row.

Now the verb that was *missing* from this script, and from the schema, until
audit finding **F-002**:

```sql
truncate audit_event;
```

**Expected:** `ERROR: Table audit_event is append-only: … never by truncate`.

Until migration `0007` this **succeeded**, emptying the audit trail in one
statement immediately after the `UPDATE` and `DELETE` above had been correctly
refused. `TRUNCATE` is a separate trigger event with a separate privilege, and
the guard enumerated only two of the three verbs. Try it on the other guarded
tables too — all four must refuse:

```sql
truncate approval;
truncate journal_entry cascade;
truncate organisation cascade;   -- reaches the guarded tables by foreign key
```

**Expected:** all refused. The last one matters on its own: a `CASCADE` from an
*unguarded* parent still fires the children's triggers, so the guard cannot be
sidestepped by aiming one level up.

Then confirm nothing was lost:

```sql
select count(*) from audit_event;
```

### What none of this stops

Every step above runs as the database **owner**, and a trigger cannot bind the
owner of the table it is on:

```sql
alter table audit_event disable trigger audit_event_no_truncate;
truncate audit_event;   -- succeeds
```

**Expected:** it works. **This is not a defect in the fix** — it is the
residual risk [`COMPLIANCE.md` §4](../COMPLIANCE.md) names, and the reason the
runtime role split is recorded as debt. Under an application role that is
neither owner nor superuser, all of it is refused. Roll back rather than
leaving the guard down:

```sql
alter table audit_event enable trigger audit_event_no_truncate;
```

Record both results. A control's boundary is part of the control.

---

## Step 12 — The audit write and the change are one transaction

The strongest property here cannot be demonstrated by a request that succeeds —
it is demonstrated by one that fails. Wei has already decided on `$PI2`… no:
Nadia has. So have Wei try to approve `$PI2` twice, the second time after her
first has committed.

Instead, use the refusal you already produced in step 5. Priya's self-approval
attempt was refused. Check the trail:

```sql
select count(*) from audit_event
 where action in ('approval.recorded', 'payment.approved')
   and actor_id = '11111111-1111-1111-1111-111111111111';
```

**Expected:** zero. A refused approval leaves no approval row **and** no audit
event: the two commit together or not at all, so an event for something that did
not happen is not representable — and neither is a change with no event.

Both actions are named because a decision and a transition are different events
after finding **F-005**, and a query that checked only the transition would
miss a recorded decision that should not exist.

The automated suite asserts the converse directly, by rolling a transaction back
after the audit write has been issued on it
(`clofin.authz.repository-test/ac-10-a-rolled-back-change-leaves-no-audit-event`).
This step is the observable half.

---

## Recording your result

| Field | |
|---|---|
| Executed by | role, not name |
| Date | |
| Build | `git rev-parse --short HEAD` |
| Result | Pass / Fail / Blocked, per step |
| Evidence | command output, screenshot, or query result |
| Defects raised | issue references |

A step with no evidence is not a passed step. **A step that succeeded where this
script says it should fail is a defect, not a variation** — steps 5, 10 and 11
in particular.
