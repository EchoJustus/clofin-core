# 009-REQ — trace hardening, and the cross-links that make the walkthrough findable

| Field | Value |
|---|---|
| **Brief** | `009-TASK-trace-hardening-and-cross-links.md` (read from `origin/meta`, per AGENT_HANDOFF §1b) |
| **Increment** | 5v.3 (visual layer, follow-up) — two repositories, small |
| **Branch** | `claude/trace-hardening-cross-links-8lbgmv` in **both** repositories — designated by the execution environment; the brief names no branch of its own |
| **PR base** | `main` at `501556e` in `clofin-core`; `main` at `71cb13f` in `clofin-trace`. Both are the versions the brief names as the floor, and both were current. No stacking: TASK-007 is merged and closed |
| **PRs** | `clofin-core` **#17** · `clofin-trace` **#2** (`ccaf993`). Cross-referenced; neither depends on the other, and they may merge in either order. Unrelated to `clofin-core` #16 (TASK-008), which is open and touches nothing this branch touches |
| **Governing decision** | [ADR-0020](../ADR/0020-two-repositories-and-the-generate-replay-rules.md) — RULE 2 replay, never fake; and the **O-3 ruling** in [`007-REQ`](007-REQ-clofin-trace.md#o-3--ac-11-asks-for-a-check-and-scope-item-7-allows-exactly-two), which bounds what "two checks" means |
| **New ADR** | **None.** No decision here that a future contributor would re-derive: the brief set the approach, and the O-3 ruling already records why a criterion folds into check 1 rather than becoming check 3 |
| **Migrations** | **None.** No schema, no controls move |
| **Runtime dependencies added** | **None** in either repository. `clofin-trace`'s build and both checks remain the Python standard library; `clofin-core` gained one README line and this file. ADR-0004 and NFR-007 intact |
| **Controls touched** | None |
| **Status** | Implemented. **No objections.** One residual boundary is recorded in §7 as a note, not a dispute — it is inherent to the class of guard, not a flaw in the brief |

### Provenance

| | |
|---|---|
| **Model** | `claude-opus-5` |
| **Reasoning effort** | High. **No subagents and no workflow orchestration were used** — the session's own instructions prohibited both unless requested, and that prohibition was followed |
| **Date** | 2026-08-15 (dispatched 2026-08-14) |
| **Verification still in flight** | **None. Nothing is running.** Every number and every check report in §6 is from a run that finished before this file was written. No self-review, adversarial pass, background job or long-running test is outstanding. This is the **L-9** statement and it is unqualified |

---

## 1. The seam, and why it was worth closing

At TASK-007's ingestion, Master Control tampered the **published** fixture copy
under `_site/fixtures/` after a build, and both checks stayed green.

The reason is structural rather than accidental. `provenance-present` read the
committed `fixtures/` tree and validated the built pages against it;
`disclaimer-verbatim` read the same committed tree. The copy served to a reader
is produced by `shutil.copytree(args.fixtures, out / "fixtures")` at the end of
`build/build.py`, and **nothing in the repository ever read those bytes back**.
The manifest's sha256 digests do not help: they bind `fixtures/`, and they live
in `manifest.json`, whose published copy is exactly as tamperable as the bundle
beside it.

What made it survivable until now is that the copy and the deploy happen inside
one job — `pages.yml` builds, runs both checks, and uploads `_site` in the same
`publish` job, with a comment in the workflow explaining that a check which
passed on a different build of the same commit has not checked what is being
deployed. That reasoning is sound and it is why the seam was theoretical.

It is also **an argument about workflow topology, not a property of the
artifact**, and it stops holding the first time anyone splits build from deploy,
caches `_site`, or publishes from a second workflow. A guard that exists only as
an argument about which job does what is a convention, not an enforcement point
— the **L-13** shape, at low stakes. The fix is to compare the bytes.

## 2. What was built — part A, in `clofin-trace` (PR #2, `ccaf993`)

### 2.1 Rule 1 of `provenance-present` now covers the published copy

One function, `published_copy_problems`, called from `main()` alongside
`fixtures.problems()`. It walks both trees and compares every file under
`_site/fixtures/` with its counterpart in `fixtures/`, byte for byte.

**Both directions, because only one of them is the obvious one (L-6).** The
brief asked for three failures and the check produces exactly three, each
naming the file:

| Condition | Report |
|---|---|
| A differing byte | `_site/fixtures/<path>: not byte-identical to fixtures/<path> — first differs at byte <n>: committed 0x…, published 0x…` |
| Committed, not published | `_site/fixtures/<path>: missing — fixtures/<path> is committed, and the build did not publish it` |
| Published, no counterpart | `_site/fixtures/<path>: published, but there is no fixtures/<path> it could have come from` |
| No published tree at all | `_site/fixtures/: the fixtures were not published beside the pages` |

The byte offset and both values are in the report rather than left to a diff,
matching what `disclaimer-verbatim` already does with a character offset: the
whole value of these checks is that the report says what to fix.

The fourth row is not in the brief and is not a fifth rule — it is the
degenerate case of the second, reported once instead of once per fixture, so a
missing publish step reads as one sentence rather than five.

### 2.2 This is check 1 of 2 — not a third check

The **O-3 ruling** draws the line: a criterion folds into `provenance-present`
when it *is* provenance, and three criteria already sit inside it on that
reasoning. The bytes a reader is invited to verify against are provenance in the
most literal sense available — `verify.html` tells the reader to check the
figures against the fixtures, and the fixtures it hands them are the published
ones. A pointer resolving correctly against a committed file the reader never
sees is not the guarantee the page makes.

Nothing was added to `build/checks/`, and nothing was added to either workflow:

```
$ ls build/checks/
disclaimer_verbatim.py
provenance_present.py

$ grep -c 'build/checks/' .github/workflows/ci.yml .github/workflows/pages.yml
.github/workflows/ci.yml:2
.github/workflows/pages.yml:2
```

The module docstring's rule 1 was extended rather than a rule 5 added, so the
count in the file's own prose still reads "four things" — the document and the
code agree, which is the L-16 discipline applied to a docstring.

### 2.3 The live URL in the README

One line, immediately under the no-system-code statement where a reader looking
for the site will actually meet it:

> The built walkthrough is served at <https://echojustus.github.io/clofin-trace/>.

## 3. What was built — part B, in `clofin-core` (PR #16)

One bullet in `README.md` → *What it demonstrates* → *Product & analysis*,
beside the other artefact links:

> - A [replay walkthrough of captured output at tag `ref-1`](https://echojustus.github.io/clofin-trace/) —
>   static pages, every figure traced to the fixture it was captured from, with
>   the source commit and that tag's release-audit coverage stated in frame

Against the brief's constraints, which it treats as constraints:

| Constraint | How it is met |
|---|---|
| Link text states what it is — a **replay walkthrough of captured output** | Those words are the link text, not the sentence around it, so they survive being extracted into a link list or a search result |
| At a **named tag** | `ref-1` is in the link text |
| No "demo" | Absent |
| No "live system" | Absent. The only verb is *served*, in the other repository's README; this line has none |
| No "try it" | Absent — there is nothing to try, and §5 of the site's own README says so |
| Survives being read with the site's scope banner in view | Every claim in the sentence is one the banner corroborates rather than contradicts: the banner carries the tag, the short SHA and the coverage label `PARTIAL` in frame, which is precisely what the second clause promises. The sentence promises *less* than the site delivers, which is the correct direction |

The clause **"that tag's release-audit coverage stated in frame"** is doing
deliberate work. `ref-1`'s release audit was partial — charter items 1–4 of 8 —
and the most-read document in the project now links to the walkthrough. Naming
coverage as something the page *states* rather than something the source *has*
keeps `README.md` clear of L-14's shape: it makes no claim about the coverage's
extent, and directs the reader to where the extent is recorded.

Nothing else in `clofin-core` was touched. No code, no contract, no diagram
source, no control.

## 4. Acceptance criteria

| # | Evidence |
|---|---|
| **AC-1** | Run for real, and run **twice** — once against the pre-change check to establish the seam was not hypothetical, once against this branch. §6.2 N-A. Tampered, observed, reverted |
| **AC-2** | §6.2 N-B (committed with no published counterpart) and N-C (published with no committed counterpart). Both directions, both naming the file |
| **AC-3** | §3, and `make docs-check` green — §6.1 |
| **AC-4** | §2.3 |
| **AC-5** | Two files in `build/checks/`, two invocations in each of the two workflows — §2.2. The seam closed inside check 1, per the O-3 ruling |

## 5. What is deliberately absent

| | Why |
|---|---|
| A third CI check in `clofin-trace` | Scope item 7 of TASK-007, the O-3 ruling, and AC-5 |
| An automated test of the negative controls | That would be the third check by another name. Recorded here instead with the reports it produced, as 007-REQ §6.4 did |
| Any change to what the pages render or how the site looks | Out of scope by the brief. `build/build.py` is untouched; the built HTML is byte-identical to before this branch |
| Re-captured fixtures | Out of scope. `fixtures/` is untouched — confirmed by `git diff` in §6.3 |
| Any `clofin-core` code | Out of scope. The diff is one README bullet and this file |
| A digest comparison instead of a byte comparison | A digest would name the file but not where it diverges, and there is no volume argument here: five fixtures, ~1 MB |

## 6. Verification

Every run below finished before this file was written.

### 6.1 `clofin-core`

`make test` routes through `docker compose run --rm toolchain`, and this
environment has no Docker daemon. Rather than skip the suite, the Clojure CLI
was installed locally and **every component of `make verify` was run directly**
against the same aliases the Makefile uses. Reported that way rather than as
"`make verify` green", because the command as written did not run.

| Component | Command | Result |
|---|---|---|
| `test` | `clojure -M:test` | **335 tests, 2064 assertions, 0 failures, 0 errors** |
| `diagrams-check` | `clojure -M:diagrams --check` | `Diagrams OK (5 generated artifact(s) match their sources).` |
| `docs-check` | `sh scripts/check-doc-links.sh` | `Documentation links OK (71 markdown files checked).` |
| `doc-consistency` | `sh scripts/check-doc-consistency.sh` | `Document consistency OK (12 control(s), 22 increment status claim(s), 7 brief(s)).` |

The test count is identical to 007-REQ's, which is the expected result for a
change that touches no code. Integration tests were not run: they need
PostgreSQL, nothing here touches persistence, and `make verify` does not include
them.

### 6.2 `clofin-trace` — the checks against deliberately broken input

Each control was injected into a freshly built `_site`, observed, and reverted.
**The committed `fixtures/` tree was never modified** — every tamper was applied
to the published copy, which is the point. N-A was additionally run against
`git show HEAD:build/checks/provenance_present.py` (restored under a temporary
name inside `build/checks/` so its relative imports still resolved, and deleted
afterwards) to demonstrate the seam rather than assert it.

| # | Injected defect | Pre-change check | This branch |
|---|---|---|---|
| **N-A** | `SGD 3750.00` → `SGD 9999.00` in `_site/fixtures/bundles/settlement-batch-misbehaves.json`, at byte offset 179286 | **`OK … release audit: PARTIAL` — exit 0, green** | exit 1 — `provenance-present FAILED` / `_site/fixtures/bundles/settlement-batch-misbehaves.json: not byte-identical to fixtures/bundles/settlement-batch-misbehaves.json — first differs at byte 179290: committed 0x33, published 0x39` |
| **N-B** | `_site/fixtures/quotations.json` deleted | — | exit 1 — `_site/fixtures/quotations.json: missing — fixtures/quotations.json is committed, and the build did not publish it` |
| **N-C** | `_site/fixtures/bundles/extra.json` added | — | exit 1 — `_site/fixtures/bundles/extra.json: published, but there is no fixtures/bundles/extra.json it could have come from` |
| **N-D** | `_site/fixtures/` removed entirely | — | exit 1 — `_site/fixtures/: the fixtures were not published beside the pages` |

Two things in N-A's row are worth reading twice. The pre-change check reported
**`PARTIAL` coverage and a matching `ref-1 5c7b4ba` stamp while serving a
falsified balance** — the failure was silent, not marginal. And
`disclaimer-verbatim` also returned exit 0 under the same tamper, which is
correct and is the whole argument: no check in this repository read those bytes.

The offsets differ by four (179286 tampered, 179290 first differing byte)
because `SGD 3750.00` and `SGD 9999.00` share their first four bytes. The report
names where the files diverge, not where a human intervened, which is the useful
answer when nobody has told you a tamper happened.

### 6.3 `clofin-trace` — the clean run, after reverting

`_site` was deleted and rebuilt from scratch:

```
$ python3 build/checks/provenance_present.py --fixtures fixtures --site _site
provenance-present OK — 5 page(s) and 5 fixture(s), all stamped ref-1 5c7b4ba,
  release audit: PARTIAL; the published fixtures are byte-identical to the committed ones.

$ python3 build/checks/disclaimer_verbatim.py --fixtures fixtures --site _site
disclaimer-verbatim OK — the captured GET / scope statement appears verbatim
  5 time(s) across 5 page(s), and in README.md.

$ git status --short
 M README.md
 M build/checks/provenance_present.py

$ git diff --stat -- fixtures/
(no output — the committed fixtures are untouched)
```

Two modified files, both intended. No stray `_old_check.py`, no modified
fixture, no `_site` committed (it is git-ignored).

### 6.4 The live site

`https://echojustus.github.io/clofin-trace/` was fetched before the link was
added to `clofin-core`'s README, rather than linked on the assumption that it
was up: **HTTP 200**, serving the `ref-1` walkthrough with `5c7b4ba` and
`PARTIAL` each appearing three times in the page and the captured scope
statement present. The link text's claims were checked against the artifact the
link resolves to.

## 7. Notes for whoever picks this up

- **The residual boundary, stated rather than left to be discovered.** This
  closes the gap between the *committed* fixtures and the *published* ones. It
  cannot close the gap between the bytes the check read and the bytes
  `actions/upload-pages-artifact` uploads a step later — no check can verify an
  artifact assembled after it exits. That remains a property of `pages.yml`'s
  step order, and it is inherent to the class, not a defect in this work. If it
  ever needs closing, the mechanism is a digest computed at check time and
  re-verified against the deployed artifact, which means fetching the deployed
  site — network, and a check that fails offline is one people learn to skip
  (the same reasoning that keeps `check-release-annotation` out of `verify`).
  Recorded so the next person weighs it rather than rediscovers it.
- **Still exactly two checks, and the pressure to make it three is now
  documented twice.** 007-REQ §7 said not to add a third; this brief was the
  first live test of that instruction and it held. If something new needs
  guarding, fold it into the check whose subject it already is, and extend that
  check's docstring in the same commit so the prose and the code keep agreeing.
- **`fixtures/` and `_site/fixtures/` must stay a copy, not a build.** The
  comparison is byte-for-byte and assumes `build.py` publishes the tree
  verbatim. If a future change ever minifies, re-serialises or filters the
  published fixtures, this check will fail loudly and correctly — and the right
  response is to reconsider the transformation, not to loosen the comparison. A
  published fixture that is not the committed file is the thing `verify.html`
  promises does not happen.
- **The negative controls are recorded, not automated, on purpose.** Re-run them
  by hand when this check changes. The four in §6.2 take about a minute and are
  the only evidence the check does anything.
