# Solid-phase transient solver stall — diagnosis

Diagnosis only. No fix designed or landed; nothing committed.

Date: 2026-09-22. Worktree: `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-ad8e7b79464d12025`.

---

## 0. Setup verification

| Check | Result |
| --- | --- |
| `git checkout --detach 440a754` | done, detached |
| `git log -1 --oneline` | `440a754 Add solid-phase fluid transport and inline filtration` |
| Working tree at start | clean |
| Other Gradle build active? | no — 4 `java.exe` present, CPU delta over 3 s = 0 for all four (12524, 37048, 41300, 44144) |
| Branch created / commit made | none; stayed detached throughout |
| `git stash` used | never |

End-of-session `git status --short`:

```
 M src/main/java/com/wormzjl/createcheme/science/fluid/network/PassiveStepSolver.java
 M src/main/java/com/wormzjl/createcheme/science/fluid/network/SolidEventIntegrator.java
 M src/main/java/com/wormzjl/createcheme/science/fluid/solver/PhaseLayout.java
 M src/main/java/com/wormzjl/createcheme/science/fluid/solver/SparseNewton.java
?? src/test/java/com/wormzjl/createcheme/science/fluid/network/ZzStallProbeTest.java
```

All four modifications are the temporary diagnostic instrumentation (marked
`==== TEMPORARY DIAGNOSTIC INSTRUMENTATION (not for commit) ====`). Every experimental switch
defaults to stock behaviour; the final stock run reproduces E0's accepted/rejected counts bit for
bit, so the instrumentation is behaviour-neutral.

`git stash list`: one entry, **pre-existing and untouched** —
`stash@{0}: On codex/plan-2-solver-experiments: !!GitHub_Desktop<codex/plan-2-solver-experiments>`.
The stack did not grow; no stash command was run in this session.

### Diagnostic switches added (uncommitted, all default to stock)

| Switch | File | Effect when set |
| --- | --- | --- |
| `PassiveStepSolver.DEBUG` | `network/PassiveStepSolver.java` | dump + classify every `Newton line search stalled` rejection |
| `PassiveStepSolver.DEBUG_PRINT_LIMIT` | same | how many dumps to print |
| `PassiveStepSolver.DEBUG_PASSES` / `DEBUG_MAXROW` / `DEBUG_STALLS` | same | active-set pass histogram, stall classification |
| `PassiveStepSolver.DEBUG_EXEMPT_SOLIDS=1` | same | `maximumStep` skips solid unknowns below `1e-12` of their scale |
| `PhaseLayout.DEBUG_PROJECT_SOLIDS=1` | `solver/PhaseLayout.java` | `decode()` projects a negative trial solid moment onto 0 |
| `SolidEventIntegrator.DEBUG` | `network/SolidEventIntegrator.java` | per-event cost split |
| `SparseNewton.DEBUG_stall*` | `solver/SparseNewton.java` | alpha0, direction, residual history, domain-failure count at the stall |

---

## E0 — Baseline reproduced

Probe test at `src/test/java/com/wormzjl/createcheme/science/fluid/network/ZzStallProbeTest.java`,
`./gradlew.bat fluidScienceTest --tests '…ZzStallProbeTest' --console=plain --offline -i`.

| nodes | solids | duration | ms | accepted | rejected | blocked pipes | result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2 | no | 0.5 s | 73–85 | 14 | 5 | 0 | ok |
| 2 | no | 5 s | 19–21 | 18 | 5 | 0 | ok |
| 2 | yes | 0.5 s | 302–559 | 36 | 7 | 1 | ok, one DEPOSITION closure, **0 stalls** |
| 2 | yes | 5 s | 215–413 | 40 | 7 | 1 | ok, **0 stalls** |
| 10 | no | 0.5 s | 68–135 | 25 | 10 | 0 | ok |
| 10 | no | 5 s | 46–73 | 32 | 14 | 0 | ok |
| 10 | yes | 0.5 s | 1494–2203 | 368 | 175 | 2 | ok, **170 stalls** |
| 10 | yes | 5 s | 2818–3569 | 1016 | 516 | — | **FAILS** |

5 s failure message (identical every run, deterministic):

