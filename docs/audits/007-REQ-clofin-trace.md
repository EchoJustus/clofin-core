# 007-REQ — `clofin-trace`, and the capture harness that feeds it

| Field | Value |
|---|---|
| **Brief** | `007-TASK-clofin-trace.md` (read from `origin/meta`, per AGENT_HANDOFF §1b) |
| **Increment** | 5v.2 (visual layer, tier 1) — two repositories |
| **Branch** | `claude/clofin-capture-trace-harness-7yba1i` in **both** repositories — designated by the execution environment; the brief names no branch of its own |
| **PR base** | `main` at `00c148d` in `clofin-core`; `main` at `6ce7c6b` in `clofin-trace` (its first commit, the operator's). No stacking: TASK-006 and ADR-0020 are both merged |
| **Governing decision** | [ADR-0020](../ADR/0020-two-repositories-and-the-generate-replay-rules.md) — RULE 1 generate, RULE 2 replay, RULE 3 quote |
| **New ADR** | [ADR-0022](../ADR/0022-the-capture-harness-establishes-its-own-provenance.md) — how the harness establishes the SHA, where coverage comes from, and what fails closed |
| **Migrations** | **None.** No schema, no controls move. `clofin-trace` enforces nothing and is enforced by nothing |
| **Runtime dependencies added** | **None** in `clofin-core`: the harness uses the JDK's HTTP client, the PostgreSQL driver already required for persistence, and `data.json`. **None** in `clofin-trace`: the build and both checks are the Python standard library. ADR-0004 and NFR-007 intact |
| **Status** | Implemented. **Five objections (O-1…O-5)**, none of which blocks the work; O-1 and O-2 need rulings that affect artifacts outside this branch |

### Provenance

| | |
|---|---|
| **Model** | `claude-opus-5` |
| **Reasoning effort** | High. Ultracode was enabled for the session; **no subagents and no workflow orchestration were used** — the session's own instructions prohibited them, and that prohibition was followed |
| **Date** | 2026-08-12 |
| **Verification still in flight** | **None. Nothing is running.** Every number in §6 is from a run that finished before this file was written. No self-review, adversarial pass or long-running job is outstanding. This is the L-9 statement and it is unqualified |

---

## 1. What was built — part A, in `clofin-core`

### 1.1 `make capture-trace`

A harness on the `tools` classpath root (`:capture` alias), the same placement
`clofin.tools.diagrams` uses and for the same reason: documentation machinery
has no business on the runtime classpath.

| Namespace | Job |
|---|---|
| `clofin.tools.capture` | The run: resolve, build, migrate, start, capture, write, stop |
| `clofin.tools.capture.provenance` | Resolving the commit, the tag and the coverage; the stamp and the single validator |
| `clofin.tools.capture.stack` | The detached worktree, the child process, and three independent cross-checks |
| `clofin.tools.capture.store` | Plain JDBC: seeding, and reading the journal and the trail out whole |
| `clofin.tools.capture.recorder` | The tape: every request, every response, raw and parsed |
| `clofin.tools.capture.scenarios` | The three scenarios, as the calls they make |
| `clofin.tools.capture.quotations` | Control statements and invariants, extracted verbatim from the captured commit |
| `clofin.tools.capture.bundle` | Assembly, the sand table, and the one function that writes |

One run produces, in `target/capture/` (git-ignored):

```
manifest.json                 index, with a digest for every file below
service-info.json             the captured GET / response — the scope statement
quotations.json               12 controls and 12 invariants, verbatim, with permalinks
bundles/segregation-of-duties-refused.json
bundles/settlement-batch-misbehaves.json
bundles/evidence-pack-timeline.json
```

### 1.2 How the harness resolves the source SHA of the running stack

The brief left this to the Worker and asked for it in the REQ. The full
argument is [ADR-0022](../ADR/0022-the-capture-harness-establishes-its-own-provenance.md);
in short:

**It does not discover the SHA — it establishes it.** `make capture-trace`
takes a git ref, not a base URL. It resolves the ref to a full commit, creates
a **detached worktree** at that commit, and runs *that commit's* migration
runner and *that commit's* service from inside it, as child processes. There is
deliberately no `--base-url`: nothing about a stack somebody else started can
be resolved, and a bundle whose stamp is a guess is worse than no bundle.

Three checks that can fail back the construction up:

| Check | Catches |
|---|---|
| `git status --porcelain` in the worktree is empty | a reused worktree somebody edited |
| the live `GET /readyz` schema version equals the last entry in **that commit's** `resources/migrations/index.txt` | a different stack answering on the port |
| the captured commit's `src/clofin/money.clj` is byte-identical to the harness's | the harness rendering that commit's amounts with a formatter it never had |

The second is the load-bearing one: it compares two values produced by
different things, which is the only agreement worth anything (**L-16**).

**AC-2 is enforced before anything else happens.** An unresolvable ref, an
abbreviated SHA, no tag at the commit, several tags with no choice made, or no
readable coverage — each stops the run at the point of resolution, before a
worktree exists, before a database is touched, and before any file is opened.

### 1.3 What a bundle carries

Every request and response of the scenario (raw body, parsed body and a digest
of the raw body); every journal entry for the scenario's organisation with
every line, the account code and type joined in; every audit event; the chart
of accounts; the sand table; and the stamp:

```json
"provenance": {
  "sourceCommit": "5c7b4badced5e807e1022fce44cbcad38c6d2095",
  "sourceCommitShort": "5c7b4ba",
  "sourceRef": "ref-1", "tag": "ref-1", "tagKind": "lightweight",
  "releaseAudit": {
    "label": "PARTIAL",
    "statement": "RELEASE AUDIT: PARTIAL. Charter items 1-4 of 8 were performed …",
    "source": "release-annotation-file",
    "sourceRef": "docs/releases/ref-1.annotation.txt",
    "sourceSha256": "cbdc34fd5c4ac5c41076d02020a20aac7377446b479bfb0b744ea8c780fdf8e9"
  },
  "capturedAt": "2026-08-12T22:40:33.471124852Z",
  "schemaVersionApplied": "0011",
  "harness": {"commit": "…", "dirty": false}
}
```

The coverage is **read, never typed** — and which of the two sources it was
read from is stamped alongside it, so the weaker one is disclosed rather than
hidden behind an identical-looking chip. See objection **O-1**.

### 1.4 The fail-closed write (AC-2, L-13)

`bundle/write!` validates the complete stamp **before it opens a file** and is
the only path to a bundle on disk. No `--no-verify`, no environment variable,
no `spit` anywhere else in the harness. `clofin.tools.capture-test` removes
each required field in turn and asserts both the refusal and that **nothing was
written** — a rejected-but-written file is indistinguishable from output to
whatever copies it next. A further test asserts the removal list and the
requirement list are *equal*, so a field added to the stamp without a test
fails rather than passing unnoticed (**L-6**).

### 1.5 Two derived values, produced here on purpose

`clofin-trace` computes nothing, so anything it needs that no response contains
is produced at capture time, in this repository:

- **Amounts as text.** Money crosses the API as an integer count of minor
  units. Bundles carry a `display` string produced by the system's own
  `clofin.money/format-amount`, beside the `minorUnits` it came from, guarded
  by the formatter check in §1.2.
- **Quotations.** RULE 3's material is extracted verbatim from the *captured
  worktree's* `COMPLIANCE.md` and `DOMAIN_MODEL.md`, with file, line and a
  permalink at the captured commit. `clofin-trace` selects by id and cannot
  author one.

### 1.6 The sand table is checked against the journal (AC-6)

Each sand-table row records the journal entry ids that existed when it was
taken. `bundle/verify-against-journal!` then puts the ledger's own
`clofin.ledger.account/balance` over exactly those entries' captured lines and
refuses if the number the API returned and the number its own journal implies
differ. Two answers from the same system by two different routes; nothing is
reimplemented, because a second implementation of a balance is a second thing
that can be wrong.

### 1.7 Also added

- `docs/releases/ref-1.annotation.txt` — the tag's release annotation, byte for
  byte, plus `docs/releases/README.md` explaining what it is and why it exists.
- `make check-release-annotation` — re-reads the published release and fails on
  drift. **Not** in `verify`: it needs the network, and a verification that
  fails offline is one people learn to skip.
- ADR-0022, and its row in the ADR index.

## 2. What was built — part B, `clofin-trace`

Branched from the operator's first commit (`6ce7c6b`), which already carried
the scope statements, the back-link, the licence and the no-system-code
statement (**AC-9** — satisfied before this branch existed).

```
fixtures/          the capture output, committed here and nowhere else
build/build.py     renders _site from the fixtures — no dependencies
build/fixtures.py  loading, stamp validation, JSON-pointer resolution
build/htmlscan.py  reading the built pages back, shared by both checks
build/checks/provenance_present.py    CHECK 1 of 2
build/checks/disclaimer_verbatim.py   CHECK 2 of 2
.github/workflows/ci.yml      build + the two checks
.github/workflows/pages.yml   build + the two checks + deploy
```

Five pages: an overview, one per scenario, and *How to check this*.

**Every figure carries its pointer.** Each captured value is rendered through
one function that resolves it from a fixture by JSON pointer and stamps
`data-captured="fixture#/pointer"` into the HTML. A value that does not
resolve cannot be rendered — the build raises rather than falling back to a
literal — and `provenance-present` walks the built pages and resolves every one
of them again, independently of the renderer that wrote it. That is what makes
**AC-5** mechanical rather than reviewed.

**The scope statement and the provenance sit together, in-frame, sticky.** Not
a footer: a screenshot crops a footer, and this is the artifact most likely to
be screenshotted. The banner carries the verbatim disclaimer and the tag, the
short SHA and the coverage label as three chips; the block below it carries the
full SHA, the verbatim coverage paragraph, the source it was read from and its
digest. All of it rendered from the manifest, none of it typed (**AC-8**).

**No JavaScript, no forms, no external requests.** The build *refuses to emit*
a page containing `<script`, `<form`, `<input`, or an external `src`/`href`
stylesheet. Enforced at the source rather than by a check somebody can delete —
the same L-13 shape as the harness.

**`SIM-RTGS` and `SIM-ACH` appear exactly as captured.** They are rendered from
response bodies; there is no code path that could prettify one.

## 3. Objections

### O-1 — `ref-1` is a **lightweight** tag, and two governance documents say it is annotated

The brief requires the tag's release-audit coverage to be *"read from its
annotation"*. There is no annotation:

```
$ git cat-file -t ref-1
commit
$ git ls-remote origin 'refs/tags/*'
5c7b4badced5e807e1022fce44cbcad38c6d2095	refs/tags/ref-1
```

An annotated tag answers `tag` to the first and produces a second, peeled
`refs/tags/ref-1^{}` line in the second. Two documents on the control plane
state otherwise, and I have not edited either (they are governance copies):

- `docs/audits/README.md` — *"**Tag scheme.** `ref-<n>` … **annotated**, with
  the date and RC SHA in the tag message."*
- `docs/ROADMAP.md` — *"charter items 1–4 of 8 were performed, 5–7 were not,
  **and the tag annotation says so**."*

The text they describe does exist, in full, as the body of the GitHub
**release** published on the tag — which is what "annotation" turns out to mean
in practice here, because a release created in the web UI on an existing
lightweight tag leaves the tag object alone.

**What I did instead.** The harness takes the coverage from the annotated tag's
message when there is one, and from `docs/releases/<tag>.annotation.txt` — a
byte-for-byte mirror of the release body, fetched from the API rather than
retyped — when there is not. It **refuses to write anything** when neither
carries a `RELEASE AUDIT:` paragraph. Which source was used, and the digest of
the text, are stamped into every bundle and rendered on the walkthrough. If
`ref-1` is ever re-tagged annotated, or `ref-2` is tagged as the protocol
already describes, the harness silently takes the stronger source with nothing
to change.

**What needs a ruling.** (a) Whether to re-tag `ref-1` as annotated carrying
the release body — an operator action, not a Worker's, and one that changes a
published tag object. (b) The two sentences above are, today, false about the
artifact; they are the L-15/L-16 class (a document asserting a state the tree
does not have) and they are on `meta`, so only Master Control can correct them.
Suggested correction, if (a) is declined: say that the annotation text is
published as the release body, and that `docs/releases/` mirrors it.

### O-2 — `GET /` returns **one** disclaimer string, not four scope statements

The brief refers throughout to *"the four scope statements"* captured from
`GET /`. The captured response has a single `disclaimer` field:

> CloFin operates on synthetic data only. It is not connected to any bank,
> payment scheme or central bank, holds no regulatory authorisation, and never
> processes real funds.

Four claims, in two sentences, in one string. **No general rule splits it into
exactly those four**: splitting on `", "` mangles the list *"any bank, payment
scheme or central bank"* into two, and any rule that produces the intended four
is hand-tuned to this sentence — which is a hand-authored split with extra
steps, and a second copy of the wording that can drift from the first.

**What I did instead.** The captured string is rendered **whole and verbatim**,
in-frame and non-dismissible on every page, and `disclaimer-verbatim` compares
it byte for byte. AC-4 asks the check to *name the differing statement*: it
names the page, the marked element, and the exact character at which the
rendered text diverges, with both sides excerpted around it — demonstrated in
§6.4. It also catches a *second, softened* copy anywhere else in the visible
text, which is the failure that actually matters (L-6).

**Recommendation, not a request.** If four separately addressable statements
are wanted, the right place is upstream: `GET /` gains a
`scopeStatements: [...]` array in `clofin-core`, and the walkthrough renders
the array. That is a change to the release-audited API surface and to
`api/openapi.yaml`, so it is out of this brief's scope and belongs in one of
its own.

### O-3 — AC-11 asks for "a check", and scope item 7 allows exactly two

AC-11 requires the audited/verified/reviewed rule to be *"asserted by a check
over the built output"*. Scope item 7 says **exactly two** automated checks,
*"resist adding a third"*.

**What I did.** Folded AC-11's assertion into `provenance-present` rather than
adding a third check, on the reasoning that **the coverage qualifier is
provenance**: the rule is "you may not characterise the source state without
its coverage", and the coverage is a stamped field. Two other criteria are
folded into the same check for the same reason — AC-5's figure traceability and
AC-6's sand-table re-derivation are both "this displayed value is the captured
one". The check's report names which of the four rules failed, so the fold
costs nothing diagnostically. Two checks, and no third.

### O-4 — `make capture-trace` cannot run in CI as it stands, and that is stated rather than discovered

The harness needs a local Clojure CLI, a reachable PostgreSQL, and a git
repository with tags fetched, because it starts the captured commit's own
service from a worktree. `clofin-core`'s CI runs none of that today, so:

- **In `verify`:** the harness's unit tests only — the fail-closed write, the
  coverage parser, the sand-table refusals (14 tests, 80 assertions).
- **Not in `verify`:** `make capture-trace` itself, and
  `make check-release-annotation` (which needs the network).

This follows the resolution 006-REQ O-1 set for `clofin-trace`'s checks and
TASK-006's *Notes for the next session*: a guard that cannot run where it is
wired is worse than an honestly unwired one. **It is mechanically feasible** —
`actions/checkout` with `fetch-depth: 0`, a `postgres` service container and
the existing `setup-clojure` step would do it — and I did not add it because
the brief does not ask for it and a capture job in CI would rewrite fixtures on
every push, which is a different decision from the one this brief makes.
Recommended for the brief that captures `ref-2`.

### O-5 — the first commit's README described the coverage source as the tag's own annotation

`clofin-trace`'s first commit (the operator's, `6ce7c6b`) says the walkthrough
states its coverage *"read from the tag's own annotation"*. Per O-1 that is not
where it is read from. I corrected the sentence on my branch — naming the
release annotation and the mirror, and linking this REQ — rather than leaving
the repository's front door asserting something the harness does not do. The
four scope statements, the back-link, the licence and the no-system-code
statement are all still there, and are now checked by CI rather than only
written down. Flagged because it is an edit to text the operator authored.

