# Generation comparison: recommendation erratum v1

Date: 2026-09-11. This corrects recommendation 2 in the
[frozen findings](generation-comparison-findings.md), published in commit
`4c5e9be`. The completed comparison and all numerical conclusions are unchanged.

The phrase "reflux, withdrawal and pumparound edges" incorrectly suggests that
current V3 includes pumparound circulation in its component material balances.
The proposed training experiment should instead use this formulation:

> Compute native stage/component material balances using adjacent liquid/vapor
> flows, the condenser and reflux boundary, feed and side-draw retention.
> Current V3 represents pumparounds as prescribed stage-heat terms. Include
> their duties only in a separately proposed energy-balance loss. Verify each
> implemented balance against native TRAIN fixtures.

This follows `V3MeshResidualEvaluator.materialBalance()` at lines 279-307:
the component balances contain no pumparound circulation term. Its
`energyResidual()` at lines 335-358 includes `problem.stageHeatWatts(node)`;
`V3ColumnProblem.stageHeatWatts()` at lines 155-157 returns the prescribed duty.
These are the semantics of the current solver used in this comparison.

For the proposed material-loss experiment, first compare unscaled component
imbalances with native TRAIN fixtures. Fixed TRAIN-derived throughput scales
would then belong to the training objective. Retain existing profile and trace
supervision, change one loss term at a time, and judge the experiment by full
native validation. This remains a proposed experiment with no demonstrated
benefit or implementation in this study.

The frozen findings and archive remain byte-identical:

- Findings SHA-256: `90c1229f2adf37eef62986f22b17033813958ca651bdc17ade4d853269a18e53`.
- Archive SHA-256: `23b58474c9b49955e4cd0d686ed202773a938b987ce40f62582e641e1df0641c`.

This separately versioned erratum supersedes only that recommendation's
material/energy formulation. It requires no campaign rerun, training,
reselection, or change to the frozen driver, models, reports or archive.