```
Interval substep limit; no partial interval may commit: advanced=1.5322265625 of 4.994516586724558 s,
accepted=1016, rejected=516, reasons={Newton line search stalled at residual #; active-set pass=#=516},
last=Newton line search stalled at residual 2.1300561901582896E-4; active-set pass=2
```

Matches the numbers in the brief. Wall-clock ms varies ±40 % with JIT ordering; accepted/rejected
counts and the advanced time are bit-reproducible.

---

## E1 — Anatomy of a stall

`Newton line search stalled at residual N` is thrown in `SparseNewton.solve`, in the
`if(!accepted)` branch after all 24 backtracks failed **and** the Jacobian was already fresh. `N` is
`norm(f)` = max |residual| at the current iterate (not at the rejected candidate).

### Per-stall detail (10 nodes, solids, 0.5 s, stock)

| # | pass | dt (s) | Newton iters | stall residual | max-residual row | **alpha0** | binding column | x at binding | direction at binding | backtracks | best next norm |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | 0.14484 | 7 | 2.2335e-5 | `e1.hydraulic` | **4.454e-12** | `n3.solid0` | 1.4646e-29 | -3.2557e-18 | 24 | 2.2335e-5 (= norm) |
| 2 | 1 | 0.07242 | 8 | 9.8019e-6 | `e3.hydraulic` | **4.154e-17** | `n5.solid2` | 4.9271e-45 | -1.1743e-28 | 24 | = norm |
| 3 | 1 | 0.03621 | 8 | 3.7099e-6 | `n1.bal0` (water balance) | **1.893e-17** | `n9.solid0` | 1.3503e-70 | -7.0635e-54 | 24 | = norm |
| 4 | 1 | 0.018105 | 11 | 4.2340e-5 | `e1.hydraulic` | **4.095e-17** | `n9.solid0` | 3.5480e-70 | -8.5777e-54 | 24 | = norm |
| 5 | 1 | 0.009053 | 13 | 1.5835e-4 | `e1.hydraulic` | **1.332e-17** | (solid) | — | — | 24 | = norm |
| 6 | 2 | 0.004526 | 13 | 9.3180e-5 | `e2.hydraulic` | **1.972e-17** | (solid) | — | — | 24 | = norm |
| 7 | 2 | 0.002263 | 9 | 1.7732e-4 | `e2.hydraulic` | **1.559e-18** | (solid) | — | — | 24 | = norm |
| 8 | 2 | 0.001132 | 9 | 2.5782e-4 | `e2.hydraulic` | **3.183e-18** | (solid) | — | — | 24 | = norm |
| 9 | 2 | 5.658e-4 | 10 | 2.4717e-4 | `e2.hydraulic` | **2.153e-16** | (solid) | — | — | 24 | = norm |

`alpha0` is `Equations.maximumStep(x,direction)` and reproduces `0.99·x/|d|` exactly
(`0.99·1.4646e-29/3.2557e-18 = 4.4536e-12`; `0.99·4.9271e-45/1.17433e-28 = 4.1537e-17`;
`0.99·1.35032e-70/7.06346e-54 = 1.8926e-17`).

`domainFailures = 0` in every stock stall: the candidate points are all inside the property domain.
`bestNextNorm == norm` **exactly** (same double) for every one of the 24 backtracks.

### Full classification of every stall (stock)

Key is `pass / max-residual row / alpha class / binding variable`. "alphadead" = alpha0 < 1e-9.

10 nodes, solids, **0.5 s** — 170 stalls:

| pass | max row | alpha class | binding | count |
| --- | --- | --- | --- | --- |
| 0 | `e0.hydraulic` | dead | `solid0/1/2` | 6 + 6 + 20 = **32** |
| 1 | `e1.hydraulic` / `e3.hydraulic` / `n1.bal0` | dead | `solid0/2` | **5** |
| 2 | `e2.hydraulic` | dead | `solid0/1/2` | 40 + 31 + 62 = **133** |

10 nodes, solids, **5 s** — 517 stalls:

| pass | max row | alpha class | binding | count |
| --- | --- | --- | --- | --- |
| 0 | `e0.hydraulic` | dead | `solid0/1/2` | 21 + 30 + 51 = **102** |
| 1 | mixed edge rows / `n1.bal0` | dead | `solid0/1/2` | **6** |
| 2 | `e2.hydraulic` | dead | `solid0/1/2` | 106 + 122 + 181 = **409** |

