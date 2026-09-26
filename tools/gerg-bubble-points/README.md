# gerg-bubble-points

Offline builder of the GERG-2008 bubble- and dew-point reference fixture for the six pilot binaries, and of the comparison of CoolProp's GERG-2008 binary parameters with NIST's `GERG2008.cpp`.

- **Batch:** `2026-09-24-coolprop-low-temperature`, stage P3, work package WP8 (`documentation/2026-09-24-coolprop-low-temperature/P3_PILOT_ENGINE_PLAN.md` sections 4.5 and 8.2 fixture F2). Report: `documentation/2026-09-24-coolprop-low-temperature/P3_REFERENCES_WP8_WP10.md` part A.
- **Output (tracked):** `src/test/resources/science/thermo/gerg2008/pilot-binaries.json`. The gate (WP9) reads it without Python (decision D4).
- **Status:** a one-off builder. It is not run by any Gradle task; it stays git-ignored under `tools/` (AGENTS.md, "Test classes and tools after a batch").

## Files

| File | Purpose |
|---|---|
| `gerg_bubble_points.py` | The builder: parameter comparison, isotherm traces, validation, fixture writer (sha256 `67d2d86eb5c355855d7a023916ba00782e25909b6497077735e2085502faceae` at the fixture commit) |
| `README.md` | This file |

## How to run

No persistent Python install. From the worktree root, in a throw-away `uv` environment:

```
uv venv "$TEMP/coolprop-probe-venv"
uv pip install --python "$TEMP/coolprop-probe-venv" coolprop==8.0.0 numpy
"$TEMP/coolprop-probe-venv/Scripts/python.exe" tools/gerg-bubble-points/gerg_bubble_points.py \
    --gerg-cpp research/2026-09-24-coolprop-low-temperature/sources/nist-aga8/GERG2008.cpp \
    --departure research/2026-09-24-coolprop-low-temperature/sources/coolprop/mixtures/mixture_departure_functions.json \
    --out src/test/resources/science/thermo/gerg2008/pilot-binaries.json
```

It runs in about 3 s and prints a per-pair summary (grid size, bubble and dew rows, failure classes, parameter comparison).

Environment of the committed fixture: CoolProp 8.0.0 wheel, git revision `ae81610e7d23efc57f9d051c8e70a4d66e87537f`, Python 3.12.13, numpy 2.5.3 (not used by the builder), uv 0.11.28, Windows 11 x86-64, 2026-09-24.

**Byte-for-byte reproduction.** Two consecutive runs gave the same file, sha256 `61d8b234969e77195964d6cf862aacb439537dfaf269ec39798a345639d21548`, 118 295 bytes, ASCII. The file records the Python version and the platform string, so another platform changes those two fields. The numbers are written with 10 significant digits; CoolProp's own convergence is coarser (full-precision validation residuals at most 1.3e-7 in pressure and 1.0e-7 in ln f, recorded per pair in `validation_at_full_precision`), so a CoolProp build on another platform may move the last digits. Recomputing P from the rounded (T, rhoL, x1) of a row reproduces it only to about 2e-5 relative, because a liquid's pressure is stiff in its density: a gate should compare P, y1 and K values, not re-derive P from rhoL.

## Inputs and provenance

| Input | Where | Provenance |
|---|---|---|
| CoolProp 8.0.0 | PyPI wheel | MIT; `CoolProp.__gitrevision__` = `ae81610e7d23efc57f9d051c8e70a4d66e87537f` (the build the P0 manifest pins). Binary parameters are read at run time with `get_mixture_binary_pair_data`; departure functions are embedded in the wheel |
| `GERG2008.cpp` | `research/2026-09-24-coolprop-low-temperature/sources/nist-aga8/` | usnistgov/AGA8 `3bdb9ab8ff317c618b0b59d1b704c2c86ddc5fce`, sha256 `901c03cd98263acae8d10480f9ada67ea9bbdf1a5a1b2f1b4a862a493266dec1`, NIST notice (sources `MANIFEST.md` section 1.3) |
| `mixture_departure_functions.json` | `research/.../sources/coolprop/mixtures/` | CoolProp `ae81610e`, sha256 `af00484c631a84e04f92cf8baf8f3468ce75dfc401e85d5c8108345c21090f35` (manifest section 1.2); the departure-function coefficients are compared from this file, which is the file the wheel was built from |
| GERG TM15 (uncertainty statements, not read by the script) | `research/2026-09-24-coolprop-low-temperature/gerg-bubble-points/literature/gerg-tm15-2007-kunz-klimeck-wagner-jaeschke.pdf` | Kunz, Klimeck, Wagner and Jaeschke, "The GERG-2004 wide-range equation of state for natural gases and other mixtures", GERG Technical Monograph 15, VDI Fortschritt-Berichte 6/557, 2007; https://www.gerg.eu/wp-content/uploads/2019/10/TM15.pdf, fetched 2026-09-24, 8 510 478 bytes, sha256 `eed3a0ded9d7fbd298c2a72c3ab1de8fde69da89407ce1034246b84f0e93dfde`; free download from GERG, copyright GERG/VDI, local research copy only |

## Method

