# UAT-002 — Ledger integrity cannot be bypassed

**Requirements:** PR-020, PR-021, PR-022 · **Controls:** C-03, C-04
**Prerequisites:** UAT-001 passed; the stack is running
**Estimated duration:** 20 minutes

## Purpose

This script is **adversarial by design**. It does not check that the ledger
works when used correctly — the automated suite already does that. It checks
that the ledger cannot be corrupted by someone with direct database access who
is actively trying, which is the scenario an auditor cares about and the one a
demonstration usually avoids.

Every step is executed as SQL against PostgreSQL, deliberately bypassing the
application entirely.

Open a database shell:

```bash
make db-shell
```

---

## Setup

```sql
-- Synthetic organisation and two accounts.
insert into organisation (id, legal_name, short_name)
values ('11111111-1111-1111-1111-111111111111',
        'Meridian Freight Holdings Pte Ltd', 'meridian-uat');

insert into ledger_account (id, organisation_id, code, name, type, currency)
values ('22222222-2222-2222-2222-222222222222',
        '11111111-1111-1111-1111-111111111111',
        '1100-CLIENT-FUNDS', 'Client funds - pooled', 'asset', 'SGD'),
       ('33333333-3333-3333-3333-333333333333',
        '11111111-1111-1111-1111-111111111111',
        '2100-CLIENT-PAYABLE', 'Client payable', 'liability', 'SGD');
```

**Expected:** both statements succeed.

---

## Steps

### Step 1 — A balanced entry commits

```sql
begin;
insert into journal_entry (id, organisation_id, occurred_at, narrative,
                           reference_type, reference_id)
values ('44444444-4444-4444-4444-444444444444',
        '11111111-1111-1111-1111-111111111111', now(),
        'UAT-002 opening balance', 'opening-balance',
        '55555555-5555-5555-5555-555555555555');

insert into journal_line (id, entry_id, line_no, account_id, direction, amount_minor, currency)
values (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 1,
        '22222222-2222-2222-2222-222222222222', 'debit', 125000, 'SGD'),
       (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 2,
        '33333333-3333-3333-3333-333333333333', 'credit', 125000, 'SGD');
commit;
```

**Expected:** `COMMIT` succeeds. **Pass criterion:** the entry exists with two
lines.

---

### Step 2 — An unbalanced entry cannot be committed

```sql
begin;
insert into journal_entry (id, organisation_id, occurred_at, narrative,
                           reference_type, reference_id)
values ('66666666-6666-6666-6666-666666666666',
        '11111111-1111-1111-1111-111111111111', now(),
        'UAT-002 deliberately unbalanced', 'opening-balance',
        '55555555-5555-5555-5555-555555555555');

insert into journal_line (id, entry_id, line_no, account_id, direction, amount_minor, currency)
values (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 1,
        '22222222-2222-2222-2222-222222222222', 'debit', 125000, 'SGD'),
       (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 2,
        '33333333-3333-3333-3333-333333333333', 'credit', 100000, 'SGD');
commit;
```

**Expected:** `COMMIT` **fails** with
`Journal entry ... does not balance in SGD: debits 125000, credits 100000`.

Then confirm nothing survived:

```sql
select count(*) from journal_entry where id = '66666666-6666-6666-6666-666666666666';
```

**Expected:** `0`.

**Pass criterion:** the database refused the entry even though the application
was never involved, and the whole transaction rolled back (C-04).

---

### Step 3 — The constraint is deferred, not merely present

Note in Step 2 that the *individual inserts* succeeded and only `COMMIT` failed.
That is deliberate: a non-deferred constraint would make it impossible to write
an entry one line at a time.

**Pass criterion:** the failure occurred at `COMMIT`, not at the second
`INSERT`.

---

### Step 4 — A posted entry cannot be amended

```sql
update journal_entry
   set narrative = 'Rewritten history'
 where id = '44444444-4444-4444-4444-444444444444';
```

**Expected:** fails with `Table journal_entry is append-only: correct a posted
entry with a reversing entry, never by update`.

```sql
update journal_line set amount_minor = 1
 where entry_id = '44444444-4444-4444-4444-444444444444';
```

**Expected:** fails with the same class of error.

**Pass criterion:** neither succeeds (C-03).

---

### Step 5 — A posted entry cannot be deleted

```sql
delete from journal_entry where id = '44444444-4444-4444-4444-444444444444';
delete from journal_line  where entry_id = '44444444-4444-4444-4444-444444444444';
```

