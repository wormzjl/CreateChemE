# TJL19: progressive steam and PA tests

Tested 2026-09-10 with installed DWSIM 10.2.5.0, PR78 thermodynamics, and ChemSep Lite 8.50.

**The first failure occurs with zero stripping steam and zero PA cooling.** Thirteen trials were run. None converged, and all returned native column-iteration reports before the 90-second worker deadline. Therefore there is no accepted starting solution from which a steam/PA breaking threshold can be inferred.

## Starting point and method

The exported `literatureCduInput()` was checked against the current source method and matches. The starting point retains the project's 19 hydrocarbon components and native property records, 40 trays plus condenser/reboiler, feed at tray 37, 638.15 K, 737.6996333 mol/s, 250000 Pa, reflux ratio 4.17, overhead temperature 332.15 K, three original liquid side draws, and zero reboiler duty.

Steam feeds and all PA heat effects were removed for the first trial. The unused water component was retained at zero concentration, with no water draw. A separate control removed Water from the component list entirely.

Since the starting point failed, subsequent cases were **independent automatic-initialization tests**, not warm-start continuation from a converged predecessor. No failed profile was treated as an accepted seed or training label. The water draw was restored when adding steam.

Steam and PA were tested separately:

- Steam: 0%, 0.1%, 1%, and 10% of the design 333.333333 mol/s rate, with all PA cooling disabled.
- PA: zero steam; PA1 at 25%, then full PA1, then adding full PA2, then full PA3.

As in the Java case, PA represents prescribed distributed cooling, not a physical liquid recycle. Native heat loads were checked against the input duties. CAPE energy ports remained disabled, so the energy-port unit error found in the petroleum replacement is not involved here. No experimental flash corrections or artificial diagnostic failure guards were enabled.

## Results

Times are the returned DWSIM solve-call times. “Iteration” is the last printed ChemSep iteration, not a count of successful steps. Cooling is positive heat removal.

| Case | Steam (mol/s) | Cooling (MW) | Solve time (s) | Last iteration | Outcome |
|---|---:|---:|---:|---:|---|
| Initial dry baseline, 20 components | 0 | 0 | 39.66 | 30 | Iteration limit |
| Dry baseline, Water removed | 0 | 0 | 33.54 | 21 | PR78 compressibility error |
| Dry, Water and side draws removed | 0 | 0 | 12.55 | 1 | Invalid/missing fugacity values |
| Dry, Water removed, saturated condenser | 0 | 0 | 33.99 | 31 | PR78 compressibility error |
| Steam at 0.1%, no PA | 0.333333 | 0 | 43.55 | 53 | PR78 compressibility error |
| Steam at 1%, no PA | 3.333333 | 0 | 38.51 | 33 | PR78 compressibility error |
| Steam at 10%, no PA | 33.333333 | 0 | 46.78 | 29 | PR78 compressibility error |
| Dry, Water removed, 5 MW reboiler control | 0 | 0 | 40.87 | 51 | PR78 compressibility error |
| Dry, PA1 at 25% | 0 | 3.21 | 35.07 | 31 | PR78 compressibility error |
| Dry, full PA1 | 0 | 12.84 | 45.87 | 60 | Iteration limit |
| Dry, full PA1 + PA2 | 0 | 30.73 | 52.50 | 60 | Iteration limit |
| Dry, all three PAs | 0 | 41.93 | 30.63 | 10 | PR78 compressibility error |
| Dry baseline repeated with 60-iteration allowance | 0 | 0 | 39.94 | 34 | PR78 compressibility error |

The saturated-condenser and positive-reboiler cases are explicitly changed-specification controls. They were intended to test whether condenser subcooling or a zero-duty bottom section alone prevented a usable start; neither control converged. The first two baseline trials allowed 30 iterations; subsequent trials allowed 60. The final repeat confirms that the initial baseline failure was not simply the 30-iteration cutoff.

## Interpretation

All trials completed automatic initialization and entered the column's Newton iteration loop. Their reported error increased overall before termination. For example, the initial dry baseline's `log(Err/Tol)` grew from 7.1656 to 9.9106; full PA1 + PA2 grew from 7.1631 to 12.3891.

The common terminal error is DWSIM failing to calculate a PR78 compressibility factor for a trial state requested during ChemSep's iterations. This does **not** establish whether the originating defect lies in initialization, derivative/property exchange, the column step, or physical feasibility. It does establish that stripping steam and PA are not required to reproduce the failure.

The previously demonstrated wet-flash defects remain real, but they do not explain the entire convergence problem: the dry, 19-component case fails too. The next investigation should therefore establish a converged dry TJL19 core and audit the states passed between ChemSep and DWSIM before trying to locate a steam/PA continuation boundary.

## Artifacts and reproduction

The complete machine-readable matrix is [`summary.json`](../../build/dwsim-research/chemsep-progressive/summary.json). The input audit is [`input-audit.json`](../../build/dwsim-research/chemsep-progressive/input-audit.json).

Each case directory under `build/dwsim-research/chemsep-progressive` contains its input JSON, native SEP, copied source-property flowsheet, native property export, result JSON, six native reports, and saved **unsolved** DWSIM configuration. In particular:

- [`12-dry-no-pa-60iter/native-report-1.txt`](../../build/dwsim-research/chemsep-progressive/12-dry-no-pa-60iter/native-report-1.txt): final zero-steam/zero-PA baseline report.
- [`01-dry-19-no-pa/native-report-1.txt`](../../build/dwsim-research/chemsep-progressive/01-dry-19-no-pa/native-report-1.txt): failure with Water completely absent.
- [`11-dry-all-pa/native-report-1.txt`](../../build/dwsim-research/chemsep-progressive/11-dry-all-pa/native-report-1.txt): full PA cooling without steam.

Prepare a new case with `tools/dwsim/New-ChemSepProgressiveCase.ps1`, run it with `tools/dwsim/Run-ChemSepV3Trial.ps1`, then regenerate the matrix with `tools/dwsim/Summarize-ChemSepProgression.ps1`. For example:

```powershell
.\tools\dwsim\New-ChemSepProgressiveCase.ps1 -Directory build/dwsim-research/chemsep-progressive/retest -SteamFraction 0.01 -PaFractions @(0,0,0)
.\tools\dwsim\Run-ChemSepV3Trial.ps1 -Directory build/dwsim-research/chemsep-progressive/retest -TimeoutSeconds 90
.\tools\dwsim\Summarize-ChemSepProgression.ps1
```

The preparation helper preserves the source operating conditions except for the explicitly selected steam, PA, side-draw, component-list, and condenser controls. The 5 MW reboiler trial additionally changes that one duty in both its JSON and native SEP. No production Java solver or installed simulator files were modified.
