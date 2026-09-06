# FEEDBACK-REL-ref-2 — release audit for `ref-2` (**COMPLETE**, 8/8)

> **Ingestion note — Master Control, 2026-09-06.** This is the auditor's
> deliverable as ferried from the bridge inbox, ingested to `meta` under the
> protocol in [`README.md`](README.md). Severities are **the auditor's
> throughout** (the `ref-1` deliverable's were assigned by Master Control; this
> one needed none). Both blocking findings were **independently confirmed in
> source by Master Control before ingestion**: 2C-006 — `start!` in
> `tools/clofin/tools/capture/stack.clj` accepts a `200` on `/readyz` before
> asking whether the spawned child is alive, and the schema check compares
> versions, not identity; 2C-002 — both receipt-collision branches in
> `clofin.recon.service` replay the winner without the digest comparison the
> serial path makes. Dispositions for all 23 findings and the residual are in
> the register's decision block of 2026-09-06; remediation is
> [TASK-015](../briefs/015-TASK-ref-2-release-remediation.md). The RC
> `c97a4f2` is **not taggable**; `ref-2` lands on the remediation descendant.
>
> *Text unchanged except this note and six link targets:* the workpapers and
> citation evidence the report links (`A-…`, `B-…`, `C-…`,
> `D-citation-evidence.md`, `D-citations.json`, `D-citation-results.json`)
> are **workpapers, kept in the bridge** at
> `clofin-core/audit/workpapers/ref-2/`, not repository artifacts (storage
> rule: official artifacts in git, workpapers in the bridge); their links are
> rendered as file names so no link in this tree dangles. Session D's prompt
> is recorded in [`REL-ref-2-SESSION-D-PROMPT.md`](REL-ref-2-SESSION-D-PROMPT.md).

## 1. Provenance and Coverage

| Session | Model Recorded | Reasoning Effort | Date | Work |
|---|---|---|---|---|
| A | GPT-5.6 Sol | not recorded | 2026-09-05 | Environment, migrations, full suites |
| B | GPT-5.6 Sol | not recorded | 2026-09-05 | Standing lessons, debt, neutrality, four declared inputs |
| C | GPT-5.6 Sol | not recorded | 2026-09-06 | Delta-first consistency and whole-set checks |
| D | GitHub Copilot; underlying model not exposed | not recorded | 2026-09-06 | Citation verification, one residual probe, consolidated report |

Sessions A-C recorded GPT-5.6 Sol. D cannot determine whether its underlying model differs; it does not adopt the earlier model label as an observed configuration fact.

- Commissioned by Master Control on 2026-09-05 under the standing release-audit charter.
- RC: `c97a4f25a4cb2d9210613939cb55c3dafcddf32f`.
- Control plane: `d9a72e027b0b5df2bd3b66cc0fb5de4fa46c5ab9`, from A and checked against `origin/meta` in D.
- Subject: clofin-core at that RC, including its capture harness and committed release artifacts. No separate trace or cockpit implementation was audited or used as truth.

**Coverage is the first audit result.** Performed means the audit work was performed, not that every control passed. This is a synthetic-only internal quality gate, not an attestation. CloFin never handles real funds, never connects to any bank, payment scheme or central bank, and holds no regulatory approval. Every observation, reproduction and disposition below is bounded by that scope.

| Charter Item | Coverage | Session and Reason |
|---|---|---|
| 1. Migrations from empty | performed | A: thirteen migrations applied twice from recreated databases. |
| 2. Full suite | performed | A: make verify and make test-it completed with recorded counts. |
| 3. Cross-document consistency | performed | C: delta first, then ref-1 regression checks; findings and explicit execution limits retained. |
| 4. Partial-set sweep | performed | C: discovered SQL, code, contract, route, audit, capture and document sets compared. |
| 5. Standing-lessons compliance | performed | B: L-1 through L-16 assessed against their named guards. |
| 6. Known-debt reconciliation | performed | B: gap/debt registers, REQ records, ref-1 deferrals and reverse caveat sweep. |
| 7. Synthetic-data and neutrality sweep | performed | B: contextual whole-tracked-tree sweep and canonical disclaimer comparisons. |
| 8. Citation discipline | performed | D: 230 manifest entries dispositioned; 226 RC/control-plane citations verified, four historical external diagnostics excluded. No subject finding discarded. |

## 2. Scope Execution Record

The workpapers are the execution record: `A-environment-and-suites.md`, `B-lessons-debt-neutrality.md`, and `C-consistency-and-sets.md`. D carried their evidence forward without rerunning their suites or probe scripts. Source paths below are relative to the clofin-core repository root. Citations labelled origin/meta were printed from the literal pinned control-plane SHA, not from a checked-out meta branch.

