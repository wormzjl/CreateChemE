package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.network.ColumnNextNetwork;
import com.wormzjl.createcheme.network.ColumnNextNetwork.StateView;
import com.wormzjl.createcheme.science.column.nextgen.ColumnModelRegistry;
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

    private final List<EditBox> editors = new ArrayList<>();
    private long stateNonce;
    private long operationId;
    private long inputRevision;
    private String status = "Loading accepted state…";
    private ColumnNextInput acceptedInput = ColumnNextInput.defaults();
    private NextColumnResultView acceptedResult;
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
        super.init();
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
        basis = addRenderableWidget(Button.builder(Component.literal("mol%"), button -> {
                    massBasis = !massBasis;
                    button.setMessage(Component.literal(massBasis ? "mass%" : "mol%"));
                }).bounds(leftPos + 154, topPos + 6, 62, 20).build());
        buildEditors();
        syncEditors();
        refreshControls();
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
        status = state.status() + (state.diagnostics().isEmpty() ? "" : ": " + state.diagnostics().getFirst());
        refreshControls();
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
        if (editors.size() != 9) return;
        editors.get(0).setValue(number(acceptedInput.crudeFeed().molarFlowMolPerSecond()));
        editors.get(1).setValue(number(acceptedInput.crudeFeed().temperatureKelvin()));
        editors.get(2).setValue(Integer.toString(acceptedInput.stageCount()));
        editors.get(3).setValue(Integer.toString(acceptedInput.crudeFeedStageNumber()));
        editors.get(4).setValue(number(acceptedInput.topPressurePascal() / 1_000.0));
        editors.get(5).setValue(number(acceptedInput.stagePressureDropPascal() / 1_000.0));
        editors.get(6).setValue(number(acceptedInput.condenserOutletTemperatureKelvin()));
        editors.get(7).setValue(number(acceptedInput.organicRefluxRatio()));
        editors.get(8).setValue(number(acceptedInput.reboilerDutyWatts() / 1_000_000.0));
    }

    private void sendEditedInput() {
        try {
            ColumnNextInput input = new ColumnNextInput(acceptedInput.schemaVersion(), acceptedInput.packageId(),
                    acceptedInput.assayId(), new ColumnNextInput.CrudeFeedInput(number(0), number(1)), integer(2), integer(3),
                    number(4) * 1_000.0, number(5) * 1_000.0, number(6), number(8) * 1_000_000.0, number(7),
                    acceptedInput.sideDraws(), acceptedInput.utilityFeeds());
            ColumnNextNetwork.sendCalculate(menu.blockPos(), stateNonce, input);
        } catch (NumberFormatException invalid) {
            status = "REJECTED_INPUT: enter finite numeric values";
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
        graphics.drawString(font, status, leftPos + 164, topPos + imageHeight - 20, 0xFFFFCC66, false);
    }

    private void renderSetup(GuiGraphics graphics) {
        graphics.drawString(font, "Setup", leftPos + 10, topPos + 39, 0xFFE6EDF3, false);
        String[] labels = {"Feed mol/s", "Feed K", "Stages", "Feed stage", "Top kPa", "Drop kPa/stage",
                "Condenser K", "Reflux", "Reboiler MW"};
        for (int index = 0; index < labels.length; index++) {
            int column = index / 5;
            int row = index % 5;
            graphics.drawString(font, labels[index], leftPos + 10 + column * 198, topPos + 47 + row * 30,
                    0xFF9AA6B2, false);
        }
        graphics.drawString(font, "Package: " + acceptedInput.packageId(), leftPos + 10, topPos + 215, 0xFF9AA6B2, false);
        graphics.drawString(font, "Assay: " + acceptedInput.assayId(), leftPos + 10, topPos + 227, 0xFF9AA6B2, false);
        graphics.drawString(font, "Side draws: " + acceptedInput.sideDraws().size() + "  Utilities: "
                + acceptedInput.utilityFeeds().size(), leftPos + 10, topPos + 239, 0xFF9AA6B2, false);
        graphics.drawString(font, "Total drop: " + number(acceptedInput.totalPressureDropPascal() / 1_000.0)
                + " kPa  Bottom: " + number(acceptedInput.bottomPressurePascal() / 100_000.0) + " bar",
                leftPos + 10, topPos + 251, 0xFF9AA6B2, false);
    }

    private void renderStreams(GuiGraphics graphics) {
        if (acceptedResult == null) {
            graphics.drawString(font, "No accepted stream result yet.", leftPos + 10, topPos + 50, 0xFF9AA6B2, false);
            return;
        }
        int first = streamPage * STREAM_COLUMNS_PER_PAGE;
        int last = Math.min(first + STREAM_COLUMNS_PER_PAGE, acceptedResult.streams().size());
        graphics.drawString(font, "Streams " + (first + 1) + "-" + last + " of " + acceptedResult.streams().size(),
                leftPos + 10, topPos + 41, 0xFFE6EDF3, false);
        int columnWidth = 112;
        for (int index = first; index < last; index++) {
            NextColumnResultView.Stream stream = acceptedResult.streams().get(index);
            int x = leftPos + 108 + (index - first) * columnWidth;
            graphics.fill(x - 2, topPos + 50, x + columnWidth - 4, topPos + 250, 0xFF29313A);
            graphics.drawString(font, abbreviate(stream.label(), 16), x, topPos + 54, 0xFFE6EDF3, false);
            graphics.drawString(font, stream.role().name() + " " + stream.phase(), x, topPos + 65, 0xFF9AA6B2, false);
        }
        List<Row> rows = streamRows();
        int visible = Math.min(15, rows.size() - streamScroll);
        for (int index = 0; index < visible; index++) {
            Row row = rows.get(streamScroll + index);
            int y = topPos + 79 + index * ROW_HEIGHT;
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
