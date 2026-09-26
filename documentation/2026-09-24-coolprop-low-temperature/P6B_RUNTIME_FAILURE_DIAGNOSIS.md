# P6b: the four runtime failures of the P6 pilot world, diagnosed

Batch `2026-09-24-coolprop-low-temperature`, follow-up of P6 ([P6_PILOT_ACCEPTANCE.md](P6_PILOT_ACCEPTANCE.md) sections 8
and 9). Written 2026-09-26 on branch `claude/coolprop-multiphase-thermo-37f6b0`, base `c369faf`, commits `db43b22`,
`a2f48d4`, `4c21104` (section 8). ASCII only. Tools: `tools/p6b-runtime-failures/`; research artifacts:
`research/2026-09-24-coolprop-low-temperature/p6b-runtime-failures/` (both in the main checkout). The saved checkpoint
read throughout is the P6 copy `research/.../p6-pilot-acceptance/world-final/data`, never the world in `run/`.

Owner instruction received during the task: failure 1's fix needs decisions not forced by the physics, so it is
diagnosed, reproduced and written up with options (section 1) and left unfixed; failures 2 to 4 are fixed with their
assumptions stated (section 7).

## 0. Outcome

| Failure (P6 section 9) | Root cause | Kind | Status | Before -> after |
|---|---|---|---|---|
| 1. Near-pure CO2 vessel at its sublimation line (row A) | The TP crystal solve decides onset and convergence on an absolute 1e-9 of (mu_i - mu_s)/RT; for one crystal species with a trace f the whole vapour-solid region at fixed T spans a supersaturation of about f n_s/n_v, so for f from about 1e-12 to 1e-7 V(P) at fixed T has a numerical step and the nested UV closes its bracket on it | code (numerical resolution), not the model | **not fixed, owner decision (D21 proposed)**; options A to E in section 1.4 | refused after 2.74 s, 7.98 M checkpoints (unchanged); option A's proxy (traces removed): 12 to 86 ms, 3.8 to 5.2 k checkpoints per interval |
| 2. Budget holds of crystal vessels under kg/s flows (row E) | The vessel's crystal UV after every accepted substep (D18 item 4) was cold beside a vapour and a liquid: 21,576 to 60,133 kernel evaluations each, 93 to 99.9 % of the checkpoints, 59 to 209 substeps per interval | code (performance) | fixed `4c21104`: warm start for three or more species (phase rule), warm budget 16; D18's approximate refusal stands (measured) | flush intervals 1-4: 0.56 / 1.20 / 2.07 / 0.58 s -> 0.33 / 0.33 / 1.00 / 0.61 s; checkpoints 1.32 / 5.02 / 10.49 M -> 0.36 / 1.08 / 4.30 M |
| 3. Warm water slurry meeting cryogenic methane (rows B, D) | The TR-BDF2 embedded companion (an error estimate, never committed) holds -2/3 of a species that enters only in stage two; building it as an inventory refused every step size ("Negative/nonfinite inventory") before the stages reached the water domain | code (sign of an estimate) | fixed `db43b22`: the companion takes a negative amount at zero | untyped numerical hold (63.5 / 26.2 ms, retried on the ladder) -> typed `ThermoDomainViolation` "Water ... below 273.16 K at node 11 / 52" (761 / 394 ms, retried once, then parked) |
| 4. Zero-flow junction-donor cycle (pilot chain) | At equal pressure the converged flows are at the pressure rows' resolution; each frozen junction donor gives the flow the other sign (the receiving vessel takes the donor's mixture in), so the donors cycle | code (junction active set) | fixed `a2f48d4`: a donor cycle at a numerically zero flow states those connections at zero for the solve | chain interval 8 refused -> 40 intervals commit; bundled chain-100 exact 0 |

