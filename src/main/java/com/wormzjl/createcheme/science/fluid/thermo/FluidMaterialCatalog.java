package com.wormzjl.createcheme.science.fluid.thermo;

import com.google.gson.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private fluid snapshot extension. Never publishes to MaterialRuntime or edits V3's catalog. */
public final class FluidMaterialCatalog {
    private static final String ROOT="data/createcheme/materials/";
    private FluidMaterialCatalog() {}
    public static MaterialCatalog withNitrogen(MaterialCatalog original,String packageId) {
        if(original.requirePackage(packageId).components().contains("Nitrogen"))return original;
        var resources=new HashMap<>(original.resources());
        resources.put(ROOT+"components/fluid_nitrogen.json","""
                {"schema_version":1,"id":"Nitrogen","kind":"chemical","translation_key":"material.createcheme.nitrogen",
                 "fallback":"Nitrogen","appearance":{"color":"#DCE8FA","transparency":0.95,"estimated":true}}
                """);
        try(var input=FluidMaterialCatalog.class.getResourceAsStream("/data/createcheme/fluid/nitrogen_property.json")) {
            if(input==null)throw new IllegalStateException("Missing nitrogen property data");
            resources.put(ROOT+"properties/fluid_nitrogen.json",new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }catch(IOException failure){throw new IllegalStateException("Cannot read nitrogen properties",failure);}
        boolean found=false;
        for(var entry:original.resources().entrySet()) {
            if(!entry.getKey().startsWith(ROOT))continue;
            var json=JsonParser.parseString(entry.getValue()).getAsJsonObject();
            if(entry.getKey().startsWith(ROOT+"packages/")&&json.get("id").getAsString().equals(packageId)) {
                json.getAsJsonArray("components").add("Nitrogen");json.getAsJsonArray("properties").add("createcheme:fluid_nitrogen");
                json.getAsJsonObject("aliases").addProperty("N2","Nitrogen");
                json.addProperty("revision","fluid-nitrogen-r1");json.addProperty("missing_interactions","zero");
                json.getAsJsonArray("advisory_evidence").add("NITROGEN_EXTENSION: initial charge is conserved inventory; nitrogen/hydrocarbon PR interactions estimated zero; no nitrogen dissolution in the separate free-water phase.");
                resources.put(entry.getKey(),json.toString());found=true;
            }else if(entry.getKey().startsWith(ROOT+"assays/")&&json.get("package").getAsString().equals(packageId)) {
                if(json.get("basis").getAsString().equals("standard_liquid_volume"))throw new IllegalArgumentException("Nitrogen extension requires a mass/mole assay basis");
                json.getAsJsonArray("components").add("Nitrogen");json.getAsJsonArray("amounts").add(0);
                resources.put(entry.getKey(),json.toString());
            }
        }
        if(!found)throw new IllegalArgumentException("Missing package resource "+packageId);
        return MaterialCatalog.parse(resources);
    }
}
