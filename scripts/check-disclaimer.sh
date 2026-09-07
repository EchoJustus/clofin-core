#!/bin/sh
# Verify that every surface restating CloFin's scope carries the canonical
# sentence, verbatim.
#
# The `ref-2` release audit found the same claim written three ways. `GET /`
# states four negations — synthetic data only, no institutional connection, no
# regulatory authorisation, never real funds. `make help` stated three and
# omitted the real-funds clause (finding 2B-007). The `ref-1` release body
# states four and omits the regulatory one (2B-008). Each was individually
# reasonable prose, and collectively a set of surfaces that disagreed about
# where the boundary is — which is standing lesson L-14 with the quantifier
# taken out of one sentence and left in another.
#
# So there is one sentence, in `resources/disclaimer.txt`, and this compares
# every surface with it:
#
#   1. `make help` prints it. (The Makefile `cat`s the file, so this is really
#      a check that the target still does — which is the thing that can rot.)
#   2. Every `docs/releases/*.annotation.txt` contains it verbatim, except the
#      tags `docs/releases/README.md` lists as historical, each of which must
#      carry a stated reason.
#
# It does **not** check the published GitHub release bodies: that needs the
# network, and `make check-release-annotation` is the target that does it and
# is deliberately outside `verify`. This one is offline and runs in `verify`.
#
# It fails closed. A missing sentence file, an unreadable README, a historical
# entry with no reason, or a `make help` that cannot be run is an error rather
# than a skip: a guard that silently checks nothing is indistinguishable, on a
# green build, from a guard that holds (standing lesson L-6).
#
# POSIX sh and awk, no dependencies, the same shape as check-doc-links.sh.
#
# Usage: check-disclaimer.sh [tree]     (default: the repository root)

set -eu

if [ $# -ge 1 ]; then
  repo_root=$(CDPATH= cd -- "$1" && pwd)
else
  repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fi
cd "$repo_root"

sentence_file="resources/disclaimer.txt"
releases_readme="docs/releases/README.md"

fail() { echo "$1" >&2; exit 1; }

[ -f "$sentence_file" ] || fail "check-disclaimer: $sentence_file is missing — it is the one place the sentence lives."

# The sentence, with the file's trailing newline removed. `printf %s` rather
# than `echo`, so a sentence that happened to begin with `-` or contain `\n`
# reaches grep unchanged.
sentence=$(awk 'NR == 1 { printf "%s", $0 }' "$sentence_file")
[ -n "$sentence" ] || fail "check-disclaimer: $sentence_file is empty."

# A second line would be a second sentence, and every consumer here treats the
# file as one line.
lines=$(awk 'NF { n++ } END { print n + 0 }' "$sentence_file")
[ "$lines" = "1" ] || fail "check-disclaimer: $sentence_file must hold exactly one non-blank line; it holds $lines."

contains() {
  # `grep -F` so the sentence is compared as bytes rather than as a pattern.
  printf '%s' "$1" | grep -qF -- "$sentence"
}

# ---------------------------------------------------------------------------
# 1. `make help`
# ---------------------------------------------------------------------------

help_output=$(make -C "$repo_root" help 2>/dev/null) \
  || fail "check-disclaimer: \`make help\` could not be run in $repo_root."

if ! contains "$help_output"; then
  echo "check-disclaimer: \`make help\` does not print the canonical sentence." >&2
  echo "" >&2
  echo "  expected, verbatim:" >&2
  echo "    $sentence" >&2
  echo "" >&2
  echo "  \`make help\` printed:" >&2
  printf '%s\n' "$help_output" | sed 's/^/    /' >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 2. Release annotations
# ---------------------------------------------------------------------------

# The historical exemptions, read from the README's own table rather than from
# a list in this script: the reason a tag is exempt belongs beside the tag, in
# the document a reader opens, and a second copy here would be a second answer.
#
# The table is the one under `### Historical exemptions`, and a row is
# `| `ref-n` | reason |`. A row with an empty reason is an error: an exemption
# nobody has to justify is not an exemption, it is a hole.
historical=""
if [ -f "$releases_readme" ]; then
  historical=$(awk '
    /^### Historical exemptions/ { in_section = 1; next }
    in_section && /^#/           { in_section = 0 }
    in_section && /^\| *`/ {
      line = $0
      sub(/^\| *`/, "", line)
      tag = line
      sub(/`.*/, "", tag)
      rest = line
      sub(/^[^|]*\| */, "", rest)
      sub(/ *\|[^|]*$/, "", rest)
      gsub(/^ +| +$/, "", rest)
      if (tag == "") next
      if (rest == "") { print "MISSING-REASON " tag; next }
      print tag
    }
  ' "$releases_readme")
fi

missing_reason=$(printf '%s\n' "$historical" | grep '^MISSING-REASON ' || true)
if [ -n "$missing_reason" ]; then
  echo "check-disclaimer: a historical exemption in $releases_readme carries no reason:" >&2
  printf '%s\n' "$missing_reason" | sed 's/^MISSING-REASON /    /' >&2
  exit 1
fi

is_historical() {
  printf '%s\n' "$historical" | grep -qx -- "$1"
}

failed=0
checked=0
exempt=0

for file in docs/releases/*.annotation.txt; do
  # No annotation files at all is a legitimate state — `ref-2`'s is written at
  # tag time, and this check must pass before it exists.
  [ -e "$file" ] || continue

  tag=$(basename "$file" .annotation.txt)

  if is_historical "$tag"; then
    exempt=$((exempt + 1))
    continue
  fi

  checked=$((checked + 1))
  if ! contains "$(cat "$file")"; then
    echo "check-disclaimer: $file does not carry the canonical sentence." >&2
    echo "                  Add it verbatim, or record \`$tag\` under" >&2
    echo "                  '### Historical exemptions' in $releases_readme with a reason." >&2
    failed=$((failed + 1))
  fi
done

[ "$failed" -eq 0 ] || exit 1

echo "Disclaimer OK (make help, $checked release annotation(s) checked, $exempt historical)."
