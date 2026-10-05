# 017-REQ — Sanctions screening and cases: C-07 built, a client's result as evidence, core's decision as the gate

| Field | Value |
|---|---|
| **Brief** | `017-TASK-screening-and-cases.md` on `origin/meta` (identical to the copy on `main` at `24b11f9`, checked with `git show origin/meta:… \| diff - …`), dispatched 2026-10-05. Cited as a path: briefs live on the control plane |
| **Series number** | **017.** Task-keyed, reporting on `TASK-017` (**L-1**) |
| **Pull request** | `clofin-core` — [EchoJustus/clofin-core#45](https://github.com/EchoJustus/clofin-core/pull/45), opened as a draft. Not merged; the merge is Master Control's, after the objections in §6 are ruled and CI is green on the branch — which it cannot be until O-2 is ruled |
| **Branch** | `task/017-screening-and-cases`, as dispatched |
| **PR base** | `main` at `24b11f9` (the merge of PR #44, the sync after TASK-018 closed). No stack |
| **Model** | An Anthropic Claude model. **The identifier is deliberately not written into this file, any commit or the PR**: this session runs under an instruction that keeps model identifiers out of repository artifacts. Recorded here as a stated absence rather than omitted |
| **Reasoning effort** | Extended reasoning throughout; a scripted mutation harness for the negative controls (§4); an independent adversarial review over the diff, then a re-review of the fixes by a panel of read-only reviewers with a refutation pass and a completeness critic, each on a separate read-only worktree (§8). The harness exposes no numeric effort setting to the session, so this records the mode, not a measured value |
| **Date** | 2026-10-05 |
| **Migrations** | **One**: `0015-screening-lists-results-and-cases.sql`, the fifteenth entry in `index.txt` — the brief's DDL **verbatim** (compared statement for statement with the brief's SQL block after stripping comments: equal), plus a comment on every table and every non-obvious column |
| **Controls touched** | **C-07** 📋 → ✅. **C-05** — three actions, two subject types, a sixth audit-composing service. **C-08** — one role, three permissions. **C-01** — a disposition is a decision the maker may not take |
| **Status** | Implemented; the adversarial review's eleven findings and the re-review's notes **fixed** (§8). Objections in §6, none resolved by diverging silently — three of them (O-6, O-7, O-15) now record a departure from the brief's text or a gap, each with a ruling asked. **CI cannot be green on this branch until O-2 is ruled** (a control-plane line this brief may not edit) |
| **Verification** | On `92c17c3`, the last code commit: `make verify` is **red by O-2 alone** — 582 tests / 3,913 assertions, 2 failures (one test reading `docs/ROADMAP.md:52`), 0 errors; docs, diagrams and disclaimer checks OK. `make test-it`: 1,072 tests / 8,770 assertions, the same 2 failures, 0 errors. 44 mutation runs, each seen failing. UAT-008: 13 of 13. Details in §9 |

---

## 1. What this file is for

TASK-017 builds C-07 as designed (ADR-0028 D5, D8). **No instruction can now be
submitted without a completed screening decision that core itself made**,
against the one versioned synthetic list core has accepted; a hit opens a case
that a compliance actor — never the maker — dispositions with a retained
rationale. A client's screening result is recorded as **evidence**: core
recomputes it against the same list version, stores both outcomes and whether
they agree, refuses (and keeps) one it cannot reproduce, and transitions nothing
on any of it. **A `201` means evidence recorded and nothing else.** The approval
queue shows the checker core's screening outcome beside the amount. Every
decision is reproducible from what was retained: the list version, the
instruction digest, the matched entries, the actor and the time.

**The list is synthetic and the matching is exact.** Nothing in this change, in
code or in prose, claims anything about real-world screening quality; it builds
the control's shape. Nothing here changes what CloFin claims to be: a
synthetic-data reference implementation, connected to nothing, approved by no
one, processing no real funds.

This file maps every acceptance criterion to its test, quotes every negative
control failing, gives the refusal order for recording, lists the objections,
and records UAT-008's run.

---

## 2. What changed

| Commit | Items | What |
|---|---|---|
| `227a338` | A-1 … A-13 | Migration `0015`. Pure `clofin.screening.rules`, `.subject`, `.decision`, `.list`. `clofin.screening.repository` (results, cases, list reads — no list writes in `src/`). `clofin.screening.service` — `record-result!`, `submit-screened!`, `disposition!`, each asserting its unit of work first. The gate in `clofin.payments.repository/transition!` (`assert-may-apply!`, `assert-screened!`; the `TODO(increment-7)` replaced by a comment naming this brief and C-07); `clofin.payments.state/screened-events` and `screenable-states`. `clofin.api.screening` (seven handlers, one per new operation) and `clofin.api.payments/submit` through `submit-screened!`. `screeningDigest` on the instruction; `screening` on the queue row. Roles and permissions; audit vocabulary and the two projections; `protected-operations` six → eight. The loader `clofin.tools.screening-list` on `tools/`, the `:screening-list` alias, `make load-screening-list`, the shipped `resources/screening-lists/synthetic-2026-10-v1.edn`. `api/openapi.yaml` in the same commit as the handlers. `COMPLIANCE.md` C-07 ✅ (and C-05, C-08), `DOMAIN_MODEL.md`, `PRD.md` Q2, `ARCHITECTURE.md` §2–§3, `README.md`'s scope row, ADR-0028 Amendment 1, `make diagrams`. Every test in §3 |
| `d809f36` | A-5, A-14 | The loader refuses an unreadable or non-EDN list file by name (`unreadable-list-file`) rather than with a stack trace — found by UAT-008's first run (§7) |
| `1891126` | A-14 | `docs/uat/UAT-008-screening-and-cases.md` and its row; the list prerequisite in UAT-004, -005 and -006 (O-12) |
| `14b12fd` | — | this file, first filed with the adversarial review's findings open |
| `4173082` | §8 R-1 … R-11 | the review's eleven findings fixed: a client's hit never supersedes a disposition (O-7); `blockingCaseId` (O-6); the per-role claim narrowed (O-15); the capture harness loads the commit's list (O-12); stale copies; C-07's boundary; the list serialised against decisions; contract notes; a test that could not fail; UAT-008's wording; the loader's command line |
| `1333125` | R-4 | `capture!`'s call of the list step, tested |
| `0519f0d` | §8 (second pass) | the list lock as a transaction-scoped advisory lock (replacing `4173082`'s row locks), one hand-over instant, a direct-`transition!` race test, the loader's command line and failures, docstrings, the `409` text, C-07's enforcement rows (control map regenerated) |
| `8de56e7` | §8 (second pass) | the hand-over instant kept at the database's own precision |
| `3748d58` | §8 (second pass) | a client's result raced against a retirement; the capture tests check where the loader runs; `parse-args` refuses a flag as the version; the R-1 assertion, the C-01 every-role cell, the lock-order reason, and the documents' remaining copies |
| `92c17c3` | §8 (third pass) | the hand-over instant's precision tested deterministically; a version may not begin with `-`; the capture test checks the database; `Refused:` against `Failed:`; the documents' last copies |
| *(this commit)* | — | this file, updated: every finding fixed or answered, every control re-run on `92c17c3`, UAT-008 re-run |

---

## 3. Finding by criterion

The `ac-17-1` … `ac-17-7` identifiers are ADR-0028's *Verification* names,
verbatim. Every negative control in the last column is quoted failing in §4.

| AC | Test(s) | Asserts | Negative control (§4) |
|---|---|---|---|
| **AC-17-0** | `clofin.db.migrate-test/ac-17-0-the-index-reaches-0015`; `clofin.screening.repository-test/ac-17-0-every-documented-shape-inserts`; `…/ac-17-0-the-pre-flight-s-twelve-refusals-with-the-application-bypassed`; `clofin.db.vocabulary-test/a-014-every-schema-vocabulary-has-an-owner-in-code` (eight new owners) and `…/a-017-the-single-value-screening-vocabularies-have-owners-too` | `0015` is the fifteenth entry and applied; every documented shape inserts — the shipped list (4 entries, 5 rules), a `screening-service` grant, core's clear, a client's agreed hit with both match rows, a client's refused clear, a case opened then dispositioned with a rationale, one retirement; all twelve pre-flight refusals by raw SQL, application bypassed, each naming its constraint or trigger message; ten closed vocabularies owned (eight discovered, two one-value ones named — O-3 iii). **And on a database migrated to `0014` by `main`'s own tree, this branch's migrator applied `0015` and reported `Pending: 0`** | **M1** — one owner removed: the guard names `screening_case_disposition_known` |
| **AC-17-1** | `clofin.api.screening-api-test/ac-17-1-submit-answers-409-screening-hit-and-emits-no-payment-submitted-event` | `Blocked Counterparty Ltd` submitted → `409`, `application/problem+json`, `errors.reason` `screening-hit`, `errors.caseId`, `errors.listVersion`; still `draft`; the trail holds `screening-result.recorded` and `screening-case.opened` and no `payment.*` beyond the creation; core's stored result is `hit` on `SYN-0001`. **In-test negative control:** an unlisted name → `200`, `pending-approval`, exactly one core result `clear`, no case | **M2** — core screens against no entries: `409` becomes `200` |
| **AC-17-2** | `…/ac-17-2-a-hit-opens-a-case-in-the-same-transaction` | the result's and the case's events share the request's correlation id (and the `409`'s `instance`); a second submission while the case is open → `409` naming the same `caseId`, one case, two results; a retry under the first request's key replays the `409` byte for byte with `Idempotent-Replayed: true` and stores no further result. **In-test negative control:** a test double breaks the unit of work after the result is written and before the case opens → `500`, and **neither row survives** | **M3** — `screening_case_open_key` dropped: no case can open (`on conflict` has no arbiter) and the second-submission assertions fail |
| **AC-17-3** | `…/ac-17-3-an-unaccepted-list-version-is-422` | a retired version and an unknown version are each `422 list-version-not-accepted`; results count unchanged. Positive control: the accepted version records | **M4** — the version check removed: the retired version is recorded `201` |
| **AC-17-4** | `…/ac-17-4-an-instruction-digest-mismatch-is-422-and-changes-nothing`; `clofin.screening.subject-test/ac-17-4-the-golden-digest` and three more | one hex character off → `422 instruction-digest-mismatch`; no result, no case, no event. **In-test negative control:** the digest read from `screeningDigest` → `201`. The subject digest of one fixed instruction is pinned (`b5577271…3e94`), **recomputed independently in Python** from the canonical document when it was pinned; status, provenance and timestamps do not move it, and each of nine screened or identity fields does | **M5** — the comparison removed: the off digest is accepted. **M19** — the projection drops `purpose-code`: the golden digest moves and that field stops moving it |
| **AC-17-5** | `…/ac-17-5-a-result-core-cannot-reproduce-is-422-screening-result-mismatch-and-is-recorded-as-refused` | a `clear` for a hit instruction → `422`, `coreOutcome` `hit`, `coreMatchedEntries` `["SYN-0001"]`, `resultId`; the results list holds it `refused`, `agrees: false`, `dispositionReason` set, with its event and `auditEventId`; status `draft`; no case. A mismatch on the entry set alone (a hit naming `SYN-0001` for an instruction that also matches `SYN-0003`) is a mismatch. **In-test negative control, the L-11 shape shown once:** a test double renders the `422` *inside* the transaction — the refused row vanishes | **M6** — the handler throws inside the transaction: `errors.coreOutcome` absent and the stored row gone |
| **AC-17-6** | `…/ac-17-6-a-201-screening-result-leaves-status-draft-and-emits-no-transition-event` | an accepted `clear` and an accepted `hit`: both `201` with `Location`, `origin client`, `accepted`, `agrees`, `auditEventId`; **read back, both still `draft` with unchanged `permittedTransitions`**; no `payment.*` event; the hit's `201` names the case it opened (open, readable), with `screenedAt` as sent; the clear opens nothing; and the hit instruction's submission is still `409` — a client's acceptance does not satisfy the gate | **M7** — recording writes a `payment.submitted`: the no-transition-event assertion fails |
| **AC-17-7** | `clofin.screening.concurrency-test/ac-17-7-amend-and-screening-result-serialise-on-the-instruction-row` | two connections, forced, each wait **observed** in `pg_stat_activity` as a lock wait on the instruction's `for update`: amendment first → the result waits, reads the amended row, `422 instruction-digest-mismatch`, nothing stored; result first → it is accepted against the digest the row held under its lock, the amendment waits and then moves the digest; and a submission and a result serialise the same way (the result sees `pending-approval`, `409`). The forbidden state, made precise in O-10, is asserted absent | **M8** — `for update` removed from the recording path: the result does not wait, is accepted, and **commits after the amendment bound to the replaced content** — the forbidden state, observed (diff in §4) |
| **AC-17-8** | `clofin.screening.rules-test` — `ac-17-8-the-field-presence-equality-matrix` (3 × 2 × 2), `…-exact-means-exact`, `…-rules-and-within-an-entry-entries-or-across-a-list`, the property `ac-17-8-insertion-order-of-entries-and-rules-never-changes-the-answer` (200 trials); `clofin.tools.screening-list-test/a-list-with-an-originator-rule-is-refused-at-load`, `…/ac-17-8-every-other-refusal-in-a-5`, `…/ac-17-8-exactly-one-list-is-accepted-at-a-time`, `…/ac-17-8-a-replacement-is-one-transaction`, `…/two-accepted-lists-are-a-defect-…`, `…/a-path-that-is-not-a-list-is-refused-naming-the-path` | the matrix, exactness (case, spaces, punctuation), AND/OR and absent fields; order never changes the outcome or the matched set; an `originator-name` rule refused `unsupported-rule-field` naming the entry and the field, nothing written, the old list not retired; operator, blank value, duplicate id, empty entries, no rules, four bad versions (one the command line would read as a flag) each refused by reason; a second version refused without `--replacing`, a wrong `--replacing` refused, with it the old retired and the new accepted; a loaded version cannot be reloaded; retire leaves none; **a test double breaks the tool after the retirement and neither change survives** | **M9a** — the field check removed: the load reaches the database and is refused there instead of `unsupported-rule-field`. **M9b** — the retirement committed on its own: the old list is retired and nothing replaces it. **M9c** — evaluation keeps input order: the property shrinks to a counterexample. **M36** (§8) — a version beginning with `-` accepted again |
| **AC-17-9** | `clofin.api.screening-api-test/ac-17-9-a-disposition-is-never-the-makers-and-is-final`; `clofin.screening.repository-test/ac-17-9-the-raw-update-of-a-dispositioned-case-is-refused-by-the-trigger`; `…/a-disposition-is-bound-to-the-content-it-was-made-on` | a maker who **holds compliance** → `403 self-disposition`; compliance → `200`, `dispositionedBy`, one `screening-case.dispositioned`; a second → `409`; `false-positive` then submit → `pending-approval`; `confirmed-hit` then submit → `409` with `disposition: confirmed-hit` and the `caseId`, and `cancel` works; rationale `""`, blanks and 1001 characters → `422`, 1000 → `200`; the raw `UPDATE` of four columns of a dispositioned case and its `DELETE` refused by the triggers; an amendment makes a disposition moot (new case), and an amendment that clears the hit submits on a fresh decision | **M10a** — the self-disposition check removed: the maker clears her own hit, `200`. **M10b** — the trigger dropped: the raw updates succeed |
| **AC-17-10** | `clofin.api.approvals-api-test/ac-17-10-the-queue-carries-the-screening-outcome`; `…/ac-17-10-a-row-with-no-decision-over-its-current-content-carries-no-screening`; `clofin.api.conformance-test/ac-17-14-…` (every walked queue row) | every queued row carries `screening` with `outcome: clear`, `listVersion` `synthetic-2026-10-v1`, a `resultId` that is core's own result about that instruction, and `recordedAt`; a pending row no decision covers carries none | **M11** — the handler stops attaching it: the rows lack `screening` |
| **AC-17-11** | `clofin.payments.repository-test/ac-17-11-submit-without-a-screening-decision-is-refused-by-the-repository-itself`; `clofin.screening.decision-test/ac-17-11-the-decision-matrix` (15 cells) | `transition!` with `:submit`, called directly: no core result → `:conflict screening-required` and nothing moved; core clear for the current digest → permitted; clear for a **stale** digest (screened, then amended) → refused; core hit → `screening-hit`; **a client's accepted clear alone → refused**; the result's list retired → `screening-required` | **M12** — the gate commented out: the first case passes, the whole finding shown once. **M13a** — `decide`'s core-only filter removed: the matrix permits a client's clear. **M13b** — that filter *and* the gate query's `origin = 'core'` removed: the repository permits a submission on a client's clear (either filter alone holds it — two enforcement points, deliberately) |
| **AC-17-12** | `clofin.authz.model-test/ac-17-12-no-role-holds-screening-disposition-with-create-submit-or-approve`, `…/ac-17-12-the-screening-service-writes-nothing-but-evidence`, `…/ac-17-12-disposition-is-compliance-and-only-compliance`, `…/every-role-in-the-model-is-a-role-the-migration-text-mentions` (now reading the last migration that defines `role_known`); `clofin.db.vocabulary-test` (`role_known` ↔ six roles); `clofin.api.screening-api-test/ac-17-12-the-screening-service-cannot-submit-approve-create-or-disposition` | the separation assertion; the service role's only write is `screening/record`; `compliance` alone dispositions; the screening-service actor gets four `403`s — submit, approve (on a `pending-approval` instruction, since approval ranks the lifecycle first), create, disposition — and can read the instruction, read the list and record | **M14a** — `operator` granted `screening/disposition`. **M14b** — `screening-service` granted `payment/submit` |
| **AC-17-13** | `clofin.api.audit-coverage-test/ac-17-13-each-screening-write-leaves-exactly-one-event`, `…/ac-17-13-a-submission-with-no-list-leaves-no-event`; `clofin.contract-test/the-evidence-pack-description-names-every-subject-type` (eleven, derived from `clofin.audit/subject-types`); `clofin.audit.unit-of-work-test` (three new entry points, pool and autocommit each refused before any write) | an accepted client hit: two events (result, case); a refused result: one; refused recordings that store nothing: none; a screening-refused submission: one (core's result) and no `payment.*`; a disposition: one, and the case's evidence pack is `opened` then `dispositioned`; a permitted submission: core's result and `payment.submitted`; no list: none | **M15** — the case opens silently: one event where two were written. **M16** — the description says "nine" again |
| **AC-17-14** | `clofin.contract-test` (`ac-17-14-every-screening-enum-is-the-vocabulary-the-service-holds` — 14 enum sites discovered and compared, `…-publishes-every-screening-refusal-reason`, `…-the-instruction-publishes-its-screening-digest-read-only`, `…-no-operation-writes-a-screening-list`, and the A-012 actor-boundary test extended to `screening`); `clofin.api.conformance-test` (`ac-17-14-every-screening-operation-is-walked-to-its-success-and-a-refusal`, `ac-15-the-key-is-required-by-exactly-the-eight-operations-that-say-so`, `ac-15-a-mutation-with-no-key-is-refused-by-the-eight-and-by-no-others`, `every-bound-key-names-the-operation-that-bound-it`); `make diagrams` then `make diagrams-check`; `make doc-consistency` | every new operation walked to its `2xx` and to a modelled refusal — record `201`/`422`, results `200`, cases `200`, case `200`, disposition `200`/`409`, lists `200`, list `200`/`404`; submit to `200`, `409 screening-hit` and `422 no-screening-list-accepted`; all three dimensions checked on each; eight protected operations of nineteen mutations, both directions; diagrams regenerated (control map C-07 ✅, topology) and checked; **doc-consistency red by exactly one ROADMAP line (O-2)** | **M17** — `self-disposition` dropped from the published enum. **M18** — the walk stops driving `getScreeningCase`. **M20** — the brief's results path (O-1) |
| **AC-17-15** | UAT-008 (§7) | executed against a fresh stack before this file was filed; 13 of 13 steps pass | the first run, which found a script defect (§7) |

**Tests added for the review's findings (§8)**, each with its control in §4:

| Finding | Test(s) | Asserts | Negative control (§4) |
|---|---|---|---|
| R-1 | `clofin.api.screening-api-test/ac-17-9-a-client-hit-never-supersedes-a-disposition` | after `confirmed-hit` and after `false-positive`, a client's accepted hit on the same content and list is `201` naming the dispositioned case, opens no second case, and the disposition still decides the next submission (`409 confirmed-hit` / `200`). In-test negative control: with no case on the content, the hit opens one | **M21** |
| R-2 | `…/ac-17-2-a-case-open-on-earlier-content-is-named-as-blocking-never-as-this-contents` | a case left open across an amendment is `errors.blockingCaseId`, never `caseId`, with a true detail; once dispositioned, the next submission opens this content's case, bound to the current digest; the same after a list replacement | **M22** |
| R-4 | `clofin.tools.capture-stack-test/a-capture-loads-the-one-screening-list-the-commit-ships`; `clofin.tools.capture-test/a-capture-loads-the-commit-s-list-after-migrating-and-before-the-service-starts` | the commit's own loader on its one list, **run inside the captured worktree** with the capture database (a script standing in for `clojure` prints its working directory, `CLOFIN_DB_URL` and arguments); none shipped → nothing run; a failing loader or two lists → refused; this commit ships exactly one; `capture!` calls it after migrating and before starting, with the captured worktree and the capture database | **M27**, **M32**, **M33**, **M37** |
| R-7 | `clofin.screening.concurrency-test/a-retirement-in-flight-holds-the-list-and-a-submission-waits-then-decides-nothing`, `…/a-replacement-in-flight-holds-the-list-and-a-submission-screens-against-the-replacement`, `…/a-decision-in-flight-holds-the-list-and-the-retirement-is-stamped-after-it`, `…/a-retirement-in-flight-and-a-direct-transition-serialise-at-the-gate`, `…/a-retirement-in-flight-holds-the-list-and-a-client-result-waits-then-is-refused`; `clofin.tools.screening-list-test/a-replacement-hands-over-at-one-instant` | five forced interleavings, each wait observed in `pg_stat_activity` on the list lock: a submission waits for a retirement and is `422`, nothing stored; one waits for a replacement and screens against it; a retirement waits for a decision and is stamped after it (no result recorded at or after its list's `retired_at`); a direct `transition!` waits at the gate and is refused `screening-required`; a client's result waits for a retirement and is `422 list-version-not-accepted`, nothing stored. A replacement's `loaded_at` equals its predecessor's `retired_at`; and the instant keeps its microseconds (`…/the-hand-over-instant-keeps-its-microseconds`: five replacements, some `retired_at` off a whole millisecond) | **M23**, **M23b**, **M24**, **M25**, **M26**, **M31**, **M35** |
| R-9 | `clofin.screening.decision-test/ac-17-11-the-decision-matrix` | every answer `decide` gives over the matrix is declared, and every declared answer is reached — computed from `decide` | **M28** |
| R-11 | `clofin.tools.screening-list-args-test/a-malformed-command-line-is-a-usage-error-not-a-stack-trace` (a unit namespace) | fourteen malformed lines parse to nil — an odd count, a flag twice, a flag where the file or the version belongs — and the well-formed ones parse; `…/the-loader-says-refused-only-for-its-own-refusals` | **M29**, **M30**, **M34**, **M38** |

R-3, R-5, R-6, R-8 and R-10 change documents and the contract's prose only;
there is no behaviour for a test to hold, and §8 says where each changed.

---

## 4. Negative controls, seen failing

A script applied each mutation, ran the named test namespace against the
migrated test database, captured the failures, and reverted — `git checkout` for
code; for schema, the dropped object recreated with the migration's own DDL
(018-REQ O-5's method, now the template's) and checked identical afterwards
(`pg_indexes.indexdef` of `screening_case_open_key` compared character for
character). It refuses to run on a dirty tree, the guard 018-REQ added after its
own incident, and `git status` was clean after every run. Quoted from the runs
on the committed tree (**`92c17c3`**, after every review fix — every run below was repeated there; the first filing quoted `1891126`), abridged to the first failure that names the
criterion; *n pass / m fail* is that namespace's whole run. **44 mutation
runs** — M1–M20 for the criteria (M9, M10, M13 and M14 in parts) and M21–M38
for the review's findings and notes (§8, M23 in parts) — **every one seen
failing**, plus two runs expected to stay green (M13c, M13d) that show where a
control is enforced twice. Every run left the tree clean.

**M1 (AC-17-0)** — `"screening_case_disposition_known"` removed from `owners`. 136 / **1**:

```
FAIL in (a-014-every-schema-vocabulary-has-an-owner-in-code) (vocabulary_test.clj:178)
Check constraints in the live schema with no owning code vocabulary: ["screening_case_disposition_known"]
```

**M2 (AC-17-1)** — `(rules/evaluate existing [])` in `submit-screened!`. 142 / **69**, ten tests:

```
FAIL in (ac-17-1-submit-answers-409-screening-hit-and-emits-no-payment-submitted-event) (screening_api_test.clj:159)
expected: (= 409 status)
  actual: (not (= 409 200))
```

**M3 (AC-17-2)** — `drop index screening_case_open_key`. 131 / **80**; within AC-17-2:

```
FAIL in (ac-17-2-a-hit-opens-a-case-in-the-same-transaction) (screening_api_test.clj:197)
expected: (= #{"screening-case.opened" "screening-result.recorded"} (set (map :action rows)))
  actual: (not (= #{"screening-case.opened" "screening-result.recorded"} #{}))
…
a second submission while the case is open opens no second case and names the same one
expected: (= 409 status)
  actual: (not (= 409 500))
```

The index is the arbiter in the literal sense: `on conflict (instruction_id)
where status = 'open'` has nothing to infer without it, and no case can open.

**M4 (AC-17-3)** — the version check reduced to `(when-not listed …)`. 204 / **7**:

```
FAIL in (ac-17-3-an-unaccepted-list-version-is-422) (screening_api_test.clj:264)
a retired version
expected: (= 422 status)
  actual: (not (= 422 201))
```

**M5 (AC-17-4)** — the digest comparison replaced by `(when-not true …)`. 207 / **4**:

```
FAIL in (ac-17-4-an-instruction-digest-mismatch-is-422-and-changes-nothing) (screening_api_test.clj:280)
expected: (= 422 status)
  actual: (not (= 422 201))
```

**M6 (AC-17-5)** — the handler throws the mismatch inside the effect
(`(err/fail! :unprocessable detail {:reason reason})`) instead of returning it.
204 / **5** / 2 errors:

```
FAIL in (ac-17-5-a-result-core-cannot-reproduce-is-422-screening-result-mismatch-and-is-recorded-as-refused) (screening_api_test.clj:298)
expected: (= "hit" (get-in json ["errors" "coreOutcome"]))
  actual: (not (= "hit" nil))
```

and the stored refused row the next assertions read is gone — F-008's shape, the
reason the refusal is a value.

**M7 (AC-17-6)** — recording an accepted result also writes `payment.submitted`. 209 / **2**:

```
FAIL in (ac-17-6-a-201-screening-result-leaves-status-draft-and-emits-no-transition-event) (screening_api_test.clj:361)
clear
no payment.* event
  actual: (not (not-any? … ("screening-result.recorded" "payment.submitted")))
```

**M8 (AC-17-7)** — `for update` removed from the recording path:

```diff
-  (let [instruction (payments/lock-instruction! tx organisation-id instruction-id)
+  (let [instruction (or (payments/find-instruction tx organisation-id instruction-id)
+                        (err/not-found! "No such payment instruction in this organisation" {}))
```

41 / **9**. The result no longer waits (`await-lock-wait!` sees nothing), is
accepted, and is held by the harness until the amendment has committed — so it
commits second, without having seen the first:

```
FAIL in (ac-17-7-amend-and-screening-result-serialise-on-the-instruction-row) (concurrency_test.clj:155)
non-vacuity: the result is waiting on the instruction's row lock
  actual: (not (pos? 0))
…
expected: (= 422 (:status b*))
  actual: (not (= 422 201))
…
the forbidden state is absent: no result accepted after the amendment against the content it replaced
expected: (empty? (stored-results f))
  actual: (not (empty? [{:disposition "accepted", :instruction-digest "712fe76e…dbd7"}]))
```

**M9a (AC-17-8)** — the rule-field check in `list/validate` replaced by `(when-not true …)`. 49 / 0 / **1 error**:

```
ERROR in (a-list-with-an-originator-rule-is-refused-at-load) (QueryExecutorImpl.java:2993)
  actual: org.postgresql.util.PSQLException: ERROR: new row for relation "screening_rule" violates check constraint "screening_rule_field_known"
```

— the schema still refuses it, which is the point of having both: without the
validator the operator gets a driver exception after the transaction began,
not `unsupported-rule-field` naming the entry before anything was written.

**M9b (AC-17-8)** — the retirement committed on its own (`(.commit tx)` after `retire-version!`). 51 / **2**:

```
FAIL in (ac-17-8-a-replacement-is-one-transaction) (screening_list_test.clj:110)
expected: (= before (list-rows))
  actual: (not (= [{:version "synthetic-2026-10-v1", :retired-at nil}]
                  [{:version "synthetic-2026-10-v1", :retired-at #inst "2026-10-05T05:19:16.558…"}]))
```

(A first form of this mutation — the whole load run on the pool — broke the
fixture's own load before the test was reached, because `lock table` needs a
transaction; it proved nothing and is not counted.)

**M9c (AC-17-8)** — `evaluate` keeps input order and does not sort what matched. 42 / **1**:

```
FAIL in (ac-17-8-insertion-order-of-entries-and-rules-never-changes-the-answer) (rules_test.clj:117)
  actual: {:shrunk {… :smallest [{:creditor-account "SG-SYNTH-99999999"}
                                 [{:id "E-00" …} {:id "E-01" …} …] …]}}
```

**M10a (AC-17-9)** — the self-disposition check replaced by `(when false …)`. 206 / **5**:

```
FAIL in (ac-17-9-a-disposition-is-never-the-makers-and-is-final) (screening_api_test.clj:433)
the maker → 403 self-disposition, whatever roles they hold
expected: (= 403 status)
  actual: (not (= 403 200))
```

**M10b (AC-17-9)** — `drop trigger screening_case_disposition_final`. 21 / **5**:

```
FAIL in (ac-17-9-the-raw-update-of-a-dispositioned-case-is-refused-by-the-trigger) (repository_test.clj:187)
disposition
expected: (str/includes? (str (refused-by …)) "a disposition is final")
  actual: (not (str/includes? "" "a disposition is final"))
```

**M11 (AC-17-10)** — the queue handler stops attaching the decision. 322 / **6** / 2 errors:

```
FAIL in (ac-17-10-the-queue-carries-the-screening-outcome) (approvals_api_test.clj:605)
{"paymentInstruction" {…}, "priorApprovals" [], … "canApprove" true}      ; no "screening"
```

**M12 (AC-17-11)** — the gate in `transition!` commented out. 109 / **6** — the whole finding:

```
FAIL in (ac-17-11-submit-without-a-screening-decision-is-refused-by-the-repository-itself) (repository_test.clj:647)
a direct call of `transition!` with `:submit` — no service, no handler — on a draft core has not screened …
expected: (= [:conflict "screening-required"] (conflict-reason …))
  actual: (not (= [:conflict "screening-required"] nil))
```

**M13a (AC-17-11)** — `(= :core (kw (:origin result)))` removed from `decide`. 32 / **4**:

```
FAIL in (ac-17-11-the-decision-matrix) (decision_test.clj:48)
a client's clear, accepted
  actual: (not (= :refuse/no-decision :permit))
```

**M13b (AC-17-11)** — that, *and* `and r.origin = 'core'` removed from the gate's query. 114 / **1**:

```
FAIL in (ac-17-11-submit-without-a-screening-decision-is-refused-by-the-repository-itself) (repository_test.clj:690)
a client's accepted clear, alone, does not open the gate — the gate reads core's results only
  actual: (not (= [:conflict "screening-required"] nil))
```

**M13c / M13d (green by design, not counted above)** — each filter removed
alone, `clofin.payments.repository-test` run (the namespace whose
`ac-17-11-…` drives the gate with a client's clear): `decide`'s filter alone
(M13c) → *Ran 35 tests containing 115 assertions. 0 failures, 0 errors*; the
query's `origin = 'core'` alone (M13d) → the same. (M13a is M13c's mutation run
against `clofin.screening.decision-test`, which does see it.) Two filters, each sufficient on its own, which is "evidence is not a
transition" enforced twice; M13b is the run that removes both.

**M14a (AC-17-12)** — `operator` granted `:screening/disposition`. 163 / **3**:

```
FAIL in (ac-17-12-disposition-is-compliance-and-only-compliance) (model_test.clj:222)
  actual: (not (= #{:compliance} #{:operator :compliance}))
```

(and `ac-17-12-no-role-holds-screening-disposition-with-create-submit-or-approve` names `:operator` with each of the three).

**M14b (AC-17-12)** — `screening-service` granted `:payment/submit`. 376 / **1**:

```
FAIL in (ac-17-12-the-screening-service-writes-nothing-but-evidence) (model_test.clj:215)
screening-service holds writes beyond :screening/record: (:payment/submit :screening/record)
```

**M15 (AC-17-13)** — a case opens with no `screening-case.opened`. 124 / **3**:

```
FAIL in (ac-17-13-each-screening-write-leaves-exactly-one-event) (audit_coverage_test.clj:455)
a client's accepted hit: the result and the case it opened, one event each
expected: (= 2 (- (audit-count) before))
  actual: (not (= 2 1))
```

**M16 (AC-17-13)** — the evidence-pack description says `**nine**` again. 419 / **1**:

```
FAIL in (the-evidence-pack-description-names-every-subject-type) (contract_test.clj:250)
and it states how many there are, so another cannot be added without this sentence being read
```

**M17 (AC-17-14)** — `self-disposition` dropped from `ScreeningRefusalReason`. 418 / **2**:

```
FAIL in (ac-17-14-the-contract-publishes-every-screening-refusal-reason) (contract_test.clj:692)
the nine reasons the brief names (non-vacuity)
  actual: (not (= 9 8))
```

**M18 (AC-17-14)** — the walk stops driving `getScreeningCase`. 288 / **2**:

```
FAIL in (every-operation-in-the-route-table-is-exercised) (conformance_test.clj:539)
operations in the route table that this namespace never exercises: ["getScreeningCase"].
```

**M19 (A-3)** — the projection drops `:purpose-code`. 18 / **2**:

```
FAIL in (a-submission-does-not-move-the-digest-and-an-amendment-does) (subject_test.clj:61)
every screened field and identity moves it
:purpose-code
…
FAIL in (ac-17-4-the-golden-digest) (subject_test.clj:43)
  actual: (not (= "b557727148059e3eb9919d9673c6542c9f036b3eff270089e427431ac35b3e94"
                  "2aaedcda172aa9242a185d444ab8c6e065ef3adb4bd4da8fa362201460789a66"))
```

**M20 (O-1)** — `listScreeningResults` on the brief's path. 30 / **1**:

```
FAIL in (ac-18-no-two-served-routes-with-one-method-can-match-one-path) (router_test.clj:113)
these same-method routes can match one path, so table order decides between them:
[[:get "/payment-instructions/by-idempotency-key/:key" "/payment-instructions/:id/screening-results"]]
```

**The review's findings and notes (§8), each with its control.** Same
harness, same tree (`92c17c3`).

**M21 (R-1)** — `record-result!` ignores the case that already covers this
content (`covering nil`). 203 / **8** — the defect itself, reappearing:

```
FAIL in (ac-17-9-a-client-hit-never-supersedes-a-disposition) (screening_api_test.clj:512)
expected: (= [[case-id "dispositioned" disposition]] (mapv (juxt :id :status :disposition) …))
  actual: (not (= [["ec674183-…" "dispositioned" "confirmed-hit"]]
                  [["ec674183-…" "dispositioned" "confirmed-hit"] ["d3699f43-…" "open" nil]]))
…
expected: (= {"disposition" "confirmed-hit", "caseId" case-id} (select-keys (get json "errors") ["disposition" "caseId"]))
  actual: (not (= {"disposition" "confirmed-hit", "caseId" "ec674183-…"} {"caseId" "d3699f43-…"}))
```

— a second, open case beside the confirmed one, and the confirmed hit no
longer answers `confirmed-hit`.

**M22 (R-2)** — `submit` names whatever case is open as this content's
(`this-hits? true`). 205 / **6**:

```
FAIL in (ac-17-2-a-case-open-on-earlier-content-is-named-as-blocking-never-as-this-contents) (screening_api_test.clj:548)
the open case is named as what blocks, and not as this content's case
expected: (= first-case (get-in json ["errors" "blockingCaseId"]))
  actual: (not (= "0e0dd5ac-…" nil))
```

**M23 (R-7)** — the service's decisions take no list lock (`hold-list-lock!`
does nothing; the gate still takes it). 38 / **12**, across four of the five
list interleavings:

```
FAIL in (a-decision-in-flight-holds-the-list-and-the-retirement-is-stamped-after-it) (concurrency_test.clj:317)
expected: (pos? (await-lock-wait! list-lock))
  actual: (not (pos? 0))
…
FAIL in (a-replacement-in-flight-holds-the-list-and-a-submission-screens-against-the-replacement) (concurrency_test.clj:294)
expected: (= 200 (:status s*))
  actual: (not (= 200 409))
```

Without the service's lock, the submission screens against the list its
snapshot shows and is then refused `409 screening-required` by the gate, which
still takes the lock and re-reads — the gate catches it, fail-closed, with the
wrong answer; and a retirement no longer waits for a decision in flight.
**M23b (R-7)** — the gate's own lock removed, the service's left. 46 / **4**:

```
FAIL in (a-retirement-in-flight-and-a-direct-transition-serialise-at-the-gate) (concurrency_test.clj:349)
expected: (pos? (await-lock-wait! list-lock))
  actual: (not (pos? 0))
…
expected: (= "draft" (status-of f))
  actual: (not (= "draft" "pending-approval"))
```

— a direct `transition!` racing a retirement submits against the list being
retired. (Under the first fix's row locks this run stayed green; the test that
fails here was added after the re-review found nothing reached the gate's lock.)

**M24 (R-7)** — the loading tool takes no list lock. 31 / **19**, every list
interleaving:

```
FAIL in (a-replacement-in-flight-holds-the-list-and-a-submission-screens-against-the-replacement) (concurrency_test.clj:295)
expected: (= ["synthetic-2026-10-v2"] (mapv :list-version …))
  actual: (not (= ["synthetic-2026-10-v2"] ["synthetic-2026-10-v1"]))
```

— a submission screened against the list being replaced.

**M25 (R-7)** — the tool stamps `now()`, its transaction's start, rather than
the instant after its waits. 49 / **1**:

```
FAIL in (a-decision-in-flight-holds-the-list-and-the-retirement-is-stamped-after-it) (concurrency_test.clj:324)
the forbidden state is absent: a result recorded at or after its list's retirement
expected: (empty? (results-recorded-after-their-list-retired))
  actual: (not (empty? [{:id #uuid "f694a149-…", :recorded-at #inst "2026-10-05T20:52:18.525791…",
                         :retired-at #inst "2026-10-05T20:52:18.503611…"}]))
```

— a result recorded 22 ms after the list it was taken against says it was
retired.

**M26 (§8)** — a replacement's `loaded_at` is the column default `now()` again.
52 / **1**:

```
FAIL in (a-replacement-hands-over-at-one-instant) (screening_list_test.clj:126)
expected: (= (:retired-at v1) (:loaded-at v2))
  actual: (not (= #inst "2026-10-05T21:24:08.761774…" #inst "2026-10-05T21:24:08.761091…"))
```

— the new list "loaded" before the old one was retired. (An earlier run of
M25 and M26, on `0519f0d`, showed `retired_at` ending `.431000` beside a
`loaded_at` of `.430454`: the instant had been cut to the millisecond on its
way back through JDBC, visible because the mutated column escaped the cut —
found here, fixed in `8de56e7`, and guarded since `92c17c3` by M35's test.)

**M27 (R-4)** — `capture!` no longer loads the captured commit's list. 336 /
**2**:

```
FAIL in (a-capture-loads-the-commit-s-list-after-migrating-and-before-the-service-starts) (capture_test.clj:669)
expected: (= [:reset-schema :migrate :load-screening-list :start] (clojure.core/deref calls))
  actual: (not (= [:reset-schema :migrate :load-screening-list :start] [:reset-schema :migrate :start]))
```

**M28 (R-9)** — the matrix loses its only `confirmed-hit` cell; `decide` still
gives the answer, the matrix no longer reaches it. 33 / **1**:

```
FAIL in (ac-17-11-the-decision-matrix) (decision_test.clj:54)
every answer `decide` gives over the matrix is one `decisions`
declares, and every declared answer is reached by some cell — …
```

**M29 (R-11)** — `parse-args` applies `hash-map` to an odd count again. 16 /
0 / **4 errors**:

```
ERROR in (a-malformed-command-line-is-a-usage-error-not-a-stack-trace) (PersistentHashMap.java:79)
expected: (nil? (parse args))
  actual: java.lang.IllegalArgumentException: No value supplied for key: --replacing
```

**M30 (§8)** — `parse-args` accepts a flag given twice again. 19 / **1**; **M34
(§8)** — it accepts a flag as the `--replacing` version again. 18 / **2**:

```
FAIL in (a-malformed-command-line-is-a-usage-error-not-a-stack-trace) (screening_list_args_test.clj:31)
expected: (nil? (parse args))
  actual: (not (nil? {:command :load, :path "f.edn", :replacing "--replacing"}))
```

**M31 (§8)** — recording a client's result reads the list without the list
lock. 45 / **5**:

```
FAIL in (a-retirement-in-flight-holds-the-list-and-a-client-result-waits-then-is-refused) (concurrency_test.clj:381)
expected: (= 422 (:status b*))
  actual: (not (= 422 201))
…
expected: (empty? (stored-results f))
  actual: (not (empty? [{:disposition "accepted", :instruction-digest "80b60939…"}]))
```

— a client's result accepted against a list being retired, and recorded after
its `retired_at`.

**M32 (§8)** — the capture runs the list loader in the harness's own
directory. 43 / **1**; **M33 (§8)** — `capture!` hands the loader the
harness's root. 337 / **1**:

```
FAIL in (a-capture-loads-the-one-screening-list-the-commit-ships) (capture_stack_test.clj:458)
expected: (str/includes? (slurp log) (str "cwd=" (.getCanonicalPath (io/file wt)) " "))
  actual: (not (str/includes? "cwd=/home/user/clofin-core args=-M:screening-list load …" "cwd=/tmp/capture-lists… "))

FAIL in (a-capture-loads-the-commit-s-list-after-migrating-and-before-the-service-starts) (capture_test.clj:670)
  actual: (not (= {:worktree "/nonexistent-worktree", …} {:worktree "/home/user/clofin-core", …}))
```

— both pass the earlier tests, which only checked the command line; a capture
of `ref-1` started from `main` would have loaded `main`'s list.

---

## 5. The refusal order for recording a client's result

DoD asks for "the matrix of refusal order for recording (which check answers
first and why)". As built, for `POST /payment-instructions/{id}/screening-results`,
each row answers only if every row above it passed; the first six belong to
the transport and the idempotency layer and are shared with every protected
operation, the rest are D5's table in the brief's order:

| # | Answer | Check | Why here |
|---|---|---|---|
| 1 | `400` | the body is a JSON object | nothing below can be asked of a body that is not one (`wire/read-object`, as every handler) |
| 2 | `401` / `403` | an actor; `screening/record`; a stated `organisationId` that is the actor's | identity before anything about the instruction is disclosed (C-08) |
| 3 | `400` | `Idempotency-Key` present and well formed | the key must be known before a replay can be recognised |
| 4 | stored answer / `409` | the key's replay check: same request → the stored response (`Idempotent-Replayed: true`); a different request → `409` | **idempotency is resolved before validation**, as `createPaymentInstruction` does: a replay answers what the first call answered even if the request would not validate today |
| 5 | `400` | the body against `ScreeningResultRequest`: required members, closed objects, enums (`outcome`, rule `field`, `operator`), the digest's shape, no entry named twice | a request that cannot be understood stores nothing and is not a business answer |
| 6 | `404` | the instruction, locked `for update`, within the caller's organisation | D5 `:instruction-exists`. The lock is taken here, first, and everything below reads what it holds (L-8) |
| 7 | `409` | the instruction is `draft` | D5 `:expected-prior-state-matches`: evidence about a submitted instruction is about a decision already taken — and asked before the list, because no list makes such evidence admissible |
| 8 | `422 list-version-not-accepted` | the version is the accepted list | the entries in rows 9–10 can only be judged against a list core holds and has not retired |
| 9 | `422 outcome-entries-inconsistent` | `hit` ⇔ entries | the claim must be coherent before its parts are looked up — a body-only check, cheapest of the content checks |
| 10 | `422 matched-entries-unknown` | every entry is in that version, with the list's rules | the claim must quote the list before it can be compared with core's evaluation of it |
| 11 | `422 instruction-digest-mismatch` | `instructionDigest` = `subject/digest` of the **locked** row | D5 `:instruction-content-matches`. Last of the refusals: every check above concerns whether the claim is well formed against the list; this one concerns whether it is about *this* content, and its remedy — re-read `screeningDigest`, screen again — is only worth taking once the claim is otherwise admissible |
| 12 | `201` / `422 screening-result-mismatch` | core recomputes against that version: equal outcome and equal entry-id set → `201`, stored `accepted` (an accepted `hit` opens a case unless one is open); otherwise **stored** `refused`, `422`, rendered after commit | D5 `:screening-result-verified`. The one refusal that stores — the disagreement is evidence (L-11) |

Rows 1–11 store nothing, write no event and leave the key unconsumed. Row 12
stores exactly one result and one `screening-result.recorded`, plus a case and
`screening-case.opened` when an accepted hit opens one, and binds the key to
its answer (O-4). No row transitions anything.
`recording-refusals-in-the-published-order` asserts rows 6, 7, 9, 10 and 11's
reasons and that row 8 answers before row 9 (a request breaking both is
`list-version-not-accepted`); M4 shows that test failing when row 8 is removed.

---

## 6. Objections

Each is a defect or gap in the brief, a decision the brief left open, or an
observation a later brief inherits. None was resolved by diverging silently:
where the build differs from the brief's text, the difference is here, with the
evidence, and the ruling asked for.

**O-1 — `GET /payment-instructions/{id}/screening-results` cannot be served
under the route table's accepted invariant.** A-9 names `listScreeningResults`
at that path. The route table is held — by
`clofin.http.router-test/ac-18-no-two-served-routes-with-one-method-can-match-one-path`,
the table-wide guard Master Control **accepted** in ruling 018-REQ O-1 as "the
better invariant", with no precedence rule ordered — to having no two
same-method routes that can match one path. Every `GET
/payment-instructions/:id/<literal>` can match the same request as `GET
/payment-instructions/by-idempotency-key/:key`
(`/payment-instructions/by-idempotency-key/screening-results` is both), so the
brief's path fails the guard. Shown, not argued — **M20** below puts the
operation on the brief's path and the guard answers:

```
FAIL in (ac-18-no-two-served-routes-with-one-method-can-match-one-path) (router_test.clj:113)
these same-method routes can match one path, so table order decides between them: …
```

Built instead at **`GET /screening-results?instructionId={id}`** —
`listScreeningResults`, `screening/read`, a `404` for another organisation's
instruction, capped with `truncated` — and said so in the route table, the
contract's operation description and ADR-0028 Amendment 1. The recording
operation stays at `POST /payment-instructions/{id}/screening-results` (no
`POST` collides). *Ruling asked:* (a) accept the query path (my recommendation:
it keeps the accepted invariant and costs a client nothing); (b) restore the
brief's path and order a literal-over-parameter rule in the router; (c) restore
it with an enumerated exception in the guard. Any later `GET` sub-resource of an
instruction meets the same wall; the ruling is worth stating once for all of
them.

**O-2 — `make doc-consistency`, and with it `make test` and `make verify`, cannot
be green on this branch without a control-plane edit.** A-13 moves C-07 to ✅,
and AC-17-14 says doc-consistency stays green because C-07's status "appears in
COMPLIANCE and the control map — not in the ROADMAP". It does appear in the
ROADMAP, at `docs/ROADMAP.md:52` on `main` at `24b11f9` — *"C-07 (screening)
remains 📋."* — so rule 1 of `scripts/check-doc-consistency.sh` reports the
disagreement, and `clofin.tools.doc-consistency-test/ac-20-the-live-roadmap-passes-rule-5`
(a unit test that runs the script on the live tree) fails with it:

```
DISAGREE  docs/ROADMAP.md:52  says C-07 is 📋
          docs/COMPLIANCE.md:656  says C-07 is ✅
1 document disagreement(s).
```

The ROADMAP is the control plane's; I did not edit it, and I did not hold C-07
at 📋 to make the check pass — that would understate a built, enforced control,
which is L-15. **Proof that this line is the only disagreement:** on a scratch
copy of `docs/` and `scripts/` with *only* line 52 changed to "C-07 (screening)
is ✅ (TASK-017).", the script reports `Document consistency OK (13 control(s),
35 increment status claim(s), 0 prose task claim(s), 20 brief(s))`. *Ruling
asked:* Master Control corrects line 52 on `meta` and syncs it to `main`; I
then merge `main` into this branch (no rebase, no force-push) and the three
targets go green. Until then CI's `verify` job is red on this branch by exactly
these two assertions and nothing else (§9).

**O-3 — Claims about the tree the brief made that the tree does not bear out
(the 015-REQ O-1 discipline, applied to this brief).** None changed the work;
each is recorded because the next brief inherits its sentences.

- (i) "`grep -n "TODO(increment-7)" src/clofin/payments/repository.clj` → line
  545". At `24b11f9` it was line **647** (TASK-018 added a hundred lines above
  it). The check was true when it was taken and stale when the brief was
  dispatched.
- (ii) "drop and recreate by name, as `0013` does" for `role_known`. `0013`
  drops and recreates `recon_adjustment_status_known`; `role_known` had never
  been recreated (it is `0005`'s). The pattern is right and was followed; the
  example names the wrong constraint.
- (iii) "eight (the pre-flight counted them)" vocabulary owners. Eight is the
  count of constraints PostgreSQL renders as `= ANY (ARRAY[…])`, which is what
  `clofin.db.vocabulary-test` discovers. Migration `0015` has **ten** closed
  vocabularies: `screening_rule_operator_known` (`in ('exact')`) and
  `screening_result_refusal_reason_known` (`in ('screening-result-mismatch')`)
  are one-value lists PostgreSQL stores as `= 'x'::text`, invisible to the
  discovery. Eight owners were added as the brief says, **and** a test of their
  own for the two the discovery cannot see
  (`a-017-the-single-value-screening-vocabularies-have-owners-too`) — the
  dimension L-17 asks a guard to enumerate.
- (iv) "`docs/DOMAIN_MODEL.md` §1's `screening-outcome` row". The row is in
  §2.2's `PaymentInstruction` field table, not §1 — the same mislabel 018-REQ
  O-4(ii) recorded for §1/§2.2. Updated where it is.
- (v) "`AuditSubjectType` (two copies)". No schema of that name exists; the two
  copies are the `subjectType` enums on `AuditEvent` and `EvidencePack`. The
  contract's own evidence-pack prose cited the same nonexistent name; corrected
  there.
- (vi) The shipped list's `SYN-0002` account, `SG-SYNTH-99999999`, is used by
  `test/clofin/audit_test.clj:59`, against the brief's own note that the
  shipped list must not use "the `SG-SYNTH-…` accounts the UAT scripts and tests
  use" — the brief's check grepped the two names, not the account. The use is a
  pure digest test that never screens, so nothing collides; shipped as the brief
  specifies. *Ruling asked* only if the list should change.

**O-4 — Two refusals bind the `Idempotency-Key` (a decision the brief left
open).** "Render after commit" for `422 screening-result-mismatch` and `409
screening-hit` means the effect returns the refusal as a value, so
`execute-once!` commits the key with the evidence and stores the refusal as the
key's response. That departs from the contract's standing sentence "a request
that failed does not consume its key" — deliberately: an unconsumed key would
let a retry of the same mismatched result store a second refused row, and a
retried hit store a second core result, i.e. record the same evidence twice,
which is what the key exists to prevent; a replay now reproduces the original
refusal (`Idempotent-Replayed: true`, byte for byte — asserted in
`ac-17-2-…`), which is L-11's "replay reproduces the original disposition". A
new attempt after a disposition takes a new key. Every other refusal stores
nothing and leaves the key unconsumed (`a-submission-with-no-list-accepted-is-422-and-consumes-nothing`).
Published in the `IdempotencyKey` parameter, both operations' descriptions and
Amendment 1. *Ruling asked:* confirm.

**O-5 — "Exactly one list is accepted at a time" is enforced by the tool, not
the schema.** The brief's DDL (which I applied verbatim) has no constraint
making two un-retired `screening_list` rows impossible. The loader takes `lock
table screening_list in share row exclusive mode`, so two concurrent loads
cannot both find no accepted list, and `--replacing` retires and loads in one
transaction; but a raw `INSERT` can still make two accepted rows. The reader
fails closed — `clofin.screening.repository/accepted-list` throws rather than
choosing, so every submission is a `500` until an operator retires one
(`two-accepted-lists-are-a-defect-screening-refuses-to-choose-between`). A
later migration could add `create unique index screening_list_one_accepted on
screening_list ((true)) where retired_at is null`. Recorded as a gap, not built.

**O-6 — One open case per instruction, and what that means after an
amendment.** `screening_case_open_key` is on `instruction_id` alone. A case
left **open** while its instruction is amended (or its list replaced) is bound
to content the instruction no longer has, yet still occupies the instruction's
one open slot: the next submission's hit cannot open a case for the new
content. **As first built, the `409` then named that stale case as `caseId`,
with a detail saying its disposition would let "this content" be submitted —
false (review finding R-2).** Fixed in `4173082`: the `409` names it
**`errors.blockingCaseId`**, never `caseId`, and says that dispositioning it
decides nothing about the current content and only lets that content's case
open (`ac-17-2-a-case-open-on-earlier-content-is-named-as-blocking-never-as-this-contents`,
digest and list-replacement variants; **M22**). Still fail-closed, and a
compliance actor still dispositions twice. Also: `screening_case_disposition_final`
freezes a case only once dispositioned; an **open** case's binding columns can
be changed by a raw `UPDATE` (the application never does). *Ruling asked:*
whether a later migration should key the index on `(instruction_id,
instruction_digest, list_version)` — one open case per content rather than per
instruction, which would remove the blocking case altogether — or freeze an
open case's binding columns.

**O-7 — A client's accepted hit never re-opens a decided case: built narrower
than A-6, after the review.** A-6 says an accepted `hit` "opens a case if none
is open". Built literally, a client's hit on content and a list a **dispositioned**
case already covered opened a second case, and because `decide` reads the
**latest** case for the triple (A-8), that case superseded the disposition: a
final `confirmed-hit` could be followed by a client's hit, a new case, a
`false-positive` disposition and a permitted submission — **with no
amendment** — and a `false-positive` permit could flip back to a refusal
(review finding R-1). That contradicts the control's own sentences ("a
disposition is final"; "`decide` never reads a client's result"), so it is
fixed in `4173082` rather than left: an accepted client hit opens a case only
when **no case at all** covers (instruction, digest, list); otherwise the
`201` names the covering case and opens none
(`ac-17-9-a-client-hit-never-supersedes-a-disposition`, both dispositions,
with a negative control; **M21**). With that, at most one case ever covers an
instruction's content against a list, so "the latest case" A-8 reads is "the"
case. **This departs from A-6's literal text**, and is stated here, in the
contract's `caseId` description, in ADR-0028 Amendment 1 and in C-07, not
silently. The rule is enforced by a read under the instruction's lock, not by
the schema: migration `0015` (the brief's DDL) has no unique key on
(instruction, digest, list), so a writer that bypassed the service could still
insert a second case for the triple. *Rulings asked:* confirm the narrower rule
(my recommendation), or restore A-6's literal text and accept that a client's
evidence can re-open a compliance decision; and whether a later migration
should add `create unique index … on screening_case (instruction_id,
instruction_digest, list_version)` so the schema holds it too.

**O-8 — The queue's `screening` member cannot show *why* a hit was let through.**
A-11's four members are built as specified. An instruction a compliance actor
cleared `false-positive` shows `"outcome":"hit"` in the queue (UAT-008 step 8)
with nothing beside it naming the case or its disposition. An approver is not
deceived — the outcome is true — but is sent elsewhere for the reason.
*Suggestion*, not built: an optional `caseId` and `disposition` on the member.

**O-9 — The `screening-service` role is wider than D8 says.** D8: "holding
`screening/record` and `payment/read`". A-10:
`#{:screening/record :screening/read :payment/read :organisation/read}`. Built
as A-10 (the client must read the accepted list it screens against; every role
reads its own organisation) and recorded in Amendment 1. It still writes nothing
but evidence (`ac-17-12-the-screening-service-writes-nothing-but-evidence`).

**O-10 — AC-17-7's "forbidden state", made precise.** "An accepted result whose
digest the instruction never held" cannot occur under `READ COMMITTED` with or
without the lock: the digest is always computed from a committed row. What the
lock prevents is a result that **commits after the amendment** while bound to
the content the amendment replaced — a decision taken over content the row no
longer held when it was taken. That is the state the test forces and asserts
absent, and the one **M8** shows appearing when `for update` is removed. Stated
so the criterion's sentence is not read as the invariant.

**O-11 — Decisions taken in this build that the brief did not state.**
- `screening_result.recorded_at` is written as `clock_timestamp()`, not the
  column default `now()` (the transaction's start): results are written under
  the instruction's lock, so the insert instant orders them as they were
  decided, and "the latest core result" the gate reads means the latest
  decided. A transaction that began earlier and waited longer for the lock
  would otherwise sort before a decision it followed.
- `submit-screened!` asks provenance and the lifecycle (`assert-may-apply!`, the
  same function `transition!` now calls) **before** it screens: a submission
  that was never going to be honoured — a non-creator's, or a second submit of a
  `pending-approval` instruction — leaves no result and opens no case.
- The disposition locks the instruction, then the case (the lock order
  `clofin.screening.repository` states), so a disposition and a submission of
  one instruction serialise rather than race into a second case.
- **The list lock.** Every screening decision — core's at `submit`, a client's
  result being recorded, and the repository's own gate — holds a
  transaction-scoped advisory lock, `clofin.screening.list/lock-key`, **shared**,
  taken after the instruction's lock; the loading tool holds it **exclusive**
  for a whole load, replacement or retirement, after its table lock. A list
  change therefore waits for every decision in flight; a decision that starts
  meanwhile waits for the change to commit and then reads the list in a
  statement whose snapshot follows it; and a waiting change is not overtaken by
  later decisions. The tool stamps a retirement's `retired_at` and a
  replacement's `loaded_at` with **one** `clock_timestamp()` instant read after
  its waits. So no decision commits against a list after its retirement did, no
  result is recorded after its list's `retired_at`, and the rows show neither
  two lists accepted at once nor a moment with none. Review finding R-7 found
  the window this closes; the first fix (`4173082`, row locks `for share` /
  `for update`) was replaced in `0519f0d` after the re-review showed it could
  refuse a submission spuriously behind two back-to-back replacements, could
  let a steady stream of decisions starve a retirement, and needed `UPDATE`
  privilege on a table the service only reads. The advisory lock needs no
  privilege on `screening_list`. `8de56e7` keeps the instant at the database's
  own microsecond precision: carried back through JDBC it had been bound at
  milliseconds, which could stamp a `retired_at` before a result recorded in
  the same millisecond (this file's own M25/M26 runs showed it). Lock order:
  instruction → list lock → case; the tool: table → list lock → rows. On
  `screening_list` itself the two take locks in reverse order (the tool's
  table lock first; a decision's read and foreign-key locks after the list
  lock), which is deadlock-free only because `share row exclusive` conflicts
  with neither `access share` nor `row share` — written into
  `clofin.screening.repository`'s lock order.
- **Event and case times are the transaction's start.** A case's `opened_at`
  and every audit event's `occurred_at` are `now()`, shared by every row a
  transaction writes with its event; a decision that waited for a list change
  began before it, so its event can carry a time before the list it was taken
  against was loaded. Not changed — the audit trail's time is the
  transaction's, everywhere, and changing it is beyond this brief. C-07 says
  that the order of decisions against lists is read from
  `screening_result.recorded_at` (`clock_timestamp()`, after the locks), not
  from event or case times.
- The list loader lives on `tools/`, not `src/`: the running service cannot
  load a list because the code that does is not on its classpath.
- The `paymentInstruction.screeningDigest` stored in a creation's idempotency
  response before migration `0015` is absent; `IdempotencyKeyLookup.originalBody`
  says so.

**O-12 — Other clients of this stack meet the new refusal.** Every submission
on a stack with no list accepted is now `422 no-screening-list-accepted`.
UAT-004, -005 and -006 submit, so each now names `make load-screening-list` as a
prerequisite (UAT-007 inherits it from UAT-006); UAT-005's count of idempotent
operations follows the code. **The capture harness** (`make capture-trace`),
which this file first named as out of reach, is in this repository and the
brief names its seeding seam: as first built, a capture at any ref carrying
this change stopped at its first submission (review finding R-4). Fixed in
`4173082`: `clofin.tools.capture.stack/load-screening-list!` runs the captured
commit's **own** loader on the commit's **one** shipped list after migrating,
before the service starts; a commit from before TASK-017 ships none and needs
none; two shipped lists are refused rather than chosen between
(`a-capture-loads-the-one-screening-list-the-commit-ships`,
`a-capture-loads-the-commit-s-list-after-migrating-and-before-the-service-starts`;
**M27**). Exercised for real once: the commit's loader, run by that function
against a freshly migrated database, loaded `synthetic-2026-10-v1` (4 entries,
accepted). A full `make capture-trace` was not run: it captures a **tagged**
ref, and no tag carries this change until `ref-3`. Out of this brief's reach
and named for its owner: `clofin-cockpit`'s scenario runner submits, and a
stack it drives needs the list loaded.

**O-13 — Documents changed beyond the lines the brief named.** Each is a copy of
a claim this change made false (L-16), corrected rather than left: the
contract's Payments tag ("Approval and settlement exist; screening does not"),
its approval-queue description ("Screening outcome is absent because screening
does not exist yet") and the submit operation's "*Not implemented:* screening";
`README.md`'s scope row, which called sanctions screening a simulated adapter;
`ARCHITECTURE.md` §2's context drawing ("Sanctions/PEP screening (simulated)")
and the sentence under it; and `COMPLIANCE.md` C-05's enforcement row for
`clofin.ledger.purity-test`, which named four services and had omitted
`clofin.recon.service` since TASK-008. *Ruling asked* only if any is unwelcome.

**O-14 — The environment (no ruling needed).** This container has no Docker
daemon. `make test-it` was run as `make -o db-up test-it` against a local
PostgreSQL 16 (its own `migrate` and `clojure -M:test:it` steps, unchanged), as
018-REQ O-8 did. UAT-008 ran against a stack started from this branch with
`clojure -M:run` on a freshly created database (what `make up` runs in its
container), with `psql` for `make db-shell` and a `uuidgen` shim reading
`/proc/sys/kernel/random/uuid`. `make smoke` cannot run without Docker; what it
checks — `/healthz` and `/readyz` — was checked against a stack with **no list
loaded** (§9): both answer, because neither consults screening.

**O-15 — Disposition and approval are separated per role, not per actor
(review finding R-3).** `clofin.authz.model` grants `:screening/disposition` to
`compliance` alone and to no role holding `:payment/create`, `:payment/submit`
or `:payment/approve` — AC-17-12, as built. But an actor can hold several
roles: one **granted** both `compliance` and `approver` can disposition a hit
`false-positive` and then approve the same instruction. Only the maker is
refused per case (`self-disposition`); `clofin.authz.approval/evaluate` knows
nothing of dispositions. The model's comment, the AC-17-12 test's label and
COMPLIANCE C-07 claimed the actor who clears a hit "is never one who could
approve". Those claims are narrowed in `4173082` to what the code enforces
(per role), and the gap is stated in C-07's enforcement table. **Not built**:
refusing it per case means a new approval refusal (the approver dispositioned a
case on this instruction), a contract change to approval and C-01's
vocabulary — beyond this brief. *Ruling asked:* a follow-up brief for the
per-case rule (my recommendation: C-01's shape, checked under the instruction's
lock at approval), or accept per-role separation as the control's boundary.

---

## 7. UAT-008, executed against a fresh stack (AC-17-15, L-20)

**Stack.** A newly created database (`clofin_uat8`, dropped and created
immediately before), and the service started from this branch at **`92c17c3`**
(the last code commit, after every review pass) by `clojure -M:run` — `GET /readyz` answered
`{"status":"ready","checks":{"database":"ok"},"schemaVersion":"0015"}`, the
migrations applied on start, and `GET /` reported `sourceCommit`
`92c17c31ee792a13ef07559e3e45432a420936da`. The script's commands were run as
written, from the file, with the substitutions O-14 names (`psql` for
`make db-shell`; a `uuidgen` shim). Executed by: the implementing Worker, acting
as the operator, the maker, the compliance actor, the screening client, the
checker and the auditor in turn. Date: 2026-10-05.

**Runs before this one.** The first run, at `227a338`, found a
defect in the script: it exported `LIST=synthetic-2026-10-v1` for its own use,
`make` read that for the Makefile's `LIST` (the list *file*), and step 1 failed
with the loader's uncaught `FileNotFoundException`. Two fixes: the script
exports `VERSION` and warns against exporting `LIST`; the loader refuses an
unreadable path by name (`d809f36`, with a test). A clean second run at
`d809f36` passed 13 of 13 and was the record this file was first filed with.
Runs at `1333125`, `0519f0d` and `3748d58`, after the review fixes, also
passed 13 of 13. **The run below is the sixth, on a fresh database at
`92c17c3`,** because
the fixes changed the code UAT-008 drives (case opening, the `409`, the list
lock). Every step has the same result as in the earlier passing runs; only
identifiers and times differ. Nothing from the earlier runs is quoted.

| Step | Requirement | Expected | Observed | Result |
|---|---|---|---|---|
| 1 | PR-063 | `make load-screening-list` loads the shipped list; a second load is refused | `Loaded screening list synthetic-2026-10-v1 (4 entries). Synthetic list, exact matching.` (exit 0); then `Refused: Screening list synthetic-2026-10-v1 is already loaded; a changed list is a new version` / `reason: version-already-loaded` (make exit 2) | Pass |
| 2 | PR-063 | actors seeded; the list readable entry by entry | every seed `INSERT` succeeded (`INSERT 0 1` ×13, `INSERT 0 2` ×1); `GET /screening-lists/synthetic-2026-10-v1` as the client: four entries `SYN-0001`…`SYN-0004`, `"accepted":true`, `SYN-0004` with two rules | Pass |
| 3 | PR-060, PR-061 | `409 screening-hit` with a case; instruction still `draft` | `HTTP/1.1 409`, `application/problem+json`, `errors` `{"reason":"screening-hit","listVersion":"synthetic-2026-10-v1","resultId":"65b38d7f-…","caseId":"158c989b-…"}`; read back `"status":"draft"`, `screeningDigest` `f2621166…` | Pass |
| 4 | PR-061, PR-063 | case open, bound to the digest, `disposition` permitted | `"status":"open"`, `"instructionDigest":"f2621166…"` (= the instruction's `screeningDigest`), `"listVersion":"synthetic-2026-10-v1"`, `"permittedTransitions":["disposition"]` | Pass |
| 5 | PR-061 | the maker (who holds compliance) refused `403 self-disposition` | `HTTP/1.1 403`, `errors` `{"reason":"self-disposition","instructionId":"5a3caaaf-…"}` | Pass |
| 6 | PR-061, PR-063 | compliance `200`, final; a second disposition `409` | `200`, `"status":"dispositioned"`, `"disposition":"false-positive"`, the rationale as sent, `"dispositionedBy":"82222222-…"` (Cleo), `"permittedTransitions":[]`; again with a new key: `409`, `errors` `{"case-status":"dispositioned","attempted":"disposition","permitted":[]}` | Pass |
| 7 | PR-060 | submit `200`, `pending-approval` | `HTTP/1.1 200`, `"status":"pending-approval"` | Pass |
| 8 | PR-015 | the queue row carries `screening` | the row for the instruction carries `"screening":{"resultId":"9ddd479f-…","outcome":"hit","listVersion":"synthetic-2026-10-v1","recordedAt":"2026-10-05T21:25:02.023313Z"}` — core's step-7 decision, a hit cleared false-positive (O-8) | Pass |
| 9 | PR-060, PR-061 | amend → `draft`, new digest; submit → `409` with a **new** case | `PATCH` → `"status":"draft"`, `screeningDigest` `bcb054d5…` (was `f2621166…`); submit → `409 screening-hit`, `caseId` `6eb95fe7-…` ≠ `158c989b-…` (the first case is dispositioned, so nothing blocks — no `blockingCaseId`) | Pass |
| 10 | PR-063 | a `clear` core cannot reproduce: `422` with core's answer; the refused row listed | `HTTP/1.1 422`, `errors` `{"reason":"screening-result-mismatch","resultId":"66f2a28e-…","coreOutcome":"hit","coreMatchedEntries":["SYN-0001"]}`; the results list (4 rows, newest first, not truncated) begins with `"origin":"client","outcome":"clear","coreOutcome":"hit","agrees":false,"disposition":"refused","dispositionReason":"screening-result-mismatch"` and an `auditEventId`, then core's three results from steps 9, 7 and 3 | Pass |
| 11 | PR-060, PR-063 | a reproducible `hit`: `201`; **read back: status unchanged**; no `payment.*` event for it | `HTTP/1.1 201`, `Location: /screening-results?instructionId=5a3caaaf-…`, `"disposition":"accepted","agrees":true`, `auditEventId` `0df3f993-…`, `caseId` `6eb95fe7-…` — step 9's case, which covers this content against this list, so no second case opened (R-1). **Read back: `"status":"draft"`, `"permittedTransitions":["cancel","submit"]` — the same as before the `201`. The `201` recorded evidence and changed no state.** The instruction's own events: `payment.created`, `payment.submitted`, `payment.amended` (3 rows) — nothing from steps 10 or 11 | Pass |
| 12 | PR-060 | list retired; submit `422 no-screening-list-accepted`; still `draft` | `Retired screening list synthetic-2026-10-v1. No list is accepted: every submission is refused until one is loaded.` (exit 0); a fresh ordinary instruction's submit → `HTTP/1.1 422`, `errors` `{"reason":"no-screening-list-accepted"}`; read back `"status":"draft"` | Pass |
| 13 | PR-063 | case pack: opened then dispositioned; one `screening-result.recorded` per result | evidence pack for `158c989b-…`: `subjectType` `screening-case`, two events, `screening-case.opened` (actor Priya, the step-3 correlation id `432270bf-…`, the `409`'s `instance`) then `screening-case.dispositioned` (actor Cleo, `beforeDigest` = the opening's `afterDigest`, `v1:20beb871…`); `GET /audit/events?action=screening-result.recorded`: count **5** — core's three (actor Priya) and the client's two (actor the screening client), the refused one included | Pass |

**13 of 13 steps pass.** One check beyond the script, on the same database
after step 12: of the five results, **none** has a `recorded_at` at or after
its list's `retired_at` (the R-7 invariant), and the list's `loaded_at`
precedes its `retired_at`. The full transcript of the run is
kept with the session's working files rather than committed (the evidence
above quotes it); the seed SQL it executed is the script's own, verbatim, with
the organisation id pasted.

---

## 8. Self-review, the adversarial review, and the L-9 statement

**Self-review.** Running the work as an operator would found two defects that
my tests had not caught, both in UAT-008's first run (§7). The script's exported
`LIST` shadowed the Makefile's list path, and the loader answered an unreadable
path with a stack trace. Both are fixed in `d809f36`, the loader's refusal with
a test (`a-path-that-is-not-a-list-is-refused-naming-the-path`). Re-reading the
diff against the brief found the six brief claims in O-3, the ROADMAP line in
O-2 and the router collision in O-1. Each was raised; none was worked around
silently. The negative controls found one defect of their own. Run on
`0519f0d`, M25 and M26 showed a `retired_at` cut to the millisecond on its way
back through JDBC, and that is fixed in `8de56e7`. The commit trailers carry no
model identifier. One early local commit's trailer did, and it was amended
before anything was pushed. Every pushed commit reads
`Co-Authored-By: Claude <noreply@anthropic.com>`.

**The independent adversarial review** was a separate read-only pass over
`24b11f9..1891126`, on its own worktree, against the brief and ADR-0028 D5/D8.
It reported **nothing blocking**. It confirmed these as clean:

- No path submits without core's own permitting decision over the locked row's
  digest. Only `transition!` writes `status`, the only API caller with `:submit`
  goes through `submit-screened!`, and the gate re-reads `origin = 'core'`.
- No client result transitions anything or writes a `payment.*` event.
- Both stored refusals are effect values, committed with the key and replayed
  byte for byte. Every thrown refusal runs before any write.
- The lock order holds, and the AC-17-7 interleaving is really forced.
- Every new read is scoped to the organisation.
- The contract matches the wire functions.
- It recomputed the golden digest independently and got the same value.
- No document claims screening quality or any connection.

It reported eleven findings. This file was first filed with them open (the
session stopped to conserve usage). **All eleven are now fixed**, in `4173082`
and `1333125`, with R-7's fix replaced in `0519f0d` (below). Each fix that
changes behaviour has its own test and its own negative control in §4, every
one seen failing.

| # | Severity | Finding | Disposition |
|---|---|---|---|
| R-1 | should-fix | A client's accepted hit on content and a list that a **dispositioned** case already covered opened a second case. `decide` reads the latest case, so a final `confirmed-hit` could be superseded with no amendment | **Fixed** (`4173082`): an accepted client hit opens a case only when no case covers the triple, and the `201` names the covering case. **Narrower than A-6's text — O-7, ruling asked.** `ac-17-9-a-client-hit-never-supersedes-a-disposition`; **M21** |
| R-2 | should-fix | `submit`'s `409` named a case open on earlier content or a retired list as this content's `caseId` | **Fixed** (`4173082`): it is named `errors.blockingCaseId`, never `caseId`, with a true detail; contract, ADR Amendment 1 and C-07 say so. `ac-17-2-a-case-open-on-earlier-content-is-named-as-blocking-never-as-this-contents` (amendment and list replacement); **M22**; O-6 |
| R-3 | should-fix | Disposition and approval are separated per role, not per actor; comments, a test label and COMPLIANCE claimed per actor | **Fixed as a claim** (`4173082`): the model's comments, the AC-17-12 test label and C-07's enforcement table now say per role and name the gap. **The per-case rule is not built — O-15, ruling asked** (it would add an approval refusal beyond this brief) |
| R-4 | should-fix | `make capture-trace` at a ref carrying this change stopped at its first submission: the capture loaded no list | **Fixed** (`4173082`, `1333125`): `clofin.tools.capture.stack/load-screening-list!` runs the commit's own loader on its one shipped list after migrating and before the service starts. `a-capture-loads-the-one-screening-list-the-commit-ships`, `a-capture-loads-the-commit-s-list-after-migrating-and-before-the-service-starts`; **M27**; exercised for real against a fresh database (O-12) |
| R-5 | should-fix | Stale copies: C-06's "six … mutations", "five roles", DOMAIN_MODEL's "Five exist" and "The six it protects", UAT-004's screening row, the payments docstring on unconsumed keys, "seventeen-route" | **Fixed** (`4173082`), plus a whole-repo sweep for the same claims, which found the idempotency docstring too |
| R-6 | should-fix (doc) | C-07's statement overstated for instructions already past `submit` when `0015` is applied | **Fixed** (`4173082`): C-07's boundary says the statement holds for every instruction submitted under `0015` and later, and how a checker sees one that was not (no `screening` on its queue row) |
| R-7 | nit | A list retirement did not serialise with a decision in flight: a submission could commit against a list retired before it committed, and a result could carry a `recorded_at` after its list's `retired_at` | **Fixed** — first with row locks (`4173082`), then, after the re-review, with a transaction-scoped advisory list lock (`0519f0d`, `8de56e7`; O-11): every decision holds it shared, the tool exclusive, and the tool stamps one instant after its waits. Five forced interleavings in `clofin.screening.concurrency-test` and the hand-over test; **M23–M26**, **M31**; checked again on UAT-008's database (§7) |
| R-8 | nit | `screeningDigest` is required, but a replayed body stored before `0015` lacks it; submit's `409` omitted `screening-required` | **Fixed** (`4173082`): both stated in the contract. `screeningDigest` stays required, as A-1 says |
| R-9 | nit | `decision-test`'s "every declared answer is reached" compared a set with a literal and could not fail | **Fixed** (`4173082`): computed from `decide` over the matrix; **M28** shows it failing |
| R-10 | nit | UAT-008 step 13 said step 10's listing showed five results | **Fixed** (`4173082`): four listed in step 10, plus step 11's |
| R-11 | nit | A malformed loader command line died with a stack trace | **Fixed** (`4173082`, widened in `0519f0d` and `3748d58`): every malformed line is a usage error, exit 2 — an odd count, a flag twice, a flag as the file or the version — in a unit namespace `make verify` runs. `a-malformed-command-line-is-a-usage-error-not-a-stack-trace`; **M29**, **M30**, **M34** |


**The re-review, in three passes.** The fixes were reviewed again by panels
of read-only reviewers, each on one area, with a skeptic assigned to refute
every non-trivial issue and a completeness critic. Two passes were cut short
when the account's usage limit was reached; what ran is stated, and the part
that did not run was re-run in a later pass.

- **Pass 1** (`14b12fd..4173082`). Three of six reviewers completed: case
  semantics, locking, and the tools. They found R-1, R-2, R-4 and R-7 **fixed**
  and raised fourteen notes. The documentation, tests and regressions reviewers,
  the refutation pass and the critic did not run.
- **Pass 2** (`14b12fd..0519f0d`), after the fourteen notes were fixed in
  `0519f0d`. The lock, documentation and tests reviewers completed, with twenty
  notes; the regressions reviewer, the refutation pass and the critic did not
  run. (Two notes were raised twice.) One note, the millisecond truncation of the hand-over instant, had
  already been found by this file's own negative control M25/M26 and fixed in
  `8de56e7`; the rest were fixed in `3748d58`.
- **Pass 3** (`14b12fd..3748d58`): regressions over the whole range, fresh eyes
  on `8de56e7` and `3748d58`, the refutation pass and the critic — **complete**: both reviewers, a skeptic for each of their four non-trivial
  issues (none refuted), and the critic all ran. Ten notes, below; eight were
  fixed in `92c17c3`, and two are answered here without a change.

| Pass | Note | Fixed in |
|---|---|---|
| 1 | Two back-to-back list replacements could refuse a submission spuriously (`422 no-screening-list-accepted`), because the second read of the row-lock design was not enough | `0519f0d`: the advisory lock — a decision reads the list in a statement whose snapshot follows any change it waited for |
| 1 | A steady stream of decisions could starve a retirement's `for update` | `0519f0d`: a queued exclusive advisory request is not overtaken by later shared ones |
| 1 | `for share` needs `UPDATE` privilege on `screening_list`, which a future read-only runtime role would lack | `0519f0d`: an advisory lock needs no table privilege |
| 1 | A replacement's `loaded_at` (`now()`) preceded its predecessor's `retired_at` — two lists in force at once, by the rows | `0519f0d`: one instant for both |
| 1 | No test reached the gate's own list lock | `0519f0d`: `a-retirement-in-flight-and-a-direct-transition-serialise-at-the-gate`; **M23b** |
| 1 | The lock-order sections omitted the list lock | `0519f0d`, corrected again in `3748d58` |
| 1 | `parse-args` silently kept the last of two `--replacing`, and took `--replacing` as the file | `0519f0d`; **M30** |
| 1 | The R-11 test lived in a database-fixtured namespace `make verify` never runs | `0519f0d`: `clofin.tools.screening-list-args-test`, a unit namespace |
| 1 | Nothing asserted that `capture!` calls the list step | `1333125`; **M27** |
| 1 | The capture and stack docstrings did not describe the list step | `0519f0d` |
| 1 | The loader's `-main` printed a stack trace for configuration, connection and SQL failures | `0519f0d`: one `Failed:` line, exit 1 |
| 1 | The `409` text named `blockingCaseId` for earlier content only; the `submit` docstring not at all | `0519f0d` |
| 1 | "One case per content and list" rested on a read, with no enforcement row | `0519f0d`: a C-07 enforcement row; O-7 asks about a unique key |
| 1 | R-2's departure from A-7 carried no "ruling asked" | `0519f0d`: ADR-0028 Amendment 1, O-6 |
| — | (this file's own M2 run, not a reviewer's) The R-2 test's detail assertions threw on a missing `detail` | `0519f0d` |
| 2 | The hand-over instant was bound back through JDBC at millisecond precision, so `retired_at` could fall before a result recorded in the same millisecond | `8de56e7`: carried as PostgreSQL text; the hand-over test compares in SQL; **M25**, **M26** |
| 2 | The lock-order docstring gave the wrong reason the loader cannot deadlock with a decision | `3748d58`: the reverse order on `screening_list` is safe only by lock compatibility, written down |
| 2 | No test raced a client's result against a list change | `3748d58`: `a-retirement-in-flight-holds-the-list-and-a-client-result-waits-then-is-refused`; **M31** |
| 2 | A case's `opened_at` and an event's `occurred_at` are the transaction's start, before any list wait | `3748d58`: **documented, not changed** — C-07 says the order of decisions against lists is read from `recorded_at`; changing the audit time's meaning is beyond this brief (O-11) |
| 2 | DOMAIN_MODEL §2.7 listed five roles; §2.6's coverage omitted screening | `3748d58` |
| 2 | C-01's "the actor holding every role" — the test's cell held five of six | `3748d58`: derived from `clofin.authz.model/roles` |
| 2 | C-05's "at every submission", C-07's "opens a case", the R-6 boundary's queue row, the contract's case wording, and a C-07 enforcement row that cited O-7 for a proposal O-7 did not make | `3748d58`, and O-7 in this update |
| 2 | ARCHITECTURE's dependency rule named two of the three screening namespaces the gate requires; ADR-0028's intro claimed `contract-test` compares `blockingCaseId` | `3748d58` |
| 2 | `parse-args` took a flag as the `--replacing` version | `3748d58`; **M34** |
| 2 | The capture tests never checked which worktree the loader ran in | `3748d58`: the loader's working directory, environment and arguments, and `capture!`'s, asserted; **M32**, **M33**, **M37** |
| 2 | The R-1 test's "the disposition stands" could not fail | `3748d58`: it asserts the instruction's only case is the dispositioned one |
| 2 | A test message named the row lock `0519f0d` removed | `3748d58` |
| 2 | Citations of O-15 and of §8's second-pass notes pointed at entries this file did not yet hold | this update: O-15 is in §6, the notes in this table |
| 3 | The hand-over test could not detect the truncation `8de56e7` fixed: a truncation cuts both columns alike, so they stay equal (should-fix, confirmed by its skeptic) | `92c17c3`: `the-hand-over-instant-keeps-its-microseconds` — five replacements must leave some `retired_at` off a whole millisecond; **M35** |
| 3 | A list version beginning with `--` could be loaded, then never replaced or retired: the command line reads it as a flag | `92c17c3`: `validate` refuses a version beginning with `-` (`invalid-version`); **M36** |
| 3 | The capture test did not check the loader is given the capture database | `92c17c3`: the stand-in prints `CLOFIN_DB_URL`; **M37** |
| 3 | The loader printed `Refused:` for a configuration that does not load | `92c17c3`: `Refused:` only for the tool's own refusals, `Failed:` otherwise; **M38** |
| 3 | C-07 and the contract omitted that a case open against a **replaced list** also blocks | `92c17c3` |
| 3 | C-07 said the list a decision was taken against is read from `recorded_at` (it is `list_version`) | `92c17c3` |
| 3 | ADR-0028 Amendment 1's sentence on which test compares which member was incomplete | `92c17c3` |
| 3 | DOMAIN_MODEL's coverage sentence had lost a word | `92c17c3` |
| 3 | A `409` **stored by this branch before `4173082`** and replayed under its key carries `caseId` where the contract now says `blockingCaseId` | **Not changed.** A replay returns the stored response as stored, which the contract states for every operation. Only a database that ran this unmerged branch's earlier commits can hold such a body; nothing released ever wrote one |
| 3 | `8de56e7`'s message cites a `retired_at` of `.431000` beside a `loaded_at` of `.430454`, which the reviewer said that commit's code could not produce | **Not changed — the premise misses where the evidence came from.** The pair came from **M26**'s run on `0519f0d`, where the mutation had put `loaded_at` back on `now()` (microseconds, unbound) while `retired_at` was the instant bound through JDBC (cut to `.431000`) — the truncation, visible because one column escaped it (§4) |

**L-9, in plain words.** **Verification is complete, and nothing I declared is still running.**
Every suite, mutation, stress run and UAT-008 in §9 ran on `92c17c3`, the last
code commit, after the last review pass's fixes. The adversarial review's
eleven findings and all the re-review notes are fixed, or answered above with a
reason. The rulings in §6 remain Master Control's, and so does CI on the
branch, which cannot be green until O-2 is ruled.

---

## 9. Verification

Every row below was run on **`92c17c3`**, the last code commit, unless it says
otherwise. The first filing's figures (on `1891126`) are kept in this file's
history.

| Suite | Command | Result |
|---|---|---|
| Baseline, before any change | `make -o db-up test-it` on `24b11f9` | 1,010 tests / 7,872 assertions, 0 failures, 0 errors |
| `make verify` | `make -k verify`: unit and property tests, docs links, diagrams, document consistency, disclaimer | **Red, by O-2 alone.** `test`: 582 tests / 3,913 assertions, **2 failures**, 0 errors. Both failures are in `clofin.tools.doc-consistency-test/ac-20-the-live-roadmap-passes-rule-5` (`doc_consistency_test.clj:422` and `:423`), which runs the script on the live tree and reads `DISAGREE docs/ROADMAP.md:52 says C-07 is 📋`. `docs-check`: `Documentation links OK (110 markdown files checked)`. `diagrams-check`: `Diagrams OK (7 generated artifact(s) match their sources)`. `doc-consistency`: `1 document disagreement(s)`, the same line. `disclaimer-check`: `Disclaimer OK (make help, 1 release annotation(s) checked, 1 historical)` |
| `make test-it` | `make -o db-up test-it` (O-14), from an **empty** database: the migrator applied `0001`…`0015` | 1,072 tests / 8,770 assertions, **the same 2 failures**, 0 errors. Nothing else fails |
| `make diagrams-check` | after `make diagrams` (the control map gains C-07's two new enforcement rows) | `Diagrams OK (7 generated artifact(s) match their sources)` |
| `make doc-consistency` | on the branch | **Red: `1 document disagreement(s)`**, `docs/ROADMAP.md:52` (O-2). On a scratch copy with only that line changed: `Document consistency OK (13 control(s), 35 increment status claim(s), 0 prose task claim(s), 20 brief(s))` |
| `make docs-check` | on the branch | `Documentation links OK (110 markdown files checked)` |
| Upgrade path | `0015` on a database `main`'s own tree migrated to `0014` | `Applied 1 migration(s): 0015`; `Pending: 0` (on `1891126`; the migration has not changed since) |
| Readiness with no list | `clojure -M:run` on a newly created database, no list loaded: what `make smoke` checks (O-14) | `GET /healthz` → `200 {"status":"ok",…}`; `GET /readyz` → `200 {"status":"ready","checks":{"database":"ok"},"schemaVersion":"0015"}` (on `1891126`; neither endpoint consults screening). CI's **Compose stack smoke test** is green on every pushed head |
| Race stress | `clofin.screening.concurrency-test` (AC-17-7's three interleavings and R-7's five), 15 runs | 15 runs, 750 assertions, 0 failures, 0 errors |
| The capture's list step, for real | `clofin.tools.capture.stack/load-screening-list!` with the real `clojure` on a freshly migrated database (O-12) | `:loaded`; `synthetic-2026-10-v1`, 4 entries, accepted (on `4173082`; the function has not changed since) |
| Negative controls | §4 | 46 runs: the 44 mutations (M1–M38, several in parts) each seen failing its named test; M13c and M13d green by design; tree clean after every run, the dropped index and trigger restored by the migration's own DDL |
| UAT-008 | §7 | 13 of 13 steps pass on a fresh stack at `92c17c3`; on its database after step 12, no result recorded at or after its list's `retired_at` |

**What is not green, exactly.** Two assertions in one test, both reading one
line of a document this brief forbids me to edit. Nothing else fails in either
suite. To make this branch pass, the repository-level gate was not bypassed,
flagged or weakened: no test was skipped, no check was relaxed, and C-07 was not
held at 📋 to satisfy the script. CI shows the same picture on each pushed
head: the unit job stops at its doc-consistency step, the integration job fails
in its test step, and the smoke test is green. On the PR I named the failure
and what would end it (one comment), and did not repeat it on each push.

**M35 (§8)** — the hand-over instant comes back as a JDBC timestamp again —
the `8de56e7` regression. 52 / **1**:

```
FAIL in (the-hand-over-instant-keeps-its-microseconds) (screening_list_test.clj:141)
expected: (some pos? sub-ms)
  actual: (not (some … [0 0 0 0 0]))
```

— five retirements, every one on a whole millisecond. The equality test above
stays green under this mutation, which is why this test exists.

**M36 (§8)** — `validate` accepts a version beginning with `-`. 51 / **2**:

```
FAIL in (ac-17-8-every-other-refusal-in-a-5) (screening_list_test.clj:68)
expected: (= expected (refusal …))
  actual: (not (= "invalid-version" nil))
…
  actual: (not (= [{:version "synthetic-2026-10-v1", :retired-at nil}]
                  [{:version "--2026-11", :retired-at nil} {:version "synthetic-2026-10-v1", :retired-at #inst "…"}]))
```

— a list the command line can never name again, accepted.

**M37 (§8)** — the capture runs the list loader without the capture database
in its environment. 43 / **1**:

```
FAIL in (a-capture-loads-the-one-screening-list-the-commit-ships) (capture_stack_test.clj:460)
  actual: (not (str/includes? "cwd=/tmp/capture-lists… db= args=-M:screening-list load …" "db=jdbc:postgresql://127.0.0.1:1/none "))
```

**M38 (§8)** — the loader calls every `ExceptionInfo` a refusal again. 19 /
**1**:

```
FAIL in (the-loader-says-refused-only-for-its-own-refusals) (screening_list_args_test.clj:48)
  actual: (not (= "Failed: CLOFIN_ENV must be one of dev, prod, test" "Refused: CLOFIN_ENV must be one of dev, prod, test"))
```
