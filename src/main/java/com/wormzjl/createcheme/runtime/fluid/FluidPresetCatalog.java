package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.createcheme.science.material.*;
import java.util.*;

/** Catalog choices projected by identity onto the single qualified network basis. */
public final class FluidPresetCatalog {
    public static final String NETWORK_PACKAGE=FluidMaterialCatalog.NETWORK_PACKAGE;
    public static final String CRUDE_BASIS_PACKAGE=FluidMaterialCatalog.CRUDE_BASIS_PACKAGE;
    private FluidPresetCatalog() {}
    public record Preset(String id,String name,double[] moleFractions) {
        public Preset {
            Objects.requireNonNull(id);Objects.requireNonNull(name);moleFractions=moleFractions.clone();
            if(id.length()>128||name.isBlank()||name.length()>128||moleFractions.length<1||moleFractions.length>MaterialAxis.MAX_CONSERVED_COMPONENTS)
                throw new IllegalArgumentException("Invalid fluid preset descriptor");
            double sum=0;for(double v:moleFractions){if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("Invalid fluid preset composition");sum+=v;}
            if(!Double.isFinite(sum)||Math.abs(sum-1)>1e-10)throw new IllegalArgumentException("Fluid preset must be normalized");
        }
        @Override public double[] moleFractions(){return moleFractions.clone();}
    }
    public static List<Preset> resolve(MaterialCatalog catalog) {
        var config=catalog.presets().network();var target=catalog.requirePackage(config.packageId());
        var ids=new ArrayList<>(target.components());ids.add("Water");var axis=new MaterialAxis(ids);var result=new ArrayList<Preset>();
        for(String id:config.fluidPresets()) {
            var preset=catalog.presets().fluid(id);double[] amounts;
            if(!preset.component().isEmpty()){amounts=new double[axis.size()];amounts[axis.requireIndex(preset.component())]=1;}
            else {
                var source=catalog.requirePackage(preset.packageId());catalog.requireSharedFluidPhysics(source.id(),target.id());
                amounts=axis.project(new MaterialAxis(source.components()),MaterialPresets.moleFractions(source,preset.assayId()));
            }
            result.add(new Preset(id,preset.label(),amounts));
        }
        return List.copyOf(result);
    }
}
