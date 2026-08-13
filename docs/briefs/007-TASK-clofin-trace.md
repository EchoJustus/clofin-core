# TASK-007: `clofin-trace` — a replay walkthrough, and the harness that feeds it

| Field | Value |
|---|---|
| **Increment** | 5v.2 (visual layer, tier 1) — spans `clofin-core` and a **new** repository |
| **Status** | `IMPLEMENTED` — Worker reported done 2026-08-12, same day as dispatch: part A open as PR #14 (CI green), part B committed in the Worker's session but its push was blocked by repository access; landing route arranged at ingestion. All five objections ruled, see Changelog. *(A3 history: the decision point was held and ruled **proceed, full three scenarios**, on TASK-006 finishing under estimate with zero rework.)* |
| **Depends on** | TASK-006 ✅ merged (PR #12, `2237a39`); `ADR-0020` ✅ merged (PR #11, `cbbd669`); the `ref-1` tag ✅ (`5c7b4ba`, fixtures are captured from it) — all satisfied at dispatch |
| **Base branch** | `main` for the harness half. The `clofin-trace` repository **does not exist yet** — the operator creates it at dispatch, deliberately not before (A6) |
| **Requirements** | Driver D5 |
| **Controls touched** | None move. The walkthrough enforces nothing and is enforced by nothing |
| **Scope** | Large — two repositories |
| **Audit** | Not yet submitted. **`clofin-trace` is permanently outside release-audit scope**; the harness in `clofin-core` is inside it |

> Status lifecycle: `READY` → `IN PROGRESS` → `IMPLEMENTED` → `AUDITED` → `CLOSED`.
> Status is maintained by Master Control on the `meta` branch — see AGENT_HANDOFF §1b.

---

## Objective

The controls are invisible. "Zero-sum enforced in both the domain and a deferred
database trigger" is abstract; a settlement batch with mixed outcomes keeping the
journal balanced is not. Clearing exposure as a readable ledger balance
(ADR-0018) is the single most distinctive thing this project can show, and today
it can only be read about.

`clofin-trace` shows what really happened. It computes nothing.

## Governing decision — read it first

**`ADR-0020`** — `docs/ADR/0020-two-repositories-and-the-generate-replay-rules.md` on `main`.
Its three rules are this brief's acceptance criteria, not its preamble:

1. **GENERATE, NEVER DRAW.**
2. **REPLAY, NEVER FAKE.** Every value on screen was captured from a real run,
   stamped with the source commit.
3. **QUOTE, NEVER PARAPHRASE.** Any statement about what a control *does* is a
   verbatim quotation from `COMPLIANCE.md` or `DOMAIN_MODEL.md`, attributed and
   linked at the captured commit.

### RULE 3's boundary, worked (amendment A7)

The line is not obvious, so here it is on both sides. **Do not re-derive it.**

- ✅ **Explanatory prose — write freely.** *"This panel shows the batch's four
  items and the outcome each one reached."* · *"The highlighted row is the item
  that timed out."* · *"Balances update as each response arrives."* These
  describe **what the reader is looking at**. They make no claim about a
  guarantee.
- ❌ **A control claim — must be a verbatim quotation, attributed and linked.**
  *"The timed-out item cannot be re-batched, because a settled or unknown
  instruction may never enter a second batch."* That asserts what the system
  **guarantees**. It must appear as a quotation from `COMPLIANCE.md` or
  `DOMAIN_MODEL.md` — for example, quoting C-04's own words — with a link to
  that file at the captured commit.
- **The test to apply:** could this sentence be *wrong about the system* rather
  than merely wrong about the picture? If yes, it is a control claim. Quote it.

## Scope

### In — part A, in `clofin-core` (this half is release-audit scope)

1. **`make capture-trace`** — runs the three scenarios against a live stack and
   writes, per scenario, a JSON bundle containing **every** API request and
   response, every journal entry and line, and every audit event produced.
2. **Provenance stamping** — each bundle carries the source commit SHA, the tag
   if the commit is tagged, **that tag's recorded release-audit coverage read
   from its annotation** (e.g. `partial — charter items 1–4 of 8`), the capture
   timestamp, and the schema version. The `GET /` response — the four scope
   statements — is captured **as a fixture**, not transcribed. Coverage is
   captured for the same reason the disclaimer is: a value read from the
   artifact cannot drift from it, and a value retyped by a human will.
3. **A test asserting the harness cannot emit an unstamped fixture** (amendment
   in the ruling; lesson L-13). A stamp that depends on remembering to add it is
   a convention, not an enforcement point. Fail closed at the source.
4. **Fixtures captured from the `ref-1` tag** (`5c7b4ba`) so the walkthrough
   replays a *tagged* state from its first day — one whose release-audit
   coverage is a matter of record rather than an impression. **`ref-1`'s
   release audit was partial: charter items 1–4 of 8, with items 5–7 carried
   forward to `ref-2`** (audits register). The walkthrough therefore never
   describes its source state as "audited" unqualified; it states the coverage
   the tag actually has.

   **Coverage is a captured field, not a sentence.** The harness reads it from
   the tag's own annotation and stamps it into the bundle (scope item 2); the
   walkthrough renders whatever it finds. When a later tag's audit is complete,
   the same harness and the same rendering produce "complete — items 1–8 of 8"
   with nothing rewritten. A coverage claim written as prose is a claim someone
   must remember to update, and the one thing this project has learned about
   such claims is that nobody does.

### In — part B, the `clofin-trace` repository

5. **Three scenarios, all of them**, replaying the captured bundles:
   - **segregation of duties refused** — from UAT-005, including the F-001
     creator-only-submit step;
   - **the settlement batch that misbehaves** — from UAT-006: partial failure,
     duplicate response, out-of-order contradiction, timeout — carrying the
     **ledger sand table** showing value move
     `1100-CLIENT-FUNDS` → `1300-IN-TRANSIT` → `2100-CLIENT-PAYABLE`, with the
     timed-out item visibly refusing to drain;
   - **the evidence-pack timeline** from `GET /audit/evidence/{subjectId}`.
6. **Honesty rendering** — the four captured scope statements appear
   **in-frame and non-dismissible**, not in a footer: a screenshot crops a
   footer. `SIM-RTGS` and `SIM-ACH` appear exactly as they are, never
   prettified toward the name of any real scheme.

   **The provenance block sits with them, in-frame, and carries three things
   together: the tag, the commit SHA, and the tag's release-audit coverage** —
   all three read from the captured bundle, never typed. A SHA shown without its
   coverage invites the reader to supply the missing word, and the word they
   supply will be "audited". The SHA links to `clofin-core` at that commit.
7. **Exactly two automated checks in its CI** — no more:
   - **`provenance-present`** — every fixture carries its stamp, and the built
     output displays the SHA;
   - **`disclaimer-verbatim`** — the scope statements in the built output match
     the captured `GET /` response **byte for byte**. Not "a disclaimer is
     present": softened wording must fail, or the check is a partial guard
     (L-6).

### Out — and why

| Out of scope | Reason |
|---|---|
| Any input, form, button that submits, or computed value | RULE 2. An interactive-but-fake console contradicts the project's doctrine more than having no visual at all |
| Calling a live CloFin instance | It is a replay. A live call makes it a client, with a client's failure modes and an unbounded surface |
| The PR-015 approval-queue wireframe | Tier 1.5, and it lives in `clofin-core/docs/design/` — it shows a *proposed* interface, so it is not captured output, and hosting it here would create one page where RULE 2 does not apply |
| Hand-drawn diagrams | RULE 1 |
| Analytics, telemetry, cookies, third-party fonts or CDNs | A page about data minimisation that phones home is self-refuting. Static assets only |
| Anything resembling a certification, badge or score | The scope statements are the only assurance claim the walkthrough makes |

## The first commit of `clofin-trace` (amendment A6)

The operator creates the repository **at dispatch, not before**. Its **first
commit** must already contain, before it is ever publicly visible:

- a `README.md` carrying **the four scope statements verbatim** from the
  captured `GET /` response;
- a link back to `clofin-core` at the captured commit, and the statement that
  every figure on the site is replayed captured output of that commit;
- the licence;
- a one-line statement that the repository contains **no CloFin system code** and
  enforces no controls.

An empty or placeholder public repository is not acceptable at any point.

## Acceptance criteria

| # | Given / When / Then | Traces |
|---|---|---|
| AC-1 | Given a captured bundle, then every API response, journal entry and audit event in the scenario is present, and each bundle carries SHA, tag, timestamp and schema version. | RULE 2 |
| AC-2 | Given the harness asked to emit a bundle without a resolvable source SHA, then it **refuses**; a test asserts this. | RULE 2, L-13 |
| AC-3 | Given a fixture lacking a stamp, when `clofin-trace` CI runs, then `provenance-present` fails. | RULE 2 |
| AC-4 | Given the built output with any scope statement altered, softened or removed, then `disclaimer-verbatim` fails, naming the differing statement. | honesty, L-6 |
| AC-5 | Given the settlement scenario, then every figure shown — balances, amounts, outcomes, event counts — is traceable to a value in the captured bundle. No figure is computed in the browser. | RULE 2 |
| AC-6 | Given the sand table, then the three account balances at each step equal the balances in the captured journal, and the timed-out item is visibly not drained. | RULE 2, ADR-0018 |
| AC-7 | Given any sentence in the walkthrough asserting what a control guarantees, then it is a verbatim quotation from `COMPLIANCE.md` or `DOMAIN_MODEL.md`, attributed, and linked at the captured commit. | RULE 3 |
| AC-8 | Given the published site, then the scope statements are rendered in-frame and cannot be dismissed, and **the tag, the captured SHA and the tag's release-audit coverage are all visible together, in-frame**, without scrolling to a footer — each rendered from the captured bundle rather than typed. | honesty, L-14 |
| AC-9 | Given the repository's first commit, then it contains the four scope statements, the back-link, the licence and the no-system-code statement. | A6 |
| AC-10 | Given any diagram in the walkthrough, then it is generated (TASK-006's output or the same generator), never hand-drawn. | RULE 1 |
| AC-11 | Given every page of the walkthrough, then **no text describes the source state as "audited", "verified", "reviewed" or equivalent without the captured coverage qualifier attached in the same sentence or the adjacent provenance block** — asserted by a check over the built output, so a later editor cannot reintroduce it. `ref-1`'s coverage is partial; a walkthrough implying otherwise would be the L-14 class in the project's most public artifact. | honesty, L-14 |

## Definition of done

- [ ] Every acceptance criterion has a named test or check
- [ ] Exactly **two** automated checks in `clofin-trace` CI — resist adding a third
- [ ] Fixtures captured from `ref-1` (`5c7b4ba`); the displayed tag, SHA **and
      release-audit coverage** all match the captured bundle
- [ ] `make verify` and `make test-it` green in `clofin-core`
- [ ] `clofin-trace` published to GitHub Pages, static, no third-party requests
- [ ] Two PRs, one per repository, cross-referenced in both descriptions
- [ ] REQ filed as `007-REQ-…` in `clofin-core`, provenance header, and a plain
      statement of whether any verification is still in flight (L-9)

## Notes for whoever picks this up

**The most dangerous artifact in the project.** Visual media strips context: a
screenshot of the sand table, posted without the page around it, is a picture of
a payment system moving money. That is why the scope statements are in-frame and
non-dismissible, why the scheme names keep their `SIM-` prefix, and why RULE 3
exists. If you find yourself writing a sentence that sounds impressive, check
whether you are paraphrasing a control.

**Replay is not a lesser thing than interactivity.** A reviewer who can see what
really happened, with a commit SHA next to it, is being given more than one who
can type into a box that pretends. Do not add a simulator to make it feel
livelier.

**The boundary you are standing on.** `clofin-core` owns truth; `clofin-trace`
owns presentation. That is why the trace repository is outside release-audit
scope and the harness is inside it. If you find yourself wanting to compute
something in the trace repository — a total, a percentage, a derived balance —
that computation belongs in the captured output, or it does not belong.

---

## Changelog — rulings on the `007-REQ` objections (2026-08-12)

*(The REQ is `docs/audits/007-REQ-clofin-trace.md` on PR #14's branch, landing
on `main` with it — not on `meta`, hence no link.)*

All five ruled the day the REQ was filed; **all five in the Worker's favour**.
Three trace back to Master Control defects. O-1's tag-kind claim was
independently reproduced before ruling: `git ls-remote` shows no peeled
`^{}` line for `refs/tags/ref-1`, and Master Control's local annotated tag
object — whose push was proxy-blocked on 2026-08-05 — matches the release
body the Worker mirrored.

| # | Objection | Ruling |
|---|---|---|
| O-1 | `ref-1` is a lightweight tag; `docs/audits/README.md` and `docs/ROADMAP.md` say it is annotated and that "the tag annotation says so". The annotation text exists as the GitHub release body. | **Confirmed — Master Control defect, and re-tagging is declined.** The annotated tag was cut locally, its push was blocked, and the release was then published via the web UI, which creates a lightweight tag; Master Control verified the tag's existence and target on the remote but asserted its kind — L-16 applied to a release step. The published tag stays as it is: replacing a published tag object to make a document true mutates the artifact instead of correcting the claim, which is backwards. Both governance sentences corrected on `meta` with dated notes; the Worker's mechanism (prefer a real annotation, fall back to the `docs/releases/` mirror, stamp which source was used, refuse when neither has a `RELEASE AUDIT:` paragraph) is ratified as the standing shape. From `ref-2`: tags pushed annotated via git, kind verified by the peeled `ls-remote` line at publish time. |
| O-2 | `GET /` returns one `disclaimer` string, not "four scope statements"; no general rule splits it into exactly four. | **Confirmed — brief defect, Master Control's language.** "Four scope statements" originated in the governance decision text and survived into the brief unexamined; the response has always been one string carrying four claims. Rendering it whole and verbatim with a byte-for-byte check is ratified — a hand-tuned split would be a second copy of the wording, which is the drift class this project exists to hunt. The Worker's upstream recommendation (a `scopeStatements` array in the API contract) is recorded as a candidate for a future brief and deliberately not committed to now: it changes the release-audited surface. |
| O-3 | AC-11 demands "a check" while scope item 7 allows exactly two. | **Confirmed — the fold is ratified.** The coverage qualifier *is* provenance, so AC-11's rule belongs inside `provenance-present`; AC-5 and AC-6 fold in on the same reasoning. AC-11 asked for a check, not a third check — but the brief should have said where it lived, and that is an authoring note against Master Control. Two checks stand. |
| O-4 | `make capture-trace` cannot run in `clofin-core`'s CI as it stands; its unit tests are in `verify`, the capture itself is not. | **Confirmed — ratified.** An honestly unwired guard beats a wired one that cannot run where it is wired (the 006-REQ O-1 pattern, correctly reused). Capture-in-CI is recorded for the brief that captures `ref-2`, where rewriting fixtures on push can be decided deliberately rather than inherited. |
| O-5 | The trace repository's first commit said coverage is "read from the tag's own annotation", which per O-1 is not where it is read from; the Worker corrected operator-authored README text. | **Confirmed — the correction stands.** The false sentence was drafted by Master Control (the first-commit contents were supplied at dispatch), so this is the same O-1 defect surfacing in a third document. A repository front door asserting something the harness does not do is exactly what the Worker is instructed not to leave standing. |

**Landing note (2026-08-12).** Part B's push failed from the Worker's session
(token scoped to `clofin-core`). Master Control's session attached
`clofin-trace` with push access at ingestion; the verified branch is landed
from the Worker's session or its bundle, never rebuilt — a re-capture would
replace the artifact the Worker verified with one nobody has.
