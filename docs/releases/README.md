# Release annotations

One file per release tag, holding **the tag's annotation text byte for byte**.

## Why these files exist

The capture harness (`make capture-trace`) stamps every bundle it writes with
the source commit, the tag, and **that tag's recorded release-audit coverage**
— because `clofin-trace` renders the coverage rather than asserting one, and a
coverage sentence typed onto a page is a claim somebody must remember to
update. A value read from the artifact cannot drift from it; a value retyped
by a human will.

The protocol says where that text lives:
[`docs/audits/README.md`](../audits/README.md) on the `meta` branch — *"`ref-<n>`
— annotated, with the date and RC SHA in the tag message"* — and
[`docs/ROADMAP.md`](../ROADMAP.md) says of `ref-1`'s partial audit that *"the
tag annotation says so"*.

`refs/tags/ref-1` is a **lightweight** tag. It carries no annotation:

```
$ git cat-file -t ref-1
commit
$ git ls-remote origin 'refs/tags/*'
5c7b4badced5e807e1022fce44cbcad38c6d2095	refs/tags/ref-1
```

An annotated tag answers `tag` to the first command and produces a second,
peeled `refs/tags/ref-1^{}` line in the second. The text both documents
describe does exist, in full — as the body of the **GitHub release** published
on that tag. This directory is where that text is mirrored so the harness can
read it offline, from a reviewed artifact, rather than from somebody's memory.
Recorded as objection **O-1** in
[`docs/audits/007-REQ-clofin-trace.md`](../audits/007-REQ-clofin-trace.md).

## What the harness does with them

`clofin.tools.capture.provenance/resolve-coverage` prefers the tag's own
annotation and falls back to the mirror:

| Source | Used when | Recorded in the bundle as |
|---|---|---|
| The annotated tag's message | `git cat-file -t <tag>` is `tag` and the message has a coverage paragraph | `git-tag-annotation` |
| `docs/releases/<tag>.annotation.txt` | it does not | `release-annotation-file` |

Which source was used is stamped into every bundle and rendered on the
walkthrough, so a reader is never left to assume the stronger one. If `ref-1`
is ever re-tagged as annotated, or `ref-2` is tagged as the protocol
describes, the harness takes the stronger source with nothing to change here.

**The harness refuses to write a bundle when neither source carries a coverage
paragraph.** That is the point: a bundle with no coverage would render a tag
and a SHA with a blank where the qualifier goes, and the reader would supply
the missing word themselves — and the word they supply is "audited".

## The format

The file is the release body, exactly, with `CRLF` line endings normalised to
`LF` and nothing else changed. One paragraph must begin with `RELEASE AUDIT:`;
that paragraph is what the harness quotes, whole, and the word between the
heading and the first full stop is the short label the provenance block shows
beside the tag and the SHA.

## Keeping them honest

```sh
make check-release-annotation
```

re-reads the published release for every tag mirrored here and fails on any
difference. It is deliberately not part of `make verify`: it needs the
network, and a verification that fails offline is one people learn to skip.

**Update a mirror from the release body, never the other way round.** These
files are copies. The release is the artifact.
