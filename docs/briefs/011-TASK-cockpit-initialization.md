# TASK-011: `clofin-cockpit` initialization — ADR-0026, the scaffold, the release browser, and the honesty layer

| Field | Value |
|---|---|
| **Increment** | 8.1 (cockpit, phase 1) — spans `clofin-core` (one ADR) and `clofin-cockpit` (everything else) |
| **Status** | `IMPLEMENTED` — Worker reported done 2026-08-15: part A (ADR-0026) **merged** in PR #21 (`eb3a561`); part B open as `clofin-cockpit` PR #1 (CI green), **merge held** pending two operator settings (visibility → public, Pages → GitHub Actions; observation N-4a). Both objections ruled, see Changelog |
| **Depends on** | The operator's D1/D2 ruling of 2026-08-15 (recorded below and in the audits register); the `clofin-cockpit` repository ✅ created with its first-commit README and EPL-2.0 licence (`13e6435`) |
| **Base branch** | `clofin-core` `main` at `812f732` or later; `clofin-cockpit` `main` at `13e6435` or later |
| **Requirements** | Driver D5; the cockpit plan of 2026-08-15 (Phase 0 + Phase 1, minus everything `ref-2`- or API-gated) |
| **Controls touched** | None. The cockpit enforces nothing; `clofin-core` gains one ADR and no code |
| **Scope** | Medium |
| **Audit** | `011-REQ` filed 2026-08-15 (`docs/audits/011-REQ-cockpit-initialization.md` on `main`); ADR-0026 joins the deferred Sol audit's scope; `clofin-cockpit` itself is outside release-audit scope |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## The ruling this brief executes

On 2026-08-15 the operator ruled: **(D1)** CloFin becomes **three repositories**,
amending ADR-0020's "two, and no more"; **(D2)** the third is named
**`clofin-cockpit`** — the operator cockpit, interaction with the *present*: a
transparent client that deploys and drives a real reference instance. The role
boundary that survives the amendment:

| Repository | Role | Owns truth? |
|---|---|---|
| `clofin-core` | The system, its controls, its documents, the capture harness | **All of it** |
| `clofin-trace` | Replay of the **past** — captured output of a tag; may never fake | No |
| `clofin-cockpit` | Interaction with the **present** — a client of the real API; **may never claim** | No |

Increment 8 (the operator interface) relocates from `clofin-core` to
`clofin-cockpit`, which also takes the npm-toolchain decision the ROADMAP has
predicted since increment 5 — outside `clofin-core`, so ADR-0004 and NFR-007
are untouched there.

## Scope

### In — part A, `clofin-core` (one document, no code)

1. **ADR-0026** — "Three repositories, and the cockpit's role boundary."
   Records D1/D2 verbatim as ruled; amends ADR-0020 with a dated amendment
   section in ADR-0020's own file per the house convention (original text
   intact); carries the role-boundary table; relocates increment 8; states
   that the frontend toolchain lives in `clofin-cockpit` and that ADR-0004 /
   NFR-007 continue to govern `clofin-core` unqualified. Index row added.
   **No other `clofin-core` change — no CORS, no code, nothing.**

### In — part B, `clofin-cockpit`

2. **The scaffold.** A static single-page application: builds to plain static
   files, deployable to GitHub Pages, **no server component, no telemetry, no
   analytics, no third-party runtime CDN**. Framework and build tool are yours
   to choose under those constraints — record the choice and its rejected
   alternatives in **`clofin-cockpit`'s own ADR series** (`docs/ADR/0001-…`,
   new series, this repository's decisions live here). Lockfile committed.
   Dependency count justified in the ADR — the cockpit does not inherit
   ADR-0004, but it inherits the instinct.
3. **The honesty layer, before any feature.** The scope statement (the same
   verbatim `GET /` text the README quotes) lives in **one** canonical
   constant; every view renders it **in-frame and non-dismissible** beside a
   provenance area that always shows tag + commit SHA + release-audit coverage
   **together** when a release is in context. No page ships before this frame
   exists.
4. **The release browser.** Lists `clofin-core`'s `ref-<n>` releases via the
   public GitHub Releases API (unauthenticated read — no PAT anywhere in this
   increment). Each release renders: tag, commit SHA, pre-release flag, and
   the release-audit coverage **parsed from the `RELEASE AUDIT:` paragraph of
   the release body** — the same text `docs/releases/*.annotation.txt`
   mirrors. Parsing **fails closed**: a body with no recognisable coverage
   paragraph renders as "coverage statement not found", never as a blank, and
   never defaults toward any word like "audited". `ref-1` must render
   `PARTIAL — charter items 1-4 of 8`.
5. **The Compose deployment card (driver v0).** For a selected release,
   generate and display the exact copyable commands to run it locally
   (clone → `git checkout <tag>` → `make up` → `make health`), pinned to the
   tag's SHA. **Generated text only — the browser executes nothing.** Deploying
   an untagged ref is out of this card's scope entirely.
6. **CI: build plus exactly two checks**, the trace discipline applied here:
   - **`scope-verbatim`** — the rendered scope statement in the built output
     matches the canonical constant **byte for byte**, and the constant matches
     the README's quoted text; softened wording fails naming the character.
   - **`no-unqualified-audited`** — no text in the built output describes any
     release as "audited"/"verified"/"reviewed" without a coverage qualifier
     in the same sentence or the adjacent provenance block (the AC-11 rule,
     third repository, same enforcement).
   A separate Pages workflow (build + both checks + deploy) runs on `main`
   only.
7. **README status update.** The Status section moves from "planning — nothing
   is built" to a true statement of what now exists and what remains gated
   (API-driving features await `ref-2` and a CORS decision in `clofin-core`).
   Everything else in the README stands.

