# The static head of a connection between two fluids of different density

Worktree `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-a28b2d53abd8b3809`,
branch `claude/elevated-line-probe`, base `c7da543`.

**Headline.** The risk recorded in `TANK_NODE_BLOCK.md` section 4 is real and it bites hard. Three of
the four elevated shapes hold on the unmodified tree, and the mechanism is the one that was
predicted, confirmed at the bit level: the hydraulic row of a connection with an elevation
difference has a step of exactly `(rho_first - rho_second)*g*dz / pressureScale` at zero flow, and a
settled line lands on the plateau that step makes. Measured on a water generator four blocks below a
nitrogen-charged tank at 400 kPa, the row reads `-3.811889274876e-04` at `q = 0+` and
`+2.716032506236e-02` at `q = 0-`; the difference is `2.7541514e-2` against a predicted
`(996.31 - 715.46)*9.80665*4/400000 = 0.027541513989850674`. The linear model predicted `1.08e-19`,
the full step produced `2.76e-2`, and every backtrack from `alpha = 1` down to `1.2e-7` landed on the
same `2.716e-2` value.

The fix is F4's rule applied to the hydraulic row: the density in `driving` is a **column of the
pass**, stated once from the pass's own seeds and never taken from the live iterate. With it, the
four driven elevated lines run forty intervals and settle on their hydrostatic offsets to under a
pascal, and the change is **bitwise nothing** for every island whose devices sit at one y — which is
every island that existed before this branch.

One line still holds, and it is honest to say so: the dead-headed vertical line at 101.325 kPa, where
water cannot rise four blocks and the only direction left is one the generator forbids. That is a
**pre-existing defect with no elevation in it**, demonstrated in section 6 by four flat controls that
hold identically on the unmodified solver.

Everything below is measured. Where I predict rather than measure, I say so.

---

## 1. Setup verification

| Step | Result |
| --- | --- |
| `git checkout -b claude/elevated-line-probe claude/full-tank-solids-event` | done |
| `git log --oneline -1` | `c7da543 Drive a configuration event the way the world drives one` — as required |
| Other checkouts touched | none written. `D:\Minecraft\Modding\1.21\CreateChemE` and every other worktree were read only. Four files were *copied out* of read-only references into this worktree: the MCP bridge jar from `solid-phase-fluid-system-plan-4369b0`, and `cmd.sh` / `mc.sh` / `poke.ps1` from `agent-ab8d04b83a80709c0`'s `build/mcp`. `Flatten.class` and `Poke.class` were used in place from `agent-a74f606336340ffe9`'s `build/flatten` on the classpath, not copied. |
| `git stash` | never used, bare or otherwise. The two baseline comparisons that needed the unmodified tree were taken by copying `PassiveStepSolver.java` to `/tmp/elev`, `git checkout --` on it, measuring, and copying it back. |
| Gradle concurrency | `Get-Process java \| Select Id,CPU` before every invocation, sampled twice five seconds apart at the start to confirm the six idle daemons were flat (the busiest moved 0.016 s of CPU in 5 s). No suite ran while the dev client was up; the client was started only after every suite, the regression, the benchmark and the GameTest server had finished. |
| `python` in Git Bash | not used. Everything is Bash, PowerShell, Gradle or a small Java helper. |
| Commit trailer | every commit ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. |

### Baselines, re-measured on this checkout before any change

| Gate | Baseline |
| --- | --- |
| `fluidScienceTest` | 161 tests, 0 skipped, 0 failures |
| `fluidRuntimeTest` | 121 tests, 0 skipped, 0 failures |
| `fluidSolverRegression -PfluidRegressionMode=exact` | `chain-100` 0.000e+00 on all four gates; `quiet-11312`, `quiet-11324`, `cold-11312` SKIP (no readable `core.dat` in this worktree, as for F3 and F4) |
| `fluidNetworkBenchmark` | 30/9, 19/14, 37/3, all CONVERGED |
| `runFluidGameTestServer` | 20 required tests |

---

## 2. What the geometry actually compiles to

