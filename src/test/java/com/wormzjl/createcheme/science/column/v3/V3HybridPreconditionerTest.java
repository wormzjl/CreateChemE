package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class V3HybridPreconditionerTest {
    @Test
    void ordinaryVolatilityTriesBubblePointBeforeTheEnergyTemperaturePath() {
        V3HybridPreconditioner.Selection selection = V3HybridPreconditioner.select(Math.log(1_000.0));

        assertEquals(Math.log(1_000.0), selection.logKSpread());
        assertIterableEquals(List.of(
                V3SequentialPreconditioner.Id.BUBBLE_POINT,
                V3SequentialPreconditioner.Id.SUM_RATES), selection.order());
    }

    @Test
    void wideVolatilityStartsWithSumRatesWithoutTreatingPressureAsTheSelector() {
        V3HybridPreconditioner.Selection selection = V3HybridPreconditioner.select(Math.log(10_001.0));

        assertIterableEquals(List.of(V3SequentialPreconditioner.Id.SUM_RATES), selection.order());
    }
}
