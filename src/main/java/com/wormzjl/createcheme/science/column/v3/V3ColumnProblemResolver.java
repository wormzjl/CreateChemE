package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.Objects;

/** Resolves topology, generated pressures, and the M0 degree-of-freedom proof before a solve. */
public final class V3ColumnProblemResolver {
    private V3ColumnProblemResolver() {}

    /**
     * Resolves one candidate condenser branch.
     *
     * <p>The calculation selects and validates this branch; it is not a user control.</p>
     *
     * @throws IllegalArgumentException if the input/schema/branch cannot form a closed V3 problem
     */
    public static V3ColumnProblem resolve(V3ColumnInput input, V3CondenserPhaseBranch condenserPhaseBranch) {
        return resolve(input, condenserPhaseBranch, null);
    }

    /**
     * Resolves one candidate branch on an explicitly supplied per-tray pressure profile.
     *
     * <p>{@code nodePressuresPascal} is null for the generated uniform profile — the only mode that existed
     * before flow-dependent tray hydraulics — and otherwise is the marched profile of an accepted state. The
     * profile is validated here and then flows into the problem, so every pressure-derived quantity downstream
     * (steam superheat, the operating-domain assessment, the feed flash, the condenser water regime) reads the
     * profile that is actually in use rather than the authored uniform drop.</p>
     *
     * @throws IllegalArgumentException if the input/schema/branch cannot form a closed V3 problem, or if the
     *         supplied profile does not describe this geometry
     */
    public static V3ColumnProblem resolve(
            V3ColumnInput input, V3CondenserPhaseBranch condenserPhaseBranch, double[] nodePressuresPascal) {
        input = Objects.requireNonNull(input, "input");
        condenserPhaseBranch = Objects.requireNonNull(condenserPhaseBranch, "condenserPhaseBranch");
        validateInput(input, nodePressuresPascal);
        V3ColumnTopology topology = switch (condenserPhaseBranch) {
            case TWO_PHASE -> V3ColumnTopology.twoPhase(input.stageCount(), input.feedStageNumber());
            case VAPOR_ONLY -> V3ColumnTopology.vaporOnly(input.stageCount(), input.feedStageNumber());
            case LIQUID_ONLY -> V3ColumnTopology.liquidOnly(input.stageCount(), input.feedStageNumber());
        };
        V3ActiveComponentBasis activeComponentBasis = V3ActiveComponentBasis.from(input);
        V3CondenserComponentPhases condenserComponentPhases = V3CondenserComponentPhases.from(activeComponentBasis);
        V3DegreeOfFreedomLedger ledger = V3DegreeOfFreedomLedger.create(
                topology, activeComponentBasis.componentCount(), input.specifications(), condenserComponentPhases,
                V3TruncationSupport.identity(topology, activeComponentBasis.componentCount()), input.sideDraws());
        if (!ledger.isValid()) {
            throw new IllegalArgumentException("Invalid V3 degree-of-freedom contract: " + ledger.humanReadableDiagnostic());
        }
        return new V3ColumnProblem(input, topology, activeComponentBasis, condenserComponentPhases,
                nodePressuresPascal == null ? pressureProfile(input, topology)
                        : requireProfile(nodePressuresPascal, input, topology),
                ledger, ledger.truncationSupport(), ledger.wetTraySet());
    }

    /**
     * Attaches fixed support without changing authored inputs. Identity returns the original problem
     * and ledger. An invalid reduced ledger is rejected for the attempt orchestrator to retry unmasked.
     */
    static V3ColumnProblem withTruncation(V3ColumnProblem problem, V3TruncationSupport support) {
        return withTruncation(problem, support, V3WetTraySet.dry(
                Objects.requireNonNull(problem, "problem").topology()));
    }

