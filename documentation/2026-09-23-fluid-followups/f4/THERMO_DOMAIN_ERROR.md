# The thermo-domain error (fluid network)

Batch `2026-09-23-fluid-followups`, package F4 (2026-09-24). Standalone reference for players' reports, agents and anyone adding material data. The review of the change is `FLUID_PUMP_AND_THERMO_DOMAIN_REVIEW.md`.

## What it is

A state the fluid network's property package cannot evaluate: the state carries a component (any amount of it, in any phase, a trace included) at a temperature or pressure outside the range that component's data declares, or the state is outside the package's own envelope. The network refuses such a state with `com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation`, a subclass of `IllegalArgumentException`, so every refusal path that already treated "outside the property domain" as a refused trial still does. Malformed input (a wrong array length, a non-finite number) stays a plain `IllegalArgumentException`, so the two are never confused.

## The rule

The package range is the outer envelope. Each component's range must lie inside it (the catalog refuses the package otherwise). A state is valid only inside the envelope and inside the range of every component it carries. Water's range runs from the water model's triple point (273.16 K; ice is not modelled) to its enthalpy correlation's limit, with the envelope's pressure range. When several carried components are out of range the error names the one the state holds most of, temperature before pressure.

"Carried" means any positive amount. Every amount a state carries is evaluated by every correlation of its phase (the translated PR78 with its ideal-gas heat capacity, the viscosity tables, the water correlations), and those are only as good as the range they were fitted or sampled over; a trace is no exception (the heaviest crude fraction in liquid nitrogen at 80 K has a fugacity ratio near e^-950).

## Fields

| field | meaning |
|---|---|
| `packageId()` | the property package, e.g. `createcheme:tjl20_methane_nitrogen` |
| `component()` | a basis component id (`Nitrogen`, `crude_pc03`, `Water`), or `package` for the envelope |
| `property()` | `TEMPERATURE` (K) or `PRESSURE` (Pa) |
| `value()` | the offending value |
| `minimum()`, `maximum()`, `range()` | the valid range, as the data states it (`63.151..900 K`) |
| `code()` | stable, machine-readable: `THERMO_DOMAIN_TEMPERATURE_BELOW`, `THERMO_DOMAIN_TEMPERATURE_ABOVE`, `THERMO_DOMAIN_PRESSURE_BELOW`, `THERMO_DOMAIN_PRESSURE_ABOVE` |
| `node()` | the network node whose state was refused, or `NO_NODE` (`Long.MIN_VALUE`) where the property evaluation does not know it; the solver attaches it with `at(node)` |

Message: `Thermo domain: Nitrogen at 50.00 K is below its valid range 63.151..900 K (package createcheme:tjl20_methane_nitrogen)[ at node N]; the state cannot be evaluated`. A value within rounding of its bound is printed with the digits that show it crossed (`298.0499 K`, or every digit for a Newton trial that only just crossed).

## Where it surfaces

| where | what you see |
|---|---|
| Exception | `ThermoDomainViolation` from `FluidThermodynamics.state`, `adoptingState`, `solidState`, `flashTP`, `saturationPressure`, `vaporWaterEnthalpy`, `waterLiquid`, `HydrocarbonModel.phase`, `WaterRegion1.evaluate` (liquid water below its triple point, above 623.15 K or below its saturation pressure), `FluidDomain.check*` |
| Newton trial | refused as before (a smaller step may stay inside); `SparseNewton.Nonconvergence.domainViolation()` names it when the failing iteration's line search was stopped by it |
| Substep rejection | counted in `PassiveIntervalSolver.Result.rejectionReasons()` under its own key, one per component, property and side, never folded into `Other`: `thermo-domain: Nitrogen temperature < 63.151 K` |
| Failed interval | the interval's `Nonconvergence` carries the violation when its last rejection, or more than half of them, were domain ones; the worker's `FluidIslandSolveResult.domain()` carries it to the coordinator |
| Island status | `HELD (thermo domain): Nitrogen at 60.12 K is below 63.151 K in reservoir at 3, 64, 0 (valid 63.151..900 K, package createcheme:tjl20_methane_nitrogen)`; after the reproduced retry it ends `; the retry failed the same way, so the island waits for a change to its network`. Shown on the device screen and the block-entity view through the ordinary 100-tick presentation buckets (no new push). |
| Server log | one `WARN` per island per hold: `fluid_island=<id> dimension=<dim> node=<id> device=<kind at x, y, z> status=THERMO_DOMAIN code=... package=... component=... property=... value=... range=... waits_for_inputs=... detail=<message> action=Extend the component's validity range in its data file only with data validated for it; see documentation/fluid-followups/THERMO_DOMAIN_ERROR.md ...`; the same island and violation are logged again at most once per 6,000 online ticks. The generic `status=HELD` line is not written for these holds. |
| Retry policy | the first domain failure is held like any failure (fresh solver, halved slice, a cadence later); if that retry fails on the same component, property, side and node, the island waits with no deadline and dispatches nothing until an input changes (a fence: a queued edit or module drive, a released or resolved fence, a property resume; a topology or control edit makes a new island). Counter `domainHolds`. |
| Certificates | an interval with a `thermo-domain:` key is not replayed (`IslandCertificate.transitionFree`, refusal "a solid transport transition or a thermo-domain rejection") |
| Device edit | a generator, void or valve setting outside the domain is refused by the server before anything is queued; the reply with the next bucket reads `Not applied: Thermo domain: ...` |
| World start | `initialNitrogenTemperatureKelvin` / `initialNitrogenPressurePascal` outside the network package's domain stop the world with `Fluid config ...: Thermo domain: ...` |

