# FEEDBACK-REL-ref-1 — release audit for `ref-1` (**PARTIAL**)

| Field | Value |
|---|---|
| **Date** | 2026-08-05 |
| **Status** | **Partial audit — closed under the resource-interruption fallback.** Ingested by Master Control; findings triaged and remediated. |
| **Audited code ref (RC)** | `main` `5d21334c1bfc59aba702e094155eea03dc9b1ef0` |
| **Control-plane ref** | `origin/meta` `70387b21f08811486a05c0314c37f350fc8bc72f` |
| **Provenance** | GPT-5.6 Sol · CodeSpace path · 2026-08-05 · reasoning effort not recorded in the workpapers |
| **Result** | **19 findings (A-001…A-019)** — Master Control severity: **2 blocking, 17 should-fix, 0 consider** |

> ## Coverage — read this before trusting any conclusion drawn from this file
>
> **This audit is incomplete.** The commissioned charter mandates eight scope
> items. Coverage actually achieved:
>
> | Item | Scope | Status |
> |---|---|---|
> | 1 | Migrations replayed from an empty schema | ✅ **performed** — `0001`–`0010` in index order against a fresh PostgreSQL 16; schema version `0010`, 10 applied, 0 pending |
> | 2 | Full suite at the RC | ✅ **performed** — `make verify` 272 tests / 1,504 assertions + docs-check 46 files; `make test-it` **584 tests / 3,644 assertions**, 0 failures, 0 errors |
> | 3 | Cross-document consistency | ✅ **performed** — checks 3.1–3.17 below |
> | 4 | The partial-set sweep | ✅ **performed** — checks 4.1–4.9 below |
> | 5 | Standing-lessons compliance (L-1…L-13) | ❌ **NOT PERFORMED** |
> | 6 | Known-debt reconciliation | ❌ **NOT PERFORMED** |
> | 7 | Synthetic-data and neutrality sweep | ❌ **NOT PERFORMED** |
> | 8 | Citation discipline re-verification | ⚠️ **partial** — the auditor cited file, line and verbatim quote throughout, but did not run the final self-verification pass |
>
> **Why it stopped:** the external auditor's monthly compute quota was exhausted
> mid-audit. Handled under the resource-interruption fallback recorded in the
> assurance-chain decision of 2026-08-05 ([`README.md`](README.md)): triage and
> remediate the partial findings, then release; do not resume.
>
> **Consequence for `ref-1`:** the tag denotes a release candidate audited
> across **items 1–4 of 8**. It is not, and must not be described as, a
> completed whole-repo release audit. Items 5–7 carry forward as
> **mandatory-first scope for the `ref-2` release audit**.
>
> **Ingestion note.** Master Control independently confirmed the two blocking
> findings in source before triage (A-006: `clofin.api.organisations` has no
> `clofin.api.principal` require at all; A-012:
> `CreatePaymentInstructionRequest.required` contains `createdBy` while
> `caller-may-not-set` refuses it). Triage, severities and dispositions are in
> [`README.md`](README.md)'s register row and in the remediation section there —
> **severities are Master Control's, not the auditor's**, since the audit
> stopped before its verdict phase.

---

*Everything below is the external auditor's `rel-findings-partA.md`, verbatim
and unedited.*

---

# CloFin ref-1 release audit - findings part A

> Scope: charter items 3 and 4 only. Synthetic data only; internal quality gate; not an attestation.
> RC: `5d21334c1bfc59aba702e094155eea03dc9b1ef0` (detached). Meta: `70387b21f08811486a05c0314c37f350fc8bc72f` (read-only via `git show`).
> Previously recorded migration replay and test suites were not rerun.

## Check 3.1 - C-01 segregation of duties / DOMAIN_MODEL I8

**Result: enforcement confirmed; evidence wording inconsistent (A-001).** The discovered mutation path has one application status writer, `clofin.payments.repository/transition!`; every source call reaches that function. For the only creator-only lifecycle event, `:submit`, it checks the actor under the instruction row lock and a missing actor fails closed. The approval decision independently refuses `created-by` first, and the HTTP boundary delegates to that decision instead of pre-empting its reason ordering. The code/schema role vocabularies are equality-tested. This confirms C-01 and I8 at the stated application boundary.

The document's test-coverage claim is broader than the test. It says the matrix covers “every role set, every limit, every amount”, but the test enumerates six selected role sets, three selected ceilings and one amount in the test named as the full matrix. In particular, it is not the power set of the five roles and cannot enumerate every numeric limit or amount. This is a cross-document evidence-description defect, not a demonstrated bypass of the control.

Evidence:

- `docs/COMPLIANCE.md:104-109` says verbatim: “`clofin.authz.approval-test` is table-driven across the full actor × instruction matrix — every role set, every limit, every amount — and calls `evaluate` **directly**, with no HTTP anywhere in the file.”
- `src/clofin/payments/state.clj:160-181` says verbatim: “`Enforced by clofin.payments.repository/transition!, under the row lock, in the same transaction as the state change`” and defines `creator-only-events` as `#{:submit}`.
- `src/clofin/payments/repository.clj:386-440` says verbatim: “`For an event in clofin.payments.state/creator-only-events — today :submit — the actor must be the instruction's creator, checked **here**, under the row lock`”; the implementation is `_ (when (state/creator-only? event) (assert-creator! existing actor (name event)))` before `state/transition`, and the only discovered status SQL is `update payment_instruction set status = ?` in this function.
- `src/clofin/authz/approval.clj:221-286` says verbatim: “`1. :self-approval — the actor created this instruction, and therefore also submitted it`” and implements `(= (:id actor) (:created-by instruction)) (refused :self-approval ...)` as the first `cond` branch.
- `src/clofin/api/approvals.clj:112-134` says verbatim: “`Checking the permission out here would answer not-an-approver to an operator who had just tried to approve their own submission — true, and not the reason that governs.`” The handler calls `approvals/decide!` with the authenticated actor and does not make a competing approval decision.
- `test/clofin/payments/repository_test.clj:224-252` says verbatim: “`f-001-only-the-creator-may-submit`” and asserts both a `:forbidden` result for another actor and that the stored status remains `:draft`; the following test asserts a missing actor is `:unauthorised` and also remains `:draft`.
- `test/clofin/authz/approval_test.clj:234-252` labels its test “`the-full-actor-times-instruction-matrix`” but its population is verbatim: `(doseq [role-set [#{} #{:operator} #{:approver} #{:controller} #{:auditor} #{:operator :approver}] ceiling [nil 1000 1000000] self? [true false]] ...)` with the instruction amount fixed at `125000`.
- `test/clofin/authz/model_test.clj:88-105` says verbatim: “`no-single-role-is-both-maker-and-checker`” and enumerates every `model/role-permissions` entry; `test/clofin/authz/model_test.clj:137-145` says verbatim: “`clofin.authz.model/roles and the role_known check constraint must name the same roles`”.

## Check 3.2 - C-02 dual authorisation proportionate to value

**Result: confirmed; lead discarded as a consistency defect.** The control, ADR, pure decision, repository, DDL and tests agree. Bands are selected per organisation and currency with an inclusive lower bound; no covering band denies. A currency-specific approver limit wins over the one permitted wildcard row; no applicable row denies. The decision returns the required/held counts and alone decides completion. The database prevents a second live decision by one actor and refuses non-positive limits or approval counts. Amendment invalidates all live decisions before returning the instruction to draft. The wildcard does not contradict the per-currency decision: ADR-0015 explicitly records it as an accepted direct-minor-unit fallback and recommends currency-specific rows for mixed currencies.

Evidence:

- `docs/COMPLIANCE.md:117-162` says verbatim: “`from_minor is inclusive`”, “`Absent means zero, never unlimited.`”, and “`An unconfigured currency denies.`” Its enforcement table names `clofin.authz.approval/evaluate`, `approval_actor_live_key`, and the `approval_threshold` / `approver_limit` checks.
- `docs/ADR/0015-approval-thresholds-are-per-currency.md:44-67` says verbatim: “`Approver limits are per currency too, for the same reason, with a nullable currency meaning "every currency" — at most one such row per actor, enforced by unique nulls not distinct.`” It also states: “`An unconfigured currency denies rather than defaults.`”
- `src/clofin/authz/approval.clj:69-92` implements the greatest inclusive `from-minor` with `(<= from-minor minor-units)`; `src/clofin/authz/approval.clj:103-123` returns the currency-specific limit first and otherwise the `nil` wildcard; `src/clofin/authz/approval.clj:256-291` refuses `:above-actor-limit` and `:no-threshold-configured` and returns `:completes?` from the same decision.
- `src/clofin/authz/repository.clj:80-99` says verbatim: “`An empty result is a real answer ... evaluate refuses with :no-threshold-configured rather than inventing a requirement.`” Its query filters both `organisation_id` and `currency` and orders by `from_minor`.
- `resources/migrations/0005-authorisation-and-audit.sql:79-109` defines verbatim `constraint approver_limit_positive check (limit_minor > 0)` and `constraint threshold_approvals_positive check (approvals_required >= 1)`; `resources/migrations/0005-authorisation-and-audit.sql:137-140` defines `approval_actor_live_key ... where invalidated_at is null`.
- `resources/migrations/0006-approver-limit-wildcard-currency.sql:54-66` drops the inherited `NOT NULL` and adds verbatim `constraint approver_limit_key unique nulls not distinct (actor_id, currency)`.
- `test/clofin/authz/approval_test.clj:94-174` exercises limits at/below/above, no applicable limit, wildcard precedence, band boundary -1/at/+1, three bands, and no covering band. `test/clofin/authz/repository_test.clj:95-154` round-trips wildcard and specific limits through PostgreSQL.
- `test/clofin/api/approvals_api_test.clj:323-377` says verbatim: “`one approval on a two-approval band leaves the instruction pending`”, then checks that the second moves it to `approved`; it also checks all three boundary values and `no-threshold-configured` as `422`.
- `test/clofin/api/approvals_api_test.clj:417-460` says verbatim: “`ac-7-amending-an-approved-instruction-invalidates-every-approval-and-returns-it-to-draft`” and checks that the rows remain but all carry `invalidated_at`.

