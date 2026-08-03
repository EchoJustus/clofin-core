# ADR-0014: The payment instruction lifecycle is data, and rules that are not transitions say so

- **Status:** Accepted
- **Date:** 2026-08-03
- **Deciders:** Technical lead
- **Supersedes / Superseded by:** —

## Context

A payment instruction has a lifecycle: `draft` → `pending-approval` →
`approved` → `released` → `settled`, with `cancelled`, `rejected`, `failed` and
`returned` as the ways out. [`DOMAIN_MODEL.md`](../DOMAIN_MODEL.md) §3 draws it
as a diagram and then adds five rules the diagram does not carry.

Lifecycles like this are usually written as conditionals inside the handler
that performs each operation — `if status is draft then …`. That works until
the second endpoint, at which point the rules exist in two places; by the fourth
they disagree, and the diagram in the documentation is describing a system that
no longer exists. The failure is silent and it is discovered during an audit.

Three further forces apply here specifically.

**The increment is deliberately partial.** Approval is TASK-003 and settlement
is increment 5, so `approve`, `reject`, `release`, `settle`, `fail` and `return`
are real transitions with no endpoint driving them yet. Whatever holds the
lifecycle has to be able to state a transition that nothing calls, or those
transitions get invented independently later.

**Not every rule about status is a transition.** `DOMAIN_MODEL.md` §1 says a
payment instruction is "mutable while `draft`; immutable in substance
thereafter". Amending a draft changes its fields and leaves it in `draft` — no
state change occurs, and the operation is not on any arrow of the diagram.
Raising a reversal against a `settled` instruction is likewise not a transition:
the original is untouched and a *new* instruction is created (§3 rule 4). Both
rules are nonetheless about which status permits what, and both are exactly the
kind of rule that ends up as an `if` in a handler.

**One of those rules collides with a transition of the same name.** The
transition table carries `:amend` on `pending-approval`, returning the
instruction to `draft` and invalidating approvals already given — that is
`DOMAIN_MODEL.md` §3 rule 3, traced to **PR-014**, which belongs to TASK-003's
approval workflow. The `PATCH` endpoint delivered here is a different
operation that shares the word: an in-place edit of a draft. If `PATCH` drove
the `:amend` event, patching a `pending-approval` instruction would succeed and
silently pull a submitted payment back to `draft` — with no approval-invalidation
logic behind it, because that logic is TASK-003's and does not exist yet.

## Decision

**1. `clofin.payments.state/transitions` is the lifecycle.** A map of state to
`{event → next-state}`. `permitted?`, `permitted-events`, `terminal?` and
`transition` all read it; none of them restates a rule. A handler never inspects
a status to decide whether an operation is allowed — it calls `transition`,
which returns the next state or throws a `:conflict` naming the state, the
attempted event, and what would have been permitted instead.

Because the table is a value, the exhaustive test is a `for` over
`states × events` rather than a list of cases someone remembered to write, and
the diagram in `DOMAIN_MODEL.md` can be checked against it rather than trusted.

**2. Transitions with no endpoint stay in the table.** `approve`, `reject`,
`release`, `settle`, `fail`, `return` and the `pending-approval` `amend` are all
present and all tested. A later increment adds the endpoint that drives one; it
does not get to redefine where it leads.

**3. A rule that is not a transition is a named set beside the table, never a
conditional in a handler.** Two exist:

```clojure
(def mutable-states    #{:draft})     ; may be edited in place — DOMAIN_MODEL §1
(def reversible-states #{:settled})   ; may have a reversal raised — §3 rule 4
```

Each has an `assert-…!` that throws `:conflict` naming the state, so the
failure a caller sees is the same shape as a rejected transition. They live in
`clofin.payments.state` next to `transitions`, so "what does status control?"
has one answer and one file.

**4. `PATCH /payment-instructions/{id}` is governed by `mutable-states`, not by
the `:amend` event.** Amending a draft leaves it in `draft`; amending anything
else is `409`. The `:amend` transition remains in the table, undriven, for
TASK-003 to wire to the approval workflow that PR-014 describes.

