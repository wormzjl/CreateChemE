# phase-equilibrium-scans

Purpose: the robustness scans behind section 5.2 of
`documentation/2026-09-24-coolprop-low-temperature/P2_PHASE_CONTRACTS.md`, run against `FluidTpEquilibrium`
(`src/main/java/com/wormzjl/createcheme/science/thermo/phase/`) outside the Gradle suite. They are larger than the unit
tests in `FluidTpEquilibriumTest` and were never part of the tracked code.

Batch: `2026-09-24-coolprop-low-temperature` (unified multiphase thermo plan, stage P2, phase and equilibrium side).

## Run

From the root of the worktree to scan (Git Bash), about 2 s including the compile; JDK 21 at
`/c/Program Files/Java/jdk-21.0.11/bin` unless `JDK` is set:

    bash tools/phase-equilibrium-scans/run.sh
    # or, from a worktree without this folder:
    bash D:/Minecraft/Modding/1.21/CreateChemE/tools/phase-equilibrium-scans/run.sh

It compiles the worktree's phase package, `TranslatedPengRobinson`, `IdealGasFunction`, the test fixtures
`PhaseTestSupport` and `FlashScan.java` (found next to `run.sh`) into a temporary directory against the worktree's
`build/classes/java/main` and `build/resources/main` (any earlier Gradle compile) and gson 2.10.1 from the Gradle
cache. No Gradle invocation. It needs the P2 sources (commit of the batch that adds `science.thermo.phase`, see the
batch's `P2_PHASE_CONTRACTS.md`).

## What it does

`FlashScan.java` (package `science.thermo.phase`, to reuse `PhaseTestSupport`):

- binary field: 25,000 methane/nitrogen states, 95 to 190 K by 5 K, 0.1 to 5 MPa by 0.1 MPa, x_N2 0.02 to 0.98 by
  0.04, on the open research contract;
- near-critical: 105,600 states, 130 to 185 K by 5 K, 2.5 to 6 MPa by 0.02 MPa, x_N2 0.01 to 0.99 by 0.02;
- network: 1,240 states of the network contract (Tia Juana light assay + 5 mol% N2, and a light gas of 10 % each C1 to
  nC5, 30 % N2 and 1e-4 of each cut), 300 to 900 K by 20 K, 0.1 to 2 MPa by 0.1 MPa, phase count against
  `FluidThermodynamics.flashTP` with no water.

It prints per scan the outcome counts, the two-phase count, flash iterations, kernel calls, the worst conservation
defect and the worst fugacity residual (with its state), the not-converged reasons, the first three states of each
not-converged outcome and, for the binary scans, the temperature, pressure and composition box of the not-converged
states.

## Result of record (2026-09-24, sources of commit `cbaa791`)

- binary field: 25,000 states, 2,537 two-phase, 10 `NOT_CONVERGED` (all substitution budget, 140 to 175 K,
  4.2 to 5.0 MPa); worst conservation defect 1.98e-16, worst fugacity residual 1.27e-10.
- near-critical: 105,600 states, 8,432 two-phase, 112 `NOT_CONVERGED` (93 budget, 17 collapse onto the feed, 1 lost
  split, 1 split outside 0 < beta < 1; 130 to 185 K, 3.58 to 5.02 MPa); worst defect 1.98e-16, fugacity 1.53e-10.
- network: 1,240 states, 1,240/1,240 phase counts agree with `flashTP`, 1,142 two-phase, 0 not converged; worst defect
  1.89e-16, fugacity 2.25e-11.

Before merge this folder is copied to the main checkout's `tools/phase-equilibrium-scans/` and indexed in
`tools/INDEX.md`; nothing of it was ever tracked, so there is no removing commit.

## P3 WP6a additions (`p3/`, 2026-09-24)

Batch `2026-09-24-coolprop-low-temperature`, P3 work package WP6a (engine TP completion); results in
`documentation/2026-09-24-coolprop-low-temperature/P3_EQUILIBRIUM_ENGINE.md` sections 3 and 7.

- `p3/compile.sh [extra.java...]`: javac of the worktree's phase package, `TangentPlaneStability` and `PhaseTestSupport`
  with other agents' files (kernel, `PairInteractions`, all of `science/fluid/thermo`) taken from the committed `$SHA`
  (default `HEAD`), so the scans compare with the committed network flash; `PIN=0` uses `build/classes` as they are.
- `p3/run.sh <Class> <sources.java...> [args]`: compile and run a class of package `science.thermo.phase`.
- `p3/P3Scan.java` (`binary`, `wet`, `wet translated`, `all`): the P2 binary fields with band, Newton and independent
  product-stability statistics and the 122 fixture states; the wet network field (6,200 states) against `flashTP`.
- `p3/BandCalibration.java`: tie lines of the former failures, stationary distances and `lambda_min(B)` of their
  single-phase neighbours, coverage per threshold (`band-calibration.txt`).
- `p3/StabilityDigest.java` + `p3/baseline.sh` (`SHA=... MAIN=StabilityDigest`): digest of every one-pressure stability
  result over 131,251 states, committed against worktree implementation (bitwise identity).
- `p3/BaselineFailures.java` via `p3/baseline.sh`: the P2 not-converged list (`baseline-failures.txt`, the fixture).
- `p3/WetDebug.java`, `p3/WetState.java`, `p3/PipCheck.java`: single-state probes; `p3/RunTests.java`: reflective JUnit
  runner for javac-only checks of the tracked tests.
- Result files of record: `final-flashscan.txt`, `final-p3scan.txt`, `final-wet-translated.txt`, `band-calibration.txt`.

    bash tools/phase-equilibrium-scans/p3/run.sh P3Scan tools/phase-equilibrium-scans/p3/P3Scan.java all

## P3 WP6b additions (`wp6b/`, 2026-09-25)

Batch `2026-09-24-coolprop-low-temperature`, P3 work package WP6b (PH and UV); results in
`documentation/2026-09-24-coolprop-low-temperature/P3_EQUILIBRIUM_ENGINE.md` section "WP6b: PH and UV".

- `wp6b/compile.sh [extra.java...]`: javac of the worktree's phase package (WP6b's files), `TangentPlaneStability` and
  `PhaseTestSupport` against `build/classes` as they are, with the phase-package files the parallel WP5 agent owned
  (`CubicPhaseEvaluator`, `PhaseContract`) taken at `$SHA` (default `HEAD`), so their uncommitted edits cannot break it.
- `wp6b/run.sh <Class> <sources.java...> [args]`: compile and run a class of package `science.thermo.phase`; the JUnit
  jars are picked by exact name (the `p3/run.sh` glob can pick a `-sources` jar, which breaks assertion messages). Run
  the tracked tests with `p3/RunTests.java`:

      T=src/test/java/com/wormzjl/createcheme/science/thermo/phase
      bash tools/phase-equilibrium-scans/wp6b/run.sh RunTests tools/phase-equilibrium-scans/p3/RunTests.java \
          $T/FluidTpEquilibriumPhUvTest.java com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibriumPhUvTest

- `wp6b/UvDebug.java [T P x_N2]`: PH and UV TP-call counts and round-trip errors on the 60 P2 binary states (or one).
- `wp6b/PhDebug.java T P x_N2`: one PH round trip on the binary. Both were used with temporary trace prints in
  `FluidTpEquilibrium` (removed before the commit).
