package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

import static com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.GAS;
import static com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.OIL;
import static com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.WATER;

/**
 * The inlet phase check of a pump or compressor (decisions D1, D2, D6 of
 * documentation/2026-09-26-phase-ports-and-compressor; plan 3.6 and 3.7): what the device's suction line delivers,
 * measured on a committed state, and whether the device is refused ({@link FlowControl.Mode#INLET_WRONG_PHASE}) for the
 * slice that starts there. Every method is a pure function of the graph (its connections and node states), the slice
 * duration and, for the decision, the committed endpoint modes: the solver decides with it, and the device view
 * ({@code FluidWorldAuthority}) formats the same {@link #reason} from the committed graph.
 *
 * <p><b>The supply walk.</b> From the device edge's first node: a vessel, generator, void or port there supplies the
 * stream of that end. A junction is walked through: the device's own junction, if it has exactly one other connection
 * and that connection is passive, is crossed, and so is every following junction with two connections neither of which
 * carries an actuator (the run {@code closeDeadHeads} walks); the walk ends at the first node that is not such a junction.
 * If that node is a vessel, generator, void or port, the supply is the stream of the last link's end there; if it is a
 * junction (a branch, or one an actuator touches), and when the device's own junction is a branch or a dead end, the
 * supply is that junction's state mixture. So the check reads the source, not the device's own junction: flashing at the
 * inlet does not refuse a device drawing a saturated liquid (risk R2), it only lowers the suction density and so the
 * pump's rise limit.
 *
 * <p><b>What a phase port supplies</b> (decision A37, answering D11 open item 6). The priority draw of decision D11 at the
 * device's target volume flow over the slice, on the committed state: the target volume {@code Q T} is filled from the
 * port's priority order, each phase up to its share of the vessel ({@code V_phase / N}, {@code N} the vessel's phase-port
 * ends that may carry outflow by the static rule, the D11 capacity over the slice), the last phase the vessel holds with
 * the rest. So a dry bottom port reads as gas, and a bottom port holding less liquid than the device would move in one
 * slice reads the gas it would break through to; a trace of condensate left after a drain does not make it read liquid.
 * With no target volume ({@code Q = 0}, or a rate solve with no slice) it is the port's leading phase, the draw at a
 * vanishing flow (decision A28). A BULK end supplies the node's bulk.
 *
 * <p><b>Measures.</b> For a pump the vapour share of the supply's fluid volume, {@code V_v / (V_v + V_l + V_w)} (solids
 * excluded: a pump moves them within the mobility rules); for a compressor the condensed share of its mass
 * ({@code (m_l + m_w + m_s) / m}) and the largest solid population's share of its volume (fluid and solids).
 */
public final class InletPhase {
    private InletPhase() {}

    /**
     * What a device's suction line delivers: the node the walk ended at ({@code node} its index, {@code nodeId} its id),
     * the end's port there (BULK for a junction mixture and for a generator), and the three measures.
     */
    public record Supply(int node,long nodeId,PassiveNetwork.PhasePort port,double vapourVolumeShare,double condensedMassShare,double largestSolidVolumeFraction) {}

    /**
     * The walk from a device edge's first node to what supplies it: {@code links} the connections crossed, in walk
     * order, with {@code donors} the node each is drawn from (the end away from the device); {@code node} the node the
     * walk ended at and {@code via} the link it arrived through, -1 when it ended at the device's own first node.
     */
    record Walk(int[] links,int[] donors,int node,int via) {}

