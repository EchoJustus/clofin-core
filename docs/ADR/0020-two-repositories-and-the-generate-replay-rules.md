# ADR-0020: Two repositories, and the rules that govern anything visual

- **Status:** Accepted
- **Date:** 2026-08-05
- **Deciders:** Technical lead / product owner
- **Supersedes / Superseded by:** —

## Context

Driver **D5** — *"the system must be inspectable by non-engineers"* — has been
satisfied since increment 1 by a single consequence: *"the API contract, domain
model and acceptance criteria are versioned artefacts, reviewed alongside the
code"* (`ARCHITECTURE.md` §2).

That satisfies *auditable*. It does not satisfy *inspectable*. The two are not
the same, and the gap has widened with every increment:

- `COMPLIANCE.md` C-04 states that the ledger's zero-sum invariant is enforced
  in the domain constructor **and** by a deferred database trigger. A reader who
  does not write Clojure or SQL has to take that on trust.
- ADR-0018's clearing exposure — the balance sitting in `1300-IN-TRANSIT`
  between release and finality — is the most distinctive thing this system
  does, and it is currently a paragraph.
- A settlement batch that partially fails, receives a duplicate response, then a
  contradictory one, then times out, and still leaves the journal balanced is a
  three-minute demonstration and a very long document.

The gap is not a documentation failure. Every one of those documents is
accurate and checkable. It is that a document cannot *show* a system behaving,
and some things are only convincing when watched.

Two forces constrain the answer. The first is this project's whole claim: its
statements are checkable. A visual layer is where that claim is easiest to
break, because visual media strips context — a screenshot travels without the
page around it, and a diagram is believed faster than it is verified. Standing
lesson **L-4** records what a hand-maintained diagram cost when it drifted from
the table it depicted. The second is that a demonstration which fakes its data
is worth less than no demonstration at all, and would contradict everything
else recorded here.

## Decision

**CloFin is two repositories, and no more.**

| Repository | Owns | Never |
|---|---|---|
| **`clofin-core`** | The system, its controls, its documents, and the **capture harness** that produces fixtures. All truth. | — |
| **`clofin-trace`** | A published replay walkthrough. Presentation only. | Drives nothing. Computes nothing. Owns no truth. |

Two, rather than one, because a walkthrough vendored into `clofin-core` would
put unaudited presentation code inside the release-audit subject and force the
audit's scope statement to acquire a carve-out — and a guarantee stated over a
partial set is the defect class this project spends the most effort hunting.
Two, rather than three or more, because each repository is a governance surface
with its own README, disclaimer, CI and place to drift; the topology, the ledger
sand table and the evidence timeline are three **views within one walkthrough**
sharing one fixture set, not three products.

The **operator console remains increment 8, inside `clofin-core`**, in its
current roadmap position, for the reason the roadmap already gives. Nothing here
accelerates it, and the console — which will drive the real API — is a different
kind of artifact from a replay.

**Three rules govern anything visual, in either repository.**

### RULE 1 — Generate, never draw

Any diagram is produced from its source of truth and checked in CI. The payment
lifecycle is generated from `clofin.payments.state/transitions`; the context
topology from `ARCHITECTURE.md` §3; the control map from `COMPLIANCE.md` §2.

A hand-drawn diagram is a second copy of the truth, and second copies drift.
L-4 is the record of that happening here: an acceptance criterion, a transition
table and an ASCII diagram disagreed three ways, and the mitigation was a note
asking a human to compare them. A drawing produced *from* the table cannot
disagree with the table.

### RULE 2 — Replay, never fake

Every value on screen was captured from a real run of the real system and is
stamped with the commit it came from. If a figure cannot be traced to captured
output, it does not appear.

A replay interface — *see what really happened, here is the commit* — is
honest. An interactive console that accepts an amount and animates a plausible
result is not, however impressive it looks, and this project cannot ship one
without contradicting every other decision recorded in this directory. The
distinction is not stylistic: a reviewer who watches a real trace is being given
*more* than one who types into a box that pretends.

### RULE 3 — Quote, never paraphrase

Any statement about what a control *does* or *guarantees* is a verbatim
quotation from `COMPLIANCE.md` or `DOMAIN_MODEL.md`, attributed and linked at
the captured commit. The walkthrough explains what you are **seeing**; it quotes
for what it **means**.

