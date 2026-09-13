# Neural correction budget results on the frozen F0 weights

**Both interventions work, and they work on different columns.** Reshaping the correction budget from a
hard sixteen-iteration wall into a progress rule raises strict neural-first success from 168 to **176**;
seeding a zero-decoded phase at its support floor raises it to **171**; together they reach **180**, in
both blocks, with the classical strict union preserved and no pooled mean neural-first latency regression.
All three variants pass the study's gates. This is the first strict neural-first gain any follow-up in this
line has produced.

The campaign contains 9,720 measured corrected-column requests plus 48 TRAIN warmups, and the bounded
diagnostic that chose the interventions contains 432 more. All four pipelines load the identical archived
F0 weight document `F-20260911-s4160`, SHA-256
`7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962`. No weight byte, tolerance, support
rule, acceptance audit or production default changed. Every threshold, the two candidate interventions and
the rule that chose between them were registered before the first measurement: see
[protocol.md](protocol.md).

## What the archive could not say, and now does

The archive records 75 neural-only failures as "neural budget exhausted" with **zero** Newton iterations.
That is a reporting artefact. The budget throws out of whatever the correction was doing, the candidate
loop then has no failure of its own, and the published outcome carried a zero placeholder. The calculator
now keeps the interrupted attempt's completed iterations, its last maximum scaled residual and its attempt
and refresh counts, and publishes them instead. No outcome, budget, tolerance or digest moves — the
baseline pipeline reproduces the archived strict identity sets exactly, which is the gate that measures
that claim.

## Bounded diagnostic

216 cases — the union of the 141 iteration-capped, 80 time-capped and 34 classical-only-loss identities
derived from the decoder-floor campaign's committed baseline evidence — traced under two configurations
that differ only in the two walls. 214 of the 216 are capped.

| Configuration | Iterations | Allowance | Strictly converged | Run |
| --- | ---: | ---: | ---: | ---: |
| production | 16 | 2,000 ms | 0 of 216 | 28.9 s |
| diagnostic | 48 | 6,000 ms | 20 of 216 | 68.1 s |

### Classification of the capped population (214 cases)

| Class | Cases | Share | Rule |
| --- | ---: | ---: | --- |
| crawling | 20 | 9.3% | converges only under the diagnostic walls |
| stalled | 137 | 64.0% | contraction ratio above 0.5 and never converges |
| hopeless | 112 | 52.3% | contraction ratio above 0.9 and never converges |
| reinserting | 124 | 57.9% | spent a refresh and omits a reference entry or decodes a zero phase |

Median decoded zero-phase count over the capped population: 1. All three declared rules fired — at least
10 crawling cases (20), at least 25% stalled (64.0%), at least 25% reinserting with a median zero-phase
count of at least 1 (57.9%, median 1) — so A and B were both implemented and the campaign also carried
their combination.

### The three registered groups, separately

| Group | Cases | crawling | stalled | hopeless | reinserting | unattributed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| iteration-capped | 141 | 20 | 97 | 79 | 69 | 9 |
| time-capped | 80 | **0** | 46 | 37 | 60 | 8 |
| classical-only loss | 34 | 7 | 21 | 19 | 20 | 1 |

**No time-capped case crawls.** Tripling both walls converts none of the 80, which says the two-second
allowance is not what holds them back: they are expensive columns whose corrections do not converge at any
budget this study measured. The campaign confirmed the prediction exactly — the progress rule recovers 0
of the 80 time-capped cases and 19 of the 141 iteration-capped ones.

Every traced case runs exactly two Newton attempts with exactly one support refresh (211 of 216), so the
refresh is universal rather than diagnostic; the "reinserting" class is carried almost entirely by its
seed-omission half. The fixed-water wet prepass never ran on these cases.

### Measured per-iteration correction cost

| Statistic | ms per Newton iteration |
| --- | ---: |
| p05 / median / p95 | 6.5 / 41.3 / 95.8 |
| mean / max | 47.0 / 144.4 |

Per case: median 1,322 ms of a 2,000 ms allowance, p95 2,008 ms.

### The declared net-time ladder

Ladder step 1 (extension cap 48, contraction factor 0.5, wall unchanged at 2,000 ms) pays for itself by a
wide margin at each case's own measured per-iteration cost: predicted extension cost 8.7 s against
predicted abort saving 92.3 s over the capped population, or **+40 ms against −431 ms per case**. It was
registered without descending the ladder.

