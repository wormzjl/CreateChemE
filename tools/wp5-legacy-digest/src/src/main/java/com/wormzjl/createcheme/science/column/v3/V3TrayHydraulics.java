package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Locale;
import java.util.Objects;

/**
 * Flow-dependent sieve-tray pressure drop, evaluated on an accepted MESH state.
 *
 * <p>The column's own solved traffic is the information source: the vapour and liquid leaving each tray are set
 * by the material and energy balances and barely move when the pressure profile changes, so one march followed by
 * at most one warm correction closes the hydraulic loop (see {@code TRAY_PRESSURE_METHOD_REVIEW}). Nothing here
 * touches the solver: the profile is resolved into the problem before a solve and frozen for its length.</p>
 *
 * <p>The model is a sieve tray: an orifice dry drop, the Bennett, Agrawal and Cook (1983) clear-liquid height, a
 * bubble-formation residual and the Fair flooding fraction in the Lygeros-Magoulas fit. Every geometric parameter
 * except the column diameter is a fixed tray preset; the diameter is the single authored input.</p>
 */
public final class V3TrayHydraulics {
    /** Identifies the correlation and its frozen tray preset in the digest of a hydraulics-bearing request. */
    public static final String CORRELATION_REVISION = "v3-sieve-tray-bennett-r1";

    /** Fraction of the column cross-section taken by one downcomer; two of them bound the bubbling area. */
    static final double DOWNCOMER_AREA_FRACTION = 0.10;
    /** Perforated fraction of the bubbling area. */
    static final double HOLE_AREA_FRACTION = 0.10;
    static final double WEIR_HEIGHT_METRES = 0.05;
    /** Weir length as a fraction of the diameter, for a downcomer of the fraction above. */
    static final double WEIR_LENGTH_RATIO = 0.726;
    /** Liebson-type orifice coefficient for a 10 % perforated tray of thickness ratio about 0.4. */
    static final double ORIFICE_COEFFICIENT = 0.78;
    static final double HOLE_DIAMETER_METRES = 0.0127;
    static final double SURFACE_TENSION_NEWTON_PER_METRE = 0.02;
    static final double TRAY_SPACING_METRES = 0.6;

    private static final double GAS_CONSTANT_JOULE_PER_MOL_KELVIN = 8.314462618;
    private static final double STANDARD_GRAVITY = 9.80665;
    private static final double WATER_MOLECULAR_WEIGHT_KG_PER_MOL = 0.01801528;
    private static final double WATER_LIQUID_DENSITY_KG_PER_CUBIC_METRE = 1000.0;
    /** Datum of the registered standard liquid densities (60 F), used by the Rackett temperature scaling. */
    private static final double STANDARD_DENSITY_TEMPERATURE_KELVIN = 288.706;
    /** Fallbacks for a tray whose liquid vanished; they only keep the correlation finite on an empty tray. */
    private static final double EMPTY_TRAY_LIQUID_DENSITY = 700.0;
    private static final double EMPTY_TRAY_CRITICAL_TEMPERATURE = 700.0;
    private static final double EMPTY_TRAY_ACENTRIC_FACTOR = 0.5;
    /** The per-tray fixed point contracts at about {@code dry drop / P}; the cap is a guard, never a budget. */
    private static final int MARCH_ITERATIONS = 50;
    private static final double MARCH_TOLERANCE_PASCAL = 1.0e-4;

    private final double diameterMetres;

    private V3TrayHydraulics(double diameterMetres) {
        this.diameterMetres = diameterMetres;
    }

    /**
     * Sieve-tray hydraulics for one authored column diameter.
     *
     * @throws IllegalArgumentException if the diameter is not finite and positive
     */
    public static V3TrayHydraulics ofDiameter(double diameterMetres) {
        if (!Double.isFinite(diameterMetres) || diameterMetres <= 0.0) {
            throw new IllegalArgumentException("V3 tray hydraulics require a finite positive column diameter");
        }
        return new V3TrayHydraulics(diameterMetres);
    }

    public double diameterMetres() {
        return diameterMetres;
    }

