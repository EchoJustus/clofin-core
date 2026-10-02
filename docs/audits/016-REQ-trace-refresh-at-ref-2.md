# 016-REQ — `clofin-trace` at `ref-2`: the capture stamps how it bound, the walkthrough reads it, and the reconciliation walk joins the three

| Field | Value |
|---|---|
| **Brief** | `016-TASK-trace-refresh-at-ref-2.md` on `origin/meta`, dispatched 2026-10-02. Cited as a path: briefs live on the control plane |
| **Series number** | **016.** Task-keyed, reporting on `TASK-016` (**L-1**) |
| **Pull requests** | `clofin-core` [EchoJustus/clofin-core#38](https://github.com/EchoJustus/clofin-core/pull/38) (A-1, A-2, A-4, this file) · `clofin-trace` [EchoJustus/clofin-trace#4](https://github.com/EchoJustus/clofin-trace/pull/4) (B-1…B-4). Neither is merged; the merge order is Master Control's (§10) |
| **Branches** | `claude/brave-tesla-92a04a` in both repositories — designated by the execution environment, substituted for a `feat/` name. No other divergence |
| **PR bases** | `clofin-core` `main` at `7f3029c` (the merge of PR #37, the sync carrying this brief); `clofin-trace` `main` at `bc0017c` |
| **Model** | An Anthropic Claude model. **The identifier is deliberately not written into this file**: this session runs under a rule that keeps model identifiers out of repository artifacts. Recorded as an absence rather than omitted, as `014-REQ` and `015-REQ` did |
| **Reasoning effort** | High — extended thinking throughout, with multi-agent reading and an adversarial review pass (§9). The harness exposes no numeric setting to the session, so this is the mode, not a measured value |
| **Date** | 2026-10-02 |
| **Migrations** | **None.** `git diff --stat origin/main -- src` is empty; nothing under `resources/` changed |
| **Controls touched** | **None.** Part A is harness, tests, one ADR amendment and one README line; part B is outside audit scope by definition (ADR-0020) |
| **Status** | Implemented. Eleven objections in §8, none resolved by diverging silently |
| **Verification** | `make verify` **543 tests / 3,404 assertions**, 0 failures, 0 errors; the integration suite (`make test-it`'s steps, §9) **971 tests / 7,449 assertions**, 0 failures, 0 errors — both on `1ee8346`, the commit captured from. `clofin-trace`: the build and both checks pass on the committed fixtures at `36eefda`; 22 mutations of the fixtures or the site each fail (§6), three of them first shown passing at `6d4e3ac`. The adversarial review this branch was held for has finished and every finding is acted on (§9). **No verification is in flight (L-9).** |
---

## 1. What this file is for

TASK-016 has two halves in two repositories and one report. Part A, in
`clofin-core`, makes the capture harness **stamp** how it bound to the process
it interrogated — the condition Master Control attached to 015-REQ objection
O-5 — and adds a fourth scenario replaying UAT-007. Part B, in `clofin-trace`,
replaces the `ref-1` capture with the `ref-2` one, renders the new field in
frame, and extends the site's two checks. This file reports on both, quotes
every negative control failing, and lists eleven objections for ruling.

Nothing here changes what CloFin claims to be: a synthetic-data reference
implementation, connected to nothing, approved by no one, processing no real
funds.

---

## 2. What changed

### Part A — `clofin-core` ([EchoJustus/clofin-core#38](https://github.com/EchoJustus/clofin-core/pull/38))

| Commit | Item | What |
|---|---|---|
| `756e0dc` | A-1 | `stack/start!` returns `:identity-binding` — what `assert-same-process!` established at start-up. `capture/run-stamp` completes the stamp with it (the keyword's name; a running map with no binding yields a stamp with none, refused by the writers' one gate rather than guessed). `provenance/required` gains the row with exactly the two values; `provenance->wire` emits `identityBinding` after `schemaVersionApplied` and before `harness`; `wire->internal` reads it back. `schema-version` is `clofin.capture/2`. ADR-0022 gains *Amendment 1*, citing ADR-0027 §3a for what each value establishes; the ADR index row reads *Accepted (amended 1)* |
| `65f0b32` | A-2 | `reconciliation-breaks`, fourth in `scenarios/all`; the settlement calls the scenarios share factored into helpers both call; `seed!` gains `:expect-error`; `clofin.tools.capture-scenarios-test` (registered in the runner) |
| `b74ef2d` | A-2 | The scenario's docstring lists every substitution it makes |
| `991f564` | A-1, A-2 | Review findings: narratives brought to what the responses show; UAT-007's read-only confirmations asserted through `confirm!`; `write!` calls the one gate; the amendment's citations narrowed (§9) |
| `1ee8346` | A-1, A-2 | Review findings: `start!` decides the identity gate before spawning; `capture!` driven by a test for both bindings; the refused start's child asserted ended; every scenario's actors in the roster test (§9). **The commit captured from** |
| *(next)* | — | this file |
| *(last)* | A-4 | `README.md`'s walkthrough link names `ref-2`. Last on the branch, on its own, so it can be seen alone and merged after the site is live at `ref-2` (§10) |

### Part B — `clofin-trace` ([EchoJustus/clofin-trace#4](https://github.com/EchoJustus/clofin-trace/pull/4))

| Item | What |
|---|---|
| B-1 | `fixtures/` is the `ref-2` capture of §4: manifest, `service-info.json`, `quotations.json`, four bundles. The `ref-1` capture leaves the tree; the README names `bc0017c`, where it lives |
| B-2 | `SCHEMA_VERSION = "clofin.capture/2"`, and `Fixtures` refuses a manifest of any other version **before reading anything else**, naming it. `identityBinding` is rendered as a captured value (`manifest.json#/provenance/identityBinding`) in the sticky in-frame banner beside the tag, the short SHA and the coverage label, and in every page's provenance block under the label `identityBinding`, linked to ADR-0027 §3a at the captured commit — no sentence of the site's own about what it proves. The verify page renders the captured `GET /` body as captured (`instanceId` the redaction marker, `sourceCommit` the self-reported value). The index's scenario count, the navigation and the fourth page come from the manifest; "the three scenarios" and the hard-coded navigation are gone, and the sand-table intro no longer types an account count |
| B-3 | `provenance-present`, still one of exactly two checks: `identityBinding` required with exactly the two values, and the required list mirrors the harness's field for field, each predicate accepting nothing the harness's rejects (four were looser at `bc0017c` — `capturedAt`, `schemaVersionApplied`, `releaseAudit.sourceSha256`, and `sourceCommitShort`, whose seven characters were counted in code points where the harness counts UTF-16 units); `identityBinding` required in both the banner and the block of every page; the captured `GET /` bound to the stamp — its `sourceCommit` equal to the stamp's byte for byte, an `instance-id` capture's body carrying `sourceCommit` and `instanceId` and the fixture saying the id was redacted, a `port-exclusion` capture's carrying no `instanceId`, and the parsed `response.body` the parse of `bodyRaw`; every fixture's whole stamp equal to the manifest's (one capture run), and each manifest entry naming its own bundle's id and title; no page carrying hidden content (`hidden`, `aria-hidden`, an inline style, a template — also refused by the build); and the assurance-word rule requires the **captured** label element in the sentence, not the word |
| B-4 | README: `ref-2`, its captured label, the four scenarios, `identityBinding`, one capture per site and `bc0017c`; the scope statement unchanged and still checked |
| B-5 | On merge, `pages.yml` builds, checks and deploys. Not done here: the merge is Master Control's |

Commits: `35388ad` (B-1…B-3, on a trial capture), `446727d` (B-4), `c63035f`
and `5af45ba` (review findings, §9), `6d4e3ac` (the capture of record, §4),
`36eefda` (the review's last finding: the predicates' digit classes, §3).

**A decision taken inside B-3, stated so it can be ruled on.** TASK-007's
assurance-word rule accepted a sentence when the coverage label appeared in it
as text. At `PARTIAL` that was nearly the same thing as the captured value; at
`COMPLETE` it is an ordinary English word, and *"The source state at this
commit is complete and audited."* passed `bc0017c`'s check (§6, *AC-7,
before*). B-3 says the qualifier is "the captured label beside the SHA, never a
word of the site's own", so the rule now asks for an element rendered from
`/provenance/releaseAudit/label` inside the sentence, and `htmlscan` records
which captured elements each sentence carries. Split points are unchanged.

---

## 3. The two required-field lists, side by side

The harness's `clofin.tools.capture.provenance/required` (Clojure, internal
keys) and the site's `build/fixtures.py` `REQUIRED_PROVENANCE` (Python, wire
keys), at the commits in the PRs. Same fifteen fields; the order differs only
in where the new row sits — appended to the harness's list, at its wire
position in the site's.

