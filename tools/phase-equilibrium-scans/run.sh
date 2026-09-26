#!/usr/bin/env bash
# Robustness scans of FluidTpEquilibrium (P2, batch 2026-09-24-coolprop-low-temperature), outside Gradle.
# Run from the root of the worktree to scan (its sources and build/classes/java/main, build/resources/main of any
# earlier Gradle compile); the script itself may live elsewhere, e.g. the main checkout's tools/ folder.
set -euo pipefail
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
GSON="$G/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
OUT="$(mktemp -d)"
CP="$(cygpath -w "$OUT");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$GSON")"
T=src/test/java/com/wormzjl/createcheme/science/thermo/phase
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$JDK/javac" -nowarn -encoding UTF-8 -d "$(cygpath -w "$OUT")" -cp "$CP" \
  src/main/java/com/wormzjl/createcheme/science/fluid/thermo/TranslatedPengRobinson.java \
  src/main/java/com/wormzjl/createcheme/science/thermo/IdealGasFunction.java \
  src/main/java/com/wormzjl/createcheme/science/thermo/phase/*.java \
  "$T/PhaseTestSupport.java" "$HERE/FlashScan.java"
"$JDK/java" -cp "$CP" com.wormzjl.createcheme.science.thermo.phase.FlashScan
