# V3 literature CDU — suspended handoff

**Suspended at the user's request on 2026-09-06, approximately 15:52 Asia/Singapore (07:52 UTC). Do not resume automatically.**

Implementation changes are preserved, including unfinished and untested edits. The execution subagent has stopped. It reports no remaining task-owned test processes or sessions. No unfinished core changes were committed at suspension.

This handoff is saved in the original checkout for discovery. **All implementation work belongs in the separate worktree below.**

## Workspace and checkpoints

- Original checkout: `D:/Minecraft/Modding/1.21/CreateChemE`. It contains unrelated V4 work. Do not reset, clean, overwrite, or merge those changes.
- Active implementation worktree: `D:/Minecraft/Modding/1.21/CreateChemE/run/codex-worktrees/v3-literature-cdu`.
- Branch: `codex/v3-literature-cdu`, created from `main` at `6c7d446645792226194df56e58873dceae58bc69`.
- Execution was delegated to one **Astra Medium** subagent, as explicitly requested by the user. The parent performed independent review and diagnosis.
- `27d00eae1e8bb05fd5ac38e53a8ed94de72289a2`: freeze literature contract and qualify the reduced thermodynamic package.
- `d0464f774ab629331be88bc77620e4516f5fd692`: checked native CDU reference harness and unresolved-target evidence. This is the current committed HEAD.

The original and worktree copies of all 26 characterization files were independently checked against the manifests in `27d00ea`: **52 file copies matched**. Verification is in the worktree's `build/cdu-reference-probe/characterization-integrity.json`.

## Objective and operating contract

Implement the reviewed V3 literature CDU plan: use the reduced DWSIM characterization, model pumparounds as prescribed internal heat removal, retain main bottom stripping steam, and omit side strippers and their return streams/utilities. V3 only; preserve unrelated V4 work.

Authoritative plan: [V3_LITERATURE_CDU_PA_PLAN.md](D:/Minecraft/Modding/1.21/CreateChemE/run/codex-worktrees/v3-literature-cdu/documentation/V3_LITERATURE_CDU_PA_PLAN.md).

Frozen numerical contract: [tjl19-literature-cdu-v1.json](D:/Minecraft/Modding/1.21/CreateChemE/run/codex-worktrees/v3-literature-cdu/src/test/resources/science/column/v3/tjl19-literature-cdu-v1.json).

- Source: Ledezma Martinez's 2019 Manchester thesis, no-preflash case, with explicitly documented reconstruction assumptions. Figure 3.2 connections were visually checked independently. Do not conflate its side-stripper vapor-return stages with liquid withdrawal stages.
- 41 main-column contacts: V3 trays 1–40 plus bottom equilibrium node 41; separate condenser node 0.
- Crude feed at stage 37, 638.15 K, uniform column pressure 250 kPa. Reduced hydrocarbon feed: 737.6996333000835 mol/s and 159.65286 kg/s.
- Package: `createcheme:tjl19_dwsim`, revision `tjl19-dwsim-10.2.3-r1`, six real hydrocarbons plus 13 pseudocomponents. V3 handles water separately.
- Direct hydrocarbon side products at stages 10/18/28: 136.3888889/143.0555556/45.8333333 mol/s. Native total wet withdrawals must be adjusted to enforce these hydrocarbon rates.
- Physical stage duties at 8/16/26: **−12.84/−17.89/−11.20 MW**, total **−41.93 MW**. Positive means heat added. No PA material circulation is represented.
- Condenser 332.15 K; organic reflux/net organic liquid product ratio 4.17; external bottom heat **0 W**.
- **Main bottom steam is retained at stage 41: 1,200 kmol/h.** Supply 533.15 K and 450 kPa are explicitly inherited assumptions. Native inlet temperature after isenthalpic pressure reduction is approximately 530.796518 K. V3's W1 steam calorics differ from native PR; the measured supply-energy difference at this rate is −64.9769 kW.
- Side strippers, their steam/reboilers and vapor returns are removed. Do not transfer their utilities into the main column.
- Mass acceptance ceiling: **0.001 kg/kg of original hydrocarbon feed**, using summed absolute component errors. Water closure is separate.