| Item | What Was Done and How | Recorded Result |
|---|---|---|
| 1 | A replayed indexed migrations on PostgreSQL 16 from empty, then recreated the database and repeated. | 13 migrations, 0001-0013, twice; schema 0013; 0 pending. Index/file sets matched 13/13 in both directions. |
| 2 | A ran make verify and make test-it at the RC using the declared Compose toolchain. | Verify: 482 tests, 2,935 assertions, 0 failures/errors, 16.91 s; links: 93 files; diagrams: 7 artifacts; consistency: 13 controls, 30 status claims, 14 briefs. Integration: 885 tests, 6,483 assertions, 0 failures/errors, 118.30 s. |
| 3 | C traced C-01-C-13, I1-I12 and the four matching rules to code, SQL and tests; ran the contract test and public evidence/refusal probes; compared ROADMAP with COMPLIANCE and all meta brief statuses. Delta surfaces were checked first, followed by ref-1 checks 3.1-3.17. | Contract: 16 tests/258 assertions. Reconciliation/retry public paths: 49 tests/1,108 assertions. CORS/build-info: 37/127. Capture tests: 14/80. All passed, while adversarial probes exposed the findings below. All 14 meta task briefs were CLOSED; selected status restatements still contradicted them. |
| 4 | C discovered the sets independently: live catalogue, source forms, recursive OpenAPI schemas, route table, capture writers, generated artifacts and full document restatements. Reperformed ref-1 checks 4.1-4.9. | 49 SQL CHECKs, 25 closed array vocabularies, no native enums; all 25 matched code. All 58 OpenAPI enum occurrences classified, 57 exact and one unused readiness alternative. Eight history tables, 16 append-only triggers, 23 forbidden table/verb pairs plus permitted approval UPDATE; retry-link trigger separately checked. 28 audit actions, 27 producers and one disclosed reserved action; nine subjects; five roles/17 permissions; 37 operations, 17 mutations, six caller-key mutations; 15 purpose codes; 21 currency scales. |
| 5 | B assessed L-1-L-16 against the actual named guards, including post-ref-1 code and meta authoring records. | 16 lessons assessed. The quantified-language census recorded 934 hits across 31 files. B-001-B-005 record guard regressions or incomplete claims. |
| 6 | B reconciled COMPLIANCE gaps, ROADMAP carried-forward/deferred debt, REQ 006-014, the four ref-1 deferred mechanisms and reverse code caveats. | 13 COMPLIANCE gaps and 17 ROADMAP debt entries recorded; three ROADMAP entries stale (B-006). Literal caveat census: one TODO, no FIXME or XXX. Deferred log sanitisation, catalogue hashing, transitive SBOM and deep contract validation remain open. |
| 7 | B swept the entire tracked tree contextually and compared the runtime disclaimer with named restatement surfaces. | No current claim of real-funds handling, institutional connectivity, regulatory approval or external attestation found. Runtime disclaimer exact; help and release-mirror wording omit one required negation each (B-007/B-008). |
| 8 | D printed every inventoried citation with sed at the RC, or git show of the pinned meta SHA followed by sed; compared the quotation, supplied C's missing line pins and appended dispositions after each group. | 230 entries: 127 matches, 99 corrected with the finding/assessment retained, four historical external probe diagnostics discarded from RC citation evidence; none pending. No numbered subject finding discarded. The one permitted residual PATCH probe returned 200 with organisationId supplied. |

C's additional focused regression results were 118 tests/547 assertions for approval, ledger and destructive-verb checks; 71/628 for audit atomicity and money/entry properties; six evidence/lock checks with 30 assertions; 49/455 for configuration, errors, middleware and the complete payment state/event walk; and 21 public regressions with 383 assertions. All recorded zero failures/errors. The payment walk covers all 81 pairs: 11 permitted and 70 refused. These are separate, partly overlapping focused runs, not an additional deduplicated full-suite total.

Execution limits are explicit. C did not run a full capture-trace invocation because it would create prohibited git worktree metadata; it reviewed all capture modules and used isolated writer, quotation and responder-identity probes instead. C's diagram run excluded the repository-writing orphan test, already included in A's suite; its 23 read-only tests/214 assertions and seven-artifact byte comparison passed. The release mirror matched the published body; the optional no-jq fallback was not executed. UAT-007 was traced and selectively reproduced, not represented as a complete manual shell transcript. Deep general OpenAPI/handler validation remains named debt. None of these limits permits an external assurance or production-readiness claim.

The exact printed source is retained in `D-citation-evidence.md`, with the immutable intake inventory and per-citation results in `D-citations.json` and `D-citation-results.json`. Citation corrections include restored Markdown/comment prefixes, truncated sentence continuations, a systematic one-line offset in B's COMPLIANCE gap table, and three mislocated OpenAPI support quotations. The original workpapers retain their history; C's appended D tables record the corrections.

## 3. Findings

A recorded no findings. The 23 numbered findings below preserve the originating session's identifiers and severities: **two blocking, twenty should-fix, one consider**. C-R10a is retained separately as an unnumbered residual, with no severity retroactively attributed to C. All concern the synthetic-only internal quality gate.

### 2C-002 - blocking - Contradictory Concurrent Statements Are Reported as Replays

The two receipt-insert collision branches replay the winning receipt without comparing its digest with the incoming document:

**Source: src/clofin/recon/service.clj:270-275**

```text
                          ;; Lost the race to a concurrent identical delivery.
                          ;; The winner committed before this insert could take
                          ;; the key, so its receipt is visible now and is the
                          ;; answer both callers get.
                          (replay tx (replay-of tx organisation-id
                                                (:statement-reference statement))))))]
```
**Source: src/clofin/recon/service.clj:307-309**

```text
                (if-not stored
                  (replay tx (replay-of tx organisation-id
                                        (:statement-reference numbered)))
```
**Source: docs/ADR/0023-a-clofin-defined-synthetic-statement-format-and-an-ordered-matching-sequence.md:58-59**

```text
reproduces the stored answer and does no work; a *different* document under the
same reference is refused and is never called a replay.
```

**Reproduction.** C's external reconciliation race probe synchronized two actual public-handler transactions after both initial receipt lookups returned nil. Same tenant/reference, different SIM-RTGS/SIM-ACH documents and explicitly different digests: the applied path returned 200/200 and the refused path 422/422, with one falsely marked replayed in each. The same contradiction sent serially returned 409. Only timing was controlled; insertion, savepoint rollback, commit and rendering were real.

**Why it matters.** The synthetic receipt contract changes with scheduling: a document never processed can be acknowledged as an exact replay of another document, hiding contradictory content. This is not a claim that two effects committed.

**Suggested direction.** Use one digest-aware decision for both pre-existing and collision-winner receipts. Add a two-connection matrix covering identical/different content and applied/refused stored outcomes.

**Affects.** C-13 receipt integrity; ADR-0023; L-8/L-11/L-12; synthetic reconciliation clients.

### 2C-006 - blocking - Capture Can Attribute Another Process's Response to the Requested Commit

Startup accepts HTTP success before checking the spawned child, and the later comparison establishes only schema equality:

**Source: tools/clofin/tools/capture/stack.clj:192-198**

```text
      (let [res (http-get (str base-url "/readyz"))]
        (cond
          (and res (= 200 (:status res)))
          {:process p :base-url base-url :readyz (:body res)}

          (not (.isAlive p))
          (throw (ex-info (format "capture refuses: the stack from %s exited before becoming ready. See %s"
```
**Source: tools/clofin/tools/capture/stack.clj:260-262**

