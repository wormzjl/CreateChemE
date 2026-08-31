package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3SideDrawSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure draft parsing kept separate from widgets so disabled-row and unit semantics are testable. */
final class V3SideDrawDraft {
    private V3SideDrawDraft() {}

    static List<V3SideDrawSpec> parse(List<Row> rows, int stageCount, double feedMolPerSecond) {
        if (rows.size() > V3ColumnInput.MAX_SIDE_DRAWS) throw new IllegalArgumentException("Too many side draw rows");
        List<V3SideDrawSpec> draws = new ArrayList<>();
        Set<Integer> trays = new HashSet<>();
        double total = 0.0;
        for (Row row : rows) {
            if (row.rateKmolPerHour().isBlank()) continue;
            double rate = Double.parseDouble(row.rateKmolPerHour().trim()) / 3.6;
            if (rate == 0.0) continue;
            int tray = Integer.parseInt(row.tray().trim());
            if (tray > stageCount || !trays.add(tray)) throw new IllegalArgumentException("Draw trays must be distinct and in range");
            draws.add(new V3SideDrawSpec(tray, rate));
            total += rate;
        }
        if (!Double.isFinite(feedMolPerSecond) || feedMolPerSecond <= 0 || total >= feedMolPerSecond) {
            throw new IllegalArgumentException("Total side draws must be below the feed rate");
        }
        draws.sort(Comparator.comparingInt(V3SideDrawSpec::trayNumber));
        return List.copyOf(draws);
    }

    record Row(String tray, String rateKmolPerHour) {}
}
