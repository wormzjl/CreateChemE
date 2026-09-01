package com.wormzjl.createcheme.science.column.v3;

/** Free-water outcome at the condenser, independent of the hydrocarbon condenser phase branch. */
enum V3WaterCondenserRegime {
    /** No stripping-steam feed is authored. */
    NONE,
    /** A separate pure-water boot exists; any remaining water joins the hydrocarbon vapor as a slip. */
    FREE_WATER,
    /** Water saturation pressure exceeds the drum pressure, so all stripping steam remains in the mixed vapor. */
    ALL_VAPOR
}
