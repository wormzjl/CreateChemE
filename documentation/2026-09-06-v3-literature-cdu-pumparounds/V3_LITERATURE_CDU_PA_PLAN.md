# V3 literature CDU plan: internal cooling and direct side products

Date: 2026-09-06. Status: characterization complete; implementation work reordered after review of current V3 code. No full CDU solve has been performed for this task.

Update: the user requested characterization using the installed DWSIM. A 31-component DWSIM 10.2.3 reconstruction, native feed case and independent checks are now available in [the characterization report](../../output/cdu-characterization/tjl-dwsim-10.2.3/REPORT.md). Property reconstruction is completed on the documented DWSIM basis; it does not recover the original HYSYS property table. The remaining gates are the exact stage connections, operating-specification mapping and full-column comparison.

This follows the requested scope: V3, pump-arounds (PAs) represented by internal heat exchangers, and no side strippers. It supersedes the scope of `V3_FULL_CDU_PLAN.md` for this experiment.

Selected working basis, per the user's subsequent request: **13 merged pseudo components plus six real components (19 total)**. Use [the reduced DWSIM characterization](../../output/cdu-characterization/tjl-dwsim-10.2.3-13pc/REPORT.md) for initial column work and retain the 31-component reconstruction as the resolution reference. Adjacent pairs are merged, with the heaviest cut kept separate; every group's mass, moles and standard liquid volume are conserved. The feed vapour fraction shifts by -0.1679 percentage points and feed enthalpy by -0.0720%. This lowers the fully retained interior-stage unknown count from 63 to 39; improved full-column convergence is still to be measured.

## Reviewed execution order

This section is the execution order. Later sections provide technical detail by topic.

Already complete: source selection; DWSIM 31-component reconstruction; reduction to 19 hydrocarbon components; group conservation; native feed flashes and case reloads; independent fugacity/balance checks; property, thermal-grid and interaction exports. These are characterization and feed results, not a converged column benchmark. Do not repeat characterization as a prerequisite.

| Order | Remaining task | Required result before proceeding |
|---|---|---|
| 1 | Freeze the column topology and operating contract | Verified stage map and one closed specification set, with unresolved source assumptions explicitly resolved or labelled |
| 2 | Integrate the 19-component property package into V3 and check thermodynamic agreement | V3 reproduces the reduced DWSIM feed and selected property states within documented model/representation differences |
| 3 | Build and qualify the simplified DWSIM CDU reference | Saved reference with direct side draws, internal cooling, main steam and the same operating contract; balance and phase checks pass |
| 4 | Implement the demonstrated V3 continuation/truncation corrections | Accepted-state continuation, bounded support refresh, independent mass cap and draw-feasibility checks pass focused regressions |
| 5 | Add V3 prescribed stage heat duties | Residuals, initial estimates, continuation and audits account for heat consistently; zero-heat and small-column checks pass |
| 6 | Solve and qualify the complete 19-component V3 CDU | Exact authored target converges and passes fresh residual, mass, energy, phase and draw audits; comparison against DWSIM is recorded |
| 7 | Measure PA effects and the cost/benefit of component reduction | Controlled PA-on/off and 19/31-component comparisons, with model differences separated from convergence/runtime results |
| 8 | Integrate the qualified case into persistence, networking and UI | Round trips and calculator operation reproduce the qualified input and result; relevant tests pass |

### 1. Freeze the column topology and operating contract

Read the flowsheet figure to identify exact feed, draw, PA return and steam stages. Reconcile the 41-stage source count with V3's separate condenser and bottom equilibrium node. Resolve condenser temperature versus overhead-product specifications, reflux definition, side-product flow basis and steam inlet enthalpy. Retain the requested simplifications: no preflash or side strippers, three direct net side products, main stripping steam, zero external bottom reboiler heat and the stated PA duties as internal cooling. Store the actual stage-duty assignments explicitly.

Choose the water treatment for the comparison here. The 19-component count describes hydrocarbons; DWSIM needs water for the wet reference, while V3 currently carries water through its separate steam model. Identify and measure differences between those treatments, including any liquid water on internal stages. A water-phase mismatch must not be mistaken for a solver discrepancy.

