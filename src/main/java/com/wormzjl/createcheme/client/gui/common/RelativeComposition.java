package com.wormzjl.createcheme.client.gui.common;


import java.util.*;

/** Relative weights retain full precision until the user edits a cell. */
public final class RelativeComposition {
    private final List<String> components;
    private final List<Double> weights;
    private final LinkedHashMap<Integer, Double> values = new LinkedHashMap<>();
    private final Map<Integer,String> text = new HashMap<>();
    private boolean mass, dirty;

    public RelativeComposition(List<String> components,List<Double> weights,double[] moleAmounts) {
        this.components=List.copyOf(components);this.weights=List.copyOf(weights);
        if(components.size()!=weights.size()||weights.size()!=moleAmounts.length
                || new HashSet<>(components).size()!=components.size()
                || weights.stream().anyMatch(w->!Double.isFinite(w)||w<=0))
            throw new IllegalArgumentException("Missing component molecular weights");
        double sum=0;for(double v:moleAmounts){if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("Invalid component amount");sum+=v;}
        if(!Double.isFinite(sum))throw new IllegalArgumentException("Invalid component total");
        for(int i=0;i<moleAmounts.length;i++)if(moleAmounts[i]>0)put(i,moleAmounts[i]/sum*100);
    }
    public boolean dirty(){return dirty;}
    public boolean mass(){return mass;}
    public List<Integer> rows(){return List.copyOf(values.keySet());}
    public String get(int id){return text.get(id);}
    public boolean contains(int id){return values.containsKey(id);}
    public void clear(){values.clear();text.clear();dirty=true;}
    public void add(int id){
        if(id<0||id>=weights.size())throw new IllegalArgumentException("Unknown component");
        if(!values.containsKey(id)){put(id,0);dirty=true;}
    }
    public void remove(int id){values.remove(id);text.remove(id);dirty=true;}
    public void set(int id,String value){
        if(!values.containsKey(id))throw new IllegalArgumentException("Unknown component");
        text.put(id,value);values.put(id,Double.NaN);dirty=true;
    }
    private void put(int id,double value){values.put(id,value);text.put(id,String.format(Locale.ROOT,value>0&&value<0.05?"%.3g":"%.1f",value));}
    private double value(int id){
        double v=values.get(id);
        if(Double.isNaN(v))try{v=Double.parseDouble(text.get(id));}
            catch(NumberFormatException e){throw new IllegalArgumentException("Enter a relative amount for each component");}
        if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("Component amounts must be finite and non-negative");
        return v;
    }
    public double total(){double sum=0;for(int id:values.keySet())sum+=value(id);return sum;}
    public double[] moleFractions(){
        double[] result=new double[weights.size()];double sum=0;
        for(int id:values.keySet()){result[id]=value(id)/(mass?weights.get(id):1);sum+=result[id];}
        if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("Add at least one component with a positive amount");
        for(int i=0;i<result.length;i++)result[i]/=sum;
        return result;
    }
    public List<Double> molecularWeights(){return weights;}
    public Map<String,Double> relativeAmounts(){
        Map<String,Double> result=new LinkedHashMap<>();
        for(int id:values.keySet())result.put(components.get(id),value(id));
        moleFractions();return Map.copyOf(result);
    }
    public void load(Map<String,Double> amounts,boolean massBasis){
        clear();mass=massBasis;
        for(var entry:amounts.entrySet()){
            int id=components.indexOf(entry.getKey());
            if(id<0)throw new IllegalArgumentException("Component is unavailable in this property package: "+entry.getKey());
            put(id,entry.getValue());
        }
        moleFractions();
    }
    public double[] displayFractions(){
        double[] result=moleFractions();if(!mass)return result;
        double total=0;for(int i=0;i<result.length;i++){result[i]*=weights.get(i);total+=result[i];}
        for(int i=0;i<result.length;i++)result[i]/=total;return result;
    }
    public double componentFlow(int id,double totalMolPerSecond,boolean massRate){
        return moleFractions()[id]*totalMolPerSecond*(massRate?weights.get(id)*3600:3.6);
    }
    public void toggleBasis(){
        if(values.isEmpty()||total()==0){mass=!mass;return;}
        double[] moles=moleFractions();boolean target=!mass;double sum=0;
        for(int id:values.keySet())sum+=moles[id]*(target?weights.get(id):1);
        for(int id:List.copyOf(values.keySet()))put(id,moles[id]*(target?weights.get(id):1)/sum*100);
        mass=target;
    }
}
