# Liquid depletion at authored side draws: counts, causes, and a request-only screen

Read-only analysis of four journals (8,712 classical-solver requests plus a 312-request three-lane
comparison). Scripts and machine-readable numbers live beside this file:

| file | what it is |
|---|---|
| `extract.js` | streams the raw journals into compact per-request records (`extracted-*.jsonl`) |
| `analyze.py` | every count, sweep and fit reported here; writes `summary.json` and `tables.md` |
| `thermo.py` | Lee-Kesler vapour pressure + ideal flash, read from the shipped component property files |
| `explore_calibration.py`, `explore_separability.py`, `explore_rule.py`, `explore_flash.py` | the search that led to the recommended statistic |
| `render_summary.py` | renders this file |
| `summary.json` | all numbers quoted below |
| `tables.md` | the full generated table set (this file embeds the important ones) |

---

## 1. Headline

**Counts.** Of 6,452 requests that authored at least one side draw, 451 reached a final state whose
worst side draw took 80 % or more of the liquid on its tray, and 329 took 100 % or more.

| journal | draw requests | solved | hard `W>=1` | near `0.8<=W<1` | family | of the near band, solved | withdrawal not measurable | family / draw failures with a measured W |
|---|---|---|---|---|---|---|---|---|
| design-v2 | 4,200 | 1,440 | 221 | 69 | 290 | 21 | 1,836 | 29.1 % |
| design-g | 1,680 | 587 | 68 | 38 | 106 | 15 | 740 | 25.8 % |
| codex | 572 | 180 | 40 | 15 | 55 | 9 | 264 | 35.9 % |
| **pooled** | **6,452** | **2,207** | **329** | **122** | **451** | **45** | **2,840** | **29.4 %** |

**Recommended rule.** Add one request-only statistic, `sigma`, to the design generator and redraw any
request that exceeds the threshold. `sigma` is the worst, over the authored draw trays, of the
cumulative withdrawal at and above the tray divided by the internal liquid the request's own energy
input can put there:

```
lambda = 60_000 J/mol                       # fixed screening latent heat
vf     = ideal vapour fraction of the authored feed at the feed stage (one flash, no solve)
V_gen  = max(0, Q / lambda + vf * F)        # reboiler boil-up plus the feed's own vapour; steam is NOT credited
L(T)   = R/(R+1) * V_gen                    # reflux liquid, R = authored organic reflux ratio
       + coolingAbove(T) / lambda           # authored pumparound cooling on trays 1..T, exactly as V3LiquidSupplyScreen computes it
       + (1 - vf) * F   if T >= feedStage   # the feed's own liquid, below the feed tray
sigma  = max over draw trays T of  ( sum of authored draws on trays 1..T ) / L(T)
reject if sigma >= 0.66
```

Measured on the 2,613 pooled draw requests whose final withdrawal is known:

| operating point | hard `W>=1` caught | near-depletion failures caught | false positives on solved requests |
|---|---|---|---|
| `sigma >= 0.66` (recommended, FP <= 0.5 %) | 74 / 329 = 22.5 % | 11 / 77 = 14.3 % | 11 / 2,207 = **0.50 %** |
| `sigma >= 0.55` (best under FP <= 2 %) | 116 / 329 = 35.3 % | 18 / 77 = 23.4 % | 45 / 2,207 = 2.04 % |
| `sigma >= 0.82` (zero measured false positives) | 38 / 329 = 11.6 % | 2 / 77 = 2.6 % | 0 / 2,207 = 0.00 % |

For comparison the shipped `V3LiquidSupplyScreen` statistic `rho` reaches only 4.6 % hard recall at
the same 0.5 % false-positive budget, and fires on **nothing** in design-v2/design-g because those
matrices were redrawn until `rho < 0.30` (measured maxima 0.29998 and 0.29767). `sigma` is a strict
improvement on the same kind of evidence: AUC 0.840 against solved requests versus 0.740 for `rho`.

On the untouched 312-request holdout (252 draw requests, three solver lanes) `sigma >= 0.66` fires on
14 requests, catches 9 of the 42 whose worst lane hit `W>=1`, and hits 2 of the 101 requests some lane
solved strictly (2.0 %, consistent with a 0.5 % rate at this sample size:
P(X>=2 | n=101, p=0.005) is about 9 %).

**Residual.** `sigma >= 0.66` would have removed 411 of the 6,452 draw requests as authored, of which
87 are family members, 11 are solvable, and 247 have no measurable withdrawal. The family left behind
is 234 / 290 (design-v2), 84 / 106 (design-g), 46 / 55 (codex). **The rule is a useful filter, not a
solution: it removes roughly a fifth of the family for half a percent of the solvable population.**

---

## 2. The one caveat that changes how every count should be read

`SIDE_DRAW_SPLIT` is an *acceptance audit* with limit 1. A request can only be reported successful if
its converged state already satisfies `W < 1`. So "0 % success at `W >= 1`" (table T3 below) is not a
measurement of solvability - it is the definition of the gate. What `W >= 1` actually says is: *the
last state this solve attempt reached had a non-positive downflow under the authored draw.*

Two pieces of evidence bound how much of the hard band is a genuine design defect:

* Only **27 of the 329** hard cases (14 / 7 / 6 per journal) carry solver-side proof: status
  `ACCEPTANCE_AUDIT_FAILURE`, i.e. the Newton iteration *converged* to a MESH state whose residuals met
  tolerance and which was then rejected because the draw exhausted the tray liquid. The other 302 are
  `NONCONVERGENCE` (or `LINEAR_SOLVE_FAILURE`), where `W` is a property of a diverged iterate - the
  withdrawal distribution runs to 5.2e42, which no physical state can carry.
* In the 312-request holdout the classification depends on which lane you read (table T10). Reading
  the worst of the three lanes gives 42 hard cases; reading the lane that actually solved gives 25, and
  **5 of the 42 were solved strictly by some lane**, so at least 12 % of "hard" requests are solvable
  and the label came from a diverged lane.

Everything below reports the family as measured, and separately flags the 27 proven cases.

---

## 3. Task 1 - counts and breakdowns

### T1 Family per journal

| journal | requests | draw requests | draw successes | draw failures | hard >=1 | near 0.8-1 | family | near that solved | unknown W | converged + audit-rejected | family / all draw failures | family / draw failures with known W |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| design-v2 | 5400 | 4200 | 1440 | 2760 | 221 | 69 | 290 | 21 | 1836 | 14 | 9.7% | 29.1% |
| design-g | 2160 | 1680 | 587 | 1093 | 68 | 38 | 106 | 15 | 740 | 7 | 8.3% | 25.8% |
| codex | 1152 | 572 | 180 | 392 | 40 | 15 | 55 | 9 | 264 | 6 | 11.7% | 35.9% |

