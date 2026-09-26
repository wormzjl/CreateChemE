package com.wormzjl.createcheme.runtime;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F4 end to end, through the real solver and the real coordinator: a placed nitrogen transfer (tank, compressor, pipe,
 * tank; the pump it was refuses gas since the phase-ports batch, decisions D1/D2) whose nitrogen record is narrowed to
 * 298.05 K, so the suction's cooling (a few kelvin at the compressor's ratio 1.05; the pump's density-scaled limit let it
 * cool 0.24 K) leaves the property domain. The solver refuses those trials under the thermo-domain key and fails the interval on the violation;
 * the island's status is the dedicated line naming the component, the value, the bound and the device; the world is told
 * once; the fresh-solver retry reproduces it and the island then waits, dispatching nothing, until an input changes.
 */
class FluidThermoDomainHoldTest {
    private static final String NETWORK="createcheme:tjl20_methane_nitrogen";
    private static final BoundedCpuSolveService.CancellationToken NEVER=new BoundedCpuSolveService.CancellationToken() {
        public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}
        public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}
    };

    private static FluidThermodynamics narrowed(double minimum) {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());String key="data/createcheme/materials/properties/nitrogen.json";
        var o=JsonParser.parseString(resources.get(key)).getAsJsonObject();o.getAsJsonObject("fluid_domain").addProperty("temperature_min_kelvin",minimum);resources.put(key,o.toString());
        return FluidThermodynamics.forNetwork(MaterialCatalog.parse(resources),NETWORK,1e-9);
    }
    /** R C P R along +x, the WP5 rig's transfer with a compressor: devices 1 (tank), 2 (compressor), 3 (pipe), 4 (tank). */
    private static PassiveNetwork transfer(FluidThermodynamics model) {
        var block=new PipeResistance.Geometry(1,.05,.000045,0);double[] nitrogen=new double[model.componentCount()];nitrogen[model.components().indexOf("Nitrogen")]=1;
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        String layout="RCPR";
        for(int i=0;i<layout.length();i++) {
            var kind=switch(layout.charAt(i)){case 'C'->TopologyCompiler.Kind.COMPRESSOR;case 'P'->TopologyCompiler.Kind.PIPE;default->TopologyCompiler.Kind.RESERVOIR;};
            var device=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",i,64,0),kind,PhysicalFluidTopology.Direction.EAST,block,
                    kind==TopologyCompiler.Kind.COMPRESSOR?new FlowControl.Compressor(.01,1.05,1):new FlowControl.Passive());
            devices.add(device);
            if(kind==TopologyCompiler.Kind.RESERVOIR)boundaries.put(device.id(),new FluidDeviceSpec(1,298.15,101325,nitrogen).initialize(device,model,()->{}));
        }
        return PhysicalFluidTopology.compile(devices,boundaries).islands().stream().filter(i->i.physicalIds().size()==devices.size()).findFirst().orElseThrow().graph();
    }

    /** The island waits for its inputs: no retry deadline at all. */
    private static boolean waiting(IslandCoordinator coordinator){return coordinator.snapshot(1).clock().retryAtTick()==Long.MAX_VALUE;}

    @Test void aReproducibleDomainFailureHoldsWithTheDedicatedStatusAndWaitsForAnInputChange() {
        var model=narrowed(298.05);
        var attempts=new LinkedHashMap<Long,IslandCoordinator.Attempt>();var commands=new HashMap<Long,ProcessSolveServices.FluidIslandCommand>();
        long[] requests={0},jobs={0};
        var coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
            public int availableWorkers(){return 1-attempts.size();}
            public long nextRequestId(){return ++requests[0];}
            public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
            public void cancel(long request){}
        },changed->{},()->0L,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false,100,CertificatePolicy.defaults()));
        var warnings=new ArrayList<String>();
        coordinator.onDomainHold((island,v,where,parked)->warnings.add(v.getMessage()+" | "+where));
        coordinator.nodeNames(id->switch((int)id){case 1->"reservoir at 0, 64, 0";case 2->"compressor at 1, 64, 0";case 4->"reservoir at 3, 64, 0";default->"pipe junction "+id;});
        coordinator.register(new IslandCoordinator.Snapshot(1,0,transfer(model),new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
        var failures=new ArrayList<ProcessSolveServices.FluidIslandSolveResult>();
        Runnable tick=()->{
            coordinator.tick();
            for(long request:List.copyOf(attempts.keySet())) {
                var attempt=attempts.remove(request);var command=commands.remove(request);long[] clock={0};
                var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(NEVER,()->clock[0]+=1_000);
                jobs[0]++;if(result.candidate().isEmpty())failures.add(result);coordinator.completed(attempt,Optional.of(result));
            }
            coordinator.pump();
        };
        for(int t=0;t<400&&!waiting(coordinator);t++)tick.run();
        assertTrue(waiting(coordinator),"the retry reproduced the violation: "+coordinator.snapshot(1).status());
        assertEquals(2,failures.size(),"one failure and one fresh-solver retry: "+failures);
        for(var failure:failures) {
            var v=failure.domain().orElseThrow(()->new AssertionError("the failure carries the violation: "+failure.detail()));
            assertEquals("Nitrogen",v.component());assertEquals(ThermoDomainViolation.Code.THERMO_DOMAIN_TEMPERATURE_BELOW,v.code());assertEquals(298.05,v.minimum());
            assertTrue(failure.detail().startsWith(ProcessSolveServices.THERMO_DOMAIN+"Thermo domain: Nitrogen at "),failure.detail());
        }
        String status=coordinator.snapshot(1).status();
        System.out.println("thermo-domain hold: "+status+" | "+warnings);
        assertTrue(status.startsWith("HELD (thermo domain): Nitrogen at "),status);
        assertTrue(status.contains(" is below 298.05 K in ")&&status.contains("(valid 298.05..900 K, package "+NETWORK+")"),status);
        assertTrue(status.contains(" in compressor at 1, 64, 0")||status.contains(" in reservoir at 0, 64, 0"),"names the device where it happened: "+status);
        assertEquals(1,warnings.size(),"one warning for the hold: "+warnings);
        long jobsWhenParked=jobs[0];
        for(int t=0;t<1000;t++)tick.run();
        assertEquals(jobsWhenParked,jobs[0],"no solver dispatch for 1,000 ticks");
        assertEquals(1,warnings.size());
        // A control or topology edit makes a new island; a fence (a queued edit, a module drive) wakes this one at once.
        long committed=coordinator.snapshot(1).clock().committedTick();
        coordinator.fence(UUID.randomUUID(),committed+1,List.of(1L));coordinator.pump();
        assertFalse(waiting(coordinator));assertEquals(1,attempts.size(),"the input change dispatches it");
    }
}
