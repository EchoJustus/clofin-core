# UAT-008 — Screening: a hit, a case, and the decision that is never the maker's

**Covers:** sanctions screening against a versioned synthetic list at every
submission, the case a hit opens, its segregated disposition, a client's
screening result recorded as evidence and never as authorisation, and the
trail behind all of it
**Requirements:** PR-060, PR-061, PR-063, PR-015 (the queue shows the screening
outcome); PRD Q2 (resolved)
**Controls:** [C-07](../COMPLIANCE.md), C-01, C-05, C-08
**Brief:** `docs/briefs/017-TASK-screening-and-cases.md`; [ADR-0028](../ADR/0028-satellite-clients-integrate-through-core-owned-contracts.md) D5, D8 and Amendment 1

---

## What this script is for

C-07 says no instruction can be released without a completed screening
decision, and that a hit blocks it pending disposition. **You will try to get a
hit through, and watch each way fail**: by submitting it, by having its maker
clear it, by sending core a client's `clear` it cannot reproduce, and by
removing the list it is screened against.

You will also watch the one thing a client's result *can* do — be recorded —
and confirm by reading the instruction back that **recording it changed
nothing**.

**The list is synthetic and the matching is exact.** CloFin's screening list is
CloFin-defined data, not derived from any real sanctions or watch list; a rule
matches by string equality with no normalisation; nothing here makes any claim
about real-world screening quality. Everything below is synthetic. CloFin is
not connected to any bank, payment scheme or central bank and holds no
regulatory authorisation.

If a step *succeeds* where this script says it should fail, stop and raise a
defect. That is the outcome this document exists to detect.

---

## Before you start

| | |
|---|---|
| Prerequisite | `make up` has completed and `make ready` answers, against a **fresh** database (`make destroy` first if in doubt) |
| Tools | `curl`, `psql` (via `make db-shell`), `uuidgen`, and a terminal |
| Time | About 25 minutes |
| Data | Synthetic only. The list's names, accounts and the `ZZ` code (user-assigned; it names no country) are invented |

```sh
export BASE=http://localhost:8080
export VERSION=synthetic-2026-10-v1
```

**Do not export `LIST`.** `make load-screening-list` reads `LIST` as the path of
the list file to load, and an exported `LIST` holding a version would be taken
for that path (found by running this script against a fresh stack before it was
filed, lesson L-20).

Conventions, as in UAT-005:

- Every payment, approval and screening mutation needs an `Idempotency-Key` —
  the eight operations `clofin.idempotency/protected-operations` names. Use a
  fresh one each time (`uuidgen`).
- Every request except organisation creation needs an `X-Actor-Id`. **This is
  not authentication that resists an adversary**; it names a seeded actor, and
  a screening client's id is configured identity, not a credential (ADR-0028
  D8).

---

## Step 1 — Load the shipped list, the only way a list is loaded

There is **no API operation that loads a list**: a client that could load the
list it is screened against would make the control unenforceable. Load it with
the operator's tool:

```sh
make load-screening-list
```

**Expected:** `Loaded screening list synthetic-2026-10-v1 (4 entries). Synthetic list, exact matching.`

Run it again:

```sh
make load-screening-list
```

**Expected:** refused — `Screening list synthetic-2026-10-v1 is already loaded;
a changed list is a new version`, `reason: version-already-loaded` — and a
non-zero exit. A loaded version never changes. (Loading a *different* version
while one is accepted is refused too unless `REPLACING=` names it: exactly one
list is accepted at a time.) *[PR-063]*

---

## Step 2 — An organisation, its actors, and an account

```sh
ORG=$(curl -sS -X POST $BASE/organisations \
  -H 'content-type: application/json' \
  -d '{"legalName":"Meridian Freight Holdings Pte Ltd","shortName":"meridian-uat8"}' \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "organisation: $ORG"
```

Seed the actors in SQL (`make db-shell`). Read what you grant: it is the
control's configuration.

