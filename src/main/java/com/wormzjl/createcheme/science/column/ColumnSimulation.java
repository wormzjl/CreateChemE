package com.wormzjl.createcheme.science.column;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Minecraft-independent column contract, validation, and thermodynamic calculation entry point.
 */
public final class ColumnSimulation {
    public static final int INPUT_SCHEMA_VERSION = 1;
    public static final String DUMMY_SOLVER_REVISION = "dummy-column-v1";
    public static final String THERMODYNAMIC_SOLVER_REVISION =
            CounterCurrentColumnSolver.SOLVER_REVISION;

    public static final ColumnAssumptions MILESTONE_1 = new ColumnAssumptions(
            "milestone-1-bare-column-v1",
            250_000.0,
            CondenserType.TOTAL,
            ReboilerType.PARTIAL_EQUILIBRIUM,
            StreamPhase.LIQUID,
            true,
            1.0,
            false,
            false,
            false,
            2,
            64,
            12,
            6,
            1.0e-6,
            1.0e7,
            200.0,
            1_200.0,
            1.0,
            1.0e12,
            0.0,
            100.0,
            1.0e-9);

    private static final int RESULT_SCHEMA_VERSION = 1;
    private static final double MIN_MATRIX_WEIGHT = 1.0e-12;
    private static final Pattern ASSAY_ID =
            Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");
    private static final List<Cut> CUTS = List.of(
            new Cut("cut01", "<50 C", 295.45, 0.060, 0.0834),
            new Cut("cut02", "50-90 C", 348.65, 0.085, 0.1207),
            new Cut("cut03", "90-125 C", 384.15, 0.100, 0.0673),
            new Cut("cut04", "125-175 C", 422.15, 0.120, 0.1299),
            new Cut("cut05", "175-210 C", 460.15, 0.145, 0.0637),
            new Cut("cut06", "210-260 C", 496.95, 0.175, 0.1136),
            new Cut("cut07", "260-305 C", 548.05, 0.210, 0.0933),
            new Cut("cut08", "305-350 C", 598.85, 0.250, 0.0795),
            new Cut("cut09", "350-405 C", 648.95, 0.300, 0.0686),
            new Cut("cut10", "405-480 C", 705.75, 0.370, 0.0648),
            new Cut("cut11", "480-620 C", 809.45, 0.480, 0.0634),
            new Cut("cut12", ">620 C", 992.25, 0.650, 0.0514));

    private ColumnSimulation() {}

