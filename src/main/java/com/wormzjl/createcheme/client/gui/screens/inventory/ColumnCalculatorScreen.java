package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.network.ColumnNetwork;
import com.wormzjl.createcheme.network.ColumnNetwork.ComponentRow;
import com.wormzjl.createcheme.network.ColumnNetwork.ProductColumn;
import com.wormzjl.createcheme.network.ColumnNetwork.ResultView;
import com.wormzjl.createcheme.science.column.ColumnSimulation;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnDiagnostic;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnInput;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnValidationResult;
import com.wormzjl.createcheme.science.column.ColumnSimulation.RefluxCondition;
import com.wormzjl.createcheme.science.column.ColumnSimulation.SideDrawSpec;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Responsive calculator form and tabular result viewer. The server remains authoritative. */
public final class ColumnCalculatorScreen extends AbstractContainerScreen<ColumnCalculatorMenu> {
    private static final String DEFAULT_ASSAY_ID = "createcheme:tia_juana_light_12";
    private static final double KILOMOL_PER_HOUR_TO_MOL_PER_SECOND = 1_000.0 / 3_600.0;
    private static final double MOL_PER_SECOND_TO_KILOMOL_PER_HOUR = 3.6;
    private static final double MEGAWATT_TO_WATT = 1_000_000.0;
    private static final double CELSIUS_TO_KELVIN = 273.15;
    private static final double PASCAL_TO_BAR = 1.0e-5;
    private static final int SIDE_DRAW_ROW_COUNT = 3;
    private static final int MAX_PANEL_WIDTH = 620;
    private static final int MAX_PANEL_HEIGHT = 360;
    private static final int PANEL_MARGIN = 10;
    private static final int CONTENT_TOP = 58;
    private static final int TABLE_MARGIN = 10;
    private static final int COMPOSITION_LABEL_WIDTH = 116;
    private static final int MIN_PRODUCT_COLUMN_WIDTH = 78;
    private static final float SMALL_TEXT_SCALE = 0.72F;
    private static final int PANEL_COLOR = 0xFF20252B;
    private static final int PANEL_BORDER_COLOR = 0xFF59636E;
    private static final int TABLE_HEADER_COLOR = 0xFF343C45;
    private static final int TABLE_ROW_COLOR = 0xFF252C33;
    private static final int TABLE_ALT_ROW_COLOR = 0xFF2A323A;
    private static final int TABLE_GRID_COLOR = 0xFF46515C;
    private static final int TEXT_COLOR = 0xFFE6EDF3;
    private static final int MUTED_TEXT_COLOR = 0xFF9AA6B2;
    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;
    private static final int SUCCESS_TEXT_COLOR = 0xFF77DD88;
    private static final int WARNING_TEXT_COLOR = 0xFFFFCC66;
    private static final Predicate<String> DECIMAL_DRAFT = ColumnCalculatorScreen::isDecimalDraft;
    private static final Predicate<String> INTEGER_DRAFT = value -> value.matches("[0-9]*");

    private final List<EditBox> inputFields = new ArrayList<>();
    private final List<SideDrawFields> sideDrawFields = new ArrayList<>();

    private EditBox feedFlowField;
    private EditBox feedTemperatureField;
    private EditBox stageCountField;
    private EditBox feedStageField;
    private EditBox reboilerDutyField;
    private EditBox refluxRatioField;
    private Button calculateButton;
    private Button inputsTabButton;
    private Button streamsTabButton;
    private Button compositionTabButton;
    private Button previousProductsButton;
    private Button nextProductsButton;
    private Button compositionBasisButton;
    private boolean awaitingResult;
    private boolean massBasis;
    private long pendingClientRequestId;
    private String validationMessage = "";
    private String resultStatus = "No result";
    private ResultView result;
    private Page page = Page.INPUTS;
    private int productOffset;

    public ColumnCalculatorScreen(ColumnCalculatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = MAX_PANEL_WIDTH;
        imageHeight = MAX_PANEL_HEIGHT;
        inventoryLabelY = imageHeight + 10;
    }