Rules 1 and 2 govern diagrams and figures. Without Rule 3 the narrative prose —
the sentences a reader actually absorbs, in the artifact most likely to be
screenshotted, read by more people than `COMPLIANCE.md` ever will be — would be
the one ungoverned surface. It is also precisely where *"enforced in the domain
and in the database"* becomes *"bank-grade controls"* without anyone deciding to
lie. The test to apply: **could this sentence be wrong about the system, rather
than merely wrong about the picture?** If yes, it is a control claim, and it is
quoted.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| One repository — vendor the walkthrough into `clofin-core` | Puts unaudited presentation code inside the release-audit subject. The audit would either have to audit demo code or state its scope with a carve-out; the second is the F-001/F-002 partial-set class, in the charter that exists to hunt it |
| Three or more repositories — one per view | The three views share one fixture set and one walkthrough. Splitting them triples the governance surface — three READMEs, three disclaimers, three CI configurations, three places for the scope statements to drift — to model a distinction that does not exist |
| An interactive console that simulates payments | Violates RULE 2. It would demonstrate a system that does not exist while sitting beside documents insisting on checkable claims. The most damaging possible artifact for this project's credibility |
| Hand-drawn diagrams, maintained carefully | Tried, in `DOMAIN_MODEL.md` §3. It drifted. That is L-4 |
| Screenshots of a running console | A screenshot is a hand-drawn diagram with extra steps: it cannot be regenerated, cannot be checked, and goes stale silently |
| Do nothing — documents are enough | Defensible for four increments; no longer. D5 says *inspectable*, and the controls that most distinguish this system are the ones hardest to read about |

## Consequences

**Positive**

- The lifecycle diagram cannot disagree with the lifecycle. Neither can the
  control map disagree with `COMPLIANCE.md`. L-4's drawing half becomes a CI
  failure instead of a human check.
- What the controls actually do becomes watchable: segregation of duties
  refusing a real submission, a batch surviving four kinds of misbehaviour with
  the journal still balanced, clearing exposure moving through
  `1300-IN-TRANSIT` and refusing to drain for a timed-out item.
- Every figure shown carries the commit it came from, so a reviewer can check
  any claim against the repository rather than trusting the page.
- The release audit's subject stays exactly one repository, stated as a
  definition rather than an exception.

**Negative / accepted cost**

- Roughly three to four weeks that do not go to increment 6 (reconciliation).
  Recorded in the ROADMAP rather than absorbed: a delay discovered afterwards is
  worse than one stated in advance.
- A second repository is a second governance surface — its own README,
  disclaimer, CI and licence, and its own capacity to rot.
- Fixtures are captured from a specific commit and are therefore a snapshot.
  They go stale by construction; the stamp is what makes that visible rather
  than silent.
- `clofin-trace` publishes claims about an audited system without being audited
  itself.

**Risks and how they are mitigated**

- *Risk:* the walkthrough is screenshotted and circulated without context,
  reading as a payment system that moves real money. *Mitigation:* the four
  scope statements served by `GET /` are captured as a fixture and rendered
  **in-frame and non-dismissible** — never in a footer, because a screenshot
  crops a footer. `SIM-RTGS` and `SIM-ACH` keep their prefixes and are never
  prettified toward the name of a real scheme.
- *Risk:* the walkthrough's own prose overstates what the controls guarantee.
  *Mitigation:* RULE 3. Control claims are inherited from audited documents
  rather than minted in an unaudited one.
- *Risk:* being outside audit scope becomes a hole. *Mitigation:* the boundary
  holds because `clofin-trace` owns no truth. What it displays is captured
  output of an audited commit, produced by the **capture harness — which lives
  in `clofin-core` and is inside audit scope**, including a test asserting the
  harness cannot emit an unstamped fixture. Audit the harness and the
  walkthrough's honesty is inherited.
- *Risk:* fixtures drift from the system and the walkthrough quietly misleads.
  *Mitigation:* the displayed commit makes the snapshot's age checkable, and
  the tag's recorded release-audit coverage is displayed beside it rather than
  implied.
- *Risk:* the second repository becomes a place where the rules do not apply.
  *Mitigation:* these three rules are stated as governing **either**
  repository, and `clofin-trace` carries exactly two automated checks —
  fixture provenance present, and the scope disclaimer matching the captured
  `GET /` response byte for byte, so softened wording fails rather than passes.

## Verification

- **RULE 1:** `make diagrams-check` regenerates every committed diagram and
  fails CI on any difference; it runs inside `make verify`. A generated diagram
  is additionally compared with its source table in both directions, so neither
  a missing nor an invented element passes.
- **RULE 2:** the capture harness stamps every fixture with its source commit,
  and a test asserts it **cannot** emit an unstamped one — the precondition
  fails closed rather than relying on a convention (lesson L-13).
  `clofin-trace`'s `provenance-present` check fails the build if any fixture or
  the built output lacks that stamp.
- **RULE 3:** `clofin-trace`'s `disclaimer-verbatim` check compares the scope
  statements in the built output against the captured `GET /` response byte for
  byte; a check that merely asserted *a* disclaimer exists would pass on
  softened wording, which is the partial-guard shape (lesson L-6). Control
  claims carry their quotation and link at review.
- **The boundary:** the release-audit charter names the `clofin-core` release
  candidate as the audit's subject and the capture harness as in-scope. See
  `docs/audits/` on the `meta` branch.

Neither repository, at any point, may imply production readiness, real
institutional connectivity, regulatory approval, or that CloFin has handled real
funds. That constraint predates this decision and is unaffected by it.
