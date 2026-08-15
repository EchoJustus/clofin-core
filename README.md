# CloFin — Enterprise Payments & Reconciliation Core

CloFin is an open-source reference implementation of the **payment initiation,
ledger, settlement and reconciliation** capabilities that a regulated enterprise
or financial institution needs in order to move money on behalf of corporate
clients — modelled end to end, from the product requirements through to running
code and acceptance tests.

It exists to answer a question that is hard to answer with a CV alone:

> *Can this person specify, decompose and evidence a regulated payments product —
> the controls, the exception paths, the audit trail — not just the happy path?*

---

## ⚠️ Scope and honest limitations

Please read this before evaluating the project.

| | |
|---|---|
| **Money** | CloFin **never handles real funds.** Every amount in this repository is synthetic. |
| **Institutional connectivity** | All external interfaces (clearing scheme, sanctions screening, bank statements) are **simulated adapters**. CloFin is **not** connected to any bank, payment scheme, or central bank, and has never been. |
| **Regulatory status** | CloFin holds **no licence, authorisation or regulatory approval** of any kind, and is not a submission to any regulator. Compliance material in `docs/` is a **modelling exercise** demonstrating control design — not a compliance attestation. |
| **Data** | All fixtures are generated. No production data, no customer data, no client data. |
| **Maturity** | Early. See [`docs/ROADMAP.md`](docs/ROADMAP.md) for what is built versus planned. |

---

## What it demonstrates

CloFin is built as a product, not as a code sample. Each slice ships with the
artefacts a product or business-analysis owner would actually be accountable for.

**Product & analysis**
- A product requirements document with measurable outcomes — [`docs/PRD.md`](docs/PRD.md)
- A domain model and ubiquitous language — [`docs/DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md)
- Architecture decision records with the alternatives considered and rejected — [`docs/ADR/`](docs/ADR/)
- A control-to-requirement mapping — [`docs/COMPLIANCE.md`](docs/COMPLIANCE.md)
- Acceptance criteria and UAT scripts per feature slice — [`docs/uat/`](docs/uat/)
- Diagrams **generated from** the code and tables they depict, and failed in CI
  when they drift — [`docs/diagrams/`](docs/diagrams/README.md)
- A [replay walkthrough of captured output at tag `ref-1`](https://echojustus.github.io/clofin-trace/) —
  static pages, every figure traced to the fixture it was captured from, with
  the source commit and that tag's release-audit coverage stated in frame

**Engineering**
- A double-entry ledger whose balance invariant is enforced by property-based tests
- Payment lifecycle with maker–checker approval, idempotent submission, and
  compensating reversals rather than deletes
- An append-only audit trail covering every state transition
- One-command local execution on any machine — `make up`

**Domain coverage (target state)**

| Capability | Concerns modelled |
|---|---|
| Payment initiation | Instruction capture, validation, purpose codes, value dating |
| Authorisation | Maker–checker, threshold-based dual authorisation, segregation of duties |
| Ledger | Double-entry postings, immutable journal, multi-currency, balance derivation |
| Clearing & settlement | Batch construction, simulated scheme adapter, settlement finality |
| Exceptions | Refunds, reversals, returns, idempotency, compensating actions |
| Reconciliation | Statement ingestion, matching rules, breaks, ageing, write-off approval |
| Financial crime | Sanctions screening hooks, rule-based fraud monitoring, case handling |
| Governance | RBAC, permission model, append-only audit trail, evidence extraction |

---

## Quick start

**Requirements:** Docker (with Compose v2) and GNU Make. Nothing else — the
Clojure toolchain and PostgreSQL run inside containers.

```bash
cp .env.example .env      # local defaults; safe to use as-is
make up                   # start PostgreSQL + the CloFin service
make health               # -> {"status":"ok", ...}
make logs                 # follow service logs
make down                 # stop everything
```

**Developing with a local toolchain** (Clojure CLI + JDK 21 installed, database
in Docker):

```bash
make db-up                # PostgreSQL only
make migrate              # apply schema migrations
make test                 # unit + property tests
make test-it              # + database integration tests
make run                  # run the service on the host
make repl                 # development REPL
```

`make help` lists every target.

---

## Repository map

```
├── src/clofin/            Clojure service
│   ├── money.clj            Money value type — integer minor units, never floats
│   ├── ledger/              Double-entry ledger core (pure functions)
│   ├── db/                  Connection pool, SQL helpers, migration runner
│   ├── http/                Jetty transport, router, middleware
│   └── api/                 HTTP resource handlers
├── test/clofin/           Unit and property-based tests
├── resources/migrations/  Forward-only SQL migrations
├── docs/
│   ├── PRD.md               Product requirements
│   ├── DOMAIN_MODEL.md      Entities, states, ubiquitous language
│   ├── COMPLIANCE.md        Control mapping
│   ├── ROADMAP.md           Delivery increments
│   ├── ADR/                 Architecture decision records
│   ├── briefs/              Self-contained implementation briefs
│   ├── diagrams/            Generated — never hand-drawn (ADR-0020 RULE 1)
│   └── uat/                 Acceptance criteria and UAT scripts
├── tools/clofin/tools/    Diagram generator; not on the runtime classpath
├── scripts/               Documentation guards run by `make verify`
├── api/openapi.yaml       API contract
├── docker-compose.yml     Local stack
└── Makefile               Unified entrypoint
```

---

## Technology and why

| Choice | Reason |
|---|---|
| **Clojure** for the ledger and rules core | Immutable values and pure functions match a domain where an entry, once posted, must never be mutated. Property-based testing of ledger invariants is idiomatic rather than exotic. See [ADR-0002](docs/ADR/0002-clojure-for-the-ledger-core.md). |
| **PostgreSQL** as system of record | Serialisable transactions, exclusion and check constraints, and durable append-only tables. Correctness constraints live in the database, not only in application code. See [ADR-0006](docs/ADR/0006-postgresql-as-system-of-record.md). |
| **Docker Compose + Make** | The stack must come up identically on a laptop, a Mac mini, or CI, with one command. See [ADR-0005](docs/ADR/0005-hybrid-local-and-cloud-execution.md). |
| **Deliberately small dependency set** | Supply-chain surface is a real control concern in regulated payments. Every runtime dependency needs an ADR. See [ADR-0004](docs/ADR/0004-minimal-dependency-footprint.md). |

Full reasoning: [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## About this project

CloFin is built and maintained by an engineering and technology consultant with
roughly four years' experience in digital-programme delivery at a multinational
engineering and technology consultancy, working with public-sector and
regulated-industry clients on feasibility studies, requirements analysis,
investment appraisal, data architecture, compliance review, vendor coordination,
project assurance and acceptance review.

The project is a deliberate, public demonstration of that capability applied to
regulated payments: the same analysis and delivery discipline, expressed as a
product that anyone can read, run and challenge.

Corrections and critique are welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Licence

[Eclipse Public License 2.0](LICENSE).
