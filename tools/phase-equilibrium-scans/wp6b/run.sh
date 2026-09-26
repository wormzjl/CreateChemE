#!/usr/bin/env bash
# WP6b: compiles the worktree's phase package (p3/compile.sh, PIN=0: against build/classes as they are) with the given
# sources and runs the class named first (package science.thermo.phase). Same as p3/run.sh, but the JUnit jars are
# picked exactly (the p3 glob can pick a -sources jar, so assertion messages fail with NoClassDefFoundError).
# Usage: bash tools/phase-equilibrium-scans/wp6b/run.sh <SimpleClassName> <source.java>... [program arguments]
set -euo pipefail
MAIN="$1"; shift
SOURCES=(); ARGS=()
for a in "$@"; do if [[ "$a" == *.java ]]; then SOURCES+=("$a"); else ARGS+=("$a"); fi; done
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT=$(bash "$HERE/compile.sh" "${SOURCES[@]}" | tail -1)
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
pick() { find "$G/$1" -name "$2" | head -1; }
GSON=$(pick com.google.code.gson/gson/2.10.1 'gson-2.10.1.jar')
JUNIT=$(pick org.junit.jupiter/junit-jupiter-api 'junit-jupiter-api-5.11.4.jar')
OPENTEST=$(pick org.opentest4j/opentest4j 'opentest4j-1.3.0.jar')
COMMONS=$(pick org.junit.platform/junit-platform-commons 'junit-platform-commons-1.11.4.jar')
"$JDK/java" -cp "$(cygpath -w "$OUT");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/classes/java/test");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$GSON");$(cygpath -w "$JUNIT");$(cygpath -w "$OPENTEST");$(cygpath -w "$COMMONS")" "com.wormzjl.createcheme.science.thermo.phase.$MAIN" "${ARGS[@]}"
