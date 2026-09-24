package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialPresets;
import java.util.*;

/** Immutable, server-authored templates and molecular weights for the column editor, never client property data. */
public record V3EditorCatalog(Map<String,V3ColumnInput> templates, Map<String,List<Double>> molecularWeights) {
    public static final int MAX_TEMPLATES=MaterialPresets.MAX_PRESETS+1, MAX_PACKAGES=MAX_TEMPLATES+1;
    public static final V3EditorCatalog EMPTY = new V3EditorCatalog(Map.of(),Map.of());
    public V3EditorCatalog {
        templates=Map.copyOf(templates);
        var copied=new LinkedHashMap<String,List<Double>>();
        molecularWeights.forEach((key,values)->{
            if(key==null||key.isBlank()||key.length()>128||values.isEmpty()||values.size()>64)
                throw new IllegalArgumentException("Invalid editor molecular-weight axis");
            for(double value:values)if(!Double.isFinite(value)||value<=0)
                throw new IllegalArgumentException("Invalid editor molecular weight");
            copied.put(key,List.copyOf(values));
        });
        molecularWeights=Map.copyOf(copied);
        if(templates.size()>MAX_TEMPLATES||molecularWeights.size()>MAX_PACKAGES)
            throw new IllegalArgumentException("Editor catalogue exceeds bounds");
        for(var entry:templates.entrySet()){
            if(!entry.getKey().matches("[a-z][a-z0-9_.:-]{0,127}"))
                throw new IllegalArgumentException("Invalid editor template ID");
            var weights=molecularWeights.get(entry.getValue().packageId());
            if(weights==null||weights.size()!=entry.getValue().componentBasis().componentCount())
                throw new IllegalArgumentException("Template molecular-weight axis differs");
        }
    }
    public static V3EditorCatalog from(MaterialCatalog catalog,V3ColumnInput current) {
        Map<String,V3ColumnInput> templates=new LinkedHashMap<>();
        Map<String,List<Double>> weights=new LinkedHashMap<>();
        for(var descriptor:catalog.presets().visibleColumns()){
            var input=catalog.presets().column(descriptor.id()).input(catalog);
            templates.put(descriptor.id(),input);
            weights.put(input.packageId(),weights(catalog,input));
        }
        templates.put("holland_3_2",V3HollandExample32.input());
        weights.put(V3HollandExample32.PACKAGE_ID,Arrays.stream(V3HollandExample32.molecularWeights()).boxed().toList());
        weights.put(current.packageId(),weights(catalog,current));
        return new V3EditorCatalog(templates,weights);
    }
    private static List<Double> weights(MaterialCatalog catalog,V3ColumnInput input) {
        if(V3HollandExample32.isPackage(input.packageId()))return Arrays.stream(V3HollandExample32.molecularWeights()).boxed().toList();
        return catalog.requirePackage(input.packageId()).properties().stream().map(MaterialCatalog.Property::molecularWeight).toList();
    }
    public List<Double> weightsFor(V3ColumnInput input) {
        var values=molecularWeights.get(input.packageId());
        if(values==null||values.size()!=input.componentBasis().componentCount())
            throw new IllegalArgumentException("Waiting for molecular-weight data");
        return values;
    }
}