**No wall variant was run.** A 2,500 ms or 3,000 ms wall also satisfies the saving-covers-cost rule, but
the measured reach says it would buy almost nothing: 16 of the 20 crawling cases complete inside 2,000 ms,
17 inside 2,500 ms, and 17 inside 3,000 ms. One extra reachable case does not justify a fifth pipeline, and
the campaign's +19 neural-only gain for the progress arm against the 16 predicted reachable cases shows the
prediction was, if anything, conservative.

## Campaign

### Harness parity

| Check | Result |
| --- | --- |
| Decoded seeds, all 405 inputs, versus both archived F0 journals | 0 of 405 mismatched, both blocks |
| Strict classical identity set versus archive | identical, both blocks, 110 cases |
| Strict ONLY identity set versus archive | identical, both blocks, 134 cases |
| Strict FIRST identity set versus archive | identical, both blocks, 168 cases |

The native core was rebuilt from 114 sources with only Gson on the compiler classpath. Against the sealed
trace-followup registration archive, 100 sources matched byte for byte, five differ only in checkout line
endings, and six differ in content, every one of them named in the study's declared allowlist: the
calculator, the Newton trace, the solver, the initialization options, the decoder and the offline
Transformer initializer. Java 21.0.11, sixteen available processors, ten owned worker threads per run.
The 110 classical strict successes are the same 110 cases in all eight runs.

### What each intervention changed before any solver ran

| Pipeline | Cases whose seed changes | Entries seeded above zero | Phases seeded above zero | Above-floor omissions on the 168 references |
| --- | ---: | ---: | ---: | ---: |
| F0-baseline | - | - | - | 819 |
| F0-progress | 0 of 405 | 0 | 0 | 819 |
| F0-phase-floor | 170 of 405 | 10,650 | 1,244 | **249** |
| F0-progress-phase-floor | 170 of 405 | 10,650 | 1,244 | **249** |

The phase floor is the first decoder change in this line that moves the omission count at all: 819 to 249,
a 70% reduction. The decoder-floor study measured why its own rule could not — 765 of F0's 819 omissions
are whole phases the phase-total head decoded as zero, and a rule that fires only on components the
presence head kept cannot reach them. This one moves the phase total, which is exactly where they are. The
progress rule is solver-side and leaves every decoded seed bit for bit identical, as it must.

### Complete validation counts and costs

FIRST includes classical fallback; ONLY is a separately executed neural-only request. All counts require
the unchanged strict native audit and the final Newton certificate. Costs include failed and advisory
requests.

| Pipeline | Block | Classical | ONLY | FIRST | FIRST advisory | FIRST failed | FIRST mean ms | ONLY mean ms | Run s |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| F0-baseline | 1 | 110 | 134 | 168 | 9 | 228 | 3771.8 | 800.9 | 372.1 |
| F0-baseline | 2 | 110 | 134 | 168 | 10 | 227 | 3549.6 | 764.2 | 353.4 |
| F0-progress | 1 | 110 | 149 | 176 | 9 | 220 | 3589.0 | 787.8 | 364.5 |
| F0-progress | 2 | 110 | 150 | 176 | 9 | 220 | 3355.9 | 749.2 | 343.7 |
| F0-phase-floor | 1 | 110 | 147 | 171 | 11 | 223 | 3735.4 | 864.8 | 371.1 |
| F0-phase-floor | 2 | 110 | 147 | 171 | 13 | 221 | 3433.1 | 817.9 | 345.5 |
| F0-progress-phase-floor | 1 | 110 | 163 | 180 | 12 | 213 | 3590.0 | 874.8 | 368.6 |
| F0-progress-phase-floor | 2 | 110 | 164 | 180 | 12 | 213 | 3411.8 | 844.7 | 351.6 |

### Paired gains and losses against F0-baseline

| Candidate | Mode | Block 1 | Block 2 | Identities reproduced |
| --- | --- | --- | --- | --- |
| F0-progress | ONLY | +19 / -4 | +19 / -3 | gains yes, losses no |
| F0-progress | FIRST | +10 / -2 | +10 / -2 | yes |
| F0-phase-floor | ONLY | +16 / -3 | +16 / -3 | yes |
| F0-phase-floor | FIRST | +6 / -3 | +6 / -3 | yes |
| F0-progress-phase-floor | ONLY | +36 / -7 | +36 / -6 | gains yes, losses no |
| F0-progress-phase-floor | FIRST | +17 / -5 | +17 / -5 | yes |

