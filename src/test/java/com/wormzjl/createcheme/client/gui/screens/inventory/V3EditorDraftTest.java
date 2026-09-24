package com.wormzjl.createcheme.client.gui.screens.inventory;
import com.wormzjl.createcheme.science.column.v3.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V3EditorDraftTest {
    private static V3ColumnInput input() {
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
        var draft=new V3EditorDraft(input());var old=draft.get("s2");draft.set("s2","65");
        assertThrows(IllegalArgumentException.class,draft::assemble);assertEquals("65",draft.get("s2"));
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
        assertEquals(65,changed.stageNumber());
        assertEquals(steam.molarFlowMolPerSecond(),changed.molarFlowMolPerSecond());
        assertEquals(steam.temperatureKelvin(),changed.temperatureKelvin());
    }

}
