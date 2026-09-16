package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Map;
import java.util.Objects;

/**
 * The registered material package the fluid network runs on, and the saved package ids that resolve
 * to it.
 *
 * <p>Nitrogen used to exist only as a private in-memory extension of the column's package, built per
 * model by appending a component, a property, an alias and a zero assay amount to a copy of the
 * parsed resources. It is shared catalog data now: {@code createcheme:tjl20_methane_nitrogen} is the
 * column's twenty components and properties in their declared order, followed by nitrogen, on the
 * same {@code createcheme:tjl20} interaction set with {@code missing_interactions: zero} and the same
 * water model. The column's own package is untouched and still has exactly twenty components.</p>
 *
 * <p>The extension produced a package with that content under the <em>column's</em> id, so a world
 * saved before this change names {@code createcheme:tjl20_methane} in its island entries. Such an id
 * is migrated here on load; the scientific revision the codec compares is unchanged, because the
 * registered package carries the same revision string, the same components, properties, interactions,
 * water and assay the extension produced, hence the same fingerprint. The next save writes the
 * registered id.</p>
 */
public final class FluidMaterialCatalog {
    /** The gameplay basis: the crude basis plus nitrogen, with water last in the conserved basis. */
    public static final String NETWORK_PACKAGE="createcheme:tjl20_methane_nitrogen";
    /** The column's package, which the network basis extends by exactly one component. */
    public static final String CRUDE_BASIS_PACKAGE="createcheme:tjl20_methane";
    public static final String NITROGEN="Nitrogen";
    /** Package ids a saved world may still name for a network island. */
    private static final Map<String,String> MIGRATED=Map.of(CRUDE_BASIS_PACKAGE,NETWORK_PACKAGE);

    private FluidMaterialCatalog() {}

    /**
     * The registered network package for a requested or saved id: the id itself when it carries
     * nitrogen, the migrated id for a pre-registration save, and a refusal otherwise. The network
     * basis is the package's components plus water, and every persisted composition, device spec and
     * preset is indexed in that order, so a package without nitrogen cannot be run silently.
     */
    public static String resolveNetworkPackage(MaterialCatalog catalog,String packageId) {
        Objects.requireNonNull(catalog,"catalog");Objects.requireNonNull(packageId,"packageId");
        String resolved=MIGRATED.getOrDefault(packageId,packageId);
        var registered=catalog.requirePackage(resolved);
        if(!registered.components().contains(NITROGEN))
            throw new IllegalArgumentException("The fluid network basis requires a \""+NITROGEN+"\" component: package "
                    +packageId+" has "+registered.components().size()+" components and none of them is nitrogen. Use "
                    +NETWORK_PACKAGE+", or register the same extension for this package.");
        return resolved;
    }
}
