# Round four: how short can the ramp handoff's sub-wall be?

Registered before round four ran. It extends `protocol.md`, which is sealed by the registrations of rounds
one to three and is not edited; everything not restated here — population, entry point, strict definition,
ten workers, thirty-second deadline, two reversed-order blocks, the parity gate, the target groups and the
limits — is that document's, unchanged.

## 1. The question

Round one measured the ramp handoff (`Recovery.RAMP_HANDOFF`) at an 8,000 ms sub-wall. It won the most
coverage of anything in the study — LNN_ONLY 170 and 171 of 330 against the baseline's 162, six and seven of
the eighteen group-B cases, nothing lost anywhere — and it was the only arm that failed the latency gate
badly, at +2,047 ms on the pooled all-case LNN_FIRST mean.

The cost is concentrated in refusals, not in successes. Of 308 armed LNN_ONLY requests it accepted 18. When
it closed it spent a mean of 1,260 ms and a maximum of 2,255 ms; when it refused it spent a mean of 5,265 ms
and a **median of 6,885 ms**, which is most of the sub-wall. A shorter wall should therefore cost most of
the refusals and little of the coverage — unless some accepted handoff needed the time, which is exactly
what the maximum of 2,255 ms makes worth measuring rather than assuming.

## 2. The arms

Three arms, 330 inputs x 3 modes x 2 reversed-order blocks, ten owned worker threads. All three carry the
adopted `Correction.PROGRESS` — the E1 selection, contraction factor **1.0** — because that is what
production now ships and what any further intervention has to beat.

| Arm | correction | recovery | sub-wall |
|---|---|---|---|
| `baseline` | adopted (8, 48, 8, **1.0**, 8, 0.9, 1e-6) | NONE | — |
| `E3-2500` | adopted | RAMP_HANDOFF | **2,500 ms** |
| `E3-4000` | adopted | RAMP_HANDOFF | **4,000 ms** |

2,500 ms sits just above the slowest handoff round one accepted (2,255 ms), so it is the shortest wall that
could in principle keep every accepted case. 4,000 ms is the midpoint between that and the registered 8,000
ms, and is there so a loss at 2,500 ms can be attributed to the wall rather than to jitter.

The sub-wall is now an option — `V3InitializationOptions.recoveryBudgetMilliseconds()` — rather than the
constant round one used, defaulting to the same 8,000 ms. Nothing else about the handoff moves: the same
eligibility rule, the same surrogate, the same unchanged ramp, the same publication path.

## 3. Adoption rule, pre-declared

An arm is adopted **only if it passes the same three gates** as every other arm in this study, evaluated
against this round's own baseline:

1. a strict LNN_FIRST gain against the baseline in **both** blocks;
2. preservation of the contemporaneous classical strict union, in both blocks;
3. no pooled mean LNN_FIRST latency regression beyond the noise band, where the band is this round's
   baseline's own between-block spread in that mean.

Passing authorises nothing by itself: `Recovery.RAMP_HANDOFF` stays opt-in, `NONE` stays the default, and
whether to ship it is a decision taken outside this study. If both arms pass, the shorter wall is reported
first, because the study has no registered rule for preferring one over the other and will not invent one
after seeing the numbers.

## 4. Reported whatever the verdict

- strict counts per arm, per block, per mode, over 330;
- paired gains and losses against the baseline, as identities;
- group-B and group-C recovery per arm;
- handoff spend per mode, split into armed, accepted and refused;
- the all-case LNN_FIRST mean against the band;
- **whether any handoff that round one accepted above 2,500 ms is lost here.** Round one accepted 18
  requests, 9 distinct cases; their per-request spend is in `evidence/v1-validation-analysis.json`. A case
  that round one accepted slowly and this round does not accept is the direct cost of the shorter wall, and
  is listed by id rather than counted.

## 5. Limits, in addition to `protocol.md` section 9

1. The baseline differs from rounds one to three by the adopted contraction factor, so this round's numbers
   are not directly comparable to theirs; only within-round pairings are.
2. Two sub-walls are two points, not a curve. Nothing here says the response is monotone between them, and
   the study does not interpolate.
3. A handoff refused at a shorter wall is not proof that a longer one would have closed it: the refusal may
   be the ramp, not the clock. The comparison against round one's accepted set is what separates the two,
   and only for the cases round one accepted.
