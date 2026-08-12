# 006-REQ — Generated diagrams, and two CI guards for document truth

| Field | Value |
|---|---|
| **Brief** | `006-TASK-generated-diagrams.md` (read from `origin/meta`, per AGENT_HANDOFF §1b) |
| **Increment** | 5v.1 (visual layer, tier 0) |
| **Branch** | `claude/diagram-generator-consistency-gpjl0d` — designated by the execution environment; the brief names no branch of its own |
| **PR base** | `main` at `cbbd669` (ADR-0020 merged in PR #11). No stacking: TASK-006's only dependency is merged |
| **Governing decision** | [ADR-0020](../ADR/0020-two-repositories-and-the-generate-replay-rules.md) RULE 1 — generate, never draw |
| **New ADR** | [ADR-0021](../ADR/0021-diagrams-are-mermaid-generated-from-code-and-tables.md) — format, sources and classpath placement |
| **Migrations** | **None.** This is document machinery; no schema, no controls move |
| **Runtime dependencies added** | **None.** No Node, no npm, no Maven coordinate. ADR-0004 and NFR-007 intact |
| **Status** | Implemented. **One objection (O-1) blocks part of AC-7** and needs a ruling; O-2…O-5 are recorded decisions, not blocks |

### Provenance

| | |
|---|---|
| **Model** | `claude-opus-5` |
| **Reasoning effort** | High. Ultracode enabled for the session; **no** subagents or workflow orchestration were used — the session's own instructions prohibited them, and that prohibition was followed |
| **Date** | 2026-08-12 |
| **Verification still in flight** | **None. Nothing is running.** See §7 — this is the L-9 statement, and it is unqualified |

---

## 1. What was built

### 1.1 A diagram generator — `clofin.tools.diagrams`

Run as `make diagrams` (write) and `make diagrams-check` (verify), through a
`deps.edn` `:diagrams` alias. Three namespaces, all on a new `tools` classpath
root that `:paths` does not carry:

| Namespace | Job |
|---|---|
| `clofin.tools.markdown` | Just enough Markdown to treat a document as a source: headings, pipe tables, bullet lists. Not a parser and must not become one |
| `clofin.tools.mermaid` | Label escaping, wrapping, node ids, fenced blocks |
| `clofin.tools.diagrams` | The three diagrams, the artifact set, the write and check modes |

Five generated artifacts, listed in one place (`artifacts`):

| Artifact | Source of truth |
|---|---|
| `docs/diagrams/payment-lifecycle.md` | `clofin.payments.state/transitions` |
| `docs/diagrams/context-topology.md` | `ARCHITECTURE.md` §3's table + the `ns` forms under `src/` |
| `docs/diagrams/control-map.md` | `COMPLIANCE.md` §2 (and §1's status vocabulary) |
| `docs/diagrams/README.md` | The artifact set itself |
| `docs/DOMAIN_MODEL.md` | A managed block, spliced between `BEGIN`/`END GENERATED` markers |

**The lifecycle diagram derives rather than lists.** The nine states come from
`transitions`' keys, the eleven pairs from its entries, the start marker from
`initial-state`, and the five terminal states from `state/terminal?` — so a
state that gains an outgoing arrow stops being drawn as terminal in the same
commit that gives it one. States are declared `state "pending-approval" as
pending_approval`, so the box carries the value the database check constraint
and `api/openapi.yaml` actually use.

**The control map keeps the document's words.** Each control's enforcement
points are read verbatim — a table's first column, a bullet, or a
semicolon-separated clause of a prose sentence — and are *not* shortened to the
identifier inside them. Shortening `"Review of `:deps` additions. *(Not
mechanical — …)*"` to its first clause would draw C-12 as mechanically enforced,
which is standing lesson **L-14** in one line of code. A point named by more
than one control is one box with several arrows into it, which is the thing the
table in `COMPLIANCE.md` cannot show: three controls rest on
`clofin.authz.approval/evaluate`.

### 1.2 A consistency guard — `scripts/check-doc-consistency.sh`

POSIX `sh` driving a POSIX `awk` program, no dependencies, the same shape as
`check-doc-links.sh`. Run as `make doc-consistency`. Four assertions, each in
both directions:

1. Every control the ROADMAP's controls prose speaks about carries the status
   `COMPLIANCE.md` §2 gives it — and names a control §2 defines.
2. No increment the ROADMAP shows as not started (📋 / 💭) has a brief whose
   status is `CLOSED` or `IMPLEMENTED`.
3. Where the ROADMAP restates a brief's status, the two words match.
4. The ROADMAP's global-state table, the briefs backlog, and the brief files on
   disk name the same set of briefs.

It reads only the working tree it is run from and never reaches across
branches, exactly as the brief specifies. It reports and never repairs.

**It fails closed.** A renamed or deleted controls paragraph, a control heading
with no status glyph, a status vocabulary that has moved, a legend that has lost
a not-started glyph, a global-state table whose columns have been renamed, and a
line claiming two statuses at once are all *errors*, not skips. A guard that
silently checks nothing is indistinguishable, on a green build, from a guard
that holds — lesson **L-6**.

### 1.3 What the hand-drawn diagram was actually wrong about

`DOMAIN_MODEL.md` §3's ASCII drawing is deleted. Its mitigation was a note
asking a reader to compare it with the transition table (lesson **L-4**). Two
things that note had not caught, and that a generated drawing cannot contain:

- it drew **`pending_approval`**, with an underscore, where migration `0003`'s
  check constraint, `api/openapi.yaml` and `clofin.payments.state` all say
  `pending-approval`;
- it carried a **`reverse` arrow out of `settled`** to a "new reversal
  instruction". `reverse` is not a transition — `reversible-states` is a rule
  about status, which is exactly why ADR-0014 holds it as a named set beside the
  table — so no drawing generated from `transitions` can show it.

Both are asserted directly in `clofin.tools.diagrams-test`, so neither can come
back.

**L-4 is narrowed, not closed**, and `DOMAIN_MODEL.md` §3 now says so in the
document itself: the numbered rules below the diagram are prose about the
lifecycle, and prose can still contradict the table. Diagram-versus-table is
mechanical; prose-versus-table remains a human check.

---

## 2. Acceptance criteria

| # | Named check | Status |
|---|---|---|
| AC-1 | `clofin.tools.diagrams-test/ac-1-a-transition-added-without-regenerating-fails-the-check` and `…-removed-…`. Both redefine the lifecycle, leave the artifacts untouched, and assert `check` fails naming the artifact **and** the changed event. `make diagrams-check` is the same code path in CI | ✅ |
| AC-2 | `make diagrams-check`, run in a fresh JVM against bytes an earlier JVM produced — the cross-process half. In-process: `ac-2-generating-twice-produces-identical-bytes`. Structural: `ac-2-every-emitted-sequence-is-sorted`. Manually confirmed byte-identical across **three** separate JVM invocations | ✅ |
| AC-3 | `ac-3-the-lifecycle-diagram-and-the-transition-table-agree-in-both-directions` — states, events, pairs, the initial marker and the derived terminal set, each compared as a set in both directions. The diagram is re-parsed with regexes that share no code with the emitter | ✅ |
| AC-4 | `ac-4-the-control-map-and-compliance-section-2-agree-in-both-directions` (ids, status group, title) and `ac-4-a-control-added-to-compliance-without-regenerating-fails-the-check` | ✅ |
| AC-5 | `clofin.tools.doc-consistency-test/ac-5-the-2026-08-05-contradiction-is-caught` **and its mirror image**. Negative control also run by hand against the real documents — see §3.2 | ✅ |
| AC-6 | `ac-6-a-closed-brief-under-a-not-started-increment-is-caught` and `…-an-implemented-brief-…`. The fixture's increments are `5v.1` and `5v.2`; the test asserts the message says `increment 5v.1` and **not** `increment 5.1`, so the key is opaque rather than parsed | ✅ |
| AC-7 | `make verify` runs `test`, `docs-check` and `diagrams-check`. **`check-doc-consistency` is not wired in — see objection O-1.** This is the brief's own documented fallback, not a divergence taken unilaterally | ⚠️ **partial** |
| AC-8 | `ac-8-domain-model-section-3-has-no-hand-maintained-diagram` — asserts §3 contains no box-drawing character at all, that the replacement names its source, its generator and its check, and that it sits inside the managed block | ✅ |

---

## 3. Test results

### 3.1 Actual numbers

| Command | Result |
|---|---|
| `make verify` | **322 tests, 1991 assertions, 0 failures, 0 errors.** Documentation links OK (67 files). Diagrams OK (5 artifacts match their sources) |
| `clojure -M:test:it` | **640 tests, 4222 assertions, 0 failures, 0 errors** |
| Of which new | `clofin.tools.diagrams-test` + `clofin.tools.doc-consistency-test`: **38 tests, 248 assertions** |
| Baseline before this branch | 284 tests, 1743 assertions, 0 failures — confirmed green on `cbbd669` before anything was changed |

**How `make test-it` was run, stated plainly.** This Worker's environment has
the Docker CLI but **no Docker daemon**, so `make test-it`'s `db-up` step could
not run. The suite was run against a PostgreSQL **16.13** cluster initialised
locally with `initdb` and migrated with `clojure -M -m clofin.db.migrate` (11
migrations applied cleanly), then `clojure -M:test:it` with `CLOFIN_DB_URL`
pointed at it — the same test set the Make target runs, the same major version
the compose stack and CI pin. It is the identical suite; it is not literally the
`make test-it` command, and saying otherwise would be the kind of small
inaccuracy this project's whole claim rests on avoiding.

### 3.2 AC-5's negative control, actually run

Required by the brief's Definition of Done, so recorded in full. The 2026-08-05
paragraph was reinstated verbatim in `docs/ROADMAP.md` on the working tree —

```
**Controls still unenforced.** Four entries in
[`COMPLIANCE.md`](../COMPLIANCE.md) are 📋 *designed, not built* — C-01, C-02,
C-05 and C-06. TASK-002 delivers C-06; TASK-003 delivers the other three.
```

Verbatim but for one character: the inline link's target reads `COMPLIANCE.md`
in the ROADMAP and is rewritten to `../COMPLIANCE.md` here so that `make
docs-check` — which does not exempt fenced blocks — can resolve it from this
directory. It caught the unrewritten version, which is a small point in its
favour.

`sh scripts/check-doc-consistency.sh` was then run. It reported four new
disagreements, each naming both documents, both line numbers and both values:

```
DISAGREE  docs/ROADMAP.md:30  says C-01 is 📋
          docs/COMPLIANCE.md:38  says C-01 is ✅
DISAGREE  docs/ROADMAP.md:30  says C-02 is 📋
          docs/COMPLIANCE.md:124  says C-02 is ✅
DISAGREE  docs/ROADMAP.md:31  says C-05 is 📋
          docs/COMPLIANCE.md:255  says C-05 is ✅
DISAGREE  docs/ROADMAP.md:31  says C-06 is 📋
          docs/COMPLIANCE.md:486  says C-06 is ✅
```

Note the second and fourth: **C-06 and the second half of the list sit on a line
with no glyph on it**, and were classified from the paragraph's lead-in. That is
the case a line-local parser would have missed, and it is why the classifier
falls back to the block's polarity rather than skipping.

The injection was then reverted with `git checkout docs/ROADMAP.md`; `git
status` shows `docs/ROADMAP.md` unmodified on this branch, and the guard is back
to the five findings of O-1. The same contradiction, and its mirror image
(COMPLIANCE stale, ROADMAP right), are permanent fixture cases so the control
does not depend on anyone repeating this by hand.

### 3.3 Determinism — how it was achieved

Asked for explicitly by the brief:

- **Sorted iteration everywhere.** States and events by `name`; edges by
  `(juxt from event)`; contexts by namespace root; context edges in a
  `sorted-set`; controls by id; enforcement points by their text; source files
  by path before any `ns` form is read. No `for [k v] some-map` reaches the
  output unsorted.
- **Fixed identifiers.** Node ids are derived from content (`C-01` → `c_01`,
  `clofin.ledger` → `clofin_ledger`). The one vocabulary that cannot guarantee
  injectivity — enforcement-point prose — is numbered by **sorted position**
  (`ep01`…), never by a counter that depends on traversal order.
- **Ordered grouping.** The control map's status groups follow
  `COMPLIANCE.md` §1's legend order, read from the document. Sorting by glyph
  would have been deterministic and meaningless (✅, 📋, 🔨 by codepoint).
- **No timestamps, no commit, no hostname, no environment variable, no
  `random`, no `gensym`.** The provenance banner names files and namespaces
  only.
- **Explicit `\n`**, one trailing newline, `\r\n` normalised on read.
- **In `awk`**, every report line is emitted from an explicitly indexed list in
  document order. `for (k in array)` order is unspecified in POSIX awk, and a
  report that reordered between runs would be as useless as one that failed
  intermittently. `the-report-is-byte-identical-between-runs` asserts it.
- Verified: three separate JVM invocations of `clojure -M:diagrams` produced
  byte-identical output (md5 over all five artifacts), and `make diagrams-check`
  passes from a **clean clone** of the pushed branch.

---

## 4. Objections

Numbered for arbitration. **O-1 blocks part of AC-7 and needs a ruling.**

### O-1 — The ordering trap is *not* released. `main`'s ROADMAP contradicts itself, so the guard is not in `verify`

**The brief says** (amendment A2): *"Released at dispatch… `main`'s ROADMAP
shows increments 3, 4, 4c and 5 as `CLOSED` and its controls prose agrees with
`COMPLIANCE.md`. You may wire the script into `verify` in the same PR. Confirm
it yourself before you do… If what you find disagrees with this note, the tree
wins and the note is the defect: stop and report."*

**I confirmed it, and it disagrees in part.** The `meta` → `main` sync (PR #10)
brought the ROADMAP's **global-state table** up to date. It did not touch the
same file's **per-increment sections**, which still say:

| Location | Says | The brief says |
|---|---|---|
| `## Increment 3 — … 📋` | not started | TASK-002 is `CLOSED` |
| `## Increment 5 — … 📋` | not started | TASK-004 is `CLOSED` |
| Increment 3's `**Status:**` line | `IMPLEMENTED` | TASK-002 is `CLOSED` |
| Increment 4's `**Status:**` line | `IMPLEMENTED` | TASK-003 is `CLOSED` |
| Increment 5's `**Status:**` line | `READY` | TASK-004 is `CLOSED` |

`origin/meta` carries the same staleness, so this is not a sync that half
applied — it is a defect on the control plane that the sync had nothing to
correct it from.

**This is L-15's own incident, still live.** The lesson's text reads: *"`main` —
the branch outsiders read — showed increments 3 and 4 as `READY`/blocked and 5–9
as 'not yet briefed' while all four were merged, audited and `CLOSED`."* Those
are these sections. The lesson records the incident as mechanically guarded from
2026-08-05 by this script; the script now exists, and the first thing it finds is
that the incident was never fully repaired.

**What I did instead of stopping.** Stopping the whole increment would have
delivered nothing over a defect in one paragraph of one file, and every other
part of the brief is independent of it. I took the brief's own fallback, in the
paragraph immediately below the released-trap note: *"build the script, commit
it, run it manually, and say plainly in your REQ that the `verify` wiring is
deferred — do not reorder around it, and do not 'fix' the ROADMAP yourself."*
So:

- `make doc-consistency` exists and runs; it is **not** in `verify`.
- The Makefile says why, at the `verify` target, with the specific unblocking
  step.
- `docs/ROADMAP.md` is **not touched** on this branch (`git diff main...HEAD`
  confirms).
- `clofin.tools.doc-consistency-test/the-known-roadmap-staleness-has-not-grown`
  pins the failure set **by count and by content**, so a *new* disagreement
  still fails `make test` today. The deferral is bounded, not open-ended, and it
  is visible in a test rather than in a comment nobody reads.

**What I did not do, and why it matters.** I did not narrow the check to the
global-state table to make it pass. A guard that read the ROADMAP's status
register and ignored a second copy of the same claim in the same file would be
green right now — and would be **lesson L-6 for the third time in this project**
(after C-01's docstring and TASK-005's `subjectType` guard), committed inside
the increment whose purpose is to close L-6's diagram half. That trade was
available, it was tempting, and it is exactly the one the lessons table exists
to refuse.

**Fix instruction requested.** Correct `docs/ROADMAP.md` on `meta` — five lines:
increment 3's heading to ✅ and its Status to `CLOSED`, increment 4's Status to
`CLOSED`, increment 5's heading to ✅ and its Status to `CLOSED` — re-sync to
`main`, then (a) delete `the-known-roadmap-staleness-has-not-grown` and (b) add
`doc-consistency` to `verify`'s prerequisites. Both are one-line changes and the
Makefile comment names them. Increment 4's heading (`🔨 in progress`, brief
`CLOSED`) is understated too and worth correcting in the same pass, though the
guard does not flag it: the brief scopes "not started" to 📋 and 💭, and 🔨 is
neither.

### O-2 — The context topology's arrows cannot come from `ARCHITECTURE.md` §3's table, because the table has no arrows

**The brief says** (scope item 2): *"the **context topology** from
`ARCHITECTURE.md` §3's table"*.

§3's table is `Context | Namespace root | Owns` — a roster, not a graph. The
dependency direction, which is the actual architectural claim (ADR-0007), is in
the **paragraph below** the table: *"the ledger's domain depends on nothing.
Payments depends on ledger and authz…"*. Drawing arrows from that paragraph is
forbidden by the brief's own out-of-scope table (*"a diagram of something only
prose describes would be a hand-drawn diagram with extra steps"*), and a
topology with no arrows is a table with rounded corners.

**What I did instead:** nodes from the table, arrows from the `:require` clauses
of the `ns` forms under `src/`, read as data rather than from loaded namespaces
— the same technique and the same reason as `clofin.ledger.purity-test`. The
diagram's own caption says so, and says the consequence: it shows what the code
*does*, so reading it against §3's paragraph is a check **on the paragraph**.
Recorded in ADR-0021. It has already earned its keep — it shows visibly that
`clofin.audit` has no outgoing context dependency, which is what §3's *"audit is
a sink"* means and which nothing previously verified.

### O-3 — "the ROADMAP's stated increment count" does not exist

**The brief says** (scope item 5, third assertion): *"the ROADMAP's stated
increment count agrees with the briefs backlog."*

The ROADMAP states no count, anywhere — not in the global-state table, not in
prose. There is nothing to compare a backlog count *to*.

**What I did instead:** implemented it as the check I believe was meant, which
is the one L-15's incident calls for (*"a reader concluded two increments existed
where six did"*) — a **set** comparison between the ROADMAP's global-state
table, the backlog table and the brief files on disk, in every direction, with
the two counts printed in the failure message when they differ. Flagged rather
than silently reinterpreted.

### O-4 — Assertion 2, taken literally, checks one of two copies of the same claim in the same file

**The brief says:** *"no increment the ROADMAP shows as not-started (📋/💭) has
a brief whose status is `CLOSED` or `IMPLEMENTED`"*, and its example failure
messages name *"the two documents"*.

The ROADMAP states each increment's status in **two** places: the global-state
table and the per-increment section (heading glyph, plus a `**Status:**` line
restating the brief's own word). Nothing in the document declares either copy
non-authoritative. A check over one of them is a guard over the copy its author
was looking at.

**What I did instead:** the guard reads **every** status claim in the ROADMAP —
both copies — and adds assertion 3 (a restated brief status must match the
brief), which is the same class and costs nothing. This is a strengthening
beyond the brief's literal list and is flagged as such. It is also what produces
O-1: three of O-1's five findings come from assertion 3, and would not exist
under the literal reading. **If Master Control rules that assertion 3 is out of
scope, the count in `known-staleness` drops to two and O-1 stands unchanged** —
the two heading findings are squarely within the literal assertion.

### O-5 — `check-doc-consistency` is two files, not one

**The brief says:** *"`scripts/check-doc-consistency.sh` — same shape as
`scripts/check-doc-links.sh`"*, which is a single self-contained shell script.

Mine is `check-doc-consistency.sh` (entry point, argument handling, file
discovery) plus `check-doc-consistency.awk` (the program). The shape is
otherwise identical: POSIX `sh` and `awk`, no dependency, one `make` target.
Embedding ~230 lines of awk in a shell heredoc means every `$1`, `$0` and `"`
in the awk program is quoted against the shell, which is where this class of
script acquires its bugs. Raised rather than assumed; trivially reversible if
Master Control prefers one file.

---

## 5. Two defects found by this session's own review, and fixed

Recorded because they are evidence about the guards, not just about the code.
Both were found *after* the implementation was complete and green, by
deliberately attacking it, and both are fixed in `8c81394`.

1. **`clofin.tools.markdown/plain` leaked a delimiter.** It removed a single
   `*` only where a lookbehind said it was not preceded by a word character —
   which strips the *opening* marker of emphasis and leaves the closing one, so
   `*invalidated*` became `invalidated*`. Latent: no enforcement point in
   `COMPLIANCE.md` today contains emphasis, which is the only reason it did not
   appear in a committed artifact. Now every asterisk goes.
2. **`check` compared only the files it was about to write.** A diagram dropped
   from the generator would have left its artifact committed under
   `docs/diagrams/` — still rendering, still believed — while the check stayed
   green. That is L-6, inside the increment that exists to close L-6's diagram
   half. `check` now reports an `ORPHAN` and says `make diagrams` will not
   remove it.

A third was found and fixed during implementation: `first-table` identified the
delimiter row by **pattern**, and `COMPLIANCE.md`'s enforcement tables have an
empty header (`| | |`) that matches every reasonable delimiter pattern. The
result was that C-05's first enforcement point — `audit_event_append_only`, the
append-only trigger on the audit trail — was silently missing from the control
map, on a diagram that otherwise looked complete. The reader now works by
position, and `enforcement-points-are-read-whole` counts the rows with a second,
dumber code path and asserts that specific row is present.

---

## 6. Debt knowingly left

- **`ARCHITECTURE.md` §2's system-context ASCII diagram is still hand-drawn.**
  It depicts CloFin's external simulated adapters, and its only source is prose,
  so RULE 1's stated exception (*"diagrams of anything without a machine-readable
  source"*) covers it. Named here rather than left to be discovered.
- **The controls-prose classifier is line-local with a paragraph-level
  fallback.** A single line naming two controls with *different* true statuses,
  where only one glyph appears, would classify both the same way. A line with
  two *different* glyphs is refused outright rather than guessed. The failure
  mode is a loud false positive, never a silent pass; a false pass additionally
  requires the misassigned glyph to coincide with what `COMPLIANCE.md` already
  says. Recorded rather than engineered around, because the alternative is a
  sentence parser over English.
- **Enforcement-point prose is split on semicolons only.** A semicolon inside a
  code span in an enforcement paragraph would split one point into two. No such
  point exists today.
- **The control map is a map of what `COMPLIANCE.md` claims**, and its own text
  says so. It is not evidence that a control holds; the enforcement points are.

---

## 7. Verification status — the L-9 statement

**Nothing is in flight. No review, no test run, no background process is
outstanding at the time of this report.**

The adversarial self-review that L-9 exists to protect was run **before** this
file was written, not after. It found the two defects in §5; both are fixed,
committed in `8c81394`, and re-verified. The full unit and integration suites
were re-run after that commit, and `make diagrams-check` was re-run from a clean
clone of the pushed branch.

There is nothing I expect to surface later and no fix I am holding. If a review
is opened after this report, it will be reported as a new event rather than as a
continuation of this one.

---

## 8. Notes for the next session

- **TASK-007 (`clofin-trace`) is unblocked by this increment's completion**, but
  read O-1 first: the same `verify` wiring question will come up again for
  `clofin-trace`'s two checks, and the resolution here sets the pattern.
- **`make diagrams` after any change to `transitions`, `ARCHITECTURE.md` §3 or
  `COMPLIANCE.md` §2.** The build tells you if you forget; it will not do it for
  you, deliberately — a generator that rewrote artifacts during `verify` would
  make drift invisible in review, which is the whole point of committing them.
- **Adding a fourth diagram** means adding an entry to `artifacts` and a
  both-directions test. The `ORPHAN` check means removing one means deleting its
  file too.
- **The two guards cover complementary halves and neither subsumes the other.**
  A control added to `COMPLIANCE.md` §2 without regenerating is caught by
  `diagrams-check`; a control whose *status* disagrees with the ROADMAP is
  caught by `check-doc-consistency`. Do not let one be dropped on the grounds
  that the other exists.