    /** Total, bubbling and net areas, and the weir length, of the authored diameter. */
    double area() {
        return Math.PI * diameterMetres * diameterMetres / 4.0;
    }

    double bubblingArea() {
        return (1.0 - 2.0 * DOWNCOMER_AREA_FRACTION) * area();
    }

    double netArea() {
        return (1.0 - DOWNCOMER_AREA_FRACTION) * area();
    }

    double weirLengthMetres() {
        return WEIR_LENGTH_RATIO * diameterMetres;
    }

    /**
     * The traffic leaving one tray, in the units the correlation consumes.
     *
     * <p>Water is accounted exactly as the solver does: the vapour carries the authored steam fed at or below this
     * tray plus the free water that left the tray above, and the liquid carries this tray's own free water.</p>
     */
    public record Tray(
            double vaporMolarFlowMolPerSecond,
            double vaporMassFlowKgPerSecond,
            double temperatureKelvin,
            double liquidMassFlowKgPerSecond,
            double liquidStandardVolumeCubicMetresPerSecond,
            double liquidCriticalTemperatureKelvin,
            double liquidAcentricFactor,
            double freeWaterMolarFlowMolPerSecond) {}

    /** One tray's pressure-drop split and its fraction of the Fair flooding limit. */
    public record Terms(double dryPascal, double liquidPascal, double residualPascal, double floodFraction) {
        public double totalPascal() {
            return dryPascal + liquidPascal + residualPascal;
        }
    }

    /**
     * The pure-component constants the correlation reads, on the public component axis.
     *
     * <p>Held as plain arrays so the hydraulics have no property model of their own: the only thermodynamics
     * here is the Rackett scaling of a registered standard density and Kay's rule over registered criticals.</p>
     */
    public record ComponentConstants(
            double[] molecularWeightKgPerMol,
            double[] standardLiquidDensityKgPerCubicMetre,
            double[] criticalTemperatureKelvin,
            double[] acentricFactor) {
        public ComponentConstants {
            int count = Objects.requireNonNull(molecularWeightKgPerMol, "molecularWeightKgPerMol").length;
            if (count == 0
                    || Objects.requireNonNull(standardLiquidDensityKgPerCubicMetre, "density").length != count
                    || Objects.requireNonNull(criticalTemperatureKelvin, "criticalTemperature").length != count
                    || Objects.requireNonNull(acentricFactor, "acentricFactor").length != count) {
                throw new IllegalArgumentException("V3 tray hydraulics component constants disagree on the axis");
            }
        }

        public static ComponentConstants of(V3PengRobinsonThermo thermo, int componentCount) {
            double[] molecularWeight = new double[componentCount];
            double[] density = new double[componentCount];
            double[] criticalTemperature = new double[componentCount];
            double[] acentricFactor = new double[componentCount];
            for (int component = 0; component < componentCount; component++) {
                molecularWeight[component] = thermo.componentMolecularWeightKgPerMol(component);
                density[component] = thermo.componentStandardLiquidDensityKgPerCubicMetre(component);
                criticalTemperature[component] = thermo.componentCriticalTemperatureKelvin(component);
                acentricFactor[component] = thermo.componentAcentricFactor(component);
            }
            return new ComponentConstants(molecularWeight, density, criticalTemperature, acentricFactor);
        }
    }

