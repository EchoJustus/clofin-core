# ADR-0027: Browser clients — a default-closed CORS allowlist, and instance self-identification

- **Status:** Accepted
- **Date:** 2026-08-15
- **Deciders:** Worker session for TASK-012, under the operator's D1/D2 ruling
  and [ADR-0026](0026-three-repositories-and-the-cockpits-role-boundary.md)
- **Supersedes / Superseded by:** —

## Context

[ADR-0026](0026-three-repositories-and-the-cockpits-role-boundary.md) created
`clofin-cockpit` and closed with a sentence that made this decision necessary
and separate:

> **No API-driving capability is authorised by this ADR.** A browser calling a
> CloFin instance requires a CORS decision in `clofin-core`, which is its own
> change, reviewed on its own terms. Nothing here pre-approves it.

This is that change. It answers two questions, which are here together because
they arrive together: **may a browser page read a CloFin response**, and
**which source is the instance that answered**.

### What CORS actually is, before deciding anything about it

A browser will let a page make a cross-origin request, but will not let the page
*read the response* unless the response says that origin may. For a request that
is not "simple" — anything with a custom header, which for CloFin means
`X-Actor-Id` and `Idempotency-Key`, and anything sending
`Content-Type: application/json` — the browser first sends an `OPTIONS`
preflight and refuses to send the real request at all unless that preflight is
answered affirmatively.

CloFin has never answered either. Every response it has ever sent has been
unreadable by a cross-origin page, and every preflight has been answered by the
router with `405 Method Not Allowed`. That is a working default-closed posture
arrived at by never having considered the question.

Three properties of CORS bear on the decision and are easy to state wrongly:

1. **It is not an access control.** It constrains browsers. `curl`, a
   server-side client, a script — none of them are affected by any header
   discussed here. A *simple* request from a browser still executes; only the
   reading of its response is blocked. Nothing in this decision protects
   anything, and `docs/COMPLIANCE.md` gains no control from it.
2. **`Access-Control-Allow-Origin: *` is not "convenient", it is a different
   decision.** It says every origin on the internet may read every response,
   including those of an instance somebody exposed on a public URL with actor
   ids they consider synthetic and a browser considers credentials.
3. **Reflecting the request's `Origin` header is the same decision as `*`,
   written to look like an allowlist.** It is the most common CORS defect
   precisely because it passes a review that reads for "we return the allowed
   origin".

### Why self-identification arrives with it

A cockpit that connects to an instance has to say *what* it connected to. The
instance already reports its `environment` and (on `/readyz`) its
`schemaVersion`; it does not report which source produced it. Without that,
"here is a running CloFin" is a claim with no checkable content, and the tag and
release-audit coverage the cockpit displays beside it would be attached to
nothing.

011-REQ's objection **O-1** is the shape of the trap. The GitHub Releases API
returns `target_commitish` — the branch a release was cut from, usually the
literal string `"main"` — and a client that displayed it under the label
"commit" would show something stable, plausible and wrong. The identical trap
exists on this side of the wire: `.git/HEAD` normally contains
`ref: refs/heads/main`, and a naïve implementation reports `main` as the commit.

## Decision

### 1. A CORS allowlist, configured per deployment, and closed unless configured

`CLOFIN_CORS_ALLOWED_ORIGINS` is a comma-separated list of origins. Unset or
empty is the default and means **no CORS behaviour whatsoever**.

That is implemented as an identity, not as a policy: `clofin.http.cors/wrap-cors`
**returns the handler it was given** when the allowlist is empty. An
unconfigured service is therefore not "a service whose CORS layer decides to add
nothing" — there is no layer. No response can differ, including in headers
nobody thought to test, because the same function object runs.

For a configured origin:

