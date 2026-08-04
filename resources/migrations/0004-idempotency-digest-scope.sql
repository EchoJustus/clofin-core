-- Correct the documented scope of `idempotency_key.request_digest`.
--
-- The digest covers the canonical *request* — its method, its path and its
-- body — not the body alone. 0003's comments describe the narrower scope this
-- project started with, which was found during increment 3 to let one key
-- replay across two different instructions' submission endpoints: identical
-- bodies, different paths, so the second caller received the first's stored
-- response and its instruction was never submitted while the operator saw
-- success. See docs/ADR/0013-canonical-request-digest-for-idempotency.md,
-- amendment 1.
--
-- This is a migration rather than an edit to 0003 because 0003 has been
-- applied, and an applied migration is immutable — the runner verifies
-- checksums and refuses to start when one has changed
-- (docs/ADR/0009-forward-only-sql-migrations.md). A comment is documentation an
-- auditor reads out of the database itself, so a stale one describing a
-- *control* is worth a migration to correct.
--
-- No schema change. Comments only.

comment on column idempotency_key.request_digest is
  'SHA-256 of the canonical serialisation of the request — its method, its '
  'path and its body, with object keys sorted and no insignificant whitespace. '
  'Canonical so that a semantically identical retry is not mistaken for a '
  'conflicting one; method and path included so that two requests a caller '
  'means differently never digest alike. See '
  'docs/ADR/0013-canonical-request-digest-for-idempotency.md.';

comment on table idempotency_key is
  'One row per (organisation, idempotency key). The row is written in the same '
  'transaction as the effect it protects: a crash between the two would leave '
  'a payment made with no record that it was. The composite primary key is the '
  'replay guarantee (COMPLIANCE C-06) — two concurrent retries contend on it, '
  'and the loser returns what the winner stored.';
