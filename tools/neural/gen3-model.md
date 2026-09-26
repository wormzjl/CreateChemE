# Generation 3: rescued labels, recovery and performance

Generation 3 adds 96 strictly qualified training-fold rescues to the original 483 profiles, producing 579 fitted columns. Generation 2 is retained in the 68-file, 165,198,202-byte cache at `.neural-cache/gen2-1a4a01d/`, outside `build/` so Gradle cleaning preserves it. Its original models, data, folds and published results remain unchanged.

The additional data and alternative initializers find useful new solutions, but they do not replace classical initialization across this operating domain. The validation-selected factorized network recovered 86 previously unresolved inputs, yet qualified only 33/252 fresh inputs versus 38/252 for Gen2 and 63/252 classically. Nearest-profile transfer was stronger at recovery: 146 qualified rescues, including 14 of the 35 historical hard failures. The two selected methods cover 195 distinct new rescues between them.

The selected neural model is bundled as the explicit `GENERALIZED_GEN3_EXPERIMENTAL` option. `GENERALIZED_EXPERIMENTAL` retains Gen2, and `LOCAL_EXPERTS` remains the default. Nearest-profile libraries remain offline comparison artifacts. Every candidate still requires the unchanged native physical corrector and acceptance audits.

## Comparable operating and recovery populations

All entries below count **strict equilibrium-qualified native solutions**, excluding accepted supersaturation advisories. A raw prediction never counts as a solution. Candidate rows use `LNN_ONLY`: the candidate guess plus native correction, with no classical fallback. This mode name also applies to the offline non-neural transfer candidates. Classical rows use `CURRENT_ONLY`.

| Initializer | Validation /405 | Fresh operating test /252 | Previously reported geometry test /395 | Remaining-failure comparison /256 |
| --- | ---: | ---: | ---: | ---: |
| Classical CURRENT | 110 | 63 | 113 | 0 |
| Frozen Gen2 MLP | 61 | 38 | 42 | 0 |
| Gen3 MLP, existing representation | 56 | 40 | 55 | 13 |
| Gen3 factorized network | 67 | 33 | 51 | 8 |
| Nearest-profile transfer, k=1 | 84 | 40 | 64 | 28 |
| Nearest-profile transfer, k=3 | 73 | 43 | 68 | 7 |

The fresh 252-input holdout was frozen before fitting. It contains exactly four inputs for every integer stage count 2–64, with two steam-on and two steam-off inputs per count, and no overlap with the original 2,793 admitted inputs or 35 historical failures. It comes from a new 2,809-point OA-LHS candidate pool with seed `202609103`. The selected subset is stratified; it is not itself claimed to retain the pool's strength-two property. All twenty composition coordinates vary under the original design bounds.

The 395 geometry-test inputs were never fitted, but their Gen2 outcomes had already been reported. They are regression evidence. The 256-input recovery comparison contains 221 preselected remaining matrix failures and all 35 historical failures. It is an outcome-conditioned diagnostic, not an operating success-rate estimate. Twenty-six inputs overlap the geometry-test and recovery cohorts; the comparison executes their 877-input union once per candidate and preserves both memberships.

Classical fresh outcomes were measured in this campaign. Original validation/geometry outcomes are preserved physical references. The historical failure references include five earlier wet-continuation failures, explicitly recorded with that provenance rather than relabelled as CURRENT_ONLY. Timing from these mixed historical references is not pooled with the new serial benchmarks.

Candidate correction used ten workers, a fresh 30-second parent deadline per request, a 10-second candidate allowance, and the existing 16-iteration correction limit. These are bounded initializer comparisons, not equal-compute comparisons against an unrestricted neural solver. All physical tolerances and final certificates are unchanged.

## Selection and what the added data establishes

The 141 strictly qualified Gen2 rescues retain their original folds: 96 train, 26 validation and 19 test. The train set therefore grows to 579; qualified validation references grow to 136, and qualified test references to 132. The validation and test rescues never enter fitting. Six additional native-accepted rescues have water-equilibrium advisories and are excluded from fitted labels.

Only the original 405 validation requests selected candidates. Maximizing strict qualified solves chose the factorized network among new neural candidates and k=1 among the transfer candidates. All five artifact hashes and the comparison/retry/benchmark policy were frozen at `2026-09-10T13:51:22.550933Z`, before any candidate test comparison or full remaining-failure retry. Their fresh-test ranking reverses parts of the validation ranking. No model, threshold, neighbor count or selection was revised after seeing that result.