## Check 3.3 - C-03 immutable financial records / DOMAIN_MODEL I3-I4

**Result: confirmed at the documented application/non-owner boundary.** Discovery from the DDL identifies both ledger append-only tables, `journal_entry` and `journal_line`. Each has row-level guards for `UPDATE` and `DELETE` and a statement-level guard for `TRUNCATE`. The exhaustive raw-SQL matrix includes both tables and all three verbs, seeding a committed row before probing so a no-op against an empty table cannot pass vacuously. Corrections use a new reversal entry, and a partial unique index prevents a second reversal of the same original. COMPLIANCE separately and accurately discloses that the shipped owner/superuser can disarm triggers; no broader adversarial claim is inferred here.

Evidence:

- `docs/COMPLIANCE.md:166-183` says verbatim: “`journal_entry_append_only and journal_line_append_only triggers reject UPDATE, DELETE and TRUNCATE at the database`” and “`clofin.ledger.entry/reverse-entry refuses to reuse the original's id`”.
- `resources/migrations/0002-ledger-accounts-and-journal.sql:64-72` defines verbatim `create unique index journal_entry_reverses_key on journal_entry (reverses_id) where reverses_id is not null`; `resources/migrations/0002-ledger-accounts-and-journal.sql:157-166` defines `journal_entry_append_only` and `journal_line_append_only` as `before update or delete` triggers.
- `resources/migrations/0007-append-only-truncate-guards.sql:46-56` defines `journal_entry_no_truncate` and `journal_line_no_truncate` as `before truncate ... for each statement` triggers.
- `src/clofin/ledger/entry.clj:138-161` says verbatim: “`The reversal is a new entry referencing the original — the original is never modified, and both remain visible in the journal.`” It rejects `(= id (:id original))` before constructing the reversal.
- `test/clofin/db/audit_constraints_test.clj:249-286` defines the guarded population with verbatim rows `{:table "journal_entry" :refuses [:update :delete :truncate]}` and `{:table "journal_line" :refuses [:update :delete :truncate]}`; `test/clofin/db/audit_constraints_test.clj:340-359` loops every table and every declared verb through raw SQL and requires an `append-only` error naming that verb.
- `test/clofin/db/ledger_constraints_test.clj:91-126` issues direct SQL updates/deletes against committed ledger rows; `test/clofin/db/ledger_constraints_test.clj:229-250` loops both ledger tables through `truncate ... cascade` and asserts the rows survive.
- `test/clofin/db/ledger_constraints_test.clj:194-211` says verbatim: “`an-entry-may-be-reversed-only-once`” and requires the second insert to fail on `journal_entry_reverses_key`.
- `docs/COMPLIANCE.md:542-576` says verbatim: “`CloFin connects as that owner, and in the shipped stack that role is also a superuser`” and “`The fix is a runtime role split ... It is not built`”, bounding rather than overstating the trigger guarantee.

## Check 3.4 - C-04 ledger integrity / DOMAIN_MODEL I1, I2, I6 and I7

**Result: C-04/I1/I2 confirmed; I6 enforcement point incomplete (A-002); I7 not mechanically enforced as stated (A-003).** Domain and deferred database checks independently require positive lines, at least two lines, and debit/credit equality per currency. The named generated settlement property drives every generated settled/returned mix through the API and independently queries the journal.

I6 is enforced on the public posting path, but not by the enforcement points the invariant names and not “at all times”: `ledger_account.currency` and `journal_line.currency` each reference the currency registry, but no schema constraint relates them. Raw SQL can therefore commit a balanced entry whose lines are denominated differently from their accounts. The repository blocks this for application callers; the balance query then filters to the account currency, rather than making the mismatched stored row impossible. The invariant row should name the repository guard and qualify its boundary, or the schema must enforce the relationship.

I7's `NOT NULL` columns and closed reference-type vocabulary enforce only shape. Neither the constructor, service, repository nor handler resolves `reference.id` to a target or proves causation. The public API accepts a random UUID for any known reference type; its own successful opening-balance fixtures do exactly that. “Every entry references the business object that caused it” is therefore broader than the mechanical guarantee.

Evidence:

- `docs/COMPLIANCE.md:187-207` says verbatim: “`Total debits equal total credits, per currency, for every entry — and every entry has at least two lines to balance.`” It names `clofin.ledger.entry/entry`, `journal_entry_must_balance`, and `journal_entry_must_be_complete`.
- `docs/DOMAIN_MODEL.md:497-508` says verbatim: “`I6 | An account holds exactly one currency. | Schema and balance computation`” and “`I7 | Every entry references the business object that caused it. | NOT NULL plus a constrained vocabulary`”.
- `src/clofin/ledger/entry.clj:39-61` rejects non-positive amounts; `src/clofin/ledger/entry.clj:99-132` requires a known reference type and UUID, at least two lines, and an empty per-currency imbalance. It performs no reference-target lookup.
- `resources/migrations/0002-ledger-accounts-and-journal.sql:12-21` gives `ledger_account` one `currency ... not null references currency (code)`; `resources/migrations/0002-ledger-accounts-and-journal.sql:82-92` separately gives `journal_line` a `currency ... not null references currency (code)` and `amount_minor > 0`. There is no constraint joining line currency to account currency.
- `resources/migrations/0008-journal-entry-completeness.sql:55-99` counts lines, rejects fewer than two, groups by `l.currency`, and rejects unequal debit/credit sums; `resources/migrations/0008-journal-entry-completeness.sql:110-115` installs the check as `deferrable initially deferred` on `journal_entry`.
- `src/clofin/ledger/repository.clj:118-194` is the actual I6 application enforcement and says verbatim: “`the line's currency matches the account's, or the resulting balance is not computable at all`”; its loop compares each line amount currency with the loaded account. `src/clofin/ledger/repository.clj:310-323` instead computes balances with SQL filtered by both `l.account_id = ? and l.currency = ?`.
- `src/clofin/api/entries.clj:96-134` parses the reference as a known `entry/reference-types` member plus `wire/read-uuid-field`, then calls `ledger-service/post-entry!`; there is no target lookup. `src/clofin/ledger/service.clj:91-126` likewise delegates directly to `ledger/post-entry!` and records the audit event.
- `test/clofin/api/ledger_api_test.clj:100-109` builds a successful request with verbatim default `{"type" "opening-balance" "id" (str (random-uuid))}`; `test/clofin/api/ledger_api_test.clj:194-215` posts that request and expects `201`.
- `test/clofin/db/ledger_constraints_test.clj:28-88` bypasses the domain and confirms balanced commit, unbalanced rollback, deferred construction, and per-currency balancing; `test/clofin/db/ledger_constraints_test.clj:128-137` directly confirms `journal_line_amount_positive`.
- `test/clofin/api/settlement_api_test.clj:760-792` defines verbatim `ac-4-the-ledger-stays-balanced-across-every-outcome-mix` as a property over vectors of `:settled` / `:returned`, then requires no unbalanced journal entry and exactly one release plus one finality entry per instruction.

## Check 3.5 - DOMAIN_MODEL I5 money arithmetic

**Result: confirmed.** All operations that combine or compare amounts enforce one currency before arithmetic. Subtraction delegates to addition after currency-preserving negation; collection sums additionally require the caller's expected currency. The tests exercise cross-currency addition, subtraction and comparison directly, while property tests cover same-currency arithmetic. The 21-entry domain registry and migration seed agree, and the recorded green database suite includes an equality check over every code and scale.

Evidence:

- `docs/DOMAIN_MODEL.md:503-503` says verbatim: “`I5 | Money arithmetic never crosses currencies implicitly. | clofin.money raises`”.
- `src/clofin/money.clj:90-99` defines `same-currency!` and raises verbatim “`Cannot combine amounts in different currencies`” when more than one code is present.
- `src/clofin/money.clj:108-126` calls `same-currency!` from `compare` and `+`; `src/clofin/money.clj:141-148` implements subtraction through `apply +` after `negate`, preserving the same guard.
- `test/clofin/money_test.clj:72-86` says verbatim: “`cross-currency-arithmetic-is-an-error`” and requires exceptions for SGD/USD addition, SGD/JPY subtraction, and SGD/AUD comparison.
- `src/clofin/money.clj:27-51` declares 21 currency codes with scales; `resources/migrations/0001-organisation-and-currency.sql:38-60` inserts the same 21 code/scale pairs.
- `test/clofin/db/ledger_constraints_test.clj:213-221` says verbatim: “`the database and clofin.money agree on every currency and its scale`” and compares the complete maps for equality.

