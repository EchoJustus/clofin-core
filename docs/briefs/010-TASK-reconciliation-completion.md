# TASK-010: Reconciliation completion — the three disclosed gaps

| Field | Value |
|---|---|
| **Increment** | 6c (completion) — closes debt TASK-008 named, opens no new product surface |
| **Status** | `READY` |
| **Depends on** | TASK-008 ✅ merged (PR #16, `a41e69f`) |
| **Base branch** | `main` at the tip carrying PR #16 and PR #17 |
| **Blocks** | Nothing hard; C-05's disclosed exception stays disclosed until this lands |
| **Requirements** | ADR-0019 (ruled text); C-05; 008-REQ O-1/O-2/N-5 and their rulings |
| **Controls touched** | C-05 — its one disclosed exception is **closed**; C-13 gains the rejection evidence claim |
| **Scope** | Medium — three named items, nothing else |
| **Audit** | Not yet submitted |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

TASK-008 shipped increment 6 and, correctly, refused to build three things its
brief had not scoped. All three were promised elsewhere in ruled text, all
three are recorded as open gaps on `main`, and each gap has a shelf life. This
brief is those three items, exactly, and nothing else — the TASK-005 shape:
its value is that the disclosed exceptions stop needing disclosure.

## Context you need

| Source | What it gives you |
|---|---|
| 008-REQ §3 (O-1, O-2) and §5 (N-5) on `main` | The three gaps, in the Worker's own words, with what was deliberately not built and why |
| The TASK-008 changelog on `origin/meta` | The rulings that route all three here |
| ADR-0019 | The ruled description of linked-retry provenance — "a `retries_id`-style reference and the exception workflow around it" |
| `COMPLIANCE.md` §4 on `main` | The gap rows this brief deletes, each currently pointing at 008-REQ |
| `clofin.audit` + L-7 | New vocabulary terms emit only where their fact commits |

## Scope

### In

1. **Linked-retry provenance (008-REQ O-1; ADR-0019's assignment).** A retry
   instruction carries a reference to the returned original it retries;
   the reference is set at creation, immutable, and refused when the target is
   not a returned instruction of the same organisation (and refused when it is
   itself a retry of something else only if ADR-0019's text says so — read it
   and decide, stating your reading). The exception workflow around it:
   `GET` surfaces the linkage both ways, and a reconciliation break on the
   original names the retry when one exists. An audit-visible fact, not a
   convenience column.
2. **The batch-status audit term (008-REQ O-2; C-05's disclosed exception).**
   A late `timeout-resolution` that moves an already-complete settlement
   batch's derived status emits a batch-subject event, with a term named under
   L-7 (it emits only where the batch's status fact actually changes, which is
   the transition the existing exception text describes). Close the exception
   everywhere it is disclosed — **enumerate the copies (L-16)**: the C-05
   statement, `COMPLIANCE.md` §4, the OpenAPI Audit tag description, and
   `DOMAIN_MODEL.md` §2.6. The C-05 statement returns to having no exception
   clause at all.
3. **Adjustment rejection (008-REQ N-5).** A third status for a proposed
   adjustment: an approver can reject it, with a reason, leaving the same
   class of evidence C-05 keeps for a rejected payment — audit term, refusal
   recorded, the adjustment terminal, the break returned to its prior state so
   a different adjustment can be raised. Update the break/adjustment lifecycle
   data and **regenerate the diagram** (RULE 1); extend UAT-007's walk with the
   rejection path.

### Out — and why

| Out of scope | Reason |
|---|---|
| Automatic re-batching or auto-retry of returned payments | ADR-0019: a retry is a deliberate new instruction; automation is product design nobody has ruled on |
| Any change to matching rules or break kinds | Increment 6 delivered them; this brief completes its edges, it does not reopen its core |
| A generic "linked instruction" mechanism | One link kind is scoped: retry → returned original. Generality without a second use case is speculation |
| Account lifecycle operations | Still increment-2 debt; still not a side door |

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given a returned instruction, when a retry is created referencing it, then the linkage is stored, immutable, visible from both sides via `GET`, and carried on the retry's audit event. | O-1, ADR-0019 |
| AC-2 | Given a reference target that is not `returned`, or belongs to another organisation, then creation is refused with a specific reason; the cross-tenant case reveals nothing about the foreign instruction. | O-1, C-08 |
| AC-3 | Given a reconciliation break on a returned original that has a retry, then the break names the retry. | O-1 |
| AC-4 | Given a late `timeout-resolution` that completes a batch, then exactly one batch-subject event with the new term is emitted in that transaction — and none when the resolution does not change the batch's derived status. | O-2, L-7, I9 |
| AC-5 | Given the closed exception, then no copy of it survives anywhere on `main` — statement, §4, OpenAPI, DOMAIN_MODEL — asserted by grep in the REQ, not by memory. | O-2, L-16 |
| AC-6 | Given a proposed adjustment, when an approver rejects it with a reason, then the adjustment is terminal with the reason recorded and audited, the break returns to its prior state, and a new adjustment can be raised; the rejector must differ from the creator. | N-5, C-01, C-05 |
| AC-7 | Given the changed lifecycles, then the transitions tables, the generated diagram and the OpenAPI enums (both audit copies) all agree; `make diagrams-check` and the vocabulary guards pass. | RULE 1, L-6 |
| AC-8 | Every new write: committed → exactly one audit event; rolled back → none. | C-05, I9 |

## Definition of done

- [ ] Every acceptance criterion has a named test; AC-5's enumeration is in the REQ with the grep output
- [ ] `make verify` and the integration suite green
- [ ] Migration numbered next-available against the live tree at build time (L-1)
- [ ] C-05's statement carries no exception clause; C-13 updated if the rejection claim belongs there
- [ ] No edits to `main`'s governance copies
- [ ] PR against `main`, REQ filed as `010-REQ-…`, provenance header, and the L-9 statement

## Notes for whoever picks this up

**Three items, exactly.** Each was refused once by a Worker who was right to
refuse it, and each is now scoped, named work with a ruling behind it. If a
fourth thing surfaces mid-flight, it goes in your REQ as an observation, not in
the PR.
