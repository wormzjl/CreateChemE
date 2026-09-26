# Native DWSIM pseudocomponent trial

Run locally on 9 September 2026 with installed DWSIM **10.2.3.0**.

Follow-up: [the reboiler-duty investigation](DWSIM_REBOILER_DUTY_INVESTIGATION.md) confirmed a missing duty equation in the installed simultaneous solver, traced Wang–Henke's unstable duty iteration, corrected a small feed-normalization defect in a separate copy, and demonstrated a successful equivalent outer duty solve.

**Result: the supplied petroleum-distillation example solves successfully using its own characterized pseudocomponent data.** No V3 property constants were substituted. This is a native-engine data-generation check, not a qualified V3 training dataset.

## Case and initialization

Source: `C:\Program Files\DWSIM\samples\Petroleum Distillation.dwxml`.
Workspace copy: `build/dwsim-research/petroleum-native.dwxml`.

| Setting | Value |
|---|---|
| Property package | Native DWSIM Peng–Robinson |
| Column solver | Native Wang–Henke (Bubble Point) |
| Components | 30 petroleum fractions, `PSE_3165_2` through `PSE_3165_31` |
| Database/flags | OriginalDB and CurrentDB = DWSIM; IsPF = 1 |
| Normal boiling-point range | 334.305–388.158 K |
| Molecular-weight range | Approximately 78.33–95.38 g/mol |
| Physical nodes | 12: total condenser, 10 trays, reboiler |
| Feed | 500 mol/s, 350 K, 101325 Pa; saved composition retained |
| Feed node | 7, with condenser indexed 0 |
| Pressure profile | 101325 Pa throughout |
| Liquid side draws | 40 mol/s at node 3, 70 mol/s at node 7 |
| Saved condenser specification | Product molar flow, 290.01 mol/s |
| Saved reboiler specification | Product molar flow, 100 mol/s |
| User temperature/flow/composition estimates | All disabled; native automatic initialization |
| Internal/external solver tolerances | Original 1e-4 / 1e-3 |

The file contains saved characterized fraction properties. It does not expose a populated original petroleum assay curve in its `PetroleumAssays` section. Thus this is a test of DWSIM's supplied characterized data, not a regeneration of pseudocomponents from a raw TBP curve. The boiling range describes a **light petroleum fraction**, not a full crude assay comparable to V3's heavy-residue basis.

## Freshly calculated results

| Quantity | Result |
|---|---:|
| Flowsheet solved / column calculated | true / true |
| Returned solver errors | 0 |
| Solve-call time | 333 ms initially; 347 ms on the expanded-export rerun |
| Condenser temperature | 344.701553 K |
| Reboiler temperature | 364.559735 K |
| Light product | Approximately 290.001016 mol/s |
| Bottoms | 100 mol/s |
| Actual reflux/distillate ratio | 2.498838578 |
| Condenser heat removed | 29.756494 MW |
| Reboiler heat added | 27.282471 MW |

Timings are individual local observations, not throughput or speedup benchmarks. The full automation process also incurs initialization/loading time. The light-product specification differs slightly from the solved flow; retain both authored and resolved values. The original two product-flow specifications are not a qualified independent specification set for a new dataset. Before sweeps, use and validate a nonredundant set such as reflux ratio plus bottoms flow.

## Independent numerical checks

Checks use freshly exported stream and profile arrays, not the saved pre-solve results.

| Check | Result |
|---|---:|
| Profile dimensions | 12 nodes × 30 components in each phase |
| Maximum composition-sum error | 2.22e-16 |
| Minimum exported mole fraction | 1.94e-5 |
| Overall molar balance error | 1.00e-10 mol/s |
| Overall mass balance error | -8.40e-5 kg/s, about 2.05 ppm of feed |
| Maximum overall component balance error | 7.72e-5 mol/s |
| Maximum local component balance error | 2.57e-4 mol/s |
| Overall energy balance error | 4.11e-10 kW |

These numbers retain the sample's loose convergence settings. They are not evidence of V3's tighter local equilibrium/acceptance gates. The tiny exported top vapor flow (`1e-10 mol/s`) is a numerical floor, not a physical overhead-vapor product.

## Artifacts and reproduction

- `build/dwsim-research/petroleum-native-profile.json`: native compound scalar properties, original/resolved stream data, column connections, specifications, initialization flags, solver status, temperatures, pressures, phase flows, compositions, side flows and duties.
- `build/dwsim-research/petroleum-native-validation.json`: independent checks and SHA-256 hashes of the copied source and exported data.
- `tools/dwsim/AutomationProbe.cs`: sample loading, fresh calculation and export; failed solves do not export cached profiles as labels.
- `tools/dwsim/Validate-NativeProfile.ps1`: checks dimensions, values, composition normalization, local/overall component balances and global energy balance.

From the workspace in PowerShell 7:

