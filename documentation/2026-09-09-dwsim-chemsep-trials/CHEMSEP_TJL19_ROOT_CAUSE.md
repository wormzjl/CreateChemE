# Why the TJL19 ChemSep trial fails

Follow-up: [progressive steam/PA testing](CHEMSEP_TJL19_PROGRESSIVE_TEST.md) also fails with zero steam, zero PA, and even Water removed from the component list. The wet-flash defects below are reproducible, but are not a complete explanation of the column convergence problem; the dry core requires a separate investigation.

Investigated 2026-09-10 against the installed **DWSIM 10.2.5.0** thermodynamics assembly, SHA-256 `30B783BB93EEEED62D9188D64CDA10FA95AD2C63EB584AC5AB1609BC625B5B70`, with ChemSep Lite 8.50.

**Two reproducible defects in the native pressure/vapor-fraction flash path explain why ChemSep receives unreliable initialization states for the wet TJL19 case.** One returns a false bubble-point convergence; the other solves a three-phase bubble point with an ideal-vapor approximation while subsequent PR78 property calls use nonideal vapor fugacity. The distinction matters: the column's Newton method has not been shown to be the original full-case blocker.

The input is the [current 40-tray TJL19 case](CHEMSEP_V3_TJL19_TRIAL.md), including 333.333333 mol/s steam and 41.93 MW distributed cooling. The original input, component records and operating specifications were retained for the full-case investigations.

## 1. A bubble flash can report convergence with the bubble equation far from zero

A water-rich composition was captured from ChemSep's actual initialization calls and replayed directly through the installed `NestedLoops.Flash_PV_1` and `Flash_PV` methods. The composition contains about 87.39 mol% water, with the original 19-component hydrocarbon axis and property records.

At 250000 Pa, both methods returned:

| Quantity | Native returned value |
| --- | ---: |
| Temperature | 537.595154 K |
| Reported iterations | 21 |
| Reported temperature step | 0 K |
| Sum of xᵢKᵢ using the returned K values | **20.2738046** |
| Required sum at a bubble point | **1** |
| Maximum log fugacity mismatch on components present above 1e-6 in both phases | 3.75479 |

Component mass balance alone passes because vapor fraction is zero. That does not establish bubble-point equilibrium. The returned normalized vapor composition conceals the large error in the unnormalized bubble equation.

Installed IL inspection locates a multicomponent, near-trivial-solution fallback in `Flash_PV_1`. After a Brent call, it recomputes K, normalizes the vapor composition, sets the temperature-step variable to zero and exits the iteration loop. The block does not independently recheck the bubble equation or fugacity equality before that exit. The relevant installed IL offsets are 005627–005834, with the explicit zero assignment at 005823–005832. The near-trivial test uses a composition-distance threshold scaled by component count.

This is based on the installed DLL, not an assumption that it matches the public repository. In fact, the downloaded public source differs in this branch. Disabling the installed azeotrope shortcut through a research-only reflection probe changes the result, but does not by itself produce a qualified flash. Reordering the same mixture with water first does not eliminate the bad result. Tightening flash tolerances to 1e-10 and disabling ideal fallback also leaves this particular bad return unchanged.

## 2. The three-phase bubble routine omits the vapor fugacity factor

The native `NestedLoops3PV3.Flash_PV_3P` calculation updates vapor composition using the activity/vapor-pressure form:

```text
Ki = gamma_i * Psat_i / P
yi = Ki * xi
```

For the PR78 liquid calculation in this path, the activity/vapor-pressure term corresponds to the liquid fugacity. The vapor fugacity coefficient is missing. The later column property calls require:

```text
yi * phiV_i = xi * phiL_i
```

This is directly visible in the numerical results. In the full case's first initialization bubble flash, the organic liquid and vapor satisfy the ideal-vapor relation, but the full PR78 equilibrium defect is almost exactly `-ln(phiV_i)` for the meaningful components. For TJL_PC03 it is **0.174897**, equivalent to roughly a **19% fugacity-ratio error**. The error is not removed by tightening the native three-phase iteration tolerances, because the equation being solved is different.

The installed three-phase method's IL and its numerical behavior were checked. The public implementation also shows the activity/vapor-pressure update in [NestedLoops3PV3.vb](https://github.com/DanWBR/dwsim/blob/windows/DWSIM.Thermodynamics/FlashAlgorithms/NestedLoops3PV3.vb). The local replay, rather than the web source alone, establishes the installed behavior.

