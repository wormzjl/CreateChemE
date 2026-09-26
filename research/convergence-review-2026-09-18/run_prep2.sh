#!/bin/bash
# Second seed-preparation batch: waits for the first batch to finish, then runs the undersized-lift arm and a
# repeated baseline under the same machine load. Sequential, never overlapping.
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/crude-regrouping-plan-review-28f25a
R=research/convergence-review
M=$R/models/G/seed-17023/epoch-160.sidecar.json
H=$R/design-v2/holdout-v2.jsonl
until [ -f $R/eval/prep-anchorFlows-G17023-holdoutv2/run.json ]; do sleep 20; done
sleep 30
for arm in liftUnder none; do
  $R/compare_prep.sh $R/eval/prep2-$arm-G17023-holdoutv2 $H $M $arm
done
echo PREP2-DONE