**Deliverable:** one versioned case specification consumed by the DWSIM reference builder and V3 fixture, with provenance for all inputs and no extra output constraints that overdetermine it.

### 2. Integrate and qualify the reduced V3 thermodynamics

Create a new property-package identity for the reduced dataset and import its MW, critical properties, fitted acentric factors, molar feed, density metadata and full interaction matrix. The exported native heat-capacity grid is not yet a V3 implementation: fit or represent it consistently with `V3PropertyComponent`'s polynomial in `T - 298.15 K` and its analytic enthalpy integral. Check errors at additional temperatures, not only the fitting points. Extend the representation if a cubic cannot meet the chosen error budget.

Compare ideal-gas Cp and relative enthalpy, PR phase enthalpy/departure, fugacity and feed flash before tuning the column solver. Record the known low-omega coefficient difference and the 11 nonzero light-component interaction pairs. The independent Python checks of exported DWSIM properties do not replace this V3-versus-DWSIM test.

### 3. Establish the DWSIM column target

Use the existing installed automation interface to build the **requested simplified column first**. Use the 19-component dataset, the frozen stage map, direct side-product rates, internal heat removal and main steam. Solve from an easier loading if necessary and retain the final source-based settings. Export temperature and phase-flow profiles, side products, condenser/bottom results and all duties, with mass/energy and phase diagnostics.

This precedes the full V3 solve so there is an independently calculated target. If the authored DWSIM column cannot converge, investigate its specifications and physical feasibility before treating the corresponding V3 failure as proof of a solver defect. Small V3 heat tests and property work can still proceed. The original source topology with actual PA loops and side strippers is an optional later study of approximation error, not a prerequisite for this deliverable.

### 4. Correct the measured V3 numerical weaknesses

First make the acceptance accounting explicit: MW-weighted summed absolute external component error and independently reconstructed omitted mass must satisfy the 0.001 hydrocarbon-feed-mass cap. Report water closure separately and retain the ordinary retained-equation and energy tolerances. The cap is independent of the mole-fraction cutoff.

Then retain the last accepted continuation state, subdivide rejected increments, and bound retries. Enable the existing truncation mechanism at intermediate rungs and allow support rebuilds between Newton attempts with proper omitted-component reconstruction/reactivation. Keep fallback provenance explicit. Make trial/seed handling respect feasible post-draw liquid flow without clipping away material.

Qualify these changes on the captured support-refresh replay, the matched F05/F06 diagnostics, and previously successful small cases. The known replay proves one intermediate can be repaired; it does not establish success at the 40% draw target. Phase-specific truncation and automatic stage phase changes are not included in this first correction.

### 5. Add and qualify internal heat duties

Implement the stage/duty contract, input validation, resolved heat source, energy residual, initial energy estimates, continuation and independent energy audit together. Test zero duty, a known cooling sign/magnitude, multiple heat locations, local versus independent Jacobian agreement, and a small wet column with a side draw. Use the corrected continuation policy from step 4 for heat/steam/draw ramps.

Persistence and UI are deferred to step 8; the core fixture must carry exact authored duties and identify the applied continuation duties throughout this step.

### 6. Qualify the full reduced V3 case

Combine the stage map, registered property package, steam, direct draws and internal cooling. Use accepted-state continuation to the exact authored target and require both solver convergence and a fresh acceptance audit. Compare with the saved DWSIM reference, separating hydrocarbon-property, water-model and column-solver discrepancies. Record actual failure steps and physical margins when a target is not reached.

### 7. Run controlled comparisons

For PA-on/off, hold all other physical inputs and numerical controls fixed; compare common accepted draw loadings if one target fails. Measure liquid supply immediately below the side draws. For 19 versus 31 components, use the same physical operating contract, allowing each characterization its own correct feed enthalpy, and compare products, temperature/flow profiles, iterations and runtime. The existing feed-flash comparison is not enough to establish full-column accuracy or faster convergence.

Do not require either the deliberately uncooled case or the 31-component case to converge in order to accept an otherwise independently qualified 19-component target. Report the limits of the comparison rather than change the target to hide a failure.