    @Override
    protected void init() {
        String feedFlowDraft = draftValue(feedFlowField, "2610.7");
        String feedTemperatureDraft = draftValue(feedTemperatureField, "365");
        String stageCountDraft = draftValue(stageCountField, "30");
        String feedStageDraft = draftValue(feedStageField, "24");
        String reboilerDutyDraft = draftValue(reboilerDutyField, "8");
        String refluxRatioDraft = draftValue(refluxRatioField, "4.17");
        String[] sideStageDrafts = {"8", "15", "22"};
        String[] sideRateDrafts = {"496", "653", "149"};
        for (int index = 0; index < Math.min(sideDrawFields.size(), SIDE_DRAW_ROW_COUNT); index++) {
            sideStageDrafts[index] = sideDrawFields.get(index).stage().getValue();
            sideRateDrafts[index] = sideDrawFields.get(index).rate().getValue();
        }

        imageWidth = Math.min(MAX_PANEL_WIDTH, Math.max(1, width - PANEL_MARGIN * 2));
        imageHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(1, height - PANEL_MARGIN * 2));
        inventoryLabelY = imageHeight + 10;
        super.init();
        inputFields.clear();
        sideDrawFields.clear();

        int tabY = topPos + 28;
        inputsTabButton = addRenderableWidget(Button.builder(
                        Component.literal("Inputs"), button -> selectPage(Page.INPUTS))
                .bounds(leftPos + 10, tabY, 72, 20)
                .build());
        streamsTabButton = addRenderableWidget(Button.builder(
                        Component.literal("Streams"), button -> selectPage(Page.STREAMS))
                .bounds(leftPos + 86, tabY, 72, 20)
                .build());
        compositionTabButton = addRenderableWidget(Button.builder(
                        Component.literal("Composition"), button -> selectPage(Page.COMPOSITION))
                .bounds(leftPos + 162, tabY, 92, 20)
                .build());

        int availableWidth = imageWidth - 20;
        int scalarColumnWidth = Math.max(1, availableWidth / 3);
        String[] scalarDrafts = {
                feedFlowDraft, feedTemperatureDraft, stageCountDraft,
                feedStageDraft, reboilerDutyDraft, refluxRatioDraft
        };
        @SuppressWarnings("unchecked")
        Predicate<String>[] scalarFilters = new Predicate[] {
                DECIMAL_DRAFT, DECIMAL_DRAFT, INTEGER_DRAFT,
                INTEGER_DRAFT, DECIMAL_DRAFT, DECIMAL_DRAFT
        };
        EditBox[] scalarFields = new EditBox[6];
        for (int index = 0; index < scalarFields.length; index++) {
            int column = index % 3;
            int row = index / 3;
            int x = leftPos + 10 + column * scalarColumnWidth;
            int y = topPos + CONTENT_TOP + 13 + row * 38;
            scalarFields[index] = addNumericField(
                    x, y, Math.max(30, Math.min(104, scalarColumnWidth - 8)),
                    scalarDrafts[index], scalarFilters[index]);
        }
        feedFlowField = scalarFields[0];
        feedTemperatureField = scalarFields[1];
        stageCountField = scalarFields[2];
        feedStageField = scalarFields[3];
        reboilerDutyField = scalarFields[4];
        refluxRatioField = scalarFields[5];

        int sideFieldY = topPos + CONTENT_TOP + 102;
        for (int index = 0; index < SIDE_DRAW_ROW_COUNT; index++) {
            int groupX = leftPos + 10 + index * scalarColumnWidth;
            int stageWidth = Math.max(28, Math.min(44, scalarColumnWidth / 3));
            int rateWidth = Math.max(42, Math.min(78, scalarColumnWidth - stageWidth - 14));
            addSideDrawRow(
                    groupX, groupX + stageWidth + 6, sideFieldY, stageWidth, rateWidth,
                    sideStageDrafts[index], sideRateDrafts[index]);
        }

