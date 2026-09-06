package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Cold calculator behaviour of a prescribed pumparound duty on a column that is also stripped with sump steam.
 *
 * <p>Every case is a fresh cold solve exercising the rung order steam, then heat, then draws. The elapsed time,
 * Newton iteration count and recomputed boundary duties are printed so the extra continuation cost of the wet
 * lane stays visible.</p>
 */
class V3PumparoundSteamCalculatorTest {
    private static final long BUDGET_NANOS = 300_000_000_000L;
    /** 1200 kmol/h of superheated sump steam; 271.75 kPa(a) at the sump saturates water near 403 K. */
    private static final double SUMP_STEAM_MOL_PER_SECOND = 1_200.0 * 1_000.0 / 3_600.0;
    private static final double SUMP_STEAM_TEMPERATURE_KELVIN = 533.15;
    private static final int STAGE_COUNT = 30;
    private static final double DEFAULT_REBOILER_DUTY_WATTS = 8_000_000.0;
    private static final String CDU17 = "createcheme:cdu17_tjl_acs2018";
    private static final String TJL19 = "createcheme:tjl19_dwsim";

    /** The heat-free wet reference is the baseline of three cases; one cold solve serves all of them. */
    private static Run wetBaseline;

    @Test
    void sumpSteamOnlyIsTheWetBaseline() {
        V3ColumnOutcome.Success success = wetBaseline().success();

        assertTrue(success.result().problem().hasSteamFeeds());
        assertPassed(success, "GLOBAL_ENERGY_BALANCE");
        assertPassed(success, "WATER_PROFILE");
        V3ColumnDutyLedger ledger = success.result().dutyLedger().orElseThrow();
        assertEquals(0.0, ledger.stageHeatTotalWatts());
        assertEquals(SUMP_STEAM_MOL_PER_SECOND * V3WaterProperties.vaporMolarEnthalpy(SUMP_STEAM_TEMPERATURE_KELVIN),
                ledger.steamEnthalpyWatts(), 1.0e-6 * Math.abs(ledger.steamEnthalpyWatts()));
        assertTrue(ledger.condenserWatts() < 0.0, () -> "condenser duty " + ledger.condenserWatts());
        assertLedgerCloses(success);
    }

    @Test
    void sumpSteamWithOneUniformPumparoundConverges() {
        Run cooled = run("wet-uniform-5-MW", wetInput(CDU17, DEFAULT_REBOILER_DUTY_WATTS,
                List.of(new V3PumparoundSpec(8, 12, -5.0e6, V3PumparoundSpec.Split.UNIFORM)), List.of()));

        V3ColumnOutcome.Success success = cooled.success();
        assertTrue(success.diagnostics().solvePath().contains("/steam-1"), success.diagnostics()::solvePath);
        assertTrue(success.diagnostics().solvePath().contains("/heat-1"), success.diagnostics()::solvePath);
        assertPassed(success, "GLOBAL_ENERGY_BALANCE");
        V3ColumnDutyLedger ledger = success.result().dutyLedger().orElseThrow();
        assertEquals(-5.0e6, ledger.stageHeatTotalWatts(), 1.0);
        assertEquals(5, ledger.stageDuties().size());
        assertLedgerCloses(success);
        double baseCondenser = wetBaseline().success().result().dutyLedger().orElseThrow().condenserWatts();
        double removed = Math.abs(baseCondenser) - Math.abs(ledger.condenserWatts());
        System.out.printf(Locale.ROOT,
                "  wet pumparound: Q_cond0=%.4g MW, Q_cond=%.4g MW, removed %.4g MW of the authored 5 MW%n",
                baseCondenser / 1.0e6, ledger.condenserWatts() / 1.0e6, removed / 1.0e6);
        assertTrue(removed > 0.0 && removed < 5.0e6,
                () -> "the condenser duty must fall by part of the authored 5 MW; measured " + removed + " W");
    }

    @Test
    void sumpSteamCarriesTheColumnWithZeroReboilerDutyAndAPumparound() {
        Run cooled = run("wet-zero-reboiler-5-MW", wetInput(CDU17, 0.0,
                List.of(new V3PumparoundSpec(8, 12, -5.0e6, V3PumparoundSpec.Split.UNIFORM)), List.of()));

        V3ColumnOutcome.Success success = cooled.success();
        V3ColumnDutyLedger ledger = success.result().dutyLedger().orElseThrow();
        assertEquals(0.0, ledger.reboilerWatts());
        assertEquals(-5.0e6, ledger.stageHeatTotalWatts(), 1.0);
        assertPassed(success, "GLOBAL_ENERGY_BALANCE");
        assertLedgerCloses(success);
    }

