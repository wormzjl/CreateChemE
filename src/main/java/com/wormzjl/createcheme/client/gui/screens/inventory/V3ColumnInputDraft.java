package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.createcheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.createcheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.createcheme.science.column.v3.V3SteamFeedSpec;
import java.util.List;

/**
 * Widget-free assembly of one candidate {@link V3ColumnInput} from the editor drafts.
 *
 * <p>The screen owns only the text; every unit conversion, diagnostic message and contract check lives here so
 * the exact input the Run button would send can be pinned by a unit test. The feed composition is never edited
 * in the GUI: the authored total rescales the server-owned component vector.</p>
 */
final class V3ColumnInputDraft {
    private V3ColumnInputDraft() {}

    static V3ColumnInput assemble(
            V3ColumnInput base,
            List<String> scalarFields,
            List<V3SideDrawDraft.Row> sideDraws,
            V3SteamFeedDraft.Row sumpSteam,
            V3SteamFeedDraft.Row traySteam,
            List<V3PumparoundDraft.Row> coolers) {
        V3ColumnScalarDraft.Values scalar = V3ColumnScalarDraft.parse(scalarFields);
        double[] scaledFlows = scaledFeed(base, scalar.feedMolPerSecond());
        List<V3SideDrawSpec> draws;
        try {
            draws = V3SideDrawDraft.parse(sideDraws, scalar.stageCount(), scalar.feedMolPerSecond());
        } catch (IllegalArgumentException invalidDraws) {
            throw new IllegalArgumentException("Side draws: " + invalidDraws.getMessage(), invalidDraws);
        }
        List<V3SteamFeedSpec> steam;
        try {
            steam = V3SteamFeedDraft.parse(sumpSteam, traySteam, scalar.stageCount(), scalar.feedMolPerSecond());
        } catch (IllegalArgumentException invalidSteam) {
            throw new IllegalArgumentException("Steam: " + invalidSteam.getMessage(), invalidSteam);
        }
        List<V3PumparoundSpec> pumparounds;
        try {
            pumparounds = V3PumparoundDraft.parse(coolers, scalar.stageCount());
        } catch (IllegalArgumentException invalidCoolers) {
            throw new IllegalArgumentException("Heat: " + invalidCoolers.getMessage(), invalidCoolers);
        }
        return new V3ColumnInput(
                V3ColumnInput.SCHEMA_VERSION, base.packageId(), base.assayId(), base.componentBasis(), scaledFlows,
                scalar.feedTemperatureKelvin(), scalar.stageCount(), scalar.feedStage(),
                scalar.topPressurePascal(), scalar.pressureDropPascal(), List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(scalar.condenserTemperatureKelvin()),
                        new V3ColumnSpecification.OrganicRefluxRatio(scalar.refluxRatio()),
                        new V3ColumnSpecification.ReboilerDuty(scalar.reboilerDutyWatts())),
                draws, steam, pumparounds);
    }

    private static double[] scaledFeed(V3ColumnInput base, double feedMolPerSecond) {
        double[] flows = base.feedComponentMolarFlowsMolPerSecond();
        double total = 0.0;
        for (double flow : flows) total += flow;
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("Server feed composition cannot be rescaled");
        }
        double scale = feedMolPerSecond / total;
        for (int index = 0; index < flows.length; index++) flows[index] *= scale;
        return flows;
    }
}
