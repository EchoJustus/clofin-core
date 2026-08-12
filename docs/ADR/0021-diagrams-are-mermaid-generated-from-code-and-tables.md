# ADR-0021: Diagrams are Mermaid, generated from code and tables, on a tools path

- **Status:** Accepted
- **Date:** 2026-08-12
- **Deciders:** Technical lead / product owner
- **Supersedes / Superseded by:** — (implements [ADR-0020](0020-two-repositories-and-the-generate-replay-rules.md) RULE 1)

## Context

[ADR-0020](0020-two-repositories-and-the-generate-replay-rules.md) RULE 1 says
*generate, never draw*, and names three diagrams and their sources: the payment
lifecycle from `clofin.payments.state/transitions`, the context topology from
`ARCHITECTURE.md` §3, and the control map from `COMPLIANCE.md` §2. It does not
say in what format, by what toolchain, or where the generator lives — and three
constraints make each of those a decision rather than a default.

**No new runtime dependency, and no Node.**
[ADR-0004](0004-minimal-dependency-footprint.md) and NFR-007 stand. Every
mainstream diagram renderer — Mermaid's own CLI, PlantUML's web pipeline,
D2 — is a toolchain this repository has decided against. The ROADMAP already
records that increment 8's operator console will bring npm and will need its own
ADR qualifying ADR-0004; a documentation generator is not the place to spend
that.

**Two of the three sources are prose documents.** `clofin.payments.state` is
data and can be read as data. `ARCHITECTURE.md` §3 and `COMPLIANCE.md` §2 are
Markdown, and only *parts* of them are machine-readable. §3's table of contexts
is; §3's dependency rule — *"the ledger's domain depends on nothing. Payments
depends on ledger and authz"* — is a paragraph. A topology diagram needs
arrows, and RULE 1 has no exception for prose: its own out-of-scope table says a
diagram of something only prose describes "would be a hand-drawn diagram with
extra steps".

**A diagram generator is documentation machinery.** It reads the repository's
own documents and emits Markdown. Nothing in a running CloFin service has any
business being able to call it.

## Decision

**Diagrams are Mermaid, embedded in generated Markdown under `docs/diagrams/`,
produced by `clofin.tools.diagrams` on a `tools` classpath root that the runtime
does not carry.**

Four parts, each a decision on its own:

