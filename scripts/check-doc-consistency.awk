# The document-consistency guard. Driven by check-doc-consistency.sh, which
# passes COMPLIANCE.md, the briefs backlog, every brief and the ROADMAP — in
# that order, because the ROADMAP's claims are checked against the other three.
#
# POSIX awk only: no gensub, no asort, no length(array). Every report is emitted
# in document order from an explicitly indexed list, never by iterating an awk
# associative array, whose order is unspecified. A guard whose output reordered
# between runs would be as useless as one that failed intermittently.

function endswith(s, suffix) {
  return length(s) >= length(suffix) &&
         substr(s, length(s) - length(suffix) + 1) == suffix
}

function relative(p) {
  if (substr(p, 1, length(root)) == root) return substr(p, length(root) + 2)
  return p
}

function trim(s) {
  sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s)
  return s
}

# The single glyph in `s`, "" for none, "?" for more than one. Ambiguity is
# reported rather than resolved: a line claiming two different statuses is a
# defect in the document, not a case for the reader to handle.
function glyph_in(s,   n, found, i) {
  n = 0; found = ""
  for (i = 1; i <= 4; i++)
    if (index(s, glyphs[i]) > 0) { n++; found = glyphs[i] }
  if (n > 1) return "?"
  return found
}

# The backtick-quoted tokens of `s`, into out[1..n]. Returns n.
function backticked(s, out,   n, p, q, w) {
  n = 0
  while (1) {
    p = index(s, "`"); if (p == 0) break
    s = substr(s, p + 1)
    q = index(s, "`"); if (q == 0) break
    w = substr(s, 1, q - 1)
    s = substr(s, q + 1)
    out[++n] = w
  }
  return n
}

# The first backticked token that is a brief-lifecycle status, or "".
function lifecycle_word(s,   words, n, i) {
  n = backticked(s, words)
  for (i = 1; i <= n; i++)
    if (words[i] in lifecycle) return words[i]
  return ""
}

# The repository-relative path of the first brief `s` names.
#
# Matched on the file *name* rather than on the link target, because the two
# documents link to the same brief by different routes: the ROADMAP writes
# `](briefs/002-TASK-….md)` and the backlog, already inside `docs/briefs/`,
# writes `](002-TASK-….md)`. Brief names are unique and never renumbered
# (AGENT_HANDOFF §4), so the name is the identity.
function brief_link(s,   name) {
  if (match(s, /[0-9][0-9][0-9]-TASK-[A-Za-z0-9._-]*\.md/) == 0) return ""
  name = substr(s, RSTART, RLENGTH)
  return "docs/briefs/" name
}

function fail(text) {
  failures[++nfail] = text
}

BEGIN {
  glyphs[1] = "✅"; glyphs[2] = "🔨"; glyphs[3] = "📋"; glyphs[4] = "💭"
  DONE = glyphs[1]; PROGRESS = glyphs[2]; NEXT = glyphs[3]; LATER = glyphs[4]
  nfail = 0; ncontrols = 0; nclaimed = 0; nincrement = 0
  nroadmap_briefs = 0; nbacklog_briefs = 0; nbrief_files = 0
  controls_para = 0; legend_seen = 0; global_table_rows = 0
}

FNR == 1 {
  kind = "brief"
  if (endswith(FILENAME, "docs/COMPLIANCE.md"))     kind = "compliance"
  else if (endswith(FILENAME, "docs/briefs/README.md")) kind = "backlog"
  else if (endswith(FILENAME, "docs/ROADMAP.md"))   kind = "roadmap"
  rel = relative(FILENAME)
  section = ""; in_controls = 0; current_section_claim = 0
  global_row = 0; global_done = 0
}

# ---------------------------------------------------------------------------
# COMPLIANCE.md §2 — the control statuses everything else is compared against
# ---------------------------------------------------------------------------

kind == "compliance" && /^### C-[0-9]+/ {
  id = $2
  g = glyph_in($0)
  if (g == "" || g == "?")
    fail(rel ":" FNR "  control " id " has no single status glyph in its heading.\n" \
         "          COMPLIANCE.md §1 defines the vocabulary: ✅ 🔨 📋.")
  control_status[id] = g
  control_line[id] = FNR
  ncontrols++
}