### 8. Expose the qualified result

Add package/case selection, stage cooling entry, reported total heat removal and useful failure diagnostics. Complete wire/save migrations and round-trip tests while preserving old inputs. Validate the calculator with the same frozen case and run the appropriate repository checks. Do not infer V3 completion from similarly named V4 heat features present elsewhere in the working tree.

### Conditional follow-up after step 6

Only if recorded failures still justify it, investigate phase-specific truncation, broader trace-path pruning or consistent liquid/vapour phase appearance. Reuse the independent reference to distinguish physical/model limits from numerical ones. These are larger model changes; neither a fixed dry-tray switch nor a wholesale linear-solver redesign is a prerequisite established by the evidence so far.

## Recommended reference and completeness

Use Minerva Ledezma-Martinez, *Design of Crude Oil Distillation Systems with Preflash Units*, University of Manchester PhD thesis (2019): the unoptimised **without-preflash** base case. Relevant locations are section 3.3.1, Figure 3.2, Appendix A Tables A1-A6, and the base-case steam entries in Tables 3.3 and 6.1. [Public thesis](https://pure.manchester.ac.uk/ws/portalfiles/portal/146445675/FULL_TEXT.PDF).

This is a substantially fuller process reference than the earlier Sotelo-based side-draw analogue. It is not a completely specified, simulator-independent numerical benchmark: its petroleum properties are generated in HYSYS, and exact flowsheet connections still need visual transcription. The web text was accessible; PDF screenshots did not return visible images and local downloads failed with TLS errors. Accordingly, exact attachment stage numbers are not claimed as verified here.

The related journal reference is Ibrahim, Jobson and Guillen-Gosalbez (2017), *Optimization-Based Design of Crude Oil Distillation Units Using Rigorous Simulation Models*, IECR 56, 6728-6740. Its publisher lists supplementary crude characterisation and initial-design data, but those supplementary tables were not retrieved in this investigation. It is a candidate supplementary source, not evidence that missing values have been verified. [Publisher](https://pubs.acs.org/doi/10.1021/acs.iecr.7b01014).

## Published starting values

| Quantity | Selected thesis base case |
|---|---|
| Crude | Tia Juana Light; 100,000 bbl/day |
| Characterisation | Six light components plus 25 pseudocomponents; Peng-Robinson |
| Column | 41 theoretical stages; uniform 250 kPa |
| Main sections, top downward | 1-9, 10-17, 18-27, 28-36, 37-41 |
| Heated crude inlet | 365 degrees C |
| Reflux ratio | 4.17 |
| Main stripping steam | 1,200 kmol/h |
| PA1 / PA2 / PA3 cooling | 12.84 / 17.89 / 11.20 MW |
| HN / LD / HD net products | 491 / 515 / 165 kmol/h |
| Condenser outlet in stream table | 59 degrees C |

Sources: Appendix A, printed pp. 215-219; section 3.3.1; Table 6.1. PA duties are positive removal magnitudes in the source. Some later tables round them differently. Table A3 is captioned as a preflash system; cross-check its common main-column entries against the explicitly no-preflash Table A6 and base-case summary before freezing the dataset. Do not import its preflash temperature or vapour-feed split.

## Case data and source assumptions

Create a dedicated, versioned reference fixture with source page/table, units, basis, and one of three labels for every field: published, calculated, or reconstruction assumption.

First resolve these remaining details:

- Visually transcribe Figure 3.2: main feed, each side draw, PA draw and return, steam entry, and condenser connections. Section boundaries alone do not establish exact attachment stages.
- Map the source stage count to V3's condenser 0, trays 1..N, and bottom equilibrium node N+1. Establish whether the source count includes the bottom contact stage. Avoid adding an unintended equilibrium stage simply because V3 calls its bottom node a reboiler.
- Obtain or reconstruct the complete petroleum property table: component MW, standard liquid density, critical properties, acentric factor, heat-capacity/enthalpy model, and binary interactions. Preserve six separate light components and all 25 oil cuts in the reference dataset; the selected working dataset combines those 25 oil cuts into 13 as described above.

  Completed as a DWSIM reconstruction: use the exported native records, ideal-gas thermal grid and binary-interaction matrix. The bulk-density constraint is matched, all 25 pseudo boiling points satisfy PR78 fugacity equality, and the feed flash reproduces after native-file reload. The density distribution and heavy critical properties remain estimates. V3 already uses the PR78 alpha form, but its low-omega linear coefficient is 1.54226 versus 1.5422 in this DWSIM build; the measured pure-NBP effect is documented. DWSIM supplies 11 nonzero light-component interaction pairs, so do not carry over the old all-zero matrix.
- Molar feed conversion is complete in the exported dataset. Use those frozen component molar flows; do not treat volume percentages as mole percentages or import the old fixture's total molar flow.
- Verify steam inlet enthalpy. Chen's earlier base-case study gives 4.5 bar and 260 degrees C, but importing that into the selected later case must be identified as an inherited assumption unless confirmed there. Use inlet enthalpy consistently through pressure reduction. [Chen thesis, section 6.1.1](https://pure.manchester.ac.uk/ws/portalfiles/portal/31440025/FULL_TEXT.PDF).
- Verify reflux definition, whether overhead gas is separately withdrawn, water decanting, and whether 59 degrees C is an appropriate fixed condenser boundary for our simplified case.

The existing `V3Cdu17TiaJuanaPackage` has 16 public hydrocarbon entries, including zero-feed methane, a C4 lump and 12 petroleum lumps. Its identifiers do not make it identical to either the 31-component reference or the selected 19-component working basis. Add a distinct package/revision for the reduced basis; retain the old package as a regression case.

**Gate:** every input needed to close the new fixture is populated and its provenance is explicit. Use the completed DWSIM reconstruction as requested; recovering the original HYSYS property table is not a remaining prerequisite.

## Internal heat-exchange formulation

Add a V3 stage-heat input containing an equilibrium-stage number and signed duty in watts. Adopt positive = heat added to the column. The three source PA removal magnitudes therefore become -12.84e6, -17.89e6 and -11.20e6 W, totalling **-41.93 MW**.

For an energy residual written as incoming minus outgoing enthalpy:

`E_j = H_in,j - H_out,j + Q_existing,j + Q_stage,j = 0`.

Initially put each PA duty on its verified return stage. This is an explicit modelling convention, not an exact elimination of the PA loop. A real loop also transports liquid and composition between stages; a prescribed heat sink preserves net heat removal without preserving that circulation. If a PA spans several stages, also test a uniform distribution over its verified contact span with the same total duty. Report the sensitivity instead of tuning the distribution to force agreement.

This feature adds no material stream, flow unknown, recycle equation, or distant Jacobian coupling. A fixed Q has zero derivative with respect to state variables. Audit residual scaling so that adding a duty cannot silently relax convergence tolerances. Include the heat terms in initial energy estimates, continuation, final energy audits and reported utilities.

Likely V3 touchpoints: input and resolver, resolved problem, `V3MeshResidualEvaluator`, initializer, calculator continuation, acceptance auditor, result/provenance, then persistence/network/UI. Preserve empty-heat behaviour and old saved inputs. Validate finite duties, legal stages and unambiguous handling of duplicate stage entries. Show cooling in MW in the editor and total removed heat in results.

## Direct-side-product simplification

Replace the HN, LD and HD stripper products with direct liquid withdrawals from their original main-column draw stages. Use the published **net product** rates: approximately 136.3889, 143.0556 and 45.8333 mol/s respectively. These are not the gross liquid feeds formerly sent through the strippers.

Remove the stripper stages, their vapour returns, their reboiler heat, and their steam. Do not transfer those deleted duties or steam flows into the main column. Keep main-column stripping steam. Set external bottom reboiler duty to zero, with the stage-count and bottom-contact interpretation established in step 1.

Preserve the heated feed boundary; its enthalpy already includes furnace heating. Do not add furnace duty again inside the column. The upstream heat-recovery network can be represented at this boundary for this experiment; network exchanger sizing and pinch targeting are separate work.

Use V3's condenser-temperature/reflux/bottom-duty closure and three prescribed side rates, after checking the source definitions. Predict top and bottom product rates and product qualities. Do not also impose all published product rates, condenser duty, and ASTM specifications: that would overconstrain this closure. Any later quality targeting must replace an independent specification.

V3 currently approximates water as an immiscible stripping medium with a prescribed upward vapour profile and condenser separation. Check that the new cooled stages remain within this model's water-phase assumptions. A need for tray water condensation is a model limitation to resolve or report, not a numerical failure to hide with tolerances.

## Reference comparisons and optional fidelity study

The required reference is a DWSIM column with the same frozen component properties and requested simplifications. Build it first. An optional later fidelity study can compare:

1. The original literature topology, if the required simulator and reconstruction data are available, to establish reproduction error.
2. That topology with side strippers removed and net products drawn directly, retaining actual PA loops.
3. The same column with PA loops replaced by the prescribed internal cooling convention used in V3.

Comparisons 1-to-2 and 2-to-3 quantify the two requested simplifications. Compare V3 primarily against case 3. Full reference side strippers would exist only in the external validation flowsheet, not in the V3 implementation.

DWSIM is installed and its automation has been verified, so simulator availability is resolved. Do not describe comparison with the original stripped products as an exact solver validation. Product compositions and ASTM endpoints will change when strippers are deleted.

## Controlled PA experiment

First verify heat sign and global energy closure on a modest known-converged column. Then solve the dedicated literature fixture using accepted-state continuation. Grow steam, heat removal and draw rates in small physical increments; halve rejected increments and retain the last accepted state. Try a coupled PA/draw ramp if applying all cooling before withdrawal creates an unsuitable branch. The final target must retain the authored feed, pressure, duties and draws.

Implement and qualify the corrections supported by the investigation: refresh truncation support at appropriate intermediate solves; use the agreed MW-weighted mass budget; and avoid reusing a failed intermediate as though it were a converged seed. These remain implementation tasks in current V3. Do not assume these changes, or PA cooling, guarantee the full target will converge.

For a controlled PA experiment, hold composition, feed state, stage map, condenser specification, reflux, bottom duty, steam and target side rates fixed. Compare zero PA cooling with the full prescribed cooling, using the same continuation and solver controls. If the zero-cooling target does not converge, compare the last common accepted draw rung and record each run's reach separately.

Record temperature, liquid and vapour flows, post-withdrawal liquid margin, heat balance, component balance, residual maxima and accepted continuation rungs. Pay particular attention to the trays immediately below the lower side draw. Cooling can condense vapour, but whether it restores that particular liquid flow is a result to measure.

Keep the old F05/F06 fixture as a separate diagnostic pair. Changes between it and the new literature fixture involve many inputs and cannot establish PA causality by themselves.

## Acceptance and delivery

Require a converged final residual under the normal solver criterion and a fresh independent audit. Enforce the user's maximum **0.1% mass error** using molecular weights, with separate reports for hydrocarbon closure, water closure and summed absolute external component discrepancies. Cancellations between component errors must not conceal truncation losses. Retain the energy tolerance separately and include all PA duties.

Require feasible post-draw liquid flow, consistent phase assumptions, and no publication of intermediate surrogate cases. Report original-source discrepancies separately from discrepancies against the independently simplified reference. Set any cross-simulator temperature/composition tolerance after quantifying property reconstruction differences; do not invent a tight agreement claim in advance.

Verification should cover zero-heat regression, one-stage cooling sign and energy accounting, local-versus-independent Jacobian agreement, persistence round trip, and an end-to-end steam/heat/draw case. Then run the relevant V3 regression suite and the fixed PA-on/off comparison. UI integration follows a successful core fixture.

Deliver a source/provenance fixture, a dedicated property package, the V3 heat-duty feature, saved reference inputs, and a results report containing stage profiles and a clear account of both successful and failed targets. Execution order: **case contract -> V3 thermodynamic agreement -> simplified DWSIM column -> minimal V3 numerical corrections -> V3 stage heat -> full target qualification -> controlled comparisons -> UI/persistence**.
