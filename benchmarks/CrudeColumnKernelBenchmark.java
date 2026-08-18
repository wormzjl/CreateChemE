import java.util.Arrays;

/**
 * Dependency-free Java 21 microbenchmark for the numerical kernel expected in
 * a gameplay crude column. It is not a column-design program: it repeatedly
 * performs staged Peng-Robinson TP flashes, caloric sums, and adjacent-stage
 * composition sweeps so measured time can bound the dominant property work.
 */
public final class CrudeColumnKernelBenchmark {
    private static final double R = 8.31446261815324;
    private static volatile double blackhole;

    private record Case(String name, int components, int stages,
                        int columnSweeps, int flashIterations, int measuredSamples) {}

    public static void main(String[] args) {
        Case[] cases = {
            new Case("10-cell gameplay column", 12, 10, 6, 3, 2_000),
            new Case("16-cell reduced CDU", 12, 16, 10, 4, 1_500),
            new Case("61-stage synthetic stress case", 16, 61, 15, 6, 500)
        };

        System.out.printf("java=%s vm=%s%n",
            System.getProperty("java.version"), System.getProperty("java.vm.name"));
        System.out.printf("cpu=%s logicalProcessors=%d%n",
            System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"),
            Runtime.getRuntime().availableProcessors());
        System.out.println("All workspaces are preallocated; timings are one worker thread.");

        for (Case benchmarkCase : cases) {
            run(benchmarkCase);
        }
        System.out.printf("blackhole=%.9g%n", blackhole);
    }

    private static void run(Case benchmarkCase) {
        ColumnKernel kernel = new ColumnKernel(benchmarkCase);
        long warmupDeadline = System.nanoTime() + 2_000_000_000L;
        int warmupUpdates = 0;
        while (System.nanoTime() < warmupDeadline) {
            blackhole += kernel.solve();
            warmupUpdates++;
        }

        double[] samplesMs = new double[benchmarkCase.measuredSamples];
        for (int sample = 0; sample < samplesMs.length; sample++) {
            long start = System.nanoTime();
            double checksum = kernel.solve();
            long elapsed = System.nanoTime() - start;
            blackhole += checksum;
            samplesMs[sample] = elapsed / 1_000_000.0;
        }
        Arrays.sort(samplesMs);
        double median = percentile(samplesMs, 0.50);
        double p95 = percentile(samplesMs, 0.95);
        double p99 = percentile(samplesMs, 0.99);
        long stageFlashes = (long) benchmarkCase.stages * benchmarkCase.columnSweeps;
        long phaseEosEvaluations = 2L * stageFlashes * benchmarkCase.flashIterations;
        long doubles = (long) benchmarkCase.stages * benchmarkCase.components * 5
            + (long) benchmarkCase.components * benchmarkCase.components;
        double workspaceKiB = doubles * Double.BYTES / 1024.0;

        System.out.printf("%n%s%n", benchmarkCase.name);
        System.out.printf("  components=%d stages=%d sweeps=%d flashIterations=%d%n",
            benchmarkCase.components, benchmarkCase.stages,
            benchmarkCase.columnSweeps, benchmarkCase.flashIterations);
        System.out.printf("  stageFlashes/update=%d phaseEOS/update=%d workspace~%.1f KiB%n",
            stageFlashes, phaseEosEvaluations, workspaceKiB);
        System.out.printf("  warmupUpdates=%d measuredSamples=%d%n",
            warmupUpdates, samplesMs.length);
        System.out.printf("  p50=%.4f ms/update p95=%.4f ms/update p99=%.4f ms/update%n",
            median, p95, p99);
        System.out.printf("  one plant CPU demand: %.3f%% of one core at 1 s; %.3f%% at 5 s%n",
            median / 10.0, median / 50.0);
    }