# ---------------------------------------------------------------------------
# docs/briefs/README.md — the status vocabulary and the backlog
# ---------------------------------------------------------------------------

kind == "backlog" && /^## / { section = $0 }

kind == "backlog" && section ~ /^## Status lifecycle/ && /^\| `/ {
  split($0, f, "|")
  w = trim(f[2]); gsub(/`/, "", w)
  if (w != "") lifecycle[w] = 1
}

kind == "backlog" && section ~ /^## Backlog/ && /^\| \[/ {
  path = brief_link($0)
  if (path != "" && !(path in backlog_set)) {
    backlog_set[path] = FNR
    backlog_briefs[++nbacklog_briefs] = path
  }
  if (path != "") indexed_set[path] = FNR
}

# The Completed table indexes briefs that have left the backlog. A brief in
# neither table is one nothing points at.
kind == "backlog" && section ~ /^## Completed/ && /^\| \[/ {
  path = brief_link($0)
  if (path != "") indexed_set[path] = FNR
}

# ---------------------------------------------------------------------------
# Each brief — its own status, which is the authority the ROADMAP is checked against
# ---------------------------------------------------------------------------

kind == "brief" && FNR == 1 {
  brief_files[++nbrief_files] = rel
}

kind == "brief" && /^\| \*\*Status\*\* \|/ {
  if (!(rel in brief_status)) {
    w = lifecycle_word($0)
    if (w == "")
      fail(rel ":" FNR "  the Status field names no status from the lifecycle\n" \
           "          docs/briefs/README.md defines.")
    else {
      brief_status[rel] = w
      brief_status_line[rel] = FNR
    }
  }
}

# ---------------------------------------------------------------------------
# ROADMAP.md
# ---------------------------------------------------------------------------

kind == "roadmap" && /^Legend:/ {
  legend_seen = 1
  if (index($0, NEXT) == 0 || index($0, LATER) == 0)
    fail(rel ":" FNR "  the legend no longer defines both not-started glyphs\n" \
         "          (📋 and 💭). This guard's notion of \"not started\" came from it.")
}

kind == "roadmap" && /^## / { section = $0; in_controls = 0; current_section_claim = 0 }

# -- the global-state table: the ROADMAP's register of what exists ------------

kind == "roadmap" && section ~ /^## Global state/ && !/^\|/ && global_row > 0 { global_done = 1 }

kind == "roadmap" && section ~ /^## Global state/ && /^\|/ && !global_done {
  global_row++
  if (global_row == 1) {
    if (index($0, "Increment") == 0 || index($0, "Brief") == 0 || index($0, "Status") == 0)
      fail(rel ":" FNR "  the global-state table's columns have moved; this guard\n" \
           "          reads Increment, Brief and Status by name and found none of them.")
    next
  }
  if (global_row == 2) next            # the |---| delimiter
  global_table_rows++
  split($0, f, "|")
  key = trim(f[2]); gsub(/`/, "", key)
  path = brief_link(f[4])
  nincrement++
  inc_where[nincrement] = "the global-state table"
  inc_line[nincrement]  = FNR
  inc_key[nincrement]   = key
  inc_glyph[nincrement] = glyph_in(f[5])
  inc_brief[nincrement] = path
  inc_word[nincrement]  = lifecycle_word(f[5])
  if (path != "" && !(path in roadmap_set)) {
    roadmap_set[path] = FNR
    roadmap_briefs[++nroadmap_briefs] = path
  }
}

# -- the per-increment sections: a second copy of the same claim --------------

kind == "roadmap" && /^## Increment / {
  rest = substr($0, length("## Increment ") + 1)
  p = index(rest, " — ")
  key = (p > 0) ? substr(rest, 1, p - 1) : rest
  nincrement++
  inc_where[nincrement] = "its section heading"
  inc_line[nincrement]  = FNR
  inc_key[nincrement]   = trim(key)
  inc_glyph[nincrement] = glyph_in($0)
  inc_brief[nincrement] = ""
  inc_word[nincrement]  = ""
  current_section_claim = nincrement
}

