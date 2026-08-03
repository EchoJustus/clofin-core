# 001-REQ — Ledger persistence and account API

**Reviews:** [TASK-001](../briefs/001-TASK-ledger-persistence-and-account-api.md) ·
**Submitted for audit:** 2026-08-02 · **Brief status:** `IMPLEMENTED`

Submission from the worker session that executed TASK-001. It records what was
built, the decisions taken along the way, the edge cases found, and the debt
knowingly left behind. It is written to be read *before* the code, by a reviewer
who was not in the session.

---

## 1. What was built

| Namespace | Purpose |
|---|---|
| `clofin.organisations.organisation` | Organisation value type. Pure. |
| `clofin.organisations.repository` | `create-organisation!`, `find-organisation` |
| `clofin.ledger.repository` | Accounts, posting, entry reads, derived balances, statements |
| `clofin.api.wire` | Domain ↔ JSON, and the parsing vocabulary handlers share |
| `clofin.api.organisations` | `createOrganisation`, `getOrganisation` |
| `clofin.api.accounts` | `createAccount`, `listAccounts`, `getAccount`, `getAccountStatement` |
| `clofin.api.entries` | `postJournalEntry`, `getJournalEntry` |

Extended: `clofin.db.core` (column coercion, constraint-violation detail, `IN`
placeholders), `clofin.http.middleware` (`wrap-query-params`), `clofin.routes`,
`api/openapi.yaml`, `clofin.test-runner`.

**Verification.** `make verify` and `make test-it` both green:
**158 tests, 757 assertions, 0 failures, 0 errors** — from 83 / 303 at the start
of the increment. The baseline was confirmed green before any change was made.

Run against PostgreSQL 16.13. Docker was unavailable in the execution
environment, so the database was a local PostgreSQL instance configured to match
`docker-compose.yml` and the CI service block exactly (same database name, role,
password and port). No test or source file was adapted to accommodate this.

---

## 2. Decisions taken, and where they are recorded

Two ADRs were written before the code that depends on them, per
[`ADR/README.md`](../ADR/README.md).

### [ADR-0011](../ADR/0011-statement-periods-ordering-and-row-caps.md) — statement periods, ordering, row cap

- **Periods are half-open `[from, to)`.** Consecutive statements then chain
  exactly. The alternative — an inclusive end — makes the boundary instant
  belong to two periods at once, and expressing it as `23:59:59.999Z` silently
  drops anything in the following 999 microseconds, because `timestamptz` has
  microsecond resolution.
- **Movements are totally ordered by `(occurred_at, recorded_at, entry_id,
  line_no)`.** `occurred_at` is caller-supplied and routinely repeats across the
  lines of one economic event, so ordering by it alone leaves PostgreSQL free to
  return a different running-balance column on each run. A statement that
  reorders between runs cannot be used as evidence.
- **The closing balance is aggregated separately, never summed from the returned
  movements**, so it stays correct when the row cap truncates them.
- **`balance-at` is inclusive; `balance-strictly-before` is not.** Two functions
  rather than one with a flag, because the alternative is a boolean at every
  call site that a reviewer has to decode.

### [ADR-0012](../ADR/0012-repository-seam-and-posting-time-validation.md) — the persistence seam

- **`clofin.<context>.repository` is the only namespace per context that may
  require `clofin.db.*`.** The brief mandated the name `clofin.ledger.repository`,
  which sits under a namespace root whose existing members are pure. Rather than
  leave that ambiguous, the rule was made mechanical and is now asserted by
  `clofin.ledger.purity-test`, which reads the `ns` forms of the pure namespaces.
- **Rules that need database state live in `post-entry!`, inside the
  transaction:** the account exists *in this organisation*, accepts postings, and
  agrees with the line on currency. None can move into the pure layer, because
  the pure layer cannot see an account it was not handed. Validating before
  opening the transaction was rejected: an account frozen between the check and
  the insert would be posted to anyway.
- **`400` versus `422` is a contract, not a preference.** `400` — not
  understood. `422` — understood and refused. A payment client's error handling
  is built on that split.
- **A cross-organisation account reference is reported as unknown, not
  forbidden.** "Forbidden" would confirm that a guessed UUID exists and belongs
  to another tenant.

---

## 3. Acceptance criteria — where each is tested

