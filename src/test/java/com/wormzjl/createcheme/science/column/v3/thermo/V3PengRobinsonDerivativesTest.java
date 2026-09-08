package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Holds the analytic PR78 derivatives against differences of the values they claim to differentiate.
 *
 * <p>The steps below are chosen for the conditioning of each quantity rather than copied from one another. A
 * temperature step of a milli-kelvin leaves the truncation of a central difference of {@code ln phi} — which
 * grows as the square of the step — far below the tolerance, while keeping the cancellation of two
 * order-one logarithms at the {@code 1e-13} level; a mole-number step of {@code 1e-6} on a one-mole basis does
 * the same for the composition derivatives and stays well inside every component's own amount in these
 * fixtures. The enthalpy differences are the best conditioned of the three, because an enthalpy about a
 * 298.15 K datum is a large number whose derivative is also large.</p>
 *
 * <p>A finite difference is only a referee while it stays on one root. Around a fold in the cubic — where a
 * liquid root is about to vanish — a step of even a tenth of a kelvin moves the evaluation to the other
 * branch, and the difference then measures the jump between two branches rather than the slope of either. Each
 * comparison below therefore checks that the probes kept the base's root count and stayed near its
 * compressibility, and skips the state otherwise; the count of surviving comparisons is asserted so that a
 * fixture cannot quietly skip everything.</p>
 *
 * <p>Observed maxima at the time of writing, over both packages and both phases, each relative to the largest
 * magnitude of the same derivative object: {@code d ln phi/dT} 2.1e-9, {@code d ln phi/dn} 2.6e-8,
 * {@code dh/dT} 3.1e-10, partial molar enthalpy 9.5e-9, symmetry 2.2e-14, Gibbs--Duhem 6.9e-15, and the Euler
 * sum of the partial molar enthalpies 1.8e-15 of the molar enthalpy. The three identities are two to three
 * orders tighter than the finite-difference comparisons because nothing is differenced in them.</p>
 */
class V3PengRobinsonDerivativesTest {
    private static final double TEMPERATURE_STEP_KELVIN = 1.0e-3;
    private static final double MOLE_STEP = 1.0e-6;
    private static final double TOLERANCE = 1.0e-6;

