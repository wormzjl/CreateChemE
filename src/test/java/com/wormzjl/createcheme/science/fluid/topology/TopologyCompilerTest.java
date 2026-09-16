package com.wormzjl.createcheme.science.fluid.topology;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.network.PipeResistance;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.*;

class TopologyCompilerTest {
    private TopologyCompiler.Node node(long id,TopologyCompiler.Kind kind){return new TopologyCompiler.Node(id,kind,id);}
    private TopologyCompiler.Link edge(long id,long a,long b){return new TopologyCompiler.Link(id,a,b,new PipeResistance.Geometry(1,.05,.000045,0));}
    @Test void seriesCompressionRetainsEveryGeometryElevationAndDebugDirection() {
        var result=TopologyCompiler.compile(List.of(node(1,RESERVOIR),node(2,PIPE),node(3,PIPE),node(4,RESERVOIR)),List.of(edge(10,1,2),edge(11,3,2),edge(12,3,4)));
        assertEquals(1,result.runs().size());var run=result.runs().getFirst();assertEquals(3,run.segments().size());
        assertEquals(1,run.first());assertEquals(4,run.second());assertFalse(result.views().get(11L).forward());
        assertEquals(1,run.segments().getFirst().startElevation());assertEquals(4,run.segments().getLast().endElevation());
    }
    @Test void parallelPipesAndBranchesSurviveButDeadLegsDoNotCarryFlow() {
        var result=TopologyCompiler.compile(List.of(node(1,RESERVOIR),node(2,RESERVOIR),node(3,PIPE),node(4,PIPE)),
                List.of(edge(10,1,2),edge(11,1,3),edge(12,3,2),edge(13,3,4)));
        assertEquals(2,result.runs().size());assertEquals(Set.of(13L),result.deadEndLinks());assertEquals(1,result.islands().size());
    }
    @Test void pumpOnlyBypassIsRejectedButTankBufferedRecycleAndValveBypassAreAllowed() {
        var edges=List.of(edge(10,1,2),edge(11,2,3),edge(12,3,4),edge(13,4,1));
        var pumpOnly=TopologyCompiler.compile(List.of(node(1,PUMP),node(2,PIPE),node(3,VALVE),node(4,PIPE)),edges);
        assertEquals(Set.of(1L),pumpOnly.invalidPumpCycles());
        var buffered=TopologyCompiler.compile(List.of(node(1,PUMP),node(2,PIPE),node(3,RESERVOIR),node(4,PIPE)),edges);
        assertTrue(buffered.invalidPumpCycles().isEmpty());
        var valve=TopologyCompiler.compile(List.of(node(1,VALVE),node(2,PIPE),node(3,RESERVOIR),node(4,PIPE)),edges);
        assertTrue(valve.invalidPumpCycles().isEmpty());assertEquals(1,valve.islands().size());
    }
    @Test void pipeOnlyRingIsExplicitlyUnanchoredAndCompilationIgnoresInputOrder() {
        var nodes=List.of(node(1,PIPE),node(2,PIPE),node(3,PIPE));var links=List.of(edge(10,1,2),edge(11,2,3),edge(12,3,1));
        var result=TopologyCompiler.compile(nodes,links);assertEquals(Set.of(10L,11L,12L),result.unanchoredLinks());assertTrue(result.runs().isEmpty());
        var reversed=new ArrayList<>(links);Collections.reverse(reversed);assertEquals(result,TopologyCompiler.compile(nodes,reversed));
    }
}
