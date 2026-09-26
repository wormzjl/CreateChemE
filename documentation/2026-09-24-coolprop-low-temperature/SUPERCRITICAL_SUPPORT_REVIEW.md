# Supercritical support in the unified engine

Status: Concluded 2026-09-24 (architecture and read-only code assessment; no implementation).

## Recommendation

Include supercritical fluid states in the shared thermodynamic engine from the start. They extend the fluid equation-of-state coverage and phase classification. They do not require adding an independently conserved supercritical species inventory or a new first-order transition with its own latent heat.

For a pure substance, T > Tc and P > Pc is the conventional supercritical region relative to its liquid-vapor critical point, subject to the fluid remaining stable against any competing solid phase. Liquid and vapor become indistinguishable at their critical point. Paths through the stable supercritical region can connect liquid-like and gas-like states continuously without crossing a liquid-vapor coexistence boundary. Response properties can change sharply; the critical point itself can be singular. Heat-capacity maxima or pseudocritical crossover labels do not introduce latent heat or a new equilibrium phase boundary.

A recommended internal representation is a chemically identified fluid phase with a descriptive regime (liquid-like, vapor-like, supercritical or unresolved/general fluid). Regime labels must not select inconsistent energy formulas or add/remove material. Multiple fluid phases remain possible where a mixture stability calculation predicts demixing. Critical merges should not retain two numerically duplicate phases with an arbitrary split.

## Mixtures

Composition-dependent critical loci and phase envelopes replace the simple pure-fluid threshold. Pure-component critical temperatures/pressures, or their weighted averages, are insufficient for deciding mixture stability. A mixture envelope's maximum temperature and pressure need not coincide with its critical point. Some mixtures exhibit multiple critical curves, liquid-liquid separation or retrograde condensation [1,2].

Determine phase count/compositions from a qualified stability and equilibrium calculation. Derive any supercritical presentation label from the qualified mixture envelope and stated convention after stability is established. One cubic EOS root for the feed composition alone does not prove mixture stability against splitting into phases of different composition. The engine may return a stable single fluid without inventing a precise critical classification when critical-locus data are unavailable.

## Current implementation findings

- `science/thermo/PhaseRoot.java` contains LIQUID and VAPOR root-selection hints. Such root choices are useful for evaluating candidate states, but do not constitute a complete physical phase taxonomy.
- `science/fluid/thermo/HydrocarbonModel.java` evaluates the vapor path with TranslatedPengRobinson at the requested pressure, while the liquid path evaluates a reference state and applies GlobalLiquidResponse. Adding a SUPERCRITICAL enum alone cannot demonstrate consistency between those paths.
- `GlobalLiquidResponse.java` uses v(P)=v_ref exp[-k(P-P_ref)] with one configured compressibility and thermodynamically integrated pressure corrections. This liquid approximation is not evidence of accurate near-critical compressibility or heat capacity. Do not extend it into the critical region without qualification; prefer a coherent fluid EOS covering the intended path.
- `TranslatedPengRobinson.java` assigns a vaporBranch flag from an algebraic heuristic. It is not a general mixture stability or critical-locus result.
- Current network property records inspected in the preceding reviews have a 2 MPa pressure ceiling. Nitrogen's approximately 3.396 MPa critical pressure already exceeds that ceiling. Supercritical nitrogen therefore needs explicit pressure-domain and property qualification, not just a display label. Keep other materials' limits individually qualified.

The existing cubic EOS calculations are a useful foundation and cubic equations can represent supercritical states [3]. Their ability to return a root does not establish high-accuracy critical-region properties. CoolProp reference models offer useful independent comparisons within each fluid and mixture model's valid domain; verify density, enthalpy, Cp, compressibility, sound speed and transport properties as relevant.

## Solver and process implications

Use a coherent phase evaluator across vapor-like, dense-fluid and supercritical states. Near criticality liquid/vapor phase compositions and densities approach one another, making two-phase equations ill-conditioned. Use stability checks, appropriate variable scaling, phase merge/disappearance handling and qualified derivatives. Do not silently clamp physical response peaks to make the solver converge. Any finite-width numerical regularization must have an explicit accuracy contract.

In a stable single supercritical fluid there is no liquid-vapor interface and conventional vapor quality is not a physical phase fraction. Represent that in API results. Separator, pump and heat-exchanger models should consume density, compressibility, enthalpy and the actual phase count rather than assuming that a vapor label means dilute gas or a liquid label means constant density. A supercritical fluid can also coexist with precipitated solids, so solid stability checks remain necessary.

The shared engine should support TP, PH and UV queries through the same property model and reference conventions. The device network remains driven by due simulation deadlines, dependency changes and worker completions, with online-tick time and engine-owned presentation.

## Proposed qualification

1. Pure-fluid single-phase paths around (not artificially through) a liquid-vapor critical endpoint: verify continuity of density/energy where physically continuous and no artificial latent heat or branch-switch jump.
2. Independent reference comparisons away from and approaching criticality, with explicit uncertainty and exclusion of an unresolved critical neighborhood if necessary. Test analytic/implemented derivatives in their valid regimes.
3. Approach coexistence/critical merging and cross a mixture phase envelope; verify stable phase count, material and energy conservation, and absence of duplicate phases. Do not use averaged critical constants as the oracle.
4. Extend pressure/temperature domains only with caloric, mechanical and transport evidence; update formats on fresh worlds and preserve required round trips if state representation changes.

Verdict: high architectural feasibility; actual near-critical accuracy, mixture stability robustness and runtime cost need qualification. This assessment does not establish current production supercritical support. No source changes, numerical benchmark, Gradle invocation or dev-client run was performed.

## Sources

[1] NIST critical-locus discussion (including multiple mixture critical curves and liquid-liquid separation): https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=930138
[2] NIST teqp critical curves: https://pages.nist.gov/teqp-docs/en/stable/algorithms/critical_curves.html
[3] CoolProp cubic EOS and mixture critical points: https://coolprop.org/coolprop/Cubics.html
[4] CoolProp mixture stability, PT flash and phase envelopes: https://coolprop.org/fluid_properties/Mixtures.html
[5] CoolProp phase-region definitions: https://coolprop.org/coolprop/HighLevelAPI.html

Sources checked 2026-09-24. Companion unified-engine and phase-transition reviews supply the broader architecture and data limitations. No tools were created or detached.
