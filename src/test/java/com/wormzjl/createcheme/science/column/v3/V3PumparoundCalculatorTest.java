package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Cold calculator behaviour of a prescribed pumparound duty.
 *
 * <p>Every case is a fresh cold solve with no warm state; the elapsed time of each is printed so the extra
 * continuation rungs stay visible.</p>
 */
class V3PumparoundCalculatorTest {
    private static final long BUDGET_NANOS = 180_000_000_000L;

    @Test
    void revisionsGainAStageHeatVariantOnlyWhenAPumparoundIsPresent() {
        V3ColumnInput heatFree = v1ScaleInput("createcheme:cdu17_tjl_acs2018", List.of());
        V3ColumnInput heated = v1ScaleInput("createcheme:cdu17_tjl_acs2018",
                List.of(new V3PumparoundSpec(8, 12, -5.0e6, V3PumparoundSpec.Split.UNIFORM)));

        assertEquals("v3-dry-mesh-r9", V3ColumnCalculator.formulationRevision(heatFree, 0.0));
        assertEquals("v3-dry-mesh-r10-flash-trace", V3ColumnCalculator.formulationRevision(heatFree, 1.0e-6));
        assertEquals("v3-dry-mesh-r13-stage-heat", V3ColumnCalculator.formulationRevision(heated, 0.0));
        assertEquals("v3-dry-mesh-r13-flash-trace-stage-heat", V3ColumnCalculator.formulationRevision(heated, 1.0e-6));
        assertEquals(V3ColumnCalculator.ASSUMPTIONS_REVISION, V3ColumnCalculator.assumptionsRevision(heatFree));
        assertEquals(V3ColumnCalculator.ASSUMPTIONS_REVISION + "+" + V3ColumnCalculator.HEAT_ASSUMPTIONS_REVISION,
                V3ColumnCalculator.assumptionsRevision(heated));
        assertEquals(List.of(), V3ColumnCalculator.withStageGeometry(heated, 8).pumparounds());
    }

    @Test
    void aOneKilowattPumparoundReproducesTheHeatFreeProductStreams() {
        Run base = run("base", v1ScaleInput("createcheme:cdu17_tjl_acs2018", List.of()));
        Run perturbed = run("minus-1-kW", v1ScaleInput("createcheme:cdu17_tjl_acs2018",
                List.of(new V3PumparoundSpec(8, 12, -1_000.0, V3PumparoundSpec.Split.UNIFORM))));

        V3ColumnOutcome.Success baseSuccess = base.success();
        V3ColumnOutcome.Success success = perturbed.success();
        assertTrue(success.diagnostics().solvePath().contains("/heat-1"), success.diagnostics()::solvePath);
        assertEquals(baseSuccess.result().streams().size(), success.result().streams().size());
        for (int index = 0; index < baseSuccess.result().streams().size(); index++) {
            V3ColumnStreamProperties expected = baseSuccess.result().streams().get(index);
            V3ColumnStreamProperties actual = success.result().streams().get(index);
            assertEquals(expected.streamId(), actual.streamId());
            assertEquals(expected.molarFlowMolPerSecond(), actual.molarFlowMolPerSecond(),
                    1.0e-3 * expected.molarFlowMolPerSecond(), expected::streamId);
            assertEquals(expected.temperatureKelvin(), actual.temperatureKelvin(), 0.05, expected::streamId);
        }
        assertEquals(baseSuccess.result().dutyLedger().orElseThrow().condenserWatts(),
                success.result().dutyLedger().orElseThrow().condenserWatts(),
                0.02 * Math.abs(baseSuccess.result().dutyLedger().orElseThrow().condenserWatts()));
    }

