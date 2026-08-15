<!-- GENERATED FILE — do not edit by hand.
     Regenerate with `make diagrams`. `make diagrams-check` fails the build on drift.
     Generator: clofin.tools.diagrams, per ADR-0020 RULE 1 (generate, never draw). -->

# Generated diagrams

Every diagram in this directory is produced from a machine-readable source
by `clofin.tools.diagrams` and verified by `make diagrams-check`, which runs
inside `make verify`. None of them is drawn or adjusted by hand — see
[ADR-0020](../ADR/0020-two-repositories-and-the-generate-replay-rules.md)
RULE 1, and standing lesson **L-4** for what a hand-maintained drawing cost.

| Diagram | Source of truth |
|---|---|
| [Payment instruction lifecycle](payment-lifecycle.md) | `clofin.payments.state/transitions` |
| [Reconciliation break lifecycle](reconciliation-break-lifecycle.md) | `clofin.recon.break-state/transitions` |
| [Bounded-context topology](context-topology.md) | [`ARCHITECTURE.md` §3](../../ARCHITECTURE.md) and the `ns` forms under `src/` |
| [Control map](control-map.md) | [`COMPLIANCE.md` §2](../COMPLIANCE.md) |

To change a diagram, change its source and run `make diagrams`.
