package com.wormzjl.createcheme.science.column.nextgen;

import java.util.Objects;

/**
 * Resolved water material and enthalpy sources on the canonical node axis.
 *
 * <p>Utility enthalpy is evaluated at its authored upstream state, then carried unchanged to the connected tray:
 * throttling is isenthalpic. Water is shifted once to the hydrocarbon 298.15 K reporting datum; the same shift is
 * used for liquid, vapor, inlet, and outlet terms so it cannot create an artificial latent-heat contribution.
 */
public final class WaterFeedProfile {
    private static final double DATUM_TEMPERATURE_KELVIN = 298.15;
    private static final double DATUM_PRESSURE_PASCAL = 50_000.0;
    private static final double SATURATION_AMBIGUITY_RELATIVE = 1.0e-9;
    private static final double WATER_DATUM_SHIFT_JOULES_PER_MOL =
            -If97Water.liquidEnthalpyJoulesPerMol(DATUM_TEMPERATURE_KELVIN, DATUM_PRESSURE_PASCAL);

    private final double[] molarFeedByNode;
    private final double[] enthalpyFlowWattsByNode;

    private WaterFeedProfile(double[] molarFeedByNode, double[] enthalpyFlowWattsByNode) {
        this.molarFeedByNode = molarFeedByNode;
        this.enthalpyFlowWattsByNode = enthalpyFlowWattsByNode;
    }

    /** Resolves all material/energy input states before a solver workspace is allocated. */
    public static WaterFeedProfile resolve(
            ColumnNextInput input, ComponentBasis basis, CharacterizedFeed characterizedFeed, double[] nodePressuresPascal) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(characterizedFeed, "characterizedFeed");
        Objects.requireNonNull(nodePressuresPascal, "nodePressuresPascal");
        int nodes = input.stageCount() + 2;
        if (nodePressuresPascal.length != nodes) {
            throw new IllegalArgumentException("Resolved water-feed pressures do not match the column node axis");
        }
        double[] molar = new double[nodes];
        double[] enthalpyFlow = new double[nodes];
        int waterIndex = basis.waterIndex();
        if (waterIndex < 0) {
            if (!input.utilityFeeds().isEmpty()) {
                throw new IllegalArgumentException("Selected package has no water model for the connected utility feed");
            }
            return new WaterFeedProfile(molar, enthalpyFlow);
        }

        double crudeWaterFlow = input.crudeFeed().molarFlowMolPerSecond() * characterizedFeed.moleFraction(waterIndex);
        if (!Double.isFinite(crudeWaterFlow) || crudeWaterFlow < 0.0) {
            throw new IllegalArgumentException("Characterized crude water flow is invalid");
        }
        if (crudeWaterFlow > 0.0) {
            int stage = input.crudeFeedStageNumber();
            double enthalpy = phaseSelectedEnthalpy(input.crudeFeed().temperatureKelvin(), nodePressuresPascal[stage]);
            add(molar, enthalpyFlow, stage, crudeWaterFlow, enthalpy);
        }
        for (ColumnNextInput.WaterSteamFeedInput utility : input.utilityFeeds()) {
            int stage = utility.stageNumber();
            if (utility.upstreamPressurePascal() < nodePressuresPascal[stage]) {
                throw new IllegalArgumentException("Utility feed pressure is below connected stage pressure");
            }
            double enthalpy;
            try {
                enthalpy = utility.mode() == ColumnNextInput.UtilityFeedMode.WATER
                        ? alignedLiquidEnthalpy(utility.temperatureKelvin(), utility.upstreamPressurePascal())
                        : alignedVaporEnthalpy(utility.temperatureKelvin(), utility.upstreamPressurePascal());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Utility " + utility.mode().serializedName() + " feed at stage "
                        + stage + " has an invalid upstream IF97 state: " + exception.getMessage(), exception);
            }
            add(molar, enthalpyFlow, stage, utility.molarFlowMolPerSecond(), enthalpy);
        }
        return new WaterFeedProfile(molar, enthalpyFlow);
    }

    /** Common-datum liquid enthalpy for the current node's pure aqueous phase. */
    public static double alignedLiquidEnthalpy(double temperatureKelvin, double pressurePascal) {
        return If97Water.liquidEnthalpyJoulesPerMol(temperatureKelvin, pressurePascal)
                + WATER_DATUM_SHIFT_JOULES_PER_MOL;
    }

    /** Common-datum vapor enthalpy for water at its Dalton partial pressure. */
    public static double alignedVaporEnthalpy(double temperatureKelvin, double partialPressurePascal) {
        return If97Water.vaporEnthalpyJoulesPerMol(temperatureKelvin, partialPressurePascal)
                + WATER_DATUM_SHIFT_JOULES_PER_MOL;
    }

    private static double phaseSelectedEnthalpy(double temperatureKelvin, double pressurePascal) {
        if (temperatureKelvin >= If97Water.CRITICAL_TEMPERATURE_KELVIN) {
            return alignedVaporEnthalpy(temperatureKelvin, pressurePascal);
        }
        double saturation = If97Water.saturationPressurePascal(temperatureKelvin);
        if (Math.abs(pressurePascal - saturation) <= SATURATION_AMBIGUITY_RELATIVE * pressurePascal) {
            throw new IllegalArgumentException("Crude water feed is ambiguous at its stage saturation state");
        }
        return pressurePascal > saturation ? alignedLiquidEnthalpy(temperatureKelvin, pressurePascal)
                : alignedVaporEnthalpy(temperatureKelvin, pressurePascal);
    }

    private static void add(double[] molar, double[] enthalpyFlow, int node, double flow, double enthalpy) {
        if (!Double.isFinite(flow) || flow < 0.0 || !Double.isFinite(enthalpy)) {
            throw new IllegalArgumentException("Water feed flow or enthalpy is invalid");
        }
        molar[node] += flow;
        enthalpyFlow[node] += flow * enthalpy;
        if (!Double.isFinite(molar[node]) || !Double.isFinite(enthalpyFlow[node])) {
            throw new IllegalArgumentException("Combined water feed exceeds a finite physical range");
        }
    }

    public double[] molarFeedByNode() { return molarFeedByNode.clone(); }
    public double[] enthalpyFlowWattsByNode() { return enthalpyFlowWattsByNode.clone(); }
}
