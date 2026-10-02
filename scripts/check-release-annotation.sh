#!/bin/sh
# Compare each committed release-annotation mirror with the published release.
#
# `docs/releases/<tag>.annotation.txt` is a byte-for-byte copy of the GitHub
# release body published on `<tag>`. The capture harness reads a tag's
# release-audit coverage from the tag's own annotation when it has one, and
# from this mirror when it does not — which is the case for `ref-1`, a
# lightweight tag whose annotation text lives in the release instead (007-REQ
# objection O-1).
#
# A mirror is a second copy of a claim, and second copies drift. This is the
# comparison that stops it. It is deliberately **not** part of `make verify`:
# it needs the network, and a check that fails on an aeroplane is a check
# people learn to skip.
#
#   make check-release-annotation
#
# Set GITHUB_TOKEN if the repository's API rate limit is being hit; the
# comparison itself needs no authentication.
#
# POSIX sh, needing only curl and sed. `jq` is used when present and a small
# inline extractor is used when it is not, so this runs on a bare CI image.

set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

api="https://api.github.com/repos/EchoJustus/clofin-core/releases/tags"
dir="docs/releases"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

checked=0
failed=0

for mirror in "$dir"/*.annotation.txt; do
  [ -e "$mirror" ] || continue
  tag=$(basename "$mirror" .annotation.txt)
  checked=$((checked + 1))

  if [ -n "${GITHUB_TOKEN:-}" ]; then
    ok=$(curl -sS -f -H "accept: application/vnd.github+json" \
              -H "authorization: Bearer ${GITHUB_TOKEN}" \
              "$api/$tag" -o "$work/release.json" && echo yes || echo no)
  else
    ok=$(curl -sS -f -H "accept: application/vnd.github+json" \
              "$api/$tag" -o "$work/release.json" && echo yes || echo no)
  fi

  if [ "$ok" != "yes" ]; then
    echo "UNREACHABLE  $tag — could not read the published release."
    failed=$((failed + 1))
    continue
  fi

  if command -v jq >/dev/null 2>&1; then
    jq -r '.body' "$work/release.json" | sed 's/\r$//' > "$work/published.txt"
  else
    # The body is a JSON string on one line of the response; decode the two
    # escapes a release body can contain and normalise line endings.
    sed -n 's/.*"body":"\(.*\)","draft".*/\1/p' "$work/release.json" \
      | sed 's/\\r//g; s/\\n/\
/g; s/\\"/"/g; s/\\\\/\\/g' > "$work/published.txt"
  fi

  # Both sides are compared modulo trailing blank lines: every line of content
  # must match byte for byte, and each side ends with exactly one newline. The
  # earlier version added a newline "because the API body does not carry one"
  # — true of a body typed into the web form, which strips trailing whitespace
  # (`ref-1`), and false of a body sent from a file with `gh release create
  # --notes-file`, which keeps the file's final newline (`ref-2`); `jq -r` then
  # adds another, and a correct mirror reported DRIFT over an empty last line.
  # A universal stated over one of the two ways a release is made (L-14), found
  # the first time the other way was used (2026-10-02).
  trim_trailing_blank_lines() {
    awk '{ line[NR] = $0; if ($0 != "") last = NR }
         END { for (i = 1; i <= last; i++) print line[i] }' "$1"
  }
  trim_trailing_blank_lines "$mirror" > "$work/mirror.txt"
  trim_trailing_blank_lines "$work/published.txt" > "$work/remote.txt"

  if diff -u "$work/mirror.txt" "$work/remote.txt" > "$work/diff.txt" 2>&1; then
    echo "OK           $tag — mirror matches the published release body."
  else
    echo "DRIFT        $tag — $mirror no longer matches the published release:"
    cat "$work/diff.txt"
    failed=$((failed + 1))
  fi
done

if [ "$checked" -eq 0 ]; then
  echo "No release annotations to check under $dir/."
  exit 0
fi

if [ "$failed" -gt 0 ]; then
  echo ""
  echo "$failed of $checked release annotation(s) disagree with the published release."
  echo "Update the mirror from the release body — never the other way round."
  exit 1
fi

echo ""
echo "$checked release annotation(s) match their published release."
