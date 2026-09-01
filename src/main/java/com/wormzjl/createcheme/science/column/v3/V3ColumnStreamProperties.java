package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntToDoubleFunction;

/**
 * Immutable, physical stream summary extracted only after an accepted V3 MESH solve.
 *
 * <p>This is deliberately a bounded presentation boundary. It contains calculated stream conditions and the public
 * component-axis composition, but never exposes a mutable solver state as a result or a warm-start candidate.</p>
 */
public record V3ColumnStreamProperties(
        String streamId,
        String displayName,
        String phase,
        double molarFlowMolPerSecond,
        double massFlowKgPerSecond,
        double temperatureKelvin,
        double pressurePascal,
        double vaporMoleFraction,
        List<ComponentFraction> moleFractions) {
    public static final int MAX_STREAMS = 7;
    public static final int MAX_COMPONENTS = V3ComponentBasis.MAX_COMPONENTS + 1;

    public V3ColumnStreamProperties {
        streamId = identifier(streamId, "streamId");
        displayName = boundedText(displayName, "displayName", 48);
        phase = boundedText(phase, "phase", 16);
        if (!Double.isFinite(molarFlowMolPerSecond) || molarFlowMolPerSecond <= 0.0
                || !Double.isFinite(massFlowKgPerSecond) || massFlowKgPerSecond <= 0.0
                || !Double.isFinite(temperatureKelvin) || temperatureKelvin <= 0.0
                || !Double.isFinite(pressurePascal) || pressurePascal <= 0.0
                || !Double.isFinite(vaporMoleFraction) || vaporMoleFraction < 0.0 || vaporMoleFraction > 1.0) {
            throw new IllegalArgumentException("V3 stream properties must be finite and physically positive");
        }
        moleFractions = List.copyOf(Objects.requireNonNull(moleFractions, "moleFractions"));
        if (moleFractions.isEmpty() || moleFractions.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException("V3 stream composition exceeds the bounded public axis");
        }
        double moleSum = 0.0;
        double massSum = 0.0;
        for (ComponentFraction fraction : moleFractions) {
            ComponentFraction checked = Objects.requireNonNull(fraction, "moleFraction");
            moleSum += checked.moleFraction();
            massSum += checked.massFraction();
        }
        if (!Double.isFinite(moleSum) || Math.abs(moleSum - 1.0) > 1.0e-8
                || !Double.isFinite(massSum) || Math.abs(massSum - 1.0) > 1.0e-8) {
            throw new IllegalArgumentException("V3 stream mole and mass fractions must each sum to one");
        }
    }

    /** One public-axis composition row, including retained exact-zero components where applicable. */
    public record ComponentFraction(String componentId, double moleFraction, double massFraction) {
        public ComponentFraction {
            componentId = identifier(componentId, "componentId");
            if (!Double.isFinite(moleFraction) || moleFraction < 0.0 || moleFraction > 1.0) {
                throw new IllegalArgumentException("V3 component mole fraction must be finite and within zero to one");
            }
            if (!Double.isFinite(massFraction) || massFraction < 0.0 || massFraction > 1.0) {
                throw new IllegalArgumentException("V3 component mass fraction must be finite and within zero to one");
            }
        }
    }

    static List<V3ColumnStreamProperties> fromAccepted(
            V3ColumnProblem problem, V3DryMeshState state, V3PengRobinsonThermo thermo) {
        thermo = Objects.requireNonNull(thermo, "thermo");
        return fromAccepted(problem, state, thermo::componentMolecularWeightKgPerMol);
    }

    static List<V3ColumnStreamProperties> fromAccepted(
            V3ColumnProblem problem, V3DryMeshState state, double[] molecularWeightsKgPerMol) {
        molecularWeightsKgPerMol = Objects.requireNonNull(
                molecularWeightsKgPerMol, "molecularWeightsKgPerMol").clone();
        if (molecularWeightsKgPerMol.length != problem.input().componentBasis().componentCount()) {
            throw new IllegalArgumentException("V3 molecular-weight axis differs from the public component basis");
        }
        for (double molecularWeight : molecularWeightsKgPerMol) {
            if (!Double.isFinite(molecularWeight) || molecularWeight <= 0.0) {
                throw new IllegalArgumentException("V3 molecular weights must be finite and positive");
            }
        }
        double[] weights = molecularWeightsKgPerMol;
        return fromAccepted(problem, state, component -> weights[component]);
    }

    private static List<V3ColumnStreamProperties> fromAccepted(
            V3ColumnProblem problem, V3DryMeshState state, IntToDoubleFunction molecularWeight) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(state, "state");
        molecularWeight = Objects.requireNonNull(molecularWeight, "molecularWeight");
        V3ColumnTopology topology = problem.topology();
        if (!topology.hasLiquidPhase(topology.condenserNode())) {
            throw new IllegalArgumentException("V3 presentation streams require an accepted condenser liquid phase");
        }
        double reflux = specification(problem.input(), V3ColumnSpecification.OrganicRefluxRatio.class).ratio();
        double liquidProductScale = 1.0 / (1.0 + reflux);
        if (!Double.isFinite(liquidProductScale) || liquidProductScale <= 0.0) {
            throw new IllegalArgumentException("V3 reflux ratio cannot form a finite liquid product scale");
        }
        List<V3ColumnStreamProperties> streams = new ArrayList<>(MAX_STREAMS);
        if (topology.hasVaporPhase(topology.condenserNode())) {
            streams.add(vaporStream(problem, state, molecularWeight, topology.condenserNode(),
                    "overhead_vapor", "Overhead vapor", "VAPOR"));
        } else if (problem.hasAllVaporWaterCondenser()) {
            streams.add(waterVaporProduct(problem, state));
        }
        streams.add(stream(problem, state, molecularWeight, topology.condenserNode(), true, liquidProductScale,
                "distillate_liquid", "Liquid distillate", "LIQUID"));
        for (V3SideDrawSpec draw : problem.input().sideDraws()) {
            streams.add(stream(problem, state, molecularWeight, draw.trayNumber(), true,
                    problem.liquidWithdrawalFraction(state, draw.trayNumber()),
                    String.format(java.util.Locale.ROOT, "side_liquid_tray_%02d", draw.trayNumber()),
                    "Side draw (tray " + draw.trayNumber() + ")", "LIQUID"));
        }
        streams.add(stream(problem, state, molecularWeight, topology.reboilerNode(), true, 1.0,
                "bottoms_liquid", "Bottoms liquid", "LIQUID"));
        if (problem.hasSteamFeeds()) {
            double freeWater = freeWaterFlow(problem, state);
            if (freeWater > 0.0) streams.add(freeWaterStream(problem, state, freeWater));
        }
        return List.copyOf(streams);
    }

    private static V3ColumnStreamProperties vaporStream(
            V3ColumnProblem problem, V3DryMeshState state, IntToDoubleFunction molecularWeight, int node,
            String streamId, String displayName, String phase) {
        if (!problem.hasSteamFeeds()) return stream(problem, state, molecularWeight, node, false, 1.0,
                streamId, displayName, phase);
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        double[] hydrocarbon = new double[active.publicBasis().componentCount()];
        double hydrocarbonMass = 0.0;
        double hydrocarbonTotal = 0.0;
        for (int component = 0; component < active.componentCount(); component++) {
            int publicComponent = active.publicIndex(component);
            hydrocarbon[publicComponent] = state.vaporFlow(node, component);
            hydrocarbonTotal += hydrocarbon[publicComponent];
            hydrocarbonMass += hydrocarbon[publicComponent] * molecularWeight.applyAsDouble(publicComponent);
        }
        double water = waterVaporFlow(problem, state, node, hydrocarbonTotal);
        double total = hydrocarbonTotal + water;
        double totalMass = hydrocarbonMass + water * V3WaterProperties.MOLAR_MASS_KG_PER_MOL;
        // H2O belongs to the molecular vapor mixture here. It is deliberately absent from every
        // hydrocarbon-liquid composition; condensed water is published by freeWaterStream instead.
        List<ComponentFraction> fractions = new ArrayList<>(hydrocarbon.length + 1);
        for (int component = 0; component < hydrocarbon.length; component++) {
            fractions.add(new ComponentFraction(active.publicBasis().componentId(component), hydrocarbon[component] / total,
                    hydrocarbon[component] * molecularWeight.applyAsDouble(component) / totalMass));
        }
        fractions.add(new ComponentFraction("h2o", water / total,
                water * V3WaterProperties.MOLAR_MASS_KG_PER_MOL / totalMass));
        return new V3ColumnStreamProperties(streamId, displayName, phase, total, totalMass,
                state.temperatureKelvin(node), problem.nodePressurePascal(node), 1.0, fractions);
    }

    private static V3ColumnStreamProperties freeWaterStream(V3ColumnProblem problem, V3DryMeshState state, double flow) {
        int node = problem.topology().condenserNode();
        return new V3ColumnStreamProperties("free_water", "Free water (drum)", "LIQUID", flow,
                flow * V3WaterProperties.MOLAR_MASS_KG_PER_MOL, state.temperatureKelvin(node),
                problem.nodePressurePascal(node), 0.0, List.of(new ComponentFraction("h2o", 1.0, 1.0)));
    }

    private static V3ColumnStreamProperties waterVaporProduct(V3ColumnProblem problem, V3DryMeshState state) {
        int node = problem.topology().condenserNode();
        double flow = problem.waterVaporFlowMolPerSecond(1);
        return new V3ColumnStreamProperties("overhead_vapor", "Overhead steam", "VAPOR", flow,
                flow * V3WaterProperties.MOLAR_MASS_KG_PER_MOL, state.temperatureKelvin(node),
                problem.nodePressurePascal(node), 1.0, List.of(new ComponentFraction("h2o", 1.0, 1.0)));
    }

    private static double freeWaterFlow(V3ColumnProblem problem, V3DryMeshState state) {
        if (!problem.hasFreeWaterCondenser()) return 0.0;
        double water = problem.waterVaporFlowMolPerSecond(1);
        return problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE
                ? water - problem.waterVaporSlipCoefficient()
                        * hydrocarbonVaporTotal(state, problem.topology().condenserNode())
                : water;
    }

    private static double waterVaporFlow(V3ColumnProblem problem, V3DryMeshState state, int node, double hydrocarbon) {
        if (node != problem.topology().condenserNode()) return problem.waterVaporFlowMolPerSecond(node);
        return switch (problem.waterCondenserRegime()) {
            case NONE -> 0.0;
            case ALL_VAPOR -> problem.waterVaporFlowMolPerSecond(1);
            case FREE_WATER -> problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE
                    ? problem.waterVaporSlipCoefficient() * hydrocarbon : 0.0;
        };
    }

    private static double hydrocarbonVaporTotal(V3DryMeshState state, int node) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) total += state.vaporFlow(node, component);
        return total;
    }

    private static V3ColumnStreamProperties stream(
            V3ColumnProblem problem,
            V3DryMeshState state,
            IntToDoubleFunction molecularWeight,
            int node,
            boolean liquid,
            double flowScale,
            String streamId,
            String displayName,
            String phase) {
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        double[] publicFlows = new double[active.publicBasis().componentCount()];
        double[] publicMassFlows = new double[publicFlows.length];
        for (int component = 0; component < active.componentCount(); component++) {
            int publicComponent = active.publicIndex(component);
            publicFlows[publicComponent] = flowScale * (liquid
                    ? state.liquidFlow(node, component) : state.vaporFlow(node, component));
            publicMassFlows[publicComponent] = publicFlows[publicComponent]
                    * molecularWeight.applyAsDouble(publicComponent);
        }
        double total = 0.0;
        double totalMass = 0.0;
        for (double flow : publicFlows) total += flow;
        for (double massFlow : publicMassFlows) totalMass += massFlow;
        if (!Double.isFinite(total) || total <= 0.0 || !Double.isFinite(totalMass) || totalMass <= 0.0) {
            throw new IllegalArgumentException("Accepted V3 stream has no positive physical flow");
        }
        List<ComponentFraction> fractions = new ArrayList<>(publicFlows.length);
        for (int component = 0; component < publicFlows.length; component++) {
            fractions.add(new ComponentFraction(active.publicBasis().componentId(component), publicFlows[component] / total,
                    publicMassFlows[component] / totalMass));
        }
        return new V3ColumnStreamProperties(streamId, displayName, phase, total, totalMass, state.temperatureKelvin(node),
                problem.nodePressurePascal(node), liquid ? 0.0 : 1.0, fractions);
    }

    private static <S extends V3ColumnSpecification> S specification(
            V3ColumnInput input, Class<S> type) {
        for (V3ColumnSpecification specification : input.specifications()) {
            if (type.isInstance(specification)) return type.cast(specification);
        }
        throw new IllegalArgumentException("Missing V3 specification " + type.getSimpleName());
    }

    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (!value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}")) {
            throw new IllegalArgumentException(name + " must be a stable identifier");
        }
        return value;
    }

    private static String boundedText(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is outside the bounded stream contract");
        }
        return value;
    }
}