```text
  (let [reported (second (re-find #"\"schemaVersion\"\s*:\s*\"([^\"]+)\"" (str readyz-body)))
        expected (migration-head worktree)]
    (when-not (= reported expected)
```

**Reproduction.** C started an independent local synthetic HTTP responder reporting schema 0013 and asked the unmodified capture startup function to spawn /bin/false on that port. The child exited 1 and was not alive, yet startup accepted the responder and schema validation accepted 0013. No alternate CloFin repository or external institutional service was used.

**Why it matters.** A same-schema process already on the port can supply behavior stamped as the requested commit even when the intended child never started successfully. This defeats the in-core provenance boundary on which replay consumers depend. It does not establish that any previously published bundle was misattributed.

**Suggested direction.** Refuse or reserve occupied ports, establish a per-run identity bound to the spawned process, and verify its liveness throughout capture. Test an occupied port with the same schema version; schema version is not source identity.

**Affects.** Capture provenance; ADR-0020/0022; internal release evidence attribution.

### 2B-001 - should-fix - Migration-Bearing Briefs Omit the L-3 Authoring Gate

The applicable brief DoDs contain generic suite and numbering checks, not the required pre-dispatch PostgreSQL/row-shape exercise:

**Source: origin/meta:docs/briefs/008-TASK-reconciliation.md:158-160**

```text
- [ ] Every acceptance criterion has a named test; AC-5's boundary value tested at the boundary
- [ ] `make verify` and the integration suite green
- [ ] Migrations numbered next-available against the live tree at build time (L-1)
```
**Source: origin/meta:docs/briefs/010-TASK-reconciliation-completion.md:92-94**

```text
- [ ] Every acceptance criterion has a named test; AC-5's enumeration is in the REQ with the grep output
- [ ] `make verify` and the integration suite green
- [ ] Migration numbered next-available against the live tree at build time (L-1)
```

**Reproduction.** B reviewed all post-adoption briefs 006-014 and their changelogs. Only 008 and 010 specify migrations; neither requires executing the specified DDL on PostgreSQL 16 and inserting every documented row shape before dispatch. D corrected the previously conflated DoD quotations.

**Why it matters.** Passing implementation tests does not demonstrate that the earlier authoring gate was honoured; a schema proposal can still promise an uninsertable shape.

**Suggested direction.** Make live-engine execution and each documented row shape a mandatory dispatch checklist with recorded evidence or an explicit exception.

**Affects.** Master Control brief authoring and future synthetic schema increments; L-3.

### 2B-002 - should-fix - Repeated Brief Contradictions Escape the Pre-Dispatch Check

The pinned control-plane ruling records a confirmed contradiction:

**Source: origin/meta:docs/briefs/012-TASK-cockpit-connect-and-bootstrap.md:151**

```text
| O-1 | The brief's bootstrap sequence names four creations; two of them — actors/roles and approver limits/thresholds — have **no API on purpose** (UAT-005 §2: an actor able to grant itself the approver role makes segregation of duties unenforceable). A literal runner reaches step two of six and stops forever. Adding endpoints was doubly out of scope, and rightly. | **Confirmed — brief defect, Master Control's, with a sharp edge:** the same brief that ordered "discover the header set, do not guess" specified a bootstrap sequence without checking which steps have an API. The Worker's design is **ratified as the standing pattern for every future manual step**: a `manual` step generates exact SQL, is confirmed only through a real API request whose response is shown — never by a button — and must carry a *what this cannot show* list, enforced by test. A green tick never stands for something nobody checked. The suggested brief correction is adopted as this changelog. |
```

**Reproduction.** B found confirmed scope, acceptance-criterion or interface contradictions across six post-adoption briefs: 006, 007, 008, 010, 011 and 012. Workers raised the contradictions; generated diagram/table checks did not cover the prose/interface mismatch class.

**Why it matters.** An unreachable criterion or unavailable API can invite silent scope invention. Successful downstream objections do not establish that L-4's authoring check happened before dispatch.

**Suggested direction.** Require a dispatch matrix connecting each scope item, criterion and vocabulary term to its interface, transition, DDL and DoD; unresolved cells need a ruling before dispatch.

**Affects.** Synthetic increment authoring and L-4. The quoted core control-plane record is in scope; the separate cockpit implementation is not audited.

### 2B-003 - should-fix - CORS Hides the Published Replay Header

**Source: src/clofin/http/cors.clj:94**

```text
  ["location" "x-correlation-id" "allow"])
```
**Source: test/clofin/http/cors_test.clj:305-310**

```text
(deftest exposed-headers-are-the-ones-a-browser-could-not-otherwise-read
  (testing "every response header CloFin sets that is not CORS-safelisted is exposed"
    ;; `content-type` is CORS-safelisted and readable without being named here;
    ;; the other three are not, and each is one a client displaying raw
    ;; responses would otherwise silently lose.
    (is (= #{"location" "x-correlation-id" "allow"} (set cors/exposed-response-headers)))
```
**Source: api/openapi.yaml:744-745**

```text
            Idempotent-Replayed:
              $ref: "#/components/headers/IdempotentReplayed"
```

**Reproduction.** B and C enumerated contract and code response headers. Idempotent-Replayed is declared on six mutating responses and emitted by payment/approval code, but is missing from exposed-response-headers. The test repeats the same three-name list.

**Why it matters.** A browser page cannot read the header distinguishing an idempotent replay from newly performed synthetic work, while the purported complete-set test stays green.

**Suggested direction.** Expose idempotent-replayed and discover non-safelisted response headers from the contract/producers for a bidirectional comparison.

**Affects.** Browser replay evidence, OpenAPI/CORS consistency and L-6. Pre-declared input 2 is confirmed, not counted twice.

### 2B-004 - should-fix - Reconciliation Locking Lacks the Required Concurrency Proof

**Source: src/clofin/recon/repository.clj:41-43**

```text
  adjustment does. Standing lesson **L-8**: a validation that gates a write
  locks what it validated, so a break's state and an adjustment's status are
  read `for update` by the transaction that changes them."
```
**Source: src/clofin/recon/service.clj:339-345**

