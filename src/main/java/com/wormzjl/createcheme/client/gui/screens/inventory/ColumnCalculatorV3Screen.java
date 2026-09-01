package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.network.ColumnV3Network;
import com.wormzjl.createcheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.createcheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.createcheme.science.column.v3.V3ColumnStreamProperties;
import com.wormzjl.createcheme.science.column.v3.V3ControlledQuantity;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3State;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Revisioned V3 editor and accepted-stream viewer.
 *
 * <p>The editor uses gameplay-friendly units while preserving the immutable, server-authoritative V3 scientific
 * input. Every displayed stream row originates from an accepted MESH state, never from an in-progress candidate.</p>
 */
public final class ColumnCalculatorV3Screen extends AbstractContainerScreen<ColumnCalculatorV3Menu> {
    private static final int CORE_EDITOR_COUNT = 9;
    private static final int SIDE_DRAW_COUNT = 3;
    private static final int MAX_PANEL_WIDTH = 620;
    private static final int MAX_PANEL_HEIGHT = 360;
    private static final int PANEL_MARGIN = 10;
    private static final int CONTENT_TOP = 58;
    private static final double KMOL_PER_HOUR_TO_MOL_PER_SECOND = 1_000.0 / 3_600.0;
    private static final double MOL_PER_SECOND_TO_KMOL_PER_HOUR = 3.6;
    private static final double CELSIUS_TO_KELVIN = 273.15;
    private static final double PASCAL_TO_BAR = 1.0e-5;
    private static final int BACKGROUND = 0xFF20252B;
    private static final int BORDER = 0xFF59636E;
    private static final int TABLE_HEADER = 0xFF343C45;
    private static final int TABLE_ROW = 0xFF252C33;
    private static final int TABLE_ALT_ROW = 0xFF2A323A;
    private static final int TABLE_GRID = 0xFF46515C;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int MUTED = 0xFF9AA6B2;
    private static final int NOTICE = 0xFFFFCC66;
    private static final int SUCCESS = 0xFF77DD88;
    private static final int FAILURE = 0xFFFF7777;

    private final List<EditBox> coreEditors = new ArrayList<>();
    private final List<SideDrawFields> sideDrawFields = new ArrayList<>();
    private Page page = Page.INPUTS;
    private V3State serverState;
    private long latestStateRevision = -1L;
    private boolean calculationRequested;
    private String validation = "Waiting for server-owned V3 state...";
    private String draftValidationDetail = "Enter all scalar inputs.";
    private Button inputsTab;
    private Button streamsTab;
    private Button convergenceTab;
    private Button run;
    private Button previousStreamPage;
    private Button nextStreamPage;
    private int streamPage;

    public ColumnCalculatorV3Screen(ColumnCalculatorV3Menu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = MAX_PANEL_WIDTH;
        imageHeight = MAX_PANEL_HEIGHT;
        inventoryLabelY = imageHeight + 10;
    }

    @Override
    protected void init() {
        String[] scalarDraft = editorDraft();
        String[] sideStageDrafts = {"13", "17", "22"};
        String[] sideRateDrafts = {"92.2974747474748", "131.853535353535", "32.9633838383838"};
        for (int index = 0; index < Math.min(sideDrawFields.size(), SIDE_DRAW_COUNT); index++) {
            sideStageDrafts[index] = sideDrawFields.get(index).stage().getValue();
            sideRateDrafts[index] = sideDrawFields.get(index).rate().getValue();
        }

        imageWidth = Math.min(MAX_PANEL_WIDTH, Math.max(1, width - PANEL_MARGIN * 2));
        imageHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(1, height - PANEL_MARGIN * 2));
        inventoryLabelY = imageHeight + 10;
        super.init();
        titleLabelY = -1_000;
        coreEditors.clear();
        sideDrawFields.clear();

