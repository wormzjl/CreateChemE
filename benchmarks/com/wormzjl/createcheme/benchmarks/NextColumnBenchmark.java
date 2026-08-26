package com.wormzjl.createcheme.benchmarks;

import com.wormzjl.createcheme.science.column.nextgen.ColumnNextInput;
import com.wormzjl.createcheme.science.column.nextgen.ColumnProblem;
import com.wormzjl.createcheme.science.column.nextgen.DryColumnOutcome;
import com.wormzjl.createcheme.science.column.nextgen.DryInsideOutColumnSolver;
import com.wormzjl.createcheme.science.column.nextgen.DrySolverDiagnostics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable direct-core benchmark for the experimental next column.  A typed failure is retained as
 * data; this harness never re-labels it as a result or uses any cache/service path.
 */
public final class NextColumnBenchmark {
    private NextColumnBenchmark() {}

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        ColumnProblem problem = ColumnProblem.resolve(ColumnNextInput.defaults());
        DryInsideOutColumnSolver solver = new DryInsideOutColumnSolver();
        for (int index = 0; index < arguments.warmup(); index++) solver.solve(problem);
        List<Sample> samples = new ArrayList<>();
        for (int index = 0; index < arguments.samples(); index++) {
            long started = System.nanoTime();
            DryColumnOutcome outcome = solver.solve(problem);
            long elapsed = System.nanoTime() - started;
            samples.add(Sample.from(elapsed, outcome));
        }
        writeAtomically(arguments.report(), report(arguments, samples));
        System.out.println("benchmark=next-column-core samples=" + arguments.samples()
                + " status=" + status(samples) + " report=" + arguments.report());
    }

    private static String report(Arguments arguments, List<Sample> samples) {
        List<Long> timings = samples.stream().map(Sample::elapsedNanos).sorted().toList();
        return "{\n"
                + "  \"reportSchemaVersion\": 1,\n"
                + "  \"benchmark\": \"next-column-core\",\n"
                + "  \"status\": \"" + status(samples) + "\",\n"
                + "  \"completedAtUtc\": \"" + Instant.now() + "\",\n"
                + "  \"samples\": " + arguments.samples() + ",\n"
                + "  \"warmup\": " + arguments.warmup() + ",\n"
                + "  \"wallP50Nanos\": " + percentile(timings, 0.50) + ",\n"
                + "  \"wallP95Nanos\": " + percentile(timings, 0.95) + ",\n"
                + "  \"outcomes\": [\n" + samples.stream().map(Sample::json).reduce((left, right) -> left + ",\n" + right).orElse("")
                + "\n  ]\n}\n";
    }

    private static String status(List<Sample> samples) {
        return samples.stream().allMatch(Sample::accepted)
                ? "COMPLETE_WITH_ACCEPTED_RESULTS" : "COMPLETE_WITH_UNACCEPTED_SOLVER_OUTCOMES";
    }

    private static long percentile(List<Long> values, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * values.size()) - 1);
        return values.get(index);
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "next-column-report-", ".json");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private record Sample(long elapsedNanos, boolean accepted, String outcome, int outerIterations,
                          int innerIterations, int thomasSolves, int propertyPhaseEvaluations) {
        static Sample from(long elapsedNanos, DryColumnOutcome outcome) {
            DrySolverDiagnostics diagnostics = outcome.diagnostics();
            String code = outcome instanceof DryColumnOutcome.Success ? "SUCCESS"
                    : ((DryColumnOutcome.Failure) outcome).code().name();
            return new Sample(elapsedNanos, outcome.isSuccess(), code, diagnostics.outerIterations(),
                    diagnostics.innerIterations(), diagnostics.thomasSolves(), diagnostics.propertyPhaseEvaluations());
        }

        String json() {
            return "    {\"elapsedNanos\":" + elapsedNanos + ",\"accepted\":" + accepted
                    + ",\"outcome\":\"" + outcome + "\",\"outerIterations\":" + outerIterations
                    + ",\"innerIterations\":" + innerIterations + ",\"thomasSolves\":" + thomasSolves
                    + ",\"propertyPhaseEvaluations\":" + propertyPhaseEvaluations + "}";
        }
    }

    private record Arguments(int samples, int warmup, Path report) {
        static Arguments parse(String[] args) {
            int samples = 3;
            int warmup = 1;
            Path report = Path.of("build", "reports", "benchmarks", "next-column-report.json");
            for (String argument : args) {
                if (argument.startsWith("--samples=")) samples = positive(argument, "--samples=");
                else if (argument.startsWith("--warmup=")) warmup = nonnegative(argument, "--warmup=");
                else if (argument.startsWith("--report=")) report = Path.of(argument.substring("--report=".length()));
                else throw new IllegalArgumentException("Unknown argument: " + argument);
            }
            return new Arguments(samples, warmup, report);
        }

        private static int positive(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value < 1) throw new IllegalArgumentException(prefix + " must be positive");
            return value;
        }

        private static int nonnegative(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value < 0) throw new IllegalArgumentException(prefix + " must be nonnegative");
            return value;
        }
    }
}