```text
  The break is read `for update` first: a state decided against a value that
  changed underneath it is validate-then-write, and that is a race (standing
  lesson **L-8**)."
  [tx {:keys [organisation-id break-id assignee-id actor correlation-id]}]
  (audit-store/assert-unit-of-work! tx)
  (let [before   (recon/lock-break! tx organisation-id break-id)
        _        (break-state/assert-assignable! (:state before))
```

**Reproduction.** B enumerated reconciliation lock helpers and tests and found no latch/barrier-based two-session proof for assignment, proposal, approval/rejection, posting or resolution. The older ledger path has such a harness. C's external probes are audit evidence, not committed regression tests.

**Why it matters.** Ordinary functional tests can stay green after a lock is removed or transaction lifetime changes. The recorded locking design is not the mandatory adversarial proof L-8 calls for.

**Suggested direction.** Add two-connection tests for assignment versus resolution and concurrent decisions on one adjustment, asserting serialization and final lifecycle/audit state. Also cover the concrete receipt race in 2C-002.

**Affects.** Synthetic reconciliation concurrency assurance and L-8. This test-gap finding is distinct from C's reproduced race defect.

### 2B-005 - should-fix - C-05's Service Enumeration Omits Reconciliation

**Source: docs/COMPLIANCE.md:294-297**

```text
Every service that composes a change with its event likewise takes the caller's
transaction and requires no `clofin.db.*` namespace at all —
`clofin.payments.approval-service`, `clofin.ledger.service`,
`clofin.organisations.service` and `clofin.settlement.service`. A service that
```
**Source: docs/COMPLIANCE.md:870**

```text
| `clofin.audit.repository/assert-unit-of-work!` in `clofin.recon.service` | Every reconciliation write and its audit event commit together or not at all |
```

**Reproduction.** Compare C-05's four-service list with C-13 and the existing audit-composition matrix: clofin.recon.service is the fifth service and has ingestion, assignment, proposal and decision cases. The status-document guard does not compare this list with code.

**Why it matters.** The document understates a built control-bearing service and contradicts its own later reconciliation section by omission.

**Suggested direction.** Update the enumeration and derive or compare it against the independently maintained service inventory.

**Affects.** C-05 documentation; L-14/L-15/L-16; internal synthetic reconciliation assurance.

### 2B-006 - should-fix - ROADMAP Carries Three Closed or Narrowed Debts as Open

**Source: docs/ROADMAP.md:158-159**

```text
- **No indexes on `payment_instruction`** — the measure-before-optimising posture,
  but a real gap at volume.
```
**Source: docs/ROADMAP.md:160**

```text
- `transactionally` exists in two namespaces; a two-line delegation closes it.
```
**Source: docs/ROADMAP.md:189-191**

```text
- **Ledger and organisation writes emit no audit events** — C-05's scope
  paragraph names the gap; **briefed as
  [TASK-005](briefs/005-TASK-audit-coverage-completion.md)**, dispatched 2026-08-04.
```

**Reproduction.** B traced the RC's retry-link index in migration 0013, the sole transactionally definition and explicit removal of its duplicate, and successful organisation/account/journal API audit events. The three bullets do not record those deliveries. D corrected the transactionally citation from line 159 to 160.

**Why it matters.** Internal planning can repeat already-delivered work or misunderstand the actual remaining query/index and audit boundaries.

**Suggested direction.** Close the duplicate-helper and successful API-write audit debts with their delivery references; narrow the index debt to the still-missing workload-specific indexes.

**Affects.** ROADMAP debt accuracy and synthetic release planning. This does not negate C's distinct settlement journal-subject gap in 2C-009.

### 2B-007 - should-fix - Help Omits the Explicit No-Real-Funds Clause

**Source: Makefile:71-72**

```text
	@echo "CloFin uses synthetic data only. It is not connected to any bank,"
	@echo "payment scheme or central bank, and holds no regulatory approval."
```

**Reproduction.** B compared make help with the canonical GET / disclaimer. The help text states synthetic data, no institutional connection and no regulatory approval, but not the explicit never-processes-real-funds clause.

**Why it matters.** This named operator-facing restatement is weaker than the charter's four-part scope boundary.

**Suggested direction.** Use the canonical sentence verbatim and compare maintained restatement surfaces mechanically.

**Affects.** Internal operator scope wording, not evidence of real-funds activity.

### 2B-008 - should-fix - Release Mirror Omits the Regulatory-Approval Negation

**Source: docs/releases/ref-1.annotation.txt:7-10**

```text
runs on SYNTHETIC DATA ONLY. It is not a production deployment, not an
attestation, not a claim of real institutional connectivity, and nothing here
has handled real funds or connected to any bank, payment scheme or central
bank. The release audit is an internal quality gate.
```

**Reproduction.** B compared the mirror's scope paragraph with the canonical disclaimer. It denies production deployment, attestation, connectivity and real-funds handling, but does not state that CloFin holds no regulatory approval. C separately confirmed that the mirror matches the published release body.

**Why it matters.** Stronger language on other axes does not supply the omitted regulatory-authorisation boundary on a named release surface.

**Suggested direction.** Make future release annotations include the canonical sentence. Disposition the historical wording through the authorized release-governance process; do not silently change a byte-for-byte mirror independently of its source.

**Affects.** Synthetic release-scope wording and internal publication controls.

### 2B-009 - should-fix - Shared Idempotency Documentation Overstates Coverage

**Source: src/clofin/idempotency.clj:36-38**

```text
  The header is **mandatory** on every mutating endpoint (PR-040): a request
  that omits it is `400` rather than being quietly executed, because a caller
  that has not thought about retries is exactly the caller a retry will hurt.
```

**Reproduction.** Independent route and contract inventories contain 17 mutations. Only six payment/approval operations require the key; eleven do not. The helper's universal wording therefore exceeds its callers' coverage.

**Why it matters.** A maintainer can infer fail-closed retry protection for writes that offer no such caller-key contract.

**Suggested direction.** Narrow the helper and error wording to its protected operations, or design and implement the broader behavior explicitly before retaining the universal claim.

