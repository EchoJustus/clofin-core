# 015-REQ — `ref-2` release remediation

| Field | Value |
|---|---|
| **Reviews** | `FEEDBACK-REL-ref-2.md` on `origin/meta` — the first complete release audit. 23 findings (2 blocking, 20 should-fix, 1 consider) plus the unnumbered residual C-R10a. Cited as a path rather than linked: `FEEDBACK` files live on the control plane and do not resolve from this branch |
| **Brief** | [`015-TASK-ref-2-release-remediation.md`](../briefs/015-TASK-ref-2-release-remediation.md) on `origin/meta`, dispatched 2026-09-06 |
| **Series number** | **015.** Task-keyed, reporting on `TASK-015` (**L-1**). The release-remediation form `REL-<tag>-REQ` is for a remediation with no brief; this one has a brief, and the register's decision block of 2026-09-06 says so explicitly |
| **Branch** | `claude/task-015-ref-2-remediation-mlgzj8` — designated by the execution environment, substituted for a `feat/` name. No other divergence |
| **PR base** | `main` at `d78cb39` (the merge of PR #30, the sync carrying this brief). The RC `c97a4f2` differs from that tip by documents and one line of `scripts/check-doc-links.sh` only |
| **Model** | An Anthropic Claude model. **The identifier is deliberately not written into this file**: this session operates under a rule that keeps the model identifier out of repository artifacts. Recorded as an absence rather than omitted silently, because the provenance column exists to say what is known, and this is a thing that is known and withheld. The same form `014-REQ` used |
| **Reasoning effort** | High — extended thinking throughout. The harness exposes no numeric setting to the session, so this is the mode, not a measured value |
| **Date** | 2026-09-07 |
| **Migrations** | **None.** Every item was code, tests, contract or prose against the schema at `0013`. The brief's migration pre-flight says *No migration* and nothing here needed one. Migrations replayed `0001`→`0013` from an empty schema after the last commit: 13 applied, 0 pending |
| **Controls touched** | **C-05** and **C-13** in `docs/COMPLIANCE.md`, plus one new row in §4 — **statements narrowed or made true, no control weakened**. C-05 **strengthened**: its service list is now compared with `service-namespaces` in both directions. C-13 **strengthened** twice: its evidence sentence states the bound it always had, and its posting-path sentence became true and is now enforced by a producer census rather than described. C-06's prose copies outside COMPLIANCE are narrowed (§6b); its own section already narrowed and is unchanged. The capture harness (ADR-0022) is strengthened on both of its findings |
| **Status** | Implemented. Both blockers closed; all 20 should-fix, the consider, and C-R10a actioned. **Four objections in §9**, none resolved unilaterally |
| **Verification** | `make verify` **525 tests / 3,244 assertions**, 0 failures, 0 errors. `make test-it` **951 tests / 7,272 assertions**, 0 failures, 0 errors. **No verification is in flight** (**L-9**) — see §8 |

---

## 1. What this file is for

Every finding the register dispositioned as *actioned in the subject* is below,
with what changed, the test that holds it, and **the failure that test produces
at the RC**. The brief requires that last column and it is the point: a
regression test nobody has watched fail is a test whose shape nobody knows.

Where a finding is a *missing proof* rather than a defect — 2B-004 — the test
passes at the RC, and that is stated rather than hidden. Where a finding is
*false protection* — 2C-003 — the RC evidence is the guard admitting the
mutation it exists to catch, because a corrected test run against the RC's
correct contract would pass and prove nothing.

---

## 2. The two blocking findings

### 2C-002 — concurrent contradictory statements reported as replays

`clofin.recon.service` compared digests in its pre-check and in neither
collision recovery. A transaction that lost the receipt key replayed the
winner's receipt on the strength of the key alone, so a document CloFin had
never processed was acknowledged as an exact replay of a different one — while
the identical contradiction sent serially was refused `409`, and ADR-0023 says
in so many words that *a different document under the same reference is refused
and is never called a replay*.

**The fix is one function.** `decide-against-existing` makes the decision once;
the pre-check and both recoveries call it. Three copies of a decision drift and
one cannot, which is standing lesson **L-18** and is why the brief asked for a
factoring rather than two patched branches.

`clofin.recon.concurrency-test` forces the interleaving with a latch on
`recon/find-statement-by-reference`, released only once both threads have looked
and both have seen no receipt. Everything after that is real: the insert, the
savepoint rollback, the commit and the rendering. Four cells, {identical,
different} × {applied, refused}, plus a fifth test asserting the raced answer
and the serial answer are the same answer.

**At the RC** (`clojure -M:test:it clofin.recon.concurrency-test`, 8 failures /
76 assertions):

```
FAIL in (ac-1-different-documents-racing-refuse-the-loser-rather-than-replaying-it)
one applies and one is refused the reference ?
  ({:status 200, :replayed true, :reason nil} {:status 200, :replayed false, :reason nil})
expected: (= #{200 409} (set (map :status answers)))
  actual: (not (= #{200 409} #{200}))

FAIL in (ac-1-different-documents-racing-on-the-refused-path-refuse-the-loser)
  ({:status 422, :replayed true, :reason "no-reconciled-account"}
   {:status 422, :replayed false, :reason "no-reconciled-account"})
expected: (= #{422 409} (set (map :status answers)))
  actual: (not (= #{422 409} #{422}))
```

`{:status 200, :replayed true}` on a document nobody sent is the finding,
printed. On the branch the namespace is green: **7 tests / 133 assertions, 0
failures** — the five `ac-1-*` cells above and the two `ac-8-*` lock proofs
2B-004 asks for.

### 2C-006 — capture attributes another process's answers to the requested commit

`start!` accepted a `200` on `/readyz` before asking whether the child it
spawned was alive, and `assert-schema-matches!` compared schema versions, which
is not an identity.

A capture now binds to **the process it started** (**L-19**): the port is
refused if anything already answers on it; a per-run UUID is minted *after* any
such process would have started and passed as `CLOFIN_INSTANCE_ID`; `GET /`
echoes it as an optional `instanceId`; liveness is sampled **before** any `200`
is looked at; and identity is re-checked before every writer call and before the
manifest. `assert-schema-matches!` stays, with its docstring corrected to say a
schema version is a check and not an identity.

**At the RC**, the audit's own reproduction, run against the unmodified
namespace (`scratchpad/rc/rc_probe_2c006.clj`):

```
an unrelated responder is listening on port 35161 reporting schema 0013
start! RETURNED: {:base-url "http://127.0.0.1:35161",
                  :readyz "{\"status\":\"ready\",\"schemaVersion\":\"0013\"}"}
child alive? false  exit value: 1
assert-schema-matches! ACCEPTED: "0013"
```

The harness returned a stranger's response as the captured stack, with its own
child dead. `clofin.tools.capture-stack-test` covers all five of the brief's
cases with a `com.sun.net.httpserver.HttpServer` and `/bin/false`, needing no
CloFin service: **8 tests / 17 assertions, 0 failures** on the branch; at the RC
the namespace does not compile, because none of the functions it asserts on
exists.

**A second hole, closed with it — and the brief describes it wrongly.** See
objection **O-1**: the brief says the child "resolves nothing and reports
`unknown`". It does not. It resolves the worktree's commit correctly when
nothing is stamped, and under `make capture-trace` it inherits
`CLOFIN_SOURCE_COMMIT` — which that Makefile exports as the *harness's* `HEAD` —
and reports `main`'s SHA for a stack running from a tag. Measured at the RC:

```
RC env-for keys: (CLOFIN_DB_PASSWORD CLOFIN_DB_URL CLOFIN_DB_USER CLOFIN_ENV
                  CLOFIN_HTTP_HOST CLOFIN_HTTP_PORT CLOFIN_MIGRATE_ON_START)
sets CLOFIN_SOURCE_COMMIT? false
  inherited stamp wins over the worktree -> "d78cb392a6560ac4d5c963f39e50c66b519720e3"
  with no stamp, the worktree resolves  -> "c97a4f25a4cb2d9210613939cb55c3dafcddf32f"
```

`env-for` now sets it explicitly from the commit under capture, and
`assert-same-process!` refuses anything else.

---

## 3. Finding by finding

Every disposition the register recorded as *actioned in the subject*. RC
evidence is a command anyone can re-run from a worktree at `c97a4f2` with the
branch's test files copied in.

| Finding | What changed | Test | At the RC |
|---|---|---|---|
| **2C-002** *(blocking)* | `decide-against-existing` — one decision for the pre-check and both collision recoveries | `clofin.recon.concurrency-test` `ac-1-*` (5 tests) | 8 failures; the loser answers `200 replayed:true` and `422 replayed:true` |
| **2C-006** *(blocking)* | Port pre-flight, per-run instance id, liveness before acceptance, identity before every write; `CLOFIN_SOURCE_COMMIT` passed explicitly | `clofin.tools.capture-stack-test` (8 tests) | `start!` returns a stranger's `200` with its child dead; probe quoted in §2 |
| **2B-003** | `idempotent-replayed` exposed; the test discovers what `src/` sets and what the contract declares, both directions | `clofin.http.cors-test` `exposed-headers-…`, `a-browser-page-can-read-the-replay-header` | 2 failures: the discovered set has `idempotent-replayed`, the exposed list does not |
| **2B-004** | Two latch-based two-connection proofs: assignment against resolution, two decisions on one adjustment | `clofin.recon.concurrency-test` `ac-8-*` | **Passes.** A missing proof, not a broken lock — see §4 |
| **2B-005** | C-05 names five services; the list is compared with `service-namespaces` both ways | `clofin.ledger.purity-test` `ac-17-…` | 1 failure: C-05 names four, the code has five |
| **2B-007** | `resources/disclaimer.txt`; `make help` prints it; `check-disclaimer.sh` in `verify` | `clofin.tools.disclaimer-test` (10 tests) | The old help text fails the check — asserted as a fixture mutation |
| **2B-008** *(forward half)* | `docs/releases/README.md` states the rule and records `ref-1` as the one historical exemption, with its reason | `clofin.tools.disclaimer-test` `a-historical-tag-removed-…`, `…-with-no-reason-fails` | Remove the row and `ref-1` fails; the annotation file is untouched |
| **2B-009** | `protected-operations`; docstring and `400` name the six; every copy of the claim narrowed | `clofin.api.conformance-test` `ac-15-*` | The docstring says every mutation; six of seventeen require the key |
| **2B-010** | `$AUDITOR` seeded in UAT-006; both Step 11 reads use it | Run live, §5 | As `$CTRL` both calls answer `403` |
| **2B-011**, **2C-008** | `check-doc-consistency.sh` rule 5 | `clofin.tools.doc-consistency-test` `ac-20-*` (8 tests) | Against the RC's ROADMAP: 2 disagreements, at lines 227 and 254 |
| **2C-001** | `breaks-for-statement` and `adjustments-for-break` return `{… :truncated?}`; four wire fields, all `required`; C-13's evidence sentence narrowed | `clofin.recon.repository-test` `ac-5-*`, `clofin.api.reconciliation-api-test` `ac-5-truncation-*` | 501 breaks and 501 adjustments returned, no flag |
| **2C-003** | Spec converted to Clojure data once at load; non-vacuity asserted against an independent raw-YAML scan; a mutation control | `clofin.contract-test` `a-019-*` | The guard iterates **0** of 8 schemas; the mutation is green |
| **2C-004** | UAT-007 replaces the tenant's SGD bands; the prerequisite row lists everything inherited | Run live, §5 | `posted:false, approvalsRequired:1, status:proposed` where the script states 0/true/resolved |
| **2C-005** | One gate, `assert-provenance!`, on all four writers; one definition of a complete stamp | `clofin.tools.capture-test` `ac-3-*`, `every-writer-is-exercised`, `the-harness-writes-…-nowhere-else` | 56 failures: 28 for `write-quotations!`, 28 for `write-manifest!` |
| **2C-007** | A statement runs to the next bold label, heading or rule; a label is a bold run that *starts a paragraph* | `clofin.tools.capture-test` `ac-4-*` (3 tests) | C-13 is 74 characters; all seven guarantees missing |
| **2C-009** | Both settlement postings go through `clofin.ledger.service/post-entry!`; producer census | `clofin.ledger.purity-test` `ac-16-…`, `clofin.api.settlement-api-test` `ac-16-*` | 25 failures: two producers; entries with no event; packs answering `404` |
| **2C-010** | `simulated` in `batch->wire`; five `400`s and one `422` declared; readiness enum narrowed; bounded conformance test | `clofin.api.conformance-test` (7 tests / 161 assertions), `clofin.contract-test` `the-readiness-check-enum-…` | 7 failures — the five `400`s, the `422`, and `simulated` missing from every list item |
| **2C-011** | `GET /reconciliation-adjustments/{id}`, copying `getReconciliationBreak`'s boundary behaviour | `clofin.api.reconciliation-api-test` `ac-6-following-a-location-…` | The decision's `Location` answers `404` |
| **2C-012** *(consider)* | The evidence join traverses `adjustment_id` as well as `instruction_id` | `clofin.api.reconciliation-api-test` `ac-7-…` | The adjustment's pack carries `proposed` and `rejected` and no `approval.recorded` |
| **C-R10a**, **010-REQ N-3** | Three contract descriptions corrected, each now derived from the code by a test | `clofin.contract-test` (3 tests), `clofin.api.payments-api-test` `ac-12-…` | The `PATCH` probe answers `200` with a matching `organisationId`, which the prose called `422` |

---

## 4. Two results worth stating plainly

**2B-004's tests pass at the RC, and that is the finding.** The locks were
right; the adversarial proof **L-8** requires did not exist. Running the branch's
`clofin.recon.concurrency-test` against `c97a4f2` fails only the `ac-1-*` cells
— the 2C-002 defect — while both `ac-8-*` lock proofs pass. A remediation that
reported these as "now fixed" would be claiming a repair that never happened.

**2C-003's corrected test passes at the RC too**, for a different reason: the
contract at `c97a4f2` has no currency defect, so a working guard is green
against it. The finding is *false protection*, and the RC evidence is therefore
the guard admitting the mutation. The RC's own `a-019` body, run verbatim
against a spec in which `Money.currency` is the old three-letter pattern:

```
Money.currency is now: {"type" "string", "pattern" "^[A-Z]{3}$"}
schemas the guard iterated : 0
schemas that FAILED it     : 0
```

Eight schemas declare a `currency` property. `map?` admitted none of them,
because SnakeYAML returns `java.util.LinkedHashMap`. The named A-019 test passed
with one assertion — its separate top-level enum comparison — and skipped every
property check, exactly as the audit reported.

---

## 5. Run against a live stack

The brief requires UAT-006 steps 1–7 and UAT-007 to be run against a fresh
stack before this file is written (**L-20**). They were: a fresh database,
migrations `0001`→`0013`, the service started from this branch, every request
by `curl` through the published API.

**AC-19 — UAT-006 Step 11.** As `$AUDITOR`:

```
["payment.created","payment.submitted","approval.recorded","payment.approved",
 "payment.released","payment.settled"]
{"subjectType":"settlement-batch",
 "actions":["settlement-batch.created","settlement-batch.submitted"]}
```

The same two calls as `$CTRL`, the actor the script used to name:

```
  evidence/$SETTLES as $CTRL -> HTTP 403
  evidence/$BATCH   as $CTRL -> HTTP 403
  {"title":"Not permitted","detail":"This actor may not read"}
```

**AC-9 — UAT-007's de-minimis case.** First the finding, with UAT-006's
band-from-zero and UAT-007's step **as it was** (additive):

```
bands: 0 | 1
       100000 | 1
{"posted":false,"approvalsRequired":1,"status":"proposed","break":"open"}
  the script states: approvalsRequired 0, posted true, break resolved
```

Then with the corrected step, which deletes the tenant's SGD bands:

```
bands: 100000 | 1
{"posted":true,"approvalsRequired":0,"status":"posted","break":"resolved",
 "entryId":"85bb9570-ea12-491d-b533-3758984d750a"}
```

and step 9's inclusive boundary still holds — at exactly SGD 1,000.00:

```
{"posted":false,"approvalsRequired":1,"status":"proposed"}
```

**Four other findings, confirmed on the same stack:**

```
2C-011  Location: /reconciliation-adjustments/05ca44ff-…
        GET /reconciliation-adjustments/{id} -> HTTP 200
2C-001  {"state":"resolved","adjustmentsLimit":500,"adjustmentsTruncated":false,
         "adjustments":1}
2C-012  {"subjectType":"reconciliation-adjustment",
         "actions":["reconciliation-adjustment.proposed",
                    "reconciliation-adjustment.posted","approval.recorded"]}
2C-009  {"journalEntryPostedEvents":8}      journal entries: 8
```

Eight journal entries, eight posting events — settlement's release and finality
entries included, which had none.

**The UAT actor census (AC-19).** Every `x-actor-id` any UAT script sends to an
`/audit/` route, with the role it holds:

| File | Line | Actor | Role | Holds `audit/read`? | Expected |
|---|---|---|---|---|---|
| UAT-005 | 507 | `$RAE` | `auditor` | yes | `200` |
| UAT-005 | 542 | `$PRIYA` | `operator` | **no** | `403` — **deliberate**; the step exists to show an operator cannot read the trail |
| UAT-006 | 499 | `$AUDITOR` *(was `$CTRL`)* | `auditor` | yes | `200` |
| UAT-006 | 513 | `$AUDITOR` *(was `$CTRL`)* | `auditor` | yes | `200` |
| UAT-007 | 699 | `$AUDITOR` | `auditor` | yes | `200` |
| UAT-007 | 748 | `$AUDITOR` | `auditor` | yes | `200` |
| UAT-007 | 769 | `$AUDITOR` | `auditor` | yes | `200` |

Seven calls. UAT-006's two were the only defects; UAT-005's `403` is the point
of its step.

---

## 6. The enumerations the brief asks for

### 6a. Every predicate over the parsed spec in `clofin.contract-test` (2C-003)

The namespace applies exactly **one** shape predicate to SnakeYAML output, and
it was vacuous. Everything else reaches the parsed data through `get`, `get-in`,
`keys` or `set`, all of which work on `java.util.Map` and `java.util.List`.

| Predicate | Site at the RC | Applied to | Vacuous at the RC? |
|---|---|---|---|
| `map?` | `contract_test.clj:238` — `:when (map? currency)` | 8 schemas' `properties.currency`, each a `java.util.LinkedHashMap` | **Yes.** Population 0 of 8 |
| `contains?` | `:35`, `:112`, `:115`, `:116`, `:341`, `:344`, `:346`, `:353`, `:354`, `:363`, `:365` | Clojure sets built by `set`/`keys` from parsed data | No |
| `set` / `keys` / `vals` | `:52`, `:90`, `:91`, `:95`, `:196`, `:202`, `:217`, `:225`, `:263`, `:265`, `:341` | `java.util.Map` and `java.util.List` — both `seq`-able | No |
| `seq` | `:201` (`(is (seq by-schema))`) | a Clojure map built by `into {}` | No |
| `when-let` on `get-in` | `:186` (subject-type discovery) | parsed data; truthiness, not shape | No |
| `every?` / `count` / `remove` | `:102`, `:333` | Clojure collections | No |
| `for` / `doseq` over parsed maps | `:33`, `:184`, `:237`, `:327` | `java.util.Map`, `seq`-able | No |

Verified by running the RC's own loader and printing the classes
(`rc/vacuity_probe.clj`): root `java.util.LinkedHashMap`, `(map? spec)` false, a
`required` list `java.util.ArrayList`, `(vector? required)` false, and the
currency population `0`.

The fix converts once at load, so no predicate in the namespace has to remember.

### 6b. Every copy of the "every mutating" sentence (2B-009)

`grep -rn "every mutating\|all mutating\|each mutating\|every mutation\|mutating endpoint\|mutating operation\|mutating request"` over the whole tree. The
control plane (`docs/briefs/`, `docs/audits/`) and historical audit records are
excluded from action by the brief and are listed for completeness.

| Copy | Disposition |
|---|---|
| `src/clofin/idempotency.clj:36` — read-key's docstring | **Narrowed.** Names the six, states that the other eleven do not read the header |
| `src/clofin/idempotency.clj` — the `400` message | **Narrowed.** Names the six operations and says "and on no other operation" |
| `src/clofin/api/payments.clj:15` | **Narrowed** to "every mutating operation **in this namespace**", with the count and the pointer to C-06 |
| `src/clofin/payments/repository.clj:19` | **Narrowed** to "every mutating call **in this namespace**" |
| `docs/uat/UAT-005-segregation-of-duties.md:53` | **Narrowed** to "every payment and approval mutation", naming what guards the rest |
| `docs/DOMAIN_MODEL.md:121` | **Narrowed.** *Not named by the brief* — found by this sweep. Said "the key protects *every* mutating operation"; now says every payment and approval one, with the six named and the eleven pointed at C-06 |
| `test/clofin/api/payments_api_test.clj:578` — a `testing` label | **Narrowed.** *Not named by the brief.* A test label restating the false claim, now scoped to its namespace and pointing at the seventeen-route sweep |
| `ARCHITECTURE.md:25` | **Checked, left.** Says "every mutating **payment** operation", which is true: the six are exactly the payment and approval mutations |
| `api/openapi.yaml:2492` | **Checked, left.** Already says PR-040 "asks for it on every mutating operation and that is not yet true" |
| `src/clofin/api/approvals.clj:27` | **Checked, left.** "Both mutating operations", already narrow |
| `docs/ADR/0013:207` | **Checked, left.** "Any new mutating endpoint should be checked against that" — guidance, not a claim |
| `docs/PRD.md:56`, `:122` | **Left, deliberately.** PR-040 is a *requirement*, not a claim about what is built. Editing it to match the implementation is the L-14 failure with the sign reversed; the shortfall is recorded in COMPLIANCE §4 |
| `docs/COMPLIANCE.md:524` | **Left.** Already narrows, at length |
| `docs/briefs/**`, `docs/audits/**` | **Not mine.** Control plane and historical records |

---

## 7. Debt this batch knowingly leaves

- **Pagination for the nested reconciliation collections.** Deferred since
  increment 2 and still deferred, for the same reason: a cursor contract
  designed without a consumer is guesswork. 2C-001 was that the bound was
  *invisible*; it no longer is, and the debt is now recorded in COMPLIANCE §4
  in its own row.
- **A-011 is narrowed, not closed.** `clofin.api.conformance-test` checks three
  dimensions of every operation's response. It does not validate request bodies,
  types, formats or `additionalProperties`. §4's row still says so, and a test
  in the namespace asserts that it does.
- **The `ref-1` release body is not rewritten** (2B-008). A mirror rewritten to
  please a checker is a fiction. It is recorded as the one historical exemption,
  with its reason, and the check enforces the rule from `ref-2` onward.
- **`ref-2`'s own annotation** is Master Control's to write at tag time. The
  check passes with no such file present, and a test asserts that it does.
- **The audit's own items 5–7** are not re-run here. Reproducing them would be
  a Worker grading its own remediation.

---

## 8. Verification status at completion (L-9)

**Nothing is in flight.** `make verify` and `make test-it` were both run to
completion on the final tree, green, with the counts in the header. The
migration replay from empty was run after the last commit. The live-stack run in
§5 was completed and its stack torn down. Every RC comparison quoted above was
run before the corresponding fix.

No review, test run or adversarial pass of mine is still executing, and I have
no pending fix I expect to push after this report.

**One qualification on how the suites were run, stated because the numbers are
the claim.** This environment has no Docker daemon, so `make test-it`'s
`db-up` step — which starts PostgreSQL through Compose — cannot run. PostgreSQL
16.13 was started directly instead and the target's remaining two steps were run
as they stand: `clojure -M -m clofin.db.migrate` then `clojure -M:test:it`. The
test selection, the database and the assertions are the target's; only the way
the server was started differs. `make verify` needs no database and was run
whole.

---

## 9. Objections

Four. None resolved unilaterally; each is implemented in the way described and
flagged here for Master Control's ruling.

### O-1 — the brief's account of what the captured service reports is wrong, and the truth is worse

The brief says, of the capture harness: *"pass `CLOFIN_SOURCE_COMMIT` in
`env-for` too, from the worktree's resolved commit — today the child resolves
nothing and reports `unknown`"*.

Both halves are false at the RC, measured:

- With nothing stamped, the child **does** resolve the commit. It runs with the
  worktree as its working directory, `clofin.build-info` handles the
  linked-worktree shape explicitly — its own docstring names
  `make capture-trace` as the case — and it returns
  `c97a4f25a4cb2d9210613939cb55c3dafcddf32f`.
- Under `make capture-trace` nothing is unstamped. That Makefile computes
  `CLOFIN_SOURCE_COMMIT` from `git rev-parse HEAD` and exports it; a child
  inherits its parent's environment; `env-for` sets seven variables and not that
  one. So the tagged commit's service inherits the **harness's** `HEAD` and
  reports it — `main`'s SHA for a stack running from a tag. Confirmed by
  probe, and it is precisely the value `clofin.tools.capture.stack`'s own
  docstring says must never be published: *"it names the harness's checkout, and
  the whole point is that the harness runs from `main` while the stack runs from
  a tag, so that value would be confidently wrong."*

**The instruction was carried out as written** — `CLOFIN_SOURCE_COMMIT` is set
in `env-for` from the commit under capture, and `assert-same-process!` refuses
anything else. The objection is to the brief's *reason*, which matters because
it understates the finding: this was not a missing value but a confidently wrong
one, and a reader of the brief would look for the wrong thing. AC-2(d) —
"a responder that echoes the right `instanceId` but a different `sourceCommit`
is refused" — is the test that closes it, and its docstring records the real
mechanism.

### O-2 — the brief specifies an uninsertable row for UAT-007 (**L-3**, applied to seed data)

C-5 says: *"delete the tenant's SGD bands, insert `(0, 0)` and `(100000, 1)`"*.

`(0, 0)` cannot be inserted. `approval_threshold` has carried
`threshold_approvals_positive check (approvals_required >= 1)` since migration
`0005`. Run against a live PostgreSQL 16:

```
ERROR:  new row for relation "approval_threshold" violates check constraint
        "threshold_approvals_positive"
DETAIL:  Failing row contains (df35a811-…, SGD, 0, 0).
```

It is also unnecessary. Zero approvals is not a band: it is what
`clofin.recon.adjustment/approvals-required` answers for an amount **below** the
lowest band the organisation configured — the function's own docstring says
*"Nil is a refusal, not a zero… Zero is a real answer and means the proposer
alone may post"*. One band at `(100000, 1)` therefore gives both halves the
script needs: a configured currency, so adjustments are possible at all, and a
de-minimis region under SGD 1,000.00.

**Implemented as one band**, with the reasoning written into the step, and
verified live: the de-minimis case posts with `approvalsRequired: 0`, and step
9's inclusive boundary at exactly SGD 1,000.00 still requires an approval. This
is standing lesson **L-3** in a place the lesson does not currently reach — it
is about migrations, and this is a brief specifying seed data the engine
refuses. Worth widening the lesson.

### O-3 — C-5 was committed with group F rather than group C

The brief puts C-5 (UAT-007) in group C and F-4 (UAT-006) in group F. The two
are one change: UAT-007's prerequisite row lists the actor aliases it inherits,
including the `$AUDITOR` that F-4 seeds. Committing them apart leaves a forward
reference in the intermediate commit — group C naming an actor that group F has
not yet created.

Both UAT edits are therefore in group F's commit, whose message names 2C-004
alongside 2B-010. The brief's stated purpose for committing by group is *"so a
reviewer can read one finding at a time"*, and that is better served by keeping
the coupled pair together than by splitting it. Recorded rather than assumed.

### O-4 — the brief's operation count is 37; the route table now has 38

AC-13 says *"the conformance namespace exercises all 37 operations"*. That was
right at the RC. C-2 adds `getReconciliationAdjustment`, so the branch has 38,
and the conformance test asserts coverage against `clofin.routes/routes` rather
than against a number — which is what keeps it right when the next operation
lands. Noted because a reader checking the AC against the output would find 38
and wonder which is wrong.

---

## 10. Files touched

**Source.** `recon/service.clj` (2C-002), `recon/repository.clj` (2C-001),
`api/reconciliation.clj` (2C-001, 2C-011), `audit/repository.clj` (2C-012),
`settlement/service.clj` (2C-009), `api/settlement.clj` (2C-010),
`http/cors.clj` (2B-003), `idempotency.clj` (2B-009), `api/payments.clj` and
`payments/repository.clj` (2B-009 prose), `config.clj` and `api/health.clj`
(2C-006, 2B-007), `routes.clj` (2C-011).

**Harness.** `tools/…/capture/stack.clj` and `capture.clj` (2C-006),
`capture/bundle.clj` (2C-005), `capture/quotations.clj` (2C-007).

**Contract.** `api/openapi.yaml` — `instanceId`, the four truncation fields, the
new operation, five `400`s, one `422`, the readiness enum, and three
descriptions.

**Scripts and build.** `scripts/check-disclaimer.sh` (new),
`scripts/check-doc-consistency.sh` and `.awk` (rule 5), `Makefile`
(`disclaimer-check` in `verify`, `help` from the resource),
`resources/disclaimer.txt` (new).

**Tests.** New: `clofin.recon.concurrency-test`,
`clofin.tools.capture-stack-test`, `clofin.api.conformance-test`,
`clofin.tools.disclaimer-test`. Extended: `contract-test`, `cors-test`,
`capture-test`, `doc-consistency-test`, `purity-test`, `health-test`,
`config-test`, `recon/repository-test`, `api/reconciliation-api-test`,
`api/settlement-api-test`, `api/payments-api-test`. New fixtures:
`test-resources/disclaimer/base`.

**Documentation.** `COMPLIANCE.md` (C-05, C-13 twice, one §4 row),
`DOMAIN_MODEL.md` (2B-009),
`ADR-0022` and `ADR-0027` (dated amendments), `docs/releases/README.md`,
`docs/uat/UAT-005`, `UAT-006`, `UAT-007`.

**Untouched.** Every control-plane file: `docs/ROADMAP.md`, `docs/briefs/`,
`docs/audits/README.md`, `docs/AGENT_HANDOFF.md`,
`docs/releases/ref-1.annotation.txt`. Verified by
`git diff --stat origin/main -- …`, which is empty.
