# Pseudocomponent freezing and high-viscosity screen

Research date: 2026-09-17. This study adds no production properties or solid-phase behavior.

We can calculate where the existing liquid-viscosity curves cross selected levels. We cannot currently determine a physically qualified solidification temperature for any of the 13 TJL pseudocomponents. The installed DWSIM characterization contains zero for both `TemperatureOfFusion` and `EnthalpyOfFusionAtTf` for every cut. These are missing values, not freezing at 0 K. DWSIM's property package itself warns about a zero fusion temperature when checking solid-phase inputs. [DWSIM source](https://github.com/DanWBR/dwsim/blob/windows/DWSIM.Thermodynamics/PropertyPackages/PropertyPackage.vb).

The previous ambient fix made generation and liquid-property evaluation possible at 298 K. Its API agreement tests establish numerical reproduction, not physical qualification of residue rheology or proof that every isolated heavy cut is liquid at ambient temperature.

## Direct results for all 13 cuts

Evaluated the installed DWSIM 10.2.5.0 API `AUX_LIQVISCi` at 100,000 Pa using the original `source-feed.dwxml`. The probe scans in 1 K intervals and refines threshold crossings by bisection. The screening script checks source molecular weights and NBPs against the production records and checks the API samples and threshold roots against the catalog interpolation to 0.0501% relative viscosity tolerance.

Thresholds of **1, 10 and 100 Pa s** (1,000, 10,000 and 100,000 cP) are selected reporting levels, not universal pumpability limits or solid transitions. Crossing temperatures below refer to cooling through that level while following the hypothetical liquid curve. A pump's usable range additionally depends on pressure head, pipe geometry and required flow. A wax gel also requires yield-stress/rheological information.

All temperature columns use °C. Cut labels are the existing estimated midpoint ranges, not representative NBPs. `Not reached` means the curve does not cross that threshold between 20°C and its sampled upper limit, min(900 K, 0.95 Tc); no colder extrapolation was made.

| Component | Estimated NBP cut, °C | Dynamic viscosity at 298 K, Pa s | T at 1 Pa s | T at 10 Pa s | T at 100 Pa s |
|---|---:|---:|---:|---:|---:|
| tjl19_pc01 | below 84 | 0.0002374 | not reached | not reached | not reached |
| tjl19_pc02 | 84–135 | 0.0003741 | not reached | not reached | not reached |
| tjl19_pc03 | 135–183 | 0.0006047 | not reached | not reached | not reached |
| tjl19_pc04 | 183–231 | 0.001048 | not reached | not reached | not reached |
| tjl19_pc05 | 231–280 | 0.001990 | not reached | not reached | not reached |
| tjl19_pc06 | 280–329 | 0.004180 | not reached | not reached | not reached |
| tjl19_pc07 | 329–378 | 0.01001 | not reached | not reached | not reached |
| tjl19_pc08 | 378–436 | 0.02878 | not reached | not reached | not reached |
| tjl19_pc09 | 436–514 | 0.1958 | not reached | not reached | not reached |
| tjl19_pc10 | 514–609 | 12.72 | 48.1 | 26.7 | not reached |
| tjl19_pc11 | 609–731 | 1.931 × 10⁷ | 93.0 | 75.9 | 63.1 |
| tjl19_pc12* | 731–875 | 2.426 | 354.7* | not reached | not reached |
| tjl19_pc13* | above 875 | 4.501 | 577.8* | not reached | not reached |

**Do not use the starred values as physical operating temperatures.** They expose the existing fallback behavior. Even the unstarred values are conditional estimates from characterized liquid curves; neither a phase-stability calculation nor measured cut viscosity was supplied.

## Why the heaviest three curves need attention

PC11 has estimated native kinematic viscosities of 97.8511 m²/s at 310.95 K and 0.000559590 m²/s at 372.05 K. Their extreme ratio drives the enormous low-temperature result. The API agrees with the imported table, but that does not validate these reference estimates physically.

