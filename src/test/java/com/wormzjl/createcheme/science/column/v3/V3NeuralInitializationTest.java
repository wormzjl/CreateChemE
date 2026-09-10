package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V3NeuralInitializationTest {
    private static V3ColumnInput input() {
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl19_dwsim");
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[7] = 50; feed[15] = 50;
        return new V3ColumnInput(1,thermo.packageId(),"test:neural",thermo.componentBasis(),feed,550,2,1,250_000,750,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(300),
                        new V3ColumnSpecification.OrganicRefluxRatio(2),new V3ColumnSpecification.ReboilerDuty(0)));
    }

    private static V3InitializationOptions options(V3InitializationOptions.Mode mode) {
        return new V3InitializationOptions(mode,V3InitializationOptions.WetStart.AUTO,16,5_000);
    }

    @Test void missingModelHonorsForcedModeAndCurrentBackup() {
        V3ColumnInput input = input();
        var forced = V3ColumnCalculator.calculate(input,()->{},0,0,options(V3InitializationOptions.Mode.LNN_ONLY),V3NeuralInitializer.UNAVAILABLE);
        assertEquals(V3SolverFailureCode.INITIALIZATION_FAILURE,assertInstanceOf(V3ColumnOutcome.Failure.class,forced).code());
        var direct = assertInstanceOf(V3ColumnOutcome.Success.class,V3ColumnCalculator.calculate(input));
        var backup = assertInstanceOf(V3ColumnOutcome.Success.class,V3ColumnCalculator.calculate(input,()->{},0,0,
                options(V3InitializationOptions.Mode.LNN_FIRST),V3NeuralInitializer.UNAVAILABLE));
        assertEquals(direct.result().inputDigest(),backup.result().inputDigest());
        assertEquals(direct.result().streams(),backup.result().streams());
        assertTrue(backup.diagnostics().events().getFirst().contains("CURRENT_BACKUP"));
    }

    @Test void forcedCurrentNeverTouchesModelAndMatchesLegacy() {
        V3ColumnInput input = input();
        var model = new V3NeuralInitializer() {
            public String modelId(){throw new AssertionError("CURRENT_ONLY touched model");}
            public Optional<V3NeuralSeed> predict(V3ColumnInput i,V3SolveControl c){throw new AssertionError();}
        };
        var expected = V3ColumnCalculator.calculate(input);
        var actual = V3ColumnCalculator.calculate(input,()->{},0,0,V3InitializationOptions.CURRENT,model);
        assertEquals(expected.diagnostics(),actual.diagnostics());
        assertEquals(((V3ColumnOutcome.Success)expected).result().streams(),((V3ColumnOutcome.Success)actual).result().streams());
    }

    @Test void exactAcceptedSnapshotTransfersThroughNativeCorrection() {
        var captured = new AtomicReference<V3NeuralSeed>();
        var request=input();
        var cold=assertInstanceOf(V3ColumnOutcome.Success.class,V3ColumnCalculator.calculateWithAcceptedProfile(request,()->{},captured::set));
        V3NeuralSeed seed=captured.get(); assertNotNull(seed);
        var predicted=assertInstanceOf(V3ColumnOutcome.Success.class,V3ColumnCalculator.calculate(request,()->{},0,0,
                options(V3InitializationOptions.Mode.LNN_ONLY),fixed(seed)));
        assertTrue(predicted.result().acceptanceAudit().accepted());
        assertTrue(predicted.result().convergenceEvidence().satisfiesGates());
        assertEquals(cold.result().inputDigest(),predicted.result().inputDigest());
        assertTrue(predicted.diagnostics().events().getFirst().contains("initializer=LNN;"));
        double actual=seed.temperatures()[1]; double[] mutable=seed.temperatures(); mutable[1]=1;
        assertEquals(actual,seed.temperatures()[1]);
        var wrong=new V3ColumnInput(request.schemaVersion(),request.packageId(),request.assayId(),request.componentBasis(),
                request.feedComponentMolarFlowsMolPerSecond(),551,request.stageCount(),request.feedStageNumber(),
                request.topPressurePascal(),request.stagePressureDropPascal(),request.specifications());
        assertInstanceOf(V3ColumnOutcome.Failure.class,V3ColumnCalculator.calculate(wrong,()->{},0,0,
                options(V3InitializationOptions.Mode.LNN_ONLY),fixed(seed)));
    }

    @Test void globalCancellationEscapesWithoutBackup() {
        var cancelled=new CancellationException("caller cancelled");
        assertSame(cancelled,assertThrows(CancellationException.class,()->V3ColumnCalculator.calculate(input(),()->{throw cancelled;},0,0,
                V3InitializationOptions.DEFAULT,V3NeuralInitializer.UNAVAILABLE)));
    }

    @Test void predictedWetAndLegacyDryProduceDifferentConsistentLedgers() {
        var base=input();
        var steamInput=new V3ColumnInput(base.schemaVersion(),base.packageId(),base.assayId(),base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(),base.feedTemperatureKelvin(),base.stageCount(),base.feedStageNumber(),
                base.topPressurePascal(),base.stagePressureDropPascal(),base.specifications(),List.of(),
                List.of(new V3SteamFeedSpec(3,10,450)));
        var dry=V3ColumnProblemResolver.resolve(steamInput,V3CondenserPhaseBranch.LIQUID_ONLY);
        int count=base.componentBasis().componentCount();
        double[][] l=new double[4][count],v=new double[4][count];
        for(int n=0;n<4;n++){l[n][7]=30;l[n][15]=30;if(n>0){v[n][7]=20;v[n][15]=20;}}
        var seed=new V3NeuralSeed(steamInput,"test-revision",V3CondenserPhaseBranch.LIQUID_ONLY,l,v,
                new double[]{300,350,400,450},new double[]{0,2,0,0},new boolean[]{false,true,false,false});
        var mask=seed.wetSetFor(dry,V3InitializationOptions.WetStart.PREDICTED_WET);
        var wet=V3ColumnProblemResolver.withTruncation(dry,
                V3TruncationSupport.identity(dry.topology(),dry.activeComponentBasis().componentCount()),mask);
        var retained=mask.seed(wet,seed.stateFor(dry));
        assertEquals(2,retained.freeWaterFlow(1));
        assertEquals(dry.degreeOfFreedomLedger().unknownCount()+1,wet.degreeOfFreedomLedger().unknownCount());
        assertTrue(wet.degreeOfFreedomLedger().isValid());
        var cleared=seed.wetSetFor(dry,V3InitializationOptions.WetStart.DRY_START).seed(dry,seed.stateFor(dry));
        assertEquals(0,cleared.freeWaterFlow(1));
        assertTrue(dry.hasSteamFeeds(),"Dry start must not remove the authored steam");
        boolean[] exposed=seed.wetTrays();exposed[1]=false;
        assertTrue(seed.wetTrays()[1]);
    }

    @Test void localNeuralBudgetDoesNotCancelClassicalBackup() {
        var model=new V3NeuralInitializer() {
            public String modelId(){return "budget-test";}
            public Optional<V3NeuralSeed> predict(V3ColumnInput i,V3SolveControl control){
                while(true)control.checkpoint();
            }
        };
        var policy=new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,V3InitializationOptions.WetStart.AUTO,1,1);
        var result=assertInstanceOf(V3ColumnOutcome.Success.class,V3ColumnCalculator.calculate(input(),()->{},0,0,policy,model));
        assertTrue(result.diagnostics().events().getFirst().contains("budget exhausted"));
    }

    private static V3NeuralInitializer fixed(V3NeuralSeed seed) {
        return new V3NeuralInitializer() {
            public String modelId(){return "accepted-snapshot-test";}
            public Optional<V3NeuralSeed> predict(V3ColumnInput input,V3SolveControl control){control.checkpoint();return Optional.of(seed);}
        };
    }
}
