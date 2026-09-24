package com.wormzjl.createcheme.client.gui.screens.inventory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V3DiagramLayoutTest {
    @Test void largerWindowFitsEverySupportedTrayCountWithoutScrolling(){
        for(int trays=2;trays<=64;trays++)for(int available:new int[]{740,920,1300}){
            assertTrue(V3DiagramLayout.contentHeight(trays,available)<=available,"trays="+trays);
            assertTrue(V3DiagramLayout.trayPitch(trays,available)>=6);
        }
    }
    @Test void smallWindowsScrollInsteadOfMakingTraysUnclickable(){
        assertTrue(V3DiagramLayout.contentHeight(64,320)>320);
        assertEquals(6,V3DiagramLayout.trayPitch(64,320));
    }
}
