package com.wormzjl.createcheme.science.column.v3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;

/** SHA-256 digest of the resolved scientific input and explicitly supplied revision identifiers. */
public record V3InputDigest(String hexadecimalSha256) {
    public V3InputDigest {
        hexadecimalSha256 = Objects.requireNonNull(hexadecimalSha256, "hexadecimalSha256");
        if (!hexadecimalSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("V3 input digest must be a lowercase SHA-256 hex value");
        }
    }

    public static V3InputDigest of(
            V3ColumnProblem problem, String formulationRevision, String propertyDataRevision,
            String assumptionsRevision) {
        return of(problem, formulationRevision, propertyDataRevision, assumptionsRevision, 0.0);
    }

    /**
     * Identifies an authored cutoff request, including when its attempt ultimately retries untruncated.
     * Zero preserves the existing digest byte stream; a positive cutoff is hashed as a named field.
     */
    public static V3InputDigest of(
            V3ColumnProblem problem, String formulationRevision, String propertyDataRevision,
            String assumptionsRevision, double stageTraceCutoffMoleFraction) {
        V3TruncationSupport.requireCutoff(stageTraceCutoffMoleFraction);
        problem = Objects.requireNonNull(problem, "problem");
        MessageDigest digest = sha256();
        put(digest, "v3-input-digest-schema", V3ColumnInput.SCHEMA_VERSION);
        put(digest, "formulation-revision", revision(formulationRevision, "formulationRevision"));
        put(digest, "property-data-revision", revision(propertyDataRevision, "propertyDataRevision"));
        put(digest, "assumptions-revision", revision(assumptionsRevision, "assumptionsRevision"));
        if (stageTraceCutoffMoleFraction > 0.0) {
            put(digest, "stage-trace-cutoff-mole-fraction-bits", canonicalBits(stageTraceCutoffMoleFraction));
        }
        V3ColumnInput input = problem.input();
        put(digest, "input-schema", input.schemaVersion());
        put(digest, "package-id", input.packageId());
        put(digest, "assay-id", input.assayId());
        put(digest, "component-count", input.componentBasis().componentCount());
        for (String componentId : input.componentBasis().componentIds()) put(digest, "component-id", componentId);
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) put(digest, "feed-flow-bits", canonicalBits(flow));
        put(digest, "feed-temperature-bits", canonicalBits(input.feedTemperatureKelvin()));
        put(digest, "tray-count", input.stageCount());
        put(digest, "feed-tray", input.feedStageNumber());
        for (V3SideDrawSpec draw : input.sideDraws()) {
            put(digest, "side-draw-tray", draw.trayNumber());
            put(digest, "side-draw-rate-bits", canonicalBits(draw.molarFlowMolPerSecond()));
        }
        // Empty-list placement is intentional: dry digests retain their historical byte stream.
        if (!input.steamFeeds().isEmpty()) {
            put(digest, "water-data-revision", V3WaterProperties.DATA_REVISION);
            for (V3SteamFeedSpec steam : input.steamFeeds()) {
                put(digest, "steam-stage", steam.stageNumber());
                put(digest, "steam-rate-bits", canonicalBits(steam.molarFlowMolPerSecond()));
                put(digest, "steam-temperature-bits", canonicalBits(steam.temperatureKelvin()));
            }
        }
        put(digest, "top-pressure-bits", canonicalBits(input.topPressurePascal()));
        put(digest, "stage-drop-bits", canonicalBits(input.stagePressureDropPascal()));
        put(digest, "condenser-branch", problem.topology().condenserPhaseBranch().name());
        for (V3ColumnSpecification specification : input.specifications()) {
            put(digest, "control", specification.controlledQuantity().name());
            switch (specification) {
                case V3ColumnSpecification.CondenserOutletTemperature temperature ->
                        put(digest, "control-bits", canonicalBits(temperature.kelvin()));
                case V3ColumnSpecification.OrganicRefluxRatio reflux ->
                        put(digest, "control-bits", canonicalBits(reflux.ratio()));
                case V3ColumnSpecification.ReboilerDuty duty -> put(digest, "control-bits", canonicalBits(duty.watts()));
            }
        }
        for (double pressure : problem.nodePressuresPascal()) put(digest, "node-pressure-bits", canonicalBits(pressure));
        return new V3InputDigest(HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("A Java runtime without SHA-256 is unsupported", exception);
        }
    }

    private static String revision(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 128) throw new IllegalArgumentException(name + " is outside the bounded contract");
        return value;
    }

    private static long canonicalBits(double value) {
        return Double.doubleToLongBits(value == 0.0 ? 0.0 : value);
    }

    private static void put(MessageDigest digest, String name, String value) {
        put(digest, name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(MessageDigest digest, String name, int value) {
        put(digest, name, Integer.toString(value));
    }

    private static void put(MessageDigest digest, String name, long value) {
        put(digest, name, Long.toUnsignedString(value));
    }

    private static void put(MessageDigest digest, String name, byte[] value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) nameBytes.length);
        digest.update(nameBytes);
        digest.update((byte) (value.length >>> 24));
        digest.update((byte) (value.length >>> 16));
        digest.update((byte) (value.length >>> 8));
        digest.update((byte) value.length);
        digest.update(value);
    }
}
