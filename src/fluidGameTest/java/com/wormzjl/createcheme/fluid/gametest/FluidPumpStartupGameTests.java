package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.FlowControl;
import com.wormzjl.createcheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidPumpStartupGameTests {
    private FluidPumpStartupGameTests() {}
    @GameTest(template="empty",timeoutTicks=30000,batch="fluid-water-pump-startup")
    public static void defaultWaterPumpStartsIntoNitrogenReservoir(GameTestHelper helper){verify(helper,false);}
    @GameTest(template="empty",timeoutTicks=30000,batch="fluid-crude-pump-startup")
    public static void heatedCrudePumpStartsIntoNitrogenReservoir(GameTestHelper helper){verify(helper,true);}
    private static void verify(GameTestHelper helper,boolean crude) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var source=helper.absolutePos(new BlockPos(0,1,0));var inlet=source.east();var pump=inlet.east();var outlet=pump.east();var tank=outlet.east();
        level.setBlock(source,ModBlocks.FLUID_GENERATOR.get().defaultBlockState(),3);
        level.setBlock(tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
        level.setBlock(pump,ModBlocks.FLUID_PUMP.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.EAST),3);
        long sourceId=((FluidDeviceBlockEntity)level.getBlockEntity(source)).fluidIdentity(),tankId=((FluidDeviceBlockEntity)level.getBlockEntity(tank)).fluidIdentity();
        if(crude) {
            var feed=world.presets().stream().filter(p->p.id().equals("createcheme:tia_juana_light_methane")).findFirst().orElseThrow();
            // A liquid-only pump (decision D1 of the phase-ports batch) refuses the crude at 350 K and 1 atm, which is 76 %
            // vapour by volume; its bubble point at 350 K is 226 kPa (one flashTP, PHASE_PORTS_REVIEW.md WP3), so the
            // generator stands at 1 MPa and supplies a liquid (re-baselined from 101325 Pa).
            var old=world.registrations().get(sourceId);world.edit(sourceId,old.revision(),old.device(),new FluidDeviceSpec(1,350,1e6,feed.moleFractions()));
            long id=((FluidDeviceBlockEntity)level.getBlockEntity(pump)).fluidIdentity();old=world.registrations().get(id);var d=old.device();
            world.edit(id,old.revision(),new PhysicalFluidTopology.Device(d.id(),d.position(),d.kind(),d.facing(),d.geometry(),new FlowControl.Pump(.0001,500000,1)),old.spec());
        }
        double initialMass=world.view(tankId).state().mass();
        level.setBlock(inlet,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);level.setBlock(outlet,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
        boolean[] done={false};long deadline=System.nanoTime()+30_000_000_000L;
        helper.onEachTick(()->{
            if(done[0])return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            var view=world.view(tankId);
            helper.assertTrue(System.nanoTime()<deadline,"Pump did not start: "+view.status());
            if(view.state()==null||view.state().mass()<initialMass+.001)return;
            helper.assertTrue(!view.status().startsWith("HELD"),"Pump held after transfer: "+view.status());
            helper.assertTrue(Double.isFinite(view.state().pressure())&&view.state().pressure()>0,"Invalid receiving pressure");
            int component=world.components().indexOf(crude?"crude_pc12":"Water");double delivered=0;
            for(var phase:view.state().phaseMoles())delivered+=phase[component];
            helper.assertTrue(delivered>0,"Requested fluid did not reach the reservoir");
            done[0]=true;for(var pos:java.util.List.of(source,inlet,pump,outlet,tank))level.removeBlock(pos,false);helper.succeed();
        });
    }
}
