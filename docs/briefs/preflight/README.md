# SQL pre-flights

The scripts Master Control ran before dispatching a brief, kept so that the
pre-flight recorded in the brief can be re-run by anyone with a PostgreSQL 16
at the schema the brief names (standing lesson L-3, widened by 015-REQ O-2:
every row of SQL a brief specifies is executed before dispatch, with one row
of every documented shape inserted and every negative control refused).

Every script opens a transaction and ends with `rollback`; the negative
controls sit in savepoints so one refusal does not abort the rest. Run with
`psql -v ON_ERROR_STOP=0 -f <script>` and read the `ERROR:` lines as the
expected answers the brief quotes. **These are not migrations.** A migration
is the file the Worker writes under `resources/migrations/`, numbered when the
branch is cut; a pre-flight is the evidence that its DDL ran once, before.

| Script | Brief | Expects a database at | Needs |
|---|---|---|---|
| [`0014-task-018.sql`](0014-task-018.sql), [`0014-task-018-negative-controls.sql`](0014-task-018-negative-controls.sql) | TASK-018 | `0013` | one organisation with one payment instruction |
| [`0015-task-017.sql`](0015-task-017.sql) | TASK-017 | `0013` | one organisation with one payment instruction and one actor |
| [`0016-task-019.sql`](0016-task-019.sql), [`0016-task-019-membership-control.sql`](0016-task-019-membership-control.sql) | TASK-019 | `0013` | an applied `settled` scheme response, its batch and a journal entry in one organisation (a UAT-006 run, or the capture database) |
| [`uat-007-task-020.sql`](uat-007-task-020.sql) | TASK-020 | `0013` | a UAT-007 run (a posted adjustment and a retry) |

The `0016` script assumes TASK-017's form of `role_known`; run after `0015`
in the same session, or accept the first `alter table actor_role` as the
only difference.
