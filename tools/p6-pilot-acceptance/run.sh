#!/usr/bin/env bash
# P6 pilot acceptance (batch 2026-09-24-coolprop-low-temperature) without Gradle: javac of every science.* main source of
# the worktree (pure Java, no Minecraft classes), the listed test sources and this folder's probes against the last Gradle
# compile (build/classes/java/main and test, build/resources); the worktree's src/main and src/test resources come first on
# the classpath. Then JUnit @Test methods through the reflective runner (RunTests.java), or a probe's main.
# Usage, from Git Bash in the worktree root:
#   bash tools/p6-pilot-acceptance/run.sh compile
#   bash tools/p6-pilot-acceptance/run.sh tests <TestClass ...>      (-Dmethod=name through JAVA_PROPS)
#   bash tools/p6-pilot-acceptance/run.sh main  <class> [args]
# Environment: TESTS lists extra test source files or directories to compile (default: the network and phase test packages).
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
OUT=build/p6-pilot-classes
CP="$(cygpath -w "$PWD/$OUT");$(cygpath -w "$PWD/src/main/resources");$(cygpath -w "$PWD/src/test/resources");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/classes/java/test");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$PWD/build/resources/test");$(cygpath -w "$GSON");$(cygpath -w "$JUNIT");$(cygpath -w "$OPENTEST");$(cygpath -w "$COMMONS")$EJML"
MODE="${1:-tests}"; shift || true
if [ "$MODE" = compile ] || [ ! -d "$OUT" ]; then
  rm -rf "$OUT"; mkdir -p "$OUT"
  mapfile -t SOURCES < <(find src/main/java/com/wormzjl/createcheme/science -name '*.java')
  for d in ${TESTS:-src/test/java/com/wormzjl/createcheme/science/fluid/network src/test/java/com/wormzjl/createcheme/science/material/PilotCryogenicTestCatalog.java}; do
    if [ -d "$d" ]; then mapfile -t -O "${#SOURCES[@]}" SOURCES < <(find "$d" -maxdepth 1 -name '*.java'); else SOURCES+=("$d"); fi
  done
  for f in "$TOOL"/src/*.java; do [ -f "$f" ] && SOURCES+=("$f"); done
  "$JDK/javac" -nowarn -encoding UTF-8 -d "$(cygpath -w "$OUT")" -cp "$CP" "${SOURCES[@]}" "$TOOL/RunTests.java"
  echo "compiled ${#SOURCES[@]} sources"
  [ "$MODE" = compile ] && exit 0
fi
if [ "$MODE" = tests ]; then
  "$JDK/java" ${JAVA_PROPS:-} -cp "$CP" RunTests "$@"
else
  "$JDK/java" ${JAVA_PROPS:-} -cp "$CP" "$@"
fi