```sql
\set org 'PASTE-THE-ORG-UUID-HERE'

-- Priya raises payments. She ALSO holds compliance — the one actor for whom
-- the permission check passes and only the provenance rule can refuse her.
insert into actor (id, organisation_id, display_name)
  values ('81111111-1111-4111-8111-111111111111', :'org', 'Priya (maker, also compliance)');
insert into actor_role (actor_id, role) values
  ('81111111-1111-4111-8111-111111111111', 'operator'),
  ('81111111-1111-4111-8111-111111111111', 'compliance');

-- Cleo dispositions screening cases. She cannot raise, submit or approve.
insert into actor (id, organisation_id, display_name)
  values ('82222222-2222-4222-8222-222222222222', :'org', 'Cleo (compliance)');
insert into actor_role (actor_id, role)
  values ('82222222-2222-4222-8222-222222222222', 'compliance');

-- The screening client's seeded identity: it records evidence and reads.
insert into actor (id, organisation_id, display_name)
  values ('83333333-3333-4333-8333-333333333333', :'org', 'Screening client');
insert into actor_role (actor_id, role)
  values ('83333333-3333-4333-8333-333333333333', 'screening-service');

-- Wei approves, and reads the queue.
insert into actor (id, organisation_id, display_name)
  values ('84444444-4444-4444-8444-444444444444', :'org', 'Wei (checker)');
insert into actor_role (actor_id, role)
  values ('84444444-4444-4444-8444-444444444444', 'approver');
insert into approver_limit (actor_id, currency, limit_minor)
  values ('84444444-4444-4444-8444-444444444444', 'SGD', 5000000);

-- Sam opens the account.
insert into actor (id, organisation_id, display_name)
  values ('85555555-5555-4555-8555-555555555555', :'org', 'Sam (controller)');
insert into actor_role (actor_id, role)
  values ('85555555-5555-4555-8555-555555555555', 'controller');

-- Rae reads the trail.
insert into actor (id, organisation_id, display_name)
  values ('86666666-6666-4666-8666-666666666666', :'org', 'Rae (auditor)');
insert into actor_role (actor_id, role)
  values ('86666666-6666-4666-8666-666666666666', 'auditor');

insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
  values (:'org', 'SGD', 0, 1);
```

```sh
export PRIYA=81111111-1111-4111-8111-111111111111
export CLEO=82222222-2222-4222-8222-222222222222
export CLIENT=83333333-3333-4333-8333-333333333333
export WEI=84444444-4444-4444-8444-444444444444
export SAM=85555555-5555-4555-8555-555555555555
export RAE=86666666-6666-4666-8666-666666666666
export VD=$(date -d '+7 days' +%F 2>/dev/null || date -v+7d +%F)

ACCT=$(curl -sS -X POST $BASE/accounts \
  -H "X-Actor-Id: $SAM" -H 'content-type: application/json' \
  -d '{"code":"1100-CLIENT-FUNDS","name":"Client funds — pooled","type":"asset","currency":"SGD"}' \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "account: $ACCT"
```

Read the list as the screening client — the list a decision names, entry by
entry:

```sh
curl -sS $BASE/screening-lists/$VERSION -H "X-Actor-Id: $CLIENT"
```

**Expected:** four entries, `SYN-0001` … `SYN-0004`, `"accepted":true`.
`SYN-0001` is the creditor name `Blocked Counterparty Ltd`. *[PR-063]*

---

## Step 3 — Raise a payment that hits, and watch `submit` refuse it

```sh
PI=$(curl -sS -X POST $BASE/payment-instructions \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"debtorAccountId\":\"$ACCT\",\"creditorName\":\"Blocked Counterparty Ltd\",\"creditorAccount\":\"SG-SYNTH-88012345\",\"amount\":{\"currency\":\"SGD\",\"minorUnits\":50000},\"valueDate\":\"$VD\",\"purposeCode\":\"SUPP\"}" \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "instruction: $PI"

curl -sS -i -X POST $BASE/payment-instructions/$PI/submission \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{}' | tee /tmp/uat8-submit-1
CASE=$(sed -n 's/.*"caseId":"\([^"]*\)".*/\1/p' /tmp/uat8-submit-1)
echo "case: $CASE"
```

**Expected:** `409`, `errors.reason` `screening-hit`, an `errors.caseId`, and
`errors.listVersion` `synthetic-2026-10-v1`. Core screened the instruction
itself, at the submission, against the accepted list. Read it back:

```sh
curl -sS $BASE/payment-instructions/$PI -H "X-Actor-Id: $PRIYA"
```

**Expected:** still `"status":"draft"`. *[PR-060, PR-061]*

