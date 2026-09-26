#!/usr/bin/env bash
# Compiles the kernel and TangentPlaneStability from the worktree's sources and runs the scans against them.
# Needs build/classes/java/main and build/resources/main of the worktree (any earlier Gradle compile) for the
# catalog-backed CrudeScan; the binary scans need nothing but the two sources. Run from the worktree root.
set -euo pipefail
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
WT="$(cygpath -m "$(pwd)")"
HERE="$WT/tools/tangent-plane-stability-scans"
OUT="$(cygpath -m "$(mktemp -d)")"
GSON="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1" -name 'gson-2.10.1.jar' | head -1)"
GSON="$(cygpath -m "$GSON")"
SRC="$WT/src/main/java/com/wormzjl/createcheme/science/thermo"
CP="$OUT;$WT/build/classes/java/main;$WT/build/resources/main;$GSON"
"$JDK/javac" -J-Duser.language=en -cp "$CP" -d "$OUT" "$SRC/PengRobinsonKernel.java" "$SRC/TangentPlaneStability.java" "$HERE"/*.java
echo "== Scratch: 60 methane/nitrogen states, 110/120 K, 0.5-3 MPa, grid 4000"; "$JDK/java" -cp "$CP" Scratch | tail -3
echo "== Scan: 25,000 states, 95-190 K, 0.1-5 MPa, grid 1500"; "$JDK/java" -Dgrid=1500 -cp "$CP" Scan | tail -1
echo "== ScanCrit: 105,600 states, 130-185 K, 2.5-6 MPa, grid 1500"; "$JDK/java" -Dgrid=1500 -cp "$CP" ScanCrit | grep -v '^T=' | tail -1
echo "== CrudeScan: 1,240 20-component states against FluidThermodynamics.flashTP"; "$JDK/java" -cp "$CP" CrudeScan | tail -1
echo "== Four: 4-component candidate states"; "$JDK/java" -cp "$CP" Four
