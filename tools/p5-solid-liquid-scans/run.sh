#!/usr/bin/env bash
# P5 solid-liquid equilibrium (batch 2026-09-24-coolprop-low-temperature) without Gradle: javac of the whole
# science.thermo.phase package, the crystal material classes and (optionally) the fluid-network thermo classes of the
# worktree, plus the stage's gate tests and this folder's probes, against the last Gradle compile (build/classes/java/main
# and test, build/resources); the worktree's src/main and src/test resources come first on the classpath. Then the gate
# tests through the reflective runner (RunTests.java), or a probe's main.
# Usage, from Git Bash in the worktree root:
#   bash tools/p5-solid-liquid-scans/run.sh compile
#   bash tools/p5-solid-liquid-scans/run.sh tests [TestClass ...]
#   bash tools/p5-solid-liquid-scans/run.sh main  <class> [args]
# Environment: TESTS=no compiles no test sources; SETS=network adds science.fluid.thermo, .state and .transport and the
# test sources listed in NETWORK_TESTS.
set -euo pipefail
TOOL="$(cd "$(dirname "$0")" && pwd)"
cd "$(git rev-parse --show-toplevel)"
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
GSON="$G/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
JUNIT=$(find $G/org.junit.jupiter/junit-jupiter-api -name "junit-jupiter-api-5.11.4.jar" | head -1)
OPENTEST=$(find $G/org.opentest4j/opentest4j -name "opentest4j-1.3.0.jar" | head -1)
COMMONS=$(find $G/org.junit.platform/junit-platform-commons -name "junit-platform-commons-1.11.4.jar" | head -1)
EJML=""
for m in ejml-core ejml-ddense ejml-dsparse; do J=$(find $G/org.ejml/$m -name "$m-0.44.0.jar" | head -1); EJML="$EJML;$(cygpath -w "$J")"; done
OUT=build/p5-solid-liquid-classes
CP="$(cygpath -w "$PWD/$OUT");$(cygpath -w "$PWD/src/main/resources");$(cygpath -w "$PWD/src/test/resources");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/classes/java/test");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$PWD/build/resources/test");$(cygpath -w "$GSON");$(cygpath -w "$JUNIT");$(cygpath -w "$OPENTEST");$(cygpath -w "$COMMONS")$EJML"
MODE="${1:-tests}"; shift || true
if [ "$MODE" = compile ] || [ ! -d "$OUT" ]; then
  rm -rf "$OUT"; mkdir -p "$OUT"
  SOURCES=(src/main/java/com/wormzjl/createcheme/science/thermo/phase/*.java
           src/main/java/com/wormzjl/createcheme/science/material/JaegerSpanGibbsFunction.java
           src/main/java/com/wormzjl/createcheme/science/material/CrystalReference.java)
  if [ "${TESTS:-yes}" = yes ]; then
    SOURCES+=(src/test/java/com/wormzjl/createcheme/science/thermo/phase/*.java
              src/test/java/com/wormzjl/createcheme/science/thermo/qualification/*.java)
  fi
  if [ "${SETS:-engine}" = network ]; then
    SOURCES+=(src/main/java/com/wormzjl/createcheme/science/fluid/thermo/*.java
              src/main/java/com/wormzjl/createcheme/science/fluid/state/*.java
              src/main/java/com/wormzjl/createcheme/science/fluid/transport/*.java)
    for d in ${NETWORK_TESTS:-}; do SOURCES+=($d); done
  fi
  EXTRA=()
  for f in "$TOOL"/src/*.java; do [ -f "$f" ] && EXTRA+=("$f"); done
  "$JDK/javac" -nowarn -encoding UTF-8 -d "$(cygpath -w "$OUT")" -cp "$CP" "${SOURCES[@]}" "${EXTRA[@]}" \
    "$TOOL/RunTests.java"
  echo "compiled"
  [ "$MODE" = compile ] && exit 0
fi
if [ "$MODE" = tests ]; then
  "$JDK/java" ${JAVA_PROPS:-} -cp "$CP" RunTests "$@"
else
  "$JDK/java" ${JAVA_PROPS:-} -cp "$CP" "$@"
fi