---

## Step 4 — Read the case

```sh
curl -sS $BASE/screening-cases/$CASE -H "X-Actor-Id: $CLEO"
```

**Expected:** `"status":"open"`, `"listVersion":"synthetic-2026-10-v1"`, an
`instructionDigest` equal to the instruction's `screeningDigest`, and
`"permittedTransitions":["disposition"]`. The case is bound to *this* content
against *this* list. *[PR-061, PR-063]*

---

## Step 5 — **The maker tries to clear her own hit, and is refused**

Priya holds compliance, so she has the permission. She is refused anyway:

```sh
curl -sS -i -X POST $BASE/screening-cases/$CASE/disposition \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"disposition":"false-positive","rationale":"It is fine, I know them"}'
```

**Expected:** `403`, `errors.reason` `self-disposition`. The answer is not "ask
for a permission" — no grant makes the maker someone else (C-01's shape). *[PR-061]*

---

## Step 6 — Compliance dispositions it

```sh
curl -sS -X POST $BASE/screening-cases/$CASE/disposition \
  -H "X-Actor-Id: $CLEO" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"disposition":"false-positive","rationale":"Synthetic name collision: the beneficiary account and purpose identify a different counterparty from SYN-0001"}'
```

**Expected:** `200`, `"status":"dispositioned"`, `"disposition":"false-positive"`,
the rationale as written, `"dispositionedBy"` equal to `$CLEO`, and
`"permittedTransitions":[]`. Send the same request again with a new key:

**Expected:** `409` — a disposition is final. *[PR-061, PR-063]*

---

## Step 7 — Submit again

```sh
curl -sS -X POST $BASE/payment-instructions/$PI/submission \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{}'
```

**Expected:** `200`, `"status":"pending-approval"`. Core screened it again — it
is still a hit — and found the false-positive disposition for this content
against this list. (Retrying step 3's request under step 3's **key** would
replay step 3's `409`: the key is bound to that refusal, because its evidence
committed. A new attempt takes a new key.) *[PR-060]*

---

## Step 8 — The checker sees the screening outcome beside the amount

```sh
curl -sS $BASE/approvals/queue -H "X-Actor-Id: $WEI"
```

**Expected:** the row for `$PI` carries `"screening"` with a `resultId`,
`"outcome":"hit"`, `"listVersion":"synthetic-2026-10-v1"` and a `recordedAt` —
core's latest decision over this content, a hit compliance dispositioned
false-positive in step 6. An approver deciding this payment is not deciding
blind about its screening. *[PR-015]*

---

## Step 9 — Amend it, and watch the old disposition stop counting

An amendment returns the instruction to `draft` and changes its content, so the
disposition given on the old content is moot:

```sh
curl -sS -X PATCH $BASE/payment-instructions/$PI \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount":{"currency":"SGD","minorUnits":60000}}'

curl -sS -i -X POST $BASE/payment-instructions/$PI/submission \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{}' | tee /tmp/uat8-submit-3
```

**Expected:** the `PATCH` answers `"status":"draft"` and a **different**
`screeningDigest`; the submission answers `409 screening-hit` with an
`errors.caseId` that is **not** `$CASE` — a fresh decision, and a new case,
because a disposition decides a hit on the content it was made about. This is
PRD Q2's answer: screening runs at every submission, and an amendment cannot
carry a clearance across. *[PR-060, PR-061]*

---

## Step 10 — A client's result core cannot reproduce

The screening client reads the digest core holds and sends a `clear`:

```sh
DIGEST=$(curl -sS $BASE/payment-instructions/$PI -H "X-Actor-Id: $CLIENT" \
  | sed -n 's/.*"screeningDigest":"\([0-9a-f]*\)".*/\1/p')
echo "digest: $DIGEST"

curl -sS -i -X POST $BASE/payment-instructions/$PI/screening-results \
  -H "X-Actor-Id: $CLIENT" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"organisationId\":\"$ORG\",\"listVersion\":\"$VERSION\",\"outcome\":\"clear\",\"matchedEntries\":[],\"instructionDigest\":\"$DIGEST\"}"
```

**Expected:** `422`, `errors.reason` `screening-result-mismatch`,
`errors.coreOutcome` `hit` and `errors.coreMatchedEntries` `["SYN-0001"]`.
And the refused result **is stored** — the disagreement is evidence:

