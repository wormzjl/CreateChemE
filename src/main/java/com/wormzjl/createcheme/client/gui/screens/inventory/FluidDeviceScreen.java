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
import com.wormzjl.createcheme.runtime.fluid.SlurryFeed;
import com.wormzjl.createcheme.science.fluid.state.ParticleSize;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;

/** Compact native Minecraft controls with explicit SI units and last-interval phase history. */
public final class FluidDeviceScreen extends AbstractContainerScreen<FluidDeviceMenu> {
    private final Map<String,EditBox> fields=new LinkedHashMap<>();
    private final List<EditBox> mixtureFields=new ArrayList<>();
    private final Map<String,String> labels=new LinkedHashMap<>();
    private Button phaseButton,mixButton,presetButton,applyButton,pathButton,solidsButton,recoverButton;
    private final List<EditBox[]> particleFields=new ArrayList<>();
    private String[][] particleText=new String[64][3];
    private boolean editSolids;
    private long draftRevision=Long.MIN_VALUE;
    private long lastMessageRevision;
    private double[] composition=new double[0];
    private String[] lastMixtureText=new String[0];
    private int phase=2,page,rows,preset=-1,path;
    private boolean editMixture,reverse;
    private String localMessage="";
    private com.wormzjl.createcheme.science.material.MaterialName hoveredMaterial;
    public FluidDeviceScreen(FluidDeviceMenu menu,Inventory inventory,Component title){super(menu,inventory,title);}
    @Override protected void init() {
        imageWidth=Math.min(454,width-12);imageHeight=Math.min(284,height-12);super.init();rows=Math.max(2,(imageHeight-200)/12);fields.clear();mixtureFields.clear();particleFields.clear();draftRevision=Long.MIN_VALUE;
        int right=leftPos+imageWidth/2+8,fieldWidth=imageWidth/2-20;
        for(String key:List.of("temperature","pressure","diameter","roughness","volumeFlow","maximumAddedPressure","solidFraction")) {
            var field=new EditBox(font,right,topPos+70,fieldWidth,16,Component.literal(key));field.setMaxLength(32);field.setVisible(false);addRenderableWidget(field);fields.put(key,field);
        }
        for(int i=0;i<rows;i++){var field=new EditBox(font,leftPos+imageWidth-100,topPos+161+i*12,86,11,Component.literal("Component percentage"));field.setMaxLength(32);field.setVisible(false);mixtureFields.add(addRenderableWidget(field));}
        for(int i=0;i<rows;i++){var cells=new EditBox[3];int[] xs={leftPos+12,leftPos+imageWidth-174,leftPos+imageWidth-92};int[] widths={imageWidth-194,76,76};
            for(int c=0;c<3;c++){cells[c]=new EditBox(font,xs[c],topPos+161+i*12,widths[c],11,Component.literal(new String[]{"Solid material ID","Particle size (µm)","Relative mass share"}[c]));cells[c].setMaxLength(c==0?128:64);cells[c].setVisible(false);addRenderableWidget(cells[c]);}particleFields.add(cells);}
        int bottom=topPos+imageHeight-24;
        phaseButton=addRenderableWidget(Button.builder(Component.literal("Vapor"),button->{phase=(phase+1)%4;page=0;phaseButton.setMessage(Component.literal(phaseName()));}).bounds(leftPos+8,bottom,64,18).build());
        addRenderableWidget(Button.builder(Component.literal("<"),button->{if(collectMixture()){page=Math.max(0,page-1);showMixture();}}).bounds(leftPos+75,bottom,20,18).build());
        addRenderableWidget(Button.builder(Component.literal(">"),button->{if(collectMixture()){page=Math.min(Math.max(0,(editSolids?64:phase==3?displayedSolids().populations().size():composition.length)-1)/rows,page+1);showMixture();}}).bounds(leftPos+97,bottom,20,18).build());
        mixButton=addRenderableWidget(Button.builder(Component.literal("Edit mix"),button->{if(collectMixture()){editMixture=!editMixture;showMixture();}}).bounds(leftPos+120,bottom,52,18).build());mixButton.visible=false;
        solidsButton=addRenderableWidget(Button.builder(Component.literal("Solids"),button->{if(collectMixture()){editSolids=!editSolids;editMixture=false;page=0;showMixture();}}).bounds(leftPos+176,bottom,58,18).build());solidsButton.visible=false;
        recoverButton=addRenderableWidget(Button.builder(Component.literal("Recover solids"),button->FluidNetwork.recoverSolids(menu,draftRevision)).bounds(leftPos+176,bottom,94,18).build());recoverButton.visible=false;
        applyButton=addRenderableWidget(Button.builder(Component.literal("Apply"),button->apply()).bounds(leftPos+imageWidth-111,bottom,52,18).build());
        addRenderableWidget(Button.builder(Component.literal("Close"),button->onClose()).bounds(leftPos+imageWidth-56,bottom,48,18).build());
        presetButton=addRenderableWidget(Button.builder(Component.literal("Preset"),button->preset()).bounds(right,topPos+122,fieldWidth,18).build());presetButton.visible=false;
        pathButton=addRenderableWidget(Button.builder(Component.literal("Forward"),button->{var data=menu.clientData();if(data!=null&&!data.view().pipeHistory().isEmpty()){if(reverse){path=(path+1)%data.view().pipeHistory().size();reverse=false;}else reverse=true;}}).bounds(right,topPos+122,fieldWidth,18).build());pathButton.visible=false;
    }
    private void refreshDraft() {
        if(menu.messageRevision()!=lastMessageRevision){lastMessageRevision=menu.messageRevision();localMessage=menu.message();}
        var data=menu.clientData();if(data==null||data.view().inputRevision()==draftRevision)return;draftRevision=data.view().inputRevision();var c=data.controls();composition=c.composition();lastMixtureText=new String[composition.length];page=Math.min(page,Math.max(0,composition.length-1)/rows);
        fields.get("temperature").setValue(Double.toString(c.temperature()));fields.get("pressure").setValue(Double.toString(c.pressure()));fields.get("diameter").setValue(Double.toString(c.diameter()));fields.get("roughness").setValue(Double.toString(c.roughness()));fields.get("volumeFlow").setValue(Double.toString(c.volumeFlow()));fields.get("maximumAddedPressure").setValue(Double.toString(c.maximumAddedPressure()));
        loadParticles(c.solids());if(data.kind()==TopologyCompiler.Kind.FILTER){phase=3;phaseButton.setMessage(Component.literal(phaseName()));}
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
        solidsButton.visible=data.kind()==TopologyCompiler.Kind.GENERATOR&&!menu.debug();recoverButton.visible=data.kind()==TopologyCompiler.Kind.FILTER&&!menu.debug();
        applyButton.active=!menu.debug()&&data.kind()!=TopologyCompiler.Kind.RESERVOIR&&data.kind()!=TopologyCompiler.Kind.FILTER;pathButton.visible=data.kind()==TopologyCompiler.Kind.PIPE||data.kind()==TopologyCompiler.Kind.FILTER;
        preset=-1;for(int i=0;i<data.presets().size();i++)if(Arrays.equals(composition,data.presets().get(i).moleFractions()))preset=i;
        showMixture();
    }
    private void preset() {
        var data=menu.clientData();if(data==null||data.presets().isEmpty())return;preset=(preset+1)%data.presets().size();composition=data.presets().get(preset).moleFractions();loadParticles(data.presets().get(preset).solids());
        localMessage="Composition selected; temperature and pressure unchanged.";showMixture();
    }
    private boolean collectMixture() {
        if(editSolids){collectParticles();return true;}
        if(!editMixture)return true;
        try {
            for(int i=0;i<mixtureFields.size();i++){int c=page*rows+i;if(c>=composition.length)break;String text=mixtureFields.get(i).getValue();if(!text.equals(lastMixtureText[c])){double value=Double.parseDouble(text)/100;if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException();composition[c]=value;lastMixtureText[c]=text;}}
            return true;
        }catch(RuntimeException invalid){localMessage="Composition must use nonnegative percentages.";return false;}
    }
    private void showMixture() {
        for(int i=0;i<mixtureFields.size();i++){int c=page*rows+i;var field=mixtureFields.get(i);field.setVisible(!editSolids&&editMixture&&c<composition.length);if(c<composition.length){lastMixtureText[c]=Double.toString(100*composition[c]);field.setValue(lastMixtureText[c]);}}
        for(int i=0;i<particleFields.size();i++)for(int c=0;c<3;c++){var field=particleFields.get(i)[c];int index=page*rows+i;field.setVisible(editSolids&&index<64);field.setValue(index>=64||particleText[index][c]==null?"":particleText[index][c]);}
        var fraction=fields.get("solidFraction");fraction.setY(topPos+130);fraction.setVisible(editSolids);if(editSolids){labels.put("solidFraction","Solids (volume %)");presetButton.visible=false;}else{labels.remove("solidFraction");presetButton.visible=menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.GENERATOR&&!menu.debug();}
        mixButton.visible=solidsButton.visible&&!editSolids;
        if(mixButton!=null)mixButton.setMessage(Component.literal(editMixture?"Phases":"Edit mix"));
    }
    private void apply() {
        if(!collectMixture()||menu.clientData()==null)return;
        try {
            var controls=new FluidNetwork.Controls(number("temperature"),number("pressure"),number("diameter"),number("roughness"),number("volumeFlow"),number("maximumAddedPressure"),composition,particleFeed());
            FluidNetwork.sendEdit(menu,draftRevision,controls);localMessage="Waiting for server validation...";
        }catch(RuntimeException invalid){localMessage="Not applied: "+invalid.getMessage();}
    }
    private double number(String key){return Double.parseDouble(fields.get(key).getValue());}
    private String phaseName(){return switch(phase){case 0->"Liquid";case 1->"Water";case 3->"Solids";default->"Vapor";};}
    private void loadParticles(SlurryFeed feed){
        particleText=new String[64][3];for(int i=0;i<feed.grades().size();i++){var grade=feed.grades().get(i);particleText[i]=new String[]{grade.material(),new java.math.BigDecimal(grade.size().metres()).scaleByPowerOfTen(6).toPlainString(),Double.toString(grade.massShare())};}
        fields.get("solidFraction").setValue(Double.toString(feed.volumeFraction()*100));
    }
    private void collectParticles(){if(!editSolids)return;for(int i=0;i<particleFields.size();i++){int index=page*rows+i;if(index>=64)break;for(int c=0;c<3;c++)particleText[index][c]=particleFields.get(i)[c].getValue();}}
    private SlurryFeed particleFeed(){
        collectParticles();var grades=new ArrayList<SlurryFeed.Grade>();for(var row:particleText){boolean empty=true;for(var value:row)empty&=value==null||value.isBlank();if(empty)continue;
            if(row[0]==null||row[1]==null||row[2]==null)throw new IllegalArgumentException("Each particle row needs material, size and mass share");
            grades.add(new SlurryFeed.Grade(row[0].strip(),ParticleSize.micrometres(row[1].strip()),Double.parseDouble(row[2])));}
        return new SlurryFeed(number("solidFraction")/100,grades);
    }
    private SolidInventory displayedSolids(){
        var data=menu.clientData();if(data==null)return SolidInventory.EMPTY;var view=data.view();
        if(view.filter()!=null)return view.filter().captured();
        if(data.kind()==TopologyCompiler.Kind.PIPE&&!view.pipeHistory().isEmpty()){var p=view.pipeHistory().get(Math.min(path,view.pipeHistory().size()-1));return (reverse?p.reverse():p.forward()).solids();}
        return view.state()==null?SolidInventory.EMPTY:view.state().solids();
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        hoveredMaterial=null;refreshDraft();super.render(graphics,mouseX,mouseY,partialTick);renderTooltip(graphics,mouseX,mouseY);
        if(hoveredMaterial!=null)graphics.renderTooltip(font,List.of(
                Component.literal(com.wormzjl.createcheme.client.MaterialNames.localized(hoveredMaterial)),
                Component.literal(hoveredMaterial.id())),Optional.empty(),mouseX,mouseY);
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
        if(view.filter()!=null){var cake=view.filter();line(g,"Load  "+format(100*cake.loading())+" %",right,y+58);line(g,"Captured  "+format(cake.captured().massKg())+" kg",right,y+71);line(g,"Capacity  "+format(cake.capacity()*1000)+" L solids",right,y+84);if(view.devicePressureChange()!=null)line(g,"Pressure drop  "+format(Math.abs(view.devicePressureChange())/1000)+" kPa",right,y+97);recoverButton.active=!cake.captured().empty();}
        if(presetButton.visible)presetButton.setMessage(Component.literal(font.plainSubstrByWidth(preset<0?"Preset: custom":data.presets().get(preset).name(),presetButton.getWidth()-8)));
        if(pathButton.visible)pathButton.setMessage(Component.literal((reverse?"Reverse":"Forward")+" / run "+(path+1)));
        if(menu.debug()&&!view.pipeHistory().isEmpty()) {
            long runId=view.pipeHistory().get(Math.min(path,view.pipeHistory().size()-1)).pipeId();
            for(var route:view.pipeRoutes())if(route.pipeId()==runId) {
                line(g,"From "+(reverse?route.second():route.first()),right,y+58);
                line(g,"To   "+(reverse?route.first():route.second()),right,y+71);
            }
        }
        double[][] amounts=view.state()==null?new double[3][data.components().size()]:view.state().phaseMoles();double[] volumes=view.state()==null?new double[3]:view.state().phaseVolumes();
        if(data.kind()==TopologyCompiler.Kind.PIPE&&!view.pipeHistory().isEmpty()) {
            path=Math.min(path,view.pipeHistory().size()-1);var transfer=view.pipeHistory().get(path);var stream=reverse?transfer.reverse():transfer.forward();amounts=stream.phaseMoles();volumes=stream.phaseVolumes();
        }
        volumes=Arrays.copyOf(volumes,4);volumes[3]=displayedSolids().volume();
        double totalVolume=Arrays.stream(volumes).sum();int barWidth=imageWidth/2-20,at=x+10;int[] colors={0xffd6a65a,0xff549bd2,0xffb3c0ca,0xffa28565};
        for(int p=0;p<4;p++){int pixels=totalVolume>0?(int)Math.round(barWidth*volumes[p]/totalVolume):0;g.fill(at,y+123,at+pixels,y+129,colors[p]);at+=pixels;}
        String fraction=totalVolume>0?String.format(Locale.ROOT,"L %.0f W %.0f V %.0f S %.0f %%",100*volumes[0]/totalVolume,100*volumes[1]/totalVolume,100*volumes[2]/totalVolume,100*volumes[3]/totalVolume):"No committed phase sample";
        g.drawString(font,font.plainSubstrByWidth(fraction,imageWidth/2-18),x+10,y+133,0xffb6ced6,false);
        if(editSolids||phase==3&&!editMixture){
            line(g,editSolids?"Material ID                         Size (µm)   Mass share":"Solid material / size / mass",x+10,y+149);
            if(!editSolids){var populations=displayedSolids().populations();for(int i=0;i<rows;i++){int atPopulation=page*rows+i;if(atPopulation>=populations.size())break;var p=populations.get(atPopulation);String text=p.material().id()+" / "+format(p.size().diameterMetres()*1e6)+" µm / "+format(p.massKg())+" kg";g.drawString(font,font.plainSubstrByWidth(text,imageWidth-24),x+12,y+163+i*12,0xffedf5f8,false);}}
            if(!localMessage.isEmpty())g.drawString(font,font.plainSubstrByWidth(localMessage,imageWidth-18),x+9,y+imageHeight-36,0xffefca83,false);
            phaseButton.active=!editSolids;return;
        }
        String heading=editMixture?"Generator composition (mole %)":phaseName()+" composition (mole %)";
        line(g,heading+"   "+(page+1)+"/"+(Math.max(0,composition.length-1)/rows+1),x+10,y+149);
        double sum=Arrays.stream(amounts[phase]).sum();for(int i=0;i<rows;i++) {
            int c=page*rows+i;if(c>=composition.length)break;int rowY=y+163+i*12;String id=data.components().get(c);
            var descriptor=data.materialNames().getOrDefault(id,com.wormzjl.createcheme.science.material.MaterialName.chemical(id));
            String name=com.wormzjl.createcheme.client.MaterialNames.localized(descriptor);
            g.drawString(font,font.plainSubstrByWidth(name,imageWidth-122),x+12,rowY,0xffedf5f8,false);
            if(mouseX>=x+12&&mouseX<x+imageWidth-110&&mouseY>=rowY&&mouseY<rowY+12)hoveredMaterial=descriptor;
            if(!editMixture)g.drawString(font,sum>0?format(100*amounts[phase][c]/sum)+" %":"Absent",x+imageWidth-94,rowY,0xffb6ced6,false);
        }
        if(!localMessage.isEmpty())g.drawString(font,font.plainSubstrByWidth(localMessage,imageWidth-18),x+9,y+imageHeight-36,0xffefca83,false);
        phaseButton.active=!editMixture;
    }
    private void line(GuiGraphics g,String text,int x,int y){g.drawString(font,text,x,y,0xffedf5f8,false);}
    private static String format(double value){if(Math.abs(value)<1e-10)return "0.00";return String.format(Locale.ROOT,Math.abs(value)<.01?"%.3g":"%.2f",value);}
}