    private static double percentile(double[] sorted, double probability) {
        int nearestRank = (int) Math.ceil(probability * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, nearestRank - 1))];
    }

    private static final class ColumnKernel {
        private final Case config;
        private final int n;
        private double[][] z;
        private double[][] nextZ;
        private final double[][] liquid;
        private final double[][] vapor;
        private final double[][] k;
        private final double[] temperature;
        private final double[] pressure;
        private final double[] feed;
        private final double[] tc;
        private final double[] pc;
        private final double[] omega;
        private final double[] cpA;
        private final double[] cpB;
        private final double[] latent;
        private final double[][] kij;

        private final double[] phiL;
        private final double[] phiV;
        private final double[] ai;
        private final double[] bi;
        private final double[] sumA;
        private final double[] betaHolder = new double[1];

        private ColumnKernel(Case config) {
            this.config = config;
            this.n = config.components;
            this.z = new double[config.stages][n];
            this.nextZ = new double[config.stages][n];
            this.liquid = new double[config.stages][n];
            this.vapor = new double[config.stages][n];
            this.k = new double[config.stages][n];
            this.temperature = new double[config.stages];
            this.pressure = new double[config.stages];
            this.feed = new double[n];
            this.tc = new double[n];
            this.pc = new double[n];
            this.omega = new double[n];
            this.cpA = new double[n];
            this.cpB = new double[n];
            this.latent = new double[n];
            this.kij = new double[n][n];
            this.phiL = new double[n];
            this.phiV = new double[n];
            this.ai = new double[n];
            this.bi = new double[n];
            this.sumA = new double[n];
            initializeProperties();
            initializeColumn();
        }

        private void initializeProperties() {
            double feedSum = 0.0;
            for (int i = 0; i < n; i++) {
                double f = i / (double) Math.max(1, n - 1);
                double normalBoiling = 315.0 + 565.0 * f;
                tc[i] = normalBoiling * (1.48 - 0.13 * f);
                pc[i] = 4.6e6 * Math.exp(-1.28 * f);
                omega[i] = 0.10 + 0.72 * f;
                cpA[i] = 80.0 + 330.0 * f;
                cpB[i] = 0.10 + 0.22 * f;
                latent[i] = 28_000.0 + 34_000.0 * f;
                feed[i] = 0.55 + 0.85 * Math.sin(Math.PI * (i + 0.5) / n);
                feedSum += feed[i];
            }
            for (int i = 0; i < n; i++) {
                feed[i] /= feedSum;
                for (int j = 0; j < n; j++) {
                    kij[i][j] = i == j ? 0.0 : 0.012 * Math.abs(i - j) / n;
                }
            }
        }

        private void initializeColumn() {
            for (int stage = 0; stage < config.stages; stage++) {
                double position = stage / (double) Math.max(1, config.stages - 1);
                temperature[stage] = 359.28 + position * (578.55 - 359.28);
                pressure[stage] = 135_800.0 + position * (225_500.0 - 135_800.0);
                double sum = 0.0;
                for (int i = 0; i < n; i++) {
                    double componentPosition = i / (double) Math.max(1, n - 1);
                    double enrichment = Math.exp(1.6 * (position - 0.5) * (componentPosition - 0.5));
                    z[stage][i] = feed[i] * enrichment;
                    sum += z[stage][i];
                    double wilson = pc[i] / pressure[stage]
                        * Math.exp(5.373 * (1.0 + omega[i]) * (1.0 - tc[i] / temperature[stage]));
                    k[stage][i] = clamp(wilson, 1e-8, 1e8);
                }
                normalize(z[stage], sum);
            }
        }

        private double solve() {
            double checksum = 0.0;
            int feedStage = (int) Math.round(config.stages * 0.62);
            for (int sweep = 0; sweep < config.columnSweeps; sweep++) {
                for (int stage = 0; stage < config.stages; stage++) {
                    flash(stage);
                    double t = temperature[stage];
                    double sensibleLiquid = 0.0;
                    double sensibleVapor = 0.0;
                    for (int i = 0; i < n; i++) {
                        double dt = t - 298.15;
                        double hLiquid = cpA[i] * dt + 0.5 * cpB[i] * dt * dt;
                        sensibleLiquid += liquid[stage][i] * hLiquid;
                        sensibleVapor += vapor[stage][i] * (hLiquid + latent[i]);
                    }
                    checksum += 1e-9 * (sensibleLiquid + betaHolder[0]
                        * (sensibleVapor - sensibleLiquid));
                }

                for (int stage = 0; stage < config.stages; stage++) {
                    int above = Math.max(0, stage - 1);
                    int below = Math.min(config.stages - 1, stage + 1);
                    double sum = 0.0;
                    for (int i = 0; i < n; i++) {
                        double value = 0.48 * liquid[above][i] + 0.48 * vapor[below][i];
                        if (stage == feedStage) {
                            value += 0.12 * feed[i];
                        } else {
                            value += 0.04 * z[stage][i];
                        }
                        nextZ[stage][i] = Math.max(1e-18, value);
                        sum += nextZ[stage][i];
                    }
                    normalize(nextZ[stage], sum);
                }
                double[][] swap = z;
                z = nextZ;
                nextZ = swap;
            }
            return checksum + z[0][0] + z[config.stages - 1][n - 1];
        }

        private void flash(int stage) {
            double[] x = liquid[stage];
            double[] y = vapor[stage];
            double[] stageK = k[stage];
            double beta = 0.5;
            for (int iteration = 0; iteration < config.flashIterations; iteration++) {
                beta = rachfordRice(z[stage], stageK);
                double sumX = 0.0;
                double sumY = 0.0;
                for (int i = 0; i < n; i++) {
                    double denominator = Math.max(1e-16, 1.0 + beta * (stageK[i] - 1.0));
                    x[i] = z[stage][i] / denominator;
                    y[i] = stageK[i] * x[i];
                    sumX += x[i];
                    sumY += y[i];
                }
                normalize(x, sumX);
                normalize(y, sumY);
                fugacityCoefficients(temperature[stage], pressure[stage], x, false, phiL);
                fugacityCoefficients(temperature[stage], pressure[stage], y, true, phiV);
                for (int i = 0; i < n; i++) {
                    double target = clamp(phiL[i] / phiV[i], 1e-8, 1e8);
                    stageK[i] = Math.exp(0.45 * Math.log(stageK[i]) + 0.55 * Math.log(target));
                }
            }
            betaHolder[0] = beta;
        }

        private double rachfordRice(double[] composition, double[] stageK) {
            double atZero = rr(composition, stageK, 0.0);
            double atOne = rr(composition, stageK, 1.0);
            if (atZero <= 0.0) return 0.0;
            if (atOne >= 0.0) return 1.0;
            double low = 0.0;
            double high = 1.0;
            for (int i = 0; i < 14; i++) {
                double mid = 0.5 * (low + high);
                if (rr(composition, stageK, mid) > 0.0) low = mid;
                else high = mid;
            }
            return 0.5 * (low + high);
        }

        private double rr(double[] composition, double[] stageK, double beta) {
            double value = 0.0;
            for (int i = 0; i < n; i++) {
                double km1 = stageK[i] - 1.0;
                value += composition[i] * km1 / (1.0 + beta * km1);
            }
            return value;
        }

        private void fugacityCoefficients(double t, double p, double[] composition,
                                          boolean vaporRoot, double[] output) {
            double aMix = 0.0;
            double bMix = 0.0;
            for (int i = 0; i < n; i++) {
                double sqrtTr = Math.sqrt(t / tc[i]);
                double kappa = 0.37464 + 1.54226 * omega[i] - 0.26992 * omega[i] * omega[i];
                double alpha = square(1.0 + kappa * (1.0 - sqrtTr));
                ai[i] = 0.45724 * R * R * tc[i] * tc[i] / pc[i] * alpha;
                bi[i] = 0.07780 * R * tc[i] / pc[i];
                bMix += composition[i] * bi[i];
            }
            for (int i = 0; i < n; i++) {
                double row = 0.0;
                for (int j = 0; j < n; j++) {
                    row += composition[j] * Math.sqrt(ai[i] * ai[j]) * (1.0 - kij[i][j]);
                }
                sumA[i] = row;
                aMix += composition[i] * row;
            }

            double bigA = aMix * p / (R * R * t * t);
            double bigB = bMix * p / (R * t);
            double c2 = -(1.0 - bigB);
            double c1 = bigA - 3.0 * bigB * bigB - 2.0 * bigB;
            double c0 = -(bigA * bigB - bigB * bigB - bigB * bigB * bigB);
            double zFactor = cubicRoot(c2, c1, c0, bigB, vaporRoot);
            double logRatio = Math.log((zFactor + (1.0 + Math.sqrt(2.0)) * bigB)
                / (zFactor + (1.0 - Math.sqrt(2.0)) * bigB));
            double attraction = bigA / (2.0 * Math.sqrt(2.0) * Math.max(bigB, 1e-15));

            for (int i = 0; i < n; i++) {
                double biOverB = bi[i] / bMix;
                double bracket = 2.0 * sumA[i] / aMix - biOverB;
                double lnPhi = biOverB * (zFactor - 1.0)
                    - Math.log(Math.max(1e-15, zFactor - bigB))
                    - attraction * bracket * logRatio;
                output[i] = Math.exp(clamp(lnPhi, -40.0, 40.0));
            }
        }

        private static double cubicRoot(double c2, double c1, double c0,
                                        double bigB, boolean largest) {
            double p = c1 - c2 * c2 / 3.0;
            double q = 2.0 * c2 * c2 * c2 / 27.0 - c2 * c1 / 3.0 + c0;
            double discriminant = q * q / 4.0 + p * p * p / 27.0;
            double selected;
            if (discriminant >= 0.0) {
                double sqrt = Math.sqrt(discriminant);
                selected = Math.cbrt(-q / 2.0 + sqrt) + Math.cbrt(-q / 2.0 - sqrt) - c2 / 3.0;
            } else {
                double radius = 2.0 * Math.sqrt(-p / 3.0);
                double theta = Math.acos(clamp((3.0 * q / (2.0 * p)) * Math.sqrt(-3.0 / p), -1.0, 1.0)) / 3.0;
                double r0 = radius * Math.cos(theta) - c2 / 3.0;
                double r1 = radius * Math.cos(theta - 2.0 * Math.PI / 3.0) - c2 / 3.0;
                double r2 = radius * Math.cos(theta - 4.0 * Math.PI / 3.0) - c2 / 3.0;
                if (largest) {
                    selected = Math.max(r0, Math.max(r1, r2));
                } else {
                    selected = Double.POSITIVE_INFINITY;
                    if (r0 > bigB) selected = Math.min(selected, r0);
                    if (r1 > bigB) selected = Math.min(selected, r1);
                    if (r2 > bigB) selected = Math.min(selected, r2);
                    if (!Double.isFinite(selected)) selected = Math.max(r0, Math.max(r1, r2));
                }
            }
            return Math.max(selected, bigB + 1e-12);
        }

        private static void normalize(double[] values, double sum) {
            double inverse = 1.0 / Math.max(sum, 1e-300);
            for (int i = 0; i < values.length; i++) values[i] *= inverse;
        }

        private static double square(double value) {
            return value * value;
        }

        private static double clamp(double value, double low, double high) {
            return Math.max(low, Math.min(high, value));
        }
    }
}
