# ADR-0004: Minimal runtime dependency footprint

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead

## Context

In a regulated payments environment, third-party code in the runtime path is a
control concern, not just an engineering preference. Institutions maintain
software bills of material, track transitive vulnerabilities, and require a named
owner for every component that can touch a payment instruction. The practical
question at review is not "is this library good?" but "who reviews it, how fast
do they patch, and what happens if it is abandoned?"

The Clojure ecosystem makes it easy to accumulate a large transitive graph very
quickly — a routing library, a validation library, a JSON library and a database
wrapper can pull in several dozen artefacts between them, most of which nobody on
the team has read.

There is a second, non-obvious benefit. Constraining the dependency set forces
the domain boundaries to be explicit, because there is no framework to hide them.

## Decision

The runtime dependency set is kept deliberately small, and every runtime
dependency must be justified. A dependency is admitted only if it is either
maintained by the Clojure core team, or is established JVM infrastructure with a
clear security-response process.

The current runtime set:

| Dependency | Role | Justification |
|---|---|---|
| `org.clojure/clojure` | Language | — |
| `org.clojure/data.json` | JSON codec | Clojure core team; no transitive dependencies. |
| `org.clojure/tools.logging` | Logging facade | Clojure core team; thin facade over SLF4J. |
| `org.eclipse.jetty/jetty-server` | HTTP transport | Eclipse Foundation; the JVM's standard embedded server, with a published security process. |
| `org.postgresql/postgresql` | JDBC driver | The reference PostgreSQL driver. |
| `com.zaxxer/HikariCP` | Connection pool | Correct pool semantics under contention are not something to hand-roll. |
| `ch.qos.logback/logback-classic` | Logging backend | Standard SLF4J backend. |

Adding a runtime dependency requires a new ADR recording what was gained and what
transitive graph came with it. Test-scope and development-scope dependencies are
not subject to this rule beyond ordinary review.

Consequences for the code: CloFin writes its own thin router, its own
Ring-compatible Jetty adapter ([ADR-0010](0010-thin-ring-compatible-http-adapter.md)),
its own SQL helpers over `java.sql`, and its own migration runner
([ADR-0009](0009-forward-only-sql-migrations.md)). Each is small, single-purpose,
and fully covered by tests.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Conventional Clojure web stack** (Ring, reitit, next.jdbc, HoneySQL, Malli, Integrant) | The idiomatic and, for a commercial team under delivery pressure, probably the *correct* choice: all are well-maintained and well-understood. Rejected here because the components CloFin actually needs from them amount to a few hundred lines, and the SBOM cost is real in the regulated setting the project is modelling. This is a genuine trade-off, not a claim that these libraries are deficient. |
| **A full framework** (Spring Boot via interop, Pedestal) | Substantial transitive surface, and the framework's opinions would obscure the domain boundaries this project exists to make visible. |
| **No dependencies at all** — JDK-only, including `jdk.httpserver` | Tempting, and briefly considered. Rejected because `jdk.httpserver` is not intended for production traffic and hand-rolling a connection pool is exactly the sort of thing this ADR argues against. The line is drawn at infrastructure with a security-response process. |

## Consequences

**Positive**
- A short, auditable SBOM in which every entry has a named upstream and a security process.
- Fewer version conflicts and less upgrade churn.
- No framework magic between an HTTP request and a domain function.

**Negative / accepted cost**
- CloFin maintains code it could have consumed — a router, an HTTP adapter, SQL helpers, a migration runner. That is real, ongoing cost, and the code must be tested to the same standard as the domain.
- Contributors familiar with the conventional stack face a small ramp.
- Some ergonomics are given up: no schema-driven coercion, no data-driven route metadata.

**Risks and how they are mitigated**
- *Risk:* hand-written infrastructure has bugs that a mature library would not. *Mitigation:* each component is kept under a few hundred lines, has direct unit tests, and stays behind a boundary that could be swapped for the mature library without touching the domain. The Ring-shaped request map is deliberately chosen for this reason.
- *Risk:* the rule becomes dogma and blocks a genuinely needed dependency. *Mitigation:* the rule is "write an ADR", not "never".

## Verification

`deps.edn` carries the policy as a comment. Any pull request adding a `:deps`
entry without a corresponding ADR is rejected at review.
