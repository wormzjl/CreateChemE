#!/usr/bin/env bash
# Tooling cleanup gates (F4's gates.sh with a dirty-tree marker, a task-graph check and the suite XML copy), one Gradle
# invocation at a time, logs in cleanup-logs/gate-<tag>-<name>.log, START/END lines in cleanup-logs/gates.log.
# Usage: gates.sh <tag> [names...]   names: compile tasks science runtime network regression gametest column
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36" || exit 2
L=documentation/fluid-followups/cleanup-logs
TAG=${1:-dev}; shift
NAMES=${@:-science runtime network regression gametest}
state() { powershell -NoProfile -Command "\$g=Get-Process Endfield -ErrorAction SilentlyContinue; \$f=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024); if(\$g){'GAME '+\$f}else{'NOGAME '+\$f}"; }
G() { local name=$1; shift
  local s; s=$(state); s=${s//[$'\r\n']/}
  case $s in GAME*) echo "$(date '+%F %T') REFUSED $TAG $name: $s" >> $L/gates.log; return 1 ;; esac
  echo "$(date '+%F %T') START $TAG $name at $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- src build.gradle || echo +dirty) $s" >> $L/gates.log
  JAVA_OPTS=-Xshare:off ./gradlew.bat "$@" --offline --console=plain "-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Xshare:off" > $L/gate-$TAG-$name.log 2>&1
  local code=$?; echo "$(date '+%F %T') END $TAG $name exit $code" >> $L/gates.log
  ls hs_err_pid*.log 2>/dev/null && { for f in hs_err_pid*.log; do mv "$f" "$L/jvm-crash/${f%.log}-gate-$TAG-$name.log"; done; echo "$(date '+%F %T') CRASH $TAG $name" >> $L/gates.log; }
  return $code
}
xml() { local task=$1; mkdir -p "$L/$TAG-test-results/$task"; cp build/test-results/$task/TEST-*.xml "$L/$TAG-test-results/$task/" 2>/dev/null; }
for n in $NAMES; do
  case $n in
    compile) G compile compileJava compileTestJava compileFluidGameTestJava ;;
    tasks) G tasks tasks --all ;;
    science) G science fluidScienceTest --rerun; xml fluidScienceTest ;;
    runtime) G runtime fluidRuntimeTest --rerun; xml fluidRuntimeTest
             for f in P12-worker-trajectories.json P31-cadence-trajectories.json; do cp build/reports/fluid/$f $L/$TAG-$f; sha256sum $L/$TAG-$f documentation/fluid-scheduler/wp2-logs/$f >> $L/gates.log; done ;;
    network) G network fluidNetworkBenchmark --rerun; xml fluidNetworkBenchmark ;;
    regression) G regression fluidSolverRegression -PfluidRegressionMode=exact --rerun; xml fluidSolverRegression ;;
    gametest) G gametest runFluidGameTestServer -PfluidGameTestRunId=cleanup-$TAG ;;
    column) G column test --tests "com.wormzjl.createcheme.science.column.*" --tests "com.wormzjl.createcheme.science.material.*" --tests "com.wormzjl.createcheme.science.thermo.*"; xml test ;;
  esac
done
echo "$(date '+%F %T') GATES DONE $TAG" >> $L/gates.log
