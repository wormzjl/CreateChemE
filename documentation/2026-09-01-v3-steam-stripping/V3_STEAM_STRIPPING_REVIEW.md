# Review: codex/v3-steam-stripping — steam stripping / free-water implementation

Date: 2026-09-01. Effort: max (10 finder angles + per-candidate verification + gap sweep).
Scope: the **uncommitted working tree** of the main checkout on branch `codex/v3-steam-stripping`
(branch tip = base 7bccf7b; 40 modified + 7 new files, reviewed via `git diff HEAD` + untracked
files). Plan of record: `documentation/V3_STEAM_STRIPPING_PLAN.md`.

## Verdict

Core W1 math is faithful to the plan and the water data layer is correct; the suite is green
(`.\gradlew.bat test` — BUILD SUCCESSFUL, 3m15s). But the branch is not landable as-is: it breaks
the dry Q_R=0 contract, weakens two audits into tautologies in a codebase where audits are the
sole publication gate, has zero end-to-end wet solve coverage, and silently steams the stock GUI
run. 11 findings below, ranked most-severe first.

### What was verified sound (no action needed)

- Dilution term `ln(V_hc/(V_hc+w_j))`, vapor-phase water enthalpy, steam inlet sources, known
  upward profile, and per-branch condenser regimes (NONE/FREE_WATER/ALL_VAPOR) match the plan;
  Jacobian coverage is automatic via the localTerms FD probes as predicted.
- `V3WaterProperties` transcribes IAPWS aux (Wagner–Pruß) Psat, NIST Shomate H2O(g) enthalpy, and
  Watson ΔHvap exactly (coefficients checked term-by-term against sources).
- Dry-input digest remains byte-identical (pinned hex test in `V3SideDrawContractTest:60-63`
  untouched and passing); steam fields hash only when present.
- The surrogate-publication hole (a dry surrogate-duty result escaping under a wet label through
  the material-closed fallback) was anticipated and correctly plugged in `V3ColumnCalculator`.
- Wire/NBT: WIRE_SCHEMA_VERSION 5→6, DATA_VERSION 5→6 with migration, MAX_STREAMS 6→7 — round-trip
  tested.

## Findings

### 1. Unconditional zero-duty rule breaks the dry Q_R=0 contract — CONFIRMED, correctness
`V3ColumnProblemResolver.java:111-112`. The new validation
`if (reboilerDuty == 0.0 && !V3SteamFeeds.hasSumpFeed(input)) throw …` applies to **all** inputs,
not just steam-bearing ones. Previously-valid dry `ReboilerDuty(0.0)` inputs are now rejected —
the working tree's own evidence is ~25 pre-existing tests edited from `ReboilerDuty(0.0)` to
`Double.MIN_NORMAL` to keep the suite green. Failure scenario: a saved world holding a dry
Q_R=0 draft loads → `readInput` validation throws `IllegalArgumentException` →
`ColumnCalculatorV3BlockEntity.loadAdditional` (:198-260) catches it and silently resets
`currentInput = defaultInput()` — user data loss on upgrade. **Fix**: scope the rule to inputs
with steam feeds (the physical rationale — log-singular reboiler without any vapor source — only
bites when the solve actually runs dry-bottomed; a dry Q_R=0 column was already legal), and
revert the 25 test edits as the proof.

### 2. No end-to-end wet solve test anywhere — CONFIRMED, test-coverage
`V3SteamFeedContractTest.java` (whole file). Every steam test stops at the resolver, profile,
digest, parser, or manufactured-state stream level; **no test in the tree calls `calculate()`
with a non-empty steam list**. The feature's centerpiece — dry rungs under surrogate duty
`Q_R + Σf·ΔHvap(450 K)`, then the joint wet λ-ramp with duty co-variation — has never executed.
A green suite currently proves nothing about whether any steam column converges, audits, or
publishes. **Fix**: add a registered-test-package end-to-end case (modest column + sump steam)
asserting convergence, audit pass, water profile in streams, and free-water/slip publication.

### 3. Wet condenser-phase audit is a self-comparison and disables phase-transition recovery — CONFIRMED, correctness
`V3AcceptanceAuditor.java:209`. `if (problem.hasSteamFeeds()) return wetTwoPhaseCondenserSplit(state);`
replaces the independent two-phase flash with a recomputation of the same formulation the solver
used — the check can no longer disagree with the solve, so wet CONDENSER_PHASE is vacuous. It
also removes the independent-flash disagreement signal that drives the warm condenser
phase-transition correction, so a wet run that converges on the wrong condenser branch is
published (or fails opaque) instead of being corrected. **Fix**: keep the independent flash and
make it water-aware (scale K by 1/(1−y_sat), or flash hydrocarbons at P − p_w).

