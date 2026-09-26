# V4 initializer integration

Branch `claude/v4-initializer-integration`, merge commit `614300a`.

## Branches merged

| Role | Branch | Sha | Contents |
| --- | --- | --- | --- |
| Base (first parent) | `claude/v4-r2-neural-budget` | `ba5ea45` | opt-in `V3InitializationOptions.Correction` progress rule (default `WALLS`, bit-identical), opt-in `DecodeOptions` presence lift and zero-phase floor (default `NONE`), in-flight iteration publication on neural budget exhaustion (`NeuralProgress` threaded through `SolvePolicy`, no-op defaults on `V3NewtonTrace`), `tools/decoder-floor-followup/`, `tools/neural-budget/` |
| Merged (second parent) | `claude/v4-r6-liquid-supply-screen` | `03693b5` | path-dependent heat-bound fix (`PathDependentHeatBound` → `NONCONVERGENCE` + hint, `terminalFailure` 256-char event bound, `CondenserAttempts.recordPathDependentHeatBound`), new `V3LiquidSupplyScreen` at both `calculate` entries, `calculate(..., ratio)` overloads, `columnV3LiquidSupplyScreenRatio` config, `tools/path-dependent-infeasible/`, `tools/liquid-supply-screen/` |

Both descend from `codex/v4-transformer-investigation` @ `c2cab76`, which is the merge base.
Intermediate ancestors: R2 via `claude/v4-r3-decoder-floor` @ `55b41ef`; R6 via
`claude/v3-path-dependent-infeasible` @ `4d14c38`.

```
git checkout -B claude/v4-initializer-integration claude/v4-r2-neural-budget
git merge --no-ff claude/v4-r6-liquid-supply-screen
```

## Conflicts and resolutions

Two conflicted paths. No side was dropped in either.

### 1. `.gitignore`

Both branches appended to the `tools/*` allowlist. Kept all four entries, in branch order, and
added `!tools/integration/` for this document:

```
!tools/decoder-floor-followup/
!tools/neural-budget/
!tools/path-dependent-infeasible/
!tools/liquid-supply-screen/
!tools/integration/
```

### 2. `V3ColumnCalculator.java` — the private learned `calculate` signature

One textual conflict. Both branches widened the same private learned entry: R2 added a trailing
`V3NewtonTrace trace` plus a delegating overload that passes `V3NewtonTrace.NONE`; R6 added a
trailing `double liquidSupplyScreenRatio`. Resolved by carrying **both** parameters, keeping R2's
delegation shape so R6's public eight-argument overload keeps its exact contract:

```java
private static V3ColumnOutcome calculate(..., Consumer<V3NeuralSeed> observer,
        double liquidSupplyScreenRatio) {
    return calculate(..., observer, liquidSupplyScreenRatio, V3NewtonTrace.NONE);
}

private static V3ColumnOutcome calculate(..., Consumer<V3NeuralSeed> observer,
        double liquidSupplyScreenRatio, V3NewtonTrace trace) { /* body */ }
```

A second, non-textual break came out of the same collision: R2's `calculateWithNeuralTrace` called
the private entry with `trace` sitting in the argument position that is now the screen ratio. Git
did not flag it — the call site was untouched on the R6 side — but it does not compile against the
merged signature, since `V3NewtonTrace` is not a `double`. Fixed explicitly: the seam passes
`DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO`, so it observes the production learned entry rather than a
differently screened one.

Everything else in the file auto-merged, and each region was read back and confirmed:

- the merged learned entry runs `V3LiquidSupplyScreen.requireRatio` (argument validation), then
  `validateInput`, then the **screen verdict**, then `staticCoolingAdmission`, then
  `new NeuralProgress(trace)`, then the candidate loop;
- R2's `NeuralProgress` publication (`reason = "neural budget exhausted; " + progress.summary()`)
  and `neuralFailure(reason, progress)` on the `LNN_ONLY` path both survive;
- R6's four-line comment above the `PROPERTY_OUT_OF_RANGE` / `INFEASIBLE_SPECIFICATION`
  short-circuit survives verbatim;
- R6's `terminalFailure` 256-character event bound (`List.of(boundedEvent(summary))`) survives;
- R6's ratio-threaded classical backup (`calculate(..., liquidSupplyScreenRatio)` /
  `calculateWithAcceptedProfile(..., liquidSupplyScreenRatio)`) survives;
