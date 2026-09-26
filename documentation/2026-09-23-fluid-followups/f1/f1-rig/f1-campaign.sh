#!/usr/bin/env bash
# F1 in-game campaign on the dedicated server, one run at a time: node run-server.js, then analyze.js.
# Usage: f1-campaign.sh <build: after|before> <runId> <scenario> [warmup=60] [window=60]
# "after" runs on the checked-out branch head; "before" expects the worktree checked out at 23beadd (the caller does
# the checkout). The run config's certificateStationaryTolerance is set to the build's own default (1e-7 after,
# 1e-9 before), since NeoForge keeps a value an earlier start wrote. Machine gate before each run; a crashed JVM keeps
# its hs_err file under f1-logs/jvm-crash/ and the run is repeated once as <runId>-rerun.
set -u
WT=/d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36
RIG=$WT/documentation/fluid-followups/f1-rig
LOGS=$WT/documentation/fluid-followups/f1-logs/rig
mkdir -p "$LOGS" "$WT/documentation/fluid-followups/f1-logs/jvm-crash"
build=$1 id=$2 scenario=$3 warmup=${4:-60} window=${5:-60}
case $build in after) tol=1.0E-7 ;; before) tol=1.0E-9 ;; *) echo "build must be after or before"; exit 2 ;; esac
sed -i "s/^\(\s*certificateStationaryTolerance = \).*/\1$tol/" "$WT/run/config/createcheme-common.toml"
gate() {
  local out; out=$(powershell -NoProfile -Command "\$e=@(Get-Process Endfield -ErrorAction SilentlyContinue).Count; \$f=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024); \"\$e \$f\"")
  local e=${out% *} f=${out#* }; f=${f//[$'\r\n ']/}
  echo "$(date '+%F %T') gate endfield=$e freeMiB=$f" | tee -a "$LOGS/campaign.log"
  [ "$e" = "0" ] && [ "$f" -ge 20480 ]
}
for attempt in 1 2; do
  until gate; do echo "gate failed, waiting 60 s" | tee -a "$LOGS/campaign.log"; sleep 60; done
  echo "$(date '+%F %T') start $id ($build, $scenario, ${warmup}+${window} s) at $(cd $WT && git rev-parse --short HEAD), tolerance $(grep -o 'certificateStationaryTolerance = .*' $WT/run/config/createcheme-common.toml)" | tee -a "$LOGS/campaign.log"
  (cd "$RIG" && node run-server.js "$WT" "$id" "$scenario" "$warmup" "$window") > "$LOGS/$id.console.log" 2>&1
  rc=$?
  echo "$(date '+%F %T') end $id rc=$rc" | tee -a "$LOGS/campaign.log"
  crash=$(ls "$WT"/run/hs_err*.log "$WT"/hs_err*.log 2>/dev/null)
  if [ -n "$crash" ]; then for f in $crash; do mv "$f" "$WT/documentation/fluid-followups/f1-logs/jvm-crash/$(basename "$f" .log)-$id.log"; done; echo "JVM crash in $id" | tee -a "$LOGS/campaign.log"; id="$id-rerun"; continue; fi
  [ $rc = 0 ] && (cd "$RIG" && node analyze.js "$LOGS/$id" > "$LOGS/$id.analyze.log" 2>&1)
  exit $rc
done