**Affects.** Synthetic API idempotency contract accuracy. Pre-declared input 1 is confirmed.

### 2B-010 - should-fix - UAT-006 Uses an Actor Who Cannot Read Its Evidence

**Source: docs/uat/UAT-006-settlement-simulation.md:489**

```text
curl -sS "$BASE/audit/evidence/$SETTLES?organisationId=$ORG" -H "x-actor-id: $CTRL" \
```
**Source: docs/uat/UAT-006-settlement-simulation.md:503**

```text
curl -sS "$BASE/audit/evidence/$BATCH?organisationId=$ORG" -H "x-actor-id: $CTRL" \
```
**Source: src/clofin/authz/model.clj:164-175**

```text
   :controller #{:account/create :account/read :entry/post :entry/read
                 :payment/read :payment/cancel :approval/read
                 :settlement/execute :organisation/read
                 ;; The operational reconciliation role. It lands beside
                 ;; `:settlement/execute` because it is the same job — the actor
                 ;; who pushed the money out is the one who reconciles what came
                 ;; back — and emphatically not beside `:payment/approve`.
                 :reconciliation/execute :reconciliation/read}
   :compliance #{:payment/read :account/read :entry/read :audit/read
                 :organisation/read :reconciliation/read}
   :auditor    #{:audit/read :payment/read :account/read :entry/read
                 :organisation/read :reconciliation/read}})
```

**Reproduction.** Both Step 11 calls use CTRL. Audit handlers require audit/read, which the controller lacks and only compliance/auditor hold. B independently established the resulting 403 instead of the stated evidence response.

**Why it matters.** The synthetic acceptance walk fails at its control-evidence step and teaches the wrong role assignment.

**Suggested direction.** Seed/name an auditor and explicitly switch actors for both evidence reads.

**Affects.** UAT reproducibility and least-privilege evidence. Pre-declared input 3 is confirmed.

### 2B-011 - should-fix - Increment-8 Heading Contradicts Its Global State

**Source: docs/ROADMAP.md:30-33**

```text
| 8.1 | Cockpit — ADR-0026, scaffold, release browser, honesty layer | [TASK-011](briefs/011-TASK-cockpit-initialization.md) | ✅ `CLOSED` — PR #21 (`eb3a561`) + `clofin-cockpit` PR #1 (`f20f4a6`); **live at <https://echojustus.github.io/clofin-cockpit/>** | green, both repositories |
| 8.2 | Cockpit — CORS allowlist, instance connect, seed bootstrap | [TASK-012](briefs/012-TASK-cockpit-connect-and-bootstrap.md) | ✅ `CLOSED` — PR #23 (`f174116`) + `clofin-cockpit` PR #2 (`90abb1d`) | green, both repositories |
| 8.3 | Cockpit — operation flows, scheme play, evidence view | [TASK-013](briefs/013-TASK-cockpit-operations-and-scheme-simulation.md) | ✅ `CLOSED` — cockpit PR #3 (`7ee7e28`) + REQ-only core PR #25 (`b962d7f`); frozen core held | green, cockpit CI ×2 |
| 8.4 | Cockpit — Actions scenario runner, PAT-free | [TASK-014](briefs/014-TASK-cockpit-scenario-runner.md) | ✅ `CLOSED` — cockpit PR #4 (`9283dbf`) + REQ-only core PR #27 (`ea428a3`); hosted run #1 against `ref-1` green, 27/27 steps | green, incl. the hosted scenario run |
```
**Source: docs/ROADMAP.md:227**

```text
## Increment 8 — Operator interface 💭 *(relocating to `clofin-cockpit` — D1 ruling 2026-08-15, ADR-0026; phase 8.1 in flight as [TASK-011](briefs/011-TASK-cockpit-initialization.md))*
```

**Reproduction.** The same RC document marks phases 8.1-8.4 CLOSED in its global table and phase 8.1 in flight in its section heading. The consistency guard still passes because this heading's inline brief reference is not compared as a status claim.

**Why it matters.** Readers receive incompatible answers about completed synthetic-project work depending on their entry point into the document.

**Suggested direction.** Correct the heading and make every restated status, including inline-linked headings, participate in the independently sourced comparison.

**Affects.** Core ROADMAP/document controls; L-15/L-16. Pre-declared input 4 is confirmed. This is not an audit of the separate cockpit repository.

### 2C-001 - should-fix - Nested Reconciliation Evidence Silently Stops at 501 Rows

**Source: src/clofin/recon/repository.clj:558-564**

```text
(defn breaks-for-statement
  "Every break a statement opened, oldest first."
  [source statement-id]
  (mapv row->break
        (db/query source [(str break-columns
                               "where statement_id = ? order by opened_at, id limit ?")
                          statement-id (inc row-cap)])))
```
**Source: src/clofin/recon/repository.clj:687-693**

```text
(defn adjustments-for-break
  "Every adjustment raised against a break, oldest first."
  [source break-id]
  (mapv row->adjustment
        (db/query source [(str adjustment-columns
                               "where break_id = ? order by created_at, id limit ?")
                          break-id (inc row-cap)])))
```
**Source: docs/COMPLIANCE.md:872-873**

```text
**Evidence.** `GET /reconciliation-statements/{id}` returns every line, every
match with the rule that produced it, and every break the statement opened.
```

**Reproduction.** C ingested 168 valid synthetic lines matching 168 journal movements but disagreeing on amount, date and type. PostgreSQL stored 504 breaks; ingest and GET returned 501 with no truncation indicator, while the status endpoint counted 504. A separate 502-adjustment fixture returned 501 through the public break read. Both input caps remained satisfied.

**Why it matters.** The evidence projection silently omits durable disagreements or proposals while claiming completeness; no database row loss is alleged.

**Suggested direction.** Return the complete bounded child set, or expose an explicit cap/truncation and retrievable continuation for both child collections. Test limits derived from both reconciliation sides and every disagreement kind.

**Affects.** C-13 evidence completeness; ADR-0011; L-6/L-14; synthetic investigation workflows.

### 2C-003 - should-fix - Currency-Property Guard Checks No Currency Properties

**Source: test/clofin/contract_test.clj:27**

