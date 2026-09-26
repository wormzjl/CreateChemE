# tangent-plane-stability-scans

Purpose: the verification scans behind `documentation/2026-09-24-coolprop-low-temperature/P1_STABILITY_TEST.md`, run
against `TangentPlaneStability` (`src/main/java/com/wormzjl/createcheme/science/thermo/`) outside the Gradle suite. They
are larger than the unit tests in `TangentPlaneStabilityTest` and were never part of the tracked code.

Batch: `2026-09-24-coolprop-low-temperature` (unified multiphase thermo plan, P1 item 3, gate G1).

## Run

From the worktree root (Git Bash), about 45 s; JDK 21 at `/c/Program Files/Java/jdk-21.0.11/bin` unless `JDK` is set:

    bash tools/tangent-plane-stability-scans/run.sh

It compiles the kernel and the stability test from the worktree's sources into a temporary directory. `CrudeScan`
additionally needs `build/classes/java/main` and `build/resources/main` (any earlier Gradle compile of the worktree)
and gson 2.10.1 from the Gradle cache. No Gradle invocation.

## What each file does

- `Scratch.java`: helpers (an open-domain kernel from critical constants; `brute`, the binary tangent-plane minimum
  over both roots on a grid of `-Dgrid` interior points plus logarithmic ends to 1e-10, refined by golden section) and
  the 60-state methane/nitrogen table (110 and 120 K, 0.5 to 3 MPa, x_N2 0.1 to 0.9), plus pure nitrogen at 77.355 K.
- `Scan.java`: 25,000 methane/nitrogen states, 95 to 190 K by 5 K, 0.1 to 5 MPa by 0.1 MPa, x_N2 0.02 to 0.98 by 0.04,
  verdict against the brute-force minimum (unstable when below -1e-8).
- `ScanCrit.java`: 105,600 states around the mixture critical locus, 130 to 185 K by 5 K, 2.5 to 6 MPa by 0.02 MPa,
  x_N2 0.01 to 0.99 by 0.02; prints any UNRESOLVED state.
- `CrudeScan.java`: the 20-component network package (Tia Juana light assay + 5 mol% nitrogen, and a light gas with
  1e-4 of each cut), 300 to 900 K by 20 K, 0.1 to 2 MPa by 0.1 MPa, verdict against the phase count of
  `FluidThermodynamics.flashTP` with no water.
- `Four.java`: methane/ethane/propane/nitrogen 0.4/0.15/0.15/0.3 at eight states, used to pick the cost-table states.

Constants are the PR78 records of the bundled network package (`createcheme:tjl20_methane_nitrogen`); all methane and
nitrogen interactions in it are zero.
