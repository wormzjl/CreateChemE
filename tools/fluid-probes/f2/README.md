# F2 probe and scripts: placement cost against world size

Batch `2026-09-23-fluid-followups`, package F2. Review `FLUID_PLACEMENT_REVIEW.md`; numbers in `f2-tables.md`; outputs in the batch's `f2-logs/`.

| file | what it measured | cited in | run | against |
|---|---|---|---|---|
| `probes/PlacementProfileProbe-before.java` (class `PlacementProfileProbe`, package `runtime.fluid`) | The old topology event path through `PhysicalRegistry`, with a real `IslandCoordinator` that runs no solve, on worlds of WP5 rest lines (500, 2,000, 5,000 devices). It timed single events (30 each after 10 of warm-up, the world restored after each), a position lookup after an event, and a registry load (server start). | review section 1.1; `f2-tables.md` section 1; `probe-before-01.txt` | with `instrumentation/instrumentation-before.patch` applied: `fluidRuntimeTest --tests com.wormzjl.createcheme.runtime.fluid.PlacementProfileProbe --rerun` | `41de055` plus the instrumentation (`f2-logs/gates.log` 03:14) |
| `probes/PlacementProfileProbe.java` | The same probe adapted to the batched event API (by `scripts/edits-probe-after.js`). | review section 2; `f2-tables.md` section 2; `probe-after-01.txt` | as above, without the patch | `51f6935` (09:21) |
| `instrumentation/instrumentation-before.patch` | Per-phase timers in the extracted old `PhysicalRegistry`, made by `scripts/instrument_before.js` and never committed. | review section 1.1 | `git apply` on `41de055` (checked: `cleanup-logs/patch-checks.txt`) | `41de055` |

Placed in `src/test/java/com/wormzjl/createcheme/runtime/fluid/`, the probe matches `fluidRuntimeTest`; delete it before any gate run.

## Scripts: how F2's commits were written

These are one-off development edits, already applied and kept as the record. They no longer match the sources.

- `scripts/extract_authority.js` with `extract_pairs.json` and `ledger_body.java.txt`: moved the event path from `FluidWorldAuthority` into `PhysicalRegistry` unchanged (`41de055`).
- `scripts/instrument_before.js`: produced `instrumentation-before.patch`.
- `scripts/apply-edits.js` and the `edits-*.js` specs: exact-text edits applied with `node apply-edits.js <spec>`, which resolves the repository root as `../../../..` from its folder. `edits-authority.js`, `edits-coordinator.js`, `edits-runtime.js` and `edits-diagnostics.js` are source edits of the commits `934040a` and `924deaa`, and `edits-authority-reads.js` of `51f6935`; `edits-probe-after.js` adapted the probe to the new API; `edits-test-fix1.js` edited `PhysicalRegistryTest` (committed in `525aa8d`).

## Run scripts

`run-scripts/gates.sh <tag> [...]` is F2's gate runner. The in-game pairs used the rig (`../../fluid-in-game-rig/campaigns/f2/`).
