# spine-join-measurement

Offline scripts of P2 (data side) of batch `2026-09-24-coolprop-low-temperature` (`documentation/2026-09-24-coolprop-low-temperature/P2_DATA_SPINE.md`). They measure where CoolProp's ideal-gas Helmholtz terms and the NASA CEA polynomials meet, and build the four reference-spine test records from the sources. Nothing here is on a Gradle path; no code was removed from a tracked path.

Run with Node 22 from this folder. They read the CoolProp fluid files bundled in the worktree (`src/test/resources/science/thermo/coolprop/`) and the git-ignored sources of the main checkout (`research/2026-09-24-coolprop-low-temperature/sources/`: `nasa-cea/cea-species-extract.txt`, `coolprop/hformation-atct-9b35f538.json`). Paths are relative to this folder's position in a worktree (`tools/spine-join-measurement/`) plus the absolute main-checkout path in `build-spine-records.mjs`.

| Script | Purpose | Output |
|---|---|---|
| `cp-overlap.mjs` | CoolProp alpha0 Cp against the CEA first interval on a 25 K grid over each overlap | the table behind the join choice |
| `cea-holdouts.mjs` | (R updated to 8.314510 in P3 WP9a; P2 copy in `previous/`) CEA second-interval Cp against JANAF (N-023, C-067, C-095) and the NIST WebBook Gurvich ethane table at 1000, 1100 and 1200 K | D7 holdout deviations |
| `build-spine-records.mjs` | P3 WP9a version (the P2 version is in `previous/`). Keeps the P2 joins of N2, C2H6 and CO2 (600, 500, 975 K) and prints what the P2 rule (smallest Cp step on the 25 K grid) would now pick; uses CEA's gas constant 8.314510 (nasa/cea `source/param.f90.in` line 32) for the CEA segments and standard entropies; reproduces WP3's CO2 sub-triple segment; builds methane r2 from the CoolProp Setzmann-Wagner terms to 425 K and the GERG-2008 ideal part parsed from `research/.../sources/nist-aga8/GERG2008.cpp` (lines printed) to 1200 K, and prints the methane holdout against the WP10 line-list table (`research/.../methane-ideal-gas/outputs/methane_cp_table.json`, measurement only). With `--write` it writes `src/main/resources/data/createcheme/materials/spine/{nitrogen,methane,ethane,carbon_dioxide}.json` | join steps, the SW-to-GERG step grid, the methane holdout, S(298.15) against JANAF |
| `fit-pilot-co2-cp.mjs` | least-squares degree-5 fit in (T - 298.15 K) of CO2's Span-Wagner ideal-gas Cp over 216.592..900 K, the `shifted_polynomial_5` placeholder of `properties/pilot_carbon_dioxide.json` | coefficients, worst deviation 0.13 % |

Re-running `node build-spine-records.mjs --write` reproduces the committed spine records (P3 WP9a: N2, C2H6 r2, CH4 r2, CO2 r3) byte for byte. It reads the research sources of the main checkout (`D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-coolprop-low-temperature/`), including `methane-ideal-gas/outputs/` for the holdout print.

`previous/` holds the P2 versions of `build-spine-records.mjs` and `cea-holdouts.mjs` (R = 8.314472, test-resource output path), which reproduce the P2 records at `dc82327` when copied back into this folder (they resolve the worktree two levels up).
