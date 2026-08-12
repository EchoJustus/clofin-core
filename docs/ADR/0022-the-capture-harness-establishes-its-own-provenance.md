# ADR-0022: The capture harness establishes its own provenance, and fails closed

- **Status:** Accepted
- **Date:** 2026-08-12
- **Deciders:** Technical lead / product owner
- **Supersedes / Superseded by:** — (implements [ADR-0020](0020-two-repositories-and-the-generate-replay-rules.md) RULE 2)

## Context

[ADR-0020](0020-two-repositories-and-the-generate-replay-rules.md) RULE 2 says
*replay, never fake*: every value `clofin-trace` shows was captured from a real
run of the real system and is **stamped with the commit it came from**. It also
says the boundary keeping `clofin-trace` out of release-audit scope holds
because that repository owns no truth — what it displays is captured output of
an audited commit, produced by a harness that is inside audit scope, *including
a test asserting the harness cannot emit an unstamped fixture*.

That leaves three questions the rule does not answer, all of which have to be
answered the same way every time or the stamp means nothing.

**1. How does the harness know which commit the running stack was built from?**
The obvious answers are all wrong. Asking the operator makes the most
load-bearing field in the artifact a value somebody typed. Reading
`git rev-parse HEAD` answers a different question — the harness runs from
`main` while the stack must run from a tag, so that value would be confidently
wrong. Asking the service is impossible: `ref-1` predates any build stamp and
`GET /` reports no commit, and the source state being captured cannot be
modified to make capture easier without capturing a different state.

**2. Where does the tag's release-audit coverage come from?** TASK-007 requires
it to be *read from the tag's annotation*, for the same reason the disclaimer
is captured rather than transcribed: a coverage sentence typed onto a page is a
claim somebody must remember to update when `ref-2` lands, and standing lesson
**L-15** is the record of nobody doing that. But `refs/tags/ref-1` is a
**lightweight** tag — `git cat-file -t ref-1` answers `commit` — while
`docs/audits/README.md` says `ref-<n>` tags are annotated and `docs/ROADMAP.md`
says of the partial audit that *"the tag annotation says so"*. The text both
documents describe exists in full as the body of the GitHub release published
on that tag.

**3. What happens when any of it cannot be resolved?** Standing lesson **L-13**
is precisely this shape one layer down: a load-bearing precondition that is
documented rather than enforced holds until the day it does not, and the day it
does not is invisible.

## Decision

### The harness establishes the SHA rather than discovering it

`make capture-trace` takes a **git ref**, not a base URL. It resolves the ref
to a full 40-character commit, creates a **detached worktree** at that commit,
and runs the captured commit's own migration runner and its own service from
inside that worktree. The running process is a child of the harness, started
from a directory whose `HEAD` was verified immediately before.

There is deliberately **no `--base-url`** that attaches to a stack somebody
else started. Nothing about such a stack can be resolved — not its commit, not
whether its tree was clean, not whether it was rebuilt since it started — and
offering the option would mean offering a bundle whose stamp is a guess, to
consumers who all treat the stamp as fact.

Three independent checks back the construction up, each of which can fail:

| Check | Catches |
|---|---|
| The worktree's `git status --porcelain` is empty | a reused worktree somebody edited |
| The live `GET /readyz` schema version equals the last entry in **that commit's** `resources/migrations/index.txt` | a different stack answering on the port |
| The captured commit's `src/clofin/money.clj` is byte-identical to the harness's | the harness rendering that commit's amounts with a formatter it never had |

The second is the one worth keeping: it compares two values produced by
different things. Standing lesson **L-16** — when two copies of a claim can
only agree, agreement proves nothing.

### Coverage is read from the tag's annotation, or from a committed mirror of it

`clofin.tools.capture.provenance/resolve-coverage` takes, in order:

1. the **annotated tag's message**, when the tag is annotated — the mechanism
   the protocol describes, preferred so that re-tagging `ref-1` or tagging
   `ref-2` as the protocol says needs no change here;
2. **`docs/releases/<tag>.annotation.txt`**, a byte-for-byte mirror of the
   release body, reviewed in the pull request that added it and re-checkable
   against the live release with `make check-release-annotation`.

Whichever it used is **stamped into every bundle** and rendered on the
walkthrough beside the coverage itself, along with the digest of the text it
was read from. A reader is never left to assume the stronger source.

The coverage is quoted **whole**: the paragraph beginning `RELEASE AUDIT:`,
verbatim, with no summarising. The short label — `PARTIAL` — is the token
between the heading and the first full stop, and never appears without the
paragraph in the same block.

### Everything fails closed