    @Test
    void sumpSteamWithThreePumparoundsAndThreeSideDrawsExercisesTheWholeRungOrder() {
        List<V3SideDrawSpec> draws = ColumnCalculatorV3BlockEntity.pilotPresetInput().sideDraws();
        Run cooled = run("wet-three-pumparounds-three-draws",
                wetInput(CDU17, DEFAULT_REBOILER_DUTY_WATTS, threePumparounds(), draws));

        V3ColumnOutcome.Success success = cooled.success();
        assertEquals(3, draws.size());
        assertTrue(success.diagnostics().solvePath().contains("/draws-3"), success.diagnostics()::solvePath);
        assertTrue(success.diagnostics().solvePath().contains("/steam-1"), success.diagnostics()::solvePath);
        assertTrue(success.diagnostics().solvePath().contains("/heat-3"), success.diagnostics()::solvePath);
        assertPassed(success, "GLOBAL_ENERGY_BALANCE");
        V3ColumnDutyLedger ledger = success.result().dutyLedger().orElseThrow();
        assertEquals(-15.0e6, ledger.stageHeatTotalWatts(), 1.0);
        assertEquals(12, ledger.stageDuties().size());
        assertLedgerCloses(success);
    }

    @Test
    void tjl19SolvesSumpSteamWithThreePumparounds() {
        Run cooled = run("tjl19-wet-three-pumparounds",
                wetInput(TJL19, DEFAULT_REBOILER_DUTY_WATTS, threePumparounds(), List.of()));

        V3ColumnOutcome.Success success = cooled.success();
        assertTrue(success.diagnostics().solvePath().contains("/steam-1"), success.diagnostics()::solvePath);
        assertTrue(success.diagnostics().solvePath().contains("/heat-3"), success.diagnostics()::solvePath);
        assertPassed(success, "GLOBAL_ENERGY_BALANCE");
        assertTrue(success.result().acceptanceAudit().accepted());
        assertEquals(-15.0e6, success.result().dutyLedger().orElseThrow().stageHeatTotalWatts(), 1.0);
        assertLedgerCloses(success);
    }

    @Test
    void tjl19WithSumpSteamAndSideDrawsStaysInsideTheTypedFailureContract() {
        // The TJL19 package does not reach the requested point on this column when sump steam and side
        // draws are both authored. Measured on the same worktree, the identical input with no pumparound
        // at all fails the same way (steam rung 0.375 stalls at residual 5.3e-2, then the requested rung
        // stalls at 1.0e-2 on a trace component whose liquid flow is 8e-64 mol/s), so this is the known
        // wet side-draw wall and not a stage-heat interaction. The contract, not the outcome, is pinned.
        Run probe = run("tjl19-wet-three-pumparounds-three-draws", wetInput(TJL19, DEFAULT_REBOILER_DUTY_WATTS,
                threePumparounds(), ColumnCalculatorV3BlockEntity.pilotPresetInput().sideDraws()));

        if (probe.outcome() instanceof V3ColumnOutcome.Failure failure) {
            assertEquals(V3SolverFailureCode.NONCONVERGENCE, failure.code(), failure::toString);
            assertTrue(failure.diagnostics().events().stream().anyMatch(event -> event.contains("ramp")),
                    () -> String.valueOf(failure.diagnostics().events()));
        } else {
            assertPassed(probe.success(), "GLOBAL_ENERGY_BALANCE");
            assertLedgerCloses(probe.success());
        }
    }

