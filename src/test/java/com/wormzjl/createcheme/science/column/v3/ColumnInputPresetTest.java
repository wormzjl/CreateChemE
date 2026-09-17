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
            var rates=catalog.columnSideDrawRates(input.packageId());
            if(!rates.isEmpty())assertEquals(rates,input.sideDraws().stream().map(V3SideDrawSpec::molarFlowMolPerSecond).toList());
            assertEquals(base.steamFeeds(),input.steamFeeds());
            assertEquals(base.pumparounds(),input.pumparounds());
            double volume=0;var properties=catalog.requirePackage(input.packageId()).properties();
            var amounts=input.feedComponentMolarFlowsMolPerSecond();
            for(int i=0;i<amounts.length;i++)volume+=amounts[i]*properties.get(i).molecularWeight()/properties.get(i).density();
            assertEquals(catalog.columnFeedStandardVolume(input.packageId()),volume,1e-14);
            assertFalse(Arrays.equals(base.feedComponentMolarFlowsMolPerSecond(),input.feedComponentMolarFlowsMolPerSecond()));
        }
    }

    @Test void loadingPresetUsesTheSuppliedServerCatalogIncludingOverrides() {
        var original=MaterialCatalog.bundled();
        var resources=new HashMap<>(original.resources());
        String path="data/createcheme/materials/assays/bonga_tjl20.json";
        var assay=JsonParser.parseString(resources.get(path)).getAsJsonObject();
        assay.getAsJsonObject("amounts_by_component").addProperty("crude_pc01",.8);
        resources.put(path,assay.toString());
        var edited=MaterialCatalog.parse(resources);
        var before=ColumnInputPreset.BONGA.input(original);
        var after=ColumnInputPreset.BONGA.input(edited);
        assertFalse(Arrays.equals(before.feedComponentMolarFlowsMolPerSecond(),after.feedComponentMolarFlowsMolPerSecond()));
        assertEquals(before,ColumnInputPreset.BONGA.input(original));
        assertSame(original,MaterialRuntime.current());
    }
}
