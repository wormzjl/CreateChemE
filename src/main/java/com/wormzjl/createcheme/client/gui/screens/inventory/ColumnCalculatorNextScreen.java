package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.network.ColumnNextNetwork;
import com.wormzjl.createcheme.network.ColumnNextNetwork.StateView;
import com.wormzjl.createcheme.science.column.nextgen.ColumnModelRegistry;
import com.wormzjl.createcheme.science.column.nextgen.ColumnNextAuthoring;
import com.wormzjl.createcheme.science.column.nextgen.ColumnNextInput;
import com.wormzjl.createcheme.science.column.nextgen.NextColumnResultView;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorNextMenu;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Server-authoritative Setup and Streams view for the isolated experimental calculator. */
public final class ColumnCalculatorNextScreen extends AbstractContainerScreen<ColumnCalculatorNextMenu> {
    private static final int STREAM_COLUMNS_PER_PAGE = 3;
    private static final int ROW_HEIGHT = 11;
    private static final int CORE_EDITOR_COUNT = 9;
    private static final int EDITOR_COUNT = 11;
    private static final int SIDE_DRAWS_EDITOR = 9;
    private static final int UTILITIES_EDITOR = 10;

    private final List<EditBox> editors = new ArrayList<>();
    private long stateNonce;
    private long operationId;
    private long inputRevision;
    private String status = "Loading accepted state…";
    private ColumnNextInput acceptedInput = ColumnNextInput.defaults();
    private NextColumnResultView acceptedResult;
    private boolean acceptedResultStale;
    private Page page = Page.SETUP;
    private int streamPage;
    private int streamScroll;
    private boolean massBasis;
    private Button calculate;
    private Button cancel;
    private Button setup;
    private Button streams;
    private Button previousStreams;
    private Button nextStreams;
    private Button basis;

    public ColumnCalculatorNextScreen(ColumnCalculatorNextMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 410;
        imageHeight = 280;
    }

