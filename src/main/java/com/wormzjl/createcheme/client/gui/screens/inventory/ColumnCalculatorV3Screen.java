package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.network.ColumnV3Network;
import com.wormzjl.createcheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.createcheme.science.column.v3.V3ControlledQuantity;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3State;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Status;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * V3 setup editor backed by revisioned server state.
 *
 * <p>The screen never treats its local fields as scientific state. It receives a bounded immutable input snapshot,
 * preserves its component split while editing total feed flow, and sends the complete candidate back with the base
 * input revision for authoritative validation.</p>
 */
public final class ColumnCalculatorV3Screen extends AbstractContainerScreen<ColumnCalculatorV3Menu> {
    private static final int CORE_EDITOR_COUNT = 9;
    private static final int FIELD_COLUMN_WIDTH = 206;
    private static final int FIELD_INPUT_WIDTH = 170;
    private static final int FIELD_LABEL_Y = 49;
    private static final int FIELD_INPUT_Y = 61;
    private static final int FIELD_ROW_SPACING = 41;
    private static final int BACKGROUND = 0xFF20252B;
    private static final int DIVIDER = 0xFF59636E;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int MUTED = 0xFF9AA6B2;
    private static final int NOTICE = 0xFFFFCC66;
    private static final int SUCCESS = 0xFF77DD88;
    private static final int FAILURE = 0xFFFF7777;

    private final List<EditBox> editors = new ArrayList<>();
    private Page page = Page.SETUP;
    private V3State serverState;
    private long latestStateRevision = -1L;
    private boolean calculationRequested;
    private String validation = "Waiting for server-owned V3 state...";
    private Button setup;
    private Button convergence;
    private Button run;

    public ColumnCalculatorV3Screen(ColumnCalculatorV3Menu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 440;
        imageHeight = 345;
    }

