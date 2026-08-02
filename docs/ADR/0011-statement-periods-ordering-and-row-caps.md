# ADR-0011: Statement periods, movement ordering and the row cap

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead
- **Supersedes / Superseded by:** —

## Context

[ADR-0008](0008-double-entry-journal-as-source-of-truth.md) settles *where* the
truth about money lives: the journal, with balances derived from it. It does not
settle how a **statement** — opening balance, movements, closing balance for a
period — is computed from that journal. Three questions have to be answered
before the first statement is produced, and each is one a future contributor
would otherwise re-derive differently:

1. **Are period boundaries inclusive or exclusive?** Get this wrong and two
   consecutive statements either double-count a movement on the boundary or
   lose it. Neither is detectable by looking at a single statement, which is
   what makes it dangerous.
2. **In what order do movements appear?** `occurred_at` is supplied by the
   caller ([`clofin.ledger.entry`](../../src/clofin/ledger/entry.clj) takes the
   occurrence time rather than reading a clock, so that entries are reproducible
   and replayable). It is therefore **not unique** — a batch of postings for one
   economic event routinely shares an instant. A running balance printed in a
   non-deterministic order is a different document every time it is produced,
   and cannot be used as evidence.
3. **What happens when a period contains more movements than the API returns?**
   The brief for this increment caps responses at 500 rows and defers real
   pagination until there is a consumer. A cap interacts with the statement's
   central promise — that opening plus movements equals closing — because the
   movements returned are then not all the movements there were.

## Decision

**1. Periods are half-open: `[from, to)`.** A movement belongs to the period
when `from <= occurred_at < to`. The opening balance is derived from every line
strictly before `from`; the closing balance from every line strictly before
`to`.

This makes consecutive periods chain exactly: the closing balance of
`[t1, t2)` is by construction the opening balance of `[t2, t3)`, with no
movement counted twice and none lost. It also avoids the precision trap in the
alternative: `timestamptz` has microsecond resolution, so an "inclusive"
end-of-period expressed as `23:59:59.999Z` silently drops anything posted in the
following 999 microseconds.

**2. Movements are totally ordered by `(occurred_at, recorded_at, entry_id,
line_no)`.** The first key is the economic order — when the event happened. The
second is the booking order — when CloFin was told about it — which breaks ties
between separate entries sharing an occurrence instant in the order they were
actually recorded. The last two are arbitrary but stable, and exist so that the
order is *total* rather than merely usually-unique. Two lines of the same entry
touching the same account are distinguished by `line_no`, which is why the
statement returns it.

**3. The closing balance is aggregated from the journal, never from the returned
movements.** It is a separate `sum` over every line strictly before `to`, with
no `limit`. This holds whether or not the movement list was capped.

**4. The row cap is 500 and truncation is stated in the response.** A statement
whose movements were capped carries `truncated: true`. Where it is `false` —
the ordinary case — `opening + sum(movements) = closing` and the last
movement's running balance equals the closing balance. Where it is `true`,
the returned movements are the *earliest* 500 in the period, the running balance
is correct for each of them, and the closing balance is still authoritative
because of decision 3. Consumers that see `truncated: true` must narrow the
period rather than treating the last running balance as a closing figure.

**5. `balance-at` is inclusive; period boundaries are not.** `(balance-at source
account as-of)` answers "what was this account's balance at this instant",
counting every line up to and including `as-of` — the natural reading of a
point-in-time question. The statement's opening and closing figures use the
strictly-before form required by decision 1. The two are deliberately different
functions rather than one function with a flag, because the alternative is a
boolean at every call site that reviewers must decode.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Inclusive periods `[from, to]`** | Matches how a finance user says "1st to the 31st", and is the more familiar convention. Rejected because the boundary instant then belongs to two adjacent periods at once: the closing balance of one period and the opening balance of the next disagree by exactly the movements posted at that instant. The user-facing convention can still be presented inclusively by a UI that passes `to` as the start of the next day — the API keeps the convention that composes. |
| **Order by `occurred_at` alone** | Simplest, and wrong whenever an instant repeats — which is the normal case for a multi-line posting, not an edge case. PostgreSQL is free to return equal keys in any order, so the running balance column would differ between two runs of the same query against unchanged data. |
| **Order by `recorded_at` alone** | Deterministic in practice but reports the ledger in the order CloFin was *told* about events rather than the order they *happened*, so a backdated correction appears after movements it precedes economically. The economic order is what a statement is for. |
| **Order by a monotonic sequence number on `journal_line`** | Genuinely simpler to reason about, and would remove the composite sort. Rejected for now because it adds an authoritative ordering column to an append-only table whose ordering is currently derivable, and because it would order by insertion, reintroducing the previous row's problem. Reconsider if the composite sort measures badly. |
| **Storing a running balance per line** | Fast reads. Rejected under ADR-0008: it is the mutable-balance anti-pattern one level down, and a running balance is only meaningful relative to a chosen ordering and period, neither of which is a property of the line. |
| **Deriving the closing balance by summing the returned movements** | One fewer query, and correct until the day a period exceeds the cap — at which point every capped statement reports a wrong closing balance, with nothing in the response to indicate it. A figure that is right until it silently is not, is worse than a second query. |
| **Real pagination now** | The honest answer for a large ledger, and where this ends up. Deferred deliberately: there is no consumer yet, and a cursor contract designed without one would be guesswork. The cap plus an explicit `truncated` flag is the smallest thing that is not misleading. |

## Consequences

**Positive**
- Consecutive statements chain exactly, and that property is asserted by a test rather than assumed.
- The same query over unchanged data produces byte-identical output, so a statement can be attached to a case file as evidence.
- A capped response is never mistaken for a complete one, because the closing balance stays correct and truncation is explicit.
- `balance-at` and the statement figures cannot be confused for one another at a call site.

**Negative / accepted cost**
- Two queries per statement — movements and closing balance — where one would do.
- The composite sort cannot be served by a single existing index; `journal_line (account_id)` narrows the scan and PostgreSQL sorts the remainder. Acceptable at present volumes and deliberately unoptimised until measured, per ADR-0008.
- `to` being exclusive will surprise someone who passes an end-of-month instant expecting it to be included. Mitigated by stating it in `api/openapi.yaml` on the parameter itself, where the reader is when the question arises.

**Risks and how they are mitigated**
- *Risk:* a consumer ignores `truncated` and treats the last running balance as a closing balance. *Mitigation:* the closing balance is a separate field that is always correct, so the correct figure is present in the same response; the OpenAPI description states the relationship.
- *Risk:* the cap is quietly raised, and the memory cost with it. *Mitigation:* the cap is one named constant in `clofin.ledger.repository`, referenced by the OpenAPI description, and asserted by a test that posts past it.

## Verification

`test/clofin/ledger/repository_test.clj` asserts, against real PostgreSQL:

- opening + sum(movements) = closing, and the last running balance equals the
  closing balance (the acceptance criterion this endpoint exists for);
- the closing balance of one period equals the opening balance of the next,
  for adjacent periods sharing a boundary instant that has movements on it;
- movements sharing an `occurred_at` come back in the same order across
  repeated queries;
- a period with more than the cap reports `truncated`, still reports a correct
  closing balance, and returns the earliest 500 movements.
