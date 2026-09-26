# coolprop-parity

Purpose: generate the CoolProp 8.0.0 parity fixtures for the Java Helmholtz reference oracle (a test-only port of
CoolProp's pure-fluid reference equations, `src/test/java/com/wormzjl/createcheme/science/thermo/reference`).

Batch: `2026-09-24-coolprop-low-temperature` (unified multiphase thermo plan, P1 item 1, gate G1, decision D4).

Output: `src/test/resources/science/thermo/coolprop/parity-<Fluid>.json` for Nitrogen, CarbonDioxide, Methane, Ethane,
Hydrogen and Water. The fluid files beside them (`<Fluid>.json`, `LICENSE`) are fetched from CoolProp revision
`ae81610e7d23efc57f9d051c8e70a4d66e87537f`, not generated; see the README in that directory.

## Environment

The throw-away `uv` environment of the batch (CoolProp 8.0.0, numpy):

    uv venv "$TEMP/coolprop-probe-venv" --python 3.12
    uv pip install --python "$TEMP/coolprop-probe-venv/Scripts/python.exe" CoolProp numpy

Check: `"$TEMP/coolprop-probe-venv/Scripts/python.exe" -c "import CoolProp; print(CoolProp.__version__, CoolProp.__gitrevision__)"`
prints `8.0.0 ae81610e7d23efc57f9d051c8e70a4d66e87537f`.

## Run

From the worktree root (Git Bash), about one second:

    "$TEMP/coolprop-probe-venv/Scripts/python.exe" tools/coolprop-parity/generate_parity.py src/test/resources/science/thermo/coolprop

The output is deterministic for a given CoolProp build; it is written with LF line endings.

## What each fixture holds

- `single_phase`: 11 temperatures (five from Tmin + 1 K to 0.95 Tc, six from 1.05 Tc to min(Tmax, 1000 K)) at 0.01,
  0.1, 1, 2, 5, 10 and 20 MPa, plus the dense and supercritical states of
  `research/2026-09-24-coolprop-low-temperature/pr78-vs-coolprop/probe.py`. Per state: CoolProp's phase string and its
  PT-flash Dmolar, Hmolar, Smolar, Cpmolar, Cvmolar and fugacity coefficient
  (`AbstractState.fugacity_coefficient(0)`; PropsSI has no keyed output for it), each value cross-checked against
  `PropsSI`; and `eos_at_Dmolar`, the same properties and the pressure from a `DmolarT_INPUTS` update at the density
  the flash reported. The two differ by up to 3.3e-6 (cp, ethane 0.08 K above Tc): CoolProp's PT flash does not
  return exactly its EOS at its own density.
- `saturation`: seven temperatures from Ttriple to 0.99 Tc plus the probe's saturation temperatures. Psat, rhoL,
  rhoV, hL, hV, sL, sV from the default QT flash (superancillaries on); `pV_eos`, CoolProp's EOS pressure at its
  saturated-vapour density; and, for diagnosis only, the iterative flash (`ENABLE_SUPERANCILLARIES` off). The
  superancillary Psat differs from `pV_eos` by up to 7.3e-8 at ethane's triple point.
- `refused`: grid states CoolProp refuses (below the melting line at Tmin + 1 K), with its message.
