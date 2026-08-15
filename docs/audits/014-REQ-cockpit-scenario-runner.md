# 014-REQ — cockpit phase 4: the Actions scenario runner, without a token

| Field | Value |
|---|---|
| **Brief** | `docs/briefs/014-TASK-cockpit-scenario-runner.md` — on **`origin/meta`**, which is the authoritative copy and is not edited here |
| **Increment** | 8.4 (cockpit, phase 4) — **`clofin-cockpit` only**; `clofin-core`'s code is frozen |
| **Requirements** | Driver D5; the cockpit plan phase 4, in the PAT-free form ruled at dispatch |
| **Controls** | **None move.** The cockpit demonstrates controls by driving them and enforces nothing. `docs/COMPLIANCE.md` is untouched |
| **Base** | `clofin-cockpit` `main` at `7ee7e28`; `clofin-core` `main` at `f533897`, **read and driven at `ref-1` (`5c7b4badced5e807e1022fce44cbcad38c6d2095`), never edited** |
| **Branch** | `claude/cockpit-headless-runner-scqmyj` in both repositories |
| **Pull requests** | `clofin-cockpit` **#4** · `clofin-core` **#27** — this file, alone — cross-referenced in both descriptions |
| **`clofin-core` diff** | **This file and nothing else.** `git diff origin/main --name-only` on this branch lists one path: `docs/audits/014-REQ-cockpit-scenario-runner.md` |
| **Model** | An Anthropic Claude model. **The identifier is deliberately not written into this file**: this session operates under a rule that keeps the model identifier out of repository artifacts. Recorded as an absence rather than omitted silently, because the provenance column exists to say what is known and this is a thing that is known and withheld |
| **Reasoning effort** | High — extended thinking enabled throughout. The harness does not expose a numeric setting to the session, so this is reported as the mode, not as a measured value |
| **Date** | 2026-08-15 |
| **Verification still in flight** | **Nothing in this session.** One item is outstanding and it is Master Control's by ruling: the hosted dispatch against `ref-1`, which GitHub's default-branch rule makes impossible before merge. See [§6a](#6a-the-hosted-dispatch--impossible-before-merge-by-construction) and [§9](#9-verification-l-9) |

---

## 1. The frozen-core rule, and what was done about it

The brief's first instruction was that this increment does not modify the audit
subject. It does not. `clofin-core` was **checked out at `ref-1`, run, and
driven hard** — the whole 013 walk, twice to completion and three times
partially, against a real PostgreSQL 16 — and **not edited**. `git diff
origin/main --name-only` on this branch lists exactly one path: this file.

The workflow that does the driving is built so that "never edited" is
structural rather than promised. It holds `contents: read` and nothing else;
both checkouts run with `persist-credentials: false`, so neither working tree
carries a credential to push with; no step commits, pushes, tags, comments or
opens anything; and every action is pinned to a commit id rather than to a tag
that could move under it.

One thing that would have been a `clofin-core` change was found. It was not
made. It is filed in [§4](#4-objections).

## 2. What was built

All of it in `clofin-cockpit`.

| Piece | Where |
|---|---|
| The workflow: dispatch, checkout at a ref, boot, run, publish | `.github/workflows/scenario-run.yml` |
| The headless entry point — arguments, documents, the walk, the exit code | `headless/run.ts` |
| The driver: the operator's hands, and nothing else | `headless/drive.ts` |
| The playbook format, its reader, and its cross-check against a flow | `headless/playbook.ts` |
| A manual step's SQL, run without a shell and printed verbatim | `headless/sql.ts` |
| The run's own re-check of every figure it is about to publish | `headless/figures-check.ts` |
| The evidence, as a job summary | `headless/summary.ts` |
| The anonymous-read probe, and what it refuses to conclude | `headless/anonymous.ts` |
| The shipped playbook — the full 013 scheme sequence | `playbooks/scheme-play.playbook.json` |
| The four-state vocabulary, moved to where the four states are defined | `src/bootstrap.ts` (from `src/views-run.ts`) |
| Recent runs, read anonymously, or the reason there is no list | `src/runs.ts`, `src/cockpit-repo.ts` |
| The batch-runs page | `src/views-batch.ts`, `src/main.ts`, `static/styles.css` |
| The two checks, over more files | `tools/check-scope-verbatim.mjs`, `tools/check-no-unqualified-audited.mjs` |
| The decision | `docs/ADR/0004-the-headless-entry-two-declared-differences-and-a-playbook-that-cannot-invent-an-answer.md` |
| Tests | `test/drive.test.ts`, `test/playbook.test.ts`, `test/summary.test.ts`, `test/runs.test.ts` (new) |

### One runner, headlessly

`headless/run.ts` is an **entry point, not an engine**. `profiles.ts` reads the
documents, `bootstrap.ts` starts and advances them, `acting.ts` holds identity,
`figures.ts` projects every figure, `net.ts` makes every request, and
`instance.ts`'s honesty gate decides whether there is a connection at all — an
address whose `GET /` carries no disclaimer is refused headlessly exactly as it
is on screen. `headless/drive.ts` contains no request, no expectation rule, no
status vocabulary and no arithmetic; its whole logic is what to do when the
runner reports `waiting for you`.

A second engine would have been much easier to write. It would also have been a
second place for the halting rule, the four states, the raw-exchange discipline
and the actor invariant to be *almost* implemented — in the one setting where
nobody is looking at the screen.

**The vocabulary moved to where it belongs.** `STATUS_WORDS` was a private
constant in `views-run.ts`, which was correct while there was one reader. It is
now exported from `bootstrap.ts`, beside the type it names, so the run screens
and the batch summary render the same word for the same state from one table.

### Two declared differences, and only two

**Manual steps.** The workflow is the operator, so it runs the statements the
runner generated — piped into `clofin-core`'s own composed PostgreSQL, as an
argv array with no shell, printed verbatim in the summary. **The confirmation
half is unchanged**: `verifyManualStep` runs, as the actor the profile names,
and the step advances on the instance's answer. The summary renders both halves
under *performed by the workflow, confirmed by the instance*.

**Choices.** Answered from a versioned playbook, which declares every answer
**with its reason, before the run**, in a file a reader can diff against the
repository before reading a single result. The summary renders each as
*declared → performed → the instance answered*.

The honesty argument is not that a declaration is as good as a click. It is
that a batch's honesty is a different thing: an interactive walk is honest
because you watched the decision being made; a batch run is honest because the
decision was written down in public *before anybody knew what it would produce*.

### The playbook cannot invent an answer

A choice the playbook does not cover **stops the run**: the runner stays in
`waiting for you`, and the job fails naming the step and listing the options it
did not choose between. No default, no first-one-wins, no ordering rule.

The cross-check that runs **before the first request** deliberately does not
require a playbook to cover every choice. An incomplete playbook is a legal
document; making it illegal there would replace the behaviour worth
demonstrating with a message about it. What the cross-check does catch, before
anything is written to anybody's instance, is an answer naming a step or an
option that does not exist.

`playbooks/` is **not copied into the built site**, so a browser cannot fetch
one even if a future view tried. Auto-play in the browser stays refused.

### Figures, checked twice

`figures.test.ts` asserts verbatim-ness against recorded bodies at build time.
A batch run additionally re-checks **every figure it is about to publish**
against the live body it came from, and fails the job if one is not a substring
of its own source. A figure the instance did not send reports as absent rather
than as zero, and that is not a failure: treating it as one would push the next
contributor toward inventing a value to make a check pass.

## 3. The evidence a run publishes

The summary opens with the scope statement, verbatim, from the one constant —
above anything interesting enough to be screenshotted. Then the requested ref,
the **resolved 40-character commit**, and that commit's release-audit coverage,
matched by `releases.ts` and `coverage.ts` — the same modules the release
browser uses — out of the release body's own `RELEASE AUDIT:` paragraph.

**Where the release documents came from is printed rather than assumed.** The
workflow asks the public API with no credential first, exactly as the page
does; if that is refused it re-asks with the automatic job token and the summary
says so in the same table, in the same words, with the status the anonymous
attempt got. A hosted runner shares its address and GitHub's unauthenticated
allowance is per address, so that fallback is about somebody else's traffic
rather than about this repository's convenience.

Then every step: the actor whose id its requests carried, the runner's own
four-state word, every request with its **raw status**, every figure **verbatim
in minor units**, the raw exchanges collapsed beneath, and the `curl` that
repeats each one. Refusals render as the expected outcomes they are. Manual
steps carry their SQL and the confirming request. Each document's *what this
cannot show* list is rendered whole.

## 4. Objections

One, **dispatch-blocked** by the frozen-core rule: filed, worked around inside
the cockpit by saying plainly what cannot be done, and not built.

### O-1 — an organisation cannot be found by its short name, so a batch run can only ever run against a fresh database

`POST /organisations` refuses a second organisation with the same short name.
That refusal is correct and the cockpit renders it as the `already-present`
outcome it is. The problem is what a client can do next: **nothing**. Checked
against a live instance at `ref-1`:

```
$ curl -sS -i -X POST http://127.0.0.1:8080/organisations \
    -H 'content-type: application/json' \
    -d '{"legalName":"Probe Conflict Ltd","shortName":"probe-conflict-1"}'
409 Conflict
{"type":"https://clofin.dev/problems/conflict","title":"Conflicting state",
 "status":409,"detail":"An organisation with this short name already exists",
 "instance":"f491a95b-…","errors":{"short-name":"probe-conflict-1"}}

$ curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/organisations
405
$ curl -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8080/organisations?shortName=probe-conflict-1'
405
```

The `409` echoes the short name that was **sent** and does not carry the
existing organisation's id; there is no collection read; and
`GET /organisations/{id}` needs the id the caller is trying to obtain. So a
client that has lost the id — which is every client that did not create the
organisation in this process — cannot recover it through the API at all.

For a browser this is a nuisance recorded in the profile's own conflict note.
For a **batch runner it is a structural bound**, and it is worth stating as one:

- every dispatch must start its own database, and this workflow does;
- a scenario run therefore **cannot re-verify a long-lived instance** — it can
  only ever demonstrate against a fresh one;
- a re-run against a persisted instance stops at step 1, correctly, with the
  instance's own `409` on the page. That was run for real; §7 control 2.

**Not fixed.** The fix is a `clofin-core` change to the audit subject inside the
audit gap.

**Suggested disposition, with a caveat that belongs to the control rather than
to convenience.** The obvious remedy — putting the existing id into the `409`
body — hands an identifier to an **unauthenticated** caller, because
`POST /organisations` is unauthenticated by design. That is a disclosure
decision, not a formatting one. The narrower remedy is an **authenticated**
lookup (`GET /organisations?shortName=…`, requiring an actor with
`organisation/read`), which gives an operator who holds a seeded actor id a way
back to the organisation that actor belongs to, and gives an anonymous caller
nothing new. Either way it is remediation-batch work after the Sol audit, not
now.

## 5. Notes and readings recorded

**N-1 — a batch run is not a browser, and sees response headers a page may
not.** 013's O-1 recorded that `Idempotent-Replayed` is absent from this
instance's exposed-headers list, so no browser page can read it. A run on a
server is not subject to that rule at all: `net.ts` records every response
header the platform gives it, and headless that is all of them. The shipped
flows do not perform the double submission, so this run does not demonstrate
C-06 either — but the *reason* is now "the flow does not attempt it" rather
than "the page cannot see it", and a future flow could. Recorded because it is
a real asymmetry between the two ways this repository drives the same API, and
because the batch-runs page had to say so rather than let a reader assume the
two are equivalent.

**N-2 — `ref-1` predates `sourceCommit`, so the oldest tag cannot corroborate
what it is running.** `GET /` at `5c7b4ba` carries `service`, `description`,
`environment`, `disclaimer` and `documentation`, and no `sourceCommit` —
ADR-0027 added that field later. The cockpit's reader already handles this: it
reports `(not reported)` rather than silently substituting anything. The
consequence for a batch run is worth stating plainly: for tags older than
ADR-0027, the link between "the commit this job checked out" and "the code
answering these requests" rests on the **workflow's own checkout**, not on the
instance's word. The summary prints the two values in separate rows and says
which is which rather than reconciling them. No change requested.

**N-3 — a scenario is offered against any ref, and whether that ref implements
it is the instance's business.** The workflow's `scenario` input lists the three
shipped flows. `ref-1` has settlement but not reconciliation — migration `0012`
is later — and the reconciliation endpoints answer `404` there:

```
404  /settlement-statements?organisationId=x
404  /reconciliation-statements
404  /reconciliation-breaks/x/assignment
```

The runner needs no capability table for this: a run of `reconciliation`
against `ref-1` halts at its first step with the instance's own `404`, names
the step, and fails the job. That is the correct behaviour and it is better
than a curated compatibility matrix in this repository, which would be a claim
about `clofin-core` that this repository is not entitled to make and would go
stale. Recorded so the halt is read as designed rather than as a defect.

**N-4 — the unauthenticated GitHub allowance is per network address, and that
is not a fact about this project.** It is 60 requests an hour, shared by
everything on the address. During this increment the same anonymous endpoint
answered `200` and `403 rate limited` within minutes of each other from the same
sandbox. Two consequences were designed for rather than discovered later: the
probe distinguishes *the endpoint is not served without a credential* from *this
address's allowance is spent* and refuses to report the first when the second
happened; and the workflow's release read asks anonymously first and states in
the summary when it had to fall back.

**N-5 — the blunt-comment trap, a third time, and the check that caught it was
the one being extended.** 011-REQ N-5 recorded three comments failing a check;
013-REQ N-5 recorded `figures.ts`'s doc comment failing the build for naming
the formatters it refuses. This increment extended `no-unqualified-audited`
over `headless/`, and its **first run failed on a sentence in
`headless/run.ts`** — a sentence explaining what a run may not be called, which
used the word it was explaining. It was rewritten to describe the word without
using it, with a comment saying why, exactly as `figures.ts` does. The rule
working bluntly on a sentence about itself is the rule working, and the fact
that the extension caught something on its first run is the best evidence
available that the extension was worth making.

## 6. The runs

### 6a. The hosted dispatch — impossible before merge, by construction

The brief's definition of done asks for a real dispatch against `ref-1`, linked
here with its summary quoted. **It could not be performed from this branch**, and
the reason is a property of GitHub rather than of this increment.

A `workflow_dispatch` is only dispatchable once the workflow file is on the
repository's **default branch**. Until then GitHub does not register the
workflow at all. Both halves of that were checked rather than assumed, in this
order:

```
POST /repos/EchoJustus/clofin-cockpit/actions/workflows/scenario-run.yml/dispatches
  { "ref": "claude/cockpit-headless-runner-scqmyj",
    "inputs": { "ref": "ref-1", "scenario": "scheme-play", "seed": "uat-standard" } }
→ 404 Not Found

GET /repos/EchoJustus/clofin-cockpit/actions/workflows
→ 2 workflows: .github/workflows/ci.yml, .github/workflows/pages.yml
```

The `404` was **not** taken as evidence on its own, because a malformed
workflow file produces the same answer: an unregistered workflow and a
registered-but-broken one are indistinguishable from the dispatch endpoint. The
file was parsed independently first — valid YAML, one `workflow_dispatch`
trigger, the three declared inputs, one job, eleven steps — and only then was
the `404` attributed to the default-branch rule. The workflow list above is the
corroboration: the branch's workflow is absent from it while both `main`
workflows are present.

**Master Control's ruling, 2026-08-15:** *merge-then-verify, the Pages
precedent from TASK-011. GitHub's default-branch constraint makes a pre-merge
hosted run impossible by construction, and your local end-to-end run against a
real `ref-1` stack covers the runner logic. Master Control merges both PRs,
triggers the real dispatch against `ref-1` immediately, and the task is not
`CLOSED` until that run's summary is verified — any failure is fixed forward.*

So the definition-of-done item stands **open by ruling, not by omission**, and
this file says so rather than quoting a run that has not happened. What the
hosted run will exercise that the local one did not is stated plainly in
[§6c](#6c-what-the-hosted-run-will-exercise-that-the-local-one-did-not), so
nobody has to work it out from the diff.

### 6b. The local end-to-end run, against a real `ref-1` stack

Everything except the hosting was performed for real: `clofin-core` at
`5c7b4badced5e807e1022fce44cbcad38c6d2095`, checked out into a detached
worktree, running on a real PostgreSQL 16 that was dropped and re-migrated
immediately beforehand, driven by the compiled headless entry point with the
shipped seed profile and the shipped playbook — the 012/013 host-run shape, and
the same shape the workflow falls back to when the composed image will not
build.

```
$ node tmp/headless/headless/run.js \
    --base-url http://localhost:8080 --ref ref-1 \
    --sha 5c7b4badced5e807e1022fce44cbcad38c6d2095 \
    --seed uat-standard --scenario scheme-play \
    --playbook playbooks/scheme-play.playbook.json \
    --psql '["psql","-v","ON_ERROR_STOP=1","-h","127.0.0.1","-p","5432","-U","clofin","-d","clofin"]'
…
every document finished and every figure is the instance's own.
EXIT=0
```

The summary it wrote is 134,854 characters. It opens:

```markdown
# Scenario run — scheme-play against ref-1

> CloFin operates on synthetic data only. It is not connected to any bank,
> payment scheme or central bank, holds no regulatory authorisation, and
> never processes real funds.

Quoted from clofin-core GET / — the disclaimer field. It is one constant in
this repository, rendered here and into every page of the cockpit; nothing in
this summary may soften it.

**Result: every document finished — 27 step(s) across 3 document(s).**

## What was run, and against what

| Requested ref | `ref-1` |
| Resolved commit | `5c7b4badced5e807e1022fce44cbcad38c6d2095` |
| Release-audit coverage | not checked — the published releases could not be read
  (GitHub's unauthenticated rate limit for this network is spent. The cockpit
  holds no token to raise it — by design.), so this commit was not compared
  with any tag |
| Where those release documents came from | asked of the public GitHub API by
  this run, carrying no credential — and not answered |
| sourceCommit, as the instance reports it | `(not reported)` — self-reported,
  not attested |
| Documents run, in order | `uat-standard` → `payment-maker-checker` → `scheme-play` |
| Playbook | `playbooks/scheme-play.playbook.json` — scheme-play-full v1.0.0 |
```

**Read that coverage line, because it is the fail-closed rule working rather
than a shortfall.** This sandbox's egress address had spent its unauthenticated
allowance (N-4), so the release body could not be read, and the summary says
*not checked* — it does not fall back to a cached value, to `target_commitish`,
or to anything that reads as a clean bill of health. `ref-1` reading `PARTIAL —
charter items 1-4 of 8` is asserted by `summary.test.ts` against the recorded
release body, and the hosted run is where it is asserted against the published
one, which is exactly why §6c lists it.

A manual step, both halves:

```markdown
#### 2. seed-actors-and-roles — **done**

- **Performed by:** Sam (controller) · controller · 9af5bd41-…
- **The runner's own words:** Confirmed by the running instance: 200 OK. The
  instance accepted this actor id, resolved it to an actor in this
  organisation, and found the controller role's account/read permission on it.
- **Handed over:** nobody → sam — seed-actors-and-roles is confirmed by asking
  the instance as sam. The runner sent nothing until this happened.
- **Performed by the workflow, confirmed by the instance.** CloFin has no
  endpoint for this step, deliberately. …

| # | Request | Raw status |
| 1 | `GET /accounts?organisationId=4a7867c4-…` | 200 OK |
```

A choice, declared before it was taken, and a refusal rendering as the expected
outcome it is:

```markdown
#### 5. item-b-contradiction — **done**

- **Declared → performed → answered:** the playbook declared
  `contradict-b-settled` before the run; the runner took `contradict-b-settled`
  (Claim it settled after all — reference SIM-CON-B); the instance answered
  409 Conflict.

| 1 | `POST /settlement-batches/5121005d-…/scheme-responses` | 409 Conflict |

| Readout | Figure | Path | Value, as the instance sent it |
| 1300-IN-TRANSIT   | closingBalance | `closingBalance` | `{"currency":"SGD","minorUnits":50000}` |
| 1100-CLIENT-FUNDS | closingBalance | `closingBalance` | `{"currency":"SGD","minorUnits":-100000}` |
| 2100-CLIENT-PAYABLE | closingBalance | `closingBalance` | `{"currency":"SGD","minorUnits":-50000}` |
```

And silence, which sends nothing and still reads all three accounts:

```markdown
#### 6. item-c-silence — **done**

- **The runner's own words:** You chose: Send nothing at all. No request was
  sent. No request was sent, so this step demonstrates only that the cockpit
  sent nothing. …

No request was sent at this step.
```

The run's own re-check of everything it published:

```markdown
32 figure(s) checked, 32 verbatim.
```

**The clearing account through the whole scheme sequence**, every value a
projection of a response body in that run, and identical to the walk 013-REQ §6
performed by hand in a browser:

| After | `1300-IN-TRANSIT` |
|---|---|
| the batch is released | `{"currency":"SGD","minorUnits":150000}` |
| item A settles | `{"currency":"SGD","minorUnits":100000}` |
| the duplicate delivery | `{"currency":"SGD","minorUnits":100000}` — unchanged |
| item B returns | `{"currency":"SGD","minorUnits":50000}` |
| the contradiction's `409` | `{"currency":"SGD","minorUnits":50000}` — unchanged |
| silence about item C | `{"currency":"SGD","minorUnits":50000}` — unchanged |
| the timeout sweep | `{"currency":"SGD","minorUnits":50000}` — still in flight |
| the late answer | `{"currency":"SGD","minorUnits":0}` |

### 6c. What the hosted run will exercise that the local one did not

Stated as a list so the merge-then-verify gap is a known quantity rather than a
shrug. Everything below is **workflow YAML and hosting**, not runner logic:

1. `actions/checkout` of `clofin-core` at an input-supplied ref, and
   `git rev-parse HEAD` resolving it — locally this was a `git worktree` at the
   same commit, and the SHA was passed in.
2. **`make up`** — the composed stack, including building `infra/Dockerfile`.
   Locally the fallback shape was used, because this sandbox has no Docker
   daemon. Which path a run took is stated in its summary either way, and the
   fallback is the one 013 recorded and this run exercised.
3. The `docker compose exec … psql` argv, as opposed to the host `psql` argv
   used locally. Same statements, same runner, different client invocation.
4. **The release read answering**, and with it `ref-1` rendering `PARTIAL —
   charter items 1-4 of 8` from the published body rather than *not checked*.
   This is the one acceptance criterion whose evidence is entirely in the
   hosted run.
5. The anonymous-read probe answering from an address that is not this
   sandbox's — §8's evidence establishes that the endpoint is served
   anonymously; the probe in the hosted run records what that runner saw.
6. `$GITHUB_STEP_SUMMARY` rendering, and the summary's size against GitHub's
   cap. The local summary is ~135 KB against a ~900 KB budget with a stated
   drop rule above it, so this is a margin check rather than a risk.

## 7. The negative controls, run for real

| # | Control | Result |
|---|---|---|
| 1 | A playbook missing one answer (`item-c-late-answer` removed), run against a fresh database | **The run stopped there and the job failed.** `FAILED item-c-late-answer: the run is waiting for you at "item-c-late-answer" and the playbook scheme-play-incomplete does not answer it. It offers late-settled, late-returned. Nothing was sent…` — exit `1`, the step rendered `waiting for you`, and the summary's first line names the document and the step |
| 2 | A second run against a database that already held the organisation | **The run stopped at step 1 and the job failed.** `FAILED create-organisation: 409 Conflict — This instance already has an organisation with this short name…` — exit `1`. This is O-1's consequence, demonstrated rather than described |
| 3 | The extended `no-unqualified-audited`, on its first run over `headless/` | **The check failed**, naming a real unqualified sentence in `headless/run.ts` (N-5). Fixed by rewriting the sentence, not by narrowing the rule |
| 4 | A playbook naming an option the flow does not offer | **Refused before the first request**, by `checkAgainstProfile`; asserted in `drive.test.ts` by counting requests at a stubbed `fetch` — the count is zero |
| 5 | Every halt in `drive.test.ts` | Asserted by **counting requests at the stub**, not by reading a status: an unanswered choice sends nothing and the step after it is never attempted; SQL that did not apply never sends its confirmation; a refused confirmation leaves the step in `waiting for you` |

## 8. Acceptance criteria

| # | Criterion | Where it is met |
|---|---|---|
| AC-1 | A dispatch against `ref-1` with the shipped seed and playbook completes; the summary carries the scope statement, the resolved full SHA, `PARTIAL` coverage and every step's actor, raw status and verbatim figures | **Partly, and the remainder is open by ruling.** §6b is the whole scenario against a real `ref-1` stack — scope statement, resolved full SHA, and every step's actor, raw status and verbatim figures, exit `0`. The `PARTIAL` coverage line and the hosting are §6c, in the dispatch Master Control runs after merge (§6a). Not claimed as met here |
| AC-2 | Every figure appears verbatim in a captured response body, asserted by the run itself | `headless/figures-check.ts`; the summary's re-check table; the job fails on a figure that is not a substring of its own source. 32 figures checked, 32 verbatim, in the local reference run |
| AC-3 | A choice the playbook does not answer halts `waiting for you` and fails the job naming the step | §7 control 1, run for real; `drive.test.ts` asserts it by counting requests |
| AC-4 | A manual step shows the SQL that was run **and** the API confirmation its status rests on | §3; the summary renders the command, the statements, the client's output and the confirming request under *performed by the workflow, confirmed by the instance* |
| AC-5 | `clofin-core` is checked out read-only at the resolved SHA and nothing writes to any repository | §1; `contents: read`, `persist-credentials: false` on both checkouts, no writing step, actions pinned by commit id |
| AC-6 | The batch-runs page describes dispatch as a github.com action, asks for no token, and either lists runs from anonymous reads or states why not — checked rather than assumed | §3 and the checked evidence below; `src/runs.ts` has two outcomes and no token path; `runs.test.ts` |
| AC-7 | Both checks green and still exactly two; the only `clofin-core` diff is this file | §9; `git diff origin/main --name-only` lists one path |

**AC-6, checked rather than assumed.** The claim that a public repository serves
this list without a credential was settled by asking, from a process whose
requests carry no credential:

```
GET https://api.github.com/repos/EchoJustus/clofin-cockpit/actions/workflows/ci.yml/runs?per_page=3
Accept: application/vnd.github+json      (and nothing else)

200  x-ratelimit-remaining: 24      total_count=13
```

and, minutes later from the same address once the allowance was spent:

```
403 Forbidden   x-ratelimit-limit: 60   x-ratelimit-remaining: 0   x-ratelimit-resource: core
```

The second answer is what identifies the first: `x-ratelimit-limit: 60` is the
**unauthenticated** bucket, so the `200` was an unauthenticated `200`. The
endpoint is served anonymously for this repository; the allowance is small and
shared, which is why the page renders GitHub's own answer when there is no list
rather than an empty box, and why nothing on it offers to raise the limit.

## 9. Verification (L-9)

**What I ran, and its results.**

- `clofin-cockpit` `npm run build` — **239 tests, 239 passed, 0 failed** (189 at
  the end of 013); type-check clean across all three projects; network guard
  clean; `_site` emitted.
- `npm run check:scope-verbatim` — OK. Verbatim once across 1 page and in
  `README.md`; no near-copy in 5 profile documents **or in 8 batch-run files**.
- `npm run check:no-unqualified-audited` — OK. 4 assurance claims across 34
  built files **and 8 batch-run files**, every one qualified. Its first run over
  the new files failed on a real sentence; see N-5.
- The whole scenario driven end to end against `clofin-core` at `ref-1` on a
  real PostgreSQL 16 — **exit 0, 27 steps across 3 documents, 32 figures all
  verbatim**, with the database dropped and re-migrated before the reference
  run. The balances track 013-REQ §6 exactly: `1300-IN-TRANSIT` `150000` after
  release, `100000` after item A settles, unchanged after the duplicate,
  `50000` after item B returns, unchanged after the contradiction's `409`,
  unchanged through silence and the sweep, `0` after the late answer.
- The five negative controls in §7.
- The local end-to-end run in §6b. **The hosted dispatch was not run by this
  session and is not claimed** — §6a records why, and the ruling that follows
  from it.
- `clofin-core`: **nothing was run against its test suite, because nothing in it
  was changed.** `make verify` was not executed and is not claimed. The
  repository was used as a running system, not modified.

**What is still running, and what is outstanding.**

Nothing is still running **in this session**. There is no self-review,
adversarial pass or long test run outstanding on this increment: every check,
control and run reported above finished before this file was written, and their
results are the ones quoted. Both PRs' CI was triggered by the commits they
carry, and `clofin-cockpit` #4's `Build the cockpit and check it` was
**green** at `18:09:57Z` on the commit this file describes.

L-9 is about a Worker's declared verification, and this one has a different
shape, so it is stated rather than left to be inferred: **the outstanding
verification is Master Control's, by Master Control's own ruling, and it is
after the merge rather than before it.** The hosted dispatch against `ref-1`
(§6a, §6c) is the last piece of AC-1, and the ruling is explicit that the task
is not `CLOSED` until that run's summary is checked and that any failure is
fixed forward. So the merge precondition is not being waived here — it does not
apply, because this session holds nothing back. What does apply is that
`IMPLEMENTED` is the right status for this increment and `CLOSED` is not, until
that summary exists and has been read.

If that run fails, the likely causes are enumerated in §6c and all six are
workflow YAML or hosting rather than runner logic — which is the honest bound
on what the local run proved, and the reason §6c exists.

## 10. What the next session should pick up

- **The hosted dispatch against `ref-1`, first.** It is the last piece of AC-1
  and the reason this increment is `IMPLEMENTED` rather than `CLOSED` (§6a,
  §9). Dispatch `Scenario run` with `ref-1` / `scheme-play` / `uat-standard`,
  read the summary, and check four things against §6b: the coverage line reads
  `PARTIAL — charter items 1-4 of 8` from the published release body; the
  resolved commit is `5c7b4bad…` in full; the figure re-check reports every
  figure verbatim; and `1300-IN-TRANSIT` walks `150000 → 100000 → 100000 →
  50000 → 50000 → 50000 → 50000 → 0`. A difference in any of those is a finding,
  not a formatting change. §6c is where to look first if it fails.
- **O-1**, if Master Control rules it actionable: an authenticated lookup by
  short name, not an enriched unauthenticated `409`. Post-audit remediation
  work by the frozen-core rule, not now.
- **013's O-1 and O-2** remain open on the same terms.
- **A playbook per scenario.** Only `scheme-play` has choices today, so only it
  has a playbook. A flow that gains a choice gains an unanswered one, and the
  next run stops there until somebody decides — by design, and worth knowing
  before it happens.
- **The Codespaces driver and PAT handling**, still their own phase. The
  README's rule 3 stays forward-tensed and was re-checked this increment: the
  scenario runner handles no token either.
- The flow documents still carry literal value dates (`2026-12-01`). A batch run
  makes this sharper than a browser did: the day they fall into the past, every
  dispatch of `payment-maker-checker` and everything downstream of it fails with
  the instance's `422` naming `valueDate`. Legible, and still a failure, and now
  one that fires unattended.
