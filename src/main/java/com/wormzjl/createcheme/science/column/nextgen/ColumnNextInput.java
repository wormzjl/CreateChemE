package com.wormzjl.createcheme.science.column.nextgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable accepted input for the experimental calculator.  This is deliberately unrelated to
 * {@code ColumnSimulation.ColumnInput}; the legacy wire and scientific contracts remain unchanged.
 */
public record ColumnNextInput(
        int schemaVersion,
        String packageId,
        String assayId,
        CrudeFeedInput crudeFeed,
        int stageCount,
        int crudeFeedStageNumber,
        double topPressurePascal,
        double stagePressureDropPascal,
        double condenserOutletTemperatureKelvin,
        double reboilerDutyWatts,
        double organicRefluxRatio,
        List<SideDrawInput> sideDraws,
        List<WaterSteamFeedInput> utilityFeeds) {
    public static final int SCHEMA_VERSION = 1;
    public static final int DEFAULT_STAGE_COUNT = 30;
    public static final double DEFAULT_TOP_PRESSURE_PASCAL = 250_000.0;
    public static final double DEFAULT_STAGE_DROP_PASCAL = 750.0;
    public static final double DEFAULT_CONDENSER_TEMPERATURE_KELVIN = 332.15;
    public static final int MAX_SIDE_DRAWS = 6;
    public static final int MAX_UTILITY_FEEDS = 8;
    public static final int MAX_PACKET_BYTES = 64 * 1024;

    public ColumnNextInput {
        packageId = requireIdentifier(packageId, "packageId");
        assayId = requireIdentifier(assayId, "assayId");
        crudeFeed = Objects.requireNonNull(crudeFeed, "crudeFeed");
        sideDraws = immutableAndCanonical(sideDraws, Comparator.comparingInt(SideDrawInput::stageNumber), "sideDraws");
        utilityFeeds = immutableAndCanonical(
                utilityFeeds,
                Comparator.comparingInt(WaterSteamFeedInput::stageNumber)
                        .thenComparing(feed -> feed.mode().serializedName())
                        .thenComparingDouble(WaterSteamFeedInput::molarFlowMolPerSecond),
                "utilityFeeds");
    }

    public static ColumnNextInput defaults() {
        return new ColumnNextInput(
                SCHEMA_VERSION,
                "createcheme:cdu17_tjl_acs2018",
                "createcheme:tia_juana_light",
                new CrudeFeedInput(100.0, 638.15),
                DEFAULT_STAGE_COUNT,
                24,
                DEFAULT_TOP_PRESSURE_PASCAL,
                DEFAULT_STAGE_DROP_PASCAL,
                DEFAULT_CONDENSER_TEMPERATURE_KELVIN,
                8_000_000.0,
                4.17,
                List.of(
                        SideDrawInput.molar(8, 10.0),
                        SideDrawInput.molar(15, 12.0),
                        SideDrawInput.molar(22, 8.0)),
                List.of());
    }

    public double totalPressureDropPascal() {
        return (stageCount - 1.0) * stagePressureDropPascal;
    }

    public double bottomPressurePascal() {
        return topPressurePascal + totalPressureDropPascal();
    }

    public double pressureAtStageNumber(int stageNumber) {
        if (stageNumber < 1 || stageNumber > stageCount) {
            throw new IllegalArgumentException("Stage number is outside the accepted topology");
        }
        return topPressurePascal + (stageNumber - 1.0) * stagePressureDropPascal;
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 96) {
            throw new IllegalArgumentException(field + " is blank or too long");
        }
        return value;
    }

    private static <T> List<T> immutableAndCanonical(
            List<T> values, Comparator<? super T> order, String field) {
        Objects.requireNonNull(values, field);
        List<T> copy = new ArrayList<>(values);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " contains null");
        }
        copy.sort(order);
        return List.copyOf(copy);
    }

    public record CrudeFeedInput(double molarFlowMolPerSecond, double temperatureKelvin) {}

    public record SideDrawInput(int stageNumber, AuthoredBasis basis, double authoredRate) {
        public SideDrawInput {
            Objects.requireNonNull(basis, "basis");
        }

        public static SideDrawInput molar(int stageNumber, double molarFlowMolPerSecond) {
            return new SideDrawInput(stageNumber, AuthoredBasis.MOLAR, molarFlowMolPerSecond);
        }

        public static SideDrawInput mass(int stageNumber, double massFlowKilogramPerSecond) {
            return new SideDrawInput(stageNumber, AuthoredBasis.MASS, massFlowKilogramPerSecond);
        }
    }

    public record WaterSteamFeedInput(
            UtilityFeedMode mode,
            int stageNumber,
            double molarFlowMolPerSecond,
            double temperatureKelvin,
            double upstreamPressurePascal) {
        public WaterSteamFeedInput {
            Objects.requireNonNull(mode, "mode");
        }
    }

    public enum AuthoredBasis {
        MOLAR("molar"),
        MASS("mass");

        private final String serializedName;

        AuthoredBasis(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static AuthoredBasis fromSerializedName(String name) {
            for (AuthoredBasis value : values()) {
                if (value.serializedName.equals(name)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown side-draw basis: " + name);
        }
    }

    public enum UtilityFeedMode {
        WATER("water"),
        STEAM("steam");

        private final String serializedName;

        UtilityFeedMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static UtilityFeedMode fromSerializedName(String name) {
            for (UtilityFeedMode value : values()) {
                if (value.serializedName.equals(name)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown utility-feed mode: " + name);
        }
    }
}
