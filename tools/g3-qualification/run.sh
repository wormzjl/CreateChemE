#!/usr/bin/env bash
# Compiles the G3 qualification fixtures (src/test/java/.../science/thermo/qualification) with javac against the last
# Gradle compile of the worktree (build/classes/java/main and test, build/resources) and runs the named classes with the
# reflective runner, without Gradle. EXTRA=<sources> MAIN=<class> compiles scratch probes too and runs MAIN instead. Usage: bash tools/g3-qualification/run.sh G3F1PureFluidOracleTest [G3F2...] [-Dmethod=name]
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
GSON="$G/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
JUNIT=$(find $G/org.junit.jupiter/junit-jupiter-api -name "junit-jupiter-api-5.11.4.jar" | head -1)
OPENTEST=$(find $G/org.opentest4j/opentest4j -name "opentest4j-1.3.0.jar" | head -1)
COMMONS=$(find $G/org.junit.platform/junit-platform-commons -name "junit-platform-commons-1.11.4.jar" | head -1)
EJML=""
for m in ejml-core ejml-ddense ejml-dsparse; do J=$(find $G/org.ejml/$m -name "$m-0.44.0.jar" | head -1); EJML="$EJML;$(cygpath -w "$J")"; done
OUT=build/g3-qualification-classes
rm -rf "$OUT"; mkdir -p "$OUT"
CP="$(cygpath -w "$PWD/$OUT");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/classes/java/test");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$PWD/build/resources/test");$(cygpath -w "$PWD/src/test/resources");$(cygpath -w "$GSON");$(cygpath -w "$JUNIT");$(cygpath -w "$OPENTEST");$(cygpath -w "$COMMONS")$EJML"
"$JDK/javac" -nowarn -encoding UTF-8 -d "$(cygpath -w "$OUT")" -cp "$CP" src/test/java/com/wormzjl/createcheme/science/thermo/qualification/*.java tools/g3-qualification/RunTests.java ${EXTRA:-}
PROPS=(); CLASSES=()
for a in "$@"; do if [[ "$a" == -D* ]]; then PROPS+=("$a"); else CLASSES+=("com.wormzjl.createcheme.science.thermo.qualification.$a"); fi; done
if [ -n "${MAIN:-}" ]; then "$JDK/java" -cp "$CP" "$MAIN"; else "$JDK/java" "${PROPS[@]}" -cp "$CP" RunTests "${CLASSES[@]}"; fi
