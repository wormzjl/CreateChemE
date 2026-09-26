# DWSIM reboiler-duty specification investigation

Investigated locally on 9 September 2026. Scope: the installed **DWSIM 10.2.3.0** native rigorous-column solvers and the supplied petroleum example. Installed assemblies were not modified.

**Conclusion:** the direct duty-specified path has solver implementation problems. The Naphtali–Sandholm residual demonstrably drops the specified reboiler duty. Wang–Henke exhibits unstable coupled heat/material updates in this case, with an additional poor initial condenser-heat estimate. A native-engine outer adjustment successfully meets the requested distillate and duty targets after correcting the sample's small feed-normalization defect.

## Controlled case

The copied `Petroleum Distillation.dwxml` contains 30 DWSIM petroleum fractions, a total condenser, 10 trays, a reboiler, and two liquid side draws (40 and 70 mol/s). Feed temperature is 350 K and pressure is 101325 Pa throughout. No custom V3 property values are used.

The exact feed flow is **500.001016078605 mol/s**; previous summaries rounded this to 500. The original feed mole fractions sum to **0.9999980000000003**.

The requested replacement fixes:

- Distillate = **290.01 mol/s**, retaining the original condenser specification.
- Reboiler heat input = **27,282,470.7032937 W**, the duty calculated by the original successful flow-specified run.
- Bottoms flow becomes an output, rather than the original 100 mol/s specification.

The original sample's two product-flow specifications are redundant through the overall material balance, to within its loose tolerances. Thus its successful run is useful as an initial state, but should not be the specification template for a new dataset.

## Finding 1: the installed simultaneous solver loses the duty equation

This is a **confirmed implementation defect**, not merely a nonconvergence symptom.

Inspected the installed `NaphtaliSandholmMethod.FunctionValue` IL, then evaluated that actual method at the same finite state while changing its cached requested duty. Its `Heat_Duty` branch assigns the stage heat but leaves the specification-residual scalar at zero. Later it unconditionally replaces the reboiler energy-balance row with that zero scalar divided by the duty.

Measured diagnostic:

| Check | Result |
|---|---:|
| Residual-vector length | 732 |
| Reboiler energy-row index (zero-based) | 671 |
| Maximum change in any residual after +50% requested duty | **0, exactly** |
| Reboiler energy residual before/after duty change | **0 / 0** |
| Reboiler energy residual after +1 K bottom temperature | **0** |
| Maximum change elsewhere after +1 K bottom temperature | 2.6166350587 |

The temperature control proves the residual evaluation is responsive; it is specifically the duty constraint that is absent. This leaves the numerical system without the intended reboiler-energy equation. Changing duty signs, increasing iteration counts or supplying a better initial guess cannot restore a missing equation.

Evidence:

- `tools/dwsim/DutyInvestigation.cs`, including `ProbeDutyEquation`.
- `build/dwsim-research/duty-ns-equation-probe.json`.
- `tools/dwsim/DumpInstalledIl.cs` and `build/dwsim-research/Naphtali-installed.il`.
- Installed IL offsets 6513–6549 assign the heat for the heat-spec branch; offsets 8636–8649 replace the terminal energy row with the scalar specification residual.
- SHA-256 of installed `DWSIM.UnitOperations.dll`: `d3ef0ef45cfd7df9b00d1063a5d6a5791d53c21d473e86aee1ae8590619393c9`.