1. **Parameter comparison.** For each pair the script reads `betaT`, `gammaT`, `betaV`, `gammaV`, `F` from CoolProp at run time and the literal `btij`, `gtij`, `bvij`, `gvij`, `fij` assignments of `SetupGERG()` in `GERG2008.cpp`. CoolProp may store a pair in the opposite order; beta is then compared as 1/beta (beta_ji = 1/beta_ij; gamma and F are symmetric). For the five pairs with a departure function it compares every `n`, `d`, `t`, `eta`, `epsilon`, `beta`, `gamma` of CoolProp's departure function with `nijk`, `dijk`, `tijk`, `cijk`, `eijk`, `bijk`, `gijk` of `GERG2008.cpp`, and checks that `SetupGERG()` folds the exponent as `-c(delta - e)^2 - b(delta - g)`, the form of CoolProp's `GERG-2008` departure type.
2. **Points.** For each pair and temperature, two isothermal traces (liquid composition for bubble points, vapour composition for dew points) in the mole fraction of the more volatile component, from 0.01 to 0.99 in 0.01 steps plus the grid points, each point seeded by the previous one (`update_with_guesses`, `QT_INPUTS`); failed steps are halved down to 1/64; a trace ends at the first composition it cannot reach. The grid rows are the trace points at the grid compositions.
3. **Validation.** Every grid row is recomputed from (T, rho, composition) with the phase imposed: both phase pressures within 1e-6 of P and ln f equal within 1e-6 per component; rhoL/rhoV >= 1.01 during the trace; plus a binary spinodal test of the liquid (d ln f1/d x1 > 0 at fixed T and P).
4. **Failures.** Grid points the trace did not reach are listed, not filled: `beyond_isotherm_end` when the bubble trace ended with rhoL/rhoV < 1.5 (at the mixture critical point) or the dew composition is richer in the light component than any equilibrium vapour of the bubble trace; `coolprop_error` otherwise; `invalid_solution` for a reached point that failed validation (none in the committed fixture).

The fixture's own `method` block states the same rules, and its `isotherm_ends` records where each trace stopped and why.

## Fixture schema (`createcheme.gerg2008.pilot-binaries/1`)

Top level: `schema`, `description`, `model`, `generator` {tool, command, coolprop_version, coolprop_gitrevision, python, platform, gerg2008_cpp, gerg2008_cpp_exponent_form_found}, `units`, `method` {flash, validation, digits, flags, failure_classes, isotherm_ends}, `d7_targets` {bubble_pressure_aad 0.10, bubble_pressure_point 0.20, abs_dy 0.02, abs_ln_k 0.15, ln_k_species_threshold 1e-3, scope}, `pairs`.

Each entry of `pairs`:

| Field | Content |
|---|---|
| `pair`, `component1`, `component2`, `cas1`, `cas2` | Label (N2/CH4, N2/C2H6, CH4/C2H6, CO2/N2, CO2/CH4, CO2/C2H6) and CoolProp names; `x1`, `y1` are always mole fractions of `component1` |
| `temperatures`, `x1_grid` | The grid (K; x1 in {0.05, 0.1, 0.2, 0.3, 0.5, 0.7, 0.9, 0.95}) |
| `traced_from` | Which pure component the traces start from |
| `parameter_comparison` | Per parameter: the `GERG2008.cpp` value, CoolProp's value, CoolProp's value in GERG order, relative difference; the departure-function comparison; `identical` |
| `isotherm_ends` | Per kind and T: last light fraction reached, its P and rhoL/rhoV, the failing step's error, failed start points, the richest vapour of the bubble trace |
| `validation_at_full_precision` | Largest relative pressure residual and ln f residual of the pair's rows before rounding |
| `bubble` | Rows {T, x1, P, y1, rhoL, rhoV, flags}: T in K, P in Pa, densities in mol/m3 |
| `dew` | Rows {T, y1, P, x1, rhoL, rhoV, flags} |
| `failures` | Rows {kind, T, z1, class, detail}; z1 is the grid value of x1 (bubble) or y1 (dew) |

Flags: `near_critical` (rhoL/rhoV < 3, the proposed exclusion for the D7 row), `above_10_MPa`, `liquid_unstable`, `liquid_stability_unchecked` (none of the last two occur).

## Results (committed fixture)

| Pair | T, K | Grid | Bubble rows (near-critical, above 10 MPa) | Dew rows | Failures |
|---|---|---|---|---|---|
| N2/CH4 | 95, 110, 125, 140, 155, 170 | 48 | 39 (3, 0) | 39 | 9 + 9 beyond isotherm end |
| N2/C2H6 | 150, 180, 210, 240, 270 | 40 | 22 (8, 3) | 32 | 18 + 8 beyond isotherm end |
| CH4/C2H6 | 150, 175, 200, 225, 250, 275 | 48 | 39 (5, 0) | 39 | 9 + 9 beyond isotherm end |
| CO2/N2 | 220, 240, 260, 280, 290 | 40 | 13 (6, 3) | 19 | 27 + 20 beyond isotherm end; 1 dew CoolProp error (280 K, y_CO2 0.7, 0.0004 beyond the richest traced vapour: marginal) |
| CO2/CH4 | 220, 235, 250, 265, 280 | 40 | 18 (5, 0) | 21 | 22 + 19 beyond isotherm end |
| CO2/C2H6 | 220, 235, 250, 265, 280 | 40 | 40 (0, 0) | 40 | none |

Parameter comparison: identical for all six pairs (details in the report, part A.3).

## Detachment

Nothing of this tool is in a tracked path; only its output fixture is tracked. When the batch merges, the lead copies this folder to the main checkout's `tools/gerg-bubble-points/` and adds a `tools/INDEX.md` row.
