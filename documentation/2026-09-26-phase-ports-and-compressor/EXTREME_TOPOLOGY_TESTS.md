# Extreme topology tests: generator/tank grid and alternating rows

Batch `2026-09-26-phase-ports-and-compressor`, 2026-09-26. Branch `claude/extreme-topology-tests-wip` (from `9c723e0`,
phase-ports WP1 and WP2). Test class `src/test/java/com/wormzjl/createcheme/runtime/fluid/ExtremeTopologyIslandTest.java`.
Measured with the Minecraft-free harness (`tools/cloud-science-harness`), OpenJDK 21.0.10, 4 cores, with another agent's
Gradle daemon running on the machine (wall ms are indicative only).

## 1. The owner's cases and how they were read

1. "10 generator in one line with 10 pipe block, connected to another 10 pipe blocks and 10 tanks, each with different
   pressure." Read as a 10 x 4 block grid. A straight 40-block line is the only other reading and is degenerate (nine
   generators and nine tanks would touch only each other: `PhysicalFluidTopology.compile` skips a boundary-boundary pair).
2. "10 generator with 10 tanks arranged in between them, with a line of pipes connecting all on the sides, each with
   different pressure." Read as one row alternating G, T, G, T, ... with a parallel line of 20 pipe blocks beside it. "On
   the sides" also reads as a pipe line on both sides of the row; that is a second fixture (`ALTERNATING_BOTH_SIDES`).

Nitrogen at 350 K in every generator and tank; world defaults: 1 m3 per generator and tank, 1 m of 50 mm pipe per block
(roughness 45 um), 100 m/s maximum velocity. x east, z south, all blocks at y = 64. Pressures in kPa.

```
Case 1, GRID
  x:     0    1    2    3    4    5    6    7    8    9
  z=0  G110 G140 G170 G200 G130 G160 G190 G120 G150 G180     generators
  z=1    P    P    P    P    P    P    P    P    P    P
  z=2    P    P    P    P    P    P    P    P    P    P
  z=3  T140 T110 T180 T150 T120 T190 T160 T130 T100 T170     tanks

Case 2, ALTERNATING (z=1 line) and ALTERNATING_BOTH_SIDES (z=1 and z=-1 lines)
  x:     0    1    2    3    4    5    6    7    8    9   10   11   12   13   14   15   16   17   18   19
  z=-1   P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P   (both sides only)
  z=0  G110 T140 G140 T110 G170 T180 G200 T150 G130 T120 G160 T190 G190 T160 G120 T130 G150 T100 G180 T170
  z=1    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P
```

Generators 110 to 200 kPa in one order, tanks 100 to 190 kPa in another, so several tanks start above the generator next
to them (grid columns 0 and 5; row positions 1, 5, 11). The reduced cases scale every pressure's distance from 155 kPa
(generators) and 145 kPa (tanks) by a spread factor; spread 0.2 gives generators 146 to 164 kPa and tanks 136 to 154 kPa.

## 2. Compiled structure (asserted)

A pipe block with exactly two connections collapses into the run through it; every other block is a node; every maximal
run between nodes is one compiled connection.

| Fixture | Blocks | Nodes | Generators | Tanks | Junctions | Connections | Rule |
|---|---|---|---|---|---|---|---|
| GRID (n = 10 columns) | 40 | 40 | 10 | 10 | 20 | 48 | every pipe block has 3 or 4 connections: 4n nodes, 5n - 2 connections |
| ALTERNATING (k = 10 pairs) | 40 | 38 | 10 | 10 | 18 | 37 | the two end pipe blocks collapse: 2k - 2 junctions, 4k - 3 connections |
| ALTERNATING_BOTH_SIDES | 60 | 56 | 10 | 10 | 36 | 74 | the pipe part doubled |

One island each, no compiler diagnostic, every connection a boundary stub onto a junction or a junction-junction run.

## 3. Oracles

For each case, forty 5 s intervals and four hundred 0.1 s intervals, every interval a job as the island runtime runs it
(`replayStart` from the committed interval, default settings). Asserted on both slice lengths:

- every interval completes, FULL acceptance, advanced = requested;
- ledgers: tanks plus junction holdups against the generators' boundary transfers, component < 1e-12 and energy < 1e-10
  relative to max(1, initial) after every interval;
