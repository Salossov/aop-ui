package aop.ui.client;

import aop.ui.api.*;
import aop.ui.config.*;
import com.google.gson.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;

/** Shared native screen. Only server edits are drafted; client values save as soon as valid. */
public final class SettingsScreen extends Screen {
    private record Tab(ConfigDocument document,String category) { String key() { return document.id()+"/"+category; } }
    private record Row(ConfigDocument document,ConfigOption option,int y,State state) { }
    private static final class State {
        JsonObject snapshot; boolean editable; boolean loading; String error="";
        final JsonObject changes=new JsonObject();
    }
    private final Screen parent;
    private String modId,category="",query="";
    private boolean remote,saving,closeAfterSave;
    private static final int ROW_HEIGHT=36;
    private int side,top,bottom,offset,totalRows,navTop,heroHeight;
    private int searchX,searchY,searchWidth,headingY;
    private int contentRight,helpX,helpWidth,helpOffset,helpMaxOffset;
    private Row helpRow;
    private GuiEventListener helpFocus;
    private boolean choosingMod;
    private long transitionStarted;
    private Component error=Component.empty();
    private final Map<String,State> states=new HashMap<>();
    private final Map<String,String> invalidInputs=new HashMap<>();
    private final List<Row> rows=new ArrayList<>();
    private final List<GuiEventListener> rowWidgets=new ArrayList<>();
    private EditBox search;
    private ThemeButton clearSearch;

