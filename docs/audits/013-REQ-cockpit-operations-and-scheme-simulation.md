# 013-REQ — cockpit phase 3: operation flows, playing the scheme by hand, and the evidence view

| Field | Value |
|---|---|
| **Brief** | `docs/briefs/013-TASK-cockpit-operations-and-scheme-simulation.md` — on **`origin/meta`**, which is the authoritative copy and is not edited here |
| **Increment** | 8.3 (cockpit, phase 3) — **`clofin-cockpit` only**; `clofin-core`'s code is frozen |
| **Requirements** | Driver D5; the cockpit plan phase 3; UAT-004…007 as the flows' scripts |
| **Controls** | **None move.** The cockpit demonstrates controls by driving them and enforces nothing. `docs/COMPLIANCE.md` is untouched |
| **Base** | `clofin-core` `main` at `e8c5bf6` — **read and driven, never edited**; `clofin-cockpit` `main` at `90abb1d` |
| **Branch** | `claude/cockpit-ops-scheme-sim-mzcfgd` in both repositories |
| **Pull requests** | `clofin-cockpit` **#3** · `clofin-core` **#25** — this file, alone — cross-referenced in both descriptions |
| **`clofin-core` diff** | **This file and nothing else.** `git diff origin/main` on this branch lists one path: `docs/audits/013-REQ-cockpit-operations-and-scheme-simulation.md` |
| **Model** | `claude-opus-5` |
| **Reasoning effort** | High — extended thinking enabled throughout. The harness does not expose a numeric setting to the session, so this is reported as the mode, not as a measured value |
| **Date** | 2026-08-15 |
| **Verification still in flight** | **See [§8](#8-verification-l-9).** Nothing is running. Both PRs' CI was triggered by the commits they carry; the Pages deployment runs on merge |

---

## 1. The frozen-core rule, and what was done about it

The brief's first instruction was that this increment does not modify the audit
subject. It does not. `clofin-core` was **run** — `make up`'s stack against a
real PostgreSQL 16, migrated to schema `0013`, reporting
`sourceCommit e8c5bf62f5ab3272ae08fa14fbd31a5fbb755f04` — and **driven hard**:
every flow below is real requests against that instance, and the walk in §6 was
performed four times over the increment against a database dropped and
re-migrated between runs.

Two things were found that would have been `clofin-core` changes. Neither was
made. Both are filed in [§3](#3-objections).

**One deviation from the letter of `make up`, recorded because it is a
deviation.** The composed `app` service could not be built in this environment:
`infra/Dockerfile`'s `RUN clojure -P -M:run` resolves dependencies over TLS, and
the JVM inside the build container does not trust this sandbox's egress-proxy
CA, so dependency resolution fails with a PKIX error before any CloFin code is
compiled. The database was therefore composed (`docker compose up -d postgres`,
the same `postgres:16-alpine` and the same `docker-compose.yml`) and the service
run on the host from the same checkout with the same environment
`docker-compose.yml` passes it, including `CLOFIN_SOURCE_COMMIT` from
`git rev-parse HEAD` and the `CLOFIN_CORS_ALLOWED_ORIGINS` allowlist. It is the
same code, the same migrations and the same configuration; the difference is
which process the JVM runs in. **This is an environment limitation, not a
`clofin-core` defect**, and `infra/Dockerfile` was not touched to work around it.

## 2. What was built

All of it in `clofin-cockpit`.

| Piece | Where |
|---|---|
| The only path by which a value in a response becomes a figure | `src/figures.ts` |
| The acting actor, and the invariant it exists to keep | `src/acting.ts` |
| What one run learned, so the next can use it | `src/workspace.ts` |
| The evidence view | `src/evidence.ts` |
| The runner, extended — the actor gate, `choice` steps, readouts, captured documents, subjects | `src/bootstrap.ts` |
| Profile format v2 — `role`, `choice`, `readouts`, `subjects`, `bodyFrom` | `src/profiles.ts` |
| The three flow documents | `profiles/payment-maker-checker.json`, `profiles/scheme-play.json`, `profiles/reconciliation.json` |
| The run screens | `src/views-run.ts`, `src/views-instance.ts`, `src/main.ts` |
| The acting-actor region of the honesty frame | `src/frame.ts`, `src/main.ts`, `static/styles.css` |
| The corrected frame sentence | `src/scope.ts` |
| The figure rules | `tools/guard-network.mjs` |
| The decision | `docs/ADR/0003-operating-a-live-instance-one-runner-explicit-actors-and-figures-that-are-never-computed.md` |
| Tests | `test/figures.test.ts` (new), `test/bootstrap.test.ts`, `test/profiles.test.ts` |

### The three decisions that shaped everything else

**A figure cannot originate in cockpit code.** `figures.ts` parses a response
body, walks a declared path and re-serialises what it finds. It contains no
arithmetic. `{"currency":"SGD","minorUnits":150000}` renders as
`{"currency":"SGD","minorUnits":150000}` — never as `SGD 1,500.00`, because that
conversion is arithmetic this repository performed, and a reader cannot
distinguish a correct conversion from a wrong one without redoing it. Three
enforcements: `figures.test.ts` requires every projected figure to appear
**verbatim** in the body it came from; the build guard refuses any number
formatter anywhere in the output and any arithmetic operator adjacent to a
money-carrying member in any module; and the guard requires the module to be in
the built site at all.

**Every authenticated request carries the acting actor, and the acting actor
changes only by an explicit operator action.** The acting actor is rendered in
the frame beside the scope statement and re-checked before and after every
render, exactly as the scope statement is — a footer would be cropped out of
precisely the screenshot that most needs it. A step declaring `as` while
somebody else is acting **sends nothing at all**, not even a precheck, and
reports `waiting for you`, halting as failure does. The evidence view obeys the
same rule rather than being excepted from it: `audit/read` is held by `auditor`
and `compliance` only, so the control names the actor it will switch to,
switches, and asks as the actor the frame now names.

**The simulated scheme's behaviour is not this page's to decide.** A `choice`
step presents its options and performs nothing; each option is one declared
request, taken by one deliberate click. An option may declare that it sends
nothing at all — that is how silence is offered — and the reader and
`profiles.test.ts` both require such an option to carry a note saying what was
not done, so a step reading `done` never stands for an outcome nobody produced.
There is no macro, no auto-play, no default and no timer.

### One runner, not two

The brief said extend, and it was right. Flows are the same versioned JSON
documents read by the same reader and executed by `bootstrap.ts`;
`role: "bootstrap" | "flow"` is the only behavioural branch. A second engine
would have been easier to write and would have been a second place for the
halting rule, the four-state vocabulary, the raw-exchange discipline and the
actor invariant to be *almost* implemented.

`formatVersion` moved 1 → 2 and **both shipped bootstrap profiles moved with
it in the same commit**, rather than a compatibility branch inside a reader
whose output is writes against somebody's live instance.

### The frame's own sentence was corrected

`COCKPIT_ROLE` ended *"It computes nothing about payments, and it stores
nothing."* The second half stopped being true in **phase 2**, when the instance
registry began storing base URLs and labels; the phrase *"generates commands you
run yourself"* stopped being the whole story when the cockpit began driving an
instance directly. Both are corrected. A frame sentence that has quietly drifted
out of true is worse than no frame sentence, because it is the part of the page
a reader is invited to rely on, and correcting it did not wait for a later
increment.

## 3. Objections

Two, both **dispatch-blocked** by the frozen-core rule: filed, worked around
inside the cockpit by saying plainly what cannot be shown, and not built.

### O-1 — `Idempotent-Replayed` is not exposed to a browser, so UAT-004's central evidence cannot be rendered

The brief's scope item 2 names UAT-004's walk as one of the payment flow's two
scripts. UAT-004 exists for one step: press submit twice with the same
`Idempotency-Key`, and see that the second press **answered** rather than acted.
Its evidence is the `Idempotent-Replayed: true` header — the script says so
directly: *"An `idempotent-replayed: true` header, which is how you can tell
nothing new happened."*

A browser page can read a cross-origin response's headers only if the server
names them in `Access-Control-Expose-Headers`. This instance answers:

```
$ curl -sS -i -X POST http://localhost:8080/organisations \
    -H 'Origin: http://localhost:4173' -H 'content-type: application/json' \
    -d '{"legalName":"Probe Expose Ltd","shortName":"probe-expose-1"}' | grep -i expose
Access-Control-Expose-Headers: location, x-correlation-id, allow
```

`idempotent-replayed` is not in that list, and neither is `idempotency-key`. So
the cockpit **cannot** render the one thing that distinguishes a replay from a
second submission. It can show two byte-identical bodies, which is suggestive
and is not the evidence; and asserting "identical" would be the cockpit
computing a claim rather than showing a response.

The fix is one entry in the exposed-headers list in `clofin.http.cors` — a
change to the audit subject in the audit gap. **Not made.** The payment flow
therefore does not attempt the double submission at all, and its document-level
*what this cannot show* list states the limitation and points at the rendered
`curl`, which reproduces the request in a terminal where the header is visible.
Claiming C-06 from a screen that cannot see its marker would be exactly the
failure this project spends the most effort hunting.

**Suggested disposition:** add `idempotent-replayed` to
`Access-Control-Expose-Headers` in the remediation batch, after the Sol audit.
It is a header CloFin already sends; exposing it grants a browser nothing it
could not learn from the body, and it is the difference between demonstrating
C-06 and describing it.

### O-2 — UAT-006 §11 reads the evidence pack as an actor that cannot read it

UAT-006's step 11 — *"The trail an auditor reads"* — instructs:

```sh
curl -sS "$BASE/audit/evidence/$SETTLES?organisationId=$ORG" -H "x-actor-id: $CTRL" | jq '[.events[].action]'
```

and states an expected list of actions. `$CTRL` is the controller seeded in
step 1. But `audit/read` is held by **`compliance` and `auditor` only** —
`src/clofin/authz/model.clj`:

```clojure
:compliance #{:payment/read :account/read :entry/read :audit/read …}
:auditor    #{:audit/read :payment/read :account/read :entry/read …}
```

and `clofin.api.audit`'s own docstring says so: *"Both are read-only and both
require `:audit/read`, which `operator` and …"*. Run against a live instance at
`e8c5bf6`, the controller is refused:

```
RAE   (auditor)     /audit/events -> 200   /audit/evidence -> 200
SAM   (controller)  /audit/events -> 403   /audit/evidence -> 403
PRIYA (operator)    /audit/events -> 403   /audit/evidence -> 403
```

So UAT-006 §11 as written cannot pass: a reviewer following it gets `403` where
the script promises a list of actions, and the natural reading of that is that
the audit trail is broken. UAT-006's own framing makes this sharper than a
typo — *"If a step succeeds where this script says it should fail, stop and
raise a defect"* — because the converse case, a step failing where the script
says it should succeed, is exactly what this is.

This is a **document defect in the audit subject**, and `docs/uat/` is
`clofin-core`'s. **Not fixed here.** The finding is that a control's own
demonstration script names the wrong actor for its most quotable step, which is
the shape standing lesson **L-10** records — *a schema path is not a product
path* — applied to a role table rather than a schema.

The cockpit's evidence view resolved the same problem correctly and
independently, before this discrepancy was noticed: it switches to an actor
holding `auditor` and says so on the button, because the alternative was a
button that produced a `403` from the controller who had just done the work.

**Suggested disposition:** correct UAT-006 §11 to use an auditor actor —
UAT-006's step 1 seeds no auditor, so the correction is two lines, one seeding
`$AUDITOR` and one using it. Doc-only, on the meta-owned path if it moves there,
otherwise in the remediation batch with the code findings.

## 4. Notes and readings recorded

**N-1 — a statement's `to` bound is exclusive, and "now" is therefore the wrong
window.** `GET /accounts/{id}/statement` is half-open, which the contract states.
The consequence for a UI is sharper than the sentence suggests: a balance read
with `to` = the current second **omits an entry posted in that same second**.
The first balance read after a release returned `minorUnits: 0` for
`1300-IN-TRANSIT` with `to=2026-08-15T14:06:41Z` while the release's entries
were stamped `14:06:41.281010Z`. Read again with a wider window, the same
instance answered `375000`. A cockpit that had shipped the obvious `to = now`
would have shown a clearing account that never moved, and the bug would have
looked like a settlement defect. The flow documents therefore declare a literal
far-future `to`, visible in the rendered URL on every readout. Recorded because
the next client to read a statement will hit this, and because the alternative —
computing a timestamp — is the kind of client-side derivation the doctrine is
otherwise pushing out.

**N-2 — the scheme of a response is the batch's, not the caller's.** The brief
says the operator "acts as `SIM-RTGS`/`SIM-ACH` through the real scheme-response
endpoint". `POST /settlement-batches/{id}/scheme-responses` carries no scheme
member: the scheme is fixed when the batch is created, so acting as `SIM-ACH`
means batching for `SIM-ACH`, not choosing it per response. The shipped walk
uses one `SIM-RTGS` batch and the flow's prose names the scheme it is speaking
as. No change requested; recorded so the phrasing is not read as a missing
capability.

**N-3 — the timeout path has a real, externally reachable mechanism, and its
limit is one of meaning rather than of reach.** The brief allowed for there
being none. There is: `POST /settlement-batches/{id}/timeout-sweep`, an explicit
operator action by design — *"a timeout that fires itself is one nobody can
point at afterwards"*. The flow drives it for real. What it cannot show is what
happened to the money, and that is a limit of the world: the item becomes
`timed-out`, the **payment stays `released`**, its value stays in
`1300-IN-TRANSIT`, and no lifecycle event is driven. The step's *what this
cannot show* list says exactly that, in four entries, and the flow re-reads
payment C's own `status` and `permittedTransitions` from the instance so the
`released` is the instance's word rather than the interface's.

**N-4 — a replayed statement ingestion still carries its breaks.** Re-delivering
a perturbed statement answers `200` with `replayed: true` **and the same
`breaks` array, with the same break id**. This is what makes the reconciliation
flow re-runnable: its capture of `breaks.0.id` succeeds on a second walk and
points at the original break rather than at nothing. Confirmed against a live
instance; recorded because a reasonable reader might expect a replay to carry a
receipt and nothing else.

**N-5 — the blunt-comment trap, again, and this time in a comment about the very
rule it broke.** 011-REQ's N-5 recorded three comments failing a check because
the guard reads comments like any other text. It happened again: `figures.ts`'s
doc comment listed the four number-formatting calls the guard refuses, and the
guard refused the build for containing one of them. The comment now describes
them without naming them and says why — the same treatment ADR-0002 gives the
three browser stores. The rule working bluntly on a sentence about itself is the
rule working.

## 5. The negative controls, run for real

Five, each run against the actual build. The first four are the ADR's; the fifth
was run to check the guard itself and **found a real weakness**.

| # | Control | Result |
|---|---|---|
| 1 | Arithmetic on a money identifier: `const scaled = minorUnits / 100;` added to `views-run.ts` | **Build refused.** `js/views-run.js: performs arithmetic on minorUnits. Every figure on screen is a value the instance sent, projected by js/figures.js; see docs/ADR/0003.` No `_site` left |
| 2 | A number formatter in the output: a `.toFixed(2)` span added beside a figure | **Build refused.** `js/views-run.js: contains toFixed — formatting a number is arithmetic this page does not do.` No `_site` left |
| 3 | `figures.ts` altered to derive rather than project (`String(value)` in place of the serialiser) | **`figures.test.ts` failed**, so `npm run build` stopped before the guard ran: `not ok 1 - renders minor units exactly as they arrived, with no conversion` and `not ok 2 - appears verbatim in the body it was read from` |
| 4 | `js/figures.js` deleted from a built site, and the guard run against it | **Guard objected.** `js/figures.js is not in the built output. It is the only path by which a figure reaches the screen…` |
| 5 | The same as (3), but checked against an **earlier draft** of the guard, which asserted that `figures.js` still mentioned the serialiser | **The rule did not fire.** It was satisfied by the module's *doc comment* mentioning the serialiser |

Control 5 is the one worth reading. The draft rule looked like an enforcement
and was a substring search that a comment could satisfy — standing lesson
**L-6**'s exact shape, *a guard stated over a partial set, passing because the
part it looks at is clean*. It was **replaced rather than patched**: a regular
expression over a file cannot establish what a module does, so the guard now
asserts only that the module is present and the reasoning is written beside it,
pointing at `figures.test.ts` as the thing that actually holds the property.
Found by running the control instead of by reading the rule, which is the whole
argument for running them.

Two earlier attempts at controls 2 and 3 were **stopped by `tsc`** before the
guard saw them — an unused import, in one case. Recorded because a control that
is blocked by an earlier stage has not tested what it claims to, and both were
re-run in a form that compiled.

## 6. The end-to-end walk, in a real browser

Driven in **Chromium** (`/opt/pw-browsers/chromium-1194`, Playwright) against
the built site served on `http://localhost:4173` — an origin in the instance's
`CLOFIN_CORS_ALLOWED_ORIGINS` — talking to `clofin-core` at `e8c5bf6` on a real
PostgreSQL 16. The database was dropped and re-migrated immediately before the
run. Every click below is one a person makes; the two SQL steps were executed in
`psql` by the operator, from the statements the cockpit generated.

**Bootstrap — `uat-standard`, 7 steps.**

```
Frame before connecting: ACTING AS No instance connected.
  Identity, as reported | Service | clofin-core | Environment | dev
  | Schema version | 0013 | Source commit | e8c5bf62f5ab3272ae08fa14fbd31a5fbb755f04
 1. Register the organisation — DONE                    (unauthenticated)
 2. Seed the actors, their roles and their ceilings — WAITING FOR YOU
    confirm while acting as nobody: "This step is confirmed by asking the instance
      as Sam (controller), and you are not acting as them. Switch actors above…"
    → switched. Frame: ACTING AS Sam (controller) — controller
    confirm as Sam: "Confirmed by the running instance: 200 OK. …"
 3-6. Open 1100 / 1300 / 2100 / 2200 — DONE
 7. Configure the approval bands — WAITING FOR YOU
    confirm as Sam: "…confirmed by asking the instance as Wei (checker)…"
    → switched. Frame: ACTING AS Wei (checker) — approver
    confirm as Wei: "Confirmed by the running instance: 200 OK. …"
```

The manual pattern ratified by the TASK-012 changelog, working: SQL generated,
the operator runs it, and the step advances only because the **instance**
answered — and only when asked by the actor the profile named.

**Payment flow — the C-01 refusal, live.**

```
 7. Priya tries to approve her own payment — and is refused, for real — DONE
    Sent as Priya (maker) · operator · 2e67f53d-…
    403 Forbidden rendered; errors.reason = self-approval
    frame at the moment of refusal: ACTING AS Priya (maker) — operator
 8. Wei approves payment A — WAITING FOR YOU
    "This step is Wei (checker) (approver)'s to perform. You are acting as
     Priya (maker). Switch actors above, then run this step. Nothing was sent —
     the cockpit does not change who you are on your behalf."
    → switched. Frame: ACTING AS Wei (checker) — approver
 8. Wei approves payment A — DONE
```

The refusal renders as the step's **expected** outcome — a `403` is what success
looks like there — with the real problem document beside it and the actor's id
in the rendered `X-Actor-Id` header. The next step sends nothing until a human
hands over.

**Release, and the money becoming visible in flight.**

```
11. Sam batches the three approved payments — DONE
    before release | 1100-CLIENT-FUNDS   -> {"currency":"SGD","minorUnits":0}
    before release | 1300-IN-TRANSIT     -> {"currency":"SGD","minorUnits":0}
    before release | 2100-CLIENT-PAYABLE -> {"currency":"SGD","minorUnits":0}
12. Sam releases the batch — DONE
    after release  | 1100-CLIENT-FUNDS   -> {"currency":"SGD","minorUnits":-150000}
    after release  | 1300-IN-TRANSIT     -> {"currency":"SGD","minorUnits":150000}
    after release  | 2100-CLIENT-PAYABLE -> {"currency":"SGD","minorUnits":0}
```

Minor units, unconverted, each with the raw statement response beneath it and
the path it was read from beside it.

**Playing the scheme — five behaviours, one deliberate click each.**

```
 1. Read the released batch          baseline      1300 -> 150000
 2. (presented) — WAITING FOR YOU
 2. Item A: SIM-RTGS settles it      after settle  1300 -> 100000
 3. (presented) — WAITING FOR YOU
 3. Item A again: the duplicate      replayed=true  outcome=settled
                                     after dup     1300 -> 100000   (unchanged)
 4. (presented) — WAITING FOR YOU
 4. Item B: sends it back            after return  1300 -> 50000
 5. (presented) — WAITING FOR YOU
 5. Item B: the contradiction        409? yes  dispositionReason=item-already-resolved
                                     receipt kept=true
                                     after 409     1300 -> 50000    (unchanged)
 6. (presented) — WAITING FOR YOU
 6. Item C: the scheme says nothing  "You chose: Send nothing at all. No request was sent."
                                     after silence 1300 -> 50000  1100 -> -100000  2100 -> -50000
 7. Sam stops waiting — the sweep    Payment C's own status -> "released"
                                     permittedTransitions  -> ["fail","return","settle"]
                                     after sweep   1300 -> 50000    (still in flight)
 8. (presented) — WAITING FOR YOU
 8. Item C: the late answer          after late    1300 -> 0
```

Every choice was presented and **performed nothing** until an option was
pressed; asking for "the next step" at a choice sends nothing, which
`bootstrap.test.ts` asserts by counting requests at a stubbed `fetch`. The
duplicate moved no money and the batch's response list did not grow. The
contradiction was refused with its receipt committed. Silence sent no request at
all and still re-read all three accounts, so "nothing moved" is something the
operator checked.

**Reconciliation — through a refusal to a posted entry.**

```
 1. Ask for the statement            format=SIM-CLOFIN-RECON-STATEMENT
 2. Post it straight back            rules=R1-reference-amount-and-value-date  breaks=none
 3. Ask for a perturbed one
 4. Ingest it, opening a break       kind=amount-mismatch  state=open
                                     detail="The statement reports 501.00 and the
                                             ledger movement is 500.00"
 5. Sam assigns the break to Wei     kind/state/detail/ageSeconds re-read: "investigating", 3
 6. Sam proposes a correction
 7. Sam approves his own — refused   403? yes  reason=self-approval
 8. Wei refuses it, with a reason    rejected=true  status=rejected
                                     after refusal  1300 -> 0   break -> "investigating"
 9. Sam proposes a different one
10. Wei agrees                       (one of the two this band needs)
11. Nadia agrees — the books move    posted=true  break=resolved
                                     1300 -> -100000   2200 -> -100000
12. Read the entry it posted         reference {"type":"reconciliation-adjustment",…}
                                     lines [{…"debit"…100000},{…"credit"…100000}]
```

The statement posted back is the document the instance returned, unchanged, with
the organisation id added — the `jq` step of UAT-007, done by the runner and
rendered in full. That the refused adjustment moved nothing was confirmed at the
database as well as on screen:

```
status   | entry_is_null | posted_is_null
---------+---------------+----------------
posted   | f             | f
rejected | t             | t
```

**Evidence.** Offered from the step that touched each subject; 6 buttons on the
payment flow's release step, 7 by the end of reconciliation. Pressing one
switches and asks:

```
Evidence | Subject | Payment A — SG-SYNTH-88012340 | The flow called it
| payment-instruction | Id | 1db2d156-… | Asked as | Rae (auditor) — auditor
frame now: ACTING AS Rae (auditor) — auditor
actions in the pack: payment.created, payment.submitted, payment.approved, payment.released
```

Every subject kind the documents declare was checked against the live audit
endpoint and returns a pack rather than a `404`:

```
organisation 200 · account ×4 200 · payment-instruction 200 · approval 200
settlement-batch 200 · reconciliation-statement 200 · reconciliation-break 200
reconciliation-adjustment 200 · journal-entry 200
```

**What the page did, and what it kept.**

```
Origins contacted: http://localhost:4173, http://localhost:8080, https://api.github.com
localStorage keys:  ["clofin-cockpit.instances.v1"]
localStorage value: [{"baseUrl":"http://localhost:8080","label":"local reference instance"}]
sessionStorage keys: []   cookies: ""
```

Its own origin, the instance the operator connected, and the GitHub API. One
stored key, containing base URLs and labels and nothing else. No session
storage, no cookies. No credential in any of it — the minted actor ids appear
only in request headers and on screen.

**Console errors, all four accounted for.** Three are the browser logging
non-`2xx` responses, and they are the demonstrations: the C-01 `403`, the
contradiction `409`, and the adjustment self-approval `403`. The fourth is
`ERR_CERT_AUTHORITY_INVALID` on `api.github.com` — this sandbox's egress proxy
re-terminates TLS and this Chromium does not trust its CA, so the release list
cannot be read. The cockpit's honest degradation is what appears on screen:
*"not checked — the published releases could not be read, so this commit was not
compared with any tag"*, which is the distinct third sentence 012 built for this
exact case, rendering rather than a blank and rather than a guess.

## 7. Acceptance criteria

| # | Criterion | Where it is met |
|---|---|---|
| AC-1 | The creator's approval attempt renders the instance's real refusal; the flow completes only after a different actor approves | §6 — `403` with `errors.reason: self-approval` rendered body and all, Priya named in the frame; step 8 sends nothing until the operator switches |
| AC-2 | Settle, return, duplicate, contradict; each response's real outcome renders; three balances re-read from the instance after each; a test or check asserts no balance originates in cockpit code | §6; `figures.test.ts` (verbatim assertion) and the guard's formatter/arithmetic refusals, negative controls 1–4 in §5 |
| AC-3 | The silent item's note states what the timeout path can and cannot demonstrate, per what the API offers | `scheme-play.json` `timeout-sweep.unverifiable`, four entries; N-3. The sweep is real and reachable, and the flow re-reads payment C's `released` status from the instance |
| AC-4 | Ingest → breaks → reject-with-reason → re-propose → approve → posted, against the real instance, with rule ids and break kinds as returned | §6 reconciliation, all twelve steps |
| AC-5 | Any touched subject's evidence pack and audit events render from the real endpoints, one click from the touching step | §6 — 6 then 7 buttons; all nine subject kinds return `200` |
| AC-6 | Every figure is traceable to a response; no computed totals, no client-side arithmetic beyond layout | `figures.ts` has no arithmetic; the guard refuses formatters and money-adjacent operators anywhere; every figure renders beside its path and its raw response |
| AC-7 | The acting actor is visible in-frame at all times and stamped on every rendered exchange | `frame.ts`'s acting region, checked before and after every render by `assertFrameIntact`; every step carries `Sent as <name> · <roles> · <id>` |
| AC-8 | Exactly two CI checks; the build guard's asserted properties unchanged; the only `clofin-core` diff is the REQ | Both checks green and still two; ADR-0002's policy properties untouched and re-asserted by the same code; `git diff origin/main` on the core branch lists this file alone |

## 8. Verification (L-9)

**What I ran, and its results.**

- `clofin-cockpit` `npm run build` — **189 tests, 189 passed, 0 failed** (126 at
  the end of 012); type-check clean; network guard clean; `_site` emitted.
- `npm run check:scope-verbatim` — OK. Statement verbatim once across 1 page and
  in `README.md`; **no near-copy anywhere in 5 profile documents** (the two
  bootstrap profiles and the three new flows — the check picked the new files up
  without being changed, which is what "extended, not multiplied" means here).
- `npm run check:no-unqualified-audited` — OK. 3 assurance claims across 31
  built files, every one qualified.
- The five negative controls in §5, each run for real, three of them confirmed
  to leave no `_site`.
- The end-to-end walk in §6, in Chromium against a live instance on a real
  PostgreSQL 16, run to completion four times over the increment with the
  database dropped and re-migrated between runs. The final run is the one quoted.
- `clofin-core`: **nothing was run against its test suite, because nothing in it
  was changed.** `make verify` was not executed and is not claimed. The
  repository was used as a running system, not modified; `git diff origin/main`
  on this branch lists exactly one path, this file.

**Nothing is still running.** There is no self-review, adversarial pass or long
test run outstanding on this increment at the time of writing. Both PRs' CI was
triggered by the commits they carry.

**Two corrections made during the increment, in the open.**

1. The frame's own `COCKPIT_ROLE` sentence was false in its second half from
   phase 2 onward (*"it stores nothing"*, while the registry stored addresses).
   Found while reading a screenshot of the frame taken to evidence AC-7 —
   the exact artifact the frame exists to make honest. Corrected in this
   increment rather than deferred; §2.
2. Two shipped flow steps re-read fewer than the three accounts the brief
   requires after every action — the silence step and the timeout sweep. Found
   by re-reading the brief against the shipped documents rather than against the
   code, after the first end-to-end walk had already passed. The silence step
   was the worst place for it: it is precisely where *nothing moved* has to be
   something the operator checked. Both now take all three; second commit on the
   cockpit branch.

**One thing a reader should know about the guard.** Its figure rules are new and
one of them was wrong on the first attempt in a way that looked right (§5,
control 5). The replacement is narrower and honest about what a text search can
establish. If the release auditor reads one part of this increment's tooling,
that is the part.

## 9. What the next session should pick up

- **The two objections**, if Master Control rules them actionable: O-1 is one
  entry in an exposed-headers list; O-2 is two lines in UAT-006 §11. Both are
  post-audit remediation-batch work by the frozen-core rule, not now.
- **The Codespaces driver and PAT handling**, still their own phase. The
  README's rule 3 stays forward-tensed and was re-checked this increment.
- **The Actions scenario runner**, phase 4.
- **N-1's exclusive statement bound** is worth a sentence in the API description
  of `getAccountStatement` — not a behaviour change, a warning for the next
  client that reads a balance. Left unfiled as an objection because it is a
  documentation improvement rather than a defect in the contract, which already
  states the half-open rule correctly.
- The flow documents carry literal value dates (`2026-12-01`), as UAT-006's own
  script does. They will eventually be in the past, at which point the instance
  answers `422` naming `valueDate` and the runner renders it. A legible failure,
  and still a failure worth pre-empting when somebody next touches those files.
