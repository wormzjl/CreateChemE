package com.wormzjl.createcheme.science.material;

import com.google.gson.*;
import java.util.*;

/** Explicit shared-basis references resolve to whole ordered records before scientific validation. */
final class MaterialAuthoring {
    private record Basis(List<String> components,List<String> properties) {}
    private MaterialAuthoring() {}
    static void expand(Map<String,Map<String,JsonObject>> groups,Map<JsonObject,String> origins) {
        var bases=groups.getOrDefault("bases",Map.of());var resolved=new HashMap<String,Basis>();
        for(String id:bases.keySet())resolve(id,bases,resolved,new LinkedHashSet<>(),origins);
        for(var entry:resolved.entrySet()) {
            var axis=entry.getValue();var row=bases.get(entry.getKey());
            for(int i=0;i<axis.components().size();i++) {
                String component=axis.components().get(i),property=axis.properties().get(i);
                var definition=groups.getOrDefault("properties",Map.of()).get(property);
                if(!groups.getOrDefault("components",Map.of()).containsKey(component)||definition==null
                        ||!definition.has("component")||!component.equals(definition.get("component").getAsString()))
                    throw error(origins,row,"basis.properties: missing or mismatched component/property "+component+" / "+property);
            }
        }
        for(var p:groups.getOrDefault("packages",Map.of()).values())if(p.has("basis")) {
            if(p.has("components")||p.has("properties"))throw error(origins,p,"basis: cannot combine a reference with inline components/properties");
            var basis=resolved.get(p.get("basis").getAsString());
            if(basis==null)throw error(origins,p,"basis: missing reference "+p.get("basis"));
            p.add("components",new Gson().toJsonTree(basis.components()));p.add("properties",new Gson().toJsonTree(basis.properties()));
        }
    }
    private static Basis resolve(String id,Map<String,JsonObject> source,Map<String,Basis> resolved,
            Set<String> visiting,Map<JsonObject,String> origins) {
        if(resolved.containsKey(id))return resolved.get(id);
        var row=source.get(id);if(row==null)throw new IllegalArgumentException("basis.extends: missing reference "+id);
        if(!visiting.add(id))throw error(origins,row,"basis.extends: cycle "+visiting+" -> "+id);
        var components=new ArrayList<String>();var properties=new ArrayList<String>();
        try {
            if(row.has("extends")) {
                var parent=resolve(row.get("extends").getAsString(),source,resolved,visiting,origins);
                components.addAll(parent.components());properties.addAll(parent.properties());
            }
            components.addAll(strings(row,"components"));properties.addAll(strings(row,"properties"));
            if(components.size()>64||components.size()!=properties.size())throw new IllegalArgumentException("basis: invalid ordered components/properties");
            new MaterialAxis(components);
            var result=new Basis(List.copyOf(components),List.copyOf(properties));resolved.put(id,result);return result;
        }catch(RuntimeException invalid){throw error(origins,row,invalid.getMessage());}
        finally {visiting.remove(id);}
    }
    private static List<String> strings(JsonObject o,String key) {
        if(!o.has(key)||!o.get(key).isJsonArray())throw new IllegalArgumentException("basis."+key+": required array");
        var values=new ArrayList<String>();
        for(var value:o.getAsJsonArray(key)) {
            if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank())throw new IllegalArgumentException("basis."+key+": string required");
            values.add(value.getAsString());
        }
        return values;
    }
    private static IllegalArgumentException error(Map<JsonObject,String> origins,JsonObject row,String message) {
        return new IllegalArgumentException(origins.get(row)+": "+message);
    }
}
