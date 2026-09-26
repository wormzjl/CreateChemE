# LNN-gap campaign results

Population 330 inputs, SHA-256 `321d9501b4b909c5...`; 7 arms x 2 blocks x 3 modes = 13860 measured requests on ten workers.

Baseline parity against the promotion run: **reproduced**. Latency noise band (the baseline's own between-block spread in the pooled FIRST mean): 119.1 ms.

## Strict counts

| arm | block | classical | LNN_ONLY | LNN_FIRST | ONLY mean ms | FIRST mean ms |
|---|--:|--:|--:|--:|--:|--:|
| E1a | 1 | 110 | 164 | 182 | 877.4 | 4002.0 |
| E1a | 2 | 110 | 164 | 182 | 866.8 | 3950.8 |
| E1b | 1 | 110 | 146 | 170 | 786.8 | 3887.5 |
| E1b | 2 | 110 | 146 | 170 | 860.5 | 4437.3 |
| E2a | 1 | 110 | 163 | 183 | 857.6 | 3634.4 |
| E2a | 2 | 110 | 161 | 183 | 917.8 | 3998.2 |
| E2b | 1 | 110 | 0 | 110 | 22.2 | 4283.9 |
| E2b | 2 | 110 | 0 | 110 | 22.2 | 4425.2 |
| E3 | 1 | 110 | 170 | 182 | 3160.8 | 6020.8 |
| E3 | 2 | 110 | 171 | 182 | 3224.8 | 6025.1 |
| E4 | 1 | 110 | 164 | 182 | 999.8 | 4085.0 |
| E4 | 2 | 110 | 164 | 182 | 989.6 | 4053.5 |
| baseline | 1 | 110 | 162 | 180 | 856.2 | 4035.3 |
| baseline | 2 | 110 | 162 | 180 | 839.4 | 3916.2 |

## Paired against the baseline: LNN_ONLY

| arm | b1 gained / lost | b2 gained / lost | net (min) | reproduced | paired common-success ms |
|---|---|---|--:|---|--:|
| E1a | 3 / 1 | 3 / 1 | 2 | yes | 8.5 |
| E1b | 0 / 16 | 0 / 16 | -16 | yes | -0.8 |
| E2a | 7 / 6 | 6 / 7 | -1 | no | -14.5 |
| E2b | 0 / 162 | 0 / 162 | -162 | yes |  |
| E3 | 8 / 0 | 9 / 0 | 8 | no | -4.7 |
| E4 | 2 / 0 | 2 / 0 | 2 | yes | -2.0 |

## Paired against the baseline: LNN_FIRST

| arm | b1 gained / lost | b2 gained / lost | net (min) | reproduced | paired common-success ms |
|---|---|---|--:|---|--:|
| E1a | 2 / 0 | 2 / 0 | 2 | yes | 21.5 |
| E1b | 0 / 10 | 0 / 10 | -10 | yes | 38.0 |
| E2a | 5 / 2 | 5 / 2 | 3 | yes | -32.3 |
| E2b | 0 / 70 | 0 / 70 | -70 | yes | 827.5 |
| E3 | 2 / 0 | 2 / 0 | 2 | yes | 100.5 |
| E4 | 2 / 0 | 2 / 0 | 2 | yes | 30.7 |

## Study gates

| arm | FIRST b1/b2 | gain both blocks | classical union kept | latency delta ms | passed |
|---|--:|---|---|--:|---|
| E1a | 182/182 | yes | yes | 0.6 | yes |
| E1b | 170/170 | no | yes | 186.6 | no |
| E2a | 183/183 | yes | yes | -159.4 | yes |
| E2b | 110/110 | no | yes | 378.8 | no |
| E3 | 182/182 | yes | yes | 2047.1 | no |
| E4 | 182/182 | yes | yes | 93.4 | yes |

## Target groups

### crawling (20 of 20 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E1a | 12/12 | 14/14 | 2 | 1 |
| E1b | 5/5 | 10/10 | 0 | 6 |
| E2a | 13/12 | 15/15 | 2 | 0 |
| E2b | 0/0 | 7/7 | 0 | 11 |
| E3 | 13/13 | 14/14 | 2 | 0 |
| E4 | 11/11 | 13/13 | 0 | 0 |
| baseline | 11/11 | 13/13 | 0 | 0 |

### group-b (18 of 18 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E1a | 1/1 | 18/18 | 1 | 0 |
| E1b | 0/0 | 18/18 | 0 | 0 |
| E2a | 2/1 | 18/18 | 2 | 0 |
| E2b | 0/0 | 18/18 | 0 | 0 |
| E3 | 6/7 | 18/18 | 6 | 0 |
| E4 | 0/0 | 18/18 | 0 | 0 |
| baseline | 0/0 | 18/18 | 0 | 0 |

### group-c (34 of 34 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E1a | 2/2 | 2/2 | 2 | 0 |
| E1b | 0/0 | 0/0 | 0 | 0 |
| E2a | 5/5 | 5/5 | 5 | 0 |
| E2b | 0/0 | 0/0 | 0 | 0 |
| E3 | 1/1 | 1/1 | 1 | 0 |
| E4 | 2/2 | 2/2 | 2 | 0 |
| baseline | 0/0 | 0/0 | 0 | 0 |

## Ramp handoff cost

| arm | mode | armed requests | accepted | mean ms armed | mean ms accepted | mean ms refused | total s |
|---|---|--:|--:|--:|--:|--:|--:|
| E3 | neural | 308 | 18 | 5031.2 | 1260.0 | 5265.3 | 1549.6 |
| E3 | neuralFirst | 285 | 18 | 4917.6 | 1257.2 | 5164.4 | 1401.5 |

## Crawling recall (E2 criterion)

| arm | continued b1 | continued b2 | minimum fraction |
|---|--:|--:|--:|
| E1a | 15 | 15 | 0.750 |
| E1b | 15 | 15 | 0.750 |
| E2a | 18 | 18 | 0.900 |
| E2b | 0 | 0 | 0.000 |
| E3 | 16 | 16 | 0.800 |
| E4 | 15 | 15 | 0.750 |
| baseline | 15 | 15 | 0.750 |

## Registered decisions

- **E1**: selected `E1a` from ['E1a', 'E1b']; net LNN_ONLY gain (min over blocks) {'E1a': 2, 'E1b': -16}
- **E2**: selected `None` from ['E2a', 'E2b']; net LNN_ONLY gain (min over blocks) {'E2a': -1, 'E2b': -162}
- **E3**: selected `None` from ['E3']; net LNN_ONLY gain (min over blocks) {'E3': 8}
- **E4**: selected `E4` from ['E4']; net LNN_ONLY gain (min over blocks) {'E4': 2}
- **E5 combination**: ['E1a', 'E4']

## Provenance

- registration `c09d25ed2007d2aa...` over protocol.md
- production delta: V3AnchorTransformerInitializer.java, V3ColumnCalculator.java, V3InitializationOptions.java, V3NeuralModels.java
- bundled weights `7f909d025e12cbc7...`