- no generator receives more than the solver's zero-flow threshold (1e-10 kg/s x interval);
- no tank rises above the highest generator `P_max`; **the lowest tank pressure is nondecreasing** (the monotonicity the
  physics defends: a junction's net-mass row is algebraic and every connection's flow is nondecreasing in its driving
  pressure, so a chain of falling pressures ends at a receiving boundary, which can only be a tank; single tanks are not
  monotone, a tank at the top of the band discharges into the mesh first: 7 or 8 of 10 tanks were monotone at 0.1 s at
  full spread);
- directions, from interval 2 on (interval 1 starts from the compiler's junction guess): a boundary more than 100 Pa above
  its junction at both ends of an interval sends fluid out over it; one more than 100 Pa below at both ends takes none out
  (a tank receives, a generator is closed);
- in the first 0.1 s the highest generator supplies and the lowest tank receives;
- at the end every tank is at `P_max` within 1e-6 relative (measured at most 1.4e-4 Pa), and every generator more than
  100 Pa below `P_max` ends CLOSED;
- 5 s against 0.1 s at t = 5, 10, ..., 40 s: tank pressure, tank moles and total supplied mass within 2 %, tank temperature
  within 0.5 K. This is the bound of `CadenceTrajectoryQualificationTest` (`CADENCE_TOLERANCE`); `MixedGasJunctionTransientTest`
  has no 5 s against 0.1 s comparison. The split of the supply between generators is printed, not asserted.

Every run reaches rest within the first 5 s (0.28 kg/s per capped 50 mm connection against about 1 kg per tank), so the
cadence comparison is decided at t = 5 s and is trivially met after it.

## 4. Results

### 4.1 Full cases at the default cap: open defect

All three full fixtures hold on their first interval at both slice lengths (`theFullCasesHoldOnTheirFirstIntervalOpenDefect`
asserts exactly this while `FULL_CASES_OPEN_DEFECT = true`):

| Fixture | Slice | Result |
|---|---|---|
| GRID | 5 s | interval 1: Newton iteration limit at residual 0.5274; active-set pass=0; reasons: Nitrogen pressure < 100 Pa x5, > 2 MPa x13, line search stalled x1 |
| GRID | 0.1 s | interval 1: Newton iteration limit at residual 0.6011; active-set pass=0; reasons: > 2 MPa x13, < 100 Pa x6 |
| ALTERNATING | 5 s | interval 1: line search stalled at residual 2.5106; active-set pass=0; reasons: > 2 MPa x16, < 100 Pa x3, singular Jacobian x1 |
| ALTERNATING | 0.1 s | interval 1: line search stalled at residual 2.5247; active-set pass=0; reasons: > 2 MPa x18, < 100 Pa x1, singular Jacobian x1 |
| BOTH_SIDES | 5 s | interval 1: Newton iteration limit at residual 2.4947; active-set pass=0; reasons: singular Jacobian x2, > 2 MPa x16, < 100 Pa x2 |
| BOTH_SIDES | 0.1 s | interval 1: line search stalled at residual 2.5231; active-set pass=0; reasons: singular Jacobian x2, < 100 Pa x2, > 2 MPa x16 |

Each spends 21 Newton solves (20 halvings of the first step) in about 0.4 s.

### 4.2 Full cases with the cap at the configuration's largest value (100000 m/s)

`theFullCasesIntegrateWhenTheVelocityCapDoesNotSaturate`: the acoustic bound caps instead (about 320 m/s here). All
oracles pass.

| Fixture | Slice | Intervals | Newton solves | Accepted / rejected | Worst moles | Worst energy | ms | Monotone tanks |
|---|---|---|---|---|---|---|---|---|
| GRID | 5 s | 40/40 | 160 | 143 / 5 | 4.90e-16 | 2.22e-16 | 1385 | 10/10 |
| GRID | 0.1 s | 400/400 | 434 | 412 / 8 | 4.68e-15 | 1.91e-15 | 2143 | 7/10 |
| ALTERNATING | 5 s | 40/40 | 182 | 144 / 5 | 1.86e-15 | 2.25e-15 | 672 | 10/10 |
| ALTERNATING | 0.1 s | 400/400 | 443 | 413 / 9 | 3.06e-15 | 2.96e-15 | 1726 | 7/10 |
| BOTH_SIDES | 5 s | 40/40 | 216 | 145 / 6 | 1.37e-15 | 2.42e-15 | 1270 | 10/10 |
| BOTH_SIDES | 0.1 s | 400/400 | 463 | 414 / 8 | 1.40e-14 | 3.07e-15 | 2673 | 8/10 |

5 s against 0.1 s (largest relative differences over t = 5..40 s): GRID pressure 1.06e-5, temperature 0.006 K, moles
2.33e-5, supplied 3.14e-5, generator split 0.8 %; ALTERNATING 3.51e-3, 0.326 K, 2.89e-3, 6.94e-3, 0.4 %; BOTH_SIDES
1.16e-5, 0.004 K, 1.06e-5, 2.25e-5, 0.2 %.

### 4.3 Reduced cases at the default cap (spread 0.2)

`theCasesAtAFifthOfTheSpreadIntegrate`. All oracles pass.

| Fixture | Slice | Intervals | Newton solves | Accepted / rejected | Worst moles | Worst energy | ms | Monotone tanks |
|---|---|---|---|---|---|---|---|---|
| GRID | 5 s | 40/40 | 142 | 127 / 3 | 2.53e-15 | 1.55e-15 | 571 | 10/10 |
| GRID | 0.1 s | 400/400 | 410 | 400 / 0 | 4.35e-15 | 3.50e-15 | 1418 | 8/10 |
| ALTERNATING | 5 s | 40/40 | 141 | 127 / 3 | 8.55e-16 | 1.89e-15 | 443 | 10/10 |
| ALTERNATING | 0.1 s | 400/400 | 413 | 401 / 1 | 3.88e-15 | 7.61e-15 | 1247 | 10/10 |
| BOTH_SIDES | 5 s | 40/40 | 153 | 128 / 4 | 2.80e-15 | 4.70e-15 | 868 | 10/10 |
| BOTH_SIDES | 0.1 s | 400/400 | 433 | 402 / 2 | 7.57e-16 | 3.37e-15 | 2357 | 10/10 |

5 s against 0.1 s: GRID pressure 5.7e-7, temperature 0.0006 K, moles 1.5e-6, supplied 2.6e-6, generator split 3.2 %;
ALTERNATING 1.0e-4, 0.010 K, 7.5e-5, 4.7e-4, 2.0 %; BOTH_SIDES 3.3e-7, 0.00004 K, 2.6e-7, 1.0e-6, 1.0 %. The generator
split is the one number above 2 %: at spread 0.2 the generators are 2 to 4 kPa apart and which of them supplies during
the first second depends on the step sequence; the tank states and the total delivered agree to 5e-4 or better.

### 4.4 Where the default-cap failure starts (first interval only)

| Reduction | Passes | Fails |
|---|---|---|
| GRID columns, full pressures | 1, 2 | 3 (and 4, 5, 6, 8, 10 at 0.1 s; 4 passes at 5 s) |
| ALTERNATING pairs, full pressures | 2, 3 | 4 to 10 |
| BOTH_SIDES pairs, full pressures | 2, 3 | 4 to 10 |
| GRID spread (10 columns) | 0.01, 0.1, 0.2, 0.22 to 0.28 | 0.3 at 0.1 s (passes at 5 s), 1 |
| ALTERNATING / BOTH_SIDES spread | 0.1, 0.2, 0.28 | 0.22, 0.24, 0.26, 0.3, 1 |
| GRID, all generators 200 kPa, tanks as case 1 | yes | |
| GRID, generators 200 kPa, tanks 100 kPa | yes | |
| GRID, generators as case 1, tanks all 100 kPa | | yes |
| GRID, generators 110..200 kPa by column, tanks 10 kPa below | | yes |

The boundary is irregular, as a Newton basin is, not a physical threshold. Once the first interval is accepted every later
interval was accepted in every run measured.

## 5. Mechanism of the open defect (read from the solver; `src/main` not modified)

1. On a first solve of a structure no start-of-solve closure reaches a generator edge: `PassiveStepSolver.closeDeadHeads`
   skips any run that ends at a junction, and `closeIllegalStarts` returns until a solve on the same structure has been
   accepted. So pass 0 of the first step solves every generator as a two-way fixed-pressure boundary: a through-flow
   network between generators 10 to 90 kPa apart, in which the low generators are sinks. Only a converged pass lets the
   `illegalDirection` rule close them.
2. The default cap saturates early: nitrogen at 350 K and 150 kPa reaches 100 m/s at 1455 Pa over a half-block stub and
   2910 Pa over a one-block run (`EXTREME_TOPOLOGY_CAP` line). In that through-flow network most connections are on the
   cap's branch (`Equations`, "A saturated pressure/flow law"): `(copySign(limitDrop, driving) - loss(q)) / capScale`,
   which depends on the flow only, not on the end pressures.
3. A junction whose connections are all on that branch keeps only the density of its own outflow caps in its pressure
   column: near singular (the alternating rows report a singular LU outright). The Newton step in that junction pressure
   leaves the nitrogen domain (the "> 2 MPa" and "< 100 Pa" rejections), the pass fails at the iteration limit or in the
   line search, and `phaseCorrection` has nothing to change, so the step is rejected at "active-set pass=0".
4. Halving the step does not help: a junction's mass is pinned, its rows are algebraic and independent of dt, and the tanks
   barely move in a short step. Twenty halvings exhaust the interval at 5 s and at 0.1 s alike.

Evidence: equal generator pressures pass whatever the tanks hold (no through-flow); raising the cap tenfold (to the
acoustic bound) integrates all three full fixtures; reducing the spread to 0.2 integrates them at the default cap.

## 6. Options (not implemented)

- **O1. Close one-way boundaries before the first pass by a physical bound.** A generator edge whose run ends at a junction
  cannot supply if the generator is below the lowest pressure the junction can take. The discrete minimum principle gives
  that bound only from the boundaries, so a usable form is weak; a stronger form is to run pass 0 with every generator edge
  one-way from the start (a complementarity row `min(q, driving - loss) = 0` or its smoothed Fischer-Burmeister form) so
  the through-flow network never enters the Newton.
- **O2. A cold rate seed with one-way generators.** `coldRateSeed` solves the port graph with generators two-way too; seeding
  the junctions from a rate solve whose generator edges are closed where the solve shows reverse flow (an outer loop of
  the same active-set rule) would start pass 0 near the physical active set.
- **O3. Keep a pressure dependence on the cap's branch.** A small slope beyond the cap (the row
  `loss(q) = copySign(limitDrop, driving) + eps (driving - copySign(limitDrop, driving))`) keeps every junction's pressure
  column nonzero. The price is a flow past the cap: the loss exceeds the cap's drop by eps times the unused driving
  pressure, so eps must be chosen against the largest excess an island can show (90 kPa over a 1.5 kPa cap drop here).
