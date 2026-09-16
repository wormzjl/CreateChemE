package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import java.util.*;

/** Immutable owned material and carried total energy, including its gravitational datum.
 * A withdrawal contributes stream enthalpy plus potential energy; receiving-vessel U is solved separately.
 */
public final class MaterialParcel {
    private final double[] moles,molecularWeights;
    private final double internalEnergy;
    private final EnergyReference reference;
    public MaterialParcel(double[] moles,double[] molecularWeights,double internalEnergy,EnergyReference reference) {
        this.moles=moles.clone();this.molecularWeights=molecularWeights.clone();this.internalEnergy=internalEnergy;this.reference=Objects.requireNonNull(reference);
        if(moles.length!=molecularWeights.length||moles.length!=reference.components().size()||!Double.isFinite(internalEnergy))throw new IllegalArgumentException("Invalid parcel basis/energy");
        double total=0;
        for(int i=0;i<moles.length;i++){if(!Double.isFinite(moles[i])||moles[i]<0||!Double.isFinite(molecularWeights[i])||molecularWeights[i]<=0)throw new IllegalArgumentException("Invalid parcel component");total+=moles[i];}
        if(!Double.isFinite(total)||!Double.isFinite(massKg())||(total>0&&massKg()==0)||(total==0&&internalEnergy!=0))throw new IllegalArgumentException("Invalid empty/overflowed parcel");
    }
    public double[] moles(){return moles.clone();}
    public double[] molecularWeights(){return molecularWeights.clone();}
    public double internalEnergy(){return internalEnergy;}
    public double energyJoule(){return internalEnergy;}
    public EnergyReference reference(){return reference;}
    public double massKg(){double total=0;for(int i=0;i<moles.length;i++)total+=moles[i]*molecularWeights[i];return total;}
    public boolean empty(){return massKg()==0;}
    public MaterialParcel rebase(EnergyReference target){return new MaterialParcel(moles,molecularWeights,reference.rebase(internalEnergy,moles,target),target);}
    /** Combine owned parcels only after their component and energy datums have been aligned. */
    public MaterialParcel plus(MaterialParcel other) {
        Objects.requireNonNull(other);
        if(!reference.revision().equals(other.reference.revision())||!reference.components().equals(other.reference.components())||reference.formationDataQualified()!=other.reference.formationDataQualified()||!Arrays.equals(molecularWeights,other.molecularWeights))throw new IllegalArgumentException("Parcel combination requires a common basis and reference");
        double[] combined=moles.clone();
        for(int c=0;c<combined.length;c++) {
            if(reference.offsetJoulesPerMole(c)!=other.reference.offsetJoulesPerMole(c))throw new IllegalArgumentException("Parcel combination requires explicit energy migration");
            combined[c]+=other.moles[c];
        }
        return new MaterialParcel(combined,molecularWeights,internalEnergy+other.internalEnergy,reference);
    }
    public record Portion(MaterialParcel delivered,MaterialParcel remainder) {}
    public Portion takeMass(double maximumKg) {
        if(!Double.isFinite(maximumKg)||maximumKg<0)throw new IllegalArgumentException("Invalid delivery bound");
        double mass=massKg(),fraction=mass==0?0:Math.min(1,maximumKg/mass);
        double[] delivered=new double[moles.length],remaining=new double[moles.length];
        for(int i=0;i<moles.length;i++){delivered[i]=moles[i]*fraction;remaining[i]=moles[i]-delivered[i];}
        double energy=internalEnergy*fraction;
        return new Portion(new MaterialParcel(delivered,molecularWeights,energy,reference),new MaterialParcel(remaining,molecularWeights,internalEnergy-energy,reference));
    }
    /** A reference-covariant fixed split for the test module; no reaction or separation physics is claimed. */
    public List<MaterialParcel> split(double[] firstOutletFractions) {
        if(firstOutletFractions.length!=moles.length)throw new IllegalArgumentException("Split basis mismatch");
        double[] first=new double[moles.length],second=new double[moles.length];double firstMass=0,offset=0,firstOffset=0;
        for(int i=0;i<moles.length;i++) {
            double fraction=firstOutletFractions[i];if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw new IllegalArgumentException("Invalid component split");
            first[i]=moles[i]*fraction;second[i]=moles[i]-first[i];firstMass+=first[i]*molecularWeights[i];
            offset+=moles[i]*reference.offsetJoulesPerMole(i);firstOffset+=first[i]*reference.offsetJoulesPerMole(i);
        }
        double firstEnergy=massKg()==0?0:(internalEnergy-offset)*(firstMass/massKg())+firstOffset;
        return List.of(new MaterialParcel(first,molecularWeights,firstEnergy,reference),new MaterialParcel(second,molecularWeights,internalEnergy-firstEnergy,reference));
    }
}