Checked before assuming it. `PhysicalFluidTopology.compile` reads a node's elevation straight off its
block y (`new TopologyCompiler.Node(d.id, d.kind, p.y)`), and a virtual mid-node between two device
blocks takes the midpoint of the two. `TopologyCompiler` then collapses a plain pipe run into one
`PassiveNetwork.Pipe` carrying the run's segment geometries, so a stack of pipe blocks does **not**
become a chain of one-metre edges: a generator at y = -59 with three pipes above it and a tank at
y = -55 compiles to **two nodes and one connection with `dz = 4`**. That is asserted permanently by
`aVerticalPipeStackCompilesToOneEdgeWithAnElevationDifference`, so a later compiler change that stops
collapsing the run shows up rather than silently retiring these fixtures.

The consequence matters for the probe: the whole four metres of head sits on a single row, not spread
over four rows of one metre each.

Vertical connectivity needs the right facing on a device. `Device.connects` requires
`direction · facing != 0` for a filter or an actuator, so the vertical filter faces `UP` and the
vertical pump faces `UP`; a pipe or a boundary connects in all six directions. The pump's outlet
junction is minted at the pump's own block, one above the generator, so a pumped vertical line puts
one block of column between the generator and the pump and three between the pump and the tank.

---

## 3. The probe, on the unmodified tree

`ElevatedBlockLineIslandTest` (permanent, committed) and a temporary `ElevatedHeadStallProbe` under
`science/fluid/network` (deleted before the first commit) build the lines through the real
`PhysicalFluidTopology` the way `FilterBlockLineIslandTest` does and run each for 40 x 5 s intervals
on one retained `PassiveIntervalSolver` from `RetainedSolver.COLD_START_SECONDS`. Generator 1 m³
water at P; tank 1 m³ nitrogen-charged; `LIFT = 4` blocks.

| line | 101.325 kPa | 400 kPa |
| --- | --- | --- |
| flat control, generator + 4 pipes + tank | **OK 40/40**, tank 101325.0 Pa | **OK 40/40**, tank 400000.0056 Pa |
| rising, generator -> 3 pipes up -> tank | **HELD @1**, interval substep limit, 505 accepted / 519 rejected, `Phase/device active-set cycle=2` and `Newton line search stalled at residual #=517`, last 4.43405387689597e-4 | **HELD @1**, substep refinement exhausted, `Newton line search stalled at residual 1.062368636941215E-9` |
| falling, generator -> 3 pipes down -> tank | **HELD @7** (t = 30 s), `Newton line search stalled at residual 1.2592855639213187E-9`, tank at 140201.048 Pa | **HELD @1**, `Newton line search stalled at residual 1.0691918455986843E-9` |
| rising, generator -> pipe -> filter -> pipe -> tank | **HELD @1**, `Empty fluid initialization` | **HELD @1**, `Empty fluid initialization` |
| rising, generator -> pump -> 2 pipes -> tank | OK 40/40, tank 562255.2507 Pa | not reached (the fixture aborted on the line above) |
| balanced: tank charged at 360918.3068 Pa under a 400 kPa generator | — | **HELD @1**, 511 accepted / 514 rejected, last `Newton line search stalled at residual 1.0000001544243826E-9` |

Three observations worth recording before the dumps.

* The `1.0e-9` to `1.3e-9` residuals are the **same tolerance-floor signature** F4 recorded for the
  tank behind a valve, on a different row.
* The pumped line passes at 101.325 kPa and settles at `562255.25` Pa, which is the pump's shutoff
  `601325` less `39070` of water column — the physically right answer, reached by accident, because
  the pump holds the flow firmly positive and the line never visits the switch.
* The **flat control is unaffected at every pressure**, which is the statement that this is about the
  elevation term and nothing else.

---

## 4. The dumps

`ElevatedHeadStallProbe` hooked a temporary `PassiveStepSolver.STALL_PROBE` consumer into the
`SparseNewton.Nonconvergence` catch and dumped, at the stalled point: the labelled point and
residual, the edge row swept across its own flow column with everything else held, a dense one-sided
Jacobian built with the solver's own `differenceScale`, the Newton direction from it, and the
residual along that direction. Both the hook and the probe were removed before the first commit.

### 4.1 The jump is the density, to the digit

Rising line at 400 kPa, `dt = 0.341151 s`, active-set pass 0. Two nodes, six rows: five for the
tank's block and one for the connection.

