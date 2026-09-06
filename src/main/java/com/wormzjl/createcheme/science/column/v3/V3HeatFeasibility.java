package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.Locale;
import java.util.Objects;

/**
 * Necessary conditions for a prescribed cooling duty, evaluated before or between solves.
 *
 * <p>None of these bounds is sufficient. They exist so that an impossible authored duty becomes a typed
 * {@code INFEASIBLE_SPECIFICATION} instead of an unexplained nonconvergence.</p>
 */
final class V3HeatFeasibility {
    private V3HeatFeasibility() {}

    /**
     * Static admission bound: the total authored cooling cannot exceed the enthalpy available between the
     * authored feed and a fully condensed product at the condenser outlet temperature, plus the reboiler and
     * steam enthalpy fed to the column.
     *
     * <p>The feed composition stands in for the products, so the bound is deliberately loose.</p>
     */
    static double availableCoolingWatts(V3ColumnInput input, V3ThermoModel thermo) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(thermo, "thermo");
        double condenserTemperature = specification(input, V3ColumnSpecification.CondenserOutletTemperature.class).kelvin();
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        double totalFeed = 0.0;
        for (double flow : feed) totalFeed += flow;
        double[] composition = new double[feed.length];
        for (int component = 0; component < feed.length; component++) composition[component] = feed[component] / totalFeed;
        double feedPressure = input.topPressurePascal()
                + (input.feedStageNumber() - 1) * input.stagePressureDropPascal();
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        V3FlashResult feedFlash = thermo.flashTP(input.feedTemperatureKelvin(), feedPressure, feed, workspace);
        double coldLiquid = thermo.molarEnthalpy(condenserTemperature, input.topPressurePascal(), composition,
                V3Phase.LIQUID, workspace);
        double available = totalFeed * (feedFlash.referenceMolarEnthalpyJoulesPerMol() - coldLiquid)
                + specification(input, V3ColumnSpecification.ReboilerDuty.class).watts();
        for (V3SteamFeedSpec steam : input.steamFeeds()) {
            available += steam.molarFlowMolPerSecond() * (V3WaterProperties.vaporMolarEnthalpy(steam.temperatureKelvin())
                    - V3WaterProperties.liquidMolarEnthalpy(Math.max(V3WaterProperties.TRIPLE_POINT_KELVIN,
                            Math.min(condenserTemperature, V3WaterProperties.CRITICAL_TEMPERATURE_KELVIN - 1.0))));
        }
        return available;
    }

    /** Human-readable static-admission diagnostic, in megawatts. */
    static String staticAdmissionDetail(double requestedCoolingWatts, double availableWatts) {
        return String.format(Locale.ROOT,
                "V3 authored pumparound cooling of %.4g MW exceeds the %.4g MW made available by the feed, "
                        + "reboiler duty and steam at the condenser outlet temperature",
                requestedCoolingWatts / 1.0e6, availableWatts / 1.0e6);
    }

    /**
     * Enthalpy that tray {@code tray} could release by totally condensing the vapor arriving from below.
     *
     * <p>Evaluated on the last accepted continuation state, whose vapor traffic is larger than the cooled
     * state's, so this cap is permissive.</p>
     */
    static double condensationCapacityWatts(
            V3ColumnProblem problem, V3DryMeshState state, V3ThermoModel thermo, V3ThermoWorkspace workspace, int tray) {
        int source = tray + 1;
        if (source > problem.topology().reboilerNode()) return 0.0;
        V3ActiveComponentBasis basis = problem.activeComponentBasis();
        double[] composition = new double[problem.input().componentBasis().componentCount()];
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            total += state.vaporFlow(source, component);
        }
        if (!(total > 0.0) || !Double.isFinite(total)) return 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            composition[basis.publicIndex(component)] = state.vaporFlow(source, component) / total;
        }
        double vapor = thermo.molarEnthalpy(state.temperatureKelvin(source), problem.nodePressurePascal(source),
                composition, V3Phase.VAPOR, workspace);
        double liquid = thermo.molarEnthalpy(state.temperatureKelvin(tray), problem.nodePressurePascal(tray),
                composition, V3Phase.LIQUID, workspace);
        double capacity = total * (vapor - liquid);
        if (problem.hasSteamFeeds()) {
            double water = problem.waterVaporFlowMolPerSecond(source);
            if (water > 0.0) {
                capacity += water * (V3WaterProperties.vaporMolarEnthalpy(state.temperatureKelvin(source))
                        - V3WaterProperties.vaporMolarEnthalpy(state.temperatureKelvin(tray)));
            }
        }
        return Double.isFinite(capacity) && capacity > 0.0 ? capacity : 0.0;
    }

    static String condensationCapDetail(int tray, double capacityWatts, double dutyWatts) {
        return String.format(Locale.ROOT,
                "V3 pumparound cooling of %.4g MW on tray %d exceeds the %.4g MW its arriving vapor can release "
                        + "even at the smallest permitted continuation increment",
                Math.abs(dutyWatts) / 1.0e6, tray, capacityWatts / 1.0e6);
    }

    static String condenserBoundDetail(double requestedCoolingWatts, double baseCondenserDutyWatts) {
        return String.format(Locale.ROOT,
                "V3 authored pumparound cooling of %.4g MW is not below the %.4g MW base condenser duty Q_cond0 "
                        + "of the same column without stage heat; the overhead would vanish",
                requestedCoolingWatts / 1.0e6, Math.abs(baseCondenserDutyWatts) / 1.0e6);
    }

    private static <S extends V3ColumnSpecification> S specification(V3ColumnInput input, Class<S> type) {
        return input.specifications().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 input is missing " + type.getSimpleName()));
    }
}
