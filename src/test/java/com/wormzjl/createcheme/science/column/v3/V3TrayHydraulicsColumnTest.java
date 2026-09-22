package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The published column of a request that authors a diameter: the marched profile, its one correction, and the
 * flooding warning.
 *
 * <p>The expected magnitudes are the measured ones of TRAY_PRESSURE_METHOD_REVIEW section 4.4 — the default
 * preset at 8 m runs at about 565 Pa a tray, 22 kPa in total and 69 % of flood — reproduced here through the
 * production publication seam rather than through the study harness.</p>
 */
class V3TrayHydraulicsColumnTest {
    private static final String LITERATURE_PACKAGE = "createcheme:tjl19_dwsim";
    private static final String LITERATURE_ASSAY = "createcheme:tia_juana_light";
    private static final double LITERATURE_FEED_MOL_PER_SECOND = 737.6996333000835;

    @Test
    void theDefaultPresetPublishesItsMarchedProfileAfterOneCorrection() {
        V3ColumnInput input = ColumnCalculatorV3BlockEntity.methaneCduInput();
        assertEquals(V3ColumnInput.DEFAULT_COLUMN_DIAMETER_METRES, input.columnDiameterMetres());
        assertEquals(0.0, input.stagePressureDropPascal(), "the authored nominal is only the first guess");

        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        V3TrayHydraulicsSummary hydraulics = success.result().trayHydraulics().orElseThrow();

        assertTrue(hydraulics.correctionApplied(), "a 0 Pa nominal is 100 % short of the marched profile");
        assertEquals(565.0, hydraulics.meanTrayPressureDropPascal(), 60.0, hydraulics::toString);
        assertEquals(22_000.0, hydraulics.totalPressureDropPascal(), 2_500.0, hydraulics::toString);
        assertEquals(0.69, hydraulics.maximumFloodFraction(), 0.08, hydraulics::toString);
        assertFalse(hydraulics.floods(), hydraulics::toString);
        assertTrue(hydraulics.residualMismatchFraction() < 0.03,
                () -> "residual mismatch " + hydraulics.residualMismatchFraction());
        assertEquals(8.0, hydraulics.columnDiameterMetres());

        // The published profile is the one the state was solved on, and it is the marched one.
        double[] published = success.result().problem().nodePressuresPascal();
        int trays = input.stageCount();
        assertEquals(input.topPressurePascal(), published[0]);
        assertEquals(input.topPressurePascal(), published[1]);
        assertEquals(published[trays], published[trays + 1]);
        assertEquals(hydraulics.totalPressureDropPascal(), published[trays] - published[1], 1.0e-6);
        for (int tray = 2; tray <= trays; tray++) assertTrue(published[tray] >= published[tray - 1]);
        assertTrue(success.diagnostics().solvePath().endsWith("/hyd"), success.diagnostics().solvePath());
        assertTrue(success.diagnostics().events().stream().anyMatch(event -> event.startsWith("tray hydraulics: ")),
                () -> String.join(" | ", success.diagnostics().events()));
        assertTrue(success.result().acceptanceAudit().accepted());
    }

    /**
     * The same column at 5 m publishes, and says which tray floods, by how much, why and what to do about it.
     *
     * <p>Flooding is an operating condition, not a modelling failure: the state is a solution of the MESH
     * system on the profile it was solved on, and only the equipment the player authored is too small for it.</p>
     */
    @Test
    void anUndersizedColumnStillPublishesAndCarriesTheFloodingWarning() {
        V3ColumnInput input = ColumnCalculatorV3BlockEntity.methaneCduInput().withColumnDiameter(5.0);

        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        V3TrayHydraulicsSummary hydraulics = success.result().trayHydraulics().orElseThrow();

        assertTrue(hydraulics.floods(), hydraulics::toString);
        assertTrue(success.result().acceptanceAudit().accepted(), "flooding never fails an acceptance check");
        assertTrue(success.result().acceptanceAudit().checks().stream()
                .noneMatch(check -> check.family().contains("FLOOD")), "flooding is not an audit family");
        String warning = success.diagnostics().acceptanceAudit().advisoryEvidence().stream()
                .filter(advisory -> advisory.contains("of flood")).findFirst()
                .orElseThrow(() -> new AssertionError(String.join(" | ",
                        success.diagnostics().acceptanceAudit().advisoryEvidence())));
        assertTrue(warning.startsWith("Warning: "), warning);
        assertTrue(warning.contains("tray " + hydraulics.maximumFloodTray()), warning);
        assertTrue(warning.contains("dry") && warning.contains("liquid"), warning);
        assertTrue(warning.contains("load is too high"), warning);
        assertTrue(warning.contains("5.0 m"), warning);
        assertTrue(warning.contains("widen the column"), warning);
    }

