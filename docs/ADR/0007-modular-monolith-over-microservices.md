# ADR-0007: Modular monolith over microservices

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead / product owner

## Context

CloFin models seven bounded contexts — ledger, payments, authorisation,
settlement, reconciliation, compliance, audit. A microservice per context is the
reflexive choice in payments architecture, and a diagram of it looks impressive.

The relevant question is what the split would buy. CloFin's most important
operations are transactionally coupled by nature: releasing a payment writes a
journal entry, records an audit event, and advances the instruction's state.
Those three must succeed or fail together. Split across services, that atomicity
becomes a saga with compensating actions — real complexity, in the exact place
where correctness matters most, bought before any scaling or team-autonomy
pressure exists to justify it.

## Decision

CloFin is a **modular monolith**: one deployable process, with contexts separated
by namespace boundaries and explicit interfaces rather than by network hops.

The rules that make the modularity real rather than aspirational:

1. Each context owns a namespace root (`clofin.ledger`, `clofin.payments`, …) and
   its own tables. No context reads another's tables directly.
2. Cross-context access goes through a published interface namespace, never
   through internals.
3. The dependency graph is acyclic and directed: `ledger` depends on nothing;
   `payments` depends on `ledger` and `authz`; `settlement` and `recon` depend on
   `ledger`; nothing depends on `http` or `api`.
4. External systems sit behind protocols, so a simulated adapter and a future
   real adapter are interchangeable.
5. Domain code never touches the database directly — persistence is a separate
   layer per context.

Those rules are what a future extraction would need anyway. Following them now
keeps the option open at low cost.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Microservice per bounded context** | Turns the ledger-plus-audit-plus-state-change write into a distributed transaction, and adds service discovery, network partition handling, distributed tracing and per-service deployment — none of which the project's actual drivers call for. It would also make the project harder to run for a reviewer, which is a stated goal. |
| **Event-driven services over a message bus** | Attractive for settlement and reconciliation, which are genuinely asynchronous. Rejected as the *starting* architecture because eventual consistency in balance reads makes "can this payment be released?" a hard question. The seam is preserved: settlement already consumes ledger events through an interface, so it can be moved behind a bus without changing the domain. |
| **Serverless functions** | Cold-start latency against a connection-pooled relational store, no natural home for long-running settlement batches, and vendor coupling that contradicts [ADR-0005](0005-hybrid-local-and-cloud-execution.md). |
| **A single unstructured application** | The genuine risk of choosing a monolith, and the reason rules 1–5 exist rather than "we'll be careful". |

## Consequences

**Positive**
- Payment release is one database transaction: journal entry, audit event and state change commit atomically. No saga, no compensation, no partial-failure window.
- The entire system runs with `docker compose up`, so a reviewer can evaluate it in minutes.
- Refactoring across context boundaries is a compiler-and-test problem, not a coordinated multi-repository release.
- Integration tests exercise real cross-context behaviour without service mocks.

**Negative / accepted cost**
- Contexts scale together; a settlement batch and the payment API share a process.
- No independent deployment, so one team would eventually contend on releases.
- Module boundaries are enforced by convention and review rather than by the compiler. This is the main cost, and it is the one to watch.

**Risks and how they are mitigated**
- *Risk:* boundaries erode until extraction is impossible. *Mitigation:* the dependency rule is stated here, checked at review, and made visible by keeping each context's persistence code in its own namespace.
- *Risk:* the choice is read as naivety about scale. *Mitigation:* this ADR states the extraction path explicitly — settlement and reconciliation are the first candidates, because they are already asynchronous and read-mostly against the ledger.

## Verification

The namespace dependency rule is checked at review; no `clofin.ledger.*`
namespace may require a `clofin.payments.*`, `clofin.http.*` or `clofin.db.*`
namespace. Ledger tests run without a database or a server, which fails
immediately if that rule is broken.