Reviewed order was: (1) source contract, (2) reduced property package, (3) independent native reference, (4) restricted continuation/truncation/mass fixes, (5) prescribed stage heat core, (6) full target qualification, (7) PA on/off and 19/31-component comparisons, (8) persistence/network/UI. Steps 1–2 are committed. Step 3 has qualified infrastructure and small cases, but its full target remains open. After bounded native trials, independent core steps 4–5 were started; full reference qualification remains a gate before benchmark claims. Broader phase-specific truncation, pruning and phase-appearance changes remain conditional.

## Committed, verified work

[Thermodynamic milestone](D:/Minecraft/Modding/1.21/CreateChemE/run/codex-worktrees/v3-literature-cdu/documentation/V3_LITERATURE_CDU_MILESTONE_1.md): reduced property package, full frozen interaction matrix, degree-five ideal-gas Cp fit with analytic integrated enthalpy, held-out native checks, feed VLE and source-contract tests. Legacy packages retain their prior arithmetic path. **Full suite: 360 tests passed, zero failures/errors/skips** at this checkpoint.

[Native reference milestone](D:/Minecraft/Modding/1.21/CreateChemE/run/codex-worktrees/v3-literature-cdu/documentation/V3_LITERATURE_CDU_REFERENCE_MILESTONE.md): `scripts/dwsim/CduReference.cs`, `run-cdu-reference.ps1`, and curated evidence under `output/cdu-reference/evidence`. Dry/wet Newton and wet Sum Rates smoke cases and all three saved-case replays passed. A native condenser test produced gas, organic liquid and aqueous liquid and verified reflux ratio 4.17.

The native PR78 enthalpy implementation was found inconsistent with its fugacity function. The harness uses native ideal enthalpy plus `−R*T²*sum(x*dln(phi)/dT)`, with independent temperature-step checks. It never uses the V3 column solver as its reference. Native ideal integrals are cached only at exact temperatures and matching property bases.

Reference bundles contain native XML, checksums, native DLL hashes, caloric revision and solver-specific heat metadata. **They require the checked replay loader; they are not directly openable, qualified DWSIM files.** Callbacks are restored before calculations. Original characterization files and installed binaries were preserved.

The native solvers interpret compiled stage heat oppositely: Newton's positive Q adds heat; Sum Rates' positive Q removes heat. Connected energy streams provide `Q = -EnergyFlow`. The adapter keeps physical heat separately and translates by solver. ±1 kW tests prove the convention: correct Sum Rates translation closes within 7.14e-6 kW; wrong translation produces approximately ±2 kW error. The condenser's `Vessel.DeltaQ` is authoritative; its connected energy stream was observed to remain zero.

**No full native CDU reference is qualified.** Existing full trials have an open reflux guess and initially use native total-wet side rates. Reflux closure, hydrocarbon-only draw adjustment, full authored boundaries, and full stage/global/phase/water audits remain unfinished. Raw large trial logs remain local and were not committed.

## Precise native energy failure — latest user question

The rejected manufactured continuation trial at lambda 0.40 has:

| Quantity | kW |
|---|---:|
| Incoming stream enthalpy | 48263.6433087473 |
| Physical heat added | −16772.0000000000 |
| Outgoing stream enthalpy | 31986.5316100567 |
| Incoming + heat − outgoing | **−494.8883013094** |

All 41 stage residuals sum to the same error. Major stage residuals are −2185.730259 kW at stage 8, −6096.230825 kW at stage 16, and −4414.381613 kW at stage 26. Positive neighboring residuals partially cancel them. Hydrocarbon mass error is only 3.518e-9 kg/kg and retained-component log-fugacity error 9.992e-8, so mass/VLE closure alone does not establish energy closure.

**The parent confirmed bound-induced false convergence by reading the installed solver's executable IL.** With `RelaxTemperatureUpdates=false`, Sum Rates imposes:

`T_lower = max(100, min(T_initial) - max(0.5*(max(T_initial)-min(T_initial)), 25))`.

The accepted lambda 0.20 starting profile has minimum 454.2856560513721 K and maximum 483.1126067834414 K. Its lower bound is therefore exactly **429.2856560513721 K**. Exactly **22 stages** in the rejected next trial equal this bound. The installed method clips updated temperatures, computes its squared temperature-change measure **after clipping**, and terminates on temperature/composition changes without requiring an energy-residual norm. Iteration 56 reports 5.36622e-11 temperature-change error and 1.22046e-14 composition-change error, while the large energy residuals remain.