## Check 3.6 - C-05 / DOMAIN_MODEL I9 transaction atomicity

**Result: confirmed for the current audit-composing entry points.** The current source population is payment handler mutations plus nine entry points across four service namespaces: organisation creation; account creation and journal posting; approval decision and withdrawal; settlement batch creation/submission/response/timeout sweep. Payment mutations receive the transaction owned by `execute-once!`; the other handlers explicitly open a transaction. Every service entry point calls `assert-unit-of-work!` before its first aggregate write. The negative test matrix supplies both a pool and an autocommit connection to every service entry point and compares all writable-table counts before/after. Commit/rollback pairs include ordinary aborts and deferred database refusals.

Evidence:

- `docs/COMPLIANCE.md:285-309` says verbatim: “`audit_event is append-only and written in the same transaction as the change it describes`” and names the four service namespaces; `docs/COMPLIANCE.md:327-339` says verbatim: “`Every audit-composing service now calls clofin.audit.repository/assert-unit-of-work! before its first write`”.
- `docs/DOMAIN_MODEL.md:506-506` says verbatim: “`I9 | A state change and its audit event commit together. | Same transaction.`”
- `src/clofin/audit/repository.clj:57-99` says verbatim: “`Called at the entry of every audit-composing service, before its first write.`” and implements `assert-unit-of-work!` as `(db/assert-transaction! tx)`.
- `src/clofin/organisations/service.clj:47-72`, `src/clofin/ledger/service.clj:53-75`, `src/clofin/ledger/service.clj:106-126`, `src/clofin/payments/approval_service.clj:132-167`, `src/clofin/payments/approval_service.clj:220-243`, and all four public functions in `src/clofin/settlement/service.clj:117-536` call `audit-store/assert-unit-of-work!` before their first write.
- `src/clofin/api/payments.clj:245-257` passes each effect to `idem-store/execute-once!`; its create/amend/transition effects write state and `audit-store/record!` on the supplied `tx` at `src/clofin/api/payments.clj:300-319`, `src/clofin/api/payments.clj:407-444`, and `src/clofin/api/payments.clj:465-486`.
- `src/clofin/api/accounts.clj:43-62`, `src/clofin/api/entries.clj:112-135`, `src/clofin/api/organisations.clj:43-57`, and `src/clofin/api/settlement.clj:139-278` use verbatim `db/with-transaction [tx pool]` around their service calls.
- `test/clofin/audit/unit_of_work_test.clj:123-226` lists all nine current service entry points; `test/clofin/audit/unit_of_work_test.clj:228-237` compares their four namespace symbols with `purity/service-namespaces`; `test/clofin/audit/unit_of_work_test.clj:251-286` applies both negative arrivals to every entry and requires the complete writable-table count map to remain equal.
- `test/clofin/ledger/purity_test.clj:116-154` defines the same four service namespaces and rejects any `clofin.db.*` dependency.
- `test/clofin/ledger/service_test.clj:140-219` confirms committed/rolled-back pairs for posting, including failures raised only at deferred-constraint commit; `test/clofin/organisations/service_test.clj:52-105` does the corresponding organisation pair; `test/clofin/api/settlement_api_test.clj:674-710` confirms a rolled-back outcome leaves no posting, outcome, or event.

## Check 3.7 - C-05 complete audit-event coverage

**Result: universal coverage claim contradicted by a disclosed implementation path (A-004).** A late `timeout-resolution` can change an already-complete batch's stored status, for example from `failed` to `settled` or `partially-settled`. `complete-batch!` always writes the recomputed status but emits `settlement-batch.completed` only when the batch was not previously complete. The transaction records the payment transition, but no event whose subject is the batch records the batch status change. That naming choice is coherent with “completed” semantics, but it falsifies the unqualified C-05 statement and DOMAIN_MODEL assertion that every API state change emits an event. COMPLIANCE itself discloses the exception later, so the document is internally inconsistent. The control statement/status and DOMAIN_MODEL coverage paragraph need a qualification, or a distinct batch-status-change action must be introduced.

Evidence:

- `docs/COMPLIANCE.md:278-284` says verbatim: “`Every state change records who did what, to which subject, when, and what changed`” and marks C-05 enforced.
- `docs/DOMAIN_MODEL.md:335-339` says verbatim: “`Every state change the API can perform emits one event`” and “`There is no qualification left on C-05`”.
- `docs/COMPLIANCE.md:648-677` later says verbatim: “`A late timeout-resolution can then move that already-terminal status again`” and “`it writes no second batch-subject event`”; it concludes: “`What is missing is an event whose subject is the batch. This is recorded debt`”.
- `src/clofin/settlement/service.clj:283-307` says verbatim: “`a late resolution that changes an already-complete batch's status updates the status and emits nothing named completed`”. The implementation always calls `settlement/set-batch-status!`, then guards the event with `(and (not was-complete?) (batch/complete? items))`.
- `src/clofin/settlement/service.clj:408-508` captures `was-complete?` before resolving an item and calls `complete-batch!` after the payment transition, posting and payment-subject audit event, making the late status update reachable from `POST .../scheme-responses`.
- `test/clofin/api/settlement_api_test.clj:623-672` drives a timeout sweep followed by a late resolution and expects the batch/payment to become `settled`; it checks one payment event but contains no assertion for a second batch-subject event, matching the documented omission.

## Check 3.8 - C-06 duplicate prevention / DOMAIN_MODEL I10

**Result: I10 and named duplicate-payment guards confirmed; “every mutating operation” claim false (A-005).** For payment and approval endpoints that use caller idempotency, the composite primary key is claimed before the effect, the stored response is completed in the same transaction, and method/path/body are all digested. The real concurrency test expects one effect and one replay. Settlement independently prevents duplicate work through complete-message digests, replay-key uniqueness, exactly-once item updates and permanent instruction membership.

However, C-06 says every mutating operation requires `Idempotency-Key`. Organisation creation, account creation, journal posting, and settlement batch creation/submission/response/timeout sweep do not read or require that header. Their handlers open ordinary transactions; settlement uses lifecycle/membership/response guards, while the three ledger/bootstrap writes expose no caller-key replay contract. This does not demonstrate a duplicate payment through the guarded payment path, but it is a false universal enforcement claim. The statement must be scoped to the payment/approval operations that use `execute-once!`, or idempotency must be added to the remaining mutation set.

Evidence:

- `docs/COMPLIANCE.md:681-690` says verbatim: “`Every mutating operation requires an Idempotency-Key.`” It promises missing key `400`, exact replay, and different request `409` without qualification.
- `docs/DOMAIN_MODEL.md:506-507` says verbatim: “`I10 | A replayed idempotency key never performs work twice. | Primary key (organisation_id, key) plus the stored response, written in the same transaction as the effect`”.
- `src/clofin/idempotency/repository.clj:81-146` inserts the key before calling `effect tx`, updates the response in that transaction, and handles only `idempotency_key_pkey` as a concurrent replay.
- `src/clofin/api/payments.clj:203-225` digests verbatim `{"method", "path", "body"}`; `src/clofin/api/payments.clj:245-257` routes mutations through `execute-once!`. `src/clofin/api/approvals.clj:45-64` implements the same method/path/body scope and calls `execute-once!`.
- `resources/migrations/0003-payment-instructions.sql:70-85` defines the composite primary key; `resources/migrations/0004-idempotency-digest-scope.sql:20-30` corrects the database comment to method, path and body.
- `test/clofin/api/payments_api_test.clj:520-690` checks exact replay, representation-only replay, different-body conflict, missing keys on all four payment mutations, cross-instruction and cross-operation conflicts, organisation scoping, and a true two-thread race that requires exactly one instruction and one key. `test/clofin/idempotency_test.clj:18-148` checks the canonical form and replay decision.
- `src/clofin/api/organisations.clj:43-57`, `src/clofin/api/accounts.clj:43-62`, and `src/clofin/api/entries.clj:112-135` call `db/with-transaction` and their services without reading `Idempotency-Key` or calling `execute-once!`.
- `src/clofin/api/settlement.clj:139-278` exposes four mutation handlers using `db/with-transaction`; none reads `Idempotency-Key` or calls `execute-once!`.
- `src/clofin/settlement/response.clj:129-158` digests batch, instruction, kind, reference, outcome and reason; `resources/migrations/0009-settlement-batches-and-scheme-responses.sql:117-137` defines the null-safe replay key; `resources/migrations/0010-settlement-remediation.sql:47-60` makes membership unique for every outcome.
- `test/clofin/api/settlement_api_test.clj:405-615` checks exact duplicate no-work behavior, retained refused receipts, contradictory-message conflicts and replayed original outcomes. `test/clofin/settlement/repository_test.clj:73-119` attempts a second membership by raw SQL for pending, settled, timed-out and returned outcomes and requires all four to fail on `settlement_item_instruction_key`.

