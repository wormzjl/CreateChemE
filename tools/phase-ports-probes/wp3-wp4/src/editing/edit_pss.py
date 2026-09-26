import re,sys
p='src/main/java/com/wormzjl/createcheme/science/fluid/network/PassiveStepSolver.java'
s=open(p).read()
def rep(old,new,count=1):
    global s
    n=s.count(old)
    if n!=count: sys.exit(f"expected {count} got {n}: {old[:120]}")
    s=s.replace(old,new)
# 1 initial mode
rep("""            case FlowControl.Pump pump->pump.targetVolumeFlow()==0?FlowControl.Mode.CLOSED:
                    graph.pipes().stream().anyMatch(p->p.blockedDirections()!=0)?FlowControl.Mode.PUMP_HEAD_LIMIT:
                    pump.targetVolumeFlow()>pipe.minimumArea()*endVelocityLimit(graph.reservoirs().get(pipe.first()).state(),pipe.firstPort())?FlowControl.Mode.PUMP_HEAD_LIMIT:FlowControl.Mode.PUMP_TARGET;""",
"""            case FlowControl.Mover pump->pump.targetVolumeFlow()==0?FlowControl.Mode.CLOSED:
                    graph.pipes().stream().anyMatch(p->p.blockedDirections()!=0)?FlowControl.Mode.PUMP_HEAD_LIMIT:
                    pump.targetVolumeFlow()>pipe.minimumArea()*endVelocityLimit(graph.reservoirs().get(pipe.first()).state(),pipe.firstPort())?FlowControl.Mode.PUMP_HEAD_LIMIT:
                    suctionCapped(graph,pipe,pump)?FlowControl.Mode.PUMP_HEAD_LIMIT:FlowControl.Mode.PUMP_TARGET;""")
# 2 carry
rep("""            if(!(pipe.control() instanceof FlowControl.Pump pump)||pump.targetVolumeFlow()==0)continue;
            var carried=previousModes.get(i);""","""            if(!(pipe.control() instanceof FlowControl.Mover pump)||pump.targetVolumeFlow()==0)continue;
            var carried=previousModes.get(i);""")
rep("""            double margin=riseLimit(pump,suction)-(endPressure""","""            double margin=riseLimit(pump,suction,a.state().pressure())-(endPressure""")
# 3 dead work
rep("""                if(pipe.control() instanceof FlowControl.Pump pump)work+=dt*Math.max(0,flows[i])/rho*Math.max(0,heads[i])/pump.efficiency();
""","")
rep("boolean changed=false;double work=0;int illegalDirection=-1;","boolean changed=false;int illegalDirection=-1;")
# 4 accepted modes
rep("case FlowControl.Pump ignored->FlowControl.Mode.PUMP_VELOCITY_LIMIT;","case FlowControl.Mover ignored->FlowControl.Mode.PUMP_VELOCITY_LIMIT;")
# 5 nextMode
rep("""        if(pipe.control() instanceof FlowControl.Pump pump) {
            double limit=riseLimit(pump,rho),margin=limit-demand(graph,pipe,states,rho);""","""        if(pipe.control() instanceof FlowControl.Mover pump) {
            double limit=riseLimit(pump,rho,states.get(pipe.first()).pressure()),margin=limit-demand(graph,pipe,states,rho);""")
# 6 checkApproximation
rep("""            if(pipe.control() instanceof FlowControl.Pump pump) {
                // A pump the solve closed""","""            if(pipe.control() instanceof FlowControl.Mover pump&&mode!=FlowControl.Mode.INLET_WRONG_PHASE) {
                // A pump the solve closed""")
rep("""                double limit=riseLimit(pump,endDensity(up,pipe.firstPort()));""","""                double limit=riseLimit(pump,endDensity(up,pipe.firstPort()),up.pressure());""")
# 7 reachable
rep("if(!(pipe.control() instanceof FlowControl.Pump pump)||pump.targetVolumeFlow()>0) {","if(!(pipe.control() instanceof FlowControl.Mover pump)||pump.targetVolumeFlow()>0) {")
# 8 startPoint
rep("flows[i]=suctionMassFlow(((FlowControl.Pump)pipe.control()).targetVolumeFlow(),a.state(),pipe.firstPort());continue;}",
    "flows[i]=suctionMassFlow(((FlowControl.Mover)pipe.control()).targetVolumeFlow(),a.state(),pipe.firstPort());continue;}")
