# P1 item 3: tangent-plane stability test on the PR78 kernel

Date: 2026-09-24. Batch `2026-09-24-coolprop-low-temperature`, unified multiphase thermo plan section 5, P1 item 3
(gate G1: "the stability-test cost ... recorded with numbers"). Branch `claude/coolprop-multiphase-thermo-37f6b0`,
commits `4500e58` (code and tests) and `585d137` (changelog). Status: implemented as a standalone service with tests; no solver calls it (integration is P3).

## 1. What exists

- `src/main/java/com/wormzjl/createcheme/science/thermo/TangentPlaneStability.java`: given a `PengRobinsonKernel`,
  a temperature, a pressure and a feed (mole numbers or fractions over the kernel's basis), returns a `Result`: the
  verdict (`STABLE`, `UNSTABLE`, `UNRESOLVED`), the most negative modified tangent-plane distance `tm` over the trial
  endpoints, that endpoint's normalised composition and root, the feed root used, and the counts of trials, accepted
  iterations, kernel calls and derivative calls. A caller-owned `Workspace` makes repeated calls allocation-light.
- `src/test/java/com/wormzjl/createcheme/science/thermo/TangentPlaneStabilityTest.java`: seven tests (section 4).
- `src/test/java/com/wormzjl/createcheme/science/thermo/TangentPlaneStabilityCostTest.java`: the cost probe, run only
  with `CREATECHEME_STABILITY_COST=1` in the environment (section 5). Measurement code: it leaves the tracked tree
  under the tooling-cleanup rule before the batch merges.
- `tools/tangent-plane-stability-scans/` (worktree, git-ignored; to be copied to the main checkout's `tools/`): the
  larger scans of section 4.2, run with javac outside Gradle.

No existing class changed. The kernel is used through its public API only (`prepareTemperature`, `evaluate`,
`evaluateDerivatives`, `dLogPhiDnRowView`, `wilsonK`, `physicalRootCount`).

## 2. Algorithm as implemented (Michelsen 1982a)

1. **Feed.** The feed is normalised; negative, non-finite or all-zero amounts and a wrong length are refused
   (`IllegalArgumentException`). Components with zero amount are absent and stay absent from every trial. The feed is
   evaluated on the kernel's `VAPOR` (largest) root; when the cubic has three physical roots the `LIQUID` (smallest)
   root is evaluated too and the root with the lower `sum_i z_i ln phi_i` is used (a tie keeps `VAPOR`). This fixes
   `d_i = ln z_i + ln phi_i(z)`.
2. **One present component.** The only other phase is the other root at the same composition. With one physical root
   the result is `STABLE`, `tm = 0`. With three, the feed takes the lower-fugacity root and the other root's
   stationary `tm = 1 - exp(-(ln phi_other - ln phi_feed)) >= 0` is reported; the verdict is always `STABLE`. At the
   equal-fugacity pressure that `tm` goes to zero: pure coexistence is visible as `tm ~ 0`, and no phase fraction is
   produced.
3. **Trial phases, fixed order.** Wilson vapour-like `W_i = z_i K_i`, Wilson liquid-like `W_i = z_i / K_i` (the
   kernel's `wilsonK`), then, when at most 8 components are present, each pure present component `W = e_k` in basis
   order. A binary runs 4 trials, the 4-component case 6, the 20-component network package 2.
4. **Trial evaluation.** Every trial point is evaluated on its own lower-Gibbs root (the preferred root first, the
   other only when the cubic has three physical roots); this is the minimum of `tm` over both roots at that
   composition. `g_i = ln W_i + ln phi_i(W) - d_i`, `tm = 1 - sum W + sum W_i g_i`.
5. **Successive substitution** `ln W_i <- d_i - ln phi_i(W)`, with a dominant-eigenvalue extrapolation on every 5th
   consecutive substitution step: `lambda = (dk . dk) / (dk-1 . dk)`; when `0 < lambda < 1` the step is taken
   `1/(1 - lambda)` times, the added part capped at 20 in any `ln W_i`, and kept only if `tm` decreases (otherwise the
   plain step is taken). `ln W` is bounded to [-700, 700].
6. **Newton.** From the first point with `max |g_i| < 1e-2`, or after 30 substitution steps, points are evaluated
   with `evaluateDerivatives` and Newton runs on `alpha_i = 2 sqrt(W_i)` with the exact Hessian
   `H_ij = delta_ij (1 + g_i/2) + sqrt(w_i w_j) (dlnphi_i/dn_j + dlnphi_j/dn_i)/2` (the kernel's mole-number
   derivatives at the normalised composition, symmetrised), solved by Cholesky. A step may shrink any `alpha_i` by at
   most 10x, must not increase `tm` beyond a roundoff allowance of `1e-12 (1 + |tm|)`, and is halved up to four
   times. If the Hessian is not positive definite, or no halving decreases `tm`, a substitution step is taken instead;
   these fallback steps keep the extrapolation cycle.
7. **End of a trial.** Converged when `max |g_i| < 1e-10`, or when an undamped Newton step moved no `ln W_i` by more
   than `1e-10`. Trivial when `sum_i (ln W_i - ln z_i)^2 < 1e-4`, `tm >= -eps`, and the feed is locally stable: the
   trivial solution's Hessian `I + sqrt(z_i z_j) dlnphi_i/dn_j` is positive definite (one derivative evaluation and a
   Cholesky per call, done the first time a trial comes near the feed). Unresolved after 100 accepted points. Any
   point with `tm < -eps` proves instability (`tm(W) < 0` at any `W` implies `D(w) < 0`); the trial still iterates to
   its stationary point so that the reported composition is one.
8. **Verdict.** `UNSTABLE` if any trial reached `tm < -eps` (default `eps = 1e-8`) or the feed's own Hessian is
   indefinite; otherwise `UNRESOLVED` if a trial ran out of iterations; otherwise `STABLE`. By default the trials stop
   at the first that proves instability (`Settings.exhaustive` runs all). A trial that ended trivial reports `tm = 0`
   with the feed composition and root.
9. **Determinism.** No randomness, fixed trial order and a fixed arithmetic order; a reused workspace carries only
   the kernel's prepared temperature (recomputed bit-identically). Tested bitwise, section 4.
10. **Root labels.** `LIQUID`/`VAPOR` are the kernel's smallest/largest physical root. With one physical root both
    name it and the result says `VAPOR`, dense or not: the label is not a phase classification.

### Controls (`TangentPlaneStability.Settings.DEFAULT`)

| Setting | Default | Meaning |
|---|---|---|
| `instabilityTolerance` (eps) | 1e-8 | `tm < -eps` proves instability |
| `convergenceTolerance` | 1e-10 | `max |g_i|`, or the move of an undamped Newton step |
| `newtonSwitch` | 1e-2 | `max |g_i|` below which Newton takes over |
| `maximumSuccessiveSubstitutions` | 30 | Newton takes over after this many substitution steps in any case |
| `accelerationCycle` | 5 | consecutive substitution steps per extrapolation |
| `maximumIterations` | 100 | accepted points per trial before `UNRESOLVED` |
| `trivialDistance` | 1e-4 | `sum (ln W_i - ln z_i)^2` of the trivial solution |
| `pureComponentTrialLimit` | 8 | pure-component trials only up to this many present components |
| `exhaustive` | false | run every trial instead of stopping at the first proof |

Fixed in the code: extrapolation cap 20 in `ln W`, `ln W` bound 700, Newton `alpha` shrink floor 0.1, four halvings,
decrease allowance `1e-12 (1 + |tm|)`.

## 3. The 20 + 1 components of the network package

The network basis (`FluidThermodynamics.componentCount()`) has 21 components: the 20 PR78 components of
`createcheme:tjl20_methane_nitrogen` (methane to n-pentane, `crude_pc01` to `crude_pc12`, nitrogen) and water, which
the network models as a separate free-water phase outside the equation of state. The stability test runs on the 20
EOS components; "21-component" states in this document are 20 EOS components, all present, plus no water.

## 4. Test matrix and outcomes

### 4.1 Unit tests (`TangentPlaneStabilityTest`, Gradle, green)

Gradle run 2026-09-24 18:14, `./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.*' --offline` with
`CREATECHEME_STABILITY_COST=1`: 46 tests, 0 failures, 0 skipped (`PengRobinson78Test` 7,
`PengRobinsonKernelRootPrecisionTest` 6, `TangentPlaneStabilityTest` 7, `TangentPlaneStabilityCostTest` 1,
`HelmholtzReferenceParityTest` 12, `HelmholtzReferenceTest` 13).

Constants are the bundled network package's PR78 records (nitrogen Tc 126.192 K, Pc 3.3958 MPa, omega 0.0372; methane
190.564 K, 4.5992 MPa, 0.01142); every methane and nitrogen interaction in the package is zero. Kernels are built with
an open domain, as `TranslatedPengRobinson` builds its own.

| Test | States | Reference | Outcome |
|---|---|---|---|
| (a) pure nitrogen, 77.355 K | 90, 95, 100 kPa and `P_eq (1 - 1e-9)`; `P_eq (1 + 1e-9)`, 105, 110, 120 kPa | kernel's own equal-fugacity pressure by bisection: `P_eq` = 102,546.9 Pa (+1.21 % against NIST's 101.325 kPa; the package evidence states +1.23 %) | all `STABLE`; feed root `VAPOR` below `P_eq`, `LIQUID` above; `tm` of the other root 0.117 at 90 kPa, 0.139 at 120 kPa, 9.5e-10 at `P_eq (1 +- 1e-9)` |
| (b) methane/nitrogen | 110 and 120 K x 0.5, 1, 1.5, 2, 2.5, 3 MPa x x_N2 0.1, 0.3, 0.5, 0.7, 0.9: 60 states | brute-force `min_w D(w)` over both roots: 3,999 interior mole fractions plus logarithmic ends to 1e-10, each discrete minimum refined by 80 golden-section steps; unstable when below -1e-8 | 60/60 verdicts agree (12 `UNSTABLE`, 48 `STABLE`); on every unstable state the reported `tm` equals `1 - exp(-D_min)` within 1e-9 (the trials found the global minimum) and the reported composition is a stationary point (equal `ln w_i + ln phi_i - d_i` within 1e-9) |
| (c) supercritical / dense | pure N2 130 K 6 MPa, 150 K 10 MPa; pure CH4 250 K 10 MPa; equimolar CH4/N2 at the same three states | brute force for the binaries | all `STABLE`; the binaries run all 4 trials; brute-force minimum >= -1e-8 |
| (d) network package | Tia Juana light assay + 5 mol% N2 (20 components, all present) at 350 K 0.5 MPa, 600 K 2 MPa, 900 K 0.1 MPa | phase count of `FluidThermodynamics.flashTP` with no water (Wilson-started Rachford-Rice substitution; vapour at the state pressure, liquid through the network's 2 MPa reference and compressibility response) | `UNSTABLE` / two phases, `UNSTABLE` / two phases, `STABLE` / one phase |
| (e) determinism | six cases (binary two-phase, compressed liquid, near-critical 145 K 4.46 MPa; network 350 K and 900 K; pure N2) | fresh workspace against one reused across all cases in reverse and forward order; mole numbers against fractions | bitwise identical: verdict, `tm`, composition bits, roots, all counts |
| (f) refusal | negative amount, all zero, NaN, infinity, wrong length, null, negative pressure | | `IllegalArgumentException` (null: `NullPointerException`) |
| absent components | CH4/C2H6/N2 kernel with no ethane against the CH4/N2 kernel; one present component | | same verdict and `tm` (1e-12), ethane stays 0 in the trial; a single present component is the pure path |

### 4.2 Scans (`tools/tangent-plane-stability-scans/run.sh`, outside Gradle)

| Scan | States | Reference | Outcome |
|---|---|---|---|
| binary table | the 60 states of (b) | brute force, grid 4,000 | 60/60 |
| binary field | 25,000: CH4/N2, 95 to 190 K by 5 K, 0.1 to 5 MPa by 0.1 MPa, x_N2 0.02 to 0.98 by 0.04 | brute force, grid 1,500 | 0 disagreements, 0 `UNRESOLVED`; mean 18.8 kernel calls, at most 91 accepted iterations |
| near-critical | 105,600: 130 to 185 K by 5 K, 2.5 to 6 MPa by 0.02 MPa, x_N2 0.01 to 0.99 by 0.02 | brute force, grid 1,500 | 0 disagreements, 0 `UNRESOLVED`; mean 19.6 kernel calls, at most 168 accepted iterations |
| network package | 1,240: assay + N2 and a light gas (10 % each C1 to nC5, 30 % N2, 1e-4 each cut), 300 to 900 K by 20 K, 0.1 to 2 MPa by 0.1 MPa | `flashTP` phase count | 1,240/1,240 agree, 0 `UNRESOLVED`; mean 9.9, at most 29 kernel calls |

The near-critical scan found the one defect fixed during the work: at 145 K 4.46 MPa x_N2 0.75 and 150 K 4.66 MPa
x_N2 0.67 (the mixture critical region) one trial ended `UNRESOLVED`. The trial sat where its own Hessian is
indefinite, so every Newton attempt was refused and the fallback substitution steps ran without extrapolation. The
fallback now keeps the extrapolation cycle; both states end `STABLE` (brute-force minimum ~0), and the first is a case
of test (e).

## 5. Cost

Measured by `TangentPlaneStabilityCostTest` in the Gradle run of 2026-09-24 18:14
(`CREATECHEME_STABILITY_COST=1 ./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.*' --offline`), in the
single test JVM after the package's other tests: JDK 21.0.11, 16 processors. Before the run no dev client or game ran
(a `column-gui` dev client had exited at 18:14:14; only the idle Steam client was up), 25.4 GB of 47.6 GB free, CPU
load 15 %. JIT warm-up: two passes of 3,000 calls on every state; then 200 timed calls per state with one reused
workspace. Kernel costs are 20,000 calls of `evaluate` and `evaluateDerivatives` at the feed on its root. Allocation is
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` over 200 calls.

| Components, state | Verdict | Trials | Iterations | Kernel calls (derivative) | Mean us | p95 us | us per kernel call in the call | `evaluate` us | `evaluateDerivatives` us | Bytes/call, reused workspace | Bytes/call, new workspace |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2: 110 K 0.5 MPa x_N2 0.5 (two-phase) | UNSTABLE | 1 | 3 | 10 (2) | 1.33 | 1.40 | 0.133 | 0.091 | 0.155 | 408 | 3,568 |
| 2: 120 K 3 MPa x_N2 0.5 (compressed liquid) | STABLE | 4 | 16 | 22 (3) | 2.36 | 2.40 | 0.107 | 0.071 | 0.125 | 792 | 3,952 |
| 2: 250 K 10 MPa x_N2 0.5 (supercritical) | STABLE | 4 | 8 | 14 (1) | 1.45 | 1.50 | 0.103 | 0.069 | 0.118 | 536 | 3,696 |
| 4: 150 K 1 MPa (two-phase) | UNSTABLE | 1 | 3 | 6 (2) | 1.11 | 1.20 | 0.185 | 0.106 | 0.209 | 296 | 4,800 |
| 4: 120 K 2 MPa (compressed liquid) | STABLE | 6 | 46 | 58 (4) | 7.80 | 10.50 | 0.135 | 0.082 | 0.189 | 1,960 | 6,464 |
| 4: 300 K 2 MPa (vapour) | STABLE | 6 | 16 | 27 (2) | 3.32 | 3.40 | 0.123 | 0.083 | 0.184 | 968 | 5,472 |
| 20: crude + N2 350 K 0.5 MPa (two-phase) | UNSTABLE | 1 | 4 | 6 (2) | 6.53 | 7.60 | 1.088 | 0.141 | 1.140 | 424 | 24,896 |
| 20: crude + N2 600 K 2 MPa (two-phase) | UNSTABLE | 1 | 7 | 9 (3) | 9.76 | 9.80 | 1.085 | 0.137 | 1.121 | 520 | 24,992 |
| 20: crude + N2 900 K 0.1 MPa (vapour) | STABLE | 2 | 6 | 15 (4) | 13.93 | 14.00 | 0.929 | 0.136 | 1.117 | 712 | 25,184 |

The 4-component feed is methane/ethane/propane/nitrogen 0.4/0.15/0.15/0.3; the 20-component feed is the Tia Juana
light assay plus 5 mol% nitrogen (section 3).

Reading of the table:

- One stability call, which is the cost per node state, is 1.1 to 7.8 us for 2 and 4 components and 6.5 to 13.9 us
  for the 20-component network package. The kernel calls per stability call are 6 to 58: an unstable feed stops at the
  first proving trial (6 to 10 calls); a stable feed runs every trial to the trivial solution (14 to 58 calls; the
  pure-component trials of the compressed 4-component liquid are the costliest case).
- At 20 components a derivative call costs about 8 value calls (1.12 against 0.14 us), and the Newton phase plus the
  feed's local-stability check (2 to 4 derivative calls) dominates; the rest of a call is the per-component `exp`/`log`
  of the trial updates and the 20 x 20 Cholesky.
- The scans' mean kernel calls per stability call were 18.8 (binary field), 19.6 (near-critical) and 9.9
  (20-component field), at most 29 at 20 components.
- With a reused workspace a call allocates its `Result` plus about 30 bytes per kernel call (the kernel's
  `RootSelection`); a new workspace per call costs 3.6 to 6.5 kB for 2 to 4 components and 25 kB for 20 (three n x n
  derivative blocks).
- Variation: an earlier javac-only run of the same probe on the same machine gave the same kernel-call counts and
  times within about 20 % (20-component: 5.1, 8.2, 12.6 us).

## 6. Known limits

- The test is on the untranslated PR78 kernel. The network's constant per-component volume translation adds
  `P c_i / RT` to `ln phi_i` in every phase and cancels from `tm`; the network's liquid path (evaluated at 2 MPa and
  carried to the state pressure by one global compressibility) is not the kernel's liquid at the state pressure, so
  near a bubble or dew point the network flash and this test can disagree. No disagreement occurred in the 1,243
  states compared.
- Water is not an EOS component (section 3); free-water appearance is outside this test.
- Multiple liquids: a liquid-liquid split is detected only if a trial reaches it; only the most negative endpoint is
  reported, not a phase set, and there is no three-phase handling. With the default early stop the reported `tm` is
  the first proof, not necessarily the global minimum (it was the global minimum on all 12 unstable binary states
  checked).
- The trial set is heuristic. Above 8 present components only the two Wilson trials run (the 20-component package), so
  a minimum reachable from neither is missed; nothing in the scans suggested one.
- No critical-point special casing. An incipient phase within `sum (ln W - ln z)^2 < 1e-4` of the feed, which only
  occurs next to a critical point, is classified trivial; near a spinodal the trivial solution's basin can be smaller than
  that radius; Newton is refused where the trial's Hessian is indefinite; the 100-iteration budget can still end
  `UNRESOLVED` (none in 130,600 binary states, 105,600 of them around the critical locus). The kernel's derivative
  refuses roots at coalescence; the test then evaluates values only and continues by substitution.
- Pure-component coexistence is reported only through `tm ~ 0` of the other root; there is no coexistence flag.
- The `LIQUID`/`VAPOR` label is not a phase classification (section 2, item 10); supercritical classification is P3.
- Domain: the service enforces only the kernel's domain. The test kernels are open-domain; states at 3 to 10 MPa and
  below the methane record's 293.15 K minimum are EOS evaluations outside the network package's `fluid_domain`.
- Allocation per call with a reused workspace is the `Result` (with its composition copy) plus about 30 bytes per
  kernel call: the kernel's `RootSelection` record, which escape analysis does not remove on this path.