```text
      (.load (Yaml.) r))))
```
**Source: test/clofin/contract_test.clj:203-208**

```text
      (doseq [[schema-name schema] (get-in spec ["components" "schemas"])
              :let [currency (get-in schema ["properties" "currency"])]
              :when (map? currency)]
        (is (= "#/components/schemas/CurrencyCode" (get currency "$ref"))
            (str "components.schemas." schema-name ".properties.currency is "
                 (pr-str currency) " rather than a CurrencyCode reference"))))))
```

**Reproduction.** C's in-memory mutant replaced Money.currency's CurrencyCode reference with the former three-uppercase-letter pattern. SnakeYAML returned java.util.LinkedHashMap; Clojure map? was false. The named A-019 test passed with exactly one assertion, its separate top-level enum comparison, skipping every currency-property check.

**Why it matters.** The precise old contract defect can return while the purported regression guard stays green. Current currency values themselves match; the finding is false protection.

**Suggested direction.** Normalize parsed data or recognize Java maps, require a nonempty discovered property population and prove a changed property fails the test.

**Affects.** A-019 regression assurance, OpenAPI set coverage and L-6.

### 2C-004 - should-fix - UAT-007 Inherits a Threshold That Invalidates Its Example

**Source: docs/uat/UAT-006-settlement-simulation.md:95-96**

```text
insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
values ('<ORG>','SGD',0,1);
```
**Source: docs/uat/UAT-007-reconciliation-and-breaks.md:52**

```text
| Prerequisite | [UAT-006](UAT-006-settlement-simulation.md) completed, or its steps 1–7 repeated |
```
**Source: docs/uat/UAT-007-reconciliation-and-breaks.md:92-95**

```text
insert into approval_threshold (organisation_id, currency, from_minor, approvals_required)
values ('<ORG>', 'SGD', 100000, 1)
on conflict (organisation_id, currency, from_minor)
  do update set approvals_required = excluded.approvals_required;
```
**Source: docs/uat/UAT-007-reconciliation-and-breaks.md:430**

```text
**Expected** — `201`, `approvalsRequired: 0`, `posted: true`, break `resolved`.
```

**Reproduction.** UAT-006 seeds an SGD band from zero. UAT-007 adds the 100000 band without removing zero. C then posted a 99999 adjustment through the public handler: 201, proposed, posted false, approvalsRequired 1, rather than the stated immediate posting.

**Why it matters.** The documented prerequisite makes the synthetic de-minimis demonstration unreachable even after valid actor variables are supplied.

**Suggested direction.** Replace or independently seed the fixture tenant's SGD bands, assert the actual floor, and provide the actor aliases/seeds the sequel expects.

**Affects.** UAT-007 reproducibility and C-02/C-13 evidence; distinct from B-010's role mismatch.

### 2C-005 - should-fix - Two Capture Writers Can Emit Unstamped Artifacts

**Source: tools/clofin/tools/capture/bundle.clj:406-411**

```text
(defn write-quotations!
  "Write the control statements and invariants the walkthrough may quote.

  Stamped like everything else, because a quotation is only worth anything
  with the commit it was taken from attached — RULE 3 says *attributed and
  linked at the captured commit*, and the link is built from the stamp."
```
**Source: tools/clofin/tools/capture/bundle.clj:424-430**

```text
        file    (io/file path)]
    (when (empty? (get quotations "controls"))
      (throw (ex-info (str "capture refuses to write " path ": no control statements were extracted.")
                      {:path (str path)})))
    (io/make-parents file)
    (spit file text)
    {:path (str path) :sha256 (prov/sha256 text)}))
```
**Source: tools/clofin/tools/capture/bundle.clj:450-454**

```text
        text    (json-text payload)
        file    (io/file path)]
    (io/make-parents file)
    (spit file text)
    {:path (str path) :sha256 (prov/sha256 text)}))
```
**Source: docs/ADR/0022-the-capture-harness-establishes-its-own-provenance.md:96-100**

```text
`clofin.tools.capture.bundle/write!` validates the complete stamp **before it
opens a file**, and is the only path to a bundle on disk. There is no
`--no-verify`, no environment variable that skips it, and no `spit` anywhere
else in the harness. A refusal therefore leaves nothing behind that looks like
output — which matters, because the next step in the pipeline copies files.
```

**Reproduction.** C called all four discovered JSON writers with absent provenance. Bundle and service-info writes refused and created no file; quotation and manifest writes succeeded and created files. Temporary files were outside the repository and removed.

**Why it matters.** The exhaustive required-field test covers one writer, while the promised precondition applies to every artifact sink. Normal capture currently supplies a stamp; this is not evidence of an already-published unstamped bundle.

**Suggested direction.** Put one pre-open provenance gate on every writer and test the writer-by-required-field matrix, including absence of output on refusal.

**Affects.** In-core capture provenance; ADR-0020/0022; L-6/L-13.

### 2C-007 - should-fix - Capture Omits C-13's Substantive Guarantees

**Source: tools/clofin/tools/capture/quotations.clj:53**

```text
      (let [body (take-while #(not (str/blank? %)) (drop idx lines))]
```
**Source: docs/COMPLIANCE.md:790-796**

```text
**Statement.** Read each sentence with its named set, because each is bounded on
purpose.

1. **Every line of a statement CloFin applies is either matched to exactly one
   ledger movement, recording which rule matched, or is a break.** The set is
   the lines of statements whose `disposition` is `applied`; a refused statement
   is matched not at all, and says so.
```

**Reproduction.** C extracted quotations at the RC: 13 controls and 12 invariants were found, but C-13's statement was only its introductory sentence. All seven numbered guarantees were omitted.

**Why it matters.** Complete ID coverage masks incomplete statement coverage. A consumer restricted to the fixture cannot display the actual reconciliation guarantees it is meant to quote.

**Suggested direction.** Parse the entire statement block structurally, including lists and multiple paragraphs, and test body completeness for every discovered control.

**Affects.** C-13 quotation evidence; ADR-0020 RULE 3; capture's whole-set guard. The retained introduction is verbatim; the defect is omission, not invented wording.

### 2C-008 - should-fix - ROADMAP Sends the Next Worker to a Closed Task

