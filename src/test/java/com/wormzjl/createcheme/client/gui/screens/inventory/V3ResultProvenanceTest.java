package com.wormzjl.createcheme.client.gui.screens.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Status;
import org.junit.jupiter.api.Test;

/**
 * Provenance of the result the calculator keeps on screen.
 *
 * <p>The block entity retains the last accepted result while a new request is calculating, after a failed
 * request, and across a save and reload. The Heat page used to read the client's own "the draft was edited"
 * flag as proof that the retained result matched the draft, but that flag is cleared by every state the server
 * sends — including the acknowledgement of the request that failed.</p>
 */
class V3ResultProvenanceTest {
    @Test
    void onlyAnAcceptedResultOnAnUneditedDraftBelongsToTheInputOnScreen() {
        assertTrue(V3ResultProvenance.retainedResultMatchesInput(V3Status.SUCCESS, true, false));
        assertFalse(V3ResultProvenance.retainedResultMatchesInput(V3Status.SUCCESS, true, true));
        assertFalse(V3ResultProvenance.retainedResultMatchesInput(V3Status.SUCCESS, false, false));
        for (V3Status status : V3Status.values()) {
            if (status == V3Status.SUCCESS) continue;
            assertFalse(V3ResultProvenance.retainedResultMatchesInput(status, true, false), status::name);
        }
        assertFalse(V3ResultProvenance.retainedResultMatchesInput(null, true, false));
    }

    /**
     * The failed-rerun sequence the fix is about: run A succeeds, the operator edits the cooler duties, run B
     * fails, and the server's acknowledgement of B clears the edit flag while A's result stays on the page.
     */
    @Test
    void aFailedRerunKeepsAStaleResultMarkedEvenOnceTheServerAcknowledgesIt() {
        assertNull(V3ResultProvenance.provenancePill(V3Status.SUCCESS, true, false));
        assertEquals(V3ResultProvenance.EDITED_PILL,
                V3ResultProvenance.provenancePill(V3Status.SUCCESS, true, true));
        assertEquals(V3ResultProvenance.RETAINED_PILL,
                V3ResultProvenance.provenancePill(V3Status.FAILED, true, false));
        assertEquals(V3ResultProvenance.EDITED_PILL,
                V3ResultProvenance.provenancePill(V3Status.FAILED, true, true));
        assertEquals(V3ResultProvenance.RETAINED_PILL,
                V3ResultProvenance.provenancePill(V3Status.CALCULATING, true, false));
        // Reopening a calculator whose last run failed persists the earlier input's result as STALE.
        assertEquals(V3ResultProvenance.RETAINED_PILL,
                V3ResultProvenance.provenancePill(V3Status.STALE, true, false));
    }

    @Test
    void aPageWithoutAResultCarriesNoPillAtAll() {
        for (V3Status status : V3Status.values()) {
            assertNull(V3ResultProvenance.provenancePill(status, false, false), status::name);
            assertNull(V3ResultProvenance.provenancePill(status, false, true), status::name);
        }
        assertNull(V3ResultProvenance.provenancePill(null, false, true));
    }
}
