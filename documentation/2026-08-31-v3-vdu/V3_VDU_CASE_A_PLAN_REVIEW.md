# VDU Case A plan review

Date: 2026-09-08. Plan reviewed: `V3_VDU_CASE_A_PLAN.md`, against current source at `e8d8937` and the primary Ji–Bagajewicz papers. This is a planning review; no VDU implementation, characterization, or solver experiment was performed. The plan itself is unchanged.

## Assessment

Keep Case A and the contract → property package → numerical qualification → game-surface sequence. Reusing the existing solver, preserving CDU behavior, keeping thermodynamic refits conditional, and analyzing stalls before changing numerical methods are sensible choices.

However, WP0 is not ready to freeze. The strongest issue is an inconsistent source-case identity. There are also concrete implementation gaps behind the statement that only audits are needed.

## 1. Reconcile the source case before characterizing its feed

The plan takes its yield row from the **conventional** column of Part I, Table 5, but its 7.20/7.33 MW pumparounds and temperature row from Part II, Table 1, explicitly titled **“Stream Data for Light Crude Distillation with Preflashing.”** These are different source scenarios. [Part I, Table 5](https://www.ou.edu/class/che-design/pub-papers/Design%20of%20Crude%20Distillation%20Plants%20with%20vacuum%20units-Targeting%20I(Ji-Bagajewicz)-02.pdf), [Part II, Table 1](https://www.ou.edu/class/che-design/pub-papers/Design%20of%20Crude%20Distillation%20Plants%20with%20vacuum%20units-HEN-II(Ji-Bagajewicz)-02.pdf).

Resolve the variant within the already adopted Case A. Retain the conventional basis only with matching thermal data, use a consistently sourced preflash reconstruction, or explicitly label any cross-case borrowing as an assumption. Do not silently combine them into a literature benchmark.

Recompute all dependent quantities together: residue volume, inferred crude cut point, steam basis, product cut assignments, and converted molar draw rates. Changing the variant after WP1 would invalidate much of the characterization work.

The plan's own conventional draw numbers imply `22.92 / (22.92 + 81.43) = 21.96%` LVGO by VGO volume, not 30%. The paper uses a D86 endpoint specification and reports optimized yields; its 30% footnote must not automatically become a second, exact product-split specification. [Part I, Vacuum Tower Specifications and Tables 1/5](https://www.ou.edu/class/che-design/pub-papers/Design%20of%20Crude%20Distillation%20Plants%20with%20vacuum%20units-Targeting%20I(Ji-Bagajewicz)-02.pdf).

## 2. Add a real zero-reflux/vapor-only work item

The resolver can construct a `VAPOR_ONLY` topology, but the public calculator does not currently reach it:

- `V3ColumnCalculator.java:184–192` selects/retries only `LIQUID_ONLY` and `TWO_PHASE`.
- `preferredCondenserBranch` has the same two-way choice.
- `V3CondenserPhaseTransition.java:57–61` rejects a vapor-only flash, even for zero organic reflux.
- The auditor currently has separate liquid/two-phase condenser checks; a vapor-only branch needs its own phase-validity review, not merely enabling branch selection.

Therefore, “the transition machinery exists” is insufficient. WP2 should explicitly cover selection, seed projection, transitions in both directions as appropriate, and acceptance for a zero-reflux top. Add a small manufactured zero-reflux case and an end-to-end test that actually publishes the vapor-only branch.

No entirely new topology class may be necessary. That conclusion should be proved by these cases, rather than equating an enum/resolver option with an operational calculation lane.

## 3. Correct the pressure/stage bookkeeping

`V3ColumnProblemResolver.pressureProfile` assigns the sump the same pressure as tray N. For the plan's top pressure 10.6 kPa, seven trays, and 0.5 kPa increment:

- Tray 6 = 13.1 kPa.
- Tray 7 and sump = 13.6 kPa, **not 14.1 kPa**.

The steam-injection validation uses the same convention. Pin the full node-pressure vector in WP0, or explicitly plan a VDU-specific sump-pressure convention without altering the CDU lane.

Also distinguish the paper's tray count from V3's additional equilibrium sump stage. Seven trays plus an equilibrium sump can be a reconstruction choice, but it should be identified as such. Include A4, the sharp residue cut, in the WP0 gate; it is more consequential than the small steam-temperature uncertainty.

## 4. Make the thermal approximations explicit

Three temperatures that the plan sometimes treats as interchangeable are different model quantities:

- **Feed-stream temperature versus mixed feed-stage temperature.** The energy equation mixes the feed enthalpy with adjacent phase traffic and steam. Setting the inlet stream to 382 °C does not enforce a 382 °C flash-zone tray. Declare the substitution as an approximation, or introduce a separately identified outer feed-enthalpy adjustment if matching the tray temperature is required. Do not later tune it silently while counting that temperature as an independent validation target.
- **PA cooler outlet versus equilibrium return tray.** The current `V3PumparoundSpec` supplies distributed heat only. It has no circulation flow and cannot uniquely calculate the physical cooler return temperature from duty alone. The available equilibrium tray temperature is not that missing cooler-outlet temperature. Limit the present audits to quantities the model actually represents.
- **Actual tower overhead versus node-0 reporting flash.** With zero reflux, prescribing node 0 at 127 °C does not impose that temperature on tray 1. Keeping the tray-1 comparison is correct. Label any additional downstream cooling/heating duty as a reconstruction choice, and keep its energy accounting separate from in-column PA heat. A small node-0 duty is a useful diagnostic, not evidence that the source specified this precondenser.

This can remain an effective heat-only VDU model, but it should not claim to reproduce the circulating PA hydraulics or cooler return temperatures.

## 5. Define volume conversions and independent qualification

For a cut assignment expressed as standard volumes, use componentwise conversion:

`n_dot = sum_i(V_dot_i,std * rho_i,std / MW_i)`

For a solved composition, under the explicitly chosen additive standard-volume convention:

`V_dot_std = sum_i(n_dot_i * MW_i / rho_i,std)`

Keep units consistent and state the reference temperature/pressure. In general, dividing a volume-weighted density by an independently volume-weighted arithmetic MW does not give the correct mixture molar flow.

The product-volume comparison remains useful: the solver can change product composition and thus volume per mole. However, once LVGO/HVGO molar draw rates are derived from the desired yields, matching their resulting volumes is primarily a conversion/composition-consistency check, not an independently predicted recovery.

WP3 should distinguish:

- Hard numerical/physical acceptance: unchanged MESH, conservation, phase checks, and fresh audit.
- Reconstruction qualification: independent temperatures, available product-quality information, overflash, and an energy comparison not fixed by the input construction.
- Prescribed or calibrated quantities: useful round-trip checks, but not independent evidence of separation accuracy.

Overflash needs a fully defined formula: hydrocarbon liquid flowing down across the boundary immediately above the feed, after any withdrawal there, converted to the same standard-volume basis as the feed denominator. Report molar flow separately if the enum is named `OVERFLASH_LIQUID_FLOW`. A hot operating volume divided by a standard feed volume would be a different ratio.

The plan calls some bands “reported only” while WP3 says yields must be inside band. Specify which comparisons gate a *literature-qualified preset* and which remain diagnostics. A numerically accepted state and a literature-qualified reconstruction should be distinguishable.

## 6. Expand the uncertainty checks before blaming PR78

The crude assay stops at 90 vol%, while the proposed residue cut retains 26.4% of the original crude. Therefore the unmeasured final 10% represents roughly `10/26.4 = 37.9%` of the proposed VDU feed by the plan's standard-volume bookkeeping.

Small K-values would limit those cuts' direct overhead recovery; they do not establish that their MW, density, Cp, or feed enthalpy has negligible influence on column temperatures and the residue-volume audit. Add bounded sensitivity cases for the tail endpoint, heavy-cut density/Cp treatment, and a non-sharp residue-cut alternative before attributing a mismatch to vapor-pressure bias.

Retain PR78 initially, but describe PR–Maxwell–Bonnell differences as **model spread**, not demonstrated error against this source's unknown property method. An independent PR implementation checks implementation consistency; it does not by itself validate the estimated petroleum properties.

The declared 500 Pa floor is broader than this approximately 10 kPa case validates. Either qualify the broader envelope with additional property/flash cases or initially limit the qualified operating range. Keep the optional two-parameter refit conditional on evidence, and validate enthalpy/density behavior as well as the two vapor-pressure anchors if it is pursued.

## 7. Tighten the execution details

- Gate the new continuation behavior on an explicit VDU capability/package policy, not just `P < 50 kPa`; otherwise a future low-pressure CDU case can silently take the new path despite the isolation promise.
- Define adaptive steps in log pressure, for example `P_mid = sqrt(P_accepted * P_failed)`. Specify exact target inclusion, total attempt/deadline budgets, retry limits, and restart from the last accepted state. The listed pressure nodes are an approximate ratio-based schedule, not a precise algorithm.
- Preserve the existing deterministic baseline tests at both normal and low CDU pressures. Avoid using timing assertions as correctness gates for the cold-start comparison.
- Enum additions do not automatically reach the client. `V3ColumnResult`/`V3ColumnDisplayResult` need an explicit plan for overflash and precondenser diagnostics if the GUI is expected to show them. `packageId` alone needs no new schema, but new transported fields might.
- A reproducible WP1 must check in the source assay, finite tail-end rule, cut mapping, scripts, manifest, generated package, and qualification fixtures despite the repository's ignored `scripts/` directory. State how missing component SG data is inferred without pretending bulk crude density uniquely determines the residue SG distribution.

## Recommended next step

Revise WP0 around a single source scenario and a provenance table, then add the zero-reflux/vapor-only proof to WP2 and strengthen WP3's independent validation and feed-uncertainty matrix. Keep the overall Case A choice and work-package order. Treat 6–10 days as provisional until the source reconstruction and top-boundary work are resolved.

## Maintainer requirement incorporated

The subsequent requirement for parameters viable in both CDU and VDU is now recorded in plan section 2.5 and the WP0/WP1/WP3/WP5 gates. One component definition uses one shared parameter revision, qualified across both regimes; vacuum-only refits are not accepted. This is feasible in principle, while the adequacy of the current estimated PR78 parameters remains to be demonstrated. Both plan copies were synchronized. The source-case and boundary-model findings above remain open.

