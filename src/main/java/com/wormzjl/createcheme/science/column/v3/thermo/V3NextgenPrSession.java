package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.column.nextgen.CharacterizedFeed;
import com.wormzjl.createcheme.science.column.nextgen.ColumnModelRegistry;
import com.wormzjl.createcheme.science.column.nextgen.ColumnThermoPackage;
import com.wormzjl.createcheme.science.column.nextgen.ComponentBasis;
import com.wormzjl.createcheme.science.column.nextgen.ComponentDescriptor;
import com.wormzjl.createcheme.science.column.nextgen.NextPengRobinsonKernel;
import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One-way V3 adapter over the existing immutable property package and PR arithmetic.
 *
 * <p>No next-generation input, outcome, warm-state, cache, or solver type crosses this boundary.
 * The mutable kernel workspace belongs to the V3 caller through {@link V3ThermoWorkspace}.</p>
 */
final class V3NextgenPrSession {
    private final ColumnThermoPackage propertyPackage;
    private final NextPengRobinsonKernel kernel;
    private final V3ComponentBasis componentBasis;

    private V3NextgenPrSession(ColumnThermoPackage propertyPackage) {
        this.propertyPackage = Objects.requireNonNull(propertyPackage, "propertyPackage");
        this.kernel = new NextPengRobinsonKernel(propertyPackage);
        ComponentBasis nextBasis = propertyPackage.basis();
        List<String> identifiers = new ArrayList<>(nextBasis.hydrocarbonCount());
        for (int component = 0; component < nextBasis.hydrocarbonCount(); component++) {
            identifiers.add(nextBasis.hydrocarbon(component).id());
        }
        this.componentBasis = new V3ComponentBasis(identifiers);
    }

    static V3NextgenPrSession registeredPackage(String packageId) {
        return new V3NextgenPrSession(ColumnModelRegistry.require(packageId));
    }

    String packageId() { return propertyPackage.packageId(); }
    String datasetRevision() { return propertyPackage.datasetRevision(); }
    V3ComponentBasis componentBasis() { return componentBasis; }
    int componentCount() { return kernel.componentCount(); }
    double componentMolecularWeightKgPerMol(int publicComponent) {
        if (publicComponent < 0 || publicComponent >= componentBasis.componentCount()) {
            throw new IllegalArgumentException("V3 component molecular-weight index is outside the public basis");
        }
        return propertyPackage.basis().hydrocarbon(publicComponent).molecularWeightKgPerMol();
    }
    Session newSession() { return new Session(kernel); }

    V3CrudeFeed crudeFeed(String assayId) {
        CharacterizedFeed source = propertyPackage.feedForAssay(assayId);
        double[] sourceFractions = source.moleFractions();
        double[] hydrocarbons = new double[componentCount()];
        System.arraycopy(sourceFractions, 0, hydrocarbons, 0, hydrocarbons.length);
        return new V3CrudeFeed(packageId(), assayId, componentBasis, hydrocarbons);
    }

    void wilsonK(double temperatureKelvin, double pressurePascal, double[] output) {
        try {
            kernel.wilsonK(temperatureKelvin, pressurePascal, output);
        } catch (IllegalArgumentException exception) {
            throw new V3ThermoException(V3ThermoException.Code.DOMAIN, null, "V3 Wilson K input is outside the property domain", exception);
        }
    }

    void evaluate(
            double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase, Session session) {
        try {
            kernel.evaluate(temperatureKelvin, pressurePascal, composition,
                    phase == V3Phase.LIQUID ? NextPengRobinsonKernel.Root.LIQUID : NextPengRobinsonKernel.Root.VAPOR,
                    session.workspace, session.evaluation(phase));
        } catch (IllegalArgumentException exception) {
            throw new V3ThermoException(V3ThermoException.Code.DOMAIN, phase,
                    "V3 Peng-Robinson input is outside the property domain", exception);
        } catch (IllegalStateException exception) {
            throw new V3ThermoException(V3ThermoException.Code.EOS_ROOT_FAILURE, phase,
                    "V3 Peng-Robinson could not select a physical phase root", exception);
        }
    }

    double idealGasMolarEnthalpy(double temperatureKelvin, double[] normalizedComposition) {
        if (normalizedComposition.length != componentCount()) throw new IllegalArgumentException("V3 composition dimension differs");
        double enthalpy = 0.0;
        for (int component = 0; component < componentCount(); component++) {
            ComponentDescriptor descriptor = propertyPackage.basis().hydrocarbon(component);
            enthalpy += normalizedComposition[component] * descriptor.idealGasEnthalpy(temperatureKelvin);
        }
        return enthalpy;
    }

    static final class Session {
        private final NextPengRobinsonKernel.Workspace workspace;
        private final NextPengRobinsonKernel.Evaluation liquid;
        private final NextPengRobinsonKernel.Evaluation vapor;

        private Session(NextPengRobinsonKernel kernel) {
            workspace = kernel.newWorkspace();
            liquid = kernel.newEvaluation();
            vapor = kernel.newEvaluation();
        }

        private NextPengRobinsonKernel.Evaluation evaluation(V3Phase phase) {
            return phase == V3Phase.LIQUID ? liquid : vapor;
        }

        double logFugacityCoefficient(V3Phase phase, int component) {
            return evaluation(phase).logFugacityCoefficient(component);
        }

        double[] logFugacityCoefficients(V3Phase phase) {
            return evaluation(phase).logFugacityCoefficients();
        }

        double compressibilityFactor(V3Phase phase) { return evaluation(phase).compressibility(); }
        double residualEnthalpyJoulesPerMol(V3Phase phase) { return evaluation(phase).residualEnthalpyJoulesPerMol(); }
        int physicalRootCount(V3Phase phase) { return evaluation(phase).physicalRootCount(); }
        double rootSeparation(V3Phase phase) { return evaluation(phase).rootSeparation(); }
        void clear() { workspace.clear(); }
    }
}