The MLP control preserves the Gen2 representation and optimizer, but this is not an isolated training-label ablation: the validation reference set also grew from 110 to 136. It qualifies two more fresh inputs in aggregate than Gen2, comprising 13 gains and 11 losses on identical requests. The factorized network has 15 gains and 20 losses against Gen2, for a net loss of five. These small, mixed changes do not establish a broad neural coverage improvement.

On the 96 newly fitted rescue inputs, the factorized network qualifies 59 and nearest-profile transfer qualifies all 96. That is fitted-input replay, not generalization or additional recovery.

## Full retry of the remaining Gen2 failures

Gen2's 147 native matrix rescues leave 1,899 matrix failures; the 35 historical failures make 1,934 remaining requests. None of these supplied a newly fitted successful label.

| Method | Native accepted /1,934 | Strict qualified /1,934 | Accepted advisories | Historical strict rescues /35 |
| --- | ---: | ---: | ---: | ---: |
| Gen3 factorized network | 91 | 86 | 5 | 0 |
| Nearest-profile transfer, k=1 | 158 | 146 | 12 | 14 |

The network's 86 qualified rescues retain original split provenance: 60 train, 12 validation and 14 test. The transfer method qualifies 132 remaining matrix failures and 14 historical failures. Every qualified rescue in both full runs is `DRY_EQUILIBRIUM`; all advisory accepts are `DRY_SUPERSATURATED`.

There are 37 shared strict rescues, 49 network-only rescues and 109 transfer-only rescues: **195 distinct qualified inputs, or 10.08% of the remaining pool**. This is the union of two separately executed methods. A combined cascade was not selected, implemented or timed, so the union is not presented as a measured cascade success rate or latency. These new outcomes are cached in the Gen3 journals for future work; they were not fed back into the frozen models.

## Where coverage remains weak

The following table uses the fresh test only. Each cell is a strict qualified count; denominators are fixed within each row.

| Stages | Inputs | Classical | Gen2 | Gen3 MLP | Gen3 factorized | Transfer k=1 | Transfer k=3 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2–8 | 28 | 11 | 16 | 18 | 8 | 17 | 15 |
| 9–16 | 32 | 12 | 8 | 10 | 12 | 8 | 10 |
| 17–32 | 64 | 22 | 11 | 10 | 12 | 9 | 14 |
| 33–48 | 64 | 13 | 3 | 0 | 1 | 4 | 3 |
| 49–64 | 64 | 5 | 0 | 2 | 0 | 2 | 1 |

Every candidate supplies a raw prediction on 251/252 fresh inputs. Thus most failures occur during physical correction rather than domain admission. The detailed stage/steam/PA/draw and operating-variable maps preserve the failed requests. Support for an input shape does not imply a reliable solve in that region.

All 579 fitted profiles are dry equilibria, with 569 two-phase and 10 liquid-only condensers. There are no wet-equilibrium or vapor-only-condenser training labels. The new model therefore does not replace the local wet expert or establish generalized wet-column performance.

## Raw profile accuracy

These medians compare each raw initializer against the **same 63 strictly qualified fresh CURRENT profiles**. They are medians of case-level RMSE values, not a pooled pointwise RMSE. The one advisory CURRENT profile is excluded here and retained separately in the model card's all-provided-reference statistics.

| Candidate | Temperature RMSE, K | Component-flow RMSE, mol/s | Phase-total difference RMSE / feed flow |
| --- | ---: | ---: | ---: |
| Gen2 | 21.87 | 187.20 | 1.0722 |
| Gen3 MLP | 19.79 | 160.73 | 0.9404 |
| Gen3 factorized | 17.14 | 118.22 | 0.2831 |
| Transfer k=1 | 44.36 | 205.51 | 0.7054 |
| Transfer k=3 | 28.38 | 180.03 | 0.5366 |

The factorized loss substantially improves bulk-profile agreement, especially phase totals, without improving fresh native convergence. Transfer has larger temperature disagreement but often provides a useful corrector starting state. Raw average profile error therefore does not reliably rank correction success in this experiment. Reference disagreement also does not establish that the supplied native profile is the only possible root.

## Serial latency, allocation and memory

Every method uses the same 64 fresh inputs, selected before fitting to cover all 50 occupied stage-bucket/steam/PA strata. These 64 are a timing sample, not a replacement for the 252-input operating holdout. Candidate profiles use `LNN_ONLY`; the classical profile uses `CURRENT_ONLY`. Separate JVMs use one solver worker, `-Xms512m -Xmx4g`, two fixed-input warmups, a 30-second parent deadline and a two-second/16-iteration candidate allowance. The runtime version is Java 21.0.11. No other native experiments or test suites ran alongside these serial measurements.

