package com.wormzjl.createcheme.science.fluid.network;
import com.wormzjl.createcheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.createcheme.science.fluid.thermo.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.*;
/** WP3/WP4 migration table: the key numbers of the migrated tests that print nothing, with the old device (arg "old",
 * run against the HEAD tree's classes) or the new one (arg "new", against the WP3+WP4 tree's classes). */
public class MigrationValueProbe {
    static boolean NEW;static final Runnable NOOP=()->{};
    public static void main(String[] a) {
        NEW=a[0].equals("new");
        flowControl();networkRegime();backwardEuler();velocityClamp();startup();
    }
    static FluidThermodynamics fc=new FluidThermodynamics(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
    static PassiveStepSolver.Result fcRun(double pa,double pb,FlowControl control,double dt) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(double p:new double[]{pa,pb}){double[] n=new double[MaterialTestBasis.CRUDE+1];n[0]=1;var unit=fc.flashTP(350,p,n,NOOP);n[0]/=unit.volume();nodes.add(new PassiveNetwork.Reservoir(nodes.size(),0,fc.flashTP(350,p,n,NOOP)));}
        return new PassiveStepSolver(fc).solve(new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0),control))),dt,NOOP);
    }
    static double gasSetting(double rise,double p){double[] n=new double[MaterialTestBasis.CRUDE+1];n[0]=1;var s=fc.flashTP(350,p,n,NOOP);return rise*fc.pumpReferenceDensity()/(s.mass()/s.volume());}
    static void flowControl() {
        var t=fcRun(101325,200000, new FlowControl.Pump(.001,gasSetting(500000,101325),1),.1);
        System.out.println("FlowControlTest target: mode="+t.modes().getFirst()+" q="+t.massFlows()[0]+" head="+t.devicePressureChanges()[0]+" work="+t.pumpWorkJoule());
        var n=fcRun(300000,101325, new FlowControl.Pump(.001,500000,1),.1);
        var s=fcRun(101325,800000, new FlowControl.Pump(.001,100000,1),.1);
        System.out.println("FlowControlTest natural: mode="+n.modes().getFirst()+" q="+n.massFlows()[0]+" head="+n.devicePressureChanges()[0]+" work="+n.pumpWorkJoule()+" | shutoff: mode="+s.modes().getFirst()+" q="+s.massFlows()[0]+" head="+s.devicePressureChanges()[0]);
    }
    static FluidThermodynamics nm=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    static PassiveNetwork.Reservoir gas(long id,double t,double p,PassiveNetwork.NodeKind kind){var n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NITROGEN]=1;var u=nm.flashTP(t,p,n,NOOP);n[MaterialTestBasis.NITROGEN]/=u.volume();return new PassiveNetwork.Reservoir(id,0,nm.flashTP(t,p,n,NOOP),kind);}
    static void networkRegime() {
        var g=new PipeResistance.Geometry(100,.02,.000045,0);
        var source=gas(1,350,1e6,PassiveNetwork.NodeKind.GENERATOR);double setting=500000*nm.pumpReferenceDensity()/(source.state().mass()/source.state().volume());
        var sb=new StringBuilder("NetworkRegimeTest bracket:");
        for(double p:new double[]{1.49e6,1.51e6}){var r=new PassiveStepSolver(nm).solve(new PassiveNetwork(List.of(source,gas(2,350,p,PassiveNetwork.NodeKind.VOID)),List.of(new PassiveNetwork.Pipe(1,0,1,g, new FlowControl.Pump(.001,setting,1)))),1,NOOP);
            sb.append(" p=").append(p).append(" mode=").append(r.modes().getFirst()).append(" q=").append(r.massFlows()[0]).append(" head=").append(r.devicePressureChanges()[0]);}
        System.out.println(sb);
        var recycle=new PassiveNetwork(List.of(gas(1,350,150000,PassiveNetwork.NodeKind.RESERVOIR),gas(2,350,150000,PassiveNetwork.NodeKind.RESERVOIR)),List.of(new PassiveNetwork.Pipe(1,0,1,g, new FlowControl.Pump(.001,500000,1)),new PassiveNetwork.Pipe(2,1,0,g)));
        var r=new PassiveIntervalSolver(nm).solve(recycle,1,PassiveIntervalSolver.Settings.defaults(),NOOP);
        System.out.println("NetworkRegimeTest recycle: q="+Arrays.toString(r.averageMassFlows())+" work="+r.pumpWorkJoule()+" modes="+r.endpointModes());
        var ss=gas(1,350,101325,PassiveNetwork.NodeKind.GENERATOR).state();
        var sonic=new PassiveNetwork(List.of(gas(1,350,101325,PassiveNetwork.NodeKind.GENERATOR),gas(2,350,101325,PassiveNetwork.NodeKind.VOID)),List.of(new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(.1,.01,0,0), new FlowControl.Pump(1,500000*nm.pumpReferenceDensity()/(ss.mass()/ss.volume()),1))));
        var c=new PassiveStepSolver(nm).solve(sonic,1,NOOP);
        System.out.println("NetworkRegimeTest sonic: mode="+c.modes().getFirst()+" q="+c.massFlows()[0]+" head="+c.devicePressureChanges()[0]);
    }
    static void backwardEuler() {
        var suction=gas(1,298.15,101325,PassiveNetwork.NodeKind.RESERVOIR);var d=gas(2,298.15,200000,PassiveNetwork.NodeKind.RESERVOIR);d=new PassiveNetwork.Reservoir(2,10,d.state());
        var graph=new PassiveNetwork(List.of(suction,d),List.of(new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(10,.05,.000045,0), new FlowControl.Pump(.001,500000*nm.pumpReferenceDensity()/(suction.state().mass()/suction.state().volume()),.8))));
        var r=new PassiveStepSolver(nm).solve(graph,.2,NOOP);
        System.out.println("BackwardEulerStepTest(approx. fixture): mode="+r.modes().getFirst()+" q="+r.massFlows()[0]+" head="+r.devicePressureChanges()[0]+" work="+r.pumpWorkJoule());
    }
    static void velocityClamp() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9,.2);
        double[] n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane_nitrogen").crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),MaterialTestBasis.NETWORK+1);n[MaterialTestBasis.NETWORK]=.2;
        var s=new FluidThermodynamics.State[2];double[] p={200000,101325};
        for(int i=0;i<2;i++){var m=n.clone();var u=model.flashTP(350,p[i],m,NOOP);for(int c=0;c<m.length;c++)m[c]/=u.volume();s[i]=model.flashTP(350,p[i],m,NOOP);}
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,s[0],PassiveNetwork.NodeKind.PORT),new PassiveNetwork.Reservoir(2,0,s[1],PassiveNetwork.NodeKind.PORT)),List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(.1,.01,0,0),new FlowControl.Pump(1,500000,1))));
        var r=new PassiveStepSolver(model).solve(graph,.1,NOOP);
        System.out.println("VelocityClampTest pump arm: mode="+r.modes().getFirst()+" q="+r.massFlows()[0]+" work="+r.pumpWorkJoule()+" supplyVapourShare="+s[0].vaporVolume()/(s[0].vaporVolume()+s[0].liquidVolume()+s[0].waterVolume()));
    }
    static void startup() {
        var catalog=MaterialCatalog.bundled();var model=FluidThermodynamics.forNetwork(catalog,FluidMaterialCatalog.networkPackage(catalog),1e-9);
        for(String id:List.of("water","createcheme:tia_juana_light_methane")) {
            var composition=FluidPresetCatalog.resolve(catalog).stream().filter(q->q.id().equals(id)).findFirst().orElseThrow().moleFractions();
            double pressure=NEW&&!id.equals("water")?150000:101325;
            var unit=model.flashTP(298.15,pressure,composition,NOOP);for(int c=0;c<composition.length;c++)composition[c]/=unit.volume();
            var source=new PassiveNetwork.Reservoir(1,-60,model.flashTP(298.15,pressure,composition,NOOP),PassiveNetwork.NodeKind.GENERATOR);
            var junction=new PassiveNetwork.Reservoir(2,-60,source.state(),PassiveNetwork.NodeKind.JUNCTION);
            var receiver=new PassiveNetwork.Reservoir(3,-60,model.initialNitrogenCharge(1,298.15,pressure,NOOP));
            var geometry=new PipeResistance.Geometry(1.5,.05,.000045,0);
            var graph=new PassiveNetwork(List.of(source,junction,receiver),List.of(new PassiveNetwork.Pipe(4,0,1,geometry),new PassiveNetwork.Pipe(5,1,2,geometry,new FlowControl.Pump(id.equals("water")?.01:.0001,500000,1))));
            var r=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),NOOP);
            System.out.println("PumpJunctionStartupTest "+id+": P="+pressure+" supplyVapourShare="+source.state().vaporVolume()/source.state().volume()+" q="+Arrays.toString(r.averageMassFlows())+" work="+r.pumpWorkJoule()+" modes="+r.endpointModes());
        }
    }
}
