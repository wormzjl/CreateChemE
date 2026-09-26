# V3 W-1 Dry-Tray Foundation

Date: 2026-09-02

## Scope completed in this checkpoint

This checkpoint establishes the structural representation needed for the plan's vapor-only
tray branch. It deliberately does **not** publish a live dry tray yet. Existing A0 calculations
therefore retain their current topology and behavior.

- `V3ColumnTopology` now carries a canonical immutable list of interior vapor-only tray numbers.
- `V3ColumnProblemResolver` can resolve a full solver-selected topology, checks authored geometry,
  and refuses a topology that would place an authored liquid side draw on a vapor-only tray.
- Component liquid presence is gated by the full topology, rather than treating every non-condenser
  node as liquid-capable.
- The input digest records any resolved vapor-only tray, while an empty list preserves the legacy
  byte stream.
- A state is now rejected when it contains liquid/vapor flow in a phase absent from its target
  topology. This prevents a same-sized wet seed from silently contaminating dry residuals.
- `V3DryTrayTransition` can detect a below-`1e-6*F` interior liquid total from an identity-support
  state, construct the merged vapor-only topology, and project exact-zero liquid flows without
  changing other state values. It checks the **full authored** draw set, so a partial feature rung
  cannot create a future draw-on-dry seed.
- Same-grid resolver paths (condenser correction, feature ramp, pressure legs, and recovery) retain
  a selected full topology. The changing 4→8→15→N continuation grids deliberately remain wet:
  physical tray numbers cannot be copied across a geometry remap.
- The independent `DRY_TRAY` audit family reads all dry-tray liquid component flows directly and
  requires exact zero, including a `Double.MIN_VALUE` leakage regression test.
- A property-gated `v3W1W5BenchmarkProbe` records the six current W1/W5 dry, no-steam wall cases
  as structured JSON without affecting the normal unit-test task.

## Algebraic result

For the independent two-component/four-tray manufactured problem, resolving tray 3 vapor-only
changes the square system from 29 to 27 unknowns/equations:

```text
dry tray block: 2 vapor component flows + temperature = 3 unknowns
removed rows:  2 liquid component unknowns and 2 VLE equations
remaining rows at that tray: 2 material balances + energy = 3 equations
```

The structural rank remains full. Whole-system finite differences and the local block Jacobian
agree across the variable-size block, and coordinate decoding produces exact zero liquid at the
vapor-only tray.

## Activation experiment and rollback

I briefly activated the transition after full-grid feature-ramp and pressure-leg solves, with a
bounded re-solve that would retain a dry candidate only on fresh audited success or a lower
residual. It was removed because it produced no observable benefit:

| Screen | Baseline | Dry-branch candidate | Decision |
|---|---:|---:|---|
| Native W1 cells 1, 4, 29 (serial) | 0/3 success | 0/3 success; same residuals and paths | rollback |
| W1/W5 probe: 155/50/40, 100/50/40, 60/85–100/22.5 | — | 0/6 success; **no dry-transition event** | rollback |

The measured reason is important: production solves stop at trace/VLE/certificate residuals
before any tray's **total** liquid falls below the structural `1e-6*F` floor. For example, W5c's
dominant PC12 liquid flow is thin, but that is a trace-pair condition rather than a dry tray.
Forcing a higher tray-total floor would change the physical formulation without evidence that the
tray is dry, so it is not an acceptable convergence shortcut.

The detailed measurements are preserved in
`documentation/V3_W1_DRY_TRAY_ACTIVATION_BENCHMARK.md`. The temporary detached baseline
worktree was removed after its values were recorded; the current workspace's ignored candidate
reports remain under `build/reports/benchmarks/`.

## Boundaries deliberately left for the next W-1 checkpoint

1. Find a production state that reaches the physical dry-tray regime, or derive and validate a
   separate pre-collapse structural criterion. It must not substitute a trace-pair symptom for a
   dry-tray topology decision.
2. Update any cold initializer/preconditioner that would be needed after a live dry branch. The
   current safe path remains transition-only from an existing same-grid state.
3. Add typed draw-on-dry publication and the formulation/assumptions revision bump only alongside
   a benchmark-proven live activation.
4. Revisit W-2/W-3 trace-pair conditioning for the current production failures; the rejected W-1
   activation confirms that those failures still occur before the dry-tray formulation is reached.
