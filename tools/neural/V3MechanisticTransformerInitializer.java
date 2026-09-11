package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Offline frozen-network wrapper. Immutable weights may be shared; every
 * preparation, thermodynamic workspace, counter and callback is call-confined.
 * Prepared states remain guesses for the unchanged native corrector and audit.
 */
final class V3MechanisticTransformerInitializer implements V3NeuralInitializer {
    static final String REVISION = "transformer-material-completion-v1";
    private static final double MAXIMUM_PHASE_TOTAL_OVER_FEED = 1000.0;
    private final V3NeuralInitializer baseline;

    V3MechanisticTransformerInitializer(V3NeuralInitializer baseline) {
        this.baseline = Objects.requireNonNull(baseline, "baseline");
    }

    @Override public String modelId() { return REVISION; }

    @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
        return predict(input, control, ignored -> {});
    }

    /** The observer runs synchronously on the calling worker, including aborts. */
    Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control, Consumer<Evidence> observer) {
        Objects.requireNonNull(observer, "observer");
        Optional<V3NeuralSeed> raw = baseline.predict(input, control);
        if (raw.isEmpty()) {
            observer.accept(new Evidence("NO_RAW_PREDICTION", null, 0, 0, false));
            return raw;
        }
        return Optional.of(prepare(raw.orElseThrow(), control, observer));
    }

    record Evidence(String status, String detail, double preparationMillis, int propertyCalls, boolean prepared) {}

    static V3NeuralSeed prepare(V3NeuralSeed raw, V3SolveControl control, Consumer<Evidence> observer) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(observer, "observer");
        long started = System.nanoTime();
        String status = "ABORTED", detail = null;
        ControlledThermo guarded = null;
        try {
            control.checkpoint();
            if (!raw.input().steamFeeds().isEmpty() || !raw.input().sideDraws().isEmpty()
                    || Arrays.stream(raw.freeWater()).anyMatch(value -> value != 0.0)
                    || any(raw.wetTrays())) {
                status = "INAPPLICABLE";
                detail = "requires dry input with no side draws";
                return raw;
            }
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(raw.input().packageId());
            if (!thermo.datasetRevision().equals(raw.propertyRevision()))
                throw new IllegalArgumentException("seed property revision mismatch");
            var problem = V3ColumnProblemResolver.resolve(raw.input(), raw.branch());
            var state = raw.stateFor(problem);
            double[][] liquid = V3ColumnInitializer.flows(state, true);
            double[][] vapor = V3ColumnInitializer.flows(state, false);
            double[] temperatures = V3ColumnInitializer.temperatures(state);
            guarded = new ControlledThermo(thermo, control);
            double[][] ratios = V3ColumnInitializer.phaseRatios(
                    problem, guarded, guarded.newWorkspace(), liquid, vapor, temperatures);
            control.checkpoint();
            V3ColumnInitializer.solveMaterialBalances(problem, ratios, liquid, vapor);
            control.checkpoint();
            var candidate = new V3DryMeshState(problem.topology(), state.componentCount(),
                    liquid, vapor, temperatures, V3ColumnInitializer.freeWaterFlows(state));
            requireAdmissible(problem, candidate);
            V3NeuralSeed result = V3NeuralSeed.capture(problem, candidate, raw.propertyRevision());
            if (!Arrays.equals(raw.temperatures(), result.temperatures())
                    || !raw.input().equals(result.input()) || raw.branch() != result.branch())
                throw new IllegalStateException("material completion changed fixed seed coordinates");
            control.checkpoint();
            status = "PREPARED";
            return result;
        } catch (V3ThermoException | IllegalArgumentException decline) {
            status = "DECLINED";
            detail = decline.getClass().getSimpleName() + ": " + decline.getMessage();
            return raw;
        } catch (RuntimeException aborted) {
            // Includes caller cancellation and the calculator's neural-budget exception.
            // Neither becomes ordinary preprocessing failure or a new budget.
            detail = aborted.getClass().getSimpleName();
            throw aborted;
        } finally {
            observer.accept(new Evidence(status, detail, (System.nanoTime() - started) / 1e6,
                    guarded == null ? 0 : guarded.calls, status.equals("PREPARED")));
        }
    }

    private static boolean any(boolean[] values) {
        for (boolean value : values) if (value) return true;
        return false;
    }

    private static void requireAdmissible(V3ColumnProblem problem, V3DryMeshState candidate) {
        double feed = problem.activeComponentBasis().totalFeedFlowMolPerSecond();
        for (int node = 0; node < candidate.nodeCount(); node++) {
            double liquid = 0, vapor = 0;
            for (int component = 0; component < candidate.componentCount(); component++) {
                double l = candidate.liquidFlow(node, component), v = candidate.vaporFlow(node, component);
                if (!Double.isFinite(l) || !Double.isFinite(v)
                        || (problem.hasLiquidUnknown(node, component) ? l <= 0.0 : l != 0.0)
                        || (problem.hasVaporUnknown(node, component) ? v <= 0.0 : v != 0.0))
                    throw new IllegalArgumentException("completed flow conflicts with native phase structure");
                liquid += l;
                vapor += v;
            }
            if (!Double.isFinite(liquid) || !Double.isFinite(vapor)
                    || liquid / feed > MAXIMUM_PHASE_TOTAL_OVER_FEED
                    || vapor / feed > MAXIMUM_PHASE_TOTAL_OVER_FEED)
                throw new IllegalArgumentException("completed phase total exceeds decoder bound");
        }
        new V3DryMeshCoordinateMap(problem).encode(candidate);
    }

    /** One owned thermodynamic boundary per call; no mutable workspace is shared. */
    private static final class ControlledThermo implements V3ThermoModel {
        private final V3ThermoModel delegate;
        private final V3SolveControl control;
        private int calls;

        private ControlledThermo(V3ThermoModel delegate, V3SolveControl control) {
            this.delegate = delegate;
            this.control = control;
        }

        @Override public V3ComponentBasis componentBasis() { return delegate.componentBasis(); }
        @Override public V3ThermoWorkspace newWorkspace() { return delegate.newWorkspace(); }

        @Override public V3FugacityResult fugacity(double temperature, double pressure, double[] composition,
                V3Phase phase, V3ThermoWorkspace workspace) {
            control.checkpoint();
            calls++;
            V3FugacityResult result = delegate.fugacity(temperature, pressure, composition, phase, workspace);
            control.checkpoint();
            return result;
        }

        @Override public double molarEnthalpy(double temperature, double pressure, double[] composition,
                V3Phase phase, V3ThermoWorkspace workspace) {
            control.checkpoint();
            calls++;
            double result = delegate.molarEnthalpy(temperature, pressure, composition, phase, workspace);
            control.checkpoint();
            return result;
        }

        @Override public V3FlashResult flashTP(double temperature, double pressure, double[] composition,
                V3ThermoWorkspace workspace) {
            control.checkpoint();
            calls++;
            V3FlashResult result = delegate.flashTP(temperature, pressure, composition, workspace);
            control.checkpoint();
            return result;
        }
    }
}