### Out — and why

| Out of scope | Reason |
|---|---|
| Anything that calls a CloFin instance's API | Phase 2. It needs the CORS increment in `clofin-core`, which is its own reviewed change |
| Codespaces and Actions runner drivers | Phase 2/4; the Compose card establishes the driver shape first |
| Seed profiles, account management, operation flows, scheme simulation | Phases 2–3 |
| Deploy-from-untagged-`main` | The cockpit's first release card must not teach that habit; revisit when a real need is stated |
| Any `clofin-core` change beyond ADR-0026 | The boundary is the product |
| Any `clofin-trace` change | Different repository, different rules, nothing shared |

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given ADR-0026 merged, then ADR-0020 carries a dated amendment section, its original text intact, and the index lists both. | D1, house convention |
| AC-2 | Given the release browser against the live repository, then `ref-1` renders tag, `5c7b4ba…`, pre-release flag and `PARTIAL` coverage — parsed from the release body, not typed. | RULE-adjacent honesty |
| AC-3 | Given a release body with no `RELEASE AUDIT:` paragraph, then the card says the coverage statement was not found and the check suite still passes — fail closed, never default. | L-6, L-14 |
| AC-4 | Given the built output with the scope statement softened by one word, then `scope-verbatim` fails naming the differing character. Run this negative control for real and record it in the REQ. | L-6 |
| AC-5 | Given a sentence calling a release "audited" with no qualifier, injected into the built output, then `no-unqualified-audited` fails quoting the sentence; with the qualifier beside it, passes. Run both halves. | AC-11 pattern, L-14 |
| AC-6 | Given the built site, then it makes **no** network request except to `api.github.com`, contains no `<form>`, no PAT handling, no storage of credentials — asserted by a check or by the build refusing, not by review. | honesty, security |
| AC-7 | Given the Compose card for `ref-1`, then the generated commands pin the tag and SHA and are executable as written on a machine with git, Docker and make. | F2 v0 |
| AC-8 | Exactly two automated checks exist in `clofin-cockpit` CI, and the Pages workflow runs build + both + deploy on `main` only. | scope item 6 |

## Definition of done

- [ ] Every AC has a named test or check; AC-4/AC-5 negative controls actually run and recorded
- [ ] `clofin-cockpit` ADR-0001 records the toolchain choice with rejected alternatives
- [ ] Two PRs (one per repository), cross-referenced in both descriptions
- [ ] `clofin-core`'s `make verify` untouched and green; cockpit CI green
- [ ] REQ filed as `011-REQ-…` in `clofin-core`'s `docs/audits/`, provenance header, objections if any, and the L-9 statement — naming anything still running

## Notes for whoever picks this up

**The honesty layer is the product; the features hang off it.** If the frame
(scope statement + tag + SHA + coverage, together, in-frame) is right, every
later phase inherits it. If it is wrong, every later phase amplifies it.

**The cockpit may never claim.** Any sentence about what a CloFin control
guarantees is a verbatim quotation from `clofin-core`'s audited documents,
attributed and linked — RULE 3 governs here exactly as it governs the trace.

---

## Changelog — rulings on the `011-REQ` objections (2026-08-15)

*(The REQ is `docs/audits/011-REQ-cockpit-initialization.md` on `main`, landed
by PR #21, `eb3a561`.)* Both premises independently verified before ruling:
Master Control queried the live Releases API and got `target_commitish:
"main"` — a branch name — while the Tags API dereferences `ref-1` to
`5c7b4bad…`; and the cockpit repository's `private: true` flag was confirmed
directly.

| # | Objection | Ruling |
|---|---|---|
| O-1 | AC-2 says the commit SHA is "parsed from the release" — but the Releases API does not return a tag's commit; it returns `target_commitish`, usually the literal string `"main"`. Implementing literally would render a branch name labelled "commit" inside the frame whose whole promise is a checkable commit. The Worker read the SHA from the Tags API and never falls back. | **Confirmed — brief defect, Master Control's.** The brief asserted where a value lives without checking the API that serves it — the L-3 class (validate the interface before dispatch) on a REST surface instead of DDL. The Tags-API resolution with no fallback is ratified: a provenance value that cannot be resolved is refused, not approximated, which is the same fail-closed rule the coverage parser follows. |
| O-2 | "Everything else in the README stands" preserves rule 3's present-tense sentence about GitHub-token handling in an increment that handles no token. The Worker did not edit the protected section and referred the tense here. | **Confirmed — the referral was correct, and the fix is Master Control's to make** (the README was drafted by Master Control at creation). Ruling: rule 3 becomes explicitly forward-tensed — a standing policy for the phase that introduces a token, not a description of a capability that exists. Master Control applies the one-sentence edit directly to `clofin-cockpit` `main` after PR #1 merges, recorded in the commit message as this ruling's execution. |

**Observations, dispositioned.** **N-4a** (the cockpit repository is
**private**, so Pages cannot deploy on the current plan and anonymous API
reads are untestable): confirmed live; the fix is the operator's — flip
visibility to public, then set Pages → Source: GitHub Actions, in that order,
**before** PR #1 merges, so the main-only `pages.yml` first fires into a
working configuration. The unverified-anonymous-path statement is accepted as
exactly the right shape of honesty: what is established is that the cockpit
sends no credential and `clofin-core` is public; the anonymous rate-limit
path is exercised the first time a visitor loads the deployed site from
outside the proxy. The stale ROADMAP "React/TypeScript console" line is
corrected on `meta` in this commit. The browser-only CORS-preflight discovery
(`X-GitHub-Api-Version` → `OPTIONS` → 405) is noted as evidence for the
standing practice the Worker followed: a feature that lives in a browser is
verified in one.
