# TASK-005: Audit coverage completion — ledger and organisation writes

| Field | Value |
|---|---|
| **Increment** | 4 (completion) — closes debt TASK-003 named, it does not open new product surface |
| **Status** | `READY` |
| **Depends on** | TASK-003 — `clofin.audit` and the authenticated principal must exist |
| **Base branch** | `claude/authorisation-audit-trail-r5fzw3` (PR #5, stacked on PR #4) — stack on its tip, open the PR against that branch, per AGENT_HANDOFF §1b. `6f58857` at authoring; the FEEDBACK-M1 remediation moves the tip — build on the tip as you find it, verified green. If PR #5 retargets or rebases, follow it |
| **Blocks** | Nothing hard; C-05 stays scope-qualified until this lands |
| **Requirements** | PR-072 (every state change), C-05 |
| **Controls touched** | C-05 — from ✅-with-scope-paragraph to **unqualified ✅** |
| **Scope** | Small — one sitting |
| **Audit** | Not yet submitted |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

TASK-003 left this debt named in its REQ (§6) rather than hidden: **payment and
approval writes emit audit events; organisation creation, account creation and
journal posting do not.** A literal reading of PR-072 — *every* state change —
covers them, so C-05 is currently ✅ with an explicit scope paragraph.

After this task, every write the API can perform leaves exactly one audit event
in the same transaction, the scope paragraph is deleted, and C-05 is claimed
without qualification. The work is deliberately small; its value is that the
compliance statement stops needing an asterisk.

## Context you need

| Source | What it gives you |
|---|---|
| [003-REQ §6](../audits/003-REQ-authorisation-and-audit-trail.md) | The debt row this brief pays down, in the Worker's own words |
| `src/clofin/audit.clj` on the base branch | The closed action vocabulary you will extend; `record!` refuses unknown actions |
| `src/clofin/audit/repository.clj` | `record!` takes the caller's transaction and cannot open one — that shape is the control (I9) |
| `src/clofin/api/organisations.clj`, `api/accounts.clj`, `api/entries.clj` | The three handlers whose writes go silent today. The principal is already threaded through them (TASK-003 did that) — the actor is available; only the event is missing |
| ADR-0016 — on the base branch | Digests, not payloads. Your events follow it exactly |

## Scope

### In

1. **Vocabulary**: add `organisation.created`, `account.created`,
   `journal-entry.posted` to `clofin.audit/actions`, and `organisation`,
   `account`, `journal-entry` to `subject-types`, in the same commit as the
   code recording them. *(TASK-004 adds terms to the same sorted-set literals —
   whichever lands second rebases; the conflict is one line and Master Control
   sequences dispatch.)*
2. **Each of the three writes records its event on the transaction that
   carries the change.** Where a handler's repository call does not already run
   in an explicit transaction, introduce one in the `approval-service` shape —
   the service composes on the caller's `tx`, never opens its own.
3. **The I9 pair, per action**: committed → exactly one event with actor,
   action, subject, digest; rolled back → none. Copy the test shape from
   `authz.repository-test` AC-9/AC-10.
4. **`COMPLIANCE.md`**: delete C-05's scope-limit paragraph and the matching
   §4 line; the control statement is then true without qualification.
5. **Evidence**: `GET /audit/events` and the evidence pack surface the new
   events with no query changes — assert it, since that is the payoff.

### Out — and why

| Out of scope | Reason |
|---|---|
| Account lifecycle (freeze/close) as API operations | Still done in SQL (ROADMAP increment-2 debt). An *un-audited* API op would be this brief's problem; an absent one is a backlog item — it needs authorisation design, not a side-door here |
| Audit events for reads | C-05 covers state changes. Read-access logging is a different control with different volume economics — a brief of its own if ever |
| The approver-limit capture columns (003-REQ O-4) | Schema change; belongs in its own brief |
| New endpoints, new migrations | None expected. `audit_event` is generic over subjects. If you find a migration is genuinely required, number it against the live tree at build time (L-1) and say why in your REQ |

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given a committed `POST /organisations`, then exactly one `organisation.created` event exists, carrying the acting principal (or the documented bootstrap identity — see note below), subject id and after-digest; a rolled-back creation leaves none. | PR-072, C-05, I9 |
| AC-2 | Given a committed `POST /accounts`, then exactly one `account.created` event; rolled back, none. | PR-072, C-05, I9 |
| AC-3 | Given a committed `POST /journal-entries`, then exactly one `journal-entry.posted` event; rolled back, none — including when the rollback is the **database refusing** (zero-sum trigger), not only an application throw. | PR-072, C-05, I9, I1 |
| AC-4 | Given the new events, then each stores digests computed by the existing canonicalisation — no payload fields appear in `audit_event`. | ADR-0016, C-09 |
| AC-5 | Given an account with a posting history, when the evidence pack / event list is requested, then creation and posting events appear in order beside the payment events, with no query changes. | PR-074, C-05 |
| AC-6 | `COMPLIANCE.md` C-05 carries no scope qualification, and §4 no longer lists the gap. | C-05 |

**The trap in AC-1:** `POST /organisations` is the bootstrap — it is
deliberately unauthenticated because no actor exists before the first
organisation (003-REQ §6). Its event therefore cannot carry a normal principal.
Decide how the bootstrap identity is recorded (a documented null actor with a
stated meaning is acceptable; a fake actor row is not), say it in the OpenAPI
description and the test, and flag it in your REQ. Do **not** solve it by
making the endpoint authenticated — that recreates the superuser problem the
brief 003 rules forbid.

## Definition of done

- [ ] Every acceptance criterion has a named test; the I9 pair holds for all
      three actions
- [ ] `make verify` and `make test-it` both green
- [ ] `COMPLIANCE.md` C-05 unqualified; §4 updated
- [ ] No lifecycle, route or OpenAPI changes — assert the contract test still
      passes unchanged
- [ ] Completion reported — PR against the base branch, REQ filed (next
      available number in the audits series) — so Master Control can set this
      brief `IMPLEMENTED` on `meta`

## Notes for whoever picks this up

**This brief is small on purpose. Keep it small.** The temptation is to "also"
add account lifecycle endpoints, audit reads, or capture columns. Every one of
those is named out-of-scope above with a reason. A small PR that makes C-05
unqualified is worth more than a broad one that reopens design questions.

**The transaction shape is the control, not a style.** An audit write outside
the transaction that carries the change is the exact failure C-05 exists to
prevent (I9). If a handler makes that hard, the handler's shape is the thing to
fix — raise it in your REQ if it grows beyond a mechanical change.

**Coordinate with TASK-004 only through Master Control.** You share one-line
literals in `clofin.audit`. Do not read its branch, do not pre-merge it;
whoever lands second rebases and resolves the one line.
