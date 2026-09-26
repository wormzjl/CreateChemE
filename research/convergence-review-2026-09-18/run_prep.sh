#!/bin/bash
# Seed-preparation study: five arms, one after another, never overlapping (10 workers each).
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/crude-regrouping-plan-review-28f25a
R=research/convergence-review
M=$R/models/G/seed-17023/epoch-160.sidecar.json
H=$R/design-v2/holdout-v2.jsonl
mkdir -p $R/eval
for arm in none project1 project3 project3T anchorFlows; do
  $R/compare_prep.sh $R/eval/prep-$arm-G17023-holdoutv2 $H $M $arm
done
echo PREP-DONE