**100 % of stalls (170/170 and 517/517) have alpha0 < 1e-9 and a solid-moment unknown as the
binding column. There is not one exception.**

### Answers to the questions posed

- **Is the stalled configuration the pass-2 one, where both direction-blocked pipes have just been
  flipped to fully closed?** No — that is the *majority* (133/170 and 409/517) but not the whole
  story. 32/170 and 102/517 stall at **pass 0**, before any flip, and 5/170 and 6/517 at pass 1.
  The active-set configuration is not the discriminator.
- **Is the max-residual row an edge row at flow ≈ 0, a solid row, the volume closure, or something
  else?** It is almost always an **edge hydraulic row** — `e0` (the direction-blocked pipe, whose
  closed-edge row is literally `f = flow`) at pass 0, and `e2` (the first *unblocked* pipe) at
  pass 2. It is **never** a solid row, never the volume closure, and only 1–2 times a node material
  balance (`n1.bal0`). The failing residual and the blocking constraint are on different variables.
- **Active-set passes per substep** (exit-pass histogram, `PassiveStepSolver` solves; includes both
  successful returns and stall throws): 0.5 s → `pass0:152 pass1:2244 pass2:1130`; 5 s →
  `pass0:226 pass1:2707 pass2:3458`. Almost every solve needs 2–3 passes.

### Where the tiny solid values come from

Dump of the decoded solid moments at stall #1 (`n0` holds the 100 kg particle inventory):

```
n0 m=9.9999e+01  n1 m=6.4314e-04  n2 m=3.5663e-09  n3 m=1.4247e-26  n4 m=8.6866e-25
n5 m=2.5453e-31  n6 m=5.0676e-38  n7 m=6.5784e-45  n8 m=5.0292e-52  n9 m=1.5950e-59
```

A geometric cascade: each backward-Euler step moves a fraction `dt·q/m ≈ 1e-6` of a node's solids
into the next node, so node *k* holds ~`1e-6^k` of the source. By node 5–9 these are 1e-31 … 1e-70,
i.e. numerical dust, not physics — and inside one node the three moments are mutually inconsistent
(`n9[solid0=1.350e-70, solid1=5.784e-67, solid2=1.729e-66]` is not a physical mass/volume/heat-capacity
triple; it is LU fill-in). They are nonetheless strictly **positive**.

---

## E2 — Semantics experiment: close both directions

`blocked[…] |= 3` instead of `|= failure.direction` / `|= transition.direction` (both sites in
`SolidEventIntegrator`). Everything else unchanged.

| case | stock ms | E2 ms | stock acc/rej | E2 acc/rej | stock stalls | E2 stalls | E2 blocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2 nodes, 0.5 s | 302–559 | 498 | 36 / 7 | 36 / 7 | 0 | 0 | `3` |
| 2 nodes, 5 s | 215–413 | 174 | 40 / 7 | 40 / 7 | 0 | 0 | `3` |
| 10 nodes, 0.5 s | 1494–2203 | **999** | 368 / 175 | **38 / 5** | 170 | **1** | `3,3,0…` |
| 10 nodes, 5 s | **FAILS** | **571 (passes)** | 1016 / 516 | **42 / 5** | 517 | **1** | `3,3,0…` |

Pass histogram under E2 collapses to `pass0` only (`0:2367`, `0:2845`) — because
`PassiveStepSolver` presets `boundaryClosed[i]=true` only when `blockedDirections==3`, so with a
full closure the active set is right on the first pass and the solve never re-enters a configuration
that can manufacture the solid dust. Reverted afterwards.

---

## E3 — Cost split of `SolidEventIntegrator.solve`

`DEBUG_STALLS` was sampled at each event boundary, so the stalls are attributed to a phase.

**10 nodes, solids, 0.5 s — total 2191 ms**