"unknown W" = draw requests with neither a `SIDE_DRAW_SPLIT` audit entry nor a parsable withdrawal in
the failure text: heat-gated condensation caps, deadline-exceeded solves, typed-infeasible requests.
Because they are 44 % of the draw population, every family count below is a **lower bound**.

### By number of side draws

| journal | draws | draw reqs | succ rate | hard | near | family / draw reqs | family / known W |
|---|---|---|---|---|---|---|---|
| design-v2 | 1 | 1200 | 35.4% | 32 | 7 | 3.2% | 6.1% |
| design-v2 | 2 | 1226 | 27.5% | 73 | 27 | 8.2% | 16.3% |
| design-v2 | 3 | 1774 | 38.2% | 116 | 35 | 8.5% | 13.5% |
| design-g | 1 | 480 | 36.5% | 7 | 4 | 2.3% | 4.6% |
| design-g | 2 | 490 | 25.5% | 24 | 15 | 8.0% | 16.0% |
| design-g | 3 | 710 | 40.4% | 37 | 19 | 7.9% | 12.3% |
| codex | 1 | 188 | 39.4% | 6 | 3 | 4.8% | 7.9% |
| codex | 2 | 192 | 28.6% | 13 | 5 | 9.4% | 19.1% |
| codex | 3 | 192 | 26.6% | 21 | 7 | 14.6% | 28.0% |

### By stage-count band

| journal | stages | draw reqs | succ rate | hard | near | family / draw reqs | family / known W |
|---|---|---|---|---|---|---|---|
| design-v2 | 2-9 | 480 | 33.8% | 22 | 9 | 6.5% | 15.0% |
| design-v2 | 10-19 | 551 | 41.6% | 46 | 22 | 12.3% | 21.7% |
| design-v2 | 20-34 | 918 | 37.0% | 67 | 17 | 9.2% | 15.6% |
| design-v2 | 35-49 | 1434 | 40.8% | 52 | 18 | 4.9% | 7.4% |
| design-v2 | 50-64 | 817 | 15.2% | 34 | 3 | 4.5% | 10.1% |
| design-g | 2-9 | 201 | 31.8% | 10 | 6 | 8.0% | 20.3% |
| design-g | 10-19 | 208 | 39.9% | 16 | 3 | 9.1% | 17.0% |
| design-g | 20-34 | 349 | 38.7% | 19 | 13 | 9.2% | 15.2% |
| design-g | 35-49 | 587 | 42.8% | 9 | 11 | 3.4% | 5.2% |
| design-g | 50-64 | 335 | 16.1% | 14 | 5 | 5.7% | 12.5% |
| codex | 2-9 | 72 | 38.9% | 5 | 1 | 8.3% | 17.1% |
| codex | 10-19 | 90 | 33.3% | 11 | 3 | 15.6% | 30.4% |
| codex | 20-34 | 136 | 45.6% | 13 | 4 | 12.5% | 19.8% |
| codex | 35-49 | 140 | 27.1% | 6 | 4 | 7.1% | 13.7% |
| codex | 50-64 | 134 | 16.4% | 5 | 3 | 6.0% | 11.8% |

### By steam

| journal | steam | draw reqs | succ rate | hard | near | family / draw reqs | family / known W |
|---|---|---|---|---|---|---|---|
| design-v2 | dry | 1800 | 26.6% | 85 | 25 | 6.1% | 13.7% |
| design-v2 | steam | 2400 | 40.0% | 136 | 44 | 7.5% | 11.5% |
| design-g | dry | 720 | 26.1% | 25 | 19 | 6.1% | 14.5% |
| design-g | steam | 960 | 41.6% | 43 | 19 | 6.5% | 9.7% |
| codex | dry | 283 | 27.9% | 18 | 10 | 9.9% | 21.2% |
| codex | steam | 289 | 34.9% | 22 | 5 | 9.3% | 15.3% |

### By pumparound count

| journal | pumparounds | draw reqs | succ rate | hard | near | family / draw reqs | family / known W |
|---|---|---|---|---|---|---|---|
| design-v2 | 0 | 720 | 43.5% | 62 | 21 | 11.5% | 14.4% |
| design-v2 | 1 | 720 | 34.9% | 46 | 17 | 8.8% | 14.1% |
| design-v2 | 2 | 720 | 27.6% | 37 | 10 | 6.5% | 13.6% |
| design-v2 | 3 | 1335 | 41.2% | 47 | 13 | 4.5% | 7.9% |
| design-v2 | 4 | 705 | 18.0% | 29 | 8 | 5.2% | 15.7% |
| design-g | 0 | 288 | 43.1% | 22 | 14 | 12.5% | 16.1% |
| design-g | 1 | 288 | 39.2% | 19 | 8 | 9.4% | 14.0% |
| design-g | 2 | 288 | 27.4% | 11 | 5 | 5.6% | 12.9% |
| design-g | 3 | 534 | 42.1% | 11 | 8 | 3.6% | 6.1% |
| design-g | 4 | 282 | 16.3% | 5 | 3 | 2.8% | 9.4% |
| codex | 0 | 111 | 34.2% | 12 | 7 | 17.1% | 23.2% |
| codex | 1 | 112 | 38.4% | 10 | 0 | 8.9% | 13.9% |
| codex | 2 | 114 | 34.2% | 10 | 2 | 10.5% | 19.7% |
| codex | 3 | 124 | 27.4% | 3 | 4 | 5.6% | 14.3% |
| codex | 4 | 111 | 23.4% | 5 | 2 | 6.3% | 15.9% |

### By design.family and by split

| journal | design.family | draw reqs | succ rate | hard | near | family / known W |
|---|---|---|---|---|---|---|
| design-v2 | factorial | 3600 | 29.2% | 211 | 64 | 13.4% |
| design-v2 | neighbourhood | 600 | 65.0% | 10 | 5 | 2.7% |
| design-g | factorial-global | 1440 | 29.4% | 64 | 35 | 12.6% |
| design-g | neighbourhood-global | 240 | 67.9% | 4 | 3 | 3.1% |