**Expected:** both fail. **Pass criterion:** the entry and its lines are still
present afterwards.

---

### Step 6 — A negative line amount is impossible

```sql
begin;
insert into journal_line (id, entry_id, line_no, account_id, direction, amount_minor, currency)
values (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 3,
        '22222222-2222-2222-2222-222222222222', 'debit', -500, 'SGD');
rollback;
```

**Expected:** fails on `journal_line_amount_positive`.

**Pass criterion:** direction carries the sign; a negative amount is not a
second way to express the same movement.

---

### Step 7 — An unknown currency is impossible

```sql
begin;
insert into journal_line (id, entry_id, line_no, account_id, direction, amount_minor, currency)
values (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 4,
        '22222222-2222-2222-2222-222222222222', 'debit', 500, 'XYZ');
rollback;
```

**Expected:** fails on the foreign key to `currency`.

---

### Step 8 — Correction is by reversal, and the original stays visible

```sql
begin;
insert into journal_entry (id, organisation_id, occurred_at, narrative,
                           reference_type, reference_id, reverses_id)
values ('77777777-7777-7777-7777-777777777777',
        '11111111-1111-1111-1111-111111111111', now(),
        'Reversal of UAT-002 opening balance', 'reversal',
        '44444444-4444-4444-4444-444444444444',
        '44444444-4444-4444-4444-444444444444');

insert into journal_line (id, entry_id, line_no, account_id, direction, amount_minor, currency)
values (gen_random_uuid(), '77777777-7777-7777-7777-777777777777', 1,
        '22222222-2222-2222-2222-222222222222', 'credit', 125000, 'SGD'),
       (gen_random_uuid(), '77777777-7777-7777-7777-777777777777', 2,
        '33333333-3333-3333-3333-333333333333', 'debit', 125000, 'SGD');
commit;
```

**Expected:** succeeds. Then verify the balance has returned to zero:

```sql
select sum(case when direction = 'debit' then amount_minor else -amount_minor end)
         as balance_minor
  from journal_line
 where account_id = '22222222-2222-2222-2222-222222222222';
```

**Expected:** `0`.

**Pass criterion:** the account is back where it started, **and both entries are
still visible** — the error and the correction (C-03, PR-022).

---

### Step 9 — An entry can be reversed only once

Repeat Step 8's `journal_entry` insert with a new id, still pointing
`reverses_id` at `4444...`.

**Expected:** fails on `journal_entry_reverses_key`.

**Pass criterion:** a second reversal would silently reapply the original
movement, and is prevented.

---

## Step 6 — Try to empty the journal with `TRUNCATE`

`UPDATE` and `DELETE` were refused above. `TRUNCATE` is a **third** verb, with
its own trigger event and its own privilege — and until migration `0007` it was
not covered, so it emptied guarded tables in one statement past every guard
that had just refused the other two. That was audit finding **F-002**, and this
step exists so nobody has to take on trust that it is closed.

```sql
truncate journal_line, journal_entry cascade;
```

**Expected:** `ERROR: Table journal_line is append-only: … never by truncate`.

**Pass criterion:** the ledger cannot be emptied, by any of the three verbs.

> **Earlier versions of this script had it the other way round.** The teardown
> below used to say that `truncate` succeeding where `delete` failed was
> "deliberate — a schema-level reset for test environments must remain possible
> without weakening the row-level control". That sentence certified the defect
> as a design choice, in the acceptance evidence, signed off. It is recorded
> here rather than quietly deleted, because a UAT script that once blessed a
> hole is itself a finding worth remembering.

---

## Teardown

The journal cannot be truncated, and that is the point of step 6. Reset the
whole environment instead:

```bash
make db-reset
```

This drops and recreates the database and re-runs every migration, so the
guards come back armed. It is a schema-level operation performed by the
database owner — which is precisely the residual risk
[`COMPLIANCE.md` §4](../COMPLIANCE.md) names: a trigger is enforced by the
table, and the table's owner decides what the table is.

---

## Result

| Step | Result | Evidence | Notes |
|---|---|---|---|
| 1 Balanced entry commits | | | |
| 2 Unbalanced entry refused | | | |
| 3 Constraint is deferred | | | |
| 4 Amendment refused | | | |
| 5 Deletion refused | | | |
| 6 Negative amount refused | | | |
| 7 Unknown currency refused | | | |
| 8 Reversal restores balance | | | |
| 9 Double reversal refused | | | |

**Overall:** Pass / Fail
**Executed by:** ____________ **Date:** ____________ **Build:** ____________
