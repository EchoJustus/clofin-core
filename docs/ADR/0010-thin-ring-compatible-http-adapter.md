# ADR-0010: Thin Ring-compatible HTTP adapter

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead

## Context

CloFin needs an HTTP layer, but the HTTP layer is not where the interesting part
of the product lives. Two properties matter more than features:

1. **API handlers must be testable without a socket.** Acceptance criteria are
   written per endpoint; running them should not require binding a port.
2. **The transport must be replaceable.** [ADR-0004](0004-minimal-dependency-footprint.md)
   commits CloFin to a small dependency set, but that commitment is only
   defensible if reversing it is cheap. If a hand-written HTTP layer proves
   inadequate, swapping in the conventional Clojure stack must be a
   contained change, not a rewrite.

Ring — the de facto Clojure HTTP specification — already defines the interface
that gives both properties: a handler is a function from a request map to a
response map.

## Decision

CloFin implements a thin adapter over Jetty 12 that converts Jetty's
`Request`/`Response`/`Callback` into and out of **plain Clojure maps in the shape
of the Ring specification**, plus a small data-driven router and a short
middleware chain.

- **Request map:** `:request-method`, `:uri`, `:query-string`, `:headers`,
  `:body`, `:server-port`, `:remote-addr`, `:scheme` — Ring's keys, Ring's
  semantics.
- **Response map:** `:status`, `:headers`, `:body`.
- **Handler:** an ordinary function of one argument. Middleware is a function
  from handler to handler, as in Ring.
- **Router:** a vector of `[method path-pattern handler]` with `:param` segments,
  compiled once at start-up. Route data is inspectable, so the route table can be
  diffed against `api/openapi.yaml`.
- **Middleware chain:** correlation id → request logging → JSON body parsing →
  error translation → router.
- **Error translation:** domain errors carry `:clofin/error` in `ex-data` and are
  rendered as RFC 9457 `application/problem+json`. Unexpected exceptions become a
  `500` with a correlation id and no internal detail.

CloFin does **not** depend on the `ring-core` library; it conforms to the
specification. That is the point — conformance is what makes the adapter
replaceable.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Ring + reitit + a Jetty adapter library** | The idiomatic choice, and better than this one on ergonomics — reitit's route data, coercion and middleware registry are genuinely good. Rejected under ADR-0004 because CloFin uses a fraction of the surface, and because writing the adapter demonstrates understanding of the boundary rather than consuming it. Conforming to the Ring specification keeps this option live at low cost. |
| **Servlet API (Jetty EE10) with `jakarta.servlet`** | Adds the servlet container layer for no benefit here, and the servlet request/response objects are mutable and stateful — awkward to test and at odds with the rest of the codebase. |
| **`jdk.httpserver` (`com.sun.net.httpserver`)** | Zero dependencies, and briefly attractive. Rejected: it is explicitly not intended for production traffic, has no configurable connection management, and would be misleading in a project modelling an institutional platform. |
| **A framework with built-in routing** (Pedestal, Luminus) | Large transitive surface, strong opinions about application structure, and an interceptor model that obscures the plain-function boundary this ADR is trying to preserve. |
| **Defining a bespoke request/response shape** | Would forfeit the entire benefit. The value of Ring conformance is that any Clojure developer already knows the interface, and that a library adapter can be dropped in. |

## Consequences

**Positive**
- Handlers are tested by calling them with a map; the whole API test suite runs without a server.
- Any Clojure developer can read the handler layer without learning a bespoke abstraction.
- The transport is a genuine seam — replacing Jetty, or adopting Ring's own adapter, touches one namespace.
- Full control over error responses, which matters for a payments API where error semantics are part of the contract.

**Negative / accepted cost**
- CloFin maintains adapter code — body streaming, header case handling, character encoding, async completion — that a library would provide and that is easy to get subtly wrong.
- No multipart parsing, content negotiation, or session handling until written. None are needed; adding them without reconsidering this ADR would be a smell.
- The router supports path parameters and nothing more: no wildcards, no route middleware, no reverse routing.

**Risks and how they are mitigated**
- *Risk:* the adapter mishandles an edge case a mature library covers — chunked bodies, header folding, early client disconnect. *Mitigation:* the adapter is deliberately minimal, directly unit-tested, and confined to one namespace so the blast radius is small and the swap is cheap.
- *Risk:* the route table and `api/openapi.yaml` drift apart. *Mitigation:* routes are data, and a test asserts that every declared route has a matching OpenAPI operation and vice versa.

## Verification

`test/clofin/http/` covers the router, the middleware chain and error
translation by calling handlers as functions. A contract test compares the route
table against `api/openapi.yaml`. One smoke test binds a real port to verify the
Jetty adapter itself.
