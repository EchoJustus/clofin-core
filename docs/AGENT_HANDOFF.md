# CloFin — Working Agreement and Handoff Protocol

**Status:** living document · **Audience:** contributors and autonomous working sessions

CloFin is built in small increments, often by sessions that share no memory with
each other. This document is what keeps that coherent. It is the protocol, not a
suggestion.

---

## 1. The rule that everything else follows from

> **The repository is the only source of truth.**

If a decision exists only in a conversation, it does not exist. A future session
will re-derive it differently, and the project will drift. Every decision worth
keeping is written into the repository *before* the code that depends on it.

| Kind of knowledge | Where it lives |
|---|---|
| Why a decision was made, and what was rejected | `docs/ADR/` |
| How the system fits together | `ARCHITECTURE.md` |
| What the product must do, and for whom | `docs/PRD.md` |
| What words mean | `docs/DOMAIN_MODEL.md` |
| What controls exist and what enforces them | `docs/COMPLIANCE.md` |
| What is built and what is next | `docs/ROADMAP.md` |
| The interface | `api/openapi.yaml` |
| What a specific unit of work involves | `docs/briefs/` |
| What is in flight, and who has claimed it | the `Status` table in each brief |
| What a review found, and what was done about it | `docs/audits/` |
| Whether it works | the test suite |

**Which copy is authoritative.** The project-management artefacts — `docs/briefs/`,
`docs/audits/`, `docs/ROADMAP.md` and this document — are maintained on the
dedicated **`meta` branch** (§1b). The copy on `origin/meta` is the current one;
copies of these files on `main` or on feature branches are historical snapshots
from whenever those branches last synchronised, and are never edited in place.
Product and engineering documents (`ARCHITECTURE.md`, ADRs, `PRD.md`,
`DOMAIN_MODEL.md`, `COMPLIANCE.md`, `api/openapi.yaml`) continue to travel with
the code they describe, on the code's own branch.

---

## 1a. Execution model

CloFin runs **asynchronously**. Three roles, none of which blocks the others:

| Role | Responsibility |
|---|---|
| **Master Control** (orchestrator) | Owns the **control plane**: writes briefs in batches ahead of execution on the `meta` branch, maintains `ROADMAP.md` and brief status there, ingests audit feedback, arbitrates disputes about a brief. |
| **Worker session** | Owns one unit of the **data plane**: executes one brief end to end — code, tests, contract, code-adjacent docs — on its own `feat/` branch. Never writes to `meta`. |
| **Principal Architect** (offline) | Audits batches of PRs at milestones and produces `FEEDBACK` files, which land on `meta`. Reviews do **not** gate execution. |

Two consequences worth stating explicitly:

1. **Planning never waits on execution.** Briefs are written ahead, in batches
   of one to five, including for work that is blocked on a dependency. A
   blocked brief names what it is blocked on and is otherwise complete.
2. **Execution never waits on review.** PRs are batched for asynchronous audit
   — one to five may be open and unmerged at once. A worker session takes a
   `READY` brief whose dependencies are met and proceeds, stacking on the
   dependency's branch where necessary (§1b). Audit findings arrive later and
   are ingested per [`audits/README.md`](audits/README.md); an `IMPLEMENTED`
   brief may return to `IN PROGRESS` when a blocking finding lands. That is
   normal, not a failure.

**Claiming and status.** Brief status lives on `meta`, and only Master Control
writes `meta` — so a Worker does not claim a brief by editing it. Master Control
sets the brief to `IN PROGRESS` on `meta` **at dispatch**; being handed the
brief is the claim. A Worker who arrives at a brief without dispatch checks
`origin/meta` for its status and the remote for a branch already implementing it
before starting. Completion is the mirror image: the Worker reports done (PR
opened, `REQ` filed), and Master Control moves the brief to `IMPLEMENTED` on
`meta`. If the brief on `origin/meta` and this table disagree about anything,
`origin/meta` wins.

---

## 1b. Branch topology: control plane and data plane

Three kinds of branch, with different rules:

| Branch | Plane | Written by | Contains |
|---|---|---|---|
| `meta` | Control | **Master Control only** | Briefs, audits (`REQ`/`FEEDBACK` after ingestion), `ROADMAP.md`, this document |
| `feat/*` | Data | One Worker each | Code, tests, migrations, code-adjacent docs (ADRs, OpenAPI) for one brief |
| `main` | Release | Merges only | Whatever has survived review. Always runnable, migrated, green. |

`meta` never carries code and is never a base for a `feat/` branch. It is
periodically merged to `main` at milestones so `main`'s copy of the governance
docs does not rot — but between syncs, `origin/meta` is the authority (§1).

### Reading your brief — without checking `meta` out

A Worker reads control-plane documents directly from the remote ref, leaving its
own working tree untouched:

