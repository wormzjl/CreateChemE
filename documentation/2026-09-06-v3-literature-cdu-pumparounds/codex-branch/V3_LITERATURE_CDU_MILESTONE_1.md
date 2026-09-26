# V3 literature CDU milestone 1: case contract and reduced thermodynamics

Date: 2026-09-06. Branch: `codex/v3-literature-cdu`, based on main `6c7d446645792226194df56e58873dceae58bc69`.

The 19-hydrocarbon package is registered as `createcheme:tjl19_dwsim`, revision `tjl19-dwsim-10.2.3-r1`. The shared [case contract](../../../src/test/resources/science/column/v3/tjl19-literature-cdu-v1.json) freezes the source connections, numerical inputs, units and provenance. It is consumed by the V3 contract regression and is the input contract for the next DWSIM builder milestone. No column solve or heat-duty implementation is claimed here.

## Frozen topology and specification set

The [captured Figure 3.2](evidence/tjl-thesis-figure3-2.png) is now readable. It was inspected independently by both execution and review agents. The [2019 thesis](https://pure.manchester.ac.uk/ws/portalfiles/portal/146445675/FULL_TEXT.PDF), section 3.3.1 and Figure 3.2 (printed pp. 103–104), gives the following connections:

| Connection | Source stage | V3 interpretation |
|---|---:|---|
| Heated main feed | 37 | Node 37 |
| Main stripping steam | 41 | Bottom equilibrium node 41 |
| HN / LD / HD liquid feeds to source strippers | 10 / 18 / 28 | Direct net liquid products at 10 / 18 / 28 |
| Deleted stripper vapor returns | 9 / 17 / 27 | Removed; never use these as liquid draw stages |
| PA draws | 10 / 18 / 28 | No material circulation in this simplification |
| PA returns | 8 / 16 / 26 | Prescribed cooling at 8 / 16 / 26 |

The source has 41 main-column contacts, numbered separately from the condenser. The reconstruction maps contacts 1–40 to V3 trays and contact 41 to its bottom equilibrium node; the separate condenser is node 0. This preserves 41 main contacts. Calling that bottom node a reboiler in existing V3 code does not add heat or an extra contact. The mapping into V3 is an explicit interpretation, not a claim that the source uses V3 terminology. Figure 3.2 includes preflash equipment; its main-column connections are retained while preflash equipment and streams are removed, as specified in the case study scope.

Three net products are 491 / 515 / 165 kmol/h, converted to 136.3888889 / 143.0555556 / 45.8333333 mol/s. The rates represent hydrocarbons in the V3 comparison; this interpretation is labelled as a reconstruction assumption. A native wet liquid stream must not silently count its water against that hydrocarbon rate.

Cooling is -12.84 / -17.89 / -11.20 MW at nodes 8 / 16 / 26, respectively, with positive duty defined as heat added. Total cooling is -41.93 MW. Concentrating each duty at the verified PA return is an explicit model assumption; it preserves heat removal while omitting PA material transport.

Fixed feed temperature, pressure, composition and flow, stage connections, steam, side rates and internal cooling accompany three boundary controls: condenser 332.15 K, organic reflux ratio 4.17 and zero external bottom heat. Organic reflux ratio is liquid organic reflux divided by net organic liquid distillate, excluding water and offgas. The 59 C condenser stream is adopted as a boundary temperature; the source reflux basis is not independently established. Both choices are labelled assumptions. Top/bottom hydrocarbon rates, offgas, condenser duty and product qualities are outputs. The V3 ledger verifies the boundary controls close the structural system without also fixing those outputs.

Steam supply is 1,200 kmol/h. Inheriting 260 C and 4.5 bar from Chen 2008 is explicitly an assumption, with enthalpy preserved through pressure reduction to the column. V3 treats water separately; a DWSIM reference uses 19 hydrocarbons plus water. Reconcile the water enthalpy datum and quantify any internal liquid water before claiming wet-column agreement. A native partial-condenser vapor-flow input may require an outer scalar adjustment to implement the same three boundary controls; it is not an additional source product constraint.

## Property representation and independent checks

The original reduced characterization and 31-component reference remain unchanged. Compiled constants come from the reduced `components.json`, the complete interaction matrix and the native ideal-gas thermal grid. The six real components remain separate. All 13 merged pseudocomponents preserve their exported critical properties, fitted acentric factors, molecular weights and standard density metadata. Feed molar flows total 737.6996333000835 mol/s; mass flow is 159.65286 kg/s. The package keeps the empirical heavy-property and assumed 60 F density-basis advisories.

A cubic Cp fit was insufficient for the selected 0.05% held-out Cp budget: its maximum additional-temperature error was 0.51055%. The new package uses a degree-five polynomial in T - 298.15 K, with analytically integrated enthalpy and H(298.15 K) = 0. Fitting uses only the original 25 temperatures per component (300–900 K). An additional native export supplies 15 temperatures per component, including the reference datum, the feed temperature and between-grid points. The old package continues through its original cubic constructor and arithmetic path.

| Quantity | Measured maximum / result | Regression budget |
|---|---:|---:|
| Held-out ideal Cp, 285 states | 0.0320674% relative | 0.05% |
| Held-out ideal H | 24.6018 J/mol absolute | max(0.2 J/mol, 0.02% of native H) |
| Analytic dH/dT versus Cp | Passed centered-difference checks | 1e-8 relative |
| Log fugacity, 8 fixed phase states / 152 component values | 1.33683e-4 absolute | 2e-4 |
| PR departure versus native-fugacity thermodynamic identity | 0.363381 J/mol | 5 J/mol |
| Total phase H versus native ideal H plus consistent departure | 4.21576 J/mol | 40 J/mol |
| Feed vapor fraction | V3 0.799469845038942; native 0.799470134725503 | 2e-5 absolute |
| Feed phase compositions | Passed | 2e-5 absolute per component |
| V3 feed component closure | Passed | 1e-9 mole fraction |
| Feed mass | 159.65286 kg/s | 1e-9 kg/s |

The existing V3 PR78 low-omega coefficient is 1.54226 while this DWSIM build uses 1.5422 for fugacity. That small model difference remains explicit; the old EOS was not altered. All 11 nonzero light-component pairs (22 symmetric entries) are present and defensively copied. Phase checks use native feed liquid/vapor compositions at 332.15, 500, 638.15 and 750 K, at 250 kPa. For a fixed composition, a selected liquid/vapor cubic root is a property test state; it is not a separate assertion of global mixture stability at every test temperature.

## Native enthalpy discrepancy: measured, not hidden

Direct native feed enthalpy is 138424.758822898 J/mol; V3 gives 137572.300071837 J/mol, a difference of -852.458751060 J/mol (-0.615828%). This is much larger than the ideal-gas fit error. The generic XML `EnthalpyEntropyCpCvCalculationMode=LeeKesler` is not the cause: the PR78 package's enthalpy override uses its EOS routine and does not consult that generic selector.

The official [PR78 property-package source](https://raw.githubusercontent.com/DanWBR/dwsim/windows/DWSIM.Thermodynamics/PropertyPackages/PengRobinson78.vb) dispatches to `H_PR_MIX`. Its [model source](https://raw.githubusercontent.com/DanWBR/dwsim/windows/DWSIM.Thermodynamics/PropertyPackages/Models/PengRobinson78.vb), `H_PR_MIX_CPU`, forms attraction alpha with the original quadratic omega expression for every component, while the derivative coefficient uses the PR78 high-omega branch. This differs from the consistent PR78 fugacity attraction function. The source inspection explains a hypothesis; installed-binary measurements independently demonstrate the discrepancy.

For fixed composition and pressure, the identity is:

`H_departure = -R T^2 sum_i(x_i * d ln(phi_i) / dT)`.

The exporter evaluates native fugacity at T +/- 0.001 K and repeats at +/- 0.002 K. The largest step-size difference is 2.41e-5 J/mol. It uses the shared V3 gas constant 8.31446261815324 J/(mol K); native direct enthalpy uses 8.314, a separately small difference. V3 agrees with this independently differentiated native fugacity to the budgets above. Native direct departure misses that identity by up to 8102.610774 J/mol for the heavy liquid composition at 332.15 K, and by 4239.349534 J/mol at feed temperature.

The installed `PengRobinson1978AdvancedPropertyPackage` was also tested with identical compounds and verified identical binary interactions. Its eight fugacity and direct-enthalpy states exactly match the standard package, so its defaults do not resolve the inconsistency. [Investigation measurements](tjl19-native-caloric-investigation.json) record this result. No production V3 EOS change or empirical enthalpy offset has been introduced.

**Remaining reference gate:** a DWSIM column benchmark must explicitly use and replay a thermodynamically consistent caloric treatment before it can qualify V3. A saved native case alone would otherwise reload the inconsistent default. The raw original native case and feed flash stay preserved as characterization evidence. These tests establish thermodynamic-identity agreement; they do not claim direct default-DWSIM enthalpy parity or a qualified wet column.

## Reproduction and deliverables

- `scripts/dwsim/generate-v3-tjl19.py` regenerates the Java package and [thermal fit coefficients](tjl19-thermal-fit.json) from the frozen data; Python with NumPy is required (used NumPy 2.3.5).
- `scripts/dwsim/export-tjl-parity.ps1` compiles the C# helper against the installed DWSIM and writes the held-out native fixture. It only loads the original case; it never saves over or recharacterizes it. `-Advanced -Output build/tjl19-advanced-parity.json` repeats the Advanced comparison.
- `V3Tjl19DwsimPackageTest` checks thermal derivatives, native fugacity, consistent PR78 departure, feed VLE/mass and package isolation. Native direct-H mismatch remains an explicit diagnostic.
- `V3LiteratureCduCaseTest` consumes the shared JSON and checks contact-count mapping, actual draw/return distinction, signed heat sum, exact feed basis and structural operating closure. The test does not construct an incomplete uncooled column and report it as the target case.
- Focused package/case regressions pass. The full suite passed in 3m3s: 360 tests, 0 failures, 0 errors, 0 skipped. Both original characterization manifests still match (26 hashed files across the two datasets).

No continuation, truncation, heat residual, persistence or UI changes are part of this milestone.
