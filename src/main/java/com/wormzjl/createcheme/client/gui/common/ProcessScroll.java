package com.wormzjl.createcheme.client.gui.common;

import net.minecraft.client.gui.GuiGraphics;

/** Draggable row scrollbar, independent of a screen's data source. */
public final class ProcessScroll {
    private int value,total,visible,x,y,height;
    private boolean dragging;
    public int value(){return value;}
    public void reset(){value=0;}
    public void reveal(int index){if(index<value)value=index;else if(index>=value+visible)value=index-visible+1;value=Math.clamp(value,0,max());}
    public void configure(int x,int y,int height,int visible,int total){
        this.x=x;this.y=y;this.height=Math.max(20,height);this.visible=Math.max(1,visible);this.total=total;
        value=Math.clamp(value,0,max());
    }
    private int max(){return Math.max(0,total-visible);}
    private int thumb(){return Math.min(height,Math.max(18,total==0?height:height*visible/total));}
    public void draw(GuiGraphics g){
        if(max()==0)return;
        int at=(height-thumb())*value/max();
        g.fill(x,y,x+8,y+height,ProcessUi.PANEL);g.fill(x+1,y+at,x+7,y+at+thumb(),dragging?ProcessUi.ACCENT:ProcessUi.LINE);
    }
    private void seek(double mouseY){value=Math.clamp((int)Math.round((mouseY-y-thumb()/2.0)*max()/Math.max(1,height-thumb())),0,max());}
    public boolean click(double mx,double my,int button){
        if(button!=0||max()==0||mx<x||mx>=x+8||my<y||my>=y+height)return false;
        dragging=true;seek(my);return true;
    }
    public boolean drag(double my){if(!dragging)return false;seek(my);return true;}
    public void release(){dragging=false;}
    public boolean wheel(double delta){if(max()==0)return false;value=Math.clamp(value-(int)Math.signum(delta)*3,0,max());return true;}
}
