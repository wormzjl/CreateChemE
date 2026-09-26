# Methane ideal-gas heat capacity: data and outputs (P3 WP10)

Batch `2026-09-24-coolprop-low-temperature`, stage P3, work package WP10. Report: `documentation/2026-09-24-coolprop-low-temperature/P3_REFERENCES_WP8_WP10.md` part B. Script: `tools/methane-ideal-gas/methane_cp.py` (README there). Fetched 2026-09-24 by the WP10 agent in worktree `claude/coolprop-multiphase-thermo-37f6b0`. Research material, git-ignored; nothing here is on the mod's classpath.

Licences. The owner has stated that the project is non-commercial, so non-commercial terms are admissible; they are recorded per file for a later distribution decision. ExoMol data are CC BY-SA 4.0: a NASA 9 fit derived from them and bundled in the mod may be "adapted material" under the share-alike clause (open item in the report).

## Downloaded files

`sha256sum` of the stored file; sizes in bytes.

| File | Bytes | sha256 | Source, licence, purpose |
|---|---|---|---|
| `exomol/12C-1H4__MM.states.bz2` | 192993210 | `b02420203f068fee1d753d62bc0e9a6ec918dc1616f4b06679b56e9c60d4d954` | https://www.exomol.com/db/CH4/12C-1H4/MM/12C-1H4__MM.states.bz2 (server Last-Modified 2026-01-27). ExoMol MM line list of 12CH4, version 20240113: 9 155 208 states to 18 000 cm-1, J <= 60 (Yurchenko, Owens, Kefala and Tennyson 2024, MNRAS 528, 3719, doi:10.1093/mnras/stae148). CC BY-SA 4.0 (https://www.exomol.com/data/licence/; cite the data paper). The direct-summation input. Large: the lead may leave it out of the main-checkout copy and re-download it (URL and hash here) |
| `exomol/12C-1H4__MM.pf` | 125000 | `b399c44980d139b11bfb374a033f8c51bb42c12e5ba9bfd1726156b9d441bb7d` | https://www.exomol.com/db/CH4/12C-1H4/MM/12C-1H4__MM.pf; partition function 1..5000 K in 1 K steps; CC BY-SA 4.0; cross-check of the direct sum and of numerical differentiation |
| `exomol/12C-1H4__MM.def` | 14197 | `c76449071900b94043128a74575912e20801222f49826ab1d5a6026f1bcff5bb` | Same folder, `.def`; dataset definition (nuclear-spin weights A1 5, A2 5, E 2, T1 3, T2 3; states file columns) |
| `exomol/12C-1H4__MM.def.json` | 9400 | `2242a9650ec5db9ed81e36a43c64b1c1170f29b03ba00e8b81a03bfdf95b06c5` | Same folder, `.def.json`; version 20240113, doi, max energy 18 000 cm-1 |
| `exomol/12C-1H4__YT10to10.pf` | 26000 | `0bd737de88f7a99ad14e654b6235aa79dd1d9fc30aecae7e5afcfb13819dd173` | https://www.exomol.com/db/CH4/12C-1H4/YT10to10/12C-1H4__YT10to10.pf; older line list (Yurchenko and Tennyson 2014); byte-identical to the YT34to10 `.pf` |
| `exomol/12C-1H4__YT10to10.cp` | 52000 | `1a2fc21cc700dd9e347efb862418edf5635ef7a3d93d52efcf23d2f5fd618b1a` | Same folder, `.cp`; ExoMol's specific-heat file of that list, for the record |
| `exomol/12C-1H4__YT10to10.def` | 22599 | `67f0169845917ac0ccaa2ed6e5cb6dcc874d5f4328880a4f580abb470920c8a7` | Same folder, `.def` |
| `exomol/README-10to10.txt` | 9057 | `b222737208d4a25d792067b963502e49a45484c86c3ae5291608aa89784d210c` | Same folder; the 10to10 readme |
| `exomol/12C-1H4__YT34to10.pf` | 26000 | `0bd737de88f7a99ad14e654b6235aa79dd1d9fc30aecae7e5afcfb13819dd173` | https://www.exomol.com/db/CH4/12C-1H4/YT34to10/12C-1H4__YT34to10.pf (Yurchenko, Amundsen, Tennyson and Waldmann 2017) |
| `exomol/12C-1H4__YT34to10.cp` | 25457 | `b930a82dd68ece4879032645f526c64a672ec81ffe0d7c9bce8fee6428132b0b` | Same folder, `.cp` (3 K steps) |
| `exomol/12C-1H4__YT34to10.def` | 22493 | `227ca0dba00bfa4fb6d33fe7ba223bf7f55215b4c164c7fb7d3470b44a7a3d13` | Same folder, `.def` |
| `hitran/q32.txt` | 89998 | `114694a9c8f5faa79880bb016eff165dabe020b1d96f089d6ef585ca4fb9bc76` | https://hitran.org/data/Q/q32.txt (server Last-Modified 2025-12-11): HITRAN TIPS total internal partition sum of 12CH4 (global isotopologue 32), 1..2500 K. HITRAN terms: free for research use with citation (Gamache et al., TIPS); cross-check only |
| `janaf/C-067.txt` | 3882 | `e8a42df0abf0ce2b706feba0ecf13d8da31f5b5dd1713657838e20384b81fa1f` | https://janaf.nist.gov/tables/C-067.txt: NIST-JANAF Thermochemical Tables, methane (Chase 1998, JPCRD Monograph 9). NIST Standard Reference Data, free online; holdout values |
| `literature/wenger-champion-boudon-2008-jqsrt-109-2697-hal-00277904v2.pdf` | 457263 | `b927498f7ed4ce99bc435c62add1e78f1fb976b0d5f4326f30ff50ef7cabe2c3` | https://hal.science/hal-00277904v2/document: author version of Wenger, Champion and Boudon, "The partition sum of methane at high temperature", JQSRT 109 (2008) 2697, doi:10.1016/j.jqsrt.2008.06.006. HAL deposit, local research copy only. Table 4 (page 10) is transcribed into the script (`WENGER_TABLE4`, 100..3000 K) |
| `literature/yurchenko-2024-mnras-528-3719-mm-ucl-stae148.pdf` | 1956165 | `5e8ac127718407b6e38b5295792cdf24d2de3f70d8200cd0caca6d6c00107977` | https://discovery.ucl.ac.uk/id/eprint/10186044/1/stae148.pdf: the MM data paper, CC BY 4.0 (stated on the article). Section 5.2: MM partition function "complete" to about 2000 K against TIPS 2021 |

