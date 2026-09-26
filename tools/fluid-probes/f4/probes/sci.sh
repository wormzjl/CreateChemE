#!/usr/bin/env bash
# F4 off-line probe runner: compiles the science sources (no Minecraft dependency) together with one probe
# class and runs it against src/main/resources, in seconds and without Gradle. Usage:
#   sci.sh <ProbeFile.java> <fully.qualified.MainClass> [args...]
# The probe may live in any science package to reach package-private members. Three Minecraft-free runtime classes
# (PhysicalFluidTopology, FluidDeviceSpec, SlurryFeed) are compiled too, so a probe can build placed lines.
set -euo pipefail
W="$(cd "$(dirname "$0")/../../../.." && pwd)"
# REF=<commit> compiles and runs against that commit's sources and resources instead (a "before" measurement
# without touching the working tree): they are extracted with git archive into a temporary directory.
if [ -n "${REF:-}" ]; then R="$(cygpath -u "${TEMP:-/tmp}")/f4ref-$REF"; rm -rf "$R"; mkdir -p "$R"; (cd "$W" && git archive "$REF" src/main | tar -x -C "$R"); W="$R"; fi
M2=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
JARS=("$M2/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar")
for j in ejml-core ejml-ddense ejml-dsparse; do JARS+=("$(ls $M2/org.ejml/$j/0.44.0/*/$j-0.44.0.jar)"); done
OUT="$(cygpath -u "${TEMP:-/tmp}")/f4sci${REF:+-$REF}"; rm -rf "$OUT"; mkdir -p "$OUT/classes"
CP=""; for j in "${JARS[@]}"; do CP="$CP;$(cygpath -w "$j")"; done; CP="${CP#;}"
find "$W/src/main/java/com/wormzjl/createcheme/science" -name "*.java" | while read -r f; do cygpath -w "$f"; done > "$OUT/files.txt"
for f in PhysicalFluidTopology FluidDeviceSpec SlurryFeed; do cygpath -w "$W/src/main/java/com/wormzjl/createcheme/runtime/fluid/$f.java" >> "$OUT/files.txt"; done
cygpath -w "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")" >> "$OUT/files.txt"
javac -nowarn -encoding UTF-8 --release 21 -cp "$CP" -d "$(cygpath -w "$OUT/classes")" @"$(cygpath -w "$OUT/files.txt")"
shift; MAIN="$1"; shift
java ${JAVA_PROPS:-} -cp "$(cygpath -w "$OUT/classes");$(cygpath -w "$W/src/main/resources");$CP" "$MAIN" "$@"
