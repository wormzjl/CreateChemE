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

/** Accepted physical inspection and a separate local draft; rendering never invokes the solver. */
public final class ColumnCalculatorV3Screen extends AbstractContainerScreen<ColumnCalculatorV3Menu> {
    private static final int BG=0xFF202A31, PANEL=0xFF28363F, LINE=0xFF506572,
            TEXT=0xFFE6EDF3, MUTED=0xFFA9BCC7, ACCENT=0xFF79D6CE, WARN=0xFFFFCD82, CONTENT=88;
    private static final String[] PAGES={"Overview","Setup","Results","Diagnostics"},
            SETUP={"Operating","Feed","Connections","Cases"}, RESULTS={"Profiles","Streams","Heat"},
            METRICS={"Temperature (C)","Pressure (kPa)","Phase traffic (kmol/h)","Liquid x","Vapor y"},
            SCALARS={"Feed (kmol/h)","Feed temperature (C)","Equilibrium trays","Feed tray",
                    "Condenser temperature (C)","Reboiler input (MW)","Organic reflux ratio",
                    "Top pressure (bar abs)","Nominal drop / tray (kPa)","Diameter (m; 0 = prescribed drop)"};
    private final List<Label> labels=new ArrayList<>();
    private final List<Hit> hits=new ArrayList<>();
    private final Map<String,EditBox> editors=new LinkedHashMap<>();
    private V3State state;
    private V3EditorDraft draft;
    private V3ColumnInput candidate;
    private String validation="Waiting for the engine", rejection="";
    private long pendingNonce;
    private boolean conflict, inspectorOnly, rebuilding;
    private int page,setup,results,connection,offset,selectedNode=1,selectedStream,metric,component,lineCount;
    private Button solve;
    private String hoveredText;
    private int pointerX, pointerY;
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hoveredText = null; pointerX = mouseX - leftPos; pointerY = mouseY - topPos;
        super.render(g, mouseX, mouseY, partialTick);
        if (hoveredText != null) g.renderTooltip(font, Component.literal(hoveredText), mouseX, mouseY);
    }
    private record Label(String text,int x,int y,int width) {}
    private record Hit(int x,int y,int w,int h,int node,int stream) {}
    private record ProductMark(int node, int index, String label) {}

    public ColumnCalculatorV3Screen(ColumnCalculatorV3Menu menu,Inventory inventory,Component title) {
        super(menu,inventory,title); imageWidth=620; imageHeight=360;
    }
    @Override protected void init() {
        imageWidth=Math.min(860,Math.max(180,width-16));
        imageHeight=Math.min(460,Math.max(140,height-16));
        super.init(); titleLabelY=inventoryLabelY=-1000;
        ColumnV3Network.setClientStateConsumer(this::receive);
        ColumnV3Network.setClientRejectionConsumer((pos,nonce,reason)->{
            if(!menu.blockPos().equals(pos)||nonce!=pendingNonce)return;
            pendingNonce=0;rejection=reason;validate();rebuild();
        });
        ColumnV3Network.sendStateRequest(menu.blockPos());rebuild();
    }
    private void receive(net.minecraft.core.BlockPos pos,V3State incoming) {
        if(!menu.blockPos().equals(pos)||state!=null&&incoming.stateRevision()<state.stateRevision())return;
        boolean own=pendingNonce!=0&&incoming.clientNonce()==pendingNonce;
        boolean replace=draft==null||own||!draft.dirty();
        boolean changed=draft==null||!draft.base().equals(incoming.input());
        boolean newConflict=!replace&&state!=null&&!state.input().equals(incoming.input());
        if(newConflict)conflict=true;
        state=incoming;
        if(own){pendingNonce=0;rejection="";conflict=false;}
        if(replace&&(changed||own)){draft=new V3EditorDraft(incoming.input());conflict=false;}
        component=Math.clamp(component,0,inspection()==null?0:inspection().input().componentBasis().componentCount()-1);
        validate();
        if(changed&&replace||own||newConflict)rebuild();else updateControls();
    }
    @Override public void onClose() {
        ColumnV3Network.setClientStateConsumer((pos,view)->{});
        ColumnV3Network.setClientRejectionConsumer((pos,nonce,reason)->{});super.onClose();
    }
    private boolean busy(){return pendingNonce!=0||state!=null&&state.status()==V3Status.CALCULATING;}
    private void validate(){
        candidate=null;
        if(draft==null){validation="Waiting for the engine";return;}
        try{candidate=draft.assemble();validation=draft.dirty()?"Inputs edited; solve to update results":"Ready";}
        catch(IllegalArgumentException invalid){validation=invalid.getMessage()==null?"Invalid input":invalid.getMessage();}
        updateControls();
    }
    private void updateControls(){
        if(solve!=null)solve.active=candidate!=null&&!busy()&&!conflict&&state!=null&&state.status()!=V3Status.INCOMPATIBLE;
        boolean editable=!busy()&&draft!=null&&!V3HollandExample32.isPackage(draft.base().packageId());
        editors.values().forEach(e->e.active=editable);
    }
    private void submit(){
        validate();if(candidate==null||busy()||conflict||state==null)return;
        pendingNonce=ColumnV3Network.sendCalculate(menu.blockPos(),state.inputRevision(),candidate);rejection="";rebuild();
    }
    private void changePage(int target){page=target;offset=0;rebuild();}
    private int bodyBottom(){return imageHeight-(conflict?72:48);}
    private int rows(){return Math.max(1,(bodyBottom()-CONTENT-22)/15);}
    private boolean narrow(){return imageWidth<540;}
    private Button button(String text,int x,int y,int w,Runnable action){
        return addRenderableWidget(Button.builder(Component.literal(text),b->action.run())
                .bounds(leftPos+x,topPos+y,Math.max(20,w),18).build());
    }
    private void rebuild(){
        if(rebuilding)return;rebuilding=true;
        if(page==1&&(setup==0||setup==2||setup==3))offset=Math.min(offset,maximumOffset());
        String focus=editors.entrySet().stream().filter(e->e.getValue()==getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        int cursor=focus==null?0:editors.get(focus).getCursorPosition();
        clearWidgets();labels.clear();editors.clear();
        int tab=(imageWidth-20)/4;
        for(int i=0;i<4;i++){int target=i;button(PAGES[i],10+i*tab,28,tab-3,()->changePage(target)).active=page!=i;}
        solve=button("Solve",10,imageHeight-25,64,this::submit);
        button("Warnings",imageWidth-82,imageHeight-25,72,()->changePage(3));
        if(conflict){
            button("Use server input",10,imageHeight-69,120,()->{
                draft=new V3EditorDraft(state.input());conflict=false;rejection="";validate();rebuild();});
            button("Keep my draft",136,imageHeight-69,115,()->{conflict=false;rejection="";validate();rebuild();});
        }
        if(page==0){
            button("<",10,53,22,()->selectNode(selectedNode-1));
            var node=new EditBox(font,leftPos+36,topPos+53,36,18,Component.literal("Physical node"));
            node.setMaxLength(2);node.setValue(Integer.toString(selectedNode));
            node.setResponder(value->{try{selectedNode=Math.clamp(Integer.parseInt(value),0,stageCount()+1);offset=0;}
                catch(NumberFormatException ignored){ /* Keep incomplete local input while typing. */ }});
            addRenderableWidget(node);button(">",76,53,22,()->selectNode(selectedNode+1));
            if(narrow())button(inspectorOnly?"Diagram":"Inspect",104,53,76,()->{inspectorOnly=!inspectorOnly;offset=0;rebuild();});
            button("Profile",imageWidth-76,53,66,()->{results=0;changePage(2);});
        }else if(page==1){
            for(int i=0;i<4;i++){int target=i;button(SETUP[i],10+i*tab,53,tab-3,()->{setup=target;offset=0;rebuild();}).active=setup!=i;}
            if(draft!=null)buildSetup();
        }else if(page==2){
            int w=(imageWidth-20)/3;
            for(int i=0;i<3;i++){int target=i;button(RESULTS[i],10+i*w,53,w-3,()->{results=target;offset=0;rebuild();}).active=results!=i;}
            if(results==0){
                button(METRICS[metric],10,CONTENT,Math.min(188,imageWidth/2-12),()->{metric=(metric+1)%5;rebuild();});
                if(metric>=3&&inspection()!=null)button(material(inspection().input().componentBasis().componentId(component)),
                        imageWidth/2,CONTENT,imageWidth/2-10,()->{component=(component+1)%inspection().input().componentBasis().componentCount();rebuild();});
            }
        }
        button("Up",imageWidth-54,bodyBottom()-19,44,()->{offset=Math.max(0,offset-3);rebuild();});
        button("Down",imageWidth-103,bodyBottom()-19,45,()->{offset=Math.min(maximumOffset(),offset+3);rebuild();});
        if(focus!=null&&editors.containsKey(focus)){setFocused(editors.get(focus));editors.get(focus).setCursorPosition(cursor);}
        rebuilding=false;updateControls();
    }
    private void buildSetup(){
        int count=Math.max(1,(bodyBottom()-CONTENT-24)/32);
        if(setup==0){
            int columns = narrow() ? 1 : 2, cell = (imageWidth - 30) / columns;
            for(int i=offset;i<Math.min(10,offset+count*columns);i++)
                field(SCALARS[i],"s"+i,CONTENT+(i-offset)/columns*32,10+(i-offset)%columns*(cell+10),cell);
        }else if(setup==2){
            button(new String[]{"Side draws","Steam","Coolers"}[connection],10,CONTENT,124,()->{connection=(connection+1)%3;offset=0;rebuild();});
            var fields=connectionFields();count=Math.max(1,(bodyBottom()-CONTENT-50)/32);
            for(int i=offset;i<Math.min(fields.size(),offset+count);i++){
                var f=fields.get(i);int y=CONTENT+26+(i-offset)*32;
                if(f[1].endsWith("split")){
                    labels.add(new Label(f[0],10,y,imageWidth-22));
                    var b=button(draft.get(f[1]).equals("UNIFORM")?"Uniform stage heat":"Return tray only",10,y+11,imageWidth-124,()->{
                        draft.set(f[1],draft.get(f[1]).equals("UNIFORM")?"RETURN_TRAY":"UNIFORM");rejection="";validate();rebuild();});
                    b.active=!busy()&&!V3HollandExample32.isPackage(draft.base().packageId());
                }else field(f[0],f[1],y);
            }
        }else if(setup==3&&state!=null){
            count=Math.max(1,(bodyBottom()-CONTENT-24)/24);
            for(int i=offset;i<Math.min(state.presets().size(),offset+count);i++){
                var preset=state.presets().get(i);
                var b=button(Component.translatableWithFallback(preset.translationKey(),preset.label()).getString(),
                        10,CONTENT+(i-offset)*24,imageWidth-22,()->{
                            pendingNonce=ColumnV3Network.sendPreset(menu.blockPos(),state.inputRevision(),preset.id());rejection="";rebuild();});
                b.setTooltip(Tooltip.create(Component.literal("Replace the draft with this server case")));b.active=!busy();
            }
        }
    }
    private List<String[]> connectionFields(){
        List<String[]> fields=new ArrayList<>();
        if(connection==0)for(int i=0;i<3;i++){
            fields.add(new String[]{"Draw "+(i+1)+" tray","d"+i+"stage"});
            fields.add(new String[]{"Draw "+(i+1)+" (kmol/h; blank or 0 = off)","d"+i+"rate"});
        }else if(connection==1){
            fields.add(new String[]{"Sump steam (kmol/h; blank or 0 = off)","sumpRate"});
            fields.add(new String[]{"Sump steam temperature (C)","sumpT"});
            fields.add(new String[]{"Additional steam tray","steamStage"});
            fields.add(new String[]{"Tray steam (kmol/h; blank or 0 = off)","steamRate"});
            fields.add(new String[]{"Tray steam temperature (C)","steamT"});
        }else for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++){
            fields.add(new String[]{"Cooler "+(i+1)+" draw tray (clear both trays = off)","c"+i+"draw"});
            fields.add(new String[]{"Cooler "+(i+1)+" return tray","c"+i+"return"});
            fields.add(new String[]{"Cooler "+(i+1)+" cooling removed (MW)","c"+i+"duty"});
            fields.add(new String[]{"Cooler "+(i+1)+" distribution","c"+i+"split"});
        }
        return fields;
    }
    private void field(String name,String key,int y) { field(name,key,y,10,imageWidth-124); }
    private void field(String name,String key,int y,int x,int w){
        labels.add(new Label(name,x,y,w));
        var editor=new EditBox(font,leftPos+x,topPos+y+11,Math.max(80,Math.min(300,w)),18,Component.literal(name));
        editor.setMaxLength(32);editor.setValue(draft.get(key));
        editor.setResponder(value->{draft.set(key,value);rejection="";validate();});
        editor.setTooltip(Tooltip.create(Component.literal(name)));editors.put(key,addRenderableWidget(editor));
    }
    private V3ColumnInspection inspection(){return state==null?null:state.displayResult().flatMap(V3ColumnDisplayResult::inspection).orElse(null);}
    private V3ColumnInput shownInput(){var v=inspection();return v!=null?v.input():candidate!=null?candidate:draft!=null?draft.base():null;}
    private int stageCount(){var i=shownInput();return i==null?2:i.stageCount();}
    private void selectNode(int node){selectedNode=Math.clamp(node,0,stageCount()+1);offset=0;rebuild();}
    private boolean current(){return state!=null&&V3ResultProvenance.retainedResultMatchesInput(state.status(),
            inspection()!=null,draft==null||draft.dirty()||pendingNonce!=0||conflict)
            &&inspection().input().equals(state.input());}
    private String freshness(){return state==null||state.displayResult().isEmpty()?"No accepted result":current()?"Current accepted result":"Previous accepted result";}
    private String status(){
        if(pendingNonce!=0)return "Request sent; awaiting engine";
        if(conflict)return "Server input changed; choose which draft to use";
        if(!rejection.isBlank())return rejection;
        if(busy())return "Solving; updates arrive on the engine schedule";
        if(candidate==null||draft!=null&&draft.dirty())return validation;
        if(state==null)return "Waiting for the engine";
        return switch(state.status()){
            case SUCCESS->"Accepted; select a tray or stream";
            case FAILED->"Last solve failed; see Diagnostics";
            case STALE->"Previous result; solve to refresh";
            case INCOMPATIBLE->"Unsupported saved case; load a preset";
            default->validation;};
    }
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY){
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,BG);
        g.renderOutline(leftPos,topPos,imageWidth,imageHeight,LINE);
    }
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        hits.clear();
        text(g,"COLUMN / "+PAGES[page],10,10,TEXT,imageWidth/2);
        text(g,freshness(),imageWidth/2,10,current()?ACCENT:WARN,imageWidth/2-10);
        g.fill(8,77,imageWidth-8,78,LINE);
        g.enableScissor(leftPos+8,topPos+CONTENT-2,leftPos+imageWidth-8,topPos+Math.max(CONTENT,bodyBottom()-22));
        if(page==0)overview(g);
        else if(page==1){for(var l:labels)text(g,l.text(),l.x(),l.y(),MUTED,l.width());if(setup==1)feed(g);}
        else if(page==2){if(results==0)profile(g);else if(results==1)streams(g);else heat(g);}
        else diagnostics(g);
        g.disableScissor();
        g.fill(8,imageHeight-47,imageWidth-8,imageHeight-46,LINE);
        text(g,status(),10,imageHeight-40,candidate==null||!rejection.isBlank()?WARN:MUTED,imageWidth-20);
        if(imageWidth>400)text(g,"Online-time engine updates",85,imageHeight-20,MUTED,imageWidth-178);
    }

    private void overview(GuiGraphics g){
        if(shownInput()==null){text(g,"The engine will publish this case at its next update.",10,CONTENT,MUTED,imageWidth-20);return;}
        selectedNode=Math.clamp(selectedNode,0,stageCount()+1);
        if(narrow()){if(inspectorOnly)inspect(g,10,imageWidth-20);else diagram(g,10,imageWidth-20);}
        else{int w=Math.min(240,imageWidth/3);diagram(g,10,w);g.fill(w+20,CONTENT,w+21,bodyBottom()-25,LINE);inspect(g,w+32,imageWidth-w-44);}
    }
    private void diagram(GuiGraphics g,int x,int w) {
        var input=shownInput();var view=inspection();
        int top=CONTENT+(narrow()?12:20),bottom=bodyBottom()-(narrow()?26:40),cx=x+w/2,n=input.stageCount();
        text(g,view==null?"Draft configuration":"Accepted topology",x,CONTENT,MUTED,w);
        if(bottom<=top+16)return;
        double min=view==null?0:view.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).min().orElse(0);
        double max=view==null?1:view.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).max().orElse(1);
        g.fill(cx-23,top,cx+24,bottom+1,LINE);
        for(int node=0;node<n+2;node++){
            int y=top+node*(bottom-top)/(n+1);
            int color=view==null?MUTED:temperatureColor((view.nodes().get(node).temperatureKelvin()-min)/Math.max(1e-9,max-min));
            g.fill(cx-19,y,cx+20,y+2,color);
            if(node==selectedNode)g.renderOutline(cx-26,y-3,53,8,TEXT);
            hits.add(new Hit(cx-27,y-2,55,Math.max(3,(bottom-top)/(n+1)),node,-1));
        }
        text(g,"C",cx-33,top-4,TEXT,10);
        text(g,"R",cx-33,bottom-4,TEXT,10);
        int fy=top+input.feedStageNumber()*(bottom-top)/(n+1);
        g.hLine(x+2,cx-24,fy,ACCENT);
        text(g,"Feed >",x,Math.min(fy-10,bottom-9),ACCENT,Math.max(30,w/2-48));
        for(int i=0;i<input.pumparounds().size();i++){
            var cooler=input.pumparounds().get(i);
            int y1=top+cooler.returnTray()*(bottom-top)/(n+1),y2=top+cooler.drawTray()*(bottom-top)/(n+1),lane=cx-40-i*6;
            g.vLine(lane,y1,y2,WARN);g.hLine(lane,cx-23,y1,WARN);g.hLine(lane,cx-23,y2,WARN);
        }
        for(var steam:input.steamFeeds()){
            int y=top+steam.stageNumber()*(bottom-top)/(n+1);
            for(int dx=x+2;dx<cx-40;dx+=5)g.hLine(dx,Math.min(dx+2,cx-40),y,WARN);
        }
        List<ProductMark> marks=new ArrayList<>();
        if(view!=null&&state!=null){
            var products=state.displayResult().orElseThrow().streams();
            for(int i=0;i<products.size();i++){
                var product=products.get(i);
                int at=0;String label=product.streamId().contains("water")?"Water":product.phase().equals("VAPOR")?"Vapor":"Distillate";
                for(var draw:input.sideDraws())if(product.streamId().equals(String.format(Locale.ROOT,"side_liquid_tray_%02d",draw.trayNumber()))){
                    at=draw.trayNumber();label="Draw "+at;
                }
                if(product.streamId().equals("bottoms_liquid")){at=n+1;label="Bottoms";}
                marks.add(new ProductMark(at,i,label));
            }
        }else for(var draw:input.sideDraws())marks.add(new ProductMark(draw.trayNumber(),-1,"Draw "+draw.trayNumber()));
        marks.sort(Comparator.comparingInt(ProductMark::node));
        if(narrow()){
            var grouped=new LinkedHashMap<Integer,ProductMark>();
            for(var mark:marks)grouped.putIfAbsent(mark.node(),mark.node()==0?new ProductMark(0,mark.index(),"Overhead"):mark);
            marks=new ArrayList<>(grouped.values());
        }
        // Separate labels from their physical attachment points and avoid terminal products crossing the column.
        int spacing=Math.min(13,Math.max(8,(bottom-top)/Math.max(1,marks.size()-1)));
        int[] ys=new int[marks.size()];
        for(int i=0;i<marks.size();i++)ys[i]=Math.max(top+marks.get(i).node()*(bottom-top)/(n+1),i==0?top:ys[i-1]+spacing);
        if(ys.length>0){
            ys[ys.length-1]=Math.min(bottom,ys[ys.length-1]);
            for(int i=ys.length-2;i>=0;i--)ys[i]=Math.min(ys[i],ys[i+1]-spacing);
        }
        for(int i=0;i<marks.size();i++){
            var mark=marks.get(i);int actual=top+mark.node()*(bottom-top)/(n+1),labelY=ys[i];
            g.hLine(cx+24,cx+28,actual,ACCENT);line(g,cx+28,actual,cx+36,labelY,ACCENT);
            g.hLine(cx+36,x+w-4,labelY,ACCENT);
            text(g,"> "+mark.label(),cx+39,labelY-9,ACCENT,Math.max(20,w/2-39));
            hits.add(new Hit(cx+29,labelY-10,Math.max(20,w/2-29),11,mark.node(),mark.index()));
        }
        if(!narrow())text(g,view==null?"C = condenser; R = sump":fmt(min-273.15)+" to "+fmt(max-273.15)+" C",x,bottom+9,MUTED,w);
    }
    private void inspect(GuiGraphics g,int x,int w){
        var input=shownInput();var view=inspection();List<String> lines=new ArrayList<>();
        lines.add(nodeName(selectedNode,input.stageCount()));
        if(view==null)lines.add("Configuration only; solve to inspect");
        else{
            var n=view.nodes().get(selectedNode);
            lines.add("T "+fmt(n.temperatureKelvin()-273.15)+" C   P "+fmt(n.pressurePascal()/1000)+" kPa");
            lines.add("HC liquid "+fmt(n.liquidMolPerSecond()*3.6)+" kmol/h");
            lines.add("HC vapor "+fmt(n.vaporMolPerSecond()*3.6)+" kmol/h");
            lines.add("Water vapor "+fmt(n.waterVaporMolPerSecond()*3.6)+" kmol/h");
            lines.add((selectedNode==0?"Decanted water ":"Free water ")+fmt(n.freeWaterMolPerSecond()*3.6)+" kmol/h");
        }
        if(selectedNode==input.feedStageNumber())lines.add("Feed enters here");
        for(var d:input.sideDraws())if(d.trayNumber()==selectedNode)lines.add("Draw: "+fmt(d.molarFlowMolPerSecond()*3.6)+" kmol/h");
        for(var s:input.steamFeeds())if(s.stageNumber()==selectedNode)lines.add("Steam inlet: "+fmt(s.molarFlowMolPerSecond()*3.6)+" kmol/h");
        for(var c:input.pumparounds())if(selectedNode>=c.returnTray()&&selectedNode<=c.drawTray())
            lines.add("Cooler "+c.drawTray()+" -> "+c.returnTray()+": "+fmt(-c.dutyWatts()/1e6)+" MW removed");
        if(view!=null){
            lines.add("Hydrocarbon composition: x / y");var n=view.nodes().get(selectedNode);
            for(int c=0;c<input.componentBasis().componentCount();c++)lines.add(material(input.componentBasis().componentId(c))+": "
                    +(n.liquidMolPerSecond()==0?"N/A":fraction(n.liquidFractions().get(c)))+" / "
                    +(n.vaporMolPerSecond()==0?"N/A":fraction(n.vaporFractions().get(c))));
        }
        drawLines(g,lines,x,CONTENT,w);
    }
    private void feed(GuiGraphics g){
        List<String> lines=new ArrayList<>();lines.add("Server composition; total flow scales this mixture");
        var input=candidate==null?draft.base():candidate;var flows=input.feedComponentMolarFlowsMolPerSecond();
        double total=Arrays.stream(flows).sum();lines.add("Total: "+fmt(total*3.6)+" kmol/h");
        for(int c=0;c<flows.length;c++)lines.add(material(input.componentBasis().componentId(c))+": "
                +fmt(flows[c]*3.6)+" kmol/h; "+fmt(flows[c]/total*100)+" mol%");
        drawLines(g,lines,10,CONTENT,imageWidth-20);
    }
    private void profile(GuiGraphics g){
        var view=inspection();
        if(view==null){text(g,"Solve to publish accepted tray profiles.",10,CONTENT+27,MUTED,imageWidth-20);return;}
        component=Math.clamp(component,0,view.input().componentBasis().componentCount()-1);
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
        hits.add(new Hit(x,y,w,h,-2,-1));
    }
    private double profileValue(V3ColumnInspection.Node node,int series){
        return switch(metric){
            case 0->node.temperatureKelvin()-273.15;
            case 1->node.pressurePascal()/1000;
            case 2->switch(series){case 0->node.liquidMolPerSecond()*3.6;case 1->node.vaporMolPerSecond()*3.6;
                case 2->node.waterVaporMolPerSecond()*3.6;default->node.freeWaterMolPerSecond()*3.6;};
            case 3->node.liquidMolPerSecond()==0?Double.NaN:node.liquidFractions().get(component);
            default->node.vaporMolPerSecond()==0?Double.NaN:node.vaporFractions().get(component);};
    }
    private void streams(GuiGraphics g){
        if(state==null||state.displayResult().isEmpty())return;
        var streams=state.displayResult().orElseThrow().streams();if(streams.isEmpty())return;
        selectedStream=Math.clamp(selectedStream,0,streams.size()-1);List<String> lines=new ArrayList<>();
        lines.add("Select a product row for composition");
        for(int i=0;i<streams.size();i++){
            var s=streams.get(i);
            lines.add((i==selectedStream?"> ":"  ")+s.displayName()+" / "+s.phase());
            lines.add("  "+fmt(s.molarFlowMolPerSecond()*3.6)+" kmol/h   "+fmt(s.temperatureKelvin()-273.15)
                    +" C   "+fmt(s.pressurePascal()/1000)+" kPa");
            hits.add(new Hit(10,CONTENT+(1+i*2-offset)*15,imageWidth-20,30,-1,i));
        }
        var s=streams.get(selectedStream);lines.add(s.displayName()+": mol% / wt%");
        lines.add("Mass flow "+fmt(s.massFlowKgPerSecond()*3600)+" kg/h");
        for(var c:s.moleFractions())lines.add(material(c.componentId())+": "+fraction(c.moleFraction()*100)+" / "+fraction(c.massFraction()*100));
        drawLines(g,lines,10,CONTENT,imageWidth-20);
    }
    private void heat(GuiGraphics g){
        List<String> lines=new ArrayList<>();lines.add("Signed heat: + into column / - removed");
        if(state!=null)state.displayResult().ifPresent(result->{
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
        if(state!=null){
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
        lineCount=lines.size();offset=Math.clamp(offset,0,Math.max(0,lines.size()-rows()));
        for(int i=offset;i<Math.min(lines.size(),offset+rows());i++)text(g,lines.get(i),x,y+(i-offset)*15,i==0?TEXT:MUTED,w);
    }
    private String material(String id){return state==null?id:MaterialNames.localized(state.materialNames().getOrDefault(id,MaterialName.chemical(id)));}
    private void text(GuiGraphics g,String value,int x,int y,int color,int max){
        if(max<=0)return;
        String shown=font.width(value)>max?font.plainSubstrByWidth(value,Math.max(1,max-8))+"...":value;
        g.drawString(font,shown,x,y,color,false);
        if (!shown.equals(value) && pointerX >= x && pointerX < x + max && pointerY >= y && pointerY < y + 10)
            hoveredText = value;
    }
    private static String fmt(double value){return String.format(Locale.ROOT,"%.3f",value);}
    private static String fraction(double value){return String.format(Locale.ROOT,"%.5g",value);}
    private static String nodeName(int node,int stages){return node==0?"Condenser (node 0)":node==stages+1?"Sump / reboiler (node "+node+")":"Tray "+node;}
    private static int temperatureColor(double t){
        t=Math.clamp(t,0,1);return 0xFF000000|((int)(77+173*t)<<16)|((int)(161+30*t)<<8)|(int)(211-123*t);}
    private static void line(GuiGraphics g,int x0,int y0,int x1,int y1,int color){
        int steps=Math.max(Math.abs(x1-x0),Math.abs(y1-y0));
        for(int i=0;i<=steps;i++){double f=steps==0?0:(double)i/steps;
            int x=(int)Math.round(x0+(x1-x0)*f),y=(int)Math.round(y0+(y1-y0)*f);g.fill(x,y,x+1,y+1,color);}}
    private int maximumOffset(){
        if(page==1&&setup==0)return Math.max(0,10-Math.max(1,(bodyBottom()-CONTENT-24)/32)*(narrow()?1:2));
        if(page==1&&setup==2)return Math.max(0,connectionFields().size()-Math.max(1,(bodyBottom()-CONTENT-50)/32));
        if(page==1&&setup==3)return state==null?0:Math.max(0,state.presets().size()-Math.max(1,(bodyBottom()-CONTENT-24)/24));
        return Math.max(0,lineCount-rows());
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(x>=leftPos&&x<leftPos+imageWidth&&y>=topPos+CONTENT&&y<topPos+bodyBottom()){
            offset=Math.clamp(offset+(vertical<0?3:-3),0,maximumOffset());rebuild();return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        // AbstractContainerScreen consumes empty-space clicks too, so inspect our content before delegating.
        if(button!=0)return super.mouseClicked(x,y,button);
        int px=(int)x-leftPos,py=(int)y-topPos;
        if(py<CONTENT||py>=bodyBottom()-22)return super.mouseClicked(x,y,button);
        Hit best=null;int distance=Integer.MAX_VALUE;
        for(Hit hit:hits)if(px>=hit.x&&px<hit.x+hit.w&&py>=hit.y&&py<hit.y+hit.h){
            int d=Math.abs(py-hit.y);if(d<distance){distance=d;best=hit;}}
        if(best==null)return super.mouseClicked(x,y,button);
        if(best.stream>=0){selectedStream=best.stream;page=2;results=1;offset=1+state.displayResult().orElseThrow().streams().size()*2;}
        else if(best.node==-2){selectedNode=Math.clamp((py-best.y)*(stageCount()+1)/best.h,0,stageCount()+1);page=0;inspectorOnly=true;offset=0;}
        else{selectedNode=best.node;inspectorOnly=true;offset=0;}
        rebuild();return true;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(page==0&&!(getFocused() instanceof EditBox)){
            if(key==GLFW.GLFW_KEY_UP){selectNode(selectedNode-1);return true;}
            if(key==GLFW.GLFW_KEY_DOWN){selectNode(selectedNode+1);return true;}}
        return super.keyPressed(key,scan,modifiers);
    }
}