    /**
     * Attaches this attempt's frozen truncation support and free-water tray set together.
     *
     * <p>Both are attempt-local decisions taken from a seed and refreshed from a solved state, and both change
     * the ledger, so they are attached in one step: a problem never carries a support that its wet set has not
     * seen or the other way round.</p>
     */
    static V3ColumnProblem withTruncation(
            V3ColumnProblem problem, V3TruncationSupport support, V3WetTraySet wetTraySet) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(wetTraySet, "wetTraySet");
        support.requireCompatible(problem);
        if (!problem.truncationSupport().isIdentity() || problem.hasWetTrays()) {
            throw new IllegalArgumentException("V3 truncation requires the original untruncated problem");
        }
        if (support.isIdentity() && !wetTraySet.hasWetTrays()) return problem;
        V3DegreeOfFreedomLedger ledger = V3DegreeOfFreedomLedger.create(problem.topology(),
                problem.activeComponentBasis().componentCount(), problem.input().specifications(),
                problem.condenserComponentPhases(), support, problem.input().sideDraws(), wetTraySet);
        if (!ledger.isValid()) {
            throw new IllegalArgumentException("Invalid V3 reduced degree-of-freedom contract: "
                    + ledger.humanReadableDiagnostic());
        }
        return new V3ColumnProblem(problem.input(), problem.topology(), problem.activeComponentBasis(),
                problem.condenserComponentPhases(), problem.nodePressuresPascal(), ledger, support, wetTraySet);
    }

    /** Validates the authored geometry and rates without assembling a numerical ledger or calling properties. */
    public static void validateInput(V3ColumnInput input) {
        validateInput(input, null);
    }

    /**
     * The same validation against the pressure profile a solve will actually use.
     *
     * <p>Only the steam superheat bound and the bottom-pressure finiteness read pressures, and both take them
     * from the supplied profile when there is one: steam that is superheated against the uniform nominal can be
     * saturated against a marched profile that is 20 kPa higher at the sump.</p>
     */
    static void validateInput(V3ColumnInput input, double[] nodePressuresPascal) {
        Objects.requireNonNull(input, "input");
        if (input.schemaVersion() != V3ColumnInput.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported V3 input schema revision " + input.schemaVersion());
        }
        if (input.stageCount() < V3ColumnInput.MIN_STAGE_COUNT || input.stageCount() > V3ColumnInput.MAX_STAGE_COUNT) {
            throw new IllegalArgumentException("V3 tray count is outside the schema range");
        }
        if (input.feedStageNumber() < 1 || input.feedStageNumber() > input.stageCount() + 1) {
            throw new IllegalArgumentException("V3 feed must enter below tray 1 and no lower than the last tray");
        }
        if (nodePressuresPascal != null) requireProfileShape(nodePressuresPascal, input);
        double totalDraw = 0.0;
        for (V3SideDrawSpec draw : input.sideDraws()) {
            if (draw.trayNumber() > input.stageCount()) {
                throw new IllegalArgumentException("V3 side draw tray is outside the equilibrium-tray range");
            }
            totalDraw += draw.molarFlowMolPerSecond();
        }
        double totalFeed = java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
        if (totalDraw >= totalFeed) {
            throw new IllegalArgumentException("V3 total side draw rate must be less than the feed rate");
        }
        for (V3PumparoundSpec pumparound : input.pumparounds()) {
            if (pumparound.drawTray() > input.stageCount()) {
                throw new IllegalArgumentException("V3 pumparound tray is outside the equilibrium-tray range");
            }
        }
        double bottomPressure = nodePressuresPascal == null
                ? input.topPressurePascal() + Math.max(0, input.stageCount() - 1) * input.stagePressureDropPascal()
                : nodePressuresPascal[input.stageCount()];
        if (!Double.isFinite(bottomPressure) || bottomPressure <= 0.0) {
            throw new IllegalArgumentException("V3 generated pressure profile is not finite and positive");
        }
        double totalSteam = 0.0;
        for (V3SteamFeedSpec feed : input.steamFeeds()) {
            if (feed.stageNumber() > input.stageCount() + 1) {
                throw new IllegalArgumentException("V3 steam stage is outside the column");
            }
            if (feed.temperatureKelvin() < V3WaterProperties.triplePoint()
                    || feed.temperatureKelvin() > V3WaterProperties.maximumTemperature()) {
                throw new IllegalArgumentException("V3 steam temperature is outside the water-property envelope");
            }
            int injectionTray = Math.min(feed.stageNumber(), input.stageCount());
            double injectionPressure = nodePressuresPascal == null
                    ? input.topPressurePascal() + Math.max(0, injectionTray - 1) * input.stagePressureDropPascal()
                    : nodePressuresPascal[injectionTray];
            double saturationTemperature = V3WaterProperties.saturationTemperatureKelvin(injectionPressure);
            if (feed.temperatureKelvin() < saturationTemperature + 5.0) {
                throw new IllegalArgumentException("V3 steam must be superheated vapor at the injection pressure");
            }
            totalSteam += feed.molarFlowMolPerSecond();
        }
        if (totalSteam > totalFeed) {
            throw new IllegalArgumentException("V3 total steam rate must not exceed the feed rate");
        }
        double reboilerDuty = input.specifications().stream().filter(V3ColumnSpecification.ReboilerDuty.class::isInstance)
                .map(V3ColumnSpecification.ReboilerDuty.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 input is missing a reboiler-duty specification")).watts();
        if (!input.steamFeeds().isEmpty() && reboilerDuty == 0.0 && !V3SteamFeeds.hasSumpFeed(input)) {
            throw new IllegalArgumentException("V3 zero reboiler duty requires steam injection at the last tray or positive duty");
        }
        if (!input.steamFeeds().isEmpty()) {
            double condenserTemperature = input.specifications().stream()
                    .filter(V3ColumnSpecification.CondenserOutletTemperature.class::isInstance)
                    .map(V3ColumnSpecification.CondenserOutletTemperature.class::cast).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "V3 steam input is missing a condenser-temperature specification")).kelvin();
            if (condenserTemperature < V3WaterProperties.triplePoint()) {
                throw new IllegalArgumentException("V3 free-water condenser temperature is below the ice-free property envelope");
            }
        }
    }

    /**
     * A supplied profile must describe this column: the same node count, the authored top pressure on the
     * condenser and the top tray, a nondecreasing tray section, and the sump on the bottom tray's pressure.
     *
     * <p>Those are exactly the invariants the generated uniform profile satisfies, so a solve cannot tell the
     * two apart, and the N-1 tray-interval convention is preserved whichever produced the profile.</p>
     */
    private static void requireProfileShape(double[] nodePressuresPascal, V3ColumnInput input) {
        int trayCount = input.stageCount();
        if (nodePressuresPascal.length != trayCount + 2) {
            throw new IllegalArgumentException("V3 supplied pressure profile does not match the tray count");
        }
        for (double pressure : nodePressuresPascal) {
            if (!Double.isFinite(pressure) || pressure <= 0.0) {
                throw new IllegalArgumentException("V3 supplied pressure profile must be finite and positive");
            }
        }
        if (nodePressuresPascal[0] != input.topPressurePascal() || nodePressuresPascal[1] != input.topPressurePascal()) {
            throw new IllegalArgumentException("V3 supplied pressure profile must start at the authored top pressure");
        }
        for (int tray = 2; tray <= trayCount; tray++) {
            if (nodePressuresPascal[tray] < nodePressuresPascal[tray - 1]) {
                throw new IllegalArgumentException("V3 supplied pressure profile must not rise up the column");
            }
        }
        if (nodePressuresPascal[trayCount + 1] != nodePressuresPascal[trayCount]) {
            throw new IllegalArgumentException("V3 supplied pressure profile must put the sump on the bottom tray");
        }
    }

    private static double[] requireProfile(
            double[] nodePressuresPascal, V3ColumnInput input, V3ColumnTopology topology) {
        if (nodePressuresPascal.length != topology.nodeCount()
                || topology.condenserNode() != 0 || topology.reboilerNode() != input.stageCount() + 1) {
            throw new IllegalArgumentException("V3 supplied pressure profile does not match the resolved topology");
        }
        return nodePressuresPascal.clone();
    }

    private static double[] pressureProfile(V3ColumnInput input, V3ColumnTopology topology) {
        double[] pressures = new double[topology.nodeCount()];
        pressures[topology.condenserNode()] = input.topPressurePascal();
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            pressures[tray] = input.topPressurePascal() + (tray - 1) * input.stagePressureDropPascal();
        }
        pressures[topology.reboilerNode()] = pressures[topology.trayCount()];
        return pressures;
    }
}
