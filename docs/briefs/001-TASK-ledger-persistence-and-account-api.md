# TASK-001: Ledger persistence and account API

| Field | Value |
|---|---|
| **Increment** | 2 |
| **Status** | `IMPLEMENTED` |
| **Depends on** | — (increment 1 is merged and green) |
| **Blocks** | TASK-002, TASK-003 |
| **Requirements** | PR-020, PR-021, PR-022, PR-023, PR-024 |
| **Controls touched** | C-03, C-04 |
| **Scope** | Medium |
| **Audit** | Requested — `001-REQ` filed by the Worker on `claude/ledger-persistence-account-api-p5oi05` (PR #2); `FEEDBACK-001` outstanding |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

The ledger domain model exists and is proven correct in memory, and the schema
exists in PostgreSQL, but **nothing connects them**. After this task, a caller
can create an organisation and ledger accounts over HTTP, post a balanced
journal entry, and produce an account statement showing opening balance,
movements and closing balance for a period — with every balance derived from the
journal rather than stored.

## Context you need

Read these. You should not need to search for anything else.

| Source | What it gives you |
|---|---|
| [ADR-0008](../ADR/0008-double-entry-journal-as-source-of-truth.md) | Why balances are derived and entries immutable |
| [ADR-0003](../ADR/0003-money-as-integer-minor-units.md) | The money representation you must persist |
| [ADR-0006](../ADR/0006-postgresql-as-system-of-record.md) | Why constraints live in the schema |
| [ADR-0010](../ADR/0010-thin-ring-compatible-http-adapter.md) | Handler and router conventions |
| `src/clofin/ledger/entry.clj` | `entry`, `line`, `imbalance`, `reverse-entry`, `transfer-lines` |
| `src/clofin/ledger/account.clj` | `account`, `balance`, `signed-amount`, `normal-balance` |
| `src/clofin/db/core.clj` | `query`, `execute!`, `insert-returning!`, `with-transaction`, `with-connection` |
| `resources/migrations/0002-ledger-accounts-and-journal.sql` | The tables you are writing to |
| `src/clofin/routes.clj`, `src/clofin/api/health.clj` | The pattern every handler follows |
| `test/clofin/test_db.clj` | Fixtures: `with-pool`, `with-migrated-schema`, `with-clean-data` |

**Existing behaviour you must not break:** the domain layer is pure. Ledger
namespaces require nothing from `clofin.db.*` or `clofin.http.*`. Persistence
goes in **new** namespaces.

## Scope

### In

1. **`clofin.ledger.repository`** — persistence for accounts and entries.
   - `create-account!`, `find-account`, `list-accounts`
   - `post-entry!` — writes the entry and all its lines in one transaction
   - `find-entry`, `list-entries-for-account`
   - `balance-at` — derived balance for an account as at an instant
   - `statement` — opening balance, movements, closing balance for a period
2. **`clofin.organisations.repository`** — `create-organisation!`, `find-organisation`.
3. **`clofin.api.accounts`** and **`clofin.api.entries`** — HTTP handlers.
4. Route table entries and matching OpenAPI operations.
5. Serialisation helpers: domain value ↔ JSON, using `money/->wire`.
6. Tests: handlers as plain functions; repository against real PostgreSQL.

### Out — and why

| Out of scope | Reason |
|---|---|
| Authentication and authorisation | TASK-003. Take `organisationId` from the request body for now, and leave a `TODO(TASK-003)` comment where the authenticated principal will come from. |
| Payment instructions | TASK-002. `reference.type` may be `opening-balance` here. |
| Idempotency keys | TASK-002, and it applies to payments rather than to raw entries. |
| Pagination | Return at most 500 rows and document the cap in OpenAPI. Real pagination when there is a consumer. |
| Balance snapshots or caching | Measure first. ADR-0008 permits a snapshot only as an explicitly derived cache. |
| A UI | Increment 8. |

## Interfaces

### Repository

```clojure
(create-account! source account)          ;=> account
(find-account source organisation-id id)  ;=> account | nil
(list-accounts source organisation-id)    ;=> [account]

(post-entry! source entry)                ;=> entry
;; Must run in ONE transaction: the entry row, then every line.
;; Rely on the deferred trigger for the zero-sum check as well as the
;; domain constructor — do not weaken either.

(balance-at source account as-of)         ;=> money
(statement source account {:from t :to t})
;=> {:account         account
;    :from            inst
;    :to              inst
;    :opening-balance money
;    :closing-balance money
;    :movements       [{:entry-id … :occurred-at … :narrative …
;                       :direction :debit|:credit :amount money
;                       :running-balance money}]}
```

`statement` must derive `opening-balance` from every line strictly before
`from`, and `running-balance` must reach `closing-balance` on the last movement.
A test asserting that is not optional — it is the point of the endpoint.

### HTTP

| Method | Path | Operation id |
|---|---|---|
| `POST` | `/organisations` | `createOrganisation` |
| `POST` | `/accounts` | `createAccount` |
| `GET` | `/accounts/:id` | `getAccount` |
| `GET` | `/accounts` | `listAccounts` |
| `POST` | `/journal-entries` | `postJournalEntry` |
| `GET` | `/accounts/:id/statement` | `getAccountStatement` |

`POST /journal-entries` request:

```json
{
  "organisationId": "…uuid…",
  "occurredAt": "2026-08-02T10:15:00Z",
  "narrative": "Opening balance",
  "reference": { "type": "opening-balance", "id": "…uuid…" },
  "lines": [
    { "accountId": "…", "direction": "debit",
      "amount": { "currency": "SGD", "minorUnits": 125000 } },
    { "accountId": "…", "direction": "credit",
      "amount": { "currency": "SGD", "minorUnits": 125000 } }
  ]
}
```

Response `201` with a `Location` header and the persisted entry.
An unbalanced entry is `422` with the per-currency shortfall in `errors`.

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given an organisation and two active accounts, when a balanced entry is posted, then it returns `201` and both lines are persisted. | PR-020 |
| AC-2 | Given an unbalanced entry, when it is posted, then it returns `422`, the body names the shortfall per currency, and **nothing** is persisted. | PR-020 |
| AC-3 | Given posted entries, when the balance is requested, then it is computed from the journal — verified by a test that adds an entry and sees the balance move. | PR-021 |
| AC-4 | Given an entry referencing a frozen or closed account, when it is posted, then it returns `422` naming the account. | PR-020 |
| AC-5 | Given entries across a period, when a statement is requested, then opening + sum(movements) = closing, and the last running balance equals the closing balance. | PR-023 |
| AC-6 | Given a posted entry, when a reversal is posted, then both entries exist and the account's balance returns to its prior value. | PR-022 |
| AC-7 | Given an entry whose line references an account in another organisation, when it is posted, then it returns `422`. | PR-020 |
| AC-8 | Given an entry mixing currencies that balance within each, when it is posted, then it succeeds. | PR-024 |
| AC-9 | Every new route has a matching OpenAPI operation — `clofin.contract-test` passes **without modification to make it pass**. | NFR-003 |

## Definition of done

- [ ] Every acceptance criterion has a named test
- [ ] `api/openapi.yaml` updated in the same commit as the handlers
- [ ] `make verify` and `make test-it` both green
- [ ] New test namespaces added to `clofin.test-runner`
- [ ] Completion reported — PR opened, `001-REQ` filed — so Master Control can
      set this brief to `IMPLEMENTED` and update `ROADMAP.md` on `meta`
- [ ] A UAT script added under `docs/uat/`
- [ ] An ADR for any decision a future contributor would otherwise re-derive —
      most likely: how a statement's running balance is computed, and what the
      row cap means for the API contract

## Notes for whoever picks this up

The tempting shortcut is a `balance` column on `ledger_account`. **Don't.**
ADR-0008 exists because that shortcut is the most common cause of unexplainable
balances in financial systems, and the schema has no such column by design. If
statement performance is genuinely a problem, measure it, then propose a
`*_snapshot` table in an ADR — as a cache, never as authority.