| # | Harness `required` | Harness test | Site `REQUIRED_PROVENANCE` | Site test |
|---|---|---|---|---|
| 1 | `[:source-commit]` | 40 lower-case hex | `["sourceCommit"]` | 40 chars, all in `0-9a-f` |
| 2 | `[:source-commit-short]` | 7 chars (Java `count`: UTF-16 units) | `["sourceCommitShort"]` | 7 lower-case hex digits *(was: 7 code points — a value with one astral character is 7 there and 8 in the harness)* |
| 3 | `[:source-ref]` | non-blank | `["sourceRef"]` | non-blank |
| 4 | `[:source-url]` | starts `https://github.com/` | `["sourceUrl"]` | starts `https://github.com/` |
| 5 | `[:tag]` | non-blank | `["tag"]` | non-blank |
| 6 | `[:tag-kind]` | `annotated` \| `lightweight` | `["tagKind"]` | `annotated` \| `lightweight` |
| 7 | `[:release-audit :label]` | non-blank | `["releaseAudit","label"]` | non-blank |
| 8 | `[:release-audit :statement]` | starts `RELEASE AUDIT:` | `["releaseAudit","statement"]` | starts `RELEASE AUDIT:` |
| 9 | `[:release-audit :source]` | `git-tag-annotation` \| `release-annotation-file` | `["releaseAudit","source"]` | the same two |
| 10 | `[:release-audit :source-ref]` | non-blank | `["releaseAudit","sourceRef"]` | non-blank |
| 11 | `[:release-audit :source-sha256]` | 64 lower-case hex | `["releaseAudit","sourceSha256"]` | 64 lower-case hex *(was: 64 chars)* |
| 12 | `[:captured-at]` | `Instant/parse` succeeds | `["capturedAt"]` | a real UTC instant in ASCII digits, seconds present, `Z`: nothing `Instant/parse` rejects; where the two differ the site is the stricter (an offset or lower case, for example, which the harness never writes) *(was: non-blank)* |
| 13 | `[:schema-version-applied]` | four digits (Java `\d`: ASCII only) | `["schemaVersionApplied"]` | four ASCII digits, `[0-9]{4}` *(was: non-blank)* |
| 14 | `[:harness :commit]` | non-blank | `["harness","commit"]` | non-blank |
| 15 | **`[:identity-binding]`** | **`instance-id` \| `port-exclusion`** | **`["identityBinding"]`** | **`instance-id` \| `port-exclusion`** |

Row 15 is the field this brief adds, in both. Rows 2 and 11–13 are the site's
predicates brought up to the harness's; each change has a negative control (§6).
The site spells its digit classes `[0-9]`: Python's `\d` matches any Unicode
decimal digit, Java's and `Instant/parse`'s only ASCII ones — the review's one
confirmed finding (§9).

---

## 4. The manifest's provenance block, verbatim