| Method | Strict qualified /64 | Median attempt, ms | Mean ± SD attempt, ms | p95 attempt, ms |
| --- | ---: | ---: | ---: | ---: |
| Classical CURRENT | 15 | 1,085.84 | 3,162.50 ± 5,554.29 | 10,584.40 |
| Gen2 | 16 | 540.01 | 791.60 ± 719.16 | 2,000.56 |
| Gen3 MLP | 15 | 643.43 | 865.45 ± 700.27 | 2,000.46 |
| Gen3 factorized | 13 | 489.48 | 728.44 ± 679.19 | 2,000.38 |
| Transfer k=1 | 18 | 363.94 | 596.23 ± 559.57 | 1,631.20 |
| Transfer k=3 | 13 | 300.11 | 584.18 ± 587.01 | 1,845.66 |

SD is the sample standard deviation across the 64 input-case timings, calculated with denominator `n−1` (`statistics.stdev`). It describes variation between inputs, including failures; it is not a standard error, confidence interval, or measure of repeated-run timing noise.

All-attempt times include rejection and unsuccessful correction. In particular, the two-second candidate ceiling is not the classical solver's time allowance. Faster failed attempts are not solve speedups. The next table uses only identical inputs strictly qualified by both CURRENT and the candidate; ratios above one mean the candidate used less time or allocation.

| Candidate | Jointly qualified inputs | Median CURRENT/candidate wall ratio | CPU ratio | Allocation-volume ratio |
| --- | ---: | ---: | ---: | ---: |
| Gen2 | 8 | 2.87 | 2.86 | 2.83 |
| Gen3 MLP | 7 | 2.04 | 2.07 | 1.66 |
| Gen3 factorized | 6 | 2.73 | 2.77 | 2.74 |
| Transfer k=1 | 9 | 4.19 | 4.25 | 3.87 |
| Transfer k=3 | 5 | 3.93 | 3.40 | 4.51 |

Each candidate has a different small set of shared successes; these ratios should not be used alone to rank candidates against one another. The paired input IDs, distributions and candidate-minus-CURRENT differences are in the model card.

| Method | Peak sampled working set, MiB | Peak sampled private memory, MiB | Median allocation volume per attempt, MiB |
| --- | ---: | ---: | ---: |
| Classical CURRENT | 837.4 | 892.8 | 1,178.61 |
| Gen2 | 911.6 | 974.2 | 682.91 |
| Gen3 MLP | 990.6 | 1,055.8 | 931.18 |
| Gen3 factorized | 781.9 | 840.0 | 610.96 |
| Transfer k=1 | 798.5 | 859.4 | 446.00 |
| Transfer k=3 | 855.4 | 915.4 | 368.44 |

Memory is sampled once per second from the whole solver JVM, including runtime, input parsing, model/library storage, warmup and correction. It is not per-case retained memory. Allocation volume can exceed the working set because temporary arrays are reclaimed and reallocated. Serialized model sizes are approximately 0.56 MiB for each MLP, 0.64 MiB for the factorized model and 9.73 MiB for each transfer library; JVM peaks are dominated by much more than those artifacts.

One interrupted setup trial initially sampled the Gradle launcher. Its partial journal and measurements are retained under `profile-current-invalid-launcher-monitor/` and excluded from every result. The complete profiles above require both the unique output path and the solver main class when attaching the sampler; final packaging verifies that evidence.

## Candidate-first behavior with classical fallback

These are separate, complete three-mode runs of the same fresh 64 inputs. Each case rotates the mode order deterministically. Every mode receives a fresh 30-second parent deadline; `LNN_FIRST` spends at most two seconds on its candidate before using the remaining deadline for any classical fallback. Each selected candidate has its own measured CURRENT control in the same JVM.

| Candidate study | Mode | Native / strict qualified | Median attempt, ms | Mean ± SD attempt, ms | p95 attempt, ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Gen3 factorized | CURRENT_ONLY | 15 / 15 | 1,101.42 | 3,172.91 ± 5,595.68 | 10,683.72 |
| Gen3 factorized | LNN_ONLY | 14 / 13 | 482.10 | 711.05 ± 672.31 | 2,000.40 |
| Gen3 factorized | LNN_FIRST | 23 / 22 | 1,266.41 | 3,669.08 ± 5,896.26 | 12,111.92 |
| Transfer k=1 | CURRENT_ONLY | 15 / 15 | 1,186.28 | 3,532.34 ± 6,092.01 | 13,239.71 |
| Transfer k=1 | LNN_ONLY | 18 / 18 | 452.46 | 666.32 ± 613.59 | 1,822.75 |
| Transfer k=1 | LNN_FIRST | 24 / 24 | 1,140.83 | 3,730.31 ± 6,229.42 | 13,714.85 |

