package com.wormzjl.createcheme.science.column.nextgen;

import java.util.Arrays;
import java.util.Objects;

/** Immutable 17-row feed vector selected by a package/assay instead of an editable runtime component list. */
public final class CharacterizedFeed {
    private final String assayId;
    private final double[] moleFractions;

    public CharacterizedFeed(String assayId, double[] moleFractions) {
        this.assayId = Objects.requireNonNull(assayId, "assayId");
        this.moleFractions = moleFractions.clone();
        if (this.moleFractions.length < 1) throw new IllegalArgumentException("Feed must use a nonempty public axis");
        double total = 0.0;
        for (double value : this.moleFractions) {
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Invalid feed fraction");
            total += value;
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("Feed must contain material");
        for (int index = 0; index < this.moleFractions.length; index++) this.moleFractions[index] /= total;
    }

    public String assayId() { return assayId; }
    public double[] moleFractions() { return moleFractions.clone(); }
    public double moleFraction(int index) { return moleFractions[index]; }

    @Override public boolean equals(Object other) {
        return other instanceof CharacterizedFeed feed && assayId.equals(feed.assayId)
                && Arrays.equals(moleFractions, feed.moleFractions);
    }

    @Override public int hashCode() { return 31 * assayId.hashCode() + Arrays.hashCode(moleFractions); }
}
