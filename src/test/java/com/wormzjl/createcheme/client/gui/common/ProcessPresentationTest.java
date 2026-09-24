package com.wormzjl.createcheme.client.gui.common;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessPresentationTest {
    private static RelativeComposition mixture(){return new RelativeComposition(List.of("Light","Heavy"),List.of(.02,.1),new double[]{1,1});}
    @Test void basisSwitchPreservesPhysicalMixtureAndChangesFractionsAndFlowTagsTogether(){
        var mixture=mixture();assertFalse(mixture.mass());assertArrayEquals(new double[]{.5,.5},mixture.displayFractions(),1e-14);
        assertEquals(3.6,mixture.componentFlow(0,2,mixture.mass()),1e-12);
        mixture.toggleBasis();assertTrue(mixture.mass());assertArrayEquals(new double[]{1.0/6,5.0/6},mixture.displayFractions(),1e-14);
        assertArrayEquals(new double[]{.5,.5},mixture.moleFractions(),1e-14);
        assertEquals(72,mixture.componentFlow(0,2,mixture.mass()),1e-12);
        mixture.toggleBasis();assertFalse(mixture.mass());assertArrayEquals(new double[]{.5,.5},mixture.moleFractions(),1e-14);
    }
    @Test void customRowsStartEmptyAndRelativeMassEntriesDoNotNeedToSumToOneHundred(){
        var mixture=mixture();mixture.clear();assertTrue(mixture.rows().isEmpty());assertThrows(IllegalArgumentException.class,mixture::moleFractions);
        mixture.toggleBasis();mixture.add(1);mixture.add(0);mixture.set(0,"2");mixture.set(1,"5");
        assertArrayEquals(new double[]{2.0/3,1.0/3},mixture.moleFractions(),1e-14);
        mixture.remove(1);assertEquals(List.of(0),mixture.rows());assertArrayEquals(new double[]{1,0},mixture.moleFractions(),1e-14);
        mixture.set(0,"-1");assertThrows(IllegalArgumentException.class,mixture::moleFractions);
    }
    @Test void onlyPresentRowsAreLoadedAndServerWeightsAreValidated(){
        var mixture=new RelativeComposition(List.of("A","B","C"),List.of(.02,.03,.04),new double[]{0,4,0});
        assertEquals(List.of(1),mixture.rows());
        assertThrows(IllegalArgumentException.class,()->new RelativeComposition(List.of("A"),List.of(Double.NaN),new double[]{1}));
        assertThrows(IllegalArgumentException.class,()->new RelativeComposition(List.of("A"),List.of(.02),new double[]{-1}));
    }
    @Test void celsiusDefaultsAndUnitRoundTripsKeepPhysicalTemperature(){
        var unit=TemperatureUnit.CELSIUS;assertEquals("C",unit.symbol());assertEquals(25,unit.display(298.15),1e-12);
        assertEquals(298.15,unit.kelvin(25),1e-12);assertEquals("K",unit.next().symbol());assertEquals(unit,unit.next().next());
    }
    @Test void conciseDraftPreservesUntouchedPrecisionAndSmallDiameters(){
        var value=new NumericDraft(123.456789);assertEquals("123.5",value.text());assertEquals(123.456789,value.value());
        value.text("124.0");assertEquals(124,value.value());
        var diameter=new NumericDraft(.05);assertEquals(.05,Double.parseDouble(diameter.text()),1e-12);
        value.text("NaN");assertThrows(IllegalArgumentException.class,value::value);
    }
}