| journal | split | draw reqs | succ rate | hard | near | unknown W | family / known W |
|---|---|---|---|---|---|---|---|
| codex | train | 406 | 30.8% | 28 | 10 | 193 | 17.8% |
| codex | validation | 86 | 30.2% | 6 | 2 | 39 | 17.0% |
| codex | test | 80 | 36.2% | 6 | 3 | 32 | 18.8% |

The full `-w{steam}p{pa}d{draws}` cell table is `T2.idSuffix` in `tables.md`. Its extremes in
design-v2 are `w0p0d3` (23.3 % of the cell in the family - no steam, no pumparound, three draws) and
`w0p3d1` / `w0p4d1` (0.0 % / 0.8 %); the pattern is monotone in the draw count and reversed in the
pumparound count. The pumparound reversal is partly an artifact: adding pumparounds pushes requests
into the heat-gated "unknown W" bucket, which is why the `family / known W` column is the one to read.

### The 312-request holdout (T10)

| withdrawal read from | hard >=1 | near 0.8-1 | other | unknown | hard solved strictly by some lane | near solved strictly by some lane |
|---|---|---|---|---|---|---|
| worst lane (max W over the three lanes) | 42 | 19 | 163 | 28 | 5 | 3 |
| best lane (W of a strictly solved lane, else min W) | 25 | 17 | 182 | 28 | 0 | |

Family members solved strictly per lane: `current` 6, `neural` 2, `neuralFirst` 7 (out of the 61 the
worst-lane reading calls family). Strict solves over the whole 312: `current` 106, `neural` 74,
`neuralFirst` 131.

---

## 4. Task 2 - success rate by withdrawal band (T3)

| withdrawal band | design-v2 n | design-v2 succ | design-g n | design-g succ | codex n | codex succ |
|---|---|---|---|---|---|---|
| [0,0.2) | 807 | 69.4% | 332 | 68.7% | 93 | 63.4% |
| [0.2,0.4) | 786 | 72.9% | 336 | 71.7% | 103 | 70.9% |
| [0.4,0.6) | 338 | 62.1% | 110 | 60.0% | 38 | 81.6% |
| [0.6,0.8) | 143 | 53.1% | 56 | 66.1% | 19 | 42.1% |
| [0.8,1) | 69 | 30.4% | 38 | 39.5% | 15 | 60.0% |
| [1,inf) | 221 | 0.0% | 68 | 0.0% | 40 | 0.0% |

Solvability decays smoothly from about 0.4 upward and the `>=1` row is 0 % by construction (section
2). The informative row is `[0.8,1)`: 30-60 % of requests that end up within 20 % of exhausting a tray
still solve, which is why an aggressive screen costs real training data.

---

## 5. Task 3 - request-only predictors

### 5.1 `rho`, reimplemented exactly

`rho` was reimplemented from `V3LiquidSupplyScreen.java` (cooling credit at 30 kJ/mol including the
`RETURN_TRAY` / `UNIFORM` split rule, feed credit at and below the feed tray, `D_max = F + S - draws`).
Sanity check against the brief's claim: the maximum `rho` over design-v2 is 0.29998 and over design-g
0.29767, i.e. both matrices were redrawn until they passed the 0.30 tier, so **`rho >= 0.30` removes
exactly zero requests from them**. The codex journal predates the screen and has 33 requests at
`rho >= 0.30`, 4 of them at `rho >= 1` - but all 33 sit in the "unknown W" bucket, so `rho` removes no
family member there either (T14).

### 5.2 The brief's energy-limited proxies are degenerate on this population

`V_top = Q/lambda + heating/lambda + vf*F + S - cooling/lambda` clamps to zero - and therefore makes
`proxy1`/`proxy2` infinite - on most requests, including most *solvable* ones, because the authored
pumparound cooling routinely exceeds the reboiler duty by an order of magnitude (median cooling
32.5 MW on solved requests versus a median reboiler duty of 5.1 MW).

| lambda kJ/mol | vf | requests with V_top = 0 | share of draw requests | solvable requests with V_top = 0 | share of solvable |
|---|---|---|---|---|---|
| 30 | 0.0 | 2350 | 65.1% | 1528 | 69.2% |
| 30 | 0.9 | 1605 | 44.4% | 1086 | 49.2% |
| 40 | 0.3 | 1957 | 54.2% | 1291 | 58.5% |
| 40 | 0.9 | 1078 | 29.8% | 715 | 32.4% |
| 50 | 0.6 | 1251 | 34.6% | 846 | 38.3% |
| 50 | 0.9 | 339 | 9.4% | 186 | 8.4% |

Consequently `proxy1` and `proxy2` have AUC 0.46-0.52 (indistinguishable from coin flipping) at every
`lambda` in {30, 40, 50} kJ/mol and every `vf` in {0, 0.3, 0.6, 0.9}, and their "worst solved" values
run to 6,341. `proxy3` avoids the clamp and reaches AUC 0.62-0.65. The physical reason the subtraction
is wrong: a pumparound cooler mostly removes *sensible* heat from the circulating liquid, and what it
does condense keeps flowing down, so the condensate becomes tray liquid below the zone rather than
disappearing from the balance. Crediting `coolingAbove(T)/lambda` as extra liquid, and *not*
subtracting the total cooling from the vapour budget, is what turns this family of statistics from
useless into the best statistic found.

Two further corrections come from a single ideal flash of the authored feed (Lee-Kesler vapour
pressure over the shipped `pr78` critical constants, Rachford-Rice; `thermo.py`), which the Java
design generator can afford because `V3HeatFeasibility` already flashes the feed:

* the real feed vapour fraction has median **0.80** and a 5-95 % range of 0.46-0.97 - these feeds are
  superheated, so a fixed `vf` guess is badly wrong at both ends of the population;
* the Clausius-Clapeyron latent heat of the feed at the condenser temperature is **46-83 kJ/mol**
  (median 62), not the 30 kJ/mol the shipped screen uses as a deliberately conservative floor.

### 5.3 All proxies at their best threshold under a false-positive cap (T5, condensed)

