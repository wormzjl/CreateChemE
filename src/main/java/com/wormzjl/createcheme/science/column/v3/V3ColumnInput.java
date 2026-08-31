package com.wormzjl.createcheme.science.column.v3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable SI input for the dry V3 baseline.
 *
 * <p>The feed vector is always returned as a defensive copy. Liquid side draws specify total
 * molar product rates; water/steam feeds are outside this dry contract.</p>
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
        List<V3ColumnSpecification> specifications,
        List<V3SideDrawSpec> sideDraws) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MIN_STAGE_COUNT = 2;
    public static final int MAX_STAGE_COUNT = 64;
    public static final int MAX_SIDE_DRAWS = 3;

    /** Legacy no-draw input; preserves the existing schema and digest representation. */
    public V3ColumnInput(
            int schemaVersion, String packageId, String assayId, V3ComponentBasis componentBasis,
            double[] feedComponentMolarFlowsMolPerSecond, double feedTemperatureKelvin, int stageCount,
            int feedStageNumber, double topPressurePascal, double stagePressureDropPascal,
            List<V3ColumnSpecification> specifications) {
        this(schemaVersion, packageId, assayId, componentBasis, feedComponentMolarFlowsMolPerSecond,
                feedTemperatureKelvin, stageCount, feedStageNumber, topPressurePascal, stagePressureDropPascal,
                specifications, List.of());
    }

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
        sideDraws = canonicalSideDraws(sideDraws);
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
                && specifications.equals(input.specifications)
                && sideDraws.equals(input.sideDraws);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(schemaVersion, packageId, assayId, componentBasis, feedTemperatureKelvin, stageCount,
                feedStageNumber, topPressurePascal, stagePressureDropPascal, specifications, sideDraws);
        return 31 * result + Arrays.hashCode(feedComponentMolarFlowsMolPerSecond);
    }

    @Override
    public String toString() {
        return "V3ColumnInput[packageId=" + packageId + ", assayId=" + assayId + ", componentCount="
                + componentBasis.componentCount() + ", stageCount=" + stageCount + ", feedStageNumber="
                + feedStageNumber + ", sideDraws=" + sideDraws + "]";
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

    private static List<V3SideDrawSpec> canonicalSideDraws(List<V3SideDrawSpec> sideDraws) {
        if (sideDraws == null || sideDraws.size() > MAX_SIDE_DRAWS || sideDraws.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V3 side draws must be a non-null list of at most " + MAX_SIDE_DRAWS);
        }
        List<V3SideDrawSpec> copy = new ArrayList<>(sideDraws);
        copy.sort(Comparator.comparingInt(V3SideDrawSpec::trayNumber));
        for (int i = 1; i < copy.size(); i++) {
            if (copy.get(i - 1).trayNumber() == copy.get(i).trayNumber()) {
                throw new IllegalArgumentException("V3 permits only one side draw per tray");
            }
        }
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
