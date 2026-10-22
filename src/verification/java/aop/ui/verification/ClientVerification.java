package aop.ui.verification;

import aop.ui.api.*;
import aop.ui.client.*;
import aop.ui.config.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Runs inside the real Minecraft client, without taking control of desktop input. */
@EventBusSubscriber(modid="aop_ui_verification",value=Dist.CLIENT)
public final class ClientVerification {
    private static int ticks,stage,at,index,checks;private static boolean done;
    private static List<String> providers;
    private static ConfigDocument enumDocument;private static ConfigOption enumOption;private static JsonObject enumBefore;
    private static final JsonArray results=new JsonArray();
    private static Path output(){return Path.of(System.getProperty("aopui.report")).getParent();}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    @SubscribeEvent public static void transparencyBackdrop(net.neoforged.neoforge.client.event.ScreenEvent.Render.Pre event){
        if(!Boolean.getBoolean("aopui.verify")||stage!=20||!(event.getScreen() instanceof SettingsScreen))return;
        // Verification-only background makes opaque panels distinguishable from correctly blended ones.
        var g=event.getGuiGraphics();int w=event.getScreen().width,h=event.getScreen().height;
        g.fill(0,0,w,h/2,0xff6699b5);g.fill(0,h/2,w,h,0xff50864a);
        for(int x=0;x<w;x+=80)g.fill(x,h/2,x+40,h/2+22,0xff466f3c);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(done||!Boolean.getBoolean("aopui.verify"))return;
        var mc=Minecraft.getInstance();ticks++;
        try {
            if(ticks>5000)throw new AssertionError("Client verification timed out");
            if(stage==0) {
                if(ticks%100==0)com.mojang.logging.LogUtils.getLogger().info("AOP UI probe screen: {}",mc.screen==null?"none":mc.screen.getClass().getSimpleName());
                if(mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")){mc.setScreen(new TitleScreen());return;}
                if(!(mc.screen instanceof TitleScreen)||ticks<100)return;
                Files.createDirectories(output());mc.options.guiScale().set(2);mc.resizeDisplay();
                providers=SettingsRegistry.providers().stream().map(SettingsRegistry.Provider::modId).toList();
                check(!providers.isEmpty(),"Providers registered");
                mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";mc.reloadResourcePacks();stage=10;at=ticks;return;
            }
            if(ticks-at<25)return;
            if(stage==10){if(mc.getOverlay()!=null)return;stage=1;at=ticks;return;}
            if(stage==1) {SettingsClient.open(new TitleScreen(),providers.get(index));focusFirstSetting(mc);stage=2;at=ticks;return;}
            if(stage==2) {
                check(mc.screen instanceof SettingsScreen,"Real settings screen");
                for(var child:mc.screen.children()) if(child instanceof Button b&&b.getMessage().getString().equals(Component.translatable("aopui.server").getString()))check(!b.active,"Server unavailable without world");
                screenshot(providers.get(index)+"-en.png");
                if(providers.get(index).equals("thaumcraft")){testSelector(mc);testSearch(mc);}
                if(providers.get(index).equals("thaumictinkerer"))testTextInput(mc);
                if(providers.get(index).equals("aop_ui_verification"))testDependencies(mc);
                var provider=SettingsRegistry.provider(providers.get(index));
                check(net.neoforged.fml.ModList.get().getModContainerById(provider.modId()).orElseThrow().getCustomExtension(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class).isPresent(),"Mods config factory");
                if(provider.modId().equals("thaumcraft")||provider.modId().equals("aop_ui_verification"))toggleAndRestore(mc,provider.modId());
                var result=new JsonObject();result.addProperty("provider",provider.modId());result.addProperty("passed",true);results.add(result);
                index++;
                if(index<providers.size()){stage=1;at=ticks;return;}
                SettingsClient.open(new TitleScreen(),providers.getFirst());mc.options.guiScale().set(4);mc.resizeDisplay();stage=3;at=ticks;return;
            }
            if(stage==3) {
                screenshot("small-gui-scale-4.png");
                // Search must rebuild rows without recursively replacing the field value.
                var search=mc.screen.children().stream().filter(c->c instanceof EditBox).map(c->(EditBox)c).findFirst().orElseThrow();search.setValue("aspect");
                stage=4;at=ticks;return;
            }
            if(stage==4) {
                screenshot("search-scale-4.png");mc.options.guiScale().set(2);mc.getWindow().setWindowed(960,540);mc.resizeDisplay();stage=11;at=ticks;return;
            }
            if(stage==11) {
                screenshot("window-960x540.png");check(mc.getWindow().getWidth()==960&&mc.getWindow().getHeight()==540,"Window resized to 960x540");
                mc.getWindow().setWindowed(1920,1080);mc.resizeDisplay();stage=12;at=ticks;return;
            }
            if(stage==12) {
                mc.options.guiScale().set(4);mc.resizeDisplay();stage=13;at=ticks;return;
            }
            if(stage==13) {
                screenshot("window-1920x1080-scale-4.png");check(mc.getWindow().getGuiScale()==4,"Actual GUI scale 4");
                mc.options.guiScale().set(2);mc.getWindow().setWindowed(1280,720);mc.resizeDisplay();
                SettingsClient.open(new TitleScreen(),"thaumcraft");
                enumDocument=NeoForgeConfigs.documents("thaumcraft").stream().filter(d->d.scope()==net.neoforged.fml.config.ModConfig.Type.CLIENT).findFirst().orElseThrow();
                enumOption=enumDocument.options().stream().filter(o->o.kind().equals("enum")&&o.category().equals("interface")&&o.choices().size()>1).findFirst().orElseThrow();
                enumBefore=enumDocument.snapshot();
                var choice=mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getY()>=86&&b.getY()<mc.screen.height-46&&b.getX()>mc.screen.width/4&&b.getWidth()>30&&!b.getMessage().getString().equals(Component.translatable("options.off").getString())&&!b.getMessage().getString().equals(Component.translatable("options.on").getString())).findFirst().orElseThrow();
                choice.onPress();stage=14;at=ticks;return;
            }
            if(stage==14){
                check(mc.screen.getClass().getSimpleName().equals("DropdownMenu"),"Enum opens anchored dropdown");screenshot("dropdown.png");
                int selectedIndex=enumOption.choices().indexOf(enumBefore.getAsJsonObject("values").get(enumOption.path()).getAsString());
                mc.screen.keyPressed(selectedIndex==enumOption.choices().size()-1?org.lwjgl.glfw.GLFW.GLFW_KEY_UP:org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN,0,0);
                mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0);
                check(mc.screen instanceof SettingsScreen,"Dropdown selection returns to editor");
                var after=enumDocument.snapshot();
                check(!after.get("revision").equals(enumBefore.get("revision")),"Dropdown keyboard selection saved immediately");
                var restore=new JsonObject();restore.add(enumOption.path(),enumBefore.getAsJsonObject("values").get(enumOption.path()));
                enumDocument.apply(after.get("revision").getAsString(),restore);
                check(enumDocument.snapshot().get("revision").equals(enumBefore.get("revision")),"Enum preference restored");
                mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getY()>=86&&b.getY()<mc.screen.height-46&&b.getX()>mc.screen.width/4&&b.getWidth()>30).findFirst().orElseThrow().onPress();
                mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
                check(mc.screen instanceof SettingsScreen&&enumDocument.snapshot().get("revision").equals(enumBefore.get("revision")),"Escape dismisses dropdown without changing or saving values");
                mc.getLanguageManager().setSelected("ru_ru");mc.options.languageCode="ru_ru";mc.reloadResourcePacks();stage=5;at=ticks;return;
            }
            if(stage==5) {
                if(mc.getOverlay()!=null)return;
                if(!net.minecraft.client.resources.language.I18n.get("aopui.local").equals("Клиент"))return;
                SettingsClient.open(new TitleScreen(),providers.contains("thaumcraft")?"thaumcraft":providers.getFirst());stage=6;at=ticks;return;
            }
            if(stage==6) {
                screenshot("russian.png");check(net.minecraft.client.resources.language.I18n.get("aopui.save").equals("Сохранить"),"Russian resource reload");
                mc.screen.onClose();check(mc.screen instanceof TitleScreen,"Back restores parent");
                SettingsClient.open(new TitleScreen(),"thaumcraft");focusFirstSetting(mc);stage=20;at=ticks;return;
            }
            if(stage==20){
                screenshot("translucent-background.png");
                try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){
                    int px=12*image.getWidth()/mc.screen.width,py=(mc.screen.height-48)*image.getHeight()/mc.screen.height;
                    int green=(image.getPixelRGBA(px,py)>>>8)&255;
                    check(green>45&&green<125,"GPU background is visible through the settings panel: green="+green);
                }
                var help=SettingsScreen.class.getDeclaredField("helpRow");help.setAccessible(true);
                check(help.get(mc.screen)!=null,"Keyboard focus selects the description pane content");
                var edge=SettingsScreen.class.getDeclaredField("contentRight");edge.setAccessible(true);int right=edge.getInt(mc.screen);
                check(right<mc.screen.width-100,"Wide layout reserves a separate description column");
                check(mc.screen.children().stream().filter(c->c instanceof AbstractWidget).map(c->(AbstractWidget)c).filter(w->w.getX()>mc.screen.width/4&&w.getY()>=86&&w.getY()<mc.screen.height-46).allMatch(w->w.getRight()<=right),"Option controls do not overlap the description column");
                mc.screen.onClose();finish(true,null);mc.stop();
            }
        } catch(Throwable e){com.mojang.logging.LogUtils.getLogger().error("AOP UI client verification failed",e);finish(false,e.toString());mc.stop();}
    }
    private static void focusFirstSetting(Minecraft mc){
        mc.screen.children().stream().filter(c->c instanceof AbstractWidget).map(c->(AbstractWidget)c)
            .filter(w->w.getX()>mc.screen.width/4&&w.getY()>=86&&w.getY()<mc.screen.height-46&&w.getWidth()>30)
            .findFirst().ifPresent(w->{mc.screen.setFocused(w);w.setFocused(true);});
    }
    private static void testSelector(Minecraft mc){
        var editor=mc.screen;
        var selector=editor.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals("Thaumcraft")).findFirst().orElseThrow();
        check(editor.children().stream().noneMatch(c->c instanceof Button b&&b.getMessage().getString().equals("AOP:TC4 Compact")),"Collapsed selector shows one mod");
        selector.onPress();check(mc.screen==editor,"Mod selector expands in place");
        var compact=editor.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals("AOP:TC4 Compact")).findFirst().orElseThrow();compact.onPress();
        check(editor.children().stream().noneMatch(c->c instanceof Button b&&b.getMessage().getString().equals("Thaumcraft")),"Choosing mod collapses selector");
        editor.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals("AOP:TC4 Compact")).findFirst().orElseThrow().onPress();
        editor.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals("Thaumcraft")).findFirst().orElseThrow().onPress();
    }
    private static void testSearch(Minecraft mc){
        var editor=mc.screen;
        var search=editor.children().stream().filter(c->c instanceof EditBox).map(c->(EditBox)c).findFirst().orElseThrow();
        editor.setFocused(search);search.setFocused(true);
        for(char letter:"book".toCharArray()){
            check(editor.charTyped(letter,0),"Search accepts typed characters");
            check(editor.children().contains(search)&&editor.getFocused()==search,"Typing retains the active search widget");
        }
        check(search.getValue().equals("book"),"Search keeps the full typed query");
        search.setCursorPosition(1);search.setHighlightPos(3);
        check(search.getHighlighted().equals("oo"),"Search supports selecting text");
        editor.charTyped('a',0);
        check(search.getValue().equals("bak")&&search.getCursorPosition()==2,"Typing replaces selection without resetting the caret");
        search.setValue("  BOOK   FONT  ");
        check(editor.children().contains(search),"Search stays stable for multiword filtering");
        check(editor.children().stream().anyMatch(c->c instanceof Button b&&b.getMessage().getString().equals("Minecraft")),"Case-insensitive multiword search ignores surrounding whitespace");
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
        check(mc.screen==editor&&search.getValue().isEmpty(),"Escape clears search without leaving settings");
        search.setValue("does-not-exist");
        var clear=editor.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.visible&&b.getMessage().getString().equals(Component.translatable("aopui.search.clear").getString())).findFirst().orElseThrow();
        clear.onPress();
        check(search.getValue().isEmpty()&&editor.getFocused()==search&&!clear.visible,"Clear search restores results and keeps keyboard focus");
        screenshotSearch();
    }
    private static void testTextInput(Minecraft mc){
        var editor=mc.screen;
        var field=editor.children().stream().filter(c->c instanceof EditBox b&&b.isBordered()).map(c->(EditBox)c).findFirst().orElseThrow();
        String original=field.getValue();
        var documents=NeoForgeConfigs.documents("thaumictinkerer").stream().filter(d->d.spec().isLoaded()).toList();
        var revisions=documents.stream().map(d->d.snapshot().get("revision").getAsString()).toList();
        editor.mouseClicked(field.getRight()-5,field.getY()+field.getHeight()/2,0);
        check(editor.getFocused()==field&&field.getCursorPosition()==original.length(),"Themed input maps clicks to the native caret");
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
        check(field.getCursorPosition()==0,"Themed input retains keyboard caret navigation");
        field.setHighlightPos(original.length());
        check(field.getHighlighted().equals(original),"Themed input retains text selection");
        editor.charTyped('x',0);
        check(field.getValue().equals("x"),"Themed input accepts invalid draft text for correction");
        editor.onClose();check(mc.screen==editor,"Invalid input keeps the editor open");
        field.setValue(original);
        check(documents.stream().map(d->d.snapshot().get("revision").getAsString()).toList().equals(revisions),"Correcting invalid input preserves existing configuration values");
    }
    private static void screenshotSearch(){
        try{screenshot("search-focus.png");}catch(Exception e){throw new RuntimeException(e);}
    }
    private static void testDependencies(Minecraft mc){
        var d=NeoForgeConfigs.documents("aop_ui_verification").stream().filter(v->v.scope()==net.neoforged.fml.config.ModConfig.Type.CLIENT).findFirst().orElseThrow();var before=d.snapshot();
        // Parent and children are grouped after the other interface options.
        var parent=mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals(Component.translatable("options.on").getString())).findFirst().orElseThrow();parent.onPress();
        check(!d.snapshot().getAsJsonObject("values").get("interface.tooltips").getAsBoolean(),"Master switch saved");
        check(d.snapshot().getAsJsonObject("values").get("interface.portalTooltips").equals(before.getAsJsonObject("values").get("interface.portalTooltips")),"Child preference retained by GUI");
        check(mc.screen.children().stream().anyMatch(c->c instanceof Button b&&!b.active&&b.getMessage().getString().equals(Component.translatable("options.on").getString())),"Stored enabled child visibly disabled by master");
        int y=parent.getY();mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getY()==y&&b.getWidth()==parent.getWidth()).findFirst().orElseThrow().onPress();
        check(d.snapshot().get("revision").equals(before.get("revision")),"Restoring master restores preferences exactly");
    }
    private static void toggleAndRestore(Minecraft mc,String id) {
        var d=NeoForgeConfigs.documents(id).stream().filter(c->c.scope()==net.neoforged.fml.config.ModConfig.Type.CLIENT).findFirst().orElseThrow();
        var before=d.snapshot();
        var button=mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals(Component.translatable("options.off").getString())).findFirst().orElseThrow();
        int x=button.getX(),y=button.getY();button.onPress();
        check(!d.snapshot().get("revision").equals(before.get("revision")),"Client change saved immediately");
        mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getX()==x&&b.getY()==y).findFirst().orElseThrow().onPress();
        check(d.snapshot().get("revision").equals(before.get("revision")),"Client value restored");
    }
    private static void screenshot(String name)throws Exception {
        try(var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){image.writeToFile(output().resolve(name));}
    }
    private static void finish(boolean passed,String error) {
        done=true;
        try {
            var result=new JsonObject();result.addProperty("passed",passed);result.addProperty("checks",checks);result.add("providers",results);if(error!=null)result.addProperty("error",error);
            Path file=Path.of(System.getProperty("aopui.report"));Files.createDirectories(file.getParent());Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch(Exception e){throw new RuntimeException(e);}
    }
}
