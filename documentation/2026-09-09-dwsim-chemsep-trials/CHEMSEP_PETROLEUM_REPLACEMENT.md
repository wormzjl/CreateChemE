# ChemSep replacement of the shipped petroleum example

Tested 2026-09-10 with DWSIM 10.2.5.0 and ChemSep Lite 8.50.

**The replacement converges, including after saving and reloading in a fresh process, when its reboiler duty is specified inside ChemSep.** Connecting the existing DWSIM energy streams directly exposes a separate factor-of-1,000 CAPE-OPEN energy-unit defect and fails.

## Saved flowsheet

Open [`chemsep-configured.dwxml`](../../build/dwsim-research/petroleum-chemsep-replacement/internal-duty/chemsep-configured.dwxml) in DWSIM with ChemSep installed. This is a copy of `C:/Program Files/DWSIM/samples/Petroleum Distillation.dwxml` with the native column replaced in place. The installed sample was not modified.

All eight simulation object IDs were retained: the column, feed, four products, and two duty streams. The original 30 pseudocomponents and Peng–Robinson package were retained. The column has 12 stages, a total condenser, feed on stage 8, and liquid side draws of 40 mol/s on stage 4 and 70 mol/s on stage 8, at 101325 Pa.

The saved file labels the two duty streams **reference** and disconnects them. Their values describe the saved calculation; they do **not** update on subsequent GUI solves. Open the ChemSep reports for current duties. The flowsheet text and object annotations explain this limitation.

## Necessary specification changes

The sample specifies distillate at 290.01 mol/s and bottoms at 100 mol/s. With the feed and side draws fixed, these are redundant and slightly inconsistent. A fresh native solve actually returns distillate at 290.001016 mol/s and a reboiler heat input of 27,282,470.703294 W.

The replacement uses two independent specifications: distillate **290.01 mol/s** and reboiler duty **27,282,500 W**. The latter is ChemSep's preprocessing rounding of the freshly calculated native duty, a difference of 29.30 W (1.07 ppm). Bottoms becomes 99.991016 mol/s by material balance.

The original rounded feed fractions sum to 0.999998. They were normalized while preserving feed temperature, pressure, and total flow of 500.001016 mol/s. Thus this is a closely matched comparison, not a claim of identical specifications or results.

## Retest results

| Check | First replacement solve | Fresh-process reload and solve |
|---|---:|---:|
| DWSIM solved / ChemSep converged | Yes / Yes | Yes / Yes |
| ChemSep iterations | 4 | 1 (saved initialization) |
| Solve time | 2.728 s | 2.644 s |
| Absolute total molar imbalance | 1.03e-12 mol/s | 1.03e-12 mol/s |
| Absolute mass imbalance | 6.265 mg/s | 6.265 mg/s |
| Absolute energy imbalance | 1.519 W | 1.670 W |
| Maximum component imbalance | 1.24e-4 mol/s | 1.24e-4 mol/s |

The mass imbalance is 0.153 ppm of the feed. The component residual is larger than floating-point noise and remains reported explicitly; these results have not been qualified as V3 training labels. The reload test clears calculated flags before invoking the flowsheet solver and obtains a new native ChemSep iteration report.

| Product | Native flow (mol/s) | ChemSep flow (mol/s) | Native temperature (K) | ChemSep temperature (K) |
|---|---:|---:|---:|---:|
| Light Product | 290.001016 | 290.010000 | 344.7016 | 344.6577 |
| Light Intermediate product | 40 | 40 | 349.1867 | 349.1207 |
| Intermediate Product | 70 | 70 | 352.9003 | 353.1172 |
| "Heavy" Product | 100 | 99.991016 | 364.5597 | 364.6185 |

The first replacement solve gives condenser heat removal of 29.755431 MW. All property calculations use the installed, unmodified DWSIM package, without the experimental wet-flash correction.

## Connected energy-port failure

With ChemSep energy ports enabled and the original duty streams attached, DWSIM's reboiler stream was set to **27282.5 kW**, but ChemSep's own specification report recorded **27282.5 J/s**. That run failed after 100 iterations in 14.605 s.

Inspection of the installed `EnergyStream.CreateParamCol` IL confirms that it initializes CAPE-OPEN's `work` parameter with the raw `EnergyFlow` value and labels it `J/s`, without multiplying DWSIM's kW value by 1000. No installed DLL was patched. The working file avoids this interface by retaining the duty inside ChemSep; it is not a fully connected replacement of the energy ports.

## Evidence and reproduction

All outputs are under [`build/dwsim-research/petroleum-chemsep-replacement`](../../build/dwsim-research/petroleum-chemsep-replacement):

- `native-baseline.json`: fresh native sample calculation.
- `result.json`, `native-report-1.txt`, `native-report-4.txt`: failed connected-energy run and the actual received duty.
- `energy-stream-parameters.il`: installed energy-interface implementation.
- `internal-duty/`: successful replacement, native reports, stage profiles, and independent balance validation.
- `reload/`: fresh-process reload results and validation.
- `preservation-audit.json`: object identities, component property checks, and verification that one CAPE-OPEN column replaced the native column.

[`Run-ReplacedPetroleum.ps1`](../../tools/dwsim/Run-ReplacedPetroleum.ps1) compiles and runs the replacement and reload checks. Its default state file is the automatically initialized 12-stage state generated by the existing [`Run-ChemSepTrial.ps1`](../../tools/dwsim/Run-ChemSepTrial.ps1) workflow. The replacement itself loads the original sample rather than creating a new flowsheet.

This successful dry petroleum example does not resolve the separate wet TJL19 flash failures documented in [`CHEMSEP_TJL19_ROOT_CAUSE.md`](CHEMSEP_TJL19_ROOT_CAUSE.md).
