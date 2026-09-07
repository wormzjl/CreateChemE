package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.network.ColumnV3Network;
import com.wormzjl.createcheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.createcheme.science.column.v3.V3ColumnDutyLedger;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.createcheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.createcheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.createcheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.createcheme.science.column.v3.V3ColumnStreamProperties;
import com.wormzjl.createcheme.science.column.v3.V3ControlledQuantity;
import com.wormzjl.createcheme.science.column.v3.V3HollandExample32;
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
    private static final int COOLER_COUNT = 3;
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
    private static final int COOLER_MARK = 0xFF378ADD;
    private static final int DRAW_MARK = 0xFF1D9E75;
    private static final int FEED_MARK = 0xFFD85A30;
    private static final int STEAM_MARK = 0xFFEF9F27;
    private static final int HEAT_NUMBER_X = 12;
    private static final int HEAT_DRAW_X = 26;
    private static final int HEAT_RETURN_X = 92;
    private static final int HEAT_COOLING_X = 158;
    private static final int HEAT_SPLIT_X = 240;
    private static final int HEAT_ROW_TOP = CONTENT_TOP + 30;
    private static final int HEAT_ROW_PITCH = 26;
    private static final int TRAY_MAP_X = 400;
    private static final int TRAY_MAP_MINIMUM_PANEL_WIDTH = 560;

    private final List<EditBox> coreEditors = new ArrayList<>();
    private final List<SideDrawFields> sideDrawFields = new ArrayList<>();
    private final List<CoolerFields> coolerFields = new ArrayList<>();
    private final V3PumparoundSpec.Split[] coolerSplits = {
            V3PumparoundSpec.Split.UNIFORM, V3PumparoundSpec.Split.UNIFORM, V3PumparoundSpec.Split.UNIFORM};
    private SteamFields steamFields;
    private Page page = Page.INPUTS;
    private V3State serverState;
    private long latestStateRevision = -1L;
    private boolean calculationRequested;
    private boolean loadingInput;
    private boolean draftEditedSinceState;
    private String[][] stashedCoolerDrafts;
    private V3PumparoundSpec.Split[] stashedCoolerSplits;
    private String validation = "Waiting for server-owned V3 state...";
    private String draftValidationDetail = "Enter all scalar inputs.";
    private Button inputsTab;
    private Button streamsTab;
    private Button heatTab;
    private Button convergenceTab;
    private Button preset;
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
        String[] sideRateDrafts = {"92.3", "131.85", "32.96"};
        String[] steamDrafts = steamFields == null ? new String[] {"", "", "", "", ""}
                : new String[] {steamFields.sumpRate().getValue(), steamFields.sumpTemperature().getValue(),
                        steamFields.trayStage().getValue(), steamFields.trayRate().getValue(),
                        steamFields.trayTemperature().getValue()};
        for (int index = 0; index < Math.min(sideDrawFields.size(), SIDE_DRAW_COUNT); index++) {
            sideStageDrafts[index] = sideDrawFields.get(index).stage().getValue();
            sideRateDrafts[index] = sideDrawFields.get(index).rate().getValue();
        }
        String[][] coolerDrafts = coolerDrafts();

        imageWidth = Math.min(MAX_PANEL_WIDTH, Math.max(1, width - PANEL_MARGIN * 2));
        imageHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(1, height - PANEL_MARGIN * 2));
        inventoryLabelY = imageHeight + 10;
        super.init();
        titleLabelY = -1_000;
        coreEditors.clear();
        sideDrawFields.clear();
        coolerFields.clear();
        steamFields = null;

        int tabY = topPos + 28;
        inputsTab = addRenderableWidget(Button.builder(Component.literal("Inputs"), button -> selectPage(Page.INPUTS))
                .bounds(leftPos + 10, tabY, 72, 20).build());
        streamsTab = addRenderableWidget(Button.builder(Component.literal("Streams"), button -> selectPage(Page.STREAMS))
                .bounds(leftPos + 86, tabY, 72, 20).build());
        heatTab = addRenderableWidget(Button.builder(Component.literal("Heat"), button -> selectPage(Page.HEAT))
                .bounds(leftPos + 162, tabY, 72, 20).build());
        convergenceTab = addRenderableWidget(Button.builder(Component.literal("Convergence"), button -> selectPage(Page.CONVERGENCE))
                .bounds(leftPos + 238, tabY, 96, 20).build());
        preset = addRenderableWidget(Button.builder(Component.literal("Load Holland 3-2"), button -> requestPreset())
                .bounds(leftPos + 340, tabY, 128, 20).build());
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

        buildEditors(scalarDraft, sideStageDrafts, sideRateDrafts, steamDrafts);
        buildHeatEditors(coolerDrafts);
        if (serverState != null) loadInput(serverState.input());
        restoreCoolerDrafts(coolerDrafts);
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

    private void buildEditors(
            String[] scalarDraft, String[] sideStageDrafts, String[] sideRateDrafts, String[] steamDrafts) {
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
            editor.setResponder(value -> onDraftEdited());
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
            stage.setResponder(value -> onDraftEdited());
            rate.setResponder(value -> onDraftEdited());
            sideDrawFields.add(new SideDrawFields(addRenderableWidget(stage), addRenderableWidget(rate)));
        }
        int steamY = topPos + CONTENT_TOP + 178;
        int sumpRateWidth = Math.max(42, Math.min(72, scalarColumnWidth / 3));
        int temperatureWidth = Math.max(42, Math.min(72, scalarColumnWidth / 3));
        EditBox sumpRate = steamEditor(leftPos + 10, steamY, sumpRateWidth, "Sump steam rate", steamDrafts[0], 20);
        EditBox sumpTemperature = steamEditor(leftPos + 16 + sumpRateWidth, steamY, temperatureWidth,
                "Sump steam temperature", steamDrafts[1], 20);
        int trayX = leftPos + 10 + scalarColumnWidth;
        EditBox trayStage = steamEditor(trayX, steamY, 42, "Tray steam stage", steamDrafts[2], 3);
        EditBox trayRate = steamEditor(trayX + 48, steamY, 72, "Tray steam rate", steamDrafts[3], 20);
        EditBox trayTemperature = steamEditor(trayX + 126, steamY, 72, "Tray steam temperature", steamDrafts[4], 20);
        steamFields = new SteamFields(sumpRate, sumpTemperature, trayStage, trayRate, trayTemperature);
    }

    private EditBox steamEditor(int x, int y, int width, String description, String value, int maximumLength) {
        EditBox editor = new EditBox(font, x, y, width, 20, Component.literal(description));
        editor.setMaxLength(maximumLength);
        editor.setValue(value);
        editor.setResponder(ignored -> onDraftEdited());
        return addRenderableWidget(editor);
    }

    /**
     * Builds the Heat page rows.
     *
     * <p>Order is draw before return because that is the physical direction of the circulating liquid; the
     * duty itself is authored as positive cooling and negated on the way into the science contract.</p>
     */
    private void buildHeatEditors(String[][] coolerDrafts) {
        int splitWidth = Math.max(56, Math.min(92, imageWidth - 10 - HEAT_SPLIT_X));
        for (int index = 0; index < COOLER_COUNT; index++) {
            int rowY = topPos + HEAT_ROW_TOP + index * HEAT_ROW_PITCH;
            EditBox drawTray = heatEditor(leftPos + HEAT_DRAW_X, rowY, 60, "Cooler draw tray", coolerDrafts[index][0], 3);
            EditBox returnTray = heatEditor(leftPos + HEAT_RETURN_X, rowY, 60, "Cooler return tray", coolerDrafts[index][1], 3);
            EditBox cooling = heatEditor(leftPos + HEAT_COOLING_X, rowY, 76, "Cooler duty (MW removed)", coolerDrafts[index][2], 12);
            int row = index;
            Button split = addRenderableWidget(Button.builder(Component.literal(splitLabel(coolerSplits[index])),
                    button -> cycleSplit(row)).bounds(leftPos + HEAT_SPLIT_X, rowY, splitWidth, 20).build());
            coolerFields.add(new CoolerFields(drawTray, returnTray, cooling, split));
        }
    }

    private EditBox heatEditor(int x, int y, int width, String description, String value, int maximumLength) {
        EditBox editor = new EditBox(font, x, y, width, 20, Component.literal(description));
        editor.setMaxLength(maximumLength);
        editor.setValue(value);
        editor.setResponder(ignored -> onDraftEdited());
        return addRenderableWidget(editor);
    }

    private void cycleSplit(int index) {
        V3PumparoundSpec.Split[] splits = V3PumparoundSpec.Split.values();
        coolerSplits[index] = splits[(coolerSplits[index].ordinal() + 1) % splits.length];
        coolerFields.get(index).split().setMessage(Component.literal(splitLabel(coolerSplits[index])));
        onDraftEdited();
    }

    private static String splitLabel(V3PumparoundSpec.Split split) {
        return split == V3PumparoundSpec.Split.UNIFORM ? "Uniform" : "Return tray";
    }

    /** Snapshot of the three cooler rows, used to survive a resize and a Holland preset round trip. */
    private String[][] coolerDrafts() {
        String[][] drafts = new String[COOLER_COUNT][];
        for (int index = 0; index < COOLER_COUNT; index++) {
            drafts[index] = index < coolerFields.size()
                    ? new String[] {coolerFields.get(index).drawTray().getValue(),
                            coolerFields.get(index).returnTray().getValue(),
                            coolerFields.get(index).cooling().getValue()}
                    : new String[] {"", "", ""};
        }
        return drafts;
    }

    private void restoreCoolerDrafts(String[][] drafts) {
        boolean authored = false;
        for (String[] row : drafts) {
            for (String value : row) authored |= !value.isBlank();
        }
        if (!authored) return;
        applyCoolerDrafts(drafts);
    }

    private void applyCoolerDrafts(String[][] drafts) {
        boolean previous = loadingInput;
        loadingInput = true;
        try {
            for (int index = 0; index < coolerFields.size(); index++) {
                coolerFields.get(index).drawTray().setValue(drafts[index][0]);
                coolerFields.get(index).returnTray().setValue(drafts[index][1]);
                coolerFields.get(index).cooling().setValue(drafts[index][2]);
            }
        } finally {
            loadingInput = previous;
        }
    }

    private void onDraftEdited() {
        if (!loadingInput) {
            draftEditedSinceState = true;
            stashedCoolerDrafts = null;
            stashedCoolerSplits = null;
        }
        validateDraft();
    }

    private void applyServerState(net.minecraft.core.BlockPos blockPos, V3State state) {
        if (!menu.blockPos().equals(blockPos) || state.stateRevision() < latestStateRevision) return;
        latestStateRevision = state.stateRevision();
        serverState = state;
        calculationRequested = false;
        loadInput(state.input());
        draftEditedSinceState = false;
        validateDraft();
        refreshControls();
    }

    private void applyRejection(net.minecraft.core.BlockPos blockPos, long clientNonce, String reason) {
        if (!menu.blockPos().equals(blockPos)) return;
        calculationRequested = false;
        validation = "Calculation request was not accepted. Check the calculator and try again.";
        refreshControls();
    }

    private String[] editorDraft() {
        if (coreEditors.size() != CORE_EDITOR_COUNT) return null;
        String[] draft = new String[CORE_EDITOR_COUNT];
        for (int index = 0; index < draft.length; index++) draft[index] = coreEditors.get(index).getValue();
        return draft;
    }

    private void loadInput(V3ColumnInput input) {
        boolean previous = loadingInput;
        loadingInput = true;
        try {
            loadInputFields(input);
        } finally {
            loadingInput = previous;
        }
    }

    private void loadInputFields(V3ColumnInput input) {
        double total = 0.0;
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) total += flow;
        String[] values = {
                compactDraft(total * MOL_PER_SECOND_TO_KMOL_PER_HOUR, 1),
                compactDraft(input.feedTemperatureKelvin() - CELSIUS_TO_KELVIN, 1),
                Integer.toString(input.stageCount()),
                Integer.toString(input.feedStageNumber()),
                compactDraft(specificationValue(input, V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)
                        - CELSIUS_TO_KELVIN, 1),
                compactDraft(specificationValue(input, V3ControlledQuantity.REBOILER_DUTY) / 1_000_000.0, 1),
                compactDraft(specificationValue(input, V3ControlledQuantity.ORGANIC_REFLUX_RATIO), 2),
                compactDraft(input.topPressurePascal() * PASCAL_TO_BAR, 2),
                compactDraft(input.stagePressureDropPascal() / 1_000.0, 2)
        };
        if (coreEditors.size() == CORE_EDITOR_COUNT) {
            for (int index = 0; index < values.length; index++) coreEditors.get(index).setValue(values[index]);
        }
        for (int index = 0; index < sideDrawFields.size(); index++) {
            V3SideDrawSpec draw = index < input.sideDraws().size() ? input.sideDraws().get(index) : null;
            sideDrawFields.get(index).stage().setValue(draw == null ? "" : Integer.toString(draw.trayNumber()));
            sideDrawFields.get(index).rate().setValue(
                    draw == null ? "" : compactDraft(draw.molarFlowMolPerSecond() * 3.6, 1));
        }
        V3SteamFeedSpec sump = input.steamFeeds().stream()
                .filter(feed -> feed.stageNumber() == input.stageCount() + 1).findFirst().orElse(null);
        V3SteamFeedSpec traySteam = input.steamFeeds().stream()
                .filter(feed -> feed.stageNumber() <= input.stageCount()).findFirst().orElse(null);
        if (steamFields != null) {
            steamFields.sumpRate().setValue(sump == null ? ""
                    : compactDraft(sump.molarFlowMolPerSecond() * 3.6, 1));
            steamFields.sumpTemperature().setValue(sump == null ? ""
                    : compactDraft(sump.temperatureKelvin() - CELSIUS_TO_KELVIN, 1));
            steamFields.trayStage().setValue(traySteam == null ? ""
                    : Integer.toString(traySteam.stageNumber()));
            steamFields.trayRate().setValue(traySteam == null ? ""
                    : compactDraft(traySteam.molarFlowMolPerSecond() * 3.6, 1));
            steamFields.trayTemperature().setValue(traySteam == null ? ""
                    : compactDraft(traySteam.temperatureKelvin() - CELSIUS_TO_KELVIN, 1));
        }
        loadCoolers(input);
    }

    /**
     * Mirrors the server-owned pumparound list into the Heat rows.
     *
     * <p>A stash taken when the Holland preset was requested is restored instead whenever the server comes
     * back with a production input that carries no pumparounds, so the round trip does not lose the rows.</p>
     */
    private void loadCoolers(V3ColumnInput input) {
        if (coolerFields.isEmpty()) return;
        if (input.pumparounds().isEmpty() && stashedCoolerDrafts != null
                && !V3HollandExample32.isPackage(input.packageId())) {
            // The server answers one preset with both a reply and a viewer broadcast, so this restore must be
            // idempotent; the stash is dropped by the next authored edit or pumparound-bearing input instead.
            applyCoolerDrafts(stashedCoolerDrafts);
            System.arraycopy(stashedCoolerSplits, 0, coolerSplits, 0, coolerSplits.length);
            refreshSplitLabels();
            return;
        }
        if (!input.pumparounds().isEmpty()) {
            stashedCoolerDrafts = null;
            stashedCoolerSplits = null;
        }
        for (int index = 0; index < coolerFields.size(); index++) {
            V3PumparoundSpec cooler = index < input.pumparounds().size() ? input.pumparounds().get(index) : null;
            coolerFields.get(index).drawTray().setValue(cooler == null ? "" : Integer.toString(cooler.drawTray()));
            coolerFields.get(index).returnTray().setValue(cooler == null ? "" : Integer.toString(cooler.returnTray()));
            coolerFields.get(index).cooling().setValue(cooler == null ? ""
                    : compactDraft(V3PumparoundDraft.dutyWattsToCoolingMegawatts(cooler.dutyWatts()), 3));
            if (cooler != null) coolerSplits[index] = cooler.split();
        }
        refreshSplitLabels();
    }

    private void refreshSplitLabels() {
        for (int index = 0; index < coolerFields.size(); index++) {
            coolerFields.get(index).split().setMessage(Component.literal(splitLabel(coolerSplits[index])));
        }
    }

    private void selectPage(Page target) {
        page = target;
        refreshControls();
    }

    private void refreshControls() {
        boolean showInputs = page == Page.INPUTS;
        boolean showHeat = page == Page.HEAT;
        boolean calculating = calculationRequested
                || serverState != null && serverState.status() == V3Status.CALCULATING;
        boolean holland = isHolland();
        for (EditBox editor : coreEditors) {
            editor.visible = showInputs;
            editor.active = showInputs && !calculating && serverState != null && !holland;
        }
        for (SideDrawFields side : sideDrawFields) {
            side.stage().visible = showInputs;
            side.rate().visible = showInputs;
            side.stage().active = showInputs && !calculating && serverState != null && !holland;
            side.rate().active = showInputs && !calculating && serverState != null && !holland;
        }
        if (steamFields != null) {
            for (EditBox editor : steamFields.editors()) {
                editor.visible = showInputs;
                editor.active = showInputs && !calculating && serverState != null && !holland;
            }
        }
        for (CoolerFields cooler : coolerFields) {
            boolean editable = showHeat && !calculating && serverState != null && !holland;
            for (EditBox editor : cooler.editors()) {
                editor.visible = showHeat;
                editor.active = editable;
            }
            cooler.split().visible = showHeat;
            cooler.split().active = editable;
        }
        inputsTab.active = !showInputs;
        streamsTab.active = page != Page.STREAMS;
        heatTab.active = !showHeat;
        convergenceTab.active = page != Page.CONVERGENCE;
        preset.visible = showInputs;
        preset.active = showInputs && !calculating && serverState != null;
        preset.setMessage(Component.literal(holland ? "Load Tia Juana" : "Load Holland 3-2"));
        run.visible = showInputs || showHeat;
        run.setMessage(Component.literal(holland ? "Run Holland" : "Run V3"));
        run.active = (showInputs || showHeat) && !calculating && draftInput() != null;
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
        validation = "Calculating...";
        refreshControls();
    }

    private void requestPreset() {
        if (serverState == null) return;
        if (!isHolland()) {
            String[][] drafts = coolerDrafts();
            boolean authored = false;
            for (String[] row : drafts) {
                for (String value : row) authored |= !value.isBlank();
            }
            stashedCoolerDrafts = authored ? drafts : null;
            stashedCoolerSplits = authored ? coolerSplits.clone() : null;
        }
        ColumnV3Network.sendPreset(menu.blockPos(), serverState.inputRevision(), !isHolland());
        calculationRequested = true;
        validation = "Loading server-owned V3 preset...";
        refreshControls();
    }

    private void validateDraft() {
        if (serverState == null) {
            validation = "Waiting for server-owned V3 state...";
        } else if (calculationRequested || serverState.status() == V3Status.CALCULATING) {
            validation = "Calculating...";
        } else if (draftInput() == null) {
            validation = draftValidationDetail;
        } else {
            validation = switch (serverState.status()) {
                case SUCCESS -> "Calculation complete — see Streams.";
                case FAILED -> "Last calculation failed — see Convergence.";
                default -> "Ready to calculate.";
            };
        }
        if (run != null && previousStreamPage != null && nextStreamPage != null) refreshControls();
    }

    private V3ColumnInput draftInput() {
        if (serverState == null || coreEditors.size() != CORE_EDITOR_COUNT) return null;
        if (isHolland()) return serverState.input();
        try {
            return V3ColumnInputDraft.assemble(serverState.input(),
                    coreEditors.stream().map(EditBox::getValue).toList(),
                    sideDrawFields.stream()
                            .map(fields -> new V3SideDrawDraft.Row(fields.stage().getValue(), fields.rate().getValue()))
                            .toList(),
                    new V3SteamFeedDraft.Row("", steamFields.sumpRate().getValue(),
                            steamFields.sumpTemperature().getValue()),
                    new V3SteamFeedDraft.Row(steamFields.trayStage().getValue(), steamFields.trayRate().getValue(),
                            steamFields.trayTemperature().getValue()),
                    coolerRows());
        } catch (IllegalArgumentException invalid) {
            draftValidationDetail = invalid.getMessage() == null || invalid.getMessage().isBlank()
                    ? "Invalid scientific draft." : invalid.getMessage();
            return null;
        }
    }

    private List<V3PumparoundDraft.Row> coolerRows() {
        List<V3PumparoundDraft.Row> rows = new ArrayList<>(coolerFields.size());
        for (int index = 0; index < coolerFields.size(); index++) {
            CoolerFields fields = coolerFields.get(index);
            rows.add(new V3PumparoundDraft.Row(fields.drawTray().getValue(), fields.returnTray().getValue(),
                    fields.cooling().getValue(), coolerSplits[index]));
        }
        return rows;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BORDER);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, BACKGROUND);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "Column Calculator V3 (Experimental)", 10, 8, TEXT, false);
        graphics.drawString(font, isHolland()
                ? "Holland (1981) Example 3-2 | independent-oracle-seeded V3 benchmark"
                : "Registered Tia Juana Light PR package | server-authoritative free-water V3", 10, 20, MUTED, false);
        switch (page) {
            case INPUTS -> renderInputs(graphics);
            case STREAMS -> renderStreams(graphics);
            case HEAT -> renderHeat(graphics);
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
        int sumpRateWidth = Math.max(42, Math.min(72, scalarColumnWidth / 3));
        int temperatureWidth = Math.max(42, Math.min(72, scalarColumnWidth / 3));
        int sumpTemperatureX = 16 + sumpRateWidth;
        int trayX = 10 + scalarColumnWidth;
        graphics.drawString(font, "Sump steam: below bottom tray", 10, CONTENT_TOP + 152, NOTICE, false);
        graphics.drawString(font, "Rate (kmol/h)", 10, CONTENT_TOP + 166, MUTED, false);
        graphics.drawString(font, "Temp (°C)", sumpTemperatureX, CONTENT_TOP + 166, MUTED, false);
        graphics.drawString(font, "Tray steam: optional stage injection", trayX, CONTENT_TOP + 152, NOTICE, false);
        graphics.drawString(font, "Stage", trayX, CONTENT_TOP + 166, MUTED, false);
        graphics.drawString(font, "Rate (kmol/h)", trayX + 48, CONTENT_TOP + 166, MUTED, false);
        graphics.drawString(font, "Temp (°C)", trayX + 126, CONTENT_TOP + 166, MUTED, false);
        if (serverState == null) {
            graphics.drawString(font, "Waiting for server V3 calculator state.", 10, CONTENT_TOP + 209, NOTICE, false);
            return;
        }
        V3ColumnInput input = serverState.input();
        graphics.drawString(font, isHolland()
                        ? "Fixed scan-verified input: 11 plates, bubble-point feed, and one 25 lbmol/h liquid draw."
                : "Optional steam: leave rate blank or 0 to keep this dry. Suggested sump: 28.8 kmol/h at 176.9°C.",
                10, CONTENT_TOP + 209, NOTICE, false);
        graphics.drawString(font, isHolland()
                        ? "Uses the independent near-root initializer; the known V3 cold-start failure remains reported."
                : "Tray steam is disabled at 0 kmol/h. Stage 1 is the top tray; stage N is the bottom tray.",
                10, CONTENT_TOP + 223, MUTED, false);
        graphics.drawString(font, "Input revision " + serverState.inputRevision() + " • state " + serverState.stateRevision()
                        + " • V3 assay " + input.assayId(),
                10, CONTENT_TOP + 237, MUTED, false);
        boolean calculating = calculationRequested || serverState.status() == V3Status.CALCULATING;
        int statusColor = calculating ? NOTICE : serverState.status() == V3Status.FAILED ? FAILURE
                : run != null && run.active ? SUCCESS : NOTICE;
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
                    fraction.componentId(), formatPercentage(fraction.moleFraction()), formatPercentage(fraction.massFraction())
            }, false);
        }
    }

    private void renderHeat(GuiGraphics graphics) {
        boolean trayMap = imageWidth >= TRAY_MAP_MINIMUM_PANEL_WIDTH;
        int leftWidth = (trayMap ? TRAY_MAP_X - 10 : imageWidth - 10) - 10;
        List<DraftCooler> coolers = draftCoolers();
        graphics.drawString(font, "Pumparound coolers (duty removed from the column)", 10, CONTENT_TOP, TEXT, false);
        graphics.drawString(font, "#", HEAT_NUMBER_X, CONTENT_TOP + 18, MUTED, false);
        graphics.drawString(font, "Draw tray", HEAT_DRAW_X, CONTENT_TOP + 18, MUTED, false);
        graphics.drawString(font, "Return tray", HEAT_RETURN_X, CONTENT_TOP + 18, MUTED, false);
        graphics.drawString(font, "Cooling (MW)", HEAT_COOLING_X, CONTENT_TOP + 18, MUTED, false);
        graphics.drawString(font, "Split", HEAT_SPLIT_X, CONTENT_TOP + 18, MUTED, false);
        for (int index = 0; index < COOLER_COUNT; index++) {
            graphics.drawString(font, Integer.toString(index + 1), HEAT_NUMBER_X,
                    HEAT_ROW_TOP + index * HEAT_ROW_PITCH + 6, MUTED, false);
        }
        graphics.drawString(font, "Empty draw and return trays disable the row. Cooling is entered positive.",
                10, CONTENT_TOP + 108, MUTED, false);
        List<String> advisories = heatAdvisories(coolers);
        if (!advisories.isEmpty()) {
            String advisory = advisories.size() == 1 ? advisories.getFirst()
                    : advisories.getFirst() + " (+" + (advisories.size() - 1) + " more)";
            graphics.drawString(font, abbreviateToWidth(advisory, leftWidth), 10, CONTENT_TOP + 122, NOTICE, false);
        }
        renderColumnDuties(graphics, leftWidth);
        if (trayMap) renderTrayMap(graphics, coolers);
        renderHeatStatus(graphics, coolers);
    }

    private void renderColumnDuties(GuiGraphics graphics, int leftWidth) {
        int dutiesY = CONTENT_TOP + 138;
        graphics.drawString(font, "Column duties", 10, dutiesY, TEXT, false);
        V3ColumnDisplayResult result = serverState == null ? null : serverState.displayResult().orElse(null);
        if (result != null && draftEditedSinceState) {
            String pill = "Input edited since run";
            int pillX = 10 + font.width("Column duties") + 10;
            graphics.fill(pillX, dutiesY - 2, pillX + font.width(pill) + 8, dutiesY + 10, TABLE_HEADER);
            graphics.drawString(font, pill, pillX + 4, dutiesY, NOTICE, false);
        }
        if (result == null) {
            graphics.drawString(font, "Run the column to see condenser, reboiler and cooler duties.",
                    10, dutiesY + 16, NOTICE, false);
            return;
        }
        if (result.dutyLedger().isEmpty()) {
            graphics.drawString(font, "Duty ledger not available for this result", 10, dutiesY + 16, NOTICE, false);
            return;
        }
        V3ColumnDutyLedger ledger = result.dutyLedger().orElseThrow();
        int boxY = CONTENT_TOP + 154;
        renderDutyBox(graphics, 10, boxY, 118, "Condenser", ledger.condenserWatts());
        renderDutyBox(graphics, 134, boxY, 118, "Reboiler", ledger.reboilerWatts());
        renderDutyBox(graphics, 258, boxY, 118, "Coolers total", ledger.stageHeatTotalWatts());
        graphics.drawString(font, abbreviateToWidth("Feed enthalpy " + megawatts(ledger.feedEnthalpyWatts())
                        + " · steam enthalpy " + megawatts(ledger.steamEnthalpyWatts()), leftWidth),
                10, CONTENT_TOP + 202, MUTED, false);
        renderStageDuties(graphics, ledger.stageDuties(), leftWidth);
    }

    private void renderDutyBox(GuiGraphics graphics, int x, int y, int width, String title, double watts) {
        graphics.fill(x, y, x + width, y + 44, TABLE_ROW);
        graphics.fill(x, y, x + width, y + 1, TABLE_GRID);
        graphics.fill(x, y + 43, x + width, y + 44, TABLE_GRID);
        graphics.drawString(font, title, x + 6, y + 6, MUTED, false);
        graphics.drawString(font, abbreviateToWidth(megawatts(watts), width - 12), x + 6, y + 19, TEXT, false);
        graphics.drawString(font, watts < 0.0 ? "heat removed" : watts > 0.0 ? "heat added" : "no duty",
                x + 6, y + 31, MUTED, false);
    }

    private void renderStageDuties(GuiGraphics graphics, List<V3ColumnDutyLedger.StageDuty> duties, int leftWidth) {
        graphics.drawString(font, "Per tray", 10, CONTENT_TOP + 216, MUTED, false);
        if (duties.isEmpty()) {
            graphics.drawString(font, "No prescribed stage heat in this result", 62, CONTENT_TOP + 216, MUTED, false);
            return;
        }
        int labelWidth = 34;
        int cellWidth = 44;
        int capacity = Math.max(1, (leftWidth - labelWidth) / cellWidth);
        boolean truncated = duties.size() > capacity;
        int shown = truncated ? Math.max(1, capacity - 1) : duties.size();
        int columns = shown + (truncated ? 1 : 0) + 1;
        int[] widths = new int[columns];
        widths[0] = labelWidth;
        for (int index = 1; index < columns; index++) widths[index] = cellWidth;
        String[] trays = new String[columns];
        String[] values = new String[columns];
        trays[0] = "Tray";
        values[0] = "MW";
        for (int index = 0; index < shown; index++) {
            trays[index + 1] = Integer.toString(duties.get(index).trayNumber());
            values[index + 1] = compactDraft(duties.get(index).dutyWatts() / 1_000_000.0, 2);
        }
        if (truncated) {
            trays[columns - 1] = "…";
            values[columns - 1] = "…";
        }
        drawTableRow(graphics, 10, CONTENT_TOP + 228, 12, widths, trays, true);
        drawTableRow(graphics, 10, CONTENT_TOP + 240, 12, widths, values, false);
    }

    private void renderHeatStatus(GuiGraphics graphics, List<DraftCooler> coolers) {
        boolean calculating = calculationRequested
                || serverState != null && serverState.status() == V3Status.CALCULATING;
        boolean valid = !calculating && run != null && run.active;
        String text = valid ? "Draft valid · " + coolers.size() + " coolers active" : validation;
        int color = calculating ? NOTICE : valid ? SUCCESS
                : serverState != null && serverState.status() == V3Status.FAILED ? FAILURE : NOTICE;
        graphics.drawString(font, abbreviate(text, 82), 101, imageHeight - 24, color, false);
    }

    /**
     * Draft-derived column sketch.
     *
     * <p>Every marker comes from the editor text, never from the server result, so the ladder always shows what
     * the Run button would send.</p>
     */
    private void renderTrayMap(GuiGraphics graphics, List<DraftCooler> coolers) {
        int x = TRAY_MAP_X;
        int width = imageWidth - 10 - x;
        graphics.drawString(font, "Tray map", x, CONTENT_TOP, TEXT, false);
        int stages = draftStageCount();
        if (stages < V3ColumnInput.MIN_STAGE_COUNT || stages > V3ColumnInput.MAX_STAGE_COUNT) {
            graphics.drawString(font, abbreviateToWidth("Enter a valid stage count to draw the map", width),
                    x, CONTENT_TOP + 26, MUTED, false);
            return;
        }
        int ladderX = x + 30;
        int top = CONTENT_TOP + 26;
        int bottom = imageHeight - 58;
        int labelX = ladderX + 12 + coolers.size() * 8;
        graphics.fill(ladderX, top, ladderX + 1, bottom + 1, BORDER);
        for (int tray = 5; tray <= stages; tray += 5) {
            int y = trayY(tray, stages, top, bottom);
            graphics.fill(ladderX - 3, y, ladderX + 4, y + 1, TABLE_GRID);
        }
        graphics.drawString(font, "1", x + 14, top - 4, MUTED, false);
        graphics.drawString(font, Integer.toString(stages), x + 14 - font.width(Integer.toString(stages)) + 6,
                bottom - 4, MUTED, false);
        int feedStage = draftFeedStage();
        if (feedStage >= 1 && feedStage <= stages) {
            int y = trayY(feedStage, stages, top, bottom);
            for (int step = 0; step < 4; step++) {
                graphics.fill(ladderX - 12 + step, y - 3 + step, ladderX - 11 + step, y + 4 - step, FEED_MARK);
            }
            graphics.drawString(font, abbreviateToWidth("Feed " + feedStage, x + width - labelX), labelX, y - 4,
                    FEED_MARK, false);
        }
        int[] drawTrays = draftSideDrawTrays();
        for (int index = 0; index < drawTrays.length; index++) {
            if (drawTrays[index] < 1 || drawTrays[index] > stages) continue;
            int y = trayY(drawTrays[index], stages, top, bottom);
            graphics.fill(ladderX - 9, y - 1, ladderX + 1, y + 2, DRAW_MARK);
            graphics.drawString(font, abbreviateToWidth("Draw " + (index + 1) + "  " + drawTrays[index],
                    x + width - labelX), labelX, y - 4, DRAW_MARK, false);
        }
        renderCoolerBars(graphics, coolers, stages, ladderX, top, bottom, labelX, x + width - labelX);
        renderSteamMarks(graphics, stages, ladderX, top, bottom, labelX, x + width - labelX);
        graphics.drawString(font, abbreviateToWidth("Blue = cooler span, teal = draw,", width), x,
                imageHeight - 44, MUTED, false);
        graphics.drawString(font, abbreviateToWidth("coral = feed, amber = steam", width), x,
                imageHeight - 32, MUTED, false);
    }

    private void renderCoolerBars(
            GuiGraphics graphics, List<DraftCooler> coolers, int stages, int ladderX, int top, int bottom,
            int labelX, int labelWidth) {
        boolean ledger = serverState != null && serverState.displayResult().isPresent()
                && serverState.displayResult().orElseThrow().dutyLedger().isPresent() && !draftEditedSinceState;
        for (int index = 0; index < coolers.size(); index++) {
            DraftCooler cooler = coolers.get(index);
            if (cooler.returnTray() > stages || cooler.drawTray() > stages) continue;
            int laneX = ladderX + 6 + index * 8;
            int thickness = 2 + dutyRank(coolers, cooler);
            int fromY = trayY(Math.min(cooler.returnTray(), cooler.drawTray()), stages, top, bottom);
            int toY = trayY(Math.max(cooler.returnTray(), cooler.drawTray()), stages, top, bottom);
            graphics.fill(laneX, fromY, laneX + thickness, toY + 1, COOLER_MARK);
            graphics.fill(laneX - 2, fromY, laneX + thickness + 2, fromY + 1, COOLER_MARK);
            graphics.fill(laneX - 2, toY, laneX + thickness + 2, toY + 1, COOLER_MARK);
            String label = ledger
                    ? "PA" + cooler.number() + "  " + megawatts(cooler.dutyWatts())
                    : "PA" + cooler.number() + "  " + cooler.drawTray() + "–" + cooler.returnTray();
            graphics.drawString(font, abbreviateToWidth(label, labelWidth), labelX, (fromY + toY) / 2 - 4,
                    COOLER_MARK, false);
        }
    }

    private void renderSteamMarks(
            GuiGraphics graphics, int stages, int ladderX, int top, int bottom, int labelX, int labelWidth) {
        if (steamFields == null) return;
        if (optionalNumber(steamFields.sumpRate().getValue()) > 0.0) {
            graphics.fill(ladderX - 9, bottom + 7, ladderX + 10, bottom + 9, STEAM_MARK);
            graphics.drawString(font, abbreviateToWidth("Steam  sump", labelWidth), labelX, bottom + 4,
                    STEAM_MARK, false);
        }
        int traySteamStage = optionalInteger(steamFields.trayStage().getValue());
        if (optionalNumber(steamFields.trayRate().getValue()) > 0.0
                && traySteamStage >= 1 && traySteamStage <= stages) {
            int y = trayY(traySteamStage, stages, top, bottom);
            graphics.fill(ladderX - 9, y - 1, ladderX + 1, y + 2, STEAM_MARK);
            graphics.drawString(font, abbreviateToWidth("Steam  " + traySteamStage, labelWidth), labelX, y - 4,
                    STEAM_MARK, false);
        }
    }

    /** Zero for the smallest authored duty, two for the largest; ties keep row order. */
    private static int dutyRank(List<DraftCooler> coolers, DraftCooler cooler) {
        int rank = 0;
        for (DraftCooler other : coolers) {
            double magnitude = Math.abs(other.dutyWatts());
            double reference = Math.abs(cooler.dutyWatts());
            if (magnitude < reference || magnitude == reference && other.number() < cooler.number()) rank++;
        }
        return Math.min(2, rank);
    }

    private static int trayY(int tray, int stages, int top, int bottom) {
        if (stages <= 1) return top;
        return top + (int) Math.round((tray - 1) * (double) (bottom - top) / (stages - 1));
    }

    private List<String> heatAdvisories(List<DraftCooler> coolers) {
        List<String> advisories = new ArrayList<>();
        int feedStage = draftFeedStage();
        int[] drawTrays = draftSideDrawTrays();
        for (DraftCooler cooler : coolers) {
            if (cooler.dutyWatts() > 0.0) {
                advisories.add("Pumparound " + cooler.number() + " carries a heater duty (set on server)");
            }
            for (int index = 0; index < drawTrays.length; index++) {
                if (drawTrays[index] == cooler.drawTray()) {
                    advisories.add("Pumparound " + cooler.number() + " draws tray " + cooler.drawTray()
                            + " which also carries side draw " + (index + 1));
                }
                if (drawTrays[index] == cooler.returnTray()) {
                    advisories.add("Pumparound " + cooler.number() + " returns to tray " + cooler.returnTray()
                            + " which also carries side draw " + (index + 1));
                }
            }
            if (cooler.drawTray() == feedStage) {
                advisories.add("Pumparound " + cooler.number() + " draws tray " + feedStage + " which is the feed stage");
            }
            if (cooler.returnTray() == feedStage) {
                advisories.add("Pumparound " + cooler.number() + " returns to tray " + feedStage
                        + " which is the feed stage");
            }
        }
        return advisories;
    }

    /** Leniently parsed rows for drawing and advisories; a negative cooling stays as a positive heater duty. */
    private List<DraftCooler> draftCoolers() {
        List<DraftCooler> coolers = new ArrayList<>(coolerFields.size());
        for (int index = 0; index < coolerFields.size(); index++) {
            CoolerFields fields = coolerFields.get(index);
            int drawTray = optionalInteger(fields.drawTray().getValue());
            int returnTray = optionalInteger(fields.returnTray().getValue());
            double cooling = optionalNumber(fields.cooling().getValue());
            if (drawTray < 1 || returnTray < 1 || !Double.isFinite(cooling) || cooling == 0.0) continue;
            coolers.add(new DraftCooler(index + 1, drawTray, returnTray,
                    V3PumparoundDraft.coolingMegawattsToDutyWatts(cooling), coolerSplits[index]));
        }
        return coolers;
    }

    private int draftStageCount() {
        return coreEditors.size() == CORE_EDITOR_COUNT ? optionalInteger(coreEditors.get(2).getValue()) : -1;
    }

    private int draftFeedStage() {
        return coreEditors.size() == CORE_EDITOR_COUNT ? optionalInteger(coreEditors.get(3).getValue()) : -1;
    }

    private int[] draftSideDrawTrays() {
        int[] trays = new int[sideDrawFields.size()];
        for (int index = 0; index < trays.length; index++) {
            double rate = optionalNumber(sideDrawFields.get(index).rate().getValue());
            trays[index] = !Double.isFinite(rate) || rate == 0.0 ? -1
                    : optionalInteger(sideDrawFields.get(index).stage().getValue());
        }
        return trays;
    }

    private static int optionalInteger(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException | NullPointerException invalid) {
            return -1;
        }
    }

    private static double optionalNumber(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException | NullPointerException invalid) {
            return Double.NaN;
        }
    }

    private static String megawatts(double watts) {
        return Double.isFinite(watts) ? compactDraft(watts / 1_000_000.0, 2) + " MW" : "-- MW";
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
            graphics.drawString(font, "Coolers: " + serverState.input().pumparounds().size() + " requested · stage heat total "
                            + megawatts(result.dutyLedger().map(V3ColumnDutyLedger::stageHeatTotalWatts)
                                    .orElseGet(() -> serverState.input().pumparounds().stream()
                                            .mapToDouble(V3PumparoundSpec::dutyWatts).sum())),
                    10, CONTENT_TOP + 128, MUTED, false);
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

    private boolean isHolland() {
        return serverState != null && V3HollandExample32.isPackage(serverState.input().packageId());
    }

    private static String compactDraft(double value, int decimalPlaces) {
        if (decimalPlaces < 0 || decimalPlaces > 3) throw new IllegalArgumentException("Invalid V3 GUI decimal precision");
        return String.format(Locale.ROOT, "%." + decimalPlaces + "f", value)
                .replaceFirst("0+$", "").replaceFirst("\\.$", "");
    }

    private static String compact(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.4g", value) : "--";
    }

    /** Fixed-width composition cells deliberately avoid exponent notation and clipped trailing digits. */
    private static String formatPercentage(double fraction) {
        return Double.isFinite(fraction) ? String.format(Locale.ROOT, "%.3f", 100.0 * fraction) : "--";
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
        HEAT,
        CONVERGENCE
    }

    private record SideDrawFields(EditBox stage, EditBox rate) {
    }

    private record CoolerFields(EditBox drawTray, EditBox returnTray, EditBox cooling, Button split) {
        List<EditBox> editors() {
            return List.of(drawTray, returnTray, cooling);
        }
    }

    /** One leniently parsed Heat row; the duty is already signed, so a heater is positive. */
    private record DraftCooler(
            int number, int drawTray, int returnTray, double dutyWatts, V3PumparoundSpec.Split split) {
    }

    private record SteamFields(
            EditBox sumpRate, EditBox sumpTemperature, EditBox trayStage, EditBox trayRate, EditBox trayTemperature) {
        List<EditBox> editors() {
            return List.of(sumpRate, sumpTemperature, trayStage, trayRate, trayTemperature);
        }
    }
}
