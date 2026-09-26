package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import java.util.*;

/** Separate solid-property identity; adding solids never changes the conserved fluid axis. */
public final class SolidMaterialCatalog {
    private final Map<String, SolidMaterial> materials;
    private SolidMaterialCatalog(Map<String, SolidMaterial> materials) { this.materials = Map.copyOf(materials); }
    public Map<String, SolidMaterial> materials() { return materials; }
    public SolidMaterial require(String id) {
        var material = materials.get(id);
        if (material == null) throw new IllegalArgumentException("Unknown solid material: " + id);
        return material;
    }
    public void validate(SolidInventory inventory) {
        for (var p : inventory.populations())
            if (!require(p.material().id()).equals(p.material())) throw new IllegalArgumentException("Saved solid properties need explicit migration: " + p.material().id());
    }
    static SolidMaterialCatalog parse(Map<String, String> resources) {
        var solids = new TreeMap<String, SolidMaterial>();
        for (var entry : resources.entrySet()) if (entry.getKey().contains("/materials/solids/")) {
            var o = JsonParser.parseString(entry.getValue()).getAsJsonObject();
            var material = new SolidMaterial(o.get("id").getAsString(), o.get("revision").getAsString(),
                    o.get("density_kg_per_m3").getAsDouble(), o.get("heat_capacity_j_per_kg_kelvin").getAsDouble());
            if (solids.putIfAbsent(material.id(), material) != null) throw new IllegalArgumentException("Duplicate solid material");
        }
        return new SolidMaterialCatalog(solids);
    }
}