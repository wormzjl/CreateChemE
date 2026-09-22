package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.material.SolidMaterialCatalog;

/** Validate only owned/referenced solid materials, never an unrelated added definition. */
final class SolidCompatibility {
    private SolidCompatibility() {}
    static void validate(SolidMaterialCatalog catalog,FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world) {
        for(var island:checkpoint.islands()) {
            for(var node:island.snapshot().graph().reservoirs())catalog.validate(node.inventory().solids());
            for(var pipe:island.snapshot().graph().pipes())if(pipe.filter()!=null)catalog.validate(pipe.filter().captured());
        }
        for(var pending:checkpoint.transfers().pending().values())catalog.validate(pending.remaining().solids());
        for(var module:checkpoint.modules())if(module.cycle()!=null)for(var input:module.cycle().inputs().values())catalog.validate(input.owned().solids());
        for(var recovery:world.recoveries().values())catalog.validate(recovery.solids());
    }
}