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
        imageWidth = 410;
        imageHeight = 280;
    }

    @Override
    protected void init() {
        String[] draft = editorDraft();
        super.init();
        editors.clear();
        setup = addRenderableWidget(Button.builder(Component.literal("Setup"), button -> selectPage(Page.SETUP))
                .bounds(leftPos + 8, topPos + 6, 64, 20).build());
        convergence = addRenderableWidget(Button.builder(Component.literal("Convergence"), button -> selectPage(Page.CONVERGENCE))
                .bounds(leftPos + 76, topPos + 6, 90, 20).build());
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
            EditBox editor = new EditBox(font, leftPos + 10 + column * 198, topPos + 59 + row * 30, 114, 18,
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
        graphics.drawString(font, abbreviate(validation, 47), leftPos + 148, topPos + imageHeight - 20, NOTICE, false);
    }

    private void renderSetup(GuiGraphics graphics) {
        graphics.drawString(font, "Column Calculator V3 (Experimental)", leftPos + 224, topPos + 12, TEXT, false);
        graphics.drawString(font, "Setup", leftPos + 10, topPos + 39, TEXT, false);
        String[] labels = {"Feed mol/s", "Feed K", "Stages", "Feed stage", "Top kPa", "Drop kPa/stage",
                "Condenser K", "Reflux", "Reboiler MW"};
        for (int index = 0; index < labels.length; index++) {
            int column = index / 5;
            int row = index % 5;
            graphics.drawString(font, labels[index], leftPos + 10 + column * 198, topPos + 47 + row * 30, MUTED, false);
        }
        graphics.drawString(font, "Pilot composition: registered PR package, PC03 = 50 mol/s, PC10 = 50 mol/s.",
                leftPos + 10, topPos + 214, MUTED, false);
        graphics.drawString(font, "Exact-zero public components remain present in the V3 scientific input axis.",
                leftPos + 10, topPos + 226, MUTED, false);
        graphics.drawString(font, "Side draws and water/steam are not exposed until their V3 scientific contracts exist.",
                leftPos + 10, topPos + 250, NOTICE, false);
    }

    private void renderConvergence(GuiGraphics graphics) {
        graphics.drawString(font, "V3 Convergence & Provenance", leftPos + 184, topPos + 12, TEXT, false);
        graphics.drawString(font, "No authoritative V3 state has been received for this block.", leftPos + 10, topPos + 51, TEXT, false);
        graphics.drawString(font, "The installed science core requires a fresh acceptance audit and immutable", leftPos + 10, topPos + 78, MUTED, false);
        graphics.drawString(font, "final-step convergence evidence before it can publish Success.", leftPos + 10, topPos + 90, MUTED, false);
        graphics.drawString(font, "The forthcoming server protocol will report residual families, iterations,", leftPos + 10, topPos + 117, MUTED, false);
        graphics.drawString(font, "globalization path, input digest, thermo dataset, and acceptance profile here.", leftPos + 10, topPos + 129, MUTED, false);
        graphics.drawString(font, "Run is deliberately unavailable in this UI-only checkpoint.", leftPos + 10, topPos + 163, NOTICE, false);
        graphics.drawString(font, "Block: " + menu.blockPos().toShortString(), leftPos + 10, topPos + 189, MUTED, false);
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(1, maximum - 1)) + "…";
    }

    private enum Page {
        SETUP,
        CONVERGENCE
    }
}