## 4. Acceptance criteria

| # | Evidence |
|---|---|
| **AC-1** | Bundles carry every recorded request/response, the organisation's whole journal with every line, and every audit event — the queries are scoped by organisation and by nothing else. Captured counts in §6.2. Stamp fields asserted by `bundle/write!` before writing and re-asserted by `provenance-present` |
| **AC-2** | `capture-test/ac-2-an-unresolvable-ref-is-refused`, `…-an-abbreviated-sha-is-not-a-source-commit`, `a-capture-must-be-attributable-to-exactly-one-tag`, `coverage-that-cannot-be-read-stops-the-capture`, and `ac-2-the-harness-cannot-emit-an-unstamped-bundle` (every field, plus *nothing written*) |
| **AC-3** | `provenance-present` fails on a bundle with a field removed and on one with no stamp at all — run, §6.4 N1/N1b |
| **AC-4** | `disclaimer-verbatim` fails on a softened statement, naming the page and the differing character — run, §6.4 N2. See O-2 on "statement" |
| **AC-5** | Every figure carries `data-captured`; `provenance-present` resolves all of them; the build refuses to emit `<script>`, so nothing can be computed in a browser. Tampering demonstrated in §6.4 N3 |
| **AC-6** | `bundle/verify-against-journal!` at capture time (`capture-test/ac-6-the-sand-table-must-agree-with-the-captured-journal`), and `provenance-present` re-reads every cell from the step it names. The timed-out item is visibly not drained: `1300-IN-TRANSIT` holds `SGD 1250.00` across the sweep row, which is highlighted |
| **AC-7** | Every control claim on the site comes from `quotations.json`, extracted verbatim from the captured commit, attributed and linked at that commit. `provenance-present` compares each rendered quotation with the captured text |
| **AC-8** | The banner carries tag, short SHA and coverage label; the block carries the full SHA and the verbatim coverage. `provenance-present` fails a page missing any of them from the in-frame banner or the block |
| **AC-9** | Satisfied by the operator's first commit, before this branch. See O-5 for the one sentence corrected |
| **AC-10** | No diagram was added. The walkthrough's only figures are tables of captured values; TASK-006's generated diagrams are unchanged and `make diagrams-check` still passes |
| **AC-11** | The fourth rule inside `provenance-present`, over the built output — see O-3. Demonstrated failing and passing in §6.4 N4/N5 |

