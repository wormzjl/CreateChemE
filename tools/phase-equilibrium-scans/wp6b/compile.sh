#!/usr/bin/env bash
# WP6b javac compile: the worktree's phase package (WP6b's files) and TangentPlaneStability, with the phase package's
# files owned by the parallel WP5 agent (CubicPhaseEvaluator, PhaseContract) taken at the committed revision $SHA
# (default HEAD), against build/classes/java/main as they are. Extra sources are the arguments. Prints the output
# directory on the last line.
set -euo pipefail
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
pick() { find "$G/$1" -name "$2" | head -1; }
GSON=$(pick com.google.code.gson/gson/2.10.1 'gson-2.10.1.jar')
JUNIT=$(pick org.junit.jupiter/junit-jupiter-api 'junit-jupiter-api-5.11.4.jar')
OPENTEST=$(pick org.opentest4j/opentest4j 'opentest4j-1.3.0.jar')
OUT="${OUT:-$(mktemp -d)}"
SRC="$(mktemp -d)"
SHA="${SHA:-HEAD}"
P=src/main/java/com/wormzjl/createcheme/science/thermo/phase
git archive "$SHA" $P/CubicPhaseEvaluator.java $P/PhaseContract.java | tar -x -C "$SRC"
CP="$(cygpath -w "$OUT");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/classes/java/test");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$GSON");$(cygpath -w "$JUNIT");$(cygpath -w "$OPENTEST")"
MINE=$(ls $P/*.java | grep -v -e CubicPhaseEvaluator.java -e PhaseContract.java)
"$JDK/javac" -nowarn -encoding UTF-8 -d "$(cygpath -w "$OUT")" -cp "$CP" \
  "$SRC/$P/CubicPhaseEvaluator.java" "$SRC/$P/PhaseContract.java" $MINE \
  src/main/java/com/wormzjl/createcheme/science/thermo/TangentPlaneStability.java \
  src/test/java/com/wormzjl/createcheme/science/thermo/phase/PhaseTestSupport.java "$@" 1>&2
echo "$OUT"