| AC | Test | Namespace |
|---|---|---|
| AC-1 | `ac-1-a-balanced-entry-persists-with-all-its-lines` | `clofin.ledger.repository-test` |
| AC-1 | `ac-1-a-balanced-entry-is-created-and-both-lines-are-persisted` | `clofin.api.ledger-api-test` |
| AC-2 | `ac-2-an-unbalanced-entry-persists-nothing` | `clofin.ledger.repository-test` |
| AC-2 | `ac-2-an-unbalanced-entry-is-422-and-persists-nothing` | `clofin.api.ledger-api-test` |
| AC-2 | `ac-2-an-entry-unbalanced-in-only-one-of-two-currencies-names-that-currency` | `clofin.api.ledger-api-test` |
| AC-3 | `ac-3-a-balance-is-derived-from-the-journal` | `clofin.ledger.repository-test` |
| AC-4 | `ac-4-an-entry-referencing-a-frozen-or-closed-account-is-refused` | `clofin.ledger.repository-test` |
| AC-4 | `ac-4-an-entry-referencing-a-frozen-account-is-422-naming-the-account` | `clofin.api.ledger-api-test` |
| AC-5 | `ac-5-a-statement-adds-up` | `clofin.ledger.repository-test` |
| AC-5 | `ac-5-a-statement-adds-up-over-http` | `clofin.api.ledger-api-test` |
| AC-6 | `ac-6-a-reversal-returns-the-balance-and-leaves-both-entries-visible` | `clofin.ledger.repository-test` |
| AC-6 | `ac-6-a-reversal-posted-over-http-returns-the-balance` | `clofin.api.ledger-api-test` |
| AC-7 | `ac-7-an-entry-referencing-another-organisations-account-is-refused` | `clofin.ledger.repository-test` |
| AC-7 | `ac-7-an-entry-referencing-another-organisations-account-is-422` | `clofin.api.ledger-api-test` |
| AC-8 | `ac-8-an-entry-may-span-currencies-that-balance-within-each` | `clofin.ledger.repository-test` |
| AC-8 | `ac-8-an-entry-spanning-currencies-that-balance-within-each-succeeds` | `clofin.api.ledger-api-test` |
| AC-9 | `clofin.contract-test` — **passed unmodified** | `clofin.contract-test` |

AC-9 deserves a note: the contract test was not touched. `api/openapi.yaml` was
written to match the route table, not the reverse.

AC-5 is the criterion the endpoint exists for, so it is asserted three ways: the
arithmetic (`opening + Σmovements = closing`), the running column reaching the
closing balance, and — separately — that consecutive periods chain across a
boundary instant that *has* a movement on it
(`a-statement-period-is-half-open-so-consecutive-periods-chain`).

---

## 4. Deviations from the brief

Three, all additive, all declared in `api/openapi.yaml`.

**4.1 Two extra endpoints: `getJournalEntry` and `getOrganisation`.**

The brief specifies six operations and requires `POST /journal-entries` to
return "`201` with a `Location` header". With only the six, that header would
point at a route that does not exist — a `201` whose `Location` returns `404`.
`GET /journal-entries/:id` makes it honest and gives `find-entry` — mandated by
the brief's repository interface — a caller. `GET /organisations/:id` does the
same for `find-organisation` and for `POST /organisations`.

A reviewer who disagrees should say so: the alternative is to drop the `Location`
headers, which is a smaller API but a worse one. There is a test asserting the
header resolves (`the-location-header-of-a-created-account-actually-resolves`).

**4.2 `clofin.organisations.organisation` — a pure namespace the brief did not
list.**

The brief scoped `clofin.organisations.repository` only. Putting the field rules
in the repository would have been the smaller diff and would have made the
organisations context the one place where domain validation lives in persistence
— the exact shape ARCHITECTURE.md §4 forbids. ~45 lines; TASK-003 needs
organisation status rules anyway.

**4.3 A currency-agreement rule the brief did not name.**

A line whose currency differs from its account's is refused with `422`. Not an
acceptance criterion, but `clofin.ledger.account/balance` throws when a posting's
currency differs from the account's — so without this check the entry commits
and every *later* balance read on that account fails. A write that makes a
subsequent read impossible is worse than a rejected write.

Also worth flagging as an addition rather than a deviation: **`wrap-query-params`
was added to the middleware chain.** `GET` endpoints need `organisationId`,
`from` and `to`, and there was no query-string parsing in the codebase. It is
inside the error boundary, so a malformed query string is a `400`.

---

## 5. Edge cases found and handled