| proxy | AUC hard vs solved | worst solved value | hard recall @<=0.5 % FP | threshold | hard recall @<=2 % FP | threshold |
|---|---|---|---|---|---|---|
| `proxy1_*` (all 12 lambda/vf) | 0.48-0.52 | 115-6341 | 0.000 | - | 0.000 | - |
| `proxy2_*` (all 12 lambda/vf) | 0.46-0.52 | 170-6341 | 0.000 | - | 0.000 | - |
| `proxy3_l30` | 0.619 | 1.794 | 0.043 | 1.033 | 0.091 | 0.866 |
| `proxy3_l50` | 0.652 | 2.050 | 0.033 | 1.128 | 0.112 | 0.925 |
| `rho` (shipped) | 0.740 | 0.287 | 0.046 | 0.241 | 0.161 | 0.176 |
| `drawTotal / (vf*F)` | 0.773 | 3.513 | 0.112 | 1.110 | 0.231 | 0.861 |
| `calibratedW` (section 5.5) | 0.800 | 3.360 | 0.030 | 1.007 | 0.122 | 0.596 |
| `sigma` at lambda = 30 kJ/mol | 0.814 | 0.712 | 0.143 | 0.621 | 0.261 | 0.494 |
| **`sigma` at lambda = 60 kJ/mol** | **0.840** | **0.812** | **0.225** | **0.660** | **0.343** | **0.552** |
| `sigma` with the flash latent heat | 0.844 | 0.881 | 0.201 | 0.703 | 0.350 | 0.569 |

The flash-derived latent heat buys nothing over the fixed 60 kJ/mol, so the recommendation keeps the
constant (one fewer thing to version). The feed vapour fraction, however, is load bearing: replacing
it with a constant is most of what separates `rho` (AUC 0.740) from `sigma` (0.840).

### 5.4 `sigma` threshold sweep

Pooled (T7b):

| threshold | hard hits | hard recall | near-fail hits | near recall | false positives on solvable | FP rate |
|---|---|---|---|---|---|---|
| 0.4 | 216/329 | 65.7% | 46/77 | 59.7% | 313/2207 | 14.2% |
| 0.45 | 179/329 | 54.4% | 37/77 | 48.1% | 190/2207 | 8.6% |
| 0.5 | 144/329 | 43.8% | 28/77 | 36.4% | 96/2207 | 4.3% |
| 0.55 | 116/329 | 35.3% | 18/77 | 23.4% | 45/2207 | 2.0% |
| 0.6 | 93/329 | 28.3% | 15/77 | 19.5% | 28/2207 | 1.3% |
| **0.66** | **74/329** | **22.5%** | **11/77** | **14.3%** | **11/2207** | **0.5%** |
| 0.7 | 65/329 | 19.8% | 7/77 | 9.1% | 9/2207 | 0.4% |
| 0.75 | 49/329 | 14.9% | 4/77 | 5.2% | 2/2207 | 0.1% |
| 0.82 | 38/329 | 11.6% | 2/77 | 2.6% | 0/2207 | 0.0% |
| 0.85 | 31/329 | 9.4% | 1/77 | 1.3% | 0/2207 | 0.0% |
| 1.0 | 16/329 | 4.9% | 1/77 | 1.3% | 0/2207 | 0.0% |
| 1.5 | 3/329 | 0.9% | 0/77 | 0.0% | 0/2207 | 0.0% |

Per journal (T8, extract):

| threshold | journal | hard hits | hard recall | near-fail hits | false positives | FP rate |
|---|---|---|---|---|---|---|
| 0.66 | design-v2 | 47/221 | 21.3% | 7/48 | 10/1440 | 0.7% |
| 0.66 | design-g | 19/68 | 27.9% | 3/23 | 1/587 | 0.2% |
| 0.66 | codex | 8/40 | 20.0% | 1/6 | 0/180 | 0.0% |
| 0.55 | design-v2 | 78/221 | 35.3% | 13/48 | 34/1440 | 2.4% |
| 0.55 | design-g | 25/68 | 36.8% | 4/23 | 8/587 | 1.4% |
| 0.55 | codex | 13/40 | 32.5% | 1/6 | 3/180 | 1.7% |

The three journals agree to within a few points, and the worst solved `sigma` is 0.812 (design-v2),
0.708 (design-g), 0.657 (codex) - the statistic transports across three independently authored
designs, which is the property `rho` was calibrated on and the reason to fix one threshold rather than
one per journal.

### 5.5 Direct calibration against the converged liquid - attempted, and it does not help

The classical journals carry the converged state for every success: `seed.liquid[n]` and
`seed.vapor[n]` are the per-component molar flows leaving node `n` (0 = condenser, 1..N = trays,
N+1 = reboiler). Two facts were verified before using them:

* `SIDE_DRAW_SPLIT.value` equals exactly `draw_T / sum(seed.liquid[T])` on all 1,440 design-v2 draw
  successes, so the audit denominator is the tray's total liquid before the draw is removed;
* `features.jsonl` `y[n][1]` equals `log1p(sum(seed.liquid[n]) / F)`, so the feature export carries the
  same information in log form - the journals were used directly instead.

The structural identity is exact: for a draw tray `T`, `L[T] = V[T+1] - D - (draws above T)`, so the
only unknown is the distillate `D`, and `D` is set by the condenser temperature specification, which
needs the property package. Two calibrations over 4,756 draw-tray samples:

| fit | R^2 |
|---|---|
| linear OLS of `L[T]` on `k*Q, k*vf*F, k*S, coolingAbove, drawsAbove, (1-vf)*F below feed, 1` | **0.038** |
| the same terms in log space | **0.341** |

with fitted coefficients (linear, mol/s per unit):

| term | coefficient |
|---|---|
| `R/(R+1) * Q` (per MW) | 15.09 |
| `R/(R+1) * vf * F` | 1.22 |
| `R/(R+1) * S` | 0.13 |
| `coolingAbove` (per MW) | 8.40 |
| `draws above tray` | -2.08 |
| `(1-vf)*F` below the feed tray | -0.66 |
| intercept | 193.1 |

The linear fit is defeated by the tail: `L[T]` spans 49 to 61,983 mol/s (median 678), because the
distillate rate is a strongly nonlinear function of the condenser temperature specification. The log
fit predicts the tray liquid only to within a factor of 0.52-2.35 (5th-95th percentile of
`L_hat / L`), far too loose to tell a withdrawal of 0.4 from one of 1.0. The resulting calibrated
withdrawal estimator scores AUC 0.800 with 3.0 % hard recall at a 0.5 % false-positive budget -
**worse than the un-calibrated `sigma`** (0.840 / 22.5 %), so the recommendation does not use it. The
fitted coefficient on `draws above tray` is a useful cross-check: it comes out at -2.08 where the
material balance demands exactly -1, which measures how much variance the request-only terms fail to
explain.

### 5.6 How much separation is available at all

