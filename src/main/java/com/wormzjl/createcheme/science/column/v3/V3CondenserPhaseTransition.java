package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.Objects;

/** Request-local, material-conserving condenser flash projection; never an accepted column result. */
final class V3CondenserPhaseTransition {
    private V3CondenserPhaseTransition() {}

    /**
     * Rebuilds the condenser topology from its incoming overhead while preserving every other node.
     * The returned problem has identity support: the caller must derive a new frozen support and
     * solve/audit the coupled column, including its changed reflux. No solved-flow floor is imposed.
     */
    static Prepared prepare(V3ColumnProblem source, V3DryMeshState state,
                            V3ThermoModel thermo, V3SolveControl control) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(thermo, "thermo");
        Objects.requireNonNull(control, "control");
        if (!source.topology().hasLiquidPhase(0) || state.nodeCount() != source.topology().nodeCount()
                || state.componentCount() != source.activeComponentBasis().componentCount()
                || !source.input().componentBasis().equals(thermo.componentBasis())) {
            throw new IllegalArgumentException("Condenser transition does not match its source problem");
        }
        control.checkpoint();
        double[] overhead = new double[source.input().componentBasis().componentCount()];
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            double flow = state.vaporFlow(1, component);
            overhead[source.activeComponentBasis().publicIndex(component)] = flow;
            total += flow;
        }
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("Condenser transition has no finite incoming overhead");
        }
        double totalPressure = source.nodePressurePascal(0);
        double hydrocarbonPressure = totalPressure;
        if (source.hasFreeWaterCondenser()
                && source.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.LIQUID_ONLY) {
            // A liquid-only candidate has no hydrocarbon vapor flow from which to infer water
            // slip, but an existing water boot still fixes the vapor pressure at saturation.
            hydrocarbonPressure -= V3WaterProperties.saturationPressurePascal(state.temperatureKelvin(0));
        } else if (source.hasSteamFeeds()) {
            double vaporWater = source.waterCondenserSplit(state).vaporFlowMolPerSecond();
            hydrocarbonPressure = totalPressure * total / (total + vaporWater);
        }
        if (!Double.isFinite(hydrocarbonPressure) || hydrocarbonPressure <= 0.0) {
            throw new IllegalArgumentException("Condenser transition has no finite positive hydrocarbon partial pressure");
        }
        V3FlashResult flash = thermo.flashTP(state.temperatureKelvin(0), hydrocarbonPressure,
                overhead, thermo.newWorkspace());
        control.checkpoint();
        if (flash.phase() == V3FeedPhase.VAPOR) {
            throw new IllegalArgumentException("Condenser transition requires liquid for the specified organic reflux");
        }
        V3CondenserPhaseBranch branch = flash.phase() == V3FeedPhase.LIQUID
                ? V3CondenserPhaseBranch.LIQUID_ONLY : V3CondenserPhaseBranch.TWO_PHASE;
        V3ColumnProblem target = V3ColumnProblemResolver.resolve(source.input(), branch);
        double[] x = flash.liquidComposition();
        double[] y = flash.vaporComposition();
        if (x.length != overhead.length || (branch == V3CondenserPhaseBranch.TWO_PHASE && y.length != overhead.length)) {
            throw new IllegalArgumentException("Condenser flash returned a different component basis");
        }
        double[][] liquid = new double[state.nodeCount()][state.componentCount()];
        double[][] vapor = new double[state.nodeCount()][state.componentCount()];
        double[] temperatures = new double[state.nodeCount()];
        for (int node = 0; node < state.nodeCount(); node++) {
            control.checkpoint();
            temperatures[node] = state.temperatureKelvin(node);
            for (int component = 0; component < state.componentCount(); component++) {
                if (node != 0) {
                    liquid[node][component] = state.liquidFlow(node, component);
                    vapor[node][component] = state.vaporFlow(node, component);
                    continue;
                }
                int publicComponent = source.activeComponentBasis().publicIndex(component);
                double incoming = overhead[publicComponent];
                if (incoming == 0.0) continue; // A removed source point is reconsidered by the new support derivation.
                if (branch == V3CondenserPhaseBranch.LIQUID_ONLY) {
                    liquid[0][component] = incoming;
                    continue;
                }
                double liquidWeight = (1.0 - flash.vaporFraction()) * x[publicComponent];
                double vaporWeight = flash.vaporFraction() * y[publicComponent];
                double weight = liquidWeight + vaporWeight;
                if (!Double.isFinite(weight) || !(liquidWeight > 0.0) || !(vaporWeight > 0.0)) {
                    throw new IllegalArgumentException("Condenser flash split is not representable in positive log-flow coordinates");
                }
                // Use the flash partition, conserving each incoming component exactly to rounding.
                // Subtract the smaller phase to avoid cancellation of a physically tiny positive phase.
                if (vaporWeight <= liquidWeight) {
                    vapor[0][component] = incoming * (vaporWeight / weight);
                    liquid[0][component] = incoming - vapor[0][component];
                } else {
                    liquid[0][component] = incoming * (liquidWeight / weight);
                    vapor[0][component] = incoming - liquid[0][component];
                }
                if (!(liquid[0][component] > 0.0) || !(vapor[0][component] > 0.0)) {
                    throw new IllegalArgumentException("Condenser flash split underflowed a positive phase flow");
                }
            }
        }
        return new Prepared(target, new V3DryMeshState(target.topology(), state.componentCount(), liquid, vapor, temperatures),
                flash.phase(), flash.vaporFraction());
    }

    record Prepared(V3ColumnProblem problem, V3DryMeshState seed, V3FeedPhase phase, double vaporFraction) {
        Prepared {
            Objects.requireNonNull(problem, "problem");
            Objects.requireNonNull(seed, "seed");
            Objects.requireNonNull(phase, "phase");
        }
    }
}
