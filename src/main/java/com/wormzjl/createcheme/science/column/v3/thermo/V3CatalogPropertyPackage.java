package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.*;

/** Immutable PR adapter; all component and interaction values originate in a resolved catalog. */
final class V3CatalogPropertyPackage implements V3PropertyPackage {
    private final MaterialCatalog.Package data;
    private final V3ComponentBasis basis;
    private final List<V3PropertyComponent> components;
    V3CatalogPropertyPackage(MaterialCatalog catalog,String id) {
        data=catalog.requirePackage(id);
        if(!data.model().equals("pr78")) throw new IllegalArgumentException("Thermodynamic solver unavailable: " + data.model());
        basis=new V3ComponentBasis(data.components());
        components=data.properties().stream().map(p -> {
            var cp=p.cp(); var pr=p.pr();
            return new V3PropertyComponent(p.component(),catalog.name(p.component()).english(),p.molecularWeight(),p.normalBoilingPoint(),
                    pr.criticalTemperature(),pr.criticalPressure(),pr.acentricFactor(),p.density(),
                    cp.get(0),cp.get(1),cp.get(2),cp.get(3),cp.get(4),cp.get(5),p.estimated());
        }).toList();
    }
    @Override public String packageId(){return data.id();}
    @Override public String datasetRevision(){return data.scientificRevision();}
    @Override public V3ComponentBasis componentBasis(){return basis;}
    @Override public V3PropertyComponent component(int i){return components.get(i);}
    @Override public double minimumTemperatureKelvin(){return data.minimumTemperature();}
    @Override public double maximumTemperatureKelvin(){return data.maximumTemperature();}
    @Override public double minimumPressurePascal(){return data.minimumPressure();}
    @Override public double maximumPressurePascal(){return data.maximumPressure();}
    @Override public List<String> advisoryEvidence(){return data.evidence();}
    @Override public double[][] binaryInteractions(){return data.interactions().stream().map(r -> r.stream().mapToDouble(Double::doubleValue).toArray()).toArray(double[][]::new);}
    @Override public V3CrudeFeed crudeFeed(String id){
        var assay=data.assays().get(id);
        if(assay==null)throw new IllegalArgumentException("Unknown assay " + id + " for " + data.id());
        double[] moles=new double[components.size()];
        for(int i=0;i<moles.length;i++) {
            double amount=assay.amounts().get(i); var p=components.get(i);
            moles[i]=switch(assay.basis()) {
                case "mole" -> amount;
                case "mass" -> amount/p.molecularWeightKgPerMol();
                case "standard_liquid_volume" -> (assay.volumeScale()*amount/assay.amountTotal())
                        *p.standardLiquidDensityKgPerCubicMetre()/p.molecularWeightKgPerMol();
                default -> throw new IllegalArgumentException("Unknown assay basis");
            };
        }
        return new V3CrudeFeed(data.id(),id,basis,moles);
    }
}