    @Test
    void uniformFiveMegawattPumparoundRemovesItsDutyFromTheCondenser() {
        Run base = run("base", v1ScaleInput("createcheme:cdu17_tjl_acs2018", List.of()));
        Run cooled = run("uniform-5-MW", v1ScaleInput("createcheme:cdu17_tjl_acs2018",
                List.of(new V3PumparoundSpec(8, 12, -5.0e6, V3PumparoundSpec.Split.UNIFORM))));

        V3ColumnDutyLedger ledger = cooled.success().result().dutyLedger().orElseThrow();
        double baseCondenser = base.success().result().dutyLedger().orElseThrow().condenserWatts();
        double removed = Math.abs(baseCondenser) - Math.abs(ledger.condenserWatts());
        System.out.printf(Locale.ROOT,
                "uniform pumparound: Q_cond0=%.4g MW, Q_cond=%.4g MW, stage heat=%.4g MW, condenser share %.1f%%%n",
                baseCondenser / 1.0e6, ledger.condenserWatts() / 1.0e6, ledger.stageHeatTotalWatts() / 1.0e6,
                100.0 * removed / 5.0e6);
        assertEquals(-5.0e6, ledger.stageHeatTotalWatts(), 1.0);
        assertEquals(5, ledger.stageDuties().size());
        assertPassed(cooled.success(), "GLOBAL_ENERGY_BALANCE");
        // Measured: 3.82 of the 5 MW leave the condenser and the remaining 1.18 MW leaves with the colder
        // products. The whole-column closure is checked exactly by GLOBAL_ENERGY_BALANCE above.
        assertTrue(removed > 0.6 * 5.0e6 && removed < 5.0e6,
                () -> "condenser duty removed " + removed + " W of the authored 5 MW");
    }

    @Test
    void returnTraySplitMatchesTheUniformSplitCondenserDuty() {
        Run uniform = run("uniform-5-MW", v1ScaleInput("createcheme:cdu17_tjl_acs2018",
                List.of(new V3PumparoundSpec(8, 12, -5.0e6, V3PumparoundSpec.Split.UNIFORM))));
        Run returnTray = run("return-tray-5-MW", v1ScaleInput("createcheme:cdu17_tjl_acs2018",
                List.of(new V3PumparoundSpec(8, 12, -5.0e6, V3PumparoundSpec.Split.RETURN_TRAY))));

        V3ColumnDutyLedger ledger = returnTray.success().result().dutyLedger().orElseThrow();
        assertPassed(returnTray.success(), "GLOBAL_ENERGY_BALANCE");
        assertEquals(1, ledger.stageDuties().size());
        assertEquals(8, ledger.stageDuties().get(0).trayNumber());
        assertEquals(-5.0e6, ledger.stageDuties().get(0).dutyWatts(), 1.0);
        double uniformCondenser = uniform.success().result().dutyLedger().orElseThrow().condenserWatts();
        System.out.printf(Locale.ROOT, "return-tray pumparound: Q_cond=%.4g MW versus uniform %.4g MW%n",
                ledger.condenserWatts() / 1.0e6, uniformCondenser / 1.0e6);
        assertEquals(uniformCondenser, ledger.condenserWatts(), 0.02 * Math.abs(uniformCondenser));
    }

