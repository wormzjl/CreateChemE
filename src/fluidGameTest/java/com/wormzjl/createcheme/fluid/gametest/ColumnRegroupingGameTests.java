package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.science.material.MaterialRuntime;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createcheme_column_test")
@PrefixGameTestTemplate(false)
public final class ColumnRegroupingGameTests {
    private ColumnRegroupingGameTests() {}
    @GameTest(template="empty",timeoutTicks=10000000,batch="column-native-service")
    public static void allCrudePresetsCalculateThroughTheMinecraftAdmissionAndCompletionPath(GameTestHelper helper) {
        var level=helper.getLevel();var server=level.getServer();var pos=helper.absolutePos(new BlockPos(0,1,0));
        level.setBlock(pos,ModBlocks.COLUMN_CALCULATOR_V3.get().defaultBlockState(),3);
        var block=(ColumnCalculatorV3BlockEntity)level.getBlockEntity(pos);
        var presets=java.util.Arrays.stream(ColumnInputPreset.values()).filter(p->p!=ColumnInputPreset.HOLLAND).toList();
        int[] next={0};boolean[] waiting={false},done={false};long deadline=System.nanoTime()+180_000_000_000L;
        helper.onEachTick(()->{
            if(done[0])return;
            helper.assertTrue(System.nanoTime()<deadline,"Column service qualification exceeded its wall deadline");
            // The GameTest server ticks unpaced; yield only this isolated harness while real workers run.
            if(waiting[0])java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            var state=block.state(0);
            if(waiting[0]) {
                if(state.status()==ColumnCalculatorV3BlockEntity.V3Status.CALCULATING)return;
                helper.assertTrue(state.status()==ColumnCalculatorV3BlockEntity.V3Status.SUCCESS,
                        "Native service failed "+presets.get(next[0]-1).id()+": "+state.diagnostics());
                helper.assertTrue(state.displayResult().isPresent(),"Accepted result was not published to the block");
                waiting[0]=false;
            }
            if(next[0]==presets.size()) {
                done[0]=true;level.removeBlock(pos,false);helper.succeed();return;
            }
            var input=presets.get(next[0]++).input(MaterialRuntime.current());
            long id=com.wormzjl.createcheme.runtime.ProcessSolveServices.nextRequestId();
            var operation=block.tryBegin(state.inputRevision(),id,input).orElseThrow();
            var target=new com.wormzjl.createcheme.runtime.ProcessSolveServices.ColumnTarget(level.dimension(),pos);
            var request=new com.wormzjl.createcheme.runtime.ProcessSolveServices.V3ColumnRequest(id,target,operation,System.nanoTime());
            var admission=com.wormzjl.createcheme.runtime.ProcessSolveServices.submitV3Column(server,request);
            helper.assertTrue(admission.accepted(),"Column admission failed: "+admission.admission());
            waiting[0]=true;
        });
    }
    @GameTest(template="empty",timeoutTicks=100,batch="column-regrouping")
    public static void obsoleteColumnInputSurvivesSavingUntilExplicitPresetReplacement(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(0,1,0));
        level.setBlock(pos,ModBlocks.COLUMN_CALCULATOR_V3.get().defaultBlockState(),3);
        var block=(ColumnCalculatorV3BlockEntity)level.getBlockEntity(pos);
        var old=new CompoundTag();old.putInt("V3DataVersion",8);
        var oldInput=new CompoundTag();oldInput.putDouble("retired_component_amount",42);
        old.put("Input",oldInput);block.loadWithComponents(old,level.registryAccess());
        helper.assertTrue(block.state(0).status()==ColumnCalculatorV3BlockEntity.V3Status.INCOMPATIBLE,"Old input was silently interpreted");
        var saved=block.saveWithFullMetadata(level.registryAccess());
        helper.assertTrue(saved.getInt("V3DataVersion")==8&&saved.getCompound("Input").equals(oldInput),"Saving silently erased unsupported input");
        var preset=ColumnInputPreset.TIA_JUANA.input(MaterialRuntime.current());
        helper.assertTrue(block.tryBegin(0,1,preset).isEmpty(),"Calculation implicitly replaced incompatible input");
        helper.assertTrue(block.tryLoadPreset(0,preset,"Explicit replacement"),"Explicit preset replacement was refused");
        var current=block.saveWithFullMetadata(level.registryAccess());
        helper.assertTrue(current.getInt("V3DataVersion")==ColumnCalculatorV3BlockEntity.DATA_VERSION,"New input was not saved with current contract");
        helper.assertTrue(block.state(0).input().componentBasis().componentIds().equals(preset.componentBasis().componentIds()),"Current basis was changed");
        level.removeBlock(pos,false);helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100,batch="column-regrouping")
    public static void currentVersionUnsupportedAxisAndCorruptInputAreRetained(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(0,1,0));
        level.setBlock(pos,ModBlocks.COLUMN_CALCULATOR_V3.get().defaultBlockState(),3);
        var block=(ColumnCalculatorV3BlockEntity)level.getBlockEntity(pos);
        var original=block.saveWithFullMetadata(level.registryAccess());
        for(String failure:java.util.List.of("axis","package","missing")) {
            var bad=original.copy();var input=bad.getCompound("Input");
            if(failure.equals("axis"))input.getList("Axis",net.minecraft.nbt.Tag.TAG_STRING).set(0,net.minecraft.nbt.StringTag.valueOf("Retired"));
            else if(failure.equals("package"))input.putString("Package","retired:package");
            else bad.remove("Input");
            block.loadWithComponents(bad,level.registryAccess());
            helper.assertTrue(block.state(0).status()==ColumnCalculatorV3BlockEntity.V3Status.INCOMPATIBLE,"Unsupported "+failure+" was silently reset");
            var saved=block.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(saved.contains("Input")==bad.contains("Input")&&saved.getCompound("Input").equals(bad.getCompound("Input")),"Saving erased unsupported "+failure);
            helper.assertTrue(block.tryLoadPreset(0,ColumnInputPreset.TIA_JUANA.input(MaterialRuntime.current()),"Explicit reset"),"Explicit reset failed");
        }
        level.removeBlock(pos,false);helper.succeed();
    }

}
