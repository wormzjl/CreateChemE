// WP2 scratch probe (review WP2 section 4): the squeeze fixture of PhasePortClosureTest with BULK or phase ends, to
// classify the equation-gate rejections seen with a VAPOR vent. Paste the method into PhasePortClosureTest (it uses that
// class's drive(), waterUnderNitrogen(), waterSupply(), nitrogenSink(), line()) and run it with the harness; to run it on
// the WP1 tree (3dbd9b8), replace PassiveStepSolver.portPhaseShare/PHASE_PORT_* with local equivalents (done by a script,
// see README). Measured: BULK vent 0 gate rejections in 12 slices, VAPOR vent 2 (WP2) and 4 (WP1 tree).
    @Test void probeSqueezeVariants() {
        for(var vent:List.of(PhasePort.BULK,PhasePort.VAPOR))for(var inlet:List.of(PhasePort.BULK,PhasePort.LIQUID)) {
            var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.8,101325)),new PassiveNetwork.Reservoir(2,0,waterSupply(300000),PassiveNetwork.NodeKind.GENERATOR),
                    new PassiveNetwork.Reservoir(3,0,nitrogenSink(),PassiveNetwork.NodeKind.VOID));
            var g=new PassiveNetwork(nodes,List.of(
                    new PassiveNetwork.Pipe(31,1,0,List.of(line(10,.025)),new FlowControl.Passive(),0,null,PhasePort.BULK,inlet),
                    new PassiveNetwork.Pipe(32,0,2,List.of(line(10,.025)),new FlowControl.Passive(),0,null,vent,PhasePort.BULK)));
            var slices=drive("probe",g,5,12,31,-1,null);
            int rej=0;var reasons=new TreeMap<String,Integer>();for(var sl:slices){rej+=sl.rejected();sl.reasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));}
            System.out.println("SQUEEZEPROBE vent="+vent+" inlet="+inlet+" rejected="+rej+" reasons="+reasons);
        }
    }
