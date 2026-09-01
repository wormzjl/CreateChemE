package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.Objects;

/** Fully resolved immutable M0/M1 numerical contract ready for later solver admission. */
public final class V3ColumnProblem {
    private final V3ColumnInput input;
    private final V3ColumnTopology topology;
    private final V3ActiveComponentBasis activeComponentBasis;
    private final V3CondenserComponentPhases condenserComponentPhases;
    private final double[] nodePressuresPascal;
    private final double[] nodeSideDrawMolPerSecond;
    private final double[] nodeSteamFeedMolPerSecond;
    private final double[] nodeSteamFeedEnthalpyWatts;
    private final double[] waterVaporFlowMolPerSecond;
    private final V3WaterCondenserRegime waterCondenserRegime;
    private final double waterVaporSlipCoefficient;
    private final V3DegreeOfFreedomLedger degreeOfFreedomLedger;
    private final V3TruncationSupport truncationSupport;

    V3ColumnProblem(
            V3ColumnInput input, V3ColumnTopology topology, V3ActiveComponentBasis activeComponentBasis,
            V3CondenserComponentPhases condenserComponentPhases, double[] nodePressuresPascal,
            V3DegreeOfFreedomLedger degreeOfFreedomLedger, V3TruncationSupport truncationSupport) {
        this.input = Objects.requireNonNull(input, "input");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.activeComponentBasis = Objects.requireNonNull(activeComponentBasis, "activeComponentBasis");
        this.condenserComponentPhases = Objects.requireNonNull(condenserComponentPhases, "condenserComponentPhases");
        this.nodePressuresPascal = Objects.requireNonNull(nodePressuresPascal, "nodePressuresPascal").clone();
        this.nodeSideDrawMolPerSecond = new double[topology.nodeCount()];
        for (V3SideDrawSpec draw : input.sideDraws()) {
            this.nodeSideDrawMolPerSecond[draw.trayNumber()] = draw.molarFlowMolPerSecond();
        }
        this.nodeSteamFeedMolPerSecond = V3SteamFeeds.nodeFeedFlows(input, topology);
        this.nodeSteamFeedEnthalpyWatts = new double[topology.nodeCount()];
        for (V3SteamFeedSpec feed : input.steamFeeds()) {
            this.nodeSteamFeedEnthalpyWatts[feed.stageNumber()] = feed.molarFlowMolPerSecond()
                    * V3WaterProperties.vaporMolarEnthalpy(feed.temperatureKelvin());
        }
        this.waterVaporFlowMolPerSecond = V3SteamFeeds.upwardVaporProfile(nodeSteamFeedMolPerSecond, topology);
        this.waterCondenserRegime = waterCondenserRegime(input, topology, this.nodePressuresPascal);
        this.waterVaporSlipCoefficient = condenserSlipCoefficient(topology, waterCondenserRegime, input,
                this.nodePressuresPascal);
        this.degreeOfFreedomLedger = Objects.requireNonNull(degreeOfFreedomLedger, "degreeOfFreedomLedger");
        this.truncationSupport = Objects.requireNonNull(truncationSupport, "truncationSupport");
        if (this.nodePressuresPascal.length != topology.nodeCount()) {
            throw new IllegalArgumentException("V3 pressure profile does not match the resolved topology");
        }
        for (double pressure : this.nodePressuresPascal) {
            if (!Double.isFinite(pressure) || pressure <= 0.0) {
                throw new IllegalArgumentException("V3 pressure profile must be finite and physically positive");
            }
        }
        if (!degreeOfFreedomLedger.topology().equals(topology)
                || degreeOfFreedomLedger.componentCount() != activeComponentBasis.componentCount()
                || !degreeOfFreedomLedger.specifications().equals(input.specifications())
                || degreeOfFreedomLedger.truncationSupport() != truncationSupport) {
            throw new IllegalArgumentException("V3 degree-of-freedom ledger does not describe this resolved problem");
        }
        truncationSupport.requireCompatible(this);
    }

    public V3ColumnInput input() {
        return input;
    }

    public V3ColumnTopology topology() {
        return topology;
    }

    V3ActiveComponentBasis activeComponentBasis() {
        return activeComponentBasis;
    }

    V3CondenserComponentPhases condenserComponentPhases() {
        return condenserComponentPhases;
    }

    V3TruncationSupport truncationSupport() {
        return truncationSupport;
    }

    public double[] nodePressuresPascal() {
        return nodePressuresPascal.clone();
    }

    public double nodePressurePascal(int node) {
        return nodePressuresPascal[node];
    }

    public V3DegreeOfFreedomLedger degreeOfFreedomLedger() {
        return degreeOfFreedomLedger;
    }

    public boolean hasSideDraws() {
        return !input.sideDraws().isEmpty();
    }

    public double nodeSideDrawMolPerSecond(int node) {
        return nodeSideDrawMolPerSecond[node];
    }

