package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import java.util.Objects;

/** Owned cake and carried energy, separate from all fluid reservoir inventories. */
public record InlineFilter(double capacity, double cleanResistance, SolidInventory captured, double energyJoule,boolean stoppedAtCapacity) {
    public InlineFilter(double capacity,double cleanResistance,SolidInventory captured,double energyJoule){this(capacity,cleanResistance,captured,energyJoule,false);}
    public InlineFilter {
        Objects.requireNonNull(captured);
        if(!Double.isFinite(capacity)||capacity<=0||!Double.isFinite(cleanResistance)||cleanResistance<=0
                ||!Double.isFinite(energyJoule)||captured.empty()&&energyJoule!=0)throw new IllegalArgumentException("Invalid filter state");
    }
    public static InlineFilter empty(){return new InlineFilter(.01,1e6,SolidInventory.EMPTY,0);}
    public double loading(){return captured.volume()/capacity;}
    public boolean clogged(){return stoppedAtCapacity||captured.volume()>=capacity;}
    public InlineFilter stopped(){return new InlineFilter(capacity,cleanResistance,captured,energyJoule,true);}
    public InlineFilter add(SolidInventory material,double energy){if(material.empty()&&energy==0)return this;return new InlineFilter(capacity,cleanResistance,captured.plus(material),energyJoule+energy,stoppedAtCapacity);}
    public InlineFilter cleared(){return new InlineFilter(capacity,cleanResistance,SolidInventory.EMPTY,0);}
    public static InlineFilter combine(InlineFilter first,double a,InlineFilter second,double b){
        if(first.equals(second)&&a+b==1)return first;
        if(first.capacity!=second.capacity||first.cleanResistance!=second.cleanResistance)throw new IllegalArgumentException("Filter settings changed during solve");
        return new InlineFilter(first.capacity,first.cleanResistance,SolidInventory.combine(first.captured,a,second.captured,b),a*first.energyJoule+b*second.energyJoule,first.stoppedAtCapacity||second.stoppedAtCapacity);
    }
}