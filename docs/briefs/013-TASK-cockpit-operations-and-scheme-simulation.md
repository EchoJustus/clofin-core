# TASK-013: Cockpit phase 3 — operation flows, playing the scheme, and the evidence view

| Field | Value |
|---|---|
| **Increment** | 8.3 (cockpit, phase 3) — **`clofin-cockpit` only; `clofin-core`'s code is frozen** |
| **Status** | `CLOSED` — `clofin-cockpit` PR #3 merged (`7ee7e28`), the REQ-only `clofin-core` PR #25 merged (`b962d7f`), 2026-08-15. The frozen-core rule held and was verified at ingestion; both objections ruled for the Worker, see Changelog. *(Dispatch history: over the hold-until-audit recommendation, by operator decision, with core frozen as the mitigation — which held.)* |
| **Depends on** | TASK-012 ✅ closed (PR #23 + cockpit PR #2) — connect and bootstrap are the floor this stands on |
| **Base branch** | `clofin-cockpit` `main` at `90abb1d` or later. `clofin-core` at `e8c5bf6` is **read and driven, never edited** — its only change is the `013-REQ` file |
| **Requirements** | Driver D5; the cockpit plan (Phase 3); UAT-004…007 as the flows' scripts |
| **Controls touched** | None. The cockpit demonstrates controls by driving them; it enforces nothing |
| **Scope** | Large — the demo centerpiece |
| **Audit** | `013-REQ` filed 2026-08-15; its O-1/O-2 join 012-REQ N-1 as pre-declared input to the 2026-09-01 Sol audit |

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

---

## Changelog — rulings on the `013-REQ` objections (2026-08-15)

*(The REQ is `docs/audits/013-REQ-cockpit-operations-and-scheme-simulation.md`
on `main`, landed alone in PR #25, `b962d7f` — the frozen-core rule held, and
was verified at ingestion: the core branch's diff against `main` was exactly
that one file.)* Both premises independently verified: `Idempotent-Replayed`
is a contract-documented response header absent from the live
`Access-Control-Expose-Headers` list, and `audit/read` is granted to
`compliance` and `auditor` only while UAT-006 §11 asks as the controller.

| # | Objection | Ruling |
|---|---|---|
| O-1 | The CORS exposed-headers list omits `Idempotent-Replayed`, so no browser can read UAT-004's central evidence; the flow declines to fake the demonstration and says why, pointing at the rendered `curl`. | **Confirmed — an incompleteness in TASK-012's CORS increment, found only by real use**: the allowance lists were discovered from the code's request reads, and nobody enumerated the contract's *response* headers — the L-16 shape on the other half of the same middleware. The frozen-core refusal was exactly right, and so was refusing to show two identical bodies as if they were the evidence. **Disposition ratified**: `idempotent-replayed` joins the exposed list in the post-audit remediation batch. |
| O-2 | UAT-006 §11 instructs the evidence read as `$CTRL`, who cannot hold `audit/read`; a reviewer following the script hits `403` precisely where the script's own stop-and-raise-a-defect rule fires. | **Confirmed — a document defect in the audit subject**: a control's demonstration script names an actor its role table refuses, the L-10 shape applied to roles. The cockpit's independent resolution (switch to an auditor and say so on the button) is the correct behaviour and predated noticing the script's defect. **Disposition ratified**: the two-line fix (seed `$AUDITOR`, use it) rides the remediation batch. |

**Pre-declared input to the 2026-09-01 Sol audit now stands at three items:**
012-REQ N-1 (the `read-key` every-mutating-endpoint docstring), 013-REQ O-1
(the unexposed evidence header), 013-REQ O-2 (UAT-006 §11's actor). All three
live in the audit subject, none was fixed in the audit gap, and all three ride
the remediation batch after the audit confirms them independently.

**Accepted with commendation rather than ruling:** negative control 5 — the
Worker attacked its own new guard, found the rule satisfiable by a comment,
and replaced it with a narrower rule honest about what a text search can
establish (L-6, caught by running the control); and the two in-the-open
corrections (§8), including the frame's own drifted sentence found in the
exact screenshot the frame exists to make honest.
