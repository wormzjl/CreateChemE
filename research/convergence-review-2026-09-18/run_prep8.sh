#!/bin/bash
# Eighth seed-preparation batch: confirm the lift at factor 1.0 on the reversed block, then tighten the cap.
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/crude-regrouping-plan-review-28f25a
R=research/convergence-review
M=$R/models/G/seed-17023/epoch-160.sidecar.json
H=$R/design-v2/holdout-v2.jsonl
HR=$R/design-v2/holdout-v2-reverse.jsonl
until [ -f $R/eval/prep7-liftSelect1.0-G17023-holdoutv2/run.json ]; do sleep 20; done
sleep 30
$R/compare_prep.sh $R/eval/prep8-liftUnder1.0-G17023-holdoutv2-rev $HR $M liftUnder 1.0
$R/compare_prep.sh $R/eval/prep8-liftUnder1.0-cap1.5-G17023-holdoutv2 $H $M liftUnder 1.0 1.5
$R/compare_prep.sh $R/eval/prep8-liftUnder1.0-cap1.0-G17023-holdoutv2 $H $M liftUnder 1.0 1.0
echo PREP8-DONE
