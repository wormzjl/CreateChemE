# Current V3 TJL19 case in ChemSep

Latest test: [the progressive steam/PA matrix](CHEMSEP_TJL19_PROGRESSIVE_TEST.md) contains 13 completed trials. Failure already occurs in the dry, zero-PA starting case, including a control with Water entirely absent.

Follow-up: [the root-cause investigation](CHEMSEP_TJL19_ROOT_CAUSE.md) identified a false-convergence path and a missing vapor-fugacity factor in the installed DWSIM bubble-flash calculations. It also corrects the interpretation of the earlier flash-algorithm and native-parameter option trials.

Tested locally on 2026-09-10 with DWSIM 10.2.5.0 and ChemSep Lite 8.50. **No converged ChemSep solution was obtained for the current full V3 case.** The case was accepted by the CAPE-OPEN unit's configuration validator, but full-steam trials spent several minutes generating initial compositions. The unchanged current V3 calculator solved and audited its own case in 2.249 seconds in one measured run.

This is different from the earlier [successful native petroleum trial](CHEMSEP_NATIVE_PSEUDOCOMPONENT_TRIAL.md). That trial had 30 light petroleum fractions and 12 stages; this one uses the project's actual TJL19 default, with steam, three side draws and distributed cooling.

## Input mapping

`Export-V3ChemSepCase.ps1` extracts the current `literatureCduInput()` method and its two constants from `ColumnCalculatorV3BlockEntity.java`, compiles that method with the current science sources, and exports the resolved input and registered property data. This avoids reusing an older 30-tray default or the earlier two-component pilot.

| Input | Current V3 value / ChemSep mapping |
| --- | --- |
| Property package | `createcheme:tjl19_dwsim`, revision `tjl19-dwsim-10.2.3-r1` |
| Hydrocarbon axis | Six real compounds plus 13 TJL pseudocomponents; 19 total |
| Water | Separate native Water component added for steam; 20 native components total |
| Trays | 40 equilibrium trays; 42 ChemSep stages including boundaries |
| Crude feed | 737.6996333000835 mol/s, 638.15 K |
| Feed location | V3 tray 37 → ChemSep stage 38 |
| Pressure | 250000 Pa throughout |
| Organic reflux ratio | 4.17 |
| Overhead temperature | 332.15 K |
| Reboiler duty | 0 W |
| Liquid side draws | 136.3888889, 143.0555556 and 45.8333333 mol/s at V3 trays 10, 18 and 28 → native stages 11, 19 and 29 |
| Steam | 333.3333333 mol/s at 533.15 K, V3 node 41 → native stage 42 |
| Cooling | -12.84, -17.89 and -11.20 MW, each spread uniformly across its three-tray zone |

V3's pumparound model adds only prescribed heat effects, not physical recycle flows. It was mapped to nine native tray coolers, totaling **-41.93 MW**, at ChemSep stages 9–11, 17–19 and 27–29.

The native property records were loaded from the original reduced TJL characterization file found under `run/codex-worktrees/v3-literature-cdu/output/cdu-characterization/tjl-dwsim-10.2.3-13pc/`. A separate copy is preserved as `source-feed.dwxml`. Against the current Java package, maximum absolute differences are 3.55e-15 kg/mol in molecular weight, 4.55e-12 K in critical temperature, 3.73e-9 Pa in critical pressure and 3.33e-15 in acentric factor. The 19-by-19 hydrocarbon interaction matrix agrees exactly. These are serialization-level differences.

The native thermodynamic provider is DWSIM's **PengRobinson1978PropertyPackage**. Matching constants and interactions does not establish complete thermodynamic equivalence: V3 uses its own caloric implementation and separate water/steam treatment. See [TJL19 property provenance](../../research/crude-regrouping/notes/tjl19-property-provenance.md). No property values were adjusted to force column convergence.

## What happened

| Attempt | Outcome |
| --- | --- |
| Full case, total subcooled condenser, automatic initialization | Accepted configuration; stopped after more than three minutes without a returned solution |
| Only K-values/enthalpy from CAPE-OPEN | Same prolonged initialization; no converged result |
| Property-call instrumentation; optional additional stream properties disabled | Repeated native pressure/vapor-fraction flashes while generating initial compositions; millions of fugacity evaluations |
| Native immiscible flash algorithm selected | Did not remove the initialization delay within the trial window |
| Partial condenser with organic-liquid split and water draw | No returned solution within the trial window; this was an alternate boundary trial, not the final selected V3 branch |
| Accepted V3 profile supplied as text “old results” | Native wrapper reset initialization to Automatic; not a valid full warm-start test |
| V3 temperatures and flows supplied through explicit User initialization | Native input retained User mode, but ChemSep still generated composition estimates; no returned solution within the trial window |
| Low-steam continuation starting point, 0.001 mol/s steam | Initialization finished in 23.920 s; Newton then failed after 30 iterations, 40.935 s for the full solve call |

