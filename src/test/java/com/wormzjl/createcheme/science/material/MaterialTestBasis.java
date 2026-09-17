package com.wormzjl.createcheme.science.material;
/** Structural dimensions only. Scientific expectations belong to independent fixtures. */
public final class MaterialTestBasis {
    private MaterialTestBasis() {}
    public static final int CRUDE = MaterialCatalog.bundled().requirePackage("createcheme:tjl20_methane").components().size();
    public static final int NETWORK = MaterialCatalog.bundled().requirePackage("createcheme:tjl20_methane_nitrogen").components().size();
    public static final int NITROGEN = MaterialCatalog.bundled().requirePackage("createcheme:tjl20_methane_nitrogen").components().indexOf("Nitrogen");
}
