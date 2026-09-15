package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;

/** Migrates package-local legacy aliases and reorders amounts by identity, never by guessed position. */
public final class V3MaterialInputs {
    private V3MaterialInputs() {}
    public static V3ColumnInput migrate(V3ColumnInput input, MaterialCatalog catalog) {
        if(V3HollandExample32.isPackage(input.packageId()))return input;
        var p=catalog.requirePackage(input.packageId());
        var old=input.componentBasis().componentIds(); double[] values=input.feedComponentMolarFlowsMolPerSecond();
        var amounts=new HashMap<String,Double>();
        for(int i=0;i<old.size();i++) {
            String id=p.canonicalId(old.get(i));
            if(!p.components().contains(id) || amounts.putIfAbsent(id,values[i])!=null)
                throw new IllegalArgumentException("Ambiguous or unavailable saved component: "+old.get(i));
        }
        if(amounts.size()!=p.components().size())throw new IllegalArgumentException("Saved composition does not match package components");
        if(old.equals(p.components()))return input;
        double[] flows=p.components().stream().mapToDouble(amounts::get).toArray();
        return new V3ColumnInput(input.schemaVersion(),input.packageId(),input.assayId(),new V3ComponentBasis(p.components()),flows,
                input.feedTemperatureKelvin(),input.stageCount(),input.feedStageNumber(),input.topPressurePascal(),
                input.stagePressureDropPascal(),input.specifications(),input.sideDraws(),input.steamFeeds(),input.pumparounds());
    }
}