    @Test
    void impossibleCoolingIsRejectedByTheStaticAdmissionGateWithoutASolve() {
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(v1ScaleInput("createcheme:cdu17_tjl_acs2018",
                List.of(new V3PumparoundSpec(8, 12, -200.0e6, V3PumparoundSpec.Split.UNIFORM))));
        System.out.printf(Locale.ROOT, "static gate: %.3f s; %s%n", (System.nanoTime() - started) / 1.0e9, outcome);

        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome, outcome::toString);
        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure.code());
        assertTrue(failure.summary().contains("exceeds"), failure::summary);
        assertEquals("input/heat-1", failure.diagnostics().solvePath());
        assertEquals(0, failure.diagnostics().newtonIterations());
    }

    @Test
    void coolingAboveTheBaseCondenserDutyIsRejectedWithThatDutyNamed() {
        Run base = run("base", v1ScaleInput("createcheme:cdu17_tjl_acs2018", List.of()));
        double baseCondenser = Math.abs(base.success().result().dutyLedger().orElseThrow().condenserWatts());
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(v1ScaleInput("createcheme:cdu17_tjl_acs2018",
                List.of(new V3PumparoundSpec(8, 12, -1.02 * baseCondenser, V3PumparoundSpec.Split.UNIFORM))));
        System.out.printf(Locale.ROOT, "condenser-bound gate: %.3f s; Q_cond0=%.4g MW; %s%n",
                (System.nanoTime() - started) / 1.0e9, baseCondenser / 1.0e6, outcome);

        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome, outcome::toString);
        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure.code());
        assertTrue(failure.summary().contains("Q_cond0"), failure::summary);
        assertTrue(failure.summary().contains(String.format(Locale.ROOT, "%.4g", baseCondenser / 1.0e6)),
                failure::summary);
    }

    @Test
    void threeDefaultSideDrawsAndOnePumparoundExerciseTheWholeRungOrder() {
        V3ColumnInput preset = ColumnCalculatorV3BlockEntity.pilotPresetInput();
        V3ColumnInput heated = new V3ColumnInput(preset.schemaVersion(), preset.packageId(), preset.assayId(),
                preset.componentBasis(), preset.feedComponentMolarFlowsMolPerSecond(), preset.feedTemperatureKelvin(),
                preset.stageCount(), preset.feedStageNumber(), preset.topPressurePascal(), preset.stagePressureDropPascal(),
                preset.specifications(), preset.sideDraws(), List.of(),
                List.of(new V3PumparoundSpec(4, 8, -3.0e6, V3PumparoundSpec.Split.UNIFORM)));
        Run cooled = run("preset-draws-plus-pumparound", heated);

        assertEquals(3, preset.sideDraws().size());
        assertTrue(cooled.success().diagnostics().solvePath().contains("/draws-3"),
                cooled.success().diagnostics()::solvePath);
        assertTrue(cooled.success().diagnostics().solvePath().contains("/heat-1"),
                cooled.success().diagnostics()::solvePath);
        assertPassed(cooled.success(), "GLOBAL_ENERGY_BALANCE");
        assertEquals(-3.0e6, cooled.success().result().dutyLedger().orElseThrow().stageHeatTotalWatts(), 1.0);
    }

    @Test
    void aLargeSingleTrayDutyStaysWithinTheTypedFailureContract() {
        // Exercises the per-tray condensation cap and the bounded midpoint subdivision it triggers. The
        // outcome is deliberately not pinned: only the typed contract is.
        Run probe = run("return-tray-40-MW", v1ScaleInput("createcheme:cdu17_tjl_acs2018",
                List.of(new V3PumparoundSpec(8, 12, -40.0e6, V3PumparoundSpec.Split.RETURN_TRAY))));

        if (probe.outcome() instanceof V3ColumnOutcome.Failure failure) {
            assertTrue(failure.code() == V3SolverFailureCode.INFEASIBLE_SPECIFICATION
                    || failure.code() == V3SolverFailureCode.NONCONVERGENCE
                    || failure.code() == V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE, failure::toString);
        } else {
            assertPassed(probe.success(), "GLOBAL_ENERGY_BALANCE");
        }
    }

    @Test
    void tjl19PackageSolvesThirtyStagesWithOnePumparound() {
        Run cooled = run("tjl19-uniform-5-MW", v1ScaleInput("createcheme:tjl19_dwsim",
                List.of(new V3PumparoundSpec(8, 12, -5.0e6, V3PumparoundSpec.Split.UNIFORM))));

        assertPassed(cooled.success(), "GLOBAL_ENERGY_BALANCE");
        assertEquals(-5.0e6, cooled.success().result().dutyLedger().orElseThrow().stageHeatTotalWatts(), 1.0);
        assertTrue(cooled.success().result().acceptanceAudit().accepted());
    }

    private static void assertPassed(V3ColumnOutcome.Success success, String family) {
        V3AcceptanceAudit.Check check = success.result().acceptanceAudit().checks().stream()
                .filter(candidate -> candidate.family().equals(family)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + family + " check: "
                        + success.result().acceptanceAudit().checks()));
        assertTrue(check.passed(), check::toString);
    }

    private static Run run(String label, V3ColumnInput input) {
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started >= BUDGET_NANOS) {
                throw new AssertionError("pumparound case " + label + " exceeded its cold budget");
            }
        });
        double seconds = (System.nanoTime() - started) / 1.0e9;
        System.out.printf(Locale.ROOT, "pumparound case %-30s %6.2f s  %s%n", label, seconds,
                outcome instanceof V3ColumnOutcome.Success success
                        ? "SUCCESS iter=" + success.diagnostics().newtonIterations()
                                + " residual=" + success.diagnostics().maximumScaledResidual()
                                + " path=" + success.diagnostics().solvePath()
                        : outcome.toString());
        return new Run(label, outcome, seconds);
    }

    private static V3ColumnInput v1ScaleInput(String packageId, List<V3PumparoundSpec> pumparounds) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 365.0 + 273.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), List.of(), List.of(), pumparounds);
    }

    private record Run(String label, V3ColumnOutcome outcome, double seconds) {
        V3ColumnOutcome.Success success() {
            return assertInstanceOf(V3ColumnOutcome.Success.class, outcome, () -> label + ": " + outcome);
        }
    }
}
