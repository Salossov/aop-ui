package aop.ui.client;

import aop.ui.api.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Themed frame around Minecraft's native text editing, selection and narration. */
final class ThemeEditBox extends EditBox {
    private final UiTheme theme;
    private boolean invalid;

    ThemeEditBox(Font font,int x,int y,int width,int height,Component label,UiTheme theme) {
        super(font,x,y,width,height,label);
        this.theme=theme;
        setTextColor(theme.text());
        setTextColorUneditable(theme.muted());
    }

    void setInvalid(boolean invalid) {
        this.invalid=invalid;
        setTextColor(invalid?0xffff8e8e:theme.text());
    }

    @Override public void renderWidget(GuiGraphics g,int mouseX,int mouseY,float tick) {
        ThemeRenderer.panel(g,theme,"field",getX(),getY(),width,height);
        if(invalid||isFocused())g.fill(getX()+4,getY()+height-2,getRight()-4,getY()+height-1,invalid?0xffff8e8e:theme.accent());
        // Match the native bordered field's four-pixel inset while omitting its vanilla frame.
        int outerWidth=width;
        g.pose().pushPose();
        g.pose().translate(4,(height-8)/2,0);
        setBordered(false);
        setWidth(outerWidth-8);
        try { super.renderWidget(g,mouseX-4,mouseY-(height-8)/2,tick); }
        finally { setWidth(outerWidth);setBordered(true);g.pose().popPose(); }
    }
}