## How a domain is declared (data only)

Property record (`data/createcheme/materials/properties/<id>.json`), beside the record's own `temperature_min_kelvin`/`temperature_max_kelvin` (which are the column's):

```json
"fluid_domain": {
  "temperature_min_kelvin": 63.151,
  "temperature_max_kelvin": 900,
  "pressure_min_pascal": 100,
  "pressure_max_pascal": 2000000,
  "evidence": "what validates this range (sources, measured deviations)"
}
```

Package record (`data/createcheme/materials/packages/<id>.json`): the same object is the envelope. A package without one cannot be built into a fluid model; the configured network package must have one.

Water: the water model's `triple_point` and `max_enthalpy_temperature` (`data/createcheme/materials/water/water.json`).

Checked when the catalog loads: every component of a package with an envelope declares a `fluid_domain` lying inside it, and so does the water model's range; a component's `fluid_domain` temperature range is covered by its vapour viscosity table; a record with a low-temperature heat-capacity segment may not claim a range below its own `temperature_min_kelvin`. The network's thermodynamic revision (`MaterialCatalog.fluidThermoFingerprint`) hashes every `fluid_domain`, so a changed domain is a changed model (a fresh world, by the owner's rule). The column's physics fingerprints and neural pins do not include it.

A component whose main ideal-gas fit is not valid low enough gets a second segment in its property record:

```json
"ideal_gas_cp": {
  "type": "shifted_polynomial_5", "reference_kelvin": 298.15, "coefficients": [...],
  "below": {"temperature_kelvin": 273.16, "coefficients": [six, in powers of (T - 298.15 K)], "source": "..."}
}
```

The enthalpy below the joint is continued from the main fit's value at the joint, so it is continuous by construction; fit the segment with its heat capacity equal to the main fit's at the joint so the enthalpy has no kink (`documentation/fluid-followups/f4-logs/probes/fit-nitrogen-cp-low.js`).

## Adding a component with its own range (oxygen, argon)

No code change. Add, per component: `components/<id>.json` (name, appearance); `properties/<id>.json` with the PR78 constants, the ideal-gas Cp (with a `below` segment if its fit starts above its range), `temperature_min_kelvin`, liquid and vapour viscosity tables covering its range, and its `fluid_domain`; a `liquid_volume_reference_points` entry in `transport/liquid_calibration.json` if its liquid volume should be anchored (otherwise its standard liquid density is used); a `conditional_solute_log_tables` curve in `transport/dissolved_viscosity.json` if it can dissolve in a crude liquid above its own liquid viscosity table (nitrogen has one, 273.16..600 K; without it such a liquid is refused as `PROPERTY_UNAVAILABLE`, a transport error, not this one); its id in `bases/network.json` (components and properties); the file paths in `src/main/resources/materials-index.json`; and, if its minimum is below the package envelope's (oxygen 54.36 K, below nitrogen's 63.151 K), the lower minimum in the network package's `fluid_domain`. Missing binary interactions default to zero (`missing_interactions: zero`); add fitted pairs to `interactions/tjl20.json` when they matter. Then validate it the way nitrogen is validated (`FluidNitrogenCryogenicTest`): saturation pressure and saturated liquid density against NIST, vapour Cp, and enthalpy continuity at any joint.

## What not to do

* Do not widen a `fluid_domain`, or a package envelope, without data validated over the new range. Every correlation of the component is evaluated there - its heat capacity fit, its PR78 constants and volume translation, its viscosity tables - and a range is a statement that they hold. The crude pseudo-components' fits are validated over 298.15..900 K (package evidence `THERMAL_FIT`); their 293.15 K minimum is the bundled ambient continuation, qualified with the viscosity tables sampled from 293.15 K.
* Do not override a fit (a data pack changing `ideal_gas_cp` or the PR78 constants) and keep a `fluid_domain` the new fit was not validated for: the network enforces what the record declares.
* Do not catch `ThermoDomainViolation` to retry the same state: it is deterministic. Change the inputs (the island's controls or topology) or the data.
* Do not report a domain failure as a generic Newton failure in new solver code: carry it (`Nonconvergence(message, lastVariables, violation)`) so the rejection key and the status stay the domain's.