The low-steam attempt is a changed-input diagnostic, not a result for the requested case. Its reported `log(Err/Tol)` increased from 7.1630 at iteration 0 to 11.7984 at iteration 30, so it did not provide a converged state from which to increase steam.

To obtain an inspectable native report from the otherwise slow full-case initialization, one separate diagnostic stopped after 50 flash calls by throwing an explicitly labeled research exception. Its native report shows `Run level: Initialization` and `Generating initial composition profiles`, followed by a pressure/vapor-fraction flash failure caused by that artificial limit. **That exception is not evidence of physical infeasibility or a naturally exhausted ChemSep solve.**

ChemSep also warned about the heaviest fraction, TJL_PC13, because its supplied critical compressibility / critical-volume consistency checks fall outside the program's usual range. This is recorded as a property warning; it has not been isolated as the cause of the convergence difficulty.

The early external timeout scripts could not stop process trees from the sandbox with `taskkill`; those owned workers and their descendants were subsequently stopped explicitly. The runner now uses `Process.Kill(true)`. Reported timeout deadlines identify when a timeout was requested, not an exact completion time for those early trials. No research solver process was left running at completion.

## V3 comparison

A generated research copy of the calculator added only a snapshot hook at its existing accepted-result publication point. The solver equations and acceptance gates were unchanged. Its full current input passed and selected **LIQUID_ONLY** at the condenser:

| V3 product | Flow, mol/s | Temperature, K |
| --- | ---: | ---: |
| Organic distillate | 194.272218 | 332.15 |
| Side draw, tray 10 | 136.388889 | 415.94 |
| Side draw, tray 18 | 143.055556 | 489.38 |
| Side draw, tray 28 | 45.833333 | 549.35 |
| Bottoms | 218.149637 | 572.39 |
| Free water | 333.333333 | 332.15 |

This establishes that the current Java model solves its authored case; it does not establish that a different native water/caloric model must have the same solution. The warm-start adapter converts V3's total organic condensate into separate native reflux and distillate flow columns. Native restart availability was not established for the full composition seed, so no successful full-state warm-start transfer is claimed.

## Saved artifacts and reproduction

- `build/dwsim-research/chemsep-v3-configured/chemsep-configured.dwxml`: reviewable **unsolved** full-case DWSIM/ChemSep configuration, using the standard installed PR78 package rather than the diagnostic subclass.
- `build/dwsim-research/chemsep-v3-configured/input.sep`: native full-case input.
- `build/dwsim-research/chemsep-v3/input.json`: current source-derived input, component records, interactions and source-method hash.
- `build/dwsim-research/chemsep-v3/mapping-check.json`: property/axis comparison and source hashes.
- `build/dwsim-research/chemsep-v3/v3-outcome.json` and `v3-accepted-profile.json`: successful Java result and accepted state.
- `build/dwsim-research/chemsep-v3-bounded-diagnostic/native-report-1.txt`: evidence that the full case is in composition initialization.
- `build/dwsim-research/chemsep-v3-steam-start/native-report-1.txt`: completed low-steam initialization and failed Newton iteration history.

The configured folder has `configure_only: true` in its JSON, so rerunning it only saves a configuration. For a fresh solve, copy the folder to a new research directory, remove that flag, and run:

```powershell
.\tools\dwsim\Run-ChemSepV3Trial.ps1 -Directory build/dwsim-research/your-new-run -TimeoutSeconds 180
```

Preparing a case from current source uses `Export-V3ChemSepCase.ps1`, the copied native `source-feed.dwxml`, a `properties` invocation of `ChemSepV3Trial.exe`, then `Build-ChemSepV3Case.ps1`. Helpers require the same local Windows installations and native registration as the earlier petroleum trial. Generated outputs remain under `build/dwsim-research`; no production Java solver or installed simulator files were modified by this task.

For the meaning of native CAPE-OPEN property, derivative, flash and initialization options, see [ChemSep's CAPE-OPEN documentation](https://cocosimulator.org/index_help.php?page=ChemSep%2Fchemsep.htm). The results and failure locations above are supported by the local run artifacts, not inferred from that documentation.
