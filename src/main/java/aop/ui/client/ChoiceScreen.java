package aop.ui.client;

import aop.ui.api.UiTheme;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Scrollable keyboard-accessible chooser, also used for category navigation on small windows. */
final class ChoiceScreen extends Screen {
    private final Screen parent; private final UiTheme theme; private final List<String> values;
    private final java.util.function.Function<String,Component> labels; private final Consumer<String> selected;
    private int offset;
    ChoiceScreen(Screen parent,Component title,UiTheme theme,List<String> values,java.util.function.Function<String,Component> labels,Consumer<String> selected) {
        super(title);this.parent=parent;this.theme=theme;this.values=values;this.labels=labels;this.selected=selected;
    }
    @Override protected void init() {
        int w=Math.min(320,width-32), count=Math.max(1,(height-80)/24);
        for(int i=offset;i<Math.min(values.size(),offset+count);i++) {
            String v=values.get(i);
            addRenderableWidget(new ThemeButton((width-w)/2,36+(i-offset)*24,w,22,labels.apply(v),theme,false,b -> { selected.accept(v);minecraft.setScreen(parent); }));
        }
        addRenderableWidget(new ThemeButton(width/2-60,height-30,120,22,Component.translatable("gui.back"),theme,false,b->onClose()));
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        offset=Math.clamp(offset-(int)Math.signum(vertical),0,Math.max(0,values.size()-Math.max(1,(height-80)/24)));rebuildWidgets();return true;
    }
    @Override public void render(GuiGraphics g,int x,int y,float tick) {
        ThemeRenderer.panel(g,theme,"panel",0,0,width,height);g.drawCenteredString(font,title,width/2,16,theme.text());super.render(g,x,y,tick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float tick) { }
}
