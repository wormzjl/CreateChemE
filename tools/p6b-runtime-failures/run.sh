#!/usr/bin/env bash
# P6b runtime-failure probes (batch 2026-09-24-coolprop-low-temperature) without Gradle. Compiles every science.* and
# runtime/fluid main source of the worktree (overlaying the last Gradle compile), the listed test sources and this
# folder's src/*.java against the last Gradle compile plus the NeoForge merged jar and the game-test legacy classpath
# (net.minecraft.nbt for the saved checkpoint), then runs a main or JUnit @Test methods (RunTests from p6-pilot-acceptance).
# Usage, from Git Bash in the worktree root:
#   bash tools/p6b-runtime-failures/run.sh compile
#   bash tools/p6b-runtime-failures/run.sh main  <class> [args]
#   bash tools/p6b-runtime-failures/run.sh tests <TestClass ...>      (-Dmethod=name through JAVA_PROPS)
set -euo pipefail
TOOL="$(cd "$(dirname "$0")" && pwd)"
cd "$(git rev-parse --show-toplevel)"
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
m() { cygpath -m "$1"; }
JUNIT=$(find $G/org.junit.jupiter/junit-jupiter-api -name "junit-jupiter-api-5.11.4.jar" | head -1)
OPENTEST=$(find $G/org.opentest4j/opentest4j -name "opentest4j-1.3.0.jar" | head -1)
COMMONS=$(find $G/org.junit.platform/junit-platform-commons -name "junit-platform-commons-1.11.4.jar" | head -1)
EJML=""
for mod in ejml-core ejml-ddense ejml-dsparse; do J=$(find $G/org.ejml/$mod -name "$mod-0.44.0.jar" | head -1); EJML="$EJML;$(m "$J")"; done
LEGACY=$(tr -d '\r' < build/moddev/fluidGameTestServerLegacyClasspath.txt | while read -r l; do cygpath -m "$l"; done | paste -sd ';')
OUT=build/p6b-classes
CP="$(m "$PWD/$OUT");$(m "$PWD/src/main/resources");$(m "$PWD/src/test/resources");$(m "$PWD/build/classes/java/main");$(m "$PWD/build/classes/java/test");$(m "$PWD/build/resources/main");$(m "$PWD/build/resources/test");$(m "$PWD/build/moddev/artifacts/neoforge-21.1.219-merged.jar");$LEGACY;$(m "$JUNIT");$(m "$OPENTEST");$(m "$COMMONS")$EJML"
printf -- '-cp\n"%s"\n' "$CP" > build/p6b-cp.txt
MODE="${1:-tests}"; shift || true
if [ "$MODE" = compile ] || [ ! -d "$OUT" ]; then
  rm -rf "$OUT"; mkdir -p "$OUT"
  mapfile -t SOURCES < <(find src/main/java/com/wormzjl/createcheme/science src/main/java/com/wormzjl/createcheme/runtime/fluid -name '*.java')
  for d in ${TESTS:-src/test/java/com/wormzjl/createcheme/science/fluid/network src/test/java/com/wormzjl/createcheme/science/material/PilotCryogenicTestCatalog.java}; do
    if [ -d "$d" ]; then mapfile -t -O "${#SOURCES[@]}" SOURCES < <(find "$d" -maxdepth 1 -name '*.java'); else SOURCES+=("$d"); fi
  done
  for f in "$TOOL"/src/*.java; do [ -f "$f" ] && SOURCES+=("$f"); done
  if [ -n "${COUNTERS:-}" ]; then for f in "$TOOL"/src-counters/*.java; do [ -f "$f" ] && SOURCES+=("$f"); done; fi
  SOURCES+=("$TOOL/../p6-pilot-acceptance/RunTests.java")
  { printf -- '-nowarn\n-encoding\nUTF-8\n-proc:none\n-d\n"%s"\n' "$(m "$PWD/$OUT")"; cat build/p6b-cp.txt; for f in "${SOURCES[@]}"; do printf '"%s"\n' "$(m "$f")"; done; } > build/p6b-javac.txt
  "$JDK/javac" "@$(m build/p6b-javac.txt)"
  echo "compiled ${#SOURCES[@]} sources"
  [ "$MODE" = compile ] && exit 0
fi
if [ "$MODE" = tests ]; then
  "$JDK/java" ${JAVA_PROPS:-} "@$(m build/p6b-cp.txt)" RunTests "$@"
else
  "$JDK/java" ${JAVA_PROPS:-} "@$(m build/p6b-cp.txt)" "$@"
fi
