# p6b-runtime-failures (research artifacts)

Batch `2026-09-24-coolprop-low-temperature`, P6b (2026-09-26): diagnosis of the four runtime failures of the P6 pilot
world. Stage document: `documentation/2026-09-24-coolprop-low-temperature/P6B_RUNTIME_FAILURE_DIAGNOSIS.md`. Probes and
instrumentation: `tools/p6b-runtime-failures/`. Offline runs on one thread with no game running (JDK 21.0.11).

| File | Content |
|---|---|
| `world-probe-before.txt` | P6's run of the saved checkpoint (copy of `../p6-pilot-acceptance/world-island-probe.txt`) |
| `world-probe-after.txt` | `P6bWorldProbe all 1` at `4c21104`: every saved island's topology, one interval each, typed causes |
| `rowsBD-before-step-trace.txt` | Rows B and D at `c369faf`'s `TrBdf2StepSolver`: single steps of decreasing size and their exceptions (failure 3) |
| `near-pure-uv-probe.txt` | Failure 1: the engine's crystal UV of row A's failing specification and the N2 / CH4 trace scan |
| `near-pure-map-probe.txt` | Failure 1: TP answers along the pure sublimation isotherm; the UV failure map over trace and crystal share |
| `rowA-traces-removed-proxy.txt` | Failure 1, option A proxy: row A with the vessel's traces removed, four intervals |
| `rowE-flush-before.txt` | Failure 2: row E replayed from the initial nitrogen at `a2f48d4` (cold UV beside a vapour and a liquid), 20 intervals and the refusal of interval 21 (failure 1's window) |
| `rowE-flush-ternary-warm-budget8.txt` | Failure 2 trial: warm start for three species with the P6 budget 8 |
| `rowE-flush-after.txt` | Failure 2 at `4c21104`'s rule (budget 16), 20 intervals |
| `rowE-approximate-fallback-compare.txt` | Failure 2: each flush interval also run as the approximate fallback with D18's refusal lifted (7 of 7 refused by the fallback's own guards) |
| `rowE-crystals-at-interval-end.txt` | Failure 2: crystals equilibrated only at the interval's end (interval 1 refused) |
| `chain-probe-before.txt`, `chain-probe-after.txt` | Failure 4: the pilot chain before (P6's `chain-probe-head.txt`) and after `a2f48d4` (40 intervals) |
| `liquidus-kij-probe.txt` | The liquidus finding at Shen 2012's 150.40 K point: solubility, sublimation pressure, k_ij perturbation |
| `test-output/` | P12 and P31 fingerprint files of the final `test` run (identical to P6's) |
| `logs/` | Gradle logs (`test-final`, `science-final`, `runtime-final`, `regression-final`, `gametest-final`, `regression-early`), `gates-*.txt`, `gate-counts.txt` |
