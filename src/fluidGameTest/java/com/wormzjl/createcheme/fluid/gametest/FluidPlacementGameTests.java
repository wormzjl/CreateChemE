package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.createcheme.world.level.block.FluidDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/**
 * Placement in a large world (FLUID_PLACEMENT_REVIEW.md): WP5 measured one datapack function placing 5,000 devices at
 * 58 to 60 s of server thread, each block compiling the whole registry. Here one call places the same 1,000 rest lines
 * (tank, three pipes, tank) through the blocks' own placement, stacked in two chunk columns: the world queues them and
 * applies them in batches of 1,024 events, each compiling only the lines it builds, and one more pipe beside a line
 * then costs the same as in an empty world.
 */
@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidPlacementGameTests {
    private FluidPlacementGameTests() {}
    private static final int LINES=1_000;
    /** Line n: two per row (x 0 to 4 and 6 to 10), eight rows two blocks apart, levels two blocks apart. */
    private static BlockPos lineStart(BlockPos origin,int n){int level=n/16,row=n%16/2,column=n%2;return origin.offset(6*column,2*level,2*row);}
    private static BlockState device(int index){return (index==0||index==4?ModBlocks.FLUID_RESERVOIR:ModBlocks.FLUID_PIPE).get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.EAST);}

    @GameTest(template="empty",timeoutTicks=2400,batch="fluid-large-world")
    public static void fiveThousandDevicesPlacedAtOnceApplyInBatchesAndOneMoreCostsLittle(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var origin=helper.absolutePos(new BlockPos(0,2,0));var placed=new ArrayList<BlockPos>();
        int[] islandsBefore={-1};double[] placeMillis={0},extraMillis={0};
        helper.startSequence().thenExecute(()->{
            islandsBefore[0]=world.diagnosticSnapshots().size();int devicesBefore=world.registrations().size();
            long started=System.nanoTime();
            for(int n=0;n<LINES;n++){var start=lineStart(origin,n);for(int i=0;i<5;i++){var pos=start.east(i);level.setBlock(pos,device(i),3);placed.add(pos);}}
            // The first read that needs the islands applies what is still queued.
            int islands=world.diagnosticSnapshots().size();placeMillis[0]=(System.nanoTime()-started)/1e6;
            helper.assertTrue(world.registrations().size()==devicesBefore+5*LINES,"Every device is registered");
            helper.assertTrue(islands==islandsBefore[0]+LINES,"Every line is one island: "+(islands-islandsBefore[0]));
            helper.assertTrue(placeMillis[0]<30_000,"5,000 devices placed in "+placeMillis[0]+" ms");
            // One more pipe beside the first line, then broken again: the event touches that line's island only.
            var extra=origin.north();double[] each=new double[11];
            for(int k=0;k<each.length;k++) {
                long t=System.nanoTime();level.setBlock(extra,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);world.diagnosticSnapshots();each[k]=(System.nanoTime()-t)/1e6;
                level.setBlock(extra,Blocks.AIR.defaultBlockState(),3);world.diagnosticSnapshots();
            }
            Arrays.sort(each);extraMillis[0]=each[each.length/2];
            com.wormzjl.createcheme.CreateChemE.LOGGER.info("fluid_placement_gametest devices={} place_ms={} one_more_pipe_median_ms={}",5*LINES,String.format(Locale.ROOT,"%.1f",placeMillis[0]),String.format(Locale.ROOT,"%.3f",extraMillis[0]));
            helper.assertTrue(extraMillis[0]<10,"One more pipe in a 5,000-device world costs "+extraMillis[0]+" ms");
        }).thenExecute(()->{for(var pos:placed)level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);})
        .thenWaitUntil(()->helper.assertTrue(world.capture().world().events().isEmpty()&&world.diagnosticSnapshots().size()==islandsBefore[0],"Waiting for the lines to be removed"))
        .thenSucceed();
    }
}
