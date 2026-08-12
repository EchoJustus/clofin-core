#!/bin/sh
# Verify that CloFin's status documents agree with each other.
#
# On 2026-08-05 `main` — the branch outsiders read — said four controls were
# "designed, not built" while `COMPLIANCE.md` on the same branch showed all four
# enforced, and listed merged, audited, closed increments as not started. A
# reader concluded two increments existed where six did. That is standing lesson
# **L-15**, and it survived two milestone audits and a release audit, because
# every guard and every reviewing instinct in this project points at
# *over*statement and this was an *under*statement.
#
# This script points at both. It reports disagreement between documents; it
# never edits either. A script that "fixed" the ROADMAP would make the ROADMAP
# agree with COMPLIANCE while both were wrong.
#
# What it asserts:
#
#   1. Every control the ROADMAP's controls prose speaks about has the status
#      `COMPLIANCE.md` §2 gives it — and names a control COMPLIANCE defines.
#   2. No increment the ROADMAP shows as not started (📋 / 💭) has a brief
#      whose status is `CLOSED` or `IMPLEMENTED`.
#   3. Where the ROADMAP restates a brief's own status, the two words match.
#   4. The set of briefs the ROADMAP's global-state table references is the set
#      the briefs backlog lists — in both directions, so neither a brief the
#      ROADMAP forgot nor one it invented passes.
#
# Which copies it reads: the ones in the tree it is run from. `docs/briefs/` and
# `docs/audits/` on `main` are Master-Control-synced snapshots of `meta`; this
# compares `main` against itself and never reaches across branches. A
# cross-branch check would fail in a shallow CI checkout and would make the
# build depend on a branch the pull request does not contain. A brief therefore
# may read `IN PROGRESS` on `meta` and `READY` here: that is expected drift
# between a live copy and a snapshot, and is not what this script looks for.
#
# It fails closed. A document whose structure has moved — a controls paragraph
# that has been renamed, a status vocabulary that has gone — is reported as an
# error rather than skipped, because a guard that silently checks nothing is
# indistinguishable, on a green build, from a guard that holds. That is standing
# lesson **L-6**.
#
# POSIX sh and awk, no dependencies, the same shape as check-doc-links.sh.
#
# Usage: check-doc-consistency.sh [tree]     (default: the repository root)

set -eu

if [ "$#" -ge 1 ]; then
  root=$1
else
  root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fi

roadmap="$root/docs/ROADMAP.md"
compliance="$root/docs/COMPLIANCE.md"
backlog="$root/docs/briefs/README.md"

for required in "$roadmap" "$compliance" "$backlog"; do
  if [ ! -f "$required" ]; then
    echo "MISSING $required — the consistency check has nothing to compare." >&2
    exit 1
  fi
done

briefs=$(find "$root/docs/briefs" -name '*-TASK-*.md' | sort)

# Sorted, explicit file order so the report reads the same on every machine.
# shellcheck disable=SC2086
LC_ALL=C awk -v root="$root" -f "$(dirname -- "$0")/check-doc-consistency.awk" \
  "$compliance" "$backlog" $briefs "$roadmap"
