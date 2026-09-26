# Estimating reaction enthalpy and kinetic constants — research for a tool

**RESEARCH ONLY. No implementation exists and nothing below is approved.**
Date: 2026-09-01. General-purpose survey (deliberately **not** framed against the CreateChemE
codebase, per request): methods, data sources, accuracy ceilings, existing software, and a
recommended tiered architecture for a tool that, given a balanced chemical reaction, returns
ΔH°rxn(T) — and, where honestly possible, equilibrium and kinetic constants.

Purpose: establish what is *solved*, what is *estimable*, and what is *fundamentally data-bound*,
so a build plan can commit to deliverables that are actually achievable at each tier.

---

## 1. Problem framing — three asks of very different difficulty

| Quantity | Nature of the problem | Achievable without experiment |
| --- | --- | --- |
| **ΔH°rxn(T)** | Bookkeeping (Hess's law) when species data exist; well-understood estimation when not | Yes — ±1–5 kJ/mol with data, ±5–30 kJ/mol estimated |
| **K_eq(T)** | Same bookkeeping extended to ΔG°rxn; rigorous thermodynamics | Yes — but exponentially sensitive to ΔG error (§3) |
| **k(T) = A·exp(−Ea/RT)** | Depends on the *transition state*, catalyst, solvent — not on reaction endpoints | Only partially — order-of-magnitude via correlations/family rules; factor 2–10 via quantum chemistry; exact only from experiment |

The critical structural insight: **enthalpy and equilibrium are species-level problems** (get
ΔfH°, S°, Cp(T) per species, then any reaction among those species is arithmetic), while
**kinetics is a reaction-level problem** (every reaction is its own experiment/calculation).
A tool should therefore be built as a *species thermochemistry resolver* first, with reaction
properties derived, and kinetics layered on top as a separate, honesty-labeled subsystem.

Every mainstream process simulator (Aspen Plus, HYSYS, DWSIM) embodies this split: they compute
reaction enthalpy and Gibbs-minimization equilibrium natively from species data, but require the
**user to type in Arrhenius constants** for kinetic reactors. Nobody ships a general kinetics
estimator in production — that is the honest baseline expectation.

---

## 2. Reaction enthalpy

### 2.1 The identity everything reduces to

```
ΔH°rxn(298.15) = Σ νᵢ·ΔfH°ᵢ(298.15)                      (Hess's law)
ΔH°rxn(T)      = ΔH°rxn(298) + Σ νᵢ·[Hᵢ(T) − Hᵢ(298)]     (Kirchhoff, needs Cp(T))
```

Phase matters: ΔfH° values are phase-specific. Cross-phase reactions need ΔHvap/ΔHfus
corrections at the relevant T (data, Watson correlation, or Clausius-Clapeyron from a vapor
pressure correlation). For solution-phase reactions, add solvation deltas (§2.5).

So the tool's core is a **per-species record**: `{ΔfH°(298), S°(298), Cp(T) polynomial, phase,
source, uncertainty}`. NASA-format 7-term polynomials bundle Cp/R, H/RT, S/R in one object and
are the de-facto interchange format (Cantera, CHEMKIN, Burcat all speak it).

### 2.2 Tier 0 — tabulated data (always preferred)

| Source | Coverage | Notes |
| --- | --- | --- |
| **ATcT** (Active Thermochemical Tables, Argonne) | ~2,000–3,000 species, small molecules + radicals | Best-in-class accuracy; a self-consistent *thermochemical network*, not per-reaction values; full uncertainties. Free (atct.anl.gov). |
| **Burcat "Third Millennium" DB** (with ATcT updates) | ~3,000 species incl. combustion radicals, some condensed phase | NASA-7 polynomials → Cp, H, S at any T directly. Free download; the single most convenient machine-readable backbone. |
| **NIST Chemistry WebBook** | Very broad organic/inorganic; gas *and* condensed phase ΔfH, ΔHvap, Antoine | Free, browsable, some structured retrieval; the reference for condensed-phase values. |
| **`chemicals`** (Python, ChEDL / Caleb Bell) | Thousands of compounds, ΔfH/Gf/S among many properties, sourced+cited | `pip install chemicals`; the fastest way to bootstrap a lookup layer. MIT license. |
| **DIPPR 801** | ~2,400 industrial compounds, evaluated | The process-industry gold standard, but **paywalled** — plan around it, not on it. |

Design consequence: with Burcat + NIST WebBook + `chemicals`, a curated local species DB covering
any realistic industrial reaction set (hundreds of species) is a data-entry task, not a research
task. Errors in ΔH°rxn from tabulated ΔfH are typically **±1–5 kJ/mol**.

### 2.3 Tier 1 — group contribution (species missing from tables)

| Method | Typical per-species error (ΔfH°, gas) | Implementations |
| --- | --- | --- |
| **Benson group additivity** (2nd-order: group + ring/gauche/radical corrections) | ±4–10 kJ/mol | **RMG-database** (1,580 curated groups, 9 correction classes incl. radicals/polycyclics); **pGrAdd** (`pip install pgradd`, SMILES in → ΔfH/S/Cp out, 6 databases); NIST "Structures & Properties" web tool |
| **Joback–Reid** (1st-order, 41 groups) | ±15–30 kJ/mol, worse for polyfunctional | `thermo` Python lib (RDKit-based, SMILES in); trivially portable — the coefficient table fits on one page. Also yields ΔfG° and Cp_ig(T). |
| Constantinou–Gani / Marrero–Gani | between the two above | Less common in open code |

Benson also yields **S°(298) and Cp(T)** — i.e. the full record needed for K_eq, not just ΔH.
Reaction-level error grows roughly in quadrature over species that had to be estimated, but
partially cancels when reactant and product share most groups (the same cancellation that makes
isodesmic schemes work).

### 2.4 Tier 2 — computed thermochemistry (no data, exotic species)

- **ML interatomic potentials — the modern sweet spot.** AIMNet2 (14 elements, neutral+charged,
  ~1.5 kcal/mol ≈ 6 kJ/mol RMSE vs its DFT reference) and ANI-1ccx (CHNO, trained to
  CCSD(T)/CBS-quality) give gas-phase reaction energies in *seconds on a laptop*, no license.
  AIMNet2-rxn (2025) reports 1–2 kcal/mol along whole reaction paths for organic mechanisms.
  Caveat: gas-phase organics only; atomization-based ΔfH needs care — best used for *reaction*
  energies directly (endpoint cancellation).
- **DFT / composite quantum chemistry — the referee.** ωB97X-D/def2-TZVP-class DFT: ~5–15 kJ/mol
  on reaction enthalpies raw, much better inside isodesmic/isogyric schemes; composite methods
  (G4, CBS-QB3) reach ~4–8 kJ/mol ("chemical accuracy") at hours-per-species cost. Free codes:
  ORCA, Psi4, xtb (GFN2-xTB for cheap screening at ±20–40 kJ/mol).

### 2.5 Phase and solution corrections

- Gas→liquid species: subtract ΔHvap(T) (NIST data; Watson exponent 0.38 scaling from the
  normal-boiling-point value; or derive from the vapor-pressure correlation already in hand).
- Solution-phase reactions: RMG-database ships **solvation corrections** (tabulated 195 solvents ×
  152 solutes + a group-additivity scheme for arbitrary solutes); the rigorous route is
  COSMO-RS/COSMO-SAC solvation free energies. For most industrial liquid reactions among stable
  molecules, ΔHrxn(liquid) from liquid-phase ΔfH tables (NIST) is simpler and better than
  gas-phase + correction.

---

## 3. Equilibrium constants — the free, underrated deliverable

With S°(298) and Cp(T) in the species record (Burcat polynomials or Benson GA provide both):

```
ΔG°rxn(T) = ΔH°rxn(T) − T·ΔS°rxn(T)        K_eq(T) = exp(−ΔG°rxn(T)/RT)
```

plus van 't Hoff for quick T-extrapolation. This is *rigorous* — no kinetics knowledge needed —
and answers "which direction, how far, how does T/P shift it", which for many tool uses
(feasibility screening, reactor heat duty, equilibrium-limited conversions like esterification or
ammonia synthesis) is worth more than a rate constant.

**Sensitivity warning to surface in the tool's UI:** at 298 K, a ±6 kJ/mol error in ΔG°rxn is a
factor ~11 in K_eq (×/÷ e^(6000/RT)). Tabulated-data tier → K_eq good to a factor of a few;
Joback tier → K_eq is an order-of-magnitude indicator only. Report K_eq with an uncertainty band
derived from the species-record uncertainty classes, never as a bare number.

---

## 4. Kinetic constants

### 4.1 Why this is categorically harder

A and Ea are properties of the **transition state and mechanism** — including catalyst surface and
solvent — not of the reactant/product thermochemistry. Two reactions with identical ΔHrxn can
differ by 10²⁰ in rate. There is consequently no kinetic analogue of the ΔfH table covering
"reactions in general", and any tool claim must be tiered by honesty:

### 4.2 Tier 0 — look it up

| Source | Scope | Notes |
| --- | --- | --- |
| **NIST Chemical Kinetics Database** (kinetics.nist.gov) | ~38,000 records, 11,700 reactant pairs, thermal **gas-phase** | Arrhenius `A, n, Ea/R` + uncertainty + valid T range; literature coverage effectively ends ~2000; dominated by combustion/atmospheric radical chemistry |
| **NDRL/NIST Solution Kinetics DB** | solution-phase radical reactions | companion database |
| Evaluated combustion mechanisms (GRI-Mech 3.0, AramcoMech, CRECK, ATcT-consistent sets) | C0–C4+ combustion | internally consistent Arrhenius sets, Cantera-ready |
| Literature / patents per industrial reaction | esterifications, hydrogenations, reforming, etc. | LHHW or power-law fits with catalyst identity attached; scattered, must be curated by hand |

For *named industrial reactions* the realistic plan is a **hand-curated library** (tens of
reactions with cited A, Ea, rate form, catalyst, valid T range) — this is what every simulator
user does individually; shipping it curated is genuine value.

### 4.3 Tier 1 — reaction-family rate rules (the mature automation)

**RMG** (Reaction Mechanism Generator, MIT/Green group, open source) is the state of the art in
automated kinetics estimation: **87 reaction families** (H-abstraction, radical addition,
disproportionation…) with rate-rule trees trained on **8,655 curated training reactions** (~21,000
reactions across 92 kinetics libraries), falling back to nearest-neighbor rules when no exact
match exists; recent versions add ML estimators. Also estimates the thermo side (Benson GA per
§2.3) and liquid-phase corrections. Queryable interactively at rmg.mit.edu (SMILES in). Accuracy
for well-trained families: typically within a factor of ~10; radical gas-phase chemistry only —
**no acid/base, no heterogeneous catalysis in the general case**.

### 4.4 Tier 2 — thermochemistry-anchored correlations (the "if possible" tier)

Within a *family* of similar reactions, barrier correlates with reaction enthalpy:

- **Evans–Polanyi/BEP:** `Ea = E₀ + α·ΔHrxn`, α ≈ 0.3–0.7 — two parameters per family, breaks
  down for large |ΔH| (can predict negative Ea).
- **Blowers–Masel (2000):** one parameter per family (intrinsic barrier E₀ᵃ); correct asymptotic
  behavior (Ea→0 for very exothermic, Ea→ΔH for very endothermic); adopted as a native reaction
  rate type in **Cantera** and used in RMG and microkinetic screening workflows.

This is the strongest defensible answer to "estimate kinetics from the enthalpy my tool already
computes": user (or a built-in table) supplies the family's intrinsic barrier and a typical A
(from collision-theory magnitudes: ~10¹⁰–10¹¹ L/mol/s bimolecular gas ceiling, 10¹³±1 s⁻¹
unimolecular; liquid diffusion limit ~10⁹–10¹⁰ L/mol/s), the tool supplies ΔHrxn(T) and returns
an **order-of-magnitude k(T) labeled as such**. Always enforce microscopic reversibility:
`k_reverse = k_forward / K_eq(T)` — this single rule keeps even crude kinetics
thermodynamically consistent and is non-negotiable in the tool design.

### 4.5 Tier 3 — compute the barrier (automated TST)

Automated pipelines have made "calculate k(T) from scratch" a batch job rather than a PhD:

- **autodE** (Oxford/Duarte): SMILES reaction in → conformer search → GFN2-xTB screen → DFT TS via
  mGSM → barrier + profile. Most turnkey for organic solution/gas reactions.
- **ARC** (RMG ecosystem) and **Arkane**: automate species/TS QC jobs and convert to k(T) with
  tunneling + master-equation (pressure-dependence) handling.
- **KinBot** (Sandia): automated PES exploration for gas-phase radical systems.
- Cost/accuracy: hours–days per reaction on a workstation; good DFT+TST with tunneling lands
  within a **factor of 2–10** of experiment; a 4 kJ/mol barrier error is already a factor ~5 at
  298 K. Realistic as an optional offline "tier 3 job runner", not an interactive feature.

### 4.6 Tier 4 — ML barrier prediction (research frontier, moving fast)

- Benchmark dataset **RDB7**: ~12,000 organic reactions with CCSD(T)-F12-refined forward/reverse
  barriers + TS geometries; the standard training/eval set.
- Chemprop-style **D-MPNN on reactant+product graphs** predicts barriers with ~2–4 kcal/mol MAE
  in-domain; 2025 benchmarking (ChemTorch; RSC Digital Discovery graph/3D study) shows
  structure-encoding models beat fingerprint/sequence baselines by ≥6 kcal/mol MAE and 3D input
  helps further. Out-of-domain (new reaction classes, catalysis, solvent) remains unreliable.
- Position for the tool: a plausible *future* tier-2.5 plug-in; not a foundation to build on
  today for arbitrary user reactions.

### 4.7 Liquid phase and catalysis — the honesty boundary

- Solvent effects on rates: computable by pairing gas-phase QC/TST with **COSMO-RS** solvation of
  reactant vs TS — published workflows reach MAE ≈ 0.9 in log₁₀ k(liquid) (i.e. within ×8);
  diffusion-limit capping (Smoluchowski/Collins–Kimball) needed for fast reactions.
- **Heterogeneous catalysis:** rate constants are catalyst-specific; BEP scaling on DFT-computed
  adsorption energies (microkinetics) is a research workflow, not an estimator. The tool should
  say "supply literature LHHW/power-law parameters for catalytic reactions" rather than pretend.

---

## 5. Existing software worth reusing instead of rebuilding

| Tool | What it gives a builder | License |
| --- | --- | --- |
| **RMG + RMG-database** | The closest existing thing to the whole ask: thermo GA + solvation + kinetics families; database files usable standalone; web API at rmg.mit.edu | MIT |
| **Cantera** | Target output format + evaluation engine: NASA-poly thermo, Arrhenius/**Blowers–Masel** rate types, equilibrium solver | BSD |
| **`chemicals` / `thermo` (ChEDL)** | pip-installable property data + Joback + vapor pressure/ΔHvap correlations | MIT |
| **pGrAdd** | Benson GA from SMILES in a lightweight package | GPL-ish (check) |
| **RDKit** | SMILES parsing, canonicalization, substructure (group matching), stoichiometry/mass-balance validation | BSD |
| **AIMNet2 / ANI (TorchANI)** | ML-potential reaction energies for the computed tier | MIT-ish |
| **autodE / ARC / Arkane** | Automated barrier/TST tier | MIT |
| **ORCA / Psi4 / xtb** | QC engines behind the computed tiers | free (ORCA academic) |

A tool that is essentially **glue**: RDKit (input) → species resolver (curated DB + Burcat +
`chemicals` + pGrAdd/Joback) → reaction arithmetic → Cantera-format output, with RMG lookups and
Blowers–Masel for kinetics — reuses battle-tested parts for every hard sub-problem.

---

## 6. Recommended tool architecture (tiered resolver cascade)

```
INPUT  reaction as SMILES + stoichiometry + phase + T (,P)
       └─ RDKit: canonicalize, balance check (atoms/charge), duplicate detection