## Check 3.9 - C-07 sanctions screening before release

**Result: designed-not-built status confirmed.** A repository-wide discovery over `src/`, `resources/`, and `test/` found no screening model, schema, or test. The only implementation reference is the explicit increment-7 TODO at the submission transition. This agrees with C-07's `📋` status and its stated future enforcement point; no partial implementation is being presented as an enforced control.

Evidence:

- `docs/COMPLIANCE.md:765-779` says verbatim: “`### C-07 Sanctions screening before release 📋`”, “`Enforcement point. State machine precondition; case creation on a hit. (Increment 7.)`”.
- `src/clofin/payments/repository.clj:433-437` says verbatim: “`TODO(increment-7): screening gates submission here.`” and “`Until increment 7 there is no screening decision to consult, and inventing a partial gate would look like a control that does not exist.`”
- The one-pass search for `screening|sanction|Screening` under `src/`, `resources/`, and `test/` returned exactly those three comment lines in `src/clofin/payments/repository.clj` and no implementation or test match.

## Check 3.10 - C-08 least privilege at the API boundary

**Result: role model confirmed; route-wide enforcement claim false (A-006).** The closed permission model defaults to deny, suspended actors receive no permissions, granted permissions are checked for reachability, role/schema vocabularies are compared, and every inspected account, journal, payment, approval, settlement and audit handler delegates to `principal/for-request` or to the approval domain decision. The three health/info routes and unauthenticated organisation bootstrap are explicit public exceptions.

`GET /organisations/:id` is a business-data route outside those exceptions. Its handler reads `organisations/find-organisation` directly with a caller-supplied UUID and never authenticates or authorises. There is no `:organisation/read` permission in the closed permission vocabulary. Any caller who can guess or obtain an organisation UUID can retrieve its legal name, short name and status, contradicting C-08's statement and its “on every operation” enforcement claim.

Evidence:

- `docs/COMPLIANCE.md:781-821` says verbatim: “`A user can perform only what their role explicitly permits.`” and names `clofin.api.principal/authorise!` as enforcement “`At the API boundary, on every operation. There is no handler that authenticates without authorising`”.
- `src/clofin/routes.clj:21-39` declares the three health/info routes and `POST /organisations`; `src/clofin/routes.clj:41-43` separately declares verbatim `{:method :get :path "/organisations/:id" :operation-id "getOrganisation" :handler (organisations/show pool)}`.
- `src/clofin/api/organisations.clj:59-68` implements `show` as verbatim `(organisations/find-organisation pool id)` after parsing the path UUID. The namespace has no `clofin.api.principal` require, and the handler accepts no actor or permission.
- `src/clofin/api/principal.clj:93-111` says verbatim: “`The single call a handler makes. Combining the two steps is deliberate: a handler that authenticated and forgot to authorise would look correct in review and would be C-08's failure mode exactly.`”
- `src/clofin/authz/model.clj:25-50` declares all permissions; none is `:organisation/read`. `src/clofin/authz/model.clj:146-169` implements default-deny membership and rejects unknown permissions in `authorise!`.
- `src/clofin/api/accounts.clj:43-97`, `src/clofin/api/entries.clj:112-148`, `src/clofin/api/payments.clj:270-506`, `src/clofin/api/approvals.clj:121-217`, `src/clofin/api/settlement.clj:139-311`, and `src/clofin/api/audit.clj:34-91` call `principal/for-request` or `principal/authenticated-for` for every business handler in those namespaces.
- `src/clofin/api/health.clj:14-49` contains only liveness, readiness and static service information; `src/clofin/api/organisations.clj:13-57` explicitly describes `POST /organisations` as “`the bootstrap and ... deliberately unauthenticated`”. No corresponding exception is documented for `GET /organisations/:id`.
- `test/clofin/authz/model_test.clj:18-154` checks default deny, suspension, no superuser, maker/checker and approver/settler separation, role/schema equality, every role mapped, every grant known, and every permission reachable. Those model checks do not cover the handler that bypasses the model.

## Check 3.11 - C-09 data minimisation and logging

**Result: named request/config controls confirmed; “sensitive values never reach a log” contradicted (A-007).** `wrap-request-logging` emits only method, URI path, status, duration and a sanitised correlation id; it does not reference the query string, parsed body or raw body. Startup configuration is passed through `config/redacted`, and its test proves the password is absent. Field-level encryption is accurately disclosed as not built.

The control statement is broader than those enforcement points. `wrap-errors` intentionally calls `log/error` with the complete unexpected throwable. Its own production-response test throws a `RuntimeException` whose message contains `password=hunter2`; that secret is hidden from the response but passed to the logger as part of the throwable. A source-wide logging discovery also found a second full-throwable log below the middleware chain. C-11 explicitly says defects are “logged in full internally”, so C-09 and C-11 cannot both be true as written. C-09 must be narrowed to request/config logging, or exception logging needs a sanitisation policy.

Evidence:

- `docs/COMPLIANCE.md:824-841` says verbatim: “`sensitive values never reach a log`”, then names `clofin.http.middleware/wrap-request-logging` and `clofin.config/redacted` as enforcement points and says field-level encryption is “`designed but not built`”.
- `src/clofin/http/middleware.clj:44-61` says verbatim: “`Query strings and bodies are not logged`” and calls `log/infof` with only request method, `:uri`, response status, elapsed milliseconds and `:correlation-id`.
- `src/clofin/config.clj:59-63` says verbatim: “`Configuration safe to log. Credentials never reach a log line.`” and replaces `:password` with `"<redacted>"`.
- `src/clofin/system.clj:37` is the discovered startup logging call, verbatim: `(log/info "Starting CloFin" (pr-str (config/redacted config)))`.
- `test/clofin/config_test.clj:20-28` constructs `:password "s3cret"`, requires `"<redacted>"`, and asserts `s3cret` is absent from the printed redacted value.
- `src/clofin/http/middleware.clj:183-203` says verbatim: “`Any other throwable is a defect: it is logged in full internally`” and executes `(log/error t (format "Unhandled error on %s (correlation=%s)" ...))`.
- `test/clofin/http/middleware_test.clj:119-132` defines verbatim `(RuntimeException. "connection string: user=admin password=hunter2")`; the production assertion checks only that `hunter2` is absent from the response detail, while the full exception is still passed to the logger.
- The one-pass source logging discovery found 14 calls in five files, including `src/clofin/http/server.clj:96` verbatim `(log/error t "Failure below the middleware chain while handling a request")`, another full-throwable path.

## Check 3.12 - C-10 change control over the schema

**Result: migration-history controls confirmed; schema-level claim overstated (A-008).** The explicit index contains migrations `0001` through `0010`; the runner hashes each indexed SQL resource, stores the digest with the applied version, verifies indexed applied files before applying pending work, serialises concurrent runners, and applies each migration transactionally. Readiness reports the maximum recorded version, and the integration test mutates a recorded checksum and requires startup refusal.

Those mechanisms make the indexed migration history identifiable and detect an applied migration file edited after application. They do not make the live schema tamper-evident or comparable: the runner never introspects or hashes tables, columns, constraints, triggers, functions, indexes, comments or privileges. A direct `ALTER TABLE`, dropped trigger, or other owner DDL leaves every `schema_migration` checksum and the reported version unchanged. Thus the statement “The schema of any environment is identifiable, comparable and tamper-evident” is broader than its enforcement point. It should say migration history, or a catalog/schema-diff mechanism must be added.

Evidence:

- `docs/COMPLIANCE.md:846-859` says verbatim: “`The schema of any environment is identifiable, comparable and tamper-evident.`” Its design is “`Forward-only migrations with recorded SHA-256 checksums. An edited migration that has already been applied aborts start-up.`”
- `resources/migrations/index.txt:1-15` says verbatim: “`Append only. Never reorder, never edit a file that has already been applied — the runner verifies checksums and will refuse to start.`” and lists `0001-organisation-and-currency.sql` through `0010-settlement-remediation.sql`.
- `src/clofin/db/migrate.clj:22-27` says verbatim: “`Migrations are listed explicitly rather than discovered by scanning the classpath`”; `src/clofin/db/migrate.clj:48-73` loads only indexed resources and computes `:checksum (sha256 sql)`.
- `src/clofin/db/migrate.clj:75-84` creates `schema_migration(version, description, checksum, applied_at)`; `src/clofin/db/migrate.clj:93-108` compares each current indexed migration checksum only with its recorded row and raises when they differ.
- `src/clofin/db/migrate.clj:144-170` takes an advisory lock, calls `verify-checksums!`, and applies only versions absent from the registry. No catalog, constraint, trigger, function, privilege or schema-shape query occurs in this path.
- `src/clofin/db/migrate.clj:193-198` implements `current-version` as verbatim `select max(version) as version from schema_migration`; `src/clofin/api/health.clj:27-42` returns that value as `schemaVersion` when the database is reachable.
- `test/clofin/db/migrate_test.clj:39-55` says verbatim: “`a migration that has been applied is immutable; tampering aborts start-up`”, changes the registry checksum for `0001`, and requires a `:conflict`. It does not alter or compare live schema objects.
- `test/clofin/api/health_test.clj:25-43` stubs `migrate/current-version` and checks only that its string appears in readiness.

