#!/bin/bash
# Third seed-preparation batch: lenient projections (trace underflow floored instead of declining), after batch 2.
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/crude-regrouping-plan-review-28f25a
R=research/convergence-review
M=$R/models/G/seed-17023/epoch-160.sidecar.json
H=$R/design-v2/holdout-v2.jsonl
until [ -f $R/eval/prep2-none-G17023-holdoutv2/run.json ]; do sleep 20; done
sleep 30
for arm in project3c project1c; do
  $R/compare_prep.sh $R/eval/prep3-$arm-G17023-holdoutv2 $H $M $arm
done
echo PREP3-DONE
