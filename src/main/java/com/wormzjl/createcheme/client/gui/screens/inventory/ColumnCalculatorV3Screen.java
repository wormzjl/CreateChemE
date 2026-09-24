package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.client.MaterialNames;
import com.wormzjl.createcheme.client.gui.common.ProcessUi;
import com.wormzjl.createcheme.client.gui.common.TemperatureUnit;
import static com.wormzjl.createcheme.client.gui.common.ProcessUi.*;
import com.wormzjl.createcheme.network.ColumnV3Network;
import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.science.material.MaterialName;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3State;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Status;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/** Editable process drawing; accepted results are discarded on any physical draft edit. */
public final class ColumnCalculatorV3Screen extends AbstractContainerScreen<ColumnCalculatorV3Menu> {
    private static final int CONTENT=78;
    private static final String[] PAGES={"overview","composition","results","diagnostics"},
        RESULTS={"profiles","streams","heat"},METRICS={"temperature","pressure","pressure_drop","traffic"};
    private static String tr(String key,Object...args){return Component.translatable("gui.createcheme.column."+key,args).getString();}
    private final List<Label> labels=new ArrayList<>();
    private final List<Hit> hits=new ArrayList<>();
    private final Map<String,EditBox> editors=new LinkedHashMap<>();
    private final List<Scroll> scrolls=new ArrayList<>();
    private final Scroll canvasY=new Scroll(),canvasX=new Scroll(),listScroll=new Scroll(),railScroll=new Scroll(),solverScroll=new Scroll();
    private Scroll dragging;
    private V3State state;
    private V3EditorDraft draft;
    private V3ColumnInput candidate;
    private String validation=tr("waiting"),rejection="",notice="",hoveredText,search="",presetName="";
    private V3ClientPresets library;
    private V3ClientPresets.Kind presetKind;
    private V3ClientPresets.Snapshot presetSnapshot;
    private boolean savingPreset,massRate,connectionMenu;
    private long pendingNonce;
    private boolean conflict,rebuilding,invalidated,awaitingResult,kelvin,picker,infoOnly,needsRebuild;
    private int page,results,offset,selectedNode=1,selectedStream,metric,lineCount,pointerX,pointerY,selectedDraw=-1,selectedPA=-1,selectedSteam=-1;
    private Button solve;
    private record Label(String text,int x,int y,int width){}
    private record Hit(int x,int y,int w,int h,Runnable action,String hint){}
    private static final class Scroll {
        int x,y,length,visible,total,value;boolean horizontal;
        int max(){return Math.max(0,total-visible);}
        int thumb(){return Math.min(length,Math.max(18,total==0?length:length*visible/total));}
        int position(){return max()==0?0:(length-thumb())*value/max();}
        boolean contains(int px,int py){return horizontal?px>=x&&px<x+length&&py>=y&&py<y+8:px>=x&&px<x+8&&py>=y&&py<y+length;}
        void seek(int px,int py){int at=(horizontal?px-x:py-y)-thumb()/2;value=Math.clamp((int)Math.round((double)at*max()/Math.max(1,length-thumb())),0,max());}
    }
    public ColumnCalculatorV3Screen(ColumnCalculatorV3Menu menu,Inventory inventory,Component title){
        super(menu,inventory,title);imageWidth=840;imageHeight=460;
    }
    @Override protected void init(){
        imageWidth=Math.max(180,width-16);imageHeight=Math.max(140,height-16);
        super.init();titleLabelY=inventoryLabelY=-1000;
        if(library==null)library=new V3ClientPresets(minecraft.gameDirectory.toPath(),name->{
            try{return minecraft.getResourceManager().getResource(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createcheme",name.substring("assets/createcheme/".length()))).orElseThrow().open();}
            catch(java.io.IOException|NoSuchElementException e){return null;}
        });
        ColumnV3Network.setClientStateConsumer(this::receive);
        ColumnV3Network.setClientRejectionConsumer((pos,nonce,reason)->{
            if(!menu.blockPos().equals(pos)||nonce!=pendingNonce)return;
            pendingNonce=0;awaitingResult=false;rejection=tr("request_rejected");validate();rebuild();
        });
        ColumnV3Network.sendStateRequest(menu.blockPos());rebuild();
    }
    private V3EditorDraft newDraft(V3ColumnInput input){var next=new V3EditorDraft(input,state.editorCatalog().weightsFor(input));if(massRate)next.composition().toggleBasis();return next;}
    private void receive(net.minecraft.core.BlockPos pos,V3State incoming){
        if(!menu.blockPos().equals(pos)||state!=null&&incoming.stateRevision()<state.stateRevision())return;
        boolean own=pendingNonce!=0&&incoming.clientNonce()==pendingNonce;
        boolean replace=draft==null||own||!draft.dirty()&&!invalidated;
        boolean changed=draft==null||!draft.base().equals(incoming.input());
        if(!replace&&state!=null&&!state.input().equals(incoming.input()))conflict=true;
        state=incoming;
        if(own){pendingNonce=0;rejection="";conflict=false;}
        if(replace&&(changed||own))draft=newDraft(incoming.input());
        if(awaitingResult&&pendingNonce==0&&incoming.status()!=V3Status.CALCULATING){
            awaitingResult=false;invalidated=false;
        }
        validate();rebuild();
    }
    @Override public void onClose(){
        ColumnV3Network.setClientStateConsumer((pos,view)->{});
        ColumnV3Network.setClientRejectionConsumer((pos,nonce,reason)->{});super.onClose();
    }
    private boolean busy(){return pendingNonce!=0||state!=null&&state.status()==V3Status.CALCULATING;}
    private boolean editable(){return !busy()&&draft!=null;}
    private void edited(){invalidated=true;rejection="";notice="";validate();}
    private void validate(){
        candidate=null;
        if(draft==null){validation=tr("waiting");return;}
        try{candidate=draft.assemble();validation=invalidated?tr("draft_changed"):tr("ready");}
        catch(IllegalArgumentException e){validation=validationMessage(e);}
        updateControls();
    }
    private void updateControls(){
        if(solve!=null)solve.active=candidate!=null&&!busy()&&!conflict&&state!=null;
        editors.forEach((key,e)->e.active=key.equals("search")||key.equals("presetName")||editable());
    }
    private void submit(){
        validate();if(candidate==null||busy()||conflict||state==null)return;
        pendingNonce=ColumnV3Network.sendCalculate(menu.blockPos(),state.inputRevision(),candidate);
        invalidated=true;awaitingResult=true;rejection="";rebuild();
    }
    private void changePage(int target){page=target;connectionMenu=false;offset=listScroll.value=0;picker=false;presetKind=null;savingPreset=false;rebuild();}
    private int bodyBottom(){return imageHeight-(conflict?73:48);}
    private int rows(){return Math.max(1,(bodyBottom()-CONTENT-10)/15);}
    private boolean narrow(){return imageWidth<710;}
    private int solverWidth(){return 152;}
    private int trayWidth(){return Math.max(185,Math.min(260,imageWidth/3));}
    private int canvasWidth(){return narrow()?imageWidth-30:imageWidth-solverWidth()-trayWidth()-38;}
    private int solverX(){return canvasWidth()+22;}
    private int trayX(){return solverX()+solverWidth()+12;}
    private int virtualWidth(){return Math.max(330,canvasWidth());}
    private int cx(){return virtualWidth()/2;}
    private int pitch(){return V3DiagramLayout.trayPitch(stageCount(),bodyBottom()-CONTENT-12);}
    private int trayY(int tray){return 52+tray*pitch();}
    private int columnBottom(){return trayY(stageCount()+1)+12;}
    private int canvasHeight(){return Math.max(columnBottom()+67,trayY(feedTray())+78);}
    private int stageCount(){return draft==null?2:draft.preview("s2",draft.base().stageCount()+2,2,66)-2;}
    private int feedTray(){return draft.preview("s3",draft.base().feedStageNumber()+1,2,stageCount()+2)-1;}
    private int sx(int x){return 10+x-canvasX.value;}
    private int sy(int y){return CONTENT+y-canvasY.value;}
    private Button button(String text,int x,int y,int w,Runnable action){
        var b=addRenderableWidget(Button.builder(Component.literal(text),v->action.run())
            .bounds(leftPos+x,topPos+y,Math.max(20,w),18).build());
        b.setTooltip(Tooltip.create(Component.literal(text)));return b;
    }
    private Button action(String key,int x,int y,int w,Runnable action){
        var b=button(tr(key),x,y,w,action);b.setTooltip(Tooltip.create(Component.literal(tr(key+".hint"))));return b;
    }
    private void rateButton(int x,int y,int w){
        button(tr(massRate?"mass_rate":"molar_rate"),x,y,w,()->{toggleBasis();})
            .setTooltip(Tooltip.create(Component.literal(tr("flow_basis.hint"))));
    }
    private void toggleBasis(){
        try{if(draft!=null){draft.composition().toggleBasis();massRate=draft.composition().mass();}else massRate=!massRate;rejection="";rebuild();}
        catch(IllegalArgumentException e){rejection=validationMessage(e);}
    }
    private String validationMessage(IllegalArgumentException e){
        String message=Objects.toString(e.getMessage(),"").toLowerCase(Locale.ROOT);
        if(message.contains("steam"))return tr("invalid_steam");
        if(message.contains("draw"))return tr("invalid_draw");
        if(message.contains("component")||message.contains("mixture"))return tr("invalid_mixture");
        if(message.contains("heat")||message.contains("cool")||message.contains("pumparound"))return tr("invalid_pa");
        return tr("invalid_input");
    }
    private void rebuild(){
        if(rebuilding)return;rebuilding=true;needsRebuild=false;
        String focus=editors.entrySet().stream().filter(e->e.getValue()==getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        int cursor=focus==null?0:editors.get(focus).getCursorPosition();
        clearWidgets();editors.clear();labels.clear();scrolls.clear();
        int tab=(imageWidth-20)/4;
        for(int i=0;i<4;i++){int t=i;action(PAGES[i],10+i*tab,28,tab-3,()->changePage(t)).active=page!=i;}
        solve=action("solve",10,imageHeight-25,64,this::submit);
        button(tr("temperature_unit",unit()),imageWidth-130,imageHeight-25,120,()->{kelvin=!kelvin;rebuild();})
            .setTooltip(Tooltip.create(Component.literal(tr("temperature_unit.hint"))));
        if(conflict){
            action("use_server",10,imageHeight-69,120,()->{draft=newDraft(state.input());conflict=false;invalidated=false;validate();rebuild();});
            action("keep_draft",136,imageHeight-69,115,()->{conflict=false;validate();rebuild();});
        }
        if(presetKind!=null&&draft!=null)buildPresets();
        else if(page==0&&draft!=null){
            action("column_templates",10,54,100,()->openPresets(V3ClientPresets.Kind.COLUMN));
            action("save_column",116,54,86,()->openSave(V3ClientPresets.Kind.COLUMN));
            if(narrow())button(tr(infoOnly?"drawing":"information"),436,54,105,()->{infoOnly=!infoOnly;rebuild();});
            action("connections",208,54,112,()->{connectionMenu=!connectionMenu;rebuild();});
            action("mixture_templates",326,54,104,()->openPresets(V3ClientPresets.Kind.MIXTURE));
            if(!narrow()||!infoOnly){if(connectionMenu)buildConnections();else buildDrawing();}
        }else if(page==1&&draft!=null)buildComposition();
        else if(page==2){
            int w=(imageWidth-20)/3;
            for(int i=0;i<3;i++){int t=i;action(RESULTS[i],10+i*w,54,w-3,()->{results=t;offset=listScroll.value=0;rebuild();}).active=results!=i;}
            if(results==0)button(tr(METRICS[metric],unit()),10,CONTENT,175,()->{metric=(metric+1)%4;rebuild();})
                .setTooltip(Tooltip.create(Component.literal(tr("profile_metric.hint"))));
            if(results==1&&inspection()!=null){
                var streams=state.displayResult().orElseThrow().streams();
                if(!streams.isEmpty()){
                    selectedStream=Math.clamp(selectedStream,0,streams.size()-1);
                    button(streamName(streams.get(selectedStream)),10,CONTENT,imageWidth-160,()->{selectedStream=(selectedStream+1)%streams.size();offset=listScroll.value=0;rebuild();})
                        .setTooltip(Tooltip.create(Component.literal(tr("next_product.hint"))));
                    rateButton(imageWidth-142,CONTENT,128);
                }
            }
        }
        if(page==3&&presetKind==null)action("copy_report",10,54,130,()->{
            if(state!=null)minecraft.keyboardHandler.setClipboard(String.join("\n",state.diagnostics())+"\n"+state.displayResult().map(Object::toString).orElse(""));
            notice=tr("report_copied");rejection="";
        });
        if(focus!=null&&editors.containsKey(focus)){setFocused(editors.get(focus));editors.get(focus).setCursorPosition(cursor);}
        rebuilding=false;updateControls();
    }
    @Override protected void containerTick(){super.containerTick();if(needsRebuild)rebuild();}
    private void field(String name,String key,int x,int y,int w){
        labels.add(new Label(name,x,y,w));
        var e=new EditBox(font,leftPos+x,topPos+y+11,w,18,Component.literal(name));
        e.setMaxLength(32);e.setValue(draft.display(key,kelvin));
        e.setResponder(v->{draft.setDisplay(key,v,kelvin);edited();if(key.equals("s2")||key.equals("s3")||key.endsWith("stage")||key.endsWith("draw")||key.endsWith("return"))needsRebuild=true;});
        e.setTooltip(Tooltip.create(Component.literal(name)));editors.put(key,addRenderableWidget(e));
    }
    private void canvasField(String label,String key,int x,int y,int w){
        int px=sx(x),py=sy(y);
        if(px>=10&&px+w<=10+canvasWidth()&&py>=CONTENT&&py+30<bodyBottom()-12){field(tr(label,unit()),key,px,py,w);editors.get(key).setTooltip(Tooltip.create(Component.literal(tr(label+".hint"))));}
    }
    private void canvasButton(String key,int x,int y,int w,Runnable run,boolean enabled){
        int px=sx(x),py=sy(y);
        if(px>=10&&px+w<=10+canvasWidth()&&py>=CONTENT&&py+18<bodyBottom()-12)
            action(key,px,py,w,run).active=editable()&&enabled;
    }
    private void configure(Scroll s,int x,int y,int length,int visible,int total,boolean horizontal){
        s.x=x;s.y=y;s.length=Math.max(20,length);s.visible=visible;s.total=total;s.horizontal=horizontal;s.value=Math.clamp(s.value,0,s.max());
        if(!scrolls.contains(s))scrolls.add(s);
    }
    private void buildConnections(){
        action("add_draw",10,CONTENT+26,240,()->{selectedDraw=draft.addDraw(Math.clamp(selectedNode+1,2,Math.max(2,stageCount()+1)));selectedPA=selectedSteam=-1;connectionMenu=false;edited();rebuild();}).active=editable()&&
            stageCount()>0&&java.util.stream.IntStream.range(0,3).anyMatch(i->draft.get("d"+i+"stage").isBlank());
        action("add_pa",10,CONTENT+51,240,()->{selectedPA=draft.addPumparound(Math.clamp(selectedNode+1,3,Math.max(3,stageCount()+1)));selectedDraw=selectedSteam=-1;connectionMenu=false;edited();rebuild();}).active=editable()&&
            stageCount()>1&&java.util.stream.IntStream.range(0,4).anyMatch(i->draft.get("c"+i+"draw").isBlank());
        action("add_steam",10,CONTENT+76,240,()->{selectedSteam=draft.addSteam(Math.clamp(selectedNode+1,2,stageCount()+2));selectedDraw=selectedPA=-1;connectionMenu=false;edited();rebuild();}).active=editable()&&
            draft.canAddSteam();
    }
    private void buildDrawing(){
        int view=bodyBottom()-CONTENT-12;
        configure(canvasY,10+canvasWidth()+2,CONTENT,view,view,canvasHeight(),false);
        configure(canvasX,10,bodyBottom()-10,canvasWidth(),canvasWidth(),virtualWidth(),true);
        int left=5,right=cx()+34;
        canvasField("tray_count","s2",left,0,58);
        canvasField("diameter","s9",left+65,0,72);
        canvasField("top_temperature","s4",right,0,82);
        canvasField("reflux","s6",right+88,0,65);
        canvasField("top_pressure","s7",right,32,62);
        int fy=trayY(feedTray());
        canvasField("feed_flow","s0",left,fy+12,76);
        canvasField("feed_temperature","s1",left,fy+46,65);
        canvasField("feed_tray","s3",left+72,fy+46,50);
        canvasField("duty","s5",right,columnBottom()+6,112);
        int location=selectedDraw>=0?draft.preview("d"+selectedDraw+"stage",2,2,stageCount()+2)-1:
            selectedPA>=0?draft.preview("c"+selectedPA+"draw",3,2,stageCount()+2)-1:
            selectedSteam>=0?draft.preview("t"+selectedSteam+"stage",stageCount()+2,2,stageCount()+2)-1:1;
        int ey=Math.max(69,Math.min(trayY(location)-12,columnBottom()-100));
        if(selectedDraw>=0){
            int i=selectedDraw;String k="d"+i;
            canvasField("draw_flow",k+"rate",right,ey,70);canvasField("draw_tray",k+"stage",right+76,ey,50);
            canvasButton("remove_draw",right,ey+34,90,()->{draft.removeDraw(i);selectedDraw=-1;edited();rebuild();},true);
        }else if(selectedPA>=0){
            int i=selectedPA;String k="c"+i;
            canvasField("cooling",k+"duty",right,ey,72);
            canvasField("draw_tray",k+"draw",right,ey+34,58);canvasField("return_tray",k+"return",right+65,ey+34,75);
            canvasButton(draft.get(k+"split").equals("UNIFORM")?"uniform_heat":"return_heat",right,ey+70,124,()->{draft.set(k+"split",draft.get(k+"split").equals("UNIFORM")?"RETURN_TRAY":"UNIFORM");edited();rebuild();},true);
            canvasButton("remove_pa",right,ey+91,90,()->{draft.removePumparound(i);selectedPA=-1;edited();rebuild();},true);
        }else if(selectedSteam>=0){
            int i=selectedSteam;String k="t"+i;
            canvasField("steam_flow",k+"rate",right,ey,82);canvasField("injection_tray",k+"stage",right+88,ey,38);
            canvasField("steam_temperature",k+"T",right,ey+34,70);
            canvasButton("remove_steam",right,ey+69,106,()->{draft.removeSteam(i);selectedSteam=-1;edited();rebuild();},true);
        }
    }
    private String unit(){return temperatureUnit().symbol();}
    private TemperatureUnit temperatureUnit(){return kelvin?TemperatureUnit.KELVIN:TemperatureUnit.CELSIUS;}
    private double temperature(double k){return temperatureUnit().display(k);}
    private void openPresets(V3ClientPresets.Kind kind){
        rejection="";notice="";presetKind=kind;savingPreset=false;presetSnapshot=library.refresh(kind);listScroll.value=0;rebuild();
    }
    private void openSave(V3ClientPresets.Kind kind){presetKind=kind;savingPreset=true;presetName="";rebuild();}
    private void buildPresets(){
        action("back",10,54,65,()->{presetKind=null;savingPreset=false;rebuild();});
        if(savingPreset){
            labels.add(new Label(tr("preset_filename"),10,CONTENT,imageWidth-30));
            var box=new EditBox(font,leftPos+10,topPos+CONTENT+18,Math.min(230,imageWidth-35),18,Component.literal(tr("preset_filename")));
            box.setMaxLength(64);box.setValue(presetName);box.setResponder(v->presetName=v);editors.put("presetName",addRenderableWidget(box));
            action("save_json",10,CONTENT+43,100,()->{
                try{
                    String json=presetKind==V3ClientPresets.Kind.COLUMN?V3ClientPresets.columnJson(presetName,draft.assembleOperating()):V3ClientPresets.mixtureJson(presetName,draft.composition());
                    var path=library.save(presetKind,presetName,json);notice=tr("saved",path.getFileName().toString());rejection="";presetKind=null;savingPreset=false;rebuild();
                }catch(java.io.IOException|IllegalArgumentException e){rejection=tr("save_failed");}
            });
            labels.add(new Label(tr("preset_filename_hint"),10,CONTENT+70,imageWidth-30));return;
        }
        action("refresh",82,54,84,()->{rejection="";notice="";presetSnapshot=library.refresh(presetKind);listScroll.value=0;rebuild();});
        labels.add(new Label(tr(presetKind==V3ClientPresets.Kind.COLUMN?"column_library":"mixture_library"),10,CONTENT,imageWidth-25));
        action("open_folder",172,54,92,()->net.minecraft.Util.getPlatform().openPath(library.directory(presetKind)));
        if(presetSnapshot==null)return;
        int count=Math.max(1,(bodyBottom()-CONTENT-35)/23);
        configure(listScroll,imageWidth-18,CONTENT+20,bodyBottom()-CONTENT-32,count,presetSnapshot.entries().size(),false);
        offset=listScroll.value;
        for(int i=offset;i<Math.min(presetSnapshot.entries().size(),offset+count);i++){
            var entry=presetSnapshot.entries().get(i);var preset=entry.preset();
            String name=preset.translationKey().isBlank()?preset.name():Component.translatable(preset.translationKey()).getString();
            button(name+"  "+tr(entry.bundled()?"bundled":"custom"),10,CONTENT+20+(i-offset)*23,imageWidth-40,()->loadPreset(preset))
                .setTooltip(Tooltip.create(Component.literal(tr(entry.file().equals("holland_3_2.json")?"benchmark_hint":presetKind==V3ClientPresets.Kind.COLUMN?"column_templates.hint":"mixture_templates.hint"))));
        }
        if(!presetSnapshot.problems().isEmpty())rejection=tr("preset_errors",presetSnapshot.problems().size(),presetSnapshot.problems().getFirst().file());
    }
    private void loadPreset(V3ClientPresets.Preset preset){
        if(busy())return;
        try{
            if(preset instanceof V3ClientPresets.Column column){
                var replacement=newDraft(draft.base());replacement.loadColumn(column.fields());replacement.assembleOperating();
                draft.loadColumn(column.fields());
            }else if(preset instanceof V3ClientPresets.Mixture mixture){
                var source=state.editorCatalog().templates().values().stream().filter(i->i.packageId().equals(mixture.packageId())).findFirst().orElseThrow();
                var comp=new V3CompositionDraft(source,state.editorCatalog().weightsFor(source));comp.load(mixture.amounts(),mixture.mass());
                draft.composition(source,state.editorCatalog().weightsFor(source));draft.composition().load(mixture.amounts(),mixture.mass());massRate=mixture.mass();
            }
            selectedDraw=selectedPA=selectedSteam=-1;connectionMenu=false;presetKind=null;listScroll.value=0;edited();rebuild();
        }catch(IllegalArgumentException|NoSuchElementException e){rejection=tr("preset_incompatible");}
    }
    private void buildComposition(){
        action("mixture_templates",10,54,108,()->openPresets(V3ClientPresets.Kind.MIXTURE));
        action("save_mixture",124,54,94,()->openSave(V3ClientPresets.Kind.MIXTURE));
        action("clear_mixture",224,54,72,()->{draft.composition().clear();picker=false;edited();rebuild();}).active=editable();
        button(tr(draft.composition().mass()?"mass_percent":"mole_percent"),imageWidth<570?166:302,imageWidth<570?CONTENT:54,85,()->{
            toggleBasis();
        }).setTooltip(Tooltip.create(Component.literal(tr("composition_basis.hint"))));
        action(picker?"close_search":"add_component",10,CONTENT,150,()->{picker=!picker;search="";offset=listScroll.value=0;rebuild();}).active=editable();
        if(picker){
            var e=new EditBox(font,leftPos+10,topPos+CONTENT+25,imageWidth-38,18,Component.literal(tr("search")));
            e.setValue(search);e.setHint(Component.literal(tr("search")));e.setResponder(v->{search=v;listScroll.value=0;needsRebuild=true;});editors.put("search",addRenderableWidget(e));
            var ids=searchComponents();int count=Math.max(1,(bodyBottom()-CONTENT-67)/22);
            offset=Math.clamp(listScroll.value,0,Math.max(0,ids.size()-count));
            for(int i=offset;i<Math.min(ids.size(),offset+count);i++){
                int id=ids.get(i);button(material(draft.composition().source().componentBasis().componentId(id)),10,CONTENT+50+(i-offset)*22,imageWidth-38,
                    ()->{if(!editable())return;draft.composition().add(id);picker=false;offset=listScroll.value=0;edited();rebuild();}).setTooltip(Tooltip.create(Component.literal(tr("add_component.hint"))));
            }
            configure(listScroll,imageWidth-18,CONTENT+50,bodyBottom()-CONTENT-63,count,ids.size(),false);return;
        }
        var ids=draft.composition().rows();int count=Math.max(1,(bodyBottom()-CONTENT-66)/25);
        offset=Math.clamp(listScroll.value,0,Math.max(0,ids.size()-count));
        int amount=amountX();
        for(int i=offset;i<Math.min(ids.size(),offset+count);i++){
            int id=ids.get(i),y=CONTENT+51+(i-offset)*25;
            var e=new EditBox(font,leftPos+amount,topPos+y,58,18,Component.literal(tr("relative_amount")));
            e.setMaxLength(24);e.setValue(draft.composition().get(id));e.setTooltip(Tooltip.create(Component.literal(tr("relative_amount.hint"))));
            e.setResponder(v->{draft.composition().set(id,v);edited();});editors.put("comp"+id,addRenderableWidget(e));
            button("x",imageWidth-47,y,22,()->{if(!editable())return;draft.composition().remove(id);edited();rebuild();}).setTooltip(Tooltip.create(Component.literal(tr("remove_component.hint"))));
        }
        configure(listScroll,imageWidth-18,CONTENT+51,bodyBottom()-CONTENT-63,count,ids.size(),false);
    }
    private int amountX(){return Math.max(120,imageWidth-310);}
    private List<Integer> searchComponents(){
        var basis=draft.composition().source().componentBasis();List<Integer> ids=new ArrayList<>();
        for(int i=0;i<basis.componentCount();i++)if(!draft.composition().contains(i)&&(material(basis.componentId(i))+" "+basis.componentId(i)).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))ids.add(i);
        return ids;
    }
    private V3ColumnInspection inspection(){return invalidated||conflict||state==null?null:state.displayResult().flatMap(V3ColumnDisplayResult::inspection).orElse(null);}
    private String freshness(){return inspection()!=null?tr("accepted"):invalidated?tr("draft_cleared"):tr("no_result");}
    private String status(){
        if(conflict)return tr("conflict");
        if(!rejection.isBlank())return rejection;
        if(!notice.isBlank())return notice;
        if(busy())return tr("solving");
        if(!invalidated&&state!=null&&state.status()==V3Status.FAILED)return tr("failed");
        return validation;
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        ProcessUi.restoreCursor(minecraft,this);
        hoveredText=null;pointerX=mouseX-leftPos;pointerY=mouseY-topPos;
        super.render(g,mouseX,mouseY,partialTick);
        if(hoveredText!=null)g.renderTooltip(font,Component.literal(hoveredText),mouseX,mouseY);
    }
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY){
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,BG);g.renderOutline(leftPos,topPos,imageWidth,imageHeight,LINE);
    }
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        hits.clear();text(g,tr("title",tr(PAGES[page])),10,10,TEXT,imageWidth/2);
        text(g,freshness(),imageWidth/2,10,inspection()!=null?ACCENT:WARN,imageWidth/2-60);
        g.hLine(8,imageWidth-8,CONTENT-3,LINE);
        g.enableScissor(leftPos+8,topPos+CONTENT-2,leftPos+imageWidth-8,topPos+Math.max(CONTENT,bodyBottom()-12));
        if(presetKind!=null){ /* Preset widgets and labels render below. */ }
        else if(page==0)overview(g);
        else if(page==1)composition(g);
        else if(page==2){if(results==0)profile(g);else if(results==1)streams(g);else heat(g);}
        else diagnostics(g);
        for(var l:labels)text(g,l.text,l.x,l.y,MUTED,l.width);
        g.disableScissor();
        for(var s:scrolls)drawScroll(g,s);
        for(var hit:hits)if(pointerX>=hit.x&&pointerX<hit.x+hit.w&&pointerY>=hit.y&&pointerY<hit.y+hit.h){
            g.renderOutline(hit.x,hit.y,hit.w,hit.h,ACCENT);hoveredText=hit.hint;
        }
        g.hLine(8,imageWidth-8,imageHeight-47,LINE);
        text(g,status(),10,imageHeight-40,candidate==null||!rejection.isBlank()?WARN:notice.isBlank()?MUTED:ACCENT,imageWidth-20);
        text(g,tr(page==1?"relative_hint":"drawing_hint"),85,imageHeight-20,MUTED,imageWidth-225);
    }
    private void drawScroll(GuiGraphics g,Scroll s){
        if(s.max()==0)return;
        g.fill(s.x,s.y,s.x+(s.horizontal?s.length:8),s.y+(s.horizontal?8:s.length),PANEL);
        int p=s.position();g.fill(s.x+(s.horizontal?p:1),s.y+(s.horizontal?1:p),
            s.x+(s.horizontal?p+s.thumb():7),s.y+(s.horizontal?7:p+s.thumb()),dragging==s?ACCENT:LINE);
    }
    private void hit(int x,int y,int w,int h,Runnable action,String hint){
        if(y>=CONTENT&&y+h<=bodyBottom()-12&&x>=10&&x+w<=imageWidth-20)hits.add(new Hit(x,y,w,h,action,hint));
    }
    private void overview(GuiGraphics g){
        if(draft==null){text(g,tr("waiting"),10,CONTENT,MUTED,imageWidth-20);return;}
        selectedNode=Math.clamp(selectedNode,0,stageCount()+1);
        if(!narrow()||!infoOnly){if(connectionMenu)text(g,tr("connections.hint"),10,CONTENT,MUTED,canvasWidth());else diagram(g);}
        if(!narrow()){solverInfo(g,solverX(),solverWidth());inspect(g,trayX(),trayWidth());}
        else if(infoOnly){int w=(imageWidth-42)/2;solverInfo(g,10,w);inspect(g,w+28,w);}
    }
    private void diagram(GuiGraphics g){
        int x=sx(cx()),top=sy(trayY(0)-12),bottom=sy(columnBottom()),n=stageCount();
        g.enableScissor(leftPos+10,topPos+CONTENT,leftPos+10+canvasWidth(),topPos+bodyBottom()-12);
        g.fill(x-24,top+10,x+25,bottom-10,PANEL);
        for(int dy=0;dy<10;dy++){int inset=10-(int)Math.sqrt(100-(10-dy)*(10-dy));
            g.hLine(x-24+inset,x+24-inset,top+dy,LINE);g.hLine(x-24+inset,x+24-inset,bottom-dy,LINE);}
        g.vLine(x-25,top+10,bottom-10,LINE);g.vLine(x+25,top+10,bottom-10,LINE);
        var view=inspection();double min=view==null?0:view.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).min().orElse(0);
        double max=view==null?1:view.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).max().orElse(1);
        for(int node=0;node<n+2;node++){
            int y=sy(trayY(node)),at=node;
            int color=view==null?MUTED:temperatureColor((view.nodes().get(node).temperatureKelvin()-min)/Math.max(1e-9,max-min));
            g.hLine(x-20,x+20,y,color);
            if(node==selectedNode)g.renderOutline(x-23,y-2,47,5,TEXT);
            if(node==0||node==n+1||(node+1)%5==0&&node<=n-2){
                int labelY=node==0?y-9:node==n+1?y+2:y-4;
                g.fill(x-8,labelY-1,x+14,labelY+9,PANEL);text(g,Integer.toString(node+1),x-7,labelY,MUTED,20);
            }
            int hitY=node==0?top:y-Math.max(1,pitch()/2);
            int hitHeight=node==0?y+Math.max(1,pitch()/2)-top:node==n+1?bottom-hitY:Math.max(3,pitch());
            hit(x-24,hitY,49,hitHeight,()->{selectedNode=at;railScroll.value=0;rebuild();},tr(node==0?"inspect_top":node==n+1?"inspect_bottom":"inspect_tray",node+1));
        }
        int fy=sy(trayY(feedTray()));
        g.hLine(sx(5),x-26,fy,ACCENT);text(g,tr("feed_icon"),sx(5),fy-11,ACCENT,125);
        hit(sx(5),fy-13,Math.min(125,font.width(tr("feed_icon"))+8),13,()->changePage(1),tr("feed_icon.hint"));
        for(int i=0;i<3;i++)if(!draft.get("d"+i+"stage").isBlank()){
            int at=draft.preview("d"+i+"stage",2,2,n+2)-1,y=sy(trayY(at)),id=i;
            g.hLine(x+26,x+39,y,ACCENT);text(g,tr("draw_icon",i+1),x+27,y-9,ACCENT,26);
            hit(x+26,y-11,28,13,()->{selectedDraw=id;selectedPA=selectedSteam=-1;rebuild();},tr("draw_icon.hint",i+1,at+1));
        }
        for(int i=0;i<4;i++)if(!draft.get("c"+i+"draw").isBlank()){
            int a=sy(trayY(draft.preview("c"+i+"draw",3,2,n+2)-1)),b=sy(trayY(draft.preview("c"+i+"return",2,2,n+2)-1));
            int lane=x-34-i*7,mid=(a+b)/2,id=i;
            g.vLine(lane,Math.min(a,b),Math.max(a,b),WARN);g.hLine(lane,x-26,a,WARN);g.hLine(lane,x-26,b,WARN);
            text(g,tr("pa_icon",i+1),lane-24,mid-4,WARN,24);
            hit(lane-25,mid-6,29,13,()->{selectedPA=id;selectedDraw=selectedSteam=-1;rebuild();},tr("pa_icon.hint",i+1));
        }
        for(int i=0;i<2;i++)if(!draft.get("t"+i+"stage").isBlank()){
            int at=draft.preview("t"+i+"stage",n+2,2,n+2)-1,y=sy(trayY(at)),id=i;
            int start=x-82-i*8;
            for(int dx=start;dx<x-26;dx+=5)g.hLine(dx,Math.min(dx+2,x-26),y,WARN);
            text(g,tr("steam_icon",i+1),start-22,y-10,WARN,26);
            hit(start-24,y-12,32,14,()->{selectedSteam=id;selectedDraw=selectedPA=-1;rebuild();},tr("steam_icon.hint",i+1,at+1));
        }
        g.disableScissor();
    }
    private void solverInfo(GuiGraphics g,int x,int w){
        List<String> lines=new ArrayList<>();lines.add(tr("solver_info"));var view=inspection();
        if(view==null){
            lines.add(tr(invalidated?"not_solved":busy()?"solving_short":state!=null&&state.status()==V3Status.FAILED?"failure_short":"no_result"));
            if(!invalidated&&state!=null&&state.status()==V3Status.FAILED){
                String raw=String.join(" ",state.diagnostics()).toLowerCase(Locale.ROOT);
                var draw=java.util.regex.Pattern.compile("side draw on authored tray ([0-9]+) requests ([0-9.eE+-]+) kmol/h; final internal liquid ([0-9.eE+-]+)").matcher(raw);
                if(draw.find()){
                    wrap(lines,tr("draw_balance",Integer.parseInt(draw.group(1)),draw.group(2),draw.group(3)),w);
                    if(Double.parseDouble(draw.group(2))>=Double.parseDouble(draw.group(3)))wrap(lines,tr("liquid_depletion"),w);
                }else if(raw.contains("exhaust")||raw.contains("deplet"))wrap(lines,tr("liquid_depletion"),w);
                else if(raw.contains("pressure"))wrap(lines,tr("pressure_failure"),w);
                else wrap(lines,tr("solver_failed_hint"),w);
            }
        }else{
            List<String> wet=new ArrayList<>();
            for(int i=1;i<=view.input().stageCount();i++)if(view.nodes().get(i).freeWaterMolPerSecond()>0)wet.add(Integer.toString(i+1));
            wrap(lines,tr("wet_trays",wet.isEmpty()?tr("none"):String.join(", ",wet)),w);
            lines.add(tr("column_dp",fmt(totalDrop(view))));
            state.displayResult().orElseThrow().trayHydraulics().ifPresent(h->{
                wrap(lines,tr(h.floods()?"flooding":"below_flood",h.maximumFloodTray()+1),w);
                lines.add(tr("worst_flood",fmt(h.maximumFloodFraction()*100)));
                if(!h.correctionApplied()&&h.residualMismatchFraction()>0.01)wrap(lines,tr("uncorrected_pressure"),w);
            });
            boolean dew=view.audit().advisoryEvidence().stream().anyMatch(a->a.contains("dew point")||a.contains("dew-point"));
            if(dew)wrap(lines,tr("dew_warning"),w);
            lines.add(tr("iterations",state.displayResult().orElseThrow().newtonIterations()));
        }
        panelLines(g,lines,x,w,solverScroll);
    }
    private void panelLines(GuiGraphics g,List<String> lines,int x,int w,Scroll scroll){
        int visible=Math.max(1,(bodyBottom()-CONTENT-12)/14);
        configure(scroll,x+w+2,CONTENT,bodyBottom()-CONTENT-12,visible,lines.size(),false);
        for(int i=scroll.value;i<Math.min(lines.size(),scroll.value+visible);i++)text(g,lines.get(i),x,CONTENT+(i-scroll.value)*14,i==0?ACCENT:MUTED,w);
    }
    private void inspect(GuiGraphics g,int x,int w){
        List<String> lines=new ArrayList<>();lines.add(tr("tray_info"));lines.add(nodeName(selectedNode,stageCount()));var view=inspection();
        if(view!=null){
            var node=view.nodes().get(selectedNode);
            lines.add(tr("tray_tp",fmt(temperature(node.temperatureKelvin())),unit(),fmt(node.pressurePascal()/1000)));
            lines.add(tr("tray_dp",selectedNode>0&&selectedNode<=stageCount()?fmt(drop(view,selectedNode)):"—"));
            lines.add(tr("hc_liquid",fmt(node.liquidMolPerSecond()*3.6)));
            lines.add(tr("hc_vapor",fmt(node.vaporMolPerSecond()*3.6)));
            lines.add(tr("water_vapor",fmt(node.waterVaporMolPerSecond()*3.6)));
            lines.add(tr("free_water",fmt(node.freeWaterMolPerSecond()*3.6)));
        }else lines.add(tr("solve_to_inspect"));
        int visible=Math.max(1,(bodyBottom()-CONTENT-12)/14);
        configure(railScroll,x+w+2,CONTENT,bodyBottom()-CONTENT-12,visible,lines.size()+(view==null?0:view.input().componentBasis().componentCount()+1),false);
        for(int i=railScroll.value;i<Math.min(lines.size(),railScroll.value+visible);i++)text(g,lines.get(i),x,CONTENT+(i-railScroll.value)*14,i==0?ACCENT:MUTED,w);
        if(view!=null){
            var node=view.nodes().get(selectedNode);
            List<String[]> data=new ArrayList<>();data.add(new String[]{tr("component"),tr("liquid_percent"),tr("vapor_percent")});
            for(int c=0;c<view.input().componentBasis().componentCount();c++)data.add(new String[]{
                material(view.input().componentBasis().componentId(c)),node.liquidMolPerSecond()==0?"—":percent(node.liquidFractions().get(c)*100),
                node.vaporMolPerSecond()==0?"—":percent(node.vaporFractions().get(c)*100)});
            for(int i=0;i<data.size();i++){int y=CONTENT+(lines.size()+i-railScroll.value)*14;if(y>=CONTENT&&y+14<bodyBottom()-12)tableRow(g,data.get(i),x,y,w,i==0);}
        }
    }
    private void composition(GuiGraphics g){
        if(draft==null)return;
        if(picker){if(searchComponents().isEmpty())text(g,tr("no_matches"),10,CONTENT+53,MUTED,imageWidth-30);return;}
        int amount=amountX(),norm=amount+74,flow=amount+156;
        text(g,tr("component"),10,CONTENT+34,TEXT,amount-18);text(g,tr("relative_amount"),amount,CONTENT+34,TEXT,68);
        text(g,tr(draft.composition().mass()?"mass_percent":"mole_percent"),norm,CONTENT+34,TEXT,76);
        text(g,tr(massRate?"mass_rate":"molar_rate"),flow,CONTENT+34,TEXT,95);
        double[] fractions=null;double total=Double.NaN;
        try{fractions=draft.composition().displayFractions();total=Arrays.stream(draft.assembleOperating().feedComponentMolarFlowsMolPerSecond()).sum();}catch(IllegalArgumentException ignored){}
        var ids=draft.composition().rows();int count=Math.max(1,(bodyBottom()-CONTENT-66)/25);
        for(int i=offset;i<Math.min(ids.size(),offset+count);i++){
            int id=ids.get(i),y=CONTENT+51+(i-offset)*25;g.hLine(10,imageWidth-25,y+22,LINE);
            text(g,material(draft.composition().source().componentBasis().componentId(id)),12,y+5,MUTED,amount-20);
            text(g,fractions==null?"—":percent(fractions[id]*100),norm,y+5,TEXT,74);
            text(g,fractions==null||!Double.isFinite(total)?"—":percent(draft.composition().componentFlow(id,total,massRate)),flow,y+5,TEXT,90);
        }
        if(ids.isEmpty())text(g,tr("empty_mixture"),10,CONTENT+58,MUTED,imageWidth-35);
    }
    private void tableRow(GuiGraphics g,String[] cells,int x,int y,int w,boolean header){
        ProcessUi.tableRow(g,font,cells,x,y,w,header);
    }
    private static String percent(double value){return value!=0&&Math.abs(value)<0.05?String.format(Locale.ROOT,"%.2g",value):fmt(value);}
    private void wrap(List<String> lines,String value,int width){
        while(font.width(value)>width){String s=font.plainSubstrByWidth(value,width);if(s.isEmpty())break;int space=s.lastIndexOf(32);if(space>s.length()/2)s=s.substring(0,space);lines.add(s);value=value.substring(s.length()).stripLeading();}
        lines.add(value);
    }
    private void profile(GuiGraphics g){
        if(metric==2){pressureDrop(g);return;}
        var view=inspection();
        if(view==null){text(g,tr("solve_profiles"),10,CONTENT+27,MUTED,imageWidth-20);return;}
        int x=53,y=CONTENT+39,w=imageWidth-76,h=bodyBottom()-y-42;
        if(h<12)return;
        int series=metric==3?4:1;double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var node:view.nodes())for(int s=0;s<series;s++){
            double v=profileValue(node,s);if(Double.isFinite(v)){min=Math.min(min,v);max=Math.max(max,v);}}
        if(!Double.isFinite(min)){text(g,tr("absent_phase"),10,y,WARN,w);return;}
        if (max-min < Math.max(1,Math.abs(max))*1e-9) {
            double padding=Math.max(0.01,Math.abs(max)*0.01);
            min-=padding;max+=padding;
        }
        double span=max-min;
        g.fill(x,y,x+w,y+h,PANEL);g.renderOutline(x,y,w+1,h+1,LINE);
        int[] colors={ACCENT,WARN,0xFF91B5FF,0xFFD5AFF1};
        for(int n=0;n<view.nodes().size();n++){
            int py=y+n*h/(view.nodes().size()-1);
            if(n==0||n==view.nodes().size()-1||n%5==0&&n<view.nodes().size()-2){
                text(g,Integer.toString(n+1),22,py-4,MUTED,25);g.hLine(x,x+w,py,LINE);}}
        for(int s=0;s<series;s++){
            int px0=0,py0=0;boolean previous=false;
            for(int n=0;n<view.nodes().size();n++){
                double value=profileValue(view.nodes().get(n),s);
                if(!Double.isFinite(value)){previous=false;continue;}
                int px=x+(int)Math.round((value-min)/span*w),py=y+n*h/(view.nodes().size()-1);
                if(previous)line(g,px0,py0,px,py,colors[s]);px0=px;py0=py;previous=true;}}
        text(g,fmt(min),x,y+h+5,MUTED,w/3);
        text(g,fmt((min+max)/2),x+w/2-font.width(fmt((min+max)/2))/2,y+h+5,MUTED,w/3);
        text(g,fmt(max),x+w-font.width(fmt(max)),y+h+5,MUTED,w/3);
        if(metric==3)text(g,tr("traffic_legend"),10,CONTENT+25,MUTED,imageWidth-20);
        hit(x,y,w,h,()->{selectedNode=Math.clamp((pointerY-y)*(stageCount()+1)/h,0,stageCount()+1);page=0;infoOnly=true;railScroll.value=0;rebuild();},tr("profile_click.hint"));
    }
    private double profileValue(V3ColumnInspection.Node node,int series){
        return switch(metric){
            case 0->temperature(node.temperatureKelvin());
            case 1->node.pressurePascal()/1000;
            case 3->switch(series){case 0->node.liquidMolPerSecond()*3.6;case 1->node.vaporMolPerSecond()*3.6;
                case 2->node.waterVaporMolPerSecond()*3.6;default->node.freeWaterMolPerSecond()*3.6;};
            default->throw new IllegalStateException("Unknown profile");};
    }
    private void streams(GuiGraphics g){
        if(inspection()==null){text(g,tr("solve_products"),10,CONTENT+27,MUTED,imageWidth-25);return;}
        var products=state.displayResult().orElseThrow().streams();if(products.isEmpty())return;
        var stream=products.get(Math.clamp(selectedStream,0,products.size()-1));
        text(g,tr("product_tp",fmt(massRate?stream.massFlowKgPerSecond()*3600:stream.molarFlowMolPerSecond()*3.6),massRate?"kg/h":"kmol/h",fmt(temperature(stream.temperatureKelvin())),unit(),fmt(stream.pressurePascal()/1000)),10,CONTENT+26,MUTED,imageWidth-30);
        List<String[]> data=new ArrayList<>();
        for(var c:stream.moleFractions())data.add(new String[]{material(c.componentId()),percent(c.moleFraction()*100),percent(c.massFraction()*100),percent(massRate?stream.massFlowKgPerSecond()*c.massFraction()*3600:stream.molarFlowMolPerSecond()*c.moleFraction()*3.6)});
        table(g,new String[]{tr("component"),tr("mole_percent"),tr("mass_percent"),tr(massRate?"mass_rate":"molar_rate")},data,CONTENT+46);
    }
    private void table(GuiGraphics g,String[] headers,List<String[]> data,int y){
        int visible=Math.max(1,(bodyBottom()-y-32)/17);
        configure(listScroll,imageWidth-18,y+18,bodyBottom()-y-30,visible,data.size(),false);
        offset=listScroll.value;tableRow(g,headers,10,y,imageWidth-36,true);
        for(int i=offset;i<Math.min(data.size(),offset+visible);i++)tableRow(g,data.get(i),10,y+18+(i-offset)*17,imageWidth-36,false);
    }
    private static double drop(V3ColumnInspection view,int tray){
        if(tray<=1||tray>view.input().stageCount())return 0;
        return (view.nodes().get(tray).pressurePascal()-view.nodes().get(tray-1).pressurePascal())/1000;
    }
    private static double totalDrop(V3ColumnInspection view){int n=view.input().stageCount();return n<2?0:(view.nodes().get(n).pressurePascal()-view.nodes().get(1).pressurePascal())/1000;}
    private String streamName(V3ColumnStreamProperties stream){
        if(stream.streamId().startsWith("side_liquid_tray_"))return tr("stream.side_draw",Integer.parseInt(stream.streamId().substring("side_liquid_tray_".length()))+1);
        return tr("stream."+stream.streamId());
    }
    private void pressureDrop(GuiGraphics g){
        var view=inspection();
        if(view==null){text(g,tr("solve_profiles"),10,CONTENT,MUTED,imageWidth-30);return;}
        int n=view.input().stageCount(),x=60,y=CONTENT+58,w=imageWidth-92,h=bodyBottom()-y-48;
        double total=totalDrop(view),max=0;
        for(int i=2;i<=n;i++)max=Math.max(max,drop(view,i));max=Math.max(max,0.001);
        text(g,tr("column_dp",fmt(total)),10,CONTENT+25,TEXT,imageWidth-30);
        text(g,tr("terminal_no_dp"),10,CONTENT+40,MUTED,imageWidth-30);
        if(h<16)return;
        g.fill(x,y,x+w,y+h,PANEL);
        for(int i=2;i<=n;i++){
            int py=y+(i-2)*h/(n-1),next=y+(i-1)*h/(n-1),tray=i;
            int bar=(int)Math.round(drop(view,i)/max*w);
            g.fill(x,py,x+bar,Math.max(py+1,next-1),ACCENT);
            if(i==2||i==n||i%5==0)text(g,Integer.toString(i+1),20,py,MUTED,35);
            hit(x,py,w,Math.max(1,next-py),()->{selectedNode=tray;page=0;infoOnly=true;railScroll.value=0;rebuild();},tr("dp_click.hint",i+1,fmt(drop(view,i))));
        }
        text(g,"0",x,y+h+5,MUTED,30);text(g,fmt(max)+" kPa",x+w-85,y+h+5,MUTED,85);
    }
    private void heat(GuiGraphics g){
        List<String> lines=new ArrayList<>();lines.add(tr("signed_heat"));
        if(inspection()!=null)state.displayResult().ifPresent(result->result.dutyLedger().ifPresent(d->{
            lines.add(tr("tray_duty",1,fmt(d.condenserWatts()/1e6)));lines.add(tr("tray_duty",stageCount()+2,fmt(d.reboilerWatts()/1e6)));
            lines.add(tr("stage_heat",fmt(d.stageHeatTotalWatts()/1e6)));
            lines.add(tr("feed_enthalpy",fmt(d.feedEnthalpyWatts()/1e6)));lines.add(tr("steam_enthalpy",fmt(d.steamEnthalpyWatts()/1e6)));
            for(var stage:d.stageDuties())lines.add(tr("tray_duty",stage.trayNumber()+1,fmt(stage.dutyWatts()/1e6)));
        }));
        drawLines(g,lines,10,CONTENT,imageWidth-20);
    }
    private void diagnostics(GuiGraphics g){
        List<String> lines=new ArrayList<>();lines.add(tr("diagnostic_status",state==null?tr("waiting"):tr("status."+state.status().serializedName().toLowerCase(Locale.ROOT))));
        if(!rejection.isBlank())wrap(lines,rejection,imageWidth-35);
        if(!invalidated&&state!=null)state.displayResult().ifPresent(r->{
            lines.add(tr("iterations",r.newtonIterations()));lines.add(tr("residual",fraction(r.maximumScaledResidual())));
            lines.add(tr("closure",fraction(r.closureTolerance())));
            r.inspection().ifPresent(v->{for(var check:v.audit().checks())lines.add(tr("audit_value",check.family(),fraction(check.value()),fraction(check.limit())));});
            lines.add(tr("dataset",r.datasetRevision()));lines.add(tr("formulation",r.formulationRevision()));lines.add(tr("digest",r.inputDigest()));
        });
        if(state!=null&&state.status()==V3Status.FAILED)wrap(lines,tr("solver_failed_hint"),imageWidth-35);
        drawLines(g,lines,10,CONTENT,imageWidth-20);
    }
    private void drawLines(GuiGraphics g,List<String> lines,int x,int y,int w){
        lineCount=lines.size();
        configure(listScroll,imageWidth-18,CONTENT,bodyBottom()-CONTENT-12,rows(),lines.size(),false);
        offset=listScroll.value;
        for(int i=offset;i<Math.min(lines.size(),offset+rows());i++)text(g,lines.get(i),x,y+(i-offset)*15,i==0?TEXT:MUTED,w-12);
    }
    private String material(String id){return state==null?id:MaterialNames.localized(state.materialNames().getOrDefault(id,MaterialName.chemical(id)));}
    private void text(GuiGraphics g,String value,int x,int y,int color,int max){
        if(max<=0)return;
        String shown=font.width(value)>max?font.plainSubstrByWidth(value,Math.max(1,max-8))+"...":value;
        g.drawString(font,shown,x,y,color,false);
        if (!shown.equals(value) && pointerX >= x && pointerX < x + max && pointerY >= y && pointerY < y + 10)
            hoveredText = value;
    }
    private static String fmt(double value){return String.format(Locale.ROOT,"%.1f",value);}
    private static String fraction(double value){return String.format(Locale.ROOT,"%.5g",value);}
    private static String nodeName(int node,int stages){return tr(node==0?"top_tray":node==stages+1?"bottom_tray":"tray",node+1);}
    private static int temperatureColor(double t){
        t=Math.clamp(t,0,1);return 0xFF000000|((int)(77+173*t)<<16)|((int)(161+30*t)<<8)|(int)(211-123*t);}
    private static void line(GuiGraphics g,int x0,int y0,int x1,int y1,int color){
        int steps=Math.max(Math.abs(x1-x0),Math.abs(y1-y0));
        for(int i=0;i<=steps;i++){double f=steps==0?0:(double)i/steps;
            int x=(int)Math.round(x0+(x1-x0)*f),y=(int)Math.round(y0+(y1-y0)*f);g.fill(x,y,x+1,y+1,color);}}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        int px=(int)x-leftPos,py=(int)y-topPos;
        if(py>=CONTENT&&py<bodyBottom()&&!(page==2&&results==0&&presetKind==null)){
            Scroll target=presetKind!=null||page!=0?listScroll:!narrow()?(px>=trayX()?railScroll:px>=solverX()?solverScroll:canvasY):infoOnly?(px>imageWidth/2?railScroll:solverScroll):canvasY;
            if(page==0&&!infoOnly&&horizontal!=0)target=canvasX;
            target.value=Math.clamp(target.value+(int)Math.signum(horizontal!=0?horizontal:-vertical)*(target==canvasY||target==canvasX?30:3),0,target.max());
            offset=listScroll.value;rebuild();return true;
        }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=0)return super.mouseClicked(x,y,button);
        int px=(int)x-leftPos,py=(int)y-topPos;
        for(var s:scrolls)if(s.max()>0&&s.contains(px,py)){
            dragging=s;setDragging(true);s.seek(px,py);offset=listScroll.value;rebuild();return true;
        }
        // Native fields win where a connection label and a widget share the viewport.
        for(var e:editors.values())if(e.isMouseOver(x,y))return super.mouseClicked(x,y,button);
        for(var h:hits)if(px>=h.x&&px<h.x+h.w&&py>=h.y&&py<h.y+h.h){h.action.run();return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==0&&dragging!=null){dragging.seek((int)x-leftPos,(int)y-topPos);offset=listScroll.value;rebuild();return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        if(dragging!=null){dragging=null;setDragging(false);return true;}return super.mouseReleased(x,y,button);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(page==0&&!(getFocused() instanceof EditBox)){
            if(key==GLFW.GLFW_KEY_UP||key==GLFW.GLFW_KEY_DOWN){
                selectedNode=Math.clamp(selectedNode+(key==GLFW.GLFW_KEY_UP?-1:1),0,stageCount()+1);railScroll.value=0;rebuild();return true;
            }
        }
        return super.keyPressed(key,scan,modifiers);
    }
}