- `correctNeuralSeed` keeps R2's `policy.trace()`, `neuralRungBudget(options)` and
  `neuralExtension(options)`; R6 never touched it.

One incidental cleanup: R2's `neuralFailure` carried its own copy of the 256-character truncation
(`summary.length() <= 256 ? summary : summary.substring(0, 256)`), which is byte-identical to the
existing `boundedEvent` helper that R6's `terminalFailure` fix now also uses. Collapsed onto
`boundedEvent` so the bound has one implementation in the file. No behavioural change.

### Ordering, confirmed by inspection

Both public entries produce the screen verdict before any flash and before any model call.

- Learned entry: `requireCutoff` → `requireRatio` → `CURRENT_ONLY` short-circuit → `validateInput`
  → **`liquidSupplyScreen`** → `staticCoolingAdmission` (the first feed flash) → `NeuralProgress`
  → `model.predict` / `phaseAware.candidates`.
- Classical entry: `requireRatio` → `totalDraw >= totalFeed` gate → **`liquidSupplyScreen`** →
  `staticCoolingAdmission` (the first feed flash) → `calculateBranch`.

## Suite

```
./gradlew.bat --offline compileJava compileTestJava
./gradlew.bat --offline test --console=plain
```

**580 tests, 1 failure** — only the known pre-existing
`V3SideDrawCodecTest.malformedAndOversizedListsAreRejectedBeforeAllocationOrSilentMigration`
(Netty `DecoderException` where the test expects `IndexOutOfBoundsException`).

That failure was traced rather than assumed: it reproduces at the shared merge base `c2cab76`
(`./gradlew.bat --offline test --tests "*V3SideDrawCodecTest*"` → 5 tests, 1 failed) and does **not**
occur on `main` `f01a28e` (5/5 green). So it entered with the neural stack `caa466d..ef05260`, which
sits between `main` and `c2cab76`, and is unrelated to either merged branch and to the merge.

New tests from every branch pass:

| Test class | Result |
| --- | --- |
| `V3LiquidSupplyScreenTest` | 8 / 8 |
| `V3PathDependentInfeasibilityTest` | 3 / 3 |
| `V3DecoderPresenceFloorTest` | 5 / 5 |
| `V3NeuralInitializationTest` | 9 / 9 (6 at `c2cab76` + the 3 R2 additions) |

## R6 re-verification on the merged build

```
python tools/liquid-supply-screen/verify.py prepare
python tools/liquid-supply-screen/verify.py run     --label merged
python tools/liquid-supply-screen/verify.py compare --label merged
```

`PATH` `python` on this machine is the Windows Store stub (exit 9009) and there is no registered
Python install, so the three commands were run with the standalone interpreter at
`C:\Users\wormz\AppData\Local\Comfy-Desktop\ComfyUI-Installs\ComfyUI\standalone-env\python.exe`
(CPython 3.13.12). `verify.py` is stdlib-only, so no other change was needed. **The script itself
needed no fixing** — its `ROOT` resolves to the containing worktree, so `run` compiled the 106
merged V3 sources, not stale bytecode.

Result: **passed, all three criteria**, over the full 405-case validation population with the
ten-worker classical probe (`WORKERS = '10'`, 30 s request deadline, 4 GiB heap; the script asserts
`distinctWorkerThreads == 10`). Wall time 152.0 s.

- **Criterion 1** — exactly the same **16** ids are screened, identical to the R6 archive; no
  missing, no unexpected, no detail mismatches, every published ratio and limiting tray agreeing
  with the independent Python port to four significant figures at zero Newton iterations.
- **Criterion 2** — all **110** archived strict successes are still `ACCEPTED` *on the identical
  Newton trajectory* (zero iteration drift), no false positive, no unexpected status transition.
  Worst protected ratio 0.21759, margin 1.379 under the shipped 0.30.
- **Criterion 3** — the caught set cost 123.272 s in the archived control and 0.0013 s now
  (123.27 s reclaimed, 7.59 % of all control time; slowest caught case 0.213 ms).

Two cases (`gd-s59-w0-p2-d0-r00`, `gd-s59-w1-p2-d1-r01`) moved `DEADLINE_EXCEEDED` →
`NONCONVERGENCE` against the archive. Both have ratios far below the threshold (0.0 and 0.063) so
neither is screened; they sat on the wall-clock 30 s boundary and landed under it this run. The
script classifies these as `deadlineBoundaryTransitions` by its own rule rather than absorbing them,
and they are the only difference from the R6 archive's status histogram.