## Check 3.13 - C-11 error handling that does not leak

**Result: unexpected-defect boundary confirmed; absolute no-internals claim false (A-009).** In test/prod profiles, unexpected throwables become a generic `500` with a correlation id and no exception message; development alone exposes detail. Domain failures, however, are treated as caller-visible and `error->problem` publishes the complete `ex-data` map after removing only `:clofin/error` and `:clofin/message`. There is no public-field allowlist or sanitiser.

Multiple repositories attach PostgreSQL constraint names to domain errors that reach handlers. Examples include account-code uniqueness, duplicate journal-entry ids, foreign-key failures, duplicate live approvals and permanent settlement membership. Those implementation identifiers are rendered under `errors.constraint`, contradicting “An error response reveals nothing about the system's internals.” The statement should be narrowed to unexpected defects and the public schema identifiers accepted explicitly, or the renderer/repositories must translate them to a public error vocabulary.

Evidence:

- `docs/COMPLIANCE.md:862-877` says verbatim: “`An error response reveals nothing about the system's internals.`” It then says domain errors render “`with their own message`” while other throwables return “`a correlation id and nothing else`”.
- `src/clofin/http/middleware.clj:183-203` implements the split and, for defects, returns generic detail unless `config/expose-error-detail?` is true.
- `src/clofin/config.clj:51-56` says verbatim: “`Only in development — in every other profile a caller gets a correlation id and nothing more.`”
- `src/clofin/http/response.clj:35-53` claims “`Only the error's own message and explicitly-declared public data reach the caller`” but implements `:errors (dissoc data :clofin/error :clofin/message)`, which publishes every other field without an allowlist.
- `src/clofin/ledger/repository.clj:60-79` maps account uniqueness to a domain conflict carrying verbatim `{:code (:code acct) :constraint constraint}`; `src/clofin/ledger/repository.clj:199-219` carries `:constraint` for duplicate entry ids and foreign keys.
- `src/clofin/authz/repository.clj:145-171` maps any unique violation while recording an approval to a domain conflict carrying verbatim `:constraint constraint`.
- `src/clofin/settlement/repository.clj:251-285` translates permanent-membership uniqueness to a domain conflict carrying verbatim `{:constraint constraint ...}`.
- `test/clofin/http/middleware_test.clj:119-132` confirms a defect message containing `password=hunter2` is absent from the production response and present in development. No test asserts that domain error data excludes schema identifiers.

## Check 3.14 - C-12 supply chain control

**Result: top-level dependency mapping confirmed; all-components claim unsupported (A-010).** The seven top-level runtime dependencies in `deps.edn` exactly match ADR-0004's seven-row table and each has a role or brief rationale. C-12 also candidly labels enforcement as review rather than a mechanical control.

The statement covers every third-party component in the runtime path, and the ADR says a new dependency must record its transitive graph and claims a short, auditable SBOM. The repository evidence names only top-level coordinates; it contains no resolved transitive inventory/SBOM in the named enforcement point, and several rows provide no referenced upstream security process. Review can enforce a policy prospectively, but these files do not demonstrate that every transitive runtime component is known, justified, and paired with a named security process. The statement should be scoped to direct dependencies or backed by a generated, reviewed SBOM and upstream-security metadata.

Evidence:

- `docs/COMPLIANCE.md:880-892` says verbatim: “`Every third-party component in the runtime path is known, justified and has a named upstream with a security process.`” It names “`Review. (Not mechanical — stated honestly.)`” as the enforcement point.
- `deps.edn:1-22` says verbatim: “`Every runtime dependency below is resolvable from Maven Central`” and declares seven direct dependencies: Clojure, data.json, tools.logging, Jetty server, PostgreSQL JDBC, HikariCP and Logback Classic.
- `docs/ADR/0004-minimal-dependency-footprint.md:27-43` lists the same seven direct dependencies and says verbatim: “`Adding a runtime dependency requires a new ADR recording what was gained and what transitive graph came with it.`”
- `docs/ADR/0004-minimal-dependency-footprint.md:59-65` claims verbatim: “`A short, auditable SBOM in which every entry has a named upstream and a security process.`” No transitive coordinates, resolved graph or SBOM entries appear in the ADR's current runtime table.
- `docs/ADR/0004-minimal-dependency-footprint.md:76-79` defines verification only as verbatim: “`Any pull request adding a :deps entry without a corresponding ADR is rejected at review.`” That check concerns direct `:deps` additions, not the complete resolved runtime component set.

## Check 3.15 - OpenAPI contract-test coverage

**Result: route identity confirmed; handler contract guard is partial (A-011).** The recorded green contract test proves both-direction equality of HTTP method/path pairs and equality of `operationId`. It also separately checks nonblank route metadata, scope wording, the complete audit action set, two discovered `subjectType` enums, and `Money.minorUnits` as required `int64`.

It does not invoke a handler and does not inspect or validate operation parameters, request bodies, required members, response statuses, response headers, response schemas, media types, operation descriptions, authentication/authorisation requirements, or even equality of route and OpenAPI summaries. Consequently it cannot establish “OpenAPI vs the handlers”; stale handler semantics pass while the route name remains stable. The concrete drift in the following checks is therefore compatible with the green result rather than contradictory to it.

Evidence:

- `test/clofin/contract_test.clj:1-7` says verbatim: “`The API contract and the service must not drift apart.`” and “`every declared operation is routable, and every route is declared`”.
- `test/clofin/contract_test.clj:26-38` reduces every OpenAPI operation to only verbatim `{:operation-id (get operation "operationId") :summary (get operation "summary")}` keyed by method/path.
- `test/clofin/contract_test.clj:45-49` says verbatim: “`Handlers are never invoked here`” and builds routes with `:pool ::stub`.
- `test/clofin/contract_test.clj:51-72` compares only method/path sets and `operation-id`; `test/clofin/contract_test.clj:74-78` checks only that each **route-table** operation id and summary are nonblank, not that its summary equals the OpenAPI summary.
- `test/clofin/contract_test.clj:80-139` adds scope-keyword, audit-action, discovered audit-subject-type and Money-shape assertions. No generic operation parameter, body, response or handler-behaviour assertion appears.
- `api/openapi.yaml:14-18` says verbatim: “`a contract test asserts that every operation declared here exists in the service's route table and vice versa.`” That limited route-table statement is true; it does not support broader handler/schema consistency.

## Check 3.16 - OpenAPI request and actor-boundary consistency

**Result: contract and handlers materially disagree (A-012).** Complete route/handler comparison found 12 actor-protected operations with no `ActorId` parameter in OpenAPI: all four account operations, both journal operations, and create/list/get/amend/submit/cancel for payment instructions. There is no global OpenAPI security requirement to supply the missing boundary. Most of those operations also omit reachable `401` and `403` responses even though their handlers authenticate and authorise before work.

Request schemas also conflict with the principal design. `organisationId` is optional to handlers because the actor supplies the authoritative organisation, but several schemas require it. More severely, `CreatePaymentInstructionRequest` requires `createdBy`, while the handler expressly rejects any caller-supplied `createdBy` and derives it from the authenticated actor. No request can satisfy both requirements. These are executable client-contract defects, not prose-only staleness.

Evidence:

- `api/openapi.yaml:1148-1190` defines reusable `ActorId` as a required header but no top-level or operation-level security scheme. The parameter is present on approval, settlement and audit operations, demonstrating that omission elsewhere is not an alternate global mechanism.
- `api/openapi.yaml:286-453` declares create/list/get/statement account operations without `ActorId`; `src/clofin/api/accounts.clj:43-97` calls `principal/for-request` with `:account/create` or `:account/read` in every one.
- `api/openapi.yaml:455-584` declares post/get journal operations without `ActorId`; `src/clofin/api/entries.clj:112-148` calls `principal/for-request` with `:entry/post` or `:entry/read`.
- `api/openapi.yaml:586-1050` omits `ActorId` from all six payment create/list/get/amend/submit/cancel operations; `src/clofin/api/payments.clj:270-506` calls `principal/for-request` for each operation before its repository work.
- `src/clofin/api/principal.clj:93-111` says verbatim: “`Authenticate, check permission, and return the actor. The single call a handler makes.`” Missing or unknown actors raise `401`; missing permissions and a stated foreign organisation raise `403`.
- `api/openapi.yaml:1674-1690` defines `CreateAccountRequest.required` as verbatim `[organisationId, code, name, type, currency]`; `src/clofin/api/accounts.clj:43-62` takes `organisation-id` from `principal/for-request` and never reads `organisationId` as a required field.
- `api/openapi.yaml:1804-1820` similarly requires `organisationId` in `PostJournalEntryRequest`; `src/clofin/api/entries.clj:112-135` uses the principal's organisation and does not require the member.
- `api/openapi.yaml:2074-2112` defines `CreatePaymentInstructionRequest.required` with verbatim `createdBy`; its `createdBy` property says “`Caller-asserted until TASK-003`”. `src/clofin/api/payments.clj:77-85` instead declares `createdBy` in `caller-may-not-set`, and `src/clofin/api/payments.clj:270-303` rejects it then sets verbatim `:created-by (:id actor)`.
- `api/openapi.yaml:2114-2143` requires `organisationId` in `AmendPaymentInstructionRequest`; `src/clofin/api/payments.clj:381-407` treats it only as an optional scoping member and derives the organisation from the principal.

