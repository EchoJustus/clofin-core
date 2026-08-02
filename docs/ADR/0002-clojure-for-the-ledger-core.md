# ADR-0002: Clojure for the ledger and rules core

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead

## Context

The ledger is the part of CloFin where a defect is unrecoverable. Once an entry
is posted and reported, it cannot be quietly corrected — it must be reversed,
visibly. The implementation language for that core should make accidental
mutation of a posted entry awkward, make invariants cheap to express, and make
generative testing of those invariants idiomatic rather than exotic.

Secondary requirements: a mature JDBC and HTTP ecosystem, an ordinary deployment
story (a JVM in a container), and a REPL workflow that supports exploring a
domain model while it is still being specified.

## Decision

Clojure on the JVM (Java 21) for the ledger, the payment rules, and the service
that hosts them.

The domain layer is written as pure functions over immutable maps. It does not
open connections, read the clock, or generate identifiers — those are supplied by
the caller. Java interoperability is used directly where a mature JVM library is
the right answer (JDBC, Jetty, connection pooling) rather than wrapping it.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Java / Kotlin** | Perfectly capable, and the obvious default for a bank. Rejected for this project because immutability and value equality require deliberate effort, and property-based testing of a ledger invariant is materially more ceremony. The tie-breaker is that CloFin exists partly to demonstrate deliberate technical judgement, and defaulting is not judgement. |
| **TypeScript / Node** | Numeric handling is the wrong starting point for money: `number` is IEEE-754 double, and `BigInt` interoperates poorly with JSON and most database drivers. Fighting the platform on the one thing that must never be wrong is a bad trade. |
| **Go** | Strong operational story, weak fit for the modelling work here. Expressing and generatively testing algebraic invariants over a journal is verbose, and the absence of immutable collections pushes defensive copying into the domain. |
| **Haskell / F#** | Better type-level guarantees than Clojure. Rejected on practical grounds: a much smaller pool of reviewers can read the result, which defeats a project whose purpose is to be publicly reviewable. |
| **Rust** | Excellent correctness properties; disproportionate cost for a domain dominated by I/O and business rules rather than by performance or memory control. |

## Consequences

**Positive**
- Posted entries are immutable values; "accidentally mutating history" is not an available mistake.
- `clojure.test.check` makes invariants such as *every entry sums to zero per currency* testable as universal claims over generated data, not as a handful of examples.
- Pure domain functions can be replayed against historical inputs to reproduce a past decision — what an auditor actually asks for.
- Full access to the JVM ecosystem: PostgreSQL JDBC, Jetty, HikariCP.

**Negative / accepted cost**
- Dynamic typing means no compiler-enforced schema at boundaries. Mitigated by validating every external input at the HTTP edge and by property tests over domain constructors.
- Smaller hiring pool than Java; irrelevant for this project, material for a real institution. Contained by keeping Clojure to the core and using conventional technology at the edges.
- JVM start-up time (~1–2s) is noticeable in tight test loops.

**Risks and how they are mitigated**
- *Risk:* a reviewer unfamiliar with Clojure cannot assess the ledger. *Mitigation:* domain namespaces are documented with worked examples, and the API contract and acceptance criteria are language-neutral.

## Verification

The ledger namespaces (`clofin.money`, `clofin.ledger.*`) import no I/O
namespaces. Ledger invariants are covered by property tests that run in
`make test` without a database or a server.