| | |
|---|---|
| Preflight (`OPTIONS` + `Origin` + `Access-Control-Request-Method`) from a listed origin | `204`, with `Access-Control-Allow-Origin`, `-Allow-Methods`, `-Allow-Headers`, `-Max-Age: 600` and `Vary: Origin` |
| Preflight from an unlisted origin | passed to the router, which answers exactly what it answers today — `405` with `Allow`, or `404` — carrying no CORS header, so the browser refuses |
| Actual request from a listed origin | the response, marked with `Access-Control-Allow-Origin`, `Access-Control-Expose-Headers` and `Vary: Origin` |
| Actual request from an unlisted origin | the response, unmarked apart from `Vary: Origin` |

Four properties are load-bearing.

**No wildcard, and the refusal is at start-up.** An entry containing `*` fails
the service to start with a message naming the variable. Not dropped, not
ignored: an operator who wrote `*`, saw the service come up and drew the obvious
conclusion would be wrong in the one direction that matters.

**No reflection, structurally.** The value emitted in
`Access-Control-Allow-Origin` is read from the *configuration*, never from the
request. The two are equal by the time it is emitted — that is what matching
means — but there is no code path along which a caller-supplied string becomes a
response header, which is a stronger statement than "the comparison above it is
correct".

**The two answer lists are derived from the service, not written beside it.**
`Access-Control-Allow-Methods` is asserted equal to the set of methods in
`clofin.routes/routes`; `Access-Control-Allow-Headers` is asserted equal to the
set of header names discovered by walking every `(get-in request [:headers …])`
in `src/` — `content-type`, `idempotency-key`, `x-actor-id`,
`x-correlation-id`. Both fail when the service changes and the CORS layer does
not. This is the answer to the specific way a guessed header list fails: a
missing entry is invisible to every test that is not a browser, because a
browser is the only client that ever sends a preflight (011-REQ §7, where a
non-safelisted request header turned every green unit test into an opaque
*"Failed to fetch"*).

**No credentials.** `Access-Control-Allow-Credentials` is never sent, so a
browser attaches no cookie and no HTTP authentication. CloFin's principal is a
header a page sets deliberately, which needs no ambient credential and is safer
without one. `Access-Control-Expose-Headers` names three headers explicitly —
`location`, `x-correlation-id`, `allow` — and never `*`.

**`Vary: Origin` on every response while the allowlist is active**, including
responses carrying no CORS header. A shared cache that stored one origin's
answer and served it to another would hand out somebody else's
`Access-Control-Allow-Origin`, and `Vary` is the only thing that prevents it.

### 2. `GET /` reports `sourceCommit`, resolved or `"unknown"`

Resolution order, at start-up, in `clofin.build-info`:

1. `CLOFIN_SOURCE_COMMIT`, **if and only if** it is 40 lower-case hex
   characters. `CLOFIN_SOURCE_COMMIT=main` is ignored, not reported.
2. Otherwise a git checkout beside the process, read down to a commit id:
   `HEAD` is either a commit already (a detached checkout — what
   `make capture-trace` produces) or `ref: <name>`, which is followed through
   loose refs and `packed-refs` to the commit it names. **The ref name itself
   is never returned.**
3. Otherwise the literal string `"unknown"`.

There is no fourth outcome, and in particular never a plausible one. The
contract declares `pattern: "^([0-9a-f]{40}|unknown)$"`, and a contract test
asserts that pattern rejects `main`, `HEAD`, `ref-1` and a short SHA — so the
011-REQ O-1 rule is enforced by the specification and not only by the
implementation.

The repository is **read**, not `git` **run**. The container image carries no
`git` binary, so a subprocess would work in a checkout and fail in the
deployment that matters; and a payments service that spawns a process at
start-up to describe itself has acquired an ability it has no other use for.

`make up` stamps the commit from the checkout it was run in, because the image
has no repository to read. The stamp is exported only when it has a value: an
exported empty variable takes precedence over `.env` in Compose and would
silently override what the operator wrote there.

### 3. What the contract says about the field, and what it does not

