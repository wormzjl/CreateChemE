#!/usr/bin/env bash
# coltest.sh <label> <classes-dir> <main-dir> <tree> <java> [jvm opts]: column/thermo/material tests (javac build) under one runtime
SP=/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad; L=$SP/lib
label=$1 cls=$2 main=$3 tree=$4 j=$5; shift 5
out=$SP/jdk-sweep/coltest-reports/$label; rm -rf $out; mkdir -p $out
cd $tree && env -u JAVA_TOOL_OPTIONS $j -Xmx3g "$@" -Dcreatecheme.fluid.scheduler.verify=true -jar $L/junit-platform-console-standalone-1.11.4.jar execute \
  -cp $cls:$tree/src/test/resources:$main:$tree/src/main/resources:$tree/src/generated/resources:$L/gson-2.10.1.jar:$L/ejml-core-0.44.0.jar:$L/ejml-ddense-0.44.0.jar:$L/ejml-dsparse-0.44.0.jar \
  --select-package com.wormzjl.createcheme.science.column --select-package com.wormzjl.createcheme.science.thermo --select-package com.wormzjl.createcheme.science.material \
  --disable-banner --details=summary --details-theme=ascii --reports-dir=$out > $out/console.txt 2>&1
echo "$label exit=$? $(grep -E 'tests (successful|failed|found)' $out/console.txt | tr -s ' []' ' ' | paste -sd, -)"