Every gained and lost identity is reproduced in both blocks except one: `gd-s59-w0-p1-d0-r00` is a
neural-only loss for the progress arm in block 1 and not in block 2. It is a 59-stage case sitting on the
two-second wall in both, so it is wall jitter rather than a changed failure mode. All FIRST sets are
reproduced exactly.

**The FIRST gains are nearly additive because the two interventions act on different columns.** The
progress arm's gains are at 45 to 59 stages — neural-only 6 to 14 at 52 stages, 4 to 7 at 59, 5 to 8 at 45
— which is precisely the regime the review measured the neural arm to be net-negative in. The phase
floor's gains are at 3 to 24 stages — neural-only 30 to 36 at three stages. Their intersection is small,
so 19 and 16 neural-only gains compose to 36.

FIRST gains (both blocks):

- **F0-progress** `gd-s03-w1-p4-d2-r00`, `gd-s24-w1-p1-d1-r00`, `gd-s45-w0-p3-d0-r00`,
  `gd-s52-w0-p0-d3-r00`, `gd-s52-w0-p4-d2-r00`, `gd-s52-w1-p0-d3-r00`, `gd-s52-w1-p3-d1-r00`,
  `gd-s52-w1-p4-d0-r00`, `gd-s59-w1-p0-d1-r00`, `gd-s59-w1-p3-d2-r00`; losses `gd-s31-w1-p2-d0-r00`,
  `gd-s38-w0-p1-d1-r00`.
- **F0-phase-floor** `gd-s03-w0-p2-d0-r00`, `gd-s03-w0-p2-d1-r00`, `gd-s10-w0-p1-d0-r00`,
  `gd-s45-w0-p2-d1-r00`, `gd-s45-w0-p4-d1-r00`, `gd-s59-w0-p2-d1-r00`; losses `gd-s10-w0-p3-d1-r00`,
  `gd-s17-w0-p2-d3-r00`, `gd-s45-w1-p4-d2-r00`.
- **F0-progress-phase-floor** the union of the two gain sets plus `gd-s38-w1-p4-d1-r00` and
  `gd-s45-w0-p0-d3-r00`; losses are the union of the two loss sets.

**No FIRST loss is a classical strict case**, which is why the classical union is preserved in all eight
runs: each lost identity is a column the baseline's neural pass won and the candidate's does not, and that
the classical solver does not take either. They are net losses of neural-owned coverage, not fallback
failures.

### Strict counts by regime, block 1, neural-only / neural-first

| Heat loops | n | baseline | progress | phase-floor | both |
| ---: | ---: | --- | --- | --- | --- |
| 0 | 81 | 33/47 | 37/50 | 38/47 | 43/51 |
| 1 | 87 | 28/36 | 30/36 | 32/37 | 34/37 |
| 2 | 78 | 27/33 | 29/32 | 30/36 | 31/34 |
| 3 | 76 | 25/28 | 28/31 | 25/27 | 28/30 |
| 4 | 83 | 21/24 | 25/27 | 22/24 | 27/28 |

| Stages | n | baseline | progress | phase-floor | both |
| ---: | ---: | --- | --- | --- | --- |
| 3 | 46 | 30/34 | 31/35 | 36/36 | 37/37 |
| 10 | 46 | 26/26 | 26/26 | 26/26 | 26/26 |
| 17 | 46 | 17/24 | 19/24 | 19/23 | 21/23 |
| 24 | 42 | 12/14 | 13/15 | 14/14 | 15/15 |
| 31 | 44 | 15/18 | 14/17 | 15/18 | 14/17 |
| 38 | 44 | 19/20 | 17/19 | 19/20 | 18/20 |
| 45 | 44 | 5/10 | 8/11 | 7/11 | 10/12 |
| 52 | 48 | 6/13 | 14/18 | 6/13 | 14/18 |
| 59 | 45 | 4/9 | 7/11 | 5/10 | 8/12 |

31 and 38 stages are the only bands where the progress arm loses ground, and they hold all four of its
neural-only losses.

### Costs on stable common successes

Cases strict under both pipelines in both blocks.

