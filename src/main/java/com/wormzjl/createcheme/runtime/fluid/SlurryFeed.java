package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.state.*;
import com.wormzjl.createcheme.science.material.SolidMaterialCatalog;
import java.util.*;

/** Generator concentration and exact particle grades, independently of its fluid mole fractions. */
public record SlurryFeed(double volumeFraction,List<Grade> grades) {
    public static final SlurryFeed NONE=new SlurryFeed(0,List.of());
    public record Grade(String material,ParticleSize size,double massShare) {
        public Grade {
            Objects.requireNonNull(material);Objects.requireNonNull(size);
            if(material.length()>128||!material.matches("[a-z][a-z0-9_.-]*:[a-z0-9_./-]+")||!Double.isFinite(massShare)||massShare<=0)
                throw new IllegalArgumentException("Each particle grade needs a material, positive diameter and positive mass share");
        }
    }
    public SlurryFeed {
        grades=List.copyOf(grades);
        if(!Double.isFinite(volumeFraction)||volumeFraction<0||volumeFraction>=1||grades.size()>64||volumeFraction>0&&grades.isEmpty())
            throw new IllegalArgumentException("Solids volume fraction must be 0..1 (exclusive), with at most 64 grades");
        double sum=0;for(var g:grades)sum+=g.massShare();
        if(!Double.isFinite(sum))throw new IllegalArgumentException("Particle shares overflow");
    }
    public static SlurryFeed demo(){return new SlurryFeed(.1,List.of(new Grade("createcheme:demo_particle",ParticleSize.micrometres("100"),1)));}
    public SolidInventory unitMass(SolidMaterialCatalog catalog) {
        if(grades.isEmpty())return SolidInventory.EMPTY;
        double sum=grades.stream().mapToDouble(Grade::massShare).sum();
        return new SolidInventory(grades.stream().map(g->new SolidInventory.Population(catalog.require(g.material()),g.size(),g.massShare()/sum)).toList());
    }
}