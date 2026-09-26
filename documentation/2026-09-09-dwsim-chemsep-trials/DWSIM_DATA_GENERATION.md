# Local DWSIM data generation for a V3 neural initializer

Investigated and smoke-tested on 9 September 2026.

Follow-up: [native pseudocomponent trial](DWSIM_NATIVE_PSEUDOCOMPONENT_TRIAL.md) successfully recalculated the supplied 30-fraction petroleum column and exported its native properties, inputs and profiles with independent balance checks. These results remain unqualified as V3 training labels.

## Confirmed on this machine

- Installation: `C:\Program Files\DWSIM`.
- Executable and automation assembly version: **10.2.3.0**.
- `DWSIM.Automation.dll`, its XML API documentation, and sample flowsheets are installed.
- The application configuration targets .NET Framework 4.7.2; the Windows x64 Framework compiler is available.
- A local C# automation probe loaded a workspace copy of `tests\basic\basic distillation.dwxmz`, called the solver, and exported its native column state.
- Result: `Solved=True`, zero returned solver errors, one native **Wang-Henke (Bubble Point)** column, 12 stages, 7 components, total condenser. The observed solve call took about 2.2 seconds and initialization/load/solve/export preparation about 7.3 seconds in this one run. These are smoke-test timings, not dataset throughput estimates.
- Exported composition arrays have 12 rows of 7 components; each row sums to one within floating-point rounding.

The sample is a water/alcohol separation. It verifies the automation/export path, not compatibility with CreateChemE's hydrocarbon/water model, not a qualified training label, and not a generated crude dataset.

Artifacts:

- Probe source: [AutomationProbe.cs](../../tools/dwsim/AutomationProbe.cs).
- Copied sample: `build/dwsim-research/basic-distillation.dwxmz`.
- Export: `build/dwsim-research/sample-profile.json`.
- Executable and runtime binding configuration: `build/dwsim-research/AutomationProbe.exe` and `.exe.config`.

The probe uses the installed engine in a separate process. It does not attach to or manipulate an unsaved GUI flowsheet, and does not overwrite the installed sample.

## Native DWSIM versus ChemSep

