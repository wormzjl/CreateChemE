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

@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ColumnRegroupingGameTests {
    private ColumnRegroupingGameTests() {}
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
}
