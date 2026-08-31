# ADR 0004 — The recency signal uses a real half-life

**Status:** accepted · 2026-08-31

## Context

The design specifies:

```
rec = exp(−Δdays / HALFLIFE_DAYS)      default 180 days
```

Those two lines disagree with each other. `exp(−1) = 0.368`, so under that formula a value named
`HALFLIFE_DAYS = 180` halves the signal at 125 days, not 180. The parameter is a mean lifetime
wearing a half-life's name.

## Decision

```
rec = 0.5 ^ (Δdays / halfLifeDays)
```

## Why

The number is exposed as workspace configuration for people to tune against their own data. A
setting whose name promises one thing and delivers another is a bug in the interface, and the person
who eventually notices will be doing it by bisecting ranking results.

The shape of the curve is unchanged — exponential decay either way — so nothing downstream is
affected beyond the constant. `SignalTest.recencyHalvesEveryHalfLife` pins the corrected behaviour.

## Note

180 days remains an arbitrary starting value, as the design says plainly. It is configuration
precisely because the right answer for a personal assistant and for a coding agent are different
numbers, and neither is knowable in advance.