| Comparison | Mode | Cases | Reference mean / median ms | Candidate mean / median ms | Paired mean / median ms |
| --- | --- | ---: | --- | --- | --- |
| F0-progress | ONLY | 130 | 173.0 / 103.6 | 212.8 / 124.1 | +39.80 / +7.54 |
| F0-progress | FIRST | 166 | 738.9 / 156.7 | 705.4 / 209.5 | -33.49 / +4.65 |
| F0-phase-floor | ONLY | 131 | 183.6 / 104.5 | 179.7 / 103.7 | -3.92 / -2.14 |
| F0-phase-floor | FIRST | 165 | 737.8 / 159.3 | 566.7 / 127.5 | -171.10 / -3.98 |
| F0-progress-phase-floor | ONLY | 127 | 165.7 / 102.2 | 211.1 / 122.4 | +45.38 / +7.47 |
| F0-progress-phase-floor | FIRST | 163 | 743.2 / 156.7 | 560.7 / 176.2 | -182.52 / +3.75 |

The paired column is the one to read: it is the same case under both pipelines, and every paired median is
within 8 ms of zero. The progress arm costs about 7 ms of median neural-only time on a case that was going
to succeed anyway — the price of the contraction test — and the phase floor costs nothing measurable. The
reference and candidate medians of the two FIRST rows drift much further apart than their paired median
does, because the set medians move when a few slow fallbacks move; that is a property of the distribution,
not a per-case cost.

### Costs and iterations on stable common failures

Cases non-strict under both pipelines in both blocks. This is where a reshaped budget acts first.

| Comparison | Mode | Cases | Reference mean ms | Candidate mean ms | Paired mean ms | Mean iterations |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| F0-progress | ONLY | 252 | 1106.3 | 1055.1 | **-51.2** | 11.24 -> 9.80 |
| F0-progress | FIRST | 227 | 5719.6 | 5610.2 | **-109.4** | 18.81 -> 18.89 |
| F0-phase-floor | ONLY | 255 | 1092.0 | 1217.2 | +125.2 | 11.43 -> 12.84 |
| F0-phase-floor | FIRST | 231 | 5761.2 | 5807.9 | +46.7 | 19.26 -> 19.56 |
| F0-progress-phase-floor | ONLY | 235 | 1121.6 | 1252.7 | +131.1 | 11.00 -> 11.01 |
| F0-progress-phase-floor | FIRST | 220 | 5738.3 | 5872.3 | +134.1 | 18.98 -> 19.07 |

The progress rule does what it was designed to do: it spends fewer iterations on a case it is going to lose
(11.24 to 9.80) and returns 51 ms of neural-only time and 109 ms of neural-first time per lost case. The
phase floor moves the other way — a fuller support is more expensive per iteration, and it buys its
successes at 125 ms per remaining failure. Combined, the abort no longer covers the floor's extra cost on
failures, and the combination's all-case advantage comes entirely from the twelve extra successes.

### Pooled all-case neural-first means, and what they are worth

| Pipeline | Pooled FIRST mean ms | Against baseline |
| --- | ---: | ---: |
| F0-baseline | 3660.7 | - |
| F0-progress | 3472.4 | -188.3 |
| F0-phase-floor | 3584.3 | -76.4 |
| F0-progress-phase-floor | 3500.9 | -159.8 |

**Treat these as gate arithmetic, not a speed result.** The baseline's own two blocks differ by 222 ms
(3771.8 against 3549.6) on identical inputs and identical seeds, which is wider than two of the three
differences in the table and comparable to the third. The durable timing claims of this campaign are the
paired ones above, measured case by case on the same identities.

### Stop-phrase evidence, neural-only, block 1 / block 2

| Pipeline | iteration_limit | neural_time_limit | no_admissible_step | unattributed |
| --- | --- | --- | --- | --- |
| F0-baseline | 150 / 156 | 61 / 51 | 18 / 20 | 42 / 44 |
| F0-progress | 32 / 33 | 66 / 52 | 13 / 13 | 145 / 157 |
| F0-phase-floor | 155 / 166 | 69 / 54 | 15 / 17 | 19 / 21 |
| F0-progress-phase-floor | 32 / 32 | 73 / 66 | 15 / 15 | 122 / 128 |

The progress arm's iteration cap all but disappears — 150 to 32 — and the unattributed column absorbs the
difference, because **the early stop has no stop phrase in the shared analysis vocabulary**. A stalled
attempt terminates with "maximum scaled residual X at iteration Y and Z now", which matches none of the
four patterns `analysis_common.mode_evidence` looks for. Counted directly from the journals, block 1 has
103 such stops for `F0-progress` and 102 for the combination, against 0 for the baseline and the phase
floor; the rest of each unattributed column is the same handful of seed rejections and property-domain
stops the baseline also has. This is an accounting gap in a shared read-only module, not a new failure
mode, and the next study to touch `analysis_common` should add the pattern.

