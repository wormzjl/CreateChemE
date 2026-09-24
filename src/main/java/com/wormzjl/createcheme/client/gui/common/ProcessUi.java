package com.wormzjl.createcheme.client.gui.common;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import java.util.Locale;

/** Shared instrument palette, table cells, numeric formatting and screen cursor recovery. */
public final class ProcessUi {
    public static final int BG=0xFF202A31,PANEL=0xFF28363F,LINE=0xFF536C7A,TEXT=0xFFEAF1F5,
        MUTED=0xFFA9BCC7,ACCENT=0xFF79D6CE,WARN=0xFFFFCD82;
    private ProcessUi(){}
    public static String number(double value){
        return String.format(Locale.ROOT,value!=0&&Math.abs(value)<0.05?"%.3g":"%.1f",value);
    }
    /** A focus/control hand-off can hide/grab the cursor without reopening the existing screen. */
    public static void restoreCursor(Minecraft client,Screen screen){
        if(client==null||client.screen!=screen||!client.isWindowActive())return;
        if(client.mouseHandler.isMouseGrabbed())client.mouseHandler.releaseMouse();
        long window=client.getWindow().getWindow();
        if(GLFW.glfwGetInputMode(window,GLFW.GLFW_CURSOR)!=GLFW.GLFW_CURSOR_NORMAL)
            GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_NORMAL);
    }
    public static void tableRow(GuiGraphics g,Font font,String[] cells,int x,int y,int width,boolean header){
        g.fill(x,y-2,x+width,y+12,header?LINE:PANEL);int at=x,first=width/2;
        for(int i=0;i<cells.length;i++){
            int cellWidth=i==0?first:(width-first)/Math.max(1,cells.length-1);
            g.drawString(font,font.plainSubstrByWidth(cells[i],Math.max(0,cellWidth-6)),at+3,y,header?TEXT:MUTED,false);at+=cellWidth;
        }
    }
}
