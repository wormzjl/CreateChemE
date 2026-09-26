package com.wormzjl.createcheme.science.fluid.topology;

import com.wormzjl.createcheme.science.fluid.network.PipeResistance;
import java.util.*;

/** Deterministic compilation of persistent block identities; no Minecraft reads or loaded-chunk dependency. */
public final class TopologyCompiler {
    private TopologyCompiler() {}
    public enum Kind { RESERVOIR, PIPE, PUMP, VALVE, GENERATOR, VOID, FILTER, COMPRESSOR }
    public record Node(long id,Kind kind,double elevation) {
        public Node{Objects.requireNonNull(kind);if(!Double.isFinite(elevation))throw new IllegalArgumentException("Invalid elevation");}
    }
    public record Link(long id,long first,long second,PipeResistance.Geometry geometry) {
        public Link{Objects.requireNonNull(geometry);if(first==second)throw new IllegalArgumentException("Self-connected physical link");}
        long other(long id){if(first==id)return second;if(second==id)return first;throw new IllegalArgumentException("Not an endpoint");}
    }
    public record Segment(long linkId,boolean forward,PipeResistance.Geometry geometry,double startElevation,double endElevation) {}
    public record Run(long first,long second,List<Segment> segments) {
        public Run{segments=List.copyOf(segments);if(segments.isEmpty())throw new IllegalArgumentException("Empty pipe run");}
    }
    public record PipeView(int runIndex,int segmentIndex,boolean forward) {}
    public record Compiled(List<Node> retainedNodes,List<Run> runs,Map<Long,PipeView> views,Set<Long> deadEndLinks,
                           Set<Long> unanchoredLinks,Set<Long> invalidPumpCycles,List<Set<Long>> islands) {
        public Compiled {
            retainedNodes=List.copyOf(retainedNodes);runs=List.copyOf(runs);views=Map.copyOf(views);deadEndLinks=Set.copyOf(deadEndLinks);
            unanchoredLinks=Set.copyOf(unanchoredLinks);invalidPumpCycles=Set.copyOf(invalidPumpCycles);islands=islands.stream().map(Set::copyOf).toList();
        }
    }
    public static Compiled compile(List<Node> inputNodes,List<Link> inputLinks) {
        var nodes=new TreeMap<Long,Node>();var links=new TreeMap<Long,Link>();var adjacent=new HashMap<Long,List<Long>>();
        for(var node:inputNodes){if(nodes.putIfAbsent(node.id,node)!=null)throw new IllegalArgumentException("Duplicate node identity");adjacent.put(node.id,new ArrayList<>());}
        for(var link:inputLinks) {
            if(!nodes.containsKey(link.first)||!nodes.containsKey(link.second)||links.putIfAbsent(link.id,link)!=null)throw new IllegalArgumentException("Invalid graph link");
            adjacent.get(link.first).add(link.id);adjacent.get(link.second).add(link.id);
        }
        for(var neighbors:adjacent.values())neighbors.sort(Long::compare);
        var active=new HashSet<>(links.keySet());var dead=new TreeSet<Long>();var queue=new ArrayDeque<Long>();
        for(var node:nodes.values())if(node.kind==Kind.PIPE&&adjacent.get(node.id).size()<=1)queue.add(node.id);
        while(!queue.isEmpty()) {
            long id=queue.remove();if(nodes.get(id).kind!=Kind.PIPE)continue;
            var remaining=adjacent.get(id).stream().filter(active::contains).toList();if(remaining.size()!=1)continue;
            long edge=remaining.getFirst();active.remove(edge);dead.add(edge);queue.add(links.get(edge).other(id));
        }
        var retained=new TreeMap<Long,Node>();
        for(var node:nodes.values())if(node.kind!=Kind.PIPE||adjacent.get(node.id).stream().filter(active::contains).count()!=2)retained.put(node.id,node);
        var visited=new HashSet<Long>();var runs=new ArrayList<Run>();var views=new HashMap<Long,PipeView>();
        for(var start:retained.values())for(long firstLink:adjacent.get(start.id)) {
            if(!active.contains(firstLink)||visited.contains(firstLink))continue;
            long current=start.id,edgeId=firstLink;var segments=new ArrayList<Segment>();
            while(true) {
                var edge=links.get(edgeId);long next=edge.other(current);visited.add(edgeId);
                segments.add(new Segment(edge.id,edge.first==current,edge.geometry,nodes.get(current).elevation,nodes.get(next).elevation));
                if(retained.containsKey(next)) {
                    int run=runs.size();runs.add(new Run(start.id,next,segments));
                    for(int i=0;i<segments.size();i++){var segment=segments.get(i);views.put(segment.linkId,new PipeView(run,i,segment.forward));}
                    break;
                }
                long previous=edgeId;current=next;
                edgeId=adjacent.get(current).stream().filter(active::contains).filter(id->id!=previous).findFirst().orElseThrow();
            }
        }
        var unanchored=new TreeSet<>(active);unanchored.removeAll(visited);
        var islands=new ArrayList<Set<Long>>();var seen=new HashSet<Long>();
        for(var start:retained.values()) {
            if(seen.contains(start.id)||adjacent.get(start.id).stream().noneMatch(visited::contains))continue;
            var members=new TreeSet<Long>();queue.add(start.id);seen.add(start.id);
            while(!queue.isEmpty()) {
                long id=queue.remove();members.add(id);
                for(long edge:adjacent.get(id))if(visited.contains(edge)){long other=links.get(edge).other(id);if(seen.add(other))queue.add(other);}
            }
            islands.add(members);
        }
        return new Compiled(new ArrayList<>(retained.values()),runs,views,dead,unanchored,invalidPumps(nodes,links,adjacent),islands);
    }
    /** Reservoir and boundary ports are disconnected for this structural test; valves remain traversable. A compressor is a
     * mover like the pump and is held to the same rule (plan 3.7). */
    private static Set<Long> invalidPumps(Map<Long,Node> nodes,Map<Long,Link> links,Map<Long,List<Long>> adjacent) {
        var invalid=new TreeSet<Long>();
        for(var pump:nodes.values())if(pump.kind==Kind.PUMP||pump.kind==Kind.COMPRESSOR) {
            var edges=adjacent.get(pump.id);if(edges.size()!=2){invalid.add(pump.id);continue;}
            long start=links.get(edges.get(0)).other(pump.id),target=links.get(edges.get(1)).other(pump.id);
            // Separate ports on one tank are still distinct and permit a tank-buffered recycle.
            if(boundary(nodes.get(start).kind)||boundary(nodes.get(target).kind))continue;
            var seen=new HashSet<Long>();var queue=new ArrayDeque<Long>();queue.add(start);seen.add(pump.id);seen.add(start);
            while(!queue.isEmpty()) {
                long current=queue.remove();if(current==target){invalid.add(pump.id);break;}
                for(long edge:adjacent.get(current)) {
                    long other=links.get(edge).other(current);
                    if(!boundary(nodes.get(other).kind)&&seen.add(other))queue.add(other);
                }
            }
        }
        return invalid;
    }
    private static boolean boundary(Kind kind){return kind==Kind.RESERVOIR||kind==Kind.GENERATOR||kind==Kind.VOID;}
}
