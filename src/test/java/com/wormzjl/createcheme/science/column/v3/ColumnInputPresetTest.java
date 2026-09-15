package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.material.MaterialRuntime;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset;
import java.util.Arrays;
import java.util.HashMap;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnInputPresetTest {
    @Test void everySelectablePresetIsValidAndSurvivesSaving() throws Exception {
        var catalog=MaterialCatalog.bundled();
        var write=ColumnCalculatorV3BlockEntity.class.getDeclaredMethod("writeInput",V3ColumnInput.class);
        var read=ColumnCalculatorV3BlockEntity.class.getDeclaredMethod("readInput",CompoundTag.class);
        write.setAccessible(true);read.setAccessible(true);
        for (var choice : ColumnInputPreset.values()) {
            var input=choice.input(catalog);
            assertTrue(choice.matches(input));
            V3ColumnProblemResolver.validateInput(input);
            assertEquals(input,read.invoke(null,write.invoke(null,input)));
            if (choice==ColumnInputPreset.HOLLAND) continue;
            var thermo=V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            double[] expected=thermo.crudeFeed(input.assayId()).moleFractions();
            double total=Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
            for (int i=0;i<expected.length;i++)
                assertEquals(expected[i],input.feedComponentMolarFlowsMolPerSecond()[i]/total,1e-14);
        }
        assertEquals(ColumnCalculatorV3BlockEntity.methaneCduInput(),ColumnInputPreset.TIA_JUANA.input(catalog));
        assertEquals(ColumnCalculatorV3BlockEntity.pilotPresetInput(),ColumnInputPreset.PILOT.input(catalog));
        assertThrows(IllegalArgumentException.class,()->ColumnInputPreset.fromId("arbitrary:package"));
    }

    @Test void newCrudesKeepEditableCurrentColumnConditionsAndDistinctFeeds() {
        var catalog=MaterialCatalog.bundled();
        var base=ColumnInputPreset.TIA_JUANA.input(catalog);
        for (var choice : new ColumnInputPreset[]{ColumnInputPreset.WTI,ColumnInputPreset.UPPER_ZAKUM,
                ColumnInputPreset.BONGA,ColumnInputPreset.DALIA,ColumnInputPreset.COLD_LAKE}) {
            var input=choice.input(catalog);
            assertEquals(base.componentBasis(),input.componentBasis());
            assertEquals(base.stageCount(),input.stageCount());
            assertEquals(base.feedStageNumber(),input.feedStageNumber());
            assertEquals(base.feedTemperatureKelvin(),input.feedTemperatureKelvin());
            assertEquals(base.topPressurePascal(),input.topPressurePascal());
            assertEquals(base.specifications(),input.specifications());
            assertEquals(base.sideDraws(),input.sideDraws());
            assertEquals(base.steamFeeds(),input.steamFeeds());
            assertEquals(base.pumparounds(),input.pumparounds());
            assertEquals(Arrays.stream(base.feedComponentMolarFlowsMolPerSecond()).sum(),
                    Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum(),1e-10);
            assertFalse(Arrays.equals(base.feedComponentMolarFlowsMolPerSecond(),input.feedComponentMolarFlowsMolPerSecond()));
        }
    }

    @Test void loadingPresetUsesTheSuppliedServerCatalogIncludingOverrides() {
        var original=MaterialCatalog.bundled();
        var resources=new HashMap<>(original.resources());
        String path="data/createcheme/materials/assays/bonga_tjl20.json";
        var assay=JsonParser.parseString(resources.get(path)).getAsJsonObject();
        assay.getAsJsonArray("amounts").set(7,new com.google.gson.JsonPrimitive(.8));
        resources.put(path,assay.toString());
        var edited=MaterialCatalog.parse(resources);
        var before=ColumnInputPreset.BONGA.input(original);
        var after=ColumnInputPreset.BONGA.input(edited);
        assertFalse(Arrays.equals(before.feedComponentMolarFlowsMolPerSecond(),after.feedComponentMolarFlowsMolPerSecond()));
        assertEquals(before,ColumnInputPreset.BONGA.input(original));
        assertSame(original,MaterialRuntime.current());
    }
}
