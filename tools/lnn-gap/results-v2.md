# LNN-gap campaign results

Population 330 inputs, SHA-256 `321d9501b4b909c5...`; 2 arms x 2 blocks x 3 modes = 3960 measured requests on ten workers.

Baseline parity against the promotion run: **NOT reproduced**. Latency noise band (the baseline's own between-block spread in the pooled FIRST mean): 61.6 ms.

## Strict counts

| arm | block | classical | LNN_ONLY | LNN_FIRST | ONLY mean ms | FIRST mean ms |
|---|--:|--:|--:|--:|--:|--:|
| E2b | 1 | 110 | 164 | 183 | 932.4 | 4000.9 |
| E2b | 2 | 110 | 164 | 183 | 931.4 | 3987.6 |
| baseline | 1 | 110 | 163 | 180 | 835.1 | 3892.0 |
| baseline | 2 | 110 | 162 | 180 | 843.4 | 3953.7 |

## Paired against the baseline: LNN_ONLY

| arm | b1 gained / lost | b2 gained / lost | net (min) | reproduced | paired common-success ms |
|---|---|---|--:|---|--:|
| E2b | 7 / 6 | 7 / 5 | 1 | no | 9.1 |

## Paired against the baseline: LNN_FIRST

| arm | b1 gained / lost | b2 gained / lost | net (min) | reproduced | paired common-success ms |
|---|---|---|--:|---|--:|
| E2b | 6 / 3 | 6 / 3 | 3 | yes | 21.2 |

## Study gates

| arm | FIRST b1/b2 | gain both blocks | classical union kept | latency delta ms | passed |
|---|--:|---|---|--:|---|
| E2b | 183/183 | yes | yes | 71.4 | no |

## Target groups

### crawling (20 of 20 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E2b | 12/12 | 15/15 | 2 | 1 |
| baseline | 11/11 | 13/13 | 0 | 0 |

### group-b (18 of 18 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E2b | 1/1 | 18/18 | 1 | 1 |
| baseline | 1/0 | 18/18 | 0 | 0 |

### group-c (34 of 34 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E2b | 6/6 | 6/6 | 6 | 0 |
| baseline | 0/0 | 0/0 | 0 | 0 |

## Crawling recall (E2 criterion)

| arm | continued b1 | continued b2 | minimum fraction |
|---|--:|--:|--:|
| E2b | 20 | 20 | 1.000 |
| baseline | 15 | 15 | 0.750 |

## Registered decisions

- **E1**: selected `None` from []; net LNN_ONLY gain (min over blocks) {}
- **E2**: selected `None` from ['E2b']; net LNN_ONLY gain (min over blocks) {'E2b': 1}
- **E3**: selected `None` from []; net LNN_ONLY gain (min over blocks) None
- **E4**: selected `None` from []; net LNN_ONLY gain (min over blocks) None
- **E5 combination**: nothing selected

## Provenance

- registration `c09d25ed2007d2aa...` over protocol.md
- production delta: V3AnchorTransformerInitializer.java, V3ColumnCalculator.java, V3InitializationOptions.java, V3NeuralModels.java, V3SimultaneousColumnSolver.java
- bundled weights `7f909d025e12cbc7...`

