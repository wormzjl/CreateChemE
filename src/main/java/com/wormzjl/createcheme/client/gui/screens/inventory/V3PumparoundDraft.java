package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3PumparoundSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure pumparound-row parsing kept separate from widgets so the sign convention stays testable.
 *
 * <p>The editor authors a cooler as a <em>positive</em> number of megawatts removed, while the science contract
 * stores a signed watt duty that is positive when heat is added to the column. Cooling therefore crosses this
 * boundary as a negation, and this class is the only place that performs it.</p>
 */
final class V3PumparoundDraft {
    static final double MEGAWATT_TO_WATT = 1_000_000.0;

    private V3PumparoundDraft() {}

    /** Player-authored cooling in MW to the signed science duty in W; cooling is always negative. */
    static double coolingMegawattsToDutyWatts(double coolingMegawatts) {
        return -coolingMegawatts * MEGAWATT_TO_WATT;
    }

    /** Signed science duty in W back to displayed cooling in MW; a server-authored heater shows negative. */
    static double dutyWattsToCoolingMegawatts(double dutyWatts) {
        return -dutyWatts / MEGAWATT_TO_WATT;
    }

    /**
     * Parses the editor rows in display order.
     *
     * <p>A row whose draw and return tray are both blank is switched off and contributes no specification;
     * every other row must be complete, in range, and cooling-signed.</p>
     */
    static List<V3PumparoundSpec> parse(List<Row> rows, int stageCount) {
        if (rows.size() > V3ColumnInput.MAX_PUMPAROUNDS) {
            throw new IllegalArgumentException("At most " + V3ColumnInput.MAX_PUMPAROUNDS + " coolers are supported");
        }
        List<V3PumparoundSpec> coolers = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            V3PumparoundSpec cooler = parseRow(rows.get(index), index + 1, stageCount);
            if (cooler == null) continue;
            for (V3PumparoundSpec existing : coolers) {
                if (existing.returnTray() == cooler.returnTray() && existing.drawTray() == cooler.drawTray()) {
                    throw invalid(index + 1, "repeats an existing draw and return tray pair");
                }
            }
            coolers.add(cooler);
        }
        return List.copyOf(coolers);
    }

    private static V3PumparoundSpec parseRow(Row row, int number, int stageCount) {
        String draw = row.drawTray().trim();
        String returned = row.returnTray().trim();
        String cooling = row.coolingMegawatts().trim();
        if (draw.isBlank() && returned.isBlank()) return null;
        if (draw.isBlank() || returned.isBlank()) throw invalid(number, "needs both a draw tray and a return tray");
        int drawTray = integer(draw, number, "draw tray");
        int returnTray = integer(returned, number, "return tray");
        if (returnTray < 1 || drawTray > stageCount) throw invalid(number, "must stay within trays 1.." + stageCount);
        if (returnTray > drawTray) throw invalid(number, "must return above the tray it draws from");
        if (cooling.isBlank()) throw invalid(number, "needs a cooling duty in MW");
        double megawatts;
        try {
            megawatts = Double.parseDouble(cooling);
        } catch (NumberFormatException notANumber) {
            throw invalid(number, "cooling must be a number of MW");
        }
        if (!Double.isFinite(megawatts) || megawatts <= 0.0) {
            throw invalid(number, "cooling must be a positive number of MW");
        }
        return new V3PumparoundSpec(returnTray, drawTray, coolingMegawattsToDutyWatts(megawatts), row.split());
    }

    private static int integer(String value, int number, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException notAnInteger) {
            throw invalid(number, field + " must be a whole tray number");
        }
    }

    private static IllegalArgumentException invalid(int number, String reason) {
        return new IllegalArgumentException("Cooler " + number + " " + reason);
    }

    record Row(String drawTray, String returnTray, String coolingMegawatts, V3PumparoundSpec.Split split) {}
}