`compare` writes to `tools/liquid-supply-screen/verification.json`, which is R6's committed archive.
That file was restored byte-identical afterwards; this run's document is kept beside this one as
`tools/integration/liquid-supply-screen-verification-merged.json`.

## Cherry-pick findings for `main` (`f01a28e`)

R2 cannot go to `main` at all: `main` lacks the whole neural stack (`caa466d..ef05260`), and every
R2 change is inside it.

Tested on a throwaway branch, since deleted; `main` was not modified and is still `f01a28e`:

```
git checkout -b tmp/main-cherry main
git cherry-pick 4d14c38      # path-dependent fix
git cherry-pick 7d08ef7      # liquid-supply screen
./gradlew.bat --offline compileJava compileTestJava test
git checkout claude/v4-initializer-integration
git branch -D tmp/main-cherry
```

**Neither commit cherry-picks cleanly.** Both conflict, and in every case for the same single
reason: the hunks are anchored in, or interleaved with, neural code that does not exist on `main`.

### `4d14c38` — path-dependent fix: 2 conflicts

| Path | Conflict | Resolution |
| --- | --- | --- |
| `V3ColumnCalculator.java` | one ~195-line region with an **empty** `HEAD` side: the four-line comment this commit adds above the neural loop's `INFEASIBLE` short-circuit drags the whole learned entry, `correctNeuralSeed`, `initializationEvent` and `NeuralBudgetExceeded` in as context | took `main`'s side; the comment has no host on `main` |
| `.gitignore` | `main` has a blanket `tools/`; the commit converts it to `tools/*` + allowlist | took `main`'s blanket ignore |

Everything substantive applied cleanly: `PathDependentHeatBound`, both retyped heat gates, the
`terminalFailure` 256-char bound, `CondenserAttempts`, `V3HeatFeasibility`,
`V3PathDependentInfeasibilityTest` and the two pumparound test updates.

### `7d08ef7` — liquid-supply screen: 4 conflicts

Two more paths conflict than the calculator and `.gitignore`, because the screen's config plumbing
is interleaved with the neural config plumbing throughout:

| Path | Conflict | Resolution |
| --- | --- | --- |
| `V3ColumnCalculator.java` | same empty-`HEAD` neural region (the learned-entry screen hook) | took `main`'s side |
| `CreateChemE.java` | 2 hunks: the 4-import block adds `V3ColumnCalculator` **and** three neural imports; the field block adds `COLUMN_V3_LIQUID_SUPPLY_SCREEN_RATIO` **and** five neural config fields | kept only the `V3ColumnCalculator` import and the screen field |
| `ProcessSolveServices.java` | 3 hunks: `main`'s `V3ColumnCommand` record has 3 components, R6's has 5, and the screen adds a 6th — so the record header, its convenience constructors and the dispatch call all collide | rewrote as a 4-component record on `main`, dispatching to the classical `calculate(input, control, cutoff, closure, ratio)` overload |
| `.gitignore` | as above | took `main`'s blanket ignore |

`V3LiquidSupplyScreen.java`, the screen's `V3ColumnCalculator` helper/constant/classical hook, the
classical ratio overloads and `V3SideDrawCalculatorTest` all applied cleanly.

**Landing the screen on `main` is a port, not a cherry-pick.** After the resolutions above,
`compileJava` succeeded but `compileTestJava` failed: 5 of the 8 `V3LiquidSupplyScreenTest` tests
reach the learned entry.

- 3 are portable mechanically — they use the learned overload only to reach an explicit ratio, and
  were rewritten onto the classical `calculate(input, control, 0.0, 0.0, ratio)` overload.
- 2 are not, and were dropped for the probe:
  `theLearnedRouteIsScreenedAtTheSameEntryAsTheClassicalOne` (no learned route on `main`) and
  `aDisabledCalibratedTierAdmitsARequestBetweenTheTwoThresholds` (it asserts
  `INITIALIZATION_FAILURE`, which is the `LNN_ONLY` route's outcome, not the classical one — this
  one needs a new classical assertion written for it, not a mechanical rewrite).

With that port applied: **`compileJava`, `compileTestJava` and `test` all pass on `main` — 511
tests, 0 failures, 0 errors**, including `V3LiquidSupplyScreenTest` 6/6 and, as noted above,
`V3SideDrawCodecTest` 5/5 green.