Liquidus (section 5): at Shen 2012's 150.40 K point the engine's CO2 solubility is 1.196 x the measurement; the crystal
side contributes +2.8 % (model sublimation pressure 916.3 Pa against Span-Wagner's 891.5 Pa), the rest is the liquid
side: the E-PPR78 CH4/CO2 k_ij of 0.1073 at 150.4 K would have to be about 0.124 (+0.0165) to meet Shen. No model change.

Gates (section 6): `test` 1,305 (1,299 before), `fluidScienceTest` 248 (242), `fluidRuntimeTest` 231 (231), 0 failures; chain-100 exact 0.000e+00; 30 of 30 GameTests on a fresh world; P12/P31 identical.

## 1. Failure 1: the near-pure CO2 vessel at its sublimation line (row A) - diagnosed, not fixed

### 1.1 Reproduction

- **Saved island** (island 47, `P6bWorldProbe 47 1`): vessel 5 at 212.2218 K, 381,703.8 Pa, 1 m3, gas N2 2.3437e-5 mol and
  CO2 229.279 mol, crystal 19.8631 mol; N2 generator 150 K / 300 kPa (its line closed: the vessel is above it), CO2
  generator 210 K / 400 kPa, void 101.325 kPa. One 5 s interval: refused after 2,811 ms (P6: 2,735.5 ms), 7,984,032
  checkpoints, 489 accepted and 535 rejected substeps advancing 0.0203 s, every rejection "Crystal equilibrium (UV) did
  not converge: ... lies in a step of U(T) ... between 212.22164568001782 and 212.22164568161602 K". After P6b's commits:
  2,673.8 ms, 7,987,454 checkpoints, the same refusal (`world-probe-after.txt`; failure 2's larger warm budget adds 3,422
  checkpoints to the failing warm attempts).
- **Engine alone** (`P6bNearPureUvProbe`), the failing specification U = -9.972099514183599e7 J (engine datum),
  V = 1 m3, totals N2 2.3343e-5, CO2 249.1435 mol: cold NOT_CONVERGED after 7,283 kernel evaluations (179 TP equilibria,
  6.7 to 7.6 ms), warm the same after 7,608. The same energy and volume with N2 = 0 (a pure feed): VAPOR_SOLID from P5's pure
  sublimation step, 212.221642009 K, 381,699.570 Pa, crystal 19.866663356 mol, 296 kernel evaluations, 0.6 ms.

### 1.2 Where it fails: the trace fraction and the crystal share

The same energy and volume, the trace f (N2, or CH4 for row E's analogue) scanned (`near-pure-uv-probe.txt`):

| trace f | N2 | CH4 |
|---|---|---|
| 0 (pure) | converged, 296 kernel, 0.6 ms | same |
| 1e-12 to 1e-8 | NOT_CONVERGED: "the specified volume lies in a step of V(P)", 34,787 to 57,076 kernel, 16 to 25 ms | same, up to 93,932 kernel, 32 ms |
| 3e-8 | "step of U(T)", 9,506 kernel | same, 10,604 |
| 1e-7 | "step of U(T)", 7,721 kernel (row A: f = 9.4e-8) | converged, 7,557 kernel (row E's saved vessel: f = 7.9e-8, one rejection per interval) |
| 3e-7 to 1e-2 | converged, 3,818 to 8,053 kernel, 1.4 to 2.5 ms; crystal 19.866666 (3e-7) to 19.3832 mol (1e-2) | converged |

The failure map over the crystal share (`near-pure-map-probe.txt`, energies from +4.5e5 to -3e6 J around row A's): at
every share the UV fails for f <= 1e-7; at the smallest crystal (0.39 mol, 0.16 % of the CO2) it fails up to f = 3e-7 and
converges from 1e-6. The trace-free answer is reached continuously: at f = 1e-4 the crystal is 19.8619 against the pure
19.8667 mol (-2.4e-4 relative) and P is +50 Pa (+1.3e-4); at 0.39 mol the same f gives -1.2 %.

### 1.3 Root cause (code, numerical resolution)

TP answers along the pure sublimation isotherm, 212.2216 K, pressure P = P_sub (1 + x f) (`near-pure-map-probe.txt`):

| f | last SINGLE_VAPOR | first VAPOR_SOLID |
|---|---|---|
| 1e-9 | x = 2.0 (drive 8.7e-10), V 1.0860 m3 | x = 3.0 (drive 1.8e-9): crystal 179.19 mol, V 0.3100 m3 |
| 1e-8 | x = 1.1 (drive 3.1e-10) | x = 1.2 (drive 1.2e-9): crystal 29.09 mol |
| 1e-7 | x = 1.05 (drive -1.6e-9) | x = 1.10: 7.48 mol |
| 1e-6 | x = 1.05 (drive -1.6e-8) | x = 1.10: 7.48 mol (resolved: the drive crosses zero between the points) |

The crystal solve (`FluidTpEquilibrium.solveWithCrystals`, `depositFromVapour`) forms a crystal only when the feed's
drive (mu_i - mu_s)/RT exceeds `DEPOSITION_TOLERANCE` = 1e-9 and converges at |f(s)| <= 1e-9. For a feed of the crystal's
species with a trace f, the vapour-solid region at fixed T spans a drive from 0 to about f n_s/n_v (a pressure width
about f, row A: 0.087 f), and its slope in the solve's unknown s is about f. So when f n_s/n_v is near or below 1e-9 the TP
answers cannot resolve the region: the crystal jumps from 0 to about (1e-9/f) of the vapour (the whole vapour at
f = 1e-9). V(P) at fixed T then has a step, the inner UV bracket closes on it ("step of V(P)"), or U(T) at fixed V has one
("step of U(T)"). The model itself has a continuous answer for every f (the scan converges on both sides of the window
to the pure answer plus O(f)), so this is not scientific. Why P5 did not meet it: a pure feed (`presentCount == 1`) takes
the pure step from the balances (`pureCrystalStep`) and never asks TP inside the region; P5's pseudo-binary rule
(`binaryCrystal`, two species above `TRACE_FRACTION` = 1e-8) covers a binary's three-phase step, not one major species
with traces, and it even counts row A's 9.4e-8 N2 as a second major.

In the network each accepted substep's equilibration fails, the substep is rejected and halved; the interval spends its
1024 attempts on 0.02 s. Every CO2-flushed vessel holding crystals crosses this window, because a pure feed dilutes the
trace exponentially and never removes it: the row E replay (section 2) reaches it about 85 s after its liquid drained
(interval 21 refused after 4.0 s, CH4 at about 1e-8).

### 1.4 Options (none implemented; the owner decides)

| Option | What it does | Assumption it makes | Expected behaviour and cost |
|---|---|---|---|
| **A. Pseudo-pure crystal step** | A feed whose other species together are at most theta of the feed, and whose major species has a competing crystal, takes P5's pure-species step (sublimation, melting, triple point) from the balances, with the traces carried in the fluid phase | theta (evidence: failures to 1e-7 at every share, to 3e-7 at a 0.16 % crystal; 1e-6 covers every measured case, 1e-5 leaves margin for smaller crystals); the traces sit in the fluid at the pure step's (T, P), not at their own partial pressure (error about 1.3 f in P and about 2.4 f relative in the crystal at 19.9 mol, 1.2 % at a 0.39 mol crystal for f = 1e-4, from the scan); precedence over P5's pseudo-binary rule for traces between 1e-8 and theta; the complete deposition of the major species stays refused (no fluid but the traces) | Engine: about the pure answer's 296 kernel evaluations (0.6 ms) instead of 7 k to 94 k failing. Network proxy (row A's vessel with its traces removed, `-Dp6b.strip=1`): four intervals of 86.1, 17.9, 16.4, 12.2 ms, 3,827 to 5,233 checkpoints, 7 to 10 substeps, the crystal growing 0.79 mol per 5 s (`rowA-traces-removed-proxy.txt`) |
| B. Resolution-relative crystal tolerance | Onset by the sign of the drive and convergence on s (the amount) rather than on an absolute 1e-9 of the drive | changes every crystal TP answer near an onset: P4/P5 pins move, G4/G5 onsets must be re-scored | still steep (dV/d ln P about V/f), so the nested UV needs about log2(1/f) more bisections per temperature; below f of about 1e-16 unresolvable in double precision, so it needs A anyway. Not recommended alone |
| C. Fast typed hold | The engine types this non-convergence (a one-major crystal UV closing on a step) and the network holds on the first refusal instead of 1024 attempts | a near-pure crystal vessel is not answered: a CO2-flushed vessel holding crystals holds indefinitely (the trace never reaches zero) | one failed UV per retry (7 to 30 ms) instead of 2.7 s; the island parks. Recommended only as the safety net behind A |
| D. Remove traces below theta from the inventory | - | breaks the network's species conservation (1e-12) | rejected |
| E. Answer a closed U(T) step by interpolation | - | the interior of the step is no state of the model | rejected |

**Recommendation (proposed D21):** A with theta = 1e-6 of the feed (1e-5 if the owner prefers margin toward small
crystals), the traces in the fluid phase, and C as the typed fallback for any one-major crystal UV that A does not
answer. Both are engine changes of the crystal competition only (fluid-only packages never reach them).

## 2. Failure 2: budget holds of crystal vessels under kg/s flows (row E) - fixed (performance)

### 2.1 Reproduction

The saved row E island (78) is after the drain (near-pure CO2 gas and 388 mol crystal): one interval 84.9 to 93.7 ms,
175 k checkpoints, well inside the budget. The holds happened during the flush, so the flush was replayed
(`P6bRowEFlushProbe`): the saved topology (liquid methane generator 115 K / 200 kPa, CO2 generator 250 K / 250 kPa, the
1 m3 vessel, void 101.325 kPa), the vessel reset to the reservoir's initial nitrogen (298.15 K, 101.325 kPa). Before
(`rowE-flush-before.txt`, one thread, no game running):

| Interval | ms | checkpoints | substeps (rejected) | crystal UV calls, kernel evaluations | vessel |
|---|---|---|---|---|---|
| 1 | 559.7 | 1,324,561 | 59 (16) | 56, 1,227,578 (92.7 %) | 123.43 K, liquid 0.071 m3, crystal 114.6 mol |
| 2 | 1,197.8 | 5,015,036 | 164 (4) | 166, 5,002,673 (99.75 %) | 123.23 K, liquid 0.021 m3, 174.7 mol |
| 3 | 2,070.0 | 10,489,051 | 209 (1) | 211, 10,474,226 (99.86 %) | 123.48 K, liquid 0.0016 m3, 240.2 mol |
| 4 | 575.0 | 2,887,385 | 111 (3) | 110, 2,848,653 (98.7 %) | 187.65 K, dry, 290.8 mol |
| 5 to 20 | 5 to 17 | 17 k to 75 k | 2 to 5 | | CO2 gas and crystal |

The world's budget is 2 s per interval with 1.5 s for the full solve: offline, on one idle thread, interval 3 alone
exceeds both; in game the client and the other islands share the machine, consistent with P6's about 50 ticks per 8 s.

### 2.2 What drives the checkpoints

Not the TP flashes (0 to 30 k kernel evaluations per interval) and not the stability retries: the vessel's crystal UV,
asked after every accepted substep (D18 item 4, the operator split), costs 21,576 to 60,133 kernel evaluations (29 to 68
outer TP equilibria of 750 to 900 kernel evaluations each: under the crystal competition every TP equilibrium of a
vapour-liquid-crystal vessel re-flashes its reduced feed). It was cold because P6's warm start is withheld beside both a
vapour and a liquid (a binary's three-phase line, where U and V step in (T, P)). Here the vessel holds N2 (0.5 to 1 %)
beside CH4 and CO2: three species, and three phases of three species hold over a region of (T, P) (two degrees of freedom
by the phase rule), where U and V are smooth. At each interval's start the warm UV answers in 3 outer iterations with
2,556 to 4,380 kernel evaluations, the same T, P and crystal (`P6bUvDiagnosis`).

### 2.3 Fix (`4c21104`) and its evidence

`FluidThermodynamics.equilibrateCrystals` warm-starts beside a vapour and a liquid when three species or more lie
above P5's trace fraction (1e-8 of the hydrocarbons, `majorSpecies`), and `FluidTpEquilibrium.WARM_UV_EVALUATIONS` is 16
(8 before): between substeps the state moves by more than two Newton iterations (three TP equilibria each with the
difference Jacobian). Warm answers per interval (flush intervals 1 to 3): 43/55, 93/166, 2/211 at 8; 154/211 in interval 3
at 12; 55/55, 166/166, 180/211 at 16; no more at 24. After (`rowE-flush-after.txt`):

| Interval | ms before -> after | checkpoints before -> after |
|---|---|---|
| 1 | 559.7 -> 327.6 | 1,324,561 -> 362,486 |
| 2 | 1,197.8 -> 328.8 | 5,015,036 -> 1,078,475 |
| 3 | 2,070.0 -> 999.1 | 10,489,051 -> 4,301,502 |
| 4 | 575.0 -> 606.4 | 2,887,385 -> 2,979,768 (the liquid vanishes; warm 73 of 110) |

The vessel states agree to the printed digits (last-digit differences from the changed numerical path). The P6
warm-start tests print their numbers unchanged (VS 228 / 380 against 1,629; LS 162 / 378). Fluid-only packages never
take this path. Test `CrystalFlushBudgetTest`: the row E rig commits three intervals under 1.0 / 3.0 / 7.0 M checkpoints
(362,420 / 1,078,475 / 4,301,478 measured) and the vessel's warm UV equals the cold one to 1e-10 at 4,380 against 60,133
kernel evaluations. Remaining: interval 3 (1.0 s on one idle thread) can still meet the soft deadline under in-game load;
the coordinator's slice halving keeps the island advancing, as P6 observed.

### 2.4 The approximate fallback for crystal islands: D18's refusal stands

Measured with the refusal lifted (`-Dp6b.approximateCrystals=true`, `rowE-approximate-fallback-compare.txt`): each
flush interval 2 to 8 run as the approximate fallback from the same start, anchored on the previous full result, as
`ProcessSolveServices` does. 7 of 7 refused by the fallback's own guards: 6 "Device regime changed" (the CO2 feed line
switches between passive and velocity-limited, the methane generator line is closed) and 1 "Conservative reconstruction
fails equation gate: 2.5e-6" (the approximate gate is 1e-6). The anchor's trust region (1 % composition, 1 K, 1 % pressure,
the same modes and phase regime) does not hold across a flush interval, so admitting crystal islands would not have
avoided row E's holds; it would only let CO2 accumulate supersaturated in the liquid for up to three cadences. A second
measurement, crystals frozen for the whole interval and equilibrated only at its end (`-Dp6b.crystalsAtEnd=true`,
`rowE-crystals-at-interval-end.txt`): interval 1 refused ("Newton line search stalled at residual 1.01e-9"), so the
per-substep split of D18 item 4 is also what keeps the step solver on states it can solve. No change to D18.

## 3. Failure 3: warm water slurry meeting cryogenic liquid methane (rows B, D) - fixed

### 3.1 Reproduction

Islands 46 (row B: liquid methane generator 120 K / 1.2 MPa) and 62 (row D: 115 K / 200 kPa), each with a water
generator at 298.15 K / 101.325 kPa (the "water slurry" preset carries no particles here), the 1 m3 vessel still at its
initial nitrogen (298.15 K, 101.325 kPa) and a void. One interval: refused after 63.5 / 26.2 ms (P6), "Substep
refinement exhausted: Negative/nonfinite inventory", no typed cause. Single TR-BDF2 steps (`P6bWorldProbe 46 1 trace`):
1 and 0.25 s pass; 62.5 ms to 0.98 ms stop on the typed boundary ("Water at 120.19 ... 258.78 K is below its valid range
273.16..900 K ... at node 11"); from 0.24 ms down to 0.24 us every step throws `IllegalArgumentException("Negative/
nonfinite inventory")` from `TrBdf2StepSolver.integrate`, the embedded companion's `new PassiveNetwork.Inventory`.

### 3.2 Root cause (code)

The cold methane pulls the vessel below the water generator's pressure inside the step, so water enters only in the
second stage: base 0, stage-one amount 0, stage-two amount n2 > 0. The order-three companion (an error estimate whose
ledger is never committed) is base + e0 dt dn + (e1/alpha)(n1 - n0) + (e2/alpha)(n2 - base), with e2/alpha = -0.667:
-1.42e-2 mol at a 1.95e-4 s step, -1.1e-7 mol at 9.5e-8 s, negative at every step size. The inventory constructor
refuses it before the stages reach the water domain; 20 such refusals in a row end the interval with the last rejection
not a domain one, so `domainCause` returns null and the hold is numerical. Scientifically the mixture of 298 K water and
115 K methane falls below 273.16 K, outside the water model (D12): the typed domain hold is the correct outcome.

### 3.3 Fix (`db43b22`) and its evidence

The companion takes a negative amount at zero (and its moles delta accordingly), as it already did for particle
populations (`finishNonNegative`); the stages stay positive and conservative. A step where free water appears changes
the phase regime, so the interval solver uses step doubling there and the companion is not read. After
(`world-probe-after.txt`): row B refused after 761 ms, 197,749 checkpoints, 536 of 539 rejections "thermo-domain: Water
temperature < 273.16 K", typed cause "Water at 273.15999999999997 K is below its valid range 273.16..900 K ... at node 11";
row D 394 ms, 535 of 537, node 52. The coordinator retries a domain hold once and then parks the island until its
network changes. The cost is 1024 attempts approaching 273.16 K from above (accepted steps stop just above the bound);
before, each 26 to 64 ms numerical hold was retried on the ladder indefinitely. Test `WaterIntoCryogenicMethaneTest` (3
tests: both rows typed at the vessel; a 2.44e-4 s step whose water arrives in stage two completes): fails before the
change with the untyped refusal. Bundled chain-100 exact 0 (the companion never went negative there).

## 4. Failure 4: the zero-flow junction-donor cycle (pilot chain) - fixed

### 4.1 Reproduction

`P6ChainProbe` (the chain of `CrystalDepositionIslandTest` (c): a warm vessel of 10 % CO2 in N2 at 250 K / 1.3 MPa, a
junction, a cold N2 vessel at 140 K / 1 MPa, 4 mm pipes): interval 7 takes 31 substeps (17 rejected), interval 8 is
refused "Substep refinement exhausted: Phase/device active-set cycle", identical on the P5 sources (`4ff68da`), so
pre-existing.

### 4.2 Root cause (code)

A pass trace (temporary, removed) at interval 8: both vessels at 1,165,597.44 Pa, differing by 0.001 to 0.004 Pa, the
converged flows +2.3e-8 kg/s with the junction's donors on the cold side and -6.4e-9 kg/s with them on the warm side. The
junction's mixture is frozen per pass on its donors (PassiveStepSolver `junctionDonorFirst`) and the receiving vessel
takes that mixture in; at the size of the pressure rows' resolution (Newton tolerance 1e-9 times 1.17e6 Pa = 1.2e-3 Pa,
through a 4 mm laminar pipe about 2e-8 kg/s) each donor gives the converged flow the other sign. `donorsTurned` restates
the donors, the pass sequence repeats, and the cycle guard refuses at every refinement. Only a zero flow is consistent.

Two intermediate attempts, measured and discarded: (a) a deadband in `donorsTurned` alone accepts a point whose flow
disagrees with its donor, and the conservative reconstruction, which mixes the junction on the live flow, fails the
equation gate (0.146); (b) stating the connections at zero flow without (c) below leaves the next solve starting at
exactly zero flow, on the `JUNCTION_INFLOW_FLOOR` switch between the junction's stored mixture and its donor's: a pass-0
Newton stall at residual 0.098 on every refinement.

### 4.3 Fix (`a2f48d4`) and its evidence

When the structure that repeats was reached by a donor turn, and every turned connection is a plain passive one (no
device, no filter) whose own friction drop at the converged flow is at most `STAGNANT_DROP` = 10 times the pass's pressure
resolution (Newton tolerance times the connection's pressure scale; measured ratio 3.8), those connections are stated at
exactly zero flow for the rest of this solve (their closed-edge rows; the junction keeps its stored mixture and pressure).
They keep their reported mode (not CLOSED), and the next solve's warm start on them is the last flow a pass solved.
Any other cycle, or a turn at a larger flow, is refused as before; a solve that never cycled is unchanged.

After (`chain-probe-after.txt`): 40 intervals commit; interval 7 26 substeps (12 rejected), intervals 8 to 16 one to four
substeps, then one per interval, the junction at rest. Test `StagnantJunctionTest`: the CO2/N2 chain commits twelve
intervals, species conserved to 1e-12, the vessels at one pressure, the junction at rest (fails before at interval 8);
the CH4/N2 fluid-only control settles before and after with the same numbers. Bundled chain-100 exact 0.

## 5. The methane-rich liquidus about 2 K cold (D19): which side the error sits on

`P6bLiquidusKijProbe` (`liquidus-kij-probe.txt`), Shen 2012 binary point 150.40 K, 1.055 MPa, x_CO2 0.008225:

| Quantity | Value |
|---|---|
| Engine CO2 solubility (liquid of a TP answer with excess CO2) at 1.055 / 2 / 5 MPa | 0.009838 / 0.009889 / 0.009998 (1.196 / 1.202 / 1.216 x Shen) |
| Crystal side: pure CO2 sublimation pressure at 150.40 K, model against Span-Wagner 1996 | 916.33 Pa against 891.51 Pa (+2.8 %, inside D16's declared 3 %) |
| Liquid side: the engine's CH4/CO2 k_ij at 150.40 K (E-PPR78; a constant-k_ij twin of the same translated PR78 matches ln phi_CO2 to 4e-15) | 0.10734 |
| Solubility at k_ij -0.02 / -0.01 / 0 / +0.01 / +0.02 / +0.03 (same crystal fugacity) | 0.012239 / 0.010971 / 0.009838 / 0.008825 / 0.007918 / 0.007107 (1.488 / 1.334 / 1.196 / 1.073 / 0.963 / 0.864 x Shen) |

So x_CO2 = f_s / (phi_CO2^L P): the crystal's fugacity accounts for +2.8 % of the +19.6 %, the liquid's CO2 fugacity
coefficient for the rest. Shen's solubility is met at k_ij about 0.124 (+0.0165; about +0.014 once the crystal's 2.8 % is
taken out); the solubility falls about 11 % per +0.01 in k_ij. The error sits on the liquid side, in the E-PPR78 CH4/CO2
k_ij(T) in cold liquid methane (too low at 150 K), as D19 said. What would fix it (P7, no model change here): a CH4/CO2
k_ij(T) fitted to the SLE data (Shen, Gao, Davis) below 170 K, checked against the VLE data at the same temperatures so
the vapour-liquid side does not move out of its D7 bound.

## 6. Gates

Git Bash in the worktree, one Gradle invocation at a time under `build/gradle.lock` (holder `p6b`, removed after each
run), `JAVA_OPTS=-Xshare:off`, `--offline`, no dev client (checked). Logs and counts:
`research/2026-09-24-coolprop-low-temperature/p6b-runtime-failures/logs/` (`gate-counts.txt`), content of `4c21104`.

| Gate | Before (P6, `4d26a45`) | After (`4c21104`) |
|---|---|---|
| `./gradlew test` | 1,299 tests, 268 classes | **1,305 tests, 271 classes, 0 failures, 0 errors, 0 skipped** (`test-final.log`, 2 min 45 s) |
| `fluidScienceTest` | 242 | **248, 0 failures** (`science-final.log`) |
| `fluidRuntimeTest` | 231 | **231, 0 failures** (`runtime-final.log`) |
| `fluidSolverRegression -PfluidRegressionMode=exact` | chain-100 0 | **chain-100 0.000e+00** on moles, temperature, phase fraction and flow (`regression-final.log`; also 0 at `a2f48d4`, `regression-early.log`) |
| `runFluidGameTestServer` | 30 of 30 | **All 30 required tests passed**, fresh world `run/fluid-gametest-p6b-final` (`gametest-final.log`) |
| P12, P31 fingerprint files (`LegacyNetworkPathPinTest` in `test`) | `cfcd4d62b51c0bbd...`, `7ba50d90eb99e585...` | identical (`test-output/`) |

+6 tests in `test` and `fluidScienceTest` (3 + 2 + 1). The bundled network is bit for bit: its package admits no crystal
(failure 2's path is never taken), the companion clip and the stagnant-junction guard act only where the solver refused
before, and the exact regression and the pins confirm it.

Offline re-runs of the saved checkpoint after the fixes (`world-probe-after.txt`, `P6bWorldProbe all 1`):

| Island (row) | Before (P6 `world-island-probe.txt`) | After |
|---|---|---|
| 43 (water only) | 48.7 ms, 312 checkpoints, 7 substeps | 46.0 ms, 312, 7 (unchanged) |
| 46 (B) | refused 63.5 ms, 4,819 checkpoints, untyped "Negative/nonfinite inventory" | refused 761 ms, 197,749 checkpoints, typed: Water below 273.16 K at node 11 |
| 47 (A) | refused 2,735.5 ms, 7,984,032 checkpoints, "step of U(T)" | refused 2,673.8 ms, 7,987,454 checkpoints, the same (failure 1 not fixed) |
| 62 (D) | refused 26.2 ms, 4,477 checkpoints, untyped | refused 394 ms, 178,576 checkpoints, typed: Water below 273.16 K at node 52 |
| 78 (E, drained) | 93.7 ms, 174,418 checkpoints, 8 substeps, 1 rejected ("step of U(T)", CH4 7.9e-8) | 81.8 ms, 175,224 checkpoints, 8 substeps, 1 rejected (the same) |

## 7. Decisions and assumptions for the lead

- **D21 (proposed): failure 1.** Option A with theta = 1e-6 (or 1e-5), traces in the fluid phase, precedence over the
  pseudo-binary rule, and option C as the typed fallback (section 1.4). Until then row A-type vessels (a crystal vessel
  flushed by pure CO2, or row E about 85 s after its liquid drained) hold on the round deadline.
- **Failure 2 (implemented, reversible).** Assumptions not forced by an existing rule: the "three species" count reuses
  P5's pseudo-binary trace fraction 1e-8 (the phase-rule argument itself is physics); the warm budget 16 is a tuning
  constant measured on row E (8, 12, 16, 24 compared). D18's approximate refusal stands (measured, section 2.4).
- **Failure 3 (implemented).** No new assumption: the companion's clip at zero is the rule it already applied to
  particle populations; the typed outcome is D12's domain.
- **Failure 4 (implemented, reversible).** Assumptions: a flow is numerically zero when its friction drop is at most 10
  times the pass's pressure resolution (measured ratio 3.8); only plain passive connections; the zero statement lasts one
  solve; the next solve's warm start keeps the last solved flow on those connections. Only active where the solver
  would otherwise refuse with the cycle.
- **Not changed:** DECISION_LOG.md, CHANGELOG.md, the unified plan, INDEX files (the lead's).

## 8. Commits

| Commit | Content |
|---|---|
| `db43b22` | `TrBdf2StepSolver`: the embedded companion takes a negative amount at zero; `WaterIntoCryogenicMethaneTest` |
| `a2f48d4` | `PassiveStepSolver`: a donor cycle at a numerically zero flow states those junction connections at zero for the solve (`stagnantTurns`, `STAGNANT_DROP`), their mode kept, the warm start kept; `StagnantJunctionTest` |
| `4c21104` | `FluidThermodynamics.equilibrateCrystals` warm-starts beside a vapour and a liquid of three species (`majorSpecies`); `FluidTpEquilibrium.WARM_UV_EVALUATIONS` 8 -> 16; `CrystalFlushBudgetTest`; `CrystalWarmStartTest` note |

Attribution line: `Co-Authored-By: Claude Fable 5.1` (the session's line).

## 9. Tooling

`tools/p6b-runtime-failures/` (main checkout; README): the javac runner with the Minecraft classpath (reads the saved
checkpoint without Gradle), the gate script, the probes (`P6bWorldProbe`, `P6bNearPureUvProbe`, `P6bNearPureMapProbe`,
`P6bUvDiagnosis`, `P6ChainProbe`, `P6bLiquidusKijProbe`, `P6bRowEFlushProbe`) and the measurement-only instrumentation
patch `p6b-probe-counters.patch` (UV/TP counters, the approximate and crystals-at-end switches, the warm budget
property). Nothing was committed from it and nothing left a tracked path, so no removal commit and no CHANGELOG line for
detached code. The temporary pass traces in `PassiveStepSolver` and `TrBdf2StepSolver` were removed before any commit.

## 10. Scenarios worth showing in the test world

- Rows B / D: a water generator (298 K) and a liquid methane generator (115 to 120 K) on one vessel: now "HELD (thermo
  domain): Water at 273.15999999999997 K is below 273.16 K in <vessel> (valid 273.16..900 K, ...); the retry failed the
  same way, so the island waits for a change to its network" (the status names the vessel).
- Row E: liquid methane (115 K, 200 kPa) and CO2 (250 K, 250 kPa) through a 1000 L vessel to a void: fewer soft-budget
  holds during the flush (worst interval about half); about 85 s after the liquid has drained the vessel enters
  failure 1's window (methane about 1e-8) and holds on the round deadline, as row A.
- Row A: nitrogen then CO2 at 400 kPa into a vessel at its sublimation line: still the round-deadline hold (failure 1,
  the owner's decision).
- The pilot chain: two vessels of different mixtures (10 % CO2 in N2 at 250 K; N2 at 140 K) joined through a junction
  (three pipe pieces): settles at one pressure without a numerical hold.
