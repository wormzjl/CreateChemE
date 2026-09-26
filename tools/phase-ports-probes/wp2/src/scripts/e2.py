import json
E=[]
E.append(["""        final boolean[] drawsVapor,drawsLiquid;
        final Transport[][] capSources;""","""        final boolean[] drawsVapor,drawsLiquid;
        /** This solve's start-state availability mask ({@link PassiveStepSolver#closedPorts}); constant for the solve. */
        final byte[] closedPorts;
        /**
         * The outflow throttle of each open phase port, per connection and direction ({@code [edge][0]}: flow leaving the
         * first end, {@code [edge][1]}: leaving the second), {@code +infinity} where there is none; {@code null} when the
         * graph has none at all. For an end whose port is open on the accepted state the step starts from:
         * <pre>throttle = (phi_start - phi_reserve) * V * rho_stream,start / (n * dt)</pre>
         * with {@code phi_start} the port's phase share of the vessel ({@link #portPhaseShare}), {@code V} the vessel's
         * volume, {@code rho_stream,start} the drawn stream's density ({@link #endDensity}), all on the start state,
         * {@code phi_reserve} = {@link #PHASE_PORT_RESERVE}, {@code n} the number of this vessel's ends with the same port
         * whose connection may carry outflow from it ({@link #boundaryAllowed} with the mask; the true constraint couples
         * them, so they share it equally), and {@code dt} this solve's step - the backward-Euler step the interval solver
         * attempts, the interval's remainder when that is shorter, halved with every retry - never the slice. Over the step
         * the ports of one phase then draw at most {@code (phi_start - phi_reserve) V rho_start} together, the phase above
         * the reserve at the start density, so a draining port lands its phase at the reserve on a step boundary instead of
         * past it (plan 3.4). It enters {@code edgeRows} beside the velocity cap as {@code min(cap, throttle)} for the
         * direction leaving the port, exactly as the filter's room limit does: a constant for the solve, a saturated law,
         * absent in a rate solve (which carries no step) and while a device of the island prescribes its own flow
         * ({@link #prescribedFlow}, as for the filter; WP3 revisits the pump fed from a port).
         */
        final double[][] throttles;
        final Transport[][] capSources;"""])
E.append(["""        Equations(PassiveNetwork graph,double dt,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> seeds,boolean[][] reachable,
                  PhaseSupport[][] supports) {
            this.modes=List.copyOf(modes);
            prescribedFlow=this.modes.contains(FlowControl.Mode.PUMP_TARGET);""","""        Equations(PassiveNetwork graph,double dt,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> seeds,boolean[][] reachable,
                  PhaseSupport[][] supports,byte[] closedPorts) {
            this.modes=List.copyOf(modes);
            prescribedFlow=this.modes.contains(FlowControl.Mode.PUMP_TARGET);
            this.closedPorts=closedPorts;
            throttles=rateOnly||prescribedFlow?null:throttles(graph,dt,closedPorts);"""])
E.append(["""            startPoint(graph,modes,boundaryClosed,pipeIdentities,seeds,q,head,headSet);
            for(int i=0;i<edges;i++) {""","""            startPoint(graph,modes,boundaryClosed,pipeIdentities,seeds,q,head,headSet,closedPorts);
            for(int i=0;i<edges;i++) {"""])
E.append(["""        private void buildSparsity() {""","""        /** {@link #throttles} of {@code graph} (its states are the step's start states), or null when it has no open phase
         * port. */
        private double[][] throttles(PassiveNetwork graph,double dt,byte[] closedPorts) {
            int edges=graph.pipes().size(),nodes=graph.reservoirs().size();
            // n: per node, the ends of each phase whose connection may carry outflow from that node through an open port.
            int[] vapor=new int[nodes],liquid=new int[nodes];boolean any=false;
            for(var pipe:graph.pipes())for(int end=0;end<2;end++) {
                var port=end==0?pipe.firstPort():pipe.secondPort();if(port==PassiveNetwork.PhasePort.BULK)continue;
                if(!boundaryAllowed(graph,pipe,end==0?1:-1,closedPorts))continue;
                int node=end==0?pipe.first():pipe.second();any=true;
                if(port==PassiveNetwork.PhasePort.VAPOR)vapor[node]++;else liquid[node]++;
            }
            if(!any)return null;
            double[][] limits=new double[edges][2];
            for(int edge=0;edge<edges;edge++) {
                var pipe=graph.pipes().get(edge);
                for(int end=0;end<2;end++) {
                    var port=end==0?pipe.firstPort():pipe.secondPort();int node=end==0?pipe.first():pipe.second();
                    if(port==PassiveNetwork.PhasePort.BULK||!boundaryAllowed(graph,pipe,end==0?1:-1,closedPorts)){limits[edge][end]=Double.POSITIVE_INFINITY;continue;}
                    var start=graph.reservoirs().get(node).state();int n=port==PassiveNetwork.PhasePort.VAPOR?vapor[node]:liquid[node];
                    limits[edge][end]=(portPhaseShare(start,port)-PHASE_PORT_RESERVE)*start.volume()*endDensity(start,port)/(n*dt);
                }
            }
            return limits;
        }
        private void buildSparsity() {"""])
E.append(["""                        if(retainedPerMass>0&&room>0)limit=Math.min(limit,room/(dt*retainedPerMass));
                    }
""","""                        if(retainedPerMass>0&&room>0)limit=Math.min(limit,room/(dt*retainedPerMass));
                    }
                    // An open phase port's outflow throttle, for the direction leaving it: the same kind of second limit, a
                    // constant for the solve, so the port lands its phase at the reserve on a step boundary; see
                    // {@link #throttles}. The direction's donor end is the port's end by construction of the index.
                    if(throttles!=null)limit=Math.min(limit,throttles[edge][direction]);
"""])
json.dump(E,open("/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad/wp2/e2.json","w"))
