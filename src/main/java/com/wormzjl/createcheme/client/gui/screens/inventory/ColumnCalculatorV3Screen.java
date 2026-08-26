package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * V3 draft screen shaped like the Next calculator without importing any Next input, state, or result contract.
 *
 * <p>Its controls are intentionally local-only until the versioned V3 packet/state protocol is available. In
 * particular, the disabled Run button prevents a client draft from being mistaken for a server-accepted calculation.</p>
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

    private final List<EditBox> editors = new ArrayList<>();
    private Page page = Page.SETUP;
    private String validation = "Draft uses the registered PC03/PC10 binary pilot fixture.";
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
        String[] draft = editorDraft();
        super.init();
        titleLabelX = 192;
        titleLabelY = 11;
        inventoryLabelY = -1000;
        editors.clear();
        setup = addRenderableWidget(Button.builder(Component.literal("Setup"), button -> selectPage(Page.SETUP))
                .bounds(leftPos + 8, topPos + 6, 64, 20).build());
        convergence = addRenderableWidget(Button.builder(Component.literal("Convergence"), button -> selectPage(Page.CONVERGENCE))
                .bounds(leftPos + 76, topPos + 6, 96, 20).build());
        run = addRenderableWidget(Button.builder(Component.literal("Run (server pending)"), button -> {})
                .bounds(leftPos + 8, topPos + imageHeight - 26, 132, 20).build());
        run.active = false;
        buildEditors();
        if (draft == null) loadPilotFixture();
        else restoreEditorDraft(draft);
        validateDraft();
        refreshControls();
    }

    private void buildEditors() {
        String[] labels = {"Feed mol/s", "Feed K", "Stages", "Feed stage", "Top kPa", "Drop kPa/stage",
                "Condenser K", "Reflux", "Reboiler MW"};
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

    private String[] editorDraft() {
        if (editors.size() != CORE_EDITOR_COUNT) return null;
        String[] draft = new String[CORE_EDITOR_COUNT];
        for (int index = 0; index < draft.length; index++) draft[index] = editors.get(index).getValue();
        return draft;
    }

    private void restoreEditorDraft(String[] draft) {
        if (draft.length != CORE_EDITOR_COUNT) {
            loadPilotFixture();
            return;
        }
        for (int index = 0; index < draft.length; index++) editors.get(index).setValue(draft[index]);
    }

    private void loadPilotFixture() {
        String[] values = {"100", "550", "2", "1", "250", "0.75", "300", "2", "0"};
        for (int index = 0; index < values.length; index++) editors.get(index).setValue(values[index]);
    }

    private void selectPage(Page target) {
        page = target;
        refreshControls();
    }

    private void refreshControls() {
        boolean showSetup = page == Page.SETUP;
        for (EditBox editor : editors) {
            editor.visible = showSetup;
            editor.active = showSetup;
        }
        setup.active = !showSetup;
        convergence.active = showSetup;
        run.visible = showSetup;
    }

    private void validateDraft() {
        if (editors.size() != CORE_EDITOR_COUNT) return;
        try {
            double flow = decimal(0);
            double feedTemperature = decimal(1);
            int stages = integer(2);
            int feedStage = integer(3);
            double topPressure = decimal(4);
            double pressureDrop = decimal(5);
            double condenserTemperature = decimal(6);
            double reflux = decimal(7);
            double reboilerDuty = decimal(8);
            if (flow <= 0.0 || feedTemperature <= 0.0 || stages < 2 || stages > 64 || feedStage < 1 || feedStage > stages
                    || topPressure <= 0.0 || pressureDrop < 0.0 || condenserTemperature <= 0.0 || reflux < 0.0
                    || reboilerDuty < 0.0) {
                validation = "Draft is outside the V3 dry-input bounds.";
                return;
            }
            validation = "Local draft is valid. Run stays disabled until the V3 server protocol is installed.";
        } catch (NumberFormatException invalid) {
            validation = "Enter finite numeric values in every V3 setup field.";
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
        graphics.drawString(font, abbreviate(validation, 34), leftPos + 148, topPos + imageHeight - 20, NOTICE, false);
    }

    private void renderSetup(GuiGraphics graphics) {
        graphics.drawString(font, "Dry baseline setup", leftPos + 12, topPos + 39, TEXT, false);
        String[] labels = {"Feed mol/s", "Feed K", "Stages", "Feed stage", "Top kPa", "Drop kPa/stage",
                "Condenser K", "Reflux", "Reboiler MW"};
        for (int index = 0; index < labels.length; index++) {
            int column = index / 5;
            int row = index % 5;
            graphics.drawString(font, labels[index], leftPos + 12 + column * FIELD_COLUMN_WIDTH,
                    topPos + FIELD_LABEL_Y + row * FIELD_ROW_SPACING, MUTED, false);
        }
        graphics.drawString(font, "Pilot feed: registered PR binary", leftPos + 12, topPos + 258, TEXT, false);
        graphics.drawString(font, "PC03 = 50 mol/s; PC10 = 50 mol/s.", leftPos + 12, topPos + 271, MUTED, false);
        graphics.drawString(font, "All other public-axis components are exact zero.", leftPos + 12, topPos + 284, MUTED, false);
        graphics.drawString(font, "Side draws and water/steam await V3 contracts.", leftPos + 12, topPos + 300, NOTICE, false);
    }

    private void renderConvergence(GuiGraphics graphics) {
        graphics.drawString(font, "Convergence & provenance", leftPos + 12, topPos + 51, TEXT, false);
        graphics.drawString(font, "No authoritative V3 server state is attached to this block yet.", leftPos + 12, topPos + 78, MUTED, false);
        graphics.drawString(font, "Scientific Success requires a fresh acceptance audit plus", leftPos + 12, topPos + 105, MUTED, false);
        graphics.drawString(font, "immutable final-step convergence evidence.", leftPos + 12, topPos + 118, MUTED, false);
        graphics.drawString(font, "The server protocol will report residuals, iterations,", leftPos + 12, topPos + 145, MUTED, false);
        graphics.drawString(font, "digests, thermodynamic data, and solver provenance here.", leftPos + 12, topPos + 158, MUTED, false);
        graphics.drawString(font, "Run remains unavailable in this UI-only checkpoint.", leftPos + 12, topPos + 194, NOTICE, false);
        graphics.drawString(font, "Block: " + menu.blockPos().toShortString(), leftPos + 12, topPos + 222, MUTED, false);
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(1, maximum - 1)) + "…";
    }

    private enum Page {
        SETUP,
        CONVERGENCE
    }
}