One other movement is worth naming: the baseline's 29 neural-only requests that end in `neural seed
rejected: IllegalArgumentException` fall to **zero** under the phase floor. A seed whose phase decodes
empty can fail the corrector's own admission before a single Newton step; filling it at the support floor
makes those seeds admissible.

### The registered budget-wall groups after the intervention

Neural-only strict recoveries, from zero by construction, block 1 / block 2:

| Group | Cases | F0-progress | F0-phase-floor | Both |
| --- | ---: | ---: | ---: | ---: |
| iteration-capped | 141 | 19 / 19 | 13 / 13 | 33 / 33 |
| time-capped | 80 | **0 / 0** | 2 / 2 | 3 / 3 |
| classical-only loss | 34 | 9 / 9 | 10 / 10 | 19 / 19 |

Mean neural-only Newton iterations on the iteration-capped group fall from 16.00 (every case at the cap, by
construction) to 10.67 under the progress rule and 9.03 under the combination, and mean cost falls from 917
ms to 851 and 850 ms. On the classical-only losses the combination recovers 19 of 34 neural-only, at a mean
of 5.1 iterations against the baseline's 15.0 — these are the cases the review identified as sitting at the
cap while classical needed a median of three steps after its continuation, and more than half of them turn
out to have needed a support the decoder had emptied, more iterations, or both.

## Verdict

| Candidate | Strict FIRST both blocks | Classical union preserved | No pooled mean FIRST latency regression | Passes |
| --- | --- | --- | --- | --- |
| F0-progress | 176 / 176 against 168 / 168 | yes | yes, -188.3 ms | **yes** |
| F0-phase-floor | 171 / 171 against 168 / 168 | yes | yes, -76.4 ms | **yes** |
| F0-progress-phase-floor | 180 / 180 against 168 / 168 | yes | yes, -159.8 ms | **yes** |

Recommendation R2 is supported, and recommendation R3 is supported in the form the decoder-floor study's
own measurement pointed at rather than the form it tested. Passing authorises further qualification only;
nothing is promoted, and the production defaults are unchanged.

What should be qualified next, in order: whether the progress rule's four neural-only losses at 31 and 38
stages are a real regression or wall jitter; whether the phase floor's factor of ten is anywhere near
optimal, since this campaign measured exactly one value of it; and whether the 80 time-capped cases, which
no budget in this study reached, are the liquid-shortage wall already documented elsewhere rather than an
initializer problem at all.

## Evidence and limits

Historical validation, not a fresh holdout; the 168 certified references are a subset of the 405 evaluation
inputs. The traced diagnostic groups are outcome-selected and are not a population benchmark, and the
diagnostic configuration is not a candidate: nothing here proposes shipping 48 iterations and six seconds.
Repeated blocks reuse identical inputs, so their spread describes case and load variability, not a
confidence interval over independent campaigns. Above-floor omissions are decode diagnostics against the
certified references and do not prove that a reference phase is necessary at every admissible root.
Advisory successes are excluded from every strict count and no failed input leaves the denominator; the
phase floor raises advisory neural-first outcomes from 9 or 10 to 11 to 13, which is reported and not
counted.

Eight derived statistics in the analysis are published as null: the sample standard deviation of the
`SIDE_DRAW_SPLIT` audit values on the phase-floor pipelines' neural-only requests overflows a double,
because one rejected candidate reports a ratio of about 2e254 against a limit of 1. The values themselves
are finite and retained, every such check failed, and no strict outcome depends on them. The baseline's
largest value on the same check is 3e8, so a fuller seed support does make that ratio wilder on candidates
that are rejected anyway.

Per-case outcome, status, cost and stop evidence for all eight runs, the reference profile evidence, the
per-case decoded-seed digests and the per-case correction trajectories of the bounded diagnostic are
committed gzipped under `evidence/` (0.67 MiB). The complete journals, decoded seeds and traced profiles
stay under `build/neural-budget/v1` and `build/neural-budget/v2`;
[cache-manifest.json](cache-manifest.json) binds them by hash. The machine-readable verdict is
[summary.json](summary.json).