A ridge logistic regression over 23 request-only features (all of the above plus the condenser and
feed temperatures, pressures, stage counts, draw positions) reaches AUC 0.911 in sample and 0.88-0.93
under leave-one-journal-out, but only 27-33 % recall at a 0.5 % false-positive budget - the same order
as `sigma`. Restricted to the 27 converged-and-audit-rejected cases, AUC is 0.948 and recall at zero
false positives is 25.9 %. **The ceiling for a request-only screen on this population is roughly a
third of the family, not all of it**, and a single interpretable ratio already gets two-thirds of the
way to that ceiling.

---

## 6. Task 4 - what in the design produces the family

`make_input` (tools/neural/generalized_design.py, around line 379) draws each side draw rate as
`baseline_draw * (0.8 + 0.4 * u)` - 80-120 % of the preset's own withdrawal - while independently
sampling the feed rate over 80-120 %, the condenser outlet temperature over 0.8-1.2x the preset's,
the reflux ratio uniformly on [0, 10], the reboiler duty uniformly on [0, 0.2 * F_base * 100 kJ/mol],
the feed temperature over 0.8-1.2x, the pumparound duties over 0.8-1.2x of the preset duties, and the
draw tray *positions* uniformly over 1..N without replacement. Nothing couples the withdrawal to the
energy that has to supply it.

### Where the family lands (T12, medians within the D-open draw population)

| journal | group | n | Q (MW) | R | draws/F | steam share | cooling (MW) | pumparounds | draws | stages | condenser T (K) | feed T (K) | vf feed | rho | sigma |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| design-v2 | family | 290 | 6.90 | 4.34 | 0.423 | 0.62 | 15.3 | 1 | 3 | 27 | 359.9 | 593.1 | 0.643 | 0.090 | 0.462 |
| design-v2 | solved | 1440 | 5.11 | 4.75 | 0.382 | 0.67 | 32.5 | 2 | 2 | 34 | 335.6 | 653.2 | 0.820 | 0.061 | 0.288 |
| design-g | family | 106 | 8.59 | 4.37 | 0.433 | 0.58 | 13.5 | 1 | 3 | 29 | 360.5 | 607.3 | 0.694 | 0.088 | 0.440 |
| design-g | solved | 587 | 5.15 | 4.81 | 0.382 | 0.68 | 32.0 | 2 | 2 | 36 | 333.9 | 662.7 | 0.816 | 0.060 | 0.292 |
| codex | family | 55 | 7.62 | 4.22 | 0.443 | 0.49 | 14.7 | 1 | 3 | 24 | 370.9 | 594.7 | 0.699 | 0.113 | 0.444 |
| codex | solved | 180 | 7.30 | 5.71 | 0.345 | 0.56 | 29.1 | 2 | 2 | 27 | 339.3 | 658.8 | 0.837 | 0.048 | 0.254 |

### Family rate across quartiles of each knob (T13; cells are design-v2 / design-g / codex)

| knob | Q1 | Q2 | Q3 | Q4 | direction |
|---|---|---|---|---|---|
| condenser outlet T | 6.9 / 6.0 / 10.4 % | 7.1 / 5.5 / 9.1 % | 12.7 / 12.3 / 15.6 % | **22.3 / 21.3 / 36.4 %** | hotter condenser is much worse |
| feed vapour fraction `vf` | **25.7 / 21.7 / 33.8 %** | 9.6 / 9.4 / 22.1 % | 7.8 / 6.0 / 7.8 % | 5.9 / 8.1 / 7.8 % | colder (more liquid) feed is much worse |
| draws / F | 5.6 / 4.3 / 3.9 % | 11.7 / 9.8 / 15.6 % | 13.7 / 11.5 / 10.4 % | **18.1 / 19.6 / 41.6 %** | monotone, as expected |
| reflux ratio R | **19.3 / 18.3 / 28.6 %** | 8.5 / 6.4 / 14.3 % | 11.5 / 12.8 / 11.7 % | 9.8 / 7.7 / 16.9 % | only the bottom quartile stands out |
| pumparound cooling | 14.2 / 15.7 / 18.8 % | 14.2 / 13.2 / 18.8 % | 9.5 / 10.2 / 20.8 % | 11.2 / 6.0 / 13.0 % | **more cooling helps** |
| reboiler duty Q | 5.6 / 4.7 / 15.6 % | 15.2 / 11.1 / 19.5 % | 15.6 / 12.3 / 24.7 % | 12.7 / 17.0 / 11.7 % | weak, non-monotone |

Reading: **the family is a condenser-and-feed-enthalpy corner, not a low-duty corner.** The two
dominant knobs are the two the brief did not list - the condenser outlet temperature (hot condenser ->
little condensate -> small distillate -> small `R*D` reflux) and the feed temperature through its
vapour fraction (a superheated feed is itself the column's vapour source). Two of the brief's
hypotheses hold (low reflux ratio, many draws / high draws-per-feed), one is neutral (steam: 13.7 %
dry vs 11.5 % wet in design-v2 among requests with a measured withdrawal), and one is **inverted**:
high pumparound cooling *reduces* the family rate, because condensate is liquid. Low reboiler duty is
not the corner either - the lowest duty quartile has the *lowest* family rate and the *highest*
success rate in design-v2 and design-g.

Draw placement matters too, and follows directly from sampling tray positions uniformly (T13b):

| journal | shallowest draw on | draw reqs | family / known W |
|---|---|---|---|
| design-v2 | tray 1 | 404 | 20.7% |
| design-v2 | trays 2-3 | 577 | 18.7% |
| design-v2 | trays 4-10 | 1506 | 12.2% |
| design-v2 | tray >10 | 1713 | 8.8% |
| design-g | tray 1 | 157 | 16.7% |
| design-g | trays 2-3 | 222 | 18.0% |
| design-g | trays 4-10 | 631 | 11.2% |
| design-g | tray >10 | 670 | 8.2% |
| codex | tray 1 | 79 | 24.1% |
| codex | trays 2-3 | 82 | 24.2% |
| codex | trays 4-10 | 176 | 19.3% |
| codex | tray >10 | 235 | 13.6% |

A draw on tray 1 or 2 can only take from the reflux itself; nothing below it has had a chance to
condense yet.

### Share of the "D open" class (T11)

Using the brief's definitions (zero-boil-up = no steam and `watts == 0` and `feedStage < N`;
heat-gated = failure text matching `condensation-capped|not below the`; typed infeasible = status
`INFEASIBLE_SPECIFICATION`):