- **O4. Bound the Newton update of a junction pressure** to the hull of its island's boundary pressures (the minimum and
  maximum principle), which removes the domain excursions; on its own it may still leave a stalled pass.
- **O5. Continuation at cold start.** Solve the first step with the boundary pressures pulled toward their mean (spread 0.2
  converges here) and relax to the real ones in a few Newton continuations; costlier and less direct than O1 to O3.

O1 or O2 attack the cause (a through-flow active set that cannot occur physically); O3 the numerical symptom that makes it
fatal. Any change must keep the junction lines of `harness.sh runtime` (MIXED_GAS/LIQUID_JUNCTION) identical or be
re-qualified.

## 7. Water variant (not added as a test)

Water generators at 350 K into nitrogen tanks, same geometry. It is not cheap: at spread 0.2 and the default cap the grid
holds on interval 1 at both slices (iteration limit, "Nitrogen temperature > 900 K"), the alternating row holds at
interval 3 at 5 s and interval 102 at 0.1 s ("Hydrocarbon vapour partial pressure below the numerical floor", 521 and 1036
rejected substeps), both sides at interval 1 at 5 s (substep limit, 540 accepted / 484 rejected) and interval 46 at 0.1 s;
with the cap at 100000 m/s all three full water fixtures hold on interval 1. A water fill of nitrogen tanks through a
junction mesh is a separate campaign, not a variant of these tests.

## 8. How to run

```
REPO=<worktree> LIB=<jar folder> bash tools/cloud-science-harness/harness.sh compile
REPO=<worktree> LIB=<jar folder> bash tools/cloud-science-harness/harness.sh select --select-class=com.wormzjl.createcheme.runtime.fluid.ExtremeTopologyIslandTest
```

(10 tests, about 22 s; `harness.sh runtime` includes them: 237 tests passed at this commit, junction lines identical to
the reference.) Output lines: `EXTREME_TOPOLOGY` (one per case and slice), `EXTREME_TOPOLOGY_CADENCE`,
`EXTREME_TOPOLOGY_STRUCTURE`, `EXTREME_TOPOLOGY_CAP`, `OPEN DEFECT EXTREME_TOPOLOGY`. Under Gradle the class is part of
`fluidRuntimeTest` (package `runtime.fluid`).

The scan that located the boundary (sections 4.4 and 7) was a temporary test method, not committed; its source was kept
outside the tree for the batch's tools folder (see the task report).
