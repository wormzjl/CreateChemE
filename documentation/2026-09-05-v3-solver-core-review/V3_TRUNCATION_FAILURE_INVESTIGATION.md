# V3 truncation and failure investigation

Date: 2026-09-05. Scope: V3 scientific core at `6c7d446645792226194df56e58873dceae58bc69`. Existing V4 work is excluded. The requested sequence is (1) phase-specific feasibility, (2) broader truncation under a 0.1% mass-balance allowance, (3) a matched successful/failed case autopsy and a supported repair plan. Production scientific source is unchanged by this investigation.

The 0.1% allowance is interpreted as `0.001 * authored feed mass rate`, with molecular-weight weighting. This is distinct from a 0.1% mole-fraction cutoff or the current molar sink-loss audit. Numerical residual thresholds are not automatically relaxed by this allowance.

## 1. Phase-specific truncation: feasible as an explicit approximation

V3 already implements phase-specific support in its standalone TP flash (`V3FlashPhaseSupport`, `V3TruncatedFlash`), while the column uses one boolean for both phases at each component/stage point. The flash allocates all of each component to the retained phase and checks whether the omitted equilibrium-predicted fraction is still below the cutoff.

| Component/stage support | Flow unknowns | Component balance | Equilibrium equality |
| --- | ---: | ---: | ---: |
| Both phases | 2 | 1 | 1 |
| Liquid only | 1 | 1 | 0 |
| Vapor only | 1 | 1 | 0 |
| Neither phase | 0 | 0 | 0 |

Energy and temperature counting is unchanged. Removing one flow coordinate and its equilibrium row keeps the system square; keeping the component balance can preserve component mass exactly. Removing both phases and the balance is a separate approximation that can lose transport, as in the current column sink-edge model.

For finite positive fugacity coefficients, `y_i phiV_i = x_i phiL_i`. An exactly zero fraction in one phase cannot satisfy that equality with a positive fraction in the other. Phase-specific zeroing must therefore be declared as approximate partitioning, with a fresh omitted-phase check (`predicted y_i = K_i x_i`, or `predicted x_i = y_i / K_i`) and bounded reactivation when the prediction ceases to be negligible. Wet stages require the existing water-dilution term in this prediction. A total mass-balance pass alone cannot certify partition accuracy: a model can conserve mass while putting it in the wrong product.

The current flash first solves an unrestricted reference. That workflow cannot rescue a column whose unrestricted solve fails. Reuse its support semantics and omitted-phase prediction, while qualifying the column approximation against successful full-column references and checking local balances, energy, and the effects on product compositions. Keep support fixed during an individual Newton solve; update it only at a controlled restart with separate removal/reactivation thresholds and one work budget.

Required column integration points:

- `V3TruncationSupport.java:110`: shared retention rule; phase-specific support needs helpers for liquid/vapor presence and material/VLE rows.
- `V3DegreeOfFreedomLedger.java:191` and `V3StageBlockLayout.java:53`: enumerate one balance whenever either phase remains, and VLE only when both remain.
- `V3MeshResidualEvaluator.java:110,177`: skip omitted VLE rather than evaluate `log(0)`; allow zero entries only where support explicitly permits them.
- `V3TruncationSupport.java:327`: directional reachability must follow retained vapor upward and retained liquid downward, including reflux and draws.
- `V3AcceptanceAuditor.java:164`: phase-specific zero invariants, full component transport audits, and omitted-phase predictions.
- `thermo/V3FlashPhaseSupport.java:10`, `thermo/V3TruncatedFlash.java:174,253`: existing phase-specific allocation and validation precedent.

An exact alternative reconstructs the minority flow from `v_i = (V/L) K_i l_i` instead of setting it to zero. PR fugacity coefficients and phase totals depend on those reconstructed flows, so this requires consistent implicit local equations/derivatives. It is a larger change than extending frozen phase support, and it does not automatically remove the weak common-total direction when both phase amounts are tiny.