```sh
curl -sS "$BASE/screening-results?instructionId=$PI" -H "X-Actor-Id: $CLEO"
```

**Expected:** the newest row is `"origin":"client"`, `"outcome":"clear"`,
`"coreOutcome":"hit"`, `"agrees":false`, `"disposition":"refused"`,
`"dispositionReason":"screening-result-mismatch"`, with an `auditEventId`.
Beneath it, core's own results from steps 3, 7 and 9. *[PR-063]*

---

## Step 11 — A client's result core can reproduce: evidence, and nothing else

```sh
curl -sS -i -X POST $BASE/payment-instructions/$PI/screening-results \
  -H "X-Actor-Id: $CLIENT" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"organisationId\":\"$ORG\",\"listVersion\":\"$VERSION\",\"outcome\":\"hit\",\"matchedEntries\":[{\"id\":\"SYN-0001\",\"rules\":[{\"field\":\"creditor-name\",\"operator\":\"exact\",\"value\":\"Blocked Counterparty Ltd\"}]}],\"instructionDigest\":\"$DIGEST\"}"
```

**Expected:** `201`, `"disposition":"accepted"`, `"agrees":true`, an
`auditEventId`, and a `caseId` naming the case step 9 opened. **Now read the
instruction back:**

```sh
curl -sS $BASE/payment-instructions/$PI -H "X-Actor-Id: $PRIYA"
```

**Expected:** `"status":"draft"`, with the same `permittedTransitions` as
before. **Say so in your evidence: the `201` recorded evidence and changed no
state.** It is not a transition acknowledgment, and its `auditEventId` is not
one either; the only acknowledgment of a transition is the instruction `submit`
returns with `status: pending-approval`. Confirm no `payment.*` event was
written for it:

```sql
select action from audit_event
 where subject_id = 'PASTE-$PI-HERE' order by occurred_at, id;
```

**Expected:** `payment.created`, `payment.submitted`, `payment.amended` — the
submission of step 7 and the amendment of step 9, nothing from steps 10 or 11.
*[PR-060, PR-063]*

---

## Step 12 — Retire the list, and watch submission refuse rather than pass

```sh
clojure -M:screening-list retire $VERSION     # or: docker compose run --rm toolchain clojure -M:screening-list retire $VERSION
```

**Expected:** `Retired screening list synthetic-2026-10-v1. No list is accepted: every submission is refused until one is loaded.`

Raise an ordinary payment — a name the list does not hold — and submit it:

```sh
PI2=$(curl -sS -X POST $BASE/payment-instructions \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"debtorAccountId\":\"$ACCT\",\"creditorName\":\"Pacific Rim Logistics Pte Ltd\",\"creditorAccount\":\"SG-SYNTH-88012345\",\"amount\":{\"currency\":\"SGD\",\"minorUnits\":20000},\"valueDate\":\"$VD\",\"purposeCode\":\"SUPP\"}" \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

curl -sS -i -X POST $BASE/payment-instructions/$PI2/submission \
  -H "X-Actor-Id: $PRIYA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{}'
```

**Expected:** `422`, `errors.reason` `no-screening-list-accepted`, and the
instruction still `draft`. With nothing to screen against, nothing is screened
and nothing is submitted — never an empty list standing in for one, which would
make every instruction clear. *[PR-060]*

---

## Step 13 — The trail

```sh
curl -sS $BASE/audit/evidence/$CASE -H "X-Actor-Id: $RAE"
curl -sS "$BASE/audit/events?action=screening-result.recorded" -H "X-Actor-Id: $RAE"
```

**Expected:** the first case's evidence pack is exactly
`screening-case.opened` then `screening-case.dispositioned`, by Priya's
submission and Cleo's decision respectively; `subjectType` `screening-case`.
The event list holds one `screening-result.recorded` per result recorded —
five: the four step 10's listing showed (core's three and the client's refused
one) and the client's accepted result from step 11.
Every decision is reproducible from what was retained: the list version, the
digest, the entries, the actor and the time. *[PR-063]*

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
script says it should fail is a defect, not a variation** — steps 3, 5, 6's
second disposition, 9, 10 and 12 in particular. Step 11's `201` is the one
success that must change **nothing**, and step 11's read-back is what proves it.
