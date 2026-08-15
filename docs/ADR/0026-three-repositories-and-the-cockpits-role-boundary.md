# ADR-0026: Three repositories, and the cockpit's role boundary

- **Status:** Accepted
- **Date:** 2026-08-15
- **Deciders:** Technical lead / product owner (operator ruling D1/D2)
- **Supersedes / Superseded by:** — · **Amends:** [ADR-0020](0020-two-repositories-and-the-generate-replay-rules.md) (§Amendment 1)

## Context

[ADR-0020](0020-two-repositories-and-the-generate-replay-rules.md) decided that
**"CloFin is two repositories, and no more"**, and in the same breath decided
that **"the operator console remains increment 8, inside `clofin-core`"**. Those
two sentences have been in tension since the day they were written, and the
tension is now due.

Three facts make it due.

**The console is a client, not a view.** ADR-0020 rejected "three or more
repositories — one per view" for a specific reason: the topology, the ledger
sand table and the evidence timeline "share one fixture set and one
walkthrough", so splitting them "triples the governance surface … to model a
distinction that does not exist". That reasoning is sound and is not what is
being proposed here. The operator console shares no fixture set with
`clofin-trace`, because it consumes no fixtures. It calls a running API. The
distinction between replaying a capture and driving a live instance is not a
presentational one — it is the difference between the past and the present, and
between *may never fake* and *may never claim*. ADR-0020 said as much itself:
the console "is a different kind of artifact from a replay."

**A frontend toolchain inside `clofin-core` would cost a doctrine.**
[ADR-0004](0004-minimal-dependency-footprint.md) states a minimal runtime
dependency footprint, and [PRD](../PRD.md) **NFR-007** requires that *every*
runtime dependency is justified by an ADR. An operator console is a browser
application: even the most disciplined one arrives with a package manager, a
lockfile and a dependency graph whose transitive members no one will write an
ADR for. `clofin-core` would then face a choice between two bad options —
qualify ADR-0004 and NFR-007 with a "except under `ui/`" carve-out, or maintain
the fiction that the carve-out is not there. A guarantee stated over a partial
set is the defect class this project spends the most effort hunting; the
[release-audit charter](../audits/RELEASE-AUDIT-CHARTER.md) exists in part to
find it. Introducing one voluntarily, into the dependency doctrine, would be a
poor trade.

**ADR-0020's own load-bearing argument points this way.** Its reason for two
repositories rather than one was that "a walkthrough vendored into
`clofin-core` would put unaudited presentation code inside the release-audit
subject and force the audit's scope statement to acquire a carve-out." Every
word of that applies to an operator console vendored into `clofin-core`, and
applies more strongly: a console is larger than a walkthrough, changes more
often, and carries a dependency tree the audit would have to either cover or
exclude by name. The decision to keep the console inside was not derived from
that argument — it was carried forward from the roadmap position it already
occupied, with the reason given as "the roadmap already gives" it.

On 2026-08-15 the operator ruled on both points.

## Decision

The ruling, recorded as made:

> **(D1)** CloFin becomes **three repositories**, amending ADR-0020's "two, and
> no more"; **(D2)** the third is named **`clofin-cockpit`** — the operator
> cockpit, interaction with the *present*: a transparent client that deploys and
> drives a real reference instance.

**CloFin is three repositories.** The role boundary, which is what the count is
for:

