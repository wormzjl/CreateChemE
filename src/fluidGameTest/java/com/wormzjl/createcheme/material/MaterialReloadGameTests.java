package com.wormzjl.createcheme.material;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.material.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.*;
import java.util.*;

/** Runs only in the dedicated, disposable material GameTest server; exercises the actual /reload path. */
@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public class MaterialReloadGameTests {
    @GameTest(template="empty",timeoutTicks=100,batch="column-presets")
    public static void columnPresetsLoadWithRevisionChecks(GameTestHelper helper) {
        var pos=helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        helper.getLevel().setBlockAndUpdate(pos,com.wormzjl.createcheme.registry.ModBlocks.COLUMN_CALCULATOR_V3.get().defaultBlockState());
        var calculator=(com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity)helper.getLevel().getBlockEntity(pos);
        for (var choice:com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset.values()) {
            var before=calculator.state(0);
            var input=choice.input(MaterialRuntime.active());
            if (!calculator.tryLoadPreset(before.inputRevision(),input,"Loaded "+choice.label())) {
                helper.fail("Preset failed to load: "+choice.id());return;
            }
            var after=calculator.state(0);
            if (!after.input().equals(input) || after.displayResult().isPresent()
                    || after.inputRevision()!=before.inputRevision()+1
                    || calculator.tryLoadPreset(before.inputRevision(),input,"Stale")) {
                helper.fail("Preset state/revision contract failed: "+choice.id());return;
            }
        }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=2400,batch="material-resource-reload")
    public static void materialReloadIsAtomic(GameTestHelper helper) throws Exception {
        var server=helper.getLevel().getServer();
        var original=MaterialRuntime.active();
        for (String crude : List.of("wti_light_export", "upper_zakum", "bonga", "dalia", "cold_lake_blend")) {
            var feedPackage=original.requirePackage("createcheme:"+crude+"_tjl20");
            var assay=feedPackage.assays().get("createcheme:"+crude);
            if (!feedPackage.components().equals(original.requirePackage("createcheme:tjl20_methane").components()) || assay==null || !assay.basis().equals("mass")) {
                helper.fail("Bundled regrouped crude assay was not loaded: "+crude);return;
            }
        }
        String packageId="createcheme:tjl20_methane";
        String resource="data/createcheme/materials/properties/tjl20_methane.json";
        var root=server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("material-reload-test");
        var file=root.resolve(resource);Files.createDirectories(file.getParent());
        Files.writeString(root.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":48,\"description\":\"Material reload integration test\"}}");
        var json=JsonParser.parseString(original.resources().get(resource)).getAsJsonObject();
        json.addProperty("molecular_weight_kg_per_mol",.017);
        Files.writeString(file,json.toString());
        var selected=new ArrayList<>(server.getPackRepository().getSelectedIds());
        server.getPackRepository().reload();
        var withOverride=new ArrayList<>(selected);withOverride.add("file/material-reload-test");
        server.reloadResources(withOverride).whenComplete((ignored,error)->server.execute(()-> {
            if(error!=null){helper.fail("Valid material override reload failed: "+error);return;}
            var changed=MaterialRuntime.active();
            if(changed.requirePackage(packageId).fingerprint().equals(original.requirePackage(packageId).fingerprint())
                    || changed.requirePackage(packageId).properties().getFirst().molecularWeight()!=.017) {
                helper.fail("Higher priority pack was not published");return;
            }
            try {json.addProperty("molecular_weight_kg_per_mol",-1);Files.writeString(file,json.toString());}
            catch(Exception e){helper.fail(e.toString());return;}
            server.reloadResources(withOverride).whenComplete((unused,invalid)->server.execute(()-> {
                if(invalid==null || MaterialRuntime.active()!=changed){helper.fail("Invalid reload replaced the valid snapshot");return;}
                server.reloadResources(selected).whenComplete((done,restore)->server.execute(()-> {
                    if(restore!=null || !MaterialRuntime.active().requirePackage(packageId).fingerprint().equals(original.requirePackage(packageId).fingerprint()))
                        helper.fail("Restoring the original data pack selection failed");
                    else helper.succeed();
                }));
            }));
        }));
    }
}