    @Override
    protected void init() {
        String[] draft = editorDraft();
        super.init();
        editors.clear();
        stateNonce = ColumnNextNetwork.sendStateRequest(menu.blockPos());
        setup = addRenderableWidget(Button.builder(Component.literal("Setup"), button -> selectPage(Page.SETUP))
                .bounds(leftPos + 8, topPos + 6, 64, 20).build());
        streams = addRenderableWidget(Button.builder(Component.literal("Streams"), button -> selectPage(Page.STREAMS))
                .bounds(leftPos + 76, topPos + 6, 70, 20).build());
        calculate = addRenderableWidget(Button.builder(Component.literal("Calculate"), button -> sendEditedInput())
                .bounds(leftPos + 8, topPos + imageHeight - 26, 78, 20).build());
        cancel = addRenderableWidget(Button.builder(Component.literal("Cancel"), button ->
                        ColumnNextNetwork.sendCancel(menu.blockPos(), stateNonce, operationId, inputRevision))
                .bounds(leftPos + 90, topPos + imageHeight - 26, 66, 20).build());
        previousStreams = addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                    if (streamPage > 0) streamPage--;
                }).bounds(leftPos + imageWidth - 82, topPos + 6, 28, 20).build());
        nextStreams = addRenderableWidget(Button.builder(Component.literal(">"), button -> streamPage++)
                .bounds(leftPos + imageWidth - 50, topPos + 6, 28, 20).build());
        basis = addRenderableWidget(Button.builder(Component.literal(massBasis ? "mass%" : "mol%"), button -> {
                    massBasis = !massBasis;
                    button.setMessage(Component.literal(massBasis ? "mass%" : "mol%"));
                }).bounds(leftPos + 154, topPos + 6, 62, 20).build());
        buildEditors();
        if (draft == null) syncEditors();
        else restoreEditorDraft(draft);
        refreshControls();
    }

    private String[] editorDraft() {
        if (editors.size() != EDITOR_COUNT) return null;
        String[] draft = new String[editors.size()];
        for (int index = 0; index < draft.length; index++) draft[index] = editors.get(index).getValue();
        return draft;
    }

    private void restoreEditorDraft(String[] draft) {
        if (draft.length != editors.size()) {
            syncEditors();
            return;
        }
        for (int index = 0; index < draft.length; index++) editors.get(index).setValue(draft[index]);
    }

    private void buildEditors() {
        String[] labels = {"Feed mol/s", "Feed K", "Stages", "Feed stage", "Top kPa", "Drop kPa/stage",
                "Condenser K", "Reflux", "Reboiler MW"};
        for (int index = 0; index < labels.length; index++) {
            int column = index / 5;
            int row = index % 5;
            EditBox editor = new EditBox(font, leftPos + 10 + column * 198, topPos + 59 + row * 30, 114, 18,
                    Component.literal(labels[index]));
            editor.setMaxLength(20);
            editors.add(addRenderableWidget(editor));
        }
        EditBox sideDraws = new EditBox(font, leftPos + 10, topPos + 216, 188, 18,
                Component.literal("stage,m|kg,rate;…"));
        sideDraws.setMaxLength(180);
        editors.add(addRenderableWidget(sideDraws));
        EditBox utilities = new EditBox(font, leftPos + 207, topPos + 216, 193, 18,
                Component.literal("water|steam,stage,mol/s,K,kPa;…"));
        utilities.setMaxLength(220);
        editors.add(addRenderableWidget(utilities));
    }

    private void selectPage(Page target) {
        page = target;
        refreshControls();
    }

    public void acceptState(StateView state) {
        if (state.clientNonce() != 0L && state.clientNonce() != stateNonce) return;
        operationId = state.operationId();
        inputRevision = state.inputRevision();
        if (state.acceptedInputPresent()) {
            acceptedInput = state.acceptedInput();
            syncEditors();
        }
        if (state.acceptedResultPresent()) acceptedResult = state.acceptedResult();
        acceptedResultStale = state.acceptedResultPresent() && state.resultRevision() != state.inputRevision();
        status = state.status() + (state.diagnostics().isEmpty() ? "" : ": " + statusDetail(state.diagnostics()));
        refreshControls();
    }

    private static String statusDetail(List<String> diagnostics) {
        String detail = diagnostics.getLast();
        int cause = detail.indexOf("; cause=");
        return cause >= 0 ? detail.substring(cause + "; cause=".length()) + " (continuation)" : detail;
    }

    private void refreshControls() {
        boolean active = "calculating".equals(statusToken()) || "cancelling".equals(statusToken());
        calculate.active = !active;
        cancel.active = active && operationId != 0L;
        boolean showSetup = page == Page.SETUP;
        for (EditBox editor : editors) {
            editor.visible = showSetup;
            editor.active = showSetup && !active;
        }
        previousStreams.visible = page == Page.STREAMS;
        nextStreams.visible = page == Page.STREAMS;
        basis.visible = page == Page.STREAMS;
        int lastPage = acceptedResult == null ? 0
                : Math.max(0, (acceptedResult.streams().size() - 1) / STREAM_COLUMNS_PER_PAGE);
        streamPage = Math.max(0, Math.min(streamPage, lastPage));
        previousStreams.active = page == Page.STREAMS && streamPage > 0;
        nextStreams.active = page == Page.STREAMS && streamPage < lastPage;
        setup.active = page != Page.SETUP;
        streams.active = page != Page.STREAMS;
    }

    private String statusToken() {
        int separator = status.indexOf(':');
        return separator < 0 ? status : status.substring(0, separator);
    }

    private void syncEditors() {
        if (editors.size() != EDITOR_COUNT) return;
        editors.get(0).setValue(number(acceptedInput.crudeFeed().molarFlowMolPerSecond()));
        editors.get(1).setValue(number(acceptedInput.crudeFeed().temperatureKelvin()));
        editors.get(2).setValue(Integer.toString(acceptedInput.stageCount()));
        editors.get(3).setValue(Integer.toString(acceptedInput.crudeFeedStageNumber()));
        editors.get(4).setValue(number(acceptedInput.topPressurePascal() / 1_000.0));
        editors.get(5).setValue(number(acceptedInput.stagePressureDropPascal() / 1_000.0));
        editors.get(6).setValue(number(acceptedInput.condenserOutletTemperatureKelvin()));
        editors.get(7).setValue(number(acceptedInput.organicRefluxRatio()));
        editors.get(8).setValue(number(acceptedInput.reboilerDutyWatts() / 1_000_000.0));
        editors.get(SIDE_DRAWS_EDITOR).setValue(formatSideDraws(acceptedInput.sideDraws()));
        editors.get(UTILITIES_EDITOR).setValue(formatUtilities(acceptedInput.utilityFeeds()));
    }

    private void sendEditedInput() {
        try {
            ColumnNextInput input = new ColumnNextInput(acceptedInput.schemaVersion(), acceptedInput.packageId(),
                    acceptedInput.assayId(), new ColumnNextInput.CrudeFeedInput(number(0), number(1)), integer(2), integer(3),
                    number(4) * 1_000.0, number(5) * 1_000.0, number(6), number(8) * 1_000_000.0, number(7),
                    ColumnNextAuthoring.parseSideDraws(editors.get(SIDE_DRAWS_EDITOR).getValue()),
                    ColumnNextAuthoring.parseUtilities(editors.get(UTILITIES_EDITOR).getValue()));
            ColumnNextNetwork.sendCalculate(menu.blockPos(), stateNonce, input);
        } catch (IllegalArgumentException invalid) {
            status = "REJECTED_INPUT: " + abbreviate(invalid.getMessage(), 48);
        }
    }

    private double number(int editor) {
        double value = Double.parseDouble(editors.get(editor).getValue());
        if (!Double.isFinite(value)) throw new NumberFormatException("non-finite");
        return value;
    }

    private int integer(int editor) {
        return Integer.parseInt(editors.get(editor).getValue());
    }

    private static String formatSideDraws(List<ColumnNextInput.SideDrawInput> draws) {
        StringBuilder result = new StringBuilder();
        for (ColumnNextInput.SideDrawInput draw : draws) {
            if (!result.isEmpty()) result.append(';');
            result.append(draw.stageNumber()).append(',')
                    .append(draw.basis() == ColumnNextInput.AuthoredBasis.MOLAR ? 'm' : "kg")
                    .append(',').append(number(draw.authoredRate()));
        }
        return result.toString();
    }

    private static String formatUtilities(List<ColumnNextInput.WaterSteamFeedInput> utilities) {
        StringBuilder result = new StringBuilder();
        for (ColumnNextInput.WaterSteamFeedInput utility : utilities) {
            if (!result.isEmpty()) result.append(';');
            result.append(utility.mode() == ColumnNextInput.UtilityFeedMode.WATER ? "water" : "steam")
                    .append(',').append(utility.stageNumber()).append(',')
                    .append(number(utility.molarFlowMolPerSecond())).append(',')
                    .append(number(utility.temperatureKelvin())).append(',')
                    .append(number(utility.upstreamPressurePascal() / 1_000.0));
        }
        return result.toString();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (page == Page.STREAMS && acceptedResult != null && verticalAmount != 0.0) {
            int maximum = Math.max(0, streamRows().size() - 15);
            streamScroll = Math.max(0, Math.min(maximum, streamScroll - (verticalAmount > 0.0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF20252B);
        graphics.fill(leftPos, topPos + 31, leftPos + imageWidth, topPos + 32, 0xFF59636E);
        graphics.drawString(font, "Column Calculator Next (Experimental)", leftPos + 224, topPos + 12, 0xFFE6EDF3, false);
        if (page == Page.SETUP) renderSetup(graphics);
        else renderStreams(graphics);
        graphics.drawString(font, abbreviate(status, 36), leftPos + 164, topPos + imageHeight - 20, 0xFFFFCC66, false);
    }

    private void renderSetup(GuiGraphics graphics) {
        graphics.drawString(font, "Setup", leftPos + 10, topPos + 39, 0xFFE6EDF3, false);
        String[] labels = {"Feed mol/s", "Feed K", "Stages", "Feed stage", "Top kPa", "Drop kPa/stage",
                "Condenser K", "Reflux", "Reboiler MW"};
        for (int index = 0; index < CORE_EDITOR_COUNT; index++) {
            int column = index / 5;
            int row = index % 5;
            graphics.drawString(font, labels[index], leftPos + 10 + column * 198, topPos + 47 + row * 30,
                    0xFF9AA6B2, false);
        }
        graphics.drawString(font, "Side draws (stage,m|kg,rate)", leftPos + 10, topPos + 204, 0xFF9AA6B2, false);
        graphics.drawString(font, "Utilities (mode,stage,mol/s,K,kPa)", leftPos + 207, topPos + 204, 0xFF9AA6B2, false);
        graphics.drawString(font, "Package: " + acceptedInput.packageId() + "  Assay: " + acceptedInput.assayId(),
                leftPos + 10, topPos + 239, 0xFF9AA6B2, false);
        graphics.drawString(font, "Total drop: " + number(acceptedInput.totalPressureDropPascal() / 1_000.0)
                + " kPa  Bottom: " + number(acceptedInput.bottomPressurePascal() / 100_000.0) + " bar",
                leftPos + 10, topPos + 251, 0xFF9AA6B2, false);
    }

    private void renderStreams(GuiGraphics graphics) {
        if (acceptedResult == null) {
            graphics.drawString(font, "No accepted stream result yet.", leftPos + 10, topPos + 50, 0xFF9AA6B2, false);
            return;
        }
        if (acceptedResultStale) {
            graphics.drawString(font, "Stale result: it belongs to an earlier accepted input revision.",
                    leftPos + 10, topPos + 41, 0xFFFFCC66, false);
        }
        int first = streamPage * STREAM_COLUMNS_PER_PAGE;
        int last = Math.min(first + STREAM_COLUMNS_PER_PAGE, acceptedResult.streams().size());
        graphics.drawString(font, "Streams " + (first + 1) + "-" + last + " of " + acceptedResult.streams().size(),
                leftPos + 10, acceptedResultStale ? topPos + 53 : topPos + 41, 0xFFE6EDF3, false);
        int columnWidth = 112;
        for (int index = first; index < last; index++) {
            NextColumnResultView.Stream stream = acceptedResult.streams().get(index);
            int x = leftPos + 108 + (index - first) * columnWidth;
            int yOffset = acceptedResultStale ? 12 : 0;
            graphics.fill(x - 2, topPos + 50 + yOffset, x + columnWidth - 4, topPos + 250, 0xFF29313A);
            graphics.drawString(font, abbreviate(stream.label(), 16), x, topPos + 54 + yOffset, 0xFFE6EDF3, false);
            graphics.drawString(font, stream.role().name() + " " + stream.phase(), x, topPos + 65 + yOffset, 0xFF9AA6B2, false);
        }
        List<Row> rows = streamRows();
        int visible = Math.min(15, rows.size() - streamScroll);
        for (int index = 0; index < visible; index++) {
            Row row = rows.get(streamScroll + index);
            int y = topPos + 79 + (acceptedResultStale ? 12 : 0) + index * ROW_HEIGHT;
            graphics.drawString(font, row.label(), leftPos + 10, y, row.component() >= 0 ? 0xFFD5E5F2 : 0xFF9AA6B2, false);
            for (int streamIndex = first; streamIndex < last; streamIndex++) {
                NextColumnResultView.Stream stream = acceptedResult.streams().get(streamIndex);
                int x = leftPos + 108 + (streamIndex - first) * columnWidth;
                graphics.drawString(font, abbreviate(value(row, stream), 15), x, y, 0xFFE6EDF3, false);
            }
        }
    }

    private List<Row> streamRows() {
        if (acceptedResult == null) return List.of();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Connected stage", -1));
        rows.add(new Row("Flow [kmol/h]", -2));
        rows.add(new Row("Temperature [C]", -3));
        rows.add(new Row("Pressure [bar]", -4));
        rows.add(new Row("Phase", -5));
        rows.add(new Row("--- Composition ---", -6));
        for (int component = 0; component < acceptedResult.componentAxis().size(); component++) {
            rows.add(new Row(acceptedResult.componentAxis().get(component) + (massBasis ? " [mass%]" : " [mol%]"), component));
        }
        return rows;
    }

    private String value(Row row, NextColumnResultView.Stream stream) {
        return switch (row.component()) {
            case -1 -> stream.connectedStage() == 0 ? "condenser" : Integer.toString(stream.connectedStage());
            case -2 -> number(stream.molarFlowMolPerSecond() * 3.6);
            case -3 -> number(stream.temperatureKelvin() - 273.15);
            case -4 -> number(stream.pressurePascal() / 100_000.0);
            case -5 -> stream.phase();
            case -6 -> "";
            default -> compositionValue(stream, row.component());
        };
    }

    private String compositionValue(NextColumnResultView.Stream stream, int component) {
        double[] flows = stream.componentMolarFlows();
        double numerator = flows[component];
        double denominator = stream.molarFlowMolPerSecond();
        if (massBasis) {
            if (acceptedResultStale) return "—";
            var basis = ColumnModelRegistry.require(acceptedInput.packageId()).basis();
            numerator *= basis.components().get(component).molecularWeightKgPerMol();
            denominator = stream.massFlowKilogramPerSecond(basis);
        }
        if (numerator == 0.0) return "0";
        if (!(denominator > 0.0)) return "—";
        double percent = 100.0 * numerator / denominator;
        return Math.abs(percent) < 0.001 ? String.format(java.util.Locale.ROOT, "%.2e", percent) : number(percent);
    }

    private static String number(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(1, maximum - 1)) + "…";
    }

    private enum Page { SETUP, STREAMS }
    private record Row(String label, int component) {}
}