| phase | wall ms | detail | stalls in phase |
| --- | --- | --- | --- |
| pre-interval rate pass | 2.1 | 1 rate solve, closes nothing | 0 |
| event 1 first normal attempt | 10.7 | throws `Transition` almost immediately | 0 |
| event 1 bracket (tight replays) | 107.2 | 19 probes / 20 replays, bracket at 2.022e-4 s | 0 |
| event 2 first normal attempt | 2.5 | throws `Transition` | 0 |
| event 2 bracket (tight replays) | 1254.3 | 19 probes / 20 replays, bracket at 5.281e-3 s | 0 |
| event 3 post-closure continuation | **812.4** | no transition, advances 0.494517 s | **170** |
| total | 2191.3 | 3 events, 40 tight replays (1361.5 ms), 1 rate solve | 170 |

**2 nodes, solids, 0.5 s — total 490 ms**

| phase | wall ms | detail | stalls |
| --- | --- | --- | --- |
| pre-interval rate pass | 26.2 (JIT warm-up) | 1 rate solve | 0 |
| event 1 first attempt | 24.1 | throws `Transition` | 0 |
| event 1 bracket | 430.2 | 19 probes / 20 replays, bracket at 5.470e-3 s | 0 |
| event 2 continuation | 3.4 | advances 0.494530 s | 0 |
| total | 490.2 | 2 events, 20 replays | 0 |

Two separate cost problems:

1. **The bracket is expensive even when nothing stalls** — 40 tight replays cost 1361 ms of the
   2191 ms; the 2-node case pays 430 ms for 20 replays with zero stalls. Each of the 19 bisection
   probes is a *complete* re-integration from the interval start at `initialStep ≤ 1 ms`,
   `relTol ≤ 1e-6`, and the locator always runs its full 19 probes to reach the 1 µs bracket.
2. **All of the stalling is in the post-closure continuation** (event 3, 812 ms, 170 stalls). At
   10 nodes this is what turns into the 5 s failure.

---

## E4 — Tolerance experiment: `tight = settings` (no tightening)

| case | stock ms | E4 ms | stock acc/rej | E4 acc/rej | E4 stalls | E4 closures |
| --- | --- | --- | --- | --- | --- | --- |
| 2 nodes, 0.5 s | 302–559 | **121** | 36 / 7 | 4 / 0 | 0 | `1` (same) |
| 2 nodes, 5 s | 215–413 | **14** | 40 / 7 | 8 / 0 | 0 | `1` (same) |
| 10 nodes, 0.5 s | 1494–2203 | **1336** | 368 / 175 | 336 / 170 | 204 | `1,1,0…` (same) |
| 10 nodes, 5 s | **FAILS** | **751 (passes)** | 1016 / 516 | 278 / 140 | 157 | `1,1,0…` (same) |

The bracket still converges — the locator reaches its 1 µs bracket, and the located transitions are
the same two DEPOSITION events on the same two edges. Cost drops 1.6×–20×. The 5 s case happens to
finish here, but only because the coarser bracket lands on a different prefix; **157 stalls remain**,
so this is not a fix for the stall, only for the bracket's price. Caveat: the locator's own doc
comment says a tight time bracket cannot correct an inaccurate hydraulic trajectory, so dropping the
tightening un-qualifies the event time. Reverted afterwards.

---

## E5 — Isolating the stall from the event machinery

Direct `PassiveIntervalSolver.integrate(...)` with `StageGuard.NONE` (no `SolidEventIntegrator`, no
locator, no replays), 1 s, default settings.

| graph | ms | accepted | rejected | stalls | result |
| --- | --- | --- | --- | --- | --- |
| end state of the 0.5 s run, blocked `1,1,0…` (as-is) | 1499 | 662 | 336 | **335** | ok but crawling |
| same end state, blocked `3,3,0…` | **2** | 2 | 0 | **0** | ok |
| same end state, blocked `0,0,0…` | 39 | 32 | 6 | 2 | ok |
| fresh 10-chain with solids, blocked `1,1,0…` | 28 | — | — | 20 | **FAILS** `Substep refinement exhausted: Newton line search stalled at residual 1.89854794982125E-9; active-set pass=1` |
| fresh 10-chain with solids, unblocked | 69 | 42 | 10 | **0** | ok |
| fresh 10-chain, **no solids**, blocked `1,1,0…` | 46 | 13 | 5 | **0** | ok |

Conclusions:

- The stall reproduces with the event locator, the replays and the stage guard **completely
  bypassed**. It is a `PassiveStepSolver` property, not an event-machinery property.
