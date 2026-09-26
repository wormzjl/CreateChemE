package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.*;
import java.util.Objects;

/** One configured gameplay axis. Changing it invalidates old development-world inventories. */
public final class FluidMaterialCatalog {
    /** Bundled fixture IDs; runtime code resolves the selected network from its catalog snapshot. */
    public static final String NETWORK_PACKAGE="createcheme:tjl20_methane_nitrogen";
    public static final String CRUDE_BASIS_PACKAGE="createcheme:tjl20_methane";
    public static final String NITROGEN="Nitrogen";
    private FluidMaterialCatalog() {}
    public static String networkPackage(MaterialCatalog catalog){return catalog.presets().network().packageId();}
    public static int conservedCount(){return MaterialRuntime.current().requirePackage(networkPackage(MaterialRuntime.current())).components().size()+1;}
    public static int nitrogenIndex(){return new MaterialAxis(MaterialRuntime.current().requirePackage(networkPackage(MaterialRuntime.current())).components()).requireIndex(NITROGEN);}
    public static int waterIndex(){return conservedCount()-1;}
    public static String resolveNetworkPackage(MaterialCatalog catalog,String packageId) {
        Objects.requireNonNull(catalog);Objects.requireNonNull(packageId);
        String selected=networkPackage(catalog);
        if(!selected.equals(packageId))throw new IllegalArgumentException("Unsupported saved fluid basis "+packageId+"; configured basis is "+selected+". Use a fresh development world or explicitly reset its data.");
        return selected;
    }
}
