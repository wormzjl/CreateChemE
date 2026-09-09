package com.wormzjl.createcheme.science.column.v3.thermo;

/** Opt-in full-flash timing; compares identical calls with reused caller workspaces. */
public final class V3FlashOptimizationProbe {
    private static volatile double sink;
    private V3FlashOptimizationProbe() {}

    public static void main(String[] args) {
        for (String packageId : new String[]{"createcheme:cdu17_tjl_acs2018", "createcheme:tjl19_dwsim"}) {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
            double[] feed = thermo.crudeFeed("createcheme:tia_juana_light").moleFractions();
            V3ThermoWorkspace oldWorkspace = thermo.newWorkspace();
            V3ThermoWorkspace newWorkspace = thermo.newWorkspace();
            for (int batch = -4; batch < 10; batch++) {
                boolean oldFirst = batch % 2 == 0;
                long first = batch(thermo, feed, oldFirst ? oldWorkspace : newWorkspace, oldFirst);
                long second = batch(thermo, feed, oldFirst ? newWorkspace : oldWorkspace, !oldFirst);
                if (batch >= 0) System.out.printf("%s batch=%d referenceUs=%.3f optimizedUs=%.3f%n",
                        packageId, batch, (oldFirst ? first : second) / 100_000.0,
                        (oldFirst ? second : first) / 100_000.0);
            }
        }
    }

    private static long batch(V3PengRobinsonThermo thermo, double[] feed, V3ThermoWorkspace workspace, boolean reference) {
        long start = System.nanoTime();
        double sum = 0;
        for (int i = 0; i < 100; i++) {
            System.arraycopy(feed, 0, workspace.normalizedOverall, 0, feed.length);
            V3FlashResult result = reference
                    ? V3FeedFlashReference.resolve(thermo, 638.15, 267_250, workspace)
                    : V3FeedFlash.resolve(thermo, 638.15, 267_250, workspace);
            sum += result.vaporFraction();
        }
        long elapsed = System.nanoTime() - start;
        sink = sum;
        return elapsed;
    }
}
