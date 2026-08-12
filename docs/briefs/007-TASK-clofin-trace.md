# TASK-007: `clofin-trace` — a replay walkthrough, and the harness that feeds it

| Field | Value |
|---|---|
| **Increment** | 5v.2 (visual layer, tier 1) — spans `clofin-core` and a **new** repository |
| **Status** | `IN PROGRESS` — dispatched 2026-08-12. **The A3 decision point was held and ruled: proceed, full three scenarios.** Grounds: TASK-006 delivered in one Worker session, seven calendar days after dispatch, under its Medium estimate with zero rework and all five objections ruled in the Worker's favour. The narrow-to-one-scenario fallback (settlement only) remains available mid-flight if part A overruns — raise it as an objection, do not take it unilaterally |
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
