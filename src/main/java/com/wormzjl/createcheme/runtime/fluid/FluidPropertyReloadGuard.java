package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.ApproximationAnchor;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Objects;
import java.util.Optional;

/** Owner-thread check against the qualified startup property snapshot. A new scientific
 * model needs explicit qualification/migration; replacing names alone does not pause science. */
public final class FluidPropertyReloadGuard {
    public static final String HOLD="HELD: property data changed; restore the qualified data or use a fresh development world after an explicit data reset";
    private final String packageId,qualifiedRevision;
    private final double compressibility,maximumVelocity,traceCutoff;
    private MaterialCatalog observed;
    private String refusal;
    public FluidPropertyReloadGuard(MaterialCatalog initial,String packageId,double compressibility,FluidThermodynamics model) {
        observed=Objects.requireNonNull(initial);this.packageId=Objects.requireNonNull(packageId);
        this.compressibility=compressibility;maximumVelocity=model.maximumVelocityMetresPerSecond();
        // Every numerical setting the qualified model was built with, so a reload of the same data
        // rebuilds the same model and the comparison isolates the property change it is looking for.
        traceCutoff=model.traceTruncation().cutoffMoleFraction();
        qualifiedRevision=ApproximationAnchor.revision(model);
    }
    public Optional<String> inspect(MaterialCatalog current) {
        Objects.requireNonNull(current);if(current==observed)return Optional.ofNullable(refusal);
        observed=current;
        try {
            var candidate=FluidThermodynamics.forNetwork(current,packageId,compressibility,maximumVelocity,traceCutoff);
            refusal=qualifiedRevision.equals(ApproximationAnchor.revision(candidate))?null:HOLD;
        } catch(IllegalArgumentException|IllegalStateException unavailable) {refusal=HOLD;}
        return Optional.ofNullable(refusal);
    }
}
