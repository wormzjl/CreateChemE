package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class V3ClientPresetsTest {
    @TempDir Path game;
    private V3ClientPresets library(){return new V3ClientPresets(game,n->getClass().getClassLoader().getResourceAsStream(n));}
    private static V3ColumnInput reference(){var c=MaterialCatalog.bundled();return c.presets().column("tia_juana").input(c);}
    private static V3EditorDraft draft(){
        var input=reference();return new V3EditorDraft(input,V3EditorCatalog.from(MaterialCatalog.bundled(),input).weightsFor(input));
    }
    @Test void bundledLibrariesAreIndependentAndPreserveAuthoredOperatingData(){
        var l=library();var columns=l.refresh(V3ClientPresets.Kind.COLUMN);var mixtures=l.refresh(V3ClientPresets.Kind.MIXTURE);
        assertTrue(columns.problems().isEmpty(),()->columns.problems().toString());assertTrue(mixtures.problems().isEmpty());
        assertEquals(8,columns.entries().size());assertEquals(7,mixtures.entries().size());
        for(var entry:columns.entries()){
            if(entry.file().equals("holland_3_2.json")||entry.file().equals("pot.json"))continue;
            var d=draft();var column=(V3ClientPresets.Column)entry.preset();d.loadColumn(column.fields());
            var changed=d.assemble();assertEquals(reference().packageId(),changed.packageId());
            var source=MaterialCatalog.bundled().presets().column(entry.file().replace(".json","")).input(MaterialCatalog.bundled());
            assertEquals(source.stageCount(),changed.stageCount());assertEquals(source.feedStageNumber(),changed.feedStageNumber());
            assertEquals(Arrays.stream(source.feedComponentMolarFlowsMolPerSecond()).sum(),Arrays.stream(changed.feedComponentMolarFlowsMolPerSecond()).sum(),1e-9);
            assertEquals(source.steamFeeds(),changed.steamFeeds());
        }
    }
    @Test void customFilesRefreshOnlyOnRequestAndNeverOverwriteAnExistingFile()throws Exception{
        var l=library();var first=l.refresh(V3ClientPresets.Kind.MIXTURE);var d=draft();
        d.composition().clear();d.composition().add(0);d.composition().set(0,"27");
        String json=V3ClientPresets.mixtureJson("custom",d.composition());
        Path file=l.save(V3ClientPresets.Kind.MIXTURE,"custom",json);
        assertEquals(game.resolve("CreatChemE/mixturepreset/custom.json"),file);
        assertEquals(7,first.entries().size());assertEquals(8,l.refresh(V3ClientPresets.Kind.MIXTURE).entries().size());
        assertNotEquals(file,l.save(V3ClientPresets.Kind.MIXTURE,"custom",json));assertEquals(json,Files.readString(file));
        assertThrows(IllegalArgumentException.class,()->l.save(V3ClientPresets.Kind.MIXTURE,"../escape",json));
    }
    @Test void jsonRoundTripRetainsIndependentMixtureAndColumnConfiguration(){
        var d=draft();for(int i=0;i<3;i++)d.removeDraw(i);for(int i=0;i<4;i++)d.removePumparound(i);d.set("s2","2");d.set("s3","2");
        var pot=d.assemble();assertEquals(0,pot.stageCount());assertEquals(1,pot.feedStageNumber());
        assertTrue(pot.sideDraws().isEmpty());assertTrue(pot.pumparounds().isEmpty());assertEquals(1,pot.steamFeeds().getFirst().stageNumber());
        String column=V3ClientPresets.columnJson("pot",pot);
        assertFalse(column.contains("package"));assertFalse(column.contains("components"));
        var c=(V3ClientPresets.Column)V3ClientPresets.decode(column,V3ClientPresets.Kind.COLUMN);
        var restored=draft();restored.loadColumn(c.fields());
        var reloaded=restored.assemble();assertEquals(pot.stageCount(),reloaded.stageCount());assertEquals(pot.steamFeeds(),reloaded.steamFeeds());
        assertArrayEquals(pot.feedComponentMolarFlowsMolPerSecond(),reloaded.feedComponentMolarFlowsMolPerSecond(),1e-12);
        d.composition().toggleBasis();
        String mixture=V3ClientPresets.mixtureJson("feed",d.composition());
        assertFalse(mixture.contains("tray_count"));assertFalse(mixture.contains("flow_kmol_h"));
        var m=(V3ClientPresets.Mixture)V3ClientPresets.decode(mixture,V3ClientPresets.Kind.MIXTURE);
        restored.composition().load(m.amounts(),m.mass());
        assertArrayEquals(d.composition().moleFractions(),restored.composition().moleFractions(),1e-14);
    }
    @Test void changingPercentBasisAndRateBasisProducesPhysicalMassAndMolarValues(){
        var d=new V3CompositionDraft(V3EditorDraftTest.input(),List.of(0.02,0.2));d.clear();d.add(0);d.add(1);d.set(0,"2");d.set(1,"3");
        assertArrayEquals(new double[]{0.4,0.6},d.displayFractions(),1e-14);
        assertEquals(14.4,d.componentFlow(0,10,false),1e-12);assertEquals(288,d.componentFlow(0,10,true),1e-12);
        d.toggleBasis();assertEquals(0.0625,d.displayFractions()[0],1e-14);
        assertEquals(14.4,d.componentFlow(0,10,false),1e-12);
    }
    @Test void invalidCustomFilesAreIsolatedFromValidEntries()throws Exception{
        var l=library();Files.createDirectories(l.directory(V3ClientPresets.Kind.COLUMN));
        Files.writeString(l.directory(V3ClientPresets.Kind.COLUMN).resolve("broken.json"),"{broken");
        Files.writeString(l.directory(V3ClientPresets.Kind.COLUMN).resolve("large.json")," ".repeat(V3ClientPresets.MAX_BYTES+1));
        var result=l.refresh(V3ClientPresets.Kind.COLUMN);assertEquals(8,result.entries().size());assertEquals(2,result.problems().size());
        var o=JsonParser.parseString(V3ClientPresets.columnJson("case",reference())).getAsJsonObject();o.addProperty("tray_count",1);
        assertThrows(IllegalArgumentException.class,()->V3ClientPresets.decode(o.toString(),V3ClientPresets.Kind.COLUMN));
    }

    @Test void separateHollandFilesCanReconstructTheFixedBenchmark(){
        var input=V3HollandExample32.input();var d=draft();
        var column=(V3ClientPresets.Column)library().refresh(V3ClientPresets.Kind.COLUMN).entries().stream().filter(e->e.file().equals("holland_3_2.json")).findFirst().orElseThrow().preset();
        var mix=(V3ClientPresets.Mixture)library().refresh(V3ClientPresets.Kind.MIXTURE).entries().stream().filter(e->e.file().equals("holland_3_2.json")).findFirst().orElseThrow().preset();
        d.loadColumn(column.fields());d.composition(input,Arrays.stream(V3HollandExample32.molecularWeights()).boxed().toList());d.composition().load(mix.amounts(),mix.mass());
        assertEquals(input,d.assemble());
        d.set("s6","9990");assertThrows(IllegalArgumentException.class,()->V3HollandExample32.validateInput(d.assemble()));
    }
}
