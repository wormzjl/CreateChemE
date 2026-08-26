package com.wormzjl.createcheme.science.column.v3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable SI input for the dry V3 baseline.
 *
 * <p>The feed vector is always returned as a defensive copy.  Side draws and water/steam feeds
 * are intentionally absent from this M0/M1 contract and will require their own reviewed
 * specification and degree-of-freedom extension.</p>
 */
public record V3ColumnInput(
        int schemaVersion,
        String packageId,
        String assayId,
        V3ComponentBasis componentBasis,
        double[] feedComponentMolarFlowsMolPerSecond,
        double feedTemperatureKelvin,
        int stageCount,
        int feedStageNumber,
        double topPressurePascal,
        double stagePressureDropPascal,
        List<V3ColumnSpecification> specifications) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MIN_STAGE_COUNT = 2;
    public static final int MAX_STAGE_COUNT = 64;

    public V3ColumnInput {
        packageId = requireIdentifier(packageId, "packageId");
        assayId = requireIdentifier(assayId, "assayId");
        componentBasis = Objects.requireNonNull(componentBasis, "componentBasis");
        feedComponentMolarFlowsMolPerSecond = copyAndValidateFeed(
                feedComponentMolarFlowsMolPerSecond, componentBasis.componentCount());
        if (!Double.isFinite(feedTemperatureKelvin) || feedTemperatureKelvin <= 0.0
                || !Double.isFinite(topPressurePascal) || topPressurePascal <= 0.0
                || !Double.isFinite(stagePressureDropPascal) || stagePressureDropPascal < 0.0) {
            throw new IllegalArgumentException("V3 feed temperature and pressure inputs must be finite and physically positive");
        }
        specifications = canonicalSpecifications(specifications);
    }

    @Override
    public double[] feedComponentMolarFlowsMolPerSecond() {
        return feedComponentMolarFlowsMolPerSecond.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof V3ColumnInput input)) return false;
        return schemaVersion == input.schemaVersion
                && stageCount == input.stageCount
                && feedStageNumber == input.feedStageNumber
                && Double.doubleToLongBits(feedTemperatureKelvin) == Double.doubleToLongBits(input.feedTemperatureKelvin)
                && Double.doubleToLongBits(topPressurePascal) == Double.doubleToLongBits(input.topPressurePascal)
                && Double.doubleToLongBits(stagePressureDropPascal) == Double.doubleToLongBits(input.stagePressureDropPascal)
                && packageId.equals(input.packageId)
                && assayId.equals(input.assayId)
                && componentBasis.equals(input.componentBasis)
                && Arrays.equals(feedComponentMolarFlowsMolPerSecond, input.feedComponentMolarFlowsMolPerSecond)
                && specifications.equals(input.specifications);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(schemaVersion, packageId, assayId, componentBasis, feedTemperatureKelvin, stageCount,
                feedStageNumber, topPressurePascal, stagePressureDropPascal, specifications);
        return 31 * result + Arrays.hashCode(feedComponentMolarFlowsMolPerSecond);
    }

    @Override
    public String toString() {
        return "V3ColumnInput[packageId=" + packageId + ", assayId=" + assayId + ", componentCount="
                + componentBasis.componentCount() + ", stageCount=" + stageCount + ", feedStageNumber="
                + feedStageNumber + "]";
    }

    private static double[] copyAndValidateFeed(double[] feed, int componentCount) {
        feed = Objects.requireNonNull(feed, "feedComponentMolarFlowsMolPerSecond").clone();
        if (feed.length != componentCount) {
            throw new IllegalArgumentException("V3 feed vector does not match the component basis");
        }
        double total = 0.0;
        for (double flow : feed) {
            if (!Double.isFinite(flow) || flow < 0.0) {
                throw new IllegalArgumentException("V3 component feed flows must be finite and nonnegative");
            }
            total += flow;
        }
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("V3 feed must contain a positive total component flow");
        }
        return feed;
    }

    private static List<V3ColumnSpecification> canonicalSpecifications(List<V3ColumnSpecification> specifications) {
        List<V3ColumnSpecification> copy = new ArrayList<>(Objects.requireNonNull(specifications, "specifications"));
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V3 specifications cannot contain null");
        }
        copy.sort(Comparator.comparing(V3ColumnSpecification::controlledQuantity)
                .thenComparing(specification -> specification.getClass().getName()));
        return List.copyOf(copy);
    }

    private static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (!value.matches("[a-z][a-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a stable identifier");
        }
        return value;
    }
}