| Edge case | Handling |
|---|---|
| **Row cap truncates a statement.** Summing the returned movements would give a wrong closing balance, invisibly. | Closing balance aggregated over the whole journal; `truncated` on every response. Tested with 502 lines ordered so the capped view is provably unrepresentative — closing is `0`, the 500 returned movements sum to `600`. |
| **Two lines of one entry touching the same account.** Indistinguishable on a statement. | `lineNo` returned per movement, and part of the sort key. Asserted in `ac-5-a-statement-adds-up-over-http`, where a withdrawal's cash line is line 2 of its entry. |
| **Movements sharing an `occurred_at`.** | Total order including `recorded_at`, `entry_id`, `line_no`. `a-statement-is-the-same-document-every-time-it-is-produced` posts five entries at one instant and compares two runs. |
| **`sum(bigint)` returns `numeric`.** Arrives as `BigDecimal`, which `money/of` rejects — correctly, since it is not `integer?`. | `db/->long` uses `longValueExact`: an aggregate that has overflowed a long fails rather than truncating. Money is involved. |
| **An account with no postings.** | Zero in the account's currency, not `nil` and not a missing key. Asserted. |
| **An empty period (`from` = `to`).** | Legal. No movements; opening equals closing. `from` after `to` is a `400`. |
| **Posting inside a caller's existing transaction.** `db/with-transaction*` requires a pool and would have failed on a connection. | `post-entry!` detects a `java.sql.Connection` and joins the caller's transaction. Tested by rolling the caller back after a successful post and asserting the entry went with it — this is what TASK-002 needs. |
| **Second reversal of one entry.** Unique index `journal_entry_reverses_key` raises `23505`. | Translated to `409` with a message saying why, rather than a `500`. |
| **Duplicate entry id, duplicate account code, duplicate short name.** | `23505` → `409`. |
| **JPY, KRW, VND have no minor unit.** | Never assumed. The `422` imbalance body formats at each currency's own scale — a JPY shortfall renders `"100"`, an SGD one `"250.00"`. Asserted. |
| **Uppercase organisation short name.** Would defeat the `lower(short_name)` unique index if allowed through. | Refused by the value type. The schema index is asserted separately by inserting directly, bypassing the domain. |
| **`reverses_id` is null for most entries.** `setNull(Types/NULL)` against a `uuid` column. | Verified working against PostgreSQL 16 rather than assumed. |

---

## 6. Technical debt, stated plainly

Ordered by what would hurt first.

**6.1 No pagination — `blocking` for any real dataset, `consider` today.**
List and statement responses cap at 500 rows and set `truncated`. A caller
holding a large account cannot read past the first 500 movements of a period by
any means except narrowing the period. Deferred deliberately per the brief; a
cursor contract designed without a consumer would be guesswork. **The cap is one
constant, `clofin.ledger.repository/row-cap`, referenced by the OpenAPI text.**

**6.2 No performance measurement at all.**
The statement sort `(occurred_at, recorded_at, entry_id, line_no)` is not served
by an index; `journal_line (account_id)` narrows the scan and PostgreSQL sorts
the remainder. No benchmark exists, so the honest statement is that statement
performance is *unknown*, not that it is adequate. ADR-0008 permits a `*_snapshot`
cache — after measurement, never before. **Do not add one on this document's
say-so.**

**6.3 `post-entry!` reads before it writes.**
One round trip to load referenced accounts, then the inserts. Correct and inside
the transaction; simply not free.

**6.4 The organisation is caller-supplied.**
`clofin.api.wire/read-organisation-id` carries the `TODO(TASK-003)` the brief
asked for. Every read of that value is a place TASK-003 must change, which is why
it is one function. **Nothing in this increment is an access control**, and the
OpenAPI description and UAT-003 Step 12 both say so in as many words.

**6.5 Account lifecycle is not an API operation.**
Freezing and closing are enforced on posting but can only be *effected* in SQL.
The transitions need authorisation and an audit trail, so they belong with
TASK-003. UAT-003 Step 11 uses `psql` for this reason and flags it.

**6.6 `find-entry` and `list-entries-for-account` issue a second query for
lines.**
Two queries rather than one join, to keep the row-mapping simple. `lines-for`
uses an `IN` list built from `db/placeholders` — the *count* of placeholders
comes from the collection size; every value is still bound. At the 500-row cap
that is a 500-parameter statement, which PostgreSQL handles but which would need
revisiting alongside 6.1.

**6.7 `list-entries-for-account` is repository-only.**
Mandated by the brief, no endpoint. It is exercised by tests, not dead, and
TASK-002 is its expected consumer.

**6.8 The JSON contract is not validated against `api/openapi.yaml` at runtime.**
The contract test asserts that routes and operations correspond, not that a
response body matches its declared schema. A field could drift from its schema
without a test failing. Worth an increment of its own if the API grows.

---

## 7. What a reviewer should look at first

1. **`assert-postable!` in `clofin.ledger.repository`** — the security-relevant
   function. Everything about tenancy isolation in this increment is there.
2. **`statement` and `with-running-balance`** — check the half-open boundary
   against ADR-0011, and that the closing balance is never derived from the
   movement list.
3. **The `400` / `422` split in `clofin.api.entries`** — whether the line drawn
   in ADR-0012 is the right one.
4. **Deviation 4.1** — the two extra endpoints. This is the deviation most
   likely to be judged differently by the architect than by the implementer.
5. **`clofin.ledger.purity-test`** — whether asserting the layering rule from
   `ns` forms is robust enough to be worth relying on.

## 8. Questions for the audit

- Are the two extra `GET` endpoints (4.1) accepted, or should the `Location`
  headers be dropped instead?
- Is `422` the right status for a frozen-account posting, or is `409` closer to
  the intent — the account is in a conflicting *state* rather than the request
  being unprocessable?
- The row cap is 500 for lists and statements alike. Should a statement's cap be
  higher, given it is the endpoint most likely to meet it first?