```
edge 0->1  dz=4.0  rho_a=996.3059037129602  rho_b=715.4606202588686  pressureScale=400000.0
  P_a=400000.0  P_b=361070.78240840824  dP=38929.21759159176
  head forward rho_a*g*dz=39081.6931625868   head reverse rho_b*g*dz=28065.087566646536
  PREDICTED ROW JUMP at q=0 = (rho_a-rho_b)*g*dz/scale = 0.027541513989850674   (Pa: 11016.60559594027)

  row  6 edge0.flow/row         x=+3.675277802572e-08  f=-3.811889290928e-04
   q=+1.0e-06   edgeRow=-3.811889711630e-04
   q=+1.0e-12   edgeRow=-3.811889274876e-04
   q=+0.0e+00   edgeRow=-3.811889274876e-04
   q=-1.0e-16   edgeRow=+2.716032506236e-02
   q=-1.0e-12   edgeRow=+2.716032506236e-02
   q=-1.0e-02   edgeRow=+2.716076376421e-02
```

`2.716032506236e-02 - (-3.811889274876e-04) = 2.7541514e-2`, and the predicted jump is
`0.027541513989850674`. They agree to every digit printed. Nothing else in a two-node island can
produce a step there: the friction term is `pressureDrop(flow, rho, mu)`, which is zero at zero flow
whatever the donor is, so its own upwind switch is continuous. Note also how **flat** each branch is
— `q = -1e-16` and `q = -1e-2` differ only in the eighth digit — which is the definition of a degree-
zero jump rather than a slope.

The falling line at 400 kPa gives the same identity with the sign reversed: branches at
`-8.014551278530e-04` and `-2.137961269689e-02`, difference `-2.0578158e-2`, predicted
`-0.020578157569038472`.

The rising line at 101.325 kPa: branches at `-3.362451411477e-01` and `+4.888823374623e-02`,
difference `0.38513338`, predicted `0.3851333748939722`.

### 4.2 The step and the plateau

Same point, rising at 400 kPa:

```
   ||d||inf=0.3428330346145589 at column 6 (edge0.flow/row)
     d[ 6] edge0.flow/row         = -3.428330346e-01
   linear prediction ||f+Jd||inf=1.0842021724855044E-19
   alpha=1.000e+00  ||f||inf=2.763456330770e-02  flow=-3.428330e-01
   alpha=1.250e-01  ||f||inf=2.720940044294e-02  flow=-4.285409e-02
   alpha=7.813e-03  ||f||inf=2.716339233856e-02  flow=-2.678346e-03
   alpha=4.883e-04  ||f||inf=2.716051676587e-02  flow=-1.673622e-04
   alpha=3.052e-05  ||f||inf=2.716033704232e-02  flow=-1.042568e-05
   alpha=1.192e-07  ||f||inf=2.716032510755e-02  flow=-4.116104e-09
   alpha=5.960e-08  ||f||inf=3.811889063723e-04  flow=+1.631834e-08
   alpha=1.164e-10  ||f||inf=3.811889290484e-04  flow=+3.671287e-08
```

The linear model predicts `1.08e-19` and the full step delivers `2.76e-2`, seventeen orders apart.
The residual is flat at `2.716e-2` across **six orders of `alpha`** and then drops back to the base
value the moment the flow crosses back to positive. A residual that does not vary with the step
length is not a slope; it is a step. This is F4's figure exactly, one row over.

Why the Jacobian does not know is also F4's answer: `differenceScale` for a flow unknown is
`max(1, |x|) = 1`, so the flow column is differenced from `+3.7e-8` to `+1e-6`, entirely on the
positive branch, where the head is the generator's water and does not move. `J[edge][flow]` comes out
as the friction slope alone, the direction points the other way, and the derivative it was computed
from does not hold along it.

### 4.3 Why a settled elevated line goes there at all

This is the part that differs from F4 and is worth stating, because it says the defect is not merely
numerical. Write `g(q)` for the connection's own balance. For `q >= 0` it is
`dP - rho_first*g*dz - loss(q)` and for `q < 0` it is `dP - rho_second*g*dz - loss(q)`; both branches
decrease in `q`, and at `q = 0` the function steps **down** by `(rho_first - rho_second)*g*dz`. So
whenever

```
rho_second*g*dz  <  dP  <  rho_first*g*dz
```

the equation has **no root at all**: the forward branch wants a negative flow and the backward branch
wants a positive one. A rising line fills its tank until `dP` falls to `rho_first*g*dz`, which is the
upper edge of exactly that band — so a tank settling on its generator does not approach a root, it
approaches a gap. At 400 kPa the band is 28065 Pa to 39082 Pa wide of `dP` and the stalled point sits
at 38929, inside it. That is why refinement cannot help and why the hold arrives the moment the line
settles rather than while it is filling.

