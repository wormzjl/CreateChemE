package com.wormzjl.createcheme.client.gui.common;

import java.text.Normalizer;
import java.util.*;

/** Deterministic local matching of server component identities and localized names. */
public final class ComponentSearch {
    public record Option(int id,String label,String identifier) {
        public Option {Objects.requireNonNull(label);Objects.requireNonNull(identifier);}
    }
    private record Match(Option option,int score,int order){}
    private ComponentSearch(){}
    public static List<Option> rank(List<Option> options,String query){
        String normalized=normalize(query);
        if(normalized.isEmpty())return List.copyOf(options);
        var matches=new ArrayList<Match>();
        for(int i=0;i<options.size();i++){
            var option=options.get(i);
            int score=Math.min(score(normalized,normalize(option.label())),score(normalized,normalize(option.identifier())));
            if(score<Integer.MAX_VALUE)matches.add(new Match(option,score,i));
        }
        matches.sort(Comparator.comparingInt(Match::score).thenComparingInt(Match::order));
        return matches.stream().map(Match::option).toList();
    }
    private static String normalize(String text){
        return Normalizer.normalize(text,Normalizer.Form.NFKD).replaceAll("\\p{M}+","")
            .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").strip().replaceAll("\\s+"," ");
    }
    private static int score(String query,String name){
        if(query.equals(name))return 0;
        if(name.startsWith(query))return 10+name.length()-query.length();
        if(name.contains(query))return 40+name.indexOf(query);
        int total=100;
        for(String token:query.split(" ")){
            int best=Integer.MAX_VALUE;
            for(String word:name.split(" ")){
                if(word.equals(token))best=0;
                else if(word.startsWith(token))best=Math.min(best,10+word.length()-token.length());
                else if(word.contains(token))best=Math.min(best,40+word.indexOf(token));
                else if(token.length()>=2){
                    int at=0,first=-1,last=-1;
                    for(int i=0;i<word.length()&&at<token.length();i++)if(word.charAt(i)==token.charAt(at)){if(first<0)first=i;last=i;at++;}
                    if(at==token.length())best=Math.min(best,70+last-first+1-token.length());
                    int allowance=token.length()>=6?2:token.length()>=3?1:0;
                    if(allowance>0&&Math.abs(word.length()-token.length())<=allowance){
                        int distance=distance(token,word);
                        if(distance<=allowance)best=Math.min(best,60+distance*5);
                    }
                }
            }
            if(best==Integer.MAX_VALUE)return best;total+=best;
        }
        return total;
    }
    /** Adjacent transpositions count as one typo. */
    private static int distance(String a,String b){
        int[][] d=new int[a.length()+1][b.length()+1];
        for(int i=0;i<=a.length();i++)d[i][0]=i;
        for(int j=0;j<=b.length();j++)d[0][j]=j;
        for(int i=1;i<=a.length();i++)for(int j=1;j<=b.length();j++){
            d[i][j]=Math.min(d[i-1][j]+1,Math.min(d[i][j-1]+1,d[i-1][j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1)));
            if(i>1&&j>1&&a.charAt(i-1)==b.charAt(j-2)&&a.charAt(i-2)==b.charAt(j-1))d[i][j]=Math.min(d[i][j],d[i-2][j-2]+1);
        }
        return d[a.length()][b.length()];
    }
}
