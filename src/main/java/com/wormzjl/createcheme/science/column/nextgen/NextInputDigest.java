package com.wormzjl.createcheme.science.column.nextgen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical scientific cache key; request/ticket/timing identity is deliberately excluded. */
public final class NextInputDigest {
    private NextInputDigest() {}

    public static String of(ColumnProblem problem, String solverRevision, String assumptionsRevision) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(solverRevision, "solverRevision");
        Objects.requireNonNull(assumptionsRevision, "assumptionsRevision");
        ColumnNextInput input = problem.input();
        StringBuilder canonical = new StringBuilder(512)
                .append(input.schemaVersion()).append('|')
                .append(input.packageId()).append('|')
                .append(problem.propertyPackage().datasetRevision()).append('|')
                .append(solverRevision).append('|').append(assumptionsRevision).append('|')
                .append(problem.propertyPackage().basis().hydrocarbonCount()).append(':')
                .append(problem.propertyPackage().basis().waterIndex()).append('|')
                .append(input.assayId()).append('|')
                .append(hex(input.crudeFeed().molarFlowMolPerSecond())).append('|')
                .append(hex(input.crudeFeed().temperatureKelvin())).append('|')
                .append(input.stageCount()).append('|').append(input.crudeFeedStageNumber()).append('|')
                .append(hex(input.topPressurePascal())).append('|')
                .append(hex(input.stagePressureDropPascal())).append('|')
                .append(hex(input.condenserOutletTemperatureKelvin())).append('|')
                .append(hex(input.reboilerDutyWatts())).append('|')
                .append(hex(input.organicRefluxRatio()));
        for (String componentId : problem.propertyPackage().basis().publicAxisIds()) {
            canonical.append('|').append(componentId);
        }
        for (ColumnNextInput.SideDrawInput draw : input.sideDraws()) {
            canonical.append('|').append(draw.stageNumber()).append(':')
                    .append(draw.basis().serializedName()).append(':').append(hex(draw.authoredRate()));
        }
        for (ColumnNextInput.WaterSteamFeedInput utility : input.utilityFeeds()) {
            canonical.append('|').append(utility.mode().serializedName()).append(':')
                    .append(utility.stageNumber()).append(':').append(hex(utility.molarFlowMolPerSecond())).append(':')
                    .append(hex(utility.temperatureKelvin())).append(':').append(hex(utility.upstreamPressurePascal()));
        }
        return sha256(canonical);
    }

    private static String hex(double value) {
        return Long.toHexString(Double.doubleToLongBits(value));
    }

    private static String sha256(CharSequence canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }
}
