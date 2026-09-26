#!/bin/bash
# Sixth seed-preparation batch: lift factor below 1.5, and the residual gate at 1.5, after batch 5.
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/crude-regrouping-plan-review-28f25a
R=research/convergence-review
M=$R/models/G/seed-17023/epoch-160.sidecar.json
H=$R/design-v2/holdout-v2.jsonl
until [ -f $R/eval/prep5-liftSelect-G17023-holdoutv2-rev/run.json ]; do sleep 20; done
sleep 30
$R/compare_prep.sh $R/eval/prep6-liftUnder1.0-G17023-holdoutv2 $H $M liftUnder 1.0
$R/compare_prep.sh $R/eval/prep6-liftUnder1.2-G17023-holdoutv2 $H $M liftUnder 1.2
$R/compare_prep.sh $R/eval/prep6-liftSelect1.5-G17023-holdoutv2 $H $M liftSelect 1.5
echo PREP6-DONE
