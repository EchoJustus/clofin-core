# 012-REQ — cockpit phase 2: a CORS decision, instance self-identification, connecting and bootstrapping

| Field | Value |
|---|---|
| **Brief** | `docs/briefs/012-TASK-cockpit-connect-and-bootstrap.md` — on **`origin/meta`**, which is the authoritative copy and is not edited here |
| **Increment** | 8.2 (cockpit, phase 2) — spans `clofin-core` (CORS + self-identification) and `clofin-cockpit` (connect + seed + bootstrap) |
| **Requirements** | Driver D5; the cockpit plan phase 2, minus the Codespaces driver |
| **Controls** | **None move.** CORS is a deliberate exposure decision recorded in an ADR, default-closed, and is not a control claim. `docs/COMPLIANCE.md` is untouched |
| **Base** | `clofin-core` `main` at `f10974c`; `clofin-cockpit` `main` at `7b0b8d6` |
| **Branch** | `claude/cockpit-connect-bootstrap-zye3rg` in both repositories |
| **Pull requests** | `clofin-core` **#23** · `clofin-cockpit` **#2** — cross-referenced in both descriptions |
| **Model** | `claude-opus-5` |
| **Reasoning effort** | High — extended thinking enabled throughout. The harness does not expose a numeric setting to the session, so this is reported as the mode, not as a measured value |
| **Date** | 2026-08-15 |
| **Verification still in flight** | **See [§8](#8-verification-l-9).** CI on both pull requests, and the Pages deployment, which cannot run before merge |

---

## 1. What was built

### `clofin-core` — one ADR, two behaviours, no new surface

| Piece | Where |
|---|---|
| ADR-0027, both decisions with rejected alternatives | `docs/ADR/0027-browser-clients-cors-allowlist-and-instance-self-identification.md` |
| The allowlist middleware | `src/clofin/http/cors.clj` |
| Commit resolution, without a subprocess | `src/clofin/build_info.clj` |
| Wiring | `src/clofin/config.clj`, `src/clofin/http/middleware.clj`, `src/clofin/api/health.clj` |
| Contract | `api/openapi.yaml` — `ServiceInfo.sourceCommit`, and a *Browser clients* paragraph |
| Compose and `make up` | `Makefile`, `docker-compose.yml`, `.env.example` |
| Tests | `test/clofin/http/cors_test.clj`, `test/clofin/build_info_test.clj`, additions to `config_test`, `health_test`, `contract_test` |

No new endpoint, no route, no schema, no migration, no authentication or
authorisation change, and no dependency. The preflight is answered by
middleware rather than by a route, so the route table and `api/openapi.yaml`
still describe the same operations and the contract test needed no exemption.

### `clofin-cockpit` — connect, seed, run

| Piece | Where |
|---|---|
| The permitted address rule, and the policy generated from it | `src/origins.ts` |
| The network seam, now recording every exchange | `src/net.ts` |
| The honesty gate and the tag match | `src/instance.ts` |
| The instance registry — the only thing persisted | `src/registry.ts` |
| Session-only synthetic credentials | `src/credentials.ts` |
| Profile reading, fail-closed | `src/profiles.ts` |
| The two shipped profiles | `profiles/uat-standard.json`, `profiles/high-value-two-approver.json` |
| The runner | `src/bootstrap.ts` |
| Raw request/response rendering | `src/raw-view.ts` |
| The instance screens | `src/views-instance.ts`, `src/main.ts` |
| The evolved refusal | `tools/guard-network.mjs`, `tools/build.mjs` |
| Both checks, extended | `tools/check-scope-verbatim.mjs`, `tools/check-no-unqualified-audited.mjs` |
| The decision | `docs/ADR/0002-…md` |

## 2. The four decisions that shaped everything else

**Default-closed is an identity, not a policy.** With no allowlist configured,
`clofin.http.cors/wrap-cors` returns **the handler it was given** — the same
function object, not a wrapper that decides to add nothing. There is therefore
no configuration under which an unconfigured service can answer differently
from the one that shipped before this middleware existed: not a header, not a
`Vary`, not a preflight. The test asserts `identical?`, because equality of
observed behaviour is exactly what a bug in this area looks like.

**The two CORS answer lists are discovered from the service, not written beside
it.** `Access-Control-Allow-Methods` is asserted equal to the set of methods in
`clofin.routes/routes`. `Access-Control-Allow-Headers` is asserted equal to the
set of header names found by reading every `.clj` file under `src/`, walking
each form for `(get-in request [:headers …])`, and resolving the symbol when the
header is named by a `def` — which three of the four are. The scan reports:

```
REQUEST HEADERS DISCOVERED IN src/ : #{"content-type" "idempotency-key" "x-actor-id" "x-correlation-id"}
METHODS IN ROUTE TABLE             : #{"DELETE" "GET" "PATCH" "POST"}
ALLOW-HEADERS EMITTED              : content-type, idempotency-key, x-actor-id, x-correlation-id
ALLOW-METHODS EMITTED              : GET, POST, PATCH, DELETE
EXPOSE-HEADERS EMITTED             : location, x-correlation-id, allow
```

The instruction was to discover the real header set rather than guess it, and
the reason is the specific way a guess fails: a missing entry in
`Access-Control-Allow-Headers` is invisible to every test that is not a browser,
because a browser is the only client that sends a preflight at all. 011-REQ §7
is the record of that failure mode costing a debugging session.

`Access-Control-Expose-Headers` names three headers explicitly. `location`
carries the identity of a resource a page just created and is otherwise
invisible to the page that created it; `x-correlation-id` is what CloFin's own
error responses tell a caller to quote; `allow` is what a `405` says.
`content-type` is CORS-safelisted and needs no naming. Never `*`.

**`sourceCommit` is resolved or it is the literal `"unknown"`, and the contract
enforces the difference.** The stamp is used only if it is 40 lower-case hex
characters, so `CLOFIN_SOURCE_COMMIT=main` is ignored rather than published; a
checkout is read down to a commit through `HEAD`, loose refs, `packed-refs` and
a linked worktree's `commondir`, and **the ref name itself is never returned**.
`api/openapi.yaml` declares `pattern: "^([0-9a-f]{40}|unknown)$"`, and a
contract test asserts that pattern rejects `main`, `HEAD`, `ref-1`, a short SHA
and the empty string — so the 011-REQ O-1 rule is in the specification and not
only in the implementation. The repository is **read**, not `git` **run**: the
container image carries no `git`, so a subprocess would work where it does not
matter and fail where it does.

**The cockpit's origin rule exists once and is enforced three times.**
`src/origins.ts` holds the permitted shapes of instance address. From that one
constant come the runtime refusal (with the sentence an operator reads), the
`connect-src` the build renders into the page, and the guard's list of hosts
that may appear in the output at all. The runtime seam is stricter than the
shape: `net.ts` contacts an instance origin only if a connection put it in the
connected set, and forgetting an instance withdraws it in the same call that
drops its credentials.

## 3. Objections

Three, all filed rather than worked around.

### O-1 — Two of the four things the bootstrap is specified to create have no API, deliberately

The brief's scope item 8 says the runner executes a profile *"against the
connected instance: organisation → actors and roles → ledger accounts →
approval thresholds"*, and AC-6 requires that the run **completes**.

**Two of those four cannot be done through the API, and their absence is a
control decision this project made on purpose.** `clofin-core` has no endpoint
that creates an actor, grants a role, sets an approver limit or configures an
approval threshold. Its own capture harness says why:

> Seeding actors and roles is done in SQL because CloFin deliberately has no
> endpoint that creates an actor or grants a role — an actor able to grant
> itself the approver role would make segregation of duties unenforceable
> however carefully the rule is written (UAT-005 §2).
> — `tools/clofin/tools/capture/recorder.clj`

The route table confirms it: `POST /organisations`, `POST /accounts` and the
payment, settlement and reconciliation endpoints exist; nothing writes `actor`,
`actor_role`, `approver_limit` or `approval_threshold`. Every UAT script seeds
them with SQL through `make db-shell`.

The consequence is sharper than it first looks, and it is why this is an
objection rather than a note. Only **one** of the six steps in a fresh-instance
bootstrap can be performed by an unauthenticated caller: `POST /organisations`.
Everything after it needs an `X-Actor-Id` naming a row that only SQL can
create. A runner built literally to the brief would reach step two and stop
permanently.

Adding the endpoints was not available either: part A's scope item 4 says *"No
new endpoints, no auth changes"*, and the Out table rules out any auth-mechanism
change. Both are right — an endpoint that grants roles is exactly what UAT-005
§2 refuses.

**What I did instead.** A profile has two kinds of step. A `request` step is
made against the API for real. A `manual` step generates the exact SQL for the
operator to run against their own instance — the shape phase 1 already
established with the Compose card, which is also text you read and run yourself
— and is then **confirmed through the API** by a real request whose real
response is shown. The cockpit never marks such a step done because a button was
pressed: pressing it before the SQL has been run produces

> Not confirmed. Expected 200; the instance answered 401 Unauthorized. The
> statements have not taken effect on this instance, or an actor id differs from
> the one this page holds.

which is what the browser run in §6 shows happening. And because a confirmation
through one endpoint cannot demonstrate everything a seed did, every manual step
is **required by a test** to carry a *what this cannot show* list. The thresholds
step says outright that CloFin exposes no read for `approval_threshold`, so
nothing the cockpit can ask demonstrates the rows are there.

This keeps the doctrine the brief's own closing note states — *"If a step ever
feels like it should 'just fix' something the API refused, stop: the refusal is
the product"* — and extends it one step further: where the API does not offer
something at all, say so, and never let a green tick stand for something nobody
checked.

**Suggested brief correction:** scope item 8 should say *organisation and ledger
accounts through the API; actors, roles, limits and approval thresholds as
generated SQL the operator runs, each confirmed through the API where a
confirmation exists and reported as unconfirmable where it does not*.

### O-2 — AC-6's "the bootstrap completes" cannot be true of a browser alone

Following from O-1: AC-6 says *"Given the `uat-standard` profile against a
fresh instance, then the bootstrap **completes**"*. A run cannot complete
without the operator running two SQL blocks outside the browser, because the
browser has no path to the database and must not have one.

**What I did instead.** The run does complete — six of six steps, confirmed
against a real instance, in §6 — with two of the steps requiring an action the
operator takes and the cockpit then checks. The runner reports its state
precisely rather than collapsing it: a step is `done`, `already present`,
`waiting for you`, or `failed`, and `waiting for you` halts the run exactly as a
failure does until the instance says otherwise.

**Suggested brief correction:** AC-6 should read *the bootstrap completes, with
the manual steps run by the operator and confirmed through the API*.

### O-3 — "an operator-connected instance origin" cannot be expressed in a static policy

The brief's scope item 5 requires the build to refuse *"any network origin that
is neither `api.github.com` nor an operator-connected instance origin"*. In
phase 1 "refused mechanically" meant the build refuses — a static check over
static output. **A connected instance origin is not static.** It is a URL the
operator types after the site is built, and a `Content-Security-Policy` is
markup written before they have typed anything. No build-time check can express
the set.

**What I did instead.** The requirement is split across the two places that can
each hold half of it, and neither is allowed to be the weaker one.

- **Statically**, the policy permits two *shapes* of address rather than an open
  set: a loopback port on the operator's own machine, and a GitHub Codespaces
  forwarded port — which are the two deployment paths the brief itself names
  (the Compose card, and *"a Codespace they opened themselves"*). It admits no
  scheme source and no wildcard host, and the build asserts those properties of
  the generated policy, so widening `origins.ts` fails the build.
- **At runtime**, `net.ts` refuses any origin that is not `api.github.com`, this
  page's own, or an instance **actually connected** — added by `registry.connect`
  and removed by `registry.forget` in the same call that clears its credentials.
  That is the half the brief asks for, and it is where the "connected" part can
  be true at all.

The accepted cost is stated in ADR-0002: an operator running an instance on a
LAN address or behind their own domain cannot connect it without a deliberate,
reviewed change to `origins.ts`.

**Suggested brief correction:** scope item 5 should distinguish the build-time
refusal (a bounded set of address shapes, and the policy's properties) from the
runtime refusal (the connected set), rather than asking the build for something
it cannot know.

## 4. Notes and readings recorded

**N-1 — a contract sentence in `clofin-core` is wider than its enforcement, found
while looking for the API's own idempotency affordances.** AC-6 says to use
them, so I went looking. `clofin.idempotency/read-key` says:

> The header is **mandatory** on every mutating endpoint (PR-040): a request
> that omits it is `400` rather than being quietly executed…

It is read by `clofin.api.payments` and `clofin.api.approvals` and by nothing
else. `POST /organisations`, `POST /accounts` and `POST /journal-entries` are
mutating endpoints that neither require nor accept it, which the OpenAPI
document is consistent with — none of them declares the parameter. So the
sentence is true of the endpoints TASK-002 was about and reads as true of all of
them.

That is the **L-14 shape** — a claim stated over a set larger than the one it is
enforced on — inside a docstring rather than a contract, and it is in the
release-audit subject with a Sol audit due 2026-09-01. **Reported, not fixed**:
part A's scope is "nothing else", and a fix is either a docstring correction or
three endpoints acquiring a required header, which is a behaviour change nobody
asked for. Named here so it is on the record before the audit rather than after.

Its practical consequence for this increment is O-1's neighbour: the bootstrap's
two write endpoints have **no** idempotency key available, which is why the
brief's "where an endpoint has none, detect and report" clause is the path
actually taken for both of them.

**N-2 — a failed precheck is read as "not detected", never as "not present".**
When an account step's `GET /accounts` precheck fails — as it does in the AC-7
run below, where the actor has just been suspended — the runner does not
conclude the account is absent. It attempts the create, whose own answer is
authoritative: `201` means it was not there, `409` is the profile's declared
conflict and reports *already present*, and anything else halts. Both exchanges
render. The alternative readings are both worse: treating a failed precheck as
"absent" would let a transient error cause a duplicate attempt with no record,
and treating it as "present" would silently skip a step, which the brief
forbids.

**N-3 — 011-REQ's N-5 recurred twice, which is the rule working.** The
`no-unqualified-audited` check reads the deployed comments, and this repository
therefore reserves *audited*, *verified* and *reviewed*. Two things I wrote had
to be rephrased: the runner's success message, which now reads *"Confirmed by
the running instance"*, and a `registry.ts` comment that named three browser
storage APIs the guard refuses everywhere. Both are recorded in the source at
the point of the rephrasing so the next contributor is not surprised.

**N-4 — the cockpit's tag match has three answers and only one of them is a
tag.** *Matched* renders the tag and its coverage; *no match* says the commit is
not any published `ref-<n>`; *not checked* says the release list was not read.
The third exists because collapsing it into the second would be a fail-open
default of exactly the shape lessons **L-6** and **L-13** record. Building this
found a real ordering defect in my own code, described in §7.

**N-5 — this session ran the integration suite, which the previous one could
not.** 011-REQ §7 noted that the database and Compose jobs were first executed
in CI. This session installed PostgreSQL 16 locally and ran `clojure -M:test:it`:
**885 tests, 6477 assertions, 0 failures, 0 errors**. The Compose smoke job
remains CI-only — there is no Docker daemon in this environment.

**N-6 — `docs/ROADMAP.md` on `origin/meta` is still stale in the way 011-REQ N-2
reported.** Increment 8 still reads "React/TypeScript console"; the cockpit's
own ADR-0001 chose TypeScript **without** React, with React/Vite recorded as a
rejected alternative. Unchanged since 011 and repeated here rather than assumed
noticed. `meta` is Master Control's alone.

## 5. The negative controls, run for real

### The build guard, evolved — four controls

Each was introduced into the source, `node tools/build.mjs` run, and the source
restored. Verbatim:

**A module other than the registry naming the browser store**

```
  - js/views.js: names localStorage. The instance registry is the only thing this
    repository persists, and js/registry.js is the only module that may write it.

1 problem(s). See tools/guard-network.mjs for what is refused and why.
```

**The policy widened to a scheme source** (`cspSources: ["https:"]` in `origins.ts`)

```
  - src/origins.ts produces a Content-Security-Policy containing a bare http: or
    https: scheme source, which is every host there is. The policy may be
    narrowed but not widened; see docs/ADR/0002.
```

This is the control that matters most, because it is the one a contributor
would trip while trying to make something work. The guard reads the policy from
the built module and then holds it to properties — so it cannot be satisfied by
editing the file it guards.

**A credential written into a built file**

```
  - js/views.js: contains a UUID — the synthetic actor ids a bootstrap run mints
    live in memory for a session and must never appear in a built file.
```

**A CDN `<script>` in the page** — phase 1's control, still refused

```
  - index.html: refers to cdn.jsdelivr.net, which is not an allowed host.
    Allowed: api.github.com, github.com, www.w3.org, localhost, 127.0.0.1.
  - index.html: <script src="https://cdn.jsdelivr.net/npm/chart.js"> loads a
    subresource from another origin. Everything this page loads is its own.
```

In every case `npm run build` exited **1** and `_site` was **removed** —
confirmed by `ls` immediately afterwards — which is the difference between
refusing and reporting. Restored, the same command exits 0 and the built page
contains a `<form>`, which phase 1's build would have refused.

### The CORS refusals, at start-up

`cors_test` asserts that four wildcard forms and eleven malformed origins refuse
the service to start, and that one bad entry in a list refuses the whole list.
The wildcard message is asserted to name the variable and to say *"There is no
wildcard"*, because an operator who wrote `*` and saw the service come up would
conclude it had been honoured.

## 6. The browser evidence

Everything below ran in **Chromium 1194** driven by Playwright, against
**`clofin-core` running for real** on this branch's code over a real PostgreSQL
16. The cockpit was served from a local **https** origin with a self-signed
certificate, so the instance calls exercise the mixed-content path a page on
GitHub Pages will use.

### AC-2 — CORS, both directions, in a real browser

A minimal page makes three requests: a CORS-simple `GET /`, a preflighted
`POST /organisations` (`Content-Type: application/json` is not a safelisted
value), and a preflighted `GET /accounts` (`X-Actor-Id` is a custom header). The
service ran with `CLOFIN_CORS_ALLOWED_ORIGINS=http://127.0.0.1:5173,https://127.0.0.1:4443`.
The same page was served on `:5173` (listed) and `:5174` (not).

**Allowed origin — `http://127.0.0.1:5173`:**

```json
{"name": "simple GET /",                    "outcome": "read", "status": 200,
 "body": "{\"service\":\"clofin-core\",…\"disclaimer\":\"CloFin operates on synthetic "}
{"name": "preflighted POST /organisations", "outcome": "read", "status": 201,
 "body": "{\"id\":\"d09a9909-8045-4468-90d3-2edd901353be\",\"legalName\":\"AC-2 Probe Pte Ltd\"…"}
{"name": "preflighted GET /accounts",       "outcome": "read", "status": 401,
 "body": "{\"type\":\"https:\\/\\/clofin.dev\\/problems\\/unauthorised\",\"title\":\"Authentication required\"…"}
```

The `401` is the point of including the third case: a **refusal** is readable,
which is what a client whose product is showing what the system answered needs
most.

**Unlisted origin — `http://127.0.0.1:5174`, same service, same moment:**

```json
{"name": "simple GET /",                    "outcome": "blocked", "error": "TypeError: Failed to fetch"}
{"name": "preflighted POST /organisations", "outcome": "blocked", "error": "TypeError: Failed to fetch"}
{"name": "preflighted GET /accounts",       "outcome": "blocked", "error": "TypeError: Failed to fetch"}
```

with the browser's own console explaining each:

```
Access to fetch at 'http://127.0.0.1:8080/' from origin 'http://127.0.0.1:5174'
  has been blocked by CORS policy: No 'Access-Control-Allow-Origin' header is
  present on the requested resource.
Access to fetch at 'http://127.0.0.1:8080/organisations' from origin 'http://127.0.0.1:5174'
  has been blocked by CORS policy: Response to preflight request doesn't pass
  access control check: No 'Access-Control-Allow-Origin' header is present on
  the requested resource.
Access to fetch at 'http://127.0.0.1:8080/accounts' from origin 'http://127.0.0.1:5174'
  has been blocked by CORS policy: Response to preflight request doesn't pass
  access control check: No 'Access-Control-Allow-Origin' header is present on
  the requested resource.
```

Every one of the six requests appears in Playwright's wire log; from `:5174` all
three are `requestfailed / net::ERR_FAILED`. **The `POST` from the unlisted
origin never executed** — the preflight was refused, so the write did not
happen, which is not something `curl` can show you and is the substantive
difference between the two halves of this criterion.

### AC-1 — the same, with the allowlist removed entirely

The service was restarted with **no** `CLOFIN_CORS_ALLOWED_ORIGINS` at all, and
the *previously allowed* origin re-tested:

```
=== curl: GET / with an Origin, no CORS configured ===
HTTP/1.1 200 OK
Content-Type: application/json
x-correlation-id: edfa6ffc-e376-49ed-80d1-74e8e144b9d4
Content-Length: 426
```

No `Access-Control-*`, and no `Vary` — the response is the one that shipped
before this middleware existed.

```
=== curl: OPTIONS preflight, no CORS configured ===
HTTP/1.1 405 Method Not Allowed
Content-Type: application/problem+json
Allow: POST
```

and in the browser, from `http://127.0.0.1:5173`, which had worked minutes
earlier: all three requests `blocked`, with *"No 'Access-Control-Allow-Origin'
header is present"* for the simple one and *"Response to preflight request
doesn't pass access control check"* for both preflighted ones.

### AC-3 — the reported commit

`GET /` on the instance started from this checkout, with nothing stamped:

```json
"sourceCommit":"c84ddaa59fbef7d060fc76bbbabdfd20440694f5"
```

resolved from `.git` — the branch was followed to its commit and the branch name
never appeared. A second instance started with
`CLOFIN_SOURCE_COMMIT=5c7b4badced5e807e1022fce44cbcad38c6d2095` reported that,
which is what the tag-match case below uses.

### AC-4 — identity, and a tag that is matched rather than assumed

Against the instance stamped with `ref-1`'s commit:

```
Identity, as reported
Service          clofin-core
Environment      dev
Schema version   0013
Source commit    5c7b4badced5e807e1022fce44cbcad38c6d2095

The source commit is self-reported by the running process, not attested. It is
what that process says it was built from; nothing in this exchange demonstrates
that the bytes answering are the bytes at that commit.

PUBLISHED TAG    ref-1 — release-audit coverage: PARTIAL — charter items 1-4 of 8
Matched: this commit is the dereferenced SHA of ref-1 in EchoJustus/clofin-core's
Tags API. The coverage beside it is read from that release's own body.
```

Against the instance running this branch, whose commit is no published tag, the
same region reads *"this commit is not any published ref-&lt;n&gt; tag"*, and the
disclaimer card reads:

```
CloFin operates on synthetic data only. It is not connected to any bank, payment
scheme or central bank, holds no regulatory authorisation, and never processes
real funds.

This is the same sentence this page carries in its frame, byte for byte.
```

The honesty frame was captured while an instance was on screen and is intact,
carrying the canonical statement and *"owns no truth"*.

### AC-5 — an instance that does not say what it is

A service answering `200` with JSON — and with permissive CORS of its own, so
that the refusal could only come from the cockpit's gate — was offered:

```
Refused
Refused: this service's GET / carries no disclaimer field. Every CloFin instance
states its scope there, and the cockpit does not drive a system that does not say
what it is. The raw response is below — nothing was read from it beyond this check.

GET http://localhost:8099/   200 OK   4 ms
```

`localStorage` afterwards: `[]`. Nothing was remembered, and the origin's
permission was withdrawn.

### AC-6 — the bootstrap, and a re-run

The `uat-standard` profile against a fresh instance, one step per click.

Step 1, `POST /organisations` — the raw exchange as rendered, including the
`location` header the page can read only because `Access-Control-Expose-Headers`
names it:

```
201 Created
location: /organisations/11e902b8-42c7-4f7b-801b-6c8dcc7331af
x-correlation-id: 32a961ad-0e01-437b-9856-88df76445892
{"id":"11e902b8-…","legalName":"Meridian Freight Holdings Pte Ltd",
 "shortName":"cockpit-uat-standard","status":"active"}
```

with the `curl` beside it. Step 2 generated the seed SQL with the minted ids
substituted and no placeholder left behind. **Pressing "I have run them" before
running it** produced:

```
Not confirmed. Expected 200; the instance answered 401 Unauthorized. The
statements have not taken effect on this instance, or an actor id differs from
the one this page holds.
```

The SQL was then run through `psql`, as an operator would, and the same button
produced:

```
Confirmed by the running instance: 200 OK. The instance accepted this actor id,
resolved it to an actor in this organisation, and found the controller role's
account/read permission on it. A 401 would mean no such actor; a 403 would mean
the actor exists without that permission.
```

beside its own *what this cannot show* list. Steps 3–5 opened the three
accounts, each preceded by a real `GET /accounts`; step 6 generated the
threshold SQL and stated plainly that the bands themselves cannot be
demonstrated by anything the cockpit can ask. Final state: **6 of 6 steps
complete**.

**Re-running the same profile against the same instance**, step 1:

```
FAILED
409 Conflict — This instance already has an organisation with this short name, so
nothing was created a second time — the instance refused it. The run stops here.
CloFin has no endpoint that looks an organisation up by short name, so the cockpit
cannot learn the existing one's id and will not guess it.
```

with the instance's own problem document rendered whole. Counted afterwards in
the database: **one** organisation with that short name. During the first run's
own account steps the precheck path was also exercised, reporting *already
present* and not sending the create.

### AC-7 — a failure in the middle of a run

A fresh instance, steps 1–3 completed, and then the instance changed underneath
the run: the controller actor was suspended by `UPDATE`, the way an operator's
own environment can change mid-sequence. Step 4:

```
4  Open 1300-IN-TRANSIT   FAILED
Expected 201; the instance answered 403 Forbidden.

{"type":"https://clofin.dev/problems/forbidden","title":"Not permitted","status":403,
 "detail":"This actor may not read","errors":{"permission":"account/read",
 "actor-status":"suspended"}}

Halted at account-1300
2 later step(s) were not attempted. The run does not continue past a failure, and
nothing here retries on your behalf.

Progress  Halted at step 4 of 6 — account-1300. 3 step(s) completed before it;
          nothing after it was attempted.
```

Asking for the next step again produced **zero** further requests. The instance
afterwards held exactly one account, `1100-CLIENT-FUNDS`. Everything before the
failure remained on screen with its exchanges.

### AC-8 — what the page did and what it kept

Every origin the page contacted across the whole session:

```
https://127.0.0.1:4443   (its own)
https://api.github.com   (releases and tags)
http://localhost:8099    (the instance it then refused)
http://localhost:8081    (an instance the operator connected)
http://localhost:8080    (an instance the operator connected)
```

Browser storage at the end:

```json
{"localStorage": {"clofin-cockpit.instances.v1":
   "[{\"baseUrl\":\"http://localhost:8081\",\"label\":\"stamped as ref-1\"},
     {\"baseUrl\":\"http://localhost:8080\",\"label\":\"local compose\"}]"},
 "sessionStorageLength": 0,
 "cookies": ""}
```

Base URLs and labels. No actor id, no organisation id, no token. The console
carried two lines across the whole session, both of them the browser noting a
`401` and a `409` that the page was displaying deliberately.

**Mixed content, checked rather than assumed.** The page was served over `https`
and called `http://localhost:*` throughout, successfully — browsers treat
loopback as potentially trustworthy. That is the specific thing the brief asked
to be verified in a real browser, and it is verified for Chromium; the interface
states the limitation for any other plain-`http` address rather than letting a
blocked request fail opaquely.

## 7. A defect this found that nothing else would have

The first full browser run rendered, beside a commit that **is** `ref-1`'s:

> not checked — the published releases could not be read

The tag was real, the Tags API was reachable, and every unit test passed. The
fault was ordering: connecting to an instance from the form could complete
before `fetchReleaseRecords` resolved, so the comparison ran against a `null`
release list, and `start()` then returned early without a final render — so the
honest-but-wrong "not compared" text stayed on screen after the comparison had
become possible. A second defect sat beside it: `start()` re-entered the
connection for a deep link after the fetch, connecting twice for one action.

Both are fixed — the deep-link connection now runs alongside the release fetch,
and the render after it is unconditional — and the wording now distinguishes
three states rather than two: *could not be read*, *has not finished loading*,
and *is not any published tag*.

The reason it is worth recording is what it says about the fail-closed design.
The bug produced a **safe** wrong answer: it under-claimed, showing no tag where
a tag existed. A fail-open design with the identical bug would have shown a
stale or defaulted tag beside a live instance, and nothing on the screen would
have looked wrong.

### Two more, from a self-review run after the pull requests were opened

This document originally said no self-review of mine was outstanding. That was
written before I ran one, and it was wrong to say — so here is what the review
found, and the correction is on the record rather than quietly folded in.

**A refusal could delete an address the operator had saved.** `openInstance`
withdrew the registry entry on any refusal. For an address that turned out not
to be CloFin, that is right. For one the operator had connected before and
whose machine was simply off, it is not: a stopped instance produces exactly
the same *"did not answer"* refusal, and losing a saved address because a
laptop was closed is a surprising thing for a refusal to do. Now the permission
and the credentials are withdrawn either way, and the **entry** goes only if the
address was not already remembered. Checked in the browser: an instance
connected, then stopped, then re-selected — the refusal renders and
`localStorage` still holds it; an address that was never remembered leaves
nothing behind.

**A confirmed manual step still offered its "I have run them" button.** Clicking
it after the step was done either did nothing or asked about a different step.
The statements stay on screen — they are what happened — but the button now
appears only while that step is the one waiting.

Both are user-interface defects rather than honesty defects, and neither could
have produced a false claim: a lost registry entry loses an address, and the
stale button asks a question the runner answers with "not that step". They are
recorded because the sentence they contradict was published first.

## 8. Verification (L-9)

**What I ran, and its results.**

- `clofin-core` `make verify` — **482 tests, 2935 assertions, 0 failures, 0
  errors**; diagrams OK (7 artifacts); document consistency OK; documentation
  links OK (87 files). The base run before any edit was 438 tests / 2779
  assertions.
- `clofin-core` `clojure -M:test:it` against a real PostgreSQL 16 — **885 tests,
  6477 assertions, 0 failures, 0 errors**.
- `clofin-cockpit` clean-room `npm ci && npm run build` — **126 tests, 126
  passed, 0 failed** (45 before this increment); both checks green; the network
  guard clean.
- The four build-guard negative controls in §5, each run and recorded verbatim,
  each confirmed to leave no `_site`.
- The browser evidence in §6, against real instances of this branch's
  `clofin-core` on a real database.

**One thing I did not measure, stated rather than implied — and it is the same
one 011-REQ §7 recorded.** Every request out of this session traverses the
environment's agent proxy, which authenticates them. This session's Chromium
reached `api.github.com` without being pointed at that proxy, but the network
egress is authenticated regardless, so **the anonymous read path was not
exercised here**. What is established is narrower than "unauthenticated reads
work": the cockpit *sends* no credential (`credentials: "omit"`, no
authorisation header, and the build fails if either appears), `clofin-core` is a
public repository, and the cockpit's own release browser worked throughout.
Anonymous access should therefore work in production, but that expectation is an
inference from those facts and not something this session measured. What is
proved here is the **mechanism**, not the anonymous path.

The same boundary applies to AC-2 in a different direction, and it is worth
being precise: the CORS evidence is **not** affected by the proxy. Those
requests never left the machine — the browser, the page's origin and the
instance were all local — and the browser's refusal is a decision the browser
made locally from headers the local service sent. AC-2 is measured, not
inferred.

**The self-review is done, and it found two things** — both described in §7,
both fixed, both re-checked in the browser, and the whole browser drive re-run
afterwards with the same results. **Nothing of mine is now outstanding.** The
earlier version of this sentence claimed that before the review had happened;
the correction is in §7 rather than removed.

**Three things are still running or not yet runnable, and none is mine:**

1. **CI on `clofin-core` #23 and `clofin-cockpit` #2.** `clofin-cockpit` #2's
   *Build the cockpit and check it* completed **success** on both its push and
   pull-request runs, and `pages.yml` correctly did not run. `clofin-core` #23
   at `159e1bc`: all **six** check runs completed **success** — *Unit, property
   and contract tests*, *Database integration tests* and *Compose stack smoke
   test*, once for the push event and once for the pull-request event. The
   Compose smoke job is one this session could not run — there is no Docker
   daemon in this environment — so CI is its first execution, and it passed.

   The caveat is the same one 011-REQ recorded and it is unavoidable: **the
   commit carrying this paragraph re-triggers both**, and its result cannot be
   known from inside the document that causes it. That commit also carries the
   two §7 fixes, so it is not documentation-only; its runs are named in the
   completion report rather than pre-declared green here.
2. **The Pages deployment has not run and cannot yet.** `pages.yml` triggers on
   `main` only, by design, so the first deploy happens after `clofin-cockpit` #2
   merges. 011-REQ's **N-4a** is unresolved as far as this session can tell:
   Pages from a private repository needs a plan that supports it, and the Pages
   source must be set to **GitHub Actions**. Neither is mine. **No cockpit URL is
   claimed to be live in this REQ**, because none is.
3. **Nothing else.** The local PostgreSQL instance, the four HTTP servers and the
   two `clofin-core` processes this session started for the browser evidence are
   scratch infrastructure in an ephemeral container and hold nothing anyone
   needs. Named for completeness so that "what is still running" is a complete
   answer rather than a convenient one.

## 9. Acceptance criteria

| # | Status | Evidence |
|---|---|---|
| AC-1 | ✅ | Identity, not policy: `unconfigured-returns-the-very-same-handler` asserts `identical?`; the whole chain compared with and without an `Origin`; §6 shows an unconfigured service in `curl` and in the browser, with no CORS header and no `Vary` |
| AC-2 | ✅ | §6, in Chromium, both directions, three request shapes each — including a preflighted write that **never executed** from the unlisted origin |
| AC-3 | ✅ | §6 AC-3; `build_info_test` covers stamp, detached head, branch, slashed branch, `packed-refs`, peeled tag line, worktree, ref cycle and eight unresolvable cases; `contract_test` pins the pattern against `main`, `HEAD`, `ref-1` and a short SHA, and pins the *self-reported, not attested* sentence |
| AC-4 | ✅ | §6 AC-4 — matched and unmatched, both against real instances; `instance.test.ts` covers all four `matchTag` outcomes, including that *not checked* never renders as *no match* |
| AC-5 | ✅ | §6 AC-5, against a service answering `200` with permissive CORS of its own; `instance.test.ts` covers missing, empty and blank |
| AC-6 | ⚠️→✅ | §6 AC-6 — six of six steps, then a re-run refused by the instance with one organisation left. **Subject to objections O-1 and O-2**: two steps are SQL the operator runs and the cockpit then confirms, because CloFin deliberately has no endpoint for them |
| AC-7 | ✅ | §6 AC-7 — halted at step 4 of 6 naming `account-1300`, three steps visible before it, zero further requests, one account on the instance; `bootstrap.test.ts` counts requests at the stub rather than reading the screen |
| AC-8 | ✅ | §5's four controls; §6 AC-8's origin list and storage dump; both checks green and still exactly two, extended over the profiles |
| AC-9 | ✅ | ADR-0027 and cockpit ADR-0002, each with a rejected-alternatives table. ADR-0027's CORS text says three times that it is not an access control, and `contract_test` asserts the contract says so too |

## 10. What the next session should pick up

- Master Control: rule on **O-1**, **O-2** and **O-3**, and on whether the
  suggested brief corrections stand.
- Decide what to do about **N-1** before the Sol audit on 2026-09-01 — a
  docstring in the release-audit subject claims a header is mandatory on every
  mutating endpoint, and three of them do not require it.
- Resolve 011-REQ **N-4a** (repository visibility and the Pages source), which
  still blocks the first deploy, and confirm on the deployed page that anonymous
  reads work — which §8 records as inferred rather than measured, for the second
  increment running.
- Update the ROADMAP bullet noted in **N-6**.
- Phase 3 — the operation flows — now has what it needs: an instance the cockpit
  can reach, a seeded organisation, and a runner that halts honestly.
