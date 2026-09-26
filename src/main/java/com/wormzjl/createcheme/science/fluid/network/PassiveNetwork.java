package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;

/** Immutable scientific graph snapshot, independent of Minecraft objects and chunk residency. */
public record PassiveNetwork(List<Reservoir> reservoirs,List<Pipe> pipes,List<ScheduledTransfer> scheduledTransfers) {
    public PassiveNetwork(List<Reservoir> reservoirs,List<Pipe> pipes){this(reservoirs,pipes,List.of());}
    /** PORT is an internal, bidirectional fixed state for instantaneous rate evaluation; never a world object. */
    public enum NodeKind { RESERVOIR, JUNCTION, GENERATOR, VOID, PORT }
    /**
     * What one end of a connection draws from its node when the flow leaves the node through it: the whole state
     * ({@code BULK}, every connection before phase-selective outlets), the vapour ({@code VAPOR}: hydrocarbon vapour and
     * water vapour, a tank's top face) or the condensed phases ({@code LIQUID}: hydrocarbon liquid, free water and every
     * mobile solid, a tank's bottom face; decision A2 of documentation/2026-09-26-phase-ports-and-compressor). A port
     * acts on outflow only: inflow through any port is delivered to the vessel as through a bulk end (D3). The port's
     * driving pressure is the node's pressure plus a per-end offset ({@code PassiveStepSolver.endPressure}), zero until
     * the level head (D9), in both directions. Only a finite vessel has phases to select from, so a non-BULK port is valid
     * only on an end whose node is a {@link NodeKind#RESERVOIR} or its rate-solve copy, a {@link NodeKind#PORT}. Named
     * {@code PhasePort} rather than {@code Port} because {@link NodeKind#PORT} is a node kind.
     */
    public enum PhasePort { BULK, VAPOR, LIQUID }
    public PassiveNetwork {
        reservoirs=List.copyOf(reservoirs);pipes=List.copyOf(pipes);scheduledTransfers=List.copyOf(scheduledTransfers);
        if(reservoirs.isEmpty())throw new IllegalArgumentException("Empty island");
        var ids=new HashSet<Long>();for(var reservoir:reservoirs)if(!ids.add(reservoir.id))throw new IllegalArgumentException("Duplicate reservoir identity");
        var edges=new HashSet<Long>();for(var pipe:pipes) {
            if(pipe.first<0||pipe.second<0||pipe.first>=reservoirs.size()||pipe.second>=reservoirs.size()
                    ||pipe.first==pipe.second||!edges.add(pipe.id))throw new IllegalArgumentException("Invalid pipe endpoint/identity");
            if(!phaseSelectable(reservoirs.get(pipe.first),pipe.firstPort)||!phaseSelectable(reservoirs.get(pipe.second),pipe.secondPort))
                throw new IllegalArgumentException("A phase port needs a vessel end (reservoir or its port copy)");
        }
        var transfers=new HashSet<Long>();
        for(var transfer:scheduledTransfers) {
            if(transfer.node()>=reservoirs.size()||!transfers.add(transfer.id())||ids.contains(transfer.id()))throw new IllegalArgumentException("Invalid scheduled transfer identity/endpoint");
            var node=reservoirs.get(transfer.node());
            if(node.kind()!=NodeKind.RESERVOIR&&node.kind()!=NodeKind.PORT)throw new IllegalArgumentException("Scheduled material needs a finite reservoir");
            if(transfer instanceof ScheduledTransfer.Injection input&&input.molesPerSecond().length!=node.inventory().moles().length)throw new IllegalArgumentException("Injection basis mismatch");
        }
    }
    /** Whether this end may carry {@code port}: BULK anywhere, a phase port only on a vessel or its rate-solve copy. */
    private static boolean phaseSelectable(Reservoir node,PhasePort port) {
        return port==PhasePort.BULK||node.kind==NodeKind.RESERVOIR||node.kind==NodeKind.PORT;
    }
    /**
     * The inventory a junction is built with from a property state: nothing yet. A junction owns a holdup sized to its
     * connections ({@link #sizeJunctionHoldups}), which its constructor does not know, so it is minted empty and sized
     * once its island's pipes are known: by {@code PhysicalRegistry} before the new island is booked into the world
     * ledger, and otherwise at the entry of every solve. An empty junction inventory means exactly "not sized yet": a
     * sized one always owns positive mass.
     */
    static Inventory unsizedJunction(FluidThermodynamics.State state) {
        return new Inventory(state.volume(),new double[state.componentCount()],0,SolidInventory.EMPTY);
    }
    /** Whether a junction still carries the empty inventory of {@link #unsizedJunction}. */
    static boolean unsized(Reservoir node) {
        if(!node.junction()||!node.inventory().solids().empty()||node.inventory().internalEnergy()!=0)return false;
        for(double n:node.inventory().moles)if(n!=0)return false;
        return true;
    }
    /** An owned junction inventory of {@code holdup} kg of the property state's composition (fluid and solids scaled
     * alike), energy field in enthalpy form ({@code m_J h}), which is the form the junction rows
     * ({@code PassiveStepSolver.Equations.junctionInflow}), the reconstruction and the conservation audit read. The
     * volume field is the state's; nothing reads it for a junction. */
    static Inventory mintedJunction(FluidThermodynamics.State state,double holdup) {
        var moles=com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(state);double scale=holdup/state.mass();
        for(int c=0;c<moles.length;c++)moles[c]*=scale;
        return new Inventory(state.volume(),moles,holdup*state.enthalpy()/state.mass(),state.solids().empty()?SolidInventory.EMPTY:state.solids().scale(scale));
    }
    /**
     * Sizes every junction that is not sized yet ({@link #unsized}), once, from its seed state and its connections:
     * {@code m_J = PassiveStepSolver.HOLDUP_TAU * max over its connections of rho_seed * minimumArea * velocityLimit(seed)},
     * the velocity cap's mass flow evaluated on the junction's seed state (a filter connection uses the same formula; its
     * cake limit is a step quantity). An unconnected junction, which carries nothing, is given 1 kg so its stock is
     * positive. A graph with no unsized junction is returned unchanged.
     *
     * <p>The mint is once, at the seed state: a later lower density lowers the cap flow and only shortens the mixing lag;
     * a higher one (liquid arriving in a gas fitting) lengthens it. The owned mass then stays exactly m_J (the mass pin of
     * {@code ConservativeTransport.reconstruct}); a topology edit recompiles the island and mints its junctions again.
     */
    public static PassiveNetwork sizeJunctionHoldups(PassiveNetwork graph,FluidThermodynamics model) {
        List<Reservoir> sized=null;
        for(int i=0;i<graph.reservoirs.size();i++) {
            var node=graph.reservoirs.get(i);
            if(!unsized(node))continue;
            var state=node.state();double rho=state.mass()/state.volume(),velocity=model.velocityLimit(state),capacity=0;
            for(var pipe:graph.pipes)if(pipe.first==i||pipe.second==i)capacity=Math.max(capacity,rho*pipe.minimumArea()*velocity);
            double mass=PassiveStepSolver.HOLDUP_TAU*capacity;
            if(!(mass>0))mass=1;
            if(sized==null)sized=new ArrayList<>(graph.reservoirs);
            sized.set(i,new Reservoir(node.id(),node.elevation(),state,node.kind(),mintedJunction(state,mass)));
        }
        return sized==null?graph:new PassiveNetwork(sized,graph.pipes,graph.scheduledTransfers);
    }
    /** A vessel stores its inventory; a junction owns a small holdup (see {@link #sizeJunctionHoldups}); a generator, a
     * void and a port hold a prescribed state. */
    public record Reservoir(long id,double elevation,FluidThermodynamics.State state,NodeKind kind,Inventory inventory) {
        public Reservoir(long id,double elevation,FluidThermodynamics.State state){this(id,elevation,state,NodeKind.RESERVOIR);}
        public Reservoir(long id,double elevation,FluidThermodynamics.State state,boolean junction){this(id,elevation,state,junction?NodeKind.JUNCTION:NodeKind.RESERVOIR);}
        public Reservoir(long id,double elevation,FluidThermodynamics.State state,NodeKind kind){this(id,elevation,state,kind,kind==NodeKind.JUNCTION?unsizedJunction(state):new Inventory(state.volume(),com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids()));}
        public Reservoir {Objects.requireNonNull(state);Objects.requireNonNull(kind);Objects.requireNonNull(inventory);if(!Double.isFinite(elevation)||inventory.moles.length!=state.componentCount())throw new IllegalArgumentException("Invalid reservoir snapshot");}
        public boolean junction(){return kind==NodeKind.JUNCTION;}
        public boolean fixed(){return kind==NodeKind.GENERATOR||kind==NodeKind.VOID||kind==NodeKind.PORT;}
        /** An evacuated vessel retains only a numerical guess in state, never physical temperature
         * or stored gas. Canonical inventory is the sole authority for empty/nonempty ownership. */
        public boolean empty(){if(kind!=NodeKind.RESERVOIR||!inventory.solids.empty())return false;for(double n:inventory.moles)if(n!=0)return false;return true;}
    }
    /** Conserved kernel values. The enclosing world snapshot supplies the component basis and energy-reference identity. */
    public record Inventory(double volume,double[] moles,double internalEnergy,SolidInventory solids) {
        public Inventory(double volume,double[] moles,double internalEnergy){this(volume,moles,internalEnergy,SolidInventory.EMPTY);}
        public Inventory {
            Objects.requireNonNull(solids);
            moles=moles.clone();if(!Double.isFinite(volume)||volume<=0||!Double.isFinite(internalEnergy))throw new IllegalArgumentException("Invalid inventory volume/energy");
            double total=0;for(double n:moles){if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Negative/nonfinite inventory");total+=n;}
            if(moles.length==0||!Double.isFinite(total)||(total==0&&solids.empty()&&internalEnergy!=0))throw new IllegalArgumentException("Invalid empty/overflowed inventory");
        }
        @Override public double[] moles(){return moles.clone();}
        @Override public boolean equals(Object other){return other instanceof Inventory value&&Double.doubleToLongBits(volume)==Double.doubleToLongBits(value.volume)&&Double.doubleToLongBits(internalEnergy)==Double.doubleToLongBits(value.internalEnergy)&&Arrays.equals(moles,value.moles)&&solids.equals(value.solids);}
        @Override public int hashCode(){return 31*Objects.hash(volume,internalEnergy,solids)+Arrays.hashCode(moles);}
    }
    /**
     * {@code blockedDirections} is a two-bit mask - 1 forbids flow from {@link #first} to
     * {@link #second}, 2 the reverse - and stays an int because the saved checkpoint format is one
     * and {@link #blocked(double)} is still asked per direction. Every closure the solid transport
     * machinery makes produces 3: a settled bed and a filter at capacity are both properties of the
     * connection rather than of one end of it. A saved checkpoint may still carry 1 or 2, and a
     * caller may still impose one.
     *
     * <p>{@code firstPort} and {@code secondPort} are what the connection draws from its first and second node when
     * the flow leaves that node through it ({@link PhasePort}). Every constructor but the canonical one passes
     * {@link PhasePort#BULK} at both ends, so every graph built without naming a port is the graph it always was.
     */
    public record Pipe(long id,int first,int second,List<PipeResistance.Geometry> sections,FlowControl control,int blockedDirections,InlineFilter filter,
                       PhasePort firstPort,PhasePort secondPort) {
        public Pipe(long id,int first,int second,List<PipeResistance.Geometry> sections,FlowControl control,int blockedDirections,InlineFilter filter){this(id,first,second,sections,control,blockedDirections,filter,PhasePort.BULK,PhasePort.BULK);}
        public Pipe(long id,int first,int second,List<PipeResistance.Geometry> sections,FlowControl control,int blockedDirections){this(id,first,second,sections,control,blockedDirections,null);}
        public Pipe(long id,int first,int second,List<PipeResistance.Geometry> sections,FlowControl control){this(id,first,second,sections,control,0);}
        /**
         * Everything about this connection that decides the shape and the coefficients of the
         * equations it contributes, and nothing that only moves their right-hand side.
         *
         * <p>The captured cake is exactly that right-hand side: each step rebuilds the pipe list with
         * the cake it left, so a retained Newton workspace, sparsity pattern,
         * fill-reducing ordering or warm flow keyed on the whole {@link Pipe} record would miss on
         * every step of any island that has a filter in it - a fresh factorization per step, no
         * preconditioner to fork, and the previous step's flows thrown away. The filter's own
         * settings do belong here, because its clean resistance and capacity are coefficients. So do the two ports: they
         * decide which phase an outflow's donor terms read, and so the coefficients of every row the donor reaches.
         */
        public record Identity(long id,int first,int second,List<PipeResistance.Geometry> sections,FlowControl control,
                               int blockedDirections,FilterSettings filter,PhasePort firstPort,PhasePort secondPort) {}
        /** The immutable half of an {@link InlineFilter}; the cake and its energy are the mutable half. */
        public record FilterSettings(double capacity,double cleanResistance) {}
        public Identity identity() {
            return new Identity(id,first,second,sections,control,blockedDirections,
                    filter==null?null:new FilterSettings(filter.capacity(),filter.cleanResistance()),firstPort,secondPort);
        }
        public Pipe withBlockedDirections(int directions){return new Pipe(id,first,second,sections,control,directions,filter,firstPort,secondPort);}
        public Pipe withFilter(InlineFilter value){return new Pipe(id,first,second,sections,control,blockedDirections,value,firstPort,secondPort);}
        /** This connection with the given ports at its first and second end. */
        public Pipe withPorts(PhasePort atFirst,PhasePort atSecond){return new Pipe(id,first,second,sections,control,blockedDirections,filter,atFirst,atSecond);}
        /** The port of the end at {@code node}, which must be one of this connection's two nodes. */
        public PhasePort portAt(int node) {
            if(node==first)return firstPort;
            if(node==second)return secondPort;
            throw new IllegalArgumentException("Node "+node+" is not an end of pipe "+id);
        }
        /** The port a flow of this sign leaves through: the first end's for a forward (nonnegative) flow, else the second's. */
        public PhasePort drawPort(double flow){return flow>=0?firstPort:secondPort;}
        /** Whether both ends draw their node's whole state. */
        public boolean bulk(){return firstPort==PhasePort.BULK&&secondPort==PhasePort.BULK;}
        public boolean blocked(double flow){return (blockedDirections&(flow>=0?1:2))!=0;}
        public Pipe(long id,int first,int second,PipeResistance.Geometry geometry){this(id,first,second,List.of(geometry),new FlowControl.Passive());}
        public Pipe(long id,int first,int second,PipeResistance.Geometry geometry,FlowControl control){this(id,first,second,List.of(geometry),control);}
        public Pipe {Objects.requireNonNull(firstPort);Objects.requireNonNull(secondPort);if(filter!=null&&!(control instanceof FlowControl.Passive))throw new IllegalArgumentException("Filter must be passive");if(blockedDirections<0||blockedDirections>3)throw new IllegalArgumentException("Invalid pipe closure");sections=coalesce(sections);Objects.requireNonNull(control);if(sections.isEmpty())throw new IllegalArgumentException("Empty hydraulic run");}
        private record SectionShape(double diameter,double roughness) {}
        private static List<PipeResistance.Geometry> coalesce(List<PipeResistance.Geometry> sections) {
            sections=List.copyOf(sections);if(sections.size()<2)return sections;
            // In this homogeneous run every section uses the same upstream density/viscosity.
            // Darcy/Poiseuille loss is linear in length and fitting K, so identical cross-sections
            // can share one friction evaluation without changing the physical run or debug map.
            var sums=new java.util.LinkedHashMap<SectionShape,double[]>();
            for(var section:sections){var total=sums.computeIfAbsent(new SectionShape(section.diameter(),section.roughness()),key->new double[2]);total[0]+=section.length();total[1]+=section.minorLoss();}
            var compact=new java.util.ArrayList<PipeResistance.Geometry>();sums.forEach((shape,total)->compact.add(new PipeResistance.Geometry(total[0],shape.diameter(),shape.roughness(),total[1])));return List.copyOf(compact);
        }
        /** All serial sections carry the same homogeneous bulk flow and use the run's upwind properties. */
        public PipeResistance.Loss loss(double flow,double density,double viscosity) {
            double pressure=0,derivative=0,reynolds=0;
            for(var section:sections){var value=PipeResistance.evaluate(section,flow,density,viscosity);pressure+=value.pressureDrop();derivative+=value.massFlowDerivative();reynolds=Math.max(reynolds,value.reynolds());}
            return new PipeResistance.Loss(pressure,derivative,reynolds);
        }
        /** The run's pressure drop, accumulated in the same order as {@link #loss} and without its
         * records: the residual evaluates this per edge and reads nothing else from the loss. */
        public double pressureDrop(double flow,double density,double viscosity) {
            double pressure=0;
            for(var section:sections)pressure+=PipeResistance.pressureDrop(section,flow,density,viscosity);
            return pressure;
        }
        public double maximumArea(){double area=0;for(var section:sections)area=Math.max(area,section.area());return area;}
        public double minimumDiameter(){double diameter=Double.POSITIVE_INFINITY;for(var section:sections)diameter=Math.min(diameter,section.diameter());return diameter;}
        public double minimumArea(){double area=Double.POSITIVE_INFINITY;for(var section:sections)area=Math.min(area,section.area());return area;}
    }
}