The phases remain distinct with positive liquid/vapor flows; the observed plateau does not establish phase disappearance. This finding applies to the **installed binary**: the fetched upstream Sum Rates source does not contain this particular temperature clamp. The earlier heat-sign issue was already corrected in this trial.

Detailed read-only evidence is in the worktree's `build/cdu-reference-probe/native-energy-diagnosis.md`, `sumrates-installed-il.txt`, `InspectSumRatesIL.cs`, and `native-rejected-profile-summary.json`. IL argument 17 is T; bound construction is around IL_0596–065b, clipping IL_2c80–2cd4, post-clipping delta measurement IL_339f–33ca, and termination IL_3cb3–3cd9. No installed binary was patched and no new solver experiment was run for this diagnosis.

When native work resumes after core steps 4–5, the first controlled remedy is adaptive subdivision: try lambda 0.30 from accepted 0.20, then 0.40 from a newly accepted state. Smaller steps move the initial-profile temperature envelope. Continue to reject clipped false convergence through independent energy audits. This is a proposed next experiment, **not a verified fix or proof that the target is feasible**.

## Uncommitted core work at suspension

**Steps 4–5 are unfinished. The latest tree has not been fully compiled or tested.** Preserve the worktree diff and untracked files.

Step 4 edits:

- Explicit `V3MolecularWeightProvider`, implemented by PR thermodynamics.
- Independent external absolute-component and omitted-edge mass checks, each capped at 0.001 kg/kg of original HC feed. Finite-positive normalization required.
- Existing `TRUNCATION_MASS_DEFECT` remains explicitly a mol/mol diagnostic; its old `8*cutoff` acceptance authority is removed. Benchmark consumer retains old units and reports the new independent mass budget.
- Truncated states without molecular weights fail closed. Untruncated exact/Holland fixtures without MW use the explicitly labelled conservative upper bound `max_i(abs(component molar error)/feed_i)`, valid for any positive molecular weights. Do not infer 400 g/mol from Holland's `heavy400` name. Manufactured fixtures declare their chosen weights.
- Strict side-draw trial feasibility and early failed-candidate audit. Seed-only liquid repair provides positive starting downflow and emits diagnostics; authored draws and accepted outputs are not clipped.
- Accepted continuation anchors only; currently at most four subdivisions and four support refreshes total, at most two refreshes per intermediate rung. Identical retained masks skip redundant refresh solves. Support remains immutable inside each Newton/Jacobian/line-search attempt.
- Previously omitted zero slots cannot justify renewed omission. Their component paths are conservatively reinserted before new support decisions. Seed floors are applied after the decision.

Step 5 partial edits:

- Immutable `V3StageHeatSpec`, canonical validation, equality/hash/digest coverage, input-copy preservation and coarse-grid remapping.
- Independent steam/draw/heat continuation fractions and physical `+Q` stage energy terms.
- `V3GlobalEnergyAudit`, invoked for heat-bearing inputs. It computes condenser duty from the local overhead/condenser balance, excludes internal reflux from external products, and includes side products and water.
- Guards at existing game transport/save/edit boundaries reject nonempty heat inputs until that later integration is implemented. No heat UI or wire-format rollout is complete.

**The global-energy audit and latest evaluator refinements were added after the last test run and remain uncompiled/unverified.** Heat-specific manufactured, Jacobian, global-energy, legacy-identity and target-contract tests still need to be written. No complete core milestone report or final full suite exists.

Modified production files are the V3 acceptance auditor, calculator, input, digest, residual evaluator, side draws, truncation support, PR thermo, plus small guards in `ColumnV3Network`, `ColumnCalculatorV3BlockEntity`, and `ColumnCalculatorV3Screen`. Modified tests include the benchmark worker, side-draw and truncation audits/calculators/support fixtures. New files are:

