package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ExactResultCacheTest {
    @Test
    void accessOrderAndEntryLimitAreDeterministic() {
        ExactResultCache<Integer, Integer> cache = new ExactResultCache<>(value -> 1L);
        for (int value = 0; value < 128; value++) cache.putCommittedSuccess(value, value);
        assertEquals(0, cache.get(0));
        cache.putCommittedSuccess(128, 128);
        assertNull(cache.get(1));
        assertEquals(0, cache.get(0));
        assertEquals(128, cache.size());
    }

    @Test
    void byteCapEvictsLeastRecentlyUsedEntryAsWellAsEntryCap() {
        ExactResultCache<Integer, Integer> cache = new ExactResultCache<>(value -> ExactResultCache.MAX_BYTES / 2L + 1L);

        cache.putCommittedSuccess(1, 1);
        cache.putCommittedSuccess(2, 2);

        assertNull(cache.get(1));
        assertEquals(2, cache.get(2));
        assertEquals(1, cache.size());
        assertEquals(ExactResultCache.MAX_BYTES / 2L + 1L, cache.bytes());
    }
}
