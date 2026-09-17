package com.wormzjl.createcheme.science.material;

import java.util.*;

/** Immutable identity axis. Projection never discards nonzero material or guesses an alias. */
public final class MaterialAxis {
    public static final int MAX_CONSERVED_COMPONENTS=65;
    private final List<String> ids;
    private final Map<String,Integer> indices;
    public MaterialAxis(List<String> ids) {
        this.ids=List.copyOf(ids);
        if(ids.isEmpty()||ids.size()>MAX_CONSERVED_COMPONENTS)throw new IllegalArgumentException("Invalid material axis size");
        var index=new HashMap<String,Integer>();
        for(int i=0;i<ids.size();i++) {
            String id=ids.get(i);
            if(id.isBlank()||id.length()>128||index.putIfAbsent(id,i)!=null)throw new IllegalArgumentException("Invalid or duplicate material identity: "+id);
        }
        indices=Map.copyOf(index);
    }
    public List<String> ids(){return ids;}
    public int size(){return ids.size();}
    public int indexOf(String id){return indices.getOrDefault(id,-1);}
    public int requireIndex(String id){int i=indexOf(id);if(i<0)throw new IllegalArgumentException("Material is outside axis: "+id);return i;}
    public double[] project(MaterialAxis source,double[] amounts) {
        if(amounts.length!=source.size())throw new IllegalArgumentException("Material vector/axis mismatch");
        var result=new double[size()];
        for(int i=0;i<amounts.length;i++) {
            double n=amounts[i];if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Invalid material amount");
            int target=indexOf(source.ids.get(i));
            if(target<0) {if(n!=0)throw new IllegalArgumentException("Nonzero material outside target axis: "+source.ids.get(i));}
            else result[target]=n;
        }
        return result;
    }
}
