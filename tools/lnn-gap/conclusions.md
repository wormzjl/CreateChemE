# LNN-gap campaign: what the registered rules returned, and what to ship

Hand-written companion to the generated tables in `results.md` (round one), `results-v2.md` (the E2b
re-measurement) and `results-v3.md` (the combination round). Every number here is in
`evidence/v*-validation-analysis.json`; nothing is restated from memory.

Population: the cleaned validation set, 330 inputs, SHA-256 `321d9501...`, 0 ids outside the promotion
archive. Ten workers, 30 s request deadline, two reversed-order blocks, production entry point, frozen
bundled weights `7f909d02...`. Classical is 110 of 330 in every one of the 22 arm-blocks.

## 1. Parity

Round one's baseline reproduced the promotion run's strict **identity sets** exactly — classical 110,
LNN_ONLY 162, LNN_FIRST 180, zero differing ids, both blocks. Every arm's first decoded candidate
reproduced the promotion seed digests, 330/330, in all three rounds.

The two later rounds' baselines each differ from the promotion by exactly one LNN_ONLY case in block 1:
`gd-s59-w0-p1-d0-r00` in round 1b and `gd-s38-w0-p0-d2-r00` in round 2, both *gained* rather than lost.
Both are the documented two-second-wall jitter cases — the promotion results name the first as
"non-reproducible" and the gap analysis names the second as "strict under all four sibling pipelines and
lost purely at the clock". LNN_FIRST is 180/180 in every baseline. The deviation is the clock, not the code.

## 2. What each registered rule returned

| Experiment | Selected | Why |
|---|---|---|
| E1 extension gate | **E1a** (contraction factor 1.0) | net LNN_ONLY +2 in both blocks, latency +0.6 ms |
| E2 early stall abort | **nothing** | E2a is ONLY-neutral (net −1 in block 2); E2b costs +71.4 ms against a 61.6 ms band |
| E3 ramp handoff | **nothing** | largest coverage win of the study (+8/+9 ONLY) but +2047 ms on the pooled FIRST mean |
| E4 second candidate | **E4** (prune decode offered second) | net LNN_ONLY +2 in both blocks, latency +93.4 ms inside the 119.1 ms band |
| E5 combination | **not recommended** | E1a+E4 measured at ONLY +3, which is not better than round one's best net ONLY gain of 8 |

Three arms passed all three study gates in round one: **E1a, E2a and E4**. E1b, E2b and E3 did not.

## 3. Two deviations from the protocol as registered

**The E2b arm measured a defect, not its intervention.** Round one read LNN_ONLY 0 of 330 for E2b, on every
case, with the evidence line `maximum scaled residual X at iteration 0 and X now`. The solver allocates its
residual history when either the stall stop or the progress extension asks for one, but the stop tested only
whether its window reached back far enough; with the extension on and the stop off the window is zero, so
`earlier == iteration` and each residual was compared to itself. The combination is legal through the public
options record — `Correction(8, 48, 8, 0.5, 0, 0.0, 0.0)` — so the defect is production reachable, though the
shipped `PROGRESS` rule enables both tests and never hits it and `WALLS` enables neither, so no history is
allocated. Fixed by gating the stop on its own positive window, with a regression test; re-measured in round
1b against a re-run baseline on a core carrying the fix. That baseline reproducing 110 / 162-163 / 180 is the
evidence that the fix is inert for every configuration with a window, which is every other arm and the
shipped default.

**The E5 rule is mis-specified and this is reported rather than repaired after the fact.** As registered, the
combination is recommended only if it beats "the best single arm" on LNN_ONLY in both blocks, and the
analysis implements that as the best net gain among *all* round-one candidates. That is E3's 8, and E3 failed
the latency gate, so an ineligible arm vetoes the combination. Under the more defensible reading — best
*eligible* arm, which is E1a and E4 at +2 — E5's +3 would qualify on coverage, though it would still fail the
latency band at +265 ms against 186 ms. Both readings are given so the decision rests on the measurement.

## 4. The ramp handoff, priced

E3 armed a handoff on 308 of 660 LNN_ONLY requests and accepted 18 of them (9 cases × 2 blocks, 16 strict).
Cost per armed request: mean 5031 ms, median 6086 ms; when it closed, mean 1260 ms; when it refused, mean
5265 ms and median 6885 ms — most refusals spend the whole 8 s sub-wall. Total 1550 s in LNN_ONLY and 1402 s
in LNN_FIRST across both blocks, which is the +2047 ms on the pooled all-case FIRST mean. It recovers 6-7 of
the 18 group-B cases, more than any other arm, and loses nothing anywhere.

**It lands on the classical root.** Of the nine cases it recovers, seven have a classical solution to compare
against, and all seven agree with it to between 2e-13 K and 5e-9 K on every node temperature and to 1e-14 to
2e-8 relative on every phase total (`evidence/root-comparison.json`). The remaining two are cases the
classical route never solves. The root multiplicity seen on the synthetic unit-test fixture does not appear
on the real population: the handoff is reaching the same answer, without paying for the cold continuation.