    public boolean hasSteamFeeds() {
        return !input.steamFeeds().isEmpty();
    }

    public double nodeSteamFeedMolPerSecond(int node) {
        return nodeSteamFeedMolPerSecond[node];
    }

    public double steamFeedEnthalpyWatts(int node) {
        return nodeSteamFeedEnthalpyWatts[node];
    }

    /** Known upward water-vapor profile for tray and sump nodes; condenser slip is state-dependent. */
    public double waterVaporFlowMolPerSecond(int node) {
        return waterVaporFlowMolPerSecond[node];
    }

    public double waterVaporSlipCoefficient() {
        return waterVaporSlipCoefficient;
    }

    V3WaterCondenserRegime waterCondenserRegime() {
        return waterCondenserRegime;
    }

    boolean hasFreeWaterCondenser() {
        return waterCondenserRegime == V3WaterCondenserRegime.FREE_WATER;
    }

    boolean hasAllVaporWaterCondenser() {
        return waterCondenserRegime == V3WaterCondenserRegime.ALL_VAPOR;
    }

    /**
     * Splits the known condenser-arriving water between the molecular overhead vapor and a
     * separate free-water product. The molecular vapor allocation is capped by arriving water:
     * an unsaturated overhead cannot create water that was never fed to the column.
     */
    WaterCondenserSplit waterCondenserSplit(V3DryMeshState state) {
        state = Objects.requireNonNull(state, "state");
        int condenser = topology.condenserNode();
        double arrivingWater = waterVaporFlowMolPerSecond(1);
        return switch (waterCondenserRegime) {
            case NONE -> new WaterCondenserSplit(0.0, 0.0);
            case ALL_VAPOR -> new WaterCondenserSplit(arrivingWater, 0.0);
            case FREE_WATER -> {
                double vaporWater = topology.condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE
                        ? Math.min(arrivingWater, waterVaporSlipCoefficient * hydrocarbonVaporTotal(state, condenser)) : 0.0;
                yield new WaterCondenserSplit(vaporWater, arrivingWater - vaporWater);
            }
        };
    }

    private static double hydrocarbonVaporTotal(V3DryMeshState state, int node) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            total += state.vaporFlow(node, component);
        }
        return total;
    }

    /** Candidate-derived molecular-vapor/free-water allocation at the condenser. */
    record WaterCondenserSplit(double vaporFlowMolPerSecond, double freeWaterFlowMolPerSecond) {}

    private static V3WaterCondenserRegime waterCondenserRegime(
            V3ColumnInput input, V3ColumnTopology topology, double[] pressures) {
        if (input.steamFeeds().isEmpty()) return V3WaterCondenserRegime.NONE;
        double temperature = input.specifications().stream()
                .filter(V3ColumnSpecification.CondenserOutletTemperature.class::isInstance)
                .map(V3ColumnSpecification.CondenserOutletTemperature.class::cast).findFirst().orElseThrow().kelvin();
        if (topology.condenserPhaseBranch() == V3CondenserPhaseBranch.VAPOR_ONLY
                || temperature >= V3WaterProperties.CRITICAL_TEMPERATURE_KELVIN) {
            return V3WaterCondenserRegime.ALL_VAPOR;
        }
        double waterFraction = V3WaterProperties.saturationPressurePascal(temperature) / pressures[topology.condenserNode()];
        if (!(waterFraction >= 0.0) || !Double.isFinite(waterFraction)) {
            throw new IllegalArgumentException("V3 condenser water saturation ratio is not finite and nonnegative");
        }
        return waterFraction >= 1.0 ? V3WaterCondenserRegime.ALL_VAPOR : V3WaterCondenserRegime.FREE_WATER;
    }

    private static double condenserSlipCoefficient(
            V3ColumnTopology topology, V3WaterCondenserRegime regime, V3ColumnInput input, double[] pressures) {
        if (regime != V3WaterCondenserRegime.FREE_WATER
                || topology.condenserPhaseBranch() != V3CondenserPhaseBranch.TWO_PHASE) return 0.0;
        double temperature = input.specifications().stream()
                .filter(V3ColumnSpecification.CondenserOutletTemperature.class::isInstance)
                .map(V3ColumnSpecification.CondenserOutletTemperature.class::cast).findFirst().orElseThrow().kelvin();
        double waterFraction = V3WaterProperties.saturationPressurePascal(temperature) / pressures[topology.condenserNode()];
        return waterFraction / (1.0 - waterFraction);
    }

    /** Recomputed from the candidate; intentionally not capped during Newton iteration. */
    double liquidWithdrawalFraction(V3DryMeshState state, int node) {
        double rate = nodeSideDrawMolPerSecond[node];
        if (rate == 0.0) return 0.0;
        return V3SideDraws.withdrawal(state, node, rate).fraction();
    }
}
