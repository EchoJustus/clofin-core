# ADR-0018: A release posts to settlement-in-transit; finality moves that leg

- **Status:** Accepted
- **Date:** 2026-08-04
- **Deciders:** Worker session (TASK-004), Master Control
- **Supersedes / Superseded by:** —

## Context

TASK-004 asked one question it refused to answer for the implementer: **what
does `release` post, if anything?** Two designs were named as defensible:

- **(a)** release posts debtor funds into a settlement-suspense account,
  finality moves the suspense leg on, and a return reverses it;
- **(b)** nothing posts until finality, and `released` is a purely informational
  state.

The choice governs what an auditor sees **mid-flight** — during the window
between a payment leaving CloFin's control and the scheme saying what became of
it. That window is where a settlement module either earns its keep or does not:
it is exactly when someone asks "where is our money?", and it is where a
timed-out item lives indefinitely.

Two facts about the codebase bear on the choice and were verified rather than
assumed:

1. `clofin.payments.posting/release-lines` **already exists** on the base
   branch, debiting `1300-IN-TRANSIT` and crediting `1100-CLIENT-FUNDS`. It was
   written by TASK-002 and driven by nothing.
2. `DOMAIN_MODEL.md` §4's worked example already carries a **Release** row with
   that pair, and defers the settlement pair explicitly: *"the exact pair depends
   on the scheme, which is why posting templates are per payment type rather than
   global."*

So design (b) would have required deleting a template and a documented movement,
not merely declining to add one.

## Decision

**Design (a).** A release posts, and finality moves the in-transit leg on:

| Moment | Debit | Credit |
|---|---|---|
| Release (batch submitted) | `1300-IN-TRANSIT` | `1100-CLIENT-FUNDS` |
| Settlement (scheme confirms) | `2100-CLIENT-PAYABLE` | `1300-IN-TRANSIT` |
| Return (scheme sends it back) | `1100-CLIENT-FUNDS` | `1300-IN-TRANSIT` |
| Timeout (nobody answered) | — nothing posts — | |

Each is one entry, balancing on its own, referencing the instruction that caused
it (`DOMAIN_MODEL.md` I7).

**What an auditor sees mid-flight.** The balance of `1300-IN-TRANSIT` is, at any
instant, the total value CloFin has released and does not yet know the fate of —
its **clearing exposure**, readable from the ledger with no knowledge of the
settlement tables at all. An account statement for that account over a period is
a list of what went out and what came back. A payment that has timed out sits in
that balance and stays there until somebody resolves it, which is precisely the
visibility a stuck payment deserves.

**Why settlement debits the payable rather than client funds.** Release moved
value between two *assets* and left the liability alone, because until the scheme
settles CloFin still owes the client the money it is holding. Settlement
extinguishes both sides: the in-transit asset is credited away and the obligation
to the client (`2100-CLIENT-PAYABLE`) is debited down. A return, by contrast,
touches no liability — CloFin owed the client before and owes them still — and is
therefore the exact mirror of the release.

`DOMAIN_MODEL.md` §4's worked example previously showed the settlement row as
debit `1100-CLIENT-FUNDS` / credit `1300-IN-TRANSIT`, under an asterisk deferring
the pair to this increment. That pair is the **return**, not the settlement: it
puts the money back in the pool while leaving the client's claim on it intact.
The table is corrected in the same commit as this ADR, with a Return row added.

