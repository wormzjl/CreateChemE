# Matched generation comparison

Fixed Gen2 MLP, selected Gen3 factorized network, and transformer seed 20260911. All cases use ten workers, a 2-second neural budget, a 30-second request deadline and 16 iterations per correction pass. Transformer journals are reused from the verified checkpoint study. This is an exposed-cohort comparison, not new blind testing or model selection.

| Population | Model | Strategy | Strict | Advisory | Failed | Mean elapsed ms | Sample SD ms | Mean CPU ms | Sample SD CPU ms |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| validation / 405 | gen2 | current | 110 | 5 | 290 | 4008.51 | 5725.69 | 3853.63 | 5537.83 |
| validation / 405 | gen2 | neural | 61 | 2 | 342 | 1231.44 | 730.65 | 1180.86 | 703.24 |
| validation / 405 | gen2 | neuralFirst | 136 | 6 | 263 | 5065.78 | 6019.20 | 4865.43 | 5793.69 |
| validation / 405 | gen3 | current | 110 | 5 | 290 | 3869.02 | 5597.66 | 3701.00 | 5380.89 |
| validation / 405 | gen3 | neural | 67 | 5 | 333 | 1049.20 | 755.54 | 1003.16 | 722.20 |
| validation / 405 | gen3 | neuralFirst | 134 | 8 | 263 | 4680.56 | 5872.00 | 4474.85 | 5619.63 |
| validation / 405 | transformer | current | 110 | 5 | 290 | 3872.85 | 5619.40 | 3717.59 | 5413.43 |
| validation / 405 | transformer | neural | 116 | 7 | 282 | 896.80 | 767.46 | 858.33 | 734.95 |
| validation / 405 | transformer | neuralFirst | 161 | 9 | 235 | 4071.37 | 5796.39 | 3914.27 | 5596.11 |
| test / 252 | gen2 | current | 51 | 4 | 197 | 5199.80 | 6613.10 | 4983.57 | 6335.70 |
| test / 252 | gen2 | neural | 29 | 1 | 222 | 1281.16 | 710.62 | 1227.00 | 678.90 |
| test / 252 | gen2 | neuralFirst | 60 | 4 | 188 | 6200.48 | 6790.55 | 5936.88 | 6518.23 |
| test / 252 | gen3 | current | 51 | 4 | 197 | 5274.85 | 6696.37 | 5066.53 | 6428.73 |
| test / 252 | gen3 | neural | 32 | 3 | 217 | 1197.07 | 763.09 | 1148.50 | 732.59 |
| test / 252 | gen3 | neuralFirst | 66 | 4 | 182 | 6104.11 | 6754.14 | 5857.58 | 6496.31 |
| test / 252 | transformer | current | 51 | 4 | 197 | 5152.79 | 6528.60 | 4946.92 | 6267.38 |
| test / 252 | transformer | neural | 54 | 4 | 194 | 1026.87 | 764.58 | 985.80 | 734.48 |
| test / 252 | transformer | neuralFirst | 80 | 6 | 166 | 5429.08 | 6681.01 | 5208.83 | 6434.21 |

All cases, including failures, contribute to timing. Sample SD describes case variability, not repeated-run timing uncertainty. CPU and allocation counters retain their own sample counts in the summary. Allocations are cumulative volume, not retained memory.

| Population | Model | FIRST gains / losses vs paired classical | Mean extra elapsed ms | Sample SD ms | FIRST gains / losses vs transformer |
|---|---|---:|---:|---:|---:|
| validation | gen2 | 26 / 0 | 1057.27 | 1135.88 | 9 / 34 |
| validation | gen3 | 24 / 0 | 811.54 | 1371.44 | 6 / 33 |
| validation | transformer | 51 / 0 | 198.52 | 2434.64 | reference |
| test | gen2 | 9 / 0 | 1000.68 | 1472.27 | 4 / 24 |
| test | gen3 | 15 / 0 | 829.26 | 2366.11 | 6 / 20 |
| test | transformer | 29 / 0 | 276.28 | 3046.23 | reference |

Cross-campaign elapsed differences include load and execution variation. Classical controls, case-level differences, stage/steam/equipment strata, raw availability and common-reference profile errors are retained in summary.json and case-map.jsonl. Different generations also differ in training data and selection; this is not an isolated architecture ablation.

Unions of separately successful model runs describe potential complementarity only. They are not measured cascade coverage or latency. The transformer choice and all runtime defaults remain unchanged.
