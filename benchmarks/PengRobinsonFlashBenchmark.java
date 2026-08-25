import com.wormzjl.createcheme.science.thermo.FlashResult;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import com.wormzjl.createcheme.science.thermo.TpFlashSolver;
import java.util.Arrays;
import java.util.List;

/** Simple warmed benchmark for the first Peng-Robinson TP-flash implementation. */
public final class PengRobinsonFlashBenchmark {
    private static volatile double blackhole;

    private record Case(String name, double temperatureKelvin, double pressurePascal, double[] feed) {}

    public static void main(String[] args) {
        var methane = new ThermoComponent("methane", 190.564, 4_599_200.0, 0.01142, 0.016043);
        var nButane = new ThermoComponent("n_butane", 425.12, 3_796_000.0, 0.2002, 0.058124);
        var solver = new TpFlashSolver(PengRobinson78.withoutBinaryInteractions(List.of(methane, nButane)));
        Case[] cases = {
            new Case("two-phase methane/n-butane", 250.0, 2_000_000.0, new double[] {0.5, 0.5}),
            new Case("single vapor methane/n-butane", 500.0, 100_000.0, new double[] {0.7, 0.3})
        };

        System.out.printf("java=%s logicalProcessors=%d%n",
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        for (Case benchmarkCase : cases) {
            run(solver, benchmarkCase);
        }
        System.out.printf("blackhole=%.9g%n", blackhole);
    }

    private static void run(TpFlashSolver solver, Case benchmarkCase) {
        long warmupDeadline = System.nanoTime() + 2_000_000_000L;
        int warmupSolves = 0;
        while (System.nanoTime() < warmupDeadline) {
            consume(solver.solve(
                    benchmarkCase.temperatureKelvin(),
                    benchmarkCase.pressurePascal(),
                    benchmarkCase.feed()));
            warmupSolves++;
        }

        double[] samplesMicroseconds = new double[20_000];
        for (int sample = 0; sample < samplesMicroseconds.length; sample++) {
            long start = System.nanoTime();
            FlashResult result = solver.solve(
                    benchmarkCase.temperatureKelvin(),
                    benchmarkCase.pressurePascal(),
                    benchmarkCase.feed());
            samplesMicroseconds[sample] = (System.nanoTime() - start) / 1_000.0;
            consume(result);
        }
        Arrays.sort(samplesMicroseconds);

        System.out.printf("%n%s%n", benchmarkCase.name());
        System.out.printf("  warmupSolves=%d samples=%d%n", warmupSolves, samplesMicroseconds.length);
        System.out.printf("  p50=%.3f us p95=%.3f us p99=%.3f us%n",
                percentile(samplesMicroseconds, 0.50),
                percentile(samplesMicroseconds, 0.95),
                percentile(samplesMicroseconds, 0.99));
    }

    private static void consume(FlashResult result) {
        blackhole += result.vaporFraction()
                + result.maximumLogFugacityResidual()
                + result.liquidMoleFractions()[0]
                + result.vaporMoleFractions()[0];
    }

    private static double percentile(double[] sorted, double probability) {
        int nearestRank = (int) Math.ceil(probability * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, nearestRank - 1))];
    }
}
