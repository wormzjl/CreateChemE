package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded immutable energy summary of one accepted V3 state.
 *
 * <p>Every value uses the column sign convention: positive adds heat to the column. The condenser duty is
 * therefore negative for a normal overhead condenser, and a pumparound duty is negative. The condenser duty is
 * recomputed here from the accepted condenser node rather than read from a solver unknown; it is a calculated
 * quantity, not a specification.</p>
 */
public record V3ColumnDutyLedger(
        double condenserWatts,
        double reboilerWatts,
        double stageHeatTotalWatts,
        List<StageDuty> stageDuties,
        double feedEnthalpyWatts,
        double steamEnthalpyWatts) {
    public static final int MAX_STAGE_DUTIES = 16;

    public V3ColumnDutyLedger {
        stageDuties = List.copyOf(Objects.requireNonNull(stageDuties, "stageDuties"));
        if (stageDuties.size() > MAX_STAGE_DUTIES) {
            throw new IllegalArgumentException("V3 duty ledger exceeds the bounded stage-duty contract");
        }
        if (!Double.isFinite(condenserWatts) || !Double.isFinite(reboilerWatts)
                || !Double.isFinite(stageHeatTotalWatts) || !Double.isFinite(feedEnthalpyWatts)
                || !Double.isFinite(steamEnthalpyWatts)) {
            throw new IllegalArgumentException("V3 duty ledger entries must be finite");
        }
    }

    /** One tray's prescribed stage heat, positive into the column. */
    public record StageDuty(int trayNumber, double dutyWatts) {
        public StageDuty {
            if (trayNumber < 1 || !Double.isFinite(dutyWatts)) {
                throw new IllegalArgumentException("V3 stage duty requires a tray number and a finite duty");
            }
        }
    }

    /** Recomputes every boundary duty of an accepted state with a fresh property session. */
    static V3ColumnDutyLedger fromAccepted(
            V3ColumnProblem problem, V3DryMeshState state, V3ThermoModel thermo,
            double feedMolarEnthalpyJoulesPerMol) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(state, "state");
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, feedMolarEnthalpyJoulesPerMol);
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        List<StageDuty> duties = new ArrayList<>(MAX_STAGE_DUTIES);
        double stageHeatTotal = 0.0;
        for (int tray = 1; tray <= problem.topology().trayCount(); tray++) {
            double duty = problem.stageHeatWatts(tray);
            if (duty == 0.0) continue;
            stageHeatTotal += duty;
            if (duties.size() < MAX_STAGE_DUTIES) duties.add(new StageDuty(tray, duty));
        }
        return new V3ColumnDutyLedger(
                condenserDutyWatts(problem, state, evaluator, workspace),
                reboilerDutyWatts(problem),
                stageHeatTotal,
                duties,
                problem.activeComponentBasis().totalFeedFlowMolPerSecond() * feedMolarEnthalpyJoulesPerMol,
                steamEnthalpyWatts(problem));
    }

    /**
     * Condenser duty from the accepted condenser node: everything leaving minus the vapor arriving from tray one.
     *
     * <p>The condenser has no MESH energy equation, so this closes that node by definition and is negative
     * whenever heat is removed.</p>
     */
    static double condenserDutyWatts(
            V3ColumnProblem problem, V3DryMeshState state, V3MeshResidualEvaluator evaluator,
            V3ThermoWorkspace workspace) {
        int condenser = problem.topology().condenserNode();
        V3MeshResidualEvaluator.LocalNodeTerms outlet = evaluator.localTerms(state, condenser, workspace);
        V3MeshResidualEvaluator.LocalNodeTerms trayOne = evaluator.localTerms(state, 1, workspace);
        double leaving = outlet.liquidPhaseEnergy() + outlet.vaporPhaseEnergy() + freeWaterEnergyWatts(problem, state);
        return leaving - trayOne.vaporPhaseEnergy();
    }

    /** Decanted free water leaves the drum as saturated liquid at the specified outlet temperature. */
    static double freeWaterEnergyWatts(V3ColumnProblem problem, V3DryMeshState state) {
        if (!problem.hasSteamFeeds()) return 0.0;
        double freeWater = problem.waterCondenserSplit(state).freeWaterFlowMolPerSecond();
        if (freeWater == 0.0) return 0.0;
        return freeWater * V3WaterProperties.liquidMolarEnthalpy(
                state.temperatureKelvin(problem.topology().condenserNode()));
    }

    static double reboilerDutyWatts(V3ColumnProblem problem) {
        return problem.input().specifications().stream().filter(V3ColumnSpecification.ReboilerDuty.class::isInstance)
                .map(V3ColumnSpecification.ReboilerDuty.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 duty ledger requires a reboiler-duty specification"))
                .watts();
    }

    static double steamEnthalpyWatts(V3ColumnProblem problem) {
        double total = 0.0;
        for (int node = 0; node < problem.topology().nodeCount(); node++) total += problem.steamFeedEnthalpyWatts(node);
        return total;
    }
}