- It needs **both** solids and *direction*-blocked pipes. Solids alone: 0 stalls. Direction-blocking
  alone (no solids): 0 stalls. Fully closing (`3`) the same pipes: 0 stalls.
- A *fresh* chain (nodes 1–9 carry exactly zero solids) stalls within 20 attempts. The dust is
  manufactured inside a single solve; it does not have to be inherited from an earlier substep.

---

## Mechanism

Line by line, with the evidence.

1. `PassiveStepSolver.Equations` builds every node's `PhaseLayout` with
   `solidSupport = hasSolids(graph)` — a **whole-island** predicate. So **every** reservoir in an
   island that contains any solids anywhere gets 3 solid-moment unknowns (mass, volume, heat
   capacity) at `PhaseLayout.solidIndex`, even reservoirs holding no solids at all.
   *Evidence:* the dumps show `n0.solid0..2 … n9.solid0..2` present for all 10 nodes while only
   `n0` was seeded with particles.

2. `PhaseLayout.totalAmountVariable(local)` returns `true` for those three, so
   `Equations.amountVariables[...]` is set for them.

3. `PhaseLayout.decode` feeds them into `new SolidInventory.Moments(...)`, whose compact
   constructor throws `IllegalArgumentException("Invalid solid moments")` on any negative
   component (`state/SolidInventory.java:27-31`). They are **hard-constrained nonnegative**.

4. `Equations.maximumStep` enforces that constraint with the 0.99 rule:
   `for(c<edgeOffset) if(amountVariables[c] && variables[c]>0 && direction[c]<0) alpha = min(alpha, .99*variables[c]/-direction[c])`.
   The guard is `variables[c] > 0` — **no relative floor**.

5. With a direction-blocked pipe, `boundaryClosed[i]` starts `false` (it is preset only when
   `blockedDirections == 3`), so passes 0 and 1 solve with that pipe free and Newton drives flow
   through the forbidden direction. Solids are transported downstream by the backward-Euler solid
   balance at ratio `dt·q/m ≈ 1e-6` per hop, so node *k* acquires a strictly positive solid moment
   of order `1e-6^k`.
   *Evidence:* `n1 6.4e-4, n2 3.6e-9, n3 1.4e-26, … n9 1.6e-59 kg` at stall #1; the inter-moment
   ratios at `n9` are physically meaningless, i.e. LU fill-in.

6. Those dust values are exactly what binds step 4. Scaled by
   `solidScale = {seed mass, seed volume, seed mass·1000}` the unknown is 1e-29 … 1e-70 while its
   Newton direction is 1e-18 … 1e-54, so
   `alpha = 0.99·x/|d| ≈ 1e-12 … 1e-18`.
   *Evidence:* the alpha0 column of the E1 table reproduces `0.99·x/|d|` to all printed digits, and
   the binding column is a `solidK` unknown in **687 of 687** classified stalls.

7. With `alpha ≈ 4e-17`, `candidate[i] = x[i] + alpha*direction[i]` is **bitwise identical** to
   `x[i]` for every O(1) unknown: the largest direction component is ~4.4e-2 on `e1.flow`, so the
   update is 1.8e-18 on a flow of 0.102 — a relative change of 1.8e-17, below `2^-52 = 2.2e-16`.
   The candidate residual is therefore the same array, `nextNorm == norm` exactly, the Armijo test
   `nextNorm < norm*(1-1e-4*alpha)` fails, the affine-invariant merit fallback fails identically,
   and all 24 backtracks (which only shrink alpha further) fail.
   *Evidence:* `bestNextNorm == norm` to the last bit in every stock stall; `domainFailures = 0`.

