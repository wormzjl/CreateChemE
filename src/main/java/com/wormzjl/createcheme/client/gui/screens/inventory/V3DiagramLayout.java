package com.wormzjl.createcheme.client.gui.screens.inventory;

/** Compact geometry in GUI units; three-pixel tray targets are nine physical pixels at scale 3. */
final class V3DiagramLayout {
    private V3DiagramLayout(){}
    static int trayPitch(int interiorTrays,int viewportHeight){
        if(interiorTrays<0||interiorTrays>64)throw new IllegalArgumentException("Tray count outside diagram bounds");
        return Math.max(3,Math.max(96,viewportHeight-131)/(interiorTrays+1));
    }
    static int contentHeight(int interiorTrays,int viewportHeight){return 131+(interiorTrays+1)*trayPitch(interiorTrays,viewportHeight);}
}
