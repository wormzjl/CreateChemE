package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The two opt-in learned follow-ups: the ramp handoff and the second decode candidate.
 *
 * <p>Both exist because the promoted path leaves coverage on the table that the same frozen weights can
 * reach. Both are off by default, and the first thing these tests assert is that being off means the
 * historical path, not a path that merely happens to agree today.</p>
 *
 * <p>The learned failure these tests hand to the recovery is produced by a cap, not by a clock: an accepted
 * profile displaced off its own solution and corrected under a one-iteration budget fails deterministically
 * and still leaves the terminal iterate the recovery is supposed to pick up.</p>
 */
class V3LearnedRecoveryTest {
    private static final String INPUTS = "/science/column/v3/neural/validation-inputs.jsonl";
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    /** A column the classical route solves, with one small authored side draw, so a ramp exists. */
    private static V3ColumnInput drawInput() {
        return column(8, 4, List.of(new V3SideDrawSpec(6, 2.0)));
    }

    /** The same column with no authored feature at all, so no ramp exists to hand anything to. */
    private static V3ColumnInput plainInput() {
        return column(8, 4, List.of());
    }

    private static V3ColumnInput column(int stages, int feedStage, List<V3SideDrawSpec> draws) {
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl19_dwsim");
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[7] = 50;
        feed[15] = 50;
        return new V3ColumnInput(1, thermo.packageId(), "test:recovery", thermo.componentBasis(), feed, 550,
                stages, feedStage, 250_000, 750,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(300),
                        new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(0)),
                draws, List.of(), List.of());
    }

    private static V3InitializationOptions learned(V3InitializationOptions.Mode mode, int iterations,
            V3InitializationOptions.Recovery recovery) {
        return new V3InitializationOptions(mode, V3InitializationOptions.WetStart.AUTO, iterations, 30_000,
                V3InitializationOptions.Correction.WALLS, recovery);
    }

    /** The handoff's sub-wall is a stated allowance, and a caller that states none gets the default. */
    @Test void theRecoveryBudgetIsStatedOrDefaulted() {
        assertEquals(V3ColumnCalculator.NEURAL_RAMP_HANDOFF_BUDGET_MILLIS,
                V3InitializationOptions.DEFAULT.recoveryBudgetMilliseconds());
        var stated = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY,
                V3InitializationOptions.WetStart.AUTO, 16, 2_000, V3InitializationOptions.Correction.PROGRESS,
                V3InitializationOptions.Recovery.RAMP_HANDOFF, 2_500);
        assertEquals(2_500, stated.recoveryBudgetMilliseconds());
        assertThrows(IllegalArgumentException.class,
                () -> new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY,
                        V3InitializationOptions.WetStart.AUTO, 16, 2_000,
                        V3InitializationOptions.Correction.PROGRESS,
                        V3InitializationOptions.Recovery.RAMP_HANDOFF, 0));
    }

    @Test void theShippedDefaultsCarryNoRecovery() {
        assertEquals(V3InitializationOptions.Recovery.NONE, V3InitializationOptions.DEFAULT.recovery());
        assertEquals(V3InitializationOptions.Recovery.NONE, V3InitializationOptions.CURRENT.recovery());
        // Every historical constructor keeps the historical rule; nothing opts in by omission.
        assertEquals(V3InitializationOptions.Recovery.NONE,
                new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
                        V3InitializationOptions.WetStart.AUTO, 16, 2_000).recovery());
        assertEquals(V3InitializationOptions.Recovery.NONE,
                new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
                        V3InitializationOptions.WetStart.AUTO, 16, 2_000,
                        V3InitializationOptions.Correction.PROGRESS).recovery());
        assertThrows(NullPointerException.class,
                () -> new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
                        V3InitializationOptions.WetStart.AUTO, 16, 2_000,
                        V3InitializationOptions.Correction.PROGRESS, null));
    }

    /**
     * A learned correction that closes by itself is untouched by the recovery rule.
     *
     * <p>The recovery is reached only after the candidate loop has given up, so an accepted correction must
     * publish the same digest, the same streams and the same Newton evidence whichever rule is selected.</p>
     */
    @Test void anAcceptedLearnedCorrectionIsIdenticalUnderEitherRecoveryRule() {
        V3ColumnInput input = drawInput();
        V3NeuralInitializer model = fixed(acceptedProfile(input));
        var none = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_ONLY, 16, V3InitializationOptions.Recovery.NONE), model));
        var handoff = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_ONLY, 16, V3InitializationOptions.Recovery.RAMP_HANDOFF), model));
        assertEquals(none.result().inputDigest(), handoff.result().inputDigest());
        assertEquals(none.result().streams(), handoff.result().streams());
        assertEquals(none.diagnostics().newtonIterations(), handoff.diagnostics().newtonIterations());
        assertEquals(none.diagnostics().solvePath(), handoff.diagnostics().solvePath());
        assertTrue(handoff.diagnostics().events().getFirst().contains("initializer=LNN;"),
                handoff.diagnostics().events().toString());
        assertFalse(handoff.diagnostics().events().getFirst().contains("handoffMs="),
                "an accepted correction never reaches the recovery");
    }

    /**
     * The handoff closes a capped learned correction the classical ramp can close.
     *
     * <p>The learned seed here is displaced far enough that one Newton iteration cannot recover it, which is
     * the shape of the failures the recovery targets: a terminal iterate that is not a solution and is not
     * junk either. Under {@code NONE} the request fails; under {@code RAMP_HANDOFF} the same request reaches
     * the same audited solution the classical route reaches, and says what the handoff cost.</p>
     */
    @Test void theRampHandoffRecoversACappedCorrectionOnAnAuthoredDrawColumn() {
        V3ColumnInput input = drawInput();
        V3NeuralInitializer model = fixed(displaced(acceptedProfile(input)));
        var capped = V3ColumnCalculator.calculate(input, () -> {}, 0, 0,
                learned(V3InitializationOptions.Mode.LNN_ONLY, 1, V3InitializationOptions.Recovery.NONE), model);
        assertEquals(V3SolverFailureCode.INITIALIZATION_FAILURE,
                assertInstanceOf(V3ColumnOutcome.Failure.class, capped).code(),
                "the premise of this test is a learned correction that runs out of iterations");

        var recovered = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_ONLY, 1, V3InitializationOptions.Recovery.RAMP_HANDOFF), model));
        String event = recovered.diagnostics().events().getFirst();
        assertTrue(event.contains("initializer=LNN_RAMP_HANDOFF;"), event);
        assertTrue(event.contains("handoffMs="), event);
        assertTrue(recovered.diagnostics().solvePath().contains("lnn/ramp-handoff/"),
                recovered.diagnostics().solvePath());
        assertTrue(recovered.diagnostics().solvePath().contains("draw-ramp"), recovered.diagnostics().solvePath());
        assertTrue(recovered.result().acceptanceAudit().accepted());
        assertTrue(recovered.result().convergenceEvidence().satisfiesGates());

        // A recovered result is the authored request's own solution, not the surrogate's.
        var classical = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input));
        assertEquals(classical.result().inputDigest(), recovered.result().inputDigest());
    }

    /** Without an authored draw, steam feed or stage heat there is no ramp, so the rule does nothing. */
    @Test void theRampHandoffIsSkippedWhenTheRequestCarriesNoAuthoredFeature() {
        V3ColumnInput input = plainInput();
        V3NeuralInitializer model = fixed(displaced(acceptedProfile(input)));
        var none = assertInstanceOf(V3ColumnOutcome.Failure.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_ONLY, 1, V3InitializationOptions.Recovery.NONE), model));
        var handoff = assertInstanceOf(V3ColumnOutcome.Failure.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_ONLY, 1, V3InitializationOptions.Recovery.RAMP_HANDOFF), model));
        assertEquals(none.code(), handoff.code());
        assertEquals(none.diagnostics().newtonIterations(), handoff.diagnostics().newtonIterations());
        assertEquals(none.diagnostics().maximumScaledResidual(), handoff.diagnostics().maximumScaledResidual());
        assertEquals(none.diagnostics().solvePath(), handoff.diagnostics().solvePath());
        assertFalse(String.join(" | ", handoff.diagnostics().events()).contains("handoffMs="),
                handoff.diagnostics().events().toString());
    }

    /** A seed the model never corrected leaves no terminal state, so there is nothing to hand over. */
    @Test void theRampHandoffIsSkippedWhenNoCorrectionEverRan() {
        V3ColumnInput input = drawInput();
        var failed = assertInstanceOf(V3ColumnOutcome.Failure.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_ONLY, 16, V3InitializationOptions.Recovery.RAMP_HANDOFF),
                V3NeuralInitializer.UNAVAILABLE));
        assertEquals(V3SolverFailureCode.INITIALIZATION_FAILURE, failed.code());
        assertFalse(String.join(" | ", failed.diagnostics().events()).contains("handoffMs="),
                failed.diagnostics().events().toString());
    }

    /**
     * A recovered result is an audited solution of the authored request, and it need not be the classical one.
     *
     * <p>The two routes reach the ramp through different arithmetic — from a failed learned iterate rather
     * than from a cold stage continuation — and on this fixture they land on **different roots** of the same
     * MESH system: the classical route publishes a 67.9 mol/s liquid distillate and the recovered one
     * 10.3 mol/s, both fully certified at closure 1e-8 and both accepted by the independent audit. That is
     * not new behaviour introduced here. The learned route already publishes whichever root its own seed
     * reaches whenever it succeeds, and the classical route's own root is a property of its continuation
     * path; multiplicity on a condenser-temperature, reflux-ratio and reboiler-duty specification is a
     * property of the problem. So what is asserted is what the route actually promises: the authored
     * request's own geometry, the authored draw rate, a passed audit and a real final Newton certificate.</p>
     */
    @Test void theHandoffPublishesAnAuditedSolutionOfTheAuthoredRequest() {
        V3ColumnInput input = drawInput();
        V3NeuralInitializer model = fixed(displaced(acceptedProfile(input)));
        var direct = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input));
        var recovered = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_FIRST, 1, V3InitializationOptions.Recovery.RAMP_HANDOFF), model));
        // The digest is the request's identity and its formulation, so it must match whatever root is found.
        assertEquals(direct.result().inputDigest(), recovered.result().inputDigest());
        assertEquals(direct.result().streams().stream().map(V3ColumnStreamProperties::streamId).toList(),
                recovered.result().streams().stream().map(V3ColumnStreamProperties::streamId).toList());
        var draw = recovered.result().streams().stream()
                .filter(stream -> stream.streamId().startsWith("side_liquid_tray")).findFirst().orElseThrow();
        assertEquals(2.0, draw.molarFlowMolPerSecond(), 1e-9, "the ramp must reach the authored draw rate");
        assertTrue(recovered.result().acceptanceAudit().accepted());
        assertTrue(recovered.result().convergenceEvidence().satisfiesGates());
        String event = recovered.diagnostics().events().getFirst();
        assertTrue(event.contains("handoffMs="), event);
        assertTrue(event.contains("initializer=LNN_RAMP_HANDOFF;") || event.contains("initializer=CURRENT_BACKUP;"), event);
    }

    /**
     * Where the handoff does not apply, {@code LNN_FIRST} still gets its unchanged classical backup.
     *
     * <p>This is the whole risk the rule carries — a recovery that runs before a restart it does not replace
     * — so on a request the rule declines, the restart has to publish the classical solution exactly, down
     * to the streams, and say nothing about a handoff that never happened.</p>
     */
    @Test void aDeclinedHandoffLeavesLnnFirstItsUnchangedClassicalBackup() {
        V3ColumnInput input = plainInput();
        V3NeuralInitializer model = fixed(displaced(acceptedProfile(input)));
        var direct = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input));
        var none = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_FIRST, 1, V3InitializationOptions.Recovery.NONE), model));
        var handoff = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_FIRST, 1, V3InitializationOptions.Recovery.RAMP_HANDOFF), model));
        assertEquals(direct.result().streams(), none.result().streams());
        assertEquals(direct.result().streams(), handoff.result().streams());
        assertEquals(none.diagnostics().solvePath(), handoff.diagnostics().solvePath());
        assertEquals(none.diagnostics().newtonIterations(), handoff.diagnostics().newtonIterations());
        String event = handoff.diagnostics().events().getFirst();
        assertTrue(event.contains("initializer=CURRENT_BACKUP;"), event);
        assertFalse(event.contains("handoffMs="), event);
    }

    /**
     * A progress extension without an early stop must extend, not stop at iteration zero.
     *
     * <p>The solver allocates its residual history when either the stall stop or the extension asks for one,
     * and the stop used to test only whether the window reached back far enough. With the extension on and
     * the stop off the window is zero, so the test compared each residual to itself and declared every
     * attempt stalled on its first iteration. The combination is legal through the public options record and
     * it destroyed the learned route: the campaign arm registered to measure what the abort costs read
     * LNN_ONLY 0 of 330 instead of a number near the baseline's 162.</p>
     */
    @Test void anExtensionWithoutAnEarlyStopStillTakesItsIterations() {
        V3ColumnInput input = drawInput();
        V3NeuralInitializer model = fixed(displaced(acceptedProfile(input)));
        var abortOff = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY,
                V3InitializationOptions.WetStart.AUTO, 16, 30_000,
                new V3InitializationOptions.Correction(8, 48, 8, 0.5, 0, 0.0, 0.0));
        assertFalse(abortOff.correction().stopsEarly());
        assertTrue(abortOff.correction().extendsAttempts());
        var outcome = V3ColumnCalculator.calculate(input, () -> {}, 0, 0, abortOff, model);
        String events = String.join(" | ", outcome.diagnostics().events());
        assertFalse(events.contains("at iteration 0 and"), "the stall stop fired with no stall window: " + events);
        assertTrue(outcome.diagnostics().newtonIterations() > 0 || outcome.isSuccess(),
                "the correction took no iterations at all: " + events);
    }

    /** The bundled production model offers exactly the one seed it predicts. */
    @Test void theBundledModelOffersASingleCandidate() throws Exception {
        var model = V3NeuralModels.bundled();
        assertNotSame(V3NeuralInitializer.UNAVAILABLE, model);
        for (var entry : supportedRequests(model, 8).entrySet()) {
            var candidates = model.candidates(entry.getValue(), V3SolveControl.UNBOUNDED);
            assertEquals(1, candidates.size(), entry.getKey());
            assertEquals(V3BundledTransformerPromotionTest.seedDigest(model.predict(entry.getValue(), V3SolveControl.UNBOUNDED).orElseThrow()),
                    V3BundledTransformerPromotionTest.seedDigest(candidates.getFirst()), entry.getKey());
        }
    }

    /**
     * The second candidate is the same forward pass decoded by the unchanged prune rule.
     *
     * <p>The first offered seed must stay the production seed — a candidate list that reorders itself is a
     * different pipeline, not an additional option — and the second must actually be a different seed on at
     * least one request, or the rule is buying nothing.</p>
     */
    @Test void theDecodeVariantRuleOffersThePruneDecodeSecond() throws Exception {
        var single = V3NeuralModels.bundled();
        var variants = V3NeuralModels.load(
                V3FactorizedNeuralFeatures.DecodeOptions.zeroPhaseFloor(V3NeuralModels.QUALIFIED_ZERO_PHASE_FLOOR_FACTOR),
                V3AnchorTransformerInitializer.CandidateRule.DECODE_VARIANTS);
        assertNotSame(V3NeuralInitializer.UNAVAILABLE, variants);
        assertEquals(single.modelId(), variants.modelId(), "the candidate rule never changes the weights");

        int differing = 0;
        for (var entry : supportedRequests(variants, 24).entrySet()) {
            var offered = variants.candidates(entry.getValue(), V3SolveControl.UNBOUNDED);
            assertEquals(2, offered.size(), entry.getKey());
            String production = V3BundledTransformerPromotionTest.seedDigest(
                    single.predict(entry.getValue(), V3SolveControl.UNBOUNDED).orElseThrow());
            assertEquals(production, V3BundledTransformerPromotionTest.seedDigest(offered.getFirst()),
                    "the production seed must still be tried first for " + entry.getKey());
            assertEquals(offered.getFirst().branch(), offered.getLast().branch(),
                    "both decodes belong to the same predicted branch");
            if (!production.equals(V3BundledTransformerPromotionTest.seedDigest(offered.getLast()))) differing++;
        }
        assertTrue(differing > 0, "the prune decode never differed from the phase-floor decode");
    }

    /** With the prune rule already loaded the two decodes are the same rule, so only one seed is offered. */
    @Test void theDecodeVariantRuleDoesNotOfferTheSameDecodeTwice() throws Exception {
        var model = V3NeuralModels.load(V3FactorizedNeuralFeatures.DecodeOptions.NONE,
                V3AnchorTransformerInitializer.CandidateRule.DECODE_VARIANTS);
        assertNotSame(V3NeuralInitializer.UNAVAILABLE, model);
        for (var entry : supportedRequests(model, 4).entrySet())
            assertEquals(1, model.candidates(entry.getValue(), V3SolveControl.UNBOUNDED).size(), entry.getKey());
    }

    /** The accepted solution of one request, exported through the unchanged classical route. */
    private static V3NeuralSeed acceptedProfile(V3ColumnInput input) {
        var captured = new AtomicReference<V3NeuralSeed>();
        assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {}, captured::set),
                "the recovery tests need a column the classical route solves");
        V3NeuralSeed accepted = captured.get();
        assertNotNull(accepted, "an accepted solve must export its profile");
        return accepted;
    }

    /** The accepted profile, moved off its own solution far enough that one Newton step cannot return. */
    private static V3NeuralSeed displaced(V3NeuralSeed seed) {
        double[][] liquid = seed.liquid(), vapor = seed.vapor();
        double[] temperatures = seed.temperatures();
        for (int n = 0; n < temperatures.length; n++) {
            if (n > 0) temperatures[n] += 12; // node zero carries the prescribed condenser temperature.
            for (int c = 0; c < liquid[n].length; c++) { liquid[n][c] *= 1.5; vapor[n][c] *= 0.7; }
        }
        return new V3NeuralSeed(seed.input(), seed.propertyRevision(), seed.branch(), liquid, vapor, temperatures,
                seed.freeWater(), seed.wetTrays());
    }

    private static V3NeuralInitializer fixed(V3NeuralSeed seed) {
        return new V3NeuralInitializer() {
            public String modelId() { return "learned-recovery-test"; }
            public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
                control.checkpoint();
                return Optional.of(seed);
            }
        };
    }

    /** The first {@code limit} frozen validation requests this model actually covers. */
    private static Map<String, V3ColumnInput> supportedRequests(V3NeuralInitializer model, int limit) throws Exception {
        var result = new LinkedHashMap<String, V3ColumnInput>();
        try (var stream = java.util.Objects.requireNonNull(
                V3LearnedRecoveryTest.class.getResourceAsStream(INPUTS), INPUTS);
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null && result.size() < limit; line = reader.readLine()) {
                if (line.isBlank()) continue;
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                V3ColumnInput request = V3MaterialInputs.migrate(input(row.getAsJsonObject("input")), com.wormzjl.createcheme.science.material.MaterialCatalog.bundled());
                if (model.predict(request, V3SolveControl.UNBOUNDED).isPresent())
                    result.put(row.get("id").getAsString(), request);
            }
        }
        assertEquals(limit, result.size(), "the frozen population must cover this model");
        return result;
    }

    /** The frozen population's input schema, as every offline probe in this line reads it. */
    private static V3ColumnInput input(JsonObject json) {
        var specs = new ArrayList<V3ColumnSpecification>();
        for (JsonElement e : json.getAsJsonArray("specifications")) {
            JsonObject s = e.getAsJsonObject();
            if (s.has("kelvin")) specs.add(new V3ColumnSpecification.CondenserOutletTemperature(s.get("kelvin").getAsDouble()));
            else if (s.has("ratio")) specs.add(new V3ColumnSpecification.OrganicRefluxRatio(s.get("ratio").getAsDouble()));
            else specs.add(new V3ColumnSpecification.ReboilerDuty(s.get("watts").getAsDouble()));
        }
        return new V3ColumnInput(json.get("schemaVersion").getAsInt(), json.get("packageId").getAsString(),
                json.get("assayId").getAsString(),
                new V3ComponentBasis(Arrays.asList(JSON.fromJson(json.getAsJsonObject("componentBasis").get("componentIds"), String[].class))),
                JSON.fromJson(json.get("feedComponentMolarFlowsMolPerSecond"), double[].class),
                json.get("feedTemperatureKelvin").getAsDouble(),
                json.get("stageCount").getAsInt(), json.get("feedStageNumber").getAsInt(),
                json.get("topPressurePascal").getAsDouble(), json.get("stagePressureDropPascal").getAsDouble(), specs,
                Arrays.asList(JSON.fromJson(json.get("sideDraws"), V3SideDrawSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("steamFeeds"), V3SteamFeedSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("pumparounds"), V3PumparoundSpec[].class)));
    }
}
