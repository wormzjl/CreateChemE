# Wax precipitation: before/after viscosity sensitivity

**Superseded inventory assumptions:** the subsequent [calibrated-rheology study](../calibrated-rheology/README.md) recovered reported whole/residue/heavy-cut wax contents from the original assay tables. The uniform 5/15/30% inventories below are retained as historical sensitivity scenarios, not the best available crude characterization. The new study also calibrates mixture transport against source-cut and aggregate measurements.

Research calculation, 17 September 2026. **No production properties or thermodynamic model have been changed. Everything in this directory is local and Git-ignored.**

The 13 isolated pseudocomponent cuts, six implemented crude compositions, and their six ideal 370°C+ residues were recalculated over 0–500°C. Each case has linear/log plots for both the full interval and a 0–150°C detail. The “before” curve is exactly the previous component-based, PC12/13-unit-repaired liquid baseline; no measured whole-crude or residue viscosity has been fitted.

**The “after” curve describes an idealized dispersion of wax crystals. It is not the apparent viscosity of a wax gel, and does not predict pour point, restart pressure, or a universal non-flowing state.**

## Literature work

The public-literature review was completed in the existing [ChatGPT research conversation](https://chatgpt.com/c/6aab5be3-bdc4-83ec-8a91-246901c46b82). No source code, local file contents, or private workspace access was provided. Primary equations and limitations were independently checked before use. The review confirmed the ideal-equilibrium reduction and dispersed-suspension approach and led to adding the bounded long-chain melting relation below.

| Source | What it supports | What was adopted here |
|---|---|---|
| Lira-Galeana, Firoozabadi & Prausnitz, *Thermodynamics of wax precipitation in petroleum mixtures*, AIChE Journal 42 (1996), 239–248, [DOI](https://doi.org/10.1002/aic.690420120), [authors' public report](https://escholarship.org/uc/item/9cb4w8ss) | Multiple solid phases; fusion-property relations and the danger of treating all petroleum material as normal paraffins. Equations 6 and 8–12 were checked in the report. | Fusion-property estimates and a simplified ideal-liquid/pure-solid equilibrium. This is **not** a reproduction of their EOS model or reported accuracy. |
| Krieger & Dougherty, *A Mechanism for Non-Newtonian Flow in Suspensions of Rigid Spheres* (1959), [DOI](https://doi.org/10.1122/1.548848) | Hydrodynamic suspension-viscosity framework. | The conventional KD concentration relation, not a wax-network constitutive model. Its modern equation and sphere constants were cross-checked in [this primary experimental paper](https://doi.org/10.1039/C5RA21068B); that paper concerns protein dispersions, not validation on wax. |
| Broadhurst, *An Analysis of the Solid Phase Behavior of the Normal Paraffins* (1962), [NIST original](https://nvlpubs.nist.gov/nistpubs/jres/066/3/V66.N03.A05.pdf) | A bounded melting relation for long normal alkanes above C44. | Reference melting estimate for virtual wax above equivalent C44; this does not establish that the residue contains those molecules. |
| Kané et al., *Rheology and structure of waxy crude oils in quiescent and under shearing conditions*, Fuel 83 (2004), 1591–1605, [DOI](https://doi.org/10.1016/j.fuel.2004.01.017) | Cooling/shear history changes the state; crystal aggregation and a network can cause gel behavior at small solid contents. | A warning that a finite dispersed-sphere viscosity is insufficient for quiescent cooling and restart. No gel parameters transferred. |
| Li & Zhang, *A generalized model for predicting non-Newtonian viscosity of waxy crudes as a function of temperature and precipitated wax*, Fuel (2003), [publisher](https://www.sciencedirect.com/science/article/abs/pii/S0016236103000358) | Wax-dependent apparent viscosity requires rheological characterization and shear dependence. | Candidate for future calibrated work; no unverified coefficients copied. |
| Paso et al., *Paraffin Polydispersity Facilitates Mechanical Gelation* (2005), [DOI](https://doi.org/10.1021/ie050325u) | Model formulations gelled with n-paraffin contents as low as 0.5 wt%; distribution and morphology matter. | Evidence against a universal packing-based gel threshold. This formulation-specific observation is not assigned to our crudes. |

The public report PDF is retained only under `sources/` for equation verification and is excluded from the deliverable ZIP. It includes a warning that unmodified normal-paraffin fusion parameters overpredict wax if applied indiscriminately to petroleum fractions.

## Explicit assumptions: a sensitivity calculation, not measured wax contents

Each existing pseudo cut is virtually divided into a crystallizable part and a noncrystallizing carrier. Both virtual parts retain the parent molecular weight and the same hypothetical liquid viscosity. This preserves the original liquid baseline exactly while permitting a separate solid inventory. The explicit seven light chemicals are kept in the carrier.

The crystallizable share is set to **5%, 15%, or 30% of each pseudocomponent**, equally for every crude. These values are analyst-selected test scenarios, not sourced wax assays, confidence bounds, or low/medium/high classifications of the named crudes. The 15% case is the plotting reference only. Because the two virtual parts have equal MW, the share is both a mass and a molar share within that parent cut. It is **not** a 15% whole-crude wax content or an assertion that 15% precipitates at every temperature.

The molecular weight of the crystallizable part is also assumed equal to the parent cut's MW. That assumption is especially speculative for PC09–13. No actual carbon-number distribution, n-paraffin assay, DSC crystallized-wax curve, or wax appearance temperature is available to establish it. Material with a high representative NBP can be aromatic/resin/asphaltenic rather than a long normal alkane. Only the assumed subinventory is allowed to crystallize; the entire heavy cut is never declared wax.

## Fusion properties and temperature sensitivity

For equivalent carbon number at most 44, the reference scenario uses the Won paraffinic relations, as reproduced in the authors' report:

```
Tf [K] = 374.5 + 0.02617 M - 20172/M
Hf [J/mol] = 4.184 × 0.1426 M Tf
```

M is g/mol. For equivalent carbon number `n = (M - 2.016)/14.027 > 44`, the reference uses Broadhurst's bounded melting relation:

```
Tf [K] = 414.3 (n - 1.5)/(n + 5)
```

The original Won entropy approximation is then evaluated using that Tf to obtain the estimated Hf. This combination is a research modeling choice, not a published validated combined model; fusion enthalpy remains especially uncertain. Continuous equivalent carbon number is a surrogate interpolation, not an assertion that a molecule has a fractional carbon atom. These relations describe hypothetical paraffinic material, not measured cut melting points. The virtual-wax assignment remains speculative for PC11–13 even with a bounded Tf.

Three **alternative assumptions**, not error bounds, are included: the unbounded Won relation at all MW, the mixed-fraction variant below, and the reference melting description with a heat-capacity correction.

```
Lira-Galeana mixed-fraction variant:
Tf [K] = 333.46 - 419.01 exp(-0.008546 M)
Hf [J/mol] = 4.184 × 0.05276 M Tf

Reference + heat-capacity sensitivity:
Cp_liquid - Cp_solid [J/(mol K)] = 4.184 M (0.3033 - 4.635e-4 T)
```

The mixed-fraction melting relation was based on lighter normal-paraffinic/naphthenic/aromatic data; its limiting value is not evidence that every residue melts near 60°C. `fusion_model_sensitivity.png` shows how strongly all four thermal descriptions affect the precipitation window. No production Cp correlation is changed: the extra heat-capacity expression is used only in this research solid-equilibrium sensitivity branch.

## Equilibrium and material balance

Reference assumptions: ideal liquid activities, independent pure solid phases, no heat-capacity correction, no vapor phase, and negligible pressure effect on solid–liquid equilibrium. At T < Tf:

```
K_i(T) = exp[-Hf_i/R × (1/T - 1/Tf_i)]
```

K is the saturation mole fraction in the **remaining liquid**, not the fraction precipitated. At/above Tf the solid is disabled (K = 1 for this inventory-limited calculation). The nonwax inventory remains liquid.

For Nw_i initial wax moles and Nn total nonwax moles, solve the scalar material balance:

```
L = Nn + Σ min(Nw_i, K_i L)
dissolved_wax_i = min(Nw_i, K_i L)
solid_i = Nw_i - dissolved_wax_i
```

This includes solvent dilution and conserves every parent's amount. It is solved by bisection, with phase-amount bounds and saturation/complementarity checks. The method assumes a nonzero nonwax carrier. It does not address solid solutions or metastable nucleation.

For the heat-capacity sensitivity, with Cp_l - Cp_s = a + bT, the additional term in ln K is:

```
(a/R)[ln(T/Tf) + Tf/T - 1]
+ (b/R)[T/2 - Tf + Tf²/(2T)]
```

All temperatures in these equations are kelvin. The Cp correction was analytically integrated with the liquid-minus-solid sign convention. A virtual crystallization onset is a consequence of the scenario, not an assigned measured WAT.

## Reconstructing effective viscosity

Solid volume is estimated from its mass using an assumed 900 kg/m³ wax density. Remaining liquid volume uses the catalog's standard-liquid component densities without a thermal-expansion correction. This is an explicit volume-conversion approximation, not a density(T) prediction. A density sensitivity using 850–950 kg/m³ for solid wax and ±5% liquid density is exported.

The liquid viscosity is recomputed from the **remaining liquid** parent mole fractions using the same log-molar rule as before. Then:

```
phi = Vsolid / (Vsolid + Vliquid)
mu_dispersion = mu_remaining_liquid × (1 - phi/0.64)^(-2.5 × 0.64)
```

The 2.5 intrinsic viscosity and 0.64 packing fraction are idealized sphere choices, not fitted wax-crystal morphology parameters. There is no specified shear rate or fitted yield stress. The result represents a nonaggregating dispersed-particle reference. At/above the packing limit the function returns unavailable rather than imposing a finite viscosity cap; the current scenarios stay below that limit.

For isolated cuts, the remaining virtual liquid retains the parent baseline, so the added factor is at least one. For crude and residue mixtures, wax equilibrium is solved on the **combined composition**, then liquid viscosity is recomputed. Blending isolated slurry viscosities would miss dissolution in the light-end solvent and is not used.

## Interpreting the result

At 20°C, the reference scenario raises isolated PC08–13 dispersed viscosities by approximately 1.26–1.66 times. It leaves PC01–07 unchanged at that temperature. See `summary.md` for exact results. These ratios quantify the selected dispersion model, not the observed cold-flow increase of actual oil.

Some whole-crude curves decrease relative to the hypothetical all-liquid baseline. For example, the Cold Lake Blend scenario gives a ratio about 0.89 at 20°C: removal of part of the extremely viscous PC11 contribution lowers the remaining-liquid estimate more than the modest sphere-crowding correction raises it. This is a diagnostic consequence of the uncertain liquid/subfraction model, **not a prediction that cooling through wax formation makes real Cold Lake Blend easier to pump**. No enforced upward multiplier was used to hide that result.

In particular, adding wax does not repair PC11's extreme original liquid curve or PC12/13's unsuitable fallback correlation. The small absolute viscosity of PC12/13 after precipitation is still not credible as a calibrated residue property.

Gelation can occur far below dense-particle packing, including in the low-content experimental formulation noted above. No universal solid-fraction threshold is assigned. A shear-dependent gel calculation needs wax morphology, cooling/shear history, and rheometry. The apparent viscosity after quiescent gelation and the yield stress remain **unknown**, not zero.

The ChatGPT review also recommended varying wax-distribution width and comparing solid-solution models. Those are further sensitivities, not established here: each parent still has only one virtual wax species, and no crystal solid solution is modeled. The mean-MW mapping and the chosen discretization are substantial remaining limitations. The log-molar liquid mixing rule is retained specifically to compare with the existing simulator baseline; the review's example mass-weighted closure was not substituted silently.

The 0–500°C plots retain the previous liquid-baseline extensions. Above all scenario melting temperatures the new curves recover that baseline exactly. The full range remains a hypothetical condensed-phase diagnostic, not atmospheric heating with vaporization or thermal cracking.

## Verification and files

`calculate_wax.py` checks the saved baseline and catalog hashes, exact material balance, phase bounds, equilibrium saturation/complementarity, zero-wax recovery, high-temperature recovery, finite positive dispersion results, the analytical binary-solubility solution, solvent dilution, the Einstein dilute limit, and refusal of finite values at the KD packing limit. These are mathematical checks, not experimental validation.

- `cuts_before_after_linear.png` / `cuts_before_after_log.png`: all 13 cuts, 0–150°C.
- `heavy_cuts_relative_change.png`: relative viscosity and solids for PC08–13.
- `fusion_model_sensitivity.png`: competing thermal assumptions.
- `<case>_before_after.png`: linear/log before/after at 0–150°C and 0–500°C for each of the 25 cases.
- `crude_before_after.png` / `residue_before_after.png`: mixture overview with the remaining-liquid curve.
- Every PNG has an editable SVG counterpart.
- `wax_results.json`: 2,001 temperatures, all scenarios, liquid and solid allocations for the reference case, viscosity curves and verification flags.

Run `calculate_wax.py` with the existing NumPy/Matplotlib Python runtime. It reads the prior `component-reconstruction/reconstruction.json` and current catalog for molecular weights/densities. Research output alone is modified. Run `package_results.py` afterward to rebuild the local gallery and archive.
