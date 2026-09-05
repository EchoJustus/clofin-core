# `ref-2` release audit — commissioning record

> **Control plane.** Written on `meta` by Master Control on 2026-09-05. This
> is the filled invocation of the standing
> [release-audit charter](RELEASE-AUDIT-CHARTER.md) for the `ref-2` candidate.
> The three session prompts below are what the auditing sessions receive,
> verbatim, so the audit runs from a written commission and not from anyone's
> memory (charter rule 2 of 2026-08-05). The decision that commissions it is
> the 2026-09-05 block in [`README.md`](README.md) → *Assurance-chain
> decisions*; if this file and that block disagree, the block governs.

## Pins

| What | Value |
|---|---|
| Release candidate (RC) | `clofin-core` `main` at **`c97a4f25a4cb2d9210613939cb55c3dafcddf32f`** (`c97a4f2`, the merge of PR #28, 2026-08-15) |
| Previous release | `ref-1` → `5c7b4badced5e807e1022fce44cbcad38c6d2095` (`5c7b4ba`), tagged 2026-08-05; a lightweight tag, as the register records |
| Control plane | `origin/meta` at the commit that carries this file. Session A records the literal value from `git rev-parse origin/meta` in its workpaper's provenance block; sessions B and C take it from there. Master Control does not write `meta` while the audit runs. |
| Auditor | GPT-5.6 Sol, CodeSpace path ([AGENT_HANDOFF §1a](../AGENT_HANDOFF.md)); reasoning effort echoed by each session into its provenance header, `not recorded` if it cannot be determined |
| Commissioned | 2026-09-05. Scheduled for 2026-09-01 by the decision of 2026-08-15 (second); execution waited on the operator's external-model allocation, not on the subject |
| Deliverable | `FEEDBACK-REL-ref-2.md`, ferried through the bridge inbox (`clofin-core/audit/inbox/`), ingested to `meta` by Master Control |

**Superseded pins:** RC `812f732` / meta `fa11e2b` (2026-08-15). `main` moved
past `812f732` by PRs #21–#28; the only code-bearing PR among them is #23
(`f174116`). `git diff f174116 c97a4f2 -- . ':!docs'` is empty — the freeze
declared for TASK-013 and TASK-014 held.

## Scope decision — full whole-repo, delta-ordered

The 2026-08-05 decision prefers delta-scoping *unless* the release changes
enforcement code or migrations in the ledger, authorisation, settlement or
financial-crime domains. This release does: TASK-008 gave the `approval`
table a second subject kind and moved the refusal vocabulary into
`clofin.authz.approval` (migration `0012`), and TASK-010 added migration
`0013`. The audit is therefore **full whole-repo** — every mandatory item runs
over the whole repository at the RC.

The delta is used for **ordering**, not scope: inside items 3 and 4, the sets
and documents the delta touched are checked first, so an interrupted session
leaves the newest code covered and the coverage table names exactly where it
stopped.

**What changed since `ref-1`** — first-parent, code-bearing PRs only;
everything else is a `meta` → `main` documentation sync:

| PR | Increment | What |
|---|---|---|
| #11 | — | ADR-0020: two repositories; the generate / replay / quote rules |
| #12 | 5v.1 (TASK-006) | Generated diagrams (`clojure -M:diagrams`), `scripts/check-doc-consistency.sh`, `scripts/check-doc-links.sh`, both in `make verify` |
| #14 | 5v.2 (TASK-007 part A) | The capture harness — `make capture-trace`, `tools/clofin/tools/capture.clj` and `tools/clofin/tools/capture/*.clj`, `docs/releases/`, `scripts/check-release-annotation.sh` |
| #16 | 6 (TASK-008) | Reconciliation: synthetic statements, matching, breaks, adjustments; migration `0012`; control C-13; DOMAIN_MODEL §2.4 and §6; UAT-007 |
| #17 | 5v.3 (TASK-009 part B) | One README line |
| #19 | 6c (TASK-010) | Linked retries (`retries_id` with an immutability trigger), a restated batch status, a rejected adjustment; migration `0013`; ADR-0024 |
| #21 | 8.1 | ADR-0026 (three repositories; ADR-0020 amended) — documents only |
| #23 | 8.2 (TASK-012 part A) | `clofin.http.cors` (default-closed allowlist), `sourceCommit` on `GET /` (`clofin.build-info`), ADR-0027, `.env.example`, `Makefile`, `docker-compose.yml`, `api/openapi.yaml` |
| #25, #27 | 8.3, 8.4 | REQ files only |

**Not in scope, by definition of the subject** (README → *Release audits*;
ADR-0026): `clofin-trace` and `clofin-cockpit`. **In scope:** the capture
harness in this repository, and ADR-0026 / ADR-0027 as the boundary
artifacts.

## Inherited scope

- **The two deferral notes** (README decisions of 2026-08-14 and 2026-08-15):
  TASK-006, 007, 008, 009 and 010 have never been independently audited. This
  audit is the one they were deferred to.
- **Charter items 5–7** were not performed at `ref-1` and carry forward as
  **mandatory-first** scope. They run in session B, before anything the
  `ref-1` audit did cover.
- **Four pre-declared inputs** — known before the audit and deliberately not
  fixed in the subject, to be confirmed independently. A pre-declared input
  that does not reproduce is reported as *disputed*, with evidence:
  1. **012-REQ N-1** — `clofin.idempotency/read-key`'s docstring claims the
     key is mandatory on *every* mutating endpoint; three endpoints neither
     require nor accept it (the L-14 class, in a copy the `ref-1` remediation
     never enumerated).
  2. **013-REQ O-1** — `Access-Control-Expose-Headers` omits
     `Idempotent-Replayed`, so a browser client cannot read a header the
     contract publishes.
  3. **013-REQ O-2** — UAT-006 §11 instructs the evidence-pack read as
     `$CTRL`, a controller, who cannot hold `audit/read`.
  4. **ROADMAP increment-8 heading at the RC** — "phase 8.1 in flight as
     TASK-011" while the global-state table shows 8.1–8.4 `CLOSED`: an L-16
     restatement the doc-consistency guard does not compare. Found by Master
     Control at commissioning, repaired on `meta` the same day; still present
     at the RC.

## Session plan

| Session | Charter items | Workpaper | Context |
|---|---|---|---|
| **A** | 1, 2 — pins verified, migrations from empty, both suites, environment capture | `/workspaces/audit-ref-2/A-environment-and-suites.md` | fresh chat |
| **B** | 5, 6, 7 — mandatory-first; plus the four pre-declared inputs | `/workspaces/audit-ref-2/B-lessons-debt-neutrality.md` | fresh chat |
| **C** | 3, 4 — delta-first; then 8 and the report | `/workspaces/audit-ref-2/C-consistency-and-sets.md`, then `/workspaces/audit-ref-2/FEEDBACK-REL-ref-2.md` | fresh chat |

This departs from the charter's default split (B: items 3–4; C: items 5–8)
on purpose: *mandatory-first* means first. If session C runs out of context
before item 8, it writes the coverage table and stops, and a session D
performs item 8 and the report from the workpapers. If any session is halted
by a resource limit, the resource-interruption fallback of 2026-08-05
applies: whatever the session holds is written to its workpaper before it
closes, and nothing is re-run from memory.

Finding identifiers: `2A-nnn`, `2B-nnn`, `2C-nnn`. The `ref-1` audit used
`A-001…A-019`; the prefix keeps the two series distinct.

## Ferry

After each session the operator copies that session's workpaper to the bridge
at `clofin-core/audit/workpapers/ref-2/`. After session C,
`FEEDBACK-REL-ref-2.md` goes to `clofin-core/audit/inbox/`, and each
session's chat transcript to `clofin-core/audit/chats/` as
`<date>-NN-ref-2-session-<A|B|C>`. Master Control reads the inbox and
ingests to `meta`; the register row moves from *(pending)* to *ingested*
with the coverage actually achieved.

---

## Session A prompt

```markdown
# CloFin — Release audit for `ref-2`, session A of three (charter items 1–2)

You are the release auditor for CloFin, commissioned 2026-09-05 by Master
Control from the standing release-audit charter
(`docs/audits/RELEASE-AUDIT-CHARTER.md` on `origin/meta`). This audit is the
terminal safety net of a tiered assurance chain: continuous PR reviews have
already passed this code. Your job is to find what they did not report — the
false negatives. A findings spike is the design working.

This is **session A of three**. Each session starts with a fresh context and
hands a file to the next. You perform charter items 1 and 2 and capture the
environment. You do NOT start items 3–8: they belong to sessions B and C,
which read your workpaper instead of repeating your work.

## Ground rules — non-negotiable

1. READ-ONLY on the repository: never commit, push, branch, or edit tracked
   files. Checking out a commit in detached-HEAD state is allowed; nothing
   else that changes the repository is. End with `git status` shown clean.
2. Audit target: the release candidate — `clofin-core` `main` at
   **`c97a4f25a4cb2d9210613939cb55c3dafcddf32f`**. Control-plane truth
   (briefs, audit register, standing lessons) is read from `origin/meta` via
   `git show origin/meta:<path>`, never by checking `meta` out. The subject
   is the `clofin-core` release candidate and nothing else: `clofin-trace`
   and `clofin-cockpit` are separate repositories that own no truth and are
   never in scope (ADR-0020, ADR-0026). The **capture harness** in this
   repository (`make capture-trace`, `tools/clofin/tools/capture.clj`,
   `tools/clofin/tools/capture/*.clj`, `test/clofin/tools/capture_test.clj`,
   `docs/releases/`) IS in scope.
3. Synthetic data only. CloFin never handles real funds, never connects to
   any bank, payment scheme or central bank, holds no regulatory approval,
   and this audit is an internal quality gate — not an attestation. Preserve
   that framing in every sentence you write.
4. Provenance: your workpaper's header records model **GPT-5.6 Sol**, your
   reasoning-effort setting as actually configured (write `not recorded` if
   you cannot determine it — never guess), today's date, the RC SHA and the
   `origin/meta` SHA.

## The workpaper is the memory, not the conversation

Write to `/workspaces/audit-ref-2/A-environment-and-suites.md` — outside the
repository tree; create the directory. Append the result of each step
**immediately after performing it, before starting the next**. Never re-read
a file you have already read: consult your notes, and if you catch yourself
re-reading, stop and write instead. A session that loses context loses
nothing already written.

## Steps, in order

### A.0 — Pin and verify
1. `cd /workspaces/clofin-core && git fetch origin main meta --tags`
2. `git checkout --detach c97a4f25a4cb2d9210613939cb55c3dafcddf32f`, then
   `git rev-parse HEAD` — it must print that SHA exactly. If the Codespace's
   `main` is at a later commit, that is expected (documentation syncs land
   after an RC); you audit the RC, not the tip.
3. `git rev-parse origin/meta` — record it. This is the control-plane pin for
   all three sessions. Confirm that
   `git show origin/meta:docs/audits/REL-ref-2-COMMISSION.md` exists and
   that its *Pins* table names the RC SHA above.
4. `git rev-parse ref-1` (expected `5c7b4badced5e807e1022fce44cbcad38c6d2095`)
   and `git cat-file -t ref-1` (expected `commit`: `ref-1` is a lightweight
   tag, a deviation the register already records — note it; it is not a
   finding).
5. Write the provenance header and these facts to the workpaper.

### A.1 — Environment capture
Record: OS and kernel; `docker --version` and `docker compose version`;
whether a local `clojure` CLI and JDK exist (`clojure --version`,
`java -version`) — the Makefile uses them when present and otherwise runs
the toolchain in a container (`clojure:temurin-21-tools-deps-bookworm`); the
PostgreSQL image (`docker-compose.yml` names `postgres:16-alpine`). If a
prerequisite is missing, install it in the Codespace — never inside the
repository — and record what you installed. Use the Makefile targets
throughout: a target that fails is itself evidence; do not substitute a
hand-rolled command silently.

### A.2 — Charter item 1: migrations from empty
1. `make destroy` (removes any existing data volume), then `make db-up`.
2. `make migrate`, then `make migrate-status`. Record the full output: every
   migration applied, in order, and the reported schema version. Expected at
   the RC: `0001`–`0013`, per `resources/migrations/index.txt`.
3. Partial-set check on the index itself: every file in
   `resources/migrations/` is named in `index.txt`, and every index entry is
   a file — both directions. Record the comparison.
4. `make db-reset` and confirm it replays to the same head — the replay must
   be repeatable, not merely possible.
Record item 1 as **performed** with the evidence, or **failed** with the
exact output.

### A.3 — Charter item 2: the full suite
1. `make verify` — runs `test`, `docs-check`, `diagrams-check` and
   `doc-consistency`. Record the test / assertion / failure / error counts
   from `make test`, the file count from `docs-check`, and the pass/fail of
   the two guards, each with its terminal output line quoted.
2. `make test-it` — the database integration tests. Record counts the same
   way.
3. Any failure is a finding (`2A-nnn`) with the exact output quoted. A
   failure that looks flaky is re-run once and BOTH results recorded.

### A.4 — Anomalies and handoff
- Anything odd you noticed on the way — a warning in the output, a target
  that needed a workaround, a test that took surprisingly long — goes in an
  *Anomalies* section, each with the verbatim line that prompted it. Do not
  investigate further: that is sessions B and C's work.
- A *Handoff* section: how to bring the database up quickly, how long each
  suite took, and anything the next sessions must know so they do not repeat
  your work.
- Coverage lines: `Item 1: performed | failed`, `Item 2: performed | failed`.

### A.5 — End
`make down`; then `git status`, shown clean; then stop. Do not begin any
other charter item.

## Finding format (should any arise here)
`2A-nnn` · Severity (`blocking` / `should-fix` / `consider`) · file:line with
the lines quoted verbatim · Reproduction · Why it matters, in product or
control terms · Suggested direction · Affects. A finding whose quote does not
match the file is discarded.
```

## Session B prompt

```markdown
# CloFin — Release audit for `ref-2`, session B of three (charter items 5–7, mandatory-first)

You are the release auditor for CloFin, commissioned 2026-09-05 by Master
Control from the standing release-audit charter
(`docs/audits/RELEASE-AUDIT-CHARTER.md` on `origin/meta`). This audit is the
terminal safety net of a tiered assurance chain: continuous PR reviews have
already passed this code. Your job is to find what they did not report — the
false negatives. A findings spike is the design working.

This is **session B of three**. Session A has already verified the pins,
replayed the migrations and run both suites. Its workpaper is at
`/workspaces/audit-ref-2/A-environment-and-suites.md` — read it FIRST, once;
take the `origin/meta` SHA and the environment facts from it; do not repeat
any of its work. You perform charter items **5, 6 and 7**. These three were
NOT performed by the `ref-1` release audit (halted by a resource limit after
items 1–4) and carry forward as mandatory-first scope — which is why they run
before items 3–4, in a session of their own. You do NOT start items 3, 4
or 8.

## Ground rules — non-negotiable

1. READ-ONLY on the repository: never commit, push, branch, or edit tracked
   files. Checking out a commit in detached-HEAD state is allowed; nothing
   else that changes the repository is. End with `git status` shown clean.
2. Audit target: the release candidate — `clofin-core` `main` at
   **`c97a4f25a4cb2d9210613939cb55c3dafcddf32f`**. Control-plane truth
   (briefs, audit register, standing lessons) is read from `origin/meta` via
   `git show origin/meta:<path>`, never by checking `meta` out. The subject
   is the `clofin-core` release candidate and nothing else: `clofin-trace`
   and `clofin-cockpit` are separate repositories that own no truth and are
   never in scope (ADR-0020, ADR-0026). The capture harness in this
   repository (`tools/clofin/tools/capture.clj`,
   `tools/clofin/tools/capture/*.clj`, `test/clofin/tools/capture_test.clj`,
   `docs/releases/`) IS in scope.
3. Synthetic data only. CloFin never handles real funds, never connects to
   any bank, payment scheme or central bank, holds no regulatory approval,
   and this audit is an internal quality gate — not an attestation. Preserve
   that framing in every sentence you write.
4. Provenance: your workpaper's header records model **GPT-5.6 Sol**, your
   reasoning-effort setting as actually configured (`not recorded` if you
   cannot determine it — never guess), today's date, the RC SHA and the
   `origin/meta` SHA from session A's workpaper.

## The workpaper is the memory, not the conversation

Write to `/workspaces/audit-ref-2/B-lessons-debt-neutrality.md`. Append the
result of each check **immediately after performing it, before starting the
next**. Never re-read a file you have already read: consult your notes. A
session that loses context loses nothing already written.

## B.0 — Pin
`cd /workspaces/clofin-core && git rev-parse HEAD` must print
`c97a4f25a4cb2d9210613939cb55c3dafcddf32f`; if it does not,
`git checkout --detach c97a4f25a4cb2d9210613939cb55c3dafcddf32f`.
`git status` clean before you begin.

## B.1 — Charter item 5: standing-lessons compliance
Source: `git show origin/meta:docs/audits/README.md`, section *Standing
lessons*, **L-1 through L-16**. For each lesson the register names its guard.
For each: (a) locate the guard at the RC — a test, a constraint, a trigger, a
script, a CI step, or a brief-authoring rule; (b) quote the lines that
constitute it (file:line, verbatim); (c) judge whether it is **honoured**,
not merely present.
- Brief-authoring lessons (L-1, L-3, L-4, L-9) are checked against the briefs
  written since they were adopted — `git show
  origin/meta:docs/briefs/0NN-TASK-*.md`, 006 onward — and against the
  rulings in their changelogs.
- Code lessons (L-5, L-6, L-8, L-11, L-12, L-13) are checked against the code
  and tests at the RC, **including the code added since `ref-1`**
  (reconciliation, linked retries, CORS, build-info), which those lessons
  were written before.
- L-14 is a whole-document sweep: every "every", "never", "no", "any" and
  "all" in `docs/COMPLIANCE.md`, `docs/DOMAIN_MODEL.md` and `docs/ADR/*.md`
  names its set and its boundary, and the enforcement covers the set.
- L-15 / L-16: the guard is `scripts/check-doc-consistency.sh` in
  `make verify`. Establish what it compares; then look for a restatement it
  does not compare — a claim about what is built that appears in a second
  place (README, a per-increment section, an ADR status line, a docstring).
A regressed lesson is a finding, severity at least `should-fix`. Record a
table: lesson · guard named · guard found at (file:line, quoted) · honoured?
· finding.

## B.2 — Charter item 6: known-debt reconciliation
Records of debt at the RC: `docs/COMPLIANCE.md` §4 *Known gaps*;
`docs/ROADMAP.md` — every *Carried forward, deliberately* list and the
*Deliberately deferred* table; each REQ's debt section
(`docs/audits/0NN-REQ-*.md`, 006 onward); the `ref-1` audit's four deferred
mechanisms (A-007 log sanitiser, A-008 live-schema catalog hashing, A-010
transitive SBOM, A-011 deep contract validation —
`docs/audits/FEEDBACK-REL-ref-1.md`); 010-REQ N-1 / N-3 / N-4; 014-REQ O-1.
For each recorded item: still open in the code, closed without its record
updating, or narrowed without saying so? Then the reverse: read the code for
limitations it admits itself — a `TODO`, a docstring caveat, a narrowed
claim, a test that documents a gap — and check that each has a record.
Record: item · recorded where · state in code (file:line, quoted) · agree?
· finding.

## B.3 — Charter item 7: synthetic-data and neutrality sweep
The whole repository at the RC, text of every kind: `README.md`, `docs/**`,
`api/openapi.yaml` descriptions, code comments and docstrings, `Makefile`
help text, `.env.example`, `docker-compose.yml`, `docs/releases/*.txt`, the
`GET /` response body in `src/clofin/api/health.clj`, test names. Nothing may
imply production readiness, real institutional connectivity, external
attestation or regulatory approval; nothing may name a real bank, scheme,
regulator or institution as connected to, approving, or using CloFin. Check
that the disclaimer `GET /` serves — "CloFin operates on synthetic data
only. It is not connected to any bank, payment scheme or central bank, holds
no regulatory authorisation, and never processes real funds." — is the
sentence the code emits, verbatim, and that every restatement elsewhere
(README, `make help`, the release annotation mirror) is either that sentence
or not weaker than it. Method: `git grep -n -i` over the tree for
production, live, real, bank, scheme, regulator, approved, certified,
attested, compliant, PCI, ISO, SOC and the like, then read each hit in
context. Record hit counts per term and every hit judged a drift, quoted.

## B.4 — The four pre-declared inputs
Known before the audit and deliberately left in the subject. Verify each
independently at the RC. Record it as a finding, with your own quote, if it
reproduces; as *disputed*, with evidence, if it does not.
1. `src/clofin/idempotency.clj` (`read-key`'s docstring) against the
   endpoints that actually require the key — enumerate the mutating routes
   from `src/clofin/http/router.clj` and from `api/openapi.yaml`, both
   directions.
2. `src/clofin/http/cors.clj` exposed response headers against every
   response header `api/openapi.yaml` declares.
3. `docs/uat/UAT-006-settlement-simulation.md` §11's actor against the role
   table's holders of `audit/read` (`src/clofin/authz/model.clj`).
4. `docs/ROADMAP.md`'s increment-8 heading against its global-state table.

## B.5 — End
Coverage lines for items 5, 6 and 7 — `performed` / `partial — stopped at …`
/ `not performed`; the findings list `2B-nnn`; handoff notes for session C.
`git status` clean. Stop.

## Finding format
`2B-nnn` · Severity (`blocking` / `should-fix` / `consider`) · file:line with
the lines quoted verbatim · Reproduction · Why it matters, in product or
control terms · Suggested direction · Affects. A finding whose quote does not
match the file is discarded.
```

## Session C prompt

```markdown
# CloFin — Release audit for `ref-2`, session C of three (charter items 3–4 delta-first, then 8 and the report)

You are the release auditor for CloFin, commissioned 2026-09-05 by Master
Control from the standing release-audit charter
(`docs/audits/RELEASE-AUDIT-CHARTER.md` on `origin/meta`). This audit is the
terminal safety net of a tiered assurance chain: continuous PR reviews have
already passed this code. Your job is to find what they did not report — the
false negatives. A findings spike is the design working.

This is **session C of three**. Sessions A and B are done; their workpapers
are `/workspaces/audit-ref-2/A-environment-and-suites.md` and
`/workspaces/audit-ref-2/B-lessons-debt-neutrality.md`. Read both FIRST,
once; take the pins from A; do not repeat their work. You perform charter
items **3 and 4**, then item **8** over all three workpapers, then write the
deliverable.

## Ground rules — non-negotiable

1. READ-ONLY on the repository: never commit, push, branch, or edit tracked
   files. Checking out a commit in detached-HEAD state is allowed; nothing
   else that changes the repository is. End with `git status` shown clean.
2. Audit target: the release candidate — `clofin-core` `main` at
   **`c97a4f25a4cb2d9210613939cb55c3dafcddf32f`**. Control-plane truth
   (briefs, audit register, standing lessons) is read from `origin/meta` via
   `git show origin/meta:<path>`, never by checking `meta` out. The subject
   is the `clofin-core` release candidate and nothing else: `clofin-trace`
   and `clofin-cockpit` are separate repositories that own no truth and are
   never in scope (ADR-0020, ADR-0026). The capture harness in this
   repository (`tools/clofin/tools/capture.clj`,
   `tools/clofin/tools/capture/*.clj`, `test/clofin/tools/capture_test.clj`,
   `docs/releases/`) IS in scope — audit it like any other enforcement point.
3. Synthetic data only. CloFin never handles real funds, never connects to
   any bank, payment scheme or central bank, holds no regulatory approval,
   and this audit is an internal quality gate — not an attestation. Preserve
   that framing in every sentence you write.
4. Provenance: your workpaper's header records model **GPT-5.6 Sol**, your
   reasoning-effort setting as actually configured (`not recorded` if you
   cannot determine it — never guess), today's date, the RC SHA and the
   `origin/meta` SHA from session A's workpaper.

## The workpaper is the memory, not the conversation

Write to `/workspaces/audit-ref-2/C-consistency-and-sets.md`. Append the
result of each check **immediately after performing it, before starting the
next** — so an interruption leaves an exact coverage line. Never re-read a
file you have already read: consult your notes.

## C.0 — Pin
`cd /workspaces/clofin-core && git rev-parse HEAD` must print
`c97a4f25a4cb2d9210613939cb55c3dafcddf32f`; if it does not,
`git checkout --detach c97a4f25a4cb2d9210613939cb55c3dafcddf32f`.
`git status` clean before you begin.

## Ordering rule for this session
The scope is full whole-repo, **delta-ordered**: check the documents and sets
the release delta touched FIRST, then everything else. The delta — PRs #12,
#14, #16, #19, #23; see `git show
origin/meta:docs/audits/REL-ref-2-COMMISSION.md`, *What changed since
ref-1* — is: reconciliation (control C-13; DOMAIN_MODEL §2.4 and §6;
migrations `0012`–`0013`; UAT-007), linked retries (ADR-0024), CORS and
`sourceCommit` (ADR-0027; `clofin.http.cors`; `clofin.build-info`), the
capture harness, the diagram generator and the two document guards. After
the delta, re-perform the `ref-1` audit's checks 3.1–3.17 and 4.1–4.9
(`docs/audits/FEEDBACK-REL-ref-1.md`) at the RC as a regression list: the
remediation of those nineteen findings was verified at `5c7b4ba`; you verify
that it is still true at the RC and that the code added since did not reopen
any of them.

## C.1 — Charter item 3: cross-document consistency
- For each control C-01…C-13 in `docs/COMPLIANCE.md`: the enforcement
  points it names (code, constraint, trigger, test) exist at the RC and
  enforce what the sentence says; the evidence query or command it tells an
  auditor to run actually runs and returns what the document says it will.
- For each invariant in `docs/DOMAIN_MODEL.md` §5, and each matching rule in
  §6: the constraint or test said to enforce it exists and covers it.
- `api/openapi.yaml` against the handlers: run and read
  `test/clofin/contract_test.clj`; then look for what the contract test does
  NOT compare — response headers, error shapes, enum values,
  required-versus-refused fields (the A-012 class).
- `docs/ROADMAP.md` against `docs/COMPLIANCE.md`: control statuses and the
  "still unenforced" prose agree; no increment the ROADMAP shows as not
  started has a `CLOSED` brief on `meta`; and every restatement of an
  increment's status inside the ROADMAP agrees with its global-state table
  (L-16).
A claim without a live enforcement point is a finding, **and so is a
document that understates what exists** (L-15): accuracy is bidirectional,
and only one direction is instinctively checked.

## C.2 — Charter item 4: the partial-set sweep
Every guarantee stated over an enumerable set is checked across EVERY
member, discovered from the source of truth rather than from the list the
author gave. Sets to discover, at minimum — the list is a floor, not a
ceiling:
- SQL destructive verbs per append-only table — `UPDATE`, `DELETE`,
  `TRUNCATE` — over every table the documents call append-only or
  immutable, including the reconciliation and linked-retry tables added
  since `ref-1`.
- Enum values: PostgreSQL types and check constraints, against the Clojure
  vocabularies, against the OpenAPI schemas — both directions, for every
  enum, including those the reconciliation increment added.
- Audit actions against producers: every action in the vocabulary is emitted
  somewhere; every emission uses a vocabulary action; every state transition
  the documents say is audited has an emission in the committing transaction
  (L-7).
- Subject types — every copy of the enum in the OpenAPI spec (L-6).
- Roles and permissions: the role table against every permission the router
  requires, both directions — `audit/read`, `organisation/read`, the
  reconciliation permissions included.
- Refusal reasons; response kinds and dispositions; payment states and
  events (the full state × event walk still holds); purpose codes;
  currencies and scales.
- CORS: allowed methods, allowed request headers and exposed response
  headers against what `api/openapi.yaml` declares (the 013-REQ O-1 shape).
- Endpoints requiring `Idempotency-Key` against the contract and the
  docstrings (the 012-REQ N-1 shape).
- Migration files against `resources/migrations/index.txt`; the diagram
  generator's sources against the committed diagrams; the doc-consistency
  guard's compared set against every restatement in the documents.
A guard that covers part of its set is a false guard; finding the uncovered
member is this audit's highest-value work. Record: set · source of truth ·
members found · guard · uncovered members · finding.

## C.3 — Charter item 8: citation discipline
Over all three workpapers: for every `file:line` cited, print the lines at
the RC (`sed -n '<line>p' <file>` with HEAD at the RC) and compare with the
quote. A quote that does not match is corrected if the finding survives on
the corrected quote, and discarded otherwise — record which happened. Do
this before the report, not after.

## C.4 — The deliverable
Write `/workspaces/audit-ref-2/FEEDBACK-REL-ref-2.md` with exactly this
structure:
1. Provenance header — model, effort, the dates of the three sessions, RC
   SHA, meta SHA — and, FIRST, the **coverage table**: all eight charter
   items, each `performed` / `partial (stopped at …)` / `not performed`,
   with the reason.
2. Scope execution record — each item: what was done, how, the result;
   sessions A's and B's evidence carried in by reference to their workpapers
   (quote the counts; do not re-run).
3. Findings, most severe first, each: id · severity · finding with file:line
   and the lines quoted verbatim · reproduction · why it matters in product
   or control terms · suggested direction · affects. Include B's four
   pre-declared inputs with their outcome, confirmed or disputed.
4. Consolidated disposition: is the RC taggable as `ref-2`, and under what
   conditions.
5. Cross-cutting observations and candidate standing lessons (L-17 onward),
   including any tier-correlated miss-pattern — a class of finding the
   continuous reviews repeatedly did not report.

If your context runs low before C.3 is complete: write the coverage table
with the exact stopping point and stop. A session D will perform C.3 and C.4
from the workpapers. Never summarise from memory what the workpapers already
hold.

## C.5 — End
`git status` clean. Stop.

## Finding format
`2C-nnn` · Severity (`blocking` / `should-fix` / `consider`) · file:line with
the lines quoted verbatim · Reproduction · Why it matters, in product or
control terms · Suggested direction · Affects. A finding whose quote does not
match the file is discarded.
```

---

*Record history:* created 2026-09-05 at commissioning. Ingestion of the
deliverable, the coverage actually achieved and every disposition are
recorded in [`README.md`](README.md)'s register, not here — this file is the
commission as issued and is not edited after the sessions begin.
