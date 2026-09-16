package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.network.FluidNetwork;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.world.inventory.FluidDeviceMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import java.util.*;

/** Compact native Minecraft controls with explicit SI units and last-interval phase history. */
public final class FluidDeviceScreen extends AbstractContainerScreen<FluidDeviceMenu> {
    private final Map<String,EditBox> fields=new LinkedHashMap<>();
    private final List<EditBox> mixtureFields=new ArrayList<>();
    private final Map<String,String> labels=new LinkedHashMap<>();
    private Button phaseButton,mixButton,presetButton,applyButton,pathButton;
    private long draftRevision=Long.MIN_VALUE;
    private long lastMessageRevision;
    private double[] composition=new double[22];
    private String[] lastMixtureText=new String[22];
    private int phase=2,page,rows,preset=-1,path;
    private boolean editMixture,reverse;
    private String localMessage="";
    public FluidDeviceScreen(FluidDeviceMenu menu,Inventory inventory,Component title){super(menu,inventory,title);}
    @Override protected void init() {
        imageWidth=Math.min(454,width-12);imageHeight=Math.min(284,height-12);super.init();rows=Math.max(2,(imageHeight-200)/12);fields.clear();mixtureFields.clear();draftRevision=Long.MIN_VALUE;
        int right=leftPos+imageWidth/2+8,fieldWidth=imageWidth/2-20;
        for(String key:List.of("temperature","pressure","diameter","roughness","volumeFlow","maximumAddedPressure")) {
            var field=new EditBox(font,right,topPos+70,fieldWidth,16,Component.literal(key));field.setMaxLength(32);field.setVisible(false);addRenderableWidget(field);fields.put(key,field);
        }
        for(int i=0;i<rows;i++){var field=new EditBox(font,leftPos+imageWidth-100,topPos+161+i*12,86,11,Component.literal("Component percentage"));field.setMaxLength(32);field.setVisible(false);mixtureFields.add(addRenderableWidget(field));}
        int bottom=topPos+imageHeight-24;
        phaseButton=addRenderableWidget(Button.builder(Component.literal("Vapor"),button->{phase=(phase+1)%3;phaseButton.setMessage(Component.literal(phaseName()));}).bounds(leftPos+8,bottom,64,18).build());
        addRenderableWidget(Button.builder(Component.literal("<"),button->{if(collectMixture()){page=Math.max(0,page-1);showMixture();}}).bounds(leftPos+75,bottom,20,18).build());
        addRenderableWidget(Button.builder(Component.literal(">"),button->{if(collectMixture()){page=Math.min((21)/rows,page+1);showMixture();}}).bounds(leftPos+97,bottom,20,18).build());
        mixButton=addRenderableWidget(Button.builder(Component.literal("Edit mix"),button->{if(collectMixture()){editMixture=!editMixture;showMixture();}}).bounds(leftPos+120,bottom,52,18).build());mixButton.visible=false;
        applyButton=addRenderableWidget(Button.builder(Component.literal("Apply"),button->apply()).bounds(leftPos+imageWidth-111,bottom,52,18).build());
        addRenderableWidget(Button.builder(Component.literal("Close"),button->onClose()).bounds(leftPos+imageWidth-56,bottom,48,18).build());
        presetButton=addRenderableWidget(Button.builder(Component.literal("Preset"),button->preset()).bounds(right,topPos+122,fieldWidth,18).build());presetButton.visible=false;
        pathButton=addRenderableWidget(Button.builder(Component.literal("Forward"),button->{var data=menu.clientData();if(data!=null&&!data.view().pipeHistory().isEmpty()){if(reverse){path=(path+1)%data.view().pipeHistory().size();reverse=false;}else reverse=true;}}).bounds(right,topPos+122,fieldWidth,18).build());pathButton.visible=false;
    }
    private void refreshDraft() {
        if(menu.messageRevision()!=lastMessageRevision){lastMessageRevision=menu.messageRevision();localMessage=menu.message();}
        var data=menu.clientData();if(data==null||data.view().inputRevision()==draftRevision)return;draftRevision=data.view().inputRevision();var c=data.controls();composition=c.composition();
        fields.get("temperature").setValue(Double.toString(c.temperature()));fields.get("pressure").setValue(Double.toString(c.pressure()));fields.get("diameter").setValue(Double.toString(c.diameter()));fields.get("roughness").setValue(Double.toString(c.roughness()));fields.get("volumeFlow").setValue(Double.toString(c.volumeFlow()));fields.get("maximumAddedPressure").setValue(Double.toString(c.maximumAddedPressure()));
        labels.clear();
        switch(data.kind()) {
            case PUMP->{labels.put("volumeFlow","Suction flow (m3/s)");labels.put("maximumAddedPressure","Max pressure rise (Pa)");}
            case VALVE->labels.put("pressure","Upstream (Pa abs)");
            case GENERATOR->{labels.put("pressure","Pressure (Pa abs)");labels.put("temperature","Temperature (K)");}
            case VOID->labels.put("pressure","Sink pressure (Pa abs)");
            case PIPE->{labels.put("diameter","Internal diameter (m)");labels.put("roughness","Wall roughness (m)");}
            default->{}
        }
        fields.values().forEach(f->f.setVisible(false));int row=0;for(var entry:labels.entrySet()){var f=fields.get(entry.getKey());f.setY(topPos+68+row++*31);f.setVisible(!menu.debug());f.setEditable(!menu.debug());}
        presetButton.visible=data.kind()==TopologyCompiler.Kind.GENERATOR&&!menu.debug();mixButton.visible=presetButton.visible;
        applyButton.active=!menu.debug()&&data.kind()!=TopologyCompiler.Kind.RESERVOIR;pathButton.visible=data.kind()==TopologyCompiler.Kind.PIPE;
        preset=-1;for(int i=0;i<data.presets().size();i++)if(Arrays.equals(composition,data.presets().get(i).moleFractions()))preset=i;
        showMixture();
    }
    private void preset() {
        var data=menu.clientData();if(data==null)return;preset=(preset+1)%data.presets().size();composition=data.presets().get(preset).moleFractions();
        if(preset>=2){fields.get("temperature").setValue("350.0");localMessage="Crude preset starts at 350 K; review before applying.";}showMixture();
    }
    private boolean collectMixture() {
        if(!editMixture)return true;
        try {
            for(int i=0;i<mixtureFields.size();i++){int c=page*rows+i;if(c>=22)break;String text=mixtureFields.get(i).getValue();if(!text.equals(lastMixtureText[c])){double value=Double.parseDouble(text)/100;if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException();composition[c]=value;lastMixtureText[c]=text;}}
            return true;
        }catch(RuntimeException invalid){localMessage="Composition must use nonnegative percentages.";return false;}
    }
    private void showMixture() {
        for(int i=0;i<mixtureFields.size();i++){int c=page*rows+i;var field=mixtureFields.get(i);field.setVisible(editMixture&&c<22);if(c<22){lastMixtureText[c]=Double.toString(100*composition[c]);field.setValue(lastMixtureText[c]);}}
        if(mixButton!=null)mixButton.setMessage(Component.literal(editMixture?"Phases":"Edit mix"));
    }
    private void apply() {
        if(!collectMixture()||menu.clientData()==null)return;
        try {
            var controls=new FluidNetwork.Controls(number("temperature"),number("pressure"),number("diameter"),number("roughness"),number("volumeFlow"),number("maximumAddedPressure"),composition);
            FluidNetwork.sendEdit(menu,draftRevision,controls);localMessage="Waiting for server validation...";
        }catch(RuntimeException invalid){localMessage="Not applied: "+invalid.getMessage();}
    }
    private double number(String key){return Double.parseDouble(fields.get(key).getValue());}
    private String phaseName(){return switch(phase){case 0->"Liquid";case 1->"Water";default->"Vapor";};}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        refreshDraft();super.render(graphics,mouseX,mouseY,partialTick);renderTooltip(graphics,mouseX,mouseY);
    }
    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY) {}
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY) {
        int x=leftPos,y=topPos,right=x+imageWidth/2+8;
        g.fill(x,y,x+imageWidth,y+imageHeight,0xff17232e);g.fill(x+1,y+1,x+imageWidth-1,y+20,0xff294252);g.drawString(font,title,x+9,y+7,0xffedf5f8,false);
        var data=menu.clientData();if(data==null){g.drawString(font,"Waiting for server state...",x+10,y+35,0xffb6ced6,false);return;}
        var view=data.view();var statusLines=font.split(Component.literal(view.status()),imageWidth-20);for(int i=0;i<Math.min(3,statusLines.size());i++)g.drawString(font,statusLines.get(i),x+10,y+25+i*9,0xffb6ced6,false);
        if(view.state()!=null&&data.kind()!=TopologyCompiler.Kind.PIPE) {
            var s=view.state();line(g,"Pressure  "+format(s.pressure()/1000)+" kPa abs",x+10,y+58);line(g,"Temperature  "+format(s.temperature())+" K",x+10,y+71);
            if(data.kind()==TopologyCompiler.Kind.RESERVOIR) {
                line(g,"Volume  "+format(s.volume()*1000)+" L",x+10,y+84);line(g,"Mass  "+format(s.mass())+" kg",x+10,y+97);
            } else {
                double flow=data.kind()==TopologyCompiler.Kind.VOID?-view.massFlow():view.massFlow();
                line(g,"Flow (last)  "+format(flow)+" kg/s",x+10,y+84);
                String detail=data.kind()==TopologyCompiler.Kind.GENERATOR?"Source: no depletion":data.kind()==TopologyCompiler.Kind.VOID?"Fixed-pressure sink":view.devicePressureChange()==null?"Zero stored inventory":"Pressure change  "+format(view.devicePressureChange()/1000)+" kPa";
                line(g,detail,x+10,y+97);
            }
        } else {
            if(!view.pipeHistory().isEmpty()&&view.intervalSeconds()>0) {
                path=Math.min(path,view.pipeHistory().size()-1);var transfer=view.pipeHistory().get(path);var selected=reverse?transfer.reverse():transfer.forward();
                line(g,"Net  "+format((transfer.forward().massKg()-transfer.reverse().massKg())/view.intervalSeconds())+" kg/s",x+10,y+58);
                line(g,(reverse?"Reverse  ":"Forward  ")+format(selected.massKg()/view.intervalSeconds())+" kg/s",x+10,y+71);
            } else {line(g,"Last completed interval",x+10,y+58);line(g,"Flow  "+format(view.massFlow())+" kg/s",x+10,y+71);}
            if(view.hydraulicOwner())line(g,"Committed  "+format(view.committedTick()/20.0)+" s",x+10,y+84);
            if(view.intervalSeconds()>0)line(g,format(view.intervalSeconds())+" s / "+view.intervalQuality(),x+10,y+97);
        }
        line(g,!view.hydraulicOwner()?"No hydraulic interval":"Lag  "+format((view.onlineTick()-view.committedTick())/20.0)+" s",x+10,y+110);
        if(!menu.debug()){int i=0;for(var label:labels.values())g.drawString(font,font.plainSubstrByWidth(label,imageWidth/2-20),right,y+57+i++*31,0xffb6ced6,false);}
        if(data.kind()==TopologyCompiler.Kind.RESERVOIR){line(g,"Adiabatic reservoir",right,y+60);line(g,"Bulk withdrawal:",right,y+77);line(g,"all phases together",right,y+89);}
        if(presetButton.visible)presetButton.setMessage(Component.literal(font.plainSubstrByWidth(preset<0?"Preset: custom":data.presets().get(preset).name(),presetButton.getWidth()-8)));
        if(pathButton.visible)pathButton.setMessage(Component.literal((reverse?"Reverse":"Forward")+" / run "+(path+1)));
        if(menu.debug()&&!view.pipeHistory().isEmpty()) {
            long runId=view.pipeHistory().get(Math.min(path,view.pipeHistory().size()-1)).pipeId();
            for(var route:view.pipeRoutes())if(route.pipeId()==runId) {
                line(g,"From "+(reverse?route.second():route.first()),right,y+58);
                line(g,"To   "+(reverse?route.first():route.second()),right,y+71);
            }
        }
        double[][] amounts=view.state()==null?new double[3][22]:view.state().phaseMoles();double[] volumes=view.state()==null?new double[3]:view.state().phaseVolumes();
        if(data.kind()==TopologyCompiler.Kind.PIPE&&!view.pipeHistory().isEmpty()) {
            path=Math.min(path,view.pipeHistory().size()-1);var transfer=view.pipeHistory().get(path);var stream=reverse?transfer.reverse():transfer.forward();amounts=stream.phaseMoles();volumes=stream.phaseVolumes();
        }
        double totalVolume=Arrays.stream(volumes).sum();int barWidth=imageWidth/2-20,at=x+10;int[] colors={0xffd6a65a,0xff549bd2,0xffb3c0ca};
        for(int p=0;p<3;p++){int pixels=totalVolume>0?(int)Math.round(barWidth*volumes[p]/totalVolume):0;g.fill(at,y+123,at+pixels,y+129,colors[p]);at+=pixels;}
        String fraction=totalVolume>0?String.format(Locale.ROOT,"L %.1f%%  W %.1f%%  V %.1f%%",100*volumes[0]/totalVolume,100*volumes[1]/totalVolume,100*volumes[2]/totalVolume):"No committed phase sample";
        g.drawString(font,font.plainSubstrByWidth(fraction,imageWidth/2-18),x+10,y+133,0xffb6ced6,false);
        String heading=editMixture?"Generator composition (mole %)":phaseName()+" composition (mole %)";
        line(g,heading+"   "+(page+1)+"/"+((21)/rows+1),x+10,y+149);
        double sum=Arrays.stream(amounts[phase]).sum();for(int i=0;i<rows;i++) {
            int c=page*rows+i;if(c>=22)break;int rowY=y+163+i*12;String name=data.components().get(c);if(name.startsWith("tjl19_pc"))name="Crude cut "+Integer.parseInt(name.substring(8));
            g.drawString(font,name,x+12,rowY,0xffedf5f8,false);
            if(!editMixture)g.drawString(font,sum>0?format(100*amounts[phase][c]/sum)+" %":"Absent",x+imageWidth-94,rowY,0xffb6ced6,false);
        }
        if(!localMessage.isEmpty())g.drawString(font,font.plainSubstrByWidth(localMessage,imageWidth-18),x+9,y+imageHeight-36,0xffefca83,false);
        phaseButton.active=!editMixture;
    }
    private void line(GuiGraphics g,String text,int x,int y){g.drawString(font,text,x,y,0xffedf5f8,false);}
    private static String format(double value){if(Math.abs(value)<1e-10)return "0.00";return String.format(Locale.ROOT,Math.abs(value)<.01?"%.3g":"%.2f",value);}
}
