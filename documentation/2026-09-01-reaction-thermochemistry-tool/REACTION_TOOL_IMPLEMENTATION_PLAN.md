# Reaction thermochemistry & kinetics tool — implementation plan

**PLANNING ONLY. No code exists. Nothing below is approved or started.**
Date: 2026-09-01. Companion research: `documentation/REACTION_ENTHALPY_KINETICS_TOOL_RESEARCH.md`
("Endpoints and Barriers", https://claude.ai/code/artifact/d70a69c1-b0c6-476d-9e0b-82472ef35480).
Frame: general-purpose standalone tool, **not** integrated with any consumer in v1 — the JSON
output contract is the integration surface for anything downstream (a process simulator, a mod, a
notebook).

Working defaults, chosen so the plan is concrete; each is cheap to override **before M1 starts,
expensive after**:

| Decision | Default | Rationale |
| --- | --- | --- |
| Language / runtime | Python ≥ 3.11, `pyproject.toml`, `uv` lockfile — **CONFIRMED by user 2026-09-01** | Every reusable component (RDKit, `chemicals`, pGrAdd, Cantera) is Python |
| Packaging | Library + CLI in one package, working name **`rxnthermo`** | Name provisional; collision check before first publish |
| Repo | Standalone new repository, MIT license | All planned deps are MIT/BSD (pGrAdd verified MIT) |
| Units | SI internally (J, mol, K, Pa, m³); kJ/mol display only at the edge | Single conversion boundary |
| Standard state | P° = 100 000 Pa; ideal-gas reference; concentration basis mol/m³ | Matches NASA-polynomial convention |
| Canonical species key | InChIKey (from RDKit), with CAS + name alias table | Survives SMILES non-canonicality |
| No network at runtime | All data vendored snapshots with provenance manifest | Reproducibility; NIST has no API anyway |

---

## 1. Objective, scope, non-goals

**Objective.** A pip-installable tool where

```
rxnthermo compute "CC(=O)O + CCO = CC(=O)OCC + O" --phase liquid --T 298:373
```

returns ΔH°rxn(T), ΔS°rxn(T), ΔG°rxn(T), K_eq(T) with a propagated uncertainty band and a
per-species provenance tier — and, where a tier admits it, k_f(T)/k_r(T) with a mandatory honesty
label — exportable as Cantera YAML and flat JSON.

**Scope (M1–M3).** Species resolver cascade T0–T4, reaction arithmetic, phase corrections,
uncertainty machinery, curated kinetics library, Blowers–Masel estimator, Cantera/JSON export,
validation suite. **Optional M4:** ML-potential tier (AIMNet2) and automated-TST job tickets
(autodE).

**Non-goals (all milestones).**
- No heterogeneous-catalysis *estimation* — catalytic entries exist only as cited literature
  records (K0). The tool refuses to invent LHHW parameters.
- No runtime web access, no scraping. NIST WebBook values enter via a documented curation
  workflow, not code.
- No GUI. CLI + importable API only.
- No electrolyte/aqueous-ion thermochemistry in v1 (ΔfH°(aq)/activity scales are a distinct
  workstream); `aqueous` phase tag is reserved but unimplemented.
- Kinetics output without a tier label must be structurally impossible (schema-enforced), not
  merely discouraged.
- v1 equilibrium in liquid phase is ideal-solution K_x with an explicit warning; an activity-model
  hook (γ-based) is designed in but not implemented.

---

## 2. Verified foundations (checked 2026-09-01)

| Fact | Consequence for the plan | Source |
| --- | --- | --- |
| Burcat DB is mirrored with a **machine-readable XML snapshot** (`BURCAT_THR.xml`) plus CHEMKIN-style `THERM.DAT`; ~3,000 species; NASA-7 with **high-T coefficient set listed first** (1000–6000 K), low-T second (200–1000 K) | WP2 parses the XML, not fixed-width text; the coefficient-order quirk gets an explicit test | respecth.elte.hu/burcat.php + READ.ME |
| `chemicals.reaction` already bundles multi-source formation data behind method selectors: `Hfg` (~8,700 chemicals; methods `ATCT_G`, `TRC`, `CRC`, `WEBBOOK`, `JANAF`, `YAWS`, `JOBACK`), `Hfl` (`ATCT_L`, `CRC`, `WEBBOOK`, `JANAF`), `Hfs`, `S0g` (~5,400), `S0l`, `S0s`, plus `Gibbs_formation`, `entropy_formation`, `Hf_basis_converter` | Tier T2 is one dependency, not a curation project; per-method provenance maps directly onto uncertainty tiers (ATcT methods rank T1-grade) | chemicals.readthedocs.io |
| pGrAdd (VlachosGroup/PythonGroupAdditivity) is **MIT licensed**, SMILES in → Benson groups → ΔfH/S°/Cp out | Normal dependency for T3; the license risk flagged in research is closed | GitHub repo |
| Cantera ships `Blowers-Masel` as a native reaction rate type | WP10's acceptance gate is numeric round-trip against `cantera` | Cantera docs / PMC 11106751 |
| RMG estimators are queryable interactively (SMILES) at rmg.mit.edu; local RMG-Py install is heavy (conda) | RMG stays an *optional, manual-capture* tier in M3; no hard dependency | rmg.mit.edu |

---

## 3. Architecture

```
rxnthermo/
├── core/
│   ├── units.py          # SI constants, R, P°; the ONLY conversion boundary
│   ├── species.py        # SpeciesRecord, Nasa7 (eval Cp/H/S), CpPoly fallback
│   ├── reaction.py       # ReactionInput parse (SMILES/id), RDKit balance check
│   └── errors.py         # UnbalancedReaction, UnresolvedSpecies, PhaseMismatch,
│                         #   TierRefused, KineticsUnavailable
├── resolve/
│   ├── base.py           # SpeciesProvider protocol: resolve(key, phase) -> SpeciesRecord|None
│   ├── local.py          # T0: curated SQLite (vendored, cited)
│   ├── burcat.py         # T1: Burcat XML snapshot import + lookup
│   ├── chemlib.py        # T2: chemicals.* lookups by CAS (per-method tier mapping)
│   ├── benson.py         # T3: pGrAdd wrapper
│   ├── joback.py         # T4: thermo.Joback wrapper (always flagged "rough")
│   └── cascade.py        # ordered cascade, first-hit-wins, tier + σ attach
├── calc/
│   ├── arithmetic.py     # ΔH/ΔS/ΔG/K_eq at T; Kp↔Kc; van 't Hoff fit; adiabatic ΔT
│   ├── phase.py          # ΔHvap(T) via data/Watson; gas↔liquid basis shifts
│   └── uncertainty.py    # tier→σ table, quadrature propagation, K bands
├── kinetics/
│   ├── records.py        # KineticsRecord (arrhenius | blowers_masel | lhhw)
│   ├── library.py        # curated YAML library loader + query
│   ├── blowers_masel.py  # K2 estimator: family table + BM formula
│   └── consistency.py    # k_r = k_f / K_c enforcement, detailed-balance checks
├── io/
│   ├── cantera_yaml.py   # species (NASA-7) + reactions export
│   ├── report.py         # JSON contract + human-readable table/markdown
│   └── manifest.py       # data provenance manifest reader/writer
├── data/                 # vendored: burcat snapshot, curated.sqlite, kinetics/*.yaml,
│                         #   PROVENANCE.md (source, URL, date, license note per file)
├── cli.py                # resolve | compute | kinetics | export | db | validate
└── tests/                # unit, golden, property-based, cantera round-trip
```

Dataflow: `ReactionInput → cascade.resolve(each species) → calc.arithmetic → (optional)
kinetics resolver → io.report/cantera_yaml`. Every stage is pure given its inputs; the only
stateful component is the local DB.

---

## 4. Data contracts

### 4.1 SpeciesRecord (canonical internal object, JSON-serializable)

```json
{
  "key": "LFQSCWFLJHTTHZ-UHFFFAOYSA-N",
  "id": "ethanol", "smiles": "CCO", "cas": "64-17-5",
  "formula": {"C": 2, "H": 6, "O": 1}, "charge": 0,
  "mw_kg_per_mol": 0.04607,
  "phase": "gas",
  "thermo": {
    "form": "nasa7",
    "t_ranges": [[200.0, 1000.0], [1000.0, 6000.0]],
    "coeffs": [[...7 low-T...], [...7 high-T...]],
    "hf298_J_per_mol": -234000.0,
    "s298_J_per_molK": 280.6
  },
  "hvap": {"t_ref_K": 351.4, "dhvap_ref_J_per_mol": 38560.0,
           "tc_K": 514.0, "watson_exp": 0.38},
  "provenance": {"tier": "T1", "source": "Burcat 2023-07-23 snapshot",
                 "method": null, "retrieved": "2026-09-01"},
  "sigma_hf_J_per_mol": 1500.0
}
```

Rules: `coeffs` are stored **low-T first** regardless of source ordering (Burcat quirk normalized
at import). `hf298`/`s298` are *derived* from the polynomial at import for NASA-7 records and
cross-checked against the source's stated values (mismatch > 1 kJ/mol → import error). For
GA/Joback records `form: "cp_poly"` holds `{cp_coeffs, t_range, hf298, s298}` explicitly.

### 4.2 Default tier → σ(ΔfH) mapping (configurable, single table in `uncertainty.py`)

| Tier | Source class | σ default |
| --- | --- | --- |
| T0 | curated, citation with stated uncertainty | as stated |
| T1 | Burcat/ATcT | 1.5 kJ/mol |
| T2 | `chemicals` ATcT methods | 2 kJ/mol |
| T2 | `chemicals` CRC/WebBook/JANAF/TRC/Yaws | 4 kJ/mol |
| T3 | Benson (pGrAdd) | 8 kJ/mol |
| T4 | Joback | 25 kJ/mol |
| T5 | ML potential (M4) | 8 kJ/mol |

σ(S°) analogous (2/4/8/20 J/mol/K). Propagation: `σ²(ΔX_rxn) = Σ νᵢ² σᵢ²`; the K band is
multiplicative, `[K·e^(−σ_ΔG/RT), K·e^(+σ_ΔG/RT)]`, reported alongside K.

### 4.3 KineticsRecord

```yaml
reaction: "CC(=O)OCC + [OH-] = CC(=O)[O-] + CCO"
rate_form: arrhenius          # arrhenius | blowers_masel | lhhw
A: 3.2e7                      # units derived from molecularity + basis (mol/m³)
n: 0.0
Ea_J_per_mol: 4.5e4
t_range_K: [273, 330]
phase: liquid
catalyst: null                # or {name, form, loading_basis}
tier: K0                      # K0 literature | K1 RMG | K2 BM-estimated | K3 computed
uncertainty: "factor 2"       # mandatory, schema-enforced
citation: "…author/year/DOI…" # mandatory for K0/K1/K3
```

Schema enforcement (WP9): the loader rejects records missing `tier`, `uncertainty`, or (for
K0/K1/K3) `citation`. K2 records are never persisted to the library — they are computed on demand
and watermarked in output.

### 4.4 JSON result contract (consumer-facing, stable from M1 on)

```json
{
  "reaction": {"canonical": "...", "delta_nu_gas": -1},
  "T_K": 298.15,
  "dH_J_per_mol":  {"value": -55000, "sigma": 4200, "tier_worst": "T2"},
  "dS_J_per_molK": {"value": -120.0, "sigma": 9.0,  "tier_worst": "T2"},
  "dG_J_per_mol":  {"value": -19200, "sigma": 5000, "tier_worst": "T2"},
  "K_eq": {"value": 2320.0, "band": [310.0, 17400.0], "basis": "activity, P0=1e5 Pa"},
  "van_t_hoff": {"A": ..., "B": ..., "C": ..., "t_range_K": [298, 373]},
  "species": [{"key": "...", "tier": "T1", "source": "..."}, ...],
  "kinetics": {"tier": "K2", "label": "ESTIMATED — order of magnitude", ...} ,
  "warnings": ["liquid K is ideal-solution K_x", ...]
}
```

---

## 5. Conventions and exact math (normative for implementation)

NASA-7 evaluation (per range, a₁…a₇):

```
Cp/R    = a1 + a2·T + a3·T² + a4·T³ + a5·T⁴
H/(R·T) = a1 + a2·T/2 + a3·T²/3 + a4·T³/4 + a5·T⁴/5 + a6/T
S/R     = a1·ln T + a2·T + a3·T²/2 + a4·T³/3 + a5·T⁴/4 + a7
```

`H(T)` is formation-referenced: **H(298.15 K) ≡ ΔfH°(298.15)** — this identity is both the datum
convention and a mandatory import test. Kirchhoff needs no separate code path; ΔH°rxn(T) =
Σν·Hᵢ(T) directly.

Equilibrium: `ΔG°rxn(T) = Σν·(Hᵢ(T) − T·Sᵢ(T))`; `K = exp(−ΔG°rxn/RT)` with gas activities
pᵢ/P°. Concentration form for rate work: `K_c = K · (P°/(R·T))^Δν_gas` (mol/m³ basis; Δν over
gas species only). Getting this sign/exponent wrong is the classic bug — dimensional tests in
WP11 target it explicitly.

van 't Hoff fit (reporting convenience): least-squares `ln K(T) ≈ A + B/T + C·ln T` over the
requested range; residual of fit reported.

Watson: `ΔHvap(T) = ΔHvap(T_ref) · ((Tc − T)/(Tc − T_ref))^0.38`.

Adiabatic temperature: solve `Σ n_out,i·Hᵢ(T_out) = Σ n_in,i·Hᵢ(T_in)` by bracketed bisection on
the NASA-7 enthalpies (never a linearized ΔT = −ΔH/ΣnCp shortcut; that becomes a golden-test
comparison case instead).

Blowers–Masel (K2), Cantera-compatible form, ΔH = ΔH°rxn(T), intrinsic barrier Ea⁰, bond-energy
parameter w (default per Cantera):

```
Ea(ΔH) = 0                                  if ΔH ≤ −4·Ea⁰
       = ΔH                                 if ΔH ≥ +4·Ea⁰
       = (w + ΔH/2)·(Vp − 2w + ΔH)² / (Vp² − 4w² + ΔH²)   otherwise,
         Vp = 2w·(w + Ea⁰)/(2w − Ea⁰)
```

Transcription is *not trusted*: WP10's acceptance gate is numeric equality (rel. 1e-10) against
`cantera`'s BlowersMasel rate evaluation across a ΔH sweep. Reverse rates always from
`k_r(T) = k_f(T)/K_c(T)` — never independently parameterized.

---

## 6. Work packages

Effort figures are focused implementation days, single developer. Every WP lists its **gate**;
a WP is done when its gate passes in CI, not before.

### Milestone M1 — deterministic thermo engine (WP0–WP6, ~5–7 d)

- **WP0 — scaffolding (0.5 d).** Repo, `pyproject` (+ extras `[benson]`, `[cantera]`, `[ml]`),
  `uv` lock, ruff + mypy (strict in `core/`), pytest with `golden`/`slow` markers, CI matrix
  (linux + windows, py3.11/3.12), no-network test guard. *Gate:* green empty-package CI.
- **WP1 — core objects (1 d).** `Nasa7` eval, `SpeciesRecord`, reaction parse + RDKit
  atom/charge balance, error taxonomy. *Gate:* golden Cp/H/S for O₂, H₂O, CH₄ at 298.15/500/1000 K
  vs Burcat-published values; unbalanced/charged inputs raise typed errors.
- **WP2 — Burcat import (1.5 d).** Parser for `BURCAT_THR.xml` snapshot (vendored with date);
  normalize coefficient order; write SQLite; alias table from Burcat CAS fields. *Gates:*
  (a) `H(298.15) − ΔfH°(298)` < 1 kJ/mol for **every** imported species; (b) Cp continuity at the
  1000 K junction < 0.5 J/mol/K for 99% of species (known-bad fits quarantined, listed);
  (c) ≥ 20 species cross-checked against ATcT within combined uncertainty.
- **WP3 — `chemicals` provider (1 d).** CAS resolution (RDKit InChIKey → `chemicals.identifiers`),
  `Hfg/Hfl/Hfs/S0g/S0l/S0s` with per-method tier mapping (§4.2); Cp(T) for T2-resolved species
  from `chemicals.heat_capacity` (TRC/Poling) or Joback-Cp clearly tiered. *Gate:* 20-species
  spot list resolves with correct tier tagging; ATcT-method values preferred when present.
- **WP4 — reaction arithmetic (1.5 d).** ΔH/ΔS/ΔG/K(T) over mixed record forms, K_c conversion,
  van 't Hoff fit, adiabatic T solver. *Gates (golden, tolerance = propagated σ):*
  CH₄ + 2O₂ → CO₂ + 2H₂O(g) ΔH°298 = −802.3 kJ/mol; N₂ + 3H₂ → 2NH₃ K(298) ≈ 5.8×10⁵ and
  ΔH°298 = −92.2 kJ/mol; water-gas shift K at 1000 K vs JANAF-derived value; ethanol + acetic
  acid liquid esterification K ≈ 4 (via T2 liquid data, ideal-solution warning asserted).
- **WP5 — phase machinery (1 d).** Per-species phase tags, ΔHvap(T) (data else Watson),
  `Hf_basis_converter` cross-check; the LHV/HHV pair as the canonical test. *Gate:* same methane
  combustion with H₂O(l) = −890.4 kJ/mol; gas-route-vs-liquid-route ΔfH discrepancy warning fires
  when > 2σ.
- **WP6 — uncertainty (0.5 d).** §4.2 table, quadrature, K bands, `tier_worst` in every result.
  *Gate:* property-based test — bands widen monotonically as any species is forced down-tier.

**M1 definition of done:** the §4.4 JSON contract is produced end-to-end for the five golden
reactions with correct values, bands, and tiers; CLI `compute` works; no estimation tiers yet.

### Milestone M2 — estimation fallbacks (WP7–WP8, ~3–5 d)

- **WP7 — Benson via pGrAdd (2–4 d).** Wrapper + group-coverage probe (which of the seed list
  §8.1 pGrAdd can decompose); per-scheme tier annotation; failure → fall through to Joback with
  warning. *Gate:* benchmark set of 30 organics with known ΔfH°g — Benson tier lands within
  ±10 kJ/mol for ≥ 80% of decomposable species; no silent fallthrough.
- **WP8 — Joback (0.5 d).** `thermo.Joback` wrapper (ΔfH, ΔfG → S° derived, Cp poly).
  *Gate:* same benchmark, documented MAE; every Joback-tier output carries the "rough" flag
  through to CLI/report rendering.

**M2 definition of done:** any balanced organic reaction over C/H/O/N/S/halogen species produces
a tiered answer or a typed refusal — never a silent wrong number.

### Milestone M3 — kinetics + export (WP9–WP15, ~7–10 d)

- **WP9 — curated kinetics library (2 d).** YAML schema §4.3 + strict loader; seed §8.2 entered
  **only** with pinned citations (chasing the exact sources is part of the WP). *Gate:* loader
  rejects fixture records missing tier/uncertainty/citation; ≥ 8 seed reactions load and evaluate.
- **WP10 — Blowers–Masel estimator (1.5 d).** Family table `{family, Ea⁰, w, A_typical, A_spread}`
  seeded for: H-abstraction, radical addition, unimolecular fission, pericyclic, acid-catalyzed
  esterification/hydrolysis, generic bimolecular exchange; A magnitudes from collision-theory
  ceilings (gas 10¹⁰–10¹¹ L/mol/s ≡ 10⁷–10⁸ m³/mol/s, unimolecular 10¹³±¹ s⁻¹, liquid diffusion
  cap ~10⁹–10¹⁰ L/mol/s). *Gates:* numeric round-trip vs Cantera BM rate (rel 1e-10); every K2
  output watermarked "ESTIMATED — order of magnitude".
- **WP11 — consistency + evaluation (1 d).** k(T) evaluator all forms; reverse from K_c;
  diffusion-limit cap warning. *Gates:* detailed balance `k_f/(k_r·K_c) − 1| < 1e-10` across a
  T sweep for every library entry; dimensional-analysis tests on Δν ≠ 0 reactions (the Kp/Kc
  exponent trap).
- **WP12 — RMG capture workflow (0.5 d, documentation + template).** No dependency: a documented
  procedure + record template for querying rmg.mit.edu and entering the result as tier K1 with
  version/date provenance. *Gate:* one worked example in the library.
- **WP13 — export (1.5 d).** Cantera YAML (NASA-7 species + arrhenius/BM reactions) and flat
  JSON. *Gate (the strongest end-to-end test in the plan):* `cantera.Solution(yaml).equilibrate("TP")`
  composition matches this tool's K_eq-predicted equilibrium within tolerance for two gas
  reactions (NH₃ synthesis, WGS) at two temperatures each.
- **WP14 — CLI polish (1 d).** `resolve`, `compute`, `kinetics`, `export`, `db import-burcat`,
  `db add-species`, `validate`; tier badges and warning banners in terminal output. *Gate:*
  scripted CLI session in CI (docs example == tested example).
- **WP15 — validation report + docs (1.5 d).** ACCURACY.md (research §7 table, now with measured
  numbers), CONVENTIONS.md (§5 of this plan), README quickstart, provenance manifest complete.
  *Gate:* validation notebook runs clean in CI (`slow` marker).

**M3 definition of done:** research-doc architecture fully realized through tier K2; a consumer
can go SMILES → Cantera YAML with defensible numbers and visible honesty labels.

### Milestone M4 — computed tiers (optional, WP16–WP17, ~5–8 d)

- **WP16 — AIMNet2 provider (2–3 d, extra `[ml]`).** Endpoint optimization (ASE), reaction
  electronic energy + RRHO thermal correction to ΔHrxn(298); documented approximation notes;
  tier T5. *Gate:* 10 gas-phase reactions with known ΔH°298 reproduced within ±10 kJ/mol.
- **WP17 — autodE job tickets (3–5 d, extra `[qc]`).** Generate/import: tool writes a job spec,
  user runs autodE where xtb/ORCA are installed, tool ingests the barrier as K3 with method
  metadata. *Gate:* one worked example end-to-end on a textbook reaction.

---

## 7. Test strategy (cross-cutting)

- **Golden species values** (Burcat/JANAF published tables): Cp/H/S at 298.15/500/1000/1500 K.
- **Structural invariants:** H(298)=ΔfH°; S(T) > 0; Cp junction continuity; monotone H(T).
- **Golden reactions:** the M1 five (§WP4/WP5) + HHV/LHV pair; tolerances tied to propagated σ,
  not hand-tuned epsilons.
- **Property-based (hypothesis):** random stoichiometric multiples leave K^(1/n) invariant;
  reversing a reaction inverts K and negates ΔH; tier downgrades only widen bands.
- **Round-trip:** Cantera equilibrate (WP13 gate); BM vs Cantera (WP10 gate).
- **No-network guard** in CI; vendored data only.
- **Windows + Linux CI** (RDKit/Cantera wheels cover both; this is why no conda-only dep is
  allowed outside optional extras).

---

## 8. Seed data

### 8.1 Species (M1 target ≈ 40; all resolvable at T1/T2)

H₂, O₂, N₂, H₂O(g,l), CO, CO₂, CH₄, C₂H₂, C₂H₄, C₂H₆, C₃H₆, C₃H₈, n-C₄H₁₀, i-C₄H₁₀,
isobutylene, 1,3-butadiene, benzene, toluene, ethylbenzene, styrene, cumene, phenol, methanol,
ethanol, ethylene glycol, formaldehyde, acetaldehyde, acetone, acetic acid, ethyl acetate,
dimethyl ether, MTBE, NH₃, NO, NO₂, N₂O, SO₂, SO₃, HCl, Cl₂, H₂S.

### 8.2 Kinetics library seeds (M3; citation pinning is part of WP9)

| Reaction | Form | Anchor to pin |
| --- | --- | --- |
| Ethyl acetate saponification (aq.) | 2nd-order Arrhenius | classic textbook data (k≈0.11 L/mol/s at 25 °C class) |
| AcOH + EtOH esterification, H₂SO₄ | pseudo-homogeneous | Smith-lineage kinetics papers |
| Water-gas shift, Fe₃O₄/Cr₂O₃ | power law | standard reactor-design texts |
| Methanol synthesis, Cu/ZnO/Al₂O₃ | LHHW | Graaf 1988 / Vanden Bussche–Froment 1996 |
| NH₃ synthesis, promoted Fe | Temkin–Pyzhev | Temkin lineage |
| MTBE synthesis, Amberlyst-15 | activity-based | Rehfinger–Hoffmann 1990 |
| N₂O₅ decomposition (gas) | 1st-order | classic gas-kinetics data |
| H₂ + I₂ ⇌ 2 HI | 2nd-order | Bodenstein lineage |
| Cyclopentadiene dimerization (liq.) | 2nd-order | well-tabulated liquid kinetics |
| Ethanol dehydration, γ-Al₂O₃ | power law | catalysis literature |
| SO₂ oxidation, V₂O₅ | rate expression | Eklund lineage |
| Ethylene hydration, H₃PO₄/SiO₂ | power law | process literature |

Entries without a pinned, checkable citation **do not ship** — the row above is a shopping list,
not data.

---

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Burcat XML quirks (fields missing, odd phases, duplicate species) | Import is snapshot-based with quarantine list + WP2 invariant gates; raw file vendored so parses are reproducible |
| Species identity confusion (name/CAS/isomer) | InChIKey canonical key; alias table; fuzzy matches require explicit `--accept-alias`; never match on bare names |
| Kp/Kc/standard-state sign errors | One `units.py` authority; §5 formulas normative; WP11 dimensional tests; Cantera equilibrate round-trip as external referee |
| Benson coverage gaps on seed list | WP7 coverage probe early; per-species fallthrough with warning, never silent |
| Kinetics overclaim | Schema-enforced tier+uncertainty; K2 never persisted; watermark strings asserted in CLI tests |
| `chemicals`/`thermo` API drift | Pinned versions in lockfile; provider layer isolates the API surface |
| RDKit/Cantera install weight on Windows | Both ship manylinux+win wheels (verified ecosystem norm); CI runs Windows to catch regressions |
| Scope creep toward activity models / electrolytes | Explicit non-goals; γ-hook designed (interface only) so the pressure has a release valve |

---

## 10. Timeline and sequencing

```
M1  WP0→WP1→WP2→WP3→WP4→WP5→WP6      ~5–7 d   deterministic ΔH/K engine
M2  WP7 ∥ WP8                         ~3–5 d   estimation tiers
M3  WP9→WP10→WP11 ; WP12 ∥ WP13→WP14→WP15   ~7–10 d  kinetics + export + validation
M4  WP16 ∥ WP17 (optional)            ~5–8 d   computed tiers
                                      ------
core total (M1–M3)                    ~15–22 focused days
```

Sequencing rules: nothing in M2/M3 starts before M1's definition of done (the golden five);
WP13's Cantera round-trip is the release gate for any public artifact of the tool.

## 11. Open questions (defaults active, none block M1)

1. Repo host/visibility and final name (default: private GitHub, `rxnthermo`, rename cheap
   pre-publish).
2. Should the flat-JSON contract additionally ship a fitted `ln K = A + B/T + C·ln T` +
   Arrhenius-only view for consumers that want three numbers per reaction (default: yes — it is
   already computed in WP4/WP11)?
3. Liquid-phase activity models (γ from NRTL/UNIFAC) as M5 (default: interface hook only in v1).
4. Chinese-language companion doc (default: English only; progressive-disclosure translation on
   request, matching the V3 doc pattern).