The factorized `LNN_FIRST` path retains all 15 classical qualified cases and adds seven, with one further advisory accept. The mean paired time increase is **496.18 ± 1,029.62 ms** (mean ± sample SD of the 64 per-input `LNN_FIRST − CURRENT_ONLY` differences), corresponding to 15.6% higher mean all-attempt time; median time rises by 15.0%. It triggers 49 classical fallback events. On the 15 shared qualified classical cases, the median CURRENT/first time ratio is 0.56, showing the overhead imposed on many existing classical successes.

Transfer-first retains all 15 classical qualified cases and adds nine, with no advisory accepts. The mean paired time increase is **197.97 ± 1,609.08 ms**, using the same 64-input difference calculation, corresponding to 5.6% higher mean time; median time falls by 3.8%. It triggers 44 classical fallback events. Its median CURRENT/first time ratio on the 15 shared qualified cases is 2.94. These results favor transfer-first within this timing sample, but the transfer library remains an offline candidate and has not replaced the configured model family.

The two benchmark runs have measurably different classical timings despite identical inputs, so comparisons above use each run's own paired control. They are single warmed runs, with no timing confidence interval or cross-machine performance claim. The frozen Gen2 model was compared on the same input-only candidate profile; its previously published fallback benchmark used a different old 64-case set and is not used as a direct fallback-time control here.

## Verification and retained study

The relevant **143 JUnit tests** pass with no failures, errors or skips. **46 Python tests** pass, covering the design and fold rules, reference attachment, analytic gradients, transfer contracts, paired statistics and archive provenance. Four independent Java/Python checks pass on the actual neural and transfer artifacts, including variable-stage interpolation and three-reference averaging. The mod jar builds successfully, and its Gen2, Gen3 and three local-expert resources match their source hashes. An in-game UI session was not run.

The Gen2 cache rechecks all 68 original cached files. The completed Gen3 study is additionally preserved in `.neural-cache/gen3-19ed5060/study.zip`: **109 files, 272,179,322 uncompressed bytes, 74,991,549 archive bytes**. Every source and decompressed entry was verified with SHA-256 and byte counts. This includes full new recovered profiles, all candidate artifacts, input cohorts, journals, serial measurements and final analysis. It omits the duplicate MLP copy of the training journal, the interrupted sampler trial and analysis drafts. The [Gen3 cache manifest](gen3-cache-manifest.json) records each entry and the archive hash.

Archive entries are relative to `build/neural-gen3`; extracting there restores the study directly. If the MLP training/parity helper needs its duplicate journal, copy `data/cases.jsonl` to `mlp-v1/cases.jsonl` after restoration. `python tools/neural/cache_gen3_campaign.py --verify-only` verifies the archive even after build-directory cleanup; add `--verify-sources` when the restored/live study is present. The Gen2 cache remains separate and unchanged.

## Configuration and evidence

In `config/createcheme-common.toml`:

```toml
[columnV3]
initializerModel = "GENERALIZED_GEN3_EXPERIMENTAL"
initializerMode = "LNN_FIRST"
lnnMaxCorrectionIterations = 16
lnnBudgetMilliseconds = 2000
```

The Gen3 bundled resource SHA-256 is `19ed50603c2955841a9dd8e5a539aa076b1d8d59a09304b2bc0c613cc520e512`. Gen2 remains `f4cab9cb22fcb0e528dbd32c76cf9f41dd3404b80838e97a105260b36c28f380`. Model references and options are captured at admission; immutable static holders publish each family once.

The [methods and exact training commands](gen3-model-methods.md) document the architectures, loss weights, transfer rules, storage sizes and tiny-phase truncation caveat. The [prospective protocol](gen3_protocol.md), [frozen selection](gen3-selection.json), [model card](gen3-model-card.json), [complete case map](gen3-case-map.jsonl.gz), and [Gen2 cache manifest](gen2-cache-manifest.json) retain the quantitative evidence and provenance.

`run_gen3_campaign.ps1` runs the frozen `comparison`, `recovery`, `replay`, `profile`, and `benchmark` stages, refusing to overwrite journals. `analyze_gen3_campaign.py` requires completed runs on the declared populations. `finalize_gen3_report.py` binds the report and archive to the analyzed journal, model, source, budget and selection hashes, and checks archived observation counts. Full teacher/final profiles remain in the local Gen3 journals and immutable Gen2 cache; the compressed tracked map retains exact inputs, cohort memberships and measured outcomes.