## Check 3.17 - OpenAPI behavioral semantics

**Result: multiple descriptions contradict current handlers (A-013).** The contract contains pre-TASK-003 and pre-settlement prose alongside current approval/settlement paths and schemas. It says settlement does not exist and no API operation drives release/finality; says PATCH is draft-only and creator authentication is not built; and calls a five-type evidence list “every record CloFin can write” while omitting settlement batches. The handler behavior and end-to-end tests implement the opposite in each case.

The scheme-response operation also describes the last creditor-account digit as determining the outcome, but the injection handler accepts a caller-selected `kind`/`outcome` and neither it nor `record-scheme-response!` checks `scheme/outcome-for`. The deterministic helper is test/demo guidance, not an enforced request contract. These descriptions need updating or the corresponding constraints must be implemented.

Evidence:

- `api/openapi.yaml:115-135` says verbatim: “`Approval exists; settlement and screening do not.`” and “`released, settled, failed and returned exist in the lifecycle and no operation here drives an instruction into them.`” The same document declares six settlement operations later.
- `src/clofin/settlement/service.clj:176-251` drives `:release` for every submitted member; `src/clofin/settlement/service.clj:472-508` drives `:settle` or `:return` for applied responses and writes finality.
- `api/openapi.yaml:690-724` says verbatim: “`Only a draft may be amended.`” and “`There is no authenticated principal to check that against yet ... TASK-003 adds it.`”
- `src/clofin/payments/repository.clj:323-384` chooses between in-place draft mutation and lifecycle `:amend` from `pending-approval` or `approved`, invalidates every live approval on the latter path, and calls `assert-creator!`; `src/clofin/api/payments.clj:367-444` supplies the authenticated actor and audits both amendment and invalidations.
- `api/openapi.yaml:1073-1092` describes an evidence subject as verbatim “`a payment instruction, an approval, an organisation, a ledger account or a journal entry — every record CloFin can write.`” It omits `settlement-batch`.
- `api/openapi.yaml:2540-2568` nevertheless includes `settlement-batch` in `EvidencePack.properties.subjectType`; `src/clofin/api/settlement.clj:139-175` creates batches, and `src/clofin/api/settlement.clj:291-311` retrieves them. `test/clofin/api/settlement_api_test.clj:727-757` expects an evidence pack whose `subjectType` is verbatim `settlement-batch`.
- `api/openapi.yaml:914-928` says verbatim: “`the outcome is derived from the last digit of the synthetic creditor account`”. `src/clofin/api/settlement.clj:207-239` reads caller-supplied `kind` and optional `outcome` and passes them to the service; `src/clofin/settlement/service.clj:354-508` validates response shape and applies those values without calling `clofin.settlement.scheme/outcome-for`.
- `api/openapi.yaml:106-111` describes idempotency as the same key and same “`body`”, while `api/openapi.yaml:1204-1215` later defines the same request as method, path and body. `src/clofin/api/payments.clj:203-225` implements the latter.

## Check 4.1 - discovered destructive-verb guard matrix

**Result: current live set fully covered.** A PostgreSQL-catalog query discovered every non-internal trigger whose function is `reject_mutation()` rather than starting from the test's table list. The live set is five tables and ten enabled triggers: `journal_entry`, `journal_line`, `audit_event`, and `scheme_response` each reject row `UPDATE`/`DELETE` and statement `TRUNCATE`; `approval` intentionally permits `UPDATE` and rejects `DELETE`/`TRUNCATE`. The discovered set and event sets exactly equal the raw-SQL test matrix. No current append-only table is omitted.

The explicit owner/superuser bypass remains outside this matrix and is already disclosed: the owner can disable/drop triggers or change replication behavior. `MERGE`/`INSERT ... ON CONFLICT DO UPDATE` mutations execute PostgreSQL `UPDATE`/`DELETE` row actions and therefore reach the same row triggers; `TRUNCATE` needs and has its distinct statement trigger.

Evidence:

- Live catalog output on the migrated PostgreSQL 16 database returned enabled (`tgenabled = O`) triggers verbatim: `journal_entry_append_only` (`BEFORE DELETE OR UPDATE`), `journal_entry_no_truncate` (`BEFORE TRUNCATE`), the same pair for `journal_line`, `audit_event`, and `scheme_response`, plus `approval_no_delete` and `approval_no_truncate`.
- `resources/migrations/0002-ledger-accounts-and-journal.sql:157-166` defines the journal row triggers; `resources/migrations/0005-authorisation-and-audit.sql:217-225` defines `audit_event_append_only` and `approval_no_delete`; `resources/migrations/0007-append-only-truncate-guards.sql:46-66` adds truncate triggers for the first four then-existing guarded tables; `resources/migrations/0009-settlement-batches-and-scheme-responses.sql:181-190` defines both scheme-response guards.
- `resources/migrations/0007-append-only-truncate-guards.sql:57-61` says verbatim: “`approval permits UPDATE ... and forbids DELETE. TRUNCATE belongs with DELETE`”, explaining the only asymmetric event set.
- `test/clofin/db/audit_constraints_test.clj:249-286` declares exactly the five discovered tables with verbatim refusal sets `[:update :delete :truncate]`, except `approval` with `:refuses [:delete :truncate] :permits [:update]`.
- `test/clofin/db/audit_constraints_test.clj:340-359` seeds a committed row per table, attempts every declared event in order, and requires the trigger error to name the attempted verb; `test/clofin/db/audit_constraints_test.clj:376-400` separately requires every discovered guard to be armed after fixture cleanup.
- `resources/migrations/0007-append-only-truncate-guards.sql:26-43` says verbatim: “`Triggers do not bind the table's owner`” and names `DISABLE TRIGGER`, `DROP TRIGGER`, and the missing runtime role split, so the matrix is not misread as an owner-adversary guarantee.

## Check 4.2 - discovered schema enum matrix

**Result: current values consistent; drift guards are one-directional or absent (A-014).** The live catalog, queried without a constraint-name allowlist, discovered 15 closed value sets. Their current values all equal the owning code and OpenAPI sets: actor status; role; approval decision; journal reference type and direction; account type and status; organisation status; payment status; settlement scheme, batch status and item outcome; scheme-response kind, outcome and disposition.

The claimed regression guards do not cover both sides of several sets. The role test's regex can only capture the five values already known to the test, so an extra SQL role is invisible. Settlement tests iterate code values and ask whether each string appears in migration `0009`; an extra SQL scheme/status/outcome also remains green. Payment status tests prove every code state can be stored and reject one sample unknown, not that the schema contains no extra state. Account, organisation, journal-reference, approval-decision and scheme-response sets have no complete code/schema equality test. This is the partial-set class: current equality was established by this catalog sweep, but the tests described as drift guards would not detect expansion on the schema side.

Evidence:

- Live PostgreSQL catalog output discovered verbatim constraints and values for `actor_status_known`, `role_known`, `approval_decision_known`, `journal_entry_reference_type_known`, `journal_line_direction_known`, `ledger_account_type_known`, `ledger_account_status_known`, `organisation_status_known`, `payment_status_known`, `settlement_scheme_known`, `settlement_batch_status_known`, `settlement_outcome_known`, `scheme_response_kind_known`, `scheme_response_outcome_known`, and `scheme_response_disposition_known`.
- `src/clofin/authz/model.clj:55-85`, `src/clofin/ledger/entry.clj:34-44`, `src/clofin/ledger/account.clj:21-46`, `src/clofin/organisations/organisation.clj:20-26`, `src/clofin/payments/state.clj:42-66`, `src/clofin/settlement/batch.clj:30-46,150-168`, and `src/clofin/settlement/response.clj:39-91` define the matching code populations.
- `api/openapi.yaml:1623-1687,1717-1753,1965-2015,2242-2420` publishes matching organisation/account/journal/payment/approval/actor/settlement/response enums.
- `test/clofin/authz/model_test.clj:137-145` builds `declared` with verbatim regex `#"'(operator|approver|controller|compliance|auditor)'"`. A sixth SQL literal cannot match that regex, so equality with the five code roles still passes.
- `test/clofin/settlement/batch_test.clj:26-45` loops each `batch/schemes`, `batch/statuses`, and `batch/item-outcomes` value and checks `str/includes? migration (str "'" value "'")`; it never extracts or compares the complete SQL sets.
- `test/clofin/payments/repository_test.clj:405-421` says verbatim: “`the check constraint and the state machine agree on the set of statuses`” but only inserts every `state/states` value. `test/clofin/payments/repository_test.clj:116-134` probes one extra value, `in-flight`, rather than discovering all SQL literals.
- `test/clofin/organisations/organisation_test.clj:43-51` enumerates the three code statuses but never reads the schema. `test/clofin/ledger/account_test.clj:18-49` samples unknown type/status values but has no schema comparison. `test/clofin/db/ledger_constraints_test.clj:139-192` similarly samples direction/reference-related constraints rather than comparing complete vocabularies.
- No `clofin.settlement.response-test` exists in the discovered test inventory; repository tests exercise selected valid/invalid response values but do not compare complete kind/outcome/disposition sets with SQL or OpenAPI.