Not fetched: the Wenger et al. online code (the paper's URL http://icb.u-bourgogne.fr/JSP/TIPS.jsp), and Nikitin et al. 2015 (JQSRT 167, 53; publisher only).

## Inputs copied from the repository

| File | Bytes | sha256 | Content |
|---|---|---|---|
| `inputs/spine-methane-r1-at-5100233.json` | 4117 | `de27372e610ff90783337fd89eb7892fb5f35d6e09d0553c7ac48c55059adbbb` | `git show 5100233:src/test/resources/data/createcheme/materials/spine/methane.json`: the r1 spine record (Setzmann-Wagner terms, CEA coefficients, S and DfH) the script compares against. A copy, so the script does not depend on files other agents edit (the record now also lives in `src/main/resources/...`, identical on 2026-09-24) |

## Outputs (`outputs/`)

Written by `python tools/methane-ideal-gas/methane_cp.py --data research/2026-09-24-coolprop-low-temperature/methane-ideal-gas --sources research/2026-09-24-coolprop-low-temperature/sources --out research/2026-09-24-coolprop-low-temperature/methane-ideal-gas/outputs` (and `--fit-max 1500 --out .../outputs/fit-1500`), CoolProp venv Python 3.12.13 with numpy 2.5.3, about 75 s each.

| File | Bytes | sha256 | Content |
|---|---|---|---|
| `outputs/methane_cp_log.txt` | 7889 | `37e8920a6e80aa662bf46f0debcc145f0f8adc62b3dc1600146f7e2b79eba845` | The printed report: partition-sum cross-checks, tail and Wenger fits, join scan, fit statistics, S(298.15), the Cp table, deviations, h(T) - h(298.15) |
| `outputs/methane_cp_table.json` | 18823 | `8b43a0037de61b643ef9f54e0cf56f00cd348b7d4412f6a2adc974d40e626f14` | Every column of the table at 15 temperatures, the tail model, the Wenger fits, the proposed segment |
| `outputs/spine-methane-r2-segment-425-1300.json` | 886 | `8207c078cf6c7f6d6dd0ef14394e8ed15a18ac474e58ac1baf5aab6933ecb7d2` | The proposed `nasa9` segment (D13 option C) |
| `outputs/fit-1500/methane_cp_log.txt` | 7891 | `6ff9c1c4910e8e82f712214c40b423fa5252c2ca77df258503eb760751995a3b` | Same, fit to 1500 K (variant) |
| `outputs/fit-1500/methane_cp_table.json` | 18854 | `371227ef064a68d12ec380ca0122e9a19fad55d452b2a7864ce17d454c136e07` | Same, variant |
| `outputs/fit-1500/spine-methane-r2-segment-425-1500.json` | 888 | `aa58ce76eaf4267fafef1ddbd31cefeb1555b0e97e50d37f16b1aee4920ffa48` | The 425..1500 K variant segment |

Two runs of the same command give identical outputs (the computation is a fixed sequence of numpy sums).
