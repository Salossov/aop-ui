package aop.ui.client;

import aop.ui.api.UiTheme;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Anchored modal popup; Escape returns to the editor without saving or changing a choice. */
final class DropdownMenu extends Screen {
    private final Screen parent;private final UiTheme theme;private final List<String> values;
    private final Function<String,Component> labels;private final Consumer<String> select;
    private final int anchorX,anchorY,anchorWidth;private int left,top,menuWidth,count,offset,index;
    DropdownMenu(Screen parent,Component title,UiTheme theme,List<String> values,Function<String,Component> labels,
            String current,int x,int y,int w,Consumer<String> select) {
        super(title);this.parent=parent;this.theme=theme;this.values=values;this.labels=labels;this.select=select;
        anchorX=x;anchorY=y;anchorWidth=w;index=Math.max(0,values.indexOf(current));
    }
    @Override protected void init() {
        menuWidth=Math.min(Math.max(120,anchorWidth),width-16);left=Math.clamp(anchorX,8,width-menuWidth-8);
        count=Math.max(1,Math.min(values.size(),(height-32)/22));
        top=Math.clamp(anchorY,8,Math.max(8,height-8-count*22));
        offset=Math.clamp(offset,0,Math.max(0,values.size()-count));
        if(index<offset)offset=index;if(index>=offset+count)offset=index-count+1;
        for(int i=offset;i<Math.min(values.size(),offset+count);i++) {
            int item=i;var b=addRenderableWidget(new ThemeButton(left,top+(i-offset)*22,menuWidth,22,labels.apply(values.get(i)),theme,i==index,button->choose(item)));
            if(i==index)setInitialFocus(b);
        }
    }
    private void choose(int item){select.accept(values.get(item));minecraft.setScreen(parent);}
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==GLFW.GLFW_KEY_DOWN||key==GLFW.GLFW_KEY_UP){index=Math.clamp(index+(key==GLFW.GLFW_KEY_DOWN?1:-1),0,values.size()-1);rebuildWidgets();return true;}
        if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){choose(index);return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(x<left||x>=left+menuWidth||y<top||y>=top+count*22){onClose();return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double h,double v){
        index=Math.clamp(index-(int)Math.signum(v),0,values.size()-1);rebuildWidgets();return true;
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float tick){}
    @Override public void render(GuiGraphics g,int x,int y,float tick){
        parent.render(g,-1,-1,tick);g.pose().pushPose();g.pose().translate(0,0,400);
        g.fill(left+2,top+3,left+menuWidth+3,top+count*22+3,0xa0000000);
        super.render(g,x,y,tick);g.pose().popPose();
    }
}