| journal | zero-boil-up | heat-gated | typed infeasible | D open | D-open draw reqs | D-open draw failures | of which family | family / D-open draw failures | family / D-open draws with known W |
|---|---|---|---|---|---|---|---|---|---|
| design-v2 | 0 | 1289 | 1 | 4110 | 3247 | 1807 | 269 | **14.9%** | 12.3% |
| design-g | 0 | 542 | 1 | 1617 | 1284 | 697 | 91 | **13.1%** | 11.3% |
| codex | 243 | 174 | 34 | 701 | 418 | 238 | 46 | **19.3%** | 17.9% |

Design-v2 and design-g contain no zero-boil-up requests at all; that failure family was already
removed when the corrected design was authored. So **roughly one in seven (one in five for the codex
design) of the otherwise-open draw failures is a liquid-depletion case**; the remaining D-open failure
mass is something else - of the 1,807 design-v2 D-open draw failures, 1,538 never reached a state with
a measurable withdrawal at all.

---

## 7. Task 5 - recommendation

### The rule

Add `sigma` (section 1) to `tools/neural/generalized_design.py` alongside `liquid_supply_ratio`, and to
the Java request-only admission probe if the same screen is wanted in process. Reject and redraw at

```
sigma >= 0.66
```

publishing the measured ratio and the limiting tray in the exclusion evidence exactly as
`LIQUID_SUPPLY_ENVELOPE` does today. Like `rho`'s calibrated tier this is an **envelope, not a proof**:
the worst solved value over 2,207 solvable requests in three independent designs is 0.812, so 0.66 sits
*below* the worst solved case and does cost data. Pick the operating point per intent:

| threshold | claim | hard recall | near-fail recall | FP on solvable |
|---|---|---|---|---|
| 0.82 | no solvable request in 2,207 reached it | 11.6 % | 2.6 % | 0.00 % |
| 0.66 | best under a 0.5 % false-positive budget | 22.5 % | 14.3 % | 0.50 % |
| 0.55 | best under a 2 % false-positive budget | 35.3 % | 23.4 % | 2.04 % |

Independent check on the 312-request holdout (T15): at 0.66, 14 of 252 draw requests fire, 9 of the 42
worst-lane-hard cases are caught, and 2 of the 101 strictly solved requests are lost.

### Residual family it leaves (T14)

| rule | journal | draw reqs | removed | family removed | solvable removed | share of solvable | unknown-W removed | residual family |
|---|---|---|---|---|---|---|---|---|
| `rho >= 0.30` (today) | design-v2 | 4200 | 0 | 0 | 0 | 0.0% | 0 | 290 |
| `rho >= 0.30` (today) | design-g | 1680 | 0 | 0 | 0 | 0.0% | 0 | 106 |
| `rho >= 0.30` (today) | codex | 572 | 33 | 0 | 0 | 0.0% | 33 | 55 |
| `sigma >= 0.66` | design-v2 | 4200 | 266 | 56 | 10 | 0.7% | 155 | **234** |
| `sigma >= 0.66` | design-g | 1680 | 87 | 22 | 1 | 0.2% | 49 | **84** |
| `sigma >= 0.66` | codex | 572 | 58 | 9 | 0 | 0.0% | 43 | **46** |
| `sigma >= 0.55` | design-v2 | 4200 | 464 | 93 | 34 | 2.4% | 248 | 197 |
| `sigma >= 0.55` | design-g | 1680 | 163 | 31 | 8 | 1.4% | 95 | 75 |
| `sigma >= 0.55` | codex | 572 | 91 | 15 | 3 | 1.7% | 61 | 40 |
| `sigma >= 0.85` | design-v2 | 4200 | 101 | 23 | 0 | 0.0% | 64 | 267 |
| `sigma >= 0.85` | design-g | 1680 | 28 | 5 | 0 | 0.0% | 20 | 101 |
| `sigma >= 0.85` | codex | 572 | 34 | 4 | 0 | 0.0% | 27 | 51 |

### The larger recommendation: fix the sampler, not only the filter

A screen can only reject; the measurements say the defect is generated, and can be generated away. The
family concentrates where a preset-anchored withdrawal meets an independently sampled condenser
temperature and feed enthalpy. Two changes would remove far more of it than any threshold:

1. **Scale the authored draw rates with the sampled liquid traffic, not with the preset.** Replace
   `baseline_draw * (0.8 + 0.4*u)` with a rate expressed as a fraction of the same `L(T)` that `sigma`
   estimates - e.g. `draw_T = phi * L(T)` with `phi` sampled on [0.05, 0.45]. That makes the
   withdrawal fraction a *designed factor* with known coverage instead of an emergent one, which is
   also what a training set for a draw-aware initializer wants.
2. **Stop sampling draw trays uniformly over 1..N.** Trays 1-3 carry roughly twice the family rate of
   trays beyond 10 in all three journals.

### What could not be determined

* **How many of the 329 hard cases are truly ill-designed.** Only 27 have solver-side proof
  (converged, then audit-rejected). 302 are diverged iterates, and the holdout shows at least 12 % of
  worst-lane-hard requests are solvable by another lane. Settling this needs a re-solve of the hard
  set under several initializers and continuations with the audit tier disabled - a solver run, and
  out of scope for a read-only analysis.
* **The true family size.** 2,840 of 6,452 draw requests (44 %) never produced a measurable
  withdrawal - heat-gated condensation caps, deadline-exceeded solves, typed-infeasible inputs. All
  family counts are lower bounds, and `sigma >= 0.66` fires on 247 of those unknown-W requests, which
  may be additional family members or may be heat-gate cases that would have solved.
* **Whether `sigma`'s threshold transports to a different preset set.** It was validated across three
  designs and one holdout, all built on the same crude presets and the same 19/20-component basis.
  The `rho` calibration has the same limitation and the same remedy: re-measure the worst solved value
  whenever the preset set changes.
* **A genuinely physical (necessary, not calibrated) tier above `rho >= 1`.** That needs the distillate
  bound `D <= D(T_condenser, P_top)`, which requires the real property package rather than the
  Lee-Kesler/Raoult surrogate used here, and a flash at the condenser rather than at the feed. The
  surrogate's own condenser light-end fraction was tested and carries no usable signal (AUC 0.32), so
  this remains open.
* **Why the family rate falls again in the 35-49 and 50-64 stage bands.** Success rates collapse to
  15-16 % in the 50-64 band, so most of those requests land in the unknown-W bucket and the comparison
  is confounded.

---

## 8. Follow-up: does pumparound cooling above a draw prevent depletion?

