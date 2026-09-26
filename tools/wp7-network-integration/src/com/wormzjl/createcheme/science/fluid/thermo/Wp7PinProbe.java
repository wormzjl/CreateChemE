package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;

/** WP7 scratch: the catalog pins after the record edits, and the legacy network digest of the current tree. */
public class Wp7PinProbe {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        for (var p : catalog.packages().values()) {
            System.out.println(p.id() + " " + p.scientificRevision() + " " + catalog.physicsFingerprint(p.id(), p.components())
                    + " fluid " + catalog.fluidValidity(p.id()).map(v -> v.minimumPressure() + ".." + v.maximumPressure()).orElse("-"));
        }
        String network = "createcheme:tjl20_methane_nitrogen";
        System.out.println("N2 " + catalog.fluidValidity(network, "Nitrogen").orElseThrow());
        System.out.println("CH4 " + catalog.fluidValidity(network, "Methane").orElseThrow());
        var model = new FluidThermodynamics(catalog, network);
        System.out.println("revision " + model.hydrocarbon.revision());
        if (args.length > 0) System.out.println("digest " + LegacyNetworkPathPinTest.digest(model));
    }
}
