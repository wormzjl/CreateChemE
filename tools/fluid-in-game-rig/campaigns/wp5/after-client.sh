#!/usr/bin/env bash
# Runs after the in-game client block: server repeats of the two held-retry scenarios, then the paced harness.
R=/d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36/documentation/fluid-scheduler/wp5-rig
L=$R/../wp5-logs
until [ "$(grep -c 'campaign done' $L/campaign.log)" -ge 3 ]; do sleep 20; done
cd $R && node campaign.js "server:wp5:mixed100:60:60:02,server:base:mixed100:60:60:02,server:base:fill100:60:60:02,server:wp5:fill100:60:60:02" > $L/campaign-repeats.out 2>&1
bash $R/paced-campaign.sh transient >> $L/paced-console.log 2>&1
bash $R/paced-campaign.sh module-off >> $L/paced-console.log 2>&1
echo "$(date '+%F %T') module-on with a 30 min cap" >> $L/paced/campaign.log
timeout 1800 bash $R/paced-campaign.sh module-on >> $L/paced-console.log 2>&1
rc=$?
if [ $rc -eq 124 ]; then
  echo "$(date '+%F %T') module-on hit the 30 min cap; stopping its server" >> $L/paced/campaign.log
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*module-wp5-on-r01*' -or (\$_.CommandLine -like '*agent-ae139e4fc1b184b36*' -and \$_.CommandLine -like '*fml.modFolders*') } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force; 'killed ' + \$_.ProcessId }" >> $L/paced/campaign.log 2>&1
  (cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36 && JAVA_OPTS=-Xshare:off ./gradlew.bat --stop >> $L/paced/campaign.log 2>&1)
fi
echo "$(date '+%F %T') AFTER-CLIENT DONE rc=$rc" >> $L/paced/campaign.log