kind == "roadmap" && /^\*\*Brief:\*\*/ && current_section_claim > 0 {
  path = brief_link($0)
  inc_brief[current_section_claim] = path
  w = lifecycle_word($0)
  if (w != "") {
    nincrement++
    inc_where[nincrement] = "its section's Status line"
    inc_line[nincrement]  = FNR
    inc_key[nincrement]   = inc_key[current_section_claim]
    inc_glyph[nincrement] = ""
    inc_brief[nincrement] = path
    inc_word[nincrement]  = w
  }
}

# -- the controls prose ------------------------------------------------------

kind == "roadmap" && /^\*\*Controls/ {
  controls_para++
  in_controls = 1
  if (index($0, "now enforced") > 0)      controls_default = DONE
  else if (index($0, "unenforced") > 0)   controls_default = NEXT
  else {
    controls_default = ""
    fail(rel ":" FNR "  a controls paragraph whose lead-in this guard does not\n" \
         "          recognise. It reads \"Controls now enforced\" and \"Controls still\n" \
         "          unenforced\"; anything else leaves control claims unchecked.")
  }
}

kind == "roadmap" && in_controls && /^[ \t]*$/ { in_controls = 0 }

kind == "roadmap" && in_controls {
  g = glyph_in($0)
  if (g == "?")
    fail(rel ":" FNR "  a controls line names two different statuses. Which one\n" \
         "          governs the control ids on it is not decidable, so it is not guessed.")
  else {
    effective = (g != "") ? g : controls_default
    rest = $0
    while (match(rest, /C-[0-9]+/)) {
      id = substr(rest, RSTART, RLENGTH)
      rest = substr(rest, RSTART + RLENGTH)
      if (effective == "")
        fail(rel ":" FNR "  " id " is claimed here with no status this guard can read.")
      else if (id in roadmap_claim && roadmap_claim[id] != effective)
        fail(rel ":" FNR "  the ROADMAP contradicts itself about " id ": " \
             roadmap_claim[id] " at line " roadmap_claim_line[id] ", " effective " here.")
      else if (!(id in roadmap_claim)) {
        roadmap_claim[id] = effective
        roadmap_claim_line[id] = FNR
        claimed[++nclaimed] = id
      }
    }
  }
}

# ---------------------------------------------------------------------------
# The report
# ---------------------------------------------------------------------------