    /** The suction walk of connection {@code edge}; see the class comment. */
    static Walk walk(PassiveNetwork graph,int edge) {
        int nodes=graph.reservoirs().size();int[] degree=new int[nodes];boolean[] actuated=new boolean[nodes];
        for(var pipe:graph.pipes()) {
            degree[pipe.first()]++;degree[pipe.second()]++;
            if(!(pipe.control() instanceof FlowControl.Passive)){actuated[pipe.first()]=true;actuated[pipe.second()]=true;}
        }
        var links=new ArrayList<Integer>();var donors=new ArrayList<Integer>();
        int at=graph.pipes().get(edge).first(),from=edge,via=-1;
        boolean[] seen=new boolean[nodes];seen[at]=true;
        while(graph.reservoirs().get(at).junction()&&degree[at]==2) {
            // The device's own junction is crossed whatever its actuator; every later one only if no actuator touches it.
            if(via>=0&&actuated[at])break;
            int next=-1;
            for(int e=0;e<graph.pipes().size();e++){var p=graph.pipes().get(e);if(e!=from&&(p.first()==at||p.second()==at)){next=e;break;}}
            if(next<0)break;
            var link=graph.pipes().get(next);
            if(!(link.control() instanceof FlowControl.Passive))break;
            int beyond=link.first()==at?link.second():link.first();
            if(beyond==at||seen[beyond])break;
            links.add(next);donors.add(beyond);seen[beyond]=true;at=beyond;from=next;via=next;
        }
        return new Walk(links.stream().mapToInt(Integer::intValue).toArray(),donors.stream().mapToInt(Integer::intValue).toArray(),at,via);
    }

    /**
     * What connection {@code edge}'s suction line delivers on the graph's states, for a device whose target is
     * {@code targetVolumeFlow} (m3/s) over a slice of {@code duration} seconds (zero or less: no slice, the leading
     * phase of a phase port).
     */
    public static Supply supply(FluidThermodynamics model,PassiveNetwork graph,int edge,double targetVolumeFlow,double duration) {
        var walk=walk(graph,edge);
        var node=graph.reservoirs().get(walk.node());var state=node.state();
        PassiveNetwork.PhasePort port;
        if(node.junction())port=PassiveNetwork.PhasePort.BULK;
        else port=walk.via()<0?graph.pipes().get(edge).firstPort():graph.pipes().get(walk.via()).portAt(walk.node());
        double[] drawn=draw(model,graph,walk.node(),port,targetVolumeFlow*Math.max(duration,0));
        return measure(model,state,drawn,walk.node(),node.id(),port);
    }

    /**
     * The volume (m3) of each phase stream ({@link FluidThermodynamics#phaseVolume}, solids with their liquid) drawn from
     * {@code node} through {@code port} for a target volume {@code volume}; null for the bulk (a BULK end, or a vessel
     * holding none of the phases). With no target volume, the leading phase alone (a unit volume).
     */
    private static double[] draw(FluidThermodynamics model,PassiveNetwork graph,int node,PassiveNetwork.PhasePort port,double volume) {
        if(port==PassiveNetwork.PhasePort.BULK)return null;
        var state=graph.reservoirs().get(node).state();
        int[] order=PhaseDraw.order(model,state,port);
        int last=-1;for(int phase:order)if(FluidThermodynamics.holdsPhase(state,phase))last=phase;
        if(last<0)return null;
        double[] drawn=new double[3];
        if(!(volume>0)){drawn[PhaseDraw.leading(model,state,port)]=1;return drawn;}
        // The vessel's phase-port ends that may carry outflow from it by the static rule: each phase is shared among them.
        int ends=0;
        for(var pipe:graph.pipes())for(int end=0;end<2;end++) {
            if((end==0?pipe.first():pipe.second())!=node||(end==0?pipe.firstPort():pipe.secondPort())==PassiveNetwork.PhasePort.BULK)continue;
            if(PassiveStepSolver.boundaryAllowed(graph,pipe,end==0?1:-1))ends++;
        }
        ends=Math.max(1,ends);
        double remaining=volume;
        for(int phase:order) {
            if(!FluidThermodynamics.holdsPhase(state,phase))continue;
            if(phase==last){drawn[phase]=remaining;break;}
            double take=Math.min(remaining,FluidThermodynamics.phaseVolume(state,phase)/ends);
            drawn[phase]=take;remaining-=take;
            if(!(remaining>0))break;
        }
        return drawn;
    }

