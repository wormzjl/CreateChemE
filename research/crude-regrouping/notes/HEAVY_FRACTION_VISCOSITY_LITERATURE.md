# Heavy-fraction viscosity: literature investigation

Research date: 2026-09-17. Research and validation tooling only; no material values, PR/Cp parameters, component basis, or runtime flow laws are changed.

The user requested ChatGPT-assisted literature research. ChatGPT completed a public-literature review in [this conversation](https://chatgpt.com/c/6aab5be3-bdc4-83ec-8a91-246901c46b82). No repository files, logs or source contents were sent. Its leads were checked independently against primary publisher/research sources where accessible; an abstract or bibliographic record is not treated as verification of the full equations or data tables. This report and its research outputs are local and ignored by Git, following the user's instruction.

## Findings that change the proposed approach

1. **Fit the heavy-end transport model to measurements of a comparable residue or fraction.** NBP, density and a very large surrogate MW do not uniquely specify low-temperature viscosity.
2. **An isolated heavy cut can be glass-like at room temperature while a mixture containing it is flowable.** The viscosity of a hypothetical pure liquid cut must not automatically be treated as its stable physical state.
3. **Temperature correlation and mixture correlation are separate calibration problems.** A good fit for each measured sample does not prove that an unfitted logarithmic mixing rule predicts a diluted crude.
4. **Straight-run and converted residues require different characterization.** Cracking does not guarantee a monotonic viscosity reduction, and asphaltene content alone does not determine viscosity.
5. **Our two heaviest curves have an additional units defect in their upstream fallback.** Agreement with DWSIM did not qualify them physically. See the local audit below.

## Most useful experimental evidence

### Vacuum-distilled Cold Lake fractions: a direct heavy-end benchmark

Mehrotra, Eastick and Svrcek, *Viscosity of Cold Lake bitumen and its fractions*, Canadian Journal of Chemical Engineering 67 (1989), 1004–1009, [DOI 10.1002/cjce.5450670620](https://onlinelibrary.wiley.com/doi/abs/10.1002/cjce.5450670620).

The publisher abstract reports these sample-specific results, converted here to SI dynamic viscosity:

| Material in that study | Temperature | Dynamic viscosity |
|---|---:|---:|
| Cut 1 | 30°C | 0.0043 Pa·s |
| Cut 4 | 30°C | 430 Pa·s |
| Cut 5 | 120°C | 800 Pa·s |

Cut 5 was glass-like at room temperature and softened around 100°C. This is not a wax melting-point measurement. The authors fitted temperature-dependent fraction viscosities and assessed mixture reconstruction. Their cut numbering has **no established identity mapping to TJL_PC01–13**. These values are benchmarks, not replacement properties.

Access: abstract verified; full cut boundaries and complete parameter tables not obtained. An [erratum](https://doi.org/10.1002/cjce.5450680226) exists. Its corrected equation must be checked before implementation; do not transcribe an uncorrected secondary equation.

### Diluted fractions: strong interpolation, weaker uncalibrated blending

Mehrotra, *Development of mixing rules for predicting the viscosity of bitumen and its fractions blended with toluene*, Canadian Journal of Chemical Engineering 68 (1990), 839–848, [DOI 10.1002/cjce.5450680515](https://onlinelibrary.wiley.com/doi/abs/10.1002/cjce.5450680515).

The abstract describes 14 blends measured at 22–100°C. The Walther temperature correlation fitted their data with AAD below 2%; a simple blending rule was considerably less precise, with predictions described only as within an order of magnitude. A temperature-dependent viscous interaction parameter improved blending. Both whole-bitumen and five-fraction representations were evaluated. Access: abstract verified, complete coefficients not recovered.

Project inference: reducing the number of transport groups can be reasonable, but the reduced representation still needs blend validation. The quoted 2% is a temperature-fit result, not a demonstrated error bound for our mixture rule or every crude.

### Residue fractions and parameter mixing

Zhang et al., *Viscosity Mixing Rule and Viscosity–Temperature Relationship Estimation for Oil Sand Bitumen Vacuum Residue and Fractions*, Energy & Fuels 33 (2019), 206–214, [DOI 10.1021/acs.energyfuels.8b03511](https://pubs.acs.org/doi/10.1021/acs.energyfuels.8b03511).

The study fractionated vacuum residue by supercritical-fluid extraction, tested blending rules, and proposed mixing of temperature-correlation parameters plus estimates from bulk properties. This is a strong candidate for a residue-specific transport model. Extracted fractions are not automatically equivalent to distillation cuts. Access: publisher abstract and supporting-information description verified; the complete fit equations, numeric applicability envelope and raw datasets were not retrieved. The publisher identifies `ef8b03511_si_001.pdf` as supporting information. No parameters from this paper are adopted yet.

### Straight-run versus hydrocracked residue: an important blend-data trap

Stratiev et al., *Empirical Modeling of Viscosities and Softening Points of Straight-Run Vacuum Residues from Different Origins and of Hydrocracked Unconverted Vacuum Residues Obtained in Different Conversions*, Energies 15 (2022), 1755, [DOI 10.3390/en15051755](https://www.mdpi.com/1996-1073/15/5/1755).

The study examines 30 straight-run residues and 33 hydrocracked residues. Crucially, the modeled viscosities are for **70% residue/30% FCC heavy-cycle-oil blends**, not neat residue. It finds different relationships for straight-run and hydrocracked materials; its best straight-run blend model uses MW and specific gravity. Reported overall AARE for that model is 14.1%, not a guarantee for other feeds. The authors leave recovery of neat-residue viscosity through suitable blending models to future work. Access: substantive publisher text verified. Full raw data were not imported.

Project inference: do not assign those blend viscosities to PC12, PC13 or a merged tail. A converted residue needs its own transport characterization.

### Thermal conversion: viscosity is not simply a function of remaining heavy mass

Zachariah and de Klerk, *Thermal Conversion Regimes for Oilsands Bitumen*, Energy & Fuels 30 (2016), 239–248, [DOI 10.1021/acs.energyfuels.5b02383](https://pubs.acs.org/doi/10.1021/acs.energyfuels.5b02383).

At a measurement temperature of 60°C and shear rate of 10 s⁻¹, the feed viscosity was 9.6 ± 0.3 Pa·s. After 30 minutes of reaction at 400°C, product viscosity reached 0.15 ± 0.03 Pa·s, then rose with further processing. Feed included 1.3 wt% solids, whereas product workup removed solids. The study shows that viscosity evolution cannot be represented safely by a universal monotonic function of conversion or asphaltene content. Access: methods/results text verified. Reaction temperature and viscosity-measurement temperature must remain separate fields.

The newer study *Visbreaking oilsands bitumen with Fischer–Tropsch wax* reports its Cold Lake feed at 100 Pa·s at 40°C and 10.3 Pa·s at 60°C. These are another sample's measurements, not values for the commercial Cold Lake Blend preset. [Publisher article](https://www.sciencedirect.com/science/article/pii/S0016236124005246). Access: relevant experimental table verified in publisher search output; full dataset not extracted.

## Correlation families and selection

| Family | Necessary inputs | Appropriate role | Main restriction |
|---|---|---|---|
| Measured table / constrained fit | Several measured viscosities, matching density if converting units, pressure and shear conditions | Preferred baseline for each qualified heavy material | Interpolation does not establish validity through softening, wax formation or reaction |
| ASTM D341 / Walther | At least two kinematic-viscosity measurements and their temperatures | Compact liquid viscosity-temperature fit | Limited temperature range; additional held-out points needed for validation |
| Mehrotra–Svrcek style bitumen fits | One or more viscosity anchors, exact equation variant/units, often fitted temperature/pressure parameters | Candidate when measurements are sparse and material resembles the validation set | A generalized one-parameter fit is a prior, not a universal unmeasured-residue model |
| Twu predictive petroleum-fraction correlation | Correctly defined boiling point and specific gravity | Candidate for characterized liquid fractions within verified scope | Extreme pseudo-NBPs and inferred densities require explicit applicability checks |
| Abbott/API-type reference-viscosity estimates | API gravity and Watson characterization factor, reference conventions | Screening/prior where original validity limits are satisfied | Do not let an unstable predicted anchor become a trusted measurement |
| Extended corresponding states | Pseudocomponent characterization, reference fluid and transport parameters | Candidate for composition/pressure coverage | EOS agreement does not qualify viscosity; heavy-end tuning may be necessary |
| MW/SG/SARA/CCR empirical residue models | The particular paper's descriptors and processing class | Residue-specific estimation or constrained calibration | Direction of regression and neat-versus-blend target must be checked |
| Wax/gel rheology | Temperature, composition, shear history/rate, wax/solid structure data | Separate model near loss of flow | No fixed viscosity threshold substitutes for yield/creep behavior |

[ASTM D341](https://store.astm.org/standards/d341) explicitly limits the intended temperature span and requires known kinematic viscosities at two temperatures. Its full normative equations are not reproduced here. A fit to dynamic viscosity using a similar mathematical shape is not automatically an ASTM D341 implementation.

Twu's original viscosity paper is *Internally consistent correlation for predicting liquid viscosities of petroleum fractions*, 24 (1985), 1287–1293, [DOI 10.1021/i200031a064](https://pubs.acs.org/doi/10.1021/i200031a064). Bibliography verified, full correlation and validation range not independently recovered. Do not confuse it with Twu's 1984 critical-property/MW correlation, or with DWSIM's `oilvisc_twu(T,T1,T2,v1,v2)`, which uses two supplied viscosity anchors. The latter cannot repair invalid anchors.

The Svrcek–Mehrotra 1988 *One parameter correlation for bitumen viscosity*, Chemical Engineering Research and Design 66, 323–327, is confirmed in the [author's publication list](https://profiles.ucalgary.ca/sites/default/files/2024-08/CV%20of%20Dr%20Mehrotra-2024.pdf). The original equation and a reliable DOI were not recovered; no guessed DOI is supplied.

Johnson, Svrcek and Mehrotra's corresponding-states study is [DOI 10.1021/ie00071a020](https://pubs.acs.org/doi/10.1021/ie00071a020), Industrial & Engineering Chemistry Research 26 (1987), 2290–2298. Publisher bibliography verified; quantitative performance claims require full-text review.

## Pressure, gas and solvent evidence

- **Compressed Cold Lake bitumen:** measured at 37–115°C and 0–10 MPa gauge; compression increased viscosity. [Mehrotra and Svrcek, 1987](https://onlinelibrary.wiley.com/doi/pdf/10.1002/cjce.5450650423). Abstract verified. Pressure basis is gauge, not absolute.
- **CO₂-saturated Cold Lake bitumen and fractions:** evaluated at 20–150°C and pressures up to 10 MPa using composition-dependent mixing. [Mehrotra, 1992](https://www.sciencedirect.com/science/article/abs/pii/0920410592900589). The abstract explicitly defines dynamic viscosity in mPa·s and temperature in K. Full interaction tables not recovered.
- **Multiple gas-saturated Alberta bitumens:** over 400 validation points, 12–120°C, up to 10 MPa. [Mehrotra, 1992](https://onlinelibrary.wiley.com/doi/abs/10.1002/cjce.5450700124). Abstract verified. This does not validate arbitrary liquid cutter stocks.
- **Athabasca plus n-decane:** experiments extend from room temperature to 344 K, up to 10 MPa, with 0.05–0.5 solvent mass fraction. [2013 primary study](https://www.sciencedirect.com/science/article/abs/pii/S0378381213001271). Relevant experimental/conclusion text verified; full tables not imported.
- **Coverage toward 200°C:** Kariznovi/Nourozieh/Abedi [10.2118/173182-PA](https://doi.org/10.2118/173182-PA) and Nourozieh/Kariznovi/Abedi [10.2118/176026-PA](https://doi.org/10.2118/176026-PA) are acquisition targets. Primary reference lists confirm them, but the publisher full texts were inaccessible. Secondary summaries disagree on 10 versus 14 MPa for the latter; no exact pressure envelope or implementation parameters are accepted here without checking its tables.

Pressure increase at fixed liquid composition and viscosity reduction from dissolving gas are different effects. A model should obtain the liquid composition from phase equilibrium and use a transport model validated for that composition, rather than treating total pressure as a universal viscosity-reduction factor.

## Additional leads from ChatGPT, independently checked

### How far do conventional heavy-fraction correlations actually reach?

The primary abstract for Abbott, Kaufmann and Domash's [1971 correlation](https://onlinelibrary.wiley.com/doi/abs/10.1002/cjce.5450490314) confirms reference predictions at 100°F and 210°F and identifies kerosene through heavy gas oil as its strongest region. That supports a distillate-first role; it is not evidence of ambient-temperature accuracy for asphaltenic residue.

Moharam, Al-Mehaideb and Fahim, *New correlation for predicting the viscosity of heavy petroleum fractions*, Fuel 74 (1995), 1776–1779, [DOI 10.1016/0016-2361(95)80007-5](https://research.uaeu.ac.ae/en/publications/new-correlation-for-predicting-the-viscosity-of-heavy-petroleum-f/), provides a particularly clear applicability envelope in the authors' university record: mid-boiling points 80–550°C, measurement temperatures 40–200°C, and kinematic viscosity 0.4–260 cSt. The reported 6.5% overall AAD covers 296 observations in that envelope. This does not cover our PC11–13 representative NBPs or the magnitude of the heaviest measured Cold Lake fraction viscosities.

ChatGPT also identified the NIST paper [*Transport Properties of Petroleum Fractions*](https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=831618). Its primary abstract describes an extended corresponding-states model from API specific gravity and mean boiling point with a propane reference and special continuation below propane's freezing point. This confirms that reference-fluid extrapolation needs deliberate treatment. The local report does not adopt ChatGPT's detailed error statistics without table verification. Bibliographic note: the PDF says 1999; the NIST landing record is dated 2000.

### A model that directly addresses future visbreaking

Marquez et al., *Viscosity of characterized visbroken heavy oils*, Fuel 271 (2020), 117606, [DOI 10.1016/j.fuel.2020.117606](https://www.sciencedirect.com/science/article/abs/pii/S0016236120306013), uses distillate boiling cuts below 370°C and residue SARA fractions above that characterization boundary, with an Expanded Fluid transport model. It reports about 8% viscosity deviation for the development bitumen and 17% for a second similar bitumen, but poor transfer to a chemically dissimilar Arabian vacuum bottom. Inputs include feed/product composition, temperature, pressure and conversion. Publisher abstract and methodology text verified; equations were not transcribed or implemented.

This is stronger support for separate chemical-quality/transport characterization than for deleting boiling cuts. The 370°C boundary belongs to that study and is not a universal VDU-residue definition. Retaining our physical/PR properties while adding an independently calibrated transport characterization remains possible.

A 2026 follow-up, [*Density and Viscosity of Visbroken Bditumens and Their Fractions*](https://pubs.acs.org/doi/abs/10.1021/acs.energyfuels.5c05483), examines two bitumens, vacuum bottom and deasphalted oil and reports average modeled-product viscosity deviation of 28%. Title spelling follows the publisher record. Abstract-level claims verified; its detailed datasets, temperature envelope and parameters remain acquisition targets. ChatGPT's claim of public supporting data is a lead, not evidence that this repository contains or has validated those tables.

### Blending accuracy and low-temperature solids

[Sánchez and de Klerk (2020)](https://pubs.acs.org/doi/10.1021/acs.energyfuels.0c01231) measured 1–10 wt% additions of six solvents to Athabasca bitumen at 30, 40 and 60°C. The better limited-input mixing rules still gave around 30% AARD. Their double-log rule uses **kinematic viscosity and mass fractions**; it is not the current simulator's log-dynamic-viscosity/mole-fraction rule. Abstract and displayed equation verified; no coefficients were adopted.

[Hasan et al. (2009)](https://pubs.acs.org/doi/10.1021/ef900313r) measured complex viscosity of Athabasca/Maya samples and nanofiltration fractions over 298–373 K. They found a temperature-dependent solid maltene contribution below 323 K. This is evidence for sample-specific liquid/solid rheology, not a rule that all crudes freeze at 50°C. Complex viscosity is not automatically interchangeable with the steady-shear dynamic viscosity used in pipe-loss calculations. Publisher abstract verified.

## Local DWSIM audit: stronger evidence than the previous suspicion

The public [`FluidProperties.vb`](https://github.com/DanWBR/dwsim/blob/windows/DWSIM.Thermodynamics/PropertyPackages/Models/FluidProperties.vb) defines `oilvisc_twu` as returning m²/s, whereas `viscl_letsti` returns Pa·s. The public [`PropertyPackage.vb`](https://github.com/DanWBR/dwsim/blob/windows/DWSIM.Thermodynamics/PropertyPackages/PropertyPackage.vb) replaces a NaN from the former with the latter, then multiplies the result by density. That multiplication is valid for kinematic viscosity but dimensionally inconsistent for an already dynamic viscosity.

`research/viscosity-tools/audit_residue_viscosity_units.py` independently evaluates the public fallback formula using the recorded characterization. It matches all **104 installed DWSIM 10.2.5.0 fallback probe values** within 1e-12 relative tolerance and confirms that PC12/PC13 API results equal dynamic fallback viscosity multiplied by density. Results are in `research/cold-flow/units-audit.json`.

There are two independent defects: the extra density factor and a correlation far outside its suitable temperature range. At 298 K, PC12 and PC13 have T/Tc approximately 0.248 and 0.226, respectively; the [Letsou–Stiel documentation](https://chemicals.readthedocs.io/chemicals.viscosity.html#chemicals.viscosity.Letsou_Stiel) states applicability near 0.76–0.98. Simply dividing the current tables by density would produce lower numbers, not a qualified residue viscosity model. No such production correction was made.

Unit conventions for future records: `mu[Pa s] = rho[kg/m³] * nu[m²/s]`; 1 cP = 0.001 Pa·s; 1 cSt = 10⁻⁶ m²/s. Convert using density at the same temperature and pressure. Keep source units, logarithm bases, composition basis and gauge/absolute pressure explicit.

## Recommended research-to-model path

1. Acquire the corrected 1989 fraction data, 1990 dilution data and 2019 residue-fraction study/supporting information. Record actual cut boundaries and characterization before matching them to any surrogate.
2. Use independent measured data to qualify the PC11–13 heavy-end viscosity behavior. Prefer a small number of calibrated transport groups initially; retain the existing PR/enthalpy component basis. A grouped residue is a transport representation, not a new universal chemical.
3. Fit viscosity-temperature curves in log space to positive measurements, with explicit validity limits. Compare interpolation, two-parameter temperature fits and calibrated bitumen-family fits using held-out temperatures and entire held-out samples. Do not score a fitted data point as an independent prediction.
4. Calibrate mixture behavior separately across residue concentration and diluent class. Verify mass-, mole- or volume-fraction definitions from the chosen equation. Do not assume that good pure-curve reproduction validates the current log-mole mixing rule.
5. Maintain distinct parameters or quality descriptors for straight-run, visbroken and hydrocracked residues. Preserve mass/elemental inventories through reactions; update transport characterization when products change.
6. Add wax/gel behavior only when the relevant experimental evidence is available. A softening temperature, pour point, wax onset and equilibrium fusion temperature must remain distinct.

This is a proposed workflow, not an authorization to replace production data with measurements from a different crude. The largest remaining gap is a defensible mapping and calibration for our specific heavy surrogates, not a lack of named correlation formulas.