## Check 4.3 - audit actions, subject types and producers

**Result: independent sets and current producers consistent; relational/schema guards incomplete (A-015).** The complete code/OpenAPI sets are 20 actions and six subject types. A source producer sweep found a correctly typed producer for every action except `payment.failed`, which is explicitly reserved with no driver in code, DOMAIN_MODEL and OpenAPI. The one bootstrap action is exactly `organisation.created`, and the test exhaustively requires an actor for every other action.

The naming guarantee is not enforced at event construction. `audit/event` checks that `action` belongs to one set and `subject-type` belongs to the other, but never checks the required relation between them. For example, `payment.approved` with subject type `account` passes. The test derives the right subject type and proves each right pair succeeds; it never tries a wrong pair of two individually known values. The database has no `CHECK` on either column, so direct SQL can also store arbitrary terms. Current call sites are correct, but the claimed closed, prefix-consistent vocabulary is only partially guarded.

Evidence:

- `src/clofin/audit.clj:42-109` defines 20 actions, including verbatim “`payment.failed`” with the comment “`Reserved ... no item outcome drives it`”; `src/clofin/audit.clj:111-126` defines six subject types.
- `api/openapi.yaml:2442-2512` publishes the same 20 `AuditAction` values and says verbatim: “`The action's prefix is its subjectType, with one exception: payment.* addresses ... payment-instruction.`” `api/openapi.yaml:2514-2570` publishes both six-value subject enums.
- `test/clofin/contract_test.clj:88-132` compares `audit/actions` with the complete OpenAPI action enum and discovers both `subjectType` enum copies before comparing each with `audit/subject-types`; these independent-set guards are bidirectional.
- `src/clofin/audit.clj:211-244` implements `event` with separate verbatim checks `(contains? actions action)` and `(contains? subject-types subject-type)` and no action-to-subject relation.
- `test/clofin/audit_test.clj:205-235` derives `subject-type-for` from each action and checks every correct pair is recordable. It contains no assertion that a wrong pair of two known values is refused.
- Current producers captured in `src/clofin/api/payments.clj:300-319,407-444,465-486`, `src/clofin/payments/approval_service.clj:170-211,220-267`, `src/clofin/organisations/service.clj:47-72`, `src/clofin/ledger/service.clj:53-75,106-126`, and `src/clofin/settlement/service.clj:117-536` cover 19 terms and use the subject type implied by each prefix.
- `docs/DOMAIN_MODEL.md:366-391` says verbatim: “`payment.failed is in the vocabulary and is emitted by nothing`”, so that lone producer absence is disclosed rather than accidental.
- The complete live `CHECK`-constraint discovery in Check 4.2 returned no constraint on `audit_event.action` or `audit_event.subject_type`; `resources/migrations/0005-authorisation-and-audit.sql:164-176` declares both simply as verbatim `text not null`.
- `test/clofin/audit_test.clj:250-270` iterates verbatim `(remove audit/bootstrap-actions audit/actions)` and requires every such action to reject a null actor; it separately requires `bootstrap-actions` to equal `#{"organisation.created"}`.

## Check 4.4 - roles and permissions

**Result: current 5-role/14-permission model set-complete; one route bypass already recorded as A-006.** The five code roles equal the five live schema roles and OpenAPI's actor model does not advertise an administrator/superuser role. Every role key has a permission set, every granted permission belongs to the 14-value closed set, every permission is reachable through at least one role, no role holds all permissions, and the maker/checker plus approver/settler exclusions hold across every role entry.

A consumer sweep found all 14 permissions used by a current boundary/domain path: account create/read; entry post/read; payment create/read/amend/cancel/submit/approve/reject; approval read; audit read; settlement execute. Unknown permission names fail in `authorise!`, and approval's dynamic approve/reject choice is checked in `evaluate`. The route-wide exception is `GET /organisations/:id`, which invokes neither permission model nor principal and has no organisation-read permission; that is A-006, not a missing value in this set.

Evidence:

- `src/clofin/authz/model.clj:25-50` defines the complete 14-value `permissions` set; `src/clofin/authz/model.clj:52-85` defines five `roles` and all five `role-permissions` entries.
- `src/clofin/authz/model.clj:146-169` implements permission membership as `contains?` with no wildcard/fallback and makes `authorise!` raise on any permission outside the closed set.
- `test/clofin/authz/model_test.clj:71-134` iterates every role to prohibit all-permission, create+approve, and approve+settle grants; `test/clofin/authz/model_test.clj:147-162` requires exact role/map key equality, every grant known, and the union of grants to equal the permission set.
- `src/clofin/api/accounts.clj:43-97` consumes `:account/create`/`:account/read`; `src/clofin/api/entries.clj:112-148` consumes `:entry/post`/`:entry/read`; `src/clofin/api/payments.clj:270-506` consumes the five direct payment mutation/read permissions.
- `src/clofin/authz/approval.clj:265-269` selects `:payment/reject` or `:payment/approve` for every decision; `src/clofin/api/approvals.clj:167-217` consumes `:payment/approve` for withdrawal and `:approval/read` for the queue.
- `src/clofin/api/audit.clj:34-91` consumes `:audit/read`; `src/clofin/api/settlement.clj:139-278` consumes `:settlement/execute` on all four mutations and `:payment/read` on both reads.
- `resources/migrations/0005-authorisation-and-audit.sql:52-66` constrains roles to verbatim `operator, approver, controller, compliance, auditor` and says “`There is no superuser role`”. The current-value equality and the regex guard's extra-value blind spot are recorded in A-014.
- `src/clofin/api/organisations.clj:59-68` is the sole discovered business handler that calls no principal/permission function; see Check 3.10 / A-006.

## Check 4.5 - refusal-reason populations

**Result: approval/batch values currently complete; settlement response reason set drifts (A-016).** Three named reason families were discovered. Approval has five decision refusals, all currently present in its status map, prose map, OpenAPI enum and exercised paths. Batch eligibility has four reasons, all present in its explanation map and produced by its four ordered branches. Scheme-response processing declares three refusal reasons, but the service emits a fourth machine code, `replay-key-conflict`, for a different message under a taken identity. That code reaches `errors.dispositionReason` and is asserted end to end, yet it is absent from `response/refusal-reasons`, the migration's documented reason vocabulary, and any OpenAPI reason enum.

The approval guard is also weaker than its docstring claims: neither `refusal-status` nor `refusal-detail` is key-compared with `approval/refusal-reasons`. `refuse!` has fallback status/detail branches, so adding an unmapped reason does not cause the promised build failure; it degrades to a generic `403`. The current five values happen to align, but the set guard is not complete.

Evidence:

- `src/clofin/authz/approval.clj:42-60` declares five reasons: verbatim `self-approval`, `not-an-approver`, `above-actor-limit`, `already-approved`, `no-threshold-configured`.
- `src/clofin/payments/approval_service.clj:51-80` has five keys in `refusal-status` and five in `refusal-detail`; `src/clofin/payments/approval_service.clj:82-101` falls back with verbatim `(or (refusal-status reason) :forbidden)` and `(or (refusal-detail reason) "This approval was refused")`.
- `src/clofin/payments/approval_service.clj:51-58` nevertheless says verbatim: “`a reason added to clofin.authz.approval/refusal-reasons without an answer here fails clofin.authz.approval-test`”. `test/clofin/authz/approval_test.clj:1-16` does not require `clofin.payments.approval-service`, and `test/clofin/authz/approval_test.clj:356-379` only checks five hand-built evaluator cases belong to the declaration; it never compares either mapping's keys.
- `api/openapi.yaml:2310-2333` publishes the same five `ApprovalRefusalReason` values.
- `src/clofin/settlement/batch.clj:61-80` declares four eligibility reasons and explanations; `src/clofin/settlement/batch.clj:82-99` has exactly four refusal branches. `test/clofin/settlement/batch_test.clj:79-89` explicitly requires all four keys and string explanations.
- `src/clofin/settlement/response.clj:79-109` declares only `item-already-resolved`, `item-not-timed-out`, and `item-not-in-batch` as `refusal-reasons`.
- `src/clofin/settlement/service.clj:386-406` emits a refused result with verbatim `:disposition-reason "replay-key-conflict"` when an existing replay key has a different digest.
- `test/clofin/api/settlement_api_test.clj:526-565` requires a contradictory message to return `409` with verbatim `errors.dispositionReason = "replay-key-conflict"` and `replayed = false`.
- `resources/migrations/0010-settlement-remediation.sql:94-99` documents `disposition_reason` as verbatim `item-already-resolved | item-not-timed-out | item-not-in-batch`; the live catalog shows only a nullness equivalence check, not a vocabulary constraint on that text column.
- `api/openapi.yaml:2290-2308` constrains stored `SchemeResponseRecord.dispositionReason` to the same three values, while the `409` operation prose mentions the different-message case but provides no complete machine-code enum for `Problem.errors.dispositionReason`.