| Repository | Role | Owns truth? |
|---|---|---|
| [`clofin-core`](https://github.com/EchoJustus/clofin-core) | The system, its controls, its documents, the capture harness | **All of it** |
| [`clofin-trace`](https://github.com/EchoJustus/clofin-trace) | Replay of the **past** — captured output of a tag; may never fake | No |
| [`clofin-cockpit`](https://github.com/EchoJustus/clofin-cockpit) | Interaction with the **present** — a client of the real API; **may never claim** | No |

Four consequences follow, and each is part of the decision rather than a note
on it.

**1. Increment 8 relocates.** The operator interface is built in
`clofin-cockpit`, not inside `clofin-core`. Its roadmap position is unchanged in
content and sequence; only its address changes. ADR-0020's sentence "the
operator console remains increment 8, inside `clofin-core`" is amended
accordingly, in ADR-0020's own file, with its original text intact.

**2. The frontend toolchain decision lives in `clofin-cockpit`.** Framework,
build tool, test runner and every dependency they bring are decided and recorded
in **`clofin-cockpit`'s own ADR series**, which starts at `0001`. This
repository's ADR numbering is not shared with it, and neither is its dependency
doctrine.

**3. [ADR-0004](0004-minimal-dependency-footprint.md) and NFR-007 continue to
govern `clofin-core` unqualified.** No carve-out, no exception, no "except for
the UI". That is the point of moving the console out, and it is the property
that would be lost by leaving it in. `clofin-cockpit` does not inherit ADR-0004
— it is a different repository with a different job — but it inherits the
instinct, and is expected to justify its dependency count in its own ADR-0001.

**4. The cockpit may never claim.** ADR-0020's three rules govern all three
repositories, with RULE 2 read in the cockpit's tense:

- **RULE 1 — generate, never draw.** Unchanged. Any diagram is produced from its
  source of truth and checked in CI.
- **RULE 2 — replay, never fake**, which for a client of a live API is *drive,
  never simulate*: every value the cockpit displays is a real response from a
  real instance, shown alongside the request that produced it. ADR-0020 rejected
  "an interactive console that simulates payments" as "the most damaging
  possible artifact for this project's credibility". That rejection stands
  unaltered — the cockpit is permitted because it does the opposite of what was
  rejected, not because the rejection has been softened.
- **RULE 3 — quote, never paraphrase.** Unchanged, and it is the rule the
  cockpit lives or dies by. Any statement about what a CloFin control *does* or
  *guarantees* is a verbatim quotation from `COMPLIANCE.md` or
  `DOMAIN_MODEL.md`, attributed and linked. `clofin-trace` may never fake;
  `clofin-cockpit` may never claim.

**The release-audit subject does not change.** It remains the `clofin-core`
release candidate. `clofin-cockpit` is outside it, on the same basis as
`clofin-trace`: it owns no truth. What it displays is the real answer of an
audited commit, and the tag's recorded audit coverage is displayed beside that
answer rather than implied.

**No API-driving capability is authorised by this ADR.** A browser calling a
CloFin instance requires a CORS decision in `clofin-core`, which is its own
change, reviewed on its own terms. Nothing here pre-approves it.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Two repositories — build the console inside `clofin-core`**, as ADR-0020 decided | Puts a package manager, a lockfile and a transitive dependency graph inside the repository whose ADR-0004 and NFR-007 promise the opposite, and inside the release-audit subject. Both promises would need a carve-out, and a guarantee stated over a partial set is the failure this project hunts hardest |
| **Two repositories — build the console inside `clofin-trace`** | Collapses *may never fake* and *may never claim* into one repository with one README and one disclaimer. The trace's honesty comes from owning no live behaviour at all; adding live behaviour to it destroys the property that makes it auditable by inheritance |
| **Three repositories, but the cockpit vendors the trace's build** | The two share no fixture set, no toolchain requirement and no tense. The only thing they share is the scope statement, which is copied verbatim from `GET /` in both and checked byte for byte in both — which is the correct amount of sharing for a string that must never drift |
| **Keep the console out of git entirely — a local operator script** | Fails D5. An inspectable system needs an inspectable operator surface; a script on someone's machine is inspectable by exactly that person |
| **A console that simulates the system for demonstration** | Rejected by ADR-0020 RULE 2, and rejected again here. It remains the most damaging possible artifact for this project's credibility, and nothing about a third repository makes it less so |
| **Defer the whole question until `ref-2`** | The repository was already created with a public README stating its scope; leaving its governing decision unrecorded while its charter is public is precisely the drift AGENT_HANDOFF §1 exists to prevent |

## Consequences

**Positive**

- ADR-0004 and NFR-007 keep their unqualified form in `clofin-core`, and the
  release audit keeps a one-repository subject stated as a definition rather
  than an exception.
- The three repositories now map onto three distinct relationships with truth —
  owns it, replays it, queries it — rather than onto three kinds of artifact.
  A contributor can tell which repository a change belongs in by asking which
  tense it is in.
- The cockpit's dependency choices are argued in the repository that bears their
  cost, by an ADR whose reviewers are looking at that trade rather than at a
  ledger.

**Negative / accepted cost**

- A third governance surface: a third README, a third disclaimer, a third CI
  configuration, a third place for a scope statement to drift. ADR-0020 counted
  this cost when it refused a third repository, and the count has not changed —
  only the thing being bought with it.
- Two repositories now publish statements about an audited system without being
  audited themselves.
- Work that would have been reviewed under `clofin-core`'s test discipline is
  reviewed under a lighter one. The mitigation is that the cockpit is not
  permitted to make any statement that would need that discipline.

**Risks and how they are mitigated**

- *Risk:* the cockpit's prose overstates what CloFin guarantees — the failure
  mode of a live console far more than of a replay, because a live console
  looks like the system. *Mitigation:* RULE 3, enforced mechanically rather than
  at review: `clofin-cockpit` carries a `no-unqualified-audited` check that
  fails the build on any text describing a release as audited, verified or
  reviewed without a coverage qualifier beside it.
- *Risk:* the scope statement drifts between three copies. *Mitigation:* one
  canonical constant per repository, compared byte for byte against the `GET /`
  text in CI, so softened wording fails rather than passes — the same
  `disclaimer-verbatim` shape ADR-0020 required of the trace, and lesson L-6's
  standing remedy.
- *Risk:* "three repositories" becomes four, then five, by the same reasoning
  applied loosely. *Mitigation:* the boundary is a relationship with truth, not
  a kind of artifact. A fourth repository needs a fourth such relationship, and
  there are not obviously any left; anything else is a view within one of the
  three.
- *Risk:* a screenshot of the cockpit circulates without context and reads as a
  payment system moving real money. *Mitigation:* the same one ADR-0020 chose,
  applied to a live surface — the `GET /` scope statement renders **in-frame and
  non-dismissible on every view**, never in a footer, because a screenshot crops
  a footer; and a release in context always shows tag, commit SHA and audit
  coverage together.
- *Risk:* the cockpit displays a tag's coverage as better than it is, by
  defaulting when it cannot parse. *Mitigation:* the coverage parser fails
  closed — an unrecognisable coverage paragraph renders as "coverage statement
  not found", never as a blank and never toward any word like "audited"
  (lessons L-6 and L-13, the fail-closed precondition shape).

## Verification

- **The amendment exists where the convention says it does.**
  [ADR-0020](0020-two-repositories-and-the-generate-replay-rules.md) carries a
  dated `## Amendment 1` section with its original decision text intact above
  it, and this index lists both. `make docs-check` resolves every link in both
  directions.
- **`clofin-core` is unchanged apart from documents.** This ADR adds no code, no
  dependency, no CORS configuration and no build step. `make verify` passes
  exactly as it did before it.
- **The boundary is checkable in the cockpit, not asserted here.**
  `clofin-cockpit` carries exactly two automated checks — `scope-verbatim`
  (the rendered scope statement matches its canonical constant and the README's
  quotation byte for byte) and `no-unqualified-audited` — and its build refuses
  to emit a site that would reach any origin other than `api.github.com`. Two
  checks, not three, for the reason ADR-0020 gave for the trace: a repository
  that owns no truth is not entitled to assert a third guarantee.
- **The dependency doctrine is unqualified.** ADR-0004 and NFR-007 carry no
  exception clause for a user interface, and after this decision there is no
  user interface in this repository for one to be written about.

No repository in this project, at any point, may imply production readiness,
real institutional connectivity, regulatory approval, or that CloFin has handled
real funds. That constraint predates ADR-0020, survived it unchanged, and is
extended verbatim to the third repository by this decision.
