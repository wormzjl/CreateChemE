package com.wormzjl.createcheme.science.material;

/** F4 probe: every package's fingerprint and physics fingerprint, and the network package's fluid thermodynamic
 * fingerprint; run at e837ada (REF) and at the F4 tree to show which moved. */
public final class FingerprintProbe {
    public static void main(String[] args) {
        var catalog=MaterialCatalog.bundled();
        for(var id:new java.util.TreeSet<>(catalog.packages().keySet())) {
            var p=catalog.requirePackage(id);
            System.out.println(id+" fingerprint="+p.fingerprint()+" physics="+catalog.physicsFingerprint(id,p.components())+" fluidThermo="+catalog.fluidThermoFingerprint(id));
        }
    }
}
