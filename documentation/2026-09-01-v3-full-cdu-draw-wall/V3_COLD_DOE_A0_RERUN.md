# V3 A0 Cold DOE Re-screen — Wet-Lane Symmetry and Direct-Basin Selection

Date: 2026-09-02  
Status: COMPLETE — retained at `9d18bb9`

## Purpose

This re-screen closes Phase A0 of `V3_FULL_CDU_PLAN.md`. It tests whether the remaining
continuation work fixes the wet side-draw lane without weakening the already-qualified dry
pressure-continuation lane or adding a material-admission shortcut.

The retained policy is deliberately path-only:

- Low-pressure wet requests attach side draws dry at the 150 kPa anchor, carry them through every
  pressure leg, then ramp steam at the requested pressure.
- Direct 150–155 kPa moderate-draw and >=200 kPa wet requests use the measured steam-first basin.
- Other direct wet requests use draws-first, with a bounded steam-first retry only after that path
  fails.
- Every published candidate still passes the unchanged exact residual, final-Newton, and fresh
  acceptance-audit gates.

## Compared revisions

| Role | Revision | Meaning |
|---|---|---|
| Main baseline | `bdfeff1` | Original paired screen baseline from `V3_COLD_DOE_BENCHMARK.md`. |
| Immediate A0 control | `cc1affb` | Dry side draws carried through pressure legs; wet lane still steam-first then draw attach. |
| Final candidate | `9d18bb9` | Wet-lane symmetry, direct-basin selection, bounded fallback, and reproducible DOE task. |

The 36-cell outcome screen was executed at `7ba414a`, immediately before the final high-pressure
timing-only selection. That final selection changes only cells 9 and 12, both of which were
separately re-run three times at `9d18bb9` and remained audited successes. All other rows execute
the same code path at both revisions.

## Method

- Registered Tia Juana Light, 30-tray fixture: 2,610.7 kmol/h feed, 638.15 K feed temperature,
  feed tray 24, 750 Pa/tray, reflux ratio 2.0, 8 MW reboiler duty.
- Three draws at trays 8/15/22 in the existing 496:653:149 allocation; loading denotes combined
  draw rate / feed rate.
- Optional steam: 8 mol/s at 450 K at stage 31.
- Three-factor, seven-run definitive screening design per steam block, plus the seven-point
  pressure-knot and 100 kPa temperature/loading interaction augmentations: 36 cold cells total.
- Each outcome uses a new immutable input and stateless calculator call with a 60-second
  calculation ceiling. The outcome screen used three workers; final serial rechecks resolved
  any cell affected by an interim policy change.
- `S` means an audited exact success. `F` means typed `NONCONVERGENCE`. There were no final
  timeouts or other terminal categories.

Reproducible task:

```powershell
./gradlew.bat v3ColdDoeScreen --offline --no-daemon --console=plain `
  -Pv3ColdDoeReport=build/reports/benchmarks/v3-cold-doe.json `
  -Pv3ColdDoeWorkers=3
```

The task also accepts `-Pv3ColdDoeCellIds=...` and `-Pv3ColdDoeWorkers=1` for deterministic
serial rechecks.

## Outcome summary

| Block | Main | `cc1affb` | Candidate | Candidate delta vs `cc1affb` |
|---|---:|---:|---:|---:|
| Dry DSD (7) | 1/7 | 4/7 | 4/7 | 0 |
| Steam DSD (7) | 3/7 | 3/7 | 4/7 | +1 |
| Dry pressure knot (7) | 0/7 | 7/7 | 7/7 | 0 |
| Steam pressure knot (7) | 0/7 | 0/7 | 7/7 | +7 |
| 100 kPa interaction (8) | 3/8 | 3/8 | 4/8 | +1 |
| **All 36 cells** | **7/36** | **17/36** | **26/36** | **+9** |

The final candidate has 13/18 dry successes and 13/18 steam successes. Every `cc1affb` success
remains successful. The nine new successes are cells 7, 21–27, and 34.

## Full outcome matrix

