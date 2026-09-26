# Native CDU reference harness: qualified checks, full target unresolved

Date: 2026-09-06. This milestone adds a reproducible DWSIM reference harness and qualified small cases. **The full literature CDU is not qualified.** The next core solver/heat implementation can use manufactured regressions; full native/V3 benchmark claims remain gated on completing this reference.

The [shared contract](../../../src/test/resources/science/column/v3/tjl19-literature-cdu-v1.json) drives a native 41-contact absorption tower, with feed at source stage37, bottom steam at41, liquid draws at10/18/28 and cooling at8/16/26. Native indices are zero-based. An external isothermal native Vessel performs condensation/decantering at332.15K, followed by a native organic splitter and recycle connection. This avoids adding condenser/reboiler contacts or a fixed overhead-gas specification to the main tower. The builder verifies compiled stage/draw arrays and all frozen hydrocarbon binary interactions.

The implemented full-tower trials use an open reflux guess and initially apply native total-wet side rates. They are explicitly diagnostic. Closing the organic reflux loop and adjusting native wet side rates to the prescribed hydrocarbon rates remain unfinished. No saved full-target reference is presented as converged.

## Independent calorics and safe replay

`scripts/dwsim/CduReference.cs` installs the explicit enthalpy callback developed from native fugacity temperature derivatives plus native ideal-gas integrals. It uses the provided property-package context and verifies component order; it never captures one stream as the universal basis. Native ideal integrals are memoized at exact temperatures with a bounded cache and matching constant-property object references. No caloric approximation or installed-binary modification is introduced.

All20 components, including water, pass100 native TP-stable pure-phase derivative checks; maximum independent step-size difference is7.22e-5J/mol. Forced pure phases that native TP identifies as unstable are excluded explicitly rather than used to widen the error budget. Actual mixture states are additionally exercised by dry/wet column and condenser checks.

Water remains a model difference: native steam supply enthalpy at533.15K/450kPa differs from V3 W1 by **-64.9769kW at1200kmol/h**. The native inlet temperature after isenthalpic pressure reduction is calculated, not assumed unchanged. [Caloric and water measurements](../../../output/cdu-reference/evidence/caloric-check.json) retain both enthalpy conventions. Native oil contains dissolved water; this is not silently equated to W1's immiscible-water approximation.

The delivered `.cdu-reference.json` files are **checked-replay bundles, not directly openable DWSIM files**. They contain native XML, its checksum, the contract checksum, caloric revision, solver/sign metadata and hashes of the actually loaded thermodynamics and unit-operations DLLs. Version10.2.3.0 alone is insufficient to identify the solver build. Serialization changes only the XML copy's override flags, leaving live callbacks active. The loader writes temporary build scratch, validates metadata/hashes, loads it, reinstalls every caloric callback before calculation, and deletes the scratch file. It never ships a sibling uncorrected `.dwxml` as a qualified result.

Example replay from the repository root:

```powershell
./scripts/dwsim/run-cdu-reference.ps1 -Mode replay `
  -ReferencePath output/cdu-reference/evidence/wet-ns.cdu-reference.json `
  -OutputDirectory build/cdu-reference-replay/wet-ns -Seconds 60
```

Dry/wet NS and wet SR replays all pass fresh audits and temperature/flow/composition comparisons. NS maximum differences are below4e-13K,6e-14mol/s and5e-16 mole fraction. SR differences are3.35e-6K,1.15e-6mol/s and3.57e-8 mole fraction. The original characterization datasets remain unchanged.

## Native heat convention: solver-specific and experimentally checked

Merely assigning `Stage.Q` does not enter the native solver input. The column reads connected `InterExchanger` energy streams, in kW, as `Q = -EnergyStream.EnergyFlow`.

The two native solvers interpret that Q oppositely:

| Native solver | Positive solver Q means | EnergyStream value for physical -1kW cooling |
|---|---|---:|
| Naphtali-Sandholm | Heat added | +1kW |
| Sum Rates | Heat removed | -1kW |