For PC12 and PC13, `PF_vA` and `PF_vB` are NaN. More decisively, direct calls to `oilvisc_twu` return NaN at every reported checkpoint. DWSIM then uses `viscl_letsti`; the probe confirms that `AUX_LIQVISCi` equals that fallback multiplied by its liquid density. Thus the modest 298 K viscosities of PC12–13 are not evidence that the heaviest residue flows more easily than PC11. The public implementation confirms the fallback branch. Its density multiplication also deserves a units audit before adopting it as a residue model. [DWSIM source, AUX_LIQVISCi](https://github.com/DanWBR/dwsim/blob/windows/DWSIM.Thermodynamics/PropertyPackages/PropertyPackage.vb).

Letsou–Stiel is a high-temperature correlation with a documented reduced-temperature applicability interval of approximately 0.76–0.98. A finite result outside that range is not validation. [Correlation implementer's documentation](https://chemicals.readthedocs.io/chemicals.viscosity.html#chemicals.viscosity.Letsou_Stiel).

Priority before production cold-flow behavior: obtain or fit defensible residue viscosity reference data for PC11–13, verify units, and add a suitability distinction between imported API values and physically qualified cold-flow data. No values were replaced in this research task.

## Can a melting temperature be estimated?

Yes, as a **characterization hypothesis**, but the current NBP/MW/density does not uniquely determine it. A pseudocomponent contains multiple chemical families. A cut can crystallize progressively, while dissolved wax can remain in a liquid crude below the corresponding pure-wax melting temperature. Bulk pour point is a separate handling test with a dependence on thermal history. [ASTM D5853-24](https://store.astm.org/d5853-24.html).

For a sensitivity calculation, the Lira-Galeana/Firoozabadi/Prausnitz paper reproduces Won's n-alkane relation and introduces a petroleum-fraction alternative:

`Tf,W = 374.5 + 0.02617 M − 20172/M`

`Tf,LG = 333.46 − 419.01 exp(−0.008546 M)`

Here M is g/mol and Tf is K. These alternatives describe different characterization assumptions. They are not an uncertainty interval or lower/upper bounds. The latter correlation was derived using normal-paraffin, naphthenic and aromatic data through approximately C30; applying it to the extreme residue molecular weights is extrapolation. Its convergence near 60°C must not be interpreted as a known residue melting point. [Primary research, equations 8–9](https://escholarship.org/uc/item/9cb4w8ss).

| Component | Won n-alkane hypothesis, °C | Lira-Galeana fraction hypothesis, °C |
|---|---:|---:|
| PC08 | 51.5 | 37.9 |
| PC09 | 66.4 | 50.2 |
| PC10 | 83.0 | 57.7 |
| PC11 | 99.6 | 60.0 |
| PC12 | 121.0 | 60.3 |
| PC13 | 150.3 | 60.3 |

The disagreement is a reason to retain `solidification_temperature_kelvin: null` in the research results. It is especially unsafe to assign the entire PC12/PC13 fraction the melting temperature of a hypothetical normal alkane with its average molecular weight. All 13 sensitivity calculations are retained in the accompanying JSON, explicitly separate from physical predictions.

DWSIM also has bulk pour/cloud/freezing-point correlations in its petroleum utility. Their existence does not supply missing fusion measurements or justify applying a distillate-style correlation to extreme residues; the viscosity-dependent pour-point route inherits the problematic native viscosities above. [DWSIM cold-flow utility](https://github.com/DanWBR/dwsim/blob/windows/DWSIM/Utilities/PetroleumColdFlowProperties/FrmColdProperties.vb).

## A defensible future model

Keep the existing cut identity, NBP and PR/Cp datasets. Attach a separate crude-dependent wax/rheology characterization to the cut amounts. At minimum this needs wax-forming fraction and n-paraffin carbon-number distribution (or explicitly estimated PNA subdivision), fusion properties for the wax species, and a liquid–solid equilibrium model. Calibrate against whole-crude or cut DSC wax appearance/disappearance temperatures and wax fraction versus temperature; use viscosity-versus-temperature and shear measurements for flow behavior. A published PR-based study uses PNA subfractions and measured DSC/viscometry data rather than assigning every heavy cut one universal freezing temperature. [Primary study](https://doi.org/10.1007/s13202-018-0480-1).

Store distinct concepts: wax onset, solid fraction, pour/gel behavior and viscosity thresholds. A linear weighted average of cut melting temperatures is not a crude freezing point. The existing crude presets share the same pseudocomponent property basis, so this screen gives the same isolated-cut numbers for each crude; different cold-flow behavior requires composition-dependent equilibrium and crude-specific wax information.

For VDU/residue processing, preserve both wax-forming and non-wax residue information when merging heavy cuts. A single heavy lump with a single solidification temperature would remove precisely the distinction needed here.

## Follow-up: consolidation and a practical no-flow criterion

Consolidation reduces the number of curves to characterize, but cannot repair missing or invalid data by averaging them. The first candidate remains PC12+PC13 (roughly 731°C+ in the current estimated cut scheme), keeping PC10 and PC11 separate for VDU assessment. This does not fix PC11's viscosity anomaly. A merged tail needs a newly qualified transport fit, with its changing composition across crudes and processing states accounted for; inheriting or averaging the two fallback curves is not qualification.

An easier first experiment is a grouped transport/rheology characterization over the existing component inventory. This preserves the separation basis and allows separate wax-forming fraction, non-wax residue, and reaction-quality information. A single merged heavy component with one melting point would suppress distinctions needed for wax precipitation and residue conversion. Any eventual thermodynamic basis reduction still needs VDU flash, enthalpy, mass and yield validation.

There is **no universal viscosity at which a mixture becomes non-flowable**. For an ideal Newtonian liquid in a horizontal circular pipe, steady fully developed laminar flow obeys

`Q = pi D^4 DeltaP / (128 mu L)`.

Here Q is m³/s, D and L are metres, DeltaP is the net pressure drop available for pipe friction in Pa, and mu is Pa s. Every finite viscosity gives a nonzero flow for a positive pressure difference in this idealization. Practical unusability requires a declared minimum useful rate, Qmin, and the actual available pressure. Its corresponding viscosity limit is `mu_limit = pi D^4 DeltaP_available / (128 L Qmin)` under these assumptions. The current `PipeResistance` laminar branch implements this dependence; it has no material yield-stress term.

For a hypothetical straight, horizontal 10 m pipe of 50 mm internal diameter with 1 bar available pressure drop, ignoring fittings:

| Viscosity, Pa s | Predicted laminar flow, L/s |
|---:|---:|
| 1 | 1.534 |
| 10 | 0.1534 |
| 100 | 0.01534 |
| 1,000 | 0.001534 |

For a density of 1,000 kg/m³ all four examples are laminar (maximum Reynolds number about 39). If the selected useful-flow threshold were 0.01 L/s, this particular installation would fall below it above about 153.4 Pa s. That value changes with the pump and pipe and is not a universal property of the material. Doubling diameter multiplies ideal laminar flow by 16 at the same pressure difference, length and viscosity.

A wax gel adds another mechanism: yield stress. In an ideal no-slip Bingham pipe model, bulk steady flow requires wall shear stress `tau_wall = DeltaP D/(4L)` to exceed `tau_y`; equivalently `DeltaP > 4L tau_y/D`. In the example, the available wall stress is 125 Pa. A gel with yield stress at least that large would remain unyielded in that ideal model. This is distinct from a viscosity threshold. [SLB rheology explanation](https://www.slb.com/resource-library/oilfield-review/defining-series/defining-rheology).

Real waxy-crude restart is more complicated: cooling/shear history, gel fracture, creep and time-dependent structural breakdown matter. Treat the yield-stress condition as a simplified future model, not a guaranteed real-pipeline restart pressure. [Experimental pipeline/rheometer study](https://www.sciencedirect.com/science/article/pii/S0009250919307043).

For future gameplay, distinguish **low flow under available head**, **unyielded gel**, and **solid blockage**. A chosen display/activity cutoff should not silently erase material or turn a numerical convergence failure into a physical freeze. This follow-up is a design recommendation only; no production flow law or component basis was changed.

## Reproduction and artifacts

- `examples/Probe-DwsimColdFlow.cs`: compile using .NET Framework csc with `Microsoft.CSharp.dll` and `System.Web.Extensions.dll`, platform x64. Copy `DWSIM.exe.config` beside the resulting executable as `<executable>.config`. Invoke with `<DWSIM installation> <original source-feed.dwxml> <output.json>`. Use absolute Windows-native paths for compilation. The original simulation is loaded but never saved.
- `examples/cold-flow/dwsim-probe.json`: direct installed-API results, engine version, native fusion/viscosity metadata, and SHA-256 of the source characterization.
- `python examples/analyze_cold_flow.py examples/cold-flow/dwsim-probe.json`: validates identity, API/table agreement and fallback behavior, then regenerates `examples/cold-flow/screening.json`.
- The source flowsheet is an offline input, not redistributed here. Reanalyzing the committed probe requires only Python and the repository catalog. Production Java and material records are unchanged by this study.
