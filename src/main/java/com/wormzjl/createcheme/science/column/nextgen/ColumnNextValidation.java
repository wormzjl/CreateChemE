package com.wormzjl.createcheme.science.column.nextgen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validation performed before a next-generation request may allocate a workspace or enter the shared queue. */
public final class ColumnNextValidation {
    public static final int MIN_STAGES = 2;
    public static final int MAX_STAGES = 64;
    public static final double MIN_TOP_PRESSURE_PASCAL = 50_000.0;
    public static final double MAX_TOP_PRESSURE_PASCAL = 2_000_000.0;
    public static final double MAX_STAGE_DROP_PASCAL = 2_000.0;
    public static final double MIN_TEMPERATURE_KELVIN = 298.15;
    public static final double MAX_TEMPERATURE_KELVIN = 900.0;
    public static final double MAX_UTILITY_PRESSURE_PASCAL = 10_000_000.0;

    private ColumnNextValidation() {}

    public static Result validate(ColumnNextInput input) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (input == null) {
            return new Result(List.of(Diagnostic.error("NULL_INPUT", "A next-column input is required")));
        }
        if (input.schemaVersion() != ColumnNextInput.SCHEMA_VERSION) {
            diagnostics.add(Diagnostic.error("UNSUPPORTED_SCHEMA", "Input schema revision is unsupported"));
        }
        if (input.stageCount() < MIN_STAGES || input.stageCount() > MAX_STAGES) {
            diagnostics.add(Diagnostic.error("INVALID_STAGE_COUNT", "Stage count must be in 2..64"));
        }
        validateStage(input.crudeFeedStageNumber(), input.stageCount(), "INVALID_FEED_STAGE", diagnostics);
        finiteRange(input.topPressurePascal(), MIN_TOP_PRESSURE_PASCAL, MAX_TOP_PRESSURE_PASCAL,
                "INVALID_TOP_PRESSURE", diagnostics);
        finiteRange(input.stagePressureDropPascal(), 0.0, MAX_STAGE_DROP_PASCAL,
                "INVALID_STAGE_PRESSURE_DROP", diagnostics);
        finiteRange(input.condenserOutletTemperatureKelvin(), MIN_TEMPERATURE_KELVIN, MAX_TEMPERATURE_KELVIN,
                "INVALID_CONDENSER_TEMPERATURE", diagnostics);
        finiteRange(input.crudeFeed().temperatureKelvin(), MIN_TEMPERATURE_KELVIN, MAX_TEMPERATURE_KELVIN,
                "INVALID_FEED_TEMPERATURE", diagnostics);
        finiteRange(input.crudeFeed().molarFlowMolPerSecond(), 1.0e-9, 1.0e7,
                "INVALID_FEED_FLOW", diagnostics);
        finiteRange(input.organicRefluxRatio(), 0.0, 100.0, "INVALID_REFLUX", diagnostics);
        finiteRange(input.reboilerDutyWatts(), 0.0,
                Math.max(0.0, input.crudeFeed().molarFlowMolPerSecond()) * 200_000.0,
                "INVALID_REBOILER_DUTY", diagnostics);
        if (!Double.isFinite(input.totalPressureDropPascal()) || !Double.isFinite(input.bottomPressurePascal())
                || input.bottomPressurePascal() > MAX_TOP_PRESSURE_PASCAL) {
            diagnostics.add(Diagnostic.error("INVALID_DERIVED_PRESSURE_PROFILE", "Derived pressure profile is invalid"));
        }
        if (input.sideDraws().size() > ColumnNextInput.MAX_SIDE_DRAWS
                || input.utilityFeeds().size() > ColumnNextInput.MAX_UTILITY_FEEDS) {
            diagnostics.add(Diagnostic.error("TOO_MANY_CONNECTIONS", "Input exceeds the schema cardinality cap"));
        }
        Set<Integer> sideStages = new HashSet<>();
        double totalSideMolar = 0.0;
        for (ColumnNextInput.SideDrawInput side : input.sideDraws()) {
            validateStage(side.stageNumber(), input.stageCount(), "INVALID_SIDE_DRAW_STAGE", diagnostics);
            if (!sideStages.add(side.stageNumber())) {
                diagnostics.add(Diagnostic.error("DUPLICATE_SIDE_DRAW_STAGE", "Only one side draw is allowed per stage"));
            }
            if (!Double.isFinite(side.authoredRate()) || side.authoredRate() < 0.0) {
                diagnostics.add(Diagnostic.error("INVALID_SIDE_DRAW_RATE", "Side-draw rate must be finite and nonnegative"));
            } else if (side.basis() == ColumnNextInput.AuthoredBasis.MOLAR) {
                totalSideMolar += side.authoredRate();
            }
        }
        if (totalSideMolar >= input.crudeFeed().molarFlowMolPerSecond()) {
            diagnostics.add(Diagnostic.error("SIDE_DRAWS_EXCEED_FEED", "Molar side draws must leave positive product flow"));
        }
        double utilityTotal = 0.0;
        for (ColumnNextInput.WaterSteamFeedInput utility : input.utilityFeeds()) {
            validateStage(utility.stageNumber(), input.stageCount(), "INVALID_UTILITY_STAGE", diagnostics);
            finiteRange(utility.molarFlowMolPerSecond(), 0.0,
                    2.0 * Math.max(input.crudeFeed().molarFlowMolPerSecond(), 1.0e-9),
                    "INVALID_UTILITY_FLOW", diagnostics);
            finiteRange(utility.temperatureKelvin(), MIN_TEMPERATURE_KELVIN, MAX_TEMPERATURE_KELVIN,
                    "INVALID_UTILITY_TEMPERATURE", diagnostics);
            finiteRange(utility.upstreamPressurePascal(), MIN_TOP_PRESSURE_PASCAL, MAX_UTILITY_PRESSURE_PASCAL,
                    "INVALID_UTILITY_PRESSURE", diagnostics);
            if (utility.stageNumber() >= 1 && utility.stageNumber() <= input.stageCount()
                    && Double.isFinite(utility.upstreamPressurePascal())
                    && Double.isFinite(input.topPressurePascal()) && Double.isFinite(input.stagePressureDropPascal())
                    && utility.upstreamPressurePascal() < input.pressureAtStageNumber(utility.stageNumber())) {
                diagnostics.add(Diagnostic.error(
                        "UTILITY_PRESSURE_BELOW_CONNECTED_STAGE",
                        "Utility feed pressure must not be below its connected stage pressure"));
            }
            if (Double.isFinite(utility.molarFlowMolPerSecond())) {
                utilityTotal += utility.molarFlowMolPerSecond();
            }
        }
        if (utilityTotal > 2.0 * input.crudeFeed().molarFlowMolPerSecond()) {
            diagnostics.add(Diagnostic.error("UTILITY_RATIO_EXCEEDED", "Total utility flow is above the hard ratio cap"));
        }
        if (estimatedWireBytes(input) > ColumnNextInput.MAX_PACKET_BYTES) {
            diagnostics.add(Diagnostic.error("INPUT_TOO_LARGE", "Input exceeds the 64 KiB schema cap"));
        }
        addEnvelopeWarnings(input, diagnostics);
        return new Result(diagnostics);
    }

    public static int estimatedWireBytes(ColumnNextInput input) {
        Objects.requireNonNull(input, "input");
        return 160 + 2 * (input.packageId().length() + input.assayId().length())
                + input.sideDraws().size() * 24 + input.utilityFeeds().size() * 40;
    }

    private static void validateStage(int stage, int count, String code, List<Diagnostic> diagnostics) {
        if (stage < 1 || stage > count) {
            diagnostics.add(Diagnostic.error(code, "Connected stage is outside the column topology"));
        }
    }

    private static void finiteRange(
            double value, double min, double max, String code, List<Diagnostic> diagnostics) {
        if (!Double.isFinite(value) || value < min || value > max) {
            diagnostics.add(Diagnostic.error(code, "Value is outside the supported hard range"));
        }
    }

    private static void addEnvelopeWarnings(ColumnNextInput input, List<Diagnostic> diagnostics) {
        if (input.stageCount() < 10 || input.topPressurePascal() < 100_000.0 || input.topPressurePascal() > 500_000.0
                || input.crudeFeed().temperatureKelvin() < 450.0 || input.crudeFeed().temperatureKelvin() > 750.0
                || input.condenserOutletTemperatureKelvin() > 400.0 || input.organicRefluxRatio() > 10.0
                || input.stagePressureDropPascal() > 2_000.0
                || input.reboilerDutyWatts() > input.crudeFeed().molarFlowMolPerSecond() * 100_000.0) {
            diagnostics.add(Diagnostic.warning("OUTSIDE_VALIDATED_ENVELOPE", "Calculation is inside hard bounds but outside the initial validated envelope"));
        }
    }

    public record Result(List<Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean isValid() {
            return diagnostics.stream().noneMatch(Diagnostic::error);
        }
    }

    public record Diagnostic(String code, String detail, boolean error) {
        public Diagnostic {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }

        public static Diagnostic error(String code, String detail) {
            return new Diagnostic(code, detail, true);
        }

        public static Diagnostic warning(String code, String detail) {
            return new Diagnostic(code, detail, false);
        }
    }
}