    @ParameterizedTest
    @ValueSource(strings = {"createcheme:tjl19_dwsim", "createcheme:cdu17_tjl_acs2018"})
    void logFugacityTemperatureDerivativesMatchCentralDifferencesOnTheSameRoot(String packageId) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        int compared = 0;
        for (State state : states(thermo)) {
            for (V3Phase phase : V3Phase.values()) {
                V3FugacityDerivatives analytic = state.derivativesOrNull(thermo, phase);
                if (analytic == null || !state.probeKeepsRoot(thermo, phase, TEMPERATURE_STEP_KELVIN)) continue;
                double[] higher = state.logFugacity(thermo, phase, TEMPERATURE_STEP_KELVIN);
                double[] lower = state.logFugacity(thermo, phase, -TEMPERATURE_STEP_KELVIN);
                double scale = 0.0;
                for (int i = 0; i < higher.length; i++) {
                    scale = Math.max(scale, Math.abs(analytic.dLogFugacityCoefficientDT(i)));
                }
                for (int i = 0; i < higher.length; i++) {
                    double difference = (higher[i] - lower[i]) / (2.0 * TEMPERATURE_STEP_KELVIN);
                    assertEquals(difference, analytic.dLogFugacityCoefficientDT(i), TOLERANCE * scale,
                            packageId + " " + phase + " " + state + " component " + i);
                }
                compared++;
            }
        }
        assertTrue(compared >= 8, "too few single-branch comparisons: " + compared);
    }

    @ParameterizedTest
    @ValueSource(strings = {"createcheme:tjl19_dwsim", "createcheme:cdu17_tjl_acs2018"})
    void logFugacityCompositionDerivativesMatchCentralDifferences(String packageId) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        int compared = 0;
        for (State state : states(thermo)) {
            for (V3Phase phase : V3Phase.values()) {
                V3FugacityDerivatives analytic = state.derivativesOrNull(thermo, phase);
                if (analytic == null) continue;
                int count = analytic.componentCount();
                double scale = 0.0;
                for (int i = 0; i < count; i++) {
                    for (int j = 0; j < count; j++) {
                        scale = Math.max(scale, Math.abs(analytic.dLogFugacityCoefficientDMoles(i, j)));
                    }
                }
                for (int j = 0; j < count; j++) {
                    if (!state.probeKeepsRootOnMoles(thermo, phase, j, MOLE_STEP)) continue;
                    double[] higher = state.logFugacityWithMoles(thermo, phase, j, MOLE_STEP);
                    double[] lower = state.logFugacityWithMoles(thermo, phase, j, -MOLE_STEP);
                    for (int i = 0; i < count; i++) {
                        double difference = (higher[i] - lower[i]) / (2.0 * MOLE_STEP);
                        assertEquals(difference, analytic.dLogFugacityCoefficientDMoles(i, j), TOLERANCE * scale,
                                packageId + " " + phase + " " + state + " d" + i + "/dn" + j);
                    }
                    compared++;
                }
            }
        }
        assertTrue(compared >= 100, "too few single-branch comparisons: " + compared);
    }

    @ParameterizedTest
    @ValueSource(strings = {"createcheme:tjl19_dwsim", "createcheme:cdu17_tjl_acs2018"})
    void phaseHeatCapacityAndPartialMolarEnthalpiesMatchCentralDifferences(String packageId) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        int compared = 0;
        for (State state : states(thermo)) {
            for (V3Phase phase : V3Phase.values()) {
                V3FugacityDerivatives analytic = state.derivativesOrNull(thermo, phase);
                if (analytic == null) continue;
                if (state.probeKeepsRoot(thermo, phase, TEMPERATURE_STEP_KELVIN)) {
                    double difference = (state.molarEnthalpy(thermo, phase, TEMPERATURE_STEP_KELVIN)
                            - state.molarEnthalpy(thermo, phase, -TEMPERATURE_STEP_KELVIN))
                            / (2.0 * TEMPERATURE_STEP_KELVIN);
                    assertEquals(difference, analytic.dMolarEnthalpyDTJoulesPerMolKelvin(),
                            TOLERANCE * Math.abs(difference), packageId + " " + phase + " " + state + " dh/dT");
                    compared++;
                }
                int count = analytic.componentCount();
                double scale = 0.0;
                for (int j = 0; j < count; j++) {
                    scale = Math.max(scale, Math.abs(analytic.partialMolarEnthalpyJoulesPerMol(j)));
                }
                for (int j = 0; j < count; j++) {
                    if (!state.probeKeepsRootOnMoles(thermo, phase, j, MOLE_STEP)) continue;
                    double difference = (state.totalEnthalpyWithMoles(thermo, phase, j, MOLE_STEP)
                            - state.totalEnthalpyWithMoles(thermo, phase, j, -MOLE_STEP)) / (2.0 * MOLE_STEP);
                    assertEquals(difference, analytic.partialMolarEnthalpyJoulesPerMol(j), TOLERANCE * scale,
                            packageId + " " + phase + " " + state + " partial molar enthalpy " + j);
                }
            }
        }
        assertTrue(compared >= 8, "too few single-branch comparisons: " + compared);
    }

    /**
     * The three identities a correct derivative bundle cannot avoid, and no finite difference is needed for.
     *
     * <p>Symmetry is the equality of the two mixed second derivatives of the Gibbs energy; Gibbs--Duhem is
     * that {@code ln phi} does not change when every mole number is scaled together; and the Euler sum ties
     * the partial molar enthalpies back to the molar enthalpy, which here also ties the temperature
     * derivatives of {@code ln phi} to the closed-form residual enthalpy, because the partial molar enthalpies
     * are computed from the former and the molar enthalpy from the latter.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {"createcheme:tjl19_dwsim", "createcheme:cdu17_tjl_acs2018"})
    void derivativeBundleSatisfiesSymmetryGibbsDuhemAndTheEulerEnthalpySum(String packageId) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        int checked = 0;
        for (State state : states(thermo)) {
            for (V3Phase phase : V3Phase.values()) {
                V3FugacityDerivatives analytic = state.derivativesOrNull(thermo, phase);
                if (analytic == null) continue;
                int count = analytic.componentCount();
                double scale = 0.0;
                double enthalpyScale = 0.0;
                for (int i = 0; i < count; i++) {
                    enthalpyScale = Math.max(enthalpyScale, Math.abs(analytic.partialMolarEnthalpyJoulesPerMol(i)));
                    for (int j = 0; j < count; j++) {
                        scale = Math.max(scale, Math.abs(analytic.dLogFugacityCoefficientDMoles(i, j)));
                    }
                }
                for (int i = 0; i < count; i++) {
                    double gibbsDuhem = 0.0;
                    for (int j = 0; j < count; j++) {
                        assertEquals(analytic.dLogFugacityCoefficientDMoles(i, j),
                                analytic.dLogFugacityCoefficientDMoles(j, i), 1.0e-11 * scale,
                                packageId + " " + phase + " " + state + " symmetry " + i + "," + j);
                        gibbsDuhem += state.moleFractions[j] * analytic.dLogFugacityCoefficientDMoles(i, j);
                    }
                    assertEquals(0.0, gibbsDuhem, 1.0e-11 * scale,
                            packageId + " " + phase + " " + state + " Gibbs-Duhem row " + i);
                }
                double euler = 0.0;
                for (int j = 0; j < count; j++) {
                    euler += state.moleFractions[j] * analytic.partialMolarEnthalpyJoulesPerMol(j);
                }
                assertEquals(analytic.molarEnthalpyJoulesPerMol(), euler, 1.0e-11 * enthalpyScale,
                        packageId + " " + phase + " " + state + " Euler enthalpy sum");
                checked++;
            }
        }
        assertTrue(checked >= 8, "too few evaluated states: " + checked);
    }

    /**
     * The rank-one mixing shortcut and the general quadratic rule agree where they must.
     *
     * <p>A package whose binary interactions are all zero takes the rank-one path, which replaces the
     * quadratic mixture sums by one dot product. To exercise the other branch on the same data the wrapper
     * below perturbs a single symmetric interaction pair by the smallest positive double, which the kernel
     * reads as "not all zero" while {@code 1 - k} rounds to exactly one, so the general path computes the same
     * quantities in a different order. What is left is summation order alone, and the two agree to it.</p>
     */
    @Test
    void rankOneMixingDerivativesMatchTheGeneralQuadraticPath() {
        V3PengRobinsonKernel rankOne = new V3PengRobinsonKernel(V3Cdu17TiaJuanaPackage.INSTANCE);
        V3PengRobinsonKernel general = new V3PengRobinsonKernel(new BarelyInteractingPackage());
        assertTrue(rankOne.usesRankOneMixing());
        assertFalse(general.usesRankOneMixing());
        double[] composition = V3Cdu17TiaJuanaPackage.INSTANCE
                .crudeFeed(V3Cdu17TiaJuanaPackage.ASSAY_ID).moleFractions();
        int count = composition.length;
        for (V3PengRobinsonKernel.Root root : V3PengRobinsonKernel.Root.values()) {
            V3PengRobinsonKernel.Derivatives first = rankOne.newDerivatives();
            V3PengRobinsonKernel.Derivatives second = general.newDerivatives();
            rankOne.evaluateDerivatives(480.0, 250_000.0, composition, root, rankOne.newWorkspace(), first);
            general.evaluateDerivatives(480.0, 250_000.0, composition, root, general.newWorkspace(), second);
            double scale = 0.0;
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < count; j++) scale = Math.max(scale, Math.abs(first.dLogPhiDn()[i][j]));
            }
            for (int i = 0; i < count; i++) {
                assertEquals(first.dLogPhiDt()[i], second.dLogPhiDt()[i], 1.0e-12 * Math.abs(first.dLogPhiDt()[i]),
                        root + " d ln phi/dT " + i);
                assertEquals(first.partialMolarResidualEnthalpy()[i], second.partialMolarResidualEnthalpy()[i],
                        1.0e-12 * Math.abs(first.partialMolarResidualEnthalpy()[i]), root + " partial molar " + i);
                for (int j = 0; j < count; j++) {
                    assertEquals(first.dLogPhiDn()[i][j], second.dLogPhiDn()[i][j], 1.0e-10 * scale,
                            root + " d ln phi/dn " + i + "," + j);
                }
            }
            assertEquals(first.dResidualEnthalpyDt(), second.dResidualEnthalpyDt(),
                    1.0e-12 * Math.abs(first.dResidualEnthalpyDt()), root + " residual heat capacity");
        }
    }

    /** A derivative request outside the package envelope fails exactly as the value request does. */
    @Test
    void derivativesRefuseTheSameStatesTheValuesRefuse() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] composition = new double[thermo.componentBasis().componentCount()];
        composition[3] = 0.5;
        composition[9] = 0.5;
        for (double[] outside : new double[][] {{901.0, 250_000.0}, {500.0, 2_100_000.0}, {500.0, 10_000.0}}) {
            assertThrows(V3ThermoException.class, () -> thermo.fugacity(
                    outside[0], outside[1], composition, V3Phase.LIQUID, thermo.newWorkspace()));
            assertThrows(V3ThermoException.class, () -> thermo.fugacityDerivatives(
                    outside[0], outside[1], composition, V3Phase.LIQUID, thermo.newWorkspace()));
        }
    }

    /** The value evaluation the derivative bundle carries is the very one {@link V3ThermoModel#fugacity} returns. */
    @ParameterizedTest
    @ValueSource(strings = {"createcheme:tjl19_dwsim", "createcheme:cdu17_tjl_acs2018"})
    void derivativeBundleCarriesTheSameValuesTheFugacityPathPublishes(String packageId) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        for (State state : states(thermo)) {
            for (V3Phase phase : V3Phase.values()) {
                V3FugacityDerivatives analytic = state.derivativesOrNull(thermo, phase);
                if (analytic == null) continue;
                V3FugacityResult value = thermo.fugacity(state.temperatureKelvin, state.pressurePascal,
                        state.moleFractions, phase, thermo.newWorkspace());
                for (int i = 0; i < analytic.componentCount(); i++) {
                    assertEquals(Double.doubleToRawLongBits(value.logFugacityCoefficient(i)),
                            Double.doubleToRawLongBits(analytic.logFugacityCoefficient(i)),
                            packageId + " " + phase + " " + state + " ln phi " + i);
                }
                assertEquals(Double.doubleToRawLongBits(value.molarEnthalpyJoulesPerMol()),
                        Double.doubleToRawLongBits(analytic.molarEnthalpyJoulesPerMol()),
                        packageId + " " + phase + " " + state + " molar enthalpy");
            }
        }
    }

    private static List<State> states(V3PengRobinsonThermo thermo) {
        int count = thermo.componentBasis().componentCount();
        List<State> states = new java.util.ArrayList<>();
        Random random = new Random(20260909L);
        for (double[] point : new double[][] {
                {340.0, 100_000.0}, {400.0, 250_000.0}, {470.0, 250_000.0}, {530.0, 500_000.0},
                {600.0, 250_000.0}, {650.0, 1_000_000.0}}) {
            double[] fractions = new double[count];
            double total = 0.0;
            for (int i = 0; i < count; i++) {
                fractions[i] = Math.pow(10.0, -2.0 * random.nextDouble());
                total += fractions[i];
            }
            for (int i = 0; i < count; i++) fractions[i] /= total;
            states.add(new State(point[0], point[1], fractions));
        }
        double[] feed = thermo.crudeFeed("createcheme:tia_juana_light").moleFractions();
        states.add(new State(420.0, 250_000.0, feed));
        states.add(new State(560.0, 250_000.0, feed));
        return states;
    }

    private record State(double temperatureKelvin, double pressurePascal, double[] moleFractions) {
        V3FugacityDerivatives derivativesOrNull(V3PengRobinsonThermo thermo, V3Phase phase) {
            try {
                return thermo.fugacityDerivatives(temperatureKelvin, pressurePascal, moleFractions, phase,
                        thermo.newWorkspace());
            } catch (V3ThermoException unavailable) {
                return null;
            }
        }

        boolean probeKeepsRoot(V3PengRobinsonThermo thermo, V3Phase phase, double temperatureStep) {
            V3FugacityResult base = thermo.fugacity(temperatureKelvin, pressurePascal, moleFractions, phase,
                    thermo.newWorkspace());
            return sameBranch(base, thermo, phase, temperatureKelvin + temperatureStep, moleFractions)
                    && sameBranch(base, thermo, phase, temperatureKelvin - temperatureStep, moleFractions);
        }

        boolean probeKeepsRootOnMoles(V3PengRobinsonThermo thermo, V3Phase phase, int component, double step) {
            if (!(moleFractions[component] > 10.0 * step)) return false;
            V3FugacityResult base = thermo.fugacity(temperatureKelvin, pressurePascal, moleFractions, phase,
                    thermo.newWorkspace());
            return sameBranch(base, thermo, phase, temperatureKelvin, perturbed(component, step))
                    && sameBranch(base, thermo, phase, temperatureKelvin, perturbed(component, -step));
        }

        private boolean sameBranch(V3FugacityResult base, V3PengRobinsonThermo thermo, V3Phase phase,
                                   double temperature, double[] composition) {
            try {
                V3FugacityResult probe = thermo.fugacity(temperature, pressurePascal, composition, phase,
                        thermo.newWorkspace());
                return probe.physicalRootCount() == base.physicalRootCount()
                        && Math.abs(probe.compressibilityFactor() - base.compressibilityFactor())
                        < 1.0e-3 * base.compressibilityFactor();
            } catch (V3ThermoException unavailable) {
                return false;
            }
        }

        double[] logFugacity(V3PengRobinsonThermo thermo, V3Phase phase, double temperatureStep) {
            return thermo.fugacity(temperatureKelvin + temperatureStep, pressurePascal, moleFractions, phase,
                    thermo.newWorkspace()).logFugacityCoefficients();
        }

        double[] logFugacityWithMoles(V3PengRobinsonThermo thermo, V3Phase phase, int component, double step) {
            return thermo.fugacity(temperatureKelvin, pressurePascal, perturbed(component, step), phase,
                    thermo.newWorkspace()).logFugacityCoefficients();
        }

        double molarEnthalpy(V3PengRobinsonThermo thermo, V3Phase phase, double temperatureStep) {
            return thermo.molarEnthalpy(temperatureKelvin + temperatureStep, pressurePascal, moleFractions, phase,
                    thermo.newWorkspace());
        }

        double totalEnthalpyWithMoles(V3PengRobinsonThermo thermo, V3Phase phase, int component, double step) {
            double[] moles = perturbed(component, step);
            double total = 0.0;
            for (double value : moles) total += value;
            return total * thermo.molarEnthalpy(temperatureKelvin, pressurePascal, moles, phase,
                    thermo.newWorkspace());
        }

        private double[] perturbed(int component, double step) {
            double[] moles = moleFractions.clone();
            moles[component] += step;
            return moles;
        }

        @Override public String toString() {
            return "T=" + temperatureKelvin + " P=" + pressurePascal;
        }
    }

    /**
     * The CDU17 package with one symmetric interaction pair set to the smallest positive double.
     *
     * <p>{@code 1 - Double.MIN_VALUE} is exactly one, so every mixture quantity is the one the zero matrix
     * gives; only the kernel's choice of mixing path changes.</p>
     */
    private static final class BarelyInteractingPackage implements V3PropertyPackage {
        private final V3PropertyPackage delegate = V3Cdu17TiaJuanaPackage.INSTANCE;

        @Override public String packageId() { return delegate.packageId(); }
        @Override public String datasetRevision() { return delegate.datasetRevision(); }
        @Override public V3ComponentBasis componentBasis() { return delegate.componentBasis(); }
        @Override public V3PropertyComponent component(int publicComponent) { return delegate.component(publicComponent); }
        @Override public V3CrudeFeed crudeFeed(String assayId) { return delegate.crudeFeed(assayId); }
        @Override public double minimumTemperatureKelvin() { return delegate.minimumTemperatureKelvin(); }
        @Override public double maximumTemperatureKelvin() { return delegate.maximumTemperatureKelvin(); }
        @Override public double minimumPressurePascal() { return delegate.minimumPressurePascal(); }
        @Override public double maximumPressurePascal() { return delegate.maximumPressurePascal(); }

        @Override
        public double[][] binaryInteractions() {
            double[][] interactions = delegate.binaryInteractions();
            interactions[0][1] = Double.MIN_VALUE;
            interactions[1][0] = Double.MIN_VALUE;
            return interactions;
        }
    }
}
