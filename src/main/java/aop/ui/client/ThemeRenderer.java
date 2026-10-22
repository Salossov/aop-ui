package aop.ui.client;

import aop.ui.api.UiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

final class ThemeRenderer {
    static ResourceLocation texture(UiTheme t,String name) { return ResourceLocation.fromNamespaceAndPath(t.namespace(),"textures/gui/"+t.textureFolder()+"/"+name+".png"); }
    private static ResourceLocation shared(String name) {return ResourceLocation.fromNamespaceAndPath("aop_ui","textures/gui/shared/"+name+".png");}
    private static void tint(GuiGraphics g,int color) {g.setColor(((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,((color>>>24)&255)/255f);}
    private static int brighten(int color,int amount) {
        return 0xff000000 | Math.clamp(((color>>16)&255)+amount,0,255)<<16 | Math.clamp(((color>>8)&255)+amount,0,255)<<8 | Math.clamp((color&255)+amount,0,255);
    }
    static void panel(GuiGraphics g,UiTheme t,String name,int x,int y,int w,int h) {
        if(w<=0||h<=0)return;
        if(!t.sharedMasks()){legacyPanel(g,t,name,x,y,w,h);return;}
        boolean selected=name.equals("selected"),field=name.equals("field"),row=name.equals("row");
        int color=field?brighten(t.surface(),-10):selected?brighten(t.surface(),19):t.surface();
        if(name.equals("panel"))color=(color&0x00ffffff)|0x99000000;
        else if(row)color=(color&0x00ffffff)|0x28000000;
        material(g,t,x,y,w,h,color);
        if(row){divider(g,t,x,y+h-1,w);return;}
        g.fill(x,y,x+w,y+1,0xff0b0c0d);g.fill(x,y+h-1,x+w,y+h,0xff0b0c0d);
        slice(g,shared("edge"),x,y,w,h,selected?t.accent():t.edge());
        slice(g,shared("bevel"),x,y,w,h,t.text());
        if(!field && !t.textureFolder().equals("compact"))slice(g,shared("trim"),x,y,w,h,selected?t.accent():t.edge());
    }
    static void material(GuiGraphics g,UiTheme t,int x,int y,int w,int h,int color) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        tint(g,color);
        for(int py=0;py<h;py+=32)for(int px=0;px<w;px+=32){int tw=Math.min(32,w-px),th=Math.min(32,h-py);g.blit(shared("grain"),x+px,y+py,0,0,tw,th,32,32);}
        g.setColor(1,1,1,1);
    }
    private static void slice(GuiGraphics g,ResourceLocation tex,int x,int y,int w,int h,int color) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        int b=Math.min(4,Math.min(w,h)/2);int[] src={0,4,12},size={4,8,4};
        int[] dx={x,x+b,x+w-b},dy={y,y+b,y+h-b},dw={b,w-2*b,b},dh={b,h-2*b,b};
        tint(g,color);
        for(int r=0;r<3;r++)for(int c=0;c<3;c++)if((r!=1||c!=1)&&dw[c]>0&&dh[r]>0)
            g.blit(tex,dx[c],dy[r],dw[c],dh[r],src[c],src[r],size[c],size[r],16,16);
        g.setColor(1,1,1,1);
    }
    private static void legacyPanel(GuiGraphics g,UiTheme t,String name,int x,int y,int w,int h) {
        int b=Math.min(12,Math.min(w,h)/2);int[] src={0,12,20},size={12,8,12};
        int[] dx={x,x+b,x+w-b},dy={y,y+b,y+h-b},dw={b,w-2*b,b},dh={b,h-2*b,b};
        for(int r=0;r<3;r++)for(int c=0;c<3;c++)if(dw[c]>0&&dh[r]>0)g.blit(texture(t,name),dx[c],dy[r],dw[c],dh[r],src[c],src[r],size[c],size[r],32,32);
    }
    static void divider(GuiGraphics g,UiTheme t,int x,int y,int w) {
        g.fill(x,y,x+w,y+1,0xff0d0e0e);g.fill(x,y+1,x+w,y+2,t.edge());
        icon(g,t,"divider",x,y-1,5,5,t.accent());
    }
    static void emblem(GuiGraphics g,UiTheme t,int x,int y,int w,int h) {
        if(t.sharedMasks()) {
            int size=Math.min(w,h);
            brand(g,t,x+(w-size)/2,y+(h-size)/2,size);
            return;
        }
        // Preserve the existing 8:5 texture contract for custom addon themes.
        int dw=Math.min(w,h*128/80),dh=dw*80/128;
        g.blit(texture(t,"emblem"),x+(w-dw)/2,y+(h-dh)/2,dw,dh,0,0,128,80,128,80);
    }
    static void modIcon(GuiGraphics g,UiTheme t,int x,int y,int size) {
        if(!t.sharedMasks()){icon(g,t,"research",x,y,size,size,t.accent());return;}
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.setColor(1,1,1,1);
        var item=ResourceLocation.fromNamespaceAndPath("aop_ui","textures/branding/items/"+t.textureFolder()+".png");
        g.blit(item,x,y,size,size,0,0,64,64,64,64);
    }
    private static void brand(GuiGraphics g,UiTheme t,int x,int y,int size) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.setColor(1,1,1,1);
        var logo=ResourceLocation.fromNamespaceAndPath("aop_ui","textures/branding/"+t.textureFolder()+".png");
        g.blit(logo,x,y,size,size,0,0,512,512,512,512);
    }
    static void icon(GuiGraphics g,UiTheme t,String name,int x,int y,int w,int h,int color) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        tint(g,color);
        if(name.equals("divider"))g.blit(shared(name),x,y,w,h,0,0,5,5,64,5);
        else if(name.equals("list"))g.blit(shared("icon_list"),x,y,w,h,0,0,16,16,16,16);
        else {
            String key=switch(name){case "local"->"client";case "chevron"->"chevron-down";default->name;};
            var image=ResourceLocation.fromNamespaceAndPath("aop_ui","textures/gui/icons/"+key+".png");
            g.blit(image,x,y,w,h,0,0,64,64,64,64);
        }
        g.setColor(1,1,1,1);
    }
    static void thumb(GuiGraphics g,UiTheme t,int x,int y,boolean active) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        tint(g,active?t.accent():t.muted());g.blit(shared("thumb"),x,y,0,0,8,14,8,14);g.setColor(1,1,1,1);
    }
    private ThemeRenderer(){}
}
