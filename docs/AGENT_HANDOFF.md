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
| Whether it works | the test suite |

---

## 2. Starting a session

Read, in this order — it takes about ten minutes and prevents most rework:

1. `README.md` — what CloFin is, and the scope limits
2. `docs/ROADMAP.md` — what is built, what is next
3. `ARCHITECTURE.md` — the shape and the layering rules
4. The ADRs relevant to the area you are touching
5. `docs/briefs/` — if a brief exists for the work, it is the specification

Then verify the baseline before changing anything:

```bash
make test          # must be green before you start
```

If it is not green, fixing that **is** the task. Never build on a red baseline.

---

## 3. Doing the work

### Increments are small and complete

An increment is complete when all of these are true. A partially complete
increment is not merged.

- [ ] Code is written and does what the brief says
- [ ] Tests cover it — property tests for anything with an invariant
- [ ] `api/openapi.yaml` is updated in the **same commit** as the handler
- [ ] Any decision a future contributor would re-derive is in an ADR
- [ ] `docs/ROADMAP.md` reflects the new state
- [ ] `make verify` passes
- [ ] `make test-it` passes if the change touches persistence

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

- `feat/<short-name>` or `fix/<short-name>` for work
- `main` stays stable and releasable
- Push regularly; the remote is the backup, not a formality

---

## 4. Writing a brief

When a unit of work is large enough that an independent session should pick it
up, write a brief in `docs/briefs/` **before** starting. A brief is a contract:
it must be executable by someone with no access to the conversation that
produced it.

A brief that cannot be executed without asking a question is not finished.

```markdown
# Brief: <title>

**Increment:** <n>  **Estimated scope:** <small | medium | large>
**Requirements:** PR-nnn, PR-nnn

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
```

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
