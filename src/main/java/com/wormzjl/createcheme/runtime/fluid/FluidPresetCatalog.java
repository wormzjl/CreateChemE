package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.material.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.*;

/** Required crude presets on one verified shared TJL20 component/property basis. */
public final class FluidPresetCatalog {
    public static final String NETWORK_PACKAGE="createcheme:tjl20_methane";
    private FluidPresetCatalog() {}
    public record Preset(String id,String name,double[] moleFractions) {
        public Preset {Objects.requireNonNull(id);Objects.requireNonNull(name);moleFractions=moleFractions.clone();}
        @Override public double[] moleFractions(){return moleFractions.clone();}
    }
    public static List<Preset> resolve(MaterialCatalog catalog) {
        var target=catalog.requirePackage(NETWORK_PACKAGE);var result=new ArrayList<Preset>();
        result.add(new Preset("water","Water",FluidDeviceSpec.water().composition()));result.add(new Preset("nitrogen","Nitrogen",FluidDeviceSpec.nitrogen().composition()));
        var packages=List.of(NETWORK_PACKAGE,"createcheme:wti_light_export_tjl20","createcheme:cold_lake_blend_tjl20");var names=List.of("Tia Juana Light","WTI Light Export","Cold Lake Blend");
        for(int i=0;i<packages.size();i++) {
            String id=packages.get(i);var source=catalog.requirePackage(id);
            if(!source.components().equals(target.components())||!source.properties().equals(target.properties())||!source.interactions().equals(target.interactions())||!source.water().equals(target.water())||!catalog.viscosityFingerprint(id).equals(catalog.viscosityFingerprint(NETWORK_PACKAGE)))throw new IllegalArgumentException("Preset no longer shares the qualified network property basis: "+id);
            String assay=new TreeSet<>(source.assays().keySet()).first();
            double[] n=MaterialRuntime.with(catalog,id,()->V3PengRobinsonThermo.fromRegisteredPackage(id).crudeFeed(assay).moleFractions());
            result.add(new Preset(assay,names.get(i),Arrays.copyOf(n,22)));
        }
        return List.copyOf(result);
    }
}
