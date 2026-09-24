package com.wormzjl.createcheme.client.gui.screens.inventory;
import com.wormzjl.createcheme.science.column.v3.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V3EditorDraftTest {
    static V3ColumnInput input() {
        return new V3ColumnInput(1,"test:package","test:case",new V3ComponentBasis(List.of("a","b")),
                new double[]{12.123456789123, 3.987654321987}, 531.123456789, 8, 4, 201234.567891,
                712.123456789, List.of(new V3ColumnSpecification.CondenserOutletTemperature(350.123456789),
                new V3ColumnSpecification.OrganicRefluxRatio(2.123456789),
                new V3ColumnSpecification.ReboilerDuty(1000000.123456789)),List.of(),List.of(),List.of(),8);
    }
    @Test void untouchedCaseKeepsExactIdentity() {
        var input=input();var draft=new V3EditorDraft(input);
        assertSame(input,draft.assemble()); assertFalse(draft.dirty());
    }
    @Test void editingOneFieldDoesNotRoundOtherPhysicalInputs() {
        var input=input();var draft=new V3EditorDraft(input);draft.set("s6","3");
        var edited=draft.assemble();
        assertArrayEquals(input.feedComponentMolarFlowsMolPerSecond(),edited.feedComponentMolarFlowsMolPerSecond());
        assertEquals(input.feedTemperatureKelvin(),edited.feedTemperatureKelvin());
        assertEquals(input.topPressurePascal(),edited.topPressurePascal());
        assertEquals(input.stagePressureDropPascal(),edited.stagePressureDropPascal());
        assertEquals(3,V3EditorDraft.value(edited,V3ControlledQuantity.ORGANIC_REFLUX_RATIO));
        assertEquals(V3EditorDraft.value(input,V3ControlledQuantity.REBOILER_DUTY),V3EditorDraft.value(edited,V3ControlledQuantity.REBOILER_DUTY));
    }
    @Test void invalidDraftRemainsEditableAndCanReturnToItsOriginalValue() {
        var draft=new V3EditorDraft(input());var old=draft.get("s2");draft.set("s2","67");
        assertThrows(IllegalArgumentException.class,draft::assemble);assertEquals("67",draft.get("s2"));
        draft.set("s2",old);assertFalse(draft.dirty());assertSame(draft.base(),draft.assemble());
    }
    @Test void changingTrayCountMovesSumpSteamWithoutRoundingItsRateOrTemperature() {
        var base=input();
        var steam=new V3SteamFeedSpec(9, 1.234567891234, 600.123456789);
        var wet=new V3ColumnInput(base.schemaVersion(),base.packageId(),base.assayId(),base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(),base.feedTemperatureKelvin(),base.stageCount(),
                base.feedStageNumber(),base.topPressurePascal(),base.stagePressureDropPascal(),base.specifications(),
                base.sideDraws(),List.of(steam),base.pumparounds(),base.columnDiameterMetres());
        var draft=new V3EditorDraft(wet);draft.set("s2","64");
        var changed=draft.assemble().steamFeeds().getFirst();
        assertEquals(63,changed.stageNumber());
        assertEquals(steam.molarFlowMolPerSecond(),changed.molarFlowMolPerSecond());
        assertEquals(steam.temperatureKelvin(),changed.temperatureKelvin());
    }


    @Test void massAndMolarRelativeAmountsDescribeTheSameMixture(){
        var comp=new V3CompositionDraft(input(),List.of(0.02,0.2));comp.clear();
        comp.add(0);comp.add(1);comp.set(0,"2");comp.set(1,"3");
        assertArrayEquals(new double[]{0.4,0.6},comp.moleFractions(),1e-14);
        comp.toggleBasis();
        assertArrayEquals(new double[]{0.4,0.6},comp.moleFractions(),1e-14);
        comp.set(0,"4");comp.set(1,"60");
        assertArrayEquals(new double[]{0.4,0.6},comp.moleFractions(),1e-14);
        comp.remove(0);assertArrayEquals(new double[]{0,1},comp.moleFractions());
        comp.clear();assertThrows(IllegalArgumentException.class,comp::moleFractions);
    }
    @Test void invalidRelativeWeightsCannotReachTheSolver(){
        var c=new V3CompositionDraft(input(),List.of(0.02,0.2));
        for(String invalid:List.of("-1","NaN","Infinity","")){
            c.set(0,invalid);assertThrows(IllegalArgumentException.class,c::moleFractions);
        }
    }
    @Test void customFeedScalesRelativeValuesByTheRequestedTotal(){
        var d=new V3EditorDraft(input(),List.of(0.02,0.2));d.composition().clear();
        d.composition().add(1);d.composition().set(1,"17");
        d.set("s0","36.0");assertArrayEquals(new double[]{0,10},d.assemble().feedComponentMolarFlowsMolPerSecond());
    }
    @Test void unitSwitchIsOnlyPresentationAndKelvinEditsConvertOnce(){
        var d=new V3EditorDraft(input());String display=d.display("s1",true);
        assertEquals("531.1",display);assertFalse(d.dirty());assertSame(d.base(),d.assemble());
        d.setDisplay("s1","600.0",true);assertEquals(600,d.assemble().feedTemperatureKelvin());
    }
    @Test void liveGeometryWorksEvenWhenAnotherFieldIsIncomplete(){
        var d=new V3EditorDraft(input());d.set("s0","");d.set("s2","32");d.set("s3","22");
        assertEquals(32,d.preview("s2",8,2,64));assertEquals(22,d.preview("s3",4,1,32));
        assertThrows(IllegalArgumentException.class,d::assemble);
    }
    @Test void connectionsCanBeAddedConfiguredAndRemoved(){
        var d=new V3EditorDraft(input());int draw=d.addDraw(3),pa=d.addPumparound(6);
        assertEquals(2,d.assemble().sideDraws().getFirst().trayNumber());
        assertEquals(5,d.assemble().pumparounds().getFirst().drawTray());
        d.removeDraw(draw);d.removePumparound(pa);assertTrue(d.assemble().sideDraws().isEmpty());assertTrue(d.assemble().pumparounds().isEmpty());
    }

    @Test void blankCustomMixtureCanChooseMassBasisBeforeAddingComponents(){
        var c=new V3CompositionDraft(input(),List.of(0.02,0.2));c.clear();c.toggleBasis();assertTrue(c.mass());
        c.add(0);c.add(1);c.set(0,"4");c.set(1,"60");assertArrayEquals(new double[]{0.4,0.6},c.moleFractions(),1e-14);
    }
    @Test void compositionOnlyPresetKeepsOperatingConfiguration(){
        var base=input();var replacement=new V3ColumnInput(1,"new:package","new:case",new V3ComponentBasis(List.of("c")),
            new double[]{50},500,10,3,300000,0,base.specifications(),List.of(),List.of(),List.of(),9);
        var d=new V3EditorDraft(base,List.of(0.02,0.2));d.composition(replacement,List.of(0.1));
        var result=d.assemble();
        assertEquals("new:package",result.packageId());assertEquals(8,result.stageCount());assertEquals(base.feedTemperatureKelvin(),result.feedTemperatureKelvin());
        assertEquals(java.util.Arrays.stream(base.feedComponentMolarFlowsMolPerSecond()).sum(),result.feedComponentMolarFlowsMolPerSecond()[0],1e-12);
        assertEquals(replacement,new V3EditorDraft(replacement,List.of(0.1)).assemble());
    }

    @Test void intermediateTrayCountTypingDoesNotDeleteConnections(){
        var d=new V3EditorDraft(input());d.addDraw(6);d.addPumparound(8);
        d.set("s2","4");d.set("s2","42");
        assertEquals("6",d.get("d0stage"));assertEquals("8",d.get("c0draw"));
    }
    @Test void bottomSteamFollowsTotalTrayCountButInteriorSteamStaysOnItsTray(){
        var d=new V3EditorDraft(input());int a=d.addSteam(10),b=d.addSteam(4);
        d.set("s2","22");
        assertEquals("22",d.get("t"+a+"stage"));assertEquals("4",d.get("t"+b+"stage"));
        assertTrue(d.assemble().steamFeeds().stream().anyMatch(t->t.stageNumber()==21));
    }

    @Test void potHasOnlyOneDistinctSteamLocation(){
        var d=new V3EditorDraft(input());d.set("s2","2");d.set("s3","2");
        assertTrue(d.canAddSteam());assertEquals(0,d.addSteam(2));assertFalse(d.canAddSteam());assertEquals(-1,d.addSteam(2));
        d.removeSteam(0);assertTrue(d.canAddSteam());
    }
}