Stated design rule: *"large side draws need appropriate pumparound cooling above them to prevent
depletion."* Quantified on the same pooled population (3,612 draw requests with a known withdrawal;
`kappa.py`, tables in `kappa-tables.md`, numbers under `pumparoundCoverage` in `summary.json`).

Definition, with the same lambda = 60 kJ/mol and the same `coolingAbove` as `sigma` (authored
pumparound cooling on trays 1..T, `UNIFORM` / `RETURN_TRAY` split rule):

```
kappa(T)  = coolingAbove(T) / (lambda * cumulative authored draws on trays 1..T)
kappa_req = min over the authored draw trays T
```

`kappa` is dimensionless: the authored cooling duty above a draw divided by the duty needed to
condense what the draw removes. Note the two structural flags in the request are the **same
predicate**: a cooling zone lying entirely at or above the shallowest draw covers every deeper draw
too (verified on all 3,612 rows). The informative weaker variant is "at least one draw tray covered".

### 8.1 The rule holds, and it is strong (K1, K2)

| kappa_req band (pooled) | n | success rate | hard W>=1 | hard rate | family | family rate | near band and solved |
|---|---|---|---|---|---|---|---|
| 0 | 1690 | 51.2% | 232 | 13.7% | 282 | **16.7%** | 20 |
| (0,0.25] | 87 | 48.3% | 12 | 13.8% | 14 | 16.1% | 3 |
| (0.25,0.5] | 167 | 59.9% | 20 | 12.0% | 21 | 12.6% | 4 |
| (0.5,1] | 411 | 68.1% | 31 | 7.5% | 42 | 10.2% | 11 |
| (1,2] | 907 | **75.9%** | 28 | 3.1% | 37 | **4.1%** | 6 |
| (2,4] | 255 | 65.1% | 6 | 2.4% | 10 | 3.9% | 1 |
| >4 | 95 | 68.4% | 0 | **0.0%** | 0 | **0.0%** | 0 |

Monotone in both directions: the family rate falls 4x and the success rate rises 25 points between
`kappa_req = 0` and `kappa_req > 1`. The same split per journal is in K1 and agrees (family rate at
`kappa_req = 0`: 16.8 % design-v2, 14.5 % design-g, 21.8 % codex; in `(1,2]`: 3.9 % / 3.3 % / 10.9 %).

The purely structural flag - ignoring duty entirely - already captures most of it:

| pooled | n | success rate | hard rate | family rate |
|---|---|---|---|---|
| every draw tray has a cooling zone entirely at or above it | 1047 | **72.5%** | 2.9% | **3.9%** |
| not | 2565 | 56.5% | 11.7% | **14.2%** |
| at least one draw tray covered | 1990 | 67.7% | 7.1% | 8.8% |
| no draw tray covered | 1622 | 53.0% | 11.5% | 14.2% |

**It is not merely "more pumparounds" (K10).** Holding the authored pumparound count fixed, the
family rate at `kappa_req > 1` is 5.0 / 4.6 / 2.7 / 6.2 % for 1 / 2 / 3 / 4 pumparounds, against
15.7 / 23.2 / 23.6 / 15.5 % at `kappa_req = 0`. What matters is *where the zone sits relative to the
draw and how big its duty is*, not how many coolers the request has.

### 8.2 But `kappa_req` is a poor rejection screen (K3, K4)

| pooled rule | fired | hard recall | near-fail recall | FP on solved | residual family |
|---|---|---|---|---|---|
| `sigma >= 0.66` | 164 | 22.5 % (74/329) | 14.3 % (11/77) | **11/2207 = 0.5 %** | 321 |
| `kappa_req < 0.25` | 1777 | 74.2 % | 67.5 % | 908/2207 = 41.1 % | 110 |
| `kappa_req < 0.5` | 1944 | 80.2 % | 68.8 % | 1008/2207 = 45.7 % | 89 |
| `kappa_req < 1` | 2355 | 89.7 % | 83.1 % | 1288/2207 = 58.4 % | 47 |
| `kappa_req < 2` | 3262 | 98.2 % | 94.8 % | 1976/2207 = 89.5 % | 10 |
| `sigma >= 0.66 OR kappa_req < 0.25` | 1782 | 75.1 % | 68.8 % | 908/2207 = 41.1 % | 106 |
| `sigma >= 0.66 OR kappa_req < 1` | 2355 | 89.7 % | 83.1 % | 1288/2207 = 58.4 % | 47 |
| `sigma >= 0.66 AND kappa_req < 1` | 164 | 22.5 % | 14.3 % | 11/2207 = 0.5 % | 321 |

Because 39 % of *solved* requests also have `kappa_req = 0`, a `kappa` cut is a blunt instrument:
AUC of `-kappa_req` against solved requests is 0.694, versus 0.844 for `sigma`. And the OR
combination buys essentially nothing over `kappa` alone, because **`sigma >= 0.66` implies
`kappa_req <= 1` in all 164 cases** (K5): `sigma` is already the duty-weighted refinement of the same
physics - it compares the draw with *all* the liquid that can reach the tray (reflux + boil-up +
condensate), while `kappa` compares it with the condensate term alone. Dropping the cooling credit
out of `sigma` costs 0.086 of AUC (0.844 -> 0.758), which is the size of the cooling contribution.

K5 also shows the two statistics are not additive: cooling changes the odds only where `sigma` is
below 0.66. At `sigma < 0.45` the family rate falls 8.8 % -> 3.5 % as `kappa_req` goes 0 -> >1; at
`sigma >= 0.66` it stays at 51 % regardless (n = 164). Cooling prevents depletion; it does not rescue
a draw that is already too large for the whole column.

**Conclusion: keep `sigma >= 0.66` as the screen and use `kappa` as a generation constraint.**

### 8.3 The counterweight: cooling is what causes the condensation cap (K8)

Over *all* 6,452 draw requests, including those whose withdrawal is not measurable:

| kappa_req band | n | heat-gated | heat-gate rate | unknown W | success rate | family rate |
|---|---|---|---|---|---|---|
| 0 | 2923 | 592 | 20.3% | 42.2% | 29.6% | 9.6% |
| (0,0.25] | 199 | 62 | 31.2% | 56.3% | 21.1% | 7.0% |
| (0.25,0.5] | 326 | 104 | 31.9% | 48.8% | 30.7% | 6.4% |
| (0.5,1] | 717 | 159 | 22.2% | 42.7% | 39.1% | 5.9% |
| **(1,2]** | **1428** | **238** | **16.7%** | **36.5%** | **48.2%** | **2.6%** |
| (2,4] | 612 | 223 | 36.4% | 58.3% | 27.1% | 1.6% |
| >4 | 247 | 90 | 36.4% | 61.5% | 26.3% | 0.0% |

