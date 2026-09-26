package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.FluidDomain;
import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import java.util.List;
import java.util.Objects;

/**
 * Where a package's states may be evaluated: an envelope and one temperature/pressure range per component, with the
 * fluid network's rule ({@link FluidDomain}): a state is inside only if it is inside the envelope and inside the range
 * of every component it carries (any positive amount).
 *
 * <p>Outside, {@link #violation} returns the same {@link ThermoDomainViolation} the network raises: the carried
 * component with the largest amount out of its temperature range, then out of its pressure range, then the envelope.
 * An open domain (for research and tests on a sub-basis, like the open-domain kernels of the stability test) accepts
 * every positive state and must be declared as such.</p>
 */
public final class PhaseDomain {
    private final String packageId;
    private final List<String> components;
    private final boolean open;
    private final double envelopeMinimumT, envelopeMaximumT, envelopeMinimumP, envelopeMaximumP;
    private final double[] minimumT, maximumT, minimumP, maximumP;

    private PhaseDomain(String packageId, List<String> components, boolean open, double[] envelope, double[][] ranges) {
        this.packageId = Objects.requireNonNull(packageId, "packageId");
        this.components = List.copyOf(components);
        this.open = open;
        int n = this.components.size();
        if (envelope.length != 4 || ranges.length != n) throw new IllegalArgumentException("Domain basis mismatch");
        envelopeMinimumT = envelope[0];
        envelopeMaximumT = envelope[1];
        envelopeMinimumP = envelope[2];
        envelopeMaximumP = envelope[3];
        requireRange(envelope);
        minimumT = new double[n];
        maximumT = new double[n];
        minimumP = new double[n];
        maximumP = new double[n];
        for (int i = 0; i < n; i++) {
            requireRange(ranges[i]);
            minimumT[i] = ranges[i][0];
            maximumT[i] = ranges[i][1];
            minimumP[i] = ranges[i][2];
            maximumP[i] = ranges[i][3];
        }
    }

    /**
     * The fluid network's domain of a package: {@code components} are the network basis, the package's EOS components
     * in order followed by water, whose range {@link FluidDomain#range(int)} gives in the slot after the last one.
     */
    public static PhaseDomain of(FluidDomain fluid, List<String> components) {
        int n = components.size();
        double[][] ranges = new double[n][];
        for (int i = 0; i < n; i++) ranges[i] = fluid.range(i);
        var envelope = fluid.envelope();
        return new PhaseDomain(fluid.packageId(), components, false, new double[] {envelope.minimumTemperature(),
                envelope.maximumTemperature(), envelope.minimumPressure(), envelope.maximumPressure()}, ranges);
    }

    /** Explicit ranges: {@code envelope} and each range are {@code [Tmin, Tmax, Pmin, Pmax]}. */
    public static PhaseDomain of(String packageId, List<String> components, double[] envelope, double[][] ranges) {
        return new PhaseDomain(packageId, components, false, envelope.clone(), deepCopy(ranges));
    }

    /** A declared open domain: every positive finite state is inside. For research and test contracts only. */
    public static PhaseDomain open(String packageId, List<String> components) {
        double[] everything = {Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE};
        double[][] ranges = new double[components.size()][];
        for (int i = 0; i < ranges.length; i++) ranges[i] = everything.clone();
        return new PhaseDomain(packageId, components, true, everything, ranges);
    }

    public String packageId() { return packageId; }
    public List<String> components() { return components; }
    public boolean open() { return open; }

    /**
     * {@code null} inside; otherwise the violation of the carried component with the largest amount (temperature
     * before pressure), then of the envelope. Allocates only on a violation.
     */
    public ThermoDomainViolation violation(double temperature, double pressure, double[] amounts) {
        if (amounts.length != components.size()) throw new IllegalArgumentException("Domain basis mismatch");
        int worst = worst(temperature, amounts, minimumT, maximumT);
        if (worst >= 0) return violation(components.get(worst), ThermoDomainViolation.Property.TEMPERATURE, temperature,
                minimumT[worst], maximumT[worst]);
        worst = worst(pressure, amounts, minimumP, maximumP);
        if (worst >= 0) return violation(components.get(worst), ThermoDomainViolation.Property.PRESSURE, pressure,
                minimumP[worst], maximumP[worst]);
        if (temperature < envelopeMinimumT || temperature > envelopeMaximumT) {
            return violation(ThermoDomainViolation.PACKAGE, ThermoDomainViolation.Property.TEMPERATURE, temperature,
                    envelopeMinimumT, envelopeMaximumT);
        }
        if (pressure < envelopeMinimumP || pressure > envelopeMaximumP) {
            return violation(ThermoDomainViolation.PACKAGE, ThermoDomainViolation.Property.PRESSURE, pressure,
                    envelopeMinimumP, envelopeMaximumP);
        }
        return null;
    }

    private static int worst(double value, double[] amounts, double[] minimum, double[] maximum) {
        int worst = -1;
        double largest = -1.0;
        for (int i = 0; i < amounts.length; i++) {
            if (amounts[i] > 0.0 && (value < minimum[i] || value > maximum[i]) && amounts[i] > largest) {
                worst = i;
                largest = amounts[i];
            }
        }
        return worst;
    }

    private ThermoDomainViolation violation(String component, ThermoDomainViolation.Property property, double value,
                                            double minimum, double maximum) {
        return new ThermoDomainViolation(packageId, component, property, value, minimum, maximum);
    }

    private static void requireRange(double[] range) {
        if (range.length != 4 || !(range[0] > 0.0) || !(range[1] > range[0]) || !(range[2] > 0.0) || !(range[3] > range[2])
                || !Double.isFinite(range[1]) || !Double.isFinite(range[3])) {
            throw new IllegalArgumentException("A domain range is [Tmin, Tmax, Pmin, Pmax] with 0 < min < max, finite");
        }
    }

    private static double[][] deepCopy(double[][] ranges) {
        double[][] copy = new double[ranges.length][];
        for (int i = 0; i < ranges.length; i++) copy[i] = ranges[i].clone();
        return copy;
    }
}