From `fixtures/manifest.json` as committed in [EchoJustus/clofin-trace#4](https://github.com/EchoJustus/clofin-trace/pull/4) (`6d4e3ac`, unchanged since) — the file
the capture wrote, byte for byte (`sha256` of the whole manifest:
`6a5e6cf2407dbbe2628301e629050fd9801273a0dc57a04ed975db70ff40ffda`); the block
as it stands in the file:

```
 "provenance":
 {"schemaVersion":"clofin.capture/2",
  "sourceCommit":"32dfcc99025fa339478f7ecf91b42ded71d725c2",
  "sourceCommitShort":"32dfcc9",
  "sourceRef":"ref-2",
  "sourceUrl":
  "https://github.com/EchoJustus/clofin-core/tree/32dfcc99025fa339478f7ecf91b42ded71d725c2",
  "tag":"ref-2",
  "tagKind":"annotated",
  "releaseAudit":
  {"label":"COMPLETE",
   "statement":
   "RELEASE AUDIT: COMPLETE. Charter items 1-8 of 8 were performed (migrations replayed from empty; the full suite; cross-document consistency; the partial-set sweep; standing-lessons compliance; known-debt reconciliation; the synthetic-data and neutrality sweep; citation discipline over 230 citations). Four sessions on the CodeSpace path: A-C on GPT-5.6 Sol, D - citation verification and the report - on a model the session could not name. Nothing was skipped and no fallback was needed. Deliverable: docs/audits/FEEDBACK-REL-ref-2.md.",
   "source":"git-tag-annotation",
   "sourceRef":"refs/tags/ref-2",
   "sourceSha256":
   "ae22baf699ed6bb17b0ed4f6e961b54c0942c1cfbabcb5661c05b4b1d2693d9c"},
  "capturedAt":"2026-10-02T14:36:51.869252622Z",
  "schemaVersionApplied":"0013",
  "identityBinding":"instance-id",
  "harness":
  {"commit":"1ee83463e7ec0bcd13f53b31f59068b0d181ca55",
   "dirty":false}}
```

Against AC-3, field by field:

| AC-3 asks | The stamp says |
|---|---|
| `sourceCommit` `32dfcc99025fa339478f7ecf91b42ded71d725c2` | `32dfcc99025fa339478f7ecf91b42ded71d725c2` |
| `tag` `ref-2` | `ref-2` |
| `tagKind` `annotated` | `annotated` |
| `releaseAudit.label` `COMPLETE` | `COMPLETE` |
| `releaseAudit.source` `git-tag-annotation` | `git-tag-annotation` (`refs/tags/ref-2`) |
| `schemaVersionApplied` `0013` | `0013` |
| `identityBinding` `instance-id` | `instance-id` |
| `harness.dirty` `false` | `false` |
| `harness.commit` the PR branch's final commit | `1ee83463e7ec0bcd13f53b31f59068b0d181ca55` — the last commit that changes anything the capture runs. See **O-1**: the two commits after it touch only this file and `README.md` |

`ref-1`'s manifest at `bc0017c`, for contrast: `tagKind: lightweight`,
`releaseAudit.source: release-annotation-file`, `label: PARTIAL`,
`harness.dirty: true`, schema `clofin.capture/1`, no `identityBinding`.

---

## 5. The capture (A-3)

From a clone of the pushed branch made for the purpose, with the tag object
fetched, into a directory outside the repository. Transcript, as run
(`JAVA_TOOL_OPTIONS` notices removed):

```
## clean clone of the pushed branch
$ git clone --quiet --branch claude/brave-tesla-92a04a https://github.com/EchoJustus/clofin-core $SP/final/clone
$ git fetch --quiet origin --tags
$ git cat-file -t ref-2
tag
$ git rev-parse ref-2 ref-2^{commit}
420722fa9d43c930ba38d9d9c7125959b23f5ec2
32dfcc99025fa339478f7ecf91b42ded71d725c2
$ git rev-parse HEAD
1ee83463e7ec0bcd13f53b31f59068b0d181ca55
$ git status --porcelain | wc -l
0
## attempt 1
$ make capture-trace CAPTURE_REF=ref-2 CAPTURE_OUT=$SP/final/attempt-1
clojure -M:capture --ref ref-2 --out $SP/final/attempt-1
capture: ref-2 -> 32dfcc99025fa339478f7ecf91b42ded71d725c2 (tag ref-2, COMPLETE)
capture: coverage read from refs/tags/ref-2 (git-tag-annotation)
capture refuses: UAT-007 step 13 needs a break about the returned payment … In this run that break names a32e81d7-7c15-4b1c-8d1e-cfdb0c7219d3, the settled payment … Run the capture again.
make: *** [Makefile:232: capture-trace] Error 1
## attempt 2
$ make capture-trace CAPTURE_REF=ref-2 CAPTURE_OUT=$SP/final/attempt-2
clojure -M:capture --ref ref-2 --out $SP/final/attempt-2
capture: ref-2 -> 32dfcc99025fa339478f7ecf91b42ded71d725c2 (tag ref-2, COMPLETE)
capture: coverage read from refs/tags/ref-2 (git-tag-annotation)
capture refuses: UAT-007 step 13 needs a break about the returned payment … In this run that break names c0c1f45a-3cef-436b-9ec4-e4ac98cbd3a0, the settled payment … Run the capture again.
make: *** [Makefile:232: capture-trace] Error 1
## attempt 3
$ make capture-trace CAPTURE_REF=ref-2 CAPTURE_OUT=$SP/final/attempt-3
clojure -M:capture --ref ref-2 --out $SP/final/attempt-3
capture: ref-2 -> 32dfcc99025fa339478f7ecf91b42ded71d725c2 (tag ref-2, COMPLETE)
capture: coverage read from refs/tags/ref-2 (git-tag-annotation)
capture refuses: UAT-007 step 13 needs a break about the returned payment … In this run that break names 08c86aff-dae3-452d-8935-79c7d9fda0d0, the settled payment … Run the capture again.
make: *** [Makefile:232: capture-trace] Error 1
## attempt 4
$ make capture-trace CAPTURE_REF=ref-2 CAPTURE_OUT=$SP/final/attempt-4
clojure -M:capture --ref ref-2 --out $SP/final/attempt-4
capture: ref-2 -> 32dfcc99025fa339478f7ecf91b42ded71d725c2 (tag ref-2, COMPLETE)
capture: coverage read from refs/tags/ref-2 (git-tag-annotation)
capture refuses: UAT-007 step 13 needs a break about the returned payment … In this run that break names 5f583688-1ca3-42aa-a95a-97fdc9d20038, the settled payment … Run the capture again.
make: *** [Makefile:232: capture-trace] Error 1
## attempt 5
$ make capture-trace CAPTURE_REF=ref-2 CAPTURE_OUT=$SP/final/attempt-5
clojure -M:capture --ref ref-2 --out $SP/final/attempt-5
capture: ref-2 -> 32dfcc99025fa339478f7ecf91b42ded71d725c2 (tag ref-2, COMPLETE)
capture: coverage read from refs/tags/ref-2 (git-tag-annotation)
capture refuses: UAT-007 step 13 needs a break about the returned payment … In this run that break names 637d50a5-1834-4b4d-a4c9-3b0c6d703557, the settled payment … Run the capture again.
make: *** [Makefile:232: capture-trace] Error 1
## attempt 6
$ make capture-trace CAPTURE_REF=ref-2 CAPTURE_OUT=$SP/final/attempt-6
clojure -M:capture --ref ref-2 --out $SP/final/attempt-6
capture: ref-2 -> 32dfcc99025fa339478f7ecf91b42ded71d725c2 (tag ref-2, COMPLETE)
capture: coverage read from refs/tags/ref-2 (git-tag-annotation)
Captured from ref-2 (32dfcc9) at 2026-10-02T14:36:51.869252622Z ? release audit: COMPLETE
attempt 6 wrote a manifest
$ git status --porcelain | wc -l
0
```

`$SP` is the session's scratch directory, outside both repositories. Each
refusal line is shortened here; in full it reads as quoted in §6. The `?`
before `release audit` is an em dash, printed by a JVM whose standard output is
not UTF-8.

**Six runs, five refused by the scenario's own premise check (O-2), the sixth
captured.** Every refused run named the *settled* payment as the one the
`missing-line` statement dropped, and stopped before step 7: no request was
made against the wrong subject, and the partial output of each (the first
three scenarios' bundles, no manifest) stays in its own directory and is
published nowhere. Across this session the scenario ran against `ref-2`
fourteen times outside the negative controls; the premise held in six, which
is what a fair coin over two random ids gives.

What lies between the captured harness commit and the tip of the branch:

```
$ git diff --stat 1ee8346 HEAD -- tools test src resources deps.edn Makefile scripts .github
$ git diff --stat 1ee8346 HEAD
 README.md                                     |   2 +-
 docs/audits/016-REQ-trace-refresh-at-ref-2.md | 806 ++++++++++++++++++++++++++
 2 files changed, 807 insertions(+), 1 deletion(-)
```

The capture's own environment: PostgreSQL 16.14 run directly, the Clojure CLI
1.12.6.1673 and OpenJDK 21.0.11, no Docker — the arrangement TASK-015's Worker
used, for the reason ADR-0022 gives.

---

## 6. Negative controls, each seen failing

Every guard this brief adds or changes, with the mutation that must fail it
(**L-17**). Unit-test mutations were applied to the working tree, run, and
reverted (`git status` clean after each); capture mutations ran against
`ref-2` with the scenario mutated and restored; site mutations ran in scratch
copies of the committed fixtures and of a site built from them, with every
digest resealed so that the mutation is the only thing wrong.

### A-1 — the stamp

**`every-required-field-is-exercised`, before the row's test existed** — the
negative control the brief names. The row was added to `provenance/required`
and the wire, and `clofin.tools.capture-test` run before `stamp-fields` or the
test stamps knew it:

```
FAIL in (every-required-field-is-exercised) (capture_test.clj:278)
each field the stamp requires has a removal test above
provenance/required and this namespace's stamp-fields disagree. A field added to the stamp without a removal test is a field that can go missing in production and pass here.
expected: (= required exercised)
  actual: (not (= #{… ["identityBinding"] …} #{…}))

Ran 22 tests containing 263 assertions.
5 failures, 5 errors.
```

The other four failures and five errors in that run are the same fact seen from
the writer matrix: with the row in `required`, every writer refused the test
suite's own "complete" stamp — `identity-binding is missing or invalid (how the
capture established that the answering process was the one it started): nil` —
so the matrix took the new row the moment it existed.

| Mutation | Test that failed | Result |
|---|---|---|
| The value predicate widened to "any non-blank" | `ac-1-every-writer-refuses-an-identity-binding-that-is-not-one-of-the-two` | 16 failures: each of the four writers wrote `"self-report"` and `:instance-id` and left the file |
| `identityBinding` moved after `harness` on the wire | `ac-1-the-binding-sits-between-the-schema-version-and-the-harness` | `(not (= 10 11))`, `(not (= 12 10))` |
| `start!` returning `running` without the binding | `ac-1-start-carries-the-binding-it-established` | 6 failures: `(not (= :instance-id nil))`, `(not (= "instance-id" nil))`, likewise for `:port-exclusion` |
| `capture!` stamping a constant `{:identity-binding :instance-id}` instead of what `start!` returned | `ac-1-a-capture-stamps-the-binding-its-start-established` | 1 failure, on the `port-exclusion` run — the case a `ref-1` capture would have mis-stamped |
| `write!` no longer calling `assert-provenance!` first | `every-writer-calls-the-one-gate-first` | 3 failures, all `write!`'s: no sentinel thrown, the gate called 0 times, the file written |
| `start!`'s refusal no longer destroying its child | `ac-1-start-carries-the-binding-it-established` | the spawned process still alive after 5 s |
| the identity gate decided after the spawn again | `a-worktree-the-harness-cannot-read-refuses-before-anything-is-spawned` | `start! spawned a child before deciding which gate applies` — `(not (zero? 1))` |

### A-2 — the scenario

**AC-2's control — one `:expect-status` changed** (step 8's unknown assignee,
`422` → `200`), capture run against `ref-2`:

```
capture refuses: step s08-unknown-assignee expected HTTP 200 from POST /reconciliation-breaks/9a83d193-ab59-43ee-aac5-f99432ab814d/assignment and the captured stack answered 422. The scenario and the system disagree; a bundle recorded now would narrate one and show the other.
{"type":"https:\/\/clofin.dev\/problems\/unprocessable","title":"Request cannot be processed","status":422,"detail":"No such actor to assign this break to","instance":"ec08719d-5918-454f-a242-db076b202015","errors":{"assignee-id":"00000000-0000-4000-8000-000000000000"}}
```

**The premise guard (O-2), on a natural run** — no mutation; the capture run
until the id order came out the other way:

```
capture refuses: UAT-007 step 13 needs a break about the returned payment and says the missing-line run produced one. In this run that break names a8949d93-8e65-4976-84af-a3c9ec8ca470, the settled payment: the generator drops the first line in scheme-reference order, which is instruction-id order, and a8949d93-8e65-4976-84af-a3c9ec8ca470 sorts before the returned payment c96d3c63-d16f-4f36-987a-eb8e1619f8b3. No break names the returned payment, so step 13 cannot be replayed as written. Run the capture again.
```

**`seed!`'s `:expect-error`** — the check removed (`(when (and false …))`):

```
FAIL in (a-refusal-step-records-only-the-refusal-it-is-about) (capture_scenarios_test.clj:76)
the negative control: a statement refused for a reason other than the guard the step is about stops the capture, naming both
a refusal for a malformed placeholder must not pass as the index's refusal
expected: (some? e)
  actual: (not (some? nil))
FAIL in (a-refusal-step-records-only-the-refusal-it-is-about) (capture_scenarios_test.clj:79)
expected: (empty? (rec/steps (:rec c)))
  actual: (not (empty? [{:id "s11-insert-refused", … :result {:ok false, :error "ERROR: invalid input syntax for type uuid: \"<BREAK>\"", :sqlstate "22P02"} …}]))
```

That is O-5's case exactly: before this change, a step-11 insert whose break id
had not been substituted would have been recorded as "the database refused it",
for the wrong reason.

**`confirm!`** — the script's read-only confirmations, made `(expect! true …)`:

```
FAIL in (a-confirmation-the-database-contradicts-stops-the-capture) (capture_scenarios_test.clj:129)
the script's "if they differ, stop and raise a defect": a count that is not the one the step expects stops the capture, naming both
expected: (thrown-with-msg? clojure.lang.ExceptionInfo #"UAT-007 step 5 confirms that neither refused document left a receipt: it expects .*0.*, and the database holds .*1" …)
  actual: nil
```

**The roster's distinct-actor test**, with the segregation scenario's Nadia
given the reconciliation maker's id: `FAIL in
(the-roster-replays-four-scripts-in-order)` — the test now reads every
scenario's actors, including the six the first scenario names directly.

**The refactor changed nothing it was not meant to.** A capture of `ref-2`
taken before the refactor (with A-1 only) and the capture of record compare,
for the three existing scenarios, step by step with UUIDs masked — ids, kinds,
titles, narratives, methods, paths, queries, headers, request bodies, statuses,
SQL statements and expectations — with one difference, made on purpose: the
shared `seed-actors` narrative on the settlement and evidence-pack pages, which
said *"the actor who agreed a payment is never the actor who pushes it out of
the door"* in the harness's own words — a control claim, unattributed (RULE 3)
— and now says which roles the three actors hold. Journal and audit-event
counts are unchanged (19, 54 and 18 steps; 0, 7 and 3 entries; 7, 34 and 16
events). The segregation scenario's seed SQL, now built from a named list of
its actors, is byte-identical.

### Part B — the site

All run by one script over scratch copies; the build and checks are the
repository's own. Outputs as printed:

**AC-4 — build given the ref-1 manifest from bc0017c** — exit 1, failed, as required
```
build refuses: manifest.json is a 'clofin.capture/1' capture, and this build reads 'clofin.capture/2' only — one capture per site, of one schema
```

**AC-4 — provenance-present given the ref-1 fixtures (against the real site)** — exit 1, failed, as required
```
provenance-present FAILED: the fixtures could not be read: manifest.json is a 'clofin.capture/1' capture, and this build reads 'clofin.capture/2' only — one capture per site, of one schema
```

**AC-5(a) — identityBinding removed from every stamp** — exit 1, failed, as required; 19 problems, the first three shown
```
  - manifest.json: provenance.identityBinding is missing or invalid (None)
  - service-info.json: provenance.identityBinding is missing or invalid (None)
  - quotations.json: provenance.identityBinding is missing or invalid (None)
```

**AC-5(a) — and the build refuses the same fixtures** — exit 1, failed, as required; 7 problems, the first three shown
```
build refuses: the fixtures are not usable.
  - manifest.json: provenance.identityBinding is missing or invalid (None)
  - service-info.json: provenance.identityBinding is missing or invalid (None)
```

**AC-5(b) — identityBinding: "self-report" in every stamp** — exit 1, failed, as required; 19 problems, the first three shown
```
  - manifest.json: provenance.identityBinding is missing or invalid ('self-report')
  - service-info.json: provenance.identityBinding is missing or invalid ('self-report')
  - quotations.json: provenance.identityBinding is missing or invalid ('self-report')
```

**B-3(i) — capturedAt 'yesterday', schemaVersionApplied '13', a 64-character non-hex digest: values bc0017c's predicates accepted** — exit 1, failed, as required; 39 problems, the first three shown
```
  - manifest.json: provenance.releaseAudit.sourceSha256 is missing or invalid ('zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz')
  - manifest.json: provenance.capturedAt is missing or invalid ('yesterday')
  - manifest.json: provenance.schemaVersionApplied is missing or invalid ('13')
```

**AC-5(c) — the identityBinding row removed from one page's provenance block** — exit 1, failed, as required
```
  - reconciliation-breaks.html: the identity binding is not in the provenance block (expected a captured /provenance/identityBinding)
```

**AC-5(c) — the identityBinding chip removed from one page's in-frame banner** — exit 1, failed, as required
```
  - index.html: the identity binding is not in the in-frame provenance banner (expected a captured /provenance/identityBinding)
```

**AC-5(d) — the captured GET / reports a sourceCommit one character off the stamp** — exit 1, failed, as required
```
  - service-info.json: the captured GET / reports sourceCommit '32dfcc99025fa339478f7ecf91b42ded71d725c0', and service-info.json's stamp names '32dfcc99025fa339478f7ecf91b42ded71d725c2' — the service said it was one commit and the capture is attributed to another
  - service-info.json: the captured GET / reports sourceCommit '32dfcc99025fa339478f7ecf91b42ded71d725c0', and manifest.json's stamp names '32dfcc99025fa339478f7ecf91b42ded71d725c2' — the service said it was one commit and the capture is attributed to another
  - verify.html: the document shown for service-info.json#/response/bodyRaw is not the captured one
```

**B-3(iii) — an instance-id capture whose GET / carries no sourceCommit at all** — exit 1, failed, as required
```
  - service-info.json: the stamp says the capture bound by instance id, and the captured GET / carries no sourceCommit
  - verify.html: the document shown for service-info.json#/response/bodyRaw is not the captured one
```

**B-3(iii) — the parsed response.body names another commit; bodyRaw unchanged** — exit 1, failed, as required
```
  - service-info.json: response.body is not the parse of response.bodyRaw
```

**B-3 — one bundle stamped port-exclusion in an instance-id set** — exit 1, failed, as required
```
  - bundles/reconciliation-breaks.json: its stamp differs from manifest.json's at provenance.identityBinding ('port-exclusion', manifest 'instance-id') — the fixture set is not one capture run
```

**B-2 — a manifest entry whose id is not its bundle's scenario.id** — exit 1, failed, as required
```
  - bundles/reconciliation-breaks.json: the manifest's id 'recon' is not the bundle's scenario.id 'reconciliation-breaks'
```

**B-3(i) — capturedAt '2026-10-02T14:12Z', which Instant/parse rejects** — exit 1, failed, as required; 13 problems, the first three shown
```
  - manifest.json: provenance.capturedAt is missing or invalid ('2026-10-02T14:12Z')
  - service-info.json: provenance.capturedAt is missing or invalid ('2026-10-02T14:12Z')
  - quotations.json: provenance.capturedAt is missing or invalid ('2026-10-02T14:12Z')
```

**B-3(i) — capturedAt '2026-13-45T14:12:16Z', which Instant/parse rejects** — exit 1, failed, as required; 13 problems, the first three shown
```
  - manifest.json: provenance.capturedAt is missing or invalid ('2026-13-45T14:12:16Z')
  - service-info.json: provenance.capturedAt is missing or invalid ('2026-13-45T14:12:16Z')
  - quotations.json: provenance.capturedAt is missing or invalid ('2026-13-45T14:12:16Z')
```

**AC-6 — 'never processes' softened to 'does not process' on one page** — exit 1, failed, as required; 4 problems, the first three shown
```
  - settlement-batch-misbehaves.html: the rendered scope statement is not the captured one.
      first differs at character 144:
      captured: …'regulatory authorisation, and never processes real funds.'
      rendered: …'regulatory authorisation, and does not process real funds.'
  - settlement-batch-misbehaves.html: the scope statement appears in a form that is not the captured one, beginning at 'CloFin operates on synthetic data'.
      first differs at character 144:
      captured: …'regulatory authorisation, and never processes real funds.'
      rendered: …'regulatory authorisation, and does not process real funds. clofin-core'
  - settlement-batch-misbehaves.html: the scope statement appears in a form that is not the captured one, beginning at 'It is not connected to any bank'.
      first differs at character 144:
      captured: …'regulatory authorisation, and never processes real funds.'
      rendered: …'regulatory authorisation, and does not process real funds. clofin-core'
```

**AC-7 — a page sentence calling the source state audited, no qualifier at all** — exit 1, failed, as required
```
  - evidence-pack-timeline.html: a sentence calls the source state 'audited' without the captured coverage qualifier ('COMPLETE', rendered from /provenance/releaseAudit/label) beside it:
      The source state at this commit was audited.
```

**AC-7 — a page sentence calling the source state audited, the label typed as an ordinary word** — exit 1, failed, as required
```
  - evidence-pack-timeline.html: a sentence calls the source state 'audited' without the captured coverage qualifier ('COMPLETE', rendered from /provenance/releaseAudit/label) beside it:
      The source state at this commit is complete and audited.
```

**AC-7 — a page sentence calling the source state audited, the captured label in the sentence** — exit 0, passed, as required
```
provenance-present OK — 6 page(s) and 6 fixture(s), all stamped ref-2 32dfcc9, release audit: COMPLETE, identityBinding: instance-id; the published fixtures are byte-identical to the committed ones.
```

**AC-7 — the captured label in the sentence, but hidden** — exit 1, failed, as required
```
  - evidence-pack-timeline.html: carries a hidden element ('<span hidden'); every figure, qualifier and provenance value on a page must be visible
```

**AC-7, before — bc0017c's substring rule, against the same typed-word sentence** — exit 0, passed, as required
```
provenance-present OK — 6 page(s) and 6 fixture(s), all stamped ref-2 32dfcc9, release audit: COMPLETE, identityBinding: instance-id; the published fixtures are byte-identical to the committed ones.
```

**B-3(i), digits — three values the harness rejects.** Python's `\d` is not
Java's (§3). The harness's own `problems`, given each value:

```
[:captured-at] -> captured-at is missing or invalid (the capture instant): "2026-10-02T14:36:51.١٢٣Z"
[:schema-version-applied] -> schema-version-applied is missing or invalid (the schema version the captured stack reported): "٠٠١٣"
[:source-commit-short] -> source-commit-short is missing or invalid (the short form of the source commit): "32dfcc𝟗"
```

At `6d4e3ac`, with the fixtures mutated first and the site built from them, the
build and `provenance-present` both pass each one — the gap:

**before (`6d4e3ac`) — capturedAt `2026-10-02T14:36:51.١٢٣Z` (Arabic-Indic digits in the fraction)** — build exit 0, check exit 0, passed, as the gap predicts
```
provenance-present OK — 6 page(s) and 6 fixture(s), all stamped ref-2 32dfcc9, release audit: COMPLETE, identityBinding: instance-id; the published fixtures are byte-identical to the committed ones.
```

**before (`6d4e3ac`) — schemaVersionApplied `٠٠١٣` (Arabic-Indic digits)** — build exit 0, check exit 0, passed, as the gap predicts
```
provenance-present OK — 6 page(s) and 6 fixture(s), all stamped ref-2 32dfcc9, release audit: COMPLETE, identityBinding: instance-id; the published fixtures are byte-identical to the committed ones.
```

**before (`6d4e3ac`) — sourceCommitShort `32dfcc𝟗` (U+1D7D7: seven code points, eight UTF-16 units)** — build exit 0, check exit 0, passed, as the gap predicts
```
provenance-present OK — 6 page(s) and 6 fixture(s), all stamped ref-2 32dfcc𝟗, release audit: COMPLETE, identityBinding: instance-id; the published fixtures are byte-identical to the committed ones.
```

At `36eefda`, the same three — the build refuses each, and `provenance-present`
(given a site built from the real fixtures, the mutated ones published beside
it) opens with the field's own rejection:

**after — capturedAt with Arabic-Indic digits** — build exit 1 (7 problems), check exit 1 (13), failed, as required; the build's first three shown
```
build refuses: the fixtures are not usable.
  - manifest.json: provenance.capturedAt is missing or invalid ('2026-10-02T14:36:51.١٢٣Z')
  - service-info.json: provenance.capturedAt is missing or invalid ('2026-10-02T14:36:51.١٢٣Z')
  - quotations.json: provenance.capturedAt is missing or invalid ('2026-10-02T14:36:51.١٢٣Z')
```

**after — schemaVersionApplied in Arabic-Indic digits** — build exit 1 (7 problems), check exit 1 (13), failed, as required; the build's first three shown
```
build refuses: the fixtures are not usable.
  - manifest.json: provenance.schemaVersionApplied is missing or invalid ('٠٠١٣')
  - service-info.json: provenance.schemaVersionApplied is missing or invalid ('٠٠١٣')
  - quotations.json: provenance.schemaVersionApplied is missing or invalid ('٠٠١٣')
```

**after — sourceCommitShort `32dfcc𝟗`** — build exit 1 (7 problems), check exit 1 (13), failed, as required; the build's first three shown
```
build refuses: the fixtures are not usable.
  - manifest.json: provenance.sourceCommitShort is missing or invalid ('32dfcc𝟗')
  - service-info.json: provenance.sourceCommitShort is missing or invalid ('32dfcc𝟗')
  - quotations.json: provenance.sourceCommitShort is missing or invalid ('32dfcc𝟗')
```

Every earlier run in this part was repeated at `36eefda`: the same 19 failures, and the same five passes (the baseline build and both its checks, the captured label in the sentence, `bc0017c`'s rule).

---

## 7. What the replay does with UAT-007

UAT-007 as merged at `32dfcc9` (identical on `main`: `git diff 32dfcc9 origin/main
-- docs/uat src resources` is empty), step by step, with the status each call
recorded. Every request carries the script's `organisationId`, in the body or
the query as the script puts it; every `/audit/` read is made as the auditor.

| UAT-007 | Recorded steps | Statuses |
|---|---|---|
| Prerequisite — UAT-006 steps 1–7, the state-bearing calls (O-9) | `organisation-created`, `seed-actors`, `seed-auditor`, three accounts, `chart-of-accounts`, `opening-balance`, three × raise/submit/approve, `batch-created`, `batch-submitted`, `response-settled`, `response-returned` | 201/200 throughout |
| *Before you start* | `account-unapplied`; `bands-replaced-delete`, `bands-replaced-insert` (the script's SQL, one step per statement) | 201; applied, 1 row each |
| — | sand-table row *before any statement is ingested* | 200 × 4 |
| 1 | `s01-statement` — two lines, the settled and returned payments; none for the unanswered one (asserted) | 200 |
| 2 | `s02-ingested`, `s02-accounts`, `s02-in-transit` (closing SGD 1,250.00 over the statement's period) | 200, 200, 200 |
| 3 | `s03-delivered-again` — `replayed: true`, same receipt | 200 |
| 4 | `s04-different-document` — `replay-key-conflict`, `replayed: false` | 409 |
| 5 | `s05-real-format`, `s05-real-scheme` | 400, 400 |
| 6 | `missing-line`, `unknown-line`, `amount-mismatch`: request, ingest; one break each, of the kind the table predicts (asserted) | 200 × 6 |
| — | the premise check (O-2) | — |
| 7 | `s07-queue`, `s07-queue-open`, `s07-queue-haunted` | 200, 200, 400 |
| 8 | `s08-assigned`, `s08-reassigned`, `s08-unknown-assignee` | 200, 200, 422 |
| 9 | `s09-proposed`, `s09-self-approval`, `s09-approved`, `s09-entry`; sand-table row | 201, 403, 201, 200 |
| 10 | `s10-below-band`; sand-table row; `s10-bands-removed`; `s10-no-threshold`; `s10-bands-restored-delete`, `-insert` (O-4) | 201; 422 |
| 11 | `s11-assign-resolved`, `s11-adjust-resolved`, `s11-insert-refused` (O-5) | 409, 409; refused by `recon_adjustment_posted_key` |
| 12 | `s12-proposed`, `s12-self-rejection`, `s12-rejection-without-reason`, `s12-rejected`, `s12-second-proposal`, `s12-decide-again` (O-3); sand-table row | 201, 403, 422, 201, 201, 409 |
| 13 | `s13-break`, `s13-returned`, `s13-retry-settled`, `s13-retry`, `s13-returned-retried`, `s13-break-retried`, `s13-relink-refused`, `s13-unlink-refused`, `s13-retry-evidence` (O-6); sand-table row (O-8) | 200, 200, 409, 201, 200, 200, 422; refused by the retry-link trigger; 200 |
| 14 | `s14-accounts`, `s14-status` | 200, 200 |
| 15 | `s15-trail`, `s15-break-evidence`; the three statements as written | 200, 200; refused — `… is append-only` twice, and the foreign key for the plain `truncate` |

The script's read-only SQL confirmations — the band check after *Before you
start* and after step 10's restore, the counts in steps 3, 4, 5 and 12, and step
14's comparison of the status figures with the rows — run through `confirm!`,
scoped to the scenario's organisation (the script's queries are database-wide,
and the capture database holds every scenario's), and **stop the capture** when
the database disagrees, as step 14 tells its reader to. They are not steps on
the tape: the tables they read are not in the bundle, and a row count printed
beside a query would read as a statement the page cannot back. Two typed
figures in narratives (the clearing balance before ingestion and at step 2) are
checked against the captured cells the same way. Where a call states no status
(most reads, the account creation, step 12's second proposal), the success
status the step describes is expected.

The sand table (the cells are the captured statements' `closingBalance`, each
row verified against the captured journal by `verify-against-journal!`):

| After | `1100-CLIENT-FUNDS` | `1300-IN-TRANSIT` | `2100-CLIENT-PAYABLE` | `2200-UNAPPLIED` |
|---|---|---|---|---|
| Before any statement is ingested | SGD 7500.00 | SGD 1250.00 | SGD 8750.00 | SGD 0.00 |
| After step 9 — the approved correction posts | SGD 7500.00 | SGD 250.00 | SGD 8750.00 | SGD -1000.00 |
| After step 10 — the de-minimis correction posts | SGD 7500.00 | SGD -749.99 | SGD 8750.00 | SGD -1999.99 |
| After step 12 — the second proposal posts | SGD 7500.00 | SGD -1749.98 | SGD 8750.00 | SGD -2999.98 |
| After step 13 — the retry is raised | SGD 7500.00 | SGD -1749.98 | SGD 8750.00 | SGD -2999.98 |

The row after step 13 equals the row after step 12 in every cell (O-8). The
adjustments' second leg is a debit to `2200-UNAPPLIED` and a credit to
`1300-IN-TRANSIT` for every `credit` the script proposes, which is why both
fall below zero; that is the system's answer to the script's inputs, captured
as it is.
## 8. Objections

Eleven, numbered for ruling. None was resolved by diverging from the brief or the
script silently: where the replay had to choose, the choice is in the step's own
narrative in the bundle as well as here.

| # | Objection | What this branch did |
|---|---|---|
| O-1 | **A-3/AC-3 ask for the capture "from a clean checkout of the core PR branch's final commit", with `harness.commit` that commit; the brief also requires this file to quote the manifest that capture writes, and A-4 to be the branch's last commit.** The three cannot all hold: the capture stamps the commit it ran from, and the commit carrying this file — and A-4 after it — moves the tip past it. | Captured from a clean clone of the **last commit that changes anything the capture runs** (`1ee8346`). The two commits after it touch only `docs/audits/016-REQ-trace-refresh-at-ref-2.md` and `README.md`: `git diff --stat 1ee8346 HEAD -- tools test src resources deps.edn Makefile scripts .github` is empty (§5). `harness.commit` is on the branch, so Master Control's check that it is an ancestor of `main` after merge holds as written. Ruling asked: is "the last harness commit, with docs-only commits after it" the commit AC-3 means? |
| O-2 | **UAT-007 step 13's premise holds in about half of all runs.** "Find a break about a returned payment — the `missing-line` run in step 6 produced one." `missing-line` drops the statement's first line in scheme-reference order (`clofin.settlement.statement`), which within one batch is instruction-id order, and instruction ids are random v4 UUIDs; `amount-mismatch` perturbs the same line and `unknown-line` names no instruction. When the settled payment's id sorts first, no break names the returned payment, step 13's `GET` reads `settled`, and its "proper" retry answers `409`, not `201`. | The scenario **refuses** such a run before making any request against the wrong subject, naming the cause (§6, seen on a natural run: `a8949d93…`, settled, sorted before `c96d3c63…`, returned). The capture of record is the sixth run from the clean clone; the first five were refused by the check, each naming the settled payment (§5). Every published step is a step that happened in the published run; no run was edited. The fix belongs to UAT-007 (or the generator), not to the replay — for example, step 13 could name the break whose `instructionId` is `$RETURNS` and the script could choose the perturbed line, or the generator could perturb a line the caller names. |
| O-3 | **`$CHECKER2` (UAT-007 step 12, last call) is defined by neither UAT-007 nor UAT-006**, and the brief seeds actors "with exactly the roles UAT-007's inherits table names". Run literally, the header is empty and the answer is `401`, not the `409` the script states. | Made as **`$CHECKER`**, the approver who refused the adjustment, and the step's narrative says so. The `409` does not depend on who asks: the adjustment's lifecycle is checked before the approver rules (`clofin.recon.service`), so the same checker, a fresh approver and the proposer all receive it. The variable mirrors `:checker-2` in `clofin.api.reconciliation-api-test`. |
| O-4 | **Step 10 says "Restore the bands before continuing" and gives no statement**, while step 12 depends on the band being exactly `(SGD, 100000, 1)` — with no band its first proposal is `422`, with a floor of zero its second proposal does not post. | Restored with the script's own *Before you start* SQL, run again; the narrative says so. |
| O-5 | **Step 11's raw insert carries placeholders** (`<ORG>`, `<BREAK>`, `<any existing journal_entry id>`, `<CONTROLLER>`). Taken literally it is refused with `22P02` for a malformed uuid — and `seed!` accepted *any* refusal as the refusal a step is about. | Substituted with this organisation, the break resolved in step 9, the entry step 9 posted and the controller; and `seed!` gained `:expect-error`, so each of the replay's six raw statements now names the refusal it shows (`recon_adjustment_posted_key`, the retry-link trigger's message, `… is append-only`, the foreign key). Negative control in §6. |
| O-6 | **Step 13 uses `$MAKER`, `$FUNDS`, `$SETTLED` and `$VALUEDATE`, none in UAT-007's inherits table** — which says it lists "every variable and actor alias". `$VALUEDATE` is defined nowhere; UAT-006 exports the settled payment as `$SETTLES`, not `$SETTLED`. The brief's "actors with exactly the roles the inherits table names" therefore cannot be met: step 13 needs an operator, and so does producing the inherited state. | UAT-006's maker (operator) is seeded with the controller and checker by the same `settlement-actors!` the settlement scenario uses; `$FUNDS` is this organisation's `1100-CLIENT-FUNDS`; `$SETTLED` is UAT-006's `$SETTLES`; `$VALUEDATE` is `2026-12-01`, the value date UAT-006 and every captured instruction use. |
| O-7 | **The inherits table understates the checker's limit.** It says "SGD limit above SGD 100.00"; the limit applies to reconciliation adjustments too, and step 9's approval of SGD 1,000.00 needs at least that — an approver who satisfies the table with SGD 500.00 is refused `403 above-actor-limit`. | Replayed with UAT-006's seeded limit (SGD 1,000,000.00), which the settlement scenario already uses. Recorded because the script's stated precondition is not sufficient for its own step 9 (lesson **L-20**). |
| O-8 | **A-2's sand-table rows are "after each posting step (9, 10, 12, 13)", and step 13 posts nothing**: the retry is a `draft`, and a draft posts no entry. | The row after step 13 is taken as specified. It equals the row after step 12 in every cell, which is itself the captured evidence that raising the retry moved no money; its narrative says only that. |
| O-9 | **"UAT-006 completed, or its steps 1–7 repeated" admits a state that contradicts UAT-007.** A completed UAT-006 resolves the unanswered payment (its step 9), which gives step 1 three lines rather than "one short" and step 2 a clearing balance of zero. | Only steps 1–7 are replayed — and of those, the calls that produce the inherited ledger state (the brief's "prerequisite ledger movements"), through the helpers the settlement scenario calls. The refusals among steps 1–7 (2, 4, 5, 7b, 7c) change nothing reconciled and are captured on the settlement page; the scenario's docstring says so. |
| O-10 | **Step 8 is headed "Take ownership, and try to skip a step", and its body makes no such attempt** — it assigns, re-assigns, and assigns to an unknown actor. Brief A-2 lists "the skipped step" among the steps to record. There is nothing to replay, and nothing the system would refuse: the break lifecycle permits `open` → `resolved`, and the capture shows two breaks taken there without assignment (steps 10 and 12). | The three calls the body states are replayed and no fourth is invented; the scenario's docstring records the gap. Ruling asked: whether UAT-007's heading or its body is the intent, and whether "the skipped step" in the brief means something else. |
| O-11 | **ADR-0027 §3a describes the two modes and what an echoed instance id establishes, and says nothing of port exclusion's limit.** The brief asks ADR-0022's amendment to cite §3a "for what each value establishes"; for `port-exclusion`, §3a cannot carry that. The limit — a process that took the port between the free-port check and the child's answer would pass — is stated only in `clofin.tools.capture.stack/assert-same-process!`'s docstring. | The amendment cites §3a for what §3a states and the docstring for the rest, restating neither; the site links §3a and says nothing of its own. Ruling asked: whether ADR-0027 should gain the sentence, or ADR-0022 may state it. Found by this branch's review; an earlier draft of the amendment over-cited §3a. |

### UAT-007 statements that do not match the captured system, where the replay is unaffected

Recorded for whoever next edits the script; none changes a status the replay
asserts.

- Steps 9 and 12 say "**above** the SGD 1,000.00 band" and send exactly SGD
  1,000.00; it needs approval only because the bound is inclusive, as step 10
  itself says. The narratives say "at".
- Step 8's third call promises "somebody in another organisation"; the UUID it
  sends names no actor anywhere. The answer is the same by design.
- Step 11 says both refusals name "what would have been permitted instead"; the
  assignment's does (`assignable-in`), the adjustment's does not.
- Step 15's evidence pack for `$BREAK` is "opened, assigned, resolved"; step 8
  assigns it twice, so the captured pack has two `assigned` events.
- Step 15's list of actions is not the whole trail: `GET /audit/events` is
  organisation-wide, so it also carries the inherited state's events and
  `account.created` for `2200-UNAPPLIED`.
- Step 15's plain `truncate` is answered by the foreign key, not by the
  append-only trigger (the brief's pre-flight already notes this); the step's
  narrative says which answered.
- *Before you start* says "two accounts beyond settlement's three" and creates
  one.
- The step-12 blockquote says self-approval is "ranked first"; at the API the
  lifecycle (`409`) and a missing reason (`422`) are checked before it.

### In the brief, where the replay is unaffected

- A-1 gives the reason for the wire position as "key order is part of the
  contract — the block is rendered in that order". `clofin-trace` renders its
  provenance block in its own order (tag, kind, commit, coverage, binding, …),
  reading the field by name. The order is kept as specified and asserted; the
  amendment gives the reason that is true (beside the other fact only the
  running stack supplies).
- A-2's "UAT-007's fifteen steps … each with `:expect-status` equal to the
  status the script states": many calls state none (most reads, the account
  creation, step 12's second proposal). The success status the step describes
  is expected, and the docstring says so.

### Found outside this brief's scope, not changed here

- **An existing gate test is vacuous in CI.**
  `clofin.tools.capture-stack-test/which-gate-applies-is-read-from-the-source-not-from-the-answer`
  writes `ref-1`'s handler with `git show 5c7b4ba:…` and asserts it does not
  self-identify. CI checks out at depth 1 (`actions/checkout@v4`, no
  `fetch-depth`), where that `git show` fails (exit 128, verified on a depth-1
  clone); the test writes the empty `:out`, and an empty file does not
  self-identify either — so the assertion passes without reading `ref-1` at all
  (**L-17**: assert non-vacuity after every discovery step). The new AC-1 tests
  avoid history for this reason. Proposed fix: assert the extracted source is
  non-blank, and give the `verify` job `fetch-depth: 0`.
- **A multi-statement seed step records the first statement's row count.**
  `store/execute!` returns `executeUpdate`'s count, which for a string of
  several statements is the first one's; the walkthrough prints it as "Applied
  — n row(s)". The existing scenarios' `seed-actors` steps therefore understate
  what they applied. The reconciliation scenario runs its band SQL as one step
  per statement for that reason; the existing steps are unchanged here so that
  their bundles replay unchanged.
- **The proposal's `Location` header and its contract disagree.** `POST
  /reconciliation-breaks/{id}/adjustments` answers `Location:
  /reconciliation-breaks/{breakId}`; the handler's docstring and the contract's
  `getReconciliationAdjustment` description say a proposal's `Location` names
  the adjustment. The captured header shows the break. `src/` is out of scope.
- **A capture re-run into an output directory whose stack worktree was deleted
  fails** (`… is a missing but already registered worktree`) until `git worktree
  prune` is run. Not a provenance defect — the refusal is closed — but the
  message does not say how to recover.

---

## 9. Verification status at completion (L-9)

**In plain words: no verification of mine is still running. The adversarial
review this branch was held for has finished, its findings are acted on, and
the capture quoted in §4 was taken after the last change to anything it
runs.** If that sentence is ever not true of a REQ, the PR waits.

### Counts, on the final tree

| Run | Result |
|---|---|
| `make verify` (`clofin-core`, `1ee8346`) | **543 tests / 3,404 assertions, 0 failures, 0 errors**; documentation links OK (100 files), diagrams OK (7), document consistency OK, disclaimer OK |
| `make verify` again (`clofin-core`, the tip: this file and A-4 on top of `1ee8346`) | **543 tests / 3,404 assertions, 0 failures, 0 errors**; documentation links OK (101 files — this one added), diagrams OK (7), document consistency OK, disclaimer OK |
| `make test-it` equivalent (`clofin-core`, `1ee8346`) | migrations applied from the schema the base left (13 applied, 0 pending); **971 tests / 7,449 assertions, 0 failures, 0 errors** |
| `python3 build/build.py` (`clofin-trace`, `36eefda`) | six pages, `site.css`, `fixtures/`; exit 0 |
| `provenance-present` | `provenance-present OK — 6 page(s) and 6 fixture(s), all stamped ref-2 32dfcc9, release audit: COMPLETE, identityBinding: instance-id; the published fixtures are byte-identical to the committed ones.` |
| `disclaimer-verbatim` | `disclaimer-verbatim OK — the captured GET / scope statement appears verbatim 6 time(s) across 6 page(s), and in README.md.` |
| CI checks in `.github/workflows/` | exactly two: `provenance_present.py` and `disclaimer_verbatim.py`, each run in `ci.yml` and `pages.yml` |
| `git diff --stat origin/main -- src` | empty |
| control-plane files | untouched: `git diff --stat origin/main -- docs/ROADMAP.md docs/briefs docs/audits/README.md docs/AGENT_HANDOFF.md docs/audits/RELEASE-AUDIT-CHARTER.md docs/releases docs/uat` is empty |

`make test-it` is defined as `db-up migrate` then the suite, and `db-up` starts
PostgreSQL through Docker Compose, which this environment cannot run. The
integration suite was run the way CI's `integration` job runs it — `clojure -M
-m clofin.db.migrate`, then `clojure -M:test:it` — against a local PostgreSQL
16.14 with the Compose file's database, user and password. Baseline on the
untouched base `7f3029c`, same environment: `make verify` 532 / 3,292 and the
integration suite 960 / 7,319, both 0 failures — the counts Master Control
recorded for `32dfcc9` (the integration assertion count varies with the
property tests).

### The review

An adversarial review ran over both branches before the capture of record, in
four dimensions — fidelity to UAT-007 and the brief; the honesty rules (ADR-0020
RULE 3, and the site's own words); the core code and its tests (**L-17**); and
the site's checks — and sent every finding to a verifier of its own, asked to
refute it against the tree as it stood when the verifier ran. The reviewers
read `clofin-core` at `65f0b32` (some also saw `b74ef2d`) and the `clofin-trace`
working tree before `c63035f`.

**34 findings**: fidelity 5, honesty 14, core code and tests 6, site checks 9;
one rated blocking, thirteen should-fix, twenty minor. Each was acted on as it
arrived — `b74ef2d`, `991f564` and `1ee8346` in `clofin-core`, `c63035f` and
`5af45ba` in `clofin-trace` — and the capture of record was taken after the last
of them (`1ee8346`). The blocking one: the reconciliation summary said every
correction was decided by a second actor, and the capture shows two of the
three posted by their proposer below the band; the summary now says what the
capture shows.

**The verifiers: 33 not live, 1 confirmed.**

- 31 were defects in the revision read and gone from the tree the verifier
  read, each verifier naming the commit that removed it and quoting the current
  line.
- 1 quoted text in no version of the file — an earlier draft's — and the
  current wording already says what the finding proposed.
- 1 was refuted on its merits: the trace README types `ref-2`, `32dfcc9`,
  `COMPLETE` and "four scenarios", and no check compares that text with the
  manifest. B-4 and AC-8 ask for exactly that text, and no check claims to
  cover it; recorded here for whoever writes the next refresh's brief, not
  changed.
- **Confirmed:** the site's `capturedAt` and `schemaVersionApplied` predicates
  used Python's `\d`, which matches any Unicode decimal digit, so values the
  harness rejects passed both the build and `provenance-present`. Fixing it
  turned up a third gap of the same kind: `sourceCommitShort` counted code
  points where the harness counts UTF-16 units. All three were fixed in
  `clofin-trace` `36eefda` (§3, rows 2, 12, 13), each value first shown
  passing at `6d4e3ac` and then refused (§6). The commit changes the site's
  build code only: not the fixtures, and nothing the capture runs, so the
  capture of record stands. The build and both checks were re-run on it.

---

## 10. Landing order, and what Master Control verifies

The brief's sequence, unchanged:

1. **`clofin-trace` [EchoJustus/clofin-trace#4](https://github.com/EchoJustus/clofin-trace/pull/4) merged first.** `pages.yml` builds, runs both
   checks against the artifact it is about to publish, and deploys.
2. **The live page verified** — its provenance block reads `ref-2` /
   `32dfcc9` / `COMPLETE` / `annotated` / `instance-id`, and the banner shows
   the `identityBinding` chip.
3. **`clofin-core` [EchoJustus/clofin-core#38](https://github.com/EchoJustus/clofin-core/pull/38) merged after.** Its last commit, alone, changes
   `README.md` to say `ref-2`; merged before step 2, the front door would name
   a release the site does not yet show. After the merge, `harness.commit`
   (`1ee8346`) is an ancestor of `main`.

---

## 11. Files touched

**`clofin-core`:** `tools/clofin/tools/capture.clj`,
`tools/clofin/tools/capture/{provenance,bundle,stack,scenarios}.clj`,
`test/clofin/tools/{capture_test,capture_stack_test,capture_scenarios_test}.clj`,
`test/clofin/test_runner.clj`,
`docs/ADR/0022-the-capture-harness-establishes-its-own-provenance.md`,
`docs/ADR/README.md`, `docs/audits/016-REQ-trace-refresh-at-ref-2.md` (this
file), `README.md` (A-4).

**`clofin-trace`:** `build/{build,fixtures,htmlscan}.py`,
`build/checks/provenance_present.py`, `README.md`, `fixtures/**`.
