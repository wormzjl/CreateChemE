#!/usr/bin/env bash
# Compiles the worktree's WP6a sources (compile.sh) with the given scan sources and runs the class named first.
# Usage: bash tools/phase-equilibrium-scans/p3/run.sh <SimpleClassName> <source.java>... [program arguments]
# (scan classes live in package science.thermo.phase; arguments not ending in .java go to the program).
set -euo pipefail
MAIN="$1"; shift
SOURCES=(); ARGS=()
for a in "$@"; do if [[ "$a" == *.java ]]; then SOURCES+=("$a"); else ARGS+=("$a"); fi; done
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT=$(bash "$HERE/compile.sh" "${SOURCES[@]}" | tail -1)
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
GSON="$G/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
JUNIT=$(ls $G/org.junit.jupiter/junit-jupiter-api/*/*/junit-jupiter-api-[0-9.]*.jar | head -1)
OPENTEST=$(ls $G/org.opentest4j/opentest4j/*/*/opentest4j-[0-9.]*.jar | head -1)
COMMONS=$(ls $G/org.junit.platform/junit-platform-commons/*/*/junit-platform-commons-[0-9.]*.jar | head -1)
"$JDK/java" -cp "$(cygpath -w "$OUT");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/classes/java/test");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$GSON");$(cygpath -w "$JUNIT");$(cygpath -w "$OPENTEST");$(cygpath -w "$COMMONS")" "com.wormzjl.createcheme.science.thermo.phase.$MAIN" "${ARGS[@]}"
