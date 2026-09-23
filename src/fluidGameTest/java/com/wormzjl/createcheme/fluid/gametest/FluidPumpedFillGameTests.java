package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.createcheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/**
 * {@code fill100}'s line placed block by block with the mod's placement defaults: a water generator, a pump facing east
 * and a closed chain of three nitrogen tanks. In game on 0.3.0 such a line was held for minutes (a third of 100 never
 * recovered); here it must start through the real coordinator, its wall budget and its hold policy, and fill.
 */
@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidPumpedFillGameTests {
    private FluidPumpedFillGameTests() {}
    @GameTest(template="empty",timeoutTicks=40000,batch="fluid-pumped-fill")
    public static void aPlacedPumpedThreeTankChainFills(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var origin=helper.absolutePos(new BlockPos(0,1,0));var positions=new java.util.ArrayList<BlockPos>();
        String layout="GUPRPRPR";
        for(int i=0;i<layout.length();i++) {
            var pos=origin.east(i);positions.add(pos);
            var state=switch(layout.charAt(i)) {
                case 'G'->ModBlocks.FLUID_GENERATOR.get().defaultBlockState();
                case 'U'->ModBlocks.FLUID_PUMP.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.EAST);
                case 'P'->ModBlocks.FLUID_PIPE.get().defaultBlockState();
                default->ModBlocks.FLUID_RESERVOIR.get().defaultBlockState();
            };
            level.setBlock(pos,state,3);
        }
        long firstTank=((FluidDeviceBlockEntity)level.getBlockEntity(positions.get(3))).fluidIdentity();
        long lastTank=((FluidDeviceBlockEntity)level.getBlockEntity(positions.get(7))).fluidIdentity();
        boolean[] done={false};int[] heldTicks={0};long deadline=System.nanoTime()+90_000_000_000L;
        helper.onEachTick(()->{
            if(done[0])return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            var first=world.view(firstTank);var last=world.view(lastTank);
            if(first.status().startsWith("HELD"))heldTicks[0]++;
            helper.assertTrue(System.nanoTime()<deadline,"The pumped chain did not fill within 90 s of wall time: "+first.status()+", held for "+heldTicks[0]+" ticks");
            if(first.state()==null||last.state()==null)return;
            // A tenth of the first tank in water: about 25 s of pumping at the pump's 0.01 m3/s.
            if(first.state().mass()<100)return;
            helper.assertTrue(!first.status().startsWith("HELD"),"Held while filling: "+first.status());
            helper.assertTrue(first.state().pressure()>101325&&last.state().pressure()>101325,"The chain is compressed as it fills");
            done[0]=true;for(var pos:positions)level.removeBlock(pos,false);helper.succeed();
        });
    }
}
