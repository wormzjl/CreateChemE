package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;

/**
 * WP7c probe (scratch, never tracked): {@code LegacyNetworkPathPinTest.digest} on the class path given (the committed
 * classes, or the WP7c classes first), and the {@link HydrocarbonModel#phase} flags of the probe's grid whose value
 * differs from the bare {@code PIP} rule, so the moved states can be named.
 */
public final class Wp7cPinDigest {
    public static void main(String[] args) {
        var model = new FluidThermodynamics(MaterialCatalog.bundled(), LegacyNetworkPathPinTest.NETWORK);
        System.out.println("digest " + LegacyNetworkPathPinTest.digest(model));
        var hydrocarbon = model.hydrocarbon;
        var eos = hydrocarbon.translated();
        int n = hydrocarbon.componentCount();
        int nitrogen = hydrocarbon.components().indexOf("Nitrogen");
        double[] pure = new double[n];
        pure[nitrogen] = 1;
        double[] mixture = new double[n];
        for (int i = 0; i < n; i++) mixture[i] = i == nitrogen ? 0.1 : (i % 5 + 1) * 0.01;
        double[] lean = new double[n];
        lean[nitrogen] = 0.9;
        lean[(nitrogen + 1) % n] = 0.1;
        String[] names = {"pure", "lean", "mixture"};
        double[][] compositions = {pure, lean, mixture};
        for (double t : new double[] {77.0, 110.0, 150.0, 293.15, 350.0, 450.0, 700.0}) {
            for (double p : new double[] {1.0e5, 5.0e5, 2.0e6}) {
                for (int c = 0; c < 3; c++) {
                    for (PhaseRoot root : PhaseRoot.values()) {
                        try {
                            var phase = hydrocarbon.phase(t, p, compositions[c], root);
                            var values = eos.evaluateValues(t, p, compositions[c], root, eos.prepare(t));
                            double pip = values.phaseIdentificationParameter();
                            boolean bareVapour = root == PhaseRoot.VAPOR && pip <= 1.0;
                            boolean bareAbsent = root == PhaseRoot.LIQUID && values.physicalRootCount() == 1 && !(pip > 1.0);
                            if (bareVapour != phase.vaporLike() || bareAbsent != phase.liquidRootAbsent()) {
                                System.out.printf("flags moved: %s K %s Pa %s %s: PIP %.9f Z %.9f vaporLike %s liquidRootAbsent %s%n", t, p,
                                        names[c], root, pip, values.compressibility(), phase.vaporLike(), phase.liquidRootAbsent());
                            }
                        } catch (RuntimeException refused) {
                            // refusals are hashed by their text; the flash probe (Wp7bDigestStates) lists them
                        }
                    }
                }
            }
        }
    }
}
