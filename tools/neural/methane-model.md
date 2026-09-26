# Coupled methane column initialization

The operating point is a joint vector of feed composition, flow and temperature, pressure, reflux,
condenser condition, steam sources, withdrawals and per-stage heat duties. Wetness is an outcome of the
solved local water saturation and energy balances, not a function of condenser temperature alone.

## Architecture

1. Each expert checks the property revision, component axis, geometry, declared feature bounds and a
   correlated-input coverage guard. The guard uses a training-input projection and distances to training
   examples; validation inputs set its margins. Test inputs do not fit it.
2. Separate dry and wet networks predict the complete physical initial state: temperatures, hydrocarbon
   liquid/vapor component flows, free-water flows and a wet mask. Their profiles are never blended.
   Both receive the complete coupled feature vector.
3. The bundle evaluates material, energy, equilibrium and water-saturation residuals at each proposed
   state and ranks candidates. This uses actual pressure, feed enthalpy, reflux, steam and duty
   distributions. A regression verifies different phase choices at the **same condenser temperature**
   when pumparound duty changes.
4. Native correction tries ranked candidates within one shared time allowance. For a wet candidate,
   a bounded warm pass holds predicted water flows fixed to resolve the energy profile before releasing
   the weakly determined water coordinates. The final requested problem includes every wet saturation
   equation and water unknown, normal trace support, physical audits and the final Newton certificate.
5. A failed neural phase candidate can be followed by another neural candidate. A qualified equilibrium
   result is preferred to an accepted dry supersaturation advisory. Only the selected final profile is
   observable. Classical backup starts cleanly, once, only when the mode allows it.