## 5. Recommended production defaults

**Change one default.** Set the correction's contraction factor from 0.5 to **1.0** — extend an attempt that
reached its cap unless its residual actually rose over the window. It is the E1 selection: +2 LNN_ONLY and +2
LNN_FIRST in both blocks, three stable gains, one stable loss, and +0.6 ms on the pooled FIRST mean against a
119 ms noise band. It is the cheapest coverage in the study by a wide margin, and it answers the gap
analysis's first finding directly: on the 20 registered crawling cases it takes LNN_ONLY from 11 to 12 and
LNN_FIRST from 13 to 14, recovering two and losing one. (The crawling *recall* figure in `results.md` is
unchanged at 15 of 20 between the baseline and E1a, and should be: it measures the early stall abort, which
E1a does not touch.)

**Offer two more, off by default.**

- `CandidateRule.DECODE_VARIANTS` (E4): +2 LNN_ONLY and +2 LNN_FIRST in both blocks, no losses, recovers two
  group-C cases the phase floor alone cannot reach, at +93 ms. Inside the band but not free; worth enabling
  wherever a request can afford it. Combined with E1a (E5) it reaches **LNN_FIRST 184 of 330** — the best
  coverage measured anywhere in this study — at +265 ms, which is over the band and therefore a latency
  decision rather than an automatic adoption.
- `Recovery.RAMP_HANDOFF` (E3): the largest coverage win, LNN_ONLY 170-171 of 330, and it lands on the
  classical root. Keep it `NONE` by default and expose it for offline or batch work: two seconds on the
  all-case FIRST mean is not something to put behind a game tick silently.

**Change nothing else.** E1b (flat 48-iteration cap) loses 16 LNN_ONLY and 10 LNN_FIRST cases and is the
clearest negative result in the study: more iterations without the gate spend the two-second wall before the
support-refresh pass that would have converged them. E2's abort should stay exactly as shipped; widening it
is ONLY-neutral and removing it costs more latency than its coverage is worth.

## 6. Round four: the handoff's sub-wall (registered in `protocol-round4.md`)

The contraction factor is now the shipped default, so round four's baseline is that rule and reads
LNN_ONLY 164/164 and LNN_FIRST 182/182 of 330 — the E1a arm's numbers, reproduced against a fresh core.
Both shortened-wall arms sit on top of it.

| Arm | sub-wall | ONLY b1/b2 | FIRST b1/b2 | group B | handoff total, ONLY | pooled FIRST delta |
|---|--:|--:|--:|--:|--:|--:|
| `baseline` | — | 164/164 | 182/182 | 1/1 | — | — |
| `E3-2500` | 2,500 ms | **172/172** | **184/184** | **6/6** | 649 s | +812 ms |
| `E3-4000` | 4,000 ms | **172/172** | **184/184** | **6/6** | 934 s | +1,169 ms |
| (round one) | 8,000 ms | 170/171 | 182/182 | 6/7 | 1,550 s | +2,047 ms |

**The coverage is identical and the cost is not.** Both arms accept exactly the same 18 requests over 9
cases, 16 of them strict, as the 8,000 ms arm did; both gain +8 LNN_ONLY and +2 LNN_FIRST in both blocks
with zero losses anywhere; both recover 6 of the 18 group-B cases and take the crawling group from 12 to 14.
Cutting the wall from 8,000 ms to 2,500 ms costs nothing in coverage and returns 58% of the handoff time.

**Nothing accepted is lost to the shorter wall, and this is not an inference.** The slowest handoff round one
ever accepted took 2,255 ms in LNN_ONLY and 2,264 ms in LNN_FIRST; **no accepted handoff anywhere in round
one exceeded 2,500 ms**, so the 2,500 ms wall has nothing to cut. Measured directly here, the slowest
accepted handoff is 2,223 ms at the 2,500 ms wall and 2,210 ms at 4,000 ms. One case round one accepted,
`gd-s31-w0-p2-d1-r00`, is not accepted in round four — not because of the wall but because the adopted
contraction factor now solves it from the seed, so no handoff is ever armed for it — and one case round one
did not accept, `gd-s59-w1-p2-d0-r01`, is accepted here.

**Neither arm is adopted, by the rule registered before the round ran.** Both pass the strict-gain gate
(FIRST 184 against 182 in both blocks) and the classical-union gate, and both fail the latency gate: +812 ms
and +1,169 ms against a band of 23.3 ms, this round's baseline having reproduced itself unusually closely
between blocks. `Recovery.RAMP_HANDOFF` therefore stays opt-in and `NONE` stays the default, which is where
it was already.

What the round establishes is the price. If the handoff is ever enabled — offline, batch, or behind a
user-facing setting — **2,500 ms is the sub-wall to enable it at**: same coverage as 8,000 ms for 40% of the
time. That is a recommendation about the parameter, not about the default.