`sourceCommit` is described in `api/openapi.yaml` as **self-reported by the
running process, not attested**. Nothing about this response demonstrates that
the bytes serving it are the bytes at that commit; a process can be started with
any stamp. Standing lesson **L-14** is about claims that are true-sounding and
larger than their enforcement, and "the service reports its commit" is exactly
the sentence that acquires the word "proves" if nobody writes down that it does
not. A contract test asserts the description contains *self-reported* and *not
attested*, and does not contain *proves*.

### 4. What does not change

No new endpoint, no route, no schema, no migration, no authentication or
authorisation change, no control in `docs/COMPLIANCE.md`, and no dependency. The
preflight is answered by middleware rather than by a route, so the route table
and `api/openapi.yaml` still describe the same operations and the contract test
needed no exemption.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **`Access-Control-Allow-Origin: *`** | Says every origin may read every response. On a public reference instance that is every page on the internet able to read an audit trail and drive it with an actor id, and there is no configuration to turn it back off per-deployment because the code no longer has the concept. It is also the answer this project would have to defend in the release audit scheduled over this middleware |
| **Reflect the request's `Origin`, with no list** | Identical exposure to `*`, in a form that reads like an allowlist in review. It is the most common CORS defect for exactly that reason. Rejecting it structurally — emit the configured string, never the request's — is cheaper than remembering |
| **A list of origin *patterns* (`https://*.github.io`)** | One character between "the cockpit" and "anything anybody publishes on that host". Every origin CloFin permits is named in full, which is a sentence an auditor can check against a deployment's environment |
| **Allow the cockpit's published origin by default, so it works out of the box** | Would make every instance anyone ever runs — including one an evaluator exposes on a forwarded URL — readable by a page this project publishes. Convenience for one client bought with a default nobody chose. The cockpit asks the operator to set one variable instead, and says so |
| **A dedicated `CLOFIN_CORS_ENABLED` flag beside the list** | Two settings that can disagree. A list with entries is the enablement; an empty list is the disablement; there is no third state to get wrong |
| **Implement CORS with a Jetty handler or a Ring library** | A dependency needing an ADR under [ADR-0004](0004-minimal-dependency-footprint.md) for eighty lines of header logic, most of whose behaviour would be a superset of what is wanted — precisely the wildcard and reflection modes rejected above, present and one configuration key away |
| **Answer the preflight in the router, as an `OPTIONS` route** | Preflight is a transport concern that applies to every path including ones that do not exist, and putting it in the route table would put an operation in `api/openapi.yaml` that no client calls deliberately. It would also mean an unconfigured deployment carried the route, which is the opposite of the identity-when-unset property |
| **Report `sourceCommit` by running `git rev-parse`** | No `git` in the image, so it would work where it does not matter and fail where it does; and a subprocess at start-up is a capability with no other purpose in this service |
| **Report the branch, or `git describe`, when the commit is unresolvable** | The 011-REQ O-1 defect, moved server-side. A branch name under the label "commit" is stable, plausible and wrong, which is worse than `"unknown"` in every case |
| **Report a "dirty" marker when the working tree has uncommitted changes** | Would put a second form into a field the contract pins to two, and the field is already declared self-reported rather than attested — a dirtiness flag would invite the stronger reading it is trying to qualify. Named here so the omission is a decision |
| **Have the cockpit resolve the commit itself from the GitHub API** | It cannot: no API tells you what source a running process was built from. That is the whole reason the process has to say |

## Consequences

**Positive**

- An unconfigured CloFin is byte-for-byte the CloFin that existed before this
  decision, by construction rather than by test — and the test that says so
  compares function identity.
- The exposure is a deployment decision, per deployment, written down in one
  environment variable an auditor can read.
- The two CORS answer lists cannot silently drift from the service, because
  they are derived from it.
- A client can state which source it is talking to, and state the limits of
  that statement, instead of showing a version-shaped blank.