## Check 4.6 - response kinds, outcomes and dispositions

**Result: current enum values align; returned-reason branch is not handled at the boundary (A-017).** Code, live schema and OpenAPI agree on four response kinds (`ack`, `settled`, `returned`, `timeout-resolution`), two claimable outcomes (`settled`, `returned`), and three stored dispositions (`applied`, `acknowledged`, `refused`). Walking every kind confirms `ack` forbids an instruction and resolves nothing; settled/returned require an instruction and choose their named outcome; timeout resolution requires an instruction and caller outcome. Every repository disposition is checked in code and SQL.

For both ways to produce `returned`, the stated mandatory reason is missing from `response/assert-shape!`. The service proceeds to update the item with a null reason; `settlement_return_needs_reason` then raises a raw database constraint failure. The API test accepts `500` as one of three possible statuses, so it does not require the handler to honour the documented `400`/`422` contract. This is a per-kind partial guard: the returned branches rely on a backstop that renders an internal error instead of a modeled refusal.

Evidence:

- `src/clofin/settlement/response.clj:39-70` declares four `kinds`, two `response-outcomes`, and maps direct `settled`/`returned` kinds to their outcomes; `src/clofin/settlement/response.clj:75-91` declares three dispositions.
- The live catalog in Check 4.2 reports exact equality for `scheme_response_kind_known`, `scheme_response_outcome_known`, and `scheme_response_disposition_known`; `api/openapi.yaml:2260-2308,2400-2425` publishes the same kind/outcome/disposition values.
- `src/clofin/settlement/response.clj:177-204` enforces the ack/instruction and non-ack/instruction matrix and requires an outcome for `timeout-resolution`, but it does not inspect `:reason` after normalisation.
- `src/clofin/settlement/service.clj:408-508` passes `resolved` and `reason` directly to `resolve-item!` or `resolve-timed-out-item!`; `src/clofin/settlement/repository.clj:300-350` writes both values without a domain reason check.
- `resources/migrations/0009-settlement-batches-and-scheme-responses.sql:79-83` defines verbatim `settlement_return_needs_reason check (outcome is distinct from 'returned' or length(btrim(coalesce(outcome_reason,''))) > 0)`.
- `api/openapi.yaml:930-940` says verbatim: “`A returned response must carry a reason.`” `api/openapi.yaml:2427-2458` repeats that `reason` is required for direct return and timeout resolution to return.
- `test/clofin/api/settlement_api_test.clj:324-337` sends a returned response without a reason and accepts verbatim `(contains? #{400 422 500} status)`, so a raw internal-error response passes. No dedicated `clofin.settlement.response-test` exists to enumerate the four shape branches.

## Check 4.7 - payment states, events and named subsets

**Result: complete current set coverage.** The lifecycle contains nine states, nine events and 11 permitted pairs. The test walks all 81 state×event combinations against the one transition table, verifies every destination is itself a state, derives terminality, and exhaustively checks the mutable/reversible subsets over all states. OpenAPI's `PaymentStatus` and `PaymentEvent` values equal the code sets; the live status constraint also currently equals the nine states, with its one-directional regression-test caveat recorded in A-014.

Call-site reconciliation found drivers for submit/cancel, approve/reject, amend, release, settle and return. `fail` alone has no driver and is explicitly disclosed in code, DOMAIN_MODEL and OpenAPI. The creator-only subset is exactly `submit`; every creator-only member is a known event, payment handlers pass the actor to submit/cancel, and the approval-service events are explicitly asserted not creator-only. No transition call site bypasses `payments/transition!`.

Evidence:

- `src/clofin/payments/state.clj:42-66` defines nine state keys and derives the nine-event set from their transition maps; the map contains 11 pairs.
- `test/clofin/payments/state_test.clj:24-49` loops verbatim `[state state/states event state/events]`, comparing every pair with `state/transitions`, and pins non-vacuity at nine states, nine events and 11 pairs.
- `test/clofin/payments/state_test.clj:51-85` compares `permitted?` with `transition`, checks every destination belongs to the table, derives the five terminal states, and requires every nonterminal state to have an exit.
- `test/clofin/payments/state_test.clj:148-187` checks `mutable-states` and `reversible-states` against every other state, not selected examples.
- `test/clofin/payments/state_test.clj:209-245` requires `creator-only-events = #{:submit}`, verifies cancel is excluded, approve/reject are excluded, and every member belongs to `state/events`.
- `api/openapi.yaml:1954-2025` publishes the same nine statuses and nine events. The current database equality is established in Check 4.2.
- Source call sites captured in `src/clofin/api/payments.clj:465-506`, `src/clofin/payments/approval_service.clj:132-167`, `src/clofin/payments/repository.clj:323-440`, and `src/clofin/settlement/service.clj:176-251,472-508` drive eight events through `payments/transition!`.
- `docs/DOMAIN_MODEL.md:468-480` says verbatim: “`fail is still driven by nothing`” and explains that timeout leaves the instruction released; `src/clofin/audit.clj:86-94` similarly marks `payment.failed` reserved.

## Check 4.8 - payment purpose codes

**Result: current 15-value code/OpenAPI sets agree; persistence and drift guard are partial (A-018).** The owning code map and OpenAPI `PurposeCode` enum currently contain the same 15 values. Domain construction and amendment reject anything else, and repository creation/amendment both route through that constructor, so normal application paths enforce the set.

`payment_instruction.purpose_code` is nevertheless unconstrained `text not null`. A direct SQL write can persist any string, making the documented constrained vocabulary false for the system of record. The unit test iterates every code value and probes selected invalid input, but no test extracts and compares the OpenAPI enum, and the contract test does not inspect `PurposeCode`. A code-only or contract-only addition can therefore drift green.

Evidence:

- `docs/DOMAIN_MODEL.md:100-113` describes `purpose-code` as verbatim “`Constrained vocabulary ... ✅`”.
- `src/clofin/payments/instruction.clj:34-61` defines exactly 15 keys: `CASH`, `CHAR`, `DIVI`, `GDDS`, `INSU`, `INTC`, `LOAN`, `PENS`, `RENT`, `SALA`, `SCVE`, `SUPP`, `TAXS`, `TRAD`, `TREA`.
- `src/clofin/payments/instruction.clj:127-132` returns “`unknown purpose code`” unless the value belongs to that map; `src/clofin/payments/instruction.clj:156-184` applies the rule in the common `field-errors` constructor path.
- `api/openapi.yaml:2027-2044` publishes the same 15 enum values in `PurposeCode`.
- `resources/migrations/0003-payment-instructions.sql:20-35` declares verbatim `purpose_code text not null` and has checks only for positive amount and payment status. The complete live catalog discovery likewise found no purpose-code constraint.
- `test/clofin/payments/instruction_test.clj:93-101` loops `(keys instruction/purpose-codes)` for acceptance and probes `SUPPLIER`/lowercase as invalid, but reads neither SQL nor OpenAPI.
- `test/clofin/contract_test.clj:1-139` has no `PurposeCode` assertion; its only non-audit schema-specific assertion is `Money` shape.

## Check 4.9 - supported currencies and scales

**Result: complete code/database coverage; OpenAPI set is overbroad (A-019).** Code and database contain the same 21 currency codes with the same scales, and a database integration test compares the complete maps for equality. Money construction rejects any code outside that registry, and monetary foreign keys stop unknown persisted currencies.

OpenAPI specifies only a three-uppercase-letter shape for `Money.currency` and the account/batch currency fields rather than the supported 21-value set. A contract-valid request using `XYZ` is rejected by `money/of` or the currency foreign key. The contract test verifies `minorUnits` type/format/required members but does not compare the currency population. Clients therefore cannot derive the actual accepted set from the interface specification.

Evidence:

- `src/clofin/money.clj:27-51` declares the 21 supported code/scale entries; `src/clofin/money.clj:53-63` makes any absent code an “`Unsupported currency`” domain error.
- `resources/migrations/0001-organisation-and-currency.sql:38-60` inserts the same 21 code/scale/name rows; monetary tables use foreign keys to `currency(code)`.
- `test/clofin/db/ledger_constraints_test.clj:213-221` queries every database currency and requires verbatim `(= from-domain from-db)` for code and scale.
- `api/openapi.yaml:1265-1287` defines `Money.properties.currency` only as `type: string` and pattern verbatim `^[A-Z]{3}$`; `api/openapi.yaml:1655-1660,1684-1687,2381-2385` uses the same broad string shape for account and settlement currencies.
- `test/clofin/contract_test.clj:134-139` verifies only that `minorUnits` is integer/int64 and `Money.required` equals `currency, minorUnits`; it never checks allowed currency values or scales.
- `test/clofin/money_test.clj:31-48` explicitly requires `money/of "XYZ" 1` to fail, demonstrating the contract-valid/handler-invalid value.

