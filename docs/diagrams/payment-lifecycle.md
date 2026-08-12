<!-- GENERATED FILE — do not edit by hand.
     Regenerate with `make diagrams`. `make diagrams-check` fails the build on drift.
     Generator: clofin.tools.diagrams, per ADR-0020 RULE 1 (generate, never draw). -->

# Payment instruction lifecycle

> Generated from `clofin.payments.state/transitions`
> by `clofin.tools.diagrams`, per
> [ADR-0020](../ADR/0020-two-repositories-and-the-generate-replay-rules.md)
> RULE 1 — *generate, never draw*.

```mermaid
stateDiagram-v2
    direction LR

    state "approved" as approved
    state "cancelled" as cancelled
    state "draft" as draft
    state "failed" as failed
    state "pending-approval" as pending_approval
    state "rejected" as rejected
    state "released" as released
    state "returned" as returned
    state "settled" as settled

    [*] --> draft

    approved --> draft : amend
    approved --> cancelled : cancel
    approved --> released : release
    draft --> cancelled : cancel
    draft --> pending_approval : submit
    pending_approval --> draft : amend
    pending_approval --> approved : approve
    pending_approval --> rejected : reject
    released --> failed : fail
    released --> returned : return
    released --> settled : settle

    cancelled --> [*]
    failed --> [*]
    rejected --> [*]
    returned --> [*]
    settled --> [*]
```

Every state, every event and every permitted pair above is read from that
table. The terminal states — the ones with an arrow to `[*]` — are
**derived** through `clofin.payments.state/terminal?` rather than listed, so a
state that gains an outgoing transition stops being drawn as terminal in the
same commit that gives it one.

The rules that are *not* transitions — `mutable-states`, `reversible-states`
and `creator-only-events` — govern operations that leave the status where it
was, so they are no arrow on any diagram. They are stated in
[`DOMAIN_MODEL.md` §3](../DOMAIN_MODEL.md) beside this drawing.

