# TASK-013: Cockpit phase 3 — operation flows, playing the scheme, and the evidence view

| Field | Value |
|---|---|
| **Increment** | 8.3 (cockpit, phase 3) — **`clofin-cockpit` only; `clofin-core`'s code is frozen** |
| **Status** | `IN PROGRESS` — dispatched 2026-08-15. **Dispatched over Master Control's hold-until-audit recommendation, by explicit operator decision**; the mitigation is this brief's hard boundary: the increment touches zero lines of the audit subject, whose Sol audit runs 2026-09-01 |
| **Depends on** | TASK-012 ✅ closed (PR #23 + cockpit PR #2) — connect and bootstrap are the floor this stands on |
| **Base branch** | `clofin-cockpit` `main` at `90abb1d` or later. `clofin-core` at `e8c5bf6` is **read and driven, never edited** — its only change is the `013-REQ` file |
| **Requirements** | Driver D5; the cockpit plan (Phase 3); UAT-004…007 as the flows' scripts |
| **Controls touched** | None. The cockpit demonstrates controls by driving them; it enforces nothing |
| **Scope** | Large — the demo centerpiece |
| **Audit** | Not yet submitted. The cockpit is outside release-audit scope; the REQ-only core PR adds no code to the audit subject |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

Phase 2 connects and seeds; nothing yet *moves money* through the UI. After
this task an operator walks the product's whole story against a live instance:
a payment refused to its own creator (C-01, live), approved by a second actor,
batched and released; the operator then **plays the simulated scheme by hand** —
settling, returning, contradicting, duplicating, going silent — and watches the
real ledger respond, `1300-IN-TRANSIT` included; a statement is generated and
ingested, breaks open, an adjustment is rejected with a reason and a second one
approved; and every subject's audit evidence is one click away throughout. All
of it real: **the cockpit performs no outcome and computes no figure — it sends
real requests and renders real responses.**

## The frozen-core rule, stated first because everything hangs on it

`clofin-core`'s Sol release audit runs 2026-09-01. **This increment does not
modify the audit subject.** Every flow drives endpoints that exist at
`e8c5bf6`. If you find a flow that cannot be completed without a `clofin-core`
change — an endpoint you wish existed, a missing read, a CORS header, anything —
that is a **dispatch-blocked objection**: file it in your REQ, ship the flows
that work, and do not touch core. The only `clofin-core` commit this task may
make is the `013-REQ` file itself, in `docs/audits/`, in a PR containing
nothing else.

## Scope — all in `clofin-cockpit`

1. **The acting-actor context, loud.** Flows need a maker and a checker. The
   current acting actor (the `X-Actor-Id` the next request will carry) is
   always visible in the frame, switching is explicit, and every rendered
   exchange names the actor that made it. The segregation-of-duties refusal
   demo is only honest if the audience can see who is asking.
2. **The payment flow** (UAT-004/005's walk): create → submit → the creator's
   own approval attempt **shown being refused** with the real 403 rendered →
   a different actor approves → batch → release. Each step real, sequential,
   raw request/response beside the pretty view, halting on failure with the
   step named — the TASK-012 runner disciplines, inherited wholesale.
3. **Playing the scheme** (UAT-006's misbehaviour, by hand): for a released
   batch's items, the operator acts as `SIM-RTGS`/`SIM-ACH` through the real
   scheme-response endpoint — settle, return with a reason, deliver a
   duplicate, deliver a contradiction, or deliberately do nothing; the timeout
   path uses whatever real mechanism the API offers for it (discover it; if
   none is reachable from outside, that is a named limitation in the flow's
   *what this cannot show* note, not a fabricated outcome). After every
   response, the affected account balances re-read from real statement calls —
   `1100`, `1300-IN-TRANSIT`, `2100` — so clearing exposure moves on screen
   because the ledger moved, never because the UI animated. Scheme names
   render exactly as captured; nothing is prettified.
4. **The reconciliation flow** (UAT-007's walk): generate a statement from the
   real `GET /settlement-statements` (perturbed and unperturbed), ingest it,
   render matches with their rule ids and breaks with their kinds, assign a
   break, propose an adjustment, **reject it with a reason as a different
   actor**, propose again, approve, and show the posted entry — the 010
   lifecycle, live.
5. **The evidence view.** For any subject a flow touched: its audit events and
   its evidence pack from the real audit endpoints, one click from the step
   that touched it. This is the payoff screen for a compliance audience; it
   renders what the API returns and nothing else.
6. **Disciplines carried forward, unchanged:** the honesty frame on every
   screen; raw exchanges everywhere; the four-state step vocabulary; manual
   steps (if any flow needs one) in the ratified TASK-012 pattern; exactly two
   CI checks, extended not multiplied; no PAT, no token, no new origins; the
   build guard's properties hold.

### Out — and why

| Out of scope | Reason |
|---|---|
| **Any `clofin-core` change beyond the REQ file** | The frozen-core rule above; the audit subject does not move in the audit gap |
| The Codespaces driver, PAT handling | Still their own phase |
| The Actions scenario runner | Phase 4 |
| Scripted auto-play of the scheme | The point is a human playing it; a macro that fires misbehaviour is a demo reel, not an operator console. One deliberate click per response |
| Charts, animations, derived balances | Balances are read, never computed (the trace doctrine's cockpit form) |

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given the payment flow with two actors, then the creator's approval attempt renders the instance's real refusal (status and body), and the flow completes only after a different actor approves. | C-01 live |
| AC-2 | Given a released batch, when the operator settles one item, returns one, delivers a duplicate for one and a contradiction for another, then each response's real outcome renders, and the three account balances shown after each action are re-read from the instance — a test or check asserts no balance figure originates in cockpit code. | UAT-006 |
| AC-3 | Given an item the operator leaves silent, then the flow's note states exactly what the timeout path can and cannot demonstrate from outside, per what the API actually offers. | honesty |
| AC-4 | Given the reconciliation flow, then ingest → breaks → reject-with-reason → re-propose → approve → posted all complete against the real instance with rule ids and break kinds rendered as returned. | UAT-007 |
| AC-5 | Given any subject touched by any flow, then its evidence pack and audit events render from the real endpoints, one click from the touching step. | D5 |
| AC-6 | Given every figure on every flow screen, then it is traceable to a response the instance sent — no computed totals, no client-side arithmetic beyond layout. | doctrine |
| AC-7 | Given the acting actor, then it is visible in-frame at all times and stamped on every rendered exchange. | AC-1's honesty |
| AC-8 | Given the built output, exactly two CI checks pass, the build guard's asserted properties hold unchanged, and the only `clofin-core` diff in the increment is `docs/audits/013-REQ-…`. | frozen core |

## Definition of done

- [ ] Every AC has a named test or check; AC-2's no-cockpit-arithmetic assertion is mechanical
- [ ] The full walk (bootstrap → payment → scheme play → reconciliation → evidence) run end-to-end against a real instance and evidenced in the REQ
- [ ] Cockpit CI green; Pages deploy green after merge
- [ ] The `clofin-core` PR contains exactly one file: the REQ, with provenance header, objections, and the L-9 statement