**Negative / accepted cost**

- One more environment variable, and one more thing an operator can misconfigure
  — mitigated by refusing to start rather than starting wrong.
- A configured deployment's responses carry `Vary: Origin` for every caller,
  including non-browser ones. Correct, and a small cache-key cost.
- `sourceCommit` is a field that looks stronger than it is, forever. The
  mitigation is a sentence in the contract and a test that keeps the sentence
  there; it is not a proof, and this ADR does not claim one.
- The middleware lands inside the release-audit subject and is new code in the
  request path of every request, in a release audit scheduled for 2026-09-01.
  Accepted deliberately: it is small, it is default-inert, and the property the
  audit will attack first is the one built structurally.

**Risks and how they are mitigated**

- *Risk:* a future contributor "fixes" a CORS failure by adding a wildcard.
  *Mitigation:* `*` fails start-up with a message that explains, and
  `clofin.http.cors-test/a-wildcard-is-refused-at-start-up-and-says-so` fails
  the build.
- *Risk:* a header is added to the API and browsers begin failing opaquely.
  *Mitigation:* the header-discovery test fails first, in CI, with the header
  named.
- *Risk:* the allowlist is read as a security control and counted as one in a
  compliance document. *Mitigation:* the namespace docstring, this ADR and
  `api/openapi.yaml` each say it is not, and no control in
  `docs/COMPLIANCE.md` references it.
- *Risk:* `sourceCommit` is read as attestation. *Mitigation:* the contract's
  own field description, asserted by a contract test that also asserts the word
  *proves* does not appear in it.
- *Risk:* a stamped commit is stale — the image was rebuilt from other code and
  stamped from an old checkout. *Mitigation:* none available, which is why the
  field is documented as self-reported. It is a statement by the process about
  itself, and the contract says exactly that.

## Verification

- **Unset is unchanged, by identity.**
  `clofin.http.cors-test/unconfigured-returns-the-very-same-handler` asserts
  `identical?` for `nil`, `""`, whitespace and comma-only configurations;
  `unconfigured-leaves-the-whole-chain-identical` compares whole responses with
  and without an `Origin` header through the composed chain.
- **Both directions.** A listed origin's preflight is asserted header by header;
  an unlisted origin's preflight is asserted to fall through with no CORS header,
  including for the three near-misses a substring comparison would accept.
- **The lists are derived.**
  `allow-methods-are-the-methods-the-route-table-has` and
  `allow-headers-are-the-headers-the-service-reads` fail on drift.
- **Refusals.** Wildcards and eleven malformed origins are asserted to refuse
  start-up; one bad entry refuses the whole list.
- **`sourceCommit`.** `clofin.build-info-test` covers the stamp, a detached
  head, a branch, a slashed branch name, `packed-refs`, a peeled tag line, a
  linked worktree, a symbolic-ref cycle, and eight inputs that must resolve to
  `"unknown"` — asserting in each case that no branch name escapes.
  `clofin.contract-test` asserts the declared pattern accepts a commit and
  `unknown` and rejects `main`, `HEAD`, `ref-1`, a short SHA and the empty
  string.
- **A real browser.** AC-2 is verified in Chromium against a running instance
  from an allowed origin and an unlisted one, because a preflight fault is
  invisible to `curl` and to every unit test in this file. The evidence is in
  `docs/audits/012-REQ-cockpit-connect-and-bootstrap.md`.
- **Nothing else moved.** `make verify` covers the same suite it did before,
  plus this increment's; no migration, no route, no dependency, and
  `docs/COMPLIANCE.md` is untouched.

This decision permits a browser to read a response. It does not permit anybody
to do anything they could not already do with `curl`, it makes no statement
about what CloFin guarantees, and it changes nothing about the fact that CloFin
operates on synthetic data only, is not connected to any bank, payment scheme or
central bank, holds no regulatory authorisation, and never processes real funds.