SPECIES THERMO RESOLVER (per species, first hit wins, uncertainty class attached)
       T0 curated local DB (JSON/SQLite of NASA-7 records, cited)
       T1 Burcat / ATcT import        [±1–5 kJ/mol]
       T2 NIST WebBook / `chemicals`  [±2–8 kJ/mol]
       T3 Benson GA (pGrAdd or RMG groups) [±5–10 kJ/mol]
       T4 Joback (never silently: flag "rough") [±15–30 kJ/mol]
       T5 optional: ML potential / QC job [±4–8 kJ/mol, gas organics]

REACTION LAYER (pure arithmetic — deterministic, testable)
       ΔHrxn(T), ΔSrxn(T), ΔGrxn(T), K_eq(T), van 't Hoff coefficients,
       adiabatic ΔT, phase corrections (ΔHvap), heat-duty helper
       → uncertainty = quadrature over species classes; always reported

KINETICS RESOLVER (separate subsystem, honesty labels mandatory)
       K0 curated literature library (A, n, Ea, rate form, catalyst, T range, citation)
       K1 RMG family match (gas-phase radical chemistry)
       K2 Blowers–Masel / BEP from ΔHrxn + family intrinsic barrier + A heuristic
          → label "ESTIMATED — order of magnitude"
       K3 offline QC/TST job ticket (autodE/ARC), results promoted into K0
       invariant: k_r ≡ k_f / K_eq(T)   (microscopic reversibility, always)

