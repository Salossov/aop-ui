package aop.ui.client;

import aop.ui.api.UiTheme;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** One real field per list element; commas and empty strings retain their exact meaning. */
final class ListEditorScreen extends Screen {
    private final Screen parent; private final UiTheme theme; private final Consumer<JsonArray> save;
    private final List<String> entries=new ArrayList<>(); private int offset; private Component error=Component.empty();
    ListEditorScreen(Screen parent,Component title,UiTheme theme,JsonArray initial,Consumer<JsonArray> save) {
        super(title);this.parent=parent;this.theme=theme;this.save=save;initial.forEach(e->entries.add(e.getAsString()));
    }
    @Override protected void init() {
        int w=Math.min(440,width-32),count=Math.max(1,(height-108)/24);
        for(int i=offset;i<Math.min(entries.size(),offset+count);i++) {
            int index=i,y=36+(i-offset)*24;
            var field=new ThemeEditBox(font,(width-w)/2,y,w-30,20,Component.translatable("aopui.list.entry",index+1),theme);
            field.setMaxLength(1024);field.setValue(entries.get(i));field.setResponder(s->entries.set(index,s));addRenderableWidget(field);
            var remove=addRenderableWidget(new ThemeButton((width+w)/2-26,y,26,20,Component.translatable("aopui.remove"),theme,false,b->{entries.remove(index);offset=Math.min(offset,Math.max(0,entries.size()-count));rebuildWidgets();}).icon("remove").iconOnly());
            remove.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("aopui.remove")));
        }
        var add=addRenderableWidget(new ThemeButton((width-w)/2,height-64,90,22,Component.translatable("aopui.add"),theme,false,b->{entries.add("");offset=Math.max(0,entries.size()-count);rebuildWidgets();}).icon("add"));add.active=entries.size()<256;
        addRenderableWidget(new ThemeButton(width/2-104,height-30,100,22,Component.translatable("gui.cancel"),theme,false,b->minecraft.setScreen(parent)).icon("close"));
        addRenderableWidget(new ThemeButton(width/2+4,height-30,100,22,Component.translatable("gui.done"),theme,true,b->onClose()).icon("check"));
    }
    @Override public void onClose() {
        var result=new JsonArray();entries.forEach(result::add);
        try { save.accept(result);minecraft.setScreen(parent); }
        catch(IllegalArgumentException e) { error=Component.translatable("aopui.error.invalid"); }
    }
    @Override public boolean mouseScrolled(double x,double y,double h,double v) {offset=Math.clamp(offset-(int)Math.signum(v),0,Math.max(0,entries.size()-Math.max(1,(height-108)/24)));rebuildWidgets();return true;}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float tick) { }
    @Override public void render(GuiGraphics g,int x,int y,float tick) {
        ThemeRenderer.panel(g,theme,"panel",0,0,width,height);g.drawCenteredString(font,title,width/2,16,theme.text());g.drawCenteredString(font,error,width/2,height-78,0xffff8e8e);super.render(g,x,y,tick);
    }
}