`clofin.tools.capture.bundle/write!` validates the complete stamp **before it
opens a file**, and is the only path to a bundle on disk. There is no
`--no-verify`, no environment variable that skips it, and no `spit` anywhere
else in the harness. A refusal therefore leaves nothing behind that looks like
output — which matters, because the next step in the pipeline copies files.

`clofin.tools.capture-test` removes each required field in turn and asserts
both the refusal and the absence of the file, and a further test asserts that
the removal list and the requirement list are equal, so a field added to the
stamp without a test fails rather than passing unnoticed (**L-6**).

### Bundles are committed in `clofin-trace`, not here

`make capture-trace` writes to `target/capture/`, which is git-ignored. The
committed copy of a bundle lives in `clofin-trace/fixtures/`, once. Two
committed copies of the same artifact is exactly the drift this project keeps
finding, and the consumer is the one that needs it under version control.

### Two derived values are produced here, deliberately

`clofin-trace` computes nothing, so anything the walkthrough needs that is not
literally in a response is produced at capture time, in this repository:

- **Amounts as text.** Money crosses the API as an integer count of minor
  units and no endpoint renders it as a decimal. Bundles carry a `display`
  string produced by the system's own `clofin.money/format-amount`, beside the
  minor units it came from, guarded by the formatter check above.
- **Quotations.** RULE 3's control statements are extracted verbatim from the
  **captured worktree's** `COMPLIANCE.md` and `DOMAIN_MODEL.md`, with the file,
  the line and a permalink at the captured commit. `clofin-trace` selects
  quotations by id and cannot author one.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Operator supplies the SHA (`--commit`) | The most load-bearing field in the artifact becomes a value somebody typed. RULE 2 exists to stop exactly that |
| Harness reads `git rev-parse HEAD` of its own checkout | Answers a different question. The harness runs from `main`; the stack must run from the tag |
| Add a build stamp to `GET /` so the service reports its commit | The right long-term answer, and it cannot be applied retrospectively to `ref-1`. Changing the source state to make it capturable captures a different source state. Worth doing for `ref-2` |
| Refuse to capture at all until `ref-1` is re-tagged annotated | Fails closed, correctly, and delivers nothing. Re-tagging a published release tag is the operator's call, not a Worker's, and the harness prefers the annotation the moment one exists |
| Read the coverage from the GitHub API at capture time | Makes capture depend on a network service and an unauthenticated rate limit, and produces bundles that cannot be reproduced offline. The mirror plus `make check-release-annotation` gets the same fidelity without either |
| Let `clofin-trace` format amounts | Arithmetic on a financial figure in the unaudited repository. ADR-0020 is explicit: if a value must be computed, it belongs in the captured output |
| Let `clofin-trace` hold the control quotations | A second copy of every control claim, in the repository most likely to be edited for tone. L-4 with the stakes raised |
| Commit the bundles in both repositories | Two copies of one artifact, and no mechanism keeping them equal |

## Consequences

**Positive**

- A bundle's stamp is a fact about how it was produced rather than a field
  somebody remembered to fill in, and the harness cannot produce one without it.
- Re-capturing at `ref-2` is one command with a different ref. The coverage,
  the tag, the SHA and the schema version all move by themselves; nothing in
  either repository is edited to describe the new state.
- The walkthrough can display the coverage source, so the weaker of the two
  sources is disclosed rather than hidden behind an identical-looking chip.

**Negative / accepted cost**

- Capture needs a local Clojure CLI and a reachable PostgreSQL. It cannot run
  through the `docker compose` toolchain fallback, because a toolchain
  container decides which commit's `deps.edn` built the stack and that is the
  one thing the harness must decide itself.
- The capture database is dropped and recreated on every run. Guarded by
  refusing any database whose name does not end in `_capture`.
- `docs/releases/<tag>.annotation.txt` is a second copy of the release body,
  and therefore something that can drift. `make check-release-annotation`
  compares it with the published release; it is not in `verify` because it
  needs the network.
- The line numbers in captured quotation permalinks are the captured commit's,
  so they do not follow the documents forward. That is the intent: the
  quotation is of that commit.

## Verification

- `clofin.tools.capture-test` — the fail-closed write, every required field
  removed in turn, with the removal list asserted equal to the requirement
  list; the coverage parser over both sources; and the sand table's refusal to
  hold a cell that does not come from a captured step. Runs in `make verify`.
- `make check-release-annotation` — the committed mirror against the published
  release body, byte for byte.
- `clofin-trace`'s `provenance-present` re-validates every stamp and resolves
  every displayed figure against the fixture it names, independently of the
  renderer that wrote it.