OUTPUT Cantera YAML + JSON report {values, tier used per species/reaction, uncertainty band}
```

Validation plan: ΔfH resolver vs held-out ATcT species; end-to-end ΔHrxn/K_eq vs textbook
reactions with well-known answers (ethyl acetate esterification K≈4, ammonia synthesis,
water-gas shift, methanol synthesis, ethylene hydration); kinetics tiers vs NIST DB entries.

### Phasing (effort-honest)

1. **Phase 1 — species resolver + reaction arithmetic** (days): Burcat import + `chemicals`
   lookup + Kirchhoff + K_eq + uncertainty classes. Deterministic, high value, zero research risk.
2. **Phase 2 — estimation fallbacks** (days–weeks): Joback (trivial) then Benson via pGrAdd/RMG
   groups; phase corrections.
3. **Phase 3 — kinetics** (weeks): curated library format + Blowers–Masel estimator +
   reversibility enforcement + Cantera export.
4. **Phase 4 — computed tiers** (optional/ongoing): AIMNet2 single-command reaction energies;
   autodE batch barrier jobs feeding the curated library.

---

## 7. Accuracy expectations to publish with the tool (do not oversell)

| Deliverable | Tier | Realistic error |
| --- | --- | --- |
| ΔHrxn, tabulated species | T0–T2 | ±1–8 kJ/mol |
| ΔHrxn, Benson-estimated species | T3 | ±5–15 kJ/mol |
| ΔHrxn, Joback | T4 | ±20–50 kJ/mol (reaction-level, partial cancellation) |
| ΔHrxn, ML potential | T5 | ±4–8 kJ/mol (gas organics) |
| K_eq, tabulated | T0–T2 | within factor ~2–10 (ΔG ±2–6 kJ/mol) |
| K_eq, estimated species | T3+ | order-of-magnitude indicator |
| k(T), literature | K0 | factor 2–3 (as reported) |
| k(T), RMG family rules | K1 | ~factor 10 (well-trained gas families) |
| k(T), Blowers–Masel from ΔH | K2 | 1–3 orders of magnitude |
| k(T), DFT+TST computed | K3 | factor 2–10 |

Rule-of-thumb the UI should surface: **1 kcal/mol (4.2 kJ/mol) of Ea or ΔG error ≈ factor 5.4 in
k or K at 298 K.** Exponential sensitivity is the entire story of this domain; a tool that prints
uncertainty tiers next to every number is credible, one that prints bare numbers is not.

---

## 8. Key sources

- RMG database & estimators: Johnson et al., *J. Chem. Inf. Model.* 2022 (RMG Database paper);
  rmg.mit.edu; RMG-Py docs (group additivity, liquid-phase systems).
- Thermo data: ATcT (atct.anl.gov); Burcat & Ruscic Third Millennium DB; NIST Chemistry WebBook;
  `chemicals`/`thermo` (ChEDL) docs.
- Group additivity: Benson (via RMG/NIST S&P); pGrAdd (Vlachos group, *Comput. Phys. Commun.* 2021).
- Kinetics data: NIST Chemical Kinetics DB (kinetics.nist.gov); NDRL/NIST solution DB.
- Correlations: Blowers & Masel, *AIChE J.* 2000; Blowers–Masel-in-Cantera microkinetics
  (ACS Omega/PMC 2024).
- Automated TST: autodE (Duarte group); KinBot (Sandia); ARC/Arkane (RMG ecosystem).
- ML: AIMNet2 (*Chem. Sci.* 2025), AIMNet2-rxn (ChemRxiv 2025), ANI-1ccx; RDB7 barrier dataset;
  ChemTorch benchmark (2025); graph-based barrier prediction, *Digital Discovery* 2025.
- Solvent kinetics: COSMO-RS liquid-phase rate constants, *J. Phys. Chem. A* 2023.
