#!/bin/bash
# Fourth seed-preparation batch: confirm the undersized lift on the reversed block and sweep its factor.
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/crude-regrouping-plan-review-28f25a
R=research/convergence-review
M=$R/models/G/seed-17023/epoch-160.sidecar.json
H=$R/design-v2/holdout-v2.jsonl
HR=$R/design-v2/holdout-v2-reverse.jsonl
until [ -f $R/eval/prep3-project1c-G17023-holdoutv2/run.json ]; do sleep 20; done
sleep 30
$R/compare_prep.sh $R/eval/prep4-liftUnder-G17023-holdoutv2-rev $HR $M liftUnder
$R/compare_prep.sh $R/eval/prep4-none-G17023-holdoutv2-rev $HR $M none
$R/compare_prep.sh $R/eval/prep4-liftUnder10-G17023-holdoutv2 $H $M liftUnder 10
$R/compare_prep.sh $R/eval/prep4-liftUnder1.5-G17023-holdoutv2 $H $M liftUnder 1.5
echo PREP4-DONE
