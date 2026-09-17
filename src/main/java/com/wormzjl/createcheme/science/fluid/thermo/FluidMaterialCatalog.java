package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Map;
import java.util.Objects;

/** Current fluid basis; obsolete development-world bases are rejected. */
public final class FluidMaterialCatalog {
    /** The gameplay basis: the crude basis plus nitrogen, with water last in the conserved basis. */
    public static final String NETWORK_PACKAGE="createcheme:tjl20_methane_nitrogen";
    /** The column's package, which the network basis extends by exactly one component. */
    public static final String CRUDE_BASIS_PACKAGE="createcheme:tjl20_methane";
    public static final String NITROGEN="Nitrogen";
    /** Package ids a saved world may still name for a network island. */


    private FluidMaterialCatalog() {}
    public static int conservedCount() {
        return com.wormzjl.createcheme.science.material.MaterialRuntime.current().requirePackage(NETWORK_PACKAGE).components().size()+1;
    }
    public static int nitrogenIndex() {
        int index=com.wormzjl.createcheme.science.material.MaterialRuntime.current().requirePackage(NETWORK_PACKAGE).components().indexOf(NITROGEN);
        if(index<0)throw new IllegalArgumentException("Network package is missing nitrogen");
        return index;
    }
    public static int waterIndex() { return conservedCount()-1; }

    /**
     * The registered network package for a requested or saved id: the id itself when it carries
     * nitrogen, the migrated id for a pre-registration save, and a refusal otherwise. The network
     * basis is the package's components plus water, and every persisted composition, device spec and
     * preset is indexed in that order, so a package without nitrogen cannot be run silently.
     */
    public static String resolveNetworkPackage(MaterialCatalog catalog,String packageId) {
        Objects.requireNonNull(catalog,"catalog");Objects.requireNonNull(packageId,"packageId");
        String resolved=packageId;
        var registered=catalog.requirePackage(resolved);
        if(!registered.components().contains(NITROGEN))
            throw new IllegalArgumentException("The fluid network basis requires a \""+NITROGEN+"\" component: package "
                    +packageId+" has "+registered.components().size()+" components and none of them is nitrogen. Use "
                    +NETWORK_PACKAGE+", or register the same extension for this package.");
        return resolved;
    }
}