8. `SparseNewton` then verifies the factorization (which is fine), sees `fresh == true`, and throws
   `Newton line search stalled at residual N`, with `N` = 1e-6 … 2.6e-4 on an **edge hydraulic row**
   — `e0.hydraulic` (the closed-edge row `f = flow`, i.e. Newton cannot even drive the blocked
   pipe's flow to zero) at pass 0, `e2.hydraulic` at pass 2.

9. `PassiveStepSolver` catches it, finds no phase correction, and rethrows with
   `; active-set pass=N`. `PassiveIntervalSolver` counts a rejection, halves `h`, and retries.
   Halving does not help: the dust regenerates at every step size, and a smaller `dt` produces
   *smaller* dust, hence a *smaller* alpha. The mean accepted step collapses to ≈3 ms.

10. At 0.5 s the interval still finishes (368 accepted / 175 rejected) because 0.494 s of 3 ms
    steps fits inside the 1024-attempt budget. At 5 s it does not: 508 accepted attempts +
    516 rejected = the 1024 cap at `advanced = 1.532 s`, and the interval solver refuses to commit
    a partial interval.

### Why 2 nodes is fine and 10 nodes is not

With 2 nodes there is no downstream chain: the only receiver, `n1`, gets a *real* ~1e-4 kg of
solids, whose scaled value is ~1e-7 — large enough that `0.99·x/|d|` stays O(1). The cascade needs
≥3 nodes to reach the 1e-30…1e-70 range where the ratio drops below 1e-16. This is a chain-length
effect, and it will bite any island with ≥3 reservoirs in series downstream of a solid source.

### Why it is not the closure flip, the φ clamp, or the truncation

- Not the flip: 32/170 and 102/517 stalls happen at pass 0, before any flip (§E1 classification);
  and `boundaryClosed = [true,false,…]` in stalls #1–#5.
- Not the Krieger–Dougherty φ clamp: `phi` is 0.0385 at `n0` and **0.0000** at every other node in
  every dump; the clamp at 0.62−1e-9 is nowhere near active.
- Not the coloured-sweep Jacobian (`differentiateEntries` returns −1 for solid islands): that costs
  time but the factorization verifies clean at every stall (`workspace.factorization.verify` does
  not throw; the exception raised is the line-search one, not `Singular Newton Jacobian`).
- Not the tight replay settings: E4 shows the bracket is expensive but the stalls survive it, and
  E5 shows the stall with the whole event machinery bypassed.

---

## Confirmation ablation

Two one-line experimental switches, measured independently and together. 10 nodes, solids.

- **floor**: `Equations.maximumStep` skips a solid unknown whose scaled value is below `1e-12`.
- **projection**: `PhaseLayout.decode` applies `Math.max(0, …)` to the three decoded moments
  instead of letting `SolidInventory.Moments` refuse the trial.

| variant | 0.5 s ms | 0.5 s acc/rej | 0.5 s stalls | 5 s ms | 5 s acc/rej | 5 s stalls | 5 s result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| stock | 2203 | 368 / 175 | 170 | 2961 | 1016 / 516 | 517 | **FAILS** |
| floor only | 821 | 78 / 28 | 23 | 1900 | 364 / 172 | 168 | passes |
| projection only | 1211 | 368 / 175 | 168 | 2669 | 1016 / 516 | 517 | **FAILS** (identical `advanced=1.5322265625`) |
| **floor + projection** | **799** | **38 / 5** | **0** | **813** | **42 / 5** | **0** | **passes** |

With floor + projection the 2-node cases are unchanged (36/7 and 40/7, 0 stalls), the closures are
the same `1,1,0…`, and the two DEPOSITION events fire at the same velocities to 7–8 significant
digits (`0.17164602142 / 0.17169288550` vs stock `0.17164602128 / 0.17169288550`). The
`Conservative reconstruction fails equation gate`, `Solid population balance failed`,
`Component balance failed` and `Total energy balance failed` checks all still ran and none fired.

Why each alone is insufficient:

- **floor only**: alpha0 returns to 1.0, but now the full Newton step pushes a solid moment
  negative and *every one of the 24 backtracks* throws `IllegalArgumentException: Invalid solid
  moments` — `domainFailures = 24/24`, `bestNextNorm = Infinity`. The nonnegativity constraint
  simply moves from the step limiter to the domain check.
- **projection only**: alpha0 is still 1e-12…1e-18 (`alphadead`, binding `solidK` in all 168/517
  stalls) because the limiter still sees the dust. The 5 s run fails at the identical
  `advanced=1.5322265625 s`.

---

## What I could not determine

- **Where the dust is written into the accepted inventory.** I showed (E5, fresh chain) that one
  solve manufactures it, so persistence is not necessary for the stall, but I did not trace which
  branch of `ConservativeTransport.reconstruct` / `PassiveNetwork.Inventory` writes a 1e-59 kg
  population back, nor whether `SolidInventory`'s `massKg()==0` population filter is the only
  pruning that exists. A fix that prunes at the source needs that trace.
- **Why direction-blocking specifically is required.** The correlation is airtight (E5: unblocked
  0 stalls, `3,3` 0 stalls, `1,1` 335 stalls), and the plausible reading is that blocking cuts the
  solid supply while leaving the hydraulic flow running, so the downstream solid unknowns lose any
  physical scale. I did not produce a per-iteration trace proving that reading.
- **Whether non-chain topologies (junctions, branches, filters, pumps) hit the same wall.** Only the
  10-reservoir series chain was probed. Junction nodes use `junctionRows`, whose solid rows are
  mass-fraction-scaled (`solidScale[i]/solidScale[0]`) rather than absolute, so their dust
  magnitudes will differ.
- **Whether the `1e-12` floor is the right threshold.** It was chosen to sit between `n1`'s real
  ~1e-7 scaled value and `n2`'s 1e-12; I did not sweep it. The correct threshold should probably be
  derived from the island's total solid mass or the step's transported mass, not hard-coded.
- **Whether `alphasmall` stalls (alpha0 between 1e-9 and 1) exist in other cases.** Two appeared
  under the floor-only variant; none in stock.

---

## Recommendation (not implemented)

The defect is that **three hard-nonnegative solid-moment unknowns exist on every node of a
solid-bearing island, and they routinely hold values 30–70 orders of magnitude below their own
scale, which the unfloored `variables[c] > 0` nonnegativity limiter then treats as a live
constraint.** The smallest change I would expect to remove the stall is the *pair*:

1. `network/PassiveStepSolver.java`, `Equations.maximumStep` — replace the `variables[c] > 0` guard
   with a per-column floor, so a solid unknown below a relative threshold of its own
   `solidScale`/`amountScale` does not bound alpha. (A one-time `double[] clampFloor` built next to
   `amountVariables` and `differenceFloors` in the `Equations` constructor.)
2. `solver/PhaseLayout.java`, `decode` — project the three decoded moments onto `[0, ∞)`
   (`Math.max(0, …)`) instead of letting `SolidInventory.Moments` reject the trial, so the
   constraint is enforced by projection rather than by a domain exception.

Measured together: 170 → 0 stalls at 0.5 s, 517 → 0 at 5 s, 5 s goes from FAILS to 813 ms, and the
2-node case, the located closures and the DEPOSITION velocities are unchanged.

A fix agent must still check: (a) the accepted solution's solid rows are satisfied, so the
projection is only ever reached at trial points — but the `maximumResidual > 1e-8` reconstruction
gate and `checkConservation` must be re-run across the suite to confirm; (b) whether the projection
should instead live in `PhaseLayout.encode`/reconstruction, pinning sub-threshold moments to exactly
zero so the unknown starts at 0 (`variables[c] > 0` is then false anyway) — that is cleaner
physically and removes the dust rather than tolerating it; (c) whether `PhaseLayout`'s
island-wide `solidSupport` can be narrowed to nodes that can actually receive solids this step,
which would delete the unknowns instead of flooring them.

Two cheaper alternatives, both with caveats:

- **E2** (`blocked |= 3` in `SolidEventIntegrator`) removes the stall completely (5 s: FAILS →
  571 ms, 1 stall) and is a two-character change, but it changes the physics: a deposition event on
  one direction would also forbid the reverse direction, which the current `Pipe.blocked(flow)`
  API deliberately distinguishes.
- Presetting `boundaryClosed[i] = true` in `PassiveStepSolver` when the Newton seed flow lies in a
  blocked direction (rather than only when `blockedDirections == 3`) would get the active set right
  on pass 0 without changing the closure semantics. Untested — it addresses step 5 of the mechanism
  but not the underlying dust, so pass-0 stalls (32/170, 102/517) may survive.

Separately, and independently of the stall: **E3 shows the event bracket costs 1361 ms of the
2191 ms even when it never stalls** (430 ms with 0 stalls in the 2-node case). The locator always
spends its full 19 bisection probes, each a complete re-integration from the interval start at
`initialStep ≤ 1 ms`. That is worth its own work item.