### 4. freeWaterSplit failure path throws instead of failing — CONFIRMED, correctness
`V3AcceptanceAuditor.java:118`. When slip exceeds arriving water (`s·V_hc,0 > w₁`), the computed
free-water fraction is negative and the fail path builds
`Check.fail(…, fraction, 0.0, …)` — but the `V3AcceptanceAudit.Check` constructor requires
`value ≥ 0` and finite and **throws**. The calculator wraps audit exceptions into
`failedAudit("UNAVAILABLE")`, so the one condition this audit exists to catch collapses into an
untyped UNAVAILABLE instead of a named FREE_WATER_SPLIT failure. **Fix**: report a non-negative
violation measure (e.g. `max(0, −F_w)/w₁` against a zero-tolerance limit).

### 5. GUI prefills sump steam into every dry server state — CONFIRMED, correctness
`ColumnCalculatorV3Screen.java:254`. `loadInput` fills the sump-steam fields with
`DEFAULT_SUMP_STEAM_RATE` "28.8" kmol/h / "176.85" °C whenever the server state has no steam —
so opening and saving any existing dry column silently converts it to a wet one (different
formulation revision, different digest, different results). Violates the blank/zero-disables
convention the parser itself implements. **Fix**: prefill blank; defaults may live in a tooltip
or placeholder text only.

### 6. WATER_PROFILE audit is a tautology — CONFIRMED, correctness
`V3AcceptanceAuditor.java:75`. Both the "expected" and "actual" profiles come from the same
`V3SteamFeeds` helpers the resolver used, and the slip + free-water = w₁ leg is an algebraic
identity of the same expressions — the check cannot fail for any input. **Fix**: recompute the
profile with an independent traversal written in the auditor (plain cumulative loop over the
canonicalized specs) and compare against `problem.waterVaporFlowMolPerSecond` node-by-node.

### 7. Missing ReboilerDuty spec raises NoSuchElementException through IAE-only catches — PLAUSIBLE, correctness
`V3ColumnProblemResolver.java:110`. `….orElseThrow().watts()` throws `NoSuchElementException`
when the spec list lacks a ReboilerDuty — that type escapes both the solver's
IllegalArgumentException→STATE_DOMAIN wrapper and the block entity's `loadAdditional` IAE catch,
crashing instead of producing a typed failure / draft reset. Trigger requires a spec-less input
reaching the resolver (wire or NBT of a foreign/corrupted payload is the plausible path).
**Fix**: `orElseThrow(() -> new IllegalArgumentException(…))` here and in any sibling readers.

### 8. Wet LIQUID_ONLY condenser flash ignores water partial pressure — PLAUSIBLE, correctness
`V3AcceptanceAuditor.java:194`. The LIQUID_ONLY acceptance flash evaluates hydrocarbon K-values
at full column pressure although water vapor occupies `p_w = y_w·P` of the drum; the
hydrocarbon phase check should use the hydrocarbon partial pressure (P − p_w, equivalently K
scaled by 1/(1−y_w)) as the TWO_PHASE branch's dilution constant does. Effect: the audit is
biased toward accepting LIQUID_ONLY at hot/wet drums where vapor should exist. **Fix**: apply
the same water-aware scaling as the (restored, finding 3) two-phase flash.

### 9. No wet FD-vs-local Jacobian test — CONFIRMED, test-coverage
The local block assembler's wet derivatives (dilution term, water energy terms) are never
compared against the full finite-difference verification Jacobian; the existing FD-vs-local test
runs dry only. A sign or scale slip in a wet derivative would show up only as mysterious
convergence degradation. **Fix**: extend the FD-vs-local test to a steam-bearing manufactured
state.

### 10. Condenser slip/regime logic duplicated four times — CONFIRMED, reuse
`V3MeshResidualEvaluator.java:206-221` (`waterVaporFlow` + `waterDilutionLogTerm` duplicate the
same regime switch), with further copies in `V3AcceptanceAuditor` and
`V3ColumnStreamProperties.freeWaterFlow/waterVaporFlow`. Four hand-maintained copies of
`s = y_sat/(1−y_sat)`, `w₀ = s·V_hc,0` branching invite drift (finding 4 is a drift of exactly
this logic). **Fix**: one helper on `V3ColumnProblem` (or the regime enum) returning the split
for a state; auditor keeps its independent copy **only** where independence is the point (§
finding 6), clearly labeled as such.

### 11. "wet-ramp" diagnostic labels leak into dry runs — CONFIRMED, conventions
`V3ColumnCalculator.java:726`. The continuation path string builder tags draw-only (dry) ramp
runs with the "wet-ramp-…" label family, so dry diagnostics claim a wet path was taken. **Fix**:
derive the label from the actual ramp content (steam present or not).

## Suggested fix order

1. Finding 1 (scope the zero-duty rule + revert the 25 test edits) — restores the dry contract.
2. Finding 4 (freeWaterSplit non-negative measure) — one-line class of fix, unblocks audit trust.
3. Finding 2 (end-to-end wet test) — one test exercises most of the dark machinery and would
   have caught 3/6/11 on its own.
4. Findings 3 + 6 + 8 (audit independence and water-awareness) as one audit-integrity pass.
5. Findings 5, 7, 9, 10, 11.

Findings 1, 3–6 are blocking for the full-CDU plan (`documentation/V3_FULL_CDU_PLAN.md` §16
lists them as its prerequisite).

