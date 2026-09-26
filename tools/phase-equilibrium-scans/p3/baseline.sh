#!/usr/bin/env bash
# Compiles the P2 sources of commit 5100233 (phase package, TangentPlaneStability, TranslatedPengRobinson, kernel,
# PhaseTestSupport) from git into a temporary directory and runs BaselineFailures: the P2 NOT_CONVERGED list.
set -euo pipefail
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
GSON="$G/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
SHA="${SHA:-5100233}"
SRC="$(mktemp -d)"; OUT="$(mktemp -d)"
git archive "$SHA" src/main/java/com/wormzjl/createcheme/science/thermo src/main/java/com/wormzjl/createcheme/science/fluid/thermo/TranslatedPengRobinson.java src/test/java/com/wormzjl/createcheme/science/thermo/phase/PhaseTestSupport.java | tar -x -C "$SRC"
CP="$(cygpath -w "$OUT");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$GSON")"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$JDK/javac" -nowarn -encoding UTF-8 -d "$(cygpath -w "$OUT")" -cp "$CP" \
  $(find "$SRC/src/main/java/com/wormzjl/createcheme/science/thermo" -name '*.java') \
  "$SRC/src/main/java/com/wormzjl/createcheme/science/fluid/thermo/TranslatedPengRobinson.java" \
  "$SRC/src/test/java/com/wormzjl/createcheme/science/thermo/phase/PhaseTestSupport.java" "$HERE/${MAIN:-BaselineFailures}.java"
"$JDK/java" -cp "$CP" com.wormzjl.createcheme.science.thermo.phase.${MAIN:-BaselineFailures}
