# Fixture — Roadmap

Synthetic fixture for `clofin.tools.doc-consistency-test`.

Legend: ✅ done · 🔨 in progress · 📋 next · 💭 later

## Global state

| Increment | Theme | Brief | Status | CI |
|---|---|---|---|---|
| 1 | Foundation | — | ✅ done — predates the brief protocol | green |
| 2 | Ledger | [TASK-001](briefs/001-TASK-ledger.md) | ✅ `CLOSED` — merged in PR #2 | green |
| 5v.1 | Visual layer — diagrams | [TASK-002](briefs/002-TASK-diagrams.md) | 📋 `READY` — dispatch after the ADR lands | — |
| 5v.2 | Visual layer — walkthrough | [TASK-003](briefs/003-TASK-trace.md) | 📋 `READY`, gated on 5v.1 | — |
| 6–9 | Reconciliation onwards | not yet briefed | 💭 later | — |

**Controls now enforced on `main`.** C-01 (segregation of duties) and C-02
(dual authorisation) are enforced on `main`. C-07 (screening) remains 📋.

---

## Increment 1 — Foundation ✅

- ✅ Everything that predates the brief protocol

## Increment 2 — Ledger ✅

**Brief:** [TASK-001](briefs/001-TASK-ledger.md) · **Status:** `CLOSED` — merged in PR #2

- ✅ Accounts, entries, balances

## Increment 6 — Reconciliation 💭

- Statement ingestion and matching