---

# Fix verification — 2026-09-01 (main @ 56771fc)

The steam feature landed on `main` as `1057efa` (the reviewed tree, committed verbatim) plus fix
commit `335fa0b` and merge `56771fc`. Re-review at max effort of the fix delta; full suite run
independently on the merge commit: **BUILD SUCCESSFUL, 3m46s, exit 0**.

## All 11 findings verified FIXED

1. **Zero-duty rule** scoped to steam-bearing inputs (`!input.steamFeeds().isEmpty() && …`);
   all ~25 `Double.MIN_NORMAL` test workarounds reverted to `ReboilerDuty(0.0)`; contract test
   renamed `acceptsDryZeroDutyButRejectsSteamWithoutAZeroDutySumpSource` and asserts the dry
   acceptance. Dry contract restored.
2. **End-to-end wet coverage**: five registered `calculate()` tests in `V3ColumnCalculatorTest`
   covering TWO_PHASE with slip, LIQUID_ONLY with free water, two water-limited drums, and a
   high-steam case asserting steam closure (vapor water + free water = authored steam to 1e-8).
3. **Wet condenser tautology**: `wetTwoPhaseCondenserSplit` deleted; replaced by a genuinely
   independent water-adjusted scaled-K flash (own fugacity calls, own Rachford–Rice bisection,
   damped substitution, water-limited outer loop) with a partial-pressure fallback; wet
   phase-transition recovery re-enabled and made water-aware in `V3CondenserPhaseTransition`.
   New test proves a wrong wet partition is rejected.
4. **freeWaterSplit throw**: fail path now reports a non-negative violation measure; the
   production split itself (`V3ColumnProblem.waterCondenserSplit`) clamps vapor water at
   arriving steam so free water is never negative; water-limited pass branch added + test.
5. **GUI prefill**: default constants deleted; all steam fields prefill blank; the 28.8 kmol/h
   suggestion moved into a notice line.
6. **WATER_PROFILE tautology**: expected profile recomputed by an inline cumulative loop in the
   auditor (no `V3SteamFeeds` calls), with an explicitly independent slip coefficient from the
   input spec. (Residual weakness: see new finding N4 below.)
7. **NoSuchElementException**: both resolver `orElseThrow()` sites and the calculator's
   `reboilerDutyWatts` now throw typed `IllegalArgumentException` with messages.
8. **LIQUID_ONLY full-P flash**: wet liquid-only acceptance now routes through the
   water-adjusted independent flash; transition flash uses hydrocarbon partial pressure.
9. **Wet FD-vs-local Jacobian test**: added (`…WithSumpSteam` in `V3BlockJacobianAssemblerTest`).
10. **Slip dedup**: single production helper `V3ColumnProblem.waterCondenserSplit`; evaluator and
    stream properties delegate; the auditor keeps one deliberately independent copy, documented
    as such.
11. **Ramp labels**: `RampStep` record carries per-feature path labels; dry draw runs log
    "side-draw ramp" again (test updated).

## New findings in the fix delta (5, reported via the review tool)

- **N1 (conventions, CONFIRMED)** `V3ColumnCalculator.java:836` — the wet residual math changed
  (condenser water capped at arriving steam ⇒ different dilution at water-limited drums) but
  `v3-wet-mesh-r6-steam` / `v3-wet-assumptions-r1` were not bumped: pre- and post-fix wet
  results share a digest despite different math. Suggest bumping the wet mesh label (r6→r7) or
  assumptions (r1→r2). Practical exposure is small (both commits same day, nothing shipped
  between), but this is exactly what the revision contract exists for.
- **N2 (correctness, CONFIRMED)** `V3ColumnCalculator.java:685` — `RampStep.progress()` =
  max(steam, draw) ⇒ every draw-phase rung of a combined ramp is labeled `draw-ramp-1.0` and a
  failure at draw fraction 0.25 logs "stopped at 1.0". Fix: report the currently-ramping
  feature's fraction.
- **N3 (altitude, CONFIRMED)** `V3ColumnCalculator.java:731` — combined-ramp intermediate
  failure skips the rest of BOTH phases and jumps to full steam + full draws (the measured
  skip-to-full pathology, amplified). The deep fix is Phase A0's adaptive bisection.
- **N4 (audit-integrity, CONFIRMED)** `V3AcceptanceAuditor.java:95` — the WATER_PROFILE
  condenser leg compares arriving water to a split that sums to arriving by construction; can
  never fail. A real check would compare the independent split against the production
  `problem.waterCondenserSplit(state)`.
- **N5 (diagnostics, CONFIRMED)** `V3AcceptanceAuditor.java:270` — dry columns' CONDENSER_PHASE
  detail now reads "water-adjusted scaled-K … flash" although the plain dry TP flash ran.

None of the five blocks the CDU workstream; N1 deserves the quickest follow-up (one-line label
bump), N2/N3 fold naturally into the Phase A0 ramp-hardening work
(`documentation/V3_CDU_CONVERGENCE_RISK.md`).