    @Test
    void wetCoolingAboveTheWetBaseCondenserDutyIsATypedInfeasibility() {
        double wetCondenser = Math.abs(wetBaseline().success().result().dutyLedger().orElseThrow().condenserWatts());
        Run rejected = run("wet-over-condenser-bound", wetInput(CDU17, DEFAULT_REBOILER_DUTY_WATTS,
                List.of(new V3PumparoundSpec(8, 12, -1.02 * wetCondenser, V3PumparoundSpec.Split.UNIFORM)),
                List.of()));

        V3ColumnOutcome.Failure failure = assertInstanceOf(
                V3ColumnOutcome.Failure.class, rejected.outcome(), rejected.outcome()::toString);
        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure.code());
        assertTrue(failure.summary().contains("Q_cond0"), failure::summary);
        assertTrue(failure.diagnostics().solvePath().contains("heat-condenser-bound"),
                failure.diagnostics()::solvePath);
        // The bound must be the wet condenser duty. The dry surrogate seed of the same input carries a
        // replacement boilup and no condenser water, so it would name a different, smaller number.
        assertTrue(failure.summary().contains(String.format(Locale.ROOT, "%.4g", wetCondenser / 1.0e6)),
                () -> failure.summary() + " should name the wet Q_cond0 of " + wetCondenser / 1.0e6 + " MW");
    }

    private static List<V3PumparoundSpec> threePumparounds() {
        return List.of(
                new V3PumparoundSpec(6, 9, -5.0e6, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(13, 16, -6.0e6, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(20, 23, -4.0e6, V3PumparoundSpec.Split.UNIFORM));
    }

    private static synchronized Run wetBaseline() {
        if (wetBaseline == null) {
            wetBaseline = run("wet-baseline", wetInput(CDU17, DEFAULT_REBOILER_DUTY_WATTS, List.of(), List.of()));
        }
        return wetBaseline;
    }

    /**
     * Independent whole-column closure from the published streams only.
     *
     * <p>The duty ledger supplies every inbound term; the product side is rebuilt from the accepted stream
     * compositions with a fresh property session, so the decanted free water and the molecular water-vapor
     * slip must both appear or the closure fails.</p>
     */
    private static void assertLedgerCloses(V3ColumnOutcome.Success success) {
        V3ColumnDutyLedger ledger = success.result().dutyLedger().orElseThrow();
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(
                success.result().problem().input().packageId());
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        int componentCount = success.result().problem().input().componentBasis().componentCount();
        double products = 0.0;
        for (V3ColumnStreamProperties stream : success.result().streams()) {
            products += streamEnthalpyWatts(stream, thermo, workspace, componentCount);
        }
        double supplied = ledger.feedEnthalpyWatts() + ledger.steamEnthalpyWatts() + ledger.reboilerWatts()
                + ledger.stageHeatTotalWatts() + ledger.condenserWatts();
        double largest = Math.max(Math.abs(products), Math.max(Math.abs(ledger.feedEnthalpyWatts()),
                Math.abs(ledger.condenserWatts())));
        double closure = supplied - products;
        System.out.printf(Locale.ROOT,
                "  ledger: feed=%.6g MW steam=%.6g MW reboiler=%.4g MW stage heat=%.4g MW condenser=%.6g MW "
                        + "-> products=%.6g MW; closure=%.4g W (%.2g relative)%n",
                ledger.feedEnthalpyWatts() / 1.0e6, ledger.steamEnthalpyWatts() / 1.0e6,
                ledger.reboilerWatts() / 1.0e6, ledger.stageHeatTotalWatts() / 1.0e6,
                ledger.condenserWatts() / 1.0e6, products / 1.0e6, closure, closure / largest);
        assertTrue(Math.abs(closure) <= 1.0e-6 * largest,
                () -> "wet duty ledger does not close: " + closure + " W against " + largest + " W");
    }

    private static double streamEnthalpyWatts(
            V3ColumnStreamProperties stream, V3PengRobinsonThermo thermo, V3ThermoWorkspace workspace,
            int componentCount) {
        if (stream.streamId().equals("free_water")) {
            return stream.molarFlowMolPerSecond() * V3WaterProperties.liquidMolarEnthalpy(stream.temperatureKelvin());
        }
        List<V3ColumnStreamProperties.ComponentFraction> fractions = stream.moleFractions();
        boolean carriesWater = fractions.size() == componentCount + 1;
        double waterFraction = carriesWater ? fractions.get(componentCount).moleFraction() : 0.0;
        double waterFlow = stream.molarFlowMolPerSecond() * waterFraction;
        double water = waterFlow * V3WaterProperties.vaporMolarEnthalpy(stream.temperatureKelvin());
        double hydrocarbonFlow = stream.molarFlowMolPerSecond() - waterFlow;
        if (!(hydrocarbonFlow > 0.0)) return water;
        double[] composition = new double[componentCount];
        for (int component = 0; component < componentCount; component++) {
            composition[component] = fractions.get(component).moleFraction() / (1.0 - waterFraction);
        }
        V3Phase phase = stream.vaporMoleFraction() == 1.0 ? V3Phase.VAPOR : V3Phase.LIQUID;
        return water + hydrocarbonFlow
                * thermo.molarEnthalpy(stream.temperatureKelvin(), stream.pressurePascal(), composition, phase, workspace);
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
                throw new AssertionError("wet pumparound case " + label + " exceeded its cold budget");
            }
        });
        double seconds = (System.nanoTime() - started) / 1.0e9;
        System.out.printf(Locale.ROOT, "wet pumparound case %-38s %7.2f s  %s%n", label, seconds,
                outcome instanceof V3ColumnOutcome.Success success
                        ? "SUCCESS iter=" + success.diagnostics().newtonIterations()
                                + " residual=" + success.diagnostics().maximumScaledResidual()
                                + " path=" + success.diagnostics().solvePath()
                        : "FAILED " + ((V3ColumnOutcome.Failure) outcome).code()
                                + " maximumScaledResidual="
                                + ((V3ColumnOutcome.Failure) outcome).diagnostics().maximumScaledResidual()
                                + " path=" + ((V3ColumnOutcome.Failure) outcome).diagnostics().solvePath());
        return new Run(label, outcome, seconds);
    }

    private static V3ColumnInput wetInput(
            String packageId, double reboilerDutyWatts, List<V3PumparoundSpec> pumparounds,
            List<V3SideDrawSpec> sideDraws) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 365.0 + 273.15, STAGE_COUNT, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(reboilerDutyWatts)), sideDraws,
                List.of(new V3SteamFeedSpec(STAGE_COUNT + 1, SUMP_STEAM_MOL_PER_SECOND,
                        SUMP_STEAM_TEMPERATURE_KELVIN)), pumparounds);
    }

    private record Run(String label, V3ColumnOutcome outcome, double seconds) {
        V3ColumnOutcome.Success success() {
            return assertInstanceOf(V3ColumnOutcome.Success.class, outcome, () -> label + ": " + outcome);
        }
    }
}