    public SettingsScreen(Screen parent,String modId) { super(Component.literal("AOP: UI"));this.parent=parent;this.modId=modId; }
    private SettingsRegistry.Provider provider() {
        var p=SettingsRegistry.provider(modId);
        if(p==null && !SettingsRegistry.providers().isEmpty()) { p=SettingsRegistry.providers().getFirst();modId=p.modId(); }
        return p;
    }
    private UiTheme theme() { var p=provider();return p==null?UiTheme.THAUMCRAFT:p.theme(); }
    private Component tr(String key) { return Component.translatable(key); }
    private String stateKey(ConfigDocument d) { return (remote?"R:":"L:")+d.id(); }
    private List<Tab> tabs() {
        List<Tab> result=new ArrayList<>();
        for(var d:NeoForgeConfigs.documents(modId)) {
            boolean included=remote ? d.scope()!=ModConfig.Type.CLIENT : d.scope()!=ModConfig.Type.SERVER;
            if(included) d.options().stream().map(ConfigOption::category).distinct().forEach(c->result.add(new Tab(d,c)));
        }
        return result;
    }
    private Component categoryLabel(Tab tab) {
        String key=SettingsRegistry.categoryTranslation(tab.document().modId(),tab.category());
        return Component.literal((I18n.exists(key)?I18n.get(key):humanize(tab.category()))+(tab.document().scope()==ModConfig.Type.STARTUP?"*":""));
    }
    private static String humanize(String value) {
        String s=value.replace('_',' ').replaceAll("([a-z])([A-Z])","$1 $2");
        return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);
    }
    private Component label(ConfigDocument d,ConfigOption option) {
        String key=option.translationKey(d.modId());
        if(I18n.exists(key)) return Component.translatable(key);
        String[] parts=option.path().split("\\.");
        String fallback=parts[parts.length-1];
        if((fallback.equals("enabled") || fallback.equals("id") || fallback.equals("show")) && parts.length>1) fallback=parts[parts.length-2];
        return Component.literal(humanize(fallback));
    }
    private Component description(ConfigDocument d,ConfigOption option) {
        String key=option.translationKey(d.modId())+".tooltip";
        return I18n.exists(key)?Component.translatable(key):Component.literal(option.description());
    }
    private State state(ConfigDocument d) {
        var s=states.computeIfAbsent(stateKey(d),k->new State());
        if(!remote) {
            if(d.spec().isLoaded()) { s.snapshot=d.snapshot();s.editable=true;s.error=""; }
            else s.error="aopui.error.unavailable";
        } else if(!SettingsClient.connected()) { s.error="aopui.error.no_world";s.editable=false; }
        else if(s.snapshot==null && !s.loading && s.error.isEmpty()) {
            s.loading=true;
            SettingsClient.request("read",d.id(),null,null,reply->{
                s.loading=false;
                if(reply.get("ok").getAsBoolean()) { s.snapshot=reply.getAsJsonObject("snapshot");s.editable=reply.get("editable").getAsBoolean();s.error=""; }
                else s.error=reply.get("error").getAsString();
                if(minecraft.screen==this) rebuildWidgets();
            });
        }
        return s;
    }
    private JsonElement value(State s,ConfigOption o) {
        return s.changes.has(o.path())?s.changes.get(o.path()):s.snapshot.getAsJsonObject("values").get(o.path());
    }
    private ThemeButton button(int x,int y,int w,int h,Component title,boolean selected,Button.OnPress action) {
        var button=new ThemeButton(x,y,w,h,title,theme(),selected,action);button.active=!saving;return addRenderableWidget(button);
    }
    private boolean navigate(Runnable action) {
        if(!invalidInputs.isEmpty()) { error=tr("aopui.error.invalid");return false; }
        if(saving) return false;
        error=Component.empty();action.run();offset=0;transitionStarted=net.minecraft.Util.getMillis();rebuildWidgets();return true;
    }
    @Override protected void init() {
        rows.clear();rowWidgets.clear();
        side=Math.clamp(width/4,104,160);bottom=height-46;
        int left=side+14,right=width-14,contentWidth=right-left;
        helpWidth=contentWidth>=400?Math.clamp(contentWidth/3,140,200):0;
        helpX=right-helpWidth;contentRight=helpWidth>0?helpX-12:width-12;
        helpRow=null;helpFocus=null;helpOffset=0;
        boolean stacked=contentWidth<300;
        top=stacked?94:86;headingY=stacked?10:19;
        searchWidth=stacked?contentWidth:Math.min(190,contentWidth/2);
        searchX=right-searchWidth;searchY=stacked?26:12;
        search=new EditBox(font,searchX+23,searchY+7,searchWidth-45,14,tr("aopui.search"));
        search.setBordered(false);search.setTextColor(theme().text());search.setTextColorUneditable(theme().muted());
        search.setMaxLength(128);search.setHint(tr("aopui.search"));search.setValue(query);
        addRenderableWidget(search);
        clearSearch=button(searchX+searchWidth-20,searchY+3,16,16,tr("aopui.search.clear"),false,b->{search.setValue("");setFocused(search);search.setFocused(true);}).icon("close").iconOnly();
        clearSearch.setTooltip(Tooltip.create(tr("aopui.search.clear")));clearSearch.visible=!query.isEmpty();
        search.setResponder(v->{query=v;clearSearch.visible=!v.isEmpty();offset=0;rebuildRows();});
        heroHeight=height>=260?Math.min(side-16,height/3):0;
        navTop=heroHeight>0?8+heroHeight+6:28;
        var selected=provider();
        if(selected!=null)button(8,navTop,side-16,24,Component.literal(selected.name()),choosingMod,b->navigate(()->choosingMod=!choosingMod)).modIcon().dropdown();
        int scopeY=stacked?54:42;
        button(left,scopeY,(contentWidth-4)/2,22,tr("aopui.local"),!remote,b->navigate(()->{remote=false;category="";})).icon("local");
        var server=button(left+(contentWidth+4)/2,scopeY,(contentWidth-4)/2,22,tr("aopui.server"),remote,b->navigate(()->{remote=true;category="";})).icon("server");
        server.active=!saving&&SettingsClient.connected();server.setTooltip(Tooltip.create(tr(SettingsClient.connected()?"aopui.server.help":"aopui.error.no_world")));
        var tabs=tabs();
        if(tabs.stream().noneMatch(t->t.key().equals(category))&&!tabs.isEmpty())category=tabs.stream().filter(t->t.category().equals("interface")).findFirst().orElse(tabs.getFirst()).key();
        int navY=navTop+32;
        if(choosingMod){
            for(var p:SettingsRegistry.providers()) {
                var choice=new ThemeButton(8,navY,side-16,25,Component.literal(p.name()),p.theme(),p.modId().equals(modId),b->navigate(()->{modId=p.modId();category="";choosingMod=false;})).modIcon();
                choice.active=!saving;addRenderableWidget(choice);navY+=29;
            }
        }else{
            int available=Math.max(0,(height-36-navY)/26);
            if(tabs.size()<=available)for(var tab:tabs){
                button(8,navY,side-16,24,categoryLabel(tab),tab.key().equals(category),b->navigate(()->category=tab.key())).icon(categoryIcon(tab.category())).navigation();navY+=26;
            }else if(!tabs.isEmpty()){
                Tab current=tabs.stream().filter(t->t.key().equals(category)).findFirst().orElse(tabs.getFirst());
                int anchor=Math.min(navY,height-66);
                button(8,anchor,side-16,24,categoryLabel(current),true,b->minecraft.setScreen(new DropdownMenu(this,tr("aopui.categories"),theme(),tabs.stream().map(Tab::key).toList(),key->categoryLabel(tabs.stream().filter(t->t.key().equals(key)).findFirst().orElseThrow()),category,8,anchor+24,side-16,key->navigate(()->category=key)))).icon(categoryIcon(current.category())).dropdown();
            }
        }
        button(width-110,height-30,96,22,tr(dirty()||remote?"aopui.save":"gui.done"),true,b->saveAndClose()).icon(dirty()||remote?"save":"check");
        button(left,height-30,Math.min(96,Math.max(64,width-side-130)),22,tr("aopui.refresh"),false,b->refresh()).icon("refresh");
        addRows();
    }
    private static String categoryIcon(String category){
        return switch(category){case "interface","models","effects","research","world","compatibility"->category;case "runicShield"->"shield";default->"general";};
    }
    private String parentPath(ConfigDocument d,ConfigOption o){
        var parent=SettingsRegistry.dependency(d.modId(),d.spec(),o.path());return parent==null?null:String.join(".",parent.getPath());
    }
    private int depth(ConfigDocument d,ConfigOption o){
        String parent=parentPath(d,o);int depth=0;
        while(parent!=null){depth++;var next=SettingsRegistry.dependency(d.modId(),d.spec(),parent);parent=next==null?null:String.join(".",next.getPath());}
        return depth;
    }
    private boolean available(ConfigDocument d,ConfigOption o,State s){
        var gate=SettingsRegistry.dependency(d.modId(),d.spec(),o.path());
        while(gate!=null){
            String path=String.join(".",gate.getPath());
            var stored=s.changes.has(path)?s.changes.get(path):s.snapshot.getAsJsonObject("values").get(path);
            if(stored==null||!stored.getAsBoolean())return false;
            gate=SettingsRegistry.dependency(d.modId(),d.spec(),path);
        }
        return true;
    }
    private String hierarchyKey(ConfigDocument d,ConfigOption o){
        String key=o.path();var gate=SettingsRegistry.dependency(d.modId(),d.spec(),o.path());
        while(gate!=null){String path=String.join(".",gate.getPath());key=path+"/"+key;gate=SettingsRegistry.dependency(d.modId(),d.spec(),path);}
        return key;
    }
    private void rebuildRows() {
        // Do not recreate the active input: selection, caret and horizontal scrolling belong to it.
        for(var widget:rowWidgets)removeWidget(widget);
        rowWidgets.clear();rows.clear();helpRow=null;helpOffset=0;addRows();
    }
    private int controlWidth(){return Math.min(160,Math.max(72,(contentRight-side-20)/2));}
    private Tooltip optionTooltip(Component help){return helpWidth>0?null:Tooltip.create(help);}
    private void addRows() {
        var chrome=new HashSet<>(children());
        String normalized=query.strip().toLowerCase(Locale.ROOT);
        String[] terms=normalized.isEmpty()?new String[0]:normalized.split("\\s+");
        List<Map.Entry<Tab,ConfigOption>> filtered=new ArrayList<>();
        for(var tab:tabs()) {
            if(terms.length==0 && !tab.key().equals(category)) continue;
            for(var o:tab.document().options()) if(o.category().equals(tab.category())) {
                String searchable=(label(tab.document(),o).getString()+" "+description(tab.document(),o).getString()+" "+categoryLabel(tab).getString()+" "+o.path()).toLowerCase(Locale.ROOT);
                if(Arrays.stream(terms).allMatch(searchable::contains)) filtered.add(Map.entry(tab,o));
            }
        }
        filtered.sort(Comparator.comparing((Map.Entry<Tab,ConfigOption> e)->e.getKey().key()).thenComparing(e->hierarchyKey(e.getKey().document(),e.getValue())));
        totalRows=filtered.size();int count=Math.max(1,(bottom-top)/ROW_HEIGHT);
        offset=Math.clamp(offset,0,Math.max(0,totalRows-count));
        for(int i=offset;i<Math.min(filtered.size(),offset+count);i++) {
            var entry=filtered.get(i);var d=entry.getKey().document();var o=entry.getValue();var s=state(d);int y=top+(i-offset)*ROW_HEIGHT;
            rows.add(new Row(d,o,y,s));
            if(s.snapshot==null) continue;
            int controlWidth=controlWidth();int x=contentRight-controlWidth-26;
            boolean enabled=available(d,o,s);
            var current=value(s,o);
            var tooltip=label(d,o).copy().append("\n").append(description(d,o)).append("\n").append(o.path());
            if(!enabled)tooltip.append("\n").append(tr("aopui.dependency.disabled"));
            if(o.restart()) tooltip.append("\n").append(tr("aopui.restart"));
            var reset=button(contentRight-22,y+7,22,22,tr("aopui.reset"),false,b->{change(d,o,o.encode(o.value().getDefault()));rebuildWidgets();}).icon("reset").iconOnly();
            reset.active=s.editable&&enabled&&!saving;reset.setTooltip(Tooltip.create(tr("aopui.reset").copy().append(": ").append(label(d,o))));
            if(o.kind().equals("boolean")) {
                var toggle=button(x,y+7,controlWidth,22,tr(current.getAsBoolean()?"options.on":"options.off"),current.getAsBoolean(),b->{change(d,o,new JsonPrimitive(!value(s,o).getAsBoolean()));rebuildWidgets();});
                toggle.active=s.editable&&enabled&&!saving;toggle.setTooltip(optionTooltip(tooltip));
            } else if(o.kind().equals("enum")) {
                var choice=button(x,y+7,controlWidth,22,enumLabel(o,current.getAsString()),false,b->minecraft.setScreen(new DropdownMenu(this,label(d,o),theme(),o.choices(),v->enumLabel(o,v),current.getAsString(),x,y+29,controlWidth,v->change(d,o,new JsonPrimitive(v))))).dropdown();
                choice.active=s.editable&&enabled&&!saving;choice.setTooltip(optionTooltip(tooltip));
            } else if(o.kind().equals("list")) {
                var edit=button(x,y+7,controlWidth,22,Component.translatable("aopui.list",current.getAsJsonArray().size()),false,b->minecraft.setScreen(new ListEditorScreen(this,label(d,o),theme(),value(s,o).getAsJsonArray(),v->{o.decode(v);if(!change(d,o,v))throw new IllegalArgumentException();})));
                edit.active=s.editable&&enabled&&!saving;edit.setTooltip(optionTooltip(tooltip));
            } else if(o.slider()) {
                var slider=addRenderableWidget(new ValueSlider(x,y+7,controlWidth,22,d,o,s,current.getAsDouble()));slider.active=s.editable&&enabled&&!saving;slider.setTooltip(optionTooltip(tooltip));
            } else {
                var field=new ThemeEditBox(font,x,y+7,controlWidth,22,label(d,o),theme());field.setMaxLength(o.kind().equals("string")?4096:32);
                String inputKey=stateKey(d)+"/"+o.path();
                field.setValue(invalidInputs.getOrDefault(inputKey,current.getAsString()));field.setEditable(s.editable&&enabled&&!saving);field.setTooltip(optionTooltip(tooltip));
                field.setInvalid(invalidInputs.containsKey(inputKey));
                field.setResponder(text->{
                    try {
                        JsonElement v=o.kind().equals("string")?new JsonPrimitive(text):new JsonPrimitive(new java.math.BigDecimal(text));
                        o.decode(v);if(change(d,o,v)){invalidInputs.remove(inputKey);field.setInvalid(false);}
                    } catch(RuntimeException e) {invalidInputs.put(inputKey,text);field.setInvalid(true);error=tr("aopui.error.invalid");}
                });addRenderableWidget(field);
            }
        }
        children().stream().filter(widget->!chrome.contains(widget)).forEach(rowWidgets::add);
    }
    private Component enumLabel(ConfigOption o,String name) {
        if(o.value().getDefault() instanceof Enum<?> e) {
            var v=Arrays.stream(e.getDeclaringClass().getEnumConstants()).filter(c->c.name().equals(name)).findFirst().orElse(null);
            if(v instanceof net.neoforged.neoforge.common.TranslatableEnum t) return t.getTranslatedName();
        }
        String key="aopui.enum."+name.toLowerCase(Locale.ROOT);
        return I18n.exists(key)?tr(key):Component.literal(humanize(name.toLowerCase(Locale.ROOT)));
    }
    private boolean change(ConfigDocument d,ConfigOption o,JsonElement v) {
        var s=state(d);
        if(!s.editable || saving) return false;
        try {
            Object decoded=o.decode(v);JsonElement canonical=o.encode(decoded);
            if(remote) {
                if(canonical.equals(s.snapshot.getAsJsonObject("values").get(o.path()))) s.changes.remove(o.path());else s.changes.add(o.path(),canonical);
            } else {
                var changes=new JsonObject();changes.add(o.path(),canonical);
                d.apply(s.snapshot.get("revision").getAsString(),changes);s.snapshot=d.snapshot();
            }
            error=Component.empty();return true;
        } catch(RuntimeException e) { error=tr(e.getMessage()!=null&&e.getMessage().startsWith("aopui.error.")?e.getMessage():"aopui.error.invalid");return false; }
    }
    private boolean dirty() {return states.entrySet().stream().anyMatch(e->e.getKey().startsWith("R:")&&!e.getValue().changes.isEmpty());}
    private void refresh() {
        Runnable reload=()->{states.entrySet().removeIf(e->e.getKey().startsWith("R:"));error=Component.empty();rebuildWidgets();};
        if(dirty()) minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes->{minecraft.setScreen(this);if(yes)reload.run();},tr("aopui.discard.title"),tr("aopui.discard.help")));
        else reload.run();
    }
    private void saveAndClose() {
        if(saving)return;
        if(!invalidInputs.isEmpty()){error=tr("aopui.error.invalid");return;}
        closeAfterSave=true;saveNext();
    }
    private void saveNext() {
        var pending=states.entrySet().stream().filter(e->e.getKey().startsWith("R:")&&!e.getValue().changes.isEmpty()).findFirst();
        if(pending.isEmpty()){saving=false;if(closeAfterSave)minecraft.setScreen(parent);return;}
        if(!SettingsClient.connected()){saving=false;error=tr("aopui.error.connection");return;}
        var e=pending.get();var s=e.getValue();saving=true;error=tr("aopui.saving");rebuildWidgets();
        try {
            SettingsClient.request("save",e.getKey().substring(2),s.snapshot.get("revision").getAsString(),s.changes.deepCopy(),reply->{
                saving=false;
                if(reply.get("ok").getAsBoolean()) {
                    s.snapshot=reply.getAsJsonObject("snapshot");s.changes.entrySet().clear();s.editable=reply.get("editable").getAsBoolean();
                    saveNext();
                } else {closeAfterSave=false;error=tr(reply.get("error").getAsString());if(minecraft.screen==this)rebuildWidgets();}
            });
        } catch(RuntimeException ex) {saving=false;closeAfterSave=false;error=tr("aopui.error.too_large");rebuildWidgets();}
    }
    @Override public void onClose() {saveAndClose();}
    @Override public boolean shouldCloseOnEsc() {return !saving;}
    @Override public void tick() {SettingsClient.tickPending();}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float tick) { }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(helpWidth>0&&x>=helpX&&y>=top&&y<bottom){helpOffset=Math.clamp(helpOffset-3*(int)Math.signum(vertical),0,helpMaxOffset);return true;}
        if(y>=top && y<bottom && x>side && x<contentRight && !saving) {offset=Math.clamp(offset-(int)Math.signum(vertical),0,Math.max(0,totalRows-Math.max(1,(bottom-top)/ROW_HEIGHT)));rebuildWidgets();return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(button==0&&x>=searchX&&x<searchX+searchWidth&&y>=searchY&&y<searchY+22) {
            if(clearSearch.visible&&clearSearch.isMouseOver(x,y))return super.mouseClicked(x,y,button);
            setFocused(search);search.setFocused(true);
            if(search.isMouseOver(x,y))search.mouseClicked(x,y,button);
            return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE&&search.isFocused()&&!query.isEmpty()){search.setValue("");return true;}
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE&&choosingMod){navigate(()->choosingMod=false);return true;}
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_F && hasControlDown()){setFocused(search);search.setFocused(true);return true;}
        if((key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN || key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) && !saving) {
            offset+=key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN?Math.max(1,(bottom-top)/ROW_HEIGHT):-Math.max(1,(bottom-top)/ROW_HEIGHT);rebuildWidgets();return true;
        }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float tick) {
        var t=theme();ThemeRenderer.panel(g,t,"panel",0,0,side,height);ThemeRenderer.panel(g,t,"panel",side+2,0,width-side-2,height);
        if(heroHeight>0)ThemeRenderer.emblem(g,t,8,8,side-16,heroHeight);
        else g.drawCenteredString(font,title,side/2,12,t.accent());
        var p=provider();String heading=p==null?"AOP: UI":p.name();
        int headingX=side+14;
        int headingRight=headingY==10?width-14:searchX-10;
        int headingWidth=Math.max(12,headingRight-headingX);
        float headingScale=1.25f;
        var headingText=Component.literal(heading).withStyle(net.minecraft.ChatFormatting.BOLD);
        var fittedHeading=Component.literal(font.substrByWidth(headingText,(int)(headingWidth/headingScale)).getString()).withStyle(net.minecraft.ChatFormatting.BOLD);
        g.pose().pushPose();g.pose().translate(headingX,headingY-2,0);g.pose().scale(headingScale,headingScale,1);
        g.drawString(font,fittedHeading,0,0,t.accent(),false);g.pose().popPose();
        ThemeRenderer.panel(g,t,"field",searchX,searchY,searchWidth,22);
        ThemeRenderer.icon(g,t,"search",searchX+6,searchY+5,12,12,search.isFocused()?t.accent():t.muted());
        if(search.isFocused())g.fill(searchX+4,searchY+20,searchX+searchWidth-4,searchY+21,t.accent());
        String section=tabs().stream().filter(tab->tab.key().equals(category)).findFirst().map(tab->categoryLabel(tab).getString()).orElse("");
        ThemeRenderer.divider(g,t,side+14,top-6,contentRight-side-14);
        if(!query.isBlank())section=tr("aopui.search.results").getString();
        g.drawString(font,section,side+20,top-17,t.muted(),false);
        boolean focusChanged=getFocused()!=helpFocus;helpFocus=getFocused();Row hovered=null,focused=null;
        for(var row:rows){
            int x=side+12,y=row.y();var o=row.option();var s=row.state();
            int indent=Math.min(3,depth(row.document(),o))*10;
            boolean enabled=s.snapshot==null||available(row.document(),o,s);
            ThemeRenderer.panel(g,t,"row",x,y,contentRight-x,ROW_HEIGHT);
            int textWidth=Math.max(20,contentRight-controlWidth()-26-x-14-indent);
            if(indent>0){g.fill(x+8,y+3,x+9,y+ROW_HEIGHT-2,t.edge());g.fill(x+8,y+16,x+14,y+17,t.edge());}
            var labelLines=font.split(label(row.document(),o),textWidth);
            for(int line=0;line<Math.min(2,labelLines.size());line++)g.drawString(font,labelLines.get(line),x+8+indent,y+(labelLines.size()>1?4:8)+line*10,enabled?t.text():t.muted(),false);
            Component note=s.error.isEmpty()?(s.loading?tr("aopui.loading"):o.restart()?tr("aopui.restart"):Component.empty()):tr(s.error);
            if(!enabled)note=tr("aopui.dependency.disabled");
            if(s.snapshot!=null&&remote&&!s.editable)note=tr("aopui.readonly");
            if(s.snapshot!=null&&o.restart()&&!value(s,o).equals(s.snapshot.getAsJsonObject("effective").get(o.path())))note=tr("aopui.pending_restart");
            boolean restartNote=o.restart()&&s.error.isEmpty()&&!s.loading&&enabled&&(!remote||s.editable);
            if(restartNote){
                float scale=.75f;
                var smallNote=Component.literal(font.plainSubstrByWidth(note.getString(),(int)(textWidth/scale))).withStyle(net.minecraft.ChatFormatting.UNDERLINE);
                g.pose().pushPose();g.pose().translate(x+8+indent,y+(labelLines.size()>1?25:22),0);g.pose().scale(scale,scale,1);
                g.drawString(font,smallNote,0,0,t.muted(),false);g.pose().popPose();
            }else g.drawString(font,font.plainSubstrByWidth(note.getString(),textWidth),x+8+indent,y+(labelLines.size()>1?25:22),t.muted(),false);
            if(mx>=x&&mx<contentRight&&my>=y&&my<y+ROW_HEIGHT)hovered=row;
            if(getFocused() instanceof AbstractWidget widget&&widget.getX()>side&&widget.getY()>=y&&widget.getY()<y+ROW_HEIGHT)focused=row;
            if(helpWidth==0&&mx>=x&&mx<x+textWidth+indent&&my>=y&&my<y+ROW_HEIGHT){
                var help=label(row.document(),o).copy().append("\n").append(description(row.document(),o)).append("\n").append(o.path());
                if(!enabled)help.append("\n").append(tr("aopui.dependency.disabled"));
                if(s.snapshot!=null&&o.restart())help.append("\n").append(Component.translatable("aopui.effective",s.snapshot.getAsJsonObject("effective").get(o.path()).toString()));
                setTooltipForNextRenderPass(font.split(help,Math.min(300,width-24)));
            }
        }
        Row activeHelp=hovered!=null?hovered:focusChanged?focused:null;
        if(activeHelp!=null){if(helpRow==null||!helpRow.option().path().equals(activeHelp.option().path()))helpOffset=0;helpRow=activeHelp;}
        if(helpWidth>0)renderHelp(g);
        if(rows.isEmpty())g.drawCenteredString(font,tr("aopui.empty"),(side+contentRight)/2,top+16,t.muted());
        int count=Math.max(1,(bottom-top)/ROW_HEIGHT);
        if(totalRows>count){int h=Math.max(12,(bottom-top)*count/totalRows),y=top+(bottom-top-h)*offset/Math.max(1,totalRows-count);g.fill(contentRight+3,top,contentRight+5,bottom,0xff101111);g.fill(contentRight+3,y,contentRight+5,y+h,t.edge());}
        g.drawString(font,font.plainSubstrByWidth(error.getString(),Math.max(10,width-side-28)),side+14,height-43,saving?t.accent():0xffff9292,false);
        super.render(g,mx,my,tick);
        // Short crossfade, no moving targets: controls retain their actual click bounds.
        float progress=Math.clamp((net.minecraft.Util.getMillis()-transitionStarted)/160f,0f,1f);
        if(progress<1){int alpha=(int)(95*(1-progress)*(1-progress));g.fill(5,navTop+28,side-5,height-6,alpha<<24);g.fill(side+6,top,width-8,bottom,alpha<<24);}
    }
    private void renderHelp(GuiGraphics g){
        var t=theme();int x=helpX+8,w=helpWidth-16;
        g.fill(helpX-5,top-6,helpX-4,bottom,0x700b0c0d);
        g.fill(helpX-4,top-6,helpX-3,bottom,(t.edge()&0x00ffffff)|0x99000000);
        ThemeRenderer.material(g,t,helpX,top-6,helpWidth,bottom-top+6,(t.surface()&0x00ffffff)|0x28000000);
        Component title=helpRow==null?tr("aopui.help.title"):label(helpRow.document(),helpRow.option());
        var titleLines=font.split(title.copy().withStyle(net.minecraft.ChatFormatting.BOLD),w);
        int y=top+4;for(var line:titleLines){g.drawString(font,line,x,y,t.accent(),false);y+=font.lineHeight+2;}
        y+=6;ThemeRenderer.divider(g,t,x,y,w);y+=10;
        var body=tr("aopui.help.hint").copy();
        if(helpRow!=null){
            var d=helpRow.document();var o=helpRow.option();var s=helpRow.state();
            body=description(d,o).copy();if(body.getString().isBlank())body=tr("aopui.help.no_description").copy();
            body=body.copy();
            if(s.snapshot!=null&&!available(d,o,s))body.append("\n\n").append(tr("aopui.dependency.disabled"));
            if(o.restart())body.append("\n\n").append(tr("aopui.restart"));
            if(s.snapshot!=null&&remote&&!s.editable)body.append("\n\n").append(tr("aopui.readonly"));
            if(s.snapshot!=null&&o.restart()&&!value(s,o).equals(s.snapshot.getAsJsonObject("effective").get(o.path())))body.append("\n\n").append(Component.translatable("aopui.effective",s.snapshot.getAsJsonObject("effective").get(o.path()).toString()));
        }
        var lines=font.split(body,w);int visible=Math.max(1,(bottom-y-6)/(font.lineHeight+2));
        helpMaxOffset=Math.max(0,lines.size()-visible);helpOffset=Math.clamp(helpOffset,0,helpMaxOffset);
        g.enableScissor(x,y,helpX+helpWidth-6,bottom-4);
        for(int i=helpOffset;i<Math.min(lines.size(),helpOffset+visible);i++)g.drawString(font,lines.get(i),x,y+(i-helpOffset)*(font.lineHeight+2),t.text(),false);
        g.disableScissor();
        if(helpMaxOffset>0){int h=Math.max(12,(bottom-y-4)*visible/lines.size()),sy=y+(bottom-y-4-h)*helpOffset/helpMaxOffset;g.fill(helpX+helpWidth-3,sy,helpX+helpWidth-1,sy+h,t.edge());}
    }
    private final class ValueSlider extends AbstractSliderButton {
        private final ConfigDocument document;private final ConfigOption option;private final State state;
        ValueSlider(int x,int y,int w,int h,ConfigDocument d,ConfigOption o,State s,double initial) {
            super(x,y,w,h,Component.empty(),(initial-o.minimum())/(o.maximum()-o.minimum()));document=d;option=o;state=s;updateMessage();
        }
        private double actual() {double n=option.minimum()+value*(option.maximum()-option.minimum());return option.kind().equals("integer")?Math.rint(n):Math.round(n*100d)/100d;}
        @Override protected void updateMessage() {double v=actual();setMessage(Component.literal(option.kind().equals("integer")?Long.toString(Math.round(v)):String.format(Locale.ROOT,"%.2f",v)));}
        @Override protected void applyValue() {change(document,option,new JsonPrimitive(actual()));}
        @Override public void renderWidget(GuiGraphics g,int x,int y,float tick) {
            int valueWidth=Math.min(45,width/3),trackWidth=Math.max(12,width-valueWidth-9),cy=getY()+height/2;
            g.fill(getX()+3,cy-2,getX()+trackWidth,cy+3,0xff0b0c0d);
            g.fill(getX()+3,cy+2,getX()+trackWidth,cy+3,theme().edge());
            g.fill(getX()+3,cy-1,getX()+3+(int)((trackWidth-6)*value),cy+1,active?theme().accent():theme().muted());
            int thumb=getX()+(int)((trackWidth-8)*value);ThemeRenderer.thumb(g,theme(),thumb,cy-7,active);
            ThemeRenderer.panel(g,theme(),"field",getX()+width-valueWidth,getY()+1,valueWidth,height-2);
            g.drawCenteredString(font,getMessage(),getX()+width-valueWidth/2,getY()+(height-8)/2,active?theme().text():theme().muted());
        }
        @Override public void onClick(double x,double y){setFromMouse(x);}
        @Override protected void onDrag(double x,double y,double dx,double dy){setFromMouse(x);}
        private void setFromMouse(double x){
            int trackWidth=Math.max(12,width-Math.min(45,width/3)-9);
            double next=Math.clamp((x-getX()-4)/(trackWidth-8),0d,1d);
            if(next!=value){value=next;applyValue();}updateMessage();
        }
    }
}
