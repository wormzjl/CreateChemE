| run | process CPU cores | fluid workers + not attributed, cores | tick ms p50 / p95 | engine ms p50 / p95 | GC count | live heap MiB | allocation MiB/s | statuses at window end |
|---|---|---|---|---|---|---|---|---|
| srv-fill100-base-r01 | 12.20 | 11.88 | 0.579 / 1.293 | 0.3116 / 1.3318 | 679 | 746 | 9181 | FULL 32, HELD 56, SOLVING 12 |
| srv-fill100-base-r02 | 12.26 | 11.96 | 0.617 / 1.220 | 0.3227 / 1.1776 | 390 | 387 | 9597 | FULL 40, HELD 48, SOLVING 12 |
| srv-fill100-wp5-r01 | 12.24 | 11.83 | 0.377 / 1.411 | 0.1204 / 1.1634 | 620 | 897 | 9107 | FULL 30, HELD 58, SOLVING 12 |
| srv-fill100-wp5-r02 | 12.30 | 11.91 | 0.344 / 1.046 | 0.0696 / 0.9348 | 611 | 757 | 9587 | FULL 32, HELD 56, SOLVING 12 |
| srv-mixed100-base-r01 | 7.81 | 7.58 | 0.576 / 1.677 | 0.3824 / 2.5617 | 433 | 641 | 5873 | FULL 78, HELD 18, SOLVING 4 |
| srv-mixed100-base-r02 | 7.75 | 7.48 | 0.613 / 1.198 | 0.3638 / 2.2104 | 285 | 322 | 6125 | FULL 93, HELD 5, SOLVING 2 |
| srv-mixed100-wp5-r01 | 9.02 | 8.78 | 0.374 / 1.127 | 0.2106 / 1.6102 | 409 | 541 | 6984 | FULL 44, HELD 20, RESTING 25, SOLVING 11 |
| srv-mixed100-wp5-r02 | 7.76 | 7.51 | 0.410 / 0.929 | 0.1942 / 1.3283 | 387 | 610 | 6091 | FULL 55, HELD 18, RESTING 25, SOLVING 2 |
