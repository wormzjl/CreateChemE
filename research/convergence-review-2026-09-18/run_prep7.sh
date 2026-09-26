#!/bin/bash
# Seventh seed-preparation batch: confirmation block of the gated lift at 1.5 and the gated lift at 1.0.
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/crude-regrouping-plan-review-28f25a
R=research/convergence-review
M=$R/models/G/seed-17023/epoch-160.sidecar.json
H=$R/design-v2/holdout-v2.jsonl
HR=$R/design-v2/holdout-v2-reverse.jsonl
until [ -f $R/eval/prep6-liftSelect1.5-G17023-holdoutv2/run.json ]; do sleep 20; done
sleep 30
$R/compare_prep.sh $R/eval/prep7-liftSelect1.5-G17023-holdoutv2-rev $HR $M liftSelect 1.5
$R/compare_prep.sh $R/eval/prep7-liftSelect1.0-G17023-holdoutv2 $H $M liftSelect 1.0
echo PREP7-DONE