    /**
     * A nominal that is already inside the tolerance publishes the first solve and spends nothing.
     *
     * <p>The authored drop of the second request is the mean the first one marched, so the marched and the
     * nominal profiles agree and step 3 of the method is skipped. This is the in-world re-solve case.</p>
     */
    @Test
    void aNominalProfileWithinTheToleranceRunsNoCorrection() {
        V3ColumnOutcome first = V3ColumnCalculator.calculate(smallColumn(0.0, 8.0));
        V3TrayHydraulicsSummary marched = assertInstanceOf(V3ColumnOutcome.Success.class, first, first::toString)
                .result().trayHydraulics().orElseThrow();
        assertTrue(marched.correctionApplied());

        V3ColumnOutcome second = V3ColumnCalculator.calculate(
                smallColumn(marched.meanTrayPressureDropPascal(), 8.0));
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, second, second::toString);
        V3TrayHydraulicsSummary hydraulics = success.result().trayHydraulics().orElseThrow();

        assertFalse(hydraulics.correctionApplied(), hydraulics::toString);
        assertTrue(hydraulics.residualMismatchFraction() <= 0.10, hydraulics::toString);
        assertFalse(success.diagnostics().solvePath().endsWith("/hyd"), success.diagnostics().solvePath());
        // The published profile is the authored uniform one; the summary reports the marched reading of it.
        double[] published = success.result().problem().nodePressuresPascal();
        assertEquals(marched.meanTrayPressureDropPascal(), published[2] - published[1], 1.0e-9);
        assertEquals(marched.totalPressureDropPascal(), hydraulics.totalPressureDropPascal(),
                0.15 * marched.totalPressureDropPascal());
    }

    /**
     * A correction that cannot run at all publishes the first solve with a warning, never a failure.
     *
     * <p>The steam here is superheated by exactly 0.01 K against the nominal sump pressure, so every profile
     * with any tray pressure drop at all — the marched one and the half step alike — is refused by the steam
     * admission before a Newton step is taken. That is the end of the method's fallback ladder: publish the
     * accepted state on its authored profile and say why the hydraulic profile was not used.</p>
     */
    @Test
    void aCorrectionThatCannotRunPublishesTheFirstSolveWithAHydraulicMismatchWarning() {
        double marginal = V3WaterProperties.saturationTemperatureKelvin(250_000.0) + 5.01;
        V3ColumnInput input = withSumpSteam(smallColumn(0.0, 8.0), marginal);
        V3ColumnProblemResolver.validateInput(input);

        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        V3TrayHydraulicsSummary hydraulics = success.result().trayHydraulics().orElseThrow();

        assertFalse(hydraulics.correctionApplied(), hydraulics::toString);
        assertTrue(hydraulics.totalPressureDropPascal() > 0.0, "the march still reports what the column would drop");
        double[] published = success.result().problem().nodePressuresPascal();
        for (int tray = 1; tray <= input.stageCount(); tray++) {
            assertEquals(input.topPressurePascal(), published[tray], 0.0, "the authored nominal is published");
        }
        String warning = success.diagnostics().acceptanceAudit().advisoryEvidence().stream()
                .filter(advisory -> advisory.contains("would not re-solve")).findFirst()
                .orElseThrow(() -> new AssertionError(String.join(" | ",
                        success.diagnostics().acceptanceAudit().advisoryEvidence())));
        assertTrue(warning.startsWith("Warning: "), warning);
        assertTrue(warning.contains("kPa"), warning);
        assertTrue(success.result().acceptanceAudit().accepted());
    }

    /** Prescribed-drop mode is the column this calculator has always published, down to the last field. */
    @Test
    void prescribedDropModePublishesNoHydraulicsAndKeepsItsDigest() {
        V3ColumnInput legacy = smallColumn(750.0, V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(legacy);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().trayHydraulics().isEmpty());
        assertFalse(success.diagnostics().solvePath().contains("/hyd"));
        assertTrue(success.diagnostics().events().stream().noneMatch(event -> event.startsWith("tray hydraulics: ")));
        double[] published = success.result().problem().nodePressuresPascal();
        for (int tray = 1; tray <= legacy.stageCount(); tray++) {
            assertEquals(legacy.topPressurePascal() + (tray - 1) * 750.0, published[tray], 0.0);
        }
    }

    @Test
    void theDigestSeparatesADiameterFromThePrescribedDropItWouldOtherwiseShare() {
        V3ColumnInput hydraulic = smallColumn(750.0, 8.0);
        V3ColumnInput legacy = hydraulic.withColumnDiameter(V3ColumnInput.PRESCRIBED_DROP_DIAMETER);

        String hydraulicDigest = digest(hydraulic);
        String legacyDigest = digest(legacy);
        assertNotEquals(legacyDigest, hydraulicDigest);
        assertNotEquals(hydraulicDigest, digest(hydraulic.withColumnDiameter(7.0)));
        // A prescribed-drop request hashes exactly the bytes it always did: the diameter and the correlation
        // revision are only written when a diameter was authored.
        assertEquals(legacyDigest, digest(new V3ColumnInput(legacy.schemaVersion(), legacy.packageId(),
                legacy.assayId(), legacy.componentBasis(), legacy.feedComponentMolarFlowsMolPerSecond(),
                legacy.feedTemperatureKelvin(), legacy.stageCount(), legacy.feedStageNumber(),
                legacy.topPressurePascal(), legacy.stagePressureDropPascal(), legacy.specifications(),
                legacy.sideDraws(), legacy.steamFeeds(), legacy.pumparounds())));
    }

    @Test
    void theShippedPresetsAllAuthorTheDefaultDiameter() {
        MaterialCatalog catalog = MaterialCatalog.bundled();
        for (var preset : catalog.presets().columns().values()) {
            assertEquals(V3ColumnInput.DEFAULT_COLUMN_DIAMETER_METRES,
                    preset.input(catalog).columnDiameterMetres(), preset.descriptor().id());
        }
    }

    private static String digest(V3ColumnInput input) {
        return V3InputDigest.of(V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE),
                V3ColumnCalculator.FORMULATION_REVISION, "test-dataset",
                V3ColumnCalculator.assumptionsRevision(input)).hexadecimalSha256();
    }

    /** A twelve-tray feature-free literature column; cheap enough to solve three times in one test class. */
    private static V3ColumnInput smallColumn(double stagePressureDropPascal, double diameterMetres) {
        V3CrudeFeed crude = V3PengRobinsonThermo.fromRegisteredPackage(LITERATURE_PACKAGE).crudeFeed(LITERATURE_ASSAY);
        double[] feed = crude.moleFractions();
        for (int component = 0; component < feed.length; component++) {
            feed[component] *= LITERATURE_FEED_MOL_PER_SECOND;
        }
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), feed, 633.15, 12, 10, 250_000.0, stagePressureDropPascal,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(3.0),
                        new V3ColumnSpecification.ReboilerDuty(6.0e6)),
                List.of(), List.of(), List.of(), diameterMetres);
    }

    private static V3ColumnInput withSumpSteam(V3ColumnInput input, double temperatureKelvin) {
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                input.specifications(), input.sideDraws(),
                List.of(new V3SteamFeedSpec(input.stageCount() + 1, 40.0, temperatureKelvin)),
                input.pumparounds(), input.columnDiameterMetres());
    }
}
