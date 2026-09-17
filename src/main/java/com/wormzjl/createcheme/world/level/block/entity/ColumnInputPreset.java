package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.science.material.*;
import java.util.*;

/** Stable choice identity; every production operating definition comes from the captured catalog. */
public final class ColumnInputPreset {
    public static final ColumnInputPreset TIA_JUANA=new ColumnInputPreset("tia_juana"),WTI=new ColumnInputPreset("wti_light_export"),
            UPPER_ZAKUM=new ColumnInputPreset("upper_zakum"),BONGA=new ColumnInputPreset("bonga"),DALIA=new ColumnInputPreset("dalia"),
            COLD_LAKE=new ColumnInputPreset("cold_lake_blend"),HOLLAND=new ColumnInputPreset("holland_3_2");
    private static final Map<String,ColumnInputPreset> KNOWN=Map.of(TIA_JUANA.id,TIA_JUANA,WTI.id,WTI,UPPER_ZAKUM.id,UPPER_ZAKUM,
            BONGA.id,BONGA,DALIA.id,DALIA,COLD_LAKE.id,COLD_LAKE,HOLLAND.id,HOLLAND);
    private final String id;
    private ColumnInputPreset(String id){this.id=id;}
    public String id(){return id;}
    public String label(){return descriptor(MaterialRuntime.current()).label();}
    public String translationKey(){return descriptor(MaterialRuntime.current()).translationKey();}
    public MaterialPresets.Descriptor descriptor(MaterialCatalog catalog) {
        if(this==HOLLAND) {var input=V3HollandExample32.input();return new MaterialPresets.Descriptor(id,"Holland Example 3-2","gui.createcheme.column_preset."+id,input.packageId(),input.assayId());}
        return catalog.presets().column(id).descriptor();
    }
    public boolean matches(V3ColumnInput input){return this==HOLLAND?V3HollandExample32.isPackage(input.packageId()):descriptor(MaterialRuntime.current()).matches(input);}
    public static List<MaterialPresets.Descriptor> descriptors(MaterialCatalog catalog) {
        var values=new ArrayList<>(catalog.presets().visibleColumns());values.add(HOLLAND.descriptor(catalog));return List.copyOf(values);
    }
    public static ColumnInputPreset[] values(){return descriptors(MaterialRuntime.current()).stream().map(d->fromId(d.id())).toArray(ColumnInputPreset[]::new);}
    public static ColumnInputPreset fromId(String id){return fromId(MaterialRuntime.current(),id);}
    public static ColumnInputPreset fromId(MaterialCatalog catalog,String id) {
        if(id.equals(HOLLAND.id))return HOLLAND;
        if(!catalog.presets().column(id).visible())throw new IllegalArgumentException("Hidden column input preset: "+id);
        return KNOWN.getOrDefault(id,new ColumnInputPreset(id));
    }
    public V3ColumnInput input(MaterialCatalog catalog){return this==HOLLAND?V3HollandExample32.input():catalog.presets().column(id).input(catalog);}
    @Override public boolean equals(Object other){return other instanceof ColumnInputPreset p&&id.equals(p.id);}
    @Override public int hashCode(){return id.hashCode();}
}
