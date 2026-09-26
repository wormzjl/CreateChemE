#!/usr/bin/env bash
# The owner's minimal F2 in-game set: one run each of fill100 (800 devices) and rest1000 (5,000 devices) on the branch
# head, then on 111d805 (F1, before the placement fix), then back to the branch. One Gradle invocation at a time.
set -u
WT=/d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36
RIG=$WT/documentation/fluid-followups/f2-rig
LOG=$WT/documentation/fluid-followups/f2-logs/rig/campaign.log
mkdir -p "$(dirname "$LOG")"
cd "$WT" || exit 2
HEAD_SHA=$(git rev-parse --short HEAD)
echo "$(date '+%F %T') pairs start, after = $HEAD_SHA" >> "$LOG"
bash "$RIG/f2-campaign.sh" after srv-fill100-f2after-r01 fill100
bash "$RIG/f2-campaign.sh" after srv-rest1000-f2after-r01 rest1000
git checkout -q --detach 111d805 && echo "$(date '+%F %T') checked out 111d805 for the before runs" >> "$LOG"
bash "$RIG/f2-campaign.sh" before srv-fill100-f2before-r01 fill100
bash "$RIG/f2-campaign.sh" before srv-rest1000-f2before-r01 rest1000
git checkout -q claude/fluid-followups && echo "$(date '+%F %T') back on claude/fluid-followups at $(git rev-parse --short HEAD)" >> "$LOG"
echo "$(date '+%F %T') pairs done" >> "$LOG"
