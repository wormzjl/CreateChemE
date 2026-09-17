package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.material.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog;
import java.util.*;

/** Persisted identity for topology/accounting even when a world has no active hydraulic islands. */
public record FluidBasis(String packageId,List<String> components,String scientificFingerprint) {
    public FluidBasis {
        Objects.requireNonNull(packageId);Objects.requireNonNull(scientificFingerprint);
        components=new MaterialAxis(components).ids();
        if(!packageId.matches("[a-z][a-z0-9_.:-]{0,127}")||!scientificFingerprint.matches("[0-9a-f]{64}:[0-9a-f]{64}")
                ||!components.getLast().equals("Water")||!components.contains("Nitrogen"))throw new IllegalArgumentException("Invalid fluid basis contract");
    }
    public static FluidBasis capture(MaterialCatalog catalog) {
        String id=FluidMaterialCatalog.networkPackage(catalog);var ids=new ArrayList<>(catalog.requirePackage(id).components());ids.add("Water");
        return new FluidBasis(id,ids,catalog.fluidThermoFingerprint(id)+":"+catalog.viscosityFingerprint(id));
    }
    public void requireCurrent(MaterialCatalog catalog) {
        if(!equals(capture(catalog)))throw new IllegalArgumentException("Incompatible saved fluid topology basis; use a fresh development world or explicitly reset its fluid data");
    }
}
