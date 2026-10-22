package aop.ui.client;

import aop.ui.api.UiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class ThemeButton extends Button {
    private final UiTheme theme;
    private final boolean selected;
    private String icon;private boolean dropdown,modIcon,navigation,iconOnly;
    ThemeButton(int x,int y,int w,int h,Component text,UiTheme theme,boolean selected,OnPress press) {
        super(x,y,w,h,text,press,DEFAULT_NARRATION);this.theme=theme;this.selected=selected;
    }
    ThemeButton icon(String name){icon=name;return this;}
    ThemeButton iconOnly(){iconOnly=true;return this;}
    ThemeButton dropdown(){dropdown=true;return this;}
    ThemeButton modIcon(){modIcon=true;return this;}
    ThemeButton navigation(){navigation=true;return this;}
    @Override protected void renderWidget(GuiGraphics g,int mx,int my,float tick) {
        if(navigation && !selected && !isHoveredOrFocused())ThemeRenderer.material(g,theme,getX(),getY(),width,height,(theme.surface()&0x00ffffff)|0x28000000);
        else ThemeRenderer.panel(g,theme,(selected||isHoveredOrFocused())&&active?"selected":"button",getX(),getY(),width,height);
        int color=active?theme.text():theme.muted();
        boolean leading=icon!=null||modIcon;
        if(leading){int size=modIcon?18:12,ix=getX()+(iconOnly?(width-size)/2:modIcon?4:5),iy=getY()+(height-size)/2;if(modIcon)ThemeRenderer.modIcon(g,theme,ix,iy,size);else ThemeRenderer.icon(g,theme,icon,ix,iy,12,12,color);}
        if(dropdown)ThemeRenderer.icon(g,theme,"chevron",getX()+width-16,getY()+(height-12)/2,12,12,color);
        if(!iconOnly&&!getMessage().getString().isEmpty()) {
            if(navigation)g.drawString(Minecraft.getInstance().font,Minecraft.getInstance().font.plainSubstrByWidth(getMessage().getString(),width-28),getX()+22,getY()+(height-8)/2,color,false);
            else renderScrollingString(g,Minecraft.getInstance().font,getMessage(),getX()+(modIcon?27:leading?21:5),getY(),getX()+width-(dropdown?19:5),getY()+height,color);
        }
        if(isFocused())g.fill(getX()+4,getY()+height-3,getX()+width-4,getY()+height-2,theme.accent());
    }
}