Primary thermodynamic reference: [IDAES fugacity equality](https://idaes-pse.readthedocs.io/en/2.7.0/explanations/components/property_package/general/pe/pe_forms.html). These feasibility conclusions are algebra/code review, not a measured column-convergence improvement.

## 2. Broader truncation with a 0.1% mass allowance

The current `TRUNCATION_MASS_DEFECT` audit (`V3AcceptanceAuditor.java:189`) is a **molar** sink sum divided by hydrocarbon molar feed, with allowance `8 * cutoff`. Setting cutoff to 0.001 would allow 0.008 mol/mol (0.8%), not the requested 0.1% by mass. Even cutoff `0.001 / 8` only equates the molar allowance. Molecular weights are already available from `V3PengRobinsonThermo.componentMolecularWeightKgPerMol` (`:59`).

A separate requested error budget should enforce:

```text
sum_i MW_i * abs(F_i - sum_external_products P_pi)
------------------------------------------------- <= 0.001
                 sum_i MW_i * F_i
```

Absolute component errors prevent compensating component gains and losses. Independently reconstruct MW-weighted sink losses and require consistency with the external discrepancy within numerical closure tolerance. For wet cases, use hydrocarbon feed mass as the truncation denominator and report total feed mass separately; this is conservative and prevents steam addition from diluting the loss percentage. Keep the local mole-fraction selection threshold separate from this cap. The 0.1% allowance does not relax the retained material equations, energy equations, significant-component equilibrium, or draw feasibility checks.

| Existing exclusion | Feasible extension | Required condition |
| --- | --- | --- |
| Every species protected between feed and draws | Protect only component-specific paths to points that must remain, allowing trace omission on other paths | Restore a directed supply path to any significant retained downstream point; do not merely delete all disconnected points |
| Every feed-stage point protected | Keep each authored feed material row, while allowing one phase flow to be omitted | At least one outgoing phase carries its material |
| Support frozen from one deciding seed | Rebuild between bounded Newton attempts, with removal/reactivation hysteresis | Predict omitted flows from the full component axis; zero-filled state slots are not evidence that omitted material remains negligible |
| Intermediate draw/steam rungs use cutoff zero | Derive support for each rung, and check that rung's transport and error budget | Reflux, draw rates, pressures and geometry remain consistent with that support |
| Failed reduced chain restarts cold untruncated | Try a bounded support repair from a usable within-request state, then retain exact fallback | Reinsert the largest omission errors first; do not count a failed rung as a converged continuation state |

Implementation sites: `V3TruncationSupport.java:96,268,327`, `V3ColumnCalculator.java:587,752,770,904,947`, and `V3TruncationFallback.java:12`. The current policy record permits only the requested cutoff or zero; adaptive cutoff attempts need explicit applied-cutoff provenance.

For phase-specific support, incoming vapor at a liquid-only component/stage is **not** a material sink: the retained balance can condense it and send it downward. A sink exists only when material arrives at a stage with no component balance. Directional graph checks must nevertheless reflect which outgoing phase exists. The first implementation should retain every positive-feed material row rather than discard entire feed species.

A fresh read-only mask census used successful benchmark `A150-quarter-off`: 30 trays, feed tray 24, 150 kPa top pressure, 400 K condenser, R=2, QR=8 MW, and 12.4296% combined molar side draws at trays 8/15/22. The private production continuation driver returned a converged, audit-accepted full-support state. Hydrocarbon feed is 166.5177208 kg/s, so the requested 0.1% allowance is 0.1665177 kg/s.

All 77 current V3 scientific sources were snapshotted and compiled with Java `--release 21`; they match the frozen benchmark candidate after normalizing line endings (three files differ only in line endings). No production acceptance or residual was changed.

| Mole-fraction cutoff | Current rule removed points / 480 | Feed-only protection removed points / 480 | Current predicted sink mass / feed | Extended predicted sink mass / feed | Additional phase-only candidate coordinates |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1e-6 | 35 | 73 | 0.00006347% | 0.00014023% | 9 |
| 1e-4 | 42 | 86 | 0.00566762% | 0.01182924% | 49 |
| 1e-3 | 49 | 96 | 0.05538131% | 0.10759597% | 101 |

The extended masks on this state did not discard any originally significant point through reachability pruning and did not empty a structural phase. At `1e-4`, lifting blanket draw-path protection roughly doubles removed component/stage points while the frozen-state loss estimate is well below the user's cap. At `1e-3`, the extended estimate exceeds the cap, directly demonstrating why cutoff and mass allowance must be separate.

These are **censuses on one converged full-state reference**, not re-solves of the prospective masks, not a global error bound, and not proof of a convergence gain. The phase-only count describes variables that could be approximated without deleting their material row; its circulating phase-flow sum is not mass loss and must not be added to the sink-loss column. A fresh reduced solve and independent full-product audit remain required before enabling any extension.

Evidence: `build/v3-truncation-investigation-20260905/results/support-budget-A150-quarter-off.json`; standalone read-only probe: `build/v3-truncation-investigation-20260905/probe/V3SupportBudgetProbe.java`. The report contains all five cutoffs, exact input, final state, audit, molar/mass sink sums, and source-state limitations.

## 3. Matched successful/failed V3 case

### Pair, reproducibility, and exact stopping conditions

Fresh public calls reproduced frozen benchmark `F05-dry-on` (success) and `F06-dry-on` (failure). Both use TJL feed 725.1944444 mol/s at 638.15 K, 30 trays, feed tray 24, top pressure 250 kPa, 750 Pa per tray, condenser 323.15 K, R=2, QR=8 MW, no steam, and cutoff 1e-6. Only the combined side-draw loading changes, with identical proportions at the same three trays:

| Tray | Successful 5% case, mol/s | Failed 40% case, mol/s |
| --- | ---: | ---: |
| 8 | 13.85579524 | 110.84636192 |
| 15 | 18.24160140 | 145.93281116 |
| 22 | 4.16232559 | 33.29860469 |
| Total | 36.25972222 | 290.07777778 |

The percentages are combined molar side draws divided by molar feed, not a percentage withdrawn at one tray and not the fraction of that tray's internal liquid supply.

Detached observation hooks captured each existing attempt, its actual controls/support, every accepted/rejected Armijo trial, LU results, correction identities, certification, and final state. Original and instrumented calls have **identical public outcomes, diagnostics, audits, convergence evidence, and successful streams**. No observer added thermodynamic evaluations or changed numerical decisions. All trace records report zero dropped events. Timings here are diagnostic durations, not a new performance benchmark.

| Route | Actual combined draw | Cutoff applied on this attempt | Exact outcome | Maximum scaled residual | Important observation |
| --- | ---: | ---: | --- | ---: | --- |
| F05 final | 5% | 1e-6 | Converged, 11 iterations | 7.1054e-14 | 51/480 points removed, all checks pass |
| F06 first chain | 10% | 0 | Converged, 24 iterations | 1.2169e-9 | Bare-column anchor had used truncation |
| F06 first chain | 20% | 0 | Converged, 23 iterations | 1.7183e-12 | Last accepted rung before the first failure |
| F06 first chain | 30% | 0 | MAX_ITERATIONS, 40 | 0.0757594 | All 40 accepted directions are local Newton; 214 merit-rejected trial points |
| F06 first chain | 40% | 1e-6 | MAX_ITERATIONS, 32 | 0.2843202 | Seed is the failed 30% state; tray 22 liquid draw exceeds its supply |
| F06 untruncated fallback | 10% | 0 | MAX_ITERATIONS, 40 | 1.7144e-5 | Computed Jacobian becomes singular; 28 accepted regularized Gauss-Newton steps |
| F06 untruncated fallback | 40% | 0 | MAX_ITERATIONS, 32 | 0.0134151 | Seed is the failed 10% state; all 32 fresh full Newton linear solves fail SINGULAR, all 32 accepted steps are regularized Gauss-Newton |

Thus the first truncated chain and final untruncated fallback fail by **different routes**. The public fallback summary must not be treated as the complete original chain. The failed cases never reach the 1e-8 residual gate, so their rejection is not a final-correction certificate-only failure.

### Physical meaning of the two routes

At 30% in the first chain, the largest remaining equation is the PC07 component balance on tray 16: incoming minus outgoing is **-4.11518 mol/s**. The sampled state exports more of that component than it receives; it is not a steady state. At 40%, the same imbalance grows to **-15.4440 mol/s**. Tray 22 has gross liquid **32.76852 mol/s** but is asked to withdraw **33.29860 mol/s**, giving impossible onward liquid flow **-0.53008 mol/s**. The last local direction proposes a **-1162.59 logarithmic correction** for liquid PC10 on tray 23 and **+315.48 K** there; its accepted multiplier has shrunk to **1/32768**. Numerical progress has become negligible near a liquid-depletion boundary.

The final untruncated fallback is a different sampled state: it has positive liquid-draw margins, but tray 12 has **+972853.368 W** of incoming-minus-outgoing enthalpy imbalance and its largest log-fugacity mismatch is **0.001197527**, corresponding to about **0.1198%** fugacity-ratio mismatch. A positive energy residual means that this candidate would accumulate energy; it is not a requested additional heater duty. These residuals cannot identify a physically infeasible target by themselves.

There is an existing diagnostic trap: `V3AcceptanceAuditor.java:58-63` uses scaled material and energy limits of **1.0**, while the actual solver requires **1e-8** for every scaled row (`V3ColumnCalculator.java:31`). Consequently, an audit can report material/energy "pass" at a numerically unfinished state, and the 10% fallback can show `failed checks=[]`. This does not mean it meets the solver's closure requirement. No audit or solver threshold was changed in the experiments.

### Confirmed trace mechanism and controlled repair

The failed 10% fallback state was reconstructed with its exact full support and a fresh FINE Jacobian. Its liquid and vapor PC08 flows on tray 4 are **6.29528e-279** and **1.48848e-280 mol/s**. The corresponding computed Jacobian columns, indices **128 and 129**, are exactly opposite element by element. Their common direction is therefore an exact null direction of that floating-point matrix. This is numerical loss of sensitivity, not a missing physical operating specification.

An independent NumPy SVD after row/column equilibration gives:

| Recorded state | Dimension | Numerical rank at the stated standard threshold | Equilibrated 2-norm condition estimate |
| --- | ---: | ---: | ---: |
| F05 reduced success | 881 | 881 | 1.176e5 |
| F06 10% fallback failure | 976 | 975 | 2.252e15 |
| Warm continuation last accepted state | 976 | 976 | 2.395e5 |
| Warm continuation next rejected state | 976 | 976 | 1.131e6 |

The exact opposite-column check does not square tiny values and is separate from tolerance-dependent SVD rank. Not every tiny pair is singular: the successful states retain many traces. During the failed 10% attempt, a full Newton proposal requests a roughly **+24.97 million** log-flow correction for vapor PC08 on tray 1, causing coordinate-domain rejection; the eventual singular system is routed to regularized Gauss-Newton.

Two replays started from the **same failed 10% state**, with identical physical specifications and a common 128-iteration cap:

| Replay | Result | Iterations | Final residual | Absolute external component mass error / feed |
| --- | --- | ---: | ---: | ---: |
| Remain untruncated | MAX_ITERATIONS | 128 | 5.7984e-6 | Not accepted |
| Derive fresh current V3 mask at 1e-6 | Converged and audited | 8 | 7.3680e-9 | 1.9504e-8 (0.0000019504%) |

The refreshed mask removes 49/480 points. This is direct evidence that activating/refreshing truncation at an intermediate failed state can repair this specific numerical failure, without using the newly allowed 0.1% mass budget. The replay removes several points; it does not prove that removing PC08 alone would suffice. It is a bounded diagnostic from a recorded state, not a new public cold-start policy or a demonstrated repair of the full 40% target.

### Higher draw loading: measured boundary, incomplete physical diagnosis

A separate counterfactual started from the accepted untruncated 5% case, retained only audited accepted seeds, and increased total draws in bounded increments. Rejected increments were halved. Every rung used the current 40-iteration limit and unchanged residual/step gates.

| Accepted combined draw | Tray-23 liquid, mol/s | Tray-23 temperature, K |
| --- | ---: | ---: |
| 10% | 255.77894 | 558.24828 |
| 20% | 107.01351 | 593.73222 |
| 25% | 46.03597 | 610.70725 |
| 27.5% | 20.03936 | 621.00696 |
| 28.75% | 8.23350 | 626.22307 |
| 29.375% | 2.78195 | 631.28652 |
| 29.6875% | 0.28891 | 635.68523 |

The next attempted 29.84375% rung failed after 40 iterations, at residual 0.00325975. Its computed Jacobian remains full rank at the documented SVD threshold. This is not the same singular-PC08 mechanism as the cold fallback. The trend identifies incipient depletion of the entire liquid phase on tray 23, immediately below the deepest side draw, as a strong physical lead. V3 forces both phases on every interior tray and cannot pass through an exactly vapor-only tray.

To test that lead, an isolated experimental branch allowed only tray 23 to be vapor-only, retained all component balances and energy equations, moved its small initial liquid amount into vapor without deleting component material, and attempted 30% loading. Both full-Newton and local-block-enabled runs stopped at `LINE_SEARCH_EXHAUSTED` after 28 iterations, residual **2.28304e-4** (dominant tray-9 energy mismatch **16556.45 W**). The local setting accepted no local directions and followed the same full-Newton trajectory, so these are two settings with the same outcome, not independent numerical confirmations. Neither reached a phase-screened accepted state. Therefore **forcing tray 23 dry is not a demonstrated cure**, and no experiment proves that the 40% request is physically infeasible. A consistent phase transition with validated derivatives/stability and possibly other affected trays still needs investigation.

### Code positions and best-supported plan

| Finding | Current production code position | Meaning / next action |
| --- | --- | --- |
| Intermediate truncation disabled | `V3ColumnCalculator.java:752` | Permit fresh, audited support on intermediate rungs; the failed-state replay demonstrates an actual benefit |
| Support fixed from deciding seed | `V3ColumnCalculator.java:904`, `V3TruncationSupport.java:79` | Add a bounded support-repair/rebuild boundary after identified stagnation, not during a Jacobian or line search |
| Singular computed linear system | `V3SimultaneousColumnSolver.java:265-302`, `linalg/V3BandedPivotedSolver.java:54` | Record failed pivot/mode and distinguish it from physical infeasibility; do not silently count regularized progress as ordinary Newton |
| Failed rung seeds full loading | `V3ColumnCalculator.java:770-777` | Retain last accepted state and subdivide failed loading increments; do not jump from an unfinished rung |
| 40 / 32 attempt limits | `V3ColumnCalculator.java:40-41`, `V3SimultaneousColumnSolver.java:217` | Report exact budget and path; more iterations alone failed the untruncated controlled replay |
| Local steps can stagnate while satisfying Armijo | `V3SimultaneousColumnSolver.java:224-247,479` | Measure accepted reduction/step size and force existing full-Jacobian correction when local progress stalls; this is a testable policy change, not yet qualified |
| Negative onward liquid allowed during iteration | `V3MeshResidualEvaluator.java:90-108`, `V3ColumnProblem.java:196` | Make trial feasibility agree with the draw audit, preferably solving nonnegative onward flow with gross `L=B+draw` |
| Fixed interior phase pattern | `V3ColumnTopology.java:66-82`, `V3CondenserComponentPhases.java:35` | Investigate a conservative phase-aware V3 boundary update near tray 23; hard-forcing one dry tray did not suffice |
| Molar rather than mass truncation cap | `V3AcceptanceAuditor.java:189-208` | Add the separate MW-weighted 0.001 cap and explicit applied-cutoff provenance |

Recommended order: first enable/repair current truncation where it is currently disabled and fix failed-rung seed handling; qualify that small change on the matched pair and shared successes. Next prototype phase-specific support plus the explicit 0.1% mass cap, keeping component balances and omitted-phase reactivation checks. Separately resolve the high-draw liquid-depletion boundary with an independently checked phase-aware stage model and consistent derivative/line-search behavior. Do not promise that either trace removal or a single dry-tray switch will solve the 40% case.

### Reproduction and evidence

All artifacts are under `build/v3-truncation-investigation-20260905/`:

- `source-provenance.json`, `line-ending-verification.json`, `snapshot/v3/`: current-core identity.
- `probe/instrument_v3.py`, `instrumented/*.diff`, `instrumented/instrumentation-provenance.json`: observation-only source changes.
- `results/plain-F05-dry-on.json`, `plain-F06-dry-on.json`, `traced-F05-dry-on.json`, `traced-F06-dry-on.json`: public calls and exact parity.
- `results/trace-summary.json` and the two `traced-*-trace/` directories: all original-chain and fallback attempts, directions, trials, states, and audits.
- `results/matrix-analysis.json`, `matrix-*/`: independent SVD, raw big-endian Jacobians, coordinates, residuals, row/unknown identities, and trace-pair checks.
- `results/replay-quarter-refreshed.json`, `replay-quarter-untruncated.json`: the controlled support-refresh comparison.
- `results/warm-load-off.json` and `warm-load-off-trace/`: accepted-seed-only continuation and physical profiles.
- `results/dry-stage-23-off.json`, `dry-stage-23-local-off.json`, `dry-stage/`: unsuccessful fixed-dry-stage experiments, preserved with their limitations.

Java probes were compiled with JDK 25 `--release 21` against the detached current V3 classes and frozen benchmark resource/Gson classpath. Numerical JVMs used 128 MB initial heap, 1536 MB maximum heap, one active processor, and SerialGC. Each run had a cooperative deadline; runs were serial. This investigation did not run the full Gradle suite, a new 64-case benchmark, or Minecraft UI tests because production code was not changed.