    /** Validates canonical-SI input against the fixed Milestone-1 topology. */
    public static ColumnValidationResult validate(ColumnInput input) {
        if (input == null) {
            return new ColumnValidationResult(
                    ColumnDegreesOfFreedom.of(2, 0),
                    List.of(ColumnDiagnostic.error(
                            ColumnFaultCode.NULL_INPUT, "input", "Column input is required")));
        }

        List<ColumnDiagnostic> diagnostics = new ArrayList<>();
        if (input.schemaVersion() != INPUT_SCHEMA_VERSION) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.UNSUPPORTED_SCHEMA_VERSION,
                    "schemaVersion",
                    "Expected schema " + INPUT_SCHEMA_VERSION));
        }
        validateAssayId(input.assayId(), diagnostics);
        validateFiniteRange(
                input.feedMolarFlowMolPerSecond(),
                MILESTONE_1.minFeedMolarFlowMolPerSecond(),
                MILESTONE_1.maxFeedMolarFlowMolPerSecond(),
                "feedMolarFlowMolPerSecond",
                diagnostics);
        validateFiniteRange(
                input.feedTemperatureKelvin(),
                MILESTONE_1.minTemperatureKelvin(),
                MILESTONE_1.maxTemperatureKelvin(),
                "feedTemperatureKelvin",
                diagnostics);
        validateFiniteRange(
                input.reboilerDutyWatts(),
                MILESTONE_1.minReboilerDutyWatts(),
                MILESTONE_1.maxReboilerDutyWatts(),
                "reboilerDutyWatts",
                diagnostics);
        validateFiniteRange(
                input.refluxRatio(),
                MILESTONE_1.minRefluxRatio(),
                MILESTONE_1.maxRefluxRatio(),
                "refluxRatio",
                diagnostics);

        if (input.stageCount() < MILESTONE_1.minStages()
                || input.stageCount() > MILESTONE_1.maxStages()) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.INVALID_STAGE_COUNT,
                    "stageCount",
                    "Allowed range is " + MILESTONE_1.minStages() + ".."
                            + MILESTONE_1.maxStages()));
        }
        if (input.feedStage() < 1 || input.feedStage() > input.stageCount()) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.INVALID_FEED_STAGE,
                    "feedStage",
                    "Feed stage must be inside the main-column stage range"));
        }

        List<SideDrawSpec> sideDraws = input.sideDraws();
        if (sideDraws == null) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.SIDE_DRAW_LIST_REQUIRED,
                    "sideDraws",
                    "Use an empty list when there are no side draws"));
            sideDraws = List.of();
        }
        if (sideDraws.size() > MILESTONE_1.maxSideDraws()) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.TOO_MANY_SIDE_DRAWS,
                    "sideDraws",
                    "Maximum side-draw count is " + MILESTONE_1.maxSideDraws()));
        }

        Set<Integer> occupiedStages = new HashSet<>();
        double totalSideFlow = 0.0;
        int finiteSideRateSpecifications = 0;
        for (int index = 0; index < sideDraws.size(); index++) {
            SideDrawSpec side = sideDraws.get(index);
            if (side == null) {
                diagnostics.add(ColumnDiagnostic.error(
                        ColumnFaultCode.NULL_SIDE_DRAW,
                        "sideDraws",
                        index,
                        "Side draw is required"));
                continue;
            }
            if (side.stage() < 1 || side.stage() > input.stageCount()) {
                diagnostics.add(ColumnDiagnostic.error(
                        ColumnFaultCode.INVALID_SIDE_DRAW_STAGE,
                        "sideDraws.stage",
                        index,
                        "Side-draw stage must be inside the main-column stage range"));
            } else if (!occupiedStages.add(side.stage())) {
                diagnostics.add(ColumnDiagnostic.error(
                        ColumnFaultCode.DUPLICATE_SIDE_DRAW_STAGE,
                        "sideDraws.stage",
                        index,
                        "Only one direct side draw is allowed on a stage"));
            }

            if (!Double.isFinite(side.molarFlowMolPerSecond())) {
                diagnostics.add(ColumnDiagnostic.error(
                        ColumnFaultCode.NON_FINITE_VALUE,
                        "sideDraws.molarFlowMolPerSecond",
                        index,
                        "Value must be finite"));
            } else {
                finiteSideRateSpecifications++;
                if (side.molarFlowMolPerSecond()
                                < MILESTONE_1.minSideDrawMolarFlowMolPerSecond()
                        || side.molarFlowMolPerSecond()
                                > MILESTONE_1.maxFeedMolarFlowMolPerSecond()) {
                    diagnostics.add(ColumnDiagnostic.error(
                            ColumnFaultCode.VALUE_OUT_OF_RANGE,
                            "sideDraws.molarFlowMolPerSecond",
                            index,
                            "Side-draw flow is outside the supported range"));
                }
                totalSideFlow += side.molarFlowMolPerSecond();
            }
        }
        if (Double.isFinite(input.feedMolarFlowMolPerSecond())
                && Double.isFinite(totalSideFlow)
                && totalSideFlow >= input.feedMolarFlowMolPerSecond()) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.TOTAL_SIDE_DRAW_RATE_EXCEEDS_FEED,
                    "sideDraws",
                    "Total side-draw flow must leave positive top and bottom product flow"));
        }

        int requiredSpecifications = 2 + sideDraws.size();
        int suppliedSpecifications = finiteSideRateSpecifications;
        if (Double.isFinite(input.reboilerDutyWatts())) {
            suppliedSpecifications++;
        }
        if (Double.isFinite(input.refluxRatio())) {
            suppliedSpecifications++;
        }

        RefluxCondition reflux = input.refluxCondition();
        if (reflux == null) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.REFLUX_CONDITION_REQUIRED,
                    "refluxCondition",
                    "A saturated or subcooled reflux boundary is required"));
        } else if (reflux.mode() == RefluxMode.SUBCOOLED_LIQUID) {
            requiredSpecifications++;
            if (reflux.temperatureKelvin().isEmpty()) {
                diagnostics.add(ColumnDiagnostic.error(
                        ColumnFaultCode.REFLUX_TEMPERATURE_REQUIRED,
                        "refluxCondition.temperatureKelvin",
                        "Subcooled reflux requires an outlet temperature"));
            } else {
                double temperature = reflux.temperatureKelvin().getAsDouble();
                if (Double.isFinite(temperature)) {
                    suppliedSpecifications++;
                }
                validateFiniteRange(
                        temperature,
                        MILESTONE_1.minTemperatureKelvin(),
                        MILESTONE_1.maxTemperatureKelvin(),
                        "refluxCondition.temperatureKelvin",
                        diagnostics);
            }
        } else if (reflux.temperatureKelvin().isPresent()) {
            if (Double.isFinite(reflux.temperatureKelvin().getAsDouble())) {
                suppliedSpecifications++;
            }
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.REFLUX_TEMPERATURE_NOT_ALLOWED,
                    "refluxCondition.temperatureKelvin",
                    "Saturated reflux temperature is calculated, not specified"));
        }

        ColumnDegreesOfFreedom dof =
                ColumnDegreesOfFreedom.of(requiredSpecifications, suppliedSpecifications);
        if (dof.remainingDegreesOfFreedom() > 0) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.UNDER_SPECIFIED,
                    "specifications",
                    dof.remainingDegreesOfFreedom() + " specification(s) missing"));
        } else if (dof.remainingDegreesOfFreedom() < 0) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.OVER_SPECIFIED,
                    "specifications",
                    -dof.remainingDegreesOfFreedom() + " excess specification(s)"));
        }
        return new ColumnValidationResult(dof, diagnostics);
    }

    /**
     * Runs the first Peng-Robinson crude-column model. The cascade closes component balances and
     * phase equilibrium at fixed pressure and internal traffic, but intentionally leaves the energy
     * equations unsolved and therefore reports an approximate result.
     */
    public static ColumnSolveOutcome calculate(ColumnInput input) {
        ColumnValidationResult validation = validate(input);
        if (!validation.isValid()) {
            return ColumnSolveOutcome.rejected(validation.diagnostics());
        }
        if (!TiaJuanaLight12PropertyPackage.ASSAY_ID.equals(input.assayId())) {
            return ColumnSolveOutcome.rejected(List.of(ColumnDiagnostic.error(
                    ColumnFaultCode.UNSUPPORTED_ASSAY,
                    "assayId",
                    "No thermodynamic property package is registered for " + input.assayId())));
        }

        CounterCurrentColumnSolver.Result cascade = new CounterCurrentColumnSolver().solve(input);
        if (!cascade.converged()) {
            List<ColumnDiagnostic> messages = List.of(ColumnDiagnostic.error(
                    ColumnFaultCode.NO_CONVERGENCE,
                    "solver",
                    "Thermodynamic cascade did not stabilize within "
                            + CounterCurrentColumnSolver.MAXIMUM_SWEEPS + " sweeps"));
            return new ColumnSolveOutcome(
                    ColumnSolveStatus.NO_CONVERGENCE, Optional.empty(), messages);
        }

        String inputDigest = inputDigest(input);
        double[] feedFractions = TiaJuanaLight12PropertyPackage.feedMoleFractions();
        double[] streamFlows = cascade.productFlows();
        double[][] componentFlows = cascade.componentFlows();
        List<StageState> stages = stageProfile(cascade);
        List<ProductStream> products = products(input, streamFlows, componentFlows, stages);
        double condenserDuty = -Math.max(
                0.0, 0.90 * input.reboilerDutyWatts() + streamFlows[0] * 30_000.0);

        ColumnResiduals baseResiduals = residuals(input, feedFractions, products);
        ColumnResiduals residuals = new ColumnResiduals(
                baseResiduals.maximumComponentMaterialResidual(),
                baseResiduals.overallMaterialResidual(),
                OptionalDouble.empty(),
                OptionalDouble.of(cascade.maximumEquilibriumResidual()),
                baseResiduals.maximumCompositionSummationResidual());
        List<ColumnDiagnostic> messages = List.of(ColumnDiagnostic.warning(
                ColumnFaultCode.APPROXIMATE_ENERGY_MODEL,
                "Peng-Robinson phase equilibrium and material balances are active; column energy "
                        + "balances and petroleum critical-property fitting remain approximate"));
        SolverDiagnostics solverDiagnostics = new SolverDiagnostics(
                validation.degreesOfFreedom(),
                "isobaric_fixed_traffic",
                cascade.sweeps(),
                cascade.propertyEvaluations(),
                residuals,
                messages);
        String datasetRevision = TiaJuanaLight12PropertyPackage.DATASET_REVISION;
        String resultDigest = resultDigest(
                inputDigest,
                THERMODYNAMIC_SOLVER_REVISION,
                datasetRevision,
                MILESTONE_1.revision(),
                condenserDuty,
                products,
                stages,
                solverDiagnostics);
        ColumnResult result = new ColumnResult(
                RESULT_SCHEMA_VERSION,
                THERMODYNAMIC_SOLVER_REVISION,
                datasetRevision,
                MILESTONE_1.revision(),
                inputDigest,
                resultDigest,
                products,
                stages,
                condenserDuty,
                solverDiagnostics);
        return ColumnSolveOutcome.approximate(result, messages);
    }

    private static void validateAssayId(String assayId, List<ColumnDiagnostic> diagnostics) {
        if (assayId == null || assayId.isBlank()) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.ASSAY_ID_REQUIRED, "assayId", "Assay ID is required"));
        } else if (assayId.length() > 96 || !ASSAY_ID.matcher(assayId).matches()) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.ASSAY_ID_INVALID,
                    "assayId",
                    "Assay ID must be a lowercase namespaced identifier"));
        }
    }

    private static void validateFiniteRange(
            double value,
            double minimum,
            double maximum,
            String field,
            List<ColumnDiagnostic> diagnostics) {
        if (!Double.isFinite(value)) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.NON_FINITE_VALUE, field, "Value must be finite"));
        } else if (value < minimum || value > maximum) {
            diagnostics.add(ColumnDiagnostic.error(
                    ColumnFaultCode.VALUE_OUT_OF_RANGE,
                    field,
                    "Allowed range is [" + minimum + ", " + maximum + "]"));
        }
    }

    private static double[] normalizedFeedFractions() {
        double sum = CUTS.stream().mapToDouble(Cut::feedFraction).sum();
        double[] fractions = new double[CUTS.size()];
        for (int component = 0; component < fractions.length; component++) {
            fractions[component] = CUTS.get(component).feedFraction() / sum;
        }
        return fractions;
    }

    private static double[] productFlows(ColumnInput input) {
        double sideTotal = input.sideDraws().stream()
                .mapToDouble(SideDrawSpec::molarFlowMolPerSecond)
                .sum();
        double remaining = input.feedMolarFlowMolPerSecond() - sideTotal;
        double specificDuty = input.reboilerDutyWatts() / input.feedMolarFlowMolPerSecond();
        double dutyResponse = Math.tanh((specificDuty - 40_000.0) / 80_000.0);
        double refluxResponse = Math.log1p(input.refluxRatio()) / Math.log(101.0);
        double topShare = clamp(0.34 + 0.12 * dutyResponse + 0.12 * refluxResponse, 0.08, 0.80);

        double[] flows = new double[input.sideDraws().size() + 2];
        flows[0] = remaining * topShare;
        for (int index = 0; index < input.sideDraws().size(); index++) {
            flows[index + 1] = input.sideDraws().get(index).molarFlowMolPerSecond();
        }
        flows[flows.length - 1] = remaining - flows[0];
        return flows;
    }

    private static double[] targetCutIndices(ColumnInput input) {
        double[] targets = new double[input.sideDraws().size() + 2];
        targets[0] = 0.0;
        for (int index = 0; index < input.sideDraws().size(); index++) {
            double stageFraction = (input.sideDraws().get(index).stage() - 0.5) / input.stageCount();
            targets[index + 1] = stageFraction * (CUTS.size() - 1.0);
        }
        targets[targets.length - 1] = CUTS.size() - 1.0;
        return targets;
    }

    private static double[][] allocateConservatively(
            double feedFlow, double[] feedFractions, double[] streamFlows, double[] targets) {
        double[][] allocation = new double[feedFractions.length][streamFlows.length];
        double spread = 1.75;
        for (int component = 0; component < allocation.length; component++) {
            for (int stream = 0; stream < streamFlows.length; stream++) {
                double distance = (component - targets[stream]) / spread;
                allocation[component][stream] = Math.exp(-0.5 * distance * distance)
                        + MIN_MATRIX_WEIGHT;
            }
        }

        for (int iteration = 0; iteration < 256; iteration++) {
            for (int component = 0; component < allocation.length; component++) {
                double rowSum = 0.0;
                for (double value : allocation[component]) {
                    rowSum += value;
                }
                double scale = feedFlow * feedFractions[component] / rowSum;
                for (int stream = 0; stream < streamFlows.length; stream++) {
                    allocation[component][stream] *= scale;
                }
            }
            for (int stream = 0; stream < streamFlows.length; stream++) {
                double columnSum = 0.0;
                for (double[] componentAllocation : allocation) {
                    columnSum += componentAllocation[stream];
                }
                double scale = streamFlows[stream] / columnSum;
                for (double[] componentAllocation : allocation) {
                    componentAllocation[stream] *= scale;
                }
            }
        }
        return allocation;
    }

    private static List<StageState> stageProfile(CounterCurrentColumnSolver.Result cascade) {
        double[] temperatures = cascade.temperatures();
        double[] liquidFlows = cascade.liquidFlows();
        List<StageState> stages = new ArrayList<>(temperatures.length);
        for (int stage = 0; stage < temperatures.length; stage++) {
            stages.add(new StageState(
                    stage + 1, temperatures[stage], liquidFlows[stage], cascade.vaporFlow()));
        }
        return stages;
    }
    private static List<StageState> stageProfile(ColumnInput input) {
        double refluxTemperature = input.refluxCondition()
                .temperatureKelvin()
                .orElse(clamp(input.feedTemperatureKelvin() - 150.0, 250.0, 520.0));
        double specificDuty = input.reboilerDutyWatts() / input.feedMolarFlowMolPerSecond();
        double bottomTemperature = clamp(
                input.feedTemperatureKelvin() + 50.0 + specificDuty / 200_000.0,
                refluxTemperature + 20.0,
                Math.max(refluxTemperature + 20.0, 1_000.0));
        double baseLiquid = input.feedMolarFlowMolPerSecond()
                * (0.55 + 0.35 * input.refluxRatio() / (1.0 + input.refluxRatio()));
        double baseVapor = input.feedMolarFlowMolPerSecond()
                * clamp(0.30 + specificDuty / 250_000.0, 0.15, 3.0);

        List<StageState> profile = new ArrayList<>(input.stageCount());
        for (int stage = 1; stage <= input.stageCount(); stage++) {
            double position = (stage - 1.0) / (input.stageCount() - 1.0);
            double smoothPosition = position * position * (3.0 - 2.0 * position);
            double temperature =
                    refluxTemperature + smoothPosition * (bottomTemperature - refluxTemperature);
            profile.add(new StageState(
                    stage,
                    temperature,
                    baseLiquid * (1.0 + 0.12 * position),
                    baseVapor * (1.0 - 0.08 * position)));
        }
        return profile;
    }

    private static List<ProductStream> products(
            ColumnInput input,
            double[] streamFlows,
            double[][] componentFlows,
            List<StageState> stages) {
        List<ProductStream> products = new ArrayList<>(streamFlows.length);
        for (int stream = 0; stream < streamFlows.length; stream++) {
            ProductKind kind;
            RateSpecification rateSpecification;
            String streamId;
            String label;
            double temperature;
            if (stream == 0) {
                kind = ProductKind.TOP;
                rateSpecification = RateSpecification.CALCULATED;
                streamId = "TOP";
                label = "Top product";
                temperature = input.refluxCondition()
                        .temperatureKelvin()
                        .orElse(stages.get(0).temperatureKelvin());
            } else if (stream == streamFlows.length - 1) {
                kind = ProductKind.BOTTOM;
                rateSpecification = RateSpecification.CALCULATED;
                streamId = "BOTTOM";
                label = "Bottom product";
                temperature = stages.get(stages.size() - 1).temperatureKelvin();
            } else {
                kind = ProductKind.SIDE;
                rateSpecification = RateSpecification.SPECIFIED;
                streamId = "SIDE_" + stream;
                label = "Side product " + stream;
                temperature = stages.get(input.sideDraws().get(stream - 1).stage() - 1)
                        .temperatureKelvin();
            }

            double[] moleFractions = new double[CUTS.size()];
            double averageMolecularWeight = 0.0;
            for (int component = 0; component < CUTS.size(); component++) {
                moleFractions[component] = componentFlows[component][stream] / streamFlows[stream];
                averageMolecularWeight +=
                        moleFractions[component] * CUTS.get(component).molecularWeightKgPerMol();
            }
            List<ComponentFraction> composition = new ArrayList<>(CUTS.size());
            for (int component = 0; component < CUTS.size(); component++) {
                Cut cut = CUTS.get(component);
                double massFraction = moleFractions[component]
                        * cut.molecularWeightKgPerMol()
                        / averageMolecularWeight;
                composition.add(new ComponentFraction(
                        cut.id(), cut.boilingRange(), moleFractions[component], massFraction));
            }
            products.add(new ProductStream(
                    streamId,
                    label,
                    kind,
                    rateSpecification,
                    streamFlows[stream],
                    streamFlows[stream] * averageMolecularWeight,
                    temperature,
                    MILESTONE_1.uniformPressurePascal(),
                    StreamPhase.LIQUID,
                    composition,
                    new BoilingRangeSummary(
                            quantileTemperature(moleFractions, 0.05),
                            quantileTemperature(moleFractions, 0.50),
                            quantileTemperature(moleFractions, 0.95))));
        }
        return products;
    }

    private static ColumnResiduals residuals(
            ColumnInput input, double[] feedFractions, List<ProductStream> products) {
        double outputFlow = products.stream().mapToDouble(ProductStream::molarFlowMolPerSecond).sum();
        double overallResidual = Math.abs(outputFlow - input.feedMolarFlowMolPerSecond())
                / input.feedMolarFlowMolPerSecond();
        double maximumComponentResidual = 0.0;
        double maximumSummationResidual = 0.0;
        for (int component = 0; component < CUTS.size(); component++) {
            double outputComponentFlow = 0.0;
            for (ProductStream product : products) {
                outputComponentFlow += product.molarFlowMolPerSecond()
                        * product.composition().get(component).moleFraction();
            }
            double feedComponentFlow = input.feedMolarFlowMolPerSecond() * feedFractions[component];
            maximumComponentResidual = Math.max(
                    maximumComponentResidual,
                    Math.abs(outputComponentFlow - feedComponentFlow)
                            / Math.max(feedComponentFlow, 1.0e-30));
        }
        for (ProductStream product : products) {
            double moleSum = product.composition().stream()
                    .mapToDouble(ComponentFraction::moleFraction)
                    .sum();
            maximumSummationResidual =
                    Math.max(maximumSummationResidual, Math.abs(moleSum - 1.0));
        }
        return new ColumnResiduals(
                OptionalDouble.of(maximumComponentResidual),
                OptionalDouble.of(overallResidual),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.of(maximumSummationResidual));
    }

    private static double quantileTemperature(double[] fractions, double quantile) {
        double cumulative = 0.0;
        for (int component = 0; component < fractions.length; component++) {
            cumulative += fractions[component];
            if (cumulative >= quantile) {
                return CUTS.get(component).representativeBoilingPointKelvin();
            }
        }
        return CUTS.get(CUTS.size() - 1).representativeBoilingPointKelvin();
    }

    private static String inputDigest(ColumnInput input) {
        StringBuilder value = new StringBuilder(256)
                .append(input.schemaVersion()).append('|')
                .append(input.assayId()).append('|')
                .append(hex(input.feedMolarFlowMolPerSecond())).append('|')
                .append(hex(input.feedTemperatureKelvin())).append('|')
                .append(input.stageCount()).append('|')
                .append(input.feedStage()).append('|')
                .append(hex(input.reboilerDutyWatts())).append('|')
                .append(hex(input.refluxRatio())).append('|')
                .append(input.refluxCondition().mode().serializedName());
        input.refluxCondition()
                .temperatureKelvin()
                .ifPresentOrElse(
                        temperature -> value.append('|').append(hex(temperature)),
                        () -> value.append("|none"));
        for (SideDrawSpec side : input.sideDraws()) {
            value.append('|')
                    .append(side.stage())
                    .append(':')
                    .append(hex(side.molarFlowMolPerSecond()));
        }
        return sha256(value);
    }

    private static String resultDigest(
            String inputDigest,
            String solverRevision,
            String datasetRevision,
            String assumptionsRevision,
            double condenserDutyWatts,
            List<ProductStream> products,
            List<StageState> stages,
            SolverDiagnostics diagnostics) {
        StringBuilder value = new StringBuilder(2_048)
                .append(RESULT_SCHEMA_VERSION).append('|')
                .append(inputDigest).append('|')
                .append(solverRevision).append('|')
                .append(datasetRevision).append('|')
                .append(assumptionsRevision).append('|')
                .append(hex(condenserDutyWatts));
        for (ProductStream product : products) {
            value.append('|').append(product.streamId())
                    .append(':').append(product.displayLabel())
                    .append(':').append(product.kind())
                    .append(':').append(product.rateSpecification())
                    .append(':').append(hex(product.molarFlowMolPerSecond()))
                    .append(':').append(hex(product.massFlowKilogramPerSecond()))
                    .append(':').append(hex(product.temperatureKelvin()))
                    .append(':').append(hex(product.pressurePascal()))
                    .append(':').append(product.phase())
                    .append(':').append(hex(product.boilingRange().t5Kelvin()))
                    .append(':').append(hex(product.boilingRange().t50Kelvin()))
                    .append(':').append(hex(product.boilingRange().t95Kelvin()));
            for (ComponentFraction fraction : product.composition()) {
                value.append(':').append(fraction.componentId())
                        .append(',').append(fraction.boilingRangeLabel())
                        .append('=').append(hex(fraction.moleFraction()))
                        .append(',').append(hex(fraction.massFraction()));
            }
        }
        for (StageState stage : stages) {
            value.append('|').append(stage.stage())
                    .append(':').append(hex(stage.temperatureKelvin()))
                    .append(':').append(hex(stage.liquidMolarFlowMolPerSecond()))
                    .append(':').append(hex(stage.vaporMolarFlowMolPerSecond()));
        }
        value.append('|').append(diagnostics.degreesOfFreedom().requiredSpecifications())
                .append(':').append(diagnostics.degreesOfFreedom().suppliedSpecifications())
                .append(':').append(diagnostics.degreesOfFreedom().remainingDegreesOfFreedom())
                .append(':').append(diagnostics.initializationMode())
                .append(':').append(diagnostics.iterations())
                .append(':').append(diagnostics.propertyEvaluations());
        ColumnResiduals residuals = diagnostics.residuals();
        appendOptional(value, residuals.maximumComponentMaterialResidual());
        appendOptional(value, residuals.overallMaterialResidual());
        appendOptional(value, residuals.relativeEnergyResidual());
        appendOptional(value, residuals.maximumEquilibriumResidual());
        appendOptional(value, residuals.maximumCompositionSummationResidual());
        for (ColumnDiagnostic message : diagnostics.messages()) {
            value.append('|').append(message.severity())
                    .append(':').append(message.code().wireCode())
                    .append(':').append(message.field())
                    .append(':').append(message.itemIndex())
                    .append(':').append(message.detail());
        }
        return sha256(value);
    }

    private static void appendOptional(StringBuilder value, OptionalDouble number) {
        value.append(':');
        if (number.isPresent()) {
            value.append(hex(number.getAsDouble()));
        } else {
            value.append("none");
        }
    }

    private static String hex(double value) {
        return Long.toHexString(Double.doubleToLongBits(value));
    }

    private static String sha256(CharSequence value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public enum CondenserType {
        TOTAL
    }

    public enum ReboilerType {
        PARTIAL_EQUILIBRIUM
    }

    /** Stable reflux boundary identifiers used by data, digests, and network adapters. */
    public enum RefluxMode {
        SATURATED_LIQUID("saturated_liquid"),
        SUBCOOLED_LIQUID("subcooled_liquid");

        private final String serializedName;

        RefluxMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static Optional<RefluxMode> fromSerializedName(String serializedName) {
            for (RefluxMode mode : values()) {
                if (mode.serializedName.equals(serializedName)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    public enum StreamPhase {
        LIQUID,
        VAPOR,
        TWO_PHASE
    }

    public enum ProductKind {
        TOP,
        SIDE,
        BOTTOM
    }

    public enum RateSpecification {
        SPECIFIED,
        CALCULATED
    }

    public enum DiagnosticSeverity {
        INFO,
        WARNING,
        ERROR
    }

    public enum ColumnSolveStatus {
        CONVERGED,
        DUMMY_RESULT,
        APPROXIMATE_RESULT,
        REJECTED_INPUT,
        INFEASIBLE,
        NO_CONVERGENCE,
        PROPERTY_RANGE,
        INTERNAL_ERROR
    }

    /** Stable wire codes are append-only; changed semantics require a new value. */
    public enum ColumnFaultCode {
        NULL_INPUT("column.input.null"),
        UNSUPPORTED_SCHEMA_VERSION("column.input.schema_unsupported"),
        ASSAY_ID_REQUIRED("column.input.assay_required"),
        ASSAY_ID_INVALID("column.input.assay_invalid"),
        UNSUPPORTED_ASSAY("column.input.assay_unsupported"),
        NON_FINITE_VALUE("column.input.non_finite"),
        VALUE_OUT_OF_RANGE("column.input.out_of_range"),
        INVALID_STAGE_COUNT("column.input.stage_count_invalid"),
        INVALID_FEED_STAGE("column.input.feed_stage_invalid"),
        SIDE_DRAW_LIST_REQUIRED("column.input.side_draw_list_required"),
        TOO_MANY_SIDE_DRAWS("column.input.too_many_side_draws"),
        NULL_SIDE_DRAW("column.input.side_draw_null"),
        INVALID_SIDE_DRAW_STAGE("column.input.side_draw_stage_invalid"),
        DUPLICATE_SIDE_DRAW_STAGE("column.input.side_draw_stage_duplicate"),
        TOTAL_SIDE_DRAW_RATE_EXCEEDS_FEED("column.input.side_draw_total_exceeds_feed"),
        REFLUX_CONDITION_REQUIRED("column.input.reflux_condition_required"),
        REFLUX_TEMPERATURE_REQUIRED("column.input.reflux_temperature_required"),
        REFLUX_TEMPERATURE_NOT_ALLOWED("column.input.reflux_temperature_not_allowed"),
        UNDER_SPECIFIED("column.input.under_specified"),
        OVER_SPECIFIED("column.input.over_specified"),
        PHASE_INCOMPATIBLE("column.solve.phase_incompatible"),
        PROPERTY_OUT_OF_RANGE("column.solve.property_out_of_range"),
        INFEASIBLE_FLOW("column.solve.infeasible_flow"),
        NO_CONVERGENCE("column.solve.no_convergence"),
        DUMMY_SOLVER_ACTIVE("column.solve.dummy_active"),
        APPROXIMATE_ENERGY_MODEL("column.solve.approximate_energy"),
        INTERNAL_INVARIANT("column.solve.internal_invariant");

        private final String wireCode;

        ColumnFaultCode(String wireCode) {
            this.wireCode = wireCode;
        }

        public String wireCode() {
            return wireCode;
        }
    }

    /** Fixed scientific choices and canonical-SI safety limits absent from the GUI. */
    public record ColumnAssumptions(
            String revision,
            double uniformPressurePascal,
            CondenserType condenserType,
            ReboilerType reboilerType,
            StreamPhase sideDrawPhase,
            boolean adiabaticStages,
            double equilibriumStageEfficiency,
            boolean pressureDropEnabled,
            boolean sideStrippersEnabled,
            boolean pumparoundsEnabled,
            int minStages,
            int maxStages,
            int maxComponents,
            int maxSideDraws,
            double minFeedMolarFlowMolPerSecond,
            double maxFeedMolarFlowMolPerSecond,
            double minTemperatureKelvin,
            double maxTemperatureKelvin,
            double minReboilerDutyWatts,
            double maxReboilerDutyWatts,
            double minRefluxRatio,
            double maxRefluxRatio,
            double minSideDrawMolarFlowMolPerSecond) {
        public ColumnAssumptions {
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(condenserType, "condenserType");
            Objects.requireNonNull(reboilerType, "reboilerType");
            Objects.requireNonNull(sideDrawPhase, "sideDrawPhase");
            if (revision.isBlank()
                    || !Double.isFinite(uniformPressurePascal)
                    || uniformPressurePascal <= 0.0
                    || !Double.isFinite(equilibriumStageEfficiency)
                    || equilibriumStageEfficiency <= 0.0
                    || equilibriumStageEfficiency > 1.0
                    || minStages < 1
                    || maxStages < minStages
                    || maxComponents < 1
                    || maxSideDraws < 0) {
                throw new IllegalArgumentException("Invalid fixed column assumptions");
            }
        }
    }

    public record RefluxCondition(RefluxMode mode, OptionalDouble temperatureKelvin) {
        public RefluxCondition {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(temperatureKelvin, "temperatureKelvin");
        }

        public static RefluxCondition saturatedLiquid() {
            return new RefluxCondition(RefluxMode.SATURATED_LIQUID, OptionalDouble.empty());
        }

        public static RefluxCondition subcooledLiquid(double temperatureKelvin) {
            return new RefluxCondition(
                    RefluxMode.SUBCOOLED_LIQUID, OptionalDouble.of(temperatureKelvin));
        }
    }

    public record SideDrawSpec(int stage, double molarFlowMolPerSecond) {}

    /** Canonical-SI user input; its side-draw list is defensively copied and immutable. */
    public record ColumnInput(
            int schemaVersion,
            String assayId,
            double feedMolarFlowMolPerSecond,
            double feedTemperatureKelvin,
            int stageCount,
            int feedStage,
            double reboilerDutyWatts,
            double refluxRatio,
            RefluxCondition refluxCondition,
            List<SideDrawSpec> sideDraws) {
        public ColumnInput {
            if (sideDraws != null) {
                sideDraws = Collections.unmodifiableList(new ArrayList<>(sideDraws));
            }
        }
    }

    public record ColumnDiagnostic(
            DiagnosticSeverity severity,
            ColumnFaultCode code,
            String field,
            int itemIndex,
            String detail) {
        public static final int NO_ITEM = -1;

        public ColumnDiagnostic {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(code, "code");
            field = field == null ? "" : field;
            detail = detail == null ? "" : detail;
        }

        public static ColumnDiagnostic error(
                ColumnFaultCode code, String field, String detail) {
            return new ColumnDiagnostic(DiagnosticSeverity.ERROR, code, field, NO_ITEM, detail);
        }

        public static ColumnDiagnostic error(
                ColumnFaultCode code, String field, int itemIndex, String detail) {
            return new ColumnDiagnostic(DiagnosticSeverity.ERROR, code, field, itemIndex, detail);
        }

        public static ColumnDiagnostic warning(ColumnFaultCode code, String detail) {
            return new ColumnDiagnostic(
                    DiagnosticSeverity.WARNING, code, "", NO_ITEM, detail);
        }
    }

    public record ColumnDegreesOfFreedom(
            int requiredSpecifications,
            int suppliedSpecifications,
            int remainingDegreesOfFreedom) {
        public ColumnDegreesOfFreedom {
            if (requiredSpecifications < 0
                    || suppliedSpecifications < 0
                    || remainingDegreesOfFreedom
                            != requiredSpecifications - suppliedSpecifications) {
                throw new IllegalArgumentException("Invalid degree-of-freedom count");
            }
        }

        public static ColumnDegreesOfFreedom of(int required, int supplied) {
            return new ColumnDegreesOfFreedom(required, supplied, required - supplied);
        }

        public boolean isSquare() {
            return remainingDegreesOfFreedom == 0;
        }
    }

    public record ColumnValidationResult(
            ColumnDegreesOfFreedom degreesOfFreedom, List<ColumnDiagnostic> diagnostics) {
        public ColumnValidationResult {
            Objects.requireNonNull(degreesOfFreedom, "degreesOfFreedom");
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean isValid() {
            return degreesOfFreedom.isSquare()
                    && diagnostics.stream()
                            .noneMatch(diagnostic ->
                                    diagnostic.severity() == DiagnosticSeverity.ERROR);
        }
    }

    public record ComponentFraction(
            String componentId,
            String boilingRangeLabel,
            double moleFraction,
            double massFraction) {
        public ComponentFraction {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(boilingRangeLabel, "boilingRangeLabel");
        }
    }

    public record BoilingRangeSummary(
            double t5Kelvin, double t50Kelvin, double t95Kelvin) {}

    public record ProductStream(
            String streamId,
            String displayLabel,
            ProductKind kind,
            RateSpecification rateSpecification,
            double molarFlowMolPerSecond,
            double massFlowKilogramPerSecond,
            double temperatureKelvin,
            double pressurePascal,
            StreamPhase phase,
            List<ComponentFraction> composition,
            BoilingRangeSummary boilingRange) {
        public ProductStream {
            Objects.requireNonNull(streamId, "streamId");
            displayLabel = displayLabel == null ? "" : displayLabel;
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(rateSpecification, "rateSpecification");
            Objects.requireNonNull(phase, "phase");
            composition = List.copyOf(composition);
            Objects.requireNonNull(boilingRange, "boilingRange");
        }
    }

    public record StageState(
            int stage,
            double temperatureKelvin,
            double liquidMolarFlowMolPerSecond,
            double vaporMolarFlowMolPerSecond) {}

    /** Empty optionals mean "not evaluated"; the dummy never fabricates zero residuals. */
    public record ColumnResiduals(
            OptionalDouble maximumComponentMaterialResidual,
            OptionalDouble overallMaterialResidual,
            OptionalDouble relativeEnergyResidual,
            OptionalDouble maximumEquilibriumResidual,
            OptionalDouble maximumCompositionSummationResidual) {
        public ColumnResiduals {
            Objects.requireNonNull(
                    maximumComponentMaterialResidual, "maximumComponentMaterialResidual");
            Objects.requireNonNull(overallMaterialResidual, "overallMaterialResidual");
            Objects.requireNonNull(relativeEnergyResidual, "relativeEnergyResidual");
            Objects.requireNonNull(maximumEquilibriumResidual, "maximumEquilibriumResidual");
            Objects.requireNonNull(
                    maximumCompositionSummationResidual,
                    "maximumCompositionSummationResidual");
        }
    }

    public record SolverDiagnostics(
            ColumnDegreesOfFreedom degreesOfFreedom,
            String initializationMode,
            int iterations,
            int propertyEvaluations,
            ColumnResiduals residuals,
            List<ColumnDiagnostic> messages) {
        public SolverDiagnostics {
            Objects.requireNonNull(degreesOfFreedom, "degreesOfFreedom");
            Objects.requireNonNull(initializationMode, "initializationMode");
            if (iterations < 0 || propertyEvaluations < 0) {
                throw new IllegalArgumentException("Solver counts must be nonnegative");
            }
            Objects.requireNonNull(residuals, "residuals");
            messages = List.copyOf(messages);
        }
    }

    public record ColumnResult(
            int schemaVersion,
            String solverRevision,
            String datasetRevision,
            String assumptionsRevision,
            String inputDigest,
            String resultDigest,
            List<ProductStream> products,
            List<StageState> stages,
            double condenserDutyWatts,
            SolverDiagnostics diagnostics) {
        public ColumnResult {
            Objects.requireNonNull(solverRevision, "solverRevision");
            Objects.requireNonNull(datasetRevision, "datasetRevision");
            Objects.requireNonNull(assumptionsRevision, "assumptionsRevision");
            Objects.requireNonNull(inputDigest, "inputDigest");
            Objects.requireNonNull(resultDigest, "resultDigest");
            products = List.copyOf(products);
            stages = List.copyOf(stages);
            Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    public record ColumnSolveOutcome(
            ColumnSolveStatus status,
            Optional<ColumnResult> result,
            List<ColumnDiagnostic> diagnostics) {
        public ColumnSolveOutcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(result, "result");
            diagnostics = List.copyOf(diagnostics);
            boolean successful = status == ColumnSolveStatus.CONVERGED
                    || status == ColumnSolveStatus.DUMMY_RESULT
                    || status == ColumnSolveStatus.APPROXIMATE_RESULT;
            if (successful != result.isPresent()) {
                throw new IllegalArgumentException("Successful statuses require exactly one result");
            }
        }

        public static ColumnSolveOutcome rejected(List<ColumnDiagnostic> diagnostics) {
            return new ColumnSolveOutcome(
                    ColumnSolveStatus.REJECTED_INPUT, Optional.empty(), diagnostics);
        }

        public static ColumnSolveOutcome dummy(
                ColumnResult result, List<ColumnDiagnostic> diagnostics) {
            return new ColumnSolveOutcome(
                    ColumnSolveStatus.DUMMY_RESULT, Optional.of(result), diagnostics);
        }

        public static ColumnSolveOutcome approximate(
                ColumnResult result, List<ColumnDiagnostic> diagnostics) {
            return new ColumnSolveOutcome(
                    ColumnSolveStatus.APPROXIMATE_RESULT, Optional.of(result), diagnostics);
        }

        public boolean hasResult() {
            return result.isPresent();
        }
    }

    private record Cut(
            String id,
            String boilingRange,
            double representativeBoilingPointKelvin,
            double molecularWeightKgPerMol,
            double feedFraction) {}
}
