package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import java.util.*;

/** Relative weights retain full precision until the user edits a cell. */
final class V3CompositionDraft {
    private final V3ColumnInput source;
    private final List<Double> weights;
    private final LinkedHashMap<Integer, Double> values = new LinkedHashMap<>();
    private final Map<Integer,String> text = new HashMap<>();
    private boolean mass, dirty;

    V3CompositionDraft(V3ColumnInput source, List<Double> weights) {
        this.source=source; this.weights=List.copyOf(weights);
        if(weights.size()!=source.componentBasis().componentCount()
                || weights.stream().anyMatch(w->!Double.isFinite(w)||w<=0))
            throw new IllegalArgumentException("Missing component molecular weights");
        double[] flows=source.feedComponentMolarFlowsMolPerSecond();
        double sum=Arrays.stream(flows).sum();
        for(int i=0;i<flows.length;i++)if(flows[i]>0)put(i,flows[i]/sum*100);
    }
    V3ColumnInput source(){return source;}
    boolean dirty(){return dirty;}
    boolean mass(){return mass;}
    List<Integer> rows(){return List.copyOf(values.keySet());}
    String get(int id){return text.get(id);}
    boolean contains(int id){return values.containsKey(id);}
    void clear(){values.clear();text.clear();dirty=true;}
    void add(int id){
        if(id<0||id>=weights.size())throw new IllegalArgumentException("Unknown component");
        if(!values.containsKey(id)){put(id,0);dirty=true;}
    }
    void remove(int id){values.remove(id);text.remove(id);dirty=true;}
    void set(int id,String value){
        if(!values.containsKey(id))throw new IllegalArgumentException("Unknown component");
        text.put(id,value);values.put(id,Double.NaN);dirty=true;
    }
    private void put(int id,double value){values.put(id,value);text.put(id,String.format(Locale.ROOT,"%.1f",value));}
    private double value(int id){
        double v=values.get(id);
        if(Double.isNaN(v))try{v=Double.parseDouble(text.get(id));}
            catch(NumberFormatException e){throw new IllegalArgumentException("Enter a relative amount for each component");}
        if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("Component amounts must be finite and non-negative");
        return v;
    }
    double total(){double sum=0;for(int id:values.keySet())sum+=value(id);return sum;}
    double[] moleFractions(){
        double[] result=new double[weights.size()];double sum=0;
        for(int id:values.keySet()){result[id]=value(id)/(mass?weights.get(id):1);sum+=result[id];}
        if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("Add at least one component with a positive amount");
        for(int i=0;i<result.length;i++)result[i]/=sum;
        return result;
    }
    void toggleBasis(){
        if(values.isEmpty()||total()==0){mass=!mass;return;}
        double[] moles=moleFractions();boolean target=!mass;double sum=0;
        for(int id:values.keySet())sum+=moles[id]*(target?weights.get(id):1);
        for(int id:List.copyOf(values.keySet()))put(id,moles[id]*(target?weights.get(id):1)/sum*100);
        mass=target;
    }
}