rep("""            if(modes.get(i)==FlowControl.Mode.PUMP_HEAD_LIMIT&&pipe.control() instanceof FlowControl.Pump pump
                    &&(reopened||!(previousAvailable&&previousHeads[i]<=riseLimit(pump,endDensity(a.state(),pipe.firstPort()))))) {
                flows[i]=headLimitMassFlow(graph,pipe,pump);
                heads[i]=riseLimit(pump,endDensity(a.state(),pipe.firstPort()))/1e5;headSet[i]=true;""","""            if(modes.get(i)==FlowControl.Mode.PUMP_HEAD_LIMIT&&pipe.control() instanceof FlowControl.Mover pump
                    &&(reopened||!(previousAvailable&&previousHeads[i]<=riseLimit(pump,endDensity(a.state(),pipe.firstPort()),a.state().pressure())))) {
                flows[i]=headLimitMassFlow(graph,pipe,pump);
                heads[i]=riseLimit(pump,endDensity(a.state(),pipe.firstPort()),a.state().pressure())/1e5;headSet[i]=true;""")
# 9 initialMassFlow
rep("""        if(pipe.control() instanceof FlowControl.Pump pump)
            return Math.min(suctionMassFlow""","""        if(pipe.control() instanceof FlowControl.Mover pump)
            return Math.min(suctionMassFlow""")
# 10 headLimitMassFlow
rep("private double headLimitMassFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe,FlowControl.Pump pump) {","private double headLimitMassFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe,FlowControl.Mover pump) {")
rep("-rho*GRAVITY*(b.elevation()-a.elevation())+riseLimit(pump,rho);","-rho*GRAVITY*(b.elevation()-a.elevation())+riseLimit(pump,rho,a.state().pressure());")
# 11 riseLimit
rep("""    private double riseLimit(FlowControl.Pump pump,double suctionDensity) {
        return pump.maximumAddedPressure()*suctionDensity/model.pumpReferenceDensity();
    }""","""    private double riseLimit(FlowControl.Mover mover,double suctionDensity,double suctionPressure) {
        return mover.riseLimit(suctionDensity,suctionPressure,model.pumpReferenceDensity());
    }""")
# 12 headDensities
rep("+(pipe.control() instanceof FlowControl.Pump?actuator:-actuator);","+(pipe.control() instanceof FlowControl.Mover?actuator:-actuator);")
# 13 energy
rep("""                if(!first&&pipe.control() instanceof FlowControl.Pump pump) {
                    int control=controlOffsets[edge];double head=control<0?0:x[control]*1e5;
                    // Metered on the suction stream's density (its bulk at a BULK end).
                    double power=Math.max(0,flow)/drawn(edge,0,tr[a],flow).density*Math.max(0,head)/pump.efficiency();""","""                if(!first&&pipe.control() instanceof FlowControl.Mover mover) {
                    int control=controlOffsets[edge];double head=control<0?0:x[control]*1e5;
                    // Metered on the suction stream's density (its bulk at a BULK end) and, for a compressor, the suction
                    // node's pressure at this trial (decision D8: suction properties only).
                    double power=mover.power(flow,head,drawn(edge,0,tr[a],flow).density,st[a].pressure());""")
# 14 edgeRows
rep("double signedHead=pipe.control() instanceof FlowControl.Pump?head:-head;","double signedHead=pipe.control() instanceof FlowControl.Mover?head:-head;")
rep("double column=pipe.control() instanceof FlowControl.Pump?suction.density:headDensities[edge];","double column=pipe.control() instanceof FlowControl.Mover?suction.density:headDensities[edge];")
# 15 control rows
rep("case PUMP_TARGET->(flow/suction.density-((FlowControl.Pump)pipe.control()).targetVolumeFlow())/.01;","case PUMP_TARGET->(flow/suction.density-((FlowControl.Mover)pipe.control()).targetVolumeFlow())/.01;")
rep("case PUMP_HEAD_LIMIT->(head-riseLimit((FlowControl.Pump)pipe.control(),suction.density))/pressureScale;","case PUMP_HEAD_LIMIT->(head-riseLimit((FlowControl.Mover)pipe.control(),suction.density,st[a].pressure()))/pressureScale;")
rep("""                case CLOSED->flow;
                case PASSIVE,""","""                // A mover refused for the slice (decision D6) is closed: its flow is exactly zero.
                case CLOSED,INLET_WRONG_PHASE->flow;
                case PASSIVE,""")
# 16 carries
rep("||node==pipe.first()&&pipe.control() instanceof FlowControl.Pump;","||node==pipe.first()&&pipe.control() instanceof FlowControl.Mover;")
# boundaryAllowed package-private
rep("    private static boolean boundaryAllowed(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {","    static boolean boundaryAllowed(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {")
# reopenable open ends
rep("            if(modes.get(e)!=FlowControl.Mode.CLOSED){open[pipe.first()]=true;open[pipe.second()]=true;}",
    "            if(modes.get(e)!=FlowControl.Mode.CLOSED&&modes.get(e)!=FlowControl.Mode.INLET_WRONG_PHASE){open[pipe.first()]=true;open[pipe.second()]=true;}")
open(p,'w').write(s)
print("ok")
