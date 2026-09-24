package com.wormzjl.createcheme.client.gui.screens.inventory;

/** Uses the available height without shrinking physical tray targets below six pixels. */
final class V3DiagramLayout {
    private V3DiagramLayout(){}
    static int trayPitch(int trays,int viewportHeight){
        if(trays<2||trays>64)throw new IllegalArgumentException("Tray count outside diagram bounds");
        // 92 px above tray 1, 24 px bottom cap, and 175 px for feed/steam/reboiler controls.
        return Math.max(6,Math.max(240,viewportHeight-291)/(trays-1));
    }
    static int contentHeight(int trays,int viewportHeight){return 291+(trays-1)*trayPitch(trays,viewportHeight);}
}
