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
    private final double[] nodeHeatDutyWatts;
    private final double[] waterVaporFlowMolPerSecond;
    private final V3WaterCondenserRegime waterCondenserRegime;
    private final double waterVaporSlipCoefficient;
    private final double freeWaterFlowScaleMolPerSecond;
    private final V3WetTraySet wetTraySet;
    private final V3DegreeOfFreedomLedger degreeOfFreedomLedger;
    private final V3TruncationSupport truncationSupport;

    V3ColumnProblem(
            V3ColumnInput input, V3ColumnTopology topology, V3ActiveComponentBasis activeComponentBasis,
            V3CondenserComponentPhases condenserComponentPhases, double[] nodePressuresPascal,
            V3DegreeOfFreedomLedger degreeOfFreedomLedger, V3TruncationSupport truncationSupport,
            V3WetTraySet wetTraySet) {
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
        this.nodeHeatDutyWatts = V3Pumparounds.nodeDutyWatts(input, topology);
        this.waterVaporFlowMolPerSecond = V3SteamFeeds.upwardVaporProfile(nodeSteamFeedMolPerSecond, topology);
        this.waterCondenserRegime = waterCondenserRegime(input, topology, this.nodePressuresPascal);
        this.waterVaporSlipCoefficient = condenserSlipCoefficient(topology, waterCondenserRegime, input,
                this.nodePressuresPascal);
        double steam = 0.0;
        for (V3SteamFeedSpec feed : input.steamFeeds()) steam += feed.molarFlowMolPerSecond();
        this.freeWaterFlowScaleMolPerSecond = steam > 0.0 ? steam : 1.0;
        this.wetTraySet = Objects.requireNonNull(wetTraySet, "wetTraySet");
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
                || degreeOfFreedomLedger.truncationSupport() != truncationSupport
                || degreeOfFreedomLedger.wetTraySet() != wetTraySet) {
            throw new IllegalArgumentException("V3 degree-of-freedom ledger does not describe this resolved problem");
        }
        if (wetTraySet.hasWetTrays() && !hasSteamFeeds()) {
            throw new IllegalArgumentException("V3 free water requires an authored steam feed");
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

    /**
     * Whether this point's liquid flow is an unknown of the resolved problem.
     *
     * <p>Three rules compose here and nothing in the package may consult them separately: the node's
     * structural liquid phase, the per-component condenser phase rule, and the attempt-local support mask,
     * which can remove one phase of a point ({@code LIQUID_ONLY} / {@code VAPOR_ONLY}) or the point as a
     * whole.</p>
     */
    boolean hasLiquidUnknown(int node, int component) {
        return truncationSupport.hasLiquidUnknown(condenserComponentPhases, node, component);
    }

    /** Whether this point's vapour flow is an unknown of the resolved problem. */
    boolean hasVaporUnknown(int node, int component) {
        return truncationSupport.hasVaporUnknown(node, component);
    }

    /** A vapour-liquid equilibrium row exists exactly where both phases of the point are unknowns. */
    boolean hasEquilibriumRow(int node, int component) {
        return truncationSupport.hasEquilibriumRow(condenserComponentPhases, node, component);
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

    public boolean hasPumparounds() {
        return !input.pumparounds().isEmpty();
    }

    /** Prescribed constant stage heat, positive into the column; zero on every node without a pumparound. */
    public double stageHeatWatts(int node) {
        return nodeHeatDutyWatts[node];
    }

    /** Authored upward steam profile of a node: every mole fed at or below it. Free water is added by state. */
    public double waterVaporFlowMolPerSecond(int node) {
        return waterVaporFlowMolPerSecond[node];
    }

    /** The trays of this attempt's frozen free-water set. */
    V3WetTraySet wetTraySet() {
        return wetTraySet;
    }

    boolean hasWetTrays() {
        return wetTraySet.hasWetTrays();
    }

    boolean isWetTray(int node) {
        return wetTraySet.isWet(node);
    }

    /** Log-flow scale of a free-water unknown: the total authored steam, or one on a dry column. */
    double freeWaterFlowScaleMolPerSecond() {
        return freeWaterFlowScaleMolPerSecond;
    }

    /** Aqueous liquid leaving a tray downward; exactly zero unless the tray is in the frozen wet set. */
    double freeWaterFlowMolPerSecond(V3DryMeshState state, int node) {
        return isWetTray(node) ? state.freeWaterFlow(node) : 0.0;
    }

    /**
     * Water vapor rising out of a node, from the candidate state.
     *
     * <p>The tray water balance {@code W_n = W_(n+1) + F_(n-1) + S_n - F_n} telescopes downward to
     * {@code W_n = (steam fed at or below n) + F_(n-1) - F_R}, and the sump is never wet
     * ({@link V3WetTraySet}), so {@code F_R} is zero and only the tray directly above contributes. Water that
     * condenses on a tray reaches the tray below and comes straight back up, which is why it never changes the
     * net water passing its own tray.</p>
     *
     * <p>At the condenser the water is not a balance but the drum's regime split, whose arriving water is
     * {@code W_1} — the authored steam total, because nothing above tray one can shed free water into it.</p>
     */
    double waterVaporFlow(V3DryMeshState state, int node) {
        if (node == topology.condenserNode()) return waterCondenserSplit(state).vaporFlowMolPerSecond();
        return waterVaporFlowMolPerSecond(node) + (node >= 2 ? freeWaterFlowMolPerSecond(state, node - 1) : 0.0);
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
     * Splits the condenser-arriving water between the molecular overhead vapor and a
     * separate free-water product. The molecular vapor allocation is capped by arriving water:
     * an unsaturated overhead cannot create water that was never fed to the column.
     *
     * <p>The arriving water is {@code W_1}, which is the authored steam total whether or not the top trays
     * carry free water: no free water is shed into tray one from above, so the water balance leaves
     * {@code W_1} equal to the steam fed. The regime and the slip coefficient are therefore still resolved
     * once from the authored total, and this split is exactly what it was before wet trays existed.</p>
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
