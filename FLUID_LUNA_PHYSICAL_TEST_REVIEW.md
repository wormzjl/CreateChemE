# Luna physical test review

## Scope

`SharedSourceDepletionQualificationTest` adds two deterministic nitrogen fixtures for the remaining P08/P21/P24 rows:

- A finite, initialized nitrogen source feeds two fixed receiving branches at the same time. The branches have unequal lengths and are supplied in both pipe and receiver input orders. Every accepted interval audits all 22 component inventories and gravity-inclusive fluid energy. The test records trajectories by pipe identity and compares matching accepted timestamps across permutations. A near-empty refusal is accepted only as an atomic refusal; it is never counted as successful sustained depletion.
- A finite nitrogen pump source is asked for more suction volume than its qualified stock can provide during a one-second interval. The production interval solver may return a bounded feasible result or refuse atomically. The refusal path checks the original inventory and state values (including moles, U, T, P, and phase volumes). An adequate-suction control uses the same pump equations and must transfer positive material and report positive pump work; component closure and gravity-inclusive energy closure are independently checked.

The fixtures use the bundled `createcheme:tjl20_methane` model with nitrogen initialized through `initialNitrogenCharge`; no evacuated or unsupported state is manufactured. The source volume and interval remain positive, and the shared `1e-9` compressibility basis is retained. No production, benchmark, or build configuration files were changed.

## Assertions and measurements

For each accepted result, the test independently sums component moles over finite reservoirs and compares the delta with the solver boundary ledger through the shared `ConservationAssertions.components` BAL helper and its relative tolerance. It sums internal energy plus `mass * g * elevation` and compares the delta with boundary energy plus pump work through `ConservationAssertions.energy`. It checks nonnegative finite inventories and state/inventory component agreement. The branch trace is keyed by pipe IDs 11 and 12, so edge ordering cannot hide a conservation discrepancy.

The test emits `build/reports/fluid/P08-shared-source-depletion.json` and `build/reports/fluid/P24-finite-pump-suction.json` after successful execution. Reports include accepted interval counts and seconds, minimum source fraction/T/P, exact refusal text, whether insufficient suction was bounded or refused, control flow and pump work, permutation errors, and maximum component/energy BAL tolerance units. They are not a claim of a sustained empty-source trajectory; the first unsupported or overdrawn interval must remain uncommitted. A `SparseNewton.Nonconvergence` message is retained verbatim and classified as a numerical refusal unless it explicitly signals candidate inventory overdraw; neither classification independently proves the full thermodynamic domain boundary.

## Focused execution evidence

The released focused command passed (`BUILD SUCCESSFUL`, 6 seconds), with output captured in `build/luna-physical-focused.log`. The generated evidence is in `build/reports/fluid/P08-shared-source-depletion.json` and `build/reports/fluid/P24-finite-pump-suction.json`.

P08 accepted 11 intervals (`0.10999999999999999 s`) in each input permutation. Both reached the same minimum source fraction `0.5589417957038342`, minimum source temperature `277.1305836951133 K`, and minimum pressure `176804.3241255232 Pa`. The exact refusal after those committed intervals was identical in both permutations:

```text
Substep refinement exhausted: Newton line search stalled at residual 1.1508326861076057E-9; active-set pass=0
```

Maximum component BAL residual was `5.378006791370572e-8` tolerance units and maximum energy BAL residual was `1.8445776920126966e-10` tolerance units. Permutation errors were zero for component moles, source energy, and pipe flows at every matching accepted timestamp. This is conservation and permutation evidence with substantial drawdown; the numerical refusal does not prove a thermodynamic domain boundary, and the remaining approximately 55.9% stock means sustained near-empty depletion remains open.

P24's insufficient-suction case atomically refused the full one-second interval: zero committed intervals and zero committed seconds. The exact preserved message was:

```text
Interval substep limit; no partial interval may commit: advanced=0.061685580527913154 of 1.0 s, accepted=486, rejected=538, reasons={Negative/nonfinite inventory=2, Newton line search stalled at residual #; active-set pass=#=41, Embedded state error #=1, Newton iteration limit at residual #; active-set pass=#=494}, last=Newton iteration limit at residual 0.058197602904325865; active-set pass=0
```

This is classified as `numerical-refusal; physical-domain-cause-not-proven`; the internal attempted progress was discarded atomically. The adequate-suction control accepted one interval (`0.1 s`) at `9.75311785071233e-5 kg/s` and recorded `0.6089414025590747 J` pump work. Its maximum component BAL residual was `0` tolerance units and maximum energy BAL residual was `9.148781644550443e-11` tolerance units.

Focused command after release:

```powershell
.\gradlew.bat test --offline --no-daemon --console=plain --tests "*SharedSourceDepletionQualificationTest"
```

The focused output and refusal messages are preserved. The enriched P08/P24 JSON was refreshed during the full two-fork regression recorded in `build/fluid-luna-parallel-unit.log`: 789 tests passed with zero failures, errors, or skips in 52 seconds. No benchmark command belongs to this fixture review.

## Remaining gaps

This source covers conservation safety, atomic refusal, branch/input permutation invariance, and an actual adequate-suction pump transfer. It does not establish flow-reference accuracy against an independent analytic/reference trajectory, does not qualify wet-crude phase transport in this specific depletion fixture, and does not qualify gameplay block or persistence behavior. It also cannot claim unsupported empty-vessel filling or repeated transfers past the first physical-domain refusal. P08/P21/P24 remain partial: P08/P21 have a numerical near-depletion refusal at 55.9% stock remaining, and P24 has an atomic numerical refusal whose physical-domain cause is not proven, although its adequate-suction control passed.