This is a hybrid initializer, not a replacement thermodynamic solver or a newly fitted equation of state.
The separation of phase handling, state prediction and rigorous correction is informed by
[PTFlash (Qu et al., 2022)](https://arxiv.org/abs/2205.03090), which uses distinct learned tasks for phase
stability and initial distribution coefficients. Here that principle is applied to column profiles with
V3's own equations and certificates.

Each methane network has 196 inputs, one 64-unit tanh hidden layer and a linear output layer. A 16-coordinate
PCA target representation, fitted only to training labels, is folded into the output weights. The final
1806 outputs describe 42 nodes on the 20-component basis. Flows use normalized `log1p` encoding. This
fixed-geometry baseline does not claim graph-network or variable-stage generalization.

## Sampling and qualification

| Quantity | Broad operating design | Wet-boundary design |
|---|---|---|
| Methane | 0.3–0.7 mol% of hydrocarbons | Same |
| Feed temperature | 633.15–643.15 K | Same |
| Feed flow | 98–102% of 737.6996333 mol/s | 737.6996333 mol/s |
| Top pressure | 237.5–262.5 kPa | 250 kPa |
| Organic reflux ratio | 4.02–4.32 | Same |
| Sump steam | ±3% of 333.3333333 mol/s | Same |
| Top pumparound | 80–100% of 12.84 MW removed | Same |
| Other pumparounds | Original 17.89/11.20 MW removed | Same |
| Side draws | ±1% of original rates | Original rates |
| Remaining composition | Light/heavy tilt ±2%, renormalized | Original relative ratios |
| Condenser temperature | 40–75°C | Refined inside 40–75°C for each coupled boundary |

The light group is ethane through TJL_PC02. Other hydrocarbons form the heavy group. Methane and the
light/heavy tilt vary independently; total flow is preserved after renormalization. Geometry, feed stage,
steam temperature and zero external reboiler duty remain fixed. These are bounded operating experiments,
not new measured crude assays.

The broad design uses independently stratified Latin-hypercube samples within each methane group.
There are 225 attempted columns. Training contains **80 qualified dry profiles**; 12 qualified validation
labels select the network. All 32 held-out inputs are evaluated, including failed teacher attempts and
accepted supersaturation advisories. The 88 accepted but supersaturated broad-design states are recorded
and excluded from fitting the new models.

The wet design jointly varies methane, feed temperature, reflux, steam and top pumparound duty. An offline
inverse continuation varies condenser temperature at each operating point with an auxiliary 1 mol/s
tray-one water flow. Gradual temperature stepping brackets saturation, followed by refinement. The water
coordinate is then released and the full native wet system is solved and certified. Only that final
profile can become a label. This provides **36 wet training profiles**, 8 validation profiles, and 7
qualified profiles among 8 held-out attempts. Four training attempts and one test attempt failed and have
no solution targets.

Methane groups 0.3/0.4/0.5/0.6/0.7 mol% train; 0.45 mol% validates; 0.55 mol% is held out. Related trays are
never independent samples. Normalization, target PCA, network weights and input correlations use training
data only. Validation selects epochs and coverage margins. Test sets are used after those choices freeze.

Wet labels describe one certified boundary branch with one wet tray. The auxiliary 1 mol/s value
parameterizes that branch during data generation; it is not a runtime specification and is not held fixed
in the final certificate. Circulating water is weakly identifiable near this boundary: for the original
energy conditions, certified 0.1, 1 and 10 mol/s water profiles occupy condenser temperatures within a few
microkelvin around 60.8039°C. Changing coupled energy inputs moves that boundary by many degrees. These
data establish a usable wet initial-guess branch, not a uniquely identifiable water flow or coverage of
arbitrary wet zones.

## Validation and limitations

[methane-model-card.json](methane-model-card.json) contains hashes, counts, repeated held-out acceptance,
median/p95 timings, water classifications, fallback counts and product differences. The benchmark warms
the JVM, rotates all three modes and repeats each input twice. Repetitions are not independent operating
cases. No performance threshold is asserted in CI.

The networks use the registered properties and assumptions in [methane-qualification.md](methane-qualification.md),
including assumed zero methane binary interactions. V3's reduced immiscible-water model and existing
dry-supersaturation advisory policy remain. Runtime accepted advisories are distinct from qualified
equilibrium results. The existing 59°C methane preset still carries that advisory; lowering its condenser
to 40°C increases supersaturation. Those cases were not relabelled as wet training examples.

## Reproduction

Use fresh output directories: generators refuse to overwrite journals. These commands do not replace
deployed artifacts automatically. Python needs NumPy.

```powershell
.\gradlew.bat generateMethaneNeuralData '-PmethaneNeuralOutput=build/neural-methane/reproduced-dry'
python tools/neural/train_mvp.py build/neural-methane/reproduced-dry --epochs 15000 --model-id tjl20-multivariable-dry-v1 --hidden-width 64 --pca-rank 16 --label-policy equilibrium-only --design-bounds --coverage-guard
.\gradlew.bat generateMethaneWetData '-PmethaneNeuralOutput=build/neural-methane/reproduced-wet'
python tools/neural/train_mvp.py build/neural-methane/reproduced-wet --epochs 15000 --model-id tjl20-multivariable-wet-v1 --hidden-width 64 --pca-rank 16 --label-policy equilibrium-only --design-bounds --coverage-guard
.\gradlew.bat evaluateMethaneNeural '-PmethaneNeuralOutput=build/neural-methane/reproduced-dry'
.\gradlew.bat evaluateMethaneNeural '-PmethaneNeuralOutput=build/neural-methane/reproduced-wet'
```

Wet generation can run independent `-PwetGroupFrom` (inclusive) and `-PwetGroupTo` (exclusive) ranges out of
seven groups. Merge separate directories with `merge_neural_journals.py`; it checks design consistency and
duplicate IDs. The original qualified wet study used ranges 0–4 and 4–7.

Evaluation loads the directory's model by default. `-PmethaneEvaluationModel=bundled` evaluates the deployed
bundle. `-PmethaneEvaluationSplit=validation` selects validation instead of test.
`-PmethaneEvaluationRepeats` defaults to two. Run `summarize_methane.py` with dry and wet directories to
produce the combined card after their bundle evaluations.

`qualifyNeuralWetStates` provides exploratory `-PwetMode=sweep`, `grid`, `wet-start` and `boundary` diagnostics.
Quote decimal parameters in PowerShell, for example `'-PwetCondenserKelvin=313.15'`. Offline tooling is
excluded from the mod JAR. Weights remain immutable bundled resources.
