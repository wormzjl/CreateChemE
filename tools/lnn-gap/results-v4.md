# LNN-gap campaign results

Population 330 inputs, SHA-256 `321d9501b4b909c5...`; 3 arms x 2 blocks x 3 modes = 5940 measured requests on ten workers.

Baseline parity against the promotion run: **NOT reproduced**. Latency noise band (the baseline's own between-block spread in the pooled FIRST mean): 23.3 ms.

## Strict counts

| arm | block | classical | LNN_ONLY | LNN_FIRST | ONLY mean ms | FIRST mean ms |
|---|--:|--:|--:|--:|--:|--:|
| E3-2500 | 1 | 110 | 172 | 184 | 1852.6 | 4795.2 |
| E3-2500 | 2 | 110 | 172 | 184 | 1857.0 | 4814.8 |
| E3-4000 | 1 | 110 | 172 | 184 | 2283.2 | 5147.3 |
| E3-4000 | 2 | 110 | 172 | 184 | 2278.9 | 5178.0 |
| baseline | 1 | 110 | 164 | 182 | 872.9 | 4004.9 |
| baseline | 2 | 110 | 164 | 182 | 872.3 | 3981.6 |

## Paired against the baseline: LNN_ONLY

| arm | b1 gained / lost | b2 gained / lost | net (min) | reproduced | paired common-success ms |
|---|---|---|--:|---|--:|
| E3-2500 | 8 / 0 | 8 / 0 | 8 | yes | 0.3 |
| E3-4000 | 8 / 0 | 8 / 0 | 8 | yes | -3.7 |

## Paired against the baseline: LNN_FIRST

| arm | b1 gained / lost | b2 gained / lost | net (min) | reproduced | paired common-success ms |
|---|---|---|--:|---|--:|
| E3-2500 | 2 / 0 | 2 / 0 | 2 | yes | 16.3 |
| E3-4000 | 2 / 0 | 2 / 0 | 2 | yes | 42.8 |

## Study gates

| arm | FIRST b1/b2 | gain both blocks | classical union kept | latency delta ms | passed |
|---|--:|---|---|--:|---|
| E3-2500 | 184/184 | yes | yes | 811.8 | no |
| E3-4000 | 184/184 | yes | yes | 1169.4 | no |

## Target groups

### crawling (20 of 20 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E3-2500 | 14/14 | 15/15 | 2 | 0 |
| E3-4000 | 14/14 | 15/15 | 2 | 0 |
| baseline | 12/12 | 14/14 | 0 | 0 |

### group-b (18 of 18 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E3-2500 | 6/6 | 18/18 | 5 | 0 |
| E3-4000 | 6/6 | 18/18 | 5 | 0 |
| baseline | 1/1 | 18/18 | 0 | 0 |

### group-c (34 of 34 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E3-2500 | 3/3 | 3/3 | 1 | 0 |
| E3-4000 | 3/3 | 3/3 | 1 | 0 |
| baseline | 2/2 | 2/2 | 0 | 0 |

## Ramp handoff cost

| arm | mode | armed requests | accepted | mean ms armed | mean ms accepted | mean ms refused | total s |
|---|---|--:|--:|--:|--:|--:|--:|
| E3-2500 | neural | 306 | 18 | 2119.9 | 1292.2 | 2171.7 | 648.7 |
| E3-2500 | neuralFirst | 291 | 18 | 2096.5 | 1293.5 | 2149.4 | 610.1 |
| E3-4000 | neural | 306 | 18 | 3050.8 | 1269.4 | 3162.1 | 933.5 |
| E3-4000 | neuralFirst | 290 | 18 | 3013.3 | 1265.7 | 3129.0 | 873.9 |

## Crawling recall (E2 criterion)

| arm | continued b1 | continued b2 | minimum fraction |
|---|--:|--:|--:|
| E3-2500 | 16 | 16 | 0.800 |
| E3-4000 | 16 | 16 | 0.800 |
| baseline | 15 | 15 | 0.750 |

## Registered decisions

- **E1**: selected `None` from []; net LNN_ONLY gain (min over blocks) {}
- **E2**: selected `None` from []; net LNN_ONLY gain (min over blocks) {}
- **E3**: selected `None` from []; net LNN_ONLY gain (min over blocks) None
- **E4**: selected `None` from []; net LNN_ONLY gain (min over blocks) None
- **E5 combination**: nothing selected

## Provenance

- registration `8d5007ecfe5fd56a...` over protocol.md
- production delta: V3AnchorTransformerInitializer.java, V3ColumnCalculator.java, V3InitializationOptions.java, V3NeuralModels.java, V3SimultaneousColumnSolver.java, v3-column-transformer-f0.md
- bundled weights `7f909d025e12cbc7...`

