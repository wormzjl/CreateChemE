package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import java.util.*;

/** Owned recovery awaiting delivery to a player or a loaded world chunk. */
public record RecoveredSolid(PhysicalFluidTopology.Position position,UUID player,SolidInventory solids,double energyJoule) {
    public RecoveredSolid {
        Objects.requireNonNull(position);Objects.requireNonNull(solids);
        if(solids.empty()||!Double.isFinite(energyJoule))throw new IllegalArgumentException("Empty/invalid solid recovery");
    }
}