```bash
git fetch origin meta
git show origin/meta:docs/briefs/NNN-TASK-<feature>.md     # your brief
git show origin/meta:docs/briefs/README.md                  # the backlog table
git show origin/meta:docs/audits/README.md                  # standing lessons
```

Never copy the brief into your feature branch "for convenience" — a copied brief
is a fork of the truth, and the next reader cannot tell which one governs.

### Choosing your base — the stacked data plane

Because PRs are batched for audit, `main` will often lack code your brief
depends on. The rule:

1. **No unmerged dependency** → branch from `origin/main`, open the PR against
   `main`.
2. **Dependency implemented but unmerged** → branch from the **tip of the
   dependency's feature branch**, and open the PR **against that branch**, not
   against `main`. A stacked PR whose base is `main` shows the whole stack as
   its diff, which makes review impossible and audit misleading.
3. Record the stack in the PR description: one line, `Stacked on #<n>`.

Discipline that makes stacking survivable, not optional:

- **When the branch below you changes** (review fixes, audit findings), rebase
  your branch onto its new tip promptly. The longer a stack diverges, the worse
  the eventual conflict.
- **When the branch below you merges**, retarget your PR to `main` (GitHub does
  this automatically if the merged branch is deleted) and rebase onto `main`.
- **Never merge out of dependency order.** The backlog table's `Depends on`
  column is the merge order.
- **Migrations in a stack are still append-only** and numbered in stack order:
  if the branch below you added `0003-…`, yours starts at `0004-…`. Renumbering
  after a rebase is a checksum failure waiting to happen — pick the number that
  is free across the whole stack, not just on `main`.

### Feedback triage — when a Worker disputes a brief

A Worker who finds a flaw in its brief does not edit the brief. It records the
objection in its `REQ` file (on its own feature branch) and reports it.
Master Control arbitrates:

1. Master Control reads the objection and rules on it — the brief is corrected
   on `meta`, or the objection is answered with evidence and the brief stands.
2. If the brief changes, Master Control produces a **specific fix instruction**
   for the Worker: what to change, on which branch, and which acceptance
   criteria now apply. The Worker executes it on its own `feat/` branch.
3. Either way, the ruling is recorded — in the brief's changelog line and, if
   it reveals a recurring trap, in the standing-lessons table in
   [`audits/README.md`](audits/README.md).

The Worker never waits silently on a disputed brief, and never resolves the
dispute unilaterally by diverging from it. Diverging from a brief without a
ruling is a failed handover even when the divergence was right.

---

## 2. Starting a session

Read, in this order — it takes about ten minutes and prevents most rework:

1. `README.md` — what CloFin is, and the scope limits
2. `git show origin/meta:docs/ROADMAP.md` — what is built, what is in flight
3. `git show origin/meta:docs/briefs/NNN-TASK-<feature>.md` — your brief is the
   specification (fetch first: `git fetch origin meta`)
4. `ARCHITECTURE.md` — the shape and the layering rules, **read from your base
   branch**, which under §1b may be a feature branch rather than `main`
5. The ADRs relevant to the area you are touching, likewise from your base

Then pick your base per §1b, and verify it before changing anything:

```bash
make test          # must be green before you start
```

If it is not green on `main`, fixing that **is** the task. If it is not green on
the feature branch you are stacking on, report it to Master Control instead of
building on it — a red base is the owner's problem, not yours to absorb.

---

## 3. Doing the work

### Increments are small and complete

An increment is complete when all of these are true. A partially complete
increment is not merged.

- [ ] Code is written and does what the brief says
- [ ] Tests cover it — property tests for anything with an invariant
- [ ] `api/openapi.yaml` is updated in the **same commit** as the handler
- [ ] Any decision a future contributor would re-derive is in an ADR
- [ ] `make verify` passes
- [ ] `make test-it` passes if the change touches persistence
- [ ] Completion is **reported** — PR opened (base per §1b), `REQ` filed — so
      Master Control can set the brief to `IMPLEMENTED` and update `ROADMAP.md`
      on `meta`. Workers do not edit `meta` themselves.

### Rules that must not be broken

These are the invariants of the codebase, not preferences.

1. **The domain layer is pure.** `clofin.money`, `clofin.ledger.*` and future
   domain namespaces never require `clofin.db.*` or `clofin.http.*`, never read
   a clock, and never generate an identifier. Effects come from the caller.
2. **Money is never a float.** Anywhere. Including in JSON and in the database.
3. **Posted entries are immutable.** Corrections are reversing entries.
4. **Every runtime dependency needs an ADR.** Test-scope dependencies do not.
5. **`main` is always runnable**, migrated, documented and green.
6. **Migrations are append-only.** Never edit an applied migration; the checksum
   check will refuse to start. Add a new one.
7. **No secrets, no real data, ever.** Synthetic only, and say so.

### Commits