- `src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3MolecularWeightProvider.java`
- `src/main/java/com/wormzjl/createcheme/science/column/v3/V3StageHeatSpec.java`
- `src/main/java/com/wormzjl/createcheme/science/column/v3/V3GlobalEnergyAudit.java`
- `src/test/java/com/wormzjl/createcheme/science/column/v3/V3FailedStateRefreshReplayTest.java`
- `src/test/resources/science/column/v3/f06-failed-ten-percent-replay.json`

These paths are relative to the implementation worktree. Use `git status --short` there for the authoritative current list.

## Test evidence and unresolved regression

- Manufactured truncation/support, side-draw audit and benchmark-consumer focused tests passed before the latest heat additions.
- Preserved historical F06 10% failed-state replay passed: **49 removed points, 8 iterations, residual 7.367984789e-9**. External absolute-component mass error **1.950418320e-8 kg/kg**; omitted-edge mass **1.950406676e-8 kg/kg**.
- Latest completed run: **11 tests, 10 passed, 1 failed**. `V3TruncationAuditTest`: 5/5 passed. `V3SideDrawCalculatorTest`: 5/6 passed; the 100 kPa, cutoff 1e-6 case exceeded its **45-second** deadline. Test XML timestamp is approximately 15:49 local.
- After that failure, the parameterized side-draw test's deadline was changed from 45 to **120 seconds for measurement**, but **not rerun**. This is not a resolved performance regression. Inspect the diff: the edited shared callback applies to that parameterized test method, not only one data row.
- The hot-condenser fallback assertion was updated to check honest fallback success instead of requiring the obsolete molar failure family. That edit has not been rerun.
- The full 360-test success belongs to committed milestone 1, **not the current dirty tree**.

Completed execution sessions reported by the subagent: 60869, 80697, 14588 and 54517, with final exit codes 1, 0, 1 and 1. None require termination. No new test or implementation was launched after suspension; only this handoff and status inspection were performed.

## Resume instructions — only after user requests continuation

1. Work in the named worktree. Read its diff, this handoff and the two committed milestone reports. Preserve all unrelated original-checkout work and frozen data.
2. Compile the latest core changes, inspect the unfinished global-energy audit, and measure the remaining 100 kPa truncated cold-solve regression. Do not declare it fixed by widening a test timeout. Check needless support changes/retries and their actual cost.
3. Finish step 4 verification: accepted-anchor subdivision, genuine support refresh, reactivation, positive draw feasibility, unequal-MW/cancellation/steam-denominator mass tests, preserved replay, and previously successful cases. Avoid vacuous `allMatch` assertions for required mass-audit families.
4. Finish step 5 tests: signed heat, immutable input/digest/copy behavior, unchanged degrees of freedom and Jacobian bandwidth, nonzero-heat Jacobian versus finite differences, independent global energy closure, empty-list legacy behavior, and exact literature stage mapping. Keep all three boundary controls and bottom steam semantics intact.
5. Run appropriate focused checks and the full suite once changes stabilize, write an honest core milestone report and commit coherent completed work. Update formulation/report revisions consistently with changed acceptance behavior.
6. Return to native qualification using the diagnosed bound and adaptive path idea. Close reflux, enforce hydrocarbon-only side rates, require strict final stage/global energy and phase audits, and quantify native dissolved-water differences versus V3 W1.
7. Only then make full target comparison, PA on/off and 19/31-component sensitivity claims. Persistence/network/UI remains later work. Broader truncation or phase-appearance changes require evidence that they are needed.

Root review notes for continuation are in the worktree's `build/cdu-reference-probe/step4-review.md` and `stage-heat-review.md`. Original historical replay evidence is under `D:/Minecraft/Modding/1.21/CreateChemE/build/v3-truncation-investigation-20260905`; only trimmed fixtures were copied, originals preserved.

Build environment: PowerShell; DWSIM in `C:/Program Files/DWSIM`; native helper compiled with .NET Framework 4 `csc.exe`. Java tests use the worktree's Gradle wrapper and cached toolchains. The known working command is `./gradlew.bat --offline test --no-daemon` from the worktree; previous runs required the normal user environment rather than the sandbox account's missing Gradle cache. Git read operations in the worktree may require `git -c safe.directory=D:/Minecraft/Modding/1.21/CreateChemE/run/codex-worktrees/v3-literature-cdu ...` because of sandbox ownership. No automatic resume or recurring automation was created.
