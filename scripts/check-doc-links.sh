#!/bin/sh
# Verify that every relative Markdown link in the repository resolves.
#
# Documentation is part of the deliverable in CloFin: an ADR referenced from
# ARCHITECTURE.md that does not exist is a broken contract, not a typo. This
# runs in CI as `make docs-check`.
#
# POSIX sh with no dependencies beyond find, grep and sed, so it behaves the
# same on macOS, Linux and Git Bash on Windows.

set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

broken_list=$(mktemp)
trap 'rm -f "$broken_list"' EXIT

checked=0

for file in $(find . -name '*.md' -not -path './node_modules/*' -not -path './.git/*' | sort); do
  dir=$(dirname "$file")
  checked=$((checked + 1))

  # Extract the target of every markdown link, then discard external links,
  # bare anchors and mailto:. Lines inside fenced code blocks are skipped
  # first: a fence quotes text — an audit deliverable quoting a ROADMAP line
  # verbatim, evidence that must not be edited to please a checker — and a
  # link inside a quotation is part of the quoted bytes, not a link this
  # document makes (added 2026-09-06 when FEEDBACK-REL-ref-2 was ingested).
  targets=$(awk '/^```/ { fenced = !fenced; next } !fenced' "$file" 2>/dev/null \
            | grep -o '](\([^)]*\))' \
            | sed 's/^](//; s/)$//' \
            | grep -v '^https\?://' \
            | grep -v '^#' \
            | grep -v '^mailto:' || true)

  for target in $targets; do
    # Strip any anchor fragment: docs/PRD.md#goals -> docs/PRD.md
    path=${target%%#*}
    [ -z "$path" ] && continue
    if [ ! -e "$dir/$path" ]; then
      echo "BROKEN  $file -> $target" >> "$broken_list"
    fi
  done
done

if [ -s "$broken_list" ]; then
  cat "$broken_list"
  echo ""
  echo "$(wc -l < "$broken_list" | tr -d ' ') broken documentation link(s)."
  exit 1
fi

echo "Documentation links OK ($checked markdown files checked)."
