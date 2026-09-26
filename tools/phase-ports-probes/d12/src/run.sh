#!/bin/bash
# D12 scratch drivers: compile tools/phase-ports-probes/d12/src/*.java against a tree's harness output and run one.
# usage: REPO=<tree> LIB=<jar folder> tools/phase-ports-probes/d12/src/run.sh <fully.qualified.Class> [args]
# REPO must have been compiled by tools/cloud-science-harness/harness.sh compile (classes in $REPO/build/cloud-harness).
unset JAVA_TOOL_OPTIONS
HERE=$(cd "$(dirname "$0")" && pwd)
: "${REPO:?set REPO}" "${LIB:?set LIB}"
OUT=$REPO/build/cloud-harness; CLS=$OUT/d12-probes
DEPS=$LIB/gson-2.10.1.jar:$LIB/ejml-core-0.44.0.jar:$LIB/ejml-ddense-0.44.0.jar:$LIB/ejml-dsparse-0.44.0.jar
mkdir -p "$CLS"
javac -nowarn -encoding UTF-8 --release 21 -d "$CLS" -cp "$OUT/main:$OUT/test:$DEPS" "$HERE"/*.java || exit 1
cls=$1; shift
cd "$REPO" && java -Xmx2g $JOPTS -cp "$CLS:$OUT/test:$REPO/src/test/resources:$OUT/main:$REPO/src/main/resources:$REPO/src/generated/resources:$DEPS" "$cls" "$@"