The cached public [NewtonRaphson.vb source](https://github.com/DanWBR/dwsim/blob/windows/DWSIM.UnitOperations/UnitOperations/RigorousColumnSolvers/NewtonRaphson.vb) shows the same structure. The conclusion is supported by the installed binary and numerical probe, rather than assuming that public source matches every release. A real fix needs to retain the energy residual for heat-duty specifications, verify the sign convention consistently, and test the resulting Jacobian and physical closure. No installed-engine patch was attempted.

## Finding 2: Wang–Henke's duty iteration becomes unstable

Automatic and complete warm starts both failed. A complete warm start includes temperatures, phase flows, compositions, distillate, bottoms and vapor product estimates from a fresh successful native calculation.

`GetSolverInputData(false)` nevertheless initializes terminal stage heats to zero. Using the installed property calls to reproduce the documented initial heat balance gives a **-1125.407 mol/s** bottoms estimate for the warm case, despite the nearby solution having approximately 100 mol/s bottoms.

A direct native-solver counterfactual restores the initial condenser heat to **29,756.493819 kW**. This changes the initial bottoms estimate to **100.03097 mol/s**, but **does not cure the solve**. It is a contributing initialization problem, not a complete explanation.

DWSIM's own Inspector trace then shows the instability:

| Internal iteration | Updated distillate, mol/s | Condenser heat, kW |
|---|---:|---:|
| 0 | 289.831718 | 29695.191026 |
| 1 | 292.588475 | 30020.668817 |
| 2 | 279.028417 | 28810.208446 |
| 61 | 2.85e58 | 8.63e59 |
| 66 | -3.56e213 | -Infinity |

The reboiler heat remains fixed at the requested value. A fallback attempt starts from corrupted intermediate values and reaches NaN. The final generic error therefore conceals a diverging heat/material iteration. The trace does not establish a universally applicable one-line repair for Wang–Henke.

Evidence: `build/dwsim-research/duty-trace.json` and `duty-trace.duty-warm-condenser-heat.inspector.txt`. The Inspector trace was collected with native parallel processing disabled because its shared item collection failed under the parallel path. The duty failure also reproduces without Inspector and with the normal execution settings. Inspector timings are not performance measurements.

## Finding 3: the example has a small feed-normalization defect

The saved feed composition sums to 0.999998. At the sample's original loose tolerances, the flow-specified case succeeds with roughly 2 ppm material error. Tightening the native solver tolerances to 1e-8 causes the native component-balance check to reject it, reporting approximately 2.0027e-6 relative error against a 1e-7 limit.

Normalizing the feed's mole-fraction vector while preserving the exact total molar flow removes this defect. This was done only in separately recorded counterfactual/workaround cases. The original copied sample remains available unchanged. A normalized direct-duty case still fails, so normalization is not the cure for the duty-path defects.

## Working equivalent formulation

The requested distillate and duty can be achieved using the installed native engine:

1. Normalize the sample feed fractions, preserving 500.001016078605 mol/s total feed.
2. Derive bottoms from the requested distillate and fixed side draws:
   `B = F - D - S1 - S2 = 99.991016078605 mol/s`.
3. Use native reflux-ratio + bottoms-flow specifications for each inner column solve.
4. Adjust reflux externally, with a bracketed scalar root solve, until calculated reboiler duty matches the requested duty.

This is mathematically an outer solution of the requested D/Q problem. The bottoms value is derived from its material balance; it is not an additional independent user specification. The saved standalone inner flowsheet still uses reflux and bottoms specifications—run the outer script to re-enforce the duty after changing inputs.

| Quantity | Target | Achieved |
|---|---:|---:|
| Distillate | 290.01 mol/s | 290.0099999999 mol/s |
| Reboiler heat added | 27,282,470.703294 W | 27,282,471.425042 W |
| Duty error | Within 1 W | **0.721748 W** |
| Bottoms | Derived by balance | 99.9910160786 mol/s |
| Reflux ratio | Solved externally | 2.49871925026 |
| Top/bottom temperature | Calculated | 344.701687 / 364.561651 K |

Three independent native column solves sufficed (346, 354 and 347 ms solve-call times). Total wall time was 7.716 seconds including three fresh worker initializations, loading and exports. This is a functional experiment, not a comparative throughput benchmark.

Independent checks on the final export:

- Maximum phase-composition sum error: 4.44e-16.
- Maximum local component balance error: 9.28e-8 mol/s.
- Maximum overall component balance error: 1.27e-7 mol/s.
- Overall mass balance error: 6.08e-9 kg/s.
- Overall energy balance error: 4.07e-10 kW.
- Native internal/external solver tolerances: 1e-8 / 1e-8.

This establishes an achievable normalized native-pseudocomponent operating point and a usable workaround for data generation. It does not qualify transfer to V3, the mod's full crude assay, or a neural initializer.

## Community reports

The user's requested community search found relevant primary discussions:

- **January 2013:** a user reported extreme duties and failure when specifying column heat duties. DWSIM's author explicitly acknowledged a duty-specification energy-balance problem in that version. [Discussion and author response](https://sourceforge.net/p/dwsim/discussion/844529/thread/1e4d3efb/).
- **August 2018:** a user found reflux plus product flow workable but other specifications/solvers troublesome. The author recommended trying a ChemSep CAPE-OPEN column, and the user reported success. [Discussion](https://sourceforge.net/p/dwsim/discussion/844529/thread/f317863b/).
- **September 2013:** the community noted that specification support depends on the chosen column solver. [Discussion](https://sourceforge.net/p/dwsim/discussion/844529/thread/b752474e/).

These are historical reports, not proof of the state of a current release. The installed-binary and numerical evidence above is the basis for the present diagnosis. No community post or issue was submitted. A subsequent [ChemSep trial](CHEMSEP_NATIVE_PSEUDOCOMPONENT_TRIAL.md) successfully solved the direct duty specification with these native pseudocomponents, hosted by DWSIM 10.2.5.0; this investigation's native-solver diagnosis remains specific to 10.2.3.0.

## Reproduction and artifacts

With the probe compiled as described in the [native trial report](DWSIM_NATIVE_PSEUDOCOMPONENT_TRIAL.md), run from the workspace in PowerShell 7:

```powershell
.\tools\dwsim\Solve-EquivalentDuty.ps1
.\tools\dwsim\Validate-NativeProfile.ps1 -Profile build/dwsim-research/equivalent-duty/profile.json -Report build/dwsim-research/equivalent-duty/validation.json
```

The outer script gives each isolated worker a 30-second deadline, checks every returned native status, brackets the duty root, checks the final distillate independently and records all actual overrides. It rejects a nonstraddling bracket instead of extrapolating an unbounded search.

Main output files:

- `build/dwsim-research/equivalent-duty/summary.json`: requested/achieved targets and all outer evaluations.
- `build/dwsim-research/equivalent-duty/profile.json`: native properties, inputs, settings and full final profiles.
- `build/dwsim-research/equivalent-duty/validation.json`: independent checks and hashes.
- `build/dwsim-research/equivalent-duty/equivalent-duty.solved.dwxml`: solved inner native flowsheet.
- `build/dwsim-research/petroleum-duty-normalized-profile.json`: direct-duty failure with normalized feed and tighter settings.

Diagnostic source is under `tools/dwsim`; all generated binaries, downloaded public source, IL and raw traces are under the ignored `build/dwsim-research` directory. Production mod/solver code and the installed DWSIM runtime were not changed by this investigation.
