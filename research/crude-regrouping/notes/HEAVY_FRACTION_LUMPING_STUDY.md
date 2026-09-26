# Heavy-fraction lumping for VDU and residue conversion

Investigation: 2026-09-15. This is a design recommendation, not an implemented basis change or a qualification of a reduced thermodynamic model.

Follow-up: [CUT_ELEMENTAL_PROFILES.md](CUT_ELEMENTAL_PROFILES.md) records the subsequently sourced H/S/N/metals and residue indicators, candidate assay-specific research profiles, and the remaining C/O and reaction-model gaps. These profiles have no production database or Java integration.

## Recommendation

Keep the current 20-component catalog as the compatibility baseline. Add separate, versioned reaction-group mappings over that basis before deleting or replacing components. For an experimental smaller thermodynamic basis, start by merging **tjl19_pc12 + tjl19_pc13**, retaining PC10 and PC11. With VDU development in mind, the best use of the freed slot may be to split PC10 at an assay-supported residue boundary rather than reduce the total count.

There are two different objectives: a VDU needs a useful distribution of volatility; a reactor needs a useful distribution of reactivity. A single universal "heavy oil" component would hide both the VGO/residue split and differences in residue conversion behavior.

Vacuum distillation recovers gas oils from atmospheric bottoms at reduced pressure to limit thermal degradation. Those gas oils and the remaining residue are different downstream feeds. [EIA: vacuum distillation](https://www.eia.gov/todayinEnergy/detail.php?id=9130).

## What the current database actually contains

The following values come directly from the bundled TJL component/property JSON. Cut boundaries are estimated adjacent-NBP midpoints. They are atmospheric-equivalent characterization temperatures, **not VDU operating temperatures**. Extreme representative NBPs are modeling proxies, not evidence that those materials can be experimentally distilled intact at those temperatures.

| Component | Estimated cut, °C | Representative NBP, °C | MW, g/mol |
|---|---:|---:|---:|
| tjl19_pc08 | 377.875–435.783 | 402.207 | 342.74 |
| tjl19_pc09 | 435.783–513.588 | 469.360 | 435.56 |
| tjl19_pc10 | 513.588–609.465 | 557.817 | 594.18 |
| tjl19_pc11 | 609.465–730.508 | 661.114 | 844.25 |
| tjl19_pc12 | 730.508–874.851 | 799.902 | 1329.87 |
| tjl19_pc13 | above 874.851 | 949.800 | 2218.91 |

Our five imported crude curves stop at 590°C. Everything allocated to PC11–PC13 is therefore in the extrapolated part; part of PC10 is extrapolated too. This makes fine distinctions among the final cuts weakly supported by source measurements. It does not establish that their thermodynamic or reaction behavior is identical.

PC10 straddles the **550°C** boundary explicitly present in the source assays. A different project might use a different residue definition; the boundary must be declared per characterization/reaction dataset, not treated as a universal constant.

## Quantitative screening of the five feeds

These are **wt% of whole crude**, calculated from committed `research/crude-assays/converted-compositions.json`, not wt% of atmospheric bottoms and not predicted VDU yields. The 550°C+ column uses the converter's rounding-corrected source cut yield.

| Crude | PC12–13, >730.508°C | PC11–13, >609.465°C | PC10–13, >513.588°C | Source 550°C+ |
|---|---:|---:|---:|---:|
| WTI Light - Export | 0.293 | 2.092 | 6.963 | 4.706 |
| Upper Zakum | 3.340 | 12.307 | 24.419 | 19.877 |
| Bonga | 1.017 | 4.717 | 12.396 | 8.885 |
| Dalia | 3.980 | 15.419 | 31.270 | 25.230 |
| Cold Lake Blend | 7.611 | 24.580 | 41.954 | 36.211 |

If all PC10–13 material were simply labeled 550°C+ residue, the bookkeeping would misclassify **2.26–6.04 percentage points of whole-crude mass** that belongs below 550°C under our conversion assumptions. This is an ideal-cut classification discrepancy, not a simulated VDU recovery error. Conversely, PC11–13 alone excludes substantial genuine 550–609°C residue carried inside PC10.

Even one merged tail needs feed-aware characterization. Preserving mass and the original pseudo-mole total gives

`M_group = sum(w_i) / sum(w_i / M_i)`.

For PC11–13 this yields approximately **893, 953, 925, 946, and 973 g/mol**, respectively. One universal fixed MW cannot preserve both moments for all five feeds, let alone all later blends and converted residues. These numbers inherit the existing surrogate MW assumptions; they are not new measurements.

## Candidate bases

| Candidate | Hydrocarbon count | Assessment |
|---|---:|---|
| Canonical basis plus separate reaction groups | 20 | Recommended first step; retains existing separation data and saves |
| Merge PC12+PC13 | 19 | Best first candidate for numerical reduction; qualify at intended VDU conditions |
| Merge PC11+PC12+PC13 | 18 | More aggressive; could suppress deep gas-oil recovery, so requires a wider vacuum sensitivity study |
| Merge PC10 through PC13 | 17 | Poor common basis for VDU; removes the gas-oil/residue transition |
| Merge PC12+PC13; split PC10 at 550°C | 20 | Preferred VDU-oriented characterization experiment; reallocates resolution toward a measured boundary |

The final option requires new properties and assay rebinning for both PC10 subcuts; merely editing their display bounds does not split a thermodynamic component. The exact 609.465°C boundary is inherited from the old midpoint scheme and need not be retained in a fully recharacterized future dataset. If a selected VDU design targets a different end point, allocate cuts around that end point instead.

No speed improvement has been benchmarked. For illustration, reducing 20 to 19 or 18 lowers the count of terms in a dense N×N mixing calculation by 9.75% or 19%, respectively. That is not an end-to-end solver speedup estimate.

## How to support cracking without losing separation detail

Use the same material inventory through CDU, VDU, reactors, and downstream fractionation, with operation-specific views:

```text
CDU bottoms -> VDU -> gas-oil cuts -> downstream conversion
                  -> vacuum residue -> thermal conversion / residue hydrocracking

Persistent stream inventory: component masses + component/lot quality information
Separation view: property datasets and boiling distribution
Reaction view: grouped masses + reactivity, elemental inventory, catalyst state
```

The stream entering a VDU must be the **actual accepted CDU bottoms composition and enthalpy**, not the original whole-crude assay rescaled to a smaller total. Likewise, a reactor must receive actual VDU residue, not a hardcoded fraction of fresh crude.

A grouping map can sum component mass into kinetic groups. The inverse is not unique. Preserve the original member distribution for reversible display/aggregation; after reaction, use a calibrated product-yield distribution and a depletion rule that accounts for different member reaction rates to update component masses. Do not redistribute reacted products using the fresh crude's original TBP curve. Grouping is exact bookkeeping; assuming one rate constant for a chemically diverse group is a separate approximation.

Thermal cracking experiments on Kuwaiti vacuum residues used process-specific lumped pathways and distinguished unconverted oil from cracked product classes. This supports a small reaction model, but not transferring fitted yields or rate constants unchanged to every crude or thermal process. [AlHumaidan et al., thermal cracking behavior](https://www.sciencedirect.com/science/article/pii/S0016236113001385).

For an initial thermal model, I would use liquid product ranges, gas, unconverted residue, and a separate coke/solids inventory. Visbreaking and coking should have different fitted model records, even if they share reactor software. Olefin content and altered product density/MW need explicit treatment or clearly labeled approximations.

For residue hydrocracking, preserve an additional chemical-quality axis. A published vacuum-residue hydrocracking model uses SARA residue classes—saturates, aromatics, resins, and asphaltenes—plus distillate products, gas, and coke. [Fukuyama and Terai](https://www.tandfonline.com/doi/abs/10.1080/10916460601054768). SARA membership cannot be assigned from our PC number or NBP alone; in particular, PC13 must not simply be renamed "asphaltenes."

If SARA data are unavailable, a calibrated easy-/difficult-to-convert split is a possible simpler model, but its fractions remain unknown until estimated or measured. Missing quality data must not silently mean zero.

## Database and solver gaps to address

The following are proposed additions, not fields already implemented:

| Area | Required information/behavior |
|---|---|
| Cut quality | C/H/N/S/O mass inventory, Ni/V and other selected contaminants; per-cut data where available, allocation method and uncertainty otherwise |
| Residue behavior | SARA with analytical method, aromaticity or hydrogen-content proxy, measured CCR/MCR indicator; distinguish indicators from actual coke inventory |
| Reaction model | Named process/catalyst, kinetic groups, rate-law type, Arrhenius units, temperature/pressure domain, yield/selectivity model, conversion basis and provenance |
| Hydroprocessing | Explicit H2 consumption; H2S/NH3 and relevant water products; catalyst activity and contaminant deposition; gas/liquid separation and recycle |
| Energy | Compatible formation enthalpies or calibrated reaction heats; the current sensible/departure enthalpy reference does not supply reaction heat |
| Solids | Separate coke and deposited-metal inventories; no artificial PR vapor phase for solids |
| Mapping | Versioned mass-conserving aggregation and product-distribution rules with no ambiguous reverse mapping |

The imported N/S/metals are currently whole-crude provenance metadata in raw JSON. The resolved `MaterialCatalog.Assay` contains amounts, basis, and scaling, not a conserved per-cut elemental inventory. Some original producer tables may supply more cut-quality data than we imported; those should be checked before assuming missing values must be estimated. Do not allocate all sulfur/nitrogen/metals to the final cut, and do not double-count trace inventories as extra feed mass.

Residue hydroprocessing includes demetallization, desulfurization, denitrogenation, and hydrogen addition; these affect both the products and catalyst behavior. [Axens residue hydroprocessing](https://www.axens.net/expertise/oil-refining/residue-hydroconversion-hydroprocessing). For reactions, conserve total mass **including net hydrogen uptake**, each tracked element, and energy. Mole count is not conserved by cracking.

The present packages admit **50 kPa–2 MPa**. This envelope is unsuitable as a claimed qualification for either deep-vacuum VDU operation or high-pressure residue hydrocracking. An EPA VDU study describes ejector systems reaching a few kPa and below; a published residue-hydrotreating study investigated 6–10 MPa. [EPA VDU study](https://nepis.epa.gov/Exe/ZyPURL.cgi?Dockey=910108AN.TXT), [five-lump hydroprocessing study](https://link.springer.com/article/10.1007/s13203-015-0142-x). Changing JSON pressure limits alone is not validation.

VDU work should qualify low-pressure flashes, gas-oil/residue cut recovery, heat duties, steam behavior and pressure profiles. The existing atmospheric condenser/reflux setup should not be assumed to represent a VDU overhead/vacuum system. Start with specified absolute pressures and an appropriate overhead boundary, then add vacuum-system energy/capacity and entrainment/wash-zone behavior as separate fidelity upgrades. Thermal degradation limits belong to residence-time/severity handling, not an arbitrary universal maximum boiling temperature.

## Migration and validation plan

1. Add reaction-group/quality records without changing current components or thermodynamic fingerprints. Give reaction and mapping models their own content fingerprints; include them in reactor cache/result identity.
2. Build a VDU baseline using actual CDU bottoms and independently qualified vacuum properties. Use at least a light, sour, and heavy crude plus blends; the current shared TJL properties remain an acknowledged approximation.
3. Fit experimental 19-/18-component or redistributed-20-component packages as new IDs. Preserve aggregate mass, reference liquid volume and sensible heat behavior where applicable; fit PR behavior over the intended T/P/composition domain. Do not just average critical properties, acentric factors, or viscosity.
4. Compare full and reduced flashes/derivatives, LVGO/HVGO/residue yields and curves, furnace/condenser/pumparound duties, and recycle behavior. Set tolerances before selecting a reduced basis. Passing against the existing model establishes reduction parity, not agreement with real crude behavior.
5. Calibrate thermal conversion first with explicit gas/coke yields and heat effects. Add hydrogen and contaminant balances, catalyst effects, and hydrocracking kinetics against a declared dataset afterward.
6. Keep old packages and saved axes loadable. A many-to-one component merge is not a rename: migrate on a mass basis with declared MWs, reject ambiguous expansion, and invalidate old warm starts/neural eligibility for changed scientific datasets.

Evidence collected here: source/code inspection and arithmetic aggregation of existing assays. No production JSON or Java was changed; no reduced-basis flash/column benchmark or reactor simulation has been run. The recommendation is therefore **to prototype and qualify the tail merge, not to publish it as an equivalent replacement yet**.
