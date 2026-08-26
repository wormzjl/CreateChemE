package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.Arrays;
import java.util.Objects;

/** Immutable normalized hydrocarbon assay vector resolved from a registered V3 property package. */
public final class V3CrudeFeed {
    private final String packageId;
    private final String assayId;
    private final V3ComponentBasis componentBasis;
    private final double[] moleFractions;

    V3CrudeFeed(String packageId, String assayId, V3ComponentBasis componentBasis, double[] moleFractions) {
        this.packageId = requireIdentifier(packageId, "packageId");
        this.assayId = requireIdentifier(assayId, "assayId");
        this.componentBasis = Objects.requireNonNull(componentBasis, "componentBasis");
        this.moleFractions = normalizedCopy(moleFractions, componentBasis.componentCount());
    }

    public String packageId() { return packageId; }
    public String assayId() { return assayId; }
    public V3ComponentBasis componentBasis() { return componentBasis; }
    public double[] moleFractions() { return moleFractions.clone(); }

    @Override
    public boolean equals(Object other) {
        return other instanceof V3CrudeFeed feed && packageId.equals(feed.packageId) && assayId.equals(feed.assayId)
                && componentBasis.equals(feed.componentBasis) && Arrays.equals(moleFractions, feed.moleFractions);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(packageId, assayId, componentBasis) + Arrays.hashCode(moleFractions);
    }

    private static double[] normalizedCopy(double[] values, int expectedLength) {
        values = Objects.requireNonNull(values, "moleFractions").clone();
        if (values.length != expectedLength) throw new IllegalArgumentException("V3 crude assay does not match its component basis");
        double total = 0.0;
        for (double value : values) {
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("V3 crude assay has invalid fractions");
            total += value;
        }
        if (!Double.isFinite(total) || total <= 0.0) throw new IllegalArgumentException("V3 crude assay has no hydrocarbon material");
        for (int index = 0; index < values.length; index++) values[index] /= total;
        return values;
    }

    private static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (!value.matches("[a-z][a-z0-9_.:-]{0,127}")) throw new IllegalArgumentException(name + " must be a stable identifier");
        return value;
    }
}