---

## 5. The rule

`PassiveStepSolver.Equations.headDensities`: one density per connection, built in the `Equations`
constructor from the pass's own seeds, next to `pressureScales` and `junctionDonorFirst`.
`edgeRows` reads it:

```java
double driving=st[a].pressure()-st[b].pressure()-headDensities[edge]*GRAVITY*dz+signedHead;
```

`rho` (the live donor's density) still serves the friction term and the velocity cap, which is
correct: `pressureDrop` is odd in the flow and zero at zero, so its upwind switch carries no jump.

**Which column.** A column states which end fills the connection, so a column is admissible exactly
when the driving pressure it produces points away from the end it was read off. With
`drivingFirst = dP - rho_first*g*dz` and `drivingSecond = dP - rho_second*g*dz`, where `dP` includes
the actuator head at the pass's start point:

```java
if(drivingFirst>=0&&drivingSecond>=0)fromFirst=true;
else if(drivingFirst<=0&&drivingSecond<=0)fromFirst=false;
else if(drivingFirst<0)fromFirst=Math.abs(drivingFirst)<=Math.abs(drivingSecond);
else fromFirst=initialPoint[edgeOffset+edge]>=0;
```

Four measured decisions are in those four lines.

* **The start point's flow sign alone is not enough, in either direction.** The first version of this
  rule simply froze the live donor at the pass's start point. It fixed the three driven holds
  outright, but it left the dead-headed line at 101.325 kPa stalling: the start flow there is exactly
  zero, which reads as the water column, and the water column then demands 39 kPa the generator has
  not got, so the pass is sent looking for a reverse flow the whole way across zero and stalls on the
  friction kink there — the slope of `loss` differs by three orders between water and nitrogen, so
  the Newton's own direction overshoots the reverse root by a factor of sixty. Reading the zero the
  other way is equally wrong: the existing science fixture
  `HydraulicMatrixQualificationTest.hydrostaticRestAndReversedElevationUseTheSameGravityEnergyLedger`
  stands a liquid line on its own two-metre column, where the two ends differ only by water's
  compressibility, and a rule that took the lighter end there left 0.18 Pa of driving pressure and
  `8.340814625899689E-7 kg/s` of permanent flow through a graph that must be at rest. That fixture
  caught the bug; it is the one place in the tree that already had elevation on a connection, and I
  was wrong to write in an earlier draft that the tree was flat everywhere.
* **The actuator head belongs in `dP`.** Without it a pump's connection is decided on the gap between
  its suction and its discharge, which at rest is the pump's whole head with the sign of a backflow,
  so every pumped connection read its discharge as the donor. Measured: the pumped vertical line at
  101.325 kPa settled at 557222.95 Pa instead of 562255.25 — 5 kPa low, because the tank's own bulk
  density (815 kg/m³, water plus a compressed nitrogen headspace) was standing in for the water in
  the pipe. With the head included it settles at 562255.2498 against a predicted 562254.98.
* **Neither column admissible is not the same case as both.** They are opposites, and a single
  fallback for both is wrong. Neither admissible is the band of section 4.3, where a settled line
  lives; there the column nearest its own rest point is taken, which makes rest a fixed point of the
  rule as well as of the equations — a line standing at `P_a - P_b = rho*g*dz` has no driving
  pressure at all under `rho` and the whole difference between the two heads under the other one, so
  it keeps the column it is balanced under. Both admissible is genuine bistability, and there the
  start point's flow sign is the right evidence and the only evidence, because which fluid is in the
  pipe is exactly the history the model does not otherwise carry. Deciding bistability by rest point
  instead was implemented and measured: the falling four-block line at 400 kPa stopped at
  **429851.13 Pa** against the **439081.69** its own water column demands, because the tank's mixture
  balances 9 kPa earlier and a filling line meets that point first.
* **The column is not revised by the outer active-set loop, and it is not part of the cycle key.**
  Revision cannot work, for the reason section 4.3 gives: in the band each column puts the flow on the
  other one, so a rule that turned the head over on a converged disagreement would alternate forever.
  Keying on it was also implemented — as a real boolean per connected elevation and a constant `true`
  for every flat connection, so a flat island's workspace identity could not move — and **measured
  worse**: the vertical line standing at its own hydrostatic balance went from forty intervals settled
  at 360918.3068 Pa to a hold on `Newton iteration limit at residual 2.202403844425388E-7`, because
  the extra key component churns the four-entry workspace cache and the modified Newton loses the
  preconditioner it was reusing. It was fully reverted. The column belongs where `pressureScales`
  belongs: a quantity each pass reads off its own seeds, revised only by the seeds moving.

**Reconstruction consistency.** Nothing was needed. `ConservativeTransport` upwinds material and
energy on the flow's sign and never reads a density for the head, and the acceptance gate re-evaluates
`equations.residual(reconstructed)` on the **same** `Equations` object, so the reconstructed point is
checked against the column the pass was solved under by construction. That is in fact an improvement
on its own: a reconstruction that lands on the other side of zero flow no longer sees a 2.7e-2 jump in
the gate it has to pass.

**Why it cannot recur.** It is the same rule F4 stated, and the sentence is unchanged: *a decision
that changes which physical stream a row reads is a decision of the pass, not of the residual.* The
static head reads a stream — the one standing in the connection — and it was the last term still
taking it from the live iterate.

---

## 6. What still holds, and why it is not this

The dead-headed vertical line at 101.325 kPa still holds, and so does its inline-filter version
(`Empty fluid initialization`, a junction mixture of what nothing delivers). A water generator with a
nitrogen tank four blocks above it at the same pressure cannot lift the column, and the only direction
left — gas down into the generator — is one `PassiveStepSolver.boundaryAllowed` forbids, so the model
answer is a flow of zero reached through the boundary closure. It does not get there.

**That is not about elevation.** The identical situation at one y, made by charging a flat tank above
its generator instead of lifting it, holds in exactly the same way, and does so on the **unmodified**
solver:

| flat line, generator water at 101325 Pa | tank charged | adverse dP | result on `c7da543` | result after the fix |
| --- | --- | --- | --- | --- |
| generator + 4 pipes + tank | 101326 Pa | 1 Pa | HELD, `Newton line search stalled at residual 1.9355024035429073E-5` | identical |
| generator + 4 pipes + tank | 110000 Pa | 8675 Pa | HELD, residual `0.0788636363636365` | identical |
| generator + 4 pipes + tank | 140395 Pa | 39070 Pa | HELD, residual `0.27828626375583176` | identical |
| generator + 4 pipes + tank | 200000 Pa | 98675 Pa | HELD, residual `0.493375` | identical |
| generator + pipe + filter + pipe + tank | 140395 Pa | 39070 Pa | HELD, `Newton iteration limit at residual 0.2946972516282591` | identical |

Every one of those numbers is bit-identical across the change, which it must be: `dz = 0` makes the
new term bitwise the zero it was. The residual `0.493375` is `98675 / 200000`, i.e. the whole adverse
pressure showing on a row whose flow cannot move at all.

The mechanism, from the dump, is a **different degree-zero problem that the elevated geometry merely
reaches**: the flow has to travel from zero to the reverse root, the friction slope on the reverse
(nitrogen) branch is some three orders steeper than on the forward (water) one, and the nonnegativity
step limiter pins `alpha` to nothing because the tank carries a phantom water trace at `1e-22` — the
`n[i] = total*1e-12` seed `initialPhaseSeeds` plants for every reachable component — whose only root
is exactly zero and which nothing delivers. It is the `JUNCTION_PHANTOM_TRACE` family on a reservoir
rather than on a junction. It is **not fixed here** and it is not in scope; it is recorded so the next
reader has the numbers.

`ElevatedBlockLineIslandTest.aDeadHeadedElevatedLineBehavesLikeItsFlatEquivalent` asserts the
*equivalence* rather than the failure, so it passes today and keeps passing when that defect is fixed.

The **boundary at which the head defect itself would bite** is stated by section 4.3 and is worth
having explicitly, because it is not a threshold on `dz` alone: the row's jump is
`(rho_a - rho_b)*g*dz / max(1e5, max(P_a,P_b))`, and it matters when the jump exceeds the Newton
tolerance the island is held to, which is `1e-9` (or `1e-10` for a small headspace). For water against
a filling tank at 400 kPa over four blocks that is `2.75e-2`, seven orders over. It would fall below
`1e-9` only at `dz*Δrho < 4e-6 kg/m²` — a millimetre of elevation against a density difference of four
grams per cubic metre. In practice **any** placed block of elevation between two nodes holding
different fluids is far over the line; what made the tree look healthy was that no fixture had one.

---

## 7. Qualification

`src/test/resources/fluid/regression/chain-100.json`, **untouched**. The other three reference islands
(`quiet-11312`, `quiet-11324`, `cold-11312`) SKIP in this worktree, as they did for F3, F4 and the
pump track — there is no readable `core.dat` here. That is a real limitation of this evidence and it
is the same limitation every recent change on this track has carried.

**`-PfluidRegressionMode=exact` against the existing reference — PASS.**

```
chain-100 vs reference: max deviation: state/moles 0.000e+00 relative (-), temperature 0.000e+00 K (-),
                                       phase fraction 0.000e+00 (-), flow 0.000e+00 relative (-)
BUILD SUCCESSFUL
```

The change is bit-identical for every island whose devices sit at one y, so the relative gate, the
re-capture and the re-verification the F3 discipline would need are all unnecessary and the committed
reference is unchanged. The bit-identity is structural rather than lucky: `dz` is exactly `0.0` on
such a connection, `rho*GRAVITY*0.0` is `0.0` for any finite positive density, and `x - 0.0` is `x`
for every finite `x` including `-0.0`. The only other thing the change could have moved is the
workspace identity, and `headDensities` deliberately stays out of `WorkspaceKey` (section 5).

`fluidNetworkBenchmark`: **30/9, 19/14, 37/3 — unchanged**, all three CONVERGED. No re-recording
needed.

---

## 8. Verification

Sequentially, one Gradle invocation at a time, `Get-Process java` checked before each. No suite ran
while the dev client was up.

| Check | Result |
| --- | --- |
| `fluidScienceTest` | **161 tests, 0 skipped, 0 failures** |
| `fluidRuntimeTest` | **125 tests, 0 skipped, 0 failures** — 121 before, plus the four new elevated fixtures |
| `fluidSolverRegression -PfluidRegressionMode=exact` | PASS at 0.000e+00 on all four gates of `chain-100`; three islands SKIP |
| `fluidNetworkBenchmark` | **30/9, 19/14, 37/3 — unchanged**, all CONVERGED |
| `runFluidGameTestServer -PfluidGameTestRunId=elevated-01` | **All 20 required tests passed**, 4.951 s |
| the whole table again on the final tree (`--rerun-tasks`, run id `elevated-02`) | 161 / 125 / 0.000e+00 / 30-9, 19-14, 37-3 / 20 passed in 5.223 s |
| seven-pressure filter sweep (`filterLineToATankKeepsIntegratingOnceTheTankIsFull`) | PASS, inside `fluidRuntimeTest` |
| valve fixture (`valveLineToATankKeepsIntegratingOnceTheTankIsFull`) | PASS |
| pumped fixtures (`aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn`) | PASS |
| full-tank solids fixtures (`SolidRuntimeTest`) | PASS |

`SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether`, three samples each side, same
session, nothing else running:

| | sample 1 | sample 2 | sample 3 |
| --- | --- | --- | --- |
| `c7da543` | 0.242 s | 0.247 s | 0.251 s |
| this branch | 0.252 s | 0.251 s | 0.251 s |

Within the noise of the measurement, which is what the exact gate already implies: this island is flat,
so its solve is bit-identical and the only added work is one pass over the connection list computing
two densities.

### The elevated fixtures' endpoints

400 kPa generator, water column over four blocks = **39081.6932 Pa** (predicted from
`rho(298.15 K, 400 kPa) = 996.3059 kg/m³`).

| line | result | tank endpoint | predicted | error |
| --- | --- | --- | --- | --- |
| flat control | OK 40/40 | 400000.0056 Pa | 400000 | 0.006 Pa |
| rising | OK 40/40 | 360918.4723 Pa | 360918.3068 | 0.166 Pa |
| falling | OK 40/40 | 439081.8102 Pa | 439081.6932 | 0.117 Pa |
| rising through a filter | OK 40/40 | 360918.6884 Pa | 360918.3068 | 0.382 Pa |
| rising through a pump | OK 40/40 | 860894.6709 Pa | 860918.3068 | 23.6 Pa |
| balanced (tank charged at 360918.3068) | OK 40/40 | 360918.3068374203 Pa | 360918.3068374132 | 7.1e-9 Pa |
| falling at 101.325 kPa | OK 40/40 | 140395.0221 Pa | 140395.0222 | 1e-4 Pa |

The pumped line's 23.6 Pa is physical rather than numerical and its fixture says so: the pump raises
the pressure by half a megapascal in the middle of the column and does work on the water doing it, so
the four blocks are not four blocks of one density. 23.6 Pa is 0.06 % of the head itself. Every
passive line is held to `1e-5` relative and the pumped one to `1e-4`.

---

## 9. In-game confirmation

The MCP bridge jar from `solid-phase-fluid-system-plan-4369b0` in `run/mods`, `pauseOnLostFocus:false`
in `run/options.txt`, `./gradlew.bat runClient --offline` started only after every suite, the
regression, the benchmark and the GameTest server had finished. Screenshots in
`documentation/screenshots/elevated-line-probe/`, all flattened.

### The line

Superflat, creative, commands on. `/setblock` at `createcheme:fluid_generator` `0 -59 0`,
`createcheme:fluid_pipe` `0 -58 0`, `0 -57 0`, `0 -56 0`, `createcheme:fluid_reservoir` `0 -55 0` —
the world's own defaults for every device, a four-block column between the generator and the tank
(`33-w2-line-wide.png`).

### A defect reproduced in the world first

The first attempt built the whole line at the generator's default 101.325 kPa, and the generator's
own screen (`08-generator-screen.png`) came up with

```
HELD: Substep refinement exhausted: Newton line search stalled at residual 5.066656016570093E-4;
      active-set pass=0
```

which is section 6's dead-headed line — the fixture records `5.066656785680298E-4` on the first
substep and the world's own first log line at 06:17:16 is `5.066656785680298E-4` **to every digit**.
That is a very tight world/test correspondence, and it is worth having even though the defect it
confirms is the pre-existing one.

It also has a consequence I had not predicted and should record: **a held island never applies the
configuration and topology events queued behind it.** Raising the generator to 400 kPa produced
`WAITING: configuration event / HELD: ...` and the setting never took; removing the pipes and the
tank did not dissolve the island either; and the island survived a save, a client restart and a
world reload, still retrying the same point at `committed_tick=1394` with its lag climbing 79.70 →
118.70 → 146.70 → 200.85 s. The only way out was to build elsewhere. That is a real operational
consequence of the dead-head defect and belongs with it.

### The 400 kPa run

Built in a clean world, in the order that avoids the dead-head: generator and the three pipes first
(an island with no second boundary does not solve, so it takes settings immediately), the generator
set to `400000` and applied — `Settings accepted at the current simulation event`, `FULL`,
`Pressure 400.00 kPa abs` (`31-w2-generator-400.png`) — and only then the reservoir placed on top.

The tank, after it filled and settled (`39-w2-tank-clean.png`):

```
Fluid Reservoir            FULL
Pressure     360.92 kPa abs
Temperature  298.23 K
Volume       1000.00 L
Mass         715.34 kg
Lag          3.45 s
L 0  W 72  V 28  S 0 %
```

| quantity | in-game | `ElevatedBlockLineIslandTest` | predicted |
| --- | --- | --- | --- |
| tank pressure | 360.92 kPa | 360918.4723 Pa | 360918.3068 Pa = 400 kPa − 39081.69 |
| tank mass | 715.34 kg | 715.3401670175984 kg | — |
| generator | 400.00 kPa | 400000 Pa | — |

The world reaches the unit fixture's endpoint to the full precision the screen shows, and the offset
from the generator is the four-block water column.

### Lag and log

Status is `FULL`, never `HELD`. Lag over two minutes of the settled line: **3.45, 1.50, 3.35, 2.15 s**
(`39-w2-tank-clean.png`, `40-lag-a.png`, `41-lag-b.png`, `42-lag-c.png`) — bounded and oscillating
inside the five-second cadence, against the held island's monotone 79.70 → 200.85 s.

```
$ grep -c "status=HELD" run/logs/latest.log
0
$ grep -c "fluid_island" run/logs/latest.log
0
```

Zero. `fluid_island=` lines are only emitted for a non-nominal status, so none at all is the stronger
statement. This is a fresh `latest.log` from the third client session, which loaded the saved world
and ran the elevated line for two minutes; the earlier sessions' log does contain `status=HELD`, and
every one of those lines is `fluid_island=10`, the dead-headed 101.325 kPa island of section 6.

The line also **round-trips the checkpoint**: saved, the client fully quit, restarted, world
reloaded, and the tank came back at `FULL`, `360.92 kPa`, `715.34 kg`, lag 0.70 s
(`27-tank-after-reload.png`).

The client was quit through `Save and Quit to Title` and then `Quit Game`; `Get-CimInstance
Win32_Process` for a `java.exe` with a Minecraft command line reports **no Minecraft java process
remains**, and the seven java processes left on the host are the same idle Gradle daemons that were
there before this session started.

---

## 10. What I did not do, and why

* **The dead-headed line is not fixed.** Section 6: it is a pre-existing defect with no elevation in
  it, demonstrated on the unmodified solver at four adverse pressures and in the filter shape, and it
  belongs to the phantom-trace family rather than to this one. Fixing it means changing either the
  `total*1e-12` trace seed for components nothing can deliver, or the step limiter's treatment of an
  unknown whose only root is zero — both of which have their own qualification surface.
* **The velocity clamp's donor is still live.** `capMassFlows` and `capPressureDrops` are indexed by
  the sign of the *flow*, so the throttled-inlet law's cap jumps at zero flow in the same way the head
  used to. It showed up once during this work, on the intermediate version of the rule, and is gone
  now because with the head frozen the driving pressure at a sign change is small and neither branch
  throttles. It is a genuine second degree-zero switch on the same row and it is worth a look, but it
  reaches into `VelocityClampTest` and the benchmark and could not be qualified inside this change.
* **Three of the four regression islands SKIP.** No readable `core.dat` exists in this worktree, as
  for every recent change on this track. `chain-100` is the whole of the replayed set.
* **No valve fixture with elevation.** The valve shape was not among the four the probe was asked
  for, and the filter and the pump between them already cover a device junction with a column above
  and below it.
* **The elevated lines are not in the GameTest set.** The GameTests place their devices at one y; the
  in-game pass in section 9 is the elevated verification, done by hand in the dev client.
* **The queued-event consequence of a held island is recorded, not fixed.** Section 9: a held island
  applies neither the configuration nor the topology events queued behind it, keeps retrying its own
  stuck point, survives a save and a client restart, and cannot be dissolved by removing its blocks.
  That is worth its own look and it is not the static head.
* **One elevation only.** Every elevated fixture uses four blocks. The jump scales linearly in `dz`,
  the boundary is stated in section 6, and nothing in the rule has a length scale in it, so a sweep
  over `dz` would have measured the same term at a different size.

---

## 11. Commits

| sha | |
| --- | --- |
| `8c072f7` | State a connection's static head on a column of the pass, not of the iterate |
| `ec9ea74` | Build the block lines with an elevation difference and hold them to their column |

Base `c7da543`, branch `claude/elevated-line-probe`. Every commit ends with
`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. `documentation/` is gitignored in this
tree, so this report and its screenshots are in the worktree but not in either commit.

### Screenshots

`documentation/screenshots/elevated-line-probe/`, all flattened with `Flatten`:

| file | |
| --- | --- |
| `01-create-world.png` … `04-in-world.png` | world setup, creative + superflat |
| `05-line-placed.png`, `06-line-view.png` | the first vertical line, built at the default 101.325 kPa |
| `08-generator-screen.png` | **the dead-head defect in the world**: `HELD … residual 5.066656016570093E-4` |
| `10-applied.png` | `WAITING: configuration event / HELD` — a held island will not take a setting |
| `12-fresh-generator.png`, `13-generator-400.png` | a replaced generator still inherits the stuck island |
| `16-new-generator.png`, `17-new-generator-400.png` | a fresh island at a new position, 400.00 kPa applied |
| `18-line-running.png` | the closed 400 kPa line |
| `23-tank-screen.png`, `24-tank-settled.png` | the tank `FULL` at 360.92 kPa / 715.34 kg |
| `27-tank-after-reload.png` | the same after a save, client restart and world reload |
| `30-w2-generator.png` … `34-w2-tank.png` | the clean-world rebuild and its identical endpoint |
| `33-w2-line-wide.png` | the four-block column, generator to tank |
| `38-w2-reloaded.png`, `39-w2-tank-clean.png` | the clean session, whose log has zero `status=HELD` |
| `40-lag-a.png`, `41-lag-b.png`, `42-lag-c.png` | lag 1.50 / 3.35 / 2.15 s, bounded |