**A timeout posts nothing**, and that is a decision rather than an omission. The
value is already in `1300-IN-TRANSIT`, which is exactly where value of unknown
fate belongs. Posting *anything* on timeout would mean choosing between claiming
the money arrived and claiming it came back, and nobody knows which.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **(b)** Nothing posts until finality; `released` is informational | It makes the mid-flight window invisible in the ledger. During it, `1100-CLIENT-FUNDS` still shows money the institution has already sent, so the one account a treasurer reads overstates available funds by the value of everything in flight — and the overstatement is largest exactly when settlement is slowest. It would also require deleting `release-lines` and the `DOMAIN_MODEL.md` §4 Release row, so it is not the smaller change it appears to be. Its real advantage — a journal free of in-flight states — is a tidiness argument against a control argument |
| Post release **and** finality as a single entry at finality | Loses the ordering an investigation needs: two entries with different `occurred_at` say when the money left and when it landed; one entry says only that it landed. It also cannot represent a timeout at all, since there is no finality to hang the entry on |
| A per-scheme suspense account (`1300-IN-TRANSIT-RTGS`, …) | Real, and premature. It matters when reconciling against a scheme statement, which is increment 6's problem; adding the accounts now would be guessing at that increment's shape. `settlement_batch.scheme` records which scheme carried each payment, so the split can be derived later without a migration |
| Debit `1200-NOSTRO` on settlement | The nostro is the institution's balance at its settlement bank, and moving it on the strength of a *simulated* scheme response would assert a bank movement CloFin has no evidence for. The nostro moves when a bank statement says it moved — increment 6 |
| Reverse the original release entry on a return, rather than posting a mirror | Forbidden by ADR-0008: a posted entry is immutable and a mistake is corrected by a compensating entry. A return is not even a mistake — it is a second real event — so it gets its own entry with its own timestamp |

## Consequences

**Positive**

- Clearing exposure is a ledger balance, derivable at any instant, with no
  settlement-specific query.
- Every state a payment can be in has an accounting representation, so the
  journal and the payment lifecycle cannot drift into disagreement.
- A timed-out payment is *visible as money* rather than only as a row in
  `settlement_batch_item`.
- `release-lines` stops being a template nothing drives.

**Negative / accepted cost**

- Three entries per settled payment instead of one, so the journal grows roughly
  threefold in settlement volume. Accepted: the ledger is the product, and
  ADR-0011's row caps already assume it grows.
- An organisation must open `1100-CLIENT-FUNDS`, `1300-IN-TRANSIT` and
  `2100-CLIENT-PAYABLE` in the batch's currency before it can settle. Submission
  refuses with the missing codes named rather than failing part-way through a
  batch, but it is a real precondition that did not exist before.
- `1300-IN-TRANSIT` accumulates the value of every timed-out payment and only
  drains when each is resolved. That is the intended reading — an unresolved
  payment *should* be uncomfortable to look at — but it means the account is not
  self-clearing.

**Risks and how they are mitigated**

- *Risk:* a partial batch failure leaves the journal unbalanced.
  *Mitigation:* each entry balances on its own and the whole batch commits in one
  transaction; `ac-4-the-ledger-stays-balanced-across-every-outcome-mix` is a
  property test over generated outcome mixes asserting zero-sum across the flow,
  not three examples.
- *Risk:* a duplicate scheme response posts finality twice.
  *Mitigation:* the response replay key refuses the duplicate and the item
  resolves under `where outcome is null`, so no second entry is produced — AC-5,
  asserted at both the repository and the API level.

## Verification

- `clofin.payments.posting-test` — the three templates' directions and accounts.
- `clofin.api.settlement-api-test/ac-3-submission-releases-every-member-with-one-audit-event-each`
  — a release posts, one entry per member, and every entry balances.
- `clofin.api.settlement-api-test/ac-4-the-ledger-stays-balanced-across-every-outcome-mix`
  — a property test over generated settled/returned mixes: the ledger is sound
  for every mix, and the entry count is exactly one release plus one finality
  entry per instruction.
- `clofin.api.settlement-api-test/ac-6-the-sweep-marks-unanswered-items-timed-out-and-leaves-them-unknown`
  — a timeout posts nothing and moves no payment.
- `unbalanced-entries` in that namespace queries the journal directly for any
  entry whose lines do not net to zero per currency, so the assertion is about
  the ledger rather than about CloFin's opinion of it.