        int tabY = topPos + 28;
        inputsTab = addRenderableWidget(Button.builder(Component.literal("Inputs"), button -> selectPage(Page.INPUTS))
                .bounds(leftPos + 10, tabY, 72, 20).build());
        streamsTab = addRenderableWidget(Button.builder(Component.literal("Streams"), button -> selectPage(Page.STREAMS))
                .bounds(leftPos + 86, tabY, 72, 20).build());
        convergenceTab = addRenderableWidget(Button.builder(Component.literal("Convergence"), button -> selectPage(Page.CONVERGENCE))
                .bounds(leftPos + 162, tabY, 96, 20).build());
        run = addRenderableWidget(Button.builder(Component.literal("Run V3"), button -> requestCalculation())
                .bounds(leftPos + 10, topPos + imageHeight - 29, 82, 20).build());
        previousStreamPage = addRenderableWidget(Button.builder(Component.literal("Previous streams"), button -> {
            streamPage = Math.max(0, streamPage - 1);
            refreshControls();
        }).bounds(leftPos + 10, topPos + imageHeight - 29, 112, 20).build());
        nextStreamPage = addRenderableWidget(Button.builder(Component.literal("Next streams"), button -> {
            streamPage++;
            refreshControls();
        }).bounds(leftPos + 128, topPos + imageHeight - 29, 100, 20).build());