DWSIM has its own rigorous `DWSIM.UnitOperations.UnitOperations.DistillationColumn`, with native column solution methods. It can also host a separately selected ChemSep column through CAPE-OPEN. Its component database can independently contain ChemSep-sourced property data. These are three distinct facts: the property data source does not identify the column solver. [DWSIM native solver API](https://dwsim.org/api_help/html/N_DWSIM_UnitOperations_UnitOperations_Auxiliary_SepOps_SolvingMethods.htm), [DWSIM ChemSep database API](https://dwsim.org/api_help/html/Methods_T_DWSIM_Thermodynamics_Databases_ChemSep.htm), [ChemSep documentation](https://chemsep.org/book/docs/book2.pdf).

Local inspection found native column objects in both `tests\basic\basic distillation.dwxmz` and `samples\Petroleum Distillation.dwxml`. The former also identifies ChemSep as a compound database source. Generic palette entries containing `CapeOpenUO` in a saved file do not establish that its simulation objects include a ChemSep block; inspect actual object types.

The successful probe below applies to native DWSIM columns. An actual ChemSep CAPE-OPEN column would need a separate check of its exposed parameters and stage-profile export facilities; the native column fields cannot be assumed to exist on that external object.

## Recommended automation route

Use a small **x64 .NET Framework C# worker**, referencing or loading the installed DWSIM assemblies. A Python process could later coordinate sampling and training, but it is not required to operate this installation. Python.NET is an alternative bridge if its CLR and bitness are configured consistently.

DWSIM's official automation documentation recommends .NET and says direct .NET use does not require COM registration. Avoid adding registration steps merely to run a C# worker. Its automation interface supports loading flowsheets, changing simulation objects, recalculating, and saving results. [DWSIM automation documentation](https://dwsim.org/wiki/index.php?title=Automation).

Confirmed installed entry points:

| API | Role |
|---|---|
| `new DWSIM.Automation.Automation3()` | Initialize the automation engine and available properties/compounds |
| `LoadFlowsheet2(absolutePath)` | Load a copied `.dwxml` or `.dwxmz` flowsheet |
| `flowsheet.SimulationObjects` / `GetFlowsheetSimulationObject(tag)` | Identify objects and access their typed interfaces |
| `MaterialStream.SetTemperature(double)` | Feed temperature in K |
| `MaterialStream.SetPressure(double)` | Pressure in Pa |
| `MaterialStream.SetMolarFlow(double)` | Molar flow in mol/s |
| `MaterialStream.SetOverallComposition(Array)` | Set composition in the explicitly tracked component order |
| `Column.SetNumberOfStages(...)`, `SetStreamFeedStage(...)` | Configure geometry; topology changes require corresponding connections/specifications |
| `Column.Specs` | Access the column's typed operating specifications; validate types, units and meanings per template |
| `Automation3.CalculateFlowsheet4(flowsheet)` | Run the solver and return its exception list; used in the smoke test |
| `flowsheet.Solved` and object `Calculated` | Additional success checks after calculation |
| `ReleaseResources()` | Release the automation instance's resources |

Evidence: installed `DWSIM.Automation.xml`, `DWSIM.Thermodynamics.xml`, `DWSIM.UnitOperations.xml`, reflection against the installed assemblies, and [public Automation3 API](https://dwsim.org/api_help/html/T_DWSIM_Automation_Automation3.htm).

Do not confuse overloads: the installed public `Automation3.CalculateFlowsheet3` returns `void`, while the explicit `AutomationInterface.CalculateFlowsheet3` returns exceptions and accepts a timeout. A future worker should use a verified error-returning API and a supervisor-enforced process deadline. An internal solver timeout alone is not a guarantee that loading, plugins or cleanup cannot hang.

## Profile data exposed by the installed native column

Reflection and the successful export confirmed these **public fields**:

| Field | Intended export |
|---|---|
| `compids` | Component ordering for composition vectors |
| `Tf` | Final stage temperature profile |
| `P0` | Stage pressure vector; explicitly verify alignment with `Stages` in each template |
| `Lf`, `Vf` | Final liquid and vapor molar-flow arrays |
| `xf`, `yf` | Final liquid and vapor composition vectors per stage |
| `LSSf`, `VSSf` | Liquid/vapor side-stream flow arrays |

The probe exports these fields verbatim, with engine version, column type, condenser type, solver name, stage count and success flags. Production export must add a verified unit schema, complete input conditions, property-package and compound parameters, boundary-stage semantics, spec provenance and quality checks. Do not treat raw array names as a self-describing dataset.

For mapped V3 states, component flows follow `l[j,i] = L[j] * x[j,i]` and `v[j,i] = V[j] * y[j,i]` after unit and ordering conversion. Match physical phase presence separately. For example, this total-condenser sample exports a tiny top vapor flow of `1e-10`; numerical floors are not evidence of a physically present phase and must not automatically become V3 training targets. Auxiliary phase compositions may be populated even where that phase is absent.

## How to generate useful training data

1. **Build and validate one matching template.** Start with a single fixed hydrocarbon component basis and tray geometry. Align pseudocomponent parameters, binary interactions, pressure profile, ideal-stage efficiency, condenser boundary and reflux convention. Match V3's stage heat convention rather than silently substituting a circulating pumparound.
2. **Define input space.** Sample feed composition, temperature, total flow, pressures, reflux and reboiler duty. Introduce draw/steam/heat variants only when their physical and boundary mappings are qualified. Record actual resolved specifications, not merely the name of the simulator's design mode.
3. **Run copied cases through the worker.** Change the actual typed stream/spec values, solve, and capture success/failure, settings, elapsed work, and all stage profiles. Use deterministic case IDs and random seeds. Preserve failed cases as failure records; never export stale converged arrays from the prior case as fresh labels.
4. **Control process state.** A single isolated worker can process a bounded chunk, then restart. Only scale to a limited number of independent processes after measuring memory and determinism. Do not assume multiple flowsheets in one shared process are thread-safe. If warm starts are used during data generation, record their history because it can affect branch selection and convergence.
5. **Validate labels.** Check dimensions, finiteness, positivity of present phases, composition sums, component and energy closure, and solver status. Store the full input/property provenance and distinguish numerical convergence from physical compatibility.
6. **Qualify transfer to V3.** Import selected profiles, reconstruct the exact V3 input, and try native correction and audit. Keep the external and corrected-native states separately. Use successfully audited native states as the preferred final supervised labels.
7. **Split by operating region.** Reserve regions, not merely randomly interleaved rows from nearby sweeps, to test generalization. Compare neural predictions with nearest-neighbor retrieval under the same bounded V3 correction/fallback policy.

Use a **rigorous column**, not DSTWU/shortcut results, for full-state supervised targets. The first success criterion is that imported states help V3 converge; bulk generation should follow that transfer check. No production dataset size or inference speedup has been established.

## Reproducing the local smoke test

Run from the CreateChemE workspace in PowerShell. All simulation/output arguments are absolute. The probe has an inspection-only mode when only the installation path is passed.

```powershell
New-Item -ItemType Directory -Path build/dwsim-research -Force | Out-Null
& 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe' /nologo /target:exe /platform:x64 /r:System.Web.Extensions.dll /r:Microsoft.CSharp.dll '/out:D:\Minecraft\Modding\1.21\CreateChemE\build\dwsim-research\AutomationProbe.exe' 'D:\Minecraft\Modding\1.21\CreateChemE\tools\dwsim\AutomationProbe.cs'
Copy-Item -LiteralPath 'C:\Program Files\DWSIM\DWSIM.exe.config' -Destination build/dwsim-research/AutomationProbe.exe.config
Copy-Item -LiteralPath 'C:\Program Files\DWSIM\tests\basic\basic distillation.dwxmz' -Destination build/dwsim-research/basic-distillation.dwxmz
& '.\build\dwsim-research\AutomationProbe.exe' 'C:\Program Files\DWSIM' 'D:\Minecraft\Modding\1.21\CreateChemE\build\dwsim-research\basic-distillation.dwxmz' 'D:\Minecraft\Modding\1.21\CreateChemE\build\dwsim-research\sample-profile.json'
```

The probe is research scaffolding. It does not implement parameter sweeps, hard process deadlines, restart/resume, checksums, comprehensive input export, or V3 label qualification. Those are the next implementation tasks for a data-generation pipeline.
