# eppr78-transcription-check

Batch `2026-09-24-coolprop-low-temperature`, P3 WP2 (E-PPR78 group-interaction data), extended in P3 WP2b (2026-09-25, the publisher's Table S4). Offline, standard-library Python; not part of the build or of any Gradle task.

## Purpose

1. `build_group_interactions_s4.py` builds the bundled record `src/main/resources/data/createcheme/materials/group_interactions/eppr78_2022.json`, revision `eppr78-2022-si-table-s4-r2`, from `jaubert-2022-si-table-s4.tsv`, the extraction of Table S4 of the Supporting Information of Jaubert, Qian, Lasala, Privat 2022 (Fluid Phase Equilibria 560, 113456, doi:10.1016/j.fluid.2022.113456) made by `tools/eppr78-table-s4-extraction`. It checks the TSV's SHA-256 (and the docx's and the article PDF's when they lie next to it) against `research/2026-09-24-coolprop-low-temperature/sources/MANIFEST.md` section 5, checks that the TSV is the complete lower triangle of the 40 groups (820 cells, diagonal 0, A and B both numeric or both NA, every printed group label as listed in the script), maps group number k to the scheme `eppr78-2022` name of `science.material.Eppr78Groups` (names unchanged), and writes one pair per numeric off-diagonal cell (356), first = the lower-numbered group, ordered by (first, second), A and B with the table's decimal text. NA cells are absent. `--check` compares with an existing record byte for byte (CRLF normalised, for a `core.autocrlf` checkout).
2. `build_group_interactions.py` is the WP2 builder of revision `eppr78-2022-clapeyron-0778184-r1` from the Clapeyron.jl transcription `EPPR78_unlike.csv` (MANIFEST.md section 4). History only: it still reproduces the r1 record of commit `7afa990` byte for byte; `check_pilot_kij.py` imports its CSV reader.
3. `check_pilot_kij.py`, three sections:
   1. the record against Table S4: every numeric off-diagonal cell, and nothing else, with the identical decimal text and the builder's group numbers and names;
   2. Table S4 against the Clapeyron transcription, row by row over the 780 off-diagonal cells, each classed as identical (same numbers), a discrepancy (A = 0 with the printed B, A = 0 with another B, another B, another A, missing from the transcription, NA in Table S4) or absent from both; a transcription value Table S4 prints in another cell is named (a shifted row);
   3. the appendix A reproduction: kij(T) of the six pilot pairs at 100, 150, 200, 250, 300, 400, 600 and 900 K from the record's values with the kernel's PR78 (Omega_a 0.45724, Omega_b 0.07780, R = 8.31446261815324), once with the ethane constants the design's appendix A used (CoolProp: 305.322 K, 4.8722 MPa, 0.0990) and once with the pilot record's (305.32 K, 4.872 MPa, 0.099), each cell compared with appendix A of `P3_PILOT_ENGINE_PLAN.md` at four decimals.

   Output: `pilot-kij-output.txt`. Exit code 0 when section 1 finds nothing.

## How to run

From the worktree root, Git Bash, any Python 3.10+ (on the owner's machine `python` is the Windows Store stub; use `C:/Users/wormz/.local/bin/python3.12.exe`). The sources lie in the main checkout:

    SRC=D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-coolprop-low-temperature/sources/e-ppr78
    python tools/eppr78-transcription-check/build_group_interactions_s4.py \
        --tsv $SRC/jaubert-2022-si-table-s4.tsv \
        --out src/main/resources/data/createcheme/materials/group_interactions/eppr78_2022.json [--check]
    python tools/eppr78-transcription-check/check_pilot_kij.py \
        --tsv $SRC/jaubert-2022-si-table-s4.tsv --csv $SRC/clapeyron/EPPR78_unlike.csv \
        --record src/main/resources/data/createcheme/materials/group_interactions/eppr78_2022.json \
        > tools/eppr78-transcription-check/pilot-kij-output.txt
    # history: the r1 record from the transcription
    python tools/eppr78-transcription-check/build_group_interactions.py --csv $SRC/clapeyron/EPPR78_unlike.csv --out <file>

## Results (2026-09-25, WP2b, revision `eppr78-2022-si-table-s4-r2`)

- Inputs verified: `jaubert-2022-si-table-s4.tsv` SHA-256 `bd347cdaa070c14b14e3c8c46e00bd8af77079e4ae996b5c055c247f7c417682`; `jaubert-2022-si-mmc1.docx` (2,837,469 bytes) SHA-256 `bd068feb989e5d47d02f90960f08ef75a05e82a9a1bb9fe93554c9ec9f4613c1`; `jaubert-2022-fpe-560-113456-publisher.pdf` (3,921,337 bytes) SHA-256 `f63f0da9d5ddf3a641714fb2343a4f1bc8c866dd185c463e4ee2ac6c4b817618`.
- Record: 40 groups, 356 pairs, 31,966 bytes, SHA-256 `426b821fb99bf90bd90d8892c5bfb049a20bad4f599bae62cacd6a81bacdd182` (LF bytes), git blob `d9eef7f3063d717c7c68a49612a294e03b2d3f6b`; `--check` identical. The per-group `transcription_label` of r1 is dropped (no loader or test read it).
- Section 1: 0 mismatched or missing cells, 0 pairs not in Table S4.
- Section 2: 274 pairs identical (55 of them differ in decimal text only, trailing zeros such as 575.0 against 575), 83 discrepancies, 423 cells absent from both:
  - 75 carried A = 0 with the printed B (by group: 18 CH2_alkenic 29, 11 CH_cyclic 19, 20 CH_cycloalkenic 13, 8 Caro 9, 25 CF_double_bond 9, the rest 1 to 4);
  - 3 carried A = 0 with another B: 5-18 (B 60.29, printed 68.29), 8-32 (2259.4, printed 2559.4), 11-12 (389.8, which is Table S4's B of 11-13);
  - 2 carried another B: 1-27 (3565, printed 356.5), 19-21 (-495.5, which is Table S4's B of 20-23);
  - 2 were missing: 4-24 (C/CF2, 479.0 / 1430) and 11-13 (CH_cyclic/N2, 331.5 / 389.8);
  - 1 is NA in Table S4: 4-25 (C/CF_double_bond, A = 0, B = 1430, Table S4's B of 4-24: the 4-24 cell shifted one column).
  - The six pilot pairs (5-6, 5-12, 5-13, 6-12, 6-13, 12-13) are identical.
- Table S4 has no pair with A = 0 and B != 0; one pair, CF3/CF2 (23/24), is printed A = B = 0.000, a zero term the loader drops. The loader's refusal of an A = 0, B != 0 term stays as a guard.
- Section 3: unchanged, 48 of 48 cells with the appendix constants (largest difference 4.9e-5), 46 of 48 with the pilot record's (ethane/nitrogen at 200 K 0.04056 and 300 K 0.02625).
- Java: `GroupContributionInteractionsTest.bundledMatrixCarriesTheFortyGroupsOfTheSchemeAndTheTableS4Pairs` asserts the revision, 356 pairs, six cells r1 had wrong, and the zero-A census; `reproducesAppendixA` asserts section 3.

## Results (2026-09-24, WP2, revision `eppr78-2022-clapeyron-0778184-r1`, history)

- Record: 40 groups, 355 pairs, SHA-256 `d7d1e977209b58449f7bc6d6dc60528ad7da97a7911513c36e2ac992be25661d` (LF bytes); the transcription check found 0 mismatched rows, 0 extra pairs.
- Appendix A as above. The appendix was computed with CoolProp's ethane constants, not the bundled record's as its caption says.

## Provenance and limits

The TSV is the lead's extraction of the MathType objects of the publisher's docx (`tools/eppr78-table-s4-extraction`, which has its own README); this folder checks the TSV's hash and completeness, not the WMF parsing. The docx, the PDF and the TSV were provided by the owner and are not redistributed; the article's first page states open access under CC BY 4.0 (© 2022 The Author(s), published by Elsevier B.V.), the Supporting Information docx states no licence of its own, and the record carries the values with a citation. Table S4 repeats some cells as printed (8-11 equals 9-11, 18-23 equals 18-24, A of 1-18 equals A of 5-18, and nine pairs have A = B); they are taken as printed. The Clapeyron.jl CSV (commit `0778184`, MIT) is kept only for the comparison.

No code of this folder was ever in a tracked path; nothing to re-attach.