**Source: docs/ROADMAP.md:253-255**

```text
Take the lowest-numbered brief in [`briefs/`](briefs) that is `READY` with its
dependencies met — currently **TASK-001**. Set its `Status` to `IN PROGRESS` in
your first commit; that commit is the lock other sessions check.
```
**Source: docs/ROADMAP.md:20**

```text
| 2 | Ledger persistence and account API | [TASK-001](briefs/001-TASK-ledger-persistence-and-account-api.md) | ✅ `CLOSED` — merged to `main` in PR #2 (`f7018a1`); audited in FEEDBACK-M1 (F-002/F-003/F-004, actioned via the increment-4 stack) | green, 757 assertions |
```

**Reproduction.** The pickup instructions identify TASK-001 as current READY work, while the RC table and pinned meta brief say CLOSED. All 14 discovered meta task briefs are CLOSED. The document-consistency guard passes.

**Why it matters.** Internal execution guidance can direct a worker to repeat completed work, another status copy outside the selected guard population.

**Suggested direction.** Remove or derive next-task naming from authoritative READY statuses and include prose restatements in the comparison model.

**Affects.** ROADMAP workflow accuracy; L-15/L-16; distinct from B-011's stale heading.

### 2C-009 - should-fix - Settlement Journal Entries Lack Their Own Posting Events

**Source: src/clofin/settlement/service.clj:68**

```text
            [clofin.ledger.repository :as ledger]
```
**Source: src/clofin/settlement/service.clj:228-232**

```text
                                 (ledger/post-entry!
                                  tx (first (posting/release-entries
                                             instruction {:accounts    accounts
                                                          :entry-ids   [entry-id]
                                                          :occurred-at occurred-at})))
```
**Source: src/clofin/settlement/service.clj:565-570**

```text
                (ledger/post-entry! tx entry)
                (audit-store/record! tx {:organisation-id organisation-id
                                         :actor-id        (:id actor)
                                         :action          (audit-action resolved)
                                         :subject-type    "payment-instruction"
                                         :subject-id      instruction-id
```
**Source: docs/COMPLIANCE.md:849-850**

```text
resolution is a new approved entry through
`clofin.ledger.service/post-entry!` — the same path a release takes.
```

**Reproduction.** C drove one synthetic payment through public settlement. The release and finality created two journal entries; an independent subject-id join found zero audit events for either entry, and both journal-entry evidence-pack requests returned 404. Settlement uses the repository directly, unlike the ledger API and reconciliation service.

**Why it matters.** The documented common posting path is false, and journal-subject evidence depends on which producer created the entry. Payment and batch events are still attributed atomically; this is not wholly unaudited settlement or a duplicate-movement finding.

**Suggested direction.** Route settlement postings through the ledger service and assert one journal-entry.posted per actual entry, or explicitly narrow the control and evidence claims through recorded governance.

**Affects.** C-05 as restated by C-13, journal evidence and complete producer coverage.

### 2C-010 - should-fix - Live Responses Disagree with Their OpenAPI Contracts

**Source: src/clofin/api/settlement.clj:376-380**

```text
      (resp/ok {"settlementBatches" (mapv batch->wire batches)
                "count"     (count batches)
                "limit"     settlement/row-cap
                "truncated" (boolean truncated?)
                "simulated" true}))))
```
**Source: api/openapi.yaml:3416**

```text
      required: [id, organisationId, scheme, currency, valueDate, status, createdBy, createdAt, simulated]
```
**Source: api/openapi.yaml:3506-3510**

```text
      properties:
        settlementBatches:
          type: array
          items:
            $ref: "#/components/schemas/SettlementBatch"
```

**Reproduction.** C compared each returned settlement-list item with the referenced schema: simulated was missing. Five operations returned undeclared 400 responses for malformed IDs or status: reconciliation break/statement GETs, settlement batch GET/list and batch submit. A returned scheme response without a reason correctly returned 422, but recordSchemeResponse declares only 200/400/401/403/404/409. The common validation problem shape was recorded. Recursive enum comparison also found the unused readiness checks value failed; it is not a separate operational failure.

**Why it matters.** Ordinary success and modeled refusal responses violate the published interface while route-level contract checks remain green. A-017's runtime correction did not close its response-status copy.

**Suggested direction.** Validate real request/response fixtures against each operation, use the correct list-item schema or include simulated, and declare all modeled response statuses.

**Affects.** Synthetic settlement/reconciliation clients; OpenAPI integrity; A-011/A-012/A-017 assurance.

### 2C-011 - should-fix - Adjustment Decision Location Is Not Retrievable

**Source: src/clofin/api/reconciliation.clj:456**

```text
      (resp/created (str "/reconciliation-adjustments/" id)
```
**Source: src/clofin/routes.clj:229-232**

```text
   {:method :post :path "/reconciliation-adjustments/:id/approvals"
    :operation-id "approveReconciliationAdjustment"
    :handler (reconciliation/decide-adjustment pool)
    :summary "Decide a reconciliation adjustment, posting or refusing it"}
```

**Reproduction.** C followed a successful adjustment rejection's 201 Location with a valid auditor. GET /reconciliation-adjustments/{id} returned 404; the adjustment is available only inside its break representation, and no GET route exists at the Location.

**Why it matters.** A normal post-creation follow-up fails even though the synthetic decision is durable.

**Suggested direction.** Return the existing retrievable representation's URI or add and declare a scoped adjustment read endpoint.

**Affects.** Reconciliation HTTP navigation/contract, not persistence of the decision.

### 2C-012 - consider - Adjustment Evidence Does Not Traverse Its Approval Decisions

**Source: src/clofin/audit/repository.clj:244-249**

```text
                               "where organisation_id = ?
                                  and (subject_id = ?
                                       or subject_id in (select id from approval
                                                          where instruction_id = ?))"
                               (ordered "asc"))
                          organisation-id subject-id subject-id (inc row-cap)])))
```
**Source: src/clofin/audit/repository.clj:262**

```text
  (let [rows (events-for-payment source organisation-id subject-id)
```

