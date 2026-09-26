#!/usr/bin/env bash
# javac compile of the WP6a sources (the worktree's phase package and TangentPlaneStability), the phase tests' fixture
# PhaseTestSupport and the given extra sources, against build/classes/java/main. Other agents' files are taken at the
# committed revision $SHA (default HEAD): the kernel, PairInteractions and the whole science/fluid/thermo package, so
# their uncommitted edits (the WP4 direct liquid path) cannot break or change this check; the network flash the scans
# compare with is therefore the committed one. PIN=0 compiles against build/classes as they are (the last Gradle compile
# of the whole tree, other agents' uncommitted work included), for running the tracked tests locally. Prints the
# output directory on the last line.
set -euo pipefail
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
GSON="$G/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
JUNIT=$(ls $G/org.junit.jupiter/junit-jupiter-api/*/*/junit-jupiter-api-[0-9.]*.jar | head -1)
OPENTEST=$(ls $G/org.opentest4j/opentest4j/*/*/opentest4j-[0-9.]*.jar | head -1)
OUT="${OUT:-$(mktemp -d)}"
SRC="$(mktemp -d)"
SHA="${SHA:-HEAD}"
if [ "${PIN:-1}" = "1" ]; then
git archive "$SHA" src/main/java/com/wormzjl/createcheme/science/thermo/PengRobinsonKernel.java \
  src/main/java/com/wormzjl/createcheme/science/fluid/thermo | tar -x -C "$SRC"
git archive "$SHA" src/main/java/com/wormzjl/createcheme/science/thermo/PairInteractions.java 2>/dev/null | tar -x -C "$SRC" || true
fi
mkdir -p "$SRC/src/main/java"
CP="$(cygpath -w "$OUT");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/classes/java/test");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$GSON");$(cygpath -w "$JUNIT");$(cygpath -w "$OPENTEST")"
T=src/test/java/com/wormzjl/createcheme/science/thermo
"$JDK/javac" -nowarn -encoding UTF-8 -d "$(cygpath -w "$OUT")" -cp "$CP" \
  $(find "$SRC/src/main/java" -name '*.java') \
  src/main/java/com/wormzjl/createcheme/science/thermo/IdealGasFunction.java \
  src/main/java/com/wormzjl/createcheme/science/thermo/TangentPlaneStability.java \
  src/main/java/com/wormzjl/createcheme/science/thermo/phase/*.java \
  "$T/phase/PhaseTestSupport.java" "$@" 1>&2
echo "$OUT"