## 5. What is deliberately absent

| | Why |
|---|---|
| Any input, form, submit button, or JavaScript | RULE 2. The build refuses to emit them |
| A third CI check in `clofin-trace` | Scope item 7, and O-3 |
| Committed bundles in `clofin-core` | Two committed copies of one artifact is the drift this project keeps finding. `target/capture/` is git-ignored; `clofin-trace/fixtures/` is the committed copy |
| A `--base-url` for the harness | ADR-0022: nothing about a stack somebody else started can be resolved |
| The PR-015 approval-queue wireframe | Out of scope by the brief; it shows a proposed interface, not captured output |

## 6. Verification

Every run below finished before this file was written.

### 6.1 `clofin-core`

| Command | Result |
|---|---|
| `make verify` (test, docs-check, diagrams-check, doc-consistency) | **335 tests, 2064 assertions, 0 failures, 0 errors** — with the 14 new capture tests included |
| `clojure -M:test:it` against a local PostgreSQL 16 (`make test-it`'s `db-up` uses Docker, which this environment has no daemon for; the same alias was run against a migrated database directly) | **653 tests, 4322 assertions, 0 failures, 0 errors** |
| `make check-release-annotation` | `OK ref-1 — mirror matches the published release body` |
| `make capture-trace` | Three bundles, the fixture, the quotations and the manifest, from `ref-1` `5c7b4ba`, coverage `PARTIAL` |

### 6.2 What the capture produced

| Bundle | Steps | Journal entries | Audit events |
|---|---|---|---|
| `segregation-of-duties-refused` | 19 | 0 | 7 |
| `settlement-batch-misbehaves` | 54 | 7 | 28 |
| `evidence-pack-timeline` | 18 | 3 | 14 |

The sand table, every cell a captured `GET /accounts/:id/statement` response
and every row checked against the journal entries present when it was taken:

| After | 1100-CLIENT-FUNDS | 1300-IN-TRANSIT | 2100-CLIENT-PAYABLE | entries |
|---|---|---|---|---|
| Opening balances | SGD 10000.00 | SGD 0.00 | SGD 10000.00 | 1 |
| The batch is released | SGD 6250.00 | **SGD 3750.00** | SGD 10000.00 | 4 |
| One payment settles | SGD 6250.00 | SGD 2500.00 | SGD 8750.00 | 5 |
| One payment comes back | SGD 7500.00 | SGD 1250.00 | SGD 8750.00 | 6 |
| **The silent item times out** | SGD 7500.00 | **SGD 1250.00** | SGD 8750.00 | **6** |
| The late answer arrives | SGD 7500.00 | SGD 0.00 | SGD 7500.00 | 7 |

The sweep row is the one worth reading twice: the same six journal entries as
the row above it, and the same three balances. Nothing moved, and
`SGD 1250.00` is still in `1300-IN-TRANSIT`.

### 6.3 `clofin-trace`

| Command | Result |
|---|---|
| `python3 build/build.py` | 5 pages, the stylesheet, and the fixtures published beside them |
| `provenance-present` | `OK — 5 page(s) and 5 fixture(s), all stamped ref-1 5c7b4ba, release audit: PARTIAL` |
| `disclaimer-verbatim` | `OK — the captured GET / scope statement appears verbatim 5 time(s) across 5 page(s), and in README.md` |

### 6.4 The checks were run against deliberately broken input

Not automated — that would be a third automated check in a repository allowed
exactly two — so they are recorded here, with the reports they produced. Each
was run on a scratch copy; exit status 1 unless stated.

| # | Injected defect | Result |
|---|---|---|
| N1 | `provenance.sourceCommit` deleted from one bundle | `provenance-present FAILED — bundles/…: provenance.sourceCommit is missing or invalid (None)`, plus the manifest digest mismatch it also causes |
| N1b | the whole `provenance` object deleted | `FAILED — bundles/…: no provenance stamp at all` (a report, not a traceback) |
| N2 | `never processes real funds.` → `never processes real funds in production.` in the built `index.html` | `disclaimer-verbatim FAILED — index.html: the rendered scope statement is not the captured one. first differs at character 170: captured …'and never processes real funds.' / rendered …'and never processes real funds in production.'`, plus the near-copy rule firing separately |
| N3 | a sand-table cell edited from `SGD 3750.00` to `SGD 9999.00` in the built page | `provenance-present FAILED — the value shown for bundles/settlement-batch-misbehaves.json#/sandTable/rows/1/cells/1/display is 'SGD 9999.00', and the fixture holds 'SGD 3750.00'` |
| N4 | `This is a fully audited system.` added to the overview | `provenance-present FAILED — a sentence calls the source state 'audited' without the captured coverage qualifier ('PARTIAL') beside it` |
| N5 | the same sentence, with the captured qualifier in it | **exit 0** — the rule permits the claim when the coverage is beside it, which is what AC-11 asks for |

### 6.5 Rendering

The built site was rendered in Chromium at 1280px and read: the sticky banner
carries the verbatim disclaimer and the three provenance chips above the fold
on every page; the sand table shows the three accounts across six moments with
the sweep row highlighted; the quotations render with their attributions and
permalinks; no request leaves the page.

## 7. Notes for whoever picks this up

- **Re-capturing at `ref-2` is one command**: `make capture-trace CAPTURE_REF=ref-2`,
  then copy `target/capture/` into `clofin-trace/fixtures/`. The tag, the SHA,
  the coverage, the schema version and the quotations all move by themselves.
  If `ref-2` is annotated as the protocol describes, the coverage comes from
  the annotation and the stamp says so — nothing to edit either side.
- **Do not add a third check to `clofin-trace`.** Three criteria are already
  folded into `provenance-present` (O-3) and the report distinguishes them.
  If something new needs guarding, fold it or reconsider whether the trace
  repository should be asserting it at all.
- **The capture database is wiped on every run** and must be named `*_capture`.
  That guard exists because the default URL is one line away from a
  development database.
- **`docs/releases/*.annotation.txt` are copies.** Update them from the release
  body, never the other way round, and run `make check-release-annotation`.
- **The value date in the scenarios is fixed** (`2026-12-01`, as UAT-006 uses)
  so that two captures differ only where the system made them differ. It is
  inside `clofin.payments.instruction/max-value-date-horizon-days` (365) today;
  a capture run far enough in the future will need it moved, and the harness
  will tell you by refusing the step rather than by producing an odd bundle.
- **If a scenario step starts failing**, the harness stops with the step id, the
  expected status and the body it actually got. That is the intended behaviour:
  a bundle narrating a refusal beside a captured `201` would be fiction with a
  commit SHA on it.
