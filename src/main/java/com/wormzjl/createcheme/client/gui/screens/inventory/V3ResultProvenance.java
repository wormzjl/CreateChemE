package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Status;

/**
 * Whether the display result a calculator is still showing was accepted for the input now on screen.
 *
 * <p>The block entity deliberately keeps the last accepted result while a new request is calculating, after a
 * failed request, and across a save and reload, so a present result is not by itself evidence that it belongs
 * to the current input. The client's own "the draft was edited" flag cannot answer this either: it is cleared
 * on every state the server sends, including the acknowledgement of the request that failed.</p>
 *
 * <p>The input and result revisions are independent counters and are never compared numerically; only the
 * status says whether the retained certificate is the current input's.</p>
 */
final class V3ResultProvenance {
    /** Shown when the operator has typed something the server has not been asked about yet. */
    static final String EDITED_PILL = "Input edited since run";
    /** Shown when the result on the page was accepted for some earlier input, not the one on screen. */
    static final String RETAINED_PILL = "Retained result from an earlier run";

    private V3ResultProvenance() {}

    /**
     * True only when the retained result is the accepted certificate of the exact input the page shows.
     *
     * <p>{@code SUCCESS} is the only status the block entity leaves behind when the result and the current
     * input were produced together. {@code CALCULATING} and {@code FAILED} retain the previous input's result,
     * and {@code STALE} is a persisted presentation-only result that may have outlived a failed rerun.</p>
     */
    static boolean retainedResultMatchesInput(V3Status status, boolean resultPresent, boolean draftEdited) {
        return resultPresent && !draftEdited && status == V3Status.SUCCESS;
    }

    /** Provenance pill for a page that shows a result, or {@code null} when the result is the current one. */
    static String provenancePill(V3Status status, boolean resultPresent, boolean draftEdited) {
        if (!resultPresent) return null;
        if (draftEdited) return EDITED_PILL;
        return retainedResultMatchesInput(status, true, false) ? null : RETAINED_PILL;
    }
}