**Reproduction.** C rejected a proposed adjustment and read its evidence pack. It contained adjustment proposed/rejected events but not the durable approval.recorded decision. The shared extractor joins approvals by instruction_id only, not adjustment_id.

**Why it matters.** An investigator starting from an adjustment cannot obtain the decision history in the same way as for a payment. The approval is not lost, and the explicit joined-decision contract is payment-specific, which is why C recorded consider rather than should-fix.

**Suggested direction.** Traverse both approval subject relationships with a complete parent/child matrix, or explicitly document the narrower adjustment pack and its decision lookup.

**Affects.** Synthetic reconciliation evidence usability and subject-relation coverage.

### C-R10a - Unnumbered Residual, Confirmed by D

C recorded the following prose mismatch without assigning an id or severity; D preserves that fact rather than adding a fictitious 2C-013.

**Source: api/openapi.yaml:868-871**

```text
        Only substance is amendable. `id`, `organisationId`, `status`,
        `createdBy`, `createdAt`, `reversesId` and `retriesId` are not, and naming
        one is `422` rather than being silently ignored — a caller that sent it
        believes it changed something.
```
**Source: src/clofin/api/payments.clj:413-416**

```text
                   permitted (into #{"organisationId"}
                                   (map ->member)
                                   instruction/amendable-fields)
                   rejected  (remove permitted (keys body))]
```

**Reproduction and result.** D made the single authorized public PATCH probe, supplying the matching organisationId and a changed synthetic creditorName as the maker: status 200, payment status draft, returned organisationId matching. The field asserts tenant scope; it does not amend tenant identity. The prose's blanket 422 statement is false.

**Source: api/openapi.yaml:2859-2862**

```text
        A move an instruction may make. Only `submit` and `cancel` have an
        operation in this contract; the rest belong to approval (TASK-003) and
        settlement (increment 5), and are part of the model before they are
        part of the API.
```

C also recorded this stale PaymentEvent availability statement despite implemented approval/amendment/settlement drivers, and the previously known evidence-pack prose omitting reconciliation subjects. The latter is retained as known 010-REQ N-3 debt, not counted as a newly discovered D defect.

**Why it matters / direction / affects.** Correct the misleading client instructions, distinguish tenant assertion from editable substance, and update every event-driver prose copy. These are synthetic contract-accuracy observations requiring an explicit disposition; no originating severity is invented.

### Pre-Declared Inputs

| Input | Outcome | Finding |
|---|---|---|
| read-key universal mutation claim | confirmed by B; six protected mutations out of seventeen | 2B-009 |
| CORS replay-header exposure | confirmed by B and C's header inventory | 2B-003 |
| UAT-006 Step 11 evidence actor | confirmed against the complete permission table | 2B-010 |
| ROADMAP increment-8 heading | confirmed against the RC global table | 2B-011 |

None was disputed or discarded by citation verification. These four inputs are included in the 23 findings, not additional findings. The total must not be described as 23 previously unknown defects: it includes pre-declared inputs and independently checked known limitations.

## 4. Consolidated Disposition

**The commissioned RC is not taggable as ref-2 in its current state under the standing charter.** The two blocking findings survive citation verification: 2C-002 can misreport contradictory concurrent statements as replays, and 2C-006 can misattribute capture output to the requested commit.

Before tagging a remediation descendant, Master Control must independently reproduce, remediate and reverify both blocking findings through the data-plane change path. The regression proof needs both receipt-collision branches and the occupied-port/same-schema capture case; the existing green suites do not cover them.

Each of the twenty should-fix findings needs a recorded disposition before the tag, including an explicit rationale, owner and bounded deferral where not fixed. The consider finding must be recorded. C-R10a needs an explicit disposition without silently inventing a historical severity. Citation repairs in this report do not constitute product remediation, and none of the repository defects was edited during the audit.

Record the final tested SHA, complete eight-item audit coverage and actual dispositions in the release artifact. Preserve the synthetic-only, no-real-funds, no-connectivity, no-regulatory-approval and no-attestation framing. This recommendation is solely an internal quality-gate decision, not a production, regulatory or external assurance conclusion.

## 5. Cross-Cutting Observations and Candidate Lessons

The recurring miss-pattern is a complete-looking check over only one dimension of a larger set: required fields but not every writer, initial lookup but not collision recovery, top-level enums but not their property uses, status-table rows but not prose copies, and one posting service but not every producer. Several focused suites stayed green while C's adversarial counterexample failed the claimed guarantee. In this commissioned tiered assurance chain, these are release-level false negatives; this audit did not independently reconstruct every continuous PR review discussion.

Bidirectional accuracy remains necessary. B-005/B-006/B-011 and C-007/C-008 understate or omit built behavior rather than exaggerate it. C's capture and contract checks also show that a nonempty registry or matching count does not establish meaningful coverage of each member.

Candidate lessons below are proposals for Master Control, not adopted register entries:

| Candidate | Proposed Standing Lesson | Evidence |
|---|---|---|
| L-17 | Enumerate every dimension of a guard: member set, entry path, output sink and boundary case. Assert non-vacuity after parsing, and include a mutation that must fail. | 2B-003; 2C-003/005/007/010 |
| L-18 | Recheck content identity after concurrency arbitration. A collision winner must enter the same digest/outcome decision as an already-existing record. | 2C-002; contrast the preserved payment idempotency path |
| L-19 | Bind captured evidence to the spawned process and source, not merely a port or schema version. Test an unrelated same-schema responder. | 2C-006 |
| L-20 | Treat acceptance-script prerequisites as executable contracts. Replay inherited roles, variables and configuration when validating a sequel's promised boundary case. | 2B-010; 2C-004 |
| L-21 | Audit coverage follows every committing producer and every relevant subject relationship, not just the first service or parent type that implemented it. | 2C-009; 2C-012 |
| L-22 | Keep citation provenance separate from prose: pin source bytes and physical lines, verify every reference, and record corrections without silently rewriting the original workpaper history. | D corrected 99 citation entries without discarding a subject finding |

The commissioned repository remained read-only. D's final clean-state output and the completed citation ledger are appended to C's workpaper. No tag, commit, push, branch or tracked-file edit was performed.