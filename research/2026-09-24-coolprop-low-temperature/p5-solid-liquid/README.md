# p5-solid-liquid

P5 of batch `2026-09-24-coolprop-low-temperature` (2026-09-25): solid-liquid and three-phase competition with the CO2-I
crystal. Stage document: `documentation/2026-09-24-coolprop-low-temperature/P5_SOLID_LIQUID_EQUILIBRIUM.md`. Tools and
probes: `tools/p5-solid-liquid-scans/`.

- `output-*.txt`: the printed output (JUnit system-out) of the stage's gate tests from the final full run at `4929e0f`:
  - the G5 families `G5F1` to `G5F6`;
  - `G4F1` and `G4F2` (the former holds);
  - `G3F7`, `SpineNetworkPathTest`, `GasSolidEquilibriumTest`;
  - `CrystalDepositionIslandTest`, `FluidCheckpointCodecTest`, `CrystalIslandRuntimeTest`.
- `output-probe-P5FusionEnthalpyProbe.txt`: the D16 revisit, every family at 9019 and 8875 J/mol, with a SUMMARY table
  at the end.
- `output-probe-P5LiquidFullVesselProbe.txt`: liquid-full vessels cooled to and through their bubble point (pure CH4
  passes; mixtures with CO2, C2H6 or N2 fail at the bubble point).
- `logs/`: every Gradle log of the stage (one invocation at a time, `JAVA_OPTS=-Xshare:off`, `--offline`), with
  `gate-counts.txt` (the four JUnit gates' class and test counts, the regression and GameTest results) and the exact
  solver regression report. Gradle does not stream test output to these logs; the outputs above are the record.
