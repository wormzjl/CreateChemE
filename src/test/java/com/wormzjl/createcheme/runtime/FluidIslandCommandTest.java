package com.wormzjl.createcheme.runtime;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.wormzjl.createcheme.runtime.fluid.*;

class FluidIslandCommandTest {
    @Test void aSoftTimeoutCanProduceAUsefulConservativeApproximationAndThenRecover() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,350,150000,()->{}),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,350,149000,()->{}))),List.of(new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(100,.01,.000045,0))));
        var warm=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});graph=warm.graph();
        var anchor=ApproximationAnchor.fromFull(model,warm);var policy=FluidFallbackPolicy.active(anchor,FallbackAllowance.NONE,100,1);
        var command=new ProcessSolveServices.FluidIslandCommand(model,graph,5,PassiveIntervalSolver.Settings.defaults(),1_000_000,policy);
        var clock=new AtomicLong();var result=assertInstanceOf(ProcessSolveServices.FluidIslandSolveResult.class,command.solve(token(false),clock::getAndIncrement));
        assertTrue(result.candidate().isPresent(),result.detail());assertEquals(PassiveStepSolver.Acceptance.APPROXIMATE,result.candidate().orElseThrow().acceptance());
        assertTrue(result.candidate().orElseThrow().averageMassFlows()[0]>0);assertEquals(1,result.proposedAllowance().acceptedIntervals());assertEquals(anchor,result.proposedAnchor().orElseThrow());
        var reference=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(reference.averageMassFlows()[0],result.candidate().orElseThrow().averageMassFlows()[0],.02*reference.averageMassFlows()[0]);
        var current=result.candidate().orElseThrow().graph();var recovery=new ProcessSolveServices.FluidIslandCommand(model,current,5,PassiveIntervalSolver.Settings.defaults(),1_000_000,
                FluidFallbackPolicy.active(anchor,result.proposedAllowance(),100,1));
        var recovered=assertInstanceOf(ProcessSolveServices.FluidIslandSolveResult.class,recovery.solve(token(false),()->0));
        assertEquals("FULL",recovered.detail());assertEquals(FallbackAllowance.NONE,recovered.proposedAllowance());
        assertTrue(recovered.candidate().orElseThrow().graph().reservoirs().get(1).inventory().moles()[20]>=current.reservoirs().get(1).inventory().moles()[20]);
    }
    private ProcessSolveServices.FluidIslandCommand command() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        var state=model.initialNitrogenCharge(1,298.15,101325,()->{});
        return new ProcessSolveServices.FluidIslandCommand(model,new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state)),List.of()),5,PassiveIntervalSolver.Settings.defaults(),100);
    }
    private BoundedCpuSolveService.CancellationToken token(boolean cancelled) {
        return new BoundedCpuSolveService.CancellationToken(){
            public long deadlineNanos(){return Long.MAX_VALUE;}
            public boolean isDeadlineExceeded(){return false;}
            public boolean isCancellationRequested(){return cancelled;}
            public void throwIfCancellationRequested(){if(cancelled)throw new CancellationException("test cancellation");}
        };
    }
    @Test void localWorkerDeadlineReturnsAHeldCandidateWithoutAdvancingTheSnapshot() {
        var command=command();var time=new AtomicLong();
        var result=assertInstanceOf(ProcessSolveServices.FluidIslandSolveResult.class,command.solve(token(false),()->time.getAndAdd(100)));
        assertTrue(result.candidate().isEmpty());assertTrue(result.detail().contains("wall deadline"));assertEquals(1,command.snapshot().reservoirs().getFirst().state().volume(),1e-12);
    }
    @Test void cancellationPropagatesAndSuccessfulIdleIntervalsRemainImmutable() {
        var command=command();assertThrows(CancellationException.class,()->command.solve(token(true),()->0));
        var result=assertInstanceOf(ProcessSolveServices.FluidIslandSolveResult.class,command.solve(token(false),()->0));
        assertEquals(5,result.candidate().orElseThrow().advancedSeconds());assertEquals(command.snapshot(),result.candidate().orElseThrow().graph());
    }
    @Test void oldHydraulicAnchorCannotTriggerSoftFallbackAndIsRenewedOnlyByAFullSolve() {
        var base=command();var model=base.model();var full=new PassiveIntervalSolver(model).solve(base.snapshot(),5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var old=new ApproximationAnchor(ApproximationAnchor.thermodynamicRevision(model),full.graph(),full.endpointModes());
        var allowance=new FallbackAllowance(2,100,100);var policy=FluidFallbackPolicy.active(old,allowance,100,1);
        var changed=new ProcessSolveServices.FluidIslandCommand(model,full.graph(),5,PassiveIntervalSolver.Settings.defaults(),1_000_000,policy);
        var clock=new AtomicLong();var result=(ProcessSolveServices.FluidIslandSolveResult)changed.solve(token(false),clock::getAndIncrement);
        assertEquals("FULL",result.detail());assertEquals(FallbackAllowance.NONE,result.proposedAllowance());
        assertEquals(ApproximationAnchor.revision(model),result.proposedAnchor().orElseThrow().propertyRevision());
    }
}
