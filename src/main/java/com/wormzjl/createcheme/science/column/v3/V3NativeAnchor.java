package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.thermo.TraceTruncationPolicy;

/** Input-only, branch-conditioned native anchor. Each call owns its workspace.
 * Ordinary construction failures return a zero anchor and a separate availability
 * flag; cancellation always propagates. An anchor is never a certified label.
 *
 * <p>This is the anchor half of the bundled Transformer's input: the model was trained with these
 * eighty-five material-closed coordinates per node beside its own node features, so the production
 * arithmetic here must stay identical to the arithmetic the offline study measured. The offline copy
 * that trained and qualified the weights is {@code tools/hybrid-learning/java/V3HybridBaseline.java};
 * its revision string is recorded in the bundled model document as {@code baselineRevision}.</p>
 */
final class V3NativeAnchor {
    static final String REVISION = "native-material-closed-anchor-v1";
    private V3NativeAnchor() {}
    record Anchor(double[][] values, boolean available, String reason) {}

    static Anchor build(V3ColumnInput input, V3CondenserPhaseBranch branch, V3SolveControl control) {
        control.checkpoint();
        try {
            var nativeThermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            var problem = V3ColumnProblemResolver.resolve(input, branch);
            var thermo = new ControlledThermo(nativeThermo, control);
            var state = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace(),
                    V3ColumnInitializer.Mode.MATERIAL_CLOSED).state();
            control.checkpoint();
            var seed = V3NeuralSeed.capture(problem, state, nativeThermo.datasetRevision());
            double[][] values = V3FactorizedNeuralFeatures.targets(seed);
            for (double[] node : values) for (double value : node)
                if (!Double.isFinite(value)) throw new IllegalArgumentException("nonfinite native anchor");
            return new Anchor(values, true, "AVAILABLE");
        } catch (V3ThermoException | IllegalArgumentException declined) {
            control.checkpoint();
            return new Anchor(new double[input.stageCount() + 2][85], false,
                    declined.getClass().getSimpleName() + ": " + declined.getMessage());
        }
    }

    private record ControlledThermo(V3PengRobinsonThermo delegate, V3SolveControl control) implements V3ThermoModel {
        @Override public V3ComponentBasis componentBasis() { return delegate.componentBasis(); }
        @Override public V3ThermoWorkspace newWorkspace() { return delegate.newWorkspace(); }
        @Override public V3FugacityResult fugacity(double t, double p, double[] x, V3Phase phase, V3ThermoWorkspace w) {
            control.checkpoint(); var result = delegate.fugacity(t, p, x, phase, w); control.checkpoint(); return result;
        }
        @Override public double molarEnthalpy(double t, double p, double[] x, V3Phase phase, V3ThermoWorkspace w) {
            control.checkpoint(); double result = delegate.molarEnthalpy(t, p, x, phase, w); control.checkpoint(); return result;
        }
        @Override public V3FlashResult flashTP(double t, double p, double[] x, V3ThermoWorkspace w) {
            control.checkpoint();
            var result = delegate.flashTP(t, p, x, TraceTruncationPolicy.of(0), w, control::checkpoint);
            control.checkpoint(); return result;
        }
    }
}
