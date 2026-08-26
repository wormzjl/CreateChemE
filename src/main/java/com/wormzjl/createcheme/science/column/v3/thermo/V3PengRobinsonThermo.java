package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.Objects;

/** Immutable V3 hydrocarbon PR78 façade backed by a solve-local one-way property-session adapter. */
public final class V3PengRobinsonThermo implements V3ThermoModel {
    private final V3NextgenPrSession session;

    private V3PengRobinsonThermo(V3NextgenPrSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** Resolves one registered hydrocarbon package while preserving its declared public component order. */
    public static V3PengRobinsonThermo fromRegisteredPackage(String packageId) {
        return new V3PengRobinsonThermo(V3NextgenPrSession.registeredPackage(packageId));
    }

    public String packageId() {
        return session.packageId();
    }

    public String datasetRevision() {
        return session.datasetRevision();
    }

    /** Resolves the registered dry-hydrocarbon assay without exposing the legacy feed object. */
    public V3CrudeFeed crudeFeed(String assayId) {
        return session.crudeFeed(assayId);
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
