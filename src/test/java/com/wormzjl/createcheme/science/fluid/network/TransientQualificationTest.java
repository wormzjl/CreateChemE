package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Independent fixed-step backward-Euler refinements qualify the production interval integration (backward Euler under the
 * state-change controller: first order; the bounds below were re-baselined in WP1 of the mixed-gas junction batch). */
class TransientQualificationTest {
    @Test void diluteGasSmallSignalMatchesAnalyticAdiabaticPoiseuilleRelaxation() throws Exception {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;
        var graph=new PassiveNetwork(List.of(tank(1,1000.5,n),tank(2,999.5,n)),List.of(new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(1,.01,0,0))));
        // Independent ideal-gas limiting solution. NIST nitrogen viscosity at 350 K is 20.1172264699 uPa.s.
        double gamma=1.4,mu=20.1172264699e-6,rate=gamma*1000*Math.PI*Math.pow(.01,4)/(64*mu),duration=10;
        double expectedDifference=Math.exp(-rate*duration),expectedMass=(1-expectedDifference)*.0280134/(2*gamma*8.31446261815324*350);
        var result=new PassiveIntervalSolver(model).solve(graph,duration,PassiveIntervalSolver.Settings.defaults(),()->{});
        double difference=result.graph().reservoirs().get(0).state().pressure()-result.graph().reservoirs().get(1).state().pressure();
        double pressureError=Math.abs(difference/expectedDifference-1),massError=Math.abs(result.averageMassFlows()[0]*duration/expectedMass-1);
        // The state cap measures change relative to max(100 Pa, P), so a 1 Pa signal in 1000 Pa tanks never binds it and the
        // interval takes few steps. Measured 0.0166 / 0.0398; the TR-BDF2 bounds were 0.01 / 0.01.
        assertTrue(pressureError<.025,"pressure difference "+pressureError);assertTrue(massError<.06,"integrated mass "+massError);
        Files.createDirectories(Path.of("build/reports/fluid"));Files.writeString(Path.of("build/reports/fluid/M3-analytic-gas-limit.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("pressureDifferenceRelativeError",pressureError,"integratedMassRelativeError",massError,"reference","adiabatic ideal-gas small-signal Poiseuille relaxation; 1000 Pa, 350 K, 1 Pa initial difference")));
    }
    @Test void freeWaterDisappearanceMatchesRefinedTrajectoriesAcrossThreeIntervals() throws Exception {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=40;n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=5;var unit=model.flashTP(298.15,150000,n,()->{});for(int i=0;i<n.length;i++)n[i]/=unit.volume();
        var initial=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,500,500000,()->{}),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,model.flashTP(298.15,150000,n,()->{}))),List.of(new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(100,.02,.000045,0))));
        var coarse=initial;var fine=initial;var candidate=initial;double coarseMass=0,fineMass=0,actualMass=0;var rows=new ArrayList<Map<String,Object>>();
        for(int interval=1;interval<=3;interval++) {
            var c=reference(coarse,20,400);coarse=c.graph;coarseMass+=c.mass;
            var f=reference(fine,20,800);fine=f.graph;fineMass+=f.mass;
            var a=new PassiveIntervalSolver(model).solve(candidate,20,PassiveIntervalSolver.Settings.defaults(),()->{});candidate=a.graph();actualMass+=20*a.averageMassFlows()[0];
            var expected=fine.reservoirs().get(1).state();var actual=candidate.reservoirs().get(1).state();
            double referenceError=Math.abs(coarseMass/fineMass-1),massError=Math.abs(actualMass/fineMass-1),pressureError=Math.abs(actual.pressure()/expected.pressure()-1),temperatureError=Math.abs(actual.temperature()-expected.temperature());
            rows.add(Map.of("simulatedSeconds",20*interval,"referenceRefinementMassError",referenceError,"massError",massError,"pressureError",pressureError,"temperatureErrorKelvin",temperatureError,"freeWaterMoles",actual.waterLiquid()));
            // Measured over the three 20 s intervals: mass 0.0094, pressure 0.0081, temperature 1.37 K (TR-BDF2 bounds
            // 0.005, 0.005, 0.5 K); the water that disappears is located to the same volume fraction.
            assertTrue(referenceError<.001);assertTrue(massError<.015,"mass "+massError);assertTrue(pressureError<.0125,"pressure "+pressureError);assertTrue(temperatureError<2,"temperature "+temperatureError);
            assertEquals(expected.waterVolume()/expected.volume(),actual.waterVolume()/actual.volume(),.005);
            assertEquals(n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK],candidate.reservoirs().get(1).inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK],1e-8);
        }
        assertEquals(0,candidate.reservoirs().get(1).state().waterLiquid(),1e-9);
        Files.createDirectories(Path.of("build/reports/fluid"));Files.writeString(Path.of("build/reports/fluid/M3-phase-time-screening.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));
    }
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private PassiveNetwork.Reservoir tank(long id,double pressure,double[] composition) {
        var n=composition.clone();var unit=model.flashTP(350,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        var state=model.flashTP(350,pressure,n,()->{});
        return new PassiveNetwork.Reservoir(id,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,n,state.internalEnergy()));
    }
    private record Reference(PassiveNetwork graph,double mass) {}
    private Reference reference(PassiveNetwork initial,double duration,int steps) {
        var graph=initial;var solver=new PassiveStepSolver(model);double moved=0;
        for(int i=0;i<steps;i++){
            PassiveStepSolver.Result result;
            try{result=solver.solve(graph,duration/steps,()->{});}catch(RuntimeException failure){throw new IllegalStateException("BE reference "+steps+" step "+i+" T="+graph.reservoirs().getFirst().state().temperature()+" P="+graph.reservoirs().getFirst().state().pressure(),failure);}
            graph=PassiveIntervalSolver.replace(graph,result);moved+=duration/steps*result.massFlows()[0];
        }
        return new Reference(graph,moved);
    }
    @Test void liquidCompressionWetCrudeReversalAndPumpMatchRefinedTrajectories() throws Exception {
        double[] water=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];water[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=1;
        var wet=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane_nitrogen").crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1);wet[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=.1;wet[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=.2;
        double[] gas=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];gas[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;
        double[] smallHeadspace=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];smallHeadspace[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=.05;smallHeadspace[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=54000;
        var rows=new ArrayList<Map<String,Object>>();var failures=new ArrayList<String>();
        for(String name:List.of("liquid-full compression","small gas headspace","wet crude equalization","wet crude reversed direction","pump against pressure")) {
            var composition=name.startsWith("liquid")?water:name.startsWith("small")?smallHeadspace:name.startsWith("pump")?gas:wet;
            double pa=name.contains("reversed")?150000:151000,pb=name.contains("reversed")?151000:150000;
            FlowControl control=name.startsWith("pump")?new FlowControl.Pump(.001,500000,.8):new FlowControl.Passive();
            if(name.startsWith("pump")){pa=101325;pb=200000;}
            var graph=new PassiveNetwork(List.of(tank(1,pa,composition),tank(2,pb,composition)),List.of(new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(100,.05,.000045,0),control)));
            var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("case",name);double duration=.5;
            try {
                int fineSteps=name.startsWith("liquid")||name.startsWith("small")?1600:400;
                var coarse=reference(graph,duration,fineSteps/2);var fine=reference(graph,duration,fineSteps);
                row.put("referenceSteps",fineSteps);
                var candidate=new PassiveIntervalSolver(model).solve(graph,duration,PassiveIntervalSolver.Settings.defaults(),()->{});
                double massScale=Math.max(1e-8,Math.abs(fine.mass)),refError=Math.abs(coarse.mass-fine.mass)/massScale;
                double error=Math.abs(candidate.averageMassFlows()[0]*duration-fine.mass)/massScale,maxP=0,maxT=0,maxPhase=0;
                for(int i=0;i<2;i++) {
                    var a=candidate.graph().reservoirs().get(i).state();var b=fine.graph.reservoirs().get(i).state();
                    maxP=Math.max(maxP,Math.abs(a.pressure()/b.pressure()-1));maxT=Math.max(maxT,Math.abs(a.temperature()-b.temperature()));
                    maxPhase=Math.max(maxPhase,Math.abs(a.vaporVolume()/a.volume()-b.vaporVolume()/b.volume()));
                }
                row.put("referenceRefinementMassError",refError);row.put("massError",error);row.put("pressureError",maxP);row.put("temperatureErrorKelvin",maxT);row.put("phaseVolumeFractionError",maxPhase);
                row.put("acceptedSteps",candidate.acceptedSubsteps());row.put("rejectedSteps",candidate.rejectedSubsteps());
                // Integrated mass bound 0.075 (measured 0.049 on the wet crude cases, one 0.5 s step; TR-BDF2 0.005).
                if(refError>.001||error>.075||maxP>.005||maxT>.5||maxPhase>.005)throw new IllegalStateException("TIME/refinement threshold exceeded: mass "+error);
                row.put("status","PASS");
            }catch(RuntimeException failure){failure.printStackTrace();row.put("status","FAIL");row.put("failure",failure.toString()+" cause="+failure.getCause());failures.add(name+": "+failure);}
        }
        Files.createDirectories(Path.of("build/reports/fluid"));Files.writeString(Path.of("build/reports/fluid/M3-transient-screening.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }
}