Conventional Commits, one logical change per commit:

```
feat:     a capability a user or caller can observe
fix:      corrected behaviour
docs:     documentation only
test:     tests only
refactor: no behavioural change
chore:    tooling, build, dependencies
```

Write the body to explain *why*, not what — the diff already says what.

### Branches

- `feat/<short-name>` or `fix/<short-name>` for work, based per §1b — on
  `main`, or on the dependency's unmerged feature branch when stacking
- `meta` is Master Control's alone; a Worker never commits to it
- `main` stays stable and releasable
- Push regularly; the remote is the backup, not a formality

---

## 4. Writing a brief

Briefs are written by Master Control, on `meta`, in batches of one to five —
**before** execution, so the backlog never runs dry. A brief is a contract: it
must be executable by someone with no access to the conversation that produced
it.

A brief that cannot be executed without asking a question is not finished. The
test: hand it to someone who has read only the repository. If they would have to
ask "which one?" or "where does this go?", answer it in the brief first. Under
the stacked topology this includes naming the **base branch**: a brief whose
dependency is unmerged states which feature branch to stack on.

Name it `NNN-TASK-<short-feature>.md`, numbered sequentially and never
renumbered. Add it to the backlog table in
[`briefs/README.md`](briefs/README.md) in the same commit.

```markdown
# TASK-NNN: <title>

| Field | Value |
|---|---|
| **Increment** | <n> |
| **Status** | `READY` |
| **Depends on** | TASK-NNN, or — |
| **Blocks** | TASK-NNN, or — |
| **Requirements** | PR-nnn, PR-nnn |
| **Controls touched** | C-nn |
| **Scope** | Small / Medium / Large |
| **Audit** | Not yet submitted |

## Objective
One paragraph. What will be true afterwards that is not true now.

## Context you need
Which ADRs govern this. Which namespaces exist already and what they do.
What the reader should *not* need to go and find out.

## Scope
### In
- Concrete, checkable items.
### Out
- Named explicitly, with a reason. This is what stops scope creep.

## Interfaces
Function signatures, request and response schemas, SQL shapes. Exact enough to
implement against without guessing.

## Acceptance criteria
Given / When / Then. Each one testable. Each traced to a PR-nnn.

## Definition of done
The increment checklist above, plus anything specific to this work.

## Notes for whoever picks this up
The traps. Where a reasonable person would take a shortcut that this project
has already decided against, and why. This is where audit findings accumulate.
```

### Ingesting audit feedback

When a `FEEDBACK` file lands in `docs/audits/` **on `meta`**, Master Control
reads it in full before touching anything, then triages every finding as
actioned, deferred with a stated reason, or disputed with evidence — all three
are legitimate, silence is not. Findings that require code changes become fix
instructions dispatched to the owning Worker's feature branch (§1b); findings
about the docs are applied on `meta` directly.

The step with lasting value is the last one: a flagged anti-pattern is added to
the *Notes for whoever picks this up* section of **every brief where it could
recur**, so it is prevented rather than re-corrected. Full protocol in
[`audits/README.md`](audits/README.md).

---

## 5. Deciding for yourself, versus asking

Autonomy is the default. Decide, record the decision in an ADR, and continue.

**Decide yourself:** library-free implementation details, naming, test strategy,
schema shape, error taxonomy, sequencing within an increment.

**Ask first — only these:**

1. A change to what the product *is* — a different domain, a different user, a
   different core claim.
2. Anything requiring confidential or personal information.
3. Anything creating an external commitment: publishing a package, registering a
   domain, contacting a third party, or anything implying a real institutional
   relationship.

When you decide something significant, the ADR is not optional. It is the
mechanism that makes autonomy safe.

---

## 6. Things that would damage this project

Stated plainly, because they are the failure modes that matter more than bugs.

| Never | Why |
|---|---|
| Claim CloFin handles real funds, or is connected to any institution or central bank | It is false, and a single such claim discredits everything else in the repository. |
| Imply regulatory approval, licensing, or a compliance attestation | Same. `docs/COMPLIANCE.md` is a modelling exercise and says so. |
| Include real client, employer or personal data | Synthetic only. No exceptions. |
| Name a specific employer, client, jurisdiction-specific project, or public body | The project is deliberately geography- and client-neutral. |
| Merge a red build | `main` must be trustworthy, or none of this demonstrates anything. |
| Add a runtime dependency without an ADR | The dependency argument is one of the project's stated positions. |
| Fake a passing test, or report work as complete when it is not | The whole value here is that the claims are checkable. |

---

## 7. Reporting back

At the end of a session, state:

- what changed, and which requirement or brief it satisfies
- test results — actual numbers, actual pass or fail
- anything left incomplete, and why
- any decision taken, and where the ADR for it is
- what the next session should pick up

Report what happened, not what was intended. If something did not work, say so
and say what was tried.