The distinction is present in the official [NS residual](https://raw.githubusercontent.com/DanWBR/dwsim/windows/DWSIM.UnitOperations/UnitOperations/RigorousColumnSolvers/NewtonRaphson.vb) and [SR residual](https://raw.githubusercontent.com/DanWBR/dwsim/windows/DWSIM.UnitOperations/UnitOperations/RigorousColumnSolvers/SumRates.vb), and confirmed against the installed binaries. The harness keeps authored physical heat in its own dictionary; solver transport values never define the independent energy audit.

Controlled five-contact equilibrium columns pass zero and ±1kW tests. Correct SR translation has maximum global energy error7.14e-6kW; the deliberately wrong translation produces approximately -2/+2kW error for cooling/heating. [Negative-control evidence](../../../output/cdu-reference/evidence/smoke-sr-wrong-sign.json) preserves the distinction. Native nonlinear tolerances remain1e-10: the default scale can accept an unresolved1kW perturbation.

The native component-relative post-check uses10times that nonlinear tolerance. Some solved states fail its1e-9 component criterion despite much smaller independent weighted errors than our budget. Only that exact documented post-copy exception is considered for independent acceptance, with the native rejection retained separately. Other solver exceptions remain failures. Native `Calculate` is not labelled successful when its post-check rejected the state.

## Condenser and full-size evidence

The three-phase smoke uses native `NestedLoops3PV3`, stability severity2 and incipient-liquid stability checking. It produces positive gas/oil/aqueous streams and verifies the hydrocarbon reflux ratio4.17. Gas, oil and water flows are0.724353/10.738866/0.674477kg/s. Oil contains2.88427mol% water, while the aqueous phase is effectively pure water. Hydrocarbon mass error is3.98e-11 relative; water closure error is1.06e-10 relative.

A missing organic phase or a water-rich liquid on the nominal organic port is rejected before reflux. The current builder fails closed if native port roles differ; dynamic port remapping is not claimed implemented. Native Vessel `DeltaQ` supplies the calculated condenser duty; its connected energy stream was observed to remainzero. The duty is checked against material-stream energy, and is not treated as an independently prescribed specification. [VLL measurements](../../../output/cdu-reference/evidence/condenser-vll.json) contain the separate values.

Full-tower attempts are summarized in [machine-readable evidence](../../../output/cdu-reference/evidence/attempt-summary.json):

- Full-load NS reached its90s limit at152,907 enthalpy calls, roughly the cost of a dense numerical Jacobian. This is not evidence of infeasibility.
- Zero-heat/zero-draw SR oscillated through its iteration limit.
- NS with the installed robust initializer reached its240s bound without an accepted solution.
- A manufactured uniform-equilibrium41-contact seed passed. After correcting the SR sign translation, audited boundary-homotopy rungs0,0.02,0.05,0.1 and0.2 were accepted.
- Rung0.4 was correctly rejected: hydrocarbon mass error3.52e-9 and log-fugacity error9.99e-8 passed, but **global energy error was-494.888kW and maximum stage energy error6096.231kW**. Its [candidate state and fresh audits](../../../output/cdu-reference/evidence/scaffold-rejected-0.40.json) are retained.

The homotopy varies manufactured feed compositions, temperatures and crude loading. It is not a percent-complete literature CDU. Its acceptance budgets are explicitly intermediate: weighted stage/global mass1e-6, water closure1e-6, stage/global energy0.1kW, absolute component fugacity1e-5 and retained-component log fugacity1e-3. Positive finite phase flows, normalized nonnegative compositions and nonnegative draws are required. Final reference equilibrium tolerances must be tighter so native numerical error cannot dominate the measured property-package differences.

Only compact qualified evidence and selected rejected states are committed. Raw trial reports, including the8MB SR log, remain local. No V3 production code changes are included in this reference-harness milestone. The full reference gate remains open before step6 qualification or UI claims.
