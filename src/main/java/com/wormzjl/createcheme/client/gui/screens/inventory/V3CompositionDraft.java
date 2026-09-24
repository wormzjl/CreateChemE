package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.client.gui.common.RelativeComposition;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import java.util.*;

/** Column-specific identity around the shared relative-composition editor model. */
final class V3CompositionDraft {
    private final V3ColumnInput source;
    private final RelativeComposition values;
    V3CompositionDraft(V3ColumnInput source,List<Double> weights){
        this.source=source;values=new RelativeComposition(source.componentBasis().componentIds(),weights,source.feedComponentMolarFlowsMolPerSecond());
    }
    V3ColumnInput source(){return source;}
    boolean dirty(){return values.dirty();}
    boolean mass(){return values.mass();}
    List<Integer> rows(){return values.rows();}
    String get(int id){return values.get(id);}
    boolean contains(int id){return values.contains(id);}
    void clear(){values.clear();}
    void add(int id){values.add(id);}
    void remove(int id){values.remove(id);}
    void set(int id,String value){values.set(id,value);}
    double total(){return values.total();}
    double[] moleFractions(){return values.moleFractions();}
    List<Double> molecularWeights(){return values.molecularWeights();}
    Map<String,Double> relativeAmounts(){return values.relativeAmounts();}
    void load(Map<String,Double> amounts,boolean mass){values.load(amounts,mass);}
    double[] displayFractions(){return values.displayFractions();}
    double componentFlow(int id,double total,boolean mass){return values.componentFlow(id,total,mass);}
    void toggleBasis(){values.toggleBasis();}
}
