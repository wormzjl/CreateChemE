package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.createcheme.science.material.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.*;

/** Required crude presets on one verified shared TJL20 component/property basis. */
public final class FluidPresetCatalog {
    /** The registered package the network runs on: the crude basis plus nitrogen. */
    public static final String NETWORK_PACKAGE=FluidMaterialCatalog.NETWORK_PACKAGE;
    /** The twenty-component basis the crude presets are computed on, which the network extends. */
    public static final String CRUDE_BASIS_PACKAGE=FluidMaterialCatalog.CRUDE_BASIS_PACKAGE;
    private FluidPresetCatalog() {}
    public record Preset(String id,String name,double[] moleFractions) {
        public Preset {Objects.requireNonNull(id);Objects.requireNonNull(name);moleFractions=moleFractions.clone();}
        @Override public double[] moleFractions(){return moleFractions.clone();}
    }
    public static List<Preset> resolve(MaterialCatalog catalog) {
        var target=catalog.requirePackage(CRUDE_BASIS_PACKAGE);
        requireNetworkExtendsCrudeBasis(catalog,catalog.requirePackage(NETWORK_PACKAGE),target);
        var result=new ArrayList<Preset>();
        result.add(new Preset("water","Water",FluidDeviceSpec.water().composition()));result.add(new Preset("nitrogen","Nitrogen",FluidDeviceSpec.nitrogen().composition()));
        var packages=List.of(CRUDE_BASIS_PACKAGE,"createcheme:wti_light_export_tjl20","createcheme:cold_lake_blend_tjl20");var names=List.of("Tia Juana Light","WTI Light Export","Cold Lake Blend");
        for(int i=0;i<packages.size();i++) {
            String id=packages.get(i);var source=catalog.requirePackage(id);
            if(!source.components().equals(target.components())||!source.properties().equals(target.properties())||!source.interactions().equals(target.interactions())||!source.water().equals(target.water())||!catalog.viscosityFingerprint(id).equals(catalog.viscosityFingerprint(CRUDE_BASIS_PACKAGE)))throw new IllegalArgumentException("Preset no longer shares the qualified network property basis: "+id);
            String assay=new TreeSet<>(source.assays().keySet()).first();
            double[] n=MaterialRuntime.with(catalog,id,()->V3PengRobinsonThermo.fromRegisteredPackage(id).crudeFeed(assay).moleFractions());
            result.add(new Preset(assay,names.get(i),Arrays.copyOf(n,22)));
        }
        return List.copyOf(result);
    }
    /**
     * The network basis is the crude basis plus nitrogen and nothing else: the same components and
     * properties in the same order, nitrogen appended, the same water model, the same interaction
     * matrix with a zero nitrogen row and column, and pure-component transport data for nitrogen in
     * both phases. That is what lets a preset computed on a crude package be padded to the network
     * basis by {@link Arrays#copyOf} - the padding is nitrogen and water, in that order - and it is
     * what {@link FluidDeviceSpec#nitrogen()} means by index 20.
     */
    private static void requireNetworkExtendsCrudeBasis(MaterialCatalog catalog,MaterialCatalog.Package network,MaterialCatalog.Package basis) {
        int n=basis.components().size();
        boolean extension=network.components().size()==n+1&&network.properties().size()==n+1
                &&network.components().subList(0,n).equals(basis.components())
                &&network.components().get(n).equals(FluidMaterialCatalog.NITROGEN)
                &&network.properties().subList(0,n).equals(basis.properties())
                &&network.water().equals(basis.water());
        for(int i=0;extension&&i<=n;i++)for(int j=0;j<=n;j++)
            if(network.interactions().get(i).get(j)!=(i==n||j==n?0:basis.interactions().get(i).get(j))){extension=false;break;}
        for(var phase:ViscosityCorrelation.Phase.values())
            extension&=catalog.viscosity(NETWORK_PACKAGE,FluidMaterialCatalog.NITROGEN,phase).isPresent();
        if(!extension)throw new IllegalArgumentException("The fluid network package is no longer the crude basis plus nitrogen: "+network.id());
    }
}
