package com.wormzjl.createcheme.science.column.nextgen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Compact, immutable reporting projection of one accepted next-column solve.
 *
 * <p>The primitive solver result remains the scientific source of truth. This projection contains only the
 * stable external/internal stream columns needed by the menu, packet, and persisted block view; it never creates
 * another solver or rescales component flows.</p>
 */
public record NextColumnResultView(
        String solverRevision,
        String datasetRevision,
        String assumptionsRevision,
        String inputDigest,
        String resultDigest,
        String initializationMode,
        double condenserDutyWatts,
        List<String> componentAxis,
        List<Stream> streams,
        List<String> diagnostics) {
    public static final String ASSUMPTIONS_REVISION = "next-cdu-assumptions-r1";
    public static final int COMPONENT_COUNT = 17;
    public static final int MAX_STREAMS = 24;
    public static final int MAX_DIAGNOSTICS = 32;

    public NextColumnResultView {
        solverRevision = boundedIdentifier(solverRevision, "solverRevision");
        datasetRevision = boundedIdentifier(datasetRevision, "datasetRevision");
        assumptionsRevision = boundedIdentifier(assumptionsRevision, "assumptionsRevision");
        inputDigest = boundedIdentifier(inputDigest, "inputDigest");
        resultDigest = boundedIdentifier(resultDigest, "resultDigest");
        initializationMode = boundedIdentifier(initializationMode, "initializationMode");
        if (!Double.isFinite(condenserDutyWatts)) throw new IllegalArgumentException("Condenser duty must be finite");
        componentAxis = List.copyOf(componentAxis);
        streams = List.copyOf(streams);
        diagnostics = List.copyOf(diagnostics);
        if (componentAxis.size() != COMPONENT_COUNT || componentAxis.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("The reported component axis must contain the stable 17 rows");
        }
        if (streams.isEmpty() || streams.size() > MAX_STREAMS) {
            throw new IllegalArgumentException("Reported stream count is outside its bounded contract");
        }
        if (diagnostics.size() > MAX_DIAGNOSTICS
                || diagnostics.stream().anyMatch(value -> value == null || value.length() > 256)) {
            throw new IllegalArgumentException("Reported diagnostics exceed their bounded contract");
        }
    }

    public static NextColumnResultView fromAccepted(
            ColumnProblem problem, DryColumnOutcome.Success success, String initializationMode) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(success, "success");
        ComponentBasis basis = problem.propertyPackage().basis();
        if (basis.publicAxisIds().size() != COMPONENT_COUNT || basis.hydrocarbonCount() != 16 || basis.waterIndex() != 16) {
            throw new IllegalArgumentException("The next CDU stream view requires the stable 16-hydrocarbon plus water axis");
        }
        DryColumnResult result = success.result();
        ColumnNextInput input = problem.input();
        double[] temperatures = result.nodeTemperaturesKelvin();
        double[] pressures = result.nodePressuresPascal();
        double[] waterVapor = result.waterVaporFlows();
        double[] aqueousWater = result.aqueousWaterFlows();
        List<Stream> streams = new ArrayList<>();

        double[] crude = hydrocarbonVector(problem.feed(), input.crudeFeed().molarFlowMolPerSecond());
        streams.add(new Stream("crude_feed", "Crude feed", Role.IN, input.crudeFeedStageNumber(), "CALCULATED",
                input.crudeFeed().temperatureKelvin(), problem.nodePressurePascal(input.crudeFeedStageNumber()), crude));
        for (ColumnNextInput.WaterSteamFeedInput utility : input.utilityFeeds()) {
            double[] flow = new double[COMPONENT_COUNT];
            flow[16] = utility.molarFlowMolPerSecond();
            streams.add(new Stream("utility_" + utility.mode().serializedName() + "_" + utility.stageNumber(),
                    utility.mode() == ColumnNextInput.UtilityFeedMode.STEAM ? "Steam" : "Water",
                    Role.IN, utility.stageNumber(), utility.mode() == ColumnNextInput.UtilityFeedMode.STEAM ? "VAPOR" : "AQ_LIQ",
                    utility.temperatureKelvin(), utility.upstreamPressurePascal(), flow));
        }

        double[] overhead = publicVector(result.externalOverheadComponentFlows());
        overhead[16] = waterVapor[0] + aqueousWater[0];
        streams.add(new Stream("overhead", "Overhead combined", Role.OUT, 0, phaseFor(overhead, waterVapor[0], aqueousWater[0]),
                temperatures[0], pressures[0], overhead));
        streams.add(new Stream("hydrocarbon_reflux", "Hydrocarbon reflux", Role.INTERNAL, 0, "HC_LIQ",
                temperatures[0], pressures[0], publicVector(result.hydrocarbonRefluxComponentFlows())));

        double[][] sideDraws = result.hydrocarbonSideDrawComponentFlows();
        for (ColumnNextInput.SideDrawInput draw : input.sideDraws()) {
            streams.add(new Stream("side_draw_" + draw.stageNumber(), "Hydrocarbon side draw", Role.OUT,
                    draw.stageNumber(), "HC_LIQ", temperatures[draw.stageNumber()], pressures[draw.stageNumber()],
                    publicVector(sideDraws[draw.stageNumber()])));
        }
        int bottom = result.stageCount() + 1;
        streams.add(new Stream("hydrocarbon_bottoms", "Hydrocarbon bottoms", Role.OUT, result.stageCount(), "HC_LIQ",
                temperatures[bottom], pressures[bottom], publicVector(result.hydrocarbonBottomsComponentFlows())));
        double[] bottomWater = new double[COMPONENT_COUNT];
        bottomWater[16] = aqueousWater[bottom];
        streams.add(new Stream("bottom_aqueous", "Bottom aqueous drain", Role.OUT, result.stageCount(), "AQ_LIQ",
                temperatures[bottom], pressures[bottom], bottomWater));

        String inputDigest = NextInputDigest.of(problem, DryInsideOutColumnSolver.SOLVER_REVISION, ASSUMPTIONS_REVISION);
        List<String> diagnostics = compactDiagnostics(success.diagnostics(), initializationMode);
        String resultDigest = digest(inputDigest, result.condenserDutyWatts(), streams);
        return new NextColumnResultView(DryInsideOutColumnSolver.SOLVER_REVISION, problem.propertyPackage().datasetRevision(), ASSUMPTIONS_REVISION,
                inputDigest, resultDigest, initializationMode, result.condenserDutyWatts(), basis.publicAxisIds(), streams,
                diagnostics);
    }

    public enum Role { IN, OUT, INTERNAL }

    public record Stream(
            String id, String label, Role role, int connectedStage, String phase,
            double temperatureKelvin, double pressurePascal, double[] componentMolarFlows) {
        public Stream {
            id = boundedIdentifier(id, "stream id");
            label = boundedIdentifier(label, "stream label");
            role = Objects.requireNonNull(role, "role");
            phase = boundedIdentifier(phase, "phase");
            if (connectedStage < 0 || !Double.isFinite(temperatureKelvin) || !Double.isFinite(pressurePascal)
                    || pressurePascal <= 0.0 || componentMolarFlows == null || componentMolarFlows.length != COMPONENT_COUNT) {
                throw new IllegalArgumentException("Invalid compact stream state");
            }
            componentMolarFlows = componentMolarFlows.clone();
            for (double value : componentMolarFlows) {
                if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Invalid component stream flow");
            }
        }

        @Override public double[] componentMolarFlows() { return componentMolarFlows.clone(); }

        public double molarFlowMolPerSecond() {
            double total = 0.0;
            for (double value : componentMolarFlows) total += value;
            return total;
        }

        public double massFlowKilogramPerSecond(ComponentBasis basis) {
            double total = 0.0;
            for (int index = 0; index < COMPONENT_COUNT; index++) {
                total += componentMolarFlows[index] * basis.components().get(index).molecularWeightKgPerMol();
            }
            return total;
        }
    }

    private static double[] hydrocarbonVector(CharacterizedFeed feed, double flow) {
        double[] vector = new double[COMPONENT_COUNT];
        for (int component = 0; component < 16; component++) vector[component] = flow * feed.moleFraction(component);
        return vector;
    }

    private static double[] publicVector(double[] hydrocarbon) {
        if (hydrocarbon.length != 16) throw new IllegalArgumentException("Expected the 16-row hydrocarbon axis");
        double[] vector = new double[COMPONENT_COUNT];
        System.arraycopy(hydrocarbon, 0, vector, 0, hydrocarbon.length);
        return vector;
    }

    private static String phaseFor(double[] overall, double vaporWater, double aqueousWater) {
        boolean hydrocarbon = false;
        for (int index = 0; index < 16; index++) hydrocarbon |= overall[index] > 0.0;
        if (aqueousWater > 0.0 && (hydrocarbon || vaporWater > 0.0)) return "MULTIPHASE";
        if (aqueousWater > 0.0) return "AQ_LIQ";
        return "VAPOR";
    }

    private static List<String> compactDiagnostics(DrySolverDiagnostics diagnostics, String initializationMode) {
        List<String> messages = new ArrayList<>();
        messages.add("INIT=" + initializationMode);
        messages.add("OUTER=" + diagnostics.outerIterations() + " INNER=" + diagnostics.innerIterations());
        messages.add("EOS=" + diagnostics.propertyPhaseEvaluations() + " THOMAS=" + diagnostics.thomasSolves());
        messages.add("RECOVERY=" + diagnostics.recoveryPath());
        messages.addAll(diagnostics.events());
        return messages.stream().limit(MAX_DIAGNOSTICS).toList();
    }

    private static String digest(String inputDigest, double condenserDuty, List<Stream> streams) {
        StringBuilder canonical = new StringBuilder(inputDigest).append('|').append(Double.doubleToLongBits(condenserDuty));
        for (Stream stream : streams) {
            canonical.append('|').append(stream.id()).append(':').append(stream.phase()).append(':')
                    .append(Double.doubleToLongBits(stream.temperatureKelvin())).append(':')
                    .append(Double.doubleToLongBits(stream.pressurePascal()));
            for (double value : stream.componentMolarFlows) canonical.append(':').append(Double.doubleToLongBits(value));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }

    private static String boundedIdentifier(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 96) throw new IllegalArgumentException(field + " is blank or too long");
        return value;
    }
}
