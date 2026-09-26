# LNN-gap campaign results

Population 330 inputs, SHA-256 `321d9501b4b909c5...`; 2 arms x 2 blocks x 3 modes = 3960 measured requests on ten workers.

Baseline parity against the promotion run: **NOT reproduced**. Latency noise band (the baseline's own between-block spread in the pooled FIRST mean): 185.7 ms.

## Strict counts

| arm | block | classical | LNN_ONLY | LNN_FIRST | ONLY mean ms | FIRST mean ms |
|---|--:|--:|--:|--:|--:|--:|
| E5 | 1 | 110 | 166 | 184 | 997.0 | 4038.5 |
| E5 | 2 | 110 | 166 | 184 | 1007.3 | 4123.1 |
| baseline | 1 | 110 | 163 | 180 | 808.6 | 3722.7 |
| baseline | 2 | 110 | 162 | 180 | 837.9 | 3908.3 |

## Paired against the baseline: LNN_ONLY

| arm | b1 gained / lost | b2 gained / lost | net (min) | reproduced | paired common-success ms |
|---|---|---|--:|---|--:|
| E5 | 5 / 2 | 5 / 1 | 3 | no | 19.8 |

## Paired against the baseline: LNN_FIRST

| arm | b1 gained / lost | b2 gained / lost | net (min) | reproduced | paired common-success ms |
|---|---|---|--:|---|--:|
| E5 | 4 / 0 | 4 / 0 | 4 | yes | 86.8 |

## Study gates

| arm | FIRST b1/b2 | gain both blocks | classical union kept | latency delta ms | passed |
|---|--:|---|---|--:|---|
| E5 | 184/184 | yes | yes | 265.3 | no |

## Target groups

### crawling (20 of 20 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E5 | 12/12 | 14/14 | 2 | 1 |
| baseline | 11/11 | 13/13 | 0 | 0 |

### group-b (18 of 18 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E5 | 1/1 | 18/18 | 1 | 1 |
| baseline | 1/0 | 18/18 | 0 | 0 |

### group-c (34 of 34 present)

| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |
|---|--:|--:|--:|--:|
| E5 | 4/4 | 4/4 | 4 | 0 |
| baseline | 0/0 | 0/0 | 0 | 0 |

## Crawling recall (E2 criterion)

| arm | continued b1 | continued b2 | minimum fraction |
|---|--:|--:|--:|
| E5 | 15 | 15 | 0.750 |
| baseline | 15 | 15 | 0.750 |

## Registered decisions

- **E1**: selected `None` from []; net LNN_ONLY gain (min over blocks) {}
- **E2**: selected `None` from []; net LNN_ONLY gain (min over blocks) {}
- **E3**: selected `None` from []; net LNN_ONLY gain (min over blocks) None
- **E4**: selected `None` from []; net LNN_ONLY gain (min over blocks) None
- **E5**: selected `None` from ['E5']; net LNN_ONLY gain (min over blocks) {'E5': 3}
- **E5 combination**: nothing selected

## Provenance

- registration `c09d25ed2007d2aa...` over protocol.md
- production delta: V3AnchorTransformerInitializer.java, V3ColumnCalculator.java, V3InitializationOptions.java, V3NeuralModels.java, V3SimultaneousColumnSolver.java
- bundled weights `7f909d025e12cbc7...`

