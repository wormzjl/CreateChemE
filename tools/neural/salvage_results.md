# Original-matrix salvage results

The frozen current Transformer completed one LNN_FIRST request for all 2,793 admitted original conditions using exactly ten owned workers. It added 101 novel strictly certified TRAIN labels: N=805 becomes N+1=906 (+12.55%). Existing N records remain the exact prefix, including their original provenance. No original validation or test labels were replaced.

| Fold | Swept | Strict outcomes | Entered N+1 as new labels |
|---|---:|---:|---:|
| TRAIN | 1,993 | 752 | 101 |
| Validation | 405 | 161 | 0 |
| Test | 395 | 164 | 0 |

The other 651 strict TRAIN outcomes matched existing N inputs and were not appended. N includes 712 inputs in the swept population and 93 outside it; all 805 were retained, including 61 existing labels that did not reproduce a strict outcome in this bounded attempt. There were zero duplicate canonical inputs, zero material root discrepancies, and zero new-root quarantines. The 35 historical challenge records are outside the admitted original matrix and were not swept. All 835 common nontraining records remain unchanged, including 168 certified validation references.

All 101 added labels have native solve path `lnn/requested-state` and condenser branch `TWO_PHASE`. They span 34 distinct stage counts from 9 to 64; 53 have steam feeds and 48 do not. They cover zero through four pumparounds and zero through three side draws. All added labels are dry-equilibrium profiles; N and N+1 contain no wet-qualified profiles. The campaign excluded 72 advisory successes from label promotion (TRAIN 57, validation 9, test 6).

Elapsed native campaign time was 1,326.288 seconds (22.1 minutes) under concurrent load. Runtime scheduling recorded maximum active workers=10, maximum in-flight cases=10, distinct worker threads=10, and terminated=true. Budgets stayed fixed at 30 seconds per request, 2 seconds for neural correction, and 16 neural iterations. All failures are retained in the journal; there were no retries or execution-failure recovery runs.

Use these training inputs:

- `build/neural-salvage/nplus1-v1/N-cases.jsonl`: SHA-256 `85c668f6162e2af819bda5110750fa5bb71a3769b4b813070cfe140e3eabbe62`.
- `build/neural-salvage/nplus1-v1/Nplus1-certified-cases.jsonl`: SHA-256 `1dc86a2590ad5da7f37d1735dc08abd3b38077b64628229cb014147d1a536a06`.

Train-only files are `N.jsonl` and `Nplus1-certified.jsonl`. The preserved registered union is `Nplus1.jsonl`; its certified counterpart only adds explicit native-run/journal provenance to novel rows. Existing N rows, labels, ordering, and eligibility are identical. `new-train.jsonl` contains the 101 original registered additions.

The complete archive is `.neural-cache/salvage-nplus1-v1/certified-campaign.zip`, SHA-256 `e41c02d5f85b2cf4140d2d21bc9465e027905c57cea78a1c91192ade1207fa5b` (106,762,904 bytes). Registration and append-only partial checkpoints are also preserved there. `salvage_dataset_manifest.json` points to the archive and ready datasets; `salvage_verification.json` records final verification. Detailed status/fold/coverage counts and provenance are in the archived `dataset-manifest.json`, `certified-dataset-manifest.json`, `acquisition-summary.json`, and audit journals.

Checks passed: seven Python strict-label/source contracts; native executor lifecycle suite; observed ten-worker one-pass contract; 71 source and 127 code hashes; exact N prefix; canonical uniqueness; no held-out training overlap; same-input native seeds; unchanged common validation labels; explicit new-label model/run hashes; ZIP CRC and all 22 campaign artifact hashes. No frozen sources, build.gradle, original datasets, models, or manifests were edited. No commits were made by this subtask.