1. **Mermaid, not SVG.** Rendering Mermaid to SVG requires Node; emitting
   Mermaid requires nothing. GitHub, and most Markdown viewers, render a
   ```` ```mermaid ```` fence natively. A Mermaid diagram is also *text*, so
   `make diagrams-check`'s failure reads as the arrow that moved rather than as
   a wall of changed path coordinates — which is what makes the check worth
   having rather than merely present.

2. **The topology's nodes come from `ARCHITECTURE.md` §3's table; its arrows
   come from the `ns` forms under `src/`.** The table is the roster of
   contexts and nothing else in the repository lists them, so it is the only
   possible source for the nodes. The arrows are read from the declared
   `:require` clauses, because that is the machine-readable statement of the
   same fact §3 states in a sentence. The consequence is deliberate and is
   printed on the diagram: it shows what the code *does*, so reading it against
   §3's paragraph is a check **on the paragraph**.

3. **The generator lives on `tools`, not `src`.** `deps.edn`'s `:paths` stays
   `["src" "resources"]`; `tools` arrives through the `:diagrams`, `:test` and
   `:dev` aliases only.

4. **`DOMAIN_MODEL.md` §3 carries the lifecycle diagram inline, in a managed
   block** delimited by `BEGIN GENERATED` / `END GENERATED` comments, rather
   than linking to it. RULE 1's purpose is that the drawing cannot disagree
   with the table; a drawing the reader has to click through to is one they
   will not compare. The block is regenerated and byte-compared like any other
   artifact, so editing it in place fails the build.

Reading is done with a deliberately small Markdown reader
(`clofin.tools.markdown`) that handles headings, pipe tables and bullet lists
and **nothing else**, and that throws rather than returning empty when a
section it expects has moved. It is not a Markdown parser and must not become
one.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Mermaid CLI, PlantUML or D2 to produce SVG | Every one is a Node or Java toolchain this repository has decided against. ADR-0004 and NFR-007 would each need an amendment to render a picture, which is a poor trade for a picture |
| Hand-rolled SVG layout in Clojure | A layout engine is a real piece of software with its own defects, and RULE 1 obliges us to *improve the generator* when its output is ugly rather than nudge the drawing. That obligation is affordable against Mermaid's layout engine and not against one we maintain |
| ASCII art, generated | Deterministic and dependency-free, and this is what §3 used to carry by hand. It does not survive a fan-out of three terminal states without becoming unreadable, and it cannot be rendered anywhere as anything better |
| Topology arrows from §3's prose | RULE 1's stated exception-free position. A regex over an English sentence is a second copy of the truth with a parser in front of it — worse than the hand-drawn diagram, because it *looks* generated |
| Topology arrows from the loaded namespaces at runtime | A transitive require through some other namespace would draw an arrow the source does not contain. `clofin.ledger.purity-test` already reads `ns` forms as data for the same reason |
| No arrows — draw the contexts as a list | A topology with no edges is a table with rounded corners. The dependency direction *is* the architectural claim (ADR-0007) |
| The generator under `src/clofin/tools/` | Puts documentation machinery on the runtime classpath. Small cost, no benefit, and it is the kind of thing that is never removed later |
| `DOMAIN_MODEL.md` §3 links to the diagram instead of embedding it | A reader who must click does not compare. The drift RULE 1 exists to prevent is between a drawing and a table *a reader sees together* |
| Generate into `target/` and leave the artifacts uncommitted | Then the diagram does not appear in a diff, in a review, or on GitHub — and nothing detects that it changed. The committed artifact **is** the check |

## Consequences

**Positive**

- The lifecycle diagram cannot disagree with the lifecycle: `make
  diagrams-check` regenerates and compares byte for byte, and
  `clofin.tools.diagrams-test` compares the parsed drawing with the transition
  table in **both** directions.
- The topology diagram makes a previously unverifiable paragraph checkable. It
  already shows something §3 states only in passing — that `clofin.audit` has
  no outgoing context dependency, which is what "audit is a sink" means.
- The control map shows what a table cannot: an enforcement point named by
  three controls is one box with three arrows into it.
- No new dependency of any kind. The generator is Clojure and the checker is
  POSIX `sh` and `awk`.

**Negative / accepted cost**

- Mermaid's layout is Mermaid's. When a diagram is awkward the lever is the
  emitted graph — direction, grouping, label width — not the drawing.
- A viewer that does not render Mermaid shows the fence as text. That is
  legible, and it is the same text the check compares.
- `DOMAIN_MODEL.md` is now partly generated. The markers say so, and an edit
  inside them fails the build rather than being silently overwritten.
- `clofin.tools.markdown` is a small parser for documents that were not written
  to be parsed. Every reader it has throws on a structure it does not
  recognise, so the failure is a build error rather than an empty diagram.

**Risks and how they are mitigated**

- *Risk:* a document is reworded and a generator silently produces less.
  *Mitigation:* every reader fails closed — a missing or duplicated section, a
  table with no delimiter row, a control heading with no status glyph and a
  status vocabulary that has moved are all errors. The parser bug found while
  building this (an empty header row `| | |` read as a delimiter, silently
  dropping C-05's first enforcement point) is exactly this failure mode, and is
  now covered by a test.
- *Risk:* output moves between runs and the check becomes a coin toss, then
  gets deleted. *Mitigation:* every emitted sequence is sorted by an explicit
  comparator over strings; no clock, commit, hostname or environment variable
  is read; and `clofin.tools.diagrams-test` asserts the sortedness directly
  rather than inferring it from two equal runs in one JVM.
- *Risk:* a generated artifact is edited by hand and regenerated later,
  silently losing the edit. *Mitigation:* it cannot be edited later — the
  banner says so and `make diagrams-check` fails the build in between.

## Verification

- `make diagrams-check` regenerates every artifact and fails on any difference,
  printing the differing lines. It runs inside `make verify` and in CI.
- `clofin.tools.diagrams-test` compares each generated diagram with its source
  **in both directions**, so neither a missing nor an invented element passes,
  and runs the negative controls: a transition added, a transition removed and
  a control added, each with nothing regenerated, each asserted to fail.
- `clofin.ledger.purity-test` is unaffected and stays the authority on the
  runtime layering; `tools` is not on `:paths`, so no purity rule needs to
  learn about it.