| ID | Design | kPa | Cond. °C | Draw | Steam | Main | `cc1affb` | Candidate |
|---:|---|---:|---:|---:|:---:|:---:|:---:|:---:|
| 0 | DSD | 155 | 100 | 5% | no | S | S | S |
| 1 | DSD | 155 | 50 | 40% | no | F | F | F |
| 2 | DSD | 250 | 75 | 5% | no | S | S | S |
| 3 | DSD | 60 | 75 | 40% | no | F | F | F |
| 4 | DSD | 60 | 100 | 22.5% | no | F | F | F |
| 5 | DSD | 250 | 50 | 22.5% | no | F | S | S |
| 6 | DSD | 155 | 75 | 22.5% | no | F | S | S |
| 7 | DSD | 155 | 100 | 5% | yes | F | F | S |
| 8 | DSD | 155 | 50 | 40% | yes | F | F | F |
| 9 | DSD | 250 | 75 | 5% | yes | S | S | S |
| 10 | DSD | 60 | 75 | 40% | yes | F | F | F |
| 11 | DSD | 60 | 100 | 22.5% | yes | F | F | F |
| 12 | DSD | 250 | 50 | 22.5% | yes | S | S | S |
| 13 | DSD | 155 | 75 | 22.5% | yes | S | S | S |
| 14 | pressure knot | 80 | 75 | 22.5% | no | F | S | S |
| 15 | pressure knot | 90 | 75 | 22.5% | no | F | S | S |
| 16 | pressure knot | 95 | 75 | 22.5% | no | F | S | S |
| 17 | pressure knot | 99 | 75 | 22.5% | no | F | S | S |
| 18 | pressure knot | 100 | 75 | 22.5% | no | F | S | S |
| 19 | pressure knot | 101 | 75 | 22.5% | no | F | S | S |
| 20 | pressure knot | 105 | 75 | 22.5% | no | F | S | S |
| 21 | pressure knot | 80 | 75 | 22.5% | yes | F | F | S |
| 22 | pressure knot | 90 | 75 | 22.5% | yes | F | F | S |
| 23 | pressure knot | 95 | 75 | 22.5% | yes | F | F | S |
| 24 | pressure knot | 99 | 75 | 22.5% | yes | F | F | S |
| 25 | pressure knot | 100 | 75 | 22.5% | yes | F | F | S |
| 26 | pressure knot | 101 | 75 | 22.5% | yes | F | F | S |
| 27 | pressure knot | 105 | 75 | 22.5% | yes | F | F | S |
| 28 | 100 kPa interaction | 100 | 50 | 5% | no | S | S | S |
| 29 | 100 kPa interaction | 100 | 50 | 40% | no | F | F | F |
| 30 | 100 kPa interaction | 100 | 100 | 5% | no | S | S | S |
| 31 | 100 kPa interaction | 100 | 100 | 40% | no | F | F | F |
| 32 | 100 kPa interaction | 100 | 50 | 5% | yes | S | S | S |
| 33 | 100 kPa interaction | 100 | 50 | 40% | yes | F | F | F |
| 34 | 100 kPa interaction | 100 | 100 | 5% | yes | F | F | S |
| 35 | 100 kPa interaction | 100 | 100 | 40% | yes | F | F | F |

## What changed and what remains limited

- **Wet pressure knot:** cells 21–27 all converge. This is the primary A0 result: dry side draws
  attach at the anchor and ride the pressure ladder; steam is no longer forced to accept draws on
  the final wet, low-pressure state.
- **Hot 155 kPa low-load DSD:** cell 7 changes from `RAMP_RUNG_CAP` failure to audited success.
- **Hot 100 kPa low-load interaction:** cell 34 changes from a bare pressure-leg stall to audited
  success because the steam lane now carries the drawn dry seed through the legs.
- **Remaining ten failures:** all are typed `NONCONVERGENCE`, dominated by the documented
  collapsed trace-liquid / draw-attach wall at 40% loading and the 60 kPa hot/high-load boundary.
  No approximation or material-balance admission rule was used to turn these into successes.

### Rejected pacing experiment

`V3AdaptiveRamp` increment re-expansion was implemented behind normal production code and then
tested against the new path. It did not provide a retained benefit:

| Canary | With re-expansion | Without re-expansion | Decision |
|---|---:|---:|---|
| 100 kPa, 75 °C, 5%, steam | 18.904 s | 19.748 s | Difference is not material; both audited success. |
| 100 kPa, 60 °C, 22.5%, steam | — | 20.539 s | The former near-miss now succeeds with steam-last ordering alone. |

The re-expansion change was rolled back. A third exact full-FD near-miss leg was not added because
the reordered path removed its demonstrated use case; it would add cost without measured gain.

## Serialized cold timing

These are calculation-only times from `9d18bb9`, with one worker, fresh input per call, and no
concurrent solver work. Historical medians are from `cc1affb` in
`V3_COLD_DOE_BENCHMARK.md`.

| DOE ID | Condition | Candidate samples (ms) | Candidate median | `cc1affb` median | Delta |
|---:|---|---|---:|---:|---:|
| 9 | 250 kPa, 75 °C, 5%, steam | 8450, 9041, 9100 | 9041 | 8418 | +7.4% |
| 12 | 250 kPa, 50 °C, 22.5%, steam | 11158, 11922, 12077 | 11922 | 11263 | +5.9% |
| 13 | 155 kPa, 75 °C, 22.5%, steam | 11144, 11729, 11878 | 11729 | 12618 | -7.0% |
| 32 | 100 kPa, 50 °C, 5%, steam | 10354, 10828, 10882 | 10828 | 21003 | **-48.4%** |

The final direct-basin policy is performance-neutral on the two high-pressure controls and removes
the temporary 40% regression seen while draw-first was applied there. The low-pressure wet route
has a large measured speed gain as well as the convergence gain.

## Retain / rollback decision

| Item | Decision | Evidence |
|---|---|---|
| Wet-lane dry-draw anchor continuation | Retain | Steam knot 0/7 -> 7/7; total 17/36 -> 26/36. |
| Direct wet basin selection + fallback | Retain | Preserves all old successes; no timing median worsens by >=10%. |
| `V3AdaptiveRamp` increment re-expansion | Roll back | No material independent improvement after lane symmetry. |
| Near-miss extra full-FD leg | Do not implement | No remaining measured trigger that justifies its cost. |
| Material closure/admission relaxation | Remains rolled back | Final results use unchanged exact publication gates. |

Phase A0 is complete for the current V3 side-draw/steam scope. The known 40%-loading and
60 kPa hot/high-load walls remain explicit numerical limits to carry into Phase A, not hidden
successes.

## Preservation note

The root-level `V3_COLD_DOE_BENCHMARK.md` deletion is user-owned and remains unstaged. This report
references the historical benchmark under `documentation/` only; it does not restore or modify the
deleted root-level file.