    @Override
    protected void init() {
        String[] localDraft = editorDraft();
        super.init();
        titleLabelX = 192;
        titleLabelY = 11;
        inventoryLabelY = -1000;
        editors.clear();
        setup = addRenderableWidget(Button.builder(Component.literal("Setup"), button -> selectPage(Page.SETUP))
                .bounds(leftPos + 8, topPos + 6, 64, 20).build());
        convergence = addRenderableWidget(Button.builder(Component.literal("Convergence"), button -> selectPage(Page.CONVERGENCE))
                .bounds(leftPos + 76, topPos + 6, 96, 20).build());
        run = addRenderableWidget(Button.builder(Component.literal("Run V3"), button -> requestCalculation())
                .bounds(leftPos + 8, topPos + imageHeight - 26, 132, 20).build());
        buildEditors();
        if (localDraft != null) restoreEditorDraft(localDraft);
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

    private void buildEditors() {
        String[] labels = labels();
        for (int index = 0; index < labels.length; index++) {
            int column = index / 5;
            int row = index % 5;
            EditBox editor = new EditBox(font, leftPos + 12 + column * FIELD_COLUMN_WIDTH,
                    topPos + FIELD_INPUT_Y + row * FIELD_ROW_SPACING, FIELD_INPUT_WIDTH, 20,
                    Component.literal(labels[index]));
            editor.setMaxLength(20);
            editor.setResponder(value -> validateDraft());
            editors.add(addRenderableWidget(editor));
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
        if (editors.size() != CORE_EDITOR_COUNT) return null;
        String[] draft = new String[CORE_EDITOR_COUNT];
        for (int index = 0; index < draft.length; index++) draft[index] = editors.get(index).getValue();
        return draft;
    }

    private void restoreEditorDraft(String[] draft) {
        if (draft.length != CORE_EDITOR_COUNT) return;
        for (int index = 0; index < draft.length; index++) editors.get(index).setValue(draft[index]);
    }

    private void loadInput(V3ColumnInput input) {
        double[] flows = input.feedComponentMolarFlowsMolPerSecond();
        double total = 0.0;
        for (double flow : flows) total += flow;
        String[] values = {
                compact(total), compact(input.feedTemperatureKelvin()), Integer.toString(input.stageCount()),
                Integer.toString(input.feedStageNumber()), compact(input.topPressurePascal() / 1_000.0),
                compact(input.stagePressureDropPascal() / 1_000.0),
                compact(specificationValue(input, V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)),
                compact(specificationValue(input, V3ControlledQuantity.ORGANIC_REFLUX_RATIO)),
                compact(specificationValue(input, V3ControlledQuantity.REBOILER_DUTY) / 1_000_000.0)
        };
        restoreEditorDraft(values);
    }

    private void selectPage(Page target) {
        page = target;
        refreshControls();
    }

    private void refreshControls() {
        boolean showSetup = page == Page.SETUP;
        boolean calculating = calculationRequested
                || serverState != null && serverState.status() == V3Status.CALCULATING;
        for (EditBox editor : editors) {
            editor.visible = showSetup;
            editor.active = showSetup && !calculating && serverState != null;
        }
        setup.active = !showSetup;
        convergence.active = showSetup;
        run.visible = showSetup;
        run.active = showSetup && !calculating && draftInput() != null;
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
            return;
        }
        if (draftInput() != null) {
            validation = "Local draft is valid; Run V3 performs server validation and solving.";
        } else {
            validation = "Enter finite values within the V3 dry-input bounds.";
        }
    }

    private V3ColumnInput draftInput() {
        if (serverState == null || editors.size() != CORE_EDITOR_COUNT) return null;
        try {
            double totalFlow = decimal(0);
            double feedTemperature = decimal(1);
            int stages = integer(2);
            int feedStage = integer(3);
            double topPressure = decimal(4) * 1_000.0;
            double pressureDrop = decimal(5) * 1_000.0;
            double condenserTemperature = decimal(6);
            double reflux = decimal(7);
            double reboilerDuty = decimal(8) * 1_000_000.0;
            if (totalFlow <= 0.0 || feedTemperature <= 0.0 || stages < V3ColumnInput.MIN_STAGE_COUNT
                    || stages > V3ColumnInput.MAX_STAGE_COUNT || feedStage < 1 || feedStage > stages
                    || topPressure <= 0.0 || pressureDrop < 0.0 || condenserTemperature <= 0.0 || reflux < 0.0
                    || reboilerDuty < 0.0) {
                return null;
            }
            V3ColumnInput base = serverState.input();
            double[] existingFlows = base.feedComponentMolarFlowsMolPerSecond();
            double existingTotal = 0.0;
            for (double flow : existingFlows) existingTotal += flow;
            if (!Double.isFinite(existingTotal) || existingTotal <= 0.0) return null;
            double[] scaledFlows = new double[existingFlows.length];
            double scale = totalFlow / existingTotal;
            for (int index = 0; index < scaledFlows.length; index++) scaledFlows[index] = existingFlows[index] * scale;
            return new V3ColumnInput(
                    V3ColumnInput.SCHEMA_VERSION,
                    base.packageId(),
                    base.assayId(),
                    base.componentBasis(),
                    scaledFlows,
                    feedTemperature,
                    stages,
                    feedStage,
                    topPressure,
                    pressureDrop,
                    List.of(
                            new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperature),
                            new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                            new V3ColumnSpecification.ReboilerDuty(reboilerDuty)));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private double decimal(int editor) {
        double value = Double.parseDouble(editors.get(editor).getValue());
        if (!Double.isFinite(value)) throw new NumberFormatException("non-finite value");
        return value;
    }

    private int integer(int editor) {
        return Integer.parseInt(editors.get(editor).getValue());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BACKGROUND);
        graphics.fill(leftPos, topPos + 31, leftPos + imageWidth, topPos + 32, DIVIDER);
        if (page == Page.SETUP) renderSetup(graphics);
        else renderConvergence(graphics);
        int validationColor = validation.startsWith("Server rejected") ? FAILURE : NOTICE;
        graphics.drawString(font, abbreviate(validation, 34), leftPos + 148, topPos + imageHeight - 20, validationColor, false);
    }

    private void renderSetup(GuiGraphics graphics) {
        graphics.drawString(font, "Dry V3 setup", leftPos + 12, topPos + 39, TEXT, false);
        String[] labels = labels();
        for (int index = 0; index < labels.length; index++) {
            int column = index / 5;
            int row = index % 5;
            graphics.drawString(font, labels[index], leftPos + 12 + column * FIELD_COLUMN_WIDTH,
                    topPos + FIELD_LABEL_Y + row * FIELD_ROW_SPACING, MUTED, false);
        }
        if (serverState == null) {
            graphics.drawString(font, "Waiting for the server V3 calculator state.", leftPos + 12, topPos + 258, NOTICE, false);
            return;
        }
        graphics.drawString(font, "Feed composition: server-provided registered PR axis", leftPos + 12, topPos + 258, TEXT, false);
        graphics.drawString(font, "This slice preserves the PC03/PC10 composition while feed rate changes.", leftPos + 12,
                topPos + 271, MUTED, false);
        graphics.drawString(font, "Input revision " + serverState.inputRevision() + "  •  State " + serverState.stateRevision(),
                leftPos + 12, topPos + 284, MUTED, false);
        graphics.drawString(font, "Side draws, utilities, and composition editing are later V3 scope.", leftPos + 12,
                topPos + 300, NOTICE, false);
    }

    private void renderConvergence(GuiGraphics graphics) {
        graphics.drawString(font, "Convergence & provenance", leftPos + 12, topPos + 51, TEXT, false);
        if (serverState == null) {
            graphics.drawString(font, "Waiting for server state...", leftPos + 12, topPos + 78, NOTICE, false);
            return;
        }
        V3Status status = serverState.status();
        int statusColor = status == V3Status.SUCCESS ? SUCCESS : status == V3Status.FAILED ? FAILURE : MUTED;
        graphics.drawString(font, "Status: " + status.serializedName(), leftPos + 12, topPos + 78, statusColor, false);
        String detail = serverState.diagnostics().isEmpty() ? "No server detail" : serverState.diagnostics().getFirst();
        graphics.drawString(font, abbreviate(detail, 53), leftPos + 12, topPos + 100, MUTED, false);
        if (serverState.displayResult().isPresent()) {
            V3ColumnDisplayResult result = serverState.displayResult().orElseThrow();
            graphics.drawString(font, "Accepted audit checks: " + result.acceptanceCheckCount(), leftPos + 12, topPos + 129, MUTED, false);
            graphics.drawString(font, "Newton iterations: " + result.newtonIterations(), leftPos + 12, topPos + 142, MUTED, false);
            graphics.drawString(font, "Maximum scaled residual: " + compact(result.maximumScaledResidual()), leftPos + 12,
                    topPos + 155, MUTED, false);
            graphics.drawString(font, "Input digest: " + result.inputDigest().substring(0, 16) + "…", leftPos + 12,
                    topPos + 181, MUTED, false);
            graphics.drawString(font, "Formulation: " + result.formulationRevision(), leftPos + 12, topPos + 194, MUTED, false);
            graphics.drawString(font, "Dataset: " + result.datasetRevision(), leftPos + 12, topPos + 207, MUTED, false);
        } else {
            graphics.drawString(font, "A successful fresh audit will appear here with provenance.", leftPos + 12,
                    topPos + 129, MUTED, false);
        }
        graphics.drawString(font, "Success requires fresh audit and convergence evidence.", leftPos + 12, topPos + 252, NOTICE, false);
        graphics.drawString(font, "Block: " + menu.blockPos().toShortString(), leftPos + 12, topPos + 276, MUTED, false);
    }

    private static String[] labels() {
        return new String[] {"Feed mol/s", "Feed K", "Stages", "Feed stage", "Top kPa", "Drop kPa/stage",
                "Condenser K", "Reflux", "Reboiler MW"};
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

    private static String compact(double value) {
        return String.format(java.util.Locale.ROOT, "%.6g", value);
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(1, maximum - 1)) + "…";
    }

    private enum Page {
        SETUP,
        CONVERGENCE
    }
}
