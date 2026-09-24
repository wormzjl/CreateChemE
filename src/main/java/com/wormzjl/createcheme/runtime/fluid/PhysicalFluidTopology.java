package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import java.util.*;

/** Pure adapter from registered block identities to scientific islands; never reads chunks or block entities. */
public final class PhysicalFluidTopology {
    private PhysicalFluidTopology() {}
    public enum Direction {
        DOWN(0,-1,0),UP(0,1,0),NORTH(0,0,-1),SOUTH(0,0,1),WEST(-1,0,0),EAST(1,0,0);
        final int x,y,z;
        Direction(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
    }
    public record Position(String dimension,int x,int y,int z) {
        public Position {Objects.requireNonNull(dimension);if(dimension.isBlank())throw new IllegalArgumentException("Missing dimension");}
        Position offset(Direction d){return new Position(dimension,Math.addExact(x,d.x),Math.addExact(y,d.y),Math.addExact(z,d.z));}
    }
    public record Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,
                         PipeResistance.Geometry geometry,FlowControl control) {
        public Device {
            if(id<=0)throw new IllegalArgumentException("Physical identities must be positive");
            Objects.requireNonNull(position);Objects.requireNonNull(kind);Objects.requireNonNull(facing);Objects.requireNonNull(geometry);Objects.requireNonNull(control);
            boolean valid=switch(kind) {case PUMP->control instanceof FlowControl.Pump;case VALVE->control instanceof FlowControl.PressureValve;default->control instanceof FlowControl.Passive;};
            if(!valid)throw new IllegalArgumentException("Device/control mismatch");
        }
        boolean boundary(){return kind==TopologyCompiler.Kind.RESERVOIR||kind==TopologyCompiler.Kind.GENERATOR||kind==TopologyCompiler.Kind.VOID;}
        boolean actuator(){return kind==TopologyCompiler.Kind.PUMP||kind==TopologyCompiler.Kind.VALVE;}
        boolean connects(Direction direction){return !actuator()&&kind!=TopologyCompiler.Kind.FILTER||direction.x*facing.x+direction.y*facing.y+direction.z*facing.z!=0;}
    }
    public record View(int islandIndex,long pipeId,boolean forward) {}
    public record Island(Set<Long> physicalIds,PassiveNetwork graph,Optional<String> error) {
        public Island {physicalIds=Set.copyOf(physicalIds);Objects.requireNonNull(graph);Objects.requireNonNull(error);}
    }
    public record Compiled(List<Island> islands,Map<Long,List<View>> pipeViews,Map<Long,String> diagnostics) {
        public Compiled {islands=List.copyOf(islands);var copied=new HashMap<Long,List<View>>();pipeViews.forEach((id,views)->copied.put(id,List.copyOf(views)));pipeViews=Map.copyOf(copied);diagnostics=Map.copyOf(diagnostics);}
    }
    private record Point(double x,double y,double z) {}
    public static long filterIdentity(long physicalId){return Long.MIN_VALUE+physicalId;}
    public static Compiled compile(Collection<Device> devices,Map<Long,PassiveNetwork.Reservoir> boundaryStates) {
        return compile(devices,boundaryStates,Map.of(),boundaryStates.values().stream().findFirst().map(PassiveNetwork.Reservoir::state).orElse(null));
    }
    public static Compiled compile(Collection<Device> devices,Map<Long,PassiveNetwork.Reservoir> boundaryStates,Map<Long,InlineFilter> filterStock,com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.State idleSeed) {
        var physical=new TreeMap<Long,Device>();var positions=new HashMap<Position,Device>();var points=new HashMap<Long,Point>();
        var nodes=new ArrayList<TopologyCompiler.Node>();var links=new ArrayList<TopologyCompiler.Link>();var linkOwner=new HashMap<Long,Long>();
        for(var d:devices) {
            if(physical.putIfAbsent(d.id,d)!=null||positions.putIfAbsent(d.position,d)!=null)throw new IllegalArgumentException("Duplicate physical identity or position");
            var p=d.position;nodes.add(new TopologyCompiler.Node(d.id,d.kind,p.y));points.put(d.id,new Point(p.x,p.y,p.z));
            if(d.boundary()) {
                var state=boundaryStates.get(d.id);
                var expected=switch(d.kind){case RESERVOIR->PassiveNetwork.NodeKind.RESERVOIR;case GENERATOR->PassiveNetwork.NodeKind.GENERATOR;case VOID->PassiveNetwork.NodeKind.VOID;default->throw new AssertionError();};
                if(state==null||state.id()!=d.id||state.kind()!=expected||state.elevation()!=p.y)throw new IllegalArgumentException("Missing/incompatible authoritative boundary state");
            }
        }
        long virtual=-1,linkId=1;
        for(var d:physical.values())for(var direction:Direction.values()) {
            if(!d.connects(direction))continue;
            var other=positions.get(d.position.offset(direction));
            if(other==null||other.id<=d.id||!other.connects(direction)||d.boundary()&&other.boundary())continue;
            if(d.boundary()||other.boundary()) {
                var owner=d.boundary()?other:d;links.add(new TopologyCompiler.Link(linkId,d.id,other.id,scaled(owner.geometry,.5)));linkOwner.put(linkId++,owner.id);
            } else {
                long middle=virtual--;var a=points.get(d.id);var b=points.get(other.id);var p=new Point((a.x+b.x)/2,(a.y+b.y)/2,(a.z+b.z)/2);
                points.put(middle,p);nodes.add(new TopologyCompiler.Node(middle,TopologyCompiler.Kind.PIPE,p.y));
                links.add(new TopologyCompiler.Link(linkId,d.id,middle,scaled(d.geometry,.5)));linkOwner.put(linkId++,d.id);
                links.add(new TopologyCompiler.Link(linkId,middle,other.id,scaled(other.geometry,.5)));linkOwner.put(linkId++,other.id);
            }
        }
        var compiled=TopologyCompiler.compile(nodes,links);var byLink=new HashMap<Long,TopologyCompiler.Link>();for(var link:links)byLink.put(link.id(),link);
        var diagnostics=new HashMap<Long,String>();
        for(long link:compiled.deadEndLinks())diagnostics.put(linkOwner.get(link),"NO FLOW: dead-end pipe");
        for(long link:compiled.unanchoredLinks())diagnostics.put(linkOwner.get(link),"NO FLOW: no reservoir or boundary");
        for(long pump:compiled.invalidPumpCycles())diagnostics.put(pump,"ERROR: pump needs two ports without a zero-storage bypass");
        var groups=new ArrayList<>(compiled.islands());var included=new HashSet<Long>();groups.forEach(included::addAll);
        for(var d:physical.values())if(d.boundary()&&!included.contains(d.id))groups.add(Set.of(d.id));
        var retained=new HashMap<Long,TopologyCompiler.Node>();for(var node:compiled.retainedNodes())retained.put(node.id(),node);
        var islands=new ArrayList<Island>();var views=new HashMap<Long,List<View>>();var pipeIdentities=new HashSet<Long>();
        // Each run belongs to the group that holds its first node (groups are disjoint); bucketed once, in run order,
        // so assembling the islands reads every run once instead of every run for every island.
        var groupOf=new HashMap<Long,Integer>();for(int g=0;g<groups.size();g++)for(long id:groups.get(g))groupOf.put(id,g);
        var runsOf=new ArrayList<List<TopologyCompiler.Run>>(groups.size());for(int g=0;g<groups.size();g++)runsOf.add(new ArrayList<>());
        for(var run:compiled.runs()){Integer g=groupOf.get(run.first());if(g!=null)runsOf.get(g).add(run);}
        for(int group=0;group<groups.size();group++) {
            var members=groups.get(group);
            var boundaries=members.stream().filter(boundaryStates::containsKey).sorted().toList();
            if(boundaries.isEmpty()){for(long id:members)if(physical.containsKey(id))diagnostics.put(id,"NO FLOW: no reservoir or boundary");continue;}
            // A zero-holdup junction is minted with the island's first boundary state as a property
            // guess - a temperature, a pressure and a composition to start a solve from - and never
            // with that boundary's stock. Solids are stock: carrying them over mints the
            // generator's whole charge of particles on every junction the compiler creates, on
            // every topology edit, including the junction on the far side of a filter edge, where
            // no connection can ever deliver them. See documentation/FULL_TANK_SOLIDS_EVENT.md.
            var boundarySeed=boundaryStates.get(boundaries.getFirst()).state();
            var seed=boundarySeed.solids().empty()?boundarySeed:boundarySeed.withSolids(com.wormzjl.createcheme.science.fluid.state.SolidInventory.EMPTY);
            var reservoirs=new ArrayList<PassiveNetwork.Reservoir>();var indices=new HashMap<Long,Integer>();
            for(long id:new TreeSet<>(members))if(retained.containsKey(id)) {
                indices.put(id,reservoirs.size());var node=retained.get(id);var boundary=boundaryStates.get(id);
                reservoirs.add(boundary!=null?boundary:new PassiveNetwork.Reservoir(id,node.elevation(),seed,PassiveNetwork.NodeKind.JUNCTION));
            }
            var pipes=new ArrayList<PassiveNetwork.Pipe>();int islandIndex=islands.size();var positiveFilterEdges=new HashMap<Long,Set<Long>>();
            for(var run:runsOf.get(group)) {
                // A passive return to the same zero-holdup node has no driving pressure or owned stock.
                if(run.first()==run.second()){for(var segment:run.segments())diagnostics.put(linkOwner.get(segment.linkId()),"NO FLOW: passive return loop");continue;}
                var start=physical.get(run.first());var end=physical.get(run.second());
                var startLink=byLink.get(run.segments().getFirst().linkId());var endLink=byLink.get(run.segments().getLast().linkId());
                boolean fromStart=outlet(start,points.get(other(startLink,run.first()))),fromEnd=outlet(end,points.get(other(endLink,run.second())));
                var sections=run.segments().stream().map(TopologyCompiler.Segment::geometry).toList();
                int a=indices.get(run.first()),b=indices.get(run.second());
                long pipeId=runIdentity(run,linkOwner,0);if(!pipeIdentities.add(pipeId))throw new IllegalStateException("Compiled pipe identity collision");
                if(fromStart&&fromEnd) {
                    int middle=reservoirs.size();reservoirs.add(new PassiveNetwork.Reservoir(virtual--,.5*(reservoirs.get(a).elevation()+reservoirs.get(b).elevation()),seed,PassiveNetwork.NodeKind.JUNCTION));
                    var halves=sections.stream().map(g->scaled(g,.5)).toList();
                    pipes.add(new PassiveNetwork.Pipe(pipeId,a,middle,halves,control(start,compiled.invalidPumpCycles())));mapViews(views,run,linkOwner,physical,islandIndex,pipeId,true);
                    pipeId=runIdentity(run,linkOwner,1);if(!pipeIdentities.add(pipeId))throw new IllegalStateException("Compiled pipe identity collision");
                    pipes.add(new PassiveNetwork.Pipe(pipeId,b,middle,halves,control(end,compiled.invalidPumpCycles())));mapViews(views,run,linkOwner,physical,islandIndex,pipeId,false);
                } else {
                    pipes.add(new PassiveNetwork.Pipe(pipeId,fromEnd?b:a,fromEnd?a:b,sections,fromStart?control(start,compiled.invalidPumpCycles()):fromEnd?control(end,compiled.invalidPumpCycles()):new FlowControl.Passive()));
                    mapViews(views,run,linkOwner,physical,islandIndex,pipeId,!fromEnd);
                    if(start!=null&&start.kind==TopologyCompiler.Kind.FILTER&&positiveSide(start,points.get(other(startLink,run.first()))))positiveFilterEdges.computeIfAbsent(start.id,k->new HashSet<>()).add(pipeId);
                    if(end!=null&&end.kind==TopologyCompiler.Kind.FILTER&&positiveSide(end,points.get(other(endLink,run.second()))))positiveFilterEdges.computeIfAbsent(end.id,k->new HashSet<>()).add(pipeId);
                }
            }
            for(long id:new TreeSet<>(members))if(physical.containsKey(id)&&physical.get(id).kind==TopologyCompiler.Kind.FILTER){
                int negative=indices.get(id),positive=reservoirs.size();var device=physical.get(id);
                reservoirs.add(new PassiveNetwork.Reservoir(virtual--,device.position.y,seed,PassiveNetwork.NodeKind.JUNCTION));
                var positiveEdges=positiveFilterEdges.getOrDefault(id,Set.of());
                for(int i=0;i<pipes.size();i++){var p=pipes.get(i);if(positiveEdges.contains(p.id()))pipes.set(i,new PassiveNetwork.Pipe(p.id(),p.first()==negative?positive:p.first(),p.second()==negative?positive:p.second(),p.sections(),p.control(),p.blockedDirections(),p.filter()));}
                long identity=filterIdentity(id);pipes.add(new PassiveNetwork.Pipe(identity,negative,positive,device.geometry).withFilter(filterStock.getOrDefault(id,InlineFilter.empty())));
                views.put(id,List.of(new View(islandIndex,identity,true)));
            }
            var ids=new TreeSet<Long>();for(long id:members)if(physical.containsKey(id))ids.add(id);
            Optional<String> error=ids.stream().filter(compiled.invalidPumpCycles()::contains).findFirst().map(id->diagnostics.get(id));
            islands.add(new Island(ids,new PassiveNetwork(reservoirs,pipes),error));
        }
        for(var d:physical.values())if(d.kind==TopologyCompiler.Kind.PIPE&&!views.containsKey(d.id))diagnostics.putIfAbsent(d.id,"NO FLOW: unconnected pipe");
        // An unconnected filter still owns its cake. Equal fixed property ports have exactly zero flow
        // and no finite fluid ownership; they keep the filter's simulation clock available for later edits.
        for(var device:physical.values())if(device.kind==TopologyCompiler.Kind.FILTER&&!views.containsKey(device.id)){
            if(idleSeed==null)throw new IllegalArgumentException("Disconnected filter needs a zero-flow property seed");
            long identity=filterIdentity(device.id);int island=islands.size();double y=device.position.y;
            var dormant=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(virtual--,y,idleSeed,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(virtual--,y,idleSeed,PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(identity,0,1,device.geometry).withFilter(filterStock.getOrDefault(device.id,InlineFilter.empty())).withBlockedDirections(3)));
            islands.add(new Island(Set.of(device.id),dormant,Optional.empty()));views.put(device.id,List.of(new View(island,identity,true)));diagnostics.put(device.id,"NO FLOW: disconnected filter");
        }
        return new Compiled(islands,views,diagnostics);
    }
    private static long other(TopologyCompiler.Link link,long id){return link.first()==id?link.second():link.first();}
    /** Independent edits must not renumber a different island's last-interval debug history. */
    private static long runIdentity(TopologyCompiler.Run run,Map<Long,Long> owners,int part) {
        long hash=0xcbf29ce484222325L;hash=(hash^run.first())*0x100000001b3L;hash=(hash^run.second())*0x100000001b3L;
        for(var segment:run.segments())hash=(hash^owners.get(segment.linkId()))*0x100000001b3L;
        hash=(hash^part)*0x100000001b3L;return (hash&Long.MAX_VALUE)==0?1:hash&Long.MAX_VALUE;
    }
    private static FlowControl control(Device device,Set<Long> invalidPumps) {
        if(device.control instanceof FlowControl.Pump pump&&invalidPumps.contains(device.id))return new FlowControl.Pump(0,pump.maximumAddedPressure(),pump.efficiency());
        return device.control;
    }
    private static boolean positiveSide(Device device,Point neighbor){var p=device.position;var d=device.facing;return (neighbor.x-p.x)*d.x+(neighbor.y-p.y)*d.y+(neighbor.z-p.z)*d.z>0;}
    private static boolean outlet(Device device,Point neighbor) {
        if(device==null||!device.actuator())return false;var p=device.position;var d=device.facing;
        return (neighbor.x-p.x)*d.x+(neighbor.y-p.y)*d.y+(neighbor.z-p.z)*d.z>0;
    }
    private static PipeResistance.Geometry scaled(PipeResistance.Geometry g,double fraction){return new PipeResistance.Geometry(g.length()*fraction,g.diameter(),g.roughness(),g.minorLoss()*fraction);}
    private static void mapViews(Map<Long,List<View>> views,TopologyCompiler.Run run,Map<Long,Long> owners,Map<Long,Device> physical,int island,long pipe,boolean forward) {
        for(var segment:run.segments()) {
            long id=owners.get(segment.linkId());if(physical.get(id).kind!=TopologyCompiler.Kind.PIPE)continue;
            var entry=new View(island,pipe,forward);var list=views.computeIfAbsent(id,ignored->new ArrayList<>());if(!list.contains(entry))list.add(entry);
        }
    }
}