        buildEditors(scalarDraft, sideStageDrafts, sideRateDrafts);
        if (serverState != null) loadInput(serverState.input());
        ColumnV3Network.setClientStateConsumer(this::applyServerState);
        ColumnV3Network.setClientRejectionConsumer(this::applyRejection);
        ColumnV3Network.sendStateRequest(menu.blockPos());
        validateDraft();
        refreshControls();
    }

    @Override
    public void onClose() {
        ColumnV3Network.setClientStateConsumer((blockPos, state) -> {});
        ColumnV3Network.setClientRejectionConsumer((blockPos, nonce, reason) -> {});
        super.onClose();
    }

    private void buildEditors(String[] scalarDraft, String[] sideStageDrafts, String[] sideRateDrafts) {
        int scalarColumnWidth = Math.max(1, (imageWidth - 20) / 3);
        String[] defaults = {"2610.7", "365", "29", "24", "126.85", "8", "2", "1.5", "0.75"};
        for (int index = 0; index < CORE_EDITOR_COUNT; index++) {
            int column = index % 3;
            int row = index / 3;
            EditBox editor = new EditBox(font, leftPos + 10 + column * scalarColumnWidth,
                    topPos + CONTENT_TOP + 13 + row * 38,
                    Math.max(30, Math.min(104, scalarColumnWidth - 8)), 20, Component.literal("V3 input"));
            editor.setMaxLength(20);
            editor.setValue(scalarDraft == null ? defaults[index] : scalarDraft[index]);
            editor.setResponder(value -> validateDraft());
            coreEditors.add(addRenderableWidget(editor));
        }
        int sideY = topPos + CONTENT_TOP + 128;
        for (int index = 0; index < SIDE_DRAW_COUNT; index++) {
            int groupX = leftPos + 10 + index * scalarColumnWidth;
            int stageWidth = Math.max(28, Math.min(44, scalarColumnWidth / 3));
            int rateWidth = Math.max(42, Math.min(78, scalarColumnWidth - stageWidth - 14));
            EditBox stage = new EditBox(font, groupX, sideY, stageWidth, 20, Component.literal("Side draw tray"));
            EditBox rate = new EditBox(font, groupX + stageWidth + 6, sideY, rateWidth, 20,
                    Component.literal("Side draw rate (kmol/h)"));
            stage.setValue(sideStageDrafts[index]);
            rate.setValue(sideRateDrafts[index]);
            stage.setMaxLength(3);
            rate.setMaxLength(20);
            stage.setResponder(value -> validateDraft());
            rate.setResponder(value -> validateDraft());
            sideDrawFields.add(new SideDrawFields(addRenderableWidget(stage), addRenderableWidget(rate)));
        }
    }

    private void applyServerState(net.minecraft.core.BlockPos blockPos, V3State state) {
        if (!menu.blockPos().equals(blockPos) || state.stateRevision() < latestStateRevision) return;
        latestStateRevision = state.stateRevision();
        serverState = state;
        calculationRequested = false;
        loadInput(state.input());
        validation = state.diagnostics().isEmpty() ? "Server state received" : state.diagnostics().getFirst();
        validateDraft();
        refreshControls();
    }

    private void applyRejection(net.minecraft.core.BlockPos blockPos, long clientNonce, String reason) {
        if (!menu.blockPos().equals(blockPos)) return;
        calculationRequested = false;
        validation = "Server rejected request: " + reason;
        refreshControls();
    }

    private String[] editorDraft() {
        if (coreEditors.size() != CORE_EDITOR_COUNT) return null;
        String[] draft = new String[CORE_EDITOR_COUNT];
        for (int index = 0; index < draft.length; index++) draft[index] = coreEditors.get(index).getValue();
        return draft;
    }

    private void loadInput(V3ColumnInput input) {
        double total = 0.0;
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) total += flow;
        String[] values = {
                compactDraft(total * MOL_PER_SECOND_TO_KMOL_PER_HOUR),
                compactDraft(input.feedTemperatureKelvin() - CELSIUS_TO_KELVIN),
                Integer.toString(input.stageCount()),
                Integer.toString(input.feedStageNumber()),
                compactDraft(specificationValue(input, V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)
                        - CELSIUS_TO_KELVIN),
                compactDraft(specificationValue(input, V3ControlledQuantity.REBOILER_DUTY) / 1_000_000.0),
                compactDraft(specificationValue(input, V3ControlledQuantity.ORGANIC_REFLUX_RATIO)),
                compactDraft(input.topPressurePascal() * PASCAL_TO_BAR),
                compactDraft(input.stagePressureDropPascal() / 1_000.0)
        };
        if (coreEditors.size() == CORE_EDITOR_COUNT) {
            for (int index = 0; index < values.length; index++) coreEditors.get(index).setValue(values[index]);
        }
        for (int index = 0; index < sideDrawFields.size(); index++) {
            V3SideDrawSpec draw = index < input.sideDraws().size() ? input.sideDraws().get(index) : null;
            sideDrawFields.get(index).stage().setValue(draw == null ? "" : Integer.toString(draw.trayNumber()));
            sideDrawFields.get(index).rate().setValue(draw == null ? "" : compactDraft(draw.molarFlowMolPerSecond() * 3.6));
        }
    }

    private void selectPage(Page target) {
        page = target;
        refreshControls();
    }

    private void refreshControls() {
        boolean showInputs = page == Page.INPUTS;
        boolean calculating = calculationRequested
                || serverState != null && serverState.status() == V3Status.CALCULATING;
        for (EditBox editor : coreEditors) {
            editor.visible = showInputs;
            editor.active = showInputs && !calculating && serverState != null;
        }
        for (SideDrawFields side : sideDrawFields) {
            side.stage().visible = showInputs;
            side.rate().visible = showInputs;
            side.stage().active = showInputs && !calculating && serverState != null;
            side.rate().active = showInputs && !calculating && serverState != null;
        }
        inputsTab.active = !showInputs;
        streamsTab.active = page != Page.STREAMS;
        convergenceTab.active = page != Page.CONVERGENCE;
        run.visible = showInputs;
        run.active = showInputs && !calculating && draftInput() != null;
        int count = serverState == null || serverState.displayResult().isEmpty() ? 0
                : serverState.displayResult().orElseThrow().streams().size();
        int perPage = streamsPerPage();
        streamPage = Math.clamp(streamPage, 0, Math.max(0, (count - 1) / perPage));
        previousStreamPage.visible = nextStreamPage.visible = page == Page.STREAMS && count > perPage;
        previousStreamPage.active = streamPage > 0;
        nextStreamPage.active = (streamPage + 1) * perPage < count;
    }

    private void requestCalculation() {
        V3ColumnInput input = draftInput();
        if (serverState == null || input == null) return;
        ColumnV3Network.sendCalculate(menu.blockPos(), serverState.inputRevision(), input);
        calculationRequested = true;
        validation = "Submitting immutable V3 input to the server...";
        refreshControls();
    }

    private void validateDraft() {
        if (serverState == null) {
            validation = "Waiting for server-owned V3 state...";
        } else if (draftInput() != null) {
            validation = "Scientific draft is valid; Run V3 performs authoritative DWSIM-path solving.";
        } else {
            validation = draftValidationDetail;
        }
        if (run != null && previousStreamPage != null && nextStreamPage != null) refreshControls();
    }

    private V3ColumnInput draftInput() {
        if (serverState == null || coreEditors.size() != CORE_EDITOR_COUNT) return null;
        try {
            V3ColumnScalarDraft.Values scalar = V3ColumnScalarDraft.parse(
                    coreEditors.stream().map(EditBox::getValue).toList());
            V3ColumnInput base = serverState.input();
            double[] existingFlows = base.feedComponentMolarFlowsMolPerSecond();
            double existingTotal = 0.0;
            for (double flow : existingFlows) existingTotal += flow;
            if (!Double.isFinite(existingTotal) || existingTotal <= 0.0) return null;
            double[] scaledFlows = new double[existingFlows.length];
            double scale = scalar.feedMolPerSecond() / existingTotal;
            for (int index = 0; index < scaledFlows.length; index++) scaledFlows[index] = existingFlows[index] * scale;
            List<V3SideDrawSpec> draws;
            try {
                draws = V3SideDrawDraft.parse(sideDrawFields.stream()
                        .map(fields -> new V3SideDrawDraft.Row(fields.stage().getValue(), fields.rate().getValue())).toList(),
                        scalar.stageCount(), scalar.feedMolPerSecond());
            } catch (IllegalArgumentException invalidDraws) {
                throw new IllegalArgumentException("Side draws: " + invalidDraws.getMessage(), invalidDraws);
            }
            return new V3ColumnInput(
                    V3ColumnInput.SCHEMA_VERSION, base.packageId(), base.assayId(), base.componentBasis(), scaledFlows,
                    scalar.feedTemperatureKelvin(), scalar.stageCount(), scalar.feedStage(),
                    scalar.topPressurePascal(), scalar.pressureDropPascal(), List.of(
                            new V3ColumnSpecification.CondenserOutletTemperature(scalar.condenserTemperatureKelvin()),
                            new V3ColumnSpecification.OrganicRefluxRatio(scalar.refluxRatio()),
                            new V3ColumnSpecification.ReboilerDuty(scalar.reboilerDutyWatts())), draws);
        } catch (IllegalArgumentException invalid) {
            draftValidationDetail = invalid.getMessage() == null || invalid.getMessage().isBlank()
                    ? "Invalid scientific draft." : invalid.getMessage();
            return null;
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BORDER);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, BACKGROUND);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "Column Calculator V3 (Experimental)", 10, 8, TEXT, false);
        graphics.drawString(font, "Registered Tia Juana Light PR package | server-authoritative dry V3", 10, 20, MUTED, false);
        switch (page) {
            case INPUTS -> renderInputs(graphics);
            case STREAMS -> renderStreams(graphics);
            case CONVERGENCE -> renderConvergence(graphics);
        }
    }

    private void renderInputs(GuiGraphics graphics) {
        String[] labels = {
                "Feed (kmol/h)", "Feed temp (C)", "Theor. stages",
                "Feed stage", "Condenser (C)", "Reboiler (MW)",
                "Reflux L/D", "Top P (bar)", "Drop (kPa/stage)"
        };
        int scalarColumnWidth = Math.max(1, (imageWidth - 20) / 3);
        for (int index = 0; index < labels.length; index++) {
            int column = index % 3;
            int row = index / 3;
            graphics.drawString(font, labels[index], 10 + column * scalarColumnWidth,
                    CONTENT_TOP + row * 38, MUTED, false);
        }
        for (int index = 0; index < SIDE_DRAW_COUNT; index++) {
            graphics.drawString(font, "Side " + (index + 1) + "  stage / kmol/h", 10 + index * scalarColumnWidth,
                    CONTENT_TOP + 114, MUTED, false);
        }
        if (serverState == null) {
            graphics.drawString(font, "Waiting for server V3 calculator state.", 10, CONTENT_TOP + 159, NOTICE, false);
            return;
        }
        V3ColumnInput input = serverState.input();
        graphics.drawString(font, "Liquid side draws: tray and kmol/h. Blank or zero rate disables a row.",
                10, CONTENT_TOP + 159, NOTICE, false);
        graphics.drawString(font, "Draws require positive liquid downflow; the solver reports infeasible operating points.",
                10, CONTENT_TOP + 173, MUTED, false);
        graphics.drawString(font, "Input revision " + serverState.inputRevision() + " • state " + serverState.stateRevision()
                        + " • V3 assay " + input.assayId(),
                10, CONTENT_TOP + 187, MUTED, false);
        int statusColor = run != null && run.active ? SUCCESS : NOTICE;
        graphics.drawString(font, abbreviate(validation, 82), 101, imageHeight - 24, statusColor, false);
    }

    private void renderStreams(GuiGraphics graphics) {
        graphics.drawString(font, "Accepted phase stream properties and compositions", 10, CONTENT_TOP, TEXT, false);
        if (serverState == null || serverState.displayResult().isEmpty() || serverState.displayResult().orElseThrow().streams().isEmpty()) {
            graphics.drawString(font, "No accepted V3 stream properties are available.", 10, CONTENT_TOP + 29, NOTICE, false);
            graphics.drawString(font, "Failed, calculating, and legacy presentation results never fabricate product streams.",
                    10, CONTENT_TOP + 44, MUTED, false);
            return;
        }
        V3ColumnDisplayResult result = serverState.displayResult().orElseThrow();
        String resultState = serverState.status() == V3Status.SUCCESS ? "current accepted result"
                : "retained accepted result (current draft is " + serverState.status().serializedName() + ")";
        graphics.drawString(font, "Result revision " + serverState.resultRevision() + " • " + resultState,
                10, CONTENT_TOP + 14, serverState.status() == V3Status.SUCCESS ? SUCCESS : NOTICE, false);
        List<V3ColumnStreamProperties> streams = result.streams();
        int first = streamPage * streamsPerPage();
        int visible = Math.min(streamsPerPage(), streams.size() - first);
        int streamWidth = Math.max(1, (imageWidth - 20) / visible);
        for (int index = 0; index < visible; index++) {
            renderStreamReport(graphics, 10 + index * streamWidth, streamWidth, CONTENT_TOP + 29, streams.get(first + index));
        }
    }

    private int streamsPerPage() {
        return Math.clamp((imageWidth - 20) / 200, 1, 3);
    }

    private void renderStreamReport(
            GuiGraphics graphics, int x, int width, int y, V3ColumnStreamProperties stream) {
        graphics.drawString(font, abbreviateToWidth(stream.displayName() + " • " + stream.phase(), width - 2), x, y, TEXT, false);
        graphics.drawString(font, abbreviateToWidth("F " + compact(stream.molarFlowMolPerSecond() * MOL_PER_SECOND_TO_KMOL_PER_HOUR)
                        + " kmol/h | " + compact(stream.massFlowKgPerSecond() * 3_600.0) + " kg/h", width - 4),
                x, y + 12, MUTED, false);
        graphics.drawString(font, abbreviateToWidth("T " + compact(stream.temperatureKelvin() - CELSIUS_TO_KELVIN) + " C | P "
                        + compact(stream.pressurePascal() * PASCAL_TO_BAR) + " bar | V/F "
                        + compact(stream.vaporMoleFraction()), width - 4),
                x, y + 23, MUTED, false);
        int[] widths = distributedWidths(width, new double[] {0.42, 0.29, 0.29});
        int tableY = y + 35;
        drawTableRow(graphics, x, tableY, 12, widths, new String[] {"Component", "mol %", "wt %"}, true);
        List<V3ColumnStreamProperties.ComponentFraction> fractions = stream.moleFractions();
        for (int index = 0; index < fractions.size(); index++) {
            V3ColumnStreamProperties.ComponentFraction fraction = fractions.get(index);
            drawTableRow(graphics, x, tableY + 12 * (index + 1), 12, widths, new String[] {
                    fraction.componentId(), compact(fraction.moleFraction() * 100.0), compact(fraction.massFraction() * 100.0)
            }, false);
        }
    }

    private void renderConvergence(GuiGraphics graphics) {
        graphics.drawString(font, "Convergence & provenance", 10, CONTENT_TOP, TEXT, false);
        if (serverState == null) {
            graphics.drawString(font, "Waiting for server state...", 10, CONTENT_TOP + 27, NOTICE, false);
            return;
        }
        V3Status status = serverState.status();
        int statusColor = status == V3Status.SUCCESS ? SUCCESS : status == V3Status.FAILED ? FAILURE : MUTED;
        graphics.drawString(font, "Status: " + status.serializedName(), 10, CONTENT_TOP + 27, statusColor, false);
        String detail = serverState.diagnostics().isEmpty() ? "No server detail" : serverState.diagnostics().getFirst();
        graphics.drawString(font, abbreviate(detail, 87), 10, CONTENT_TOP + 42, MUTED, false);
        if (serverState.displayResult().isPresent()) {
            V3ColumnDisplayResult result = serverState.displayResult().orElseThrow();
            graphics.drawString(font, "Accepted audit checks: " + result.acceptanceCheckCount(), 10, CONTENT_TOP + 68, MUTED, false);
            graphics.drawString(font, "Newton iterations: " + result.newtonIterations(), 10, CONTENT_TOP + 83, MUTED, false);
            graphics.drawString(font, "Maximum scaled residual: " + compact(result.maximumScaledResidual()), 10, CONTENT_TOP + 98, MUTED, false);
            graphics.drawString(font, "Published streams: " + result.streams().size(), 10, CONTENT_TOP + 113, MUTED, false);
            graphics.drawString(font, "Input digest: " + result.inputDigest().substring(0, 16) + "…", 10, CONTENT_TOP + 139, MUTED, false);
            graphics.drawString(font, "Formulation: " + result.formulationRevision(), 10, CONTENT_TOP + 154, MUTED, false);
            graphics.drawString(font, "Dataset: " + result.datasetRevision(), 10, CONTENT_TOP + 169, MUTED, false);
        } else {
            graphics.drawString(font, "A successful fresh audit will publish provenance and physical stream properties.",
                    10, CONTENT_TOP + 68, MUTED, false);
        }
        graphics.drawString(font, "Success requires fresh audit and convergence evidence; this page does not assert convergence.",
                10, CONTENT_TOP + 223, NOTICE, false);
        graphics.drawString(font, "Block: " + menu.blockPos().toShortString(), 10, CONTENT_TOP + 247, MUTED, false);
    }

    private void drawTableRow(
            GuiGraphics graphics, int x, int y, int rowHeight, int[] widths, String[] cells, boolean header) {
        int color = header ? TABLE_HEADER : ((y / rowHeight) & 1) == 0 ? TABLE_ROW : TABLE_ALT_ROW;
        graphics.fill(x, y, x + sum(widths), y + rowHeight, color);
        int cellX = x;
        for (int index = 0; index < widths.length; index++) {
            graphics.fill(cellX, y, cellX + 1, y + rowHeight, TABLE_GRID);
            graphics.drawString(font, abbreviateToWidth(cells[index], Math.max(1, widths[index] - 6)), cellX + 3,
                    y + Math.max(1, (rowHeight - 9) / 2),
                    header ? TEXT : MUTED, false);
            cellX += widths[index];
        }
        graphics.fill(x + sum(widths) - 1, y, x + sum(widths), y + rowHeight, TABLE_GRID);
        graphics.fill(x, y + rowHeight - 1, x + sum(widths), y + rowHeight, TABLE_GRID);
    }

    private static int[] distributedWidths(int totalWidth, double[] shares) {
        int[] widths = new int[shares.length];
        int used = 0;
        for (int index = 0; index < shares.length - 1; index++) {
            widths[index] = Math.max(1, (int) Math.floor(totalWidth * shares[index]));
            used += widths[index];
        }
        widths[widths.length - 1] = Math.max(1, totalWidth - used);
        return widths;
    }

    private static int sum(int[] values) {
        int sum = 0;
        for (int value : values) sum += value;
        return sum;
    }

    private static double specificationValue(V3ColumnInput input, V3ControlledQuantity wanted) {
        for (V3ColumnSpecification specification : input.specifications()) {
            if (specification.controlledQuantity() != wanted) continue;
            return switch (specification) {
                case V3ColumnSpecification.CondenserOutletTemperature temperature -> temperature.kelvin();
                case V3ColumnSpecification.OrganicRefluxRatio reflux -> reflux.ratio();
                case V3ColumnSpecification.ReboilerDuty duty -> duty.watts();
            };
        }
        throw new IllegalArgumentException("Missing V3 specification " + wanted);
    }

    private static String compactDraft(double value) {
        return String.format(Locale.ROOT, "%.6f", value).replaceFirst("0+$", "").replaceFirst("\\.$", "");
    }

    private static String compact(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.5g", value) : "--";
    }

    private static String abbreviate(String value, int maximumCharacters) {
        return value.length() <= maximumCharacters ? value : value.substring(0, Math.max(1, maximumCharacters - 1)) + "…";
    }

    private String abbreviateToWidth(String value, int maximumWidth) {
        if (font.width(value) <= maximumWidth) return value;
        String ellipsis = "…";
        int end = value.length();
        while (end > 1 && font.width(value.substring(0, end) + ellipsis) > maximumWidth) end--;
        return value.substring(0, end) + ellipsis;
    }

    private enum Page {
        INPUTS,
        STREAMS,
        CONVERGENCE
    }

    private record SideDrawFields(EditBox stage, EditBox rate) {
    }
}
