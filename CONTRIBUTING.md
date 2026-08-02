# Contributing to CloFin

Critique is more welcome here than agreement. CloFin is a public demonstration
of how to specify and build a regulated payments product; if a control is weak,
a trade-off is wrong, or a claim is overstated, saying so improves the project
more than a feature would.

> CloFin operates on synthetic data only. Never contribute real data of any
> kind — no client data, no personal data, no production configuration, no
> credentials.

## Getting set up

```bash
git clone https://github.com/EchoJustus/clofin-core.git
cd clofin-core
cp .env.example .env
make up          # full stack in Docker
make test        # unit and property tests
make help        # every available target
```

A local Clojure CLI with JDK 21 gives a faster loop and is used automatically
when present. Without it, the same commands run in a container.

## What is most useful

| | |
|---|---|
| **Challenge a decision** | Every ADR states the alternatives it rejected. If a rejection is wrong, open an issue arguing it — that is the most valuable contribution possible here. |
| **Find a way to break the ledger** | [UAT-002](docs/uat/UAT-002-ledger-integrity.md) attacks the ledger directly in SQL. If you find a path it misses, that is a genuine defect. |
| **Correct a domain error** | If a control, a payment concept or an accounting treatment is modelled wrongly, say so. Practitioner correction is worth more than any amount of code. |
| **Flag an overstated claim** | If any wording implies real funds, institutional connectivity or regulatory approval, raise it immediately. That is the most serious defect class in this repository. |

## Before you open a pull request

Read [`docs/AGENT_HANDOFF.md`](docs/AGENT_HANDOFF.md) — it is the working
agreement, and it applies to human contributors as much as to automated
sessions. In particular:

- [ ] `make verify` passes
- [ ] `make test-it` passes if the change touches persistence
- [ ] Tests cover the change; anything with an invariant has a property test
- [ ] `api/openapi.yaml` updated in the same commit as the handler
- [ ] An ADR for any decision a future contributor would otherwise re-derive
- [ ] `docs/ROADMAP.md` reflects the new state
- [ ] No secrets, no real data, no overstated claims

## Rules that are not negotiable

1. **Money is never a float** — anywhere, including JSON and the database.
2. **Posted entries are immutable** — corrections are reversing entries.
3. **The domain layer is pure** — no database, no clock, no id generation.
4. **Every runtime dependency needs an ADR** — test-scope dependencies do not.
5. **`main` is always runnable, migrated, documented and green.**
6. **Migrations are append-only** — never edit one that has been applied.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/), one logical change
per commit:

```
feat(ledger): derive account balance from journal lines
fix(money): keep minor units integral when parsing a decimal
docs(adr): record why balances are never stored
```

Explain *why* in the body. The diff already says what.

## Licence

Contributions are made under the [Eclipse Public License 2.0](LICENSE).
