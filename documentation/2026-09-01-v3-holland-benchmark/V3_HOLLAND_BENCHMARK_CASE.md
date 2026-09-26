# V3 full literature benchmark: Holland Example 3-2 (complex column, one liquid sidestream)

**Written:** 2026-09-01; **implemented:** 2026-09-01
**Purpose:** a benchmark case that is *fully inside* V3's dry contract (condenser + partial reboiler + liquid side draw; no steam, no side strippers, no pumparounds) with a complete published solution, so both **convergence** and **accuracy** can be checked number-for-number. This complements `V3_SIDE_DRAW_LITERATURE_CASE.md` (Sotelo 2019), which is a geometry/flow-fraction analog with no accuracy comparison possible.
**Local source excerpts:** `documentation/archives/holland-example-3-2-ocr-excerpts.txt` (OCR from the archive.org full text; verify digits against page scans before use)

**Measured verdict:** the V3 residual/solver/audit path agrees with an independently implemented dense-Newton MESH oracle to numerical precision, but the printed source tables are internally inconsistent and do **not** pass the proposed strict published-number gate. The benchmark records this as `strictPublishedAccuracyAccepted=false`; it does not turn contradictory source values into a solver failure or weaken the proposed tolerances.

---

## 1. Why this case

C. D. Holland, *Fundamentals of Multicomponent Distillation*, McGraw-Hill (1981), **Example 3-2** (statement in Table 3-1, convergence history in Table 3-2, full solution in Table 3-3, pp. ≈98–100; full text on [archive.org](http://archive.org/stream/FundamentalsOfMulticomponentDistillation/FundamentalsOfMulticomponentDistillation-Holland_djvu.txt)).

It is the canonical algorithm-validation column for exactly V3's configuration:

- **11 plates + partial condenser + partial reboiler**, uniform 300 psia, no steam, no strippers, no pumparounds.
- **One liquid sidestream, rate-specified** (25.0 lb·mol/h from plate 10) — precisely V3's `V3SideDrawSpec` semantics.
- 11 all-hydrocarbon components including two heavy lumps — inside `V3ComponentBasis.MAX_COMPONENTS`.
- **Self-contained thermodynamics**: ideal-solution K(T) and enthalpy curve fits in the book's own Tables B-1/B-2 (K data from S. T. Hadden, "Vapor–Liquid Equilibria in Hydrocarbon Systems," *Chem. Eng. Prog.* 44:37, 1948). No EOS, no interaction parameters — a benchmark `V3ThermoModel` can encode the source's thermo *exactly*, so any output disagreement indicts the solver, not the property package.
- **Complete published solution**: stage temperatures, total liquid/vapor profiles, per-component product flows down to 1e-9 lb·mol/h, and both duties. Converged to a tight criterion in 12 theta-method trials (2.81 s on an Amdahl 470V/6 — a fun historical yardstick).

## 2. Case statement (original units)

Feed, 100.0 lb·mol/h total, enters plate 6 (Holland numbering: 1 = condenser, 2–12 = plates, 13 = reboiler) as bubble-point liquid at column pressure:

| Component | FXᵢ (lb·mol/h) | Component | FXᵢ (lb·mol/h) |
| --- | ---: | --- | ---: |
| CH₄ | 2.0 | n-C₅H₁₂ | 15.2 |
| C₂H₆ | 10.0 | n-C₆H₁₄ | 11.3 |
| C₃H₆ | 6.0 | n-C₇H₁₆ | 9.0 |
| C₃H₈ | 12.5 | n-C₈H₁₈ | 8.5 |
| i-C₄H₁₀ | 3.5 | "400" (heavy lump, MW 400) | 7.0 |
| n-C₄H₁₀ | 15.0 | **Total** | **100.0** |

Specifications as authored by Holland: P = 300 psia everywhere (zero tray pressure drop); vapor distillate D = 32.298 lb·mol/h from the partial condenser; **liquid sidestream W₁ = 25.0 lb·mol/h from plate 10**; reflux ratio L₁/D = 2.25.

Published solution highlights (Table 3-3): condenser T₁ = 114.13 °F, reflux L₁ = 72.671, plate-10 liquid L₁₀ = 196.97 (withdrawal fraction w = 25/196.97 ≈ 0.1269 — comfortably feasible), bottoms B = 42.702 lb·mol/h, **Q_C = 0.47243×10⁶ Btu/h, Q_R = 1.5519×10⁶ Btu/h**, full T/L/V profiles and dᵢ/w₁ᵢ/bᵢ splits (see §5).

## 3. Thermodynamic data (Tables B-1/B-2)

- **K-values** (P = 300 psia base): `(Kᵢ/T)^(1/3) = a₁ᵢ + a₂ᵢT + a₃ᵢT² + a₄ᵢT³`, T in °R. Off-base pressures use the book's own rule `Kᵢ(P) = (300/P)·Kᵢ(300 psia)` — irrelevant here (uniform 300 psia) but needed by the model interface.
- **Enthalpies** (Btu/lb·mol, ideal solution): `hᵢ^(1/2) = c₁ᵢ + c₂ᵢT + c₃ᵢT²` (liquid), `Hᵢ^(1/2) = e₁ᵢ + e₂ᵢT + e₃ᵢT²` (vapor), T in °R. Mixture enthalpy = mole-fraction-weighted sum.
- Coefficient tables cover every component in the example (through n-C₈H₁₈, "400", and "500"). OCR of all coefficients is in the archived excerpt file; **digits must be verified against the page scans** before implementation (see §6 risk R1).

## 4. Mapping onto the V3 contract

**Node mapping** (V3: node 0 = condenser, trays 1..N, node N+1 = reboiler):

| Holland | V3 |
| --- | --- |
| Stage 1 (partial condenser) | node 0, TWO_PHASE branch |
| Plates 2–12 | trays 1–11 (`stageCount = 11`) |
| Feed plate 6 | `feedStageNumber = 4` |
| Sidestream plate 10 | `V3SideDrawSpec(trayNumber = 9, …)` |
| Stage 13 (reboiler) | node 12 |

The feed mapping differs from the research draft. Holland's Fig. 3-4 indexes the liquid and vapor **streams** around a stage; applying `f = 6` to V3's node balances places the feed on V3 tray 4. This is also the only mapping that closes the published total-flow profile. Holland's reported `L10 = 196.97` is liquid continuing below the withdrawal, while V3 stores liquid before withdrawal, so the comparable V3 tray-9 state target is `196.97 + 25 = 221.97 lb·mol/h`.

**Input in SI** (1 lb·mol/h = 0.1259979 mol/s; T(K) = (T°F−32)/1.8 + 273.15; 1 Btu/h = 0.29307107 W; 300 psia = 2,068,427 Pa):

- Feed component rates: the FXᵢ above × 0.1259979 (total 12.59979 mol/s); feed temperature = the computed bubble point of the feed at 300 psia from the B-1 fits (Holland states bubble-point feed; the fixture computes it).
- `topPressurePascal = 2.068427e6`, `stagePressureDropPascal = 0`.
- Side draw: tray 9 at 3.149948 mol/s.
- Specifications — this is the key mapping decision. Holland specifies {D, L₁/D}; V3 specifies {condenser T, reflux ratio, reboiler duty}. Take the two anchors from Holland's *solution*: `CondenserOutletTemperature = 318.778 K` (114.13 °F) and `ReboilerDuty = 454,847 W` (1.5519×10⁶ Btu/h). Then D, B, W₁ leaving compositions, profiles, reflux, and Q_C all become **predictions**, and reproducing Holland's numbers closes the loop.
- **Partial-condenser emulation:** Holland's condenser refluxes *all* condensate (no liquid distillate; D is pure vapor). V3's two-phase condenser always splits condensate into reflux + liquid distillate via `R/(1+R)`. Emulate with a large reflux ratio: `OrganicRefluxRatio = 1e4` gives a spurious liquid-distillate leak of condensate/(1+R) ≈ 7.3×10⁻³ lb·mol/h — 0.007% of feed, an order below every comparison tolerance. Run R ∈ {1e3, 1e4, 1e5} once as a sensitivity check; results must be insensitive, which also demonstrates the conditioning is safe. Compare V3's *overhead vapor* stream against Holland's D, and verify the emergent ratio reflux/overhead-vapor = 2.250.

## 5. Published targets (accuracy comparison)

Temperatures (Holland → SI): condenser 114.13 °F/318.78 K; plates 145.45/336.18, 165.40/347.26, 183.47/357.30, 217.13/376.00, 239.30/388.32, 253.55/396.23, 264.68/402.42, 276.18/408.81, 292.27/417.74, 319.29/432.76, 363.18/457.14 K; reboiler 446.41 °F/503.38 K. The page scan confirms that Table 3-3 prints `456.41`, while Table 3-2 prints `446.41`; the B-1 bubble equation and independent re-solve support `446.41`.

Total flows (lb·mol/h): V₁=D=32.298; L₁=72.671; V₂..V₁₃ = 104.97, 103.60, 100.38, 91.291, 127.25, 144.65, 154.75, 159.66, 159.67, 154.27, 143.39, 122.01; L₂..L₁₂ = 61.302, 68.080, 58.993, 194.95, 212.35, 222.45, 227.36, 227.38, 196.97, 186.10, 164.71; B = 42.702. The scan confirms `L2 = 61.302`, but adjacent total balances require `71.302`; the fixture preserves both the printed and reconciled values. For V3 comparison, the tray-9 pre-withdrawal liquid target is 221.97 as explained above.

Product component flows (lb·mol/h) — distillate dᵢ / sidestream w₁ᵢ / bottoms bᵢ:

| Component | dᵢ | w₁ᵢ | bᵢ |
| --- | ---: | ---: | ---: |
| CH₄ | 2.0000 | 3.2125e-7 | 3.0273e-10 |
| C₂H₆ | 9.9985 | 1.5077e-3 | 1.9926e-5 |
| C₃H₆ | 5.9356 | 6.1044e-2 | 3.3848e-3 |
| C₃H₈ | 12.224 | 0.24742 | 1.8230e-2 |
| i-C₄H₁₀ | 1.1119 | 1.9577 | 0.43043 |
| n-C₄H₁₀ | 1.0242 | 10.428 | 3.5483 |
| n-C₅H₁₂ | 3.6429e-3 | 6.4835 | 8.7129 |
| n-C₆H₁₄ | 1.8929e-5 | 2.5252 | 8.7747 |
| n-C₇H₁₆ | 1.5019e-7 | 1.3819 | 7.6181 |
| n-C₈H₁₈ | 1.2374e-9 | 1.0969 | 7.4031 |
| 400 | 1.4162e-12 | 0.80727 | 6.1927 |

Duties: Q_C = 0.47243×10⁶ Btu/h (138.46 kW, internal check — V3 does not publish condenser duty), Q_R = 1.5519×10⁶ Btu/h (454.85 kW, spec input).

**Acceptance tolerances** (Holland's own convergence left D within ±0.001 lb·mol/h; his tables carry 5 significant figures):

| Quantity | Tolerance |
| --- | --- |
| Stage temperatures | \|ΔT\| ≤ 0.3 K per node (report the max) |
| Total V_j, L_j, and D/W₁/B | relative ≤ 0.5% |
| Product component flows ≥ 0.01 lb·mol/h | relative ≤ 1% |
| Trace product flows < 0.01 lb·mol/h | log₁₀ agreement within ±0.2 |
| Emergent reflux ratio (reflux / overhead vapor) | 2.250 ± 0.002 |
| Q_C (internal) | ≤ 1% |
| Convergence | V3's own gates unchanged (1e-8 scaled residual, full audit incl. `SIDE_DRAW_SPLIT`); record Newton iterations and wall time |

## 6. Implemented work packages

- **W1 — complete.** The page-scan transcription and both raw/reconciled targets are stored in `src/test/resources/column/v3/holland-example-3-2.json`. Printed pages 95, 99, 100, 596, and 597 were rendered and checked. The fixture records every source correction explicitly.
- **W2 — complete.** `IndependentHollandMeshOracle` owns a separate log-flow coordinate map, MESH residual, central finite-difference Jacobian, dense partial-pivot solve, and Armijo line search. It converges in three Newton iterations at each tested condenser split ratio.
- **W3 — complete.** `HollandB12Thermo` implements the B-1 K fits, pressure scaling, B-2 phase enthalpies in SI, bounded ideal fugacity calls, bubble point, and Rachford-Rice TP flash. The Table B-2 liquid quadratic scale is interpreted as 10^-5: the printed 10^-4 heading is inconsistent with the printed duty by millions of Btu/h, while 10^-5 reproduces Q_C within 0.17%.
- **W4 — complete.** `HollandExample32BenchmarkTest` exercises resolver, initializer, simultaneous solver, and independent audit for R = 10^3, 10^4, and 10^5. It also solves from a deliberately perturbed independent state so V3 must perform a fresh Newton correction.
- **W5 — complete.** `./gradlew v3HollandBenchmark` writes `build/reports/benchmarks/v3-holland-example-3-2.json`. The ordinary test suite also runs the benchmark regression.
- **W6 — complete.** The existing in-game V3 calculator can load a server-owned, fixed Holland 3-2 preset. It runs asynchronously through the shared bounded solve service, executes the independent oracle and perturbed V3 correction, publishes four accepted stream cards, and identifies the seven source-table conflicts as advisory evidence. The GUI locks the benchmark fields so rounded display values cannot alter the scan-verified input; the same button restores the editable Tia Juana production draft.

### 6.1 Measured results

At R = 10^4:

| Check | Result |
| --- | ---: |
| Independent oracle | 3 iterations, max scaled residual 8.88e-15 |
| V3 from a perturbed independent state | 2 iterations, accepted audit |
| V3 vs independent temperature difference | 1.71e-13 K maximum |
| V3 vs independent flow difference | 1.94e-14 relative maximum |
| Published temperature comparison | 0.118 K maximum — passes |
| Published condenser duty comparison | 473,211 vs 472,430 Btu/h; 0.165% — passes |
| Published liquid totals | 1.98% maximum — fails 0.5% |
| Published vapor totals | 2.63% maximum — fails 0.5% |
| Published major product components | 5.18% maximum — fails 1% |
| Emergent reflux / overhead vapor | 2.2604 vs 2.250 — fails ±0.002 |

The strict failures are liquid node 11; vapor nodes 11 and 12; overhead i-C4H10 and n-C4H10; side-draw C3H8; and the emergent reflux ratio. Trace products pass the ±0.2 log10 gate. These misses occur identically in the independent and V3 implementations, while the two implementations agree essentially bit-for-bit; they are source-data/specification inconsistencies, not evidence of a V3 residual or Newton error.

The original V3 cold initializer does not reach the Holland root under the 64-iteration benchmark allowance (`LINEAR_SINGULAR` at R=10^3; `MAX_ITERATIONS` at R=10^4 and 10^5). The independent near-root seed plus a deterministic perturbation converges in two V3 Newton iterations. This benchmark therefore validates the equations, Newton correction, and audit at the literature root, while separately exposing a cold-start limitation.

The condenser emulation is bounded. Relative to R=10^4, R=10^3 changes temperature by at most 0.0566 K and any product component by at most 0.447%; R=10^5 changes them by 0.00566 K and 0.0447%. This is below the published comparison tolerances, though not literally zero.

## 7. Cases surveyed and not selected

- **Sotelo et al. 2019** (already tracked in `V3_SIDE_DRAW_LITERATURE_CASE.md`): real CDU with three steam strippers and four pumparounds — geometry/fraction analog only; no accuracy comparison possible. Remains the *stress* companion; Holland 3-2 is the *accuracy* anchor.
- **Holland Example 4-6**: the same column re-specified with ratio specs (B/V_N = 0.31, W₁/L_p = 0.12692, sidestream moved to plate 11) — those specification types are not expressible in V3's control set; footnote only.
- **Holland's pipe-still example (after Cecchetti et al. 1963)**: crude column with four sidestreams — but all fed to steam side strippers; the natural next benchmark once strippers/steam exist (VDU roadmap), not now.
- **Wang–Henke (1966), Naphtali–Sandholm (1971), Tomich (1970) originals**: paywalled; example statements not fully recoverable from open sources, and their data conventions are less completely documented than Holland's Appendix B.
- **Luyben-style Aspen BTX sidestream columns**: fully specified in his books, but the thermodynamics are Aspen property systems (Chao–Seader/NRTL); accuracy comparison would be confounded unless those property systems were reimplemented — exactly the confound Holland's self-contained fits avoid.
- **DWSIM cross-replication of the dry TJL CDU-17 case**: still worthwhile as a *production-package* (PR) cross-simulator check — but it is simulator-vs-simulator, not literature-vs-solver, and belongs to a separate decision. Holland 3-2 isolates the MESH solver with exact shared thermo, which is the cleanest possible accuracy claim.

## 8. Citations

- C. D. Holland, *Fundamentals of Multicomponent Distillation*, McGraw-Hill, 1981. Example 3-2: Tables 3-1 (statement), 3-2 (convergence), 3-3 (solution), Chapter 3; K/enthalpy data: Appendix Tables B-1/B-2. Full text: [archive.org scan](http://archive.org/stream/FundamentalsOfMulticomponentDistillation/FundamentalsOfMulticomponentDistillation-Holland_djvu.txt); overview: [Google Books](https://books.google.com/books/about/Fundamentals_of_Multicomponent_Distillat.html?id=J8xTAAAAMAAJ).
- S. T. Hadden, "Vapor–Liquid Equilibria in Hydrocarbon Systems," *Chem. Eng. Prog.* 44:37 (1948) — source of the Table B-1 K-value fits.
- L. M. Naphtali and D. P. Sandholm, "Multicomponent Separation Calculations by Linearization," *AIChE J.* 17(1):148 (1971) — method context; [example usage in later work](https://www.sciencedirect.com/science/article/abs/pii/0098135479800903).
- S. Wang and G. Henke, "Tridiagonal Matrix for Distillation," *Hydrocarbon Processing* 45(8):155 (1966) — method context ([overview](https://www.brewiki.org/extractive-distillation/equationtearing-procedures-using-the-tridiagonalmatrix-algorithm.html)).
- D. Sotelo et al., *Int. J. Simul. Model.* 18(2):229–241 (2019), [doi:10.2507/IJSIMM18(2)465](https://doi.org/10.2507/IJSIMM18(2)465) — the tracked analog case.