        calculateButton = addRenderableWidget(Button.builder(
                        Component.literal("Calculate"), button -> submitInput())
                .bounds(leftPos + 10, topPos + imageHeight - 29, 82, 20)
                .build());
        previousProductsButton = addRenderableWidget(Button.builder(
                        Component.literal("<"), button -> changeProductPage(-1))
                .bounds(leftPos + imageWidth - 112, topPos + 61, 24, 18)
                .build());
        nextProductsButton = addRenderableWidget(Button.builder(
                        Component.literal(">"), button -> changeProductPage(1))
                .bounds(leftPos + imageWidth - 84, topPos + 61, 24, 18)
                .build());
        compositionBasisButton = addRenderableWidget(Button.builder(
                        compositionBasisLabel(), button -> toggleCompositionBasis())
                .bounds(leftPos + imageWidth - 56, topPos + 61, 46, 18)
                .build());

        setInputsEditable(!awaitingResult);
        refreshValidity();
        updateControlState();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_BORDER_COLOR);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, PANEL_COLOR);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, Component.literal("Crude Distillation Calculator"), 10, 8, TEXT_COLOR, false);
        drawScaledText(
                guiGraphics,
                "Tia Juana Light (12 cuts) | 2.5 bar | saturated reflux",
                10, 20, SMALL_TEXT_SCALE, MUTED_TEXT_COLOR, imageWidth - 20, false);
        switch (page) {
            case INPUTS -> renderInputs(guiGraphics);
            case STREAMS -> renderStreams(guiGraphics);
            case COMPOSITION -> renderComposition(guiGraphics);
        }
    }

    /** Called by the client payload handler after the server has resolved a request. */
    public void acceptResult(long clientRequestId, ResultView receivedResult) {
        if (!awaitingResult || clientRequestId != pendingClientRequestId) {
            return;
        }
        awaitingResult = false;
        pendingClientRequestId = 0L;
        setInputsEditable(true);
        result = receivedResult;
        resultStatus = boundedText(receivedResult.status());
        productOffset = 0;
        if (!receivedResult.products().isEmpty()) {
            page = Page.STREAMS;
        } else {
            page = Page.INPUTS;
            if (!receivedResult.messages().isEmpty()) {
                validationMessage = boundedText(receivedResult.messages().getFirst());
            }
        }
        refreshValidity();
        updateControlState();
    }

    private void renderInputs(GuiGraphics guiGraphics) {
        String[] labels = {
                "Feed (kmol/h)", "Feed temp (C)", "Theor. stages",
                "Feed stage", "Reboiler (MW)", "Reflux L/D"
        };
        int scalarColumnWidth = Math.max(1, (imageWidth - 20) / 3);
        for (int index = 0; index < labels.length; index++) {
            int column = index % 3;
            int row = index / 3;
            drawScaledText(
                    guiGraphics, labels[index], 10 + column * scalarColumnWidth,
                    CONTENT_TOP + row * 38, SMALL_TEXT_SCALE, MUTED_TEXT_COLOR,
                    scalarColumnWidth - 6, false);
        }
        for (int index = 0; index < SIDE_DRAW_ROW_COUNT; index++) {
            drawScaledText(
                    guiGraphics, "Side " + (index + 1) + "  stage / kmol/h",
                    10 + index * scalarColumnWidth, CONTENT_TOP + 88,
                    SMALL_TEXT_SCALE, MUTED_TEXT_COLOR, scalarColumnWidth - 6, false);
        }
        int statusColor = calculateButton != null && calculateButton.active
                ? SUCCESS_TEXT_COLOR : ERROR_TEXT_COLOR;
        drawScaledText(
                guiGraphics, validationMessage, 101, imageHeight - 24,
                SMALL_TEXT_SCALE, statusColor, imageWidth - 111, false);
        drawScaledText(
                guiGraphics, "Last result: " + resultStatus, 10, CONTENT_TOP + 129,
                SMALL_TEXT_SCALE,
                result != null && result.placeholder() ? WARNING_TEXT_COLOR : MUTED_TEXT_COLOR,
                imageWidth - 20, false);
        drawScaledText(
                guiGraphics,
                "PoC limitation: direct liquid side draws; no side strippers or pumparounds.",
                10, CONTENT_TOP + 143, SMALL_TEXT_SCALE, MUTED_TEXT_COLOR,
                imageWidth - 20, false);
    }

    private void renderStreams(GuiGraphics guiGraphics) {
        if (!hasProducts()) {
            renderNoResult(guiGraphics);
            return;
        }
        renderResultBanner(guiGraphics);
        int tableX = TABLE_MARGIN;
        int tableY = 91;
        int tableWidth = imageWidth - TABLE_MARGIN * 2;
        List<ProductColumn> products = result.products();
        int rowHeight = Math.max(
                10,
                Math.min(17, Math.max(1, imageHeight - tableY - 10) / (products.size() + 1)));
        String[] headers = {"Stream", "Spec", "kmol/h", "kg/s", "C", "bar", "Phase", "T5", "T50", "T95"};
        double[] shares = {0.16, 0.10, 0.12, 0.11, 0.08, 0.08, 0.10, 0.08, 0.08, 0.09};
        int[] widths = distributedWidths(tableWidth, shares);
        drawTableRow(guiGraphics, tableX, tableY, rowHeight, widths, headers, true);
        for (int index = 0; index < products.size(); index++) {
            ProductColumn product = products.get(index);
            String[] cells = {
                    product.streamId(), shortSpecification(product.rateSpecification()),
                    format(product.molarFlowMolPerSecond() * MOL_PER_SECOND_TO_KILOMOL_PER_HOUR, 3),
                    format(product.massFlowKilogramPerSecond(), 4),
                    format(product.temperatureKelvin() - CELSIUS_TO_KELVIN, 2),
                    format(product.pressurePascal() * PASCAL_TO_BAR, 3), product.phase(),
                    format(product.t5Kelvin() - CELSIUS_TO_KELVIN, 1),
                    format(product.t50Kelvin() - CELSIUS_TO_KELVIN, 1),
                    format(product.t95Kelvin() - CELSIUS_TO_KELVIN, 1)
            };
            drawTableRow(
                    guiGraphics, tableX, tableY + rowHeight * (index + 1), rowHeight,
                    widths, cells, false);
        }
    }

    private void renderComposition(GuiGraphics guiGraphics) {
        if (!hasProducts()) {
            renderNoResult(guiGraphics);
            return;
        }
        renderResultBanner(guiGraphics);
        int tableX = TABLE_MARGIN;
        int tableY = 91;
        int tableWidth = imageWidth - TABLE_MARGIN * 2;
        int labelWidth = Math.min(COMPOSITION_LABEL_WIDTH, Math.max(76, tableWidth / 3));
        int capacity = visibleProductCapacity();
        int visibleProducts = Math.min(capacity, result.products().size() - productOffset);
        if (visibleProducts <= 0) {
            productOffset = 0;
            visibleProducts = Math.min(capacity, result.products().size());
        }
        int productWidth = Math.max(1, (tableWidth - labelWidth) / visibleProducts);
        int[] widths = new int[visibleProducts + 1];
        widths[0] = tableWidth - productWidth * visibleProducts;
        for (int index = 1; index < widths.length; index++) {
            widths[index] = productWidth;
        }
        int availableHeight = Math.max(1, imageHeight - tableY - 10);
        int rowHeight = Math.max(9, Math.min(15, availableHeight / (result.components().size() + 1)));
        String[] headers = new String[visibleProducts + 1];
        headers[0] = massBasis ? "Cut (mass %)" : "Cut (mol %)";
        for (int column = 0; column < visibleProducts; column++) {
            headers[column + 1] = result.products().get(productOffset + column).streamId();
        }
        drawTableRow(guiGraphics, tableX, tableY, rowHeight, widths, headers, true);
        for (int row = 0; row < result.components().size(); row++) {
            ComponentRow component = result.components().get(row);
            String[] cells = new String[visibleProducts + 1];
            cells[0] = component.componentId() + "  " + component.boilingRangeLabel();
            for (int column = 0; column < visibleProducts; column++) {
                ProductColumn product = result.products().get(productOffset + column);
                double fraction = massBasis
                        ? product.massFractions().get(row) : product.moleFractions().get(row);
                cells[column + 1] = formatPercent(fraction);
            }
            drawTableRow(
                    guiGraphics, tableX, tableY + rowHeight * (row + 1), rowHeight,
                    widths, cells, false);
        }
    }

    private void renderResultBanner(GuiGraphics guiGraphics) {
        int color = result.placeholder() ? WARNING_TEXT_COLOR : SUCCESS_TEXT_COLOR;
        String prefix = result.placeholder() ? "PLACEHOLDER / " : "";
        int bannerWidth = page == Page.COMPOSITION ? imageWidth - 132 : imageWidth - 20;
        drawScaledText(
                guiGraphics, prefix + result.status() + " | " + result.modelRevision(),
                10, 59, SMALL_TEXT_SCALE, color, bannerWidth, false);
        String message = result.messages().isEmpty()
                ? result.datasetRevision() : result.messages().getFirst();
        drawScaledText(
                guiGraphics, message, 10, 72, SMALL_TEXT_SCALE,
                MUTED_TEXT_COLOR, bannerWidth, false);
    }

    private void renderNoResult(GuiGraphics guiGraphics) {
        guiGraphics.drawString(
                font, Component.literal("Calculate a valid case to populate this table."),
                10, CONTENT_TOP + 10, MUTED_TEXT_COLOR, false);
    }

    private void drawTableRow(
            GuiGraphics guiGraphics, int x, int y, int height,
            int[] widths, String[] cells, boolean header) {
        int background = header
                ? TABLE_HEADER_COLOR
                : ((y / Math.max(1, height)) & 1) == 0 ? TABLE_ROW_COLOR : TABLE_ALT_ROW_COLOR;
        int cursor = x;
        for (int index = 0; index < cells.length; index++) {
            int cellWidth = widths[index];
            guiGraphics.fill(cursor, y, cursor + cellWidth, y + height, background);
            guiGraphics.fill(cursor, y, cursor + 1, y + height, TABLE_GRID_COLOR);
            guiGraphics.fill(cursor, y + height - 1, cursor + cellWidth, y + height, TABLE_GRID_COLOR);
            drawScaledText(
                    guiGraphics, cells[index], cursor + 2, y + Math.max(1, (height - 7) / 2),
                    SMALL_TEXT_SCALE, header ? TEXT_COLOR : MUTED_TEXT_COLOR,
                    Math.max(1, cellWidth - 4), index > 0);
            cursor += cellWidth;
        }
        guiGraphics.fill(cursor - 1, y, cursor, y + height, TABLE_GRID_COLOR);
    }

    private void drawScaledText(
            GuiGraphics guiGraphics, String text, int x, int y, float scale,
            int color, int maximumWidth, boolean rightAligned) {
        if (maximumWidth <= 0) {
            return;
        }
        int unscaledMaximum = Math.max(1, (int) (maximumWidth / scale));
        String visible = font.plainSubstrByWidth(boundedText(text), unscaledMaximum);
        float drawX = rightAligned ? x + maximumWidth - font.width(visible) * scale : x;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(drawX, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(font, Component.literal(visible), 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    private EditBox addNumericField(
            int x, int y, int fieldWidth, String initialValue, Predicate<String> filter) {
        EditBox field = new EditBox(font, x, y, fieldWidth, 16, Component.empty());
        field.setMaxLength(24);
        field.setFilter(filter);
        field.setValue(initialValue);
        field.setResponder(ignored -> refreshValidity());
        inputFields.add(field);
        return addRenderableWidget(field);
    }

    private void addSideDrawRow(
            int stageX, int rateX, int y, int stageWidth, int rateWidth,
            String initialStage, String initialRate) {
        EditBox stage = addNumericField(stageX, y, stageWidth, initialStage, INTEGER_DRAFT);
        EditBox rate = addNumericField(rateX, y, rateWidth, initialRate, DECIMAL_DRAFT);
        sideDrawFields.add(new SideDrawFields(stage, rate));
    }

    private void submitInput() {
        try {
            ColumnInput input = buildColumnInput();
            ColumnValidationResult validation = ColumnSimulation.validate(input);
            if (!validation.isValid()) {
                validationMessage = firstDiagnostic(validation);
                calculateButton.active = false;
                return;
            }
            resultStatus = "Submitting";
            result = null;
            productOffset = 0;
            awaitingResult = true;
            calculateButton.active = false;
            setInputsEditable(false);
            updateControlState();
            pendingClientRequestId = ColumnNetwork.sendCalculate(menu.blockPos(), input);
        } catch (IllegalArgumentException exception) {
            validationMessage = "Enter a valid number in every field";
            calculateButton.active = false;
        }
    }

    private ColumnInput buildColumnInput() {
        List<SideDrawSpec> sideDraws = sideDrawFields.stream()
                .map(fields -> new SideDrawSpec(
                        parseInteger(fields.stage()),
                        parseDecimal(fields.rate()) * KILOMOL_PER_HOUR_TO_MOL_PER_SECOND))
                .toList();
        return new ColumnInput(
                ColumnSimulation.INPUT_SCHEMA_VERSION, DEFAULT_ASSAY_ID,
                parseDecimal(feedFlowField) * KILOMOL_PER_HOUR_TO_MOL_PER_SECOND,
                parseDecimal(feedTemperatureField) + CELSIUS_TO_KELVIN,
                parseInteger(stageCountField), parseInteger(feedStageField),
                parseDecimal(reboilerDutyField) * MEGAWATT_TO_WATT,
                parseDecimal(refluxRatioField), RefluxCondition.saturatedLiquid(), sideDraws);
    }

    private void refreshValidity() {
        if (calculateButton == null
                || inputFields.isEmpty()
                || sideDrawFields.size() != SIDE_DRAW_ROW_COUNT) {
            return;
        }
        if (awaitingResult) {
            calculateButton.active = false;
            validationMessage = "Waiting for server";
            return;
        }
        try {
            ColumnValidationResult validation = ColumnSimulation.validate(buildColumnInput());
            calculateButton.active = validation.isValid();
            validationMessage = validation.isValid() ? "Ready" : firstDiagnostic(validation);
        } catch (IllegalArgumentException exception) {
            calculateButton.active = false;
            validationMessage = "Complete every numeric field";
        }
    }

    private void selectPage(Page selectedPage) {
        if (selectedPage != Page.INPUTS && !hasProducts()) {
            return;
        }
        page = selectedPage;
        productOffset = Math.min(productOffset, maximumProductOffset());
        updateControlState();
    }

    private void toggleCompositionBasis() {
        massBasis = !massBasis;
        if (compositionBasisButton != null) {
            compositionBasisButton.setMessage(compositionBasisLabel());
        }
    }

    private Component compositionBasisLabel() {
        return Component.literal(massBasis ? "mass %" : "mol %");
    }

    private void changeProductPage(int direction) {
        int capacity = visibleProductCapacity();
        productOffset = Math.clamp(
                productOffset + direction * capacity, 0, maximumProductOffset());
        updateControlState();
    }

    private void updateControlState() {
        boolean showInputs = page == Page.INPUTS;
        for (EditBox field : inputFields) {
            field.visible = showInputs;
            field.active = showInputs;
        }
        if (calculateButton != null) {
            calculateButton.visible = showInputs;
            refreshValidity();
        }
        boolean productsAvailable = hasProducts();
        if (inputsTabButton != null) {
            inputsTabButton.active = page != Page.INPUTS;
        }
        if (streamsTabButton != null) {
            streamsTabButton.active = productsAvailable && page != Page.STREAMS;
        }
        if (compositionTabButton != null) {
            compositionTabButton.active = productsAvailable && page != Page.COMPOSITION;
        }
        boolean showProductControls = page == Page.COMPOSITION && productsAvailable;
        int maximumOffset = maximumProductOffset();
        if (previousProductsButton != null) {
            previousProductsButton.visible = showProductControls && maximumOffset > 0;
            previousProductsButton.active = productOffset > 0;
        }
        if (nextProductsButton != null) {
            nextProductsButton.visible = showProductControls && maximumOffset > 0;
            nextProductsButton.active = productOffset < maximumOffset;
        }
        if (compositionBasisButton != null) {
            compositionBasisButton.visible = showProductControls;
            compositionBasisButton.active = showProductControls;
            compositionBasisButton.setMessage(compositionBasisLabel());
        }
    }

    private int visibleProductCapacity() {
        int tableWidth = Math.max(1, imageWidth - TABLE_MARGIN * 2);
        int labelWidth = Math.min(COMPOSITION_LABEL_WIDTH, Math.max(76, tableWidth / 3));
        return Math.max(1, (tableWidth - labelWidth) / MIN_PRODUCT_COLUMN_WIDTH);
    }

    private int maximumProductOffset() {
        return result == null ? 0 : Math.max(0, result.products().size() - visibleProductCapacity());
    }

    private boolean hasProducts() {
        return result != null && !result.products().isEmpty();
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

    private static String shortSpecification(String specification) {
        return "CALCULATED".equals(specification) ? "calc" : "input";
    }

    private static String format(double value, int decimals) {
        return Double.isFinite(value)
                ? String.format(Locale.ROOT, "%." + decimals + "f", value) : "--";
    }

    private static String formatPercent(double fraction) {
        double percent = fraction * 100.0;
        if (!Double.isFinite(percent)) {
            return "--";
        }
        if (percent == 0.0) {
            return "0";
        }
        if (Math.abs(percent) < 0.001) {
            return String.format(Locale.ROOT, "%.2e", percent);
        }
        if (Math.abs(percent) < 1.0) {
            return String.format(Locale.ROOT, "%.3f", percent);
        }
        return String.format(Locale.ROOT, "%.2f", percent);
    }

    private static String firstDiagnostic(ColumnValidationResult validation) {
        return validation.diagnostics().stream()
                .findFirst().map(ColumnCalculatorScreen::diagnosticText).orElse("Invalid input");
    }

    private static String diagnosticText(ColumnDiagnostic diagnostic) {
        String text = diagnostic.detail().isBlank()
                ? diagnostic.code().name().toLowerCase(Locale.ROOT) : diagnostic.detail();
        return boundedText(text);
    }

    private static double parseDecimal(EditBox field) {
        try {
            return Double.parseDouble(field.getValue());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid decimal", exception);
        }
    }

    private static int parseInteger(EditBox field) {
        try {
            return Integer.parseInt(field.getValue());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer", exception);
        }
    }

    private static boolean isDecimalDraft(String value) {
        if (value.isEmpty()) {
            return true;
        }
        return value.matches("[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]*)?");
    }

    private static String boundedText(String value) {
        String flattened = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return flattened.length() <= 256 ? flattened : flattened.substring(0, 256);
    }

    private static String draftValue(EditBox field, String defaultValue) {
        return field == null ? defaultValue : field.getValue();
    }

    private void setInputsEditable(boolean editable) {
        inputFields.forEach(field -> field.setEditable(editable));
    }

    private enum Page {
        INPUTS,
        STREAMS,
        COMPOSITION
    }

    private record SideDrawFields(EditBox stage, EditBox rate) {
    }
}