## Counterfactual correction

`CorrectedWaterPvFlash.cs` is an isolated research adapter. It seeds separate aqueous and organic liquids and applies an outer vapor-fugacity correction around the native three-phase routine. The same correction factor is applied to both liquid fugacity coefficients during the inner calculation, so it cancels from liquid/liquid equilibrium while supplying the missing vapor factor. The factor is iterated to consistency and removed before normal property calls resume. Native component data and ordinary PR78 property values are unchanged.

| Captured initialization state | Original native behavior | Corrected temperature | Corrected equilibrium defect* |
| --- | --- | ---: | ---: |
| First full-case bubble flash | 325.679871 K; missing vapor factor | 323.576229 K | 5.08e-12 |
| Water-rich initialization state | False single-liquid bubble at 537.595154 K | 354.648055 K, two liquids | 6.13e-11 |

*Maximum absolute log fugacity mismatch across the returned liquid phases for components above 1e-6 in both compared phases. Maximum component-balance defects are below 1.3e-16. This screening avoids interpreting numerical traces in the aqueous phase as accurate solubility predictions.

These controlled replays support the causal diagnosis. They are not a validation of the entire column or a general replacement flash algorithm. The full corrected trial passed many initialization flashes, then reached a later native iteration limit after 9.771 seconds. A further hybrid diagnostic permits a VLE fallback only after independent bubble-equation and fugacity checks; it continued generating initial compositions until its 90-second deadline. Those checks do not establish global phase stability. No converged full ChemSep column is claimed.

## Diagnostic setup corrections and other findings

- **Flash selection:** this installed version's `FlashBase` selects a `UniversalFlash` through `FlashSettings`. Assigning the older `FlashAlgorithm` property did not test the intended immiscible algorithm. The earlier trial's label should not be interpreted as evidence that the intended algorithm ran.
- **CAPE options:** DWSIM maintains copies of native unit parameters and restores them before calculation. The research worker now calls `UpdateParams()` after changing native options, preventing those changes from being overwritten. Effective derivative-option trials still did not cure the low-steam control's failed Newton run.
- **Derivative probes:** native Cp differs from finite differences of native enthalpy at sampled states. A low-level partial-molar-enthalpy routine is also highly sensitive to unnormalized input. However, calls to that low-level routine were not observed in the failed ChemSep trace. Those tests do **not** establish the cause of the column failure and are not used as the primary diagnosis.
- **Property transfer:** dry hydrocarbon fugacity comparisons against V3 at sampled native bubble states differ by at most 1.67e-4 in log coefficient. There is no evidence here of a component-axis or gross pressure-unit error. Full water and caloric equivalence with V3 remains unestablished.
- **Condenser and cooling mapping:** the accepted V3 reference selects a liquid-only condenser. Its temperature and organic reflux specification were mapped to the native subcooled total condenser, with a water draw. The prescribed cooling was mapped to nine tray duties, matching V3's heat-only pumparound model.

## Evidence and reproduction

All investigation artifacts are under `build/dwsim-research/chemsep-cause/`:

- `confirmed-findings.json`: compact numerical evidence, DLL identity and corrected replay checks.
- `contracts/input.json`, `contracts/thermo-contract.json`: captured compositions and direct native-method replays.
- `PV1-installed.il`, `PV3-installed.il`, `GetFlash-installed.il`: installed implementation evidence.
- `full-case-validated-flash/`: full-case run stopped by an independent equilibrium check, with the rejected native state and native reports.
- `full-corrected-pv/`: full-case experimental three-phase correction trial.
- `full-corrected-hybrid/`: experimental correction with independently checked VLE fallback.
- `perturbed-effective/`: derivative-option control after fixing parameter restoration.
- `v3-native-fugacity-check.json`: sampled hydrocarbon property comparison.

After compiling the research worker through `Run-ChemSepV3Trial.ps1`, the direct replay can be run from the repository root:

```powershell
& .\build\dwsim-research\ChemSepV3Trial.exe 'C:\Program Files\DWSIM' `
  "$PWD\build\dwsim-research\chemsep-cause\contracts\input.json" `
  "$PWD\build\dwsim-research\chemsep-cause\contracts" contract
```

The original installed simulator files and production Java solver were not modified. Failed or experimentally corrected states have not been promoted to training labels.
