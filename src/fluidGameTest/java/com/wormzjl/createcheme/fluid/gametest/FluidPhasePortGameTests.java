package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.network.FluidNetwork;
import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/**
 * The three devices of the phase-ports batch placed as blocks (plan section 6, phase-ports WP5): a tank's top, side and
 * bottom outlets each carrying their phase to a void; a liquid-only pump on a gas tank refused with its reason on its
 * status line; and a gas compressor moving nitrogen between two tanks on its target, then to its pressure-ratio limit, then
 * closed. Every observation is the device's view as the engine publishes it ({@link FluidWorldAuthority#view}).
 */
@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidPhasePortGameTests {
    private FluidPhasePortGameTests() {}
    private static long identity(GameTestHelper helper,BlockPos pos){return ((FluidDeviceBlockEntity)helper.getLevel().getBlockEntity(pos)).fluidIdentity();}
    private static void place(GameTestHelper helper,BlockPos pos,BlockState state){helper.getLevel().setBlock(pos,state,3);}
    private static void clear(GameTestHelper helper,Collection<BlockPos> positions){for(var pos:positions)helper.getLevel().removeBlock(pos,false);}

    /**
     * Plan section 6, scenario 1. A tank is filled to about a fifth with water from a 130 kPa generator on its west side;
     * then the generator goes and a pipe and a void at 1 atm go on its top, its east side and its bottom. On the first
     * interval the new outlets carry: the top outlet (UP face, VAPOR port) draws gas and carries no liquid, the bottom
     * outlet (DOWN face, LIQUID port) draws water and carries no gas, the side outlet (BULK) draws all phases together,
     * and the tank page's lines (its view's outlets, as the menu snapshot carries them) say so, the bottom one with the
     * level head. The vent pressure stays below the humid-vent floor of decision D14.
     */
    @GameTest(template="empty",timeoutTicks=30000,batch="fluid-phase-port-outlets")
    public static void aTanksTopSideAndBottomOutletsEachCarryTheirPhase(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var tank=helper.absolutePos(new BlockPos(2,3,0));var feedPipe=tank.west();var generator=feedPipe.west();
        var top=tank.above();var topVoid=top.above();var side=tank.east();var sideVoid=side.east();var bottom=tank.below();var bottomVoid=bottom.below();
        var all=List.of(tank,feedPipe,generator,top,topVoid,side,sideVoid,bottom,bottomVoid);
        place(helper,tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());place(helper,generator,ModBlocks.FLUID_GENERATOR.get().defaultBlockState());
        long tankId=identity(helper,tank),generatorId=identity(helper,generator);
        var old=world.registrations().get(generatorId);world.edit(generatorId,old.revision(),old.device(),new FluidDeviceSpec(1,298.15,130000,old.spec().composition()));
        place(helper,feedPipe,ModBlocks.FLUID_PIPE.get().defaultBlockState());
        int[] stage={0};long deadline=System.nanoTime()+90_000_000_000L;long[] ids=new long[3];
        helper.onEachTick(()->{
            if(stage[0]==2)return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            var view=world.view(tankId);
            helper.assertTrue(System.nanoTime()<deadline,"Stage "+stage[0]+" did not complete: "+view.status()+" "+view.outlets());
            helper.assertTrue(!view.status().startsWith("HELD"),"Held: "+view.status());
            if(stage[0]==0) {
                if(view.state()==null||view.state().mass()<150)return;
                clear(helper,List.of(generator,feedPipe));
                for(var pos:List.of(top,side,bottom))place(helper,pos,ModBlocks.FLUID_PIPE.get().defaultBlockState());
                for(var pos:List.of(topVoid,sideVoid,bottomVoid))place(helper,pos,ModBlocks.FLUID_VOID.get().defaultBlockState());
                ids[0]=identity(helper,top);ids[1]=identity(helper,side);ids[2]=identity(helper,bottom);stage[0]=1;return;
            }
            var outlets=view.outlets();
            if(outlets.size()!=3||outlets.stream().anyMatch(o->!(o.massFlow()>0)))return;
            var byPort=new EnumMap<PassiveNetwork.PhasePort,FluidView.Outlet>(PassiveNetwork.PhasePort.class);for(var o:outlets)byPort.put(o.port(),o);
            helper.assertTrue(byPort.size()==3,"One top, one side and one bottom outlet: "+outlets);
            helper.assertTrue(byPort.get(PassiveNetwork.PhasePort.VAPOR).drawn()==2,"The top outlet draws gas: "+outlets);
            helper.assertTrue(byPort.get(PassiveNetwork.PhasePort.LIQUID).drawn()==1,"The bottom outlet draws water: "+outlets);
            helper.assertTrue(byPort.get(PassiveNetwork.PhasePort.LIQUID).head()>0,"The bottom outlet shows its level head: "+outlets);
            // What each pipe carried over the interval, as its own inspection page shows it.
            var topPhases=world.view(ids[0]).pipeInfo().contents().phaseMoles();var bottomPhases=world.view(ids[2]).pipeInfo().contents().phaseMoles();var sidePhases=world.view(ids[1]).pipeInfo().contents().phaseMoles();
            helper.assertTrue(sum(topPhases[0])+sum(topPhases[1])==0&&sum(topPhases[2])>0,"The top pipe carries gas only");
            helper.assertTrue(sum(bottomPhases[2])==0&&sum(bottomPhases[1])>0,"The bottom pipe carries water only");
            helper.assertTrue(sum(sidePhases[1])>0&&sum(sidePhases[2])>0,"The side pipe carries both phases together");
            // The tank page is built from the same view: the menu snapshot validates and carries the three lines.
            var page=FluidNetwork.snapshot(world,world.registrations().get(tankId),view);
            helper.assertTrue(page.view().outlets().equals(outlets),"The tank page carries its outlet lines");
            System.out.println("PHASE_PORT_GAMETEST outlets tick="+view.onlineTick()+" committed="+view.committedTick()+" tankP="+view.state().pressure()+" tankMass="+view.state().mass()+" "+outlets);
            stage[0]=2;clear(helper,all);helper.succeed();
        });
    }
    private static double sum(double[] values){double s=0;for(double v:values)s+=v;return s;}

    /**
     * Plan section 6, scenario 2 (its first half). A liquid-only pump (decision D1) on the east side of a nitrogen tank,
     * facing east into a pipe and a void: its supply is 100 % vapour, so it is refused (INLET_WRONG_PHASE, decision D6),
     * carries nothing, and its status line gives the reason with the tank named as a device, in the words the menu shows.
     */
    @GameTest(template="empty",timeoutTicks=30000,batch="fluid-pump-refused")
    public static void aPumpOnAGasTankIsRefusedWithItsReasonOnItsStatusLine(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var tank=helper.absolutePos(new BlockPos(0,1,0));var pump=tank.east();var pipe=pump.east();var sink=pipe.east();var all=List.of(tank,pump,pipe,sink);
        place(helper,tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());
        place(helper,pump,ModBlocks.FLUID_PUMP.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.EAST));
        place(helper,pipe,ModBlocks.FLUID_PIPE.get().defaultBlockState());place(helper,sink,ModBlocks.FLUID_VOID.get().defaultBlockState());
        long pumpId=identity(helper,pump);var p=world.registrations().get(identity(helper,tank)).device().position();
        String expected="ERROR: pump inlet not liquid (vapour 100.0 % by volume, from reservoir at "+p.x()+", "+p.y()+", "+p.z()+")";
        boolean[] done={false};long deadline=System.nanoTime()+60_000_000_000L;
        helper.onEachTick(()->{
            if(done[0])return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            var view=world.view(pumpId);
            helper.assertTrue(System.nanoTime()<deadline,"The pump was not refused: "+view.status());
            if(!view.status().contains("INLET_WRONG_PHASE"))return;
            helper.assertTrue(view.status().contains(expected),"The reason names the tank: "+view.status());
            helper.assertTrue(view.massFlow()==0,"A refused pump carries nothing: "+view.massFlow());
            helper.assertTrue(!view.status().contains("limit"),"No limit is shown for a refused pump: "+view.status());
            var page=FluidNetwork.snapshot(world,world.registrations().get(pumpId),view);
            helper.assertTrue(page.view().status().contains(expected),"The pump's page shows the reason");
            System.out.println("PHASE_PORT_GAMETEST pump tick="+view.onlineTick()+" committed="+view.committedTick()+" status="+view.status());
            done[0]=true;clear(helper,all);helper.succeed();
        });
    }

    /**
     * Plan section 6, scenario 3 (its gas half). Two nitrogen tanks at 1 atm with a compressor at its placement default
     * (0.05 m3/s, ratio 3, plan Appendix B) facing from the first into the second: it runs on its suction-flow target
     * (PUMP_TARGET), the discharge rises until it stands at three times the suction (its ratio limit), and it closes there
     * (CLOSED, the mover's shutoff), the second tank then holding three times the first tank's pressure.
     */
    @GameTest(template="empty",timeoutTicks=40000,batch="fluid-compressor-transfer")
    public static void aCompressorMovesNitrogenToItsRatioLimitAndCloses(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var suction=helper.absolutePos(new BlockPos(0,1,0));var inlet=suction.east();var compressor=inlet.east();var outlet=compressor.east();var discharge=outlet.east();
        var all=List.of(suction,inlet,compressor,outlet,discharge);
        place(helper,suction,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());place(helper,discharge,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());
        place(helper,compressor,ModBlocks.FLUID_COMPRESSOR.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.EAST));
        long suctionId=identity(helper,suction),dischargeId=identity(helper,discharge),compressorId=identity(helper,compressor);
        var control=world.registrations().get(compressorId).device().control();
        helper.assertTrue(control.equals(new com.wormzjl.createcheme.science.fluid.network.FlowControl.Compressor(.05,3,1)),"Placement default: "+control);
        place(helper,inlet,ModBlocks.FLUID_PIPE.get().defaultBlockState());place(helper,outlet,ModBlocks.FLUID_PIPE.get().defaultBlockState());
        var seen=new LinkedHashSet<String>();boolean[] done={false};long deadline=System.nanoTime()+120_000_000_000L;
        helper.onEachTick(()->{
            if(done[0])return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            var view=world.view(compressorId);
            helper.assertTrue(System.nanoTime()<deadline,"The compressor did not close: "+view.status()+", seen "+seen);
            helper.assertTrue(!view.status().startsWith("HELD"),"Held: "+view.status());
            for(var mode:List.of("PUMP_TARGET","PUMP_HEAD_LIMIT","PUMP_VELOCITY_LIMIT","CLOSED","INLET_WRONG_PHASE"))if(view.status().contains(" / "+mode))seen.add(mode);
            helper.assertTrue(!seen.contains("INLET_WRONG_PHASE"),"A compressor on nitrogen is not refused: "+view.status());
            if(!view.status().contains(" / CLOSED")||!seen.contains("PUMP_TARGET"))return;
            var a=world.view(suctionId).state();var b=world.view(dischargeId).state();
            helper.assertTrue(view.status().contains("limit")&&view.status().contains("at this suction"),"The compressor shows its limit: "+view.status());
            helper.assertTrue(Math.abs(b.pressure()/a.pressure()-3)<3e-3,"Closed at its ratio limit: "+b.pressure()+" / "+a.pressure()+" = "+b.pressure()/a.pressure());
            helper.assertTrue(b.temperature()>a.temperature(),"The discharge is heated by the compression work");
            System.out.println("PHASE_PORT_GAMETEST compressor tick="+view.onlineTick()+" committed="+view.committedTick()+" seen="+seen+" suction="+a.pressure()+" Pa "+a.temperature()+" K discharge="+b.pressure()+" Pa "
                    +b.temperature()+" K ratio="+b.pressure()/a.pressure()+" status="+view.status());
            done[0]=true;clear(helper,all);helper.succeed();
        });
    }
}