**5. A field-level validation failure is `422` under the `validation` problem
type.** `clofin.error/error-types` gains `:field-validation` — status `422`,
title `Request failed validation` — carrying an explicit `:problem-type` of
`:validation` so the URI a client branches on stays
`https://clofin.dev/problems/validation`. The existing `:validation` category
keeps its `400` for a request that could not be *understood*: a malformed UUID,
an absent body, a missing `Idempotency-Key`. An amount of zero, a value date in
the past and an unknown purpose code are understood perfectly well and cannot be
carried out, which [ADR-0012](0012-repository-seam-and-posting-time-validation.md)
already settled as the meaning of `422`.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Transition rules as `if`/`case` in each handler | The rules then exist once per endpoint. `DOMAIN_MODEL.md` §3 stops being a description and becomes a claim nobody checks. The exhaustive test also becomes impossible to write — there is nothing to enumerate. |
| A state-machine library | A runtime dependency ([ADR-0004](0004-minimal-dependency-footprint.md)) for a nine-state map and three functions over it. |
| Put `mutable-states` and `reversible-states` in the handler or the repository | Same failure as conditionals, one layer down: two namespaces would then answer "what does status permit?", and a reviewer has to find both. |
| Add `:amend :draft` as a self-transition on `draft` and let `PATCH` drive `:amend` | Reads well, but leaves the `pending-approval` `:amend` still permitted — so `PATCH` on a submitted instruction would succeed and return it to `draft`, without the approval-invalidation PR-014 requires. It trades a documented interpretation for a silent hole in the control. |
| Delete `:amend` from `pending-approval` until TASK-003 needs it | Contradicts `DOMAIN_MODEL.md` §3 rule 3, which is a stated part of the model. The lifecycle is not a record of which endpoints happen to exist this week. |
| Reuse `:unprocessable` (`422`) for field validation | The problem type would be `https://clofin.dev/problems/unprocessable` and the title "Request cannot be processed" — so a client could not distinguish "these named fields are wrong" from "this entry does not balance" by `type`, which is the one member RFC 9457 promises is stable. |
| Return `400` for field validation, matching the existing `:validation` | Contradicts ADR-0012's split, which the ledger endpoints already follow: `400` is a bug in the caller, `422` is a business outcome to show a human. A rejected value date is the second kind. |

## Consequences

**Positive**

- The lifecycle is one value. Adding a state or an event is a one-line diff that
  the exhaustive test immediately covers, in both directions — new permitted
  pairs must succeed, and every pair still absent must raise.
- A caller can be told what it may do next: `permittedTransitions` on the
  instruction resource is `(permitted-events status)`, so the API cannot
  advertise an operation the state machine would refuse.
- Terminality is derived, not declared. `settled` is terminal because nothing
  leaves it, so no one can add an arrow out of it and leave a `terminal?` list
  saying otherwise.

**Negative / accepted cost**

- Two rules about status live outside `transitions`. That is one more place to
  look than "everything is in the table", and the mitigation is that both sit in
  the same namespace, immediately below it, with the `DOMAIN_MODEL.md` clause
  each implements named in its docstring.
- `PATCH` and the `:amend` event share a word while doing different things.
  Recorded here because it is the first thing a reader of the table will trip
  over, and the second thing TASK-003 has to get right.

**Risks and how they are mitigated**

- *A handler grows a status conditional anyway.* The exhaustive test does not
  catch that — it tests the table, not its callers. Mitigated by review, and by
  keeping the `assert-…!` helpers so obviously available that restating a rule
  costs more than calling one.
- *TASK-003 wires `PATCH` to `:amend` without reading this.* Mitigated by
  naming PR-014 in the decision above and in the `transitions` docstring, so
  the constraint is visible from the code.
- *`:field-validation` and `:validation` diverge in title or type.* Both are in
  one map, five lines apart, and `clofin.error-test` asserts every category
  renders a status and a title.

## Verification

- `clofin.payments.state-test` enumerates every `(state, event)` pair over
  `states × events`: each pair in the table returns its declared next state, and
  every pair absent from it raises `:conflict`. Terminal states are asserted to
  have no outgoing event, and each non-terminal state to have at least one.
- The same test asserts every next-state named in the table is itself a key of
  the table, so no transition can point at a state that does not exist.
- `clofin.api.payments-api-test` covers the interpretation directly: a draft
  amends, a submitted instruction is `409`, and a settled instruction rejects
  every transition while still accepting a reversal.
- `clofin.ledger.purity-test` lists `clofin.payments.state`,
  `clofin.payments.instruction` and `clofin.payments.posting` as pure, so the
  lifecycle cannot acquire a database dependency and start consulting rows.
