| 91a | run 87b set (`BASE FINAL '-PbeStateCap=0.05' -PsolverDiag=true`), `-PjunctionInterval=0.1` | 32/32 | 12/12, every probe line equal to run 87b; 1082 ms, 1922 / 2, 2158 Newton solves, 5.11 iterations, 1213 MB (`run91a-cost-table.txt`) |
| 91b / 91c | 91a at `-PjunctionInterval=1` / `5` | 32/32 | 12/12 / 12/12; 431 / 363 ms, 262 / 40 and 227 / 19 accepted / rejected, 355 / 281 solves, 8.19 / 8.50 iterations, 365 / 317 MB |
| 91d / 91e | 91b / 91c with `-PstartStep=hint` | 32/32 | 12/12 / 12/12; 437 / 351 ms, 318 / 10 and 232 / 8. 91e bottles the injection (0.05:4:true +2797 Pa at 13 s): the first 3 s injection step starts at rest with the void edges closed by the start-point closure, and its 2.8 % change is inside the 0.05 cap. Trajectories in `run91-trajectory-vs-run91a.txt` and `run91-trajectory-summary.txt` |
| 91j / 91k | 91c / 91a transient only, `-Pjfr` | - | allocation profiles `run91j-interval5-jfr-sites.txt`, `run91k-interval0.1-jfr-sites.txt` (phase checks 36.7 % of the bytes at 0.1 s) |
| 92 | 91c repeated: the 5 s baseline of the levers, with the cold-seed timers | 32/32 | 12/12, 349 ms, of which the cold seed 157 ms |
| 92a | 92 + `-PphaseCheck=once` | 32/32 | 12/12, every probe line equal to 92; flashes 5023 -> 4216, 333 ms |
| 92b | 92 + `-PlowAlloc=on` (first form) | 32/32 | 12/12, equal to 92; transient 317 -> 144 MB, static 416 -> 142 MB |
| 92c | 92 + `-Ppredictor=linear` | 32/32 | 12/12; worse: 9.71 iterations, 741 Jacobians, 23 Newton rejections, 446 ms |
| 92d | 92 + `-PjacobianReuse=on` | 32/32 | 12/12; neutral: 8.59 iterations, 374 Jacobians, 207 preconditioned opens |
| 92e / 92f / 92g | 92 + `-ProwForm=amount` at tolerance 1e-9 / 1e-8 / 1e-7 | 32/32 each | 12/12 each; 8.61 / 8.18 / 7.69 iterations; 92g: 11 "Velocity constraint did not close" |
| 92h | 92 + `-PnewtonTolerance=1e-8` (rate rows) | 32/32 | 12/12; 199 "Conservative reconstruction fails equation gate" rejections |
| 93a-i | combinations at 5 s and 0.1 s (A+B+C8, D+E, D+E+C8, C8, A-E) | 32/32 each | 12/12 each. 93k: gate dump of C8 at 0.1 s (0.02:4:true): the junction enthalpy row, 9.7e-9 after the Newton and 1.14e-8 after the reconstruction |
| 95a / 95e | memory probe, 87b set | - | 5-node 80.8 / 81.3 KB retained per island (graph 14.9 KB, solver 65.8 KB); 50-node chain 856 / 851 KB (graph 103 KB, solver 753 KB) |
| 95b / 95c / 95d | memory probe + `lowAlloc` / `jacobianReuse` / both | - | 5-node 64.0 / 75.4 / 57.1 KB; chain 569 / 861 / 575 KB |
| 96a-e | lowAlloc (final form) and D+E at 5 s and 0.1 s, base 0.1 s repeat | 32/32 each | 12/12 each; every probe line equal to 92 / 91a |
| 96j / 96k | D+E, `-Pjfr`, 0.1 s / 5 s | - | `run96j-DE-interval0.1-jfr-sites.txt`, `run96k-DE-interval5-jfr-sites.txt` |
| 97a-f | final code: D+E at 0.1 / 5 / 5-hint / 1 s, A-E at 5 / 0.1 s | 32/32 each | 12/12 each; D+E equal to the base line for line; `run97-cost-wide.txt`, `run97-alloc-units.txt` |
| 98a / 98b / 98c | 7 gate classes, 87b set without diag / + A-E / + D+E (`run98x-gates/`) | - | **36/38** each: NetworkRegime x2 only (FilterBlock passes at cap 0.05) |
| 99a / 99b / 99c | fluid suites, 87b set / + A-E / + D+E (`run99x-gates/`) | - | **386/402** / **384/402** / **386/402** (402 = 399 + the three probe wrappers); 99c equals 99a message for message; 99b adds two IslandCertificateTest failures |
| 99e-i | IslandCertificate and McpGameplayRegression classes, 87b set + predictor / amount+1e-8 / amount 1e-9 / reuse / rate 1e-8 (`run99x-gates/`) | - | the certificate failures come from the predictor (closed ladder) and from tolerance 1e-8 (both tests) |
| 99d | fluid suites, defaults, no init script (`run99d-gates/`) | - | **399/399** in 91 classes, 0 skipped |
