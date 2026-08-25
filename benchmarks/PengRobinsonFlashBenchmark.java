import com.wormzjl.createcheme.science.thermo.FlashResult;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import com.wormzjl.createcheme.science.thermo.TpFlashSolver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Simple warmed benchmark for the Peng-Robinson TP-flash implementation. */
public final class PengRobinsonFlashBenchmark {
    private static final double[] CRUDE_BOILING_POINTS_KELVIN = {
        295.45, 348.65, 384.15, 422.15, 460.15, 496.95,
        548.05, 598.85, 648.95, 705.75, 809.45, 992.25
    };
    private static final double[] CRUDE_MOLAR_MASSES_KILOGRAM_PER_MOL = {
        0.060, 0.085, 0.100, 0.120, 0.145, 0.175,
        0.210, 0.250, 0.300, 0.370, 0.480, 0.650
    };
    private static final double[] CRUDE_FEED = {
        0.0834, 0.1207, 0.0673, 0.1299, 0.0637, 0.1136,
        0.0933, 0.0795, 0.0686, 0.0648, 0.0634, 0.0514
    };
    private static volatile double blackhole;

    private record Case(
            String name,
            TpFlashSolver solver,
            double temperatureKelvin,
            double pressurePascal,
            double[] feed) {}

    public static void main(String[] args) {
        var methane = new ThermoComponent("methane", 190.564, 4_599_200.0, 0.01142, 0.016043);
        var nButane = new ThermoComponent("n_butane", 425.12, 3_796_000.0, 0.2002, 0.058124);
        var lightSolver = new TpFlashSolver(
                PengRobinson78.withoutBinaryInteractions(List.of(methane, nButane)));
        var crudeSolver = new TpFlashSolver(crudeEquationOfState());
        Case[] cases = {
            new Case(
                    "two-phase methane/n-butane",
                    lightSolver,
                    250.0,
                    2_000_000.0,
                    new double[] {0.5, 0.5}),
            new Case(
                    "single vapor methane/n-butane",
                    lightSolver,
                    500.0,
                    100_000.0,
                    new double[] {0.7, 0.3}),
            new Case(
                    "two-phase Tia Juana Light 12-cut proxy",
                    crudeSolver,
                    500.0,
                    190_000.0,
                    normalized(CRUDE_FEED))
        };

        System.out.printf("java=%s logicalProcessors=%d%n",
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        for (Case benchmarkCase : cases) {
            run(benchmarkCase);
        }
        System.out.printf("blackhole=%.9g%n", blackhole);
    }

    private static void run(Case benchmarkCase) {
        long warmupDeadline = System.nanoTime() + 2_000_000_000L;
        int warmupSolves = 0;
        while (System.nanoTime() < warmupDeadline) {
            consume(benchmarkCase.solver().solve(
                    benchmarkCase.temperatureKelvin(),
                    benchmarkCase.pressurePascal(),
                    benchmarkCase.feed()));
            warmupSolves++;
        }

        double[] samplesMicroseconds = new double[20_000];
        for (int sample = 0; sample < samplesMicroseconds.length; sample++) {
            long start = System.nanoTime();
            FlashResult result = benchmarkCase.solver().solve(
                    benchmarkCase.temperatureKelvin(),
                    benchmarkCase.pressurePascal(),
                    benchmarkCase.feed());
            samplesMicroseconds[sample] = (System.nanoTime() - start) / 1_000.0;
            consume(result);
        }
        Arrays.sort(samplesMicroseconds);

        System.out.printf("%n%s%n", benchmarkCase.name());
        System.out.printf("  components=%d warmupSolves=%d samples=%d%n",
                benchmarkCase.feed().length, warmupSolves, samplesMicroseconds.length);
        System.out.printf("  p50=%.3f us p95=%.3f us p99=%.3f us%n",
                percentile(samplesMicroseconds, 0.50),
                percentile(samplesMicroseconds, 0.95),
                percentile(samplesMicroseconds, 0.99));
    }

    private static PengRobinson78 crudeEquationOfState() {
        int count = CRUDE_BOILING_POINTS_KELVIN.length;
        List<ThermoComponent> components = new ArrayList<>(count);
        double[][] interactions = new double[count][count];
        for (int i = 0; i < count; i++) {
            double cutPosition = i / (double) (count - 1);
            components.add(new ThermoComponent(
                    "tia_juana_light_cut_" + (i + 1),
                    CRUDE_BOILING_POINTS_KELVIN[i] * (1.48 - 0.13 * cutPosition),
                    4_600_000.0 * Math.exp(-1.28 * cutPosition),
                    0.10 + 0.72 * cutPosition,
                    CRUDE_MOLAR_MASSES_KILOGRAM_PER_MOL[i]));
            for (int j = 0; j < count; j++) {
                interactions[i][j] = i == j ? 0.0 : 0.012 * Math.abs(i - j) / count;
            }
        }
        return new PengRobinson78(components, interactions);
    }

    private static double[] normalized(double[] values) {
        double[] result = values.clone();
        double sum = 0.0;
        for (double value : result) {
            sum += value;
        }
        for (int i = 0; i < result.length; i++) {
            result[i] /= sum;
        }
        return result;
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
