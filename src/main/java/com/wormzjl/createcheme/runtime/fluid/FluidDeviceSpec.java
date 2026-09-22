package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import java.util.*;

/** Validated persisted initialization/boundary settings. Finite reservoir contents live only in the world ledger. */
public record FluidDeviceSpec(double volume,double temperature,double pressure,double[] composition,SlurryFeed solids) {
    public FluidDeviceSpec(double volume,double temperature,double pressure,double[] composition){this(volume,temperature,pressure,composition,SlurryFeed.NONE);}
    public FluidDeviceSpec {
        Objects.requireNonNull(solids);composition=composition.clone();
        if(!Double.isFinite(volume)||volume<=0||volume>1000||!Double.isFinite(temperature)||temperature<273.16||temperature>600||!Double.isFinite(pressure)||pressure<100||pressure>2e6||(composition.length<1||composition.length>com.wormzjl.createcheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS))throw new IllegalArgumentException("Device settings outside the fluid model's bounds");
        double sum=0;for(double amount:composition){if(!Double.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid composition");sum+=amount;}
        if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("Empty composition");
        if(Math.abs(sum-1)>1e-12)for(int i=0;i<composition.length;i++)composition[i]/=sum;
    }
    @Override public double[] composition(){return composition.clone();}
    public static FluidDeviceSpec nitrogen(){return nitrogen(com.wormzjl.createcheme.science.material.MaterialRuntime.current());}
    public static FluidDeviceSpec water(){return water(com.wormzjl.createcheme.science.material.MaterialRuntime.current());}
    public static FluidDeviceSpec nitrogen(com.wormzjl.createcheme.science.material.MaterialCatalog catalog){return pure(catalog,"Nitrogen");}
    public static FluidDeviceSpec water(com.wormzjl.createcheme.science.material.MaterialCatalog catalog){return pure(catalog,"Water");}
    private static FluidDeviceSpec pure(com.wormzjl.createcheme.science.material.MaterialCatalog catalog,String id) {
        var ids=new ArrayList<>(catalog.requirePackage(com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog)).components());ids.add("Water");
        var axis=new com.wormzjl.createcheme.science.material.MaterialAxis(ids);double[] n=new double[axis.size()];n[axis.requireIndex(id)]=1;
        return new FluidDeviceSpec(1,298.15,101325,n);
    }
    /** Called for first activation, never to reconcile an already owned finite reservoir. */
    public PassiveNetwork.Reservoir initialize(PhysicalFluidTopology.Device device,FluidThermodynamics model,Runnable checkpoint) {
        if(composition.length!=model.components().size())throw new IllegalArgumentException("Device composition differs from captured fluid axis");
        var kind=switch(device.kind()) {case RESERVOIR->PassiveNetwork.NodeKind.RESERVOIR;case GENERATOR->PassiveNetwork.NodeKind.GENERATOR;case VOID->PassiveNetwork.NodeKind.VOID;default->throw new IllegalArgumentException("Pipes and actuators have no owned reservoir");};
        FluidThermodynamics.State state;
        if(kind==PassiveNetwork.NodeKind.RESERVOIR||kind==PassiveNetwork.NodeKind.VOID)state=model.initialNitrogenCharge(volume,temperature,pressure,checkpoint);
        else {
            var n=composition.clone();var unit=model.flashTP(temperature,pressure,n,checkpoint);
            var stock=solids.unitMass(model.solids);double liquidVolume=unit.liquidVolume()+unit.waterVolume();
            if(solids.volumeFraction()>0&&liquidVolume==0)throw new IllegalArgumentException("A slurry generator needs a liquid carrier");
            double solidVolume=solids.volumeFraction()/(1-solids.volumeFraction())*liquidVolume;
            double factor=volume/(unit.volume()+solidVolume);for(int c=0;c<n.length;c++)n[c]*=factor;
            state=model.flashTP(temperature,pressure,n,checkpoint).withSolids(solidVolume==0?com.wormzjl.createcheme.science.fluid.state.SolidInventory.EMPTY:stock.scale(solidVolume*factor/stock.volume()));
        }
        return new PassiveNetwork.Reservoir(device.id(),device.position().y(),state,kind,new PassiveNetwork.Inventory(volume,com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids()));
    }
    @Override public boolean equals(Object value){return value instanceof FluidDeviceSpec other&&volume==other.volume&&temperature==other.temperature&&pressure==other.pressure&&Arrays.equals(composition,other.composition)&&solids.equals(other.solids);}
    @Override public int hashCode(){return 31*Objects.hash(volume,temperature,pressure,solids)+Arrays.hashCode(composition);}
}
