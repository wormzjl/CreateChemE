package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Full dry MESH residual assembly; this class assembles equations only and cannot publish a solver result. */
final class V3MeshResidualEvaluator {
    private final V3ColumnProblem problem;
    private final V3ThermoModel thermo;
    private final double feedMolarEnthalpyJoulesPerMol;
    private final double organicRefluxRatio;
    private final double reboilerDutyWatts;
    private final double totalFeedFlow;
    private final V3ActiveComponentBasis activeComponentBasis;

    V3MeshResidualEvaluator(V3ColumnProblem problem, V3ThermoModel thermo, double feedMolarEnthalpyJoulesPerMol) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.thermo = Objects.requireNonNull(thermo, "thermo");
        if (!problem.input().componentBasis().equals(thermo.componentBasis())) {
            throw new IllegalArgumentException("V3 MESH thermodynamic basis differs from the resolved problem basis");
        }
        if (!Double.isFinite(feedMolarEnthalpyJoulesPerMol)) {
            throw new IllegalArgumentException("V3 MESH feed enthalpy must be finite");
        }
        this.feedMolarEnthalpyJoulesPerMol = feedMolarEnthalpyJoulesPerMol;
        this.organicRefluxRatio = specification(V3ColumnSpecification.OrganicRefluxRatio.class).ratio();
        this.reboilerDutyWatts = specification(V3ColumnSpecification.ReboilerDuty.class).watts();
        this.activeComponentBasis = problem.activeComponentBasis();
        this.totalFeedFlow = activeComponentBasis.totalFeedFlowMolPerSecond();
    }

    V3MeshResidual evaluate(V3DryMeshState state, V3ThermoWorkspace workspace) {
        state = Objects.requireNonNull(state, "state");
        workspace = Objects.requireNonNull(workspace, "workspace");
        if (state.nodeCount() != problem.topology().nodeCount()
                || state.componentCount() != activeComponentBasis.componentCount()) {
            throw new IllegalArgumentException("V3 MESH state does not match the resolved problem");
        }
        NodeProperties[] properties = nodeProperties(state, workspace);
        List<V3MeshResidual.Row> rows = new ArrayList<>(problem.degreeOfFreedomLedger().equationCount());
        double[] balance = new double[2];
        for (V3DegreeOfFreedomLedger.Equation equation : problem.degreeOfFreedomLedger().equations()) {
            V3DegreeOfFreedomLedger.EquationId id = equation.id();
            double physicalValue;
            double scale;
            switch (id.family()) {
                case COMPONENT_MATERIAL_BALANCE -> {
                    materialBalance(state, id.node(), id.component(), balance);
                    physicalValue = balance[0];
                    scale = materialScale(balance[1], id.component());
                }
                case VAPOR_LIQUID_EQUILIBRIUM -> {
                    physicalValue = equilibriumResidual(state, id.node(), id.component(), properties[id.node()]);
                    scale = 1.0;
                }
                case ENERGY_BALANCE -> {
                    physicalValue = energyResidual(state, id.node(), properties);
                    scale = energyScale();
                }
                // A log-composition statement exactly like the VLE rows, and scaled like them.
                case WATER_SATURATION -> {
                    physicalValue = waterSaturationResidual(state, id.node(), properties[id.node()]);
                    scale = 1.0;
                }
                default -> throw new IllegalStateException("V3 MESH equation family is unhandled");
            }
            rows.add(new V3MeshResidual.Row(id, physicalValue, scale));
        }
        return new V3MeshResidual(rows);
    }

    /**
     * Returns the thermodynamic terms whose value can change when only one stage block is perturbed.
     *
     * <p>Material rows are intentionally absent: their log-flow derivatives are exact and are assembled without a
     * property call. The returned phase-energy terms are total phase enthalpy rates, so adjacent energy rows can use
     * their difference directly.</p>
     */
    LocalNodeTerms localTerms(V3DryMeshState state, int node, V3ThermoWorkspace workspace) {
        state = Objects.requireNonNull(state, "state");
        workspace = Objects.requireNonNull(workspace, "workspace");
        if (state.nodeCount() != problem.topology().nodeCount()
                || state.componentCount() != activeComponentBasis.componentCount()
                || node < problem.topology().condenserNode() || node > problem.topology().reboilerNode()) {
            throw new IllegalArgumentException("V3 local MESH thermodynamic probe does not match its problem");
        }
        NodeProperties properties = nodeProperties(state, node, workspace);
        double[] equilibrium = new double[state.componentCount()];
        Arrays.fill(equilibrium, Double.NaN);
        for (int component = 0; component < equilibrium.length; component++) {
            if (problem.hasEquilibriumRow(node, component)) {
                equilibrium[component] = equilibriumResidual(state, node, component, properties);
            }
        }
        double liquidEnergy = problem.topology().hasLiquidPhase(node)
                ? phaseEnergy(state, node, true, properties) : 0.0;
        double vaporEnergy = phaseEnergy(state, node, false, properties);
        double saturation = problem.isWetTray(node) ? waterSaturationResidual(state, node, properties) : Double.NaN;
        return new LocalNodeTerms(equilibrium, liquidEnergy, vaporEnergy, freeWaterEnergy(state, node), saturation);
    }

    /**
     * Writes the component material imbalance into {@code balance[0]} and its local throughput into {@code balance[1]}.
     *
     * <p>The throughput is the largest absolute term of the same balance at this state: liquid arriving from
     * above after any side-draw withdrawal, vapour arriving from below, the feed term on the feed tray, and the
     * two outlets. It is the natural denominator of a relative material closure, and it is what makes a trace
     * component's imbalance visible: an imbalance is small only when it is small against the flows that produce
     * it, not when it is small against the component's feed.</p>
     */
    private void materialBalance(V3DryMeshState state, int node, int component, double[] balance) {
        V3ColumnTopology topology = problem.topology();
        if (node == topology.condenserNode()) {
            double vaporIn = state.vaporFlow(1, component);
            double vaporOut = state.vaporFlow(0, component);
            double liquidOut = problem.hasLiquidUnknown(0, component) ? state.liquidFlow(0, component) : 0.0;
            balance[0] = vaporIn - vaporOut - liquidOut;
            balance[1] = largest(vaporIn, vaporOut, liquidOut, 0.0, 0.0);
            return;
        }
        if (node <= topology.trayCount()) {
            double liquidIn = node == 1
                    ? (problem.hasLiquidUnknown(0, component)
                    ? organicRefluxFraction() * state.liquidFlow(0, component) : 0.0)
                    : (1.0 - problem.liquidWithdrawalFraction(state, node - 1)) * state.liquidFlow(node - 1, component);
            double vaporIn = state.vaporFlow(node + 1, component);
            double feed = node == topology.feedTrayNumber() ? activeComponentBasis.feedFlowMolPerSecond(component) : 0.0;
            double liquidOut = state.liquidFlow(node, component);
            double vaporOut = state.vaporFlow(node, component);
            balance[0] = liquidIn + vaporIn + feed - liquidOut - vaporOut;
            balance[1] = largest(liquidIn, vaporIn, feed, liquidOut, vaporOut);
            return;
        }
        double liquidIn = (1.0 - problem.liquidWithdrawalFraction(state, node - 1)) * state.liquidFlow(node - 1, component);
        double liquidOut = state.liquidFlow(node, component);
        double vaporOut = state.vaporFlow(node, component);
        balance[0] = liquidIn - liquidOut - vaporOut;
        balance[1] = largest(liquidIn, liquidOut, vaporOut, 0.0, 0.0);
    }

    private static double largest(double first, double second, double third, double fourth, double fifth) {
        double maximum = Math.abs(first);
        maximum = Math.max(maximum, Math.abs(second));
        maximum = Math.max(maximum, Math.abs(third));
        maximum = Math.max(maximum, Math.abs(fourth));
        return Math.max(maximum, Math.abs(fifth));
    }

    private double equilibriumResidual(V3DryMeshState state, int node, int component, NodeProperties properties) {
        int publicComponent = activeComponentBasis.publicIndex(component);
        double residual = Math.log(properties.vaporComposition()[publicComponent])
                + properties.vaporResult().logFugacityCoefficient(publicComponent)
                - Math.log(properties.liquidComposition()[publicComponent])
                - properties.liquidResult().logFugacityCoefficient(publicComponent);
        return problem.hasSteamFeeds() ? residual + waterDilutionLogTerm(state, node, properties.vaporTotal()) : residual;
    }

    /**
     * One node's energy closure, in watts.
     *
     * <p>Free water enters at the enthalpy of saturated liquid water at the tray it fell from and leaves at
     * that of this tray, and the water vapour of every phase term is the state-dependent {@code W}, so the
     * latent heat released where water condenses and absorbed where it re-evaporates appears without a term
     * of its own. Free water is <em>not</em> withdrawn by a side draw: the aqueous phase is immiscible and
     * decants, so the {@code 1 - w} retention factor applies to the hydrocarbon liquid only.</p>
     */
    private double energyResidual(V3DryMeshState state, int node, NodeProperties[] properties) {
        V3ColumnTopology topology = problem.topology();
        double freeWaterIn = node >= 2 ? freeWaterEnergy(state, node - 1) : 0.0;
        if (node <= topology.trayCount()) {
            double liquidIn = node == 1
                    ? (topology.hasLiquidPhase(0) ? organicRefluxFraction() * phaseEnergy(state, 0, true, properties) : 0.0)
                    : (1.0 - problem.liquidWithdrawalFraction(state, node - 1)) * phaseEnergy(state, node - 1, true, properties);
            double vaporIn = phaseEnergy(state, node + 1, false, properties);
            double feed = node == topology.feedTrayNumber() ? totalFeedFlow * feedMolarEnthalpyJoulesPerMol : 0.0;
            // A prescribed pumparound duty is a constant source term with no state derivative, so no
            // unknown, equation, or Jacobian block changes when it is present.
            return liquidIn + vaporIn + feed + freeWaterIn + problem.steamFeedEnthalpyWatts(node)
                    + problem.stageHeatWatts(node) - phaseEnergy(state, node, true, properties)
                    - phaseEnergy(state, node, false, properties) - freeWaterEnergy(state, node);
        }
        // The sump is never wet, so free water arriving there leaves it entirely as vapour in W_R.
        return (1.0 - problem.liquidWithdrawalFraction(state, node - 1)) * phaseEnergy(state, node - 1, true, properties) + reboilerDutyWatts
                + problem.steamFeedEnthalpyWatts(node) + freeWaterIn
                - phaseEnergy(state, node, true, properties) - phaseEnergy(state, node, false, properties);
    }

    /** Enthalpy rate of the aqueous liquid leaving one tray downward; zero unless the tray is wet. */
    private double freeWaterEnergy(V3DryMeshState state, int node) {
        double freeWater = problem.freeWaterFlowMolPerSecond(state, node);
        return freeWater == 0.0 ? 0.0
                : freeWater * V3WaterProperties.liquidMolarEnthalpy(state.temperatureKelvin(node));
    }

    /**
     * {@code ln(P_n W_n / ((V_hc,n + W_n) P_sat(T_n)))}: the wet tray's vapour is exactly water-saturated.
     *
     * <p>Written in logs for the same reason the equilibrium rows are: it makes the row a difference of
     * log compositions whose scale is one, and it keeps the strictly positive flows in the coordinates the
     * solver already works in.</p>
     */
    private double waterSaturationResidual(V3DryMeshState state, int node, NodeProperties properties) {
        double water = waterVaporFlow(state, node);
        if (!(water > 0.0) || !Double.isFinite(water)) {
            throw new IllegalArgumentException("V3 MESH wet tray carries no positive water vapor");
        }
        double hydrocarbon = properties.vaporTotal();
        return Math.log(problem.nodePressurePascal(node)) + Math.log(water) - Math.log(hydrocarbon + water)
                - Math.log(V3WaterProperties.saturationPressurePascal(state.temperatureKelvin(node)));
    }

    private double phaseEnergy(V3DryMeshState state, int node, boolean liquid, NodeProperties[] properties) {
        return phaseEnergy(state, node, liquid, properties[node]);
    }

    private double phaseEnergy(V3DryMeshState state, int node, boolean liquid, NodeProperties properties) {
        double totalFlow = phaseTotal(state, node, liquid);
        double energy = totalFlow == 0.0 ? 0.0 : totalFlow * (liquid ? properties.liquidResult().molarEnthalpyJoulesPerMol()
                : properties.vaporResult().molarEnthalpyJoulesPerMol());
        // Water vapor is carried outside the hydrocarbon basis and must leave the node even when the hydrocarbon
        // vapor is absent: an ALL_VAPOR condenser on the LIQUID_ONLY branch publishes the arriving steam as a
        // pure-water overhead product, and that product's enthalpy belongs to the condenser vapor outlet.
        if (!liquid && problem.hasSteamFeeds()) {
            double water = waterVaporFlow(state, node);
            if (water != 0.0) energy += water * V3WaterProperties.vaporMolarEnthalpy(state.temperatureKelvin(node));
        }
        return energy;
    }

    /** Builds immutable per-node property snapshots once per residual evaluation. */
    private NodeProperties[] nodeProperties(V3DryMeshState state, V3ThermoWorkspace workspace) {
        NodeProperties[] properties = new NodeProperties[state.nodeCount()];
        for (int node = 0; node < properties.length; node++) {
            properties[node] = nodeProperties(state, node, workspace);
        }
        return properties;
    }

    private NodeProperties nodeProperties(V3DryMeshState state, int node, V3ThermoWorkspace workspace) {
        double temperature = state.temperatureKelvin(node);
        double pressure = problem.nodePressurePascal(node);
        double[] vaporComposition = null;
        V3FugacityResult vaporResult = null;
        if (problem.topology().hasVaporPhase(node)) {
            vaporComposition = normalizedPublicPhaseComposition(state, node, false);
            vaporResult = thermo.fugacity(temperature, pressure, vaporComposition, V3Phase.VAPOR, workspace);
        }
        double[] liquidComposition = null;
        V3FugacityResult liquidResult = null;
        if (problem.topology().hasLiquidPhase(node)) {
            liquidComposition = normalizedPublicPhaseComposition(state, node, true);
            liquidResult = thermo.fugacity(temperature, pressure, liquidComposition, V3Phase.LIQUID, workspace);
        }
        return new NodeProperties(liquidComposition, vaporComposition, liquidResult, vaporResult,
                problem.topology().hasVaporPhase(node) ? phaseTotal(state, node, false) : 0.0);
    }

    private double[] normalizedPublicPhaseComposition(V3DryMeshState state, int node, boolean liquid) {
        double total = phaseTotal(state, node, liquid);
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("V3 MESH equilibrium phase has no positive hydrocarbon flow");
        }
        double[] composition = new double[problem.input().componentBasis().componentCount()];
        for (int component = 0; component < state.componentCount(); component++) {
            double flow = liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            // A phase the support removed — one of a one-phase point, or both of an ABSENT point — carries
            // exactly zero and contributes nothing to this phase's composition.
            if (!(liquid ? problem.truncationSupport().retainsLiquid(node, component)
                    : problem.truncationSupport().retainsVapor(node, component))) {
                if (flow != 0.0) throw new IllegalArgumentException("V3 truncated component flow must be exactly zero");
                continue;
            }
            if (flow == 0.0 && liquid && !problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component)) {
                continue;
            }
            if (flow <= 0.0) throw new IllegalArgumentException("V3 MESH active component flow must be positive for logarithmic VLE");
            composition[activeComponentBasis.publicIndex(component)] = flow / total;
        }
        return composition;
    }

    private double phaseTotal(V3DryMeshState state, int node, boolean liquid) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            total += liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
        }
        return total;
    }

    private double waterVaporFlow(V3DryMeshState state, int node) {
        return problem.waterVaporFlow(state, node);
    }

    private double waterDilutionLogTerm(V3DryMeshState state, int node, double hydrocarbonVaporTotal) {
        double water = waterVaporFlow(state, node);
        return water == 0.0 ? 0.0 : Math.log(hydrocarbonVaporTotal / (hydrocarbonVaporTotal + water));
    }

    /**
     * Relative denominator of one component material balance: its own local throughput, floored.
     *
     * <p>A state-dependent scale cannot be gamed by inflating a flow. The denominator is the largest single
     * term of the balance, so growing that term by {@code d} moves the numerator by the same {@code d} unless
     * the remaining terms absorb it: a balanced row stays balanced only if the material actually goes
     * somewhere. Multiplying every term of a row by a common factor leaves the ratio unchanged, and that is
     * the intended reading — the row is closed to a relative precision, exactly as the tolerance claims. The
     * flows themselves are not free to move: the same coordinates carry the neighbouring balances, the
     * equilibrium rows and the energy rows, which are scaled by constants.</p>
     *
     * <p>The denominator is the smaller of that throughput and the component's flow scale — its authored feed
     * flow, with the existing {@code F_total * 1e-12} guard for a negligible feed — and is floored at
     * {@link V3TruncationSupport#TRACE_FLOOR_FRACTION} of the same flow scale. Taking the smaller of the two
     * makes this a strict tightening of the former feed-only scale: internal traffic exceeds the feed
     * wherever a component is concentrated by the reflux, and relaxing those rows in proportion would move
     * weight out of the bulk material balances and into the equilibrium and energy rows, which are scaled by
     * constants. Where a component is locally depleted the throughput is the smaller number and the row
     * becomes as strict as the material actually passing through the point, which is the whole point: an
     * imbalance is small only when it is small against the flows that produce it.</p>
     *
     * <p>The floor keeps a physically empty balance from becoming an order-one demand. A retained point is
     * above it by construction of the support, so it binds only for the points the support is forced to
     * retain regardless of flow (the feed tray, the side-draw trays and the band between them) and for a
     * point whose flows are collapsing inside a continuation rung. None of this affects the linear algebra:
     * the banded solver equilibrates every row by its own maximum, so row scaling cancels before pivoting.</p>
     */
    private double materialScale(double localThroughput, int component) {
        double flowScale = activeComponentBasis.flowScale(component);
        return Math.max(Math.min(localThroughput, flowScale), flowScale * V3TruncationSupport.TRACE_FLOOR_FRACTION);
    }

    private double energyScale() {
        return Math.max(1.0, totalFeedFlow * 100_000.0);
    }

    private double organicRefluxFraction() {
        return organicRefluxRatio / (1.0 + organicRefluxRatio);
    }

    private <T extends V3ColumnSpecification> T specification(Class<T> type) {
        return problem.input().specifications().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 MESH problem is missing " + type.getSimpleName()));
    }

    private record NodeProperties(
            double[] liquidComposition,
            double[] vaporComposition,
            V3FugacityResult liquidResult,
            V3FugacityResult vaporResult,
            double vaporTotal) {}

    /**
     * Package-local response of one node to a local coordinate perturbation.
     *
     * <p>{@code freeWaterPhaseEnergy} is separate from {@code liquidPhaseEnergy} because the two are carried
     * downward by different coefficients: the hydrocarbon liquid by the side-draw retention {@code 1 - w},
     * the immiscible free water by one. {@code waterSaturationResidual} is {@code NaN} on a dry tray.</p>
     */
    record LocalNodeTerms(
            double[] equilibriumResiduals,
            double liquidPhaseEnergy,
            double vaporPhaseEnergy,
            double freeWaterPhaseEnergy,
            double waterSaturationResidual) {
        LocalNodeTerms {
            equilibriumResiduals = Objects.requireNonNull(equilibriumResiduals, "equilibriumResiduals").clone();
            if (!Double.isFinite(liquidPhaseEnergy) || !Double.isFinite(vaporPhaseEnergy)
                    || !Double.isFinite(freeWaterPhaseEnergy)) {
                throw new IllegalArgumentException("V3 local MESH phase energies must be finite");
            }
        }

        @Override public double[] equilibriumResiduals() { return equilibriumResiduals.clone(); }

        double equilibriumResidual(int component) { return equilibriumResiduals[component]; }
    }
}
