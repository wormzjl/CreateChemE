package com.wormzjl.createcheme.science.fluid.thermo;
/** Prints LegacyNetworkPathPinTest.digest on the sources it was compiled with (WP5 one-off, see README). */
public final class PrintLegacyDigest {
    public static void main(String[] args) {
        var catalog=com.wormzjl.createcheme.science.material.MaterialCatalog.bundled();
        var model=new FluidThermodynamics(catalog,LegacyNetworkPathPinTest.NETWORK);
        System.out.println("revision "+model.hydrocarbon.revision());
        System.out.println("digest "+LegacyNetworkPathPinTest.digest(model));
        System.out.println("java "+System.getProperty("java.version"));
    }
}
