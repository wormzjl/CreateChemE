package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Objects;

/**
 * Immutable first derivatives of one phase's fugacity coefficients and enthalpy on the public component axis.
 *
 * <p>Every derivative belongs to the same selected equation-of-state root as the value it accompanies, so a
 * consumer may use these in place of a finite difference of {@link V3ThermoModel#fugacity} only while that
 * difference would stay on one branch. A model that cannot say so does not implement
 * {@link V3ThermoDerivatives} at all.</p>
 *
 * <p>The composition derivatives are taken with respect to <em>mole numbers of a one-mole basis</em>: the
 * caller's composition is normalised, and {@code n_j} is then the mole fraction {@code x_j}. That is the only
 * convention a caller who passes a composition rather than mole numbers can be given, and it costs nothing,
 * because {@code ln phi} is intensive: for actual mole numbers {@code N_j = N x_j} the derivative is
 * {@code (1/N)} times the one recorded here, so a caller differentiating in log-flow coordinates multiplies by
 * {@code x_j} and never needs the total at all. Two consequences are worth stating because they are the
 * cheapest possible test of an implementation: the matrix is symmetric, and {@code sum_j x_j} of any of its
 * rows is zero (Gibbs--Duhem).</p>
 *
 * <p>{@code partialMolarEnthalpy[j]} is {@code d(n h)/dn_j} at constant temperature and pressure, so
 * {@code sum_j x_j partialMolarEnthalpy[j]} returns {@code molarEnthalpy}; {@code dMolarEnthalpyDT} is the
 * phase heat capacity, ideal gas plus residual.</p>
 */
public final class V3FugacityDerivatives {
    private final V3Phase phase;
    private final double[] logFugacityCoefficients;
    private final double[] dLogFugacityCoefficientDT;
    private final double[][] dLogFugacityCoefficientDMoles;
    private final double molarEnthalpyJoulesPerMol;
    private final double dMolarEnthalpyDTJoulesPerMolKelvin;
    private final double[] partialMolarEnthalpyJoulesPerMol;

    public V3FugacityDerivatives(
            V3Phase phase,
            double[] logFugacityCoefficients,
            double[] dLogFugacityCoefficientDT,
            double[][] dLogFugacityCoefficientDMoles,
            double molarEnthalpyJoulesPerMol,
            double dMolarEnthalpyDTJoulesPerMolKelvin,
            double[] partialMolarEnthalpyJoulesPerMol) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.logFugacityCoefficients = Objects.requireNonNull(logFugacityCoefficients, "logFugacityCoefficients").clone();
        this.dLogFugacityCoefficientDT = Objects.requireNonNull(dLogFugacityCoefficientDT, "dLogFugacityCoefficientDT").clone();
        this.dLogFugacityCoefficientDMoles = copy(
                Objects.requireNonNull(dLogFugacityCoefficientDMoles, "dLogFugacityCoefficientDMoles"));
        this.molarEnthalpyJoulesPerMol = molarEnthalpyJoulesPerMol;
        this.dMolarEnthalpyDTJoulesPerMolKelvin = dMolarEnthalpyDTJoulesPerMolKelvin;
        this.partialMolarEnthalpyJoulesPerMol =
                Objects.requireNonNull(partialMolarEnthalpyJoulesPerMol, "partialMolarEnthalpyJoulesPerMol").clone();
        int count = this.logFugacityCoefficients.length;
        if (count == 0 || this.dLogFugacityCoefficientDT.length != count
                || this.dLogFugacityCoefficientDMoles.length != count
                || this.partialMolarEnthalpyJoulesPerMol.length != count
                || !Double.isFinite(molarEnthalpyJoulesPerMol)
                || !Double.isFinite(dMolarEnthalpyDTJoulesPerMolKelvin)) {
            throw new IllegalArgumentException("V3 fugacity derivatives have an invalid shape");
        }
        for (int component = 0; component < count; component++) {
            if (!Double.isFinite(this.logFugacityCoefficients[component])
                    || !Double.isFinite(this.dLogFugacityCoefficientDT[component])
                    || !Double.isFinite(this.partialMolarEnthalpyJoulesPerMol[component])
                    || this.dLogFugacityCoefficientDMoles[component].length != count) {
                throw new IllegalArgumentException("V3 fugacity derivatives must be finite");
            }
            for (double value : this.dLogFugacityCoefficientDMoles[component]) {
                if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 fugacity derivatives must be finite");
            }
        }
    }

    public V3Phase phase() {
        return phase;
    }

    public int componentCount() {
        return logFugacityCoefficients.length;
    }

    public double logFugacityCoefficient(int component) {
        return logFugacityCoefficients[component];
    }

    /** {@code (d ln phi_i / dT)} at constant pressure and composition, in 1/K. */
    public double dLogFugacityCoefficientDT(int component) {
        return dLogFugacityCoefficientDT[component];
    }

    /** {@code (d ln phi_i / dn_j)} at constant temperature and pressure on a one-mole basis. */
    public double dLogFugacityCoefficientDMoles(int component, int withRespectTo) {
        return dLogFugacityCoefficientDMoles[component][withRespectTo];
    }

    public double molarEnthalpyJoulesPerMol() {
        return molarEnthalpyJoulesPerMol;
    }

    /** The phase heat capacity {@code (dh/dT)} at constant pressure and composition, in J/(mol K). */
    public double dMolarEnthalpyDTJoulesPerMolKelvin() {
        return dMolarEnthalpyDTJoulesPerMolKelvin;
    }

    /** {@code d(n h)/dn_j} at constant temperature and pressure, in J/mol. */
    public double partialMolarEnthalpyJoulesPerMol(int component) {
        return partialMolarEnthalpyJoulesPerMol[component];
    }

    private static double[][] copy(double[][] values) {
        double[][] copy = new double[values.length][];
        for (int row = 0; row < values.length; row++) copy[row] = Objects.requireNonNull(values[row], "row").clone();
        return copy;
    }
}
