package com.wormzjl.createcheme.client.gui.common;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import static com.wormzjl.createcheme.client.gui.common.ProcessUi.*;

/** Search field with a bounded overlay list; typing never rebuilds the parent screen. */
public final class ComponentDropdown extends EditBox {
    private static final int ROW=18;
    private final Font font;
    private final int fullWidth,rows;
    private final List<ComponentSearch.Option> options;
    private final IntConsumer select;
    private final ProcessScroll scroll=new ProcessScroll();
    private List<ComponentSearch.Option> matches=List.of();
    private int selected;
    private boolean expanded;
    public ComponentDropdown(Font font,int x,int y,int width,int availableHeight,List<ComponentSearch.Option> options,
            String query,Consumer<String> changed,IntConsumer select){
        super(font,x,y,width-20,18,Component.translatable("gui.createcheme.common.component_search"));
        this.font=font;this.fullWidth=width;this.options=List.copyOf(options);this.select=select;
        rows=Math.clamp((availableHeight-22)/ROW,1,6);
        setMaxLength(64);setHint(Component.translatable("gui.createcheme.common.component_search"));
        setTooltip(Tooltip.create(Component.translatable("gui.createcheme.common.component_search.hint")));
        setValue(query);updateMatches(query);
        setResponder(value->{changed.accept(value);updateMatches(value);expanded=true;});
    }
    private void updateMatches(String query){matches=ComponentSearch.rank(options,query);selected=0;scroll.reset();configure();}
    private void configure(){scroll.configure(getX()+fullWidth-9,getY()+20,Math.max(1,Math.min(rows,matches.size()))*ROW,rows,matches.size());}
    public boolean expanded(){return expanded;}
    public void expanded(boolean value){expanded=value;}
    private boolean open(){return visible&&active&&isFocused()&&expanded;}
    private int popupHeight(){return Math.max(1,Math.min(rows,matches.size()))*ROW;}
    public boolean popupContains(double x,double y){return open()&&x>=getX()&&x<getX()+fullWidth&&y>=getY()+20&&y<getY()+20+popupHeight();}
    @Override public boolean isMouseOver(double x,double y){return super.isMouseOver(x,y)||x>=getX()+getWidth()&&x<getX()+fullWidth&&y>=getY()&&y<getY()+18;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(!active||!visible||button!=0)return false;
        if(isMouseOver(x,y)){
            boolean arrow=x>=getX()+getWidth();expanded=arrow?!expanded:true;
            if(!arrow)super.mouseClicked(x,y,button);return true;
        }
        return false;
    }
    @Override public void setFocused(boolean focused){super.setFocused(focused);if(!focused)expanded=false;}
    /** Called before ordinary screen widgets, so the popup wins over the table beneath it. */
    public boolean popupClick(double x,double y,int button){
        if(!popupContains(x,y)){if(!isMouseOver(x,y))expanded=false;return false;}
        if(button!=0)return true;
        if(scroll.click(x,y,button))return true;
        int index=scroll.value()+(int)(y-getY()-20)/ROW;
        if(index<matches.size()){selected=index;choose();}return true;
    }
    public boolean popupDrag(double y){return scroll.drag(y);}
    public void popupRelease(){scroll.release();}
    public boolean popupScroll(double x,double y,double amount){
        if(!popupContains(x,y))return false;scroll.wheel(amount);return true;
    }
    private void choose(){if(matches.isEmpty())return;expanded=false;select.accept(matches.get(selected).id());}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(!isFocused()||!active)return false;
        if(key==GLFW.GLFW_KEY_ESCAPE&&expanded){expanded=false;return true;}
        if(key==GLFW.GLFW_KEY_DOWN||key==GLFW.GLFW_KEY_UP){
            if(!expanded)expanded=true;
            else if(!matches.isEmpty())selected=Math.clamp(selected+(key==GLFW.GLFW_KEY_DOWN?1:-1),0,matches.size()-1);
            scroll.reveal(selected);return true;
        }
        if((key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)&&expanded){choose();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void renderWidget(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        super.renderWidget(g,mouseX,mouseY,partialTick);
        int x=getX()+fullWidth-18,y=getY();
        g.fill(x,y,x+18,y+18,PANEL);g.renderOutline(x,y,18,18,isFocused()?ACCENT:LINE);
        for(int i=0;i<4;i++)g.hLine(x+5+i,x+12-i,y+7+i,TEXT);
    }
    /** Render after all normal screen widgets so suggestions form a true dropdown overlay. */
    public void renderSuggestions(GuiGraphics g,int mouseX,int mouseY){
        if(!open())return;
        int x=getX(),y=getY()+20;g.pose().pushPose();g.pose().translate(0,0,500);
        g.fill(x-1,y-1,x+fullWidth+1,y+popupHeight()+1,LINE);g.fill(x,y,x+fullWidth,y+popupHeight(),BG);
        if(matches.isEmpty())g.drawString(font,Component.translatable("gui.createcheme.common.no_components"),x+5,y+5,MUTED,false);
        for(int i=scroll.value();i<Math.min(matches.size(),scroll.value()+rows);i++){
            int rowY=y+(i-scroll.value())*ROW;boolean hovered=mouseX>=x&&mouseX<x+fullWidth-10&&mouseY>=rowY&&mouseY<rowY+ROW;
            if(i==selected||hovered)g.fill(x,rowY,x+fullWidth-10,rowY+ROW,PANEL);
            if(i==selected)g.fill(x,rowY,x+2,rowY+ROW,ACCENT);
            g.drawString(font,font.plainSubstrByWidth(matches.get(i).label(),fullWidth-22),x+6,rowY+5,hovered||i==selected?TEXT:MUTED,false);
        }
        scroll.draw(g);g.pose().popPose();
    }
}
