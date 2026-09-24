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
 *
 * <p>{@code columnDiameterMetres} selects how the pressure profile is generated. Zero is the prescribed-drop
 * mode this contract has always had: every tray interval drops {@code stagePressureDropPascal}. A positive
 * diameter asks for sieve-tray hydraulics ({@link V3TrayHydraulics}), where the authored drop becomes the
 * nominal guess the first solve runs on and the published profile is marched from that solve's own traffic.</p>
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
        List<V3SideDrawSpec> sideDraws,
        List<V3SteamFeedSpec> steamFeeds,
        List<V3PumparoundSpec> pumparounds,
        double columnDiameterMetres) {
    public static final int SCHEMA_VERSION = 1;
    /** Internal interior-tray count. Public total tray count is stageCount + 2. */
    public static final int MIN_STAGE_COUNT = 0;
    public static final int MAX_STAGE_COUNT = 64;
    public static final int MAX_SIDE_DRAWS = 3;
    public static final int MAX_STEAM_FEEDS = 2;
    public static final int MAX_PUMPAROUNDS = 4;
    /** Prescribed-drop mode: the historical uniform profile, bit-identical in every published field. */
    public static final double PRESCRIBED_DROP_DIAMETER = 0.0;
    /** Matches the shipped presets' ~100 kbpd throughput at about 69 % of flood on the default preset. */
    public static final double DEFAULT_COLUMN_DIAMETER_METRES = 8.0;
    public static final double MAX_COLUMN_DIAMETER_METRES = 15.0;

    /** Legacy no-draw input; preserves the existing schema and digest representation. */
    public V3ColumnInput(
            int schemaVersion, String packageId, String assayId, V3ComponentBasis componentBasis,
            double[] feedComponentMolarFlowsMolPerSecond, double feedTemperatureKelvin, int stageCount,
            int feedStageNumber, double topPressurePascal, double stagePressureDropPascal,
            List<V3ColumnSpecification> specifications) {
        this(schemaVersion, packageId, assayId, componentBasis, feedComponentMolarFlowsMolPerSecond,
                feedTemperatureKelvin, stageCount, feedStageNumber, topPressurePascal, stagePressureDropPascal,
                specifications, List.of(), List.of(), List.of());
    }

    /** Legacy side-draw input; preserves all dry callers and their empty steam contract. */
    public V3ColumnInput(
            int schemaVersion, String packageId, String assayId, V3ComponentBasis componentBasis,
            double[] feedComponentMolarFlowsMolPerSecond, double feedTemperatureKelvin, int stageCount,
            int feedStageNumber, double topPressurePascal, double stagePressureDropPascal,
            List<V3ColumnSpecification> specifications, List<V3SideDrawSpec> sideDraws) {
        this(schemaVersion, packageId, assayId, componentBasis, feedComponentMolarFlowsMolPerSecond,
                feedTemperatureKelvin, stageCount, feedStageNumber, topPressurePascal, stagePressureDropPascal,
                specifications, sideDraws, List.of(), List.of());
    }

    /** Legacy steam input; preserves every existing wire and persistence caller with no stage heat. */
    public V3ColumnInput(
            int schemaVersion, String packageId, String assayId, V3ComponentBasis componentBasis,
            double[] feedComponentMolarFlowsMolPerSecond, double feedTemperatureKelvin, int stageCount,
            int feedStageNumber, double topPressurePascal, double stagePressureDropPascal,
            List<V3ColumnSpecification> specifications, List<V3SideDrawSpec> sideDraws,
            List<V3SteamFeedSpec> steamFeeds) {
        this(schemaVersion, packageId, assayId, componentBasis, feedComponentMolarFlowsMolPerSecond,
                feedTemperatureKelvin, stageCount, feedStageNumber, topPressurePascal, stagePressureDropPascal,
                specifications, sideDraws, steamFeeds, List.of());
    }

    /** Legacy prescribed-drop input; every existing caller, wire payload and persisted state decodes through here. */
    public V3ColumnInput(
            int schemaVersion, String packageId, String assayId, V3ComponentBasis componentBasis,
            double[] feedComponentMolarFlowsMolPerSecond, double feedTemperatureKelvin, int stageCount,
            int feedStageNumber, double topPressurePascal, double stagePressureDropPascal,
            List<V3ColumnSpecification> specifications, List<V3SideDrawSpec> sideDraws,
            List<V3SteamFeedSpec> steamFeeds, List<V3PumparoundSpec> pumparounds) {
        this(schemaVersion, packageId, assayId, componentBasis, feedComponentMolarFlowsMolPerSecond,
                feedTemperatureKelvin, stageCount, feedStageNumber, topPressurePascal, stagePressureDropPascal,
                specifications, sideDraws, steamFeeds, pumparounds, PRESCRIBED_DROP_DIAMETER);
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
        if (!Double.isFinite(columnDiameterMetres) || columnDiameterMetres < 0.0
                || columnDiameterMetres > MAX_COLUMN_DIAMETER_METRES) {
            throw new IllegalArgumentException("V3 column diameter must be finite and within 0.."
                    + MAX_COLUMN_DIAMETER_METRES + " m");
        }
        specifications = canonicalSpecifications(specifications);
        sideDraws = canonicalSideDraws(sideDraws);
        steamFeeds = canonicalSteamFeeds(steamFeeds);
        pumparounds = canonicalPumparounds(pumparounds);
    }

    @Override
    public double[] feedComponentMolarFlowsMolPerSecond() {
        return feedComponentMolarFlowsMolPerSecond.clone();
    }

    /** Whether the pressure profile is marched from the solved traffic rather than prescribed uniformly. */
    public boolean usesTrayHydraulics() {
        return columnDiameterMetres > 0.0 && stageCount > 0;
    }

    /** The same request in prescribed-drop mode; the seam tests and legacy pins author their inputs through. */
    public V3ColumnInput withColumnDiameter(double diameterMetres) {
        return diameterMetres == columnDiameterMetres ? this
                : new V3ColumnInput(schemaVersion, packageId, assayId, componentBasis,
                        feedComponentMolarFlowsMolPerSecond, feedTemperatureKelvin, stageCount, feedStageNumber,
                        topPressurePascal, stagePressureDropPascal, specifications, sideDraws, steamFeeds,
                        pumparounds, diameterMetres);
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
                && Double.doubleToLongBits(columnDiameterMetres) == Double.doubleToLongBits(input.columnDiameterMetres)
                && packageId.equals(input.packageId)
                && assayId.equals(input.assayId)
                && componentBasis.equals(input.componentBasis)
                && Arrays.equals(feedComponentMolarFlowsMolPerSecond, input.feedComponentMolarFlowsMolPerSecond)
                && specifications.equals(input.specifications)
                && sideDraws.equals(input.sideDraws)
                && steamFeeds.equals(input.steamFeeds)
                && pumparounds.equals(input.pumparounds);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(schemaVersion, packageId, assayId, componentBasis, feedTemperatureKelvin, stageCount,
                feedStageNumber, topPressurePascal, stagePressureDropPascal, specifications, sideDraws, steamFeeds,
                pumparounds, columnDiameterMetres);
        return 31 * result + Arrays.hashCode(feedComponentMolarFlowsMolPerSecond);
    }

    @Override
    public String toString() {
        return "V3ColumnInput[packageId=" + packageId + ", assayId=" + assayId + ", componentCount="
                + componentBasis.componentCount() + ", stageCount=" + stageCount + ", feedStageNumber="
                + feedStageNumber + ", sideDraws=" + sideDraws + ", steamFeeds=" + steamFeeds
                + ", pumparounds=" + pumparounds + "]";
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

    private static List<V3SteamFeedSpec> canonicalSteamFeeds(List<V3SteamFeedSpec> steamFeeds) {
        if (steamFeeds == null || steamFeeds.size() > MAX_STEAM_FEEDS || steamFeeds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V3 steam feeds must be a non-null list of at most " + MAX_STEAM_FEEDS);
        }
        List<V3SteamFeedSpec> copy = new ArrayList<>(steamFeeds);
        copy.sort(Comparator.comparingInt(V3SteamFeedSpec::stageNumber));
        for (int i = 1; i < copy.size(); i++) {
            if (copy.get(i - 1).stageNumber() == copy.get(i).stageNumber()) {
                throw new IllegalArgumentException("V3 permits only one steam feed per stage");
            }
        }
        return List.copyOf(copy);
    }

    private static List<V3PumparoundSpec> canonicalPumparounds(List<V3PumparoundSpec> pumparounds) {
        if (pumparounds == null || pumparounds.size() > MAX_PUMPAROUNDS || pumparounds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V3 pumparounds must be a non-null list of at most " + MAX_PUMPAROUNDS);
        }
        List<V3PumparoundSpec> copy = new ArrayList<>(pumparounds);
        copy.sort(Comparator.comparingInt(V3PumparoundSpec::returnTray)
                .thenComparingInt(V3PumparoundSpec::drawTray));
        for (int i = 1; i < copy.size(); i++) {
            if (copy.get(i - 1).returnTray() == copy.get(i).returnTray()
                    && copy.get(i - 1).drawTray() == copy.get(i).drawTray()) {
                throw new IllegalArgumentException("V3 permits only one pumparound per return/draw tray pair");
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
