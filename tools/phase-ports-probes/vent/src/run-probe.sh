#!/usr/bin/env bash
# Run a d9 gate-classification probe (ManometerProbe by default, P=DrainProbe for the drain) against a compiled tree.
#   MAIN   compiled main classes of the tree to test (harness.sh compile writes them to $OUT/main)
#   PROBE  compiled probe classes (javac -d <dir> -cp $MAIN:<jars> tools/phase-ports-probes/d9/src/ManometerProbe.java ...);
#          put the instrumented classes (src/instr-gate-probe.patch, compiled the same way) in front of it to instrument
#   LIB    folder with gson and the three EJML jars (tools/cloud-science-harness fetch)
#   JOPTS  JVM system properties: -Dgateprobe.compact [-Dgateprobe.all] | -Dgateprobe.max=N | -Dnewtonprobe |
#          -Dnewton.freshStart | -Dnewton.fresh (instrumented classes); -Dvent.polish.trace=true (prototype build)
# Example: MAIN=build/cloud-harness/main PROBE=/tmp/probe LIB=tools/cloud-science-harness/lib \
#          bash run-probe.sh 5 4 LIQUID 104325 101325 0.3 0.3 0.05 VAPOR VAPOR
set -euo pipefail
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
DEPS=$LIB/gson-2.10.1.jar:$LIB/ejml-core-0.44.0.jar:$LIB/ejml-ddense-0.44.0.jar:$LIB/ejml-dsparse-0.44.0.jar
exec java ${JOPTS:-} -cp "$PROBE:$MAIN:$REPO/src/main/resources:$REPO/src/generated/resources:$DEPS" \
  com.wormzjl.createcheme.science.fluid.network.${P:-ManometerProbe} "$@"
