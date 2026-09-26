# methane-ideal-gas

Offline computation of the ideal-gas heat capacity of methane from the ExoMol MM line list (direct summation over its 9.16 million levels), its comparison with every other source the batch has (JANAF, GERG-2008 ideal part, CoolProp Setzmann-Wagner, NASA CEA / Gurvich 1991, Wenger et al. 2008, HITRAN TIPS, a rigid-rotor harmonic-oscillator bound), and the NASA 9 fit proposed for the methane spine above 425 K (decision D13).

- **Batch:** `2026-09-24-coolprop-low-temperature`, stage P3, work package WP10 (`documentation/2026-09-24-coolprop-low-temperature/P3_PILOT_ENGINE_PLAN.md` section 5 and appendix B). Report: `documentation/2026-09-24-coolprop-low-temperature/P3_REFERENCES_WP8_WP10.md` part B.
- **Data and outputs:** `research/2026-09-24-coolprop-low-temperature/methane-ideal-gas/` (README there: URLs, sizes, sha256, licences).
- **Status:** a one-off study script. No Gradle task runs it; nothing of it is tracked. If D13 is adopted, WP3 or WP9 copies the segment of `outputs/spine-methane-r2-segment-425-1300.json` into the methane spine record by hand (report section B.6).

## Files

| File | Purpose |
|---|---|
| `methane_cp.py` | The study (sha256 `34e4ebc04fb3fdad80aeceab43bdabfedbb5521a3eb420d1a65fe41dfcf635d4`) |
| `README.md` | This file |

## How to run

From the worktree root, with the throw-away CoolProp venv of the batch (only numpy is needed):

```
uv venv "$TEMP/coolprop-probe-venv"
uv pip install --python "$TEMP/coolprop-probe-venv" numpy
M=research/2026-09-24-coolprop-low-temperature/methane-ideal-gas
"$TEMP/coolprop-probe-venv/Scripts/python.exe" tools/methane-ideal-gas/methane_cp.py \
    --data $M --sources research/2026-09-24-coolprop-low-temperature/sources --out $M/outputs
"$TEMP/coolprop-probe-venv/Scripts/python.exe" tools/methane-ideal-gas/methane_cp.py \
    --data $M --sources research/2026-09-24-coolprop-low-temperature/sources --fit-max 1500 --out $M/outputs/fit-1500
```

Each run takes about 75 s (parsing the 193 MB bz2 states file, then about 250 Boltzmann sums over 9.16 million levels). If the states file is absent, download it first (URL and sha256 in the data README). Environment of the recorded outputs: Python 3.12.13, numpy 2.5.3, Windows 11, 2026-09-24.

Inputs read: `$M/exomol/12C-1H4__MM.states.bz2`, `.pf`, the two older `.cp` files, `$M/hitran/q32.txt`, `$M/janaf/C-067.txt`, `$M/inputs/spine-methane-r1-at-5100233.json` (or `--spine`), and `sources/nist-aga8/GERG2008.cpp` (methane `n0i`, `th0i`).

## Method

- **Direct sum.** Q = sum g exp(-c2 E/T), with E and g (nuclear-spin weights included) from the states file, c2 = 1.438776877 cm K. Cp = 5/2 R + R (c2/T)^2 var(E), the variance of the level energy under the Boltzmann distribution (two-pass sums in float64, no numerical differentiation). H(T) - H(0) = 5/2 RT + R c2 <E>. R = 8.314462618 J/(mol K).
- **Missing levels.** The file holds every level below 18 000 cm-1 (J <= 60). Its level density follows a power law (E + 4250)^7.77 from 10 000 to 15 000 cm-1 (rms 0.8 % per 100 cm-1 bin) and falls below it from about 15 500 cm-1 (0.80 of the law at 17 550 cm-1). The reference adds the deficit above 15 000 cm-1 and the law up to 36 100 cm-1 (D0). A second estimate reads the excess of Wenger et al.'s partition sum over the direct sum at 1300 to 2000 K as missing levels with one activation temperature (five fit windows) and gives the Cp they imply, R f (theta/T)^2. The declared reference error runs from the plain direct sum (a strict lower bound) to the larger of the two estimates, plus 1.8e-4 relative for level-structure differences (Wenger against MM at 300 to 1200 K).
- **Method checks.** The direct sum reproduces the ExoMol `.pf` to 2.2e-6; Cp from local polynomial fits of ln Q of the `.pf` (degree 6 over +-50 K, degree 4 over +-25 K) reproduces the direct-sum Cp to 2.5e-6 and 7.0e-6 relative.
- **Other sources.** JANAF: the downloaded C-067 table and the NIST Shomate fit; GERG-2008: `GERG2008.cpp`'s own Cp0 formula with its gas constants; Setzmann-Wagner and CEA: the r1 spine record; RRHO: classical spherical top plus harmonic oscillators at the observed fundamentals (2916.48, 1533.33 x2, 3019.49 x3, 1310.76 x3 cm-1), a sanity bound only.
- **Join and fit.** The join with the Setzmann-Wagner segment is the 25 K grid point with the smallest Cp step (P2's rule), 425 K. The NASA 9 segment is a relative least-squares fit of Cp/R on a 5 K grid from the join to `--fit-max`; b1 and b2 are set so that the segment continues h and s of the spine at the join (the loader recomputes both and ignores them).

## Detachment

Nothing of this tool is in a tracked path. When the batch merges, the lead copies this folder to the main checkout's `tools/methane-ideal-gas/`, the data folder to the main checkout's `research/2026-09-24-coolprop-low-temperature/methane-ideal-gas/` (the 193 MB states file optional), and adds the `tools/INDEX.md` and `research/INDEX.md` rows.
