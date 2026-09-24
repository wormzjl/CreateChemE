package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.client.MaterialNames;
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
    private static final int BG=0xFF202A31,PANEL=0xFF28363F,LINE=0xFF536C7A,TEXT=0xFFEAF1F5,
        MUTED=0xFFA9BCC7,ACCENT=0xFF79D6CE,WARN=0xFFFFCD82,CONTENT=84;
    private static final String[] PAGES={"Overview","Composition","Results","Diagnostics"},
        RESULTS={"Profiles","Streams","Heat","Column dP"},
        METRICS={"Temperature","Pressure (kPa)","Phase traffic (kmol/h)"};
    private final List<Label> labels=new ArrayList<>();
    private final List<Hit> hits=new ArrayList<>();
    private final Map<String,EditBox> editors=new LinkedHashMap<>();
    private final List<Scroll> scrolls=new ArrayList<>();
    private final Scroll canvasY=new Scroll(),canvasX=new Scroll(),listScroll=new Scroll(),railScroll=new Scroll();
    private Scroll dragging;
    private V3State state;
    private V3EditorDraft draft;
    private V3ColumnInput candidate;
    private String validation="Waiting for the engine",rejection="",hoveredText,search="",presetChoice;
    private long pendingNonce;
    private boolean conflict,rebuilding,invalidated,awaitingResult,kelvin,presets,picker,infoOnly,needsRebuild;
    private int page,results,offset,selectedNode=1,selectedStream,metric,lineCount,pointerX,pointerY,selectedDraw=-1,selectedPA=-1;
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
        ColumnV3Network.setClientStateConsumer(this::receive);
        ColumnV3Network.setClientRejectionConsumer((pos,nonce,reason)->{
            if(!menu.blockPos().equals(pos)||nonce!=pendingNonce)return;
            pendingNonce=0;awaitingResult=false;rejection=reason;validate();rebuild();
        });
        ColumnV3Network.sendStateRequest(menu.blockPos());rebuild();
    }
    private V3EditorDraft newDraft(V3ColumnInput input){return new V3EditorDraft(input,state.editorCatalog().weightsFor(input));}
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
    private boolean editable(){return !busy()&&draft!=null&&!V3HollandExample32.isPackage(draft.base().packageId());}
    private void edited(){invalidated=true;rejection="";validate();}
    private void validate(){
        candidate=null;
        if(draft==null){validation="Waiting for the engine";return;}
        try{candidate=draft.assemble();validation=invalidated?"Draft changed; solve to calculate results":"Ready";}
        catch(IllegalArgumentException e){validation=e.getMessage()==null?"Check the inputs":e.getMessage();}
        updateControls();
    }
    private void updateControls(){
        if(solve!=null)solve.active=candidate!=null&&!busy()&&!conflict&&state!=null;
        editors.forEach((key,e)->e.active=key.equals("search")||editable());
    }
    private void submit(){
        validate();if(candidate==null||busy()||conflict||state==null)return;
        pendingNonce=ColumnV3Network.sendCalculate(menu.blockPos(),state.inputRevision(),candidate);
        invalidated=true;awaitingResult=true;rejection="";rebuild();
    }
    private void changePage(int target){page=target;offset=listScroll.value=0;presets=picker=false;presetChoice=null;rebuild();}
    private int bodyBottom(){return imageHeight-(conflict?73:48);}
    private int rows(){return Math.max(1,(bodyBottom()-CONTENT-10)/15);}
    private boolean narrow(){return imageWidth<780;}
    private int canvasWidth(){return narrow()?imageWidth-30:imageWidth-264;}
    private int virtualWidth(){return Math.max(560,canvasWidth());}
    private int cx(){return virtualWidth()/2;}
    private int pitch(){return V3DiagramLayout.trayPitch(stageCount(),bodyBottom()-CONTENT-12);}
    private int trayY(int tray){return 92+(tray-1)*pitch();}
    private int columnBottom(){return trayY(stageCount())+24;}
    private int canvasHeight(){return columnBottom()+175;}
    private int stageCount(){return draft==null?2:draft.preview("s2",draft.base().stageCount(),2,64);}
    private int feedTray(){return draft.preview("s3",draft.base().feedStageNumber(),1,stageCount());}
    private int sx(int x){return 10+x-canvasX.value;}
    private int sy(int y){return CONTENT+y-canvasY.value;}
    private Button button(String text,int x,int y,int w,Runnable action){
        var b=addRenderableWidget(Button.builder(Component.literal(text),v->action.run())
            .bounds(leftPos+x,topPos+y,Math.max(20,w),18).build());
        b.setTooltip(Tooltip.create(Component.literal(text)));return b;
    }
    private void rebuild(){
        if(rebuilding)return;rebuilding=true;needsRebuild=false;
        String focus=editors.entrySet().stream().filter(e->e.getValue()==getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        int cursor=focus==null?0:editors.get(focus).getCursorPosition();
        clearWidgets();editors.clear();labels.clear();scrolls.clear();
        int tab=(imageWidth-20)/4;
        for(int i=0;i<4;i++){int t=i;button(PAGES[i],10+i*tab,28,tab-3,()->changePage(t)).active=page!=i;}
        solve=button("Solve",10,imageHeight-25,64,this::submit);
        button(kelvin?"K":"C",imageWidth-51,5,40,()->{kelvin=!kelvin;rebuild();});
        if(conflict){
            button("Use server input",10,imageHeight-69,120,()->{draft=newDraft(state.input());conflict=false;invalidated=false;validate();rebuild();});
            button("Keep my draft",136,imageHeight-69,115,()->{conflict=false;validate();rebuild();});
        }
        if(page==0&&draft!=null){
            button("Templates",10,54,95,()->{changePage(1);presets=true;rebuild();});
            if(narrow())button(infoOnly?"Drawing":"Tray / solver info",112,54,130,()->{infoOnly=!infoOnly;rebuild();});
            if(!narrow()||!infoOnly)buildDrawing();
        }else if(page==1&&draft!=null)buildComposition();
        else if(page==2){
            int w=(imageWidth-20)/4;
            for(int i=0;i<4;i++){int t=i;button(RESULTS[i],10+i*w,54,w-3,()->{results=t;offset=listScroll.value=0;rebuild();}).active=results!=i;}
            if(results==0)button(METRICS[metric]+(metric==0?" ("+unit()+")":""),10,CONTENT,180,()->{metric=(metric+1)%3;rebuild();});
            if(results==1&&inspection()!=null){
                var streams=state.displayResult().orElseThrow().streams();
                if(!streams.isEmpty()){
                    selectedStream=Math.clamp(selectedStream,0,streams.size()-1);
                    button(streams.get(selectedStream).displayName()+" > ",10,CONTENT,imageWidth-30,()->{selectedStream=(selectedStream+1)%streams.size();offset=listScroll.value=0;rebuild();});
                }
            }
        }
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
        if(px>=10&&px+w<=10+canvasWidth()&&py>=CONTENT&&py+30<bodyBottom()-12)field(label,key,px,py,w);
    }
    private void canvasButton(String label,int x,int y,int w,Runnable action){
        int px=sx(x),py=sy(y);
        boolean capacity=!label.equals("+ Draw")||java.util.stream.IntStream.range(0,3).anyMatch(i->draft.get("d"+i+"stage").isBlank());
        capacity&=!label.equals("+ Pumparound")||java.util.stream.IntStream.range(0,V3ColumnInput.MAX_PUMPAROUNDS).anyMatch(i->draft.get("c"+i+"draw").isBlank());
        if(px>=10&&px+w<=10+canvasWidth()&&py>=CONTENT&&py+18<bodyBottom()-12)
            button(label,px,py,w,action).active=editable()&&capacity;
    }
    private void configure(Scroll s,int x,int y,int length,int visible,int total,boolean horizontal){
        s.x=x;s.y=y;s.length=Math.max(20,length);s.visible=visible;s.total=total;s.horizontal=horizontal;s.value=Math.clamp(s.value,0,s.max());
        if(!scrolls.contains(s))scrolls.add(s);
    }
    private void buildDrawing(){
        int view=bodyBottom()-CONTENT-12;
        configure(canvasY,10+canvasWidth()+2,CONTENT,view,view,canvasHeight(),false);
        configure(canvasX,10,bodyBottom()-10,canvasWidth(),canvasWidth(),virtualWidth(),true);
        int left=8,right=cx()+68,rw=virtualWidth()-right-12;
        canvasField("Number of trays","s2",left,4,100);
        canvasField("Diameter (m)","s9",left+110,4,100);
        canvasField("Condenser T ("+unit()+")","s4",right,4,rw);
        canvasField("Reflux ratio","s6",right,38,rw/2-3);
        canvasField("Top P (bar)","s7",right+rw/2+3,38,rw/2-3);
        int fy=trayY(feedTray());
        int steamY=Math.max(columnBottom()+20,fy+88);
        canvasField("Flowrate (kmol/h)","s0",left,fy+12,184);
        canvasField("Temperature ("+unit()+")","s1",left,fy+46,112);
        canvasField("Feed tray","s3",left+122,fy+46,62);
        canvasField("Reboiler duty (MW)","s5",right,columnBottom()+14,rw);
        canvasField("Sump steam (kmol/h)","sumpRate",right,columnBottom()+51,rw/2-3);
        canvasField("Steam T ("+unit()+")","sumpT",right+rw/2+3,columnBottom()+51,rw/2-3);
        canvasField("Steam tray","steamStage",left,steamY,65);
        canvasField("kmol/h","steamRate",left+72,steamY,65);
        canvasField("T ("+unit()+")","steamT",left+144,steamY,65);
        canvasButton("+ Draw",left,steamY+44,92,()->{selectedDraw=draft.addDraw(Math.clamp(selectedNode,1,stageCount()));selectedPA=-1;edited();rebuild();});
        canvasButton("+ Pumparound",left+98,steamY+44,112,()->{selectedPA=draft.addPumparound(Math.clamp(selectedNode,2,stageCount()));selectedDraw=-1;edited();rebuild();});
        int location=selectedDraw>=0?draft.preview("d"+selectedDraw+"stage",1,1,stageCount()):selectedPA>=0?draft.preview("c"+selectedPA+"draw",2,1,stageCount()):1;
        int ey=Math.max(88,Math.min(trayY(location)-10,columnBottom()-130));
        if(selectedDraw>=0){
            int i=selectedDraw;String k="d"+i;
            canvasField("Draw "+(i+1)+" (kmol/h)",k+"rate",right,ey,rw);
            canvasField("Draw tray",k+"stage",right,ey+34,rw);
            canvasButton("Remove draw",right,ey+70,rw,()->{draft.removeDraw(i);selectedDraw=-1;edited();rebuild();});
        }else if(selectedPA>=0){
            int i=selectedPA;String k="c"+i;
            canvasField("PA "+(i+1)+" cooling (MW)",k+"duty",right,ey,rw);
            canvasField("Draw tray",k+"draw",right,ey+34,rw/2-3);
            canvasField("Return tray",k+"return",right+rw/2+3,ey+34,rw/2-3);
            canvasButton(draft.get(k+"split").equals("UNIFORM")?"Heat: uniform":"Heat: return tray",right,ey+72,rw,()->{draft.set(k+"split",draft.get(k+"split").equals("UNIFORM")?"RETURN_TRAY":"UNIFORM");edited();rebuild();});
            canvasButton("Remove PA",right,ey+95,rw,()->{draft.removePumparound(i);selectedPA=-1;edited();rebuild();});
        }
    }
    private String unit(){return kelvin?"K":"C";}
    private double temperature(double k){return kelvin?k:k-273.15;}
    private void buildComposition(){
        button("Templates",10,54,95,()->{presets=!presets;picker=false;offset=listScroll.value=0;rebuild();});
        button("Custom / clear",112,54,110,()->{draft.composition().clear();presets=picker=false;edited();rebuild();}).active=editable();
        button(draft.composition().mass()?"Mass %":"Molar %",228,54,85,()->{
            try{draft.composition().toggleBasis();rejection="";rebuild();}catch(IllegalArgumentException e){rejection=e.getMessage();}
        }).active=editable();
        if(presetChoice!=null){
            labels.add(new Label("Load the column settings with this composition?",10,CONTENT,imageWidth-30));
            button("Composition only",10,CONTENT+30,145,()->applyPreset(false));
            button("Composition + column",10,CONTENT+54,185,()->applyPreset(true));
            button("Cancel",10,CONTENT+80,80,()->{presetChoice=null;rebuild();});return;
        }
        if(presets){
            int count=Math.max(1,(bodyBottom()-CONTENT-15)/24);
            var available=state.presets();
            offset=Math.clamp(listScroll.value,0,Math.max(0,available.size()-count));
            for(int i=offset;i<Math.min(available.size(),offset+count);i++){
                var preset=available.get(i);
                button(Component.translatableWithFallback(preset.translationKey(),preset.label()).getString(),10,CONTENT+(i-offset)*24,imageWidth-38,
                    ()->{presetChoice=preset.id();rebuild();}).active=!busy();
            }
            configure(listScroll,imageWidth-18,CONTENT,bodyBottom()-CONTENT-12,count,available.size(),false);return;
        }
        button(picker?"Close component search":"+ Add component",10,CONTENT,190,()->{picker=!picker;search="";offset=listScroll.value=0;rebuild();}).active=editable();
        if(picker){
            var e=new EditBox(font,leftPos+10,topPos+CONTENT+25,imageWidth-38,18,Component.literal("Search components"));
            e.setValue(search);e.setHint(Component.literal("Search by component name"));
            e.setResponder(v->{search=v;listScroll.value=0;needsRebuild=true;});editors.put("search",addRenderableWidget(e));
            var ids=searchComponents();int count=Math.max(1,(bodyBottom()-CONTENT-67)/22);
            offset=Math.clamp(listScroll.value,0,Math.max(0,ids.size()-count));
            for(int i=offset;i<Math.min(ids.size(),offset+count);i++){
                int id=ids.get(i);button(material(draft.composition().source().componentBasis().componentId(id)),10,CONTENT+50+(i-offset)*22,imageWidth-38,
                    ()->{draft.composition().add(id);picker=false;offset=listScroll.value=0;edited();rebuild();}).active=editable();
            }
            configure(listScroll,imageWidth-18,CONTENT+50,bodyBottom()-CONTENT-63,count,ids.size(),false);return;
        }
        var ids=draft.composition().rows();int count=Math.max(1,(bodyBottom()-CONTENT-66)/25);
        offset=Math.clamp(listScroll.value,0,Math.max(0,ids.size()-count));
        int amount=Math.max(120,imageWidth/2),norm=imageWidth-140;
        for(int i=offset;i<Math.min(ids.size(),offset+count);i++){
            int id=ids.get(i),y=CONTENT+51+(i-offset)*25;
            var e=new EditBox(font,leftPos+amount,topPos+y,Math.max(42,norm-amount-8),18,Component.literal("Relative amount"));
            e.setMaxLength(24);e.setValue(draft.composition().get(id));
            e.setResponder(v->{draft.composition().set(id,v);edited();});editors.put("comp"+id,addRenderableWidget(e));
            button("x",imageWidth-47,y,22,()->{draft.composition().remove(id);edited();rebuild();}).active=editable();
        }
        configure(listScroll,imageWidth-18,CONTENT+51,bodyBottom()-CONTENT-63,count,ids.size(),false);
    }
    private List<Integer> searchComponents(){
        var basis=draft.composition().source().componentBasis();List<Integer> ids=new ArrayList<>();
        for(int i=0;i<basis.componentCount();i++)if(!draft.composition().contains(i)&&(material(basis.componentId(i))+" "+basis.componentId(i)).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))ids.add(i);
        return ids;
    }
    private void applyPreset(boolean column){
        var input=state.editorCatalog().templates().get(presetChoice);
        if(input==null){rejection="This template is unavailable";return;}
        if(!column&&(V3HollandExample32.isPackage(input.packageId())||V3HollandExample32.isPackage(draft.base().packageId()))){
            rejection="The Holland benchmark requires its matching column settings";return;
        }
        if(column)draft=newDraft(input);else draft.composition(input,state.editorCatalog().weightsFor(input));
        selectedDraw=selectedPA=-1;presetChoice=null;presets=false;offset=listScroll.value=0;edited();rebuild();
    }
    private V3ColumnInspection inspection(){return invalidated||conflict||state==null?null:state.displayResult().flatMap(V3ColumnDisplayResult::inspection).orElse(null);}
    private String freshness(){return inspection()!=null?"Accepted result":invalidated?"Draft / results cleared":"No accepted result";}
    private String status(){
        if(conflict)return "Server inputs changed; choose which draft to use";
        if(!rejection.isBlank())return rejection;
        if(busy())return "Solving; awaiting engine update";
        if(!invalidated&&state!=null&&state.status()==V3Status.FAILED)return "Solve failed; see solver info and Diagnostics";
        return validation;
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        hoveredText=null;pointerX=mouseX-leftPos;pointerY=mouseY-topPos;
        super.render(g,mouseX,mouseY,partialTick);
        if(hoveredText!=null)g.renderTooltip(font,Component.literal(hoveredText),mouseX,mouseY);
    }
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY){
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,BG);g.renderOutline(leftPos,topPos,imageWidth,imageHeight,LINE);
    }
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        hits.clear();text(g,"COLUMN / "+PAGES[page],10,10,TEXT,imageWidth/2);
        text(g,freshness(),imageWidth/2,10,inspection()!=null?ACCENT:WARN,imageWidth/2-60);
        g.hLine(8,imageWidth-8,77,LINE);
        g.enableScissor(leftPos+8,topPos+CONTENT-2,leftPos+imageWidth-8,topPos+Math.max(CONTENT,bodyBottom()-12));
        if(page==0)overview(g);
        else if(page==1)composition(g);
        else if(page==2){if(results==0)profile(g);else if(results==1)streams(g);else if(results==2)heat(g);else pressureDrop(g);}
        else diagnostics(g);
        for(var l:labels)text(g,l.text,l.x,l.y,MUTED,l.width);
        g.disableScissor();
        for(var s:scrolls)drawScroll(g,s);
        for(var hit:hits)if(pointerX>=hit.x&&pointerX<hit.x+hit.w&&pointerY>=hit.y&&pointerY<hit.y+hit.h){
            g.renderOutline(hit.x,hit.y,hit.w,hit.h,ACCENT);hoveredText=hit.hint;
        }
        g.hLine(8,imageWidth-8,imageHeight-47,LINE);
        text(g,status(),10,imageHeight-40,candidate==null||!rejection.isBlank()?WARN:MUTED,imageWidth-20);
        text(g,page==1?"Amounts are relative; they do not need to total 100%":"Select a tray, feed or connection on the drawing",85,imageHeight-20,MUTED,imageWidth-95);
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
        if(draft==null){text(g,"Waiting for the column configuration",10,CONTENT,MUTED,imageWidth-20);return;}
        selectedNode=Math.clamp(selectedNode,0,stageCount()+1);
        if(!narrow()||!infoOnly)diagram(g);
        if(!narrow()||infoOnly)inspect(g,narrow()?10:imageWidth-240,narrow()?imageWidth-35:218);
    }
    private void diagram(GuiGraphics g){
        int x=sx(cx()),top=sy(68),bottom=sy(columnBottom()),n=stageCount();
        g.enableScissor(leftPos+10,topPos+CONTENT,leftPos+10+canvasWidth(),topPos+bodyBottom()-12);
        g.fill(x-38,top+18,x+39,bottom-18,PANEL);
        for(int dy=0;dy<18;dy++){int inset=18-(int)Math.sqrt(18*18-(18-dy)*(18-dy));
            g.hLine(x-38+inset,x+38-inset,top+dy,LINE);g.hLine(x-38+inset,x+38-inset,bottom-dy,LINE);}
        g.vLine(x-39,top+18,bottom-18,LINE);g.vLine(x+39,top+18,bottom-18,LINE);
        var view=inspection();double min=view==null?0:view.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).min().orElse(0);
        double max=view==null?1:view.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).max().orElse(1);
        for(int tray=1;tray<=n;tray++){
            int y=sy(trayY(tray)),at=tray;
            int color=view==null?MUTED:temperatureColor((view.nodes().get(tray).temperatureKelvin()-min)/Math.max(1e-9,max-min));
            g.hLine(x-32,x+32,y,color);
            if(tray==selectedNode)g.renderOutline(x-36,y-3,73,7,TEXT);
            if(tray==1||tray==n||tray%5==0)text(g,Integer.toString(tray),x-14,y+2,MUTED,28);
            hit(x-36,y-3,73,Math.max(6,pitch()),()->{selectedNode=at;railScroll.value=0;rebuild();},"Inspect tray "+tray);
        }
        hit(x-38,top,77,18,()->{selectedNode=0;railScroll.value=0;rebuild();},"Inspect condenser");
        hit(x-38,bottom-18,77,18,()->{selectedNode=n+1;railScroll.value=0;rebuild();},"Inspect reboiler");
        int fy=sy(trayY(feedTray()));
        g.hLine(sx(8),x-40,fy,ACCENT);text(g,"FEED  >  edit composition",sx(8),fy-12,ACCENT,210);
        hit(sx(8),fy-15,210,15,()->changePage(1),"Edit feed composition");
        for(int i=0;i<3;i++)if(!draft.get("d"+i+"stage").isBlank()){
            int at=draft.preview("d"+i+"stage",1,1,n),y=sy(trayY(at)),id=i;
            g.hLine(x+40,x+61,y,ACCENT);text(g,"D"+(i+1),x+42,y-10,ACCENT,24);
            hit(x+40,y-12,25,15,()->{selectedDraw=id;selectedPA=-1;rebuild();},"Configure or remove draw "+(i+1));
        }
        for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++)if(!draft.get("c"+i+"draw").isBlank()){
            int a=sy(trayY(draft.preview("c"+i+"draw",2,1,n))),b=sy(trayY(draft.preview("c"+i+"return",1,1,n))),lane=x-49-i*10,id=i;
            g.vLine(lane,Math.min(a,b),Math.max(a,b),WARN);g.hLine(lane,x-40,a,WARN);g.hLine(lane,x-40,b,WARN);
            text(g,"P"+(i+1),lane-18,a-10,WARN,18);
            hit(lane-18,a-12,26,15,()->{selectedPA=id;selectedDraw=-1;rebuild();},"Configure or remove pumparound "+(i+1));
        }
        text(g,"REBOILER",x-32,bottom+8,MUTED,100);
        if(V3HollandExample32.isPackage(draft.base().packageId()))text(g,"Fixed benchmark: load another template to edit",sx(8),sy(44),WARN,virtualWidth()-16);
        g.disableScissor();
    }
    private void inspect(GuiGraphics g,int x,int w){
        List<String> lines=new ArrayList<>();lines.add("SOLVER INFO");
        var view=inspection();
        if(view==null){
            lines.add(invalidated?"Not solved for this draft":state==null?"Waiting":state.status().serializedName());
            if(!invalidated&&state!=null)for(String s:state.diagnostics())wrap(lines,s,w);
        }else{
            List<String> wet=new ArrayList<>();
            for(int i=1;i<=view.input().stageCount();i++)if(view.nodes().get(i).freeWaterMolPerSecond()>0)wet.add(Integer.toString(i));
            wrap(lines,"Wet trays: "+(wet.isEmpty()?"none":String.join(", ",wet)),w);
            lines.add("Column dP: "+fmt((view.nodes().get(view.input().stageCount()).pressurePascal()-view.nodes().get(1).pressurePascal())/1000)+" kPa");
            state.displayResult().orElseThrow().trayHydraulics().ifPresent(h->{
                wrap(lines,h.floods()?"High dP / flooding: tray "+h.maximumFloodTray():"Hydraulics: below flooding limit",w);
                lines.add("Worst flood: "+fmt(h.maximumFloodFraction()*100)+"%");
                if(!h.correctionApplied())wrap(lines,"Pressure profile has no hydraulic correction; see Diagnostics.",w);
            });
            for(String a:view.audit().advisoryEvidence())if(a.startsWith("Warning: ")||a.toLowerCase(Locale.ROOT).contains("pressure"))wrap(lines,a,w);
        }
        lines.add("");lines.add(nodeName(selectedNode,stageCount()));
        if(view!=null){
            var node=view.nodes().get(selectedNode);
            lines.add("T "+fmt(temperature(node.temperatureKelvin()))+" "+unit()+"   P "+fmt(node.pressurePascal()/1000)+" kPa");
            lines.add("Tray dP: "+(selectedNode>=2&&selectedNode<=stageCount()?fmt(drop(view,selectedNode))+" kPa":"N/A"));
            lines.add("HC liquid "+fmt(node.liquidMolPerSecond()*3.6)+" kmol/h");
            lines.add("HC vapor "+fmt(node.vaporMolPerSecond()*3.6)+" kmol/h");
            lines.add("Water vapor "+fmt(node.waterVaporMolPerSecond()*3.6)+" kmol/h");
            lines.add("Free water "+fmt(node.freeWaterMolPerSecond()*3.6)+" kmol/h");
        }else lines.add("Solve to inspect tray results");
        int visible=Math.max(1,(bodyBottom()-CONTENT-12)/15);
        configure(railScroll,x+w+2,CONTENT,bodyBottom()-CONTENT-12,visible,lines.size()+(view==null?0:view.input().componentBasis().componentCount()+2),false);
        for(int i=railScroll.value;i<Math.min(lines.size(),railScroll.value+visible);i++)text(g,lines.get(i),x,CONTENT+(i-railScroll.value)*15,i==0?ACCENT:MUTED,w);
        if(view!=null){
            List<String[]> data=new ArrayList<>();var node=view.nodes().get(selectedNode);
            data.add(new String[]{"Component","x mol%","y mol%"});
            for(int c=0;c<view.input().componentBasis().componentCount();c++)data.add(new String[]{
                material(view.input().componentBasis().componentId(c)),node.liquidMolPerSecond()==0?"N/A":fmt(node.liquidFractions().get(c)*100),
                node.vaporMolPerSecond()==0?"N/A":fmt(node.vaporFractions().get(c)*100)});
            for(int i=0;i<data.size();i++){int y=CONTENT+(lines.size()+i-railScroll.value)*15;if(y>=CONTENT&&y+15<bodyBottom()-12)tableRow(g,data.get(i),x,y,w,i==0);}
        }
    }
    private void composition(GuiGraphics g){
        if(draft==null||presets||presetChoice!=null)return;
        if(picker){if(searchComponents().isEmpty())text(g,"No matching components in this template",10,CONTENT+53,MUTED,imageWidth-30);return;}
        int amount=Math.max(120,imageWidth/2),norm=imageWidth-140;
        text(g,"Component",10,CONTENT+34,TEXT,amount-18);text(g,"Relative %",amount,CONTENT+34,TEXT,norm-amount-8);
        text(g,"Mol %",norm,CONTENT+34,TEXT,65);
        double[] fractions=null;try{fractions=draft.composition().moleFractions();}catch(IllegalArgumentException ignored){}
        var ids=draft.composition().rows();int count=Math.max(1,(bodyBottom()-CONTENT-66)/25);
        for(int i=offset;i<Math.min(ids.size(),offset+count);i++){
            int id=ids.get(i),y=CONTENT+51+(i-offset)*25;
            g.hLine(10,imageWidth-25,y+22,LINE);
            text(g,material(draft.composition().source().componentBasis().componentId(id)),12,y+5,MUTED,amount-20);
            text(g,fractions==null?"--":fmt(fractions[id]*100),norm,y+5,TEXT,65);
        }
        if(ids.isEmpty())text(g,"Start with Add component. Amounts are relative.",10,CONTENT+58,MUTED,imageWidth-35);
    }
    private void tableRow(GuiGraphics g,String[] cells,int x,int y,int w,boolean header){
        g.fill(x,y-2,x+w,y+13,header?LINE:PANEL);int[] widths={w*50/100,w*25/100,w-w*75/100};int at=x;
        for(int i=0;i<cells.length;i++){text(g,cells[i],at+3,y,header?TEXT:MUTED,widths[i]-6);at+=widths[i];}
    }
    private void wrap(List<String> lines,String value,int width){
        while(font.width(value)>width){String s=font.plainSubstrByWidth(value,width);if(s.isEmpty())break;lines.add(s);value=value.substring(s.length());}
        lines.add(value);
    }
    private void profile(GuiGraphics g){
        var view=inspection();
        if(view==null){text(g,"Solve to publish accepted tray profiles.",10,CONTENT+27,MUTED,imageWidth-20);return;}
        int x=53,y=CONTENT+39,w=imageWidth-76,h=bodyBottom()-y-42;
        if(h<12)return;
        int series=metric==2?4:1;double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var node:view.nodes())for(int s=0;s<series;s++){
            double v=profileValue(node,s);if(Double.isFinite(v)){min=Math.min(min,v);max=Math.max(max,v);}}
        if(!Double.isFinite(min)){text(g,"This phase is absent.",10,y,WARN,w);return;}
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
                text(g,n==0?"C":n==view.nodes().size()-1?"R":Integer.toString(n),22,py-4,MUTED,25);g.hLine(x,x+w,py,LINE);}}
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
        if(metric==2)text(g,"Teal L / amber V / blue steam / violet water",10,CONTENT+25,MUTED,imageWidth-20);
        hit(x,y,w,h,()->{selectedNode=Math.clamp((pointerY-y)*(stageCount()+1)/h,0,stageCount()+1);page=0;infoOnly=true;railScroll.value=0;rebuild();},"Click a profile to inspect its tray");
    }
    private double profileValue(V3ColumnInspection.Node node,int series){
        return switch(metric){
            case 0->temperature(node.temperatureKelvin());
            case 1->node.pressurePascal()/1000;
            case 2->switch(series){case 0->node.liquidMolPerSecond()*3.6;case 1->node.vaporMolPerSecond()*3.6;
                case 2->node.waterVaporMolPerSecond()*3.6;default->node.freeWaterMolPerSecond()*3.6;};
            default->throw new IllegalStateException("Unknown profile");};
    }
    private void streams(GuiGraphics g){
        if(inspection()==null){text(g,"Solve to view product compositions",10,CONTENT+27,MUTED,imageWidth-25);return;}
        var products=state.displayResult().orElseThrow().streams();if(products.isEmpty())return;
        var stream=products.get(Math.clamp(selectedStream,0,products.size()-1));
        text(g,fmt(stream.molarFlowMolPerSecond()*3.6)+" kmol/h  |  "+fmt(temperature(stream.temperatureKelvin()))+" "+unit()+"  |  "+fmt(stream.pressurePascal()/1000)+" kPa",10,CONTENT+26,MUTED,imageWidth-30);
        List<String[]> data=new ArrayList<>();
        for(var c:stream.moleFractions())data.add(new String[]{material(c.componentId()),fmt(c.moleFraction()*100),fmt(c.massFraction()*100)});
        table(g,new String[]{"Component","Mol %","Mass %"},data,CONTENT+46);
    }
    private void table(GuiGraphics g,String[] headers,List<String[]> data,int y){
        int visible=Math.max(1,(bodyBottom()-y-32)/17);
        configure(listScroll,imageWidth-18,y+18,bodyBottom()-y-30,visible,data.size(),false);
        offset=listScroll.value;tableRow(g,headers,10,y,imageWidth-36,true);
        for(int i=offset;i<Math.min(data.size(),offset+visible);i++)tableRow(g,data.get(i),10,y+18+(i-offset)*17,imageWidth-36,false);
    }
    private static double drop(V3ColumnInspection view,int tray){
        return (view.nodes().get(tray).pressurePascal()-view.nodes().get(tray-1).pressurePascal())/1000;
    }
    private void pressureDrop(GuiGraphics g){
        var view=inspection();
        if(view==null){text(g,"Solve to view the column pressure-drop profile",10,CONTENT,MUTED,imageWidth-30);return;}
        int n=view.input().stageCount(),x=60,y=CONTENT+35,w=imageWidth-92,h=bodyBottom()-y-48;
        double total=(view.nodes().get(n).pressurePascal()-view.nodes().get(1).pressurePascal())/1000,max=0;
        for(int i=2;i<=n;i++)max=Math.max(max,drop(view,i));max=Math.max(max,0.001);
        text(g,"Total dP: "+fmt(total)+" kPa  |  intervals between trays",10,CONTENT,TEXT,imageWidth-30);
        text(g,"Condenser / tray 1 / sump: no separate interval",10,CONTENT+15,MUTED,imageWidth-30);
        if(h<16)return;
        g.fill(x,y,x+w,y+h,PANEL);
        for(int i=2;i<=n;i++){
            int py=y+(i-2)*h/(n-1),next=y+(i-1)*h/(n-1),tray=i;
            int bar=(int)Math.round(drop(view,i)/max*w);
            g.fill(x,py,x+bar,Math.max(py+1,next-1),ACCENT);
            if(i==2||i==n||i%5==0)text(g,Integer.toString(i),20,py,MUTED,35);
            hit(x,py,w,Math.max(1,next-py),()->{selectedNode=tray;page=0;infoOnly=true;railScroll.value=0;rebuild();},"Tray "+i+": "+fmt(drop(view,i))+" kPa; click to inspect");
        }
        text(g,"0",x,y+h+5,MUTED,30);text(g,fmt(max)+" kPa",x+w-85,y+h+5,MUTED,85);
    }
    private void heat(GuiGraphics g){
        List<String> lines=new ArrayList<>();lines.add("Signed heat: + into column / - removed");
        if(inspection()!=null)state.displayResult().ifPresent(result->{
            result.dutyLedger().ifPresent(d->{
                lines.add("Condenser "+fmt(d.condenserWatts()/1e6)+" MW");lines.add("Reboiler "+fmt(d.reboilerWatts()/1e6)+" MW");
                lines.add("Stage heat "+fmt(d.stageHeatTotalWatts()/1e6)+" MW");
                lines.add("Feed enthalpy "+fmt(d.feedEnthalpyWatts()/1e6)+" MW");lines.add("Steam enthalpy "+fmt(d.steamEnthalpyWatts()/1e6)+" MW");
                for(var stage:d.stageDuties())lines.add("Tray "+stage.trayNumber()+": "+fmt(stage.dutyWatts()/1e6)+" MW");});
            if(result.dutyLedger().isEmpty())lines.add("No accepted duty ledger available");});
        drawLines(g,lines,10,CONTENT,imageWidth-20);
    }
    private void diagnostics(GuiGraphics g){
        List<String> lines=new ArrayList<>();lines.add("Latest attempt: "+(state==null?"Awaiting engine":state.status().serializedName()));
        if(!rejection.isBlank())lines.add(rejection);
        if(state!=null&&!invalidated){
            lines.addAll(state.diagnostics());
            state.displayResult().ifPresent(r->{
                lines.add(freshness()+" - evidence below belongs to this result");
                lines.add("Newton iterations: "+r.newtonIterations()+"; residual: "+fraction(r.maximumScaledResidual()));
                lines.add("Closure tolerance: "+fraction(r.closureTolerance()));
                r.trayHydraulics().ifPresent(h->{
                    lines.add("Diameter "+fmt(h.columnDiameterMetres())+" m; pressure drop "+fmt(h.totalPressureDropPascal()/1000)+" kPa");
                    lines.add("Worst tray "+h.maximumFloodTray()+": "+fmt(h.maximumFloodFraction()*100)+"% of flood");
                    if(h.floods())lines.add("Flooding: widen the column or reduce feed, steam, heat or reflux");});
                r.inspection().ifPresent(v->{
                    lines.addAll(v.audit().advisoryEvidence());
                    for(var check:v.audit().checks()){
                        lines.add(check.family()+": "+fraction(check.value())+" <= "+fraction(check.limit()));lines.add(check.detail());}});
                lines.add("Dataset: "+r.datasetRevision());lines.add("Formulation: "+r.formulationRevision());lines.add("Digest: "+r.inputDigest());});
        }
        List<String> wrapped=new ArrayList<>();
        for(String line:lines){
            String rest=line;
            while(font.width(rest)>imageWidth-24){
                String prefix=font.plainSubstrByWidth(rest,imageWidth-24);if(prefix.isEmpty())break;
                wrapped.add(prefix);rest=rest.substring(prefix.length());}
            wrapped.add(rest);}
        drawLines(g,wrapped,10,CONTENT,imageWidth-20);
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
    private static String nodeName(int node,int stages){return node==0?"Condenser (node 0)":node==stages+1?"Sump / reboiler (node "+node+")":"Tray "+node;}
    private static int temperatureColor(double t){
        t=Math.clamp(t,0,1);return 0xFF000000|((int)(77+173*t)<<16)|((int)(161+30*t)<<8)|(int)(211-123*t);}
    private static void line(GuiGraphics g,int x0,int y0,int x1,int y1,int color){
        int steps=Math.max(Math.abs(x1-x0),Math.abs(y1-y0));
        for(int i=0;i<=steps;i++){double f=steps==0?0:(double)i/steps;
            int x=(int)Math.round(x0+(x1-x0)*f),y=(int)Math.round(y0+(y1-y0)*f);g.fill(x,y,x+1,y+1,color);}}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        int px=(int)x-leftPos,py=(int)y-topPos;
        if(py>=CONTENT&&py<bodyBottom()&&!(page==2&&(results==0||results==3))){
            Scroll target=page==0?(!narrow()&&px>imageWidth-248||narrow()&&infoOnly?railScroll:canvasY):listScroll;
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
