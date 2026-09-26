#!/usr/bin/env bash
# Compiles only the main sources (science, runtime, ColumnInputPreset and the cloud-harness stand-ins) of REPO into
# OUT/main, as tools/cloud-science-harness/harness.sh compile does, without the tests. The probes of this folder are
# compiled against OUT/main (javac -cp OUT/main:<gson, EJML>) and run with src/main/resources and src/generated/resources
# on the class path. Usage: REPO=<tree> OUT=<dir> LIB=<jar folder> compile-main.sh
REPO=${REPO:-$(cd "$(dirname "$0")/../../../.." && pwd)};OUT=${OUT:?set OUT};LIB=${LIB:?set LIB (the harness jars)}
CP=$LIB/gson-2.10.1.jar:$LIB/ejml-core-0.44.0.jar:$LIB/ejml-ddense-0.44.0.jar:$LIB/ejml-dsparse-0.44.0.jar
PKG=com/wormzjl/createcheme;H=$REPO/tools/cloud-science-harness
unset JAVA_TOOL_OPTIONS
rm -rf "$OUT/main";mkdir -p "$OUT/main"
{ find "$REPO/src/main/java/$PKG/science" "$REPO/src/main/java/$PKG/runtime" -name '*.java'; echo "$REPO/src/main/java/$PKG/world/level/block/entity/ColumnInputPreset.java"; find "$H/stubs" -name '*.java'; } > "$OUT/main-sources.txt"
javac -encoding UTF-8 --release 21 -nowarn -Xmaxerrs 50 -d "$OUT/main" -cp "$CP" @"$OUT/main-sources.txt"
