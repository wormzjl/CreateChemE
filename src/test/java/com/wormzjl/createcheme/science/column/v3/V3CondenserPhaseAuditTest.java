package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class V3CondenserPhaseAuditTest {
    @Test
    void independentlyAcceptsTheManufacturedTwoPhaseSplit() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);

        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), fixture.thermo(), 0.0)
                .audit(fixture.exact(), fixture.thermo().newWorkspace());

        assertTrue(phaseCheck(audit).passed(), phaseCheck(audit)::toString);
        assertTrue(audit.accepted(), audit::toString);
    }

    @Test
    void rejectsATwoPhaseMeshWhenTheIndependentFlashIsLiquid() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3ThermoModel thermo = withFlash(fixture.thermo(), new V3FlashResult(V3FeedPhase.LIQUID, 0, 0.0,
                new double[] {0.6, 0.0, 0.4, 0.0}, new double[0], 0.0, "manufactured liquid override"));

        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), thermo, 0.0)
                .audit(fixture.exact(), thermo.newWorkspace());

        assertFalse(phaseCheck(audit).passed());
        assertFalse(audit.accepted());
        assertTrue(audit.checks().stream().filter(check -> !check.family().equals("CONDENSER_PHASE"))
                .allMatch(V3AcceptanceAudit.Check::passed));
    }

    @Test
    void rejectsAFlashWhosePhaseFractionsDoNotMatchTheSolvedComponentFlows() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3FlashResult inconsistent = new V3FlashResult(V3FeedPhase.TWO_PHASE, 1, 0.5,
                new double[] {0.6, 0.0, 0.4, 0.0}, new double[] {0.6, 0.0, 0.4, 0.0}, 0.0,
                "manufactured inconsistent split");
        V3ThermoModel thermo = withFlash(fixture.thermo(), inconsistent);

        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), thermo, 0.0)
                .audit(fixture.exact(), thermo.newWorkspace());

        assertFalse(phaseCheck(audit).passed());
        assertFalse(audit.accepted());
    }

    private static V3AcceptanceAudit.Check phaseCheck(V3AcceptanceAudit audit) {
        return audit.checks().stream().filter(check -> check.family().equals("CONDENSER_PHASE"))
                .findFirst().orElseThrow();
    }

    @Test
    void rejectsIncorrectComponentPartitionEvenWhenOverallCompositionAndBetaAgree() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3FlashResult wrongPartition = new V3FlashResult(V3FeedPhase.TWO_PHASE, 1, 1.0 / 3.0,
                new double[] {0.6, 0.0, 0.4, 0.0}, new double[] {0.6, 0.0, 0.4, 0.0}, 0.0,
                "same overall composition and beta, different component partition");
        V3ThermoModel thermo = withFlash(fixture.thermo(), wrongPartition);

        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), thermo, 0.0)
                .audit(fixture.exact(), thermo.newWorkspace());

        assertFalse(phaseCheck(audit).passed());
    }

    @Test
    void independentlyRejectsAWetCondenserPartitionThatTheWetResidualWouldOtherwiseShare() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3ColumnProblem wetProblem = wetProblem(fixture, 150_000.0);
        V3ThermoModel wetThermo = withWetEquilibrium(fixture, wetProblem);
        V3AcceptanceAudit exactAudit = new V3AcceptanceAuditor(wetProblem, wetThermo, 0.0)
                .audit(fixture.exact(), wetThermo.newWorkspace());
        assertTrue(phaseCheck(exactAudit).passed(), phaseCheck(exactAudit)::toString);

        double[][] liquid = V3TruncationNumericsTest.copyFlows(fixture.exact(), true);
        double[][] vapor = V3TruncationNumericsTest.copyFlows(fixture.exact(), false);
        liquid[0][0] = 12.0;
        liquid[0][1] = 8.0;
        vapor[0][0] = 6.0;
        vapor[0][1] = 4.0;
        V3DryMeshState wrongPartition = new V3DryMeshState(wetProblem.topology(), 3, liquid, vapor,
                V3TruncationNumericsTest.temperatures());
        V3AcceptanceAudit wrongAudit = new V3AcceptanceAuditor(wetProblem, wetThermo, 0.0)
                .audit(wrongPartition, wetThermo.newWorkspace());

        assertFalse(phaseCheck(wrongAudit).passed());
    }

    @Test
    void waterLimitedOverheadRetainsAllSteamAsMixedVaporInsteadOfMakingNegativeFreeWater() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3ColumnProblem wetProblem = wetProblem(fixture, 250_000.0);
        V3ThermoModel wetThermo = withWetEquilibrium(fixture, wetProblem);

        V3AcceptanceAudit audit = new V3AcceptanceAuditor(wetProblem, wetThermo, 0.0)
                .audit(fixture.exact(), wetThermo.newWorkspace());
        V3AcceptanceAudit.Check freeWater = audit.checks().stream()
                .filter(check -> check.family().equals("FREE_WATER_SPLIT")).findFirst().orElseThrow();

        assertTrue(freeWater.passed());
        assertEquals(0.0, freeWater.value());
        V3ColumnProblem.WaterCondenserSplit split = wetProblem.waterCondenserSplit(fixture.exact());
        assertEquals(wetProblem.waterVaporFlowMolPerSecond(1), split.vaporFlowMolPerSecond());
        assertEquals(0.0, split.freeWaterFlowMolPerSecond());
    }

    @Test
    void cancellationAfterTheIndependentFlashEscapesUnchanged() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        AtomicBoolean flashed = new AtomicBoolean();
        CancellationException cancelled = new CancellationException("audit cancelled after flash");
        V3ThermoModel thermo = new V3ThermoModel() {
            @Override public V3ComponentBasis componentBasis() { return fixture.thermo().componentBasis(); }
            @Override public V3ThermoWorkspace newWorkspace() { return fixture.thermo().newWorkspace(); }
            @Override public V3FugacityResult fugacity(double t, double p, double[] z, V3Phase phase, V3ThermoWorkspace workspace) {
                return fixture.thermo().fugacity(t, p, z, phase, workspace);
            }
            @Override public double molarEnthalpy(double t, double p, double[] z, V3Phase phase, V3ThermoWorkspace workspace) {
                return fixture.thermo().molarEnthalpy(t, p, z, phase, workspace);
            }
            @Override public V3FlashResult flashTP(double t, double p, double[] z, V3ThermoWorkspace workspace) {
                flashed.set(true);
                return fixture.thermo().flashTP(t, p, z, workspace);
            }
        };
        assertSame(cancelled, assertThrows(CancellationException.class, () ->
                new V3AcceptanceAuditor(fixture.problem(), thermo, 0.0).audit(fixture.exact(), thermo.newWorkspace(), () -> {
                    if (flashed.get()) throw cancelled;
                })));
    }

    private static V3ThermoModel withFlash(V3ThermoModel delegate, V3FlashResult flash) {
        return new V3ThermoModel() {
            @Override public V3ComponentBasis componentBasis() { return delegate.componentBasis(); }
            @Override public V3ThermoWorkspace newWorkspace() { return delegate.newWorkspace(); }
            @Override public V3FugacityResult fugacity(double t, double p, double[] z, V3Phase phase, V3ThermoWorkspace workspace) {
                return delegate.fugacity(t, p, z, phase, workspace);
            }
            @Override public double molarEnthalpy(double t, double p, double[] z, V3Phase phase, V3ThermoWorkspace workspace) {
                return delegate.molarEnthalpy(t, p, z, phase, workspace);
            }
            @Override public V3FlashResult flashTP(double t, double p, double[] z, V3ThermoWorkspace workspace) { return flash; }
        };
    }

    private static V3ColumnProblem wetProblem(V3TruncationNumericsTest.Fixture fixture, double topPressurePascal) {
        V3ColumnInput dry = fixture.original().input();
        V3ColumnInput wet = new V3ColumnInput(dry.schemaVersion(), dry.packageId(), dry.assayId(), dry.componentBasis(),
                dry.feedComponentMolarFlowsMolPerSecond(), dry.feedTemperatureKelvin(), dry.stageCount(), dry.feedStageNumber(),
                topPressurePascal, dry.stagePressureDropPascal(), dry.specifications(), dry.sideDraws(),
                List.of(new V3SteamFeedSpec(dry.stageCount() + 1, 4.0, 450.0)));
        V3ColumnProblem untruncated = V3ColumnProblemResolver.resolve(wet, V3CondenserPhaseBranch.TWO_PHASE);
        return V3ColumnProblemResolver.withTruncation(untruncated, fixture.problem().truncationSupport());
    }

    private static V3ThermoModel withWetEquilibrium(
            V3TruncationNumericsTest.Fixture fixture, V3ColumnProblem wetProblem) {
        return new V3ThermoModel() {
            @Override public V3ComponentBasis componentBasis() { return fixture.thermo().componentBasis(); }
            @Override public V3ThermoWorkspace newWorkspace() { return fixture.thermo().newWorkspace(); }

            @Override
            public V3FugacityResult fugacity(double temperature, double pressure, double[] composition,
                                              V3Phase phase, V3ThermoWorkspace workspace) {
                V3FugacityResult result = fixture.thermo().fugacity(temperature, pressure, composition, phase, workspace);
                if (phase != V3Phase.LIQUID) return result;
                int node = (int) Math.round((temperature - 400.0) / 10.0);
                double hydrocarbon = 0.0;
                for (int component = 0; component < fixture.exact().componentCount(); component++) {
                    hydrocarbon += fixture.exact().vaporFlow(node, component);
                }
                double water = node == wetProblem.topology().condenserNode()
                        ? wetProblem.waterCondenserSplit(fixture.exact()).vaporFlowMolPerSecond()
                        : wetProblem.waterVaporFlowMolPerSecond(node);
                double[] logPhi = result.logFugacityCoefficients();
                double dilution = Math.log(hydrocarbon / (hydrocarbon + water));
                for (int component = 0; component < logPhi.length; component++) logPhi[component] += dilution;
                return new V3FugacityResult(phase, logPhi, result.compressibilityFactor(),
                        result.molarEnthalpyJoulesPerMol(), result.physicalRootCount(), result.rootSeparation());
            }

            @Override public double molarEnthalpy(double t, double p, double[] z, V3Phase phase, V3ThermoWorkspace workspace) {
                return fixture.thermo().molarEnthalpy(t, p, z, phase, workspace);
            }

            @Override public V3FlashResult flashTP(double t, double p, double[] z, V3ThermoWorkspace workspace) {
                return fixture.thermo().flashTP(t, p, z, workspace);
            }
        };
    }
}
