# 011-REQ — `clofin-cockpit` initialization: ADR-0026, the honesty layer, the release browser

| Field | Value |
|---|---|
| **Brief** | `docs/briefs/011-TASK-cockpit-initialization.md` — on **`origin/meta`**, which is the authoritative copy and is not edited here |
| **Increment** | 8.1 (cockpit, phase 1) — spans `clofin-core` (one ADR) and `clofin-cockpit` (everything else) |
| **Requirements** | Driver D5; the operator's D1/D2 ruling of 2026-08-15 |
| **Controls** | **None.** The cockpit enforces nothing. `clofin-core` gains one document and no code |
| **Base** | `clofin-core` `main` at `812f732`; `clofin-cockpit` `main` at `13e6435` |
| **Branch** | `claude/cockpit-init-adr-0026-phr801` in both repositories |
| **Pull requests** | `clofin-core` **#21** · `clofin-cockpit` **#1** — cross-referenced in both descriptions |
| **Model** | `claude-opus-5` |
| **Reasoning effort** | High — extended thinking enabled throughout. The harness does not expose a numeric setting to the session, so this is reported as the mode, not as a measured value |
| **Date** | 2026-08-15 |
| **Verification still in flight** | **See [§7](#7-verification-l-9).** Two things are outstanding and neither is mine to hide: CI on both pull requests, and the Pages deployment, which cannot run before merge and additionally needs a repository setting only an operator can make |

---

## 1. What was built

**`clofin-core` — one document and its index row, and nothing else.**

| Piece | Where |
|---|---|
| ADR-0026, recording D1/D2 as ruled, with the role-boundary table | `docs/ADR/0026-three-repositories-and-the-cockpits-role-boundary.md` |
| ADR-0020's dated `## Amendment 1`, original decision text intact | `docs/ADR/0020-…md` |
| Index rows: ADR-0026 added, ADR-0020 marked `Accepted (amended 1)` | `docs/ADR/README.md` |

Three files, `+283 / −4`. No code, no CORS, no `Makefile` change, no dependency.
`make verify` is byte-for-byte the same run as on the base: **438 tests, 2779
assertions, 0 failures, 0 errors**; diagrams OK (7 artifacts); document
consistency OK; documentation links OK (84 files, one more than the base
because ADR-0026 is the new file).

The only lines removed anywhere in ADR-0020 are its three metadata header lines,
which were replaced by augmented versions recording the amendment. Its Context,
Decision, Alternatives, Consequences and Verification sections are untouched
(**AC-1**).

**`clofin-cockpit` — a static single-page application.** Five commits, ordered
so the honesty layer lands before anything it frames.

| Piece | Where |
|---|---|
| The one canonical scope-statement constant | `src/scope.ts` |
| The frame, rendered into the page at build time | `src/frame.ts`, `tools/build.mjs` |
| Coverage parsing, fail-closed | `src/coverage.ts` |
| The single network seam, origin-locked | `src/net.ts` |
| Releases + tag/SHA join | `src/releases.ts`, `src/core-repo.ts` |
| Tag/commit/coverage as a fixed triple | `src/provenance.ts` |
| Views, router, runtime frame assertion | `src/views.ts`, `src/main.ts`, `src/dom.ts` |
| The Compose deployment card | `src/compose.ts` |
| The build's refusal (AC-6) | `tools/guard-network.mjs` |
| Check 1 of 2 | `tools/check-scope-verbatim.mjs` |
| Check 2 of 2 | `tools/check-no-unqualified-audited.mjs` |
| CI, and Pages on `main` only | `.github/workflows/ci.yml`, `.github/workflows/pages.yml` |
| The toolchain decision | `docs/ADR/0001-…md`, `docs/ADR/README.md` (new series) |

## 2. The three decisions that shaped everything else

**The scope statement is written once and rendered at build time.** Not typed
into the HTML, and not injected by the application. `tools/build.mjs` imports the
compiled constant and writes it into `index.html`, so the statement is in the
document *before any script runs* and stays there if none ever does. Two
consequences follow that a runtime-injected banner would not have: the frame
survives an API failure, a JavaScript error and a blocked script; and
`scope-verbatim` has something static to compare against, which is what lets the
check be byte-for-byte rather than aspirational.

*"No page ships before this frame exists"* is therefore enforced three ways
rather than sequenced: the build **refuses** if `index.html` has lost the
placeholder; the check compares the rendered statement against the constant and
against the README's quotation; and `src/main.ts` re-asserts the frame before
**and after** every render, replacing the document with a failure notice if it
has been damaged. The failure mode is "shows nothing", never "shows the
interesting part".

**The parser's design is what it does when it cannot read.** `parseCoverage`
returns a discriminated union whose every uncertain path is `not-found`,
rendering as *"coverage statement not found"*. There is no default status, no
empty string, and no fallback that reads as reassurance. It refuses four things
deliberately: a `RELEASE AUDIT:` marker buried inside a sentence rather than
opening a paragraph; a status word this project has not defined; a body that
declares coverage **twice** — two statements cannot both govern, and choosing
one would be a guess; and a stated status whose scope is missing, which keeps
the status and says the scope was not stated rather than implying a full one.

**Tag, commit and coverage are separated by a type, not by a convention.**
`provenanceFields` returns a fixed three-element tuple. A view that wanted only
the tag would have to stop calling the function — a visible change rather than a
quiet one — and the renderer maps over the tuple, so it cannot drop an element.

## 3. Objections

Two, both filed rather than worked around.

### O-1 — The brief's named interface cannot satisfy the brief's own AC-2

Scope item 4 specifies the release browser reads releases "via the public GitHub
Releases API", and **AC-2** requires that `ref-1` render its commit SHA
(`5c7b4ba…`). **The Releases API does not return the commit a tag points at.**
It returns `target_commitish`, which is the branch or commitish the release was
*created from* — for `ref-1` and for most releases, the literal string `"main"`.

This is not a pedantic gap. A Worker implementing the brief literally would find
one field in the response that looks like the answer, and would put a branch
name, or whatever commit `main` happens to be at today, on screen labelled
"commit" — inside a frame whose entire promise is that the commit is checkable.
The wrong value would be stable, plausible and never obviously wrong.

**What I did instead.** Two calls: `/releases` for the body and the pre-release
flag, `/tags` for the dereferenced SHA, joined by tag name. Both are on
`api.github.com`, so nothing about AC-6 changes. When the join fails the SHA is
`null` and the interface reads *"commit SHA not found"* beside the tag; it never
falls back to `target_commitish`. `releases.test.ts` asserts that fallback does
not happen, and a malformed SHA is rejected rather than displayed.

**Suggested brief correction:** scope item 4 should name both endpoints and say
why the second is needed.

### O-2 — "Everything else in the README stands" preserves a present-tense claim about a mechanism that does not exist

The brief's scope item 7 says the Status section is rewritten and "Everything
else in the README stands". But the README's rule 3, under *The rules this
repository lives under*, reads:

> **No secrets live here.** A GitHub token you supply stays in your own browser
> session; CloFin credentials are synthetic and per-instance; this repository's
> history contains no credential of any kind.

In this increment there is no token, no field to supply one, and no session
storage of any kind — the build refuses to emit output containing any of them.
The sentence describes a safeguard for a feature that has not been built, in the
present tense, in the one repository whose stated product is not claiming things
that are not so. It is the mildest possible version of the failure this whole
increment exists to prevent, and it sits four paragraphs above the scope
statement.

**What I did instead.** I did **not** edit rule 3 — the brief protected it, and
diverging from a brief without a ruling is a failed handover even when the
divergence is right (AGENT_HANDOFF §1b). Instead the new Status section ends
with an explicit paragraph: *"No credential is handled in this increment. Rule 3
below describes where a GitHub token would live if one were ever needed; today
none is asked for, stored or sent, and the build fails if any credential
handling appears in the output."*

**For Master Control to rule on:** whether rule 3 should be tensed
("A GitHub token you supply *would stay*…") or left as a statement of the
repository's standing policy. I have no strong view; I do think the present
tense should not survive into a release the cockpit itself displays.

## 4. Notes and readings recorded

**N-1 — What "exactly two automated checks" was taken to mean.** AC-8 caps the
checks at two; the Definition of Done requires every AC to have a named test or
check; AC-6 asks for network purity "asserted by a check **or by the build
refusing**". I read the brief's own phrase *"build plus exactly two checks"*
literally: CI is install → build → `scope-verbatim` → `no-unqualified-audited`,
and the **build** runs the type-check, the 45 unit tests and the network guard as
preconditions. A build that cannot produce a site from a broken parser, and
refuses to produce one that could reach a second origin, is a stronger guarantee
than a third check reporting on output that already exists. No third guarantee
is asserted from this repository.

**N-2 — A governance copy on `meta` is now inaccurate.** `docs/ROADMAP.md` on
`origin/meta`, under *Increment 8 — Operator interface*, still reads
"React/TypeScript console: instruction capture, approval queue, break
workbench". The brief made the toolchain mine to choose and `clofin-cockpit`
ADR-0001 chose TypeScript **without** React, with React/Vite recorded as a
rejected alternative. The heading above that bullet has been updated for the D1
ruling; the bullet has not. Reported rather than edited — `meta` is Master
Control's alone.

**N-3 — An ADR-index sentence is now non-exhaustive.** `docs/ADR/README.md`
under *Conventions* says an amended ADR keeps its original text and that
"ADR-0013 and ADR-0014 both carry one." ADR-0020 now carries one too. The
sentence remains true as written and does not claim to be a complete list, so I
left it alone under the brief's "ONE document and its index row … nothing else"
constraint. Flagged so the omission is a decision on the record rather than an
oversight.

**N-4 — A real limitation of the no-PAT design, disclosed rather than hidden.**
Unauthenticated GitHub API access is rate limited by IP (60 requests/hour). A
visitor behind a shared address can therefore find the release list unavailable.
`src/net.ts` distinguishes that case and the interface says so in plain words,
including that the cockpit "holds no token to raise it — by design", rather than
rendering an empty list that would read as "there are no releases".

**N-5 — Three of the words this repository checks for are now reserved in its
own shipped text.** Because the deployed JavaScript keeps its comments,
`no-unqualified-audited` reads them too. Three comments I wrote during this
increment had to be rephrased to comply — one of them the sentence *describing
the check itself*. That is the rule working, not a workaround, and it is
recorded here so the next contributor is not surprised by it: in this
repository, "audited", "verified" and "reviewed" travel with their coverage
qualifier or they do not appear.

## 5. The negative controls, run for real

### AC-4 — the scope statement softened by one word (`only` → `mostly`)

Run against a copy of the built site; `README.md` left untouched, so only the
page comparison diverges. Verbatim, first two of five reported problems:

```
scope-verbatim FAILED
  - index.html: the rendered scope statement is not the canonical one.
      first differs at character 34:
        expected "o" (U+006F)
        found    "m" (U+006D)
      canonical: …"in operates on synthetic data only. It is not connected to any bank, p"
      rendered:  …"in operates on synthetic data mostly. It is not connected to any bank,"
  - index.html: the scope statement appears in a form that is not the canonical one, beginning at "CloFin operates on synthetic data".
      first differs at character 34:
        expected "o" (U+006F)
        found    "m" (U+006D)
…
5 problem(s).
EXIT=1
```

The differing character is named by index, by literal and by code point. The
remaining three problems are the near-copy rule firing from the statement's other
distinctive openings — the rule that catches a second, friendlier copy further
down a page rather than a missing one.

### AC-5 — both halves

Injected into the built page, unqualified:

```
injected [unqualified]: The ref-1 release has been audited.
no-unqualified-audited FAILED
  - index.html: describes something as “audited” with no coverage qualifier beside it:
      "The ref-1 release has been audited."
      Say what the audit covered in the same sentence — for example “PARTIAL — charter items 1-4 of 8” — or do not use the word.

1 problem(s).
EXIT=1
```

The same sentence with the qualifier beside it:

```
injected [qualified]: The ref-1 release has been audited: PARTIAL — charter items 1-4 of 8.
no-unqualified-audited OK — 3 assurance claim(s) across 13 built file(s), every one of them qualified.
EXIT=0
```

### AC-6 — the build refusing (demonstration, not required to be a check)

A CDN `<script>` tag added to the page:

```
The build refuses to publish this site:

  - index.html: refers to cdn.jsdelivr.net, which is not an allowed host. Allowed: api.github.com, github.com, www.w3.org.
  - index.html: <script src="https://cdn.jsdelivr.net/npm/chart.js"> loads a subresource from another origin. Everything this page loads is its own.

2 problem(s). See tools/guard-network.mjs for what is refused and why.
```

`npm run build` exited **1** and `_site` was **removed** — there is no site to
publish, which is the difference between refusing and reporting. Restored, the
same command exits 0.

Two further refusals happened unprompted during development, which is the
better evidence: the guard failed the build on my own prose, because a comment
had written out a form tag and another had named a browser storage API. Both
were rephrased so that those tokens do not appear anywhere in the built output
at all — a simpler property than "they appear only in comments", and one the
guard can state without qualification.

## 6. Acceptance criteria

| # | Status | Evidence |
|---|---|---|
| AC-1 | ✅ | ADR-0020 carries dated `## Amendment 1`; only its three metadata header lines changed; index lists both |
| AC-2 | ✅ | Driven in Chromium against the **live** API: `ref-1` renders tag, `pre-release`, `5c7b4ba` / full `5c7b4badced5e807e1022fce44cbcad38c6d2095`, and `PARTIAL — charter items 1-4 of 8`, parsed from the body. `coverage.test.ts`, `releases.test.ts` |
| AC-3 | ✅ | `coverage.test.ts` — missing paragraph, empty/blank/null/undefined body, undefined status word, duplicate declarations, buried marker. All render the not-found label; suite passes |
| AC-4 | ✅ | §5, run for real, character named |
| AC-5 | ✅ | §5, both halves run for real |
| AC-6 | ✅ | Build refusal (§5) + browser evidence: the only external requests the page made were the two `api.github.com` URLs. No form element, no input, no storage API, no credential token anywhere in the output; CSP `default-src 'none'; connect-src https://api.github.com` |
| AC-7 | ✅ | `compose.test.ts` + browser: commands pin `ref-1` and verify the SHA; `make up` / `make health` / `make down` are targets `clofin-core`'s Makefile defines, and `make up` creates `.env` itself via its `env` prerequisite, so the block is executable as written |
| AC-8 | ✅ | `ci.yml` has exactly two check steps; `pages.yml` runs build + both + deploy on `main` only |

## 7. Verification (L-9)

**What I ran, and its results.**

- `clofin-core`: `make verify` — **438 tests, 2779 assertions, 0 failures, 0
  errors**, plus diagrams, doc-links and doc-consistency, identical to the base
  run taken before any edit.
- `clofin-cockpit`: clean-room `rm -rf node_modules && npm ci && npm run build` —
  **45 tests, 45 passed, 0 failed**; both checks green.
- `clofin-cockpit` driven in **Chromium against the live GitHub API**: 16 of 16
  acceptance assertions passed, no console or page errors, and the recorded
  request list contains nothing but the site's own files and the two
  `api.github.com` URLs.
- The three negative controls in §5, each run and recorded verbatim.

**A defect this found that nothing else would have.** The first browser run
failed with an opaque *"Failed to fetch"*. The cause was mine: `src/net.ts` sent
an `X-GitHub-Api-Version` header, which is not CORS-safelisted, so the browser
sent an `OPTIONS` preflight first — and `api.github.com` answers preflights with
**405 Method Not Allowed** (confirmed directly: `GET` returns
`Access-Control-Allow-Origin: *`, `OPTIONS` returns 405). Every unit test passed
throughout, because the fault was in the shape of the request rather than in any
logic. The header is gone and the request is CORS-simple; the reason is recorded
in the module so it is not helpfully re-added.

**No self-review, adversarial pass or long-running check of mine is
outstanding.** I have finished verifying.

**Two things are still running or not yet runnable, and neither is mine:**

1. **CI on both pull requests.** `clofin-core` #21 and `clofin-cockpit` #1 were
   pushed and their workflows trigger on these branches. Their results are not
   in hand at the time of writing.
2. **The Pages deployment has not run and cannot yet.** `pages.yml` triggers on
   `main` only, by design, so the first deploy happens after #1 merges. It will
   additionally fail until an operator sets the repository's Pages source to
   **GitHub Actions** (Settings → Pages). That is deliberate and follows
   `clofin-trace`'s precedent: `ci.yml` builds the site and runs both checks
   independently, so a missing repository setting never looks like a broken
   build. **No cockpit URL is claimed to be live in this REQ**, because none is.

## 8. What the next session should pick up

- Master Control: rule on **O-1** (brief correction naming the Tags API) and
  **O-2** (the tense of README rule 3); update the ROADMAP bullet noted in
  **N-2**; decide on **N-3**.
- Set the `clofin-cockpit` Pages source to GitHub Actions so the first `main`
  build publishes.
- Phase 2 remains blocked on `ref-2` and on the CORS decision in `clofin-core`.
  ADR-0026 states explicitly that it pre-approves neither.