END {
  # -- fail closed: a guard with nothing to check is not a passing guard ------
  if (ncontrols == 0)
    fail("docs/COMPLIANCE.md  §2 defines no controls this guard can read.")
  if (nbacklog_briefs == 0)
    fail("docs/briefs/README.md  the backlog table lists no briefs.")
  for (w in lifecycle) have_lifecycle = 1
  if (!have_lifecycle)
    fail("docs/briefs/README.md  the status lifecycle table is gone; brief statuses\n" \
         "          cannot be recognised without it.")
  if (!legend_seen)
    fail("docs/ROADMAP.md  the legend line is gone; \"not started\" is undefined.")
  if (global_table_rows == 0)
    fail("docs/ROADMAP.md  the global-state table has no rows.")
  if (controls_para == 0)
    fail("docs/ROADMAP.md  no controls paragraph. The check that COMPLIANCE and the\n" \
         "          ROADMAP agree about control status has nothing to compare, which is\n" \
         "          not the same as agreement (lesson L-6).")
  if (nclaimed == 0 && controls_para > 0)
    fail("docs/ROADMAP.md  the controls paragraph names no control.")

  # -- 1. control status: ROADMAP prose against COMPLIANCE §2 ----------------
  for (i = 1; i <= nclaimed; i++) {
    id = claimed[i]
    if (!(id in control_status))
      fail("docs/ROADMAP.md:" roadmap_claim_line[id] "  names control " id ",\n" \
           "          which docs/COMPLIANCE.md §2 does not define.")
    else if (control_status[id] != roadmap_claim[id])
      fail("docs/ROADMAP.md:" roadmap_claim_line[id] "  says " id " is " roadmap_claim[id] "\n" \
           "          docs/COMPLIANCE.md:" control_line[id] "  says " id " is " control_status[id] "\n" \
           "          Two documents on this branch disagree about a control's status.\n" \
           "          Neither is corrected here: this reports, it does not edit (L-15).")
  }

  # -- 2 and 3. increment status: ROADMAP against each brief -----------------
  for (i = 1; i <= nincrement; i++) {
    path = inc_brief[i]
    if (path == "") continue
    if (!(path in brief_status)) {
      fail("docs/ROADMAP.md:" inc_line[i] "  increment " inc_key[i] " links " path ",\n" \
           "          which has no readable status. The ROADMAP points at a brief that is\n" \
           "          missing or whose Status field has moved.")
      continue
    }
    if (inc_glyph[i] == NEXT || inc_glyph[i] == LATER) {
      if (brief_status[path] == "CLOSED" || brief_status[path] == "IMPLEMENTED")
        fail("docs/ROADMAP.md:" inc_line[i] "  shows increment " inc_key[i] \
             " as not started (" inc_glyph[i] ") in " inc_where[i] "\n" \
             "          " path ":" brief_status_line[path] "  its brief's status is " \
             brief_status[path] "\n" \
             "          A merged increment shown as not started understates what exists.\n" \
             "          That understatement is standing lesson L-15, and it survived two\n" \
             "          milestone audits and a release audit because nobody looks for it.")
    }
    if (inc_word[i] != "" && inc_word[i] != brief_status[path])
      fail("docs/ROADMAP.md:" inc_line[i] "  restates increment " inc_key[i] \
           "'s status as " inc_word[i] " in " inc_where[i] "\n" \
           "          " path ":" brief_status_line[path] "  the brief itself says " \
           brief_status[path] "\n" \
           "          The brief is authoritative; the ROADMAP is the stale copy (L-15).")
  }

  # -- 4. the brief sets, in every direction ---------------------------------
  for (i = 1; i <= nroadmap_briefs; i++) {
    path = roadmap_briefs[i]
    if (!(path in backlog_set))
      fail("docs/ROADMAP.md:" roadmap_set[path] "  its global-state table references " path "\n" \
           "          docs/briefs/README.md  its backlog table does not list it.")
  }
  for (i = 1; i <= nbacklog_briefs; i++) {
    path = backlog_briefs[i]
    if (!(path in roadmap_set))
      fail("docs/briefs/README.md:" backlog_set[path] "  the backlog lists " path "\n" \
           "          docs/ROADMAP.md  its global-state table does not reference it.\n" \
           "          " nroadmap_briefs " increment(s) with a brief on the ROADMAP, " \
           nbacklog_briefs " in the backlog.")
  }
  for (i = 1; i <= nbrief_files; i++) {
    path = brief_files[i]
    if (!(path in indexed_set))
      fail(path "  exists but is listed in neither docs/briefs/README.md's backlog\n" \
           "          nor its Completed table. A brief nothing indexes is a brief nobody\n" \
           "          reads — " nbrief_files " brief file(s) on disk, " nbacklog_briefs \
           " in the backlog.")
  }

  # -- output ----------------------------------------------------------------
  if (nfail == 0) {
    printf "Document consistency OK (%d control(s), %d increment status claim(s), %d brief(s)).\n",
           ncontrols, nincrement, nbacklog_briefs
    exit 0
  }
  for (i = 1; i <= nfail; i++) printf "DISAGREE  %s\n\n", failures[i]
  printf "%d document disagreement(s).\n", nfail
  print "These are reported, never repaired: a script that edited the ROADMAP would"
  print "make it agree with COMPLIANCE while both were wrong. Governance documents"
  print "(ROADMAP.md, docs/briefs/, docs/audits/) are synced from the meta branch by"
  print "Master Control and are never edited in place — see docs/AGENT_HANDOFF.md §1."
  exit 1
}