```powershell
& 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe' /nologo /target:exe /platform:x64 /r:System.Web.Extensions.dll /r:Microsoft.CSharp.dll '/out:D:\Minecraft\Modding\1.21\CreateChemE\build\dwsim-research\AutomationProbe.exe' 'D:\Minecraft\Modding\1.21\CreateChemE\tools\dwsim\AutomationProbe.cs'
Copy-Item -LiteralPath 'C:\Program Files\DWSIM\DWSIM.exe.config' -Destination build/dwsim-research/AutomationProbe.exe.config
Copy-Item -LiteralPath 'C:\Program Files\DWSIM\samples\Petroleum Distillation.dwxml' -Destination build/dwsim-research/petroleum-native.dwxml
& .\build\dwsim-research\AutomationProbe.exe 'C:\Program Files\DWSIM' 'D:\Minecraft\Modding\1.21\CreateChemE\build\dwsim-research\petroleum-native.dwxml' 'D:\Minecraft\Modding\1.21\CreateChemE\build\dwsim-research\petroleum-native-profile.json'
.\tools\dwsim\Validate-NativeProfile.ps1 -Profile build/dwsim-research/petroleum-native-profile.json -Report build/dwsim-research/petroleum-native-validation.json
```

The source sample hash for this run is `40c0658d87b375c735b9c09f77bd33cd5b441eba8abb46318b4306860c40b771`.

## Earlier controls and transfer status

Before the pseudocomponent trial, a native n-hexane/n-heptane case converged with DWSIM's built-in CoolProp-sourced constants and standard PR. A research-only fixture copied that native data into the unchanged V3 PR78 kernel and verified its quadratic heat-capacity conversion against sampled DWSIM Cp values. Its imported seed had maximum scaled residual about 6.48e-6; one V3 Newton correction converged numerically, but the independent condenser-phase audit **failed** at the saturated outlet. It is explicitly not an accepted training label. No acceptance gate was changed.

The earlier custom PC03/PC10 experiments used V3 pseudocomponent constants in DWSIM; their property comparison at 550 K, 250 kPa and 50/50 mole composition found PC10 liquid fugacity coefficient approximately 25% higher in standard DWSIM PR than V3 PR78. That difference cannot be extrapolated to all native DWSIM fractions. Those custom column trials did not qualify a matched converged case.

The native petroleum trial has **not** yet been transferred to V3: it has a different 30-component axis, native petroleum heat-capacity correlations and two side draws. Qualified transfer requires mapping these properties and boundaries and passing the unchanged native correction and physical audit. No bulk training sweep or neural model was created.

## Replacing bottoms flow with reboiler duty

At the user's request, replaced only the reboiler operating specification: from 100 mol/s bottoms to **27,282.4707032937 kW heat added**, taken from the fresh flow-specified solution. Retained the 290.01 mol/s distillate specification, native compound data, 500 mol/s feed, both side draws, pressure profile, geometry and original tolerances.

**Outcome: this duty-specified case did not converge in the installed engine.** Wang–Henke failed with automatic estimates and with a complete warm start reconstructed from a fresh successful flow-specified solve (temperature, liquid/vapor flows, compositions, distillate, bottoms and vapor product estimates). Modified Wang–Henke and Napthali–Sandholm attempts also failed. These outcomes do not prove physical infeasibility and do not invalidate the earlier successful flow-specified case. No failed attempt exported cached stage profiles as new labels.

The heat-duty **specification** uses positive heat input for Wang–Henke, while its output `ReboilerDuty` field reports negative stage heat. An initial negative-specification trial was corrected after checking the public solver source; the positive, physically intended specification still failed. Cached public Napthali–Sandholm source used a different signed-stage-heat convention, so both signs were tried there, without obtaining convergence. Do not assume a uniform sign convention across solver versions without checking a solved energy balance.

Artifacts:

- `build/dwsim-research/petroleum-duty-overrides.json`: positive heat-input override in watts.
- `build/dwsim-research/petroleum-duty-profile.json`: failed automatic-initialization attempt.
- `build/dwsim-research/petroleum-duty-complete-seed-profile.json`: failed complete warm-start attempt; 453 ms solve call plus 377 ms initialization solve in the recorded run.
- `build/dwsim-research/petroleum-duty-complete-seed-profile.configured.dwxml`: inspectable configured case. Its pre-solve cached values are from the initialization solve and are not a converged duty-specified result.
- `build/dwsim-research/petroleum-duty-modified-profile.json` and `petroleum-duty-ns-positive-profile.json`: alternate native solver failure records.

The probe now accepts an optional fourth argument naming an overrides JSON file. `reboiler_heat_added_W` changes the reboiler specification. `initialize_from_native_flow_solution: 1` first solves the unmodified input to obtain a warm start. `column_solver_code` selects 0 = Wang–Henke, 1 = modified Wang–Henke, 2 = Napthali–Sandholm. All applied specifications are exported before the attempted solve, and configured flowsheets are saved separately from successful solved flowsheets.
