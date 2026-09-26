#!/bin/bash
# Science-only compile + JUnit harness for the cloud container (Gradle cannot reach the NeoForge/Create Maven hosts here).
# Usage: science-build.sh compile            -> compiles science main + science tests into $OUT
#        science-build.sh test [junit args]   -> runs the fluid science tests (default: --select-package com.wormzjl.createcheme.science.fluid)
set -e
REPO=/home/user/CreateChemE
SP=/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad
LIB=$SP/lib
OUT=$SP/out
CP=$LIB/gson-2.10.1.jar:$LIB/ejml-core-0.44.0.jar:$LIB/ejml-ddense-0.44.0.jar:$LIB/ejml-dsparse-0.44.0.jar
JUNIT=$LIB/junit-platform-console-standalone-1.11.4.jar
export JAVA_TOOL_OPTIONS=
cmd=${1:-compile}; shift || true
if [ "$cmd" = compile ]; then
  rm -rf $OUT; mkdir -p $OUT/main $OUT/test
  find $REPO/src/main/java/com/wormzjl/createcheme/science -name '*.java' > $SP/main-sources.txt
  javac -encoding UTF-8 -nowarn -d $OUT/main -cp $CP @$SP/main-sources.txt
  # science tests that do not touch runtime/world/minecraft, plus the shared support class
  grep -rLE "(com\.wormzjl\.createcheme\.(runtime|world|network|client|registry)\.|net\.minecraft|net\.neoforged)" \
     $(find $REPO/src/test/java/com/wormzjl/createcheme/science -name '*.java') > $SP/test-sources.txt
  grep -L "MaterialCatalogTest\." $(cat $SP/test-sources.txt) > $SP/test-sources.tmp; mv $SP/test-sources.tmp $SP/test-sources.txt
  ls $REPO/src/test/java/com/wormzjl/createcheme/fluid/support/*.java >> $SP/test-sources.txt
  javac -encoding UTF-8 -nowarn -d $OUT/test -cp $OUT/main:$CP:$JUNIT @$SP/test-sources.txt
  echo "compiled $(wc -l < $SP/main-sources.txt) main and $(wc -l < $SP/test-sources.txt) test sources"
elif [ "$cmd" = test ]; then
  ARGS="$@"; [ -z "$ARGS" ] && ARGS="--select-package com.wormzjl.createcheme.science.fluid"
  java -Xmx3G -Dcreatecheme.fluid.scheduler.verify=true -jar $JUNIT execute \
     -cp $OUT/main:$OUT/test:$REPO/src/main/resources:$REPO/src/test/resources:$CP \
     --details=summary --details-theme=ascii --disable-banner --fail-if-no-tests $ARGS
else echo "unknown command $cmd"; exit 2; fi
