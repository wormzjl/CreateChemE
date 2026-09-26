# eppr78-table-s4-extraction

Batch `2026-09-24-coolprop-low-temperature`, P3 WP2b (E-PPR78 provenance). Offline Node.js scripts (no dependencies); not part of the build or of any Gradle task. Written 2026-09-25 by the lead session.

## Purpose

The Supporting Information of Jaubert, Qian, Lasala and Privat 2022 (Fluid Phase Equilibria 560, 113456, doi:10.1016/j.fluid.2022.113456; Elsevier file `1-s2.0-S0378381222000814-mmc1.docx`, owner-provided, stored as `research/2026-09-24-coolprop-low-temperature/sources/e-ppr78/jaubert-2022-si-mmc1.docx`, SHA-256 `bd068feb989e5d47d02f90960f08ef75a05e82a9a1bb9fe93554c9ec9f4613c1`) prints Table S4, the 40-group E-PPR78 matrix of Akl and Bkl (MPa), as six Word tables whose numeric cells are MathType OLE objects (826 embedded objects), so no text extraction sees the values. These scripts recover them from the WMF preview of each object: MathType writes every text run as a `META_EXTTEXTOUT` record with a `META_MOVETO` position, so the two lines of a cell (`A12 = 65.54` above `B12 = 105.7`) are read back from the run text, the font (Symbol carries `=` and the minus sign) and the y position of each run.

## Files

| File | Role |
|---|---|
| `tables.mjs` | Lists every top-level table of `word/document.xml` with its caption and writes each as TSV (`tables/tableNN.tsv`); Table S4 is tables 04 to 09 |
| `s4.mjs` | For tables 04 to 09: maps every cell to its embedded WMF (through `word/_rels/document.xml.rels`), parses the WMF records (placeable header, `CREATEFONTINDIRECT`, `SELECTOBJECT`, `SETWINDOWORG`, `MOVETO`, `EXTTEXTOUT`, `TEXTOUT`) and writes `tables/s4-cells.json` (mode `json`) or prints the runs of the first cells (mode `dump N`) |
| `compose.mjs` | Builds the matrix from the runs: the largest font is the value line, the `A`/`B` run gives the letter and digits, the Symbol run on the same y gives `=` or `=-` (sign), a lone `0` is the diagonal, `NA` and `-` cells are taken from the cell text; writes `tables/s4-matrix.tsv` and refuses any cell that does not parse |
| `compare.mjs` | Compares `s4-matrix.tsv` with a `group_interactions` record (`eppr78_2022.json`) pair by pair and prints the pilot pairs and every discrepancy |
| `docx-tables-summary.txt` | The `tables.mjs` listing of the 13 tables of the document (S1 to S8) |

## How to run

    # unpack the docx (any zip tool) into a folder DIR, then from this folder:
    node tables.mjs DIR
    node s4.mjs DIR json
    node compose.mjs DIR
    node compare.mjs DIR/tables/s4-matrix.tsv <worktree>/src/main/resources/data/createcheme/materials/group_interactions/eppr78_2022.json report.txt

## Results (2026-09-25)

- 820 lower-triangle cells parsed, 0 refused: 40 diagonal zeros, 356 numeric off-diagonal pairs, 424 `NA`. Output stored as `research/2026-09-24-coolprop-low-temperature/sources/e-ppr78/jaubert-2022-si-table-s4.tsv` (SHA-256 `bd347cdaa070c14b14e3c8c46e00bd8af77079e4ae996b5c055c247f7c417682`).
- Against the record built from the Clapeyron.jl transcription (revision `eppr78-2022-clapeyron-0778184-r1`, 355 pairs): 274 pairs identical, 83 discrepancies (`jaubert-2022-si-table-s4-vs-record-r1.txt` next to the TSV). The six pilot pairs (CH4, C2H6, CO2, N2) are identical. Most discrepancies are transcription rows with A = 0 for whole groups (11 CH cyclic, 18 CH2 alkenic, 20 CH cycloalkenic, 25 CF double bond and others); the rest are shifted or mistyped values (11-12, 11-13 missing, 19-21, 1-27, 5-18, 8-32, 4-24 against 4-25, the Ar row) and five pairs the transcription carries where Table S4 prints NA.
- The record was rebuilt from the TSV by `tools/eppr78-transcription-check/` (P3 WP2b) as revision `eppr78-2022-si-table-s4-r2`.

## Limits

The extraction reads the MathType preview text, not the MTEF equation stream; a cell whose preview were stale would be misread, which the 820-of-820 parse and the 274 agreements with an independent transcription make unlikely. Group names are taken from the table headers as printed (`CHF2-CH3`/`CF3-CH2F` in one sub-table are the same groups 26/27 as `C2H4F2`/`C2H2F4`). No code of this folder was ever in a tracked path; nothing to re-attach.