    /**
     * Extracts the per-tray traffic of an accepted state.
     *
     * <p>Index {@code j} of the result is the traffic leaving tray {@code j}; the condenser and reboiler entries
     * are absent, because the N-1 tray intervals are the only places a tray pressure drop exists.</p>
     */
    public static Tray[] traffic(V3ColumnProblem problem, V3DryMeshState state, ComponentConstants constants) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(constants, "constants");
        V3ColumnTopology topology = problem.topology();
        Tray[] trays = new Tray[topology.nodeCount()];
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            double water = problem.waterVaporFlow(state, tray);
            double vaporMoles = 0.0;
            double vaporMass = 0.0;
            double liquidMoles = 0.0;
            double liquidMass = 0.0;
            double liquidVolume = 0.0;
            double criticalTemperature = 0.0;
            double acentricFactor = 0.0;
            for (int component = 0; component < state.componentCount(); component++) {
                int publicComponent = problem.activeComponentBasis().publicIndex(component);
                double molecularWeight = constants.molecularWeightKgPerMol()[publicComponent];
                double vapor = state.vaporFlow(tray, component);
                double liquid = state.liquidFlow(tray, component);
                vaporMoles += vapor;
                vaporMass += vapor * molecularWeight;
                liquidMoles += liquid;
                liquidMass += liquid * molecularWeight;
                liquidVolume += liquid * molecularWeight
                        / constants.standardLiquidDensityKgPerCubicMetre()[publicComponent];
                criticalTemperature += liquid * constants.criticalTemperatureKelvin()[publicComponent];
                acentricFactor += liquid * constants.acentricFactor()[publicComponent];
            }
            trays[tray] = new Tray(
                    vaporMoles + water,
                    vaporMass + water * WATER_MOLECULAR_WEIGHT_KG_PER_MOL,
                    state.temperatureKelvin(tray),
                    liquidMass,
                    liquidVolume,
                    liquidMoles > 0.0 ? criticalTemperature / liquidMoles : EMPTY_TRAY_CRITICAL_TEMPERATURE,
                    liquidMoles > 0.0 ? acentricFactor / liquidMoles : EMPTY_TRAY_ACENTRIC_FACTOR,
                    problem.freeWaterFlowMolPerSecond(state, tray));
        }
        return trays;
    }

    /**
     * Rackett temperature scaling of the registered standard liquid density, on Kay's-rule pseudocritical
     * properties of the tray liquid.
     */
    public double liquidDensityKgPerCubicMetre(Tray tray) {
        if (!(tray.liquidStandardVolumeCubicMetresPerSecond() > 0.0)) return EMPTY_TRAY_LIQUID_DENSITY;
        double standardDensity = tray.liquidMassFlowKgPerSecond() / tray.liquidStandardVolumeCubicMetresPerSecond();
        double rackettCompressibility = 0.29056 - 0.08775 * tray.liquidAcentricFactor();
        double standardReduced = Math.min(0.98,
                STANDARD_DENSITY_TEMPERATURE_KELVIN / tray.liquidCriticalTemperatureKelvin());
        double reduced = Math.min(0.98, tray.temperatureKelvin() / tray.liquidCriticalTemperatureKelvin());
        return standardDensity * Math.pow(rackettCompressibility,
                Math.pow(1.0 - standardReduced, 2.0 / 7.0) - Math.pow(1.0 - reduced, 2.0 / 7.0));
    }

    /**
     * The pressure-drop split of one tray at one pressure.
     *
     * <p>The dry term is an orifice drop on the ideal-gas vapour density, so it scales as {@code 1/P}: evaluating
     * it at the pressure being marched rather than at the solved state's pressure is what removes the dominant
     * feedback analytically. The clear-liquid height falls as the vapour load rises through the Bennett aeration
     * factor, which is why the total is far flatter than a squared-velocity model and the fixed point benign.</p>
     */
    public Terms terms(Tray tray, double pressurePascal) {
        double liquidDensity = liquidDensityKgPerCubicMetre(tray);
        double liquidVolumeFlow = tray.liquidMassFlowKgPerSecond() / liquidDensity
                + tray.freeWaterMolarFlowMolPerSecond() * WATER_MOLECULAR_WEIGHT_KG_PER_MOL
                / WATER_LIQUID_DENSITY_KG_PER_CUBIC_METRE;
        boolean hasVapor = tray.vaporMolarFlowMolPerSecond() > 0.0;
        double vaporDensity = hasVapor
                ? tray.vaporMassFlowKgPerSecond() * pressurePascal
                        / (tray.vaporMolarFlowMolPerSecond() * GAS_CONSTANT_JOULE_PER_MOL_KELVIN
                                * tray.temperatureKelvin())
                : 1.0;
        double vaporVolumeFlow = hasVapor ? tray.vaporMassFlowKgPerSecond() / vaporDensity : 0.0;
        double densityDifference = Math.max(1.0, liquidDensity - vaporDensity);
        double bubblingVelocity = vaporVolumeFlow / bubblingArea();
        double capacityFactor = bubblingVelocity * Math.sqrt(vaporDensity / densityDifference);
        double aeration = Math.exp(-12.55 * Math.pow(capacityFactor, 0.91));
        double crestCoefficient = 0.501 + 0.438 * Math.exp(-137.8 * WEIR_HEIGHT_METRES);
        double clearLiquidHeight = aeration * (WEIR_HEIGHT_METRES + crestCoefficient
                * Math.pow(Math.max(0.0, liquidVolumeFlow) / (weirLengthMetres() * aeration), 2.0 / 3.0));
        double holeVelocity = bubblingVelocity / HOLE_AREA_FRACTION;
        double dry = vaporDensity * holeVelocity * holeVelocity / (2.0 * ORIFICE_COEFFICIENT * ORIFICE_COEFFICIENT);
        double bubbleDiameter = 1.27 * Math.cbrt(HOLE_DIAMETER_METRES * SURFACE_TENSION_NEWTON_PER_METRE
                / (STANDARD_GRAVITY * densityDifference));
        double residual = 6.0 * SURFACE_TENSION_NEWTON_PER_METRE / bubbleDiameter;
        double flowParameter = tray.vaporMassFlowKgPerSecond() > 0.0
                ? (tray.liquidMassFlowKgPerSecond()
                        + tray.freeWaterMolarFlowMolPerSecond() * WATER_MOLECULAR_WEIGHT_KG_PER_MOL)
                        / tray.vaporMassFlowKgPerSecond() * Math.sqrt(vaporDensity / liquidDensity)
                : 1.0;
        double capacityParameter = (0.0105 + 8.127e-4 * Math.pow(TRAY_SPACING_METRES * 1000.0, 0.755)
                * Math.exp(-1.463 * Math.pow(flowParameter, 0.842)))
                * Math.pow(SURFACE_TENSION_NEWTON_PER_METRE / 0.02, 0.2);
        double flood = (vaporVolumeFlow / netArea())
                / (capacityParameter * Math.sqrt(Math.max(1.0e-9, densityDifference / vaporDensity)));
        return new Terms(dry, liquidDensity * STANDARD_GRAVITY * clearLiquidHeight, residual, flood);
    }

    /** The total pressure drop of one tray at one pressure. */
    public double dropPascal(Tray tray, double pressurePascal) {
        return terms(tray, pressurePascal).totalPascal();
    }

    /**
     * Marches the pressure-consistent profile down the column from the authored top pressure.
     *
     * <p>Each of the N-1 tray intervals is a scalar fixed point solved at its own lower-tray pressure. The
     * condenser shares the top tray's pressure and the sump shares the bottom tray's, exactly as the uniform
     * profile does, so the marched profile is interchangeable with the authored one everywhere downstream.</p>
     */
    public double[] march(V3ColumnProblem problem, Tray[] trays) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(trays, "trays");
        V3ColumnTopology topology = problem.topology();
        if (trays.length != topology.nodeCount()) {
            throw new IllegalArgumentException("V3 tray traffic does not match the resolved topology");
        }
        double top = problem.input().topPressurePascal();
        double[] pressures = new double[topology.nodeCount()];
        pressures[topology.condenserNode()] = top;
        pressures[1] = top;
        for (int tray = 2; tray <= topology.trayCount(); tray++) {
            double above = pressures[tray - 1];
            double pressure = above + dropPascal(trays[tray], above);
            for (int iteration = 0; iteration < MARCH_ITERATIONS; iteration++) {
                double next = above + dropPascal(trays[tray], pressure);
                boolean converged = Math.abs(next - pressure) < MARCH_TOLERANCE_PASCAL;
                pressure = next;
                if (converged) break;
            }
            pressures[tray] = pressure;
        }
        pressures[topology.reboilerNode()] = pressures[topology.trayCount()];
        return pressures;
    }

    /** Total drop across the N-1 tray intervals of a profile. */
    public static double totalDropPascal(V3ColumnTopology topology, double[] pressures) {
        return pressures[topology.trayCount()] - pressures[1];
    }

    /**
     * Relative disagreement between a profile in use and the profile its own traffic asks for.
     *
     * <p>Normalised on the marched total, so the authored 0 Pa nominal of the shipped presets reports a
     * mismatch of 1 rather than dividing by zero.</p>
     */
    public static double totalDropMismatch(V3ColumnTopology topology, double[] used, double[] marched) {
        double marchedDrop = totalDropPascal(topology, marched);
        if (!Double.isFinite(marchedDrop) || marchedDrop <= 0.0) return 0.0;
        return Math.abs(totalDropPascal(topology, used) - marchedDrop) / marchedDrop;
    }

    /** Whether a marched profile is finite, positive, ordered, and inside the property package's envelope. */
    public static boolean isAdmissible(V3ColumnTopology topology, double[] pressures, V3PengRobinsonThermo thermo) {
        double previous = 0.0;
        for (double pressure : pressures) {
            if (!Double.isFinite(pressure) || pressure <= 0.0
                    || pressure < thermo.minimumPressurePascal() || pressure > thermo.maximumPressurePascal()) {
                return false;
            }
        }
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            if (pressures[tray] < previous) return false;
            previous = pressures[tray];
        }
        return true;
    }

    /** Halfway between two profiles; the fallback step of a correction that refused the full one. */
    public static double[] halfway(V3ColumnTopology topology, double[] from, double[] to) {
        double[] half = from.clone();
        for (int tray = 2; tray <= topology.trayCount(); tray++) {
            half[tray] = from[tray] + 0.5 * (to[tray] - from[tray]);
        }
        half[topology.reboilerNode()] = half[topology.trayCount()];
        return half;
    }

    /**
     * Summarises a hydraulic profile for publication and finds the tray closest to flooding.
     *
     * @param correctionApplied whether the published state was re-solved on this profile
     * @param residualMismatch the disagreement left between the published profile and this state's own march
     */
    public V3TrayHydraulicsSummary summarise(V3ColumnProblem problem, Tray[] trays, double[] pressures,
            boolean correctionApplied, double residualMismatch) {
        V3ColumnTopology topology = problem.topology();
        double worstFlood = 0.0;
        int worstTray = 1;
        Terms worstTerms = terms(trays[1], pressures[1]);
        for (int tray = 2; tray <= topology.trayCount(); tray++) {
            Terms candidate = terms(trays[tray], pressures[tray]);
            if (candidate.floodFraction() > worstFlood) {
                worstFlood = candidate.floodFraction();
                worstTray = tray;
                worstTerms = candidate;
            }
        }
        double total = totalDropPascal(topology, pressures);
        int intervals = Math.max(1, topology.trayCount() - 1);
        return new V3TrayHydraulicsSummary(diameterMetres, total, total / intervals, worstFlood, worstTray,
                correctionApplied, residualMismatch, worstTerms.dryPascal(), worstTerms.liquidPascal());
    }

    /**
     * The flooding warning: which tray, how far past the limit, what its drop is made of, why, and what to do.
     *
     * <p>Flooding is never a typed failure. The column is published and the operator is told the cause, because
     * the two remedies — a wider column, or less traffic — are both authored choices the calculator cannot make.
     * The dominant term names the cause: an orifice drop above the froth head is a vapour load the perforations
     * cannot pass, and a dominant weir-crest head is a liquid load the downcomer and weir cannot pass.</p>
     */
    public static String floodingWarning(V3TrayHydraulicsSummary summary) {
        return String.format(Locale.ROOT,
                "tray pressure drop is too high: tray %d is at %.0f%% of flood (drop %.2f kPa: dry %.2f, liquid %.2f kPa); "
                        + "the %s load is too high "
                        + "for a %.1f m column; widen the column, or cut feed, steam, reboiler duty or reflux",
                summary.maximumFloodTray(), 100.0 * summary.maximumFloodFraction(),
                (summary.worstTrayDryPascal() + summary.worstTrayLiquidPascal()) / 1000.0,
                summary.worstTrayDryPascal() / 1000.0, summary.worstTrayLiquidPascal() / 1000.0,
                summary.vaporLimited() ? "vapor" : "liquid", summary.columnDiameterMetres());
    }
}