The family rate keeps falling above `kappa_req = 2`, but the condensation cap takes over: the
heat-gate rate more than doubles (16.7 % -> 36.4 %) and the overall success rate collapses from 48.2 %
back to 26-27 %. **`kappa_req` in (1, 2] is the joint optimum**: highest success rate, lowest
heat-gate rate, family rate 2.6 %.

### 8.4 Interaction with shallow draws and with the hot-condenser corner (K6, K7, K9)

| shallowest draw (pooled) | n | median kappa_req | share kappa_req = 0 | success rate | family rate |
|---|---|---|---|---|---|
| tray 1 | 289 | 0 | **83.7%** | 59.9% | 17.3% |
| trays 2-3 | 438 | 0 | 76.9% | 57.5% | 17.6% |
| trays 4-10 | 1417 | 0.433 | 43.3% | 63.5% | 11.1% |
| tray >10 | 1468 | 0.989 | 33.9% | 60.1% | 8.3% |

The tray-1-to-3 penalty found in section 6 is largely *this* effect: a draw on tray 1 has no tray
above it on which a cooling zone can sit, so `kappa_req` is structurally 0 for 84 % of those requests.
The cooling rule and the draw-placement rule are the same rule seen twice.

| condenser half (pooled) | kappa_req band | n | success rate | hard rate | family rate |
|---|---|---|---|---|---|
| below median (298-340 K) | <= 1 | 1076 | 66.8% | 7.0% | 8.8% |
| below median (298-340 K) | > 1 | 730 | **78.5%** | 1.0% | **1.6%** |
| above median (340-399 K) | <= 1 | 1279 | 44.5% | 17.2% | **20.6%** |
| above median (340-399 K) | > 1 | 527 | 65.7% | 5.1% | **6.6%** |

Cooling helps in both halves and helps *most* in the hot-condenser corner (20.6 % -> 6.6 %), but does
not erase it: a well-cooled hot-condenser request still has four times the family rate of a
well-cooled cold-condenser one. The two mechanisms are partially independent, as expected - a hot
condenser starves the reflux term of `L(T)`, cooling feeds the condensate term.

Feasibility of the constraint on the existing matrices (K9):

| scope | draw requests | no cooling pumparound at all | a draw on tray 1 | already meets kappa_req >= 1 |
|---|---|---|---|---|
| design-v2 | 4200 | 720 (17.1%) | 404 (9.6%) | 1500 (35.7%) |
| design-g | 1680 | 288 (17.1%) | 157 (9.3%) | 616 (36.7%) |
| codex | 572 | 111 (19.4%) | 79 (13.8%) | 171 (29.9%) |
| pooled | 6452 | 1119 (17.3%) | 640 (9.9%) | 2287 (35.4%) |

A third of the existing draw population already satisfies the proposed constraint, so it is not
exotic; but 17 % of draw requests carry no cooling pumparound at all (the `p0` structural cells) and
would have to gain one or lose their draws.

### 8.5 Recommended generation rule

> For each authored side draw of rate `d` on tray `T`, place a cooling pumparound whose zone lies
> entirely at or above the draw (`returnTray <= drawTray <= T`) with
> **`|duty| >= kappa_min * lambda * d`, `kappa_min = 1.0`, `lambda = 60 kJ/mol`**, and keep the
> cumulative cooling above any draw tray below about `2 * lambda * (cumulative draws at and above it)`.

Applied per draw, this implies `kappa_req >= kappa_min` for the request as a whole, because the
cumulative cooling above tray `T` then covers the cumulative draw above `T`.

Evidence for `kappa_min = 1.0`: it is the knee of K1 (family rate 10.2 % just below it, 4.1 % just
above) and the start of the K8 optimum band (success rate 48.2 %, heat-gate rate at its minimum
16.7 %). Evidence for the soft upper bound near 2: above it the family rate improves only from 2.6 %
to 1.6 % while the heat-gate rate doubles and the success rate falls by 21 points.

Two corollaries the same data forces:

* **No side draw on tray 1, and prefer tray 4 or deeper.** No zone can lie entirely above tray 1, so
  the constraint is unsatisfiable there; trays 2-3 can only be covered by a one-tray zone at tray 1
  or 2. 9.9 % of the current draw population violates this.
* **The rule does not replace the condenser-temperature fix.** In the hot half of the condenser
  specification a request that satisfies `kappa_req > 1` still shows a 6.6 % family rate against
  1.6 % in the cold half, so the `sigma` screen (which carries the reflux term) is still needed.

### 8.6 What this data cannot show

* **Whether cooling *immediately* above a draw beats cooling anywhere above it.** `coolingAbove(T)` is
  a single cumulative number and the designs place pumparound zones by factors sampled independently
  of the draw trays, so the population contains no controlled contrast between "zone one tray above
  the draw" and "zone twenty trays above the draw". This is the single most useful follow-up
  experiment and it needs generated requests, not these journals.
* **Causality.** Authored cooling here is *not conditioned on the draws*, so `kappa_req` is emergent
  and correlated with the pumparound count, the stage count and the pumparound span. K10 removes the
  pumparound-count confound but not the others; a matrix generated under the proposed constraint is
  the only way to confirm the effect size.
* **Whether the effect survives once the rule is enforced.** Every number above is observational. The
  `(1,2]` optimum could move once cooling is deliberately sized against draws.
* **The `>4` band's 0 % family rate.** n = 95 with a known withdrawal; the 95 % confidence upper bound
  on a zero count there is about 3 %, i.e. it is not distinguishable from the `(2,4]` band's 3.9 %.
* **The unknown-withdrawal mass.** 36-62 % of each `kappa_req` band has no measurable withdrawal, and
  that share rises with cooling, so the family rates in K8 are lower bounds that are *less* tight in
  the high-`kappa` bands. The apparent perfection of high cooling is partly requests disappearing into
  the heat gate.
* **Whether `lambda = 60 kJ/mol` is the right conversion.** It was chosen for `sigma`; the flash
  Clausius-Clapeyron value spans 46-83 kJ/mol across this population, so a `kappa_min` of 1.0 at
  60 kJ/mol is between 0.7 and 1.3 in true latent-heat units.