    private static Supply measure(FluidThermodynamics model,FluidThermodynamics.State state,double[] drawn,int node,long id,PassiveNetwork.PhasePort port) {
        double[] fluidVolume={state.vaporVolume(),state.liquidVolume(),state.waterVolume()};
        double gas,oil,water,gasMass,condensedMass,solidScale,total;
        if(drawn==null) {
            gas=fluidVolume[GAS];oil=fluidVolume[OIL];water=fluidVolume[WATER];
            gasMass=model.vaporMass(state);condensedMass=model.liquidMass(state);
            solidScale=1;total=gas+oil+water+state.solidMoments().volume();
        } else {
            double[] volume=new double[3],mass=new double[3];solidScale=0;total=0;
            for(int phase=0;phase<3;phase++) {
                if(!(drawn[phase]>0))continue;
                double stream=FluidThermodynamics.phaseVolume(state,phase);
                volume[phase]=drawn[phase]*fluidVolume[phase]/stream;
                mass[phase]=drawn[phase]*model.phaseMass(state,phase)/stream;
                solidScale+=drawn[phase]/stream*FluidThermodynamics.phaseSolidShare(state,phase);
                total+=drawn[phase];
            }
            gas=volume[GAS];oil=volume[OIL];water=volume[WATER];gasMass=mass[GAS];condensedMass=mass[OIL]+mass[WATER];
        }
        double fluid=gas+oil+water;
        double largestSolid=0;
        if(total>0)for(var population:state.solids().populations())largestSolid=Math.max(largestSolid,population.volume()*solidScale/total);
        return new Supply(node,id,port,fluid>0?gas/fluid:0,gasMass+condensedMass>0?condensedMass/(gasMass+condensedMass):0,largestSolid);
    }

    /**
     * Which connections of {@code graph} are refused for the slice that starts on its states: every mover whose supply
     * ({@link #supply}, over {@code duration}) it refuses ({@link FlowControl.Mover#refuses}), with its hysteresis input
     * the committed endpoint mode {@code committedModes} (null, or of another length: none refused before). One flag per
     * connection.
     */
    public static boolean[] decide(FluidThermodynamics model,PassiveNetwork graph,List<FlowControl.Mode> committedModes,double duration) {
        boolean[] refused=new boolean[graph.pipes().size()];
        boolean carried=committedModes!=null&&committedModes.size()==refused.length;
        for(int edge=0;edge<refused.length;edge++) {
            if(!(graph.pipes().get(edge).control() instanceof FlowControl.Mover mover))continue;
            var node=graph.reservoirs().get(graph.pipes().get(edge).first());
            if(node.empty())continue;
            boolean before=carried&&committedModes.get(edge)==FlowControl.Mode.INLET_WRONG_PHASE;
            refused[edge]=mover.refuses(supply(model,graph,edge,mover.targetVolumeFlow(),duration),before);
        }
        return refused;
    }

    /**
     * The reason a refused mover shows (decision D6), which the device view shows with the node named as a device: "ERROR: pump inlet not liquid
     * (vapour 34.0 % by volume, from node 12)", "ERROR: compressor inlet not gas (condensed 2.5 % by mass, from node 12)"
     * or, for solids, "ERROR: compressor inlet not gas (solids 1.0e-03 by volume, from node 12)"; {@code node} is the id of
     * the node the supply walk ended at.
     */
    public static String reason(FlowControl.Mover mover,Supply supply) {
        return switch(mover) {
            case FlowControl.Pump ignored->String.format(Locale.ROOT,"ERROR: pump inlet not liquid (vapour %.1f %% by volume, from node %d)",100*supply.vapourVolumeShare(),supply.nodeId());
            case FlowControl.Compressor ignored->supply.largestSolidVolumeFraction()>com.wormzjl.createcheme.science.fluid.transport.SlurryTransport.DEFAULT_TRACE_VOLUME_FRACTION
                    ?String.format(Locale.ROOT,"ERROR: compressor inlet not gas (solids %.1e by volume, from node %d)",supply.largestSolidVolumeFraction(),supply.nodeId())
                    :String.format(Locale.ROOT,"ERROR: compressor inlet not gas (condensed %.1f %% by mass, from node %d)",100*supply.condensedMassShare(),supply.nodeId());
        };
    }
    /** {@link #reason} of connection {@code edge} of {@code graph} (a mover) over a slice of {@code duration} seconds. */
    public static String reason(FluidThermodynamics model,PassiveNetwork graph,int edge,double duration) {
        var mover=(FlowControl.Mover)graph.pipes().get(edge).control();
        return reason(mover,supply(model,graph,edge,mover.targetVolumeFlow(),duration));
    }
}
