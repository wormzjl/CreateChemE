package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.Objects;

/**
 * One registered V3 property package and its allocation-free Peng-Robinson arithmetic.
 *
 * <p>The mutable kernel workspace belongs to the V3 caller through {@link V3ThermoWorkspace}; no state is shared
 * between concurrent solves.</p>
 */
final class V3PengRobinsonSession {
    private final V3PropertyPackage propertyPackage;
    private final V3PengRobinsonKernel kernel;
    private final V3ComponentBasis componentBasis;

    private V3PengRobinsonSession(V3PropertyPackage propertyPackage) {
        this.propertyPackage = Objects.requireNonNull(propertyPackage, "propertyPackage");
        this.kernel = new V3PengRobinsonKernel(propertyPackage);
        this.componentBasis = propertyPackage.componentBasis();
    }

    static V3PengRobinsonSession registeredPackage(String packageId) {
        return new V3PengRobinsonSession(V3PropertyPackageRegistry.require(packageId));
    }

    String packageId() { return propertyPackage.packageId(); }
    String datasetRevision() { return propertyPackage.datasetRevision(); }
    V3ComponentBasis componentBasis() { return componentBasis; }
    int componentCount() { return kernel.componentCount(); }

    double componentMolecularWeightKgPerMol(int publicComponent) {
        if (publicComponent < 0 || publicComponent >= componentBasis.componentCount()) {
            throw new IllegalArgumentException("V3 component molecular-weight index is outside the public basis");
        }
        return propertyPackage.component(publicComponent).molecularWeightKgPerMol();
    }

    Session newSession() { return new Session(kernel); }

    V3CrudeFeed crudeFeed(String assayId) {
        return propertyPackage.crudeFeed(assayId);
    }

    void wilsonK(double temperatureKelvin, double pressurePascal, double[] output) {
        try {
            kernel.wilsonK(temperatureKelvin, pressurePascal, output);
        } catch (IllegalArgumentException exception) {
            throw new V3ThermoException(V3ThermoException.Code.DOMAIN, null,
                    "V3 Wilson K input is outside the property domain", exception);
        }
    }

    void evaluate(
            double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase, Session session) {
        try {
            kernel.evaluate(temperatureKelvin, pressurePascal, composition,
                    phase == V3Phase.LIQUID ? V3PengRobinsonKernel.Root.LIQUID : V3PengRobinsonKernel.Root.VAPOR,
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
            enthalpy += normalizedComposition[component]
                    * propertyPackage.component(component).idealGasEnthalpy(temperatureKelvin);
        }
        return enthalpy;
    }

    static final class Session {
        private final V3PengRobinsonKernel.Workspace workspace;
        private final V3PengRobinsonKernel.Evaluation liquid;
        private final V3PengRobinsonKernel.Evaluation vapor;

        private Session(V3PengRobinsonKernel kernel) {
            workspace = kernel.newWorkspace();
            liquid = kernel.newEvaluation();
            vapor = kernel.newEvaluation();
        }

        private V3PengRobinsonKernel.Evaluation evaluation(V3Phase phase) {
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
