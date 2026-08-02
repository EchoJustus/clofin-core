# ADR-0003: Money as integer minor units

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead / product owner

## Context

Every amount in CloFin is eventually posted to a ledger that must balance
exactly. Representation errors here are not cosmetic: a rounding difference of
one minor unit on a settlement batch is a reconciliation break that a human has
to investigate, and a currency mismatch that silently coerces is a
misappropriation of funds.

Currencies do not share a scale. JPY and KRW have no minor unit; most currencies
have two decimal places; BHD, KWD and TND have three. A representation that
assumes "cents" is wrong for a third of the world.

## Decision

An amount is a map of an ISO 4217 alphabetic code and a signed integer count of
that currency's **minor units**:

```clojure
{:currency "SGD" :minor-units 125000}   ; SGD 1,250.00
{:currency "JPY" :minor-units 125000}   ; JPY 125,000
{:currency "KWD" :minor-units 125000}   ; KWD 125.000
```

Rules:

1. Floating-point types are never used for monetary values, anywhere, including
   in JSON serialisation and in the database.
2. Arithmetic between different currencies raises an error. There is no implicit
   conversion; FX is an explicit operation producing an explicit audit record.
3. The scale of a currency comes from a registry (`clofin.money/currencies`), not
   from a hard-coded constant.
4. Division and proportional allocation must use a remainder-preserving
   allocation function — never naive division — so that split amounts always sum
   back to the original.
5. On the wire, an amount is transported as `{"currency": "SGD", "minorUnits": 125000}`.
   The string decimal form is a *presentation* concern, produced only for display.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **IEEE-754 floating point** (`double`) | Cannot represent 0.10 exactly. Accumulating a batch of a few thousand payments drifts. Disqualifying for a ledger. |
| **`BigDecimal` with a scale** | Correct, and the usual JVM answer. Rejected as the *canonical* form because scale is carried per value rather than per currency, so two values of the same currency can disagree on scale, and equality becomes surprising (`1.5 ≠ 1.50` under `equals`). An integer count against a currency-defined scale has exactly one representation per amount. `BigDecimal` is still used at the presentation boundary. |
| **Decimal string** (`"1250.00"`) | Pushes parsing and validation into every consumer, and makes arithmetic a parse-compute-format round trip at every step. |
| **Integer minor units with no currency attached** | The dangerous option: it makes cross-currency arithmetic *silently succeed*. The currency has to travel with the amount for the type to be safe. |
| **Fixed scale of 2 for all currencies** | Wrong for JPY, KRW, BHD, KWD, TND, and others. A payments platform that cannot represent a yen amount is not a payments platform. |

## Consequences

**Positive**
- Exact arithmetic; addition and subtraction are integer operations.
- One canonical representation per amount, so equality and hashing are trivially correct.
- Cross-currency errors surface as exceptions at the point of the mistake.
- Maps cleanly to `BIGINT` in PostgreSQL and to a JSON integer.

**Negative / accepted cost**
- Callers must think in minor units. Mitigated by parsing and formatting helpers at the edges.
- `minor-units` is a JVM `long`. The maximum representable SGD amount is roughly 9.2×10¹⁶ — about 92 trillion in major units, which is beyond any plausible instruction, but the bound is real and is asserted in the database with a `CHECK` constraint.
- A currency registry has to be maintained. It is a small static table, versioned in the repository.

**Risks and how they are mitigated**
- *Risk:* a contributor introduces a `double` in a hot path. *Mitigation:* the money namespace exposes no function accepting a floating-point value, and the database column type is `BIGINT`, so a float cannot round-trip.
- *Risk:* proportional splits lose a unit. *Mitigation:* `clofin.money/allocate` is covered by a property test asserting that the parts always sum to the whole.

## Verification

Property tests in `test/clofin/money_test.clj` assert associativity and
commutativity of addition, round-trip of parse/format across all registered
currency scales, and the allocation sum-preservation law. Cross-currency
arithmetic is asserted to throw.
