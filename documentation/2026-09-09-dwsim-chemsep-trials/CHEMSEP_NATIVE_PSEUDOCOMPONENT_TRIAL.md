# ChemSep direct reboiler-duty trial

Tested locally on 2026-09-10. **ChemSep converged with distillate flow and reboiler heat duty specified directly**, using the 30 native DWSIM petroleum pseudocomponents and DWSIM Peng–Robinson thermodynamics through CAPE-OPEN 1.1. There was no outer reflux adjustment and no imported converged stage seed.

The host is now **DWSIM 10.2.5.0**, with registered **ChemSep Lite 8.50** (`ChemSepUO.ChemSep_UnitOperation.1`, 64-bit COM). The earlier [DWSIM duty investigation](DWSIM_REBOILER_DUTY_INVESTIGATION.md) tested DWSIM 10.2.3.0. This trial does not establish whether those native-solver defects persist in 10.2.5.0.

## Case and result

The source is DWSIM's shipped `Petroleum Distillation.dwxml`, copied into the research output directory. Its native fractions are `PSE_3165_2` through `PSE_3165_31`; these represent a light petroleum sample, not a full crude assay. Their original DWSIM property records remain the thermodynamic source. Feed mole fractions were normalized because the shipped composition summed to 0.999998; the actual feed flow was preserved.

| Setting | Value |
| --- | --- |
| Components | 30 native petroleum pseudocomponents |
| Geometry | 12 stages including total condenser and partial reboiler |
| Feed | 500.001016078605 mol/s, 350 K, stage 8 |
| Pressure | 101325 Pa throughout |
| Liquid side draws | 40 mol/s at stage 4; 70 mol/s at stage 8 |
| Distillate specification | 290.01 mol/s |
| Requested reboiler heat input | 27,282,470.703293696 W |
| Applied reboiler heat input | 27,282,500 W |
| Initialization and solver | Automatic initialization; native ChemSep Newton solver |
| Native convergence | 4 iterations; accuracy setting 1e-7 |
| Measured solve call | 2.779 s on the repeat run; 2.895 s on the first successful run |

ChemSep uses one-based stage numbers; these correspond to DWSIM feed node 7 and side-draw nodes 3 and 7. ChemSep's CAPE preprocessing rounds the duty to six significant digits, introducing **29.2967 W** of input error (about 1.07 ppm). The result is a direct duty-specified solution at the applied value, not exact agreement with the original full-precision target.

| Product | Flow, mol/s | Temperature, K |
| --- | ---: | ---: |
| Distillate | 290.010000 | 344.657711 |
| Bottoms | 99.991016 | 364.618506 |
| Light side draw | 40.000000 | 349.120749 |
| Heavy side draw | 70.000000 | 353.117159 |

The condenser removes 29,755,430.779568 W. Independent checks on the exported port results give:

- Overall molar closure: 1.02e-12 mol/s.
- Overall mass closure: 6.26e-6 kg/s, about 0.153 ppm of feed mass flow.
- Overall energy closure: -1.519 W, including signed condenser and reboiler heat.
- Maximum individual component closure: 1.239e-4 mol/s.
- Maximum stage composition normalization error: 3.70e-11 across both 12-by-30 composition tables.

The validation script checks native and host success, complete stage/composition exports, nonnegative finite compositions, overall mass closure below 1 ppm, energy closure within 10 W and composition normalization within 1e-6. It reports component closure separately. These checks are not the V3 physical audit.

## How automation works

The worker loads the source's compound records into a fresh DWSIM flowsheet and supplies a DWSIM Peng–Robinson property package to a ChemSep CAPE-OPEN unit. The native calculation report confirms CAPE-OPEN 1.1 thermodynamics: enthalpy derivatives are supplied by the property provider and equilibrium derivatives are differenced. Thermodynamic model labels retained in the SEP template are superseded by the active external property provider.

Headless DWSIM `AddObject(CapeOpenUO)` opens an interactive selector. The research worker instead constructs the wrapper, selects the registered ChemSep unit and loads a prepared native persistence state. The state bridge uses the observed `CSUO` envelope and embedded SEP text from a shipped DWSIM CAPE-OPEN sample, followed by a native load/save validation. This is a version-specific research adapter, not a promised stable persistence API.

Two integration details were necessary: externally supplied components use zero library offsets, and pseudocomponents with no CAS number must match the DWSIM adapter's component-name fallback in the CAPE-OPEN identifier field. These fallback strings are not real CAS numbers. After loading the state, the wrapper refreshes its ports before connecting the feed and four products.

## Reproduction and files

Requires the installed 64-bit ChemSep registration, DWSIM 10.2.5.0, the Windows .NET Framework compiler and PowerShell 7. The scripts currently assume ChemSep is installed under `C:\Program Files\ChemSepL8v50`. Run from the repository root after generating `build/dwsim-research/petroleum-native.dwxml` and `petroleum-native-profile.json` using the [native trial procedure](DWSIM_NATIVE_PSEUDOCOMPONENT_TRIAL.md).

```powershell
.\tools\dwsim\Run-ChemSepTrial.ps1
.\tools\dwsim\Validate-ChemSepTrial.ps1
```

The runner compiles the two isolated C# helpers, extracts the shipped template, builds the petroleum case, validates native persistence and executes the solve worker with a 60-second deadline. It records success and errors explicitly. A repeat run replaces the generated artifacts in its output directory; pass `-OutputDirectory` to retain separate runs.

Main artifacts under `build/dwsim-research/chemsep-direct-duty/`:

- `chemsep-configured.dwxml`: saved DWSIM flowsheet after the successful ChemSep calculation, despite the filename.
- `chemsep-calculated.sep`: native ChemSep input and converged results.
- `result.json`: host status, timing and full-precision product port data.
- `validation.json`: independent checks and duty rounding discrepancy.
- `stage-profiles.json`: parsed temperatures, raw flow columns and both composition matrices.
- `native-report-0.txt` through `native-report-5.txt`: native diagnostic and calculation reports.
- `petroleum.sep` and `prepared-state.bin`: prepared input before calculation.

## Implications for data generation

This demonstrates that ChemSep can solve this native pseudocomponent case with the requested type of operating specifications. It supports further offline data-generation trials, but does not measure robustness across a parameter sweep or runtime performance inside Minecraft.

The result is **not yet qualified for V3 training**. Native stage temperatures are printed to only 0.01 K in the exported profile table, while product ports retain more precision. At the total condenser, ChemSep's raw “Vapour Flow” column contains the liquid distillate, so a direct phase-flow mapping would be wrong. Feed-stage flow conventions, side draws, thermodynamic correlations and condenser boundaries still require explicit mapping and the unchanged V3 correction and physical audit. No neural model was trained and no production solver code was changed by this trial.

For background on the external property-provider mode, see [COCO's ChemSep CAPE-OPEN documentation](https://cocosimulator.org/index_help.php?page=ChemSep%2Fchemsep.htm). The native command-line options are documented in the [ChemSep book](https://chemsep.org/book/docs/book2.pdf). The measured results above come from the local run artifacts.
