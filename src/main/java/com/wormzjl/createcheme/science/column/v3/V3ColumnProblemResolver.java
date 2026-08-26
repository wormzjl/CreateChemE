package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Resolves topology, generated pressures, and the M0 degree-of-freedom proof before a solve. */
public final class V3ColumnProblemResolver {
    private V3ColumnProblemResolver() {}

    /**
     * Resolves one candidate condenser branch.
     *
     * <p>A future endpoint flash selects this branch; it is not a user control.  Keeping it
     * explicit in M0 allows both branch contracts to be tested before thermodynamics exists.</p>
     *
     * @throws IllegalArgumentException if the input/schema/branch cannot form a closed V3 problem
     */
    public static V3ColumnProblem resolve(V3ColumnInput input, V3CondenserPhaseBranch condenserPhaseBranch) {
        input = Objects.requireNonNull(input, "input");
        condenserPhaseBranch = Objects.requireNonNull(condenserPhaseBranch, "condenserPhaseBranch");
        validateInput(input);
        V3ColumnTopology topology = condenserPhaseBranch == V3CondenserPhaseBranch.TWO_PHASE
                ? V3ColumnTopology.twoPhase(input.stageCount(), input.feedStageNumber())
                : V3ColumnTopology.vaporOnly(input.stageCount(), input.feedStageNumber());
        V3ActiveComponentBasis activeComponentBasis = V3ActiveComponentBasis.from(input);
        V3DegreeOfFreedomLedger ledger = V3DegreeOfFreedomLedger.create(
                topology, activeComponentBasis.componentCount(), input.specifications());
        if (!ledger.isValid()) {
            throw new IllegalArgumentException("Invalid V3 degree-of-freedom contract: " + ledger.humanReadableDiagnostic());
        }
        return new V3ColumnProblem(input, topology, activeComponentBasis, pressureProfile(input, topology), ledger);
    }

    private static void validateInput(V3ColumnInput input) {
        if (input.schemaVersion() != V3ColumnInput.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported V3 input schema revision " + input.schemaVersion());
        }
        if (input.stageCount() < V3ColumnInput.MIN_STAGE_COUNT || input.stageCount() > V3ColumnInput.MAX_STAGE_COUNT) {
            throw new IllegalArgumentException("V3 tray count is outside the schema range");
        }
        if (input.feedStageNumber() < 1 || input.feedStageNumber() > input.stageCount()) {
            throw new IllegalArgumentException("V3 feed tray is outside the equilibrium-tray range");
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
