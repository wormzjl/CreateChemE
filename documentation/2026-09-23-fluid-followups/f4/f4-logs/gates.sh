#!/usr/bin/env bash
# F4 gates, one Gradle invocation at a time, logs in f4-logs/gate-<tag>-<name>.log. Usage: gates.sh <tag> [names...]
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36" || exit 2
L=documentation/fluid-followups/f4-logs
TAG=${1:-dev}; shift
NAMES=${@:-science runtime network regression gametest}
G() { local name=$1; shift
  echo "$(date '+%F %T') START $TAG $name at $(git rev-parse --short HEAD) $(powershell -NoProfile -Command "\$g=Get-Process Endfield -ErrorAction SilentlyContinue; \$f=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024); if(\$g){'GAME '+\$f}else{'NOGAME '+\$f}")" >> $L/gates.log
  JAVA_OPTS=-Xshare:off ./gradlew.bat "$@" --offline --console=plain "-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Xshare:off" > $L/gate-$TAG-$name.log 2>&1
  local code=$?; echo "$(date '+%F %T') END $TAG $name exit $code" >> $L/gates.log
  ls hs_err_pid*.log 2>/dev/null && { for f in hs_err_pid*.log; do mv "$f" "$L/jvm-crash/${f%.log}-gate-$TAG-$name.log"; done; echo "$(date '+%F %T') CRASH $TAG $name" >> $L/gates.log; }
}
for n in $NAMES; do
  case $n in
    science) G science fluidScienceTest --rerun ;;
    runtime) G runtime fluidRuntimeTest --rerun
             for f in P12-worker-trajectories.json P31-cadence-trajectories.json; do cp build/reports/fluid/$f $L/$TAG-$f; sha256sum $L/$TAG-$f documentation/fluid-scheduler/wp2-logs/$f >> $L/gates.log; done ;;
    network) G network fluidNetworkBenchmark --rerun ;;
    regression) G regression fluidSolverRegression -PfluidRegressionMode=exact --rerun ;;
    gametest) G gametest runFluidGameTestServer -PfluidGameTestRunId=f4-$TAG ;;
    compile) G compile compileJava compileTestJava compileFluidGameTestJava ;;
    column) G column test --tests "com.wormzjl.createcheme.science.column.*" --tests "com.wormzjl.createcheme.science.material.*" --tests "com.wormzjl.createcheme.science.thermo.*" ;;
  esac
done
echo "$(date '+%F %T') GATES DONE $TAG" >> $L/gates.log
