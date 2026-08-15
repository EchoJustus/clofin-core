# TASK-012: Cockpit phase 2 — connect to a real instance, and bootstrap an organisation

| Field | Value |
|---|---|
| **Increment** | 8.2 (cockpit, phase 2) — spans `clofin-core` (CORS + self-identification) and `clofin-cockpit` (connect + seed + bootstrap) |
| **Status** | `CLOSED` — part A merged in PR #23 (`f174116`), part B merged in `clofin-cockpit` PR #2 (`90abb1d`), 2026-08-15; three objections ruled for the Worker, see Changelog |
| **Depends on** | TASK-011 ✅ closed (PR #21 + cockpit PR #1); the cockpit live on Pages |
| **Base branch** | `clofin-core` `main` at `f10974c` or later; `clofin-cockpit` `main` at `7b0b8d6` or later |
| **Requirements** | Driver D5; the cockpit plan (Phase 2, **minus** the Codespaces driver — deliberately deferred, see Out) |
| **Controls touched** | None move. CORS is a deliberate exposure decision recorded in an ADR, default-closed; it is not a control claim |
| **Scope** | Large — a middleware change in the release-audit subject, plus the cockpit's first write path |
| **Audit** | `012-REQ` filed 2026-08-15 (`docs/audits/012-REQ-cockpit-connect-and-bootstrap.md` on `main`); the `clofin-core` half is inside the 2026-09-01 Sol audit's scope, with N-1 pre-declared as audit input |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

Phase 1 shows releases; it cannot touch a running system. After this task the
cockpit connects to a CloFin instance the operator started (the Compose card,
or a Codespace they opened themselves — its forwarded HTTPS URL is just a base
URL), proves what that instance is from the instance's own answers, and
bootstraps a synthetic organisation through the real API — actors, roles,
ledger accounts, approval thresholds — from a versioned seed profile, with
every request and response shown raw. The cockpit's first write path, under
the doctrine that makes it safe: **it drives the real API and displays real
responses; it computes and claims nothing.**

## Scope

### In — part A, `clofin-core` (release-audit scope; keep it exactly this small)

1. **ADR-0027 — browser clients: the CORS allowlist and instance
   self-identification.** Records both decisions below with rejected
   alternatives.
2. **A CORS allowlist, default-closed.** An environment-configured list of
   allowed origins (unset ⇒ **no CORS headers at all and preflights refused —
   today's behaviour, byte for byte**). For a configured origin: correct
   preflight answers for the methods and headers the API actually uses
   (discover them — the principal header, `Content-Type`, `Idempotency-Key` —
   do not guess), no wildcard ever, no reflection of arbitrary origins.
   Tests in both directions: a configured origin gets exactly the right
   headers; an unlisted origin and an unset config get none, and their
   preflights fail. Wire the compose stack so `make up` can pass an allowlist
   through (documented, default empty).
3. **Instance self-identification.** `GET /` gains a `sourceCommit` field —
   resolved at startup from configuration stamped by the build/run path
   (`make up` passes it; a source checkout resolves it from git), and the
   literal string `"unknown"` when unresolvable — **never guessed, never a
   branch name** (the 011-REQ O-1 rule, applied server-side). The OpenAPI
   contract and its description state plainly: *self-reported by the running
   process, not attested*. L-14 applies to that sentence.
4. Nothing else. No new endpoints, no auth changes, no schema changes.

### In — part B, `clofin-cockpit`

5. **The build guard evolves, deliberately.** Phase 1's build refused `<form>`
   and `<input>` because nothing legitimate needed one. Phase 2's purpose is
   forms that drive the real API, so the refusal list changes shape: forms and
   inputs are permitted; **still refused mechanically** — scripts from
   anywhere but self, any network origin that is neither `api.github.com` nor
   an operator-connected instance origin, any telemetry, and any persistence
   of anything that is not the instance registry. Record the evolution in a
   cockpit ADR (`docs/ADR/0002-…`), including what deliberately did not change.
6. **Connect to an instance.** The operator enters a base URL. The cockpit
   calls that instance's `GET /` and `GET /readyz` and renders what the
   instance itself answers: its verbatim `disclaimer`, its `environment`, its
   `schemaVersion`, its self-reported `sourceCommit` labelled **self-reported**
   (and, when it equals a known `ref-<n>` tag's commit from the Tags API, the
   tag and its release-audit coverage beside it — matched, never assumed).
   **An instance whose `GET /` does not carry the CloFin disclaimer field is
   refused**: the cockpit does not drive systems that do not identify
   themselves as this synthetic reference implementation. The instance
   registry (URLs and display state only) lives in `localStorage`; a
   mixed-content note is rendered where relevant (an `https` page may call
   `http://localhost` in current browsers — verify this in a real browser, as
   011 verified CORS — but any other plain-`http` origin will be blocked, and
   the UI says so rather than failing opaquely).
7. **Seed profiles.** Versioned JSON documents in the repository — start with
   two: `uat-standard` (the UAT actor set, the `1100/1300/2100` chart, the
   payments threshold table) and `high-value-two-approver`. Each profile
   declares the exact sequence of API calls it will make. A profile is data;
   the runner is code; neither embeds the other.
8. **The bootstrap runner.** Executes a profile against the connected
   instance: organisation → actors and roles → ledger accounts → approval
   thresholds, strictly sequential, halting on the first failure with the
   failing step named. **Every step renders the raw request and the raw
   response** beside the pretty view — the transparent-client doctrine; the
   cURL equivalent is one click away. Synthetic per-instance credentials the
   bootstrap creates are held for the session, sent only to that instance's
   origin, cleared with the registry entry, and never rendered into built
   output or committed anywhere.
9. **CI stays at exactly two checks** (`scope-verbatim`,
   `no-unqualified-audited`), extended over the new pages, not multiplied.

### Out — and why

| Out of scope | Reason |
|---|---|
| The Codespaces driver, and any PAT handling | Deliberately deferred to its own phase: a token is a different risk class, and this brief already changes the audited middleware. The README's forward-tensed rule 3 stays forward-tensed |
| Payment/settlement/reconciliation operation flows, scheme simulation | Phase 3. Bootstrap first; operating comes when connecting and seeding are proven |
| The Actions scenario runner | Phase 4 |
| Any auth-mechanism change in `clofin-core` | The existing principal model is what the cockpit drives; changing it is not a cockpit need |
| Proxying, relaying or hosting anything server-side | The cockpit remains a static page; the browser talks to the instance directly — that is what the CORS increment exists for |

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given no CORS configuration, then `clofin-core`'s responses are byte-identical to today's — no CORS header appears anywhere and preflights are refused; the full existing suite passes unchanged. | default-closed |
| AC-2 | Given a configured origin, then preflight and actual requests succeed from a real browser page served on that origin, and an unlisted origin's requests fail in that same browser — verified in a browser, not only with curl (the 011 lesson: preflight faults are invisible to non-browser tests). | part A.2 |
| AC-3 | Given `make up` with the commit stamp wired, then `GET /` reports the checkout's commit; unresolvable ⇒ literally `"unknown"`; the contract test covers the field and its description says self-reported. | part A.3, L-14 |
| AC-4 | Given a connected instance, then the cockpit renders the instance's own disclaimer, schema version and source commit, labelled self-reported, with tag + coverage beside it only when the commit matches a real tag. | honesty |
| AC-5 | Given a base URL whose `GET /` lacks the disclaimer field, then the cockpit refuses to connect and says why. | honesty gate |
| AC-6 | Given the `uat-standard` profile against a fresh instance, then the bootstrap completes, each step showing raw request and response; re-running it against the same instance does not double-create (use the API's own idempotency affordances; where none exists, detect-and-report, never silently skip). | part B.8 |
| AC-7 | Given a mid-sequence failure (e.g. a threshold step rejected), then the runner halts naming the step, with everything before it visible and nothing after it attempted. | part B.8 |
| AC-8 | Given the built output, then no script from a non-self origin, no telemetry, no credential in any built file or commit; the network guard's new allowlist shape is tested; both CI checks green and still exactly two. | part B.5 |
| AC-9 | Given ADR-0027 (core) and cockpit ADR-0002, then each records its decision with rejected alternatives; ADR-0027's CORS text never claims more than the enforcement does. | L-14 |

## Definition of done

- [ ] Every AC has a named test or check; AC-2 verified in a real browser with the evidence in the REQ
- [ ] `clofin-core`: `make verify` and the integration suite green; contract test covers the `GET /` field
- [ ] Two PRs, cross-referenced; cockpit CI green; Pages deploy green after merge
- [ ] REQ filed as `012-REQ-…` in `clofin-core`'s `docs/audits/`, provenance header, objections, and the L-9 statement

## Notes for whoever picks this up

**Part A is about to be audited.** The Sol release audit runs 2026-09-01 and
this middleware lands inside its scope. Default-closed is not a preference —
it is the property the audit will attack first.

**The bootstrap is the cockpit's first write path.** If a step ever feels like
it should "just fix" something the API refused, stop: the refusal is the
product. Render it.

---

## Changelog — rulings on the `012-REQ` objections (2026-08-15)

*(The REQ is `docs/audits/012-REQ-cockpit-connect-and-bootstrap.md` on `main`,
landed by PR #23, `f174116`.)* All three ruled at ingestion; premises
independently verified first (no actor/role/limit/threshold write endpoint
exists in the contract or the route table; the `read-key` docstring carries the
every-mutating-endpoint claim verbatim).

| # | Objection | Ruling |
|---|---|---|
| O-1 | The brief's bootstrap sequence names four creations; two of them — actors/roles and approver limits/thresholds — have **no API on purpose** (UAT-005 §2: an actor able to grant itself the approver role makes segregation of duties unenforceable). A literal runner reaches step two of six and stops forever. Adding endpoints was doubly out of scope, and rightly. | **Confirmed — brief defect, Master Control's, with a sharp edge:** the same brief that ordered "discover the header set, do not guess" specified a bootstrap sequence without checking which steps have an API. The Worker's design is **ratified as the standing pattern for every future manual step**: a `manual` step generates exact SQL, is confirmed only through a real API request whose response is shown — never by a button — and must carry a *what this cannot show* list, enforced by test. A green tick never stands for something nobody checked. The suggested brief correction is adopted as this changelog. |
| O-2 | AC-6's "the bootstrap completes" cannot be true of a browser alone — two steps require SQL the browser must not be able to run. | **Confirmed — follows from O-1.** The four-state step vocabulary (`done` / `already present` / `waiting for you` / `failed`, with `waiting for you` halting exactly as failure does) is ratified: it reports the run's true shape instead of collapsing it. |
| O-3 | "Refuse any origin that is not an operator-connected instance origin" cannot be expressed in a build-time policy — the connected set exists only after the operator types. | **Confirmed — the brief asked the build for something it cannot know.** The split is ratified: build-time, a bounded set of address *shapes* (loopback, Codespaces forwarded) with the policy's properties asserted so widening `origins.ts` fails the build; runtime, the actually-connected set, cleared with credentials in the same call. Neither half may ever be weakened to compensate for the other. The LAN-address limitation is an accepted, recorded cost. |

**N-1 — pre-declared input to the 2026-09-01 Sol audit.**
`clofin.idempotency/read-key`'s docstring claims the key is mandatory on
*every* mutating endpoint (PR-040); three endpoints neither require nor accept
it. This is the `ref-1` audit's own finding class (one of the nine L-14
instances), surviving in a **copy the remediation never enumerated** — the
L-16 shape, in code. Deliberately not fixed here (part A's scope was "nothing
else"); the audit confirms it independently and it rides the remediation
batch. **The Worker's post-PR self-review corrections (§7 of the REQ) are
accepted as filed** — an L-9 statement corrected in the open beats a fix
folded in silently, and that is the behaviour the lesson exists to produce.
