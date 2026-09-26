#!/bin/bash
# usage: compare_prep.sh <out-dir> <input-jsonl> <model sidecar> <seedPrep mode> [<undersizedFactor>] [<oversizedFactor>]
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/crude-regrouping-plan-review-28f25a
EXTRA=""
if [ -n "$5" ]; then EXTRA="-PundersizedFactor=$5"; fi
if [ -n "$6" ]; then EXTRA="$EXTRA -PoversizedFactor=$6"; fi
./gradlew.bat -I research/convergence-review/prep.init.gradle regroupingTraining -PseedPrep=$4 $EXTRA -PtrainingMain=com.wormzjl.createcheme.science.column.v3.V3GeneralTrainingProbe "-PtrainingArgs=compare $1 $2 10 30 $3" --offline --console=plain > "$1.log" 2>&1
tail -2 "$1.log" | head -1
