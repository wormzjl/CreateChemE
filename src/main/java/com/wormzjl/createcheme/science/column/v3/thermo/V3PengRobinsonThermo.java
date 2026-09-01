package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.Objects;

/** Immutable V3 hydrocarbon PR78 façade backed by a solve-local one-way property-session adapter. */
public final class V3PengRobinsonThermo implements V3ThermoModel {
    private static final Runnable NO_CHECKPOINT = () -> { };
    private final V3PengRobinsonSession session;

    private V3PengRobinsonThermo(V3PengRobinsonSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** Resolves one registered hydrocarbon package while preserving its declared public component order. */
    public static V3PengRobinsonThermo fromRegisteredPackage(String packageId) {
        return new V3PengRobinsonThermo(V3PengRobinsonSession.registeredPackage(packageId));
    }

    public String packageId() {
        return session.packageId();
    }

    public String datasetRevision() {
        return session.datasetRevision();
    }

    /** Returns immutable, non-blocking dataset evidence for audits and solver diagnostics. */
    public java.util.List<String> advisoryEvidence() {
        return session.advisoryEvidence();
    }

    /** Returns the inclusive lower temperature bound declared by the selected property package. */
    public double minimumTemperatureKelvin() {
        return session.minimumTemperatureKelvin();
    }

    /** Returns the inclusive upper temperature bound declared by the selected property package. */
    public double maximumTemperatureKelvin() {
        return session.maximumTemperatureKelvin();
    }

    /** Returns the inclusive lower absolute-pressure bound declared by the selected property package. */
    public double minimumPressurePascal() {
        return session.minimumPressurePascal();
    }

    /** Returns the inclusive upper absolute-pressure bound declared by the selected property package. */
    public double maximumPressurePascal() {
        return session.maximumPressurePascal();
    }

    /** Resolves the registered dry-hydrocarbon assay without exposing mutable property-package data. */
    public V3CrudeFeed crudeFeed(String assayId) {
        return session.crudeFeed(assayId);
    }

    /** Returns the registered public-axis molecular weight used for accepted mass-composition reporting. */
    public double componentMolecularWeightKgPerMol(int publicComponent) {
        return session.componentMolecularWeightKgPerMol(publicComponent);
    }

    @Override
    public V3ComponentBasis componentBasis() {
        return session.componentBasis();
    }

    @Override
    public V3ThermoWorkspace newWorkspace() {
        return new V3ThermoWorkspace(session);
    }

    @Override
    public V3FugacityResult fugacity(
            double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
            V3ThermoWorkspace workspace) {
        phase = Objects.requireNonNull(phase, "phase");
        normalizeInto(composition, workspace.normalizedOverall, workspace);
        evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, phase, workspace);
        return new V3FugacityResult(phase, workspace.prSession.logFugacityCoefficients(phase),
                workspace.prSession.compressibilityFactor(phase), phaseMolarEnthalpy(temperatureKelvin,
                workspace.normalizedOverall, phase, workspace), workspace.prSession.physicalRootCount(phase),
                workspace.prSession.rootSeparation(phase));
    }

    @Override
    public double molarEnthalpy(
            double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
            V3ThermoWorkspace workspace) {
        phase = Objects.requireNonNull(phase, "phase");
        normalizeInto(composition, workspace.normalizedOverall, workspace);
        evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, phase, workspace);
        return phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, phase, workspace);
    }

    @Override
    public V3FlashResult flashTP(
            double temperatureKelvin, double pressurePascal, double[] overallComposition, V3ThermoWorkspace workspace) {
        normalizeInto(overallComposition, workspace.normalizedOverall, workspace);
        return V3FeedFlash.resolve(this, temperatureKelvin, pressurePascal, workspace);
    }

    /**
     * Opts into reference-validated phase truncation. The existing four-argument method remains
     * unrestricted for independent audits. Zero cutoff follows exactly that original numerical path.
     */
    public V3FlashResult flashTP(double temperatureKelvin, double pressurePascal, double[] overallComposition,
                                 V3TraceTruncationPolicy policy, V3ThermoWorkspace workspace) {
        return flashTP(temperatureKelvin, pressurePascal, overallComposition, policy, workspace, NO_CHECKPOINT);
    }

    /**
     * Caller-confined, bounded phase truncation with cooperative cancellation checkpoints.
     * A failed unrestricted reference or a cancellation is never converted into approximate success.
     * Returned evidence distinguishes disabled/identity/reduced/fallback paths and counts their iterations.
     */
    public V3FlashResult flashTP(double temperatureKelvin, double pressurePascal, double[] overallComposition,
                                 V3TraceTruncationPolicy policy, V3ThermoWorkspace workspace, Runnable checkpoint) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(checkpoint, "checkpoint");
        normalizeInto(overallComposition, workspace.normalizedOverall, workspace);
        // Preserve callback provenance even if a caller happens to throw the same exception
        // type as an EOS failure. It must not become an approximate-solve fallback.
        Runnable guardedCheckpoint = () -> {
            try {
                checkpoint.run();
            } catch (V3ThermoException callbackFailure) {
                throw new FlashCheckpointFailure(callbackFailure);
            }
        };
        try {
            return V3TruncatedFlash.resolve(this, temperatureKelvin, pressurePascal, workspace, policy, guardedCheckpoint);
        } catch (FlashCheckpointFailure callbackFailure) {
            throw callbackFailure.original;
        }
    }

    private static final class FlashCheckpointFailure extends RuntimeException {
        private final V3ThermoException original;

        private FlashCheckpointFailure(V3ThermoException original) {
            super(null, original, false, false);
            this.original = original;
        }
    }

    void evaluateInto(
            double temperatureKelvin, double pressurePascal, double[] normalizedComposition, V3Phase phase,
            V3ThermoWorkspace workspace) {
        requireWorkspace(workspace);
        session.evaluate(temperatureKelvin, pressurePascal, normalizedComposition, phase, workspace.prSession);
    }

    void wilsonK(double temperatureKelvin, double pressurePascal, double[] output, V3ThermoWorkspace workspace) {
        requireWorkspace(workspace);
        if (output.length != componentBasis().componentCount()) {
            throw new IllegalArgumentException("V3 Wilson K workspace dimension differs from the component basis");
        }
        session.wilsonK(temperatureKelvin, pressurePascal, output);
    }

    double logFugacityCoefficient(V3Phase phase, int component, V3ThermoWorkspace workspace) {
        requireWorkspace(workspace);
        return workspace.prSession.logFugacityCoefficient(phase, component);
    }

    double phaseMolarEnthalpy(
            double temperatureKelvin, double[] normalizedComposition, V3Phase phase, V3ThermoWorkspace workspace) {
        requireWorkspace(workspace);
        return session.idealGasMolarEnthalpy(temperatureKelvin, normalizedComposition)
                + workspace.prSession.residualEnthalpyJoulesPerMol(phase);
    }

    private void normalizeInto(double[] composition, double[] output, V3ThermoWorkspace workspace) {
        requireWorkspace(workspace);
        if (composition == null || composition.length != output.length) {
            throw new IllegalArgumentException("V3 hydrocarbon composition dimension differs from the component basis");
        }
        double total = 0.0;
        for (double value : composition) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("V3 hydrocarbon composition must be finite and nonnegative");
            }
            total += value;
        }
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("V3 hydrocarbon composition has no material");
        }
        for (int component = 0; component < output.length; component++) output[component] = composition[component] / total;
    }

    private void requireWorkspace(V3ThermoWorkspace workspace) {
        workspace = Objects.requireNonNull(workspace, "workspace");
        workspace.requireOwner(session);
    }
}
