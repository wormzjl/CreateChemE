package com.wormzjl.createcheme.science.column.v3;

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
        input = Objects.requireNonNull(input, "input");
        condenserPhaseBranch = Objects.requireNonNull(condenserPhaseBranch, "condenserPhaseBranch");
        validateInput(input);
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
                pressureProfile(input, topology), ledger, ledger.truncationSupport());
    }

    /**
     * Attaches fixed support without changing authored inputs. Identity returns the original problem
     * and ledger. An invalid reduced ledger is rejected for the attempt orchestrator to retry unmasked.
     */
    static V3ColumnProblem withTruncation(V3ColumnProblem problem, V3TruncationSupport support) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(support, "support");
        support.requireCompatible(problem);
        if (!problem.truncationSupport().isIdentity()) {
            throw new IllegalArgumentException("V3 truncation requires the original untruncated problem");
        }
        if (support.isIdentity()) return problem;
        V3DegreeOfFreedomLedger ledger = V3DegreeOfFreedomLedger.create(problem.topology(),
                problem.activeComponentBasis().componentCount(), problem.input().specifications(),
                problem.condenserComponentPhases(), support, problem.input().sideDraws());
        if (!ledger.isValid()) {
            throw new IllegalArgumentException("Invalid V3 reduced degree-of-freedom contract: "
                    + ledger.humanReadableDiagnostic());
        }
        return new V3ColumnProblem(problem.input(), problem.topology(), problem.activeComponentBasis(),
                problem.condenserComponentPhases(), problem.nodePressuresPascal(), ledger, support);
    }

    /** Validates the authored geometry and rates without assembling a numerical ledger or calling properties. */
    public static void validateInput(V3ColumnInput input) {
        Objects.requireNonNull(input, "input");
        if (input.schemaVersion() != V3ColumnInput.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported V3 input schema revision " + input.schemaVersion());
        }
        if (input.stageCount() < V3ColumnInput.MIN_STAGE_COUNT || input.stageCount() > V3ColumnInput.MAX_STAGE_COUNT) {
            throw new IllegalArgumentException("V3 tray count is outside the schema range");
        }
        if (input.feedStageNumber() < 1 || input.feedStageNumber() > input.stageCount()) {
            throw new IllegalArgumentException("V3 feed tray is outside the equilibrium-tray range");
        }
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
        double bottomPressure = input.topPressurePascal()
                + (input.stageCount() - 1) * input.stagePressureDropPascal();
        if (!Double.isFinite(bottomPressure) || bottomPressure <= 0.0) {
            throw new IllegalArgumentException("V3 generated pressure profile is not finite and positive");
        }
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
