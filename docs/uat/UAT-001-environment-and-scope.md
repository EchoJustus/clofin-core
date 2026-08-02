# UAT-001 — Environment and service scope

**Requirements:** NFR-001, NFR-005, and the scope statements in `README.md`
**Prerequisites:** Docker with Compose v2, GNU Make, a clone of this repository
**Estimated duration:** 10 minutes

## Purpose

Establish two things before any functional testing begins:

1. A reviewer can bring the whole stack up from a clean clone with one command.
2. The service states plainly what it is and is not — a payments system that
   overstates its own status is a governance problem before it is a technical
   one.

---

## Steps

### Step 1 — Clean start

```bash
cp .env.example .env
make up
```

**Expected:** PostgreSQL and the CloFin service both start. No manual
configuration, no credentials to obtain, no editing of any file.

**Record:** total elapsed time from `make up` to the service answering.
**Pass criterion:** under 5 minutes on a laptop (NFR-001).

---

### Step 2 — Liveness

```bash
make health
```

**Expected:**

```json
{"status":"ok","service":"clofin-core","uptimeSeconds":<n>}
```

**Pass criterion:** HTTP 200 and `status` is `ok`.

---

### Step 3 — Readiness and schema version

```bash
make ready
```

**Expected:** `status` is `ready`, `checks.database` is `ok`, and
`schemaVersion` is a four-digit migration version.

**Pass criterion:** the reported schema version matches the highest file in
`resources/migrations/`. This is the question asked first in any incident, so it
must be answerable without database access.

---

### Step 4 — The service states its scope

```bash
curl -s http://localhost:8080/ | python3 -m json.tool
```

**Expected:** the `disclaimer` field states that CloFin operates on synthetic
data only, is not connected to any bank, payment scheme or central bank, holds
no regulatory authorisation, and never processes real funds.

**Pass criterion:** all four statements present. **This step fails the release
if any is missing or softened.**

---

### Step 5 — Errors do not leak internals

```bash
curl -si http://localhost:8080/no-such-endpoint
```

**Expected:** HTTP 404, `content-type: application/problem+json`, and a body
with `type`, `title` and `status`. No stack trace, no file path, no SQL, no
library name.

**Pass criterion:** the response reveals nothing about the implementation
(NFR-005).

---

### Step 6 — Correlation

```bash
curl -si -H 'x-correlation-id: uat-001-step-6' http://localhost:8080/healthz | grep -i correlation
```

**Expected:** the response echoes `x-correlation-id: uat-001-step-6`.

Then, without supplying one:

```bash
curl -si http://localhost:8080/healthz | grep -i correlation
```

**Expected:** a generated identifier is present.

**Pass criterion:** every response carries a correlation id, whether or not the
caller supplied one — this is what makes a support conversation possible.

---

### Step 7 — Clean shutdown

```bash
make down
make health
```

**Expected:** `make down` completes without error; `make health` then fails to
connect.

**Pass criterion:** no orphaned containers (`docker ps` shows none for the
`clofin` project).

---

## Result

| Step | Result | Evidence | Notes |
|---|---|---|---|
| 1 Clean start | | | |
| 2 Liveness | | | |
| 3 Readiness | | | |
| 4 Scope statement | | | |
| 5 Error handling | | | |
| 6 Correlation | | | |
| 7 Shutdown | | | |

**Overall:** Pass / Fail
**Executed by:** ____________ **Date:** ____________ **Build:** ____________
